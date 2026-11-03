package io.zer0.muse.channel

import io.zer0.common.AppJson
import io.zer0.muse.data.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * v2.0: Cliente robot DingTalk — Stream Recepción de conexión larga + envío de webhook de sesión/openapi.
 *
 * recibir:Stream modo(POST /v1.0/gateway/connections/open Obtener endpoint + ticket,
 * crear WebSocket;Conexión saliente,Red pública gratuita). enviar de dos maneras:
 *  1. sessionWebhook — Enviar el mensaje cuando sea recibido.,Válido por aproximadamente 2 horas.,No se requiere autenticación;
 *  2. OpenAPI — oauth2/accessToken + Interfaz de mensajes del robot(Toma la iniciativa de enviar los detalles.).
 *
 * Nota sobre el protocolo:Stream El canal en sí no puede responder directamente a los mensajes IM.(solo puedo ACK),La respuesta debe tomar los dos caminos mencionados anteriormente.
 */
internal object DingtalkClient {

    private val HTTP: OkHttpClient by lazy {
        io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** WebSocket cliente dedicado(mantener vivo el latido del corazón). */
    val WEB_SOCKET: OkHttpClient by lazy {
        io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** Stream Credenciales de conexión. */
    data class StreamConnection(val endpoint: String, val ticket: String)

    /** Aplicar credenciales de acceso. */
    data class TokenInfo(val accessToken: String, val expireInSeconds: Long)

    /** Registrar credenciales de conexión de Stream(Suscríbete solo a devoluciones de llamadas de mensajes de bot). */
    suspend fun openStreamConnection(
        clientId: String,
        clientSecret: String,
        apiBase: String = DINGTALK_DEFAULT_API_BASE,
    ): Result<StreamConnection> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("clientId", clientId)
                put("clientSecret", clientSecret)
                putJsonArray("subscriptions") {
                    addJsonObject {
                        put("topic", "/v1.0/im/bot/messages/get")
                        put("type", "CALLBACK")
                    }
                }
                put("ua", "muse-app/2.0")
            }.toString()
            val resp = postJson(
                "$apiBase/gateway/connections/open",
                body,
                emptyMap(),
            ).getOrThrow()
            val obj = AppJson.parseToJsonElement(resp).jsonObject
            StreamConnection(
                endpoint = obj["endpoint"]?.jsonPrimitive?.contentOrNull
                    ?: error("Stream Falta respuesta endpoint: ${resp.take(200)}"),
                ticket = obj["ticket"]?.jsonPrimitive?.contentOrNull
                    ?: error("Stream Falta respuesta ticket: ${resp.take(200)}"),
            )
        }
    }

    /** WebSocket conectar URL(endpoint + ticket;ticket Válido por 90 segundos y solo se puede usar una vez). */
    fun streamUrl(connection: StreamConnection): String =
        "${connection.endpoint}?ticket=${java.net.URLEncoder.encode(connection.ticket, "UTF-8")}"

    /** Responder con sesiónWebhook(La dirección temporal viene con las credenciales de sesión.,No se requiere autenticación). */
    suspend fun sendViaSessionWebhook(webhookUrl: String, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("msgtype", "text")
                putJsonObject("text") { put("content", text) }
            }.toString()
            val resp = postJson(webhookUrl, body, emptyMap()).getOrThrow()
            val obj = runCatching { AppJson.parseToJsonElement(resp).jsonObject }.getOrNull()
            val errcode = obj?.get("errcode")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            if (errcode != null && errcode != 0) {
                error("La devolución de datos de DingTalk falló: errcode=$errcode ${obj["errmsg"]?.jsonPrimitive?.contentOrNull.orEmpty()}")
            }
        }
    }

    /** Obtenga el token de acceso de la aplicación interna. */
    suspend fun fetchAccessToken(appKey: String, appSecret: String, apiBase: String = DINGTALK_DEFAULT_API_BASE): Result<TokenInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildJsonObject {
                    put("appKey", appKey)
                    put("appSecret", appSecret)
                }.toString()
                val resp = postJson(
                    "$apiBase/oauth2/accessToken",
                    body,
                    emptyMap(),
                ).getOrThrow()
                val obj = AppJson.parseToJsonElement(resp).jsonObject
                TokenInfo(
                    accessToken = obj["accessToken"]?.jsonPrimitive?.contentOrNull
                        ?: error("Falta la respuesta del token de DingTalk accessToken: ${resp.take(200)}"),
                    expireInSeconds = obj["expireIn"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 7200L,
                )
            }
        }

    /** Enviar proactivamente:charla privada(Conversación humano-robot;userId Obtener del senderStaffId de la devolución de llamada del mensaje). */
    suspend fun sendToUser(
        accessToken: String,
        robotCode: String,
        userId: String,
        text: String,
        apiBase: String = DINGTALK_DEFAULT_API_BASE,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("robotCode", robotCode)
                putJsonArray("userIds") { add(userId) }
                put("msgKey", "sampleText")
                put("msgParam", buildJsonObject { put("content", text) }.toString())
            }.toString()
            val resp = postJson(
                "$apiBase/robot/oToMessages/batchSend",
                body,
                mapOf("x-acs-dingtalk-access-token" to accessToken),
            ).getOrThrow()
            validateRobotResponse(resp)
        }
    }

    /** Enviar proactivamente:chat grupal(openConversationId Obtener del ID de conversación de la devolución de llamada del mensaje). */
    suspend fun sendToGroup(
        accessToken: String,
        robotCode: String,
        openConversationId: String,
        text: String,
        apiBase: String = DINGTALK_DEFAULT_API_BASE,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("robotCode", robotCode)
                put("openConversationId", openConversationId)
                put("msgKey", "sampleText")
                put("msgParam", buildJsonObject { put("content", text) }.toString())
            }.toString()
            val resp = postJson(
                "$apiBase/robot/groupMessages/send",
                body,
                mapOf("x-acs-dingtalk-access-token" to accessToken),
            ).getOrThrow()
            validateRobotResponse(resp)
        }
    }

    /** Comprobación de errores de la interfaz de envío del robot DingTalk(Ningún código de error/procesoQueryKey regresa normalmente cuando tiene éxito). */
    private fun validateRobotResponse(resp: String) {
        val obj = runCatching { AppJson.parseToJsonElement(resp).jsonObject }.getOrNull() ?: return
        val code = obj["code"]?.jsonPrimitive?.contentOrNull
        if (!code.isNullOrBlank() && code != "0") {
            error("DingTalk no pudo enviar: code=$code ${obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty()}")
        }
    }

    private fun postJson(url: String, body: String, headers: Map<String, String>): Result<String> = runCatching {
        val builder = Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA))
        headers.forEach { (k, v) -> builder.header(k, v) }
        HTTP.newCall(builder.build()).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(300)}")
            text
        }
    }

    /** Stream Respuesta ACK para marco de inserción(Devolver ID de mensaje y data). */
    fun ackFrame(messageId: String, data: String): String = buildJsonObject {
        put("code", 200)
        put("message", "OK")
        putJsonObject("headers") {
            put("messageId", messageId)
            put("contentType", "application/json")
        }
        put("data", data)
    }.toString()
}

