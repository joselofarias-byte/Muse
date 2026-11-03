package io.zer0.muse.data.knowledge

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * v2.6.x: Una proyección ligera para mapeo de títulos de documentos.
 *
 * Recuperar enlace solamente id→title,usar [KnowledgeDocDao.observeAll] voluntad content(Texto completo)leer juntos en la memoria,
 * Y esta lectura ocurrirá una vez por cada página en el bucle de paginación.
 */
data class KnowledgeDocTitleRow(
    val id: String,
    val title: String,
)

/**
 * Proyección ligera para listas de documentos.:No leas el texto,Solo se incluye el número de bytes y el estado del índice.
 *
 * [sizeBytes] Calculado por SQL `LENGTH(CAST(content AS BLOB))`;Leer el texto completo nuevamente en la memoria solo para medirlo.
 * tamaño,Se queda atascado en la primera pantalla de la página de la base de conocimientos.
 */
data class KnowledgeDocListRow(
    val id: String,
    val title: String,
    @ColumnInfo(name = "chunk_count") val chunkCount: Int,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "embedding_model") val embeddingModel: String,
)

@Dao
interface KnowledgeDocDao {
    @Query("SELECT * FROM knowledge_docs ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<KnowledgeDocEntity>>

    /**
     * v2.6.x: id→title Una sola instantánea de(Proyecte solo las dos columnas id/title,no leer content).
     *
     * ordenar con [observeAll] consistente(updated_at orden descendente),llamador `associate` El contenido y el orden del mapeo resultante permanecen sin cambios.;
     * La semántica sigue siendo "tomar una vez".,No hay retorno al Flujo, no hay observación continua.
     */
    @Query("SELECT id, title FROM knowledge_docs ORDER BY updated_at DESC")
    suspend fun getDocTitles(): List<KnowledgeDocTitleRow>

    /**
     * v1.133: Enumerar documentos por ID de base de conocimientos(Los documentos internos están excluidos por defecto.).
     * kbId para "default" Esta es la base de datos principal del usuario.
     */
    @Query(
        "SELECT * FROM knowledge_docs WHERE kb_id = :kbId AND is_internal = 0 ORDER BY updated_at DESC",
    )
    fun observeByKb(kbId: String): Flow<List<KnowledgeDocEntity>>

    /** v1.133: Listar todos los documentos no internos(Filtrado de conjuntos de candidatos para búsquedas entre KB). */
    @Query("SELECT * FROM knowledge_docs WHERE is_internal = 0 ORDER BY updated_at DESC")
    fun observeAllUser(): Flow<List<KnowledgeDocEntity>>

    /**
     * Listar documentos paginados por base de conocimientos(Sin documentación interna),ordenar con [observeByKb] coherente.
     *
     * keyset Paginación:`cursor` Sube el último elemento de la página anterior. id,Toma solo las filas que le siguen.
     * (`updated_at` antes,Para la misma marca de tiempo, la identificación es menor.).`cursor` no encontrado(El documento ha sido eliminado y la biblioteca ha sido cambiada.)
     * Cuando la subconsulta devuelve NULL, la comparación no se mantiene.,Esta pagina esta vacia —— Preferiría volver a una página vacía que volver silenciosamente a la primera página.
     */
    @Query(
        """
        SELECT id, title, chunk_count,
               LENGTH(CAST(content AS BLOB)) AS size_bytes,
               created_at, embedding_model
        FROM knowledge_docs
        WHERE kb_id = :kbId AND is_internal = 0
          AND (
            :cursor IS NULL
            OR updated_at < (SELECT updated_at FROM knowledge_docs WHERE id = :cursor)
            OR (
                updated_at = (SELECT updated_at FROM knowledge_docs WHERE id = :cursor)
                AND id < :cursor
            )
          )
        ORDER BY updated_at DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getPageByKb(kbId: String, cursor: String?, limit: Int): List<KnowledgeDocListRow>

    /**
     * v0.23: Búsqueda de texto completo — El título o contenido contiene query(No distingue entre mayúsculas y minúsculas).
     *
     * M-KB1/M-KB2: uso ESCAPE '\' cláusula,La persona que llama debe usar primero [escapeLikeQuery] Escape en consulta
     * LIKE comodín(% _ \),De lo contrario, el %_ ingresado por el usuario se tratará como un carácter comodín.
     * La base de conocimientos tiene una pequeña cantidad de datos.,LIKE aceptable;Si necesitas un seguimiento real FTS,Se puede introducir KnowledgeFtsEntity + MATCH.
     */
    @Query(
        """
        SELECT * FROM knowledge_docs
        WHERE (title LIKE '%' || :query || '%' ESCAPE '\' OR content LIKE '%' || :query || '%' ESCAPE '\')
          AND (:includeInternal = 1 OR is_internal = 0)
        ORDER BY updated_at DESC
        """,
    )
    fun search(query: String, includeInternal: Boolean = false): Flow<List<KnowledgeDocEntity>>

    @Query("SELECT * FROM knowledge_docs WHERE id = :id")
    suspend fun getById(id: String): KnowledgeDocEntity?

    /**
     * v1.133: Consultar documentos en lotes por ID(Contiene campos internos).
     *
     * usado para [io.zer0.muse.rag.RagService.retrieve] El relleno es una etiqueta interna en los resultados de recuperación de vectores,
     * sustituto original `docId.startsWith("devdoc-")` Codificado.
     * ids Devuelve una lista vacía si está vacía(Evite escanear por error toda la tabla).
     */
    @Query("SELECT * FROM knowledge_docs WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<KnowledgeDocEntity>

    /** v1.133: Consulta por lotes por kb_id(Resumen de la documentación de KB para el asistente de encuadernación). */
    @Query("SELECT * FROM knowledge_docs WHERE kb_id IN (:kbIds) AND is_internal = 0")
    suspend fun getByKbIds(kbIds: List<String>): List<KnowledgeDocEntity>

    /** v2.4.x: Listar todos los documentos internos(devdoc), para que Seeder cree índices de forma incremental. */
    @Query("SELECT * FROM knowledge_docs WHERE is_internal = 1")
    suspend fun getInternalDocs(): List<KnowledgeDocEntity>

    /** v1.133: Consulta por content_hash(Juicio de actualización incremental). */
    @Query("SELECT * FROM knowledge_docs WHERE content_hash = :hash LIMIT 1")
    suspend fun findByContentHash(hash: String): KnowledgeDocEntity?

    /** v1.133: Coincidencia difusa por título(@mention Se utiliza para analizar docId). */
    @Query(
        "SELECT * FROM knowledge_docs WHERE title LIKE '%' || :titleKeyword || '%' ESCAPE '\\' AND is_internal = 0 LIMIT 5",
    )
    suspend fun findByTitleKeyword(titleKeyword: String): List<KnowledgeDocEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(doc: KnowledgeDocEntity)

    /** Mark a document as unindexed after an interrupted replacement. */
    @Query("UPDATE knowledge_docs SET chunk_count = 0, embedding_model = '' WHERE id = :id")
    suspend fun clearIndexStatus(id: String)

    /**
     * P0-3(informe de auditoría): Si el reemplazo del índice falla, se marcará como "Índice perdido y es necesario reconstruirlo".
     *
     * Ajuste después del fracaso de la implementación anterior [clearIndexStatus] Borre el recuento de fragmentos a cero,embedding_model claro,
     * UI Parece "no indexado" y el usuario no sabe que el documento se indexó originalmente, pero ahora los datos desaparecieron.
     *
     * Aquí, el campo embedding_model se reutiliza para escribir el valor centinela (sin columnas nuevas, migración cero):
     *  UI reconocimiento lateral [INDEX_BROKEN_SENTINEL] Luego se muestra un mensaje procesable que dice "se requiere reindexación".
     */
    @Query("UPDATE knowledge_docs SET chunk_count = 0, embedding_model = :sentinel WHERE id = :id")
    suspend fun markIndexBroken(id: String, sentinel: String = INDEX_BROKEN_SENTINEL)

    @Query("DELETE FROM knowledge_docs WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * v1.133: Eliminar todos los documentos bajo la KB especificada(Los fragmentos asociados se limpian mediante una clave externa AL ELIMINAR CASCADA o la capa de aplicación).
     * Asegúrese de que se hayan eliminado todos los fragmentos relevantes antes de llamar.
     */
    @Query("DELETE FROM knowledge_docs WHERE kb_id = :kbId")
    suspend fun deleteByKb(kbId: String)

    @Query("DELETE FROM knowledge_chunks WHERE doc_id = :docId")
    suspend fun deleteChunksByDoc(docId: String)

    @Transaction
    suspend fun deleteDocWithChunks(id: String) {
        deleteChunksByDoc(id)
        delete(id)
    }

    @Query("SELECT COUNT(*) FROM knowledge_docs")
    suspend fun count(): Int

    /** Cuente el número de documentos visibles para los usuarios(excluir is_internal=true documentación de desarrollo interno). */
    @Query("SELECT COUNT(*) FROM knowledge_docs WHERE is_internal = 0")
    suspend fun countUserVisible(): Int

    /** v1.133: Cuente el número de documentos en KB(Para visualización de lista de KB). */
    @Query("SELECT COUNT(*) FROM knowledge_docs WHERE kb_id = :kbId AND is_internal = 0")
    suspend fun countByKb(kbId: String): Int

    @Query("SELECT * FROM knowledge_docs")
    suspend fun getAll(): List<KnowledgeDocEntity>

    @Query("DELETE FROM knowledge_docs")
    suspend fun deleteAll()

    companion object {
        /** Valor centinela de error de índice (escrito en embedding_model). No puede ser una clave de modelo real y no fallará. */
        const val INDEX_BROKEN_SENTINEL = "__index_broken__"

        fun escapeLikeQuery(query: String): String = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
