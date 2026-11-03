package io.zer0.muse.tools

import io.zer0.common.Logger
// v1.0.53: Versión persistente SubagentThreadStore(Reemplaza las herramientas antiguas. Versión de memoria SubagentThreadStore)
import io.zer0.muse.data.subagent.SubagentThreadStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * subagent_task Herramientas.
 *
 * Ejecución de tareas de subagente en segundo plano sin bloqueo,Admite la continuación y el cierre de subprocesos.
 *
 * Operación de tres piezas:
 *  - launch:Iniciar una nueva tarea de subagente,Regresar taskId + threadId
 *  - reply :Continuación del mismo niño. agent(prensa threadId),entrante nuevo task
 *  - close :Cerrar el hilo del agente secundario,Liberar recursos
 *
 * Operaciones auxiliares:
 *  - status :Consultar el progreso de la tarea
 *  - cancel :Abortar tarea
 *  - list  :enumerar todas las tareas
 *
 * Herencia de archivos de permisos(Transformación 3):
 *  - access parámetros(read/write,Valores predeterminados cuando se omiten read,Heredar sesión principal)
 *  - Los valores ilegales se rechazan directamente.,No bajes de categoría en silencio
 *  - Pase [DelegationContract.DelegationRequest.access] pasar a [SkillExecutor.delegateAgent],
 *    Es verificado por delegadoDepth y access(Solución simplificada:Esta herramienta solo realiza análisis de parámetros.)
 *
 * Comentarios de resultados asincrónicos(Transformación 4):
 *  - launch/reply Regresar inmediatamente taskId,No bloquea la conversación principal.
 *  - Aprobado después de completar la subtarea [DeferredResultStore] Resultados de recarga
 *  - ChatViewModel Disponible para suscripción [DeferredResultStore.completedTasks] Obtenga tareas completadas e inyecte en el diálogo principal
 *  - [cleanupCompletedTasks] Limpiar periódicamente las tareas completadas,Sincronizar con DeferredResultStore
 */
object SubagentTool {

    /** ¿Cuánto tiempo mantener las tareas completadas?:30 Limpiar después de minutos. */
    private const val COMPLETED_TASK_TTL_MS = 30L * 60 * 1000

    data class TaskState(
        val taskId: String,
        val agentId: String,
        val description: String,
        val status: String, // pending / running / completed / failed / cancelled
        val progress: String = "",
        val result: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
        /** v1.202: Tiempo de finalización de la tarea(para limpieza),null Indica incompleto. */
        val completedAt: Long? = null,
        /** v1.202: Hilos de agentes secundarios asociados ID(Se utiliza para responder/cerrar continuación). */
        val threadId: String? = null,
    )

    private val tasks = ConcurrentHashMap<String, TaskState>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _taskListFlow = MutableStateFlow<List<TaskState>>(emptyList())
    val taskListFlow: StateFlow<List<TaskState>> = _taskListFlow

    fun toolDef() = ToolRegistry.ToolDef(
        name = "subagent_task",
        description = "Iniciar/continuar/detener una tarea de subagente sin bloqueo." +
            "action valor:launch(Crear nueva tarea,Devolver taskId con threadId),reply(Continuar por threadId)," +
            "close(soltar hilo),status(Ver progreso),cancel(Abortar tarea),list(Listar todas las tareas).",
        parameters = mapOf(
            "action" to "Required. One of: launch / reply / close / status / cancel / list.",
            "agent_id" to "Required for launch. The assistant id to run the task.",
            "task" to "Required for launch/reply. Task description / prompt.",
            "task_id" to "Required for status/cancel. Task id returned by launch.",
            "thread_id" to "Required for reply/close. Thread id returned by launch.",
            // A-10: parent_session_id Requerido —— De forma predeterminada, los resultados asincrónicos no se pueden atribuir a la sesión que inicia y se pierden silenciosamente.
            "parent_session_id" to "REQUIRED for launch/reply. Parent session id for context isolation and " +
                "result delivery. If omitted, the async result is silently lost.",
            "access" to "Optional. Permission tier: read / write (default read, inherits parent session).",
            "label" to "Optional. Display label for the task.",
        ),
        required = setOf("action"),
        category = "built-in",
        // con ToolPermissionResolver.EXPLICIT_RISK_OVERRIDES ser consistente:subagent_task volver HIGH
        riskLevel = ToolRiskLevel.HIGH,
    )

