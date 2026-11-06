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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * v2.0.1: Receptor de conexión larga Feishu — pbbp2 over WebSocket(Red pública gratuita).
 *
 * proceso:POST {openBase}/callback/ws/endpoint A cambio de dirección wss(Contiene service_id)→ conectar →
 * Ping de control periódico para mantener vivo;El marco de datos se fragmenta y fusiona mediante message_id para analizar el evento.,Escribir después del procesamiento ack
 * (La carga útil del mismo marco se establece en {"code":200}). Compatible con la versión china Feishu y la versión internacional Lark(region campo).
 *
 * evento:Solo proceso `im.message.receive_v1`;p2p mensaje de vuelta al remitente open_id,Mensaje grupal de regreso a chat_id
 * (lado emisor [FeishuChannelSender] Determinar automáticamente según el prefijo de destino receive_id_type).
 */
class FeishuReceiver(
    private val channelManager: ChannelManager,
    private val context: Context,
    private val appScope: CoroutineScope,
) {
    /**
     * La configuración de canal actualmente efectiva.
     *
     * La descarga de imágenes es asíncrona y necesita leer la misma configuración que el bucle de conexión.
     * Entonces la variable local de connectLoop se promueve a miembro.
     */
    @Volatile private var currentConfig: ChannelConfig? = null

    private var job: Job? = null

    /** Búfer de eventos fragmentado:Conjunto FIFO acotado,Evite que una sola conexión anormal se quede sin memoria. */
    private val frameAssembler = FeishuFrameAssembler()

    private data class InboundEvent(
        val from: String,
        val text: String,
        val eventId: String,
        /** image_key para mensajes con imágenes; nulo para mensajes sin imágenes. */

        val imageKey: String? = null,
    )

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

    private suspend fun connectLoop() {
        while (currentCoroutineContext().isActive) {
            channelManager.refresh()
            val config = channelManager.channels.value.firstOrNull {
                it.enabled && it.platform == ChannelPlatform.FEISHU &&
                    it.appId.isNotBlank() && it.appSecret.isNotBlank()
            }

            currentConfig = config
            if (config == null) {
                // v2.6.7: Cuando no hay ningún canal habilitado**Suspender esperando cambios de configuración**, ya no se activará inactivo cada 5 segundos.
                // antigua forma de escribir delay(5s)+continue Permite que cinco receptores se despierten un total combinado de 60 veces por minuto, evitando el sueño profundo.
                // Los cambios de configuración se escriben en los canales mediante ChannelManager.upsert/remove/refresh;
                // Una vez guardada la página de configuración, se ajustará restart() Al volver a entrar en este ciclo, ambos caminos pueden despertarse.
                Logger.i(TAG, "No hay ningún canal Feishu activo,El bucle de recepción se bloquea esperando la configuración")
                channelManager.channels.first { list ->
                    list.any {
                        it.enabled && it.platform == ChannelPlatform.FEISHU &&
                            it.appId.isNotBlank() && it.appSecret.isNotBlank()
                    }
                }
                continue
            }
            Logger.i(TAG, "Bucle receptor Feishu: encontrar canales ${config.id},Establecer conexión…")
            runCatching { connectOnce(config) }
                .onFailure { e -> Logger.w(TAG, "Anormalidad en la conexión Feishu: ${e.message}") }
            if (!currentCoroutineContext().isActive) return
            delay(RECONNECT_DELAY_MS)
        }
    }

    /** Establezca un WebSocket una vez y espere hasta que se desconecte. */
    private suspend fun connectOnce(config: ChannelConfig) {
        val endpoint = requestEndpoint(config) ?: return
        val serviceId = queryParam(endpoint.url, "service_id")?.toIntOrNull() ?: 0
        Logger.i(TAG, "endpoint listo(service=$serviceId, ping=${endpoint.pingIntervalSeconds}s),crear WebSocket…")
        val disconnected = CompletableDeferred<Unit>()
        val socketRef = AtomicReference<WebSocket?>(null)
        val pingJob = appScope.launch {
            while (isActive) {
                delay(endpoint.pingIntervalSeconds * 1000L)
                val webSocket = socketRef.get() ?: continue
                runCatching { webSocket.send(pingFrame(serviceId).toByteString()) }
            }
        }
        val request = Request.Builder().url(endpoint.url).build()
        val socket = WS_CLIENT.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Logger.i(TAG, "Feishu WS está conectado")
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    runCatching { handleFrame(bytes.toByteArray(), webSocket, config.id) }
                        .onFailure { e -> Logger.w(TAG, "Falló el procesamiento del marco Feishu: ${e.message}") }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Logger.w(TAG, "Feishu WS desconectado: ${t.message}")
                    disconnected.complete(Unit)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Logger.i(TAG, "Feishu WS Cerrar(code=$code, reason=$reason)")
                    disconnected.complete(Unit)
                }
            },
        )
        socketRef.set(socket)
        awaitChannelSocketTermination(disconnected) {
            pingJob.cancel()
            socket.cancel()
        }
    }

    /** despacho de marco:control(ping/pong)con data(evento). */
    private fun handleFrame(bytes: ByteArray, webSocket: WebSocket, channelId: String) {
        val frame = FeishuPbbp2.decode(bytes)
        when (frame.method) {
            FeishuPbbp2.METHOD_CONTROL -> Unit // ping actualizaciones de configuración de echo /pong(Ignorar en la primera edición)
            FeishuPbbp2.METHOD_DATA -> handleDataFrame(frame, webSocket, channelId)
        }
    }

    /** marco de datos:Fusión de fragmentos → distribución de eventos → ack Contestar. */
    private fun handleDataFrame(frame: FeishuPbbp2.Frame, webSocket: WebSocket, channelId: String) {
        val messageId = frame.header("message_id")
        val frameType = frame.header("type")
        val merged = messageId
            ?.takeIf { frameType == "event" }
            ?.let {
                val sum = frame.header("sum")?.toIntOrNull() ?: 1
                val seq = frame.header("seq")?.toIntOrNull() ?: 0
                frameAssembler.add(it, sum, seq, frame.payload)
            }
        if (merged != null) {
            dispatchEvent(merged, channelId)
        }
        if (shouldAcknowledgeFeishuEventFrame(messageId, frameType)) {
            // Cada fragmento de evento reconoce que la capa de transporte ha recibido;Sólo la carga útil completa ingresa a la distribución de servicios anterior.
            val ackFrame = frame.copy(
                headers = frame.headers + FeishuPbbp2.Header("biz_rt", "0"),
                payload = "{\"code\":200}".toByteArray(Charsets.UTF_8),
            )
            runCatching { webSocket.send(FeishuPbbp2.encode(ackFrame).toByteString()) }
        }
    }

    /**

     * Envío de eventos JSON(solo im.message.receive_v1).

     *

     * v2.7: Mensaje con imagen real descargar aquí —— Resulta que la imagen solo sale "[fotos]" literal,

     * El modelo no puede obtener los bytes y la ayuda visual falla porque mediaBase64 está vacía.

     * La descarga pasa por la corrutina sin bloquear el bucle de lectura de conexión larga; si falla, se degradará a texto de marcador de posición.

     */

    private fun dispatchEvent(json: String, channelId: String) {
        val event = parseInboundEvent(json) ?: return

        val source = ChannelInbox.Source("FEISHU", event.from, channelId, event.eventId)

        val cfg = currentConfig

        if (event.imageKey.isNullOrBlank() || cfg == null) {
            ChannelInbox.record(source, event.text, "")

            return
        }

        appScope.launch {
            val base64 = ChannelImageFetcher.fetchFeishu(
                config = cfg,
                imageKey = event.imageKey,
                tokenProvider = { c -> FeishuChannelSender().fetchToken(c) },
            )

            if (base64 != null) {
                ChannelInbox.record(
                    source = source,
                    text = event.text,
                    rawPayload = "",
                    media = ChannelInbox.Media(kind = "image", base64 = base64),
                )
            } else {
                Logger.w(TAG, "Falló la adquisición de la imagen de Feishu(from=${event.from})")

                ChannelInbox.record(source, "[fotos(No se pudo obtener)]", "")
            }
        }
    }

    private fun parseInboundEvent(json: String): InboundEvent? {
        val obj = runCatching { AppJson.parseToJsonElement(json).jsonObject }.getOrNull()
        val header = obj?.get("header")?.jsonObject
        val event = obj?.get("event")?.jsonObject
        val message = event?.get("message")?.jsonObject
        val sender = event?.get("sender")?.jsonObject
        val eventType = header?.get("event_type")?.jsonPrimitive?.contentOrNull
        val senderType = sender?.get("sender_type")?.jsonPrimitive?.contentOrNull
        val chatType = message?.get("chat_type")?.jsonPrimitive?.contentOrNull.orEmpty()
        val messageType = message?.get("message_type")?.jsonPrimitive?.contentOrNull.orEmpty()
        val openId = sender?.get("sender_id")?.jsonObject
            ?.get("open_id")?.jsonPrimitive?.contentOrNull.orEmpty()
        val chatId = message?.get("chat_id")?.jsonPrimitive?.contentOrNull.orEmpty()
        val from = if (chatType == "p2p") openId else chatId
        val text = inboundText(message, messageType)
        val eventId = header?.get("event_id")?.jsonPrimitive?.contentOrNull.orEmpty()
        val supportedEvent = eventType == "im.message.receive_v1"
        val userMessage = senderType == null || senderType == "user"
        val messageReady = from.isNotBlank() && text.isNotBlank()
        // Los mensajes de imagen vienen con image_key para su posterior descarga real; otros tipos son nulos.

        val imageKey =
            if (messageType == "image") {
                ChannelImageFetcher.feishuImageKey(
                    message?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            } else {
                null
            }

        return if (supportedEvent && userMessage && messageReady) {
            InboundEvent(from, text, eventId, imageKey)
        } else {
            null
        }
    }

    private fun inboundText(message: JsonObject?, messageType: String): String = feishuInboundText(message, messageType)

    /**
     * v2.6.0 (AUD-CHANNEL-F8): Extraiga texto visible para el usuario de los mensajes de Feishu (conexión larga/webhook compartido).
     * webhook La ruta anterior tomaba directamente la cadena original message.content ({"text":"Hola"}) como el texto,
     * El modelo recibe el JSON y puede volver a leerlo; esto es consistente con rutas de conexión largas.
     */
    internal fun feishuInboundText(message: JsonObject?, messageType: String): String = when (messageType) {
        "text" -> parseFeishuTextContent(message?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty())
        "image" -> "[fotos]"
        "audio" -> "[Voz]"
        "media" -> "[vídeo]"
        "file" -> "[Archivo]"
        "post" -> "[texto enriquecido]"
        else -> if (messageType.isBlank()) "" else "[$messageType]"
    }

    /** Analizar mensajes de texto content(JSON cuerda;Quitar el marcador de posición @). */
    internal fun parseFeishuTextContent(contentJson: String): String = runCatching {
        AppJson.parseToJsonElement(contentJson).jsonObject
            .get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
            .replace(AT_PLACEHOLDER_REGEX, "")
            .trim()
    }.getOrDefault("")

    /** POST {openBase}/callback/ws/endpoint a cambio de la configuración de la conexión. */
    private suspend fun requestEndpoint(config: ChannelConfig): EndpointInfo? {
        val base = if (config.region == "intl") "https://open.larksuite.com" else "https://open.feishu.cn"
        return runCatching {
            val body = buildJsonObject {
                put("AppID", config.appId)
                put("AppSecret", config.appSecret)
            }.toString()
            val resp = postJson("$base/callback/ws/endpoint", body)
            val obj = AppJson.parseToJsonElement(resp).jsonObject
            val code = obj["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1
            if (code != 0) {
                error("endpoint respuesta code=$code ${obj["msg"]?.jsonPrimitive?.contentOrNull.orEmpty()}")
            }
            val data = obj["data"]?.jsonObject ?: error("endpoint Falta de respuesta data")
            val url = data["URL"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: error("endpoint Falta de respuesta URL")
            val pingSeconds = data["ClientConfig"]?.jsonObject
                ?.get("PingInterval")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?.takeIf { it > 0 } ?: DEFAULT_PING_SECONDS
            EndpointInfo(url = url, pingIntervalSeconds = pingSeconds)
        }.onFailure { e ->
            Logger.w(TAG, "Falló la adquisición del punto final de conexión larga de Feishu: ${e.message}")
        }.getOrNull()
    }

    /** control ping marco(service=serviceId). */
    private fun pingFrame(serviceId: Int): ByteArray = FeishuPbbp2.encode(
        FeishuPbbp2.Frame(
            service = serviceId,
            method = FeishuPbbp2.METHOD_CONTROL,
            headers = listOf(FeishuPbbp2.Header("type", "ping")),
        ),
    )

    private fun queryParam(url: String, name: String): String? =
        runCatching { android.net.Uri.parse(url).getQueryParameter(name) }.getOrNull()

    private fun postJson(url: String, body: String): String {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        HTTP.newCall(request).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(300)}")
            return text
        }
    }

    private data class EndpointInfo(val url: String, val pingIntervalSeconds: Long)

    companion object {
        private const val TAG = "FeishuReceiver"

        /** evitación de reconexión(milisegundos). */
        private const val RECONNECT_DELAY_MS = 5_000L

        /** endpoint El período de latido predeterminado cuando no se devuelve PingInterval(segundos). */
        private const val DEFAULT_PING_SECONDS = 120L

        /** @ marcador de posición en mensajes de texto(Como por ejemplo @_user_1). */
        private val AT_PLACEHOLDER_REGEX = Regex("@_user_\\d+")

        /**
         * v2.6.0 (AUD-CHANNEL-F8): webhook Entrada estática para caminos.
         * de message.content(JSON cuerda)Analizando texto visible para el usuario con message_type,
         * con largas rutas de conexión(método de instancia feishuInboundText)Mismo calibre.
         */
        internal fun parseFeishuMessageText(content: String, messageType: String): String = when (messageType) {
            "text", "" -> runCatching {
                AppJson.parseToJsonElement(content).jsonObject
                    .get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                    .replace(AT_PLACEHOLDER_REGEX, "")
                    .trim()
            }.getOrDefault("")
            "image" -> "[fotos]"
            "audio" -> "[Voz]"
            "media" -> "[vídeo]"
            "file" -> "[Archivo]"
            "post" -> "[texto enriquecido]"
            else -> "[$messageType]"
        }

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private val HTTP: OkHttpClient by lazy {
            io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /** Cliente dedicado de conexión larga(mantener vivo). */
        private val WS_CLIENT: OkHttpClient by lazy {
            io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
