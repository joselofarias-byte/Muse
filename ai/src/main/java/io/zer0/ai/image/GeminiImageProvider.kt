package io.zer0.ai.image

import io.zer0.ai.RefImageUrlValidator
import io.zer0.ai.core.ProviderHttpSupport
import io.zer0.ai.gemini.GeminiProvider
import io.zer0.common.AppJson
import io.zer0.common.ErrorCode
import io.zer0.common.Logger
import io.zer0.common.toMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * v1.0.18 Seguimiento (B14-2): Google Gemini Proveedor de generación de imágenes nativas.
 *
 * antes de eso,Gemini Configurar y listo [OpenAIImageProvider] Ten todo en cuenta(Solicitar /images/generations,Si el protocolo es incompatible, fallará.),
 * o por capa de aplicación [io.zer0.muse.ui.chat.ImageGenCoordinator] Tome el camino multimodal de streamChat.
 * Esta clase le permite a Géminis tener independencia [ImageProvider] darse cuenta,Transitable [ImageProviderRegistry] Enrutamiento normal,
 * Consistente con el adaptador Agnes/OpenAI.
 *
 * API(Google Generación de imágenes nativas,Modelos como `gemini-2.5-flash-image` / Nano Banana):
 *  - punto final: POST {baseUrl}/models/{model}:generateContent(Mismo punto final que el chat,Distinguir por generaciónConfig)
 *  - Cuerpo de la solicitud:
 *      { "contents":[{"role":"user","parts":[{"text": prompt}, {"inlineData":{...}}(Imagen de referencia)]}],
 *        "generationConfig": { "responseModalities": ["TEXT","IMAGE"] } }
 *  - respuesta: candidates[0].content.parts[].inlineData.{mimeType,data}(base64,Ninguno dato: prefijo)
 *  - Retorno sincrónico(Sin tareas asincrónicas),supportsAsync=false
 *  - El gráfico generado por el gráfico se implementa a través del gráfico de entrada inlineData del mismo punto final.,supportsImageEdit=true
 *
 * Vertex AI:
 *  - punto final/Reutilización de autenticación [GeminiProvider.buildUrl] / [GeminiProvider.resolveToken],
 *    Sobrescribir automáticamente la cuenta de servicio(OAuth Bearer),Express Mode(API Key),generativelanguage(API Key)Tres modos,
 *    Evite que dos conjuntos de implementaciones se desvíen a medida que evoluciona el protocolo.
 *
 * Análisis y reutilización de imágenes de referencia. [AgnesImageProvider.resolveReferenceImage](data:/http(s)/file → base64,
 * Con límite de tamaño de suma de comprobación SSRF);Banda base64 devuelta `mimeType|` prefijo entregado [ImageService] URI de datos deletreados.
 */
