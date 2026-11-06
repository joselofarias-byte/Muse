package io.zer0.muse.ui.chat

import android.content.Context
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.lorebook.LorebookEntity
import io.zer0.muse.data.lorebook.LorebookRepository
import io.zer0.muse.data.promptinjection.PromptInjectionEntity
import io.zer0.muse.data.promptinjection.PromptInjectionRepository
import io.zer0.muse.data.quickmsg.QuickMessageEntity
import io.zer0.muse.data.quickmsg.QuickMessageRepository
import io.zer0.muse.data.session.FolderRepository
import io.zer0.muse.data.session.SearchResult
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.ui.ChatError
import io.zer0.muse.ui.ChatErrorType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * v1.105 Fase 2: Coordinador varios extraído de ChatViewModel.
 *
 * Fusionar 4 áreas de responsabilidad de bajo acoplamiento(No hay muchos métodos para cada uno.,No se obtienen muchos beneficios creando una categoría sola):
 *  - Agrupación de carpetas CRUD(createFolder / renameFolder / deleteFolder / toggleFolderExpanded / moveSessionToFolder / togglePinned)
 *  - Buscar(updateSearchQuery / search / clearSearch)
 *  - Operación de cobranza(toggleFavorite / setMessageFavoriteTag / setFavoriteTagFilter / deleteMessage)
 *  - página de gestión CRUD(Lorebook / PromptInjection / QuickMessage de refresh/save/delete)
 *
 * Estos métodos no llaman a los métodos de transmisión principales de ChatViewModel.(detachStreaming / launchStream / refreshContextInfo),
 * Estado de solo lectura y escritura + sintonización repository,Adecuado para puesta en marcha unificada.
 */
