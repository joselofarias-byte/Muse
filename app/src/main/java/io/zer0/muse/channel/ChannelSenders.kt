package io.zer0.muse.channel

import io.zer0.common.AppJson
import io.zer0.common.Logger
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

/**
 * v1.0.92: remitente del canal — Enviar un texto a la plataforma correspondiente(lado emisor).
 *
 * lado receptor(Conexión larga al evento/acceso al webhook)Progrese paso a paso con capacidades bidireccionales más profundas.
 */
internal interface ChannelSender {
    /**
     * enviar un mensaje de texto;Devuelve una excepción razonada en caso de error.
     *
     * [targetOverride] Se utiliza para respuestas automáticas, etc."Publicar de nuevo a la fuente"escena(Feishu open_id / QQ openid),
     * Usado cuando está vacío [ChannelConfig.targetId].
     */
    suspend fun sendText(config: ChannelConfig, text: String, targetOverride: String? = null): Result<Unit>

    /** WeClaw Las respuestas automáticas pueden anular el caché global,Reintento garantizado utilizando el token del evento entrante original. */
    @Suppress("UnusedParameter")
    suspend fun sendText(config: ChannelConfig, text: String, targetOverride: String?, contextTokenOverride: String?): Result<Unit> =
        sendText(config, text, targetOverride)

    /** QQ Las respuestas automáticas pueden vincularse a eventos entrantes ID,evitar de“caché de mensajes recientes”Los mensajes de seguimiento se obtuvieron por error. */
    @Suppress("UnusedParameter")
    suspend fun sendText(
        config: ChannelConfig,
        text: String,
        targetOverride: String?,
        contextTokenOverride: String?,
        sourceEventIdOverride: String?,
    ): Result<Unit> = sendText(config, text, targetOverride, contextTokenOverride)

    /** QQ Genere un msg_seq incrementado utilizando el número persistente de intentos de envío cuando se reintente la respuesta automática. */
    @Suppress("UnusedParameter")
    suspend fun sendText(
        config: ChannelConfig,
        text: String,
        targetOverride: String?,
        contextTokenOverride: String?,
        sourceEventIdOverride: String?,
        sourceEventSequenceOverride: Int?,
    ): Result<Unit> = sendText(
        config,
        text,
        targetOverride,
        contextTokenOverride,
        sourceEventIdOverride,
    )
}

/** Caché de token de acceso de plataforma(Válido por aproximadamente 2 horas.;Se considera caducado con 5 minutos de antelación.). */
private class TokenCache {
    @Volatile private var token: String? = null

    @Volatile private var expiresAt: Long = 0L

    fun get(): String? = token?.takeIf { System.currentTimeMillis() < expiresAt }

    fun put(value: String, expiresInSeconds: Long) {
        token = value
        val safeTtl = (expiresInSeconds - 300L).coerceAtLeast(60L)
        expiresAt = System.currentTimeMillis() + safeTtl * 1000L
    }
}

private val HTTP: OkHttpClient by lazy {
    io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
}

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/** Enviar una solicitud JSON POST;Excepciones no 2xx con fragmentos de respuesta generados(para cadena de resultados). */
private fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): Result<String> = runCatching {
    val builder = Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA))
    headers.forEach { (k, v) -> builder.header(k, v) }
    HTTP.newCall(builder.build()).execute().use { resp ->
        val text = resp.body.string()
        if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(300)}")
        text
    }
}

/**
 * Feishu(Aplicaciones de plataforma abierta y autoconstruidas)transmisor.
 *
 * a cambio de tenant_access_token → `POST /open-apis/im/v1/messages`.
 * receive_id_type Determinar por prefijo de destino:oc_=chat_id / ou_=open_id / on_=union_id.
 */
internal class FeishuChannelSender : ChannelSender {

    private val tokenCache = TokenCache()

