package io.zer0.memory.space

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * v1.0.52 P2-2: Objeto de acceso a datos del espacio de memoria.
 *
 * Consulta de lista CRUD + que proporciona espacio de memoria(Estadísticas con hechos).
 * con [io.zer0.memory.fact.FactDao] cooperar,Implemente el aislamiento de la tabla de hechos mediante space_id.
 */
@Dao
interface MemorySpaceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(space: MemorySpaceEntity): Long

    /**
     * enumerar todo Space,Ordenar por índice de clasificación ascendente.
     * No incluye cantidad real(consulta ligera,para el menú desplegable del conmutador).
     */
    @Query("SELECT * FROM memory_spaces ORDER BY sort_index ASC, created_at ASC")
    suspend fun listAll(): List<MemorySpaceEntity>

    /**
     * Observar la lista de espacios(Flow forma,UI Actualización en tiempo real).
     */
    @Query("SELECT * FROM memory_spaces ORDER BY sort_index ASC, created_at ASC")
    fun observeAll(): Flow<List<MemorySpaceEntity>>

    /**
     * Listar todos los espacios + número de hechos asociados(LEFT JOIN COUNT).
     * Se utiliza para mostrar las estadísticas objetivas de cada Espacio en la página de administración.
     */
    @Query(
        """
        SELECT s.id AS id, s.name AS name, s.icon AS icon, s.description AS description,
               s.created_at AS created_at, s.sort_index AS sort_index,
               COUNT(f.id) AS fact_count
        FROM memory_spaces s
        LEFT JOIN facts f ON f.space_id = s.id
        GROUP BY s.id
        ORDER BY s.sort_index ASC, s.created_at ASC
    """,
    )
    suspend fun listAllWithCount(): List<MemorySpaceWithCount>

    /**
     * Lista de espacios de observación + número de hechos(Flow forma).
     */
    @Query(
        """
        SELECT s.id AS id, s.name AS name, s.icon AS icon, s.description AS description,
               s.created_at AS created_at, s.sort_index AS sort_index,
               COUNT(f.id) AS fact_count
        FROM memory_spaces s
        LEFT JOIN facts f ON f.space_id = s.id
        GROUP BY s.id
        ORDER BY s.sort_index ASC, s.created_at ASC
    """,
    )
    fun observeAllWithCount(): Flow<List<MemorySpaceWithCount>>

    @Query("SELECT * FROM memory_spaces WHERE id = :id")
    suspend fun getById(id: String): MemorySpaceEntity?

    @Query("DELETE FROM memory_spaces WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT COUNT(*) FROM memory_spaces")
    suspend fun count(): Int

    /**
     * Cambiar nombre Space(solo cambio name,Mantener otros campos).
     */
    @Query("UPDATE memory_spaces SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String): Int

    /**
     * Actualizar número de clasificación de espacio(Para secuencia de ajuste de lotes).
     */
    @Query("UPDATE memory_spaces SET sort_index = :sortIndex WHERE id = :id")
    suspend fun updateSortIndex(id: String, sortIndex: Int): Int

    /**
     * Cuente el número de hechos en el espacio especificado.
     * Mensaje de confirmación antes de eliminar el espacio.
     */
    @Query("SELECT COUNT(*) FROM facts WHERE space_id = :spaceId")
    suspend fun countFactsInSpace(spaceId: String): Int

    /**
     * Migra todos los datos del Espacio especificado a otro Espacio.
     * Se utiliza para eliminar la migración de hechos antes de Space.
     */
    @Query("UPDATE facts SET space_id = :targetSpaceId WHERE space_id = :sourceSpaceId")
    suspend fun migrateFacts(sourceSpaceId: String, targetSpaceId: String): Int
}
