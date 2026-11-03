package io.zer0.muse.web.a2a

/**
 * AUD-20261011-A2A-PROTOCOL: A2A libro de tareas(Limitada, la pertenencia se puede determinar.).
 *
 * Dividir en clases independientes para pruebas individuales,y resolver tres deficiencias:
 *  - GetTask Necesidad de verificar con precisión por identificación de tarea(No más uso indebido de sobres JSON-RPC id);
 *  - CancelTask Primero debe determinar"¿Esta tarea es propiedad de este controlador y todavía se está ejecutando?",
 *    De lo contrario, se ignorará el taskId y se detendrá directamente la generación en curso en primer plano del usuario.;
 *  - El libro de tareas debe estar encuadernado.,Evitar el crecimiento desmedido de los servicios de larga distancia.
 */
internal class A2aTaskRegistry(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    private val tasks = LinkedHashMap<String, A2aTask>()

    val size: Int
        get() = synchronized(tasks) { tasks.size }

    /** Grabar instantánea de la tarea(Se actualizan los registros duplicados con la misma identificación.,Sin ocupación repetida). */
    fun record(task: A2aTask): A2aTask {
        synchronized(tasks) {
            tasks.remove(task.id)
            tasks[task.id] = task
            while (tasks.size > maxEntries) {
                val oldest = tasks.keys.firstOrNull() ?: break
                tasks.remove(oldest)
            }
        }
        return task
    }

    fun get(taskId: String): A2aTask? = synchronized(tasks) { tasks[taskId] }

    /** ¿Hay alguna tarea registrada para esta sesión A2A?(Se utiliza para cancelar la verificación de propiedad.). */
    fun isOwned(taskId: String): Boolean = synchronized(tasks) { tasks.containsKey(taskId) }

    /** ¿Se puede cancelar?:Debe ser una tarea de este controlador y seguir funcionando. */
    fun isCancelable(taskId: String): Boolean = get(taskId)?.state == A2aTaskState.WORKING

    companion object {
        const val DEFAULT_MAX_ENTRIES = 64
    }
}
