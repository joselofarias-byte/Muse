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
 * v1.137: Generación de vídeo universal compatible con OpenAI Provider(Refactorizar,darse cuenta [VideoProvider]).
 *
 * punto final de llamada: POST {baseUrl}{videoGenerationsPath}(Predeterminado /videos/generations).
 * Certificación: Bearer Token.
 *
 * Este Proveedor es compatible con ambos tipos de formularios de respuesta:
 *  1. Retorno sincrónico:Incluido directamente en el cuerpo de la respuesta. video URL → isAsync=false
 *  2. Tareas asincrónicas:El cuerpo de la respuesta contiene task_id / id → isAsync=true,seguimiento [poll] sondeo
 *
 * v1.137 Cambiar:
 *  - implementar nuevo [VideoProvider] interfaz,Unificar el protocolo de envío/encuesta
 *  - Agregar soporte para tareas asincrónicas:Analizar task_id y sondear(GET {baseUrl}{path}/{taskId})
 *  - Eliminar el modelo predeterminado falso de gpt-4o-video(El modelo en realidad no existe.,Hará que la llamada a la API falle)
 *  - El cronograma de votación avanza hasta [VideoGenerationService],Esta clase solo es responsable de una única consulta.
 *
 * Punto final de consulta asincrónica: GET {baseUrl}{videoGenerationsPath}/{taskId}
 *  - Punto final genérico compatible con OpenAI(DashScope / MiniMax espera)Generalmente sigue esta convención.
 *  - Si la URL de consulta devuelta por el proveedor es diferente,Se recomienda implementar especializados. [VideoProvider] En lugar de seguir las reglas generales
 *
 * @param client OkHttp cliente
 */