    /**
     * Ejecute la herramienta subagent_task.
     *
     * @param args Parámetros de la herramienta
     * @param skillExecutor Skill actuador(Utilizado para el asistente de ejecución de delegadoAgent LLM)
     * @param subagentThreadStore Administrador de hilos de subagente(Continuación del hilo/cerrar/serialización)
     * @param deferredResultStore Almacenamiento asincrónico de comentarios de resultados(Los resultados sin bloqueo se devuelven al cuadro de diálogo principal.)
     * @param appScope nivel de aplicación CoroutineScope(Realizar tareas sin bloqueo en segundo plano,Cortar páginas/Sin interrupciones en segundo plano)
     */
    suspend fun execute(
        args: Map<String, String>,
        skillExecutor: SkillExecutor,
        subagentThreadStore: SubagentThreadStore,
        deferredResultStore: DeferredResultStore,
        appScope: CoroutineScope,
    ): String {
        val action = args["action"]?.trim() ?: return "Error: action parameter is required."

        // list/status Limpiar tareas completadas,Evite pérdidas de memoria
        if (action == "list" || action == "status") {
            cleanupCompletedTasks(deferredResultStore)
        }

        return when (action) {
            "launch" -> doLaunch(args, skillExecutor, subagentThreadStore, deferredResultStore, appScope)
            "reply" -> doReply(args, skillExecutor, subagentThreadStore, deferredResultStore, appScope)
            "close" -> doClose(args, subagentThreadStore)

            "status" -> {
                val taskId = args["task_id"]?.trim()
                    ?: return "Error: task_id is required for status."
                val state = tasks[taskId] ?: return "Error: task '$taskId' not found."
                buildString {
                    append("Task ${state.taskId}: status=${state.status}, progress=${state.progress}")
                    if (state.threadId != null) append(", threadId=${state.threadId}")
                    if (state.result != null) append("\nResult: ${state.result}")
                }
            }

            "cancel" -> {
                val taskId = args["task_id"]?.trim()
                    ?: return "Error: task_id is required for cancel."
                jobs[taskId]?.cancel()
                deferredResultStore.abort(taskId)
                // Only mark the outer task cancelled when abort won the same
                // terminal-state race as the real child Job. If the child already
                // resolved, keep COMPLETED instead of rewriting history to CANCELLED.
                if (deferredResultStore.getTask(taskId)?.status == DeferredResultStore.TaskStatus.ABORTED) {
                    tasks[taskId]?.let { current ->
                        tasks[taskId] = current.copy(
                            status = "cancelled",
                            completedAt = System.currentTimeMillis(),
                        )
                    }
                }
                updateFlow()
                "Task $taskId cancelled."
            }

            "list" -> {
                val all = tasks.values.sortedByDescending { it.createdAt }
                if (all.isEmpty()) return "No background tasks."
                buildString {
                    appendLine("Background tasks (${all.size}):")
                    for (t in all.take(20)) {
                        appendLine("- ${t.taskId}: ${t.status} | ${t.description.take(40)} | ${t.progress}")
                    }
                }.trimEnd()
            }

            else -> "Error: action must be launch/reply/close/status/cancel/list."
        }
    }

