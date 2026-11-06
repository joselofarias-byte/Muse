package io.zer0.memory.fact

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** v13 (T4-1): Registros de revisión de hechos DAO. */
@Dao
interface FactRevisionDao {

    @Insert
    suspend fun insert(entity: FactRevisionEntity): Long

    /** Historial de revisión de consultas por ID de hecho(nuevo→viejo). */
    @Query("SELECT * FROM fact_revisions WHERE fact_id = :factId ORDER BY changed_at DESC, id DESC LIMIT :limit")
    suspend fun getByFactId(factId: Long, limit: Int = 20): List<FactRevisionEntity>

    /** Limpiar todas las revisiones de un hecho específico(Cuando se borra el hecho). */
    @Query("DELETE FROM fact_revisions WHERE fact_id = :factId")
    suspend fun deleteByFactId(factId: Long): Int

    /** v2.6.2(auditoría de memoria): Borre todas las revisiones (cuando se restablece la memoria). */
    @Query("DELETE FROM fact_revisions")
    suspend fun deleteAll()

    /**
     * v2.6.2(auditoría de memoria): Limpiar revisiones huérfanas — Una enmienda correspondiente al hecho de que ya no existe.
     * para la eliminación de la descomposición por lotes (applyDecay) y luego límpielos.
     */
    @Query("DELETE FROM fact_revisions WHERE fact_id NOT IN (SELECT id FROM facts)")
    suspend fun deleteOrphans(): Int

    /** total(Monitoreo anti-expansión). */
    @Query("SELECT COUNT(*) FROM fact_revisions")
    suspend fun count(): Int
}
