package com.example.lingualocal

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.initialize

class LinguaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Firebase.initialize(this)
        // Firebase AI Logic requires App Check. Debug builds use the debug provider:
        // the debug secret is printed to logcat and must be added in
        // Firebase console > App Check > Apps > Manage debug tokens.
        if (BuildConfig.DEBUG) {
            Firebase.appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
        }
    }
}
