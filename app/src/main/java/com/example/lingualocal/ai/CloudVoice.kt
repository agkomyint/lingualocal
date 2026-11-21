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
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
        Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
            modelName = Config.LIVE_MODEL,
            generationConfig = liveGenerationConfig { responseModality = ResponseModality.AUDIO },
            systemInstruction = content {
                text(
                    "You are a text-to-speech voice. Read the user's text aloud exactly as written, word for word, " +
                        "in the language it is written in, clearly and naturally. Never translate, answer, explain or add anything."
                )
            },
        )
    }

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

    private suspend fun play(text: String) {
        val session = model.connect()
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
            session.send(text)
            withTimeout(45_000) {
                // Stop reading as soon as Gemini says its turn (or generation) is complete.
                session.receive().takeWhile { msg ->
                    if (msg !is LiveServerContent) return@takeWhile true
                    msg.content?.parts?.filterIsInstance<InlineDataPart>()?.forEach { p ->
                        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                        t.write(p.inlineData, 0, p.inlineData.size)
                        frames += p.inlineData.size / 2
                    }
                    !(msg.turnComplete || msg.generationComplete || msg.interrupted)
                }.collect()
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
            withContext(NonCancellable) { runCatching { session.close() } }
        }
    }
}
