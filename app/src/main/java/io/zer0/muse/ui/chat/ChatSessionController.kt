package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.chat.PendingToolCallStore
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.rag.SessionAttachmentService
import io.zer0.muse.tools.BrowserManagerRegistry
import io.zer0.muse.tools.WeakToolUseDetector
import io.zer0.muse.ui.ChatStreamPhase
import io.zer0.muse.ui.SessionMemoryCache
import io.zer0.muse.ui.common.feedback.MuseToast
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/**
 * v1.x: Controlador CRUD de sesión separado de ChatViewModel.
 *
 * Responsabilidades(Lista de efectos secundarios de la sesión + mantenimiento del ciclo de vida de la sesión,No se carga ningún mensaje/generar):
 *  - renameSession / setSessionArchived / deleteSession / setSessionIgnoreMemory / forkSessionFromMessage
 *  - Eliminación de sesión/Al presentar:Limpiar la instancia del navegador y la memoria caché de mensajes,y borrar/Cambiar a la primera sesión restante al archivar la sesión actual
 *  - Después de cambiar el interruptor de memoria [SessionFlowBridge.refreshContext] actualizar contexto
 *
 * Llamadas de responsabilidad cruzada(Carga de mensajes, actualización de contexto, desconexión de transmisión e informes de fallas de bifurcación)por [SessionFlowBridge] inyectar,
 * No hay dependencia inversa de ChatViewModel. compartir estado ir [ChatStateAccessor],StateFlow no se copia.
 */
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
internal class ChatSessionController(
    private val accessor: ChatStateAccessor,
    private val sessionRepository: SessionRepository,
    private val sessionMemoryCache: SessionMemoryCache,
    private val browserManagerRegistry: BrowserManagerRegistry?,
    private val bridge: SessionFlowBridge,
    private val sessionDeps: SessionDeps,
    /** Cambie la protección del número de serie. Sólo compartiendo la misma instancia con la persona que llama puede el“Conmutación continua en la misma interfaz”Organizar en orden. */
    private val switchGuard: SessionSwitchGuard = SessionSwitchGuard(),
) {
    fun renameSession(sessionId: String, title: String) {
        accessor.coroutineScope.launch {
            sessionRepository.renameSession(sessionId, title)
        }
    }

    /** v0.45: Alternar el estado de archivo de la sesión. Cambiar a la primera sesión restante al archivar la sesión actual;Borrar estado cuando no quedan sesiones,No se crea ninguna nueva sesión. */
    fun setSessionArchived(sessionId: String, archived: Boolean) {
        accessor.coroutineScope.launch {
            if (archived) {
                // P0-5: Archivar y detener — Evitar que se genere esta sesión + supresión de escritura,Evite que las devoluciones de llamadas de streaming se escriban en archivos
                // sesión y después de reanudar"resurrección"(El mismo mecanismo que B-1 para eliminar un solo mensaje.)
                bridge.stopGenerationForSession(sessionId)
                bridge.suppressSessionWrites(sessionId)
            }
            sessionRepository.setArchived(sessionId, archived)
            if (accessor.snapshot.currentSessionId == sessionId && archived) {
                val remaining = sessionRepository.observeSessions().first()
                val target = remaining.firstOrNull()
                if (target != null) {
                    switchSession(target.id)
                } else {
                    accessor.updateMessages { emptyList() }
                    accessor.update { it.copy(currentSessionId = null) }
                }
            }
        }
    }

    fun deleteSession(sessionId: String) {
        accessor.coroutineScope.launch {
            // P0-5: Eliminar y detener — Primero detenga la sesión en tránsito + supresión de escritura,Eliminación temporal nuevamente,Evitar devoluciones de llamadas en streaming
            // Continuar a la conversación eliminada temporalmente upsert("resurrección" + Generación residual después de la recuperación.)
            bridge.stopGenerationForSession(sessionId)
            bridge.suppressSessionWrites(sessionId)
            sessionRepository.softDeleteSession(sessionId)
            // v1.x: Libera la instancia del navegador para la sesión cuando se elimina la sesión.
            browserManagerRegistry?.let { registry ->
                resultOf { registry.closeSession(sessionId) }
                    .onError { msg, _ -> Logger.w("ChatVM", "closeSession browser falló: $msg") }
            }
            // Experimental:Recuperación de archivos adjuntos de sesión —— Cuando se elimina una sesión, también se borra el índice de archivos adjuntos de la sesión.,Evite los residuos de trozos
            runCatching {
                GlobalContext.get().get<SessionAttachmentService>()
                    .dropSessionAttachments(sessionId = sessionId, keepIndex = false)
            }.onFailure { Logger.w("ChatVM", "Error en el índice de archivos adjuntos de la sesión de limpieza: ${it.message}") }
            // v1.93+: Eliminado de la memoria caché LRU,Evite conservar copias de mensajes de conversaciones eliminadas(Evite pérdidas de memoria y lecturas sucias)
            sessionMemoryCache.remove(sessionId)
            // v2.6.0 (AUD-UI-F13): Borrar instantáneas pendientes para esta sesión —— TodoTool.sessionTodos(singleton)adentro
            // TodoList nunca soltar,Para uso a largo plazo, guarde una copia de cada sesión que utilice todo_write.,Y la conversación ha sido eliminada.
            // Todavía se puede leer a través de getTodos. Esto se limpia sincrónicamente con la eliminación de la sesión.
            resultOf { io.zer0.muse.tools.TodoTool.clearSession(sessionId) }
                .onError { msg, _ -> Logger.w("ChatVM", "Error en la sesión de limpieza pendiente: $msg") }
            // v2.6.7: La copia en la biblioteca también debe eliminarse; de ​​lo contrario, al recrear una sesión con el mismo nombre se leerá la antigua tarea pendiente de la sesión eliminada.
            runCatching {
                org.koin.core.context.GlobalContext.get()
                    .get<io.zer0.muse.data.todo.SessionTodoRepository>()
                    .delete(sessionId)
            }.onFailure { Logger.w("ChatVM", "No se pudo eliminar el registro de tareas pendientes: ${it.message}") }
            // v2.6.7 fix (AUDIT F14): También se debe borrar el registro de la herramienta de sesión.
            // La implementación anterior solo borraba clearSearchableSkills (llamado cuando finaliza la sesión),
            // Y clearVisibleTools y SessionToolLoadRegistry.clear no tienen llamadas ——
            // La sesión eliminada aún deja una herramienta visible configurada en la memoria. Cuando se reconstruya la sesión con el mismo nombre, la carga anterior se heredará al inicio.
            runCatching {
                org.koin.core.context.GlobalContext.get()
                    .get<io.zer0.muse.tools.ToolRegistry>()
                    .clearVisibleTools(sessionId)
            }.onFailure { Logger.w("ChatVM", "Error al limpiar el conjunto visible de herramientas: ${it.message}") }
            runCatching { io.zer0.muse.tools.SessionToolLoadRegistry.clear(sessionId) }
                .onFailure { Logger.w("ChatVM", "La herramienta de limpieza no pudo cargar registros: ${it.message}") }
            if (accessor.snapshot.currentSessionId == sessionId) {
                val remaining = sessionRepository.observeSessions().first()
                val target = remaining.firstOrNull()
                if (target != null) {
                    switchSession(target.id)
                } else {
                    accessor.updateMessages { emptyList() }
                    accessor.update { it.copy(currentSessionId = null) }
                }
            }
        }
    }

    fun setSessionIgnoreMemory(ignore: Boolean) {
        val st = accessor.snapshot
        val sessionId = (if (st.isAgentMode) st.agentSessionId else st.currentSessionId) ?: return
        accessor.coroutineScope.launch {
            sessionRepository.setSessionIgnoreMemory(sessionId, ignore)
            // Actualizar el estado de la sesión local(Conmutador VacuumChatGuide del controlador + clave de caché del mensaje del sistema)
            accessor.update { state ->
                state.copy(
                    sessions =
                    state.sessions.map {
                        if (it.id == sessionId) it.copy(ignoreMemory = ignore) else it
                    },
                )
            }
            // El cambio de memoria afecta las instantáneas estáticas,actualizar contexto
            bridge.refreshContext()
        }
    }

    /**
     * v1.58: Bifurca la conversación del mensaje especificado — Crear una sesión bifurcada en el repositorio,Cambie a una nueva sesión si tiene éxito.
     */
    @Suppress("TooGenericExceptionCaught")
    fun forkSessionFromMessage(messageId: Uuid) {
        val snapshot = accessor.snapshot
        val sourceSessionId =
            (if (snapshot.isAgentMode) snapshot.agentSessionId else snapshot.currentSessionId) ?: return
        if (accessor.snapshot.isStreaming) bridge.detachStreaming()
        accessor.coroutineScope.launch {
            try {
                val newId = sessionRepository.forkSession(sourceSessionId, messageId.toString())
                if (newId != null) {
                    switchSession(newId)
                    // U-23: fork Brinde comentarios claros después del éxito,Evite el cambio silencioso que confunde a los usuarios
                    MuseToast.show(sessionDeps.appContext.getString(R.string.chat_fork_success))
                }
            } catch (e: Exception) {
                Logger.w("ChatViewModel", "forkSession failed: ${e.message}")
                bridge.onForkError(e)
            }
        }
    }

    /** Nueva sesión:Liberar sesión anterior → Crear por asistente predeterminado → Restablecer el estado de la interfaz de usuario → Actualizar el contexto.
     *  v2.x: [assistantIdOverride] Creado con el asistente especificado cuando no está vacío(Los socios se pueden organizar horizontalmente para iniciar rápidamente un chat.). */
    @Suppress("CyclomaticComplexMethod")
    fun createNewSession(onReady: (() -> Unit)? = null, assistantIdOverride: String? = null) {
        if (accessor.snapshot.isStreaming) bridge.detachStreaming()
        // Phase 8.7: Detenerse al cambiar de sesión TTS(Evite continuar leyendo entre sesiones.)
        sessionDeps.onStopTts()
        // v1.91: lanzamiento de transmisión ASR(Evite seguir ocupando el micrófono entre sesiones.)
        sessionDeps.onDisposeAsr()
        // Notificar ticker: la sesión anterior finaliza
        sessionDeps.onNotifySessionEnd()
        // v1.x: Limpiar sesiones antiguas"Esta sesión permite"caché temporal
        sessionDeps.currentSessionIdForApproval()?.let { sessionDeps.sessionPermissionStore.clearSession(it) }
        val currentSession = accessor.snapshot.currentSessionId
        // Conteo de referencias:Liberar sesión anterior(La nueva identificación de sesión se crea dentro del bloque asíncrono y luego acquire)
        currentSession?.let { sessionDeps.sessionManager.release(it) }
        accessor.coroutineScope.launch {
            // v1.0.63: Las nuevas tareas utilizan el asistente predeterminado en la configuración
            // v2.x: assistantIdOverride Creado con el asistente especificado cuando no está vacío(Entrada rápida horizontal de socio)
            val currentAssistantId =
                assistantIdOverride
                    ?: sessionDeps.settings.defaultAssistantIdFlow.first().ifBlank { "default" }
            val id = sessionRepository.createSession(assistantId = currentAssistantId)
            // v1.x: El nuevo modo de permiso de sesión sigue el valor predeterminado global
            val permissionMode =
                sessionDeps.sessionPermissionStore.getMode(
                    id,
                    sessionDeps.settings.defaultSessionPermissionModeFlow.first(),
                )
            sessionDeps.sessionManager.acquire(id)
            val assistant =
                sessionDeps.assistantRepository.getById(currentAssistantId)
                    ?: sessionDeps.assistantRepository.getById("default")
            sessionDeps.stateStore.messages.value = emptyList()
            accessor.update {
                it.copy(
                    currentSessionId = id,
                    activeProviderId = sessionDeps.globalActiveProviderId(),
                    selectedModelId = sessionDeps.globalSelectedModelId(),
                    input = "",
                    hasDraft = false,
                    errors = emptyList(),
                    isDrawerOpen = false,
                    currentAssistant = assistant,
                    deepThinkingEnabled = it.chatPreferences.defaultDeepThinking,
                    taskCards = emptyMap(),
                    toolCallHistory = emptyList(),
                    agentPlans = emptyMap(),
                    inputHistory = emptyList(),
                    inputHistoryIndex = null,
                    visionAssistedMessageIds = emptySet(),
                    visionProgress = null,
                    sessionPermissionMode = permissionMode,
                )
            }
            onReady?.invoke()
            // v0.45: Actualizar la ocupación del token de contexto(Los mensajes de nueva sesión están vacíos.,cargar solo contextWindow)
            bridge.refreshContext()
            // R-UI-02: Sincronice el foco de la vista persistente después de crear una nueva sesión.
            resultOf { sessionDeps.settings.saveViewedSessionId(id) }
                .onError { msg, _ -> Logger.w("ChatVM", "saveViewedSessionId falló: $msg") }
        }
    }

    /** reiniciar contexto:Liberar sesión anterior → Crear nueva tarea/Agent sesión → Restablecer el estado de la interfaz de usuario. */
    fun restartContext() {
        if (accessor.snapshot.isStreaming) bridge.detachStreaming()
        sessionDeps.onStopTts()
        sessionDeps.onNotifySessionEnd()
        // v1.x: Limpiar sesiones antiguas"Esta sesión permite"caché temporal
        sessionDeps.currentSessionIdForApproval()?.let { sessionDeps.sessionPermissionStore.clearSession(it) }
        // Conteo de referencias:Liberar sesión anterior
        sessionDeps.currentSessionIdForApproval()?.let { sessionDeps.sessionManager.release(it) }
        accessor.coroutineScope.launch {
            val currentAssistantId = accessor.snapshot.currentAssistant?.id ?: "default"
            // v1.28: Agent Crear una sesión de Agente en modo,No contamines la lista de tareas
            val id =
                if (accessor.snapshot.isAgentMode) {
                    sessionRepository.createAgentSession(assistantId = currentAssistantId)
                } else {
                    sessionRepository.createSession(assistantId = currentAssistantId)
                }
            sessionDeps.sessionManager.acquire(id)
            val assistant =
                sessionDeps.assistantRepository.getById(currentAssistantId)
                    ?: sessionDeps.assistantRepository.getById("default")
            accessor.update {
                if (it.isAgentMode) {
                    sessionDeps.stateStore.messages.value = emptyList()
                    it.copy(
                        agentSessionId = id,
                        input = "",
                        errors = emptyList(),
                        isDrawerOpen = false,
                        currentAssistant = assistant,
                        taskCards = emptyMap(),
                        toolCallHistory = emptyList(),
                        agentPlans = emptyMap(),
                        visionAssistedMessageIds = emptySet(),
                        visionProgress = null,
                    )
                } else {
                    sessionDeps.stateStore.messages.value = emptyList()
                    it.copy(
                        currentSessionId = id,
                        input = "",
                        errors = emptyList(),
                        isDrawerOpen = false,
                        currentAssistant = assistant,
                        taskCards = emptyMap(),
                        toolCallHistory = emptyList(),
                        agentPlans = emptyMap(),
                        visionAssistedMessageIds = emptySet(),
                        visionProgress = null,
                    )
                }
            }
            bridge.refreshContext()
            accessor.update {
                it.copy(toast = sessionDeps.appContext.getString(R.string.err_chat_context_restarted_toast))
            }
            // R-UI-02: Sincronice el foco de la vista persistente después de reiniciar el contexto en modo tarea.
            if (!accessor.snapshot.isAgentMode) {
                resultOf { sessionDeps.settings.saveViewedSessionId(id) }
                    .onError { msg, _ -> Logger.w("ChatVM", "saveViewedSessionId falló: $msg") }
            }
        }
    }

    /** v1.97 gap8: Enviar texto a una nueva conversación(Crear atómicamente una nueva sesión + completar la entrada + activar el envío). */
    fun sendToNewChat(text: String, onReady: (() -> Unit)? = null) {
        if (accessor.snapshot.isStreaming) bridge.detachStreaming()
        sessionDeps.onStopTts()
        sessionDeps.onDisposeAsr()
        sessionDeps.onNotifySessionEnd()
        // v1.x: Limpiar sesiones antiguas"Esta sesión permite"caché temporal
        sessionDeps.currentSessionIdForApproval()?.let { sessionDeps.sessionPermissionStore.clearSession(it) }
        val currentSession = accessor.snapshot.currentSessionId
        val currentInput = accessor.snapshot.input
        // Conteo de referencias:Liberar sesión anterior
        currentSession?.let { sessionDeps.sessionManager.release(it) }
        accessor.coroutineScope.launch {
            if (currentSession != null && currentInput.isNotBlank()) {
                // Los borradores se colocan en la base de datos por sesión (la implementación anterior escribe en el DataStore y nunca lo lee, lo que significa que solo guarda pero no restaura)
                resultOf { sessionRepository.saveInputDraft(currentSession, currentInput) }
                    .onError { msg, _ ->
                        Logger.w("ChatVM", "saveInputDraft failed: $msg")
                        MuseToast.show(sessionDeps.appContext.getString(R.string.chat_draft_save_failed))
                    }
            }
            // v1.0.63: Las nuevas tareas utilizan el asistente predeterminado en la configuración
            val currentAssistantId = sessionDeps.settings.defaultAssistantIdFlow.first().ifBlank { "default" }
            val id = sessionRepository.createSession(assistantId = currentAssistantId)
            val permissionMode =
                sessionDeps.sessionPermissionStore.getMode(
                    id,
                    sessionDeps.settings.defaultSessionPermissionModeFlow.first(),
                )
            sessionDeps.sessionManager.acquire(id)
            val assistant =
                sessionDeps.assistantRepository.getById(currentAssistantId)
                    ?: sessionDeps.assistantRepository.getById("default")
            sessionDeps.stateStore.messages.value = emptyList()
            accessor.update {
                it.copy(
                    currentSessionId = id,
                    activeProviderId = sessionDeps.globalActiveProviderId(),
                    selectedModelId = sessionDeps.globalSelectedModelId(),
                    input = text,
                    hasDraft = false,
                    errors = emptyList(),
                    isDrawerOpen = false,
                    currentAssistant = assistant,
                    deepThinkingEnabled = it.chatPreferences.defaultDeepThinking,
                    taskCards = emptyMap(),
                    toolCallHistory = emptyList(),
                    agentPlans = emptyMap(),
                    visionAssistedMessageIds = emptySet(),
                    visionProgress = null,
                    sessionPermissionMode = permissionMode,
                )
            }
            bridge.refreshContext()
            onReady?.invoke()
            sessionDeps.onSend()
            // R-UI-02: Sincronice el foco de vista persistente al crear una nueva sesión y enviarla.
            resultOf { sessionDeps.settings.saveViewedSessionId(id) }
                .onError { msg, _ -> Logger.w("ChatVM", "saveViewedSessionId falló: $msg") }
        }
    }

    /** v1.28: Establecer el modo de pestaña Agente(restaurar/Crear una sesión de agente independiente;Reanudar la sesión de tareas al salir). */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun setAgentMode(enabled: Boolean, requestedSessionId: String? = null) {
        // v2.x: número de serie del interruptor — Caducado(Reemplazado por una palanca más nueva)Los resultados de la carga asincrónica se descartan antes del envío.,Evitar conversaciones cruzadas en la sesión
        val switchToken = switchGuard.begin()
        if (accessor.snapshot.isStreaming) bridge.detachStreaming()
        sessionDeps.onStopTts()
        sessionDeps.onDisposeAsr()
        sessionDeps.onNotifySessionEnd()
        val prevSessionId = sessionDeps.currentSessionIdForApproval()
        if (enabled) {
            accessor.update { it.copy(isSwitchingSession = true) }
            accessor.coroutineScope.launch {
                try {
                    val preferredAgentId =
                        sessionDeps.settings.proactiveMessageConfigFlow.first()
                            .agentId.ifBlank { "default" }
                    val requestedAgentSession =
                        requestedSessionId
                            ?.takeIf { it.isNotBlank() }
                            ?.let { sessionRepository.getSessionById(it) }
                            ?.takeIf { it.isAgentSession && it.deletedAt == null }
                    val agentSession =
                        requestedAgentSession
                            ?: sessionRepository.getRecentAgentByAssistant(preferredAgentId, 1).firstOrNull()
                            ?: run {
                                // v2.x(comentarios de los usuarios): No más volver a caer"Última sesión de cualquier asistente"(getLatestAgentSession).
                                // Si el asistente de destino no tiene sesiones históricas después de cambiar de asistente, la lógica anterior obtendrá la antigua sala predeterminada (u otros asistentes).
                                // Se muestra como"Agent Encendí el asistente en configuración pero no surte efecto". Ahora haga clic directamente en el asistente de destino para crear una nueva sesión.
                                val newId = sessionRepository.createAgentSession(preferredAgentId)
                                Logger.i(
                                    "ChatVM",
                                    "setAgentMode: El asistente objetivo no tiene salas históricas, cree una nueva sesión de agente(assistant=$preferredAgentId)",
                                )
                                sessionRepository.getSessionById(newId)
                            }
                    val sessionId =
                        agentSession?.id
                            ?: sessionRepository.createAgentSession(preferredAgentId)
                    prevSessionId?.let { sessionDeps.sessionManager.release(it) }
                    sessionDeps.sessionManager.acquire(sessionId)
                    val permissionMode =
                        sessionDeps.sessionPermissionStore.getMode(
                            sessionId,
                            sessionDeps.settings.defaultSessionPermissionModeFlow.first(),
                        )
                    val (messages, hasMore) = sessionDeps.messageController.loadMessagesPaged(sessionId)
                    if (switchGuard.isStale(switchToken)) {
                        // Reemplazado por alternancia actualizada:Suelta esta vez acquire,Descartar resultados de carga
                        sessionDeps.sessionManager.release(sessionId)
                        Logger.i("ChatVM", "setAgentMode(true) Resultados caducados,descartar: $sessionId")
                        return@launch
                    }
                    val assistantId = sessionRepository.getAssistantId(sessionId)
                    val assistant =
                        sessionDeps.assistantRepository.getById(assistantId)
                            ?: sessionDeps.assistantRepository.getById("default")
                    val agentBackgroundStreaming = sessionDeps.chatGenerationManager.isStreaming(sessionId)
                    // No finalices un plan activo mientras todavía se esté transmitiendo en segundo plano.
                    val restoredAgentPlans =
                        sessionDeps.messageController.restoreAgentPlansForSession(
                            sessionId,
                            messages,
                            settleUnfinished = !agentBackgroundStreaming,
                        )
                    sessionDeps.stateStore.messages.value = messages
                    accessor.update {
                        it.copy(
                            isAgentMode = true,
                            agentSessionId = sessionId,
                            isSwitchingSession = false,
                            isStreaming = agentBackgroundStreaming,
                            isWaitingFirstToken =
                            agentBackgroundStreaming && messages
                                .lastOrNull { msg -> msg.role == MessageRole.ASSISTANT }
                                ?.let { msg -> msg.content.isBlank() && msg.toolCalls.isNullOrEmpty() }
                                == true,
                            currentAssistant = assistant,
                            errors = emptyList(),
                            hasMoreHistory = hasMore,
                            isLoadingMore = false,
                            lastHistoryLoadCount = 0,
                            replyingTo = null,
                            replyQuoteOverride = null,
                            taskCards = emptyMap(),
                            toolCallHistory = emptyList(),
                            agentPlans = restoredAgentPlans,
                            visionAssistedMessageIds = emptySet(),
                            visionProgress = null,
                            sessionPermissionMode = permissionMode,
                            // v2.2.1: Agent Restaurar la página a su posición anterior.;nueva sesión(El caché no pertenece a esta sesión.)antes de volver al final de la lista
                            agentListFirstVisibleItemIndex =
                            if (it.agentScrollOwnerSessionId == sessionId &&
                                it.agentListFirstVisibleItemIndex >= 0
                            ) {
                                it.agentListFirstVisibleItemIndex
                            } else {
                                messages.lastIndex.coerceAtLeast(0)
                            },
                            agentListFirstVisibleItemScrollOffset =
                            if (it.agentScrollOwnerSessionId == sessionId) {
                                it.agentListFirstVisibleItemScrollOffset
                            } else {
                                0
                            },
                            agentScrollOwnerSessionId = sessionId,
                            isSessionLocked = true,
                        )
                    }
                    val model = resultOf { sessionDeps.settings.getSelectedModel() }.getOrNull()
                    // v2.6.7 fix (AUDIT F7): Agent Tab Calienta también la lista de tareas pendientes ——
                    // La implementación anterior solo lo hace en switchSession,Agent La barra de tareas pendientes permanece vacía después de reiniciar la sesión.
                    accessor.coroutineScope.launch {
                        runCatching {
                            val repo =
                                org.koin.core.context.GlobalContext.get()
                                    .get<io.zer0.muse.data.todo.SessionTodoRepository>()
                            val todoList = repo.load(sessionId)
                            if (!switchGuard.isStale(switchToken)) {
                                io.zer0.muse.tools.TodoTool.syncFromDatabase(sessionId, todoList)
                            }
                        }.onFailure { Logger.w("ChatVM", "No se pudo calentar el Agente para hacer: ${it.message}") }
                    }
                    val weakHint = WeakToolUseDetector.getWeakToolHint(model)
                    accessor.update {
                        it.copy(isWeakToolModel = weakHint != null, weakToolHint = weakHint)
                    }
                    bridge.refreshContext()
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (switchGuard.isStale(switchToken)) {
                        Logger.i("ChatVM", "setAgentMode(true) Rama fallida caducada,ignorar")
                        return@launch
                    }
                    // v2.0 Reparación: No puede morir silenciosamente cuando falla el cambio(De lo contrario, la pestaña Agente siempre girará en círculos.).
                    // volver al estado seguro: Haz tu mejor esfuerzo para crear una sesión de canje,Asegúrese de que la interfaz sea utilizable y conmutable.
                    Logger.e("ChatVM", "setAgentMode falló,volver al estado seguro", e)
                    val fallbackId = resultOf { sessionRepository.createAgentSession("default") }.getOrNull()
                    val fallbackAssistant =
                        runCatching {
                            sessionDeps.assistantRepository.getById("default")
                        }.getOrNull()
                    accessor.update {
                        it.copy(
                            isAgentMode = true,
                            agentSessionId = fallbackId,
                            isSwitchingSession = false,
                            isSessionLocked = true,
                            currentAssistant = fallbackAssistant,
                        )
                    }
                } finally {
                    // No quedan caminos"Conmutación"Estado de carga;Las tareas caducadas no deben borrar el estado de carga recién cambiado
                    if (!switchGuard.isStale(switchToken)) {
                        accessor.update { it.copy(isSwitchingSession = false) }
                    }
                }
            }
        } else {
            accessor.update {
                it.copy(
                    isAgentMode = false,
                    agentSessionId = null,
                    taskCards = emptyMap(),
                    toolCallHistory = emptyList(),
                    agentPlans = emptyMap(),
                    visionAssistedMessageIds = emptySet(),
                    visionProgress = null,
                    isSessionLocked = false,
                    isWeakToolModel = false,
                    weakToolHint = null,
                    agentModeHint = null,
                )
            }
            prevSessionId?.let { sessionDeps.sessionManager.release(it) }
            accessor.snapshot.currentSessionId?.let { sid ->
                sessionDeps.sessionManager.acquire(sid)
                accessor.coroutineScope.launch {
                    val (messages, hasMore) = sessionDeps.messageController.loadMessagesPaged(sid)
                    if (switchGuard.isStale(switchToken)) {
                        // Reemplazado por alternancia actualizada:Suelta esta vez acquire,Descartar resultados de carga
                        sessionDeps.sessionManager.release(sid)
                        Logger.i("ChatVM", "setAgentMode(false) Resultados caducados,descartar: $sid")
                        return@launch
                    }
                    val permissionMode =
                        sessionDeps.sessionPermissionStore.getMode(
                            sid,
                            sessionDeps.settings.defaultSessionPermissionModeFlow.first(),
                        )
                    val assistantId = sessionRepository.getAssistantId(sid)
                    val assistant =
                        sessionDeps.assistantRepository.getById(assistantId)
                            ?: sessionDeps.assistantRepository.getById("default")
                    val taskBackgroundStreaming = sessionDeps.chatGenerationManager.isStreaming(sid)
                    val restoredAgentPlans =
                        sessionDeps.messageController.restoreAgentPlansForSession(
                            sid,
                            messages,
                            settleUnfinished = !taskBackgroundStreaming,
                        )
                    sessionDeps.stateStore.messages.value = messages
                    // v2.6.7 Reparación (comentarios de los usuarios: eliminar el fondo/tachar el fondo y volver al principio de la lista):
                    // Se da prioridad a restaurar la sesión donde se quedó la última vez; sólo se descarta el último si nunca se ha grabado antes.
                    // La implementación anterior escribe lastIndex incondicionalmente, por lo que:
                    //  - Cambiar de sesión normalmente → Saltar siempre al fondo (no a la posición donde se fue el usuario);
                    //  - Cuando el proceso finaliza y se reconstruye, los mensajes aún no se han cargado.lastIndex=-1
                    //    coerce mas tarde 0 → Volver arriba. Ambas actuaciones parten de esta línea de trabajo.
                    val restoredScroll =
                        resultOf { sessionDeps.settings.getChatScrollPosition(sid) }.getOrNull()
                    val restoredIndex = restoredScroll?.first
                    val restoredOffset = restoredScroll?.second ?: 0
                    accessor.update {
                        it.copy(
                            currentAssistant = assistant,
                            isStreaming = taskBackgroundStreaming,
                            isWaitingFirstToken =
                            taskBackgroundStreaming && messages
                                .lastOrNull { msg -> msg.role == MessageRole.ASSISTANT }
                                ?.let { msg -> msg.content.isBlank() && msg.toolCalls.isNullOrEmpty() }
                                == true,
                            hasMoreHistory = hasMore,
                            isLoadingMore = false,
                            lastHistoryLoadCount = 0,
                            agentPlans = restoredAgentPlans,
                            sessionPermissionMode = permissionMode,
                            isSwitchingSession = false,
                            listFirstVisibleItemIndex =
                            if (restoredIndex != null && restoredIndex in 0..messages.lastIndex) {
                                restoredIndex
                            } else {
                                messages.lastIndex.coerceAtLeast(0)
                            },
                            listFirstVisibleItemScrollOffset = if (restoredIndex != null) restoredOffset else 0,
                        )
                    }
                    bridge.refreshContext()
                    resultOf { sessionDeps.settings.saveViewedSessionId(sid) }
                        .onError { msg, _ -> Logger.w("ChatVM", "saveViewedSessionId falló: $msg") }
                }
            }
        }
    }

    /** Cambiar a la sesión especificada:limpiar → liberar/Obtener referencia → Cargar mensaje/planear/Permisos → Reanudar aprobación/outbox. */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ComplexCondition")
    fun switchSession(sessionId: String) {
        // v2.x: número de serie del interruptor — Caducado(Reemplazado por una palanca más nueva)Los resultados de la carga asincrónica se descartan antes del envío.,Evitar conversaciones cruzadas en la sesión
        val switchToken = switchGuard.begin()
        if (accessor.snapshot.isStreaming) bridge.detachStreaming()
        sessionDeps.onStopTts()
        sessionDeps.onDisposeAsr()
        sessionDeps.onNotifySessionEnd()
        // v1.201: Cambiar sesión borra el enlace delegado + estado suspendido
        sessionDeps.onClearDelegation()
        // v1.x: Limpiar sesiones antiguas"Esta sesión permite"caché temporal
        val approvalSession = sessionDeps.currentSessionIdForApproval()
        approvalSession?.let { sessionDeps.sessionPermissionStore.clearSession(it) }
        val currentSession = accessor.snapshot.currentSessionId
        if (currentSession != null && currentSession != sessionId) {
            // Clearing the approval UI alone would leave the old generation suspended forever.
            sessionDeps.onCancelPendingApprovals(currentSession)
        }
        val currentInput = accessor.snapshot.input
        // Conteo de referencias:Liberar sesión anterior + obtener nueva sesión
        if (currentSession != null && currentSession != sessionId) {
            sessionDeps.sessionManager.release(currentSession)
        }
        sessionDeps.sessionManager.acquire(sessionId)
        // C3: Registrar el historial de navegación reciente
        accessor.coroutineScope.launch { sessionDeps.settings.recordSessionViewed(sessionId) }
        // v2.6.7: Calienta la instantánea de las tareas pendientes de la sesión desde la biblioteca —— Anteriormente, la lista de tareas pendientes solo estaba en la memoria y estaba vacía cuando la dejaba y regresaba.
        // Utilice switchToken para evitar la conversación cruzada de sesiones: si se ha cambiado a otra sesión cuando se vuelve a cargar, se descartará.
        // v2.6.7 fix (AUDIT F7): Usar en su lugar syncFromDatabase —— La implementación anterior se preparaba solo cuando no era nula,
        // Por lo tanto, "la biblioteca está vacía" nunca puede sobrescribir la lista anterior en la memoria (especialmente obvio después de restaurar la copia de seguridad).
        accessor.coroutineScope.launch {
            runCatching {
                val repo =
                    org.koin.core.context.GlobalContext.get()
                        .get<io.zer0.muse.data.todo.SessionTodoRepository>()
                val list = repo.load(sessionId)
                if (!switchGuard.isStale(switchToken)) {
                    io.zer0.muse.tools.TodoTool.syncFromDatabase(sessionId, list)
                }
            }.onFailure { Logger.w("ChatVM", "Sesión de calentamiento pendiente fallida: ${it.message}") }
        }
        // v1.93+: Almacene la instantánea del mensaje de la sesión actual en la caché LRU antes de cambiar(Si hay ramas variantes, no se almacenarán en caché.)
        if (currentSession != null && sessionDeps.stateStore.messages.value.isNotEmpty() &&
            sessionDeps.stateStore.conversationTree.value.userNodes.none {
                it.variants.size > 1 || it.currentVariant?.assistantNodes?.any { a -> a.variants.size > 1 } == true
            }
        ) {
            sessionMemoryCache.put(currentSession, sessionDeps.stateStore.messages.value)
        }
        accessor.coroutineScope.launch {
            if (currentSession != null && currentInput.isNotBlank()) {
                resultOf { sessionRepository.saveInputDraft(currentSession, currentInput) }
                    .onError { msg, _ ->
                        Logger.w("ChatVM", "saveInputDraft failed: $msg")
                        MuseToast.show(sessionDeps.appContext.getString(R.string.chat_draft_save_failed))
                    }
            }
        }
        accessor.coroutineScope.launch {
            // F-15: DeepLink control de objetivos — Regrese a la lista de sesiones si la sesión no existe.
            // Cuando no se pueda encontrar la biblioteca, eche otro vistazo a la lista de sesiones en la memoria: si existe en la lista, continúe cambiando.
            // De lo contrario, un error instantáneo en la consulta borrará el foco a nulo (lo que el usuario ve es "se hace clic y luego se recupera").
            val exists = resultOf { sessionRepository.getSessionById(sessionId) }.getOrNull() != null ||
                accessor.snapshot.sessions.any { it.id == sessionId }
            if (switchGuard.isStale(switchToken)) {
                // Reemplazado por alternancia actualizada:Suelta esta vez acquire,Descartar resultados de carga
                sessionDeps.sessionManager.release(sessionId)
                Logger.i("ChatVM", "switchSession Resultados caducados,descartar: $sessionId")
                return@launch
            }
            if (!exists) {
                Logger.w("ChatVM", "switchSession La sesión de destino no existe,Lista de sesiones de reversión: $sessionId")
                accessor.update {
                    it.copy(
                        currentSessionId = null,
                        isStreaming = false,
                        isWaitingFirstToken = false,
                        streamState = it.streamState.copy(phase = ChatStreamPhase.IDLE),
                    )
                }
                sessionDeps.stateStore.messages.value = emptyList()
                return@launch
            }
            // Abrir una conversación significa "leer": registre el mensaje activo más reciente en la conversación que aún está esperando respuesta como ABIERTO.
            // Los enlaces profundos de notificación, los teléfonos celulares pequeños y los chats principales convergen al final en esta ruta de conmutación, por lo que solo se conectan aquí una vez.
            // Si falla, simplemente lleve un registro: leer es más importante que llevar cuentas y no puede permitir que la omisión ralentice la carga de la página.
            // Si no puede obtener el programador (los mensajes activos no están habilitados/Koin no se inicia en el entorno de prueba único), simplemente omítalo.
            // No confíe en excepciones para controlar el proceso —— Una vez arrojado aquí, absorberá la recuperación de enfoque posterior.
            runCatching {
                org.koin.core.context.GlobalContext.get().get<io.zer0.muse.proactive.ProactiveScheduler>()
            }.getOrNull()?.let { proactive ->
                resultOf { proactive.onSessionOpened(sessionId) }
                    .onError { msg, _ -> Logger.w("ChatVM", "Error al marcar el mensaje activo como leído: $msg") }
            }
            val isBackgroundStreaming = sessionDeps.chatGenerationManager.isStreaming(sessionId)
            val cached = if (!isBackgroundStreaming) sessionMemoryCache.get(sessionId) else null
            val memoryCacheHit = cached != null
            val (messages, hasMore) =
                if (cached != null) {
                    cached to (cached.size >= MESSAGE_PAGE_SIZE)
                } else {
                    sessionDeps.messageController.loadMessagesPaged(sessionId)
                }
            val backgroundWaitingForOutput =
                isBackgroundStreaming && (
                    messages.lastOrNull { it.role == MessageRole.ASSISTANT }
                        ?.let { it.content.isBlank() && it.toolCalls.isNullOrEmpty() } == true
                    )
            val permissionMode =
                sessionDeps.sessionPermissionStore.getMode(
                    sessionId,
                    sessionDeps.settings.defaultSessionPermissionModeFlow.first(),
                )
            val assistantId = sessionRepository.getAssistantId(sessionId)
            val assistant =
                sessionDeps.assistantRepository.getById(assistantId)
                    ?: sessionDeps.assistantRepository.getById("default")
            // No finalices un plan activo mientras todavía se esté transmitiendo en segundo plano.
            val restoredAgentPlans =
                sessionDeps.messageController.restoreAgentPlansForSession(
                    sessionId,
                    messages,
                    settleUnfinished = !isBackgroundStreaming,
                )
            // Borrador de recuperación: al volver a una sesión, el contenido que no se envió la última vez volverá al cuadro de entrada.
            // Las sesiones que todavía se generan en segundo plano no se reanudan (para evitar confusiones con el contenido que se transmite).
            val restoredDraft = if (isBackgroundStreaming) {
                ""
            } else {
                resultOf { sessionRepository.loadInputDraft(sessionId) }.getOrNull().orEmpty()
            }
            accessor.update {
                sessionDeps.stateStore.messages.value = messages
                it.copy(
                    currentSessionId = sessionId,
                    activeProviderId = sessionDeps.activeProviderForSession(sessionId),
                    selectedModelId = sessionDeps.selectedModelForSession(sessionId),
                    // Recuperación de borrador: en la implementación anterior, la cadena vacía se escribe directamente aquí y el borrador se perderá para siempre después de volver a cambiar.
                    // En su lugar, lea el contenido no enviado guardado de la propia sesión (inyectado por recoveryInput arriba).
                    input = restoredDraft,
                    hasDraft = restoredDraft.isNotBlank(),
                    errors = emptyList(),
                    isDrawerOpen = false,
                    currentAssistant = assistant,
                    memoryCacheHit = memoryCacheHit,
                    deepThinkingEnabled = it.chatPreferences.defaultDeepThinking,
                    hasMoreHistory = hasMore,
                    isLoadingMore = false,
                    lastHistoryLoadCount = 0,
                    isStreaming = isBackgroundStreaming,
                    isWaitingFirstToken = backgroundWaitingForOutput,
                    taskCards = emptyMap(),
                    pendingToolApprovals = emptyList(),
                    // F-4: Cambie la sesión y borre el banner superior del nivel del mensaje,Evite la transferencia entre sesiones
                    pinnedMessageContent = null,
                    toolCallHistory = emptyList(),
                    agentPlans = restoredAgentPlans,
                    sessionsError = null,
                    visionProgress = null,
                    pendingImages = emptyList(),
                    pendingDocuments = emptyList(),
                    replyingTo = null,
                    replyQuoteOverride = null,
                    inputHistory = emptyList(),
                    inputHistoryIndex = null,
                    sessionPermissionMode = permissionMode,
                    listFirstVisibleItemIndex = messages.lastIndex.coerceAtLeast(0),
                    listFirstVisibleItemScrollOffset = 0,
                )
            }
            if (isBackgroundStreaming) {
                sessionMemoryCache.remove(sessionId)
            }
            resultOf { sessionDeps.settings.saveViewedSessionId(sessionId) }
                .onError { msg, _ -> Logger.w("ChatVM", "saveViewedSessionId falló: $msg") }
            // v1.0.30: Marcar marca de tiempo de cambio de sesión,Proporcionado a onAppForeground para determinar si se requiere una actualización forzada
            sessionDeps.onSessionSwitched(sessionId)
            // v0.45: Actualizar la ocupación del token de contexto
            bridge.refreshContext()
            // P0 árbol de diálogo: Lea la instantánea de la última selección de sucursales,Restaurar usuarios al reconstruir/Variante asistente
            val treeSnapshot = sessionDeps.treeSnapshotStore?.load(sessionId)
            sessionDeps.messageController.rebuildConversationTree(previousOverride = treeSnapshot)
            sessionDeps.restorePendingApprovalsForSession(sessionId)
            // Reanudar la carga desde el punto de interrupción:Compruebe si hay llamadas de herramientas pendientes en esta sesión
            val pendingCount =
                resultOf { PendingToolCallStore.getForChat(sessionId) }
                    .onError { msg, t -> Logger.w("ChatVM", "switchSession getForChat falló: $msg", t) }
                    .getOrNull()?.size ?: 0
            if (pendingCount > 0) {
                accessor.update { it.copy(pendingToolCallCount = pendingCount) }
                Logger.i("ChatVM", "switchSession detectado $pendingCount llamadas de herramientas excepcionales,sesión=$sessionId")
            } else {
                accessor.update { it.copy(pendingToolCallCount = 0) }
            }
            sessionDeps.requeueOutboxForSession(sessionId)
        }
    }

    companion object {
        private const val MESSAGE_PAGE_SIZE = 50
    }
}
