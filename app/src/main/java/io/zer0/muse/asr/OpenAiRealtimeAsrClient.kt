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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenAI Realtime transmisión ASR Controller(Basado en el protocolo oficial en tiempo real de OpenAI,con este proyecto
 * [DashScopeAsrController] Arquitectura consistente).
 *
 * ir wss://api.openai.com/v1/realtime?intent=transcription,Transmisión verdadera + confirmación manual del cliente.
 *
 * Acuerdo(Presione el modo de transcripción de la versión GA de OpenAI Realtime API):
 *  1. crear WebSocket(wss://api.openai.com/v1/realtime?intent=transcription)
 *     - Header: Authorization: Bearer {apiKey}
 *     - no enviar OpenAI-Beta: realtime=v1 —— Beta El canal estuvo desconectado el 2026-05-12,
 *       Se rechazará estrechar la mano de esta cabeza.;GA la versión no requiere ningún encabezado de etiqueta de versión Beta
 *  2. Enviar evento session.update,Configuración(GA Recopile todas las configuraciones del lado de entrada en la capa session.audio.input):
 *     - session.type: transcription(GA Requerido,Diferenciarse de la forma de diálogo de voz en tiempo real)
 *     - session.audio.input.format: { type: "audio/pcm", rate: 16000 }
 *       rate tomar [AsrAudioCapture] La tasa de muestreo de la colección.([AsrConfig.sampleRate] Predeterminado 16000),
 *       Si el valor declarado no coincide con la colección real, el servidor lo analizará según la longitud del marco incorrecto. PCM
 *     - session.audio.input.transcription.model: gpt-realtime-whisper(modelo de transcripción en tiempo real)
 *     - session.audio.input.transcription.prompt: palabras calientes(Opcional,Mejorar la tasa de reconocimiento de nombres propios)
 *     - session.audio.input.transcription.language: Idioma(Opcional)
 *     - session.audio.input.turn_detection: null(No es un VAD del lado del servidor, se abre manualmente mediante la confirmación del cliente);
 *       La clave debe escribirse explícitamente como nula, lo que tiene una semántica diferente a la de omitir la clave.
 *     - session.audio.input.noise_reduction: null(La supresión de ruido no está habilitada.)
 *     Uso de transliteración pura,No configure elementos relacionados con session.audio.output y diálogo de voz
 *  3. empezar AudioRecord(VOICE_COMMUNICATION fuente,PCM 16-bit mono)Colección de bucles
 *  4. por cuadro:Calcular la amplitud RMS → actualizar state.amplitudes;Base64 codificación → Enviar evento input_audio_buffer.append
 *  5. recibir conversation.item.input_audio_transcription.delta empalme incremental
 *  6. recibir conversation.item.input_audio_transcription.completed entrar completedTranscripts
 *     (delta y completado son solo texto acumulado y no envían ninguna señal de envío a la capa superior)
 *  7. stop(): input_audio_buffer.commit() se envía solo cuando el usuario termina activamenteturn_detection cuando es nulo
 *     Este es el único punto de desconexión), espere a que el servidor regrese completo (o se agote el tiempo de espera para forzar la inactividad)
 *  8. dispose():Cancelar corrutina,cerrar WebSocket,liberar AudioRecord
 *
 * Flujo de estado:Idle → Connecting → Listening → Stopping → Idle(o Error)
 *
 * Desconectar y volver a conectar(Tarea 1):
 *  - monitorear WebSocket onClosed/onFailure → Ingrese al estado de reconexión
 *  - retroceso exponencial:1s, 2s, 4s, 8s, 16s, máximo 30s
 *  - Continuar grabando durante la reconexión,Almacenar en búfer tramas PCM para [audioBuffer](cola de memoria,Máximo 5 MB evitar OOM)
 *  - Retransmitir tramas de audio almacenadas en búfer después de una reconexión exitosa,Restablecer el recuento de retrocesos
 *  - Cambie al estado de error después de que el error de reconexión supere 5 veces,Notificación UI"Anormalidad de la red,Por favor verifique la red y vuelva a intentarlo."
 *
 * Nota:Después de cerrar el VAD del lado del servidor,El punto final de un segmento de entrada lo determina completamente la persona que llama stop() decidir——Esta es exactamente la semántica.:
 * ASR No terminará solo por una pausa entre oraciones.,El servidor no enviará media frase por adelantado para el usuario.
 * El VAD local tampoco está habilitado de forma predeterminada([AsrConfig.vadEnabled] Predeterminado false).
 *
 * @param config ASR Configuración(debe tener apiKey)
 * @param sharedClient Compartir inyectables OkHttpClient
 */
