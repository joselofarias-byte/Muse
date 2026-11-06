package io.zer0.muse.channel

import io.zer0.common.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
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
 * v2.0: Telegram Bot API cliente — puro HTTP/JSON,Sondeo largo para recibir mensajes(Red pública gratuita).
 *
 * Acceso:Los usuarios buscan @BotFather en Telegram para crear un robot y conseguirlo Bot Token(rellenar appSecret);
 * Recibir mensajes usando el sondeo largo getUpdates,Las respuestas se envían a chat_id mediante sendMessage.
 * Isomórfico con WeChat ClawBot:Conexión saliente,No se requiere ninguna dirección de red pública ni túnel.
 */
internal object TelegramClient {

    private val HTTP: OkHttpClient by lazy {
        io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
            .connectTimeout(15, TimeUnit.SECONDS)
            // El servidor de votación prolongado retiene aproximadamente 30s,El tiempo de espera de lectura debe ser mayor que este valor.
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** Un mensaje entrante. */
    data class InboundMsg(
        val chatId: Long,
        val text: String,
        val chatType: String,
        val updateId: Long? = null,
        /** file_id para mensajes de imagen; nulo para no imágenes. */
        val photoFileId: String? = null,
    )

    /** getUpdates resultados.nextOffset ¿Qué se debe llevar para la próxima solicitud? offset(finalmente update_id + 1). */
    data class Updates(val messages: List<InboundMsg>, val nextOffset: Long?)

    /** Sondeo largo para recibir mensajes. */
    suspend fun getUpdates(token: String, offset: Long?, timeoutSeconds: Int = 30): Result<Updates> = withContext(Dispatchers.IO) {
        runCatching {
            val params = buildString {
                append("?timeout=").append(timeoutSeconds)
                if (offset != null) append("&offset=").append(offset)
            }
            val resp = request(
                "GET",
                "https://api.telegram.org/bot$token/getUpdates$params",
            ).getOrThrow()
            val obj = AppJson.parseToJsonElement(resp).jsonObject
            if (obj["ok"]?.jsonPrimitive?.booleanOrNull == false) {
                error(
                    "Telegram getUpdates falló: " +
                        obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
            val array = obj["result"] as? JsonArray ?: JsonArray(emptyList())
            var maxUpdateId: Long? = null
            val messages = mutableListOf<InboundMsg>()
            for (element in array) {
                val update = element as? JsonObject ?: continue
                val updateId = update["update_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                if (updateId != null && (maxUpdateId == null || updateId > maxUpdateId)) {
                    maxUpdateId = updateId
                }
                val message = update["message"] as? JsonObject ?: continue
                val text = message["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                // photo es una variedad de tamaños, siendo el último elemento el más grande.
                val photoFileId = (message["photo"] as? JsonArray)
                    ?.lastOrNull()
                    ?.let { it as? JsonObject }
                    ?.get("file_id")?.jsonPrimitive?.contentOrNull
                if (text.isBlank() && photoFileId.isNullOrBlank()) continue
                if (text.isBlank()) continue
                val chat = message["chat"] as? JsonObject ?: continue
                val chatId = chat["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: continue
                messages += InboundMsg(
                    chatId = chatId,
                    text = text,
                    chatType = chat["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    updateId = updateId,
                )
            }
            Updates(
                messages = messages,
                nextOffset = maxUpdateId?.plus(1) ?: offset,
            )
        }
    }

    /** Obtenga la ruta del archivo:getFile Reemplace ruta_archivo. */

    suspend fun getFile(token: String, fileId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = request(

                "GET",

                "https://api.telegram.org/bot$token/getFile?file_id=$fileId",

            ).getOrThrow()

            val obj = AppJson.parseToJsonElement(resp).jsonObject

            if (obj["ok"]?.jsonPrimitive?.booleanOrNull == false) {
                error("Telegram getFile falló")
            }

            obj["result"]?.jsonObject

                ?.get("file_path")?.jsonPrimitive?.contentOrNull.orEmpty()
                .ifBlank { error("getFile No devuelto file_path") }
        }
    }

    /** Descargar bytes por file_path. */

    suspend fun downloadFile(token: String, filePath: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://api.telegram.org/file/bot$token/$filePath")
                .get()
                .build()

            HTTP.newCall(req).execute().use { resp ->

                if (!resp.isSuccessful) error("HTTP ${resp.code}")

                resp.body.bytes()
            }
        }
    }

    /** Enviar texto a una conversación específica(chat_id Compatible tanto con números como con formularios @channelusername). */
    suspend fun sendMessage(token: String, chatId: String, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("chat_id", chatId)
                put("text", text)
            }.toString()
            val resp = request(
                "POST",
                "https://api.telegram.org/bot$token/sendMessage",
                body,
            ).getOrThrow()
            val obj = AppJson.parseToJsonElement(resp).jsonObject
            if (obj["ok"]?.jsonPrimitive?.booleanOrNull == false) {
                error(
                    "Telegram Envío fallido: " +
                        obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
        }
    }

    /** v2.0.1: verificar Bot Token — getMe Devuelve el nombre de usuario del bot en caso de éxito.(Como por ejemplo @xxx_bot). */
    suspend fun getMe(token: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = request("GET", "https://api.telegram.org/bot$token/getMe").getOrThrow()
            val obj = AppJson.parseToJsonElement(resp).jsonObject
            if (obj["ok"]?.jsonPrimitive?.booleanOrNull == false) {
                error(
                    "Telegram La verificación falló: " +
                        obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
            obj["result"]?.jsonObject?.get("username")?.jsonPrimitive?.contentOrNull
                ?.let { "@$it" } ?: "Bot"
        }
    }

    private fun request(method: String, url: String, body: String? = null): Result<String> = runCatching {
        val builder = Request.Builder().url(url)
        when (method) {
            "GET" -> builder.get()
            else -> builder.post((body ?: "{}").toRequestBody(JSON_MEDIA))
        }
        HTTP.newCall(builder.build()).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(300)}")
            text
        }
    }
}