    override suspend fun sendText(config: ChannelConfig, text: String, targetOverride: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = tokenCache.get() ?: run {
                    val (value, expires) = fetchToken(config)
                    tokenCache.put(value, expires)
                    value
                }
                val target = targetOverride?.takeIf { it.isNotBlank() } ?: config.targetId
                val idType = when {
                    target.startsWith("oc_") -> "chat_id"
                    target.startsWith("ou_") -> "open_id"
                    target.startsWith("on_") -> "union_id"
                    else -> "chat_id"
                }
                val body = buildJsonObject {
                    put("receive_id", target)
                    put("msg_type", "text")
                    put("content", buildJsonObject { put("text", text) }.toString())
                }.toString()
                postJson(
                    "${feishuOpenBase(config)}/open-apis/im/v1/messages?receive_id_type=$idType",
                    body,
                    mapOf("Authorization" to "Bearer $token"),
                ).getOrThrow()
            }.fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { e ->
                    Logger.w(TAG, "El envío de Feishu falló: ${e.message}")
                    Result.failure(e)
                },
            )
        }

    /** v2.0.1: Nombre de dominio de plataforma abierta Feishu — Elija una de las versiones china o internacional Lark. */
    private fun feishuOpenBase(config: ChannelConfig): String =
        if (config.region == "intl") "https://open.larksuite.com" else "https://open.feishu.cn"

    /** a cambio de tenant_access_token,Regresar (token, expiresInSeconds).internal Para reutilización en la detección de credenciales. */
    internal fun fetchToken(config: ChannelConfig): Pair<String, Long> {
        val body = buildJsonObject {
            put("app_id", config.appId)
            put("app_secret", config.appSecret)
        }.toString()
        val resp = postJson(
            "${feishuOpenBase(config)}/open-apis/auth/v3/tenant_access_token/internal",
            body,
        ).getOrThrow()
        val obj = AppJson.parseToJsonElement(resp).jsonObject
        val code = obj["code"]?.jsonPrimitive?.contentOrNull
        if (code != null && code != "0") error("Error de token de Feishu: ${resp.take(200)}")
        val token = obj["tenant_access_token"]?.jsonPrimitive?.contentOrNull ?: error("Faltan campos de respuesta del token Feishu")
        val expires = obj["expire"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 7200L
        return token to expires
    }

    companion object {
        private const val TAG = "FeishuSender"
    }
}

/**
 * QQ Transmisor robot de plataforma abierta.
 *
 * AppID/AppSecret Cambiar access_token → `POST /v2/groups/{group_openid}/messages`
 * o `/v2/users/{openid}/messages`(Distinguir por tipo de objetivo).
 */
internal class QqChannelSender : ChannelSender {

    private val tokenCache = TokenCache()

    override suspend fun sendText(config: ChannelConfig, text: String, targetOverride: String?): Result<Unit> =
        sendText(config, text, targetOverride, null, null, null)

