package io.zer0.ai.video

import io.zer0.ai.core.ProviderError
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ke ling(Kling)Implementación del proveedor de generación de video.(v1.137 Refactorizar,darse cuenta [VideoProvider]).
 *
 * API Referencia: https://kling.kuaishou.com/docs
 *
 * punto final:
 *  - Vídeo de Vicente: POST {baseUrl}/videos/generations/text2video
 *  - vídeo de tusheng: POST {baseUrl}/videos/generations/image2video
 *  - Tareas de consulta: GET  {baseUrl}/videos/generations/{taskId}
 *
 * Certificación: Bearer Token(API Key Coloque el encabezado de Autorización directamente)
 *
 * Mapeo del estado de la tarea([mapStatus]):
 *  - "submit" / "submitted" / "pending" → [PollStatus.PENDING]
 *  - "processing" / "running"          → [PollStatus.PENDING](Aún en proceso,Continuar encuestando)
 *  - "succeed" / "success" / "succeeds" → [PollStatus.SUCCESS]
 *  - "failed" / "fail" / "error"       → [PollStatus.FAILED]
 *  - estado desconocido → [PollStatus.FAILED](Evite las heladas silenciosas)
 *
 * v1.137 Cambiar:
 *  - implementar nuevo [VideoProvider] interfaz,Unificar el protocolo de envío/encuesta
 *  - El cronograma de votación avanza hasta [VideoGenerationService],Esta clase solo es responsable de una única consulta.
 *  - Solucionar el error de enrutamiento de ProviderId: el ProviderId permanece "kling",
 *    por [VideoProviderRegistry] Coincidir con preset_kling / kling / definido por el usuario por specId/host ID
 *  - [supportsMultiFrameToVideo] = false(Kling Solo admite videos Tusheng de una sola imagen)
 *
 * @param client OkHttp cliente(Inyectado por Koin named("chat"))
 * @param baseUrl Conceptos básicos de la API de Keling URL,Predeterminado https://api.klingai.com/v1
 */
