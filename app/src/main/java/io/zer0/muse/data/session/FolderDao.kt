package io.zer0.muse.data.session

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Phase 9.1 (M13): Objeto de acceso a datos de carpeta.
 */
@Dao
interface FolderDao {

    /** Ver todas las carpetas(Ordenar por índice ascendente). */
    @Query("SELECT * FROM folders ORDER BY sortIndex ASC, createdAt ASC")
    fun observeAll(): Flow<List<FolderEntity>>

    /** Obtener una sola carpeta por id(Desechable). */
    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: String): FolderEntity?

    /** Insertar nueva carpeta(Ignorar en caso de conflicto). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: FolderEntity)

    /** actualizar carpeta(Nombre/ordenar/estado expandido). */
    @Update
    suspend fun update(folder: FolderEntity)

    /**
     * M-SESS8: Carpeta de cambio de nombre atómico(evitar leer-cambiar-Escribir actualizaciones perdidas de simultaneidad).
     * Mantenimiento sincrónico actualizadoEn.
     */
    @Query("UPDATE folders SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun updateName(id: String, name: String, now: Long)

    /** Eliminar carpeta por id(El ID de carpeta de la sesión se borra mediante SessionDao.setFolderId). */
    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Número total de carpetas. */
    @Query("SELECT COUNT(*) FROM folders")
    suspend fun count(): Int

    /** Alternar estado de expansión de carpeta(colapso de la barra lateral/Expandir). */
    @Query("UPDATE folders SET expanded = :expanded WHERE id = :id")
    suspend fun setExpanded(id: String, expanded: Boolean)

    // ── v1.107 Método de mantenimiento de campo redundante ──

    /** v1.107: Incrementa o disminuye atómicamente el recuento de sesiones dentro de una carpeta. */
    @Query("UPDATE folders SET sessionCount = sessionCount + :delta WHERE id = :id")
    suspend fun incrementSessionCount(id: String, delta: Int)

    /** v1.107: Establecer el recuento de sesiones de carpeta directamente(relleno/Para calibración). */
    @Query("UPDATE folders SET sessionCount = :count WHERE id = :id")
    suspend fun setSessionCount(id: String, count: Int)

    @Query("SELECT * FROM folders")
    suspend fun getAll(): List<FolderEntity>

    @Query("DELETE FROM folders")
    suspend fun deleteAll()
}
