package com.example.lingualocal.ai

import com.example.lingualocal.data.Lang
import com.example.lingualocal.data.LangPair
import com.google.firebase.ai.GenerativeModel
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.lingualocal.AppState
import com.example.lingualocal.Config
import com.example.lingualocal.SegmentStatus
import com.example.lingualocal.Turn
import com.example.lingualocal.audio.LanguageId
import com.example.lingualocal.audio.People
import com.example.lingualocal.audio.SegmentAudio
import com.example.lingualocal.audio.Speaker
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PromptBlockedException
import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.ResponseStoppedException
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.ServiceDisabledException
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Translates every speech segment on its own: one request per segment (Firebase AI Logic, Gemini),
 * with the segment's recording as audio. Each card gets exactly the transcript and translation of
 * its own audio. Segments run in parallel, so a slow one never holds up the next. A failed request
 * is retried until it succeeds (e.g. after the network comes back), so every piece gets translated.
 * While waiting, audio beyond [MEMORY_LIMIT] is parked on disk so a long time offline can't run out of memory.
 */
class SegmentTranslator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val speaker: Speaker,
    /** The project being recorded: every piece of this session goes into it. */
    private val space: com.example.lingualocal.ProjectSpace,
) {

    /** One model per language pair (the instructions differ). */
    private val models = java.util.concurrent.ConcurrentHashMap<LangPair, GenerativeModel>()
    private fun model(pair: LangPair) = models.getOrPut(pair) { buildModel(pair) }

    private fun buildModel(pair: LangPair): GenerativeModel {
        val line = mapOf(
            "speaker" to Schema.string("A, B, C… in order of first appearance in this recording"),
            "transcript" to Schema.string("What this person said, in the original language"),
            "translation" to Schema.string("Translation into the other language"),
            "start" to Schema.double("Second in this recording where this turn starts, e.g. 0.0 or 2.4"),
            "end" to Schema.double("Second in this recording where this turn ends"),
        )
        return Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = Config.SEGMENT_MODEL,
            generationConfig = generationConfig {
                temperature = 0.2f
                // Transcribing + translating needs little reasoning; low thinking answers much faster.
                thinkingConfig = thinkingConfig { thinkingLevel = ThinkingLevel.LOW }
                responseMimeType = "application/json"
                responseSchema = Schema.obj(
                    mapOf(
                        "language" to Schema.enumeration(listOf("th", "en", "my", "none"), "Main language spoken"),
                        "transcript" to Schema.string("Exactly what was said, in the original language"),
                        "translation" to Schema.string("Translation into the other language"),
                        "turns" to Schema.array(Schema.obj(line), "Who said what, in order. A single turn if only one person speaks."),
                    )
                )
            },
            systemInstruction = content { text(Config.segmentPrompt(pair)) },
        )
    }

    /** On-device Thai/English detector for the offline path; loaded the first time it's needed. */
    private val languageId by lazy {
        runCatching { LanguageId(context) }.onFailure { Log.w(TAG, "language ID unavailable: $it") }.getOrNull()
    }

    private val parallel = Semaphore(PARALLEL_REQUESTS)
    private val memoryBytes = AtomicLong(0)

    /** Queue one segment's 16 kHz PCM for translation. */
    fun submit(id: Long, pcm: ByteArray, voicedMs: Long = pcm.size / 32L) {
        val size = pcm.size
        val pair = space.pair // the project's pair when this was recorded
        AppState.bufferedBytes.update { it + size }
        space.updateCaption(id) { if (it.done) it else it.copy(status = SegmentStatus.QUEUED) }
        // Keep the audio in memory, or park it on disk when a lot is already waiting (long offline stretch).
        val held = AtomicReference<ByteArray?>(pcm)
        val spill = if (memoryBytes.get() + size > MEMORY_LIMIT) File(context.cacheDir, "pending_$id.pcm") else null
        if (spill == null) memoryBytes.addAndGet(size.toLong())
        scope.launch(Dispatchers.IO) {
            var parked = false
            if (spill != null) {
                parked = runCatching { spill.writeBytes(held.get()!!) }.isSuccess
                if (parked) held.set(null) else memoryBytes.addAndGet(size.toLong())
            }
            var backoff = 1_000L
            var attempt = 0
            try {
                while (true) {
                    attempt++
                    if (!online() && !pair.worksOffline) {
                        // No on-device Burmese: wait for the internet instead of guessing.
                        space.updateCaption(id) { it.copy(status = SegmentStatus.QUEUED, note = "${Lang.name(pair.b)} needs internet · will translate when it's back") }
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(15_000L)
                        continue
                    }
                    if (!online() && OfflineSpeech.ready) {
                        // No internet: transcribe and translate on the device instead of waiting.
                        translateOffline(id, held.get() ?: spill!!.readBytes(), voicedMs)
                        break
                    }
                    try {
                        val result = parallel.withPermit {
                            space.updateCaption(id) { it.copy(status = SegmentStatus.TRANSLATING) }
                            val wav = SegmentAudio.wav(held.get() ?: spill!!.readBytes())
                            val started = System.currentTimeMillis()
                            val response = model(pair).generateContent(
                                content {
                                    inlineData(wav, "audio/wav")
                                    text("Transcribe and translate this piece of speech.")
                                }
                            )
                            Log.d(TAG, "#$id answered in ${System.currentTimeMillis() - started} ms (attempt $attempt)")
                            JSONObject(response.text ?: "{}")
                        }
                        apply(id, result, held.get() ?: spill!!.readBytes(), voicedMs)
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e is PromptBlockedException || e is ResponseStoppedException) {
                            // Gemini refuses this audio for good; retrying won't change that.
                            Log.w(TAG, "#$id refused by Gemini: ${e.message?.take(200)}")
                            space.updateCaption(id) {
                                it.copy(status = SegmentStatus.NO_SPEECH, note = "Gemini wouldn't process this piece")
                            }
                            break
                        }
                        val (note, permanent) = reason(e)
                        Log.w(TAG, "#$id failed (attempt $attempt): ${e.javaClass.simpleName}: ${e.message?.take(200)} · retrying in ${backoff / 1000} s")
                        space.updateCaption(id) { it.copy(status = SegmentStatus.QUEUED, note = note) }
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(if (permanent) 60_000L else 15_000L)
                    }
                }
            } finally {
                if (parked) spill?.delete() else memoryBytes.addAndGet(-size.toLong())
                AppState.bufferedBytes.update { (it - size).coerceAtLeast(0) }
            }
        }
    }

    /** On-device path: detect Thai/English, ML Kit speech recognition in that language, then ML Kit Translate. */
    private suspend fun translateOffline(id: Long, pcm: ByteArray, voicedMs: Long) {
        space.updateCaption(id) { it.copy(status = SegmentStatus.TRANSLATING, note = "No internet · translating on the device", offline = true) }
        // Thai unless the detector is fairly sure it's English (Thai is the common case here).
        val pThai = languageId?.thaiProbability(pcm) ?: 1f
        val isThai = pThai >= 0.3f || !OfflineSpeech.englishReady
        val heard = OfflineSpeech.transcribe(pcm, isThai).orEmpty()
        if (heard.isBlank()) {
            space.updateCaption(id) { it.copy(status = SegmentStatus.NO_SPEECH, note = null, offline = true) }
            Log.d(TAG, "offline #$id: no words")
            return
        }
        if (Speaker.isEcho(heard)) return markEcho(id, heard)
        val translated = runCatching { OfflineTranslator.translate(heard) }.getOrElse {
            Log.w(TAG, "offline #$id translate failed: $it"); ""
        }
        space.updateCaption(id) {
            it.copy(heard = heard, translation = translated, note = if (translated.isBlank()) "Offline translator not ready" else null,
                status = SegmentStatus.DONE, offline = true, lang = if (isThai) Lang.TH else Lang.EN, engine = Lang.DEVICE_ENGINE)
        }
        Log.d(TAG, "turn done #$id [offline ${if (isThai) "th" else "en"} p=%.2f]: heard".format(pThai) + "=\"$heard\" said=\"$translated\"")
        identifyPeople(id, pcm, voicedMs, emptyList())
        if (translated.isNotBlank()) speaker.say(translated, "seg-$id")
    }

    /** What to tell the user about a failed request, and whether it looks like a setup problem (slower retries). */
    private fun reason(e: Exception): Pair<String, Boolean> {
        val msg = (e.message.orEmpty() + " " + e.cause?.message.orEmpty()).lowercase()
        return when {
            !online() -> "Waiting for internet · will translate when it's back" to false
            "app check" in msg || "appcheck" in msg || "403" in msg || "permission_denied" in msg || "unauthenticated" in msg ->
                "Blocked by Firebase App Check · check the app's App Check setup" to true
            e is QuotaExceededException || "quota" in msg || "429" in msg || "resource_exhausted" in msg ->
                "Gemini quota reached · retrying" to true
            e is ServiceDisabledException -> "Firebase AI Logic is turned off for this project" to true
            else -> "Retrying · ${e.message?.take(60) ?: e.javaClass.simpleName}" to false
        }
    }

    private fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun apply(id: Long, json: JSONObject, pcm: ByteArray, voicedMs: Long) {
        val language = json.optString("language")
        val transcript = json.optString("transcript").trim()
        val translation = json.optString("translation").trim()
        val words = language != "none" && transcript.isNotBlank() && translation.isNotBlank()
        // Each turn with where it is in the audio (seconds), to match its voice to a person.
        val timed = json.optJSONArray("turns")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
                val heard = o.optString("transcript").trim()
                if (heard.isBlank()) null
                else Turn(o.optString("speaker").trim().ifBlank { "?" }, heard, o.optString("translation").trim(), Lang.of(heard)) to
                    (o.optDouble("start", -1.0) to o.optDouble("end", -1.0))
            }
        }.orEmpty()
        val turns = timed.map { it.first }
        // Only show the split when Gemini really heard more than one voice in this piece.
        val split = if (turns.map { it.speaker }.distinct().size > 1) turns else emptyList()
        if (words && Speaker.isEcho(transcript)) return markEcho(id, transcript)
        space.updateCaption(id) {
            if (words) it.copy(
                heard = transcript, translation = translation, turns = split, note = null, status = SegmentStatus.DONE,
                lang = language.takeIf { l -> l == Lang.TH || l == Lang.EN || l == Lang.MY } ?: Lang.of(transcript),
                engine = Lang.cloudEngine(Config.SEGMENT_MODEL),
            )
            else it.copy(heard = "", translation = "", note = null, status = SegmentStatus.NO_SPEECH)
        }
        Log.d(TAG, "turn done #$id [$language]: heard=\"$transcript\" said=\"$translation\"" +
            if (split.isNotEmpty()) " voices=${split.map { it.speaker }.distinct()}" else "")
        if (words) identifyPeople(id, pcm, voicedMs, if (split.isNotEmpty()) timed else emptyList())
        if (words) speaker.say(translation, "seg-$id")
    }

    /**
     * Matches who spoke to the people of this project, on the device. With turns (two or more voices), each
     * voice's stretches of audio are cut out and matched on their own; otherwise the whole piece is one person.
     */
    private fun identifyPeople(id: Long, pcm: ByteArray, voicedMs: Long, timed: List<Pair<Turn, Pair<Double, Double>>>) {
        if (space.caption(id)?.mine == true) return // the user's own push-to-talk
        scope.launch(Dispatchers.Default) {
            runCatching {
                val byVoice = LinkedHashMap<String, java.io.ByteArrayOutputStream>()
                for ((t, span) in timed) {
                    val (s, e) = span
                    if (s < 0 || e <= s) continue
                    var from = (s * 16_000).toInt() * 2
                    var to = (e * 16_000).toInt() * 2
                    if (to - from > 19_200) { from += 3_200; to -= 3_200 } // drop 0.1 s at each edge: less of the other voice
                    from = from.coerceIn(0, pcm.size); to = to.coerceIn(from, pcm.size)
                    if (to > from) byVoice.getOrPut(t.speaker) { java.io.ByteArrayOutputStream() }.write(pcm, from, to - from)
                }
                val people = byVoice.mapValues { (_, b) -> People.identify(space.id, b.toByteArray(), b.size() / 32L) }
                if (people.values.any { it != null }) {
                    space.updateCaption(id) { c ->
                        val withPeople = c.turns.map { t -> t.copy(person = people[t.speaker]) }
                        val main = withPeople.filter { it.person != null }.maxByOrNull { it.heard.length }?.person
                        c.copy(turns = withPeople, speaker = main ?: c.speaker)
                    }
                    Log.d(TAG, "#$id people by turn: $people")
                } else {
                    val who = People.identify(space.id, pcm, voicedMs)
                    if (who != null) space.updateCaption(id) { it.copy(speaker = who) }
                }
            }.onFailure { Log.w(TAG, "#$id speaker match failed: $it") }
        }
    }

    /** The mic picked up our own spoken translation: keep it out of the conversation and don't speak it again. */
    private fun markEcho(id: Long, heard: String) {
        space.updateCaption(id) { it.copy(heard = "", translation = "", turns = emptyList(), note = "Echo of the app's voice · ignored", status = SegmentStatus.SKIPPED) }
        Log.d(TAG, "turn #$id ignored: echo of our own voice \"$heard\"")
    }

    companion object {
        private const val TAG = "SegmentTranslator"
        private const val PARALLEL_REQUESTS = 3
        private const val MEMORY_LIMIT = 16L * 1024 * 1024 // ~8 min of waiting speech in memory, the rest on disk

        /** Removes audio parked by a previous run (its captions are gone). */
        fun clearParked(context: Context) {
            context.cacheDir.listFiles { f -> f.name.startsWith("pending_") }?.forEach { it.delete() }
        }
    }
}
