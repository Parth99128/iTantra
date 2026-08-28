package com.itantra.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Local PCM playback for voice notes, normal call-style voice, and emergency alerts. */
class AudioPlayer(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var activeTrack: AudioTrack? = null

    fun playVoiceNote(pcm16: ShortArray, sampleRate: Int) = play(pcm16, sampleRate, Mode.VOICE_NOTE)
    fun playCallVoice(pcm16: ShortArray, sampleRate: Int) = play(pcm16, sampleRate, Mode.CALL)
    fun playAlert(pcm16: ShortArray, sampleRate: Int) = play(pcm16, sampleRate, Mode.ALERT)

    private enum class Mode { VOICE_NOTE, CALL, ALERT }

    @Synchronized
    private fun play(pcm16: ShortArray, sampleRate: Int, mode: Mode) {
        if (pcm16.isEmpty() || sampleRate <= 0) return
        stopActive()

        val isAlert = mode == Mode.ALERT
        val usage = when (mode) {
            Mode.ALERT -> AudioAttributes.USAGE_ALARM
            Mode.CALL -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            Mode.VOICE_NOTE -> AudioAttributes.USAGE_MEDIA
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        if (isAlert) {
            setMaxVolume(AudioManager.STREAM_ALARM)
            requestFocus(attrs)
        } else if (mode == Mode.CALL) {
            // Use communication stream for phone-call style listening, while keeping
            // the phone's normal call controls untouched.
            requestFocus(attrs)
        } else {
            requestFocus(attrs)
        }

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = minBuffer.coerceAtLeast(pcm16.size * 2).coerceAtLeast(4096)

        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        activeTrack = track
        track.write(pcm16, 0, pcm16.size)
        track.setVolume(1.0f)
        track.play()

        val durationMs = (pcm16.size * 1000L) / sampleRate
        Handler(Looper.getMainLooper()).postDelayed({
            synchronized(this) {
                if (activeTrack === track) {
                    try { track.stop() } catch (_: Exception) { }
                    try { track.release() } catch (_: Exception) { }
                    activeTrack = null
                    abandonFocus()
                }
            }
        }, durationMs + 300L)
    }

    private fun requestFocus(attrs: AudioAttributes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(false)
                .build()
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION") audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION") audioManager.abandonAudioFocus(null)
        }
    }

    private fun setMaxVolume(stream: Int) {
        try {
            audioManager.setStreamVolume(stream, audioManager.getStreamMaxVolume(stream), 0)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun stopActive() {
        activeTrack?.let {
            try { it.stop() } catch (_: Exception) { }
            try { it.release() } catch (_: Exception) { }
        }
        activeTrack = null
        abandonFocus()
    }
}
