package io.zer0.muse.rag

/**
 * v1.133: Rerank Provider interfaz — cross-encoder Reordenar.
 *
 * Opciones de implementación:
 *  - [LocalRerankProvider]:Reordenamiento local simple basado en BM25 + superposición de palabras clave(Sin dependencias,Calidad media)
 *  - CohereRerankProvider:llamar Cohere Rerank API(necesidad de confiar en + API Key,buena calidad)— Se implementará después de introducir dependencias.
 *  - JinaRerankProvider:llamar Jina Rerank API(necesidad de confiar en + API Key,buena calidad)— Se implementará después de introducir dependencias.
 *  - OnnxRerankProvider:locales bge-reranker-base ONNX(Requiere dependencia de onnxruntime-android)— Se implementará después de introducir dependencias.
 *
 * La persona que llama es [RagConfig.rerankEnabled]=true base de tiempo [RagConfig.rerankProvider] Elige implementar,
 * Bajar a cuando no esté disponible [LocalRerankProvider].
 */
interface RerankProvider {
    /**
     * Reordene el conjunto de candidatos.
     * @param query Consulta de usuario
     * @param candidates lista de candidatos(docId/docTitle/content)
     * @param topK El número final de artículos devueltos.
     * @return Lista reordenada(Las puntuaciones se han normalizado a 0-1)
     */
    suspend fun rerank(query: String, candidates: List<RerankCandidate>, topK: Int): List<RerankResult>
}

/** Reordenar los candidatos de entrada. */
data class RerankCandidate(
    val chunkId: String,
    val docId: String,
    val docTitle: String,
    val content: String,
    val chunkIndex: Int,
)

/** Reordenar los resultados de salida. */
data class RerankResult(
    val chunkId: String,
    val docId: String,
    val docTitle: String,
    val content: String,
    val chunkIndex: Int,
    /** reordenar puntuación(0-1,Cuanto más alto, más relevante). */
    val score: Float,
)

/**
 * v1.133: reordenamiento local — Basado en la cobertura del término de consulta + heurística BM25(No se requieren dependencias,Calidad media pero utilizable.).
 *
 * algoritmo:
 *  1. Extraer palabras clave de consulta(longitud≥2 de token)
 *  2. Calcular la cobertura de palabras clave para cada candidato(content Número de palabras clave contenidas en / Número total de palabras clave)
 *  3. Combinado con ponderación TF-IDF estilo BM25
 *
 * Escenarios aplicables:Bajar de categoría sin red/API de reclasificación configurada.
 */
class LocalRerankProvider : RerankProvider {
    override suspend fun rerank(query: String, candidates: List<RerankCandidate>, topK: Int): List<RerankResult> {
        if (candidates.isEmpty()) return emptyList()
        val queryTerms = extractTerms(query)
        if (queryTerms.isEmpty()) {
            // degenerar:Mantener el orden original
            return candidates.take(topK).map {
                RerankResult(it.chunkId, it.docId, it.docTitle, it.content, it.chunkIndex, 0.5f)
            }
        }

        val scored = candidates.map { c ->
            val contentLower = c.content.lowercase()
            val titleLower = c.docTitle.lowercase()
            var hitCount = 0
            var titleBonus = 0f
            for (term in queryTerms) {
                val termLower = term.lowercase()
                if (contentLower.contains(termLower)) hitCount++
                if (titleLower.contains(termLower)) titleBonus += 0.1f
            }
            val coverage = hitCount.toFloat() / queryTerms.size
            // Puntaje integral:Cobertura + Bonificación por acierto de título + Normalización de duración(texto corto primero)
            val lengthPenalty = (1f / kotlin.math.sqrt(c.content.length.toFloat() / 100f + 1f))
            val score = (coverage * 0.8f + titleBonus) * lengthPenalty
            RerankResult(c.chunkId, c.docId, c.docTitle, c.content, c.chunkIndex, score.coerceIn(0f, 1f))
        }
        return scored.sortedByDescending { it.score }.take(topK)
    }

    private fun extractTerms(query: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in query) {
            if (ch.isLetterOrDigit()) {
                current.append(ch)
            } else {
                if (current.isNotEmpty()) {
                    tokens.add(current.toString())
                    current.clear()
                }
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        // Filtrar palabras(ruidoso),Longitud reservada≥2 de token
        return tokens.filter { it.length >= 2 }.distinct()
    }
}
