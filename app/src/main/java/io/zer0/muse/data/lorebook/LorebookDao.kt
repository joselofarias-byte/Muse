package io.zer0.muse.data.lorebook

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Lorebook DAO — Phase 8.5.
 * - observeAll: todos los libros del mundo(Para la página de gestión,Ordenar por sortIndex + nombre)
 * - observeEnabled: Sólo habilitado(Se utiliza al inyectar)
 * - getByIds: Buscar por lista de identificación(Assistant atado)
 */
@Dao
interface LorebookDao {
    @Query("SELECT * FROM lorebooks ORDER BY priority DESC, name ASC")
    fun observeAll(): Flow<List<LorebookEntity>>

    @Query("SELECT * FROM lorebooks WHERE enabled = 1 ORDER BY priority DESC, name ASC")
    fun observeEnabled(): Flow<List<LorebookEntity>>

    @Query("SELECT * FROM lorebooks WHERE id IN (:ids) AND enabled = 1 ORDER BY priority DESC, name ASC")
    suspend fun getByIdsEnabled(ids: List<String>): List<LorebookEntity>

    @Query("SELECT * FROM lorebooks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): LorebookEntity?

    @Upsert
    suspend fun upsert(entity: LorebookEntity)

    @Delete
    suspend fun delete(entity: LorebookEntity)

    @Query("DELETE FROM lorebooks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<LorebookEntity>)

    @Query("SELECT * FROM lorebooks")
    suspend fun getAll(): List<LorebookEntity>

    @Query("DELETE FROM lorebooks")
    suspend fun deleteAll()
}