class OpenAiRealtimeAsrController(
    private val config: AsrConfig,
    sharedClient: OkHttpClient? = null,
    // v2.7: Alineado con el controlador de transmisión DashScope——La copia de error de cara al usuario debe pasar por los recursos.
    // El mismo campo errorMessage de antes:DashScope usar appContext.getString(...),
    // Esta clase y Step/OpenAiWhisper escriben en chino, lo que equivale a una función y cuatro métodos.
    // Parámetros obligatorios (puede dejarlos vacíos si no lo hace): devolver una cadena vacía tragará el mensaje de error directamente, lo cual es peor que codificar.
    private val appContext: Context,
) : ASRController {

    private val json = Json { ignoreUnknownKeys = true }
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
    private var session: RealtimeSession? = null
    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private var onTranscriptChange: ((String) -> Unit)? = null

    // resultado final acumulado(completed texto del evento)+ resultado intermedio actual(delta texto)
    private val completedTranscripts = StringBuilder()

    @Volatile private var currentDeltaText: String = ""

    // Local opcional VAD(El usuario lo habilita explícitamente en la configuración.)
    private var vadDetector: VadDetector? = null

    // ── Tarea 1: Campos relevantes para la desconexión y reconexión ──────────────────────────────────────
    /** Número de reconexiones activadas(Borrado después del éxito). */
    private var reconnectAttempt = 0

    /** Tramas de audio PCM almacenadas en búfer durante la reconexión(Reedición por orden de incorporación al equipo.). */
    private val audioBuffer = ArrayDeque<ByteArray>()

    /** [audioBuffer] cerrar,El hilo de grabación addLast y la reedición de reconexión removeFirst son mutuamente excluyentes. */
    private val audioBufferLock = Any()

    /** [audioBuffer] Número acumulado actual de bytes,usado para [MAX_AUDIO_BUFFER_BYTES] Juicio de límite superior. */
    @Volatile private var audioBufferBytes = 0

    /** La rutina de reconexión de retroceso actualmente programada,dispose/stop/start Se requiere cancelación. */
    private var reconnectJob: Job? = null

    /** dispose logotipo,Evite la reconexión después de desecharlo. */
    private val isDisposed = AtomicBoolean(false)

    /** v2.7: Analizar el texto de error localizado (isomorfo a DashScopeAsrController). */
    private fun asrError(res: Int, vararg args: Any): String =
        if (args.isEmpty()) appContext.getString(res) else appContext.getString(res, *args)

    override fun start(onTranscriptChange: ((String) -> Unit)?) {
        // dispose Se debe mantener el estado final; de lo contrario, el alcance se canceló pero el inicio aún cambiará el estado a Conectando, lo que provocará que la interfaz de usuario se conecte de forma permanente y falsa.
        if (isDisposed.get()) return
        if (_state.value.status == ASRStatus.Listening) {
            Logger.w(TAG, "Ya grabando,ignorar duplicados start")
            return
        }
        if (config.apiKey.isBlank()) {
            Logger.w(TAG, "OpenAI Realtime ASR No configurado apiKey")
            _state.update { it.copy(status = ASRStatus.Error, errorMessage = asrError(R.string.asr_error_no_apikey)) }
            return
        }
        // si en Reconnecting,Primero cancele la reconexión y limpie los recursos de grabación.,El inicio posterior de Grabación interna se reconstruirá
        if (_state.value.status == ASRStatus.Reconnecting) {
            cancelReconnect()
            recordJob?.cancel()
            try {
                audioRecord?.stop()
            } catch (_: Throwable) { /* Detenido o no inicializado */ }
            audioRecord?.release()
            audioRecord = null
        }
        this.onTranscriptChange = onTranscriptChange
        completedTranscripts.setLength(0)
        currentDeltaText = ""
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

    /** Establezca WebSocket, envíe session.update e inicie el hilo de grabación(primera ruta de conexión). */
    private suspend fun connectAndRecord() {
        val success = establishConnection(isInitial = true)
        if (success) {
            startRecordingInternal()
        } else if (_state.value.status == ASRStatus.Connecting) {
            // establishConnection Ya cleanupConnection,state todavía Connecting,Transferir aquí Error
            _state.update { it.copy(status = ASRStatus.Error, errorMessage = asrError(R.string.asr_error_connect_failed)) }
        }
    }

    /**
     * Establezca una conexión WebSocket y envíe session.update.
     *
     * @param isInitial true=Conexión por primera vez(Transferencia fallida Error,Sin reconexión);false=Reconectar(no ir [scheduleReconnect] continuar retirándose)
     * @return true=Conexión exitosa(session.update Enviado,session.created Ya esperando)
     */
    private suspend fun establishConnection(isInitial: Boolean): Boolean {
        val streamSession = RealtimeSession(json) { event ->
            // onMessage Activado en el hilo OkHttp,El envío al ámbito principal garantiza que handleEvent se ejecute en el hilo principal
            scope.launch { handleEvent(event) }
        }
        streamSession.onDisconnected = { code, reason ->
            // Distribuido a Main scope,Serie con actualizaciones de estado.
            scope.launch { onWebSocketDisconnected(code, reason) }
        }
        session = streamSession

        // endpoint tener prioridad config.baseUrl,Utilice el valor predeterminado incorporado cuando esté vacío
        // baseUrl Puede ser https:// o wss://,Necesita convertir a wss:// y agregar intent=transcription
        val endpoint = buildRealtimeEndpoint(config.baseUrl)
        val request = buildRealtimeRequest(endpoint, config.apiKey)

        val ws = client.newWebSocket(request, streamSession)
        webSocket = ws

        return try {
            // Enviar configuración de sesión.actualizar(Sin respuesta,Realtime API No devuelva eventos que no sean session.created,
            // session.updated posible retraso,Simplemente ve a Escuchar directamente)
            val sessionUpdate = buildSessionUpdateMessage()
            if (!ws.send(sessionUpdate)) {
                Logger.w(TAG, "No se pudo enviar la sesión.actualización")
                cleanupConnection()
                if (isInitial) {
                    _state.update {
                        it.copy(
                            status = ASRStatus.Error,
                            errorMessage = asrError(R.string.asr_error_send_session_update_failed),
                        )
                    }
                }
                return false
            }

            // corta espera session.created(Opcional,5s Sin bloqueo en el tiempo de espera)
            streamSession.waitForEvent("session.created", TIMEOUT_EVENT_MS)
            // P2-13: Durante el apretón de manos, el usuario puede tener stop/dispose — Dejar de iniciar la grabación cuando el estado ya no sea Conectando
            if (isInitial) {
                if (_state.value.status != ASRStatus.Connecting) {
                    Logger.d(TAG, "Apretón de manos completado pero el estado cambió(${_state.value.status}),Dejar de iniciar la grabación")
                    cleanupConnection()
                    return false
                }
                // Ingrese incluso si no se recibe session.created Listening(Algunas estaciones de transferencia no responden a este evento.)
                _state.update { it.copy(status = ASRStatus.Listening, errorMessage = null) }
            } else {
                if (_state.value.status != ASRStatus.Connecting) {
                    Logger.d(TAG, "Reconexión completada pero el estado ha cambiado(${_state.value.status}),renunciar a la recuperación")
                    return false
                }
                // Reconexión exitosa:Restablecer el recuento de retrocesos,Reemitir cuadros de audio almacenados en buffer
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
                        errorMessage = e.message ?: asrError(R.string.asr_error_connection_exception),
                    )
                }
            }
            false
        }
    }

    /**
     * Tarea 1: devolución de llamada de desconexión de WebSocket(por [RealtimeSession.onClosed]/[onFailure] distribuir).
     *
     * toma de decisiones:
     *  - dispose Medio / Ya el estado final(Idle/Error):ignorar
     *  - Stopping(El usuario se está deteniendo activamente.):Sin reconexión,dejar stop() La lógica del tiempo de espera termina
     *  - Connecting(Conectando por primera vez)/ Listening(Conectado)/ Reconnecting(Reconectando):gatillo [scheduleReconnect]
     *
     * @param code código de cierre,[RealtimeSession.FAILURE_CODE] expresar onFailure(-1)
     * @param reason Motivo del cierre(Mensaje o servidor de excepción reason)
     */
    private fun onWebSocketDisconnected(code: Int, reason: String) {
        if (isDisposed.get()) return
        val status = _state.value.status
        if (status == ASRStatus.Idle || status == ASRStatus.Error) {
            // Ya el estado final,ignorar(Puede ser activado por eliminar close)
            return
        }
        if (status == ASRStatus.Stopping) {
            // El usuario se está deteniendo activamente.,WebSocket El cierre es un final normal.,Sin reconexión
            Logger.d(TAG, "Stopping Cierre de WebSocket mediano,Sin reconexión: code=$code reason=$reason")
            return
        }
        if (status == ASRStatus.Connecting) {
            // establishConnection Activado por conexión de limpieza interna close,
            // El error es manejado por el propio establishmentConnection/attemptReconnect,Evite duplicar el número de reconexiones
            Logger.d(TAG, "Connecting Cierre de WebSocket mediano,Manejado por establecer Conexión: code=$code reason=$reason")
            return
        }
        Logger.w(TAG, "WebSocket Desconectar,Activar reconexión: code=$code reason=$reason (attempt=$reconnectAttempt)")
        scheduleReconnect()
    }

    /**
     * Tarea 1: programar la próxima reconexión de retroceso exponencial.
     *
     * secuencia de retroceso:1s, 2s, 4s, 8s, 16s, Máximo 30s.
     * exceder [MAX_RECONNECT_ATTEMPTS] Después de esto, cambie al estado de Error y notifique a la interfaz de usuario.
     */
    private fun scheduleReconnect() {
        if (isDisposed.get()) return
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            Logger.w(TAG, "La reconexión excede $MAX_RECONNECT_ATTEMPTS veces,rendirse,Estado de error del interruptor")
            failAsr(asrError(R.string.asr_reconnect_exhausted))
            return
        }
        val delayMs = minOf(MAX_RECONNECT_DELAY_MS, 1000L * (1L shl reconnectAttempt))
        Logger.w(TAG, "Programación No. ${reconnectAttempt + 1} reconexión,${delayMs}ms Después de la ejecución")
        _state.update {
            it.copy(
                status = ASRStatus.Reconnecting,
                errorMessage = asrError(R.string.asr_reconnecting_message, reconnectAttempt + 1, MAX_RECONNECT_ATTEMPTS),
            )
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            attemptReconnect()
        }
        reconnectAttempt++
    }

    /** Tarea 1: realizar un intento de reconexión(Se ejecuta solo cuando el estado aún es Reconectando). */
    private suspend fun attemptReconnect() {
        if (isDisposed.get()) return
        // Durante el período de espera, el usuario puede detener o cancelar manualmente dispose,Estado de verificación
        val status = _state.value.status
        if (status != ASRStatus.Reconnecting) {
            Logger.d(TAG, "Cambios de estado durante el retiro($status),Cancelar la reconexión")
            return
        }
        // Cambiar temporalmente a Conectando indica que se está estableciendo una conexión.(Retroceso completado)
        _state.update { it.copy(status = ASRStatus.Connecting) }
        val success = establishConnection(isInitial = false)
        if (!success) {
            // Error de reconexión:continuar retirándose(El estado volverá a cambiar mediante ScheduleReconnect Reconnecting)
            Logger.w(TAG, "Error de reconexión (attempt=$reconnectAttempt),Continuar programando")
            scheduleReconnect()
        }
    }

    /** Tarea 1: Devolución de llamada tras una reconexión exitosa — Restablecer el recuento de retrocesos,Reemitir fotogramas de audio almacenados en búfer. */
    private fun onReconnected() {
        Logger.i(TAG, "Reconexión exitosa,Reemitir cuadros de audio almacenados en buffer(${audioBuffer.size} marco,$audioBufferBytes bytes)")
        reconnectAttempt = 0
        _state.update { it.copy(status = ASRStatus.Listening, errorMessage = null) }
        flushAudioBuffer()
    }

    /**
     * Tarea 1: después de una reconexión exitosa, solo se volverá a emitir el búfer más reciente. PCM,y borre el búfer antiguo.
     *
     * B-28: La implementación anterior ocupaba todo el búfer.(límite superior 5MB≈decenas de segundos)Reenvío completo a una nueva sesión VAD del servidor,
     *  Todo el segmento de audio antes de la desconexión se tratará como nueva entrada de voz.,Contaminar los resultados de identificación de la sesión. Reparación:
     *  - Mantenga solo el más reciente [MAX_REPLAY_BYTES](≈1-2 segundos)cuadro de audio,Los fotogramas anteriores se descartan directamente.
     *  - Borre el búfer antiguo antes de volver a emitirlo.,Evite que los marcos residuales se vuelvan a emitir en la siguiente descarga
     *  - webSocket Descartar en su totalidad cuando no esté disponible(consistente con la vieja lógica)
     */
    private fun flushAudioBuffer() {
        val ws = webSocket ?: run {
            Logger.w(TAG, "flushAudioBuffer: webSocket esta vacio,descartar buffer")
            clearAudioBuffer()
            return
        }
        synchronized(audioBufferLock) {
            // descartar todos los fotogramas,Mantenga solo los MAX_REPLAY_BYTES bytes más recientes al final(datos de audio recientes)
            while (audioBufferBytes > MAX_REPLAY_BYTES && audioBuffer.isNotEmpty()) {
                val dropped = audioBuffer.removeFirst()
                audioBufferBytes -= dropped.size
            }
            // Reemitir el último período restante
            while (audioBuffer.isNotEmpty()) {
                val chunk = audioBuffer.removeFirst()
                audioBufferBytes -= chunk.size
                val audioBase64 = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)
                val appendMsg = buildInputAudioBufferAppendMessage(audioBase64)
                if (!ws.send(appendMsg)) {
                    Logger.w(TAG, "No se pudo reenviar el cuadro de audio,restante ${audioBuffer.size} cuadro caído")
                    break
                }
            }
            // claro unificado,Evite que las tramas residuales después de la interrupción de la transmisión se retransmitan la próxima vez
            audioBuffer.clear()
            audioBufferBytes = 0
        }
    }

    /** Tarea 1: borrar el búfer de audio(dispose/stop/start Llamado al reiniciar). */
    private fun clearAudioBuffer() {
        synchronized(audioBufferLock) {
            audioBuffer.clear()
            audioBufferBytes = 0
        }
    }

    /** Tarea 1: cancelar la rutina de reconexión que se está programando(No modificar estado). */
    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /** Iniciar el hilo de grabación AudioRecord(Colección de bucles + carga base64). */
    @SuppressLint("MissingPermission")
    private fun startRecordingInternal() {
        val capture = AsrAudioCapture.create(config.sampleRate, TAG)
        if (capture == null) {
            _state.update { it.copy(status = ASRStatus.Error, errorMessage = asrError(R.string.asr_error_mic_init_failed)) }
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
            _state.update { it.copy(status = ASRStatus.Error, errorMessage = asrError(R.string.asr_error_recording_start_failed)) }
            return
        }

        // hilo de grabación:Lectura en bucle PCM → Calcular amplitud → base64 codificación → enviar input_audio_buffer.append
        // Tarea 1: continuar el bucle en el estado Reconexión(sigue grabando),Pero pon el framebuffer en [audioBuffer] en lugar de enviar
        recordJob = scope.launch(Dispatchers.IO) {
            val chunk = ByteArray(AUDIO_CHUNK_BYTES)
            try {
                while (isActive) {
                    val currentStatus = _state.value.status
                    // Listening/Reconnecting Salga del bucle en un estado distinto al(Idle/Error/Stopping/Connecting estado de transición)
                    if (currentStatus != ASRStatus.Listening && currentStatus != ASRStatus.Reconnecting) {
                        break
                    }
                    val read = record.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        // 1. Calcular la amplitud RMS → actualizar state.amplitudes
                        val amp = AudioAmplitude.calculateRmsAmplitude(chunk, read)
                        _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amp)) }

                        // 2. locales VAD(Si está habilitado):Gatillo activo silencioso de ultraumbral stop
                        if (vadDetector?.processFrame(chunk, read, amp) == true) {
                            Logger.d(TAG, "Activador VAD local,Detenerse activamente")
                            scope.launch { stop() }
                            break
                        }

                        // 3. Tarea 1: almacenar en búfer cuadros de audio durante la reconexión,Listening Enviar normalmente durante el período.
                        if (currentStatus == ASRStatus.Reconnecting) {
                            bufferAudioFrame(chunk, read)
                        } else {
                            sendAudioFrame(chunk, read)
                        }
                    } else if (read == 0) {
                        delay(10L)
                    } else if (read < 0) {
                        Logger.w(TAG, "AudioRecord.read error: $read")
                        if (shouldReportAsrCaptureFailure(_state.value.status, coroutineContext[Job]?.isActive == true)) {
                            failAsr(asrError(R.string.asr_error_recording_read_failed, read))
                        }
                        break
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (shouldReportAsrCaptureFailure(_state.value.status, coroutineContext[Job]?.isActive == true)) {
                    Logger.w(TAG, "Anomalía en el bucle de grabación: ${error.message}", error)
                    failAsr(error.message ?: "AudioRecord read failed")
                }
            }
        }
    }

    /** Tarea 1: enviar una trama en estado de escucha PCM(Pérdida de marco debido a una contrapresión excesiva). */
    private fun sendAudioFrame(chunk: ByteArray, read: Int) {
        val ws = webSocket
        if (ws != null && ws.queueSize() < MAX_WEBSOCKET_QUEUE_BYTES) {
            // Base64 codificación PCM(Realtime API pedido base64)
            val audioBase64 = android.util.Base64.encodeToString(
                chunk.copyOf(read),
                android.util.Base64.NO_WRAP,
            )
            val appendMsg = buildInputAudioBufferAppendMessage(audioBase64)
            if (!ws.send(appendMsg)) {
                Logger.w(TAG, "No se pudo enviar el fragmento de audio")
            }
        } else {
            Logger.d(TAG, "La contrapresión excede el límite,marcos caídos queueSize=${ws?.queueSize()}")
        }
    }

    /** Tarea 1: colocar una trama de PCM en el búfer de cola en estado de Reconexión(Prevención de pérdida de fotogramas que supera el límite superior de 5 MB OOM). */
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
        val eventType = event.optString("type")
        when (eventType) {
            "conversation.item.input_audio_transcription.delta" -> {
                // texto delta:acumular hasta currentDeltaText
                val delta = event.optString("delta")
                if (delta.isNotEmpty()) {
                    currentDeltaText += delta
                    val full = buildTranscript()
                    _state.update { it.copy(transcript = full) }
                    onTranscriptChange?.invoke(full)
                }
            }
            "conversation.item.input_audio_transcription.completed" -> {
                // completar oraciones:poner el acumulado delta(o evento completado text)entrar completedTranscripts,Borrar delta
                val text = event.optString("text").ifBlank { currentDeltaText }
                if (text.isNotEmpty()) {
                    completedTranscripts.append(text).append(" ")
                }
                currentDeltaText = ""
                val full = buildTranscript()
                _state.update { it.copy(transcript = full) }
                onTranscriptChange?.invoke(full)
                // En el modo de confirmación manual, completado es el final de la entrada en este párrafo.:commit luego inmediatamente retrocedió Idle,
                // No pierdas más el tiempo esperando stop() Las garantías de tiempo de espera de los 5(delta / completed No envía una señal de confirmación a la capa superior.)
                if (_state.value.status == ASRStatus.Stopping) {
                    _state.update { it.copy(status = ASRStatus.Idle) }
                    cleanupConnection()
                }
            }
            "error" -> {
                val errObj = event.optObject("error")
                val msg = errObj?.optString("message") ?: event.toString()
                Logger.w(TAG, "Realtime ASR error: $msg")
                failAsr(msg)
            }
            "session.created", "session.updated" -> {
                // Ha sido procesado en establishmentConnection o no necesita ser procesado,ignorar
            }
            else -> { /* Ignorar eventos desconocidos(input_audio_buffer.speech_started / speech_stopped / committed espera) */ }
        }
    }

    /** empalme completedTranscripts + currentDeltaText,separados por espacios. */
    private fun buildTranscript(): String {
        val parts = mutableListOf<String>()
        if (completedTranscripts.isNotEmpty()) {
            val completed = completedTranscripts.toString().trim()
            if (completed.isNotEmpty()) parts.add(completed)
        }
        if (currentDeltaText.isNotBlank()) parts.add(currentDeltaText.trim())
        return parts.joinToString(" ").trim()
    }

    override fun stop() {
        if (!_state.value.isRecording) return
        // Tarea 1: cancelar la reconexión que se está programando(El usuario se detiene activamente,No más reconexión)
        cancelReconnect()
        _state.update { it.copy(status = ASRStatus.Stopping) }
        // Detente primero AudioRecord(Desbloquear lectura),Cancelar la rutina de grabación nuevamente.,lanzamiento final
        try {
            audioRecord?.stop()
        } catch (_: Throwable) { /* Detenido o no inicializado */ }
        recordJob?.cancel()
        val recorder = audioRecord
        audioRecord = null
        runCatching { recorder?.release() }

        // enviar input_audio_buffer.commit — Modo de confirmación manual(turn_detection=null)La única señal de cierre
        val ws = webSocket
        if (ws != null) {
            ws.send(buildRealtimeCommitMessage())
        }

        // Protección de tiempo de espera:5 El final no ha sido recibido en segundos.,corte forzado Idle
        scope.launch {
            delay(STOP_TIMEOUT_MS)
            if (_state.value.status == ASRStatus.Stopping) {
                Logger.w(TAG, "stop() Timeout esperando cierre,corte forzado Idle")
                val full = buildTranscript()
                _state.update { it.copy(status = ASRStatus.Idle, transcript = full) }
                cleanupConnection()
            }
        }
    }

    override fun dispose() {
        isDisposed.set(true)
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
        if (ownsClient) {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    /** Enter a terminal error state and release both network and microphone resources. */
    private fun failAsr(message: String) {
        if (isDisposed.get()) return
        cancelReconnect()
        _state.update { it.withAsrFailure(message) }
        cleanupConnection()
        releaseAudioRecord()
    }

    /** Cierre WebSocket y limpie la referencia de la sesión. */
    private fun cleanupConnection() {
        webSocket?.close(1000, "session ended")
        webSocket = null
        session = null
    }

    /**
     * Liberar recursos de hardware de AudioRecord(Cancelar la grabación de rutina + stop + release).
     *
     * Cortar error/llamar antes de reiniciar,Evite fugas de ocupación del hardware del micrófono.
     */
    private fun releaseAudioRecord() {
        recordJob?.cancel()
        recordJob = null
        try {
            audioRecord?.stop()
        } catch (_: Throwable) { /* Detenido o no inicializado */ }
        audioRecord?.release()
        audioRecord = null
    }

    /**
     * Construye un punto final WebSocket en tiempo real.
     * - La entrada está vacía:Usar incorporado wss://api.openai.com/v1/realtime?intent=transcription
     * - Ingrese wss://...: deletrear directamente intent=transcription
     * - Ingrese https://...: wss:// y deletree intent=transcription
     */
    private fun buildRealtimeEndpoint(baseUrl: String?): String {
        val raw = baseUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_REALTIME_ENDPOINT
        val withScheme = when {
            raw.startsWith("wss://") || raw.startsWith("ws://") -> raw
            raw.startsWith("https://") -> "wss://" + raw.removePrefix("https://")
            raw.startsWith("http://") -> "ws://" + raw.removePrefix("http://")
            else -> "wss://$raw"
        }
        // baseUrl A menudo se completa como Finalizaciones de chat de OpenAI https://host/v1;
        // Realtime Debe agregarse bajo el mismo host /realtime,No puede conectar directamente la consulta después de /v1.
        val realtimePath = if (withScheme.substringBefore('?').trimEnd('/').endsWith("/realtime")) {
            withScheme
        } else {
            val baseWithoutQuery = withScheme.substringBefore('?').trimEnd('/')
            val query = withScheme.substringAfter('?', "").takeIf { it.isNotBlank() }
            val path = "$baseWithoutQuery/realtime"
            if (query == null) path else "$path?$query"
        }
        // empalme intent=transcription(Si ya hay una consulta, úsala. &,De lo contrario utilizar ?),Evite duplicar parámetros.
        // GA El formulario de sesión ha sido asumido por session.type en session.update,intent Los parámetros ya no son necesarios;
        // Guárdalo porque es sólo una pista morfológica redundante.,No afecta la autenticación y el comportamiento.,Pero es compatible con estaciones de transferencia que aún no siguen GA.
        return if (realtimePath.contains("intent=transcription")) {
            realtimePath
        } else if (realtimePath.contains("?")) {
            "$realtimePath&intent=transcription"
        } else {
            "$realtimePath?intent=transcription"
        }
    }

    /** Construir evento session.update JSON(tipo de sesión/Formato de entrada/modelo/VAD/palabras calientes). */
    private fun buildSessionUpdateMessage(): String {
        val payload = buildRealtimeSessionUpdatePayload(
            model = config.model.ifBlank { config.defaultModel() },
            hotwords = config.hotwords,
            language = config.language,
            // con [AsrAudioCapture.create] Se utiliza la misma frecuencia de muestreo.,No podemos escribir a cada uno hasta la muerte.
            sampleRate = config.sampleRate,
        )
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    /** Construir evento input_audio_buffer.append JSON(Audio codificado y enviado en base64.). */
    private fun buildInputAudioBufferAppendMessage(audioBase64: String): String {
        val msg = buildJsonObject {
            put("type", "input_audio_buffer.append")
            put("audio", audioBase64)
        }
        return json.encodeToString(JsonObject.serializer(), msg)
    }

    /** JsonObject Herramientas de extensión(Valor de tolerancia a fallos). */
    private fun JsonObject.optString(key: String): String = (this[key] as? JsonPrimitive)?.content ?: ""

    private fun JsonObject.optObject(key: String): JsonObject? = this[key] as? JsonObject

    companion object {
        private const val TAG = "OpenAiRealtimeAsrController"
        private const val DEFAULT_REALTIME_ENDPOINT = "wss://api.openai.com/v1/realtime"
        private const val TIMEOUT_EVENT_MS = 5_000L

        /** Tamaño del fragmento de audio:100ms @ 16kHz 16-bit mono = 16000 * 2 * 0.1 = 3200 bytes. */
        private const val AUDIO_CHUNK_BYTES = 3200

        /** WebSocket Límite superior de contrapresión de cola de envío(bytes),Suelte fotogramas más allá del límite para evitar la acumulación de memoria. */
        private const val MAX_WEBSOCKET_QUEUE_BYTES = 100_000L

        /** stop() Tiempo de espera a la espera de finalización,Obligado a cambiar a inactivo después del tiempo de espera. */
        private const val STOP_TIMEOUT_MS = 5_000L

        // ── Tarea 1: Parámetros de desconexión y reconexión ──
        /** Límite de almacenamiento en búfer de audio durante la reconexión(bytes),5MB Evite OOM. */
        private const val MAX_AUDIO_BUFFER_BYTES = 5 * 1024 * 1024

        // B-28: La duración máxima de audio para la retransmisión después de una reconexión exitosa.2 segundos @ 16kHz 16-bit mono = 2 * 16000 * 2 bytes ≈ 64KB.
        //     Sólo se reeditará el párrafo más reciente.(en lugar de todo el búfer de 5 MB),Evite que el audio antiguo contamine las nuevas sesiones VAD del lado del servidor.
        private const val MAX_REPLAY_BYTES = 2 * 16_000 * 2

        /** Número máximo de intentos de reconexión(Cambie a notificación de estado de error después de exceder este número de veces UI). */
        private const val MAX_RECONNECT_ATTEMPTS = 5

        /** Retraso máximo de retroceso para una sola reconexión(milisegundos),30s tapado. */
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
    }
}

/**
 * Realtime Ingrese la frecuencia de muestreo predeterminada para PCM:con [AsrConfig.sampleRate] valor predeterminado,[AsrAudioCapture] Los parámetros de la colección son consistentes.
 * El valor enviado real está sujeto a la frecuencia de muestreo de recopilación pasada por la persona que llama.,Esto solo se utiliza como valor predeterminado de una función pura.
 */
private const val DEFAULT_INPUT_SAMPLE_RATE = 16_000

/**
 * Construye una solicitud de protocolo de enlace WebSocket en tiempo real.
 *
 * GA la versión no requiere ningún encabezado de etiqueta de versión:Beta canal(`OpenAI-Beta: realtime=v1`)Desconectado el 2026-05-12,
 * Continuar enviando hará que se rechace el protocolo de enlace. Sólo autenticación `Authorization`.
 *
 * Separe en funciones para que las pruebas unitarias puedan afirmar directamente el encabezado del protocolo de enlace,No es necesario iniciar un WebSocket real.
 */
internal fun buildRealtimeRequest(endpoint: String, apiKey: String): Request =
    Request.Builder()
        .url(endpoint)
        .header("Authorization", "Bearer $apiKey")
        .build()

/**
 * Construir versión GA `session.update` de payload(función pura,Conveniente para que las pruebas unitarias afirmen directamente).
 *
 * GA usar `session.type` Distinguir entre formas conversacionales.:`"transcription"` para transcripción pura,`"realtime"` para conversaciones de voz.
 * Este cliente solo transcribe,Fijo `transcription`;La falta de este campo será rechazada por el servidor.
 *
 * La configuración del lado de entrada está en GA y toda anidada en `session.audio.input` abajo:
 * ```
 * {
 *   "type": "session.update",
 *   "session": {
 *     "type": "transcription",
 *     "audio": {
 *       "input": {
 *         "format": { "type": "audio/pcm", "rate": 16000 },
 *         "transcription": { "model": ..., "prompt": ..., "language": ... },
 *         "turn_detection": null,
 *         "noise_reduction": null
 *       }
 *     }
 *   }
 * }
 * ```
 * escritura plana(`session.input_audio_format` / `session.input_audio_transcription` /
 * `session.turn_detection`)Reemplazado por GA,ya no se reconoce.
 *
 * @param model Traducir el nombre del modelo(Si está vacío, la persona que llama asumirá el valor predeterminado.)
 * @param hotwords palabras calientes,escrito en `prompt` entrante;Si está vacío, el campo no se enviará.
 * @param language Consejos de idioma;Si es nulo/en blanco, el campo no se enviará.
 * @param sampleRate [AsrAudioCapture] tasa de muestreo real,escribe en `format.rate`.
 *   Predeterminado 16000 vs. [AsrConfig.sampleRate] El valor predeterminado es el mismo.,No es un valor de ejemplo de documentación.
 */
internal fun buildRealtimeSessionUpdatePayload(
    model: String,
    hotwords: List<String>,
    language: String?,
    sampleRate: Int = DEFAULT_INPUT_SAMPLE_RATE,
): JsonObject {
    val input = buildJsonObject {
        // GA declaración de formato de entrada:Tipo de contenedor + frecuencia de muestreo,Tarifa faltante. El servidor no puede cortar marcos correctamente.
        put(
            "format",
            buildJsonObject {
                put("type", "audio/pcm")
                put("rate", sampleRate)
            },
        )
        put(
            "transcription",
            buildJsonObject {
                put("model", model)
                // Las palabras candentes se transmiten mediante un mensaje(Realtime API apoyo)
                if (hotwords.isNotEmpty()) {
                    val prompt = hotwords.joinToString(", ")
                    if (prompt.isNotBlank()) put("prompt", prompt)
                }
                language?.takeIf { it.isNotBlank() }?.let { put("language", it) }
            },
        )
        // El VAD del lado del servidor está cerrado(turn_detection=null):Solo se puede abrir mediante la confirmación del cliente.,
        // De esta forma, todo el texto terminará de una vez cuando el usuario crea que está terminado.
        put("turn_detection", JsonNull)
        // La supresión de ruido no está habilitada.:Nulo explícito significa desactivado
        put("noise_reduction", JsonNull)
    }
    val session = buildJsonObject {
        put("type", "transcription")
        put(
            "audio",
            buildJsonObject {
                put("input", input)
            },
        )
    }
    return buildJsonObject {
        put("type", "session.update")
        put("session", session)
    }
}

/**
 * estructura `input_audio_buffer.commit` evento(función pura,Conveniente para que las pruebas unitarias afirmen directamente).
 *
 * session de `turn_detection` Cuando es nulo, el servidor no lo hace. VAD,Este evento es la única señal que puede abrir una sección de entrada.:
 * El usuario hace clic en "Finalizar"→ Detener colección → commit → Devoluciones del servidor `transcription.completed` Refinamiento.
 */
internal fun buildRealtimeCommitMessage(): String = buildJsonObject {
    put("type", "input_audio_buffer.commit")
}.toString()

/**
 * OpenAI Realtime WebSocket sesión,Utilice Channel para unir devoluciones de llamadas a corrutinas(Modo de escucha continua).
 * - onMessage(text) → analizar JSON → entrar events Channel(Para esperar a que llegue Evento session.created)
 * - Al mismo tiempo, todos los eventos se envían continuamente a través de la devolución de llamada onEvent.(para que el Controlador procese transcription.delta / completed / error)
 * - waitForEvent(type) Obtener el tipo de evento especificado del canal
 *
 * Tarea 1: Agregar nuevo [onDisconnected] devolución de llamada,WebSocket cerrar/En caso de falla, se notifica al Controlador para activar la reconexión.
 */
private class RealtimeSession(
    private val json: Json,
    private val onEvent: (JsonObject) -> Unit,
) : WebSocketListener() {

    private val events = kotlinx.coroutines.channels.Channel<JsonObject>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    /**
     * Tarea 1: devolución de llamada de desconexión de WebSocket(onClosed/onFailure activará).
     * - code:código de apagado normal;[FAILURE_CODE] expresar onFailure
     * - reason:Motivo del cierre(Mensaje o servidor de excepción reason)
     * Establecido por el controlador,Activar la reconexión de retroceso exponencial.
     */
    var onDisconnected: ((code: Int, reason: String) -> Unit)? = null

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Logger.d(TAG, "WebSocket Conexión establecida")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        resultOf {
            val obj = json.parseToJsonElement(text) as JsonObject
            // Ingrese el canal para que se realice waitForEvent(Principalmente a session.created)
            events.trySend(obj)
            // Envío continuo al Controlador para su procesamiento(transcription.delta / completed / error)
            onEvent(obj)
        }.onError { message, throwable ->
            Logger.w(TAG, "No se pudo analizar el mensaje del servidor: ${throwable?.message ?: message}")
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
        // Realtime transcription El patrón no devuelve mensajes binarios,ignorar
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Logger.w(TAG, "WebSocket cerrar: code=$code reason=$reason")
        onDisconnected?.invoke(code, reason)
        closeChannel()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Logger.w(TAG, "WebSocket falló: ${t.message}")
        onDisconnected?.invoke(FAILURE_CODE, t.message ?: "WebSocket La conexión falló")
        closeChannel()
    }

    /** Suspender la espera del tipo de evento especificado,timeoutMs Devuelve nulo cuando se agota el tiempo de espera. */
    suspend fun waitForEvent(type: String?, timeoutMs: Long): JsonObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            val event = withTimeoutOrNull(remaining) { events.receiveCatching().getOrNull() } ?: return null
            if (type == null) return event
            val eventType = event["type"]?.let { (it as? JsonPrimitive)?.content }
            if (eventType == type) return event
            // error Manejo especial de eventos.
            if (eventType == "error") {
                val errMsg = (event["error"] as? JsonObject)?.let {
                    (it["message"] as? JsonPrimitive)?.content
                } ?: event.toString()
                Logger.w(TAG, "espera $type recibido cuando error: $errMsg")
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
        private const val TAG = "RealtimeSession"

        /** Tarea 1: valor centinela de código utilizado cuando onFailure(Diferente a los códigos de cierre normales 1000/1001, etc.). */
        const val FAILURE_CODE = -1
    }
}
