package io.zer0.muse.asr

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.os.SystemClock
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.asr.AudioAmplitude.appendAmplitude
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Controlador ASR de transmisión de audio por pasos.
 *
 * Step Punto final compatible con OpenAI Whisper(POST {baseUrl}/v1/audio/transcriptions)No compatible
 * WebSocket transmisión,Por lo tanto, se adopta una estrategia de carga por lotes segmentada.:
 *  - PCM acumulado en el buffer durante la grabación
 *  - por umbral de tiempo(Predeterminado 30s)o umbral de bytes(6MB)gatillo flush
 *  - cada vez flush:PCM -> WAV -> HTTP POST -> Analizar respuesta -> devolución de llamada onTranscriptChange
 *  - [stop] hazlo por última vez flush,Espere a que se complete el trabajo de descarga antes de cortar. Idle
 *  - FlushJob en serie evita el desorden simultáneo
 *
 * Solicitar(multipart/form-data):
 *  - file: archivo de audio(wav Formato,PCM 16-bit)
 *  - model: Nombre del modelo(Predeterminado step-audio-r1.1)
 *  - language: Idioma(Opcional)
 *
 * respuesta(JSON):
 *  - { "text": "Reconocer texto" }
 *
 * @param config ASR Configuración(Debe tener apiKey y model)
 * @param baseUrl API Conceptos básicos URL(Predeterminado https://api.stepfun.com)
 */
class StepAsrController(
    private val config: AsrConfig,
    private val baseUrl: String = config.baseUrl.ifBlank { DEFAULT_BASE_URL },
) : ASRController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ASRState(isAvailable = config.apiKey.isNotBlank()))
    private val isDisposed = AtomicBoolean(false)
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private var flushJob: Job? = null
    private val flushMutex = Mutex()
    private var onTranscriptChange: ((String) -> Unit)? = null

    // PCM buffer(bufferLock Copia y reinicio sincrónicos)
    private val pcmBuffer = ByteArrayOutputStream()
    private val bufferLock = Any()
    private var totalTranscript = StringBuilder()

    // B-29: Recuento de fallos de reconocimiento continuo,Descartar el segmento después de alcanzar el límite superior,Evite el apilamiento de reescritura infinita.
    private var consecutiveFlushFailures = 0

    // Umbral de segmentación:30 segundos o 6 MB, lo que ocurra primero
    private val segmentDurationMs = SEGMENT_DURATION_MS
    private val maxSegmentBytes = MAX_SEGMENT_BYTES
    private var segmentStartElapsedMs = 0L

    private val client: OkHttpClient = io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
        .connectTimeout(AsrConstants.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(AsrConstants.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    override fun start(onTranscriptChange: ((String) -> Unit)?) {
        if (isDisposed.get() || _state.value.isRecording) return
        this.onTranscriptChange = onTranscriptChange
        totalTranscript.clear()
        synchronized(bufferLock) {
            pcmBuffer.reset()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
        }
        consecutiveFlushFailures = 0
        flushJob = null
        _state.update {
            it.copy(
                status = ASRStatus.Connecting,
                transcript = "",
                errorMessage = null,
                amplitudes = emptyList(),
            )
        }
        startRecording()
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        // El bucle de grabación se ejecuta dentro de la rutina IO.,Utilice isActive internamente para comprobar el estado de cancelación
        recordJob = scope.launch(Dispatchers.IO) {
            // Verifique los permisos y cree grabadoras a partir de múltiples fuentes compatibles.
            val capture = AsrAudioCapture.create(config.sampleRate, TAG)
            if (capture == null) {
                setError("Falló la inicialización del micrófono,Por favor verifique los permisos o el dispositivo de grabación.")
                return@launch
            }
            val recorder = capture.recorder
            val bufSize = capture.bufferSize
            audioRecord = recorder

            try {
                recorder.startRecording()
                _state.update { it.copy(status = ASRStatus.Listening) }
                val buffer = ByteArray(bufSize)
                while (coroutineContext[Job]?.isActive == true) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        // 1. Calcular amplitud -> actualizar state(normalización 0-1f)
                        val amplitude = AudioAmplitude.calculateRmsAmplitude(buffer, read)
                        _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }

                        // 2. acumulado hasta pcmBuffer(bufferLock sincronizar),Al mismo tiempo, determine si se alcanza el umbral de segmentación.
                        val shouldFlush = synchronized(bufferLock) {
                            pcmBuffer.write(buffer, 0, read)
                            val elapsed = SystemClock.elapsedRealtime() - segmentStartElapsedMs
                            pcmBuffer.size() >= maxSegmentBytes || elapsed >= segmentDurationMs
                        }
                        // 3. súper umbral -> gatillo flush(de serie,No bloquea el bucle de grabación.)
                        if (shouldFlush) {
                            triggerFlush()
                        }
                    } else if (read == 0) {
                        delay(10L)
                    } else if (read < 0) {
                        Logger.w(TAG, "AudioRecord.read error: $read")
                        if (shouldReportAsrCaptureFailure(_state.value.status, coroutineContext[Job]?.isActive == true)) {
                            setError("AudioRecord error de lectura: $read")
                        }
                        break
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Error de grabación: ${e.message}")
                if (shouldReportAsrCaptureFailure(_state.value.status, coroutineContext[Job]?.isActive == true)) {
                    setError(e.message ?: "Error de grabación")
                }
            } finally {
                releaseRecorder()
            }
        }
    }

    /**
     * disparar una vez flush(de serie):Sólo se permite ejecutar un FlushJob al mismo tiempo,Evite que los resultados de último llegado primero en ser atendidos estén desordenados.
     * PCM Copiar y restablecer el búfer en [bufferLock] completado dentro de,HTTP La solicitud se ejecutó fuera de la cerradura.
     */
    private fun triggerFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch(Dispatchers.IO) {
            flushMutex.withLock {
                resultOf { flushSegment() }
                    .onError { message, throwable ->
                        val error = throwable?.message ?: message
                        Logger.w(TAG, "Error al vaciar el segmento: $error")
                        setError("La segmentación del reconocimiento de voz falló: $error")
                    }
            }
        }
    }

    /**
     * Sacar el buffer actual PCM,Convierta WAV y luego POST al paso /v1/audio/transcripciones.
     * Los resultados del reconocimiento se adjuntan a [totalTranscript] Y pase la devolución de llamada onTranscriptChange.
     */
    private suspend fun flushSegment() {
        // bufferLock dentro:copiar PCM -> restablecer buffer -> Restablecer el tiempo dividido
        val pcmCopy = synchronized(bufferLock) {
            if (pcmBuffer.size() == 0) return
            val bytes = pcmBuffer.toByteArray()
            pcmBuffer.reset()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
            bytes
        }

        // filtro de segmento más corto:PCM < 3200 bytes(100ms @ 16kHz 16bit mono)saltar,Evita los errores 400
        if (pcmCopy.size < MIN_SEGMENT_BYTES) {
            Logger.d(TAG, "Saltar descarga: PCM demasiado corto(${pcmCopy.size} bytes)")
            return
        }

        val wavBytes = PcmWavConverter.toWav(pcmCopy, config.sampleRate, channels = 1, bitsPerSample = 16)
        val text = recognizeSegment(wavBytes)
        if (!text.isNullOrBlank()) {
            // Reconocimiento exitoso:Restablecer el recuento de errores consecutivos,Agregar resultados
            consecutiveFlushFailures = 0
            totalTranscript.append(text).append(" ")
            val transcript = totalTranscript.toString().trim()
            _state.update { it.copy(transcript = transcript, errorMessage = null) }
            onTranscriptChange?.invoke(transcript)
        } else {
            // B-29: El reconocimiento falló(4xx/Regreso del fracaso final null)tiempo,Este segmento de PCM se ha eliminado y no se puede restaurar.
            //     Reparación:Escribe el segmento nuevamente al comienzo del búfer.,Reservado para el próximo reintento de fusión;Si la falla continua alcanza el límite superior, se descartará.
            consecutiveFlushFailures++
            if (consecutiveFlushFailures < MAX_CONSECUTIVE_FLUSH_FAILURES) {
                prependToBuffer(pcmCopy)
                Logger.w(TAG, "El reconocimiento falló,escribir de nuevo ${pcmCopy.size} Bytes que se volverán a intentar la próxima vez que se complete la combinación(fracasos consecutivos $consecutiveFlushFailures)")
            } else {
                Logger.w(TAG, "Fallo de reconocimiento continuo $consecutiveFlushFailures veces,descartar este segmento ${pcmCopy.size} bytes")
                consecutiveFlushFailures = 0
            }
        }
    }

    /**
     * B-29: Escriba el PCM del segmento fallido al principio del búfer,Se garantiza que se fusionará con nuevos cuadros agregados por el hilo de grabación.
     * en [bufferLock] completado dentro de,Evite la competencia de lectura y escritura con bucles de grabación.
     */
    private fun prependToBuffer(pcm: ByteArray) {
        synchronized(bufferLock) {
            val current = pcmBuffer.toByteArray()
            pcmBuffer.reset()
            pcmBuffer.write(pcm) // Primer párrafo antiguo,Reservado para reidentificación durante la próxima descarga
            pcmBuffer.write(current) // Posteriormente, empalmar nuevos fotogramas acumulados por el hilo de grabación.
        }
    }

    /**
     * Llame al punto final compatible con Step OpenAI Whisper para identificar una pieza de audio WAV.
     * Reutilice la lógica multiparte del StepAsrClient original.
     */
    private suspend fun recognizeSegment(wavBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val wavBody = wavBytes.toRequestBody("audio/wav".toMediaType())
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", wavBody)
            .addFormDataPart("model", config.model.ifBlank { config.defaultModel() })
            .apply {
                config.language?.takeIf { it.isNotBlank() }?.let { addFormDataPart("language", it) }
            }
            .build()
        val request = Request.Builder()
            .url(transcriptionEndpoint())
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(multipart)
            .build()
        var lastError: String? = null
        var retryableResponse = true
        // F-34: Desconectar y volver a conectar — red/5xx/429 Si falla, el mismo segmento de audio se retransmitirá según un retroceso exponencial.,Límite superior RECONNECT_MAX_ATTEMPTS.
        for (attempt in 0 until AsrConstants.RECONNECT_MAX_ATTEMPTS) {
            try {
                val result = client.newCall(request).execute().use { resp: Response ->
                    if (!resp.isSuccessful) {
                        lastError = "servicio de identificación HTTP ${resp.code}: ${resp.message}"
                        Logger.w(TAG, "Step ASR HTTP ${resp.code}: ${resp.message}")
                        // 4xx(especialmente 401/403/413)No tiene sentido volver a intentarlo;5xx/429 Déjelo para el próximo reintento breve.
                        if (!isRetryableAsrHttpStatus(resp.code)) {
                            retryableResponse = false
                            return@use null
                        }
                        null
                    } else {
                        parseTranscriptionResponse(resp.body.string())
                    }
                }
                if (!result.isNullOrBlank()) return@withContext result
                if (!retryableResponse) break
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                lastError = e.message ?: "La conexión de red falló"
                Logger.w(TAG, "Step ASR Solicitud fallida(attempt=${attempt + 1}): ${e.message}")
            }
            if (attempt + 1 < AsrConstants.RECONNECT_MAX_ATTEMPTS) {
                delay(AsrConstants.HTTP_RETRY_BACKOFF_MS * (attempt + 1))
            }
        }
        lastError?.let { setError("Error en el reconocimiento de voz: $it") }
        null
    }

    private fun transcriptionEndpoint(): String {
        val base = baseUrl.trimEnd('/')
        return if (base.endsWith("/audio/transcriptions")) {
            base
        } else if (base.endsWith("/v1")) {
            "$base/audio/transcriptions"
        } else {
            "$base/v1/audio/transcriptions"
        }
    }

    /** Análisis de respuestas de transcripción compatibles con OpenAI,Extraer campo de texto. */
    private fun parseTranscriptionResponse(body: String): String? {
        val fromJson = resultOf {
            val obj = Json.parseToJsonElement(body) as? JsonObject
            (obj?.get("text") as? JsonPrimitive)?.content
        }.getOrNull()
        // Algunas implementaciones de Paso/tránsito se ignoran response_format,Devuelve texto sin formato directamente.
        return fromJson?.takeIf { it.isNotBlank() } ?: body.takeIf { it.isNotBlank() }
    }

    override fun stop() {
        if (!_state.value.isRecording) return
        _state.update { it.copy(status = ASRStatus.Stopping) }
        recordJob?.cancel()
        releaseRecorder()
        // La última descarga: llame directamente después de que se complete el trabajo de descarga actual flushSegment(Omitir la verificación de concurrencia de triggerFlush)
        scope.launch(Dispatchers.IO) {
            try {
                flushJob?.join()
                flushMutex.withLock { flushSegment() }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "La descarga final falló: ${e.message}")
                setError(e.message ?: "Step ASR La descarga final falló")
            } finally {
                _state.update { it.afterAsrStop() }
            }
        }
    }

    override fun dispose() {
        if (!isDisposed.compareAndSet(false, true)) return
        recordJob?.cancel()
        flushJob?.cancel()
        releaseRecorder()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        _state.update { it.copy(status = ASRStatus.Idle, amplitudes = emptyList()) }
    }

    private fun setError(message: String) {
        recordJob?.cancel()
        releaseRecorder()
        _state.update { it.copy(status = ASRStatus.Error, errorMessage = message) }
    }

    private fun releaseRecorder() {
        recordJob = null
        resultOf { audioRecord?.stop() }
        resultOf { audioRecord?.release() }
        audioRecord = null
    }

    companion object {
        private const val TAG = "StepAsrController"
        private const val DEFAULT_BASE_URL = "https://api.stepfun.com"

        /** Umbral de tiempo fraccionado:30 Active la descarga una vez por segundo. */
        private const val SEGMENT_DURATION_MS = 30_000L

        /** Umbral de bytes de fragmento:6MB gatillo flush(plazo de entrega,Evite segmentos individuales que sean demasiado grandes). */
        private const val MAX_SEGMENT_BYTES = 6 * 1024 * 1024

        /** Número mínimo de bytes en el segmento:16kHz/16bit/mono abajo 100ms = 3200 bytes,Saltos más cortos que esto evitan 400. */
        private const val MIN_SEGMENT_BYTES = 3200

        // B-29: Límite superior de falla de reconocimiento continuo de un solo segmento,Descartar el segmento después de alcanzar,Evite que la reescritura infinita provoque una acumulación de búfer.3 Jitter de la red de cobertura+2subtransitorio.
        private const val MAX_CONSECUTIVE_FLUSH_FAILURES = 3
    }
}

