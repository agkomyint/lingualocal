package com.example.lingualocal.ai

import android.content.Context
import android.util.Log
import com.example.lingualocal.AppState
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Offline "What did they mean?" on phones without Gemini Nano: Google's open Gemma 3 1B model (int4, ~584 MB)
 * running on the device through LiteRT-LM. No internet needed once the model file is on the phone.
 */
object GemmaAssistant {
    private const val TAG = "GemmaAssistant"
    const val MODEL_FILE = "gemma3-1b-it-int4.litertlm"

    private lateinit var appContext: Context
    private val lock = Mutex()
    private var engine: Engine? = null
    private var backendName = ""

    fun init(context: Context) {
        appContext = context.applicationContext
        AppState.gemmaStatus.value = if (modelFile() != null) "Ready offline (Gemma 3 1B, loads on first use)"
        else "Model not on this device yet"
    }

    /** Looks in the app's private files first, then its external files folder. */
    private fun modelFile(): File? = listOfNotNull(
        File(appContext.filesDir, MODEL_FILE),
        appContext.getExternalFilesDir(null)?.let { File(it, MODEL_FILE) },
    ).firstOrNull { it.isFile && it.length() > 0 }

    val available get() = ::appContext.isInitialized && modelFile() != null

    private fun load(): Engine {
        engine?.let { return it }
        val path = modelFile()?.absolutePath ?: error("Gemma model not on this device")
        val started = System.currentTimeMillis()
        // GPU is much faster when the device supports it; fall back to CPU otherwise.
        for ((name, backend) in listOf("GPU" to Backend.GPU(), "CPU" to Backend.CPU())) {
            val e = runCatching {
                Engine(EngineConfig(modelPath = path, backend = backend, maxNumTokens = 1024, cacheDir = appContext.cacheDir.absolutePath))
                    .also { it.initialize() }
            }.onFailure { Log.w(TAG, "Gemma on $name failed: $it") }.getOrNull() ?: continue
            backendName = name
            engine = e
            Log.d(TAG, "Gemma loaded on $name in ${System.currentTimeMillis() - started} ms")
            AppState.gemmaStatus.value = "Ready offline (Gemma 3 1B on $name)"
            return e
        }
        error("Gemma couldn't start on this device")
    }

    /** Explains what was meant, in 2-3 short sentences, fully on the device. */
    suspend fun explain(heard: String, translation: String): HybridAssistant.Answer = withContext(Dispatchers.IO) {
        lock.withLock {
            AppState.gemmaStatus.value = if (engine == null) "Loading Gemma…" else AppState.gemmaStatus.value
            val e = load()
            val started = System.currentTimeMillis()
            val prompt = """
                Thai phrase heard by an expat: "$heard"
                English translation: "$translation"
                Explain in at most 2 short sentences what the speaker meant, including any Thai politeness or cultural nuance.
                Then write one line starting with "Reply:" with a short reply the expat could say.
                Plain text only, no markdown, no headings.
            """.trimIndent()
            val config = ConversationConfig(
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3, seed = 0),
                maxOutputToken = 120,
            )
            val raw = e.createConversation(config).use { c ->
                c.sendMessage(prompt).contents.contents.joinToString("") { (it as? com.google.ai.edge.litertlm.Content.Text)?.text.orEmpty() }
            }
            // Small models still like markdown; keep it as plain sentences.
            val text = raw.replace(Regex("[*#_`]+"), "").replace(Regex("\\s*Reply:"), "\nReply:").lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            Log.d(TAG, "explained in ${System.currentTimeMillis() - started} ms on $backendName: \"$text\"")
            HybridAssistant.Answer(text, "on-device · Gemma 3 1B ($backendName) · offline")
        }
    }
}
