package com.example.lingualocal.data

import java.util.Locale

/**
 * The app's languages, as ISO 639-1 codes ("th", "en", "my"), so every saved piece says its translation
 * direction explicitly (th -> en, my -> th, ...) instead of leaving it to be guessed later.
 */
object Lang {
    const val TH = "th"
    const val EN = "en"
    /** Burmese (Myanmar). */
    const val MY = "my"

    private fun hasMyanmar(text: String) = text.any { it in '\u1000'..'\u109F' }
    private fun hasThai(text: String) = text.any { it in '\u0E00'..'\u0E7F' }

    /** Language of [text] by its script: Burmese, Thai, otherwise English. Empty for blank text. */
    fun of(text: String): String = when {
        text.isBlank() -> ""
        hasMyanmar(text) -> MY
        hasThai(text) -> TH
        else -> EN
    }

    /** The language a piece is translated into, in the Thai ⇄ English pair (Burmese goes to Thai). */
    fun other(lang: String): String = when (lang) {
        TH -> EN
        EN -> TH
        MY -> TH
        else -> ""
    }

    /** The language a piece is translated into, within [pair]. Anything outside the pair goes to its second language. */
    fun other(lang: String, pair: LangPair): String = when (lang) {
        pair.a -> pair.b
        pair.b -> pair.a
        "" -> ""
        else -> pair.b
    }

    /** What a finished piece was translated into, from its language and its translation's script. */
    fun target(lang: String, translation: String): String = when (lang) {
        MY -> if (translation.isNotBlank() && !hasThai(translation)) EN else TH
        TH -> if (hasMyanmar(translation)) MY else EN
        EN -> if (hasMyanmar(translation)) MY else TH
        else -> ""
    }

    fun name(code: String) = when (code) { TH -> "Thai"; EN -> "English"; MY -> "Burmese"; else -> code }

    fun locale(code: String): Locale = when (code) {
        TH -> Locale.forLanguageTag("th-TH")
        MY -> Locale.forLanguageTag("my-MM")
        else -> Locale.US
    }

    /** What produced a piece's transcript and translation, for filtering training data by label quality. */
    fun cloudEngine(model: String) = "cloud:$model"
    const val DEVICE_ENGINE = "device:mlkit"
}

/** The two languages a project interprets between. Either side is detected on its own and translated into the other. */
enum class LangPair(val code: String, val a: String, val b: String) {
    TH_EN("th-en", Lang.TH, Lang.EN),
    TH_MY("th-my", Lang.TH, Lang.MY),
    EN_MY("en-my", Lang.EN, Lang.MY),
    MY_ONLY("my", Lang.MY, Lang.MY);

    /** One language: speech is written down and typed text is read aloud, nothing is translated. */
    val transcribeOnly get() = a == b
    val label get() = if (transcribeOnly) "${Lang.name(a)} only" else "${Lang.name(a)} ⇄ ${Lang.name(b)}"
    val short get() = if (transcribeOnly) a.uppercase() else "${a.uppercase()} ⇄ ${b.uppercase()}"
    /** Only Thai ⇄ English has on-device speech recognition and translation. */
    val worksOffline get() = this == TH_EN

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code } ?: TH_EN
    }
}
