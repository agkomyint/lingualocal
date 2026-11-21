package com.example.lingualocal.data

import android.content.Context
import android.util.Log
import com.example.lingualocal.AppState
import com.example.lingualocal.Caption
import com.example.lingualocal.Project
import com.example.lingualocal.SegmentStatus
import com.example.lingualocal.Revision
import com.example.lingualocal.Turn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Projects live in the app's private storage, one folder each, kept until the user deletes them:
 *   files/projects/<id>/project.json   name, dates, totals
 *   files/projects/<id>/captions.json  every piece: what was heard, translation, explanation, speaker…
 *   files/projects/<id>/audio/         the recordings (AAC .m4a)
 */
object ProjectStore {
    private const val TAG = "ProjectStore"
    /** Version of the saved format. 2 = every piece has lang/target/engine. */
    const val SCHEMA = 2
    private lateinit var root: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @OptIn(FlowPreview::class)
    fun init(context: Context) {
        root = File(context.filesDir, "projects").apply { mkdirs() }
        refresh()
        // Save the open project shortly after anything in it changes.
        scope.launch {
            combine(AppState.currentProject, AppState.captions) { p, c -> p to c }
                .debounce(800)
                .collect { (p, c) -> if (p != null) save(p, c) }
        }
    }

    fun refresh() {
        AppState.projects.value = root.listFiles()?.mapNotNull { readMeta(it) }?.sortedByDescending { it.lastUsed }.orEmpty()
    }

