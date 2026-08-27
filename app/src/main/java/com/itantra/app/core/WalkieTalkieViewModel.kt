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

/**
 * This is the single most important file for demo day: it is the literal
 * implementation of the pipeline diagram we designed —
 *
 *   Mic -> VAD -> STT -> (finalize on pause) -> Bluetooth send (text)
 *   Bluetooth receive (text) -> TTS -> AudioPlayer
 *
 * A simple alert-keyword check decides whether incoming speech is played back
 * as a normal "voice note" or as a non-interruptible max-volume alert — swap
 * `ALERT_KEYWORDS` for whatever your actual distress-trigger design ends up being
 * (e.g. a dedicated "SOS" button instead of keyword detection is simpler and more
 * reliable for a live demo).
 */
class WalkieTalkieViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val sttEngine: SttEngine = VoskSttEngine(application)
    private val ttsEngine: TtsEngine = OnnxVitsTtsEngine(application)
    private val vad = SileroVad(application)
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
            sttEngine.loadModel(language)
            ttsEngine.loadModel(language)
            _uiState.update { it.copy(language = language) }
        }
    }

    fun setMode(mode: OperatingMode) {
        _uiState.update { it.copy(mode = mode) }
        if (mode == OperatingMode.NORMAL_PHONE) {
            stopListening()
            bluetooth.disconnect()
        }
    }

    fun startAsHost() = bluetooth.startAsServer()
    fun connectAsClient(deviceMac: String) {
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        val device = adapter?.getRemoteDevice(deviceMac)
        device?.let { bluetooth.connectToDevice(it) }
    }

    /** Call on push-to-talk button DOWN. */
    fun onPushToTalkStart() {
        if (_uiState.value.mode != OperatingMode.WALKIE_TALKIE) return
        _uiState.update { it.copy(talkState = TalkState.LISTENING_FOR_SPEECH, partialTranscript = "") }
        vad.reset()

        recorder = AudioRecorder { pcm, size -> onMicFrame(pcm, size) }.also { it.start() }
    }

    /** Call on push-to-talk button UP — also triggered automatically if VAD detects
     *  a sustained pause before release, per the PS's "detects pauses and stoppages" spec. */
    fun onPushToTalkEnd() {
        recorder?.stop()
        recorder = null

        viewModelScope.launch {
            val start = System.currentTimeMillis()
            val result = sttEngine.finalizeUtterance()
            val sttLatency = System.currentTimeMillis() - start

            _uiState.update {
                it.copy(
                    talkState = TalkState.TRANSMITTING,
                    lastFinalTranscript = result.text,
                    lastSttLatencyMs = sttLatency
                )
            }

            if (result.text.isNotBlank()) {
                bluetooth.sendText(result.text)
            }
            _uiState.update { it.copy(talkState = TalkState.IDLE) }
        }
    }

    private fun onMicFrame(pcm: ShortArray, size: Int) {
        // Feed STT continuously (streaming/incremental decoding lowers perceived latency).
        sttEngine.acceptAudioFrame(pcm, size)
        _uiState.update {
            // Cheap partial preview; the engine also pushes results via observePartialResults().
            it
        }

        // Feed VAD in parallel to detect a pause/stop mid-hold (not just on button release).
        if (pcmFloatBuffer.size != size) pcmFloatBuffer = FloatArray(size)
        for (i in 0 until size) pcmFloatBuffer[i] = pcm[i] / 32768f
        val vadResult = vad.processChunk(pcmFloatBuffer)
        if (vadResult.utteranceEnded && _uiState.value.talkState == TalkState.LISTENING_FOR_SPEECH) {
            onPushToTalkEnd()
        }
    }

    private fun handleIncomingText(text: String) {
        _uiState.update { it.copy(talkState = TalkState.RECEIVING, lastReceivedText = text) }
        viewModelScope.launch {
            val receivedAt = System.currentTimeMillis()
            val ttsResult = ttsEngine.synthesize(text)

            val isAlert = ALERT_KEYWORDS.any { kw -> text.contains(kw, ignoreCase = true) }
            _uiState.update {
                it.copy(
                    talkState = TalkState.PLAYING_ALERT,
                    lastTtsLatencyMs = ttsResult.processingTimeMs,
                    lastEndToEndMs = System.currentTimeMillis() - receivedAt
                )
            }

            if (isAlert) {
                audioPlayer.playAlert(ttsResult.pcm16, ttsResult.sampleRate)
            } else {
                audioPlayer.playVoiceNote(ttsResult.pcm16, ttsResult.sampleRate)
            }
            _uiState.update { it.copy(talkState = TalkState.IDLE) }
        }
    }

    private fun stopListening() {
        recorder?.stop()
        recorder = null
    }

    override fun onCleared() {
        sttEngine.release()
        ttsEngine.release()
        vad.release()
        bluetooth.disconnect()
    }
}
