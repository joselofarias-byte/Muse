package io.zer0.muse.ui.groupchat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.AgentTeam
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.groupchat.GroupChatEntity
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.data.groupchat.GroupChatRepository
import io.zer0.muse.schedule.GroupChatScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Estado de la interfaz de usuario del chat grupal.
 *
 * @param chats Lista de chat grupal(Orden descendente por actualizadoEn)
 * @param currentChat La entidad de chat grupal actualmente seleccionada(null Indica no seleccionado)
 * @param currentMessages Lista de mensajes del chat grupal actual(orden ascendente por tiempo)
 * @param inputText Texto del cuadro de entrada
 * @param isAgentResponding Ya sea esperando la respuesta de rotación de agentes
 * @param assistants Lista de todos los asistentes(Para crear un nuevo miembro del chat grupal, seleccione)
 * @param teams Todos los equipos colaborativos(Crear un nuevo chat grupal para equipos asociados)
 */
/**
 * Estado de expansión de mensajes de chat grupal — Se utiliza para mantener el estado de ánimo/razonamiento plegado después de cortar la página.
 */
data class GroupChatMessageExpandedState(
    val isMoodExpanded: Boolean? = null,
    val isReasoningExpanded: Boolean? = null,
)

data class GroupChatUiState(
    val chats: List<GroupChatEntity> = emptyList(),
    /** v1.72: Primera bandera de carga de la lista de chat grupal(Evite el estado flash) */
    val isChatsLoading: Boolean = true,
    val currentChat: GroupChatEntity? = null,
    val currentMessages: List<GroupChatMessageEntity> = emptyList(),
    val inputText: String = "",
    val pendingImages: List<String> = emptyList(),
    val pendingFileAttachments: List<FileAttachment> = emptyList(),
    val isAgentResponding: Boolean = false,
    /** v2.x: Si el usuario ha pausado la rotación(control del director),Se puede hacer clic durante la pausa"continuar"o"saltar restante" */
    val roundPaused: Boolean = false,
    /** v2.x: Los nombres de los miembros restantes que hablarán en esta ronda.(Visualización de colas,Visualización de la barra del director). */
    val upcomingSpeakers: List<String> = emptyList(),
    /** v1.104: actualmente hablando Agent(usado para"quien esta pensando"instrucciones),null=nadie esta hablando */
    val currentSpeaker: AssistantEntity? = null,
    val assistants: List<AssistantEntity> = emptyList(),
    val teams: List<AgentTeam> = emptyList(),
    /** v1.45: Preferencias de chat(Controlar la visualización del estado de ánimo/razonamiento y la expansión predeterminada). */
    val chatPreferences: ChatPreferences = ChatPreferences(),
    /** v1.45: Lista de caché de posición de desplazamiento(Cortar páginas/Reanudar después del fondo). */
    val listFirstVisibleItemIndex: Int = 0,
    val listFirstVisibleItemScrollOffset: Int = 0,
    /** v1.45: Caché de estado de expansión a nivel de mensaje(mood/reasoning El estado plegado no se pierde debido al corte de página.). */
    val messageExpandedStates: Map<String, GroupChatMessageExpandedState> = emptyMap(),
    /** M7: Marca de eliminación de chat grupal,La página de detalles volverá automáticamente en consecuencia. */
    val chatDeleted: Boolean = false,
    /** v1.126: Mensaje de error de operación(null=Sin errores) */
    val errorMessage: String? = null,
    /** v1.53-GC: ¿Hay algún mensaje histórico anterior que se pueda cargar?(Desliza hacia arriba para cargar más). */
    val hasMoreHistory: Boolean = false,
    /** v1.53-GC: Está cargando más mensajes históricos.(Evitar disparos repetidos + indicador de carga superior de la interfaz de usuario). */
    val isLoadingMore: Boolean = false,
    /** v1.x: Colección de identificadores de mensajes seleccionados en modo de selección múltiple(No vacío = Modo de selección múltiple). */
    val selectedMessageIds: Set<String> = emptySet(),
    /** v1.x: El contenido de respuesta del asistente generado por el chat grupal actual se está transmitiendo(null=Sin salida de transmisión). */
    val streamingContent: String? = null,
    /** v1.0.72: Estado de búsqueda del chat grupal. */
    val searchResults: List<GroupChatMessageEntity> = emptyList(),
    val isSearching: Boolean = false,
    /**
     * v1.53-GC: más reciente"cargar más"El número de elementos del historial insertados.
     *
     * UI Monitorear los cambios en este campo.(>0)después,Pase [androidx.compose.foundation.lazy.LazyListState.scrollToItem]
     * Omitir la cantidad de elementos recién insertados,Evita que la posición visual del usuario salte(El mensaje que estaba en la parte superior ahora está en la parte superior. index),
     * Luego ajuste [GroupChatViewModel.clearHistoryLoadCount] Vacío.
     */
    val lastHistoryLoadCount: Int = 0,
    /**
     * ActivityHub: Lista de estado de actividad de cada agente en el chat grupal actual.
     *
     * por [GroupChatActivityHub.activities] StateFlow derivado,Solo contiene actividad para el chat grupal actual.
     * UI Renderizar en consecuencia [AgentActivityBar] en estado chip(IDLE El estado se filtra y no se muestra).
     */
    val activities: List<AgentActivity> = emptyList(),
)

