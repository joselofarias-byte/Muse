package io.zer0.muse.rag

import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.util.PriorityQueue
import kotlin.math.sqrt

/**
 * v1.54: Servicio de recuperación de vectores(recorrido de similitud del coseno).
 *
 * v1.133 Transformación:
 *  - embedding leer:BLOB Prioridad,Ninguno BLOB fallback JSON(Compatible con datos antiguos)
 *  - [mmrLambda] parámetros:MMR reordenamiento de la diversidad(0=pura diversidad,1=pura similitud)
 *  - [scopeDocIds] parámetros:Limitar el alcance de la búsqueda(@mention Se utiliza para recuperación dirigida/encuadernación asistente KB)
 *  - [metadataFilter] parámetros:Filtrado de metadatos(docIds/Título/Fuente/tag/rango de tiempo),Vector y BM25 dos enlaces comparten la misma decisión
 *
 * @param chunkPageProvider Carga paginada indexada chunk(por la persona que llama join docTitle)
 * @param chunkCountProvider Número total de fragmentos indexados
 */
class VectorSearchService(
    private val chunkPageProvider: suspend (limit: Int, offset: Int) -> List<ChunkWithDoc>,
    private val chunkCountProvider: suspend () -> Int,
    /**
     * v1.133: Paginación filtrada por docIds provider(Se utiliza para @mention de recuperación dirigida/enlace de ayuda KB).
     * null Indica tomar la cantidad total provider(compatible con versiones anteriores).
     */
    private val chunkPageByDocIdsProvider: (suspend (docIds: List<String>, limit: Int, offset: Int) -> List<ChunkWithDoc>)? = null,
    /**
     * B4-03: Paginación con filtrado de metadatos. provider(SQL filtrado de capas).
     * null Si se transmite [MetadataFilter],VectorSearchService se filtrará en la memoria.
     */
    private val chunkPageByMetadataProvider: (suspend (filter: MetadataFilter, limit: Int, offset: Int) -> List<ChunkWithDoc>)? = null,
) {
    data class SearchResult(
        val docId: String,
        val docTitle: String,
        val chunkContent: String,
        val score: Float,
        val chunkIndex: Int,
        /** v1.133: chunkId(Se utiliza para hacer referencia al posicionamiento de renderizado.). */
        val chunkId: String = "",
        /**
         * v1.133: ¿Es un documento interno?(Documentos de desarrollo preestablecidos devdoc, etc.).
         *
         * por [io.zer0.muse.rag.RagService.retrieve] Durante la fase de reabastecimiento de resultados de
         * [io.zer0.muse.data.knowledge.KnowledgeDocEntity.isInternal] Inyección de consultas por lotes,
         * reemplazo unificado `docId.startsWith("devdoc-")` Codificado.
         * Predeterminado false(Antes de rellenar/Tomar el valor predeterminado cuando falla la consulta,seguridad conductual).
         */
        val isInternal: Boolean = false,
    )

    /** chunk + Título del documento(Consulta por unión de la persona que llama). */
    data class ChunkWithDoc(
        val chunkId: String,
        val docId: String,
        val docTitle: String,
        val content: String,
        /** viejo embedding JSON(Leer compatible). */
        val embedding: String,
        /** v1.133: nuevo embedding BLOB(Leer primero). */
        val embeddingBlob: ByteArray? = null,
        val chunkIndex: Int,
        /** B4-03: Metadatos JSON(Filtro de memoria para uso de bolsillo.). */
        val metadataJson: String = "{}",
        /** B4-03: Crear marca de tiempo(Filtro de memoria para uso de bolsillo.). */
        val createdAt: Long = 0L,
    )

    /**
     * B4-03: Filtros de metadatos(Todo opcional,Todas son relaciones “Y”;Vacío significa que no hay restricciones).
     *
     * Condiciones a nivel de documento([docIds]/[titleKeyword])con condiciones a nivel de bloque([sourceKeyword]/[tag]/[startTime]/[endTime])
     * Unificado por [matches] juicio,Las búsquedas vectoriales comparten la misma semántica que las búsquedas BM25,Evite la inconsistencia en los resultados del filtrado de los dos enlaces.
     */
    data class MetadataFilter(
        /** Colección de identificación de documentos calificados(vacío = No filtrar por documento). */
        val docIds: List<String> = emptyList(),
        /** etiquetar etiqueta precisa en metadata_json fragmentado(vacío = No filtrar por etiqueta). */
        val tag: String = "",
        /** Límite inferior en el tiempo de creación de fragmentos(0 = sin limite,Contiene límites). */
        val startTime: Long = 0L,
        /** Límite superior del tiempo de creación del bloque(0 = sin limite,Contiene límites). */
        val endTime: Long = 0L,
        /** palabra clave del título(Ignorar caso incluye coincidencias,Pulsar título del documento;vacío = No filtrar por título). */
        val titleKeyword: String = "",
        /** palabra clave fuente(Ignorar caso incluye coincidencias,Campos de acceso como fuente/archivo de metadata_json fragmentado;vacío = No filtrar por fuente). */
        val sourceKeyword: String = "",
    ) {
        fun isEmpty(): Boolean = docIds.isEmpty() && tag.isBlank() && startTime == 0L && endTime == 0L &&
            titleKeyword.isBlank() && sourceKeyword.isBlank()

        /**
         * B4-03: Determine si un solo bloque cumple con todas las condiciones de filtrado.
         *
         * @param docId Documento de pertenencia ID(corresponder [docIds])
         * @param docTitle Título del documento(corresponder [titleKeyword])
         * @param metadataJson metadatos fragmentados JSON(corresponder [sourceKeyword]/[tag])
         * @param createdAt Marca de tiempo de creación de fragmentos(corresponder [startTime]/[endTime])
         */
        fun matches(docId: String, docTitle: String, metadataJson: String, createdAt: Long): Boolean {
            if (docIds.isNotEmpty() && docId !in docIds) return false
            if (titleKeyword.isNotBlank() && !docTitle.contains(titleKeyword, ignoreCase = true)) return false
            if (sourceKeyword.isNotBlank() && !metadataJson.contains(sourceKeyword, ignoreCase = true)) return false
            if (tag.isNotBlank() && !metadataJson.contains("\"$tag\"")) return false
            if (startTime > 0L && createdAt < startTime) return false
            if (endTime > 0L && createdAt > endTime) return false
            return true
        }
    }

    private data class CachedVector(
        val chunkId: String,
        val docId: String,
        val docTitle: String,
        val content: String,
        val vector: FloatArray,
        val norm: Float,
        val chunkIndex: Int,
    )

    /**
     * caché vectorial: clave = scope|metadata Condiciones del filtro.
     *
     * ¿Por qué es**entradas múltiples acotadas**En lugar de una sola entrada: la implementación anterior solo conserva una copia y toda la tabla se recarga tan pronto como cambia la clave.
     * La combinación más común en el uso real es "sin filtrado" y "filtrado con una etiqueta fija" que aparecen alternativamente
     * (El diálogo principal recorre toda la base de datos,@mention o recuperación dirigida con filtrado), el caché de entrada única irá y viene entre los dos.
     *
     * ¿Por qué sólo quedarse? [MAX_CACHE_ENTRIES] Tiras: Máximo tira única [CACHE_THRESHOLD] un vector de 768 dimensiones,
     * Cada artículo tiene texto y aumentar el número de artículos significa aumentar la memoria. Las dos líneas cubren exactamente la alternancia de "biblioteca completa + un filtro",
     * Habrá que volver a argumentar más en función del presupuesto de memoria real.
     */
    private val cacheEntries = object : LinkedHashMap<String, List<CachedVector>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<CachedVector>>): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    fun invalidateCache() {
        synchronized(cacheEntries) { cacheEntries.clear() }
    }

    /**
     * v1.133: Buscar — Admite filtrado MMR/scopeDocIds.
     *
     * @param queryVector vector de consulta
     * @param topK Devolver el número de artículos
     * @param threshold umbral de similitud
     * @param mmrLambda MMR peso de la diversidad(1.0=Clasificación de similitud pura,Desactivar MMR;0<λ<1=MMR reorganizar)
     * @param scopeDocIds Limitar el alcance de la búsqueda(vacío=Base de datos completa,null=Compatible con versiones anteriores de toda la biblioteca)
     */
    suspend fun search(
        queryVector: FloatArray,
        topK: Int,
        threshold: Float,
        mmrLambda: Float = 1.0f,
        scopeDocIds: List<String>? = null,
        metadataFilter: MetadataFilter? = null,
    ): List<SearchResult> {
        val queryNorm = norm(queryVector)
        if (queryNorm == 0f) return emptyList()

        // B4-03: metadata ruta de filtro — docIds/tag/tiempo empujado hacia abajo SQL provider(Evite el caché completo).
        // P2-33: Tirar en lotes(La implementación anterior pasó Int.MAX_VALUE,Cargue todos los resultados del filtrado en la memoria a la vez)
        // Nota:SQL provider Solo cubre docIds/etiqueta/hora,Título/La fuente y otras condiciones aún deben determinarse minuciosamente en la memoria.,
        //       Por lo tanto applyMetadataInMemory=true(SQL para optimización,Se considera que la memoria es la única fuente verdadera de semántica).
        if (metadataFilter != null && !metadataFilter.isEmpty() && chunkPageByMetadataProvider != null) {
            return scanPagedProvider(
                queryVector = queryVector,
                queryNorm = queryNorm,
                topK = topK,
                threshold = threshold,
                mmrLambda = mmrLambda,
                applyMetadataInMemory = true,
                metadataFilter = metadataFilter,
                pageProvider = { limit, offset -> chunkPageByMetadataProvider(metadataFilter, limit, offset) },
            )
        }
        // v1.133: scope ruta de filtro — ir docIds provider(No usar caché completo)
        // P2-33: También extraiga lotes:La búsqueda dirigida ya no cubre todo el alcance de la búsqueda.(dakuko >2000 chunk)carga única
        if (scopeDocIds != null && scopeDocIds.isNotEmpty() && chunkPageByDocIdsProvider != null) {
            return scanPagedProvider(
                queryVector = queryVector,
                queryNorm = queryNorm,
                topK = topK,
                threshold = threshold,
                mmrLambda = mmrLambda,
                applyMetadataInMemory = true,
                metadataFilter = metadataFilter,
                pageProvider = { limit, offset -> chunkPageByDocIdsProvider(scopeDocIds, limit, offset) },
            )
        }

        val total = resultOf { chunkCountProvider() }.getOrNull() ?: 0
        if (total == 0) return emptyList()

        if (total <= CACHE_THRESHOLD) {
            val vectors = getOrLoadCache(total, scopeDocIds, metadataFilter)
            if (vectors.isEmpty()) return emptyList()
            val candidates = vectors.mapNotNull { v ->
                val score = scoreOf(v.vector, v.norm, queryVector, queryNorm)
                if (score == Float.NEGATIVE_INFINITY || score < threshold) {
                    null
                } else {
                    v to score
                }
            }
            return applyMMR(candidates, topK, mmrLambda)
        }

        Logger.d("VectorSearchService", "Búsqueda de base de datos(total=$total > $CACHE_THRESHOLD),Escaneo por lotes continuo")
        // P2-30: La ruta de transmisión fue descartada previamente scopeDocIds,daku(>CACHE_THRESHOLD)Al cruzar el límite, busque en toda la base de datos.
        return searchStreamed(queryVector, queryNorm, topK, threshold, mmrLambda, scopeDocIds, metadataFilter)
    }

    /**
     * P2-33: Escaneo por lotes para recuperación dirigida(scope / metadata Compartido por dos rutas de proveedor).
     *
     * prensa [BATCH_SIZE] Tirar página por página(Solo se carga una página por llamada al proveedor),Desacoplar las cargas máximas del tamaño de la biblioteca;
     * Los resultados son estrictamente equivalentes a la antigua "carga completa + puntuación + clasificación única":
     *  - λ<1.0(MMR):El conjunto de candidatos debe ser visible globalmente.,Por lo tanto, todos los candidatos son retenidos.(La cantidad está limitada por el alcance de la búsqueda y no por toda la base de datos.);
     *  - λ≥1.0(pura similitud,Con valor predeterminado):usar [BoundedTopK] grupo de candidatos limitado,Solo mantenga el orden topK y empate
     *    y clasificación estable `sortedByDescending{score}.take(topK)` coherente.
     *
     * @param applyMetadataInMemory Si se debe utilizar metadataFilter para determinar la memoria una por una.scope / metadata dos
     *   provider Es necesario abrir todos los caminos.:provider Sólo responsable de recortar el rango candidato.,La semántica de filtrado está unificada por [MetadataFilter.matches]
     *   Determinar en la memoria(evitar títulos/Las condiciones no cubiertas por las fuentes y otros proveedores se ignoran silenciosamente).
     */
    private suspend fun scanPagedProvider(
        queryVector: FloatArray,
        queryNorm: Float,
        topK: Int,
        threshold: Float,
        mmrLambda: Float,
        applyMetadataInMemory: Boolean,
        metadataFilter: MetadataFilter?,
        pageProvider: suspend (limit: Int, offset: Int) -> List<ChunkWithDoc>,
    ): List<SearchResult> {
        val needsFullCandidates = mmrLambda < 1.0f
        val allCandidates = if (needsFullCandidates) ArrayList<Pair<CachedVector, Float>>() else null
        val bounded = if (needsFullCandidates) null else BoundedTopK(topK)
        var offset = 0
        while (true) {
            val page = resultOf { pageProvider(BATCH_SIZE, offset) }.getOrNull() ?: emptyList()
            if (page.isEmpty()) break
            for (chunk in page) {
                if (applyMetadataInMemory && !chunkMatchesMetadata(chunk, metadataFilter)) continue
                val vector = parseEmbedding(chunk) ?: continue
                val n = norm(vector)
                if (n == 0f) continue
                val score = scoreOf(vector, n, queryVector, queryNorm)
                if (score == Float.NEGATIVE_INFINITY || score < threshold) continue
                val candidate = CachedVector(
                    chunk.chunkId, chunk.docId, chunk.docTitle, chunk.content, vector, n, chunk.chunkIndex,
                ) to score
                allCandidates?.add(candidate) ?: bounded?.offer(candidate)
            }
            if (page.size < BATCH_SIZE) break
            offset += BATCH_SIZE
        }
        val candidates = allCandidates ?: bounded?.toList() ?: emptyList()
        return applyMMR(candidates, topK, mmrLambda)
    }

    /**
     * P2-33: Grupo limitado de candidatos top-K — Con "Candidatos completos + clasificación descendente estable + take(topK)”Los resultados son estrictamente consistentes.
     *
     * Sólo reemplace el peor elemento si "la nueva puntuación es estrictamente mayor que la peor actual";Un lazo retiene al insertador anterior.,Y la posición de inserción está en
     * Después de todos los mismos artículos,Por lo tanto, el orden de salida es consistente con la clasificación estable.,El uso de memoria se fija en O(topK).
     */
    private class BoundedTopK(private val capacity: Int) {
        private val best = ArrayList<Pair<CachedVector, Float>>(capacity + 1)

        fun offer(item: Pair<CachedVector, Float>) {
            if (capacity <= 0) return
            if (best.size >= capacity) {
                val last = best.last()
                if (item.second <= last.second) return
                best.removeAt(best.size - 1)
            }
            var index = best.size
            while (index > 0 && best[index - 1].second < item.second) index--
            best.add(index, item)
        }

        fun toList(): List<Pair<CachedVector, Float>> = best
    }

    /** v1.133: Aplicación del reordenamiento de diversidad MMR.candidates Ordenado en orden descendente por puntuación. */
    private fun applyMMR(candidates: List<Pair<CachedVector, Float>>, topK: Int, mmrLambda: Float): List<SearchResult> {
        if (candidates.isEmpty()) return emptyList()
        // λ=1.0 Ordenar por similitud(Desactivar MMR)
        if (mmrLambda >= 1.0f) {
            return candidates
                .sortedByDescending { it.second }
                .take(topK)
                .map { (v, score) ->
                    SearchResult(v.docId, v.docTitle, v.content, score, v.chunkIndex, v.chunkId)
                }
        }

        // MMR: Seleccionado S,candidato C,Elige cada vez argmax_{c∈C} [λ·sim(q,c) - (1-λ)·max_{s∈S} sim(c,s)]
        val sorted = candidates.sortedByDescending { it.second }.toMutableList()
        val selected = mutableListOf<Pair<CachedVector, Float>>()
        if (sorted.isEmpty()) return emptyList()
        selected.add(sorted.removeAt(0))

        while (selected.size < topK && sorted.isNotEmpty()) {
            var bestIdx = 0
            var bestScore = Float.NEGATIVE_INFINITY
            for (i in sorted.indices) {
                val (candidate, simToQuery) = sorted[i]
                var maxSimToSelected = 0f
                for (s in selected) {
                    val sim = cosineSim(candidate.vector, candidate.norm, s.first.vector, s.first.norm)
                    if (sim > maxSimToSelected) maxSimToSelected = sim
                }
                val mmrScore = mmrLambda * simToQuery - (1f - mmrLambda) * maxSimToSelected
                if (mmrScore > bestScore) {
                    bestScore = mmrScore
                    bestIdx = i
                }
            }
            selected.add(sorted.removeAt(bestIdx))
        }

        return selected.map { (v, score) ->
            SearchResult(v.docId, v.docTitle, v.content, score, v.chunkIndex, v.chunkId)
        }
    }

    private fun cosineSim(a: FloatArray, aNorm: Float, b: FloatArray, bNorm: Float): Float {
        if (a.size != b.size || aNorm == 0f || bNorm == 0f) return 0f
        return dotProduct(a, b) / (aNorm * bNorm)
    }

    private suspend fun searchStreamed(
        queryVector: FloatArray,
        queryNorm: Float,
        topK: Int,
        threshold: Float,
        mmrLambda: Float,
        // P2-30: Una colección de documentos que limitan el alcance de la búsqueda.;null/vacío = Base de datos completa
        scopeDocIds: List<String>?,
        metadataFilter: MetadataFilter?,
    ): List<SearchResult> {
        // MMR es difícil de aplicar en escenarios de streaming(Requiere cálculo completo del conjunto de candidatos sim),rebajar a pura similitud + topK×5 Candidato nuevamente MMR
        val candidatePoolSize = if (mmrLambda < 1.0f) topK * 5 else topK
        val scopeSet = scopeDocIds?.toHashSet()?.takeIf { it.isNotEmpty() }
        val heap = PriorityQueue<Pair<CachedVector, Float>>(candidatePoolSize + 1) { a, b ->
            a.second.compareTo(b.second)
        }
        var offset = 0
        while (true) {
            val page = resultOf { chunkPageProvider(BATCH_SIZE, offset) }.getOrNull() ?: emptyList()
            if (page.isEmpty()) break
            for (chunk in page) {
                // P2-30: La paginación de streaming también se filtra por alcance.,Evite la recuperación fuera de los límites
                if (scopeSet != null && chunk.docId !in scopeSet) continue
                if (!chunkMatchesMetadata(chunk, metadataFilter)) continue
                val vector = parseEmbedding(chunk) ?: continue
                val n = norm(vector)
                if (n == 0f) continue
                val score = scoreOf(vector, n, queryVector, queryNorm)
                if (score == Float.NEGATIVE_INFINITY || score < threshold) continue
                val cv = CachedVector(chunk.chunkId, chunk.docId, chunk.docTitle, chunk.content, vector, n, chunk.chunkIndex)
                heap.offer(cv to score)
                if (heap.size > candidatePoolSize) heap.poll()
            }
            if (page.size < BATCH_SIZE) break
            offset += BATCH_SIZE
        }
        return applyMMR(heap.toList(), topK, mmrLambda)
    }

    private fun scoreOf(vector: FloatArray, vectorNorm: Float, queryVector: FloatArray, queryNorm: Float): Float =
        if (vector.size != queryVector.size) {
            Float.NEGATIVE_INFINITY
        } else {
            dotProduct(vector, queryVector) / (vectorNorm * queryNorm)
        }

    private suspend fun getOrLoadCache(total: Int, scopeDocIds: List<String>?, metadataFilter: MetadataFilter?): List<CachedVector> {
        val filterKey = metadataFilter?.let {
            "${it.docIds.sorted().joinToString(",")}#${it.tag}#${it.startTime}#${it.endTime}" +
                "#${it.titleKeyword}#${it.sourceKeyword}"
        } ?: ""
        val key = (scopeDocIds?.sorted()?.joinToString(",") ?: "ALL") + "|" + filterKey
        // Acceda a las entradas existentes (incluidas consultas repetidas con el mismo alcance/condiciones de filtro)
        synchronized(cacheEntries) { cacheEntries[key] }?.let { return it }
        return mutex.withLock {
            synchronized(cacheEntries) { cacheEntries[key] }?.let { return@withLock it }
            val chunks = chunkPageProvider(total, 0)
            // v1.133: Si es así scope,Filtrar en memoria(Evite que los proveedores tomen el monto total)
            val filtered = if (scopeDocIds != null && scopeDocIds.isNotEmpty()) {
                chunks.filter { it.docId in scopeDocIds }
            } else {
                chunks
            }
            val filteredByMetadata = filtered.filter { chunkMatchesMetadata(it, metadataFilter) }
            val parsed = filteredByMetadata.mapNotNull { chunk ->
                val vector = parseEmbedding(chunk) ?: return@mapNotNull null
                val n = norm(vector)
                if (n == 0f) return@mapNotNull null
                CachedVector(chunk.chunkId, chunk.docId, chunk.docTitle, chunk.content, vector, n, chunk.chunkIndex)
            }
            synchronized(cacheEntries) { cacheEntries[key] = parsed }
            Logger.d("VectorSearchService", "Cargar ${parsed.size} eliminar vector a caché(scope=$key)")
            parsed
        }
    }

    /**
     * v1.133: analizar embedding — BLOB Prioridad,JSON Llega al fondo de esto.
     */
    /** B4-03: metadata Filtrado de memoria(SQL provider Cuando los campos no están inyectados o faltan). */
    private fun chunkMatchesMetadata(chunk: ChunkWithDoc, filter: MetadataFilter?): Boolean {
        if (filter == null || filter.isEmpty()) return true
        return filter.matches(chunk.docId, chunk.docTitle, chunk.metadataJson, chunk.createdAt)
    }
    private fun parseEmbedding(chunk: ChunkWithDoc): FloatArray? {
        // BLOB Prioridad
        chunk.embeddingBlob?.let { blob ->
            return resultOf {
                val arr = FloatArray(blob.size / 4)
                ByteBuffer.wrap(blob).asFloatBuffer().get(arr)
                arr
            }.onError { msg, e ->
                Logger.w("VectorSearchService", "BLOB El análisis falló,intentarlo JSON: $msg", e)
            }.getOrNull()
        }
        // JSON fallback(datos antiguos)
        if (chunk.embedding.isBlank()) return null
        return resultOf {
            val arr = json.parseToJsonElement(chunk.embedding).jsonArray
            FloatArray(arr.size) { i -> arr[i].jsonPrimitive.float }
        }.getOrNull()
    }

    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) sum += a[i] * b[i]
        return sum
    }

    private fun norm(a: FloatArray): Float {
        var sum = 0f
        for (v in a) sum += v * v
        return sqrt(sum)
    }

    companion object {
        /** P2-33: búsqueda dirigida/Lotes paginados de recuperación de metadatos(El número máximo de fragmentos cargados por llamada al proveedor.). */
        internal const val BATCH_SIZE = 500
        private const val CACHE_THRESHOLD = 2000

        /** Límite de entrada de caché de vector (consulte la descripción de compensación de cacheEntries). */
        internal const val MAX_CACHE_ENTRIES = 2

        /** v1.133: FloatArray → ByteArray(BLOB almacenamiento). */
        fun floatArrayToBlob(arr: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(arr.size * 4)
            buffer.asFloatBuffer().put(arr)
            return buffer.array()
        }
    }
}
