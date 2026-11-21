package com.example.lingualocal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.lingualocal.audio.ListenService
import com.example.lingualocal.ui.LinguaScreen
import com.example.lingualocal.ui.LinguaTheme
import com.example.lingualocal.ui.LoadingScreen

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) startListening()
    }

    /** Android's "start recording / casting?" prompt, needed to hear other apps' sound. */
    private val projectionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            AppState.projectionConsent = result.data
            ListenService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run first (before anything touches the window), or the launch theme sticks.
        // System splash (logo on the app background) until the first frame, then fade it into the loading screen,
        // which shows the same logo while models load.
        installSplashScreen().setOnExitAnimationListener { splash ->
            splash.view.animate().alpha(0f).setDuration(250).withEndAction { splash.remove() }.start()
        }
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        setContent {
            LinguaTheme {
                // Shown once per app launch; survives rotation.
                var loading by rememberSaveable { mutableStateOf(!Startup.loadingShown) }
                Crossfade(targetState = loading, animationSpec = tween(350), label = "launch") { isLoading ->
                    if (isLoading) LoadingScreen(onReady = { Startup.loadingShown = true; loading = false })
                    else LinguaScreen(onToggleListening = ::toggleListening)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        com.example.lingualocal.data.ProjectStore.flush()
    }

    private fun toggleListening() {
        if (AppState.running.value) {
            ListenService.stop(this)
            return
        }
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) startListening() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startListening() {
        if (AppState.source.value == AudioSource.PHONE && Build.VERSION.SDK_INT >= 29) {
            val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java)
            val intent = if (Build.VERSION.SDK_INT >= 34) {
                mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
            } else mpm.createScreenCaptureIntent()
            projectionLauncher.launch(intent)
        } else {
            ListenService.start(this)
        }
    }
}
