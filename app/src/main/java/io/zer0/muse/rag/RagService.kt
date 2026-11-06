package io.zer0.muse.rag

import io.zer0.ai.core.RagCitation
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.Perf
import io.zer0.common.resultOf
import io.zer0.muse.data.knowledge.KnowledgeChunkDao
import io.zer0.muse.data.knowledge.KnowledgeChunkEntity
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsDao
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsRow
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsSelfHealer
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import io.zer0.muse.data.session.MessageFtsManager
import io.zer0.muse.util.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * v1.54: RAG Servicios de orquestación(Fragmentación + incrustación + indexación + recuperación + inyección).
 *
 * v1.133 Transformación:
 *  - embedding almacenamiento:BLOB(sustituto JSON,5× acelerar)
 *  - FTS4 sincronizar:Cree un índice FTS simultáneamente al indexar documentos,Sincronizar la limpieza al eliminar documentos
 *  - Múltiples bases de conocimiento:retrieve apoyo [scopeDocIds](@mention Orientación/encuadernación asistente KB)
 *  - Búsqueda híbrida:[hybridSearchService] Proporciona fusión RRF vectorial BM25+
 *  - Rerank:[rerankProvider] si top-K×5 reordenación de candidatos
 *  - token presupuesto:[tokenBudget] Controlar el número de tokens inyectados,Compartir grupo de presupuesto con webSearch
 *  - Devolución de referencia:[buildInjectionContextWithCitations] Regresar [RagInjection](Contiene citations)
 */
