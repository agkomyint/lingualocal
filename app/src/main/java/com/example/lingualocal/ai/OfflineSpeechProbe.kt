package com.example.lingualocal.ai

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import java.util.Locale

/** Logs which offline (on-device) speech recognizers this device has for Thai and English. */
object OfflineSpeechProbe {
    private const val TAG = "OfflineProbe"

    suspend fun run(context: Context) {
        // 1. ML Kit GenAI speech recognition (basic + advanced modes).
        for (tag in listOf("th-TH", "en-US")) {
            for (mode in listOf(SpeechRecognizerOptions.Mode.MODE_BASIC, SpeechRecognizerOptions.Mode.MODE_ADVANCED)) {
                val status = runCatching {
                    val client = SpeechRecognition.getClient(speechRecognizerOptions {
                        locale = Locale.forLanguageTag(tag)
                        preferredMode = mode
                    })
                    client.checkStatus().also { client.close() }
                }.fold({ "status=$it" }, { "error=${it.message?.take(120)}" })
                Log.d(TAG, "ML Kit $tag mode=$mode -> $status")
            }
        }
        // 2. Android's on-device SpeechRecognizer (Android System Intelligence).
        Handler(Looper.getMainLooper()).post {
            Log.d(TAG, "Android on-device recognizer available=${SpeechRecognizer.isOnDeviceRecognitionAvailable(context)}")
            if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "th-TH")
                r.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                    override fun onSupportResult(s: RecognitionSupport) {
                        Log.d(TAG, "Android on-device installed=${s.installedOnDeviceLanguages}")
                        Log.d(TAG, "Android on-device pending=${s.pendingOnDeviceLanguages}")
                        Log.d(TAG, "Android on-device supported=${s.supportedOnDeviceLanguages}")
                        Log.d(TAG, "Android online-only=${s.onlineLanguages.size} languages")
                        r.destroy()
                    }
                    override fun onError(error: Int) {
                        Log.d(TAG, "Android on-device checkRecognitionSupport error=$error")
                        r.destroy()
                    }
                })
            }
        }
    }

    private fun thaiClient() = SpeechRecognition.getClient(speechRecognizerOptions {
        locale = Locale.forLanguageTag("th-TH")
        preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
    })

    private fun thaiIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "th-TH")

    /** Downloads the Thai offline speech models (needs internet once). */
    suspend fun download(context: Context) {
        val c = thaiClient()
        runCatching { c.download().collect { Log.d(TAG, "ML Kit th download: $it") } }
            .onFailure { Log.w(TAG, "ML Kit th download failed: $it") }
        Log.d(TAG, "ML Kit th status after download=${c.checkStatus()}")
        c.close()
        OfflineTranslator.prepare()
        if (Build.VERSION.SDK_INT >= 33) withContext(Dispatchers.Main) {
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            runCatching { r.triggerModelDownload(thaiIntent()) }
                .onSuccess { Log.d(TAG, "Android th-TH model download triggered") }
                .onFailure { Log.w(TAG, "Android th-TH download failed: $it") }
            r.destroy()
        }
    }

    private fun feed(pipe: ParcelFileDescriptor, pcm: ByteArray, silenceChunks: Int) = Thread {
        runCatching {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe).use { out ->
                var i = 0
                while (i < pcm.size) {
                    val n = minOf(3200, pcm.size - i)
                    out.write(pcm, i, n); i += n; Thread.sleep(100)
                }
                repeat(silenceChunks) { out.write(ByteArray(3200)); Thread.sleep(100) }
            }
        }
    }.start()

    /** Speaks a Thai sentence with the device voice, then recognizes it with each offline recognizer. */
    suspend fun test(context: Context) {
        val sentence = "สวัสดีครับ ขอโทษนะครับ ห้องน้ำอยู่ที่ไหนครับ"
        val pcm = synthThai(context, sentence) ?: return
        Log.d(TAG, "TEST input \"$sentence\" (${pcm.size / 32} ms of audio)")

        // ML Kit GenAI speech recognition, Thai, basic mode.
        val c = thaiClient()
        Log.d(TAG, "ML Kit th status=${c.checkStatus()}")
        val pipe = ParcelFileDescriptor.createPipe()
        val source = AudioSource.fromPfd(pipe[0], AudioSource.Mode.STREAMING)
        val started = System.currentTimeMillis()
        feed(pipe[1], pcm, 10)
        var text = ""
        withTimeoutOrNull(30_000) {
            runCatching {
                c.startRecognition(speechRecognizerRequest { audioSource = source }).collect { r ->
                    when (r) {
                        is SpeechRecognizerResponse.FinalTextResponse -> { text += r.text; Log.d(TAG, "ML Kit final: ${r.text}") }
                        is SpeechRecognizerResponse.ErrorResponse -> Log.w(TAG, "ML Kit error: ${r.e}")
                        else -> Unit
                    }
                }
            }.onFailure { Log.w(TAG, "ML Kit failed: $it") }
        }
        Log.d(TAG, "RESULT ML Kit th: \"$text\" in ${System.currentTimeMillis() - started} ms")
        if (text.isNotBlank()) {
            val en = runCatching { OfflineTranslator.translate(text) }.getOrElse { "translate failed: ${it.message}" }
            Log.d(TAG, "RESULT offline translation: \"$en\"")
        }
        c.close()

        // Android on-device SpeechRecognizer, Thai, fed with the same audio.
        if (Build.VERSION.SDK_INT >= 33) {
            val result = CompletableDeferred<String>()
            val p2 = ParcelFileDescriptor.createPipe()
            val t0 = System.currentTimeMillis()
            val r = withContext(Dispatchers.Main) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onResults(b: Bundle) { result.complete(b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()) }
                        override fun onError(error: Int) { result.complete("error $error") }
                        override fun onPartialResults(b: Bundle) {}
                        override fun onReadyForSpeech(p: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(v: Float) {}
                        override fun onBufferReceived(b: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onEvent(t: Int, p: Bundle?) {}
                    })
                    startListening(thaiIntent()
                        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, p2[0])
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000))
                }
            }
            feed(p2[1], pcm, 20)
            val text2 = withTimeoutOrNull(30_000) { result.await() } ?: "timeout"
            withContext(Dispatchers.Main) { r.destroy() }
            Log.d(TAG, "RESULT Android th: \"$text2\" in ${System.currentTimeMillis() - t0} ms")
            if (text2.isNotBlank() && !text2.startsWith("error") && text2 != "timeout") {
                val en = runCatching { OfflineTranslator.translate(text2) }.getOrElse { "translate failed: ${it.message}" }
                Log.d(TAG, "RESULT offline translation: \"$en\"")
            }
        }
    }

    /** Thai text -> 16 kHz PCM16 using the device's Thai voice. */
    private suspend fun synthThai(context: Context, text: String): ByteArray? {
        val file = File(context.cacheDir, "probe_th.wav")
        var tts: TextToSpeech? = null
        val ok = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont -> tts = TextToSpeech(context) { cont.resume(it == TextToSpeech.SUCCESS) } }
        }
        if (!ok) { Log.w(TAG, "TTS init failed"); return null }
        val t = tts!!
        t.language = Locale.forLanguageTag("th-TH")
        val done = CompletableDeferred<Unit>()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { done.complete(Unit) }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { done.complete(Unit) }
        })
        t.synthesizeToFile(text, null, file, "probe")
        withTimeoutOrNull(20_000) { done.await() }
        t.shutdown()
        val bytes = file.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val rate = bb.getInt(24)
        var pos = 12
        while (pos + 8 < bytes.size && String(bytes, pos, 4) != "data") pos += 8 + bb.getInt(pos + 4)
        val start = pos + 8
        val n = (bytes.size - start) / 2
        val src = ShortArray(n) { bb.getShort(start + it * 2) }
        val outN = (n.toLong() * 16000 / rate).toInt()
        val out = ByteBuffer.allocate(outN * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until outN) {
            val x = i.toDouble() * rate / 16000
            val a = x.toInt().coerceAtMost(n - 1)
            val b = (a + 1).coerceAtMost(n - 1)
            val f = x - a
            out.putShort((src[a] * (1 - f) + src[b] * f).toInt().toShort())
        }
        Log.d(TAG, "synthesized Thai at $rate Hz -> 16 kHz")
        return out.array()
    }
}
