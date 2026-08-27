package com.itantra.app.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Captures mic audio at 16kHz mono PCM16 — this exact format is required by both
 * Vosk (STT) and Silero VAD. Do not change sample rate here without updating both.
 */
class AudioRecorder(
    private val onFrame: (ShortArray, Int) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 16000
        // 512 samples matches Silero VAD's expected chunk size.
        const val CHUNK_SIZE = 512
    }

    private var audioRecord: AudioRecord? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBufSize, CHUNK_SIZE * 4)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, // tuned by OEMs for speech, better than MIC/DEFAULT
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )

        audioRecord?.startRecording()

        job = scope.launch {
            val buffer = ShortArray(CHUNK_SIZE)
            while (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = audioRecord?.read(buffer, 0, CHUNK_SIZE) ?: 0
                if (read > 0) onFrame(buffer, read)
            }
        }
    }

    fun stop() {
        job?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
