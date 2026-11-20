package com.example.lingualocal.ai

import android.util.Log
import com.example.lingualocal.AppState
import com.example.lingualocal.Config
import com.google.firebase.Firebase
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.ai
import com.google.firebase.ai.ondevice.FirebaseAIOnDevice
import com.google.firebase.ai.ondevice.OnDeviceModelStatus
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PublicPreviewAPI

/**
 * Firebase AI Logic hybrid inference: runs on Gemini Nano when the device has it,
 * otherwise falls back to the cloud model. Used for the text features around the live interpreter.
 */
@OptIn(PublicPreviewAPI::class)
object HybridAssistant {

    private val model by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = Config.CLOUD_TEXT_MODEL,
            onDeviceConfig = OnDeviceConfig(mode = InferenceMode.PREFER_ON_DEVICE),
        )
    }

    suspend fun refreshStatus() {
        AppState.nanoStatus.value = try {
            when (FirebaseAIOnDevice.checkStatus()) {
                OnDeviceModelStatus.AVAILABLE -> "Gemini Nano ready on this device"
                OnDeviceModelStatus.DOWNLOADING -> "Gemini Nano downloading…"
                OnDeviceModelStatus.DOWNLOADABLE -> {
                    runCatching { FirebaseAIOnDevice.download().collect {} }
                    "Gemini Nano downloading…"
                }
                else -> "Not available on this device · using cloud fallback"
            }
        } catch (e: Exception) {
            Log.w("HybridAssistant", "on-device check failed", e)
            "Not available on this device · using cloud fallback"
        }
    }

    data class Answer(val text: String, val source: String)

    /** Explains what was meant: cultural context and a suggested polite reply. */
    suspend fun explain(heard: String, translation: String): Answer {
        val prompt = """
            An expat in Thailand heard this in a conversation.
            Original: "$heard"
            Translation: "$translation"
            In at most 3 short sentences of plain English: explain what the speaker most likely meant
            (including any cultural or politeness nuance), then suggest one short reply the expat could say.
        """.trimIndent()
        val response = model.generateContent(prompt)
        val source = when (response.inferenceSource) {
            InferenceSource.ON_DEVICE -> "on-device · Gemini Nano"
            else -> "cloud · ${Config.CLOUD_TEXT_MODEL}"
        }
        return Answer(response.text?.trim().orEmpty(), source)
    }
}
