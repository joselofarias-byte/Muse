package io.zer0.muse.ui.memory

import io.zer0.memory.fact.FactStore

/**
 * Funciones puras que seleccionan nodos de constelaciones.
 *
 * Los límites de relación, la importancia y el estado fijado sólo afectan la prioridad de visualización y ya no determinan si los hechos ordinarios son visibles;
 * De lo contrario, los hechos que se acaban de escribir pero que aún no se han resuelto se perderán en la constelación de la memoria.
 */
internal fun selectGraphFacts(facts: List<FactStore.Fact>, linkedFactIds: Set<Long>, maxNodes: Int): List<FactStore.Fact> = facts
    .sortedWith(
        compareByDescending<FactStore.Fact> { it.id in linkedFactIds }
            .thenByDescending { it.pinnedAt != null }
            .thenByDescending { it.importance }
            .thenByDescending { it.confidence }
            .thenByDescending { it.id },
    )
    .take(maxNodes)
