package com.itantra.app.tts

import android.content.Context
import com.itantra.app.core.ModelPackManager
import com.itantra.app.core.SupportedLanguage
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/** Real offline Piper/VITS inference through sherpa-onnx using an installed local model pack. */
class OnnxVitsTtsEngine(private val context: Context) : TtsEngine {
    private var tts: OfflineTts? = null
    override var outputSampleRate: Int = 22050
        private set

    override suspend fun loadModel(language: SupportedLanguage) {
        val root = ModelPackManager(context).rootDir()
        val modelDir = File(root, "tts/${language.bcp47}")
        require(File(modelDir, "model.onnx").exists()) { "Offline ${language.displayName} TTS pack is not installed" }
        require(File(modelDir, "tokens.txt").exists()) { "Offline ${language.displayName} TTS tokens are missing" }
        val dataDir = File(root, "tts/espeak-ng-data")
        require(File(dataDir, "phontab").exists()) { "Offline TTS phonemizer data is missing" }

        tts?.release()
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = File(modelDir, "model.onnx").absolutePath,
                    tokens = File(modelDir, "tokens.txt").absolutePath,
                    dataDir = dataDir.absolutePath,
                ),
                numThreads = 1,
                provider = "cpu",
                debug = false,
            )
        )
        tts = OfflineTts(config)
        outputSampleRate = tts?.sampleRate() ?: 22050
    }

    override suspend fun synthesize(text: String): TtsResult {
        val engine = tts ?: error("TTS model not loaded — install an offline model pack first")
        val start = System.currentTimeMillis()
        val audio = engine.generateWithConfig(text = text, config = GenerationConfig(speed = 1.0f, silenceScale = 0.2f))
        val elapsed = System.currentTimeMillis() - start
        val pcm16 = ShortArray(audio.samples.size) { i -> (audio.samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
        return TtsResult(pcm16, audio.sampleRate, elapsed)
    }

    override fun release() {
        tts?.release()
        tts = null
    }
}
