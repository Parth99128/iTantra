package com.itantra.app.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.itantra.app.core.SupportedLanguage
import org.json.JSONObject
import java.nio.LongBuffer

/**
 * IMPORTANT ARCHITECTURE NOTE (read before wiring models in):
 *
 * We deliberately do NOT use Piper's default pipeline here. Piper's standard voices
 * depend on espeak-ng for text->phoneme conversion, and espeak-ng's phoneme coverage
 * for several target languages (Odia, several Marathi/Kannada dialect variants) is
 * inconsistent, plus bundling espeak-ng's native library adds real complexity on
 * Android for the payoff.
 *
 * Instead, this engine targets CHARACTER-LEVEL / native-script VITS models — the
 * style AI4Bharat's Indic-TTS / Indic-Parler models are trained with — where the
 * tokenizer maps native-script characters (or a small BPE vocab) directly to IDs,
 * with NO external phonemizer dependency. This is the more reliably buildable path
 * for Indian languages in a hackathon timeframe.
 *
 * MODEL SETUP PER LANGUAGE:
 *  1. Export an AI4Bharat Indic-TTS (or Coqui VITS) checkpoint to ONNX
 *     (single-file end-to-end VITS graph: text_ids -> waveform).
 *  2. Place at app/src/main/assets/models/tts/<lang_code>/model.onnx
 *  3. Place the matching char->id vocabulary as a JSON map at
 *     app/src/main/assets/models/tts/<lang_code>/vocab.json  e.g. {"अ": 12, "आ": 13, ...}
 *  4. Quantize to int8 with onnxruntime's quantization tool before shipping —
 *     this is what your "efficiency" score is measured on.
 */
class OnnxVitsTtsEngine(private val context: Context) : TtsEngine {

    private val env = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var vocab: Map<String, Int> = emptyMap()
    override var outputSampleRate: Int = 22050
        private set

    override suspend fun loadModel(language: SupportedLanguage) {
        val base = "models/tts/${language.bcp47}"
        val modelBytes = context.assets.open("$base/model.onnx").readBytes()
        session = env.createSession(modelBytes, OrtSession.SessionOptions())

        val vocabJson = context.assets.open("$base/vocab.json").bufferedReader().readText()
        val obj = JSONObject(vocabJson)
        vocab = obj.keys().asSequence().associateWith { obj.getInt(it) }

        // Some exports embed sample rate in a config.json alongside the model; default
        // 22050 is VITS's common training rate — verify against your specific export.
    }

    override suspend fun synthesize(text: String): TtsResult {
        val s = session ?: error("TTS model not loaded — call loadModel() first")
        val start = System.currentTimeMillis()

        val ids = tokenize(text)
        val inputTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))
        val lengthTensor = OnnxTensor.createTensor(env, longArrayOf(ids.size.toLong()))

        val inputs = mapOf("input_ids" to inputTensor, "input_lengths" to lengthTensor)
        s.run(inputs).use { results ->
            @Suppress("UNCHECKED_CAST")
            val waveform = (results[0].value as Array<FloatArray>)[0]
            val pcm16 = ShortArray(waveform.size) { i ->
                (waveform[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }
            val elapsed = System.currentTimeMillis() - start
            return TtsResult(pcm16 = pcm16, sampleRate = outputSampleRate, processingTimeMs = elapsed)
        }
    }

    /** Character-level tokenization — unknown characters map to a reserved <unk> id (0).
     *  Swap this for a BPE tokenizer if your specific exported model was trained with one. */
    private fun tokenize(text: String): LongArray =
        text.map { (vocab[it.toString()] ?: 0).toLong() }.toLongArray()

    override fun release() {
        session?.close()
    }
}
