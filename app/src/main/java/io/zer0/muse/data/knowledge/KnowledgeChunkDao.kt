package io.zer0.muse.data.knowledge

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.SkipQueryVerification
import androidx.room.Transaction

/**
 * v1.54: La base de conocimientos está fragmentada en Dao.
 *
 * v1.133 Transformación:
 *  - Nuevo [getAllWithEmbeddingBlob] / [getPageWithEmbeddingBlob]:Prioridad BLOB,Sin JSON de reserva de BLOB.
 *  - Nuevo [getByDocIds]:Filtrar por docId(Asistente de enlace KB/@mención para recuperación dirigida).
 *  - Los métodos de búsqueda ya no filtran internal(internal filtrar en [KnowledgeDocDao] Hecho en capas).
 */
@Dao
interface KnowledgeChunkDao {
    /**
     * P3-3: chunk Proyección ligera de metadatos (sin incrustar).
     *
     * `KnowledgeChunkEntity` de `embedding` Si la columna no está vacía, verificar directamente algunas columnas generará un error de verificación de sala;
     * El uso de la clase de proyección solo puede recuperar los campos requeridos, lo que realmente puede reducir el pico de memoria.
     */
    data class ChunkMetaProjection(
        val id: String,
        @androidx.room.ColumnInfo(name = "doc_id") val docId: String,
        val content: String,
        @androidx.room.ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<KnowledgeChunkEntity>)

    @Query("SELECT * FROM knowledge_chunks WHERE doc_id = :docId ORDER BY chunk_index ASC")
    suspend fun getByDoc(docId: String): List<KnowledgeChunkEntity>

    /**
     * v1.133: Cargar todos los fragmentos con incrustaciones generadas(BLOB Prioridad,JSON compatibles).
     * Condiciones:embedding_blob NOT NULL OR embedding != '' AND embedding != '[]'.
     */
    @Query(
        """
        SELECT * FROM knowledge_chunks
        WHERE embedding_blob IS NOT NULL
           OR (embedding != '' AND embedding != '[]')
        """,
    )
    suspend fun getAllWithEmbedding(): List<KnowledgeChunkEntity>

    /** v1.133 Alias:Misma semántica [getAllWithEmbedding],Utilice nombres más específicos para el código nuevo. */
    suspend fun getAllWithEmbeddingBlob(): List<KnowledgeChunkEntity> = getAllWithEmbedding()

    /**
     * v1.133: Fragmentos indexados filtrados por docIds(Asistente de enlace KB/@mención para recuperación dirigida).
     * docIds Devuelve una lista vacía si está vacía(Evite escanear por error toda la tabla).
     */
    @Query(
        """
        SELECT * FROM knowledge_chunks
        WHERE doc_id IN (:docIds)
          AND (embedding_blob IS NOT NULL OR (embedding != '' AND embedding != '[]'))
        ORDER BY created_at ASC
        """,
    )
    suspend fun getByDocIds(docIds: List<String>): List<KnowledgeChunkEntity>

