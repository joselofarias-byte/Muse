package io.zer0.muse.data.skill

import kotlinx.coroutines.flow.Flow

/**
 * Phase 8.8: Skill Repository.
 */
class SkillRepository(private val dao: SkillDao) {
    val observeAll: Flow<List<SkillEntity>> = dao.observeAll()

    suspend fun listEnabled(): List<SkillEntity> = dao.listEnabled()

    // L-SR2: getById Baja frecuencia de llamadas(Principalmente una única consulta cuando la herramienta ejecuta el enrutamiento.),No hay almacenamiento en caché por ahora.
    // Si se produce un camino caliente en el futuro(Como llamada circular),Considere agregar memoria caché LRU.
    suspend fun getById(id: String): SkillEntity? = dao.getById(id)

    suspend fun upsert(entity: SkillEntity) = dao.upsert(entity)

    /**
     * P0-11: Activación de habilidades incorporada seed — Inicializar sólo si falta la clave principal,Ya existe la reserva de fila completa
     * (Incluyendo cierre manual por parte de los usuarios. enabled=false),Evite iniciar REPLACE para restablecer silenciosamente la selección deshabilitada del usuario.
     */
    suspend fun seedBuiltInIfAbsent(entity: SkillEntity) = dao.seedIfAbsent(entity)

    /**
     * v2.x: Activación de habilidades incorporada seed — Si falta, inserte,Si ya existe, solo se actualizará el campo de definición.
     * (name/description/parametersJson/requiredJson/category/implementationKotlin),
     * Conservar el estado del usuario, como habilitado.
     *
     * Se corrigieron dos problemas históricos.:
     *  - Pure seedIfAbsent nunca se actualiza → El antiguo esquema de la biblioteca se detiene en la primera versión instalada.(El modelo no se puede ver añadido./Parámetros modificados);
     *  - upsert(REPLACE) Las habilidades que el usuario haya desactivado se volverán a habilitar.(P0-11 evitado,Este método amplía esta garantía.).
     */
    suspend fun seedOrRefreshBuiltIn(entity: SkillEntity) {
        if (dao.getById(entity.id) == null) {
            dao.seedIfAbsent(entity)
        } else {
            dao.refreshBuiltInDefinition(
                id = entity.id,
                name = entity.name,
                description = entity.description,
                parametersJson = entity.parametersJson,
                requiredJson = entity.requiredJson,
                category = entity.category,
                implementationKotlin = entity.implementationKotlin,
            )
        }
    }

    suspend fun update(entity: SkillEntity) = dao.update(entity)

    suspend fun delete(id: String) = dao.delete(id)

    /** Alternar estado de habilidad habilitada(Actualizar simultáneamente updatedAt). */
    suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled, System.currentTimeMillis())

    /**
     * Filtrar habilidades habilitadas por lista de identificación.
     * @param skillIds Lista de ID de habilidades habilitadas;null Indica todo habilitado
     *
     * M-SR1: Utilice la capa DAO en su lugar `WHERE id IN (:ids)` Consulta,Evite cargar completamente y volver a recordar el filtro.
     */
    suspend fun listEnabledByIds(skillIds: List<String>?): List<SkillEntity> {
        if (skillIds.isNullOrEmpty()) return dao.listEnabled()
        return dao.listEnabledByIds(skillIds)
    }
}
