@file:Suppress("NestedBlockDepth", "ReturnCount")

package io.zer0.ai.video

import io.zer0.ai.core.ProviderError
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
 * v1.137: Agnes AI Implementación de Proveedor de generación de video.
 *
 * Puntos de implementación:
 *
 * punto final:
 *  - Enviar: POST {baseUrl}/videos        (baseUrl Predeterminado https://apihub.agnes-ai.com/v1)
 *  - Consulta: GET  {rootBase}/agnesapi?video_id=X&model_name=Y
 *          (rootBase para eliminar la raíz del sufijo /v1,Eso es https://apihub.agnes-ai.com)
 *
 * Certificación: Bearer Token.
 *
 * modo:
 *  - text2video:        Sin imagen de referencia
 *  - image2video:       Imagen de referencia única(image campo)
 *  - multiframe2video:  2+ Imagen de referencia(extra_body.image matriz)
 *
 * Restricciones de número de fotogramas([resolveNumFrames]):
 *  - num_frames debe estar satisfecho 8n+1,alcance [81, 441](corresponder 3-18s @ 1-60fps)
 *  - Si los numFrames pasados por la persona que llama no cumplen con las restricciones,Se corregirá automáticamente al valor legal más cercano
 *
 * mapeo estatal([mapStatus]):
 *  - failed / error / cancelled / canceled → [PollStatus.FAILED]
 *  - completed / success / succeeded / done → [PollStatus.SUCCESS]
 *  - Otros → [PollStatus.PENDING]
 *
 * Extracción de resultados([extractVideoUrl]):
 *  - Pruebe por turno remixed_from_video_id / video_url / url / output_url campo
 *  - Compatible con matrices de datos anidadas
 *
 * @param client OkHttp cliente(Inyectado por Koin named("chat"))
 */
class AgnesVideoProvider(
    private val client: OkHttpClient,
) : VideoProvider {

    override val providerId: String = PROVIDER_ID
    override val supportsImageToVideo: Boolean = true
    override val supportsMultiFrameToVideo: Boolean = true

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Contexto almacenado en caché por taskId(poll cuando sea necesario apiKey / modelName).
     *
     * key = submit regresó taskId,value = La apiKey de la tarea y el nombre del modelo utilizado al enviar.
     * El estado final de la tarea es seguido por [VideoGenerationService] Limpiando la lógica(aqui solo put,No proactivo remove,
     * Porque el mismo taskId no se reutilizará,El número de entradas está limitado.).
     */
    private val taskContext = ConcurrentHashMap<String, TaskContext>()

    private data class TaskContext(val apiKey: String, val modelName: String, val baseUrl: String)

    /**
     * Envíe una tarea de generación de video.
     *
     * Según [VideoGenRequest.referenceImages] Modo de selección automática de cantidad:
     *  - 0 zhang → text2video
     *  - 1 zhang → image2video(image campo)
     *  - 2+ zhang → multiframe2video(extra_body.image matriz)
     */
    /** R-TEST-20: Agnes Estructura del cuerpo de la solicitud de vídeo(puro DTO,Fácil de probar). */
    internal fun buildRequestBody(request: VideoGenRequest, numFrames: Int): JsonObject {
        val images = request.referenceImages.filter { it.isNotBlank() }
        return buildJsonObject {
            put("model", request.model.ifBlank { DEFAULT_MODEL })
            put("prompt", request.prompt)
            put("width", request.width)
            put("height", request.height)
            put("frame_rate", request.frameRate)
            put("num_frames", numFrames)
            when {
                images.size == 1 -> put("image", images[0])
                images.size > 1 -> {
                    put(
                        "extra_body",
                        buildJsonObject {
                            put("image", JsonArray(images.map { JsonPrimitive(it) }))
                        },
                    )
                }
            }
        }
    }

    override suspend fun submit(request: VideoGenRequest): VideoSubmitResult = withContext(Dispatchers.IO) {
        resultOf {
            if (request.apiKey.isBlank()) {
                error("Agnes API key esta vacio")
            }
            if (request.prompt.isBlank()) {
                error("Prompt esta vacio")
            }

            val model = request.model.ifBlank { DEFAULT_MODEL }
            val base = agnesV1Base(request.baseUrl)
            val url = "$base/videos"

            // Corrija el número de fotograma al valor legal 8n+1
            val numFrames = resolveNumFrames(request.numFrames, request.frameRate, request.duration)

            val body = buildRequestBody(request, numFrames)

            val httpRequest = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${request.apiKey}")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            Logger.i(
                TAG,
                "submit: model=$model size=${request.width}x${request.height} " +
                    "frameRate=${request.frameRate} numFrames=$numFrames images=" +
                    "${request.referenceImages.count { it.isNotBlank() }}",
            )

            exec(httpRequest).use { resp ->
                val respBody = readBody(resp)
                if (!resp.isSuccessful) {
                    val apiMsg = parseApiErrorMessage(respBody)
                    error(
                        "Agnes submit falló: HTTP ${resp.code}" +
                            (apiMsg?.let { ": $it" }
                                ?: ProviderError.safeErrorDetail(respBody)?.let { ": $it" }.orEmpty()),
                    )
                }
                val root = json.parseToJsonElement(respBody).jsonObject
                // tener prioridad video_id,en segundo lugar task_id / id
                val taskId = root["video_id"]?.jsonPrimitive?.content
                    ?: root["task_id"]?.jsonPrimitive?.content
                    ?: root["id"]?.jsonPrimitive?.content
                    ?: error(
                        "Agnes submit Falta respuesta video_id/task_id: " +
                            (ProviderError.safeErrorDetail(respBody) ?: "<empty>"),
                    )

                // Cheque de devolución sincrónico:En algunos escenarios, el estado de finalización puede volver directamente al vídeo. URL
                val syncVideoUrl = extractVideoUrl(root)
                if (syncVideoUrl != null) {
                    Logger.i(TAG, "submit Sincronizar vídeo de regreso: taskId=$taskId")
                    return@use VideoSubmitResult(
                        taskId = taskId,
                        videoUrl = syncVideoUrl,
                        isAsync = false,
                        modelName = model,
                    )
                }

                taskContext[taskId] = TaskContext(
                    apiKey = request.apiKey,
                    modelName = model,
                    // Reparación de auditoría (7.6): guardar baseUrl,poll Utilice el mismo punto final(Nombre de dominio predeterminado codificado en la encuesta original,
                    // Agente autoconstruido de configuración de usuario/El envío se realizó correctamente durante la transferencia, pero el sondeo alcanzó el nombre de dominio predeterminado.,Las tareas nunca se pueden encontrar)
                    baseUrl = agnesRootBase(request.baseUrl),
                )
                Logger.i(TAG, "submit éxito: taskId=$taskId")
                VideoSubmitResult(taskId = taskId, isAsync = true, modelName = model)
            }
        }.getOrThrow()
    }

    /**
     * Consultar el estado de la tarea.
     *
     * GET {rootBase}/agnesapi?video_id=X&model_name=Y
     * rootBase Elimine el sufijo /v1 de baseUrl.
     */
    override suspend fun poll(taskId: String): VideoPollResult = withContext(Dispatchers.IO) {
        val r = resultOf {
            val ctx = taskContext[taskId]
            val apiKey = ctx?.apiKey ?: ""
            val modelName = ctx?.modelName ?: ""
            if (apiKey.isBlank()) {
                return@resultOf VideoPollResult(
                    status = PollStatus.FAILED,
                    errorMessage = "Agnes poll desaparecido apiKey(taskId=$taskId, Se ha perdido el contexto de la tarea.)",
                )
            }

            val rootBase = ctx?.baseUrl ?: agnesRootBase(null)
            val queryBuilder = StringBuilder("?video_id=").append(urlEncode(taskId))
            if (modelName.isNotBlank()) {
                queryBuilder.append("&model_name=").append(urlEncode(modelName))
            }
            val url = "$rootBase/agnesapi$queryBuilder"

            val httpRequest = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()

            exec(httpRequest).use { resp ->
                val respBody = readBody(resp)
                if (!resp.isSuccessful) {
                    // HTTP Los errores no se juzgan directamente como fracasos.,Déjelo en manos del mecanismo de reintento de la capa superior.(Falló 5 veces seguidas)
                    return@use VideoPollResult(
                        status = PollStatus.PENDING,
                        errorMessage = "Agnes poll HTTP ${resp.code}: $respBody",
                    )
                }
                val root = json.parseToJsonElement(respBody).jsonObject
                val statusStr = root["status"]?.jsonPrimitive?.content?.lowercase() ?: "unknown"
                val status = mapStatus(statusStr)

                when (status) {
                    PollStatus.SUCCESS -> {
                        val videoUrl = extractVideoUrl(root)
                            ?: return@use VideoPollResult(
                                status = PollStatus.PENDING,
                                errorMessage = "Agnes El estado es exitoso pero no se encontró ningún video. URL: $respBody",
                            )
                        // Limpiar el contexto después del éxito
                        taskContext.remove(taskId)
                        VideoPollResult(status = status, videoUrl = videoUrl)
                    }
                    PollStatus.FAILED -> {
                        val errMsg = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                            ?: root["message"]?.jsonPrimitive?.content
                            ?: "Agnes Falló la generación de video(status=$statusStr)"
                        taskContext.remove(taskId)
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
                errorMessage = "Agnes poll anormal: ${r.throwable?.message ?: r.throwable?.toString() ?: r.message}",
            )
        }
    }

    // ── Restricciones de número de fotogramas ─────────────────────────────────────────────────────────────

    /**
     * Corregir numFrames al valor legal 8n+1,alcance [81, 441].
     *
     *  - Si el valor entrante es legal,Usar directamente
     *  - En caso contrario, tome el más cercano. 8n+1,Si es menor que el límite inferior, tome el límite superior(81),Si es mayor que el límite superior, tome el límite superior(441)
     */
    private fun resolveNumFrames(numFrames: Int, frameRate: Int, duration: Int): Int {
        // Si numFrames ya es válido 8n+1 y está dentro del rango,Usar directamente
        if (numFrames in MIN_FRAMES..MAX_FRAMES && (numFrames - 1) % 8 == 0) {
            return numFrames
        }
        // De lo contrario presione duration * frameRate + 1 Deducir el número de fotograma de destino
        val rate = if (frameRate in 1..60) frameRate else DEFAULT_FRAME_RATE
        val dur = if (duration in 3..18) duration else DEFAULT_DURATION
        val target = dur * rate + 1
        return clampToValidFrames(target)
    }

    /**
     * Sujete cualquier número de fotogramas al valor legal 8n+1 más cercano.
     */
    private fun clampToValidFrames(target: Int): Int {
        if (target <= MIN_FRAMES) return MIN_FRAMES
        if (target >= MAX_FRAMES) return MAX_FRAMES
        // (n - 1) / 8 redondo,otra vez *8 + 1
        val n = (target - 1) / 8
        val candidate = n * 8 + 1
        return if (candidate in MIN_FRAMES..MAX_FRAMES) candidate else MIN_FRAMES
    }

    // ── mapeo estatal ─────────────────────────────────────────────────────────────

    internal fun mapStatus(statusStr: String): PollStatus = when (statusStr) {
        "failed", "error", "cancelled", "canceled" -> PollStatus.FAILED
        "completed", "success", "succeeded", "done" -> PollStatus.SUCCESS
        else -> PollStatus.PENDING
    }

    // ── Análisis de respuesta ─────────────────────────────────────────────────────────────

    /**
     * Extraiga la URL del vídeo de la respuesta.
     *
     * Pruebe por turno: remixed_from_video_id / video_url / url / output_url
     * Compatible con matrices de datos anidadas(búsqueda recursiva).
     */
    internal fun extractVideoUrl(root: JsonObject): String? {
        for (key in VIDEO_URL_KEYS) {
            val v = root[key]
            if (v is JsonPrimitive && v.isString) {
                val s = v.content
                if (s.startsWith("http://", true) || s.startsWith("https://", true)) return s
            }
        }
        // Matriz de datos anidados
        val data = root["data"]
        if (data is JsonArray) {
            for (item in data) {
                if (item is JsonObject) {
                    extractVideoUrl(item)?.let { return it }
                }
            }
        }
        return null
    }

    /**
     * Intente analizar la respuesta de error de Agnes {"error":{"message":"..."}} o {"message":"..."}.
     */
    internal fun parseApiErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val message = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: root["message"]?.jsonPrimitive?.content
            message?.let { ProviderError.safeErrorDetail(it) ?: it.take(200) }
        }.getOrNull()
    }

    // ── URL Herramientas ─────────────────────────────────────────────────────────────

    /**
     * Normalice baseUrl para que se forme con el sufijo /v1.
     *  - dejar en blanco → Predeterminado [DEFAULT_BASE_URL]
     *  - Ya traído /v1 → Úselo como está
     *  - sin /v1 → anexar /v1
     */
    internal fun agnesV1Base(baseUrl: String?): String {
        val base = baseUrl?.trim()?.trimEnd('/')?.ifBlank { DEFAULT_BASE_URL } ?: DEFAULT_BASE_URL
        return if (base.endsWith("/v1", ignoreCase = true)) base else "$base/v1"
    }

    /**
     * Normalice baseUrl a la raíz con el sufijo /v1 eliminado.
     * Se utiliza para consultar el punto final. /agnesapi(ruta raíz,no /v1).
     */
    internal fun agnesRootBase(baseUrl: String?): String {
        return agnesV1Base(baseUrl).replace(Regex("/v1$", RegexOption.IGNORE_CASE), "")
    }

    /** Codificación de valor de consulta de URL simple(Codificar solo caracteres especiales). */
    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    // ── HTTP Herramientas(Consistente con KlingVideoProvider) ───────────────────────────────

    private fun readBody(resp: Response): String {
        val bytes = resp.body?.bytes() ?: return ""
        if (bytes.size > MAX_RESPONSE_BYTES) {
            error("Agnes Respuesta demasiado grande: ${bytes.size} bytes")
        }
        return String(bytes, Charsets.UTF_8)
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

    companion object {
        private const val TAG = "AgnesVideoProvider"

        /** Provider Identificador único. */
        const val PROVIDER_ID = "agnes"

        /** Agnes API URL base predeterminada. */
        const val DEFAULT_BASE_URL = "https://apihub.agnes-ai.com/v1"

        /** Modelo de vídeo predeterminado. */
        const val DEFAULT_MODEL = "agnes-video-v2.0"

        /** Velocidad de fotogramas predeterminada. */
        private const val DEFAULT_FRAME_RATE = 24

        /** Duración predeterminada(segundos). */
        private const val DEFAULT_DURATION = 5

        /** Velocidad de fotogramas mínima(8n+1,corresponder 3s@24fps). */
        private const val MIN_FRAMES = 81

        /** Límite de velocidad de fotogramas(8n+1,corresponder 18s@24fps). */
        private const val MAX_FRAMES = 441

        /** El tamaño máximo del cuerpo de respuesta es 1 MB. */
        private const val MAX_RESPONSE_BYTES = 1 * 1024 * 1024

        /** Campo candidato de URL de resultado de vídeo(Ordenar por prioridad). */
        private val VIDEO_URL_KEYS = listOf(
            "remixed_from_video_id",
            "video_url",
            "url",
            "output_url",
        )
    }
}
