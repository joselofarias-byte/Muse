package io.zer0.muse.rag

import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.common.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * v1.54: nube Embedding Provider(OpenAI Interfaz compatible).
 *
 * Reutilizar [ProviderConfig] de baseUrl + apiKey + embeddingsPath,Llame a POST /incrustaciones.
 * Admite todas las API de integración compatibles con OpenAI, como OpenAI / Zhipu / Tongyi / DeepSeek / Dark Side of the Moon / Doubao.
 *
 * Formato de solicitud:
 * ```
 * POST {baseUrl}/embeddings
 * Authorization: Bearer {apiKey}
 * {"model": "...", "input": ["text1", "text2"]}
 * ```
 *
 * formato de respuesta(OpenAI compatibles):
 * ```json
 * {"data": [{"embedding": [0.01, ...], "index": 0}, ...]}
 * ```
 *
 * @param config Provider Configuración(Contiene baseUrl + apiKey + embeddingsPath)
 * @param model embedding Nombre del modelo(El proveedor se utiliza de forma predeterminada cuando la cadena está vacía.)
 * @param client Reutilizar Koin inyectado OkHttpClient(named("chat"))
 */
class OpenAIEmbeddingProvider(
    private val config: ProviderConfig,
    private val model: String,
    private val client: OkHttpClient,
) : EmbeddingProvider {

    override val id: String = "cloud-${config.id}"
    override val displayName: String = "${config.displayName} (Cloud)"
    override val modelName: String = model.ifBlank { defaultModel() }

    /** Las dimensiones se obtienen dinámicamente a partir de la respuesta después de la primera inserción.,Anteriormente, se utilizaba -1 como marcador de posición. */
    @Volatile
    private var cachedDimension: Int = -1

    /**
     * v2.6.0 (AUD-RAG-F3): Si se han medido las dimensiones reales.
     *
     * [dimension] Devuelto anteriormente cuando en realidad no se probó [guessDimension] valor estimado,Y RagService
     * HNSW embedding La huella digital contiene esta dimensión. —— Cuando el valor estimado no coincide con el valor real,La huella digital se escribirá primero basándose en conjeturas,
     * Después de ser incrustado por primera vez, adquiere la dimensión real.,Como resultado, cada arranque en frío determina los cambios de huellas dactilares y reconstruye el índice.
     * Ahora se utiliza el lado de la huella digital. [stableDimensionOrNull]:Devolver cuando no se mide null,La persona que llama toma el marcador de posición estable.
     * huella dactilar de participación,Evite adivinar valores debido a la contaminación de las huellas dactilares.
     */
    override val dimension: Int
        get() = if (cachedDimension > 0) cachedDimension else guessDimension(modelName)

    /** Dimensiones reales medidas;Devuelve nulo si no se mide. */
    val measuredDimension: Int?
        get() = cachedDimension.takeIf { it > 0 }

    /**
     * Dimensiones para huellas dactilares.:Valores reales medidos y utilizados,Marcador de posición estable no probado en la práctica "unknown"(en lugar de una suposición).
     * No probado/Se ha medido que las huellas dactilares en los dos estados son diferentes.,Pero cada uno es estable —— Una vez completada la medición real, la huella digital se fija en la dimensión real.,
     * Los índices ya no se reconstruirán cada inicio en frío debido a saltos en el valor estimado.
     */
    override val fingerprintDimension: String
        get() = measuredDimension?.toString() ?: "unknown"

    private val embeddingsPath: String by lazy {
        val specific = config.resolvedSpecific()
        if (specific is ProviderSpecificConfig.OpenAI) {
            specific.embeddingsPath.ifBlank { "/embeddings" }
        } else {
            "/embeddings"
        }
    }

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        val url = config.resolvedBaseUrl().trimEnd('/') + embeddingsPath
        val body = buildJsonObject {
            put("model", modelName)
            put("input", JsonArray(texts.map { JsonPrimitive(it) }))
        }.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(body)
            .build()

        coroutineContext.ensureActive()
        // v1.0.30: Agregue un tiempo de espera de 10 segundos para evitar que la API de inserción bloquee todo el enlace generado.
        val response = kotlinx.coroutines.withTimeout(10_000L) {
            client.newCall(request).execute()
        }
        if (!response.isSuccessful) {
            val errBody = response.body?.string()?.take(500) ?: ""
            throw RuntimeException("Embedding API failed ${response.code}: $errBody")
        }
        val raw = response.body?.string()
            ?: throw RuntimeException("Embedding API returned empty body")
        val json = AppJson.parseToJsonElement(raw).jsonObject
        val dataArray = json["data"]?.jsonArray
            ?: throw RuntimeException("Embedding API response missing data field: ${raw.take(200)}")
        // Ordene por índice para garantizar que el orden sea coherente con la entrada
        val sorted = dataArray.sortedBy { it.jsonObject["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0 }
        val result = sorted.map { item ->
            val embArray = item.jsonObject["embedding"]?.jsonArray
                ?: throw RuntimeException("Embedding response item missing embedding field")
            FloatArray(embArray.size) { i -> embArray[i].jsonPrimitive.floatOrNull ?: 0f }
        }
        if (result.isNotEmpty()) cachedDimension = result[0].size
        result
    }

    /** Adivina las dimensiones según el nombre del modelo.(Para mostrar la interfaz de usuario antes de la primera llamada). */
    private fun guessDimension(model: String): Int {
        val m = model.lowercase()
        return when {
            m.contains("text-embedding-3-large") -> 3072
            m.contains("text-embedding-3-small") -> 1536
            m.contains("text-embedding-ada") -> 1536
            m.contains("embedding-2") -> 1024 // espectro de sabiduría embedding-2
            m.contains("text-embedding-v3") -> 1024 // significado general text-embedding-v3
            m.contains("bge-large") -> 1024
            m.contains("bge-small") -> 512
            m.contains("minilm") -> 384
            else -> 1024 // Suposición predeterminada
        }
    }

    /** Devuelve el modelo de incrustación predeterminado según el tipo de proveedor.(cloudModel Llamado cuando está vacío). */
    private fun defaultModel(): String = when (config.type) {
        io.zer0.ai.core.ProviderType.OPENAI, io.zer0.ai.core.ProviderType.OPENAI_RESPONSES -> "text-embedding-3-small"
        io.zer0.ai.core.ProviderType.ANTHROPIC ->
            throw IllegalStateException(
                "Anthropic does not provide an OpenAI-compatible embedding API. Please specify an embedding model in RAG settings or switch to an OpenAI-compatible Provider",
            )
        io.zer0.ai.core.ProviderType.GEMINI ->
            throw IllegalStateException(
                "Gemini's embedding API is not fully compatible with OpenAI's. Please specify an embedding model in RAG settings or switch to an OpenAI-compatible Provider",
            )
    }
}
