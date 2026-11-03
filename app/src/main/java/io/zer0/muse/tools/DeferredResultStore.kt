package io.zer0.muse.tools

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Comentarios asincrónicos sobre los resultados de las tareas delegadas — Infraestructura central para la delegación sin bloqueo.
 *
 * diseño:
 *  - El agente principal llama al subagente y regresa inmediatamente. taskId,No bloquea la conversación principal.
 *  - Aprobado después de completar la subtarea [resolve] Resultados de recarga
 *  - ChatViewModel Suscríbete [completedTasks] Obtener tareas completadas,Inyectar en la conversación principal como interludio
 *
 * Revisión de corrección (2.0):
 *  - B-33: Leer todo-cambiar-Escriba en StateFlow.update y complete dentro de la transformación CAS,Múltiples subtareas simultáneamente
 *    resolve/fail Ya no se sobrescriben las entradas faltantes entre sí.
 *  - A-15: resolve/fail Rechazar comentarios sobre tareas ABORTADAS(Los resultados de la finalización en segundo plano después del aborto no se deben inyectar.
 *    dialogo principal);abort Cancelar simultáneamente [attachJob] Tareas en segundo plano registradas,detenerse/No más después de cambiar de sesión
 *    El fantasma acabó corriendo de fondo.
 */
class DeferredResultStore {
    enum class TaskStatus { PENDING, RESOLVED, FAILED, ABORTED }

    data class DeferredTask(
        val taskId: String,
        val parentSessionId: String,
        val threadId: String?,
        val label: String?,
        val taskSummary: String,
        val status: TaskStatus,
        val result: String? = null,
        val error: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
        val completedAt: Long? = null,
    )

    private val _tasks = MutableStateFlow<Map<String, DeferredTask>>(emptyMap())
    val tasks: StateFlow<Map<String, DeferredTask>> = _tasks.asStateFlow()

    /** Tareas completadas que se reintroducirán(parentSessionId -> Lista de resultados a rellenar). */
    private val _completedTasks = MutableStateFlow<Map<String, List<DeferredTask>>>(emptyMap())
    val completedTasks: StateFlow<Map<String, List<DeferredTask>>> = _completedTasks.asStateFlow()

    /** A-15: Tarea → Registro de trabajos de backend(abort Cancelar cuando,La misión anti-fantasmas continúa). */
    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * [attachJob] con [abort] comparte este candado,evitar“abortar primero y luego attach”Dejando un trabajo fantasma que no se puede cancelar.
     * StateFlow Él mismo garantiza la atomicidad de las actualizaciones de instantáneas.,El bloqueo garantiza que la verificación del estado final de la tarea y el registro del trabajo no puedan interrumpirse.
     */
    private val jobLock = Any()

    /** A-15: Registrar tareas en segundo plano Job,suministro [abort] Cancelar. */
    fun attachJob(taskId: String, job: Job) {
        val cancelImmediately = synchronized(jobLock) {
            when (_tasks.value[taskId]?.status) {
                TaskStatus.ABORTED, TaskStatus.RESOLVED, TaskStatus.FAILED -> true
                // A caller may attach before defer() in compatibility code; abort()
                // still owns the race for an as-yet-unregistered task.
                else -> {
                    jobs[taskId] = job
                    false
                }
            }
        }
        if (cancelImmediately) {
            // Tarea terminada,No se puede permitir que un Job tardío continúe ejecutando al niño real.
            job.cancel()
            return
        }
        // El registro también deberá liquidarse cuando se cancele el ámbito externo.,Evite trabajos que mantengan el trabajo permanentemente hasta que se complete la tarea.
        job.invokeOnCompletion { jobs.remove(taskId, job) }
    }

    /** Registrar tareas diferidas. */
    fun defer(taskId: String, parentSessionId: String, threadId: String?, label: String?, taskSummary: String) {
        val task = DeferredTask(taskId, parentSessionId, threadId, label, taskSummary, TaskStatus.PENDING)
        synchronized(jobLock) {
            _tasks.update { it + (taskId to task) }
        }
    }

    /** Marcar tarea completa(éxito). */
    fun resolve(taskId: String, result: String) {
        synchronized(jobLock) {
            val completed = markCompleted(taskId) { it.copy(status = TaskStatus.RESOLVED, result = result) }
            if (completed != null) enqueueCompleted(completed)
        }
    }

