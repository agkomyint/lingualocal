package com.example.lingualocal.audio

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.lingualocal.Caption
import com.example.lingualocal.ProjectSpace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** What "Play all" plays for each piece. */
enum class PlayMode { ORIGINAL, TRANSLATION }

/**
 * Plays a whole project (or one session) piece by piece, skipping the silences between them.
 * Original voices by default; can switch to the translations, spoken in the voice picked for each language, mid-play.
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

    private fun spokenText(c: Caption) = c.spokenText

    /** Starts playing [items] in order, from [fromId] if given. */
    fun start(items: List<Caption>, fromId: Long? = null) {
        SegmentAudio.stop()
        source = items
        queue = items.filter { playable(it, mode.value) }
        if (queue.isEmpty()) {
            android.widget.Toast.makeText(appContext, "No recorded sound to play here", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // From a piece without sound: start at the next one that has it, never back at the beginning.
        val from = fromId?.let { id -> items.firstOrNull { it.id == id } }
        val start = from?.let { f -> queue.indexOfFirst { it.time >= f.time }.takeIf { it >= 0 } } ?: 0
        play(start)
    }

    /** Key of the text being read by [say] (a caption id, or "typed"), null when quiet. */
    val saying = MutableStateFlow<String?>(null)

    /** Reads one translation aloud, on tap. Tapping the same one again stops it. [space] is the piece's project. */
    fun say(text: String, key: String, space: ProjectSpace? = com.example.lingualocal.AppState.view.value) {
        if (saying.value == key) { tts?.stop(); releasePlayer(); com.example.lingualocal.ai.CloudVoice.stop(); saying.value = null; return }
        stop()
        SegmentAudio.stop()
        if (text.isBlank()) return
        saying.value = key
        val c = key.toLongOrNull()?.let { space?.caption(it) }
        val wants = TranslationVoice.wantsGemini(space, c, text)
        if (space != null && c != null) {
            val saved = TranslationVoice.saved(c, text)
            // Saved voice for this piece, in the voice picked: plays offline, no voice engine needed.
            if (saved != null && TranslationVoice.savedIsGemini(space, c) == wants && playSaid(saved, text, key)) return
            if (wants) {
                if (TranslationVoice.alwaysGemini(space, c) || TranslationVoice.online()) {
                    sayWithGemini(space, c, text, key)
                    return
                }
                // No internet: the phone's voice, until Gemini's can be made.
                if (saved != null && playSaid(saved, text, key)) {
                    offlineVoice()
                    return
                }
            }
        }
        sayOnPhone(text, key, space, c)
    }

    private fun playSaid(path: String, text: String, key: String): Boolean {
        Speaker.remember(text)
        player = playFile(path, onEnd = { if (saying.value == key) saying.value = null })
        return player != null
    }

    /** [geminiFailed]: Gemini was already tried, so it isn't the fallback. */
    private fun sayOnPhone(text: String, key: String, space: ProjectSpace?, c: Caption?, geminiFailed: Boolean = false) {
        val engine = tts
        if (engine == null || !ttsReady) {
            if (engine == null) tts = TextToSpeech(appContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                main.post {
                    if (!ttsReady) tts = null
                    if (saying.value != key) return@post
                    if (ttsReady) sayOnPhone(text, key, space, c, geminiFailed) else sayWithoutPhone(text, key, space, c, geminiFailed)
                }
            }.also { it.setOnUtteranceProgressListener(listener) }
            return
        }
        val lang = com.example.lingualocal.data.Lang.of(text)
        if (engine.setLanguage(com.example.lingualocal.data.Lang.locale(lang)) < TextToSpeech.LANG_AVAILABLE) {
            sayWithoutPhone(text, key, space, c, geminiFailed)
            return
        }
        if (geminiFailed) {
            android.widget.Toast.makeText(appContext, "Gemini's voice didn't load · using the device's voice", android.widget.Toast.LENGTH_SHORT).show()
        } else if (TranslationVoice.wantsGemini(space, c, text) && !TranslationVoice.online()) {
            offlineVoice()
        }
        Speaker.remember(text)
        engine.setAudioAttributes(SegmentAudio.playbackAttributes())
        engine.setSpeechRate(1f)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "say-$key")
    }

    private val voiceScope = kotlinx.coroutines.MainScope()

    private fun sayWithGemini(space: ProjectSpace, c: Caption, text: String, key: String) {
        voiceScope.launch {
            val f = TranslationVoice.voiceFile(space, c.id, gemini = true)
            if (saying.value != key) return@launch
            when {
                f != null -> if (!playSaid(f, text, key)) saying.value = null
                TranslationVoice.alwaysGemini(space, c) -> {
                    failedVoice(com.example.lingualocal.data.Lang.of(text))
                    saying.value = null
                }
                else -> sayOnPhone(text, key, space, c, geminiFailed = true)
            }
        }
    }

    /** The phone has no voice for this language: the saved one, else Gemini makes it once and it's saved. */
    private fun sayWithoutPhone(text: String, key: String, space: ProjectSpace?, c: Caption?, geminiFailed: Boolean) {
        val lang = com.example.lingualocal.data.Lang.of(text)
        if (c != null && TranslationVoice.saved(c, text)?.let { playSaid(it, text, key) } == true) return
        if (geminiFailed) {
            failedVoice(lang)
            saying.value = null
            return
        }
        Speaker.remember(text)
        if (space == null || c == null) {
            com.example.lingualocal.ai.CloudVoice.speak(text, onFail = { failedVoice(lang) }, onDone = { if (saying.value == key) saying.value = null })
            return
        }
        voiceScope.launch {
            val f = TranslationVoice.voiceFile(space, c.id, gemini = false)
            if (saying.value != key) return@launch
            if (f != null && playSaid(f, text, key)) return@launch
            failedVoice(lang)
            saying.value = null
        }
    }

    private fun speakWithGemini(space: ProjectSpace, c: Caption, gen: Int, wants: Boolean) {
        // Made once by Gemini and saved, so playing again later is free.
        voiceScope.launch {
            val f = TranslationVoice.voiceFile(space, c.id, gemini = wants)
            if (gen != generation) return@launch
            val text = spokenText(c)
            if (f == null) {
                if (wants && !TranslationVoice.alwaysGemini(space, c)) speakOnPhone(space, c, gen, geminiFailed = true)
                else { failedVoice(com.example.lingualocal.data.Lang.of(text)); advance(gen) }
                return@launch
            }
            Speaker.remember(text)
            player = playFile(f, onEnd = { advance(gen) }, rate = speed())
            if (player == null) advance(gen)
        }
    }

    fun stop() {
        generation++
        saying.value = null
        releasePlayer()
        tts?.stop()
        com.example.lingualocal.ai.CloudVoice.stop()
        current.value = null
    }

    private fun offlineVoice() = android.widget.Toast.makeText(
        appContext, "No internet · using the device's voice", android.widget.Toast.LENGTH_SHORT,
    ).show()

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
        val space = com.example.lingualocal.AppState.view.value
        if (space == null) { advance(gen); return }
        val wants = TranslationVoice.wantsGemini(space, c, text)
        val always = TranslationVoice.alwaysGemini(space, c)
        // Playing everything never remakes old pieces: only a tap on one does.
        val makeNow = wants && TranslationVoice.autoGemini(space, c, text) && (always || TranslationVoice.online())
        val saved = TranslationVoice.saved(c, text)
        if (saved != null && VoiceRules.playSaved(wants, TranslationVoice.savedIsGemini(space, c), makeNow) && playPiece(saved, text, gen)) return
        if (makeNow || always) speakWithGemini(space, c, gen, wants = true)
        else speakOnPhone(space, c, gen)
    }

    private fun playPiece(path: String, text: String, gen: Int): Boolean {
        Speaker.remember(text)
        player = playFile(path, onEnd = { advance(gen) }, rate = speed())
        return player != null
    }

    /** [geminiFailed]: Gemini was already tried, so it isn't the fallback. */
    private fun speakOnPhone(space: ProjectSpace, c: Caption, gen: Int, geminiFailed: Boolean = false) {
        val text = spokenText(c)
        val engine = tts
        if (engine == null || !ttsReady) {
            // First use: start the voice, then speak this piece.
            if (engine == null) tts = TextToSpeech(appContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                main.post {
                    if (!ttsReady) tts = null
                    if (gen != generation) return@post
                    if (ttsReady) speakOnPhone(space, c, gen, geminiFailed) else speakWithoutPhone(space, c, gen, geminiFailed)
                }
            }.also { it.setOnUtteranceProgressListener(listener) }
            return
        }
        val lang = com.example.lingualocal.data.Lang.of(text)
        if (engine.setLanguage(com.example.lingualocal.data.Lang.locale(lang)) < TextToSpeech.LANG_AVAILABLE) {
            speakWithoutPhone(space, c, gen, geminiFailed)
            return
        }
        Speaker.remember(text)
        engine.setAudioAttributes(SegmentAudio.playbackAttributes())
        engine.setSpeechRate(speed())
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "playall-$gen")
    }

    private fun speakWithoutPhone(space: ProjectSpace, c: Caption, gen: Int, geminiFailed: Boolean) {
        val text = spokenText(c)
        if (TranslationVoice.saved(c, text)?.let { playPiece(it, text, gen) } == true) return
        if (!geminiFailed) { speakWithGemini(space, c, gen, wants = false); return }
        failedVoice(com.example.lingualocal.data.Lang.of(text))
        advance(gen)
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

    private fun playFile(path: String, onEnd: () -> Unit, rate: Float = 1f): MediaPlayer? = runCatching {
        MediaPlayer().apply {
            setAudioAttributes(SegmentAudio.playbackAttributes())
            setDataSource(path)
            setOnCompletionListener { onEnd() }
            setOnErrorListener { _, _, _ -> onEnd(); true }
            prepare()
            start()
            if (rate != 1f) playbackParams = playbackParams.setSpeed(rate)
        }
    }.getOrElse { Log.w(TAG, "can't play $path: $it"); null }

    private fun releasePlayer() {
        player?.release()
        player = null
    }
}
