package io.zer0.muse.ui.chat

/**
 * Actividad del agente secundario en segundo plano.
 *
 * Empaquetado en un objeto porque tiene dos fuentes (hilos activos + tareas pendientes),
 * Las pletinas no deberían tener un parámetro extra para cada una de ellas.
 */
internal data class SubagentActivity(
    val threads: List<io.zer0.muse.data.subagent.SubagentThreadStore.ThreadEntry> = emptyList(),
    val pending: List<io.zer0.muse.tools.DeferredResultStore.DeferredTask> = emptyList(),
) {
    /** Los dos suelen ser iguales y el valor mayor se utiliza para cubrir escenarios de desacoplamiento (del mismo calibre que SubagentTaskListCard). */
    val count: Int get() = maxOf(threads.size, pending.size)

    val isEmpty: Boolean get() = threads.isEmpty() && pending.isEmpty()
}
