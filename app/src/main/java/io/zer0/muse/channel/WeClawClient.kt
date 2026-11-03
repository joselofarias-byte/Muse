package io.zer0.muse.channel

import io.zer0.common.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * v2.0: WeChat ClawBot(iLink)cliente de protocolo — puro HTTP/JSON,4 punto final.
 *
 * proceso:Escanea el código QR para registrarte(get_bot_qrcode + sondeo get_qrcode_status)→ Sondeo largo para recibir mensajes(getupdates)
 * → devolución de datos(sendmessage). El protocolo no está acoplado al marco específico.,Se puede conectar cualquier programa que pueda realizar solicitudes HTTP.
 *
 * Mapa de credenciales a [ChannelConfig]:appId=ilink_bot_id,appSecret=bot_token,targetId=ilink_user_id.
 * La respuesta debe contener el mensaje fuente. context_token(ver [WeClawContextCache]).
 */
internal object WeClawClient {

    /** Nombre de dominio de acceso predeterminado(La respuesta de confirmación del código de escaneo puede recuperar el valor de anulación de la URL base.). */
    const val DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com"

    /** v2.x cierre del legado:Transmisión del tamaño del bloque de descarga al disco(64KB). */
    private const val COPY_BUFFER_BYTES = 64 * 1024

    private val HTTP: OkHttpClient by lazy {
        io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
            .connectTimeout(15, TimeUnit.SECONDS)
            // El servidor de votación prolongado retiene aproximadamente 30s,El tiempo de espera de lectura debe ser mayor que este valor.
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** Vincular información del código QR. */
    data class QrCodeInfo(val qrcode: String, val imgContent: String)

    /** Estado del código de escaneo.status: wait / scaned / confirmed / expired. */
    data class QrStatus(
        val status: String,
        val botToken: String = "",
        val botId: String = "",
        val userId: String = "",
        val baseUrl: String = "",
    )

    /** Un mensaje de usuario entrante. */
    data class InboundMsg(
        val fromUserId: String,
        val text: String,
        val contextToken: String,
        /** v2.0.1: carga útil de medios(fotos/Voz/vídeo/Archivo);El mensaje de texto es nulo. */
        val media: MediaRef? = null,
    )

    /**
     * v2.0.1: carga útil de medios — Parámetros de descarga de CDN entrante.
     *
     * Descargar:full_url Prioridad,De lo contrario `{baseUrl}/download?encrypted_query_param=...`;
     * Descifrar:AES-128-ECB + PKCS7(aes_key para base64(16 raw bytes) o base64(hex 32 chars)).
     */
    data class MediaRef(
        /** "image" / "voice" / "video" / "file" */
        val kind: String,
        val aesKeyBase64: String = "",
        val encryptedQueryParam: String = "",
        val fullUrl: String = "",
        val fileName: String = "",
        val durationMs: Long = 0L,
    )

    /** getupdates resultados.buffer El cursor de sincronización llevado para la siguiente solicitud. */
    data class Updates(val messages: List<InboundMsg>, val buffer: String)

    /** 1. Obtenga el código QR vinculante(bot_type=3 es el tipo público actual). */
    suspend fun getQrCode(baseUrl: String = DEFAULT_BASE_URL): Result<QrCodeInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${baseUrl.trimEnd('/')}/ilink/bot/get_bot_qrcode?bot_type=3"
            val resp = request("GET", url).getOrThrow()
            val obj = AppJson.parseToJsonElement(resp).jsonObject
            QrCodeInfo(
                qrcode = obj["qrcode"]?.jsonPrimitive?.contentOrNull
                    ?: error("A la respuesta del código QR le falta el campo qrcode"),
                imgContent = obj["qrcode_img_content"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }

    /** 2. Estado del código de escaneo de encuesta;confirmed Traiga sus credenciales con usted. */
    suspend fun getQrStatus(qrcode: String, baseUrl: String = DEFAULT_BASE_URL): Result<QrStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${baseUrl.trimEnd('/')}/ilink/bot/get_qrcode_status" +
                "?qrcode=${java.net.URLEncoder.encode(qrcode, "UTF-8")}"
            val resp = request(
                "GET",
                url,
                headers = mapOf("iLink-App-ClientVersion" to "1"),
            ).getOrThrow()
            val obj = AppJson.parseToJsonElement(resp).jsonObject
            QrStatus(
                status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "wait",
                botToken = obj["bot_token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                botId = obj["ilink_bot_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                userId = obj["ilink_user_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                baseUrl = obj["baseurl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }

    /** 3. Sondeo largo para recibir mensajes(El servidor tiene aproximadamente 30s,Ningún mensaje devuelve una lista vacía). */
    suspend fun getUpdates(botToken: String, buffer: String, baseUrl: String = DEFAULT_BASE_URL): Result<Updates> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "${baseUrl.trimEnd('/')}/ilink/bot/getupdates"
                val body = buildJsonObject {
                    put("get_updates_buf", buffer)
                    putJsonObject("base_info") { put("channel_version", "0.1.0") }
                }.toString()
                val resp = request(
                    "POST",
                    url,
                    body,
                    headers = mapOf(
                        "Authorization" to "Bearer $botToken",
                        "AuthorizationType" to "ilink_bot_token",
                        "X-WECHAT-UIN" to randomUin(),
                    ),
                ).getOrThrow()
                val obj = AppJson.parseToJsonElement(resp).jsonObject
                val ret = obj["ret"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                if (ret != 0) error("iLink ret=$ret: ${resp.take(200)}")
                val messages = parseMessages(obj["msgs"] as? JsonArray ?: JsonArray(emptyList()))
                Updates(
                    messages = messages,
                    buffer = obj["get_updates_buf"]?.jsonPrimitive?.contentOrNull ?: buffer,
                )
            }
        }

    /** 4. enviar mensaje(message_type=2 / message_state=2 Mensaje de bot que indica finalización). */
    suspend fun sendMessage(
        botToken: String,
        toUserId: String,
        text: String,
        contextToken: String?,
        baseUrl: String = DEFAULT_BASE_URL,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${baseUrl.trimEnd('/')}/ilink/bot/sendmessage"
            val msg = buildJsonObject {
                put("to_user_id", toUserId)
                put("client_id", UUID.randomUUID().toString())
                put("message_type", 2)
                put("message_state", 2)
                putJsonArray("item_list") {
                    addJsonObject {
                        put("type", 1)
                        putJsonObject("text_item") { put("text", text) }
                    }
                }
                if (!contextToken.isNullOrBlank()) put("context_token", contextToken)
            }
            val body = buildJsonObject { put("msg", msg) }.toString()
            val resp = request(
                "POST",
                url,
                body,
                headers = mapOf(
                    "Authorization" to "Bearer $botToken",
                    "AuthorizationType" to "ilink_bot_token",
                ),
            ).getOrThrow()
            val obj = runCatching { AppJson.parseToJsonElement(resp).jsonObject }.getOrNull()
            val ret = obj?.get("ret")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            if (ret != null && ret != 0) error("iLink send ret=$ret: ${resp.take(200)}")
        }
    }

    /**
     * Analizar matriz de mensajes(solo message_type=1 mensajes de usuario).
     *
     * v2.0.1: Dividir por artículo — texto/Entrada de voz a texto,fotos/vídeo/Archivo adjunto [MediaRef] disponible para descargar;
     * La transcripción de voz proviene del servidor iLink(voice_item.text),No se requiere ASR local.
     */
    private fun parseMessages(array: JsonArray): List<InboundMsg> {
        val result = mutableListOf<InboundMsg>()
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            val type = obj["message_type"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
            if (type != 1) continue
            val from = obj["from_user_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (from.isBlank()) continue
            val contextToken = obj["context_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val items = obj["item_list"] as? JsonArray ?: continue
            for (itemElement in items) {
                val itemObj = itemElement as? JsonObject ?: continue
                val itemType = itemObj["type"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
                when (itemType) {
                    1 -> { // TEXT
                        val text = itemObj["text_item"]?.jsonObject
                            ?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (text.isNotBlank()) {
                            result += InboundMsg(
                                fromUserId = from,
                                text = text,
                                contextToken = contextToken,
                            )
                        }
                    }
                    3 -> { // VOICE — El servidor viene con transcodificación ASR
                        val asr = itemObj["voice_item"]?.jsonObject
                            ?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                        result += InboundMsg(
                            fromUserId = from,
                            text = asr.ifBlank { "[Voz]" },
                            contextToken = contextToken,
                        )
                    }
                    2, 4, 5 -> { // IMAGE / FILE / VIDEO
                        val kind = when (itemType) {
                            2 -> "image"
                            5 -> "video"
                            else -> "file"
                        }
                        val sub = itemObj["${kind}_item"]?.jsonObject
                        val media = sub?.get("media")?.jsonObject
                        // IMAGE La aes_key se puede colocar en image_item.aeskey en formato hexadecimal sin formato.
                        val aesKey = media?.get("aes_key")?.jsonPrimitive?.contentOrNull.orEmpty()
                            .ifBlank {
                                sub?.get("aeskey")?.jsonPrimitive?.contentOrNull
                                    ?.let { hex -> runCatching { hexToBase64Key(hex) }.getOrDefault("") }
                                    .orEmpty()
                            }
                        result += InboundMsg(
                            fromUserId = from,
                            text = "",
                            contextToken = contextToken,
                            media = MediaRef(
                                kind = kind,
                                aesKeyBase64 = aesKey,
                                encryptedQueryParam = media?.get("encrypt_query_param")
                                    ?.jsonPrimitive?.contentOrNull.orEmpty(),
                                fullUrl = media?.get("full_url")
                                    ?.jsonPrimitive?.contentOrNull.orEmpty(),
                                fileName = sub?.get("file_name")
                                    ?.jsonPrimitive?.contentOrNull.orEmpty(),
                                durationMs = sub?.get("duration_ms")
                                    ?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                            ),
                        )
                    }
                    else -> continue
                }
            }
        }
        return result
    }

    /**
     * v2.0.1: Descargar y descifrar la carga útil de los medios(IMAGE/VOICE/VIDEO/FILE).
     *
     * full_url Prioridad,De lo contrario vete `{baseUrl}/download?encrypted_query_param=...`(CDN El enlace viene con parámetros de autenticación.).
     * Leer en la memoria de una vez,Sólo apto para medios pequeños.(fotos/Voz);Úselo para archivos grandes [downloadMediaToFile].
     */
    suspend fun downloadMedia(media: MediaRef, baseUrl: String = DEFAULT_BASE_URL): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            require(media.aesKeyBase64.isNotBlank()) { "falta de medios aes_key" }
            val url = mediaUrl(media, baseUrl)
            val ciphertext = requestBytes(url).getOrThrow()
            decryptAesEcb(ciphertext, media.aesKeyBase64)
        }
    }

    /**
     * v2.x cierre del legado:Transmita descargas y descifre medios en archivos(Escribe sobre la marcha,No acumule todo el archivo en la memoria).
     *
     * con [downloadMedia](Desechable readBytes)diferente:Ajustar el flujo de bytes de respuesta en CipherInputStream,
     * Descifrar y escribir disco por bloque;El número de bytes de texto plano escritos excede [maxBytes] Cancele inmediatamente y elimine el archivo de trabajo en progreso.
     * Vídeo adecuado para WeChat entrante/Archivos y otros medios de gran volumen.
     *
     * Cancelación de rutina(Como withTimeoutOrNull timeout)será retirado en el bucle de bloque,Y lanza CancellationException hacia arriba.
     *
     * @return éxito:Número de bytes de texto plano escritos;falló:Result.failure(exceder el límite para [MediaTooLargeException])
     */
    suspend fun downloadMediaToFile(media: MediaRef, targetFile: File, maxBytes: Long, baseUrl: String = DEFAULT_BASE_URL): Result<Long> =
        withContext(Dispatchers.IO) {
            val ctx = coroutineContext
            try {
                require(media.aesKeyBase64.isNotBlank()) { "falta de medios aes_key" }
                val url = mediaUrl(media, baseUrl)
                val key = parseAesKey(media.aesKeyBase64)
                val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding").apply {
                    init(
                        javax.crypto.Cipher.DECRYPT_MODE,
                        javax.crypto.spec.SecretKeySpec(key, "AES"),
                    )
                }
                targetFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                val request = Request.Builder().url(url).get().build()
                val total = HTTP.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body ?: error("El cuerpo de la respuesta está vacío.")
                    body.byteStream().use { raw ->
                        javax.crypto.CipherInputStream(raw, cipher).use { decrypted ->
                            targetFile.outputStream().buffered().use { out ->
                                val buffer = ByteArray(COPY_BUFFER_BYTES)
                                var written = 0L
                                while (true) {
                                    ctx.ensureActive()
                                    val read = decrypted.read(buffer)
                                    if (read < 0) break
                                    written += read
                                    if (written > maxBytes) {
                                        throw MediaTooLargeException(
                                            "Los medios exceden el límite de tamaño(${maxBytes / 1024 / 1024}MB)",
                                        )
                                    }
                                    out.write(buffer, 0, read)
                                }
                                out.flush()
                                written
                            }
                        }
                    }
                }
                Result.success(total)
            } catch (e: kotlinx.coroutines.CancellationException) {
                runCatching { if (targetFile.exists()) targetFile.delete() }
                throw e
            } catch (e: Exception) {
                runCatching { if (targetFile.exists()) targetFile.delete() }
                Result.failure(e)
            }
        }

    /** URL de descarga de medios de construcción: full_url tiene prioridad,De lo contrario, vaya al punto final de descarga de CDN. */
    private fun mediaUrl(media: MediaRef, baseUrl: String): String {
        val full = media.fullUrl.trim()
        if (full.isNotBlank()) return full
        require(media.encryptedQueryParam.isNotBlank()) { "A los medios les faltan parámetros de descarga" }
        return "${baseUrl.trimEnd('/')}/download?encrypted_query_param=" +
            java.net.URLEncoder.encode(media.encryptedQueryParam, "UTF-8")
    }

    /** Descarga binaria(GET,Sin encabezado de autenticación adicional — CDN Los parámetros vienen con autenticación.). */
    private fun requestBytes(url: String): Result<ByteArray> = runCatching {
        val request = Request.Builder().url(url).get().build()
        HTTP.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            resp.body.bytes()
        }
    }

