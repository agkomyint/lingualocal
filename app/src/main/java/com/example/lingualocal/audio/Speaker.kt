package com.example.lingualocal.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.lingualocal.AppState
import java.util.Locale

/**
 * Reads translations aloud with the device's own voices (Thai and English).
 * It plays on the voice-call path so the phone's echo canceller removes it from the mic, and the app keeps
 * listening while it speaks. Anything that still leaks through is caught by [isEcho].
 */
class Speaker(context: Context) {
    @Volatile private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        Log.d("Speaker", "voices ready=$ready")
    }

    init {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { AppState.ttsSpeaking.value = true }
            override fun onDone(id: String?) { if (!tts.isSpeaking) AppState.ttsSpeaking.value = false }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { AppState.ttsSpeaking.value = false }
        })
    }

    /** Queues [text] in its language (Thai if it has Thai letters, otherwise English). */
    fun say(text: String, id: String, interrupt: Boolean = false) {
        if (!ready || text.isBlank() || !AppState.speakAloud.value) return
        // No voice for this language on the device (Burmese usually): Gemini reads it instead.
        if (tts.setLanguage(com.example.lingualocal.data.Lang.locale(com.example.lingualocal.data.Lang.of(text))) < TextToSpeech.LANG_AVAILABLE) {
            remember(text)
            AppState.ttsSpeaking.value = true
            val done = { if (!tts.isSpeaking) AppState.ttsSpeaking.value = false }
            val piece = id.removePrefix("seg-").toLongOrNull()
            // A piece: one Gemini request makes the voice, it's saved, and that copy is played (no second request).
            if (piece != null) TranslationVoice.speakPiece(piece, done)
            else com.example.lingualocal.ai.CloudVoice.speak(text, flush = interrupt, onDone = done)
            return
        }
        remember(text)
        AppState.ttsSpeaking.value = true
        tts.speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
    }

    fun stop() {
        tts.stop()
        com.example.lingualocal.ai.CloudVoice.stop()
        TranslationVoice.stopSpeaking()
        AppState.ttsSpeaking.value = false
    }

    fun close() {
        ready = false
        tts.shutdown()
        AppState.ttsSpeaking.value = false
    }

    companion object {
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
            val now = SystemClock.elapsedRealtime()
            val said = synchronized(recent) { recent.filter { now - it.second <= ECHO_WINDOW_MS }.map { it.first } }
            return said.any { looksLikeEcho(heard, it) }
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
