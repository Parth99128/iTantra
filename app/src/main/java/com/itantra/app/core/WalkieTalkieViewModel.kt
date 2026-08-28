package com.itantra.app.core

import android.app.Application
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

    // Speech engines are deliberately lazy: a bad native/model asset must never crash app startup.
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

    fun loadModels(language: SupportedLanguage) {
        viewModelScope.launch {
            runCatching {
                sttEngine.value.loadModel(language)
                ttsEngine.value.loadModel(language)
            }.onSuccess {
                _uiState.update { it.copy(language = language, errorMessage = null) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = "Offline model failed to initialize: ${error.message ?: error::class.java.simpleName}")
                }
            }
        }
    }

    fun setMode(mode: OperatingMode) {
        _uiState.update { it.copy(mode = mode) }
        if (mode == OperatingMode.NORMAL_PHONE) {
            stopListening(); bluetooth.disconnect()
        }
    }

    fun startAsHost() = bluetooth.startAsServer()
    fun connectAsClient(deviceMac: String) {
        android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(deviceMac)?.let {
            bluetooth.connectToDevice(it)
        }
    }

    fun onPushToTalkStart() {
        if (_uiState.value.mode != OperatingMode.WALKIE_TALKIE) return
        viewModelScope.launch {
            try {
                // Ensure the selected language models are ready before opening the mic.
                if (!sttEngine.isInitialized() || !ttsEngine.isInitialized()) {
                    loadModels(_uiState.value.language)
                    return@launch
                }
                vad.value.reset()
                _uiState.update { it.copy(talkState = TalkState.LISTENING_FOR_SPEECH, partialTranscript = "", errorMessage = null) }
                recorder = AudioRecorder { pcm, size -> onMicFrame(pcm, size) }.also { it.start() }
            } catch (error: Throwable) {
                _uiState.update { it.copy(talkState = TalkState.IDLE, errorMessage = "Microphone/ML initialization failed: ${error.message ?: error::class.java.simpleName}") }
            }
        }
    }

    fun onPushToTalkEnd() {
        recorder?.stop(); recorder = null
        viewModelScope.launch {
            runCatching {
                val start = System.currentTimeMillis()
                val result = sttEngine.value.finalizeUtterance()
                val latency = System.currentTimeMillis() - start
                _uiState.update { it.copy(talkState = TalkState.TRANSMITTING, lastFinalTranscript = result.text, lastSttLatencyMs = latency) }
                if (result.text.isNotBlank()) bluetooth.sendText(result.text)
                _uiState.update { it.copy(talkState = TalkState.IDLE) }
            }.onFailure { error ->
                _uiState.update { it.copy(talkState = TalkState.IDLE, errorMessage = "STT failed: ${error.message ?: error::class.java.simpleName}") }
            }
        }
    }

    private fun onMicFrame(pcm: ShortArray, size: Int) {
        if (!sttEngine.isInitialized() || !vad.isInitialized()) return
        sttEngine.value.acceptAudioFrame(pcm, size)
        if (pcmFloatBuffer.size != size) pcmFloatBuffer = FloatArray(size)
        for (i in 0 until size) pcmFloatBuffer[i] = pcm[i] / 32768f
        runCatching { vad.value.processChunk(pcmFloatBuffer) }.onSuccess { result ->
            if (result.utteranceEnded && _uiState.value.talkState == TalkState.LISTENING_FOR_SPEECH) onPushToTalkEnd()
        }.onFailure { error ->
            _uiState.update { it.copy(errorMessage = "VAD failed: ${error.message ?: error::class.java.simpleName}") }
        }
    }

    private fun handleIncomingText(text: String) {
        _uiState.update { it.copy(talkState = TalkState.RECEIVING, lastReceivedText = text) }
        viewModelScope.launch {
            runCatching {
                val receivedAt = System.currentTimeMillis()
                if (!ttsEngine.isInitialized()) throw IllegalStateException("TTS model is not loaded")
                val result = ttsEngine.value.synthesize(text)
                val alert = ALERT_KEYWORDS.any { text.contains(it, ignoreCase = true) }
                _uiState.update { it.copy(talkState = TalkState.PLAYING_ALERT, lastTtsLatencyMs = result.processingTimeMs, lastEndToEndMs = System.currentTimeMillis() - receivedAt) }
                if (alert) audioPlayer.playAlert(result.pcm16, result.sampleRate) else audioPlayer.playVoiceNote(result.pcm16, result.sampleRate)
                _uiState.update { it.copy(talkState = TalkState.IDLE) }
            }.onFailure { error ->
                _uiState.update { it.copy(talkState = TalkState.IDLE, errorMessage = "TTS failed: ${error.message ?: error::class.java.simpleName}") }
            }
        }
    }

    private fun stopListening() { recorder?.stop(); recorder = null }

    override fun onCleared() {
        if (sttEngine.isInitialized()) sttEngine.value.release()
        if (ttsEngine.isInitialized()) ttsEngine.value.release()
        if (vad.isInitialized()) vad.value.release()
        bluetooth.disconnect()
    }
}
