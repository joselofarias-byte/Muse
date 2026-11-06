package io.zer0.memory.compile

import io.zer0.memory.fact.FactStore

/**
 * D3-P2: FACTS Proyector determinista de segmentos.
 *
 * Representar las entradas de la tabla de hechos en orden estable como"Un hecho por línea"segmento de texto plano.
 * El formato de salida está alineado con los productos LLM existentes (CompilePrompts.buildFactsPrompt:
 * "one fact per line, no bullet prefix, no headings").
 *
 * principios de diseño (D3 esquema):
 *  - Fuente única autorizada:FACTS El contenido del segmento proviene 100 % de la tabla de hechos y LLM ya no lo vuelve a compilar;
 *  - Editar/La eliminación tiene efecto inmediatamente: la proyección es el valor de la tabla;
 *  - Restricción presupuestaria: número de artículos/personaje/Hay un triple límite superior para la longitud de una sola línea, y si excede el límite, se truncará según su importancia.
 */
object FactsSectionProjector {

    /** Configuración de proyección. */
    data class Config(
        /** Número máximo de entradas. */
        val maxItems: Int = 40,
        /** Número máximo de caracteres (superando el truncamiento por clasificación). */
        val maxChars: Int = 1500,
        /** La duración máxima de retención de un solo hecho (las entradas extremadamente largas se truncan para evitar que un solo hecho sobrecargue el presupuesto). */
        val maxItemChars: Int = 120,
    )

    /**
     * Representación de proyección.
     *
     * Ordenar: arriba (pinnedAt no vacío)> importance orden descendente > lastConfirmedAt/createdAt Orden descendente.
     * La persona que llama debería haber filtrado la entrada en busca de lápidas y entradas caducadas (getByScopeAndSpace Filtrado caducado incluido,
     * Las lápidas son manejadas por MemoryCompiler.filterTombstonedLines).
     *
     * v18: Los viejos recuerdos señalados por los reemplazantes no se proyectan —— La fila todavía está en la biblioteca (la página de memoria es visible y se puede revertir),
     * Simplemente no lo escriba en el contexto para evitar que el modelo reciba dos afirmaciones mutuamente excluyentes al mismo tiempo. Una vez eliminada la parte señalante,
     * La colección automáticamente ya no lo contiene y la memoria anterior vuelve al contexto sin estado adicional.
     *
     * @return Texto plano de un hecho por línea; devuelve una cadena vacía si no hay contenido
     */
    fun project(facts: List<FactStore.Fact>, config: Config = Config()): String {
        if (facts.isEmpty()) return ""
        val supersededIds = facts.mapNotNullTo(mutableSetOf()) { it.supersedesId }
        val sorted = facts
            .filter { it.fact.isNotBlank() && it.id !in supersededIds }
            .sortedWith(
                compareByDescending<FactStore.Fact> { it.pinnedAt != null }
                    .thenByDescending { it.importance }
                    .thenByDescending { it.lastConfirmedAt ?: it.createdAt },
            )
        val out = StringBuilder()
        var count = 0
        for (f in sorted) {
            if (count >= config.maxItems) break
            val line = f.fact.trim().let {
                if (it.length > config.maxItemChars) it.take(config.maxItemChars) + "…" else it
            }
            if (line.isBlank()) continue
            if (out.isNotEmpty() && out.length + line.length + 1 > config.maxChars) break
            out.append(line).append('\n')
            count++
        }
        return out.toString().trim()
    }
}
