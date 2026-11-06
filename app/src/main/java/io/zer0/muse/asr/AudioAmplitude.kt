package io.zer0.muse.asr

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Herramienta de cálculo de amplitud de volumen.
 * Convierta valores RMS PCM de 16 bits a amplitud normalizada de 0-1f,En línea con la percepción logarítmica del oído humano.
 */
object AudioAmplitude {
    /**
     * Calcule la amplitud RMS de datos PCM de 16 bits,Normalizado a 0-1f.
     * -60dB~0dB mapeado a 0~1f,Por debajo de -60 dB devuelve 0.
     */
    fun calculateRmsAmplitude(buffer: ByteArray, readBytes: Int): Float {
        if (readBytes < 2) return 0f
        val shorts = ByteBuffer.wrap(buffer, 0, readBytes)
            .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sum = 0.0
        val count = shorts.remaining()
        if (count == 0) return 0f
        for (i in 0 until count) {
            val sample = shorts[i].toDouble()
            sum += sample * sample
        }
        val rms = sqrt(sum / count)
        val linear = (rms / Short.MAX_VALUE).toFloat()
        if (linear < 1e-6f) return 0f
        val db = 20f * log10(linear)
        return ((db + 60f) / 60f).coerceIn(0f, 1f)
    }

    /** ventana corredera:Agregar un valor de amplitud,Deseche el más antiguo cuando se exceda maxSize. */
    fun List<Float>.appendAmplitude(amplitude: Float, maxSize: Int = 32): List<Float> {
        val list = if (size >= maxSize) drop(1) else this
        return list + amplitude
    }
}
