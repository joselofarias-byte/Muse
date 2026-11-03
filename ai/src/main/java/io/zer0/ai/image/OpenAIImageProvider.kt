package io.zer0.ai.image

import io.zer0.ai.RefImageUrlValidator
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderHttpSupport
import io.zer0.ai.core.ProviderKeyRotation
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.common.ErrorCode
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.common.toMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * v1.0.18: OpenAI Compatible con proveedor de generación de imágenes.
 *
 * Compatible con OpenAI oficial y todas las interfaces de dibujo de estaciones de transferencia compatibles con OpenAI. Separado del ImageService original,
 * Conservar verificación de parámetros maduros, reintentos, análisis de errores y diagramas de referencia.(multipart)lógica.
 *
 * punto final:
 *  - Cuadro vicenciano: POST {baseUrl}/images/generations
 *  - Tu Sheng Tu: POST {baseUrl}/images/edits(gpt-image Serie / dall-e-2)
 *
 * Diferencias de comportamiento del modelo:
 *  - DALL-E 3: tamaño fijo 1024x1024 / 1792x1024 / 1024x1792,No se admiten imágenes de referencia.;
 *  - DALL-E 2: Imágenes de referencia de soporte(/images/edits),n Accesible 10;
 *  - gpt-image-1: Imágenes de referencia de soporte(/images/edits),El parámetro Response_Format no es compatible.
 *
 * fuente de ruta: ProviderSpecificConfig.OpenAI.imagesPath(Predeterminado /images/generations),
 * edits Path reemplaza el segmento de últimas generaciones con edits(Alinear la implementación antigua de ImageService).
 */
