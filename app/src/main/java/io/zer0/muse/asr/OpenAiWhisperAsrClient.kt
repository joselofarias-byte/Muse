package io.zer0.muse.asr

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioRecord
import android.os.SystemClock
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
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
 * Compatible con OpenAI Whisper universal ASR Controller(con este proyecto [StepAsrController] Arquitectura consistente).
 *
 * Se adapta a cualquier oferta `POST {baseUrl}/audio/transcriptions` Proveedor de servicios de punto final:
 *  - OpenAI oficial(https://api.openai.com/v1)
 *  - Groq / DeepInfra / Together y otras plataformas compatibles
 *  - Agnes Esperando estación de transferencia(El usuario está en [AsrConfig.baseUrl] Personalizar)
 *
 * OpenAI proporciona oficialmente WebSocket de transmisión real(ver [OpenAiRealtimeAsrController]),
 * La mayoría de las estaciones de transferencia solo admiten la carga de archivos de varias partes,Por lo tanto, este Responsable adopta [StepAsrController] lo mismo
 * Estrategia por lotes de puesta en escena:
 *  - PCM acumulado en el buffer durante la grabación
 *  - por umbral de tiempo(Predeterminado 30s)o umbral de bytes(6MB)gatillo flush
 *  - cada vez flush:PCM -> WAV -> HTTP multipart POST -> Analizar respuesta -> devolución de llamada onTranscriptChange
 *  - [stop] hazlo por última vez flush,Espere a que se complete el trabajo de descarga antes de cortar. Idle
 *  - FlushJob en serie evita el desorden simultáneo
 *  - Opcional [AsrConfig.vadEnabled] Habilitar local VAD,Se activa automáticamente cuando el silencio excede el umbral flush(Simular segmentación de oraciones)
 *
 * Solicitar(multipart/form-data):
 *  - file: archivo de audio(wav Formato,PCM 16-bit)
 *  - model: Nombre del modelo(Predeterminado whisper-1)
 *  - language: Idioma(Opcional,ISO-639-1 Como por ejemplo zh/en/ja)
 *  - prompt: palabras calientes/sugerencias contextuales(Opcional,Whisper Utilice esto para mejorar la tasa de reconocimiento de sustantivos propios)
 *  - response_format: text(Predeterminado,Devolver texto sin formato directamente)o json(Contiene campo de texto)
 *
 * respuesta(JSON):
 *  - { "text": "Reconocer texto" }
 * o response_format=text Devuelve texto sin formato directamente.
 *
 * @param config ASR Configuración(debe tener apiKey;baseUrl Usar valor predeterminado cuando esté vacío https://api.openai.com/v1)
 * @param sharedClient Compartir inyectables OkHttpClient
 */
class OpenAiWhisperAsrController(
    private val config: AsrConfig,
    sharedClient: OkHttpClient? = null,
    // v2.7: Alinear con DashScope/Controlador en tiempo real——Recursos de redacción de errores de cara al usuario.
    // Parámetros requeridos: Si no lo haces, te quedarás con las manos vacías. Devolver una cadena vacía tragará el mensaje de error directamente, lo cual es peor que codificar.
    private val appContext: Context,
) : ASRController {

    private val json = Json { ignoreUnknownKeys = true }
    private val ownsClient: Boolean = sharedClient == null
    private val client: OkHttpClient = sharedClient ?: io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
        .connectTimeout(AsrConstants.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(AsrConstants.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

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
    private var consecutiveFlushFailures = 0

    // Umbral de segmentación:30 segundos o 6 MB, lo que ocurra primero
    private val segmentDurationMs = SEGMENT_DURATION_MS
    private val maxSegmentBytes = MAX_SEGMENT_BYTES
    private var segmentStartElapsedMs = 0L

    // Local opcional VAD(Silenciar automáticamente cuando supera el umbral flush,Simular segmentación de oraciones)
    private var vadDetector: VadDetector? = null

    override fun start(onTranscriptChange: ((String) -> Unit)?) {
        if (isDisposed.get() || _state.value.isRecording) return
        if (config.apiKey.isBlank()) {
            Logger.w(TAG, "OpenAI Whisper ASR No configurado apiKey")
            _state.update { it.copy(status = ASRStatus.Error, errorMessage = appContext.getString(R.string.asr_error_no_apikey)) }
            return
        }
        this.onTranscriptChange = onTranscriptChange
        totalTranscript.clear()
        synchronized(bufferLock) {
            pcmBuffer.reset()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
        }
        flushJob = null
        consecutiveFlushFailures = 0
        // VAD inicialización(Solo habilitado si config.vadEnabled)
        vadDetector = if (config.vadEnabled) {
            VadDetector(
                threshold = config.vadThreshold,
                silenceDurationMs = config.vadSilenceDurationMs,
            )
        } else {
            null
        }
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
        recordJob = scope.launch(Dispatchers.IO) {
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

                        // 2. alimentar VAD(Si está habilitado),El silencio se fuerza cuando se supera el umbral. flush(Simular segmentación de oraciones)
                        val vadTriggered = vadDetector?.processFrame(buffer, read, amplitude) == true

                        // 3. acumulado hasta pcmBuffer(bufferLock sincronizar),Determinar el umbral de segmentación
                        val shouldFlush = synchronized(bufferLock) {
                            pcmBuffer.write(buffer, 0, read)
                            val elapsed = SystemClock.elapsedRealtime() - segmentStartElapsedMs
                            // VAD gatillo:Sólo si el buffer tiene suficientes datos flush,Evite los párrafos cortos
                            val vadFlush = vadTriggered && pcmBuffer.size() >= MIN_VAD_FLUSH_BYTES
                            pcmBuffer.size() >= maxSegmentBytes || elapsed >= segmentDurationMs || vadFlush
                        }
                        // 4. súper umbral -> gatillo flush(de serie,No bloquea el bucle de grabación.)
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
     * Sacar el buffer actual PCM,Convierta a WAV y luego POST a {baseUrl}/audio/transcriptions.
     * Los resultados del reconocimiento se adjuntan a [totalTranscript] Y pase la devolución de llamada onTranscriptChange.
     */
    private suspend fun flushSegment() {
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
            consecutiveFlushFailures = 0
            totalTranscript.append(text).append(" ")
            val transcript = totalTranscript.toString().trim()
            _state.update { it.copy(transcript = transcript, errorMessage = null) }
            onTranscriptChange?.invoke(transcript)
        } else {
            consecutiveFlushFailures++
            if (consecutiveFlushFailures < MAX_CONSECUTIVE_FLUSH_FAILURES) {
                prependToBuffer(pcmCopy)
                Logger.w(TAG, "El reconocimiento falló,escribir de nuevo ${pcmCopy.size} Bytes que se volverán a intentar la próxima vez(fracasos consecutivos $consecutiveFlushFailures)")
            } else {
                Logger.w(TAG, "Fallo de reconocimiento continuo $consecutiveFlushFailures veces,descartar este segmento ${pcmCopy.size} bytes")
                consecutiveFlushFailures = 0
            }
        }
    }

    private fun prependToBuffer(pcm: ByteArray) {
        synchronized(bufferLock) {
            val current = pcmBuffer.toByteArray()
            pcmBuffer.reset()
            pcmBuffer.write(pcm)
            pcmBuffer.write(current)
        }
    }

    /**
     * Llame al punto final compatible con OpenAI Whisper para identificar una pieza de audio WAV.
     *
     * multipart form campo:
     *  - file: wav Audio
     *  - model: Nombre del modelo(Predeterminado whisper-1)
     *  - language: Idioma(Opcional)
     *  - prompt: Empalme de palabras calientes(Opcional,Whisper Utilice esto para mejorar la tasa de reconocimiento de sustantivos propios)
     *  - response_format: json(Fácil de analizar)
     */
    private suspend fun recognizeSegment(wavBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val wavBody = wavBytes.toRequestBody("audio/wav".toMediaType())
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", wavBody)
            .addFormDataPart("model", config.model.ifBlank { config.defaultModel() })
            .addFormDataPart("response_format", "json")
            .apply {
                config.language?.takeIf { it.isNotBlank() }?.let { addFormDataPart("language", it) }
                if (config.hotwords.isNotEmpty()) {
                    val prompt = config.hotwords.joinToString(", ")
                    if (prompt.isNotBlank()) addFormDataPart("prompt", prompt)
                }
            }
            .build()
        val base = config.baseUrl.ifBlank { config.defaultBaseUrl().ifBlank { DEFAULT_BASE_URL } }
        val request = Request.Builder()
            .url(transcriptionEndpoint(base))
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
                        Logger.w(TAG, "Whisper ASR HTTP ${resp.code}: ${resp.message}")
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
                Logger.w(TAG, "Whisper ASR Solicitud fallida(attempt=${attempt + 1}): ${e.message}")
            }
            if (attempt + 1 < AsrConstants.RECONNECT_MAX_ATTEMPTS) {
                delay(AsrConstants.HTTP_RETRY_BACKOFF_MS * (attempt + 1))
            }
        }
        lastError?.let { setError("Error en el reconocimiento de voz: $it") }
        null
    }

    private fun transcriptionEndpoint(baseUrl: String): String {
        val base = baseUrl.trimEnd('/')
        return if (base.endsWith("/audio/transcriptions")) {
            base
        } else if (base.endsWith("/v1")) {
            "$base/audio/transcriptions"
        } else {
            "$base/v1/audio/transcriptions"
        }
    }

    /**
     * Analizar la respuesta de transcripción,Extraer campo de texto.
     * - response_format=json: { "text": "..." }
     * - response_format=text: Texto directamente sin formato(Compatibilidad alternativa)
     */
    private fun parseTranscriptionResponse(body: String): String? {
        // Pruebe primero el análisis JSON
        val fromJson = resultOf {
            val obj = Json.parseToJsonElement(body) as? JsonObject
            (obj?.get("text") as? JsonPrimitive)?.content
        }.getOrNull()
        if (!fromJson.isNullOrBlank()) return fromJson
        // revertir:La respuesta puede ser directamente texto plano.(response_format=text)
        return body.takeIf { it.isNotBlank() }
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
                setError(e.message ?: "Whisper ASR La descarga final falló")
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
        if (ownsClient) {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
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
        private const val TAG = "OpenAiWhisperAsrController"
        private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

        /** Umbral de tiempo fraccionado:30 Active la descarga una vez por segundo. */
        private const val SEGMENT_DURATION_MS = 30_000L

        /** Umbral de bytes de fragmento:6MB gatillo flush(plazo de entrega,Evite segmentos individuales que sean demasiado grandes). */
        private const val MAX_SEGMENT_BYTES = 6 * 1024 * 1024

        /** Número mínimo de bytes en el segmento:16kHz/16bit/mono abajo 100ms = 3200 bytes,Saltos más cortos que esto evitan 400. */
        private const val MIN_SEGMENT_BYTES = 3200

        /** VAD El número mínimo de bytes en el segmento que activa el vaciado.:Evite los párrafos cortos(500ms = 16000 bytes). */
        private const val MIN_VAD_FLUSH_BYTES = 16000
        private const val MAX_CONSECUTIVE_FLUSH_FAILURES = 3
    }
}
