package io.zer0.muse.asr

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioRecord
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Controlador de reconocimiento de voz en streaming Alibaba Cloud DashScope Paraformer.
 *
 * con versión antigua [AsrClient] Una sola vez reconoce diferentes,Este controlador implementa [ASRController] interfaz,
 * Graba, transmite y vuelve a llamar al mismo tiempo,Admite visualización en tiempo real de resultados intermedios.
 *
 * Acuerdo(Siga usando DashScope nativo Paraformer,sin cambios):
 *  1. crear WebSocket(wss://dashscope.aliyuncs.com/api-ws/v1/inference)
 *  2. enviar run-task,recibir task-started
 *  3. empezar AudioRecord(VOICE_COMMUNICATION)Colección de bucles PCM
 *  4. por cuadro:Calcular la amplitud RMS → actualizar state.amplitudes;Base64/envío binario → WebSocket(Con control de contrapresión)
 *  5. onMessage analizar result-generated:sentence_end=true acumular,sentence_end=false Reemplazar resultados intermedios por frase_id
 *  6. stop():enviar finish-task,espera task-finished(5s Corte forzado después del tiempo de espera Idle)
 *  7. dispose():Cancelar corrutina,cerrar WebSocket,liberar AudioRecord
 *
 * Flujo de estado:Idle → Connecting → Listening → Stopping → Idle(o Error)
 *
 * @param config ASR Configuración(Debe tener apiKey y model)
 * @param sharedClient Compartir inyectables OkHttpClient(El ciclo de vida lo gestiona la persona que llama.)
 * @param appContext Solicitud Context(Se utiliza para analizar el texto de error visible para el usuario en cadenas localizadas)
 */
class DashScopeAsrController(
    private val config: AsrConfig,
    // Compartir inyectables OkHttpClient,Evite crear nuevos independientes client
    sharedClient: OkHttpClient? = null,
    // CONS-05: inyectar Application Context,Se utiliza para analizar el texto de error visible para el usuario en cadenas localizadas
    private val appContext: Context,
) : ASRController {

    private val json = Json { ignoreUnknownKeys = true }

    // Priorizar la reutilización de acciones inyectadas client;Autoconstruido cuando no se inyecta(ownsClient=true,dispose Sólo entonces shutdown)
    private val ownsClient: Boolean = sharedClient == null
    private val client: OkHttpClient = sharedClient ?: io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
        .connectTimeout(AsrConstants.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(AsrConstants.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ASRState(isAvailable = config.apiKey.isNotBlank()))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var webSocket: WebSocket? = null
    private var session: DashScopeSession? = null
    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private var onTranscriptChange: ((String) -> Unit)? = null

    @Volatile private var currentTaskId: String? = null

    // resultado final acumulado(sentence_end=true el texto de)+ resultado intermedio actual(sentence_id → text)
    private val completedTranscripts = StringBuilder()
    private val partialTranscripts = ConcurrentHashMap<String, String>()

    // Local opcional VAD(El usuario lo habilita explícitamente en la configuración.;DashScope El servidor viene con VAD,Generalmente no hay locales)
    private var vadDetector: VadDetector? = null

    // ── Phase 3: Campos relacionados con la desconexión y la reconexión(Alineación [OpenAiRealtimeAsrController] implementación de reconexión) ──
    /** Número de reconexiones activadas(Borrado después de una reconexión exitosa). */
    private var reconnectAttempt = 0

    /** Tramas de audio PCM almacenadas en búfer durante la reconexión(Reedición por orden de incorporación al equipo.). */
    private val audioBuffer = ArrayDeque<ByteArray>()

    /** [audioBuffer] cerrar:La puesta en cola del hilo de grabación y la reconexión son mutuamente excluyentes. */
    private val audioBufferLock = Any()

    /** [audioBuffer] Número acumulado actual de bytes,Se utiliza para juzgar el límite superior. */
    @Volatile private var audioBufferBytes = 0

    /** La rutina de reconexión de retroceso actualmente programada;stop/dispose/start Cancelar en el momento. */
    private var reconnectJob: Job? = null

    /** dispose logotipo,Evite la reconexión después de desecharlo. */
    private val isDisposed = AtomicBoolean(false)

    override fun start(onTranscriptChange: ((String) -> Unit)?) {
        // dispose Se debe mantener el estado final; de lo contrario, el alcance se canceló pero el inicio aún cambiará el estado a Conectando, lo que provocará que la interfaz de usuario se conecte de forma permanente y falsa.
        if (isDisposed.get()) return
        // Rechazar duplicados solo en estado de escucha start;Connecting/Stopping El estado de transición permite que continúe el nuevo comienzo.
        if (_state.value.status == ASRStatus.Listening) {
            Logger.w(TAG, "Ya grabando,ignorar duplicados start")
            return
        }
        if (config.apiKey.isBlank()) {
            Logger.w(TAG, "DashScope ASR No configurado apiKey")
            _state.update {
                it.copy(status = ASRStatus.Error, errorMessage = appContext.getString(R.string.asr_error_no_apikey))
            } // CONS-05: Migración de chino codificado a recursos de cadena
            return
        }
        // Phase 3: Reconectarse durante la reconexión start → Cancelar la reconexión y liberar recursos de grabación,Tomar una nueva conexión(Evite la superposición de dos conjuntos de grabaciones.)
        if (_state.value.status == ASRStatus.Reconnecting) {
            cancelReconnect()
            releaseAudioRecord()
        }
        this.onTranscriptChange = onTranscriptChange
        completedTranscripts.setLength(0)
        partialTranscripts.clear()
        reconnectAttempt = 0
        clearAudioBuffer()
        vadDetector = if (config.vadEnabled) {
            VadDetector(
                threshold = config.vadThreshold,
                silenceDurationMs = config.vadSilenceDurationMs,
            )
        } else {
            null
        }
        _state.update {
            it.copy(status = ASRStatus.Connecting, transcript = "", errorMessage = null, amplitudes = emptyList())
        }
        scope.launch { connectAndRecord() }
    }

    /** Establezca WebSocket, envíe la tarea de ejecución, espere a que se inicie la tarea e inicie el hilo de grabación(primera ruta de conexión). */
    private suspend fun connectAndRecord() {
        if (establishConnection(isInitial = true)) {
            startRecordingInternal()
        }
    }

    /**
     * Phase 3: Establecer una sesión de WebSocket(Enviar tarea de ejecución + etc. task-started).
     *
     * @param isInitial true=Conexión por primera vez(No cortar Error,Sin reconexión);false=Desconectar y volver a conectar
     *   (falló por [attemptReconnect] continuar retirándose)
     * @return true=Recibido task-started,Conexión disponible
     */
    private suspend fun establishConnection(isInitial: Boolean): Boolean {
        val taskId = java.util.UUID.randomUUID().toString()
        currentTaskId = taskId

        // Crear sesión:onMessage Ingrese después del análisis channel(Para esperar a que llegue Evento task-started),
        // Distribuido a onEvent(Seguimiento monitoreo continuo result-generated / task-finished / task-failed)
        val streamSession = DashScopeSession(taskId, json) { event ->
            // onMessage Activado en el hilo OkHttp,El envío al ámbito principal garantiza que handleEvent se ejecute en el hilo principal
            scope.launch { handleEvent(event) }
        }
        // Phase 3: Desconectar devolución de llamada — También enviado a Main scope,Serie con actualizaciones de estado.
        streamSession.onDisconnected = { reason ->
            scope.launch { onWebSocketDisconnected(reason) }
        }
        session = streamSession

        // L-ASR3: Prioridad de punto final config.asrEndpoint,Volver a los valores predeterminados integrados cuando está vacío
        val endpoint = config.asrEndpoint.ifBlank { ENDPOINT }
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("X-DashScope-DataInspection", "enable")
            .build()

        val ws = client.newWebSocket(request, streamSession)
        webSocket = ws

        return try {
            // H-ASR1: Enviar directamente run-task,Espera un poco más task-started
            val runTask = buildRunTaskMessage(taskId, config.sampleRate)
            if (!ws.send(runTask)) {
                Logger.w(TAG, "Error al enviar la tarea de ejecución")
                cleanupConnection()
                if (isInitial) {
                    _state.update {
                        it.copy(status = ASRStatus.Error, errorMessage = appContext.getString(R.string.asr_error_send_run_task_failed))
                    } // CONS-05: Migración de chino codificado a recursos de cadena
                }
                return false
            }

            // espera task-started(run-task Confirmación posterior al servidor)
            val started = streamSession.waitForEvent("task-started", TIMEOUT_EVENT_MS)
            if (started == null) {
                Logger.w(TAG, "run-task no recibido después task-started")
                cleanupConnection()
                if (isInitial) {
                    _state.update {
                        it.copy(status = ASRStatus.Error, errorMessage = appContext.getString(R.string.asr_error_no_task_started))
                    } // CONS-05: Migración de chino codificado a recursos de cadena
                }
                return false
            }

            if (isInitial) {
                // P2-13: Durante el apretón de manos, el usuario puede tener stop/dispose — Dejar de iniciar la grabación cuando el estado ya no sea Conectando,
                // De lo contrario, la intención de parada del usuario se sobrescribirá y continuará la escucha y la grabación.
                if (_state.value.status != ASRStatus.Connecting) {
                    Logger.d(TAG, "Apretón de manos completado pero el estado cambió(${_state.value.status}),Dejar de iniciar la grabación")
                    cleanupConnection()
                    return false
                }
                // Recibido task-started,Cambie a Escuchar y comience a grabar
                _state.update { it.copy(status = ASRStatus.Listening) }
            } else {
                if (_state.value.status != ASRStatus.Connecting) {
                    Logger.d(TAG, "Reconexión completada pero el estado ha cambiado(${_state.value.status}),renunciar a la recuperación")
                    return false
                }
                // Reconexión exitosa:Restablecer el recuento de retrocesos,Cambio de estado hacia atrás Listening,Reedición de audio almacenado en buffer
                onReconnected()
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "establishConnection anormal: ${e.message}")
            cleanupConnection()
            if (isInitial) {
                _state.update {
                    it.copy(
                        status = ASRStatus.Error,
                        errorMessage = e.message ?: appContext.getString(R.string.asr_error_connection_exception),
                    )
                } // CONS-05: Migración de chino codificado a recursos de cadena
            }
            false
        }
    }

    /** Iniciar el hilo de grabación AudioRecord(Colección de bucles + carga). */
    @SuppressLint("MissingPermission")
    private fun startRecordingInternal() {
        val capture = AsrAudioCapture.create(config.sampleRate, TAG)
        if (capture == null) {
            _state.update {
                it.copy(status = ASRStatus.Error, errorMessage = appContext.getString(R.string.asr_error_mic_init_failed))
            } // CONS-05: Migración de chino codificado a recursos de cadena
            return
        }
        val record = capture.recorder
        audioRecord = record
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "AudioRecord El inicio falló: ${e.message}", e)
            record.release()
            audioRecord = null
            _state.update {
                it.copy(status = ASRStatus.Error, errorMessage = appContext.getString(R.string.asr_error_recording_start_failed))
            } // CONS-05: Migración de chino codificado a recursos de cadena
            return
        }

        // hilo de grabación:Lectura en bucle PCM → Calcular amplitud → enviar WebSocket(Con control de contrapresión)
        // Phase 3: Reconnecting Continuar grabando durante,Primer búfer del cuadro de audio,Reemisión después de una reconexión exitosa
        recordJob = scope.launch(Dispatchers.IO) {
            val chunk = ByteArray(AUDIO_CHUNK_BYTES)
            try {
                while (isActive && _state.value.isRecording) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        // 1. Calcular la amplitud RMS → actualizar state.amplitudes(normalización 0-1f)
                        val amp = AudioAmplitude.calculateRmsAmplitude(chunk, read)
                        _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amp)) }

                        // 2. locales VAD(Si está habilitado):Gatillo activo silencioso de ultraumbral stop
                        //    DashScope El servidor viene con VAD(sentence_end Segmentación automática de oraciones),El VAD local solo se utiliza para detener la grabación automáticamente
                        if (vadDetector?.processFrame(chunk, read, amp) == true) {
                            Logger.d(TAG, "Activador VAD local,Detenerse activamente")
                            scope.launch { stop() }
                            break
                        }

                        // 3. Listening Enviar normalmente durante el período.;Otros estados de transición(Reconnecting / Se está estableciendo la reconexión)Buffer primero,
                        //    Evite enviar PCM a un nuevo servidor antes de que se complete el protocolo de enlace de tarea de ejecución/inicio de tarea WebSocket
                        if (_state.value.status == ASRStatus.Listening) {
                            sendAudioFrame(chunk, read)
                        } else {
                            bufferAudioFrame(chunk, read)
                        }
                    } else if (read == 0) {
                        delay(10L)
                    } else if (read < 0) {
                        Logger.w(TAG, "AudioRecord.read error: $read")
                        if (shouldReportAsrCaptureFailure(_state.value.status, coroutineContext[Job]?.isActive == true)) {
                            failAsr(appContext.getString(R.string.asr_error_recording_read_failed, read))
                        }
                        break
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (shouldReportAsrCaptureFailure(_state.value.status, coroutineContext[Job]?.isActive == true)) {
                    Logger.w(TAG, "Anomalía en el bucle de grabación: ${error.message}", error)
                    failAsr(error.message ?: appContext.getString(R.string.asr_error_recording_start_failed))
                }
            }
        }
    }

    /** Phase 3: Listening Enviar una trama en el estado PCM(Pérdida de marco debido a una contrapresión excesiva,Evitar la acumulación de memoria.). */
    private fun sendAudioFrame(chunk: ByteArray, read: Int) {
        val ws = webSocket
        if (ws != null && ws.queueSize() < MAX_QUEUE_BYTES) {
            // Construido con okio.Buffer ByteString(Transferencia directa PCM binaria,DashScope Soporte de protocolo)
            val byteString = okio.Buffer().apply { write(chunk, 0, read) }.readByteString()
            if (!ws.send(byteString)) {
                Logger.w(TAG, "No se pudo enviar el fragmento de audio,Activar reconexión")
                scope.launch { onWebSocketDisconnected("No se pudo enviar el fragmento de audio") }
            }
        } else {
            Logger.d(TAG, "La contrapresión excede el límite,marcos caídos queueSize=${ws?.queueSize()}")
        }
    }

    /** Phase 3: Reconnecting Coloque un marco de PCM en el búfer de cola(Evitar la pérdida de fotogramas más allá del límite superior OOM). */
    private fun bufferAudioFrame(chunk: ByteArray, read: Int) {
        val frame = chunk.copyOf(read)
        synchronized(audioBufferLock) {
            if (audioBufferBytes + frame.size <= MAX_AUDIO_BUFFER_BYTES) {
                audioBuffer.addLast(frame)
                audioBufferBytes += frame.size
            } else {
                Logger.w(TAG, "El buffer de audio está lleno durante la reconexión($audioBufferBytes bytes),marcos caídos")
            }
        }
    }

    /** Manejo de eventos WebSocket(Ejecutado en el hilo principal). */
    private fun handleEvent(event: JsonObject) {
        if (isDisposed.get() || (_state.value.status == ASRStatus.Error || _state.value.status == ASRStatus.Idle)) return
        val eventType = event.optString("event")
        when (eventType) {
            "result-generated" -> {
                val sentence = event.optObject("payload")?.optObject("output")?.optObject("sentence")
                val text = sentence?.optString("text") ?: ""
                val isFinal = sentence?.optBool("sentence_end") ?: false
                val sentenceId = sentence?.optString("sentence_id") ?: "_partial"
                if (text.isNotEmpty()) {
                    if (isFinal) {
                        // final de la frase:acumulado hasta completedTranscripts,Borrar los resultados intermedios correspondientes
                        completedTranscripts.append(text).append(" ")
                        partialTranscripts.remove(sentenceId)
                    } else {
                        // resultado intermedio:Reemplazar por id_oración(Los resultados intermedios de la misma frase se actualizarán repetidamente.)
                        partialTranscripts[sentenceId] = text
                    }
                    // empalme completed + partial,Devolución de llamada al hilo principal
                    val full = buildTranscript()
                    _state.update { it.copy(transcript = full) }
                    onTranscriptChange?.invoke(full)
                }
            }
            "task-finished" -> {
                // Solo iniciado por el usuario stop()(El estado ha sido establecido. Stopping)tiempo,task-finished Esta es la señal final de entrada en esta sección.
                // El servidor termina solo(mudo por mucho tiempo/Tiempo de espera de la sesión)No significa que el usuario haya terminado de hablar.:Sólo el estado de retorno está aquí.,
                // Reiniciar la grabación desde arriba,No se envía ningún texto al modelo.
                val userStopped = _state.value.status == ASRStatus.Stopping
                Logger.d(TAG, "recibido task-finished,Fin del reconocimiento | userStopped=$userStopped")
                val full = buildTranscript()
                _state.update { it.copy(status = ASRStatus.Idle, transcript = full, amplitudes = emptyList()) }
                onTranscriptChange?.invoke(full)
                cancelReconnect()
                clearAudioBuffer()
                cleanupConnection()
                releaseAudioRecord()
            }
            "task-failed" -> {
                val errMsg = (event["header"] as? JsonObject)?.let {
                    (it["error_message"] as? JsonPrimitive)?.content
                } ?: event.toString()
                Logger.w(TAG, "DashScope ASR Tarea fallida: $errMsg")
                failAsr(errMsg)
            }
            "task-started" -> {
                // Ya manejado en waitForEvent de connectAndRecord,ignorar aquí
            }
            else -> { /* Ignorar eventos desconocidos */ }
        }
    }

    /** empalme completedTranscripts + partialTranscripts.values,separados por espacios. */
    private fun buildTranscript(): String {
        val parts = mutableListOf<String>()
        if (completedTranscripts.isNotEmpty()) {
            val completed = completedTranscripts.toString().trim()
            if (completed.isNotEmpty()) parts.add(completed)
        }
        partialTranscripts.values.forEach { if (it.isNotBlank()) parts.add(it) }
        return parts.joinToString(" ").trim()
    }

    // ── Phase 3: Desconectar y volver a conectar(retroceso exponencial,Alinear la implementación de OpenAiRealtimeAsrController) ──

    /**
     * WebSocket Desconectar devolución de llamada(por [DashScopeSession.onDisconnected] Distribuido a Main scope).
     *
     * Toma de decisiones [resolveAsrDisconnectAction]:
     *  - dispose / Estado final/El usuario se está deteniendo/Conectándose por primera vez → ignorar;
     *  - Listening(o reconectarse) → Reconexión de retroceso exponencial;
     *  - Reintentos agotados → Cortar errores y liberar recursos de grabación,Se adjunta una copia de degradación visible para el usuario.
     */
    private fun onWebSocketDisconnected(reason: String) {
        when (
            resolveAsrDisconnectAction(
                status = _state.value.status,
                isDisposed = isDisposed.get(),
                reconnectAttempt = reconnectAttempt,
            )
        ) {
            AsrDisconnectAction.IGNORE -> {
                Logger.d(TAG, "Ignorar las desconexiones de WebSocket(Estado=${_state.value.status}): $reason")
            }
            AsrDisconnectAction.GIVE_UP -> {
                giveUpReconnect(reason)
            }
            AsrDisconnectAction.RECONNECT -> {
                Logger.w(TAG, "WebSocket Desconectar,Activar reconexión: $reason (attempt=$reconnectAttempt)")
                scheduleReconnect(reason)
            }
        }
    }

    /**
     * Programe la próxima reconexión exponencial:500ms → 1s → 2s([DashScopeReconnectPolicy]).
     *
     * Durante el período de retiro, el estado es [ASRStatus.Reconnecting],El hilo de grabación continúa recopilando y almacenando fotogramas de audio.
     */
    private fun scheduleReconnect(reason: String) {
        if (isDisposed.get()) return
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            giveUpReconnect(reason)
            return
        }
        // Ya se está programando una tarea de reconexión/ejecutar(Como onFailure + onClosing doble devolución de llamada, falla de envío continuo)Ignorar la programación duplicada cuando
        if (reconnectJob?.isActive == true) {
            Logger.d(TAG, "La reconexión está en progreso,Ignorar horarios duplicados: $reason")
            return
        }
        val attemptIndex = reconnectAttempt
        val attemptNumber = attemptIndex + 1
        val delayMs = DashScopeReconnectPolicy.delayForAttempt(attemptIndex)
        Logger.w(TAG, "Programación No. $attemptNumber/$MAX_RECONNECT_ATTEMPTS reconexión,${delayMs}ms Después de la ejecución($reason)")
        _state.update {
            it.copy(
                status = ASRStatus.Reconnecting,
                errorMessage = appContext.getString(
                    R.string.asr_reconnecting_message,
                    attemptNumber,
                    MAX_RECONNECT_ATTEMPTS,
                ), // CONS-05: Migración de chino codificado a recursos de cadena
            )
        }
        reconnectJob = scope.launch {
            awaitReconnectBackoff(attemptIndex)
            attemptReconnect()
        }
        reconnectAttempt++
    }

    /** Realizar un intento de reconexión(Sólo mientras aún nos estamos reconectando;Continuar retrocediendo después del fracaso.,Degradado si está agotado). */
    private suspend fun attemptReconnect() {
        if (isDisposed.get()) return
        if (_state.value.status != ASRStatus.Reconnecting) {
            Logger.d(TAG, "Cambios de estado durante el retiro(${_state.value.status}),Cancelar la reconexión")
            return
        }
        // Cambiar temporalmente a Conectando indica que se está estableciendo una conexión.(Retroceso completado);La desconexión en este estado la maneja el propio establishmentConnection.
        _state.update { it.copy(status = ASRStatus.Connecting) }
        if (!establishConnection(isInitial = false)) {
            Logger.w(TAG, "Error de reconexión(attempt=$reconnectAttempt),continuar retirándose")
            scheduleReconnect("El intento de reconexión falló")
        }
    }

    /** Reconexión exitosa:Restablecer el recuento de retrocesos,El estado vuelve a Escuchar y reenvía el último audio almacenado en el buffer. */
    private fun onReconnected() {
        // protección de carrera:Durante el protocolo de enlace de reconexión, el usuario puede tener stop/dispose,Cancelar la recuperación cuando el estado ya no sea Conectando
        if (_state.value.status != ASRStatus.Connecting) {
            Logger.d(TAG, "La reconexión se realizó correctamente pero el estado cambió(${_state.value.status}),renunciar a la recuperación")
            cleanupConnection()
            clearAudioBuffer()
            return
        }
        Logger.i(TAG, "WebSocket Reconexión exitosa,Reedición de audio almacenado en buffer(${audioBuffer.size} marco,$audioBufferBytes bytes)")
        reconnectAttempt = 0
        _state.update { it.copy(status = ASRStatus.Listening, errorMessage = null) }
        flushAudioBuffer()
    }

    /**
     * Reconexión agotada:Estado de error del interruptor(Se adjunta una copia de degradación visible para el usuario.),Cierra la conexión y suelta el micrófono.
     *
     * El texto reconocido se conserva en state.transcript,Los usuarios pueden copiar e intentar nuevamente.
     */
    private fun giveUpReconnect(reason: String) {
        Logger.w(TAG, "Reconectar $MAX_RECONNECT_ATTEMPTS Aún no recuperado,degradado a Error: $reason")
        _state.update {
            it.copy(
                status = ASRStatus.Error,
                errorMessage = appContext.getString(R.string.asr_reconnect_exhausted),
            ) // CONS-05: Migración de chino codificado a recursos de cadena
        }
        cleanupConnection()
        releaseAudioRecord()
    }

    /**
     * Después de una reconexión exitosa, vuelva a enviar el último PCM con almacenamiento en búfer corto y borre el búfer.
     *
     * Alineación OpenAiRealtimeAsrController.B-28: Reemitir solo el más reciente [MAX_REPLAY_BYTES](≈2 segundos),
     * Los fotogramas anteriores se descartan directamente.,Esto evita que todo el segmento de audio antes de la desconexión se reconozca repetidamente como una nueva voz en una nueva sesión.
     */
    private fun flushAudioBuffer() {
        val ws = webSocket ?: run {
            Logger.w(TAG, "flushAudioBuffer: webSocket esta vacio,descartar buffer")
            clearAudioBuffer()
            return
        }
        synchronized(audioBufferLock) {
            while (audioBufferBytes > MAX_REPLAY_BYTES && audioBuffer.isNotEmpty()) {
                audioBufferBytes -= audioBuffer.removeFirst().size
            }
            while (audioBuffer.isNotEmpty()) {
                val chunk = audioBuffer.removeFirst()
                audioBufferBytes -= chunk.size
                val byteString = okio.Buffer().apply { write(chunk) }.readByteString()
                if (!ws.send(byteString)) {
                    Logger.w(TAG, "No se pudo reenviar el cuadro de audio,restante ${audioBuffer.size} cuadro caído")
                    break
                }
            }
            audioBuffer.clear()
            audioBufferBytes = 0
        }
    }

    /** Enter a terminal error state and release both network and microphone resources. */
    private fun failAsr(message: String) {
        if (isDisposed.get()) return
        cancelReconnect()
        clearAudioBuffer()
        _state.update { it.withAsrFailure(message) }
        cleanupConnection()
        releaseAudioRecord()
    }

    /** Borrar el búfer de audio de reconexión(start/stop/dispose llamado cuando). */
    private fun clearAudioBuffer() {
        synchronized(audioBufferLock) {
            audioBuffer.clear()
            audioBufferBytes = 0
        }
    }

    /** Cancelar programación/Realizar reconexión(No modificar estado),Se llama cuando el usuario se detiene activamente. */
    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /** Liberar recursos de hardware de AudioRecord(Cancelar la grabación de rutina + stop + release). */
    private fun releaseAudioRecord() {
        recordJob?.cancel()
        recordJob = null
        try {
            audioRecord?.stop()
        } catch (_: Throwable) { /* Detenido o no inicializado */ }
        val recorder = audioRecord
        audioRecord = null
        runCatching { recorder?.release() }
    }

    override fun stop() {
        if (!_state.value.isRecording) return
        // Phase 3: El usuario se detiene activamente,Cancele la reconexión en curso y borre el búfer de reconexión
        cancelReconnect()
        clearAudioBuffer()
        _state.update { it.copy(status = ASRStatus.Stopping) }
        // Detente primero AudioRecord(Desbloquear lectura),Cancelar la rutina de grabación nuevamente.,lanzamiento final
        try {
            audioRecord?.stop()
        } catch (_: Throwable) { /* Detenido o no inicializado */ }
        recordJob?.cancel()
        audioRecord?.release()
        audioRecord = null

        // enviar finish-task,espera task-finished(El cambio de retorno es manejado por handleEvent Idle)
        val ws = webSocket
        val tid = currentTaskId
        if (ws != null && tid != null) {
            ws.send(buildFinishTaskMessage(tid))
        }

        // Protección de tiempo de espera:5 No recibido en segundos task-finished,corte forzado Idle
        scope.launch {
            delay(STOP_TIMEOUT_MS)
            if (_state.value.status == ASRStatus.Stopping) {
                Logger.w(TAG, "stop() Esperando el tiempo de espera de finalización de la tarea,corte forzado Idle")
                val full = buildTranscript()
                _state.update { it.copy(status = ASRStatus.Idle, transcript = full) }
                cleanupConnection()
            }
        }
    }

    override fun dispose() {
        isDisposed.set(true)
        // Phase 3: Cancelar la reconexión y borrar el búfer,Evite la reconexión después de desechar
        cancelReconnect()
        clearAudioBuffer()
        recordJob?.cancel()
        try {
            audioRecord?.stop()
        } catch (_: Throwable) { /* Detenido o no inicializado */ }
        audioRecord?.release()
        audioRecord = null
        webSocket?.close(1000, "disposed")
        webSocket = null
        session = null
        scope.cancel()
        _state.update { it.copy(status = ASRStatus.Idle, amplitudes = emptyList()) }
        // Solo cuando se construye un cliente propio shutdown(El ciclo de vida del cliente compartido lo gestiona la persona que llama)
        if (ownsClient) {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    /** Cierre WebSocket y limpie la referencia de la sesión. */
    private fun cleanupConnection() {
        webSocket?.close(1000, "session ended")
        webSocket = null
        session = null
    }

    /**
     * Construya la directiva de ejecución de tareas JSON.
     *
     * Modelos compatibles(config.model):
     *  - paraformer-realtime-v2(Predeterminado,transmisión en vivo Paraformer v2)
     *  - paraformer-realtime-v3(v3,Precisión de reconocimiento mejorada,Mejora del dialecto de soporte)
     *  - sensevoice-v1(SenseVoice,Apoyar el reconocimiento de emociones. + ASR,Fuerte multilingüismo)
     *
     * Parámetros de palabras calientes:DashScope Paraformer Soporte a través de `vocabulary_id`(Vocabulario creado previamente ID)palabras calientes entrantes,
     * actual [AsrConfig.hotwords] es una lista en línea,Pase `hotwords` Parámetros pasados(DashScope parte
     * La versión del modelo admite palabras activas en línea. map,Formato {word: weight});Si el servidor no acepta el formulario en línea,
     * En su lugar, los usuarios deberían usar la consola DashScope para crear previamente la lista de palabras y completarla. [AsrConfig.hotwords] primer elemento
     * como vocabulary_id(Se detectará automáticamente aquí.:Se considera un solo elemento que no termina en un carácter no alfanumérico. vocabulary_id).
     */
    private fun buildRunTaskMessage(taskId: String, sampleRate: Int): String {
        val parameters = buildJsonObject {
            put("format", "pcm")
            put("sample_rate", sampleRate)
            put("punctuation_prediction_enabled", config.enablePunctuation)
            put("inverse_text_normalization_enabled", config.enableInverseTextNormalization)
            config.language?.let { put("language_hints", JsonArray(listOf(JsonPrimitive(it)))) }
            // palabras calientes:inline La lista se convierte en hotwords map(word -> Peso predeterminado 1)
            if (config.hotwords.isNotEmpty()) {
                val hotwordsMap = buildJsonObject {
                    config.hotwords.forEach { word ->
                        if (word.isNotBlank()) put(word.trim(), JsonPrimitive(1))
                    }
                }
                put("hotwords", hotwordsMap)
            }
        }
        val msg = buildJsonObject {
            put(
                "header",
                buildJsonObject {
                    put("action", "run-task")
                    put("task_id", taskId)
                    put("streaming", "duplex")
                },
            )
            put(
                "payload",
                buildJsonObject {
                    put("task_group", "audio")
                    put("task", "asr")
                    put("function", "recognition")
                    put("model", config.model.ifBlank { config.defaultModel() })
                    put("input", buildJsonObject {})
                    put("parameters", parameters)
                },
            )
        }
        return json.encodeToString(JsonObject.serializer(), msg)
    }

    /** Construya la directiva de tarea final JSON. */
    private fun buildFinishTaskMessage(taskId: String): String {
        val msg = buildJsonObject {
            put(
                "header",
                buildJsonObject {
                    put("action", "finish-task")
                    put("task_id", taskId)
                    put("streaming", "duplex")
                },
            )
            put(
                "payload",
                buildJsonObject {
                    put("input", buildJsonObject {})
                },
            )
        }
        return json.encodeToString(JsonObject.serializer(), msg)
    }

    /** JsonObject Herramientas de extensión(Valor de tolerancia a fallos). */
    private fun JsonObject.optString(key: String): String = (this[key] as? JsonPrimitive)?.content ?: ""

    private fun JsonObject.optInt(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.optBool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private fun JsonObject.optObject(key: String): JsonObject? = this[key] as? JsonObject

    companion object {
        private const val TAG = "DashScopeAsrController"
        private const val ENDPOINT = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
        private const val TIMEOUT_EVENT_MS = 15_000L

        /** Tamaño del fragmento de audio:100ms @ 16kHz 16-bit mono = 16000 * 2 * 0.1 = 3200 bytes. */
        private const val AUDIO_CHUNK_BYTES = 3200

        /** WebSocket Límite superior de contrapresión de cola de envío(bytes),Suelte fotogramas más allá del límite para evitar la acumulación de memoria. */
        private const val MAX_QUEUE_BYTES = 100_000L

        /** stop() Tiempo de espera de finalización de la tarea,Obligado a cambiar a inactivo después del tiempo de espera. */
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Phase 3: Número máximo de intentos de reconexión(Alineado con AsrConstants.RECONNECT_MAX_ATTEMPTS de Step/Whisper). */
        private const val MAX_RECONNECT_ATTEMPTS = DashScopeReconnectPolicy.MAX_ATTEMPTS

        /** Phase 3: Límite del búfer PCM durante la reconexión(5MB,Alineación OpenAiRealtimeAsrController,evitar OOM). */
        private const val MAX_AUDIO_BUFFER_BYTES = 5 * 1024 * 1024

        /**
         * Phase 3: La cantidad máxima de bytes de audio que se pueden retransmitir después de una reconexión exitosa.
         * 2 segundos @16kHz/16bit/mono ≈ 64KB(con OpenAiRealtimeAsrController.MAX_REPLAY_BYTES consistente).
         */
        private const val MAX_REPLAY_BYTES = 2 * 16_000 * 2
    }
}

/**
 * DashScope WebSocket sesión,Utilice Channel para unir devoluciones de llamadas a corrutinas(Modo de escucha continua).
 * - onMessage(text) → analizar JSON → entrar events Channel(Para esperar a que llegue Evento task-started)
 * - Al mismo tiempo, todos los eventos se envían continuamente a través de la devolución de llamada onEvent.(para que el Controlador procese result-generated / task-finished)
 * - waitForEvent(type) Obtener el tipo de evento especificado del canal,null El tipo puede ser arbitrario.
 *
 * Phase 3: Nuevo [onDisconnected] devolución de llamada — WebSocket falló/Notificar cuando el servidor se apaga Controller
 * Activar la reconexión de retroceso exponencial(locales close() Activado onClosed no notifica,Evite la reconexión autoactivada).
 */
internal class DashScopeSession(
    private val taskId: String,
    private val json: Json,
    private val onEvent: (JsonObject) -> Unit,
) : WebSocketListener() {

    private val events = kotlinx.coroutines.channels.Channel<JsonObject>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    /**
     * Phase 3: Desconectar devolución de llamada(Establecido por el controlador).
     * - [onFailure]: La conexión falló/Desconexión anormal;
     * - [onClosing]: El servidor cierra activamente la conexión.(locales close() no disparará).
     * Depende del Controlador decidir si reconectarse o bajar a Error.
     */
    var onDisconnected: ((String) -> Unit)? = null

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Logger.d(TAG, "WebSocket Conexión establecida")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        // L-ASR1: Utilice resultOf en su lugar runCatching,evitar tragar CancellationException
        resultOf {
            val obj = json.parseToJsonElement(text) as JsonObject
            val header = obj["header"] as? JsonObject ?: return@resultOf
            // Fusione header.event con el nivel superior para recuperar fácilmente el valor
            val merged = JsonObject(obj + ("event" to (header["event"] ?: JsonPrimitive(""))))
            // Ingrese el canal para que se realice waitForEvent(Principalmente a task-started)
            events.trySend(merged)
            // Envío continuo al Controlador para su procesamiento(result-generated / task-finished / task-failed)
            onEvent(merged)
        }.onError { message, throwable ->
            Logger.w(TAG, "No se pudo analizar el mensaje del servidor: ${throwable?.message ?: message}")
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
        // DashScope ASR No devolver mensajes binarios,ignorar
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        // Phase 3: El servidor se apaga activamente(locales close() No activado onClosing) → Notificar al Controlador sobre la decisión de reconectarse
        Logger.w(TAG, "El servidor cierra la conexión: code=$code reason=$reason")
        onDisconnected?.invoke("El servidor cierra la conexión: code=$code reason=$reason")
        webSocket.close(code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        // locales close() también vendrá aquí,No active la reconexión aquí(De lo contrario, cleanupConnection se activará solo)
        closeChannel()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Logger.w(TAG, "WebSocket falló: ${t.message}")
        // Phase 3: Desconectar y volver a conectar — El Controlador determina la reconexión según el estado y la cantidad de interrupciones./Rebajar(F-34 TODO aterrizaje)
        onDisconnected?.invoke(t.message ?: "WebSocket La conexión falló")
        closeChannel()
    }

    /** Suspender la espera del tipo de evento especificado,timeoutMs Devuelve nulo cuando se agota el tiempo de espera.type=null Tome cualquier evento. */
    suspend fun waitForEvent(type: String?, timeoutMs: Long): JsonObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            val event = withTimeoutOrNull(remaining) { events.receiveCatching().getOrNull() } ?: return null
            if (type == null) return event
            val eventType = event["event"]?.let { (it as? JsonPrimitive)?.content }
            if (eventType == type) return event
            // L-ASR2: Manejo especial de eventos de error como tarea fallida,Otros eventos no objetivo son al menos Logger.d
            if (eventType == "task-failed") {
                val errMsg = (event["header"] as? JsonObject)?.let {
                    (it["error_message"] as? JsonPrimitive)?.content
                } ?: event.toString()
                Logger.w(TAG, "espera $type recibido cuando task-failed: $errMsg")
                return null
            }
            Logger.d(TAG, "espera $type Descartar eventos no objetivo: $eventType")
        }
        return null
    }

    private fun closeChannel() {
        events.close()
    }

    companion object {
        private const val TAG = "DashScopeSession"
    }
}

/**
 * Phase 3: DashScope Estrategia de desconexión y reconexión.
 *
 * Pruebe el cliente limitado o por lotes(Step/Whisper de AsrConstants.RECONNECT_MAX_ATTEMPTS)Alineación,
 * Alineación de la curva de retroceso [OpenAiRealtimeAsrController](Índice duplicado + limitado):
 * 500ms → 1s → 2s,Límite único 8s,Hasta 3 veces.
 */
internal object DashScopeReconnectPolicy {
    /** Número máximo de intentos de reconexión(3 alineaciones con Paso/Susurro). */
    const val MAX_ATTEMPTS = 3

    /** Primer retiro de reconexión(milisegundos). */
    const val BASE_DELAY_MS = 500L

    /** Límite de retiro único(milisegundos). */
    const val MAX_DELAY_MS = 8_000L

    /**
     * La copia de degradación es visible para los usuarios una vez que se agota la reconexión.:Descripción del contenido reservado,Y proporcione un método de reintento manual claro.
     * CONS-05: ruta de producción(giveUpReconnect)Migrado a recursos de cadena asr_reconnect_exhausted;
     * Esta constante está reservada solo para afirmaciones de copia de DashScopeAsrReconnectTest,Ya no ingresa a la ruta visible para el usuario.
     */
    const val EXHAUSTED_MESSAGE = "Se perdió la conexión de voz,Se volvió a conectar automáticamente 3 veces y aún no se restablece. Contenido identificado retenido," +
        "Verifique la red y comience a grabar nuevamente."

    /** intento(0-based)Tiempo de espera antes de volver a conectarse:500ms → 1s → 2s(límite superior 8s). */
    fun delayForAttempt(attempt: Int): Long {
        val safeAttempt = attempt.coerceIn(0, 16)
        return minOf(MAX_DELAY_MS, BASE_DELAY_MS shl safeAttempt)
    }
}

/** Phase 3: WebSocket Qué debe hacer el controlador después de la desconexión. */
internal enum class AsrDisconnectAction {
    /** ignorar(Eliminado / Estado final / El usuario se está deteniendo / La primera conexión es manejada por el propio establishmentConnection). */
    IGNORE,

    /** Ingrese a un retroceso exponencial y vuelva a conectarse. */
    RECONNECT,

    /** Reintentos agotados,Baje a Error y libere los recursos de grabación. */
    GIVE_UP,
}

/**
 * Phase 3: Determinar la acción de procesamiento de desconexión según el estado actual(función pura,Conveniente para pruebas individuales).
 *
 * - dispose Después,Idle/Error estado final:ignorar;
 * - Stopping(El usuario se está deteniendo activamente.):ignorar,por stop() Finalización de la lógica de tiempo de espera;
 * - Connecting(Conexión por primera vez/Se está estableciendo la reconexión):ignorar,El error es manejado por el propio establishmentConnection.;
 * - el resto(Listening/Reconnecting):Reconectar;alcanzar [maxAttempts] Luego rebajar.
 */
internal fun resolveAsrDisconnectAction(
    status: ASRStatus,
    isDisposed: Boolean,
    reconnectAttempt: Int,
    maxAttempts: Int = DashScopeReconnectPolicy.MAX_ATTEMPTS,
): AsrDisconnectAction = when {
    isDisposed -> AsrDisconnectAction.IGNORE
    status == ASRStatus.Idle || status == ASRStatus.Error -> AsrDisconnectAction.IGNORE
    status == ASRStatus.Stopping -> AsrDisconnectAction.IGNORE
    status == ASRStatus.Connecting -> AsrDisconnectAction.IGNORE
    reconnectAttempt >= maxAttempts -> AsrDisconnectAction.GIVE_UP
    else -> AsrDisconnectAction.RECONNECT
}

/**
 * Phase 3: Espera de retroceso para la desconexión y reconexión.
 *
 * Extraer por separado para facilitar la verificación de prueba única de la semántica de cancelación:delay La CancellationException lanzada debe propagarse hacia arriba sin cambios.
 * (No atrapar, no tragar),De lo contrario stop()/dispose() La corrutina continuará ejecutándose después de cancelar la reconexión.
 */
internal suspend fun awaitReconnectBackoff(attempt: Int) {
    delay(DashScopeReconnectPolicy.delayForAttempt(attempt))
}
