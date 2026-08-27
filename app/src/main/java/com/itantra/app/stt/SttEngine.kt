package com.itantra.app.stt

import com.itantra.app.core.SupportedLanguage

/**
 * Contract every STT backend must satisfy. Keeping this abstract means you can
 * ship with Vosk for the demo and swap in AI4Bharat/Conformer models later
 * without touching the ViewModel or UI.
 */
interface SttEngine {

    /** Load the model for a given language. Call on a background thread — this
     *  reads files from assets/ and can take a few hundred ms to a few seconds. */
    suspend fun loadModel(language: SupportedLanguage)

    /** Feed raw 16kHz mono PCM16 audio frames as they arrive from the mic. */
    fun acceptAudioFrame(pcm16: ShortArray, frameSize: Int)

    /** Partial (in-progress, unstable) transcript — used for low-latency UI feedback
     *  and for streaming decoding so perceived latency is lower than "wait for full sentence." */
    fun observePartialResults(): SttResultFlow

    /** Called when VAD detects a pause/stop — forces the engine to finalize
     *  whatever sentence it has buffered and emit a final result. */
    suspend fun finalizeUtterance(): SttResult

    fun release()
}

data class SttResult(
    val text: String,
    val isFinal: Boolean,
    val confidence: Float = 0f,
    val processingTimeMs: Long = 0
)

/** Minimal callback-flow abstraction so we don't force a specific Flow/LiveData choice
 *  on whoever wires this into the ViewModel. */
fun interface SttResultFlow {
    fun collect(onResult: (SttResult) -> Unit)
}
