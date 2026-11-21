package com.example.lingualocal.audio

import com.example.lingualocal.data.Lang
import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.lingualocal.AppState
import com.example.lingualocal.SegmentStatus
import com.example.lingualocal.ai.OfflineTranslator
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Push-to-talk: the user holds the button and speaks English.
 * Speech -> ML Kit GenAI speech recognition (on-device, en-US) -> live English text
 *        -> ML Kit translation (on-device) -> live Thai text -> Thai voice on release.
 * The mic stays owned by [ListenService]; it feeds the PCM here while the button is held.
 * If on-device recognition is not available, the recording is translated by Gemini instead.
 */
class PushToTalk(
    private val context: Context,
    private val scope: CoroutineScope,
    private val speaker: Speaker,
    /** The project being recorded. */
    private val space: com.example.lingualocal.ProjectSpace,
    /** Sends a finished recording to Gemini (fallback). */
    private val toGemini: (id: Long, pcm: ByteArray) -> Unit,
) {
    private val recognizer = SpeechRecognition.getClient(
        speechRecognizerOptions {
            locale = Locale.US
            preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
        }
    )
    @Volatile private var available = false

    private class Take(val id: Long, val out: OutputStream?, val source: AudioSource?) {
        val pcm = ByteArrayOutputStream()
        val writes = Channel<ByteArray>(Channel.UNLIMITED)
        var recognition: Job? = null
        var writer: Job? = null
        val english = MutableStateFlow("")
        var gotText = false
        var failed = false
    }
    private var take: Take? = null

    init {
        scope.launch(Dispatchers.IO) { prepare() }
    }

    private suspend fun prepare() {
        try {
            when (recognizer.checkStatus()) {
                FeatureStatus.AVAILABLE -> available = true
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                    AppState.pushToTalkStatus.value = "Downloading English speech model…"
                    recognizer.download().collect { Log.d(TAG, "download: $it") }
                    available = recognizer.checkStatus() == FeatureStatus.AVAILABLE
                }
                else -> available = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "speech recognition status failed", e)
            available = false
        }
        AppState.pushToTalkStatus.value =
            if (available) "On-device (ML Kit speech + translation)" else "Using Gemini (on-device speech not available)"
        Log.d(TAG, "on-device English recognition available=$available")
    }

    /** Button pressed: start a new "You" card and start recognizing. Called from the mic thread. */
    fun start() {
        speaker.stop()
        val id = space.newCaption(mine = true, source = com.example.lingualocal.Caption.SOURCE_MIC)
        // On-device recognition is English only; other pairs go straight to Gemini on release.
        if (!available || space.pair != com.example.lingualocal.data.LangPair.TH_EN) {
            take = Take(id, null, null)
            return
        }
        val pipe = ParcelFileDescriptor.createPipe()
        val source = AudioSource.fromPfd(pipe[0], AudioSource.Mode.STREAMING)
        val t = Take(id, ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]), source)
        take = t
        // Writes happen off the mic thread so a slow reader can never stall the microphone.
        t.writer = scope.launch(Dispatchers.IO) {
            try {
                for (b in t.writes) t.out?.write(b)
            } catch (e: Exception) {
                Log.w(TAG, "pipe write failed: ${e.message}")
            } finally {
                runCatching { t.out?.close() }
            }
        }
        t.recognition = scope.launch(Dispatchers.IO) {
            var committed = ""
            try {
                recognizer.startRecognition(speechRecognizerRequest { audioSource = source }).collect { r ->
                    when (r) {
                        is SpeechRecognizerResponse.PartialTextResponse -> t.english.value = (committed + r.text).trim()
                        is SpeechRecognizerResponse.FinalTextResponse -> {
                            committed += r.text + " "
                            t.english.value = committed.trim()
                        }
                        is SpeechRecognizerResponse.ErrorResponse -> {
                            Log.w(TAG, "recognition error", r.e)
                            t.failed = true
                        }
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "recognition failed", e)
                t.failed = true
            } finally {
                runCatching { source.close() }
            }
        }
        // Live: show the English and its Thai translation while the user is still speaking.
        scope.launch(Dispatchers.Default) {
            t.english.collectLatest { en ->
                if (en.isBlank()) return@collectLatest
                t.gotText = true
                space.updateCaption(id) { it.copy(heard = en) }
                val th = runCatching { OfflineTranslator.translate(en) }.getOrNull() ?: return@collectLatest
                space.updateCaption(id) { it.copy(translation = th) }
            }
        }
    }

    /** Called from the mic thread with each frame while the button is held. */
    fun feed(bytes: ByteArray) {
        val t = take ?: return
        t.pcm.write(bytes)
        t.writes.trySend(bytes)
        if (t.pcm.size() % (32_000) < bytes.size) {
            val ms = t.pcm.size() / 32L
            space.updateCaption(t.id) { it.copy(durationMs = ms) }
        }
    }

    /** Button released: finish recognition, final translation, speak the Thai. */
    fun stop() {
        val t = take ?: return
        take = null
        val pcm = t.pcm.toByteArray()
        space.updateCaption(t.id) { it.copy(durationMs = pcm.size / 32L, status = SegmentStatus.TRANSLATING) }
        t.writes.close() // closes the pipe -> recognizer finishes the last words
        scope.launch(Dispatchers.IO) {
            runCatching { SegmentAudio.save(space, t.id, pcm) }
                .getOrNull()?.let { path -> space.updateCaption(t.id) { it.copy(audioFile = path) } }
        }
        scope.launch(Dispatchers.IO) {
            withTimeoutOrNull(5_000) { t.recognition?.join() }
            t.recognition?.cancel()
            val en = t.english.value
            if (t.source == null || (en.isBlank() && (t.failed || pcm.size > 16_000))) {
                // Nothing recognized on-device: let Gemini translate the recording.
                Log.d(TAG, "push-to-talk #${t.id}: sending to Gemini (on-device text=\"$en\")")
                space.updateCaption(t.id) { it.copy(status = SegmentStatus.QUEUED) }
                toGemini(t.id, pcm)
                return@launch
            }
            if (en.isBlank()) {
                space.updateCaption(t.id) { it.copy(status = SegmentStatus.NO_SPEECH) }
                return@launch
            }
            val th = runCatching { OfflineTranslator.translate(en) }.getOrElse {
                Log.w(TAG, "translation failed", it)
                space.updateCaption(t.id) { c -> c.copy(status = SegmentStatus.QUEUED) }
                toGemini(t.id, pcm)
                return@launch
            }
            space.updateCaption(t.id) {
                it.copy(heard = en, translation = th, status = SegmentStatus.DONE, lang = Lang.EN, engine = Lang.DEVICE_ENGINE)
            }
            Log.d(TAG, "push-to-talk #${t.id}: \"$en\" -> \"$th\"")
            speaker.say(th, "ptt-${t.id}", interrupt = true)
        }
    }

    fun close() {
        take?.writes?.close()
        runCatching { recognizer.close() }
    }

    private companion object {
        const val TAG = "PushToTalk"
    }
}
