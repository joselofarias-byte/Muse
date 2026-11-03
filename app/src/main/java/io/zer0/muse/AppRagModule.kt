package io.zer0.muse

import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * P2-3 Deslocalización:RAG / Módulo independiente de registro de búsqueda de base de conocimiento.
 */
val appRagModule = module {

    // v1.54: RAG Sistema:Embedding Servicio + Orquestación de recuperación de vectores
    // v1.134: Inyecte filesDir para EmbeddingService para resolver las rutas relativas del modelo ONNX
    single {
        io.zer0.muse.rag.EmbeddingService(
            configStore = get(),
            client = get(named("chat")),
            filesDir = androidContext().filesDir,
        )
    }
    // v1.133: locales Rerank Provider(Sin dependencias,Plan de degradación)
    single<io.zer0.muse.rag.RerankProvider> { io.zer0.muse.rag.LocalRerankProvider() }
    // v1.134: locales ONNX Cross-Encoder Rerank Provider(Opcional,Bajar automáticamente a cuando falta el modelo LocalRerankProvider)
    // Convenciones de archivos de modelo:filesDir/muse_onnx/rerank.onnx + Mismo directorio vocab.txt
    single {
        io.zer0.muse.rag.OnnxRerankProvider(
            modelPath = java.io.File(androidContext().filesDir, "muse_onnx/rerank.onnx").absolutePath,
        )
    }
    // v1.133: Servicio de búsqueda híbrido(FTS4 + Vector RRF)
    single {
        io.zer0.muse.rag.HybridSearchService(
            ftsDao = get<io.zer0.muse.data.session.MuseDb>().knowledgeChunkFtsDao(),
            vectorSearch = io.zer0.muse.rag.VectorSearchService(
                chunkPageProvider = { limit, offset ->
                    // v2.6.x: Proyección de título/identificación de solo lectura;Llamado una vez por página en el bucle de paginación.,Ya no lee el texto completo en la memoria
                    val titles = get<io.zer0.muse.data.knowledge.KnowledgeDocDao>().getDocTitles()
                        .associate { it.id to it.title }
                    get<io.zer0.muse.data.knowledge.KnowledgeChunkDao>().getPageWithEmbedding(limit, offset).map { chunk ->
                        io.zer0.muse.rag.VectorSearchService.ChunkWithDoc(
                            chunkId = chunk.id, docId = chunk.docId,
                            docTitle = titles[chunk.docId] ?: "Unknown",
                            content = chunk.content, embedding = chunk.embedding,
                            embeddingBlob = chunk.embeddingBlob, chunkIndex = chunk.chunkIndex,
                            // B4-03: Metadatos transparentes/Marca de tiempo,Para que metadataFilter filtre dentro de enlaces vectoriales de búsquedas híbridas
                            metadataJson = chunk.metadataJson, createdAt = chunk.createdAt,
                        )
                    }
                },
                chunkCountProvider = { get<io.zer0.muse.data.knowledge.KnowledgeChunkDao>().countIndexed() },
                // P2-30: Búsqueda dirigida empuja hacia abajo para SQL(HybridSearchService no se inyectó antes docIds provider,
                // La recuperación dirigida seguirá estando llena antes del filtrado de memoria load,Cuando una biblioteca grande cruza el límite de toda la biblioteca, está limitada por ScopeDocIds pero la memoria aumenta.).
                // P2-33: Cambiar a paginación SQL(getPageByDocIds traer LIMIT/OFFSET)——antigua implementación getByDocIds(...)
                // Todo el rango de búsqueda se cargará en fragmentos a la vez. drop/take;El título del documento solo se toma de los documentos de esta página.,
                // Ya no tendrás que cargar la tabla de documentos completa repetidamente en cada página.
                chunkPageByDocIdsProvider = { docIds, limit, offset ->
                    val chunkDao = get<io.zer0.muse.data.knowledge.KnowledgeChunkDao>()
                    val docDao = get<io.zer0.muse.data.knowledge.KnowledgeDocDao>()
                    val chunks = chunkDao.getPageByDocIds(docIds, limit, offset)
                    val titles = if (chunks.isEmpty()) {
                        emptyMap()
                    } else {
                        docDao.getByIds(chunks.map { it.docId }.distinct()).associate { it.id to it.title }
                    }
                    chunks.map { chunk ->
                        io.zer0.muse.rag.VectorSearchService.ChunkWithDoc(
                            chunkId = chunk.id, docId = chunk.docId,
                            docTitle = titles[chunk.docId] ?: "Unknown",
                            content = chunk.content, embedding = chunk.embedding,
                            embeddingBlob = chunk.embeddingBlob, chunkIndex = chunk.chunkIndex,
                            // B4-03: Metadatos transparentes/Marca de tiempo,Para que metadataFilter filtre dentro de búsquedas específicas
                            metadataJson = chunk.metadataJson, createdAt = chunk.createdAt,
                        )
                    }
                },
            ),
            // P2-31: BM25-only(golpe preciso)Metadatos de contenido completos,Ya no se descarta en su totalidad
            bm25MetaResolver = { chunkIds ->
                val dao = get<io.zer0.muse.data.knowledge.KnowledgeChunkDao>()
                val titles = get<io.zer0.muse.data.knowledge.KnowledgeDocDao>().getDocTitles()
                    .associate { it.id to it.title }
                dao.getByIds(chunkIds).associate { chunk ->
                    chunk.id to io.zer0.muse.rag.HybridSearchService.ChunkMeta(
                        docId = chunk.docId,
                        docTitle = titles[chunk.docId] ?: "Unknown",
                        content = chunk.content,
                        chunkIndex = chunk.chunkIndex,
                        // B4-03: Metadatos transparentes/Marca de tiempo,Para que HybridSearchService filtre enlaces BM25
                        metadataJson = chunk.metadataJson,
                        createdAt = chunk.createdAt,
                    )
                }
            },
        )
    }
    single {
        io.zer0.muse.rag.RagService(
            chunkDao = get(),
            docDao = get(),
            ftsDao = get<io.zer0.muse.data.session.MuseDb>().knowledgeChunkFtsDao(),
            docTitleProvider = {
                get<io.zer0.muse.data.knowledge.KnowledgeDocDao>().getDocTitles()
                    .associate { it.id to it.title }
            },
            embeddingService = get(),
            hybridSearchService = get(),
            rerankProvider = get(),
            onnxRerankProvider = get(),
            // P2-6: Cliente HTTP para reclasificación en la nube (comparte grupo de conexiones con chat)
            httpClientProvider = { get<okhttp3.OkHttpClient>(named("chat")) },
            // Keyword fallback shares the vector path's document scope, internal-document exclusion,
            // metadata filter, and stable chunk identity so citations remain navigable.
            keywordSearchFallback = { query, topK ->
                io.zer0.muse.rag.KnowledgeKeywordFallback.search(
                    query = query,
                    topK = topK,
                    scopeDocIds = null,
                    metadataFilter = null,
                    daos = io.zer0.muse.rag.KnowledgeKeywordFallback.SearchDaos(
                        docDao = get(),
                        chunkDao = get(),
                        ftsDao = get<io.zer0.muse.data.session.MuseDb>().knowledgeChunkFtsDao(),
                    ),
                )
            },
            scopedKeywordSearchFallback = { query, topK, docIds, metadataFilter ->
                io.zer0.muse.rag.KnowledgeKeywordFallback.search(
                    query = query,
                    topK = topK,
                    scopeDocIds = docIds,
                    metadataFilter = metadataFilter,
                    daos = io.zer0.muse.rag.KnowledgeKeywordFallback.SearchDaos(
                        docDao = get(),
                        chunkDao = get(),
                        ftsDao = get<io.zer0.muse.data.session.MuseDb>().knowledgeChunkFtsDao(),
                    ),
                )
            },
            // v1.0.12: HNSW Ruta del archivo de persistencia del índice — Habilitar la colocación del índice de vectores RAG
            // ubicación del archivo:filesDir/rag/hnsw_index.bin;App MuseApp.onCreate se carga de forma asincrónica después de reiniciar,
            // Evite reconstruir completamente el índice desde la base de datos cada vez que lo inicie.indexFile Predeterminado null(No persistente,solo memoria),
            // La inyección explícita aquí permite la persistencia,Compatibilidad con versiones anteriores de personas mayores(Las rutas nulas predeterminadas no se ven afectadas).
            // rag/ Directorio creado en la inyección(mkdirs idempotente,Ya existen sin efectos secundarios.).
            indexFile = java.io.File(androidContext().filesDir, "rag/hnsw_index.bin").apply {
                parentFile?.mkdirs()
            },
        )
    }
    // v1.133: KnowledgeBaseDao Regístrese por separado(Para múltiples páginas de gestión de bases de conocimientos)
    single { get<io.zer0.muse.data.session.MuseDb>().knowledgeBaseDao() }
    // v2.4.6: Carpeta de la base de conocimientos DAO(Contiene carpetas vacías,carpeta=Recuperar unidades de cuarentena)
    single { get<io.zer0.muse.data.session.MuseDb>().knowledgeFolderDao() }
    // v1.0.47 P7-2: Servicio de indexación de archivos adjuntos a nivel de sesión
    single { io.zer0.muse.rag.SessionAttachmentService(get(), get(), get()) }
}
