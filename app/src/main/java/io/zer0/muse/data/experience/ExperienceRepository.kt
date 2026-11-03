package io.zer0.muse.data.experience

import kotlinx.coroutines.flow.Flow

/**
 * v1.98: Almacén de bases de datos de experiencias — Proporcionar CRUD + observar todo.
 *
 * inyectar en SystemPromptAssembler(experimentar la infusión),MemoryViewModel(UI mostrar),ChatViewModel(manuales CRUD).
 */
class ExperienceRepository(
    private val dao: ExperienceDao,
) {
    fun observeAll(): Flow<List<ExperienceEntity>> = dao.observeAll()

    suspend fun getAll(): List<ExperienceEntity> = dao.getAll()

    suspend fun getById(id: String): ExperienceEntity? = dao.getById(id)

    suspend fun upsert(entity: ExperienceEntity) = dao.upsert(entity)

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun count(): Int = dao.count()
}