    @Suppress("UnusedParameter")
    override suspend fun sendText(
        config: ChannelConfig,
        text: String,
        targetOverride: String?,
        contextTokenOverride: String?,
        sourceEventIdOverride: String?,
        sourceEventSequenceOverride: Int?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val token = tokenCache.get() ?: run {
            val info = QqClient.fetchAccessToken(config.appId, config.appSecret).getOrElse { e ->
                Logger.w(TAG, "QQ access_token No se pudo obtener: ${e.message}")
                return@withContext Result.failure<Unit>(e)
            }
            tokenCache.put(info.accessToken, info.expiresInSeconds)
            info.accessToken
        }
        val overrideTarget = targetOverride?.takeIf { it.isNotBlank() }
        val rawTarget = overrideTarget ?: config.targetId
        // v2.0.1: El grupo objetivo es "group:" etiqueta de prefijo(mensaje del grupo);De lo contrario, se determinará según el targetType configurado.
        val isGroup = if (overrideTarget != null) {
            overrideTarget.startsWith("group:")
        } else {
            config.targetType == "group"
        }
        val target = rawTarget.removePrefix("group:")
        val url = if (isGroup) {
            "${QqClient.API_BASE}/v2/groups/$target/messages"
        } else {
            "${QqClient.API_BASE}/v2/users/$target/messages"
        }
        // Calcule primero el contexto de respuesta:replyContext msg_seq avanzará y las llamadas repetidas aumentarán el número de secuencia.
        val reply = QqMsgIdCache.replyContext(
            target = rawTarget,
            sourceEventId = sourceEventIdOverride,
            sequenceOverride = sourceEventSequenceOverride,
        )
        // El chat grupal no tiene permiso de mensaje activo: sin msg_id debe ser 40034102. En lugar de enviar una solicitud que seguramente fallará,
        // Es mejor explicar el motivo claramente en el acto (la implementación anterior no tenía msg_id enviado y el error se oculta como“No me responde”).
        if (isGroup && reply == null) {
            Logger.w(
                TAG,
                "QQ El chat grupal no tiene contexto de respuesta pasiva | target=$rawTarget " +
                    "eventId=${sourceEventIdOverride?.let { if (it.isBlank()) "<cadena vacía>" else it } ?: "<null>"} " +
                    "cached=${QqMsgIdCache.get(rawTarget) ?: "<Ninguno>"}",
            )
            return@withContext Result.failure(
                IllegalStateException(
                    "QQ El chat grupal solo puede responder de forma pasiva: no se ha obtenido el ID del mensaje en 5 minutos (es posible que se haya agotado el tiempo de espera o que el evento haya caducado)",
                ),
            )
        }
        val body = buildJsonObject {
            put("content", text)
            put("msg_type", 0)
            // v2.0.1: respuesta pasiva — ID del mensaje de origen (5 ventana de minutos)+ Incremente el número de secuencia (la plataforma eliminará los duplicados del mismo msg_id+seq).
            reply?.let {
                put("msg_id", it.messageId)
                put("msg_seq", it.sequence)
            }
        }.toString()
        runCatching {
            postJson(
                url,
                body,
                mapOf(
                    "Authorization" to "QQBot $token",
                    "X-Union-Appid" to config.appId,
                ),
            ).getOrThrow()
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { e ->
                // Traduzca el código de error QQ en una oración que pueda usarse para solucionar problemas; el texto original todavía se ingresa en el registro.
                val reason = QqSendErrorMapper.describe(e.message, isGroup)
                Logger.w(TAG, "QQ Envío fallido(${if (isGroup) "chat grupal" else "charla privada"}): ${e.message}")
                Result.failure(IllegalStateException(reason, e))
            },
        )
    }

    companion object {
        private const val TAG = "QqSender"
    }
}

/**
 * v2.0: WeChat ClawBot(iLink)transmisor.
 *
 * bot_token existir en appSecret;El objetivo de la respuesta es el usuario vinculado al escanear el código QR.(targetId=ilink_user_id).
 * Al enviar de vuelta, se da prioridad al mensaje de origen. context_token(Requisitos del acuerdo).
 */
internal class WeClawChannelSender : ChannelSender {
    override suspend fun sendText(config: ChannelConfig, text: String, targetOverride: String?): Result<Unit> =
        sendText(config, text, targetOverride, null)

    override suspend fun sendText(
        config: ChannelConfig,
        text: String,
        targetOverride: String?,
        contextTokenOverride: String?,
    ): Result<Unit> {
        val botToken = config.appSecret.trim()
        val target = targetOverride?.takeIf { it.isNotBlank() } ?: config.targetId
        val result = when {
            botToken.isBlank() ->
                Result.failure(IllegalStateException("ClawBot no obligado(desaparecido bot_token,Escanee el código para vincularse primero)"))
            target.isBlank() ->
                Result.failure(IllegalStateException("Receptor perdido ID(ilink_user_id)"))
            else -> WeClawClient.sendMessage(
                botToken = botToken,
                toUserId = target,
                text = text,
                contextToken = contextTokenOverride ?: WeClawContextCache.get(target),
            )
        }
        return result
    }
}

