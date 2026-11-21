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
import kotlin.math.sqrt

/**
 * Tells speakers apart on-device (ONNX Runtime, 3D-Speaker CAM++ voice-fingerprint model, Apache-2.0).
 * Each speech piece becomes a 192-number voice fingerprint; it is matched against the voices heard so
 * far in this session, so "Speaker 2" stays "Speaker 2" across the whole conversation. No audio leaves
 * the device for this.
 */
class SpeakerId(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        context.assets.open(MODEL).use { it.readBytes() },
        OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) },
    )
    private val inputName = session.inputNames.first()
    private val mutex = Mutex()
    private val voices = mutableListOf<Voice>()

    private class Voice(val number: Int, val sum: FloatArray, var count: Int)

    /**
     * Speaker number (1, 2, …) for one piece of 16 kHz PCM16 that has [voicedMs] of clear voice,
     * or null when the piece is too short to be sure.
     */
    suspend fun identify(pcm: ByteArray, voicedMs: Long): Int? = mutex.withLock {
        if (voicedMs < MIN_VOICED_MS) return null
        val emb = embed(pcm) ?: return null
        var best: Voice? = null
        var bestSim = -1f
        for (v in voices) {
            val s = cosine(emb, v.sum)
            if (s > bestSim) { bestSim = s; best = v }
        }
        val result = when {
            best != null && bestSim >= MATCH -> {
                // Only learn from confident matches, so a mixed piece doesn't blur a voice.
                if (bestSim >= LEARN && best.count < MAX_LEARN) {
                    for (i in emb.indices) best.sum[i] += emb[i]
                    best.count++
                }
                best.number
            }
            voicedMs >= NEW_VOICE_MS && voices.size < MAX_VOICES ->
                Voice(voices.size + 1, emb.copyOf(), 1).also { voices += it }.number
            else -> null
        }
        Log.d(TAG, "voice %.1f s · best match %s %.2f -> %s".format(
            voicedMs / 1000f, best?.number?.let { "S$it" } ?: "-", bestSim, result?.let { "Speaker $it" } ?: "unsure"))
        result
    }

    private fun embed(pcm: ByteArray): FloatArray? {
        val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val n = minOf(shorts.remaining(), MAX_SAMPLES)
        val samples = FloatArray(n) { shorts.get(it) / 32768f }
        val feats = Fbank.compute(samples)
        if (feats.size < MIN_FRAMES) return null
        // Mean normalisation per bin (also cancels any volume boost applied to the piece).
        val mean = FloatArray(Fbank.BINS)
        for (row in feats) for (b in 0 until Fbank.BINS) mean[b] += row[b]
        for (b in 0 until Fbank.BINS) mean[b] /= feats.size
        val flat = FloatArray(feats.size * Fbank.BINS)
        feats.forEachIndexed { t, row -> for (b in 0 until Fbank.BINS) flat[t * Fbank.BINS + b] = row[b] - mean[b] }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, feats.size.toLong(), Fbank.BINS.toLong())).use { x ->
            session.run(mapOf(inputName to x)).use { out ->
                val buf = (out[0] as OnnxTensor).floatBuffer
                return FloatArray(buf.remaining()).also { buf.get(it) }
            }
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return (dot / (sqrt(na) * sqrt(nb) + 1e-9)).toFloat()
    }

    override fun close() = session.close()

    private companion object {
        const val TAG = "SpeakerId"
        const val MODEL = "speaker_campplus.onnx"
        const val MIN_VOICED_MS = 700L     // shorter pieces are too unreliable to label
        const val NEW_VOICE_MS = 1_200L    // a new speaker needs a slightly longer piece
        const val MATCH = 0.55f            // same voice above this (same person ~0.8, different <0.5 in tests)
        const val LEARN = 0.62f
        const val MAX_LEARN = 40
        const val MAX_VOICES = 8
        const val MIN_FRAMES = 50          // 0.5 s of features
        const val MAX_SAMPLES = 16_000 * 15
    }
}
