package io.zer0.muse.worldbook

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * P1-2: Worldbook DAO.
 *
 * La clasificación de consultas está unificada como priority DESC, name ASC(Consistente con LorebookDao).
 */
@Dao
interface WorldBookDao {
    @Query("SELECT * FROM worldbook_entries ORDER BY priority DESC, name ASC")
    fun observeAll(): Flow<List<WorldBookEntryEntity>>

    @Query("SELECT * FROM worldbook_entries WHERE enabled = 1 ORDER BY priority DESC, name ASC")
    fun observeEnabled(): Flow<List<WorldBookEntryEntity>>

    /** Obtener todas las entradas habilitadas(Para inyector,Contiene siempre activo y activador de palabra clave.). */
    @Query("SELECT * FROM worldbook_entries WHERE enabled = 1 ORDER BY priority DESC, name ASC")
    suspend fun getEnabled(): List<WorldBookEntryEntity>

    /** Obtenga entrada de activación para residentes(alwaysActive=1 y enabled=1),Filtrar por ID de asistente(null = situación general). */
    @Query(
        """
        SELECT * FROM worldbook_entries
        WHERE enabled = 1 AND alwaysActive = 1
          AND (assistantId IS NULL OR assistantId = :assistantId)
        ORDER BY priority DESC, name ASC
        """,
    )
    suspend fun getAlwaysActive(assistantId: String?): List<WorldBookEntryEntity>

    @Query("SELECT * FROM worldbook_entries WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): WorldBookEntryEntity?

    @Upsert
    suspend fun upsert(entity: WorldBookEntryEntity)

    @Delete
    suspend fun delete(entity: WorldBookEntryEntity)

    @Query("DELETE FROM worldbook_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<WorldBookEntryEntity>)

    @Query("SELECT * FROM worldbook_entries")
    suspend fun getAll(): List<WorldBookEntryEntity>

    @Query("DELETE FROM worldbook_entries")
    suspend fun deleteAll()
}
