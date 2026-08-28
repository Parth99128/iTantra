package com.itantra.app.core

import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.itantra.app.audio.AudioPlayer
import com.itantra.app.audio.AudioRecorder
import com.itantra.app.bluetooth.BluetoothTransceiver
import com.itantra.app.stt.SttEngine
import com.itantra.app.stt.VoskSttEngine
import com.itantra.app.tts.OnnxVitsTtsEngine
import com.itantra.app.tts.TtsEngine
import com.itantra.app.vad.SileroVad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WalkieTalkieViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState
    private val packManager = ModelPackManager(application)
    private val sttEngine: Lazy<SttEngine> = lazy { VoskSttEngine(application) }
    private val ttsEngine: Lazy<TtsEngine> = lazy { OnnxVitsTtsEngine(application) }
    private val vad: Lazy<SileroVad> = lazy { SileroVad(application) }
    private val audioPlayer = AudioPlayer(application)
    private var recorder: AudioRecorder? = null
    private var pcmFloatBuffer = FloatArray(SileroVad.CHUNK_SAMPLES)
    private var currentActivity: Activity? = null
    private val bluetooth = BluetoothTransceiver(
        application,
        onTextReceived = { text -> handleIncomingText(text) },
        onStateChanged = { state -> _uiState.update { it.copy(btState = state) } },
        onDevicesChanged = { list ->
            val peers = list.map { BluetoothPeer(it.address, bluetoothName(it), it) }
            _uiState.update { it.copy(bluetoothDevices = peers) }
        },
        onError = { message -> _uiState.update { it.copy(errorMessage = message) } }
    )
    private val alertKeywords = listOf("madad", "bachao", "help", "emergency", "sos", "आपात", "मदद", "बचाओ")

    private fun bluetoothName(device: BluetoothDevice): String = bluetooth.safeName(device)
    fun attachActivity(activity: Activity) { currentActivity = activity }

    fun installModelPack(uri: Uri) {
        if (_uiState.value.installInProgress) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(installInProgress = true, installMessage = "Installing model pack…", errorMessage = null) }
            runCatching { packManager.installPack(uri) }
                .onSuccess { _uiState.update { it.copy(installInProgress = false, installMessage = "Pack installed. Choose its language and load models.") } }
                .onFailure { e -> _uiState.update { it.copy(installInProgress = false, installMessage = "", errorMessage = "Model pack installation failed: ${e.message}") } }
        }
    }

    fun loadModels(language: SupportedLanguage) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                require(packManager.isInstalled(language)) { "${language.displayName} offline model pack is not installed" }
                if (packManager.hasStt(language)) sttEngine.value.loadModel(language)
                if (packManager.hasTts(language)) ttsEngine.value.loadModel(language)
            }.onSuccess {
                _uiState.update { it.copy(language = language, errorMessage = null) }
            }.onFailure { e -> _uiState.update { it.copy(language = language, errorMessage = e.message ?: "Offline model unavailable") } }
        }
    }

    fun setMode(mode: OperatingMode) {
        _uiState.update { it.copy(mode = mode) }
        if (mode == OperatingMode.NORMAL_PHONE) { stopListening(); bluetooth.disconnect() }
    }
    fun startDiscovery() = bluetooth.startDiscovery()
    fun startAsHost() { currentActivity?.let { bluetooth.startAsServer(it) } ?: _uiState.update { it.copy(errorMessage = "Bluetooth host is not ready") } }
    fun connectToPeer(peer: BluetoothPeer) = bluetooth.connectToDevice(peer.device)
    fun disconnectBluetooth() = bluetooth.disconnect()

    fun onPushToTalkStart() {
        if (_uiState.value.mode != OperatingMode.WALKIE_TALKIE || recorder != null) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val language = _uiState.value.language
                require(packManager.hasStt(language)) { "${language.displayName} does not have an offline STT model installed" }
                sttEngine.value.loadModel(language)
                vad.value.reset()
                _uiState.update { it.copy(talkState = TalkState.LISTENING_FOR_SPEECH, partialTranscript = "", errorMessage = null) }
                recorder = AudioRecorder { pcm, size -> onMicFrame(pcm, size) }.also { it.start() }
            } catch (e: Throwable) {
                _uiState.update { it.copy(talkState = TalkState.IDLE, errorMessage = "Microphone/ML initialization failed: ${e.message}") }
            }
        }
    }

    fun onPushToTalkEnd() {
        recorder?.stop(); recorder = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                require(sttEngine.isInitialized()) { "Offline STT model is not loaded" }
                val result = sttEngine.value.finalizeUtterance()
                _uiState.update { it.copy(talkState = TalkState.TRANSMITTING, lastFinalTranscript = result.text, lastSttLatencyMs = result.processingTimeMs) }
                if (result.text.isNotBlank()) bluetooth.sendText(result.text)
                _uiState.update { it.copy(talkState = TalkState.IDLE) }
            }.onFailure { e -> _uiState.update { it.copy(talkState = TalkState.IDLE, errorMessage = "STT failed: ${e.message}") } }
        }
    }

    private fun onMicFrame(pcm: ShortArray, size: Int) {
        if (!sttEngine.isInitialized() || !vad.isInitialized()) return
        if (size != SileroVad.CHUNK_SAMPLES) return
        if (pcmFloatBuffer.size != size) pcmFloatBuffer = FloatArray(size)
        for (i in 0 until size) pcmFloatBuffer[i] = pcm[i] / 32768f
        runCatching { vad.value.processChunk(pcmFloatBuffer) }
            .onSuccess { r ->
                if (r.isSpeech) sttEngine.value.acceptAudioFrame(pcm, size)
                if (r.utteranceEnded && _uiState.value.talkState == TalkState.LISTENING_FOR_SPEECH) onPushToTalkEnd()
            }
            .onFailure { e -> _uiState.update { it.copy(errorMessage = "VAD failed: ${e.message}") } }
    }

    fun testTts(text: String) {
        val language = _uiState.value.language
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                require(packManager.hasTts(language)) { "Offline TTS model for ${language.displayName} is not installed" }
                ttsEngine.value.loadModel(language)
                _uiState.update { it.copy(talkState = TalkState.RECEIVING, errorMessage = null) }
                val result = ttsEngine.value.synthesize(text)
                _uiState.update { it.copy(talkState = TalkState.PLAYING_ALERT, lastTtsLatencyMs = result.processingTimeMs) }
                audioPlayer.playVoiceNote(result.pcm16, result.sampleRate)
                _uiState.update { it.copy(talkState = TalkState.IDLE) }
            }.onFailure { e -> _uiState.update { it.copy(talkState = TalkState.IDLE, errorMessage = "TTS failed: ${e.message}") } }
        }
    }

    private fun handleIncomingText(text: String) {
        val language = _uiState.value.language
        _uiState.update { it.copy(talkState = TalkState.RECEIVING, lastReceivedText = text, errorMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                require(packManager.hasTts(language)) { "Offline TTS model for ${language.displayName} is not installed" }
                ttsEngine.value.loadModel(language)
                val start = System.currentTimeMillis()
                val result = ttsEngine.value.synthesize(text)
                val alert = alertKeywords.any { text.contains(it, ignoreCase = true) }
                _uiState.update { it.copy(talkState = if (alert) TalkState.PLAYING_ALERT else TalkState.RECEIVING, lastTtsLatencyMs = result.processingTimeMs, lastEndToEndMs = System.currentTimeMillis() - start) }
                if (alert) audioPlayer.playAlert(result.pcm16, result.sampleRate) else audioPlayer.playVoiceNote(result.pcm16, result.sampleRate)
                _uiState.update { it.copy(talkState = TalkState.IDLE) }
            }.onFailure { e -> _uiState.update { it.copy(talkState = TalkState.IDLE, errorMessage = "TTS failed: ${e.message}") } }
        }
    }

    private fun stopListening() { recorder?.stop(); recorder = null }
    override fun onCleared() { stopListening(); if (sttEngine.isInitialized()) sttEngine.value.release(); if (ttsEngine.isInitialized()) ttsEngine.value.release(); if (vad.isInitialized()) vad.value.release(); bluetooth.close() }
}
