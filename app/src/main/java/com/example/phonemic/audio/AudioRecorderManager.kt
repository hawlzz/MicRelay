package com.example.phonemic.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class AudioRecorderManager(
    val sampleRate: Int = 44100,
    val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) {
    companion object {
        private const val TAG = "AudioRecorderManager"
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var noiseSuppressor: NoiseSuppressor? = null

    var gainMultiplier: Float = 1.0f
    var isMuted: Boolean = false
    var isNoiseSuppressionEnabled: Boolean = true
        set(value) {
            field = value
            updateEffectStates()
        }

    val visualizerState = AudioVisualizerState()

    // Listeners receiving raw PCM 16-bit audio byte chunks
    private val listeners = mutableListOf<(ByteArray, Int) -> Unit>()

    fun addAudioDataListener(listener: (ByteArray, Int) -> Unit) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    fun removeAudioDataListener(listener: (ByteArray, Int) -> Unit) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (isRecording) return true

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = minBufferSize.coerceAtLeast(4096)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return false
            }

            val audioSessionId = audioRecord?.audioSessionId ?: 0
            if (audioSessionId != 0) {
                initAudioEffects(audioSessionId)
            }

            audioRecord?.startRecording()
            isRecording = true

            recordingJob = scope.launch {
                val buffer = ByteArray(2048)
                val shortBuffer = ShortArray(1024)

                while (isActive && isRecording) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (readBytes > 0) {
                        // Apply mute or gain adjustment
                        processAudioBuffer(buffer, readBytes, shortBuffer)

                        // Dispatch to listeners
                        val activeListeners = synchronized(listeners) { listeners.toList() }
                        for (listener in activeListeners) {
                            try {
                                listener(buffer, readBytes)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in audio listener", e)
                            }
                        }
                    }
                }
            }

            Log.i(TAG, "Audio recording started successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioRecord", e)
            stopRecording()
            return false
        }
    }

    private fun processAudioBuffer(buffer: ByteArray, bytesRead: Int, shortBuffer: ShortArray) {
        val shortsToProcess = bytesRead / 2
        ByteBuffer.wrap(buffer, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortBuffer, 0, shortsToProcess)

        var maxAmp = 0
        val muted = isMuted
        val gain = gainMultiplier

        for (i in 0 until shortsToProcess) {
            if (muted) {
                shortBuffer[i] = 0
            } else if (gain != 1.0f) {
                var sample = (shortBuffer[i] * gain).toInt()
                sample = sample.coerceIn(-32768, 32767)
                shortBuffer[i] = sample.toShort()
            }
            val absVal = abs(shortBuffer[i].toInt())
            if (absVal > maxAmp) {
                maxAmp = absVal
            }
        }

        // Put modified samples back into byte array
        val byteBuf = ByteBuffer.wrap(buffer, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until shortsToProcess) {
            byteBuf.putShort(shortBuffer[i])
        }

        // Update visualizer state (0.0 to 1.0 normalized)
        val normalizedAmp = (maxAmp / 32768.0f).coerceIn(0.0f, 1.0f)
        visualizerState.updateAmplitude(normalizedAmp)
    }

    private fun initAudioEffects(audioSessionId: Int) {
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = isNoiseSuppressionEnabled
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio DSP effects initialization failed", e)
        }
    }

    private fun updateEffectStates() {
        try {
            noiseSuppressor?.enabled = isNoiseSuppressionEnabled
        } catch (e: Exception) {
            Log.w(TAG, "Failed to toggle noise suppressor", e)
        }
    }

    fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            noiseSuppressor?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing DSP effects", e)
        } finally {
            noiseSuppressor = null
        }

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        } finally {
            audioRecord = null
        }

        visualizerState.updateAmplitude(0f)
        Log.i(TAG, "Audio recording stopped")
    }
}
