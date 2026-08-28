package com.itantra.app.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.itantra.app.core.ModelPackManager
import java.nio.FloatBuffer

/** Offline Silero VAD loaded from the installed local model pack. */
class SileroVad(context: Context) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private var h = FloatArray(2 * 1 * 64)
    private var c = FloatArray(2 * 1 * 64)
    private var consecutiveSilentChunks = 0

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_SAMPLES = 512
        const val SPEECH_THRESHOLD = 0.5f
        const val SILENCE_CHUNKS_FOR_STOP = 22
    }

    init {
        val model = ModelPackManager(context).rootDir().resolve("vad/silero_vad.onnx")
        require(model.exists()) { "Offline VAD model pack is not installed" }
        session = env.createSession(model.readBytes(), OrtSession.SessionOptions())
    }

    fun processChunk(pcmFloat: FloatArray): VadResult {
        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(pcmFloat), longArrayOf(1, pcmFloat.size.toLong()))
        val hTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(h), longArrayOf(2, 1, 64))
        val cTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(c), longArrayOf(2, 1, 64))
        val srTensor = OnnxTensor.createTensor(env, longArrayOf(SAMPLE_RATE.toLong()))
        val inputs = mapOf("input" to inputTensor, "h" to hTensor, "c" to cTensor, "sr" to srTensor)
        session.run(inputs).use { results ->
            val prob = (results[0].value as Array<FloatArray>)[0][0]
            h = (results[1].value as Array<Array<FloatArray>>).flattenToFloatArray()
            c = (results[2].value as Array<Array<FloatArray>>).flattenToFloatArray()
            val isSpeech = prob >= SPEECH_THRESHOLD
            consecutiveSilentChunks = if (isSpeech) 0 else consecutiveSilentChunks + 1
            return VadResult(isSpeech, prob, consecutiveSilentChunks == SILENCE_CHUNKS_FOR_STOP)
        }
    }

    fun reset() {
        h = FloatArray(2 * 1 * 64)
        c = FloatArray(2 * 1 * 64)
        consecutiveSilentChunks = 0
    }

    fun release() = session.close()

    private fun Array<Array<FloatArray>>.flattenToFloatArray(): FloatArray =
        this.flatMap { it.flatMap { row -> row.toList() } }.toFloatArray()
}

data class VadResult(val isSpeech: Boolean, val speechProbability: Float, val utteranceEnded: Boolean)
