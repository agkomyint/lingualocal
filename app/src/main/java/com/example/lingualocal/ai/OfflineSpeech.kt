package com.example.lingualocal.ai

import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.lingualocal.AppState
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * On-device Thai and English speech recognition (ML Kit GenAI, basic mode) used when there is no internet.
 * The models (Thai ~37 MB) are downloaded automatically on first launch while online; Google ships it
 * through Play services, so they can't be bundled inside the APK.
 */
object OfflineSpeech {
    private const val TAG = "OfflineSpeech"
    private val lock = Mutex()
    private fun newClient(tag: String) = SpeechRecognition.getClient(speechRecognizerOptions {
        locale = Locale.forLanguageTag(tag)
        preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
    })
    private val thaiClient by lazy { newClient("th-TH") }
    private val englishClient by lazy { newClient("en-US") }

    @Volatile var thaiReady = false
        private set
    @Volatile var englishReady = false
        private set
    /** True when at least Thai can be recognized offline (the main case: the other person speaks Thai). */
    val ready get() = thaiReady

    /** Checks the Thai and English models and downloads them if needed. Safe to call repeatedly. */
    suspend fun prepare() = lock.withLock {
        if (thaiReady && englishReady) return@withLock
        thaiReady = thaiReady || ensure(thaiClient, "Thai")
        englishReady = englishReady || ensure(englishClient, "English")
        AppState.offlineSpeechStatus.value = when {
            thaiReady && englishReady -> "Ready offline (ML Kit Thai + English, on-device)"
            thaiReady -> "Thai ready · English not downloaded yet"
            englishReady -> "English ready · Thai not downloaded yet (needs internet once)"
            else -> "Not downloaded yet · needs internet once"
        }
        Log.d(TAG, "offline speech: ${AppState.offlineSpeechStatus.value}")
    }

    private suspend fun ensure(client: com.google.mlkit.genai.speechrecognition.SpeechRecognizer, name: String): Boolean = try {
        var status = client.checkStatus()
        if (status == FeatureStatus.DOWNLOADABLE || status == FeatureStatus.DOWNLOADING) {
            AppState.offlineSpeechStatus.value = "Downloading $name speech model…"
            client.download().collect { }
            status = client.checkStatus()
        }
        status == FeatureStatus.AVAILABLE
    } catch (e: Exception) {
        Log.w(TAG, "$name model not ready: $e")
        false
    }

    /** Transcript of one 16 kHz PCM16 segment in Thai or English, or null if that model isn't available. */
    suspend fun transcribe(pcm: ByteArray, thai: Boolean): String? {
        if (!(if (thai) thaiReady else englishReady)) prepare()
        if (!(if (thai) thaiReady else englishReady)) return null
        val client = if (thai) thaiClient else englishClient
        return lock.withLock {
            val pipe = ParcelFileDescriptor.createPipe()
            val source = AudioSource.fromPfd(pipe[0], AudioSource.Mode.STREAMING)
            Thread {
                runCatching {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                        out.write(pcm)
                        out.write(ByteArray(16000)) // 0.5 s of silence so the last word is finalized
                    }
                }
            }.start()
            val text = StringBuilder()
            val started = System.currentTimeMillis()
            withTimeoutOrNull(30_000 + pcm.size / 16L) {
                runCatching {
                    client.startRecognition(speechRecognizerRequest { audioSource = source }).collect { r ->
                        when (r) {
                            is SpeechRecognizerResponse.FinalTextResponse -> text.append(r.text).append(' ')
                            is SpeechRecognizerResponse.ErrorResponse -> Log.w(TAG, "recognition error: ${r.e}")
                            else -> Unit
                        }
                    }
                }.onFailure { Log.w(TAG, "recognition failed: $it") }
            }
            runCatching { pipe[0].close() }
            // ML Kit separates Thai words with spaces; Thai is written without them.
            val result = text.toString().trim().replace(Regex("(?<=[฀-๿]) (?=[฀-๿])"), "")
            Log.d(TAG, "transcribed ${pcm.size / 32} ms (${if (thai) "th" else "en"}) in ${System.currentTimeMillis() - started} ms: \"$result\"")
            result
        }
    }
}
