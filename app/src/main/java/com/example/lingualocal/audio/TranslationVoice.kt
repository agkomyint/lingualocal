package com.example.lingualocal.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.lingualocal.AppState
import com.example.lingualocal.Caption
import com.example.lingualocal.ProjectSpace
import com.example.lingualocal.SegmentStatus
import com.example.lingualocal.ai.CloudVoice
import com.example.lingualocal.data.Lang
import com.example.lingualocal.data.ProjectStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Saves every finished translation as spoken audio next to its recording, whether or not anyone plays it,
 * so projects can be listened to offline later. Each language uses the voice picked in Settings: the phone's
 * (free, offline) or Gemini's, which waits for internet. Languages the phone has no voice for use Gemini's.
 * A translation that changes later gets a new recording.
 * In a one-language project every piece, typed or spoken, is saved in Gemini's voice.
 */
object TranslationVoice {
    private const val TAG = "TranslationVoice"
    private const val RETRY_MS = 120_000L
    /** Background catch-up only voices pieces this recent; older ones get a voice when tapped. Gemini's voice is paid. */
    private const val CATCH_UP_WINDOW_MS = 10 * 60_000L

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tts: TextToSpeech? = null
    private val pending = ConcurrentHashMap<String, (Boolean) -> Unit>()
    /** caption id + text -> when it last failed, so a missing connection isn't retried every second. */
    private val failed = ConcurrentHashMap<String, Long>()
    private val tries = ConcurrentHashMap<String, Int>()
    /** Pieces seen unfinished or recent: Gemini's voice is made when it can be, however late that is. */
    private val due = HashSet<String>()
    private const val MAX_LATE_TRIES = 5

    fun init(context: Context) {
        appContext = context.applicationContext
        scope.launch {
            while (true) {
                val before = changes().first()
                runCatching { catchUp() }.onFailure { Log.w(TAG, "catch-up failed: $it") }
                // Run again when the pieces change (also while voices were being made), or every two minutes to retry.
                withTimeoutOrNull(RETRY_MS) { changes().first { it != before } }
                delay(1_500)
            }
        }
    }

    /** The projects worth voicing now: recorded, on screen, and ones just left (for Gemini's voices). */
    private fun spaces() = (listOfNotNull(AppState.live.value, AppState.view.value) + ProjectStore.openSpaces()).distinct()

    /** Emits whenever the pieces of those projects change. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun changes(): kotlinx.coroutines.flow.Flow<Pair<Map<String, VoiceChoice>, List<List<Caption>>>> =
        kotlinx.coroutines.flow.combine(AppState.live, AppState.view, AppState.voices) { _, _, v -> v to spaces() }
            .flatMapLatest { (v, ss) ->
                if (ss.isEmpty()) kotlinx.coroutines.flow.flowOf(v to emptyList())
                else kotlinx.coroutines.flow.combine(ss.map { it.captions }) { v to it.toList() }
            }

    fun online(): Boolean {
        val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** The saved voice for [c], if it was made from [text] (the translation as it is now). */
    fun saved(c: Caption, text: String): String? =
        c.voiceFile?.takeIf { c.voiceText == text.trim() && File(it).isFile }

    fun spokenText(c: Caption): String = c.spokenText

    /** A piece of a one-language project: always Gemini's voice, even where the phone has one. */
    fun alwaysGemini(project: ProjectSpace, c: Caption): Boolean = c.textOnly && project.pair.transcribeOnly

    /** Gemini's voice is wanted for this piece: the setting of the language being spoken. */
    fun wantsGemini(project: ProjectSpace?, c: Caption?, text: String): Boolean = VoiceRules.wantsGemini(
        project != null && c != null && alwaysGemini(project, c),
        AppState.voices.value[Lang.of(text)],
    )

    /** Gemini's voice is made for this piece without being asked. */
    fun autoGemini(project: ProjectSpace, c: Caption, text: String): Boolean = VoiceRules.autoGemini(
        alwaysGemini(project, c),
        AppState.voices.value[Lang.of(text)],
        c.time,
        untranslatedSpeech = c.source != Caption.SOURCE_TYPED && c.textOnly,
    )

    /** Whether the saved voice of [c] is Gemini's. */
    fun savedIsGemini(project: ProjectSpace, c: Caption): Boolean =
        VoiceRules.madeBy(c.voiceEngine, Lang.of(c.voiceText), alwaysGemini(project, c)) == VoiceEngine.GEMINI

    private var voicesChecked: Map<String, VoiceChoice>? = null

    /** Cards already found with their voice saved (same object = unchanged), so long projects aren't rescanned. */
    private val checkedBy = HashMap<String, HashMap<Long, Caption>>()

    private suspend fun catchUp() {
        val spaces = spaces()
        val voices = AppState.voices.value
        if (voices != voicesChecked) checkedBy.clear()
        voicesChecked = voices
        checkedBy.keys.retainAll(spaces.map { it.id }.toSet())
        spaces.forEach { catchUp(it) }
    }

