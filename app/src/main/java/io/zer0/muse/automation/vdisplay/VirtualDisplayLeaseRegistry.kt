package io.zer0.muse.automation.vdisplay

/**
 * La pantalla virtual es compatible con la cuenta de arrendamiento en proceso de la pantalla.
 *
 * Varios flujos de trabajo reanudables pueden hacer referencia al mismo `ensureDisplay` pantalla, pero tampoco workflow
 * Las pantallas que todavía se utilizan en otros flujos de trabajo no se pueden destruir directamente al liberarlas. solo el ultimo owner
 * Cuando se libera, la persona que llama debe ejecutar la destrucción remota.
 */
class VirtualDisplayLeaseRegistry {

    private val ownersByDisplay = mutableMapOf<Int, MutableSet<String>>()
    private val displayByRun = mutableMapOf<String, Int>()
    private val previousDisplaysByRun = mutableMapOf<String, LinkedHashSet<Int>>()

    @Synchronized
    fun acquire(runId: String, displayId: Int): Boolean {
        require(runId.isNotBlank()) { "runId no puede estar vacío" }
        require(displayId >= 0) { "displayId Inválido" }

        val previous = displayByRun[runId]
        if (previous == displayId) return true
        if (previous != null) {
            val aliases = previousDisplaysByRun.getOrPut(runId) { linkedSetOf() }
            aliases += previous
            while (aliases.size > MAX_PREVIOUS_DISPLAY_ALIASES) aliases.remove(aliases.first())
            ownersByDisplay[previous]?.remove(runId)
            ownersByDisplay[previous]?.takeIf { it.isEmpty() }?.let { ownersByDisplay.remove(previous) }
        }
        previousDisplaysByRun[runId]?.remove(displayId)
        displayByRun[runId] = displayId
        ownersByDisplay.getOrPut(displayId) { linkedSetOf() }.add(runId)
        return true
    }

    /**
     * Libera una referencia de ejecución para mostrar.
     *
     * @return true Indica que la pantalla no tiene otro propietario y la persona que llama puede destruir la pantalla remota.
     */
    @Synchronized
    fun release(runId: String, displayId: Int): Boolean {
        if (displayByRun[runId] != displayId) return false
        displayByRun.remove(runId)
        previousDisplaysByRun.remove(runId)
        val owners = ownersByDisplay[displayId] ?: return true
        owners.remove(runId)
        if (owners.isEmpty()) {
            ownersByDisplay.remove(displayId)
            return true
        }
        return false
    }

    @Synchronized
    fun displayFor(runId: String): Int? = displayByRun[runId]

    /** A stale ID is accepted only when it was previously leased by the same workflow run. */
    @Synchronized
    fun isKnownDisplayId(runId: String, displayId: Int): Boolean =
        displayByRun[runId] == displayId || displayId in previousDisplaysByRun[runId].orEmpty()

    private companion object {
        const val MAX_PREVIOUS_DISPLAY_ALIASES = 4
    }
}
