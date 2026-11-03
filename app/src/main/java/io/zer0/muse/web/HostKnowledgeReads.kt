package io.zer0.muse.web

import io.zer0.muse.data.knowledge.KnowledgeBaseDao
import io.zer0.muse.data.knowledge.KnowledgeChunkDao
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.rag.EmbeddingService

/**
 * Comando de solo lectura de conocimiento(`knowledge.list` / `knowledge.documents`)proyección de carga.
 *
 * dos cosas:
 *  - Convierta el banco de almacenamiento en un acuerdo DTO(Las bibliotecas vacías se pliegan en matrices vacías,La biblioteca no existe y se convierte en evento con error,No lanzar excepción);
 *  - Diámetro de paginación y número de recortes.([pageLimit]),con `memory.list` Lo mismo lo hace primero la capa de comando y luego lo verifica.
 *
 * ## Tamaño de página
 *
 * Los documentos están ordenados por "los más recientes actualizados primero"(`updated_at DESC, id DESC`),`cursor` Obtenga el último elemento en la página anterior id,
 * La página siguiente solo toma las filas que la siguen.keyset En lugar de desplazamiento: al importar un nuevo documento solo se insertará antes de la primera página,
 * Las siguientes páginas no estarán llenas de repeticiones ni líneas saltadas.
 */
internal object HostKnowledgeReads {

    const val TYPE_LIST = "knowledge.list"
    const val TYPE_DOCUMENTS = "knowledge.documents"

    /** El valor predeterminado del número de elementos en una sola página. */
    const val DEFAULT_LIMIT = 20

    /** Número máximo de entradas en una sola página:La página web no puede caber más en una pantalla.,Cualquier página más grande sólo hará que el recibo sea más grande. */
    const val MAX_LIMIT = 50

    /** valor de error cuando la base de conocimientos no existe(`knowledge.documents` El kbId falla). */
    const val ERROR_KB_NOT_FOUND = "knowledge_base_not_found"

    /** Documento de una página + si hay una página siguiente. */
    data class DocumentPage(val documents: List<HostKnowledgeDocument>, val hasMore: Boolean)

    /** `knowledge.list` Acuse de recibo. Enviar matriz vacía bajo biblioteca vacía,No es un error. */
    fun listEvent(bases: List<HostKnowledgeBase>, requestId: String?): HostEvent = HostEvent(
        type = TYPE_LIST,
        requestId = requestId,
        knowledgeBases = bases,
    )

    /**
     * `knowledge.documents` Acuse de recibo.
     *
     * [page] Si es nulo, la base de conocimientos de destino no existe. —— volver [ERROR_KB_NOT_FOUND] en lugar de una página vacía,
     * De lo contrario, la página web mostrará "La biblioteca ha sido eliminada" como "No hay ningún documento en la biblioteca".
     */
    fun documentsEvent(kbId: String, page: DocumentPage?, requestId: String?): HostEvent {
        if (page == null) {
            return HostEvent(type = TYPE_DOCUMENTS, requestId = requestId, kbId = kbId, error = ERROR_KB_NOT_FOUND)
        }
        return HostEvent(
            type = TYPE_DOCUMENTS,
            requestId = requestId,
            kbId = kbId,
            documents = page.documents,
            hasMore = page.hasMore,
        )
    }

    /** El número solicitado de elementos por página se recorta a `1..`[MAX_LIMIT];No pases el botón [DEFAULT_LIMIT]. */
    fun pageLimit(requested: Int?): Int = (requested ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    /** Estado del índice del documento.[embeddingModel] Cuando el índice no alcanza el valor centinela, primero se determina que es necesario reconstruirlo. */
    fun statusOf(chunkCount: Int, embeddingModel: String): String = when {
        embeddingModel == KnowledgeDocDao.INDEX_BROKEN_SENTINEL -> HostDocumentStatus.INDEX_BROKEN
        chunkCount > 0 -> HostDocumentStatus.INDEXED
        else -> HostDocumentStatus.PENDING
    }
}

/**
 * Fuente de datos para comandos de conocimiento de solo lectura.
 *
 * La interfaz de comisión permite que la puerta de enlace funcione sin conectarse a la base de conocimientos.(RAG no habilitado, escenario de chat puro)También puedes recibir el recibo como de costumbre.;
 * Ver producción y montaje [DaoHostKnowledgeSource].
 */
internal interface HostKnowledgeSource {

