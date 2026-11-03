@file:Suppress("FunctionNaming")

package io.zer0.muse.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isUnspecified
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.transformer.InternalMarkupSanitizer
import io.zer0.muse.transformer.MoodSkinParser
import io.zer0.muse.ui.artifact.ArtifactCardList
import io.zer0.muse.ui.chat.BranchSelector
import io.zer0.muse.ui.chat.MessageInfoSheet
import io.zer0.muse.ui.chat.QuickAction
import io.zer0.muse.ui.chat.QuickActionButton
import io.zer0.muse.ui.chat.StickerAwareMarkdownBody
import io.zer0.muse.ui.chat.VideoAttachment
import io.zer0.muse.ui.chat.VoiceMessageBubble
import io.zer0.muse.ui.chat.buildHighlightedText
import io.zer0.muse.ui.chat.buildMoodSkinAnnotated
import io.zer0.muse.ui.chat.parseQuotedContent
import io.zer0.muse.ui.common.MusePopover
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.media.ContextMenuItem
import io.zer0.muse.ui.common.media.DesktopContextMenu
import io.zer0.muse.ui.common.media.FullScreenMediaViewer
import io.zer0.muse.ui.common.media.rememberDesktopShortcutsEnabled
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.markdown.CardAction
import io.zer0.muse.ui.markdown.MoodSectionParser
import io.zer0.muse.ui.taskcard.AgentPlan
import io.zer0.muse.ui.taskcard.PlanCard
import io.zer0.muse.ui.theme.BubbleRole
import io.zer0.muse.ui.theme.BubbleSkin
import io.zer0.muse.ui.theme.BubbleSkinResolver
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseAvatarSize
import io.zer0.muse.ui.theme.MuseBubbleStyles
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.glassBorder
import io.zer0.muse.ui.theme.glassEdgeHighlight
import io.zer0.muse.ui.theme.glassFakeSurfaceColor
import io.zer0.muse.ui.theme.tiny
import io.zer0.muse.util.ShareIntentHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun messageCopyText(content: String, reasoning: String? = null): String {
    // v2.6.8: Copie y exporte la exportación unificada. —— Pelar bloques de marcadores internos + colas de estado de tarjeta + efectos en línea.
    //
    // Copie únicamente el texto principal. Anteriormente, pondría mi proceso de pensamiento al final del texto (con un [proceso de pensamiento] marca),
    // Prueba de usuario real "Lo que obtienes cuando haces clic en copiar es pensar"—— Si quieres pensar, hay un botón dedicado a "Copiar pensamiento".
    // Copiar el texto debe ser solo el texto. El texto está vacío (solo pensamientos/fue interrumpido) antes de volver a pensar,
    // Evite que el botón se vuelva no operativo.
    val body = InternalMarkupSanitizer.exportContent(content)
    if (body.isNotBlank()) return body
    return InternalMarkupSanitizer.exportContent(reasoning.orEmpty())
}

/**
 * v2.6.0: Si se debe mostrar la tarjeta de herramientas.
 *
 * Las tarjetas de herramientas ordinarias se controlan mediante el interruptor "Mostrar detalles de llamada de herramienta";show_card es una tarjeta de contenido(información que no es de depuración),
 * El renderizado continúa como de costumbre cuando el interruptor está apagado.
 */
private fun toolPayloadShowing(toolName: String, showToolCallDetails: Boolean): Boolean = showToolCallDetails || toolName == "show_card"

/**
 * Si este mensaje debe representarse como una tarjeta de herramienta separada.
 *
 * Las tres condiciones de veto son independientes entre sí.:herramienta silenciosa(Emoticones/Lista de emoticones), apagar(show_card excepto),
 * y el producto ha sido dibujado por la tarjeta de tarea del mensaje principal(De lo contrario, la misma tarjeta aparecerá dos veces en la pantalla.).
 */
private fun shouldRenderStandaloneToolCard(
    toolInfo: io.zer0.ai.core.ToolCallInfo?,
    isSilentTool: Boolean,
    showToolCallDetails: Boolean,
    cardRenderedByTaskCard: Boolean,
): Boolean {
    if (toolInfo == null || isSilentTool || cardRenderedByTaskCard) return false
    return toolPayloadShowing(toolInfo.toolName, showToolCallDetails)
}

/**
 * unidad de mensaje.
 *
 * - USER: iMessage burbuja gris,Alinear a la derecha,Filete asimétrico(abajo a la derecha 6dp,Otros 20dp),ancho máximo 280dp
 * - ASSISTANT: Sin burbujas de aire,texto de ancho completo,alineado a la izquierda,Extender directamente sobre el color base.
 * - proceso de razonamiento: Doblar la tarjeta sobre el texto(Phase 8.3,Plegado predeterminado,Haga clic para ampliar/cerrar)
 * - Representación de imágenes: Phase 5-G,Si imageUrls no está vacío,Se muestra con Coil AsyncImage
 * - cursor de transmisión(escenario 4):Se agrega un cursor vertical parpadeante al final del texto del mensaje de transmisión de IA.
 * - Menú de pulsación larga(escenario 4):Mantenga presionado el mensaje completo para que aparezca el menú de operación(Editar/regenerar/Traducción/leer en voz alta/Colección)
 * - Botón de acceso directo de IA al final(escenario 4):Se muestra después de que finaliza la transmisión"regenerar"botón icono
 */

// v1.x: etiqueta de marcador de posición del producto(El modelo puede generar directamente [artifact:uuid] Cotización,Pero no hay contenido de etiqueta emparejado.);
// Las capas de renderizado se eliminan uniformemente,Evite mostrar el texto claro del UUID al usuario(Los artefactos reales se muestran en la lista de tarjetas de artefactos.).
private val ARTIFACT_MARKER_RE = Regex("""\[artifact:[0-9a-fA-F-]{36}\]""")

internal enum class MessageBubbleRole {
    USER,
    ASSISTANT,
}

internal data class MessageBubbleLayout(
    val widthFraction: Float,
    val hasOpaqueOuterSurface: Boolean,
)

/** Valores derivados necesarios para la representación de texto (ver [MessageBubble] bubbleText recuerda). */
internal data class BubbleText(
    val content: String,
    val titleText: String?,
    val bodyContent: String,
)


/**
 * Valor derivado requerido para la representación de texto.
 *
 * La función pura se extrae para que pueda cubrirse directamente mediante una única prueba: esta lógica (eliminación de ruta de etiqueta, eliminación de marcas de compresión, segmentación de línea de título,
 * artifact Limpieza de marcas) se escribió previamente en el ensamblaje y solo se pudo confirmar leyendo el código. Un solo cambio puede cambiar silenciosamente el resultado del renderizado.
 */
internal fun deriveBubbleText(
    content: String,
    body: String,
    imageUrls: List<String>,
    imageBase64List: List<String>,
    isStreaming: Boolean,
    isCompressed: Boolean,
): BubbleText {
    val hasImages = imageUrls.isNotEmpty() || imageBase64List.isNotEmpty()
    val hasStickerPaths = if (hasImages) false else extractStickerPaths(content).isNotEmpty()
    val bodyWithoutStickerPaths = if (hasStickerPaths) {
        STICKER_PATH_PATTERN.replace(body, "").trim()
    } else {
        body
    }
    val rawContent = if (isCompressed) {
        body.removePrefix("[COMPRESSED]").trim()
    } else {
        bodyWithoutStickerPaths.ifEmpty { if (hasImages) "" else " " }
    }
    val firstLineEnd = rawContent.indexOf('\n').takeIf { it >= 0 } ?: rawContent.length
    val firstLine = rawContent.substring(0, firstLineEnd)
    val heading = !isStreaming && firstLine.isNotBlank() && firstLine.startsWith("#")
    return BubbleText(
        content = rawContent,
        titleText = if (heading) firstLine else null,
        bodyContent = (if (heading) rawContent.substring(firstLineEnd + 1).trimStart() else rawContent)
            .replace(ARTIFACT_MARKER_RE, ""),
    )
}

