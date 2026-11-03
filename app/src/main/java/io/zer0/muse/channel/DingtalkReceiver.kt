package io.zer0.muse.channel

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal fun sanitizeDingtalkPayloadForStorage(payload: JsonObject): String = JsonObject(
    payload.toMutableMap().apply {
        remove("sessionWebhook")
        remove("sessionWebhookExpiredTime")
    },
).toString()

/**
 * v2.0: receptor de uñas — Stream Modo de conexión persistente WebSocket.
 *
 * proceso:Registrar credenciales de conexión → crear WebSocket → Manipulación de marcos de empuje:
 *  - CALLBACK Mensajes de robots → analizar(texto/sesiónWebhook/remitente)→ escribir [ChannelInbox];
 *  - SYSTEM ping → volver ACK(opaque Devuélvelo como está);
 *  - SYSTEM disconnect → Desconectarse activamente,Restablezca la conexión desde el bucle exterior.
 *
 * Después de desconectar, presione [RECONNECT_DELAY_MS] Retrocede y vuelve a conectarte;Salga del bucle cuando no haya canales habilitados.
 * Conexión saliente,Libre de red pública y túneles.
 */
class DingtalkReceiver(
    private val channelManager: ChannelManager,
    private val context: Context,
    private val appScope: CoroutineScope,
) {

    /**
     * La configuración de canal actualmente efectiva.
     *
     * La descarga de imágenes es asíncrona y requiere leer la misma configuración que el bucle de conexión.
     */
    @Volatile private var currentConfig: ChannelConfig? = null
    private var job: Job? = null

    /** Iniciar conexión larga(idempotente;Si hay una conexión existente, deténgase y luego comience de nuevo.). */
    fun restart() {
        job?.cancel()
        job = appScope.launch {
            ChannelInbox.attach(context)
            connectLoop()
        }
    }

    /** Detener conexiones largas. */
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun connectLoop() {
        while (currentCoroutineContext().isActive) {
            channelManager.refresh()
            val config = channelManager.channels.value.firstOrNull {
                it.enabled && it.platform == ChannelPlatform.DINGTALK &&
                    it.appId.isNotBlank() && it.appSecret.isNotBlank()
            }
            if (config == null) {
                // v2.6.7: Cuando no hay ningún canal habilitado**Suspender esperando cambios de configuración**, ya no se activará inactivo cada 5 segundos.
                // antigua forma de escribir delay(5s)+continue Permite que cinco receptores se despierten un total combinado de 60 veces por minuto, evitando el sueño profundo.
                // Los cambios de configuración se escriben en los canales mediante ChannelManager.upsert/remove/refresh;
                // Una vez guardada la página de configuración, se ajustará restart() Al volver a entrar en este ciclo, ambos caminos pueden despertarse.
                Logger.i(TAG, "No hay canales activos de DingTalk,El bucle de recepción se bloquea esperando la configuración")
                channelManager.channels.first { list ->
                    list.any {
                        it.enabled && it.platform == ChannelPlatform.DINGTALK &&
                            it.appId.isNotBlank() && it.appSecret.isNotBlank()
                    }
                }
                continue
            }
            runCatching {
                connectOnce(config)
            }.onFailure { e -> Logger.w(TAG, "Anormalidad en la conexión de DingTalk Stream: ${e.message}") }
            if (!currentCoroutineContext().isActive) return
            delay(RECONNECT_DELAY_MS)
        }
    }

    /** Establezca un WebSocket una vez y espere hasta que se desconecte. */
    private suspend fun connectOnce(config: ChannelConfig) {
        currentConfig = config
        val connection = DingtalkClient.openStreamConnection(
            config.appId,
            config.appSecret,
            config.dingtalkApiBase,
        ).getOrElse { e ->
            Logger.w(TAG, "Error en el registro de DingTalk Stream: ${e.message}")
            return
        }
        val disconnected = CompletableDeferred<Unit>()
        val request = Request.Builder().url(DingtalkClient.streamUrl(connection)).build()
        val socket = DingtalkClient.WEB_SOCKET.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    appScope.launch {
                        runCatching { handleFrame(webSocket, text, config.id) }
                            .onFailure { e -> Logger.w(TAG, "Falló el procesamiento del marco de DingTalk: ${e.message}") }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Logger.i(TAG, "DingTalk WebSocket Cerrar: code=$code $reason")
                    disconnected.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Logger.w(TAG, "DingTalk WebSocket desconectado: ${t.message}")
                    disconnected.complete(Unit)
                }
            },
        )
        Logger.i(TAG, "DingTalk Stream está conectado")
        try {
            disconnected.await()
        } finally {
            socket.cancel()
        }
    }

    /** Manejo de marcos push de Stream(Consulte la documentación para desarrolladores de DingTalk para conocer el protocolo.). */
    private suspend fun handleFrame(webSocket: WebSocket, frame: String, channelId: String) {
        val obj = runCatching { AppJson.parseToJsonElement(frame).jsonObject }.getOrNull() ?: return
        val type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val headers = obj["headers"] as? JsonObject
        val messageId = headers?.get("messageId")?.jsonPrimitive?.contentOrNull.orEmpty()
        val topic = headers?.get("topic")?.jsonPrimitive?.contentOrNull.orEmpty()
        val data = obj["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
        when {
            type == "SYSTEM" && topic == "ping" -> {
                val opaque = runCatching {
                    AppJson.parseToJsonElement(data).jsonObject["opaque"]?.jsonPrimitive?.contentOrNull
                }.getOrNull().orEmpty()
                webSocket.send(DingtalkClient.ackFrame(messageId, "{\"opaque\":\"$opaque\"}"))
            }
            type == "SYSTEM" && topic == "disconnect" -> {
                Logger.i(TAG, "Desconexión de solicitud del servidor DingTalk: ${data.take(200)}")
                webSocket.cancel()
            }
            type == "CALLBACK" && topic == "/v1.0/im/bot/messages/get" -> {
                handleRobotMessage(data, channelId, messageId)
                webSocket.send(DingtalkClient.ackFrame(messageId, "{\"response\": null}"))
            }
            else -> {
                // Otro empujón(evento/Devolución de llamada de tarjeta)educado ACK,No afecta el enlace principal.
                if (messageId.isNotBlank()) {
                    webSocket.send(DingtalkClient.ackFrame(messageId, "{\"response\": null}"))
                }
            }
        }
    }

    /** Analiza la devolución de llamada del mensaje del bot y escribe en la bandeja de entrada. */
    private suspend fun handleRobotMessage(dataJson: String, channelId: String, eventId: String) {
        val payload = runCatching { AppJson.parseToJsonElement(dataJson).jsonObject }.getOrNull() ?: return
        // text campos como objetos {"content": "..."};Defensivamente compatible con la forma pura del encordado.
        val content = (payload["text"] as? JsonObject)
            ?.get("content")?.jsonPrimitive?.contentOrNull
            ?: payload["text"]?.jsonPrimitive?.contentOrNull
        val text = content?.trim().orEmpty()

        // v2.7: El texto del mensaje con imagen está vacío y no se puede descartar aquí; obtenga el código de descarga primero.

        val downloadCode = ChannelImageFetcher.dingtalkDownloadCode(payload)

        if (text.isBlank() && downloadCode == null) return
        val conversationId = payload["conversationId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val senderStaffId = payload["senderStaffId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val conversationType = payload["conversationType"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val webhook = payload["sessionWebhook"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val expireAt = payload["sessionWebhookExpiredTime"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        // grupo/Caché de doble clave de chat único,El lado de devolución no necesita distinguir el tipo de fuente
        DingtalkSessionCache.put(listOf(conversationId, senderStaffId), webhook, expireAt)
        val encryptedReplyContextToken = protectDingtalkReplyContextToken(webhook)
        // chat grupal(conversationType=2)Usar sesión id,Chat único usando el remitente id,Consistente con la semántica objetivo del lado emisor
        val from = if (conversationType == "2") conversationId else senderStaffId
        val source = ChannelInbox.Source(
            platform = "DINGTALK",
            from = from,
            channelId = channelId,
            eventId = eventId,
            encryptedReplyContextToken = encryptedReplyContextToken,
        )

        if (downloadCode == null) {
            ChannelInbox.record(source, text, sanitizeDingtalkPayloadForStorage(payload))
        } else {
            appScope.launch {
                val cfg = currentConfig ?: return@launch
                val base64 = runCatching {
                    val token = DingtalkClient.fetchAccessToken(cfg.appId, cfg.appSecret, cfg.dingtalkApiBase)
                        .getOrNull()?.accessToken

                    if (token.isNullOrBlank()) {
                        null
                    } else {
                        ChannelImageFetcher.fetchDingTalk(
                            apiBase = cfg.dingtalkApiBase,
                            accessToken = token,
                            downloadCode = downloadCode,
                        )
                    }
                }.getOrNull()

                if (base64 != null) {
                    ChannelInbox.record(
                        source = source,
                        text = text.ifBlank { "[fotos]" },
                        rawPayload = sanitizeDingtalkPayloadForStorage(payload),
                        media = ChannelInbox.Media(kind = "image", base64 = base64),
                    )
                } else {
                    Logger.w(TAG, "No se pudieron obtener imágenes de DingTalk(from=$from)")

                    ChannelInbox.record(source, text.ifBlank { "[fotos(No se pudo obtener)]" }, sanitizeDingtalkPayloadForStorage(payload))
                }
            }
        }
    }

    companion object {
        private const val TAG = "DingtalkReceiver"

        /** Retroceder al volver a conectar después de la desconexión(milisegundos). */
        private const val RECONNECT_DELAY_MS = 5_000L
    }
}
