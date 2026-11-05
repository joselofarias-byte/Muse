package io.zer0.muse.channel

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * v2.0.1: QQ receptor robótico — WebSocket Gateway conexión larga(Red pública gratuita).
 *
 * Acuerdo:Obtener punto de acceso WSS → conectar(Hola recibido lleva el ciclo de latidos del corazón)→ Identify
 * (token=QQBot {access_token}, intents=GROUP_AND_C2C_EVENT)→ op=1 latido del corazón → op=0 Despacho de eventos.
 * Escritura de mensajes de usuario [ChannelInbox] y almacenar en caché msg_id para respuestas pasivas(ver [QqMsgIdCache]).
 * Guardar configuración/Llamado por UI después de la eliminación [restart];App Lo llama MuseApp al inicio.
 */
class QqReceiver(
    private val channelManager: ChannelManager,
    private val context: Context,
    private val appScope: CoroutineScope,
) {
    private data class FrameContext(
        val accessToken: String,
        val channelId: String,
        val heartbeatInterval: AtomicLong,
        val lastSeq: AtomicLong,
    )

    private var job: Job? = null

    /** Iniciar bucle de recepción(idempotente;Existe un ciclo de detenerse y luego comenzar de nuevo.). */
    fun restart() {
        job?.cancel()
        job = appScope.launch {
            ChannelInbox.attach(context)
            connectLoop()
        }
    }

    /** Deja de recibir. */
    fun stop() {
        job?.cancel()
        job = null
    }

    @Suppress("TooGenericExceptionCaught") // IO, SSL y protocolo requieren reconexión; la cancelación se propaga.
    private suspend fun connectLoop() {
        while (currentCoroutineContext().isActive) {
            channelManager.refresh()
            val config = channelManager.channels.value.firstOrNull {
                it.enabled && it.platform == ChannelPlatform.QQ &&
                    it.appId.isNotBlank() && it.appSecret.isNotBlank()
            }
            if (config == null) {
                // v2.6.7: Cuando no hay ningún canal habilitado**Suspender esperando cambios de configuración**, ya no se activará inactivo cada 5 segundos.
                // antigua forma de escribir delay(5s)+continue Permite que cinco receptores se despierten un total combinado de 60 veces por minuto, evitando el sueño profundo.
                // Los cambios de configuración se escriben en los canales mediante ChannelManager.upsert/remove/refresh;
                // Una vez guardada la página de configuración, se ajustará restart() Al volver a entrar en este ciclo, ambos caminos pueden despertarse.
                Logger.i(TAG, "Ningún canal QQ activo,El bucle de recepción se bloquea esperando la configuración")
                channelManager.channels.first { list ->
                    list.any {
                        it.enabled && it.platform == ChannelPlatform.QQ &&
                            it.appId.isNotBlank() && it.appSecret.isNotBlank()
                    }
                }
                continue
            }
            Logger.i(TAG, "QQ: canal encontrado ${config.id},conectando…")
            try {
                connectOnce(config)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                Logger.w(TAG, "Error de conexión QQ: ${e::class.simpleName}: ${e.message}")
            }
            if (!currentCoroutineContext().isActive) return
            delay(RECONNECT_DELAY_MS)
        }
    }

    /**
     * Estado de la sesión de la plataforma, persistido en todas las conexiones.
     *
     * La desconexión y la reconexión deben poder reanudarse para recuperar los eventos durante la interrupción, y esto depende de la última vez.
     * session_id con el ultimo recibido seq —— No pueden ser variables locales para conectarOnce.
     */
    private val sessionIdRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val lastSeqRef = java.util.concurrent.atomic.AtomicLong(0L)

    /** La plataforma requiere volver a identificarse (session Establecer en verdadero cuando caduque). */
    private val identifyRequired = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Establezca un WebSocket una vez y espere hasta que se desconecte. */
    private suspend fun connectOnce(config: ChannelConfig) {
        Logger.i(TAG, "Obtener QQ access_token…")
        val token = QqClient.fetchAccessToken(config.appId, config.appSecret).getOrElse { e ->
            Logger.w(TAG, "QQ access_token No se pudo obtener: ${e.message}")
            return
        }
        Logger.i(TAG, "access_token listo(Periodo de validez ${token.expiresInSeconds}s),Obtener gateway…")
        val gateway = QqClient.getGateway(token.accessToken).getOrElse { e ->
            Logger.w(TAG, "QQ gateway No se pudo obtener: ${e.message}")
            return
        }
        Logger.i(TAG, "gateway listo(${gateway.take(60)}),crear WebSocket…")
        val disconnected = CompletableDeferred<Unit>()
        val heartbeatInterval = AtomicLong(DEFAULT_HEARTBEAT_MS)
        val frameContext = FrameContext(token.accessToken, config.id, heartbeatInterval, lastSeqRef)
        val socketRef = AtomicReference<WebSocket?>(null)
        val heartbeatJob = appScope.launch {
            while (isActive) {
                delay(heartbeatInterval.get())
                val webSocket = socketRef.get() ?: continue
                val seq = lastSeqRef.get()
                val payload = if (seq > 0) seq.toString() else "null"
                runCatching { webSocket.send("{\"op\":1,\"d\":$payload}") }
            }
        }
        val request = Request.Builder().url(gateway).build()
        val socket = QqClient.WEB_SOCKET.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        handleFrame(webSocket, text, frameContext)
                    }.onFailure { e -> Logger.w(TAG, "QQ Falló el procesamiento del marco: ${e.message}") }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Logger.w(TAG, "QQ WS Desconectar: ${t.message} (http=${response?.code})")
                    disconnected.complete(Unit)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Logger.i(TAG, "QQ WS cerrar(code=$code, reason=$reason)")
                    // 4006 Inválido session id / 4007 seq Error: Sólo se puede volver a identificar;
                    // El resto (4008 control de frecuencia,4009 la conexión expiró,4900~4913 Error interno) Conserve la sesión y vaya a Reanudar.
                    if (code == 4006 || code == 4007) {
                        sessionIdRef.set(null)
                        lastSeqRef.set(0L)
                        identifyRequired.set(true)
                        Logger.w(TAG, "QQ La sesión no es recuperable(code=$code),Inténtalo de nuevo la próxima vez Identify")
                    }
                    disconnected.complete(Unit)
                }

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Logger.i(TAG, "QQ WS Conectado,espera Hello…")
                }
            },
        )
        socketRef.set(socket)
        awaitChannelSocketTermination(disconnected) {
            heartbeatJob.cancel()
            socket.cancel()
        }
    }

    /** máquina de estado del marco(op: 10 Hello / 11 Heartbeat ACK / 0 eventos / 7 reconexiones / 9 sesiones no válidas). */
    private fun handleFrame(webSocket: WebSocket, text: String, frameContext: FrameContext) {
        val obj = AppJson.parseToJsonElement(text).jsonObject
        val op = obj["op"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return
        when (op) {
            10 -> {
                obj["d"]?.jsonObject?.get("heartbeat_interval")
                    ?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    ?.let { interval -> frameContext.heartbeatInterval.set(interval) }
                val sessionId = sessionIdRef.get()
                val seq = lastSeqRef.get()
                val canResume = sessionId != null && seq > 0 && !identifyRequired.get()
                if (canResume) {
                    Logger.i(
                        TAG,
                        "QQ Hello(ciclo de latidos del corazón ${frameContext.heartbeatInterval.get()}ms),enviar Resume(seq=$seq) Para compensar el evento de interrupción",
                    )
                    webSocket.send(resumePayload(frameContext.accessToken, sessionId, seq))
                } else {
                    Logger.i(TAG, "QQ Hello(ciclo de latidos del corazón ${frameContext.heartbeatInterval.get()}ms),enviar Identify")
                    identifyRequired.set(false)
                    webSocket.send(identifyPayload(frameContext.accessToken))
                }
            }
            11 -> Unit // Heartbeat ACK
            0 -> handleDispatch(obj, frameContext.channelId)
            7 -> {
                Logger.i(TAG, "QQ Gateway Solicitud de reconexión")
                webSocket.close(1000, "reconnect")
            }
            9 -> {
                Logger.w(TAG, "QQ Invalid Session(intents Problemas de permisos o parámetros,Consulte el chat único de la plataforma abierta QQ/Permisos de mensajes de chat grupal)")
                // La plataforma ha rechazado explícitamente esta sesión, abandonarla sólo provocará que se siga rechazando el siguiente CV.
                sessionIdRef.set(null)
                lastSeqRef.set(0L)
                identifyRequired.set(true)
                webSocket.close(1000, "invalid session")
            }
        }
        obj["s"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.let { seq ->
            if (seq > frameContext.lastSeq.get()) frameContext.lastSeq.set(seq)
        }
    }

    /** Dispatch evento(op=0). */
    private fun handleDispatch(obj: JsonObject, channelId: String) {
        val eventType = obj["t"]?.jsonPrimitive?.contentOrNull
        val d = obj["d"] as? JsonObject
        if (eventType != null && d != null) {
            when (eventType) {
                "C2C_MESSAGE_CREATE" -> handleC2cMessage(d, channelId)
                "GROUP_AT_MESSAGE_CREATE" -> handleGroupMessage(d, channelId)
                // Después de activar "Recibir todos los mensajes", cada mensaje del grupo enviará este evento. Tratado directamente como mensaje @
                // Se le pedirá al robot que responda (deslice el dedo por la pantalla) a cada oración del grupo. Sólo deja rastros y no genera respuestas.
                "GROUP_MESSAGE_CREATE" -> Logger.i(TAG, "QQ Todos los mensajes grupales(todavía no@robot),Sin respuesta automática")
                "READY" -> {
                    val username = d["user"]?.jsonObject
                        ?.get("username")?.jsonPrimitive?.contentOrNull.orEmpty()
                    // session_id Es un ticket para Currículum. No se guardó en absoluto antes, por lo que se debe abrir una nueva sesión si se desconecta la conexión.
                    sessionIdRef.set(d["session_id"]?.jsonPrimitive?.contentOrNull)
                    lastSeqRef.set(0L)
                    identifyRequired.set(false)
                    Logger.i(TAG, "QQ Conectado($username),session grabado")
                }
                "RESUMED" -> Logger.i(TAG, "QQ Sesión reanudada,Los eventos interrumpidos serán reeditados por la plataforma")
            }
        }
    }

    private fun handleC2cMessage(data: JsonObject, channelId: String) {
        val openid = data["author"]?.jsonObject
            ?.get("user_openid")?.jsonPrimitive?.contentOrNull.orEmpty()
        val messageId = data["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (openid.isNotBlank() && messageId.isNotBlank()) QqMsgIdCache.put(openid, messageId)
        val text = resolveInboundText(data)
        if (openid.isNotBlank() && text.isNotBlank()) {
            recordQqInbound(appScope, ChannelInbox.Source("QQ", openid, channelId, messageId), data, text)
        } else {
            Logger.i(TAG, "QQ No hay texto disponible para un solo mensaje de chat,saltar($messageId ← $openid)")
        }
    }

    private fun handleGroupMessage(data: JsonObject, channelId: String) {
        val groupOpenId = data["group_openid"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val target = "group:$groupOpenId"
        val messageId = data["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        // En modo completo, el mensaje @ llegará a dos eventos al mismo tiempo y el mismo mensaje solo se procesará una vez.
        if (messageId.isNotBlank() && QqMsgIdCache.get(target) == messageId) {
            Logger.i(TAG, "QQ Envío repetido de mensajes grupales.,ignorado($messageId)")
            return
        }
        if (groupOpenId.isNotBlank() && messageId.isNotBlank()) QqMsgIdCache.put(target, messageId)
        val text = resolveInboundText(data)
        if (groupOpenId.isNotBlank() && text.isNotBlank()) {
            recordQqInbound(appScope, ChannelInbox.Source("QQ", target, channelId, messageId), data, text)
        } else {
            // Solo @ sin texto(y sin archivos adjuntos)Caerá aquí:Solía ​​ser descartado silenciosamente,Es completamente invisible durante la inspección.
            Logger.i(TAG, "QQ No hay texto disponible para mensaje grupal,saltar($messageId ← $target)")
        }
    }

    /** v2.0.1: Extracción de texto entrante — texto primero;Referencia ASR para el habla.;fotos/vídeo/Marcador de posición de archivo. */
    private fun resolveInboundText(d: JsonObject): String {
        val content = d["content"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        if (content.isNotBlank()) return content
        val attachment = (d["attachments"] as? JsonArray)?.firstOrNull() as? JsonObject
        val contentType = attachment?.get("content_type")?.jsonPrimitive?.contentOrNull.orEmpty()
        return when {
            contentType == "voice" -> attachment?.get("asr_refer_text")
                ?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "[Voz]" }
            contentType.startsWith("image/") -> "[fotos]"
            contentType.startsWith("video/") -> "[vídeo]"
            contentType.isNotBlank() -> "[Accesorios]"
            else -> ""
        }
    }

    companion object {
        private const val TAG = "QqReceiver"

        /** evitación de reconexión(milisegundos). */
        private const val RECONNECT_DELAY_MS = 5_000L

        /** Periodo de latido predeterminado(Hello los marcos se sobrescribirán). */
        private const val DEFAULT_HEARTBEAT_MS = 45_000L
    }
}

/** GROUP_AND_C2C_EVENT (1 << 25) — Mensaje de chat único + grupo@mensaje. */
private const val GROUP_AND_C2C_INTENT = 1 shl 25

/** Resume cargar — Trae el session_id de la sesión anterior y el último recibido seq,La plataforma volverá a emitir el evento de interrupción en consecuencia. */
private fun resumePayload(accessToken: String, sessionId: String, seq: Long): String = buildJsonObject {
    put("op", 6)
    putJsonObject("d") {
        put("token", "QQBot $accessToken")
        put("session_id", sessionId)
        put("seq", seq)
    }
}.toString()

/** Identify cargar(token=QQBot {access_token};shard fragmento único [0,1]). */
private fun identifyPayload(accessToken: String): String = buildJsonObject {
    put("op", 2)
    putJsonObject("d") {
        put("token", "QQBot $accessToken")
        put("intents", GROUP_AND_C2C_INTENT)
        putJsonArray("shard") {
            // [shard_id, shard_count] — Un solo fragmento debe ser [0, 1](count=0 será rechazado: shard count error).
            add(0)
            add(1)
        }
        putJsonObject("properties") {
            put("\$os", "android")
            put("\$browser", "muse")
            put("\$device", "muse")
        }
    }
}.toString()
