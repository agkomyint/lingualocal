package com.example.lingualocal

import com.example.lingualocal.audio.VoiceChoice
import com.example.lingualocal.audio.VoiceEngine
import com.example.lingualocal.audio.VoiceRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRulesTest {
    private val gemini = VoiceChoice(VoiceEngine.GEMINI, since = 1_000)
    private val device = VoiceChoice(VoiceEngine.DEVICE, since = 1_000)

    @Test fun defaults() {
        assertEquals(VoiceEngine.DEVICE, VoiceRules.DEFAULTS["en"]?.engine)
        assertEquals(VoiceEngine.DEVICE, VoiceRules.DEFAULTS["th"]?.engine)
        assertEquals(VoiceEngine.GEMINI, VoiceRules.DEFAULTS["my"]?.engine)
    }

    @Test fun theSettingDecides() {
        assertTrue(VoiceRules.wantsGemini(false, gemini))
        assertFalse(VoiceRules.wantsGemini(false, device))
    }

    @Test fun oneLanguageIsAlwaysGemini() {
        assertTrue(VoiceRules.wantsGemini(true, device))
        assertTrue(VoiceRules.autoGemini(true, device, time = 1, untranslatedSpeech = true))
    }

    @Test fun switchingDoesNotPayForOlderPieces() {
        assertFalse(VoiceRules.autoGemini(false, gemini, time = 999, untranslatedSpeech = false))
        assertTrue(VoiceRules.autoGemini(false, gemini, time = 1_000, untranslatedSpeech = false))
    }

    @Test fun untranslatedSpeechIsNotPaidFor() =
        assertFalse(VoiceRules.autoGemini(false, gemini, time = 2_000, untranslatedSpeech = true))

    @Test fun deviceIsNotPaidFor() =
        assertFalse(VoiceRules.autoGemini(false, device, time = 2_000, untranslatedSpeech = false))

    @Test fun pickingAgainKeepsTheTime() {
        val picked = VoiceRules.pick(VoiceRules.DEFAULTS, "en", VoiceEngine.GEMINI, 5)
        assertEquals(5L, VoiceRules.pick(picked, "en", VoiceEngine.GEMINI, 9)["en"]?.since)
        assertEquals(VoiceEngine.DEVICE, picked["th"]?.engine)
    }

    @Test fun olderSavedVoices() {
        assertEquals(VoiceEngine.GEMINI, VoiceRules.madeBy("", "my", false))
        assertEquals(VoiceEngine.DEVICE, VoiceRules.madeBy("", "th", false))
        assertEquals(VoiceEngine.GEMINI, VoiceRules.madeBy("", "en", true))
        assertEquals(VoiceEngine.GEMINI, VoiceRules.madeBy("gemini", "en", false))
        assertEquals(VoiceEngine.DEVICE, VoiceRules.madeBy("device", "my", false))
    }

    @Test fun playAll() {
        assertTrue(VoiceRules.playSaved(wants = true, savedGemini = true, makeNow = false))
        assertFalse(VoiceRules.playSaved(wants = false, savedGemini = true, makeNow = false))
        assertTrue(VoiceRules.playSaved(wants = true, savedGemini = false, makeNow = false))
        assertFalse(VoiceRules.playSaved(wants = true, savedGemini = false, makeNow = true))
    }
}
