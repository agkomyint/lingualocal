package com.example.lingualocal

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
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

        // Remember the "Speak translations" switch between launches.
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        AppState.speakAloud.value = prefs.getBoolean(KEY_SPEAK, true)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            AppState.speakAloud.drop(1).collect { prefs.edit().putBoolean(KEY_SPEAK, it).apply() }
        }

        // Firebase AI Logic requires App Check. Debug builds use the debug provider:
        // the debug secret is printed to logcat and must be added in
        // Firebase console > App Check > Apps > Manage debug tokens.
        if (BuildConfig.DEBUG) {
            Firebase.appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
        }
    }

    private companion object {
        const val KEY_SPEAK = "speak_aloud"
    }
}
