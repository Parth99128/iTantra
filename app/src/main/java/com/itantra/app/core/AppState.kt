package com.itantra.app.core

import android.bluetooth.BluetoothDevice

enum class OperatingMode { WALKIE_TALKIE, NORMAL_PHONE }

enum class TalkState {
    IDLE, LISTENING_FOR_SPEECH, TRANSMITTING, RECEIVING, PLAYING_ALERT
}

enum class BtConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

enum class SupportedLanguage(val displayName: String, val bcp47: String) {
    HINDI("Hindi", "hi"), ENGLISH("English", "en"), MARATHI("Marathi", "mr"),
    GUJARATI("Gujarati", "gu"), BENGALI("Bengali", "bn"), KANNADA("Kannada", "kn"),
    MALAYALAM("Malayalam", "ml"), TAMIL("Tamil", "ta"), TELUGU("Telugu", "te"), ODIA("Odia", "or")
}

data class BluetoothPeer(val address: String, val name: String, val device: BluetoothDevice)

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
    val installInProgress: Boolean = false,
    val installMessage: String = "",
    val bluetoothDevices: List<BluetoothPeer> = emptyList(),
    val errorMessage: String? = null
)