    /** La tarea de marcar falló. */
    fun fail(taskId: String, error: String) {
        synchronized(jobLock) {
            val completed = markCompleted(taskId) { it.copy(status = TaskStatus.FAILED, error = error) }
            if (completed != null) enqueueCompleted(completed)
        }
    }

    /**
     * A-15: Hecho dentro de una transformación CAS"leer tarea → verificar no ABORTED → Marcar completo".
     * La tarea ha sido abortada y la retroalimentación se rechaza directamente.(Regresar null),Los resultados antiguos completados en segundo plano antes de que se cancelara la propagación no entrarán en la cola para rellenarse.
     */
    private fun markCompleted(taskId: String, transform: (DeferredTask) -> DeferredTask): DeferredTask? {
        var completed: DeferredTask? = null
        synchronized(jobLock) {
            _tasks.update { map ->
                val task = map[taskId] ?: return@update map
                if (task.status != TaskStatus.PENDING) return@update map
                val next = transform(task).copy(completedAt = System.currentTimeMillis())
                completed = next
                map + (taskId to next)
            }
            if (completed != null) {
                jobs.remove(taskId)
            }
        }
        return completed
    }

    /** B-33: La adición a la cola para rellenar se completa dentro de la transformación CAS. */
    private fun enqueueCompleted(completed: DeferredTask) {
        _completedTasks.update { map ->
            val list = map[completed.parentSessionId]?.toMutableList() ?: mutableListOf()
            list.add(completed)
            map + (completed.parentSessionId to list)
        }
    }

    /** Abortar tarea:A-15 Cancele también el trabajo en segundo plano. */
    fun abort(taskId: String) {
        val job = synchronized(jobLock) {
            val task = _tasks.value[taskId]
            if (task == null) {
                // Keep compatibility with callers that attach a Job before defer().
                jobs.remove(taskId)
            } else if (task.status == TaskStatus.PENDING) {
                _tasks.update { map ->
                    val current = map[taskId] ?: return@update map
                    if (current.status != TaskStatus.PENDING) return@update map
                    map + (
                        taskId to current.copy(
                            status = TaskStatus.ABORTED,
                            completedAt = System.currentTimeMillis(),
                        )
                        )
                }
                jobs.remove(taskId)
            } else {
                null
            }
        }
        // Cancel after the terminal state is visible; a child that races to finish
        // can no longer enqueue a result because markCompleted rejects non-pending tasks.
        job?.cancel()
    }

    /** Consumir y borrar tareas pendientes para la sesión especificada(Llamado después de recargar). */
    fun consumeCompleted(sessionId: String): List<DeferredTask> {
        var result: List<DeferredTask> = emptyList()
        _completedTasks.update { map ->
            result = map[sessionId] ?: emptyList()
            if (result.isEmpty()) map else map - sessionId
        }
        return result
    }

    /**
     * A-10: consumir y aclarar“No adquirido”tareas a reinyectar —— Es decir, el resultado se almacena en la identificación de la sesión principal de cadena vacía.
     *
     * razones comerciales:subagent_task Confíe en LLM para pasar parent_session_id para atribuir resultados,Pero LLM puede omitir este parámetro.,
     * En este momento [defer] Utilice una cadena vacía como clave para guardar,y [consumeCompleted] Coincidencia exacta basada en la identificación de la sesión real,
     * Los resultados de cadenas vacías nunca coincidirán,será [cleanupCompleted] Limpiar después de 30 minutos,Los usuarios no pueden ver los resultados.
     *
     * ChatViewModel Después de consumir los resultados de la sesión actual,Luego llame a este método para recuperar estos resultados no atribuidos.,
     * Inyectar la sesión actualmente activa(Comportamiento degradado,La persona que llama debe registrar el registro de ERRORES para explicar el motivo.).
     */
    fun consumeUnowned(): List<DeferredTask> {
        return consumeCompleted("")
    }

    /** Obtener el estado de la tarea. */
    fun getTask(taskId: String): DeferredTask? = _tasks.value[taskId]

    /** Listar tareas pendientes. */
    fun listPendingTasks(): List<DeferredTask> = _tasks.value.values.filter { it.status == TaskStatus.PENDING }

    /** Limpiar tareas completadas(Llame periódicamente para evitar pérdidas de memoria.). */
    fun cleanupCompleted(maxAgeMs: Long = 30 * 60 * 1000L) {
        val now = System.currentTimeMillis()
        _tasks.update { map ->
            map.filter { (_, task) ->
                task.status == TaskStatus.PENDING || (task.completedAt != null && now - task.completedAt < maxAgeMs)
            }
        }
    }
}
