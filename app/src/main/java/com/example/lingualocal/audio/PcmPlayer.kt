package com.example.lingualocal.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.example.lingualocal.Config
import kotlinx.coroutines.channels.Channel

/** Plays the interpreter's 24 kHz PCM16 voice and tracks whether it is currently speaking. */
class PcmPlayer {
    private val queue = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    private var playingUntil = 0L

    private val track: AudioTrack = run {
        val rate = Config.SPEAKER_SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, rate)) // ~0.5 s
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** True while translated speech is playing (plus a short tail for room echo). */
    val isPlaying: Boolean
        get() = SystemClock.elapsedRealtime() < playingUntil + TAIL_MS

    fun enqueue(pcm: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        val durationMs = pcm.size * 1000L / (Config.SPEAKER_SAMPLE_RATE * 2)
        playingUntil = maxOf(now, playingUntil) + durationMs
        queue.trySend(pcm)
    }

    fun flush() {
        while (queue.tryReceive().isSuccess) Unit
        runCatching {
            track.pause()
            track.flush()
            track.play()
        }
        playingUntil = 0
    }

    /** Blocks the calling coroutine, writing queued audio to the speaker until cancelled. */
    suspend fun run() {
        track.play()
        try {
            for (chunk in queue) track.write(chunk, 0, chunk.size)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private companion object {
        const val TAIL_MS = 300L
    }
}
