package com.itantra.app.tts

import android.content.Context
import com.itantra.app.core.SupportedLanguage
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.FileOutputStream

/** Real offline Piper/VITS inference through sherpa-onnx.
 *
 * Model weights stay in APK assets. sherpa's Android implementation needs
 * espeak-ng-data on the filesystem, so the shared phonemizer data is copied once
 * to the app's private files directory and reused by all language packs.
 */
class OnnxVitsTtsEngine(private val context: Context) : TtsEngine {

    private var tts: OfflineTts? = null

    override var outputSampleRate: Int = 22050
        private set

    override suspend fun loadModel(language: SupportedLanguage) {
        val modelDir = "models/tts/${language.bcp47}"
        val dataDir = copySharedEspeakData()

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "$modelDir/model.onnx",
                    tokens = "$modelDir/tokens.txt",
                    dataDir = dataDir.absolutePath,
                ),
                numThreads = 1,
                provider = "cpu",
                debug = false,
            )
        )

        tts?.release()
        tts = OfflineTts(assetManager = context.assets, config = config)
        outputSampleRate = tts?.sampleRate() ?: 22050
    }

    override suspend fun synthesize(text: String): TtsResult {
        val engine = tts ?: error("TTS model not loaded — call loadModel() first")
        val start = System.currentTimeMillis()
        val audio = engine.generateWithConfig(
            text = text,
            config = GenerationConfig(speed = 1.0f, silenceScale = 0.2f),
        )
        val elapsed = System.currentTimeMillis() - start
        val pcm16 = ShortArray(audio.samples.size) { i ->
            (audio.samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        return TtsResult(
            pcm16 = pcm16,
            sampleRate = audio.sampleRate,
            processingTimeMs = elapsed,
        )
    }

    private fun copySharedEspeakData(): File {
        val target = File(context.filesDir, "espeak-ng-data")
        val marker = File(target, "phontab")
        if (marker.exists()) return target

        target.deleteRecursively()
        copyAssetTree("models/tts/espeak-ng-data", target)
        require(marker.exists()) {
            "Offline TTS phonemizer data is missing or incomplete. Rebuild with the model provisioning workflow."
        }
        return target
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
            return
        }

        destination.mkdirs()
        for (child in children) {
            copyAssetTree("$assetPath/$child", File(destination, child))
        }
    }

    override fun release() {
        tts?.release()
        tts = null
    }
}
