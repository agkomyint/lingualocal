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
import kotlinx.coroutines.flow.drop
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
 * so projects can be listened to offline later. Thai/English use the phone's voice (free, offline);
 * languages it has no voice for (Burmese) use Gemini's voice and wait for internet.
 * A translation that changes later gets a new recording.
 */
object TranslationVoice {
    private const val TAG = "TranslationVoice"
    private const val RETRY_MS = 120_000L
    /** Background catch-up only voices pieces this recent; older ones get a voice when tapped. Burmese uses Gemini's paid voice. */
    private const val CATCH_UP_WINDOW_MS = 10 * 60_000L

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tts: TextToSpeech? = null
    private val pending = ConcurrentHashMap<String, (Boolean) -> Unit>()
    /** caption id + text -> when it last failed, so a missing connection isn't retried every second. */
    private val failed = ConcurrentHashMap<String, Long>()

    fun init(context: Context) {
        appContext = context.applicationContext
        scope.launch {
            while (true) {
                runCatching { catchUp() }.onFailure { Log.w(TAG, "catch-up failed: $it") }
                // Run again when the pieces change, or every two minutes to retry ones that needed internet.
                withTimeoutOrNull(RETRY_MS) { changes().drop(1).first() }
                delay(1_500)
            }
        }
    }

    /** The projects worth voicing now: the one being recorded and the one on screen (often the same). */
    private fun spaces() = listOfNotNull(AppState.live.value, AppState.view.value).distinct()

    /** Emits whenever the pieces of those projects change. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun changes(): kotlinx.coroutines.flow.Flow<Any> =
        kotlinx.coroutines.flow.combine(AppState.live, AppState.view) { a, b -> listOfNotNull(a, b).distinct() }
            .flatMapLatest { ss -> if (ss.isEmpty()) kotlinx.coroutines.flow.flow { kotlinx.coroutines.awaitCancellation() } else kotlinx.coroutines.flow.merge(*ss.map { it.captions }.toTypedArray()) }

    /** The saved voice for [c], if it was made from [text] (the translation as it is now). */
    fun saved(c: Caption, text: String): String? =
        c.voiceFile?.takeIf { c.voiceText == text.trim() && File(it).isFile }

    fun spokenText(c: Caption): String =
        if (c.turns.isNotEmpty()) c.turns.joinToString(" ") { it.translation }.trim() else c.translation.trim()

    /** Cards already found with their voice saved (same object = unchanged), so long projects aren't rescanned. */
    private val checkedBy = HashMap<String, HashMap<Long, Caption>>()

    private suspend fun catchUp() {
        val spaces = spaces()
        checkedBy.keys.retainAll(spaces.map { it.id }.toSet())
        spaces.forEach { catchUp(it) }
    }

    private suspend fun catchUp(project: ProjectSpace) {
        val checked = checkedBy.getOrPut(project.id) { HashMap() }
        for (c in project.captions.value) {
            if (checked[c.id] === c) continue
            if (c.status != SegmentStatus.DONE) continue
            if (c.time < System.currentTimeMillis() - CATCH_UP_WINDOW_MS) { checked[c.id] = c; continue }
            val text = spokenText(c)
            if (text.isBlank()) continue
            val have = saved(c, text)
            if (have != null) {
                // Saved uncompressed by an earlier version: shrink it.
                if (have.endsWith(".wav")) shrink(c.id, File(have), project) else checked[c.id] = c
                continue
            }
            if ((failed["${project.id}|${c.id}|${text.hashCode()}"] ?: 0L) > System.currentTimeMillis() - RETRY_MS) continue
            ensure(c.id, text, project)
        }
    }

    /** Requests being made now, so the live voice and the catch-up never pay for the same piece twice. */
    private val inFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<File?>>()

