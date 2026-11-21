package com.example.lingualocal.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/**
 * On-device spoken language ID: Thai or English? Uses OpenAI Whisper-tiny (MIT licence, int8 ONNX
 * export by sherpa-onnx), trimmed to the one decoder step and the two language tokens it needs
 * (~23 MB instead of ~100 MB). About 20–70 ms per piece, no internet.
 */
class LanguageId(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
    private val encoder = env.createSession(context.assets.open("lid_encoder.onnx").use { it.readBytes() }, opts)
    private val decoder = env.createSession(context.assets.open("lid_decoder.onnx").use { it.readBytes() }, opts)
    private val mutex = Mutex()

    /** Probability (0..1) that this 16 kHz PCM16 piece is Thai rather than English. */
    suspend fun thaiProbability(pcm: ByteArray): Float = mutex.withLock {
        val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val n = minOf(shorts.remaining(), MAX_SAMPLES)
        val x = FloatArray(n) { shorts.get(it) / 32768f }
        val mel = WhisperMel.compute(x) // [80][frames]
        val frames = mel[0].size
        val pad = 50
        var t = frames + pad
        if (t % 2 == 1) t--
        val floor = mel.minOf { row -> row.min() }
        val flat = FloatArray(80 * t)
        for (b in 0 until 80) for (i in 0 until t) flat[b * t + i] = if (i < frames) mel[b][i] else floor

        val started = System.currentTimeMillis()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, 80, t.toLong())).use { melT ->
            encoder.run(mapOf("mel" to melT)).use { enc ->
                val cache = FloatBuffer.allocate(4 * 448 * 384)
                val shape = longArrayOf(4, 1, 448, 384)
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(0)), longArrayOf(1, 1)).use { tokens ->
                OnnxTensor.createTensor(env, cache, shape).use { k ->
                OnnxTensor.createTensor(env, cache, shape).use { v ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(0)), longArrayOf(1)).use { offset ->
                    decoder.run(mapOf(
                        "tokens" to tokens,
                        "in_n_layer_self_k_cache" to k,
                        "in_n_layer_self_v_cache" to v,
                        "n_layer_cross_k" to enc[0] as OnnxTensor,
                        "n_layer_cross_v" to enc[1] as OnnxTensor,
                        "offset" to offset,
                    ), setOf("logits")).use { out ->
                        val logits = (out[0] as OnnxTensor).floatBuffer // [SOT, en, th]
                        val en = logits.get(1)
                        val th = logits.get(2)
                        val p = (1.0 / (1.0 + exp((en - th).toDouble()))).toFloat()
                        Log.d(TAG, "%.1f s of audio -> P(thai)=%.3f in %d ms".format(n / 16000f, p, System.currentTimeMillis() - started))
                        return p
                    }
                }}}}
            }
        }
    }

    override fun close() {
        encoder.close()
        decoder.close()
    }

    companion object {
        private const val TAG = "LanguageId"
        private const val MAX_SAMPLES = 16000 * 10 // the first 10 s is plenty to tell the language
    }
}

/** Whisper's log-mel spectrogram (80 bins, 25 ms Hann window, 10 ms hop, Slaney mel scale). */
object WhisperMel {
    private const val N_FFT = 400
    private const val HOP = 160
    private const val BINS = 80
    private const val FREQS = N_FFT / 2 + 1

    private val window = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2 * PI * it / N_FFT)).toFloat() }
    private val cosT = FloatArray(N_FFT) { cos(2 * PI * it / N_FFT).toFloat() }
    private val sinT = FloatArray(N_FFT) { sin(2 * PI * it / N_FFT).toFloat() }
    private val filters: Array<FloatArray> = buildFilters()

    private fun hzToMel(f: Double) = if (f < 1000) f / (200.0 / 3) else 15 + ln(f / 1000) / (ln(6.4) / 27)
    private fun melToHz(m: Double) = if (m < 15) m * 200.0 / 3 else 1000 * exp((ln(6.4) / 27) * (m - 15))

    private fun buildFilters(): Array<FloatArray> {
        val fft = DoubleArray(FREQS) { it * 8000.0 / (FREQS - 1) }
        val lo = hzToMel(0.0); val hi = hzToMel(8000.0)
        val pts = DoubleArray(BINS + 2) { melToHz(lo + (hi - lo) * it / (BINS + 1)) }
        return Array(BINS) { i ->
            val norm = 2.0 / (pts[i + 2] - pts[i])
            FloatArray(FREQS) { k ->
                val down = (fft[k] - pts[i]) / (pts[i + 1] - pts[i])
                val up = (pts[i + 2] - fft[k]) / (pts[i + 2] - pts[i + 1])
                (max(0.0, minOf(down, up)) * norm).toFloat()
            }
        }
    }

    /** Returns [80][frames] normalised log-mel features. */
    fun compute(samples: FloatArray): Array<FloatArray> {
        val p = N_FFT / 2
        val n = samples.size
        // Reflect padding, as torch.stft(center=True).
        val x = FloatArray(n + 2 * p) { i ->
            val j = i - p
            when {
                n == 1 -> samples[0]
                j < 0 -> samples[-j]
                j >= n -> samples[2 * n - 2 - j]
                else -> samples[j]
            }
        }
        val frames = max(0, 1 + (x.size - N_FFT) / HOP - 1) // Whisper drops the last frame
        val out = Array(BINS) { FloatArray(frames) }
        val frame = FloatArray(N_FFT)
        val power = FloatArray(FREQS)
        var top = -Float.MAX_VALUE
        for (f in 0 until frames) {
            val off = f * HOP
            for (i in 0 until N_FFT) frame[i] = x[off + i] * window[i]
            for (k in 0 until FREQS) {
                var re = 0f; var im = 0f; var idx = 0
                for (i in 0 until N_FFT) {
                    re += frame[i] * cosT[idx]; im -= frame[i] * sinT[idx]
                    idx += k; if (idx >= N_FFT) idx -= N_FFT
                }
                power[k] = re * re + im * im
            }
            for (b in 0 until BINS) {
                val w = filters[b]
                var s = 0f
                for (k in 0 until FREQS) if (w[k] != 0f) s += w[k] * power[k]
                val v = log10(max(s, 1e-10f))
                out[b][f] = v
                if (v > top) top = v
            }
        }
        for (b in 0 until BINS) for (f in 0 until frames) out[b][f] = (max(out[b][f], top - 8f) + 4f) / 4f
        return out
    }
}