internal const val DINGTALK_REPLY_CONTEXT_PREFIX = "muse.dingtalk.session-webhook.v1:"

internal suspend fun protectDingtalkReplyContextToken(webhookUrl: String): String =
    if (webhookUrl.isBlank()) "" else SecureKeyStore.encrypt(DINGTALK_REPLY_CONTEXT_PREFIX + webhookUrl)

internal suspend fun restoreDingtalkReplyContextToken(encryptedToken: String): String? = SecureKeyStore.decryptOrNull(encryptedToken)
    ?.takeIf { it.startsWith(DINGTALK_REPLY_CONTEXT_PREFIX) }
    ?.removePrefix(DINGTALK_REPLY_CONTEXT_PREFIX)
    ?.takeIf { it.isNotBlank() }

/**
 * v2.0: Caché de webhook de sesión de DingTalk — Registrar cuando se recibe el mensaje sessionWebhook,Tenga prioridad al publicar.
 *
 * El chat grupal usa conversationId como clave y el chat individual usa senderStaffId como clave.(Doble escritura al mismo tiempo.,No es necesario distinguir en el lado de la respuesta.);
 * El tiempo de vencimiento se toma del sessionWebhookExpiredTime del cuerpo del mensaje.
 */
internal object DingtalkSessionCache {
    private data class Entry(val webhookUrl: String, val expireAt: Long)

    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun put(keys: List<String>, webhookUrl: String, expireAt: Long) {
        if (webhookUrl.isBlank()) return
        val entry = Entry(webhookUrl, expireAt)
        keys.filter { it.isNotBlank() }.forEach { entries[it] = entry }
    }

    /** Devolución vigente sessionWebhook;Devuelve nulo si no hay ningún valor disponible. */
    fun get(key: String): String? = entries[key]?.takeIf { it.expireAt > System.currentTimeMillis() }?.webhookUrl
}