/**
 * PCM -> WAV Herramientas de embalaje.
 *
 * WAV Estructura de archivos(44 Encabezado de bytes + datos PCM):
 *  - RIFF chunk descriptor(12 bytes): "RIFF" + tamaño de archivo + "WAVE"
 *  - fmt sub-chunk(24 bytes): "fmt " + Tamaño de subbloque + formato de audio + canales + frecuencia de muestreo + velocidad de bytes + alineación de bloque + profundidad de bits
 *  - data sub-chunk(8 bytes): "data" + Tamaño de datos
 *  - PCM datos
 */
internal object PcmWavConverter {
    fun toWav(pcm: ByteArray, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val chunkSize = 36 + dataSize

        val out = ByteArrayOutputStream(44 + dataSize)
        val dos = java.io.DataOutputStream(out)

        // RIFF cabeza
        dos.writeBytes("RIFF")
        dos.writeIntLittleEndian(chunkSize)
        dos.writeBytes("WAVE")

        // fmt subbloque
        dos.writeBytes("fmt ")
        dos.writeIntLittleEndian(16) // PCM Formatear el tamaño del subbloque
        dos.writeShortLittleEndian(1) // formato de audio = 1 (PCM)
        dos.writeShortLittleEndian(channels)
        dos.writeIntLittleEndian(sampleRate)
        dos.writeIntLittleEndian(byteRate)
        dos.writeShortLittleEndian(blockAlign)
        dos.writeShortLittleEndian(bitsPerSample)

        // data subbloque
        dos.writeBytes("data")
        dos.writeIntLittleEndian(dataSize)
        dos.write(pcm)

        return out.toByteArray()
    }

    private fun java.io.DataOutputStream.writeIntLittleEndian(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
        write((v shr 16) and 0xFF)
        write((v shr 24) and 0xFF)
    }

    private fun java.io.DataOutputStream.writeShortLittleEndian(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
    }
}
