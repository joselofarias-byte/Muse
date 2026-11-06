package io.zer0.muse.channel

import kotlinx.serialization.Serializable

/**
 * v1.0.92: plataforma de canal — Tipo de canal de mensajes para acceso a mensajería instantánea externa.
 *
 * v1 Soporte de envío(Empuje las noticias de Muse a la plataforma);El lado receptor y el atraque en profundidad se avanzan paso a paso.:
 *  - FEISHU  Feishu(Aplicaciones de plataforma abierta y autoconstruidas,tenant_access_token + im/v1 enviar)
 *  - QQ      QQ robot de plataforma abierta(AppID/AppSecret Cambiar access_token + v2 y enviar)
 *  - WECLAW  WeChat ClawBot(iLink Acuerdo,2026-03 Oficialmente abierto;Escanee el código QR para vincular + sondeo largo para enviar y recibir)
 *  - TELEGRAM Telegram robot(@BotFather crear,Bot Token + Transceptor de sondeo largo)
 *  - DINGTALK Robot DingTalk(Aplicaciones de plataforma abierta,Stream Recepción de conexión larga + envío de sesión Webhook/OpenAPI)
 */
@Serializable
enum class ChannelPlatform { FEISHU, QQ, WECLAW, TELEGRAM, DINGTALK }

/**
 * v1.0.92: Configuración de un solo canal.
 *
 * Campo de credenciales(APP Secret)Cifrado con SecureKeyStore antes de la persistencia,El texto claro se almacena en la memoria.
 */
@Serializable
data class ChannelConfig(
    val id: String,
    val platform: ChannelPlatform,
    val name: String = "",
    val enabled: Boolean = true,
    /** Credenciales de aplicación de plataforma(Robot ID/QQ de la aplicación Feishu AppID). */
    val appId: String = "",
    /** Clave de aplicación de plataforma(Feishu App Secret / QQ AppSecret),Cifrado cuando se almacena. */
    val appSecret: String = "",
    /** Interruptor de devolución de llamada de plataforma externa,Desactivado de forma predeterminada;No afecta la recepción de conexiones largas existentes. */
    val webhookEnabled: Boolean = false,
    /** Webhook clave privada compartida(Feishu body token / QQ devolución de llamada URL token),Independiente del secreto de la aplicación y de la ubicación del disco cifrado. */
    val webhookVerificationToken: String = "",
    /** Enviar objetivo:Feishu chat_id / open_id;QQ group_openid / user_openid. */
    val targetId: String = "",
    /** QQ tipo de objetivo:"group" chat grupal(Predeterminado) o "c2c" charla privada;Feishu determina automáticamente el tipo de targetId. */
    val targetType: String = "group",
    /**
     * v2.0.1: DingTalk Robot Code — Para enviar respuesta;Los bots internos suelen estar asociados con Client ID(AppKey) Lo mismo,Dejar en blanco para regresar [appId].
     */
    val robotCode: String = "",
    /**
     * v2.0.1: Identidad corporativa de DingTalk Corp ID(Obtenido de información empresarial.;Algunas interfaces de nivel empresarial requieren,Primera tienda con configuración.).
     */
    val corpId: String = "",
    /**
     * v2.0.1: DingTalk API Base URL — Déjelo en blanco para usar el valor predeterminado oficial(https://api.dingtalk.com/v1.0),Se puede apuntar a una puerta de enlace compatible.
     */
    val apiBaseUrl: String = "",
    /**
     * v2.0.1: Área de Feishu — "cn" versión china(open.feishu.cn) / "intl" Versión internacional Lark(open.larksuite.com).
     */
    val region: String = "cn",
    /**
     * v2.0: respuesta automática — Cuando se recibe un mensaje entrante desde la plataforma, automáticamente ejecutará una ronda y lo enviará de regreso a la fuente del mensaje.
     * Desactivado de forma predeterminada,Evite consumir créditos de modelo cuando sea inesperado.
     */
    val autoReply: Boolean = false,
    /**
     * v2.0.1: Asistente de encuadernación de respuesta automática id — Una cadena vacía representa el asistente predeterminado;heredar su personalidad/modelo/parámetros.
     */
    val assistantId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** v2.0.1: DingTalk API Base — Déjelo en blanco para volver al valor predeterminado oficial.;Elimine las barras diagonales finales para evitar problemas de empalme. */
    val dingtalkApiBase: String
        get() = apiBaseUrl.trim().trimEnd('/').ifBlank { DINGTALK_DEFAULT_API_BASE }

    /**
     * Una vista escrita de la credencial.
     *
     * Los campos en mosaico de arriba (appId / appSecret / robotCode / corpId / apiBaseUrl / region)
     * El formato del disco permanece sin cambios; aquí se instalan en clases selladas según la plataforma para que las utilicen nuevas ramas de código.
     *
     * La ventaja es que en el tipo está escrito "Qué plataforma requiere qué certificados". Para plataformas nuevas, el compilador le recordará que complete la rama.
     * En lugar de confiar en la convención de campos de cadenas.
     */
    val credentials: ChannelCredentials
        get() = ChannelCredentialsSerializer.fromFlat(

            flat = ChannelCredentialsSerializer.Flat(

                appId = appId,

                appSecret = appSecret,

                robotCode = robotCode,

                corpId = corpId,

                apiBaseUrl = apiBaseUrl,

                region = region,

                dingtalkApiBase = apiBaseUrl,

            ),

            platform = platform.name,

        )

    /**
     * Sobrescriba el campo en mosaico con las nuevas credenciales.
     *
     * La ruta de escritura va aquí de manera uniforme para evitar que la persona que llama cambie las credenciales y se olvide de sincronizar los campos en mosaico.
     */
    fun withCredentials(value: ChannelCredentials): ChannelConfig {
        val flat = ChannelCredentialsSerializer.toFlat(value)
        return copy(
            appId = flat.appId,
            appSecret = flat.appSecret,
            robotCode = flat.robotCode,
            corpId = flat.corpId,
            apiBaseUrl = flat.apiBaseUrl,
            region = flat.region,
        )
    }
}

/** v2.0.1: URL base predeterminada de la API de DingTalk. */
internal const val DINGTALK_DEFAULT_API_BASE = "https://api.dingtalk.com/v1.0"

/** v1.0.92: El canal envía los resultados. */
data class ChannelSendResult(
    val ok: Boolean,
    val detail: String,
)
