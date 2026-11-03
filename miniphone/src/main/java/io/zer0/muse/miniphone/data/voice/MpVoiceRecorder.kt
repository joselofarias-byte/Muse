package io.zer0.muse.miniphone.data.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.zer0.common.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Contrato externo para grabación.
 *
 * lo real es [MpVoiceRecorder];El motivo de la interfaz de extracción es que el camino de "presionar el micrófono" debe completarse en una sola prueba.,
 * Y realmente no deberías encender el micrófono durante una sola prueba.,No debe solicitar permiso para una migración estatal.
 */
internal interface MpVoiceCapture {

    /** Empezar a coleccionar;No se pudo conseguir el micrófono o no se pudo recoger y devolver false(No quedan archivos temporales en este momento). */
    fun start(): Boolean

    /** Detener la recogida y entregar el segmento completo. WAV;Devuelve nulo cuando no hay grabación o el audio está vacío. */
    suspend fun stop(): ByteArray?

    /** Descartar esta grabación:Deje de minar y elimine archivos temporales. */
    fun cancel()
}

/**
 * Grabador para el portal de voz de la página de chat.
 *
 * PCM16 / 16kHz / mono,El hilo de adquisición lee y escribe archivos temporales al mismo tiempo. —— Sesenta segundos son aproximadamente 1,9 MB.,
 * La memoria completa no es demasiado grande.,Sin embargo, la memoria quedará ocupada durante la grabación.,dump No puedo decir cómo se ve el audio.
 * Al detenerse, agregue el encabezado WAV al archivo temporal y lea la sección completa.,Eliminar el archivo nuevamente:Lo que se entrega son bytes.,No es un camino.
 *
 * La colección más larga [MAX_DURATION_MS]:Cuando llegue el momento, no escribiré más.,No sea que el usuario se olvide del punto final y llene el disco.
 * Después de llegar, aún debes organizarlo afuera. [stop] para obtener el audio —— Quien decide "la grabación ha terminado" lo decide la máquina de estados.
 *
 * @param directory directorio de archivos temporales,Generalmente pasa `cacheDir`
 * @param factory Haz un agujero para el coleccionista.;Pruebe usarlo para reemplazar el micrófono real.
 * @param ioDispatcher Hilos para recopilar, leer y escribir archivos.
 * @param maxDurationMs Duración máxima de una sola grabación
 */
