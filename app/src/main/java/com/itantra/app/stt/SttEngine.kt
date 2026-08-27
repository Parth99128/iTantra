package com.itantra.app.stt

import com.itantra.app.core.SupportedLanguage

interface SttEngine {
    suspend fun loadModel(language: SupportedLanguage)
    fun acceptAudioFrame(pcm16: ShortArray, frameSize: Int)
    fun observePartialResults(): SttResultFlow
    suspend fun finalizeUtterance(): SttResult
    fun release()
}

data class SttResult(
    val text: String,
    val isFinal: Boolean,
    val confidence: Float = 0f,
    /** Wall-clock time spent inside the recognizer, not speech duration. */
    val processingTimeMs: Long = 0,
    /** Duration of PCM audio represented by this utterance. */
    val audioDurationMs: Long = 0,
) {
    val realTimeFactor: Double
        get() = if (audioDurationMs > 0) processingTimeMs.toDouble() / audioDurationMs else 0.0
}

fun interface SttResultFlow {
    fun collect(onResult: (SttResult) -> Unit)
}
