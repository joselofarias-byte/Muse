package io.zer0.muse.data.relationship

import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.flow.Flow

/**
 * Almacén de estado de relación de roles (P1-1).
 *
 * Sólo expuesto al mundo exterior."semántico"operación, evitando que la persona que llama toque el DAO directamente:
 * La probabilidad de mensajes privados, la ponderación de la interacción del círculo de amigos y la inyección rápida se basan en esto.
 * Esto evita la inconsistencia en el comportamiento causada por el cálculo de coeficientes en cada uno de los tres lugares.
 */
class AssistantRelationshipRepository(
    private val dao: AssistantRelationshipDao,
) {
    fun observeAll(): Flow<List<AssistantRelationshipEntity>> = dao.observeAll()

    suspend fun getAll(): List<AssistantRelationshipEntity> = resultOf { dao.getAll() }
        .onError { msg, t -> Logger.w(TAG, "No se pudo leer la lista de relaciones: ${t?.message ?: msg}") }
        .getOrNull() ?: emptyList()

    /**
     * Obtener el estado civil de un determinado rol; devolver si no hay registro**Valor predeterminado**en lugar de nulo.
     *
     * No escriba bibliotecas: evite escribir líneas vacías para cada ayudante. Sólo cuando la interacción ocurre realmente se abandona.
     */
    suspend fun getOrDefault(assistantId: String): AssistantRelationshipEntity = resultOf { dao.get(assistantId) }.getOrNull()
        ?: AssistantRelationshipEntity(assistantId = assistantId)

    suspend fun upsert(entity: AssistantRelationshipEntity) {
        val now = System.currentTimeMillis()
        val normalized = entity.copy(
            affinity = entity.affinity.coerceIn(0, 100),
            createdAt = if (entity.createdAt == 0L) now else entity.createdAt,
            updatedAt = now,
        )
        resultOf { dao.upsert(normalized) }
            .onError { msg, t -> Logger.w(TAG, "No se pudo guardar la relación: ${t?.message ?: msg}") }
    }

    /**
     * Documente una interacción real y aumente la intimidad de una manera diminuta.
     *
     * Cuando el registro no existe, primero construye el banco y luego súmalo. —— Utilice upsert para garantizar"primera interacción"También se puede almacenar en almacenamiento,
     * De lo contrario, la ACTUALIZACIÓN de knockAffinity llegará a la fila 0 y fallará silenciosamente.
     */
    suspend fun recordInteraction(assistantId: String, affinityDelta: Int = AFFINITY_PER_INTERACTION) {
        val now = System.currentTimeMillis()
        val existing = resultOf { dao.get(assistantId) }.getOrNull()
        if (existing == null) {
            resultOf {
                dao.upsert(
                    AssistantRelationshipEntity(
                        assistantId = assistantId,
                        affinity = affinityDelta.coerceIn(0, 100),
                        lastInteractionAt = now,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }.onError { msg, t -> Logger.w(TAG, "No se pudo inicializar la relación: ${t?.message ?: msg}") }
            return
        }
        resultOf { dao.bumpAffinity(assistantId, affinityDelta, now) }
            .onError { msg, t -> Logger.w(TAG, "No se pudo actualizar la intimidad: ${t?.message ?: msg}") }
    }

    suspend fun delete(assistantId: String) {
        resultOf { dao.delete(assistantId) }
            .onError { msg, t -> Logger.w(TAG, "No se pudo eliminar la relación: ${t?.message ?: msg}") }
    }

    /**
     * Calcula la probabilidad de que un personaje tome la iniciativa de enviar un mensaje privado.
     *
     * La implementación anterior estaba codificada en un 40% y no tenía absolutamente nada que ver con las relaciones de los personajes. —— Los extraños pueden ser tan pegajosos como las novias.
     * Ahora: probabilidad base × coeficiente de intimidad × coeficiente de engranaje deseado y sujetado [MIN_PROBABILITY, MAX_PROBABILITY].
     *
     * @param baseProbability La probabilidad básica dada por la persona que llama (manteniendo la magnitud del comportamiento existente)
     */
    fun proactiveProbability(relationship: AssistantRelationshipEntity, baseProbability: Float = BASE_PROBABILITY): Float {
        if (!relationship.proactiveEnabled) return 0f
        val raw = baseProbability * relationship.affinityFactor * relationship.proactiveFactor
        return raw.coerceIn(MIN_PROBABILITY, MAX_PROBABILITY)
    }

    /**
     * Selección ponderada de asistentes en función de la intimidad.
     *
     * La interacción de momentos era anteriormente `Random.nextInt(size)` Puramente aleatorio, lo que resulta en"Alguien con quien nunca he hablado"y
     * "Personas que chatean todos los días."Me gusta con la mayor frecuencia posible. Aquí, la intimidad se utiliza como peso, y el límite inferior del peso garantiza que también tengan la oportunidad de aparecer nuevos personajes.
     */
    fun pickWeighted(
        candidates: List<String>,
        relationships: Map<String, AssistantRelationshipEntity>,
        random: kotlin.random.Random = kotlin.random.Random,
    ): String? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        val weights = candidates.map { id ->
            val affinity = relationships[id]?.affinity ?: 0
            (MIN_PICK_WEIGHT + affinity / 100f).toDouble()
        }
        val total = weights.sum()
        if (total <= 0.0) return candidates.random(random)
        var cursor = random.nextDouble(total)
        for (i in candidates.indices) {
            cursor -= weights[i]
            if (cursor <= 0.0) return candidates[i]
        }
        return candidates.last()
    }

    companion object {
        private const val TAG = "AssistantRelation"

        /** Intimidad incremental con cada interacción real. Unas 50 interacciones para llegar al máximo. */
        const val AFFINITY_PER_INTERACTION = 2

        /** La probabilidad fija de mensajes privados de la implementación anterior permanece sin cambios en magnitud como probabilidad base. */
        const val BASE_PROBABILITY = 0.4f

        const val MIN_PROBABILITY = 0.02f
        const val MAX_PROBABILITY = 0.9f

        /** Peso mínimo para ponderación de interacción: asegúrese de que un personaje con nivel de intimidad 0 todavía tenga aproximadamente 1/(n*~1.0) oportunidad. */
        const val MIN_PICK_WEIGHT = 0.15f
    }
}
