package io.zer0.muse.channel

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Credenciales de canal: clasificadas por plataforma, reemplazando la pila original de [ChannelConfig] campo de azulejos en el interior.
 *
 * Pregunta original:`appId` / `appSecret` / `robotCode` / `corpId` / `region` Todo apretado
 * ChannelConfig Por otro lado, cada vez que se agrega una plataforma, se deben agregar campos adicionales y no es posible saber qué plataforma requiere cuál en el momento de la compilación.
 *
 * Ahora cada plataforma solo declara las credenciales que realmente usa y solo agrega una subclase para las nuevas plataformas.
 * `when` El compilador fuerza la finalización de la rama.
 *
 * Compatible: la configuración anterior es un campo en mosaico,[ChannelCredentialsSerializer] Responsable de la conversión bidireccional,
 * Asegúrese de que el JSON antiguo se pueda leer y escribir sin cambios.
 */
@Serializable(with = ChannelCredentialsSerializer::class)
sealed interface ChannelCredentials {
    /** Aplicación de creación propia Feishu/Lark. */
    @Serializable
    data class Feishu(
        val appId: String = "",
        val appSecret: String = "",
        val region: String = "cn",
    ) : ChannelCredentials

    /** QQ Robot. */
    @Serializable
    data class Qq(
        val appId: String = "",
        val appSecret: String = "",
    ) : ChannelCredentials

    /** Robot Ding Talk. */
    @Serializable
    data class DingTalk(
        val appId: String = "",
        val appSecret: String = "",
        val robotCode: String = "",
        val apiBase: String = "",
    ) : ChannelCredentials

    /** puente WeChat,token Existe en el campo appSecret. */
    @Serializable
    data class WeClaw(
        val botToken: String = "",
    ) : ChannelCredentials

    /** Telegram Bot API. */
    @Serializable
    data class Telegram(
        val botToken: String = "",
    ) : ChannelCredentials

    /** Aún no configurado o plataforma desconocida. */
    @Serializable
    data object None : ChannelCredentials

    /**
     * ¿El bono está lo suficientemente completo como para guardarlo?
     *
     * Originalmente, esta sentencia estaba escrita en la página de configuración. `when (platform)` Aquí, escribe cada rama una vez,
     * Es muy fácil filtrar nuevas plataformas. Ahora depende de cada tipo de credencial declararla por sí mismo.
     *
     * @param webhookEnabled Si Webhook está habilitado (se requiere un token de verificación adicional, verificado de forma independiente por la persona que llama)
     */
    fun isComplete(): Boolean = when (this) {
        is Feishu -> appId.isNotBlank() && appSecret.isNotBlank()
        is Qq -> appId.isNotBlank() && appSecret.isNotBlank()
        is DingTalk -> appId.isNotBlank() && appSecret.isNotBlank()
        is WeClaw -> botToken.isNotBlank()
        is Telegram -> botToken.isNotBlank()
        None -> false
    }
}

/**
 * Campos de mosaico ↔ Serialización bidireccional de clases selladas.
 *
 * El formato de ubicación sigue siendo un conjunto de campos en mosaico (appId / appSecret / robotCode / corpId / apiBaseUrl /
 * region / dingtalkApiBase), que es completamente igual que antes de la transformación, por lo que no se requiere migración de datos.
 */
internal object ChannelCredentialsSerializer : KSerializer<ChannelCredentials> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ChannelCredentials")

    override fun deserialize(decoder: Decoder): ChannelCredentials {
        val input = decoder as? JsonDecoder ?: error("ChannelCredentials Solo admite decodificación JSON")
        val obj = input.decodeJsonElement() as? JsonObject ?: return ChannelCredentials.None
        return fromFlat(
            flat = Flat(
                appId = obj.stringOrEmpty("appId"),
                appSecret = obj.stringOrEmpty("appSecret"),
                robotCode = obj.stringOrEmpty("robotCode"),
                apiBaseUrl = obj.stringOrEmpty("apiBaseUrl"),
                region = obj.stringOrEmpty("region").ifBlank { "cn" },
                dingtalkApiBase = obj.stringOrEmpty("dingtalkApiBase"),
            ),
            platform = obj.stringOrEmpty("platform"),
        )
    }

    override fun serialize(encoder: Encoder, value: ChannelCredentials) {
        val out = encoder as? JsonEncoder ?: error("ChannelCredentials Solo admite codificación JSON")
        val flat = toFlat(value)
        val obj = buildJsonObject {
            put("appId", flat.appId)
            put("appSecret", flat.appSecret)
            put("robotCode", flat.robotCode)
            put("corpId", flat.corpId)
            put("apiBaseUrl", flat.apiBaseUrl)
            put("region", flat.region)
            put("dingtalkApiBase", flat.dingtalkApiBase)
        }
        out.encodeJsonElement(obj)
    }

    /** Representación intermedia de campos en mosaico. */
    internal data class Flat(
        val appId: String = "",
        val appSecret: String = "",
        val robotCode: String = "",
        val corpId: String = "",
        val apiBaseUrl: String = "",
        val region: String = "cn",
        val dingtalkApiBase: String = "",
    )

    /** Cargue campos en mosaico en los tipos de cupones correspondientes por plataforma. */

    internal fun fromFlat(flat: Flat, platform: String): ChannelCredentials = when (platform.uppercase()) {
        "FEISHU" -> ChannelCredentials.Feishu(flat.appId, flat.appSecret, flat.region.ifBlank { "cn" })

        "QQ" -> ChannelCredentials.Qq(flat.appId, flat.appSecret)

        "DINGTALK" -> ChannelCredentials.DingTalk(

            appId = flat.appId,

            appSecret = flat.appSecret,

            robotCode = flat.robotCode,

            apiBase = flat.dingtalkApiBase.ifBlank { flat.apiBaseUrl },

        )

        "WECLAW" -> ChannelCredentials.WeClaw(flat.appSecret)

        "TELEGRAM" -> ChannelCredentials.Telegram(flat.appSecret)

        else -> ChannelCredentials.None
    }

    /** Extienda el cupón nuevamente en el campo mosaico para colocarlo y leerlo con el código anterior. */
    internal fun toFlat(value: ChannelCredentials): Flat = when (value) {
        is ChannelCredentials.Feishu -> Flat(
            appId = value.appId,
            appSecret = value.appSecret,
            region = value.region,
        )
        is ChannelCredentials.Qq -> Flat(appId = value.appId, appSecret = value.appSecret)
        is ChannelCredentials.DingTalk -> Flat(
            appId = value.appId,
            appSecret = value.appSecret,
            robotCode = value.robotCode,
            dingtalkApiBase = value.apiBase,
        )
        is ChannelCredentials.WeClaw -> Flat(appSecret = value.botToken)
        is ChannelCredentials.Telegram -> Flat(appSecret = value.botToken)
        ChannelCredentials.None -> Flat()
    }

    private fun JsonObject.stringOrEmpty(key: String): String = runCatching { this[key]?.jsonPrimitive?.content ?: "" }.getOrDefault("")
}
