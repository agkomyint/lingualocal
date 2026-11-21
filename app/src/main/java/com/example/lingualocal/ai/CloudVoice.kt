package com.example.lingualocal.ai

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.lingualocal.Config
import com.example.lingualocal.audio.SegmentAudio
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Reads text aloud with Gemini's voice, for languages the phone has no voice for (Burmese on most devices).
 * Needs internet. Requests play one after another; [stop] cuts everything off.
 */
@OptIn(PublicPreviewAPI::class)
object CloudVoice {
    private const val TAG = "CloudVoice"
    private const val RATE = 24_000

    private val model by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = Config.TTS_MODEL,
            generationConfig = generationConfig {
                responseModalities = listOf(ResponseModality.AUDIO)
                speechConfig = SpeechConfig(Voice(Config.TTS_VOICE))
            },
        )
    }

    /** Only the text itself: the speech model reads out everything it is sent, instructions included. */
    private fun prompt(text: String) = text

    /** Audio bytes (16-bit mono PCM at [RATE]) in a response. */
    private fun audioOf(r: GenerateContentResponse): List<ByteArray> =
        r.candidates.flatMap { c -> c.content.parts.filterIsInstance<InlineDataPart>().map { it.inlineData } }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Mutex()
    private val jobs = mutableSetOf<Job>()
    @Volatile private var track: AudioTrack? = null

    /** Speaks [text]. [flush] stops anything already playing or waiting. [onDone] runs on the main thread when it ends, fails or is stopped. */
    fun speak(text: String, flush: Boolean = true, onFail: (String) -> Unit = {}, onDone: () -> Unit = {}) {
        if (flush) stop()
        val job = scope.launch {
            try {
                lock.withLock { play(text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "couldn't speak: $e")
                main.post { onFail(e.message ?: e.javaClass.simpleName) }
            } finally {
                main.post(onDone)
            }
        }
        synchronized(jobs) { jobs += job }
        job.invokeOnCompletion { synchronized(jobs) { jobs -= job } }
    }

    fun stop() {
        synchronized(jobs) { jobs.toList() }.forEach { it.cancel() }
        track?.let { t -> runCatching { t.pause(); t.flush() } }
    }

    /** Gemini reads [text] into memory without playing it: 16-bit mono PCM at [RATE_HZ]. Needs internet. */
    suspend fun record(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        withTimeout(60_000) { audioOf(model.generateContent(prompt(text))).forEach { out.write(it) } }
        if (out.size() == 0) error("no audio came back")
        return out.toByteArray()
    }

    const val RATE_HZ = RATE

    private suspend fun play(text: String) {
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(SegmentAudio.playbackAttributes())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuf, RATE * 2))
            .build()
        track = t
        try {
            var frames = 0L
            withTimeout(45_000) {
                // Plays each piece of audio as it arrives.
                model.generateContentStream(prompt(text)).collect { r ->
                    audioOf(r).forEach { pcm ->
                        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                        t.write(pcm, 0, pcm.size)
                        frames += pcm.size / 2
                    }
                }
            }
            Log.d(TAG, "received ${frames * 1000 / RATE} ms of audio")
            if (frames == 0L) error("no audio came back")
            // Let the last of the audio play out before releasing the track.
            withTimeout(frames * 1000 / RATE + 3_000) {
                while (t.playState == AudioTrack.PLAYSTATE_PLAYING && t.playbackHeadPosition < frames) delay(50)
            }
            Log.d(TAG, "played to the end")
        } finally {
            track = null
            runCatching { t.stop() }
            t.release()
        }
    }
}
