package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.resultOf
import io.zer0.muse.data.chat.ConversationTree
import io.zer0.muse.data.chat.ConversationTreeSnapshotStore
import io.zer0.muse.data.chat.mergeRebuildMessages
import io.zer0.muse.data.chat.orderConversationMessages
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.tools.SkillExecutor
import io.zer0.muse.ui.MessageExpandedState
import io.zer0.muse.ui.taskcard.AgentPlan
import io.zer0.muse.ui.taskcard.restoreAgentPlansFromHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * v1.x: Controlador de carga de mensajes desconectado de ChatViewModel.
 *
 * Responsabilidades(Lectura de mensajes y paginación,No incluye generación de streaming/ejecución de herramientas):
 *  - [loadMessagesPaged] Carga de página inicial(apátrida,Lectura pura del repositorio)
 *  - [loadMoreHistory] Desliza hacia arriba para cargar el historial anterior(Estable para anclajes de paginación. seq,Fusionar y eliminar duplicados,plan de recuperación)
 *  - [restoreAgentPlansForSession] Reproducir el plan del agente a partir de mensajes de herramientas descartadas
 *
 * compartir estado ir [ChatStateAccessor](_state/_messages),Las fuentes de hechos de StateFlow o Room no se copian.
 * reconstrucción de árboles [onTreeRebuild] devolución de llamada(ConversationTree StateFlow todavía en el anfitrión),No hay dependencia inversa de ChatViewModel.
 */
