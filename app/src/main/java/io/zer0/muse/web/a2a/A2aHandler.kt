package io.zer0.muse.web.a2a

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * A2A manejador de protocolo —— Asigne las capacidades conversacionales de Muse a los métodos estándar A2A JSON-RPC.
 *
 * Modelo de ejecución: solicitudes sin interfaz de usuario ni herramientas-Completar el texto de respuesta.A2A Cada contexto se asigna a una sesión del Agente propiedad del controlador,
 * Utilice ChatService.completeText + historial de mensajes privados; El estado de chat actual del usuario no se alternará ni se leerá, y las herramientas del dispositivo no se ejecutarán.
 * taskId/contextId gestionado por un protocolo externo,CancelTask Cancele únicamente la rutina de solicitud registrada por este controlador.
 *
 * Límites de capacidad intencionales:A2A Actualmente sólo se proporcionan diálogos de texto y contexto de múltiples turnos; si necesitas herramientas/Operaciones del dispositivo, que el usuario debe activar en la interfaz de usuario nativa,
 * La aprobación de la interacción no se puede omitir mediante A2A remoto.
 */
class A2aHandler(
    private val chatService: ChatService,
    private val sessionRepo: SessionRepository,
    private val assistantRepository: AssistantRepository,
    private val appVersion: String,
) {
    /** El libro de tareas tiene propiedad limitada y verificable; se utiliza para operaciones GetTask/CancelTask ​​basadas exactamente en la identificación de la tarea A2A. */
    private val taskRegistry = A2aTaskRegistry()
    private val headlessGenerator = A2aHeadlessGenerator(chatService, sessionRepo, assistantRepository)

    /** Serialice A2A SendMessage para evitar la serialización entre solicitudes de recursos generados compartidos. */
    private val sendMutex = kotlinx.coroutines.sync.Mutex()

    /** actual A2A task → Correspondiente a generar sesión;CancelTask Sólo detiene la sesión y no toca las creaciones de otros usuarios. */
    private val taskSessions = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** El trabajo de rutina de la tarea A2A actual;CancelTask Cancele únicamente su propia solicitud. */
    private val taskJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** externo context id → handler-owned session;LRU limitada, elimina sesiones ocultas del Agente en caso de desalojo. */
    private val contexts = A2aContextRegistry()

    /** Agent Card: Declara la identidad, habilidades y destrezas de este agente. */
    fun agentCard(baseUrl: String): AgentCard = AgentCard(
        protocolVersion = A2A_PROTOCOL_VERSION,
        name = "Muse",
        description = "Muse — Android Asistente de terminal AI, que proporciona finalización de conversaciones de texto aisladas (A2A no realiza operaciones de herramientas o dispositivos locales).",
        url = "$baseUrl/a2a",
        version = appVersion,
        capabilities = AgentCapabilities(streaming = false, pushNotifications = false),
        skills = listOf(
            AgentSkill(
                id = "chat",
                name = "dialogo",
                description = "Aislar conversaciones de texto del contexto de múltiples turnos;A2A No se realizan operaciones de dispositivos o herramientas locales.",
                tags = listOf("chat", "text"),
            ),
        ),
    )

    /**
     * Proceso `SendMessage`: envíe texto, espere a que se complete la generación y devuelva los resultados de la tarea.
     *
     * @param params Texto que contiene mensaje.partes
     * @return Tarea completada; falló/La tarea fallida se devuelve después del tiempo de espera (no se genera ninguna excepción y está empaquetada por la capa JSON-RPC)
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught", "CyclomaticComplexMethod")
    suspend fun sendMessage(params: MessageSendParams): A2aTask = sendMutex.withLock {
        val text = params.message.parts.mapNotNull { it.text }.joinToString("\n").trim()
        val taskId = params.taskId?.takeIf { it.isNotBlank() }
            ?: params.message.messageId?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString()
        if (text.isEmpty()) {
            return@withLock record(A2aTask(id = taskId, state = A2aTaskState.FAILED, error = "empty message text"))
        }
        if (text.length > MAX_MESSAGE_CHARS) {
            return@withLock record(A2aTask(id = taskId, state = A2aTaskState.FAILED, error = "message too long"))
        }
        return@withLock try {
            // A2A session keys are handler-owned and map to Agent-tab sessions. Arbitrary user
            // session IDs are rejected; no switchSession/updateInput/send can touch the UI.
            val requestedContextId = params.message.contextId?.takeIf { it.isNotBlank() }
            val legacySessionKey = params.message.sessionId?.takeIf { it.isNotBlank() }
            val isNewContext = requestedContextId == null && legacySessionKey == null
            val externalContextId = requestedContextId ?: legacySessionKey ?: "ctx-${java.util.UUID.randomUUID()}"
            val sessionId = contexts.get(externalContextId) ?: run {
                // contextId is an A2A-owned namespace and may be first seen here; the legacy
                // Muse sessionId extension is accepted only if this handler created/mapped it.
                if (!isNewContext && requestedContextId == null) {
                    return@withLock record(A2aTask(id = taskId, state = A2aTaskState.FAILED, error = "unknown A2A session id"))
                }
                val created = withContext(Dispatchers.IO) { sessionRepo.createAgentSession("default") }
                val evicted = contexts.put(externalContextId, created)
                if (evicted != null) {
                    // Only sessions created by this handler enter the registry, so eviction cannot
                    // delete a user-owned session. Delete out of band to bound hidden DB growth.
                    withContext(Dispatchers.IO) { runCatching { sessionRepo.deleteSession(evicted) } }
                }
                created
            }
            val session = sessionRepo.getSessionById(sessionId)
                ?: return@withLock record(A2aTask(id = taskId, state = A2aTaskState.FAILED, error = "A2A session expired"))
            if (!session.isAgentSession) {
                contexts.remove(externalContextId, sessionId)
                return@withLock record(A2aTask(id = taskId, state = A2aTaskState.FAILED, error = "session is not A2A-owned"))
            }

            taskSessions[taskId] = sessionId
            currentCoroutineContext()[Job]?.let { taskJobs[taskId] = it }
            record(A2aTask(id = taskId, contextId = externalContextId, state = A2aTaskState.WORKING))
            val reply = withTimeout(GENERATION_TIMEOUT_MS) {
                // A2A deliberately uses the Tasker-style headless path: no UI state, no tools,
                // no approvals, no foreground-session switching. Device actions remain in-app only.
                headlessGenerator.complete(sessionId, text).ifBlank { "(empty response)" }
            }
            check(!io.zer0.common.ProcessWriteGate.restoring) { "backup restore in progress" }
            sessionRepo.appendMessage(sessionId, UIMessage(role = MessageRole.USER, content = text))
            sessionRepo.appendMessage(sessionId, UIMessage(role = MessageRole.ASSISTANT, content = reply))
            record(
                A2aTask(
                    id = taskId,
                    contextId = externalContextId,
                    state = A2aTaskState.COMPLETED,
                    artifacts = listOf(A2aArtifact(name = "response", parts = listOf(A2aPart(kind = "text", text = reply)))),
                ),
            )
        } catch (e: TimeoutCancellationException) {
            Logger.w(TAG, "A2A SendMessage tiempo de espera: ${e.message}")
            record(A2aTask(id = taskId, state = A2aTaskState.FAILED, error = "generation timeout"))
        } catch (e: CancellationException) {
            if (taskRegistry.isCancelable(taskId)) {
                record(A2aTask(id = taskId, state = A2aTaskState.CANCELED))
            }
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "A2A SendMessage falló: ${e.message}", e)
            record(A2aTask(id = taskId, state = A2aTaskState.FAILED, error = e.message ?: "internal error"))
        }
    }

    /** Proceso `GetTask`: revisa los resultados de las tareas recientes. */
    fun getTask(taskId: String): A2aTask? = taskRegistry.get(taskId)

    /** Cancele únicamente las tareas registradas por este controlador y aún en ejecución; desconocido/El taskId final devuelve nulo y no afecta a la generación de la interfaz de usuario. */
    @Suppress("ReturnCount")
    fun cancelTask(taskId: String): A2aTask? {
        val current = taskRegistry.get(taskId) ?: return null
        if (current.state != A2aTaskState.WORKING || !taskSessions.containsKey(taskId)) return null
        val job = taskJobs[taskId] ?: return null
        // Headless A2A generation is owned by this request coroutine; cancel it, never a UI/global generation.
        job.cancel(CancellationException("A2A CancelTask"))
        return record(current.copy(state = A2aTaskState.CANCELED, error = null))
    }

    /** Grabar instantánea de la tarea (limitada). */
    private fun record(task: A2aTask): A2aTask {
        val recorded = taskRegistry.record(task)
        if (task.state != A2aTaskState.WORKING) {
            // Las tareas completadas ya no se pueden cancelar; Las asignaciones de sesiones se conservan con el libro de tareas delimitado.,Sólo para auditoría de resultados/uso del estado.
            taskSessions.remove(task.id)
            taskJobs.remove(task.id)
        }
        return recorded
    }

    companion object {
        const val MAX_MESSAGE_CHARS = 16_000
        private const val TAG = "A2aHandler"
        const val GENERATION_TIMEOUT_MS = 300_000L
    }
}
