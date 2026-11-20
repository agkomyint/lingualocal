package com.example.lingualocal.ai

import android.util.Log
import com.example.lingualocal.AppState
import com.example.lingualocal.Config
import com.example.lingualocal.audio.PcmPlayer
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.LiveServerGoAway
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.LiveSessionResumptionUpdate
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.RealtimeInputConfig
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SessionResumptionConfig
import com.google.firebase.ai.type.activityDetectionConfig
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import com.google.firebase.ai.type.realtimeInputConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Always-on interpreter on the Gemini Live API (Firebase AI Logic).
 * Audio chunks come in from the mic loop; translated speech goes to [player]; transcripts go to [AppState].
 * The connection is refreshed automatically (session resumption) so listening never stops.
 */
@OptIn(PublicPreviewAPI::class)
class LiveInterpreter(private val player: PcmPlayer) {

    private val model by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
            modelName = Config.LIVE_MODEL,
            generationConfig = liveGenerationConfig {
                responseModality = ResponseModality.AUDIO
                inputAudioTranscription = AudioTranscriptionConfig()
                outputAudioTranscription = AudioTranscriptionConfig()
                realtimeInputConfig = realtimeInputConfig {
                    // Keep translating even if someone starts talking over the translation.
                    activityHandling = RealtimeInputConfig.ActivityHandling.NO_INTERRUPT
                    automaticActivityDetection = activityDetectionConfig {
                        silenceDurationMs = 600
                    }
                }
            },
            systemInstruction = content { text(Config.INTERPRETER_PROMPT) },
        )
    }

    private val session = MutableStateFlow<LiveSession?>(null)
    private var openCaption: Long? = null

    suspend fun run(audio: ReceiveChannel<ByteArray>) = coroutineScope {
        // Sender: waits for a live session, so audio buffers (instead of being lost) while reconnecting.
        launch {
            for (chunk in audio) {
                val s = session.filterNotNull().first()
                try {
                    s.sendAudioRealtime(InlineData(chunk, MIME_PCM_16K))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "send failed: ${e.message}")
                }
            }
        }

        var handle: String? = null
        var backoffMs = 1_000L
        while (isActive) {
            var s: LiveSession? = null
            var refresh = false
            try {
                AppState.liveStatus.value = "Connecting…"
                s = model.connect(handle?.let { SessionResumptionConfig(it) } ?: SessionResumptionConfig())
                session.value = s
                AppState.liveStatus.value = "Live · listening"
                backoffMs = 1_000L
                s.receive().collect { msg ->
                    when (msg) {
                        is LiveServerContent -> onContent(msg)
                        is LiveSessionResumptionUpdate ->
                            if (msg.resumable == true && msg.newHandle != null) handle = msg.newHandle
                        is LiveServerGoAway -> {
                            AppState.liveStatus.value = "Refreshing session…"
                            refresh = true
                            s.stopReceiving()
                        }
                        else -> Unit
                    }
                }
                refresh = true // stream ended without an error: reconnect right away
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "live session error", e)
                AppState.liveStatus.value = "Error: ${e.message?.take(120)} · retrying"
            } finally {
                session.value = null
                withContext(NonCancellable) { runCatching { s?.close() } }
            }
            if (!refresh) {
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
            }
        }
    }

    private fun onContent(msg: LiveServerContent) {
        if (msg.interrupted) player.flush()

        msg.content?.parts?.forEach { part ->
            if (part is InlineDataPart && part.mimeType.startsWith("audio/pcm")) player.enqueue(part.inlineData)
        }
        msg.inputTranscription?.text?.takeIf { it.isNotEmpty() }?.let { t ->
            val id = openCaption ?: AppState.newCaption().also { openCaption = it }
            AppState.updateCaption(id) { it.copy(heard = it.heard + t) }
        }
        msg.outputTranscription?.text?.takeIf { it.isNotEmpty() }?.let { t ->
            val id = openCaption ?: AppState.newCaption().also { openCaption = it }
            AppState.updateCaption(id) { it.copy(translation = it.translation + t) }
        }
        if (msg.turnComplete) {
            openCaption?.let { id -> AppState.updateCaption(id) { it.copy(done = true) } }
            openCaption = null
        }
    }

    private companion object {
        const val TAG = "LiveInterpreter"
        const val MIME_PCM_16K = "audio/pcm;rate=16000"
    }
}