@Suppress("TooManyFunctions")
class ChatMessageController(
    private val accessor: ChatStateAccessor,
    private val sessionRepository: SessionRepository,
    private val skillExecutor: SkillExecutor,
    internal val treeState: MutableStateFlow<ConversationTree>,
    private val treeSnapshotStore: ConversationTreeSnapshotStore?,
    private val messagePageSize: Int = 50,
) {
    /**
     * v2.6.7: Puerto de colocación de posición de desplazamiento (no escrito de forma predeterminada).
     *
     * Como propiedades mutables en lugar de parámetros de construcción, evite constructores con más de 6 parámetros (detekt LongParameterList).
     * Solo genera efectos secundarios y no participa en la lógica de carga de mensajes: la falla de escritura no puede afectar el desplazamiento.
     */
    var scrollPersister: ScrollPositionPersister? = null

    /** Puerto de escritura de posición de desplazamiento. */
    fun interface ScrollPositionPersister {
        suspend fun persist(sessionId: String, index: Int, offset: Int)
    }

    /** Carga de página inicial:Toma el más cercano [messagePageSize] Artículo;Regresar (Lista de mensajes ascendente, ¿Existe una historia anterior?). */
    suspend fun loadMessagesPaged(sessionId: String): Pair<List<UIMessage>, Boolean> {
        val total = sessionRepository.getMessageCount(sessionId)
        if (total == 0) return emptyList<UIMessage>() to false
        val limit = minOf(messagePageSize, total)
        val messages = sessionRepository.getRecentMessages(sessionId, limit)
        return messages to (total > messages.size)
    }

    /**
     * Recuperar planes de agentes a partir de mensajes de presentación de herramientas persistentes.
     * La ontología del plan solo existe en la memoria caché de SkillAgentToolsImpl,Cambiar de sesión/Después de reiniciar, los mensajes deben reproducirse y restaurarse en orden.
     *
     * @param settleUnfinished v2.6.4: Si finalizar los pasos inacabados con CANCELADO.
     *   - La sesión ha finalizado por completo (primer plano abierto, sin transmisión en segundo plano):true —— No debería existir tal cosa en la historia.“corriendo”plan.
     *   - La sesión todavía se transmite en segundo plano:false —— No interrumpas el plan activo, déjalo continuar.
     */
    suspend fun restoreAgentPlansForSession(
        sessionId: String,
        visibleMessages: List<UIMessage>,
        settleUnfinished: Boolean = true,
    ): Map<String, AgentPlan> {
        val persistedToolMessages =
            resultOf {
                sessionRepository.getToolCallMessages(sessionId)
            }.getOrNull().orEmpty()
        val merged = linkedMapOf<String, UIMessage>()
        // El historial de la base de datos primero establece un orden estable,La ventana actual sobrescribe el contenido de la proyección anterior con la misma identificación.
        persistedToolMessages.forEach { merged[it.id.toString()] = it }
        visibleMessages.forEach { merged[it.id.toString()] = it }
        val history = orderConversationMessages(merged.values.toList())
        val plans = restoreAgentPlansFromHistory(history, sessionId, settleUnfinished)
        // UI Las cachés de proyección y ejecución de herramientas deben restaurarse al mismo tiempo; restaurar solo reemplaza la sesión actual y evita la planificación de cadenas de sesiones paralelas.
        skillExecutor.restoreActivePlans(plans, sessionId)
        return plans
    }

    /**
     * Desliza hacia arriba para cargar el historial anterior:Utilice la secuencia estable del primer mensaje actual como punto de anclaje para llegar antes [messagePageSize] Artículo,
     * Insertar antes messages,y actualice hasMoreHistory con la restauración programada.
     * Durante la transmisión/Cargando/Saltar cuando llegue al fondo.
     */
    fun loadMoreHistory() {
        val state = accessor.snapshot
        // Durante la transmisión/Cargando/Terminado → saltar
        if (state.isStreaming || state.isLoadingMore || !state.hasMoreHistory) return
        val sessionId = if (state.isAgentMode) state.agentSessionId else state.currentSessionId
        val firstMsg = accessor.messagesSnapshot.firstOrNull()
        // Ninguna sesión válida/Sin noticias → saltar
        if (sessionId == null || firstMsg == null) return
        val sessionIdSafe = sessionId
        val firstNonEmpty = firstMsg
        accessor.coroutineScope.launch {
            accessor.update { it.copy(isLoadingMore = true) }
            // Secuencia estable para anclajes de paginación.(Homología con clasificación DAO),No utilice createAt, que se actualizará con REPLACE.
            val beforeSeq = if (firstNonEmpty.commitSeq > 0) firstNonEmpty.commitSeq else firstNonEmpty.seq
            val older = sessionRepository.getOlderMessages(sessionIdSafe, beforeSeq, messagePageSize)
            if (older.isEmpty()) {
                accessor.update { it.copy(hasMoreHistory = false, isLoadingMore = false) }
                return@launch
            }
            // Vuelva a leer la lista más reciente,Evitar que se sobrescriban los mensajes nuevos transmitidos durante la carga
            val merged = older + accessor.messagesSnapshot
            val restoredAgentPlans = restoreAgentPlansForSession(sessionIdSafe, merged)
            accessor.updateMessages { merged }
            accessor.update {
                it.copy(
                    hasMoreHistory = older.size >= messagePageSize,
                    isLoadingMore = false,
                    lastHistoryLoadCount = older.size,
                    agentPlans = restoredAgentPlans,
                )
            }
            rebuildConversationTree()
        }
    }

    fun selectUserVariant(userGroupId: String, variantIndex: Int) {
        val tree = treeState.value
        val node =
            tree.userNodes.firstOrNull { user ->
                (user.currentVariant?.message?.variantGroupId ?: user.groupId) == userGroupId
            } ?: return
        val updated = tree.selectUserVariant(node.userId, variantIndex)
        treeState.value = updated
        accessor.updateMessages { updated.displayMessages }
        persistTreeSelection(updated)
    }

    /**
     * Alternar variante de respuesta del asistente (P0 Árbol de diálogo): con alcance únicamente al grupo de asistentes especificado en la variante de usuario actual.
     */
    fun selectAssistantVariant(userGroupId: String, assistantGroupId: String, index: Int) {
        val updated = treeState.value.selectAssistantVariant(userGroupId, assistantGroupId, index)
        treeState.value = updated
        accessor.updateMessages { updated.displayMessages }
        persistTreeSelection(updated)
    }

    private fun persistTreeSelection(tree: ConversationTree) {
        val state = accessor.snapshot
        val sessionId = if (state.isAgentMode) state.agentSessionId else state.currentSessionId
        if (sessionId == null) return
        accessor.coroutineScope.launch(Dispatchers.IO) {
            treeSnapshotStore?.save(sessionId, tree)
        }
    }

    /** v1.0.63: Indexar las ramas normalizadas/Contar reescritura en la base de datos,Reparar datos históricos incorrectos. */
    suspend fun healBranchCounts(sessionId: String, original: List<UIMessage>, tree: ConversationTree) {
        val originalById = original.associateBy { it.id.toString() }
        val normalizedAll =
            buildList {
                tree.userNodes.forEach { user ->
                    user.variants.forEach { add(it.message) }
                    user.variants.forEach { variant ->
                        variant.assistantNodes.forEach { assistant -> assistant.variants.forEach { add(it) } }
                    }
                }
            }
        val changed =
            normalizedAll.filter { msg ->
                val old = originalById[msg.id.toString()]
                old != null && (old.variantIndex != msg.variantIndex || old.variantCount != msg.variantCount)
            }
        if (changed.isEmpty()) return
        changed.forEach { sessionRepository.upsertMessage(sessionId, it) }
    }

    /** v0.44: La sesión a la que pertenece el árbol actual. id,Evitar el cambio de sesión/Al cambiar de Agente, recupere la rama de la sesión anterior. */
    @Volatile
    private var treeSessionId: String? = null

    /**
     * P0 árbol de diálogo: Reconstruir el árbol a partir de la lista de mensajes plana actual,Y muestre la lista de forma sincrónica.
     * Durante la transmisión de mensajes es una fuente plana de hecho.;enviar/Inténtalo de nuevo/Editar/Cambiar de sesión/Llamado en un punto estable como el final del arroyo.
     */
    fun rebuildConversationTree(previousOverride: ConversationTree? = null) {
        val snapshot = accessor.snapshot
        val sessionId = if (snapshot.isAgentMode) snapshot.agentSessionId else snapshot.currentSessionId
        val currentTree =
            if (sessionId != null && treeSessionId == sessionId) {
                treeState.value
            } else {
                ConversationTree()
            }
        // viejo árbol plano guárdelo todo e inténtelo de nuevo/Editar rama,current Mantenga el contenido más reciente y las nuevas actualizaciones,Los dos están fusionados por id.
        // v2.3.2 (D): Los textos originales que han sido sobrescritos por resúmenes ya no se fusionan en el árbol anterior.(De lo contrario"Resumen + texto original"coexistir → token cuenta doble).
        val summarizedIds =
            io.zer0.muse.transformer.CompressionSummaryStore.entry(sessionId)?.coveredIds.orEmpty()
        val messages = mergeRebuildMessages(currentTree, accessor.messagesSnapshot, summarizedIds)
        if (messages.isEmpty()) {
            treeState.value = ConversationTree()
            treeSessionId = sessionId
            return
        }
        val previous = previousOverride ?: currentTree
        val tree = ConversationTree.build(messages, previous)
        treeState.value = tree
        treeSessionId = sessionId
        accessor.updateMessages { tree.displayMessages }
        if (sessionId != null) {
            accessor.coroutineScope.launch(Dispatchers.IO) {
                treeSnapshotStore?.save(sessionId, tree)
                healBranchCounts(sessionId, messages, tree)
            }
        }
    }

    /** Editar el contenido del mensaje del asistente(Lista de mensajes de actualización optimista + biblioteca desplegable). */
    fun editAssistantMessage(messageId: kotlin.uuid.Uuid, newContent: String) {
        val snapshot = accessor.snapshot
        val messages = accessor.messagesSnapshot
        val index = messages.indexOfFirst { it.id == messageId && it.role == MessageRole.ASSISTANT }
        // P2-4: Agent Usado en modo agentSessionId,Evite escribir ediciones en la sesión incorrecta(Mismo calibre que reconstruirConversationTree)
        val sessionId = if (snapshot.isAgentMode) snapshot.agentSessionId else snapshot.currentSessionId
        // Transmisión/Sin sesión/No hay ningún mensaje de asistente coincidente → saltar
        if (snapshot.isStreaming || sessionId == null || index == -1) return
        val sessionIdSafe = sessionId
        // v2.5.3 (P3-1): La edición ya no sobrescribe la variante actual, sino que agrega un nuevo nodo de versión auxiliar.
        // Las versiones antiguas se conservan permanentemente en la rama y los usuarios pueden usar flechas para retroceder (corregir“La versión antigua se perdió después de editarla.”).
        val tree = treeState.value
        val update = tree?.editAssistantMessage(messageId, newContent)
        if (update?.newMessage != null) {
            val newMsg = update.newMessage
            treeState.value = update.tree
            accessor.updateMessages { update.tree.displayMessages }
            accessor.update { it.copy(errors = emptyList()) }
            accessor.coroutineScope.launch {
                // Se inserta la nueva versión; la versión anterior ya está en la biblioteca y no se eliminará.
                sessionRepository.upsertMessage(sessionIdSafe, newMsg)
                treeSnapshotStore?.save(sessionIdSafe, update.tree)
            }
            return
        }
        // Alternativa: Sin árbol (sesión anterior)/Cuando no se crea), la semántica de cobertura original se conserva para evitar la pérdida de ediciones.
        val updated = messages[index].copy(content = newContent, reasoning = null)
        accessor.updateMessages { messages.toMutableList().apply { set(index, updated) } }
        accessor.update { it.copy(errors = emptyList()) }
        accessor.coroutineScope.launch {
            sessionRepository.updateMessageContent(sessionIdSafe, messageId, newContent)
        }
    }

    /** Borrar lastHistoryLoadCount(UI Llamado después de la compensación de desplazamiento,Evite la reorganización y los saltos repetidos.). */
    fun clearHistoryLoadCount() {
        if (accessor.snapshot.lastHistoryLoadCount != 0) {
            accessor.update { it.copy(lastHistoryLoadCount = 0) }
        }
    }

    /** Posición de desplazamiento de la lista de caché,Cortar páginas/Reanudar después de la preparación de antecedentes.Agent El patrón escribe en caché independiente(Ver la anotación del campo ChatUiState),Salir/Los interruptores no se contaminan entre sí. */
    fun onListScrollPositionChanged(index: Int, offset: Int) {
        accessor.update {
            if (it.isAgentMode) {
                it.copy(agentListFirstVisibleItemIndex = index, agentListFirstVisibleItemScrollOffset = offset)
            } else {
                it.copy(listFirstVisibleItemIndex = index, listFirstVisibleItemScrollOffset = offset)
            }
        }
        // v2.6.7 Solución (no se puede volver a la posición original después de eliminar el fondo): La memoria caché se pierde tan pronto como se completa el proceso, por lo que aquí se requiere almacenamiento adicional.
        // Solo recuerda las conversaciones normales:Agent Las páginas se almacenan en caché de forma independiente y tienen una semántica de"Regresa a donde lo dejaste", independientemente de la ubicación persistente de esta sesión.
        val persister = scrollPersister ?: return
        val snapshot = accessor.snapshot
        val sid = snapshot.currentSessionId
        // La limitación se entrega a la interfaz de usuario superior (sample 100ms), escriba directamente aquí;DataStore La escritura es asincrónica y no bloquea el desplazamiento.
        if (!snapshot.isAgentMode && sid != null) {
            accessor.coroutineScope.launch {
                // guardia: launch Es posible que la sesión se haya interrumpido entre la ejecución real. Si ha cambiado, no se escribirá.
                // De lo contrario, los eventos de desplazamiento de la sesión A contaminarán los registros de la sesión B.
                if (accessor.snapshot.currentSessionId == sid) {
                    persister.persist(sid, index, offset)
                }
            }
        }
    }

    /** v1.45: Alterna la expansión del bloque de estado de ánimo del mensaje especificado/estado plegado. */
    fun toggleMessageMoodExpanded(messageId: String) {
        accessor.update { current ->
            val currentState = current.messageExpandedStates[messageId] ?: MessageExpandedState()
            val default = current.chatPreferences.moodExpandedByDefault
            val newExpanded = !(currentState.isMoodExpanded ?: default)
            current.copy(
                messageExpandedStates =
                current.messageExpandedStates +
                    (messageId to currentState.copy(isMoodExpanded = newExpanded)),
            )
        }
    }

    /** v1.45: Alternar la expansión del bloque de razonamiento para el mensaje especificado/estado plegado. */
    fun toggleMessageReasoningExpanded(messageId: String) {
        accessor.update { current ->
            val currentState = current.messageExpandedStates[messageId] ?: MessageExpandedState()
            val default = current.chatPreferences.reasoningExpandedByDefault
            val newExpanded = !(currentState.isReasoningExpanded ?: default)
            current.copy(
                messageExpandedStates =
                current.messageExpandedStates +
                    (messageId to currentState.copy(isReasoningExpanded = newExpanded)),
            )
        }
    }

    /** v1.64: Alterna la expansión del bloque de reflexión del mensaje especificado/estado plegado. */
    fun toggleMessageReflectionExpanded(messageId: String) {
        accessor.update { current ->
            val currentState = current.messageExpandedStates[messageId] ?: MessageExpandedState()
            val default = current.chatPreferences.reflectionExpandedByDefault
            val newExpanded = !(currentState.isReflectionExpanded ?: default)
            current.copy(
                messageExpandedStates =
                current.messageExpandedStates +
                    (messageId to currentState.copy(isReflectionExpanded = newExpanded)),
            )
        }
    }

    /** v2.4.6: Alterna la expansión del bloque de traducción de mensajes especificado/estado plegado(Expandir por defecto). */
    fun toggleMessageTranslationExpanded(messageId: String) {
        accessor.update { current ->
            val currentState = current.messageExpandedStates[messageId] ?: MessageExpandedState()
            val newExpanded = !(currentState.isTranslationExpanded ?: true)
            current.copy(
                messageExpandedStates =
                current.messageExpandedStates +
                    (messageId to currentState.copy(isTranslationExpanded = newExpanded)),
            )
        }
    }
}
