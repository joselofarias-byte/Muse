package io.zer0.muse.data.knowledge

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * v1.133: base de conocimientos(KB)entidad — Admite la vinculación de múltiples bases de conocimientos y asistentes.
 *
 * Una KB contiene varios [KnowledgeDocEntity],Pase [KnowledgeDocEntity.kbId] asociación.
 * Pases de asistente [io.zer0.muse.data.assistant.AssistantEntity.knowledgeBaseIdsJson] Vincular uno o más KB.
 *
 * Predeterminado KB:id="default",name="Base de conocimientos predeterminada".MIGRATION_38_39 se insertará automáticamente.
 */
@Serializable
@Entity(
    tableName = "knowledge_bases",
    indices = [Index(value = ["updated_at"])],
)
data class KnowledgeBaseEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "") val description: String = "",
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    /**
     * Número de documentos bajo esta KB — Calculado en tiempo real por subconsultas durante la lectura.(No más dependencia del mantenimiento de doble escritura,
     * evitar importar/La falta de actualizaciones al eliminar rutas hace que la lista muestre valores antiguos). Escribir columnas solo es compatible con migraciones de bases de datos antiguas.
     */
    @ColumnInfo(name = "doc_count", defaultValue = "0") val docCount: Int = 0,

    // ── P2-2(informe de auditoría): Anular los parámetros de recuperación por KB (null = Heredar global)──
    // La configuración RAG de implementación anterior es una copia global y diferentes bibliotecas no pueden tener diferentes fragmentos/topK/umbrales.
    // Tampoco es posible configurar diferentes incrustaciones/reclasificaciones para diferentes bibliotecas. aquí con**Campos de anulación que admiten valores NULL**darse cuenta,
    // Cuando está vacío, el comportamiento original se conserva por completo y los datos existentes no se ven afectados.
    @ColumnInfo(name = "chunk_size_override") val chunkSizeOverride: Int? = null,
    @ColumnInfo(name = "chunk_overlap_override") val chunkOverlapOverride: Int? = null,
    @ColumnInfo(name = "top_k_override") val topKOverride: Int? = null,
    @ColumnInfo(name = "threshold_override") val thresholdOverride: Float? = null,
    @ColumnInfo(name = "embedding_model_override", defaultValue = "") val embeddingModelOverride: String = "",
)

@Dao
interface KnowledgeBaseDao {
    @Query(
        """
        SELECT id, name, description, created_at, updated_at,
           (SELECT COUNT(*) FROM knowledge_docs d WHERE d.kb_id = knowledge_bases.id AND d.is_internal = 0)
           AS doc_count,
           chunk_size_override, chunk_overlap_override, top_k_override,
           threshold_override, embedding_model_override
        FROM knowledge_bases ORDER BY updated_at DESC
        """,
    )
    fun observeAll(): Flow<List<KnowledgeBaseEntity>>

    @Query(
        """
        SELECT id, name, description, created_at, updated_at,
           (SELECT COUNT(*) FROM knowledge_docs d WHERE d.kb_id = knowledge_bases.id AND d.is_internal = 0)
           AS doc_count,
           chunk_size_override, chunk_overlap_override, top_k_override,
           threshold_override, embedding_model_override
        FROM knowledge_bases ORDER BY updated_at DESC
        """,
    )
    suspend fun getAll(): List<KnowledgeBaseEntity>

    @Query("SELECT * FROM knowledge_bases WHERE id = :id")
    suspend fun getById(id: String): KnowledgeBaseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(kb: KnowledgeBaseEntity)

    @Query("DELETE FROM knowledge_bases WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM knowledge_bases")
    suspend fun count(): Int

    /** P0-10: Para respaldo y recuperación — Borrar todo. */
    @Query("DELETE FROM knowledge_bases")
    suspend fun deleteAll()
}