    /** New project; a blank name becomes "Untitled · 21 Nov 14:05". */
    fun create(name: String?): Project {
        val now = System.currentTimeMillis()
        val p = Project(
            id = "p$now",
            name = name?.trim()?.takeIf { it.isNotEmpty() }
                ?: ("Untitled · " + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(now))),
            created = now, lastUsed = now,
            // New projects use the language pair of the project used last.
            pair = AppState.nextPair.value?.code ?: AppState.projects.value.firstOrNull()?.pair ?: LangPair.TH_EN.code,
        )
        synchronized(lock) { writeMeta(p) }
        refresh()
        return p
    }

    fun open(p: Project) {
        flush()
        AppState.currentProject.value = null
        AppState.captions.value = synchronized(lock) { loadCaptions(p.id) }
        AppState.continueIds()
        val opened = (readMeta(dir(p.id)) ?: p).copy(lastUsed = System.currentTimeMillis())
        synchronized(lock) { writeMeta(opened) }
        AppState.currentProject.value = opened
        Log.d(TAG, "opened ${p.id} \"${p.name}\" with ${AppState.captions.value.size} pieces")
    }

    fun close() {
        flush()
        AppState.currentProject.value = null
        AppState.captions.value = emptyList()
        refresh()
    }

    fun rename(p: Project, name: String) {
        val n = name.trim().takeIf { it.isNotEmpty() } ?: return
        val updated = (readMeta(dir(p.id)) ?: p).copy(name = n)
        synchronized(lock) { writeMeta(updated) }
        if (AppState.currentProject.value?.id == p.id) AppState.currentProject.value = updated
        refresh()
    }

    /** Switches the language pair; pieces already recorded keep their languages. */
    fun setPair(p: Project, pair: LangPair) {
        val updated = (readMeta(dir(p.id)) ?: p).copy(pair = pair.code)
        synchronized(lock) { writeMeta(updated) }
        if (AppState.currentProject.value?.id == p.id) AppState.currentProject.value = updated
        refresh()
    }

    /** Deletes the project with all its recordings. */
    fun delete(p: Project) {
        if (AppState.currentProject.value?.id == p.id) {
            AppState.currentProject.value = null
            AppState.captions.value = emptyList()
        }
        synchronized(lock) { dir(p.id).deleteRecursively() }
        refresh()
    }

    /** Writes the open project now (when listening stops, before switching projects). */
    fun flush() {
        val p = AppState.currentProject.value ?: return
        save(p, AppState.captions.value)
    }

    /** Where recordings of the open project go. */
    fun audioDir(context: Context): File {
        val p = AppState.currentProject.value
        val d = if (p != null && ::root.isInitialized) File(dir(p.id), "audio") else File(context.filesDir, "segments")
        return d.apply { mkdirs() }
    }

    private fun dir(id: String) = File(root, id)

    private fun save(p: Project, captions: List<Caption>) = synchronized(lock) {
        val d = dir(p.id)
        if (!d.isDirectory) return@synchronized // deleted meanwhile
        runCatching {
            writeAtomic(File(d, "captions.json"), JSONArray().apply { captions.forEach { put(toJson(it)) } }.toString())
            val stored = readMeta(d) ?: p
            writeMeta(
                stored.copy(
                    lastUsed = maxOf(stored.lastUsed, captions.maxOfOrNull { it.time + it.durationMs } ?: 0),
                    items = captions.count { it.heard.isNotBlank() || it.turns.isNotEmpty() },
                    sessions = captions.map { it.session }.distinct().size,
                    totalMs = captions.sumOf { it.durationMs },
                )
            )
        }.onFailure { Log.e(TAG, "save ${p.id} failed", it) }
    }

    private fun loadCaptions(id: String): List<Caption> = runCatching {
        val f = File(dir(id), "captions.json")
        if (!f.isFile) return emptyList()
        val audio = File(dir(id), "audio")
        val a = JSONArray(f.readText())
        (0 until a.length()).map { fromJson(a.getJSONObject(it), audio) }.map { c ->
            // Pieces that were still being translated when the app closed.
            if (c.done) c else c.copy(
                status = if (c.translation.isNotBlank()) SegmentStatus.DONE else SegmentStatus.NO_SPEECH,
                note = if (c.translation.isNotBlank()) null else "Not translated · the app was closed",
            )
        }
    }.getOrElse { Log.e(TAG, "load $id failed", it); emptyList() }

    private fun readMeta(d: File): Project? = runCatching {
        val j = JSONObject(File(d, "project.json").readText())
        Project(
            id = d.name, name = j.getString("name"), created = j.getLong("created"), lastUsed = j.getLong("lastUsed"),
            items = j.optInt("items"), sessions = j.optInt("sessions"), totalMs = j.optLong("totalMs"),
            pair = LangPair.of(j.optString("pair")).code,
        )
    }.getOrNull()

    private fun writeMeta(p: Project) {
        val d = dir(p.id).apply { mkdirs() }
        writeAtomic(
            File(d, "project.json"),
            JSONObject().put("schema", SCHEMA).put("name", p.name).put("created", p.created).put("lastUsed", p.lastUsed)
                .put("items", p.items).put("sessions", p.sessions).put("totalMs", p.totalMs).put("pair", p.pair).toString(),
        )
    }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    fun toJson(c: Caption): JSONObject = JSONObject().apply {
        put("id", c.id); put("time", c.time); put("durationMs", c.durationMs); put("status", c.status.name)
        // Direction is stored explicitly (th -> en or en -> th) so the pieces can be used as training pairs later.
        val lang = c.lang.ifEmpty { Lang.of(c.heard) }
        if (lang.isNotEmpty()) { put("lang", lang); put("target", Lang.target(lang, c.translation.ifBlank { c.turns.firstOrNull()?.translation.orEmpty() })) }
        put("heard", c.heard); put("translation", c.translation)
        if (c.engine.isNotEmpty()) put("engine", c.engine)
        c.audioFile?.let { put("audio", File(it).name) } // by name: the folder may move (backup/restore)
        c.explanation?.let { put("explanation", it) }
        c.explainSource?.let { put("explainSource", it) }
        put("mine", c.mine)
        c.speaker?.let { put("speaker", it) }
        if (c.turns.isNotEmpty()) put("turns", JSONArray().apply {
            c.turns.forEach {
                val lang = it.lang.ifEmpty { Lang.of(it.heard) }
                put(JSONObject().put("speaker", it.speaker).put("lang", lang).put("target", Lang.target(lang, it.translation))
                    .put("heard", it.heard).put("translation", it.translation))
            }
        })
        c.note?.let { put("note", it) }
        put("offline", c.offline)
        put("session", c.session)
        // Every earlier version, oldest first: (replaced text, why, by whom) pairs for fine-tuning.
        if (c.revisions.isNotEmpty()) put("revisions", JSONArray().apply {
            c.revisions.forEach { r ->
                put(JSONObject().apply {
                    put("heard", r.heard); put("translation", r.translation)
                    if (r.lang.isNotEmpty()) { put("lang", r.lang); put("target", Lang.target(r.lang, r.translation)) }
                    put("engine", r.engine)
                    if (r.turns.isNotEmpty()) put("turns", JSONArray().apply {
                        r.turns.forEach { put(JSONObject().put("speaker", it.speaker).put("lang", it.lang).put("heard", it.heard).put("translation", it.translation)) }
                    })
                    put("reason", r.reason); put("by", r.by); put("time", r.time)
                })
            }
        })
    }

    fun fromJson(j: JSONObject, audioDir: File): Caption = Caption(
        id = j.getLong("id"),
        time = j.getLong("time"),
        durationMs = j.optLong("durationMs"),
        status = runCatching { SegmentStatus.valueOf(j.getString("status")) }.getOrDefault(SegmentStatus.DONE),
        heard = j.optString("heard"),
        translation = j.optString("translation"),
        audioFile = j.optString("audio").takeIf { it.isNotEmpty() }?.let { File(audioDir, it) }?.takeIf { it.isFile }?.absolutePath,
        explanation = if (j.has("explanation")) j.getString("explanation") else null,
        explainSource = if (j.has("explainSource")) j.getString("explainSource") else null,
        mine = j.optBoolean("mine"),
        speaker = if (j.has("speaker")) j.getInt("speaker") else null,
        turns = j.optJSONArray("turns")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }
                .map { Turn(it.optString("speaker"), it.optString("heard"), it.optString("translation"), it.optString("lang").ifEmpty { Lang.of(it.optString("heard")) }) }
        }.orEmpty(),
        note = if (j.has("note")) j.getString("note") else null,
        offline = j.optBoolean("offline"),
        session = j.optLong("session"),
        // Older saves have no language: tell it from the script of what was heard.
        lang = j.optString("lang").ifEmpty { Lang.of(j.optString("heard")) },
        engine = j.optString("engine").ifEmpty { if (j.optBoolean("offline")) Lang.DEVICE_ENGINE else "" },
        revisions = j.optJSONArray("revisions")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { k ->
                Revision(
                    heard = k.optString("heard"),
                    translation = k.optString("translation"),
                    lang = k.optString("lang"),
                    engine = k.optString("engine"),
                    turns = k.optJSONArray("turns")?.let { a ->
                        (0 until a.length()).map { a.getJSONObject(it) }
                            .map { Turn(it.optString("speaker"), it.optString("heard"), it.optString("translation"), it.optString("lang")) }
                    }.orEmpty(),
                    reason = k.optString("reason"),
                    by = k.optString("by"),
                    time = k.optLong("time"),
                )
            }
        }.orEmpty(),
    )
}
