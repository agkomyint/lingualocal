package com.example.lingualocal

object Config {
    /** Gemini Live model used for the always-on interpreter (cloud, streaming audio). */
    const val LIVE_MODEL = "gemini-3.1-flash-live-preview"

    /** Cloud model the hybrid SDK falls back to when Gemini Nano isn't available on the device. */
    const val CLOUD_TEXT_MODEL = "gemini-3.8-flash"

    const val MIC_SAMPLE_RATE = 16_000
    const val SPEAKER_SAMPLE_RATE = 24_000

    val INTERPRETER_PROMPT = """
        You are LinguaLocal, a live Thai and English interpreter for an expat living in Thailand.
        You hear a continuous stream of real-world conversation.
        - When you hear Thai, say the English translation.
        - When you hear English, say the Thai translation, politely and naturally (use ครับ or ค่ะ where a Thai speaker would).
        Translate only. Never answer questions yourself, never add comments, never greet,
        never explain, and never say that you are translating.
        Keep the speaker's meaning and tone. If the speech is unclear, or is neither Thai nor English, stay silent.
    """.trimIndent()
}
