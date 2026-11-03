package io.zer0.memory.summary

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Punto de control de contexto DAO.
 *
 * Una sesión puede tener como máximo un punto de control (sobrescritura), por lo que no hay"lista/Paginación"consulta de clase——
 * Al lector siempre sólo le importa"¿Dónde están los límites actuales de esta sesión y cuál es el resumen?".
 */
@Dao
interface ContextCheckpointDao {

    /** escribir/Cubrir el puesto de control. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ContextCheckpointEntity)

    /** Lea el punto de control de una sesión (devuelve nulo si no existe ninguno). */
    @Query("SELECT * FROM context_checkpoints WHERE session_id = :sessionId")
    suspend fun get(sessionId: String): ContextCheckpointEntity?

    /** Eliminar el punto de control de una sesión (llamado cuando se elimina la sesión/se borra el historial/se considera que el punto de control no es válido). */
    @Query("DELETE FROM context_checkpoints WHERE session_id = :sessionId")
    suspend fun deleteById(sessionId: String)

    /** Borrar todo (llamado antes de realizar copias de seguridad y restaurar escrituras de sobrescritura). */
    @Query("DELETE FROM context_checkpoints")
    suspend fun deleteAll()

    /** Para copia de seguridad y exportación. */
    @Query("SELECT * FROM context_checkpoints")
    suspend fun getAll(): List<ContextCheckpointEntity>
}
