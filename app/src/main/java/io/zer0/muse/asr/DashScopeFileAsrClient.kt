package io.zer0.muse.asr

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Phase 11.1.5: DashScope Cliente de transcripción de archivos asincrónicos.
 *
 * con [DashScopeAsrClient](WebSocket transmisión)diferente,este cliente va **Transcripción de archivos asincrónica** Acuerdo
 * (https://help.aliyun.com/document_detail/90727.html):
 *
 * 1. **Submit**:POST `https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription`
 *    pasar `file_urls`(Audio de acceso público URL),Regresar `task_id`
 * 2. **Query**:GET `https://dashscope.aliyuncs.com/api/v1/tasks/{task_id}`
 *    Estado de la tarea de sondeo:PENDING → RUNNING → SUCCEEDED / FAILED
 * 3. SUCCEEDED Más tarde de `results.transcription_url` Obtener transcripción JSON,analizar `sentences.text` empalme
 *
 * Escenarios aplicables:
 *  - Transcripción de archivos de audio largos(>1 minutos,Se agota el tiempo de espera de la transmisión de WebSocket)
 *  - Ya tengo audio de red pública. URL(Por ejemplo, después de cargar en OSS)
 *  - Transcripción sin conexión por lotes
 *
 * Escenarios no aplicables(usar [DashScopeAsrClient]):
 *  - Entrada de micrófono en vivo(Necesita transmisión)
 *  - Reconocimiento rápido de audio corto.
 *
 * Nota:
 *  - `recognize(audioData, sampleRate)` Requisitos de interfaz PCM bytes,Pero este cliente requiere una URL.
 *    La persona que llama debe utilizar [recognizeFile] pasar URL,en vez de [recognize].
 *  - [recognize] usará [AsrConfig.fileAudioUrl] ir transcribiendo;Devuelve un resultado vacío con un mensaje de error cuando no está configurado
 *    (PCM La transmisión directa requiere primero una red pública URL,Carga de archivos locales/OSS Transmisión directa como punto de expansión posterior).
 *
 * Escrito de forma independiente(Según la documentación oficial de DashScope),Apache 2.0.
 */
class DashScopeFileAsrClient(
    private val config: AsrConfig,
) : AsrClient {

    private val httpClient: OkHttpClient by lazy {
        io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
            .connectTimeout(AsrConstants.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(AsrConstants.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    private companion object {
        const val TAG = "DashScopeFileASR"
        const val SUBMIT_ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription"
        const val QUERY_ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/tasks/"
    }

    /**
     * Transcripción de archivos asincrónica:Envíe una tarea desde una URL de audio y realice una encuesta para obtener resultados.
     *
     * @param audioUrl Audio de acceso público URL(Compatible con mp3/wav/m4a/aac/flac/opus, etc.)
     * @return Resultados del reconocimiento;Retorno del fracaso null
     */
    suspend fun recognizeFile(audioUrl: String): AsrResult? = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) {
            Logger.w(TAG, "apiKey esta vacio")
            return@withContext null
        }
        val taskId = submitTranscription(audioUrl) ?: return@withContext null
        Logger.d(TAG, "submit éxito,task_id=$taskId,Empezar a sondear")
        val result = pollTask(taskId) ?: return@withContext null
        // result.transcription_url apuntar a un JSON,necesito de nuevo fetch
        val transcriptionJson = fetchTranscriptionUrl(result.transcriptionUrl) ?: return@withContext null
        val text = parseTranscriptionJson(transcriptionJson)
        if (text.isBlank()) {
            Logger.w(TAG, "El texto del resultado de la transcripción está vacío")
            return@withContext null
        }
        AsrResult(text = text, isFinal = true, durationMs = result.audioDurationMs)
    }

    /**
     * AsrClient Implementación de interfaz:La transcripción de archivos asincrónica requiere URL en lugar de bytes PCM.
     *
     * darse cuenta:si [AsrConfig.fileAudioUrl] configurado,Utilice directamente la URL configurada para la transcripción;
     * De lo contrario, devolverá un resultado vacío con un mensaje de error explícito.(PCM Los bytes no se pueden cargar directamente,necesito primero URL).
     *
     * Se puede ampliar más tarde:Agregar lógica de carga OSS(Los usuarios deben proporcionar credenciales de OSS),Convierta PCM a wav y cárguelo para obtener la URL.
     */
    override suspend fun recognize(audioData: ByteArray, sampleRate: Int): AsrResult? {
        val url = config.fileAudioUrl.ifBlank {
            Logger.w(TAG, "La transcripción de archivos asincrónica requiere la configuración de fileAudioUrl,Actualmente vacío")
            return AsrResult(text = "", errorMessage = "La transcripción de archivos asincrónica requiere la configuración fileAudioUrl, que actualmente está vacía.")
        }
        return recognizeFile(url)
    }

    override fun close() {
        // OkHttp No hay conexión persistente WebSocket en el lado del cliente,No es necesario cerrar explícitamente
    }

    // ── interno ──────────────────────────────────────────────────────────────

    /** Step 1: Enviar una tarea de transcripción,Devuelve task_id. */
    private suspend fun submitTranscription(audioUrl: String): String? = withContext(Dispatchers.IO) {
        resultOf {
            val requestBody = buildJsonObject {
                put("model", config.model.ifBlank { "paraformer-v2" })
                put(
                    "input",
                    buildJsonObject {
                        put("file_urls", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(audioUrl))))
                    },
                )
                put(
                    "parameters",
                    buildJsonObject {
                        put(
                            "language_hints",
                            kotlinx.serialization.json.JsonArray(
                                (config.language?.takeIf { it.isNotBlank() }?.split(",") ?: listOf("es"))
                                    .map { JsonPrimitive(it.trim()) },
                            ),
                        )
                        put("disfluency_removal_enabled", true)
                        put("phrase_id", JsonPrimitive(""))
                    },
                )
            }
            val req = Request.Builder()
                .url(SUBMIT_ENDPOINT)
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("X-DashScope-DataInspection", "enable")
                .header("Content-Type", "application/json")
                .post(AppJson.encodeToString(JsonObject.serializer(), requestBody).toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Logger.w(TAG, "submit falló: HTTP ${resp.code} ${resp.body.string().take(200)}")
                    return@resultOf null
                }
                val body = resp.body.string()
                val obj = AppJson.decodeFromString(JsonObject.serializer(), body)
                val output = obj["output"]?.jsonObject
                val taskId = output?.get("task_id")?.jsonPrimitive?.contentOrNull
                if (taskId.isNullOrBlank()) {
                    Logger.w(TAG, "submit Falta de respuesta task_id: ${body.take(200)}")
                    return@resultOf null
                }
                taskId
            }
        }.onError { msg, t -> Logger.w(TAG, "submitTranscription falló: $msg", t) }
            .getOrNull()
    }

    /** Step 2: Estado de la tarea de sondeo,Hasta que SE ÉXITO/FALLÓ/se agotó el tiempo de espera. */
    private suspend fun pollTask(taskId: String): TranscriptionResult? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + config.pollTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = queryTask(taskId) ?: return@withContext null
            when (status.taskStatus) {
                "SUCCEEDED" -> {
                    Logger.d(TAG, "task=$taskId SUCCEEDED,Consume mucho tiempo ${System.currentTimeMillis() - (deadline - config.pollTimeoutMs)}ms")
                    return@withContext status.result
                }
                "FAILED" -> {
                    Logger.w(TAG, "task=$taskId FAILED: ${status.message}")
                    return@withContext null
                }
                else -> {
                    // PENDING / RUNNING,Continuar encuestando
                    delay(config.pollIntervalMs)
                }
            }
        }
        Logger.w(TAG, "task=$taskId Tiempo de espera de la encuesta(${config.pollTimeoutMs}ms)")
        null
    }

    /** Estado de la tarea de consulta única. */
    private suspend fun queryTask(taskId: String): TaskStatus? = withContext(Dispatchers.IO) {
        resultOf {
            val req = Request.Builder()
                .url("$QUERY_ENDPOINT$taskId")
                .header("Authorization", "Bearer ${config.apiKey}")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@resultOf null
                val body = resp.body.string()
                val obj = AppJson.decodeFromString(JsonObject.serializer(), body)
                val output = obj["output"]?.jsonObject
                val taskStatus = output?.get("task_status")?.jsonPrimitive?.contentOrNull ?: return@resultOf null
                // DashScope El campo del código de error puede ser un mensaje o code,tener prioridad message
                val message = output["message"]?.jsonPrimitive?.contentOrNull
                    ?: obj["message"]?.jsonPrimitive?.contentOrNull ?: ""
                val result = output["results"]?.jsonObject
                    ?.let { parseResult(it) }
                TaskStatus(taskStatus = taskStatus, message = message, result = result)
            }
        }.onError { msg, t -> Logger.w(TAG, "queryTask($taskId) falló: $msg", t) }
            .getOrNull()
    }

    /** Analice la URL de la transcripción + la duración del audio del objeto de resultados. */
    private fun parseResult(results: JsonObject): TranscriptionResult? {
        // results.transcription_url Apuntar a un archivo JSON URL,Contiene sentences
        val transcriptionUrl = results["transcription_url"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val audioDurationMs = results["audio_duration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        return TranscriptionResult(transcriptionUrl = transcriptionUrl, audioDurationMs = audioDurationMs)
    }

    /** fetch Transcribir archivos JSON(Red pública URL). */
    private suspend fun fetchTranscriptionUrl(url: String): String? = withContext(Dispatchers.IO) {
        resultOf {
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@resultOf null
                resp.body.string()
            }
        }.onError { msg, t -> Logger.w(TAG, "fetchTranscriptionUrl falló: $msg", t) }
            .getOrNull()
    }

    /** Analizar transcripción JSON,Extraiga todas las concatenaciones de texto de oraciones. */
    private fun parseTranscriptionJson(jsonText: String): String {
        return resultOf {
            val obj = AppJson.decodeFromString(JsonObject.serializer(), jsonText)
            val transcripts = obj["transcripts"]?.jsonArray ?: return@resultOf ""
            // Cada transcripción contiene una serie de oraciones.,Cada oración tiene un campo de texto.
            transcripts.joinToString("") { transcriptEl ->
                val transcript = transcriptEl.jsonObject
                val sentences = transcript["sentences"]?.jsonArray ?: return@joinToString ""
                sentences.joinToString("") { sentenceEl ->
                    sentenceEl.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
                }
            }
        }.onError { msg, t -> Logger.w(TAG, "parseTranscriptionJson falló: $msg", t) }
            .getOrNull() ?: ""
    }

    private data class TaskStatus(
        val taskStatus: String,
        val message: String,
        val result: TranscriptionResult?,
    )

    private data class TranscriptionResult(
        val transcriptionUrl: String,
        val audioDurationMs: Long,
    )
}
