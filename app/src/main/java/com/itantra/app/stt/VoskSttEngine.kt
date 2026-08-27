package com.itantra.app.stt

import android.content.Context
import com.itantra.app.core.SupportedLanguage
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import kotlin.coroutines.resume

/**
 * Wraps Vosk's offline recognizer.
 *
 * SETUP REQUIRED BEFORE THIS COMPILES/RUNS (see README "Model Setup" section):
 *  1. Download a small Vosk model per language from https://alphacephei.com/vosk/models
 *     (pick the "-small" variants — typically 40-50MB — for the RAM/flash footprint metric).
 *  2. Unzip each into app/src/main/assets/models/vosk/<lang_code>/  e.g. .../vosk/hi/
 *  3. Vosk does not ship small models for every one of the 10 target languages yet —
 *     cross-check availability first and pick your 3 demo languages accordingly
 *     (Hindi + English have the most mature Vosk community models as of writing).
 *
 * SAMPLE RATE: Vosk expects 16kHz mono PCM16 — AudioRecorder.kt is already configured
 * for this, do not change the sample rate independently in one place only.
 */
class VoskSttEngine(private val context: Context) : SttEngine {

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private val resultCallbacks = mutableListOf<(SttResult) -> Unit>()
    private var utteranceStartMs: Long = 0L

    override suspend fun loadModel(language: SupportedLanguage) {
        val assetPath = "models/vosk/${language.bcp47}"
        model = suspendCancellableCoroutine { cont ->
            StorageService.unpack(
                context, assetPath, "vosk-model-${language.bcp47}",
                { unpackedModel -> cont.resume(unpackedModel) },
                { exception ->
                    // Fail loudly rather than silently — a missing model is the #1
                    // reason this pipeline appears "broken" during rehearsal.
                    throw IllegalStateException(
                        "Vosk model for ${language.displayName} not found at " +
                        "assets/$assetPath — did you download and unzip it? " +
                        "See README Model Setup.", exception
                    )
                }
            )
        }
        recognizer = Recognizer(model, 16000.0f)
    }

    override fun acceptAudioFrame(pcm16: ShortArray, frameSize: Int) {
        if (utteranceStartMs == 0L) utteranceStartMs = System.currentTimeMillis()
        val rec = recognizer ?: return
        val bytes = ByteArray(frameSize * 2)
        for (i in 0 until frameSize) {
            val v = pcm16[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val gotFinal = rec.acceptWaveForm(bytes, bytes.size)
        val json = if (gotFinal) rec.result else rec.partialResult
        val key = if (gotFinal) "text" else "partial"
        val text = JSONObject(json).optString(key, "")
        if (text.isNotBlank()) {
            emit(SttResult(text = text, isFinal = gotFinal))
        }
    }

    override fun observePartialResults(): SttResultFlow = SttResultFlow { cb ->
        resultCallbacks.add(cb)
    }

    override suspend fun finalizeUtterance(): SttResult {
        val rec = recognizer ?: return SttResult("", true)
        val elapsed = System.currentTimeMillis() - utteranceStartMs
        val json = rec.finalResult
        val text = JSONObject(json).optString("text", "")
        utteranceStartMs = 0L
        val result = SttResult(text = text, isFinal = true, processingTimeMs = elapsed)
        emit(result)
        return result
    }

    override fun release() {
        recognizer?.close()
        model?.close()
    }

    private fun emit(result: SttResult) {
        resultCallbacks.forEach { it(result) }
    }
}