/**
 * chat grupal ViewModel — Administre la lista de chat grupal/flujo de mensajes/horario de rotación de agentes.
 *
 * Responsabilidades:
 *  - Ver lista de chat grupal(observeChats)con lista de ayuda(AssistantRepository.observeAll)
 *  - Cargue el flujo de mensajes en páginas al cambiar el chat grupal actual(getPagedMessages Primera pantalla Flujo + cargarMásHistoria Desliza hacia arriba para cargar más)
 *  - Llamado después de que el usuario envía un mensaje. [GroupChatScheduler.triggerAgentRoundRobin] Activar que cada agente hable en serie
 *  - Crear/eliminar chat grupal
 *
 * v1.53-GC: La lista de mensajes se carga en páginas.,Reutilizar la solución de chat único v1.53(consulta de ventana + Repository reversed +
 * ViewModel loadMoreHistory). Cargando la primera pantalla [INITIAL_PAGE_SIZE] Artículo,Deslice hacia arriba para activar
 * [loadMoreHistory] tomar antes [LOAD_MORE_PAGE_SIZE] Artículo;Los mensajes nuevos se agregan incrementalmente al final de la lista a través de Flow.
 *
 * @param groupChatRepository Almacén de chat grupal
 * @param scheduler Programador de chat grupal(Rotación del agente desencadenante)
 * @param assistantRepository Auxiliar de almacén(Cargar lista de candidatos a miembros)
 * @param settings Configurar almacén(Leer configuración del equipo + nombre de usuario)
 * @param activityHub Administrador de estado de actividad de chat grupal,
 *        Suscríbete a su [GroupChatActivityHub.activities] Deriva la lista activa del chat grupal actual al estado de la interfaz de usuario.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupChatViewModel(
    private val groupChatRepository: GroupChatRepository,
    private val scheduler: GroupChatScheduler,
    private val assistantRepository: AssistantRepository,
    private val settings: SettingsRepository,
    private val activityHub: GroupChatActivityHub,
    private val appContext: Context,
    private val groupChatMemoryRepository: io.zer0.muse.data.groupchat.GroupChatMemoryRepository? = null,
) : ViewModel() {

    companion object {
        private const val TAG = "GroupChatViewModel"

        /** v1.126: Intervalo mínimo para enviar mensajes.(ms),Evite que los clics rápidos se envíen repetidamente */
        private const val SEND_DEBOUNCE_MS = 500L

        /** v1.53-GC: Paginación de mensajes de chat grupal — Tamaño de la página de inicio(Número inicial de artículos cargados). */
        private const val INITIAL_PAGE_SIZE = GroupChatRepository.INITIAL_PAGE_SIZE

        /** v1.53-GC: Paginación de mensajes de chat grupal — Desliza hacia arriba para cargar más tamaños de página. */
        private const val LOAD_MORE_PAGE_SIZE = GroupChatRepository.LOAD_MORE_PAGE_SIZE
    }

    private val _state = MutableStateFlow(GroupChatUiState())
    private val json = Json { ignoreUnknownKeys = true }
    val state: StateFlow<GroupChatUiState> = _state.asStateFlow()

    /** v1.126: Marca de tiempo del último envío,usado para debounce */
    private var lastSendTimestamp = 0L

    /** Chat grupal actualmente seleccionado id(Utilice flatMapLatest para cambiar el flujo de mensajes). */
    private val currentChatId = MutableStateFlow<String?>(null)

    /**
     * Convergir la colección de un flujo de estado de la interfaz de usuario en un registro(No lo vomites,Pero nunca en silencio).
     *
     * por qué es necesario:init Aquí están estos `viewModelScope.launch { flow.collect { } }` Anteriormente, las excepciones eran completamente
     * No hay línea de defensa. aguas arriba(Room / DataStore flujo de almacenamiento)Se genera un error al emitir, o el propio bloque de recopilación descendente genera un error.,se convertirá
     * Excepción no detectada de la rutina:Al menos este flujo de interfaz de usuario muere silenciosamente(La interfaz ya no se actualizará y no habrá registros.),Más importante aún, déjelo así.
     * El UncaughtExceptionHandler predeterminado bloquea el proceso. En la prueba, se filtrará al siguiente caso de uso.
     * (`UncaughtExceptionsBeforeTest`),Haga que los casos de uso irrelevantes sean inestables.
     *
     * Cubriendo dos capas de excepciones:
     *  1. aguas arriba —— `this@guardUpstream.collect` La anomalía de emisión en;
     *  2. aguas abajo —— collect Excepciones lanzadas por el propio bloque.(comienza desde `emit` salir,También entra dentro de este intento.).
     * Sólo aguas arriba `Flow.catch` Categoría 2 no recibida,Entonces aquí usamos `flow {}` Envuelve una capa.
     *
     * **¿Por qué puedo tragarlo?**:Este método solo se utiliza para el flujo de estado de la interfaz de usuario de solo lectura.(Lista de chat grupal/ventana de mensajes/lista de asistentes /
     * Difusión de actividad/Estado de compilación/Preferencias). No participan en la coherencia de la escritura.,El peor resultado cuando ocurre una excepción es"Esta interfaz de usuario ya no se actualizará",
     * Los datos en sí todavía están en la base de datos.,La página se restaurará cuando el usuario vuelva a ingresar a ella. escribir ruta(enviar/Eliminar/Archivo/Comparte documentos y más)de
     * Las excepciones siempre pasan por sus propias combinaciones try/catch. errorMessage,Sin este método.
     *
     * CancellationException Debe volverse a lanzar tal como está.:viewModelScope Limpieza con flatMapLatest y cambio ascendente
     * Úselo como señal de cancelación normal.,La deglución convertirá la cancelación en"El proceso finaliza normalmente",La lógica de limpieza posterior falla.
     */
    private fun <T> Flow<T>.guardUpstream(tag: String): Flow<T> = flow {
        try {
            this@guardUpstream.collect { emit(it) }
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            throw ce
        } catch (e: Exception) {
            Logger.w(TAG, "Se interrumpió el flujo de estado de la interfaz de usuario del chat grupal($tag): ${e.message}", e)
        }
    }

    init {
        // H-GC2 Reparación: Recopilado originalmente usando appScope.launch Flow,ViewModel El coleccionista nunca cancela después de la destrucción.,
        // Provoca fugas de Flow y restos de suscripciones antiguas de StateFlow. Usar en su lugar viewModelScope,Cancelado automáticamente con la limpieza de ViewModel.
        // Ver lista de chat grupal
        viewModelScope.launch {
            groupChatRepository.observeChats()
                .guardUpstream("observeChats")
                .collect { chats ->
                    _state.update { it.copy(chats = chats, isChatsLoading = false) }
                }
        }
        // Lista de asistentes de observación(Para crear un nuevo miembro del chat grupal, seleccione)
        // v1.111: assistants Sincronizar actualizaciones después de cargar currentSpeaker(Es posible que el programador ya se haya generado activamente cuando se corta y reconstruye la página.)
        viewModelScope.launch {
            assistantRepository.observeAll.guardUpstream("observeAll").collect { assistants ->
                val activeGen = scheduler.activeGroupGeneration.value
                val speaker = activeGen?.currentSpeakerId?.let { id ->
                    assistants.firstOrNull { it.id == id }
                }
                _state.update { it.copy(assistants = assistants, currentSpeaker = speaker) }
            }
        }
        // v1.x: Salida de transmisión de chat grupal — Suscríbete a las retransmisiones de contenidos en streaming de ActivityHub,Mapa al chat grupal actual
        viewModelScope.launch {
            activityHub.streamingContent.guardUpstream("streamingContent").collect { streaming ->
                val chatId = currentChatId.value
                _state.update {
                    it.copy(streamingContent = if (chatId != null) streaming[chatId] else null)
                }
            }
        }
        // v1.53-GC: Observe la última página de mensajes en el chat grupal actual(Flow),Agregue incrementalmente nuevos mensajes a currentMessages.
        // v1.0.21: Anular el bloque de recopilación — Quitar isEmpty() guardia(Hará que se pierda el primer mensaje.),
        //   Reemplace las instantáneas obsoletas con it.currentMessages current(Evite sobrescribir modificaciones simultáneas),
        //   currentMessages Si está vacío, utilice la ventana reciente directamente como primera pantalla.(Flow Asumir la carga inicial).
        viewModelScope.launch {
            currentChatId.flatMapLatest { chatId ->
                if (chatId != null) {
                    groupChatRepository.getPagedMessages(chatId, INITIAL_PAGE_SIZE)
                } else {
                    flowOf(emptyList())
                }
            }.guardUpstream("pagedMessages").collect { recentWindow ->
                if (recentWindow.isEmpty()) return@collect
                // Igual que el guardia chatId:Evite la interferencia entre mensajes antiguos y flujo nuevo al cambiar de chat grupal
                val windowChatId = recentWindow.first().chatId
                if (windowChatId != currentChatId.value) return@collect
                _state.update { state ->
                    val current = state.currentMessages
                    if (current.isEmpty()) {
                        // Por encima del pliegue:Usar directamente recentWindow(Flow Asumir la carga inicial,Complementario a loadInitialPage)
                        state.copy(currentMessages = recentWindow)
                    } else {
                        // v1.0.22: Deduplicación de noticias optimista — recentWindow Cuando se incluye el mensaje real correspondiente,
                        //   Eliminar noticias optimistas(id a "optimistic-" identificador de prefijo)Evite la visualización duplicada.
                        //   Condición de partido:senderType + body Cierre consistente y con marca de tiempo(±2s,
                        //   Anule el retraso del programador al escribir de forma asincrónica en la base de datos).
                        val hasOptimistic = current.any { it.id.startsWith("optimistic-") }
                        val dedupedCurrent = if (hasOptimistic) {
                            val realMessages = recentWindow.filterNot { it.id.startsWith("optimistic-") }
                            val filtered = current.filter { optMsg ->
                                if (!optMsg.id.startsWith("optimistic-")) return@filter true
                                val matched = realMessages.any { real ->
                                    real.senderType == optMsg.senderType &&
                                        real.body == optMsg.body &&
                                        kotlin.math.abs(real.timestamp - optMsg.timestamp) < 2000L
                                }
                                !matched
                            }
                            // Vacío después de la deduplicación(Todas las noticias optimistas se han comparado con noticias reales.)Entonces úsalo directamente recentWindow
                            if (filtered.isEmpty() && recentWindow.isNotEmpty()) recentWindow else filtered
                        } else {
                            current
                        }
                        // Igual que la guardia secundaria chatId
                        val lastLoaded = dedupedCurrent.last()
                        if (lastLoaded.chatId != windowChatId) return@update state
                        // Fusión incremental:Agregue solo el elemento más reciente que el dedupedCurrent (timestamp, id) noticias actualizadas
                        val newMessages = recentWindow.filter { msg ->
                            msg.timestamp > lastLoaded.timestamp ||
                                (msg.timestamp == lastLoaded.timestamp && msg.id > lastLoaded.id)
                        }
                        if (newMessages.isNotEmpty() || dedupedCurrent.size != current.size) {
                            state.copy(currentMessages = dedupedCurrent + newMessages)
                        } else {
                            state
                        }
                    }
                }
            }
        }
        // Observar los metadatos actuales del chat grupal(Para actualizar el título)
        viewModelScope.launch {
            currentChatId.flatMapLatest { chatId ->
                if (chatId != null) groupChatRepository.observeChat(chatId) else flowOf(null)
            }.guardUpstream("observeChat").collect { chat ->
                _state.update { it.copy(currentChat = chat) }
            }
        }
        // Cargar lista de equipo(Leer desde la memoria caché,Bloqueo cero)
        viewModelScope.launch {
            settings.multiAgentConfigFlow.guardUpstream("multiAgentConfig").collect { config ->
                _state.update { it.copy(teams = config.teams) }
            }
        }
        // Cargar preferencias de chat
        viewModelScope.launch {
            settings.chatPreferencesFlow.guardUpstream("chatPreferences").collect { prefs ->
                _state.update { it.copy(chatPreferences = prefs) }
            }
        }
        // v1.111: Suscripción al estado de generación de chat grupal.(Restaurado cuando ViewModel se reconstruye después del corte de página isAgentResponding / currentSpeaker)
        viewModelScope.launch {
            scheduler.activeGroupGeneration.guardUpstream("activeGroupGeneration").collect { gen ->
                if (gen == null) {
                    _state.update {
                        it.copy(
                            isAgentResponding = false,
                            currentSpeaker = null,
                            roundPaused = false,
                            upcomingSpeakers = emptyList(),
                        )
                    }
                } else if (gen.chatId == currentChatId.value) {
                    // Mostrar el estado de generación solo cuando coincida el chat grupal actual(Evitar que otros chats grupales interfieran con la página actual)
                    val speaker = gen.currentSpeakerId?.let { id ->
                        _state.value.assistants.firstOrNull { it.id == id }
                    }
                    _state.update {
                        it.copy(
                            isAgentResponding = gen.isResponding,
                            currentSpeaker = speaker,
                            upcomingSpeakers = gen.upcomingSpeakerNames,
                        )
                    }
                } else {
                    // Se están generando otros chats grupales.,La página actual no se muestra
                    _state.update { it.copy(isAgentResponding = false, currentSpeaker = null) }
                }
            }
        }
        // ActivityHub: Estado de actividad de la suscripción,Derivar la lista de actividades del chat grupal actual para UI(renderizado AgentActivityBar chip).
        // Obtenga solo las actividades del chat grupal actual;No se muestran las actividades de otros chats grupales.,Evite las conversaciones cruzadas en los chats grupales.
        viewModelScope.launch {
            activityHub.activities.guardUpstream("activities").collect { allActivities ->
                val chatId = currentChatId.value
                val current = if (chatId != null) allActivities[chatId] ?: emptyList() else emptyList()
                _state.update { it.copy(activities = current) }
            }
        }
    }

    /**
     * v1.45: Posición de desplazamiento de la lista de caché,Cortar páginas/Reanudar después de la preparación de antecedentes.
     */
    fun onListScrollPositionChanged(index: Int, offset: Int) {
        // L-GC6 Reparación: usar _state.update {} Actualizaciones atómicas,sustituto _state.value = _state.value.copy(...)
        // evitar leer-cambiar-Escribir condiciones de carrera(Las actualizaciones simultáneas pueden perder el estado intermedio durante el desplazamiento de alta frecuencia)
        _state.update {
            it.copy(
                listFirstVisibleItemIndex = index,
                listFirstVisibleItemScrollOffset = offset,
            )
        }
    }

    /**
     * v1.45: Alterna la expansión del bloque de estado de ánimo del mensaje especificado/estado plegado.
     */
    fun toggleMessageMoodExpanded(messageId: String) {
        _state.update { current ->
            val currentState = current.messageExpandedStates[messageId] ?: GroupChatMessageExpandedState()
            val default = current.chatPreferences.moodExpandedByDefault
            val newExpanded = !(currentState.isMoodExpanded ?: default)
            current.copy(
                messageExpandedStates = current.messageExpandedStates +
                    (messageId to currentState.copy(isMoodExpanded = newExpanded)),
            )
        }
    }

    /**
     * v1.45: Alternar la expansión del bloque de razonamiento para el mensaje especificado/estado plegado.
     */
    fun toggleMessageReasoningExpanded(messageId: String) {
        _state.update { current ->
            val currentState = current.messageExpandedStates[messageId] ?: GroupChatMessageExpandedState()
            val default = current.chatPreferences.reasoningExpandedByDefault
            val newExpanded = !(currentState.isReasoningExpanded ?: default)
            current.copy(
                messageExpandedStates = current.messageExpandedStates +
                    (messageId to currentState.copy(isReasoningExpanded = newExpanded)),
            )
        }
    }

    /**
     * Seleccionar chat grupal,Cambie a la observación del flujo de mensajes y active la carga de la pantalla de inicio de paginación.
     *
     * v1.53-GC: Cambiar a carga de página — Borrar mensajes antiguos ahora(Evite las conversaciones cruzadas en los chats grupales),Luego cargue asincrónicamente la última
     * [INITIAL_PAGE_SIZE] Artículo;Los nuevos mensajes son generados por init [getPagedMessages] Flow Anexo incremental.
     *
     * @param chatId chat grupal id
     */
    fun selectChat(chatId: String) {
        currentChatId.value = chatId
        // Borrar inmediatamente mensajes antiguos + estado de paginación,Evite abandonar el contenido antiguo del chat grupal antes de que se cargue la primera pantalla del nuevo chat grupal.
        // ActivityHub: También borre la lista de actividades.,Evite los residuos de chips de los chats grupales antiguos(Las actividades del nuevo chat grupal se derivan nuevamente del suscriptor.)
        _state.update {
            it.copy(
                currentMessages = emptyList(),
                hasMoreHistory = false,
                isLoadingMore = false,
                lastHistoryLoadCount = 0,
                activities = emptyList(),
            )
        }
        loadInitialPage(chatId)
    }

    /**
     * v1.53-GC: Cargando la pantalla de inicio de paginación — Toma el más cercano [INITIAL_PAGE_SIZE] mensajes,Y configure hasMoreHistory según el número total.
     *
     * @param chatId chat grupal id
     */
    private fun loadInitialPage(chatId: String) {
        viewModelScope.launch {
            try {
                val total = groupChatRepository.countMessages(chatId)
                if (total == 0) {
                    _state.update {
                        it.copy(
                            currentMessages = emptyList(),
                            hasMoreHistory = false,
                            isLoadingMore = false,
                            lastHistoryLoadCount = 0,
                        )
                    }
                    return@launch
                }
                val limit = minOf(INITIAL_PAGE_SIZE, total)
                val messages = groupChatRepository.getRecentMessagesPaged(chatId, limit)
                _state.update {
                    it.copy(
                        currentMessages = messages,
                        hasMoreHistory = total > messages.size,
                        isLoadingMore = false,
                        lastHistoryLoadCount = 0,
                    )
                }
            } catch (e: Exception) {
                Logger.e(TAG, "No se pudo cargar la paginación de la página de inicio del chat grupal chatId=$chatId", e)
                _state.update { it.copy(isLoadingMore = false, hasMoreHistory = false) }
            }
        }
    }

    /**
     * v1.53-GC: Desliza hacia arriba para cargar más noticias históricas.
     *
     * Obtenga el mensaje más antiguo de los mensajes actuales actuales (timestamp, id) como doble ancla,Obtener de DB antes de este punto de anclaje
     * [LOAD_MORE_PAGE_SIZE] mensajes,Anteponer a mensajes actuales.
     *
     * - isLoadingMore=true salto de tiempo(Prevenir disparos repetidos)
     * - hasMoreHistory=false salto de tiempo(cargado)
     * - currentMessages Saltar si está vacío(La primera pantalla aún no se ha cargado.)
     * - Establecer después de que se complete la carga [GroupChatUiState.lastHistoryLoadCount],UI Ajuste la posición de desplazamiento después de escuchar para mantener la posición visual
     */
    fun loadMoreHistory() {
        val state = _state.value
        if (state.isLoadingMore || !state.hasMoreHistory) return
        val chatId = currentChatId.value ?: return
        val firstMsg = state.currentMessages.firstOrNull() ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            try {
                val older = groupChatRepository.getOlderMessages(
                    chatId = chatId,
                    beforeTimestamp = firstMsg.timestamp,
                    beforeId = firstMsg.id,
                    limit = LOAD_MORE_PAGE_SIZE,
                )
                if (older.isEmpty()) {
                    _state.update { it.copy(hasMoreHistory = false, isLoadingMore = false) }
                    return@launch
                }
                // Releer lo último state.currentMessages,Evitar que se sobrescriban los mensajes nuevos agregados por Flow durante la carga
                val current = _state.value.currentMessages
                _state.update {
                    it.copy(
                        currentMessages = older + current,
                        hasMoreHistory = older.size >= LOAD_MORE_PAGE_SIZE,
                        isLoadingMore = false,
                        lastHistoryLoadCount = older.size,
                    )
                }
            } catch (e: Exception) {
                Logger.e(TAG, "El chat grupal no pudo cargar más historial chatId=$chatId", e)
                _state.update { it.copy(isLoadingMore = false, hasMoreHistory = false) }
            }
        }
    }

    /**
     * v1.53-GC: Borrar [GroupChatUiState.lastHistoryLoadCount].
     *
     * UI en [GroupChatDetailScreen] Supervisar los cambios de lastHistoryLoadCount(>0)después,sintonizar
     * [androidx.compose.foundation.lazy.LazyListState.scrollToItem] Omitir la cantidad de elementos recién insertados,
     * Evita que la posición visual salte.,Luego llame a este método para borrarlo.(Evite saltos repetidos durante la reorganización).
     */
    fun clearHistoryLoadCount() {
        if (_state.value.lastHistoryLoadCount != 0) {
            _state.update { it.copy(lastHistoryLoadCount = 0) }
        }
    }

    /**
     * Actualice el texto del cuadro de entrada.
     */
    fun updateInput(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    /**
     * Añadir fotos para enviar(base64 cuerda).
     */
    fun addPendingImage(base64Image: String) {
        _state.update { it.copy(pendingImages = it.pendingImages + base64Image) }
    }

    /**
     * Elimina la imagen especificada que se enviará.
     */
    fun removePendingImage(base64Image: String) {
        _state.update { it.copy(pendingImages = it.pendingImages - base64Image) }
    }

    /**
     * Borrar todas las imágenes pendientes.
     */
    fun clearPendingImages() {
        _state.update { it.copy(pendingImages = emptyList()) }
    }

    fun addPendingFileAttachment(attachment: FileAttachment) {
        _state.update { it.copy(pendingFileAttachments = it.pendingFileAttachments + attachment) }
    }

    fun removePendingFileAttachment(index: Int) {
        val list = _state.value.pendingFileAttachments.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            _state.update { it.copy(pendingFileAttachments = list) }
        }
    }

    // ── v2.x: Rotar el control del director(pausa/continuar/saltar restante) ──────────────────
    // La combinación de pausa + reanudación es"Un solo paso adelante":Deténgase y observe más de cerca después de que cada miembro sea liberado.

    /** v2.x: Pausar la rotación del chat grupal actual(Cuelgue antes de que comience el próximo miembro.). */
    fun pauseRound() {
        currentChatId.value?.let { id ->
            scheduler.pauseRound(id)
            _state.update { it.copy(roundPaused = true) }
        }
    }

    /** v2.x: Continuar una rotación pausada. */
    fun resumeRound() {
        currentChatId.value?.let { id ->
            scheduler.resumeRound(id)
            _state.update { it.copy(roundPaused = false) }
        }
    }

    /** v2.x: Salta a los miembros restantes de esta ronda.,Liquidación inmediata(reserva hablada). */
    fun skipRoundRemaining() {
        currentChatId.value?.let { id ->
            scheduler.skipRoundRemaining(id)
            _state.update { it.copy(roundPaused = false) }
        }
    }

    /**
     * v2.x: Precipitar mensajes resumidos en documentos compartidos en grupo(Cristalización de la discusión).
     *
     * Después de la precipitación,La próxima vez que un miembro hable, el documento se insertará automáticamente en la sección [Documento compartido del grupo] del indicador del sistema.——
     * Los resultados de la discusión provienen de"un mensaje"convertirse en"Antecedentes de consenso de todos los miembros.",Se proporcionan referencias para discusión posterior.
     */
    fun saveSummaryAsSharedDoc(messageId: String) {
        val chatId = currentChatId.value ?: return
        viewModelScope.launch {
            val message = _state.value.currentMessages.firstOrNull { it.id == messageId } ?: return@launch
            if (message.body.isBlank()) return@launch
            // v2.3.1: El título toma recurso de cadena(7 Idioma);El formato de marca de tiempo es independiente del idioma.,quédate como está
            val stamp = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(message.timestamp))
            val title = appContext.getString(R.string.groupchat_summary_doc_title, stamp)
            runCatching {
                groupChatRepository.addSharedDoc(chatId, title, message.body)
            }.onFailure { t ->
                Logger.w("GroupChatViewModel", "No se pudo guardar el resumen como documento compartido", t)
            }
        }
    }

    /**
     * Envíe mensajes de usuario y active respuestas de rotación de agentes.
     *
     * v1.111: Correr en rotación [GroupChatScheduler.launchRoundRobin] en aplicaciónScope,
     * Cortar páginas/No hay interrupción en el fondo.ViewModel Solo responsable de verificar previamente + borrar el cuadro de entrada + delegarlo al programador.
     *
     * @param text Texto del mensaje
     */
    fun sendMessage(text: String) {
        val chatId = currentChatId.value ?: return
        if (text.isBlank() && _state.value.pendingImages.isEmpty() && _state.value.pendingFileAttachments.isEmpty()) return
        // v1.126: debounce — Evite que los clics rápidos se envíen repetidamente
        val now = System.currentTimeMillis()
        if (now - lastSendTimestamp < SEND_DEBOUNCE_MS) return
        lastSendTimestamp = now
        // v2.x: mecanismo de interjección — Los mensajes de usuario no se descartan durante la rotación.:Interrumpe primero la ronda actual.(Se mantiene lo dicho),
        // Después de que se retire la ronda anterior, comience una nueva ronda con este mensaje,Todos los miembros pueden ver los últimos comentarios del usuario.
        val interrupting = scheduler.hasActiveGeneration(chatId)
        if (interrupting) scheduler.stop(chatId)

        val images = _state.value.pendingImages
        val fileAttachments = _state.value.pendingFileAttachments
        _state.update { it.copy(inputText = "", pendingImages = emptyList(), pendingFileAttachments = emptyList()) }
        // v1.0.52: text Cuando está vacío pero con fotos.,Agregar texto de marcador de posición al cuerpo,
        // Informe a LLM que el usuario publicó la imagen.(De lo contrario, el planificador/LLM puede ignorar el cuerpo vacío.)
        val effectiveText = when {
            text.isBlank() && images.isNotEmpty() -> "[fotos]"
            text.isBlank() && fileAttachments.isNotEmpty() -> "[Archivo]"
            else -> text
        }
        // v1.0.21: Actualización optimista de la interfaz de usuario — Agregue inmediatamente mensajes de usuario a currentMessages,
        //   No depende del reflujo de Room Flow(Razón original recoger guardia/La condición de carrera hace que el mensaje no se muestre después de enviarse)
        // v1.0.22: ID de noticias optimista plus "optimistic-" prefijo,collect Identifique y elimine duplicados basándose en esto al fusionar,
        //   Evite que los mensajes reales escritos por el programador en la base de datos se agreguen después de redistribuirse, lo que resulta en una visualización repetida
        val imageJson = if (images.isNotEmpty()) {
            json.encodeToString(images)
        } else {
            "[]"
        }
        val optimisticMsg = GroupChatMessageEntity(
            id = "optimistic-" + java.util.UUID.randomUUID().toString(),
            chatId = chatId,
            senderType = "user",
            senderId = "local_user",
            senderName = "yo",
            body = effectiveText,
            imageBase64Json = imageJson,
            fileAttachmentsJson = if (fileAttachments.isNotEmpty()) {
                kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(FileAttachment.serializer()),
                    fileAttachments,
                )
            } else {
                "[]"
            },
            timestamp = now,
        )
        _state.update { it.copy(currentMessages = it.currentMessages + optimisticMsg) }
        if (interrupting) {
            // v2.x: Esperando que se retire la vieja rueda interrumpida(la mayoría 3s),Evite escribir el mismo chat grupal en dos rondas simultáneamente
            viewModelScope.launch {
                var waited = 0
                while (scheduler.hasActiveGeneration(chatId) && waited < 30) {
                    delay(100)
                    waited++
                }
                scheduler.launchRoundRobin(chatId, effectiveText, images, fileAttachments)
            }
        } else {
            scheduler.launchRoundRobin(chatId, effectiveText, images, fileAttachments)
        }
    }

    /**
     * v1.111: Detenga manualmente la generación actual del chat grupal.
     */
    fun stopGeneration() {
        val chatId = currentChatId.value ?: return
        scheduler.stop(chatId)
    }

    /**
     * v2.x: Especifica que la IA regenera su último mensaje.
     */
    fun regenerateAgentMessage(assistantId: String) {
        val chatId = currentChatId.value ?: return
        if (scheduler.hasActiveGeneration(chatId)) return
        scheduler.regenerateAgentMessage(chatId, assistantId)
    }

    /**
     * v2.x: Iniciar una votación.
     */
    fun launchVote(topic: String) {
        val chatId = currentChatId.value ?: return
        if (scheduler.hasActiveGeneration(chatId)) return
        scheduler.launchVote(chatId, topic)
    }

    /**
     * v2.x: Resumen de conclusiones.
     */
    fun launchSummary(summarizerId: String? = null) {
        val chatId = currentChatId.value ?: return
        if (scheduler.hasActiveGeneration(chatId)) return
        scheduler.launchSummary(chatId, summarizerId)
    }

    /**
     * v2.x: Envía un mensaje privado a la IA especificada.
     */
    fun sendWhisper(targetAssistantId: String, text: String) {
        val chatId = currentChatId.value ?: return
        if (text.isBlank()) return
        if (scheduler.hasActiveGeneration(chatId)) return
        scheduler.launchWhisper(chatId, targetAssistantId, text)
    }

    // ── v2.x Gestión del contexto del chat grupal:Documentos compartidos en grupo + contexto exclusivo de IA ──

    /**
     * v2.x: Agregue documentos compartidos en grupo.
     *
     * @param title Título del documento
     * @param content Texto del documento
     */
    fun addSharedDoc(title: String, content: String) {
        val chatId = currentChatId.value ?: return
        if (title.isBlank() || content.isBlank()) return
        viewModelScope.launch {
            try {
                groupChatRepository.addSharedDoc(chatId, title.trim(), content)
            } catch (e: Exception) {
                Logger.e(TAG, "No se pudo agregar el documento compartido del grupo", e)
                _state.update { it.copy(errorMessage = appContext.getString(R.string.err_group_chat_update_failed)) }
            }
        }
    }

    /**
     * v2.x: Eliminar documentos compartidos del grupo.
     *
     * @param docId Documentación id
     */
    fun removeSharedDoc(docId: String) {
        val chatId = currentChatId.value ?: return
        viewModelScope.launch {
            try {
                groupChatRepository.removeSharedDoc(chatId, docId)
            } catch (e: Exception) {
                Logger.e(TAG, "No se pudo eliminar el documento compartido del grupo", e)
                _state.update { it.copy(errorMessage = appContext.getString(R.string.err_group_chat_update_failed)) }
            }
        }
    }

    /**
     * v2.x: Establecer un contexto exclusivo para un miembro del grupo(Visible sólo para esta IA).
     *
     * @param assistantId miembro AI id
     * @param contextText Texto contextual exclusivo(Se borra la cadena vacía)
     */
    fun setMemberPrivateContext(assistantId: String, contextText: String) {
        val chatId = currentChatId.value ?: return
        viewModelScope.launch {
            try {
                groupChatRepository.setMemberPrivateContext(chatId, assistantId, contextText)
            } catch (e: Exception) {
                Logger.e(TAG, "No se pudo establecer el contexto específico del miembro", e)
                _state.update { it.copy(errorMessage = appContext.getString(R.string.err_group_chat_update_failed)) }
            }
        }
    }

    /**
     * Crea un nuevo chat grupal.
     *
     * @param name Nombre del chat grupal
     * @param memberIds Lista de identificación de ayudante de miembros
     * @param teamId Equipo asociado opcional id
     * @return Chat grupal recién creado id
     */
    suspend fun createChat(name: String, memberIds: List<String>, teamId: String? = null): String {
        return groupChatRepository.createChat(name, memberIds, teamId)
    }

    /**
     * v1.126: Borre el mensaje de error.
     */
    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /**
     * Eliminar chat grupal(Elimina en cascada todos sus mensajes.).
     *
     * @param chatId chat grupal id
     */
    fun deleteChat(chatId: String) {
        viewModelScope.launch {
            try {
                groupChatRepository.deleteChat(chatId)
                // Si eliminas el chat grupal actual,Borre el estado seleccionado y notifique a la página de detalles para regresar
                if (currentChatId.value == chatId) {
                    currentChatId.value = null
                    // M7: Establecer bandera de eliminación,La página de detalles volverá automáticamente en consecuencia.
                    // v1.53-GC: El flujo ya no se reenvía completo en modo de paginación,Borrar mensajes manualmente + estado de búsqueda
                    _state.update {
                        it.copy(
                            chatDeleted = true,
                            currentMessages = emptyList(),
                            hasMoreHistory = false,
                            isLoadingMore = false,
                            lastHistoryLoadCount = 0,
                        )
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "No se pudo eliminar el chat grupal", e)
                _state.update { it.copy(errorMessage = appContext.getString(R.string.err_group_chat_delete_failed)) }
            }
        }
    }

    /**
     * v1.0.72: Borrar la memoria del chat grupal del chat grupal especificado(Limpieza de residuos de estilo).
     *
     * La memoria del chat grupal es independiente del sistema de memoria principal.(Si el usuario borra la memoria principal, no podrá borrarla aquí.),
     * El mal estilo del período de prueba histórico puede permanecer en el resumen y ser inyectado en él. system prompt,
     * Proporcione una entrada de compensación explícita para que los usuarios la borren con un solo clic.
     *
     * @param chatId chat grupal id
     */
    fun clearChatMemory(chatId: String) {
        viewModelScope.launch {
            try {
                groupChatMemoryRepository?.deleteByGroupChat(chatId)
                Logger.i(TAG, "Se borró la memoria del chat grupal: $chatId")
            } catch (e: Exception) {
                Logger.e(TAG, "No se pudo borrar la memoria del chat grupal", e)
                _state.update { it.copy(errorMessage = "No se pudo borrar la memoria del chat grupal") }
            }
        }
    }

    /**
     * v1.0.72: Buscar mensajes en el chat grupal(Texto de coincidencia de palabras clave,en orden cronológico inverso).
     *
     * @param query Buscar palabras clave,Borrar resultados cuando esté en blanco
     */
    fun searchMessages(query: String) {
        val chatId = currentChatId.value ?: return
        if (query.isBlank()) {
            _state.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }
        _state.update { it.copy(isSearching = true) }
        viewModelScope.launch {
            try {
                val results = groupChatRepository.searchMessages(chatId, query)
                _state.update { it.copy(searchResults = results, isSearching = false) }
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                Logger.e(TAG, "La búsqueda del chat grupal falló", e)
                _state.update { it.copy(searchResults = emptyList(), isSearching = false) }
            }
        }
    }

    /**
     * v1.0.72: Actualizar el proveedor del asistente especificado/modelo(per-assistant,chat grupal"Proveedor de asistente de edición"usar).
     */
    fun updateAssistantModel(assistantId: String, providerId: String?, modelId: String?) {
        viewModelScope.launch {
            try {
                val current = assistantRepository.getById(assistantId) ?: return@launch
                assistantRepository.upsert(
                    current.copy(providerId = providerId, modelId = modelId),
                )
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                Logger.e(TAG, "Error en la actualización del modelo auxiliar", e)
            }
        }
    }

    /**
     * v1.77: Eliminar un solo mensaje en un chat grupal.
     *
     * @param messageId noticias id
     */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            try {
                groupChatRepository.deleteMessage(messageId)
                // v1.53-GC: En el modo de paginación, Flow solo agrega mensajes nuevos de forma incremental y no reenvía la cantidad total.,
                // Los mensajes eliminados deben eliminarse manualmente de mensajes actuales(evitar residuos)
                _state.update { state ->
                    state.copy(currentMessages = state.currentMessages.filterNot { it.id == messageId })
                }
            } catch (e: Exception) {
                Logger.e(TAG, "No se pudo eliminar el mensaje", e)
                _state.update { it.copy(errorMessage = appContext.getString(R.string.err_group_chat_delete_msg_failed)) }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // v1.x: Modo de selección múltiple(Menú de pulsación larga"elegir"entrar,Admite eliminación por lotes)
    // ══════════════════════════════════════════════════════════════════

    /** Ingrese al modo de selección múltiple y seleccione el mensaje actual. */
    fun startSelection(messageId: String) {
        _state.update { it.copy(selectedMessageIds = setOf(messageId)) }
    }

    /** Alterna el estado seleccionado de un solo mensaje. */
    fun toggleMessageSelection(messageId: String) {
        _state.update { state ->
            val current = state.selectedMessageIds
            if (current.isEmpty()) {
                // Hacer clic cuando no se está en modo de selección múltiple se considera ingresado y seleccionado.
                state.copy(selectedMessageIds = setOf(messageId))
            } else {
                val next = if (messageId in current) current - messageId else current + messageId
                state.copy(selectedMessageIds = next)
            }
        }
    }

    /**
     * v1.0.72: Buscar haga clic para saltar — Desplácese hasta la ubicación del mensaje deseado y resáltela.
     * Implementación sencilla:Localizar mensaje en mensajes actuales index,UI Escucha lateral de desplazamiento jumpTargetId.
     */
    private val _jumpTargetId = MutableStateFlow<String?>(null)
    val jumpTargetId: StateFlow<String?> = _jumpTargetId.asStateFlow()

    fun jumpToMessage(messageId: String) {
        _jumpTargetId.value = messageId
        // Resalte brevemente y luego borre
        viewModelScope.launch {
            delay(3000)
            if (_jumpTargetId.value == messageId) _jumpTargetId.value = null
        }
    }

    /** Seleccione todos los mensajes actualmente visibles. */
    fun selectAllVisible() {
        _state.update { state ->
            state.copy(selectedMessageIds = state.currentMessages.map { it.id }.toSet())
        }
    }

    /** Salga del modo de selección múltiple. */
    fun clearSelection() {
        _state.update { it.copy(selectedMessageIds = emptySet()) }
    }

    /** Eliminar mensajes seleccionados en lotes. */
    fun deleteSelectedMessages() {
        val ids = _state.value.selectedMessageIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { id ->
                try {
                    groupChatRepository.deleteMessage(id)
                } catch (e: Exception) {
                    Logger.e(TAG, "No se pudieron eliminar mensajes en lotes: $id", e)
                }
            }
            _state.update { state ->
                state.copy(
                    currentMessages = state.currentMessages.filterNot { it.id in ids },
                    selectedMessageIds = emptySet(),
                )
            }
        }
    }

    /**
     * Cambia el chat grupal al estado superior.
     *
     * @param chatId chat grupal id
     */
    fun togglePin(chatId: String) {
        viewModelScope.launch {
            try {
                groupChatRepository.togglePin(chatId)
            } catch (e: Exception) {
                Logger.e(TAG, "No se pudo cambiar al principio", e)
                _state.update { it.copy(errorMessage = appContext.getString(R.string.err_group_chat_operation_failed)) }
            }
        }
    }

    /**
     * v2.x: Alternar el estado del archivo del chat grupal.
     */
    fun toggleArchive(chatId: String, archived: Boolean) {
        viewModelScope.launch {
            try {
                groupChatRepository.toggleArchive(chatId, archived)
            } catch (e: Exception) {
                Logger.e(TAG, "Error en la operación de archivado", e)
                _state.update { it.copy(errorMessage = appContext.getString(R.string.err_group_chat_update_failed)) }
            }
        }
    }

    /**
     * v2.x: Cree una plantilla de chat grupal rápida para su equipo de redacción.
     * Utilice la plantilla del equipo de redacción para que los miembros del equipo creen automáticamente chats grupales y asocien equipos de redacción.
     */
    fun createWritingGroupChat() {
        viewModelScope.launch {
            try {
                val config = settings.multiAgentConfigCache
                val writingTeam = config.teams.find { it.name == appContext.getString(R.string.settings_multi_agent_writing_team_name) }
                if (writingTeam != null && writingTeam.memberIds.isNotEmpty()) {
                    val chatId = groupChatRepository.createChat(
                        name = appContext.getString(R.string.settings_multi_agent_writing_team_name),
                        memberIds = writingTeam.memberIds,
                        teamId = writingTeam.id,
                    )
                    // Abrir el chat grupal creado
                    selectChat(chatId)
                }
            } catch (e: Exception) {
                Logger.e(TAG, "No se pudo crear el chat del grupo de escritura", e)
                _state.update { it.copy(errorMessage = appContext.getString(R.string.err_group_chat_create_failed)) }
            }
        }
    }

    /**
     * v1.97: Actualizar la información del chat grupal(Nombre/Descripción/miembro).
     *
     * @param chatId chat grupal id
     * @param name nuevo nombre(null No indica ningún cambio)
     * @param description nueva descripción(null No indica ningún cambio)
     * @param memberIds Nueva lista de miembros(null No indica ningún cambio)
     */
    fun updateChat(
        chatId: String,
        name: String? = null,
        description: String? = null,
        memberIds: List<String>? = null,
        discussionMode: String? = null,
        autoMaxRounds: Int? = null,
        hostId: String? = null,
        replyLengthMode: String? = null,
        observerIds: List<String>? = null,
    ) {
        viewModelScope.launch {
            try {
                groupChatRepository.updateChat(chatId, name, description, memberIds, discussionMode, autoMaxRounds, hostId, replyLengthMode, observerIds)
            } catch (e: Exception) {
                Logger.e(TAG, "No se pudo actualizar el chat grupal", e)
                _state.update { it.copy(errorMessage = appContext.getString(R.string.err_group_chat_update_failed)) }
            }
        }
    }

    /**
     * Analice la lista de ID de miembros del chat grupal.
     */
    fun parseMemberIds(chat: GroupChatEntity): List<String> = groupChatRepository.parseMemberIds(chat)

    /** v2.x: Analizar la lista de identificación de miembros observadores del chat grupal(Editar cuadro de diálogo preseleccionado). */
    fun parseObserverIds(chat: GroupChatEntity): List<String> = groupChatRepository.parseObserverIds(chat)

    /**
     * v2.x: Analizar la lista de documentos compartidos de un chat grupal(Para renderizado de interfaz de usuario).
     */
    fun parseSharedDocs(chat: GroupChatEntity): List<io.zer0.muse.data.groupchat.GroupSharedDoc> = groupChatRepository.parseSharedDocs(chat)

    /**
     * v2.x: Analizando el contexto exclusivo de los miembros del chat grupal Map(Para renderizado de interfaz de usuario).
     */
    fun parseMemberPrivateContext(chat: GroupChatEntity): Map<String, String> = groupChatRepository.parseMemberPrivateContext(chat)

    /**
     * Obtenga el último mensaje del chat grupal especificado(Para obtener una vista previa de la página de lista).
     */
    suspend fun getLatestMessage(chatId: String): GroupChatMessageEntity? = groupChatRepository.getLatestMessage(chatId)
}