@Suppress("LongParameterList", "LargeClass")
class RagService(
    private val chunkDao: KnowledgeChunkDao,
    private val docDao: KnowledgeDocDao,
    private val ftsDao: KnowledgeChunkFtsDao,
    private val docTitleProvider: suspend () -> Map<String, String>,
    private val embeddingService: EmbeddingService,
    /** v1.133: Servicio de búsqueda híbrido(Opcional,solo [RagConfig.hybridEnabled]=true habilitado cuando). */
    private val hybridSearchService: HybridSearchService? = null,
    /** v1.133: Rerank Provider(Opcional,solo [RagConfig.rerankEnabled]=true habilitado cuando). */
    private val rerankProvider: RerankProvider? = null,
    /**
     * v1.134: locales ONNX Cross-Encoder Rerank Provider(Opcional).
     *
     * solo si [RagConfig.rerankProvider] == "onnx" habilitado cuando;Bajar a cuando el modelo no esté disponible [rerankProvider].
     */
    private val onnxRerankProvider: OnnxRerankProvider? = null,
    /**
     * P2-6: Cliente HTTP utilizado para la reclasificación de la nube (opcional, la reclasificación de la nube se degrada a la reclasificación local cuando no se inyecta).
     */
    private val httpClientProvider: (() -> okhttp3.OkHttpClient)? = null,
    /**
     * Palabras clave sin alcance cuando la búsqueda vectorial no arroja resultados. Los resultados estructurados preservan los metadatos de referencia y filtrado.
     */
    private val keywordSearchFallback: (suspend (String, Int) -> List<KeywordFallbackResult>)? = null,
    /**
     * Cobertura de palabras clave con alcance; El alcance del documento, las exclusiones internas de documentos y el filtro de metadatos deben mantenerse coherentes.
     */
    private val scopedKeywordSearchFallback: (
        suspend (String, Int, List<String>?, VectorSearchService.MetadataFilter?) -> List<KeywordFallbackResult>
    )? = null,
    /**
     * v1.55: HNSW Ruta del archivo de persistencia del índice(null = No persistente,solo memoria,App Perdido después de reiniciar).
     *
     * Inyectado por AppKoinModule `File(androidContext().filesDir, "rag/hnsw_index.bin")`.
     * App Llamado al inicio [loadVectorIndexIfNeeded] Cargar;Actualización incremental + guardado periódico cuando se agregan nuevos fragmentos.
     */
    private val indexFile: File? = null,
    /** Cache directory used to stage a full source before replacing its current index. */
    private val reindexStagingDirectory: File? = null,
) {
    /**
     * El backend de inserción actualmente efectivo(Contiene motivos para la degradación silenciosa).
     *
     * Cuando la integración en la nube configurada por el usuario no está disponible o las capacidades no son compatibles,[io.zer0.muse.rag.EmbeddingService] Recurrirá a la implementación de palabras clave locales,
     * Tráelo aquí en este momento. `degraded=true` Redacción con motivos;página de configuración/La página de diagnóstico pregunta "¿Qué backend integrado se está utilizando ahora?"
     */
    val activeEmbeddingBackend: StateFlow<EmbeddingService.BackendState?> = embeddingService.activeBackend

    private val json = Json { ignoreUnknownKeys = true }
    private val embedBatchSize = 50
    private val vectorIndexRebuildMarkerFile: File? = indexFile?.let { File(it.absolutePath + ".rebuild_required") }

    private fun persistVectorIndexRebuildMarker(reason: String) {
        val marker = vectorIndexRebuildMarkerFile ?: return
        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText(reason.take(512))
        }.onFailure { Logger.e("RagService", "Error al escribir en la etiqueta de reconstrucción HNSW: ${it.message}", it) }
    }

    /** HNSW is derived data; invalidation forces DB-vector retrieval until a complete reindex. */
    private fun markVectorIndexNeedsFullRebuild(reason: String) {
        vectorIndexNeedsFullRebuild = true
        pendingSaveCount.set(0)
        val marker = vectorIndexRebuildMarkerFile
        val markerWritten = if (marker == null) {
            false
        } else {
            persistVectorIndexRebuildMarker(reason)
            marker.exists()
        }
        if (!markerWritten) {
            // If marker persistence fails, a missing index plus remaining DB vectors forces rebuild on next boot.
            indexFile?.let { file ->
                if (file.exists() && !file.delete()) Logger.e("RagService", "No se puede invalidar el índice HNSW antiguo: ${file.absolutePath} ($reason)")
            }
        }
        Logger.w("RagService", "HNSW Marcado para reconstrucción completa: $reason")
    }

    /** R-DB-02: FTS Cuando falta la tabla, primero autorrecupere y reconstruya y luego vuelva a intentarlo.(Las inserciones masivas se realizan fuera de la transacción Room,Evite los puntos muertos de DDL). */
    private suspend fun <T> withFtsSelfHeal(block: suspend () -> T): T {
        try {
            return block()
        } catch (e: RuntimeException) {
            if (e.message?.contains("no such table: knowledge_chunks_fts") != true) throw e
            KnowledgeChunkFtsSelfHealer.repair()
            return block()
        }
    }

    // ── v1.55: HNSW Índice de vecino más cercano aproximado(Biblioteca a gran escala >=5000 chunk habilitado cuando) ──
    /**
     * HNSW Ejemplo de índice vectorial(por [VectorIndexFactory] crear,Política HNSW predeterminada).
     *
     * - Al buscar si [vectorIndex].size > 0 Y no búsqueda mixta/búsqueda no dirigida → Tome la ruta HNSW
     * - De lo contrario retroceder [vectorSearch] recorrido violento(compatible con versiones anteriores,<5000 chunk La antigua biblioteca todavía está disponible.)
     * - App pasado al inicio [loadVectorIndexIfNeeded] de [indexFile] Cargar
     * - Incremento al agregar un nuevo fragmento add;acumulativo [SAVE_INTERVAL] Automáticamente después save
     */
    private val vectorIndex: VectorIndex? = VectorIndexFactory.createIndex(
        VectorIndexFactory.IndexStrategy.HNSW,
    )

    /**
     * chunkId → Metadatos(Se utiliza para rellenar los resultados de búsqueda de HNSW docId/docTitle/content/chunkIndex).
     * B-35: Cambiar a caché limitada por LRU(access-order,Impulso de golpe,Eliminado y sin uso durante más tiempo.),límite superior [MAX_CHUNK_META],
     * Evite que todo el contenido de una gran base de conocimientos resida en la memoria. OOM;Los resultados de HNSW se degradan en general a vectorSearch en caso de errores.
     */
    private val chunkMetaCache = LruChunkMetaCache(MAX_CHUNK_META)

    /** HNSW Si el índice se ha cargado desde el disco(Evite la duplicación en cada búsqueda. load). */
    @Volatile
    private var vectorIndexLoaded = false
    private val vectorIndexMutex = kotlinx.coroutines.sync.Mutex()

    @Volatile
    private var vectorIndexFingerprint: String? = null

    @Volatile
    private var vectorIndexNeedsFullRebuild = false

    @Volatile
    private var vectorEmbeddingsNeedReindex = false

    /** El recuento acumulado que se guardará desde la primera adición.,alcanzar [SAVE_INTERVAL] desencadenado cuando [saveVectorIndex]. */
    private val pendingSaveCount = AtomicInteger(0)

    /** v2.x: HNSW Colocar placa de estrangulamiento — No es suficiente desde la última colocación [SAVE_THROTTLE_MS] tiempo libre,Evite que documentos grandes se reescriban por completo cada 50 bloques. */
    private val lastVectorIndexSaveAt = java.util.concurrent.atomic.AtomicLong(0L)

    /** v1.55: chunk Entradas de caché de metadatos(HNSW Relleno de resultados de búsqueda). */
    private data class ChunkMeta(
        val chunkId: String,
        val docId: String,
        val content: String,
        val chunkIndex: Int,
    )

    /**
     * B-35: Caché de metadatos de fragmentos LRU seguro para subprocesos,límite superior [capacity] Artículo,Evite que el contenido completo del fragmento resida en la memoria.
     * access-order LinkedHashMap:get hit promocionará la entrada a la utilizada más recientemente,Cuando la inserción esté llena, se eliminará la entrada más antigua no utilizada.
     * todo variable/Todas las operaciones de solo lectura mantienen el mismo bloqueo,evitar el recorrido(values)La simultaneidad con las escrituras da como resultado una excepción ConcurrentModificationException.
     */
    private class LruChunkMetaCache(private val capacity: Int) {
        // accessOrder=true:get Visitas promocionadas a usadas recientemente;Cuando esté lleno se eliminarán los más antiguos que lleven mucho tiempo sin utilizarse.(keys primer elemento).
        private val delegate = java.util.LinkedHashMap<String, ChunkMeta>(capacity, 0.75f, true)

        fun get(id: String): ChunkMeta? = synchronized(delegate) { delegate[id] }

        fun put(meta: ChunkMeta) {
            synchronized(delegate) {
                delegate[meta.chunkId] = meta
                while (delegate.size > capacity) {
                    val eldest = delegate.keys.first()
                    delegate.remove(eldest)
                }
            }
        }

        fun remove(id: String) {
            synchronized(delegate) { delegate.remove(id) }
        }

        fun clear() {
            synchronized(delegate) { delegate.clear() }
        }

        fun size(): Int = synchronized(delegate) { delegate.size }

        fun asList(): List<ChunkMeta> = synchronized(delegate) { delegate.values.toList() }
    }

    // v1.0.47: embedding fusible — embed La recuperación volverá directamente vacía dentro de los 5 minutos posteriores al error.,
    //   Deje que buildInjectionContextWithCitations se degrade naturalmente a keywordSearchFallback(Búsqueda de bases de datos locales),
    //   Evite presionar 404 en cada conversación, desperdiciando entre 60 y 200 ms + actualización de pantalla + activación del límite de rpm de SenseTime.
    //   5 Curación en minutos:Inténtalo de nuevo embed,Si tiene éxito, borre el fusible.,El fallo continúa durante 5 minutos.
    @Volatile
    private var embeddingFailureUntil: Long = 0L

    // v1.133: docTitle TTL caché — Evite buscar en toda la tabla cada vez(docTitleProvider original cada vez observeAll().first())
    // 5 los minutos caducan,La sincronización tarda hasta 5 minutos después de agregar o eliminar documentos;indexDocument/deleteDocIndex falla activa
    @Volatile
    private var cachedTitles: Map<String, String>? = null

    @Volatile
    private var cachedTitlesAt: Long = 0
    private val titlesMutex = kotlinx.coroutines.sync.Mutex()

    /** v1.133: Obtener docTitle con caché — 5 minutos TTL,Recargar después del fallo. */
    private suspend fun getCachedTitles(): Map<String, String> {
        val now = System.currentTimeMillis()
        cachedTitles?.let {
            if (now - cachedTitlesAt < TITLES_TTL_MS) return it
        }
        return titlesMutex.withLock {
            // doble verificación,Evite la carga repetida durante la concurrencia
            cachedTitles?.let {
                if (now - cachedTitlesAt < TITLES_TTL_MS) return@withLock it
            }
            val titles = resultOf { docTitleProvider() }
                .onError { msg, e -> Logger.w("RagService", "docTitle Error al cargar: $msg", e) }
                .getOrNull() ?: emptyMap()
            cachedTitles = titles
            cachedTitlesAt = System.currentTimeMillis()
            titles
        }
    }

    /** v1.133: Invalidar activamente la caché de docTitle(Se llama después de agregar o eliminar un documento.). */
    private fun invalidateTitlesCache() {
        cachedTitles = null
        cachedTitlesAt = 0
    }

    // ── v1.55: HNSW Gestión del ciclo de vida del índice ──

    /**
     * v1.55: App Llamado después del inicio — de [indexFile] Cargar índice HNSW,y reconstruir desde DB [chunkMetaCache].
     *
     * idempotente:Solo la primera vez se carga cuando se llama varias veces.,Regrese directamente más tarde.
     * incluso si [indexFile] es nulo o no se pudo cargar,también será reconstruido chunkMetaCache(Asegúrese de que la recuperación posterior de HNSW esté disponible).
     */
    /** Invalidate derived HNSW state after backup restore; DB embeddings remain authoritative. */
    suspend fun invalidateAfterRestore() {
        vectorIndexMutex.withLock {
            vectorIndex?.clear()
            chunkMetaCache.clear()
            pendingSaveCount.set(0)
            lastVectorIndexSaveAt.set(0L)
            vectorIndexLoaded = true
            vectorIndexFingerprint = null
            vectorIndexNeedsFullRebuild = true
            persistVectorIndexRebuildMarker("backup restore")
            vectorEmbeddingsNeedReindex = false
            indexFile?.let { file ->
                if (file.exists() && !file.delete()) {
                    Logger.w("RagService", "La eliminación del antiguo índice HNSW falla después de la restauración de la copia de seguridad: ${file.absolutePath}")
                }
            }
        }
        vectorSearch.invalidateCache()
        invalidateTitlesCache()
    }

    suspend fun loadVectorIndexIfNeeded() {
        loadVectorIndexIfNeeded(embeddingService.getProvider(RagConfig()))
    }

    suspend fun loadVectorIndexIfNeeded(ragConfig: RagConfig) {
        loadVectorIndexIfNeeded(embeddingService.getProvider(ragConfig))
    }

    private suspend fun loadVectorIndexIfNeeded(provider: EmbeddingProvider) {
        val vi = vectorIndex ?: return
        val expectedFingerprint = embeddingFingerprint(provider)
        if (vectorIndexLoaded && vectorIndexFingerprint == expectedFingerprint) return
        vectorIndexMutex.withLock {
            if (vectorIndexLoaded && vectorIndexFingerprint == expectedFingerprint) return@withLock
            val previousFingerprint = vectorIndexFingerprint
            val fingerprintChanged = previousFingerprint != null && previousFingerprint != expectedFingerprint
            if (fingerprintChanged) vectorEmbeddingsNeedReindex = true
            preservePendingIndexBeforeLoad(vi, expectedFingerprint)
            val file = indexFile
            val rebuildMarkerExists = vectorIndexRebuildMarkerFile?.exists() == true
            val loadResult = if (rebuildMarkerExists) {
                IndexLoadResult(loaded = false)
            } else {
                loadIndexFile(vi, file, expectedFingerprint)
            }
            if (!loadResult.loaded) {
                vi.clear()
                pendingSaveCount.set(0)
                val indexedCount = resultOf { chunkDao.countIndexed() }.getOrNull()
                if (rebuildMarkerExists || file?.exists() == true || fingerprintChanged || indexedCount == null || indexedCount > 0) {
                    vectorIndexNeedsFullRebuild = true
                }
                if (loadResult.fingerprintMismatch) vectorEmbeddingsNeedReindex = true
            } else {
                vectorIndexNeedsFullRebuild = false
            }
            vectorIndexFingerprint = expectedFingerprint
            // Independientemente de si la carga es exitosa o no,son reconstruidos chunkMetaCache(HNSW Es necesario rellenar los resultados de la búsqueda)
            rebuildChunkMetaCache()
            vectorIndexLoaded = true
        }
    }

    private suspend fun preservePendingIndexBeforeLoad(vi: VectorIndex, expectedFingerprint: String) {
        if (pendingSaveCount.get() <= 0) return
        if (vectorIndexFingerprint == null || vectorIndexFingerprint == expectedFingerprint) {
            saveVectorIndex(expectedFingerprint)
        } else {
            vi.clear()
            pendingSaveCount.set(0)
            vectorIndexNeedsFullRebuild = true
        }
    }

    private data class IndexLoadResult(val loaded: Boolean, val fingerprintMismatch: Boolean = false)

    private fun loadIndexFile(vi: VectorIndex, file: File?, expectedFingerprint: String): IndexLoadResult {
        if (file == null || !file.exists()) return IndexLoadResult(loaded = false)
        var fingerprintMismatch = false
        val loaded = resultOf {
            vi.load(file, expectedFingerprint)
            true
        }
            .onSuccess { Logger.i("RagService", "HNSW Carga del índice completada:size=${vi.size}") }
            .onError { msg, error ->
                fingerprintMismatch = error is HnswEmbeddingFingerprintMismatchException
                Logger.w("RagService", "HNSW Error al cargar el índice(Caminará a través de la violencia): $msg", error)
            }
            .getOrNull() == true
        return IndexLoadResult(loaded, fingerprintMismatch)
    }

    /**
     * v1.55: Llamado al recuperar — Activar la carga si el índice no está cargado,De lo contrario, regrese directamente.
     *
     * con [loadVectorIndexIfNeeded] la diferencia:Este método es sólo para [retrieve] Uso interno,
     * Evite la sobrecarga de lectura volátil + verificación Mutex para cada recuperación.
     */
    private suspend fun ensureVectorIndexLoaded() {
        if (vectorIndexLoaded) return
        loadVectorIndexIfNeeded()
    }

    /**
     * v1.55: Persistir en el índice HNSW para [indexFile].
     *
     * tiempo de activación:
     *  - [addChunksToVectorIndex] acumulativo [SAVE_INTERVAL] Se activa automáticamente después
     *  - App Llamado activamente por la persona que llama al salir/en segundo plano
     *  - [deleteDocIndex] Entonces la persona que llama toma la iniciativa de llamar.(Opcional)
     */
    suspend fun saveVectorIndex(fingerprint: String? = vectorIndexFingerprint) {
        val vi = vectorIndex
        val file = indexFile
        if (vi == null || file == null) return
        if (vectorIndexNeedsFullRebuild || vectorEmbeddingsNeedReindex) {
            Logger.i("RagService", "Embedding Algunos índices HNSW no persistirán hasta que se vuelva a indexar toda la base de datos.")
            return
        }
        resultOf {
            // Ejecutar dentro del bloque sincronizado IO(HNSW save Bloqueo de lectura interno,No bloquear search)
            vi.save(file, fingerprint)
        }.onSuccess {
            pendingSaveCount.set(0)
            lastVectorIndexSaveAt.set(System.currentTimeMillis())
            vectorIndexRebuildMarkerFile?.let { marker ->
                if (marker.exists() && !marker.delete()) Logger.w("RagService", "HNSW Error al limpiar el archivo de marca: ${marker.absolutePath}")
            }
            Logger.d("RagService", "HNSW Índice guardado:size=${vi.size}, file=${file.absolutePath}")
        }.onError { msg, e ->
            Logger.w("RagService", "HNSW Error al guardar el índice: $msg", e)
        }
    }

    /**
     * v1.55: Cargue completamente los metadatos de fragmentos de la base de datos a [chunkMetaCache].
     *
     * usado para [loadVectorIndexIfNeeded] Construir caché al inicio;Borrar caché en caso de error(Las búsquedas posteriores de HNSW serán
     * chunkMetaCache Perdido y degradado a vectorSearch,No afecta la funcionalidad).
     */
    private suspend fun rebuildChunkMetaCache() {
        // P3-3(informe de auditoría): Lea en lotes para evitar cargar todo el fragmento de la biblioteca + incrustarlo en la memoria a la vez.
        // Para implementación anterior getAllWithEmbedding(), memoria máxima = El tamaño de toda la biblioteca;
        // Ahora, simplemente tomando la columna de metadatos y leyéndola por página, el pico se reduce a una sola página.
        chunkMetaCache.clear()
        var offset = 0
        var page = resultOf { chunkDao.getPageWithoutEmbedding(CACHE_REBUILD_PAGE_SIZE, offset) }
            .onError { msg, e -> Logger.w("RagService", "chunkMetaCache La reconstrucción falló: $msg", e) }
            .getOrNull()
        while (!page.isNullOrEmpty()) {
            for (chunk in page) {
                chunkMetaCache.put(
                    ChunkMeta(
                        chunkId = chunk.id,
                        docId = chunk.docId,
                        content = chunk.content,
                        chunkIndex = chunk.chunkIndex,
                    ),
                )
            }
            if (page.size < CACHE_REBUILD_PAGE_SIZE) break
            offset += CACHE_REBUILD_PAGE_SIZE
            page = resultOf { chunkDao.getPageWithoutEmbedding(CACHE_REBUILD_PAGE_SIZE, offset) }
                .onError { msg, e -> Logger.w("RagService", "chunkMetaCache Error de lectura de página: $msg", e) }
                .getOrNull()
        }
        Logger.d("RagService", "chunkMetaCache Reconstrucción completada:${chunkMetaCache.size()} Artículo")
    }

    /**
     * v1.55: Agregue el nuevo fragmento al índice HNSW + fragmentoMetaCache.
     *
     * No se lanza ninguna excepción en caso de error.(El error de actualización del índice no afecta la base de datos que se ha escrito. chunk,La próxima vez que se inicie la aplicación, se reconstruirá desde la base de datos.).
     * acumulativo [SAVE_INTERVAL] Activado después [saveVectorIndex] Persistencia.
     */
    private suspend fun addChunksToVectorIndex(
        entities: List<KnowledgeChunkEntity>,
        embeddings: List<FloatArray>,
        provider: EmbeddingProvider,
    ) {
        val vi = vectorIndex ?: return
        loadVectorIndexIfNeeded(provider)
        var addFailed = false
        for ((idx, entity) in entities.withIndex()) {
            val vec = embeddings.getOrNull(idx) ?: continue
            var added = true
            resultOf { vi.add(entity.id, vec) }
                .onError { msg, e ->
                    added = false
                    Logger.w("RagService", "HNSW add falló(chunkId=${entity.id}): $msg", e)
                }
            if (added) {
                chunkMetaCache.put(
                    ChunkMeta(
                        chunkId = entity.id,
                        docId = entity.docId,
                        content = entity.content,
                        chunkIndex = entity.chunkIndex,
                    ),
                )
            } else {
                addFailed = true
                chunkMetaCache.remove(entity.id)
            }
        }
        if (addFailed) {
            markVectorIndexNeedsFullRebuild("incremental add failed")
            vectorSearch.invalidateCache()
            return
        }
        val pending = pendingSaveCount.addAndGet(entities.size)
        if (pending >= SAVE_INTERVAL && System.currentTimeMillis() - lastVectorIndexSaveAt.get() >= SAVE_THROTTLE_MS) {
            saveVectorIndex()
        }
    }

    /**
     * v1.55: Elimine todos los fragmentos del documento especificado del índice HNSW + fragmentoMetaCache.
     *
     * P2-35: Originalmente solo hice clic [chunkMetaCache](LRU,límite superior [MAX_CHUNK_META]) Contrainvestigación,El de la gran biblioteca. doc
     * Se omiten y eliminan fragmentos después de ser eliminados por LRU,HNSW Datos sucios restantes(La búsqueda aún devuelve contenido eliminado). Cambiar a base de datos
     * chunkId Toda la colección se elimina de la fuente.(El almacenamiento en caché es sólo para limpiar la tabla de memoria.),Y superponga la identificación residual eliminada de la base de datos en el caché.
     * La persona que llama debe ejecutar este método antes de eliminar la base de datos.(DB es una fuente autorizada).
     */
    private suspend fun removeDocChunksFromVectorIndex(docId: String) {
        val vi = vectorIndex ?: return
        var dbReadFailed = false
        val dbIds = resultOf { chunkDao.getByDoc(docId).map { it.id } }
            .onError { msg, e ->
                dbReadFailed = true
                Logger.e("RagService", "No se pudo leer el ID del fragmento del documento que se va a eliminar(docId=$docId): $msg", e)
            }
            .getOrNull()
        val cacheIds = chunkMetaCache.asList().filter { it.docId == docId }.map { it.chunkId }
        if (dbReadFailed || dbIds == null) {
            markVectorIndexNeedsFullRebuild("chunkId lookup failed for doc $docId")
            cacheIds.forEach(chunkMetaCache::remove)
            return
        }
        val idsToRemove = (dbIds + cacheIds).distinct()
        if (idsToRemove.isNotEmpty()) {
            var removeFailed = false
            for (id in idsToRemove) {
                resultOf { vi.remove(id) }
                    .onError { msg, e ->
                        removeFailed = true
                        Logger.e("RagService", "HNSW remove falló(chunkId=$id): $msg", e)
                    }
                chunkMetaCache.remove(id)
            }
            if (removeFailed) markVectorIndexNeedsFullRebuild("incremental remove failed for doc $docId")
        }
    }

    private val vectorSearch = VectorSearchService(
        chunkPageProvider = { limit, offset ->
            val titles = getCachedTitles()
            chunkDao.getPageWithEmbedding(limit, offset).map { chunk ->
                VectorSearchService.ChunkWithDoc(
                    chunkId = chunk.id,
                    docId = chunk.docId,
                    docTitle = titles[chunk.docId] ?: "Unknown Document",
                    content = chunk.content,
                    embedding = chunk.embedding,
                    embeddingBlob = chunk.embeddingBlob,
                    chunkIndex = chunk.chunkIndex,
                    metadataJson = chunk.metadataJson,
                    createdAt = chunk.createdAt,
                )
            }
        },
        chunkCountProvider = { chunkDao.countIndexed() },
        chunkPageByDocIdsProvider = { docIds, limit, offset ->
            val titles = getCachedTitles()
            // v1.133: Conjunto de candidatos filtrado por docIds(@mention Para búsqueda dirigida)
            // P2-33: respeto limit/offset(Anteriormente, los parámetros de paginación se ignoraban y todos los bloques del alcance se cargaban completamente en cada llamada.)
            val chunks = if (docIds.isNotEmpty()) chunkDao.getPageByDocIds(docIds, limit, offset) else emptyList()
            chunks.map { chunk ->
                VectorSearchService.ChunkWithDoc(
                    chunkId = chunk.id,
                    docId = chunk.docId,
                    docTitle = titles[chunk.docId] ?: "Unknown Document",
                    content = chunk.content,
                    embedding = chunk.embedding,
                    embeddingBlob = chunk.embeddingBlob,
                    chunkIndex = chunk.chunkIndex,
                    metadataJson = chunk.metadataJson,
                    createdAt = chunk.createdAt,
                )
            }
        },
        chunkPageByMetadataProvider = { filter, limit, offset ->
            val titles = getCachedTitles()
            chunkDao.getFilteredPageWithEmbedding(
                limit = limit,
                offset = offset,
                docIdsJson = AppJson.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(String.serializer()),
                    filter.docIds,
                ),
                tag = filter.tag,
                startTime = filter.startTime,
                endTime = filter.endTime,
            ).map { chunk ->
                VectorSearchService.ChunkWithDoc(
                    chunkId = chunk.id,
                    docId = chunk.docId,
                    docTitle = titles[chunk.docId] ?: "Unknown Document",
                    content = chunk.content,
                    embedding = chunk.embedding,
                    embeddingBlob = chunk.embeddingBlob,
                    chunkIndex = chunk.chunkIndex,
                    metadataJson = chunk.metadataJson,
                    createdAt = chunk.createdAt,
                )
            }
        },
    )

    /**
     * Documentos indexados:Chunking + incrustación + almacenamiento + sincronización FTS.
     *
     * v2.x: fragmentación/HNSW Las operaciones de CPU y disco, como la escritura, son [Dispatchers.Default] ejecutar,
     * Evite importaciones de archivos grandes en el hilo de la persona que llama(hilo principal)en la interfaz de bloqueo.
     *
     * @return Número de bloques
     */
    suspend fun indexDocument(
        docId: String,
        content: String,
        ragConfig: RagConfig,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = indexDocumentInternal(
        docId,
        content,
        ragConfig,
        allowDimensionChange = false,
        onProgress = onProgress,
    )

    private suspend fun indexDocumentInternal(
        docId: String,
        content: String,
        ragConfig: RagConfig,
        allowDimensionChange: Boolean,
        onProgress: (current: Int, total: Int) -> Unit,
    ): Int = withContext(Dispatchers.Default) {
        if (content.isBlank()) return@withContext 0
        val perfTimer = Perf.start("rag-index-$docId")

        // 1. fragmentación(v1.133:apoyo markdownAware / chunkByToken)
        val chunker = TextChunker(
            targetSize = ragConfig.chunkSize,
            overlap = ragConfig.chunkOverlap,
            markdownAware = ragConfig.markdownAware,
            chunkByToken = ragConfig.chunkByToken,
        )
        val chunks = chunker.split(content)
        if (chunks.isEmpty()) {
            if (allowDimensionChange && vectorIndexNeedsFullRebuild) clearDocumentChunks(docId)
            return@withContext 0
        }
        Logger.d(
            "RagService",
            "Documentación $docId fragmentación ${chunks.size} bloquear(markdownAware=${ragConfig.markdownAware}, chunkByToken=${ragConfig.chunkByToken})",
        )
        perfTimer.split("chunk")

        // 2. lote embedding
        val provider = embeddingService.getProvider(ragConfig)
        loadVectorIndexIfNeeded(provider)
        val embeddings = mutableListOf<FloatArray>()
        for (i in chunks.indices step embedBatchSize) {
            val batch = chunks.subList(i, minOf(i + embedBatchSize, chunks.size))
            val vectors = resultOf { provider.embed(batch.map { it.content }) }
                .onError { msg, e -> Logger.e("RagService", "Embedding lote $i falló: $msg", e) }
                .getOrThrow()
            check(vectors.size == batch.size) {
                "Provider returned ${vectors.size} vectors for ${batch.size} chunks (batch $i)"
            }
            embeddings.addAll(vectors)
            onProgress(minOf(i + embedBatchSize, chunks.size), chunks.size)
        }
        perfTimer.split("embedding")

        // v1.133: embedding Comprobación de coherencia de dimensiones — Las nuevas dimensiones de incrustación deben ser coherentes con las dimensiones de fragmentos existentes en la biblioteca.
        // Las dimensiones inconsistentes harán que la recuperación de vectores cosSim siempre sea 0(Interceptado por falta de coincidencia de tamaño),base de conocimientos"Parece que está indexado pero no se puede encontrar.".
        // Lanza una excepción cuando falla la verificación,Registrado como error por reindexAllInKbs/caller de la capa superior,No escrito en la base de datos.
        val newDim = embeddings.firstOrNull()?.size ?: 0
        if (newDim > 0) {
            val existingDim = resultOf { chunkDao.getFirstIndexedEmbeddingDim() }.getOrNull()
            if (shouldRejectDimensionChange(existingDim, newDim, allowDimensionChange)) {
                throwDimensionMismatch(docId, checkNotNull(existingDim), newDim)
            }
        }

        // 3. El primer lote de comprobaciones de incrustación y dimensiones ha sido exitoso;Cualquier falla a partir de este momento debe borrar el estado semiindexado.
        var destructiveStarted = false
        try {
            destructiveStarted = true
            removeDocChunksFromVectorIndex(docId)
            chunkDao.deleteByDoc(docId)
            withFtsSelfHeal { ftsDao.deleteByDoc(docId) }

            // 4. almacenamiento chunk + embedding(BLOB)
            val now = System.currentTimeMillis()
            val entities = createChunkEntities(docId, chunks, embeddings, chunker, now)
            chunkDao.insertAll(entities)

            // v1.55: Únase sincrónicamente al índice HNSW(actualización incremental)
            addChunksToVectorIndex(entities, embeddings, provider)

            // 5. FTS Sincronizar índice(Para vías de recuperación híbridas BM25)
            val ftsRows = entities.map {
                KnowledgeChunkFtsRow(
                    chunkId = it.id,
                    docId = it.docId,
                    content = MessageFtsManager.toNgram(it.content),
                )
            }
            resultOf { withFtsSelfHeal { ftsDao.insertAll(ftsRows) } }
                .onError { msg, e -> Logger.w("RagService", "FTS Error de sincronización(No afecta la recuperación de vectores): $msg", e) }

            perfTimer.split("store")
            vectorSearch.invalidateCache()
            invalidateTitlesCache()

            Logger.d("RagService", "Documentación $docId Indexación completada:${entities.size} bloquear,Dimensiones ${embeddings.firstOrNull()?.size ?: 0}")
            perfTimer.end()
            return@withContext entities.size
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            if (destructiveStarted) cleanupFailedIndexReplacement(docId)
            throw cancelled
        } catch (error: Exception) {
            if (destructiveStarted) cleanupFailedIndexReplacement(docId)
            throw error
        }
    }

    private suspend fun cleanupFailedIndexReplacement(docId: String) = withContext(NonCancellable) {
        var cleanupFailed = false
        runCatching { removeDocChunksFromVectorIndex(docId) }
            .onFailure {
                cleanupFailed = true
                Logger.e("RagService", "Limpiar el estado de HNSW después de que falla el reemplazo del índice(docId=$docId)", it)
            }
        runCatching { withFtsSelfHeal { ftsDao.deleteByDoc(docId) } }
            .onFailure {
                cleanupFailed = true
                Logger.e("RagService", "La limpieza de FTS falla después de que falla el reemplazo del índice(docId=$docId)", it)
            }
        runCatching { chunkDao.deleteByDoc(docId) }
            .onFailure {
                cleanupFailed = true
                Logger.e("RagService", "La limpieza de filas de fragmentos falla después de que falla el reemplazo del índice(docId=$docId)", it)
            }
        // P0-3(informe de auditoría): Márquelo como "El índice se ha perdido y debe reconstruirse" en lugar de borrarlo silenciosamente.
        // Cuando falla a mitad de camino, el bloque antiguo se ha eliminado, el nuevo bloque está incompleto y el documento sigue ahí pero no se puede recuperar;
        // Los usuarios no ven ningún mensaje, simplemente sienten“Esta biblioteca no funciona”.
        runCatching {
            docDao.markIndexBroken(docId)
        }.onFailure {
            cleanupFailed = true
            Logger.e("RagService", "Error al marcar el estado del documento después de que falló el reemplazo del índice(docId=$docId)", it)
        }
        Logger.w(
            "RagService",
            "Documentación $docId El reemplazo del índice falló a mitad de camino, los bloques antiguos se borraron y los bloques nuevos están incompletos; ha sido marcado para su reconstrucción." +
                "El documento no se está recuperando actualmente.",
        )
        if (cleanupFailed) markVectorIndexNeedsFullRebuild("cleanup failed for doc $docId")
        vectorSearch.invalidateCache()
        invalidateTitlesCache()
    }

    /**
     * v2.x: Transmisión de documentos indexados — fragmentación/embedding/Almacenar flujo completo,Uso de memoria O(ventana) en vez de O(Texto completo).
     *
     * v2.x: Segmentación de ventana deslizante y escritura por lotes HNSW en [Dispatchers.Default] ejecutar,Evite que las importaciones de archivos grandes bloqueen el hilo principal.
     *
     * con [indexDocument] alineación semántica(Primero borre los bloques antiguos y luego escriba nuevos bloques/verificación de dimensiones/sincronización FTS),Diferencia en la entrada de texto:
     * El texto comienza con [textPieces] Transmisión de transmisión fragmentada,No es necesario cargar el texto completo en la memoria a la vez.,Admite la importación de archivos de cualquier tamaño.
     *
     * darse cuenta:Toma de ventana corredera — El buffer se acumula hasta [windowChars] detrás del límite seguro(párrafo/nueva línea)cortar ventana,
     * Reutilizar [TextChunker] Bloquear por ventana;bloquear lote([embedBatchSize])Escriba la tabla de fragmentos/HNSW/FTS en lotes después de la incrustación.
     *
     * semántica del fracaso:
     *  - El primer lote de incrustación falló o las dimensiones no coincidieron:Sin limpieza de bloques viejos, cero escrituras.(con [indexDocument] consistente);
     *  - Falló a mitad de camino:Bloques escritos reservados(índice parcial),Los datos antiguos se limpiarán antes del primer lote de escrituras al volver a importarlos.
     *
     * @param textPieces secuencia de fragmentos de texto(Bucle de lectura de archivos bloque a bloque emit)
     * @param windowChars Tamaño de ventana corredera(personaje,Predeterminado [STREAM_WINDOW_CHARS];La prueba se puede ajustar en tamaño pequeño.)
     * @param onProgress Devolución de llamada de recuento de bloques indexados(Una vez por lote)
     * @return Número total de bloques de índice
     */
    suspend fun indexDocumentStreamed(
        docId: String,
        textPieces: Flow<String>,
        ragConfig: RagConfig,
        windowChars: Int = STREAM_WINDOW_CHARS,
        onProgress: (indexedChunks: Int) -> Unit = {},
    ): Int = withContext(Dispatchers.Default) {
        val perfTimer = Perf.start("rag-index-stream-$docId")
        val effectiveWindow = windowChars.coerceAtLeast(1024)
        val chunker = TextChunker(
            targetSize = ragConfig.chunkSize,
            overlap = ragConfig.chunkOverlap,
            markdownAware = ragConfig.markdownAware,
            chunkByToken = ragConfig.chunkByToken,
        )
        val provider = embeddingService.getProvider(ragConfig)
        loadVectorIndexIfNeeded(provider)

        val buffer = StringBuilder()
        val pending = mutableListOf<TextChunker.Chunk>()
        var nextIndex = 0
        var totalIndexed = 0
        var initialized = false
        var destructiveStarted = false

        suspend fun cleanupPartialIndex() {
            if (!destructiveStarted) return
            runCatching { deleteDocIndex(docId) }
                .onFailure { Logger.e("RagService", "La limpieza parcial del índice falla después de una falla del índice de transmisión(docId=$docId)", it) }
            runCatching { docDao.clearIndexStatus(docId) }
                .onFailure { Logger.e("RagService", "Error en la limpieza del estado del índice del documento después de que falló la indexación en streaming(docId=$docId)", it) }
        }

        suspend fun storeBatch(batch: List<TextChunker.Chunk>) {
            if (batch.isEmpty()) return
            val vectors = resultOf { provider.embed(batch.map { it.content }) }
                .onError { msg, e -> Logger.e("RagService", "Falló el lote de incrustación de índice de transmisión(indexado $totalIndexed bloquear): $msg", e) }
                .getOrThrow()
            check(vectors.size == batch.size) {
                "Provider returned ${vectors.size} vectors for ${batch.size} chunks"
            }
            if (!initialized) {
                // primer lote:Comprobación de coherencia de dimensiones(Mismo calibre que indexDocument;No limpie bloques antiguos en caso de falla, cero escrituras)
                val newDim = vectors.firstOrNull()?.size ?: 0
                if (newDim > 0) {
                    val existingDim = resultOf { chunkDao.getFirstIndexedEmbeddingDim() }.getOrNull()
                    if (existingDim != null && existingDim > 0 && existingDim != newDim) {
                        Logger.e(
                            "RagService",
                            "Embedding Las dimensiones no coinciden:La dimensión del fragmento ya existe en la biblioteca.=$existingDim,nuevo documento $docId Dimensiones=$newDim.",
                        )
                        throw IllegalStateException(
                            "Embedding dimension mismatch: existing=$existingDim, new=$newDim (doc=$docId). " +
                                "Run 'reindex all' with the same embedding model.",
                        )
                    }
                }
                // Limpiar bloques antiguos después de que el primer lote de incrustaciones sea exitoso(Con indexDocumento "embedding Después del éxito, elimine los fragmentos antiguos y alinéelos.).
                // A partir de aquí, cualquier fallo en el proceso deberá limpiar este índice parcial para evitar que el documento quede en“semi-buscable”estado.
                destructiveStarted = true
                removeDocChunksFromVectorIndex(docId)
                chunkDao.deleteByDoc(docId)
                withFtsSelfHeal { ftsDao.deleteByDoc(docId) }
                initialized = true
            }
            val now = System.currentTimeMillis()
            val entities = batch.mapIndexed { i, chunk ->
                KnowledgeChunkEntity(
                    id = "chunk-$docId-${chunk.index}",
                    docId = docId,
                    content = chunk.content,
                    embedding = "",
                    embeddingBlob = VectorSearchService.floatArrayToBlob(vectors[i]),
                    chunkIndex = chunk.index,
                    tokenCount = chunker.estimateTokens(chunk.content),
                    metadataJson = encodeMetadata(chunk.metadata),
                    createdAt = now,
                )
            }
            chunkDao.insertAll(entities)
            addChunksToVectorIndex(entities, vectors, provider)
            val ftsRows = entities.map {
                KnowledgeChunkFtsRow(
                    chunkId = it.id,
                    docId = it.docId,
                    content = MessageFtsManager.toNgram(it.content),
                )
            }
            resultOf { withFtsSelfHeal { ftsDao.insertAll(ftsRows) } }
                .onError { msg, e -> Logger.w("RagService", "FTS Error de sincronización(No afecta la recuperación de vectores): $msg", e) }
            totalIndexed += entities.size
            onProgress(totalIndexed)
        }

        /** Corta una ventana y únete a la cola en trozos;Devuelve si se ha consumido alguna ventana. */
        fun drainWindow(hard: Boolean): Boolean {
            if (buffer.isEmpty()) return false
            if (!hard && buffer.length < effectiveWindow) return false
            val limit = minOf(buffer.length, effectiveWindow)
            val cut = run {
                val para = buffer.lastIndexOf("\n\n", limit - 1)
                if (para >= limit / 2) {
                    para + 2
                } else {
                    val line = buffer.lastIndexOf("\n", limit - 1)
                    if (line >= limit / 2) line + 1 else limit
                }
            }
            val part = buffer.substring(0, cut)
            buffer.delete(0, cut)
            if (part.isNotBlank()) {
                for (chunk in chunker.split(part)) {
                    for (piece in splitOversizedPlainChunk(chunk, ragConfig.chunkSize, ragConfig.chunkOverlap)) {
                        pending.add(piece.copy(index = nextIndex++))
                    }
                }
            }
            return true
        }

        try {
            textPieces.collect { piece ->
                if (piece.isEmpty()) return@collect
                buffer.append(piece)
                while (drainWindow(hard = false)) {
                    while (pending.size >= embedBatchSize) {
                        val batch = pending.subList(0, embedBatchSize).toList()
                        pending.subList(0, embedBatchSize).clear()
                        storeBatch(batch)
                    }
                }
            }
            // Refinamiento:Las ventanas restantes se ciclan y cortan.(Cada desagüe sólo consume una ventana),Los bloques están escritos en lotes a continuación.
            while (drainWindow(hard = true)) {
                // Una ventana para cada ronda de consumo.,hasta que el buffer esté vacío
            }
            while (pending.size >= embedBatchSize) {
                val batch = pending.subList(0, embedBatchSize).toList()
                pending.subList(0, embedBatchSize).clear()
                storeBatch(batch)
            }
            if (pending.isNotEmpty()) {
                storeBatch(pending.toList())
                pending.clear()
            }

            if (totalIndexed > 0) {
                vectorSearch.invalidateCache()
                invalidateTitlesCache()
            }
            perfTimer.end()
            Logger.d("RagService", "Documentación $docId Indexación de streaming completada:$totalIndexed bloquear")
            return@withContext totalIndexed
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            cleanupPartialIndex()
            throw cancelled
        } catch (error: Exception) {
            cleanupPartialIndex()
            throw error
        }
    }

    /**
     * v2.x: Segmentación garantizada por ingesta de streaming — El bloque de texto sin formato superlargo de la ventana se divide en dos partes.
     *
     * TextChunker El modo consciente de las rebajas presiona los límites de la estructura(Título/bloque de código/mesa)cortar,
     * Los párrafos largos sin estructura Markdown se fusionarán en un solo fragmento.(ver TextChunkerTest
     * "Los párrafos puros y sin bordes se fusionarán en un solo bloque"). Si se ingresa directamente un bloque enorme en la incrustación, excederá el límite superior del modelo.
     * Entrar en la búsqueda arrastrará hacia abajo la calidad de la inyección.,Entonces, en el lado de la ingesta de streaming durante más de 2×targetSize bloque de texto plano
     * Haz un corte duro con un fondo garantizado.(Cortar en la magnitud del tamaño objetivo,Reservar overlap).
     *
     * bloque de código/mesa(metadata type=code/table)Respetar la semántica estructural,Mantén todo sin cortes.
     */
    private fun splitOversizedPlainChunk(chunk: TextChunker.Chunk, targetSize: Int, overlap: Int): List<TextChunker.Chunk> {
        val type = chunk.metadata["type"]
        if (type == "code" || type == "table") return listOf(chunk)
        val limit = targetSize * 2
        val text = chunk.content
        if (text.length <= limit) return listOf(chunk)
        val pieces = mutableListOf<TextChunker.Chunk>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + targetSize, text.length)
            pieces.add(chunk.copy(content = text.substring(start, end)))
            if (end >= text.length) break
            start = (end - overlap).coerceAtLeast(start + 1)
        }
        return pieces
    }

    /**
     * v1.133: Buscar — Admite recuperación mixta/MMR/scopeDocIds.
     *
     * v1.55: Biblioteca a gran escala(>=5000 chunk)Dar prioridad al vecino más cercano aproximado de HNSW;
     *       En un escenario de recuperación no híbrida/no dirigida, si [vectorIndex] Si tienes datos, úsalos. HNSW,De lo contrario, volverá a caer en un cruce violento.
     *
     * @param scopeDocIds Limitar el alcance de la búsqueda(@mention Búsqueda dirigida/KB de enlace del Asistente)
     * @param metadataFilter Filtros de metadatos(docIds/Título/Fuente/tag/rango de tiempo,Opcional,null = Sin filtrado);
     *   También funciona en búsquedas híbridas.(Vector+BM25)Dos enlaces con recuperación vectorial pura.
     */
    suspend fun retrieve(
        query: String,
        topK: Int,
        threshold: Float,
        ragConfig: RagConfig,
        scopeDocIds: List<String>? = null,
        metadataFilter: VectorSearchService.MetadataFilter? = null,
    ): List<VectorSearchService.SearchResult> {
        if (query.isBlank()) return emptyList()
        val start = System.currentTimeMillis()
        // v1.0.47: Regresa directamente vacío durante el período del disyuntor,Deja ir la cima keywordSearchFallback(Búsqueda de bases de datos locales),
        //   Ya no ajuste la inserción para activar 404.5 Se curará solo en minutos.
        if (System.currentTimeMillis() < embeddingFailureUntil) {
            Perf.log("rag-retrieve", System.currentTimeMillis() - start)
            return emptyList()
        }
        val provider = embeddingService.getProvider(ragConfig)
        // P0-1 Reparación (informe de auditoría): Agregue un tiempo de espera estricto para consultar la incrustación.
        //
        // La implementación anterior solo tenía cortes de circuito (saltos dentro de los 5 minutos posteriores al fallo) sin tiempos de espera. si embedding
        // punto final“Acepta conexión pero nunca responde”, la llamada se colgará hasta que charle OkHttpClient
        // readTimeout=300s —— Los usuarios esperan hasta 5 minutos en la ruta crítica para enviar un mensaje antes de ver la primera palabra.
        // Agregue aquí un tiempo de espera estricto de 10 segundos: el tiempo de espera también activa un disyuntor y la capa superior se degrada a la recuperación de palabras clave locales.
        // Primero verifique el caché de vectores: si el acceso tiene éxito, no ingresará a la ventana de tiempo de espera de la red (repetir preguntas/volver a intentar la misma oración es lo más común).
        // Deliberadamente no se usa aquí embedWithCache —— Eso convierte la excepción del Proveedor en nula, mientras que lo siguiente
        // Excepción de dependencia lógica del disyuntor; si no se puede encontrar, se seguirá utilizando la ruta original withTimeout + proveedor.embed.
        val cachedQueryVector: FloatArray? = resultOf {
            embeddingService.cachedVector(ragConfig, query)
        }.getOrNull()
        val queryVector: FloatArray = cachedQueryVector ?: resultOf {
            withTimeout(EMBED_QUERY_TIMEOUT_MS) { provider.embed(query) }
        }
            .onError { msg, e ->
                Logger.w("RagService", "Query embedding falló/tiempo de espera: $msg", e)
                // v1.0.47: Configure el fusible para que se rompa durante 5 minutos.,Durante la recuperación, regresa vacío directamente. → Degradar la búsqueda local desde el nivel superior
                embeddingFailureUntil = System.currentTimeMillis() + EMBEDDING_CIRCUIT_BREAKER_MS
                Logger.i("RagService", "embedding fusible ${EMBEDDING_CIRCUIT_BREAKER_MS / 1000}s,La búsqueda de período se degrada a palabras clave locales")
            }
            .getOrNull()
            ?: return emptyList<VectorSearchService.SearchResult>().also { Perf.log("rag-retrieve", System.currentTimeMillis() - start) }
        // v1.0.47: embed éxito → fusible claro(autocuración),La próxima vez, reanude la recuperación normal de vectores.
        embeddingFailureUntil = 0L
        if (cachedQueryVector == null) {
            // El almacenamiento en caché de escritura es una optimización pura: el error no afecta los resultados de esta recuperación.
            resultOf { embeddingService.rememberVector(ragConfig, query, queryVector) }
        }

        if (vectorEmbeddingsNeedReindex) {
            Logger.w("RagService", "Embedding El modelo es inconsistente con el vector almacenado. Espere a que se vuelva a indexar toda la base de datos antes de reanudar la recuperación de vectores.")
            return emptyList()
        }

        // v1.133: Rutas de búsqueda mixtas
        if (ragConfig.hybridEnabled && hybridSearchService != null) {
            val hybrid = resultOf {
                hybridSearchService.hybridSearch(
                    query = query,
                    queryVector = queryVector,
                    topK = topK,
                    threshold = threshold,
                    mmrLambda = ragConfig.mmrLambda,
                    bm25Weight = ragConfig.hybridBm25Weight,
                    vectorWeight = ragConfig.hybridVectorWeight,
                    scopeDocIds = scopeDocIds,
                    metadataFilter = metadataFilter,
                )
            }.onError { msg, e -> Logger.w("RagService", "La búsqueda mixta falló,vector de degradación: $msg", e) }
                .getOrNull()
            if (hybrid != null) {
                Perf.log("rag-retrieve-hybrid", System.currentTimeMillis() - start)
                val mapped = hybrid.map {
                    VectorSearchService.SearchResult(
                        docId = it.docId,
                        docTitle = it.docTitle,
                        chunkContent = it.chunkContent,
                        score = it.rrfScore,
                        chunkIndex = it.chunkIndex,
                        chunkId = it.chunkId,
                    )
                }
                // v1.133: Rellene el campo isInternal(Consulta por lotes desde KnowledgeDocEntity,Reemplazo unificado de la codificación del prefijo docId)
                return applyIsInternal(mapped)
            }
        }

        // v1.55: HNSW camino(Biblioteca a gran escala + búsqueda no direccional)
        // Condiciones:vectorIndex Con datos + Ninguno scopeDocIds(scope El conjunto de candidatos de escena es pequeño,El recorrido violento es suficiente)
        //      + Ninguno metadataFilter: resultados de HNSW sin metadatos,No se puede filtrar;Cuando el filtrado está activado, debe caer a
        //        vectorSearch(su apoyo interno metadataFilter),Evite que los filtros se ignoren silenciosamente.
        // Nota:Esto también sucederá cuando la recuperación mixta falle. — HNSW Mucho más rápido que la fuerza bruta
        val vi = vectorIndex
        if (vi != null && scopeDocIds.isNullOrEmpty() && (metadataFilter == null || metadataFilter.isEmpty())) {
            loadVectorIndexIfNeeded(provider)
            if (!vectorIndexNeedsFullRebuild && vi.size > 0) {
                val hnswResults = resultOf { vi.search(queryVector, topK) }
                    .onError { msg, e -> Logger.w("RagService", "HNSW Error de recuperación,Reducir el recorrido de fuerza bruta: $msg", e) }
                    .getOrNull()
                if (hnswResults != null && hnswResults.isNotEmpty()) {
                    val titles = getCachedTitles()
                    val mapped = hnswResults.mapNotNull { r ->
                        // chunkMetaCache golpear → Relleno directo;señorita(raro,El índice no es consistente con DB)→ saltar
                        val meta = chunkMetaCache.get(r.id) ?: return@mapNotNull null
                        VectorSearchService.SearchResult(
                            docId = meta.docId,
                            docTitle = titles[meta.docId] ?: "Unknown Document",
                            chunkContent = meta.content,
                            score = r.score,
                            chunkIndex = meta.chunkIndex,
                            chunkId = meta.chunkId,
                        )
                    }.filter { it.score >= threshold }
                    if (mapped.isNotEmpty()) {
                        Perf.log("rag-retrieve-hnsw", System.currentTimeMillis() - start)
                        // v1.133: Rellene el campo isInternal
                        return applyIsInternal(mapped)
                    }
                    // HNSW El resultado está vacío.(Puede que todo esté por debajo del umbral o que todos los fragmentos de MetaCache fallen)→ caer a vectorSearch
                }
            }
        }

        val results = vectorSearch.search(
            queryVector = queryVector,
            topK = topK,
            threshold = threshold,
            mmrLambda = ragConfig.mmrLambda,
            scopeDocIds = scopeDocIds,
            metadataFilter = metadataFilter,
        )
        Perf.log("rag-retrieve", System.currentTimeMillis() - start)
        // v1.133: Rellene el campo isInternal
        return applyIsInternal(results)
    }

    /**
     * v1.133: Relleno por lotes [VectorSearchService.SearchResult.isInternal] campo.
     *
     * de [KnowledgeDocDao.getByIds] Consulta la etiqueta isInternal correspondiente a cada docId,Luego copiar a cada
     * SearchResult en. Reemplazo unificado original `docId.startsWith("devdoc-")` Juicios codificados.
     *
     * Se devuelve una lista vacía cuando la consulta falla o falta la entidad del documento.fail-closed, evita documentos internos o huérfanos chunk
     * Ingrese el contexto del usuario cuando los metadatos no estén disponibles.
     */
    private suspend fun applyIsInternal(results: List<VectorSearchService.SearchResult>): List<VectorSearchService.SearchResult> {
        if (results.isEmpty()) return results
        val docIds = results.map { it.docId }.filter { it.isNotEmpty() }.distinct()
        if (docIds.isEmpty()) return results
        val docs = resultOf { docDao.getByIds(docIds) }
            .onError { msg, e -> Logger.w("RagService", "Error en la consulta por lotes de metadatos del documento: $msg", e) }
            .getOrNull()
            ?: return emptyList() // Fail closed: metadata failure must not expose internal documents as user content.
        if (docs.isEmpty()) return emptyList()
        val isInternalMap = docs.associate { it.id to it.isInternal }
        // chunk/HNSW/FTS Los registros que permanecen en el documento pero cuya entidad de documento se ha eliminado no deben continuar ingresando al contexto.
        return results
            .filter { it.docId in isInternalMap }
            .map { r -> r.copy(isInternal = isInternalMap[r.docId] == true) }
    }

    /**
     * v1.54: Construir texto inyectado(compatible con versiones anteriores). El nuevo código debería usar [buildInjectionContextWithCitations].
     */
    suspend fun buildInjectionContext(query: String, ragConfig: RagConfig): String =
        buildInjectionContextWithCitations(query, ragConfig, scopeDocIds = null).text

    /**
     * v1.133: Inyección de construcción — Regresar [RagInjection](Contiene texto + lista de citas.).
     *
     * apoyo:
     *  - Búsqueda híbrida / MMR / scopeDocIds
     *  - Rerank(si top-K×5 reordenación de candidatos)
     *  - Token control presupuestario(Acumular en orden descendente de similitud,Detener si se excede el presupuesto)
     *  - Lista de referencias(Para renderizado ChatUI)
     *
     * @param scopeDocIds Limitar el alcance de la búsqueda(@mention Para búsqueda dirigida)
     * @param metadataFilter Filtros de metadatos(Opcional,null = Sin filtrado);por [retrieve] paso hacia abajo,
     *   Son válidos tanto los enlaces vectoriales como los BM25.
     */
    suspend fun buildInjectionContextWithCitations(
        query: String,
        ragConfig: RagConfig,
        scopeDocIds: List<String>? = null,
        metadataFilter: VectorSearchService.MetadataFilter? = null,
    ): RagInjection {
        val start = System.currentTimeMillis()
        if (query.isBlank()) return RagInjection("", emptyList(), 0)

        // 1. Buscar(Ampliar el grupo de candidatos a topK×5 Para uso de reclasificación)
        val activeRerankProvider = resolveRerankProvider(ragConfig)
        val candidateK = if (ragConfig.rerankEnabled && activeRerankProvider != null) ragConfig.topK * 5 else ragConfig.topK
        // La documentación interna solo permite el uso de rutas explícitas de know_search include_internal.
        // No se debe ingresar la inyección automática de RAG solicitada por el sistema.
        var results = retrieve(query, candidateK, ragConfig.threshold, ragConfig, scopeDocIds, metadataFilter)
            .filterNot { it.isInternal }

        // v1.133: caché docId → isInternal mapeo(retrieve Ya reabastecido),rerank Necesito completar nuevamente más tarde
        // (Rerank La ruta descartará el SearchResult original y lo reconstruirá.,rerank El resultado no trae isInternal)
        val isInternalMap = results.associate { it.docId to it.isInternal }

        // 2. Reordenar
        var matchType = if (ragConfig.hybridEnabled) "hybrid" else "vector"
        if (ragConfig.rerankEnabled && activeRerankProvider != null && results.isNotEmpty()) {
            val candidates = results.map {
                RerankCandidate(
                    chunkId = it.chunkId,
                    docId = it.docId,
                    docTitle = it.docTitle,
                    content = it.chunkContent,
                    chunkIndex = it.chunkIndex,
                )
            }
            val reranked = resultOf { activeRerankProvider.rerank(query, candidates, ragConfig.topK) }
                .onError { msg, e -> Logger.w("RagService", "Rerank falló,Degradar secuencia original: $msg", e) }
                .getOrNull()
            if (reranked != null) {
                results = reranked.map { r ->
                    VectorSearchService.SearchResult(
                        docId = r.docId,
                        docTitle = r.docTitle,
                        chunkContent = r.content,
                        score = r.score,
                        chunkIndex = r.chunkIndex,
                        chunkId = r.chunkId,
                        // v1.133: Reservar isInternal(retrieve Comprobado,Evite la pérdida de reconstrucción por cambio de rango)
                        isInternal = isInternalMap[r.docId] ?: false,
                    )
                }
                matchType = if (ragConfig.rerankProvider == "onnx") "rerank-onnx" else "rerank"
            }
        } else {
            results = results.take(ragConfig.topK)
        }

        // 3. Construir texto + cita(Aplicar presupuesto simbólico)
        if (results.isNotEmpty()) {
            val citations = mutableListOf<RagCitation>()
            val sb = StringBuilder()
            sb.appendLine("La siguiente es información relevante recuperada de la base de conocimientos.,Por favor consúltelo al responder.(La similitud es solo para referencia.):")
            var usedTokens = 0
            val budget = ragConfig.tokenBudget
            // El límite superior de la inyección de un solo bloque: mucho más grande que el antiguo 200 codificado, lo que garantiza que el contenido realmente pueda ingresar al contexto después de aumentar el tamaño del fragmento.
            // Cuando hay un presupuesto simbólico, la verdadera adaptación se realiza mediante la lógica presupuestaria siguiente, que es el límite superior seguro.
            val chunkSnippetCap = 1200
            for ((idx, r) in results.withIndex()) {
                // P1-3 Reparación (informe de auditoría): No más codificación take(200).
                // La implementación anterior solo inyecta las primeras 200 palabras de cada bloque. Cuando el usuario ajusta el tamaño del fragmento a 500,
                // El último 60% nunca entrará en el contexto. —— Gastar más dinero en incorporación y obtener menos información es una pura pérdida.
                // Ahora se ha cambiado a: Déjelo en manos del presupuesto simbólico que aparece a continuación para recortarlo; si no hay límite de presupuesto, presione
                // [chunkSnippetCap] Límite superior, el valor predeterminado es mucho mayor que 200.
                val chunkSnippet = r.chunkContent.take(chunkSnippetCap)
                // La inyección solo envía fragmentos y el presupuesto también debe medirse en función del texto inyectado real para evitar eliminar accidentalmente los resultados posteriores con el número de token completo.
                val chunkTokens = TokenEstimator.estimate(chunkSnippet)
                // token control presupuestario(0 = sin limite)
                if (budget > 0 && usedTokens + chunkTokens > budget) {
                    Logger.d("RagService", "Token Presupuesto agotado(budget=$budget, used=$usedTokens, restante ${results.size - idx} Tiras desechadas)")
                    break
                }
                usedTokens += chunkTokens
                sb.appendLine("[${idx + 1}] Fuente: ${r.docTitle} (Similitud ${"%.2f".format(r.score)})")
                // Keep the actual injected text identical to the budgeted snippet.
                sb.appendLine(chunkSnippet)
                sb.appendLine()
                citations.add(
                    RagCitation(
                        index = idx + 1,
                        docId = r.docId,
                        docTitle = r.docTitle,
                        chunkId = r.chunkId,
                        chunkIndex = r.chunkIndex,
                        snippet = chunkSnippet,
                        score = r.score,
                        matchType = matchType,
                        // v1.133: Pasado desde SearchResult isInternal(Reemplace el juicio codificado del prefijo docId original)
                        isInternal = r.isInternal,
                    ),
                )
            }
            return RagInjection(
                text = sb.toString().trimEnd(),
                citations = citations,
                elapsedMs = System.currentTimeMillis() - start,
            )
        }

        // 4. No hay resultados para la búsqueda de vectores → Palabras clave: mantenlo en secreto(LIKE coincidencia de subcadenas)
        val fallback = scopedKeywordSearchFallback ?: if (scopeDocIds == null) {
            keywordSearchFallback?.let { legacy ->
                { text: String, limit: Int, _: List<String>?, _: VectorSearchService.MetadataFilter? -> legacy(text, limit) }
            }
        } else {
            null
        } ?: return RagInjection("", emptyList(), System.currentTimeMillis() - start)
        val allowedDocIds = scopeDocIds?.toSet()
        val fallbackResults = resultOf { fallback(query, ragConfig.topK, scopeDocIds, metadataFilter) }
            .onError { msg, e -> Logger.w("RagService", "Palabras clave: falta de encubrimiento: $msg", e) }
            .getOrNull().orEmpty()
            // Defend at the final injection boundary too; a fallback provider must not be
            // able to leak internal or out-of-scope documents into automatic prompts.
            .filterNot { it.isInternal }
            .filter { allowedDocIds == null || it.docId in allowedDocIds }
            .filter {
                metadataFilter == null || metadataFilter.isEmpty() ||
                    metadataFilter.matches(it.docId, it.docTitle, it.metadataJson, it.createdAt)
            }
            .distinctBy { it.chunkId.ifBlank { "${it.docId}\u0000${it.docTitle}\u0000${it.snippet}" } }
            .take(ragConfig.topK)
        if (fallbackResults.isEmpty()) return RagInjection("", emptyList(), System.currentTimeMillis() - start)

        val citations = mutableListOf<RagCitation>()
        val sb = StringBuilder()
        sb.appendLine("La siguiente es información relevante recuperada de la base de conocimientos.(concordancia de palabras clave),Por favor consúltelo al responder.:")
        var usedTokens = 0
        val budget = ragConfig.tokenBudget
        // P1-3 Solución: la misma ruta vectorial, ya no codificada en 200.
        val chunkSnippetCap = 1200
        for ((idx, result) in fallbackResults.withIndex()) {
            val snippet = result.snippet.take(chunkSnippetCap)
            val snippetTokens = TokenEstimator.estimate(snippet)
            if (budget > 0 && usedTokens + snippetTokens > budget) break
            usedTokens += snippetTokens
            sb.appendLine("[${idx + 1}] Fuente: ${result.docTitle}")
            sb.appendLine(snippet)
            sb.appendLine()
            citations.add(
                RagCitation(
                    index = idx + 1,
                    docId = result.docId,
                    docTitle = result.docTitle,
                    chunkId = result.chunkId,
                    chunkIndex = result.chunkIndex,
                    snippet = snippet,
                    score = result.score,
                    matchType = "keyword_fallback",
                    isInternal = result.isInternal,
                ),
            )
        }
        return RagInjection(
            text = sb.toString().trimEnd(),
            citations = citations,
            elapsedMs = System.currentTimeMillis() - start,
        )
    }

    /** Eliminar todo el índice de bloque del documento.(Contiene FTS + HNSW). */
    suspend fun deleteDocIndex(docId: String) {
        require(docId.isNotBlank()) { "docId must not be blank" }
        // Primero cargue el índice del disco y asegúrese de que la eliminación también sobrescriba las entradas HNSW antiguas que aún no están en la memoria.
        ensureVectorIndexLoaded()
        // P2-35: Cambie el orden de eliminación a "Borrar primero" HNSW(DB Recuperación autorizada del conjunto completo de chunkId)→ Eliminar DB/FTS nuevamente",
        // Asegúrese de que los fragmentos eliminados por LRU en la base de datos grande también se puedan eliminar del índice,Sin residuos.
        removeDocChunksFromVectorIndex(docId)
        // FTS Las fallas de limpieza deben descartarse: continuar eliminando fragmentos dejará resultados FTS fantasma no diagnosticables.
        withFtsSelfHeal { ftsDao.deleteByDoc(docId) }
        chunkDao.deleteByDoc(docId)
        // HNSW Es un índice derivado; se coloca inmediatamente después de la eliminación para evitar que el proceso se reinicie y resucite los documentos eliminados del índice anterior.
        saveVectorIndex()
        vectorSearch.invalidateCache()
        invalidateTitlesCache()
    }

    /** Eliminar la entidad del documento y todos sus índices derivados;UI/Si desea importar o cancelar, diríjase a esta entrada. */
    suspend fun deleteDocument(docId: String) {
        deleteDocIndex(docId)
        docDao.delete(docId)
        invalidateTitlesCache()
    }

    private data class StagedReindexSource(val file: File, val preview: String, val contentHash: String, val charCount: Long)

    private suspend fun stageReindexSource(pieces: Flow<String>): StagedReindexSource = withContext(Dispatchers.IO) {
        val directory = reindexStagingDirectory ?: indexFile?.parentFile
            ?: error("RAG reindex staging directory is unavailable")
        if (!directory.exists() && !directory.mkdirs()) error("Cannot create RAG reindex staging directory")
        val staged = File.createTempFile("reindex-", ".txt", directory)
        val digest = MessageDigest.getInstance("SHA-256")
        val preview = StringBuilder()
        var charCount = 0L
        try {
            staged.outputStream().buffered().use { output ->
                pieces.collect { piece ->
                    if (piece.isEmpty()) return@collect
                    val bytes = piece.toByteArray(Charsets.UTF_8)
                    output.write(bytes)
                    digest.update(bytes)
                    if (preview.length < REINDEX_PREVIEW_CHARS) {
                        preview.append(piece.take(REINDEX_PREVIEW_CHARS - preview.length))
                    }
                    charCount += piece.length
                }
            }
            check(charCount > 0) { "Original document source is empty" }
            StagedReindexSource(
                file = staged,
                preview = preview.toString(),
                contentHash = digest.digest().joinToString("") { "%02x".format(it) },
                charCount = charCount,
            )
        } catch (error: Throwable) {
            staged.delete()
            throw error
        }
    }

    private fun stagedReindexFlow(file: File): Flow<String> = flow {
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(64 * 1024)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (count > 0) emit(String(buffer, 0, count))
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * v1.133: Reindexar por lotes todos los documentos en KB especificado(para la página de administración de KB"Reindexar todo"botón).
     *
     * proceso:
     *  1. Pase [KnowledgeDocDao.getByKbIds] Obtenga todos los documentos en KB
     *  2. Llame artículo por artículo [indexDocument](Internamente, deleteByDoc se eliminará primero y luego se reconstruirá.)
     *  3. Devolución de llamada después de completar cada artículo. [onProgress](current, total)
     *  4. Sin interrupción si algún artículo falla,Continuar al siguiente artículo.,Finalmente devuelve una lista de documentos fallidos.
     *
     * @param kbIds Lista de identificadores de KB para reindexar(lista vacía = No ejecutado)
     * @param ragConfig Configuración RAG utilizada(Parámetros de fragmentación/embedding Fuente)
     * @param onProgress Devolución de llamada de progreso,(current, total) — 1-based
     * @return falló docId → Razón del fracaso Map
     */
    suspend fun reindexAllInKbs(
        kbIds: List<String>,
        ragConfig: RagConfig,
        sourceContentProvider: (suspend (KnowledgeDocEntity) -> Flow<String>?)? = null,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<String, String> {
        val visibleDocs =
            if (kbIds.isEmpty()) {
                null
            } else {
                resultOf { docDao.getByKbIds(kbIds) }
                    .onError { msg, e -> Logger.e("RagService", "KB Reindexar:No se pudo cargar el documento: $msg", e) }
                    .getOrNull()
            }
        if (visibleDocs == null) {
            if (kbIds.isNotEmpty()) Logger.w("RagService", "KB Reindexar:No hay documentos para procesar")
            return emptyMap()
        }
        val provider = embeddingService.getProvider(ragConfig)
        loadVectorIndexIfNeeded(provider)
        val plan = createReindexPlan(kbIds, visibleDocs)
        val failures = mutableMapOf<String, String>()
        // P1-9: Los documentos integrados también participan en la reconstrucción (para garantizar una indexación completa), pero no se incluyen en el número total visto por el usuario./progreso.
        // La implementación anterior los mezclaba en total, por lo que el usuario veía 30 de sus propios documentos pero se le solicitaban 46.
        val visibleDocCount = plan.documents.count { !it.isInternal }
        val internalDocCount = plan.documents.size - visibleDocCount
        val total = visibleDocCount
        Logger.i(
            "RagService",
            "KB Comienza la reindexación:Documentación de usuario $visibleDocCount Capítulo" +
                (if (internalDocCount > 0) "(Incluyendo incorporado $internalDocCount artículos, no incluidos en progreso)" else "") +
                " (kbIds=$kbIds)",
        )
        var visibleDone = 0
        for (doc in plan.documents) {
            try {
                reindexDocument(doc, ragConfig, plan.allowDimensionChange, sourceContentProvider)
            } catch (e: Throwable) {
                Logger.w("RagService", "KB Reindexar:Documentación ${doc.id} falló: ${e.message}", e)
                // Los errores de documentos integrados no entran en la lista de errores visibles para el usuario.
                if (!doc.isInternal) failures[doc.id] = e.message ?: e.javaClass.simpleName
            }
            if (!doc.isInternal) {
                visibleDone++
                onProgress(visibleDone, total)
            }
        }
        if (vectorIndexNeedsFullRebuild && plan.coversAllIndexedDocuments && failures.isEmpty()) {
            vectorIndexFingerprint = embeddingFingerprint(provider)
            vectorIndexNeedsFullRebuild = false
            vectorEmbeddingsNeedReindex = false
            saveVectorIndex(vectorIndexFingerprint)
        }
        Logger.i("RagService", "KB Reindexación completada:éxito ${total - failures.size}/$total,falló ${failures.size}")
        return failures
    }

    /**
     * Reindexar un solo documento.
     *
     * [KnowledgeDocEntity.content] Puede que sea sólo un adelanto; cuando [contentHash] Cuando no es coherente con el hash de vista previa,
     * debe pasar [sourceContentProvider] Lea el contenido original y desactive la sobrescritura de la vista previa en un nuevo índice completo.
     */
    suspend fun reindexDocument(
        docId: String,
        ragConfig: RagConfig,
        sourceContentProvider: (suspend (KnowledgeDocEntity) -> Flow<String>?)? = null,
    ): Int {
        val document = docDao.getById(docId) ?: error("Knowledge document not found: $docId")
        val provider = embeddingService.getProvider(ragConfig)
        loadVectorIndexIfNeeded(provider)
        reindexDocument(
            document = document,
            ragConfig = ragConfig,
            allowDimensionChange = false,
            sourceContentProvider = sourceContentProvider,
        )
        return docDao.getById(docId)?.chunkCount ?: 0
    }

    private data class ReindexPlan(
        val documents: List<KnowledgeDocEntity>,
        val coversAllIndexedDocuments: Boolean,
        val allowDimensionChange: Boolean,
    )

    private suspend fun createReindexPlan(kbIds: List<String>, visibleDocs: List<KnowledgeDocEntity>): ReindexPlan {
        val targetKbIds = kbIds.toSet()
        val allDocsInTargetKbs = resultOf { docDao.getAll() }
            .getOrNull()
            ?.filter { it.kbId in targetKbIds }
        val targetDocIds = allDocsInTargetKbs?.map { it.id }
        // P1-5 Reparación (informe de auditoría): Para implementación anterior chunkDao.getAllWithEmbedding() Pon toda la biblioteca
        // chunk Junto con la incrustación, se carga en la memoria una vez, solo para calcular un valor booleano.
        // Cambie a la capa SQL COUNT: el número de fragmentos que no pertenecen a la colección de documentos de destino es 0, es decir, "cobertura completa".
        val outsideChunkCount = if (targetDocIds.isNullOrEmpty()) {
            null
        } else {
            resultOf { chunkDao.countOutsideDocs(targetDocIds) }.getOrNull()
        }
        val coversAllIndexedDocuments = outsideChunkCount != null &&
            targetDocIds != null &&
            outsideChunkCount == 0
        val documents = if (vectorIndexNeedsFullRebuild && coversAllIndexedDocuments) {
            // P1-9 Reparación (informe de auditoría): Al reconstruir completamente, es necesario colocar los documentos integrados (devdoc) junto con la reindexación,
            // De lo contrario, el índice estaría incompleto; sin embargo**No permita que cuenten para la cantidad de artículos vistos por el usuario.** ——
            // La implementación anterior devuelve directamente todos los DocsInTargetKbs (sin filtrar is_internal),
            // El usuario ve 30 de sus propios documentos, pero cuando hace clic en Reconstruir, aparece el mensaje "46 Capítulo",
            // La primera reacción es“¿De dónde saqué 46 artículos? ¿Son datos sucios?”.
            //
            // Ahora se ha cambiado a: los documentos visibles para el usuario van primero (esta parte se incluye en el total) y los documentos integrados se añaden al final y se cuentan por separado.
            val visibleIds = visibleDocs.mapTo(mutableSetOf()) { it.id }
            val internals = allDocsInTargetKbs.orEmpty().filter { it.id !in visibleIds }
            visibleDocs + internals
        } else {
            visibleDocs
        }
        val allowDimensionChange = coversAllIndexedDocuments &&
            (vectorEmbeddingsNeedReindex || vectorIndexNeedsFullRebuild)
        return ReindexPlan(documents, coversAllIndexedDocuments, allowDimensionChange)
    }

    private suspend fun reindexDocument(
        document: KnowledgeDocEntity,
        ragConfig: RagConfig,
        allowDimensionChange: Boolean,
        sourceContentProvider: (suspend (KnowledgeDocEntity) -> Flow<String>?)?,
    ) {
        val storedContentHash = computeContentHash(document.content)
        val inlineContentComplete = if (document.contentHash.isNotBlank()) {
            document.contentHash == storedContentHash
        } else {
            document.content.length < REINDEX_PREVIEW_CHARS
        }
        if (inlineContentComplete) {
            val chunkCount = if (document.content.isBlank()) {
                clearDocumentChunks(document.id)
                0
            } else {
                indexDocumentInternal(
                    document.id,
                    document.content,
                    ragConfig,
                    allowDimensionChange = allowDimensionChange,
                    onProgress = { _, _ -> },
                )
            }
            if (chunkCount > 0) {
                docDao.upsert(
                    document.copy(
                        chunkCount = chunkCount,
                        embeddingModel = RagConfig.embeddingModelKey(ragConfig),
                        contentHash = document.contentHash.ifBlank { storedContentHash },
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            } else {
                clearDocumentChunks(document.id)
            }
            return
        }

        // Document.content is a preview; stage the original source completely before the existing index is touched.
        val source = sourceContentProvider?.invoke(document)
            ?: error("Document source is truncated or unavailable; re-import the original file before reindexing")
        val staged = stageReindexSource(source)
        try {
            val chunkCount = indexDocumentStreamed(
                docId = document.id,
                textPieces = stagedReindexFlow(staged.file),
                ragConfig = ragConfig,
            )
            check(chunkCount > 0) { "Original source produced no indexable chunks" }
            docDao.upsert(
                document.copy(
                    content = staged.preview,
                    chunkCount = chunkCount,
                    embeddingModel = RagConfig.embeddingModelKey(ragConfig),
                    contentHash = staged.contentHash,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        } finally {
            staged.file.delete()
        }
    }

    private suspend fun clearDocumentChunks(docId: String) {
        removeDocChunksFromVectorIndex(docId)
        withFtsSelfHeal { ftsDao.deleteByDoc(docId) }
        chunkDao.deleteByDoc(docId)
        docDao.clearIndexStatus(docId)
        vectorSearch.invalidateCache()
        invalidateTitlesCache()
    }

    private fun createChunkEntities(
        docId: String,
        chunks: List<TextChunker.Chunk>,
        embeddings: List<FloatArray>,
        chunker: TextChunker,
        createdAt: Long,
    ): List<KnowledgeChunkEntity> = chunks.mapIndexed { index, chunk ->
        KnowledgeChunkEntity(
            id = "chunk-$docId-$index",
            docId = docId,
            content = chunk.content,
            embedding = "",
            embeddingBlob = VectorSearchService.floatArrayToBlob(embeddings[index]),
            chunkIndex = index,
            tokenCount = chunker.estimateTokens(chunk.content),
            metadataJson = encodeMetadata(chunk.metadata),
            createdAt = createdAt,
        )
    }

    private fun shouldRejectDimensionChange(existingDim: Int?, newDim: Int, allowDimensionChange: Boolean): Boolean {
        return when {
            allowDimensionChange -> false
            existingDim == null -> false
            existingDim <= 0 -> false
            else -> existingDim != newDim
        }
    }

    private fun throwDimensionMismatch(docId: String, existingDim: Int, newDim: Int): Nothing {
        Logger.e(
            "RagService",
            "Embedding Las dimensiones no coinciden:La dimensión del fragmento ya existe en la biblioteca.=$existingDim,nuevo documento $docId Dimensiones=$newDim." +
                "Vaya a la página de administración de la base de conocimientos y haga clic en\"Reindexar todo\",Reconstruya el índice utilizando el mismo modelo de incrustación.",
        )
        throw IllegalStateException(
            "Embedding dimension mismatch: existing=$existingDim, new=$newDim (doc=$docId). " +
                "Run 'reindex all' with the same embedding model.",
        )
    }

    /**
     * R-DB-02/R-UI-01: Corrija Knowledge_chunks_fts y vuelva a indexar todos los documentos del usuario.
     * Limpia la mesa de sombra y reconstrúyela primero. FTS,Luego volver a indexar según la KB del documento al que pertenece;Mapa de devoluciones de documentos fallidos.
     */
    suspend fun repairKnowledgeFtsIndex(
        ragConfig: RagConfig,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<String, String> {
        KnowledgeChunkFtsSelfHealer.repair()
        val kbIds = resultOf { docDao.getAll() }
            .onError { msg, e -> Logger.e("RagService", "Arreglar FTS: no se pudo cargar el documento: $msg", e) }
            .getOrNull()
            ?.map { it.kbId }
            ?.distinct()
            .orEmpty()
        return reindexAllInKbs(kbIds, ragConfig, onProgress = onProgress)
    }

    fun invalidateVectorCache() = vectorSearch.invalidateCache()

    suspend fun indexedChunkCount(): Int = chunkDao.countIndexed()

    /**
     * v1.134: Según la corriente [RagConfig] Resuelva el proveedor de reclasificación real utilizado.
     *
     * - rerankEnabled=false → null(Sin reordenamiento)
     * - rerankProvider="onnx" + onnxRerankProvider Disponible → OnnxRerankProvider
     * - rerankProvider="onnx" + onnxRerankProvider No disponible → Rebajar rerankProvider(LocalRerankProvider)
     * - Otros → rerankProvider(LocalRerankProvider)
     */
    private fun resolveRerankProvider(ragConfig: RagConfig): RerankProvider? {
        if (!ragConfig.rerankEnabled) return null
        // P2-6(informe de auditoría): nube rerank —— OpenAI Compatible con el protocolo /rerank.
        // Anteriormente, solo existían LocalRerankProvider (básicamente inactivo para chinos) y ONNX no implementado.
        // No hay ninguna solución en la nube. Se construye bajo demanda cuando se completa la configuración y degrada automáticamente a local si falla.
        if (ragConfig.rerankProvider == "remote") {
            if (ragConfig.rerankBaseUrl.isBlank() || ragConfig.rerankModel.isBlank()) {
                Logger.w("RagService", "La configuración de cambio de rango en la nube está incompleta(baseUrl/model esta vacio), degradar el reordenamiento local")
            } else {
                return RemoteRerankProvider(
                    baseUrl = ragConfig.rerankBaseUrl,
                    apiKey = ragConfig.rerankApiKey,
                    model = ragConfig.rerankModel,
                    httpPost = { url, headers, body -> httpPostJson(url, headers, body) },
                )
            }
        }
        if (ragConfig.rerankProvider == "onnx" && onnxRerankProvider != null && onnxRerankProvider.isAvailable()) {
            return onnxRerankProvider
        }
        return rerankProvider
    }

    /**
     * P2-6: POST HTTP mínimo para reclasificación en la nube (no se introducen nuevas dependencias, se reutiliza OkHttp).
     *
     * No inyecte OkHttpClient directamente para evitar expandir la firma de construcción de RagService;
     * Ir al cliente de chat, tiempo de espera por [RemoteRerankProvider.DEFAULT_TIMEOUT_MS] Restricciones para la persona que llama.
     */
    private suspend fun httpPostJson(url: String, headers: Map<String, String>, body: String): String = withContext(Dispatchers.IO) {
        val client = httpClientProvider?.invoke()
            ?: error("Cambio de rango en la nube no disponible: no inyectado HTTP client")
        val request = okhttp3.Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("HTTP ${resp.code}: ${text.take(200)}")
            }
            text
        }
    }

    /**
     * Composición de huellas dactilares:provider id + Nombre del modelo + dimensiones.
     *
     * v2.6.0 (AUD-RAG-F3): Uso de dimensiones [EmbeddingProvider.fingerprintDimension] ——
     * [EmbeddingProvider.dimension] Puede ser un valor aproximado antes de la primera inserción, que no coincide con la dimensión real.
     * permitirá que las huellas dactilares "adivinen"→Salte entre "real", cada arranque en frío determina los cambios de huellas dactilares y reconstruye el índice HNSW.
     */
    private fun embeddingFingerprint(provider: EmbeddingProvider): String =
        "${provider.id}\u0000${provider.modelName}\u0000${provider.fingerprintDimension}"

    /**
     * v1.133: Analizar el texto @mention en una lista de docIds(@mention Para búsqueda dirigida).
     *
     * proceso:
     *  1. de [text] Extraiga todas las etiquetas @xxx de(Quitar el prefijo @)
     *  2. Llamado para cada palabra clave mencionada [KnowledgeDocDao.findByTitleKeyword] coincidencia difusa
     *  3. Fusionar todos los documentos visitados id(Eliminar duplicados)
     *
     * @return lista de docId después de la deduplicación;Una lista vacía significa que no se alcanzaron documentos(La persona que llama debe realizar una búsqueda completa en la base de datos.)
     */
    suspend fun resolveMentionToDocIds(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val mentions = MENTION_REGEX.findAll(text).map { it.value.removePrefix("@").trim() }
            .filter { it.isNotBlank() }.toList()
        if (mentions.isEmpty()) return emptyList()
        val docIds = linkedSetOf<String>()
        for (mention in mentions) {
            val escaped = KnowledgeDocDao.escapeLikeQuery(mention)
            val docs = resultOf { docDao.findByTitleKeyword(escaped) }
                .onError { msg, e -> Logger.w("RagService", "@mention El análisis falló '$mention': $msg", e) }
                .getOrNull() ?: emptyList()
            docs.forEach { docIds.add(it.id) }
        }
        Logger.d("RagService", "@mention analizar: ${mentions.size} un mention → ${docIds.size} un docId")
        return docIds.toList()
    }

    /**
     * Amplíe el ID de la base de conocimientos vinculado al asistente a un ID de documento con capacidad de búsqueda.
     * Una lista vacía indica claramente que no hay ninguna base de conocimientos vinculada.,Las personas que llaman deben seguir utilizando la semántica de recuperación global.
     */
    suspend fun resolveKnowledgeBaseDocIds(knowledgeBaseIds: List<String>): List<String> {
        val ids = knowledgeBaseIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyList()
        return resultOf { docDao.getByKbIds(ids).map { it.id }.distinct() }
            .onError { msg, throwable ->
                Logger.w("RagService", "No se pudo expandir el asistente vinculado a la base de conocimientos: ${throwable?.message ?: msg}", throwable)
            }
            .getOrNull()
            ?: emptyList()
    }

    /**
     * v2.4.6: Expanda por "Base de conocimientos + Ruta de la carpeta" para recuperar el ID del documento.
     *
     * visión del mundo:base de conocimientos(KB)= raíz,carpeta = Subdirectorios bajo la raíz. Una carpeta es un dominio de búsqueda independiente.,
     * [folderPath] es una ruta relativa dentro de KB,Una cadena vacía representa toda la biblioteca. Expandir reglas = Coincidencia de prefijo de ruta:
     *  - camino vacío → Toda la documentación de usuario para esta KB;
     *  - camino `contrato` → folderPath para `contrato` o `contrato/...` Documentación(Contiene subdirectorios).
     * Esto garantiza que los resultados de búsqueda de diferentes carpetas no se confundan entre sí.
     *
     * @param scopes en forma de (kbId, folderPath) lista vinculante
     * @return El conjunto de identificadores de documentos visitados.;Una colección vacía significa sin consolidar(Búsqueda global de reserva de llamadas)
     */
    suspend fun resolveKbFolderScopes(scopes: List<Pair<String, String>>): List<String> {
        val normalized = scopes
            .map { it.first.trim() to io.zer0.muse.rag.KnowledgeFolderBrowser.normalize(it.second) }
            .filter { it.first.isNotBlank() }
            .distinct()
        val kbIds = normalized.map { it.first }.distinct()
        val allDocs = if (kbIds.isEmpty()) {
            emptyList()
        } else {
            resultOf { docDao.getByKbIds(kbIds) }
                .onError { msg, throwable ->
                    Logger.w("RagService", "KB+Falló la expansión del alcance de la carpeta: ${throwable?.message ?: msg}", throwable)
                }
                .getOrNull()
                .orEmpty()
        }
        return if (normalized.isEmpty() || allDocs.isEmpty()) {
            emptyList()
        } else {
            allDocs.asSequence()
                .filter { doc ->
                    normalized.any { (kbId, path) ->
                        doc.kbId == kbId && pathHitsFolder(doc, path)
                    }
                }
                .map { it.id }
                .distinct()
                .toList()
        }
    }

    /** Determinar si el documento se encuentra en una determinada ruta de carpeta(camino vacío = Toda la biblioteca;De lo contrario, el prefijo de ruta coincide,Contiene subdirectorios). */
    private fun pathHitsFolder(doc: KnowledgeDocEntity, path: String): Boolean {
        if (path.isEmpty()) return true
        val p = io.zer0.muse.rag.KnowledgeFolderBrowser.folderPath(doc)
        return p == path || p.startsWith("$path/")
    }

    /** v1.133: @mention extraer regular(Sinónimo de ChatViewModel.KNOWLEDGE_MENTION_REGEX,RagService uso personal). */
    companion object {
        val MENTION_REGEX = Regex("@[^\\s@]+")

        /** docTitle TTL de caché: 5 minutos(Invalidar automáticamente cuando se agregan o eliminan documentos,No es necesario esperar a que caduque). */
        const val TITLES_TTL_MS = 5L * 60 * 1000

        /** v1.55: HNSW Umbral de guardado automático del índice(Se activa una vez después de agregar SAVE_INTERVAL fragmentos en total save). */
        const val SAVE_INTERVAL = 50

        /** v2.x: HNSW Intervalo mínimo entre la realización de pedidos — Incluso si llega SAVE_INTERVAL,No debe haber menos de este intervalo entre dos colocaciones. */
        const val SAVE_THROTTLE_MS = 15_000L

        /** v2.x: Tamaño de la ventana deslizante del índice de transmisión(personaje). Cuanto mayor sea la ventana, mayor será el pico de memoria y menos divisiones entre ventanas. */
        const val STREAM_WINDOW_CHARS = 200_000

        private const val REINDEX_PREVIEW_CHARS = 500_000

        /** B-35: chunkMetaCache LRU Número máximo de artículos(Aproximadamente 20 MB de tamaño,Evitar que el contenido completo resida en la memoria). */
        const val MAX_CHUNK_META = 2000

        /** v1.0.47: embedding duración del fusible — La recuperación volverá directamente vacía dentro de los 5 minutos posteriores al error.,Degradar la búsqueda local. */
        const val EMBEDDING_CIRCUIT_BREAKER_MS = 5L * 60 * 1000

        /**
         * P0-1(informe de auditoría): query embedding tiempo de espera difícil.
         *
         * Recupere la ruta clave para enviar mensajes y reutilice OkHttpClient del chat (readTimeout=300s).
         * punto final“Aceptando conexión pero sin respuesta”, el usuario quedará atrapado hasta 5 minutos antes de ver la primera palabra.
         * 10s Es un límite superior razonable: las consultas de incrustación normales son mucho más bajas que esto y el disyuntor se degradará cuando expire el tiempo de espera.
         */
        private const val EMBED_QUERY_TIMEOUT_MS = 10_000L

        /** P3-3: chunkMetaCache El número de elementos leídos por página durante la reconstrucción. */
        private const val CACHE_REBUILD_PAGE_SIZE = 500

        /** v1.133: content SHA-256 used by all knowledge import entry points. */
        fun computeContentHash(content: String): String = resultOf {
            val md = MessageDigest.getInstance("SHA-256")
            md.digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: ""
    }

    /** v1.133: Serializar el mapa de metadatos de fragmentos a JSON. */
    private fun encodeMetadata(meta: Map<String, String>): String =
        resultOf { json.encodeToString(MapSerializer(String.serializer(), String.serializer()), meta) }
            .getOrNull() ?: "{}"
}
