package io.zer0.muse.data.artifact

import kotlinx.coroutines.flow.Flow

/**
 * Artifact almacén:Encapsulación [ArtifactDao],Exponga el método de verificación agregar/eliminar con el mismo nombre.
 */
class ArtifactRepository(
    private val dao: ArtifactDao,
) {
    suspend fun upsert(entity: ArtifactEntity) = dao.upsert(entity)

    suspend fun delete(id: String) = dao.deleteById(id)

    fun observeBySession(sessionId: String): Flow<List<ArtifactEntity>> = dao.observeBySession(sessionId)

    suspend fun getByMessage(messageId: String): List<ArtifactEntity> = dao.getByMessage(messageId)

    fun observeByMessage(messageId: String): Flow<List<ArtifactEntity>> = dao.observeByMessage(messageId)

    suspend fun getById(id: String): ArtifactEntity? = dao.getById(id)

    // v2.0.1: Centro de productos — flujo completo de producto
    fun observeAll(): Flow<List<ArtifactEntity>> = dao.observeAll()
}
