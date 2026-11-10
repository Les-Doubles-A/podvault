package com.podvault.app.service

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/** Niveau sonore courant (0..1), partagé entre le service de lecture et l'interface (même processus). */
object AudioLevel {
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    fun publish(value: Float) {
        _level.value = value.coerceIn(0f, 1f)
    }
}

/**
 * Processeur "pass-through" : l'audio n'est pas modifié, on mesure seulement l'énergie (RMS)
 * de chaque tampon PCM 16 bits. Aucune permission d'enregistrement n'est nécessaire.
 */
@UnstableApi
class AudioLevelProcessor : BaseAudioProcessor() {

    private var smoothed = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        var sum = 0.0
        var samples = 0
        val view = inputBuffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        // Un échantillon sur 4 suffit pour une mesure d'énergie et limite le coût CPU.
        var position = view.position()
        val end = view.limit() - 1
        while (position < end) {
            val sample = view.getShort(position).toInt() / 32768.0
            sum += sample * sample
            samples++
            position += 8
        }
        if (samples > 0) {
            val rms = sqrt(sum / samples).toFloat()
            // La parole a un RMS faible : on amplifie puis on lisse (attaque rapide, relâchement lent).
            val target = (rms * 5f).coerceIn(0f, 1f)
            smoothed = if (target > smoothed) smoothed + (target - smoothed) * 0.6f else smoothed * 0.88f
            AudioLevel.publish(smoothed)
        }
        replaceOutputBuffer(size).put(inputBuffer).flip()
    }

    override fun onFlush() {
        smoothed = 0f
        AudioLevel.publish(0f)
    }

    override fun onReset() {
        onFlush()
    }
}
