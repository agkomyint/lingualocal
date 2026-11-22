package com.example.lingualocal.audio

import com.example.lingualocal.data.Lang

enum class VoiceEngine(val id: String) { DEVICE("device"), GEMINI("gemini") }

/** The voice picked for a language, and when it was picked. */
data class VoiceChoice(val engine: VoiceEngine, val since: Long = 0)

/** Which voice reads a piece aloud, and which is paid for without being asked. */
object VoiceRules {
    val DEFAULTS = linkedMapOf(
        Lang.EN to VoiceChoice(VoiceEngine.DEVICE),
        Lang.TH to VoiceChoice(VoiceEngine.DEVICE),
        Lang.MY to VoiceChoice(VoiceEngine.GEMINI),
    )

    fun engine(id: String?): VoiceEngine? = VoiceEngine.entries.firstOrNull { it.id == id }

    /** Picking again keeps the time it was first picked. */
    fun pick(voices: Map<String, VoiceChoice>, lang: String, engine: VoiceEngine, now: Long): Map<String, VoiceChoice> =
        if (voices[lang]?.engine == engine) voices else voices + (lang to VoiceChoice(engine, now))

    /** A one-language project always has Gemini's voice; elsewhere the spoken language's setting decides. */
    fun wantsGemini(alwaysGemini: Boolean, choice: VoiceChoice?) = alwaysGemini || choice?.engine == VoiceEngine.GEMINI

    /** Gemini is paid by itself only for pieces since it was picked, never for untranslated speech in a translation project. */
    fun autoGemini(alwaysGemini: Boolean, choice: VoiceChoice?, time: Long, untranslatedSpeech: Boolean) =
        alwaysGemini || (!untranslatedSpeech && choice?.engine == VoiceEngine.GEMINI && time >= choice.since)

    /** What made a saved voice. Saved before that was noted: Burmese came from Gemini, Thai and English from the phone. */
    fun madeBy(stored: String, lang: String, alwaysGemini: Boolean): VoiceEngine =
        engine(stored) ?: if (alwaysGemini || lang == Lang.MY) VoiceEngine.GEMINI else VoiceEngine.DEVICE

    /** "Play all" plays the saved voice, unless the phone's is picked over Gemini's or a new piece still waits for Gemini's. */
    fun playSaved(wants: Boolean, savedGemini: Boolean, makeNow: Boolean) =
        if (savedGemini) wants else !makeNow
}
