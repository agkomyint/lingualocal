package com.example.lingualocal.audio

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.lingualocal.Caption
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Locale

/** What "Play all" plays for each piece. */
enum class PlayMode { ORIGINAL, TRANSLATION }

/**
 * Plays a whole project (or one session) piece by piece, skipping the silences between them.
 * Original voices by default; can switch to the translations spoken by the phone's voice, mid-play.
 */
object PlayAll {
    private const val TAG = "PlayAll"
    private const val GAP_MS = 350L

    /** Caption being played now, or null when stopped. */
    val current = MutableStateFlow<Long?>(null)
    val mode = MutableStateFlow(PlayMode.ORIGINAL)
    val fast = MutableStateFlow(false)
    /** Position in the queue, for the player bar ("3 / 12"). */
    val position = MutableStateFlow(0 to 0)

    private lateinit var appContext: Context
    private val main = Handler(Looper.getMainLooper())
    private var source: List<Caption> = emptyList()
    private var queue: List<Caption> = emptyList()
    private var index = 0
    /** Bumped on every start/stop/skip so callbacks from an earlier piece are ignored. */
    private var generation = 0
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun playable(c: Caption, m: PlayMode): Boolean = when (m) {
        PlayMode.ORIGINAL -> c.audioFile?.let { File(it).isFile } == true && (c.heard.isNotBlank() || c.turns.isNotEmpty())
        PlayMode.TRANSLATION -> spokenText(c).isNotBlank()
    }

    private fun spokenText(c: Caption) =
        if (c.turns.isNotEmpty()) c.turns.joinToString(" ") { it.translation }.trim() else c.translation.trim()

    /** Starts playing [items] in order, from [fromId] if given. */
    fun start(items: List<Caption>, fromId: Long? = null) {
        SegmentAudio.stop()
        source = items
        queue = items.filter { playable(it, mode.value) }
        val start = fromId?.let { id -> queue.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: 0
        play(start)
    }

    /** Key of the text being read by [say] (a caption id, or "typed"), null when quiet. */
    val saying = MutableStateFlow<String?>(null)

    /** Reads one translation aloud, on tap. Tapping the same one again stops it. */
    fun say(text: String, key: String) {
        if (saying.value == key) { tts?.stop(); com.example.lingualocal.ai.CloudVoice.stop(); saying.value = null; return }
        stop()
        SegmentAudio.stop()
        if (text.isBlank()) return
        val engine = tts
        if (engine == null || !ttsReady) {
            saying.value = key
            if (engine == null) tts = TextToSpeech(appContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                main.post { if (saying.value == key) { saying.value = null; if (ttsReady) say(text, key) } }
            }.also { it.setOnUtteranceProgressListener(listener) }
            return
        }
        val lang = com.example.lingualocal.data.Lang.of(text)
        if (engine.setLanguage(com.example.lingualocal.data.Lang.locale(lang)) < TextToSpeech.LANG_AVAILABLE) {
            // No voice for this language on the device (Burmese usually): Gemini reads it instead.
            Speaker.remember(text)
            saying.value = key
            com.example.lingualocal.ai.CloudVoice.speak(text, onFail = { failedVoice(lang) }, onDone = { if (saying.value == key) saying.value = null })
            return
        }
        Speaker.remember(text)
        engine.setAudioAttributes(SegmentAudio.playbackAttributes())
        engine.setSpeechRate(1f)
        saying.value = key
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "say-$key")
    }

    fun stop() {
        generation++
        saying.value = null
        releasePlayer()
        tts?.stop()
        com.example.lingualocal.ai.CloudVoice.stop()
        current.value = null
    }

    private fun failedVoice(lang: String) = android.widget.Toast.makeText(
        appContext, "Couldn't speak ${com.example.lingualocal.data.Lang.name(lang)} (needs internet)", android.widget.Toast.LENGTH_SHORT,
    ).show()

    fun next() = play(index + 1)

    fun previous() = play((index - 1).coerceAtLeast(0))

    /** Switches between original voices and spoken translations, carrying on from the same piece. */
    fun setMode(m: PlayMode) {
        if (mode.value == m) return
        mode.value = m
        val at = current.value ?: return
        queue = source.filter { playable(it, m) }
        val atTime = source.firstOrNull { it.id == at }?.time ?: 0
        play(queue.indexOfFirst { it.time >= atTime }.takeIf { it >= 0 } ?: queue.size)
    }

    fun toggleSpeed() {
        fast.value = !fast.value
        player?.let { p -> runCatching { if (p.isPlaying) p.playbackParams = p.playbackParams.setSpeed(speed()) } }
    }

    private fun speed() = if (fast.value) 1.5f else 1f

    private fun play(i: Int) {
        val gen = ++generation
        releasePlayer()
        tts?.stop()
        com.example.lingualocal.ai.CloudVoice.stop()
        if (i !in queue.indices) {
            current.value = null
            return
        }
        index = i
        val c = queue[i]
        current.value = c.id
        position.value = (i + 1) to queue.size
        when (mode.value) {
            PlayMode.ORIGINAL -> playOriginal(c, gen)
            PlayMode.TRANSLATION -> speak(c, gen)
        }
    }

    private fun advance(gen: Int) {
        main.postDelayed({ if (gen == generation) play(index + 1) }, GAP_MS)
    }

    private fun playOriginal(c: Caption, gen: Int) {
        Speaker.remember(c.heard)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(SegmentAudio.playbackAttributes())
                setDataSource(c.audioFile!!)
                setOnCompletionListener { advance(gen) }
                setOnErrorListener { _, _, _ -> advance(gen); true }
                prepare()
                start()
                if (fast.value) playbackParams = playbackParams.setSpeed(speed())
            }
        }.getOrElse {
            Log.w(TAG, "can't play ${c.audioFile}: $it")
            advance(gen)
            null
        }
    }

    private fun speak(c: Caption, gen: Int) {
        val text = spokenText(c)
        val engine = tts
        if (engine == null || !ttsReady) {
            // First use: start the voice, then speak this piece.
            if (engine == null) tts = TextToSpeech(appContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                main.post { if (gen == generation) (if (ttsReady) speak(c, gen) else stop()) }
            }.also { it.setOnUtteranceProgressListener(listener) }
            return
        }
        Speaker.remember(text)
        val lang = com.example.lingualocal.data.Lang.of(text)
        if (engine.setLanguage(com.example.lingualocal.data.Lang.locale(lang)) < TextToSpeech.LANG_AVAILABLE) {
            com.example.lingualocal.ai.CloudVoice.speak(text, onFail = { failedVoice(lang) }, onDone = { advance(gen) })
            return
        }
        engine.setAudioAttributes(SegmentAudio.playbackAttributes())
        engine.setSpeechRate(speed())
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "playall-$gen")
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) = Unit
        override fun onDone(id: String?) {
            if (id?.startsWith("say-") == true) { main.post { if (saying.value == id.removePrefix("say-")) saying.value = null }; return }
            id?.removePrefix("playall-")?.toIntOrNull()?.let { advance(it) }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) = onDone(id)
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }
}
