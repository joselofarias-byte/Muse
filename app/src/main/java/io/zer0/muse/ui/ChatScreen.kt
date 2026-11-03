package io.zer0.muse.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.kyant.backdrop.backdrops.layerBackdrop
import io.github.fletchmckee.liquid.liquefiable
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.perf.MessagePaginator
import io.zer0.muse.transformer.InternalMarkupSanitizer
import io.zer0.muse.ui.chat.BrowserStatusCapsule
import io.zer0.muse.ui.chat.ChatContextDivider
import io.zer0.muse.ui.chat.ChatSurface
import io.zer0.muse.ui.chat.MESSAGE_MAP_MIN_MESSAGES
import io.zer0.muse.ui.chat.MESSAGE_MAP_RESERVED_WIDTH
import io.zer0.muse.ui.chat.MessageMapBar
import io.zer0.muse.ui.chat.PendingApprovalsSummary
import io.zer0.muse.ui.chat.PendingQueueBar
import io.zer0.muse.ui.chat.SessionTodoBar
import io.zer0.muse.ui.chat.SlashCommand
import io.zer0.muse.ui.chat.TokenStatsBar
import io.zer0.muse.ui.chat.ToolApprovalCard
import io.zer0.muse.ui.chat.ToolPausedBanner
import io.zer0.muse.ui.chat.toolMessageIdsCoveredByTaskCards
import io.zer0.muse.ui.common.MuseFloatingActionItem
import io.zer0.muse.ui.common.MuseFloatingActionMenu
import io.zer0.muse.ui.common.animation.SendFlight
import io.zer0.muse.ui.common.animation.SendFlightWindow
import io.zer0.muse.ui.common.animation.computeSendFlight
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.museSafeTopInsetPadding
import io.zer0.muse.ui.common.media.DesktopShortcuts
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberDesktopShortcutsEnabled
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.museAnimateItem
import io.zer0.muse.ui.common.navigation.ChatTopBarScrim
import io.zer0.muse.ui.common.navigation.MuseTopBarIconButton
import io.zer0.muse.ui.common.state.MuseLoadingState
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.common.surface.museBottomBarInsets
import io.zer0.muse.ui.markdown.CardAction
import io.zer0.muse.ui.speech.SpeechInput
import io.zer0.muse.ui.speech.TtsControllerWidget
import io.zer0.muse.ui.speech.VoiceConversationMode
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.util.ShareIntentHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject

/** P6-B3: Lista de idiomas de destino compatibles con la traducción(Idiomas de uso común,El nombre chino es más fácil de entender para LLM). */
internal val TranslationLanguages =
    listOf(
        "chino", "English", "日本語", "한국어", "Français", "Deutsch", "Español", "Русский", "العربية", "Português",
    )

/** Phase 8.10: La tecla de volumen se desplaza con un solo desplazamiento(px),M21. */
private const val VOLUME_SCROLL_DISTANCE_PX = 200f

/** v2.x: Tamaño del paso de desplazamiento inferior — Seguir automáticamente/Al regresar al final, presione el ciclo de pasos para desplazarse hasta el final de la lista.(Truncar extremos automáticamente,Nunca retroceder). */
private const val SNAP_SCROLL_STEP_PX = 8_000f

/** v1.79 (M-S12): Intervalo de agrupación de mensajes(5 minutos),Muestra avatar y marca de tiempo después de este intervalo. */
private const val MESSAGE_GROUP_INTERVAL_MS = 5 * 60 * 1000L

/**
 * El límite superior para la selección de lotes de álbumes de fotos.
 *
 * Cite directamente el límite superior de imágenes a enviar ([io.zer0.muse.ui.chat.ImageGenCoordinator.MAX_PENDING_IMAGES]),
 * Ambas partes mantienen la misma fuente verdadera: la cantidad que el usuario puede seleccionar en el selector del sistema es la cantidad que realmente puede enviar.
 * no aparecerá“Seleccioné 10 tarjetas y solo entraron 4.”de confusión.
 */
private const val MAX_MULTI_PICK = io.zer0.muse.ui.chat.ImageGenCoordinator.MAX_PENDING_IMAGES

/**
 * Sólo se permiten mensajes con contenido real o carga comercial para ingresar a la lista de chat.
 *
 * La fase de espera de transmisión está representada por una ThinkingBubble independiente, y la ocupación de asistente vacía no puede continuar usándose como
 * Representación de burbujas normales; de lo contrario global isStreaming=true A veces, la cáscara vacía de la historia también se dibuja en un delgado marco blanco.
 */
/** v2.5.0: ¿Es la carga útil que no es de texto suficiente para renderizar (herramientas/pensar/fotos/producto/citas, etcétera). */
private fun UIMessage.hasRenderablePayload(showToolCallDetails: Boolean): Boolean {
    // toolCalls Es un protocolo interno enviado al modelo y no representa contenido visible para el usuario; la verdadera herramienta UI
    // Alojado por toolCallInfo/taskCard.
    // v2.5.0 fix: toolCallInfo Cerrado por el interruptor "Mostrar proceso de llamada de herramienta" —— capa de filtro anterior
    // Los mensajes que contienen toolCallInfo se cuentan incondicionalmente como renderizables, pero MessageBubble internamente
    // Regrese con anticipación cuando el interruptor esté apagado, lo que dará como resultado un artículo de tamaño cero; cada rueda de herramientas continua se apila una copia
    // messageGap, se apila un gran espacio fantasma en blanco entre los bloques de pensamiento (captura de pantalla de la medición real del usuario).
    // Cuando el interruptor está cerrado“Solo carga de herramienta”El mensaje no se ingresa directamente en la lista de representación.
    // v2.6.7 fix(Auditoría de tarjeta): show_card El producto es la "tarjeta en sí", no el "proceso de llamada de la herramienta".
    // Por lo tanto, debe permanecer visible cuando el interruptor está cerrado. Las exenciones para la implementación anterior solo estaban escritas en la capa de burbujas.
    // (MessageBubble.isShowCardMessage), y la capa de lista lo filtra primero —— La exención está escrita en
    // Donde no puedas llegar, las tarjetas desaparecerán por completo con el interruptor.
    val isShowCardMessage = toolCallInfo?.toolName == SHOW_CARD_TOOL_NAME
    val toolPayloadVisible = (showToolCallDetails && toolCallInfo != null) || isShowCardMessage
    return toolPayloadVisible ||
        reasoning?.isNotBlank() == true ||
        imageUrls.isNotEmpty() ||
        imageBase64List.isNotEmpty() ||
        !videoFileUri.isNullOrBlank() ||
        artifactIds.isNotEmpty() ||
        citationUrls.isNotEmpty() ||
        ragCitations.isNotEmpty() ||
        attachments.isNotEmpty() ||
        !mood.isNullOrBlank() ||
        !reflection.isNullOrBlank()
}

@Suppress("ReturnCount")
private fun isRenderableChatMessage(message: UIMessage, showToolCallDetails: Boolean = true): Boolean {
    if (message.role == MessageRole.SYSTEM) return false
    // v2.6.0(comentarios de los usuarios): TOOL Mensaje de resultado (knowledge_search Texto completo de los documentos recuperados, etc.)
    // Siga el interruptor "Mostrar proceso de llamada de herramienta" —— Ya no aparece como una burbuja de texto en la interfaz cuando está cerrada.
    // El resultado de la herramienta es originalmente la carga útil del protocolo rellenada en el modelo. Anteriormente, el contenido no estaba vacío y siempre se representaba.
    // Después de que los usuarios apagan la visualización de la herramienta, todavía ven grandes secciones de texto de búsqueda, lo que crea una apariencia fragmentada.
    if (message.role == MessageRole.TOOL) return showToolCallDetails
    val hasVisibleContent = message.content.isNotBlank() &&
        (message.role == MessageRole.USER || InternalMarkupSanitizer.displayContent(message.content).isNotBlank())
    return hasVisibleContent || message.hasRenderablePayload(showToolCallDetails)
}

private fun List<UIMessage>.filterRenderableChatMessages(showToolCallDetails: Boolean = true): List<UIMessage> =
    filter { isRenderableChatMessage(it, showToolCallDetails) }

/** El número máximo de palabras que se pueden mostrar en la palabra del mensaje de trabajo con imagen se cortará si excede el límite. */
private const val IMAGE_GEN_PROMPT_CHARS = 60

/** El nombre de la herramienta que genera imágenes. La barra de trabajo de la imagen se basa en ella para esperar los parámetros de la llamada más reciente. */
private const val IMAGE_GEN_TOOL = "generate_image"

/**
 * Cada mensaje "reenvía la palabra de aviso de la generación_imagen más reciente" para mostrar las imágenes.
 *
 * La implementación anterior escaneaba hacia adelante cada elemento visible (`take(index + 1).asReversed()`):
 * lista total O(n²)y los mismos parámetros de la herramienta se volverán a analizar como JSON en cada actualización.
 * Escanee hacia adelante aquí: cualquiera que sea el elemento escaneado, registre en él la "última palabra de aviso hasta este elemento".
 * Cada mensaje solo analiza sus propios parámetros una vez. La semántica es exactamente la misma que la de escanear hacia adelante uno por uno. ——
 * Cuando la palabra de aviso de la última llamada está vacía, el resultado es nulo y no se realizará ningún seguimiento adicional.
 *
 * @param parsePrompt parámetros JSON → Palabra inmediata; el valor predeterminado es el análisis real, la persona que llama puede inyectar la implementación de conteo para verificar el número de análisis
 * @return ID del mensaje (en forma de cadena, en el mismo espacio que la clave de la lista)→ Palabra de aviso (truncada)
 */
internal fun imageGenPromptsByMessage(
    messages: List<UIMessage>,
    parsePrompt: (String) -> String? = ::promptOfImageGenCall,
): Map<String, String?> {
    val prompts = HashMap<String, String?>(messages.size)
    var latest: String? = null
    var sawCall = false
    for (message in messages) {
        val args =
            message.toolCalls?.firstOrNull { it.name == IMAGE_GEN_TOOL }?.arguments
                ?: message.toolCallInfo?.takeIf { it.toolName == IMAGE_GEN_TOOL }?.arguments
        if (args != null) {
            sawCall = true
            latest = parsePrompt(args)?.takeIf { it.isNotBlank() }?.let { prompt ->
                if (prompt.length > IMAGE_GEN_PROMPT_CHARS) prompt.take(IMAGE_GEN_PROMPT_CHARS) + "…" else prompt
            }
        }
        prompts[message.id.toString()] = if (sawCall) latest else null
    }
    return prompts
}

/** Obtenga un aviso del parámetro JSON de generate_image; El error de análisis se trata como \"sin palabra rápida\". */
private fun promptOfImageGenCall(args: String): String? =
    runCatching { org.json.JSONObject(args).optString("prompt") }.getOrNull()

/**
 * Phase 8.10: Bloquear las teclas de volumen/evento de tecla abajo,Cambie a la operación de desplazamiento.
 * - VOLUME_UP → direction = -1(desplazarse hacia arriba)
 * - VOLUME_DOWN → direction = +1(desplazarse hacia abajo)
 * Devuelve verdadero indicando que el evento ha sido consumido,El sistema ya no ajusta el volumen.
 *
 * Pase [androidx.compose.ui.input.key.KeyEvent.nativeKeyEvent] Consigue el piso inferior
 * [android.view.KeyEvent],visita keyCode/action(Compose Tipo de capa envolvente/atributo clave
 * Disponibilidad inconsistente en diferentes versiones,nativeKeyEvent Estable y confiable).
 */