class ChatMiscCoordinator(
    private val accessor: ChatStateAccessor,
    private val sessionRepository: SessionRepository,
    private val folderRepository: FolderRepository,
    private val lorebookRepository: LorebookRepository,
    private val promptInjectionRepository: PromptInjectionRepository,
    private val quickMessageRepository: QuickMessageRepository,
    private val assistantRepository: AssistantRepository,
    private val appContext: Context,
) {

    private val tag = "ChatVM"

    // ── Agrupación de carpetas CRUD ──────────────────────────────────────────────────

    /** Crea una nueva carpeta. */
    fun createFolder(name: String, reportError: (String) -> Unit) {
        accessor.coroutineScope.launch {
            resultOf { folderRepository.createFolder(name) }
                .onError { msg, t -> reportError(appContext.getString(R.string.err_chat_misc_create_folder_failed, t?.message ?: "")) }
        }
    }

    /** Cambie el nombre de la carpeta. */
    fun renameFolder(id: String, name: String, reportError: (String) -> Unit) {
        accessor.coroutineScope.launch {
            resultOf { folderRepository.renameFolder(id, name) }
                .onError { msg, t -> reportError(appContext.getString(R.string.err_chat_misc_rename_folder_failed, t?.message ?: "")) }
        }
    }

    /** eliminar carpeta(Las conversaciones relacionadas se movieron a desagrupadas). */
    fun deleteFolder(id: String, reportError: (String) -> Unit) {
        accessor.coroutineScope.launch {
            resultOf {
                accessor.snapshot.sessions.filter { it.folderId == id }.forEach { s ->
                    folderRepository.moveSessionToFolder(s.id, null)
                }
                folderRepository.deleteFolder(id)
            }.onError { msg, t -> reportError(appContext.getString(R.string.err_chat_misc_delete_folder_failed, t?.message ?: "")) }
        }
    }

    /** Cambiar expansión de carpeta/estado plegado. */
    fun toggleFolderExpanded(id: String, expanded: Boolean) {
        accessor.coroutineScope.launch {
            resultOf { folderRepository.setExpanded(id, expanded) }
        }
    }

    /** Mover sesión a la carpeta(folderId=null = Mover a desagrupado). */
    fun moveSessionToFolder(sessionId: String, folderId: String?, reportError: (String) -> Unit) {
        accessor.coroutineScope.launch {
            resultOf { folderRepository.moveSessionToFolder(sessionId, folderId) }
                .onError { msg, t ->
                    Logger.e(tag, "moveSessionToFolder failed", t)
                    reportError(
                        appContext.getString(
                            R.string.err_chat_misc_move_session_failed,
                            t?.message ?: appContext.getString(R.string.err_chat_unknown),
                        ),
                    )
                }
        }
    }

    /** P0-1 Reparación: Alternar estado superior de la conversación. */
    fun togglePinned(sessionId: String, reportError: (String) -> Unit) {
        accessor.coroutineScope.launch {
            val session = accessor.snapshot.sessions.find { it.id == sessionId } ?: return@launch
            resultOf { sessionRepository.setSessionPinned(sessionId, !session.pinned) }
                .onError { msg, t -> reportError(appContext.getString(R.string.err_chat_misc_toggle_pin_failed, t?.message ?: "")) }
        }
    }

    /** B7-05: Persistencia de clasificación de arrastrar y soltar de la sesión superior. */
    fun reorderPinnedSessions(ids: List<String>, reportError: (String) -> Unit) {
        accessor.coroutineScope.launch {
            resultOf { sessionRepository.reorderPinnedSessions(ids) }
                .onError { msg, t -> reportError(appContext.getString(R.string.err_chat_misc_reorder_pinned_failed, t?.message ?: "")) }
        }
    }

    // ── Buscar ─────────────────────────────────────────────────────────────

    /** Actualizar el texto del cuadro de búsqueda. Resultados vacíos cuando el texto está vacío. */
    fun updateSearchQuery(query: String) {
        accessor.update { it.copy(searchQuery = query) }
        if (query.isBlank()) {
            accessor.update {
                it.copy(
                    searchResults = emptyList(),
                    isSearching = false,
                    messageResults = emptyList(),
                    isSearchingMessages = false,
                    searchError = null,
                )
            }
        }
    }

    /** Realizar una búsqueda. Las consultas vacías se ignoran. */
    fun search() {
        val query = accessor.snapshot.searchQuery.trim()
        if (query.isEmpty()) return
        accessor.update { it.copy(isSearching = true, searchError = null) }
        accessor.coroutineScope.launch {
            var failed = false
            var detail: String? = null
            val results = try {
                kotlinx.coroutines.withTimeoutOrNull(5000L) {
                    sessionRepository.searchMessages(query)
                } ?: emptyList<SearchResult>().also {
                    failed = true
                    detail = appContext.getString(R.string.err_chat_network_timeout)
                }
            } catch (t: Throwable) {
                Logger.w(tag, "Search failed: ${t.message}")
                failed = true
                detail = t.message ?: appContext.getString(R.string.err_chat_unknown)
                emptyList()
            }
            accessor.update {
                it.copy(
                    searchResults = results,
                    isSearching = false,
                    searchError = if (failed) {
                        appContext.getString(R.string.err_chat_request_failed, detail ?: appContext.getString(R.string.err_chat_unknown))
                    } else {
                        null
                    },
                )
            }
        }
    }

    /** Borrar estado de búsqueda. */
    fun clearSearch() {
        accessor.update {
            it.copy(
                searchQuery = "",
                searchResults = emptyList(),
                isSearching = false,
                // v2.x: Borrar sincrónicamente los resultados de búsqueda del contenido del mensaje(También se borra al volver a la pestaña de sesión,evitar residuos)
                messageResults = emptyList(),
                isSearchingMessages = false,
                searchError = null,
            )
        }
    }

    // ── v2.x: Búsqueda de contenido de mensajes(Tab=1) ──────────────────────────────────────

    /** v2.x: Cambiar página de búsqueda Tab(0=sesión, 1=Contenido del mensaje). */
    fun switchSearchTab(tab: Int) {
        accessor.update { it.copy(searchTab = tab, searchError = null) }
    }

    /**
     * v2.x: Realizar búsqueda de contenido de mensajes(FTS4 + snippet,Repliegue ante el fracaso LIKE).
     *
     * con [search] diferencia:ir [SessionRepository.searchMessageContentFlow](Devolver fragmento de fragmento directamente),
     * El resultado se almacena en [io.zer0.muse.ui.ChatUiState.messageResults],suministro SearchScreen Tab=1 mostrar.
     * Una consulta vacía borra los resultados.
     */
    fun searchMessageContent() {
        val query = accessor.snapshot.searchQuery.trim()
        if (query.isEmpty()) {
            accessor.update { it.copy(messageResults = emptyList(), isSearchingMessages = false, searchError = null) }
            return
        }
        accessor.update { it.copy(isSearchingMessages = true, searchError = null) }
        accessor.coroutineScope.launch {
            var failed = false
            var detail: String? = null
            val results = try {
                kotlinx.coroutines.withTimeoutOrNull(5000L) {
                    sessionRepository.searchMessageContentFlow(query).first()
                } ?: emptyList<SearchResult>().also {
                    failed = true
                    detail = appContext.getString(R.string.err_chat_network_timeout)
                }
            } catch (t: Throwable) {
                // v1.x: Cancelación de rutina(saltar/Salir de la página de búsqueda)Hay que volver a lanzarlo.,No se muestra porque la búsqueda falló
                if (t is kotlinx.coroutines.CancellationException) throw t
                Logger.w(tag, "Message content search failed: ${t.message}")
                failed = true
                detail = t.message ?: appContext.getString(R.string.err_chat_unknown)
                emptyList()
            }
            accessor.update {
                it.copy(
                    messageResults = results,
                    isSearchingMessages = false,
                    searchError = if (failed) {
                        appContext.getString(R.string.err_chat_request_failed, detail ?: appContext.getString(R.string.err_chat_unknown))
                    } else {
                        null
                    },
                )
            }
        }
    }

    /**
     * v2.x: Establecer mensaje de destino(Haga clic para saltar desde los resultados de búsqueda).
     *
     * ChatScreen Se leerá después de ingresar a la sesión. [io.zer0.muse.ui.ChatUiState.targetMessageId]
     * Desplázate hasta el mensaje,y basado en [io.zer0.muse.ui.ChatUiState.highlightedMessageId]
     * Resalte brevemente. Una vez completado el posicionamiento del desplazamiento, el [consumeTargetMessage] Borrar targetMessageId;
     * El resaltado dura unos 2,5 segundos y luego [clearHighlightedMessage] Vacío.
     *
     * @param messageId mensaje objetivo id(null = estado claro)
     * @param query Buscar palabras clave(Se utiliza para resaltar texto dentro de MessageBubble,null = No resaltar texto)
     */
    fun setTargetMessage(messageId: String?, query: String?) {
        accessor.update {
            it.copy(
                targetMessageId = messageId,
                searchHighlightQuery = query,
                highlightedMessageId = messageId,
            )
        }
    }

    /** v2.x: Consumir mensajes objetivo id(Se llama después de completar el posicionamiento del desplazamiento.,Evite activar el desplazamiento repetidamente). */
    fun consumeTargetMessage() {
        accessor.update { it.copy(targetMessageId = null) }
    }

    /** v2.x: Borrar mensajes resaltados id(Se llama después de que finaliza el período de la ventana resaltada.,Deja de parpadear el resaltado). */
    fun clearHighlightedMessage() {
        accessor.update {
            it.copy(
                highlightedMessageId = null,
                searchHighlightQuery = null,
            )
        }
    }

    // ── Operación de cobranza ─────────────────────────────────────────────────────────

    /** Cambiar el estado de la recopilación de mensajes(Actualizaciones optimistas + reversión en caso de falla). */
    fun toggleFavorite(messageId: Uuid) {
        val target = resolveFavoriteTarget(messageId) ?: return
        setFavorite(messageId, !target.favorite)
    }

    /**
     * Establezca el estado de la colección en el valor especificado (idempotente: cuando ya esté en el estado de destino, ya no escribirá en la biblioteca y no se informará ningún error).
     *
     * con [toggleFavorite] La diferencia es que no se voltea y se puede especificar directamente mediante una entrada externa (menú presionado prolongadamente en la página web).“Colección/Cancelar favoritos”.
     * Cuando el objetivo no está en la lista de mensajes actual ni en la lista de favoritos (referencias residuales después de la eliminación entre sesiones), la base de datos aún se escribe, pero no hay una actualización optimista.
     */
    fun setFavorite(messageId: Uuid, favorite: Boolean) {
        val target = resolveFavoriteTarget(messageId)
        if (target != null && target.favorite == favorite) return
        if (target != null) applyFavoriteState(messageId, target, favorite)
        accessor.coroutineScope.launch {
            resultOf { sessionRepository.setMessageFavorite(messageId.toString(), favorite) }
                .onError { _, t ->
                    if (target != null) rollbackFavoriteState(messageId, target, favorite)
                    pushFavoriteError(t)
                }
        }
    }

    /** Phase 8.5 Reparación: Primero verifique la sesión actual messages,No lo encuentro, compruébalo de nuevo. favoriteMessages(Lista de favoritos entre sesiones). */
    private fun resolveFavoriteTarget(messageId: Uuid): UIMessage? = accessor.messagesSnapshot.firstOrNull { it.id == messageId }
        ?: accessor.snapshot.favoriteMessages.firstOrNull { it.id == messageId }

    /** Colección/Actualización optimista para cancelar favoritos(La lista de mensajes + la lista de favoritos se pueden modificar simultáneamente). */
    private fun applyFavoriteState(messageId: Uuid, target: UIMessage, newFav: Boolean) {
        accessor.updateMessages { list -> list.map { if (it.id == messageId) it.copy(favorite = newFav) else it } }
        accessor.update { st ->
            val newFavs = if (newFav) {
                if (st.favoriteMessages.none { it.id == messageId }) {
                    st.favoriteMessages + target.copy(favorite = true)
                } else {
                    st.favoriteMessages
                }
            } else {
                st.favoriteMessages.filterNot { it.id == messageId }
            }
            st.copy(favoriteMessages = newFavs)
        }
    }

    /** Calcular el orden de visualización de la lista de favoritos(DAO Calibre:createdAt orden descendente). Utilícelo para mantener la posición al hacer retroceder el inserto. */
    private fun sortedFavorites(list: List<UIMessage>): List<UIMessage> = list.sortedByDescending { it.createdAt }

    /**
     * Recopile la reversión de la escritura fallida de la base de datos.
     *
     * Al revertir la implementación anterior, vaya a la lista para buscar el mensaje.,Si no lo encuentras, no hagas nada. —— Y la reversión de la cancelación del cobro es exactamente
     * "ya no esta en la lista"esa rama de,El icono siempre permanece en estado de error. Úselo directamente aquí [target] Instantánea
     * recuperar,No depende del contenido de la lista actual.
     */
    private fun rollbackFavoriteState(messageId: Uuid, target: UIMessage, attemptedFav: Boolean) {
        val restored = !attemptedFav
        accessor.updateMessages { list -> list.map { if (it.id == messageId) it.copy(favorite = restored) else it } }
        accessor.update { st ->
            val others = st.favoriteMessages.filterNot { it.id == messageId }
            val rolled = if (restored) sortedFavorites(others + target.copy(favorite = true)) else others
            st.copy(favoriteMessages = rolled)
        }
    }

    private fun pushFavoriteError(t: Throwable?) {
        accessor.update { st ->
            st.copy(
                errors = listOf(
                    ChatError(
                        type = ChatErrorType.UNKNOWN,
                        message = appContext.getString(R.string.err_chat_misc_favorite_failed, t?.message ?: ""),
                    ),
                ),
            )
        }
    }

    /**
     * v2.6.9: Comentarios sobre los resultados de la recopilación por lotes.
     *
     * Distinguir entre tres situaciones y evitar“Se hizo clic pero no hubo respuesta.”:
     *  - Todos han sido recolectados (puntos de repetición)
     *  - Todo agregado exitosamente
     *  - Parcialmente exitoso
     */
    fun reportBatchFavorite(added: Int, total: Int) {
        val msg = when {
            added == 0 -> appContext.getString(R.string.chat_favorite_selected_all_exist, total)
            added == total -> appContext.getString(R.string.chat_favorite_selected_ok, added)
            else -> appContext.getString(R.string.chat_favorite_selected_partial, added, total)
        }
        accessor.update { st ->
            st.copy(errors = st.errors + ChatError(type = ChatErrorType.UNKNOWN, message = msg))
        }
    }

    /** v1.104 U7: Establecer etiquetas de grupos favoritos(null = Mover a desagrupado). */
    fun setMessageFavoriteTag(messageId: Uuid, tag: String?) {
        val target = accessor.snapshot.favoriteMessages.firstOrNull { it.id == messageId } ?: return
        val oldTag = target.favoriteTag
        val newTag = tag?.trim()?.takeIf { it.isNotEmpty() }
        if (oldTag == newTag) return
        val idStr = messageId.toString()
        accessor.update { st ->
            val newFavs = st.favoriteMessages.map {
                if (it.id == messageId) it.copy(favoriteTag = newTag) else it
            }
            st.copy(favoriteMessages = newFavs)
        }
        accessor.coroutineScope.launch {
            resultOf { sessionRepository.setMessageFavoriteTag(idStr, newTag) }
                .onError { msg, t ->
                    accessor.update { st ->
                        val rolled = st.favoriteMessages.map {
                            if (it.id == messageId) it.copy(favoriteTag = oldTag) else it
                        }
                        st.copy(
                            favoriteMessages = rolled,
                            errors = listOf(
                                ChatError(
                                    type = ChatErrorType.UNKNOWN,
                                    message = appContext.getString(R.string.err_chat_misc_set_tag_failed, t?.message ?: ""),
                                ),
                            ),
                        )
                    }
                }
        }
    }

    /** v1.104 U7: Establezca las condiciones del filtro de agrupación para los favoritos actuales. */
    fun setFavoriteTagFilter(tag: String?) {
        accessor.update { it.copy(favoriteTagFilter = tag) }
    }

    /**
     * Colección por lotes:Camina con todo el lote [toggleFavorite] La misma ruta de escritura.
     *
     * Anteriormente, escribí otro párrafo en ViewModel."Ajustar directamente el repositorio + modificar manualmente las dos listas"realización,
     * Tres estados con un solo cambio(messages / favoriteMessages / invalidación de caché)
     * hacer lo suyo,No hay retroceso en caso de fracaso. Aquí, la ruta por lotes converge en un ajuste cíclico. [toggleOne],
     * Un mensaje tiene solo una colección./Semántica de cancelación.
     *
     * @return Número real de nuevas colecciones exitosas(Recuento único de favoritos,No vuelvas a escribir la biblioteca)
     */
    suspend fun favoriteMessages(messageIds: List<Uuid>): Int = messageIds.count { id ->
        val target = accessor.messagesSnapshot.firstOrNull { it.id == id }
            ?: accessor.snapshot.favoriteMessages.firstOrNull { it.id == id }
            ?: return@count false
        !target.favorite && toggleOne(id)
    }

    /** [toggleFavorite] Implementación de la versión suspendida.:Mismo conjunto de actualizaciones optimistas + reversión en caso de falla,Para la reutilización de rutas por lotes. */
    private suspend fun toggleOne(messageId: Uuid): Boolean {
        val target = resolveFavoriteTarget(messageId) ?: return false
        val newFav = !target.favorite
        applyFavoriteState(messageId, target, newFav)
        return resultOf { sessionRepository.setMessageFavorite(messageId.toString(), newFav) }
            .onError { _, t ->
                rollbackFavoriteState(messageId, target, newFav)
                pushFavoriteError(t)
            }
            .isSuccess
    }

    /** v1.48: Eliminar un solo mensaje(Actualizaciones optimistas + reversión en caso de falla). */
    fun deleteMessage(messageId: Uuid) {
        if (accessor.messagesSnapshot.none { it.id == messageId }) return
        val idStr = messageId.toString()
        // v1.0.80 (T-4): Eliminación en cascada de mensajes infantiles en la interfaz de usuario(assistant Responder),Consistente con la eliminación en cascada de base de datos,
        // De lo contrario, tras eliminar el mensaje del usuario, su respuesta permanecerá en la lista.,Desaparecerá después de volver a la sesión.(intermitente/Dislocación).
        val removedIds = collectCascadeIds(messageId)
        val removedList = accessor.messagesSnapshot.filter { it.id in removedIds }
        accessor.updateMessages { messages -> messages.filterNot { it.id in removedIds } }
        accessor.coroutineScope.launch {
            resultOf {
                sessionRepository.deleteMessage(idStr)
            }.onError { msg, t ->
                // revertir: Inserte el mensaje eliminado nuevamente en su posición original según creado en,Los mensajes recién agregados después de la eliminación no se sobrescriben.
                val rolled = accessor.messagesSnapshot.toMutableList()
                removedList.sortedBy { it.createdAt }.forEach { removedMsg ->
                    val idx = rolled.indexOfFirst { it.createdAt > removedMsg.createdAt }
                        .let { if (it < 0) rolled.size else it }
                    rolled.add(idx, removedMsg)
                }
                accessor.updateMessages { rolled }
                accessor.update { st ->
                    st.copy(
                        errors = listOf(
                            ChatError(
                                type = ChatErrorType.UNKNOWN,
                                message = appContext.getString(R.string.err_chat_misc_delete_msg_failed, t?.message ?: ""),
                            ),
                        ),
                    )
                }
            }
        }
    }

    /**
     * U-16: Eliminar el mensaje de destino y todos los mensajes posteriores(semántica de truncamiento,Documentación §4"Junto con la eliminación posterior").
     *
     * con [deleteMessage] la diferencia:Este último sólo elimina el mensaje y sus respuestas a nivel de árbol.;Este método elimina
     * createdAt >= Todos los mensajes del mensaje de destino.(independientemente de la relación padre-hijo),La capa de datos va sessionRepository.truncateFrom
     * (Compensación sincrónica de FTS y generación de puntos de control dentro de una transacción). Actualizaciones optimistas + reversión en caso de falla,Consistente con el comportamiento de eliminación única.
     */
    fun deleteMessagesFrom(messageId: Uuid, sessionId: String) {
        val target = accessor.messagesSnapshot.firstOrNull { it.id == messageId } ?: return
        val fromCreatedAt = target.createdAt
        val removedList = accessor.messagesSnapshot.filter { it.createdAt >= fromCreatedAt }
        if (removedList.isEmpty()) return
        val removedIds = removedList.map { it.id }.toSet()
        accessor.updateMessages { messages -> messages.filterNot { it.id in removedIds } }
        accessor.coroutineScope.launch {
            resultOf {
                sessionRepository.truncateFrom(sessionId, fromCreatedAt)
            }.onError { msg, t ->
                // revertir: Inserte el mensaje eliminado nuevamente en su posición original según creado en,Los mensajes recién agregados después de la eliminación no se sobrescriben.
                val rolled = accessor.messagesSnapshot.toMutableList()
                removedList.sortedBy { it.createdAt }.forEach { removedMsg ->
                    val idx = rolled.indexOfFirst { it.createdAt > removedMsg.createdAt }
                        .let { if (it < 0) rolled.size else it }
                    rolled.add(idx, removedMsg)
                }
                accessor.updateMessages { rolled }
                accessor.update { st ->
                    st.copy(
                        errors = listOf(
                            ChatError(
                                type = ChatErrorType.UNKNOWN,
                                message = appContext.getString(R.string.err_chat_misc_delete_msg_failed, t?.message ?: ""),
                            ),
                        ),
                    )
                }
            }
        }
    }

    /**
     * v1.0.80 (T-4): Recopilar mensajes que se conectarán en cascada para su eliminación. id(Anti-anillo BFS).
     *
     * B-15: DB lado [SessionRepository.deleteMessage] Utilice messageDao.getChildrenByParentId para
     * `parentGroupId = :parentId OR parentMessageId = :parentId` Subrespuesta relacionada;UI Instantánea
     * (UIMessage)exponer solo parentGroupId(Los nuevos datos equivalen al mensaje principal id,Equivalente a la asociación parentMessageId).
     * para cubrir con"grupo variante"respuesta montada(DB Se eliminará pero la lógica anterior no se eliminará. UI),Excepto la coincidencia por ID de mensaje al atravesar
     * parentGroupId afuera,Al mismo tiempo, utilice el VariantGroupId del mensaje eliminado como referencia principal.,Evite los residuos de mensajes fantasma.
     */
    private fun collectCascadeIds(root: Uuid): Set<Uuid> {
        val result = linkedSetOf<Uuid>()
        val queue = ArrayDeque<Uuid>()
        queue.add(root)
        val snapshot = accessor.messagesSnapshot
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!result.add(id)) continue
            val idStr = id.toString()
            val refs: Set<String> = buildSet {
                add(idStr)
                snapshot.firstOrNull { it.id == id }?.variantGroupId?.let(::add)
            }
            snapshot.forEach { msg ->
                // v2.5.2: Considere también las referencias explícitas a mensajes para padres.(parentMessageId) ——
                // El mensaje en pantalla de la herramienta se cuelga debajo del mensaje del asistente al que pertenece.,Al eliminar el asistente, debes llevarlo contigo.
                val parentRef = msg.parentGroupId ?: msg.parentMessageId
                if (parentRef != null && parentRef in refs && msg.id !in result) {
                    queue.add(msg.id)
                }
            }
        }
        // v2.5.2 Tanto el stock como el fondo: El mensaje que muestra la herramienta en los datos antiguos no tiene asociación principal (los campos solo se agregan en esta ronda).
        // No se puede encontrar presionando padre arriba. Extraiga un método independiente para el procesamiento y mantenga esta función legible.
        appendTrailingOrphanToolDisplays(ordered = snapshot.sortedBy { it.createdAt }, result = result)

        return result
    }

    /**
     * v2.5.2: Tarjeta y base de herramientas huérfanas de stock.
     *
     * Escanee hacia atrás desde cualquier mensaje eliminado y deténgase cuando encuentre el siguiente mensaje del USUARIO; elimine "sin asociación principal y carga de herramientas" en el medio
     * Los mensajes en pantalla también se agregan al conjunto de eliminación para borrar las tarjetas huérfanas que quedan del historial.
     */
    private fun appendTrailingOrphanToolDisplays(ordered: List<io.zer0.ai.core.UIMessage>, result: MutableSet<Uuid>) {
        if (ordered.isEmpty() || result.isEmpty()) return
        var i = ordered.indexOfLast { it.id in result } + 1
        while (i < ordered.size) {
            val m = ordered[i]
            val hasParent = m.parentGroupId != null || m.parentMessageId != null
            val isToolDisplay = m.toolCallInfo != null || m.toolCalls != null
            val stop = m.role == io.zer0.ai.core.MessageRole.USER || hasParent || !isToolDisplay
            if (stop) break
            result.add(m.id)
            i++
        }
    }

    // ── página de gestión CRUD: Lorebook / PromptInjection / QuickMessage ───────────

    /** v1.97: Carga diferida de todas las entradas del Lorebook. */
    fun refreshLorebooks() {
        accessor.coroutineScope.launch {
            try {
                val list = lorebookRepository.observeAll().first()
                accessor.update { it.copy(lorebooks = list, lorebooksError = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // ST-01: No se pudo cargar la lista mundial de libros → Estado de error de registro,Para mostrar LorebookScreen y reintentar
                Logger.w(tag, "refreshLorebooks falló: ${e.message}", e)
                accessor.update { it.copy(lorebooksError = e.message ?: appContext.getString(R.string.common_load_failed)) }
            }
        }
    }

    fun saveLorebook(entity: LorebookEntity) {
        accessor.coroutineScope.launch {
            lorebookRepository.upsert(entity.copy(updatedAt = System.currentTimeMillis()))
            refreshLorebooks()
        }
    }

    fun deleteLorebook(id: String) {
        accessor.coroutineScope.launch {
            lorebookRepository.delete(id)
            refreshLorebooks()
        }
    }

    /** v1.97: Carga diferida de todas las entradas de PromptInjection. */
    fun refreshPromptInjections() {
        accessor.coroutineScope.launch {
            val list = promptInjectionRepository.observeAll().first()
            accessor.update { it.copy(promptInjections = list) }
        }
    }

    fun savePromptInjection(entity: PromptInjectionEntity) {
        accessor.coroutineScope.launch {
            promptInjectionRepository.upsert(entity.copy(updatedAt = System.currentTimeMillis()))
            refreshPromptInjections()
        }
    }

    fun deletePromptInjection(id: String) {
        accessor.coroutineScope.launch {
            promptInjectionRepository.delete(id)
            refreshPromptInjections()
        }
    }

    /** v1.97: Carga diferida de todas las entradas de QuickMessage. */
    fun refreshAllQuickMessages() {
        accessor.coroutineScope.launch {
            val list = quickMessageRepository.observeAll().first()
            accessor.update { it.copy(allQuickMessages = list) }
        }
    }

    fun saveQuickMessage(entity: QuickMessageEntity) {
        accessor.coroutineScope.launch {
            quickMessageRepository.upsert(entity.copy(updatedAt = System.currentTimeMillis()))
            refreshAllQuickMessages()
        }
    }

    fun deleteQuickMessage(id: String) {
        accessor.coroutineScope.launch {
            quickMessageRepository.delete(id)
            refreshAllQuickMessages()
        }
    }
}
