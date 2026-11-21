package com.example.lingualocal

import com.example.lingualocal.audio.Speaker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoTest {
    @Test fun sameThaiTextIsEcho() = assertTrue(Speaker.looksLikeEcho("ไม่เป็นไรครับ", "ไม่เป็นไรครับ"))
    @Test fun slightlyMisheardEnglishIsEcho() = assertTrue(Speaker.looksLikeEcho("where have you been", "Where have you been?"))
    @Test fun partOfSpokenTextIsEcho() = assertTrue(Speaker.looksLikeEcho("ไปไหนมา", "คุณไปไหนมาครับ"))
    @Test fun differentSentenceIsNotEcho() = assertFalse(Speaker.looksLikeEcho("How much is this?", "Where have you been?"))
    @Test fun differentThaiIsNotEcho() = assertFalse(Speaker.looksLikeEcho("ราคาเท่าไหร่", "ไม่เป็นไรครับ"))
}
