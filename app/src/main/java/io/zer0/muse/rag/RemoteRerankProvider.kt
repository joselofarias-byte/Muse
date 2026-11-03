package io.zer0.muse.rag

import io.zer0.common.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * P2-6(informe de auditoría): OpenAI Proveedor de cambio de rango en la nube compatible.
 *
 * El informe afirma:rerank No existe ninguna solución en la nube,`LocalRerankProvider` Inactivo básico para chinos
 * (`extractTerms` Trate toda la oración china como una muestra `contains`, casi imposible de acertar,
 * todos los candidatos score=0,UI La "similitud" también se mostrará como 0,00).
 *
 * Aquí hay un conjunto **OpenAI compatibles `/rerank` Acuerdo**,SiliconFlow / Jina / Cohere
 * Puede reutilizar la misma implementación, solo configure baseUrl + model + key:
 *
 * ```
 * POST {baseUrl}/rerank
 * {"model": "...", "query": "...", "documents": ["...","..."], "top_n": N}
 * →
 * {"results": [{"index": 0, "relevance_score": 0.93}, ...]}
 * ```
 *
 * La diferencia entre cada familia depende de [responseShape] Tolerancia:Jina usar `results[].relevance_score`,
 * Cohere usar `results[].relevance_score`, utilizado para implementación parcial `data[].score`.
 */
class RemoteRerankProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val httpPost: suspend (url: String, headers: Map<String, String>, body: String) -> String,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : RerankProvider {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun rerank(query: String, candidates: List<RerankCandidate>, topK: Int): List<RerankResult> {
        if (candidates.isEmpty()) return emptyList()
        if (query.isBlank()) {
            return candidates.take(topK).map {
                RerankResult(it.chunkId, it.docId, it.docTitle, it.content, it.chunkIndex, 0.5f)
            }
        }

        val url = baseUrl.trimEnd('/') + "/rerank"
        val requestBody = json.encodeToString(
            RerankRequest.serializer(),
            RerankRequest(
                model = model,
                query = query,
                documents = candidates.map { it.content },
                topN = topK,
            ),
        )
        val headers = buildMap {
            put("Content-Type", "application/json")
            if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
        }

        val raw = runCatching { httpPost(url, headers, requestBody) }
            .onFailure { Logger.w(TAG, "La solicitud de cambio de rango en la nube falló y el cambio de rango local se degradó.: ${it.message}") }
            .getOrNull() ?: return LocalRerankProvider().rerank(query, candidates, topK)

        val parsed = runCatching { parseResponse(raw) }
            .onFailure { Logger.w(TAG, "El análisis de la respuesta de reclasificación en la nube falló y se degradó a reclasificación local: ${it.message}") }
            .getOrNull()
        if (parsed.isNullOrEmpty()) {
            Logger.w(TAG, "La reclasificación en la nube devuelve resultados vacíos y degrada la reclasificación local.")
            return LocalRerankProvider().rerank(query, candidates, topK)
        }

        // Mapear de nuevo al candidato según el índice devuelto; los índices fuera de límites se ignoran directamente (diferentes implementaciones ocasionalmente tienen base 1)
        val mapped = parsed.mapNotNull { item ->
            val idx = item.index
            val candidate = candidates.getOrNull(idx)
                ?: candidates.getOrNull(idx - 1)
                ?: return@mapNotNull null
            RerankResult(
                chunkId = candidate.chunkId,
                docId = candidate.docId,
                docTitle = candidate.docTitle,
                content = candidate.content,
                chunkIndex = candidate.chunkIndex,
                score = item.score.coerceIn(0f, 1f),
            )
        }
        return if (mapped.isEmpty()) {
            LocalRerankProvider().rerank(query, candidates, topK)
        } else {
            mapped.sortedByDescending { it.score }.take(topK)
        }
    }

    /** Tolera múltiples estructuras de respuesta:results[] / data[],relevance_score / score. */
    private fun parseResponse(raw: String): List<ScoredIndex> {
        val root = json.parseToJsonElement(raw)
        return runCatching {
            json.decodeFromJsonElement(RerankResponse.serializer(), root).let { resp ->
                val items = resp.results ?: resp.data ?: emptyList()
                items.mapNotNull { item ->
                    val score = item.relevanceScore ?: item.score ?: return@mapNotNull null
                    ScoredIndex(item.index, score.toFloat())
                }
            }
        }.getOrElse {
            // Seguridad flexible: escanee directamente el índice + cualquier campo de puntuación en la matriz de resultados/datos
            val obj = root as? kotlinx.serialization.json.JsonObject ?: return emptyList()
            val arr = (obj["results"] ?: obj["data"]) as? kotlinx.serialization.json.JsonArray
                ?: return emptyList()
            arr.mapNotNull { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val idx = (o["index"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                    ?: return@mapNotNull null
                val sc = (o["relevance_score"] ?: o["score"] ?: o["relevance"]) as? kotlinx.serialization.json.JsonPrimitive
                    ?: return@mapNotNull null
                ScoredIndex(idx, sc.content.toFloatOrNull() ?: return@mapNotNull null)
            }
        }
    }

    private data class ScoredIndex(val index: Int, val score: Float)

    companion object {
        private const val TAG = "RemoteRerank"
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}

@Serializable
private data class RerankRequest(
    val model: String,
    val query: String,
    val documents: List<String>,
    @SerialName("top_n") val topN: Int,
)

@Serializable
private data class RerankResponse(
    val results: List<RerankItem>? = null,
    val data: List<RerankItem>? = null,
)

@Serializable
private data class RerankItem(
    val index: Int = 0,
    @SerialName("relevance_score") val relevanceScore: Double? = null,
    val score: Double? = null,
)
