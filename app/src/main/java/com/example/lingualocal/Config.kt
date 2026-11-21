package com.example.lingualocal

object Config {
    /** Gemini Live model used for the always-on interpreter (cloud, streaming audio). */
    const val LIVE_MODEL = "gemini-3.1-flash-live-preview"

    /** Cloud model the hybrid SDK falls back to when Gemini Nano isn't available on the device. */
    const val CLOUD_TEXT_MODEL = "gemini-4-hydrogen"

    /** Gemini's speech model: reads translations aloud for languages the phone has no voice for (Burmese). */
    const val TTS_MODEL = "gemini-3.8-flash-tts"
    const val TTS_VOICE = "Puck"

    const val MIC_SAMPLE_RATE = 16_000
    const val SPEAKER_SAMPLE_RATE = 24_000

    /** Model that transcribes and translates each finished speech segment (one request per segment). */
    const val SEGMENT_MODEL = CLOUD_TEXT_MODEL

    val SEGMENT_PROMPT = """
        You are LinguaLocal, a Thai and English interpreter for an expat living in Thailand.
        You get one short recording of real-world speech, cut out by a voice detector. It may start or end
        in the middle of a sentence, and there may be background noise, music or a video playing.
        1. Write down exactly what is said, in the language it is spoken (Thai in Thai script).
        2. Translate it: Thai -> natural English. English -> natural, polite Thai (use ครับ or ค่ะ where a Thai speaker would).
        Translate fragments as they are; never skip quiet or unclear words if you can make them out.
        Only use language "none" (with empty transcript and translation) if there are truly no spoken words at all.
        In "turns", split the speech by who is talking (A, B, … by voice, in order of first appearance).
        If only one person speaks, return one turn. Do not invent extra speakers.
    """.trimIndent()

    /** Thai ⇄ Burmese: translated directly, never through English. */
    val SEGMENT_PROMPT_MY = """
        You are LinguaLocal, a Thai and Burmese (Myanmar) interpreter for Burmese people living and working in Thailand.
        You get one short recording of real-world speech, cut out by a voice detector. It may start or end
        in the middle of a sentence, and there may be background noise, music or a video playing.
        1. Write down exactly what is said, in the language it is spoken: Thai in Thai script, Burmese in Myanmar
           script (standard Unicode, never Zawgyi).
        2. Translate it directly, not through English: Thai -> natural, everyday Burmese. Burmese -> natural, polite
           Thai (use ครับ or ค่ะ where a Thai speaker would). Anything said in another language (e.g. English) -> Burmese.
        Translate fragments as they are; never skip quiet or unclear words if you can make them out.
        Only use language "none" (with empty transcript and translation) if there are truly no spoken words at all.
        In "turns", split the speech by who is talking (A, B, … by voice, in order of first appearance).
        If only one person speaks, return one turn. Do not invent extra speakers.
    """.trimIndent()

    /** English ⇄ Burmese. */
    val SEGMENT_PROMPT_EN_MY = """
        You are LinguaLocal, an English and Burmese (Myanmar) interpreter for Burmese speakers.
        You get one short recording of real-world speech, cut out by a voice detector. It may start or end
        in the middle of a sentence, and there may be background noise, music or a video playing.
        1. Write down exactly what is said, in the language it is spoken: Burmese in Myanmar script (standard Unicode,
           never Zawgyi), English in English, anything else in its own script.
        2. Translate it: English -> natural, everyday Burmese. Burmese -> natural English.
           Anything said in another language (e.g. Thai) -> Burmese.
        Translate fragments as they are; never skip quiet or unclear words if you can make them out.
        Only use language "none" (with empty transcript and translation) if there are truly no spoken words at all.
        In "turns", split the speech by who is talking (A, B, … by voice, in order of first appearance).
        If only one person speaks, return one turn. Do not invent extra speakers.
    """.trimIndent()

    fun segmentPrompt(pair: com.example.lingualocal.data.LangPair) = when (pair) {
        com.example.lingualocal.data.LangPair.TH_MY -> SEGMENT_PROMPT_MY
        com.example.lingualocal.data.LangPair.EN_MY -> SEGMENT_PROMPT_EN_MY
        else -> SEGMENT_PROMPT
    }

    /** Gemini Live is only used for the running transcript while someone talks. */
    val PREVIEW_PROMPT = """
        Listen only. Do not reply, do not translate, do not speak. Stay silent.
    """.trimIndent()
}
