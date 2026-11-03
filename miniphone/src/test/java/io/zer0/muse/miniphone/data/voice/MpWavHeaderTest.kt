package io.zer0.muse.miniphone.data.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WAV Diseño del campo de encabezado.
 *
 * Emparejamiento byte a byte de RIFF/WAVE estándar: desplazamiento incorrecto,El servicio de reconocimiento sólo responderá "Formato no compatible".,
 * No se pueden ver excepciones localmente. —— Entonces lo que se afirma aquí es el valor específico en la posición específica.
 */
class MpWavHeaderTest {

    @Test
    fun `El encabezado se fija en 44 bytes y termina con tres párrafos de datos RIFF WAVE.`() {
        val header = wavHeader(dataLength = 1_000, sampleRate = 16_000)

        assertEquals("WAV longitud de la cabeza", 44, header.size)
        assertEquals("RIFF", ascii(header, 0, 4))
        assertEquals("WAVE", ascii(header, 8, 4))
        assertEquals("fmt ", ascii(header, 12, 4))
        assertEquals("data", ascii(header, 36, 4))
    }

    @Test
    fun `RIFF La longitud del bloque es igual a dataLength más 36`() {
        val dataLength = 3_200

        val header = wavHeader(dataLength = dataLength, sampleRate = 16_000)

        assertEquals(36 + dataLength, intLe(header, 4))
    }

    @Test
    fun `data La longitud del bloque es igual a dataLength`() {
        val dataLength = 32_000

        val header = wavHeader(dataLength = dataLength, sampleRate = 16_000)

        assertEquals(dataLength, intLe(header, 40))
    }

    @Test
    fun `fmt El bloque especifica PCM mono de 16 bits y frecuencia de muestreo.`() {
        val header = wavHeader(dataLength = 0, sampleRate = 16_000, channels = 1, bitsPerSample = 16)

        assertEquals("fmt longitud del bloque", 16, intLe(header, 16))
        assertEquals("PCM número de formato", 1, shortLe(header, 20))
        assertEquals("Número de canales", 1, shortLe(header, 22))
        assertEquals("Tasa de muestreo", 16_000, intLe(header, 24))
        assertEquals("bytes por segundo = Tasa de muestreo × tracto vocal × profundidad de bits / 8", 32_000, intLe(header, 28))
        assertEquals("alineación de bloques = tracto vocal × profundidad de bits / 8", 2, shortLe(header, 32))
        assertEquals("profundidad de bits", 16, shortLe(header, 34))
    }

    @Test
    fun `El estéreo bloquea la alineación y duplica los bytes por segundo`() {
        val header = wavHeader(dataLength = 0, sampleRate = 8_000, channels = 2, bitsPerSample = 16)

        assertEquals("Número de canales", 2, shortLe(header, 22))
        assertEquals("Tasa de muestreo", 8_000, intLe(header, 24))
        assertEquals("alineación de bloques", 4, shortLe(header, 32))
        assertEquals("bytes por segundo", 32_000, intLe(header, 28))
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String = String(bytes, offset, length, Charsets.US_ASCII)

    private fun intLe(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun shortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
