package com.example.lingualocal.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * Kaldi-compatible 80-bin log-mel filterbank of 16 kHz audio (25 ms frames, 10 ms hop, Povey window,
 * pre-emphasis 0.97, no dither). This is the input the speaker-embedding model was trained on.
 * Pure Kotlin, no Android dependencies, so it is unit-tested against a Python reference.
 */
object Fbank {
    const val BINS = 80
    private const val FRAME = 400
    private const val HOP = 160
    private const val NFFT = 512
    private const val RATE = 16_000.0
    private const val EPS = 1.1920929e-7 // float32 epsilon, Kaldi's log floor

    private val window = DoubleArray(FRAME) { (0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1))).pow(0.85) }
    private val cosTable = DoubleArray(NFFT / 2) { cos(-2 * PI * it / NFFT) }
    private val sinTable = DoubleArray(NFFT / 2) { sin(-2 * PI * it / NFFT) }

    /** Triangular mel filters: for each bin, the first FFT index it covers and its weights. */
    private val filterStart = IntArray(BINS)
    private val filters: Array<DoubleArray>

    init {
        fun mel(f: Double) = 1127.0 * ln(1.0 + f / 700.0)
        val lo = mel(20.0)
        val hi = mel(RATE / 2)
        val step = (hi - lo) / (BINS + 1)
        filters = Array(BINS) { b ->
            val left = lo + b * step
            val center = left + step
            val right = center + step
            val weights = DoubleArray(NFFT / 2)
            for (k in 0 until NFFT / 2) {
                val m = mel(k * RATE / NFFT)
                if (m > left && m < right) weights[k] = if (m <= center) (m - left) / (center - left) else (right - m) / (right - center)
            }
            val first = weights.indexOfFirst { it > 0 }.coerceAtLeast(0)
            val last = weights.indexOfLast { it > 0 }.coerceAtLeast(first)
            filterStart[b] = first
            weights.copyOfRange(first, last + 1)
        }
    }

    /** [samples] in -1..1. Returns one 80-value row per 10 ms frame (empty if shorter than 25 ms). */
    fun compute(samples: FloatArray): Array<FloatArray> {
        if (samples.size < FRAME) return emptyArray()
        val frames = 1 + (samples.size - FRAME) / HOP
        val re = DoubleArray(NFFT)
        val im = DoubleArray(NFFT)
        val power = DoubleArray(NFFT / 2 + 1)
        return Array(frames) { i ->
            val off = i * HOP
            var mean = 0.0
            for (k in 0 until FRAME) mean += samples[off + k]
            mean /= FRAME
            for (k in 0 until FRAME) {
                val cur = samples[off + k] - mean
                val prev = if (k > 0) samples[off + k - 1] - mean else cur
                re[k] = (cur - 0.97 * prev) * window[k]
            }
            re.fill(0.0, FRAME, NFFT)
            im.fill(0.0)
            fft(re, im)
            for (k in power.indices) power[k] = re[k] * re[k] + im[k] * im[k]
            FloatArray(BINS) { b ->
                val w = filters[b]
                val start = filterStart[b]
                var e = 0.0
                for (j in w.indices) e += w[j] * power[start + j]
                ln(maxOf(e, EPS)).toFloat()
            }
        }
    }

    /** In-place iterative radix-2 FFT of size [NFFT]. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        var j = 0
        for (i in 1 until NFFT) {
            var bit = NFFT shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= NFFT) {
            val half = len / 2
            val stride = NFFT / len
            for (start in 0 until NFFT step len) {
                for (k in 0 until half) {
                    val c = cosTable[k * stride]
                    val s = sinTable[k * stride]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * c - im[b] * s
                    val ti = re[b] * s + im[b] * c
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
            }
            len *= 2
        }
    }
}
