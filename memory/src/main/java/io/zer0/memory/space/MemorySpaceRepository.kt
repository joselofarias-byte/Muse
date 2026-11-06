package io.zer0.memory.space

import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

/**
 * v1.0.52 P2-2: almacén de espacio de memoria — Space CRUD + migración de hechos.
 *
 * Responsabilidades:
 *  - lista/crear/Cambiar nombre/Eliminar Space
 *  - Migrar hechos a los valores predeterminados al eliminar Espacio Space(Evite la pérdida de datos)
 *  - Ajustar la clasificación del espacio
 *
 * con [io.zer0.memory.fact.FactStore] relación:
 *  - FactStore Aislar tabla de hechos por spaceId
 *  - Este repositorio gestiona los metadatos del espacio por sí mismo.
 *  - Aprobado al eliminar un espacio [MemorySpaceDao.migrateFacts] transferir los hechos de regreso default
 *
 * Predeterminado Space("default") No se puede eliminar,Asegúrese de que los usuarios siempre tengan un espacio para escribir.
 */
class MemorySpaceRepository(
    private val spaceDao: MemorySpaceDao,
    /**
     * v2.6.2(auditoría de memoria): Borde del gráfico de conocimiento DAO.
     *
     * Al eliminar el espacio, los hechos se volverán a migrar a los valores predeterminados y los bordes del gráfico también se deben migrar simultáneamente; de ​​lo contrario, los bordes quedarán huérfanos.
     * (Señalando el hecho por defecto, pero marcado debajo del Espacio eliminado).
     */
    private val memoryLinkDao: io.zer0.memory.ai.MemoryLinkDao? = null,
) {

    /**
     * Observar la lista de espacios(Contiene el número de hechos.,Flow forma).
     * UI Utilice este flujo para actualizar la lista de espacios y las estadísticas en tiempo real.
     */
    fun observeSpacesWithCount(): Flow<List<MemorySpaceWithCount>> = spaceDao.observeAllWithCount()

    /**
     * Observar la lista de espacios(No incluye cantidad real,ligero).
     * para el menú desplegable del conmutador,Evite hacer LEFT JOIN COUNT cada vez que cambie.
     */
    fun observeSpaces(): Flow<List<MemorySpaceEntity>> = spaceDao.observeAll()

    /** enumerar todo Space(Contiene el número de hechos.). */
    suspend fun listSpacesWithCount(): List<MemorySpaceWithCount> = withContext(Dispatchers.IO) {
        spaceDao.listAllWithCount()
    }

    /** enumerar todo Space(ligero,No incluye cantidad real). */
    suspend fun listSpaces(): List<MemorySpaceEntity> = withContext(Dispatchers.IO) {
        spaceDao.listAll()
    }

    /** Espacio de consulta por id. */
    suspend fun getSpace(id: String): MemorySpaceEntity? = withContext(Dispatchers.IO) {
        spaceDao.getById(id)
    }

    /**
     * Crea un nuevo espacio.
     *
     * @param name nombre para mostrar(Requerido,trim La espalda no está vacía.)
     * @param icon logotipo del icono(Opcional)
     * @param description Descripción(Opcional)
     * @return Crea un nuevo espacio id(Retorno del fracaso null)
     */
    suspend fun createSpace(name: String, icon: String? = null, description: String = ""): String? = withContext(Dispatchers.IO) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            Logger.w("MemorySpaceRepository", "createSpace: name is empty")
            return@withContext null
        }
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val maxSortIndex = spaceDao.listAll().maxOfOrNull { it.sortIndex } ?: 0
        val space = MemorySpaceEntity(
            id = id,
            name = trimmedName,
            icon = icon,
            description = description.trim(),
            createdAt = now,
            sortIndex = maxSortIndex + 1,
        )
        spaceDao.upsert(space)
        Logger.i("MemorySpaceRepository", "Created space: id=$id name=$trimmedName")
        id
    }

    /**
     * Cambiar el nombre del espacio.
     * @return ¿Tiene éxito?(name No está vacío y el espacio existe.)
     */
    suspend fun renameSpace(id: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withContext false
        spaceDao.rename(id, trimmed) > 0
    }

    /**
     * Descripción del espacio actualizada.
     */
    suspend fun updateDescription(id: String, description: String): Boolean = withContext(Dispatchers.IO) {
        val existing = spaceDao.getById(id) ?: return@withContext false
        spaceDao.upsert(existing.copy(description = description.trim())) > 0
    }

    /**
     * Icono de espacio actualizado.
     */
    suspend fun updateIcon(id: String, icon: String?): Boolean = withContext(Dispatchers.IO) {
        val existing = spaceDao.getById(id) ?: return@withContext false
        spaceDao.upsert(existing.copy(icon = icon)) > 0
    }

    /**
     * Eliminar espacio.
     *
     * comportamiento:
     *  1. Predeterminado Space("default") No se puede eliminar,Regresar false
     *  2. Migrar todos los datos en Space a los valores predeterminados antes de eliminarlos Space,Evite la pérdida de datos
     *  3. Eliminar metadatos del espacio
     *
     * @return ¿La eliminación se realizó correctamente?(Espacio predeterminado/espacio inexistente devuelto false)
     */
    suspend fun deleteSpace(id: String): Boolean = withContext(Dispatchers.IO) {
        if (id == MemorySpaceEntity.DEFAULT_SPACE_ID) {
            Logger.w("MemorySpaceRepository", "Cannot delete default space")
            return@withContext false
        }
        val existing = spaceDao.getById(id) ?: return@withContext false
        // Migrar hechos al valor predeterminado Space
        val migrated = spaceDao.migrateFacts(id, MemorySpaceEntity.DEFAULT_SPACE_ID)
        if (migrated > 0) {
            Logger.i("MemorySpaceRepository", "Migrated $migrated facts from $id → default")
        }
        // v2.6.2(auditoría de memoria): Migre los bordes del gráfico de forma sincrónica; de lo contrario, los bordes permanecerán huérfanos en el espacio eliminado.
        val migratedLinks = memoryLinkDao?.migrateBySpaceId(id, MemorySpaceEntity.DEFAULT_SPACE_ID) ?: 0
        if (migratedLinks > 0) {
            Logger.i("MemorySpaceRepository", "Migrated $migratedLinks links from $id → default")
        }
        spaceDao.deleteById(id)
        Logger.i("MemorySpaceRepository", "Deleted space: id=$id name=${existing.name}")
        true
    }

    /**
     * Ajustar la clasificación del espacio.
     * @param orderedIds Lista de identificadores de espacio en nuevo orden
     */
    suspend fun reorderSpaces(orderedIds: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (orderedIds.isEmpty()) return@withContext false
        orderedIds.forEachIndexed { index, id ->
            spaceDao.updateSortIndex(id, index)
        }
        true
    }

    /**
     * Cuente el número de hechos en el espacio especificado.
     */
    suspend fun countFactsInSpace(spaceId: String): Int = withContext(Dispatchers.IO) {
        spaceDao.countFactsInSpace(spaceId)
    }

    /**
     * Asegúrese de que exista el espacio predeterminado.
     * Llamado cuando se inicia la aplicación.,Evite que las excepciones de migración de bases de datos provoquen que falte el espacio predeterminado.
     */
    suspend fun ensureDefaultSpaceExists() = withContext(Dispatchers.IO) {
        val existing = spaceDao.getById(MemorySpaceEntity.DEFAULT_SPACE_ID)
        if (existing == null) {
            val now = Instant.now().toString()
            spaceDao.upsert(
                MemorySpaceEntity(
                    id = MemorySpaceEntity.DEFAULT_SPACE_ID,
                    name = MemorySpaceEntity.DEFAULT_SPACE_NAME,
                    icon = MemorySpaceEntity.DEFAULT_SPACE_ICON,
                    description = "",
                    createdAt = now,
                    sortIndex = 0,
                ),
            )
            Logger.i("MemorySpaceRepository", "Default space was missing, recreated")
        }
    }
}
