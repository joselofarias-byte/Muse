package io.zer0.muse.data.patrol

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.serialization.Serializable

/**
 * v1.0.74: Registro de inspección activo — Registre lo que se hizo durante cada inspección./No se hizo mucho.
 * AI Podrás verlo durante la próxima inspección.,Evite hacer lo mismo dos veces.
 */
@Serializable
@Entity(tableName = "patrol_logs")
data class PatrolLogEntity(
    @PrimaryKey val id: String,
    /** Marca de tiempo de inspección. */
    val timestamp: Long,
    /** Resultados de la inspección: wrote_diary / organized_memories / sent_message / idle(No pasa nada) / error. */
    val action: String,
    /** que hizo(Resumen,AI legible). */
    val summary: String,
)

/** v1.0.74: Registro de inspección DAO. */
@Dao
interface PatrolLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: PatrolLogEntity)

    @Query("SELECT * FROM patrol_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<PatrolLogEntity>

    @Query("DELETE FROM patrol_logs WHERE timestamp < :before")
    suspend fun deleteBefore(before: Long)

    /** Si una determinada acción se ha realizado hoy(Anti-duplicación). */
    @Query("SELECT COUNT(*) FROM patrol_logs WHERE action = :action AND timestamp >= :dayStart")
    suspend fun countActionToday(action: String, dayStart: Long): Int
}
