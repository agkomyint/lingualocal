package com.example.lingualocal

import com.example.lingualocal.data.Lang
import org.junit.Assert.assertEquals
import org.junit.Test

class LangTest {
    @Test fun thaiScriptIsThai() = assertEquals("th", Lang.of("จ่ายด้วยการโอนเงินได้ไหมครับ"))
    @Test fun latinIsEnglish() = assertEquals("en", Lang.of("Can I pay by bank transfer?"))
    @Test fun mixedWithThaiIsThai() = assertEquals("th", Lang.of("ค่าเช่า 5000 baht"))
    @Test fun blankIsUnknown() = assertEquals("", Lang.of("  "))
    @Test fun directionIsTheOtherLanguage() {
        assertEquals("en", Lang.other("th"))
        assertEquals("th", Lang.other("en"))
        assertEquals("", Lang.other(""))
    }
}
