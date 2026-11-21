package com.example.lingualocal.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.example.lingualocal.AppState
import com.example.lingualocal.Config
import com.example.lingualocal.data.ProjectStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Saves each speech segment into the open project (AAC, WAV if the encoder fails) and replays it on request. */
object SegmentAudio {

    /** Recordings from older versions, kept outside any project. They belong to nothing now. */
    fun clearLegacy(context: Context) {
        File(context.filesDir, "segments").deleteRecursively()
    }

    fun delete(path: String?) {
        path?.let { File(it).delete() }
    }

    fun save(space: com.example.lingualocal.ProjectSpace, id: Long, pcm: ByteArray): String {
        val dir = ProjectStore.audioDir(space)
        val m4a = File(dir, "segment_$id.m4a")
        runCatching { AacEncoder.encode(pcm, Config.MIC_SAMPLE_RATE, m4a) }
            .onSuccess { return m4a.absolutePath }
            .onFailure { Log.w("SegmentAudio", "AAC failed, saving WAV: $it"); m4a.delete() }
        val file = File(dir, "segment_$id.wav")
        file.outputStream().use { out ->
            out.write(wavHeader(pcm.size, Config.MIC_SAMPLE_RATE))
            out.write(pcm)
        }
        return file.absolutePath
    }

    /** A complete in-memory WAV file of 16 kHz PCM16, for sending to Gemini. */
    fun wav(pcm: ByteArray): ByteArray = wavHeader(pcm.size, Config.MIC_SAMPLE_RATE) + pcm

    fun wavHeader(dataSize: Int, rate: Int): ByteArray =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1) // PCM, mono
            putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataSize)
        }.array()

    /**
     * How the app's own audio is played. While listening it goes on the voice-call path, so the echo canceller
     * removes it from the mic; otherwise as normal media (loudspeaker, media volume).
     */
    fun playbackAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (AppState.running.value) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var player: MediaPlayer? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Plays one recording. The mic keeps listening: playback goes through the echo canceller, and [heard]
     * is remembered so a leftover echo of it is ignored instead of translated again.
     */
    fun play(path: String, heard: String = "") {
        stop()
        PlayAll.stop()
        Speaker.remember(heard)
        AppState.replaying.value = true
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(playbackAttributes())
                setDataSource(path)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
        }.onSuccess { p ->
            // Backup: on the voice-call path the completion callback can go missing, leaving the button on Stop.
            // Check once the clip should have ended, and keep checking until it has.
            val check = object : Runnable {
                override fun run() {
                    if (player !== p) return
                    if (runCatching { p.isPlaying }.getOrDefault(false)) main.postDelayed(this, 300) else stop()
                }
            }
            main.postDelayed(check, runCatching { p.duration.toLong() }.getOrDefault(0L).coerceAtLeast(0L) + 400)
        }.getOrElse {
            AppState.replaying.value = false
            null
        }
    }

    fun stop() {
        player?.release()
        player = null
        AppState.replaying.value = false
    }
}
