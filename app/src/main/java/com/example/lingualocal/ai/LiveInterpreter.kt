package com.example.lingualocal.ai

import android.util.Log
import com.example.lingualocal.AppState
import com.example.lingualocal.Config
import com.example.lingualocal.SegmentStatus
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.LiveServerGoAway
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.LiveSessionResumptionUpdate
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SessionResumptionConfig
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A piece of one speech segment's audio (16 kHz PCM16). */
class AudioChunk(val segmentId: Long, val bytes: ByteArray)

/**
 * Live preview on the Gemini Live API (Firebase AI Logic): streams speech as it is recorded and shows
 * Gemini's running transcript on the card that is being recorded, so the user sees the Thai words
 * while the person is still talking. The final transcript and translation of every card come from
 * [SegmentTranslator]; this preview never decides what a card says in the end.
 * While disconnected, preview audio is dropped (the segment itself is kept and translated later).
 */
@OptIn(PublicPreviewAPI::class)
class LiveInterpreter(private val space: com.example.lingualocal.ProjectSpace) {

    private val model by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
            modelName = Config.LIVE_MODEL,
            generationConfig = liveGenerationConfig {
                responseModality = ResponseModality.AUDIO
                inputAudioTranscription = AudioTranscriptionConfig()
            },
            systemInstruction = content { text(Config.PREVIEW_PROMPT) },
        )
    }

    @Volatile private var session: LiveSession? = null
    /** The card whose audio was streamed last: live words belong to it, not to a card that just started. */
    @Volatile private var lastSegment = -1L

    /** The live words of each unfinished piece as they came, and what doesn't belong in them. */
    private class Preview(var raw: String, val spoken: MutableSet<String> = linkedSetOf(), val above: MutableSet<String> = linkedSetOf())
    private val previews = HashMap<Long, Preview>()

    suspend fun run(audio: ReceiveChannel<AudioChunk>) = coroutineScope {
        launch {
            for (chunk in audio) {
                val s = session ?: continue
                try {
                    s.sendAudioRealtime(InlineData(chunk.bytes, MIME_PCM_16K))
                    lastSegment = chunk.segmentId
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "preview send failed: ${e.message}")
                }
            }
        }
        // The piece above often gets its final words after they leaked into this one.
        launch { space.captions.collect { synchronized(previews) { previews.keys.toList().forEach(::showPreview) } } }
        connectLoop()
    }

    private suspend fun connectLoop() {
        var handle: String? = null
        var backoffMs = 1_000L
        coroutineScope {
            while (isActive) {
                var s: LiveSession? = null
                var refresh = false
                try {
                    AppState.liveStatus.value = "Connecting…"
                    s = model.connect(handle?.let { SessionResumptionConfig(it) } ?: SessionResumptionConfig())
                    session = s
                    AppState.liveStatus.value = "Live · listening"
                    Log.d(TAG, "connected to ${Config.LIVE_MODEL}")
                    backoffMs = 1_000L
                    s.receive().collect { msg ->
                        when (msg) {
                            is LiveServerContent -> onContent(msg)
                            is LiveSessionResumptionUpdate ->
                                if (msg.resumable == true && msg.newHandle != null) handle = msg.newHandle
                            is LiveServerGoAway -> {
                                refresh = true
                                s.stopReceiving()
                            }
                            else -> Unit
                        }
                    }
                    refresh = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "live session error: $e")
                    AppState.liveStatus.value = "Offline · ${e.message?.take(80) ?: e.javaClass.simpleName} · retrying"
                } finally {
                    session = null
                    withContext(NonCancellable) { runCatching { s?.close() } }
                }
                if (!refresh) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
                }
            }
        }
    }

    private fun onContent(msg: LiveServerContent) {
        val raw = msg.inputTranscription?.text?.takeIf { it.isNotBlank() } ?: return
        val t = keepPairScripts(raw, space.pair) ?: return
        // Show it on the card whose audio it came from (words arrive a little after the audio).
        val target = space.caption(lastSegment)?.takeIf { !it.mine && !it.done } ?: return
        synchronized(previews) {
            previews.getOrPut(target.id) { Preview(target.preview) }.raw += t
            showPreview(target.id)
        }
    }

    private fun showPreview(id: Long) {
        val p = previews[id] ?: return
        val list = space.captions.value
        val at = list.indexOfLast { it.id == id }
        if (at < 0 || list[at].done) {
            previews.remove(id)
            return
        }
        val card = list[at]
        p.spoken += com.example.lingualocal.audio.Speaker.recentlySaid()
        for (c in list.subList(maxOf(0, at - ABOVE), at))
            if (!c.mine && c.heard.isNotBlank() && c.time + c.durationMs >= card.time - ABOVE_MS) p.above += c.heard
        val shown = PreviewText.clean(p.raw, p.spoken, p.above)
        space.updateCaption(id) { if (it.done || it.preview == shown) it else it.copy(preview = shown) }
    }

    /**
     * Live speech-to-text can't be told which languages to expect, so it sometimes writes words in a third
     * language (Hindi, Chinese…). Drops a chunk that is mostly in other scripts, and stray letters from them.
     */
    private fun keepPairScripts(text: String, pair: com.example.lingualocal.data.LangPair): String? {
        fun scriptOf(ch: Char): String? = when {
            ch in 'A'..'Z' || ch in 'a'..'z' -> com.example.lingualocal.data.Lang.EN
            ch in '฀'..'๿' -> com.example.lingualocal.data.Lang.TH
            ch in 'က'..'႟' || ch in 'ꧠ'..'꧿' || ch in 'ꩠ'..'ꩿ' -> com.example.lingualocal.data.Lang.MY
            Character.isLetter(ch) -> "other"
            else -> null // digits, spaces, punctuation, combining marks of the scripts above
        }
        // Burmese speech mixes in English words.
        val allowed = if (pair.transcribeOnly) setOf(pair.a, com.example.lingualocal.data.Lang.EN) else setOf(pair.a, pair.b)
        val letters = text.mapNotNull { scriptOf(it) }
        if (letters.isEmpty()) return text
        if (letters.count { it in allowed } * 2 < letters.size) {
            Log.d(TAG, "preview dropped (wrong language): $text")
            return null
        }
        return text.filter { ch -> scriptOf(ch).let { it == null || it in allowed } }
    }

    private companion object {
        const val TAG = "LiveInterpreter"
        const val ABOVE = 2
        const val ABOVE_MS = 30_000L
        const val MIME_PCM_16K = "audio/pcm;rate=16000"
    }
}
