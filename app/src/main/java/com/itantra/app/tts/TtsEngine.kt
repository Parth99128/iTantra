package com.itantra.app.tts

import com.itantra.app.core.SupportedLanguage

interface TtsEngine {
    suspend fun loadModel(language: SupportedLanguage)

    /** Synthesize text -> raw 16-bit PCM audio at [outputSampleRate]. Returns the
     *  audio plus how long synthesis took, so you can report RTF (processing_time / audio_duration). */
    suspend fun synthesize(text: String): TtsResult

    val outputSampleRate: Int
    fun release()
}

data class TtsResult(
    val pcm16: ShortArray,
    val sampleRate: Int,
    val processingTimeMs: Long
) {
    val audioDurationMs: Long get() = (pcm16.size * 1000L) / sampleRate
    /** Real-Time Factor — one of the graded metrics. RTF < 1.0 = faster than real-time. */
    val realTimeFactor: Double get() = processingTimeMs.toDouble() / audioDurationMs.coerceAtLeast(1)
}