    private suspend fun catchUp(project: ProjectSpace) {
        val checked = checkedBy.getOrPut(project.id) { HashMap() }
        val active = project === AppState.live.value || project === AppState.view.value
        for (c in project.captions.value) {
            if (checked[c.id] === c) continue
            val recent = c.time >= System.currentTimeMillis() - CATCH_UP_WINDOW_MS
            val k = "${project.id}|${c.id}"
            if (recent || c.status != SegmentStatus.DONE) due += k
            if (c.status != SegmentStatus.DONE) continue
            val text = spokenText(c)
            val auto = autoGemini(project, c, text)
            if (!recent && !(auto && k in due && (tries["$k|${text.hashCode()}"] ?: 0) < MAX_LATE_TRIES)) { checked[c.id] = c; continue }
            // A project that was left only keeps saving Gemini's voices.
            if (!active && !auto) continue
            // Untranslated speech in a translation project isn't paid for.
            if (c.source != Caption.SOURCE_TYPED && c.textOnly && !auto) continue
            if (text.isBlank()) continue
            val have = saved(c, text)
            if (have != null && (!auto || savedIsGemini(project, c))) {
                // Saved uncompressed by an earlier version: shrink it.
                if (have.endsWith(".wav")) shrink(c.id, File(have), project) else checked[c.id] = c
                continue
            }
            if ((failed["${project.id}|${c.id}|${text.hashCode()}"] ?: 0L) > System.currentTimeMillis() - RETRY_MS) continue
            // Waiting for internet isn't a failed try.
            if (auto && !online()) continue
            ensure(c.id, text, project, auto)
        }
    }

    /** Requests being made now, so the live voice and the catch-up never pay for the same piece twice. */
    private val inFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<File?>>()

    /**
     * The saved voice of piece [id] for [text], made now if it isn't saved yet (once, however many callers ask).
     * [wantGemini]: a saved phone voice won't do.
     */
    private suspend fun ensure(id: Long, text: String, project: ProjectSpace, wantGemini: Boolean): File? {
        project.caption(id)?.let { c ->
            saved(c, text)?.takeIf { !wantGemini || savedIsGemini(project, c) }?.let { return File(it) }
        }
        // Without a phone voice it's Gemini's either way, and must be one request.
        val gemini = wantGemini || !phoneSpeaks(Lang.of(text))
        val key = "${project.id}|$id|${text.hashCode()}"
        // Atomic, unlike getOrPut: the live voice and the catch-up can ask at the same moment.
        val job = inFlight.computeIfAbsent("$key|$gemini") {
            scope.async {
                try {
                    // An identical request may have finished since the caller looked: never pay for it again.
                    project.caption(id)?.let { c ->
                        saved(c, text)?.takeIf { !wantGemini || savedIsGemini(project, c) }?.let { return@async File(it) }
                    }
                    val dir = ProjectStore.audioDir(project)
                    val file = runCatching { make(text, dir, id, gemini) }.onFailure { Log.w(TAG, "piece $id: $it") }.getOrNull()
                    if (file == null) {
                        failed[key] = System.currentTimeMillis()
                        tries.merge(key, 1, Int::plus)
                        return@async null
                    }
                    // The translation may have changed (or the piece gone) while this was being made.
                    val now = project.caption(id)
                    if (now == null || spokenText(now) != text) { file.delete(); return@async null }
                    if (!gemini) saved(now, text)?.takeIf { it != file.absolutePath && savedIsGemini(project, now) }?.let {
                        // Gemini's arrived meanwhile: a paid voice isn't replaced by the phone's.
                        file.delete()
                        return@async File(it)
                    }
                    now.voiceFile?.takeIf { it != file.absolutePath }?.let { File(it).delete() }
                    val engine = if (gemini) VoiceEngine.GEMINI else VoiceEngine.DEVICE
                    project.updateCaption(id) { it.copy(voiceFile = file.absolutePath, voiceText = text, voiceEngine = engine.id) }
                    Log.d(TAG, "saved voice for piece $id (${file.length() / 1024} KB)")
                    file
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The callers play and listen: they get no voice, never a crash.
                    Log.w(TAG, "can't save the voice of piece $id", e)
                    failed[key] = System.currentTimeMillis()
                    tries.merge(key, 1, Int::plus)
                    null
                } finally {
                    inFlight.remove("$key|$gemini")
                }
            }
        }
        return job.await()
    }

    private val playLock = kotlinx.coroutines.sync.Mutex()
    @Volatile private var player: android.media.MediaPlayer? = null

