package com.itantra.app.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.itantra.app.core.ModelPackManager
import java.nio.FloatBuffer

/**
 * Offline Silero VAD with compatibility for the two common exported contracts:
 *  - x, h, c  (the model currently bundled by iTantra)
 *  - input, state, sr (newer Silero export)
 */
class SileroVad(context: Context) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val usesXhc: Boolean
    private var h = FloatArray(HC_SIZE)
    private var c = FloatArray(HC_SIZE)
    private var state = FloatArray(STATE_SIZE)
    private var contextSamples = FloatArray(CONTEXT_SAMPLES)
    private var consecutiveSilentChunks = 0

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_SAMPLES = 512
        private const val CONTEXT_SAMPLES = 64
        private const val HC_SIZE = 2 * 1 * 64
        private const val STATE_SIZE = 2 * 1 * 128
        const val SPEECH_THRESHOLD = 0.5f
        const val SILENCE_CHUNKS_FOR_STOP = 22
    }

    init {
        val model = ModelPackManager(context).rootDir().resolve("vad/silero_vad.onnx")
        require(model.exists()) { "Offline VAD model pack is not installed" }
        session = env.createSession(model.readBytes(), OrtSession.SessionOptions())
        val names = session.inputNames
        usesXhc = names.contains("x") && names.contains("h") && names.contains("c")
        require(usesXhc || (names.contains("input") && names.contains("state") && names.contains("sr"))) {
            "Unsupported Silero VAD input contract: ${names.joinToString() }"
        }
    }

    fun processChunk(pcmFloat: FloatArray): VadResult {
        require(pcmFloat.size == CHUNK_SAMPLES) {
            "Silero VAD expects $CHUNK_SAMPLES samples per chunk, got ${pcmFloat.size}"
        }

        return if (usesXhc) processXhc(pcmFloat) else processStateSr(pcmFloat)
    }

    private fun processXhc(pcmFloat: FloatArray): VadResult {
        val xTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(pcmFloat), longArrayOf(1, CHUNK_SAMPLES.toLong()))
        val hTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(h), longArrayOf(2, 1, 64))
        val cTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(c), longArrayOf(2, 1, 64))

        session.run(mapOf("x" to xTensor, "h" to hTensor, "c" to cTensor)).use { results ->
            val prob = extractSpeechProbability(results[0].value)
            h = flattenFloatArray(results[1].value, HC_SIZE)
            c = flattenFloatArray(results[2].value, HC_SIZE)
            return finishChunk(prob)
        }
    }

    private fun processStateSr(pcmFloat: FloatArray): VadResult {
        val input = FloatArray(CONTEXT_SAMPLES + pcmFloat.size)
        System.arraycopy(contextSamples, 0, input, 0, CONTEXT_SAMPLES)
        System.arraycopy(pcmFloat, 0, input, CONTEXT_SAMPLES, pcmFloat.size)

        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong()))
        val stateTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128))
        val srTensor = OnnxTensor.createTensor(env, longArrayOf(SAMPLE_RATE.toLong()))

        session.run(mapOf("input" to inputTensor, "state" to stateTensor, "sr" to srTensor)).use { results ->
            val prob = extractSpeechProbability(results[0].value)
            state = flattenFloatArray(results[1].value, STATE_SIZE)
            System.arraycopy(input, input.size - CONTEXT_SAMPLES, contextSamples, 0, CONTEXT_SAMPLES)
            return finishChunk(prob)
        }
    }

    private fun finishChunk(prob: Float): VadResult {
        val isSpeech = prob >= SPEECH_THRESHOLD
        consecutiveSilentChunks = if (isSpeech) 0 else consecutiveSilentChunks + 1
        return VadResult(isSpeech, prob, consecutiveSilentChunks >= SILENCE_CHUNKS_FOR_STOP)
    }

    fun reset() {
        h.fill(0f)
        c.fill(0f)
        state.fill(0f)
        contextSamples.fill(0f)
        consecutiveSilentChunks = 0
    }

    fun release() = session.close()

    private fun extractSpeechProbability(value: Any?): Float = when (value) {
        is FloatArray -> value.firstOrNull() ?: 0f
        is Array<*> -> {
            var current: Any? = value
            while (current is Array<*>) current = current.firstOrNull()
            current as? Float ?: error("Unexpected Silero probability value: $current")
        }
        else -> error("Unexpected Silero probability output type: ${value?.javaClass?.name}")
    }

    private fun flattenFloatArray(value: Any?, expectedSize: Int): FloatArray {
        if (value is FloatArray) {
            require(value.size == expectedSize) { "Unexpected Silero state size: ${value.size}, expected $expectedSize" }
            return value
        }
        val output = ArrayList<Float>(expectedSize)
        fun visit(node: Any?) {
            when (node) {
                is FloatArray -> node.forEach(output::add)
                is Array<*> -> node.forEach(::visit)
            }
        }
        visit(value)
        require(output.size == expectedSize) { "Unexpected Silero state size: ${output.size}, expected $expectedSize" }
        return output.toFloatArray()
    }
}

data class VadResult(val isSpeech: Boolean, val speechProbability: Float, val utteranceEnded: Boolean)
