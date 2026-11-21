package com.example.lingualocal.ai

import com.example.lingualocal.Config
import com.example.lingualocal.data.Lang
import com.example.lingualocal.data.LangPair
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend

/** Typed text in pairs the device can't translate on its own (Thai ⇄ Burmese): Gemini, directly between the two. */
object CloudText {
    private val model by lazy { Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(Config.CLOUD_TEXT_MODEL) }

    suspend fun translate(text: String, pair: LangPair): String {
        val from = Lang.of(text)
        val to = Lang.other(from, pair)
        val script = if (to == Lang.MY) " in Myanmar Unicode script" else ""
        val r = model.generateContent(
            "Translate this ${Lang.name(from)} text directly into natural ${Lang.name(to)}$script. " +
                "Reply with the translation only.\n\n$text"
        )
        return r.text?.trim().orEmpty()
    }
}
