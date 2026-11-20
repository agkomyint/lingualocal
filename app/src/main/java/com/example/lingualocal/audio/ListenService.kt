package com.example.lingualocal.audio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.lingualocal.AppState
import com.example.lingualocal.Config
import com.example.lingualocal.MainActivity
import com.example.lingualocal.ai.LiveInterpreter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Foreground service that keeps the microphone open (screen off, other apps in front).
 * Pipeline: mic -> Silero VAD (on-device, ONNX Runtime) -> Gemini Live interpreter -> speaker.
 */
class ListenService : LifecycleService() {

    private var pipeline: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (pipeline == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LinguaLocal:listen")
                .apply { acquire(12 * 60 * 60 * 1000L) }
            pipeline = lifecycleScope.launch(Dispatchers.Default) { runPipeline() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        pipeline?.cancel()
        pipeline = null
        wakeLock?.takeIf { it.isHeld }?.release()
        AppState.running.value = false
        AppState.liveStatus.value = "Idle"
        AppState.speechActive.value = false
        AppState.micLevel.value = 0f
        AppState.vadProbability.value = 0f
        super.onDestroy()
    }

    private suspend fun runPipeline() = coroutineScope {
        AppState.running.value = true
        val player = PcmPlayer()
        val interpreter = LiveInterpreter(player)
        val vad = try {
            withContext(Dispatchers.Default) { SileroVad(applicationContext) }.also {
                AppState.vadStatus.value = "Running on-device (ONNX Runtime)"
            }
        } catch (e: Exception) {
            Log.e(TAG, "VAD failed to load", e)
            AppState.vadStatus.value = "Failed: ${e.message?.take(80)} · using volume gate"
            null
        }
        // ~6 s of audio buffer; oldest chunks drop if the network stalls longer than that.
        val audioOut = Channel<ByteArray>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        launch(Dispatchers.IO) { interpreter.run(audioOut) }
        launch(Dispatchers.IO) { player.run() }
        // Switching "Speak translations" off cuts any speech that is already playing.
        launch { AppState.speakAloud.collect { if (!it) player.flush() } }
        try {
            withContext(Dispatchers.IO) { micLoop(this, vad, player, audioOut) }
        } finally {
            vad?.close()
            audioOut.close()
        }
    }

    @SuppressLint("MissingPermission") // checked in onStartCommand
    private fun micLoop(scope: CoroutineScope, vad: SileroVad?, player: PcmPlayer, out: SendChannel<ByteArray>) {
        val rate = Config.MIC_SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, // enables the platform echo canceller path
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate),
        )
        val aec = if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
        } else null
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        val frame = ShortArray(SileroVad.FRAME)
        val floats = FloatArray(SileroVad.FRAME)
        val preRoll = ArrayDeque<ByteArray>()
        val batch = ByteArrayOutputStream()
        var speaking = false
        var hangover = 0
        var headset = false
        var lastDeviceCheck = 0L
        var lastUiUpdate = 0L
        var lastLog = 0L
        var sentBytes = 0L

        recorder.startRecording()
        try {
            while (scope.isActive) {
                var read = 0
                while (read < frame.size) {
                    val n = recorder.read(frame, read, frame.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < frame.size) continue

                var sumSq = 0.0
                for (i in frame.indices) {
                    val v = frame[i] / 32768f
                    floats[i] = v
                    sumSq += v * v
                }
                val rms = sqrt(sumSq / frame.size).toFloat()
                val prob = vad?.probability(floats) ?: if (rms > 0.02f) 1f else 0f

                val now = SystemClock.elapsedRealtime()
                if (now - lastDeviceCheck > 1_000) {
                    headset = hasHeadset(audioManager)
                    lastDeviceCheck = now
                }
                // Through the loudspeaker, ignore the mic while our own translation is playing.
                val echoGuard = !headset && player.isPlaying
                val voice = prob > SPEECH_THRESHOLD && !echoGuard
                if (voice) hangover = HANGOVER_FRAMES else if (hangover > 0) hangover--
                val nowSpeaking = voice || (speaking && hangover > 0 && !echoGuard)

                val bytes = ByteBuffer.allocate(frame.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    .also { it.asShortBuffer().put(frame) }.array()

                if (nowSpeaking) {
                    if (!speaking) {
                        preRoll.forEach { batch.write(it) }
                        preRoll.clear()
                    }
                    batch.write(bytes)
                    if (batch.size() >= BATCH_BYTES) {
                        sentBytes += batch.size()
                        out.trySend(batch.toByteArray())
                        batch.reset()
                    }
                } else {
                    if (speaking) {
                        if (batch.size() > 0) out.trySend(batch.toByteArray())
                        batch.reset()
                        out.trySend(ByteArray(rate * 2)) // 1 s of silence so the server closes the turn
                    }
                    preRoll.addLast(bytes)
                    if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                }
                speaking = nowSpeaking

                if (now - lastLog > 2_000) {
                    Log.d(TAG, "mic rms=%.4f vad=%.2f speaking=%b echoGuard=%b sentBytes=%d".format(rms, prob, speaking, echoGuard, sentBytes))
                    lastLog = now
                }
                if (now - lastUiUpdate > 80) {
                    AppState.micLevel.value = (rms * 6f).coerceIn(0f, 1f)
                    AppState.vadProbability.value = prob
                    AppState.speechActive.value = speaking
                    AppState.echoGuard.value = echoGuard
                    lastUiUpdate = now
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            aec?.release()
        }
    }

    private fun hasHeadset(am: AudioManager): Boolean =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type in setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET,
            )
        }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Live interpreter", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ListenService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("LinguaLocal is listening")
            .setContentText("Translating Thai ⇄ English live")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val TAG = "ListenService"
        private const val CHANNEL_ID = "listen"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.example.lingualocal.STOP"
        private const val SPEECH_THRESHOLD = 0.5f
        private const val HANGOVER_FRAMES = 25 // 25 x 32 ms = 0.8 s
        private const val PRE_ROLL_FRAMES = 10 // 0.32 s kept so word starts aren't clipped
        private const val BATCH_BYTES = 3_200 // ~100 ms per network message

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, ListenService::class.java))

        fun stop(context: Context) =
            context.startService(Intent(context, ListenService::class.java).setAction(ACTION_STOP))
    }
}