class GenericOpenAiVideoProvider(
    private val client: OkHttpClient,
) : VideoProvider {

    override val providerId: String = PROVIDER_ID
    override val supportsImageToVideo: Boolean = true
    override val supportsMultiFrameToVideo: Boolean = false

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Contexto almacenado en caché por taskId(poll cuando sea necesario apiKey / baseUrl / path).
     */
    private val taskContext = ConcurrentHashMap<String, TaskContext>()

    private data class TaskContext(
        val apiKey: String,
        val baseUrl: String,
        val path: String,
        val modelName: String,
    )

    /**
     * Envíe una tarea de generación de video.
     *
     * clasificación de respuesta:
     *  - Contiene video URL(Ninguno task_id) → isAsync=false,Regresar directamente videoUrl
     *  - Contiene task_id / id(Ninguno video URL) → isAsync=true,Contexto de caché para uso por encuesta
     */
    override suspend fun submit(request: VideoGenRequest): VideoSubmitResult = withContext(Dispatchers.IO) {
        resultOf {
            if (request.apiKey.isBlank()) {
                error("La clave API de generación de video está vacía")
            }
            if (request.prompt.isBlank()) {
                error("Prompt esta vacio")
            }

            val baseUrl = (request.baseUrl ?: DEFAULT_BASE_URL).trimEnd('/')
            val path = request.videoGenerationsPath?.trim()?.trim('/')?.ifBlank { DEFAULT_PATH }
                ?: DEFAULT_PATH
            val url = "$baseUrl/$path"

            val body = buildRequestBody(request)
            val httpRequest = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${request.apiKey}")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            Logger.i(
                TAG,
                "submit: model=${request.model} baseUrl=$baseUrl path=$path",
            )

            exec(httpRequest).use { resp ->
                val respBody = readBody(resp)
                if (!resp.isSuccessful) {
                    val apiMsg = parseApiErrorMessage(respBody)
                    error(
                        "Error en la solicitud de generación de vídeo: HTTP ${resp.code}" +
                            (apiMsg?.let { ": $it" }
                                ?: ProviderError.safeErrorDetail(respBody)?.let { ": $it" }.orEmpty()),
                    )
                }

                val root = json.parseToJsonElement(respBody).jsonObject

                // Priorizar intentar sincronizar resultados:Incluido directamente en el cuerpo de la respuesta. video URL
                val syncVideoUrl = extractVideoUrl(root)
                if (syncVideoUrl != null) {
                    Logger.i(TAG, "submit Sincronizar vídeo de regreso: url=$syncVideoUrl")
                    return@use VideoSubmitResult(
                        // Las tareas de sincronización se derivan mediante URL taskId,Facilitar la correlación de registros;no entrará poll
                        taskId = "$SYNC_PREFIX${System.nanoTime()}",
                        videoUrl = syncVideoUrl,
                        isAsync = false,
                        modelName = request.model,
                    )
                }

                // De lo contrario, se procesará como una tarea asincrónica.:analizar task_id / id
                val taskId = root["task_id"]?.jsonPrimitive?.content
                    ?: root["id"]?.jsonPrimitive?.content
                    ?: root["task"]?.jsonPrimitive?.content
                    ?: error("La URL del video no se encuentra en la respuesta de generación de video o task_id: $respBody")

                taskContext[taskId] = TaskContext(
                    apiKey = request.apiKey,
                    baseUrl = baseUrl,
                    path = path,
                    modelName = request.model,
                )
                Logger.i(TAG, "submit Tareas asincrónicas: taskId=$taskId")
                VideoSubmitResult(taskId = taskId, isAsync = true, modelName = request.model)
            }
        }.getOrThrow()
    }

    /**
     * Consultar el estado de las tareas asincrónicas.
     *
     * GET {baseUrl}{path}/{taskId}
     */
    override suspend fun poll(taskId: String): VideoPollResult = withContext(Dispatchers.IO) {
        val r = resultOf {
            val ctx = taskContext[taskId]
            if (ctx == null) {
                return@resultOf VideoPollResult(
                    status = PollStatus.FAILED,
                    errorMessage = "GenericOpenAi poll Falta contexto de tarea(taskId=$taskId, Puede ser que la tarea de sincronización estuviera mal ajustada. poll)",
                )
            }

            val url = "${ctx.baseUrl}/${ctx.path}/${taskId.encodeUrlPathSegment()}"
            val httpRequest = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${ctx.apiKey}")
                .get()
                .build()

            exec(httpRequest).use { resp ->
                val respBody = readBody(resp)
                if (!resp.isSuccessful) {
                    // HTTP Los errores no se juzgan directamente como fracasos.,Déjelo en manos del mecanismo de reintento de la capa superior.
                    return@use VideoPollResult(
                        status = PollStatus.PENDING,
                        errorMessage = "GenericOpenAi poll HTTP ${resp.code}: $respBody",
                    )
                }
                val root = json.parseToJsonElement(respBody).jsonObject
                val statusStr = root["status"]?.jsonPrimitive?.content?.lowercase()
                    ?: root["state"]?.jsonPrimitive?.content?.lowercase()
                    ?: "unknown"
                val status = mapStatus(statusStr)

                when (status) {
                    PollStatus.SUCCESS -> {
                        val videoUrl = extractVideoUrl(root)
                        taskContext.remove(taskId)
                        if (videoUrl == null) {
                            VideoPollResult(
                                status = PollStatus.PENDING,
                                errorMessage = "GenericOpenAi El estado es exitoso pero no se encontró ningún video. URL: $respBody",
                            )
                        } else {
                            VideoPollResult(status = status, videoUrl = videoUrl)
                        }
                    }
                    PollStatus.FAILED -> {
                        val errMsg = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                            ?: root["message"]?.jsonPrimitive?.content
                            ?: "GenericOpenAi Falló la generación de video(status=$statusStr)"
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
                errorMessage = "GenericOpenAi poll anormal: ${r.throwable?.message ?: r.throwable?.toString() ?: r.message}",
            )
        }
    }

    /**
     * Construya el cuerpo de la solicitud JSON.
     *
     * v1.137: Eliminar el modelo predeterminado falso de gpt-4o-video — model Debe ser transmitido por la persona que llama
     * (Filtrar de la lista de modelos de ProviderConfig supportsVideoOutput() modelo).
     */
    internal fun buildRequestBody(request: VideoGenRequest): String {
        return buildJsonObject {
            if (request.model.isNotBlank()) {
                put("model", request.model)
            }
            put("prompt", request.prompt)
            if (request.duration > 0) put("duration", request.duration)
            if (request.resolution.isNotBlank()) put("resolution", request.resolution)
            val images = request.referenceImages.filter { it.isNotBlank() }
            if (images.size == 1) {
                put("image", images[0])
            } else if (images.size > 1) {
                // Se pasan varias imágenes en forma de matriz.(Compatible con puntos finales que admiten múltiples gráficos)
                put("image", JsonArray(images.map { JsonPrimitive(it) }))
            }
        }.toString()
    }

    /**
     * Extraiga la URL del vídeo de la respuesta.
     *
     * Formatos soportados:
     *  - {"data":[{"url":"..."}]}
     *  - {"video":{"url":"..."}}
     *  - {"url":"..."}
     *  - {"output_url":"..."} / {"video_url":"..."}
     */
    private fun extractVideoUrl(root: JsonObject): String? {
        // campo superior directo
        for (key in VIDEO_URL_KEYS) {
            val v = root[key]
            if (v is JsonPrimitive && v.isString) {
                val s = v.content
                if (s.startsWith("http://", true) || s.startsWith("https://", true)) return s
            }
        }
        // data matriz
        val data = root["data"]
        if (data is JsonArray) {
            for (item in data) {
                if (item is JsonObject) {
                    extractVideoUrl(item)?.let { return it }
                }
            }
        }
        // video objeto
        val video = root["video"]
        if (video is JsonObject) {
            extractVideoUrl(video)?.let { return it }
        }
        return null
    }

    private fun mapStatus(statusStr: String): PollStatus = when (statusStr) {
        "completed", "success", "succeeded", "done" -> PollStatus.SUCCESS
        "failed", "error", "cancelled", "canceled" -> PollStatus.FAILED
        else -> PollStatus.PENDING
    }

    /**
     * Intente analizar una respuesta de error de estilo OpenAI {"error":{"message":"..."}} O ordinario {"message":"..."}.
     */
    private fun parseApiErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: root["message"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    private fun readBody(resp: Response): String {
        val bytes = resp.body?.bytes() ?: return ""
        if (bytes.size > MAX_RESPONSE_BYTES) {
            error("La respuesta de generación de vídeo es demasiado grande: ${bytes.size} bytes")
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
        private const val TAG = "GenericOpenAiVideoProvider"

        /** Provider identificador único,en [VideoProviderRegistry] como red de seguridad general. */
        const val PROVIDER_ID = "openai_video_generic"

        /** Base predeterminada URL(Respaldo cuando no se pasa baseUrl). */
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

        /** Ruta predeterminada del punto final de generación de vídeo. */
        const val DEFAULT_PATH = "videos/generations"

        /** Prefijo de ID de tarea de sincronización(para correlación logarítmica,no entrará poll). */
        private const val SYNC_PREFIX = "sync:"

        /** El tamaño máximo del cuerpo de respuesta es 1 MB. */
        private const val MAX_RESPONSE_BYTES = 1 * 1024 * 1024

        /** Campo candidato de URL de resultado de vídeo(Ordenar por prioridad). */
        private val VIDEO_URL_KEYS = listOf("url", "video_url", "output_url")
    }
}

/** String Expandir:URL codificación de segmento de ruta(Evite caracteres especiales que interrumpan las solicitudes). */
private fun String.encodeUrlPathSegment(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
