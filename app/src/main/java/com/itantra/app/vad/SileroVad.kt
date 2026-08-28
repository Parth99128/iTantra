package com.itantra.app.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.itantra.app.core.ModelPackManager
import java.nio.FloatBuffer

/** Offline Silero VAD using the current ONNX contract: input, state, sr. */
class SileroVad(context: Context) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private var state = FloatArray(2 * 1 * 128)
    private var contextSamples = FloatArray(CONTEXT_SAMPLES)
    private var consecutiveSilentChunks = 0

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_SAMPLES = 512
        const val CONTEXT_SAMPLES = 64
        const val SPEECH_THRESHOLD = 0.5f
        const val SILENCE_CHUNKS_FOR_STOP = 22
    }

    init {
        val model = ModelPackManager(context).rootDir().resolve("vad/silero_vad.onnx")
        require(model.exists()) { "Offline VAD model pack is not installed" }
        session = env.createSession(model.readBytes(), OrtSession.SessionOptions())
    }

    fun processChunk(pcmFloat: FloatArray): VadResult {
        require(pcmFloat.size == CHUNK_SAMPLES) {
            "Silero VAD expects $CHUNK_SAMPLES samples per chunk, got ${pcmFloat.size}"
        }

        // Current Silero ONNX expects the previous 64 samples prepended to the
        // 512-sample frame: effective input length = 576 samples.
        val input = FloatArray(CONTEXT_SAMPLES + pcmFloat.size)
        System.arraycopy(contextSamples, 0, input, 0, CONTEXT_SAMPLES)
        System.arraycopy(pcmFloat, 0, input, CONTEXT_SAMPLES, pcmFloat.size)

        val inputTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(1, input.size.toLong())
        )
        val stateTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(state),
            longArrayOf(2, 1, 128)
        )
        val srTensor = OnnxTensor.createTensor(
            env,
            longArrayOf(SAMPLE_RATE.toLong())
        )

        val inputs = mapOf(
            "input" to inputTensor,
            "state" to stateTensor,
            "sr" to srTensor
        )

        session.run(inputs).use { results ->
            val prob = extractSpeechProbability(results[0].value)
            val newState = flattenState(results[1].value)
            require(newState.size == state.size) {
                "Unexpected Silero state size: ${newState.size}, expected ${state.size}"
            }
            state = newState
            System.arraycopy(input, input.size - CONTEXT_SAMPLES, contextSamples, 0, CONTEXT_SAMPLES)

            val isSpeech = prob >= SPEECH_THRESHOLD
            consecutiveSilentChunks = if (isSpeech) 0 else consecutiveSilentChunks + 1
            return VadResult(isSpeech, prob, consecutiveSilentChunks >= SILENCE_CHUNKS_FOR_STOP)
        }
    }

    fun reset() {
        state.fill(0f)
        contextSamples.fill(0f)
        consecutiveSilentChunks = 0
    }

    fun release() = session.close()

    private fun extractSpeechProbability(value: Any?): Float {
        return when (value) {
            is FloatArray -> value.firstOrNull() ?: 0f
            is Array<*> -> extractFirstFloat(value)
            else -> error("Unexpected Silero probability output type: ${value?.javaClass?.name}")
        }
    }

    private fun extractFirstFloat(value: Array<*>): Float {
        var current: Any? = value
        while (current is Array<*>) current = current.firstOrNull()
        return current as? Float ?: error("Unexpected Silero probability value: $current")
    }

    private fun flattenState(value: Any?): FloatArray {
        return when (value) {
            is FloatArray -> value
            is Array<*> -> {
                val output = ArrayList<Float>(state.size)
                fun visit(node: Any?) {
                    when (node) {
                        is FloatArray -> node.forEach(output::add)
                        is Array<*> -> node.forEach(::visit)
                    }
                }
                visit(value)
                output.toFloatArray()
            }
            else -> error("Unexpected Silero state output type: ${value?.javaClass?.name}")
        }
    }
}

data class VadResult(val isSpeech: Boolean, val speechProbability: Float, val utteranceEnded: Boolean)
