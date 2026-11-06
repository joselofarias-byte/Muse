package io.zer0.muse.rag

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsDao
import io.zer0.muse.data.session.MessageFtsManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * v1.133: Servicio de búsqueda híbrido — BM25(FTS4) + Fusión RRF de coseno vectorial.
 *
 * algoritmo:
 *  1. Ejecución de recuperaciones de BM25 FTS en paralelo(top-K1)y búsqueda de vectores(top-K2)
 *  2. usar RRF(Reciprocal Rank Fusion)fusionar:
 *     score = Σ 1/(rank_i + RRF_K)
 *     entre ellos RRF_K=60 es una constante empírica
 *  3. Tome en orden descendente de puntuación RRF top-K
 *
 * Ventajas:
 *  - Los vectores son buenos en similitud semántica.(sinónimo/contexto)
 *  - BM25 Bueno en coincidencia exacta(nombre propio/identificador de código)
 *  - RRF No se basa en la comparabilidad de la puntuación bruta,Robusto
 */
class HybridSearchService(
    private val ftsDao: KnowledgeChunkFtsDao,
    private val vectorSearch: VectorSearchService,
    /**
     * P2-31: BM25-only Análisis de contenido de éxito(chunkId → Metadatos).
     * FTS La línea de golpe sólo toma chunkId+score,Las visitas anteriores exclusivas de BM25 se descartaron en su totalidad porque no se pudo obtener el contenido.;
     * Estos resultados también se pueden devolver después de inyectar el análisis DAO.null = prueba/Mantener el comportamiento anterior cuando no se inyecta(saltar).
     */
    private val bm25MetaResolver: (suspend (List<String>) -> Map<String, ChunkMeta>)? = null,
) {
    /** P2-31: BM25 completar el contenido/Metadatos necesarios para el filtrado de decisiones. */
    data class ChunkMeta(
        val docId: String,
        val docTitle: String,
        val content: String,
        val chunkIndex: Int,
        /** B4-03: metadatos fragmentados JSON(Fuente/tag Para fines de filtrado). */
        val metadataJson: String = "",
        /** B4-03: Marca de tiempo de creación de fragmentos(Para filtrado y determinación de rangos de tiempo). */
        val createdAt: Long = 0L,
    )

    /** Resultados de búsqueda mixtos — Reemplace la similitud bruta con la puntuación RRF. */
    data class HybridResult(
        val docId: String,
        val docTitle: String,
        val chunkContent: String,
        val chunkIndex: Int,
        val chunkId: String,
        /** RRF puntuación de fusión(0-1 entre,Cuanto más alto, más relevante). NOTA: Esto es**puntuación relativamente más alta**La proporción, no la similitud. */
        val rrfScore: Float,
        /**
         * P1-6: Puntuación bruta (similitud de coseno vectorial).BM25-only 0 aciertos.
         *
         * UI Este campo debe usarse cuando se muestra "similitud" en lugar de [rrfScore] —— Este último siempre es hasta 1,0,
         * No es una verdadera similitud.
         */
        val rawScore: Float = 0f,
        /** Ya sea golpeado por BM25 y vector al mismo tiempo(true = doble golpe,Alta credibilidad). */
        val bothHit: Boolean,
    )

    /**
     * Realizar una búsqueda híbrida.
     *
     * @param query Consulta original del usuario(Usado después del participio FTS MATCH)
     * @param queryVector vector de consulta
     * @param topK El número final de artículos devueltos.
     * @param threshold Umbral de similitud para la recuperación de vectores
     * @param mmrLambda MMR peso(Pasar a búsqueda de vectores)
     * @param bm25Weight BM25 Peso RRF en carretera(Predeterminado 1.0)
     * @param vectorWeight Peso RRF de ruta vectorial(Predeterminado 1.0)
     * @param scopeDocIds Limitar el alcance de la búsqueda(Opcional)
     * @param metadataFilter Filtros de metadatos(Opcional,null/vacío = Sin filtrado).docIds/Título/Fuente/tag/tiempo
     *   Actúa simultáneamente en enlaces vectoriales y BM25:Vector al lado de [VectorSearchService.search] filtrar,
     *   BM25 Prensa lateral en este método [bm25MetaResolver] Filtrado de metadatos de documentos analizados,Asegúrese de que los resultados en ambos lados sean consistentes.
     * @param vectorCandidateK Número de candidatos a vectores(Predeterminado topK×3,Ampliar el grupo de candidatos del FRR)
     * @param bm25CandidateK BM25 número de candidatos(Predeterminado topK×3)
     */
    suspend fun hybridSearch(
        query: String,
        queryVector: FloatArray,
        topK: Int,
        threshold: Float,
        mmrLambda: Float,
        bm25Weight: Float = 1f,
        vectorWeight: Float = 1f,
        scopeDocIds: List<String>? = null,
        metadataFilter: VectorSearchService.MetadataFilter? = null,
        vectorCandidateK: Int = topK * 3,
        bm25CandidateK: Int = topK * 3,
    ): List<HybridResult> {
        // 1. Recuperación paralela (simultaneidad de rutina real)
        //
        // P1-4 Reparación (informe de auditoría): La antigua anotación decía“búsqueda paralela(Concurrencia de rutinas)”, pero resultOf es solo
        // try/catch embalaje (ver common/Result.kt),**sin ningún async**, las dos líneas se ejecutan secuencialmente.
        // Cuando la base de datos es grande, el tiempo de búsqueda del vector se superpone completamente al tiempo del BM25.
        val (vectorResults, bm25Hits) = coroutineScope {
            val vectorDeferred = async {
                resultOf {
                    vectorSearch.search(queryVector, vectorCandidateK, threshold, mmrLambda, scopeDocIds, metadataFilter)
                }
            }
            val bm25Deferred = async {
                resultOf { ftsDao.searchBm25(buildFtsQuery(query), bm25CandidateK) }
            }
            vectorDeferred.await().getOrNull().orEmpty() to bm25Deferred.await().getOrNull().orEmpty()
        }

        Logger.d(
            "HybridSearchService",
            "Búsqueda híbrida:vector=${vectorResults.size}, bm25=${bm25Hits.size}, query=$query",
        )

        if (vectorResults.isEmpty() && bm25Hits.isEmpty()) return emptyList()

        // B4-03: El filtrado de metadatos solo está habilitado cuando no es nulo(null / vacío filter = Sin filtrado,compatible con versiones anteriores).
        val filter = metadataFilter?.takeIf { !it.isEmpty() }

        // 2. RRF Fusión
        // camino vectorial:Ordenar por puntuación en orden descendente(Ya clasificado en la búsqueda,Pero solo para estar seguro, organícelo nuevamente.)
        //   El filtrado lateral del vector ha sido implementado por VectorSearchService.search(metadataFilter) Hecho dentro del enlace.
        val vectorRanked = vectorResults.sortedByDescending { it.score }
        val vectorChunkIds = vectorRanked.map { it.chunkId }.toHashSet()
        // BM25 camino:score orden ascendente(SQLite bm25 Devolver valor negativo,Cuanto más pequeño, más relevante),negar como adelante
        val bm25Sorted = bm25Hits.sortedBy { it.score }

        // P2-31/B4-03: Análisis de metadatos de documentos para visitas de BM25(resolver Al inyectar).
        //  - sin filtro:Complete solo el contenido alcanzado por bm25-only(viejo comportamiento);
        //  - hay filtrado:Todos los hits de BM25 deben obtener el docId/título/Metadatos,Los talentos se filtran de la misma forma que los enlaces vectoriales.
        val bm25MetaById: Map<String, ChunkMeta> = if (bm25MetaResolver != null) {
            val needIds = if (filter != null) {
                bm25Sorted.map { it.chunkId }
            } else {
                bm25Sorted.map { it.chunkId }.filter { it !in vectorChunkIds }
            }.distinct()
            if (needIds.isEmpty()) {
                emptyMap()
            } else {
                runCatching { bm25MetaResolver(needIds) }.getOrNull().orEmpty()
            }
        } else {
            emptyMap()
        }

        // B4-03: BM25 El filtrado debe realizarse antes de la fusión RRF. — Los aciertos que se filtran no deben contribuir a la puntuación del RRF.
        // Las visitas cuyos metadatos no se pueden analizar se tratan como "Error" cuando el filtrado está activado.,Evite publicar documentos no verificados(lado seguro).
        val bm25Ranked = if (filter == null) {
            bm25Sorted
        } else {
            bm25Sorted.filter { hit ->
                bm25MetaById[hit.chunkId]?.let { m ->
                    filter.matches(m.docId, m.docTitle, m.metadataJson, m.createdAt)
                } == true
            }
        }

        val rrfScores = mutableMapOf<String, Float>() // chunkId -> rrfScore
        val metaMap = mutableMapOf<String, VectorSearchService.SearchResult>()
        val bm25ChunkIds = mutableSetOf<String>()

        // Contribución del vector FRR
        vectorRanked.forEachIndexed { rank, r ->
            val contribution = vectorWeight * (1.0f / (rank + 1 + RRF_K))
            rrfScores[r.chunkId] = (rrfScores[r.chunkId] ?: 0f) + contribution
            metaMap[r.chunkId] = r
        }

        // BM25 RRF Contribuir(Sólo aporta puntos,El contenido todavía está sujeto a los resultados del vector.;Si se golpea solo en BM25, construye la entrada por separado.)
        // P1-7 Reparación (informe de auditoría): threshold Anteriormente solo funcionaba en rutas vectoriales,BM25 Los hits participan en RRF sin importar cuán débiles sean,
        // BM25 puede introducir un bloque que contiene palabras clave pero que tiene una semántica irrelevante hasta topK.
        // SQLite bm25() Devuelve un valor negativo y cuanto menor es el valor, más relevante es; aquí está normalizado a 0~1 La fuerza relativa del umbral post-atascado.
        val bm25RawRange = if (bm25Ranked.isEmpty()) {
            0.0
        } else {
            (bm25Ranked.last().score - bm25Ranked.first().score).takeIf { it > 0.0 } ?: 0.0
        }
        bm25Ranked.forEachIndexed { rank, hit ->
            // Fuerza relativa: el golpe clasificado en el puesto 0 es 1,0, el más débil es 0,0 (todos 1,0 cuando no hay diferencia de puntos)
            val strength = if (bm25RawRange <= 0.0) {
                1f
            } else {
                (1.0 - (hit.score - bm25Ranked.first().score) / bm25RawRange).toFloat().coerceIn(0f, 1f)
            }
            // threshold <= 0 Indica que el filtrado no está habilitado (consistente con la semántica de ruta vectorial)
            if (threshold > 0f && strength < threshold) {
                return@forEachIndexed
            }
            val contribution = bm25Weight * (1.0f / (rank + 1 + RRF_K))
            rrfScores[hit.chunkId] = (rrfScores[hit.chunkId] ?: 0f) + contribution
            bm25ChunkIds.add(hit.chunkId)
        }

        // 3. Resultado de la construcción(Priorizar el contenido del resultado vectorial.)
        // P2-31: anteriormente `filter { metaMap[it.key] != null }` Descartar los impactos exclusivos del BM25 en su totalidad —
        // golpe preciso(nombre propio/identificador de código)Exactamente el punto fuerte del BM25,No debería desecharse. inyectar resolver
        // Luego complete los metadatos de contenido para visitas exclusivas de BM25;No analizable(Eliminado de la biblioteca)Todavía saltando según el antiguo comportamiento.
        val bm25OnlyIds = rrfScores.keys.filter { it !in metaMap }
        for (chunkId in bm25OnlyIds) {
            val m = bm25MetaById[chunkId] ?: continue
            metaMap[chunkId] = VectorSearchService.SearchResult(
                docId = m.docId,
                docTitle = m.docTitle,
                chunkContent = m.content,
                score = 0f,
                chunkIndex = m.chunkIndex,
                chunkId = chunkId,
            )
        }
        val maxScore = rrfScores.values.maxOrNull() ?: 0f
        return rrfScores.entries
            .filter { metaMap[it.key] != null } // Mantenga solo los resultados con contenido(Salto irresoluble solo para BM25)
            .map { (chunkId, score) ->
                val r = metaMap[chunkId]!!
                HybridResult(
                    docId = r.docId,
                    docTitle = r.docTitle,
                    chunkContent = r.chunkContent,
                    chunkIndex = r.chunkIndex,
                    chunkId = chunkId,
                    // P1-6 Reparación (informe de auditoría): rrfScore es la relación entre "relativo al puntaje más alto" (el más alto es siempre 1,0),
                    // No semejanza coseno.UI Pero dice "similitud 0,83"—— Ese número es falso.
                    // Al mismo tiempo, rawScore (coseno vectorial / BM25 Ninguno) se conserva para que la interfaz de usuario muestre valores reales y creíbles.
                    rrfScore = if (maxScore > 0) score / maxScore else 0f,
                    rawScore = r.score.toFloat(),
                    bothHit = chunkId in bm25ChunkIds,
                )
            }
            .sortedByDescending { it.rrfScore }
            .take(topK)
    }

    companion object {
        /** RRF constante empírica(Valor estándar 60). */
        private const val RRF_K = 60

        /**
         * Convierta la consulta original del usuario a la sintaxis FTS4 MATCH.
         * estrategia simple:espacio de prensa/Palabras de segmentación de caracteres chinos.,Conecta con espacios(FTS4 Predeterminado AND).
         * consulta larga(>10 palabra)Utilice OR para evitar resultados nulos.
         */
        fun buildFtsQuery(query: String): String {
            val filtered = MessageFtsManager.toNgram(query)
                .split(' ')
                .filter { it.length >= 2 }
                .distinct()
                .take(10)
            if (filtered.isEmpty()) {
                return "\"${query.take(50).replace("\"", " ")}\"" // fallback frase
            }
            // El ngram chino debe citarse según el límite simbólico;ingles/Los números conservan palabras simples para compatibilidad con la sintaxis FTS4 existente.
            val rendered = filtered.map { token ->
                if (token.any { it.code > 127 }) "\"$token\"" else token
            }
            // Múltiples tokens separados por espacios(FTS4 Predeterminado AND);Si hay demasiados tokens, se degradará a OR.
            return if (rendered.size <= 5) {
                rendered.joinToString(" ")
            } else {
                rendered.joinToString(" OR ")
            }
        }
    }
}
