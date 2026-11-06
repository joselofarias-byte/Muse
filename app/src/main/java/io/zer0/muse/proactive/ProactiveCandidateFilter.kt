package io.zer0.muse.proactive

import io.zer0.muse.data.proactive.PendingThoughtEntity
import io.zer0.muse.data.proactive.ProactiveIntensity
import io.zer0.muse.data.proactive.ThoughtKind

/**
 * Seleccione candidatos del libro de preocupaciones que puedan presentarse al modelo en esta ronda.
 *
 * Dos filtros, por diferentes motivos:
 *  - Nivel activo: el modo silencioso solo permite "cosas que hay que decir", la pequeña charla no está incluida en la lista
 *  - Comentarios históricos: los usuarios nunca están interesados ​​en ciertos tipos de mensajes, así que no los moleste con ellos.
 *
 * Lo seleccioné porque estas dos reglas son juicios puros y no requieren modelos ni bibliotecas;
 * Enterrado en la etapa de inspección de generación, solo puede verificarse realizando una ejecución real.
 */
object ProactiveCandidateFilter {

    /**
     * @param candidates Todas las inquietudes ABIERTAS en el libro mayor
     * @param intensity Nivel activo seleccionado por el usuario
     * @param bannedKinds Tipos juzgados como "no interesados" por la retroalimentación histórica
     */
    fun select(
        candidates: List<PendingThoughtEntity>,
        intensity: ProactiveIntensity,
        bannedKinds: Set<ThoughtKind> = emptySet(),
    ): List<PendingThoughtEntity> = candidates
        .sortedByDescending { it.weight }
        .filter { intensity.allows(it.kindEnum()) }
        .filterNot { it.kindEnum() in bannedKinds }
}
