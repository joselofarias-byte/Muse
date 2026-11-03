package io.zer0.muse.data.assistant

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Assistant objeto de acceso a datos(Phase 8.2).
 */
@Dao
interface AssistantDao {

    @Upsert
    suspend fun upsert(assistant: AssistantEntity)

    /**
     * M-AST1: INSERT OR IGNORE — usado para [AssistantRepository.ensureDefaultExists] Inserción atómica de,
     * Evite verificar y luego insertar la condición de carrera TOCTOU. Ignorar silenciosamente los conflictos(Regresar rowId=-1).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(assistant: AssistantEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(assistants: List<AssistantEntity>)

    @Query("SELECT * FROM assistants ORDER BY sortIndex ASC, createdAt ASC")
    fun observeAll(): Flow<List<AssistantEntity>>

    @Query("SELECT * FROM assistants ORDER BY sortIndex ASC, createdAt ASC")
    suspend fun getAll(): List<AssistantEntity>

    @Query("SELECT * FROM assistants WHERE id = :id")
    suspend fun getById(id: String): AssistantEntity?

    @Query("SELECT COUNT(*) FROM assistants")
    suspend fun count(): Int

    @Query("DELETE FROM assistants WHERE id = :id")
    suspend fun deleteById(id: String)

    @Delete
    suspend fun delete(assistant: AssistantEntity)

    @Query("DELETE FROM assistants")
    suspend fun deleteAll()

    // ── v1.107 Método de mantenimiento de campo redundante ──

    /** v1.107: Aumenta y disminuye atómicamente el recuento de mensajes del Asistente. */
    @Query("UPDATE assistants SET messageCount = messageCount + :delta WHERE id = :id")
    suspend fun incrementMessageCount(id: String, delta: Int)

    /** v1.107: Actualice la última vez que se utilizó el Asistente. */
    @Query("UPDATE assistants SET updatedAt = :timestamp WHERE id = :id")
    suspend fun updateLastUsedAt(id: String, timestamp: Long)

    /** U-26: habilitar/Desactiva el asistente. */
    @Query("UPDATE assistants SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
}
