package com.example.lingualocal.audio

import android.content.Context
import android.util.Log
import com.example.lingualocal.AppState
import com.example.lingualocal.data.ProjectStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The people heard in each project: their voice fingerprints (on-device, [SpeakerId]) and the names the
 * user gave them. Saved with the project (people.json), so "Person 2" is the same person in every session
 * of a meeting series, and a renamed person keeps their name on every card.
 */
object People {
    private const val TAG = "People"
    private const val FILE = "people.json"

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelLock = Mutex()
    private val identifyLock = Mutex()
    private val fileLock = Any()
    @Volatile private var model: SpeakerId? = null
    /** Project whose voices the model holds now. */
    @Volatile private var modelProject: String? = null

    /** One project's people. */
    private class Data(val id: String) {
        @Volatile var voices: List<SpeakerId.Voice> = emptyList()
        val names = MutableStateFlow<Map<Int, String>>(emptyMap())
    }
    private val data = java.util.concurrent.ConcurrentHashMap<String, Data>()
    private fun data(id: String): Data = data.computeIfAbsent(id) { load(it) }

    /** Person number -> name the user gave them, in the project on screen ("Person N" when unnamed). */
    val names = MutableStateFlow<Map<Int, String>>(emptyMap())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun init(context: Context) {
        appContext = context.applicationContext
        scope.launch {
            AppState.view.map { it?.id }.distinctUntilChanged()
                .flatMapLatest { id -> if (id == null) flowOf(emptyMap()) else data(id).names }
                .collect { names.value = it }
        }
    }

    fun label(n: Int, names: Map<Int, String> = this.names.value): String =
        names[n]?.takeIf { it.isNotBlank() } ?: "Person $n"

    /** The fingerprint model, loaded once and kept (shared by every listening session). */
    suspend fun model(): SpeakerId = modelLock.withLock {
        model ?: SpeakerId(appContext).also { model = it }
    }

    /**
     * Person number for a stretch of 16 kHz PCM16 speech with [voicedMs] of voice, matched against everyone
     * heard in project [projectId]; a clearly new voice becomes a new person. Null when too short to be sure.
     */
    suspend fun identify(projectId: String, pcm: ByteArray, voicedMs: Long): Int? = identifyLock.withLock {
        val d = data(projectId)
        val m = model()
        if (modelProject != projectId) { m.restore(d.voices); modelProject = projectId }
        val who = m.identify(pcm, voicedMs)
        d.voices = m.snapshot()
        save(d)
        who
    }

    /** Names person [n] in the project on screen. */
    fun rename(n: Int, name: String) {
        val id = AppState.view.value?.id ?: return
        val d = data(id)
        d.names.update { it + (n to name.trim()) }
        scope.launch { save(d) }
    }

    private fun load(id: String): Data {
        val d = Data(id)
        runCatching {
            val f = file(id) ?: return@runCatching
            if (!f.isFile) return@runCatching
            val a = JSONObject(f.readText()).optJSONArray("people") ?: JSONArray()
            val n = mutableMapOf<Int, String>()
            val v = mutableListOf<SpeakerId.Voice>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val num = o.getInt("n")
                o.optString("name").takeIf { it.isNotBlank() }?.let { n[num] = it }
                val sum = o.optJSONArray("sum") ?: continue
                v += SpeakerId.Voice(num, FloatArray(sum.length()) { sum.getDouble(it).toFloat() }, o.optInt("count", 1))
            }
            d.names.value = n; d.voices = v
        }.onFailure { Log.w(TAG, "can't read people of $id: $it") }
        Log.d(TAG, "project $id: ${d.voices.size} known voices, ${d.names.value.size} names")
        return d
    }

    private fun save(d: Data) = synchronized(fileLock) {
        val f = file(d.id) ?: return@synchronized
        if (f.parentFile?.isDirectory != true) return@synchronized // project deleted
        val voices = d.voices
        val names = d.names.value
        val nums = (voices.map { it.number } + names.keys).distinct().sorted()
        val a = JSONArray()
        for (num in nums) {
            val o = JSONObject().put("n", num)
            names[num]?.let { o.put("name", it) }
            voices.firstOrNull { it.number == num }?.let { v ->
                o.put("count", v.count)
                o.put("sum", JSONArray().apply { v.sum.forEach { put(it.toDouble()) } })
            }
            a.put(o)
        }
        runCatching {
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(JSONObject().put("people", a).toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Log.w(TAG, "can't save people: $it") }
    }

    private fun file(id: String): File? = ProjectStore.projectDir(id)?.let { File(it, FILE) }
}
