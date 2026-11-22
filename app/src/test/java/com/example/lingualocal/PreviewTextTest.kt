package com.example.lingualocal

import com.example.lingualocal.ai.PreviewText
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewTextTest {
    private fun clean(raw: String, spoken: String = "", above: String = "") =
        PreviewText.clean(raw, listOf(spoken), listOf(above)).trim()

    @Test fun translationHeardBackIsHiddenAsItArrives() {
        val said = "Where have you been today?"
        for (end in 2..said.length) assertEquals("", clean(said.substring(0, end), said))
    }

    @Test fun misheardTranslationIsHidden() =
        assertEquals("", clean("Please send the signed contact tomorrow morning", "Please send the signed contract tomorrow morning"))

    @Test fun thaiAndBurmeseAreHidden() {
        assertEquals("", clean("วันนี้อากาศร้อน", "วันนี้อากาศร้อนมากครับ"))
        assertEquals("", clean("ဒီနေ့ရာသီဥတု", "ဒီနေ့ရာသီဥတုအရမ်းပူတယ်"))
    }

    @Test fun newWordsAfterItStay() =
        assertEquals("Okay, two tickets", clean("The train leaves at seven. Okay, two tickets", "The train leaves at seven."))

    @Test fun wordsAroundItStay() =
        assertEquals(
            "I was wondering if parking costs extra",
            clean("I was wondering breakfast is served downstairs if parking costs extra", "Breakfast is served downstairs"),
        )

    @Test fun lateWordsOfThePieceAboveAreHidden() =
        assertEquals("Can we visit", clean(" the river. Can we visit", above = "Our office moved beside the river"))

    @Test fun ordinarySpeechIsUntouched() =
        assertEquals("I went to the restaurant", clean("I went to the restaurant", "Could you open a window", "สวัสดี"))

    @Test fun aSharedStartStays() =
        assertEquals("Tomorrow everybody belongs", clean("Tomorrow everybody belongs", "Tomorrow everybody becomes responsible"))
}