    /** AES-128-ECB + PKCS7 Descifrar(aes_key apoyo base64(16 raw bytes) con base64(hex 32 chars) Dos codificaciones). */
    private fun decryptAesEcb(ciphertext: ByteArray, aesKeyBase64: String): ByteArray {
        val key = parseAesKey(aesKeyBase64)
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
        )
        return cipher.doFinal(ciphertext)
    }

    private fun parseAesKey(aesKeyBase64: String): ByteArray {
        val decoded = android.util.Base64.decode(aesKeyBase64, android.util.Base64.DEFAULT)
        if (decoded.size == 16) return decoded
        if (decoded.size == 32) return hexToBytes(String(decoded, Charsets.US_ASCII))
        error("aes_key longitud anormal: ${decoded.size} bytes")
    }

    /** originales hex(32 personaje) → base64(16 raw bytes);Conversión compatible para image_item.aeskey. */
    private fun hexToBase64Key(hex: String): String {
        val bytes = hexToBytes(hex)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex longitud ilegal: ${hex.length}" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Realizar una solicitud HTTP;Excepciones de lanzamiento que no son 2xx con fragmentos de respuesta. */
    private fun request(method: String, url: String, body: String? = null, headers: Map<String, String> = emptyMap()): Result<String> =
        runCatching {
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
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

    /** ID de dispositivo generado aleatoriamente para cada solicitud(Requisitos del acuerdo,base64). */
    private fun randomUin(): String {
        val bytes = ByteArray(12).also { java.util.Random().nextBytes(it) }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }
}

/**
 * v2.x cierre del legado:Se lanza cuando la descarga de transmisión de medios excede el límite de tamaño máximo.
 *
 * suministro [WeClawClient.downloadMediaToFile] distinción de la persona que llama"por encima del límite"con"red/El análisis falló",
 * El primero solo registra texto de marcador de posición.,No se conservan documentos de trabajo en curso.
 */
internal class MediaTooLargeException(message: String) : Exception(message)

/**
 * v2.0: La caché context_token del mensaje entrante más reciente.
 *
 * iLink El protocolo requiere que el mensaje enviado de vuelta contenga el mensaje fuente. context_token;
 * El receptor escribe cuando se recibe un mensaje.,El remitente lee la devolución de datos.
 */
internal object WeClawContextCache {
    private val tokens = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun put(userId: String, token: String) {
        if (userId.isNotBlank() && token.isNotBlank()) tokens[userId] = token
    }

    fun get(userId: String): String? = tokens[userId]
}