internal class MpVoiceRecorder(
    private val directory: File,
    private val factory: MpAudioRecordFactory = SystemMpAudioRecordFactory(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxDurationMs: Long = MAX_DURATION_MS,
) : MpVoiceCapture {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** Esta grabación en progreso.;null Dijo que no estaba grabando. */
    private var session: Session? = null

    init {
        // Los archivos temporales que dejó el último proceso que se eliminó se borran en segundo plano.:La construcción ocurre en composición.,No puedo hacer archivos aquí IO
        scope.launch { cleanupStaleRecordings(directory) }
    }

    override fun start(): Boolean {
        if (session != null) return false
        val record = openRecord() ?: return false
        val file = createTempFile()
        if (file == null || !beginCapture(record)) {
            record.releaseQuietly()
            file?.delete()
            return false
        }
        val current = Session(record = record, file = file, maxBytes = bytesOf(maxDurationMs))
        session = current
        current.job = scope.launch { pump(current) }
        return true
    }

    /** Consigue un coleccionista disponible;Si se produce una excepción durante la inicialización o no se puede obtener, se considerará que la grabación no ha comenzado. */
    private fun openRecord(): MpAudioRecord? = runCatching { factory.create(SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE) }
        .onFailure { Logger.w(TAG, "Falló la inicialización del micrófono: ${it.message}") }
        .getOrNull()

    /** Empezar a minar;Si el recolector se niega a arrancar, se considera un fracaso. */
    private fun beginCapture(record: MpAudioRecord): Boolean = runCatching { record.start() }
        .onFailure { Logger.w(TAG, "La minería falló: ${it.message}") }
        .isSuccess

    override suspend fun stop(): ByteArray? = withContext(ioDispatcher) {
        val current = session ?: return@withContext null
        session = null
        current.record.stopQuietly()
        current.job?.join()
        current.record.releaseQuietly()
        val wav = readWavOrNull(current.file)
        current.file.delete()
        wav
    }

    override fun cancel() {
        val current = session ?: return
        session = null
        current.record.stopQuietly()
        current.job?.cancel()
        // Espere hasta que el bucle de recopilación realmente salga antes de eliminarlo.,Para no eliminarlo en una transmisión que aún está abierta.
        scope.launch {
            runCatching { current.job?.join() }
            current.record.releaseQuietly()
            current.file.delete()
        }
    }

    /** Bucle de adquisición:Escribe tanto como lees,Deténgase cuando se alcance el límite. */
    private suspend fun pump(current: Session) {
        try {
            FileOutputStream(current.file).use { sink ->
                val buffer = ByteArray(BUFFER_BYTES)
                var written = 0L
                while (written < current.maxBytes && currentCoroutineContext().isActive) {
                    val read = current.record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        sink.write(buffer, 0, read)
                        written += read
                    } else if (read == 0) {
                        // El recopilador no tiene datos temporalmente.:ceder el paso,No te quedes inactivo y quemes CPU
                        delay(READ_RETRY_MS)
                    } else {
                        Logger.w(TAG, "AudioRecord.read Regresar $read,Esta grabación termina temprano")
                        break
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Logger.w(TAG, "La escritura de grabación falló: ${e.message}")
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "Estado anormal del colector: ${e.message}")
        } finally {
            // Si finaliza normalmente, si se llena el límite superior o si se cancela, la minería se detendrá aquí.:pump No debes seguir ocupando el micrófono después de salir
            current.record.stopQuietly()
        }
    }

    private fun createTempFile(): File? = runCatching {
        directory.mkdirs()
        File(directory, TEMP_PREFIX + UUID.randomUUID() + TEMP_SUFFIX)
    }.onFailure { Logger.w(TAG, "Falló la creación del archivo temporal: ${it.message}") }.getOrNull()

    /** Solo hay archivos desnudos en el archivo temporal. PCM,Léelo y agrega el encabezado WAV.;Si no se recopila ni un solo byte, se supone que no hay audio. */
    private fun readWavOrNull(file: File): ByteArray? = runCatching {
        val pcm = file.readBytes()
        if (pcm.isEmpty()) return null
        val header = wavHeader(pcm.size, SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE)
        ByteArray(header.size + pcm.size).also { wav ->
            header.copyInto(wav)
            pcm.copyInto(wav, header.size)
        }
    }.onFailure { Logger.w(TAG, "No se pudo leer la grabación: ${it.message}") }.getOrNull()

    /** Toda la escena grabada de una vez.:Colector, archivo temporal, límite de escritura. */
    private class Session(
        val record: MpAudioRecord,
        val file: File,
        val maxBytes: Long,
    ) {
        var job: Job? = null
    }

    companion object {
        /** 16kHz:La frecuencia de muestreo más utilizada para servidores de reconocimiento de voz.,No importa qué tan alto sea, ocupa ancho de banda en vano. */
        const val SAMPLE_RATE: Int = 16_000

        const val CHANNELS: Int = 1

        const val BITS_PER_SAMPLE: Int = 16

        /** Graba hasta sesenta segundos a la vez. */
        const val MAX_DURATION_MS: Long = 60_000L

        /** El número de bytes que se pueden recopilar durante este período.:PCM por segundo = Tasa de muestreo × tracto vocal × Profundidad de bits / 8. */
        private fun bytesOf(durationMs: Long): Long = durationMs * SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8) / 1_000L
    }
}

/**
 * Borre los archivos de grabación temporales restantes.
 *
 * Mueva únicamente archivos con el prefijo de este módulo y que no hayan sido modificados durante más de una hora.:El límite máximo para una sola grabación es de un minuto y el archivo se eliminará cuando finalice normalmente.,
 * Sobrevivió una hora `mp-voice-*` Sólo puede quedar cuando se mata el proceso. Un cuchillo según tarjeta de tiempo.,
 * Incluso si la limpieza choca con el recopilador recién creado de otra página,,El que se está grabando no se eliminará.
 *
 * @return De hecho, eliminé algunos;Si el directorio no está allí y el archivo no se puede eliminar, solo se registrará el registro.,No lo tires
 */
internal fun cleanupStaleRecordings(directory: File, now: Long = System.currentTimeMillis()): Int = runCatching {
    (directory.listFiles() ?: emptyArray())
        .filter { it.isFile && it.isStaleRecording(now) }
        .count { it.deleteQuietly() }
}.onFailure { Logger.w(TAG, "No se pudieron borrar las grabaciones residuales: ${it.message}") }.getOrDefault(0)

private fun File.isStaleRecording(now: Long): Boolean = name.startsWith(TEMP_PREFIX) && now - lastModified() > STALE_RECORDING_MS

private fun File.deleteQuietly(): Boolean = runCatching { delete() }.onFailure { Logger.w(TAG, "No se pudo eliminar la grabación residual: $name") }.getOrDefault(false)

private const val TAG = "MpVoiceRecorder"

/** Los archivos de grabación que hayan sido modificados dentro de una hora no serán tocados.:Esa es una grabación viva,Ni un residuo. */
private const val STALE_RECORDING_MS = 60L * 60L * 1_000L

private const val BUFFER_BYTES = 8_192
private const val READ_RETRY_MS = 10L

/** La forma de ubicación en disco del nombre del archivo temporal.;La lógica de limpieza se basa en este prefijo para identificar a las personas. */
private const val TEMP_PREFIX = "mp-voice-"
private const val TEMP_SUFFIX = ".pcm"

/**
 * Encabezado WAV estándar de 44 bytes:RIFF cáscara + 16 bytes fmt(PCM)+ data longitud del bloque.
 *
 * @param dataLength seguido del número de bytes PCM
 * @param sampleRate Tasa de muestreo
 * @param channels Número de canales
 * @param bitsPerSample profundidad de bits
 */
internal fun wavHeader(dataLength: Int, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
    require(dataLength >= 0) { "dataLength no puede ser negativo: $dataLength" }
    val blockAlign = channels * bitsPerSample / 8
    val header = ByteArray(WAV_HEADER_SIZE)
    header.writeIntLe(OFFSET_RIFF_SIZE, WAV_HEADER_SIZE - 8 + dataLength) // RIFF longitud del bloque = 36 + dataLength
    header.writeAscii(OFFSET_RIFF, "RIFF")
    header.writeAscii(OFFSET_WAVE, "WAVE")
    header.writeAscii(OFFSET_FMT, "fmt ")
    header.writeIntLe(OFFSET_FMT_SIZE, FMT_CHUNK_SIZE)
    header.writeShortLe(OFFSET_AUDIO_FORMAT, PCM_FORMAT)
    header.writeShortLe(OFFSET_CHANNELS, channels)
    header.writeIntLe(OFFSET_SAMPLE_RATE, sampleRate)
    header.writeIntLe(OFFSET_BYTE_RATE, sampleRate * blockAlign)
    header.writeShortLe(OFFSET_BLOCK_ALIGN, blockAlign)
    header.writeShortLe(OFFSET_BITS_PER_SAMPLE, bitsPerSample)
    header.writeAscii(OFFSET_DATA, "data")
    header.writeIntLe(OFFSET_DATA_SIZE, dataLength)
    return header
}

private const val WAV_HEADER_SIZE = 44
private const val FMT_CHUNK_SIZE = 16
private const val PCM_FORMAT = 1

private const val OFFSET_RIFF = 0
private const val OFFSET_RIFF_SIZE = 4
private const val OFFSET_WAVE = 8
private const val OFFSET_FMT = 12
private const val OFFSET_FMT_SIZE = 16
private const val OFFSET_AUDIO_FORMAT = 20
private const val OFFSET_CHANNELS = 22
private const val OFFSET_SAMPLE_RATE = 24
private const val OFFSET_BYTE_RATE = 28
private const val OFFSET_BLOCK_ALIGN = 32
private const val OFFSET_BITS_PER_SAMPLE = 34
private const val OFFSET_DATA = 36
private const val OFFSET_DATA_SIZE = 40

/** Little endian escribe 4 bytes:WAV La longitud y la frecuencia de muestreo del encabezado son little-endian. */
private fun ByteArray.writeIntLe(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = (value shr 8 and 0xFF).toByte()
    this[offset + 2] = (value shr 16 and 0xFF).toByte()
    this[offset + 3] = (value shr 24 and 0xFF).toByte()
}

/** Little endian escribe 2 bytes. */
private fun ByteArray.writeShortLe(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = (value shr 8 and 0xFF).toByte()
}

private fun ByteArray.writeAscii(offset: Int, text: String) {
    text.encodeToByteArray().copyInto(this, offset)
}

/** Área mínima útil del colector.:Sólo quedan las cuatro acciones necesarias para el cobro.,En consecuencia, la prueba reemplaza el micrófono real. */
internal interface MpAudioRecord {
    fun start()

    fun read(buffer: ByteArray, offset: Int, size: Int): Int

    fun stop()

    fun release()
}

/** hacer [MpAudioRecord] la boca;Devuelve nulo cuando no hay ningún recopilador disponible. */
internal fun interface MpAudioRecordFactory {
    fun create(sampleRate: Int, channels: Int, bitsPerSample: Int): MpAudioRecord?
}

/** micrófono real:[AudioRecord] Haga coincidir la fuente de reconocimiento de voz con el mono PCM16 y entréguelo. */
private class SystemMpAudioRecordFactory : MpAudioRecordFactory {

    @SuppressLint("MissingPermission")
    override fun create(sampleRate: Int, channels: Int, bitsPerSample: Int): MpAudioRecord? {
        if (bitsPerSample != 16) return null
        val channelConfig = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val minBytes = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) return null
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBytes, MIN_RECORD_BUFFER_BYTES),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        return SystemMpAudioRecord(record)
    }

    private companion object {
        /** Leer límite inferior del búfer:8KB A 16kHz/16bit es 250ms,Por pequeño que sea, será fácil despertar el hilo con frecuencia. */
        const val MIN_RECORD_BUFFER_BYTES = 8_192
    }
}

/** [MpAudioRecord] verdadera realización:Reenviar varias llamadas a AudioRecord sin cambios. */
private class SystemMpAudioRecord(private val record: AudioRecord) : MpAudioRecord {

    override fun start() = record.startRecording()

    override fun read(buffer: ByteArray, offset: Int, size: Int): Int = record.read(buffer, offset, size)

    override fun stop() = record.stop()

    override fun release() = record.release()
}

private fun MpAudioRecord.stopQuietly() {
    runCatching { stop() }.onFailure { Logger.w("MpVoiceRecorder", "No detener la producción: ${it.message}") }
}

private fun MpAudioRecord.releaseQuietly() {
    runCatching { release() }.onFailure { Logger.w("MpVoiceRecorder", "No se pudo liberar el recopilador: ${it.message}") }
}
