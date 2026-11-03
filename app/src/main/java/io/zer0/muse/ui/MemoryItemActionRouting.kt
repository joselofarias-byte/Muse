package io.zer0.muse.ui

internal inline fun <T> routeMemoryItemAction(
    item: MemoryItem,
    onFact: (factId: String, scope: String?) -> T,
    onSummary: (sessionId: String) -> T,
    onUnsupported: () -> T,
    /**
     * v2.6.7: Experimente la entrada a la biblioteca (source="Experience").
     *
     * MemoryViewModel de addExperience / editExperience / deleteExperience Se ha realizado hace mucho tiempo,
     * ExperienceLibraryCard Los dos componentes con ExperienceEditDialog también se han escrito, pero
     * MemoryScreen nunca los cites —— La base de datos de experiencias solo puede ser escrita por IA y los usuarios no pueden leerla ni modificarla.
     */
    onExperience: (id: String) -> T,
    /**
     * v2.6.7: Compilar producto (source="Compile").
     *
     * id Esa es la clave de sección (facts/today/week/longterm), coherente con MemoryCompiler.Section.key.
     * Anteriormente, dichas entradas se incluían en onUnsupported —— Después de la edición y la confirmación, el guardado silencioso falla y no sucede nada al eliminar.
     * Y editCompile/deleteCompile se implementó hace mucho tiempo.
     */
    onCompile: (sectionKey: String) -> T,
): T = when (item.source) {
    "Fact" -> onFact(item.id, item.scope)
    "Summary" -> onSummary(item.id)
    "Compile" -> onCompile(item.id)
    "Experience" -> onExperience(item.id)
    else -> onUnsupported()
}

internal fun memoryScopesForMaintenance(selectedScope: String?, availableScopes: List<ScopeOption>): List<String> {
    if (selectedScope != null) return listOf(selectedScope)
    return availableScopes.asSequence()
        .filterNot { it.isAll }
        .mapNotNull { it.id }
        .distinct()
        .toList()
        .ifEmpty { listOf("main") }
}
