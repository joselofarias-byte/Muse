package io.zer0.memory.summary

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SessionSummaryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SessionSummaryEntity)

    @Query("SELECT * FROM session_summaries WHERE session_id = :sessionId")
    suspend fun get(sessionId: String): SessionSummaryEntity?

    @Query("SELECT * FROM session_summaries WHERE summary != '' ORDER BY updated_at DESC")
    suspend fun getAll(): List<SessionSummaryEntity>

    @Query(
        """
        SELECT * FROM session_summaries
        WHERE summary != ''
          AND updated_at >= :startISO AND updated_at <= :endISO
          AND (:since IS NULL OR updated_at > :since)
          AND (
            :assistantId IS NULL OR
            assistant_id = '' OR assistant_id IS NULL OR assistant_id = :assistantId
          )
          AND (
            :spaceId IS NULL OR
            space_id = '' OR space_id IS NULL OR space_id = :spaceId
          )
        ORDER BY updated_at DESC
    """,
    )
    suspend fun getInRange(
        startISO: String,
        endISO: String,
        since: String?,
        assistantId: String? = null,
        spaceId: String? = null,
    ): List<SessionSummaryEntity>

    /** Obtener todo"sucio" session(summary !== snapshot). */
    @Query("SELECT * FROM session_summaries WHERE summary != '' AND summary != snapshot")
    suspend fun getDirty(): List<SessionSummaryEntity>

    @Query("UPDATE session_summaries SET snapshot = summary, snapshot_at = :now WHERE session_id = :sessionId")
    suspend fun markProcessed(sessionId: String, now: String)

    @Query("DELETE FROM session_summaries")
    suspend fun deleteAll()

    /** P2: Eliminar el resumen de la sesión especificada(Se utiliza para recordar la interfaz de usuario de la página para eliminar entradas de la capa de resumen). */
    @Query("DELETE FROM session_summaries WHERE session_id = :sessionId")
    suspend fun deleteById(sessionId: String)

    /**
     * Cuando se elimina un asistente, se borran todos los resúmenes de sesiones bajo su nombre.
     *
     * La línea de sesión en sí no se elimina aquí. —— Se conservan las conversaciones que ya pertenecen al asistente.,Simplemente suelta la encuadernación.
     *
     * @return número real de filas eliminadas
     */
    @Query("DELETE FROM session_summaries WHERE assistant_id = :assistantId")
    suspend fun deleteByAssistant(assistantId: String): Int
}
