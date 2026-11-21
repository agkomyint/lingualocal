package com.example.lingualocal

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.lingualocal.ai.HybridAssistant
import com.example.lingualocal.ai.OfflineSpeech
import com.example.lingualocal.ai.OfflineTranslator
import com.example.lingualocal.audio.LanguageId
import com.example.lingualocal.audio.SileroVad
import com.example.lingualocal.audio.SpeakerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Everything the app gets ready at launch, shown step by step on the loading screen.
 * Steps run in parallel and keep running after the app opens, so a slow first-time
 * model download never blocks the user; the loading screen only waits a few seconds for them.
 */
object Startup {
    enum class State { RUNNING, DONE, FAILED }
    data class Step(val id: String, val label: String, val state: State = State.RUNNING, val detail: String = "")

    private val _steps = MutableStateFlow(
        listOf(
            Step(PROJECTS, "Your projects"),
            Step(VOICE, "Voice detection"),
            Step(TRANSLATE, "Offline translation"),
            Step(SPEECH, "Offline speech"),
            Step(CLOUD, "Gemini"),
        )
    )
    val steps: StateFlow<List<Step>> = _steps.asStateFlow()

    /** Set once the loading screen has been dismissed in this process, so it isn't shown again. */
    @Volatile var loadingShown = false

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Starts every step once per process. Call after ProjectStore.init. */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext

        val count = AppState.projects.value.size
        set(PROJECTS, State.DONE, if (count == 1) "1 project on this device" else "$count projects on this device")

        // Load the on-device voice models once, so the first "Start listening" doesn't stall on them.
        scope.launch(Dispatchers.Default) {
            runCatching {
                SileroVad(app).close()
                SpeakerId(app).close()
                LanguageId(app).close()
            }.onSuccess { set(VOICE, State.DONE, "Speech, speaker and language models loaded") }
                .onFailure { Log.e(TAG, "voice models failed", it); set(VOICE, State.FAILED, it.message?.take(60) ?: "Failed to load") }
        }

        scope.launch {
            OfflineTranslator.prepare() // downloads the Thai/English pack the first time (needs internet once)
            val status = AppState.offlineStatus.value
            set(TRANSLATE, if (status.startsWith("Ready")) State.DONE else State.FAILED, status)
        }

        scope.launch {
            OfflineSpeech.prepare()
            set(SPEECH, if (OfflineSpeech.ready) State.DONE else State.FAILED, AppState.offlineSpeechStatus.value)
        }

        scope.launch {
            val online = isOnline(app)
            HybridAssistant.refreshStatus()
            set(
                CLOUD,
                State.DONE,
                (if (online) "Online · Gemini cloud" else "Offline · on-device only") + " · " + AppState.nanoStatus.value,
            )
        }
    }

    private fun set(id: String, state: State, detail: String) =
        _steps.update { list -> list.map { if (it.id == id) it.copy(state = state, detail = detail) else it } }

    private fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private const val TAG = "Startup"
    private const val PROJECTS = "projects"
    private const val VOICE = "voice"
    private const val TRANSLATE = "translate"
    private const val SPEECH = "speech"
    private const val CLOUD = "cloud"
}
