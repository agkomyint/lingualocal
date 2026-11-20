package com.example.lingualocal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.lingualocal.ai.HybridAssistant
import com.example.lingualocal.ai.OfflineTranslator
import com.example.lingualocal.audio.ListenService
import com.example.lingualocal.ui.LinguaScreen
import com.example.lingualocal.ui.LinguaTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) ListenService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        lifecycleScope.launch { HybridAssistant.refreshStatus() }
        lifecycleScope.launch { OfflineTranslator.prepare() }

        setContent {
            LinguaTheme {
                LinguaScreen(onToggleListening = ::toggleListening)
            }
        }
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

        if (needed.isEmpty()) ListenService.start(this) else permissionLauncher.launch(needed.toTypedArray())
    }
}
