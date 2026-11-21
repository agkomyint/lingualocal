package com.example.lingualocal

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.initialize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class LinguaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Firebase.initialize(this)
        com.example.lingualocal.ai.HybridAssistant.init(this)
        com.example.lingualocal.ai.GemmaAssistant.init(this)
        com.example.lingualocal.ai.AskAssistant.init(this)
        // Projects (recordings and captions) are kept on the phone until the user deletes them.
        com.example.lingualocal.data.ProjectStore.init(this)
        com.example.lingualocal.audio.PlayAll.init(this)
        com.example.lingualocal.audio.SegmentAudio.clearLegacy(this)
        com.example.lingualocal.ai.SegmentTranslator.clearParked(this)

        // Remember the "Speak translations" switch between launches.
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        AppState.speakAloud.value = prefs.getBoolean(KEY_SPEAK, true)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            AppState.speakAloud.drop(1).collect { prefs.edit().putBoolean(KEY_SPEAK, it).apply() }
        }

        // Load models and fetch the offline packs (needs internet once, so the app works with Wi-Fi off later).
        // The loading screen shows these steps.
        Startup.start(this)

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Debug-only test hooks: adb shell run-as <pkg> touch files/probe_download (or files/probe_test)
            if (BuildConfig.DEBUG) {
                val d = java.io.File(filesDir, "probe_download")
                if (d.exists()) { d.delete(); com.example.lingualocal.ai.OfflineSpeechProbe.download(this@LinguaApp) }
                val t = java.io.File(filesDir, "probe_test")
                if (t.exists()) { t.delete(); com.example.lingualocal.ai.OfflineSpeechProbe.test(this@LinguaApp) }
                val g = java.io.File(filesDir, "probe_gemma")
                if (g.exists()) {
                    g.delete()
                    for ((heard, tr) in listOf("ไม่เป็นไรครับ" to "It's okay / never mind.", "ไปไหนมา" to "Where have you been?")) {
                        val a = runCatching { com.example.lingualocal.ai.HybridAssistant.explain(heard, tr) }
                        android.util.Log.d("GemmaAssistant", "PROBE \"$heard\" -> ${a.getOrNull()?.let { "[${it.source}] ${it.text}" } ?: a.exceptionOrNull()}")
                    }
                }
            }
        }

        // Firebase AI Logic requires App Check.
        // Debug builds: debug provider (its secret is printed to logcat; add it in
        // Firebase console > App Check > Apps > Manage debug tokens).
        // Release builds: Play Integrity (register the app's SHA-256 in Firebase and install it from Google Play).
        Firebase.appCheck.installAppCheckProviderFactory(
            if (BuildConfig.DEBUG) DebugAppCheckProviderFactory.getInstance()
            else PlayIntegrityAppCheckProviderFactory.getInstance()
        )
    }

    private companion object {
        const val KEY_SPEAK = "speak_aloud"
    }
}