/**
 * v2.0: Telegram transmisor.
 *
 * Bot Token existir en appSecret(@BotFather proporcionar);El objetivo de envío es chat_id.
 * Utilice sendMessage para enviar el mensaje de respuesta directamente a chat_id,No se requiere ningún token de contexto adicional.
 */
internal class TelegramChannelSender : ChannelSender {
    override suspend fun sendText(config: ChannelConfig, text: String, targetOverride: String?): Result<Unit> {
        val token = config.appSecret.trim()
        if (token.isBlank()) {
            return Result.failure(IllegalStateException("Telegram Bot Token No configurado(Por favor complete App Secret)"))
        }
        val target = targetOverride?.takeIf { it.isNotBlank() } ?: config.targetId
        if (target.isBlank()) {
            return Result.failure(IllegalStateException("desaparecido chat_id(Enviar objetivo)"))
        }
        return TelegramClient.sendMessage(token, target, text)
    }
}

/**
 * v2.0: Remitente de DingTalk.
 *
 * Priorizar el uso de la sesión Webhook almacenada en caché al recibir el mensaje para publicar(Válido por aproximadamente 2 horas.,No se requiere autenticación);
 * Cuando el caché no esté disponible, utilice OpenAPI para enviarlo de forma proactiva.(Requiere reemplazo ClientID/ClientSecret access_token).
 * semántica objetivo:a "cid" Comience como una conversación grupal(openConversationId),En caso contrario, será considerado como un único usuario del chat.(senderStaffId).
 */
internal class DingtalkChannelSender : ChannelSender {

    private val tokenCache = TokenCache()

    override suspend fun sendText(config: ChannelConfig, text: String, targetOverride: String?): Result<Unit> =
        sendText(config, text, targetOverride, null)

    override suspend fun sendText(
        config: ChannelConfig,
        text: String,
        targetOverride: String?,
        contextTokenOverride: String?,
    ): Result<Unit> {
        val target = targetOverride?.takeIf { it.isNotBlank() } ?: config.targetId
        if (target.isBlank()) {
            return Result.failure(IllegalStateException("Falta destino de envío(sesión o usuario id)"))
        }
        // 1. sessionWebhook Prioridad(La dirección temporal viene con las credenciales de sesión.)
        (contextTokenOverride?.takeIf { it.isNotBlank() } ?: DingtalkSessionCache.get(target))?.let { webhook ->
            return DingtalkClient.sendViaSessionWebhook(webhook, text)
        }
        // 2. OpenAPI Enviar proactivamente
        val appKey = config.appId.trim()
        val appSecret = config.appSecret.trim()
        if (appKey.isBlank() || appSecret.isBlank()) {
            return Result.failure(
                IllegalStateException("Las credenciales de DingTalk no están configuradas(ClientID/ClientSecret),y la ventana de sesión ha expirado"),
            )
        }
        val token = tokenCache.get() ?: run {
            val info = DingtalkClient.fetchAccessToken(appKey, appSecret, config.dingtalkApiBase).getOrElse { e ->
                return Result.failure(e)
            }
            tokenCache.put(info.accessToken, info.expireInSeconds)
            info.accessToken
        }
        // v2.0.1: Robot Code La configuración explícita tiene prioridad,Dejar en blanco para regresar AppKey(Los bots internos suelen ser los mismos).
        val robotCode = config.robotCode.trim().ifBlank { appKey }
        return if (target.startsWith("cid")) {
            DingtalkClient.sendToGroup(token, robotCode, target, text, config.dingtalkApiBase)
        } else {
            DingtalkClient.sendToUser(token, robotCode, target, text, config.dingtalkApiBase)
        }
    }
}
