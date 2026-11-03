package io.zer0.muse.data.knowledge

import androidx.room.Dao
import androidx.room.Query
import androidx.room.SkipQueryVerification

/**
 * v1.133: knowledge_chunks mesa virtual FTS4 — Utilizado para la búsqueda de texto completo BM25(Una búsqueda híbrida).
 *
 * diseño:
 *  - **No**Registrado como Room @Entity en una lista de entidades @Database.
 *    Razón:Room KSP La base de datos en memoria sqlite-jdbc utilizada para la verificación del esquema en tiempo de compilación es independiente de FTS4 vtable
 *    (Ninguno contentEntity)La construcción CREAR TABLA VIRTUAL informa un error "vtable constructor failed".
 *    Cambiar al modo SQL sin formato completo — por [io.zer0.muse.data.session.MuseDb.MIGRATION_38_39] crear,
 *    DAO Todos los métodos más @SkipQueryVerification,Room La mesa no es consciente de su existencia.
 *  - chunkId / docId como una columna normal(Utilizado por documento/Posicionamiento al eliminar en trozos).
 *  - Buscar usando el operador MATCH,BM25 para clasificar `bm25(knowledge_chunks_fts)` función(SQLite 3.x Incorporado).
 *
 * Estrategia de sincronización(por [io.zer0.muse.rag.RagService] gatillo):
 *  - Nuevo chunk → [insert] Inserción sincrónica
 *  - Eliminar chunk → [deleteByDoc] / [deleteByChunkIds] Eliminación sincrónica
 *  - Índice de reconstrucción → primero [deleteAll] lote de nuevo [insert]
 *  - "upsert" Semántica:FTS rowid Generado automáticamente,REPLACE sin sentido,La persona que llama debe eliminar primero y luego insert
 *
 * R-DB-02: Llamado después de una única operación de escritura no captura dicha tabla [KnowledgeChunkFtsSelfHealer.repair] Autocurate y vuelve a intentarlo.
 * lote [insertAll] Aún dentro de la transacción Habitación,por [io.zer0.muse.rag.RagService] Realizar reintentos de autorreparación fuera de la transacción,
 * Evite DDL y conexiones de transacciones esperando unas a otras.
 */
@Dao
interface KnowledgeChunkFtsDao {

    @SkipQueryVerification
    @Query(
        """
        INSERT INTO knowledge_chunks_fts(chunkId, doc_id, text_content)
        VALUES (:chunkId, :docId, :content)
        """,
    )
    suspend fun insertRaw(chunkId: String, docId: String, content: String)

    @SkipQueryVerification
    @Query("DELETE FROM knowledge_chunks_fts WHERE doc_id = :docId")
    suspend fun deleteByDocRaw(docId: String)

    @SkipQueryVerification
    @Query("DELETE FROM knowledge_chunks_fts WHERE chunkId IN (:chunkIds)")
    suspend fun deleteByChunkIdsRaw(chunkIds: List<String>)

    @SkipQueryVerification
    @Query("DELETE FROM knowledge_chunks_fts")
    suspend fun deleteAllRaw()

    /** Inserto de lote — Utilice transacciones para garantizar la atomicidad. */
    @androidx.room.Transaction
    suspend fun insertAll(rows: List<KnowledgeChunkFtsRow>) {
        for (r in rows) {
            insertRaw(r.chunkId, r.docId, r.content)
        }
    }

    /**
     * Inserto de una sola fila.@Insert Requiere enlace de tipo de entidad,Utilice @Query SQL sin formato en su lugar.
     * OnConflictStrategy.REPLACE Basado en la tabla FTS rowid(Generado automáticamente),
     * En realidad INSERT,No se reemplazará por chunkId. La persona que llama primero debe [deleteByChunkIds] otra vez [insert] Implementar semántica upsert.
     */
    suspend fun insert(chunkId: String, docId: String, content: String) = withFtsSelfHeal { insertRaw(chunkId, docId, content) }

    suspend fun deleteByDoc(docId: String) = withFtsSelfHeal { deleteByDocRaw(docId) }

    suspend fun deleteByChunkIds(chunkIds: List<String>) = withFtsSelfHeal { deleteByChunkIdsRaw(chunkIds) }

    suspend fun deleteAll() = withFtsSelfHeal { deleteAllRaw() }

    /**
     * v1.133: BM25 Búsqueda de texto completo — Devolver el ID de fragmento coincidente y la puntuación BM25(Cuanto más pequeño mejor,SQLite bm25() Devolver valor negativo).
     * La persona que llama debe invertir la puntuación como peso adelantado.
     *
     * query Requiere sintaxis FTS4:Las palabras separadas por espacios son AND,Utilice comillas dobles para frases.
     * Ejemplo:"incumplimiento de contrato" → Contiene ambos"contrato"y"incumplimiento de contrato";"\"Responsabilidad por incumplimiento de contrato\"" → Frase completa.
     *
     * Regresar:List<FTSGolpee el ID del fragmento + la puntuación BM25>
     */
    @SkipQueryVerification
    @Query(
        """
        SELECT chunkId, bm25(knowledge_chunks_fts) AS score
        FROM knowledge_chunks_fts
        WHERE text_content MATCH :query
        ORDER BY score ASC
        LIMIT :limit
        """,
    )
    suspend fun searchBm25(query: String, limit: Int): List<KnowledgeChunkFtsHit>

    /** Search wrapper with the same missing-table self-healing as writes. */
    suspend fun searchBm25Safe(query: String, limit: Int): List<KnowledgeChunkFtsHit> = withFtsSelfHeal { searchBm25(query, limit) }

    private suspend fun <T> withFtsSelfHeal(block: suspend () -> T): T {
        try {
            return block()
        } catch (e: RuntimeException) {
            if (e.message?.contains("no such table: knowledge_chunks_fts") != true) throw e
            KnowledgeChunkFtsSelfHealer.repair()
            return block()
        }
    }
}

/** FTS BM25 Filas de resultados de búsqueda. */
data class KnowledgeChunkFtsHit(
    val chunkId: String,
    /** SQLite bm25() Devolver valor negativo,Cuanto más pequeño, más relevante;La persona que llama toma el inverso como peso delantero. */
    val score: Double,
)

/** FTS Vector ligero para insertar filas.(no entity,solo [KnowledgeChunkFtsDao.insertAll] Para referencia). */
data class KnowledgeChunkFtsRow(
    val chunkId: String,
    val docId: String,
    val content: String,
)