    /**
     * Iniciar una nueva tarea de subagente(Transformación 2:launch).
     *
     * proceso:
     *  1. Parámetros de análisis(agent_id / task / access / label / parent_session_id)
     *  2. generar threadId + taskId
     *  3. Registrar hilo para [SubagentThreadStore]
     *  4. Llegan los resultados del retraso en el registro [DeferredResultStore]
     *  5. appScope Ejecución asincrónica en segundo plano delegateAgent,Resultado después de la finalización
     *  6. Regresar inmediatamente taskId + threadId
     */
    private suspend fun doLaunch(
        args: Map<String, String>,
        skillExecutor: SkillExecutor,
        subagentThreadStore: SubagentThreadStore,
        deferredResultStore: DeferredResultStore,
        appScope: CoroutineScope,
    ): String {
        val agentId = args["agent_id"]?.trim()
            ?: return "Error: agent_id is required for launch."
        val task = args["task"]?.trim()
            ?: return "Error: task is required for launch."
        val parentSessionId = args["parent_session_id"]?.trim()?.takeIf { it.isNotBlank() }
        val label = args["label"]?.trim()?.takeIf { it.isNotBlank() }
        // Transformación 3: análisis de archivos de permisos,Los valores ilegales se rechazan directamente.
        val access = parseAccess(args["access"])
            ?: return "Error: access must be 'read' or 'write' (got: ${args["access"]})."

        val threadId = "thread-" + UUID.randomUUID().toString().take(12)
        val taskId = UUID.randomUUID().toString().take(12)

        // Registrar hilo(Para respuesta posterior/cierre continuación)
        subagentThreadStore.beginRun(threadId, agentId, parentSessionId)
        // Registrar una tarea retrasada(Para que ChatViewModel retroalimente la conversación principal)
        deferredResultStore.defer(taskId, parentSessionId ?: "", threadId, label, task)
        // Registrarse localmente TaskState(Para consulta de estado/lista)
        tasks[taskId] = TaskState(
            taskId = taskId,
            agentId = agentId,
            description = task,
            status = "pending",
            progress = "Queued",
            threadId = threadId,
        )
        updateFlow()

        // LAUNCH MUST be the sole owner of this task's background Job. The delegate
        // request below is blocking so this Job stays alive until the real child finishes.
        // Start lazily so cancellation cannot race the registration in DeferredResultStore.
        val job = appScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            runSubagent(
                taskId = taskId,
                threadId = threadId,
                agentId = agentId,
                task = task,
                parentSessionId = parentSessionId,
                access = access,
                label = label,
                skillExecutor = skillExecutor,
                subagentThreadStore = subagentThreadStore,
                deferredResultStore = deferredResultStore,
            )
        }
        jobs[taskId] = job
        // A-15: Regístrate en la tienda de resultados — abort Capaz de cancelar tareas en segundo plano.,Los resultados anti-fantasmas continúan ejecutándose
        deferredResultStore.attachJob(taskId, job)
        job.invokeOnCompletion { jobs.remove(taskId, job) }
        job.start()

