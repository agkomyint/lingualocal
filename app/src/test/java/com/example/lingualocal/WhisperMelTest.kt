package com.example.lingualocal

import com.example.lingualocal.audio.WhisperMel
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Checks the Kotlin Whisper log-mel against the Python reference used to validate language ID. */
class WhisperMelTest {
    @Test
    fun matchesPythonReference() {
        val x = FloatArray(8000) { n ->
            (0.3 * sin(2 * PI * 440 * n / 16000) + 0.2 * sin(2 * PI * 1234.5 * n / 16000 + 0.3) +
                0.05 * sin(n.toDouble() * n * 1e-5)).toFloat()
        }
        val m = WhisperMel.compute(x)
        assertEquals(80, m.size)
        assertEquals(50, m[0].size)
        assertEquals(0.9133933, m[0][0].toDouble(), 2e-3)
        assertEquals(1.2378124, m[10][5].toDouble(), 2e-3)
        assertEquals(-0.66956085, m[40][20].toDouble(), 2e-3)
        assertEquals(-0.6173709, m[79][49].toDouble(), 2e-3)
    }
}
