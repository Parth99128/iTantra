package com.itantra.app.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

/**
 * Detects speech start/end so we know when to (a) start feeding audio to STT and
 * (b) treat a pause/stop as "finalize the sentence and transmit" per the PS spec.
 *
 * MODEL SETUP: Download silero_vad.onnx from the official Silero VAD repo and place
 * it at app/src/main/assets/models/vad/silero_vad.onnx (~1-2MB, cheap to bundle).
 *
 * This runs continuously while the push-to-talk button is held, on small chunks
 * (recommended 512 samples @ 16kHz ≈ 32ms) — it is intentionally tiny so it barely
 * touches your "idle listening CPU usage" efficiency metric.
 */
class SileroVad(context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    // Silero VAD is stateful (LSTM-based) — h/c state carried between calls.
    private var h = FloatArray(2 * 1 * 64)
    private var c = FloatArray(2 * 1 * 64)

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_SAMPLES = 512
        const val SPEECH_THRESHOLD = 0.5f
        // Consecutive silent chunks (~32ms each) before we treat it as end-of-utterance.
        // ~700ms of silence — tune this: too short cuts words, too long adds latency.
        const val SILENCE_CHUNKS_FOR_STOP = 22
    }

    private var consecutiveSilentChunks = 0

    init {
        val modelBytes = context.assets.open("models/vad/silero_vad.onnx").readBytes()
        session = env.createSession(modelBytes, OrtSession.SessionOptions())
    }

    /** Returns true exactly once, on the frame where sustained silence is confirmed
     *  (i.e. "the speaker has stopped talking, finalize the sentence now"). */
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
            val utteranceEnded = consecutiveSilentChunks == SILENCE_CHUNKS_FOR_STOP

            return VadResult(isSpeech = isSpeech, speechProbability = prob, utteranceEnded = utteranceEnded)
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

data class VadResult(
    val isSpeech: Boolean,
    val speechProbability: Float,
    val utteranceEnded: Boolean
)
