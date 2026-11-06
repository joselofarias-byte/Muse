package io.zer0.muse.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.zer0.common.Logger

/**
 * Crea una grabadora de micrófono en unidad.
 *
 * Diferentes ROM tienen soporte inconsistente para fuentes de audio VOICE_COMMUNICATION; prensa“reconocimiento de voz -> llamar -> Micrófono predeterminado”
 * Pruébelos secuencialmente para evitar que un dispositivo rechace una única fuente de audio y haga que todo el enlace ASR no esté disponible.
 */
internal object AsrAudioCapture {
    data class Session(
        val recorder: AudioRecord,
        val bufferSize: Int,
    )

    @SuppressLint("MissingPermission")
    fun create(sampleRate: Int, tag: String): Session? {
        if (sampleRate !in 8_000..48_000) {
            Logger.w(tag, "Frecuencia de muestreo ASR no compatible: $sampleRate")
            return null
        }
        val minBuffer = try {
            AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        } catch (e: IllegalArgumentException) {
            Logger.w(tag, "No se pudo obtener el búfer AudioRecord: ${e.message}", e)
            return null
        }
        if (minBuffer <= 0) {
            Logger.w(tag, "AudioRecord Tamaño de búfer no válido: $minBuffer")
            return null
        }
        val bufferSize = (minBuffer * 2)
            .coerceAtLeast(sampleRate / 10 * 2)
            .coerceAtLeast(4096)
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.MIC,
        ).distinct()
        for (source in sources) {
            val recorder = try {
                AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 2,
                )
            } catch (e: SecurityException) {
                Logger.w(tag, "AudioRecord fuente de sonido $source rechazado: ${e.message}")
                null
            } catch (e: IllegalArgumentException) {
                Logger.w(tag, "AudioRecord fuente de sonido $source Parámetro no válido: ${e.message}")
                null
            }
            if (recorder != null) {
                if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                    return Session(recorder, bufferSize)
                }
                recorder.release()
            }
        }
        Logger.w(tag, "Todas las fuentes de AudioRecord no pudieron inicializarse")
        return null
    }
}
