package com.example.lingualocal.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.lingualocal.AppState
import com.example.lingualocal.ProjectSpace
import com.example.lingualocal.data.Lang
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads translations of [space] aloud with the voice picked for each language, the device's or Gemini's, one
 * after another whichever it is. It plays on the voice-call path so the phone's echo canceller removes it from
 * the mic, and the app keeps listening while it speaks. Anything that still leaks through is caught by [isEcho].
 */
class Speaker(context: Context, private val space: ProjectSpace) {
    private val ready = CompletableDeferred<Boolean>()
    private val utterances = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var last: Job? = null
    private var closed = false

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
        Log.d("Speaker", "voices ready=${status == TextToSpeech.SUCCESS}")
    }

    init {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) = ended(id)
            override fun onStop(id: String?, interrupted: Boolean) = ended(id)
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = ended(id)
            private fun ended(id: String?) {
                id?.let { utterances.remove(it) }?.complete(Unit)
            }
        })
    }

    /** Queues [text] in the voice picked for its language. [id] is "seg-" or "ptt-" and the piece's number. */
    fun say(text: String, id: String, interrupt: Boolean = false) {
        if (text.isBlank() || !AppState.speakAloud.value) return
        if (interrupt) stop()
        synchronized(lock) {
            if (closed) return
            val before = last
            val piece = id.substringAfter('-').toLongOrNull()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    speak(text, piece, before)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A voice that fails mustn't take listening down with it.
                    Log.w("Speaker", "can't speak piece $piece", e)
                }
            }
            last = job
            job.invokeOnCompletion {
                synchronized(lock) {
                    if (last === job) {
                        last = null
                        AppState.ttsSpeaking.value = false
                    }
                }
            }
            job.start()
        }
    }

    private suspend fun speak(text: String, piece: Long?, before: Job?) = supervisorScope {
        val c = piece?.let { space.caption(it) }
        val wants = TranslationVoice.wantsGemini(space, c, text)
        val online = TranslationVoice.online()
        // Asked for straight away, so it's ready by its turn.
        val early = if (wants && c != null && online) async { TranslationVoice.voiceFile(space, c.id, gemini = true) } else null
        before?.join()
        val started = withTimeoutOrNull(VOICE_START_MS) { ready.await() } == true
        if (!started && !wants) return@supervisorScope
        val lang = Lang.of(text)
        val phone = started && tts.isLanguageAvailable(Lang.locale(lang)) >= TextToSpeech.LANG_AVAILABLE

        val path = when {
            // With a phone voice to fall back on, a slow Gemini mustn't hold up the queue.
            early != null && phone -> withTimeoutOrNull(GEMINI_WAIT_MS) { early.await() }.also { if (it == null) early.cancel() }
            early != null -> early.await()
            !phone && c != null -> TranslationVoice.voiceFile(space, c.id, gemini = false)
            else -> null
        }
        currentCoroutineContext().ensureActive()
        if (path == null && !phone && c != null) return@supervisorScope
        // Noted as the sound starts: Gemini's can come seconds later.
        remember(text)
        AppState.ttsSpeaking.value = true
        when {
            path != null -> TranslationVoice.playSaved(path)
            phone -> speakOnPhone(text, lang)
            else -> {
                // Not a piece: nowhere to save it.
                val done = CompletableDeferred<Unit>()
                com.example.lingualocal.ai.CloudVoice.speak(text, flush = false, onDone = { done.complete(Unit) })
                done.await()
            }
        }
        AppState.ttsSpeaking.value = false
    }

    private suspend fun speakOnPhone(text: String, lang: String) {
        val id = "live-${System.nanoTime()}"
        val done = CompletableDeferred<Unit>()
        utterances[id] = done
        try {
            tts.setLanguage(Lang.locale(lang))
            currentCoroutineContext().ensureActive()
            if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) return
            // An engine that never reports the end mustn't hold up the pieces after it.
            withTimeoutOrNull(10_000L + text.length * 250L) { done.await() }
        } finally {
            utterances.remove(id)
        }
    }

    fun stop() {
        synchronized(lock) {
            last = null
            scope.coroutineContext.cancelChildren()
        }
        tts.stop()
        com.example.lingualocal.ai.CloudVoice.stop()
        TranslationVoice.stopSpeaking()
        AppState.ttsSpeaking.value = false
    }

    fun close() {
        synchronized(lock) { closed = true }
        scope.cancel()
        tts.shutdown()
        AppState.ttsSpeaking.value = false
    }

    companion object {
        /** How long a translation waits for Gemini before the device's voice reads it. */
        private const val GEMINI_WAIT_MS = 20_000L
        private const val VOICE_START_MS = 5_000L
        private const val ECHO_WINDOW_MS = 30_000L

        /** What the app played recently (text, time), to recognise its own audio coming back through the mic. */
        private val recent = ArrayDeque<Pair<String, Long>>()

        /** Notes text the app is about to play (a spoken translation or a replayed recording). */
        fun remember(text: String) {
            if (text.isBlank()) return
            synchronized(recent) {
                val now = SystemClock.elapsedRealtime()
                recent.addLast(text to now)
                while (recent.isNotEmpty() && now - recent.first().second > ECHO_WINDOW_MS) recent.removeFirst()
            }
        }

        /** True if [heard] is just something the app itself played recently, picked up again by the mic. */
        fun isEcho(heard: String): Boolean {
            return recentlySaid().any { looksLikeEcho(heard, it) }
        }

        /** What the app said in the last 30 s. */
        fun recentlySaid(): List<String> {
            val now = SystemClock.elapsedRealtime()
            return synchronized(recent) { recent.filter { now - it.second <= ECHO_WINDOW_MS }.map { it.first } }
        }

        private fun normalize(t: String) = t.lowercase().filter { it.isLetterOrDigit() }

        private fun bigrams(t: String) = t.windowed(2).groupingBy { it }.eachCount()

        /** Character-bigram similarity (works for Thai, which has no spaces). */
        fun looksLikeEcho(heard: String, said: String): Boolean {
            val h = normalize(heard)
            val s = normalize(said)
            if (h.length < 2 || s.length < 2) return false
            if (h.length >= 4 && h in s) return true
            val a = bigrams(h)
            val b = bigrams(s)
            val common = a.entries.sumOf { (k, n) -> minOf(n, b[k] ?: 0) }
            return 2.0 * common / (h.length - 1 + s.length - 1) >= 0.6
        }
    }
}