    /** Plays a saved voice to the end, for live "Speak translations". Pieces play one after another. */
    suspend fun playSaved(path: String) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        playLock.withLock { playToEnd(File(path)) }
    }

    /** Saved voice of piece [id] of [project], made now (once, then kept) if missing. Null if it can't be made. */
    suspend fun voiceFile(project: ProjectSpace, id: Long, gemini: Boolean): String? {
        val c = project.caption(id) ?: return null
        val text = spokenText(c)
        if (text.isBlank()) return null
        return ensure(id, text, project, gemini)?.absolutePath
    }

    fun stopSpeaking() {
        player?.let { p -> runCatching { p.stop() } }
    }

    private suspend fun playToEnd(file: File) {
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        val p = android.media.MediaPlayer()
        player = p
        try {
            p.setAudioAttributes(SegmentAudio.playbackAttributes())
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener { done.complete(Unit) }
            p.setOnErrorListener { _, _, _ -> done.complete(Unit); true }
            p.prepare()
            p.start()
            withTimeoutOrNull(p.duration.toLong().coerceAtLeast(0) + 3_000) { done.await() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "can't play ${file.name}: $e")
        } finally {
            player = null
            runCatching { p.release() }
        }
    }

    /** Speaks [text] into a compressed file in [dir] (AAC .m4a; WAV only if the encoder fails). */
    private suspend fun make(text: String, dir: File, id: Long, gemini: Boolean): File {
        val lang = Lang.of(text)
        // Gemini's voice needs internet.
        if (gemini) return store(CloudVoice.record(text), CloudVoice.RATE_HZ, dir, "segment_${id}_gemini")
        val engine = engine() ?: error("no phone voice")
        check(engine.setLanguage(Lang.locale(lang)) >= TextToSpeech.LANG_AVAILABLE) { "no phone voice for $lang" }
        engine.setSpeechRate(1f)
        val tmp = File(dir, "segment_${id}_voice.tmp")
        try {
            if (toFile(engine, text, tmp)) readWav(tmp)?.let { (pcm, rate) -> return store(pcm, rate, dir, "segment_${id}_voice") }
        } finally { tmp.delete() }
        // Device was picked, so this is never a reason to pay.
        error("the phone's voice made no sound")
    }

    /** Each engine has its own file [name], so neither writes over the other's. */
    private fun store(pcm: ByteArray, rate: Int, dir: File, name: String): File {
        val m4a = File(dir, "$name.m4a")
        val ok = runCatching { AacEncoder.encode(pcm, rate, m4a) }.onFailure { Log.w(TAG, "AAC failed, keeping WAV: $it") }.isSuccess
        if (ok && m4a.length() > 0) return m4a
        m4a.delete()
        return File(dir, "$name.wav").apply {
            outputStream().use { it.write(SegmentAudio.wavHeader(pcm.size, rate)); it.write(pcm) }
        }
    }

    private fun shrink(id: Long, wav: File, project: ProjectSpace) {
        val (pcm, rate) = readWav(wav) ?: return
        val m4a = File(wav.parentFile, "${wav.nameWithoutExtension}.m4a")
        if (runCatching { AacEncoder.encode(pcm, rate, m4a) }.isFailure || m4a.length() == 0L) { m4a.delete(); return }
        project.updateCaption(id) { if (it.voiceFile == wav.absolutePath) it.copy(voiceFile = m4a.absolutePath) else it }
        wav.delete()
        Log.d(TAG, "compressed voice for piece $id: ${m4a.length() / 1024} KB")
    }

    /** The samples and rate of a 16-bit mono PCM WAV file, or null if it's anything else. */
    private fun readWav(f: File): Pair<ByteArray, Int>? {
        val b = f.readBytes()
        if (b.size < 44 || String(b, 0, 4) != "RIFF" || String(b, 8, 4) != "WAVE") return null
        val buf = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = 0; var channels = 0; var bits = 0
        while (pos + 8 <= b.size) {
            val name = String(b, pos, 4)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            if (name == "fmt ") { channels = buf.getShort(body + 2).toInt(); rate = buf.getInt(body + 4); bits = buf.getShort(body + 14).toInt() }
            if (name == "data") {
                if (channels != 1 || bits != 16 || rate <= 0) return null
                val end = if (size <= 0 || body + size > b.size) b.size else body + size
                return b.copyOfRange(body, end) to rate
            }
            if (size < 0) return null
            pos = body + size + (size and 1)
        }
        return null
    }

    private val engineLock = kotlinx.coroutines.sync.Mutex()

    /** Whether the phone has a voice for [lang]. */
    suspend fun phoneSpeaks(lang: String): Boolean {
        val e = engine() ?: return false
        return e.isLanguageAvailable(Lang.locale(lang)) >= TextToSpeech.LANG_AVAILABLE
    }

    private suspend fun engine(): TextToSpeech? = engineLock.withLock { startEngine() }

    private suspend fun startEngine(): TextToSpeech? {
        tts?.let { return it }
        var made: TextToSpeech? = null
        val ok = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont -> made = TextToSpeech(appContext) { cont.resume(it == TextToSpeech.SUCCESS) } }
        } == true
        val t = made ?: return null
        if (!ok) { t.shutdown(); return null }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { id?.let { pending.remove(it)?.invoke(true) } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { id?.let { pending.remove(it)?.invoke(false) } }
        })
        tts = t
        return t
    }

    private suspend fun toFile(engine: TextToSpeech, text: String, out: File): Boolean {
        val id = "voice-${System.nanoTime()}"
        val done = withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine { cont ->
                pending[id] = { ok -> if (cont.isActive) cont.resume(ok) }
                cont.invokeOnCancellation { pending.remove(id) }
                if (engine.synthesizeToFile(text, null, out, id) != TextToSpeech.SUCCESS) pending.remove(id)?.invoke(false)
            }
        } == true
        return done && out.length() > 44
    }
}
