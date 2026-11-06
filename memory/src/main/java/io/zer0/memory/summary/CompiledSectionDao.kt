package io.zer0.memory.summary

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CompiledSectionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CompiledSectionEntity)

    @Query("SELECT * FROM compiled_sections WHERE section_key = :key")
    suspend fun get(key: String): CompiledSectionEntity?

    @Query("SELECT * FROM compiled_sections")
    suspend fun getAll(): List<CompiledSectionEntity>

    /**
     * v1.0.53: Cambiar a la semántica UPSERT — La ACTUALIZACIÓN original afecta a 0 filas cuando la tabla está inicialmente vacía,
     * Primera compilación escrita perdida silenciosamente,Hace que las secciones compiladas siempre estén vacías.memory.md Todo vacío.
     * INSERT ... ON CONFLICT DO UPDATE Compatible con la primera escritura y las siguientes.
     */
    @Query(
        "INSERT INTO compiled_sections (section_key, content, fingerprint, updated_at) " +
            "VALUES (:key, :content, :fingerprint, :now) " +
            "ON CONFLICT(section_key) DO UPDATE SET " +
            "content = :content, fingerprint = :fingerprint, updated_at = :now",
    )
    suspend fun updateContent(key: String, content: String, fingerprint: String?, now: String)

    @Query("UPDATE compiled_sections SET content = '', fingerprint = NULL, updated_at = :now")
    suspend fun clearAll(now: String)

    /** P2: Borrar el contenido de la sección especificada.(Se utiliza para recordar la interfaz de usuario de la página para eliminar un segmento único de la capa de compilación.). */
    @Query("UPDATE compiled_sections SET content = '', fingerprint = NULL, updated_at = :now WHERE section_key = :key")
    suspend fun clearByKey(key: String, now: String)

    /** Borrar todo(Se utiliza para realizar copias de seguridad y restaurar,Android 16 prohibido execSQL DML). */
    @Query("DELETE FROM compiled_sections")
    suspend fun deleteAll()
}
