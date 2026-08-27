package com.itantra.app.stt

import android.content.Context
import com.itantra.app.core.SupportedLanguage
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import kotlin.coroutines.resume

/** Real streaming offline Vosk STT. Models are bundled in assets/models/vosk/<lang>. */
class VoskSttEngine(private val context: Context) : SttEngine {
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private val resultCallbacks = mutableListOf<(SttResult) -> Unit>()
    private var audioSamples: Long = 0
    private var inferenceTimeMs: Long = 0

    override suspend fun loadModel(language: SupportedLanguage) {
        val assetPath = "models/vosk/${language.bcp47}"
        model = suspendCancellableCoroutine { cont ->
            StorageService.unpack(
                context,
                assetPath,
                "vosk-model-${language.bcp47}",
                { unpacked -> cont.resume(unpacked) },
                { error ->
                    throw IllegalStateException(
                        "Offline Vosk model missing at assets/$assetPath. " +
                            "Run tools/fetch_models.py before building.", error
                    )
                },
            )
        }
        recognizer = Recognizer(model, 16000.0f)
        audioSamples = 0
        inferenceTimeMs = 0
    }

    override fun acceptAudioFrame(pcm16: ShortArray, frameSize: Int) {
        val rec = recognizer ?: return
        audioSamples += frameSize
        val bytes = ByteArray(frameSize * 2)
        for (i in 0 until frameSize) {
            val v = pcm16[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val start = System.nanoTime()
        val gotFinal = rec.acceptWaveForm(bytes, bytes.size)
        inferenceTimeMs += (System.nanoTime() - start) / 1_000_000
        val json = if (gotFinal) rec.result else rec.partialResult
        val key = if (gotFinal) "text" else "partial"
        val text = JSONObject(json).optString(key, "")
        if (text.isNotBlank()) emit(SttResult(text, gotFinal))
    }

    override fun observePartialResults(): SttResultFlow = SttResultFlow { cb ->
        resultCallbacks.add(cb)
    }

    override suspend fun finalizeUtterance(): SttResult {
        val rec = recognizer ?: return SttResult("", true)
        val start = System.nanoTime()
        val text = JSONObject(rec.finalResult).optString("text", "")
        inferenceTimeMs += (System.nanoTime() - start) / 1_000_000
        val durationMs = audioSamples * 1000 / 16000
        val result = SttResult(
            text = text,
            isFinal = true,
            processingTimeMs = inferenceTimeMs,
            audioDurationMs = durationMs,
        )
        audioSamples = 0
        inferenceTimeMs = 0
        emit(result)
        return result
    }

    override fun release() {
        recognizer?.close()
        model?.close()
        recognizer = null
        model = null
    }

    private fun emit(result: SttResult) {
        resultCallbacks.forEach { it(result) }
    }
}