class OpenAIImageProvider(
    private val client: OkHttpClient,
    private val keyRotationFactory: (ProviderConfig) -> ProviderKeyRotation = { ProviderKeyRotation(it) },
    /**
     * G4: http(s) Verificador previo de la SSRF antes de descargar la imagen de referencia(Devuelve verdadero para indicar lanzamiento,false rechazar el URL).
     * con [AgnesImageProvider] Lo mismo:Inyectado por el ensamblaje Koin de la capa de aplicación(SsrfBridgeModule),
     * null revertir el viejo comportamiento(http(s) Descarga directa, sin verificación).
     */
    private val referenceImageUrlValidator: RefImageUrlValidator? = null,
) : ImageProvider {

    override val providerId: String = PROVIDER_ID
    override val supportsImageEdit: Boolean = true
    override val supportsAsync: Boolean = false

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun submit(request: ImageGenRequest): ImageSubmitResult = withContext(Dispatchers.IO) {
        val config = request.config
            ?: error(ErrorCode.IMAGE_API_KEY_MISSING.toMessage())
        if (config.apiKey.isBlank()) error(ErrorCode.IMAGE_API_KEY_MISSING.toMessage())

        val specific = (config.resolvedSpecific() as? ProviderSpecificConfig.OpenAI)
            ?: ProviderSpecificConfig.OpenAI()
        val effectiveModelId = request.model.takeIf { it.isNotBlank() }
            ?: specific.imageModel.takeIf { it.isNotBlank() }
            ?: ImageModelCatalog.DEFAULT_MODEL_ID
        val model = ImageModelCatalog.resolveById(effectiveModelId)

        // Verificación de parámetros(Reutilizar la antigua lógica ImageService.validateParams)
        val validated = validateParams(request, effectiveModelId, model)
        val hasReference = validated.referenceImages.isNotEmpty()
        if (hasReference && model != null && !model.supportsReferenceImage) {
            error(ErrorCode.IMAGE_UNSUPPORTED_MODEL.toMessage(effectiveModelId))
        }

        // ruta del punto final:Prioridad ProviderSpecificConfig.OpenAI.imagesPath
        val generationsPath = specific.imagesPath.trim().trim('/').ifBlank { "images/generations" }
        val editsPath = if (generationsPath.endsWith("generations")) {
            generationsPath.removeSuffix("generations").trimEnd('/') + "/edits"
        } else {
            "images/edits"
        }
        val path = if (hasReference) editsPath else generationsPath
        val baseUrl = config.resolvedBaseUrl()
        val url = "${baseUrl.trimEnd('/')}/$path"

        Logger.i(TAG, "submit: model=$effectiveModelId size=${validated.size} n=${validated.n} ref=$hasReference")

        // T1.2: Rotación de múltiples claves — Construido con clave efectiva header;429 Corta la siguiente clave y vuelve a intentarlo.
        val keyRotation = keyRotationFactory(config)
        val initialKey = keyRotation.effectiveApiKey()
        // Análisis de bytes de imagen de referencia.(Sólo se ejecuta cuando tiene Referencia,Consistente con el comportamiento original de buildEditsRequest)
        val editsRequest: Request? = if (hasReference) {
            buildEditsRequest(url, initialKey, validated, model)
        } else {
            null
        }
        val initialRequest = editsRequest ?: buildGenerationsRequest(url, initialKey, validated, model)

        try {
            var resp = execWithRetry(initialRequest)
            // T1.2: 429 Cuando la corriente sea limitada, cambie a la siguiente tecla e inténtelo de nuevo.(Solo escenarios de múltiples claves)
            if (resp.code == 429 && keyRotation.switchToNextKey()) {
                Logger.i(TAG, "openai image 429, Cambie a la siguiente clave e inténtelo de nuevo")
                resp.close()
                val retryKey = keyRotation.effectiveApiKey()
                val retryRequest = if (hasReference) {
                    buildEditsRequest(url, retryKey, validated, model)
                } else {
                    buildGenerationsRequest(url, retryKey, validated, model)
                }
                resp = execWithRetry(retryRequest)
            }
            resp.use { r ->
                if (!r.isSuccessful) {
                    val body = ProviderHttpSupport.readBodyCapped(r)
                    val openAiMsg = parseOpenAiError(body)
                    val hint = when (r.code) {
                        401, 403 -> ErrorCode.AUTH_FAILED.toMessage()
                        429 -> ErrorCode.RATE_LIMITED.toMessage()
                        in 500..599 -> ErrorCode.SERVICE_UNAVAILABLE.toMessage()
                        else -> null
                    }
                    val msg = buildString {
                        append(ErrorCode.IMAGE_GEN_FAILED.toMessage())
                        append(" HTTP ${r.code}")
                        hint?.let { append(" [").append(it).append("]") }
                        openAiMsg?.let { append(": ").append(it) }
                        if (hint == null && openAiMsg == null && body.isNotBlank()) {
                            append(": ").append(body)
                        }
                    }
                    Logger.w(TAG, "openai image HTTP ${r.code}")
                    error(msg)
                }
                val declaredLen = r.body?.contentLength() ?: -1L
                if (declaredLen > MAX_RESPONSE_BODY_BYTES) {
                    error(ErrorCode.IMAGE_RESPONSE_TOO_LARGE.toMessage(declaredLen / 1024 / 1024))
                }
                // B-03: chunked/La verificación de contentLength no surte efecto cuando no se declara la longitud,Utilice el límite de duración de la transmisión para leer el resultado final
                val (respBody, overLimit) = ProviderHttpSupport.readBodyCappedStreaming(r, MAX_RESPONSE_BODY_BYTES.toInt())
                if (overLimit) {
                    error(ErrorCode.IMAGE_RESPONSE_TOO_LARGE.toMessage(MAX_RESPONSE_BODY_BYTES / 1024 / 1024))
                }
                if (respBody.isBlank()) error(ErrorCode.IMAGE_EMPTY_RESPONSE.toMessage())
                val root = json.parseToJsonElement(respBody).jsonObject
                val data = root["data"]?.jsonArray
                    ?: error(ErrorCode.INVALID_RESPONSE.toMessage("missing_data"))
                if (data.isEmpty()) error(ErrorCode.IMAGE_NO_RESULTS.toMessage())
                val mime = model?.outputMime ?: "image/png"
                val images = data.mapNotNull { item ->
                    val obj = item.jsonObject
                    // B-01 (Enlace de imagen sin formato): Sólo acepta cadenas escalares — JSON null / Número/Booleano
                    // (JsonPrimitive.content Devuelve no cadenas tal como están,Como por ejemplo "123"/"true")Siempre filtrar,
                    // De lo contrario, deletrea "data:image/png;base64,123" foto falsa;literal "null" Las cadenas también se filtran.
                    val b64 = (obj["b64_json"] as? JsonPrimitive)
                        ?.takeIf { it.isString }
                        ?.content?.takeIf { it.isNotBlank() && it != "null" }
                    // B-01: url debe ser http(s) o datos:imagen/prefijo
                    val url = (obj["url"] as? JsonPrimitive)
                        ?.takeIf { it.isString }
                        ?.content?.takeIf { it.startsWith("http") || it.startsWith("data:image/") }
                    if (b64 == null && url == null) null else GeneratedImage(base64 = b64, url = url)
                }
                // Mantener mimo en el campo base64,Eliminado al deletrear el URI de datos por ImageService
                val withMime = images.map { img ->
                    if (img.base64 != null) img.copy(base64 = "$mime|${img.base64}") else img
                }
                ImageSubmitResult(images = withMime, isAsync = false)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "openai image failed: ${e.message}")
            error(ErrorCode.IMAGE_GEN_FAILED.toMessage(e.message ?: ""))
        }
    }

    override suspend fun poll(taskId: String): ImagePollResult {
        // OpenAI Generación y retorno de imágenes sincrónicamente.,No se admiten tareas asincrónicas
        error("OpenAI La generación de imágenes no admite tareas asincrónicas.: $taskId")
    }

    /**
     * Verificación de parámetros(Migrado de viejo ImageService.validateParams).
     * poner [ImageGenRequest] Normalizado para modelar restricciones de capacidad. [ValidatedParams].
     */
    private fun validateParams(request: ImageGenRequest, modelId: String, model: ImageModel?): ValidatedParams {
        if (model == null) {
            return ValidatedParams(
                prompt = request.prompt,
                model = modelId,
                size = request.size.ifBlank { "1024x1024" },
                quality = request.quality,
                style = request.style,
                n = 1,
                referenceImages = request.referenceImages,
                responseFormat = request.responseFormat,
            )
        }
        val size = request.size.takeIf { it in model.supportedSizes } ?: model.defaultSize
        val quality = request.quality
            .takeIf { it.isBlank() || it in model.supportedQualities }
            ?: model.defaultQuality
        val style = request.style
            .takeIf { it.isBlank() || it in model.supportedStyles }
            ?: model.defaultStyle
        val responseFormat = when {
            !model.supportsResponseFormatParam -> ""
            !model.supportsB64Json && request.responseFormat == "b64_json" -> "url"
            else -> request.responseFormat
        }
        return ValidatedParams(
            prompt = request.prompt,
            model = modelId,
            size = size,
            quality = quality,
            style = style,
            n = request.n.coerceIn(1, model.maxN),
            referenceImages = request.referenceImages,
            responseFormat = responseFormat,
        )
    }

    private fun buildGenerationsRequest(url: String, apiKey: String, params: ValidatedParams, model: ImageModel?): Request {
        val body = buildGenerationsBody(
            prompt = params.prompt,
            n = params.n,
            size = params.size,
            model = params.model,
            quality = params.quality,
            style = params.style,
            responseFormat = params.responseFormat,
        )

        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    }

    private suspend fun buildEditsRequest(url: String, apiKey: String, params: ValidatedParams, model: ImageModel?): Request {
        val refUri = params.referenceImages.first()
        val imageBytes = resolveImageBytes(refUri)
            ?: error(ErrorCode.IMAGE_REFERENCE_DOWNLOAD_FAILED.toMessage("no_bytes"))
        val mime = inferImageMime(imageBytes)
        val ext = mime.substringAfter('/')
        val imageBody = imageBytes.toRequestBody(mime.toMediaType())
        val promptBody = params.prompt.toRequestBody("text/plain".toMediaType())

        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", "reference.$ext", imageBody)
            .addFormDataPart("prompt", null, promptBody)
            .addFormDataPart("n", params.n.toString())
            .addFormDataPart("size", params.size)
        if (params.responseFormat.isNotBlank()) {
            builder.addFormDataPart("response_format", params.responseFormat)
        }
        builder.addFormDataPart("model", params.model)

        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(builder.build())
            .build()
    }

    /**
     * Analizar el URI de la imagen de referencia en una matriz de bytes(Migrado de viejo ImageService.resolveImageBytes).
     * Admite URL http/https,data URI, URI del archivo local.content:// Convierta el URI de datos por adelantado desde la capa de aplicación.
     */
    private suspend fun resolveImageBytes(uri: String): ByteArray? {
        return when {
            uri.startsWith("data:image") -> {
                val base64 = uri.substringAfter("base64,", "")
                if (base64.length > MAX_REF_IMAGE_BYTES * 4 / 3) {
                    error(ErrorCode.IMAGE_REFERENCE_TOO_LARGE.toMessage(base64.length / 1024 / 1024))
                }
                java.util.Base64.getDecoder().decode(base64)
            }
            uri.startsWith("content://") -> {
                error(ErrorCode.IMAGE_INVALID_URI.toMessage("content_uri"))
            }
            uri.startsWith("http") -> {
                // G4: Si el verificador de inyección no está vacío, primero pasará la verificación SSRF.(Intranet/dirección reservada → rechazar);
                // No inyectado(null)Vuelva al comportamiento anterior al descargar directamente.
                val validator = referenceImageUrlValidator
                if (validator != null && !validator.isAllowed(uri)) {
                    error(ErrorCode.IMAGE_INVALID_URI.toMessage("ssrf_blocked"))
                }
                val request = Request.Builder().url(uri).build()
                exec(request).use { resp ->
                    if (!resp.isSuccessful) {
                        error(ErrorCode.IMAGE_REFERENCE_DOWNLOAD_FAILED.toMessage("HTTP ${resp.code}"))
                    }
                    val len = resp.body?.contentLength() ?: -1L
                    if (len > MAX_REF_IMAGE_BYTES) {
                        error(ErrorCode.IMAGE_REFERENCE_TOO_LARGE.toMessage(len / 1024 / 1024))
                    }
                    val bytes = resp.body?.bytes()
                        ?: error(ErrorCode.IMAGE_REFERENCE_DOWNLOAD_FAILED.toMessage("empty"))
                    if (bytes.size > MAX_REF_IMAGE_BYTES) {
                        error(ErrorCode.IMAGE_REFERENCE_TOO_LARGE.toMessage(bytes.size / 1024 / 1024))
                    }
                    bytes
                }
            }
            else -> {
                // Reparación de auditoría (6.4): file Límite de tamaño del complemento de rama(Otras sucursales tienen comprobaciones MAX_REF_IMAGE_BYTES,
                // Solo aqui esta el directo readBytes,Archivos locales muy grandes OOM).
                val file = java.io.File(uri.removePrefix("file:"))
                if (!file.exists()) return null
                if (file.length() > MAX_REF_IMAGE_BYTES) {
                    error(ErrorCode.IMAGE_REFERENCE_TOO_LARGE.toMessage(file.length() / 1024 / 1024))
                }
                file.readBytes()
            }
        }
    }

    /** Inferir el tipo MIME de la imagen a partir del encabezado del byte(Migrado de viejo ImageService.inferImageMime). */
    private fun inferImageMime(bytes: ByteArray): String {
        if (bytes.size < 4) return "image/png"
        return when {
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
            bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() -> "image/gif"
            bytes.size >= 12 &&
                bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
                bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
                bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte() -> "image/webp"
            else -> "image/png"
        }
    }

    /** Intente analizar la estructura de error de OpenAI {"error":{"message","code","type"}}. */
    private fun parseOpenAiError(body: String): String? {
        if (body.isBlank()) return null
        return resultOf {
            val root = json.parseToJsonElement(body).jsonObject
            val err = root["error"]?.jsonObject ?: return@resultOf null
            err["message"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    private suspend fun exec(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    /**
     * Haga un reintento limitado en 429(1 veces,Migrado de viejo ImageService.execWithRetry).
     * Pago por visión de generación de imágenes,C-02: Sólo para fallas en las que se determina que no se incurrió en cargos(429 Limitación de corriente)Inténtalo de nuevo;
     * SocketTimeoutException(readTimeout)no lo intentes de nuevo — La solicitud puede haber sido recibida y generada por el servidor.
     * (Tiempo de espera de respuesta),El reintento resultará en deducciones repetidas.
     */
    private suspend fun execWithRetry(request: Request): Response {
        val resp = exec(request)
        if (resp.code != 429) return resp
        val retryAfter = resp.header("Retry-After")?.toLongOrNull()
        resp.close()
        delay(retryAfter?.let { it * 1000L } ?: 1000L)
        return exec(request)
    }

    /** Conjunto de parámetros verificado(Para transmisión interna). */
    private data class ValidatedParams(
        val prompt: String,
        val model: String,
        val size: String,
        val quality: String,
        val style: String,
        val n: Int,
        val referenceImages: List<String>,
        val responseFormat: String,
    )

    companion object {
        private const val TAG = "OpenAIImageProvider"

        /** R-TEST-20: Vincent gráfico solicita función pura del constructor del cuerpo. */
        @Suppress("LongParameterList")
        internal fun buildGenerationsBody(
            prompt: String,
            n: Int,
            size: String,
            model: String,
            quality: String,
            style: String,
            responseFormat: String,
        ): String = buildJsonObject {
            put("prompt", prompt)
            put("n", n)
            put("size", size)
            put("model", model)
            if (quality.isNotBlank()) put("quality", quality)
            if (style.isNotBlank()) put("style", style)
            if (responseFormat.isNotBlank()) put("response_format", responseFormat)
        }.toString()

        /** Provider Identificador único. */
        const val PROVIDER_ID = "openai"

        /** El tamaño máximo de imagen de referencia es de 10 MB. */
        private const val MAX_REF_IMAGE_BYTES = 10L * 1024 * 1024

        /** Tamaño máximo del cuerpo de respuesta exitosa 20MB(Evitar picos de memoria de múltiples imágenes b64_json). */
        private const val MAX_RESPONSE_BODY_BYTES = 20L * 1024 * 1024
    }
}
