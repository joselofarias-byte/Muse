package io.zer0.muse.data.audit

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/**
 * P2-4 Registro de auditoría DAO.
 *
 * - [insert]: Escribir un registro de auditoría
 * - [query]: Consulta por página en orden cronológico inverso
 * - [count]: Número total de artículos(para comprobar el búfer circular)
 * - [deleteOldest]: Eliminar los n elementos más antiguos(Se llama cuando el búfer circular excede el límite.)
 * - [deleteOlderThan]: Eliminar registros antes de la marca de tiempo especificada(Limpieza por período de retención al inicio)
 * - [deleteAll]: Borrar todos los registros(El usuario hace clic en la página de registro de auditoría."Borrar"llamado cuando)
 * - [insertWithCleanup]: átomo insert + count + trim,Evite las ventanas de carrera
 */
@Dao
interface AuditLogDao {

    @Insert
    suspend fun insert(log: AuditLogEntity): Long

    @Query("SELECT * FROM audit_log ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun query(limit: Int, offset: Int): List<AuditLogEntity>

    @Query("SELECT COUNT(*) FROM audit_log")
    suspend fun count(): Int

    @Query("DELETE FROM audit_log WHERE id IN (SELECT id FROM audit_log ORDER BY timestamp ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int)

    @Query("DELETE FROM audit_log WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM audit_log")
    suspend fun deleteAll()

    /** M-DB-4: átomo insert + count + trim — Evite correr ventanas entre el conteo y la inserción durante la escritura simultánea. */
    @Transaction
    suspend fun insertWithCleanup(
        timestamp: Long,
        category: String,
        action: String,
        target: String = "",
        detail: String = "",
        success: Boolean = true,
        maxCount: Int,
        trimBatch: Int,
    ) {
        insert(
            AuditLogEntity(
                timestamp = timestamp,
                category = category,
                action = action,
                target = target,
                detail = detail,
                success = success,
            ),
        )
        val total = count()
        if (total > maxCount) {
            deleteOldest(trimBatch)
        }
    }
}
