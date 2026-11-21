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
import com.example.lingualocal.SegmentStatus
import com.example.lingualocal.WaveBar
import com.example.lingualocal.ai.AudioChunk
import com.example.lingualocal.ai.LiveInterpreter
import com.example.lingualocal.ai.SegmentTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Foreground service that keeps the microphone open (screen off, other apps in front).
 * Pipeline: mic -> Silero VAD (on-device, ONNX Runtime) cuts speech segments -> volume boost ->
 * Gemini Live interpreter -> speaker. Every segment is also saved for replay.
 */
class ListenService : LifecycleService() {

    private var pipeline: Job? = null
    /** Set when listening to phone audio (other apps' sound) instead of the mic. */
    private var projection: android.media.projection.MediaProjection? = null
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
        val consent = AppState.projectionConsent.also { AppState.projectionConsent = null }
        val phone = pipeline == null && consent != null && Build.VERSION.SDK_INT >= 29
        startInForeground(phone)
        if (phone) {
            // Must come after startForeground with the mediaProjection type.
            projection = runCatching {
                (getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager)
                    .getMediaProjection(android.app.Activity.RESULT_OK, consent!!)
            }.onFailure { Log.e(TAG, "phone audio unavailable", it) }.getOrNull()
            if (projection == null) { stopSelf(); return START_NOT_STICKY }
            projection!!.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                // Stopped from the system's cast/record chip: stop listening too.
                override fun onStop() { stopSelf() }
            }, android.os.Handler(mainLooper))
        }
        if (pipeline == null) {
            AppState.runningSource.value = if (projection != null) com.example.lingualocal.AudioSource.PHONE else com.example.lingualocal.AudioSource.MIC
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
        projection?.let { p -> projection = null; runCatching { p.stop() } }
        wakeLock?.takeIf { it.isHeld }?.release()
        AppState.running.value = false
        AppState.liveStatus.value = "Idle"
        AppState.speechActive.value = false
        AppState.echoGuard.value = false
        AppState.micLevel.value = 0f
        AppState.vadProbability.value = 0f
        AppState.inputGain.value = 1f
        AppState.bufferedBytes.value = 0
        AppState.waveform.value = emptyList()
        AppState.pushToTalk.value = false
        // Pieces already recorded keep translating after stop (they run in [finishing]); the piece being
        // spoken when stop was tapped is closed and sent by the mic loop on its way out.
        com.example.lingualocal.data.ProjectStore.flush()
        super.onDestroy()
    }

    private suspend fun runPipeline() = coroutineScope {
        AppState.session = System.currentTimeMillis()
        AppState.running.value = true
        val speaker = Speaker(applicationContext)
        val translator = SegmentTranslator(applicationContext, finishing, speaker)
        // Voice fingerprints for "who is speaking" (on-device, ONNX Runtime). Loads in the background.
        val speakers = async(Dispatchers.Default) {
            runCatching { SpeakerId(applicationContext) }
                .onSuccess { AppState.speakerStatus.value = "Running on-device (ONNX Runtime)" }
                .onFailure { Log.e(TAG, "speaker model failed", it); AppState.speakerStatus.value = "Failed: ${it.message?.take(80)}" }
                .getOrNull()
        }
        val interpreter = LiveInterpreter()
        val vad = try {
            withContext(Dispatchers.Default) { SileroVad(applicationContext) }.also {
                AppState.vadStatus.value = "Running on-device (ONNX Runtime)"
            }
        } catch (e: Exception) {
            Log.e(TAG, "VAD failed to load", e)
            AppState.vadStatus.value = "Failed: ${e.message?.take(80)} · using volume gate"
            null
        }
        // Unlimited: while offline, audio waits here (32 KB per second of speech) instead of being dropped.
        val audioOut = Channel<AudioChunk>(Channel.UNLIMITED)

        launch(Dispatchers.IO) { interpreter.run(audioOut) }
        // Push-to-talk falls back to Gemini (per-segment translation) if on-device recognition fails.
        val pushToTalk = PushToTalk(applicationContext, this, speaker) { id, pcm -> translator.submit(id, pcm) }
        // Switching "Speak translations" off cuts any speech that is already playing.
        launch { AppState.speakAloud.collect { if (!it) speaker.stop() } }
        try {
            withContext(Dispatchers.IO) { micLoop(this, vad, translator, speakers, pushToTalk, audioOut) }
        } finally {
            if (speakers.isCompleted) speakers.getCompleted()?.close() else speakers.cancel()
            pushToTalk.close()
            speaker.close()
            vad?.close()
            audioOut.close()
        }
    }

    @SuppressLint("MissingPermission") // checked in onStartCommand
    private fun micLoop(
        scope: CoroutineScope, vad: SileroVad?, translator: SegmentTranslator,
        speakers: Deferred<SpeakerId?>, pushToTalk: PushToTalk, out: SendChannel<AudioChunk>,
    ) {
        val rate = Config.MIC_SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val proj = projection
        val phone = proj != null && Build.VERSION.SDK_INT >= 29
        val recorder = if (phone) {
            // Phone audio: what other apps play (videos, music, games). Our own spoken translations and replays
            // use the voice-communication usage, which isn't captured, so they never loop back in.
            AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(minBuf, rate))
                .setAudioPlaybackCaptureConfig(
                    android.media.AudioPlaybackCaptureConfiguration.Builder(proj!!)
                        .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                        .excludeUid(android.os.Process.myUid())
                        .build()
                )
                .build()
        } else AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, // enables the platform echo canceller path
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate),
        )
        val aec = if (!phone && AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
        } else null
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        // Voice-call mode: the spoken translation goes through the same path the echo canceller listens to,
        // so the mic can stay on while it plays. Loudspeaker, not the earpiece.
        val previousMode = audioManager.mode
        if (!phone) runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                if (!hasHeadset(audioManager)) audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { audioManager.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = !hasHeadset(audioManager)
            }
        }.onFailure { Log.w(TAG, "communication mode failed: $it") }
        Log.d(TAG, "echo canceller=${aec?.enabled} mode=${audioManager.mode}")

        val frame = ShortArray(SileroVad.FRAME)
        val floats = FloatArray(SileroVad.FRAME)
        val preRoll = ArrayDeque<ByteArray>()
        val batch = ByteArrayOutputStream()
        val segmentAudio = ByteArrayOutputStream()
        val wave = ArrayDeque<WaveBar>()
        val agc = AutoGain()

        var speaking = false
        var hangover = 0
        var headset = false
        var segmentId = -1L
        var segmentStart = 0L
        var frameCount = 0L
        var lastDeviceCheck = 0L
        var lastUiUpdate = 0L
        var lastDurationUpdate = 0L
        var lastLog = 0L
        // On-device check: a segment is only sent to Gemini once it has enough real voice in it.
        var voicedFrames = 0
        var confirmed = false
        var pttActive = false

        /** Live preview only: the full segment is translated separately when it ends. */
        fun send(chunk: AudioChunk) {
            out.trySend(chunk)
        }

        fun endSegment(now: Long) {
            val id = segmentId
            val pcm = segmentAudio.toByteArray()
            if (confirmed) {
                if (batch.size() > 0) send(AudioChunk(id, batch.toByteArray()))
                AppState.updateCaption(id) { it.copy(durationMs = now - segmentStart) }
                translator.submit(id, pcm)
                val voicedMs = voicedFrames * 32L
                scope.launch(Dispatchers.Default) {
                    val who = runCatching { speakers.await()?.identify(pcm, voicedMs) }
                        .onFailure { Log.w(TAG, "speaker id failed: $it") }.getOrNull()
                    if (who != null) AppState.updateCaption(id) { it.copy(speaker = who) }
                }
            } else {
                // Too little voice (a click, a cough, background noise): never sent to Gemini.
                Log.d(TAG, "segment #$id skipped on device: only ${voicedFrames * 32} ms of voice")
                AppState.updateCaption(id) { it.copy(durationMs = now - segmentStart, status = SegmentStatus.SKIPPED) }
            }
            batch.reset()
            segmentAudio.reset()
            finishing.launch(Dispatchers.IO) {
                val path = runCatching { SegmentAudio.save(applicationContext, id, pcm) }.getOrNull()
                if (path != null) AppState.updateCaption(id) { it.copy(audioFile = path) }
            }
        }

        fun startSegment(now: Long) {
            segmentId = AppState.newCaption()
            segmentStart = now
            voicedFrames = 0
            confirmed = false
            preRoll.forEach { batch.write(it); segmentAudio.write(it) }
            preRoll.clear()
        }

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
                frameCount++

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
                // Never pause: the app's own audio is removed by the echo canceller, and leftovers are
                // dropped later by text (Speaker.isEcho).
                val echoGuard = false
                val voice = prob > SPEECH_THRESHOLD && !echoGuard
                if (voice) hangover = HANGOVER_FRAMES else if (hangover > 0) hangover--
                val nowSpeaking = voice || (speaking && hangover > 0 && !echoGuard)

                if (voice || (pttActive && prob > SPEECH_THRESHOLD)) agc.track(rms)
                val bytes = agc.apply(frame)

                // Push-to-talk: while the button is held, the mic goes to the English recognizer instead.
                val pttWanted = AppState.pushToTalk.value && !phone // hold-to-speak needs the mic
                if (pttWanted != pttActive) {
                    if (pttWanted) {
                        if (speaking) endSegment(now)
                        speaking = false
                        hangover = 0
                        pushToTalk.start()
                    } else {
                        pushToTalk.stop()
                    }
                    pttActive = pttWanted
                }

                if (pttActive) {
                    pushToTalk.feed(bytes)
                } else if (nowSpeaking) {
                    if (!speaking) startSegment(now)
                    if (voice) voicedFrames++
                    batch.write(bytes)
                    segmentAudio.write(bytes)
                    if (!confirmed && voicedFrames >= MIN_VOICED_FRAMES) {
                        confirmed = true // real speech: start streaming it, including what was held back
                    }
                    if (confirmed && batch.size() >= BATCH_BYTES) {
                        send(AudioChunk(segmentId, batch.toByteArray()))
                        batch.reset()
                    }
                    val length = now - segmentStart
                    if (length >= MAX_SEGMENT_MS || (length >= SOFT_CUT_MS && prob < BREATH_THRESHOLD)) {
                        // Non-stop speech (a talk, a video) is cut at a breath so translations keep flowing
                        // piece by piece. Each piece gets its own card and its own translation.
                        endSegment(now)
                        startSegment(now)
                    } else if (now - lastDurationUpdate > 250) {
                        val id = segmentId
                        val d = now - segmentStart
                        AppState.updateCaption(id) { it.copy(durationMs = d) }
                        lastDurationUpdate = now
                    }
                } else {
                    if (speaking) endSegment(now)
                    preRoll.addLast(bytes)
                    if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                }
                if (!pttActive) speaking = nowSpeaking

                wave.addLast(WaveBar((rms * 6f).coerceIn(0f, 1f), speaking || pttActive, echoGuard && !pttActive))
                if (wave.size > WAVE_BARS) wave.removeFirst()

                if (frameCount % 2 == 0L) AppState.waveform.value = wave.toList()
                if (now - lastUiUpdate > 80) {
                    AppState.micLevel.value = (rms * 6f).coerceIn(0f, 1f)
                    AppState.vadProbability.value = prob
                    AppState.speechActive.value = speaking
                    AppState.echoGuard.value = echoGuard
                    AppState.inputGain.value = agc.gain
                    lastUiUpdate = now
                }
                if (now - lastLog > 2_000) {
                    Log.d(TAG, "mic rms=%.4f vad=%.2f gain=%.1f speaking=%b echoGuard=%b buffered=%d"
                        .format(rms, prob, agc.gain, speaking, echoGuard, AppState.bufferedBytes.value))
                    lastLog = now
                }
            }
        } finally {
            // Stop tapped mid-sentence: what was said so far is a real piece, so translate it.
            if (speaking && !pttActive && segmentId >= 0) {
                runCatching { endSegment(SystemClock.elapsedRealtime()) }.onFailure { Log.w(TAG, "final segment failed: $it") }
            }
            runCatching { recorder.stop() }
            recorder.release()
            aec?.release()
            if (!phone) runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice()
                else @Suppress("DEPRECATION") { audioManager.isSpeakerphoneOn = false }
                audioManager.mode = previousMode
            }
        }
    }

    /**
     * Automatic gain for quiet or distant speech: learns the loudness of recent speech and boosts it
     * towards a target level (up to [MAX_GAIN]x), with a soft limiter so loud peaks don't clip.
     */
    private class AutoGain {
        var gain = 1f
            private set
        private var speechRms = 0f

        fun track(rms: Float) {
            speechRms = if (speechRms == 0f) rms else speechRms * 0.95f + rms * 0.05f
            val target = (TARGET_RMS / speechRms.coerceAtLeast(1e-4f)).coerceIn(1f, MAX_GAIN)
            // Turn down quickly when it gets loud, turn up slowly.
            gain += (target - gain) * if (target < gain) 0.3f else 0.03f
        }

        fun apply(frame: ShortArray): ByteArray {
            val buf = ByteBuffer.allocate(frame.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in frame) {
                var x = s / 32768f * gain
                if (abs(x) > KNEE) x = sign(x) * (KNEE + (1 - KNEE) * tanh((abs(x) - KNEE) / (1 - KNEE)))
                buf.putShort((x * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            }
            return buf.array()
        }

        private companion object {
            const val TARGET_RMS = 0.08f
            const val MAX_GAIN = 8f
            const val KNEE = 0.8f
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

    private fun startInForeground(phone: Boolean) {
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
            .setContentText(if (phone) "Translating phone audio live" else "Translating ${AppState.pair.label} live")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
        val type = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> 0
            phone -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        /**
         * Outlives the service: recorded pieces finish translating (and their audio is saved) after Stop,
         * instead of being cancelled and shown as "No words".
         */
        private val finishing = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private const val TAG = "ListenService"
        private const val CHANNEL_ID = "listen"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.example.lingualocal.STOP"
        private const val SPEECH_THRESHOLD = 0.5f
        private const val HANGOVER_FRAMES = 25 // 25 x 32 ms = 0.8 s
        private const val PRE_ROLL_FRAMES = 10 // 0.32 s kept so word starts aren't clipped
        private const val BATCH_BYTES = 3_200 // ~100 ms per network message
        private const val SOFT_CUT_MS = 8_000L // after this, cut at the next breath
        private const val MAX_SEGMENT_MS = 15_000L // hard cut if there is no breath
        private const val BREATH_THRESHOLD = 0.3f
        private const val MIN_VOICED_FRAMES = 10 // 10 x 32 ms = 0.32 s of clear voice before anything is sent
        private const val WAVE_BARS = 150 // 150 x 32 ms = 4.8 s of waveform

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, ListenService::class.java))

        fun stop(context: Context) =
            context.startService(Intent(context, ListenService::class.java).setAction(ACTION_STOP))
    }
}