    /**
     * P2-33: Paginar por docIds para recuperar fragmentos indexados(Para búsqueda dirigida).
     *
     * con [getByDocIds] Mismo filtro y clasificación(`created_at ASC`),pero impuesto por SQL LIMIT/OFFSET:
     * búsqueda dirigida(@mention / Encuadernación auxiliar KB)Extraer por página de la base de datos,Evite fragmentar todo el rango de búsqueda
     * Cargar la memoria una vez(antigua implementación `getByDocIds(...).drop().take()` amplificación de la memoria).
     */
    @Query(
        """
        SELECT * FROM knowledge_chunks
        WHERE doc_id IN (:docIds)
          AND (embedding_blob IS NOT NULL OR (embedding != '' AND embedding != '[]'))
        ORDER BY created_at ASC LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun getPageByDocIds(docIds: List<String>, limit: Int, offset: Int): List<KnowledgeChunkEntity>

    /**
     * P2-31: Obtener fragmentos en lotes por fragmentId(Complemente los metadatos de contenido de las visitas exclusivas de BM25 durante la recuperación híbrida).
     * ids Devuelve una lista vacía si está vacía. No requerido embedding(Sólo éxitos de FTS,Puede que no haya ningún vector).
     */
    @Query("SELECT * FROM knowledge_chunks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<KnowledgeChunkEntity>

    /**
     * M-KC1: Carga de página de fragmentos con incrustación generada(Para datos a gran escala).
     */
    @Query(
        """
        SELECT * FROM knowledge_chunks
        WHERE embedding_blob IS NOT NULL OR (embedding != '' AND embedding != '[]')
        ORDER BY created_at ASC LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun getPageWithEmbedding(limit: Int, offset: Int): List<KnowledgeChunkEntity>

    /**
     * B4-03: Consulta paginada con filtrado de metadatos.(docIds/tag/El rango de tiempo se reduce a SQLite).
     * docIdsJson Sin filtrado cuando la matriz está vacía;tag Sin filtrado cuando está vacío;start/endTime Cuando es 0, no se realiza ningún filtrado.
     */
    @SkipQueryVerification
    @Query(
        """
        SELECT * FROM knowledge_chunks
        WHERE (embedding_blob IS NOT NULL OR (embedding != '' AND embedding != '[]'))
          AND (:docIdsJson = '' OR doc_id IN (SELECT value FROM json_each(:docIdsJson)))
          AND (:tag = '' OR metadata_json LIKE '%' || :tag || '%')
          AND (:startTime = 0 OR created_at >= :startTime)
          AND (:endTime = 0 OR created_at <= :endTime)
        ORDER BY created_at ASC LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun getFilteredPageWithEmbedding(
        limit: Int,
        offset: Int,
        docIdsJson: String,
        tag: String,
        startTime: Long,
        endTime: Long,
    ): List<KnowledgeChunkEntity>

    /** v1.133 Alias:Misma semántica [getPageWithEmbedding]. */
    suspend fun getPageWithEmbeddingBlob(limit: Int, offset: Int): List<KnowledgeChunkEntity> = getPageWithEmbedding(limit, offset)

    /** Elimine todos los fragmentos por ID de documento. */
    @Query("DELETE FROM knowledge_chunks WHERE doc_id = :docId")
    suspend fun deleteByDoc(docId: String)

    @Transaction
    suspend fun reindex(docId: String, chunks: List<KnowledgeChunkEntity>) {
        deleteByDoc(docId)
        insertAll(chunks)
    }

    /** v1.133: Reindexación por lotes(Reindexación por lotes para trabajadores). */
    @Transaction
    suspend fun reindexBatch(chunksByDoc: Map<String, List<KnowledgeChunkEntity>>) {
        chunksByDoc.forEach { (docId, chunks) ->
            deleteByDoc(docId)
            insertAll(chunks)
        }
    }

    @Query(
        """
        SELECT COUNT(*) FROM knowledge_chunks
        WHERE embedding_blob IS NOT NULL OR (embedding != '' AND embedding != '[]')
        """,
    )
    suspend fun countIndexed(): Int

    @Query("SELECT COUNT(*) FROM knowledge_chunks WHERE doc_id = :docId")
    suspend fun countByDoc(docId: String): Int

    /** Proyección de números de fragmentos agregados por base de conocimientos. */
    data class ChunkCountByKb(
        @androidx.room.ColumnInfo(name = "kb_id") val kbId: String,
        @androidx.room.ColumnInfo(name = "chunk_count") val chunkCount: Int,
    )

    /**
     * Calcular el número de bloques según la base de conocimientos.(Excluir documentos internos).
     *
     * Lo que quiere la descripción general de la base de conocimientos es "¿Cuántas piezas tiene realmente esta biblioteca ahora?" —— en la línea del documento `chunk_count` Se registra al escribir.,
     * El número de bloques puede ser inconsistente con el número real después de que la reconstrucción falla o se interrumpe a mitad de camino.,Entonces cuenta las filas físicas. Las bibliotecas sin bloques no aparecen en los resultados,
     * La persona que llama se trata como 0.
     */
    @Query(
        """
        SELECT d.kb_id AS kb_id, COUNT(c.id) AS chunk_count
        FROM knowledge_docs d
        LEFT JOIN knowledge_chunks c ON c.doc_id = d.id
        WHERE d.is_internal = 0
        GROUP BY d.kb_id
        """,
    )
    suspend fun countByKb(): List<ChunkCountByKb>

    /**
     * P1-5(informe de auditoría): Estadísticas**no pertenece**El número de fragmentos de una colección de documentos determinada.
     *
     * Para calcular un valor booleano de "si todos los documentos indexados están cubiertos", la implementación anterior llamaba `getAllWithEmbedding()`
     * Lea el fragmento completo de la biblioteca junto con el BLOB incorporado en la memoria a la vez (1024 dimensión ≈ 4KB/bloque,
     * 1 diez mil yuanes ≈ 40MB+), las máquinas de gama baja son propensas a OOM.
     *
     * Esta consulta devuelve el recuento directamente en la capa SQL.0 Es decir, "todo dentro del alcance" y cero sobrecarga de memoria.
     */
    @Query("SELECT COUNT(*) FROM knowledge_chunks WHERE doc_id NOT IN (:docIds)")
    suspend fun countOutsideDocs(docIds: List<String>): Int

    /**
     * P3-3(informe de auditoría): Lea el fragmento completo en las páginas (solo campos de metadatos, excluyendo la incrustación).
     *
     * Se utiliza para escenarios que requieren un recorrido completo pero no requieren vectores (como la reconstrucción de chunkMetaCache al inicio).
     * Para implementación anterior `getAllWithEmbedding()` Recuperar todas las filas + incrustar BLOB a la vez,
     * La memoria máxima es igual al tamaño total de la base de datos; después de la paginación, el valor máximo cae a una sola página.
     *
     * El orden ascendente por ID de fila garantiza una paginación estable (no depende del orden de la clave principal).
     */
    @Query(
        "SELECT id, doc_id, content, chunk_index FROM knowledge_chunks ORDER BY rowid LIMIT :limit OFFSET :offset",
    )
    suspend fun getPageWithoutEmbedding(limit: Int, offset: Int): List<ChunkMetaProjection>

    /**
     * v1.133: Obtenga la dimensión de incrustación del primer fragmento indexado en la biblioteca(BLOB longitud / 4).
     * usado para [io.zer0.muse.rag.RagService.indexDocument] Comprobación de coherencia de dimensiones —
     * Evitar que los usuarios cambien de modelo integrado después,Las dimensiones del nuevo documento no son coherentes con las del documento antiguo, lo que provoca que falle toda recuperación de vectores.
     *
     * Devolver nulo significa que no hay datos BLOB en la biblioteca(Todos los datos JSON antiguos o biblioteca vacía),La persona que llama debe omitir la verificación.
     */
    @Query(
        """
        SELECT LENGTH(embedding_blob) / 4 FROM knowledge_chunks
        WHERE embedding_blob IS NOT NULL
        LIMIT 1
        """,
    )
    suspend fun getFirstIndexedEmbeddingDim(): Int?

    @Query("DELETE FROM knowledge_chunks")
    suspend fun deleteAll()

    /** v1.133: Eliminación por lotes por docIds en KB(Se utiliza al eliminar KB). */
    @Query("DELETE FROM knowledge_chunks WHERE doc_id IN (:docIds)")
    suspend fun deleteByDocIds(docIds: List<String>)

    @Query(
        "SELECT id, doc_id, content, '' AS embedding, NULL AS embedding_blob, chunk_index, " +
            "token_count, metadata_json, created_at FROM knowledge_chunks",
    )
    suspend fun getAll(): List<KnowledgeChunkEntity>
}