internal fun messageBubbleLayout(role: MessageBubbleRole): MessageBubbleLayout = when (role) {
    MessageBubbleRole.USER -> MessageBubbleLayout(widthFraction = 0.85f, hasOpaqueOuterSurface = true)
    MessageBubbleRole.ASSISTANT -> MessageBubbleLayout(widthFraction = 1f, hasOpaqueOuterSurface = false)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
// Las burbujas de mensajes son puntos de reunión a nivel de pantalla.:60+ parámetros,1697 Tanto la complejidad de filas como la ciclomática son“Carga útil completa de un solo mensaje”la forma inherente de,
// Otras funciones importantes del proyecto.(Como por ejemplo ChatScreenComponents / MemoryDialogsDashboard)El mismo calibre está exento por punto.
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun MessageBubble(
    msg: UIMessage,
    isStreaming: Boolean,
    isLastAssistant: Boolean,
    isTranslating: Boolean,
    /**
     * H11: La traducción conserva el texto original. — El texto del mensaje de origen del mensaje de traducción.(La persona que llama analiza el mensaje entrante desde la fuente.).
     * Cuando no es nulo, el texto de traducción se muestra debajo de la burbuja."Ver texto original"doblar,Ampliar para ver el texto original.
     */
    translationSourceContent: String? = null,
    /**
     * v2.4.6: Mapeo de traducción para este mensaje(Nombre del idioma → traducción). Si no está vacío, se mostrará como un bloque expandible debajo del mensaje.,
     * Estilo consistente con MOOD/Think Blocks. Representación de valor de cadena vacía"Traduciendo".
     */
    translations: Map<String, String> = emptyMap(),
    /** v2.4.6: Estado expandido del bloque de traducción(Controlado externamente,Corta páginas sin perderlas). */
    isTranslationExpanded: Boolean? = null,
    /** v2.4.6: Alternar la expansión del bloque de traducción. */
    onToggleTranslationExpanded: () -> Unit = {},
    /**
     * Si se debe hacer la última pregunta del usuario.AI No se pudo generar el informe de errores/Después de un mensaje de asistente vacío, el último mensaje puede ser asistente,
     * Pero este usuario sigue siendo la última pregunta, el énfasis está enrollEl botón debe seguir apareciendo, los usuarios no necesitan eliminarlo y volver a publicarlo.
     */
    isLastUserMessage: Boolean = false,
    // v1.0.53: Índice de sucursales y número total de mensajes actuales(Cambio de múltiples versiones de mensajes del asistente)
    branchIndex: Int = 0,
    branchCount: Int = 1,
    onBranchPrevious: () -> Unit = {},
    onBranchNext: () -> Unit = {},
    onEdit: () -> Unit,
    onQuote: () -> Unit,
    onRegenerate: () -> Unit,
    /** B7-04: Una vez interrumpido el flujo, se continúa generando la entrada. */
    onContinue: (() -> Unit)? = null,
    /** B7-01: Modo de selección múltiple. */
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelection: (() -> Unit)? = null,
    onEnterMultiSelect: (() -> Unit)? = null,
    onTranslate: (String) -> Unit,
    onToggleFavorite: () -> Unit = {},
    // Etapa J: copiar el contenido del mensaje al portapapeles
    onCopyMessage: (String) -> Unit = {},
    // Phase 8.7: TTS leer en voz alta(Solo mensajes de IA)
    isSpeaking: Boolean = false,
    onToggleTts: () -> Unit = {},
    // Phase 8.8: tarjeta de tarea(Solo mensajes de IA,Se muestra cuando se llama a una herramienta)
    taskCard: io.zer0.muse.ui.taskcard.TaskCardData? = null,
    // Phase 10.1: Devolución de llamada de interacción de tarjeta de tarea
    onToggleTaskCardExpand: () -> Unit = {},
    onRetryTaskCardStep: (String) -> Unit = {},
    onCancelTask: () -> Unit = {},
    onToggleTaskCardPause: () -> Unit = {},
    onToggleTaskStepPause: (String) -> Unit = {},
    // v1.25: Mantenga presionado el menú para activar "Delegar al Asistente"
    onDelegate: () -> Unit = {},
    // v0.29 P0-3: Compartir toda la conversación(Exportar a Markdown a través del sistema share sheet)
    onShareSession: () -> Unit = {},
    // F-2: Reenvío entre sesiones — Reenviar este mensaje de texto a otras conversaciones
    onForward: () -> Unit = {},
    // v1.58: Bifurca la conversación de este mensaje(Copiar historial a una nueva sesión)
    onFork: () -> Unit = {},
    // v1.48: Mantenga presionado el menú para eliminar el mensaje.(Los mensajes enviados por error se pueden eliminar desde el menú)
    onDeleteMessage: () -> Unit = {},
    /** U-16: Eliminar este mensaje y todos los mensajes posteriores.(Documentación §4\"Junto con la eliminación posterior\"). */
    onDeleteWithFollowing: () -> Unit = {},
    // v0.29 P0-4: AI La parte inferior del mensaje muestra el nombre del modelo + estimación del token.
    modelName: String? = null,
    // v0.31: Preferencias de comportamiento en el chat(Controlar el estado de ánimo/proceso de pensamiento./token/Nombre del modelo/visualización de marca de tiempo)
    chatPrefs: io.zer0.muse.data.ChatPreferences = io.zer0.muse.data.ChatPreferences(),
    // v0.48: agrupación de mensajes — Continuamente el mismo rol con intervalo de tiempo. < 5 Avatar de compresión de mensajes de minutos + marca de tiempo
    showAvatar: Boolean = true,
    showTimestamp: Boolean = true,
    // v0.48: AI fuente de avatar(Tomado de currentAssistant,null Volver al texto del logotipo de muse)
    assistant: io.zer0.muse.data.assistant.AssistantEntity? = null,
    // v1.43: Lista de tarjetas de productos asociadas al mensaje
    artifacts: List<ArtifactEntity> = emptyList(),
    // v1.43: Devolución de llamada con clic en la tarjeta del producto
    onArtifactClick: (io.zer0.muse.data.artifact.ArtifactEntity) -> Unit = {},
    // Función1: Buscar texto resaltado dentro de una sesión
    highlightText: String? = null,
    // v2.3: debug Resumen de rendimiento del modo(Solo se muestra el último mensaje del asistente en la parte inferior)
    debugInfo: String? = null,
    /** P1 UI: Token barra de estadísticas(Pasado por ChatScreen,Aparece debajo del grupo de botones de acceso directo a mensajes del Asistente). */
    tokenStats: (@Composable () -> Unit)? = null,
    // v1.55: Agent Tarjeta de planificación del flujo de trabajo(Mostrar los últimos planes activos,Desplázate con las novedades)
    agentPlan: AgentPlan? = null,
    // v1.201: Nodo raíz de enlace delegado(Solo mensajes de IA,Se muestra cuando se delega)
    delegationChain: List<io.zer0.muse.tools.DelegationChainTracker.ChainNode>? = null,
    // v2.0.1: Resumen de palabras del mensaje de trabajo de imagen del artículo (análisis de mensajes cruzados: ChatScreen espera con ansias la llamada generate_image más cercana)
    imageGenPrompt: String? = null,
    // v1.45: mood/reasoning El estado plegado se controla externamente.,No se pierde después de cortar páginas
    isMoodExpanded: Boolean? = null,
    isReasoningExpanded: Boolean? = null,
    isReflectionExpanded: Boolean? = null,
    onToggleMoodExpanded: () -> Unit = {},
    onToggleReasoningExpanded: () -> Unit = {},
    onToggleReflectionExpanded: () -> Unit = {},
    // HTML/SVG Devolución de llamada de vista previa de pantalla completa de bloque de código(El parámetro es el código fuente HTML completo.,SVG empacado)
    onHtmlPreview: (String) -> Unit = {},
    /** v1.0.92: Devolución de llamada de devolución de tarjeta(mensaje de guión/Guardar artefacto).
     *
     * v2.6.8: null Simplemente significa "sin canal de retorno", la tarjeta seguirá renderizando y ejecutando su propio canal. JS
     * (JS Desacoplado del puente, consulte RichContentCard).
     */
    onCardAction: ((CardAction) -> Unit)? = null,
    /**
     * v2.6.8: Controlador de devolución de tarjeta para tarjeta de tarea, tarjeta de producto de paso (messageId, action).
     *
     * Es posible que haya tarjetas de productos distintas de show_card en el paso de la tarjeta de tareas (render_data etc),
     * La identidad de su tarjeta es "la identificación del mensaje de la tarjeta de tarea", por lo que se necesita la dimensión messageId.
     * Regresar a cuando no se pasa [onCardAction](La identidad de la tarjeta utiliza esta identificación de mensaje, que es coherente con una única tarjeta en línea).
     */
    onTaskCardCardAction: ((String, CardAction) -> Unit)? = null,
    // Adjunto de vídeo(Solo mensajes de USUARIO):Miniatura del vídeo + duración + icono de reproducción,Haga clic para iniciar el reproductor del sistema con ACTION_VIEW
    videoAttachment: VideoAttachment? = null,
    // v1.138: ayuda visual UI — Análisis en progreso(null=No analizando)
    visionAssistProgress: io.zer0.muse.vision.VisionProgress? = null,
    // v1.138: ayuda visual UI — ¿El mensaje ha sido ayudado visualmente?(mostrar"visión de asistencia"etiqueta)
    visionAssisted: Boolean = false,
    /**
     * Host-owned bubble skin. Null keeps the built-in appearance byte-for-byte; a
     * non-null skin only changes the bubble shell (color/radius/padding/width), never
     * the message content pipeline. Plugin packages may supply this data but cannot
     * inject rendering code.
     */
    bubbleSkin: BubbleSkin? = null,
    showUserAvatar: Boolean = false,
    userAvatarText: String = "U",
    /**
     * v2.4.5 fix: Ya sea para mostrar el proceso de llamada de la herramienta(ajustes → charlar → Mostrar proceso de llamada de herramienta).
     *
     * Anteriormente, este interruptor solo controlaba la lista ToolRunCard / TaskCard / subagente en el lado ChatScreen.,
     * MessageBubble ToolCallCard huérfana interna no está controlada:Cuando el interruptor está apagado, ChatScreen se enciende taskCard
     * conjunto null,En cambio, la representación recae en `toolInfo != null` rama,La tarjeta todavía aparece. La activación ahora está unificada por este parámetro.
     */
    showToolCallDetails: Boolean = true,
    /**
     * Si los artefactos de este mensaje han sido representados por la tarjeta de tarea en el mensaje de ayuda asociado.
     *
     * Cada paso de la tarjeta de tareas dibujará directamente el producto.(show_card tarjetas, imágenes generadas…).
     * El mensaje de visualización de la herramienta es un mensaje independiente.,si vuelve a dibujar,La misma tarjeta aparece dos veces en la pantalla.
     * (Comentarios de los usuarios"Muestre dos tarjetas dentro de una tarjeta y en ese lugar").true Saltar renderizado de producto,
     * Mantenga sólo una identificación de proceso minimalista.
     */
    cardRenderedByTaskCard: Boolean = false,
    /** v2.6: vidrio liquido — true Cuando la burbuja de mensajes del usuario está habilitada(El gas siempre es vidrio falso.). */
    glassActive: Boolean = false,
    /** v2.5.0: Estilo de vidrio y configuración de cubículo. */
    glassConfig: io.zer0.muse.ui.theme.LiquidGlassConfig = io.zer0.muse.ui.theme.LiquidGlassConfig(),
    /**
     * v2.x(comentarios de los usuarios): Si la sesión actual tiene una imagen de fondo.
     *
     * Texto largo cuando hay una imagen de fondo."Sin tarjeta de crédito"Hará que el texto presione directamente sobre la imagen (texto negro debajo del fondo de pantalla oscuro/Contraste de texto blanco
     * Perdido, prueba real del usuario"Si no hay burbujas, no se puede leer con claridad."). Entonces cuando hay una imagen de fondo**Mantenga siempre la base de burbujas**,
     * Sacrificar un poco el ritmo de lectura de artículos largos por la legibilidad; color sólido/Los fondos degradados todavía se procesan como si no se incluyeran artículos largos.
     */
    hasBackgroundImage: Boolean = false,
    /**
     * v2.x(comentarios de los usuarios): Si el fondo de la sesión es oscuro.
     *
     * usado para"El texto se presiona directamente sobre el fondo."(sin fondo de burbuja/Invierte automáticamente el color cuando el texto largo no ingresa a la tarjeta) —— Utilice caracteres claros sobre un fondo oscuro,
     * Utilice caracteres oscuros sobre fondos brillantes para evitar"No se puede ver nada en texto negro debajo de un fondo de pantalla oscuro.". No tiene efecto cuando hay un fondo de burbujas (las burbujas tienen su propio color de fondo).
     */
    backgroundIsDark: Boolean = false,
) {
    val isUser = msg.role == MessageRole.USER
    val outerLayout = messageBubbleLayout(
        if (isUser) MessageBubbleRole.USER else MessageBubbleRole.ASSISTANT,
    )
    // Resolve once per role/theme; null skincortocircuito es null,Mantenga la apariencia predeterminada completamente sin cambios.
    val resolvedSkin = bubbleSkin?.let { skin ->
        val role = if (isUser) BubbleRole.USER else BubbleRole.ASSISTANT
        BubbleSkinResolver.resolve(skin, role, darkTheme = isSystemInDarkTheme())
    }
    // Etapa 4: Estado del menú de pulsación larga(Menú principal + Submenú Traducir idioma)
    // B-AUDIT: Menú de pulsación larga/Eliminar confirmación/información/Respuestas de expresión, etc."estado de interacción instantánea"Cambiar de nuevo remember —
    // rememberSaveable Se guardará por elemento mediante SaveableStateHolder de LazyColumn./recuperar,
    // Hace que se desplace fuera de la pantalla y luego retroceda después de ser destruido.,Menú cerrado/cuadro de diálogo"resurrección"Re-pop.
    // Este tipo de visibilidad instantánea no debería ocurrir entre rotaciones./detrás del escenario/Recuperación rodante.
    var actionSurface by remember { mutableStateOf(MessageActionSurface.Hidden) }
    val showActionMenu = actionSurface != MessageActionSurface.Hidden
    val showLanguageSubmenu = actionSurface == MessageActionSurface.TranslationLanguages
    // v1.0.74 fix: Registre la posición de la burbuja en la ventana.,Mantenga presionado el menú para anclar esto(No hay punto de anclaje antes,Siempre aparece en la esquina superior derecha de la ventana.)
    var actionMenuBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    // El menú de pulsación larga requiere la posición real de presión del dedo,en lugar de un límite fijo para toda la burbuja.
    var actionMenuPressPointLocal by remember { mutableStateOf<Offset?>(null) }
    var actionMenuPointInWindow by remember { mutableStateOf<Offset?>(null) }
    // v1.0.72: Menú de pulsación larga — false=Panel compacto(Cotización/Compartir/Copiar/Seleccionar texto/Más),
    // true=Expandir menú completo(confiar/rama/Traducción/Colección/Editar/Eliminar etc)
    // El submenú de idioma pertenece a la capa de menú completa; de lo contrario, la traducción rápida caerá primero en el menú de clic simplificado.
    val showExtendedMenu = actionSurface == MessageActionSurface.Extended ||
        actionSurface == MessageActionSurface.TranslationLanguages
    // v1.0.72: Modo de selección de texto — Mantenga presionado el elemento del menú"Seleccionar texto"Después de entrar,Admite selección y copia.;
    // Haga clic en la burbuja o mantenga presionada nuevamente para salir
    var textSelectMode by remember { mutableStateOf(false) }
    // P2-13: Menú contextual del botón derecho del escritorio(Solo se muestra la escena de ventana expandida + teclado físico)
    var showDesktopContextMenu by remember { mutableStateOf(false) }
    // v1.60-B: Estado del visor de medios en pantalla completa — Lista de imágenes + índice inicial
    // mediaPreview es de tipo par,Custom Saver es demasiado complejo,mantener remember
    var mediaPreview by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    // v2.6.13: Vista previa del archivo adjunto. Después de hacer clic en el chip adjunto, deberá obtener el original, así que guarde la referencia completa en lugar de la ruta.
    var attachmentPreview by remember { mutableStateOf<io.zer0.ai.core.AttachmentRef?>(null) }
    // v1.48: Eliminar cuadro de diálogo de confirmación de mensaje
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // v1.0.92: Cuadro de diálogo de comentario de mensaje(escribir/Ver/Eliminar)
    var showAnnotateDialog by remember { mutableStateOf(false) }
    // A5: Capa emergente de información del mensaje(Mantenga presionado para expandir el menú/Activador de "Información del mensaje" del menú contextual del escritorio)
    var showInfoSheet by remember { mutableStateOf(false) }
    // Visualización del cursor al final de la transmisión de IA
    val showStreamingCursor = !isUser && isLastAssistant && isStreaming
    // v1.42: El último mensaje de IA en la transmisión desactiva la animación,Evite el retraso causado por cada medida del marco.
    val isAnimating = !(isLastAssistant && isStreaming)
    // Mango de respuesta táctil(Se activa al presionar prolongadamente el interruptor menú/copiar/favorito/regenerar/TTS)
    val hapticFeedback = LocalHapticFeedback.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Cotización Responder:Analizar contenido que comienza con `> ` bloquear,quotedContent Prioridad(para editar/Estado del borrador)
    // v1.42: USER Los mensajes deben analizarse en busca de referencias.,Assistant La omisión de mensajes parseQuotedContent reduce el cálculo.
    val quoteAndBody = remember(msg.content, isUser) {
        if (isUser) parseQuotedContent(msg.content) else null to msg.content
    }
    val parsedQuote = quoteAndBody.first
    val parsedBody = quoteAndBody.second
    // v1.79 (M-B8): La persona que llama garantiza que el contenido no contiene prefijos de referencia.,Aquí no se realizará más deduplicación.
    val quote = msg.quotedContent ?: parsedQuote
    // La última línea de defensa en la capa de visualización: los mensajes históricos o los modelos de transferencia anormales aún pueden dejar etiquetas internas en el contenido.
    // Las burbujas de texto no deben mostrar pensamiento/mod/estado de ánimo como Markdown normal.
    // v2.6.7 Rendimiento: El resultado del análisis del texto se cambia para recordarlo y las 4 copias del estado del medio se elevan a la capa exterior y se leen primero.
    // La antigua forma de escribir requiere volver a ejecutarse cada vez que se reorganiza. lines() Escaneo de tabla completa + stripCardStateFromMessage regular,
    // Y cada vez se crea un nuevo conjunto (lo que hace imposible utilizar el conjunto como clave de recuerdo).
    val mediaImgGenerating = stringResource(R.string.err_chat_img_generating)
    val mediaImgGenerated = stringResource(R.string.err_chat_img_generated)
    val mediaVidGenerating = stringResource(R.string.err_chat_video_generating)
    val mediaVidGenerated = stringResource(R.string.err_chat_video_generated)
    val body =
        remember(
            msg.content,
            msg.quotedContent,
            isUser,
            parsedBody,
            mediaImgGenerating,
            mediaImgGenerated,
            mediaVidGenerating,
            mediaVidGenerated,
        ) {
            val raw = if (isUser) {
                // Los mensajes de los usuarios no se limpian internamente (los usuarios pueden haber publicado etiquetas que contienen <think> código), pero se debe quitar la cola del estado de la tarjeta.
                InternalMarkupSanitizer.stripCardState(if (msg.quotedContent != null) msg.content else parsedBody)
            } else {
                InternalMarkupSanitizer.displayContent(if (msg.quotedContent != null) msg.content else parsedBody)
            }
            val mediaStatusTexts = setOf(mediaImgGenerating, mediaImgGenerated, mediaVidGenerating, mediaVidGenerated)
                .map { it.trim() }
                .toSet()
            if (!isUser && raw.isNotBlank() &&
                raw.lines().all { it.isBlank() || it.trim() in mediaStatusTexts }
            ) {
                ""
            } else {
                raw
            }
        }
    // falló/El asistente vacío restaurado no se puede representar como una barra blanca larga sin contenido.
    // La transmisión de comentarios en espera es manejada por ThinkingBubble independiente de ChatScreen.
    // v2.4.5 fix: toolCallInfo no puede actuar incondicionalmente“Carga visible”——Cuando "Mostrar proceso de llamada de herramienta" está desactivado
    //   Esta carga útil no representa,Sólo el texto en blanco se convertirá en uno.“barra de mensajes vacía”. En este momento sólo está el texto principal./Figura/vídeo/producto/
    //   pensar/estado de ánimo/Reflexión/Cotización/Se requieren tarjetas de tareas para que los mensajes se puedan representar.
    val toolPayloadVisible = showToolCallDetails && msg.toolCallInfo != null
    // v2.6.0: show_card es una tarjeta de contenido(información que no es de depuración),No controlado por el interruptor "Mostrar detalles de llamada de herramienta".
    val isShowCardMessage = msg.toolCallInfo?.toolName == SHOW_CARD_TOOL_NAME
    // v2.6.16: mensaje de voz — Utilice barras de voz para reemplazar las burbujas de texto cuando haya citas de voz.
    //   Sólo cambiar la representación,content Sin mover una palabra:El modelo se basa en el texto para comprender el contexto.,La interfaz no puede borrarlo fácilmente.
    val voiceUri = msg.voiceUri?.takeIf { it.isNotBlank() }
    val hasAssistantPayload = body.isNotBlank() ||
        msg.imageUrls.isNotEmpty() ||
        msg.imageBase64List.isNotEmpty() ||
        msg.videoFileUri != null ||
        voiceUri != null ||
        msg.artifactIds.isNotEmpty() ||
        toolPayloadVisible ||
        isShowCardMessage ||
        msg.reasoning?.isNotBlank() == true ||
        msg.mood?.isNotBlank() == true ||
        msg.reflection?.isNotBlank() == true ||
        msg.ragCitations.isNotEmpty() ||
        taskCard != null ||
        artifacts.isNotEmpty()
    if (!isUser && !hasAssistantPayload) return

    // Los mensajes históricos solo pueden tener contenido pero ningún campo de estado de ánimo, y la capa de visualización aún restaura el estado de ánimo/mod incrustado en un bloque plegable.
    // Durante el período de transmisión, cuando el estado de ánimo aún no se ha almacenado en la biblioteca, se debe realizar la búsqueda de texto completo para cada cuadro del texto y las reflexiones;
    // El resultado está determinado únicamente por estas tres entradas y se almacena en caché en consecuencia.
    val visibleMood = remember(msg.id, msg.mood, msg.content, msg.reasoning) {
        if (isUser) {
            null
        } else {
            msg.mood?.takeIf { it.isNotBlank() }
                ?: InternalMarkupSanitizer.extractMood(msg.content)
                ?: InternalMarkupSanitizer.extractMood(msg.reasoning.orEmpty())
        }
    }
    // P2-13: Interruptor de tecla de acceso directo del escritorio(Expanded Ventana + teclado físico)— Controlar si el menú contextual está habilitado
    val desktopShortcutsEnabled = rememberDesktopShortcutsEnabled()
    // M-UI1: Menú de pulsación larga/Cierre unificado de gestos del menú del escritorio.,Posteriormente adjunto al usuario respectivamente./asistente de burbuja,
    // Evite que toda la fila de la columna quede cubierta al hacer clic en resaltado.
    val bubbleInteractionSource = remember { MutableInteractionSource() }
    val bubbleClickModifier = Modifier
        // Punto de prensa de observación de bypass,Ningún evento de consumo,Mantener pares de enlaces combinados en los que se puede hacer clic/Comportamiento existente para seleccionar texto.
        .pointerInput(msg.id) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                actionMenuPressPointLocal = down.position
                waitForUpOrCancellation()
            }
        }
        .combinedClickable(
            interactionSource = bubbleInteractionSource,
            indication = null,
            // v1.0.72: Pulsación larga en cualquier parte del mensaje(Contiene área de texto,Se ha eliminado la interceptación de SelectionContainer.)menú de operación emergente;
            // Haga clic:Cambiar al modo de selección múltiple/salir del modo de selección de texto
            onClick = {
                if (selectionMode) {
                    onToggleSelection?.invoke()
                } else if (textSelectMode) textSelectMode = false
            },
            onLongClick = {
                MuseHaptics.medium(hapticFeedback)
                if (textSelectMode) {
                    // v1.0.72: En el modo de selección de texto, manténgalo presionado y entréguelo a SelectionContainer para activar el controlador de selección del sistema.,
                    // No abrir el menú de operación(De lo contrario, se eliminará la pulsación larga.,Nunca puedo seleccionar texto)
                    return@combinedClickable
                }
                if (selectionMode) {
                    onToggleSelection?.invoke()
                } else if (desktopShortcutsEnabled) {
                    showDesktopContextMenu = true
                } else {
                    // v1.0.90: El menú se ha cambiado a un panel inferior y ya no se requieren las coordenadas del punto de prensa.
                    actionSurface = MessageActionSurface.Compact
                }
            },
        )
        .onGloballyPositioned { coordinates ->
            if (showActionMenu) {
                val bounds = coordinates.boundsInWindow()
                val pointInWindow = actionMenuPressPointLocal?.let(coordinates::localToWindow)
                if (actionMenuBounds != bounds) actionMenuBounds = bounds
                if (actionMenuPointInWindow != pointInWindow) {
                    actionMenuPointInWindow = pointInWindow
                }
            }
        }

    // Calcular la alineación absoluta según la dirección del diseño actual,Evite los usuarios de RTL/Las burbujas del asistente están invertidas de izquierda a derecha.
    val layoutDirection = LocalLayoutDirection.current
    val isLtr = layoutDirection == LayoutDirection.Ltr
    val horizontalAlignment = if (isUser == isLtr) Alignment.End else Alignment.Start

    // v1.0.74 fix (Auditoría inicial 2.5): Solo registra la ubicación cuando se muestra el menú de pulsación larga.
    // La implementación original escribe onGloballyPositioned durante cada cuadro durante el desplazamiento. MutableState,Burbujas(Contiene MarkdownText)Reorganizar repetidamente durante el desplazamiento.
    // v1.0.90: Después de cambiar el menú al panel inferior, aquí no se necesitan coordenadas de anclaje.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isAnimating) {
                    Modifier.animateContentSize(
                        // v2.6.7: Para cambios dimensionales, utilice GrowEase (inicio rápido, acabado estable) y ya no utilice curvas de desplazamiento.
                        animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS, easing = MuseAnimation.GrowEase),
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = horizontalAlignment,
        ) {
            // B7-01: Indicación de modo de selección múltiple
            if (selectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    horizontalArrangement = if (isUser == isLtr) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(22.dp),
                    ) {
                        Icon(
                            imageVector = if (selected) MuseIcons.check else MuseIcons.circle,
                            // C-21: Semántica específica del indicador de selección múltiple(previamente reutilizado skill_enabled/skill_disabled,TalkBack error semántico)
                            contentDescription = stringResource(
                                if (selected) R.string.chat_msg_selected else R.string.chat_msg_not_selected,
                            ),
                            tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(2.dp),
                        )
                    }
                }
            }
            // v2.7: MOOD reescritura de bloque —— Segmentos estructurados + líneas de resumen localizadas + títulos empaquetados en tarjetas.
            // Originalmente un tono primario Superficie + codificado "MOOD" + cuerpo de texto plano,
            // Los cuatro párrafos de contenido están agrupados en una pila y la lista está completamente sangrada; el título todavía flota fuera de la tarjeta, lo que es inconsistente con la forma de la tarjeta pensante.
            if (chatPrefs.showMoodBlock) {
                visibleMood?.let { mood ->
                    // v1.45: Prefieren estados controlados externos;Utilice el valor predeterminado cuando no esté controlado
                    val moodExpanded = isMoodExpanded ?: chatPrefs.moodExpandedByDefault
                    // La etapa de transmisión no se expande:El texto de la etiqueta todavía se está analizando pieza por pieza.,La expansión forzada hará que todo el manuscrito aparezca en un marco determinado.
                    val showMoodExpanded = if (isLastAssistant && isStreaming) false else moodExpanded
                    val moodSections = remember(mood) { MoodSectionParser.parse(mood) }
                    // v2.6.10: Sigue el color del tema. —— La variante de superficie (gris-blanco) se corrigió originalmente, pero no estaba sincronizada al cambiar el tema.
                    val moodContainerColor =
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    val moodBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    Surface(
                        color = moodContainerColor,
                        shape = MuseShapes.medium,
                        tonalElevation = MuseElevation.none,
                        border = androidx.compose.foundation.BorderStroke(1.dp, moodBorderColor),
                        modifier = Modifier
                            .padding(horizontal = MusePaddings.screen)
                            .widthIn(max = 360.dp)
                            .padding(bottom = 6.dp),
                    ) {
                        Column(modifier = Modifier.padding(MusePaddings.bubbleInner)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleMoodExpanded() }
                                    .padding(vertical = MusePaddings.tinyGap),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (showMoodExpanded) {
                                        stringResource(R.string.chat_mood_label)
                                    } else {
                                        // Plegar con resumen de primera línea: mostrar solo "MOOD"El título equivale a ocultar completamente el contenido.
                                        // Los usuarios no pueden discernir el tono emocional del mensaje.
                                        val summary = moodSections
                                            .firstOrNull { !it.title.isNullOrBlank() || it.lines.isNotEmpty() }
                                            ?.let { section ->
                                                section.lines.firstOrNull()
                                                    ?: section.title.orEmpty()
                                            }
                                            ?.replace('\n', ' ')
                                            ?.trim()
                                            .orEmpty()
                                        if (summary.isBlank()) {
                                            stringResource(R.string.chat_mood_label)
                                        } else {
                                            stringResource(R.string.chat_mood_label) + " · " + summary
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    imageVector = if (showMoodExpanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                                    contentDescription = if (showMoodExpanded) {
                                        stringResource(R.string.chat_mood_collapse_cd)
                                    } else {
                                        stringResource(R.string.chat_mood_expand_cd)
                                    },
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                                )
                            }
                            if (showMoodExpanded) {
                                Spacer(Modifier.height(MusePaddings.tinyGap))
                                moodSections.forEachIndexed { sectionIndex, section ->
                                    if (sectionIndex > 0) {
                                        Spacer(Modifier.height(MusePaddings.tinyGap))
                                    }
                                    section.title?.let { title ->
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    section.lines.forEach { rawLine ->
                                        if (rawLine.isBlank()) return@forEach
                                        val line = MoodSectionParser.toLine(rawLine)
                                        // v2.7: Haga clic para deletrear directamente en el texto. —— Al dibujar puntos de texto independientes, la línea base y el texto principal no estarán alineados y flotarán sobre el comienzo de la línea.
                                        Text(
                                            text = if (line.bullet) "·  ${line.text}" else line.text,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

// Phase 8.3: Tarjetas plegables de proceso de razonamiento(área de pliegue de razonamiento)
            // v0.31: Controlado por el interruptor chatPrefs.showReasoning,El estado de expansión predeterminado está determinado por chatPrefs.reasoningExpandedByDefault
            // v1.45: Cambiar a controlado externamente,Cortar páginas/Permanecer doblado después del fondo
            // v1.118: Cuando está plegado, el título muestra un resumen del contenido del pensamiento.(en lugar de estático"proceso de pensamiento"cuatro palabras),Permita que los usuarios comprendan rápidamente en qué están pensando
            // v2.x: La rueda de herramientas también retiene el bloque de pensamiento; Los usuarios deben poder ver y copiar el razonamiento antes de que se llame a la herramienta después de activar el pensamiento.
            if (chatPrefs.showReasoning) {
                msg.reasoning?.takeIf { it.isNotBlank() }?.let { reasoning ->
                    val reasoningExpanded = isReasoningExpanded ?: chatPrefs.reasoningExpandedByDefault

                    // v2.7: Operandos en el resumen del procedimiento. Las tarjetas de tareas se cuentan como pasos y las llamadas a herramientas aisladas se cuentan como 1;

                    // Las herramientas silenciosas tipo pegatina no muestran tarjetas y no se incluyen aquí.

                    val processOps = run {
                        val silentTool = msg.toolCallInfo?.toolName == "send_sticker" ||

                            msg.toolCallInfo?.toolName == "list_stickers"

                        when {
                            taskCard != null -> taskCard.steps.size

                            msg.toolCallInfo != null && !silentTool -> 1

                            else -> 0
                        }
                    }
                    // v1.0.92: Quitar"Expansión forzada del último elemento de flujo."(Comentarios de los usuarios:La expansión predeterminada no está habilitada, pero Thinking aún se expande automáticamente.);
                    // Piense en bloquear, simplemente presione el valor predeterminado/Cambiar manualmente para expandir,El progreso de la transmisión ahora está representado por un temporizador en tiempo real a la derecha de la fila del título.
                    val showExpanded = reasoningExpanded
                    // v1.0.92: pensar en el temporizador — Actualiza cada segundo durante la transmisión;La duración acumulada de este ciclo de vida de la pantalla se conservará una vez finalizado.
                    // (reciclar/Reiniciar después del corte de página,Sin persistencia;La fuente de datos es el reloj local.,Almacenamiento de mensajes sin contacto).
                    var reasoningElapsedSec by remember(msg.id) { mutableStateOf(0) }
                    LaunchedEffect(isLastAssistant, isStreaming) {
                        if (isLastAssistant && isStreaming) {
                            val startedAt = System.currentTimeMillis()
                            while (true) {
                                reasoningElapsedSec = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
                                delay(1_000L)
                            }
                        }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = MuseShapes.medium,
                        tonalElevation = MuseElevation.low,
                        modifier = Modifier
                            .padding(horizontal = MusePaddings.screen)
                            .widthIn(max = 360.dp)
                            .padding(bottom = 6.dp),
                    ) {
                        Column(modifier = Modifier.padding(MusePaddings.bubbleInner)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleReasoningExpanded() }
                                    .padding(vertical = MusePaddings.tinyGap),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // v1.118: Cuando está plegado, el título muestra un resumen del contenido del pensamiento.,Mostrar cuando esté expandido"proceso de pensamiento"Título
                                // El resumen ocupa unos 40 caracteres antes del razonamiento.(Fusionar saltos de línea),prefijo"pensar · "Fuente de identificación
                                val titleText = when {
                                    showExpanded -> stringResource(R.string.chat_reasoning_title)

                                    processOps > 0 ->

                                        stringResource(R.string.chat_process_thinking) + " · " +

                                            pluralStringResource(R.plurals.chat_process_ops, processOps, processOps)

                                    else -> {
                                        val cleaned = reasoning.replace("\n", " ").trim()

                                        if (cleaned.length > 40) {
                                            stringResource(R.string.chat_thinking_preview, cleaned.take(40) + "…")
                                        } else {
                                            stringResource(R.string.chat_thinking_preview, cleaned)
                                        }
                                    }
                                }
                                Text(
                                    text = titleText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                // v1.0.92: Tiempo de pensamiento a la derecha de la línea del título.(Transmitir en tiempo real/congelar fotograma una vez finalizado)
                                if (reasoningElapsedSec > 0) {
                                    Text(
                                        text = stringResource(R.string.chat_reasoning_elapsed, reasoningElapsedSec),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(MusePaddings.tightGap))
                                }
                                Icon(
                                    imageVector = if (showExpanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                                    // L-MB1: contentDescription más explícito
                                    contentDescription = if (showExpanded) {
                                        stringResource(
                                            R.string.chat_reasoning_collapse_cd,
                                        )
                                    } else {
                                        stringResource(R.string.chat_reasoning_expand_cd)
                                    },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                                )
                            }
                            if (showExpanded) {
                                Spacer(Modifier.height(MusePaddings.tinyGap))
                                SelectionContainer {
                                    Text(
                                        text = reasoning,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // v1.64: bloque de reflexión(reflection — AI Precisión de las propias respuestas./integridad/Autoevaluación del tono.,plegable)
            // Isomórfico al estado de ánimo/bloqueo de razonamiento:Controlado por el interruptor chatPrefs.showReflectionBlock + estado de expansión predeterminado
            if (chatPrefs.showReflectionBlock) {
                msg.reflection?.takeIf { it.isNotBlank() }?.let { reflection ->
                    val reflectionExpanded = isReflectionExpanded ?: chatPrefs.reflectionExpandedByDefault
                    val showReflectionExpanded = (isLastAssistant && isStreaming) || reflectionExpanded
                    Surface(
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f),
                        shape = MuseShapes.medium,
                        tonalElevation = MuseElevation.none,
                        modifier = Modifier
                            .padding(horizontal = MusePaddings.screen)
                            .widthIn(max = 360.dp)
                            .padding(bottom = 6.dp),
                    ) {
                        Column(modifier = Modifier.padding(MusePaddings.bubbleInner)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleReflectionExpanded() }
                                    .padding(vertical = MusePaddings.tinyGap),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // LAYOUT-01: El título del bloque contraído toma el ancho restante (solo un ícono de flecha de ancho fijo a la derecha)
                                Text(
                                    text = stringResource(R.string.chat_reflection_title),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    imageVector = if (showReflectionExpanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                                    // L-MB1: contentDescription más explícito
                                    contentDescription = if (showReflectionExpanded) {
                                        stringResource(
                                            R.string.chat_reflection_collapse_cd,
                                        )
                                    } else {
                                        stringResource(R.string.chat_reflection_expand_cd)
                                    },
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                                )
                            }
                            if (showReflectionExpanded) {
                                Spacer(Modifier.height(MusePaddings.tinyGap))
                                Text(
                                    text = reflection,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (isUser) {
                if (showUserAvatar) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MusePaddings.screen, vertical = 2.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = userAvatarText.take(1).ifBlank { "U" },
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }
                // Mensajes de usuario: Burbuja redondeada de color claro + cola pequeña en la parte inferior derecha.(toma de decisiones D1,Igual que el chat grupal)
                // F-41: El redondeo está controlado por chatPrefs.bubbleRadius(0=cuadrado/8=esquinas redondeadas/20=Grandes esquinas redondeadas/28=cápsula)
                val bubbleRadius = chatPrefs.bubbleRadius.coerceIn(0, 28).toFloat()
                val hasImages = msg.imageBase64List.isNotEmpty()
                // La piel sólo cubre el caparazón.(color/esquinas redondeadas/relleno/ancho máximo);null La piel conserva su estilo original.
                val userSurfaceColor = resolvedSkin?.let { Color(it.style.surfaceArgb) }
                    ?: MuseBubbleStyles.userSurfaceColor()
                // v2.0: Agregue un trazo de color principal a la burbuja del usuario cuando no haya máscara,Evite las burbujas en fondos claros"no puedo ver"
                val userBorder = if (resolvedSkin == null) {
                    androidx.compose.foundation.BorderStroke(1.dp, MuseBubbleStyles.userBorderColor())
                } else {
                    null
                }
                val userShape = resolvedSkin?.style?.radiusDp?.dp?.let { RoundedCornerShape(it) }
                    ?: MuseBubbleStyles.userBubbleShape(bubbleRadius)
                val userContentColor = resolvedSkin?.let { Color(it.style.contentArgb) }
                    ?: MuseBubbleStyles.userContentColor()
                // v2.0: Los mensajes de usuario mantienen burbujas compactas a la derecha —— El cambio de banner solo funciona en artículos largos del asistente.,
                // Las oraciones cortas del usuario ya no ocupan toda la línea en el lado izquierdo de la publicación.;Adaptar el ancho al contenido,El tamaño máximo no supera el 78% de la pantalla.
                val userWidthFraction = resolvedSkin?.style?.maxWidthFraction ?: outerLayout.widthFraction
                val userMaxWidthDp = with(androidx.compose.ui.platform.LocalConfiguration.current) {
                    (screenWidthDp * userWidthFraction).dp
                }
                val userPadding = resolvedSkin?.style?.let {
                    PaddingValues(horizontal = it.paddingHorizontalDp.dp, vertical = it.paddingVerticalDp.dp)
                } ?: MusePaddings.bubbleInner
                // v2.5.2: burbujas de vidrio liquido —— Utilice "vidrio falso" en su lugar(sin desenfoque):
                // Si hay docenas de burbujas en una pantalla, cada una estará muy borrosa y arrastrará el desplazamiento hacia abajo.(Agujero negro de rendimiento),
                // Cambiar a fondo degradado + resaltado superior + trazo,Visualmente similar pero de bajo costo.
                val userGlassActive = glassActive && resolvedSkin == null && glassConfig.enabled
                val userGlassModifier = if (userGlassActive) {
                    Modifier
                        .clip(userShape)
                        .background(glassFakeSurfaceColor(MuseBubbleStyles.userSurfaceColor(), glassConfig))
                        .glassEdgeHighlight(userShape, glassConfig)
                        .glassBorder(userShape, glassConfig)
                } else {
                    Modifier
                }
                Surface(
                    color = if (userGlassActive) Color.Transparent else userSurfaceColor,
                    shape = userShape,
                    border = userBorder,
                    // v1.0.29: Quitar sombra,Evite las burbujas de colores claros en colores oscuros./Aparecen extraños bordes de sombras sobre fondos claros.
                    modifier = userGlassModifier
                        .then(bubbleClickModifier)
                        .padding(horizontal = MusePaddings.tinyGap, vertical = 3.dp),
                ) {
                    Column(
                        modifier = Modifier
                            // v2.0: Adaptar el ancho al contenido(Hasta 78% del ancho de pantalla),Los mensajes cortos ya no se extienden en pancartas largas.
                            .widthIn(max = userMaxWidthDp)
                            .padding(userPadding),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // Cotización Responder:Mostrar bloque de cotización en la parte superior de los mensajes de los usuarios
                        quote?.let {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = MuseShapes.small,
                            ) {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(MusePaddings.contentGap),
                                )
                            }
                        }
                        // Adjunto de vídeo:Miniatura + duración + icono de reproducción,Haga clic para iniciar el reproductor del sistema con ACTION_VIEW
                        // Degradado a marcador de posición oscuro + ícono de video cuando falta la miniatura,Mantenga una alta coherencia para evitar fluctuaciones en el diseño
                        videoAttachment?.let { va ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp)
                                    .clip(MuseShapes.medium)
                                    .clickable {
                                        // Llame al reproductor del sistema para reproducir video.;Aviso de brindis cuando no se puede procesar ninguna solicitud
                                        runCatching {
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(va.uri, "video/*")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                        }.onFailure { e ->
                                            MuseToast.show(
                                                context.getString(R.string.chat_video_open_failed, e.message ?: ""),
                                            )
                                        }
                                    },
                            ) {
                                val thumb = va.thumbnail
                                if (!thumb.isNullOrBlank()) {
                                    SmartImage(
                                        model = "data:image/jpeg;base64,$thumb",
                                        contentDescription = stringResource(R.string.chat_user_video_cd),
                                        modifier = Modifier.fillMaxWidth(),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(160.dp)
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = MuseIcons.video,
                                            contentDescription = stringResource(R.string.chat_user_video_cd),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(MuseIconSizes.iconEmpty),
                                        )
                                    }
                                }
                                // icono de reproducción central(scrim El fondo semitransparente mejora el contraste.)
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = MuseIcons.play,
                                        contentDescription = stringResource(R.string.chat_video_play_cd),
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(MuseIconSizes.iconLarge),
                                    )
                                }
                                // Etiqueta de duración en la esquina inferior derecha(Texto blanco sobre fondo negro,Consistente con el estilo del álbum del sistema.)
                                Surface(
                                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f),
                                    shape = MuseShapes.tiny,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(MusePaddings.labelVerticalGap),
                                ) {
                                    Text(
                                        text = MessageBubbleFormatters.formatVideoDuration(va.durationMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(
                                            horizontal = MusePaddings.labelVerticalGap,
                                            vertical = MusePaddings.tinyGap,
                                        ),
                                    )
                                }
                            }
                        }
                        if (hasImages) {
                            // v1.60-B: También se puede hacer clic en las imágenes del usuario para ampliarlas e ingresar al visor de medios en pantalla completa.
                            val userImageUris = msg.imageBase64List.map { "data:image/jpeg;base64,$it" }
                            userImageUris.forEachIndexed { index, uri ->
                                SmartImage(
                                    model = uri,
                                    contentDescription = stringResource(R.string.chat_user_image_cd),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp)
                                        .clip(MuseShapes.medium)
                                        .clickable { mediaPreview = userImageUris to index },
                                )
                            }
                            // v1.138: Etiquetas de ayuda visual — Mostrar la imagen de abajo"visión de asistencia"Estado
                            // analizando:mostrar progreso"visión de asistencia · analizando x/y"
                            // Completado:mostrar"visión de asistencia · Analizado"(éxito)o"visión de asistencia · falló"
                            val showVisionLabel = visionAssistProgress?.isActive == true || visionAssisted
                            if (showVisionLabel) {
                                val (labelText, labelColor, labelIcon) = when {
                                    visionAssistProgress?.isActive == true -> Triple(
                                        stringResource(
                                            R.string.vision_assist_analyzing,
                                            visionAssistProgress.index,
                                            visionAssistProgress.total,
                                        ),
                                        MaterialTheme.colorScheme.tertiary,
                                        MuseIcons.eye,
                                    )
                                    visionAssisted -> Triple(
                                        stringResource(R.string.vision_assist_done),
                                        MaterialTheme.colorScheme.primary,
                                        MuseIcons.check,
                                    )
                                    else -> Triple(
                                        stringResource(R.string.vision_assist_label),
                                        MaterialTheme.colorScheme.outline,
                                        MuseIcons.eye,
                                    )
                                }
                                Surface(
                                    color = labelColor.copy(alpha = 0.12f),
                                    shape = MuseShapes.tiny,
                                    modifier = Modifier.padding(top = 2.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(MusePaddings.chipInnerLoose),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
                                    ) {
                                        if (visionAssistProgress?.isActive == true) {
                                            MuseSpinner(
                                                size = MusePaddings.itemGap,
                                                strokeWidth = 1.5.dp,
                                                color = labelColor,
                                            )
                                        } else {
                                            Icon(
                                                imageVector = labelIcon,
                                                contentDescription = null,
                                                tint = labelColor,
                                                modifier = Modifier.size(MusePaddings.itemGap),
                                            )
                                        }
                                        Text(
                                            text = labelText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = labelColor,
                                        )
                                    }
                                }
                            }
                        }
                        val userText = body.ifEmpty { if (hasImages || videoAttachment != null || voiceUri != null) "" else " " }
                        // v2.6.13: El chip adjunto se coloca encima del texto. La línea "guardada en el espacio de trabajo" del texto es una referencia para el modelo.
                        // Al usuario sólo le indica que hay un archivo, pero no cuál es, qué tamaño tiene o si se puede abrir.
                        if (msg.attachments.isNotEmpty()) {
                            MessageAttachmentChips(
                                attachments = msg.attachments,
                                onOpen = { attachmentPreview = it },
                            )
                        }
                        if (voiceUri != null) {
                            // v2.6.16: mensaje de voz — La barra de voz reemplaza la burbuja de texto(El contenido permanece en msg.content)
                            VoiceMessageBubble(
                                voiceUri = voiceUri,
                                durationMs = msg.voiceDurationMs,
                                isFromUser = true,
                            )
                        } else if (userText.isNotBlank()) {
                            Text(
                                text = userText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = userContentColor,
                            )
                        }
                    }
                }
                // v0.48: USER La marca de tiempo se muestra alineada a la derecha debajo de la burbuja del mensaje.(recibir showTimestamp && chatPrefs.showTimestamp controlar)
                if (showTimestamp && chatPrefs.showTimestamp) {
                    Text(
                        text = formatMessageTime(msg.createdAt, use24Hour = chatPrefs.use24Hour),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 2.dp, end = MusePaddings.tinyGap),
                    )
                }
            } else {
                // v1.0.54: Mensaje de asistente vacío(content Vacío + sin imagen/tarjeta/pensar/Reflexión/emociones)no renderizar —
                //   Actualización del mensaje de ocupación de la rueda de herramientas. El asistente no está actualizado toolCalls(hengwei null),No puedo juzgar por la rueda de herramientas;
                //   Reservado durante la transmisión(ThinkingIndicator Es normal generar retroalimentación),despues del final/Los mensajes de tiempo y espacio se ocultan durante la carga.
                // C-22: Tarjeta de producto incluida(artifactIds)/tarjeta de tarea(por taskCardInfo)El mensaje no se oculta aunque el texto esté vacío.
                val isToolRoundPlaceholder = !isStreaming &&
                    body.isBlank() &&
                    msg.imageUrls.isEmpty() && msg.imageBase64List.isEmpty() &&
                    msg.artifactIds.isEmpty() &&
                    msg.toolCallInfo == null &&
                    voiceUri == null &&
                    msg.reasoning.isNullOrBlank() &&
                    msg.mood.isNullOrBlank() &&
                    msg.reflection.isNullOrBlank()
                // v1.0.80: Novedades puras sobre herramientas(content vacío + herramienta/tarjeta de tarea)No incluye fondo de burbujas.
                // Comentarios de los usuarios:Durante la citometría de flujo, se expone un círculo de fondo de burbuja profundo debajo de la tarjeta de herramientas.,Como una interfaz de usuario vacía que primero ocupa espacio.
                // Tarjeta de herramientas(ToolCallCard/TaskCard)Viene con superficie + borde.,No es necesario agregar otra capa de burbujas.
                // v2.0: Complementa dos categorías principales de mensajes sin texto. — ①si toolCalls(Ninguno toolCallInfo/taskCard)rueda de herramientas;
                // ②solo razonamiento“pensar”mensaje. Originalmente no alcanzaron esta condición.,Los bloqueos del pensamiento vienen con su propia casi invisibilidad
                // surfaceVariant abajo,El fondo de la burbuja solo expone una franja gris de 48 píxeles de ancho en el lado izquierdo.(medido“escombros grises”);
                // Igual que la rueda de herramientas,El contenido con su propio bloque de fondo no necesita tener burbujas.
                val isPureToolBubble = body.isBlank() &&
                    msg.imageUrls.isEmpty() && msg.imageBase64List.isEmpty() &&
                    msg.artifactIds.isEmpty() && msg.videoFileUri.isNullOrBlank() &&
                    (
                        msg.toolCallInfo != null || taskCard != null ||
                            !msg.toolCalls.isNullOrEmpty() || msg.reasoning?.isNotBlank() == true
                        )
                // v0.48: AI avatar — Compresión continua de avatares para mensajes con el mismo rol al agrupar mensajes(showAvatar=false salto de tiempo)
                if (showAvatar && !isToolRoundPlaceholder) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(outerLayout.widthFraction)
                            .padding(
                                start = MusePaddings.screen,
                                end = MusePaddings.screen,
                                bottom = MusePaddings.tinyGap,
                            ),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AssistantAvatar(
                            assistant = assistant ?: io.zer0.muse.data.assistant.AssistantEntity(
                                id = "default",
                                name = "Muse",
                            ),
                            avatarSize = MuseAvatarSize.inline,
                        )
                        // v2.6.10: Los nombres están desacoplados de las marcas de tiempo.
                        // El nombre es el ancla de identidad (las escenas de múltiples asistentes pueden distinguir quién está hablando), controlada por showAssistantName, activada de forma predeterminada;
                        // La marca de tiempo es metainformación y está controlada únicamente por showTimestamp.
                        val showName = chatPrefs.showAssistantName
                        val showTime = showTimestamp && chatPrefs.showTimestamp
                        if (showName || showTime) {
                            Spacer(Modifier.width(MusePaddings.contentGap))
                            val nameText = assistant?.name?.takeIf { it.isNotBlank() } ?: "Muse"
                            val timeText =
                                formatMessageTime(msg.createdAt, use24Hour = chatPrefs.use24Hour)
                            Text(
                                text =
                                when {
                                    showName && showTime -> "$nameText · $timeText"
                                    showName -> nameText
                                    else -> timeText
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
                // D1: Mensaje del asistente = tarjeta de luz(Calibre de chat grupal,cola inferior izquierda);El mensaje de herramienta pura viene con tarjeta sin fondo de burbuja.(v1.0.80).
                if (!isToolRoundPlaceholder) {
                    Column(
                        // Mensaje del asistente:capa de contenido;Funda para mensajes de herramientas no puras con base de tarjeta de color claro.
                        modifier = Modifier
                            .fillMaxWidth(if (chatPrefs.bubbleFullWidth) 1f else outerLayout.widthFraction)
                            // v2.0.1: Respira a izquierda y derecha — Los bloques de fondo, como las tarjetas de asistente y los bloques de pensamiento, ya no llenan la pantalla (comentarios de los usuarios)."demasiado lleno, lleno de gente").
                            .padding(horizontal = MusePaddings.screen)
                            .then(bubbleClickModifier),
                    ) {
                        val assistantSurfaceColor = resolvedSkin?.let { Color(it.style.surfaceArgb) }
                            ?: MuseBubbleStyles.assistantSurfaceColor()
                        val assistantShape = resolvedSkin?.style?.radiusDp?.dp?.let { RoundedCornerShape(it) }
                            ?: MuseBubbleStyles.assistantBubbleShape()
                        // v2.x: Calibración de texto largo — tipo estructural/respuesta muy larga"Sin tarjeta de crédito":contenedor de concha cancelar(color de fondo/Accidente cerebrovascular/esquinas redondeadas/70% Eliminar todos los límites de ancho),
                        // El contenido cae directamente en el borde de respiración, diseño de ancho completo(Ritmo de lectura de contenidos largos). En el modo piel, prevalecerá la piel.
                        // v2.x(comentarios de los usuarios): Si hay una imagen de fondo, no escribas artículos largos y no se incluirán en la tarjeta. —— De lo contrario, el texto se presionará directamente sobre la imagen.
                        // El contraste se pierde bajo fondos de pantalla oscuros ("Sin burbujas, no puedes ver las palabras."). Solo colores sólidos/Se aplica el fondo degradado.
                        val isLongFormBubble = !isPureToolBubble && resolvedSkin == null &&
                            !hasBackgroundImage && isLongFormContent(body)
                        // v2.x(comentarios de los usuarios): Cuando el texto principal se presiona directamente sobre el fondo (el texto largo no se bloqueará) = Sin fondo de burbujas)
                        // Invertir colores automáticamente —— Utilice caracteres claros sobre fondos oscuros y caracteres oscuros sobre fondos claros para evitar"El texto negro no se puede ver debajo del fondo de pantalla oscuro".
                        // No tendrá efecto cuando haya una base de burbujas (el color de fondo lo determina el tema para garantizar el contraste).
                        val bodyTextColor = if (isLongFormBubble && backgroundIsDark) {
                            androidx.compose.ui.graphics.Color.White.copy(alpha = 0.92f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                        Column(
                            modifier = if (isPureToolBubble) {
                                Modifier.padding(PaddingValues(0.dp))
                            } else if (isLongFormBubble) {
                                // v2.x: Artículo largo“Sin tarjeta de crédito” — Sin color de fondo/Sin derrame cerebral/Sin esquinas redondeadas/Sin ancho máximo,Fila recta de ancho completo.
                                //
                                // Comentarios de los usuarios (el artículo largo se desborda desde el borde derecho)/La frontera desaparece):
                                // valor antiguo horizontal = 0.dp —— El texto queda realmente pegado al borde de la pantalla.
                                // En una sesión con una imagen de fondo parece“Sin burbujas, el texto sale volando de la pantalla.”.
                                // En su lugar, mantenga un margen mínimo de lectura (no igual al margen interior de la tarjeta, pero no permita que las palabras se peguen al borde de la pantalla).
                                Modifier.padding(
                                    PaddingValues(
                                        horizontal = MusePaddings.tightGap,
                                        vertical = MusePaddings.contentGap,
                                    ),
                                )
                            } else {
                                Modifier
                                    .clip(assistantShape)
                                    .background(assistantSurfaceColor)
                                    .then(
                                        if (resolvedSkin == null) {
                                            // v2.0: Agregue un trazo a la tarjeta del asistente cuando no haya máscara,Evite que las tarjetas de colores claros se vuelvan casi invisibles sobre fondos de colores claros.
                                            Modifier.border(1.dp, MuseBubbleStyles.assistantBorderColor(), assistantShape)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .then(
                                        if (chatPrefs.bubbleFullWidth) {
                                            // Modo banner: elimina el límite superior del ancho máximo de burbujas
                                            Modifier
                                        } else {
                                            Modifier.widthIn(max = MuseBubbleStyles.maxBubbleWidth())
                                        },
                                    )
                                    .padding(
                                        if (chatPrefs.bubbleFullWidth) {
                                            PaddingValues(
                                                // v1.0.92: La barandilla también conserva el borde respirable. — antigua implementación horizontal=0 Se adjuntará directamente a la pantalla.
                                                // (Comentarios de los usuarios"No hay ningún espacio a la izquierda y a la derecha.");Alineado a 8 dp del banner del lado del usuario.
                                                horizontal = MusePaddings.contentGap,
                                                vertical = MusePaddings.cardInner.calculateTopPadding(),
                                            )
                                        } else {
                                            MusePaddings.cardInner
                                        },
                                    )
                            },
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            // transmisión/estado de pensamiento:AI Se muestra la parte superior del mensaje."pensando…"Con puntos verdes pulsantes
                            if (isLastAssistant && isStreaming && !isPureToolBubble) {
                                ThinkingIndicator()
                            }
                            // v2.6.16: tira de mensajes de voz — con fotos/Transmisión de vídeo en la misma capa,No participa en la representación de texto.
                            voiceUri?.let { uri ->
                                VoiceMessageBubble(
                                    voiceUri = uri,
                                    durationMs = msg.voiceDurationMs,
                                    isFromUser = false,
                                )
                            }
                            // Phase 5-G / Phase 8.6: Renderizar la imagen generada(URL o base64 data URI)
                            // fuente de visualización unificada:prioridad imageUrls,Evite Géminis mientras tenga imageUrls(data URI) Representación repetida al usar imageBase64List
                            // v1.95: Escanee también la ruta absoluta del paquete de emoticonos en el contenido(filesDir/stickers/...),Generado por la herramienta send_sticker
                            val displayImageUris = remember(msg.imageUrls, msg.imageBase64List, msg.content, isLastAssistant, isStreaming) {
                                val fromUrls = if (msg.imageUrls.isNotEmpty()) {
                                    msg.imageUrls
                                } else {
                                    msg.imageBase64List.map { "data:image/png;base64,$it" }
                                }
                                // v1.100: Saltar el escaneo de rutas de emoticones durante la transmisión(Los gastos generales de regularización aumentan con el contenido),
                                // Después de la transmisión(no isStreaming)Solo escanea,Alineado con la estrategia alternativa de MarkdownText
                                if (isLastAssistant && isStreaming) {
                                    fromUrls
                                } else {
                                    // Markdown Imagen: El analizador ![alt](url) Bórralo del texto,
                                    // Si no lo sacas aquí, toda la línea desaparecerá. Lo mismo ocurre con la ruta del paquete de emoticones.
                                    val markdownImages = extractMarkdownImageUrls(msg.content)
                                    val stickerUris = extractStickerPaths(msg.content).map { "file://$it" }
                                    fromUrls + markdownImages + stickerUris
                                }
                            }
                            if (displayImageUris.size > 1) {
                                // v2.0.1: Cuadrícula de múltiples gráficos — Organizadas en dos columnas (las imágenes individuales todavía se muestran en la imagen grande a continuación y varias imágenes ya no se apilan verticalmente).
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    displayImageUris.chunked(2).forEachIndexed { rowIndex, rowUris ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            rowUris.forEachIndexed { colIndex, imageUri ->
                                                val globalIndex = rowIndex * 2 + colIndex
                                                GeneratedImageCard(
                                                    imageUri = imageUri,
                                                    onPreview = { mediaPreview = displayImageUris to globalIndex },
                                                    onSave = {
                                                        scope.launch {
                                                            resultOf {
                                                                saveImageToGallery(context, imageUri)
                                                            }.onSuccess { path ->
                                                                MuseToast.show(context.getString(R.string.chat_image_saved_toast, path))
                                                            }.onError { msg, t ->
                                                                MuseToast.show(
                                                                    context.getString(R.string.chat_image_save_failed_toast, msg),
                                                                )
                                                            }
                                                        }
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                    compact = true,
                                                )
                                            }
                                            if (rowUris.size == 1) {
                                                Box(Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            } else {
                                // v2.0.1: tarjeta de trabajo — Resumen de palabras rápidas + guardar/compartir (solo se compartirán recursos locales)
                                val genPromptSummary = remember(msg.toolCalls, msg.toolCallInfo) {
                                    val genArgs = msg.toolCalls?.firstOrNull { it.name == "generate_image" }?.arguments
                                        ?: msg.toolCallInfo?.takeIf { it.toolName == "generate_image" }?.arguments
                                    genArgs
                                        ?.let { args ->
                                            runCatching { org.json.JSONObject(args).optString("prompt") }.getOrNull()
                                        }
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { if (it.length > 60) it.take(60) + "…" else it }
                                }
                                displayImageUris.forEachIndexed { index, imageUri ->
                                    GeneratedImageCard(
                                        imageUri = imageUri,
                                        onPreview = { mediaPreview = displayImageUris to index },
                                        onSave = {
                                            scope.launch {
                                                resultOf {
                                                    saveImageToGallery(context, imageUri)
                                                }.onSuccess { path ->
                                                    // M-MB2: Utilice MuseToast en lugar de nativo Toast,Mantenga el tema consistente
                                                    MuseToast.show(context.getString(R.string.chat_image_saved_toast, path))
                                                }.onError { msg, t ->
                                                    MuseToast.show(context.getString(R.string.chat_image_save_failed_toast, msg))
                                                }
                                            }
                                        },
                                        promptSummary = genPromptSummary ?: imageGenPrompt,
                                        onShare = if (imageUri.startsWith("file") || imageUri.startsWith("content")) {
                                            {
                                                runCatching {
                                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                        type = "image/*"
                                                        putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(imageUri))
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    io.zer0.muse.util.ShareIntentHelper.startChooserSafely(
                                                        context = context,
                                                        shareIntent = shareIntent,
                                                        chooserTitle = context.getString(R.string.action_share),
                                                    )
                                                }
                                            }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                            // Reparación de auditoría (S-02): Tarjeta de resultados de generación de video(generate_video escrito videoFileUri)
                            // Anteriormente, UIMessage solo existía en la memoria y no se representaba.,Reiniciar/El vídeo se pierde permanentemente después de cambiar de página.;
                            // Ahora acércate con las novedades.(v88 Migrar)y renderizar aquí,Haga clic para abrir el reproductor del sistema.
                            val generatedVideoUri = msg.videoFileUri
                            if (!generatedVideoUri.isNullOrBlank()) {
                                AssistantVideoCard(videoUri = generatedVideoUri)
                            }
                            // v1.112 (C1): Diseño dividido de la cápsula de llamadas de herramientas y lista de tareas
                            // estado expandido:TaskCard Apilado verticalmente en todo su ancho(La lista de pasos necesita espacio),ToolCallCard abajo
                            val toolInfo = msg.toolCallInfo
                            // v1.0.53: herramienta silenciosa(send_sticker)— Los emoticonos son interacciones divertidas,No mostrar tarjeta de llamada de herramienta,
                            //   evitar"herramienta de llamada"Consejos para arruinar la experiencia de las pegatinas. La imagen de la pegatina se muestra como de costumbre.
                            // v1.0.54: list_stickers Mismo silencio(Listar memes es un trabajo interno).
                            val isSilentTool = toolInfo?.toolName == "send_sticker" ||
                                toolInfo?.toolName == "list_stickers"
                            if (taskCard != null) {
                                // perder peso:La tarjeta de tareas ha tratado cada llamada de herramienta como“pasos”mostrar;
                                // Anteriormente, cuando los dos coexistían, TaskCard + ToolCallCard se representaban al mismo tiempo.,La misma operación se muestra dos veces.,
                                // Varias rondas de operaciones se unen en largas cadenas de cartas. Ahora solo conserve las tarjetas de tareas.;Las llamadas a herramientas aisladas se bloquean por separado.
                                // v2.6.8: La tarjeta de producto en el paso de la tarjeta de tarea debe pasarse al puente.
                                // Las tarjetas sin onClick solo se pueden ver:No hay respuesta al hacer clic y una pulsación prolongada degrada la selección de palabras del sistema.
                                // Cuando la persona que llama no proporciona un controlador para la dimensión messageId,Volver a la dimensión de este mensaje
                                // Procesador(La identidad de la tarjeta todavía usa mensaje id,Mismo espacio que una sola tarjeta en línea).
                                val taskCardCardAction = remember(onTaskCardCardAction, onCardAction) {
                                    onTaskCardCardAction ?: onCardAction?.let { handler ->
                                        { _: String, action: CardAction -> handler(action) }
                                    }
                                }
                                io.zer0.muse.ui.taskcard.TaskCard(
                                    data = taskCard,
                                    onToggleExpand = onToggleTaskCardExpand,
                                    onRetryStep = onRetryTaskCardStep,
                                    onCancel = onCancelTask,
                                    onTogglePause = onToggleTaskCardPause,
                                    onToggleStepPause = onToggleTaskStepPause,
                                    delegationChain = delegationChain,
                                    onCardAction = taskCardCardAction,
                                )
                            } else if (
                                toolInfo != null &&
                                shouldRenderStandaloneToolCard(
                                    toolInfo,
                                    isSilentTool,
                                    showToolCallDetails,
                                    cardRenderedByTaskCard,
                                )
                            ) {
                                // Llamadas a herramientas huérfanas(No en ninguna tarea)——Mantenga la visualización de una sola tarjeta
                                // v2.6.0: show_card Las tarjetas se procesan incluso si "Mostrar detalles de llamada de herramienta" está desactivado(es contenido).
                                ToolCallCard(
                                    toolName = toolInfo.toolName,
                                    arguments = toolInfo.arguments,
                                    result = toolInfo.result,
                                    isSuccess = toolInfo.isSuccess,
                                    onCardAction = onCardAction,
                                    // v2.6.7: Utilice la identificación del mensaje como identidad de la tarjeta —— Naturalmente vincula la ubicación de la conversación,
                                    // Estable durante los reinicios y más adecuado como referencia que el c_xxx único generado cuando se ejecuta show_card.
                                    cardId = msg.id.toString(),
                                    modifier = Modifier.widthIn(max = 360.dp),
                                )
                            } else {
                                // v5: etiqueta comprimida — SYSTEM El mensaje y el contenido se basan en [COMPRESSED] Mostrado al principio
                                val isCompressed = msg.role == io.zer0.ai.core.MessageRole.SYSTEM && msg.content.startsWith("[COMPRESSED]")
                                if (isCompressed) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
                                        modifier = Modifier.padding(bottom = MusePaddings.tinyGap),
                                    ) {
                                        Icon(
                                            imageVector = MuseIcons.gitMerge,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Text(
                                            text = stringResource(R.string.msg_compressed),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }
                                // contenido del texto(Markdown renderizado);Los mensajes de imagen pueden contener contenido y sintaxis de imagen de rebajas,Doble renderizado para evitar la cavitación.
                                // v1.0.54: send_sticker El contenido solo tiene una ruta absoluta de etiqueta.(Se utiliza para renderizar imágenes.),Eliminación al renderizar texto,
                                //   evitar"camino desconcertante"Se muestra como texto.
                                // Esta sección solía volver a ejecutarse cada vez que se reorganizaba: la ruta del paquete de emoticones findAll, dos reemplazos regulares de sección completa (ruta de pegatinas,artifact marca),
                                // División de la línea de título. Durante la transmisión, cada fragmento desencadenará una reorganización y el costo de los mensajes largos (especialmente aquellos con el código fuente de la tarjeta) aumenta linealmente.
                                // Los resultados solo constan de la lista msg.content/image / isStreaming / body / isCompressed Determinar, caché por ellos.
                                val bubbleText = remember(
                                    msg.content,
                                    msg.imageUrls,
                                    msg.imageBase64List,
                                    isStreaming,
                                    body,
                                    isCompressed,
                                ) {
                                    deriveBubbleText(
                                        content = msg.content,
                                        body = body,
                                        imageUrls = msg.imageUrls,
                                        imageBase64List = msg.imageBase64List,
                                        isStreaming = isStreaming,
                                        isCompressed = isCompressed,
                                    )
                                }
                                val content = bubbleText.content
                                val titleText = bubbleText.titleText
                                val bodyContent = bubbleText.bodyContent
                                if (voiceUri == null && content.isNotBlank()) {
                                    // Markdown línea de título
                                    titleText?.let {
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        if (bodyContent.isNotBlank()) {
                                            Spacer(Modifier.height(MusePaddings.tinyGap))
                                        }
                                    }
                                    // Cotización Responder:AI Bloque de cotización mostrado en la parte superior del mensaje(Compatible con contenido etiquetado con citas)
                                    quote?.let {
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            shape = MuseShapes.small,
                                        ) {
                                            Box {
                                                Text(
                                                    text = it,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 3,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(MusePaddings.contentGap),
                                                )
                                                val bgColor = MaterialTheme.colorScheme.surface
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(
                                                            Brush.verticalGradient(
                                                                0.5f to Color.Transparent,
                                                                1.0f to bgColor,
                                                            ),
                                                        ),
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(6.dp))
                                    }
                                    // v1.24: El texto ya no está doblado en su totalidad.,Solo bloques de código Markdown/El bloque de composición es plegable por dentro.
                                    // v1.42: Deshabilitar la detección de clics en enlaces para LinkableText en streaming,Evite que pointerInput se reconstruya repetidamente a medida que cambia el contenido.
                                    // v1.52: Envuelto con SelectionContainer,Admite una pulsación prolongada para seleccionar parte del texto y copiarlo(Cuando no está transmitiendo).
                                    //        La selección no está habilitada en streaming,evitar con el cursor/Conflicto de actualización de contenido;
                                    //        SelectionContainer Se consumirá el gesto de pulsación larga sobre el texto.(para la selección),
                                    //        El Clickable combinado de la columna principal activa el menú de acciones solo en áreas que no son de texto.
                                    // v1.0.53: tarjeta de datos(```card JSON)renderizar primero,el resto se va markdown
                                    val dataCard = remember(bodyContent) {
                                        if (io.zer0.muse.ui.markdown.DataCardParser.containsCardBlock(bodyContent)) {
                                            io.zer0.muse.ui.markdown.DataCardParser.parse(bodyContent)
                                        } else {
                                            null
                                        }
                                    }
                                    // v2.0: Diseño de texto — La escala del tamaño de fuente y el espaciado entre caracteres siguen las preferencias del usuario(El valor predeterminado es consistente con la versión anterior.)
                                    val bodyBase = MaterialTheme.typography.bodyMedium
                                    val bodyScale = chatPrefs.messageFontScale.coerceIn(0.85f, 1.3f)
                                    val bodyStyle = bodyBase.copy(
                                        fontSize = bodyBase.fontSize * bodyScale,
                                        lineHeight = bodyBase.lineHeight.let { if (it.isUnspecified) it else it * bodyScale },
                                        letterSpacing = chatPrefs.messageLetterSpacingEm.coerceIn(-0.02f, 0.1f).em,
                                    )
                                    val markdownContent = @Composable {
                                        // La determinación y eliminación de efectos especiales en línea se ejecutan aquí en cada fotograma; los dos resultados solo están determinados por bodyContent y se almacenan en caché de acuerdo con él.
                                        val hasInlineEffect = remember(bodyContent) {
                                            MoodSkinParser.containsInlineEffect(bodyContent)
                                        }
                                        val bodyWithoutInlineEffect = remember(bodyContent, hasInlineEffect) {
                                            if (hasInlineEffect) bodyContent else MoodSkinParser.stripInlineEffects(bodyContent)
                                        }
                                        // v1.79 (H-B3): manejo defensivo citationUrls,MarkdownText Interno debe garantizar [N] No cruces la línea
                                        val safeCitationUrls = msg.citationUrls ?: emptyList()
                                        if (highlightText != null && bodyContent.contains(highlightText, ignoreCase = true)) {
                                            androidx.compose.material3.Text(
                                                text = buildHighlightedText(bodyContent, highlightText),
                                                style = bodyStyle,
                                                color = bodyTextColor,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        } else if (hasInlineEffect) {
                                            Text(
                                                text = buildMoodSkinAnnotated(bodyContent),
                                                style = bodyStyle,
                                                color = bodyTextColor,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        } else {
                                            // v2.x: Etiquetas de memes [[sticker:Clasificación]] → Tarjeta con imagen pura;Paso directo sin gastos generales cuando no está marcado MarkdownText
                                            StickerAwareMarkdownBody(
                                                text = bodyWithoutInlineEffect,
                                                stickerSeed = msg.id.hashCode().toLong(),
                                                style = bodyStyle,
                                                color = bodyTextColor,
                                                modifier = Modifier.fillMaxWidth(),
                                                citationUrls = safeCitationUrls,
                                                isStreaming = isLastAssistant && isStreaming,
                                                // v1.0.72: La detección de enlaces está deshabilitada en el modo de selección o en el modo de selección de texto.:
                                                // El modo de selección de texto debe estar deshabilitado.,De lo contrario, el pointerInput de LinkableText intercepta una pulsación larga,
                                                // El controlador de selección de texto del sistema nunca se activa(Comentarios de los usuarios"La selección de texto falla por completo")
                                                disableLinks = selectionMode || textSelectMode,
                                                onHtmlPreview = onHtmlPreview,
                                                onCardAction = onCardAction,
                                                // v2.6.13: Se debe pasar la tarjeta de identidad —— en el texto ```html / ```svg
                                                // La tarjeta depende de él para leer sus propios datos vinculantes y devolver la fuente con la fuente.
                                                cardId = msg.id.toString(),
                                                // v1.0.72: Pulsación larga en el área sin enlace → Menú de pulsación larga de burbuja pop(Solucionar el problema de no respuesta al presionar prolongadamente el mensaje)
                                                onLongPressOutside = {
                                                    textSelectMode = false
                                                    actionSurface = MessageActionSurface.Compact
                                                },
                                            )
                                        }
                                    }
                                    if (dataCard != null) {
                                        io.zer0.muse.ui.markdown.DataCardRenderer(card = dataCard)
                                    } else if (textSelectMode) {
                                        // v1.0.72: solo"Seleccionar texto"Utilice SelectionContainer en modo para admitir la selección y la copia;
                                        // No es necesario en otros casos(De lo contrario, SelectionContainer interceptará una pulsación larga,El menú de operación no puede aparecer)
                                        SelectionContainer { markdownContent() }
                                    } else {
                                        markdownContent()
                                    }
                                }
                                // Función3: Tarjeta de vista previa de enlace(Sólo cuando no está transmitiendo,Evite volver a rastrear causado por incrementos de transmisión)
                                if (!isStreaming) {
                                    val linkPreviews = rememberLinkPreviews(bodyContent)
                                    if (linkPreviews.isNotEmpty()) {
                                        linkPreviews.forEach { preview ->
                                            LinkPreviewCard(preview = preview)
                                        }
                                    }
                                }
                                // Etapa 4: Cursor de transmisión(Al final de la transmisión de IA,Mostrar una línea vertical parpadeante alineada a la izquierda debajo del texto)
                                if (showStreamingCursor) {
                                    StreamingCursor(
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                                // Función2: TTS Reproductor de mensajes de voz(Solo mensajes de IA que no se transmiten,Y se muestra cuando está hablando)
                                if (!isUser && !isStreaming && isSpeaking) {
                                    TtsAudioPlayer(
                                        modifier = Modifier.padding(top = MusePaddings.contentGap),
                                    )
                                }
                                // v2.3: debug Resumen de rendimiento del modo(Opcional)
                                if (!isStreaming && debugInfo != null) {
                                    Text(
                                        text = debugInfo,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                                // v1.43: Lista de tarjetas de productos(código/Documentación/HTML/SVG/Imágenes, etc.)
                                if (artifacts.isNotEmpty()) {
                                    ArtifactCardList(
                                        artifacts = artifacts,
                                        onArtifactClick = onArtifactClick,
                                        modifier = Modifier.padding(top = MusePaddings.contentGap),
                                    )
                                }
                                // v1.133: RAG Lista de chips de referencia(Haga clic para ampliar snippet)
                                if (!isUser && !isStreaming && msg.ragCitations.isNotEmpty()) {
                                    RagCitationChips(
                                        citations = msg.ragCitations,
                                        modifier = Modifier.padding(top = MusePaddings.contentGap),
                                    )
                                }
                            } // closes inner else (no taskCard/toolInfo)
                            // v1.55: Agent Las tarjetas de planificación del flujo de trabajo se desplazan con mensajes,en lugar de estar fijado al final de la lista de mensajes
                            if (
                                agentPlan != null &&
                                taskCard == null &&
                                msg.toolCallInfo == null &&
                                msg.toolCalls.isNullOrEmpty()
                            ) {
                                PlanCard(plan = agentPlan)
                            }
                        } // closes AI bubble Surface Column
                    }
                } // closes AI bubble Surface
            }

            // v1.0.53: Botón de acceso directo en la parte inferior del mensaje de usuario — Copiar + reintentar(solo el ultimo)
            if (isUser && msg.content.isNotEmpty() && !isStreaming && !isTranslating) {
                Row(
                    modifier = Modifier.padding(top = 2.dp, end = MusePaddings.tinyGap),
                    // v2.x(comentarios de los usuarios): Apretar el espacio
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MuseTactileButton(
                        icon = MuseIcons.copy,
                        onClick = {
                            MuseHaptics.light(hapticFeedback)
                            onCopyMessage(messageCopyText(msg.content, msg.reasoning))
                        },
                        contentDescription = stringResource(R.string.action_copy),
                        tint = MaterialTheme.colorScheme.outline,
                        size = MuseIconSizes.touchTarget,
                        iconSize = MuseIconSizes.iconSmall,
                    )
                    if (isLastUserMessage) {
                        MuseTactileButton(
                            icon = MuseIcons.refresh,
                            onClick = {
                                MuseHaptics.light(hapticFeedback)
                                onRegenerate()
                            },
                            contentDescription = stringResource(R.string.action_retry),
                            tint = MaterialTheme.colorScheme.outline,
                            size = MuseIconSizes.touchTarget,
                            iconSize = MuseIconSizes.iconSmall,
                        )
                    }
                    // v2.4.6: Editar botón de acceso directo — Puede editar directamente este mensaje de usuario sin ingresar al menú de pulsación larga.
                    MuseTactileButton(
                        icon = MuseIcons.edit,
                        onClick = {
                            MuseHaptics.light(hapticFeedback)
                            onEdit()
                        },
                        contentDescription = stringResource(R.string.action_edit),
                        tint = MaterialTheme.colorScheme.outline,
                        size = MuseIconSizes.touchTarget,
                        iconSize = MuseIconSizes.iconSmall,
                    )
                    // v2.6.x(Comentarios de los usuarios): boton borrar — Anteriormente los mensajes de los usuarios solo se podían copiar/Inténtalo de nuevo/Editar,
                    // Eliminar solo se puede encontrar en el menú de pulsación larga,Los usuarios pensaron que "no puedes borrar lo que publicaste".
                    // La capa de datos deleteMessage ya admite la eliminación en cascada de las respuestas de su asistente.,Reutilizar directamente aquí
                    // Ventana emergente de confirmación igual que el mensaje del asistente(Sólo este artículo / junto con el siguiente).
                    MuseTactileButton(
                        icon = MuseIcons.trash,
                        onClick = {
                            MuseHaptics.light(hapticFeedback)
                            showDeleteConfirm = true
                        },
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.outline,
                        size = MuseIconSizes.touchTarget,
                        iconSize = MuseIconSizes.iconSmall,
                    )
                }
            }

            // v1.138 / v1.0.53: Botón de acceso directo en la parte inferior del mensaje del Asistente — Copiar/Traducción/Compartir/Reconstrucción + conmutador de sucursales
            // El botón de traducción reutiliza el submenú de idioma del menú de pulsación larga(showActionMenu + showLanguageSubmenu)
            // El botón de compartir utiliza la hoja de compartir del sistema para compartir el contenido de un solo mensaje.
            // v2.x(comentarios de los usuarios): Primero calcule si esta fila mostrará al menos un botón ——
            // De lo contrario, cuando el usuario pone todos quickAction* Todos los interruptores están apagados y no hay“continuar generando”cuando,
            // La condición externa sigue siendo cierta porque isLastAssistant dejará un marcador de posición de Fila vacío.
            val truncatedMarker = stringResource(R.string.err_reply_truncated)
            val willContinueShow = isLastAssistant && onContinue != null &&
                (
                    msg.content.contains("[interrumpido]") ||
                        (truncatedMarker.isNotBlank() && msg.content.contains(truncatedMarker))
                    )
            val hasContent = msg.content.isNotEmpty()
            val hasReasoning = !msg.reasoning.isNullOrBlank()
            // v2.6.0: Quick Action Order está impulsado por QuickActionOrder (lista de activación ordenada);
            // lista vacía = No configurado, recurre a la antigua lógica de seis interruptores (compatible con la configuración original, escrita en orden una vez editada en la página de configuración).
            val configuredActions: List<QuickAction> =
                if (chatPrefs.quickActionOrder.isNotEmpty()) {
                    chatPrefs.quickActionOrder.mapNotNull { QuickAction.fromId(it) }
                } else {
                    // viejo interruptor → Lista de acciones (mantener el antiguo comportamiento predeterminado: pensar en mostrar fuerza cuando no hay cuerpo)
                    buildList {
                        if (chatPrefs.quickActionCopy) add(QuickAction.COPY)
                        if (chatPrefs.quickActionTranslate) add(QuickAction.TRANSLATE)
                        if (chatPrefs.quickActionShare) add(QuickAction.SHARE)
                        if (chatPrefs.quickActionReasoning || !hasContent) add(QuickAction.REASONING)
                        if (chatPrefs.quickActionRegenerate) add(QuickAction.REGENERATE)
                        if (chatPrefs.quickActionDelete) add(QuickAction.DELETE)
                    }
                }

            // Las condiciones de visualización para cada acción en este mensaje (consistentes con las condiciones originales del menú de seis interruptores + pulsación larga):
            fun QuickAction.visibleNow(): Boolean = when (this) {
                QuickAction.COPY -> hasContent
                QuickAction.REASONING -> hasReasoning && (chatPrefs.quickActionReasoning || !hasContent)
                QuickAction.TRANSLATE, QuickAction.SHARE -> hasContent
                QuickAction.REGENERATE -> isLastAssistant
                QuickAction.DELETE -> isUser || hasContent || hasReasoning
                QuickAction.QUOTE -> true
                QuickAction.ANNOTATE -> true
                QuickAction.DELEGATE -> true
                QuickAction.FORK -> true
                QuickAction.MULTI_SELECT -> true
                QuickAction.INFO -> true
                QuickAction.FAVORITE -> hasContent || hasReasoning
                QuickAction.TTS -> !isUser && hasContent
                QuickAction.EDIT -> isUser
                QuickAction.FORWARD -> true
            }
            val rowActions = configuredActions.filter { it.visibleNow() }
            val hasAnyAssistantQuickAction = rowActions.isNotEmpty() || willContinueShow
            // Visibilidad de fila: no es mensaje de usuario, al menos un botón, no transmite, no traduce.
            // v2.6.1(comentarios de los usuarios): tarjeta de tarea/tarjeta de ejecución/Los mensajes de "tarjeta pura", como las tarjetas de planificación, ya no se pueden organizar rápidamente con mensajes.
            // Estas acciones (cita/Comentario/confiar/rama/opción múltiple/información/adelante…) solo para mensajes de usuario con contenido sustancial
            // Los mensajes del asistente tienen sentido. Anteriormente, se determinaba que sólo se excluían los mensajes de los usuarios. El texto estaba vacío pero el mensaje del asistente con la tarjeta representada aún estaría
            // Ven aquí; los seis modificadores predeterminados no son visibles porque se especifica hasContent y la mayoría de las acciones agregadas por los usuarios son incondicionales
            // mostrar (visibleNow -> true), por lo que todos aparecen debajo de la tarjeta.
            val hasCardPayload =
                taskCard != null || msg.toolCallInfo != null ||
                    !msg.toolCalls.isNullOrEmpty() || agentPlan != null
            val isCardOnlyMessage = body.isBlank() && hasCardPayload
            val showQuickActionRow = !isUser && !isStreaming && !isTranslating && !isCardOnlyMessage
            if (
                showQuickActionRow &&
                hasAnyAssistantQuickAction
            ) {
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    // v2.x(comentarios de los usuarios): El espacio entre botones se reduce de apretadoGap a 2dp —— El espacio original es demasiado amplio y parece disperso.
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    rowActions.forEach { action ->
                        QuickActionButton(
                            action = action,
                            msg = msg,
                            isSpeaking = isSpeaking,
                            favorite = msg.favorite,
                            onCopyMessage = onCopyMessage,
                            onRegenerate = {
                                MuseHaptics.light(hapticFeedback)
                                onRegenerate()
                            },
                            onDelete = { showDeleteConfirm = true },
                            onQuote = onQuote,
                            onAnnotate = { showAnnotateDialog = true },
                            onDelegate = onDelegate,
                            onFork = onFork,
                            onMultiSelect = { onEnterMultiSelect?.invoke() },
                            onInfo = { showInfoSheet = true },
                            onToggleFavorite = {
                                MuseHaptics.light(hapticFeedback)
                                onToggleFavorite()
                            },
                            onToggleTts = onToggleTts,
                            onEdit = onEdit,
                            onForward = onForward,
                            onTranslate = {
                                MuseHaptics.light(hapticFeedback)
                                // v1.0.88 (R-1): Vaya directamente al submenú de idioma (omitiendo el panel compacto).
                                actionSurface = MessageActionSurface.Extended
                                actionSurface = MessageActionSurface.TranslationLanguages
                            },
                            onShare = {
                                MuseHaptics.light(hapticFeedback)
                                scope.launch {
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        // v2.6.8: El uso compartido también se exporta y el JSON del estado de la tarjeta no se puede enviar a otras personas.
                                        putExtra(Intent.EXTRA_TEXT, InternalMarkupSanitizer.exportContent(msg.content))
                                    }
                                    ShareIntentHelper.startChooserSafely(context, sendIntent)
                                }
                            },
                        )
                    }
                    // B7-04: continuar generando(Solo el último mensaje de ayuda que se interrumpió, no en el grupo configurable——La entrada de recuperación no puede bloquearse con un interruptor.)
                    if (willContinueShow) {
                        MuseTactileButton(
                            icon = MuseIcons.play,
                            onClick = {
                                MuseHaptics.light(hapticFeedback)
                                onContinue()
                            },
                            contentDescription = stringResource(R.string.chat_asr_tip_confirm),
                            tint = MaterialTheme.colorScheme.primary,
                            size = MuseIconSizes.touchTarget,
                            iconSize = MuseIconSizes.iconSmall,
                        )
                    }
                    // v2.6.0: "Más" al final de la línea—— Entrada fija, ingresa al panel completo de pulsación larga (incluidas todas las acciones antes de ingresar a la piscina).
                    // Cuando se eliminan todas las acciones, los usuarios aún pueden acceder a todas las operaciones desde aquí.
                    MuseTactileButton(
                        icon = MuseIcons.moreVertical,
                        onClick = {
                            MuseHaptics.light(hapticFeedback)
                            actionSurface = MessageActionSurface.Extended
                        },
                        contentDescription = stringResource(R.string.action_more),
                        tint = MaterialTheme.colorScheme.outline,
                        size = MuseIconSizes.touchTarget,
                        iconSize = MuseIconSizes.iconSmall,
                    )
                }
                if (!isUser && chatPrefs.showModelName && !modelName.isNullOrBlank()) {
                    Text(
                        text = modelName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    )
                }
                // P1 UI: Token Fila de estadísticas independientes(Debajo del botón de acceso directo,No más filas de botones abarrotadas)
                if (!isUser && tokenStats != null) {
                    tokenStats()
                }
                // El conmutador de rama está en una línea separada,Evite que las pantallas estrechas se salgan de la pantalla
                if (branchCount > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BranchSelector(
                            currentIndex = branchIndex,
                            totalCount = branchCount,
                            onPrevious = onBranchPrevious,
                            onNext = onBranchNext,
                        )
                    }
                }
            }

            // Cambiador de versión de mensajes de usuario: Editar/La versión de la pregunta del usuario generada al reintentar.
            if (isUser && branchCount > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = if (isUser == isLtr) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BranchSelector(
                        currentIndex = branchIndex,
                        totalCount = branchCount,
                        onPrevious = onBranchPrevious,
                        onNext = onBranchNext,
                    )
                }
            }

            // v1.0.92: Cuadro de diálogo de comentario de mensaje(escribir/Ver/Eliminar)
            if (showAnnotateDialog) {
                io.zer0.muse.annotation.AnnotationStore.attach(context)
                var annotateInput by remember { mutableStateOf("") }
                io.zer0.muse.ui.common.feedback.MuseDialog(
                    onDismissRequest = { showAnnotateDialog = false },
                    title = stringResource(R.string.message_annotate_title),
                    content = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            val existing = io.zer0.muse.annotation.AnnotationStore.ofMessage(msg.id.toString())
                            existing.forEach { an ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = an.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        // LAYOUT-01: Se ha ampliado el texto de la anotación y se ha añadido el límite superior de 2 líneas:
                                        // Los comentarios largos ya no mantienen las líneas del cuadro de diálogo verticalmente y conservan el contenido legible.
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    // LAYOUT-01: La etiqueta de acción de fin de línea solo se limita a líneas y no toma el ancho (el texto de la anotación ya está weight(1f))
                                    Text(
                                        text = stringResource(R.string.skill_delete),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .clickable { io.zer0.muse.annotation.AnnotationStore.remove(an.id) }
                                            .padding(horizontal = 4.dp),
                                    )
                                }
                            }
                            io.zer0.muse.ui.common.form.MuseTextField(
                                value = annotateInput,
                                onValueChange = { annotateInput = it },
                                label = { Text(stringResource(R.string.message_annotate_hint)) },
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    },
                    confirmText = stringResource(R.string.channel_save),
                    onConfirm = {
                        io.zer0.muse.annotation.AnnotationStore.add(msg.id.toString(), "", annotateInput)
                        showAnnotateDialog = false
                    },
                    dismissText = stringResource(R.string.settings_common_cancel),
                    onDismiss = { showAnnotateDialog = false },
                )
            }

            // Etapa 4: menú de pulsación larga
            // v1.0.72: tipo de capa flotante Popup — Posición cerca del mensaje(Haga clic donde aparecer,Deslizamiento no inferior),
            // Las tarjetas contienen citas/Copiar/Seleccionar texto/Compartir/Editar(Solo mensajes de usuario)/Más;
            // "Más"Expandir menú completo(confiar/rama/Traducción/Colección/Eliminar etc,Se mantiene la lógica original).
            if (showActionMenu) {
                if (!showExtendedMenu) {
                    // ── Operación de mensaje Tarjeta emergente(burbuja de mensaje de anclaje) ──
                    // v1.0.74 fix: Antes no había ningún ancla para padres,El menú siempre aparece en la esquina superior derecha de la ventana.(Lejos del dedo).
                    // Cambiar al posicionamiento según la posición de la ventana de burbuja:Pega el borde derecho de la burbuja en el borde derecho del menú.,El borde superior está 8 dp por encima de la burbuja.
                    // E4 (H8): La lógica de posicionamiento converge hacia lo universal. MusePopover(Ancho 220dp, espaciado 8dp);
                    // Punto de anclaje aún no medido(Zero)Cuando el componente degenera en la esquina superior izquierda de la pantalla,,Consistente con el comportamiento de degradación original.
                    MusePopover(
                        anchorBounds = actionMenuBounds,
                        gapDp = 8,
                        onDismiss = {
                            actionSurface = MessageActionSurface.Hidden
                            actionSurface = MessageActionSurface.Hidden
                        },
                        anchorPointInWindow = actionMenuPointInWindow,
                    ) {
                        TelegramActionCard(
                            isUser = isUser,
                            onQuote = {
                                actionSurface = MessageActionSurface.Hidden
                                actionSurface = MessageActionSurface.Hidden
                                onQuote()
                            },
                            onCopy = {
                                actionSurface = MessageActionSurface.Hidden
                                actionSurface = MessageActionSurface.Hidden
                                MuseHaptics.light(hapticFeedback)
                                onCopyMessage(messageCopyText(msg.content, msg.reasoning))
                            },
                            onSelectText = {
                                // v1.0.72: "Seleccionar texto"= Ingrese al modo de selección de texto(Mantenga presionado el texto para activar el controlador de selección del sistema)
                                actionSurface = MessageActionSurface.Hidden
                                actionSurface = MessageActionSurface.Hidden
                                textSelectMode = true
                                MuseToast.show(context.getString(R.string.chat_select_text_hint))
                            },
                            onShare = {
                                actionSurface = MessageActionSurface.Hidden
                                actionSurface = MessageActionSurface.Hidden
                                onShareSession()
                            },
                            onForward = {
                                actionSurface = MessageActionSurface.Hidden
                                actionSurface = MessageActionSurface.Hidden
                                onForward()
                            },
                            onEdit = {
                                actionSurface = MessageActionSurface.Hidden
                                actionSurface = MessageActionSurface.Hidden
                                onEdit()
                            },
                            onMore = {
                                actionSurface = MessageActionSurface.Extended
                            },
                        )
                    }
                } else {
                    MuseBottomSheet(
                        onDismissRequest = {
                            actionSurface = MessageActionSurface.Hidden
                            actionSurface = MessageActionSurface.Hidden
                            actionSurface = MessageActionSurface.Hidden
                        },
                        bottomContentSpacing = MusePaddings.contentGap,
                        content = {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                if (showLanguageSubmenu) {
                                    // Traducir submenú de idioma(Desde el menú principal"Traducción"disparador de elemento)
                                    ActionMenuItem(
                                        icon = MuseIcons.arrowDown,
                                        text = stringResource(R.string.action_back),
                                        contentDescription = stringResource(R.string.action_back),
                                        onClick = { actionSurface = MessageActionSurface.Hidden },
                                    )
                                    TranslationLanguages.forEach { lang ->
                                        ActionMenuItem(
                                            icon = MuseIcons.languages,
                                            text = lang,
                                            contentDescription = stringResource(R.string.chat_translate_to_cd, lang),
                                            onClick = {
                                                actionSurface = MessageActionSurface.Hidden
                                                actionSurface = MessageActionSurface.Hidden
                                                onTranslate(lang)
                                            },
                                        )
                                    }
                                } else {
                                    // M-UI2: El menú de pulsación larga del mensaje del asistente está estrictamente optimizado para cotizar/confiar/rama,
                                    // Los mensajes de usuario conservan el menú completo original.(Editar/Traducción/Colección/Copiar/Compartir/Eliminar etc).
                                    ActionMenuItem(
                                        icon = MuseIcons.reply,
                                        text = stringResource(R.string.message_action_quote),
                                        contentDescription = stringResource(R.string.message_action_quote),
                                        onClick = {
                                            actionSurface = MessageActionSurface.Hidden
                                            onQuote()
                                        },
                                    )
                                    // v1.0.92: Comentario — escribe a este mensaje/Ver anotaciones locales(Con comentarios existentes)
                                    val annotCount = io.zer0.muse.annotation.AnnotationStore
                                        .ofMessage(msg.id.toString()).size
                                    ActionMenuItem(
                                        icon = MuseIcons.edit,
                                        text = if (annotCount > 0) {
                                            stringResource(R.string.message_action_annotate) + " ($annotCount)"
                                        } else {
                                            stringResource(R.string.message_action_annotate)
                                        },
                                        contentDescription = stringResource(R.string.message_action_annotate),
                                        onClick = {
                                            actionSurface = MessageActionSurface.Hidden
                                            showAnnotateDialog = true
                                        },
                                    )
                                    ActionMenuItem(
                                        icon = MuseIcons.users,
                                        text = stringResource(R.string.chat_delegate_action),
                                        contentDescription = stringResource(R.string.chat_delegate_action),
                                        onClick = {
                                            actionSurface = MessageActionSurface.Hidden
                                            onDelegate()
                                        },
                                    )
                                    ActionMenuItem(
                                        icon = MuseIcons.gitMerge,
                                        text = stringResource(R.string.chat_fork_action),
                                        contentDescription = stringResource(R.string.chat_fork_action),
                                        onClick = {
                                            actionSurface = MessageActionSurface.Hidden
                                            onFork()
                                        },
                                    )
                                    ActionMenuItem(
                                        icon = MuseIcons.square,
                                        text = stringResource(R.string.chat_select_messages),
                                        contentDescription = stringResource(R.string.chat_select_messages),
                                        onClick = {
                                            actionSurface = MessageActionSurface.Hidden
                                            onEnterMultiSelect?.invoke()
                                        },
                                    )
                                    // A5: Capa emergente de información del mensaje(modelo/Consume mucho tiempo/Token Dosis)
                                    ActionMenuItem(
                                        icon = MuseIcons.info,
                                        text = stringResource(R.string.msg_info_title),
                                        contentDescription = stringResource(R.string.msg_info_title),
                                        onClick = {
                                            actionSurface = MessageActionSurface.Hidden
                                            actionSurface = MessageActionSurface.Hidden
                                            showInfoSheet = true
                                        },
                                    )
                                    if (msg.content.isNotBlank()) {
                                        ActionMenuItem(
                                            icon = MuseIcons.copy,
                                            text = stringResource(R.string.action_copy),
                                            contentDescription = stringResource(R.string.action_copy),
                                            onClick = {
                                                actionSurface = MessageActionSurface.Hidden
                                                MuseHaptics.light(hapticFeedback)
                                                onCopyMessage(messageCopyText(msg.content, msg.reasoning))
                                            },
                                        )
                                    }
                                    if (msg.content.isNotBlank() || msg.reasoning?.isNotBlank() == true) {
                                        ActionMenuItem(
                                            // §11-8: El ícono tiene solo una estrella sólida (MuseIcons No hay un contorno de estrella en
                                            // Este proyecto no agrega un ícono para este estado), seleccione/Los no seleccionados se distinguen por el tinte:
                                            // Recogido = Color de acento del tema, no recogido. = Colores neutros.
                                            icon = MuseIcons.star,
                                            tint = favoriteStarTint(msg.favorite),
                                            text = if (msg.favorite) {
                                                stringResource(
                                                    R.string.chat_favorite_remove,
                                                )
                                            } else {
                                                stringResource(R.string.chat_favorite_add)
                                            },
                                            contentDescription = if (msg.favorite) {
                                                stringResource(
                                                    R.string.chat_favorite_remove,
                                                )
                                            } else {
                                                stringResource(R.string.chat_favorite_add)
                                            },
                                            onClick = {
                                                actionSurface = MessageActionSurface.Hidden
                                                MuseHaptics.light(hapticFeedback)
                                                onToggleFavorite()
                                            },
                                        )
                                    }
                                    // U-2: AI Mensaje suplemento menú pulsación larga"leer en voz alta/deja de leer"(Reutilizar la devolución de llamada del conmutador TTS;Solo mensajes de IA)
                                    if (!isUser && msg.content.isNotBlank()) {
                                        val ttsLabel = if (isSpeaking) {
                                            stringResource(R.string.chat_tts_stop)
                                        } else {
                                            stringResource(R.string.chat_tts_play)
                                        }
                                        ActionMenuItem(
                                            icon = MuseIcons.play,
                                            text = ttsLabel,
                                            contentDescription = ttsLabel,
                                            onClick = {
                                                actionSurface = MessageActionSurface.Hidden
                                                onToggleTts()
                                            },
                                        )
                                    }
                                    if (isUser) {
                                        // C-14: Los mensajes de usuario solo agregan elementos específicos del usuario(Editar/Traducción/Compartir);
                                        // Seleccionar mensaje/Colección/Copiado en menú público.(arriba)prestar.
                                        ActionMenuItem(
                                            icon = MuseIcons.edit,
                                            text = stringResource(R.string.action_edit),
                                            contentDescription = stringResource(R.string.action_edit),
                                            onClick = {
                                                actionSurface = MessageActionSurface.Hidden
                                                onEdit()
                                            },
                                        )
                                        if (msg.content.isNotBlank()) {
                                            ActionMenuItem(
                                                icon = MuseIcons.languages,
                                                text = stringResource(R.string.chat_translate_action),
                                                contentDescription = stringResource(R.string.chat_translate_action),
                                                onClick = { actionSurface = MessageActionSurface.TranslationLanguages },
                                            )
                                        }
                                        ActionMenuItem(
                                            icon = MuseIcons.share,
                                            text = stringResource(R.string.chat_share_action),
                                            contentDescription = stringResource(R.string.chat_share_action),
                                            onClick = {
                                                actionSurface = MessageActionSurface.Hidden
                                                onShareSession()
                                            },
                                        )
                                        // F-2: Reenvío entre sesiones — con"Compartir"yuxtaponer
                                        ActionMenuItem(
                                            icon = MuseIcons.swapHorizontal,
                                            text = stringResource(R.string.chat_forward_action),
                                            contentDescription = stringResource(R.string.chat_forward_action),
                                            onClick = {
                                                actionSurface = MessageActionSurface.Hidden
                                                onForward()
                                            },
                                        )
                                    } else if (msg.content.isNotBlank() || msg.reasoning?.isNotBlank() == true) {
                                        // Los mensajes del asistente también admiten pulsación larga“Más”Ingrese a la edición; el backend ya existe
                                        // editAssistantMessage, para evitar generar contenido, solo podrás eliminarlo y empezar de nuevo.
                                        ActionMenuItem(
                                            icon = MuseIcons.edit,
                                            text = stringResource(R.string.action_edit),
                                            contentDescription = stringResource(R.string.action_edit),
                                            onClick = {
                                                actionSurface = MessageActionSurface.Hidden
                                                onEdit()
                                            },
                                        )
                                    }
                                    // v2.x(comentarios de los usuarios): La eliminación de mensajes se ha movido a la fila de botones de acceso directo debajo del mensaje (configurable).
                                    // El menú de pulsación larga ya no se mostrará repetidamente para evitar dos entradas.
                                }
                            }
                        },
                    )
                }
            }

            // v2.4.6: Bloque expandible de traducción — Mismo estilo que MOOD/pensamiento,Cuelga debajo del mensaje.
            // Nombre del idioma → traducción;Un valor de cadena vacío indica que el idioma se está traduciendo.
            if (translations.isNotEmpty()) {
                val expanded = isTranslationExpanded ?: true
                Surface(
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f),
                    shape = MuseShapes.medium,
                    tonalElevation = MuseElevation.none,
                    modifier = Modifier
                        .padding(horizontal = MusePaddings.screen)
                        .widthIn(max = 360.dp)
                        .padding(top = 6.dp),
                ) {
                    Column(modifier = Modifier.padding(MusePaddings.bubbleInner)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleTranslationExpanded() }
                                .padding(vertical = MusePaddings.tinyGap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.chat_translation_block_title),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = if (expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(MuseIconSizes.iconTiny),
                            )
                        }
                        if (expanded) {
                            translations.forEach { (lang, text) ->
                                Spacer(Modifier.height(MusePaddings.tinyGap))
                                Text(
                                    text = lang,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (text.isBlank()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(top = 2.dp),
                                    ) {
                                        MuseSpinner(size = MusePaddings.itemGap, color = MaterialTheme.colorScheme.secondary)
                                        Text(
                                            stringResource(R.string.chat_translate_in_progress),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                } else {
                                    SelectionContainer {
                                        Text(
                                            text = text,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // P5-F: Instrucciones en traducción(Ruta de compatibilidad para traducciones no alcanzadas;La nueva lógica se representa dentro del bloque de traducción.)
            if (isTranslating && translations.isEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = MusePaddings.tinyGap),
                ) {
                    MuseSpinner(
                        size = MusePaddings.itemGap,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        stringResource(R.string.chat_translate_in_progress),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            // H11: La traducción conserva el texto original. — Colapsar debajo de la burbuja de traducción"Ver texto original",Expandir para mostrar el contenido del mensaje fuente para comparar
            val sourceContent = translationSourceContent
            if (sourceContent != null && sourceContent.isNotBlank()) {
                var showSource by remember { mutableStateOf(false) }
                Column(modifier = Modifier.padding(top = MusePaddings.tinyGap)) {
                    MuseTactileButton(
                        icon = if (showSource) MuseIcons.chevronUp else MuseIcons.chevronDown,
                        onClick = { showSource = !showSource },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.heightIn(min = MuseIconSizes.touchTarget),
                    )
                    AnimatedVisibility(
                        visible = showSource,
                        enter = MuseMotion.expandFadeEnter(),
                        exit = MuseMotion.expandFadeExit(),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = MuseShapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = sourceContent,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(MusePaddings.itemGap),
                            )
                        }
                    }
                }
            }

            // v1.79 (H-B2): Verificación de lista vacía movida a LaunchedEffect,Evite el estado de escritura durante la composición.
            // L-MB2: mediaPreview omitir si es nulo,Evite desencadenantes redundantes
            LaunchedEffect(mediaPreview) {
                if (mediaPreview != null && mediaPreview?.first.isNullOrEmpty()) {
                    mediaPreview = null
                }
            }
            // v2.6.13: Vista previa del archivo adjunto. Bytes leídos usando el espacio de trabajo —— El espacio de trabajo puede ser una montura SAF,
            // No se puede obtener la ruta del archivo compartible, por lo que no se puede utilizar FileProvider.
            attachmentPreview?.let { attachment ->
                val workspace: io.zer0.muse.workspace.WorkspaceManager? =
                    org.koin.compose.koinInject()
                AttachmentPreviewDialog(
                    attachment = attachment,
                    loadBytes = { attachment.workspacePath?.let { workspace?.readBytes(it) } },
                    onDismiss = { attachmentPreview = null },
                )
            }
            // v1.0.15: Visor multimedia de pantalla completa extraído como componente compartido(Implementación en línea original v1.60-B),Para reutilizar el chat grupal
            mediaPreview?.let { (images, initialIndex) ->
                FullScreenMediaViewer(
                    images = images,
                    initialIndex = initialIndex,
                    onDismiss = { mediaPreview = null },
                    onSaveImage = { imageUri ->
                        scope.launch {
                            resultOf { saveImageToGallery(context, imageUri) }
                                .onSuccess { path ->
                                    MuseToast.show(
                                        context.getString(R.string.chat_image_saved_toast, path),
                                    )
                                }
                                .onError { msg, _ ->
                                    MuseToast.show(
                                        context.getString(R.string.chat_image_save_failed_toast, msg),
                                    )
                                }
                        }
                    },
                )
            }
            if (showDeleteConfirm) {
                // U-16: Eliminar selección de rango — solo este mensaje(incluyendo su respuesta) / junto con todos los mensajes posteriores
                var deleteWithFollowing by remember { mutableStateOf(false) }
                MuseDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = stringResource(R.string.chat_delete_message),
                    content = {
                        Column {
                            Text(stringResource(R.string.chat_delete_message_confirm))
                            Spacer(Modifier.height(8.dp))
                            // U-16: Eliminar selección de rango(Sólo este artículo / junto con el siguiente),Haga clic en la fila para cambiar,Resaltar elemento seleccionado
                            Text(
                                text = (if (!deleteWithFollowing) "● " else "○ ") + stringResource(R.string.chat_delete_scope_only),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { deleteWithFollowing = false }
                                    .padding(vertical = 4.dp),
                                color = if (!deleteWithFollowing) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = (if (deleteWithFollowing) "● " else "○ ") +
                                    stringResource(R.string.chat_delete_scope_with_following),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { deleteWithFollowing = true }
                                    .padding(vertical = 4.dp),
                                color = if (deleteWithFollowing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    },
                    confirmText = stringResource(R.string.action_delete),
                    onConfirm = {
                        showDeleteConfirm = false
                        if (deleteWithFollowing) onDeleteWithFollowing() else onDeleteMessage()
                    },
                    destructive = true,
                )
            }
            // A5: Capa emergente de información del mensaje(modelo/tiempo/Consume mucho tiempo/Token Dosis)
            if (showInfoSheet) {
                MessageInfoSheet(
                    msg = msg,
                    onDismiss = { showInfoSheet = false },
                )
            }
            // P2-13: Menú contextual del botón derecho del escritorio(Solo aparece debajo del teclado físico + Ventana expandida)
            // artículo:copiar/regenerar(Sólo el último mensaje de IA)/ Eliminar/Compartir
            // Menú de pulsación larga con terminal celular(showActionMenu)Alineación funcional,Pero usar el paradigma de interacción del menú contextual del escritorio
            if (showDesktopContextMenu) {
                // Precargar cadenas localizadas en el contexto @Composable,remember No se puede llamar dentro de un bloque stringResource
                val copyLabel = stringResource(R.string.desktop_context_copy)
                val regenerateLabel = stringResource(R.string.desktop_context_regenerate)
                val shareLabel = stringResource(R.string.desktop_context_share)
                val deleteLabel = stringResource(R.string.desktop_context_delete)
                // A5: Entrada de la capa de viñetas de información del mensaje(Menú contextual del escritorio)
                val infoLabel = stringResource(R.string.msg_info_title)
                val contextMenuItems = remember(
                    msg.id,
                    isUser,
                    isLastAssistant,
                    copyLabel,
                    regenerateLabel,
                    shareLabel,
                    deleteLabel,
                    infoLabel,
                ) {
                    buildList {
                        if (msg.content.isNotBlank()) {
                            add(
                                ContextMenuItem(
                                    label = copyLabel,
                                    icon = MuseIcons.copy,
                                    onClick = { onCopyMessage(messageCopyText(msg.content, msg.reasoning)) },
                                ),
                            )
                        }
                        // Solo disponible al final del mensaje AI"regenerar"
                        if (!isUser && isLastAssistant && msg.content.isNotEmpty()) {
                            add(
                                ContextMenuItem(
                                    label = regenerateLabel,
                                    icon = MuseIcons.refresh,
                                    onClick = {
                                        MuseHaptics.light(hapticFeedback)
                                        onRegenerate()
                                    },
                                ),
                            )
                        }
                        add(
                            ContextMenuItem(
                                label = shareLabel,
                                icon = MuseIcons.share,
                                onClick = onShareSession,
                            ),
                        )
                        // A5: Capa emergente de información del mensaje(modelo/Consume mucho tiempo/Token Dosis)
                        add(
                            ContextMenuItem(
                                label = infoLabel,
                                icon = MuseIcons.info,
                                onClick = {
                                    showDesktopContextMenu = false
                                    showInfoSheet = true
                                },
                            ),
                        )
                        add(
                            ContextMenuItem(
                                label = deleteLabel,
                                icon = MuseIcons.trash,
                                destructive = true,
                                onClick = { showDeleteConfirm = true },
                            ),
                        )
                    }
                }
                DesktopContextMenu(
                    items = contextMenuItems,
                    onDismiss = { showDesktopContextMenu = false },
                )
            }
        } // cerrado Column

        // v1.0.72: Máscara de tamaño completo en modo de selección múltiple — Haga clic en cualquier parte del mensaje en modo de selección.(Contiene texto/fotos/bloque de código/bloque de cotización,
        // Estos subcomponentes consumirán eventos de clic.)Todos están alternados seleccionados. Reparación"Después de ingresar al modo de selección, no puedo seleccionar ningún mensaje.".
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        // A11Y-04: Parche de contenedor en el que se puede hacer clic sin nombre role=Button Semántica
                        role = Role.Button,
                    ) { onToggleSelection?.invoke() },
            )
        }
    } // cerrado Box
}

/**
 * v1.0.72: Tarjeta de operación de prensa larga(Popup Posicionamiento,Haga clic donde aparecer).
 *
 * v1.0.72 iterar:
 *  - Compacto en general:Reduzca el bloque inferior del icono/altura de línea/esquinas redondeadas/espaciado.
 *  - scale+fade Animación de entrada.
 *  - D4 (CHAT-05): La combinación de colores sigue el tema.(colorScheme color semántico),No más blanco y negro.
 * contenido:Citar/Copiar/Seleccionar texto/Compartir/Reenviar/Editar(Solo mensajes de usuario) / Más.
 */
@Composable
private fun TelegramActionCard(
    isUser: Boolean,
    onQuote: () -> Unit,
    onCopy: () -> Unit,
    onSelectText: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onMore: () -> Unit,
    // F-2: Reenvío entre sesiones
    onForward: () -> Unit = {},
) {
    // D4 (CHAT-05): Mantenga presionado el menú para seguir el tema. — Opte por la combinación de colores en blanco y negro,Utilice colores semánticos colorScheme en su lugar,
    // Tema personalizado/No más dramatismo en colores oscuros.
    val bg = MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = MaterialTheme.colorScheme.onSurface
    val iconBlock = MaterialTheme.colorScheme.surfaceVariant
    val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)

    // Animación de entrada:scale 0.92 → 1 + fade
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = 1f,
        animationSpec = MuseMotion.tween(MuseAnimation.FAST_NORMAL_MS, easing = FastOutSlowInEasing),
        label = "menuScale",
    )
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = 1f,
        animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS),
        label = "menuAlpha",
    )
    Surface(
        color = bg,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 10.dp,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 170.dp, max = 210.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .padding(vertical = 5.dp),
        ) {
            FixedColorActionRow(MuseIcons.reply, stringResource(R.string.message_action_quote), textColor, iconBlock, onQuote)
            FixedColorActionRow(MuseIcons.copy, stringResource(R.string.action_copy), textColor, iconBlock, onCopy)
            FixedColorActionRow(MuseIcons.square, stringResource(R.string.action_select_text), textColor, iconBlock, onSelectText)
            FixedColorActionRow(MuseIcons.share, stringResource(R.string.chat_share_action), textColor, iconBlock, onShare)
            // F-2: Reenvío entre sesiones(yuxtaponer con compartir)
            FixedColorActionRow(MuseIcons.swapHorizontal, stringResource(R.string.chat_forward_action), textColor, iconBlock, onForward)
            if (isUser) {
                FixedColorActionRow(MuseIcons.edit, stringResource(R.string.action_edit), textColor, iconBlock, onEdit)
            }
            // Línea divisoria delgada de color fijo.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .height(0.5.dp)
                    .background(divider),
            )
            FixedColorActionRow(MuseIcons.moreHorizontal, stringResource(R.string.action_more), textColor, iconBlock, onMore)
        }
    }
}

/** v1.0.72: Fila de menú de color fijo(Compacto:Bloque inferior de ícono pequeño + altura de línea pequeña + presionar scale). */
@Composable
private fun FixedColorActionRow(icon: ImageVector, text: String, textColor: Color, iconBlockColor: Color, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS),
        label = "rowScale",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Bloque inferior de icono pequeño
        Surface(
            shape = RoundedCornerShape(9.dp),
            color = iconBlockColor,
            modifier = Modifier.size(30.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = text,
                    modifier = Modifier.size(16.dp),
                    tint = textColor,
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
        )
    }
}
