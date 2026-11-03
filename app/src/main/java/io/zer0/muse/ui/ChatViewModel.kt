package io.zer0.muse.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelContextWindowRegistry
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderError
import io.zer0.ai.core.ProviderException
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.UIMessage
import io.zer0.ai.core.inferFromMessage
import io.zer0.ai.image.ImageService
import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.summary.SessionSummaryManager
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.util.ErrorMessages
import io.zer0.muse.data.MultiAgentConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.SpeechProviderResolver
import io.zer0.muse.data.withResolvedEndpoint
import io.zer0.muse.network.NetworkMonitor
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.data.artifact.ArtifactRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.audit.AuditLogger
import io.zer0.muse.data.milestone.MilestoneChecker
import io.zer0.muse.data.lorebook.LorebookEntity
import io.zer0.muse.data.lorebook.LorebookRepository
import io.zer0.muse.data.promptinjection.PromptInjectionEntity
import io.zer0.muse.data.promptinjection.PromptInjectionRepository
import io.zer0.muse.data.quickmsg.QuickMessageEntity
import io.zer0.muse.data.quickmsg.QuickMessageRepository
import io.zer0.muse.data.session.SearchResult
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.doc.DocumentParser
import io.zer0.muse.R
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.schedule.ChatGenerationManager
import io.zer0.muse.schedule.ChatGenerationService
import io.zer0.muse.schedule.UserActivityProfile
import io.zer0.muse.tools.ToolApprovalPolicy
import io.zer0.muse.tools.AgentRouter
import io.zer0.muse.tools.DelegationContract
import io.zer0.muse.tools.DelegationContextBuilder
import io.zer0.muse.tools.ToolApprovalState
import io.zer0.muse.tools.ToolConfigStore
import io.zer0.muse.tools.ToolPermissionResolver
import io.zer0.muse.tools.SessionPermissionMode
import io.zer0.muse.tools.SessionPermissionStore
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import io.zer0.muse.chat.InternalPromptMarkers
import io.zer0.muse.chat.PendingToolCallStore
import io.zer0.muse.data.chat.ConversationTree
import io.zer0.muse.data.chat.ConversationTreeSnapshotStore
import io.zer0.muse.data.chat.rewrite.ConversationEventDraft
import io.zer0.muse.data.chat.rewrite.ConversationService
import io.zer0.muse.data.chat.rewrite.MessageCommit
import io.zer0.muse.transformer.CompressionDiagnostics
import io.zer0.muse.transformer.ContextCompressTransformer
import io.zer0.muse.transformer.ConversationMemoryInjectionTransformer
import io.zer0.muse.transformer.LorebookTransformer
import io.zer0.muse.transformer.PromptInjectionTransformer
import io.zer0.muse.transformer.TemplateTransformer
import io.zer0.muse.transformer.ThinkTagTransformer
import io.zer0.muse.transformer.TransformContext
import io.zer0.muse.transformer.TransformerPipeline
import io.zer0.muse.transformer.splitThinkTagsForStreaming
import io.zer0.muse.ui.chat.ChatStateAccessor
import io.zer0.muse.ui.chat.ContextTokenUsage
import io.zer0.muse.ui.chat.ChatAudioCoordinator
import io.zer0.muse.ui.chat.ChatGenerationController
import io.zer0.muse.ui.chat.GenerationDeps
import io.zer0.muse.ui.chat.ChatInputController
import io.zer0.muse.ui.chat.ChatMediaController
// P2-23: Host de entrega de sesiones para herramientas de generación de medios(El cuerpo de la herramienta se ha movido a io.zer0.muse.tools.MediaGenToolsImpl)
import io.zer0.muse.ui.chat.ChatMediaGenHost
import io.zer0.muse.ui.chat.ChatMessageController
import io.zer0.muse.ui.chat.ChatSessionController
import io.zer0.muse.ui.chat.SessionDeps
import io.zer0.muse.ui.chat.ChatSettingsController
import io.zer0.muse.ui.chat.SessionModelSelectionStore
import io.zer0.muse.ui.chat.ChatStateStore
import io.zer0.muse.ui.chat.SystemPromptCache
import io.zer0.muse.ui.chat.ChatVoiceState
import io.zer0.muse.ui.chat.ChatGenerationState
import io.zer0.muse.ui.chat.SendRequest
import io.zer0.muse.ui.chat.ChatToolController
import io.zer0.muse.ui.chat.SessionFlowBridge
import io.zer0.muse.ui.chat.ChatDocumentCoordinator
import io.zer0.muse.ui.chat.ChatExportCoordinator
import io.zer0.muse.ui.chat.ChatMiscCoordinator
import io.zer0.muse.ui.chat.ChatStreamCoordinator
import io.zer0.muse.ui.chat.ChatTaskCardCoordinator
import io.zer0.muse.ui.chat.ImageGenCoordinator
import io.zer0.muse.ui.chat.SlashCommand
import io.zer0.muse.ui.chat.StreamRunState
import io.zer0.muse.ui.chat.TokenCountSnapshot
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.speech.TtsManager
import io.zer0.muse.ui.speech.PlaybackState
import io.zer0.muse.ui.speech.VoiceConversationState
import io.zer0.muse.ui.taskcard.AgentPlan
import io.zer0.muse.util.TokenEstimator
import io.zer0.muse.util.retryOnNetworkError
import io.zer0.muse.web.WebSearchConfig
import io.zer0.muse.web.WebSearchService
import io.zer0.muse.asr.ASRState
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * v0.49: Mensaje de error de chat(Soporta la coexistencia de múltiples errores.).
 *
 * @param id El único error id(usado para dismiss)
 * @param type Tipo de error
 * @param message mensaje de error
 * @param timestamp tiempo de ocurrencia
 * @param isRecoverable ¿Es recuperable?(Si hay un error de red, puedes intentarlo nuevamente.,API key Los errores requieren manejo por parte del usuario)
 */
data class ChatError(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: ChatErrorType,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isRecoverable: Boolean = true,
)

/** v0.49: Enumeración de tipo de error,Se utiliza para visualización de clasificación y estrategias de procesamiento. */
enum class ChatErrorType {
    NETWORK, // error de red
    API_KEY, // API key Inválido
    RATE_LIMIT, // Limitación de corriente
    MODEL_ERROR, // El modelo devuelve error
    TOOL_ERROR, // Error de ejecución de herramienta
    UNKNOWN, // error desconocido
}

/**
 * Estado de la página de chat.
 *
 * # v1.0.20: Descripción de agrupación de campos(división progresiva,Controlar riesgos)
 *
 * actual [ChatUiState] Contiene docenas de campos.,Cualquier cambio desencadena un nuevo valor de estado.,Provocando una reorganización inesperada.
 * división completa(Mover campos a subestados data class)Grandes cambios,Es necesario cambiar todos los puntos de acceso.,El riesgo es alto.
 * Por lo tanto, el progresivo:Mantener la estructura de nivel superior sin cambios,Comentarios grupales por frecuencia de cambio.,UI Las capas utilizan deriveStateOf para limitar el alcance de la reorganización.
 *
 * ## Campos muy cambiantes(UI Sensible a la recombinación,Se recomienda utilizar deriveStateOf para ajustar la lectura.)
 *  - [input]:Cambia cada vez que el usuario presiona una tecla
 *  - [isStreaming]:Comienza la transmisión/cambio final
 *  - [isWaitingFirstToken]:Cambios antes y después de la llegada del primer token
 *  - [visionProgress]:El análisis visual cambia cada vez que se completa una imagen.
 *  - [isOcrProcessing] / [toolProgressMessage]:Espere brevemente el cambio de fase.
 *  - [isSpeaking] / [speakingMessageId]:TTS Leer en voz alta los cambios de estado
 *  - [asrState]:ASR Cambios de estado de transmisión
 *  - [messages]:Cada delta se actualiza durante la transmisión.(Estrangulado por StreamController)
 *
 * ## campo cambiante de baja frecuencia(Configuración/sesión/Lista, etc.,La reestructuración tiene poco impacto)
 *  - [sessions] / [archivedSessions] / [folders]:Cambios en la lista de conversaciones
 *  - [assistants] / [currentAssistant]:Cambios en la configuración del asistente
 *  - [providers] / [activeProviderId] / [selectedModelId]:Provider Cambios de configuración
 *  - [chatPreferences] / [mediaConfig] / [webSearchConfig]:Cambios de configuración
 *  - [favoriteMessages] / [quickMessages] / [lorebooks]:Listar cambios de datos
 *
 * ## derivado/Campos transitorios(Derivado de otros campos,No impulsa directamente la reorganización)
 *  - [isConfigured]:Derivado de proveedores
 *  - [error]:por errors.firstOrNull() derivado
 *  - [toast]:Desechable Toast,UI Vacío después del consumo
 *
 * Seguimiento de la hoja de ruta dividida completa(No lo haré esta vez,marca TODO):
 *  - TODO(state-split): Extraer campos de alta frecuencia `ChatUIState`(input/isStreaming/isWaitingFirstToken/visionProgress)
 *  - TODO(state-split): Extraer herramientas relacionadas con `ToolsState`(toolCallHistory/pendingToolApprovals/taskCards)
 *  - TODO(state-split): Extraer información relacionada con el agente `ChatAgentState`(isAgentMode/agentSessionId/delegationChain)
 *
 * @param messages Lista completa de mensajes(mensaje de transmisión del asistente con marcador de posición)
 * @param input Texto del cuadro de entrada actual
 * @param isStreaming Si la salida es streaming
 * @param errors v0.49: Lista de mensajes de error(Soporta la coexistencia de múltiples errores.),Vacío significa que no hay error
 * @param isConfigured ¿Se ha configurado? Provider(decide mostrar"Ir a configuración"guía)
 * @param currentSessionId sesión actual id(null Indica no inicializado)
 * @param sessions Lista de todas las conversaciones(Para barra lateral,Orden descendente por actualizadoEn)
 * @param isDrawerOpen Si la barra lateral está expandida
 * @param searchQuery texto del cuadro de búsqueda
 * @param searchResults Lista de resultados de búsqueda(Una lista vacía significa que no hay búsqueda o no hay resultados.)
 * @param isSearching estas buscando
 * @param isDrawMode modo de dibujo(P5-G):Después de habilitar, envíe llamadas ImageService en lugar de ChatService
 * @param isGeneratingImage Si se están generando imágenes(P5-G)
 * @param isTranslating ¿Se está traduciendo?(P5-F)
 * @param translatingMessageId Mensaje en traducción id(null significa ninguno)
 * @param assistants Lista de todos los asistentes(Phase 8.2 Para selector de barra lateral)
 * @param currentAssistant Vinculado a la sesión actual Assistant(Phase 8.2,Afecta al sistemaIndicación/modelo/Herramientas/memoria)
 * @param favoriteMessages Lista de mensajes favoritos entre sesiones(Phase 8.3,Para panel de colección)
 */

/**
 * F-10: Máquina de estado de fase de generación de streaming(fuente estatal única).
 *
 *  - [IDLE]: ninguna generación;
 *  - [CONNECTING]: Enviado,Esperando el primero token;
 *  - [STREAMING]: Salida de transmisión de texto;
 *  - [TOOLING]: Fase de ejecución de llamada de herramienta;
 *  - [INTERRUPTED]: Detenido por el usuario/Cambio de sesión interrumpido,Contenido reservado;
 *  - [FINISHED]: Completado normalmente;
 *  - [FAILED]: Terminar en caso de error.
 *
 * UI/Notificación/Las estadísticas están unificadas de [ChatStreamState.phase] derivado, No juzgues los valores booleanos dispersos individualmente.
 */
enum class ChatStreamPhase { IDLE, CONNECTING, STREAMING, TOOLING, INTERRUPTED, FINISHED, FAILED }

data class ChatStreamState(
    /** F-10: fase de generación(fuente estatal única;isStreaming etc. Bool sigue siendo compatible con derivados). */
    val phase: ChatStreamPhase = ChatStreamPhase.IDLE,
    val isStreaming: Boolean = false,
    /**
     * v1.0.3: ¿El streaming sigue esperando el primero? token(Contiene ContentDelta / ReasoningDelta).
     *
     * - true: en"Pensando"escenario,UI Mostrar la pantalla esqueleto de ThinkingBubble + "Pensando"texto
     * - false: Recibido primero token,entrar"escribiendo"escenario,UI Mostrar cursor StreamingCursor + texto de transmisión
     *
     * con [isStreaming] relación:
     *  - Al enviar:isStreaming=true, isWaitingFirstToken=true
     *  - Llega la primera ficha:isStreaming=true, isWaitingFirstToken=false
     *  - Fin de la transmisión/error/detenerse:isStreaming=false, isWaitingFirstToken=false
     *
     * Los beneficios de distinguir las dos etapas:
     *  1. Haz que ThinkingBubble desaparezca inmediatamente después de que llegue el primer token,evitar"loading → de repente mucho texto"falla visual
     *  2. UI Se pueden dar diferentes comentarios visuales a las dos etapas respectivamente.(Pantalla esqueleto vs cursor de transmisión)
     *  3. reasoning-only escenario(El modelo solo genera la cadena de pensamiento.,content Todavía vacío)no será mal juzgado como"todavía esperando"
     */
    val isWaitingFirstToken: Boolean = false,
    /**
     * v1.0.4: Ayudas visuales para analizar el progreso.(null=No analizando).
     *
     * Cuando un modelo de solo texto necesita analizar imágenes con ayudas visuales,UI se puede mostrar en consecuencia"Analizando imágenes 2/4…".
     * con [isWaitingFirstToken] cooperar:ThinkingBubble muestra texto de progreso visual durante el análisis visual.
     */
    val visionProgress: io.zer0.muse.vision.VisionProgress? = null,
    /**
     * v1.138: Una colección de ID de mensajes de usuario con ayuda visual.
     *
     * Cuando el modelo principal no admite visión, después de analizar la imagen a través de VisionBridge,Agregue ID de mensajes de usuario a esta colección.
     * UI La capa se muestra debajo de la imagen en consecuencia."visión de asistencia · Analizado"etiqueta,Informe a los usuarios que la imagen ha sido mejorada visualmente.
     */
    val visionAssistedMessageIds: Set<String> = emptySet(),
    /**
     * v1.0.4 (P0): OCR Identificar señales de progreso.
     *
     * Cuando el usuario selecciona una imagen del álbum de fotos y reconoce el texto en modo OCR,período de identificación(1-3s)ajustes true,
     * ThinkingBubble mostrar"Reconocer texto de imagen…".
     */
    val isOcrProcessing: Boolean = false,
    /**
     * v1.0.4 (P0): Texto de progreso cuando se reanudan las llamadas a herramientas(Se utiliza para reanudar la ruta de transmisión después del punto de interrupción.).
     *
     * Cuando un usuario ingresa a una sesión con una llamada de herramienta pendiente y hace clic en Reanudar,ThinkingBubble Mostrar este texto.
     * La ruta de llamada de la herramienta de transmisión muestra el progreso a través de TaskCard,Este campo no es obligatorio.
     */
    val toolProgressMessage: String? = null,
    /**
     * Reanudar la carga desde el punto de interrupción:El número de llamadas de herramientas pendientes para la sesión actual.
     *
     * La visualización superior de ChatScreen se reanuda cuando es mayor que 0 Banner(Reanudar ejecución/descartar).
     * por [ChatViewModel.switchSession] / [ChatViewModel.resumePendingToolCalls] /
     * [ChatViewModel.discardPendingToolCalls] Mantenimiento.
     */
    val pendingToolCallCount: Int = 0,
)

data class ChatToolsState(
    val isCompressing: Boolean = false,
    val compressionPhase: ManualCompressionPhase = ManualCompressionPhase.IDLE,
    val taskCards: Map<String, io.zer0.muse.ui.taskcard.TaskCardData> = emptyMap(),
    val toolCallHistory: List<ToolCallRecord> = emptyList(),
    val pendingToolApprovals: List<PendingToolApproval> = emptyList(),
)

/** v1.205 B2: entrada de cola para ser enviada(Instantánea de texto e imagen al unirse al equipo.,Restaurar al enviar solo). */
data class PendingMessage(
    val text: String,
    val images: List<String> = emptyList(),
    /**
     * El ID de sesión al unirse a la cola.
     *
     * El canal de interrupción depende de él para determinar "a qué conversación pertenece este mensaje en cola".:Recuperar un mensaje perteneciente a otra conversación,
     * Simplemente inserte palabras que el usuario no dijo en esa conversación.,Y se convertirá en la base para el razonamiento posterior del modelo.
     * null Indica que no se puede obtener la sesión al unirse a la cola.(datos antiguos/Sin sesión),Trátelo de la forma más conservadora. —— No participes en interjecciones.
     */
    val sessionId: String? = null,
)

/**
 * Seleccione elementos de la cola para enviar que se puedan inyectar en la siguiente ronda como inserciones.
 *
 * Sólo reconocer entradas para "esta sesión + texto sin formato":
 *  - [PendingMessage.sessionId] Inconsistente con esta sesión → Pertenece a otra conversación,Quitar es la contaminación entre sesiones.;
 *  - [PendingMessage.sessionId] para null(datos antiguos/Sin sesión al unirse a la cola) → No se puede confirmar la propiedad,Permanezca en la cola y tome la ruta de envío original;
 *  - Con fotos → Enlaces de colocación y montaje de medios a través del canal de envío,La segunda implementación aquí perderá los archivos adjuntos.
 *
 * Preferiría perderme:La consecuencia de la omisión es que el mensaje se envía más tarde.,La consecuencia de una selección incorrecta es que en la conversación aparecen palabras que el usuario no dijo.
 */
internal fun selectSteeringMessages(queue: List<PendingMessage>, sessionId: String): List<PendingMessage> =
    queue.filter { it.sessionId == sessionId && it.images.isEmpty() && it.text.isNotBlank() }

data class ChatInputState(
    val input: String = "",
    /** Phase 8.6: Lista Base64 de imágenes locales a enviar(Ninguno dato: prefijo). */
    val pendingImages: List<String> = emptyList(),
    /** v1.136 T10: Lista de documentos a enviar(Analizado como texto sin formato,Combinar con el contenido del mensaje al enviar). */
    val pendingDocuments: List<io.zer0.muse.ui.chat.PendingDocument> = emptyList(),
    /** v1.91: ASR máquina de estados(modo de transmisión). */
    val asrState: ASRState = ASRState(),
    /** Cotización Responder:El mensaje de destino al que se está respondiendo actualmente(Solo capa de interfaz de usuario,Borrar después de enviar). */
    val replyingTo: UIMessage? = null,
    /** v1.57: Texto de cotización personalizado para respuestas a cotizaciones(Los usuarios pueden editar y recortar la tarjeta de referencia.,null Usado cuando replyingTo.content). */
    val replyQuoteOverride: String? = null,
    /**
     * v1.0.47 P5: Entrar en la historia(Texto de los mensajes enviados en esta conversación,Se utiliza para la devolución de llamada de flecha en el cuadro de entrada.).
     *
     * Últimos N artículos(Predeterminado 50),La flecha hacia arriba recorre en orden cronológico inverso.,La flecha hacia abajo avanza.
     * solo memoria,No persistente(Vacío después de terminar la sesión).
     */
    val inputHistory: List<String> = emptyList(),
    /** v1.0.47 P5: Ingresar índice histórico de navegación(null=No en la navegación del historial.,0=último). */
    val inputHistoryIndex: Int? = null,
    /** Función2: Si la entrada actual es un borrador restaurado desde DataStore. */
    val hasDraft: Boolean = false,
    /** v1.205 B2: Cola de mensajes a enviar(En cola durante la compilación,Vista previa artículo por artículo/Editar/Eliminar/Enviar por separado). */
    val sendQueue: List<PendingMessage> = emptyList(),
)

data class ChatSessionState(
    val currentSessionId: String? = null,
    val sessions: List<SessionEntity> = emptyList(),
    /** v1.72: Primera bandera de carga de la lista de sesiones(Evite el estado flash,DB Mostrado antes de la primera emisión loading) */
    val isSessionsLoading: Boolean = true,
    /** v1.0.62: Información de error de carga de la lista de sesiones(null=normales). */
    val sessionsError: String? = null,
    /** v0.45: Lista de conversaciones archivadas(Uso de Archive FilterCard). */
    val archivedSessions: List<SessionEntity> = emptyList(),
    /** ST-01: Archivar lista de sesiones cargando información de error(null=normales). */
    val archivedSessionsError: String? = null,
    /** Phase 9.1 (M13): Todas las carpetas(Drawer Sesiones de renderizado grupal por ID de carpeta). */
    val folders: List<io.zer0.muse.data.session.FolderEntity> = emptyList(),
)

data class ChatAgentState(
    /** v1.28: Ya sea en modo pestaña Agente(Decida si utilizará agentSessionId o currentSessionId). */
    val isAgentMode: Boolean = false,
    /** v1.28: Agent Tab sesión privada id(tarea independiente currentSessionId). */
    val agentSessionId: String? = null,
    val assistants: List<AssistantEntity> = emptyList(),
    /** v1.72: Lista de asistentes primera bandera de carga(Evite el estado flash) */
    val isAssistantsLoading: Boolean = true,
    val currentAssistant: AssistantEntity? = null,
    /** v1.201: Nodo raíz de enlace delegado(Una lista vacía significa que no hay delegación). */
    val delegationChain: List<io.zer0.muse.tools.DelegationChainTracker.ChainNode> = emptyList(),
    /** v1.201: Solicitudes de pausa de delegación actualmente activas(null Indica que aún no hay confirmación). */
    val activePauseRequest: io.zer0.muse.tools.DelegationPauseManager.PauseRequest? = null,
    /** v1.202: El hilo del subagente en segundo plano activo en la sesión actual(SubagentTaskListCard Para renderizar,Una lista vacía significa ninguna). */
    val activeSubagentThreads: List<io.zer0.muse.data.subagent.SubagentThreadStore.ThreadEntry> = emptyList(),
    /** v1.202: Sesión actual pendiente(PENDING)tareas de subagente en segundo plano(SubagentTaskListCard Para renderizar). */
    val pendingSubagentTasks: List<io.zer0.muse.tools.DeferredResultStore.DeferredTask> = emptyList(),
    // ── P6: Agent Mode mejorar ──────────────────────────────────────────
    /** v1.0.47 P6-2: Si el modelo actual es un modelo de llamada de herramienta débil(Agent Mode A continuación se muestra un mensaje de degradación). */
    val isWeakToolModel: Boolean = false,
    /** v1.0.47 P6-2: Copia del mensaje de degradación del modelo de herramienta débil(null = modelo de herramienta no débil,no mostrar). */
    val weakToolHint: String? = null,
    /** v1.0.47 P6-3: bloqueo de sesión(Agent Mode ON bloqueo de tiempo,Deshabilitar el asistente de conmutación/Borrar mensajes). */
    val isSessionLocked: Boolean = false,
    /** v1.0.47 P6-4: Agent Mode Copia de la tarjeta de propinas(null = No mostrar tarjeta). */
    val agentModeHint: String? = null,
)

/**
 * Estado de la interfaz de usuario de la página de chat.
 *
 * ⚠️ [asrConfig] Puede haber sido anulado por la configuración del proveedor:Cuando está activado "Seguir la configuración del proveedor",baseUrl / apiKey / model
 * de [io.zer0.muse.data.SpeechProviderResolver] El resultado del análisis de(Vea el bloque de colección de esta clase para suscribirse para combinar).
 * **Está prohibido pasarlo directamente a `saveAsrConfig` Realizar el pedido**;Se debe tomar la colocación `settings.asrConfigFlow` el valor original.
 */
@Suppress("LongParameterList")
class ChatUiState(
    val streamState: ChatStreamState = ChatStreamState(),
    val inputState: ChatInputState = ChatInputState(),
    val sessionState: ChatSessionState = ChatSessionState(),
    val agentState: ChatAgentState = ChatAgentState(),
    val toolsState: ChatToolsState = ChatToolsState(),
    /** B7-01: Modo de selección múltiple de mensajes. */
    val selectionMode: Boolean = false,
    /** B7-01: Una colección de identificadores de mensajes seleccionados. */
    val selectedMessageIds: Set<String> = emptySet(),
    /** v1.137 B2: Bandera de cambio de sesión — true Cuando la interfaz de usuario mantiene el mensaje del marco anterior(No mostrar lista vacía),Elimina el parpadeo de conmutación. */
    val isSwitchingSession: Boolean = false,
    val errors: List<ChatError> = emptyList(),
    val isConfigured: Boolean = false,
    /** Si se está retirando la lista de modelos ascendentes(Se utiliza el panel de conmutación del modelo inferior.). */
    val isFetchingModels: Boolean = false,
    /** Extraiga información de error del modelo ascendente. */
    val fetchModelsError: String? = null,
    /**
     * v1.93+: Si esta switchSession llega a la memoria caché LRU(para depurar).
     *
     * - true: golpear [SessionMemoryCache],Consulta de base de datos omitida,Reutilizar directamente instantáneas de mensajes de memoria
     * - false: señorita(O el fondo está generando la necesidad de ir DB),Carga de paginación desde DB
     *
     * Solo para seguimiento y depuración del rendimiento,No afecta la lógica empresarial.
     */
    val memoryCacheHit: Boolean = false,
    val isDrawerOpen: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<SearchResult> = emptyList(),
    val isSearching: Boolean = false,
    /**
     * v2.x: Página de búsqueda Pestaña índice actualmente seleccionado(0=sesión, 1=Contenido del mensaje).
     *
     * Tab=0 Usa el original"Título de la sesión/Vista previa de coincidencia + configuración de coincidencia de elementos"lógica(mostrar [searchResults] + matchedSessions);
     * Tab=1 mostrar [messageResults](Búsqueda de contenido de mensajes,FTS4 snippet + Haga clic para saltar a transferir messageId).
     * U-19: Ingrese el contenido del mensaje de forma predeterminada Tab(Sesión de búsqueda de texto completo+noticias),Las pestañas de sesión aún se pueden cambiar manualmente.
     */
    val searchTab: Int = 1,
    /** v2.x: Resultados de búsqueda de contenido de mensajes(solo en searchTab=1 visualización de la hora,Generado por FTS4 + fragmento). */
    val messageResults: List<SearchResult> = emptyList(),
    /** v2.x: Si se está realizando una búsqueda del contenido del mensaje(independiente de [isSearching],Evite que dos pestañas interfieran entre sí). */
    val isSearchingMessages: Boolean = false,
    /** v1.0.62: Búsqueda de información fallida(null=normales),sesión/Contenido del mensaje Compartir pestañas. */
    val searchError: String? = null,
    /**
     * v2.x: Al hacer clic en el mensaje para saltar de los resultados de búsqueda,ID del mensaje de destino.
     *
     * ChatScreen Después de ingresar a la sesión, se leerá este campo. Desplácese hasta el mensaje y active el resaltado.,Al finalizar por
     * [ChatViewModel.consumeTargetMessage] Borrar(Evite los desencadenantes repetidos).
     */
    val targetMessageId: String? = null,
    /**
     * v2.x: Buscar palabras clave resaltadas(Llevar al saltar de los resultados de búsqueda,Se utiliza para resaltar texto dentro de MessageBubble).
     *
     * solo si [highlightedMessageId] Válido cuando no está vacío;Se borra una vez finalizado el resaltado.
     */
    val searchHighlightQuery: String? = null,
    /**
     * v2.x: El mensaje actualmente resaltado brevemente id(Establecer después de saltar de los resultados de búsqueda al posicionamiento de desplazamiento,Borrar después de unos segundos.).
     *
     * ChatScreen De acuerdo con esto, la MessageBubble correspondiente highlightText,darse cuenta"Ingrese a la conversación y resalte el mensaje de destino.".
     */
    val highlightedMessageId: String? = null,
    val isDrawMode: Boolean = false,
    /** v0.34: Parámetros de dibujo actuales(Capacidad de anular temporalmente los valores predeterminados en la configuración). */
    val imageGenParams: io.zer0.ai.image.ImageGenParams = io.zer0.ai.image.ImageGenParams(),
    val isGeneratingImage: Boolean = false,
    /**
     * v1.0.4 (P1): Bandera de generación de vídeo.
     *
     * LLM Establecer al llamar a la herramienta generate_video true,Completo/falló/Establecer en falso al cancelar.
     * ChatScreen Mostrar tarjeta de marcador de posición VideoGenerationPlaceholder,Simétrico a ImageGenerationPlaceholder.
     * Nota:execGenerateVideo pasará updateAssistant(content="Generando vídeo...") Burbuja de mensaje en el Asistente
     * Progreso de la sincronización interna,Este campo es una tarjeta de marcador de posición complementaria para comentarios.,No es un reemplazo.
     */
    val isGeneratingVideo: Boolean = false,
    val isTranslating: Boolean = false,
    val translatingMessageId: Uuid? = null,
    val favoriteMessages: List<UIMessage> = emptyList(),
    /** v1.77: Lista de colección primera bandera de carga(Evite el estado flash) */
    val isFavoritesLoading: Boolean = true,
    /** ST-01: Mensaje de error al cargar la lista de colecciones(null=normales). */
    val favoritesError: String? = null,
    /**
     * v1.104 U7: Lista de etiquetas de grupos de colecciones con nombre(Eliminar duplicados en orden ascendente,Sin NULL Desagrupado).
     *
     * Agregado por DAO observeAllFavoriteTags,UI Representa la fila superior de FilterChip en consecuencia.
     */
    val favoriteTags: List<String> = emptyList(),
    /**
     * v1.104 U7: La condición de filtro de agrupación seleccionada actualmente.
     *
     *  - null = Mostrar todas las colecciones
     *  - cadena no vacía = Mostrar solo colecciones bajo esta etiqueta
     *  - valor especial [FAVORITE_TAG_UNGROUPED] = Mostrar solo desagrupados(favoriteTag=null)colección
     *
     * FavoritesScreen Filtrar mensajes favoritos localmente,No prefiltrado en ViewModel,
     * Asegúrese de que los mensajes favoritos estén siempre llenos(Conveniente"Todos"chip Mostrar total).
     */
    val favoriteTagFilter: String? = null,
    /** Phase 8.4: Si se debe habilitar la búsqueda en Internet(InputBar encender,Cambio de tiempo de ejecución). */
    val webSearchEnabled: Boolean = false,
    /**
     * v0.45: Estimación de ocupación de tokens en el contexto actual(Contiene un mensaje del sistema + todo el texto del mensaje).
     *
     * por [ChatViewModel.updateContextTokenCount] actualizaciones en tiempo real,UI Representa el anillo de ocupación en consecuencia.
     * Actualizado cada 50 caracteres durante la transmisión(evitar con demasiada frecuencia),Actualice inmediatamente cuando no esté transmitiendo.
     */
    val contextTokenCount: Int = 0,
    /**
     * v0.45: El tamaño de la ventana de contexto del modelo actual.(token numero).
     *
     * 0 Indica desconocido(Modelo no declarado contextWindow),UI Los anillos de ocupación no se muestran.
     * Al cambiar de sesión/seleccionar un modelo [ChatViewModel.refreshContextMaxTokens] Refrescar.
     */
    val contextMaxTokens: Int = 0,
    /**
     * v2.x: ID de mensaje de límite del punto de control de contexto (null = Aún no hay puntos de control para esta sesión).
     *
     * En consecuencia, la interfaz traza una línea en el flujo de la conversación."El contexto está comprimido."Línea de separación y ocultar este mensaje y los mensajes más antiguos de forma predeterminada.
     * esto es**Solo interfaz**Ancla de presentación: La historia del modelo emitido por [io.zer0.muse.transformer.ContextCheckpointMerge]
     * El filtrado se realiza durante la etapa de ensamblaje para que los dos no compartan los mismos datos para evitar la contaminación mutua.
     */
    val contextCheckpointBoundaryId: String? = null,
    /** v2.x: El número acumulado de mensajes incorporados al resumen (0 = desconocido/Para los puntos de control antiguos, la línea divisoria no muestra el número de líneas). */
    val contextCheckpointTotalCovered: Int = 0,
    /**
     * v1.0.47: Token Cambio de estimación(Desactivado de forma predeterminada).
     *
     * Después de que el usuario abre explícitamente la página de configuración,El cuadro de entrada muestra la estimación del token de entrada actual.,
     * AI Se muestra en la parte inferior del mensaje. completionTokens,La barra superior muestra la ocupación del contexto.
     * No se realiza ningún cálculo de token al cerrar,Evite la sobrecarga de rendimiento de codificación BPE.
     */
    val tokenEstimateEnabled: Boolean = false,
    /** v1.0.47 P5-2: Cambio de pegado de texto largo a archivo(Habilitado por defecto). */
    val pasteAsFileEnabled: Boolean = true,
    /** v1.0.47 P5-2: Umbral para pegar y convertir texto largo en archivos(Número de caracteres). */
    val pasteAsFileThreshold: Int = 2000,
    /** v1.0.47 P5-3: Token Si se expande el menú de conteo. solo tokenEstimateEnabled=true se puede activar cuando. */
    val tokenCountVisible: Boolean = false,
    /** v1.0.47 P5-3: Token contar instantánea(Calculado una vez cuando se abre el menú.,Evite volver a calcular cada pulsación de tecla BPE). */
    val tokenSnapshot: TokenCountSnapshot? = null,
    // v0.45: ¿Se está ejecutando?"Actualizar memoria y comprimir".
    // Botón de compresión manual, haga clic en publicar true,Establecer falso después de completar.UI Esto muestra el círculo + botón de desactivar.
    /** v0.39: interruptor de pensamiento profundo(Habilite temporalmente la inferencia ALTA mientras chatea,Anular el valor predeterminado del asistente reasoningLevel). */
    val deepThinkingEnabled: Boolean = false,
    /**
     * v1.0.47 P5-6: nivel de pensamiento profundo(solo en [deepThinkingEnabled]=true eficaz cuando).
     *
     * Predeterminado HIGH(Compatible con versiones anteriores con el comportamiento v0.39). El usuario puede hacer clic en la cápsula de nivel de campo de entrada en
     * LOW → MEDIUM → HIGH → XHIGH ciclo entre.
     * Los modelos que no soportan la inferencia son [ChatStreamCoordinator] automáticamente degradado a AUTO,No se informará ningún error.
     */
    val deepThinkingLevel: ReasoningLevel = ReasoningLevel.HIGH,
    /** Phase 8.4: Web Configuración de búsqueda(Se utiliza para editar la página de configuración). */
    val webSearchConfig: WebSearchConfig = WebSearchConfig(),
    /** Phase 8.5: Lista de mensajes de acceso directo vinculados a la sesión actual(InputBar Se utiliza la línea de chip superior.). */
    val quickMessages: List<QuickMessageEntity> = emptyList(),
    /** Phase 8.5: Modo actualmente activo(usado para PromptInjection,default Indica que no hay inyección). */
    val currentMode: String = "default",
    /** Phase 8.5: Todas las entradas del libro de historia(Para la página de gestión). */
    val lorebooks: List<LorebookEntity> = emptyList(),
    /** ST-01: Mensaje de error al cargar la lista mundial de libros(null=normales). */
    val lorebooksError: String? = null,
    /** Phase 8.5: Todas las entradas de PromptInjection(Para la página de gestión). */
    val promptInjections: List<PromptInjectionEntity> = emptyList(),
    /** Phase 8.5: Todas las entradas de QuickMessage(Para la página de gestión,Contiene enlace global + cada asistente). */
    val allQuickMessages: List<QuickMessageEntity> = emptyList(),
    /** v1.58: Prompt Lista de plantillas(Palabras de indicación de escena preestablecidas,Ingrese a la selección de la biblioteca de plantillas desde el menú más). */
    val promptTemplates: List<io.zer0.muse.data.prompttemplate.PromptTemplate> = emptyList(),
    /** Phase 8.7: TTS ¿Estás leyendo en voz alta?(para InputBar deshabilitado/MessageBubble Resaltar). */
    val isSpeaking: Boolean = false,
    /** Phase 8.7: Mensaje leído en voz alta id(null significa ninguno). */
    val speakingMessageId: Uuid? = null,
    // Phase 8.8: Mapeo de tarjetas de tareas(assistant noticias id → TaskCardData),Visualización del plan de llamadas de herramientas.
    /** v1.55: Agent Mapeo del plan de flujo de trabajo(planId → AgentPlan),Presentación estructurada de planificación de tareas. */
    val agentPlans: Map<String, io.zer0.muse.ui.taskcard.AgentPlan> = emptyMap(),
    /** Phase 9.3 (M2): ASR Configuración(provider/apiKey/model). */
    val asrConfig: io.zer0.muse.asr.AsrConfig = io.zer0.muse.asr.AsrConfig(),
    /** Etapa 5: Todo configurado Provider(Se utiliza el panel de conmutación del modelo inferior.). */
    val providers: List<io.zer0.ai.core.ProviderConfig> = emptyList(),
    /** Fase 5: Identificación del proveedor actualmente activado. */
    val activeProviderId: String? = null,
    /** Etapa 5: modelo actualmente seleccionado id(null Representa un respaldo al primer modelo que activó el Proveedor.). */
    val selectedModelId: String? = null,
    /** v1.60-A: modelo de herramienta id(Uso de la ronda de llamadas de herramientas,null Indica que el modelo de diálogo principal se hereda.). */
    val toolModelId: String? = null,
    /** v0.31: Preferencias de comportamiento en el chat(Leer desde la configuración,UI Controle el renderizado y la interacción en consecuencia). */
    val chatPreferences: io.zer0.muse.data.ChatPreferences = io.zer0.muse.data.ChatPreferences(),
    /** v0.32: Compartir plantilla(Leer desde la configuración,exportSessionAsMarkdown Filtrar contenido según esto). */
    val shareTemplate: io.zer0.muse.data.ShareTemplateConfig = io.zer0.muse.data.ShareTemplateConfig(),
    /** v0.33: configuración de medios(Leer desde la configuración,Grabación/TTS Controle la frecuencia de muestreo en consecuencia/velocidad al hablar/tono/Idioma/Modo de salida). */
    val mediaConfig: io.zer0.muse.data.MediaConfig = io.zer0.muse.data.MediaConfig(),
    /**
     * v2.3: Resumen de rendimiento de esta respuesta completado después de que finaliza la transmisión,UI Se puede mostrar en la parte inferior de MessageBubble.
     *
     * Formato:"modelo:xxx | Consume mucho tiempo:xxms | TTFT:xxms | tarifa:xx tok/s | personaje:xx | llamada de herramienta:N | rondas:N".
     * solo debugMode=true llenar cuando,En caso contrario nulo. Restablecer cada vez que se inicia launchStream.
     */
    val debugInfo: String? = null,
    /**
     * v0.51: Recordatorio de brindis único(Escenarios como el cambio de modelo.).
     *
     * Utilice Toast en lugar de Snackbar: el canal Snackbar ha sido [errors] ocupar,Las indicaciones de cambio de modelo no deben ser incorrectas.
     * Noticias exprimidas,Así que tome el pasillo independiente de Toast.UI Utilice LaunchedEffect para observar este campo,bomba no vacía Toast
     * y ajustar inmediatamente [ChatViewModel.clearToast] Borrar(Evite repetidos bombardeos durante la reorganización.).
     */
    val toast: String? = null,
    /** v1.25: Configuración de colaboración multiagente(Lista de equipos y cambio maestro). */
    val multiAgentConfig: MultiAgentConfig = MultiAgentConfig(),
    /** v1.43: La tarjeta de producto seleccionada actualmente(Se utiliza para la ventana emergente ArtifactViewerDialog). */
    val selectedArtifact: io.zer0.muse.data.artifact.ArtifactEntity? = null,
    /** v1.45: Lista de caché de posición de desplazamiento(Cortar páginas/Reanudar después del fondo,evitar volver al principio). */
    val listFirstVisibleItemIndex: Int = 0,
    val listFirstVisibleItemScrollOffset: Int = 0,
    /**
     * v2.2.1: Agent Página(agent modo)Caché de posición de desplazamiento independiente.
     *
     * Mantenimiento normal de la sesión."seguir el último mensaje";Agent Cuando la página regrese, restablezca la posición donde dejó el usuario antes de irse.
     * agentListFirstVisibleItemIndex < 0 expresar"No registrado",Vuelva al final de la lista al ingresar.
     */
    val agentListFirstVisibleItemIndex: Int = -1,
    val agentListFirstVisibleItemScrollOffset: Int = 0,
    /** v2.2.1: agent Sesiones de propiedad de caché rodante id(Invalidación de caché cuando cambia de sesión,Volver al final de la lista). */
    val agentScrollOwnerSessionId: String? = null,
    /** v1.45: Caché de estado de expansión a nivel de mensaje(mood/reasoning El estado plegado no se pierde debido al corte de página.). */
    val messageExpandedStates: Map<String, MessageExpandedState> = emptyMap(),
    /** v1.53-A1: ¿Hay algún mensaje histórico anterior que se pueda cargar?(Desliza hacia arriba para cargar más). */
    val hasMoreHistory: Boolean = false,
    /** v1.53-A1: Está cargando más mensajes históricos.(Prevenir disparos repetidos). */
    val isLoadingMore: Boolean = false,
    /**
     * v1.53-A1: más reciente"cargar más"El número de elementos del historial insertados.
     *
     * UI Monitorear los cambios en este campo.(>0)después,Pase [listState.scrollToItem] Omitir la cantidad de elementos recién insertados,
     * Evita que la posición visual del usuario salte(El mensaje que estaba en la parte superior ahora está en la parte superior. index),Luego ajuste
     * [ChatViewModel.clearHistoryLoadCount] Vacío.
     */
    val lastHistoryLoadCount: Int = 0,
    // v1.94: Registro de llamadas de herramientas para la sesión actual(Se utiliza para la visualización de cápsulas dinámicas de InputBar).
    /** v2.3: Conmutador de enrutamiento del modelo de tarea(Cuando está habilitado, los modelos se recomiendan automáticamente según el contenido de entrada.). */
    val taskRoutingEnabled: Boolean = false,
    // Lista de convocatorias de herramientas pendientes de aprobación(ToolApprovalCard usar).
    /** P3: Modo de permiso de herramienta para la sesión actual(TRUSTED/ASK/STRICT),PREGUNTAR por defecto. */
    val sessionPermissionMode: SessionPermissionMode = SessionPermissionMode.ASK,
    /**
     * v1.0.16: Aprobar todas las llamadas a herramientas durante la apertura de esta aplicación(estado de memoria,No persistente).
     *
     * El usuario comprueba la ToolApprovalCard"Aprobar todas las herramientas durante esta apertura"trasero true,
     * Todas las llamadas a herramientas posteriores se ejecutan directamente en Auto,No más tarjetas de aprobación.
     * Reconstrucción de ChatUiState después del inicio en frío de la aplicación,Devuelve falso automáticamente.
     */
    val appRunAllowAllTools: Boolean = false,
    /**
     * F-4: /pin Texto del mensaje fijado(estado de memoria,No persistente).
     *
     * null = No fijado. El valor es el texto del último mensaje del usuario.,Representado como un banner adhesivo en la parte superior de la lista de mensajes.;
     * /pin Borrar al volver a fijar el mismo mensaje(Desanclar). Borrado por switchSession después de cambiar de sesión.
     */
    val pinnedMessageContent: String? = null,
) {
    val isStreaming: Boolean get() = streamState.isStreaming
    val isWaitingFirstToken: Boolean get() = streamState.isWaitingFirstToken
    val visionProgress: io.zer0.muse.vision.VisionProgress? get() = streamState.visionProgress
    val visionAssistedMessageIds: Set<String> get() = streamState.visionAssistedMessageIds
    val isOcrProcessing: Boolean get() = streamState.isOcrProcessing
    val toolProgressMessage: String? get() = streamState.toolProgressMessage
    val pendingToolCallCount: Int get() = streamState.pendingToolCallCount
    val isCompressing: Boolean get() = toolsState.isCompressing
    val compressionPhase: ManualCompressionPhase get() = toolsState.compressionPhase
    val taskCards: Map<String, io.zer0.muse.ui.taskcard.TaskCardData> get() = toolsState.taskCards
    val toolCallHistory: List<ToolCallRecord> get() = toolsState.toolCallHistory
    val pendingToolApprovals: List<PendingToolApproval> get() = toolsState.pendingToolApprovals
    val input: String get() = inputState.input
    val hasDraft: Boolean get() = inputState.hasDraft
    val inputHistory: List<String> get() = inputState.inputHistory
    val inputHistoryIndex: Int? get() = inputState.inputHistoryIndex
    val pendingImages: List<String> get() = inputState.pendingImages
    val pendingDocuments: List<io.zer0.muse.ui.chat.PendingDocument> get() = inputState.pendingDocuments
    val replyingTo: UIMessage? get() = inputState.replyingTo
    val replyQuoteOverride: String? get() = inputState.replyQuoteOverride
    val asrState: ASRState get() = inputState.asrState
    val sendQueue: List<PendingMessage> get() = inputState.sendQueue
    val sessions: List<SessionEntity> get() = sessionState.sessions
    val currentSessionId: String? get() = sessionState.currentSessionId
    val archivedSessions: List<SessionEntity> get() = sessionState.archivedSessions
    val folders: List<io.zer0.muse.data.session.FolderEntity> get() = sessionState.folders
    val isSessionsLoading: Boolean get() = sessionState.isSessionsLoading
    val sessionsError: String? get() = sessionState.sessionsError
    val archivedSessionsError: String? get() = sessionState.archivedSessionsError
    val isAgentMode: Boolean get() = agentState.isAgentMode
    val agentSessionId: String? get() = agentState.agentSessionId
    val assistants: List<AssistantEntity> get() = agentState.assistants
    val isAssistantsLoading: Boolean get() = agentState.isAssistantsLoading
    val currentAssistant: AssistantEntity? get() = agentState.currentAssistant
    val delegationChain: List<io.zer0.muse.tools.DelegationChainTracker.ChainNode> get() = agentState.delegationChain
    val activePauseRequest: io.zer0.muse.tools.DelegationPauseManager.PauseRequest? get() = agentState.activePauseRequest
    val activeSubagentThreads: List<io.zer0.muse.data.subagent.SubagentThreadStore.ThreadEntry> get() = agentState.activeSubagentThreads
    val pendingSubagentTasks: List<io.zer0.muse.tools.DeferredResultStore.DeferredTask> get() = agentState.pendingSubagentTasks
    val isWeakToolModel: Boolean get() = agentState.isWeakToolModel
    val weakToolHint: String? get() = agentState.weakToolHint
    val isSessionLocked: Boolean get() = agentState.isSessionLocked
    val agentModeHint: String? get() = agentState.agentModeHint

    /** v0.49: compatible con versiones anteriores — El primer mensaje de error se obtiene desde donde se lee actualmente state.error. */
    val error: String? get() = errors.firstOrNull()?.message

    fun copy(
        streamState: ChatStreamState = this.streamState,
        inputState: ChatInputState = this.inputState,
        sessionState: ChatSessionState = this.sessionState,
        agentState: ChatAgentState = this.agentState,
        toolsState: ChatToolsState = this.toolsState,
        isSwitchingSession: Boolean = this.isSwitchingSession,
        selectionMode: Boolean = this.selectionMode,
        selectedMessageIds: Set<String> = this.selectedMessageIds,
        errors: List<ChatError> = this.errors,
        isConfigured: Boolean = this.isConfigured,
        isFetchingModels: Boolean = this.isFetchingModels,
        fetchModelsError: String? = this.fetchModelsError,
        memoryCacheHit: Boolean = this.memoryCacheHit,
        isDrawerOpen: Boolean = this.isDrawerOpen,
        searchQuery: String = this.searchQuery,
        searchResults: List<SearchResult> = this.searchResults,
        isSearching: Boolean = this.isSearching,
        searchTab: Int = this.searchTab,
        messageResults: List<SearchResult> = this.messageResults,
        isSearchingMessages: Boolean = this.isSearchingMessages,
        searchError: String? = this.searchError,
        targetMessageId: String? = this.targetMessageId,
        searchHighlightQuery: String? = this.searchHighlightQuery,
        highlightedMessageId: String? = this.highlightedMessageId,
        isDrawMode: Boolean = this.isDrawMode,
        imageGenParams: io.zer0.ai.image.ImageGenParams = this.imageGenParams,
        isGeneratingImage: Boolean = this.isGeneratingImage,
        isGeneratingVideo: Boolean = this.isGeneratingVideo,
        isTranslating: Boolean = this.isTranslating,
        translatingMessageId: Uuid? = this.translatingMessageId,
        favoriteMessages: List<UIMessage> = this.favoriteMessages,
        isFavoritesLoading: Boolean = this.isFavoritesLoading,
        favoritesError: String? = this.favoritesError,
        favoriteTags: List<String> = this.favoriteTags,
        favoriteTagFilter: String? = this.favoriteTagFilter,
        webSearchEnabled: Boolean = this.webSearchEnabled,
        contextTokenCount: Int = this.contextTokenCount,
        contextMaxTokens: Int = this.contextMaxTokens,
        contextCheckpointBoundaryId: String? = this.contextCheckpointBoundaryId,
        contextCheckpointTotalCovered: Int = this.contextCheckpointTotalCovered,
        tokenEstimateEnabled: Boolean = this.tokenEstimateEnabled,
        pasteAsFileEnabled: Boolean = this.pasteAsFileEnabled,
        pasteAsFileThreshold: Int = this.pasteAsFileThreshold,
        tokenCountVisible: Boolean = this.tokenCountVisible,
        tokenSnapshot: TokenCountSnapshot? = this.tokenSnapshot,
        deepThinkingEnabled: Boolean = this.deepThinkingEnabled,
        deepThinkingLevel: ReasoningLevel = this.deepThinkingLevel,
        webSearchConfig: WebSearchConfig = this.webSearchConfig,
        quickMessages: List<QuickMessageEntity> = this.quickMessages,
        currentMode: String = this.currentMode,
        lorebooks: List<LorebookEntity> = this.lorebooks,
        lorebooksError: String? = this.lorebooksError,
        promptInjections: List<PromptInjectionEntity> = this.promptInjections,
        allQuickMessages: List<QuickMessageEntity> = this.allQuickMessages,
        promptTemplates: List<io.zer0.muse.data.prompttemplate.PromptTemplate> = this.promptTemplates,
        isSpeaking: Boolean = this.isSpeaking,
        speakingMessageId: Uuid? = this.speakingMessageId,
        taskCards: Map<String, io.zer0.muse.ui.taskcard.TaskCardData> = this.taskCards,
        agentPlans: Map<String, io.zer0.muse.ui.taskcard.AgentPlan> = this.agentPlans,
        asrConfig: io.zer0.muse.asr.AsrConfig = this.asrConfig,
        providers: List<io.zer0.ai.core.ProviderConfig> = this.providers,
        activeProviderId: String? = this.activeProviderId,
        selectedModelId: String? = this.selectedModelId,
        toolModelId: String? = this.toolModelId,
        chatPreferences: io.zer0.muse.data.ChatPreferences = this.chatPreferences,
        shareTemplate: io.zer0.muse.data.ShareTemplateConfig = this.shareTemplate,
        mediaConfig: io.zer0.muse.data.MediaConfig = this.mediaConfig,
        debugInfo: String? = this.debugInfo,
        toast: String? = this.toast,
        multiAgentConfig: MultiAgentConfig = this.multiAgentConfig,
        selectedArtifact: io.zer0.muse.data.artifact.ArtifactEntity? = this.selectedArtifact,
        listFirstVisibleItemIndex: Int = this.listFirstVisibleItemIndex,
        listFirstVisibleItemScrollOffset: Int = this.listFirstVisibleItemScrollOffset,
        agentListFirstVisibleItemIndex: Int = this.agentListFirstVisibleItemIndex,
        agentListFirstVisibleItemScrollOffset: Int = this.agentListFirstVisibleItemScrollOffset,
        agentScrollOwnerSessionId: String? = this.agentScrollOwnerSessionId,
        messageExpandedStates: Map<String, MessageExpandedState> = this.messageExpandedStates,
        hasMoreHistory: Boolean = this.hasMoreHistory,
        isLoadingMore: Boolean = this.isLoadingMore,
        lastHistoryLoadCount: Int = this.lastHistoryLoadCount,
        toolCallHistory: List<ToolCallRecord> = this.toolCallHistory,
        taskRoutingEnabled: Boolean = this.taskRoutingEnabled,
        pendingToolApprovals: List<PendingToolApproval> = this.pendingToolApprovals,
        sessionPermissionMode: SessionPermissionMode = this.sessionPermissionMode,
        appRunAllowAllTools: Boolean = this.appRunAllowAllTools,
        pinnedMessageContent: String? = this.pinnedMessageContent,
        isStreaming: Boolean = this.isStreaming,
        isWaitingFirstToken: Boolean = this.isWaitingFirstToken,
        visionProgress: io.zer0.muse.vision.VisionProgress? = this.visionProgress,
        visionAssistedMessageIds: Set<String> = this.visionAssistedMessageIds,
        isOcrProcessing: Boolean = this.isOcrProcessing,
        toolProgressMessage: String? = this.toolProgressMessage,
        pendingToolCallCount: Int = this.pendingToolCallCount,
        input: String = this.input,
        hasDraft: Boolean = this.hasDraft,
        inputHistory: List<String> = this.inputHistory,
        inputHistoryIndex: Int? = this.inputHistoryIndex,
        pendingImages: List<String> = this.pendingImages,
        pendingDocuments: List<io.zer0.muse.ui.chat.PendingDocument> = this.pendingDocuments,
        replyingTo: UIMessage? = this.replyingTo,
        replyQuoteOverride: String? = this.replyQuoteOverride,
        sendQueue: List<PendingMessage> = this.sendQueue,
        asrState: ASRState = this.asrState,
        sessions: List<SessionEntity> = this.sessions,
        currentSessionId: String? = this.currentSessionId,
        archivedSessions: List<SessionEntity> = this.archivedSessions,
        archivedSessionsError: String? = this.archivedSessionsError,
        folders: List<io.zer0.muse.data.session.FolderEntity> = this.folders,
        isSessionsLoading: Boolean = this.isSessionsLoading,
        sessionsError: String? = this.sessionsError,
        isAgentMode: Boolean = this.isAgentMode,
        agentSessionId: String? = this.agentSessionId,
        assistants: List<AssistantEntity> = this.assistants,
        isAssistantsLoading: Boolean = this.isAssistantsLoading,
        currentAssistant: AssistantEntity? = this.currentAssistant,
        delegationChain: List<io.zer0.muse.tools.DelegationChainTracker.ChainNode> = this.delegationChain,
        activePauseRequest: io.zer0.muse.tools.DelegationPauseManager.PauseRequest? = this.activePauseRequest,
        activeSubagentThreads: List<io.zer0.muse.data.subagent.SubagentThreadStore.ThreadEntry> = this.activeSubagentThreads,
        pendingSubagentTasks: List<io.zer0.muse.tools.DeferredResultStore.DeferredTask> = this.pendingSubagentTasks,
        isWeakToolModel: Boolean = this.isWeakToolModel,
        weakToolHint: String? = this.weakToolHint,
        isSessionLocked: Boolean = this.isSessionLocked,
        agentModeHint: String? = this.agentModeHint,
    ): ChatUiState = ChatUiState(
        streamState =
        streamState.copy(
            isStreaming = isStreaming,
            isWaitingFirstToken = isWaitingFirstToken,
            visionProgress = visionProgress,
            visionAssistedMessageIds = visionAssistedMessageIds,
            isOcrProcessing = isOcrProcessing,
            toolProgressMessage = toolProgressMessage,
            pendingToolCallCount = pendingToolCallCount,
        ),
        inputState =
        inputState.copy(
            input = input,
            hasDraft = hasDraft,
            inputHistory = inputHistory,
            inputHistoryIndex = inputHistoryIndex,
            pendingImages = pendingImages,
            pendingDocuments = pendingDocuments,
            replyingTo = replyingTo,
            replyQuoteOverride = replyQuoteOverride,
            asrState = asrState,
            sendQueue = sendQueue,
        ),
        sessionState =
        sessionState.copy(
            sessions = sessions,
            currentSessionId = currentSessionId,
            archivedSessions = archivedSessions,
            archivedSessionsError = archivedSessionsError,
            folders = folders,
            isSessionsLoading = isSessionsLoading,
            sessionsError = sessionsError,
        ),
        agentState =
        agentState.copy(
            isAgentMode = isAgentMode,
            agentSessionId = agentSessionId,
            assistants = assistants,
            isAssistantsLoading = isAssistantsLoading,
            currentAssistant = currentAssistant,
            delegationChain = delegationChain,
            activePauseRequest = activePauseRequest,
            activeSubagentThreads = activeSubagentThreads,
            pendingSubagentTasks = pendingSubagentTasks,
            isWeakToolModel = isWeakToolModel,
            weakToolHint = weakToolHint,
            isSessionLocked = isSessionLocked,
            agentModeHint = agentModeHint,
        ),
        isSwitchingSession = isSwitchingSession,
        selectionMode = selectionMode,
        selectedMessageIds = selectedMessageIds,
        errors = errors,
        isConfigured = isConfigured,
        isFetchingModels = isFetchingModels,
        fetchModelsError = fetchModelsError,
        memoryCacheHit = memoryCacheHit,
        isDrawerOpen = isDrawerOpen,
        searchQuery = searchQuery,
        searchResults = searchResults,
        isSearching = isSearching,
        searchTab = searchTab,
        messageResults = messageResults,
        isSearchingMessages = isSearchingMessages,
        searchError = searchError,
        targetMessageId = targetMessageId,
        searchHighlightQuery = searchHighlightQuery,
        highlightedMessageId = highlightedMessageId,
        isDrawMode = isDrawMode,
        imageGenParams = imageGenParams,
        isGeneratingImage = isGeneratingImage,
        isGeneratingVideo = isGeneratingVideo,
        isTranslating = isTranslating,
        translatingMessageId = translatingMessageId,
        favoriteMessages = favoriteMessages,
        isFavoritesLoading = isFavoritesLoading,
        favoritesError = favoritesError,
        favoriteTags = favoriteTags,
        favoriteTagFilter = favoriteTagFilter,
        webSearchEnabled = webSearchEnabled,
        contextTokenCount = contextTokenCount,
        contextMaxTokens = contextMaxTokens,
        tokenEstimateEnabled = tokenEstimateEnabled,
        pasteAsFileEnabled = pasteAsFileEnabled,
        pasteAsFileThreshold = pasteAsFileThreshold,
        tokenCountVisible = tokenCountVisible,
        tokenSnapshot = tokenSnapshot,
        toolsState =
        toolsState.copy(
            taskCards = taskCards,
            toolCallHistory = toolCallHistory,
            pendingToolApprovals = pendingToolApprovals,
        ),
        deepThinkingEnabled = deepThinkingEnabled,
        deepThinkingLevel = deepThinkingLevel,
        webSearchConfig = webSearchConfig,
        quickMessages = quickMessages,
        currentMode = currentMode,
        lorebooks = lorebooks,
        lorebooksError = lorebooksError,
        promptInjections = promptInjections,
        allQuickMessages = allQuickMessages,
        promptTemplates = promptTemplates,
        isSpeaking = isSpeaking,
        speakingMessageId = speakingMessageId,
        agentPlans = agentPlans,
        asrConfig = asrConfig,
        providers = providers,
        activeProviderId = activeProviderId,
        selectedModelId = selectedModelId,
        toolModelId = toolModelId,
        chatPreferences = chatPreferences,
        shareTemplate = shareTemplate,
        mediaConfig = mediaConfig,
        debugInfo = debugInfo,
        toast = toast,
        multiAgentConfig = multiAgentConfig,
        selectedArtifact = selectedArtifact,
        listFirstVisibleItemIndex = listFirstVisibleItemIndex,
        listFirstVisibleItemScrollOffset = listFirstVisibleItemScrollOffset,
        agentListFirstVisibleItemIndex = agentListFirstVisibleItemIndex,
        agentListFirstVisibleItemScrollOffset = agentListFirstVisibleItemScrollOffset,
        agentScrollOwnerSessionId = agentScrollOwnerSessionId,
        messageExpandedStates = messageExpandedStates,
        hasMoreHistory = hasMoreHistory,
        isLoadingMore = isLoadingMore,
        lastHistoryLoadCount = lastHistoryLoadCount,
        taskRoutingEnabled = taskRoutingEnabled,
        sessionPermissionMode = sessionPermissionMode,
        appRunAllowAllTools = appRunAllowAllTools,
        pinnedMessageContent = pinnedMessageContent,
    )
}
data class ToolCallRecord(
    val toolName: String,
    val arguments: String,
    val result: String,
    val isSuccess: Boolean,
    val timestamp: Long,
    /** Stable execution identity; legacy records default to empty. */
    val traceId: String = "",
    val sessionId: String = "",
    val turnId: String = "",
    val generationId: String = "",
    val roundIndex: Int = -1,
    val toolCallId: String = "",
    val executionId: String = "",
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val status: String = if (isSuccess) "COMPLETED" else "FAILED",
    val terminationReason: String? = null,
)

/** Llamadas de herramientas pendientes de aprobación(ToolApprovalCard usar). */

data class PendingToolApproval(
    val toolCallId: String,
    val toolName: String,
    val argumentsPreview: String,
    /** Parámetros completos (analizados) para que la tarjeta de aprobación se expanda y se vea sin truncarla. */
    val argumentsFull: Map<String, Any?> = emptyMap(),
    val alwaysAllow: Boolean = false,
    /** v1.0.16: Aprobar todas las herramientas durante esta apertura(estado de memoria,No persistente) */
    val appRunAllowAll: Boolean = false,
    /**
     * v1.x: La imagen de referencia seleccionada por el usuario en la tarjeta de aprobación.(data URI Formato,Como por ejemplo "data:image/jpeg;base64,...").
     *
     * Solo válido para herramientas que admiten imágenes de referencia como generate_image;Pasar después de la aprobación
     * [ToolApprovalState.Approved.argOverrides] Parámetros de ejecución de la herramienta de inyección(reference_image clave).
     * LLM No se puede acceder al álbum de fotos local del usuario,Por lo tanto, este campo es la principal fuente de imágenes de referencia para realizar dibujos.
     */
    val referenceImageOverride: String? = null,
)

/**
 * v1.45: Caché de estado de expansión de la interfaz de usuario para un solo mensaje.
 *
 * Representado por nulo"No ha sido cambiado manualmente por el usuario,Utilice valores predeterminados de chatPreferences".
 */
data class MessageExpandedState(
    val isMoodExpanded: Boolean? = null,
    val isReasoningExpanded: Boolean? = null,
    val isReflectionExpanded: Boolean? = null,
    /** v2.4.6: Estado expandido del bloque de traducción. */
    val isTranslationExpanded: Boolean? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
/**
 * pagina de chat ViewModel — El centro comercial principal de toda la aplicación.
 *
 * Responsabilidades:
 *  - sesión/Carga, envío, recepción de streaming, detención, reintento, edición y eliminación de mensajes
 *  - multimodal:texto + imagen(El usuario toma fotos/Álbum de fotos + imagen generada por IA)+ Accesorios(Análisis de documentos OCR)
 *  - llamada de herramienta:Skill sistema(9 implementación incorporada + importación de usuarios .skill.json)+ MCP herramientas dinámicas
 *  - tubería de contexto:6 Procesamiento secuencial del transformador de paso(Memory → Time → Lorebook → PromptInjection → Template → ThinkTag/MoodTag → Compresión)
 *  - Montaje de indicaciones del sistema:SystemPromptAssembler Combine la configuración del Asistente + retrato de usuario + memoria en la final system prompt
 *  - Web Buscar:Cuando se activa el interruptor del pensamiento profundo,LLM Decide de forma independiente cuándo llamar web_search/web_fetch
 *  - Piensa profundamente:interruptor de conmutación ReasoningLevel.HIGH(8000 tokens presupuesto)
 *  - TTS:Una vez completada la generación de la transmisión, se puede activar el informe TTS del sistema.
 *  - Notificación:Inicio de transmisión/Progreso/Completo/error/llamar a la parada MuseNotificationManager
 *
 * Gestión de estado:soltero [state] StateFlow,UI Suscríbase a través de CollectAsStateWithLifecycle.
 * corrutina:Todas las redes/DB Para operación viewModelScope,Las llamadas a herramientas tienen un tiempo de espera de 2 minutos([TOOL_TIMEOUT_MS]).
 */
// i18n Hoja de ruta de extracción posterior(Procesamiento unificado durante la migración general,Este departamento suspende)— Este ViewModel ha sido inyectado.
// appContext(Ver constructor abajo),Disponible más tarde appContext.getString(R.string.xxx) o
// ErrorMessage.toLocalizedString(appContext) Reemplace la cadena china en las siguientes áreas:
// MuseToast.show / reportError / addError / errors.emit / toast /
// fetchModelsError / Cada ChatError.message/texto de notificación/copia de progreso de paso/etiqueta de descuento de exportación, etc.
// Logger Iniciar sesión/TAG/LLM Las palabras rápidas no se extraen.
// @REFACTOR: Subcomponentes recomendados para dividir
//   - ChatSessionManager: creación de sesión/cambiar/Lógica de archivo
//   - ChatStreamOrchestrator: Solicitudes de transmisión/Reconectar/tiempo de espera
//   - ChatToolDispatcher: ToolCall enrutamiento y ejecución
//   - ChatContextController: Gestión y compresión del contexto.
//   - ChatAudioController: TTS/ASR Gestión de estado
//   - ChatErrorHandler: Clasificación de errores y mensajes de usuario

/**
 * v1.131: Elimina la expresión regular de la etiqueta de nombre @document — Constantes de nivel de archivo,evitar cada vez [buildWebSearchQuery] Todas las llamadas son de nueva creación.
 *
 * Se utiliza para limpiar las consultas de los usuarios antes de las búsquedas web. @mention(Semántica de referencia de la base de conocimientos.),
 * Evite que contamine las palabras clave de búsqueda.
 */
@Suppress("UnusedPrivateProperty")
private val KNOWLEDGE_MENTION_REGEX = Regex("@[^\\s@]+")


/** R-TEST-06: Enviar guardia pura lógica (mensaje de defensa aérea/Transmisión/Agent Reentrada de creación de sesión). */
internal fun canStartGeneration(text: String, images: List<String>, isStreaming: Boolean, isCreatingAgentSession: Boolean): Boolean =
    (text.isNotBlank() || images.isNotEmpty()) && !isStreaming && !isCreatingAgentSession

/** R-TEST-06: Fusionar el contenido del documento que se enviará con la entrada del usuario antes de enviarlo(Texto del documento + entrada del usuario). */
internal fun buildSendText(
    rawText: String,
    documentContents: List<String>,
    budget: io.zer0.muse.context.ContextBudget = io.zer0.muse.context.ContextBudget(),
): String {
    val docText = documentContents.joinToString("\n\n---\n\n")
    // M4.3: El texto adjunto está sujeto al límite superior unificado de ContextBudget.(mantener el encabezado,El truncamiento es diagnóstico);
    // La entrada del usuario está al final de la secuencia de empalme.,No será truncado ni absorbido por archivos adjuntos.
    val clampedDoc = budget.clampText(io.zer0.muse.context.ContextSection.ATTACHMENT_TEXT, docText)
    return when {
        documentContents.isEmpty() -> rawText
        rawText.isBlank() -> clampedDoc
        else -> "$clampedDoc\n\n---\n\n$rawText"
    }
}

/** R-TEST-06: Sólo si no es streaming, el último es con [interrumpido] La continuación solo está permitida si el mensaje del asistente marcado está marcado. */
internal fun canContinueGeneration(isStreaming: Boolean, lastMessage: UIMessage?): Boolean = !isStreaming && lastMessage != null &&
    lastMessage.role == MessageRole.ASSISTANT &&
    lastMessage.content.contains("[interrumpido]")

/** R-TEST-06: eliminar [interrumpido] marca de cola,Mantenga el contenido antes del punto de interrupción. */
internal fun resumeFromInterrupted(content: String): String = content.removeSuffix("\n\n[interrumpido]").removeSuffix("[interrumpido]")

/**
 * C-11: calcular [a] con [b] La longitud de prefijo común más larga de(Por unidad de código Unicode).
 *
 * función pura,Proporciona transmisión reanudable para eliminar duplicados(duplicateRemaining)uso:Cuando el proveedor anula/Al completar el contenido devuelto,
 * El contenido devuelto no coincide palabra por palabra con el prefijo del contenido mostrado.(La coincidencia original exacta de startWith perderá los duplicados por completo.,
 * conducir a la duplicación/saltar). Utilice LCP para determinar la cantidad de superposición,Saltar sólo las partes superpuestas,Conserve el nuevo contenido después de reescribirlo.
 *
 * Ejemplo:longestCommonPrefix("El clima esta muy bueno hoy", "esta lloviendo hoy") == 3(saltar"hoy dia").
 */
internal fun longestCommonPrefix(a: String, b: String): Int {
    val max = minOf(a.length, b.length)
    var i = 0
    while (i < max && a[i] == b[i]) i++
    return i
}

/**
 * Estrategia de poda conservadora para la transmisión continua de duplicados.
 *
 * Solo hay dos casos en los que delta es contenido de repetición:
 * 1. Todo el delta es un prefijo antiguo;
 * 2. delta Se tratan los prefijos antiguos restantes, seguidos del contenido nuevo.
 *
 * Si delta se bifurca a mitad del prefijo antiguo, el prefijo común puede ser simplemente un comienzo coincidente del nuevo contenido;
 * En este momento, se devuelve 0 y se retiene todo el delta para evitar tragarse la nueva primera letra real del usuario.
 */
internal fun resumeOverlapToDrop(duplicateRemaining: String, delta: String): Int {
    if (duplicateRemaining.isEmpty() || delta.isEmpty()) return 0
    val overlap = longestCommonPrefix(duplicateRemaining, delta)
    // A partial matching chunk may be legitimate new continuation text. Only drop
    // a delta that proves the entire remaining old answer was replayed; otherwise
    // prefer visible duplication over silently swallowing user-visible characters.
    return overlap.takeIf { it == duplicateRemaining.length } ?: 0
}

/**
 * v2.0: Determinar si el proveedor es“reescribir desde cero” — El texto original ha sido ampliamente consumido.(Cantidad de superposición >= La mitad del texto original y al menos 6 caracteres.)
 * puntas abiertas,Respuesta completa indicando que la nueva ronda es una regeneración.,Aplicar texto nuevo para reemplazar contenido antiguo en lugar de agregarlo,Evite empalmes repetidos.
 */
internal fun shouldReplaceOnResumeRewrite(duplicateTotal: Int, consumedChars: Int, minOverlap: Int = 6): Boolean =
    duplicateTotal > 0 && consumedChars >= maxOf(minOverlap, duplicateTotal / 2)

/**
 * v2.0: Done fondo del escenario — Cuando se hizo una nueva ronda de intentos de texto para volver a escribir el fragmento inicial del texto original,,
 * También considerado como“reescribir desde cero”,El contenido antiguo debe ser reemplazado.(artículo antiguo+La capa final de protección para empalmes repetidos de artículos nuevos.).
 */
internal fun shouldReplaceOnResumeSupersede(original: String?, attemptText: String?, currentContent: String): Boolean {
    if (original.isNullOrEmpty() || attemptText.isNullOrEmpty()) return false
    val head = original.take(SUPERSEDE_HEAD_CHARS)
    if (head.length < 4) return false
    if (!attemptText.contains(head)) return false
    return !currentContent.contentEquals(attemptText)
}

/** [shouldReplaceOnResumeSupersede] La longitud del fragmento de encabezado original comparado. */
private const val SUPERSEDE_HEAD_CHARS = 6

/**
 * C-12: Determinar si hay una ronda disponible [toolModel] como modelo de solicitud.
 *
 * rueda de herramientas(Los resultados de la ronda anterior incluyen toolCalls)Ir por defecto toolModel;Pero cuando esta ronda de la historia todavía contiene imágenes,
 * y [toolModel] Cuando la entrada visual no es compatible,Continuar enviando imágenes a modelos que no admiten la visión resultará en HTTP 400,
 * En este punto, el modelo principal debe retroceder.(Sus capacidades visuales se han determinado en prepareVisionContext./Rebajar).
 * Devuelto cuando el modelo de herramienta no está configurado o el historial no tiene imagen true,Mantenga el comportamiento predeterminado.
 *
 * @param history El historial de conversaciones que se enviará en esta ronda.
 * @param toolModel Modelo de herramienta configurable por el usuario(Se han mejorado las capacidades de reconocimiento.)
 */
internal fun canUseToolModelForRound(history: List<UIMessage>, toolModel: Model): Boolean {
    if (toolModel.supportsVisionInput()) return true
    val hasImages = history.any { it.imageBase64List.isNotEmpty() }
    return !hasImages
}

/** R-TEST-06: La regeneración solo está disponible cuando no hay transmisión, con una sesión y la variante de usuario actual es seleccionable. */
internal fun canRegenerate(isStreaming: Boolean, hasSession: Boolean, hasSelectedUserVariant: Boolean): Boolean =
    !isStreaming && hasSession && hasSelectedUserVariant

/** Build the source text used by message translation, including reasoning-only replies. */
internal fun buildTranslationSourceText(message: UIMessage): String {
    val content = message.content.trim()
    val reasoning = message.reasoning.orEmpty().trim()
    return when {
        content.isNotBlank() && reasoning.isNotBlank() ->
            "${InternalPromptMarkers.BODY_HEADER}:\n$content\n\n${InternalPromptMarkers.THINKING_HEADER}:\n$reasoning"
        content.isNotBlank() -> content
        else -> reasoning
    }
}

internal fun effectiveChatSessionId(state: ChatUiState): String? = if (state.isAgentMode) state.agentSessionId else state.currentSessionId

// La fase de división progresiva todavía requiere una inyección plana de dependencias; Mantenga estable el orden de los parámetros de Koin para evitar cambios de comportamiento a gran escala.
@Suppress("LongParameterList")
class ChatViewModel(
    private val chatService: ChatService,
    private val settings: SettingsRepository,
    private val memoryTicker: MemoryTicker,
    private val sessionRepository: SessionRepository,
    // v2.x: Lector de punto de control de sesión —— Al armar el contexto, el"Antes de los límites comprimidos"contenido en un resumen
    private val checkpointReader: io.zer0.memory.summary.ContextCheckpointReader,
    private val imageService: ImageService,
    private val videoGenerationService: io.zer0.ai.video.VideoGenerationService,
    private val documentParser: DocumentParser,
    private val toolRegistry: ToolRegistry,
    private val assistantRepository: AssistantRepository,
    private val webSearchService: WebSearchService,
    private val lorebookRepository: LorebookRepository,
    private val quickMessageRepository: QuickMessageRepository,
    private val promptInjectionRepository: PromptInjectionRepository,
    private val ocrManager: io.zer0.muse.doc.OcrManager,
    private val ttsManager: TtsManager,
    private val skillRepository: io.zer0.muse.data.skill.SkillRepository,
    private val skillExecutor: io.zer0.muse.tools.SkillExecutor,
    // v1.201: Administrador de pausas delegado + rastreador de enlaces(Comparte la misma instancia única de Koin con SkillExecutor)
    private val delegationPauseManager: io.zer0.muse.tools.DelegationPauseManager,
    private val delegationChainTracker: io.zer0.muse.tools.DelegationChainTracker,
    private val agentRouter: AgentRouter,
    private val folderRepository: io.zer0.muse.data.session.FolderRepository,
    private val notificationManager: MuseNotificationManager,
    // v0.30-a: Ensamblador de avisos del sistema(6 paso flujo de trabajo paso 1)
    private val systemPromptAssembler: io.zer0.muse.transformer.SystemPromptAssembler,
    // v1.43: Administrador de compilación a nivel de aplicación(Cortar páginas/Sigue generando en segundo plano)
    private val chatGenerationManager: ChatGenerationManager,
    // v1.43: Almacén de productos(Tarjetas de productos durante la sesión)
    private val artifactRepository: ArtifactRepository,
    // v1.43: Solicitud Context,Se utiliza para iniciar el servicio en primer plano.
    private val appContext: Context,
    // v1.54: RAG servicio(Base de conocimientos inyección automática)
    private val ragService: io.zer0.muse.rag.RagService,
    // v1.25: Puente de asistencia visual(Pasar un modelo de texto plano a través de un modelo visual"ver"fotos)
    private val visionBridge: io.zer0.muse.vision.VisionBridge,
    // P2-4: Registrador de auditoría(Registre eventos de mensajes de usuario en el portal de envío de mensajes)
    private val auditLogger: AuditLogger,
    // P3: Persistencia del modo de permiso de herramienta a nivel de sesión
    private val sessionPermissionStore: SessionPermissionStore,
    // v1.0.15: Oyente de estado de red(StreamInterrupted Luego espere a que la red se recupere y se vuelva a conectar automáticamente.)
    private val networkMonitor: NetworkMonitor,
    // v2.1: Retrato de actividad del usuario,Registrar la actividad cuando el usuario envía un mensaje + actualizar el tipo de finalización de la conversación,Impulsar la programación de mensajes proactiva y adaptativa
    private val activityProfile: UserActivityProfile,
    // v1.202: Comentarios sobre los resultados de la delegación asincrónica(Suscríbete completedTasks,Inyecte los resultados del subagente de la finalización en segundo plano en la conversación principal como interludio)
    private val deferredResultStore: io.zer0.muse.tools.DeferredResultStore,
    // v1.202: Administrador de hilos de subagente(Exponer subprocesos de agentes secundarios activos a la visualización de la interfaz de usuario)
    private val subagentThreadStore: io.zer0.muse.data.subagent.SubagentThreadStore,
    // v1.x: Administrador de recursos a nivel de sesión(Recuento de referencias + limpieza inactiva)
    private val sessionManager: io.zer0.muse.session.ConversationSessionManager,
    // P1-1: Hook Registro(Inject ToolOrchestrator + procesamiento de mensajes Hook)
    private val hookRegistry: io.zer0.muse.hook.HookRegistry? = null,
    // v1.0.52 P2-3: AI Memoria guardada automáticamente(Extraiga entidades en tiempo real durante las conversaciones./relación/fusionar/Clasificación)
    private val memoryAutoSaveScheduler: io.zer0.memory.ai.MemoryAutoSaveScheduler? = null,
    // B0-08: Comprobador de hitos(Una vez finalizada la compilación, verifique si se activa el hito.)
    private val milestoneChecker: MilestoneChecker? = null,
    // B2-04: La unidad es inyectada por Koin ToolOrchestrator(sesión principal/Enlace de aprobación compartido del subagente)
    private val toolOrchestrator: io.zer0.muse.tools.ToolOrchestrator,
    // B2-04: Enrutamiento de aprobación de subagente(Registra esta instancia como delegate,Cuando el subagente necesita aprobación, aparece la tarjeta de aprobación de la sesión principal)
    private val toolApprovalRouter: io.zer0.muse.tools.ToolApprovalRouter,
    /** P0 Árbol de diálogo para seleccionar almacenamiento de instantáneas(puede ser null,El entorno de prueba no inyecta). */
    private val treeSnapshotStore: ConversationTreeSnapshotStore? = null,
    // v1.x: Registro de instancias de navegador a nivel de sesión(Independiente por sesión WebView,Liberado cuando se elimina la sesión)
    private val browserManagerRegistry: io.zer0.muse.tools.BrowserManagerRegistry? = null,
    // v1.x: Almacenamiento de configuración de herramientas(Persistencia de la política de aprobación) — Koin Inyectar un singleton,
    // Elimine el bloqueo de múltiples instancias de DataStore con el mismo archivo causado por nueva directa
    private val toolConfigStore: io.zer0.muse.tools.ToolConfigStore? = null,
    /** MCP Registro,Se utiliza para esperar a que el servidor vinculado al asistente complete las herramientas/lista antes del primer mensaje. */
    private val mcpRegistry: io.zer0.muse.mcp.McpRegistry? = null,
    /** Generación unificada/Registro de recursos de ejecución de herramientas,Se utiliza para cancelación por sesión y diagnóstico de resultados tardíos. */
    private val executionRegistry: io.zer0.muse.session.SessionExecutionRegistry? = null,
    /**
     * P2-23: Implementación de herramienta de generación de medios.(Koin single,Es la misma instancia que registra la herramienta con ToolRegistrarBootstrapper.).
     *
     * La herramienta en sí se registró cuando se inició la aplicación. [ToolRegistry],Esta clase solo es responsable de instalar el host de entrega de sesiones durante la inicialización.
     * ([ChatMediaGenHost]),dejar generate_image / generate_video / generate_qr_code el resultado
     * puede volver a escribir"Mensaje del asistente que se está generando actualmente". Cuando el entorno de prueba no se inyecta, es null(El host no se instala,Las herramientas degeneran en
     * Ruta del archivo de retorno/URL).
     */
    private val mediaGenTools: io.zer0.muse.tools.MediaGenToolsImpl? = null,
    // v2.x: comité — Convoca un chat grupal temporal en cualquier momento desde la conversación principal para discutir y retroalimentar las conclusiones.(Comparta la misma instancia del programador con la página de chat grupal)
    private val groupChatScheduler: io.zer0.muse.schedule.GroupChatScheduler? = null,
    private val groupChatRepository: io.zer0.muse.data.groupchat.GroupChatRepository? = null,
    private val conversationSummaryManager: SessionSummaryManager? = null,
    /**
     * v2.6.7: almacén de relaciones —— Graba una interacción real cuando un usuario envía un mensaje.
     *
     * Anteriormente, recordInteraction solo estaba disponible en teléfonos pequeños.“AI Enviar mensajes privados de forma proactiva”Llamado una vez,
     * Cada frase que dice el usuario en un chat normal no actualiza el nivel de intimidad ni el tiempo de la última interacción.
     * Como resultado, el sistema de relaciones sólo vive en el círculo de amigos.
     *
     * Al final de la lista de parámetros (no junto a ActivityProfile):Koin El ensamblaje utiliza parámetros posicionales para pasar,
     * Insertar parámetros en el medio generará docenas de cambios posteriores. get() Desalineación general.
     */
    private val relationshipRepository: io.zer0.muse.data.relationship.AssistantRelationshipRepository? = null,
) : ViewModel(), ChatStateAccessor, io.zer0.muse.tools.ToolApprovalBridge {
    // v1.0.54: autoSave Estado de deduplicación(30 Ejecute solo la misma sesión una vez por segundo,Antiacumulación)
    // v1.0.54: autoSave Estado de deduplicación(30 Ejecute solo la misma sesión una vez por segundo,Antiacumulación)
    private var lastAutoSaveSessionId: String? = null
    private var lastAutoSaveAt: Long = 0L

    /** Capas de adaptación de enlaces nuevas y antiguas; De forma predeterminada, los eventos solo se escriben cuando el interruptor de sombra está activado explícitamente. */
    private val conversationService = ConversationService(sessionRepository)
    private val messageCommit = MessageCommit(sessionRepository)

    /**
     * Registre eventos ocultos que no afecten el comportamiento del usuario.
     * CancellationException Se debe seguir lanzando para evitar que las escrituras de diagnóstico absorban la generación de paradas del usuario.
     */
    private suspend fun recordConversationShadow(event: ConversationEventDraft) = generationController.recordConversationShadow(event)

    companion object {
        /**
         * v2.5.7: El valor final cuando el modelo no declara una ventana de contexto (token).
         *
         * Originalmente 32768, pero para tránsito./Modelo de enrutamiento aleatorio (contextWindow=null) es demasiado pequeño,
         * Sin embargo, la definición real de historial + sistema + herramienta a menudo excede con creces los 32K, lo que lleva a un error de cálculo al exceder el límite. Se menciona 128K (un límite inferior común para los modelos modernos).
         */
        private const val DEFAULT_CONTEXT_WINDOW_FALLBACK = 131_072

        /** v0.47: Umbral de tiempo de espera de llamada de herramienta(2 minutos),Terminar después del tiempo de espera,Evite bloquear la salida de streaming. */

        /** La tarjeta de aprobación no puede bloquear la generación en segundo plano indefinidamente; si caduca, se procesará como rechazado. */

        /** v2.x Etapa de adelgazamiento de herramientas.1:El límite superior de mensajes históricos escaneados por herramientas adhesivas(de atrás hacia adelante). */
        private const val STICKY_HISTORY_SCAN_MESSAGES = 50

        /** v1.53-A1: Tamaño de página de paginación de mensajes(Carga inicial + deslizar hacia arriba para cargar más tamaños de ventana). */
        private const val MESSAGE_PAGE_SIZE = 50

        /**
         * v2.5.3 (P3-3): El umbral de estrangulamiento de la placa durante el modo de flujo.
         *
         * Cada flujo generado corresponde a una sesión, por lo que estos dos elementos son“Lote por sesión flush”Granularidad:
         * El nuevo contenido acumulado es inferior a [PERSIST_CHAR_THRESHOLD] personajes, y no es suficiente desde la última colocación.
         * [PERSIST_TIME_THRESHOLD_MS] La escritura en disco se omite para evitar amplificar el volumen de escritura en disco fragmento a fragmento.
         */

        /** Comprime automáticamente la cola de seguridad: el sistema retiene una pequeña cantidad de mensajes recientes y rondas completas de herramientas,Los usuarios no necesitan seleccionar la cantidad de elementos. */

        /** v1.79 (L-CV1): Valor de reserva cuando falla la lectura del límite superior de ronda de herramienta(La ruta normal está determinada por la configuración.,0=Ilimitado). */

        /**
         * v1.134 P1-1: Número máximo de reintentos automáticos de transmisión(NETWORK/RATE_LIMIT error).
         * v1.0.16: mencionado de 2 3,retirarse 3s/10s/30s,Cubre la duración típica de conmutación en segundo plano(5-15s).
         */

        /**
         * v1.0.17: StreamInterrupted Tiempo de espera para el currículum inteligente para esperar la recuperación de la red(milisegundos).
         *
         * Encuesta después de un corte de red [NetworkMonitor.isOnline],la mayoría espera 30s;
         * Si el tiempo de espera no se recupera, se reducirá a reintento manual.(Mantenga algo de contenido + isRecoverable).
         * 30s Cubre la duración típica de conmutación en segundo plano/conmutación de red(5-15s),Evite esperar indefinidamente.
         */

        /** v1.0.17: Intervalo de sondeo mientras se espera la recuperación de la red(milisegundos). */
        private const val NETWORK_POLL_INTERVAL_MS = 2_000L

        /** v1.200: Umbral de confianza del enrutamiento automático. Si el valor es inferior a este valor, se seguirá utilizando el asistente actual. */
        private const val AUTO_ROUTE_CONFIDENCE_THRESHOLD = 0.55f
        // v1.80 (L-CV1): transmisión/Dibujo/Números mágicos relacionados con la compresión extraídos como constantes
        /**
         * Relación de disparo inferior para compresión automática (calibre de degradación con ventana desconocida o extremadamente pequeña).
         *
         * v2.x: El calibre principal se cambia a"Reservado según proporción de ventana."(Ver [shouldAutoCompress], con
         * [AUTO_COMPRESS_MIN_RESERVE_TOKENS] / [AUTO_COMPRESS_RESERVE_RATIO] coincidencia).
         * Se corrigieron los activadores del 80% demasiado tarde para modelos con ventanas superiores a 200K: cuando el 80% está ocupado, un ciclo de herramienta
         * Es posible bloquear la ventana durante la generación.
         */
        private const val AUTO_COMPRESS_TOKEN_RATIO = 0.8f

        /** El límite inferior de la reserva de compresión automática: no importa cuán pequeña sea la ventana, se deben reservar tantos tokens para la salida y los resultados posteriores de la herramienta. */
        private const val AUTO_COMPRESS_MIN_RESERVE_TOKENS = 16_384

        /** Comprime automáticamente la cantidad reservada como una proporción de la ventana (reserva proporcionalmente en ventanas grandes para evitar un valor fijo que sea demasiado pequeño en relación con la ventana). */
        private const val AUTO_COMPRESS_RESERVE_RATIO = 0.1

        /**
         * Si es necesario activar la compresión automática (función pura, conveniente para una sola prueba para bloquear el calibre).
         *
         * Calibre = "Ocupar al otro lado de la ventana − Monto reservado", se toma el importe reservado `max(16K, 10% ventana)`:
         *  - ventana ≤ 0(desconocido) u ocupado ≤ 0(Aún no calculado)→ No activado;
         *  - La ventana es tan pequeña que ni siquiera cabe en la cantidad reservada (≤ Monto reservado)→ Vuelva a una proporción fija del 80% para evitar que se active para siempre o nunca.
         *
         * ¿Por qué no arreglar el 80%?200K Una ventana inferior al 80% significa que se han saturado 160K, lo que deja"Resultado de esta ronda + resultados de herramientas posteriores"
         * Solo quedan 40K de espacio y un ciclo de herramienta puede bloquear la ventana a mitad de camino. —— Y no hay posibilidad de compresor en el medio.
         */
        internal fun shouldAutoCompress(currentTokens: Int, maxTokens: Int): Boolean {
            if (maxTokens <= 0 || currentTokens <= 0) return false
            val reserve = maxOf(AUTO_COMPRESS_MIN_RESERVE_TOKENS, (maxTokens * AUTO_COMPRESS_RESERVE_RATIO).toInt())
            return if (maxTokens > reserve) {
                currentTokens > maxTokens - reserve
            } else {
                currentTokens.toFloat() / maxTokens > AUTO_COMPRESS_TOKEN_RATIO
            }
        }

        /**
         * v0.49 clasificación de errores (función pura,B4 La prueba complementaria se denomina interna).
         * Priorizar la ruta de tipo (Provider arrojado ProviderException), de lo contrario inferencia de cadenas
         * (inferFromMessage,Deprecated Ruta de compatibilidad), ninguno de ellos acertó → UNKNOWN.
         */
        internal fun classifyErrorTypeStatic(message: String, throwable: Throwable? = null): ChatErrorType {
            val providerError =
                (throwable as? ProviderException)?.providerError
                    ?: inferFromMessage(message, throwable)
            return when (providerError) {
                is ProviderError.Network -> ChatErrorType.NETWORK
                is ProviderError.RateLimit -> ChatErrorType.RATE_LIMIT
                // v1.0.1 (P4): 5xx incorporar NETWORK(Se puede volver a intentar),El original cae en DESCONOCIDO sin volver a intentarlo
                is ProviderError.ServerError -> ChatErrorType.NETWORK
                is ProviderError.AuthError -> ChatErrorType.API_KEY
                // v2.6.8: Los saldos insuficientes se atribuyen a API_KEY (un problema a nivel de credencial, no un parámetro),
                // Evite ser considerado una Solicitud Inválida y guiar a los usuarios para que ajusten los parámetros.
                is ProviderError.InsufficientQuota -> ChatErrorType.API_KEY
                is ProviderError.InvalidRequest -> ChatErrorType.UNKNOWN
                is ProviderError.Cancelled -> ChatErrorType.UNKNOWN
                is ProviderError.Unknown -> ChatErrorType.UNKNOWN
                null -> ChatErrorType.UNKNOWN
            }
        }

        /**
         * v2.x: contar"Resumen de esta nueva fusión"El número de mensajes (función pura,B3 La prueba complementaria se denomina interna).
         *
         * usar**Lista de mensajes ordenados**Para contar (en lugar de ordenar el conjunto de ID de anulación): la ID del mensaje es un UUID aleatorio,
         * La colección en sí está desordenada y cualquier inferencia basada en comparaciones de identificación se calculará mal con los datos reales.
         */
        internal fun newlyCoveredCountStatic(messages: List<UIMessage>, coveredIds: Set<String>, previousBoundaryId: String?): Int {
            if (coveredIds.isEmpty()) return 0
            val startIndex =
                if (previousBoundaryId == null) {
                    0
                } else {
                    messages.indexOfFirst { it.id.toString() == previousBoundaryId }
                        .let { if (it < 0) 0 else it + 1 }
                }
            return messages.drop(startIndex).count { it.id.toString() in coveredIds }
        }
        // v1.105 escenario 4: IMAGE_SCALE_TARGET / IMAGE_JPEG_QUALITY / DOC_MAX_CHARS Se ha hundido al correspondiente Coordinator
        /**
         * v1.0.3: Umbral de caracteres de actualización de la interfaz de usuario de streaming(acelerador).
         *
         * de 40 a 12,Permitir que el contenido fluya más fluidamente,evitar"párrafo por párrafo"falla visual.
         * El primer token se actualizará inmediatamente, superando este umbral.(ver [launchStream] Dentro de la rama isFirstToken).
         */

        /**
         * v1.0.3: Umbral de tiempo de actualización de la interfaz de usuario de streaming(milisegundos,acelerador).
         *
         * de 80ms a 50ms,Combinado con un umbral de caracteres más pequeño para que la transmisión sea más coherente.
         * 50ms ≈ 20fps,El ojo humano percibe"flujo continuo"en vez de"párrafo por párrafo".
         */
        // v1.0.4: Transmisión de corte adaptativo de la interfaz de usuario(50ms Aceleración + corte adaptativo).
        // poner"Número de caracteres O tiempo"El bicondicional se cambia a"Aceleración fija de 50 ms + tamaño de corte adaptable":
        // - delta Acumular primero pendingBuilder,50ms Cuando se activa el temporizador, los primeros N caracteres se toman y se emiten de acuerdo con el tamaño del segmento.
        // - El tamaño del segmento se basa en la velocidad de transmisión(Media celular de los últimos 10 intervalos de fragmentos)Ajuste dinámico:
        //   flujo lento(Reasoning)Grandes intervalos → rate pequeño → rebanada pequeña(de grano fino,2-40)
        //   flujo rápido(texto plano)Pequeños intervalos → rate grande → rebanada grande(lote,40-240)
        /** Intervalo de aceleración fijo(milisegundos),Cuando se acaba el tiempo, se activa una salida de segmento. */

        private const val STREAM_THROTTLE_MS = 50L

        /**
         * v2.0: La transmisión de textos largos supera a la adaptativa.
         *
         * Cuanto más largo sea el texto,Markdown análisis de bloques/El costo de la reorganización y la reorganización de la burbuja es mayor. El texto corto permanece 50 ms.
         * "máquina de escribir"vencer;Relajar el intervalo de actualización después de exceder el umbral,Se adapta a rebanadas más grandes(ver [computeAdaptiveSlice] de
         * flushScale),Reducir el número de redibujos durante respuestas largas,Al mismo tiempo, mantenga la apariencia del flujo palabra por palabra.
         */
        fun streamFlushIntervalMs(builderLength: Int): Long = when {
            builderLength < 2_000 -> STREAM_THROTTLE_MS
            builderLength < 6_000 -> 90L
            else -> 130L
        }

        /** Límite inferior de corte adaptativo:Las transmisiones lentas también generan al menos 2 caracteres,Evite las actualizaciones vacías. */

        /** Punto de referencia de corte adaptativo:rate=1.0 tamaño de la rebanada cuando,corresponder avgInterval≈50ms flujo de velocidad media. */

        /** Límite superior de corte adaptativo:La transmisión rápida puede generar hasta 420 caracteres a la vez(Los ritmos de texto largos se ralentizan y sincronizan para acercar). */

        /** chunk tamaño de ventana corrediza de intervalo(El intervalo de los últimos N fragmentos se utiliza para calcular la tasa promedio.). */
        // v1.117: Se eliminaron 6 constantes huérfanas.(STREAM_NOTIF_*/STREAM_TOKEN_*/STREAM_PERSIST_*),
        // La lógica de limitación real se implementa utilizando literales dentro de launchStream.,Nunca se hace referencia a estas constantes.

        // v1.116 (C1-2): El número máximo de mensajes en la parte de la cadena de herramientas del historial de conversaciones dentro del bucle de llamada de la herramienta.
        // Descartar rondas de llamadas de herramientas anteriores cuando se excedan(Mantener contexto inicial + cadena de herramientas reciente).

        // P0-6: El número máximo de caracteres para la vista previa del parámetro de herramienta que se muestra en la tarjeta de aprobación durante la recuperación del punto de interrupción.
        private const val MAX_TOOL_APPROVAL_ARGS_CHARS = 20_000 // v2.0.1: 500→20000, la tarjeta de aprobación requiere un JSON completo para generar un resumen humano

        // v1.116: Colección de ID de herramientas relacionadas con emoticones,Filtrado para control probabilístico
        private val STICKER_TOOL_IDS = setOf("list_stickers", "send_sticker")

        /**
         * v1.104 U7: [favoriteTagFilter] valor especial de,expresar"Mostrar solo desagrupados".
         *
         * Utilice una cadena interna que no entre en conflicto con las etiquetas definidas por el usuario.
         * FavoritesScreen arriba FilterChip "No agrupado" Tono cuando se selecciona setFavoriteTagFilter(FAVORITE_TAG_UNGROUPED).
         */
        const val FAVORITE_TAG_UNGROUPED = "__ungrouped__"

        // Dentro del grupo de incumplimiento histórico token(v2.0 El valor de las acciones cayó al inventario,Solo leer pero no escribir)
        const val FAVORITE_GROUP_INSPIRATION = "__group_inspiration__"
        const val FAVORITE_GROUP_CODE = "__group_code__"
        const val FAVORITE_GROUP_LEARNING = "__group_learning__"
        const val FAVORITE_GROUP_CUSTOM = "__group_custom__"
    }

    // ── v2.x Refactor S1: expuesto a miembros de ChatToolLoopRunner(Referencia directa al runToolLoop original)──
    /** El subproceso del subagente que el usuario cierra manualmente dentro de este proceso. Sólo afecta a la visualización y no cambia los datos. */
    private val closedSubagentThreads: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())

    private val stateStore = ChatStateStore()
    private val _state get() = stateStore.state

    // v1.100: StateFlow ya mismo conflated(Mantenga solo el último valor),Al transmitir actualizaciones de alta frecuencia
    // collectAsStateWithLifecycle Sólo se obtendrá el último valor. El verdadero obstáculo reside en el alcance de la reorganización.(P1-P3 Ya
    // Reducido por @Immutable + deriveStateOf),No se requieren muestras adicionales.
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** B2-01: Lista de mensajes independiente StateFlow,Ya no se incluye en ChatUiState. */
    private val _messages get() = stateStore.messages
    val messages: StateFlow<List<UIMessage>> = _messages.asStateFlow()

    /**
     * §11-4: Pertenece a la lista de favoritos"versión antigua"Una colección de identificadores de mensajes.
     *
     * Regenerar solo filas de variantes nuevas,La ubicación de recopilación de la fila anterior no se mueve.,Así que quizás mantengas la versión 1 en tus favoritos.,
     * La sesión muestra la versión 3. Guardar aquí"Más antiguo que la última versión del grupo."de id,Una línea de recordatorio está marcada en la tarjeta.
     *
     * Abrir un Flow separado sin colocarlo [ChatUiState]:Esa clase de datos ya tiene 124 parámetros.,
     * Agregar otro destruirá toda la línea base de firmas de detekt.(Coincidencia básica de texto completo por firma,No por número de línea).
     * Este valor solo lo lee la página de colección.,No relacionado con ninguna representación del enlace de chat principal.,Perfecto para una transmisión independiente.
     */
    private val _staleFavoriteIds = MutableStateFlow<Set<Uuid>>(emptySet())
    val staleFavoriteIds: StateFlow<Set<Uuid>> = _staleFavoriteIds.asStateFlow()

    // v1.93+: Caché LRU en memoria de mensajes de sesión.
    // ChatViewModel Después de cambiar a Koin single, la lista de mensajes de la sesión nunca se publicará.,La acumulación de memoria es evidente después de un uso prolongado.
    // Aquí se mantiene una copia en memoria de las sesiones SessionMemoryCache.MAX_CACHE_SIZE más recientes en la política LRU,
    // Desalojar automáticamente las sesiones a las que no se ha accedido durante más tiempo después de exceder el límite,Vuelva a cargar desde la base de datos al volver a cambiar.
    // Nota:Sin dependencias externas,Creación de instancias directas,no irse Koin(evitar cambios single{} Lista de parámetros posicionales registrados).
    // v2.x: Configuración de lectura en caliente del límite superior(Configuración de chat→Avanzado,Predeterminado 5)
    private val sessionMemoryCache = SessionMemoryCache { settings.sessionCacheSizeCache }

    // ── Modo de conversación de voz(Grabación → identificar → pensar → bucle de transmisión)──────────
    // máquina de estados:IDLE → LISTENING → THINKING → SPEAKING → LISTENING(bucle)
    // IDLE:Espere a que el usuario haga clic en el botón principal.
    // LISTENING:ASR Grabación,Escritura de devolución de llamada en tiempo real transcript
    // THINKING:Mensaje de usuario enviado,Espere a que se complete la respuesta de transmisión de IA
    // SPEAKING:TTS Leer las respuestas de la IA en voz alta,Reanudar después de leer en voz alta LISTENING(dialogo continuo)
    private val voiceState = ChatVoiceState()
    private val _voiceConversationState get() = voiceState.state
    val voiceConversationState: StateFlow<VoiceConversationState> = _voiceConversationState.asStateFlow()

    /**
     * Texto de reconocimiento acumulado para esta ronda del modo de diálogo de voz(Desde el inicio de la grabación hasta el punto "Fin",Acumulativo en múltiples sesiones de ASR).
     * UI Mostrar la transcripción en consecuencia,Envíe la sección completa cuando haga clic en "Finalizar".
     */
    private val _voiceConversationTranscript get() = voiceState.transcript
    val voiceConversationTranscript: StateFlow<String> = _voiceConversationTranscript.asStateFlow()

    /** Modo de conversación de voz texto de respuesta AI actual(SPEAKING llenar cuando,UI Mostrar el contenido de lectura en consecuencia). */
    private val _voiceConversationAiReply get() = voiceState.aiReply
    val voiceConversationAiReply: StateFlow<String> = _voiceConversationAiReply.asStateFlow()

    /** Avisos luminosos de conversación por voz(Como \"No te escuché claramente, por favor dilo de nuevo\".);Se borra cuando se reinicia la grabación. */
    val voiceConversationHint: StateFlow<String?> = voiceState.hint.asStateFlow()

    /** TTS Estado de juego(paso a través TtsManager.playbackState,Para que la interfaz de usuario del diálogo de voz muestre el progreso). */
    val ttsPlaybackStateFlow: StateFlow<PlaybackState> get() = ttsManager.playbackState

    /** Corrutina de observación del bucle de diálogo de voz(Se mudó a ChatMediaController). */

    // Gerente de sucursal de mensajes
    // P0 árbol de diálogo: estructura de dos niveles(Grupo de preguntas de usuario → Grupo de respuesta del asistente)fuente de hecho,
    // _messages Mantenga la lista plana durante la transmisión,punto estable(enviar/Inténtalo de nuevo/Editar/Cambiar de sesión/final de la corriente)Reconstruya el árbol y muéstrelo simultáneamente.
    private val _conversationTree get() = stateStore.conversationTree
    val conversationTree: StateFlow<ConversationTree> = _conversationTree.asStateFlow()

    // P0 árbol de diálogo: reconstrucción de árboles/El cambio de sucursal se ha trasladado a ChatMessageController(treeSessionId),Aquí _conversationTree todavía lo comparte el anfitrión.
    // P0 árbol de diálogo: Mensaje de usuario para editar id(Rellene el cuadro de entrada después de que el usuario haga clic en editar,Aplicar como nueva variante de usuario al enviar)
    // v1.0.30: seguimiento de devolución de llamada — onAppForeground usar
    @Volatile private var lastSessionSwitchAt: Long = 0L

    @Volatile private var lastSessionSwitchIdValue: String? = null
    // Almacenamiento de configuración de herramientas(Persistencia de la política de aprobación) — Ver parámetros de construcción toolConfigStore

    // v1.135: Mensaje del asistente correspondiente a la ronda de llamada de herramienta actual id(Recibido ChatGenerationState)
    private var toolAssistantId
        get() = generationState.toolAssistantId
        set(value) {
            generationState.toolAssistantId = value
        }

    // Reparación de auditoría (S-01): toolAssistantId Sesión de generación correspondiente id(Recibido ChatGenerationState).
    private var activeToolSessionId
        get() = generationState.activeToolSessionId
        set(value) {
            generationState.activeToolSessionId = value
        }

    // Reparación de auditoría (A-13): Generar token generacional — Cada vez que se inicia runToolLoop/Finalizar el incremento automático.
    // exec* ejecución de herramientas(La generación de imágenes puede tardar decenas de segundos)Durante este período, si el usuario envía un nuevo mensaje/Cambiar de sesión,situación general
    // toolAssistantId será sobrescrito por una nueva ronda de generación,Los resultados de herramientas antiguas se escriben en mensajes de error(Contaminación de medios entre sesiones).
    // exec* La entrada captura el token actual.,Antes de escribir medios, verifique que el token no haya cambiado y que la sesión de destino siga siendo la sesión de visualización actual.
    // generar/Enviar contenedor de estado compartido(ver ChatGenerationState)
    private val generationState = ChatGenerationState()
    private var toolGenerationToken
        get() = generationState.toolGenerationToken
        set(value) {
            generationState.toolGenerationToken = value
        }

    /** Incrementar atómicamente los tokens generacionales; los incrementos simultáneos no perderán las actualizaciones. */
    private fun nextToolGenerationToken(): Long = generationState.nextToolGenerationToken()

    // Revisión de corrección (2.0 C-17): Mensaje de la rueda de herramientas para los medios adjuntos en esta generación id(Seguridad de concurrencia).
    // exec* Regístrese después de adjuntar medios exitosamente;runToolLoop Finalmente, realice una colocación integral de toda la información registrada.,
    // alejar"La memoria tiene medios escritos. → Realizar pedido ahora"El proceso fue asesinado entre/Ventana perdida por colocación fallida.
    private val toolMediaMessages: MutableSet<Uuid> get() = generationState.toolMediaMessages

    // B-24: Número de secuencia de generación de streaming — Cada vez que launchStream se incrementa y se escribe en StreamRunState.generaciónSerial.
    // Refinamiento/Verifique la ruta del error antes de borrar isStreaming"Sigue siendo la última generación.",Evitar ráfagas rápidas de fuego.
    // gen-1 Finalmente, borre el estado de transmisión de gen-2(UI Se muestra como"Todavía se está generando pero se nota que se ha detenido.").
    private var streamGenerationSerial
        get() = generationState.streamGenerationSerial
        set(value) {
            generationState.streamGenerationSerial = value
        }

    /** Los valores predeterminados globales y las anulaciones de sesión se guardan por separado; El cambio de página de chat solo cambia la superposición de la sesión actual y no sobrescribe la configuración global. */
    private var globalSelectedModelId
        get() = generationState.globalSelectedModelId
        set(value) {
            generationState.globalSelectedModelId = value
        }
    private var globalActiveProviderId
        get() = generationState.globalActiveProviderId
        set(value) {
            generationState.globalActiveProviderId = value
        }
    private var sessionModelOverrides
        get() = generationState.sessionModelOverrides
        set(value) {
            generationState.sessionModelOverrides = value
        }
    private var sessionProviderOverrides
        get() = generationState.sessionProviderOverrides
        set(value) {
            generationState.sessionProviderOverrides = value
        }

    private fun displayedSessionId(state: ChatUiState = _state.value): String? = effectiveChatSessionId(state)

    /**
     * v2.5.3 (P3-2): Almacenamiento en caché diferido en memoria de instantáneas de configuración a nivel de sesión.
     *
     * Primera carga de sesión/Al generar, la instantánea se lee desde la base de datos y se coloca en este mapa para [selectedModelForSession] /
     * [activeProviderForSession] Recurrir a“Configuración en ese momento”.
     */
    private val sessionConfigSnapshots = java.util.concurrent.ConcurrentHashMap<String, io.zer0.muse.data.session.SessionConfigSnapshot>()

    /** v2.5.3 (P3-2): Precargar la instantánea de la sesión en la memoria (cambio de sesión)./Se llama cuando se genera por primera vez). */
    internal fun cacheSessionConfigSnapshot(sessionId: String, snapshot: io.zer0.muse.data.session.SessionConfigSnapshot?) {
        if (snapshot == null) sessionConfigSnapshots.remove(sessionId) else sessionConfigSnapshots[sessionId] = snapshot
    }

    private fun selectedModelForSession(sessionId: String?): String? =
        sessionId?.let { sessionModelOverrides[it] ?: sessionConfigSnapshots[it]?.modelId } ?: globalSelectedModelId

    private fun activeProviderForSession(sessionId: String?): String? =
        sessionId?.let { sessionProviderOverrides[it] ?: sessionConfigSnapshots[it]?.providerId } ?: globalActiveProviderId

    /**
     * A-13: Verifique que esta ronda de generación de herramientas aún esté"Generación actualmente activa"(Tokens generacionales sin cambios).
     *
     * Revisión de corrección (2.0 A-04): Implementación original"Token sin cambios"con"La sesión de destino sigue siendo la sesión de visualización."acoplados juntos,
     * El usuario cambia de sesión(No recién generado)El token permanece sin cambios pero el foco de visualización ha desaparecido.,exec* Dejar directamente los primeros resultados como completados.
     * Los medios no se muestran ni se almacenan. Ahora dividido en dos métodos.:
     * - [isCurrentToolGeneration]: Solo verificar generación(Una nueva ronda de generación invalidará los resultados anteriores.);
     * - [isToolSessionDisplayed]: Verificar solo el enfoque de visualización(Decida si los medios se fusionarán a través de la interfaz de usuario o se colocarán directamente en la biblioteca.).
     *
     * @param capturedToken exec* Capturado en la entrada [toolGenerationToken]
     */
    private fun isCurrentToolGeneration(capturedToken: Long): Boolean = capturedToken == toolGenerationToken

    /** Revisión de corrección (2.0 A-04): Si la sesión que se muestra actualmente sigue siendo la sesión de destino generada por la herramienta. */
    private fun isToolSessionDisplayed(): Boolean {
        val active = activeToolSessionId ?: return false
        val displayed = if (_state.value.isAgentMode) _state.value.agentSessionId else _state.value.currentSessionId
        return active == displayed
    }

    /**
     * B-24: solo en"Sigue siendo la última generación."Borre el estado de la transmisión.
     *
     * explosión rápida/Después de cambiar de sesión,El fin de la vieja generación(finalizeResponse/camino equivocado)No se permite borrar
     * recién generado isStreaming/isWaitingFirstToken — De lo contrario, la interfaz de usuario muestra"Detenido"Pero el trasfondo todavía se genera.
     *
     * @return Si se realiza la limpieza
     */
    private fun clearStreamingStateIfLatest(
        state: StreamRunState,
        // F-10: etapa final al final(Completado normalmente=FINISHED/error=FAILED/interrumpir=INTERRUPTED)
        finalPhase: ChatStreamPhase = ChatStreamPhase.IDLE,
    ): Boolean = generationController.clearStreamingStateIfLatest(state, finalPhase)

    /** Envíe un mensaje optimista al usuario/asistente para eliminar la página actual cuando la solicitud falle. */
    // v5: Cola de envío de mensajes + deduplicación de recuperación de bandeja de salida(Recibido ChatGenerationState)
    private val sendChannel get() = generationState.sendChannel
    private val outboxRecoveryQueuedIds get() = generationState.outboxRecoveryQueuedIds

    // Phase 8.5: Instancia Json reutilizable(Evite crear otros nuevos cada vez que analice).
    private val idListJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    // v5: Actualización optimista — Los mensajes de usuario se muestran inmediatamente a UI,No espere a que se escriba la base de datos(mudarse a ChatGenerationController)
    private fun enqueueSend(text: String, images: List<String>, sessionId: String) {
        generationController.enqueueSend(text, images, sessionId)
        // El contenido que se ha enviado ya no es un borrador. Borra el borrador de la conversación.
        // Esto evita que veas el texto que ya has enviado la próxima vez que vuelvas a cambiar.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { sessionRepository.saveInputDraft(sessionId, "") }
                .onFailure { Logger.w("ChatVM", "No se pudo borrar el borrador de entrada: ${it.message}") }
        }
        // v2.6.7: Graba una interacción real (intimidad + hora de la última interacción).
        // En el pasado, sólo los teléfonos celulares pequeños llamaban a recordInteraction a través de mensajes privados activos. Los usuarios
        // Por mucho que hablemos, la relación no avanzará. De hecho, el sistema de relaciones sólo está vivo a medias.
        val assistantId = snapshot.currentAssistant?.id
        if (relationshipRepository != null && !assistantId.isNullOrBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { relationshipRepository.recordInteraction(assistantId) }
                    .onFailure { Logger.w("ChatVM", "Error en la interacción de la relación de registro: ${it.message}") }
            }
        }
    }

    // v1.105 dividir: ChatStateAccessor darse cuenta — Para que cada Coordinador lea y escriba state
    override val snapshot: ChatUiState get() = _state.value

    override fun update(transform: (ChatUiState) -> ChatUiState) = _state.update(transform)

    override val messagesSnapshot: List<UIMessage> get() = _messages.value

    override fun updateMessages(transform: (List<UIMessage>) -> List<UIMessage>) = _messages.update(transform)

    override val coroutineScope: kotlinx.coroutines.CoroutineScope get() = viewModelScope

    // v1.105 División de la fase 1: Áreas de responsabilidad Coordinator(Ninguno state,mantener la dependencia,Leer y escribir a través del descriptor de acceso)
    private val imageGenCoordinator =
        ImageGenCoordinator(
            accessor = this,
            imageService = imageService,
            settings = settings,
            sessionRepository = sessionRepository,
            ocrManager = ocrManager,
            chatService = chatService,
            appContext = appContext,
        )
    private val exportCoordinator =
        ChatExportCoordinator(
            accessor = this,
            settings = settings,
            sessionRepository = sessionRepository,
            auditLogger = auditLogger,
        )
    private val audioCoordinator =
        ChatAudioCoordinator(
            accessor = this,
            ttsManager = ttsManager,
            settings = settings,
            context = appContext,
        )

    // v1.x: entrada/borrador/Cola para enviar/Entrar en la historia Controller
    private val inputController =
        ChatInputController(
            accessor = this,
            appContext = appContext,
            onEnqueueSend = { text, images, sid -> enqueueSend(text, images, sid) },
        )

    // v1.x: Cargando mensajes Controller(paginación inicial/cargar más/planificar la recuperación)
    private val messageController =
        ChatMessageController(
            accessor = this,
            sessionRepository = sessionRepository,
            skillExecutor = skillExecutor,
            treeState = _conversationTree,
            treeSnapshotStore = treeSnapshotStore,
            messagePageSize = MESSAGE_PAGE_SIZE,
        )
            .apply {
                // v2.6.7 Solución (no se puede volver a la posición original después de eliminar el fondo): Establezca la posición de desplazamiento con la identificación de la sesión,
                // CherSessionController volverá a leer esta ubicación cuando el proceso finalice y se reconstruya.
                scrollPersister =
                    ChatMessageController.ScrollPositionPersister { sid, index, offset ->
                        resultOf { settings.saveChatScrollPosition(sid, index, offset) }
                            .onError { msg, _ -> Logger.w("ChatVM", "saveChatScrollPosition falló: $msg") }
                    }
            }

    /**
     * Cambie la protección del número de serie.
     *
     * Sólo se organizan los interruptores iniciados continuamente dentro de esta interfaz; escritos como campos de instancia en lugar de singletons globales.
     * De lo contrario, el cambio tardío de una instancia de interfaz considerará que el cambio de otra instancia ha caducado.
     */
    private val switchGuard = io.zer0.muse.ui.chat.SessionSwitchGuard()

    // v1.x: sesión CRUD Controller(rename/archive/delete/ignore-memory/fork)
    private val sessionController =
        ChatSessionController(
            accessor = this,
            sessionRepository = sessionRepository,
            sessionMemoryCache = sessionMemoryCache,
            browserManagerRegistry = browserManagerRegistry,
            bridge =
            object : SessionFlowBridge {
                override suspend fun refreshContext() = refreshContextInfo()

                override fun detachStreaming() = this@ChatViewModel.detachStreaming()

                override fun onForkError(throwable: Throwable) =
                    reportError(appContext.getString(R.string.err_chat_fork_failed, throwable.message ?: ""))

                // P0-5: Eliminar/Detener la generación en curso + supresión de escritura al archivar sesiones(Controlador de generación de delegados,protegerse contra"resurrección")
                override fun stopGenerationForSession(sessionId: String?) = generationController.stopGenerationForSession(sessionId)

                override fun suppressSessionWrites(sessionId: String?) = generationController.suppressSessionWrites(sessionId)
            },
            sessionDeps =
            SessionDeps(
                stateStore = stateStore,
                settings = settings,
                assistantRepository = assistantRepository,
                sessionPermissionStore = sessionPermissionStore,
                sessionManager = sessionManager,
                appContext = appContext,
                onStopTts = { stopTts() },
                onDisposeAsr = { disposeAsr() },
                onNotifySessionEnd = { notifySessionEndForCurrent() },
                currentSessionIdForApproval = { currentSessionIdForApproval() },
                globalActiveProviderId = { globalActiveProviderId },
                globalSelectedModelId = { globalSelectedModelId },
                onSend = { send() },
                messageController = messageController,
                chatGenerationManager = chatGenerationManager,
                onClearDelegation = {
                    delegationChainTracker.clear()
                    delegationPauseManager.clearAll()
                },
                onCancelPendingApprovals = { sid -> cancelPendingApprovalsForSession(sid) },
                treeSnapshotStore = treeSnapshotStore,
                restorePendingApprovalsForSession = { sid -> restorePendingApprovalsForSession(sid) },
                activeProviderForSession = { sid -> activeProviderForSession(sid) },
                selectedModelForSession = { sid -> selectedModelForSession(sid) },
                onSessionSwitched = { sid ->
                    lastSessionSwitchAt = System.currentTimeMillis()
                    lastSessionSwitchIdValue = sid
                    // v2.5.3 (P3-2): Precargue la instantánea de configuración al cambiar de sesión, lo que permitirá que las sesiones antiguas vuelvan a“Configuración en ese momento”.
                    viewModelScope.launch {
                        val snapshot = sessionRepository.getConfigSnapshot(sid)
                        cacheSessionConfigSnapshot(sid, snapshot)
                    }
                },
                requeueOutboxForSession = { sid -> generationController.requeueOutboxForSession(sid) },
            ),
            switchGuard = switchGuard,
        )

    // v1.x: Configuración del nivel de chat Controller(barra lateral/Dibujo/modelo de herramienta/toast)
    private val settingsController =
        ChatSettingsController(
            accessor = this,
            settings = settings,
            appContext = appContext,
            selectionStore =
            object : SessionModelSelectionStore {
                override fun setProviderOverride(sessionId: String, providerId: String?) {
                    sessionProviderOverrides =
                        if (providerId == null) {
                            sessionProviderOverrides - sessionId
                        } else {
                            sessionProviderOverrides + (sessionId to providerId)
                        }
                }

                override fun setModelOverride(sessionId: String, modelId: String?) {
                    sessionModelOverrides =
                        if (modelId == null) {
                            sessionModelOverrides - sessionId
                        } else {
                            sessionModelOverrides + (sessionId to modelId)
                        }
                }
            },
        )

    // Almacenamiento de resultados de devolución de llamada de aprobación de herramienta(toolCallId → Deferred result),Compartido con herramientaControlador/Ciclo de vida de aprobación
    private val toolApprovalResults =
        java.util.concurrent.ConcurrentHashMap<
            String,
            kotlinx.coroutines.CompletableDeferred<ToolApprovalState>,
            >()

    // v1.x: Aprobación de herramienta/Permisos Controller(Imagen de referencia/siempre permitido/Publicado para esta ejecución/modo de permiso de sesión)
    private val toolController =
        ChatToolController(
            accessor = this,
            sessionPermissionStore = sessionPermissionStore,
            approvalResults = toolApprovalResults,
            toolConfigStore = toolConfigStore,
        )

    // v1.x: medios de comunicación/producto Controller(artifact seleccionado/cerrar/observar)
    // v1.x: system prompt / Contenedor de caché de token de contexto(buildSystemPromptForStream Migrar hacia adelante)
    private val systemPromptCache = SystemPromptCache()
    private val mediaController =
        ChatMediaController(
            accessor = this,
            artifactRepository = artifactRepository,
            voiceState = voiceState,
            stateStore = stateStore,
            audioCoordinator = audioCoordinator,
            ttsManager = ttsManager,
            appContext = appContext,
            onError = { type, msg, rec -> addError(type, msg, rec) },
            onSend = { send() },
            onStop = { stop() },
            onUpdateInput = { text -> updateInput(text) },
            settings = settings,
        )
    private val documentCoordinator =
        ChatDocumentCoordinator(
            accessor = this,
            documentParser = documentParser,
            settings = settings,
        )

    // v1.105 División de la fase 2: Varios Coordinator(Carpeta / Buscar / Favoritos / Administrar página CRUD)
    private val miscCoordinator =
        ChatMiscCoordinator(
            accessor = this,
            sessionRepository = sessionRepository,
            folderRepository = folderRepository,
            lorebookRepository = lorebookRepository,
            promptInjectionRepository = promptInjectionRepository,
            quickMessageRepository = quickMessageRepository,
            assistantRepository = assistantRepository,
            appContext = appContext,
        )

    // v0.45: Extraer como campo,manualCompress Llame a transform directamente para compresión manual
    // v1.0.17: inyectar ConversationCompressor,Habilitar paralelismo fragmentado + modelo económico independiente
    private val conversationCompressor = io.zer0.muse.transformer.ConversationCompressor(chatService, settings)
    private val contextCompressTransformer = ContextCompressTransformer(chatService, conversationCompressor)

    private val transformerPipeline: TransformerPipeline by lazy { buildTransformerPipeline() }

    // v1.105 División de la fase 3: Asistente de transmisión Coordinator(detach / updateAssistant / Persistencia/extracción de etiquetas)
    private val streamCoordinator =
        ChatStreamCoordinator(
            accessor = this,
            sessionRepository = sessionRepository,
            memoryTicker = memoryTicker,
            settings = settings,
            appContext = appContext,
            notificationManager = notificationManager,
            assistantRepository = assistantRepository,
            visionBridge = visionBridge,
            toolRegistry = toolRegistry,
            skillRepository = skillRepository,
            idListJson = idListJson,
            lorebookRepository = lorebookRepository,
            promptInjectionRepository = promptInjectionRepository,
            transformerPipeline = transformerPipeline,
            checkpointReader = checkpointReader,
            hookRegistry = hookRegistry,
            toolConfigStore = toolConfigStore,
        )

    // v1.x: Control de construcción Controller(stop/launchStream/send/continue/retry)
    private val generationController =
        ChatGenerationController(
            deps =
            GenerationDeps(
                accessor = this,
                stateStore = stateStore,
                generationState = generationState,
                settings = settings,
                sessionRepository = sessionRepository,
                appContext = appContext,
                activityProfile = activityProfile,
                auditLogger = auditLogger,
                idListJson = idListJson,
                messageController = messageController,
                addError = { type, msg, recoverable -> addError(type, msg, recoverable) },
                generateImage = { prompt, sid -> generateImage(prompt, sid) },
                sessionMemoryCache = sessionMemoryCache,
                systemPromptCache = systemPromptCache,
                toolRegistry = toolRegistry,
                systemPromptAssembler = systemPromptAssembler,
                assistantRepository = assistantRepository,
                ragService = ragService,
                conversationService = conversationService,
                streamCoordinator = streamCoordinator,
                mcpRegistry = mcpRegistry,
                milestoneChecker = milestoneChecker,
                runToolLoop = { state -> runToolLoop(state) },
                persistInterruptedAssistant = { sid, msg, aid, dur -> persistInterruptedAssistant(sid, msg, aid, dur) },
                classifyErrorType = { msg, t -> classifyErrorType(msg, t) },
                transformerPipeline = transformerPipeline,
                memoryTicker = memoryTicker,
                refreshContextInfo = { refreshContextInfo() },
                triggerAutoCompress = { sid -> triggerAutoCompress(sid) },
                maybeAutoRoute = { text, assistantMessageId, sid ->
                    maybeAutoRoute(text, assistantMessageId, sid)
                },
                // v2.6.9: Bucle cerrado de retroalimentación de mensaje activo —— El usuario le dice al planificador tan pronto como abre la boca,
                // Se utiliza para determinar "si esta respuesta es una respuesta a un mensaje activo" y para reponer la cuota.
                // Utilice Koin para analizar bajo demanda en lugar de construir parámetros: evite la referencia mutua entre ChatViewModel y el programador.
                // También evite no construir cuando no esté ensamblado.
                onUserSpoke = { sid ->
                    viewModelScope.launch {
                        runCatching {
                            org.koin.core.context.GlobalContext.get()
                                .get<io.zer0.muse.proactive.ProactiveScheduler>()
                                .onUserSpoke(sid)
                        }.onFailure { e ->
                            io.zer0.common.Logger.w("ChatViewModel", "Falló la grabación de comentarios de mensajes activos: ${e.message}")
                        }
                    }
                },
            ),
            accessor = this,
            chatGenerationManager = chatGenerationManager,
            sessionManager = sessionManager,
            settings = settings,
            notificationManager = notificationManager,
            onCancelAncillaryJobs = {
                imageJob?.cancel()
                imageJob = null
                translateJob?.cancel()
                translateJob = null
            },
            onCancelPendingApprovals = { sid -> cancelPendingApprovalsForSession(sid) },
            executionRegistry = executionRegistry,
        )

    // v1.134 P1-5: tarjeta de tarea Coordinator(etapa de la tarjeta de tarea/pasos/Expandir/Inténtalo de nuevo/Juicio del resultado de la herramienta)
    private val taskCardCoordinator =
        ChatTaskCardCoordinator(
            accessor = this,
            toolRegistry = toolRegistry,
        )
    private val routeGuard = io.zer0.muse.tools.ToolRouteExecutionGuard(toolRegistry)

    /**
     * v2.x Refactor S1: cuerpo del bucle de herramienta(runToolLoop + Incorporado ToolLoopHost,Línea original 1392).
     * Esta clase en sí misma actúa como [ChatToolLoopHostBridge] Implementar inyección —— extracción pura,El comportamiento se mantiene sin cambios.
     */
    /**
     * v2.x Refactor S1: implementación del puente de host del cuerpo del bucle de herramientas.
     *
     * runner Dentro del paquete ui.chat,No se pueden leer miembros privados de esta clase,Utilice objetos anónimos aquí
     * Los miembros a los que hace referencia directamente el runToolLoop original se le reenvían. —— todos**Reenviar directamente**,Sin lógica.
     */
    @Suppress("TooManyFunctions", "LongParameterList")
    private val toolLoopHostBridge = object : io.zer0.muse.ui.chat.ChatToolLoopHostBridge {
        override val stateStore get() = this@ChatViewModel.stateStore
        override val generationState get() = this@ChatViewModel.generationState
        override val appContext get() = this@ChatViewModel.appContext
        override val settings get() = this@ChatViewModel.settings
        override val sessionRepository get() = this@ChatViewModel.sessionRepository
        override val chatService get() = this@ChatViewModel.chatService
        // Recuperación de desbordamiento de contexto: comprima una ronda del historial y luego reenvíe (consulte la rama de recuperación de ChatToolLoopRunner para obtener más detalles)
        override val contextCompressTransformer get() = this@ChatViewModel.contextCompressTransformer
        override val sessionManager get() = this@ChatViewModel.sessionManager
        override val notificationManager get() = this@ChatViewModel.notificationManager
        override val conversationService get() = this@ChatViewModel.conversationService
        override val messageCommit get() = this@ChatViewModel.messageCommit
        override val artifactRepository get() = this@ChatViewModel.artifactRepository
        override val chatGenerationManager get() = this@ChatViewModel.chatGenerationManager
        override val sessionPermissionStore get() = this@ChatViewModel.sessionPermissionStore
        override val toolOrchestrator get() = this@ChatViewModel.toolOrchestrator
        override val generationController get() = this@ChatViewModel.generationController
        override val taskCardCoordinator get() = this@ChatViewModel.taskCardCoordinator
        override val messageController get() = this@ChatViewModel.messageController
        override val coroutineScope get() = this@ChatViewModel.viewModelScope
        override val accessor: io.zer0.muse.ui.chat.ChatStateAccessor get() = this@ChatViewModel

        override fun addError(type: ChatErrorType, message: String, isRecoverable: Boolean) =
            this@ChatViewModel.addError(type, message, isRecoverable)
        override fun classifyErrorType(message: String, throwable: Throwable?) = this@ChatViewModel.classifyErrorType(message, throwable)
        override fun clearStreamingStateIfLatest(state: StreamRunState, finalPhase: ChatStreamPhase?) =
            this@ChatViewModel.clearStreamingStateIfLatest(state, finalPhase ?: ChatStreamPhase.IDLE)
        override fun collectStickyToolNames(history: List<UIMessage>) = this@ChatViewModel.collectStickyToolNames(history)
        override fun currentSessionIdForApproval() = this@ChatViewModel.currentSessionIdForApproval()
        override fun nextToolGenerationToken() = this@ChatViewModel.nextToolGenerationToken()
        override fun streamFlushIntervalMs(builderLength: Int) = ChatViewModel.streamFlushIntervalMs(builderLength)
        override fun canUseToolModelForRound(history: List<UIMessage>, toolModel: Model) =
            io.zer0.muse.ui.canUseToolModelForRound(history, toolModel)
        override fun resumeOverlapToDrop(duplicateRemaining: String, delta: String) =
            io.zer0.muse.ui.resumeOverlapToDrop(duplicateRemaining, delta)
        override fun shouldReplaceOnResumeRewrite(duplicateTotal: Int, consumedChars: Int, minOverlap: Int) =
            io.zer0.muse.ui.shouldReplaceOnResumeRewrite(duplicateTotal, consumedChars, minOverlap)
        override fun shouldReplaceOnResumeSupersede(original: String?, attemptText: String?, currentContent: String) =
            io.zer0.muse.ui.shouldReplaceOnResumeSupersede(original, attemptText, currentContent)
        override suspend fun persistCheckpointAndReleaseOutbox(
            state: StreamRunState,
            sessionId: String,
            userMessageId: String,
            assistantMessageId: String,
            content: String,
            createdAt: Long,
        ) = this@ChatViewModel.persistCheckpointAndReleaseOutbox(
            state,
            sessionId,
            userMessageId,
            assistantMessageId,
            content,
            createdAt,
        )
        override suspend fun persistCurrentAssistant(sessionId: String, assistantId: Uuid, msg: UIMessage?) =
            this@ChatViewModel.persistCurrentAssistant(sessionId, assistantId, msg)
        override suspend fun persistToolMessageMedia(sessionId: String?, assistantId: Uuid) =
            this@ChatViewModel.persistToolMessageMedia(sessionId, assistantId)
        override suspend fun recordConversationShadow(event: ConversationEventDraft) = this@ChatViewModel.recordConversationShadow(event)
        override suspend fun requestToolApprovalForSession(
            sessionId: String,
            toolName: String,
            toolCallId: String,
            argsPreview: String,
            args: Map<String, Any?>,
        ) = this@ChatViewModel.requestToolApprovalForSession(
            sessionId,
            toolName,
            toolCallId,
            argsPreview,
            args,
        )
        override fun updateAssistant(
            id: Uuid,
            content: String,
            reasoning: String?,
            imageBase64List: List<String>?,
            imageUrls: List<String>?,
            isStreaming: Boolean,
        ) = this@ChatViewModel.updateAssistant(
            id = id,
            content = content,
            reasoning = reasoning,
            imageBase64List = imageBase64List,
            imageUrls = imageUrls,
            isStreaming = isStreaming,
        )
        override suspend fun updateAssistantWithVisualTransform(
            assistantId: Uuid,
            content: String,
            reasoning: String?,
            isStreaming: Boolean,
        ) = this@ChatViewModel.updateAssistantWithVisualTransform(assistantId, content, reasoning, isStreaming)
        override suspend fun updateContextTokenCount() = this@ChatViewModel.updateContextTokenCount()
        override suspend fun waitForNetworkRecovery(timeoutMs: Long) = this@ChatViewModel.waitForNetworkRecovery(timeoutMs)
    }

    private val toolLoopRunner = io.zer0.muse.ui.chat.ChatToolLoopRunner(host = toolLoopHostBridge)

    /**
     * v2.x Refactorización S2: implementación de puente de host para orquestación de compresión —— Reenviar los miembros privados de la VM a
     * ChatCompressionCoordinator,Reenviar todo directamente,Sin lógica.
     */
    @Suppress("TooManyFunctions")
    private val compressionHostBridge = object : io.zer0.muse.ui.chat.ChatCompressionHostBridge {
        override val stateStore get() = this@ChatViewModel.stateStore
        override val appContext get() = this@ChatViewModel.appContext
        override val settings get() = this@ChatViewModel.settings
        override val sessionRepository get() = this@ChatViewModel.sessionRepository
        override val checkpointReader get() = this@ChatViewModel.checkpointReader
        override val contextCompressTransformer get() = this@ChatViewModel.contextCompressTransformer
        override val memoryTicker get() = this@ChatViewModel.memoryTicker
        override val coroutineScope get() = this@ChatViewModel.viewModelScope
        override var checkpointCoveredSessionId: String?
            get() = this@ChatViewModel.checkpointCoveredSessionId
            set(value) {
                this@ChatViewModel.checkpointCoveredSessionId = value
            }
        override var checkpointCoveredIds: Set<String>
            get() = this@ChatViewModel.checkpointCoveredIds
            set(value) {
                this@ChatViewModel.checkpointCoveredIds = value
            }

        override fun displayedSessionId() = this@ChatViewModel.displayedSessionId()
        override fun reportError(message: String) = this@ChatViewModel.reportError(message)
        override fun shouldAutoCompress(currentTokens: Int, maxTokens: Int) = ChatViewModel.shouldAutoCompress(currentTokens, maxTokens)
        override suspend fun updateContextTokenCount() = this@ChatViewModel.updateContextTokenCount()
        override suspend fun refreshCheckpointCoveredIds(sessionId: String) = this@ChatViewModel.refreshCheckpointCoveredIds(sessionId)
        override suspend fun refreshContextInfo() = this@ChatViewModel.refreshContextInfo()
        override fun newlyCoveredCount(messages: List<UIMessage>, coveredIds: Set<String>, previousBoundaryId: String?) =
            ChatViewModel.newlyCoveredCountStatic(messages, coveredIds, previousBoundaryId)
    }

    private val compressionCoordinator = io.zer0.muse.ui.chat.ChatCompressionCoordinator(host = compressionHostBridge)
    /**
     * v2.x Refactorización S4: Implementación de Host Bridge para la aprobación de herramientas —— Reenviar miembros privados de VM,Sin lógica.
     */
    /**
     * v2.x Refactor S4/S5: implementación del puente de host para la aprobación y recuperación de herramientas —— Reenviar miembros privados de VM,Sin lógica.
     */
    private val toolApprovalHostBridge = object : io.zer0.muse.ui.chat.ChatToolApprovalHostBridge {
        override val stateStore get() = this@ChatViewModel.stateStore
        override val stateAccessor: io.zer0.muse.ui.chat.ChatStateAccessor get() = this@ChatViewModel
        override val appContext get() = this@ChatViewModel.appContext
        override val settings get() = this@ChatViewModel.settings
        override val sessionPermissionStore get() = this@ChatViewModel.sessionPermissionStore
        override val toolConfigStore get() = this@ChatViewModel.toolConfigStore
        override val toolRegistry get() = this@ChatViewModel.toolRegistry
        override val notificationManager get() = this@ChatViewModel.notificationManager
        override val sessionManager get() = this@ChatViewModel.sessionManager
        override val skillRepository get() = this@ChatViewModel.skillRepository
        override val idListJson get() = this@ChatViewModel.idListJson
        override val coroutineScope get() = this@ChatViewModel.viewModelScope
        override val toolApprovalResults get() = this@ChatViewModel.toolApprovalResults
        override val toolApprovalSessions get() = this@ChatViewModel.toolApprovalSessions
        override val pendingToolApprovalRecords get() = this@ChatViewModel.pendingToolApprovalRecords
        override val approvalTimeoutPaused get() = this@ChatViewModel.approvalTimeoutPaused
        override val appInForeground get() = this@ChatViewModel.appForegroundFlow.value
        override fun addError(type: io.zer0.muse.ui.ChatErrorType, message: String, isRecoverable: Boolean) =
            this@ChatViewModel.addError(type, message, isRecoverable)
        override fun isToolResultSuccess(result: String) = this@ChatViewModel.isToolResultSuccess(result)
        override fun launchStream(assistantId: kotlin.uuid.Uuid, sessionId: String, isNewBranch: Boolean) =
            this@ChatViewModel.launchStream(assistantId, sessionId, isNewBranch)
        override fun currentSessionIdForApproval() = this@ChatViewModel.currentSessionIdForApproval()
        override val skillExecutor get() = this@ChatViewModel.skillExecutor
        override val routeGuard get() = this@ChatViewModel.routeGuard
        override val sessionRepository get() = this@ChatViewModel.sessionRepository
        override suspend fun recheckApprovalForResume(chatId: String, pending: PendingToolCallStore.PendingToolCall) =
            this@ChatViewModel.recheckApprovalForResume(chatId, pending)
    }

    private val toolApprovalCoordinator = io.zer0.muse.ui.chat.ChatToolApprovalCoordinator(host = toolApprovalHostBridge)

    private val toolResumeCoordinator = io.zer0.muse.ui.chat.ChatToolResumeCoordinator(host = toolApprovalHostBridge)

    /**
     * Transformer Tubería. El resumen de la sesión se agrega después de la compresión del contexto.,No se comprimirá dos veces ni se escribirá en mensajes históricos.
     * - TemplateTransformer Asumir el control de Assistant.messageTemplate {{var}} reemplazar
     * - Assistant Configuración aprobada [TransformContext.extras] inyectar,Cada Transformer lo lee solo
     * - Phase 8.5: LorebookTransformer(activador de palabra clave) + PromptInjectionTransformer(interruptor de modo)
     */
    private fun buildTransformerPipeline(): TransformerPipeline {
        val builder = TransformerPipeline.Builder()
            .add(LorebookTransformer(lorebookRepository))
            .add(PromptInjectionTransformer())
            .add(TemplateTransformer(appContext))
            .add(io.zer0.muse.transformer.RegexMessageTransformer())
            .add(ThinkTagTransformer())
            .add(io.zer0.muse.transformer.MoodTagTransformer())
            .add(contextCompressTransformer)
        conversationSummaryManager?.let { manager ->
            builder.add(ConversationMemoryInjectionTransformer(manager))
        }
        return builder.build()
    }

    /** Phase 8.5 Reparación: Token de inicialización de la primera sesión,Evite que la condición de carrera de observeSessions cree sesiones repetidamente. */
    @Volatile
    private var initializing: Boolean = false

    /** Tarea de generación de imágenes actual(P5-G). */
    private var imageJob: Job? = null

    /** Tareas de traducción actuales(P5-F). */
    private var translateJob: Job? = null

    init {
        // El contexto está ocupado para la autoinspección get_token_usage: la capa de herramientas no puede ver ChatUiState.
        // Aquí, la entrada leída está vinculada al estado en tiempo real de este ViewModel (un singleton a nivel de proceso, solo vincúlelo una vez).
        io.zer0.muse.ui.chat.ContextOccupancyProbe.bind {
            val snapshot = _state.value
            snapshot.contextTokenCount to snapshot.contextMaxTokens
        }
        // B2-04: Cuando un subagente encuentra una herramienta que requiere aprobación,Reutilizar tarjetas de aprobación de la sesión principal
        toolApprovalRouter.delegate = this
        // Escuche los cambios de configuración,Actualizar el indicador isConfigured
        // v1.22: No más listas de modelos codificadas localmente,isConfigured Esto es cierto siempre que el Proveedor esté activado.
        // Si el Proveedor está activado y no hay modelo,Activa automáticamente extracciones desde modelos/upstream.
        // Cuando realmente faltan las credenciales, chatService enviará un error.,Avisar al usuario a través de state.error.
        viewModelScope.launch {
            settings.providerConfigFlow.collect { config ->
                _state.update {
                    it.copy(
                        isConfigured = config != null,
                    )
                }
                // Tirar automáticamente:Active el proveedor con apiKey configurado pero los modelos están vacíos
                if (config != null && config.models.isEmpty() && config.apiKey.isNotBlank()) {
                    refreshModels(config.id)
                }
            }
        }
        // Etapa 5: Observar a todos los proveedores/Activar proveedores/Seleccionar modelos(Para panel de conmutación inferior)
        viewModelScope.launch {
            settings.providersFlow.collect { providers ->
                _state.update { it.copy(providers = providers) }
            }
        }
        viewModelScope.launch {
            settings.activeProviderIdFlow.collect { id ->
                globalActiveProviderId = id
                _state.update { state ->
                    state.copy(activeProviderId = activeProviderForSession(displayedSessionId(state)))
                }
            }
        }
        viewModelScope.launch {
            settings.selectedModelIdFlow.collect { modelId ->
                globalSelectedModelId = modelId
                _state.update { state ->
                    state.copy(selectedModelId = selectedModelForSession(displayedSessionId(state)))
                }
            }
        }
        viewModelScope.launch {
            settings.sessionModelOverridesFlow.collect { overrides ->
                sessionModelOverrides = overrides
                _state.update { state ->
                    state.copy(selectedModelId = selectedModelForSession(displayedSessionId(state)))
                }
            }
        }
        viewModelScope.launch {
            settings.sessionProviderOverridesFlow.collect { overrides ->
                sessionProviderOverrides = overrides
                _state.update { state ->
                    state.copy(activeProviderId = activeProviderForSession(displayedSessionId(state)))
                }
            }
        }
        // v1.60-A: modelo de herramienta de recolección id(Uso de la ronda de llamadas de herramientas)
        viewModelScope.launch {
            settings.toolModelIdFlow.collect { modelId ->
                _state.update { it.copy(toolModelId = modelId) }
            }
        }
        // v1.0.47: Token Cambio de estimación
        viewModelScope.launch {
            settings.tokenEstimateEnabledFlow.collect { enabled ->
                _state.update { it.copy(tokenEstimateEnabled = enabled) }
            }
        }
        // v1.0.47 P5-2: Pegar texto largo en archivo cambiar + umbral
        viewModelScope.launch {
            settings.pasteAsFileEnabledFlow.collect { enabled ->
                _state.update { it.copy(pasteAsFileEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settings.pasteAsFileThresholdFlow.collect { threshold ->
                _state.update { it.copy(pasteAsFileThreshold = threshold) }
            }
        }
        // Ver lista de sesiones(Para barra lateral)
        viewModelScope.launch {
            try {
                sessionRepository.observeSessions().collect { sessions ->
                    _state.update { it.copy(sessions = sessions, isSessionsLoading = false, sessionsError = null) }
                    // primera carga:Si no hay sesión, crea una nueva.,Si lo hay, cambie al más reciente.
                    // Phase 8.5 Reparación: Utilice el indicador de inicialización para evitar que observeSessions createNewSession
                    // Configure de forma asincrónica currentSessionId antes de volver a emitir para crear repetidamente varias sesiones
                    if (_state.value.currentSessionId == null && !initializing) {
                        initializing = true
                        // R-UI-02: La recuperación de procesos prioriza la restauración de sesiones que el usuario estaba viendo mientras estaba fuera,en lugar de la sesión activa más reciente.
                        val viewedId = resultOf { settings.getViewedSessionId() }.getOrNull()
                        val target = sessions.firstOrNull { it.id == viewedId } ?: sessions.firstOrNull()
                        if (target != null) {
                            switchSession(target.id)
                        } else {
                            createNewSession()
                        }
                    }
                }
            } catch (t: Throwable) {
                // v1.x: La cancelación de la rutina debe volver a lanzarse,no puede ser considerado como"Error al cargar"mostrar(De lo contrario mostrar
                // "Solicitud fallida: Job was cancelled"Y el error siempre se bloqueará después de que muera la rutina.)
                if (t is kotlinx.coroutines.CancellationException) throw t
                Logger.e("ChatVM", "observeSessions failed", t)
                _state.update {
                    it.copy(
                        isSessionsLoading = false,
                        sessionsError =
                        appContext.getString(
                            R.string.err_chat_request_failed,
                            t.message ?: appContext.getString(R.string.err_chat_unknown),
                        ),
                    )
                }
            }
        }
        // v0.45: Observar conversaciones archivadas(Uso de Archive FilterCard)
        loadArchivedSessions()
        // Phase 8.2: Mira la lista de asistentes(Para selector de barra lateral)
        viewModelScope.launch {
            assistantRepository.observeAll.collect { list ->
                _state.update { state ->
                    val currentId = state.currentAssistant?.id
                    val refreshedCurrent =
                        currentId?.let { id ->
                            list.firstOrNull { assistant -> assistant.id == id }
                        } ?: state.currentAssistant
                    state.copy(
                        assistants = list,
                        isAssistantsLoading = false,
                        // Escriba Room directamente después de la página de detalles del asistente,Actualizar sincrónicamente la instantánea del chat actual;
                        // De lo contrario, herramienta,Skill,KB, modelo y otros cambios solo tendrán efecto después de cambiar de sesión.
                        currentAssistant = refreshedCurrent,
                    )
                }
            }
        }
        // Phase 8.3: Observe los mensajes favoritos en todas las sesiones(Para panel de colección)
        loadFavorites()
        // v1.104 U7: Observe las etiquetas del grupo de colección con nombre(Para la representación de FilterChip en la parte superior de la pantalla Favoritos)
        viewModelScope.launch {
            sessionRepository.observeAllFavoriteTags().collect { tags ->
                _state.update { it.copy(favoriteTags = tags) }
            }
        }
        // Phase 8.4: Observar la configuración de búsqueda web(sincronizar webSearchEnabled / webSearchConfig)
        viewModelScope.launch {
            settings.webSearchConfigFlow.collect { cfg ->
                _state.update {
                    it.copy(
                        webSearchConfig = cfg,
                        // El modo local lo maneja la herramienta web_search; el modo nativo lo maneja la herramienta nativa del proveedor y los dos son mutuamente excluyentes.
                        webSearchEnabled = cfg.enabled && cfg.mode != io.zer0.muse.web.WebSearchMode.OFF,
                    )
                }
            }
        }
        // v0.34: Observe la configuración de parámetros predeterminados de generación de imágenes
        viewModelScope.launch {
            settings.imageGenConfigFlow.collect { cfg ->
                _state.update {
                    it.copy(
                        imageGenParams =
                        io.zer0.ai.image.ImageGenParams(
                            model = cfg.modelId,
                            size = cfg.size,
                            quality = cfg.quality,
                            style = cfg.style,
                            responseFormat = cfg.responseFormat,
                            n = cfg.n,
                        ),
                    )
                }
            }
        }
        // Phase 8.5: Observe los mensajes de acceso directo vinculados al Asistente actual
        // Utilice flatMapLatest para cambiar automáticamente al flujo de mensajes de acceso directo del nuevo Asistente cuando cambie el Asistente actual.
        viewModelScope.launch {
            _state
                .map { it.currentAssistant }
                .distinctUntilChanged()
                .flatMapLatest { assistant ->
                    val assistantId = assistant?.id ?: "default"
                    val boundIds = assistant?.let { parseIdList(it.quickMessageIdsJson) }.orEmpty()
                    quickMessageRepository.observeForAssistant(assistantId, boundIds)
                }
                .collect { list ->
                    _state.update { it.copy(quickMessages = list) }
                }
        }
        // v1.97 (P1-1): Lorebook / PromptInjection / allQuickMessages Cambiar a carga diferida
        // (refreshLorebooks / refreshPromptInjections / refreshAllQuickMessages),
        // por LorebookScreen / PromptInjectionScreen / QuickMessageScreen Se activa al entrar.
        // Estos tres elementos solo se utilizan en la página de administración.,No leído en el proceso de chat principal.,No se requiere recolector de flujo residente.
        // AssistantDetailPages Recopilado de forma independiente mediante RememberFlowList,No depende del estado de ChatViewModel.

        // v1.58: Suscríbete a la lista de plantillas de mensajes
        viewModelScope.launch {
            settings.promptTemplatesFlow.collect { templates ->
                _state.update { it.copy(promptTemplates = templates) }
            }
        }
        // Phase 9.1 (M13): Lista de carpetas vigiladas(Drawer Para renderizado grupal)
        viewModelScope.launch {
            folderRepository.observeAll().collect { folders ->
                _state.update { it.copy(folders = folders) }
            }
        }
        // Phase 9.3 (M2): Observar la configuración de ASR(SYSTEM/DashScope/Step)
        // Cuando el proveedor está activado, los parámetros de conexión analizados desaparecen del estado.,¿Es posible comprobarlo y construirlo? lee este
        viewModelScope.launch {
            combine(settings.asrConfigFlow, settings.providersFlow) { cfg, providers ->
                val resolved = SpeechProviderResolver.resolveAsr(cfg, providers)
                if (resolved.isProviderFallback) {
                    Logger.w(
                        "ChatViewModel",
                        "ASR Error al seguir la configuración del proveedor,Volver a la configuración manual: ${resolved.fallbackReason}" +
                            "(providerId=${cfg.providerId}, modelId=${cfg.modelId})",
                    )
                }
                cfg.withResolvedEndpoint(resolved)
            }.collect { cfg ->
                _state.update { it.copy(asrConfig = cfg) }
            }
        }
        // v0.31: Suscríbete a las preferencias de comportamiento del chat(MessageBubble/ChatScreen/InputBar Controle el renderizado y la interacción en consecuencia)
        viewModelScope.launch {
            settings.chatPreferencesFlow.collect { prefs ->
                _state.update { it.copy(chatPreferences = prefs) }
            }
        }
        // v1.97 (P1-1): shareTemplate Cambiado para exportSessionAsMarkdown first() Cargar,
        // Colector de flujo que ya no es residente(Solo se usó una vez durante la exportación.).
        // v0.33: Configuración de medios de suscripción(TTS / Frecuencia de muestreo de grabación, etc.)
        viewModelScope.launch {
            settings.mediaConfigFlow.collect { cfg ->
                _state.update { it.copy(mediaConfig = cfg) }
            }
        }
        // v1.25: Suscripción a configuración de colaboración multiagente(Lista de equipos y cambio maestro)
        viewModelScope.launch {
            settings.multiAgentConfigFlow.collect { cfg ->
                _state.update { it.copy(multiAgentConfig = cfg) }
            }
        }
        // v1.43: Supervise el estado de compilación a nivel de aplicación. Cuando se crea un nuevo ViewModel después de un corte de página,Si todavía se genera la misma sesión en segundo plano,
        // Establecer automáticamente isStreaming true,UI Aparece un botón Detener y continúa observando el flujo de mensajes.
        // Usar instantánea completa por sessionId,evitar que otra sesión se convierta“tareas recientes”Después de enmascarar el estado de la sesión actual.
        viewModelScope.launch {
            chatGenerationManager.activeGenerations.collect { generations ->
                val state = _state.value
                val currentSessionId = if (state.isAgentMode) state.agentSessionId else state.currentSessionId
                val streaming = currentSessionId != null && currentSessionId in generations
                _state.update { current ->
                    if (current.isStreaming == streaming) {
                        current
                    } else {
                        current.copy(
                            isStreaming = streaming,
                            isWaitingFirstToken = if (streaming) current.isWaitingFirstToken else false,
                        )
                    }
                }
            }
        }
        // v1.0.4: Recopilar ayudas visuales para analizar el progreso.,Pantalla de interfaz de usuario del controlador"Analizando imágenes 2/4…"
        viewModelScope.launch {
            visionBridge.progressFlow.collect { progress ->
                _state.update {
                    it.copy(visionProgress = if (progress.isActive) progress else null)
                }
            }
        }
        // Phase 8.7: Registrar devolución de llamada de estado TTS,conducir isSpeaking / speakingMessageId
        // La devolución de llamada proviene del hilo en segundo plano.(UtteranceProgressListener),Utilice la actualización para garantizar la seguridad del hilo
        ttsManager.onStateChange = { utteranceId, isSpeaking ->
            _state.update {
                it.copy(
                    isSpeaking = isSpeaking,
                    speakingMessageId =
                    utteranceId?.let { id ->
                        runCatching { Uuid.parse(id) }.getOrNull()
                    },
                )
            }
        }
        // v2.3: Configuración de enrutamiento del modelo de tarea de suscripción
        viewModelScope.launch {
            settings.taskRoutingConfigFlow.collect { config ->
                _state.update { it.copy(taskRoutingEnabled = config.enabled) }
            }
        }
        // Phase 10.3: Verifique la coherencia del índice FTS al iniciar,Si es inconsistente, el fondo rebuild
        // - v8→v9 message_fts está vacío después de la migración,El primer inicio indexará completamente los mensajes históricos.
        // - ngram La conversión consume mucha CPU,Poner hilo IO;No bloquear UI(Los mensajes de nivel 10.000 tardan cientos de milisegundos)
        // - Silencio ante el fracaso(Sólo mantén un registro),No afecta el proceso principal.
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // H-01 Reparación: ensureFtsIndexConsistent si suspend,Utilice resultOf en su lugar para evitar engullir CancellationException
                resultOf { sessionRepository.ensureFtsIndexConsistent() }
                    .onError { msg, t -> Logger.w("ChatVM", "FTS ensureFtsIndexConsistent failed: $msg") }
            }
        }
        // v5: Consumidor de cola de envío de mensajes — procesamiento en serie,Admite reintento automático
        // v1.136 Reparación:Agent El modo está completamente aislado de las sesiones del modo tarea.,El consumidor obtiene la sesión actual mediante isAgentMode id,
        // Evitar que se omitan mensajes en la página del Agente porque currentSessionId apunta a la sesión de la tarea(Hace que solo se muestre la animación de entrada.).
        // Reparación de auditoría (Comentarios de los usuarios): Las rutinas de consumo nunca deben morir. La implementación original del bucle for no tiene capa exterior try/catch,
        // Cualquier ronda de procesamiento genera una excepción no detectada.(Tipo sin excepción,o lanzamientos launchStream/_state.update)
        // matará a los consumidores → sendChannel no más consumidores → capacity=8 Lleno pronto →
        // Todos los despachos"La cola está llena", el mensaje no se puede enviar(Los usuarios piensan que no pueden conectarse), la lógica de la conversación también está estancada.
        // Reparación: paquete exterior mientras try/catch,Registrar y reiniciar el consumo cuando se produzca una excepción.(La corrutina no sale.);
        // Reenvasado después de cada ronda de procesamiento. try/catch,Si falla un solo mensaje, el bucle no se cerrará.
        viewModelScope.launch {
            while (isActive) {
                try {
                    for (req in sendChannel) {
                        try {
                            generationController.consumeSendRequest(req)
                        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Error al procesar un solo mensaje:Revertir actualización optimista + eliminar outbox,Continúe con la siguiente ronda.
                            // Aparecerá si no retrocedes.“La página actual se puede ver y desaparecerá después de volver a ingresar.”noticias de fantasmas.
                            Logger.e("ChatVM", "Error en la solicitud de envío del consumidor(msg=${req.userMessage.content.take(30)}): ${e.message}", e)
                            generationController.rollbackOptimisticSend(req)
                            runCatching { sessionRepository.deleteOutbox(req.outboxId) }
                            _state.update { it.copy(isStreaming = false, isWaitingFirstToken = false) }
                        }
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // El consumidor fue interrumpido por una excepción inesperada.:Continuar consumiendo después de grabar(reinicio del ciclo)
                    Logger.e("ChatVM", "Enviar excepción de consumidor de cola,Reiniciar consumo: ${e.message}", e)
                }
            }
        }

        // P2-23: herramientas de generación de medios(generate_image / generate_video / generate_qr_code)Ya en el inicio de la aplicación
        // Registrado por MediaGenToolsRegistrar ToolRegistry(tareas programadas/chat grupal/Los subagentes también pueden ser llamados),
        // Ya no es necesario registrarse aquí. Instalar solo aquí"Anfitrión de entrega de resultados" —— Escribe los resultados generados en el mensaje actual del asistente.,
        // Equivalente al original registerMediaTools() Luego, la parte junto con la interfaz de usuario en execGenerateImage/Video/QrCode.
        // La relación correspondiente entre cada proveedor/devolución de llamada y la implementación anterior se puede encontrar en los comentarios de cada parámetro de construcción de ChatMediaGenHost.
        mediaGenTools?.installHost(
            ChatMediaGenHost(
                accessor = this,
                sessionRepository = sessionRepository,
                streamCoordinator = streamCoordinator,
                appContext = appContext,
                // viejo exec* anclado toolAssistantId(Determine el mensaje de ayuda de destino antes de que comience la compilación;Cada ronda de streamRound se actualiza sincrónicamente)
                toolAssistantIdProvider = { toolAssistantId },
                // viejo exec* de activeToolSessionId ?: currentSessionIdForApproval():
                // tanto para isToolSessionDisplayed() Determinar el foco de visualización,También se utiliza para sesiones sin cita previa de medios. id
                toolSessionIdProvider = { activeToolSessionId ?: currentSessionIdForApproval() },
                // viejo exec* Captura de entrada y verificación antes de escribir en soportes. toolGenerationToken(A-13 token intergeneracional)
                generationTokenProvider = { toolGenerationToken },
                // viejo exec* adentro updateAssistant(assistantId, content, isStreaming = true)
                updateAssistant = { id, content -> updateAssistant(id, content, isStreaming = true) },
                // viejo exec* de persistToolMessageMedia(S-01: Colocación inmediata con información de los medios.)
                persistMediaMessage = { sessionId, id -> persistToolMessageMedia(sessionId, id) },
                // viejo exec* de toolMediaMessages.add(C-17: Registrar las noticias mediáticas de esta generación para su liquidación definitiva.)
                registerMediaMessage = { id -> toolMediaMessages.add(id) },
            ),
        )
        // v1.201: Suscríbete al enlace de delegación + solicitud de pausa,Sincronizar con UiState
        viewModelScope.launch {
            delegationChainTracker.chains.collect { all ->
                val roots =
                    all.values
                        .filter { it.parentRequestId == null }
                        .sortedBy { it.startedAt }
                _state.update { it.copy(delegationChain = roots) }
            }
        }
        viewModelScope.launch {
            delegationPauseManager.activePauses.collect { pauses ->
                _state.update { it.copy(activePauseRequest = pauses.values.firstOrNull()) }
            }
        }

        // v1.202: Suscribirse al flujo de retorno de resultados de delegación sin bloqueo —— Una vez completado el subagente en segundo plano,tratar el resultado como interlude
        // Agregar mensaje a la sesión actual,Permita que los usuarios vean el resultado del trabajo de los subagentes.
        // Nota:consumeCompleted Consumirá y aclarará,Por lo tanto, cada emisión debe extraer la lista de recargas pendientes para la sesión actual.
        viewModelScope.launch {
            deferredResultStore.completedTasks.collect { _ ->
                val sessionId = currentSessionIdForApproval() ?: return@collect
                val tasks = deferredResultStore.consumeCompleted(sessionId)
                if (tasks.isEmpty()) return@collect
                // Adjunte cada resultado de subagente completado a la sesión actual como un mensaje intermedio de ASISTENTE
                val interludes =
                    tasks.map { task ->
                        val prefix = task.label?.let { "[$it] " } ?: ""
                        val content =
                            if (task.status == io.zer0.muse.tools.DeferredResultStore.TaskStatus.RESOLVED) {
                                "${prefix}El subagente ha completado la tarea.:\n${task.result}"
                            } else {
                                "${prefix}Error en la tarea del subagente: ${task.error}"
                            }
                        UIMessage(role = MessageRole.ASSISTANT, content = content)
                    }
                updateMessages { it + interludes }
                // Sincronizar la persistencia con DB,Asegúrate de cortar las páginas./Aún se puede restaurar después de reiniciar
                for (msg in interludes) {
                    try {
                        sessionRepository.appendMessage(sessionId, msg)
                    } catch (e: Exception) {
                        Logger.w("ChatVM", "interlude appendMessage falló", e)
                    }
                }
                // A-10: Consume los resultados de subtareas sin propietario y los degrada a la sesión activa actual.
                // razones comerciales:subagent_task Puede ser omitido por LLM parent_session_id,Hace que el resultado se almacene con una clave de cadena vacía
                // DeferredResultStore,No puede ser alcanzado por consumaCompleted por el ID de sesión anterior y se pierde silenciosamente.
                // Obtenga activamente resultados no atribuidos aquí,Degradar la inyección en la sesión actual para garantizar que los resultados sean visibles para el usuario;
                // Las degradaciones deben registrar registros de ERRORES para indicar la causa raíz.(LLM No transmitido parent_session_id),Facilitar el posicionamiento posterior.
                val unowned = deferredResultStore.consumeUnowned()
                if (unowned.isNotEmpty()) {
                    Logger.e(
                        "ChatVM",
                        "A-10: detectado ${unowned.size} resultados de subtarea sin dueño(parent_session_id desaparecido)," +
                            "Inyección de degradación en la sesión actual sessionId=$sessionId,Compruebe si falta el parámetro parent_session_id en la llamada subagent_task",
                    )
                    val unownedInterludes =
                        unowned.map { task ->
                            val prefix = task.label?.let { "[$it] " } ?: ""
                            val content =
                                if (task.status == io.zer0.muse.tools.DeferredResultStore.TaskStatus.RESOLVED) {
                                    "${prefix}El subagente ha completado la tarea.:\n${task.result}"
                                } else {
                                    "${prefix}Error en la tarea del subagente: ${task.error}"
                                }
                            UIMessage(role = MessageRole.ASSISTANT, content = content)
                        }
                    updateMessages { it + unownedInterludes }
                    for (msg in unownedInterludes) {
                        try {
                            sessionRepository.appendMessage(sessionId, msg)
                        } catch (e: Exception) {
                            Logger.w("ChatVM", "interlude(unowned) appendMessage falló", e)
                        }
                    }
                }
            }
        }

        // v1.202: Suscríbase a hilos activos de agentes secundarios + tareas pendientes,Sincronizado con UiState para la representación SubagentTaskListCard
        // listActiveThreads es normal List(no Flow),a través de deferredResultStore.tasks StateFlow emit
        // Impulsar indirectamente la reorganización(Actualizar la instantánea de la lista de subprocesos cuando cambia el estado de la tarea)
        viewModelScope.launch {
            deferredResultStore.tasks.collect { tasksMap ->
                val sid = currentSessionIdForApproval()
                val activeThreads =
                    subagentThreadStore.listActiveThreads()
                        .filter { sid == null || it.parentSessionId == sid }
                        .filterNot { it.threadId in closedSubagentThreads }
                val pendingTasks =
                    tasksMap.values
                        .filter { it.status == io.zer0.muse.tools.DeferredResultStore.TaskStatus.PENDING }
                        .filter { sid == null || it.parentSessionId == sid }
                _state.update {
                    it.copy(
                        activeSubagentThreads = activeThreads,
                        pendingSubagentTasks = pendingTasks,
                    )
                }
            }
        }

        // v1.0.15: Recuperar mensajes de la bandeja de salida no terminados(Cuando un proceso se reinicia después de ser eliminado)
        // Escanear tabla de bandeja de salida,Asegúrese de que los mensajes de los usuarios persistan en la tabla de mensajes.,Luego elimine el registro de la bandeja de salida.
        // Los usuarios pueden ver el mensaje y reenviarlo manualmente cuando abren la sesión correspondiente.
        viewModelScope.launch {
            val pending = resultOf { sessionRepository.getPendingOutbox() }.getOrNull()
            if (pending.isNullOrEmpty()) return@launch
            Logger.i("ChatVM", "outbox restaurar: ${pending.size} mensajes sin terminar")
            for (req in pending) {
                // Compruebe si el mensaje del usuario ha persistido(Es posible que el consumidor tenga appendMessage pero el proceso finalizó antes de launchStream)
                val alreadySaved =
                    resultOf { sessionRepository.messageExists(req.sessionId, req.userMessageId) }
                        .getOrNull() ?: false
                // Si el punto de control ya existe, significa que la generación realmente se ha iniciado; no vuelva a entregar la bandeja de salida restante.
                // De lo contrario, se generará la misma respuesta del asistente en paralelo con la ruta de recuperación del punto de control.
                val checkpointExists =
                    resultOf { sessionRepository.hasGenerationCheckpointForUserMessage(req.userMessageId) }
                        .getOrNull() ?: false
                if (checkpointExists) {
                    Logger.i("ChatVM", "outbox ${req.id} Ya existe un punto de control de generación, omita la recuperación repetida")
                    resultOf { sessionRepository.deleteOutbox(req.id) }
                        .onError { msg, _ -> Logger.w("ChatVM", "No se pudo borrar el punto de control correspondiente a la bandeja de salida: $msg") }
                    continue
                }
                val images =
                    runCatching {
                        idListJson.decodeFromString<List<String>>(req.imageBase64Json)
                    }.getOrDefault(emptyList())
                val userMsgId =
                    runCatching { Uuid.parse(req.userMessageId) }.getOrElse {
                        Logger.w("ChatVM", "outbox userMessageId no UUID,Repliegue aleatorio id: ${req.userMessageId}")
                        Uuid.random()
                    }
                val userMessage =
                    UIMessage(
                        id = userMsgId,
                        role = MessageRole.USER,
                        content = req.text,
                        imageBase64List = images,
                        createdAt = req.createdAt,
                    )
                if (!alreadySaved) {
                    resultOf {
                        // Reutilice userMessageId + createAt de la bandeja de salida para evitar cambios en la clasificación después de la recuperación.
                        sessionRepository.appendMessage(req.sessionId, userMessage)
                    }.onError { msg, t ->
                        Logger.w("ChatVM", "outbox No se pudo restaurar appendMessage: $msg", t)
                    }
                }
                // Los mensajes de usuario no se pueden eliminar directamente cuando ya existen: el proceso puede morir entre appendMessage y launchStream.
                // Vuelva a enviar la misma solicitud y elimínela según el outboxId original después de que el consumidor la haya iniciado y generado correctamente.
                // outbox El campo de enrutamiento no se guarda y se volverá a determinar según la configuración de enrutamiento de tareas más reciente durante la recuperación.
                val recoveredFallbackModel = sessionModelOverrides[req.sessionId] ?: globalSelectedModelId
                val recoveredFallbackProvider = sessionProviderOverrides[req.sessionId] ?: globalActiveProviderId
                val recoveredRoute =
                    settings.recommendTaskRoute(
                        req.text,
                        recoveredFallbackModel,
                        recoveredFallbackProvider,
                    )
                val retry =
                    sendChannel.trySend(
                        SendRequest(
                            text = req.text,
                            images = images,
                            sessionId = req.sessionId,
                            userMessage = userMessage,
                            assistantMessageId = runCatching { Uuid.parse(req.assistantMessageId) }.getOrElse { Uuid.random() },
                            outboxId = req.id,
                            taskRouteSelection = recoveredRoute,
                        ),
                    )
                if (retry.isFailure) {
                    Logger.w("ChatVM", "outbox Error al volver a poner en cola la recuperación: ${req.id}")
                }
            }
        }
    }

    /** v1.201: El usuario presenta la decisión de suspensión delegada. */
    fun submitPauseDecision(requestId: String, response: io.zer0.muse.tools.DelegationPauseManager.PauseResponse) {
        delegationPauseManager.submitDecision(requestId, response)
    }

    /** v1.201: El usuario cancela una delegación en curso. */
    fun cancelDelegation(requestId: String) {
        delegationPauseManager.cancelDelegation(requestId)
    }

    /**
     * v1.202: Cancelar tareas de subagente en segundo plano.
     *
     * Pase [DeferredResultStore.abort] Marcar la tarea como ABORTED,
     * El SkillExecutor en segundo plano dejará de avanzar después de detectar el cambio de estado.
     */
    fun cancelSubagentTask(taskId: String) {
        viewModelScope.launch {
            deferredResultStore.abort(taskId)
        }
    }

    /**
     * Cierre un hilo de agente secundario en segundo plano.
     *
     * y [cancelSubagentTask] No es lo mismo: la que se abortó era una “tarea retrasada que aún no ha dado resultados”.
     * Esto finaliza la sesión que "ha terminado de ejecutarse pero el hilo aún está abierto". Este último originalmente no tenía ninguna salida en la interfaz,
     * Una vez que el hilo no esté terminado, siempre quedará colgado en la tarjeta de tareas y el usuario solo podrá mirarlo.
     *
     * Elimínelo activamente del estado después del cierre: la lista de subprocesos solo se recalcula cuando cambia la tarea de deferredResultStore.
     * El hilo de cierre en sí no genera eventos de tarea. Si no la recoges, la tarjeta no desaparecerá hasta el siguiente evento.
     */
    fun closeSubagentThread(threadId: String) {
        viewModelScope.launch {
            // Cuenta primero y luego colóquelo en la base de datos: este hilo en la interfaz ha finalizado y las actualizaciones posteriores no deberían devolverlo.
            closedSubagentThreads.add(threadId)
            runCatching { subagentThreadStore.close(threadId) }
                .onFailure { e ->
                    io.zer0.common.Logger.w("ChatVM", "No se pudo cerrar el hilo del agente secundario: ${e.message}")
                }
            _state.update { st ->
                st.copy(
                    activeSubagentThreads =
                    st.activeSubagentThreads.filterNot { it.threadId in closedSubagentThreads },
                )
            }
        }
    }

    /**
     * Reparación de auditoría (Comentarios de los usuarios): Lógica de consumo de mensaje único en cola de envío.
     * Extraído de la circulación del consumidor original.,Llamados uno por uno por el bucle while externo nunca muerto.
     * La lógica es completamente consistente con la implementación original.:Omisión de cambio de sesión + reintento automático de appendMessage + launchStream.
     */
    fun updateInput(text: String) = inputController.updateInput(text)

    /**
     * v1.205 B2: Agregue la entrada actual a la cola para enviar(En cola durante la compilación,No interrumpas la generación actual.).
     *
     * Después de unirse al equipo, borre el cuadro de entrada y la imagen que se enviará.;cola llena([MAX_PENDING_SEND_QUEUE])Ignorado en silencio.
     */
    fun enqueuePendingSend() = inputController.enqueuePendingSend()

    /** v1.205 B2: Eliminar el artículo de la cola [index] artículo. */
    fun removePendingSend(index: Int) = inputController.removePendingSend(index)

    /**
     * v1.205 B2: Envía el primero en la cola por separado. [index] artículo.
     *
     * Primero retire la cola y luego ingrese a la canalización de envío.,Evite el reingreso que resulte en envíos repetidos.
     */
    fun sendPendingSend(index: Int) = inputController.sendPendingSend(index)

    /** v1.205 B2: Pon el primero en la cola. [index] Complete el cuadro de entrada(Poner en cola después de editar/enviar),Y sal del equipo. */
    fun editPendingSend(index: Int) = inputController.editPendingSend(index)

    /** v1.205 B2: Borre toda la cola de envíos. */
    fun clearPendingQueue() = inputController.clearPendingQueue()

    /**
     * v1.0.47 P5: en el cuadro de entrada/Devolución de llamada de flecha hacia abajo,Recorra el historial de entrada de esta sesión.
     *
     * Acuerdo:[ChatUiState.inputHistory] prensa"nuevo→viejo"almacenamiento secuencial(index 0 = último).
     * - direction < 0(flecha arriba):pasando a una historia más antigua
     *   - Actualmente no estoy navegando(index==null):saltar a index 0(último)
     *   - Actualmente en el índice i: saltar a i+1(mayor);No se moverá cuando llegue al final.
     * - direction > 0(flecha hacia abajo):Mover al historial actualizado
     *   - Actualmente en el índice 0: Salir de la navegación,Borrar entrada
     *   - actualmente en index i>0:saltar a i-1
     *   - No en navegación:sin moverse
     * - direction == 0:Ninguna acción
     * - Regresar directamente cuando el historial esté vacío.
     *
     * Entrada de edición del manual del usuario(ver [updateInput])se restablecerá [ChatUiState.inputHistoryIndex] para null,
     * Al presionar nuevamente la flecha hacia arriba se comenzará desde el más reciente.
     */
    fun navigateInputHistory(direction: Int) = inputController.navigateInputHistory(direction)

    /**
     * v1.0.47 P5-3: Abra el menú Conteo de tokens.
     *
     * solo en [ChatUiState.tokenEstimateEnabled] Lo llama la interfaz de usuario cuando está habilitado.
     * Calcular una instantánea única(Entrada actual + mensajes históricos + ventana de contexto del modelo),Almacenar en estado para visualización del menú,
     * Evite la codificación BPE cada vez que presione una tecla(Gastos generales de alto rendimiento).
     *
     * BPE La codificación consume mucha CPU,Ejecutar en Dispatchers.Default;
     * [settings.getSelectedModel] si suspend,Es necesario llamarlo en la corrutina.
     * Reutilización del número de token de mensaje histórico [TokenEstimator.estimate] Los mensajes se sobrecargan,
     * Ya incluido reasoning/mood/reflection/toolCalls.arguments Con gastos generales fijos de 4 fichas por artículo.
     */
    fun showTokenCountMenu() {
        val input = _state.value.input
        val messages = _messages.value
        viewModelScope.launch(Dispatchers.Default) {
            val inputTokens = TokenEstimator.estimate(input)
            val historyTokens = TokenEstimator.estimate(messages)
            val model = resultOf { settings.getSelectedModel() }.getOrNull()
            val contextWindow = model?.contextWindow?.takeIf { it > 0 }
            // v2.5.3 (P4-3): Calcule aproximadamente el costo histórico de esta sesión basándose en el precio del modelo actual (si se desconoce el precio unitario). known=false, no mostrado).
            val cost =
                model?.let { m ->
                    io.zer0.ai.core.TokenCostCalculator.costFor(
                        provider = m.providerId,
                        modelId = m.id,
                        usage = io.zer0.ai.core.TokenCostCalculator.Usage(promptTokens = historyTokens.toLong()),
                    )
                }
            val snapshot =
                TokenCountSnapshot(
                    inputTokens = inputTokens,
                    historyTokens = historyTokens,
                    contextWindow = contextWindow,
                    estimatedCostUsd = cost?.takeIf { it.known }?.usd,
                )
            _state.update { it.copy(tokenSnapshot = snapshot, tokenCountVisible = true) }
        }
    }

    /** v1.0.47 P5-3: Cierre el menú Conteo de tokens. */
    fun dismissTokenCountMenu() {
        _state.update { it.copy(tokenCountVisible = false) }
    }

    /**
     * v2.x: comité — Convoca a un grupo de asistentes en cualquier momento desde la conversación principal para iniciar una discusión de chat grupal temporal,La conclusión retroalimenta automáticamente esta conversación.
     *
     * proceso:Crear un chat grupal temporal → Rotación del disparador por problema → Resumen automático → Pase DeferredResultStore
     * Inyectar la conclusión en la sesión actual como un interludio.(Los usuarios pueden ver el proceso de discusión completo en la página de chat grupal.).
     *
     * @param memberIds asistente de discusión id(≥2)
     * @param topic temas de discusión
     */
    fun launchCommittee(memberIds: List<String>, topic: String) {
        val scheduler = groupChatScheduler ?: return
        val repository = groupChatRepository ?: return
        val sessionId = displayedSessionId(state.value) ?: return
        if (memberIds.size < 2 || topic.isBlank()) return
        val taskId = "committee-" + java.util.UUID.randomUUID().toString()
        val chatName =
            "comité · " +
                java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date())
        deferredResultStore.defer(taskId, sessionId, null, "comité", topic.take(60))
        viewModelScope.launch {
            try {
                val committeeChatId = repository.createChat(chatName, memberIds)
                // 1. Activar la rotación de la discusión
                scheduler.launchRoundRobin(committeeChatId, topic, emptyList())
                var waited = 0
                while (scheduler.hasActiveGeneration(committeeChatId) && waited < 1800) {
                    delay(1000)
                    waited++
                }
                // 2. Resumen automático
                scheduler.launchSummary(committeeChatId)
                waited = 0
                while (scheduler.hasActiveGeneration(committeeChatId) && waited < 300) {
                    delay(1000)
                    waited++
                }
                // 3. Obtenga un resumen y retroalimente la conversación principal
                val recent = repository.getRecentMessages(committeeChatId, 10)
                val summary = recent.lastOrNull { it.messageType == "summary" }?.body
                if (summary != null) {
                    deferredResultStore.resolve(taskId, "[Conclusión del Comité · $chatName】\n$summary")
                } else {
                    deferredResultStore.resolve(taskId, "[Comité] Se ha completado el debate(Chat grupal \"$chatName\"),No se genera ningún resumen. Problema:$topic")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("ChatVM", "La ejecución del comité falló", e)
                deferredResultStore.fail(taskId, e.message ?: "La ejecución falló")
            }
        }
    }

    /** Borrar todos los errores(portal de compatibilidad con versiones anteriores,UI"cerrar"llamada de botón). */
    fun clearError() {
        clearErrors()
    }

    /** P5-D/E: UI Error de informe de capa(Fallo de voz, fallo de análisis de archivos, etc.). ir addError,Las clases recuperables desaparecerán automáticamente. */
    fun reportError(msg: String) {
        addError(ChatErrorType.UNKNOWN, msg)
    }

    // ── v0.49: Gestión de errores múltiples ──────────────────────────────────────────────────

    /**
     * v2.6.8: Si la aplicación está en primer plano(pancarta de error del controlador“Tiempo después de visible”).
     *
     * Aprobado por el observador ProcessLifecycleOwner de MuseApp [onAppBackground] / [onAppForeground]
     * Mantenimiento. Predeterminado true —— Cuando el proceso está vivo y nadie le ha notificado que salga del fondo,Supongamos que el usuario está mirando.
     *
     * ¿Por qué es necesario para cronometrar?:Después de cambiar al fondo“desaparecer automáticamente”Significa que el usuario queda limpio sin siquiera verlo.
     */
    private val appForegroundFlow = MutableStateFlow(true)

    /**
     * Agregar un error a [ChatUiState.errors].
     *
     * Reglas de desaparición automática(v2.6.8 reescribir,Se fijó en 5 segundos.):
     *  1. lo único correcto [shouldAutoDismissError] Errores de tiempo para verdadero —— Recuperable **y**
     *     No es una herramienta/modelo/Categorías como credenciales que requieren la toma de decisiones del usuario;
     *  2. Tiempo desde“El error realmente aparece en la parte superior.”a partir de ese momento,en lugar de desde el momento addError:
     *     monitorear [ChatUiState] + marca de primer plano,espera [resolveErrorVisibility] El cronómetro vuelve VISIBLE.
     *     De esta forma, el tiempo que ocupa Banner y el tiempo dedicado a pasar a segundo plano se comprimen sin ocupar la ventana de lectura.;
     *  3. Una vez invisible durante la cuenta regresiva(salir y esperar)luego pausa,Vuelve y continúa usando las ventanas restantes.;
     *  4. Espera como mucho [ERROR_VISIBLE_WAIT_MS] Si aún no es visible, desiste y desaparece automáticamente.(Deje que el usuario apague manualmente).
     *
     * La antigua implementación directamente `delay(5000); dismissError(...)`,Superposición“El banner de compresión prioriza los errores”de
     * después de la vieja estrategia,Este error no aparecerá en la pantalla ni una sola vez al comprimir durante más de 5 segundos.
     */
    private fun addError(type: ChatErrorType, message: String, isRecoverable: Boolean = true) {
        val error = ChatError(type = type, message = message, isRecoverable = isRecoverable)
        _state.update { it.copy(errors = it.errors + error) }
        if (shouldAutoDismissError(type, isRecoverable)) {
            scheduleAutoDismiss(error.id)
        }
    }

    /**
     * El flujo de visibilidad actual de un error.(Síntesis de estado + marca de primer plano).
     *
     * La determinación de la visibilidad se deja enteramente a [resolveErrorVisibility] —— Se utilizan las mismas reglas de prioridad que la interfaz de usuario.
     */
    private fun errorVisibilityFlow(errorId: String): Flow<ErrorVisibility> = combine(_state, appForegroundFlow) { state, foreground ->
        resolveErrorVisibility(
            errorStillPresent = state.errors.any { it.id == errorId },
            appInForeground = foreground,
            isCompressing = state.isCompressing,
            showPendingResume = state.pendingToolCallCount > 0 && !state.isStreaming,
            isConfigured = state.isConfigured,
        )
    }.distinctUntilChanged()

    /**
     * Organizar un error“Tiempo después de la visibilidad”desaparecer automáticamente(ver las reglas [addError]).
     *
     * Capa exterior, etc.“visible”,Cuenta atrás de la capa interior;Una vez que se vuelva invisible durante la cuenta regresiva, regrese a la capa exterior y espere nuevamente.
     * No hay necesidad de arreglar `delay(15_000)` Talla única,Es debido a la sección interrumpida por un banner o fondo comprimido.
     * Los usuarios del tiempo no pueden ver las palabras.,No debe contarse en la ventana de lectura.
     */
    private fun scheduleAutoDismiss(errorId: String) {
        viewModelScope.launch {
            while (true) {
                val firstSeen = withTimeoutOrNull(ERROR_VISIBLE_WAIT_MS) {
                    errorVisibilityFlow(errorId).first { it != ErrorVisibility.PENDING }
                }
                when (firstSeen) {
                    // Espere hasta que la ventana se agote y seguirá siendo invisible.:No lo borres por iniciativa propia,Deje que los usuarios lo apaguen ellos mismos.
                    null -> {
                        Logger.w("ChatVM", "error ${ERROR_VISIBLE_WAIT_MS}ms no visible,Ríndete y desaparece automáticamente. id=$errorId")
                        return@launch
                    }
                    // El error ha sido desactivado por el usuario/borrado mediante clearErrors.
                    ErrorVisibility.GONE -> return@launch
                    else -> Unit
                }
                // Ya visible:Leer una ventana de lectura completa;Si no es visible en el medio, regresa a la capa exterior y espera nuevamente.(No consume windows).
                val interrupted = withTimeoutOrNull(ERROR_AUTO_DISMISS_MS) {
                    errorVisibilityFlow(errorId).first { it != ErrorVisibility.VISIBLE }
                }
                when (interrupted) {
                    // Continuamente visible en toda la ventana. → Desaparecerá automáticamente cuando llegue el momento.
                    null -> {
                        dismissError(errorId)
                        return@launch
                    }
                    ErrorVisibility.GONE -> return@launch
                    else -> Unit
                }
            }
        }
    }

    /** v0.49: Eliminar el error con la identificación especificada.(manual de usuario dismiss). */
    fun dismissError(errorId: String) {
        _state.update { it.copy(errors = it.errors.filter { er -> er.id != errorId }) }
    }

    /** v0.49: Borre todos los errores. */
    fun clearErrors() {
        _state.update { it.copy(errors = emptyList()) }
    }

    /**
     * v0.49: Clasificar tipos de error según mensajes de excepción(Bloque de captura principal para launchStream).
     *
     * v1.0.1 (P4): Usar en su lugar [ProviderError] Clasificación de tipo,Reemplaza la cadena original con una coincidencia que contiene.
     * v1.0.27 Phase 5-B: Priorizar la ruta de tipo `(throwable as? ProviderException)?.providerError`,
     * Úselo solo como encubrimiento. [inferFromMessage] Inferencia de cadenas (compatible con versiones anteriores).
     *
     *  - ProviderError.Network → NETWORK(IOException / timeout / Conexión perdida)
     *  - ProviderError.RateLimit → RATE_LIMIT(429 / RESOURCE_EXHAUSTED)
     *  - ProviderError.ServerError → NETWORK(5xx / 529 overloaded,Se puede volver a intentar)
     *  - ProviderError.AuthError → API_KEY(401 / 403)
     *  - el resto → UNKNOWN
     */
    private fun classifyErrorType(message: String, throwable: Throwable?): ChatErrorType = classifyErrorTypeStatic(message, throwable)

    /**
     * v1.0.17: Espere a que se recupere la red(StreamInterrupted Para una reanudación inteligente de la transferencia).
     *
     * uso prioritario [NetworkMonitor.isOnline] StateFlow(Reflejo en tiempo real del estado de la red.);
     * si [NetworkMonitor] Si no está disponible, recurra a [isNetworkAvailable] Sondee ConnectivityManager de forma sincrónica.
     *
     * @param timeoutMs tiempo máximo de espera(milisegundos),Regreso del tiempo de espera false
     * @return true Indica que la red ha sido restaurada.;false Indica tiempo de espera y no hay recuperación.
     */
    private suspend fun waitForNetworkRecovery(timeoutMs: Long): Boolean {
        // Si actualmente estás en línea, regresa inmediatamente(Evite esperas innecesarias)
        if (networkMonitor.isOnline.value) return true
        // withTimeoutOrNull Regreso del tiempo de espera null → false;Devuelve cuando cualquier fuente aparece en línea durante la encuesta true
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            // Estado de la red de encuestas(Verifique cada NETWORK_POLL_INTERVAL_MS),Compatible con escenarios donde no se activa la devolución de llamada de NetworkMonitor
            while (!networkMonitor.isOnline.value && !isNetworkAvailable()) {
                kotlinx.coroutines.delay(NETWORK_POLL_INTERVAL_MS)
            }
            true
        } ?: false
    }

    /**
     * v1.0.17: Verifique sincrónicamente si la red está disponible(Plan alternativo,cuando [NetworkMonitor] Usar cuando no esté disponible).
     *
     * Juicio basado en ConnectivityManager.activeNetwork + NET_CAPABILITY_INTERNET,
     * con [NetworkMonitor] La lógica de checkOnline es consistente con(pero no requerido VALIDATED,más flojo).
     */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm =
                appContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            Logger.w("ChatVM", "isNetworkAvailable check failed: ${e.message}")
            false
        }
    }

    /**
     * Tarea 3: Unificar la redacción de mensajes de error de red.
     * Clasificación por palabras clave de mensajes de excepción,Ofrezca a los usuarios indicaciones en chino más amigables.
     */
    private fun classifyNetworkError(e: Throwable): String = ErrorMessages.classifyNetworkError(appContext, e)

    // ── v0.45: Estimación de ocupación del token de contexto ──────────────────────────────────────

    private var cachedSystemPrompt
        get() = systemPromptCache.cachedSystemPrompt
        set(value) {
            systemPromptCache.cachedSystemPrompt = value
        }
    private var cachedStaticSystemPrompt
        get() = systemPromptCache.cachedStaticSystemPrompt
        set(value) {
            systemPromptCache.cachedStaticSystemPrompt = value
        }
    private var cachedStaticSnapshotKey
        get() = systemPromptCache.cachedStaticSnapshotKey
        set(value) {
            systemPromptCache.cachedStaticSnapshotKey = value
        }

    /**
     * v0.45: Actualizar información contextual(Cambiar de sesión/enviar/detenerse/Llamado después de la compresión.).
     *
     * 1. Leer el modelo seleccionado actualmente contextWindow → escribir contextMaxTokens
     * 2. Reconstruir el mensaje del sistema y el caché(SystemPromptAssembler.build)
     * 3. Actualizar el recuento de tokens ahora
     *
     * Este es el método de suspensión.,Llamado en viewModelScope.launch.
     */
    private suspend fun refreshContextInfo() {
        // 1. Cargando el modelo contextWindow
        val model = resultOf { settings.getSelectedModel() }.getOrNull()
        // Cuando el modelo no declara contextWindow,Utilice ModelContextWindowRegistry para averiguarlo por prefijo de identificación.
        // v2.5.7 Reparación: Fondo de bolsillo original 32768 demasiado pequeño —— tránsito/Modelo de enrutamiento aleatorio (como automático)contextWindow=null
        // fue considerado como 32K, pero la historia real+system+Las definiciones de herramientas superan con creces los 32 000, lo que da como resultado“Siempre está por encima del límite y la compresión es inútil.”.
        // Los modelos modernos son generalmente de 128K+, y 128K se menciona en detalle; si realmente excede el límite, el proveedor lo rechazará (ver estrategia de interceptación dura).
        val maxTokens =
            model?.contextWindow
                ?: ModelContextWindowRegistry.lookup(model?.id ?: "")
                ?: DEFAULT_CONTEXT_WINDOW_FALLBACK
        // 2. reconstruir system prompt(6 paso flujo de trabajo paso 1,Contiene personalidad/memoria/Herramientas, etc.)
        // Dividir en instantánea estática + tiempo dinámico,Las partes estáticas se reutilizan dentro de la misma sesión.
        val assistant =
            _state.value.currentAssistant
                ?: assistantRepository.getById("default")
        val memoryEnabled = assistant?.memoryEnabled ?: true
        val useGlobalMemory = assistant?.useGlobalMemory ?: false
        val timeReminderEnabled = assistant?.enableTimeReminder ?: true
        val effectiveMemoryEnabled = memoryEnabled && settings.isMemoryEnabled()
        // v1.0.72: Esta sesión no se refiere a indicadores de memoria.
        val state = _state.value
        val effSessionId = if (state.isAgentMode) state.agentSessionId else state.currentSessionId
        val sessionIgnoreMemory =
            state.sessions
                .firstOrNull { it.id == effSessionId }?.ignoreMemory ?: false
        val memoryScope = assistant?.id?.takeIf { it.isNotBlank() && it != "default" } ?: "main"
        val memorySpaceId = settings.currentSpaceIdFlow.firstOrNull().orEmpty().ifBlank { "default" }
        // 2.1 Reconstruir y almacenar en caché instantáneas estáticas
        val staticSnapshot =
            resultOf {
                systemPromptAssembler.buildStaticSnapshot(
                    assistant = assistant,
                    memoryEnabled = effectiveMemoryEnabled,
                    ignoreMemory = sessionIgnoreMemory,
                    useGlobalMemory = useGlobalMemory,
                    memoryScope = memoryScope,
                    memorySpaceId = memorySpaceId,
                    // La conexión entre sesiones requiere conocer la sesión actual para poder excluirla y tomar la "anterior".
                    sessionId = effSessionId,
                )
            }.getOrNull() ?: ""
        cachedStaticSystemPrompt = staticSnapshot
        cachedStaticSnapshotKey = generationController.computeFullSnapshotKey(
            assistant = assistant,
            memoryEnabled = effectiveMemoryEnabled,
            useGlobalMemory = useGlobalMemory,
            memoryScope = memoryScope,
            memorySpaceId = memorySpaceId,
            sessionId = effSessionId,
        )
        // 2.2 combinación completa system prompt(Instantánea estática + hora actual + guía de emoticonos)
        val dynamicSection = if (timeReminderEnabled) systemPromptAssembler.buildDynamicSection() else ""
        // v2.x: Guía de uso de emoticonos(lectura dinámica;La biblioteca esta vacia/Cadena vacía cuando el interruptor está apagado)
        val stickerGuide = resultOf { systemPromptAssembler.buildStickerGuideSection() }.getOrNull().orEmpty()
        cachedSystemPrompt =
            buildString {
                if (staticSnapshot.isNotBlank()) append(staticSnapshot)
                if (dynamicSection.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n---\n\n")
                    append(dynamicSection)
                }
                if (stickerGuide.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n---\n\n")
                    append(stickerGuide)
                }
            }
        // 3. actualizar state(contextMaxTokens + contextTokenCount)
        // v1.97 Correcciones de rendimiento: TokenEstimator.estimate Es una operación intensiva de CPU(jtokkit BPE codificación),
        // lista de mensajes de larga historia(200 Artículo × varios cientos de caracteres)Una sola vez puede alcanzar entre 50 y 200 ms.
        // Originalmente ejecutado sincrónicamente en el hilo principal,Apilado una vez por segundo durante la transmisión,Causando directamente retraso.
        // Ahora trasladado a Dispatchers. Ejecución predeterminada del subproceso en segundo plano.,El resultado se vuelve a escribir en el hilo principal para indicarlo.
        val msgsSnapshot = _messages.value
        val sysPromptSnapshot = cachedSystemPrompt
        // v2.x: El calibre ocupado tiene el mismo origen que la ruta de actualización de la transmisión (provider Medición real primero + estimación de cola, ver [ContextTokenUsage]),
        // De lo contrario, cambie de sesión/Lo que se escribe después de la compresión es una estimación pura, que eliminará el nivel real del agua recién recolectada.
        val tokenCount =
            withContext(Dispatchers.Default) {
                ContextTokenUsage.estimate(msgsSnapshot, sysPromptSnapshot)
            }
        _state.update {
            it.copy(
                contextMaxTokens = maxTokens,
                contextTokenCount = tokenCount,
            )
        }
    }

    /**
     * v2.x: Caché de identificadores de mensajes cubiertos por el punto de control de la sesión actual.
     *
     * Exclusiones para estimaciones de ocupación"Comprimido y no se enviará nuevamente al modelo."noticias. La razón para usar caché en lugar de verificar la base de datos cada vez es porque
     * Durante el proceso de transmisión, la tasa de ocupación se actualizará con frecuencia de acuerdo con la limitación (consulte el punto de llamada de updateContextTokenCount).
     */
    @Volatile
    private var checkpointCoveredIds: Set<String> = emptySet()

    /** ¿A qué sesión pertenece el caché anterior (dejará de ser válido inmediatamente después de cambiar la sesión para evitar usar el conjunto de cobertura de la sesión A a la sesión B)? */
    @Volatile
    private var checkpointCoveredSessionId: String? = null

    /**
     * Asegúrese de que el conjunto de cobertura sea coherente con la sesión mostrada actualmente; si no, vuelva a leer el punto de control.
     *
     * usar"Autocuración perezosa"en lugar de en cada sesión de corte/Una llamada queda oculta dondequiera que esté escrito el punto de control: siempre que el estado no coincida,
     * La próxima vez que se calcule la ocupación, se corregirá automáticamente y no aparecerá."Cambié diez lugares y me perdí uno."deriva a largo plazo.
     */
    private suspend fun ensureCheckpointCoveredIds(sessionId: String) {
        if (checkpointCoveredSessionId == sessionId) return
        checkpointCoveredSessionId = sessionId
        refreshCheckpointCoveredIds(sessionId)
    }

    /** Vuelva a leer el punto de control de la sesión actual y actualice la colección de cobertura (llamada después de escribir un punto de control/sesión de cambio). */
    private suspend fun refreshCheckpointCoveredIds(sessionId: String) {
        checkpointCoveredIds =
            try {
                val checkpoint = checkpointReader.get(sessionId)
                val covered =
                    if (checkpoint == null) {
                        io.zer0.muse.transformer.CompressionSummaryStore.clear(sessionId)
                        emptySet()
                    } else {
                        val ids =
                            io.zer0.muse.transformer.ContextCheckpointMerge.coveredMessageIds(
                                _messages.value,
                                checkpoint,
                            )
                        // A paged UI window may not contain the persisted boundary yet. In that
                        // case keep the checkpoint authoritative and let the full-history path
                        // hydrate the in-process watermark later.
                        if (ids.isNotEmpty() && checkpoint.summary.isNotBlank()) {
                            io.zer0.muse.transformer.CompressionSummaryStore.remember(
                                sessionId = sessionId,
                                coveredIds = ids,
                                summary = checkpoint.summary,
                            )
                        }
                        ids
                    }
                // Punto de anclaje de la interfaz de sincronización: posición de la línea de separación + número acumulado de elementos (se borra cuando no hay ningún punto de control para evitar líneas de separación restantes de la sesión anterior)
                val boundaryId = checkpoint?.lastCoveredMessageId?.takeIf { it.isNotBlank() }
                _state.update {
                    it.copy(
                        contextCheckpointBoundaryId = boundaryId,
                        contextCheckpointTotalCovered = checkpoint?.totalCoveredCount ?: 0,
                    )
                }
                covered
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Logger.w("ChatVM", io.zer0.muse.transformer.CompressionDiagnostics.refreshCoveredIdsFailed(t.message))
                emptySet()
            }
    }

    /**
     * v0.45: Actualizar rápidamente el recuento de tokens(Llamado cada 200 caracteres o 1000 ms durante la transmisión).
     *
     * uso [cachedSystemPrompt] Evite reconstruir cada vez system prompt(IO Denso).
     * Los escenarios sin transmisión deben llamar [refreshContextInfo](Reconstruirá el mensaje del sistema + cargará contextWindow).
     *
     * v1.97 Correcciones de rendimiento: Cambiar a suspend,TokenEstimator.estimate(jtokkit BPE codificación,CPU Denso)
     * Se movió la ejecución a Dispatchers.Default. Originalmente sincronizado en el hilo principal.,Larga historia de realización de pedidos. 50-200ms,
     * Apilado una vez por segundo durante la transmisión,Es la principal causa del retraso.
     */
    private suspend fun updateContextTokenCount() {
        displayedSessionId()?.let { ensureCheckpointCoveredIds(it) }
        generationController.updateContextTokenCount(checkpointCoveredIds)
    }

    /**
     * Los nombres de resumen de IA se activan al salir de una conversación.
     * Sólo si el título sigue siendo el predeterminado"nueva sesión"Se activa cuando hay al menos una ronda completa de diálogo.
     */
    fun autoTitleOnExit(sessionId: String) {
        autoTitleSession(sessionId)
    }

    /**
     * Función2: Las conversaciones reciben nombre automáticamente.
     *
     * Una vez completada la transmisión,Si el título de la sesión es el valor predeterminado(Como por ejemplo"nueva sesión"o vacío),Llame a LLM para resumir la conversación en 6 palabras o menos.
     * Solo se activa cuando hay al menos un mensaje de usuario,Evite diálogos vacíos que generen títulos sin sentido.
     */
    private fun autoTitleSession(sessionId: String) {
        val state = _state.value
        val title = state.sessions.firstOrNull { it.id == sessionId }?.title ?: return
        val defaultTitle = appContext.getString(R.string.session_repo_default_title)
        // v2.5.2 fix: Títulos sin sentido que quedan de la historia ("..." etc. literales) también se consideran sin nombre,
        // Permita el cambio de nombre automático al salir la próxima vez; de lo contrario, la sesión anterior siempre tendrá puntos suspensivos.
        val titleIsMeaningless = io.zer0.muse.data.session.isMeaninglessSessionTitle(title)
        if (title.isNotBlank() && title != defaultTitle && !titleIsMeaningless) return
        val messages = _messages.value.filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
        if (messages.size < 2) return
        val preview =
            messages.take(4).joinToString("\n") {
                io.zer0.muse.transformer.InternalMarkupSanitizer.stripForDisplay(it.content).take(100)
            }
        if (preview.isBlank()) return
        viewModelScope.launch(AppDispatchers.io) {
            // v1.0.52: Leer nombres de conversaciones definidos por el usuario prompt(null/Una cadena vacía indica el valor predeterminado)
            val customTitlePrompt =
                resultOf { settings.customTitlePromptFlow.first() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
            val prompt =
                if (customTitlePrompt != null) {
                    "$customTitlePrompt\n\n$preview"
                } else {
                    "Por favor use4llegar8Resume el tema de la siguiente conversación en una palabra.,Salida directa del texto del título,No utilice comillas ni otros signos de puntuación.:\n\n$preview"
                }
            resultOf {
                val completion =
                    retryOnNetworkError {
                        // v2.x: La generación de títulos da prioridad al archivo "gadget" de enrutamiento del modelo auxiliar(Déjelo en blanco para regresar al modelo de diálogo principal.)
                        val routed =
                            runCatching {
                                io.zer0.muse.data.routing.UtilityModelRouter(settings)
                                    .resolve(io.zer0.muse.data.routing.UtilityTier.SMALL)
                            }.getOrNull()
                        chatService.completeText(
                            messages = listOf(UIMessage(role = MessageRole.USER, content = prompt)),
                            model = routed?.second,
                            providerConfig = routed?.first,
                        )
                    }
                // v1.0.74 fix: Pelar primero <think> Luego se limpian las etiquetas de inferencia.
                // Anteriormente, recortar+eliminar comillas directamente+take(20),Modelo con salida de pensamiento(como estación de transferencia R1)
                // El título será cortado `<think>...` contenido,El título que ven los usuarios comienza con pensar.
                val cleaned = io.zer0.muse.transformer.stripThinkTags(completion.text)
                cleaned.removeSurrounding("\"").removeSurrounding("'").take(20)
            }.onSuccess { newTitle ->
                // v2.5.2 fix: Bloquear títulos sin sentido —— estación de transferencia/Los modelos pequeños ocasionalmente son flojos a la hora de generar "..." / "." /
                // ". . ." etc. (Los usuarios midieron que todos los títulos de las sesiones se convirtieron en elipses y la lista exportada era irreconocible),
                // Este tipo de título no se escribe, la sesión conserva el nombre predeterminado y se le cambiará el nombre automáticamente la próxima vez que salga.
                val meaningless = io.zer0.muse.data.session.isMeaninglessSessionTitle(newTitle)
                if (!meaningless) {
                    sessionRepository.renameSession(sessionId, newTitle)
                }
            }.onError { _, _ -> }
        }
    }

    /**
     * v1.42: Activador automático de compresión de contexto.
     *
     * Cuando el token está ocupado más del 80%,Historial de compresión en subprocesos IO en segundo plano,No bloquea la interfaz de usuario.
     * Sólo comprimir en memoria messages,no persistió en DB(DB Mantener el historial completo).
     */
    /**
     * v2.x Refactor S2: La cadena de compresión automática se ha extraído para [io.zer0.muse.ui.chat.ChatCompressionCoordinator].
     * Implementación original(3175-3433)Mover la nueva clase tal cual,Reserva delegación aquí —— extracción pura,El comportamiento se mantiene sin cambios.
     */
    private fun triggerAutoCompress(sessionId: String) = compressionCoordinator.triggerAutoCompress(sessionId)

    /**
     * v2.x Refactor S2: La cadena de compresión manual se ha extraído para [io.zer0.muse.ui.chat.ChatCompressionCoordinator].
     * Implementación original(3435-3607)Mover la nueva clase tal cual,Reserva delegación aquí —— extracción pura,El comportamiento se mantiene sin cambios.
     */
    fun manualCompress(updateMemoryFirst: Boolean = true) = compressionCoordinator.manualCompress(updateMemoryFirst)

    /**
     * v1.97: Ejecute el comando de barra diagonal.
     *
     * Analizar texto de entrada,Si es un comando de barra diagonal, realice la operación correspondiente y regrese true(Indica que ha sido procesado.,no debe ser enviado a LLM).
     * Devuelve falso si el comando no comienza con / o es desconocido.
     *
     * @return true Indica que se ha ejecutado como un comando de barra diagonal.(no debe ser enviado),false No indica un comando de barra diagonal
     */
    fun executeSlashCommand(text: String): Boolean {
        val cmd = SlashCommand.parse(text) ?: return false
        val sessionId = displayedSessionId()
        when (cmd) {
            SlashCommand.NEW -> {
                // Nueva sesión — Reutilizar existente createNewSession(Internamente se crea una sesión de base de datos y se cambia el estado.)
                MuseToast.show(appContext.getString(R.string.slash_command_new_done))
                createNewSession()
            }
            SlashCommand.COMPACT -> {
                // Comprimir el historial de sesiones — compresión pura,sin actualizar la memoria primero
                // F-5: Retroalimentación del estado del proceso en dos etapas — Consejos antes de empezar"comprimiendo…",manualCompress Solicitar el resultado cuando se complete
                MuseToast.show(appContext.getString(R.string.slash_command_compact_starting))
                manualCompress(updateMemoryFirst = false)
            }
            SlashCommand.RESET -> {
                // Reparación de auditoría (2.5): Deshabilitado durante la transmisión reset — Después de borrar, updateAssistant se omite por completo.,
                // El contenido en streaming no cae al disco,Los mensajes de usuario persistentes y las respuestas faltantes hacen que la conversación se interrumpa.
                if (_state.value.isStreaming) {
                    // B-31: Solicitar redacción independiente cuando se rechace,No más falsos positivos"Reiniciar"
                    MuseToast.show(appContext.getString(R.string.slash_command_reset_busy))
                    return true
                }
                val sid =
                    sessionId ?: run {
                        MuseToast.show(appContext.getString(R.string.slash_command_unknown, text))
                        return true
                    }
                // F-5: Restablecer es una eliminación asincrónica de la base de datos.,Dar retroalimentación del proceso al principio.
                MuseToast.show(appContext.getString(R.string.slash_command_reset_starting))
                viewModelScope.launch(AppDispatchers.io) {
                    // F-4: /reset Realmente restablecer la conversación — Eliminar todos los mensajes de la conversación actual
                    // (truncateFrom(fromCreatedAt=0) Eliminar createdAt>=0 todas las noticias de,Con sincronización de índice FTS);
                    // La implementación anterior solo borra la memoria sin eliminarla. DB,La recarga desde la base de datos después de la actualización da como resultado"El reinicio no tiene ningún efecto".
                    resultOf { sessionRepository.truncateFrom(sid, 0) }.onError { msg, t ->
                        Logger.w("ChatVM", "reset session messages failed: $msg", t)
                    }
                    // Borrar memoria + invalidar caché(Evite retroceder y acceder a instantáneas antiguas)
                    _messages.value = emptyList()
                    sessionMemoryCache.remove(sid)
                    // Actualizar las estadísticas de contexto después del reinicio(token volver a cero)
                    refreshContextInfo()
                    MuseToast.show(appContext.getString(R.string.slash_command_reset_cleared))
                }
            }
            SlashCommand.PIN -> {
                // F-4: /pin Cambiar a pin"actual(último artículo)Mensajes de usuario",en lugar de conversaciones fijadas.
                // Retención fijada a nivel de sesión togglePinned(setSessionPinned),Activado por una pulsación larga en la lista de sesiones de UI;
                // /pin Semántica de comandos para alinear documentos.:Mensaje fijado,Estado de almacenamiento de texto a nivel de mensaje pinnedMessageContent,
                // ChatScreen lo presenta como un banner adhesivo en la parte superior de la lista de mensajes.
                val latestUser = _messages.value.lastOrNull { it.role == MessageRole.USER }
                if (latestUser == null) {
                    MuseToast.show(appContext.getString(R.string.slash_command_unknown, text))
                    return true
                }
                // /fijar el mismo mensaje otra vez = Desanclar
                val currentlyPinned = _state.value.pinnedMessageContent == latestUser.content
                _state.update {
                    it.copy(pinnedMessageContent = if (currentlyPinned) null else latestUser.content)
                }
                MuseToast.show(appContext.getString(R.string.slash_command_pin_message_done))
            }
            SlashCommand.ARCHIVE -> {
                // Archivar la sesión actual — Reutilizar existente setSessionArchived(Cambia internamente a la sesión restante)
                val id =
                    sessionId ?: run {
                        MuseToast.show(appContext.getString(R.string.slash_command_unknown, text))
                        return true
                    }
                setSessionArchived(id, true)
                MuseToast.show(appContext.getString(R.string.slash_command_archive_done))
            }
        }
        // Borrar cuadro de entrada
        _state.update { it.copy(input = "") }
        return true
    }

    /**
     * F-4: Cancelar(/pin Banner de mensaje superior). El nivel del mensaje se mueve al estado de memoria.,Borrado por switchSession al cambiar de sesión.
     */
    fun clearPinnedMessage() {
        _state.update { it.copy(pinnedMessageContent = null) }
    }

    /**
     * F-2: Agrega un fragmento de texto a la conversación especificada como reenvío de un mensaje de usuario.
     *
     * Menú de pulsación larga desde mensajes"Adelante a la conversación"llamar:Después de seleccionar la sesión de destino,Cambiar el texto del mensaje(Mensaje de usuario original/mensaje del asistente
     * Convertir a texto sin formato)Se adjunta como un nuevo mensaje de USUARIO a la sesión de destino.[sessionRepository.appendMessage] Estará sincronizado
     * Actualiza el índice updateAt/view/FTS de la sesión de destino.
     *
     * @return true Indica una adición exitosa,false Indica que la sesión de destino no existe o que la escritura falló.
     */
    suspend fun forwardMessageToSession(targetSessionId: String, text: String): Boolean {
        val target = resultOf { sessionRepository.getSessionById(targetSessionId) }.getOrNull()
        if (target == null || target.deletedAt != null) return false
        val msg =
            UIMessage(
                id = Uuid.random(),
                role = MessageRole.USER,
                content = text,
                createdAt = System.currentTimeMillis(),
            )
        return resultOf { sessionRepository.appendMessage(targetSessionId, msg) }
            .onError { m, t -> Logger.w("ChatVM", "forwardMessageToSession: append failed: $m", t) }
            .isSuccess
    }

    /** Alternar apertura y cierre de la barra lateral. */
    fun toggleDrawer(open: Boolean) = settingsController.toggleDrawer(open)

    /** P5-G: Cambia el modo de dibujo. El marcador de posición del campo de entrada cambia después de abrirlo,send Vaya a ImageService. */
    fun toggleDrawMode() = settingsController.toggleDrawMode()

    /** v0.34: Actualizar los parámetros de dibujo actuales(Puede anular temporalmente los valores predeterminados de configuración). */
    fun updateImageGenParams(params: io.zer0.ai.image.ImageGenParams) = settingsController.updateImageGenParams(params)

    /**
     * Etapa 5: activación del interruptor Provider(Llamada al panel de conmutación del modelo inferior).
     *
     * v1.0.28 Reparación: Anotado antes"El interior será limpiado selectedModelId"Pero en realidad no está claro.,
     * Hace que el usuario cambie del Proveedor A al Proveedor B,El antiguo selectedModelId todavía apunta al modelo de A.,
     * resolveToolsAndModel Encuentre modelos que lleguen a A en todos los proveedores → La solicitud fue enviada a A por error.
     * Llamada explícita ahora saveSelectedModel(null) Borrar,Promesas de anotación de alineación.
     *
     * v1.22: Si el proveedor de destino aún no ha retirado el modelo,Disparador automático/modelos tiran.
     */
    fun setActiveProvider(providerId: String) = settingsController.setActiveProvider(providerId)

    /**
     * v1.22: manuales/Extrae automáticamente la lista de modelos ascendentes del proveedor especificado.
     * Después de que la extracción sea exitosa, actualice ProviderConfig.models y consérvelo.,En caso de error, se escribe fetchModelsError.
     *
     * v1.132: Escribir sincrónicamente después de una extracción exitosa [ModelListCache],Permitir que la página de edición ProviderSection reutilice los resultados(5 en minutos).
     */
    fun refreshModels(providerId: String) = settingsController.refreshModels(providerId)

    /**
     * Etapa 5: seleccione el modelo específico del proveedor actual(Llamada al panel de conmutación del modelo inferior).
     * Pase nulo para borrar la selección,Recurrir al primer modelo del proveedor.
     *
     * v0.51: Después de cambiar a un modelo no vacío,Reproducir mensaje de brindis"Modelo cambiado,Se generarán nuevos mensajes utilizando el nuevo modelo.(La información histórica se mantiene sin cambios.)".
     * Ir al campo de brindis independiente(no Snackbar),Evite verse abrumado por mensajes de error.
     */
    fun setSelectedModel(modelId: String?) = settingsController.setSelectedModel(modelId)

    /** v1.60-A: Configurar modelo de herramienta(null Borrar,Heredar el modelo de diálogo principal.). */
    fun setToolModel(modelId: String?) = settingsController.setToolModel(modelId)

    /**
     * v0.51: Limpiar una vez toast(Toast Llamado por la interfaz de usuario inmediatamente después de aparecer,Evite repetidos bombardeos durante la reorganización.).
     */
    fun clearToast() = settingsController.clearToast()

    /**
     * v1.0.72: Establecer sesión actual"Sin referencia a la memoria"logotipo(Opción de página de conversación en blanco).
     * persistir en la sesión,Cambiar de sesión/Reiniciar espera;Cuando está habilitado, el mensaje del sistema omite la inyección de memoria.
     */
    fun setSessionIgnoreMemory(ignore: Boolean) = sessionController.setSessionIgnoreMemory(ignore)

    /** ajustes/Eliminar la referencia al destino de respuesta. */
    fun setReplyingTo(message: UIMessage?) {
        // v1.0.72: Agregar registro para solucionar problemas"No se puede eliminar la referencia."pregunta(Si el bloque de referencia recibe un evento claro después de hacer clic en X)
        Logger.i("ChatVM", "setReplyingTo: ${if (message == null) "null(referencia clara)" else message.id}")
        _state.update { it.copy(replyingTo = message, replyQuoteOverride = null) }
    }

    /** v1.57: Establecer texto de cotización personalizado(Se utiliza para editar y recortar tarjetas de referencia.,Cite con precisión parte del contenido.). */
    fun setReplyQuoteOverride(text: String?) {
        _state.update { it.copy(replyQuoteOverride = text?.takeIf { it.isNotBlank() }) }
    }

    /**
     * v1.58: Bifurca la conversación del mensaje especificado — Crea una nueva sesión y copia el historial hasta ese mensaje,Luego cambia.
     * Los usuarios pueden explorar diferentes direcciones en una nueva sesión.,No afecta la conversación original.
     */
    fun forkSessionFromMessage(messageId: kotlin.uuid.Uuid) = sessionController.forkSessionFromMessage(messageId)

    /** Crea una nueva sesión.[assistantIdOverride] Creado con el asistente especificado cuando no está vacío(Chatea rápidamente con socios horizontalmente). */
    fun createNewSession(onReady: (() -> Unit)? = null, assistantIdOverride: String? = null) =
        sessionController.createNewSession(onReady, assistantIdOverride)

    /**
     * v1.97 gap8: Enviar texto a una nueva conversación.
     *
     * Cree atómicamente una nueva sesión, complete la entrada y active un envío,Evite que la persona que llama sea asincrónica createNewSession
     * Llamado antes de finalizar send() lo que resulta en la pérdida del mensaje.
     */
    fun sendToNewChat(text: String, onReady: (() -> Unit)? = null) = sessionController.sendToNewChat(text, onReady)

    /**
     * v1.24: Agent reiniciar contexto — Mantener asistente actual,Crear una nueva sesión vacía,
     * Deje que el Agente a largo plazo continúe la conversación desde cero,Al mismo tiempo, Toast avisa al usuario.
     */
    fun restartContext() = sessionController.restartContext()

    /** Cambie a la sesión especificada. */
    fun switchSession(sessionId: String) = sessionController.switchSession(sessionId)

    /** La reenvío de la solicitud de la bandeja de salida generada al volver a la sesión aún no ha comenzado. */
    private suspend fun requeueOutboxForSession(sessionId: String) = generationController.requeueOutboxForSession(sessionId)

    /**
     * v1.28: Establezca el modo de pestaña Agente.
     *
     * Agent Tab Llamado al entrar:Reanudar o crear una sesión de Agente independiente,No depende del currentSessionId de la tarea.
     * Al salir de la pestaña Agente(isAgentMode=false)Mensaje para reanudar la sesión de tareas.
     */
    @Suppress("LongMethod")
    fun setAgentMode(enabled: Boolean, requestedSessionId: String? = null) = sessionController.setAgentMode(enabled, requestedSessionId)

    /**
     * Abre una notificación para la sesión especificada.
     *
     * Las conversaciones normales pertenecen a tareas. Tab;Agent La sesión no está en la lista de tareas.,Debe cambiar a Agent Tab
     * y utilizar la sesión precisa llevada por la notificación id,No se puede volver a adivinar con el asistente predeterminado.
     */
    fun openSessionFromNotification(sessionId: String) {
        viewModelScope.launch {
            val target = sessionRepository.getSessionById(sessionId)
            if (target?.isAgentSession == true) {
                setAgentMode(true, requestedSessionId = sessionId)
                return@launch
            }
            val ready =
                withTimeoutOrNull(5_000L) {
                    state.first { state ->
                        state.currentSessionId != null &&
                            state.sessions.any { it.id == sessionId }
                    }
                }
            if (ready == null) {
                Logger.w("ChatVM", "Esperando el tiempo de espera de inicialización de la sesión de destino de notificación,Aún intento abrir: $sessionId")
            }
            switchSession(sessionId)
        }
    }

    /**
     * v1.0.47 P6-4: Cerrar tarjeta de aviso del modo agente(Clics del usuario"Lo tengo"llamar después).
     */
    fun dismissAgentModeHint() {
        _state.update { it.copy(agentModeHint = null) }
    }

    /**
     * v1.0.47 P6-2: Desactivar sugerencias de modelos de herramientas débiles(Se llama después de que el usuario hace clic en cerrar.).
     */
    fun dismissWeakToolHint() {
        _state.update { it.copy(weakToolHint = null) }
    }

    /**
     * v1.53-A1: Desliza hacia arriba para cargar más noticias históricas.
     *
     * Tome el mensaje creado en el más antiguo de la lista de mensajes actual como punto de anclaje,Obtener datos anteriores a este momento de la base de datos
     * MESSAGE_PAGE_SIZE mensajes,Prefijado a los mensajes.
     *
     * - Deshabilitado durante la transmisión(isStreaming=true no cargando,Evite la fluctuación de la lista que interfiere con la salida de streaming)
     * - isLoadingMore=true salto de tiempo(Prevenir disparos repetidos)
     * - hasMoreHistory=false salto de tiempo(cargado)
     * - Establecer después de que se complete la carga [ChatUiState.lastHistoryLoadCount],UI Ajuste la posición de desplazamiento después de escuchar para mantener la posición visual
     */
    fun loadMoreHistory() = messageController.loadMoreHistory()

    /**
     * v1.53-A1: Borrar [ChatUiState.lastHistoryLoadCount].
     *
     * UI en [ChatScreen] Supervisar los cambios de lastHistoryLoadCount(>0)después,sintonizar
     * [androidx.compose.foundation.lazy.LazyListState.scrollToItem] Omitir la cantidad de elementos recién insertados,
     * Evita que la posición visual salte.,Luego llame a este método para borrarlo.(Evite saltos repetidos durante la reorganización).
     */
    fun clearHistoryLoadCount() = messageController.clearHistoryLoadCount()

    /** v1.28: Obtener el asistente actual id(Se utiliza para crear sesiones de agente). */
    private fun currentAssistantId(): String = _state.value.currentAssistant?.id ?: "default"

    /** Sesión de eliminación temporal. Cambiar automáticamente al primero restante al eliminar la sesión actual;Borrar estado cuando no quedan sesiones,No se crea ninguna nueva sesión. */
    fun deleteSession(sessionId: String) = sessionController.deleteSession(sessionId)

    /** Cambie el nombre de la sesión. */
    fun renameSession(sessionId: String, title: String) = sessionController.renameSession(sessionId, title)

    /** v0.45: Alternar el estado de archivo de la sesión. Cambiar a la primera sesión restante al archivar la sesión actual;Borrar estado cuando no quedan sesiones,No se crea ninguna nueva sesión. */
    fun setSessionArchived(sessionId: String, archived: Boolean) = sessionController.setSessionArchived(sessionId, archived)

    /** Recargar lista de sesiones(Reintento del estado de error de la lista de sesiones). */
    fun retryLoadSessions() {
        viewModelScope.launch {
            _state.update { it.copy(isSessionsLoading = true, sessionsError = null) }
            try {
                val sessions = sessionRepository.observeSessions().first()
                _state.update { it.copy(sessions = sessions, isSessionsLoading = false, sessionsError = null) }
                if (_state.value.currentSessionId == null && !initializing) {
                    initializing = true
                    val target = sessions.firstOrNull()
                    if (target != null) {
                        switchSession(target.id)
                    } else {
                        createNewSession()
                    }
                }
            } catch (t: Throwable) {
                Logger.e("ChatVM", "retryLoadSessions failed", t)
                // v1.x: La cancelación de la rutina debe volver a lanzarse,no se puede mostrar como"Solicitud fallida: Job was cancelled"
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.update {
                    it.copy(
                        isSessionsLoading = false,
                        sessionsError =
                        appContext.getString(
                            R.string.err_chat_request_failed,
                            t.message ?: appContext.getString(R.string.err_chat_unknown),
                        ),
                    )
                }
            }
        }
    }

    /** Actualizar el texto del cuadro de búsqueda. Resultados vacíos cuando el texto está vacío. */
    fun updateSearchQuery(query: String) = miscCoordinator.updateSearchQuery(query)

    /** Realizar una búsqueda. Las consultas vacías se ignoran. */
    fun search() = miscCoordinator.search()

    /** Borrar búsqueda(Volver a la lista de sesiones). */
    fun clearSearch() = miscCoordinator.clearSearch()

    // ── v2.x: Búsqueda de contenido de mensajes(Tab=1)+ Saltar desplazamiento resaltado ──

    /** v2.x: Cambiar página de búsqueda Tab(0=sesión, 1=Contenido del mensaje). */
    fun switchSearchTab(tab: Int) = miscCoordinator.switchSearchTab(tab)

    /** v2.x: Realizar búsqueda de contenido de mensajes(FTS4 + snippet,Repliegue ante el fracaso LIKE). */
    fun searchMessageContent() = miscCoordinator.searchMessageContent()

    /**
     * v2.x: Establecer mensaje de destino(Haga clic para saltar desde los resultados de búsqueda).
     *
     * Lo llama SearchScreen cuando se hace clic en un elemento de mensaje,MainActivity La lógica de salto inyectada primero switchSession,
     * Llame a este método nuevamente para configurar targetMessageId / highlightedMessageId / searchHighlightQuery.
     */
    fun setTargetMessage(messageId: String?, query: String?) = miscCoordinator.setTargetMessage(messageId, query)

    /**
     * v2.x: Abrir mensaje desde los resultados de búsqueda — Cambiar de sesión primero,Espere hasta que se implemente el interruptor antes de configurar el objetivo de posicionamiento.
     *
     * Corregir condición de carrera: anteriormente switchSession(asincrónico) + setTargetMessage(inmediatamente) paralelo,
     * ChatScreen Espere el mensaje de destino en la sesión anterior y bórrelo después de un tiempo de espera de 5 segundos. targetMessageId,
     * Cuando realmente se cambia la sesión, el objetivo de posicionamiento se ha perdido.,Se muestra como"Al hacer clic en los resultados solo se regresa a la página de inicio sin posicionar.".
     */
    fun openMessageFromSearch(sessionId: String, messageId: String, query: String) {
        switchSession(sessionId)
        viewModelScope.launch {
            // Espere a que switchSession aterrice(currentSessionId convertirse en sesión objetivo),Tiempo de espera de 5 segundos y abandono del posicionamiento.
            val switched =
                withTimeoutOrNull(5000L) {
                    _state.filter { it.currentSessionId == sessionId }.first()
                    true
                } ?: false
            if (switched) {
                setTargetMessage(messageId, query)
            } else {
                Logger.w("ChatVM", "openMessageFromSearch Tiempo de espera de espera para el cambio de sesión: $sessionId")
            }
        }
    }

    /** v2.x: Consumir mensajes objetivo id(Se llama después de completar el posicionamiento del desplazamiento.,Evite los desencadenantes repetidos). */
    fun consumeTargetMessage() = miscCoordinator.consumeTargetMessage()

    /** v2.x: Borrar mensajes resaltados id(Se llama después de que finaliza el período de la ventana resaltada.,dejar de resaltar). */
    fun clearHighlightedMessage() = miscCoordinator.clearHighlightedMessage()

    /**
     * Enviar la entrada actual. texto vacío(y sin fotos)O ignorado durante la transmisión.
     * P5-G: si isDrawMode,ir [generateImage] En lugar de transmitir chat.
     * Phase 8.6: Soporte multimodal — Si las imágenes pendientes no están vacías,Adjunte la lista base64 al mensaje de USUARIO.
     * v1.28: Agent Para el modo agentSessionId,Creado automáticamente cuando no hay sesión(Agent El chat diario no depende de tareas.).
     */

    /** v1.0.92: devolución de tarjeta — El texto solicitado por el script de la tarjeta se envía como mensaje de usuario. */
    fun sendFromCard(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val sid = displayedSessionId(snapshot) ?: return
        enqueueSend(trimmed, emptyList(), sid)
    }

    /**
     * v2.6.7: Devolución de tarjeta (con estado estructurado)—— Un paso crucial para cerrar el círculo.
     *
     * con [sendFromCard] La diferencia: la tarjeta ya no sólo comprime la operación en una frase, sino que además comprime el estado interno.
     * (El valor arrastrado, el elemento seleccionado, el resultado del cálculo) se devuelven juntos en formato JSON. El anfitrión lo trata como
     * invisibles `<card_state>` La cola se añade al final del mensaje del usuario:
     *  - El modelo puede leer. → Este estado podrá ser utilizado en la siguiente ronda sin que el usuario tenga que repetirlo;
     *  - Las burbujas no se muestran → [stripCardStateFromMessage] Recórtalo en la capa de renderizado sin contaminar el diálogo.
     *
     * El motivo para no crear una nueva columna de la tabla de mensajes: la tabla de mensajes ya es DB 116 y los metadatos de una oración se migran a otra versión.
     * Los riesgos no guardan proporción con los beneficios; Naturalmente, la cola del texto se respalda, se exporta y se involucra en la adaptación contextual junto con el mensaje.
     */
    fun sendFromCard(text: String, cardId: String?, data: String?) {
        val trimmed = text.trim()
        val payload = data?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed.isEmpty() && payload == null) return
        val sid = displayedSessionId(snapshot) ?: return
        val body = if (payload == null) {
            trimmed
        } else {
            io.zer0.muse.ui.markdown.appendCardStateToMessage(
                text = trimmed.ifEmpty { appContext.getString(R.string.chat_card_state_only) },
                cardId = cardId,
                data = payload,
            )
        }
        enqueueSend(body, emptyList(), sid)
    }

    /** devolución de tarjeta — Tarjetas de contenido enriquecido guardadas como artefactos(Después de guardar, se mostrará debajo del mensaje según la asociación del mensaje.). */
    fun saveCardAsArtifact(messageId: String, language: String, content: String) {
        val sid = displayedSessionId(snapshot) ?: return
        viewModelScope.launch {
            resultOf {
                artifactRepository.upsert(
                    ArtifactEntity(
                        // ID idempotente: al hacer clic dos veces en la misma tarjeta (mismo ID de mensaje + mismo idioma) se cubrirá la misma línea.
                        // En lugar de guardar una copia más cada vez. Usar la marca de tiempo como clave principal hará“guardar”convertirse en“Agregando constantemente nuevos”.
                        id = "card_${messageId}_${language.lowercase()}",
                        sessionId = sid,
                        messageId = messageId,
                        title = language.uppercase(),
                        type = "rich_card",
                        content = content,
                        language = language,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }.onError { msg, _ -> Logger.w("ChatVM", "Error al guardar la tarjeta: $msg") }
        }
    }

    /**
     * v2.6.13: colección de tarjetas —— Añade la tarjeta a tus favoritos.
     *
     * Las tarjetas no son mensajes y la página de colección solo lee la tabla de mensajes; Así que aquí hay una línea en la "Sesión dedicada a la colección de tarjetas".
     * favorite=true mensaje (el texto es el código fuente de la tarjeta encerrada por la valla). La sesión privada ha sido archivada y no se incluirá en la lista de tareas.
     * Tampoco incluye el código fuente de la tarjeta en ningún contexto de conversación.
     *
     * @param cardId Identidad de la tarjeta (identificación del mensaje en el escenario del chat), utilizada para determinar la autenticidad
     */
    fun favoriteCard(cardId: String, language: String, content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            resultOf {
                sessionRepository.favoriteCard(cardId = cardId, language = language, content = content)
            }.onSuccess {
                // Las colecciones duplicadas también indican que han sido recopiladas: lo que el usuario quiere es“esta hecho”, en lugar de distinguir entre primera vez y repetición.
                MuseToast.show(appContext.getString(R.string.card_favorite_done))
            }.onError { msg, _ ->
                Logger.w("ChatVM", "Error al recoger la tarjeta: $msg")
                MuseToast.show(appContext.getString(R.string.card_favorite_failed, msg))
            }
        }
    }

    fun send() = generationController.send()

    /** v1.28: send implementación interna de(Enviar mensaje + iniciar transmisión). */

    /**
     * v1.200: Intente enrutar automáticamente los mensajes de los usuarios a agentes/equipos más apropiados.
     * Devolver verdadero indica que el mensaje del marcador de posición del asistente se ha enrutado y completado y la persona que llama debe omitir launchStream.
     */
    private suspend fun maybeAutoRoute(text: String, assistantMessageId: Uuid, sessionId: String): Boolean {
        if (!settings.multiAgentConfigCache.autoRoutingEnabled) return false
        val currentId = currentAssistantId()
        // v2.x: LLM Ir cuando el interruptor de enrutamiento semántico esté activado routeWithLlm,De lo contrario, siga la ruta de reglas.
        // routeWithLlm Internamente, bajará automáticamente al enrutamiento de reglas cuando el chatService no se inyecta o es anormal.
        val config = settings.multiAgentConfigCache
        val route =
            if (config.llmRoutingEnabled) {
                agentRouter.routeWithLlm(text, excludeAssistantId = currentId)
            } else {
                agentRouter.route(text, excludeAssistantId = currentId)
            }
        if (route.confidence < AUTO_ROUTE_CONFIDENCE_THRESHOLD || route.targetId.isNullOrBlank()) return false
        if (route.targetId == currentId) return false

        val targetType =
            when (route.targetType) {
                "team" -> DelegationContract.DelegationRequest.TargetType.TEAM
                else -> DelegationContract.DelegationRequest.TargetType.ASSISTANT
            }

        val request =
            DelegationContract.DelegationRequest(
                requestId = "auto-${System.currentTimeMillis()}",
                task = text,
                targetType = targetType,
                targetId = route.targetId,
                contextMessages =
                DelegationContextBuilder.build(
                    sessionMessages = _messages.value,
                    maxMessages = DelegationContextBuilder.DEFAULT_MAX_MESSAGES,
                    includeImages = false,
                ),
                timeoutSec = 120,
            )

        // v1.202: Presentación en streaming — mostrar primero"Delegar a ${name}..."Permitir que los usuarios perciban el proceso intermedio.,
        // En cambio, el resultado aparece una vez después de que finaliza la llamada sin bloqueo.isStreaming=true Deje que MessageBubble muestre un cursor de transmisión.
        val routeTargetName = route.targetName ?: route.targetId
        val pendingText =
            appContext.getString(R.string.chat_auto_route_prefix, routeTargetName) +
                "\n\nDelegar a $routeTargetName ..."
        updateAssistant(assistantMessageId, pendingText, "", null, null, null, true)

        val result = skillExecutor.delegateAgent(request)
        val output =
            buildString {
                appendLine(appContext.getString(R.string.chat_auto_route_prefix, route.targetName ?: route.targetId))
                appendLine()
                if (result.success) {
                    append(result.resultText)
                } else {
                    append(result.error ?: appContext.getString(R.string.skill_unknown_error))
                }
            }

        updateAssistant(assistantMessageId, output, "", null, null, null, false)
        _state.update { it.copy(isStreaming = false) }
        // B-1: Si la sesión se ha eliminado, se omitirá la ubicación.,Prevenir la eliminación"resurrección".
        if (!generationController.isSessionWritesSuppressed(sessionId)) {
            persistCurrentAssistant(sessionId, assistantMessageId)
        }
        return true
    }

    /**
     * Phase 8.6: Añadir fotos para enviar(Leer desde URI,girar base64).
     * @param uri fotos URI(álbum de fotos/cámara/SAF)
     * @param context usado para ContentResolver
     * @param asOcr Si se debe utilizar el reconocimiento OCR(Si true,Los resultados del reconocimiento se adjuntan al cuadro de entrada.;De lo contrario, como entrada visual)
     *
     * v1.105: Delegar a [ImageGenCoordinator.pickImage].
     */
    fun pickImage(uri: Uri, context: Context, asOcr: Boolean) {
        imageGenCoordinator.pickImage(uri, context, asOcr, ::reportError, ::addError)
    }

    /**
     * v1.135: Seleccione un video y extraiga fotogramas clave para agregarlos a la imagen que se enviará.
     * Actualmente degradado a envío de imágenes,Los modelos de visión comprenden el contenido del vídeo a través de fotogramas clave.
     */
    fun pickVideo(uri: Uri, context: Context) {
        imageGenCoordinator.pickVideo(uri, context, ::reportError)
    }

    /** Phase 8.6: Elimina la imagen que se enviará en el índice especificado.v1.105: Delegar a [ImageGenCoordinator]. */
    fun removePendingImage(index: Int) {
        imageGenCoordinator.removePendingImage(index)
    }

    /**
     * P5-G + Phase 10.2: Genera imágenes.
     *
     * Sucursal por tipo de proveedor:
     *  - OpenAI compatibles: ir ImageService(Punto final independiente /images/generaciones,Regresar URL)
     *  - Gemini:      Tome el camino multimodal de StreamChat(responseModalities Contiene image,recoger ImageDelta base64)
     *  - Anthropic:   No compatible(Claude Sin capacidades de generación de imágenes nativas)
     *
     * Gemini Los resultados del dibujo se almacenan como URI de datos. imageUrls(Conveniente para que MessageBubble se represente usando AsyncImage),
     * Depositar simultáneamente imageBase64List(Devolución de llamada de entrada multimodal).
     */
    private fun generateImage(prompt: String, sessionId: String) {
        imageGenCoordinator.generateImage(
            prompt,
            sessionId,
            ::addError,
        ) { id, content, reasoning, b64, urls, streaming ->
            updateAssistant(id, content, reasoning, b64, urls, null, streaming)
        }
    }

    /**
     * P5-E: Analizar texto después de seleccionar el documento,Agregar al cuadro de entrada(separados por delimitador).
     * apoyo TXT / Markdown / PDF(Nativo PdfRenderer).
     *
     * v1.105: Delegar a [ChatDocumentCoordinator.pickDocument].
     */
    fun pickDocument(uri: Uri, context: Context) {
        documentCoordinator.pickDocument(uri, context, ::reportError)
    }

    /** v1.136 T10: Eliminar documentos pendientes(El usuario hace clic en Eliminar en el chip InputBar.). */
    fun removePendingDocument(index: Int) {
        val docs = _state.value.pendingDocuments.toMutableList()
        if (index in docs.indices) {
            docs.removeAt(index)
            _state.update { it.copy(pendingDocuments = docs) }
        }
    }

    /**
     * v1.0.47 P5-2: Agregue grandes secciones de texto pegado como archivos adjuntos de texto [ChatUiState.pendingDocuments].
     *
     * v2.6.13: confiado a [ChatDocumentCoordinator.addPastedText] —— Texto largo pegado y subido.
     * Los archivos pasan por el mismo canal: el archivo original se coloca en el espacio de trabajo, solo se deja una cita en el mensaje y el texto ya no se inserta en el contexto de esta ronda.
     * No borre el cuadro de entrada(Preservar otro contenido que el usuario haya ingresado).
     */
    fun addPastedTextAsDocument(text: String) {
        documentCoordinator.addPastedText(text, appContext, ::reportError)
    }

    /**
     * P5-F / P6-B3: Traducir el mensaje especificado al idioma de destino.
     *
     * v1.0.30 gap4.9: prompt Construir estrategia y [TranslateViewModel.buildTranslationPrompt] unificar,
     * Admite parámetro de estilo opcional(Predeterminado"Universales"),Y llame uniformemente a stripThinkTags para procesar los resultados.
     *
     * @param targetLanguage Idioma de destino Nombre chino(Como por ejemplo"chino"/"English"/"日本語"),Predeterminado"chino"Compatible con llamadas antiguas
     * @param style estilo de traducción(Predeterminado"Universales",Alineado con TranslateViewModel.TRANSLATION_STYLES)
     * El resultado de la traducción se adjunta como un nuevo mensaje de ASISTENTE.("Traducción(idioma de destino):\n\n...").
     */
    fun translateMessage(messageId: Uuid, targetLanguage: String = "chino", style: String = "Universales") {
        if (_state.value.isStreaming || _state.value.isTranslating) return
        val target =
            _messages.value.firstOrNull { it.id == messageId }
                ?: return
        val sourceText = buildTranslationSourceText(target)
        if (sourceText.isBlank()) return

        _state.update {
            it.copy(
                isTranslating = true,
                translatingMessageId = messageId,
                errors = emptyList(),
            )
        }
        translateJob =
            viewModelScope.launch(AppDispatchers.io) {
                try {
                    // v1.0.30 gap4.9: Usar uniformemente TranslateViewModel.buildTranslationPrompt,Consistente con páginas de traducción independientes.
                    val prompt =
                        io.zer0.muse.ui.translate.TranslateViewModel.buildTranslationPrompt(
                            text = sourceText,
                            targetLanguage = targetLanguage,
                            sourceLanguage = io.zer0.muse.ui.translate.TranslateViewModel.SOURCE_AUTO,
                            style = style,
                        )
                    val messages = listOf(UIMessage(role = MessageRole.USER, content = prompt))
                    val sessionId = displayedSessionId() ?: return@launch

                    // v2.4.6: La traducción ya no abrirá otro mensaje del asistente,En cambio, depende de las traducciones del mensaje fuente.
                    // Representado por un marcador de posición de cadena vacía"Traduciendo",Transmisión de actualizaciones incrementales directamente a los mensajes de origen(UI Presentado en bloques ampliables.).
                    fun applyTranslation(text: String) {
                        updateMessages { list ->
                            list.map { m ->
                                if (m.id == target.id) {
                                    m.copy(translations = m.translations + (targetLanguage to text))
                                } else {
                                    m
                                }
                            }
                        }
                    }
                    applyTranslation("")

                    val sb = StringBuilder()
                    var streamFailure: Pair<String, ChatErrorType>? = null
                    var streamCompleted = false
                    try {
                        chatService.streamChat(messages = messages).collect { ev ->
                            if (ev is ChatStreamEvent.ContentDelta) {
                                if (streamFailure != null) return@collect
                                sb.append(ev.delta)
                                applyTranslation(sb.toString())
                            } else if (ev is ChatStreamEvent.Error) {
                                streamFailure = ev.message to classifyErrorType(ev.message, ev.throwable)
                            } else if (ev is ChatStreamEvent.StreamInterrupted) {
                                streamFailure = ev.message to ChatErrorType.NETWORK
                            } else if (ev is ChatStreamEvent.Done) {
                                streamCompleted = true
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Conservar algunos resultados recopilados al cancelar
                        throw e
                    }

                    val failure = streamFailure
                        ?: if (!streamCompleted) {
                            appContext.getString(R.string.err_chat_stream_broken) to ChatErrorType.NETWORK
                        } else {
                            null
                        }
                    if (failure != null) {
                        // v2.4.6: interrumpir/Mantener la traducción parcial no vacía que se muestra en caso de error(No se pierde el contenido que el usuario ya ha visto),
                        // solo claro"Traduciendo"marcador de posición vacío.
                        val partial = sb.toString().trim()
                        updateMessages { list ->
                            list.map { m ->
                                if (m.id != target.id) {
                                    m
                                } else if (partial.isEmpty()) {
                                    m.copy(translations = m.translations - targetLanguage)
                                } else {
                                    m.copy(translations = m.translations + (targetLanguage to partial))
                                }
                            }
                        }
                        _state.update {
                            it.copy(
                                errors = listOf(
                                    ChatError(
                                        type = failure.second,
                                        message = ErrorMessages.resolve(appContext, failure.first),
                                    ),
                                ),
                                isTranslating = false,
                                translatingMessageId = null,
                            )
                        }
                        return@launch
                    }

                    val translated = io.zer0.muse.transformer.stripThinkTags(sb.toString()).trim()
                    if (translated.isEmpty()) {
                        // Limpiar el espacio vacío,Cambiar al mensaje de error
                        updateMessages { list ->
                            list.map { m ->
                                if (m.id == target.id) m.copy(translations = m.translations - targetLanguage) else m
                            }
                        }
                        _state.update {
                            it.copy(
                                errors =
                                listOf(
                                    ChatError(
                                        type = ChatErrorType.UNKNOWN,
                                        message =
                                        appContext.getString(
                                            R.string.err_chat_translate_empty,
                                        ),
                                    ),
                                ),
                                isTranslating = false,
                                translatingMessageId = null,
                            )
                        }
                        return@launch
                    }
                    // v2.4.6: cayendo a la fuente translations(Texto final limpio)Y persistir el mensaje fuente
                    applyTranslation(translated)
                    _state.update {
                        it.copy(
                            isTranslating = false,
                            translatingMessageId = null,
                        )
                    }
                    val persistedSource = _messages.value.firstOrNull { it.id == target.id }
                    if (persistedSource != null) {
                        sessionRepository.upsertMessage(sessionId, persistedSource)
                    }
                    // v1.0.88 (S-4): Almacenamiento en caché no válido después de que la traducción se coloque en el disco — De lo contrario, cambie y vuelva a acceder a la instantánea anterior.
                    sessionMemoryCache.remove(sessionId)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // v1.80 (H-CVM1): La cancelación de la rutina debe volver a lanzarse,evitar la destrucción stop()/switchSession() Semántica
                    throw e
                } catch (t: Exception) {
                    // v1.80 (L-CVM3): catch Throwable Cambiar a catch Exception,Evite capturar OOM/StackOverflow, etc. Error
                    Logger.e("ChatVM", "translate failed", t)
                    _state.update {
                        it.copy(
                            errors =
                            listOf(
                                ChatError(
                                    type = ChatErrorType.UNKNOWN,
                                    message =
                                    appContext.getString(
                                        R.string.err_chat_translate_failed,
                                        t.message ?: "",
                                    ),
                                ),
                            ),
                            isTranslating = false,
                            translatingMessageId = null,
                        )
                    }
                }
            }
    }

    /**
     * Aplicar edición de usuario (P0 Árbol de diálogo): guarda el texto modificado como una nueva variante de usuario,
     * Mantener viejas preguntas/Responda desde el asistente anterior, cree una nueva versión de usuario e inicie un nuevo flujo de respuesta.
     */
    fun applyUserEdit(messageId: String, newContent: String) {
        if (_state.value.isStreaming) return
        val sessionId = displayedSessionId() ?: return
        val tree = _conversationTree.value
        val target =
            tree.userNodes.asSequence()
                .flatMap { it.variants.asSequence() }
                .map { it.message }
                .firstOrNull { it.id.toString() == messageId && it.role == MessageRole.USER }
                ?: return
        val edit = tree.editUserMessage(target.id, newContent) ?: return
        _conversationTree.value = edit.tree
        // La lista es la proyección de ConversationTree y debe reemplazarse en su totalidad aquí; cambiar para fusionar creará mensajes que no existen en el árbol.
        // Utilizado por mergeRebuildMessages cuando se reconstruyen ramas, desgarrando estructuras variantes.
        _messages.value = edit.tree.displayMessages
        _state.update {
            it.copy(
                input = "",
                hasDraft = false,
                errors = emptyList(),
                isStreaming = true,
                isWaitingFirstToken = true,
            )
        }
        sessionMemoryCache.remove(sessionId)
        edit.newAssistantPlaceholder?.let { placeholder ->
            // Los mensajes de rama se deben colocar correctamente en la biblioteca antes de iniciar la transmisión para evitar que la confirmación de turno se escriba antes que la variante principal/usuario.
            viewModelScope.launch {
                val persisted =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            edit.newUserMessage?.let { sessionRepository.upsertMessage(sessionId, it) }
                            sessionRepository.upsertMessage(sessionId, placeholder)
                            edit.newUserMessage?.variantGroupId?.let { groupId ->
                                edit.newUserMessage?.variantCount?.let { count ->
                                    sessionRepository.updateVariantCount(groupId, count)
                                }
                            }
                        }.onFailure { e -> Logger.e("ChatVM", "applyUserEdit persist failed", e) }.isSuccess
                    }
                if (persisted) {
                    launchStream(placeholder.id, sessionId, isNewBranch = true)
                } else {
                    _state.update { it.copy(isStreaming = false, isWaitingFirstToken = false) }
                }
            }
        }
    }

    fun editAssistantMessage(messageId: Uuid, newContent: String) = messageController.editAssistantMessage(messageId, newContent)

    /**
     * B7-04: La generación continúa después de la interrupción del streaming.
     *
     * Reutilizar la última tira [interrumpido] Mensajes del asistente etiquetados,Después de quitar la marca, use
     * resumeFromText Cómo reanudar la escritura desde el punto de interrupción,No se agregarán nuevos mensajes.
     */
    fun continueGeneration() = generationController.continueGeneration()
    /**
     * Regenerar la última respuesta del asistente: Mantener las respuestas antiguas como ramas,Crea una nueva sucursal y vuelve a solicitar.
     * Sólo disponible si el último es asistente y no streaming.
     */

    /**
     * Regenerar la última respuesta del asistente bajo la variante de usuario actual (P0 árbol de diálogo).
     * Mantener respuestas antiguas como variantes del asistente,Cree una nueva variante de asistente bajo la variante de usuario actual y solicítela nuevamente.
     */
    fun regenerateLastAssistant() = generationController.regenerateLastAssistant()

    /**
     * Cambiar rama de mensaje:El usuario utiliza las flechas izquierda y derecha de BranchSelector para cambiar entre varias versiones de respuestas del asistente en la misma ubicación.
     */

    /**
     * Cambiar variaciones de preguntas de usuario (P0 árbol de diálogo).
     */
    fun selectUserVariant(userGroupId: String, variantIndex: Int) = messageController.selectUserVariant(userGroupId, variantIndex)

    /**
     * Alternar variante de respuesta del asistente (P0 Árbol de diálogo): con alcance únicamente al grupo de asistentes especificado en la variante de usuario actual.
     */
    fun selectAssistantVariant(userGroupId: String, assistantGroupId: String, index: Int) =
        messageController.selectAssistantVariant(userGroupId, assistantGroupId, index)

    /**
     * Llamada a la herramienta de aprobación:El usuario aprueba las llamadas de herramientas pendientes.
     */
    fun approveToolCall(toolCallId: String) = toolController.approveToolCall(toolCallId)

    /**
     * F-37: Las herramientas de aprobación son llamadas e inyectadas por la persona que llama(Como la puerta de enlace del host)Cualquier parámetro proporcionado anula la asignación.
     * Se utiliza para la aprobación web respondida: después de que el usuario completa los parámetros en la página web,El valor de cobertura se pasa aquí de forma transparente y se pasa a través de
     * [io.zer0.muse.tools.ToolApprovalState.Approved.argOverrides] Combinado con los parámetros de la herramienta.
     */
    fun approveToolCallWithOverrides(toolCallId: String, argOverrides: Map<String, String>) =
        toolController.approveToolCallWithOverrides(toolCallId, argOverrides)

    /**
     * v1.x: Establezca el valor de cobertura del mapa de referencia para que se aprueben las llamadas de herramientas.
     *
     * Por ToolApprovalCard en"Seleccionar imagen"disparador de botón:Después de que el usuario selecciona una imagen del álbum,
     * UI Convierta la imagen en URI de datos y envíela aquí para guardarla. [PendingToolApproval.referenceImageOverride],
     * Aprobado por [approveToolCall] Inyecte argOverrides.
     *
     * @param dataUri en forma de "data:image/jpeg;base64,...";Pase nulo para borrar las imágenes seleccionadas
     */
    fun setToolApprovalReferenceImage(toolCallId: String, dataUri: String?) =
        toolController.setToolApprovalReferenceImage(toolCallId, dataUri)

    /**
     * Actualizar la herramienta solicitada para aprobación"siempre permitido"Consultar estado.
     */
    fun setToolApprovalAlwaysAllow(toolCallId: String, alwaysAllow: Boolean) =
        toolController.setToolApprovalAlwaysAllow(toolCallId, alwaysAllow)

    /**
     * v1.0.16: Actualizar la herramienta solicitada para aprobación"Aprobar todo durante este período de apertura"Consultar estado.
     * F-AUDIT: Antes solo escribía PendingToolApproval.appRunAllowAll(nadie lee),Los botones son inútiles;
     * Ahora escribe al mismo tiempo. appRunAllowAllTools,dejar resolve() realmente leo"Todos están liberados para esta carrera.".
     */
    fun setToolApprovalAppRunAllowAll(toolCallId: String, allowAll: Boolean) =
        toolController.setToolApprovalAppRunAllowAll(toolCallId, allowAll)

    /**
     * Denegar llamada a herramienta:El usuario rechaza la llamada de la herramienta pendiente de aprobación.
     */
    fun denyToolCall(toolCallId: String, reason: String) = toolController.denyToolCall(toolCallId, reason)

    /**
     * P3: Establece el modo de permiso de la herramienta para la sesión actual.
     */
    fun setSessionPermissionMode(mode: SessionPermissionMode) = toolController.setSessionPermissionMode(mode)

    /**
     * v1.x: Obtener la sesión actual utilizada para la aprobación de herramientas id(Agent modo tomar agentSessionId,De lo contrario toma currentSessionId).
     *
     * Clave utilizada para el almacenamiento en caché temporal a nivel de sesión,con [setSessionPermissionMode] La lógica de decisión de la sesión sigue siendo coherente.
     */
    private fun currentSessionIdForApproval(): String? {
        return if (_state.value.isAgentMode) _state.value.agentSessionId else _state.value.currentSessionId
    }

    /**
     * v1.x: Estrategia persistente de una sola herramienta(por tarjeta de aprobación"siempre permitido"disparador de botón).
     *
     * Desvinculado de esta aprobación:La estrategia de persistencia primero cuando se hace clic en el botón,Luego vaya a onApprove para manejar esta llamada.
     */
    fun persistToolPolicy(toolCallId: String, policy: ToolApprovalPolicy) = toolController.persistToolPolicy(toolCallId, policy)

    /**
     * v1.x: Agregue la herramienta al conjunto temporal permitido para la sesión actual(No vuelvas a preguntar en esta conversación.).
     *
     * Por ToolApprovalCard en"Esta sesión permite"disparador de botón:
     *  1. Agregar nombre de herramienta [SessionPermissionStore] caché a nivel de sesión(solo memoria,No persistente)
     *  2. En sesiones posteriores, la herramienta llama a Auto y lo ejecuta directamente.,No más aparecer la tarjeta de aprobación
     *  3. Cambiar de sesión/Al crear una nueva sesión por [switchSession] / [createNewSession] Limpiar caché de sesión anterior
     *
     * Nota:Este método solo actualiza el caché.,No procesar elementos pendientes de aprobación actuales —— La convocatoria actual todavía la realiza [approveToolCall]
     * Manejado a través de devolución de llamada onApprove(con"siempre permitido"Los botones tienen el mismo patrón.:onPersistPolicy + onApprove).
     *
     * @param toolCallId Llamada de herramienta pendiente de aprobación id
     */
    fun allowToolForSession(toolCallId: String) = toolController.allowToolForSession(toolCallId)

    /** La sesión a la que pertenece la convocatoria a aprobar,Evite que los resultados de la aprobación se transfieran a la sesión actual después de cambiar de sesión. */
    private val toolApprovalSessions = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Registros de aprobación esperando en segundo plano,Restaurar tarjetas UI al regresar a la sesión correspondiente. */
    private val pendingToolApprovalRecords = java.util.concurrent.ConcurrentHashMap<String, PendingToolApproval>()

    /**
     * CHAT-08: Si el tiempo de espera de aprobación está suspendido(El usuario se dobla para leer "N El período para el "Artículo Pendiente de Aprobación" es true).
     * Mientras está en pausa [requestToolApprovalForSession] Congelación de la cuenta regresiva de los 30,Evite que las aprobaciones sean rechazadas automáticamente cuando los usuarios las lean.
     */
    @Volatile
    private var approvalTimeoutPaused = false

    /**
     * CHAT-08: Llamado por la interfaz de usuario — Pausa durante la lectura de la tarjeta de aprobación doblada/Tiempo de espera de aprobación de recuperación.
     */
    fun setApprovalTimeoutPaused(paused: Boolean) {
        approvalTimeoutPaused = paused
    }

    /** Cancelar todas las llamadas de herramientas pendientes(Detención a nivel de aplicación o uso de limpieza de prueba). */
    private fun cancelAllPendingApprovals() {
        _state.update { it.copy(pendingToolApprovals = emptyList()) }
        for ((toolCallId, deferred) in toolApprovalResults) {
            deferred.complete(ToolApprovalState.Denied("Generation stopped"))
            viewModelScope.launch {
                resultOf { PendingToolCallStore.updateState(toolCallId, "ABORTED", "generation_stopped") }
                    .onError { msg, _ -> Logger.w("ChatVM", "El estado de cancelación de aprobación no pudo realizar el pedido: $msg") }
            }
            toolApprovalSessions.remove(toolCallId)
            pendingToolApprovalRecords.remove(toolCallId)
        }
        toolApprovalResults.clear()
    }

    private fun cancelPendingApprovalsForSession(sessionId: String?) {
        if (sessionId.isNullOrBlank()) {
            cancelAllPendingApprovals()
            return
        }
        val ids =
            toolApprovalSessions
                .filterValues { it == sessionId }
                .keys
                .toList()
        _state.update { state ->
            state.copy(
                pendingToolApprovals = state.pendingToolApprovals.filterNot { it.toolCallId in ids },
            )
        }
        ids.forEach { toolCallId ->
            toolApprovalResults.remove(toolCallId)?.complete(ToolApprovalState.Denied("Generation stopped"))
            viewModelScope.launch {
                resultOf { PendingToolCallStore.updateState(toolCallId, "ABORTED", "generation_stopped") }
                    .onError { msg, _ -> Logger.w("ChatVM", "El estado de cancelación de aprobación no pudo realizar el pedido: $msg") }
            }
            toolApprovalSessions.remove(toolCallId)
            pendingToolApprovalRecords.remove(toolCallId)
        }
    }

    private fun restorePendingApprovalsForSession(sessionId: String) {
        val records =
            pendingToolApprovalRecords
                .filter { (toolCallId, _) -> toolApprovalSessions[toolCallId] == sessionId }
                .values
                .toList()
        _state.update { it.copy(pendingToolApprovals = records) }
    }

    /**
     * Solicitar llamada a la herramienta de aprobación del usuario,Cuelgue hasta que el usuario tome una decisión.
     *
     * P3: Tres factores integrales determinan el estado de aprobación:
     *  - Estrategia de persistencia de una sola herramienta([ToolConfigStore])
     *  - Modo de permiso de sesión actual([SessionPermissionMode])
     *  - Nivel de riesgo propio de la herramienta([ToolRiskLevel])
     * en última instancia por [ToolPermissionResolver] Análisis unificado.
     */
    override suspend fun requestToolApproval(
        toolName: String,
        toolCallId: String,
        argsPreview: String,
        args: Map<String, Any?>,
    ): ToolApprovalState {
        val sessionId =
            currentSessionIdForApproval()
                ?: return ToolApprovalState.Denied("No active chat session")
        return requestToolApprovalForSession(sessionId, toolName, toolCallId, argsPreview, args)
    }

    /**
     * v2.x Refactor S4: Aprobación de herramienta extraída a [io.zer0.muse.ui.chat.ChatToolApprovalCoordinator].
     * Implementación original(4218-4331)Mover la nueva clase tal cual,Reserva delegación aquí —— extracción pura,El comportamiento se mantiene sin cambios.
     */
    private suspend fun requestToolApprovalForSession(
        sessionId: String,
        toolName: String,
        toolCallId: String,
        argsPreview: String,
        args: Map<String, Any?>,
    ): ToolApprovalState = toolApprovalCoordinator.requestToolApprovalForSession(
        sessionId,
        toolName,
        toolCallId,
        argsPreview,
        args,
    )

    /**
     * v0.29 P0-3: Exportar la sesión actual como texto Markdown(para compartir/Exportar).
     *
     * v1.105: Delegar a [ChatExportCoordinator.exportSessionAsMarkdown].
     */
    suspend fun exportSessionAsMarkdown(): String {
        return exportCoordinator.exportSessionAsMarkdown()
    }

    /** Función4: Exporte la sesión actual como JSON. */
    suspend fun exportSessionAsJson(): String {
        return exportCoordinator.exportSessionAsJson()
    }

    /** Función4: Exporte la sesión actual como texto sin formato. */
    suspend fun exportSessionAsPlainText(): String {
        return exportCoordinator.exportSessionAsPlainText()
    }

    /** Función4: Entrada de exportación unificada. */
    suspend fun exportSession(format: io.zer0.muse.ui.chat.ExportFormat): Pair<String, String> {
        return exportCoordinator.exportSession(format)
    }

    /**
     * Exportar la sesión actual a un solo archivo HTML(Imágenes CSS + base64 en línea + highlight.js CDN).
     *
     * Delegar a [ChatExportCoordinator.exportSessionAsHtml].
     */
    suspend fun exportSessionAsHtml(): String {
        return exportCoordinator.exportSessionAsHtml()
    }

    /**
     * Exportar la sesión actual a un archivo PDF(Android Nativo PdfDocument,A4 Paginación).
     *
     * Delegar a [ChatExportCoordinator.exportSessionAsPdf].
     * El archivo devuelto se encuentra en cacheDir/export/,Expuesto a intenciones de compartir a través de FileProvider.
     *
     * @param context Android Context(para leer cacheDir)
     */
    suspend fun exportSessionAsPdf(context: android.content.Context): java.io.File {
        return exportCoordinator.exportSessionAsPdf(context)
    }

    /**
     * Inicie una solicitud de transmisión.history Obtenga los mensajes actuales y elimine el asistente de marcador de posición.
     * memory Las notificaciones de inyección + ticker se manejan de manera uniforme aquí,Para reutilización mediante envío/regeneración.
     *
     * Phase 7: Acceda a la función que llama al bucle de llamada de herramienta —
     *  1. Enviar mensajes + herramientas a LLM
     *  2. recoger ContentDelta / ReasoningDelta / ToolCallDelta
     *  3. Si se acumula al final del flujo toolCalls,herramienta de ejecución → Mensaje de HERRAMIENTA de reposición → Solicitar nuevamente(Hasta 25 rondas de bucle anti-muerte.,v1.52 Promovido de 5)
     *  4. Si no hay llamadas a herramientas, finalizará normalmente.
     *
     * Phase 8.2: Configuración del asistente de acceso —
     *  - Inyectar systemPrompt como mensaje de SISTEMA(poner primero)
     *  - inyectar presetMessages(Diálogo predeterminado,Poner después del SISTEMA y antes del mensaje del usuario)
     *  - prensa [AssistantEntity.contextMessageSize] Truncar historial(Obtenga los N elementos más recientes)
     *  - usar Assistant.temperature / maxTokens / reasoningLevel pasar por ChatService
     *  - Presione Assistant.memoryEnabled / enableTimeReminder para controlar el interruptor de tubería del transformador
     *  - Manejar Assistant.messageTemplate a través de TemplateTransformer {{var}} variable
     */
    private fun launchStream(assistantId: Uuid, sessionId: String, isNewBranch: Boolean = false, continueFrom: UIMessage? = null) =
        generationController.launchStream(assistantId, sessionId, isNewBranch, continueFrom)

    // v1.0.27 Phase 4-A.1: StreamRunState dibujado chat/StreamRunState.kt (internal class)
    // Compartido por ChatStreamCoordinator con esta clase,catch El bloque todavía es accesible. state.builder / state.unmaskPii

    // ===== Phase A: preparación de historia =====
    // v1.0.27 Phase 4-A.2: prepareHistory dibujado ChatStreamCoordinator

    // ===== Phase B: system prompt mensaje ensamblado + prefijo(Con inyección RAG)=====
    private suspend fun buildSystemPromptForStream(state: StreamRunState) = generationController.buildSystemPromptForStream(state)

    // ===== Phase C+D: Transformer tubo =====
    // v1.0.27 Phase 4-A.2: applyTransformers dibujado ChatStreamCoordinator

    // ===== Phase E: Definición de herramientas + análisis de modelos. =====
    // v1.0.27 Phase 4-A.2: resolveToolsAndModel dibujado ChatStreamCoordinator

    // ===== Phase F: PII Guard cubrir =====
    // v1.0.27 Phase 4-A.2: applyPiiGuard dibujado ChatStreamCoordinator

    // ===== Phase G: ayuda visual =====
    // v1.0.27 Phase 4-A.2: prepareVisionContext dibujado ChatStreamCoordinator

    /**
     * v2.x Etapa de adelgazamiento de herramientas.1:Recopilado del historial de sesiones"herramientas utilizadas"Colección pegajosa.
     *
     * ASSISTANT noticias [UIMessage.toolCalls] Es decir, esta conversación es real.(o probado)Herramientas llamadas;
     * Escanee solo el más reciente [STICKY_HISTORY_SCAN_MESSAGES] Artículo,No se realizan exploraciones completas durante sesiones largas.
     */
    private fun collectStickyToolNames(history: List<UIMessage>): Set<String> {
        val names = mutableSetOf<String>()
        history.asReversed().take(STICKY_HISTORY_SCAN_MESSAGES).forEach { message ->
            message.toolCalls?.forEach { call -> names += call.name }
        }
        return names
    }

    // ===== Phase H: Bucle de llamada de herramienta(Contiene transmisión streamRound)=====
    // Devuelve verdadero para indicar una finalización exitosa(puede continuar finalizeResponse),false Indica que el error se ha solucionado y se debe omitir la finalización.
    /**
     * v2.x Refactorización S1: el cuerpo del bucle de la herramienta se ha extraído en su totalidad [io.zer0.muse.ui.chat.ChatToolLoopRunner].
     *
     * Implementación original(L4746-L6137,1392 OK,Contiene incrustado object : ToolLoopHost)Mover la nueva clase tal cual,
     * Deja aquí una línea de delegación —— extracción pura,El comportamiento se mantiene sin cambios.
     */
    private suspend fun runToolLoop(state: StreamRunState): Boolean = toolLoopRunner.run(state)

    // ===== Phase I: Refinamiento(Limpieza de estado/información de depuración/notificación / memory ticker)=====
    private suspend fun finalizeResponse(state: StreamRunState) = generationController.finalizeResponse(state)

    /**
     * v1.97: Cortar páginas/Cambiar de sesión/Salga de la transmisión al cambiar de pestaña UI,No detiene la generación de fondo.
     *
     * con [stop] la diferencia:
     * - stop():El usuario se detiene activamente,Cancelar conexión SSE + generar corrutina
     * - detachStreaming():Gatillo de corte de página,El cierre generado continúa ejecutándose en appScope
     *   - updateAssistant porque index==-1 Saltar en silencio(No actualizar la sesión de error messages)
     *   - persistCurrentAssistant Utilice contenido de creación para colocarlo directamente en el mercado(no depende de _messages.value)
     *   - Las notificaciones siguen actualizándose normalmente.(notificationManager no depende de _state)
     *   - Cargue el contenido más reciente de la base de datos al volver a la sesión original(Incluye placa de caída intermedia)+ restaurar isStreaming
     */
    private fun detachStreaming() = streamCoordinator.detachStreaming()

    /** punto de usuario"detenerse". */
    fun stop() = generationController.stop()

    /**
     * A8 interjección de transmisión:Interrumpe la generación actual e inmediatamente redirige la generación con el contenido del cuadro de entrada.
     *
     * stop() Limpieza sincrónica isStreaming(Ver arriba),posteriormente send() La verificación canStartGeneration
     * (text No vacío && !isStreaming)puedes pasar;El contenido generado antes de la interrupción lo proporciona el streaming.
     * catch(CancellationException) bloquear con [interrumpido] Marca la persistencia.
     */
    fun interject() {
        stop()
        send()
    }

    /**
     * Reanudar la carga desde el punto de interrupción:Reanuda las llamadas de herramientas pendientes para la sesión especificada.
     *
     * escena desencadenante:El usuario detuvo manualmente la transmisión durante la última sesión/la aplicación falló/el proceso finalizó,
     * Como resultado, las llamadas a herramientas decididas por LLM no se han ejecutado. Aviso mediante banner la próxima vez que ingrese a esta sesión,
     * punto de usuario"Reanudar la ejecución"El botón activa esta función.
     *
     * Proceso de ejecución:
     *  1. de [PendingToolCallStore] Obtener toda la sesión pending(Ordenar por creadoEn ascendente)
     *  2. Ejecutar en secuencia(skill ir SkillExecutor,Las herramientas locales van ToolRegistry)
     *     - Reutilizar [TOOL_TIMEOUT_MS] tiempo de espera;Los resultados completos de la herramienta se conservan.,Los resultados a largo plazo se manejan mediante el presupuesto contextual.
     *  3. Cada resultado de herramienta se construye como un mensaje de HERRAMIENTA.(mantener original [PendingToolCallStore.PendingToolCall.toolCallId],
     *     Deje que LLM pueda hacer frente a),Persistir en la base de datos y agregar a [_messages.value]
     *  4. Después de completar toda la ejecución,Borrar los registros pendientes de esta sesión de PendingToolCallStore
     *  5. Agregar un mensaje de marcador de posición de ASISTENTE vacío,sintonizar [launchStream] Deje que LLM continúe respondiendo según los resultados de la herramienta
     *
     * tolerancia al fallo:No ejecutar una sola herramienta no afecta a otras herramientas,El resultado del error todavía se construye como un mensaje de HERRAMIENTA que se repone en LLM,
     * Deje que LLM decida si volver a intentarlo o cambiar de plan(Consistente con las llamadas normales a herramientas de transmisión).
     *
     * @param chatId sesión id(debe ser igual a [_state.value.currentSessionId])
     */

    /**
     * P0-6: Antes de la recuperación del punto de interrupción, solicite la aprobación de nueva ejecución para recuperar una única herramienta.
     *
     * fondo:Al persistir"Después de guardar y antes de escribir el estado de aprobación"llamada muerta,es [PendingToolCallStore.PendingToolCall.executionState]
     * Sigue siendo el predeterminado EXECUTING(en vez de APPROVAL_PENDING),Ejecutar directamente cuando se restablezca la implementación anterior. → Las herramientas de alto riesgo pasan por alto la aprobación.
     * Vuelva a ejecutar aquí [ToolPermissionResolver](Modo de sesión + estrategia parametrizada + nivel de riesgo),La semántica es consistente con las llamadas en línea.:
     *  - juez Pending → Solicite la tarjeta de aprobación nuevamente(await decisión del usuario)
     *  - juez Denied → Descartar directamente y registrar
     *  - juez Auto/Approved → Liberación para ejecución
     *
     * @return null Indica liberación para ejecución.;No nulo es"Ejecución no permitida"estado final(Denied/Answered),La persona que llama descarta el registro y se salta
     */
    private suspend fun recheckApprovalForResume(chatId: String, pending: PendingToolCallStore.PendingToolCall): ToolApprovalState? {
        val args = parseToolArgs(pending.arguments)
        // P0-6: tomar riesgos ToolPermissionResolver.riskLevelFor(Tabla explícita + fuente única de verdad para la inferencia de prefijos,
        // P0-3),No tome el valor de la cuenta registrada —— Cuando los valores registrados están desconectados de tablas explícitas(Por ejemplo, en el modo CONFIANZA, la herramienta ALTA se registra como
        // NORMAL)todavía irá Auto,La reanudación del nuevo juicio fue en vano.
        val risk = ToolPermissionResolver.riskLevelFor(pending.toolName)
        val perToolPolicy = toolConfigStore?.getConfiguredPolicy(pending.toolName)
        val displayedSessionId = currentSessionIdForApproval()
        val mode =
            if (displayedSessionId == chatId) {
                _state.value.sessionPermissionMode
            } else {
                sessionPermissionStore.getMode(chatId, settings.defaultSessionPermissionModeFlow.first())
            }
        val resolved = ToolPermissionResolver.resolve(pending.toolName, risk, mode, perToolPolicy, args)
        return when (resolved) {
            is ToolApprovalState.Pending -> {
                // P0-6: Solicite la tarjeta de aprobación nuevamente — Reutilizar el enlace de aprobación en línea(await decisión del usuario)
                requestToolApprovalForSession(
                    sessionId = chatId,
                    toolName = pending.toolName,
                    toolCallId = pending.toolCallId,
                    argsPreview = pending.arguments.take(MAX_TOOL_APPROVAL_ARGS_CHARS),
                    args = args,
                )
            }
            is ToolApprovalState.Denied, is ToolApprovalState.Answered -> resolved
            is ToolApprovalState.Auto, is ToolApprovalState.Approved -> null
        }
    }

    /** P0-6: El parámetro de la herramienta de análisis JSON es Map<String, String>(Valor de cadena de solo lectura de estrategia parametrizada). */
    private fun parseToolArgs(arguments: String): Map<String, Any?> = runCatching {
        val element = io.zer0.common.AppJson.parseToJsonElement(arguments)
        (element as? JsonObject)?.mapValues { (_, value) ->
            (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
        } ?: emptyMap()
    }.getOrNull() ?: emptyMap()

    /**
     * v2.x Refactor S5: las llamadas a herramientas que se restaurarán se han extraído a [io.zer0.muse.ui.chat.ChatToolResumeCoordinator].
     * Implementación original(4467-4657)Mover la nueva clase tal cual,Reserva delegación aquí —— extracción pura,El comportamiento se mantiene sin cambios.
     */
    fun resumePendingToolCalls(chatId: String) = toolResumeCoordinator.resumePendingToolCalls(chatId)

    /**
     * Reanudar la carga desde el punto de interrupción:Descarta todas las llamadas a herramientas pendientes para la sesión especificada.
     *
     * El usuario hace clic en el banner"descartar"Se activa cuando se presiona el botón. Borrar los registros de sesión en PendingToolCallStore,
     * No se ejecutan herramientas,Banner Oculto. Los mensajes persistentes de ASSISTANT tool_calls permanecen en la base de datos
     * (como parte de una historia conversacional),LLM La próxima vez que responda, es posible que vea que ha emitido herramientas_calls pero no hay respuesta.
     * TOOL respuesta — Este es el comportamiento esperado del descarte iniciado por el usuario.,LLM Normalmente cambiaré mi plan y continuaré.
     *
     * @param chatId sesión id(debe ser igual a [_state.value.currentSessionId])
     */
    fun discardPendingToolCalls(chatId: String) {
        viewModelScope.launch {
            resultOf { PendingToolCallStore.clearForChat(chatId) }
                .onError { msg, t -> Logger.w("ChatVM", "discardPendingToolCalls falló: $msg", t) }
            _state.update { it.copy(pendingToolCallCount = 0) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        release()
    }

    /**
     * Liberar recursos como TTS/ASR.
     *
     * v1.92: ChatViewModel Cambiar a single{} después de onCleared nunca se llama,
     * Llamado explícitamente por el observador ON_STOP de ProcessLifecycleOwner de MuseApp.
     */
    fun release() {
        releaseInternal(notifySessionEnd = true)
    }

    /**
     * Los recursos de voz se liberan cuando la aplicación cambia a segundo plano, pero la sesión actual no se trata como la memoria de notificación de fin de sesión LLM.
     * Es posible que el proceso aún continúe generándose después de regresar al primer plano, y el ciclo de vida en segundo plano no debe poner en cola completeText repetidamente.
     */
    fun releaseForBackground() {
        releaseInternal(notifySessionEnd = false)
    }

    private fun releaseInternal(notifySessionEnd: Boolean) {
        // Modo de conversación de voz:Cancelar la rutina de bucle y liberar recursos ASR/TTS(Evite grabar en segundo plano/leer en voz alta)
        stopVoiceConversation()
        // Phase 8.7: ViewModel Deja de destruir TTS(Evite continuar leyendo en voz alta después de salir de la página.)
        stopTts()
        // v1.53: Borrar devolución de llamada de estado TTS,Evite las pérdidas de memoria causadas por el hecho de que TtsManager singleton retenga devoluciones de llamadas de ViewModels destruidos.
        ttsManager.onStateChange = null
        if (notifySessionEnd) notifySessionEndForCurrent()
        // v1.91: lanzamiento de transmisión ASR Controller(AudioRecord / WebSocket / corrutina scope)
        // v1.105: Delegar a ChatAudioCoordinator
        audioCoordinator.disposeAsr()
        // v1.x: Liberar el recuento de referencias de la sesión actual.(Emparejado con adquirir en switchSession/createNewSession)
        currentSessionIdForApproval()?.let { sessionManager.release(it) }
    }

    /**
     * v1.0.29: Se llama cuando la aplicación cambia a segundo plano.
     *
     * Si actualmente hay una compilación de transmisión en progreso,Iniciar notificación de servicio en primer plano(Informar a los usuarios del progreso de la generación de fondo.,
     * Al mismo tiempo, mantenga la prioridad de primer plano del proceso para evitar que el sistema lo recicle.). No mostrar notificaciones cuando esté en primer plano(evitar"generando"molestar).
     */
    fun onAppBackground() {
        // v2.6.8: El banner de error no aparece frente al usuario después de salir del fondo,Se debe suspender la cuenta atrás de desaparición automática.(ver [scheduleAutoDismiss]).
        appForegroundFlow.value = false
        val activeGenerations = chatGenerationManager.activeGenerations.value
        if (activeGenerations.isNotEmpty()) {
            val latest = activeGenerations.values.maxByOrNull { it.lastUpdatedAt }
            Logger.i(
                "ChatViewModel",
                "background keep-alive started | tasks=${activeGenerations.size} | " +
                    "latestSession=${latest?.sessionTitle ?: "unknown"}",
            )
            runCatching { ChatGenerationService.start(appContext) }
                .onFailure { Logger.w("ChatViewModel", "El servicio en primer plano no pudo iniciarse,El fondo cortado se puede reciclar.", it) }
        }
    }

    /**
     * Se llama cuando la aplicación vuelve al primer plano.
     *
     * El servicio en primer plano no se puede detener cuando todavía hay tareas generadas; el servicio es una identidad de mantenimiento generada en segundo plano.
     * ChatGenerationService solo se cerrará solo después de que se hayan completado todas las tareas.
     */
    fun onAppForeground() {
        // v2.6.8: El error vuelve a ser visible después de regresar a la recepción.,Continúa la cuenta atrás pendiente de desaparición automática.
        appForegroundFlow.value = true
        if (chatGenerationManager.activeGenerations.value.isEmpty()) {
            runCatching { notificationManager.updateLiveProgress("", 0, false) }
                .onFailure { Logger.w("ChatVM", "Error en la notificación del progreso de la limpieza: ${it.message}") }
        } else {
            Logger.i(
                "ChatViewModel",
                "foreground resumed; keep generation service alive, tasks=${chatGenerationManager.activeGenerations.value.size}",
            )
        }
        refreshDisplayedSessionFromDatabase()
    }

    /** Actualiza la sesión de visualización actual desde Room sin modificar el enfoque de navegación ni cancelar la creación de fondo. */
    private fun refreshDisplayedSessionFromDatabase() {
        val sessionId = displayedSessionId() ?: return
        val switchToken = switchGuard.begin()
        sessionMemoryCache.remove(sessionId)
        viewModelScope.launch {
            val loaded =
                runCatching {
                    withContext(Dispatchers.IO) { messageController.loadMessagesPaged(sessionId) }
                }.onFailure { Logger.w("ChatVM", "No se pudo actualizar la sesión en primer plano: ${it.message}") }.getOrNull() ?: return@launch
            if (switchGuard.isStale(switchToken) || displayedSessionId() != sessionId) {
                return@launch
            }
            val (messages, hasMore) = loaded
            val isBackgroundStreaming = chatGenerationManager.isStreaming(sessionId)
            val restoredAgentPlans =
                messageController.restoreAgentPlansForSession(
                    sessionId,
                    messages,
                    settleUnfinished = !isBackgroundStreaming,
                )
            if (switchGuard.isStale(switchToken) || displayedSessionId() != sessionId) {
                return@launch
            }
            updateMessages { messages }
            _state.update { state ->
                if (displayedSessionId(state) != sessionId) {
                    state
                } else {
                    state.copy(
                        hasMoreHistory = hasMore,
                        isLoadingMore = false,
                        memoryCacheHit = false,
                        agentPlans = restoredAgentPlans,
                        isStreaming = isBackgroundStreaming,
                        isWaitingFirstToken =
                        isBackgroundStreaming && messages
                            .lastOrNull { it.role == MessageRole.ASSISTANT }
                            ?.let { it.content.isBlank() && it.toolCalls.isNullOrEmpty() }
                            == true,
                    )
                }
            }
            val treeSnapshot = withContext(Dispatchers.IO) { treeSnapshotStore?.load(sessionId) }
            if (displayedSessionId() == sessionId) {
                messageController.rebuildConversationTree(previousOverride = treeSnapshot)
            }
        }
    }

    /**
     * Notificar al ticker que la sesión actual finaliza(fire-and-forget).
     *
     * Phase 8.5 Reparación: Implementación original `runBlocking { settings.getSelectedModel() }` Bloquear en el hilo principal,
     * onCleared / createNewSession / switchSession ANR ocurre cuando se llama.
     * Cambiar a:model pasar null(MemoryTicker LaunchTracked interno se ejecuta de forma asincrónica,puede manejar null model
     * escenario de degradación),Elimine runBlocking por completo.
     */
    private fun notifySessionEndForCurrent() {
        // v1.0.52 P2-3: sesión terminada/Activar el guardado automático de memoria AI antes de cambiar(sin bloqueo,ejecución en segundo plano)
        triggerMemoryAutoSaveIfNeeded()
        streamCoordinator.notifySessionEndForCurrent()
    }

    /**
     * v1.0.52 P2-3: Activar el guardado automático de memoria AI(sesión terminada/Al cambiar).
     *
     * Entregar el historial de conversaciones de la sesión actual a [MemoryAutoSaveScheduler],Por él en el ámbito de fondo
     * Llame a LLM para extraer entidades/relación/fusionar/Clasificación,sin bloqueo,No afecta la interfaz de usuario.
     *
     * solo en [memoryAutoSaveScheduler] Activado cuando no está vacío;
     * La historia está vacía o es demasiado corta.(<2 Artículo)salto de tiempo.
     */
    private fun triggerMemoryAutoSaveIfNeeded() {
        val scheduler = memoryAutoSaveScheduler ?: return
        val sessionId = displayedSessionId() ?: return
        // v1.0.54: 30 Deduplica la misma sesión en segundos — Cambiar pestaña/Cambiar sesión/Reiniciar el contexto se activará repetidamente
        //   notifySessionEndForCurrent,Haga cola uno a la vez autoSave;Se acumula más de diez veces cuando la red está lenta.,
        //   Percepción del usuario"No se detuvo después de responder."(Llamadas continuas en segundo plano. completeText).
        val now = System.currentTimeMillis()
        if (sessionId == lastAutoSaveSessionId && now - lastAutoSaveAt < 30_000L) return
        lastAutoSaveSessionId = sessionId
        lastAutoSaveAt = now
        val uiHistory = _messages.value
        if (uiHistory.size < 2) return
        val assistantId = _state.value.currentAssistant?.id ?: "default"
        val scope = if (assistantId == "default") "main" else assistantId
        val locale = "es"
        // Capture el espacio actual antes de hacer cola y no podrá escribir la extracción en segundo plano de forma predeterminada para siempre;
        // Después de que el usuario cambie de espacio, la propiedad de esta sesión no cambiará.
        viewModelScope.launch(Dispatchers.IO) {
            // La recuperación de memoria no puede restringirse mediante la paginación en la pantalla de inicio de la página de chat.;Lea primero el historial persistente completo,
            // Luego agregue los mensajes de la interfaz de usuario que aún no se han incluido en la biblioteca.(Por ejemplo cambiar/última ronda sobre el límite del proceso).
            val dbHistory =
                if (settings.memoryConfigCache.conversationRecallEnabled) {
                    resultOf { sessionRepository.getAllMessagesForWarmup(sessionId) }
                        .onError { msg, t -> Logger.w("ChatVM", "memory full history load failed: $msg", t) }
                        .getOrNull()
                        .orEmpty()
                } else {
                    emptyList()
                }
            val dbIds = dbHistory.mapTo(HashSet()) { it.id }
            val history = if (dbHistory.isEmpty()) {
                uiHistory
            } else {
                dbHistory + uiHistory.filter { it.id !in dbIds }
            }
            if (history.size < 2) return@launch
            val spaceId = settings.currentSpaceIdFlow.firstOrNull().orEmpty().ifBlank { "default" }
            // model pasar null,MemoryLlmClient Implementar el modelo predeterminado configurado usando el Proveedor
            scheduler.scheduleAutoSave(
                sessionId = sessionId,
                history = history,
                assistantId = assistantId,
                spaceId = spaceId,
                scope = scope,
                model = null,
                locale = locale,
            )
        }
    }

    /**
     * v1.80 (M-CVM2): Marcar el mensaje actual del asistente como [interrumpido] Y poner parte de la respuesta en el mercado.
     *
     * Cancelado durante la transmisión(El usuario hace clic en detener/Cambiar de sesión)O llamado cuando una excepción interrumpe,Asegúrese de que el contenido recibido no se pierda cuando se destruya ViewModel.
     * Por la perseverancia [NonCancellable] paquete,Asegúrese de que la escritura de la base de datos aún pueda completarse cuando se cancele la rutina
     * (De lo contrario, la llamada de suspensión en la rutina cancelada arrojará inmediatamente CancellationException,Colocación fallida).
     */
    // A-12/A-14: paso a través expectedAssistantId — [interrumpido] Sólo se permite marcar mensajes generados en esta ronda.
    private suspend fun persistInterruptedAssistant(
        sessionId: String,
        partialMsg: UIMessage? = null,
        expectedAssistantId: Uuid? = null,
        durationMs: Long? = null,
    ) = streamCoordinator.persistInterruptedAssistant(sessionId, partialMsg, expectedAssistantId, durationMs)

    /**
     * v1.43: Colocación periódica — Conservar el progreso del flujo de mensajes del asistente actual en la base de datos,
     * cortemos paginas/El nuevo ViewModel detrás de escena puede restaurar el contenido más reciente de la base de datos.
     * Sin extracción de producto(Los productos solo se extraen después de que se completa el flujo.),Ni adjunto citation(Transmisión pending).
     *
     * v1.97 (P1-2): Para colocación periódica skipFts=true Saltar la reconstrucción de FTS(toNgram Caro para textos largos).
     * Ubicación final después de que finalice la transmisión(directo upsertMessage)Estará sincronizado FTS;Las interrupciones van a persistInterruptedAssistant y también se sincronizan.
     * Si la aplicación falla y el FTS se desvía,sureFtsIndexConsistent se reconstruirá automáticamente la próxima vez que se inicie.
     */
    private suspend fun persistCurrentAssistant(sessionId: String, assistantId: Uuid, msg: UIMessage? = null) =
        streamCoordinator.persistCurrentAssistant(sessionId, assistantId, msg, ::addError)

    private fun updateAssistant(
        id: Uuid,
        content: String,
        reasoning: String? = null,
        imageBase64List: List<String>? = null,
        imageUrls: List<String>? = null,
        videoFileUri: String? = null,
        isStreaming: Boolean = false,
    ) = streamCoordinator.updateAssistant(id, content, reasoning, imageBase64List, imageUrls, videoFileUri, isStreaming)

    /**
     * B10: Transmisión de desmontaje en tiempo real de ThinkTag(Ruta de función pura y ligera).
     *
     * Sólo el contenido del buffer contiene `<think>` La transformación visual se realiza sólo cuando se marca,En otros casos, simplemente vaya al original.
     * [updateAssistant] camino rápido —— Cero cambios de comportamiento en las rutas de transmisión normales sin etiquetas.
     *
     * contrato de desempeño(Reemplace la llamada de canalización completa de applyVisualTransform original):
     *  - cortocircuito: hazlo una vez primero O(n) de `contains("<think>")`,La mayoría de los fragmentos no contienen etiquetas.,
     *    Camino rápido de golpe directo,No toca ninguna lógica de conversión.
     *  - ligero: Llamado por golpe [splitThinkTagsForStreaming] función pura(Concatenación de cadenas de un solo paso regular + de un solo paso),
     *    No más correr transformerPipeline.applyVisualTransform(Eso pasará por todo Transformer
     *    Y construya una lista de mensajes temporales para cada descarga de limitación.).
     *  - Frecuencia de disparo: Acelere la descarga de la interfaz de usuario una vez cada vez(Predeterminado 50ms,El texto largo se amplía de forma adaptativa según streamFlushIntervalMs),
     *    y sólo si el contenido contiene `<think>` período;Por lo general, una compilación solo aparece al comienzo del párrafo de reflexión.
     *
     * Descripción: La única implementación actual de la transformación visual en streaming es la eliminación de ThinkTag.;Si el gancho visualTransform se agrega en el futuro,
     * Necesidad de evaluar si incorporar este camino ligero(En lugar de hacer retroceder incondicionalmente todo el oleoducto).
     */
    private suspend fun updateAssistantWithVisualTransform(assistantId: Uuid, content: String, reasoning: String?, isStreaming: Boolean) {
        val (visualReasoning, visualContent) =
            if (content.contains("<think>", ignoreCase = true)) {
                splitThinkTagsForStreaming(content, reasoning)
            } else {
                reasoning to content
            }
        updateAssistant(assistantId, visualContent, visualReasoning, isStreaming = isStreaming)
    }

    // ── v1.135 / P2-23: Ubicación de los resultados de la herramienta multimedia(El cuerpo de la herramienta se ha movido a MediaGenToolsImpl) ──────

    /**
     * Reparación de auditoría (S-01): Una vez que la herramienta se ejecute con éxito, el mensaje que contiene los medios se colocará inmediatamente en el mercado.
     *
     * Ver implementación de herramientas [io.zer0.muse.tools.MediaGenToolsImpl](App Regístrate al inicio),Este método consiste en
     * [ChatMediaGenHost] La devolución de llamada persistMediaMessage está en"La sesión actual todavía se muestra."cuando lo llaman.
     *
     * fondo: Durante múltiples rondas de Agent Loop,El mensaje A1 de la rueda de herramientas se bloqueó antes de la ejecución de la herramienta
     * ToolOrchestrator.persistAssistantToolMsg Realizar el pedido(Sin campo multimedia),no hay código después de esto
     * re upsert A1 — Los medios solo existen en la memoria. _messages,Reiniciar/Después de cortar la página, se pierden todas las imágenes y vídeos.
     * Cerrar identificadores de solo combinación finalAssistantMessage(A2),No es operativo para A1.
     *
     * El objeto de ubicación toma el mensaje actual de la identificación en _messages(Contiene medios escritos por updateAssistant),
     * Envolverlo en NonCancellable garantiza que la escritura se pueda completar incluso cuando se cancele.
     */
    private suspend fun persistToolMessageMedia(sessionId: String?, assistantId: Uuid) {
        if (sessionId.isNullOrBlank()) return
        val msg = _messages.value.firstOrNull { it.id == assistantId } ?: return
        // Los mensajes de voz tienen solo un campo multimedia, voiceUri, que también debe colocarse inmediatamente: nuevos mensajes de voz (misma ronda
        // Artículo 2 y posteriores) No hay otra oportunidad de realizar pedidos. Saltarlo hará que se reinicie todo el mensaje./Desaparece después de cortar la página.
        if (msg.imageUrls.isEmpty() && msg.imageBase64List.isEmpty() &&
            msg.videoFileUri == null && msg.voiceUri == null
        ) {
            return
        }
        withContext(Dispatchers.IO + NonCancellable) {
            // B-1: Si la sesión se ha eliminado, se omitirá la colocación de mensajes multimedia.,Prevenir la eliminación"resurrección".
            if (generationController.isSessionWritesSuppressed(sessionId).not()) {
                runCatching { sessionRepository.upsertMessage(sessionId, msg) }
                    .onFailure { Logger.e("ChatVM", "La colocación de noticias en los medios falló | session=$sessionId | id=$assistantId", it) }
            }
        }
    }

    // ── Phase 10.1: Programación completa de tarjetas de tareas.(v1.134 P1-5 adelante a taskCardCoordinator) ────

    /** Determinar si el resultado de la ejecución de la herramienta es exitoso(adelante a taskCardCoordinator). */
    private fun isToolResultSuccess(result: String): Boolean = taskCardCoordinator.isToolResultSuccess(result)

    /** Actualizar etapa de tarjeta de tarea(adelante a taskCardCoordinator). */
    private fun updateTaskCardPhase(taskCardId: String, phase: io.zer0.muse.ui.taskcard.TaskCardPhase) =
        taskCardCoordinator.updateTaskCardPhase(taskCardId, phase)

    /** Actualizar con precisión un solo TaskStep(adelante a taskCardCoordinator). */
    private fun updateTaskCardStep(
        taskCardId: String,
        stepIndex: Int,
        transform: (io.zer0.muse.ui.taskcard.TaskStep) -> io.zer0.muse.ui.taskcard.TaskStep,
    ) = taskCardCoordinator.updateTaskCardStep(taskCardId, stepIndex, transform)

    /** Alternar estado expandido/contraído de la tarjeta de tareas(adelante a taskCardCoordinator). */
    fun toggleTaskCardExpand(taskCardId: String) = taskCardCoordinator.toggleTaskCardExpand(taskCardId)

    /** Alterna la intención de pausa de un solo paso de herramienta en la tarjeta de tarea que aún no se ha iniciado. */
    fun toggleTaskStepPause(taskCardId: String, stepId: String) = taskCardCoordinator.toggleTaskStepPause(taskCardId, stepId)

    /** Alterna la intención de pausa de todos los pasos de la herramienta en la tarjeta de tareas que aún no se han iniciado. */
    fun toggleTaskCardPause(taskCardId: String) = taskCardCoordinator.toggleTaskCardPause(taskCardId)

    /**
     * Reintentar pasos fallidos en una tarjeta de tarea(adelante a taskCardCoordinator).
     *
     * - stepId = "ALL_FAILED":Reintentar todos los pasos fallidos
     * - stepId = ID de paso específico: reintente un único paso fallido
     *
     * Nota:Reintentar solo actualiza el estado de la interfaz de usuario(FAILED → RUNNING → SUCCESS/FAILED),
     * No volver a solicitar LLM(Los parámetros de la herramienta se conservan en pasos.).
     * Si necesita dejar que LLM continúe en función de nuevos resultados,Los usuarios deben regenerar manualmente.
     */
    fun retryFailedStep(taskCardId: String, stepId: String) = taskCardCoordinator.retryFailedStep(taskCardId, stepId)

    // ── Phase 8.2: Assistant Manejo de personalidad múltiple ─────────────────────────────────────

    /**
     * v1.0.54: Agent Tab Asistente de cambio = Alterna la sala de conversación del Agente del asistente.
     *
     * restricciones de diseño(Aprenda las lecciones de la contaminación 8-01):
     * - Solo modo agente(isAgentMode=true)válido;Utilice la pestaña Tarea [setSessionAssistant]
     * - solo operar agentSessionId,Nunca toques currentSessionId(Las sesiones de tareas están completamente aisladas.)
     * - no irse switchSession(Omita las actualizaciones de caché put/get y currentSessionId),
     *   Verifique directamente loadMessagesPagged DB,Evite naturalmente la contaminación de la clave de caché/Auto-golpe de caché vacío
     * - Detenga toda generación de fondo antes de cambiar(El bucle de agente de la sesión anterior no debe seguir ejecutándose.)
     */
    fun switchAgentAssistant(assistantId: String) {
        if (!_state.value.isAgentMode) return
        val targetId = assistantId.ifBlank { "default" }
        // v2.x: número de serie del interruptor — Caducado(Reemplazado por una palanca más nueva)Los resultados de la carga asincrónica se descartan antes del envío.,Evitar conversaciones cruzadas en la sesión
        val switchToken = switchGuard.begin()
        // Detener toda generación de fondo + separarse de la transmisión de UI + detener TTS/ASR,Consistente con switchSession
        chatGenerationManager.stop()
        runCatching { io.zer0.muse.schedule.ChatGenerationService.stop(appContext) }
        if (_state.value.isStreaming) detachStreaming()
        stopTts()
        disposeAsr()
        notifySessionEndForCurrent()
        viewModelScope.launch {
            // Tome 2 elementos y excluya la sesión actual:Golpear la sala histórica restaura,De lo contrario crear nuevo
            val recent = sessionRepository.getRecentAgentByAssistant(targetId, 2)
            val current = _state.value.agentSessionId
            val target = recent.firstOrNull { it.id != current }
            val sessionId = target?.id ?: sessionRepository.createAgentSession(assistantId = targetId)
            if (target != null) {
                io.zer0.common.Logger.i("ChatVM", "switchAgentAssistant: sala de historia de éxitos ${target.id}(assistant=$targetId)")
            } else {
                io.zer0.common.Logger.i("ChatVM", "switchAgentAssistant: Crear nueva habitación $sessionId(assistant=$targetId)")
            }
            // v1.0.54: sincronizar"Asistente de agente predeterminado"Preferencia — Mantenga al asistente al salir y volver a ingresar a la pestaña Agente
            //   (setAgentMode(true) Haga clic en esta preferencia para restaurar la habitación),Los valores que se ven en la entrada de la página de configuración también son consistentes.
            runCatching {
                val cfg = settings.proactiveMessageConfigFlow.first()
                if (cfg.agentId != targetId) {
                    settings.saveProactiveMessageConfig(cfg.copy(agentId = targetId))
                }
            }
            // Alternar recuento de referencias de sesiones(Consistente con setAgentMode)
            // v2.6.0 (AUD-UI-F4): La antigua referencia de sesión está en**Instantánea y publicación inmediatamente después de la lectura.**,evitar
            // “leer valor antiguo”con“liberar”insertado por otro interruptor —— En la implementación original, las dos filas son inmediatamente adyacentes pero ambas están en
            // Dentro de la corrutina,rapido A→B Durante el cambio continuo, B puede liberar la nueva sesión que A acaba de adquirir.,
            // El recuento de referencia se redujo demasiado,5s idle reaper Se borrarán los recursos de sesión que aún estén en uso.
            val previousAgentSessionId = _state.value.agentSessionId
            sessionManager.acquire(sessionId)
            if (previousAgentSessionId != null && previousAgentSessionId != sessionId) {
                sessionManager.release(previousAgentSessionId)
            }
            // Precargar mensajes(Comprobar directamente DB,No leer caché),Actualización de estado única
            val (messages, hasMore) = messageController.loadMessagesPaged(sessionId)
            if (switchGuard.isStale(switchToken)) {
                // Reemplazado por alternancia actualizada:Suelta esta vez acquire,Descartar resultados de carga
                sessionManager.release(sessionId)
                Logger.i("ChatVM", "switchAgentAssistant Resultados caducados,descartar: $sessionId")
                return@launch
            }
            val permissionMode =
                sessionPermissionStore.getMode(
                    sessionId,
                    settings.defaultSessionPermissionModeFlow.first(),
                )
            val assistant =
                assistantRepository.getById(targetId)
                    ?: assistantRepository.getById("default")
            val restoredAgentPlans =
                messageController.restoreAgentPlansForSession(
                    sessionId,
                    messages,
                    // El asistente de cambio ha dejado de generarse y el plan anterior debería finalizarse sin dejar ninguna tarjeta girando.
                    settleUnfinished = true,
                )
            // Carga entre sesiones, no existe una relación de fusión entre las listas antigua y nueva, y está prohibido cambiar a fusión; ya existe una doble verificación de SessionSwitchGuard.
            _messages.value = messages
            _state.update {
                it.copy(
                    agentSessionId = sessionId,
                    currentAssistant = assistant,
                    errors = emptyList(),
                    hasMoreHistory = hasMore,
                    isLoadingMore = false,
                    lastHistoryLoadCount = 0,
                    isStreaming = false,
                    // v1.0.72 fix: El asistente de cambio borra las referencias(Prevenir la contaminación entre sesiones)
                    replyingTo = null,
                    replyQuoteOverride = null,
                    taskCards = emptyMap(),
                    toolCallHistory = emptyList(),
                    agentPlans = restoredAgentPlans,
                    visionAssistedMessageIds = emptySet(),
                    visionProgress = null,
                    sessionPermissionMode = permissionMode,
                    listFirstVisibleItemIndex = messages.lastIndex.coerceAtLeast(0),
                    listFirstVisibleItemScrollOffset = 0,
                )
            }
            refreshContextInfo()
        }
    }

    /**
     * Cambie el Asistente vinculado a la sesión actual.
     * - persistir en sessions.assistantId
     * - Actualizar el estado actual del Asistente(impacto la próxima vez launchStream)
     * - No reenviar mensaje(Evite desencadenantes falsos);Los usuarios pueden regenerar manualmente
     */
    fun setSessionAssistant(assistantId: String) {
        val sessionId = displayedSessionId() ?: return
        viewModelScope.launch {
            sessionRepository.setSessionAssistant(sessionId, assistantId)
            val assistant =
                assistantRepository.getById(assistantId)
                    ?: assistantRepository.getById("default")
            _state.update { it.copy(currentAssistant = assistant) }
        }
    }

    /** Asistente agregado. Devuelve la nueva identificación. */
    fun createAssistant(name: String, callback: (String) -> Unit = {}) {
        viewModelScope.launch {
            val id = "assistant-" + System.currentTimeMillis()
            val now = System.currentTimeMillis()
            assistantRepository.upsert(
                AssistantEntity(
                    id = id,
                    name = name.ifBlank { "nuevo asistente" },
                    avatarEmoji = "",
                    // v1.0.51: Nuevo asistente precarga predeterminado prompt(Carga desde activos,respaldo con la configuración regional)
                    systemPrompt = assistantRepository.loadDefaultPrompt(),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            callback(id)
        }
    }

    /** Campo Asistente de actualización(arbitrario subset). */
    fun saveAssistant(assistant: AssistantEntity) {
        val now = System.currentTimeMillis()
        val saved = assistant.copy(updatedAt = now)
        viewModelScope.launch {
            resultOf { assistantRepository.upsert(saved) }
                .onSuccess {
                    // Phase 8.5 Reparación: Actualizar con guardado con nuevo actualizadoEn currentAssistant,Evite que la interfaz de usuario obtenga objetos antiguos
                    if (_state.value.currentAssistant?.id == assistant.id) {
                        _state.update { it.copy(currentAssistant = saved) }
                    }
                }
                .onError { msg, t ->
                    Logger.e("ChatVM", "saveAssistant failed", t)
                    reportError(appContext.getString(R.string.err_chat_assistant_save_failed, msg))
                }
        }
    }

    /**
     * v2.x(comentarios de los usuarios): Eliminar Assistant —— El asistente predeterminado también se puede eliminar (protección condicional).
     *
     * reglas:
     *  - Se permite eliminar al menos 2 asistentes (debe haber un reemplazo después de eliminar el predeterminado);
     *  - No está permitido eliminar el asistente vinculado a la sesión actual (se conserva la protección original);
     *  - Lo que se elimina es el asistente predeterminado (id=default o el puntero predeterminado apunta a),
     *    Cambia automáticamente el puntero predeterminado a otro asistente.
     * @param onDeleteDefaultBlocked Devolución de llamada cuando se rechaza la eliminación (solo queda un asistente), utilizada para indicaciones de la interfaz de usuario.
     */
    fun deleteAssistant(id: String, onDeleteDefaultBlocked: ((String) -> Unit)? = null) {
        if (_state.value.currentAssistant?.id == id) return
        viewModelScope.launch {
            val assistants = assistantRepository.getAll()
            if (assistants.size < 2) {
                onDeleteDefaultBlocked?.invoke(appContext.getString(R.string.err_chat_assistant_delete_last))
                return@launch
            }
            val defaultId = settings.getDefaultAssistantId()
            // Lo que se elimina es el asistente predeterminado → Primero, cambie el puntero a otro asistente (para asegurarse de que haya un asistente predeterminado en cualquier momento)
            if (id == defaultId || id == "default") {
                val successor = assistants.firstOrNull { it.id != id }
                if (successor == null) {
                    onDeleteDefaultBlocked?.invoke(appContext.getString(R.string.err_chat_assistant_delete_last))
                    return@launch
                }
                settings.saveDefaultAssistantId(successor.id)
                Logger.i("ChatVM", "deleteAssistant: Asistente predeterminado $id se eliminará y el puntero predeterminado cortará a ${successor.id}")
            }
            resultOf { assistantRepository.delete(id) }
                .onError { msg, t ->
                    Logger.e("ChatVM", "deleteAssistant failed", t)
                    reportError(appContext.getString(R.string.err_chat_assistant_delete_failed, msg))
                }
        }
    }

    /**
     * Phase 8.3: Cambiar el estado de la recopilación de mensajes.
     * - sincronizar DB(Sea optimista y cambie primero la lista de UI,DB Revertir en caso de falla)
     * - Actualizar simultáneamente messages(sesión actual)con favoriteMessages(Lista de sesiones cruzadas)Dos campos estatales
     *
     * Phase 8.5 Reparación: La implementación original está solo en `messages`(sesión actual)Encuentra aquí target,Desfavoritos entre sesiones
     * (FavoritesScreen Haga clic para eliminar colecciones que no están en la sesión actual)debido a target==null directo return,Completamente ineficaz.
     * Cambiar a:comprobar primero currentSession messages,No lo encuentro, compruébalo de nuevo. favoriteMessages(Lista de favoritos entre sesiones).
     */
    fun toggleFavorite(messageId: Uuid) {
        // v1.0.88 (S-4): Caché de memoria de invalidación de sincronización de cambios de colección — De lo contrario, el mensaje en la caché de la sesión
        // favorite Estado obsoleto(El icono de colección de burbujas no coincide con el panel de colección.).
        displayedSessionId()?.let { sessionMemoryCache.remove(it) }
        miscCoordinator.toggleFavorite(messageId)
    }

    /**
     * Cambie el estado de la colección al valor especificado (para entrada externa: mantenga presionado el menú en la página web).
     *
     * con [toggleFavorite] Comparten el mismo conjunto de actualizaciones y reversiones optimistas, la única diferencia es que el estado objetivo lo proporciona la persona que llama.
     * Establecer repetidamente el mismo estado no cambiará.
     */
    fun setMessageFavorite(messageId: Uuid, favorite: Boolean) {
        displayedSessionId()?.let { sessionMemoryCache.remove(it) }
        miscCoordinator.setFavorite(messageId, favorite)
    }

    /**
     * Eliminar un lote de mensajes (para portal externo: eliminación mediante selección múltiple en la página web).
     *
     * con [deleteMessage] La diferencia: no detiene la generación en curso de la sesión y no inhibe la colocación posterior. ——
     * Lo que se borra externamente son mensajes históricos. No debes borrar accidentalmente las respuestas que se están ejecutando en el teléfono celular. Ir paso a paso
     * [ChatMiscCoordinator.deleteMessage], la lista de mensajes, el árbol de diálogo y la base de datos son consistentes.
     */
    fun deleteMessages(messageIds: List<Uuid>) {
        if (messageIds.isEmpty()) return
        displayedSessionId()?.let { sessionMemoryCache.remove(it) }
        messageIds.forEach { id ->
            miscCoordinator.deleteMessage(id)
            _conversationTree.value = _conversationTree.value.removeMessage(id)
        }
    }

    /** Función1: Establecer respuesta de emoticón de mensaje(null = Cancelar). */
    fun setReaction(messageId: Uuid, reaction: String?) {
        val messageIdStr = messageId.toString()
        viewModelScope.launch {
            sessionRepository.setMessageReaction(messageIdStr, reaction?.takeIf { it.isNotEmpty() })
            // Actualización optimista UI
            updateMessages { list ->
                list.map { msg ->
                    if (msg.id == messageId) msg.copy(reaction = reaction?.takeIf { it.isNotEmpty() }) else msg
                }
            }
        }
    }

    /**
     * v1.104 U7: Establecer etiquetas de grupos favoritos(null = Mover a desagrupado).
     *
     * Actualización optimista favoriteMessages(Cambiar ahora tag,UI Responder inmediatamente);
     * favoriteTags La lista es reemitida automáticamente por DAO Flow,No requiere mantenimiento manual.
     * DB Revertir el campo de etiqueta en mensajes favoritos cuando falla la escritura.
     */
    fun setMessageFavoriteTag(messageId: Uuid, tag: String?) = miscCoordinator.setMessageFavoriteTag(messageId, tag)

    /**
     * v1.104 U7: Establezca las condiciones del filtro de agrupación para los favoritos actuales.
     *
     *  - null = Mostrar todo
     *  - cadena no vacía = Mostrar solo esto tag
     *  - [FAVORITE_TAG_UNGROUPED] = Mostrar solo desagrupados
     *
     * FavoritesScreen El FilterChip superior se llama cuando se hace clic.
     */
    fun setFavoriteTagFilter(tag: String?) = miscCoordinator.setFavoriteTagFilter(tag)

    /**
     * v1.48: Eliminar un solo mensaje(Menú de pulsación larga"Eliminar mensaje").
     *
     * Actualización optimista UI,Revertir y avisar en caso de error.
     */
    fun deleteMessage(messageId: Uuid) {
        // v1.0.85 (T-2): Eliminar sincronización de mensajes caché de memoria de sesión no válida — De lo contrario, aparecerá cuando te alejes y luego vuelvas a cambiar.
        // Instantánea antigua antes de la eliminación,mensaje eliminado"resurrección"(Comentarios de los usuarios:Eliminado y reingresado y todavía está ahí.).
        val sessionId =
            displayedSessionId()
        sessionId?.let { sessionMemoryCache.remove(it) }
        // B-1: Detenga la generación de la sesión en tránsito antes de eliminarla y prohíba su colocación posterior.,Evitar que se transmitan mensajes eliminados"resurrección".
        generationController.stopGenerationForSession(sessionId)
        generationController.suppressSessionWrites(sessionId)
        miscCoordinator.deleteMessage(messageId)
        // v1.0.80 (T-4): Árbol de conversación sincronizado — Ruoshu aún conserva los mensajes eliminados después de la eliminación,Al volver a la sesión
        // rebuildConversationTree Utilizará mergeRebuildMessages para fusionar mensajes de árbol antiguos,
        // Luego escribe a través de healBranchCounts DB,resultando en mensajes eliminados"resurrección"Y la sincronización de los mensajes antiguos no funciona.
        _conversationTree.value = _conversationTree.value.removeMessage(messageId)
        val tree = _conversationTree.value
        val sid = sessionId
        if (sid != null) {
            viewModelScope.launch(Dispatchers.IO) { treeSnapshotStore?.save(sid, tree) }
        }
    }

    /**
     * U-16: Eliminar el mensaje de destino y todos los mensajes posteriores(semántica de truncamiento).
     * Documentación §4 Compromiso"Solo borra este,O eliminarlo junto con el seguimiento.". La capa de datos va sessionRepository.truncateFrom,
     * UI capa consiste en [ChatMiscCoordinator.deleteMessagesFrom] Actualice y revierta de manera optimista en caso de falla.
     */
    fun deleteMessageWithFollowing(messageId: Uuid) {
        val sessionId =
            displayedSessionId() ?: return
        sessionMemoryCache.remove(sessionId)
        // B-1: Detenga la generación de la sesión en tránsito antes de eliminarla y prohíba su colocación posterior.,Evitar que se transmitan mensajes eliminados"resurrección".
        generationController.stopGenerationForSession(sessionId)
        generationController.suppressSessionWrites(sessionId)
        miscCoordinator.deleteMessagesFrom(messageId, sessionId)
        // Árbol de conversación sincronizado:Eliminar el nodo de mensaje(Los nodos posteriores son truncados por DB,Reconstrucción correcta al volver a cambiar)
        _conversationTree.value = _conversationTree.value.removeMessage(messageId)
        val tree = _conversationTree.value
        viewModelScope.launch(Dispatchers.IO) { treeSnapshotStore?.save(sessionId, tree) }
    }

    // ── B7-01: Operación por lotes de selección múltiple de mensajes ──────────────────────────────────────────

    fun setSelectionMode(enabled: Boolean) {
        _state.update {
            it.copy(
                selectionMode = enabled,
                selectedMessageIds = if (enabled) it.selectedMessageIds else emptySet(),
            )
        }
    }

    fun toggleMessageSelection(messageId: Uuid) {
        _state.update {
            val id = messageId.toString()
            it.copy(
                selectedMessageIds =
                if (id in it.selectedMessageIds) {
                    it.selectedMessageIds - id
                } else {
                    it.selectedMessageIds + id
                },
            )
        }
    }

    fun selectAllMessages(ids: Collection<String>) {
        _state.update { it.copy(selectedMessageIds = ids.toSet()) }
    }

    /** B7-03: Marque la conversación actual como leída cuando se desplace hacia abajo. */
    fun markSessionRead() {
        val sessionId = displayedSessionId() ?: return
        // Es posible que los mensajes de nueva sesión aún no se hayan cargado (_messages está vacío), también debes marcarlo como leído por messageCount en este momento.
        // De lo contrario, lastReadCount es siempre 0,messageCount - lastReadCount > 0, siempre se muestran nuevas sesiones“no leído 1”.
        viewModelScope.launch {
            val session = _state.value.sessions.find { it.id == sessionId }
            val last = _messages.value.lastOrNull()
            val readCount = session?.messageCount?.coerceAtLeast(_messages.value.size) ?: _messages.value.size
            sessionRepository.updateLastReadMessage(sessionId, last?.id?.toString() ?: "", readCount)
        }
    }

    fun deleteSelectedMessages() {
        val ids = _state.value.selectedMessageIds
        if (ids.isEmpty()) return
        ids.forEach { id ->
            runCatching { Uuid.parse(id) }.getOrNull()?.let { deleteMessage(it) }
        }
        _state.update { it.copy(selectionMode = false, selectedMessageIds = emptySet()) }
    }

    /**
     * v2.6.9(comentarios de los usuarios): Colección de mensajes seleccionados en lotes.
     *
     * Palabras originales del usuario:“Originalmente pensé que el diálogo de selección era para colección, pero no esperaba que en realidad fuera una pulsación larga para copiar.”.
     * La barra de selección múltiple utilizada para seleccionar solo todos/Copiar/Eliminar/Exportado, de hecho no hay colección.
     *
     * P3-10: La biblioteca de escritura converge a [ChatMiscCoordinator.favoriteMessages](Con solo toggle
     * El mismo conjunto de actualizaciones optimistas + reversión de fallas), aquí solo es responsable de la limpieza del estado seleccionado y la retroalimentación de los resultados.
     */
    fun favoriteSelectedMessages() {
        val ids = _state.value.selectedMessageIds
        if (ids.isEmpty()) return
        _state.update { it.copy(selectionMode = false, selectedMessageIds = emptySet()) }
        val uuids = ids.mapNotNull { id -> runCatching { Uuid.parse(id) }.getOrNull() }
        // Los favoritos afectan la estrella de la burbuja y el caché de la sesión, invalidando el caché consistente con toggleFavorite.
        displayedSessionId()?.let { sessionMemoryCache.remove(it) }
        viewModelScope.launch {
            val added = miscCoordinator.favoriteMessages(uuids)
            miscCoordinator.reportBatchFavorite(added = added, total = ids.size)
        }
    }

    // ── Phase 9.1 (M13): Agrupación de carpetas CRUD ──────────────────────────────────

    /** Crea una nueva carpeta. */
    fun createFolder(name: String) = miscCoordinator.createFolder(name, ::reportError)

    /** Cambie el nombre de la carpeta. */
    fun renameFolder(id: String, name: String) = miscCoordinator.renameFolder(id, name, ::reportError)

    /** eliminar carpeta(Las conversaciones relacionadas se movieron a desagrupadas). */
    fun deleteFolder(id: String) = miscCoordinator.deleteFolder(id, ::reportError)

    /** Cambiar expansión de carpeta/estado plegado. */
    fun toggleFolderExpanded(id: String, expanded: Boolean) = miscCoordinator.toggleFolderExpanded(id, expanded)

    /** Mover sesión a la carpeta(folderId=null = Mover a desagrupado). */
    fun moveSessionToFolder(sessionId: String, folderId: String?) = miscCoordinator.moveSessionToFolder(sessionId, folderId, ::reportError)

    /** P0-1 Reparación: Alternar estado superior de la conversación. */
    fun togglePinned(sessionId: String) = miscCoordinator.togglePinned(sessionId, ::reportError)

    /** B7-05: El nuevo orden después de arrastrar la sesión fijada persistente. */
    fun reorderPinnedSessions(ids: List<String>) = miscCoordinator.reorderPinnedSessions(ids, ::reportError)

    /**
     * Phase 8.4: Alternar interruptor de búsqueda de red(InputBar botón icono).
     * Cambie solo la interfaz de usuario para obtener comentarios inmediatos + persistencia en settings(Iniciar la recuperación la próxima vez).
     */
    fun toggleWebSearch() = settingsController.toggleWebSearch()

    /** v0.39: Alternar el interruptor de pensamiento profundo(Solo estado de tiempo de ejecución,No persistente,La próxima vez que ingrese al asistente de restauración de sesión predeterminado). */
    fun toggleDeepThinking() = settingsController.toggleDeepThinking()

    /**
     * v1.0.47 P5-6: Nivel circular de pensamiento profundo(LOW → MEDIUM → HIGH → XHIGH → LOW).
     *
     * solo en [ChatUiState.deepThinkingEnabled]=true Cuando lo llama la cápsula de nivel de UI.
     * Excluyendo APAGADO/APAGADO mediante toggleDeepThinking;Aquí sólo se ajustan las marchas.
     */
    fun cycleDeepThinkingLevel() = settingsController.cycleDeepThinkingLevel()

    /** v1.43: Seleccionar tarjeta de producto,Abra ArtifactViewerDialog. */
    fun selectArtifact(artifact: io.zer0.muse.data.artifact.ArtifactEntity) = mediaController.selectArtifact(artifact)

    /** v1.43: Cierre la ventana emergente de vista de tarjeta de producto. */
    fun dismissArtifactViewer() = mediaController.dismissArtifactViewer()

    /** v1.43: Ver la lista de tarjetas de productos asociadas con un mensaje. */
    fun observeArtifactsByMessage(messageId: String): kotlinx.coroutines.flow.Flow<List<io.zer0.muse.data.artifact.ArtifactEntity>> =
        mediaController.observeArtifactsByMessage(messageId)

    /** v1.45: Posición de desplazamiento de la lista de caché,Cortar páginas/Reanudar después de la preparación de antecedentes. */
    fun onListScrollPositionChanged(index: Int, offset: Int) = messageController.onListScrollPositionChanged(index, offset)

    /**
     * v2.6.7: Ejecución de la herramienta de recuperación con un solo clic desde el banner de la página de chat.
     *
     * Después de que el usuario activa "Pausar la ejecución de la herramienta", cada herramienta solo devolverá "Pausado, no se realizó ninguna operación real en esta ronda".
     * Se parece mucho a Internet/La herramienta está rota. Banner proporciona una salida que se puede cerrar con un clic, por lo que no es necesario volver a realizar la configuración.
     */
    fun resumeToolExecution() {
        viewModelScope.launch {
            resultOf { settings.updateChatPreferences { it.copy(pauseToolExecution = false) } }
                .onError { msg, _ -> Logger.w("ChatVM", "resumeToolExecution falló: $msg") }
        }
    }

    /** v1.45: Alterna la expansión del bloque de estado de ánimo del mensaje especificado/estado plegado. */
    fun toggleMessageMoodExpanded(messageId: String) = messageController.toggleMessageMoodExpanded(messageId)

    /** v1.45: Alternar la expansión del bloque de razonamiento para el mensaje especificado/estado plegado. */
    fun toggleMessageReasoningExpanded(messageId: String) = messageController.toggleMessageReasoningExpanded(messageId)

    /** v1.64: Alterna la expansión del bloque de reflexión del mensaje especificado/estado plegado. */
    fun toggleMessageReflectionExpanded(messageId: String) = messageController.toggleMessageReflectionExpanded(messageId)

    /** v2.4.6: Alterna la expansión del bloque de traducción de mensajes especificado/estado plegado. */
    fun toggleMessageTranslationExpanded(messageId: String) = messageController.toggleMessageTranslationExpanded(messageId)

    /**
     * Phase 8.7: Cambia el estado de lectura del mensaje.
     * - Actualmente leyendo este mensaje → detenerse
     * - Actualmente se está leyendo otro mensaje o no se está leyendo en voz alta → Empieza a leer esto en voz alta.
     *
     * @param messageId noticias id
     * @param content Texto para leer en voz alta(TtsManager eliminará automáticamente el contenido que contenga Markdown)
     *
     * v1.105: Delegar a [ChatAudioCoordinator.toggleTts].
     */
    fun toggleTts(messageId: Uuid, content: String) {
        // v1.0.4 (P2): TTS Brinde comentarios inmediatos cuando no esté listo(Originalmente solo regresó en silencio. false,sentimiento del usuario"No hay respuesta al hacer clic")
        // Solo se marca si el mensaje actual no se reproduce(speakingMessageId == messageId Es hora de detener la operación.,No hay necesidad de estar listo)
        if (_state.value.speakingMessageId != messageId && !ttsManager.isReady.value) {
            // Distinga entre "todavía inicializándose" y "inicialización fallida": este último no debería mantener al usuario“Vuelve a intentarlo más tarde”
            MuseToast.show(
                appContext.getString(
                    if (ttsManager.initFailedState.value) {
                        R.string.speech_tts_init_failed
                    } else {
                        R.string.tts_not_ready
                    },
                ),
            )
            return
        }
        audioCoordinator.toggleTts(messageId, content, ::reportError)
    }

    /** Phase 8.7: Detener la lectura actual(Cambiar de sesión/Llamado al salir de la página.).v1.105: Delegar a [ChatAudioCoordinator]. */
    fun stopTts() {
        audioCoordinator.stopTts()
    }

    // ── v1.91: transmisión ASR(v1.105 Delegar a ChatAudioCoordinator)─────────────

    /** v1.91: Inicie el reconocimiento de grabación en streaming.v1.105: Delegar a [ChatAudioCoordinator]. */
    fun startStreamingAsr() {
        audioCoordinator.startStreamingAsr()
    }

    /** v1.91: Detener la grabación en streaming,A la espera de resultados finales.v1.105: Delegar a [ChatAudioCoordinator]. */
    fun stopStreamingAsr() {
        audioCoordinator.stopStreamingAsr()
    }

    /** v1.91: Cancelar grabación de transmisión(Restaurar el texto del cuadro de entrada original).v1.105: Delegar a [ChatAudioCoordinator]. */
    fun cancelStreamingAsr() {
        audioCoordinator.cancelStreamingAsr()
    }

    /** v1.91: liberar ASR Controller(Cambio de sesión/ViewModel cuando destruido).v1.105: Delegar a [ChatAudioCoordinator]. */
    fun disposeAsr() {
        audioCoordinator.disposeAsr()
    }

    /**
     * Phase 9.3 (M2): Guarde la configuración de ASR.v1.105: Delegar a [ChatAudioCoordinator].
     *
     * ⚠️ Los parámetros de entrada deben ser la configuración original editada por el usuario.(de `settings.asrConfigFlow` o página de configuración).
     * **No se permite pasar `state.asrConfig`** —— Es posible que la configuración del proveedor haya sobrescrito esa copia.(Al seguir a un proveedor
     * baseUrl/apiKey/model Obtenga valor de proveedor),La clave del proveedor se escribirá en el disco cuando se coloque. `asr_config_json`.
     */
    fun saveAsrConfig(config: io.zer0.muse.asr.AsrConfig) {
        audioCoordinator.saveAsrConfig(config)
    }

    /**
     * Phase 9.3 (M2): Determinar si la ruta de grabación API se debe tomar actualmente(en lugar de sistema Intent).
     * v1.105: Delegar a [ChatAudioCoordinator].
     */
    fun shouldUseApiRecording(): Boolean {
        return audioCoordinator.shouldUseApiRecording()
    }

    // ── Modo de conversación de voz(completo ASR + AI + TTS bucle)──────────────────────────────

    /**
     * Ingrese al modo de conversación de voz:Inicie la primera ronda de ESCUCHA y comience la observación del bucle de la máquina de estados.
     *
     * Condiciones previas:
     *  - RECORD_AUDIO Permiso concedido(UI Responsable de comprobar/Aplicar)
     *  - ASR API configurado([shouldUseApiRecording] Regresar true),De lo contrario, salga con un error.
     *
     * Flujo de estado:IDLE → LISTENING(empezar ASR)→ El usuario hace clic en "Finalizar"→ THINKING →
     * SPEAKING(TTS leer en voz alta)→ LISTENING(dialogo continuo)→ ...
     *
     * Cuando termina el ingreso de esta sección lo decide el usuario:ASR no lo hará“Finaliza la grabación”Considerado automáticamente como una señal de envío.
     */
    fun startVoiceConversation() = mediaController.startVoiceConversation()

    /**
     * Salir del modo de conversación de voz:detenerse ASR/TTS,Cancelar la rutina de observación del bucle.,El estado se pone a cero.
     *
     * tiempo de llamada:El usuario hace clic en el botón cerrar(X), salida de página,ViewModel Destruir.
     */
    fun stopVoiceConversation() = mediaController.stopVoiceConversation()

    /**
     * Interrumpir el estado actual de la conversación de voz.(El usuario hace clic en el botón principal.).
     *
     * comportamiento:
     *  - LISTENING:detenerse ASR,Descartar la transcripción actual
     *  - THINKING:Detener ASR + Detener la generación de IA
     *  - SPEAKING:Detener la lectura TTS
     *  - IDLE:Ninguna acción
     *
     * Volver al estado después de la interrupción IDLE,Espere a que el usuario vuelva a hacer clic en el botón principal para iniciar una nueva conversación.
     */
    fun interruptVoiceConversation() = mediaController.interruptVoiceConversation()

    /**
     * Botón principal de conversación de voz(Sin presionar y mantener presionado toggle):
     *  - IDLE: iniciar una conversación
     *  - LISTENING Y grabación: finalice la entrada de este párrafo y envíela.
     *  - LISTENING Pero no grabando: reiniciar la grabación
     *  - THINKING / SPEAKING: Interrumpir la respuesta actual
     */
    fun toggleVoiceConversation() = mediaController.toggleVoiceConversation()

    /**
     * Iniciar una ronda de grabación ASR,Reconocer texto escrito mediante devolución de llamada [_voiceConversationTranscript].
     *
     * Nota:No escriba en los campos del cuadro de entrada(El modo de diálogo de voz no utiliza el flujo de texto de la barra de entrada),
     * con [io.zer0.muse.ui.chat.ChatAudioCoordinator.startStreamingAsr] Distinguir.
     */
    /** La rutina de observación del bucle de conversación de voz se ha trasladado a ChatMediaController. */

    /**
     * Inicie la rutina de observación del bucle de la máquina de estados:Monitorear ASR/Transmisión/TTS Cambio de estado,Controla automáticamente los bucles de la máquina de estados.
     *
     * tres puntos de observación(soltero collect,Procesamiento interno de sucursales):
     *  1. LISTENING El período ASR es Registrado por true → false: Sólo cuando el usuario haya terminado explícitamente de ingresar este párrafo(Ya colocado
     *     pendingCommit)Obtenga el envío de texto acumulado de esta ronda → THINKING;de lo contrario(Error / Cierre del lado del servidor/texto vacío)
     *     No envíe el mensaje, conserve el texto acumulado, vuelva a ESCUCHAR y escuche nuevamente (el mensaje luminoso "Dilo de nuevo" se dará cuando la acumulación esté vacía)
     *  2. THINKING El estado isStreaming está dado por true → false:AI Una vez completada la respuesta, tome el último mensaje del asistente y llámelo TTS → SPEAKING
     *  3. SPEAKING el estado esHablando por true → false:TTS Leer en voz alta, reanudar la grabación → LISTENING(dialogo continuo)
     *
     * THINKING / SPEAKING El micrófono permanece apagado durante(TTS Sin autograbación), solo se vuelve a abrir al volver a ESCUCHAR.
     *
     * La corrutina está en [stopVoiceConversation] / [interruptVoiceConversation] fue cancelado.
     */
    /** La rutina de observación del bucle de la máquina de estado del diálogo de voz se ha trasladado a ChatMediaController. */

    /** Sistema de consulta TTS lista de sonidos disponibles(Cambiar voz para la hoja inferior). */
    fun getAvailableTtsVoices(): List<android.speech.tts.Voice> = mediaController.getAvailableTtsVoices()

    /** Nombre del sonido TTS actualmente efectivo(Se utiliza para alternar la selección de marcas de hoja de voz.). */
    fun currentTtsVoiceName(): String = mediaController.currentTtsVoiceName()

    /**
     * Cambiar sonido TTS:Aplicar inmediatamente a TtsManager,y persistir en Settings(Seguirá surtiendo efecto la próxima vez que lo inicies.).
     *
     * @param voiceName Sistema TTS Voz name(de [getAvailableTtsVoices])
     */
    fun setTtsVoice(voiceName: String) = mediaController.setTtsVoice(voiceName)

    /**
     * Phase 8.4: Guardar configuración de búsqueda web(Settings Llamado después de editar la página.).
     * Phase 8.5 Reparación:Llame a CompositeWebSearchService.updateConfig sincrónicamente para que el cambio de tiempo de ejecución entre en vigor de inmediato.
     */
    fun saveWebSearchConfig(config: WebSearchConfig) {
        viewModelScope.launch {
            // v1.117: Utilice resultOf en su lugar para evitar tragar CancellationException(saveWebSearchConfig si suspend)
            resultOf { settings.saveWebSearchConfig(config) }
            // Tiempo de ejecución de actualización sincrónica service,Evite utilizar el antiguo método de búsqueda al inicio la próxima vez config
            (webSearchService as? io.zer0.muse.web.CompositeWebSearchService)?.updateConfig(config)
        }
    }

    // ── Phase 8.5: QuickMessages / Lorebook / PromptInjection ────────────

    /**
     * Phase 8.5: Cambiar el modo actual(usado para PromptInjection).
     * "default" Indica que no hay inyección;Otros modos activan las entradas PromptInjection correspondientes.
     */
    fun setMode(mode: String) {
        // v1.80 (M-CVM5): Actualizaciones atómicas
        _state.update { it.copy(currentMode = mode) }
    }

    /** Phase 8.5: Obtenga la lista de modos disponibles actualmente(Preestablecido + existente en la base de datos). */
    fun getAvailableModes(): List<Pair<String, String>> = promptInjectionRepository.presetModes

    /**
     * Phase 8.5: Inserte un mensaje rápido en el cuadro de entrada.
     * apoyo {{input}} / {{clipboard}} / {{date}} Sustitución de variables de plantilla.
     * Si la plantilla contiene {{input}},Reemplazar con el contenido del cuadro de entrada actual;De lo contrario, añádalo al final del cuadro de entrada.
     */
    fun insertQuickMessage(quickMessage: QuickMessageEntity, clipboardText: String = "") {
        val currentInput = _state.value.input
        val rendered =
            quickMessageRepository.renderTemplate(
                template = quickMessage.content,
                currentInput = currentInput,
                clipboard = clipboardText,
            )
        // Si el texto de la plantilla original contiene {{input}},rendered reemplazado;De lo contrario, utilice el resultado de la renderización como nueva entrada.
        val newInput =
            if (quickMessage.content.contains("{{input}}")) {
                rendered
            } else if (currentInput.isBlank()) {
                rendered
            } else {
                "$currentInput\n\n$rendered"
            }
        // v1.80 (M-CVM5): Actualizaciones atómicas
        _state.update { it.copy(input = newInput) }
    }

    /** v1.58: Inserte el contenido de la plantilla de solicitud en el cuadro de entrada(Si el cuadro de entrada ya contiene contenido, añádelo). */
    fun insertPromptTemplate(template: io.zer0.muse.data.prompttemplate.PromptTemplate) {
        val currentInput = _state.value.input
        val newInput =
            if (currentInput.isBlank()) {
                template.content
            } else {
                "$currentInput\n\n${template.content}"
            }
        // v1.80 (M-CVM5): Actualizaciones atómicas
        _state.update { it.copy(input = newInput) }
    }

    /** Phase 8.5: analizar Assistant.quickMessageIdsJson / lorebookIdsJson / modeInjectionIdsJson. */
    private fun parseIdList(json: String): List<String> = runCatching { idListJson.decodeFromString<List<String>>(json) }
        .getOrDefault(emptyList())

    // ── Phase 8.5: Lorebook / PromptInjection / QuickMessage CRUD(Para la página de gestión) ──

    /**
     * v1.97 (P1-1): Carga diferida de todas las entradas del Lorebook(Se llama cuando se ingresa a la página de administración.).
     * Reemplace el colector de flujo residente en el inicio original,Reduzca la sobrecarga innecesaria de observación de la base de datos del proceso de chat principal.
     */
    fun refreshLorebooks() = miscCoordinator.refreshLorebooks()

    /** ST-01: Vuelva a intentar la entrada después de no poder cargar la lista de favoritos. */
    fun retryLoadFavorites() = loadFavorites()

    /** ST-01: Reintente la entrada después de una carga fallida de la lista de sesiones de archivo. */
    fun retryLoadArchivedSessions() = loadArchivedSessions()

    /** ST-01: Vuelva a intentar la entrada después de que la lista mundial de libros no se cargue. */
    fun retryLoadLorebooks() = miscCoordinator.refreshLorebooks()

    /** ST-01: Observe los mensajes favoritos en todas las sesiones,Error al iniciar sesión [favoritesError] Para mostrar el estado de error de la página de colección. */
    private fun loadFavorites() {
        viewModelScope.launch {
            try {
                sessionRepository.observeAllFavorites().collect { favs ->
                    _state.update { it.copy(favoriteMessages = favs, isFavoritesLoading = false, favoritesError = null) }
                    // §11-4: Marcar versión antigua como favorita(La colección apunta a la antigua fila después de la regeneración.). El error de consulta no puede hacer que se muestre la lista.
                    _staleFavoriteIds.value = runCatching { sessionRepository.staleFavoriteIds(favs) }
                        .getOrDefault(emptySet())
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isFavoritesLoading = false,
                        favoritesError = e.message ?: appContext.getString(R.string.common_load_failed),
                    )
                }
            }
        }
    }

    /** ST-01: Observar conversaciones archivadas,Error al iniciar sesión [archivedSessionsError] Para visualización de errores en la página de archivo. */
    private fun loadArchivedSessions() {
        viewModelScope.launch {
            try {
                sessionRepository.observeArchived().collect { archived ->
                    _state.update { it.copy(archivedSessions = archived, archivedSessionsError = null) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(archivedSessionsError = e.message ?: appContext.getString(R.string.common_load_failed)) }
            }
        }
    }

    /** Lorebook: Nuevo o actualizado. */
    fun saveLorebook(entity: LorebookEntity) = miscCoordinator.saveLorebook(entity)

    /** Lorebook: borrar. */
    fun deleteLorebook(id: String) = miscCoordinator.deleteLorebook(id)

    /**
     * v1.97 (P1-1): Carga diferida de todas las entradas de PromptInjection(Se llama cuando se ingresa a la página de administración.).
     */
    fun refreshPromptInjections() = miscCoordinator.refreshPromptInjections()

    /** PromptInjection: Nuevo o actualizado. */
    fun savePromptInjection(entity: PromptInjectionEntity) = miscCoordinator.savePromptInjection(entity)

    /** PromptInjection: borrar. */
    fun deletePromptInjection(id: String) = miscCoordinator.deletePromptInjection(id)

    /**
     * v1.97 (P1-1): Carga diferida de todas las entradas de QuickMessage(Se llama cuando se ingresa a la página de administración.).
     */
    fun refreshAllQuickMessages() = miscCoordinator.refreshAllQuickMessages()

    /** QuickMessage: Nuevo o actualizado. */
    fun saveQuickMessage(entity: QuickMessageEntity) = miscCoordinator.saveQuickMessage(entity)

    /** QuickMessage: borrar. */
    fun deleteQuickMessage(id: String) = miscCoordinator.deleteQuickMessage(id)
    private suspend fun persistCheckpointAndReleaseOutbox(
        state: StreamRunState,
        sessionId: String,
        userMessageId: String,
        assistantMessageId: String,
        content: String,
        createdAt: Long,
    ) {
        val pendingOutboxId = state.outboxId
        val checkpointPersisted = resultOf {
            if (pendingOutboxId != null) {
                sessionRepository.upsertGenerationCheckpointAndDeleteOutbox(
                    sessionId = sessionId,
                    userMessageId = userMessageId,
                    assistantMessageId = assistantMessageId,
                    content = content,
                    createdAt = createdAt,
                    outboxId = pendingOutboxId,
                )
            } else {
                sessionRepository.upsertGenerationCheckpoint(
                    sessionId = sessionId,
                    userMessageId = userMessageId,
                    assistantMessageId = assistantMessageId,
                    content = content,
                    createdAt = createdAt,
                )
            }
        }.onError { msg, _ -> Logger.w("ChatVM", "generation checkpoint Error de escritura: $msg") }.isSuccess
        if (checkpointPersisted && pendingOutboxId != null) {
            state.outboxId = null
        }
    }
}
