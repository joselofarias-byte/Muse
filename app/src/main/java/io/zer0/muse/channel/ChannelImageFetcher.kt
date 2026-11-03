package io.zer0.muse.channel

import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Descarga de imágenes entrantes del canal.
 *
 * Antecedentes: anteriormente, solo el puente WeChat descargaba imágenes y las convertía a base64, mientras Feishu/QQ y otros terminales analizaban los mensajes.
 * Escribe la imagen directamente como `"[fotos]"` Literal. El resultado es que el modelo sólo ve cuatro palabras y la ayuda visual no puede obtener los bytes.
 * (Su condición previa es `mediaBase64` no vacío), los dos síntomas tienen el mismo origen.
 *
 * Aquí, complete el "Obtener identidad del archivo adjunto" según la plataforma. → Descargar → Comprimido en una sección base64",
 * El error siempre se degradará a texto de marcador de posición, no se generará ninguna excepción y el bucle de recepción no se bloqueará.
 */
internal object ChannelImageFetcher {
    private const val TAG = "ChannelImageFetcher"

    /** Se agotó el tiempo de descarga de una sola imagen. */
    private const val TIMEOUT_MS = 15_000L

    /**
     * El límite máximo para una sola imagen.
     *
     * El límite superior real está determinado por la tabla de capacidades de cada canal; aquí sólo evitamos la situación extrema de "la tabla de capacidades no está declarada".
     * Tome el valor máximo indicado en la declaración de cada canal para asegurarse de que no sea más restrictivo que las restricciones de la plataforma.
     */
    private const val FALLBACK_MAX_BYTES = 50L * 1024 * 1024

    /** Obtenga el límite superior de bytes de imágenes en un determinado canal y modo de entrada. */
    private fun imageLimit(platform: ChannelPlatform, mode: MediaInputMode): Long {
        val limit = platform.capabilities.mediaCapabilities.limitFor(mode, MediaKind.IMAGE)
        return if (limit > 0L) limit else FALLBACK_MAX_BYTES
    }

    private val JSON: okhttp3.MediaType =
        "application/json; charset=utf-8".toMediaType()