    /** Base de conocimientos de un vistazo,Ordene primero las actualizaciones más recientes. */
    suspend fun bases(): List<HostKnowledgeBase>

    /**
     * Una página de documentos bajo una determinada base de conocimientos.
     *
     * @return null expresar [kbId] La base de conocimientos correspondiente no existe.
     */
    suspend fun documents(kbId: String, cursor: String?, limit: Int): HostKnowledgeReads.DocumentPage?

    /** Implementación vacía cuando no está ensamblada:No hay biblioteca. */
    object Empty : HostKnowledgeSource {
        override suspend fun bases(): List<HostKnowledgeBase> = emptyList()

        override suspend fun documents(kbId: String, cursor: String?, limit: Int): HostKnowledgeReads.DocumentPage? = null
    }
}

/**
 * Lea directamente las fuentes de datos de tres tablas en la base de conocimientos.(La misma biblioteca y el mismo conjunto de criterios de filtrado que la página de base de conocimientos de la aplicación).
 *
 * No se lee ningún conteo `knowledge_bases.doc_count` tipo de columna de escritura,Pero en términos reales:
 * El número de documentos consta de [KnowledgeBaseDao.getAll] La subconsulta de da,El número de bloques está dado por [KnowledgeChunkDao.countByKb] agregación.
 */
internal class DaoHostKnowledgeSource(
    private val baseDao: KnowledgeBaseDao,
    private val docDao: KnowledgeDocDao,
    private val chunkDao: KnowledgeChunkDao,
    private val embeddingService: EmbeddingService,
) : HostKnowledgeSource {

    override suspend fun bases(): List<HostKnowledgeBase> {
        val backend = embeddingBackend()
        val chunkCounts = chunkDao.countByKb().associate { it.kbId to it.chunkCount }
        return baseDao.getAll().map { base ->
            HostKnowledgeBase(
                id = base.id,
                name = base.name,
                documentCount = base.docCount,
                chunkCount = chunkCounts[base.id] ?: 0,
                createdAt = base.createdAt,
                updatedAt = base.updatedAt,
                embeddingBackend = backend,
            )
        }
    }

    override suspend fun documents(kbId: String, cursor: String?, limit: Int): HostKnowledgeReads.DocumentPage? {
        // Asegúrese de que el inventario exista primero:El kbId inexistente y la "biblioteca vacía" deben dar recibos diferentes.
        if (baseDao.getById(kbId) == null) return null
        // Toma uno más solo para juzgar hasMore,No incluido en el recibo.
        val rows = docDao.getPageByKb(kbId = kbId, cursor = cursor, limit = limit + 1)
        return HostKnowledgeReads.DocumentPage(
            documents = rows.take(limit).map { row ->
                HostKnowledgeDocument(
                    id = row.id,
                    title = row.title,
                    chunkCount = row.chunkCount,
                    sizeBytes = row.sizeBytes,
                    createdAt = row.createdAt,
                    status = HostKnowledgeReads.statusOf(row.chunkCount, row.embeddingModel),
                )
            },
            hasMore = rows.size > limit,
        )
    }

    /**
     * El nombre del backend de inserción actualmente efectivo.
     *
     * tomar [EmbeddingService.activeBackend] Esta instantánea del tiempo de ejecución(Este proceso solo se completará después de que el Proveedor esté realmente construido.),
     * En lugar de en la configuración `embeddingSource` —— Los dos son diferentes cuando se cambia a palabras clave locales.,Lo que queremos aquí es el que realmente se está ejecutando.
     * Devuelto cuando el backend no ha sido analizado en el proceso null,Deje este campo en blanco en el recibo.
     */
    private fun embeddingBackend(): String? =
        embeddingService.activeBackend.value
            ?.let { state -> state.modelName.ifBlank { state.backendId } }
            ?.takeIf { it.isNotBlank() }
}
