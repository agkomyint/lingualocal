package com.example.lingualocal

import com.example.lingualocal.audio.Fbank
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Checks the Kotlin filterbank against the Python (Kaldi-style) reference the speaker model was validated with. */
class FbankTest {
    @Test
    fun matchesPythonReference() {
        val x = FloatArray(8000) { n ->
            (0.3 * sin(2 * PI * 440 * n / 16000) + 0.2 * sin(2 * PI * 1234.5 * n / 16000 + 0.3) +
                0.05 * sin(n.toDouble() * n * 1e-5)).toFloat()
        }
        val f = Fbank.compute(x)
        assertEquals(48, f.size)
        assertEquals(-6.208714, f[0][0].toDouble(), 2e-3)
        assertEquals(-5.591959, f[0][10].toDouble(), 2e-3)
        assertEquals(-12.062954, f[5][40].toDouble(), 2e-3)
        assertEquals(-15.942385, f[20][79].toDouble(), 2e-3)
    }
}