    private val HTTP: OkHttpClient by lazy {
        io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Fotos de Feishu:`GET {openBase}/open-apis/im/v1/images/{image_key}`, con tenencia_acceso_token.
     *
     * @return Base comprimida64; devuelve nulo si algún paso falla.
     */
    suspend fun fetchFeishu(config: ChannelConfig, imageKey: String, tokenProvider: (ChannelConfig) -> Pair<String, Long>): String? =
        withContext(Dispatchers.IO) {
            if (imageKey.isBlank()) return@withContext null
            runCatching {
                val maxBytes = imageLimit(ChannelPlatform.FEISHU, MediaInputMode.BUFFER)
                val (token, _) = tokenProvider(config)
                val base = if (config.region == "intl") {
                    "https://open.larksuite.com"
                } else {
                    "https://open.feishu.cn"
                }
                val request = Request.Builder()
                    .url("$base/open-apis/im/v1/images/$imageKey")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                val bytes = withTimeoutOrNull(TIMEOUT_MS) {
                    HTTP.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            error("HTTP ${resp.code}")
                        }
                        val len = resp.body.contentLength()
                        if (len > maxBytes) error("Imagen demasiado grande: $len > $maxBytes")
                        resp.body.bytes()
                    }
                }
                if (bytes == null) {
                    Logger.w(TAG, "Tiempo de espera de descarga de imágenes de Feishu")
                    return@runCatching null
                }
                ChannelMediaUtils.toCompactImageBase64(bytes)
            }.onFailure { e ->
                Logger.w(TAG, "Falló la adquisición de la imagen de Feishu: ${e.message}")
            }.getOrNull()
        }

    /**
     * QQ Imagen:attachments Proporcione la dirección directamente aquí y descárguela directamente.
     *
     * La dirección es urgente y debe descargarse inmediatamente después de recibir el mensaje. No se puede almacenar en caché ni procesar de otro modo más adelante.
     *
     * @return Base comprimida64; devuelve nulo si algún paso falla.
     */
    suspend fun fetchQq(imageUrl: String): String? = withContext(Dispatchers.IO) {
        if (imageUrl.isBlank()) return@withContext null
        runCatching {
            val maxBytes = imageLimit(ChannelPlatform.QQ, MediaInputMode.PUBLIC_URL)
            val request = Request.Builder().url(imageUrl).get().build()
            val bytes = withTimeoutOrNull(TIMEOUT_MS) {
                HTTP.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        error("HTTP ${resp.code}")
                    }
                    val len = resp.body.contentLength()
                    if (len > maxBytes) error("Imagen demasiado grande: $len > $maxBytes")
                    resp.body.bytes()
                }
            }
            if (bytes == null) {
                Logger.w("QQ", "Tiempo de espera de descarga de imágenes")
                return@runCatching null
            }
            ChannelMediaUtils.toCompactImageBase64(bytes)
        }.onFailure { e ->
            Logger.w("QQ", "Error en la adquisición de imágenes: ${e.message}")
        }.getOrNull()
    }

    /**

     * Obtenga la dirección de la imagen de los archivos adjuntos del mensaje QQ.

     *

     * compatibles `url` con `file_url` Dos formas de escribir campos.

     */

    fun qqImageUrl(attachment: kotlinx.serialization.json.JsonObject?): String? {
        val contentType = attachment

            ?.get("content_type")

            ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            .orEmpty()

        return if (!contentType.startsWith("image/")) {
            null
        } else {
            listOf("url", "file_url", "image_url")
                .asSequence()
                .mapNotNull { key ->
                    runCatching { attachment?.get(key)?.jsonPrimitive?.content }
                        .getOrNull()

                        ?.trim()
                }
                .firstOrNull { it.isNotBlank() }
        }
    }

    /**
     * Imágenes de DingTalk:downloadCode + access_token Cambie la dirección temporal y descargue nuevamente.
     *
     * @return Base comprimida64; devuelve nulo si algún paso falla.
     */
    suspend fun fetchDingTalk(apiBase: String, accessToken: String, downloadCode: String): String? = withContext(Dispatchers.IO) {
        if (downloadCode.isBlank() || accessToken.isBlank()) return@withContext null
        runCatching {
            val maxBytes = imageLimit(ChannelPlatform.DINGTALK, MediaInputMode.BUFFER)
            val body = "{\"downloadCode\":\"$downloadCode\"}"
            val req = Request.Builder()
                .url("$apiBase/robot/messages/download")
                .header("x-acs-dingtalk-access-token", accessToken)
                .post(body.toRequestBody(JSON))
                .build()
            val url = withTimeoutOrNull(TIMEOUT_MS) {
                HTTP.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val obj = io.zer0.common.AppJson.parseToJsonElement(resp.body.string())
                        .jsonObject
                    obj["downloadUrl"]?.jsonPrimitive?.content.orEmpty()
                }
            }
            if (url.isNullOrBlank()) {
                Logger.w(TAG, "DingTalk no devolvió la dirección de descarga")
                return@runCatching null
            }
            val imgReq = Request.Builder().url(url).get().build()
            val bytes = withTimeoutOrNull(TIMEOUT_MS) {
                HTTP.newCall(imgReq).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val len = resp.body.contentLength()
                    if (len > maxBytes) error("Imagen demasiado grande: $len > $maxBytes")
                    resp.body.bytes()
                }
            }
            if (bytes == null) {
                Logger.w(TAG, "Tiempo de espera de descarga de imágenes de DingTalk")
                return@runCatching null
            }
            ChannelMediaUtils.toCompactImageBase64(bytes)
        }.onFailure { e ->
            Logger.w(TAG, "No se pudieron obtener imágenes de DingTalk: ${e.message}")
        }.getOrNull()
    }

    /** Obtenga el código de descarga de la imagen de la devolución de llamada del robot DingTalk. */
    fun dingtalkDownloadCode(payload: kotlinx.serialization.json.JsonObject): String? {
        val msgType = runCatching {
            payload["msgtype"]?.jsonPrimitive?.content.orEmpty()
        }.getOrDefault("")
        if (msgType != "picture") return null
        return runCatching {
            (payload["content"] as? kotlinx.serialization.json.JsonObject)
                ?.get("downloadCode")?.jsonPrimitive?.content?.trim().orEmpty()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Obtenga image_key del contenido del mensaje de Feishu.
     *
     * content en forma de `{"image_key":"img_v2_xxx"}`.
     */
    fun feishuImageKey(contentJson: String): String? = runCatching {
        AppJson.parseToJsonElement(contentJson).jsonObject
            .get("image_key")?.jsonPrimitive?.content?.trim().orEmpty()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Descargue cualquier byte multimedia sin compresión.
     *
     * paseo fotográfico [toCompactImageBase64] Volverá a codificar el vídeo./La documentación no puede hacer esto,
     * Entonces, la ruta de envío usa esto para obtener directamente los bytes originales.
     */
    suspend fun downloadRaw(url: String, maxBytes: Long = 50L * 1024 * 1024): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        runCatching {
            val request = Request.Builder().url(url).get().build()
            withTimeoutOrNull(TIMEOUT_MS) {
                HTTP.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val len = resp.body.contentLength()
                    if (len > maxBytes) error("Los medios son demasiado grandes.: $len > $maxBytes")
                    resp.body.bytes()
                }
            }
        }.onFailure { e ->
            Logger.w(TAG, "Falló la descarga de medios: ${e.message}")
        }.getOrNull()
    }
}