        return "Task launched: taskId=$taskId, threadId=$threadId, agent=$agentId. " +
            "Use action=status&task_id=$taskId to check progress, " +
            "action=reply&thread_id=$threadId to continue, " +
            "action=close&thread_id=$threadId to release."
    }

    /**
     * Continuación del mismo niño. agent(Transformación 2:reply).
     *
     * proceso:
     *  1. analizar thread_id / task / access / label
     *  2. Verifique que el hilo exista y ACTIVE
     *  3. Registrar nuevo taskId(Reutilizar lo mismo threadId)
     *  4. appScope Ejecución asincrónica en segundo plano,Serialización mediante runSerialized(Tareas en cola con el mismo threadId)
     */
    private suspend fun doReply(
        args: Map<String, String>,
        skillExecutor: SkillExecutor,
        subagentThreadStore: SubagentThreadStore,
        deferredResultStore: DeferredResultStore,
        appScope: CoroutineScope,
    ): String {
        val threadId = args["thread_id"]?.trim()
            ?: return "Error: thread_id is required for reply."
        val task = args["task"]?.trim()
            ?: return "Error: task is required for reply."
        val label = args["label"]?.trim()?.takeIf { it.isNotBlank() }
        val access = parseAccess(args["access"])
            ?: return "Error: access must be 'read' or 'write' (got: ${args["access"]})."

        val entry = subagentThreadStore.getThread(threadId)
            ?: return "Error: thread '$threadId' not found."
        if (entry.status == SubagentThreadStore.ThreadStatus.CLOSED) {
            return "Error: thread '$threadId' is closed. Start a new task with action=launch."
        }

        val taskId = UUID.randomUUID().toString().take(12)
        deferredResultStore.defer(taskId, entry.parentSessionId ?: "", threadId, label, task)
        tasks[taskId] = TaskState(
            taskId = taskId,
            agentId = entry.assistantId,
            description = task,
            status = "pending",
            progress = "Queued",
            threadId = threadId,
        )
        updateFlow()

        // REPLY also owns exactly one background Job. The delegate call is blocking
        // so completion below reflects the real child result, not an enqueue acknowledgement.
        val job = appScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            runSubagent(
                taskId = taskId,
                threadId = threadId,
                agentId = entry.assistantId,
                task = task,
                parentSessionId = entry.parentSessionId,
                access = access,
                label = label,
                skillExecutor = skillExecutor,
                subagentThreadStore = subagentThreadStore,
                deferredResultStore = deferredResultStore,
            )
        }
        jobs[taskId] = job
        // A-15: Regístrate en la tienda de resultados — abort Capaz de cancelar tareas en segundo plano.
        deferredResultStore.attachJob(taskId, job)
        job.invokeOnCompletion { jobs.remove(taskId, job) }
        job.start()

        return "Reply queued: taskId=$taskId, threadId=$threadId. " +
            "Use action=status&task_id=$taskId to check progress."
    }

    /**
     * Cerrar el hilo del agente secundario(Transformación 2:close).
     *
     * Después del cierre, el threadId ya no se puede reply;La tarea que se está ejecutando se completará.,Pero las respuestas posteriores fracasarán.
     */
    private suspend fun doClose(args: Map<String, String>, subagentThreadStore: SubagentThreadStore): String {
        val threadId = args["thread_id"]?.trim()
            ?: return "Error: thread_id is required for close."
        val entry = subagentThreadStore.getThread(threadId)
            ?: return "Error: thread '$threadId' not found."
        if (entry.status == SubagentThreadStore.ThreadStatus.CLOSED) {
            return "Thread '$threadId' is already closed."
        }
        subagentThreadStore.closeThread(threadId)
        return "Thread '$threadId' closed."
    }

    /**
     * Ejecutar tareas de subagente en segundo plano(Operación serializada).
     *
     * Pase [SubagentThreadStore.runSerialized] Asegúrese de que varias tareas con el mismo threadId se ejecuten en serie,
     * Evite las carreras concurrentes. Pasar después de completar [DeferredResultStore] Resultados de recarga(Resolución exitosa/fracaso fail).
     */
    private suspend fun runSubagent(
        taskId: String,
        threadId: String,
        agentId: String,
        task: String,
        parentSessionId: String?,
        access: String,
        label: String?,
        skillExecutor: SkillExecutor,
        subagentThreadStore: SubagentThreadStore,
        deferredResultStore: DeferredResultStore,
    ) {
        // marca running
        tasks[taskId]?.let { current ->
            tasks[taskId] = current.copy(status = "running", progress = "Running...")
        }
        updateFlow()

        try {
            val request = DelegationContract.DelegationRequest(
                requestId = taskId,
                task = task,
                targetType = DelegationContract.DelegationRequest.TargetType.ASSISTANT,
                targetId = agentId,
                parentSessionId = parentSessionId,
                threadId = threadId,
                access = access,
                // SubagentTool already owns the background Job. Passing true here would
                // make SkillDelegateAgentImpl launch a second detached Job and report
                // completion before the real child has finished.
                nonBlocking = false,
                label = label,
            )
            val result = subagentThreadStore.runSerialized(threadId) {
                skillExecutor.delegateAgent(request)
            }
            if (result.success) {
                // Resolve the store first. If cancel() won the race, resolve() is
                // rejected and the outer state must remain cancelled, never a fake
                // COMPLETED state produced before the real child was done.
                deferredResultStore.resolve(taskId, result.resultText)
                if (deferredResultStore.getTask(taskId)?.status == DeferredResultStore.TaskStatus.RESOLVED) {
                    tasks[taskId]?.let { current ->
                        tasks[taskId] = current.copy(
                            status = "completed",
                            progress = "Done",
                            result = result.resultText,
                            completedAt = System.currentTimeMillis(),
                        )
                    }
                }
            } else {
                val err = result.error ?: "Unknown error"
                deferredResultStore.fail(taskId, err)
                if (deferredResultStore.getTask(taskId)?.status == DeferredResultStore.TaskStatus.FAILED) {
                    tasks[taskId]?.let { current ->
                        tasks[taskId] = current.copy(
                            status = "failed",
                            progress = "Error: $err",
                            completedAt = System.currentTimeMillis(),
                        )
                    }
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // Reparación de auditoría (3.5): La señal de cancelación de rutina debe propagarse hacia arriba.,no puede ser catch(Exception) tragar
            throw e
        } catch (e: Exception) {
            Logger.w("SubagentTool", "Error en la ejecución de la tarea del subagente: taskId=$taskId, threadId=$threadId", e)
            val err = e.message ?: e.javaClass.simpleName
            deferredResultStore.fail(taskId, err)
            if (deferredResultStore.getTask(taskId)?.status == DeferredResultStore.TaskStatus.FAILED) {
                tasks[taskId]?.let { current ->
                    tasks[taskId] = current.copy(
                        status = "failed",
                        progress = "Error: $err",
                        completedAt = System.currentTimeMillis(),
                    )
                }
            }
        }
        updateFlow()
    }

    /**
     * Analizar parámetros de acceso(Modificación 3: herencia de archivos de permisos).
     *
     * - Omitir/en blanco → "read"(Heredar el archivo predeterminado de la sesión principal)
     * - "read" / "write"(No distingue entre mayúsculas y minúsculas)→ Valor correspondiente
     * - Otros → null(Representa un valor ilegal.,Error devuelto por la persona que llama,No bajes de categoría en silencio)
     */
    private fun parseAccess(raw: String?): String? {
        if (raw.isNullOrBlank()) return "read"
        return when (raw.trim().lowercase()) {
            "read" -> "read"
            "write" -> "write"
            else -> null
        }
    }

    private fun updateFlow() {
        _taskListFlow.value = tasks.values.toList()
    }

    fun getTask(taskId: String): TaskState? = tasks[taskId]

    /**
     * Limpieza completada [COMPLETED_TASK_TTL_MS] tarea(Transformación 4: mecanismo de limpieza de tareas).
     *
     * - Eliminar local [tasks] / [jobs] Completado en(pending afuera)y entradas agotadas
     * - llamada sincrónica [DeferredResultStore.cleanupCompleted] Mantenga ambos lados consistentes
     *
     * tiempo de llamada:
     *  - list / status Llamado durante la operación(Esta herramienta se activa internamente)
     *  - temporizador externo(Como el temporizador de ChatViewModel)llamada explícita
     */
    fun cleanupCompletedTasks(deferredResultStore: DeferredResultStore? = null) {
        val now = System.currentTimeMillis()
        val expiredKeys = tasks.entries.filter { (_, state) ->
            state.completedAt != null && (now - state.completedAt) > COMPLETED_TASK_TTL_MS
        }.map { it.key }
        if (expiredKeys.isNotEmpty()) {
            expiredKeys.forEach { id ->
                tasks.remove(id)
                jobs.remove(id)
            }
            updateFlow()
        }
        // Limpieza sincrónica DeferredResultStore,Mantenga ambos lados consistentes
        deferredResultStore?.cleanupCompleted(COMPLETED_TASK_TTL_MS)
    }
}
