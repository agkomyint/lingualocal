package com.example.lingualocal.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

/**
 * Silero VAD v5 running on-device with ONNX Runtime.
 * Feed 512-sample frames of 16 kHz mono audio (32 ms) and get the probability that someone is speaking.
 * Not thread-safe: call from a single audio thread.
 */
class SileroVad(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val state = FloatArray(STATE_SIZE)
    private val contextBuf = FloatArray(CONTEXT)
    private val input = FloatArray(CONTEXT + FRAME)
    private val sampleRate: OnnxTensor = OnnxTensor.createTensor(env, 16_000L)

    init {
        val bytes = context.assets.open("silero_vad.onnx").use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
        }
        session = env.createSession(bytes, options)
    }

    fun probability(frame: FloatArray): Float {
        require(frame.size == FRAME) { "Silero VAD expects $FRAME samples, got ${frame.size}" }
        System.arraycopy(contextBuf, 0, input, 0, CONTEXT)
        System.arraycopy(frame, 0, input, CONTEXT, FRAME)

        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, (CONTEXT + FRAME).toLong())).use { x ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { s ->
                session.run(mapOf("input" to x, "state" to s, "sr" to sampleRate)).use { out ->
                    val prob = (out[0] as OnnxTensor).floatBuffer.get(0)
                    (out[1] as OnnxTensor).floatBuffer.get(state)
                    System.arraycopy(frame, FRAME - CONTEXT, contextBuf, 0, CONTEXT)
                    return prob
                }
            }
        }
    }

    override fun close() {
        sampleRate.close()
        session.close()
    }

    companion object {
        const val FRAME = 512
        private const val CONTEXT = 64
        private const val STATE_SIZE = 2 * 1 * 128
    }
}
