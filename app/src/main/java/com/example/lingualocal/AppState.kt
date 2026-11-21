package com.example.lingualocal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** Where a speech segment is in the pipeline. */
/** NO_SPEECH = Gemini heard no words. SKIPPED = the on-device check found only noise, nothing was sent. */
enum class SegmentStatus { RECORDING, QUEUED, TRANSLATING, DONE, NO_SPEECH, SKIPPED }

/**
 * One earlier version of a piece, kept when it was replaced. History is append-only: every fix (by Ask) and every
 * undo (by the user) adds the version it replaced, so all versions stay in the project as training data.
 */
data class Revision(
    /** The text as it was before this change. */
    val heard: String,
    val translation: String,
    val lang: String,
    /** Engine that made that replaced text. */
    val engine: String,
    val turns: List<Turn> = emptyList(),
    /** Why it was replaced, e.g. "missed the negative" (shown to the user). */
    val reason: String,
    /** Who replaced it: "cloud:<model>" for Ask, USER_UNDO when the user undid a fix. */
    val by: String,
    val time: Long = System.currentTimeMillis(),
) {
    companion object { const val USER_UNDO = "user:undo" }
}

/** One person's part of a segment, when Gemini hears more than one voice in it (A, B, … within that piece). */
data class Turn(
    val speaker: String,
    val heard: String,
    val translation: String,
    /** Language this person spoke, "th" or "en" (see data.Lang). */
    val lang: String = "",
)

/** One speech segment (cut by the on-device voice detector) and its live translation. */
data class Caption(
    val id: Long,
    /** Wall-clock time the speech started (System.currentTimeMillis). */
    val time: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val status: SegmentStatus = SegmentStatus.RECORDING,
    val heard: String = "",
    /** Running transcript from Gemini Live while the segment is still being recorded (replaced by [heard]). */
    val preview: String = "",
    val translation: String = "",
    /** Recorded audio of this segment (16 kHz WAV), for replay. */
    val audioFile: String? = null,
    val explanation: String? = null,
    val explainSource: String? = null,
    /** True for the user's own push-to-talk speech (English -> Thai). */
    val mine: Boolean = false,
    /** Who is speaking (1, 2, …), matched on-device by voice across the whole session. Null = unsure. */
    val speaker: Int? = null,
    /** Who said what inside this piece, only when Gemini heard more than one voice in it. */
    val turns: List<Turn> = emptyList(),
    /** Why the translation is waiting (no internet, quota, App Check…), shown on the card. */
    val note: String? = null,
    /** Translated on the device (no internet): ML Kit speech recognition + ML Kit Translate. */
    val offline: Boolean = false,
    /** Start time of the listening session this piece belongs to (one tap of Start listening = one session). */
    val session: Long = 0,
    /** Language spoken in this piece, "th" or "en" (data.Lang); it is translated into the other one. */
    val lang: String = "",
    /** What made the transcript and translation: "cloud:<model>" or "device:mlkit" (data.Lang). */
    val engine: String = "",
    /** Earlier versions, oldest first (append-only). */
    val revisions: List<Revision> = emptyList(),
) {
    /** The latest change, when it was a fix by Ask that the user hasn't undone. */
    val correction: Revision? get() = revisions.lastOrNull()?.takeIf { it.by != Revision.USER_UNDO }
    val done: Boolean get() = status == SegmentStatus.DONE || status == SegmentStatus.NO_SPEECH || status == SegmentStatus.SKIPPED
}

/** One bar of the live waveform: mic level plus what the pipeline was doing at that moment. */
data class WaveBar(val level: Float, val speech: Boolean, val muted: Boolean)

/** A folder for one real-life situation; holds every session recorded in it. Stored on the phone only. */
data class Project(
    val id: String,
    val name: String,
    val created: Long,
    val lastUsed: Long,
    val items: Int = 0,
    val sessions: Int = 0,
    val totalMs: Long = 0,
    /** Language pair, a data.LangPair code ("th-en", "th-my"). */
    val pair: String = "th-en",
)

/** Where listening takes its sound from. */
enum class AudioSource { MIC, PHONE }

/** Process-wide state shared by the listening service and the UI. */
object AppState {
    val running = MutableStateFlow(false)

    /** Mic (people in the room) or phone audio (YouTube, videos, other apps). Chosen before listening starts. */
    val source = MutableStateFlow(AudioSource.MIC)
    /** Screen-capture consent for phone audio, handed from the activity to the service. Single use. */
    @Volatile var projectionConsent: android.content.Intent? = null
    /** Source of the session that is running now. */
    val runningSource = MutableStateFlow(AudioSource.MIC)
    val liveStatus = MutableStateFlow("Idle")
    val vadStatus = MutableStateFlow("Not loaded")
    val nanoStatus = MutableStateFlow("Checking…")
    val gemmaStatus = MutableStateFlow("Checking…")
    val offlineStatus = MutableStateFlow("Preparing…")
    val offlineSpeechStatus = MutableStateFlow("Checking…")
    val speakerStatus = MutableStateFlow("Not loaded")

    val micLevel = MutableStateFlow(0f)
    val vadProbability = MutableStateFlow(0f)
    val speechActive = MutableStateFlow(false)
    val echoGuard = MutableStateFlow(false)

    /** Last ~5 s of mic activity, newest last. */
    val waveform = MutableStateFlow<List<WaveBar>>(emptyList())

    /** Audio recorded but not yet delivered to Gemini (e.g. while offline), in bytes of 16 kHz PCM16. */
    val bufferedBytes = MutableStateFlow(0L)

    /** Current automatic boost applied to quiet speech before it is sent to Gemini (1 = none). */
    val inputGain = MutableStateFlow(1f)

    /** True while an original recording is being replayed (the mic ignores it). */
    val replaying = MutableStateFlow(false)

    /** Speak translations out loud. Off = text only, and the mic never pauses for our own voice. */
    val speakAloud = MutableStateFlow(true)

    /** All projects on the phone, most recently used first. */
    val projects = MutableStateFlow<List<Project>>(emptyList())

    /** The open project; its captions are in [captions]. Null = projects home. */
    val currentProject = MutableStateFlow<Project?>(null)
    /** Language pair of the open project; new pieces are interpreted in it. */
    val pair: com.example.lingualocal.data.LangPair get() = com.example.lingualocal.data.LangPair.of(currentProject.value?.pair)

    /** Pair picked on the home screen for the next new project; null = the pair of the project used last. */
    val nextPair = MutableStateFlow<com.example.lingualocal.data.LangPair?>(null)

    /** Start time of the current listening session. */
    @Volatile var session: Long = 0

    val captions = MutableStateFlow<List<Caption>>(emptyList())
    private val nextId = AtomicLong(1)

    /** After loading a project, new pieces continue after its highest id (ids also name the audio files). */
    fun continueIds() = nextId.set((captions.value.maxOfOrNull { it.id } ?: 0) + 1)

    /** Held down while the user speaks English with the push-to-talk button. */
    val pushToTalk = MutableStateFlow(false)
    val pushToTalkStatus = MutableStateFlow("Checking…")

    /** True while the Thai voice is reading out the user's translation (the mic ignores it). */
    val ttsSpeaking = MutableStateFlow(false)

    fun newCaption(mine: Boolean = false): Long {
        val id = nextId.getAndIncrement()
        val s = session.takeIf { it > 0 } ?: System.currentTimeMillis()
        captions.update { it + Caption(id, mine = mine, session = s) }
        return id
    }

    fun caption(id: Long): Caption? = captions.value.firstOrNull { it.id == id }

    fun updateCaption(id: Long, transform: (Caption) -> Caption) {
        captions.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

}
