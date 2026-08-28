package com.example.phonemic.audio

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioVisualizerState {
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _waveform = MutableStateFlow(FloatArray(30) { 0f })
    val waveform: StateFlow<FloatArray> = _waveform.asStateFlow()

    fun updateAmplitude(normAmp: Float) {
        _amplitude.value = normAmp

        val currentWave = _waveform.value.copyOf()
        // Shift left
        System.arraycopy(currentWave, 1, currentWave, 0, currentWave.size - 1)
        currentWave[currentWave.size - 1] = normAmp
        _waveform.value = currentWave
    }
}