class KlingVideoProvider(
    private val client: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : VideoProvider {

    override val providerId: String = PROVIDER_ID
    override val supportsImageToVideo: Boolean = true
    override val supportsMultiFrameToVideo: Boolean = false

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Almacenado en caché por taskId apiKey(poll cuando sea necesario).
     *
     * v1.137: El estado final de la tarea es seguido por [poll] Limpiar proactivamente cuando haya ÉXITO/FALLO;
     * Si la capa superior se agota/Cancelar ya no llama poll,Las entradas permanecerán(acotado,Sin pérdidas de memoria).
     */
    private val apiKeyForQuery = ConcurrentHashMap<String, String>()

    // Reparación de auditoría (7.7): Registre sincrónicamente cada tarea baseUrl,poll Utilice el mismo punto final
    private val baseUrlForQuery = ConcurrentHashMap<String, String>()

    /**
     * Envíe una tarea de generación de video.
     *
     * Según [VideoGenRequest.referenceImages] Si el punto final seleccionado no es nulo:
     *  - vacío → POST /videos/generations/text2video(Vídeo de Vicente)
     *  - No vacío(Toma la primera página.) → POST /videos/generations/image2video(vídeo de tusheng)
     *
     * Regresar isAsync=true,taskId es el ID de la tarea de Keling.
     */
    override suspend fun submit(request: VideoGenRequest): VideoSubmitResult = withContext(Dispatchers.IO) {
        resultOf {
            if (request.apiKey.isBlank()) {
                error("Kling API key esta vacio")
            }
            if (request.prompt.isBlank()) {
                error("Prompt esta vacio")
            }

            val images = request.referenceImages.filter { it.isNotBlank() }
            val path = if (images.isNotEmpty()) "image2video" else "text2video"
            // Reparación de auditoría (7.7): prioridad request.baseUrl(Agente autoconstruido de configuración de usuario/tránsito),
            // La implementación original ignora request.baseUrl y en su lugar utiliza el valor predeterminado construido.,Los puntos finales personalizados nunca entran en vigor.
            val effectiveBase = request.baseUrl?.trim()?.trimEnd('/')?.ifBlank { baseUrl } ?: baseUrl
            val url = "$effectiveBase/videos/generations/$path"

            val body = buildRequestBody(request, images.firstOrNull())
            val httpRequest = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${request.apiKey}")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            Logger.i(
                TAG,
                "submit: model=${request.model} duration=${request.duration} " +
                    "resolution=${request.resolution} hasImage=${images.isNotEmpty()}",
            )

            exec(httpRequest).use { resp ->
                val respBody = readBody(resp)
                if (!resp.isSuccessful) {
                    val apiMsg = parseApiErrorMessage(respBody)
                    error(
                        "Kling submit falló: HTTP ${resp.code}" +
                            (apiMsg?.let { ": $it" }
                                ?: ProviderError.safeErrorDetail(respBody)?.let { ": $it" }.orEmpty()),
                    )
                }
                val root = json.parseToJsonElement(respBody).jsonObject
                val code = root["code"]?.jsonPrimitive?.content
                // Error empresarial de Keling API:code != 0
                if (code != null && code != "0") {
                    val msg = root["message"]?.jsonPrimitive?.content ?: "unknown error"
                    error("Kling submit API error: code=$code message=$msg")
                }
                val data = root["data"]?.jsonObject
                    ?: error("Kling submit Falta respuesta data: $respBody")
                val taskId = data["task_id"]?.jsonPrimitive?.content
                    ?: error("Kling submit Falta respuesta task_id: $respBody")
                apiKeyForQuery[taskId] = request.apiKey
                baseUrlForQuery[taskId] = request.baseUrl?.trim()?.trimEnd('/')?.ifBlank { baseUrl } ?: baseUrl
                Logger.i(TAG, "submit éxito: taskId=$taskId")
                VideoSubmitResult(taskId = taskId, isAsync = true, modelName = request.model)
            }
        }.getOrThrow()
    }

    /**
     * Consultar el estado de la tarea(soltero,Sin programación de votaciones).
     *
     * Programación por turnos(Frecuencia adaptativa/recuento de errores)por [VideoGenerationService] Procesamiento unificado.
     * Este método regresa cuando la red es anormal. PENDING + errorMessage,Deje que la capa superior lo maneje de acuerdo con el recuento continuo de errores.
     */
    override suspend fun poll(taskId: String): VideoPollResult = withContext(Dispatchers.IO) {
        val r = resultOf {
            val effectiveBase = baseUrlForQuery[taskId] ?: baseUrl
            val url = "$effectiveBase/videos/generations/$taskId"
            val apiKey = apiKeyForQuery[taskId] ?: ""
            val httpRequest = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()

            exec(httpRequest).use { resp ->
                val respBody = readBody(resp)
                if (!resp.isSuccessful) {
                    return@use VideoPollResult(
                        status = PollStatus.PENDING,
                        errorMessage = "Kling query HTTP ${resp.code}: $respBody",
                    )
                }
                val root = json.parseToJsonElement(respBody).jsonObject
                val code = root["code"]?.jsonPrimitive?.content
                if (code != null && code != "0") {
                    val msg = root["message"]?.jsonPrimitive?.content ?: "unknown error"
                    // Los errores empresariales determinan directamente el fracaso(Problemas de red no transitorios)
                    apiKeyForQuery.remove(taskId)
                    baseUrlForQuery.remove(taskId)
                    return@use VideoPollResult(
                        status = PollStatus.FAILED,
                        errorMessage = "Kling query API error: code=$code message=$msg",
                    )
                }
                val data = root["data"]?.jsonObject
                    ?: run {
                        apiKeyForQuery.remove(taskId)
                        baseUrlForQuery.remove(taskId)
                        return@use VideoPollResult(
                            status = PollStatus.FAILED,
                            errorMessage = "Kling query Falta respuesta data: $respBody",
                        )
                    }

                val statusStr = data["task_status"]?.jsonPrimitive?.content ?: "unknown"
                val status = mapStatus(statusStr)

                when (status) {
                    PollStatus.SUCCESS -> {
                        val videoUrl = data["task_result"]?.jsonObject
                            ?.get("videos")?.jsonArray
                            ?.firstOrNull()
                            ?.jsonObject
                            ?.get("url")?.jsonPrimitive?.content
                        apiKeyForQuery.remove(taskId)
                        baseUrlForQuery.remove(taskId)
                        if (videoUrl == null) {
                            VideoPollResult(
                                status = PollStatus.FAILED,
                                errorMessage = "Kling Misión exitosa pero no se encontró ningún video. URL: $respBody",
                            )
                        } else {
                            VideoPollResult(status = status, videoUrl = videoUrl)
                        }
                    }
                    PollStatus.FAILED -> {
                        val failInfo = data["task_fail_info"]?.jsonObject
                        val errMsg = failInfo?.get("message")?.jsonPrimitive?.content
                            ?: "Kling Tarea fallida(status=$statusStr)"
                        apiKeyForQuery.remove(taskId)
                        baseUrlForQuery.remove(taskId)
                        VideoPollResult(status = status, errorMessage = errMsg)
                    }
                    PollStatus.PENDING -> VideoPollResult(status = status)
                }
            }
        }
        when (r) {
            is io.zer0.common.Result.Success -> r.data
            is io.zer0.common.Result.Error -> VideoPollResult(
                status = PollStatus.PENDING,
                errorMessage = "Kling poll anormal: ${r.throwable?.message ?: r.throwable?.toString() ?: r.message}",
            )
        }
    }

    /**
     * Construir cuerpo de solicitud JSON(Vídeo de Wensheng/vídeo de Tusheng general).
     *
     * @param imageUrl Imagen de referencia única(El tiempo del video de Tusheng no está vacío.)
     */
    internal fun buildRequestBody(request: VideoGenRequest, imageUrl: String?): JsonObject {
        return buildJsonObject {
            put("model", request.model.ifBlank { DEFAULT_MODEL })
            put("prompt", request.prompt)
            // duration Debe ser 5 o 10(Límite de flexibilidad),Respaldo ilegal 5
            val duration = if (request.duration == 5 || request.duration == 10) request.duration else 5
            put("duration", duration)
            put("resolution", request.resolution.ifBlank { "720p" })
            // mode Predeterminado std(Modo estándar),pro El modo requiere un pago más alto,no expuesto
            put("mode", "std")
            if (!imageUrl.isNullOrBlank()) {
                put("image", imageUrl)
            }
        }
    }

    /**
     * Asigne la cadena de recopilación task_status a [PollStatus].
     *
     * Nota:processing / running Bajo el nuevo modelo, el mapeo unificado es PENDING
     * (Ambos pertenecen a"No completado,Continuar encuestando"Semántica,[PollStatus] ya no se separan PROCESSING).
     *
     * El estado desconocido se asigna a FALLADO y alertas,Evite que los estados desconocidos sean tratados silenciosamente como PENDIENTE, lo que provoca que las tareas se bloqueen.
     */
    internal fun mapStatus(statusStr: String): PollStatus {
        return when (statusStr.lowercase()) {
            "submit", "submitted", "pending" -> PollStatus.PENDING
            "processing", "running" -> PollStatus.PENDING
            "succeed", "success", "succeeds" -> PollStatus.SUCCESS
            "failed", "fail", "error" -> PollStatus.FAILED
            else -> {
                Logger.w(TAG, "Estado de tarea desconocido: $statusStr, asignado a FAILED")
                PollStatus.FAILED
            }
        }
    }

    /**
     * Intente analizar la respuesta de error de la API de KeLing {"code": non-zero, "message": "..."}.
     */
    internal fun parseApiErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root["message"]?.jsonPrimitive?.content?.let { ProviderError.safeErrorDetail(it) ?: it.take(200) }
        }.getOrNull()
    }

    /**
     * Leer cuerpo de respuesta(Límite 1MB,Evitar que respuestas anormales causen OOM).
     */
    private fun readBody(resp: Response): String {
        val bytes = resp.body?.bytes() ?: return ""
        if (bytes.size > MAX_RESPONSE_BYTES) {
            error("Kling response too large: ${bytes.size} bytes")
        }
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * La rutina cancela la ejecución de la solicitud OkHttp propagable(prensa ImageService.exec).
     */
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
        private const val TAG = "KlingVideoProvider"

        /** Provider identificador único(por [VideoProviderRegistry] partido specId/host). */
        const val PROVIDER_ID = "kling"

        /** URL base predeterminada de la API de Keling. */
        const val DEFAULT_BASE_URL = "https://api.klingai.com/v1"

        /** Modelo predeterminado(ke ling v1,5 Segunda generación de vídeo.). */
        const val DEFAULT_MODEL = "kling-v1"

        /** Límite de tamaño del cuerpo de respuesta 1MB(La respuesta a la consulta de tarea generalmente < 10KB,1MB Ten todo en cuenta). */
        private const val MAX_RESPONSE_BYTES = 1 * 1024 * 1024
    }
}
