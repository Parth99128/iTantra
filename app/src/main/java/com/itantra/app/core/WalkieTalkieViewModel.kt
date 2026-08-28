package com.itantra.app.core

import android.app.Application
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
    private var pcmFloatBuffer = FloatArray(512)
    private val bluetooth = BluetoothTransceiver(
        onTextReceived = { text -> handleIncomingText(text) },
        onStateChanged = { state -> _uiState.update { it.copy(btState = state) } }
    )
    private val ALERT_KEYWORDS = listOf("madad", "bachao", "help", "emergency", "sos")

    fun installModelPack(uri: Uri) {
        viewModelScope.launch {
            runCatching { packManager.installPack(uri) }
                .onSuccess { _uiState.update { it.copy(errorMessage = "Offline model pack installed. Select a language to load it.") } }
                .onFailure { e -> _uiState.update { it.copy(errorMessage = "Model pack installation failed: ${e.message}") } }
        }
    }

    fun loadModels(language: SupportedLanguage) {
        viewModelScope.launch {
            runCatching {
                require(packManager.isInstalled(language)) { "${language.displayName} offline model pack is not installed" }
                if (packManager.hasStt(language)) sttEngine.value.loadModel(language)
                if (packManager.hasTts(language)) ttsEngine.value.loadModel(language)
            }.onSuccess {
                _uiState.update { it.copy(language = language, errorMessage = null) }
            }.onFailure { e -> _uiState.update { it.copy(errorMessage = e.message ?: "Offline model unavailable") } }
        }
    }

    fun setMode(mode: OperatingMode) {
        _uiState.update { it.copy(mode = mode) }
        if (mode == OperatingMode.NORMAL_PHONE) { stopListening(); bluetooth.disconnect() }
    }
    fun startAsHost() = bluetooth.startAsServer()
    fun connectAsClient(deviceMac: String) { android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(deviceMac)?.let { bluetooth.connectToDevice(it) } }

    fun onPushToTalkStart() {
        if (_uiState.value.mode != OperatingMode.WALKIE_TALKIE) return
        viewModelScope.launch {
            try {
                val language = _uiState.value.language
                require(packManager.hasStt(language)) { "${language.displayName} does not have an offline STT model installed" }
                if (!sttEngine.isInitialized()) sttEngine.value.loadModel(language)
                if (!vad.isInitialized()) vad.value
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
        viewModelScope.launch {
            runCatching {
                require(sttEngine.isInitialized()) { "Offline STT model is not loaded" }
                val start = System.currentTimeMillis(); val result = sttEngine.value.finalizeUtterance(); val latency = System.currentTimeMillis() - start
                _uiState.update { it.copy(talkState = TalkState.TRANSMITTING, lastFinalTranscript = result.text, lastSttLatencyMs = latency) }
                if (result.text.isNotBlank()) bluetooth.sendText(result.text)
                _uiState.update { it.copy(talkState = TalkState.IDLE) }
            }.onFailure { e -> _uiState.update { it.copy(talkState = TalkState.IDLE, errorMessage = "STT failed: ${e.message}") } }
        }
    }

    private fun onMicFrame(pcm: ShortArray, size: Int) {
        if (!sttEngine.isInitialized() || !vad.isInitialized()) return
        sttEngine.value.acceptAudioFrame(pcm, size)
        if (pcmFloatBuffer.size != size) pcmFloatBuffer = FloatArray(size)
        for (i in 0 until size) pcmFloatBuffer[i] = pcm[i] / 32768f
        runCatching { vad.value.processChunk(pcmFloatBuffer) }
            .onSuccess { r -> if (r.utteranceEnded && _uiState.value.talkState == TalkState.LISTENING_FOR_SPEECH) onPushToTalkEnd() }
            .onFailure { e -> _uiState.update { it.copy(errorMessage = "VAD failed: ${e.message}") } }
    }

    private fun handleIncomingText(text: String) {
        val language = _uiState.value.language
        _uiState.update { it.copy(talkState = TalkState.RECEIVING, lastReceivedText = text) }
        viewModelScope.launch {
            runCatching {
                require(packManager.hasTts(language)) { "Offline TTS model for ${language.displayName} is not installed" }
                if (!ttsEngine.isInitialized()) ttsEngine.value.loadModel(language)
                val receivedAt = System.currentTimeMillis(); val result = ttsEngine.value.synthesize(text)
                val alert = ALERT_KEYWORDS.any { text.contains(it, ignoreCase = true) }
                _uiState.update { it.copy(talkState = if (alert) TalkState.PLAYING_ALERT else TalkState.RECEIVING, lastTtsLatencyMs = result.processingTimeMs, lastEndToEndMs = System.currentTimeMillis() - receivedAt) }
                if (alert) audioPlayer.playAlert(result.pcm16, result.sampleRate) else audioPlayer.playVoiceNote(result.pcm16, result.sampleRate)
                _uiState.update { it.copy(talkState = TalkState.IDLE) }
            }.onFailure { e -> _uiState.update { it.copy(talkState = TalkState.IDLE, errorMessage = "TTS failed: ${e.message}") } }
        }
    }
    private fun stopListening() { recorder?.stop(); recorder = null }
    override fun onCleared() { if (sttEngine.isInitialized()) sttEngine.value.release(); if (ttsEngine.isInitialized()) ttsEngine.value.release(); if (vad.isInitialized()) vad.value.release(); bluetooth.disconnect() }
}
