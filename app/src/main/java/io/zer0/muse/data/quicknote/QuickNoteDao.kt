package io.zer0.muse.data.quicknote

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * v1.0.17: Registre rápidamente un DAO.
 *
 * Distinga entre registros normales y papelera de reciclaje mediante la marca de eliminado:
 *  - [observeActive]: lista normal(deleted=0)
 *  - [observeTrash]: papelera de reciclaje(deleted=1)
 *
 * Búsqueda de etiquetas:tags Lista de Escrituras [QuickNoteConverters] Serializar en una cadena separada por comas,
 * `tags LIKE '%tag%'` Se pueden acceder a los registros que contengan esta etiqueta.(Granularidad para la coincidencia de subcadenas,aceptable).
 *
 * v1.0.18: Agregar consulta relacionada con la carpeta([observeByFolder] / [observeFolders]).
 */
@Dao
interface QuickNoteDao {
    /** Observe la lista de registros normal.(traer al frente,En segundo lugar, ordene por actualizado en orden descendente). */
    @Query("SELECT * FROM quick_notes WHERE deleted = 0 ORDER BY pinned DESC, updated_at DESC LIMIT :limit")
    fun observeActive(limit: Int = 100): Flow<List<QuickNoteEntity>>

    /**
     * v1.0.18: Ver registros por carpeta(traer al frente,En segundo lugar, ordene por actualizado en orden descendente).
     * para filtrado de carpetas,La carpeta de cadenas vacía devuelve registros sin clasificar.
     */
    @Query("SELECT * FROM quick_notes WHERE deleted = 0 AND folder = :folder ORDER BY pinned DESC, updated_at DESC")
    fun observeByFolder(folder: String): Flow<List<QuickNoteEntity>>

    /**
     * v1.0.18: Observe todas las carpetas que no estén vacías(Eliminar duplicados,para barra lateral/Visualización de la barra de filtro).
     */
    @Query("SELECT DISTINCT folder FROM quick_notes WHERE deleted = 0 AND folder != ''")
    fun observeFolders(): Flow<List<String>>

    /** por palabra clave/Búsqueda de etiquetas(También excluir registros eliminados).keyword/tag Cuando es nulo, no participa en el filtrado. */
    @Query(
        """
        SELECT * FROM quick_notes
        WHERE deleted = 0
          AND (:keyword IS NULL OR title LIKE '%' || :keyword || '%' OR content LIKE '%' || :keyword || '%')
          AND (:tag IS NULL OR tags LIKE '%' || :tag || '%')
        ORDER BY pinned DESC, updated_at DESC
        LIMIT :limit
        """,
    )
    suspend fun search(keyword: String?, tag: String?, limit: Int = 50): List<QuickNoteEntity>

    /** Mira la lista de la papelera de reciclaje(Orden descendente por tiempo de eliminación). */
    @Query("SELECT * FROM quick_notes WHERE deleted = 1 ORDER BY deleted_at DESC")
    fun observeTrash(): Flow<List<QuickNoteEntity>>

    /** Obtener un registro único basado en la identificación(Contiene eliminado). */
    @Query("SELECT * FROM quick_notes WHERE id = :id")
    suspend fun getById(id: String): QuickNoteEntity?

    /** insertar o reemplazar(Utilice la identificación como clave principal). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: QuickNoteEntity)

    /** P0-10: Para copia de seguridad y exportación — lectura completa(Incluye papelera de reciclaje). */
    @Query("SELECT * FROM quick_notes")
    suspend fun getAll(): List<QuickNoteEntity>

    /** P0-10: Para respaldo y recuperación — Borrar todo. */
    @Query("DELETE FROM quick_notes")
    suspend fun deleteAll()

    /** Mover a la papelera de reciclaje(soft delete). */
    @Query("UPDATE quick_notes SET deleted = 1, deleted_at = :now, updated_at = :now WHERE id = :id")
    suspend fun moveToTrash(id: String, now: Long = System.currentTimeMillis())

    /** Restaurar desde la Papelera de reciclaje. */
    @Query("UPDATE quick_notes SET deleted = 0, deleted_at = 0, updated_at = :now WHERE id = :id")
    suspend fun restore(id: String, now: Long = System.currentTimeMillis())

    /** Eliminar permanentemente un solo registro. */
    @Query("DELETE FROM quick_notes WHERE id = :id")
    suspend fun deletePermanent(id: String)

    /** Limpiar la Papelera de reciclaje que tiene más de [before] registros(Para limpieza regular). */
    @Query("DELETE FROM quick_notes WHERE deleted = 1 AND deleted_at < :before")
    suspend fun cleanOldTrash(before: Long)

    /** Alternar estado fijado. */
    @Query("UPDATE quick_notes SET pinned = :pinned, updated_at = :now WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, now: Long = System.currentTimeMillis())

    /**
     * v1.0.18: Configure la carpeta.
     * folder Pasar una cadena vacía significa salir de la carpeta(Sin categoría).
     */
    @Query("UPDATE quick_notes SET folder = :folder, updated_at = :now WHERE id = :id")
    suspend fun setFolder(id: String, folder: String, now: Long = System.currentTimeMillis())

    /**
     * v1.0.18: Establecer tipo de contenido(plain / markdown).
     */
    @Query("UPDATE quick_notes SET content_type = :contentType, updated_at = :now WHERE id = :id")
    suspend fun setContentType(id: String, contentType: String, now: Long = System.currentTimeMillis())

    /**
     * v1.0.18: Establecer hora de recordatorio(0 Indica cancelar el recordatorio.).
     */
    @Query("UPDATE quick_notes SET reminder_at = :reminderAt, updated_at = :now WHERE id = :id")
    suspend fun setReminderAt(id: String, reminderAt: Long, now: Long = System.currentTimeMillis())

    /**
     * v1.0.18: Establezca el estado de cifrado y el texto cifrado.
     * encrypted=false Cuando se cifra contenido, se debe pasar una cadena vacía.
     */
    @Query(
        "UPDATE quick_notes SET encrypted = :encrypted, encrypted_content = :encryptedContent, " +
            "content = CASE WHEN :encrypted THEN '' ELSE :encryptedContent END, " +
            "updated_at = :now WHERE id = :id",
    )
    suspend fun setEncrypted(id: String, encrypted: Boolean, encryptedContent: String, now: Long = System.currentTimeMillis())
}
