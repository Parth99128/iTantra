package com.itantra.app.core

/** Master toggle required by the PS: walkie-talkie mode vs behaving like a normal phone. */
enum class OperatingMode {
    WALKIE_TALKIE,
    NORMAL_PHONE
}

/** Per-device role in the push-to-talk loop. A phone can switch roles instantly;
 *  it is not hardwired to one role. */
enum class TalkState {
    IDLE,               // not transmitting, listening for incoming TTS playback
    LISTENING_FOR_SPEECH, // push-to-talk held down, VAD+STT pipeline active
    TRANSMITTING,       // finalized text being sent over Bluetooth
    RECEIVING,          // incoming text received, TTS synthesis in progress
    PLAYING_ALERT       // TTS audio currently playing back (non-interruptible)
}

enum class BtConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED
}

enum class SupportedLanguage(val displayName: String, val bcp47: String) {
    HINDI("Hindi", "hi"),
    ENGLISH("English", "en"),
    MARATHI("Marathi", "mr"),
    GUJARATI("Gujarati", "gu"),
    BENGALI("Bengali", "bn"),
    KANNADA("Kannada", "kn"),
    MALAYALAM("Malayalam", "ml"),
    TAMIL("Tamil", "ta"),
    TELUGU("Telugu", "te"),
    ODIA("Odia", "or")
}

/** Single immutable snapshot the UI observes. */
data class UiState(
    val mode: OperatingMode = OperatingMode.WALKIE_TALKIE,
    val talkState: TalkState = TalkState.IDLE,
    val btState: BtConnectionState = BtConnectionState.DISCONNECTED,
    val language: SupportedLanguage = SupportedLanguage.HINDI,
    val partialTranscript: String = "",
    val lastFinalTranscript: String = "",
    val lastReceivedText: String = "",
    val lastSttLatencyMs: Long = 0,
    val lastTtsLatencyMs: Long = 0,
    val lastEndToEndMs: Long = 0,
    val errorMessage: String? = null
)
