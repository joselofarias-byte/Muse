package io.zer0.ai.image

import io.zer0.ai.RefImageUrlValidator
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderHttpSupport
import io.zer0.ai.core.ProviderKeyRotation
import io.zer0.common.ErrorCode
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.common.toMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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
 * v1.0.18: Agnes AI Proveedor de generación de imágenes.
 *
 * Puntos de implementación:
 *  - punto final: POST {baseUrl}/images/generations(baseUrl Predeterminado https://apihub.agnes-ai.com/v1)
 *  - Autenticación: Authorization: Bearer ${apiKey}
 *  - Cuerpo de la solicitud: { model, prompt, size, extra_body: { response_format: "b64_json", image: [...] } }
 *  - mapeo de tamaño: proporción("1:1" espera)→ Valor de píxel fijo;También se aceptan valores de píxeles directos si están dentro del conjunto de soporte.
 *  - Modelo predeterminado: agnes-image-2.1-flash
 *  - supportsImageEdit = true(Pase la imagen de referencia a través de extra_body.image)
 *  - supportsAsync = false(Retorno sincrónico)
 *  - Análisis de respuesta: data[i].b64_json → base64, data[i].url → url;Ambos son compatibles.
 *  - Manejo de errores: HTTP Análisis no 2xx de err.error.message.
 *
 * Diferencias clave con la ruta de compatibilidad de OpenAI:
 *  - No vayas al punto final /images/edits,La imagen se pasa a través del campo extra_body.image. /images/generations;
 *  - response_format Colóquelo dentro de extra_body en lugar de en la parte superior del cuerpo de la solicitud.;
 *  - size ratio de aceptación(Como por ejemplo "3:2"),Este proveedor lo asigna a un valor de píxel y luego lo envía.
 */
class AgnesImageProvider(
    private val client: OkHttpClient,
    private val keyRotationFactory: (ProviderConfig) -> ProviderKeyRotation = { ProviderKeyRotation(it) },
    /**
     * G4: http(s) Verificador previo de la SSRF antes de descargar la imagen de referencia(Devuelve verdadero para indicar lanzamiento,false rechazar el URL).
     * ai El módulo no depende de la aplicación. SsrfGuard(Orientación del módulo app→ai),Inyectado por el ensamblaje Koin de la capa de aplicación
     * (SsrfBridgeModule Registre SsrfGuard.refImageUrlValidator para [RefImageUrlValidator]);
     * null Cuando cada rama revierte el comportamiento anterior(http(s) Descarga directa, sin verificación salto a salto),Permanece desconectado.
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
        if (config.apiKey.isBlank()) {
            error(ErrorCode.IMAGE_API_KEY_MISSING.toMessage())
        }

        val baseUrl = config.resolvedBaseUrl().trimEnd('/')
        val url = "$baseUrl/images/generations"
        val modelId = request.model.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_ID
        val size = resolveSize(request.size)
        val refImages = request.referenceImages
            // G4: Inyectar validador SSRF — http(s) Imágenes de referencia antes de descargar. SsrfGuard(Pasado por el ensamblado de la aplicación)
            .map { resolveReferenceImage(it, referenceImageUrlValidator) }
            .takeIf { it.isNotEmpty() }

        // extra_body Poner Response_Format con imagen de referencia.
        val body = buildJsonObject {
            put("model", modelId)
            put("prompt", request.prompt)
            put("size", size)
            putJsonObject("extra_body") {
                put("response_format", request.responseFormat.ifBlank { "b64_json" })
                if (refImages != null) {
                    putJsonArray("image") {
                        refImages.forEach { add(JsonPrimitive(it)) }
                    }
                }
            }
        }.toString()

        Logger.i(TAG, "submit: model=$modelId size=$size refs=${refImages?.size ?: 0}")

        // T1.2: Rotación de múltiples claves — Construido con clave efectiva header;429 Corta la siguiente clave y vuelve a intentarlo.
        val keyRotation = keyRotationFactory(config)
        fun buildHttpRequest(apiKey: String): Request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val initialResp = exec(buildHttpRequest(keyRotation.effectiveApiKey()))
            var resp = initialResp
            // T1.2: 429 Cuando la corriente sea limitada, cambie a la siguiente tecla e inténtelo de nuevo.(Solo escenarios de múltiples claves)
            if (resp.code == 429 && keyRotation.switchToNextKey()) {
                Logger.i(TAG, "agnes image 429, Cambie a la siguiente clave e inténtelo de nuevo")
                resp.close()
                resp = exec(buildHttpRequest(keyRotation.effectiveApiKey()))
            }
            resp.use { r ->
                if (!r.isSuccessful) {
                    val errBody = readBodySafely(r)
                    val apiMsg = parseApiErrorMessage(errBody)
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
                        apiMsg?.let { append(": ").append(it) }
                        if (hint == null && apiMsg == null && errBody.isNotBlank()) {
                            append(": ").append(errBody)
                        }
                    }
                    Logger.w(TAG, "agnes image HTTP ${r.code}")
                    error(msg)
                }
                // B-03: contentLength desconocido(chunked)mismo límite de tiempo — lectura en streaming,Interrumpir si se excede el límite
                val (respBody, overLimit) = ProviderHttpSupport.readBodyCappedStreaming(r, MAX_RESPONSE_BODY_BYTES)
                if (overLimit) {
                    error(ErrorCode.IMAGE_RESPONSE_TOO_LARGE.toMessage(MAX_RESPONSE_BODY_BYTES / 1024 / 1024))
                }
                if (respBody.isBlank()) error(ErrorCode.IMAGE_EMPTY_RESPONSE.toMessage())
                val images = parseResponseImages(respBody)
                if (images.isEmpty()) error(ErrorCode.IMAGE_NO_RESULTS.toMessage())
                ImageSubmitResult(images = images, isAsync = false)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "agnes image failed: ${e.message}")
            error(ErrorCode.IMAGE_GEN_FAILED.toMessage(e.message ?: ""))
        }
    }

    override suspend fun poll(taskId: String): ImagePollResult {
        // Agnes Retorno sincrónico,No iré a esta sucursal.;Volver a FALLADO.
        error("Agnes La generación de imágenes no admite tareas asincrónicas.: $taskId")
    }

    /**
     * Analizar la lista de imágenes en la respuesta.,Es compatible con los formatos b64_json y de retorno de URL.
     */
    internal fun parseResponseImages(body: String): List<GeneratedImage> {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonArray
            ?: return emptyList()
        return data.mapNotNull { item ->
            val obj = item.jsonObject
            // B-01 (Enlace de imagen sin formato): Sólo acepta cadenas escalares — JSON null / Número/Booleano
            // (JsonPrimitive.content Devuelve no cadenas tal como están,Como por ejemplo "123"/"true")Siempre filtrar,
            // De lo contrario, deletrea "data:image/png;base64,123" foto falsa;literal "null" Las cadenas también se filtran.
            val b64 = (obj["b64_json"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content?.takeIf { it.isNotBlank() && it != "null" }
            // B-01: url debe ser http(s) o datos:imagen/prefijo,Filtrar cuerdas desnudas/ruta relativa
            val url = (obj["url"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content?.takeIf { it.startsWith("http") || it.startsWith("data:image/") }
            if (b64 == null && url == null) null else GeneratedImage(base64 = b64, url = url)
        }
    }

    /**
     * Intente analizar las respuestas de error del estilo Agnes {"error":{"message":"..."}} o nivel superior {"message":"..."}.
     */
    internal fun parseApiErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return resultOf {
            val root = json.parseToJsonElement(body).jsonObject
            root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: root["message"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    private fun readBodySafely(resp: Response): String = ProviderHttpSupport.readBodySafely(resp)

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

    companion object {
        private const val TAG = "AgnesImageProvider"

        /** B-03: Límite del cuerpo de respuesta de imagen 20MB(Consistente con la versión OpenAI). */
        private const val MAX_RESPONSE_BODY_BYTES = 20 * 1024 * 1024

        /** Provider Identificador único. */
        const val PROVIDER_ID = "agnes"

        /** Base predeterminada URL(Alineación PresetProviders.ENDPOINT_AGNES). */
        const val DEFAULT_BASE_URL = "https://apihub.agnes-ai.com/v1"

        /** ID de modelo predeterminado. */
        const val DEFAULT_MODEL_ID = "agnes-image-2.1-flash"

        /**
         * proporción → Mapeo de tamaño de píxel(8 especie).
         *
         * 4:3 / 3:4 Usando un total de 1,5K píxeles
         * (1152x896 / 896x1152),Mantenga la coherencia visual con 3:2 / 2:3.
         */
        val SIZE_RATIOS: Map<String, String> = mapOf(
            "1:1" to "1024x1024",
            "16:9" to "1344x768",
            "21:9" to "1536x640",
            "9:16" to "768x1344",
            "4:3" to "1152x896",
            "3:4" to "896x1152",
            "3:2" to "1152x768",
            "2:3" to "768x1152",
        )

        /** Conjunto de tamaños de píxeles admitidos(Se utiliza para la verificación directa del valor de los píxeles.). */
        val SUPPORTED_SIZES: Set<String> = SIZE_RATIOS.values.toSet()

        /**
         * Analizando el parámetro de tamaño:
         *  - proporción("1:1" espera)→ Asignar al valor de píxel;
         *  - valor de píxel directo("1024x1024")→ Si está dentro de la colección de manutención, se devolverá tal cual.,De lo contrario se informará un error.;
         *  - valor nulo → Predeterminado 3:2.
         */
        fun resolveSize(size: String?): String {
            if (size.isNullOrBlank()) return SIZE_RATIOS.getValue("3:2")
            val trimmed = size.trim()
            // proporción
            SIZE_RATIOS[trimmed]?.let { return it }
            // valor de píxel directo
            if (trimmed.matches(Regex("^\\d+x\\d+$", RegexOption.IGNORE_CASE))) {
                if (trimmed.lowercase() in SUPPORTED_SIZES) return trimmed.lowercase()
                error(ErrorCode.IMAGE_UNSUPPORTED_MODEL.toMessage("agnes size=$trimmed"))
            }
            error(ErrorCode.IMAGE_UNSUPPORTED_MODEL.toMessage("agnes size=$trimmed"))
        }

        /**
         * Normalizar las referencias de imágenes de referencia a base64(Ninguno dato: prefijo).
         * apoyo:data:image URI(prefijo de tira),http/https URL(Descargar y transferir base64), ruta del archivo local.
         *
         * G4: [urlValidator] — http(s) Verificación previa de la SSRF antes de descargar la imagen de referencia(Pasó cada salto).
         * ai El módulo no depende de la aplicación. SsrfGuard(Orientación del módulo app→ai),Los validadores se inyectan desde la capa de la aplicación.
         * (SsrfGuard.refImageUrlValidator → [RefImageUrlValidator]);
         * Devolver falso indica que la URL llega a la intranet/dirección reservada,Rechazar la solicitud.
         * null revertir el viejo comportamiento(Descarga directa,Sin verificación salto a salto),Permanece desconectado.
         */
        suspend fun resolveReferenceImage(ref: String, urlValidator: RefImageUrlValidator? = null): String {
            val trimmed = ref.trim()
            return when {
                trimmed.startsWith("data:") -> {
                    trimmed.substringAfter("base64,", "").ifBlank { trimmed }
                }
                trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> {
                    // G4: Cuando el verificador de inyección no está vacío, SSRF debe verificarlo antes de descargarlo.(Intranet/dirección reservada → rechazar);
                    // No inyectado(null)Vuelva al comportamiento anterior al descargar directamente.
                    if (urlValidator != null && !urlValidator.isAllowed(trimmed)) {
                        error(ErrorCode.IMAGE_INVALID_URI.toMessage("ssrf_blocked"))
                    }
                    // Reparación de auditoría (6.3): Descarga de streaming con tiempo de espera + límite de tamaño durante la lectura.
                    // Implementación original openStream().readBytes() Sin tiempo de espera(Cuando el servidor se cuelga, el subproceso IO está permanentemente ocupado),
                    // Y primero lea la cantidad total en la memoria antes de verificar el límite superior de 10 MB.(Archivos remotos muy grandes directamente OOM).
                    val url = java.net.URL(trimmed)
                    var conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 30_000
                    // G4: Desactivar redireccionamientos de seguimiento automático,Cada salto pasa nuevamente la verificación SSRF(Evitar que 302 salte a la intranet)
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent", "Muse/1.0")
                    try {
                        conn.connect()
                        var currentUrl = trimmed
                        var redirectCount = 0
                        while (conn.responseCode in 300..399 && redirectCount < 5) {
                            val location = conn.getHeaderField("Location")
                                ?: error(ErrorCode.IMAGE_INVALID_URI.toMessage("http_${conn.responseCode}"))
                            redirectCount++
                            conn.disconnect()
                            val redirectUrl = java.net.URL(url, location)
                            currentUrl = redirectUrl.toExternalForm()
                            // G4: Cuando el verificador de inyección no está vacío, cada objetivo de redirección de salto vuelve a pasar la verificación SSRF.(Evitar que 302 salte a la intranet)
                            if (urlValidator != null && !urlValidator.isAllowed(currentUrl)) {
                                error(ErrorCode.IMAGE_INVALID_URI.toMessage("ssrf_blocked"))
                            }
                            val redirectConn = redirectUrl.openConnection() as java.net.HttpURLConnection
                            redirectConn.connectTimeout = 10_000
                            redirectConn.readTimeout = 30_000
                            redirectConn.instanceFollowRedirects = false
                            redirectConn.setRequestProperty("User-Agent", "Muse/1.0")
                            redirectConn.connect()
                            conn = redirectConn
                        }
                        if (conn.responseCode !in 200..299) {
                            error(ErrorCode.IMAGE_INVALID_URI.toMessage("http_${conn.responseCode}"))
                        }
                        conn.inputStream.use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val buf = ByteArray(16 * 1024)
                            var total = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_REF_IMAGE_BYTES) {
                                    error(ErrorCode.IMAGE_REFERENCE_TOO_LARGE.toMessage(total / 1024 / 1024))
                                }
                                out.write(buf, 0, n)
                            }
                            java.util.Base64.getEncoder().encodeToString(out.toByteArray())
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
                else -> {
                    val file = java.io.File(trimmed.removePrefix("file:"))
                    if (!file.exists()) error(ErrorCode.IMAGE_INVALID_URI.toMessage("ref_not_found"))
                    if (file.length() > MAX_REF_IMAGE_BYTES) {
                        error(ErrorCode.IMAGE_REFERENCE_TOO_LARGE.toMessage(file.length() / 1024 / 1024))
                    }
                    java.util.Base64.getEncoder().encodeToString(file.readBytes())
                }
            }
        }

        /** Tamaño máximo de imagen de referencia 10MB(Alinear la implementación anterior de ImageService). */
        private const val MAX_REF_IMAGE_BYTES = 10L * 1024 * 1024
    }
}
