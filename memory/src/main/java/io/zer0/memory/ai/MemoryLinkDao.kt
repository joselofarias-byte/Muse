package io.zer0.memory.ai

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * v1.0.52 P2-3: Borde del gráfico de conocimiento de la memoria DAO.
 *
 * Proporciona consultas CRUD + aisladas por espacio/alcance para relaciones memorizadas.
 * con [io.zer0.memory.fact.FactDao] cooperar,pasado cuando se eliminó el hecho [deleteByFactId] Limpia el lado huérfano.
 */
@Dao
interface MemoryLinkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(link: MemoryLinkEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(links: List<MemoryLinkEntity>): List<Long>

    /**
     * Consulta todas las relaciones dentro del Espacio + Alcance especificado.
     * Se utiliza para la visualización de gráficos de conocimiento./Cargue la colección de bordes al recuperar.
     */
    @Query(
        """
        SELECT * FROM memory_links
        WHERE space_id = :spaceId AND scope = :scope
        ORDER BY weight DESC, created_at DESC
        """,
    )
    suspend fun listBySpaceAndScope(spaceId: String, scope: String): List<MemoryLinkEntity>

    /** Consultar la relación entre todos los ámbitos en el espacio especificado, utilizado en el centro de memoria.“Todos los alcances”vista. */
    @Query(
        """
        SELECT * FROM memory_links
        WHERE space_id = :spaceId
        ORDER BY weight DESC, created_at DESC
        """,
    )
    suspend fun listBySpace(spaceId: String): List<MemoryLinkEntity>

    /**
     * Observe la lista de relaciones del Espacio + Alcance especificado(Flow forma,UI Actualización en tiempo real).
     */
    @Query(
        """
        SELECT * FROM memory_links
        WHERE space_id = :spaceId AND scope = :scope
        ORDER BY weight DESC, created_at DESC
        """,
    )
    fun observeBySpaceAndScope(spaceId: String, scope: String): Flow<List<MemoryLinkEntity>>

    /**
     * Consultar todos los bordes relacionados con el hecho especificado.(como origen o destino).
     * Memoria asociativa para visualizar un hecho.
     */
    @Query(
        """
        SELECT * FROM memory_links
        WHERE source_fact_id = :factId OR target_fact_id = :factId
        ORDER BY weight DESC
        """,
    )
    suspend fun listByFactId(factId: Long): List<MemoryLinkEntity>

    /**
     * Eliminar todos los bordes relacionados con el hecho especificado.(Se llama cuando se elimina un hecho.,Limpiar el lado huérfano).
     */
    @Query("DELETE FROM memory_links WHERE source_fact_id = :factId OR target_fact_id = :factId")
    suspend fun deleteByFactId(factId: Long): Int

    /**
     * Eliminar todos los bordes dentro del espacio especificado(Space Llamado al eliminar,Cooperar con la migración de hechos).
     * Nota:Space Los datos se migran a la configuración predeterminada cuando se eliminan Space,Los bordes también deberían migrarse en lugar de eliminarse,
     * Este método sólo se utiliza para forzar la limpieza de escenas espaciales.
     */
    @Query("DELETE FROM memory_links WHERE space_id = :spaceId")
    suspend fun deleteBySpaceId(spaceId: String): Int

    /**
     * Migrar bordes dentro del espacio especificado al destino Space(con [MemorySpaceDao.migrateFacts] cooperar).
     */
    @Query("UPDATE memory_links SET space_id = :targetSpaceId WHERE space_id = :sourceSpaceId")
    suspend fun migrateBySpaceId(sourceSpaceId: String, targetSpaceId: String): Int

    /** Cuente el número de aristas dentro del Espacio + Alcance especificado. */
    @Query("SELECT COUNT(*) FROM memory_links WHERE space_id = :spaceId AND scope = :scope")
    suspend fun countBySpaceAndScope(spaceId: String, scope: String): Int

    /** Eliminar un solo borde por id. */
    @Query("DELETE FROM memory_links WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    /** Limpiar todos los bordes(Sólo para pruebas/restablecer). */
    @Query("DELETE FROM memory_links")
    suspend fun deleteAll(): Int

    /**
     * v2.6.2(auditoría de memoria): Limpiar el lado huérfano — fuente/Bordes donde el hecho objetivo ya no existe.
     *
     * para la eliminación de la descomposición por lotes (applyDecay) y otros escenarios: DELETE por lotes no devuelve la identificación eliminada,
     * No es posible limpiar los bordes relacionados uno por uno, por lo que los huérfanos se limpian juntos después de la eliminación.
     * facts Cuando la tabla está vacía, la subconsulta es el conjunto vacío.NOT IN Siempre es cierto, borra exactamente todos los bordes (semánticamente correcto).
     */
    @Query(
        "DELETE FROM memory_links WHERE source_fact_id NOT IN (SELECT id FROM facts) OR target_fact_id NOT IN (SELECT id FROM facts)",
    )
    suspend fun deleteOrphans(): Int
}