    /** The saved voice of piece [id] for [text], made now if it isn't saved yet (once, however many callers ask). */
    private suspend fun ensure(id: Long, text: String, project: ProjectSpace): File? {
        project.caption(id)?.let { c -> saved(c, text) }?.let { return File(it) }
        val key = "${project.id}|$id|${text.hashCode()}"
        val job = inFlight.getOrPut(key) {
            scope.async {
                try {
                    val dir = ProjectStore.audioDir(project)
                    val file = runCatching { make(text, dir, id) }.onFailure { Log.w(TAG, "piece $id: $it") }.getOrNull()
                    if (file == null) { failed[key] = System.currentTimeMillis(); return@async null }
                    // The translation may have changed (or the piece gone) while this was being made.
                    val now = project.caption(id)
                    if (now == null || spokenText(now) != text) { file.delete(); return@async null }
                    now.voiceFile?.takeIf { it != file.absolutePath }?.let { File(it).delete() }
                    project.updateCaption(id) { it.copy(voiceFile = file.absolutePath, voiceText = text) }
                    Log.d(TAG, "saved voice for piece $id (${file.length() / 1024} KB)")
                    file
                } finally {
                    inFlight.remove(key)
                }
            }
        }
        return job.await()
    }

    private val playLock = kotlinx.coroutines.sync.Mutex()
    private val playJobs = java.util.Collections.synchronizedSet(mutableSetOf<kotlinx.coroutines.Job>())
    @Volatile private var player: android.media.MediaPlayer? = null

    /**
     * Live "Speak translations" for a language the phone has no voice for (Burmese): makes the voice once,
     * saves it, and plays that saved copy. Pieces play one after another. [onDone] runs when it ends or fails.
     */
    fun speakPiece(id: Long, onDone: () -> Unit) {
        val job = scope.launch {
            try {
                val project = AppState.live.value ?: return@launch
                val c = project.caption(id) ?: return@launch
                val text = spokenText(c)
                if (text.isBlank()) return@launch
                val file = ensure(id, text, project) ?: return@launch
                playLock.withLock { playToEnd(file) }
            } finally {
                onDone()
            }
        }
        playJobs += job
        job.invokeOnCompletion { playJobs -= job }
    }

    /** Saved voice of piece [id] of [project], made now (once, then kept) if missing. Null if it can't be made. */
    suspend fun voiceFile(project: ProjectSpace, id: Long): String? {
        val c = project.caption(id) ?: return null
        val text = spokenText(c)
        if (text.isBlank()) return null
        return ensure(id, text, project)?.absolutePath
    }

    fun stopSpeaking() {
        playJobs.toList().forEach { it.cancel() }
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
    private suspend fun make(text: String, dir: File, id: Long): File {
        val lang = Lang.of(text)
        val engine = engine()
        if (engine != null && engine.setLanguage(Lang.locale(lang)) >= TextToSpeech.LANG_AVAILABLE) {
            engine.setSpeechRate(1f)
            val tmp = File(dir, "segment_${id}_voice.tmp")
            try {
                if (toFile(engine, text, tmp)) readWav(tmp)?.let { (pcm, rate) -> return store(pcm, rate, dir, id) }
            } finally { tmp.delete() }
        }
        // No phone voice for this language: Gemini's voice (needs internet).
        return store(CloudVoice.record(text), CloudVoice.RATE_HZ, dir, id)
    }

    private fun store(pcm: ByteArray, rate: Int, dir: File, id: Long): File {
        val m4a = File(dir, "segment_${id}_voice.m4a")
        val ok = runCatching { AacEncoder.encode(pcm, rate, m4a) }.onFailure { Log.w(TAG, "AAC failed, keeping WAV: $it") }.isSuccess
        if (ok && m4a.length() > 0) return m4a
        m4a.delete()
        return File(dir, "segment_${id}_voice.wav").apply {
            outputStream().use { it.write(SegmentAudio.wavHeader(pcm.size, rate)); it.write(pcm) }
        }
    }

    private fun shrink(id: Long, wav: File, project: ProjectSpace) {
        val (pcm, rate) = readWav(wav) ?: return
        val m4a = File(wav.parentFile, "segment_${id}_voice.m4a")
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

    private suspend fun engine(): TextToSpeech? {
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