private fun Modifier.onVolumeKeyEvent(onScroll: (Float) -> Unit): Modifier = this.onKeyEvent { event ->
    val native = event.nativeKeyEvent
    if (native.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
    val direction =
        when (native.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> -1f
            KeyEvent.KEYCODE_VOLUME_DOWN -> 1f
            else -> return@onKeyEvent false
        }
    onScroll(direction)
    true
}

/**
 * pagina de chat — Navegación en la pestaña superior,Retire la estructura del cajón.
 *
 * ChatScreen Insertar como contenido de la pestaña 1 de HomeScreen,El cajón ya no es necesario.
 * Mantener todas las funciones principales del chat:
 *  - Cambio de modelo, configuración de entrada.
 *  - Desplazamiento de la tecla de volumen,MessageBubble,InputBar
 *  - Visualización de errores, botón de desplazamiento hasta el final, desplazamiento automático
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    onOpenAssistants: () -> Unit = {},
    /** v0.27: Pasar la devolución de llamada cuando se empuja desde la lista de tareas;cuando es nulo(Tab modo)No mostrar el botón Atrás. */
    onBack: (() -> Unit)? = null,
    /**
     * v1.24: Agent Tab modo — Oculto viene con TopAppBar,HomeScreen proporciona un menú de tres puntos.
     *
     * v2.6.7(P0-3): Reservado únicamente para compatibilidad con versiones anteriores. Utilice el nuevo código en su lugar. [surface],
     * Este parámetro se convertirá internamente a [ChatSurface.AGENT] / [ChatSurface.FULL].
     */
    isAgentMode: Boolean = false,
    /**
     * v2.6.7(P0-3): Formulario de página de chat.
     *
     * poner"caparazón de página"Dibuja una única enumeración en lugar de apilar modificadores booleanos:
     * Formulario integrado de teléfono celular pequeño ([ChatSurface.MINI_PHONE]) requiere barra superior estilo WeChat y
     * "Volver al teléfono celular pequeño"navegación en lugar de regresar a la lista principal de sesiones de la aplicación.
     *
     * No cambie la generación de enlaces, el ensamblaje de herramientas y la determinación de permisos.——Están determinadas por las propiedades de la propia sesión.
     */
    surface: ChatSurface? = null,
    viewModel: ChatViewModel = koinInject(),
    /**
     * HTML/SVG Bloqueo de código de devolución de llamada de vista previa de pantalla completa.
     * por [MessageBubble] En la esquina superior derecha del bloque de código HTML/SVG"Vista previa"disparador de botón,
     * El parámetro es el código fuente HTML completo.(SVG Empaquetado para estar completo HTML).
     * MainActivity Inyectar lógica de navegación en NavGraph,saltar a [HtmlPreviewScreen].
     */
    onHtmlPreview: (String) -> Unit = {},
    /** Menú más → Entrada de habilidad. */
    onOpenSkills: () -> Unit = {},
    /** B0-07: Abra la página de administración de plantillas de Word. */
    onOpenPromptTemplateManager: () -> Unit = {},
    /** C4: Modo integrado de doble columna(Columna derecha de la pestaña de tareas de pantalla ancha)Al pasar modificadores de diseño a nivel de columna. */
    modifier: Modifier = Modifier,
) {
    // v2.6.7(P0-3): Análisis morfológico. prioridad [surface];Devuelve el antiguo si aún no se ha transmitido. [isAgentMode].
    // rama vieja (isAgentMode Lea más de una docena de lugares) lea uniformemente las variables locales aquí,
    // evitar"Parámetro booleano + nueva enumeración"Ambas fuentes de verdad tienen efecto simultáneamente.
    val resolvedSurface =
        surface ?: if (isAgentMode) ChatSurface.AGENT else ChatSurface.FULL
    val isAgentMode: Boolean = resolvedSurface == ChatSurface.AGENT
    val isMiniPhoneSurface: Boolean = resolvedSurface.isMiniPhone

    val state by viewModel.state.collectAsStateWithLifecycle()
    val conversationTree by viewModel.conversationTree.collectAsStateWithLifecycle()
    // B2-01: Lista de mensajes independiente collect,Los cambios de estado de alta frecuencia, como los cuadros de entrada, ya no provocan que se reorganice toda la lista de mensajes.
    val messages by viewModel.messages.collectAsStateWithLifecycle()

    // Enviar sobre la marcha: solo se utiliza para el primer mensaje de una conversación. Tome las coordenadas de la ventana del rectángulo en ambos extremos,
    // Porque la capa fantasma se dibuja en una ventana separada (consulte SendFlightWindow).
    var inputWindowBounds by remember { mutableStateOf(Rect.Zero) }
    // La altura real de todo el bloque inferior (tarjeta de aprobación/lista de tareas pendientes/lista de tareas pendientes/barra plana de proceso/campo de entrada).
    // El botón de desplazamiento hacia la parte inferior está ubicado junto a él: estas barras aparecerán o desaparecerán dependiendo del estado de ejecución, y el desplazamiento de escritura inevitablemente las alcanzará.
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    var listWindowBounds by remember { mutableStateOf(Rect.Zero) }
    var sendFlight by remember { mutableStateOf<SendFlight?>(null) }
    // v1.0.20 (Task 3): Estos valores son de la misma instantánea de StateFlow.
    // No se puede utilizar remember { derivedStateOf { state.xxx } } Capture una instantánea local de la reorganización actual;
    // derivedStateOf Los valores ordinarios capturados no se observarán,El cuadro de entrada siempre se detendrá en el texto vacío inicial.
    // Leer la instantánea actual directamente,Asegúrese de que las condiciones de entrada del teclado, relleno de voz y botón de envío estén siempre sincronizadas.
    val isStreaming = state.isStreaming
    val isWaitingFirstToken = state.isWaitingFirstToken
    val visionProgress = state.visionProgress
    val currentInput = state.input
    // P2-13: Interruptor maestro de tecla de acceso directo de escritorio(Expanded Ventana + teclado físico)
    val desktopShortcutsEnabled = rememberDesktopShortcutsEnabled()
    // P2-1: pantalla grande(Expanded)La lista de mensajes inferior está centrada y el ancho es limitado 720dp
    val widthClass = rememberWindowWidthClass()
    // Phase 3 3E: Banner de mensaje cronometrado
    val pendingMessageManager: io.zer0.muse.data.schedule.PendingMessageManager = koinInject()
    val pendingMessages by pendingMessageManager.pendingMessagesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val pendingScope = rememberCoroutineScope()
    // v1.45: Inicializado con la posición de desplazamiento almacenada en caché en ViewModel LazyListState,Cortar páginas/Restaurar ubicación después del fondo
    // v2.2.1: Agent El modo utiliza caché independiente para restaurar la posición donde el usuario se fue antes;Las sesiones ordinarias mantienen el comportamiento original.
    val agentCachedIndex = state.agentListFirstVisibleItemIndex.takeIf { it >= 0 }
    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex =
            if (state.isAgentMode && agentCachedIndex != null) {
                agentCachedIndex
            } else {
                state.listFirstVisibleItemIndex
            },
            initialFirstVisibleItemScrollOffset =
            if (state.isAgentMode && agentCachedIndex != null) {
                state.agentListFirstVisibleItemScrollOffset
            } else {
                state.listFirstVisibleItemScrollOffset
            },
        )
    // v1.45: Se almacena en caché sincrónicamente cuando cambia la posición de desplazamiento ViewModel
    // v1.100: agregar distinctUntilChanged + sample(100ms) desaceleración,Evite la alta frecuencia al desplazarse
    // Escribir en el estado ViewModel hace que ChatScreen se reorganice por completo(desplazarse → escribir state → Reorganización → Bucle implícito de remedición)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .sample(100L)
            .distinctUntilChanged()
            .collect { (index, offset) ->
                viewModel.onListScrollPositionChanged(index, offset)
            }
    }
    // v2.6.7 fix(Auditoría de tarjeta): Los procesadores de devolución de tarjetas convergen en una ubicación.
    // La implementación anterior solo se definió en línea en el sitio de llamada de MessageBubble, mientras que la operación de agrupación estaba bloqueada (ToolRunCard,
    // continuo >=2 Aparece cuando se llama a una herramienta) El puente no se obtiene en absoluto —— Entonces el mismo show_card
    // Se puede hacer clic en él si aparece solo, pero no se puede hacer clic en él si está agrupado y no aparece ningún mensaje.
    val handleCardAction: (String, CardAction) -> Unit =
        remember(viewModel) {
            { messageId: String, action: CardAction ->
                when (action) {
                    is CardAction.Send -> viewModel.sendFromCard(action.text, action.cardId, action.data)
                    is CardAction.Save -> viewModel.saveCardAsArtifact(
                        messageId = messageId,
                        language = action.language,
                        content = action.content,
                    )
                    is CardAction.Favorite -> viewModel.favoriteCard(
                        cardId = action.cardId ?: messageId,
                        language = action.language,
                        content = action.content,
                    )
                }
            }
        }
    val context = LocalContext.current
    // v2.6.2: Copia de mensaje común previamente analizada —— Utilizado en lambdas de devolución de llamada profundamente anidadas context.getString
    // Activará lint LocalContextGetResourceValueCall (se debe usar stringResource durante la combinación).
    val copiedToastText = androidx.compose.ui.res.stringResource(R.string.chat_copied_toast)
    val backgroundSavedText = androidx.compose.ui.res.stringResource(R.string.chat_background_saved)
    val backgroundSaveFailedText = androidx.compose.ui.res.stringResource(R.string.chat_background_save_failed)
    val backgroundClearedText = androidx.compose.ui.res.stringResource(R.string.chat_background_cleared)
    val forwardNoSessionText = androidx.compose.ui.res.stringResource(R.string.chat_forward_no_session)
    // chat_forward_done Con parámetros, no se puede ampliar por adelantado; Utilice recursos para construir una función de búsqueda de palabras durante el período de combinación.
    // Evite acceder directamente a LocalContext para obtener el valor del recurso en la devolución de llamada lambda (lint se detendrá).
    // v2.6.3 Reparación: No debe utilizar ningún parámetro. stringResource —— Traiga a los participantes inmediatamente durante el período de combinación. String.format,
    // poner "%1$s" Completa el literal,conduciendo a abajo .replace("%1\$s", title) Nunca puede ser reemplazado,
    // toast Sólo se puede mostrar "Reenviado a"%1$s"". Tiene el mismo origen que el fallo del emoticón de la página de configuración.
    val forwardDoneFormat = androidx.compose.ui.res.stringResource(R.string.chat_forward_done)
    // Phase 8.5 Reparación: Para leer en el hilo IO clipboard(La implementación original ahora se lee sincrónicamente en el hilo principal. primaryClip,
    // es un proceso transversal IPC,Android 10+ Posible cuando el acceso en segundo plano está restringido ANR)
    val ioScope = rememberCoroutineScope()
    // ── A1: Buscar dentro de una sesión — Estado/Recuento de golpes/Salto ──
    // Cambio de barra de búsqueda in situ, término de consulta, índice del resultado actual en la lista de resultados(Restablecer con RememberSaveable al cambiar de sesión)
    var showInChatSearch by rememberSaveable { mutableStateOf(false) }
    var inChatQuery by rememberSaveable { mutableStateOf("") }
    var currentMatchIndex by rememberSaveable { mutableStateOf(0) }
    // P1-1: Cambio de modo de pantalla completa de conversación de voz en tiempo real(La máquina de estado de la conversación de voz está conectada a la página de chat.)
    var showVoiceConversation by remember { mutableStateOf(false) }
    // v2.x: comité — Invocar una discusión de chat grupal temporal desde una conversación(La conclusión retroalimenta esta conversación a través de DeferredResultStore)
    var showCommitteeDialog by remember { mutableStateOf(false) }
    // lista de resultados:La conversación actual está cargada de mensajes.,El contenido contiene palabras de consulta.(ignorar caso)noticias id;Devuelve vacío cuando la consulta está vacía o la búsqueda no está habilitada.
    // Sólo sobrescribe los mensajes cargados(OBSERVE_LIMIT dentro),Para ver el historial anterior, primero debes deslizar hacia arriba para cargar más.
    // A1 control de acceso:Si la búsqueda durante la sesión no está habilitada, no se realizará esta ronda de análisis completo. —— messages Cada vaciado durante la transmisión es una nueva instancia de lista,
    // Sin este control de acceso,displayContent+contains Se realizará un escaneo completo de la tabla repetidamente con actualizaciones.showInChatSearch al mismo tiempo que key,
    // Volver a calcular las visitas inmediatamente después de activar la búsqueda,Golpes claros al cerrar(En este momento, la barra de búsqueda ya no se muestra.).
    val findMatches =
        remember(messages, inChatQuery, showInChatSearch) {
            val q = inChatQuery.trim()
            if (!showInChatSearch || q.isEmpty()) {
                emptyList()
            } else {
                messages.filter {
                    val text = InternalMarkupSanitizer.displayContent(it.content)
                    text.isNotBlank() && text.contains(q, ignoreCase = true)
                }
                    .map { it.id.toString() }
            }
        }

    // saltar:Reutilizar la canalización setTargetMessage v2.x(Posicionamiento de desplazamiento + resaltado de texto + ventana de 2,5 s);
    // índice negativo/Envoltura de módulo fuera de límites,Apoya el artículo anterior./Salta al siguiente bucle.
    fun jumpToMatch(index: Int) {
        if (findMatches.isEmpty()) return
        val size = findMatches.size
        val safe = ((index % size) + size) % size
        currentMatchIndex = safe
        viewModel.setTargetMessage(findMatches[safe], inChatQuery.trim())
    }
    // Consulta de cambios de palabras.(O el número de visitas cambia durante la transmisión)Salta automáticamente al primer golpe cuando;La consulta vacía borra el estado resaltado
    LaunchedEffect(inChatQuery, findMatches.size) {
        if (!showInChatSearch) return@LaunchedEffect
        if (findMatches.isEmpty()) {
            viewModel.setTargetMessage(null, null)
        } else {
            currentMatchIndex = 0
            viewModel.setTargetMessage(findMatches[0], inChatQuery.trim())
        }
    }
    // Etapa 5: El modelo cambia el panel inferior al estado expandido
    val sheetState = remember { ChatSheetState() }
    // F-2: Reenviar texto de destino entre sesiones(null=No reenviar ventanas emergentes)
    var forwardText by remember { mutableStateOf<String?>(null) }
    val knowledgeDao: KnowledgeDocDao = koinInject()
    val knowledgeDocs by knowledgeDao.observeAllUser().collectAsStateWithLifecycle(initialValue = emptyList())
    // v1.95: Inyectar repositorio de configuración para lectura/Guardar el estado del mensaje ASR
    val settings: SettingsRepository = koinInject()
    val accountState by settings.accountStateFlow.collectAsStateWithLifecycle(initialValue = io.zer0.muse.data.AccountState())
    // Phase 4: La máscara de burbuja actualmente seleccionada;No seleccionado/Cuando la piel es ilegal null,MessageBubble Mantener el aspecto existente.
    // La máscara del complemento proviene del complemento ui-skin instalado(Vuelva a verificar cada lectura),Complemento deshabilitado/Después de la desinstalación, la tienda vuelve automáticamente al valor predeterminado integrado.
    val pluginSkinSource: io.zer0.muse.ui.theme.PluginSkinSource = koinInject()
    val bubbleSkinStore =
        remember(context, pluginSkinSource) {
            io.zer0.muse.ui.theme.BubbleSkinStore(context, pluginSkinSource)
        }
    val bubbleSkin by bubbleSkinStore.selectedSkinFlow.collectAsStateWithLifecycle(initialValue = null)
    val proactiveConfig by settings.proactiveMessageConfigFlow.collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.data.ProactiveMessageConfig(),
    )
    // Registro del navegador a nivel de sesión:UI Observe únicamente los administradores de sesiones que realmente se hayan creado, evite sesiones nuevas
    // Genera efectos secundarios durante Redactar y se muestra en la barra de título“No iniciado”entrada.
    val browserRegistry: io.zer0.muse.tools.BrowserManagerRegistry = koinInject()
    val activeBrowserSessions by browserRegistry.activeSessionIds.collectAsState()
    // Agent Utilice agentSessionId y currentSessionId para el chat normal y evitar sesiones entre páginas.
    val browserSessionId = if (isAgentMode) state.agentSessionId else state.currentSessionId
    val currentBrowserManager =
        remember(browserSessionId, activeBrowserSessions) {
            browserSessionId?.let(browserRegistry::getIfActive)
        }
    // v1.0.75 fix (Comentarios de los usuarios): Markdown Eliminar toda la barra de herramientas de formato,No más cambios de texto enriquecido

    // v1.0.4 (P3-4): Modo de rendimiento — Paginación de mensajes a nivel de memoria a través de MessagePaginator,
    // LazyColumn Representa solo los N elementos más recientes,Expande la página siguiente al deslizarte hacia la parte superior(Paginación de memoria local pura);
    // Expanda todo y luego deslícese hacia arriba para activar DB loadMoreHistory.
    // cuando está cerrado visibleMessages == messages,El comportamiento es completamente consistente con la lógica original.
    val performanceMode = state.chatPreferences.performanceMode
    // Las indicaciones de paginación y cantidad solo cuentan los mensajes verdaderamente renderizables, y los asistentes vacíos/shells de protocolos internos no deben ocupar cuotas.
    val renderableMessageCount by remember(messages) {
        derivedStateOf { messages.count(::isRenderableChatMessage) }
    }
    var paginatorPageCount by rememberSaveable { mutableStateOf(1) }
    // B7-03: Estado no leído en la sesión
    // Restablecer el recuento de paginación al cambiar de sesión/apagar el modo de rendimiento
    LaunchedEffect(effectiveChatSessionId(state)) {
        paginatorPageCount = 1
    }
    LaunchedEffect(performanceMode) {
        if (!performanceMode) paginatorPageCount = 1
    }
    var savedPaginatorScrollOffset by remember { mutableStateOf(0) }
    // v2.x: El estado expandido del separador de contexto (contraído por defecto) —— Después de la compresión debería verse como"Continuar desde el nuevo contexto").
    // Memoria por sesión: vuelve al estado contraído predeterminado después de cambiar de sesión, en lugar de conservar el estado expandido de la sesión anterior.
    var contextDividerExpanded by remember(state.currentSessionId, state.agentSessionId) {
        mutableStateOf(false)
    }
    // v2.x: Durante la salida de streaming, los mensajes cambiarán muchas veces por segundo.,mismo par (hidden, total) Escribí docenas de líneas repetidamente.
    // Este registro en sí es útil.(Diagnóstico "Por qué no se muestran algunos mensajes"),Por lo tanto, sólo se eliminan los duplicados, no se eliminan.
    val chatRenderLogGate = remember { ChatRenderLogGate() }
    val visibleMessages by produceState(
        initialValue =
        if (isAgentMode && !state.isAgentMode && !state.isSwitchingSession) {
            emptyList()
        } else {
            messages.filterRenderableChatMessages(state.chatPreferences.showToolCallDetails)
        },
        messages,
        paginatorPageCount,
        performanceMode,
        isAgentMode,
        state.isAgentMode,
        state.isSwitchingSession,
        // v2.x: Los cambios en los límites del punto de control o en el estado de expansión requieren que se vuelva a calcular la lista visible
        state.contextCheckpointBoundaryId,
        contextDividerExpanded,
        // v2.5.0 fix: Impacto del interruptor de visualización de llamada de herramienta“Solo carga de herramienta”Es necesario volver a calcular la calificación de representación de mensajes
        state.chatPreferences.showToolCallDetails,
    ) {
        // control de acceso:Agent Tab modo pero ViewModel aún no ha cambiado al modo Agente,Se muestra en blanco.
        // Evite mostrar el contenido del cuadro de diálogo antiguo durante la animación de HorizontalPager cuando la página de destino se haya compuesto pero setAgentMode aún no se haya ejecutado.
        // v1.137 B2: isSwitchingSession=true mantener el cuadro anterior(No claro),Eliminar la pantalla de presentación de la lista vacía.
        if (isAgentMode && !state.isAgentMode && !state.isSwitchingSession) {
            value = emptyList()
            return@produceState
        }
        val renderableMessages = messages.filterRenderableChatMessages(state.chatPreferences.showToolCallDetails)
        val hiddenShells = messages.size - renderableMessages.size
        if (chatRenderLogGate.shouldLog(hiddenShells, messages.size)) {
            Logger.d(
                "ChatRender",
                "hidden non-renderable messages=$hiddenShells, total=${messages.size}, " +
                    "session=${state.currentSessionId ?: state.agentSessionId ?: "none"}",
            )
        }
        // v2.x: Cuando se comprime el contexto, los mensajes antes del límite se contraen de forma predeterminada (la interfaz se parece a"Continuar desde el nuevo contexto").
        // Tenga en cuenta que solo afecta a la interfaz: el historial enviado al modelo es filtrado por ContextCheckpointMerge durante la fase de ensamblaje.
        // Cada uno de ellos gestiona una sección y son independientes entre sí.
        val boundary = state.contextCheckpointBoundaryId
        val boundaryIndex = if (boundary == null) {
            -1
        } else {
            renderableMessages.indexOfFirst { it.id.toString() == boundary }
        }
        val pruned =
            if (boundaryIndex > 0 && !contextDividerExpanded) {
                renderableMessages.subList(boundaryIndex, renderableMessages.size).toList()
            } else {
                renderableMessages
            }
        if (!performanceMode) {
            value = pruned
            return@produceState
        }
        val allIds = pruned.map { it.id.toString() }
        if (allIds.isEmpty()) {
            value = emptyList()
            return@produceState
        }
        val pageSize = MessagePaginator.DEFAULT_PAGE_SIZE * paginatorPageCount
        // Obtener la página de inicio(Últimos N artículos ID),Luego verifique el orden de retención de UIMessage
        val visibleIds = MessagePaginator.createFlow(allIds, pageSize = pageSize).first()
        val msgById = pruned.associateBy { it.id.toString() }
        value = visibleIds.mapNotNull { msgById[it] }
    }

    // v1.0.74 fix (Auditoría inicial 1.1): El índice global inicial del área de mensajes en LazyColumn.
    // Extras insertados condicionalmente antes y después del mensaje. item(agent_mode_hint/load_more/shimmer/subagent_task_list espera),
    // v2.x: Mensaje de anclaje para separador de contexto id —— Sólo el primer elemento de la lista visible en estado contraído está subrayado.
    // Cuando se expande, se muestran todos los mensajes antiguos y la línea ya no es necesaria (el usuario ya puede ver el texto original).
    val boundaryIdForRender: String? =
        state.contextCheckpointBoundaryId?.takeIf { !contextDividerExpanded }
    // todos"Índice local de mensajes ↔ índice mundial"Esta compensación debe agregarse a la conversión.,De lo contrario, el posicionamiento isAtBottom/scroll está completamente desalineado.
    val messageStartIndex =
        run {
            var idx = 0
            val showAgentHint =
                !state.weakToolHint.isNullOrEmpty() || !state.agentModeHint.isNullOrEmpty()
            if (showAgentHint) idx++
            if (state.isLoadingMore) idx++
            idx
        }

    // Agregación de grupos de acción(capa de renderizado):Agrupe mensajes de llamadas de herramientas consecutivos en un grupo.
    // Primera representación del grupo ToolRunCard,Las barras restantes del grupo no se renderizan. —— El índice de mensajes permanece sin cambios,posicionamiento de desplazamiento/El resaltado de búsqueda no requiere ningún ajuste.
    val showToolCallDetails = state.chatPreferences.showToolCallDetails
    // Deduplicación de productos:tarjeta de tarea(Espera los mensajes del asistente)Se ha extraído el producto de cada paso.,
    // La herramienta independiente correspondiente muestra el mensaje si se vuelve a dibujar,El usuario verá las copias superior e inferior de la misma tarjeta.
    // Aquí se calcula previamente el ID del mensaje de la herramienta "El producto ha sido representado por la tarjeta de tarea del mensaje principal".
    //
    // v2.6.8: Entregables(show_card)No dentro del alcance de la deduplicación —— La tarjeta es lo que el propio usuario quiere,
    // Debe permanecer en el flujo de mensajes como una tarjeta en línea de primera clase.;Sólo hay una línea de referencia "Tarjeta generada" para los pasos de la tarjeta de tareas.
    // De lo contrario, el usuario deberá expandir el plan de ejecución para ver la tarjeta que acaba de generar.(Fuente directa de retroalimentación de dispositivos reales.).
    val cardRenderedByTaskCard by remember(visibleMessages, state.taskCards) {
        derivedStateOf {
            val covered = toolMessageIdsCoveredByTaskCards(
                messages = visibleMessages,
                taskCardOwnerIds = state.taskCards.keys,
            )
            if (covered.isEmpty()) return@derivedStateOf emptySet()
            covered - io.zer0.muse.ui.inlineDeliverableMessageIds(visibleMessages)
        }
    }
    val groupedRuns by remember(visibleMessages, state.taskCards, showToolCallDetails) {
        derivedStateOf {
            if (!showToolCallDetails) return@derivedStateOf emptyMap()
            // procesar mensajes = Mensajes del lado del asistente sin texto final y con herramientas o pensamientos.
            //
            // v2.6.8: Entregables(show_card)Nunca te unas al grupo —— Las tarjetas de grupo están plegadas por defecto.
            // Introducir la tarjeta equivale a ocultarla nuevamente, que es lo mismo que“Puedes verlo sin ampliarlo.”De lo contrario.
            io.zer0.muse.ui.chat.ChatDisplayGrouper.group(
                messages = visibleMessages,
                isGroupable = { msg ->
                    !io.zer0.muse.ui.isInlineDeliverableTool(msg.toolCallInfo?.toolName) &&
                        // "Proceso puro"= No hay un texto final. A juzgar por el texto visible purificado, es consistente con el calibre de renderizado.
                        io.zer0.muse.transformer.InternalMarkupSanitizer
                            .displayContent(msg.content)
                            .isBlank() &&
                        (msg.toolCallInfo != null || !msg.reasoning.isNullOrBlank())
                },
                // El mensaje adjunto a la tarjeta de tarea: No ingrese al grupo (la tarjeta de tarea debe representarse sola) y no trunque el grupo.
                // De lo contrario, una ronda de respuestas se dividirá en tarjetas de "Pensamiento/Herramientas/Plan de ejecución".
                isTransparent = { msg -> state.taskCards[msg.id.toString()] != null },
            ).filterIsInstance<io.zer0.muse.ui.chat.ChatDisplayItem.Grouped>()
                .flatMap { run -> run.msgs.map { it.id.toString() to run } }
                .toMap()
        }
    }

    // v0.48: estado derivado — isAtBottom Determinar si la lista está al final(El usuario no se desplazó hacia arriba.)
    // v1.52: Apretar el umbral — Solo cuenta si la parte inferior del último elemento está dentro de la ventana gráfica"en la parte inferior",
    //        evitar"parcialmente visible=en la parte inferior"Hace que el incremento del flujo lleve al usuario de regreso al fondo.
    // v1.0.4 (P3-4): Utilizado en modo de rendimiento visibleMessages(lista de renderizado real)juez,en lugar de mensajes.
    val isAtBottom by remember(
        visibleMessages.size,
        visibleMessages.lastOrNull()?.id,
        messageStartIndex,
    ) {
        derivedStateOf {
            if (visibleMessages.isEmpty()) return@derivedStateOf true
            // v2.4.5 fix: Si ya no puede avanzar, se considerará que está al final. se insertará al final del área del mensaje shimmer(pensar/Herramientas
            // ejecutar)/Marcador de posición de imagen y vídeo/Elementos finales, como tarjetas de tareas de subagente,Requisitos de juicio antiguos"El último elemento visible es exactamente el último.
            // un mensaje",Siempre que aparecen estos términos, siempre son false —— Es obvio que está al final pero se juzga que aún no está al final.,y luego poner
            // userScrolledUp Bloquear, detener el seguimiento automático(Comentarios de los usuarios:Tienes que desplazarte manualmente hasta el final cada vez que respondas.).
            if (!listState.canScrollForward) return@derivedStateOf true
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            val viewportEnd = listState.layoutInfo.viewportEndOffset
            // Llegó el último artículo visible/Más allá del final del área de mensajes(acomodar elementos finales),Y su parte inferior está cerca de la parte inferior de la ventana gráfica.
            // v1.0.74 fix (Auditoría inicial 1.1): Implementación original lastVisible.index == visibleMessages.lastIndex
            // Utilice índices globales en lugar de índices locales,agent_hint/load_more Siempre es falso cuando existe un elemento adicional.
            lastVisible.index >= messageStartIndex + visibleMessages.lastIndex &&
                (lastVisible.offset + lastVisible.size) <= viewportEnd + 150
        }
    }

    // v1.53-A1: Desliza hacia arriba para cargar más noticias históricas — Llegar al principio de la lista(firstVisibleItemIndex==0)y se activa cuando se cumplen las condiciones.
    // Configuración de ViewModel después de activar lastHistoryLoadCount,El LaunchedEffect a continuación ajusta la posición de desplazamiento en consecuencia,
    // Omitir la cantidad de elementos recién insertados,Hacer que firstVisibleItemIndex se convierta en lastHistoryLoadCount(>0),
    // evitando así repetidos disparos en la parte superior(El usuario debe deslizar activamente hacia arriba nuevamente para continuar con la carga.).
    var savedScrollOffset by remember { mutableStateOf(0) }
    val loadMoreTrigger by remember(
        state.hasMoreHistory,
        state.isLoadingMore,
        state.isStreaming,
        messages.isNotEmpty(),
        performanceMode,
        visibleMessages.size,
        renderableMessageCount,
    ) {
        derivedStateOf {
            state.hasMoreHistory &&
                !state.isLoadingMore &&
                !state.isStreaming &&
                messages.isNotEmpty() &&
                listState.firstVisibleItemIndex == 0 &&
                // v1.0.4 (P3-4): En el modo de rendimiento, la carga de la base de datos solo se activa cuando visibleMessages ha cubierto todos los mensajes.,
                // De lo contrario, paginatorLoadMoreTrigger expande primero la paginación de memoria.
                (!performanceMode || visibleMessages.size >= renderableMessageCount)
        }
    }
    LaunchedEffect(loadMoreTrigger) {
        if (loadMoreTrigger) {
            // registro actual offset,Mantenga la posición visual al saltar a una nueva ubicación después de completar la carga
            savedScrollOffset = listState.firstVisibleItemScrollOffset
            viewModel.loadMoreHistory()
        }
    }
    // B7-03: Marcar automáticamente como leído
    LaunchedEffect(isAtBottom, messages.lastOrNull()?.id) {
        if (isAtBottom) viewModel.markSessionRead()
    }
    // v1.0.4 (P3-4): Activador de paginación de memoria en modo de rendimiento — Cuando se llega al tope de mensajes y hay mensajes más antiguos que no se han procesado,
    // Expandir paginatorPageCount(Paginación de memoria local pura,no comprobar DB). Después de la expansión, use scrollToItem para mantener la posición visual sin saltar.
    val paginatorLoadMoreTrigger by remember(
        performanceMode,
        renderableMessageCount,
        visibleMessages.size,
        state.isStreaming,
    ) {
        derivedStateOf {
            performanceMode &&
                renderableMessageCount > visibleMessages.size &&
                !state.isStreaming &&
                listState.firstVisibleItemIndex == 0
        }
    }
    LaunchedEffect(paginatorLoadMoreTrigger) {
        if (paginatorLoadMoreTrigger) {
            // registro actual offset,Saltar a una nueva ubicación después de cargar para mantener la posición visual
            savedPaginatorScrollOffset = listState.firstVisibleItemScrollOffset
            val previousSize = visibleMessages.size
            paginatorPageCount++
            // Espere a que visibleMessages se recalcule y crezca(produceState Actualizaciones asincrónicas)
            withTimeoutOrNull(1000L) {
                snapshotFlow { visibleMessages.size }
                    .filter { it > previousSize }
                    .first()
            }
            // Saltar a una nueva ubicación una vez completada la carga(El mensaje que estaba en la parte superior ahora está en la posición addCount)
            val addedCount = visibleMessages.size - previousSize
            if (addedCount > 0) {
                // v1.0.74 fix (Auditoría inicial 1.1): Agregar desplazamiento inicial del área de mensaje,agent_hint/load_more El posicionamiento no es correcto cuando existe.
                listState.scrollToItem(messageStartIndex + addedCount, savedPaginatorScrollOffset)
            }
            savedPaginatorScrollOffset = 0
        }
    }
    // v1.53-A1: Ajuste la posición de desplazamiento después de que se complete la carga,Evita que la posición visual salte.
    // (El mensaje que estaba en la parte superior ahora está en lastHistoryLoadCount)
    // v1.0.4 (P3-4): En modo rendimiento,DB Sincronizar extensiones después de cargar más paginatorPageCount,
    // Permitir que visibleMessages contenga mensajes antiguos recién cargados(De lo contrario, visibleMessages siguen siendo los últimos N mensajes,No puedo ver los mensajes antiguos recién cargados).
    LaunchedEffect(state.lastHistoryLoadCount) {
        if (state.lastHistoryLoadCount > 0) {
            if (performanceMode) {
                paginatorPageCount++
                // Espere a que se vuelvan a calcular los mensajes visibles y sobrescriba todos messages
                val targetSize = renderableMessageCount
                withTimeoutOrNull(1000L) {
                    snapshotFlow { visibleMessages.size }
                        .filter { it >= targetSize }
                        .first()
                }
            }
            // v1.0.74 fix (Auditoría inicial 1.1): Agregar desplazamiento inicial del área de mensaje,agent_hint/load_more El posicionamiento no es correcto cuando existe.
            listState.scrollToItem(messageStartIndex + state.lastHistoryLoadCount, savedScrollOffset)
            viewModel.clearHistoryLoadCount()
            savedScrollOffset = 0
        }
    }
    // v2.6.7: eliminar“El borrador ha sido restaurado.”toast con la etiqueta "Borrador" del campo de entrada.
    // Las palabras originales del usuario son: No se requieren indicaciones ni respuestas especiales, simplemente persista el contenido.
    // Si hay palabras en el cuadro de entrada, trátelas como palabras. —— Fue creado originalmente por el propio usuario, por lo que no es necesario recordárselo.

    // v2.x: Haga clic en el mensaje para saltar de los resultados de búsqueda. — Desplácese hasta el mensaje de destino y resáltelo brevemente.
    //
    // Condición de activación:state.targetMessageId No vacío(por SearchScreen onOpenMessage →
    // MainActivity switchSession + setTargetMessage ajustes).
    //
    // proceso:
    //  1. etc. visibleMessages contiene el mensaje de destino(tiempo de espera 5s,Anular la carga asincrónica de switchSession)
    //     — switchSession currentSessionId cambia cuando se completa la rutina,Este efecto lanzado se reactiva(Antigua rutina cancelada)
    //  2. En el modo de rendimiento, si los mensajes contienen el mensaje de destino pero visibleMessages no está cubierto,extensión temporal paginatorPageCount
    //  3. scrollToItem al índice correspondiente(Instantáneo,Sin animación,Evite retrasos en animaciones de sesiones largas)
    //  4. Retrase 2,5 segundos y luego ajuste clearHighlightedMessage para dejar de resaltar.
    //  5. Llame a consumeTargetMessage para borrar targetMessageId,Evite los desencadenantes repetidos
    //
    // Límite:Si el mensaje de destino está en un historial anterior(más allá OBSERVE_LIMIT=200 Artículo),Los usuarios deben deslizar manualmente hacia arriba para cargar más,
    //       Este LaunchedEffect expirará y abandonará el posicionamiento del desplazamiento.(Borrar solo targetMessageId,No activa el resaltado).
    //
    // Implementación destacada:MessageBubble El parámetro texto resaltado,Vea la llamada dentro de itemsIndexados a continuación.
    LaunchedEffect(state.targetMessageId, effectiveChatSessionId(state)) {
        val targetId = state.targetMessageId ?: return@LaunchedEffect
        if (targetId.isBlank()) return@LaunchedEffect

        // En modo rendimiento:Si los mensajes ya contienen el mensaje de destino,extensión temporal paginatorPageCount
        // Deje que visibleMessages cubra todo messages(Para saltar a la escena, necesitas ver el mensaje de destino.)
        if (performanceMode && messages.any { it.id.toString() == targetId }) {
            val pageSize = MessagePaginator.DEFAULT_PAGE_SIZE
            val requiredPages = (messages.size + pageSize - 1) / pageSize
            if (paginatorPageCount < requiredPages) {
                paginatorPageCount = requiredPages
            }
        }

        // Espere a que visibleMessages contenga el mensaje de destino
        // (Tiempo de espera 5 segundos: carga asincrónica de switchSession + cálculo asincrónico de visibleMessages en modo de rendimiento)
        val targetIndex =
            withTimeoutOrNull(5000L) {
                snapshotFlow { visibleMessages }
                    .filter { list -> list.any { it.id.toString() == targetId } }
                    .first()
                    .indexOfFirst { it.id.toString() == targetId }
            }
        if (targetIndex == null || targetIndex < 0) {
            // Tiempo de espera no encontrado:Borrar solo targetMessageId,No activa el resaltado
            // (Es posible que el mensaje se haya eliminado/no esté en los mensajes OBSERVE_LIMIT recientes/no esté en la sesión actual)
            viewModel.consumeTargetMessage()
            return@LaunchedEffect
        }
        // Desplácese instantáneamente al mensaje de destino(Las animaciones en sesiones largas pueden congelarse.,Y la escena del salto requiere posicionamiento en tiempo real.)
        // v1.0.74 fix (Auditoría inicial 1.1): Agregar desplazamiento inicial del área de mensaje
        listState.scrollToItem(messageStartIndex + targetIndex)
        // La ventana resaltada se borrará después de 2,5 segundos. highlightedMessageId + searchHighlightQuery
        delay(2500L)
        viewModel.clearHighlightedMessage()
        // Borrar targetMessageId,Evite los desencadenantes repetidos
        viewModel.consumeTargetMessage()
    }
    // v1.28: Número de últimos mensajes,usado para distinguir"Usuario envía mensaje"y"delta de transmisión"
    // v1.45: Guardar con recordarGuardable,Evite los cortes de página/Restablecer en segundo plano provoca que se desplace hacia abajo por error
    var lastMessageCount by rememberSaveable { mutableStateOf(0) }
    // v2.0: Seguir automáticamente en su lugar"Cambio de ID del último mensaje de usuario"juicio —— La implementación anterior requiere que el último elemento en el instante de muestreo sea USER,
    // Cuando los mensajes de usuario y la ocupación del asistente llegan entre dos puntos de muestreo, se perderá el juicio.,La lista de mensajes se detendrá en la posición anterior después del envío.
    var lastSeenUserMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    // v2.0: Restablecer la línea base de seguimiento al cambiar de sesión,Evite arrastrar nuevas conversaciones directamente al final.
    var trackedSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    // Reparación de auditoría (8.5): Cuadro de diálogo de confirmación de eliminación de selección múltiple
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // CHAT-10: Descartar llamadas de herramientas no finalizadas requiere confirmación
    var showDiscardConfirm by remember { mutableStateOf(false) }
    // Reparación de auditoría (2.3): primera marca de combinación — inicial lastMessageCount=0 La primera entrada a una sesión no vacía será
    // Mal juzgado como"nuevas noticias"Activar desplazamiento automático hacia abajo,Anula la ubicación de restauración/búsqueda de v1.45.
    var firstCompositionDone by rememberSaveable { mutableStateOf(false) }

    // v1.52: Optimización de deslizamiento y mano. — El usuario se desliza activamente hacia arriba y luego bloquea userScrolledUp=true,
    // El incremento de transmisión ya no regresa automáticamente al final,hasta que el usuario haga clic"llegar al fondo"o deslícelo manualmente hacia abajo para desbloquearlo.
    // esto resuelto"Cuando el asistente envía un mensaje, el usuario desliza hacia arriba y es arrastrado al principio de la generación."problema.
    var userScrolledUp by rememberSaveable { mutableStateOf(false) }
    // bandera de desplazamiento del programa:animateScrollToItem Configuración del período true,Evite errores de juicio cuando el usuario se desplaza en la devolución de llamada al final del desplazamiento
    val isProgrammaticScroll = remember { mutableStateOf(false) }
    // v1.0.92: Una breve ventana de enfriamiento después del final del programa."final del desplazamiento"Los eventos siempre están ahí animateScrollToItem
    // SnapshotFlow lo consume solo después de regresar.,Restablecer isProgrammaticScroll inmediatamente juzgará mal el desplazamiento del programa como
    // desplazamiento del usuario,Bloquear errores de userScrolledUp — Se muestra como"El asistente está enviando un mensaje.,La lista sigue vigente".
    var programmaticScrollCooldownUntil by remember { mutableStateOf(0L) }

    // v2.2.1: Agent Después de volver a entrar a la página,Espere a que el mensaje y el diseño estén listos.,Restaurar a la posición antes de salir de una sola vez.
    // Los mensajes se cargan de forma asíncrona./La lista es posterior a cuando se creó la combinación.,Confiar simplemente en el valor inicial de RememberLazyListState caerá en el lugar equivocado.
    var agentScrollRestored by remember { mutableStateOf(false) }
    LaunchedEffect(state.isAgentMode, state.isSwitchingSession, visibleMessages.size) {
        if (!state.isAgentMode || agentScrollRestored || state.isSwitchingSession) return@LaunchedEffect
        val target = state.agentListFirstVisibleItemIndex
        if (target < 0 || visibleMessages.isEmpty()) return@LaunchedEffect
        val total =
            withTimeoutOrNull(2_000L) {
                snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
            } ?: return@LaunchedEffect
        agentScrollRestored = true
        listState.scrollToItem(target.coerceIn(0, total - 1), state.agentListFirstVisibleItemScrollOffset)
        // Cuando no está en la parte inferior, se considera como"Dónde se alojan los usuarios",evitar mensajes nuevos/El incremento de transmisión arrastra la lista de regreso al final
        userScrolledUp = listState.canScrollForward
    }

    // Escuche el final del desplazamiento.:solo en"Desplazamiento activado por el usuario"Actualizar el estado de bloqueo cuando haya terminado
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { !it } // sólo al final del desplazamiento( false )desencadenado cuando
            .collect {
                if (!isProgrammaticScroll.value &&
                    System.currentTimeMillis() >= programmaticScrollCooldownUntil
                ) {
                    // H-S4: agregar un retraso de fotograma(~16ms),Evite el momento de salto isScrollInProgress. La información de diseño sigue siendo una instantánea del desplazamiento.
                    delay(16)
                    // v1.0.92: Verifique el enfriamiento nuevamente después del retraso para evitar que se inicie otro programa durante el período de espera./fin
                    if (System.currentTimeMillis() >= programmaticScrollCooldownUntil) {
                        // El desplazamiento del usuario finaliza:Actualizar el estado de bloqueo según la ubicación actual
                        userScrolledUp = !isAtBottom
                    }
                }
            }
    }

    // v1.0.30: Desplazamiento de seguimiento de transmisión precalculado (@Composable no se puede ajustar dentro de snapshotFlow)
    // v2.0: Seguir en su lugar"Último mensaje alineado en la parte inferior de la ventana gráfica",120dp Desplazamiento superior eliminado(Vea a continuación para seguir la lógica.).

    // Desplácese automáticamente hacia abajo cuando lleguen nuevos mensajes(v0.31: Controlado por chatPrefs.autoScrollToBottom)
    // v0.48: Sólo si el usuario ya está en la parte inferior(isAtBottom)Desplazarse automáticamente cuando,Los usuarios se desplazan activamente hacia arriba para ver el historial sin interrumpir
    // v1.28: Cuando un usuario envía un mensaje(El número de mensajes aumenta)Utilice el desplazamiento instantáneo(snap),Evite animaciones que provoquen saltos de página.;
    //        La transmisión aumenta con un desplazamiento suave(animate),Seguir solo si ya estás en la parte inferior
    // v1.52: Cambiar al control de bandera userScrolledUp — Los usuarios deslizan hacia arriba una vez para dejar de seguir,
    //        Ya no depende de la derivación isAtBottom(Evite el crecimiento del contenido que lleve a un juicio erróneo de isAtBottom).
    // M-CS1: usar snapshotFlow + sample(100ms) desaceleración,Evite cambios frecuentes en el contenido de streaming
    //        Cancelar reinicio LaunchedEffect causando paliza.
    LaunchedEffect(Unit) {
        snapshotFlow {
            Triple(
                messages.size,
                messages.lastOrNull()?.content,
                state.chatPreferences.autoScrollToBottom,
            )
        }
            .distinctUntilChanged()
            .sample(300L)
            .collect { (size, _, autoScroll) ->
                if (size == 0) return@collect
                if (!autoScroll) return@collect
                // Reparación de auditoría (2.3): Al ingresar a una sesión que no esté vacía por primera vez, no se desplazará automáticamente hacia abajo.,
                // Deje que v1.45 restaure la ubicación/posicionamiento de búsqueda entre en vigor;Sólo se registra la línea de base.
                if (!firstCompositionDone) {
                    firstCompositionDone = true
                    lastMessageCount = size
                    trackedSessionId = effectiveChatSessionId(state)
                    lastSeenUserMessageId = visibleMessages.lastOrNull { it.role == MessageRole.USER }?.id?.toString()
                    return@collect
                }
                // Instantánea visibleMessages,Evite la actualización asincrónica de produceState, lo que hace que el espacio entre guard y call quede vacío
                val msgs = visibleMessages
                if (msgs.isEmpty()) return@collect
                // v2.0: Solo restablezca la línea base de seguimiento al cambiar de sesión,Sin desplazamiento(De lo contrario, la nueva sesión se arrastrará directamente al final.)
                val sessionId = effectiveChatSessionId(state)
                if (sessionId != trackedSessionId) {
                    trackedSessionId = sessionId
                    lastSeenUserMessageId = msgs.lastOrNull { it.role == MessageRole.USER }?.id?.toString()
                    lastMessageCount = size
                    return@collect
                }
                val targetIndex = msgs.size - 1
                // v2.0: usar"Si cambia la identificación del último mensaje de usuario"Determinar nuevos mensajes enviados por los usuarios.
                // antigua implementación (`size > lastMessageCount && last?.role == USER`) En mensajes de usuario y
                // Cuando el asistente aterrice en el suelo entre dos puntos de muestreo, se perderá la detección.,Hace que la lista se detenga en la posición del mensaje anterior y no siga.
                val currentLastUserId = msgs.lastOrNull { it.role == MessageRole.USER }?.id?.toString()
                val isUserSendMessage = currentLastUserId != null && currentLastUserId != lastSeenUserMessageId
                lastSeenUserMessageId = currentLastUserId
                // v1.0.90: Desplazamiento programado unificado plus"Si el usuario ya está en la parte inferior"prejuicio.
                // Resulta que ambas ramas se desplazan incondicionalmente, lo que provoca dos problemas del mundo real:
                //   1) Envíe un mensaje cuando retroceda en el historial y la lista se arrastrará directamente al final;
                //   2) Aparece el lado del asistente"pensando"O cuando se agrega en forma de transmisión, el usuario que está leyendo el contenido en el medio regresa al final.
                // v2.0: Cuando los usuarios envían mensajes ellos mismos, relájese"Los usuarios siguen sin desplazarse activamente hacia arriba.",
                // Porque los mensajes nuevos se configurarán primero como isAtBottom false,Continuar solicitando atBottom no se desplazará directamente.
                val atBottom = isAtBottom
                // v2.0: rodar a"parte inferior del último mensaje"En lugar de fijar la parte superior del mensaje a 120 dp.
                // Antiguo desplazamiento de 120 dp en mensajes largos/La transmisión de datos adjuntos hará que el texto nuevo se caiga de la pantalla,
                // Mira y siente"Siguiendo el mensaje anterior".
                val targetGlobalIndex = messageStartIndex + targetIndex
                if (isUserSendMessage && (atBottom || !userScrolledUp)) {
                    // El usuario acaba de enviar un mensaje y está en la parte inferior.(O no apareció activamente):Quédese instantáneamente en el último lugar de la lista,y desbloquear seguir
                    userScrolledUp = false
                    // v1.0.92: Consume el siguiente evento de fin de desplazamiento del programa,Evite el bloqueo accidental
                    programmaticScrollCooldownUntil = System.currentTimeMillis() + 250L
                    // v2.x: Desplácese directamente hasta el final de la lista hasta que ya no pueda desplazarse. — Originales“Desplazamiento inferior del mensaje”La fórmula está encima de la ventana gráfica.
                    // Los mensajes largos van en la dirección opuesta.,La parte superior del mensaje se fija y el contenido nuevo se desplaza fuera de la pantalla.(Comentarios de los usuarios:Seguido por
                    // “último mensaje”en lugar del contenido más reciente).
                    var snapGuard = 0
                    while (listState.canScrollForward && snapGuard < 100) {
                        listState.scrollBy(SNAP_SCROLL_STEP_PX)
                        snapGuard++
                    }
                } else if (!userScrolledUp) {
                    // v2.0.1: La siguiente condición se simplifica a"El usuario no se desplazó activamente hacia arriba." — viejas condiciones (atBottom || isStreaming)
                    // Durante la fase de ejecución de la herramienta, dejará de seguir: el crecimiento del contenido hace isAtBottom=false, mientras espera herramientas isStreaming=false,
                    // Si ambos no son válidos al mismo tiempo, la lista se congelará (los comentarios de los usuarios regresan: el último elemento no siempre se sigue al generar).
                    // userScrolledUp Es la única señal de intención: detenerse al subir, retroceder hasta el fondo o hacer clic."llegar al fondo"Eso está desbloqueado.
                    // v1.0.92: Si se vuelve a llamar a la última animación siguiente antes de que finalice, se cancelará./reiniciar animación,provocando saltos visuales;
                    // Omita este muestreo mientras la animación esté en progreso.,Una vez completada la animación, el siguiente punto de muestreo continuará naturalmente.
                    if (!listState.isScrollInProgress) {
                        isProgrammaticScroll.value = true
                        try {
                            // v2.x: solo para“¿Qué tan lejos está del final de la lista?”Hacer desplazamiento animado hacia adelante — Preciso, nunca regrese a
                            // Parte superior del mensaje;Salta la última barra si no está en la ventana gráfica.(El próximo período de muestreo continúa).
                            // v2.4.5 fix: usar >= en vez de == —— se insertará al final del área del mensaje shimmer(pensar/Herramientas
                            // ejecutar)/Marcador de posición de imagen y vídeo/Elementos finales, como tarjetas de tareas de subagente,Requisitos de juicio antiguos"último elemento visible
                            // Exactamente el último mensaje.",Una vez que aparecen estos elementos, son false,Una vez durante todo el período de respuesta
                            // no seguir,Los usuarios deben desplazarse manualmente hasta el final para ver el texto completo.(Comentarios de los usuarios).
                            val info = listState.layoutInfo
                            val lastVisible = info.visibleItemsInfo.lastOrNull()
                            if (lastVisible != null && lastVisible.index >= targetGlobalIndex) {
                                val missing = (lastVisible.offset + lastVisible.size) - info.viewportEndOffset
                                if (missing > 0) {
                                    listState.animateScrollBy(missing.toFloat())
                                } else if (listState.canScrollForward) {
                                    // El elemento final se pega en la parte inferior, pero la lista aún se puede desplazar hacia adelante.:Avanza una pantalla,Lleva los elementos finales más abajo en la ventana gráfica.
                                    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
                                    if (viewport > 0f) listState.animateScrollBy(viewport)
                                }
                            }
                        } finally {
                            isProgrammaticScroll.value = false
                            // v1.0.92: El consumo sigue"final del desplazamiento"evento,Evite el bloqueo accidental(Ver comentarios de los oyentes)
                            programmaticScrollCooldownUntil = System.currentTimeMillis() + 250L
                        }
                    }
                }
                lastMessageCount = size
            }
    }

    // v0.36 Optimización del rendimiento:modelName No tiene nada que ver con los nombres de los modelos a nivel de sesión.,Mencione el alcance de ChatScreen para evitar búsquedas repetidas de cada elemento.
    val modelName =
        remember(state.providers, state.activeProviderId, state.selectedModelId) {
            state.providers
                .firstOrNull { it.id == state.activeProviderId }?.models
                ?.firstOrNull { it.id == state.selectedModelId }?.name
        }
    val assistantDisplayName = state.currentAssistant?.name?.takeIf { it.isNotBlank() }
    val displayModelName =
        if (state.currentAssistant?.useAssistantName == true) {
            assistantDisplayName ?: modelName
        } else {
            modelName
        }

    // v0.51: Observar una vez toast(Avisos de cambio de modelo, etc.),Revienta la tostada cuando no esté vacía y retírala inmediatamente.,Evitar repetidos bombardeos de reorganización.
    LaunchedEffect(state.toast) {
        state.toast?.let { msg ->
            // M-S7: toast Envuelto con runCatching,Evite la excepción del sistema que provoca que LaunchedEffect se interrumpa
            runCatching { MuseToast.show(msg) }
            viewModel.clearToast()
        }
    }

    // v1.49: Después de eliminar el reconocimiento sin conexión de Vosk,La entrada de voz cambió a dos rutas:
    //  - si API Key(DashScope/Step):Mantenga presionado para grabar + suelte para reconocer + deslice hacia arriba para cancelar(Vaya a la ruta API ChatViewModel)
    //  - Ninguno API Key(SYSTEM):Mantenga presionado el micrófono → aflojar → Reconocimiento de voz del sistema emergente Intent → texto de relleno de devolución de llamada
    val onSpeechResult: (String) -> Unit = { text ->
        if (text.isNotBlank()) {
            val current = viewModel.state.value.input
            val merged = if (current.isBlank()) text else "$current $text"
            viewModel.updateInput(merged)
        }
    }

    // SYSTEM camino:Sistema de reconocimiento de voz Intent launcher(Sin clave API fallback)
    var systemRecording by rememberSaveable { mutableStateOf(false) }
    val speechLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            systemRecording = false
            val text = SpeechInput.parseResult(result.resultCode, result.data?.extras)
            if (text != null) {
                onSpeechResult(text)
            }
        }

    // P6-B2: RECORD_AUDIO Aplicación de permiso de tiempo de ejecución(API Ruta de grabación requerida,SYSTEM Intent Las rutas son manejadas por el sistema.)
    val micPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                // La ventana emergente de permiso interrumpirá el gesto de pulsación larga original,Después de una autorización exitosa, el usuario debe presionar y mantener presionado nuevamente,Evite activar accidentalmente la grabación ilimitada.
                MuseToast.show(context.getString(R.string.chat_mic_permission_granted))
            } else {
                viewModel.reportError(context.getString(R.string.chat_err_mic_permission))
            }
        }

    // P5-E: Selección de documentos launcher — Después de la selección, ViewModel lo lee y analiza.
    val documentLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            uri?.let { viewModel.pickDocument(it, context) }
        }

    // v2.6.7: Solicitud de modelo para leer el archivo de usuario —— El lado de la herramienta cuelga, aquí se juega el selector del sistema para devolver los resultados.
    // Los modelos no pueden especificar rutas por sí mismos (SAF El usuario debe hacer clic en él personalmente), por lo que esta solicitud sólo podrá realizarse-Enlace de respuesta.
    val fileAccessRequest by
        io.zer0.muse.tools.FileAccessRequestBridge.pending.collectAsStateWithLifecycle()
    val fileAccessLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            val request = fileAccessRequest
            if (request != null) {
                if (uri == null) {
                    io.zer0.muse.tools.FileAccessRequestBridge.deliverNoPick(request.id)
                } else {
                    // v2.6.7 fix (AUDIT F11): Las lecturas se colocan en el hilo IO.
                    // La implementación original lee hasta 200.000 caracteres sincrónicamente en la devolución de llamada ActivityResult;
                    // ContentResolver Cuando apunta al URI del disco de la nube, equivale a esperar la red en el hilo principal, lo que puede provocar ANR.
                    ioScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val result = io.zer0.muse.tools.FileAccessRequestBridge.readPickedFile(context, uri)
                        io.zer0.muse.tools.FileAccessRequestBridge.deliver(request.id, result)
                    }
                }
            }
        }
    LaunchedEffect(fileAccessRequest?.id) {
        val request = fileAccessRequest ?: return@LaunchedEffect
        val types = request.mimeTypes.toTypedArray().ifEmpty { arrayOf("*/*") }
        fileAccessLauncher.launch(types)
    }

    // v1.0.30: Ocultar automáticamente el teclado después de enviar
    val focusManager = LocalFocusManager.current

    // v1.135: selección de medios launcher — Admite imágenes y vídeos.
    // Haga clic en el botón de foto para llevarlo al selector de medios visuales unificados.;El video será extraído y degradado a imágenes para su envío.
    // Correcciones en la interfaz (persistencia-11): OCR cambio de marca rememberSaveable,Una vez restablecido el proceso, la devolución de llamada de selección de imágenes aún se procesa como se pretendía originalmente.
    var imagePickAsOcr by rememberSaveable { mutableStateOf(false) }
    // El álbum de fotos admite la selección por lotes: seleccione varias imágenes a la vez/Se agregan varios videos a la cola para enviarlos uno por uno.
    // La implementación anterior es PickVisualMedia (selección única). Si desea enviar varias fotos, deberá abrir el álbum repetidamente.
    val visualMediaLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_MULTI_PICK),
        ) { uris ->
            uris.forEach { uri ->
                when (context.contentResolver.getType(uri)?.startsWith("video/")) {
                    true -> viewModel.pickVideo(uri, context)
                    else -> viewModel.pickImage(uri, context, imagePickAsOcr)
                }
            }
        }

    // v2.6.6: Archivo adjunto universal —— Admite audio,PDF Espere el tipo que PickVisualMedia no puede seleccionar.
    // v1.0.74: Fondo de chat(charlar/Agent compartir) — La página de configuración es personalizable,La imagen de fondo se coloca debajo de la capa del mensaje.
    // v2.6.2(comentarios de los usuarios): Tres niveles abajo — nivel de sesión > nivel asistente > Incumplimiento global.
    val settingsRepo = org.koin.compose.koinInject<io.zer0.muse.data.SettingsRepository>()
    val globalChatBackground by settingsRepo.chatBackgroundFlow.collectAsState(initial = null)
    // v2.6.2(comentarios de los usuarios): Fondo dedicado al modo oscuro (opcional)
    val darkChatBackground by settingsRepo.chatBackgroundDarkFlow.collectAsState(initial = null)
    val isDarkTheme = io.zer0.muse.ui.theme.LocalIsDarkTheme.current
    val assistantBackgrounds by settingsRepo.assistantChatBackgroundFlow.collectAsState(initial = emptyMap())
    val sessionBackgrounds by settingsRepo.sessionChatBackgroundFlow.collectAsState(initial = emptyMap())
    // Sesión activa actual id(Agent Para el modo agentSessionId)
    val effectiveSessionIdForBg = if (isAgentMode) state.agentSessionId else state.currentSessionId
    val effectiveAssistantIdForBg = state.currentAssistant?.id ?: "default"
    // El valor predeterminado global está disponible en modo oscuro“Fondo oscuro dedicado”Superposición (si no está configurada, continúe usando el fondo principal)
    val effectiveGlobalBackground = if (isDarkTheme && !darkChatBackground.isNullOrBlank()) {
        darkChatBackground
    } else {
        globalChatBackground
    }
    // Tres niveles de respaldo: nivel de sesión → nivel asistente → incumplimiento global
    val chatBackground: String? =
        io.zer0.muse.data.resolveChatBackground(
            sessionId = effectiveSessionIdForBg,
            assistantId = effectiveAssistantIdForBg,
            sessionBackgrounds = sessionBackgrounds,
            assistantBackgrounds = assistantBackgrounds,
            globalBackground = effectiveGlobalBackground,
        )

    // v2.6.2(comentarios de los usuarios): Selector de fondo para esta conversación (más entradas de menú)
    val sessionBgLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            val sid = effectiveSessionIdForBg
            if (uri != null && !sid.isNullOrBlank()) {
                ioScope.launch {
                    val path = withContext(Dispatchers.IO) {
                        io.zer0.muse.ui.settings.ChatBackgroundStore.saveFromUri(
                            context,
                            uri,
                            io.zer0.muse.ui.settings.ChatBackgroundStore.sessionSlot(sid),
                            unique = true,
                        )
                    }
                    if (path != null) {
                        settingsRepo.saveSessionChatBackground(sid, path)
                        MuseToast.show(backgroundSavedText)
                    } else {
                        MuseToast.show(backgroundSaveFailedText)
                    }
                }
            }
        }
    // E3: Fondo degradado dinámico — Sólo tiene efecto cuando no se establece ninguna imagen de fondo(La imagen de fondo tiene prioridad)
    val chatGradient by org.koin.compose.koinInject<io.zer0.muse.data.SettingsRepository>()
        .chatGradientFlow
        .collectAsState(initial = null)

    // v2.6: vidrio liquido — Configuración unificada para todas las aplicaciones.
    // clave:backdrop La capa sólo debe incluir“fondo en sí”,El contenido es su nodo hermano.
    // Si el contenido(Contiene componentes de vidrio.)Bao Jin layerBackdrop,formará“toma de vidrio backdrop → backdrop Contiene vidrio”
    // recursión de renderizado,Causa desbordamiento de la pila RenderThread(SIGSEGV,No informar fallas de Java).
    val glassConfig = io.zer0.muse.ui.theme.LocalLiquidGlass.current
    val realGlassAvailable = io.zer0.muse.ui.theme.isRealGlassSupported()
    val glassActive = glassConfig.enabled && realGlassAvailable
    // Capa de fondo exclusiva para esta página.:Muestra solo la imagen de fondo de esta página./gradiente,Isla Dinglan/El vidrio que entra a la isla tiene una textura realista.
    val pageLayerBackdrop =
        if (glassActive) com.kyant.backdrop.backdrops.rememberLayerBackdrop() else null
    val pageWaterState =
        if (glassActive) io.github.fletchmckee.liquid.rememberLiquidState() else null
    // v2.6: ¿El fondo que se revela a través del cristal es oscuro? —— Se utiliza para hacer que el color del contenido en la isla sea adaptable.(fondo oscuro→personajes superficiales).
    // Es imposible obtener el brillo de la imagen de forma económica cuando hay una imagen de fondo.,Tratamiento conservador y oscuro.(Los fondos de pantalla oscuros son más comunes);
    // Cuando no hay imagen de fondo, se juzga por el brillo del color en ambos extremos del degradado.
    val backgroundIsDark =
        when {
            !chatBackground.isNullOrBlank() -> true
            // P3-4(informe de auditoría): eliminar !! —— chatGradient es un atributo que acepta valores NULL,
            // Kotlin No existe una conversión inteligente de atributos, así que utilice ?.let Emprender.
            else -> chatGradient?.let { g ->
                val start = Color(g.startColorArgb.toInt())
                val end = Color(g.endColorArgb.toInt())
                (start.luminance() + end.luminance()) / 2f < 0.5f
            } ?: (androidx.compose.material3.MaterialTheme.colorScheme.background.luminance() < 0.5f)
        }

    Box(modifier = modifier.fillMaxSize()) {
        // capa de fondo(backdrop fuente de muestreo):Imagen de fondo/degradado de dos colores/fondo de color sólido.
        // Solo se incluye el fondo.,No incluye contenido —— Esta es la clave para evitar la recursividad en el renderizado.
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .then(
                    if (pageLayerBackdrop != null) {
                        Modifier.layerBackdrop(pageLayerBackdrop)
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (pageWaterState != null) {
                        Modifier.liquefiable(pageWaterState)
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (!chatBackground.isNullOrBlank()) {
                io.zer0.muse.ui.SmartImage(
                    model = chatBackground,
                    contentDescription = stringResource(R.string.chat_background_cd),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // E3: Pavimento degradado lineal bicolor(arriba a la izquierda→abajo a la derecha);Acceso a variables locales,delegated property incapaz smart cast
                val gradient = chatGradient
                if (gradient != null) {
                    androidx.compose.foundation.layout.Box(
                        modifier =
                        Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    colors =
                                    listOf(
                                        Color(gradient.startColorArgb.toInt()),
                                        Color(gradient.endColorArgb.toInt()),
                                    ),
                                ),
                            ),
                    )
                }
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(
            io.zer0.muse.ui.theme.LocalLayerBackdrop provides
                (pageLayerBackdrop ?: io.zer0.muse.ui.theme.LocalLayerBackdrop.current),
            io.zer0.muse.ui.theme.LocalWaterGlassState provides
                (pageWaterState ?: io.zer0.muse.ui.theme.LocalWaterGlassState.current),
            io.zer0.muse.ui.theme.LocalGlassIsBackgroundDark provides backgroundIsDark,
        ) {
            MusePageScaffold(
                // v1.0.72 fix: El área de contenido insertada es responsable de la barra superior de la página y el campo de entrada, respectivamente, para evitar que se eviten repetidamente cuando se anidan en HomeScreen.
                topBar = {
                    // v2.6.7(P0-3): El formulario integrado del pequeño teléfono celular utiliza su propia barra superior estilo WeChat,
                    // con página completa/Agent Tab El riel superior de la isla de cristal está completamente separado.
                    if (isMiniPhoneSurface) {
                        MiniPhoneChatTopBar(
                            title = state.currentAssistant?.name
                                ?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.assistant_repo_default_name),
                            subtitle = state.sessions
                                .find { it.id == state.currentSessionId }
                                ?.title
                                ?.takeIf { it.isNotBlank() },
                            avatarUrl = state.currentAssistant?.avatarImageUrl,
                            onBack = onBack,
                        )
                    } else if (!isAgentMode || onBack != null) {
                        val currentSession =
                            remember(state.sessions, state.currentSessionId, isAgentMode) {
                                if (isAgentMode) null else state.sessions.find { it.id == state.currentSessionId }
                            }
                        val sessionTitle =
                            if (isAgentMode) {
                                "Agent"
                            } else {
                                currentSession?.title
                                    ?.replace(Regex("(?i)&(?:#10|#xA);"), " ")
                                    ?.replace(Regex("(?i)<br\\s*/?>"), " ")
                                    ?.replace(Regex("\\s+"), " ")
                                    ?.trim()
                                    ?.takeIf { it.isNotBlank() }
                                    ?: "muse"
                            }
                        val assistantTitle =
                            state.currentAssistant?.name?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.assistant_repo_default_name)
                        val sessionCd = stringResource(R.string.chat_session_cd, "$assistantTitle · $sessionTitle")

                        Box(modifier = Modifier.fillMaxWidth()) {
                            // v2.6.0: Migración de la solución de barra superior de chat grupal —— Una gran isla de cristal en la capa exterior lo cubre todo.
                            // Lógica original (v2.5.2) Dibuja malla solo cuando el vidrio está cerrado y dibuja tres islas independientes cuando está abierto;
                            // Ahora cambiado a: dibujar la isla exterior independientemente del interruptor (activar=Gran isla de cristal, cerrada.=gradiente de ancho completo),
                            // El contenido se apila en la parte superior y una cápsula de color sólido está incrustada en el medio.
                            ChatTopBarScrim(
                                modifier = Modifier.matchParentSize(),
                                glassActive = glassActive,
                                glassConfig = glassConfig,
                            )
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .statusBarsPadding()
                                        // v1.0.75 fix (Comentarios de los usuarios): 8dp → 4dp,Mishima está más cerca de la barra de estado.
                                        .padding(horizontal = MusePaddings.screen, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                                ) {
                                    // ── isla izquierda:botón de retroceso(Componentes circulares compartidos,Mismo tamaño que el menú derecho) ──
                                    // v2.6.0: Ya no dibujar islas de vidrio independientes cada una —— El cristal lo proporciona la gran isla exterior,
                                    // El botón en sí está pegado a la isla grande en un contenedor de color sólido, en consonancia con la barra superior del chat grupal.
                                    if (onBack != null) {
                                        MuseTopBarIconButton(
                                            icon = MuseIcons.arrowLeft,
                                            contentDescription = stringResource(R.string.action_back),
                                            onClick = onBack,
                                        )
                                    } else {
                                        Spacer(Modifier.width(MuseIconSizes.touchTarget))
                                    }

                                    // ── Nakajima:Título de asistente(nombre de la sesión/modelo como subtítulo) ──
                                    val sessionTitleInteractionSource = remember { MutableInteractionSource() }
                                    // v2.6.0: Migración de la solución de barra superior de chat grupal —— La isla del medio es una cápsula de color sólido (el cristal exterior de la isla grande se revela a la izquierda y a la derecha).
                                    // Intenté que Nakajima también usara el motor de vidrio, pero el contraste entre el título y el fondo debajo del manuscrito no era bueno, y la apariencia no era buena, y volvió a usar colores sólidos.
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        // v1.0.75 fix (Comentarios de los usuarios): 44dp → 48dp,Realzando y ampliando la isla del medio.,con las islas reducidas izquierda y derecha(40dp)Extender las capas
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                    ) {
                                        Box(
                                            modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                // v1.136 T1: Haga clic=Cambiar de sesión,Pulsación larga=Asistente de reemplazo
                                                .combinedClickable(
                                                    interactionSource = sessionTitleInteractionSource,
                                                    indication = null,
                                                    onClick = { sheetState.showSessionSheet = true },
                                                    onLongClick = { sheetState.showAssistantSwitchSheet = true },
                                                )
                                                .semantics { contentDescription = sessionCd },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center,
                                            ) {
                                                Text(
                                                    text = assistantTitle,
                                                    style =
                                                    MaterialTheme.typography.titleMedium.copy(
                                                        fontWeight = FontWeight.Bold,
                                                    ),
                                                    // v2.6.0: Nakajima cambió a una cápsula de color sólido y el título se fijó en onSurface (ya no depende de la luz de fondo ni de la adaptación de sombras).
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                // v1.0.90: La isla del título del medio ya no deletrea el nombre del modelo. —— El ancho de la isla es limitado, después de agregar el nombre del modelo.
                                                // El texto está cortado por las esquinas redondeadas y parece que la representación está rota. Deja solo el subtitulo"nombre de la sesión · duración de la empresa".
                                                if (currentSession != null) {
                                                    // v2.6.10: Toma el numero de dias**asistente**El tiempo de creación, no la sesión.
                                                    // La implementación anterior calcula currentSession.createdAt, que significa "Crear una nueva sesión".
                                                    // El número de días que ha estado contigo ha vuelto a cero." Este número expresa "cuánto tiempo te acompañará".
                                                    // Debes seguir a la persona (asistente), no a la habitación.
                                                    val companionSince = state.currentAssistant
                                                        ?.createdAt
                                                        ?.takeIf { it > 0L }
                                                        ?: currentSession.createdAt
                                                    val days =
                                                        (System.currentTimeMillis() - companionSince) /
                                                            (24 * 60 * 60 * 1000)
                                                    val daysText =
                                                        if (days <= 0L) {
                                                            stringResource(R.string.chat_companion_days_zero)
                                                        } else {
                                                            stringResource(R.string.chat_companion_days, days.toInt())
                                                        }
                                                    Text(
                                                        text = "$sessionTitle · $daysText",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // ── isla derecha:Menú de tres puntos(Componentes circulares compartidos,Almacenamiento"Seleccionar proveedor"/"Contexto de compresión"espera) ──
                                    var showTopMenu by remember { mutableStateOf(false) }
                                    LaunchedEffect(isStreaming) {
                                        showTopMenu = false
                                    }
                                    Box(
                                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        MuseTopBarIconButton(
                                            icon = MuseIcons.moreVertical,
                                            contentDescription = stringResource(R.string.chat_top_menu_cd),
                                            onClick = { showTopMenu = true },
                                            enabled = !isStreaming,
                                            tint =
                                            if (showTopMenu) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                        // Menú flotante sin máscara:Cada operación alinea la ventana emergente a la derecha de forma independiente.
                                        if (showTopMenu) {
                                            // v1.0.90: Mantenga la capa flotante de tres puntos en la esquina superior derecha (se cambió al panel inferior y se volvió a cambiar según los comentarios).
                                            MuseFloatingActionMenu(
                                                items =
                                                listOfNotNull(
                                                    MuseFloatingActionItem(
                                                        key = "assistant",
                                                        icon = MuseIcons.chat,
                                                        label = stringResource(R.string.chat_switch_assistant),
                                                        enabled = !isStreaming,
                                                        onClick = {
                                                            showTopMenu = false
                                                            // Asistente a nivel de sesión que reutiliza la pulsación larga del título para cambiar de hoja,
                                                            // No salta a la página de gestión de asistentes ni modifica el asistente global predeterminado.
                                                            sheetState.showAssistantSwitchSheet = true
                                                        },
                                                    ),
                                                    MuseFloatingActionItem(
                                                        key = "proactive_toggle",
                                                        icon = MuseIcons.sparkle,
                                                        label = stringResource(R.string.chat_proactive_toggle),
                                                        enabled = !isStreaming,
                                                        checked = proactiveConfig.enabled,
                                                        onClick = {
                                                            showTopMenu = false
                                                            ioScope.launch {
                                                                settings.saveProactiveMessageConfig(
                                                                    proactiveConfig.copy(enabled = !proactiveConfig.enabled),
                                                                )
                                                            }
                                                        },
                                                    ),
                                                    MuseFloatingActionItem(
                                                        key = "provider",
                                                        // UI-FIX: Compartido originalmente con "Mensaje activo" AutoAwesome(destello), dos iconos fallan,
                                                        // proveedor/En su lugar, el modelo utiliza el icono del chip.
                                                        icon = MuseIcons.memoryChip,
                                                        label = stringResource(R.string.chat_select_provider),
                                                        enabled = !isStreaming,
                                                        onClick = {
                                                            showTopMenu = false
                                                            sheetState.showModelSheet = true
                                                        },
                                                    ),
                                                    MuseFloatingActionItem(
                                                        key = "compress",
                                                        icon = MuseIcons.gitMerge,
                                                        label = stringResource(R.string.chat_update_compress),
                                                        enabled = !isStreaming && !state.isCompressing && messages.size >= 2,
                                                        onClick = {
                                                            showTopMenu = false
                                                            viewModel.manualCompress(updateMemoryFirst = true)
                                                        },
                                                    ),
                                                    MuseFloatingActionItem(
                                                        key = "find",
                                                        icon = MuseIcons.search,
                                                        label = stringResource(R.string.chat_find_in_conversation),
                                                        enabled = messages.isNotEmpty(),
                                                        onClick = {
                                                            showTopMenu = false
                                                            showInChatSearch = true
                                                        },
                                                    ),
                                                    // v2.0.1: Registro de llamadas de herramientas (las boyas se cambian para que se muestren solo durante la generación y la entrada del historial recibe un menú)
                                                    MuseFloatingActionItem(
                                                        key = "tool_history",
                                                        icon = MuseIcons.wrench,
                                                        label = stringResource(R.string.chat_tool_calls_title),
                                                        enabled = messages.isNotEmpty(),
                                                        onClick = {
                                                            showTopMenu = false
                                                            sheetState.showToolCallSheet = true
                                                        },
                                                    ),
                                                    // v2.x: comité — Convoca a un grupo de asistentes en cualquier momento desde la conversación para iniciar una discusión de chat grupal temporal
                                                    MuseFloatingActionItem(
                                                        key = "committee",
                                                        icon = MuseIcons.users,
                                                        label = stringResource(R.string.chat_committee),
                                                        enabled = !isStreaming,
                                                        onClick = {
                                                            showTopMenu = false
                                                            showCommitteeDialog = true
                                                        },
                                                    ),
                                                    // v2.6.2(comentarios de los usuarios): Antecedentes de esta conversación — No afecta a otras sesiones.
                                                    MuseFloatingActionItem(
                                                        key = "session_background",
                                                        icon = MuseIcons.image,
                                                        label = stringResource(R.string.chat_set_background),
                                                        enabled = !effectiveSessionIdForBg.isNullOrBlank(),
                                                        onClick = {
                                                            showTopMenu = false
                                                            sessionBgLauncher.launch(
                                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                                            )
                                                        },
                                                    ),
                                                    // v2.6.2(comentarios de los usuarios): Limpiar el fondo de esta conversación. —— Aparece solo cuando esta sesión está configurada sola
                                                    if (effectiveSessionIdForBg?.let { sessionBackgrounds[it] } != null) {
                                                        MuseFloatingActionItem(
                                                            key = "session_background_clear",
                                                            icon = MuseIcons.trash,
                                                            label = stringResource(R.string.chat_clear_background),
                                                            onClick = {
                                                                showTopMenu = false
                                                                val sid = effectiveSessionIdForBg
                                                                if (!sid.isNullOrBlank()) {
                                                                    ioScope.launch {
                                                                        settingsRepo.saveSessionChatBackground(sid, null)
                                                                        withContext(Dispatchers.IO) {
                                                                            io.zer0.muse.ui.settings.ChatBackgroundStore.deleteSlot(
                                                                                context,
                                                                                io.zer0.muse.ui.settings.ChatBackgroundStore.sessionSlot(
                                                                                    sid,
                                                                                ),
                                                                            )
                                                                        }
                                                                        MuseToast.show(backgroundClearedText)
                                                                    }
                                                                }
                                                            },
                                                        )
                                                    } else {
                                                        null
                                                    },
                                                ),
                                                onDismiss = { showTopMenu = false },
                                                glassActive = glassActive,
                                            )
                                        }
                                    }
                                }
                                // v2.x: diálogo del comité — Seleccionar miembros + temas,Iniciar una discusión de chat grupal temporal
                                if (showCommitteeDialog) {
                                    CommitteeDialog(
                                        assistants = state.assistants,
                                        onDismiss = { showCommitteeDialog = false },
                                        onConfirm = { memberIds, topic ->
                                            showCommitteeDialog = false
                                            viewModel.launchCommittee(memberIds, topic)
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                bottomBar = {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { bottomBarHeightPx = it.size.height },
                        // Se disponen varias barras planas hacia arriba en secuencia, y el espacio se proporciona aquí de manera uniforme, en lugar de depender de que cada barra se sostenga a sí misma.
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        // Tarjeta de aprobación de herramienta: se muestra fijamente encima del campo de entrada (siguiendo la posición de operación del usuario y sin desplazarse con el flujo de mensajes)
                        // CHAT-08: Varias tarjetas de aprobación se pliegan en "N Artículo pendiente de aprobación";doblar(leer)Cuenta regresiva pausada durante,
                        // Evite que la aprobación sea rechazada automáticamente en 30 segundos cuando los usuarios la lean.
                        val approvals = state.pendingToolApprovals
                        var approvalsExpanded by rememberSaveable { mutableStateOf(false) }
                        // v2.6.10: Interruptor de ventana emergente de detalles de paso para barra plana de proceso
                        var showProcessSheet by rememberSaveable { mutableStateOf(false) }
                        // Pausar el tiempo de espera de aprobación del lado de la máquina virtual durante el colapso;Sin aprobación/folleto/Restaurar en expansión,Evite que permanezca el estado de pausa.
                        LaunchedEffect(approvals.size, approvalsExpanded) {
                            viewModel.setApprovalTimeoutPaused(approvals.size > 1 && !approvalsExpanded)
                        }
                        // v2.6.0 (AUD-UI-F12): abandonar el grupo(Tab cortar/Salida de página completa)Restablece el estado de pausa.
                        // LaunchedEffect Sólo vuelva a ejecutar cuando cambie la clave:al salir pause=true,
                        // Al regresar, si la clave se restablece con la misma combinación,no se volverá a ejecutar,
                        // VM El tiempo de espera de aprobación en pausa permanecerá true —— Aprobación de nuevas sesiones
                        // será un error“suspendido permanentemente”sin activar un tiempo de espera de 30 segundos.
                        DisposableEffect(Unit) {
                            onDispose { viewModel.setApprovalTimeoutPaused(false) }
                        }
                        if (approvals.size == 1) {
                            approvals.forEach { approval ->
                                ToolApprovalCard(
                                    toolName = approval.toolName,
                                    argumentsPreview = approval.argumentsPreview,
                                    argumentsFull = approval.argumentsFull,
                                    onApprove = { viewModel.approveToolCall(approval.toolCallId) },
                                    onDeny = { reason -> viewModel.denyToolCall(approval.toolCallId, reason) },
                                    onPersistPolicy = { policy ->
                                        viewModel.persistToolPolicy(approval.toolCallId, policy)
                                    },
                                    onAllowThisSession = {
                                        viewModel.allowToolForSession(approval.toolCallId)
                                    },
                                    referenceImageOverride = approval.referenceImageOverride,
                                    onReferenceImageChange = { dataUri ->
                                        viewModel.setToolApprovalReferenceImage(approval.toolCallId, dataUri)
                                    },
                                )
                            }
                        } else if (approvals.size > 1) {
                            PendingApprovalsSummary(
                                approvals = approvals,
                                expanded = approvalsExpanded,
                                onToggleExpanded = { approvalsExpanded = !approvalsExpanded },
                                onApprove = { approval -> viewModel.approveToolCall(approval.toolCallId) },
                                onDeny = { approval, reason -> viewModel.denyToolCall(approval.toolCallId, reason) },
                                onPersistPolicy = { approval, policy ->
                                    viewModel.persistToolPolicy(approval.toolCallId, policy)
                                },
                                onAllowThisSession = { approval ->
                                    viewModel.allowToolForSession(approval.toolCallId)
                                },
                                onReferenceImageChange = { approval, dataUri ->
                                    viewModel.setToolApprovalReferenceImage(approval.toolCallId, dataUri)
                                },
                                countdownPaused = !approvalsExpanded,
                            )
                        }
                        // v1.97: Herramientas de cálculo/Progreso de la tarea — Priorizar activo agentPlan,De lo contrario utilizar toolCallHistory
                        val latestPlan = state.agentPlans.values.maxByOrNull { it.createdAt }
                        val activePlan = latestPlan?.takeIf { it.steps.isNotEmpty() && !it.isAllSettled }
                        val toolCallTotal = activePlan?.totalSteps ?: state.toolCallHistory.size
                        val toolCallCompleted =
                            activePlan?.completedSteps
                                ?: state.toolCallHistory.count { it.isSuccess }
                        // B2: Barra de cola para enviar(cola en streaming,Vista previa artículo por artículo/Editar/Eliminar/Enviar por separado)
                        if (state.sendQueue.isNotEmpty()) {
                            PendingQueueBar(
                                queue = state.sendQueue,
                                onSend = viewModel::sendPendingSend,
                                onEdit = viewModel::editPendingSend,
                                onRemove = viewModel::removePendingSend,
                                onClear = viewModel::clearPendingQueue,
                            )
                        }
                        // v1.0.92: Lista de tareas pendientes de la conversación — AI Progreso del desglose de tareas mantenido a través de todo_write,Mostrar cuando hay contenido
                        SessionTodoBar(sessionId = effectiveChatSessionId(state).orEmpty())
                        // v2.6.10: procesar tira plana —— Solo aparece durante la operación y solo responde "¿Qué estás haciendo en este momento?"
                        // Con él, las tarjetas de proceso en el flujo de mensajes ya no tienen que competir por grandes porciones de espacio.
                        // La subtarea persiste a lo largo de las rondas, por lo que tiene una razón para mostrarse y no tiene que esperar a la transmisión.
                        val subagentActivity = remember(
                            state.activeSubagentThreads,
                            state.pendingSubagentTasks,
                        ) {
                            io.zer0.muse.ui.chat.SubagentActivity(
                                threads = state.activeSubagentThreads,
                                pending = state.pendingSubagentTasks,
                            )
                        }
                        if (state.isStreaming || !subagentActivity.isEmpty) {
                            io.zer0.muse.ui.chat.ActiveProcessBar(
                                records = state.toolCallHistory,
                                progressMessage = state.toolProgressMessage,
                                subagent = subagentActivity,
                                onExpand = { showProcessSheet = true },
                            )
                        }
                        if (showProcessSheet) {
                            io.zer0.muse.ui.chat.ActiveProcessSheet(
                                records = state.toolCallHistory,
                                subagent = subagentActivity,
                                onCancelSubagent = { taskId -> viewModel.cancelSubagentTask(taskId) },
                                onDismiss = { showProcessSheet = false },
                            )
                        }
                        // Deje que la isla de entrada ocupe únicamente el área segura del sistema inferior,Evite que toda la barra inferior se estire hasta formar una máscara blanca al insertarla.
                        // La capa fantasma se cuelga en una ventana independiente y la posición declarada no afecta su posición de dibujo.
                        SendFlightWindow(flight = sendFlight) { sendFlight = null }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .museBottomBarInsets()
                                .onGloballyPositioned { inputWindowBounds = it.boundsInWindow() },
                        ) {
                            // I3: Límites de error independientes del área de entrada,La construcción de datos de representación de entrada falla y solo degrada la barra de entrada
                            RegionErrorBoundary(
                                regionName = "input",
                                data = { state.sendQueue },
                            ) {
                                RichInputBar(
                                    // v1.0.20 (Task 3): input/isStreaming Leer valor derivado,Evite otros cambios de campo que provoquen la reorganización de la barra inferior
                                    text = currentInput,
                                    assistantName = state.currentAssistant?.name?.takeIf { it.isNotBlank() } ?: "Muse",
                                    isStreaming = isStreaming,
                                    isWaitingFirstToken = isWaitingFirstToken,
                                    isDrawMode = state.isDrawMode,
                                    isWebSearchEnabled = state.webSearchEnabled,
                                    isDeepThinkingEnabled = state.deepThinkingEnabled,
                                    // v1.0.47 P5-6: Cápsula de nivel de pensamiento profundo(Se muestra cuando está activado,Haga clic para repetir)
                                    deepThinkingLevel = state.deepThinkingLevel,
                                    onCycleDeepThinkingLevel = viewModel::cycleDeepThinkingLevel,
                                    imageGenParams = state.imageGenParams,
                                    onImageGenParamsChange = viewModel::updateImageGenParams,
                                    // v1.0.75 fix: Se ha eliminado la barra de herramientas de formato.,No más propagación formatEnabled
                                    showExpandButton = state.chatPreferences.showExpandButton,
                                    glassActive = glassActive,
                                    glassConfig = glassConfig,
                                    onTextChanged = viewModel::updateInput,
                                    // v1.0.47 P5: teclado físico/La flecha hacia abajo recorre el historial de entrada
                                    onNavigateInputHistory = viewModel::navigateInputHistory,
                                    // v1.97: Intercepción de comando de barra — / La entrada al principio va executeSlashCommand,No enviar a LLM
                                    onSend = {
                                        // Lea el valor en el momento de hacer clic desde ViewModel,No se basa en instantáneas antiguas que los cierres componibles pueden capturar.
                                        val text = viewModel.state.value.input
                                        // Sólo el primer mensaje volará: cada mensaje posterior se convertirá en ruido.
                                        // Y la primera frase resulta ser "la conversación comienza".
                                        val isFirstMessage = messages.none { it.role == MessageRole.USER }
                                        if (isFirstMessage && !SlashCommand.isSlashCommand(text)) {
                                            sendFlight = computeSendFlight(
                                                text = text,
                                                input = inputWindowBounds,
                                                list = listWindowBounds,
                                            )
                                        }
                                        if (SlashCommand.isSlashCommand(text)) {
                                            viewModel.executeSlashCommand(text)
                                        } else {
                                            viewModel.send()
                                            focusManager.clearFocus()
                                        }
                                    },
                                    onStop = viewModel::stop,
                                    onInterject = viewModel::interject,
                                    onEnqueuePending = viewModel::enqueuePendingSend,
                                    replyingTo =
                                    state.replyingTo?.let { r ->
                                        // v1.0.72 fix: El bloque de referencia utiliza el último objeto de mensaje — Streaming de contenidos de mensajes actualizados en tiempo real.,
                                        // Los objetos antiguos capturados cuando se hace referencia a ellos pueden tener contenido vacío(El primer mensaje cita la causa raíz de que la interfaz de usuario esté vacía.)
                                        messages.find { it.id == r.id } ?: r
                                    },
                                    onClearReply = { viewModel.setReplyingTo(null) },
                                    replyQuoteOverride = state.replyQuoteOverride,
                                    onEditReply = { viewModel.setReplyQuoteOverride(it) },
                                    onPickDocument = {
                                        runCatching {
                                            documentLauncher.launch(
                                                // v2.6.13: El archivo adjunto se cambió a "El archivo original se coloca en el espacio de trabajo" y ya no se descomprime localmente./análisis,
                                                // Entonces el selector ya no limita la clase de documento. MIME —— Paquete comprimido, código, archivo de datos.
                                                // Siempre se pueden almacenar en el espacio de trabajo tal como están y el modelo los puede leer según demanda.
                                                arrayOf(
                                                    "*/*",
                                                    "text/*",
                                                    "application/pdf",
                                                    "application/zip",
                                                    "application/x-zip-compressed",
                                                    "application/octet-stream",
                                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                                    "application/msword",
                                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                                    "application/vnd.ms-excel",
                                                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                                    "application/epub+zip",
                                                ),
                                            )
                                        }.onFailure {
                                            viewModel.reportError(
                                                context.getString(R.string.chat_err_open_file_picker, it.message),
                                            )
                                        }
                                    },
                                    onToggleDrawMode = viewModel::toggleDrawMode,
                                    onToggleWebSearch = viewModel::toggleWebSearch,
                                    onToggleDeepThinking = viewModel::toggleDeepThinking,
                                    // v1.24: Agent Los modos se muestran en la barra de herramientas más."reiniciar contexto",Las conversaciones ordinarias no se muestran.
                                    showRestartContext = isAgentMode,
                                    onRestartContext = viewModel::restartContext,
                                    // v1.25: Delegado a la entrada del asistente
                                    assistants = state.assistants,
                                    onDelegateToAssistant = { sheetState.showDelegateSheet = DelegateSheetMode.Input },
                                    // v0.29 P1-6: Base de conocimientos @mencionar selección de documentos sheet
                                    onPickKnowledge = { sheetState.showKnowledgeSheet = true },
                                    onOpenPromptTemplates = { sheetState.showPromptTemplateSheet = true },
                                    // Menú más → Entrada de habilidad
                                    onOpenSkills = onOpenSkills,
                                    // v0.31: La tecla Enter envía el interruptor a InputBar
                                    enterToSend = state.chatPreferences.enterToSend,
                                    // Phase 8.5:mensaje rapido
                                    quickMessages = state.quickMessages,
                                    // Phase 8.6: Entrada de imagen multimodal
                                    pendingImages = state.pendingImages,
                                    onPickImage = { asOcr ->
                                        imagePickAsOcr = asOcr
                                        runCatching {
                                            visualMediaLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                                            )
                                        }.onFailure {
                                            viewModel.reportError(
                                                context.getString(R.string.chat_err_open_image_picker, it.message),
                                            )
                                        }
                                    },
                                    // v0.53: Haga clic en las imágenes recientes del álbum en el menú de herramientas para agregarlas directamente y enviarlas.
                                    onPickGalleryImage = { uri ->
                                        viewModel.pickImage(uri, context, asOcr = false)
                                    },
                                    onRemovePendingImage = viewModel::removePendingImage,
                                    // v1.136 T10: Chip de documento a enviar
                                    pendingDocuments = state.pendingDocuments,
                                    onRemovePendingDocument = viewModel::removePendingDocument,
                                    onInsertQuickMessage = { qm ->
                                        // Phase 8.5 Reparación: clipboard La lectura se cambia al hilo IO.,evitar el hilo principal IPC ANR
                                        ioScope.launch {
                                            val clipboard =
                                                withContext(Dispatchers.IO) {
                                                    runCatching {
                                                        val cm =
                                                            context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                                                as android.content.ClipboardManager
                                                        cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
                                                    }.getOrDefault("")
                                                }
                                            viewModel.insertQuickMessage(qm, clipboard)
                                        }
                                    },
                                    // Phase 9.3 (M2): ASR Grabación
                                    // v1.49: Después de eliminar Vosk,dos caminos:
                                    //  - API modo(si apiKey):Mantenga presionado para grabar + suelte para reconocer + deslice hacia arriba para cancelar
                                    //  - SYSTEM modo(Ninguno apiKey):Mantenga presionado el micrófono y suéltelo para activar el reconocimiento de voz del sistema. Intent
                                    isRecording = state.asrState.isRecording || systemRecording,
                                    asrStatus = state.asrState.status,
                                    asrErrorMessage = state.asrState.errorMessage,
                                    recordingAmplitudes = state.asrState.amplitudes,
                                    onStartRecording = {
                                        if (viewModel.shouldUseApiRecording()) {
                                            // API camino:Primero verifique el permiso RECORD_AUDIO,Aplicar si no está autorizado
                                            val granted =
                                                ContextCompat.checkSelfPermission(
                                                    context, Manifest.permission.RECORD_AUDIO,
                                                ) == PackageManager.PERMISSION_GRANTED
                                            if (granted) {
                                                viewModel.startStreamingAsr()
                                                true
                                            } else {
                                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                // U-17: Brinde comentarios claros cuando falten permisos,Evite una pulsación larga silenciosa y sin respuesta
                                                MuseToast.show(context.getString(R.string.chat_err_mic_permission))
                                                false
                                            }
                                        } else {
                                            // SYSTEM camino:Compruebe si el servicio de reconocimiento de voz del sistema está disponible,Si está disponible, mantenga presionado y suelte para que aparezca Intent
                                            if (!SpeechInput.isAvailable(context)) {
                                                Logger.w("ChatScreen", "El servicio de voz del sistema no está disponible")
                                                // U-17: Servicio no listo mensaje separado,con"Permisos faltantes"distinguir
                                                MuseToast.show(context.getString(R.string.chat_voice_not_ready))
                                                false
                                            } else {
                                                // Devuelve falso: no entrar"Grabación"Estado(La intención del sistema se hará cargo UI)
                                                // El lanzamiento real se activa cuando lo sueltas.,Evite repetir durante una pulsación larga launch
                                                systemRecording = true
                                                true
                                            }
                                        }
                                    },
                                    onStopRecording = {
                                        if (viewModel.shouldUseApiRecording()) {
                                            viewModel.stopStreamingAsr()
                                        } else {
                                            systemRecording = false
                                            // v1.95: Consejos solo para uso por primera vez,Luego active directamente el sistema. Intent
                                            ioScope.launch {
                                                val shown = settings.asrTipShownFlow.first()
                                                if (!shown) {
                                                    sheetState.asrTipDialogShown = true
                                                    settings.saveAsrTipShown(true)
                                                } else {
                                                    resultOf {
                                                        speechLauncher.launch(
                                                            SpeechInput.createIntent(context.getString(R.string.speech_speak_prompt)),
                                                        )
                                                    }.onError { msg, _ ->
                                                        // v1.98: Eliminar mensajes emergentes,Procesamiento silencioso
                                                        Logger.w("ChatScreen", "No se pudo iniciar el reconocimiento de voz: $msg")
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    onCancelRecording = {
                                        if (viewModel.shouldUseApiRecording()) {
                                            viewModel.cancelStreamingAsr()
                                        } else {
                                            systemRecording = false
                                        }
                                        // SYSTEM Camino sin concepto de cancelación(todavía no launch Intent)
                                    },
                                    // Mostrar el micrófono solo si el proveedor de ASR en tiempo real ha configurado la clave API;Mantener el botón de enviar cuando no esté configurado,
                                    // Evite que los usuarios hagan clic en un portal de voz que seguramente no estará disponible.DASHSCOPE_FILE Sólo se admite la transcripción de archivos.
                                    showMic = viewModel.shouldUseApiRecording() && state.asrConfig.apiKey.isNotBlank(),
                                    // v1.97: Herramientas/Progreso de la tarea pill(Priorizar el uso del progreso del plan,De lo contrario utilizar toolCallHistory)
                                    toolCallCompleted = toolCallCompleted,
                                    toolCallTotal = toolCallTotal,
                                    onShowToolCalls = { sheetState.showToolCallSheet = true },
                                    // v2.6.7: Ya no pasa marcas de borrador a los campos de entrada (UI No dejar rastro)
                                    // v1.0.29: Agent Tab No llame activamente al método de entrada
                                    autoFocus = !isAgentMode,
                                    // v1.0.47 P5-3: Token estimación(Desactivado de forma predeterminada,Después de abrir la página de configuración, la barra de estadísticas del token se muestra en la parte inferior del campo de entrada.)
                                    tokenEstimateEnabled = state.tokenEstimateEnabled,
                                    historyTokens = state.contextTokenCount,
                                    contextWindow = state.contextMaxTokens,
                                    // v1.0.47 P5-2: Pegar y convertir texto largo en archivos(Habilitado por defecto,Al pegar texto que supere el umbral, se le solicitará que lo convierta en un archivo adjunto.)
                                    pasteAsFileEnabled = state.pasteAsFileEnabled,
                                    pasteAsFileThreshold = state.pasteAsFileThreshold,
                                    onAddPastedTextAsDocument = viewModel::addPastedTextAsDocument,
                                )
                            } // I3: Final del límite de error del área de entrada
                        }
                    }
                },
                // Imagen de fondo/El degradado se dibuja en el cuadro exterior.; Scaffold debe ser transparente,De lo contrario, el fondo predeterminado cubrirá el degradado.
                containerColor =
                if (chatBackground.isNullOrBlank() && chatGradient == null) {
                    MaterialTheme.colorScheme.background
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
            ) { innerPadding ->
                val scrollToBottomScope = rememberCoroutineScope()
                // P2-13: Bloqueo de teclas de acceso directo del escritorio — Ctrl+Shift+C Copiar la última respuesta de AI
                // Enter/Shift+Enter Manejado por el propio InputBar(Lógica enterToSend implementada en v0.31),
                // No hay intercepciones repetidas aquí.,Evite romper la configuración de usuario existente("Entra para enviar" / "Shift+Entra para enviar").
                val copyLastReplyClipboardScope = rememberCoroutineScope()
                val copyLastReply: () -> Unit = {
                    val lastAssistant =
                        messages.lastOrNull {
                            it.role == MessageRole.ASSISTANT && it.content.isNotBlank()
                        }
                    if (lastAssistant != null) {
                        copyLastReplyClipboardScope.launch {
                            val clipboard =
                                withContext(Dispatchers.IO) {
                                    context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                }
                            clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText(
                                    context.getString(R.string.app_name),
                                    // v2.6.8: Al copiar y exportar, la cola del estado de la tarjeta no va al portapapeles.
                                    InternalMarkupSanitizer.exportContent(lastAssistant.content),
                                ),
                            )
                            MuseToast.show(copiedToastText)
                        }
                    }
                    // Silencioso y sin operación cuando no hay respuesta de IA(Evite introducir tiras de tostadas no localizadas)
                }
                // v1.0.72: Altura superior de la isla flotante — Consejos/La superposición del banner cede,Evita ser bloqueado por tres islas.
                val topInset = innerPadding.calculateTopPadding()
                // fix(Oclusión del mapa de mensajes): Durante sesiones largas, se reserva espacio en el lado derecho para la barra de navegación. Las listas y las barras de navegación comparten los mismos criterios.,
                // evitar"La lista no cede pero aparece la barra de navegación."(El botón está bloqueado)o viceversa(Hay un espacio en blanco inexplicable en el lado derecho.).
                val showMessageMap = messages.size >= MESSAGE_MAP_MIN_MESSAGES && visibleMessages.isNotEmpty()

                // v2.7: La animación del proceso se cambia a una capa flotante residente al final del flujo de mensajes. —— Originalmente era un elemento de lista y se recicló cuando salió de la ventana gráfica.

                val processIndicatorVisible =

                    state.isOcrProcessing ||

                        (isStreaming && isWaitingFirstToken) ||

                        state.toolProgressMessage != null

                val processIndicatorText =

                    when {
                        state.toolProgressMessage != null -> state.toolProgressMessage

                        visionProgress?.isActive == true ->

                            stringResource(R.string.chat_analyzing_image, visionProgress.index, visionProgress.total)

                        state.isOcrProcessing -> stringResource(R.string.ocr_processing_hint)

                        else -> null
                    }
                Box(
                    modifier =
                    Modifier
                        .fillMaxSize()
                        // v1.0.72 fix: Quitar la capa exterior superior/inferior padding — Los controles flotantes se extienden hasta los límites de la barra del sistema.
                        // La lista de mensajes en sí evita el campo de entrada en contentPadding,La barra de navegación derecha ya no finaliza prematuramente.
                        // P2-13: Teclas de acceso directo del escritorio — Ctrl+Shift+C Copiar la última respuesta de AI
                        // Solo tiene efecto bajo teclado físico + ventana expandida,Evite conflictos con la acción IME del teclado virtual
                        .onKeyEvent { event ->
                            if (!desktopShortcutsEnabled) return@onKeyEvent false
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            if (event.key == DesktopShortcuts.COPY_LAST_REPLY &&
                                event.isCtrlPressed && event.isShiftPressed
                            ) {
                                copyLastReply()
                                true
                            } else {
                                false
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // B7-01: Barra de operación de selección múltiple
                    if (state.selectionMode) {
                        // A4: Seleccionar texto del mensaje — Copia por lotes/Las exportaciones comparten el mismo formato
                        val selectedText =
                            visibleMessages
                                .filter { it.id.toString() in state.selectedMessageIds }
                                .joinToString("\n\n") {
                                    // v2.6.8: La copia por lotes también exporta.
                                    "${it.role}: ${InternalMarkupSanitizer.exportContent(it.content)}"
                                }
                        ChatSelectionBar(
                            count = state.selectedMessageIds.size,
                            onSelectAll = { viewModel.selectAllMessages(visibleMessages.map { it.id.toString() }) },
                            onCopy = {
                                if (selectedText.isNotBlank()) {
                                    val cm =
                                        context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                            as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("muse-selected", selectedText))
                                }
                            },
                            // Reparación de auditoría (8.5): Confirmar antes de eliminar — Implementación original de eliminación directa.,Si lo tocas accidentalmente, toda la conversación se perderá y no podrás recuperarla.
                            onDelete = { showDeleteConfirm = true },
                            onExport = {
                                if (selectedText.isNotBlank()) {
                                    val sendIntent =
                                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_TEXT, selectedText)
                                        }
                                    ShareIntentHelper.startChooserSafely(context, sendIntent)
                                }
                            },
                            onExit = { viewModel.setSelectionMode(false) },
                            // v2.6.9(comentarios de los usuarios): Colección de lotes de selección múltiple
                            onFavorite = { viewModel.favoriteSelectedMessages() },
                            modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                // v1.0.72: Da paso a las tres islas suspendidas.(No bloquear)
                                .padding(top = topInset)
                                .fillMaxWidth()
                                .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap)
                                .zIndex(10f),
                        )
                        // Reparación de auditoría (8.5): Cuadro de diálogo de confirmación de eliminación de selección múltiple
                        if (showDeleteConfirm) {
                            MuseDialog(
                                onDismissRequest = { showDeleteConfirm = false },
                                title = stringResource(R.string.chat_delete_selected_title),
                                content = {
                                    Text(stringResource(R.string.chat_delete_selected_message, state.selectedMessageIds.size))
                                },
                                confirmText = stringResource(R.string.action_delete),
                                destructive = true,
                                onConfirm = {
                                    showDeleteConfirm = false
                                    viewModel.deleteSelectedMessages()
                                },
                            )
                        }
                    }
                    // Phase 3 3E: Banner de mensaje cronometrado
                    io.zer0.muse.ui.chat.ScheduledMessageBanner(
                        pendingMessages = pendingMessages,
                        onCancel = { msgId ->
                            pendingScope.launch { pendingMessageManager.cancelMessage(msgId) }
                        },
                    )
                    // v2.6.7: Aviso visible cuando la ejecución de la herramienta se pausa globalmente.
                    // Anteriormente, solo había un interruptor en la página de configuración. En el chat, sólo podías ver "Pausado,0ms",
                    // Es fácil juzgar mal“Redes/la herramienta esta rota”. Expliquemos el asunto claramente aquí y le brindaremos una recuperación con un solo clic.
                    if (state.chatPreferences.pauseToolExecution) {
                        ToolPausedBanner(onResume = { viewModel.resumeToolExecution() })
                    }
                    // Estado vacío y lista de mensajes Transición cruzada,Evite el traspaso difícil
                    // v1.0.4 (P3-4): Utilice visibleMessages para probar vacío,En el modo de rendimiento, visibleMessages refleja el estado de renderizado real.
                    // v1.0.48: Se solucionó el problema del estado vacío parpadeante cuando se ingresa a la pestaña Agente. — HorizontalPager La página de destino ha sido compose
                    //   Pero setAgentMode aún no se ha ejecutado.(settledPage gatillo),En este momento visibleMessages=emptyList() parpadeará
                    //   "¿De qué quieres hablar hoy?"guía. Estado intermedio de carga agregado:isAgentMode && !state.isAgentMode período
                    //   Mostrar cargando en lugar de estado vacío,Espere hasta que se complete el cambio de ViewModel antes de representar el mensaje o el estado vacío real.
                    val isAgentTabLoading = isAgentMode && !state.isAgentMode
                    val chatScreenState =
                        when {
                            isAgentTabLoading -> 2 // Agent Tab Cargando
                            visibleMessages.isEmpty() -> 1 // verdadera sesión vacía
                            else -> 0 // hay noticias
                        }
                    Crossfade(
                        targetState = chatScreenState,
                        animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS),
                        label = "chatState",
                        modifier = Modifier.fillMaxSize(),
                    ) { screenState ->
                        // v1.0.72: sesión actual"Sin referencia a la memoria"logotipo(Para cambiar la página de inicio en blanco)
                        val currentSessionIgnoreMemory =
                            remember(state.sessions, state.currentSessionId, state.agentSessionId) {
                                val sid = if (state.isAgentMode) state.agentSessionId else state.currentSessionId
                                state.sessions.firstOrNull { it.id == sid }?.ignoreMemory ?: false
                            }
                        if (screenState == 2) {
                            // Agent Tab Cargando — mostrar loading,Arrancar sin flashear
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                MuseLoadingState()
                            }
                        } else if (screenState == 1) {
                            // Arranque en estado vacío — Mensaje ligero centrado + cápsula de mensaje de sugerencia(Sin enmascaramiento,Haga clic para completar el cuadro de entrada)
                            // Agregue la altura del área de entrada en la parte inferior: este contenedor es de pantalla completa y la isla de entrada y el teclado ocupan la parte inferior.
                            // Si presiona toda la pantalla hacia el centro, la guía se adjuntará al borde superior del cuadro de entrada, haciendo que parezca que está exprimida.
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(
                                        bottom = innerPadding.calculateBottomPadding() +
                                            MusePaddings.inputIslandBottomGap +
                                            MusePaddings.listBottomClearance,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                EmptyChatGuide(
                                    assistant = state.currentAssistant,
                                    // v1.0.72: Esta conversación no se refiere a la memoria.
                                    ignoreMemory = currentSessionIgnoreMemory,
                                    onToggleIgnoreMemory = { ignore ->
                                        viewModel.setSessionIgnoreMemory(ignore)
                                    },
                                    modifier = Modifier.padding(horizontal = MusePaddings.largeGap),
                                )
                            }
                        } else {
                            // Phase 8.10: Desplazamiento de la tecla de volumen
                            // interceptar VOLUME_UP/DOWN → listState.scrollBy,Experiencia de lectura de chat larga mejorada
                            val volumeScrollScope = rememberCoroutineScope()
                            // H-S6: latestPlan Caché fuera de LazyColumn(remember No se puede llamar dentro de LazyListScope)
                            val latestPlan =
                                remember(state.agentPlans) {
                                    state.agentPlans.values.maxByOrNull { it.createdAt }
                                }
                            // v1.137: construir messageId → plan mapeo,Deje que cada mensaje del asistente encuentre su propia tarjeta de plan.
                            // La tarjeta de planificación se fija al mensaje que la creó y se desplaza con el mensaje.,no más"saltar"al último mensaje del asistente.
                            val plansByMessageId =
                                remember(state.agentPlans) {
                                    state.agentPlans.values
                                        // P3-4(informe de auditoría): Utilice mapNotNull en su lugar filter + !!,
                                        // Evite refactorizaciones posteriores !! Conviértete en una NPE.
                                        .mapNotNull { plan -> plan.messageId?.let { it to plan } }
                                        .toMap()
                                }
                            val visibleMessageIds =
                                remember(visibleMessages) {
                                    visibleMessages.mapTo(mutableSetOf()) { it.id.toString() }
                                }
                            // v1.0.92 Rendimiento: noticias → Información de sucursal variante Índice de construcción única —
                            // La implementación anterior se llama dentro del elemento para cada mensaje. branchInfoFor(Recorrido de todo el árbol),Listar el costo total O(n²),
                            // Vuelva a calcular cada cuadro durante la transmisión,Es una de las principales fuentes de retraso en sesiones largas.
                            val branchInfoByMessageId =
                                remember(conversationTree) {
                                    conversationTree.buildBranchInfoIndex()
                                }
                            // v1.0.92: ID del último mensaje de usuario precalculado(para el artículo O(1) juez)
                            val lastUserId = visibleMessages.lastOrNull { it.role == MessageRole.USER }?.id
                            // M-UI3: Vincular la tarjeta del plan más reciente al mensaje más reciente del Asistente,Desplázate con las novedades
                            val lastAssistantId = visibleMessages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
                            // v2.x Rendimiento: Las palabras clave para las tiras de trabajo con imágenes se cuentan a la vez fuera de la lista.
                            // La implementación anterior era que cada elemento visible se escaneaba individualmente y el total se sumaba durante el período de transmisión. O(n²),
                            // Y los mismos parámetros de herramienta vuelven a analizar JSON en cada cuadro.key todavía depende de visibleMessages,
                            // Pero eso es sólo una vez por ronda. O(n).
                            val imageGenPrompts =
                                remember(visibleMessages) {
                                    imageGenPromptsByMessage(visibleMessages)
                                }
                            // I3: Límite de error independiente del área de chat,La construcción de datos de representación de la lista de mensajes falló y solo degradó el área
                            RegionErrorBoundary(
                                regionName = "chat",
                                data = { visibleMessages },
                            ) {
                                // P2-1: Box Lista de mensajes del paquete,Expanded Centrar y limitar el ancho en modo 720dp
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.TopCenter,
                                ) {
                                    // B6-02: Aspecto de estado de ánimo en pantalla completa(detrás de la lista de mensajes)
                                    MoodSkinOverlay(visibleMessages.lastOrNull { it.role == MessageRole.ASSISTANT }?.moodSkin)
                                    LazyColumn(
                                        state = listState,
                                        modifier =
                                        Modifier
                                            .fillMaxSize()
                                            // Rectángulo de la ventana de lista: envíe el vuelo y utilícelo para calcular el punto de aterrizaje de la burbuja
                                            .onGloballyPositioned { listWindowBounds = it.boundsInWindow() }
                                            // v2.5.0: La fuente mundial de vidrio se ha trasladado a MainActivity(NavGraph capa exterior),Esta página no será marcada nuevamente.
                                            .then(
                                                if (widthClass == WindowWidthClass.Expanded) {
                                                    Modifier.widthIn(max = 720.dp)
                                                } else {
                                                    Modifier
                                                },
                                            )
                                            // v0.31: El desplazamiento de las teclas de volumen se controla mediante el interruptor chatPrefs.volumeKeyScroll
                                            .then(
                                                if (state.chatPreferences.volumeKeyScroll) {
                                                    Modifier.onVolumeKeyEvent { direction ->
                                                        volumeScrollScope.launch {
                                                            listState.scrollBy(direction * VOLUME_SCROLL_DISTANCE_PX)
                                                        }
                                                    }
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                        // v1.0.92: El espaciado de mensajes ya no se utiliza spacedBy —— Mide el espaciado por cantidad de artículo.,Y la tarjeta del grupo de operaciones.
                                        // Mostrará los mensajes restantes del grupo como vacíos. item(Mantener el índice de mensajes sin cambios),Cada elemento vacío todavía contribuye
                                        // una porción messageGap,Cuando se llama a la herramienta demasiadas veces, aparecerá un gran espacio en blanco fantasma.(Prueba real del usuario:22 operaciones
                                        // Superponer la mitad de la pantalla en blanco). En cambio, el artículo normal utiliza un acolchado inferior para asumir la responsabilidad. messageGap:
                                        // Elemento vacío tamaño cero espacio cero,El espaciado visual es consistente con la versión original.(Habrá un elemento más al final del último elemento de la lista.
                                        // messageGap la brecha,diferencia aceptable).
                                        // v1.0.72: La cima da paso a tres islas suspendidas.;Evite los campos de entrada en la parte inferior.
                                        // La caja exterior mantiene la altura completa.,Deje que el mapa de mensajes de la derecha se extienda hasta el borde superior del campo de entrada.
                                        // fix(Oclusión del mapa de mensajes): Durante sesiones largas, el lado derecho está reservado para la barra de navegación. [MESSAGE_MAP_RESERVED_WIDTH];
                                        // Tanto el contenido del mensaje como la línea de acción inferior terminan en el área reservada.,Ya no se superpone con el punto de acceso de la barra de navegación.,
                                        // Copia más a la derecha/Se puede hacer clic en el botón regenerar normalmente.
                                        contentPadding =
                                        PaddingValues(
                                            top = innerPadding.calculateTopPadding(),
                                            // Además de la altura de la barra inferior medida por Scaffold, agregue otra
                                            // Ingrese el margen de altura de la isla misma: la isla de entrada es la capa flotante,Scaffold medido
                                            // altura sin que sobresalga/En la parte elevada se suprimirá el último mensaje.
                                            bottom =
                                                innerPadding.calculateBottomPadding() +
                                                    MusePaddings.inputIslandBottomGap +
                                                    MusePaddings.inputIslandVertical * 2,
                                            end = if (showMessageMap) MESSAGE_MAP_RESERVED_WIDTH else 0.dp,
                                        ),
                                    ) {
                                        // v1.0.47 P6: Agent Mode tarjeta recordatoria — bloqueo de sesión/Degradación de herramienta débil/Agent Mode Consejos.
                                        // v1.0.54: eliminar"Agent Modo de sesión bloqueada"Consejos(No se requieren comentarios del usuario),Mantener solo rebajas/Consejos.
                                        val showAgentHint =
                                            !state.weakToolHint.isNullOrEmpty() ||
                                                !state.agentModeHint.isNullOrEmpty()
                                        if (showAgentHint) {
                                            item(key = "agent_mode_hint") {
                                                Box(Modifier.padding(bottom = MusePaddings.messageGap)) {
                                                    AgentModeHintCard(
                                                        isSessionLocked = false,
                                                        weakToolHint = state.weakToolHint,
                                                        agentModeHint = state.agentModeHint,
                                                        onDismissWeakToolHint = viewModel::dismissWeakToolHint,
                                                        onDismissAgentModeHint = viewModel::dismissAgentModeHint,
                                                    )
                                                }
                                            }
                                        }
                                        // v2.6.9(comentarios de los usuarios): Correcciones de visibilidad para el portal de carga del historial.
                                        //
                                        // La implementación anterior estaba solo en isLoadingMore=true El marcador de posición de brillo se muestra cuando
                                        // En otras palabras, los usuarios sólo tienen“Ya puedo activar la carga”Sólo puedes ver las cosas en ese momento;
                                        // Después de leer este historial, no hay nada en la parte superior y el usuario no sabe si puede desplazarse hacia arriba.
                                        // Comentarios originales:“Hay algunos pequeños carteles para pasar páginas, otros no, y quiero ver los mensajes anteriores pero no puedo.”.
                                        //
                                        // Ahora está dividido en tres estados:
                                        //   Cargando → shimmer Marcador de posición
                                        //   Hay una historia anterior → se puede hacer clic“Cargar mensajes antiguos”entrada
                                        //   Finalizado/no es necesario cargar → no mostrar
                                        if (state.isLoadingMore) {
                                            item(key = "load_more") {
                                                Box(Modifier.padding(bottom = MusePaddings.messageGap)) {
                                                    HistoryLoadMorePlaceholder()
                                                }
                                            }
                                        } else if (state.hasMoreHistory) {
                                            item(key = "load_more_entry") {
                                                Box(Modifier.padding(bottom = MusePaddings.messageGap)) {
                                                    HistoryLoadMoreEntry(
                                                        onClick = { viewModel.loadMoreHistory() },
                                                    )
                                                }
                                            }
                                        }
                                        // F-4: /pin Banner de mensaje fijado(estado de memoria,Borrado por switchSession después de cambiar de sesión)
                                        state.pinnedMessageContent?.let { pinned ->
                                            item(key = "pinned_message_top") {
                                                Box(Modifier.padding(bottom = MusePaddings.messageGap)) {
                                                    PinnedMessageBanner(
                                                        content = pinned,
                                                        onDismiss = viewModel::clearPinnedMessage,
                                                    )
                                                }
                                            }
                                        }
                                        // El último ID del asistente actualmente en ejecución: sólo el grupo de procesos al que pertenece este elemento se considera "grupo activo".
                                        // La fila en ejecución nunca se mostrará en el grupo de historial. Cuente una vez fuera de la lista,
                                        // Ponerlo en elemento hará que se escanee la lista para cada mensaje.
                                        val activeAssistantLastId =
                                            if (isStreaming) {
                                                visibleMessages.lastOrNull {
                                                    it.role != MessageRole.USER
                                                }?.id
                                            } else {
                                                null
                                            }
                                        itemsIndexed(
                                            // v1.0.4 (P3-4): Renderizado en modo rendimiento visibleMessages(Últimos N artículos);
                                            // En modo sin rendimiento visibleMessages == messages,El comportamiento se mantiene sin cambios.
                                            visibleMessages,
                                            key = { _, it -> it.id },
                                            // v1.100: contentType Deje que LazyColumn reutilice elementos del mismo tipo measure cache
                                            contentType = { _, it -> it.role.name },
                                        ) { index, msg ->
                                            // Agregación de grupos de acción:Primera tarjeta de grupo de renderizado del grupo,Las barras restantes del grupo no se renderizan.(Absorbido visualmente por el elemento anterior.).
                                            val groupedRun = if (showToolCallDetails) groupedRuns[msg.id.toString()] else null
                                            if (groupedRun != null) {
                                                if (groupedRun.msgs.first().id == msg.id) {
                                                    ToolRunCard(
                                                        msgs = groupedRun.msgs,
                                                        onCardAction = handleCardAction,
                                                        // La transmisión actual debe estar en este grupo para que se considere "este grupo se está ejecutando".
                                                        // De lo contrario, cuando el modelo comience a escribir texto después de ejecutar la herramienta, el elemento de texto ya no pertenece a este grupo.
                                                        // A pesar de que está componiendo cartas, todavía piensa que todavía está vivo y siempre hay una línea de estado obsoleto.
                                                        live = ToolRunLiveState(
                                                            isActive = groupedRun.msgs.any {
                                                                it.id == activeAssistantLastId
                                                            },
                                                            progressMessage = state.toolProgressMessage,
                                                            activeMessageId = activeAssistantLastId?.toString(),
                                                        ),
                                                        modifier =
                                                        Modifier
                                                            .padding(horizontal = MusePaddings.screen)
                                                            .padding(bottom = MusePaddings.messageGap),
                                                    )
                                                }
                                                return@itemsIndexed
                                            }
                                            // separador de fecha: Cuando los mensajes adyacentes duren días, inserte una línea delgada + centre el texto de la fecha
                                            // v1.0.4 (P3-4): prevMsg Tomado de visibleMessages,Consistente con el orden de renderizado
                                            val prevMsg = visibleMessages.getOrNull(index - 1)
                                            val showDateSeparator =
                                                prevMsg != null &&
                                                    !isSameDay(prevMsg.createdAt, msg.createdAt)
                                            // v2.x: separador de contexto —— pintado en"Límites comprimidos"antes de ese mensaje.
                                            // Solo se utilizan anclajes de interfaz para la representación y los mensajes no se modifican, por lo que es imposible que se los coloque por error en la biblioteca.
                                            val showContextDivider =
                                                boundaryIdForRender != null && msg.id.toString() == boundaryIdForRender
                                            // La animación del elemento del mensaje, la última herramienta asistente y el estado de depuración comparten la misma lista visible de evaluación del último elemento.
                                            val isLast = msg.id == visibleMessages.lastOrNull()?.id
                                            // La última pregunta del usuario: controlar el peso en la parte inferior de los mensajes de los usuariosrollbotón.
                                            // Incluso si el error de IA genera un mensaje de asistente fallido, siempre que este usuario siga haciendo la última pregunta,
                                            // pesadorollEs utilizable y el usuario no necesita eliminarlo ni reenviarlo.
                                            // v1.0.92 Rendimiento: lastUserId Precálculo,Evitar dentro del artículo O(n) Barrido de cola.
                                            val isLastUserMessage = msg.role == MessageRole.USER && lastUserId == msg.id
                                            // v1.0.53: Información del grupo de sucursales correspondiente al mensaje actual.(directamente desde ConversationTree)
                                            // v1.0.92 Rendimiento: Cambiar a consulta de índice(La implementación anterior recorre todo el árbol para cada mensaje.,costo total O(n²)).
                                            val branchInfo = branchInfoByMessageId[msg.id.toString()]
                                            // Las operaciones de ejecución y expansión de herramientas actualizan el mapa de ChatUiState. Lea la instantánea actual directamente aquí;
                                            // No se puede utilizar remember(msg.id) captura inicial state,De lo contrario, la nueva tarjeta de tarea/estado expandido
                                            // No pasará a ser existente. LazyColumn item,La página parece“Herramienta no llamada”.
                                            val expandedState = state.messageExpandedStates[msg.id.toString()]
                                            val taskCard =
                                                if (showToolCallDetails) state.taskCards[msg.id.toString()] else null
                                            // v1.100: isTranslating/isSpeaking exacto a msg.id,Limitar con deriveStateOf
                                            val isTranslating = state.isTranslating && state.translatingMessageId == msg.id
                                            val isSpeaking = state.isSpeaking && state.speakingMessageId == msg.id
                                            // v1.43: Observe la lista de tarjetas de productos asociadas al mensaje.
                                            // H-S1: Utilice produceState con msg.id como key,Evite la reconstrucción repetida de Flow + comprobaciones repetidas de la base de datos durante la reorganización
                                            val artifacts by produceState(initialValue = emptyList<ArtifactEntity>(), msg.id) {
                                                viewModel.observeArtifactsByMessage(msg.id.toString()).collect { value = it }
                                            }
                                            // v0.48: agrupación de mensajes — El artículo anterior tiene el mismo rol e intervalo de tiempo. < 5 minutos → Comprimir avatares y marcas de tiempo
                                            // v2.0.1: Mensajes continuos con el mismo carácter.(Pensamientos sobre la eliminación del asistente/rueda de herramientas)No más avatares repetidos —
                                            // Solo se mostrará un avatar en una ronda de respuestas.(Comentarios de los usuarios"Aparecen dos avatares").
                                            // v2.7: Roles grupales —— rueda de herramientas (TOOL) se fusiona con el lado del asistente y solo se retiene un avatar en una ronda de respuesta.

                                            // El juicio original se compara directamente con el rol.TOOL ≠ ASSISTANT hará que la cadena de agrupación se rompa en cada rueda de herramienta,

                                            // La manifestación es que el avatar y el nombre aparecen repetidamente en la misma respuesta.

                                            fun groupingRole(role: io.zer0.ai.core.MessageRole) =

                                                if (role == io.zer0.ai.core.MessageRole.TOOL) {
                                                    io.zer0.ai.core.MessageRole.ASSISTANT
                                                } else {
                                                    role
                                                }

                                            val showAvatar =

                                                prevMsg == null ||

                                                    groupingRole(prevMsg.role) != groupingRole(msg.role) ||

                                                    // v2.7: La regla de intervalo solo tiene efecto en el lado del usuario. —— En la ronda del asistente, la herramienta funciona durante mucho tiempo y sigue siendo la misma ronda.

                                                    // No deberías volver a dibujar tu avatar sólo porque lleva más de 5 minutos.

                                                    (
                                                        msg.role == io.zer0.ai.core.MessageRole.USER &&

                                                            (msg.createdAt - prevMsg.createdAt) > MESSAGE_GROUP_INTERVAL_MS
                                                        )
                                            val showTimestamp = showAvatar // Avatar y marca de tiempo mostrados simultáneamente
                                            // v0.36 Optimización del rendimiento:Nivel de elemento de caché lambda,Evite que la reorganización principal provoque que falle todo el MessageBubble.
                                            val onEdit =
                                                remember(msg.id, msg.role) {
                                                    {
                                                        if (msg.role == MessageRole.USER) {
                                                            sheetState.editingUserMessage = msg
                                                        } else {
                                                            sheetState.editingMessage = msg
                                                        }
                                                    }
                                                }
                                            val onQuote = remember(msg.id) { { viewModel.setReplyingTo(msg) } }
                                            val onTranslate =
                                                remember(msg.id) { { lang: String -> viewModel.translateMessage(msg.id, lang) } }
                                            val onToggleFavorite = remember(msg.id) { { viewModel.toggleFavorite(msg.id) } }
                                            val onToggleTts =
                                                remember(msg.id) {
                                                    // v2.6.8: Al leer la exportación, no se puede leer el JSON del estado de la tarjeta.
                                                    { viewModel.toggleTts(msg.id, InternalMarkupSanitizer.exportContent(msg.content)) }
                                                }
                                            val onToggleTaskCardExpand =
                                                remember(msg.id) { { viewModel.toggleTaskCardExpand(msg.id.toString()) } }
                                            val onToggleTaskCardPause =
                                                remember(msg.id) { { viewModel.toggleTaskCardPause(msg.id.toString()) } }
                                            val onToggleTaskStepPause =
                                                remember(msg.id) {
                                                    {
                                                            stepId: String ->
                                                        viewModel.toggleTaskStepPause(msg.id.toString(), stepId)
                                                    }
                                                }
                                            val onCancelTask = remember(msg.id) { { viewModel.stop() } }
                                            val onRetryTaskCardStep =
                                                remember(msg.id) {
                                                    {
                                                            stepId: String ->
                                                        viewModel.retryFailedStep(msg.id.toString(), stepId)
                                                    }
                                                }
                                            val onShareSession =
                                                remember(viewModel, ioScope) {
                                                    { sheetState.showExportSheet = true }
                                                }
                                            // F-2: Reenvío entre sesiones — Mensaje de usuario original,Convertir mensajes del asistente a texto sin formato
                                            val onForward =
                                                remember(msg.id) {
                                                    {
                                                        // v2.6.8: Adelante por la salida unificada. El mensaje del usuario lo escribe el propio usuario, solo se quita la cola del estado de la tarjeta.
                                                        // (El calibre es consistente con la visualización de burbujas); el mensaje del asistente se exporta y las marcas internas se eliminan.
                                                        forwardText =
                                                            if (msg.role == MessageRole.USER) {
                                                                InternalMarkupSanitizer.stripCardState(msg.content)
                                                            } else {
                                                                InternalMarkupSanitizer.exportContent(msg.content)
                                                            }
                                                    }
                                                }
                                            // v1.58: Bifurca la conversación de este mensaje
                                            val onFork = remember(msg.id) { { viewModel.forkSessionFromMessage(msg.id) } }
                                            // v2.x Rendimiento: Las instancias de devolución de llamada con el mismo msg.id se deben reutilizar en todas las reorganizaciones.
                                            // Hay varios lugares aguas abajo que utilizan esta lambda como clave para recordar (Card Bridge → Toda la tarjeta HTML),
                                            // La sustitución de nuevos objetos en cada cuadro destruirá el caché.key Solo ponga msg.id inmutable,
                                            // objeto mutable (sheetState,branchInfo etc.) no ingresa la clave.
                                            val onToggleTranslationExpanded =
                                                remember(msg.id) {
                                                    { viewModel.toggleMessageTranslationExpanded(msg.id.toString()) }
                                                }
                                            val onToggleSelection =
                                                remember(msg.id) { { viewModel.toggleMessageSelection(msg.id) } }
                                            val onEnterMultiSelect = remember { { viewModel.setSelectionMode(true) } }
                                            val onToggleMoodExpanded =
                                                remember(msg.id) { { viewModel.toggleMessageMoodExpanded(msg.id.toString()) } }
                                            val onToggleReasoningExpanded =
                                                remember(msg.id) {
                                                    { viewModel.toggleMessageReasoningExpanded(msg.id.toString()) }
                                                }
                                            val onToggleReflectionExpanded =
                                                remember(msg.id) {
                                                    { viewModel.toggleMessageReflectionExpanded(msg.id.toString()) }
                                                }
                                            val onDeleteMessage = remember(msg.id) { { viewModel.deleteMessage(msg.id) } }
                                            val onDeleteWithFollowing =
                                                remember(msg.id) { { viewModel.deleteMessageWithFollowing(msg.id) } }
                                            // v1.0.92: devolución de tarjeta — Mensaje de script enviado/guardado como artefacto
                                            // v2.6.7: Comparta el mismo procesador con la tarjeta de grupo para evitar la deriva entre los dos conjuntos.
                                            val onCardAction =
                                                remember(msg.id) {
                                                    { action: CardAction -> handleCardAction(msg.id.toString(), action) }
                                                }
                                            // Las animaciones de elementos de mensaje son uniformemente museAnimateItem; el último mensaje de la secuencia no se inserta./animación de desplazamiento,
                                            // Evite las fluctuaciones causadas por el incremento de contenido y la animación del diseño de la lista que se ejecutan al mismo tiempo.
                                            Column(
                                                modifier =
                                                museAnimateItem(enabled = !(isLast && isStreaming))
                                                    .padding(bottom = MusePaddings.messageGap),
                                            ) {
                                                // El separador de fecha se muestra encima del mensaje.
                                                if (showDateSeparator) {
                                                    DateSeparator(timestamp = msg.createdAt)
                                                }
                                                // v2.x: Los separadores de contexto se dibujan en"Límites comprimidos"antes de ese mensaje ——
                                                // También es el mensaje"Esto es lo que el modelo aún puede ver."punto de partida.
                                                if (showContextDivider) {
                                                    ChatContextDivider(
                                                        totalCovered = state.contextCheckpointTotalCovered,
                                                        expanded = contextDividerExpanded,
                                                        onToggle = { contextDividerExpanded = !contextDividerExpanded },
                                                        // El divisor tiene su propio relleno horizontal para compensar el espacio en blanco excesivo causado por el espacio inferior del mensaje exterior.
                                                        modifier = Modifier.padding(bottom = 0.dp),
                                                    )
                                                }
                                                // v2.x: Se ha eliminado la referencia de la diapositiva izquierda.(Los comentarios de los usuarios son que la tasa de falsos toques es alta) — Se accede a las cotizaciones a través del menú de pulsación larga.
                                                MessageBubble(
                                                    msg = msg,
                                                    // v1.0.20 (Task 3): isStreaming Leer valor derivado,Evite que cada mensaje se reorganice debido a las pulsaciones de teclas
                                                    isStreaming = isStreaming,
                                                    isLastAssistant = isLast && msg.role == MessageRole.ASSISTANT,
                                                    // v2.x: Al saltar de los resultados de búsqueda,state.highlightedMessageId Pulsa este mensaje →
                                                    // Pase searchHighlightQuery para que MessageBubble resalte el texto coincidente;De lo contrario null
                                                    highlightText =
                                                    if (msg.id.toString() == state.highlightedMessageId) {
                                                        state.searchHighlightQuery
                                                    } else {
                                                        null
                                                    },
                                                    isTranslating = isTranslating,
                                                    // H11: El mensaje de traducción lleva el contenido del mensaje fuente.(Comparación y plegado de texto original.),No se transmite cuando falta el mensaje fuente
                                                    translationSourceContent =
                                                    msg.translationSourceId?.let { srcId ->
                                                        messages.find { it.id.toString() == srcId }
                                                            ?.let(::buildTranslationSourceText)
                                                    },
                                                    // v2.4.6: Los mensajes vienen con traducciones.(Idioma→traducción)— bloque expandible
                                                    translations = msg.translations,
                                                    isTranslationExpanded = expandedState?.isTranslationExpanded,
                                                    onToggleTranslationExpanded = onToggleTranslationExpanded,
                                                    // v2.3: debug Resumen de rendimiento del modo(Sólo el último mensaje del asistente.)
                                                    debugInfo = if (isLast && msg.role == MessageRole.ASSISTANT) state.debugInfo else null,
                                                    onEdit = onEdit,
                                                    onQuote = onQuote,
                                                    onRegenerate = viewModel::regenerateLastAssistant,
                                                    onContinue = viewModel::continueGeneration,
                                                    selectionMode = state.selectionMode,
                                                    selected = msg.id.toString() in state.selectedMessageIds,
                                                    onToggleSelection = onToggleSelection,
                                                    onEnterMultiSelect = onEnterMultiSelect,
                                                    onTranslate = onTranslate,
                                                    onToggleFavorite = onToggleFavorite,
                                                    // Etapa J: copiar el contenido del mensaje al portapapeles(Pulsación larga → Copiar)
                                                    // M-S8: clipboard Escribir en hilo IO,evitar el hilo principal IPC
                                                    onCopyMessage = { text ->
                                                        ioScope.launch {
                                                            val clipboard =
                                                                withContext(Dispatchers.IO) {
                                                                    context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                                                        as android.content.ClipboardManager
                                                                }
                                                            clipboard.setPrimaryClip(
                                                                android.content.ClipData.newPlainText("Muse Message", text),
                                                            )
                                                            MuseToast.show(copiedToastText)
                                                        }
                                                    },
                                                    // Phase 8.7: TTS leer en voz alta(Solo mensajes de IA)
                                                    isSpeaking = isSpeaking,
                                                    onToggleTts = onToggleTts,
                                                    // Phase 8.8: tarjeta de tarea
                                                    taskCard = taskCard,
                                                    // v2.4.5 fix: Interruptor de visualización del proceso de llamada de herramienta(ajustes → charlar)
                                                    showToolCallDetails = showToolCallDetails,
                                                    // Cuando el producto ha sido renderizado por la tarjeta de tarea del mensaje principal,No más sacar la misma carta aquí
                                                    cardRenderedByTaskCard =
                                                    cardRenderedByTaskCard.contains(msg.id.toString()),
                                                    glassActive = glassActive,
                                                    glassConfig = glassConfig,
                                                    // v2.x(comentarios de los usuarios): Cuando hay una imagen de fondo, el fondo de burbuja se conserva para artículos largos (para garantizar el contraste)
                                                    hasBackgroundImage = !chatBackground.isNullOrBlank(),
                                                    backgroundIsDark = backgroundIsDark,
                                                    // v1.201: Enlace delegado(Sólo llega el último mensaje de IA,Evite la visualización repetida de mensajes históricos)
                                                    delegationChain =
                                                    if (showToolCallDetails && isLast && msg.role == MessageRole.ASSISTANT) {
                                                        state.delegationChain
                                                    } else {
                                                        null
                                                    },
                                                    // Phase 10.1: Devolución de llamada de interacción de tarjeta de tarea
                                                    onToggleTaskCardExpand = onToggleTaskCardExpand,
                                                    onRetryTaskCardStep = onRetryTaskCardStep,
                                                    onToggleTaskCardPause = onToggleTaskCardPause,
                                                    onToggleTaskStepPause = onToggleTaskStepPause,
                                                    // R-UI-09: Botón de cancelación de la tarjeta de tarea -> Detener la compilación actual
                                                    onCancelTask = onCancelTask,
                                                    // v1.25: Mantenga presionado el menú "Legate to Assistant"
                                                    onDelegate = { sheetState.showDelegateSheet = DelegateSheetMode.Message(msg) },
                                                    // v0.29 P0-3: Compartir toda la conversación(Exportar Markdown → sistema share sheet)
                                                    onShareSession = onShareSession,
                                                    // F-2: Reenvío entre sesiones
                                                    onForward = onForward,
                                                    onFork = onFork,
                                                    // v1.48: Menú de pulsación larga"Eliminar mensaje"
                                                    onDeleteMessage = onDeleteMessage,
                                                    onDeleteWithFollowing = onDeleteWithFollowing,
                                                    // v0.29 P0-4: AI La parte inferior del mensaje muestra el nombre del modelo + estimación del token.
                                                    modelName = displayModelName,
                                                    // v0.31: Las preferencias de comportamiento del chat se pasan a MessageBubble
                                                    chatPrefs = state.chatPreferences,
                                                    // Phase 4: Piel de burbuja anfitriona(null = apariencia existente,Análisis y respaldo en MessageBubble)
                                                    bubbleSkin = bubbleSkin,
                                                    // v0.48: Parámetros de agrupación de mensajes + fuente de avatar AI
                                                    showAvatar = showAvatar,
                                                    showUserAvatar = !isAgentMode && state.chatPreferences.showUserAvatar,
                                                    userAvatarText = accountState.userName.take(1).ifBlank { "U" },
                                                    showTimestamp = showTimestamp,
                                                    assistant = state.currentAssistant,
                                                    // v1.43: Lista de tarjetas de productos y haga clic para ver
                                                    artifacts = artifacts,
                                                    onArtifactClick = viewModel::selectArtifact,
                                                    // v2.0.1: Palabras clave para trabajos de imágenes (esperamos la llamada a generate_image más reciente;
                                                    // toolCalls No persiste, siga la ruta de persistencia de toolCallInfo después de reiniciar)
                                                    // v2.x Rendimiento: Desplácese hacia adelante y los elementos mencionados en la lista se contarán una vez. Aquí sólo buscamos la tabla.
                                                    imageGenPrompt = imageGenPrompts[msg.id.toString()],
                                                    // v1.45: mood/reasoning El estado expandido es administrado centralmente por ViewModel
                                                    isMoodExpanded = expandedState?.isMoodExpanded,
                                                    isReasoningExpanded = expandedState?.isReasoningExpanded,
                                                    isReflectionExpanded = expandedState?.isReflectionExpanded,
                                                    onToggleMoodExpanded = onToggleMoodExpanded,
                                                    onToggleReasoningExpanded = onToggleReasoningExpanded,
                                                    onToggleReflectionExpanded = onToggleReflectionExpanded,
                                                    // v1.137: La tarjeta de programación está asociada por messageId al mensaje del asistente que la creó.,Desplácese con este mensaje.
                                                    // viejo plan(Ninguno messageId)Volver al último ID de asistente,Mantener la compatibilidad con versiones anteriores.
                                                    agentPlan =
                                                    if (msg.role == MessageRole.ASSISTANT) {
                                                        plansByMessageId[msg.id.toString()]
                                                            ?: if (
                                                                msg.id == lastAssistantId &&
                                                                latestPlan?.let {
                                                                    it.messageId == null || it.messageId !in visibleMessageIds
                                                                } == true
                                                            ) {
                                                                latestPlan
                                                            } else {
                                                                null
                                                            }
                                                    } else {
                                                        null
                                                    },
                                                    // HTML/SVG Vista previa en pantalla completa de bloques de código
                                                    onHtmlPreview = onHtmlPreview,
                                                    // v1.0.92: devolución de tarjeta — Mensaje de script enviado/guardado como artefacto
                                                    // v2.6.7: Comparta el mismo procesador con la tarjeta de grupo para evitar la deriva entre los dos conjuntos.
                                                    onCardAction = onCardAction,
                                                    // v2.6.8: El puente de retorno de la tarjeta de producto en el paso de la tarjeta de tarea. —— mismo procesador.
                                                    // En la implementación anterior, la ruta de la tarjeta de tareas no tenía ningún puente y las tarjetas se podían ver pero no hacer clic.
                                                    onTaskCardCardAction = handleCardAction,
                                                    // v1.138: ayuda visual UI — Análisis en progreso + Etiqueta completada
                                                    // v1.0.16: El progreso solo se muestra en el mensaje que se está analizando.(messageId partido),Evitar que todos los mensajes de USUARIO se muestren al mismo tiempo"analizando"
                                                    // v1.0.20 (Task 3): visionProgress Leer valor derivado,Evite que cada mensaje se reorganice debido a las pulsaciones de teclas
                                                    visionAssistProgress =
                                                    if (
                                                        msg.role == MessageRole.USER &&
                                                        visionProgress?.messageId == msg.id.toString()
                                                    ) {
                                                        visionProgress
                                                    } else {
                                                        null
                                                    },
                                                    visionAssisted =
                                                    if (msg.role == MessageRole.USER) {
                                                        msg.id.toString() in state.visionAssistedMessageIds
                                                    } else {
                                                        false
                                                    },
                                                    isLastUserMessage = isLastUserMessage,
                                                    branchIndex = branchInfo?.selectIndex ?: 0,
                                                    branchCount = branchInfo?.branchCount ?: 1,
                                                    onBranchPrevious = {
                                                        branchInfo?.let { info ->
                                                            if (msg.role == MessageRole.USER) {
                                                                viewModel.selectUserVariant(info.groupId, info.selectIndex - 1)
                                                            } else {
                                                                viewModel.selectAssistantVariant(
                                                                    info.parentGroupId ?: info.groupId,
                                                                    info.groupId,
                                                                    info.selectIndex - 1,
                                                                )
                                                            }
                                                        }
                                                    },
                                                    onBranchNext = {
                                                        branchInfo?.let { info ->
                                                            if (msg.role == MessageRole.USER) {
                                                                viewModel.selectUserVariant(info.groupId, info.selectIndex + 1)
                                                            } else {
                                                                viewModel.selectAssistantVariant(
                                                                    info.parentGroupId ?: info.groupId,
                                                                    info.groupId,
                                                                    info.selectIndex + 1,
                                                                )
                                                            }
                                                        }
                                                    },
                                                    tokenStats =
                                                    if (isLast && msg.role == MessageRole.ASSISTANT && state.tokenEstimateEnabled) {
                                                        {
                                                            TokenStatsBar(
                                                                message = msg,
                                                                historyTokens = state.contextTokenCount,
                                                                contextWindow = state.contextMaxTokens,
                                                                // v2.0: Muestra directamente la entrada cuando hay un uso real del proveedor./salida,De lo contrario, recurra a la estimación.(~)
                                                                promptTokens = msg.promptTokens,
                                                                completionTokens = msg.completionTokens,
                                                                // No se realiza ninguna estimación de BPE local durante la transmisión (consulte la descripción de la transmisión de TokenStatsBar)
                                                                streaming = state.isStreaming,
                                                                modifier =
                                                                Modifier
                                                                    .fillMaxWidth()
                                                                    .padding(top = MusePaddings.tightGap),
                                                            )
                                                        }
                                                    } else {
                                                        null
                                                    },
                                                )
                                            }
                                        }
                                        // P5-G: Tarjeta de marcador de posición en la generación de imágenes.(Más comentarios que texto plano LoadingDots)
                                        if (state.isGeneratingImage) {
                                            // H-S5: Proporcionar estabilidad explícitamente key
                                            item(key = "image_placeholder") {
                                                Box(Modifier.padding(bottom = MusePaddings.messageGap)) {
                                                    ImageGenerationPlaceholder()
                                                }
                                            }
                                        }
                                        // v1.0.4 (P1): Tarjeta marcador de posición en la generación de vídeo(Generar simetría con la imagen.)
                                        if (state.isGeneratingVideo) {
                                            item(key = "video_placeholder") {
                                                Box(Modifier.padding(bottom = MusePaddings.messageGap)) {
                                                    VideoGenerationPlaceholder()
                                                }
                                            }
                                        }
                                        // Tarjeta de aprobación de herramienta:Las llamadas a herramientas pendientes de aprobación muestran la aprobación/botón de rechazo
                                        // v2.6.4: La tarjeta de tareas del subasistente se cambia a la única salida (fusionando la pequeña ventana flotante original y el banner de delegación superior).
                                        // Ya no está controlado por el interruptor "Detalles de llamada de herramienta" —— Las subtareas en segundo plano son eventos independientes,
                                        // Los usuarios deberían poder ver y administrar los detalles de la herramienta incluso cuando los desactiven.
                                        if (state.activeSubagentThreads.isNotEmpty() || state.pendingSubagentTasks.isNotEmpty()) {
                                            item(key = "subagent_task_list") {
                                                Box(Modifier.padding(bottom = MusePaddings.messageGap)) {
                                                    io.zer0.muse.ui.taskcard.SubagentTaskListCard(
                                                        activeThreads = state.activeSubagentThreads,
                                                        pendingTasks = state.pendingSubagentTasks,
                                                        onCancel = { taskId -> viewModel.cancelSubagentTask(taskId) },
                                                        onCloseThread = { tid -> viewModel.closeSubagentThread(tid) },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } // I3: Finalización del límite de error del área de chat
                    }

                    // v1.0.29: desplazarse hasta el botón inferior — Cambiar al pequeño botón transparente redondo estilo GPT,
                    // Solo cuando el usuario desliza activamente hacia arriba(userScrolledUp)mostrar más tarde,Ubicado encima del campo de entrada.
                    AnimatedVisibility(
                        visible = userScrolledUp && visibleMessages.isNotEmpty(),
                        enter = MuseMotion.verticalSlideFadeEnter(),
                        exit = MuseMotion.verticalSlideFadeExit(),
                        modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            // v2.6.0 fix: Esta capa flotante pertenece al Cuadro que cubre toda la página (incluida el área del campo de entrada).
                            // Solo confía en align(BottomCenter)+padding(screen) se pegará en la parte inferior de la pantalla → Ajuste debajo del campo de entrada.
                            // Aquí, la altura dada por la barra inferior se eleva adicionalmente (innerPadding.bottom), flota sobre el campo de entrada.
                            .padding(
                                // Utilice la altura medida de la barra inferior en lugar de relleno interno:
                                // Este último sigue siendo el valor anterior en el marco cuando aparece la barra plana, lo que hará que el botón presione la barra plana.
                                bottom =
                                MusePaddings.screen +
                                    with(LocalDensity.current) { bottomBarHeightPx.toDp() },
                            ),
                    ) {
                        Box(
                            modifier =
                            Modifier
                                .size(MuseIconSizes.touchTarget)
                                .clickable {
                                    userScrolledUp = false
                                    isProgrammaticScroll.value = true
                                    scrollToBottomScope.launch {
                                        val msgs = visibleMessages
                                        if (msgs.isEmpty()) return@launch
                                        try {
                                            // v2.x: Mismo calibre que el seguimiento automático. — Desplácese hacia el final de la lista hasta que ya no pueda desplazarse,
                                            // Ya no depende de“Desplazamiento inferior del mensaje”(Los mensajes largos van en la dirección opuesta.).
                                            var snapGuard = 0
                                            while (listState.canScrollForward && snapGuard < 100) {
                                                listState.scrollBy(SNAP_SCROLL_STEP_PX)
                                                snapGuard++
                                            }
                                        } finally {
                                            isProgrammaticScroll.value = false
                                            // v1.0.92: Consume el siguiente evento de fin de desplazamiento del programa,Evite el bloqueo accidental
                                            programmaticScrollCooldownUntil = System.currentTimeMillis() + 250L
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                shape = CircleShape,
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = MuseIcons.arrowDown,
                                        contentDescription = stringResource(R.string.chat_scroll_to_bottom_cd),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                                    )
                                }
                            }
                        }
                    }

                    // Reanudar la carga desde el punto de interrupción(Recuperación de interrupción de herramientas)Banner:Se muestra cuando se detecta que hay llamadas a herramientas sin terminar en esta sesión.
                    // La última transmisión del usuario fue interrumpida.(parada manual/proceso matado),tool_calls La cola no se ha completado.
                    // Proporciona dos operaciones.:
                    //  - Reanudar la ejecución:Ajuste viewModel.resumePendingToolCalls para ejecutar las herramientas pendientes en secuencia,
                    //    El resultado se rellena como un mensaje de HERRAMIENTA.,Active launchStream nuevamente para permitir que LLM continúe
                    //  - descartar:Ajuste viewModel.discardPendingToolCalls para borrar registros pendientes,Banner ocultar
                    // Banners principales mutuamente excluyentes — Progreso de la compresión > error > Reanudar la carga desde el punto de interrupción > delegar > No configurado,Evite la superposición y preserve la retroalimentación operativa.
                    val showPendingResume = state.pendingToolCallCount > 0 && !isStreaming
                    val topBanner = resolveChatTopBanner(
                        isCompressing = state.isCompressing,
                        showPendingResume = showPendingResume,
                        hasErrors = state.errors.isNotEmpty(),
                        isConfigured = state.isConfigured,
                    )
                    AnimatedVisibility(
                        // v1.0.20 (Task 3): isStreaming Leer valor derivado,Evite las teclas de entrada que desencadenen la reorganización del banner
                        visible = topBanner == ChatTopBanner.PENDING_TOOLS,
                        enter = MuseMotion.expandFadeEnter(),
                        exit = MuseMotion.expandFadeExit(),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset),
                    ) {
                        val pendingCd = stringResource(R.string.chat_pending_tools_cd)
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = MuseShapes.medium,
                            tonalElevation = 3.dp,
                            modifier =
                            Modifier
                                .padding(MusePaddings.itemGap)
                                .semantics { contentDescription = pendingCd },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(MusePaddings.itemGap),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                            ) {
                                Icon(
                                    imageVector = MuseIcons.alertCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        text =
                                        stringResource(
                                            R.string.chat_pending_tools_banner_title,
                                            state.pendingToolCallCount,
                                        ),
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = stringResource(R.string.chat_pending_tools_banner_subtitle),
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                MuseCapsuleButton(
                                    text = stringResource(R.string.chat_pending_tools_resume),
                                    onClick = {
                                        effectiveChatSessionId(state)?.let { viewModel.resumePendingToolCalls(it) }
                                    },
                                    variant = IosCapsuleButtonVariant.Text,
                                    fillWidth = false,
                                )
                                MuseCapsuleButton(
                                    text = stringResource(R.string.chat_pending_tools_discard),
                                    onClick = {
                                        // CHAT-10: Confirmar antes de descartar — Las llamadas incompletas no se pueden recuperar después de ser descartadas.
                                        showDiscardConfirm = true
                                    },
                                    variant = IosCapsuleButtonVariant.Text,
                                    fillWidth = false,
                                )
                            }
                        }
                    }
                    // CHAT-10: Descartar el cuadro de diálogo de confirmación de llamada de herramienta sin terminar
                    if (showDiscardConfirm) {
                        MuseDialog(
                            onDismissRequest = { showDiscardConfirm = false },
                            title = stringResource(R.string.chat_pending_tools_discard_confirm_title),
                            content = {
                                Text(stringResource(R.string.chat_pending_tools_discard_confirm_msg))
                            },
                            confirmText = stringResource(R.string.chat_pending_tools_discard),
                            destructive = true,
                            onConfirm = {
                                showDiscardConfirm = false
                                effectiveChatSessionId(state)?.let { viewModel.discardPendingToolCalls(it) }
                            },
                        )
                    }

                    // v1.0.4 (P2): Comprimir el historial de sesiones Banner — /compact Visualización continua durante,
                    // (El IconButton original en la parte superior se reemplaza con un círculo giratorio,No hay comentarios en el área de conversación.,El usuario no sabe si se está ejecutando la compresión.)
                    AnimatedVisibility(
                        visible = topBanner == ChatTopBanner.COMPRESSION,
                        enter = MuseMotion.expandFadeEnter(),
                        exit = MuseMotion.expandFadeExit(),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = MuseShapes.medium,
                            tonalElevation = 3.dp,
                            modifier = Modifier.padding(MusePaddings.itemGap),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(MusePaddings.itemGap),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                            ) {
                                MuseSpinner(
                                    size = MuseIconSizes.iconSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Text(
                                    text = stringResource(
                                        if (state.compressionPhase == ManualCompressionPhase.UPDATING_MEMORY) {
                                            R.string.chat_updating_memory_banner
                                        } else {
                                            R.string.chat_compressing_banner
                                        },
                                    ),
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }

                    // v2.6.4: Se eliminó la ventana flotante del subagente. —— Todo se fusionó en SubagentTaskListCard dentro del flujo de mensajes.
                    // La ventana flotante original y los datos de la tarjeta de tareas tienen la misma fuente, y los dos lugares que aparecen al mismo tiempo son expresiones repetidas; están unificados en un solo lugar en la tarjeta de tareas.
                    // v2.6.4: Banner de delegación superior eliminado —— El progreso de la subtarea está uniformemente determinado por el
                    // SubagentTaskListCard expresión (la línea de título de la tarjeta ya contiene“N tareas”cantidad).
                    // v0.49: Visualización de lista de errores múltiples(Reintentar por tira/botón cerrar,AnimatedVisibility Transición)
                    // v1.131: El banner sin conexión de la red roja se mueve de abajo hacia arriba,Evite bloquear campos de entrada
                    // U-1: La barra de aviso de luz residente del servicio de modelo no está configurada — Ningún proveedor/Ninguno key(isConfigured=false)visualización de la hora,
                    // Haga clic para saltar al panel de configuración del modelo.;Solo se muestra cuando no hay un banner de mayor prioridad,Evite la superposición de pancartas de error.
                    AnimatedVisibility(
                        visible = topBanner == ChatTopBanner.NOT_CONFIGURED,
                        enter = MuseMotion.expandFadeEnter(),
                        exit = MuseMotion.expandFadeExit(),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MuseShapes.medium,
                            tonalElevation = 1.dp,
                            modifier =
                            Modifier
                                .padding(MusePaddings.itemGap)
                                .clickable { sheetState.showModelSheet = true },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(MusePaddings.itemGap),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                            ) {
                                Icon(
                                    imageVector = MuseIcons.alertCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                )
                                Text(
                                    text = stringResource(R.string.chat_model_not_configured_hint),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = topBanner == ChatTopBanner.ERROR,
                        enter = MuseMotion.expandFadeEnter(),
                        exit = MuseMotion.expandFadeExit(),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(MusePaddings.itemGap),
                            ) {
                                state.errors.forEach { err ->
                                    // L-S4: forEach negativo key,Proporcionar una identificación estable
                                    key(err.id) {
                                        val errorCd = stringResource(R.string.chat_error_cd, err.message)
                                        // v2.x(Comentarios del usuario n.º 3-3): El informe de errores se puede ampliar y copiar.
                                        // Previamente arreglado maxLines=2 + Ellipsis, solo se muestra la mitad del mensaje de error largo y no se puede copiar.
                                        var expanded by remember(err.id) { mutableStateOf(false) }
                                        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                                        Surface(
                                            color = MaterialTheme.colorScheme.errorContainer,
                                            shape = MuseShapes.medium,
                                            tonalElevation = 3.dp,
                                            modifier =
                                            Modifier.semantics {
                                                contentDescription = errorCd
                                            },
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(MusePaddings.itemGap),
                                                verticalAlignment = Alignment.Top,
                                                horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                                            ) {
                                                Text(
                                                    text = err.message,
                                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        // v2.x: Mantenga presionado para copiar el mensaje de error completo (igual que el mensaje mantenga presionado para copiar)
                                                        .combinedClickable(
                                                            onClick = { expanded = !expanded },
                                                            onLongClick = {
                                                                clipboard.setText(
                                                                    androidx.compose.ui.text.AnnotatedString(err.message),
                                                                )
                                                            },
                                                        ),
                                                    // Sin límite de líneas cuando se expande, se conservan 2 líneas de mensajes cuando se contraen
                                                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                // Inténtalo de nuevo:Regenerar la respuesta del asistente según el último mensaje del usuario
                                                // M-S11: solo red/El error de clase desconocida muestra el botón de reintento(API_KEY/RATE_LIMIT No tiene sentido volver a intentarlo)
                                                if (err.type == ChatErrorType.NETWORK || err.type == ChatErrorType.UNKNOWN) {
                                                    MuseCapsuleButton(
                                                        text = stringResource(R.string.chat_retry),
                                                        onClick = { viewModel.regenerateLastAssistant() },
                                                        variant = IosCapsuleButtonVariant.Text,
                                                        fillWidth = false,
                                                    )
                                                }
                                                MuseCapsuleButton(
                                                    text = stringResource(R.string.action_close),
                                                    onClick = { viewModel.dismissError(err.id) },
                                                    variant = IosCapsuleButtonVariant.Text,
                                                    fillWidth = false,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // v1.4: TTS controlador de suspensión(Esquina inferior derecha,InputBar arriba)
                    // Sólo cuando se reproduce TTS/Mostrar cuando está en pausa(Idle Oculto automáticamente por AnimatedVisibility cuando)
                    TtsControllerWidget(
                        modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = MusePaddings.screen, bottom = MusePaddings.screen)
                            .navigationBarsPadding(),
                    )
                    // A1: Barra de búsqueda durante la sesión — suspensión superior(Encima de la lista de mensajes, debajo de las tres islas en la barra superior,Trae tu propia experiencia Surface)
                    if (showInChatSearch) {
                        InChatFindBar(
                            query = inChatQuery,
                            onQueryChange = { inChatQuery = it },
                            matchCount = findMatches.size,
                            currentIndex = if (findMatches.isEmpty()) 0 else currentMatchIndex + 1,
                            onPrev = { jumpToMatch(currentMatchIndex - 1) },
                            onNext = { jumpToMatch(currentMatchIndex + 1) },
                            onClose = {
                                showInChatSearch = false
                                inChatQuery = ""
                                currentMatchIndex = 0
                                viewModel.setTargetMessage(null, null)
                            },
                            modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                // Altura del riel superior aprox. 56dp,La barra de búsqueda está fija debajo de la isla del título.,No más represión a Mishima.
                                .padding(
                                    top = 56.dp,
                                    start = MusePaddings.screen,
                                    end = MusePaddings.screen,
                                    bottom = 4.dp,
                                ),
                        )
                    }
                } // Box

                // F-2: Reenvío entre sesiones — Aparece el cuadro de diálogo de selección de sesión.,Agregue el texto del mensaje después de seleccionar la conversación de destino
                forwardText?.let { text ->
                    val targetSessions = state.sessions.filter { it.id != state.currentSessionId }
                    MuseDialog(
                        onDismissRequest = { forwardText = null },
                        title = stringResource(R.string.chat_forward_dialog_title),
                        content = {
                            Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
                                if (targetSessions.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.chat_forward_no_session),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    targetSessions.take(20).forEach { s ->
                                        Row(
                                            modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(MuseShapes.small)
                                                .clickable {
                                                    forwardText = null
                                                    ioScope.launch {
                                                        val ok = viewModel.forwardMessageToSession(s.id, text)
                                                        MuseToast.show(
                                                            if (ok) {
                                                                forwardDoneFormat.replace("%1\$s", s.title)
                                                            } else {
                                                                forwardNoSessionText
                                                            },
                                                        )
                                                    }
                                                }
                                                .padding(MusePaddings.cardInner),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                imageVector = MuseIcons.swapHorizontal,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(MuseIconSizes.iconMedium),
                                            )
                                            Spacer(Modifier.width(MusePaddings.tightGap))
                                            Text(
                                                text = s.title.ifBlank { stringResource(R.string.session_repo_default_title) },
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        onConfirm = null,
                        dismissText = stringResource(R.string.action_close),
                        onDismiss = { forwardText = null },
                    )
                }

                // A6: mapa de mensajes — CHAT-07: Pertenece a la misma capa de contenido de Scaffold que la ventana emergente de reenvío.,
                // pero debe ser independiente de forwardText,De lo contrario, al chatear normalmente forwardText=null La entrada será eliminada por completo.
                // La zona activa termina encima del campo de entrada.,No presione el borde derecho de la isla de entrada./Enviar clave.
                // fix(oclusión): La lista de mensajes se ha evitado en su totalidad a la derecha por MESSAGE_MAP_RESERVED_WIDTH,
                // Entonces el área activa solo cubrirá el espacio en blanco en el lado derecho de la lista.,No más clics en los botones devoran la fila de acción en la parte inferior del mensaje.
                // v2.7: Superposición de animación de proceso —— La cola del flujo de mensajes.
                // Resulta que es un elemento de la lista. LazyColumn lo recicla cuando sale de la ventana gráfica. Cuando te desplazas hacia arriba, no puedes ver "todavía funciona".
                // Ahora apilado en la parte superior de la lista, cerca de la parte inferior de la ventana gráfica: cuando te desplazas hacia abajo, cae justo debajo del último mensaje.
                if (processIndicatorVisible) {
                    // v2.6.10: Cambiar a tarjeta de proceso de dos capas —— Resumen de un nivel (qué estás haciendo/cuántos pasos/cuánto tiempo te llevó),
                    // Haga clic para ver los pasos detallados. La capa flotante original solo tenía una línea de texto y no se podía ver el progreso ni el consumo de tiempo.
                    //
                    // La posición debe agregarse a la altura de la barra inferior (barra de entrada):content La capa es de pantalla completa,
                    // align(BottomCenter) Se pega en la parte inferior de la pantalla, dejando solo el espacio del mensaje cubierto por el campo de entrada.
                    // Tome la tarjeta de tarea creada más recientemente: es el progreso de la herramienta para la ronda actual.
                    // No es necesario adivinar la identificación del mensaje para evitar vaciar la identificación del mensaje del marcador de posición de la transmisión cuando no coincide con la clave de la tarjeta.
                    val activeTaskCard = state.taskCards.values.maxByOrNull { it.createdAt }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(
                                bottom =
                                innerPadding.calculateBottomPadding() + MusePaddings.messageGap,
                            ),
                    ) {
                        io.zer0.muse.ui.chat.ProcessCard(
                            taskCard = activeTaskCard,
                            progressText = processIndicatorText,
                            isActive = isStreaming || state.isOcrProcessing,
                            startedAt = activeTaskCard?.createdAt,
                        )
                    }
                }

                if (showMessageMap) {
                    MessageMapBar(
                        messages = visibleMessages,
                        listState = listState,
                        messageStartIndex = messageStartIndex,
                        modifier =
                        Modifier
                            .align(Alignment.CenterEnd)
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            // v2.5.0 fix: El borde derecho está retraído 6 dp para evitar la banda de gestos de retorno del sistema Android 10+.
                            // De lo contrario, el sistema seguirá consumiendo los 20 dp más a la derecha de la zona activa de 40 dp y los arrastrará verticalmente.
                            .padding(end = MusePaddings.tightGap + 6.dp),
                    )
                }

                ChatSheetHost(
                    sheetState = sheetState,
                    viewModel = viewModel,
                    knowledgeDocs = knowledgeDocs,
                    speechLauncher = speechLauncher,
                    ioScope = ioScope,
                    onOpenPromptTemplateManager = onOpenPromptTemplateManager,
                )
            } // Scaffold

            // La entrada del navegador se encuentra fuera de la barra superior.
            //
            // Anteriormente se dibujaba dentro de la barra superior, y la isla exterior de la barra superior era una tira entera de vidrio. —— La cápsula está bajo vidrio,
            // Difuminar el fondo resaltará su color, que aparecerá como "un trozo de verde que se ve a través del cristal".
            // Después de pasar a la capa de contenido, ya no está dentro del alcance de la isla de cristal.
            if (currentBrowserManager != null) {
                BrowserStatusCapsule(
                    manager = currentBrowserManager,
                    modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .museSafeTopInsetPadding()
                        // Se debe respetar la altura visual de todo el riel superior, de lo contrario la cápsula caerá en la isla de cristal:
                        // La isla en sí tiene aproximadamente 56 dp de altura (cápsula 48 dp + espacio superior e inferior), solo agregue contentGap
                        // hará que la cápsula se superponga a la isla, mostrando "Cargando…”en el centro de la isla.
                        .padding(top = topBarVisualHeight)
                        .zIndex(10f),
                )
            }

            // P1-1: Modo de pantalla completa de conversación de voz en tiempo real — Ingresa desde el menú superior “Chat de Voz”(La máquina de estado de la conversación de voz está conectada a la página de chat.)
            AnimatedVisibility(
                visible = showVoiceConversation,
                // v2.x: Desvanecimiento desnudo por token(La duración aumenta con la animación del sistema.)
                enter =
                androidx.compose.animation.fadeIn(
                    animationSpec = MuseMotion.tween(MuseAnimation.SLOW_MS),
                ),
                exit =
                androidx.compose.animation.fadeOut(
                    animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS),
                ),
            ) {
                VoiceConversationMode(
                    onClose = { showVoiceConversation = false },
                    viewModel = viewModel,
                )
            }
        } // CompositionLocalProvider(Fuente de cristal a nivel de página)
    } // fondo Box(v1.0.74 Fondo de chat personalizado)
} // ChatScreen

/**
 * F-4: Banner de mensaje fijado — Mostrar el texto del último mensaje de usuario fijado por /pin,Desancla la esquina superior derecha.
 * @param content Texto del mensaje fijado
 * @param onDismiss Haga clic para cancelar la devolución de llamada fijada
 */
@Composable
private fun PinnedMessageBanner(content: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        shape = MuseShapes.medium,
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.contentGap),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.tightGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MuseIcons.pin,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
            Text(
                text = content.replace('\n', ' ').trim(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                Modifier
                    .weight(1f, fill = false)
                    .padding(start = MusePaddings.tightGap),
            )
            MuseCapsuleButton(
                text = stringResource(R.string.action_close),
                onClick = onDismiss,
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
            )
        }
    }
}

/** v2.x: diálogo del comité — Convoca a un grupo de asistentes desde la conversación principal para iniciar una discusión de chat grupal temporal,La conclusión retroalimenta esta conversación. */
@Composable
fun CommitteeDialog(
    assistants: List<io.zer0.muse.data.assistant.AssistantEntity>,
    onDismiss: () -> Unit,
    onConfirm: (memberIds: List<String>, topic: String) -> Unit,
) {
    var selectedIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    var topic by rememberSaveable { mutableStateOf("") }
    val canConfirm = selectedIds.size >= 2 && topic.isNotBlank()
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.chat_committee),
        content = {
            Text(
                text = stringResource(R.string.chat_committee_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(12.dp))
            MuseTextField(
                value = topic,
                onValueChange = { topic = it },
                label = { Text(stringResource(R.string.chat_committee_topic)) },
                singleLine = false,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.chat_committee_members),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                items(assistants, key = { it.id }) { assistant ->
                    val selected = assistant.id in selectedIds
                    MuseChip(
                        selected = selected,
                        onClick = {
                            selectedIds = if (selected) selectedIds - assistant.id else selectedIds + assistant.id
                        },
                        label = assistant.name,
                    )
                }
            }
        },
        confirmText = stringResource(R.string.chat_committee_start),
        onConfirm = { if (canConfirm) onConfirm(selectedIds.toList(), topic.trim()) },
        dismissText = stringResource(R.string.groupchat_cancel),
        onDismiss = onDismiss,
    )
}

// ── v2.6.7(P0-3): Barra superior estilo WeChat integrada en un pequeño teléfono celular ─────────────────────────────

/**
 * Los teléfonos celulares pequeños tienen una barra superior de página de chat incorporada.
 *
 * con página completa/Agent Tab La barra superior de la isla de cristal está deliberadamente separada: lo que los usuarios de juegos de rol esperan de los teléfonos celulares pequeños
 * "Estoy chateando con esta persona en mi teléfono.", entonces aquí usamos barra de color sólido de estilo IM + avatar + nombre,
 * Regrese a la lista de sesiones en el teléfono más pequeño, en lugar de a la aplicación principal.
 *
 * No mostrar el nombre del modelo,token, estado de la herramienta y otra información de depuración——Esas son páginas completas.
 */
@Composable
private fun MiniPhoneChatTopBar(title: String, subtitle: String?, avatarUrl: String?, onBack: (() -> Unit)?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                MuseTopBarIconButton(
                    icon = MuseIcons.arrowLeft,
                    contentDescription = stringResource(R.string.action_back),
                    onClick = onBack,
                )
            } else {
                Spacer(Modifier.width(MuseIconSizes.touchTarget))
            }
            Spacer(Modifier.width(4.dp))
            // Avatar: si hay una imagen, use una imagen. Si no hay imagen, utilice las iniciales del nombre.
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (!avatarUrl.isNullOrBlank()) {
                    coil.compose.AsyncImage(
                        model = avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = title.take(1),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(MuseIconSizes.touchTarget))
        }
    }
}

/** La altura visual del riel superior (isla de vidrio). */
private val topBarVisualHeight = 56.dp
