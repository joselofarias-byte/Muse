package io.zer0.muse.channel

import io.zer0.common.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * v2.0.1: QQ cliente robot — access_token / WSS Gateway / Almacenamiento en caché de respuestas pasivas.
 *
 * recibir:WebSocket Gateway conexión larga(Red pública gratuita,ver [QqReceiver]);
 * enviar:v2 HTTP API(ver QqChannelSender;La respuesta pasiva lleva msg_id + incremento msg_seq).
 */
internal object QqClient {

    /** v2 API dirección base. */
    const val API_BASE = "https://api.sgroup.qq.com"

    /** access_token Obtenga el punto final. */
    private const val TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken"

    private val HTTP: OkHttpClient by lazy {
        io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** WebSocket cliente dedicado(La larga conexión se mantiene viva.). */
    val WEB_SOCKET: OkHttpClient by lazy {
        io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    data class TokenInfo(val accessToken: String, val expiresInSeconds: Long)

    /** Obtener access_token(QQBot Para autenticación). */
    suspend fun fetchAccessToken(appId: String, appSecret: String): Result<TokenInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("appId", appId)
                put("clientSecret", appSecret)
            }.toString()
            val resp = postJson(TOKEN_URL, body).getOrThrow()
            val obj = AppJson.parseToJsonElement(resp).jsonObject
            TokenInfo(
                accessToken = obj["access_token"]?.jsonPrimitive?.contentOrNull
                    ?: error("QQ token Faltan campos de respuesta: ${resp.take(200)}"),
                expiresInSeconds = obj["expires_in"]?.jsonPrimitive?.contentOrNull
                    ?.toLongOrNull() ?: 7200L,
            )
        }
    }

    /** Obtenga un punto de acceso WSS genérico(AUTHORIZATION: QQBot {access_token}). */
    suspend fun getGateway(accessToken: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            // Nota:Aquí está la melodía REST `/gateway` Obtener dirección wss;`/websocket/` es el punto final de WS en sí
            // (Se devolverá un GET HTTP directo 426 Upgrade Required).
            val resp = getJson(
                "$API_BASE/gateway",
                mapOf("Authorization" to "QQBot $accessToken"),
            ).getOrThrow()
            val obj = AppJson.parseToJsonElement(resp).jsonObject
            obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: error("QQ gateway Falta de respuesta url: ${resp.take(200)}")
        }
    }

    private fun getJson(url: String, headers: Map<String, String>): Result<String> = runCatching {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        HTTP.newCall(builder.build()).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(300)}")
            text
        }
    }

    private fun postJson(url: String, body: String): Result<String> = runCatching {
        val request = Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA)).build()
        HTTP.newCall(request).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(300)}")
            text
        }
    }
}

/**
 * v2.0.1: QQ Almacenamiento en caché de respuestas pasivas — El ID del mensaje entrante más reciente y el número de secuencia de respuesta.
 *
 * msg_id Ventana de respuesta pasiva 60 minutos;Varias respuestas con el mismo msg_id deben incrementar msg_seq para eliminar duplicados.
 * (La plataforma rechazará el envío repetido del mismo msg_id + msg_seq).
 */
internal object QqMsgIdCache {

    /**
     * Ventana de respuesta pasiva (tabla original de la "Descripción general de envío y recepción de mensajes" oficial).
     *
     *   Escenario Periodo de validez Número de respuestas por mensaje
     *   4 chats privados durante 60 minutos
     *   Chat grupal 5 veces en 5 minutos
     *
     * Si la ventana de chat grupal está escrita como 60 minutos, 5~60 Los minutos se envían con el msg_id vencido.
     * La respuesta oficial fue 40034128; a su vez, el chat único se redujo a 5 minutos, lo que podría haber sido una conversación de una hora.
     * Expiró a los cinco minutos. Los dos escenarios deben contarse por separado.
     */
    internal const val GROUP_WINDOW_MS = 5 * 60 * 1000L
    internal const val C2C_WINDOW_MS = 60 * 60 * 1000L

    /** El número máximo de respuestas pasivas por mensaje entrante (5 para chats grupales / 4 para chats individuales). */
    internal const val GROUP_MAX_REPLIES = 5
    internal const val C2C_MAX_REPLIES = 4

    /** Banda objetivo del chat grupal `group:` prefijo (ver QqReceiver.handleGroupMessage). */
    internal fun isGroupTarget(target: String): Boolean = target.startsWith("group:")

    internal fun windowFor(target: String): Long = if (isGroupTarget(target)) GROUP_WINDOW_MS else C2C_WINDOW_MS

    internal fun maxRepliesFor(target: String): Int = if (isGroupTarget(target)) GROUP_MAX_REPLIES else C2C_MAX_REPLIES

    private data class Entry(val msgId: String, val at: Long)

    data class ReplyContext(val messageId: String, val sequence: Int)

    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    private val seqs = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

    fun put(target: String, msgId: String) {
        if (target.isBlank() || msgId.isBlank()) return
        entries[target] = Entry(msgId, System.currentTimeMillis())
        seqs.remove(target)
    }

    /** Respuesta pasiva válida dentro de la ventana msg_id;Devuelve nulo cuando caduca. Chat grupal por ventana/Los chats individuales se calculan por separado. */
    fun get(target: String): String? {
        val entry = entries[target] ?: return null
        if (System.currentTimeMillis() - entry.at > windowFor(target)) return null
        return entry.msgId
    }

    /** Obtenga el siguiente número de secuencia de respuesta(Se incrementan varias respuestas con el mismo msg_id). */
    fun nextSeq(target: String): Int = seqs.getOrPut(target) { AtomicInteger(0) }.incrementAndGet()

    /**
     * Cuando la respuesta automática tiene una identidad de evento, vincule el evento y corrija el número de secuencia de reintento;needs Pase nulo cuando se almacenó en caché recientemente.
     *
     * Las cadenas vacías se tratan como nulas:No es una identidad de evento válida.,Las rutas de respaldo de la caché tampoco deben truncarse.
     */
    fun replyContext(target: String, sourceEventId: String?, sequenceOverride: Int? = null): ReplyContext? {
        val eventId = sourceEventId?.takeIf { it.isNotBlank() }
        if (eventId != null) {
            return ReplyContext(eventId, sequence = sequenceOverride?.coerceAtLeast(1) ?: 1)
        }
        return get(target)?.let { ReplyContext(it, nextSeq(target)) }
    }
}