class GeminiImageProvider(
    private val client: OkHttpClient,
    /**
     * G4: http(s) Verificador previo de la SSRF antes de descargar la imagen de referencia(Mismo calibre que Agnes/OpenAI),
     * Inyectado por el ensamblaje Koin de la capa de aplicación,null revertir el viejo comportamiento(Descarga directa,Sin verificación salto a salto).
     */
    private val referenceImageUrlValidator: RefImageUrlValidator? = null,
) : ImageProvider {

    override val providerId: String = PROVIDER_ID
    override val supportsImageEdit: Boolean = true
    override val supportsAsync: Boolean = false

    override suspend fun submit(request: ImageGenRequest): ImageSubmitResult = withContext(Dispatchers.IO) {
        val config = request.config
            ?: error(ErrorCode.IMAGE_API_KEY_MISSING.toMessage())
        if (config.apiKey.isBlank() && !config.allowMissingApiKey) {
            error(ErrorCode.IMAGE_API_KEY_MISSING.toMessage())
        }
        val modelId = request.model.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_ID

        try {
            // Imagen de referencia → base64(Ninguno dato: prefijo):El análisis falló/por encima del límite/Acceder a la intranet generará un error comercial,vomitar como está
            val refBase64 = request.referenceImages.map { ref ->
                AgnesImageProvider.resolveReferenceImage(ref, referenceImageUrlValidator)
            }
            val body = buildImageRequestBody(request.prompt, refBase64)

            // Reutilizar puntos finales de GeminiProvider/Análisis de autenticación(Incluye Vertex Express / Número de servicio OAuth / generativelanguage)
            val endpoint = GeminiProvider(config)
            val url = endpoint.buildUrl(model = modelId, stream = false)
            val token = endpoint.resolveToken()

            val httpRequest = Request.Builder()
                .url(url)
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            Logger.i(TAG, "submit: model=$modelId refs=${refBase64.size} vertex=${url.contains("aiplatform")}")

            exec(httpRequest).use { r ->
                if (!r.isSuccessful) {
                    val errBody = ProviderHttpSupport.readBodyCapped(r)
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
                        if (errBody.isNotBlank()) append(": ").append(errBody)
                    }
                    Logger.w(TAG, "gemini image HTTP ${r.code}")
                    error(msg)
                }
                // B-03: contentLength desconocido(chunked)mismo límite de tiempo — lectura en streaming,Interrumpir si se excede el límite
                val (respBody, overLimit) = ProviderHttpSupport.readBodyCappedStreaming(r, MAX_RESPONSE_BODY_BYTES)
                if (overLimit) {
                    error(ErrorCode.IMAGE_RESPONSE_TOO_LARGE.toMessage(MAX_RESPONSE_BODY_BYTES / 1024 / 1024))
                }
                if (respBody.isBlank()) error(ErrorCode.IMAGE_EMPTY_RESPONSE.toMessage())
                // Intercepción de seguridad de nivel rápido:Dar razones claras en lugar de"Sin resultados"
                val blockReason = runCatching {
                    AppJson.parseToJsonElement(respBody).jsonObject["promptFeedback"]
                        ?.jsonObject?.get("blockReason")?.jsonPrimitive?.content
                }.getOrNull()
                if (!blockReason.isNullOrBlank()) {
                    error(ErrorCode.IMAGE_GEN_FAILED.toMessage("safety: $blockReason"))
                }
                val images = parseInlineImages(respBody)
                if (images.isEmpty()) error(ErrorCode.IMAGE_NO_RESULTS.toMessage())
                ImageSubmitResult(images = images, isAsync = false)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // error() Los errores comerciales arrojados se propagan sin cambios
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "gemini image failed: ${e.message}")
            error(ErrorCode.IMAGE_GEN_FAILED.toMessage(e.message ?: ""))
        }
    }

    override suspend fun poll(taskId: String): ImagePollResult {
        error("Gemini La generación de imágenes no admite tareas asincrónicas.: $taskId")
    }

    /**
     * Construya el cuerpo de solicitud generateContent.
     *
     * La imagen de referencia se inserta como parte inlineData.(texto primero,Consistente con el orden recomendado por Géminis),
     * responseModalities=["TEXT","IMAGE"] Habilite la salida de imágenes.
     */
    internal fun buildImageRequestBody(prompt: String, referenceImagesBase64: List<String>): String {
        val parts = buildJsonArray {
            add(buildJsonObject { put("text", prompt) })
            referenceImagesBase64.forEach { b64 ->
                add(
                    buildJsonObject {
                        putJsonObject("inlineData") {
                            put("mimeType", inferMimeType(b64))
                            put("data", b64)
                        }
                    },
                )
            }
        }
        return buildJsonObject {
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("parts", parts)
                    },
                )
            }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") {
                    add(JsonPrimitive("TEXT"))
                    add(JsonPrimitive("IMAGE"))
                }
            }
        }.toString()
    }

    /**
     * analizar candidates[0].content.parts[].inlineData para la lista de imágenes.
     *
     * Consistente con Agnes/OpenAI:Sólo acepta cadenas escalares,Filtrar JSON nulo/número/booleano/cadena vacía.
     * Banda base64 devuelta `mimeType|` prefijo([ImageService] Tira al deletrear el URI de datos).
     */
    internal fun parseInlineImages(body: String): List<GeneratedImage> {
        val root = AppJson.parseToJsonElement(body).jsonObject
        val parts = root["candidates"]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray
            ?: return emptyList()
        return parts.mapNotNull { part ->
            val inline = part.jsonObject["inlineData"]?.jsonObject ?: return@mapNotNull null
            val data = (inline["data"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content?.takeIf { it.isNotBlank() && it != "null" }
                ?: return@mapNotNull null
            val mime = (inline["mimeType"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content?.takeIf { it.isNotBlank() }
                ?: "image/png"
            GeneratedImage(base64 = "$mime|$data")
        }
    }

    /** Inferir imagen a partir de bytes mágicos del encabezado base64 MIME(readReferenceImage No lleva información de tipo.). */
    private fun inferMimeType(base64: String): String {
        val head = base64.take(16).uppercase()
        return when {
            head.startsWith("IVBORW0") -> "image/png" // PNG: iVBORw0K
            head.startsWith("/9J/") -> "image/jpeg" // JPEG: /9j/
            head.startsWith("UKLGR") -> "image/webp" // WebP: UklGR
            head.startsWith("R0LGOD") -> "image/gif" // GIF: R0lGOD
            else -> "image/png"
        }
    }

    private suspend fun exec(request: Request): Response = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
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

    companion object {
        private const val TAG = "GeminiImageProvider"

        /** Provider Identificador único. */
        const val PROVIDER_ID = "gemini"

        /**
         * Modelo de dibujo predeterminado: Gemini 2.5 Flash Image(nombre en clave Nano Banana).
         * Solo cuando la persona que llama no pasa explícitamente el modelo y no hay un modelo de salida de imagen en ProviderConfig.models.
         */
        const val DEFAULT_MODEL_ID = "gemini-2.5-flash-image"

        /** Límite superior del cuerpo de respuesta exitosa 32MB(inlineData base64 De mayor tamaño). */
        private const val MAX_RESPONSE_BODY_BYTES = 32 * 1024 * 1024

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
