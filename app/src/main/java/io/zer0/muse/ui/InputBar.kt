@file:Suppress("FunctionNaming")

package io.zer0.muse.ui

import android.view.KeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.MessageRole
import io.zer0.muse.R
import io.zer0.muse.asr.ASRStatus
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.delay

/** L-IB1: Límite de caracteres del cuadro de entrada,Evite que el texto muy largo ralentice la representación o exceda la ventana de contexto del modelo. */
internal const val INPUT_TEXT_MAX_LENGTH = 5000

/**
 * v1.0.47 P5-2: Se extrae del texto antiguo y se inserta nuevo.(Pegar)fragmento.
 *
 * Eliminando el prefijo común más largo y el sufijo común más largo,El resto es contenido nuevo.
 * Adecuado para pegar escenarios donde el cursor está en cualquier posición(No solo agregar al final).
 */
private fun extractInsertedSegment(oldText: String, newText: String): String {
    if (oldText.isEmpty()) return newText
    val minLen = minOf(oldText.length, newText.length)
    var prefix = 0
    while (prefix < minLen && oldText[prefix] == newText[prefix]) prefix++
    var suffix = 0
    while (suffix < minLen - prefix &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
        ) suffix++
    return newText.substring(prefix, newText.length - suffix)
}

/**
 * v1.131: @mention Destacar la regularidad — Constantes de nivel de archivo,evitar cada vez [MentionHighlightTransformation.filter]
 * Reorganizar y construir nuevos(El filtro se llamará con frecuencia cuando cambie el texto en el cuadro de entrada.).
 * Coincidir @ seguido de chino/ingles/numeros/Secuencia de subrayado(Evite resaltar buzones accidentalmente).
 */
private val MENTION_HIGHLIGHT_REGEX = Regex("@[\\u4e00-\\u9fa5\\w]+")

/**
 * v0.22 Campo de entrada de cápsula minimalista — Diseño de cápsula minimalista.
 *
 * diseño(Imágenes de referencia):
 *  - entero:Tiras redondeadas de color claro.(esquinas redondeadas 24dp),fondo surfaceVariant
 *  - lado izquierdo:Botón redondo + → Ampliar archivo adjunto/fotos/Dibujo/menú de búsqueda en Internet
 *  - medio:Sin fronteras TextField,texto de marcador de posición"enviar mensaje…"
 *  - lado derecho:micrófono(texto vacío) / boton enviar(cuando hay texto)
 *  - Transmisión:El lado derecho se convierte en un botón de parada.
 *
 * Funcionalidad retenida:
 *  - Adjuntos, fotos(OCR/Visión), entrada de voz, modo de dibujo, búsqueda de red
 *  - Chips de mensajes rápidos, selector de modo, vista previa de imagen a enviar
 *  - Las zonas seguras de borde a borde se manejan de manera uniforme en la barra inferior de la página de Chat
 */

/** Desplazamiento de desbordamiento del botón Eliminar en la miniatura que se enviará: deje que la marca de la esquina del botón se presione fuera de la esquina redondeada de la miniatura y la línea de vista previa no se elevará. */
private val REMOVE_BUTTON_OVERHANG = 12.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun InputBar(state: MuseInputState = MuseInputState(), callbacks: InputBarCallbacks = InputBarCallbacks()) {
    val hapticFeedback = LocalHapticFeedback.current
    val reducedMotion = MuseMotion.isReducedMotion()
    // B7-07: del estado agregado/Desempaquetar en devolución de llamada,Mantenga la lógica original del cuerpo de la función sin cambios.
    val text = state.text
    val assistantName = state.assistantName.ifBlank { "Muse" }
    val isStreaming = state.isStreaming
    val isWaitingFirstToken = state.isWaitingFirstToken
    val isDrawMode = state.isDrawMode
    val isWebSearchEnabled = state.isWebSearchEnabled
    val isDeepThinkingEnabled = state.isDeepThinkingEnabled
    val deepThinkingLevel = state.deepThinkingLevel
    val showExpandButton = state.showExpandButton
    val imageGenParams = state.imageGenParams
    val showRestartContext = state.showRestartContext
    val assistants = state.assistants
    val enterToSend = state.enterToSend
    val quickMessages = state.quickMessages
    val pendingImages = state.pendingImages
    val pendingDocuments = state.pendingDocuments
    val pendingVideo = state.pendingVideo
    val replyingTo = state.replyingTo
    val replyQuoteOverride = state.replyQuoteOverride
    val isRecording = state.isRecording
    val asrStatus = state.asrStatus
    val asrErrorMessage = state.asrErrorMessage
    val recordingAmplitudes = state.recordingAmplitudes
    val showMic = state.showMic
    val autoFocus = state.autoFocus
    val pasteAsFileEnabled = state.pasteAsFileEnabled
    val pasteAsFileThreshold = state.pasteAsFileThreshold
    val onCycleDeepThinkingLevel = callbacks.onCycleDeepThinkingLevel
    val onImageGenParamsChange = callbacks.onImageGenParamsChange
    val onTextChanged = callbacks.onTextChanged
    val onSend = callbacks.onSend
    val onStop = callbacks.onStop
    val onInterject = callbacks.onInterject
    val onEnqueuePending = callbacks.onEnqueuePending
    val onNavigateInputHistory = callbacks.onNavigateInputHistory
    val onPickDocument = callbacks.onPickDocument
    val onToggleDrawMode = callbacks.onToggleDrawMode
    val onToggleWebSearch = callbacks.onToggleWebSearch
    val onToggleDeepThinking = callbacks.onToggleDeepThinking
    val onRestartContext = callbacks.onRestartContext
    val onDelegateToAssistant = callbacks.onDelegateToAssistant
    val onPickKnowledge = callbacks.onPickKnowledge
    val onOpenPromptTemplates = callbacks.onOpenPromptTemplates
    val onOpenSkills = callbacks.onOpenSkills
    val onInsertQuickMessage = callbacks.onInsertQuickMessage
    val onPickImage = callbacks.onPickImage
    val onPickGalleryImage = callbacks.onPickGalleryImage
    val onRemovePendingImage = callbacks.onRemovePendingImage
    val onRemovePendingDocument = callbacks.onRemovePendingDocument
    val onPickVideo = callbacks.onPickVideo
    val onRemovePendingVideo = callbacks.onRemovePendingVideo
    val onClearReply = callbacks.onClearReply
    val onEditReply = callbacks.onEditReply
    val onStartRecording = callbacks.onStartRecording
    val onStopRecording = callbacks.onStopRecording
    val onCancelRecording = callbacks.onCancelRecording
    val onAddPastedTextAsDocument = callbacks.onAddPastedTextAsDocument
    val onShowToolCalls = callbacks.onShowToolCalls
    val toolCallCompleted = state.toolCallCompleted
    val toolCallTotal = state.toolCallTotal
    // v1.26: Después de deslizar hacia arriba para cancelar"Cancelado"aviso transitorio(1.5s desaparece automáticamente después)
    var showCancelledHint by remember { mutableStateOf(false) }
    LaunchedEffect(showCancelledHint) {
        if (showCancelledHint) {
            delay(1500)
            showCancelledHint = false
        }
    }
    var expanded by remember { mutableStateOf(false) }
    // Mantenga presionado el campo de entrada para que aparezca el menú de acciones(Entrada al modo de entrada de pantalla completa)
    var showActionMenu by remember { mutableStateOf(false) }
    // v2.4.5 fix: Finalización en línea del comando de barra diagonal — entrada "/xxx"(Aún no hay espacios)Los candidatos se encuentran encima de la isla de entrada.
    // anteriormente SlashCommand.allCommandNames() Sólo definido, nunca llamado,Entrada del usuario/no ve mensajes.
    val slashQuery = text.takeIf { it.startsWith("/") && !it.contains(' ') && !it.contains('\n') }?.removePrefix("/")
    val slashMatches = remember(slashQuery) {
        if (slashQuery == null) {
            emptyList()
        } else {
            io.zer0.muse.ui.chat.SlashCommand.entries.filter {
                it.command.startsWith(slashQuery, ignoreCase = true)
            }
        }
    }
    // F-3: Mantenga presionado el signo más para expandir/Estado de la barra de herramientas de acceso directo contraída(rememberSaveable,rotar/retención de fondo).
    // Sin interruptor de persistencia listo para usar,Simplemente use RememberSaveable según lo requiera la tarea.
    var quickBarExpanded by rememberSaveable { mutableStateOf(false) }
    if (expanded) {
        MuseExpandedInputEditor(
            text = text,
            assistantName = assistantName,
            onTextChanged = onTextChanged,
            onSend = onSend,
            onClose = { expanded = false },
        )
    }
    // Centrarse automáticamente en el cuadro de entrada al ingresar a la página de chat(Sólo si el texto está vacío y se permite el enfoque automático,Evite interrumpir borradores existentes)
    val focusRequester = remember { FocusRequester() }
    // v1.0.74 fix: Espere un cuadro antes de solicitar el enfoque,Evite que el sistema de enfoque no esté listo.(Prueba real de Huawei Android 10)
    io.zer0.muse.ui.common.focus.SafeAutoFocusEffect({ autoFocus && text.isEmpty() }, focusRequester)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // v1.99: grandeRángulo/Evitación de zonas de seguridad horizontales para dispositivos con pantalla curva(displayCutout Devoluciones en dispositivos no recortados 0,seguro)
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            // v2.4.5: La isla de entrada se estrecha y se eleva.(Alineación de la imagen de referencia).
            .padding(
                horizontal = MusePaddings.inputIslandHorizontal,
                vertical = MusePaddings.inputIslandVertical,
            )
            // v2.4.5: espaciado flotante inferior — Aleje la isla de entrada de la parte inferior/barra de navegación un poco,Ya no se pega al fondo.
            .padding(bottom = MusePaddings.inputIslandBottomGap),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        // QuickMessages Burbujas
        if (quickMessages.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.inputStackGap),
            ) {
                quickMessages.forEach { qm ->
                    MuseChip(
                        selected = false,
                        onClick = { onInsertQuickMessage(qm) },
                        label = qm.name.ifBlank { stringResource(R.string.chat_unnamed) },
                    )
                }
            }
        }
        // v2.6.11: Originalmente aquí había una cápsula "Tool 22/23".
        // Dice lo mismo que la barra plana de proceso encima del campo de entrada. Cuando dos de ellos aparecen juntos, son repetitivos y exprimen el espacio vertical.
        // Ahora el estado de ejecución está unificado por ActiveProcessBar (haga clic en él para obtener más detalles), lo cual no se repetirá aquí.
        // F-3: Mantenga presionado el signo más para expandir la barra de herramientas de acceso directo(fila horizontal,Haga clic en un elemento para contraerlo).
        if (quickBarExpanded && !isStreaming) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                QuickBarAction(
                    // v2.x: Icono de búsqueda en Internet de"Traducción"Semántica(languages)Corregido a globe
                    icon = MuseIcons.globe,
                    contentDescription = stringResource(R.string.chat_web_search_cd),
                    active = isWebSearchEnabled,
                    onClick = {
                        onToggleWebSearch()
                        quickBarExpanded = false
                    },
                )
                QuickBarAction(
                    icon = MuseIcons.brain,
                    contentDescription = stringResource(R.string.chat_deep_thinking_cd),
                    active = isDeepThinkingEnabled,
                    onClick = {
                        onToggleDeepThinking()
                        quickBarExpanded = false
                    },
                )
                QuickBarAction(
                    icon = MuseIcons.paperclip,
                    contentDescription = stringResource(R.string.chat_tool_attachment),
                    active = false,
                    onClick = {
                        quickBarExpanded = false
                        onPickDocument()
                    },
                )
                QuickBarAction(
                    icon = MuseIcons.book,
                    contentDescription = stringResource(R.string.chat_tool_knowledge),
                    active = false,
                    onClick = {
                        quickBarExpanded = false
                        onPickKnowledge()
                    },
                )
                QuickBarAction(
                    icon = MuseIcons.wrench,
                    contentDescription = stringResource(R.string.chat_tool_skills),
                    active = false,
                    onClick = {
                        quickBarExpanded = false
                        onOpenSkills()
                    },
                )
                QuickBarAction(
                    icon = MuseIcons.brush,
                    contentDescription = stringResource(R.string.chat_tool_draw_mode),
                    active = isDrawMode,
                    onClick = {
                        quickBarExpanded = false
                        onToggleDrawMode()
                    },
                )
            }
        }
        // Vista previa de las fotografías a enviar
        // La altura de la línea está determinada por el cuerpo de la miniatura.:El botón Quitar tiene un área táctil de 48 dp,Ponerlo directamente en la Caja elevará las filas.,
        // Deje que la barra de vista previa invada la lista de mensajes de arriba. Los botones cambiaron a apilamiento en posición absoluta,No participa en la medición de la altura de las hileras.
        if (pendingImages.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MusePaddings.previewThumb)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pendingImages.forEachIndexed { index, b64 ->
                    Box(
                        modifier = Modifier
                            .size(MusePaddings.previewThumb)
                            .clip(MuseShapes.medium),
                    ) {
                        SmartImage(
                            model = "data:image/jpeg;base64,$b64",
                            contentDescription = stringResource(R.string.chat_pending_image_cd, index + 1),
                            // Si presiona Ajustar en el cuadro, la imagen vertical se reducirá a una línea en el medio.,Cambie Recortar para llenar la miniatura.
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                        io.zer0.muse.ui.common.media.AttachmentRemoveButton(
                            contentDescription = stringResource(R.string.chat_remove_image_cd),
                            onClick = { onRemovePendingImage(index) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = REMOVE_BUTTON_OVERHANG, y = -REMOVE_BUTTON_OVERHANG),
                        )
                    }
                }
            }
        }

        // v1.136 T10: Vista previa de los documentos a enviar(Chip de archivo extraíble)
        if (pendingDocuments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = MusePaddings.tightGap),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pendingDocuments.forEachIndexed { index, doc ->
                    val docInteractionSource = remember { MutableInteractionSource() }
                    Surface(
                        shape = MuseShapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier
                            .padding(top = MusePaddings.tightGap)
                            .clickable(
                                interactionSource = docInteractionSource,
                                indication = null,
                            ) { onRemovePendingDocument(index) },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.labelVerticalGap),
                        ) {
                            Icon(
                                imageVector = MuseIcons.fileText,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(MuseIconSizes.iconSmallTiny),
                            )
                            Spacer(Modifier.width(MusePaddings.tightGap))
                            Column {
                                Text(
                                    text = doc.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = MusePaddings.maxInlineWidth),
                                )
                                Text(
                                    text = docChipSubtitle(doc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(MusePaddings.tightGap))
                            Icon(
                                imageVector = MuseIcons.x,
                                contentDescription = stringResource(R.string.chat_remove_document_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(MuseIconSizes.iconTiny),
                            )
                        }
                    }
                }
            }
        }

        // Vista previa del vídeo que se enviará(Consistente con el estilo de vista previa de la imagen.,Duración de superposición + ícono de reproducción indica el tipo de video)
        pendingVideo?.let { va ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MusePaddings.tightGap),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(MusePaddings.previewThumb)
                        .clip(MuseShapes.medium),
                ) {
                    // Degradado a marcador de posición oscuro + ícono de video cuando falta la miniatura,evitar espacios en blanco
                    val thumb = va.thumbnail
                    if (!thumb.isNullOrBlank()) {
                        SmartImage(
                            model = "data:image/jpeg;base64,$thumb",
                            contentDescription = stringResource(R.string.chat_pending_video_cd),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = MuseIcons.video,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(MuseIconSizes.iconLarge),
                            )
                        }
                    }
                    // icono de reproducción central,Consejo: Este es un vídeo, no una imagen.
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(MuseIconSizes.iconVideo)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = MuseIcons.play,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    }
                    // botón quitar(Vista previa de la misma imagen,tocar el objetivo 48dp,Icon centro)
                    val removeVideoInteractionSource = remember { MutableInteractionSource() }
                    val isRemoveVideoPressed by removeVideoInteractionSource.collectIsPressedAsState()
                    val removeVideoBgColor by animateColorAsState(
                        targetValue = if (isRemoveVideoPressed) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        },
                        animationSpec = MuseMotion.tween(MuseAnimation.TACTILE_MS, easing = MuseAnimation.EaseOutCubic),
                        label = "removeVideoBg",
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(MuseIconSizes.touchTarget)
                            .clip(CircleShape)
                            .background(removeVideoBgColor)
                            .clickable(
                                interactionSource = removeVideoInteractionSource,
                                indication = null,
                            ) { onRemovePendingVideo() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = MuseIcons.x,
                            contentDescription = stringResource(R.string.chat_remove_video_cd),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    }
                }
                // La metainformación del vídeo se muestra a la derecha.:duración + resolución,Ayudar a los usuarios a confirmar los archivos adjuntos
                Column {
                    Text(
                        text = formatVideoDuration(va.durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (va.width > 0 && va.height > 0) {
                        Text(
                            text = "${va.width}x${va.height}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }

        // Cotización Responder:Se muestra un resumen de los mensajes citados encima del cuadro de entrada.
        // v1.0.72: Eliminar la funcionalidad de edición de cotizaciones(Después de editar, el bloque de referencia se fijará en el campo de entrada. bug,La edición en sí es inútil)
        // v1.0.72 fix: Todo el bloque de referencia se puede borrar con un solo clic.(Comentarios de los usuarios"no puedo cruzar" — X Demasiado pequeño y puede bloquearse con clics,
        //   Haga clic en el bloque de referencia en cualquier lugar para borrar la referencia.,Reducir la dificultad operativa)
        replyingTo?.let { msg ->
            // v1.0.72: Bloque de citas para crear un estilo isleño.(Fondo sólido + esquinas redondeadas),Cuadro de entrada bloque blanco transparente resuelto
            io.zer0.muse.ui.common.surface.MuseIsland(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClearReply,
                    ),
                backgroundAlpha = 1f,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.bubbleInner),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                if (msg.role == MessageRole.USER) {
                                    R.string.quote_label_user
                                } else {
                                    R.string.quote_label_assistant
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            // v2.6.8: Cite la vista previa y use la salida de visualización; de lo contrario, el mensaje de la tarjeta filtrará la cola JSON sobre el cuadro de entrada.
                            text = io.zer0.muse.transformer.InternalMarkupSanitizer.displayContent(msg.content),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // v1.0.72: botón borrar cita — Ampliar área de clic(48dp) + Los iconos son más grandes y claros.
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onClearReply,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = MuseIcons.x,
                            contentDescription = stringResource(R.string.quote_clear),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }

        // v0.34: Panel de parámetros del modo de dibujo(Anular temporalmente los valores predeterminados de configuración)
        if (isDrawMode) {
            ImageGenParamsPanel(
                params = imageGenParams,
                onParamsChange = onImageGenParamsChange,
            )
        }

        // v1.0.53: Las cápsulas Thought Strength se han fusionado en el menú Plus"pensamiento profundo"cambiar —
        // Haga clic en el interruptor de palanca,Mantenga presionado para recorrer los niveles (LOW → MED → HIGH → XHIGH).

        // Campo de entrada principal: contenedor de esquinas redondeadas
        // v1.0.72: Hacer estilo isla — Fondo sólido + esquinas redondeadas + sombra(Los comentarios de los usuarios son completamente transparentes y demasiado desnudos.);
        //   El cuadro de entrada en sí permanece transparente.,Evite apilar contenedores de colores sólidos"bloque blanco"
        // v2.0 Reparación(Comentarios de los usuarios: El contraste entre la isla de entrada y el fondo es demasiado débil bajo el tema claro.):
        //   El color de relleno se intensifica en un nivel hacia el color de mezcla de onSurface.,Los colores claros se oscurecen automáticamente/Ilumina automáticamente los colores oscuros,Mismo calibre que MuseTextField.
        // v2.5.0: vidrio liquido — Cuando el interruptor está encendido y hazeState está disponible,La isla de entrada tiene un fondo borroso en lugar de un color sólido.
        val islandBaseColor = androidx.compose.ui.graphics.lerp(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurface,
            0.06f,
        )
        val islandModifier: Modifier = if (state.glassActive && state.glassConfig.enabled) {
            // v2.6: Entra en Island Glass —— Unificado por decisión glassSurfaceModifier(backdrop/liquid Vidrio real o vidrio falso degradado)
            io.zer0.muse.ui.theme.glassSurfaceModifier(
                shape = MuseShapes.pill,
                surfaceColor = islandBaseColor,
                config = state.glassConfig,
                backdrop = io.zer0.muse.ui.theme.LocalLayerBackdrop.current,
                waterState = io.zer0.muse.ui.theme.LocalWaterGlassState.current,
            )
        } else {
            Modifier
        }
        Surface(
            color = if (state.glassActive && state.glassConfig.enabled) Color.Transparent else islandBaseColor,
            // v2.4.5 (Alineación de la imagen de referencia): Cambia la isla de entrada a una cápsula real.(esquinas redondeadas = media altura) + quitar la sombra,
            // Visualmente más ligero y compacto;La altura está determinada por el área táctil de 48 dp dentro de la fila.
            shape = MuseShapes.pill,
            tonalElevation = MuseElevation.low,
            modifier = islandModifier.fillMaxWidth(),
        ) {
            // v0.52: @mention Resaltar transición(Color @documentname como color primario,El mensaje se refiere a la base de conocimientos.)
            val mentionColor = MaterialTheme.colorScheme.primary
            val mentionTransform = remember(mentionColor) { MentionHighlightTransformation(mentionColor) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // v2.4.5 (Alineación de la imagen de referencia): Acolchado apretado para 6dp;La altura de la línea ya no agrega una línea vertical adicional padding,
                    // La altura de la cápsula está determinada por el área táctil de 48 dp del botón interior.,Visualmente más compacto.
                    .padding(
                        horizontal = MusePaddings.inputIslandInnerHorizontal,
                        vertical = MusePaddings.inputIslandRowVertical,
                    )
                    // Mantenga presionado el campo de entrada para que aparezca el menú de acciones(Entrada al modo de entrada de pantalla completa)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = {
                            MuseHaptics.medium(hapticFeedback)
                            showActionMenu = true
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                // v0.44: Sheet declaración de estado(El botón Agregar a la derecha activa,La capa de resorte va anclada al propio botón.)
                Box(modifier = Modifier.size(MuseIconSizes.touchTarget)) {
                    var showToolSheet by remember { mutableStateOf(false) }
                    val context = LocalContext.current
                    // lado izquierdo: + botón → Haga clic en la barra inferior de la herramienta emergente;Mantenga presionado para expandir/Cerrar barra de herramientas de acceso directo(F-3)
                    // F-3: Utilice combinadoClickable para alojar clics simultáneamente(bomba Sheet)con pulsación larga(Cortar barra de herramientas de acceso directo).
                    Box(
                        modifier = Modifier
                            .size(MuseIconSizes.touchTarget)
                            .combinedClickable(
                                enabled = !isStreaming,
                                onClick = {
                                    MuseHaptics.light(hapticFeedback)
                                    showToolSheet = true
                                },
                                onLongClick = {
                                    // combinedClickable El estado de transmisión se ha deshabilitado usando habilitado,Hagamos otro juicio aquí.
                                    if (!isStreaming) {
                                        MuseHaptics.medium(hapticFeedback)
                                        quickBarExpanded = !quickBarExpanded
                                        // Proporciona información sobre el brindis con cada pulsación larga(onLongClick Sólo se activa una vez por gesto)
                                        MuseToast.show(
                                            context.getString(
                                                if (quickBarExpanded) R.string.chat_quickbar_hint_on else R.string.chat_quickbar_hint_off,
                                            ),
                                        )
                                    }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = MuseIcons.plus,
                            contentDescription = stringResource(R.string.chat_tools_cd),
                            // v1.79 (L-I7): Estado deshabilitado reducido alpha,Proporcionar retroalimentación visual
                            // F-3: Cuando se expande la barra de herramientas de acceso directo, el signo más se resalta en color primario.,Proporcionar comentarios visibles
                            tint = when {
                                isStreaming -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                quickBarExpanded -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    }
                    if (showToolSheet) {
                        val deepThinkingLabel = stringResource(R.string.chat_deep_thinking_cd)
                        val deepThinkingTitle = if (isDeepThinkingEnabled) {
                            val levelLabel = when (deepThinkingLevel) {
                                // v1.0.72: Pensando en el cambio de nivel en chino(bajo/en/alto/extremadamente alto)
                                io.zer0.ai.core.ReasoningLevel.LOW -> "bajo"
                                io.zer0.ai.core.ReasoningLevel.MEDIUM -> "en"
                                io.zer0.ai.core.ReasoningLevel.HIGH -> "alto"
                                io.zer0.ai.core.ReasoningLevel.XHIGH -> "extremadamente alto"
                                else -> "automático"
                            }
                            "$deepThinkingLabel · $levelLabel"
                        } else {
                            deepThinkingLabel
                        }
                        val toolEntries = buildList {
                            add(
                                ToolEntry(
                                    // v2.x: El icono de búsqueda en Internet se ha corregido para globe(Los idiomas originales son semántica de traducción.)
                                    icon = MuseIcons.globe,
                                    title = stringResource(R.string.chat_web_search_cd),
                                    isActive = isWebSearchEnabled,
                                    showArrow = false,
                                    onClick = {
                                        MuseHaptics.light(hapticFeedback)
                                        onToggleWebSearch()
                                    },
                                ),
                            )
                            add(
                                ToolEntry(
                                    icon = MuseIcons.brain,
                                    title = deepThinkingTitle,
                                    isActive = isDeepThinkingEnabled,
                                    showArrow = false,
                                    onClick = {
                                        MuseHaptics.light(hapticFeedback)
                                        onToggleDeepThinking()
                                    },
                                    onLongClick = {
                                        // v2.x: Cambia el nivel para usar un toque más intenso + el título se actualiza instantáneamente con el nivel(Comentarios visibles)
                                        MuseHaptics.medium(hapticFeedback)
                                        onCycleDeepThinkingLevel()
                                    },
                                ),
                            )
                            add(
                                ToolEntry(
                                    icon = MuseIcons.paperclip,
                                    title = stringResource(R.string.chat_tool_attachment),
                                    onClick = {
                                        MuseHaptics.light(hapticFeedback)
                                        showToolSheet = false
                                        onPickDocument()
                                    },
                                ),
                            )
                            add(
                                ToolEntry(
                                    icon = MuseIcons.book,
                                    title = stringResource(R.string.chat_tool_knowledge),
                                    subtitle = stringResource(R.string.chat_tool_knowledge_subtitle),
                                    onClick = {
                                        MuseHaptics.light(hapticFeedback)
                                        showToolSheet = false
                                        onPickKnowledge()
                                    },
                                ),
                            )
                            add(
                                ToolEntry(
                                    icon = MuseIcons.book,
                                    title = stringResource(R.string.chat_prompt_templates_title),
                                    subtitle = stringResource(R.string.chat_tool_prompt_template_subtitle),
                                    onClick = {
                                        MuseHaptics.light(hapticFeedback)
                                        showToolSheet = false
                                        onOpenPromptTemplates()
                                    },
                                ),
                            )
                            add(
                                ToolEntry(
                                    icon = MuseIcons.wrench,
                                    title = stringResource(R.string.chat_tool_skills),
                                    subtitle = stringResource(R.string.chat_tool_skills_subtitle),
                                    onClick = {
                                        MuseHaptics.light(hapticFeedback)
                                        showToolSheet = false
                                        onOpenSkills()
                                    },
                                ),
                            )
                            add(
                                ToolEntry(
                                    icon = MuseIcons.brush,
                                    title = stringResource(R.string.chat_tool_draw_mode),
                                    subtitle = if (isDrawMode) {
                                        stringResource(
                                            R.string.chat_tool_draw_mode_subtitle_on,
                                        )
                                    } else {
                                        stringResource(R.string.chat_tool_draw_mode_subtitle)
                                    },
                                    isActive = isDrawMode,
                                    showArrow = !isDrawMode,
                                    onClick = {
                                        MuseHaptics.light(hapticFeedback)
                                        showToolSheet = false
                                        onToggleDrawMode()
                                    },
                                ),
                            )
                            if (assistants.isNotEmpty()) {
                                add(
                                    ToolEntry(
                                        icon = MuseIcons.users,
                                        title = stringResource(R.string.chat_delegate_action),
                                        subtitle = stringResource(R.string.chat_tool_delegate_subtitle),
                                        onClick = {
                                            MuseHaptics.light(hapticFeedback)
                                            showToolSheet = false
                                            onDelegateToAssistant()
                                        },
                                    ),
                                )
                            }
                            if (showRestartContext) {
                                add(
                                    ToolEntry(
                                        icon = MuseIcons.refresh,
                                        title = stringResource(R.string.chat_tool_restart_context),
                                        subtitle = stringResource(R.string.chat_tool_restart_context_subtitle),
                                        onClick = {
                                            MuseHaptics.light(hapticFeedback)
                                            showToolSheet = false
                                            onRestartContext()
                                        },
                                    ),
                                )
                            }
                        }
                        MuseToolSheet(
                            context = context,
                            hapticFeedback = hapticFeedback,
                            onPickImage = { asOcr ->
                                showToolSheet = false
                                onPickImage(asOcr)
                            },
                            onPickGalleryImage = { uri ->
                                showToolSheet = false
                                onPickGalleryImage(uri)
                            },
                            entries = toolEntries,
                            onDismiss = { showToolSheet = false },
                        )
                    }
                }

                // v2.4.5 fix: candidato al comando de barra diagonal(Se muestra encima de la isla de entrada cuando la entrada comienza con / y no contiene espacios)
                if (slashMatches.isNotEmpty() && !isStreaming) {
                    SlashCommandSuggestions(
                        commands = slashMatches,
                        onPick = { cmd -> onTextChanged("/${cmd.command} ") },
                    )
                }

                // v1.0.47 P5-4: Extraiga el subcomponente MessageInputField,Reorganización de alta frecuencia de caja de entrada aislada.,
                // Evite que onValueChange active todo InputBar(Contiene herramientas Vista previa de hoja/imagen, etc.)Reorganización.
                MessageInputField(
                    text = text,
                    isStreaming = isStreaming,
                    isDrawMode = isDrawMode,
                    enterToSend = enterToSend,
                    showExpandButton = showExpandButton,
                    focusRequester = focusRequester,
                    mentionTransform = mentionTransform,
                    pasteAsFileEnabled = pasteAsFileEnabled,
                    pasteAsFileThreshold = pasteAsFileThreshold,
                    onTextChanged = onTextChanged,
                    onSend = onSend,
                    onNavigateInputHistory = onNavigateInputHistory,
                    onAddPastedTextAsDocument = onAddPastedTextAsDocument,
                    onExpand = { expanded = true },
                )
                // Mantenga presionado el campo de entrada para que aparezca el menú de acciones(Entrada al modo de entrada de pantalla completa)
                MuseAnchoredMenu(
                    expanded = showActionMenu,
                    onDismissRequest = { showActionMenu = false },
                ) {
                    MuseListItem(
                        onClick = {
                            showActionMenu = false
                            expanded = true
                        },
                        leadingContent = {
                            Icon(
                                imageVector = MuseIcons.maximize,
                                contentDescription = null,
                                modifier = Modifier.size(MuseIconSizes.iconMedium),
                            )
                        },
                        headlineContent = { Text(stringResource(R.string.chat_input_action_fullscreen)) },
                    )
                }
                // lado derecho: micrófono(Texto vacío y no se enviará ninguna imagen.) / enviar(cuando hay texto) / detenerse(Transmisión) / interponer(La entrada en la secuencia no está vacía.)
                val canInterject = text.isNotBlank() || pendingImages.isNotEmpty() || pendingVideo != null
                if (isStreaming && !canInterject) {
                    // dejar de generar:Botón redondo rojo sólido,Icono de parada blanco
                    val stopInteractionSource = remember { MutableInteractionSource() }
                    val isStopPressed by stopInteractionSource.collectIsPressedAsState()
                    val stopScale by animateFloatAsState(
                        targetValue = if (isStopPressed) 0.9f else 1f,
                        animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS),
                        label = "stopScale",
                    )
                    Box(
                        modifier = Modifier
                            .size(MuseIconSizes.touchTarget)
                            .clickable(
                                interactionSource = stopInteractionSource,
                                indication = null,
                                onClick = {
                                    MuseHaptics.medium(hapticFeedback)
                                    onStop()
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(MuseIconSizes.stopButton)
                                .graphicsLayer {
                                    scaleX = stopScale
                                    scaleY = stopScale
                                }
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isWaitingFirstToken) {
                                MuseSpinner(
                                    size = MuseIconSizes.iconSmallTiny,
                                    color = MaterialTheme.colorScheme.onError,
                                )
                            } else {
                                Icon(
                                    imageVector = MuseIcons.square,
                                    contentDescription = stringResource(R.string.chat_stop_generation_cd),
                                    tint = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                )
                            }
                        }
                    }
                } else if (isStreaming) {
                    // A8 + B2: Cuando la entrada en streaming no está vacía,A la derecha está "Ingresar al equipo".+Doble botón "interrumpir" —
                    // Unirse a la cola no interrumpe la generación,Interrupt interrumpe la compilación actual y la redirige.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                    ) {
                        // B2 botón unirse:Agregue la entrada actual a la cola para enviar(No interrumpir la generación),El botón enviar se agrega a la izquierda.
                        val enqueueInteractionSource = remember { MutableInteractionSource() }
                        val enqueuePressed by enqueueInteractionSource.collectIsPressedAsState()
                        val enqueueScale by animateFloatAsState(
                            targetValue = if (enqueuePressed) 0.9f else 1f,
                            animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS),
                            label = "enqueueScale",
                        )
                        Box(
                            modifier = Modifier
                                .size(MuseIconSizes.touchTarget)
                                .clickable(
                                    interactionSource = enqueueInteractionSource,
                                    indication = null,
                                    enabled = true,
                                    onClick = {
                                        MuseHaptics.medium(hapticFeedback)
                                        onEnqueuePending()
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(MuseIconSizes.stopButton)
                                    .graphicsLayer {
                                        scaleX = enqueueScale
                                        scaleY = enqueueScale
                                    }
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = MuseIcons.stack,
                                    contentDescription = stringResource(R.string.chat_pending_enqueue_cd),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                )
                            }
                        }
                        // A8 botón de interrupción:Interrumpir la compilación actual y redirigir(Enviar posición del botón,Avión de papel de color principal.).
                        val interjectInteractionSource = remember { MutableInteractionSource() }
                        val interjectPressed by interjectInteractionSource.collectIsPressedAsState()
                        val interjectScale by animateFloatAsState(
                            targetValue = if (interjectPressed) 0.9f else 1f,
                            animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS),
                            label = "interjectScale",
                        )
                        Box(
                            modifier = Modifier
                                .size(MuseIconSizes.touchTarget)
                                .clickable(
                                    interactionSource = interjectInteractionSource,
                                    indication = null,
                                    enabled = true,
                                    onClick = {
                                        MuseHaptics.medium(hapticFeedback)
                                        onInterject()
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(MuseIconSizes.stopButton)
                                    .graphicsLayer {
                                        scaleX = interjectScale
                                        scaleY = interjectScale
                                    }
                                    .clip(CircleShape)
                                    .background(MuseActionColors.container),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = MuseIcons.send,
                                    contentDescription = stringResource(R.string.chat_interject_cd),
                                    tint = MuseActionColors.content,
                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                )
                            }
                        }
                    }
                } else if (text.isBlank() && pendingImages.isEmpty() && pendingVideo == null && showMic) {
                    // v1.26: El micrófono está unificado para presionar prolongadamente para hablar + deslizar hacia arriba para cancelar(No más distinción entre rutas API/Vosk,
                    //   Por ChatScreen en onStartRecording/onStopRecording/onCancelRecording en devolución de llamada
                    //   Decidir qué reconocedor usar;InputBar Sólo responsable de la interacción de gestos.)
                    val pulseScale by animateFloatAsState(
                        targetValue = if (isRecording && !reducedMotion) 1.25f else 1f,
                        animationSpec = if (isRecording && !reducedMotion) {
                            infiniteRepeatable(
                                animation = MuseMotion.tween(MuseAnimation.LOOP_NORMAL_MS, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse,
                            )
                        } else {
                            MuseMotion.tween(MuseAnimation.TACTILE_MS)
                        },
                        label = "micPulse",
                    )
                    // v1.79 (H-I2): Envuelva la devolución de llamada con RememberUpdatedState,
                    // evitar pointerInput(Unit) captura caducada lambda(key Sin reorganización cuando no se modifica)
                    val currentOnStart by rememberUpdatedState(onStartRecording)
                    val currentOnStop by rememberUpdatedState(onStopRecording)
                    val currentOnCancel by rememberUpdatedState(onCancelRecording)
                    Box(
                        modifier = Modifier
                            .size(MuseIconSizes.touchTarget)
                            .pointerInput(Unit) {
                                // v1.79 (M-I10): Umbral de cancelación de deslizamiento hacia arriba reducido de 100 dp 48dp
                                // (PointerInputScope si Density,Puede ser directamente toPx)
                                val slideThresholdPx = MuseIconSizes.touchTarget.toPx()
                                awaitPointerEventScope {
                                    val down = awaitFirstDown()
                                    // Realmente tienes que mantener presionado para comenzar a grabar.;Una pulsación breve ya no activa accidentalmente el ASR del sistema ni carga audio vacío.
                                    val longPress = awaitLongPressOrCancellation(down.id)
                                    if (longPress == null) return@awaitPointerEventScope
                                    // Mantenga presionado para comenzar a grabar;Si el modelo no está listo/Permiso no concedido,
                                    // onStartRecording Regresar false,Salir directamente sin entrar en el bucle de gestos.
                                    val started = currentOnStart()
                                    if (!started) {
                                        // v1.98: Eliminar mensaje de brindis,Procesamiento silencioso
                                        return@awaitPointerEventScope
                                    }
                                    MuseHaptics.medium(hapticFeedback)
                                    var cancelled = false
                                    try {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            // v1.79 (H-I3): Utilice firstOrNull en su lugar para evitar límites
                                            val change = event.changes.firstOrNull() ?: continue
                                            // Deslizar hacia arriba supera el umbral → Cancelar(Renunciar a este resultado de reconocimiento.)
                                            if (down.position.y - change.position.y > slideThresholdPx) {
                                                cancelled = true
                                                break
                                            }
                                            // Déjalo ir → Dejar de registrar y procesar los resultados.
                                            // (Incluye clics rápidos:Prensa breve y comunicado breve para ir aquí,Procesar la grabación normalmente)
                                            if (change.changedToUp()) break
                                        }
                                    } finally {
                                        // v1.79 (H-I1): try/finally Composable Garantizado al salir de la composición
                                        // Los recursos de grabación también se pueden liberar cuando se cancela la rutina.
                                        if (cancelled) {
                                            currentOnCancel()
                                            showCancelledHint = true
                                        } else {
                                            currentOnStop()
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        val recognizingCd = stringResource(R.string.chat_recognizing_cd)
                        val recordingCd = stringResource(R.string.chat_recording_cd)
                        val holdToRecordCd = stringResource(R.string.chat_hold_to_record_cd)
                        when {
                            // v1.91: Stopping(Acabado)mostrar loading,Los resultados se han completado durante la escucha en modo streaming.
                            asrStatus == ASRStatus.Stopping -> MuseSpinner(
                                modifier = Modifier
                                    .semantics { contentDescription = recognizingCd },
                                size = MuseIconSizes.iconMedium,
                            )
                            // Tarea 1: Reconnecting(Desconectado y reconectándose)mostrar loading,Indica al usuario que la red se está recuperando.
                            asrStatus == ASRStatus.Reconnecting -> MuseSpinner(
                                modifier = Modifier
                                    .semantics { contentDescription = recognizingCd },
                                size = MuseIconSizes.iconMedium,
                            )
                            isRecording -> Icon(
                                imageVector = MuseIcons.microphone,
                                contentDescription = recordingCd,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .size(MuseIconSizes.iconMedium)
                                    .scale(pulseScale),
                            )
                            else -> Box(
                                // v2.4.5 (Alineación de la imagen de referencia): El micrófono cambia a un ícono + de fondo redondo y claro.
                                modifier =
                                Modifier
                                    .size(MuseIconSizes.stopButton)
                                    .clip(CircleShape)
                                    .background(MuseActionColors.neutralContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = MuseIcons.microphone,
                                    contentDescription = holdToRecordCd,
                                    tint = MuseActionColors.mutedContent,
                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                )
                            }
                        }
                    }
                } else {
                    // boton enviar:Botón redondo sólido verde laurel,Icono de avión de papel blanco
                    val sendInteractionSource = remember { MutableInteractionSource() }
                    val sendPressed by sendInteractionSource.collectIsPressedAsState()
                    val sendScale by animateFloatAsState(
                        targetValue = if (sendPressed) 0.9f else 1f,
                        animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS),
                        label = "sendScale",
                    )
                    val canSend = text.isNotBlank() || pendingImages.isNotEmpty() || pendingVideo != null
                    Box(
                        modifier = Modifier
                            .size(MuseIconSizes.touchTarget)
                            .clickable(
                                interactionSource = sendInteractionSource,
                                indication = null,
                                enabled = canSend,
                                onClick = {
                                    MuseHaptics.medium(hapticFeedback)
                                    onSend()
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier =
                            Modifier
                                // v2.4.5 (Alineación de la imagen de referencia): El círculo visual de la tecla de envío se reduce para que sea coherente con la tecla correcta.
                                .size(MuseIconSizes.stopButton)
                                .graphicsLayer {
                                    scaleX = sendScale
                                    scaleY = sendScale
                                }
                                .clip(CircleShape)
                                .background(
                                    // UI-FIX A: se puede enviar=Sólido, no enviable=Base neutra opaca (no más 38% de color principal translúcido)
                                    if (canSend) {
                                        MuseActionColors.container
                                    } else {
                                        MuseActionColors.neutralContainer
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                // v2.6.4: Vuelva a cambiar la tecla de envío a avión de papel (comentarios de los usuarios: la flecha hacia arriba no es el efecto deseado).
                                imageVector = MuseIcons.send,
                                contentDescription = stringResource(R.string.action_send),
                                tint = if (canSend) MuseActionColors.content else MuseActionColors.mutedContent,
                                modifier = Modifier.size(MuseIconSizes.iconSmall),
                            )
                        }
                    }
                }
            }
        }

        // Grabación/identificar/Cancelar barra de aviso de estado
        // v1.91: Stopping(Acabado)mostrar loading;isRecording Contiene Connecting/Listening/Stopping/Reconnecting,
        // Pero Detener/Reconectar se emparejará primero LoadingDots,Listening/Connecting Simplemente toma la rama de forma de onda.
        if (isRecording || asrStatus == ASRStatus.Stopping ||
            (asrStatus == ASRStatus.Error && !asrErrorMessage.isNullOrBlank()) || showCancelledHint
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MusePaddings.tightGap),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    asrStatus == ASRStatus.Stopping -> LoadingDots(text = stringResource(R.string.voice_recognizing))
                    // Tarea 1: Reconnecting(Desconectado y reconectándose)mostrar"Anormalidad de la red,Reconectando…",Alertar a los usuarios sobre problemas de red
                    asrStatus == ASRStatus.Reconnecting -> LoadingDots(text = stringResource(R.string.voice_reconnecting))
                    // v1.0.4 (P2): Connecting El escenario aún no ha comenzado a grabar.,mostrar"Conexión al servicio de identificación…"en lugar de una forma de onda en blanco
                    // (isRecording contiene Connecting,Primero debe hacer coincidir el cortocircuito Conexión para caer a la rama correcta)
                    asrStatus == ASRStatus.Connecting -> LoadingDots(text = stringResource(R.string.voice_connecting))
                    asrStatus == ASRStatus.Error -> Text(
                        text = asrErrorMessage ?: stringResource(R.string.chat_recording_start_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    isRecording -> {
                        RecordingWaveform(amplitudes = recordingAmplitudes)
                        Spacer(modifier = Modifier.width(MusePaddings.contentGap))
                        Text(
                            text = stringResource(R.string.voice_release_to_recognize),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    showCancelledHint -> Text(
                        text = stringResource(R.string.chat_cancelled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Barra de ondas de grabación:Representa el historial de amplitud reciente como barras verticales.
 * v1.91: La amplitud cambió a normalizada. Float(0-1f),Ya no es necesario dividir por 32768.
 */
/**
 * v0.52: @mention Resalte la transición.
 *
 * El cuadro de entrada se inserta después de seleccionar la base de conocimientos. "@Nombre del documento" texto plano(Ver la base de conocimientos de ChatScreen sheet),
 * Esta transformación visual pone "@Nombre del documento" Teñir al color primario + negrita media,
 * Sirve como señal visual para hacer referencia a la base de conocimientos. No cambies el texto subyacente.(El contenido enviado permanece sin cambios.).
 *
 * Reglas coincidentes:`@` seguido por el chino/ingles/numeros/Secuencia de subrayado.
 */
private class MentionHighlightTransformation(
    private val highlightColor: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        // v1.79 (L-I5): Apretar el regular,Sólo coincide con @ seguido de chino/ingles/numeros/subrayado,Evite resaltar accidentalmente buzones, etc.
        // v1.131: Regex Constante a nivel de archivo MENTION_HIGHLIGHT_REGEX,Evite crear un nuevo filtro cada vez que se reorganice.
        if (!text.text.contains('@')) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val builder = AnnotatedString.Builder(text.text)
        MENTION_HIGHLIGHT_REGEX.findAll(text.text).forEach { match ->
            builder.addStyle(
                SpanStyle(color = highlightColor, fontWeight = FontWeight.Medium),
                match.range.first,
                match.range.last + 1,
            )
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

/**
 * Formato de duración del vídeo:milisegundos → "M:SS"(Más de 1 hora "H:MM:SS").
 * Solo para vista previa de la interfaz de usuario,Sin dependencias de lógica de negocios.
 */
private fun formatVideoDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "0:00"
    val totalSec = durationMs / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * v2.6.13: Subtítulo del chip accesorio.
 *
 * Los archivos adjuntos ordinarios se cambiaron a "el documento original se coloca en el espacio de trabajo + una cita de una línea en el mensaje". El texto ya no se lee, por lo que se muestra primero.
 * Tamaño del archivo; solo se mostrará el recuento de palabras para los archivos adjuntos de texto sin formato (se ha leído el texto principal) y, cuando ninguno de los dos esté disponible, volverá al final de la ruta de referencia.
 */
@Composable
private fun docChipSubtitle(doc: io.zer0.muse.ui.chat.PendingDocument): String {
    val subtitle = when {
        doc.charCount > 0 -> stringResource(R.string.chat_doc_char_count, doc.charCount)
        doc.sizeBytes > 0 -> io.zer0.common.FileSizeFormatter.format(doc.sizeBytes)
        else -> doc.workspacePath.orEmpty()
    }
    return subtitle
}

/**
 * v1.0.47 P5-4: Subcomponente del cuadro de entrada de mensajes — Renderizar aislamiento.
 *
 * de [InputBar] independencia extraída Composable,Encapsulación:
 *  - marca de borrador
 *  - TextField(Contiene resaltado de @mención, presione Intro para enviar y flecha de navegación por el historial.)
 *  - Detección de pegado de texto largo + cuadro de diálogo de conversión de archivos
 *  - Botón expandir(Entrada de pantalla completa)
 *  - Token Botón de contar(Sólo cuando tokenEstimateEnabled)
 *
 * Principio de aislamiento de renderizado:
 *  - Compose El compilador genera código que se puede omitir para Composables con argumentos estables.
 *  - Cuando cambian otros parámetros de InputBar(Como por ejemplo toolCallCompleted/isStreaming)tiempo,
 *    Mientras los parámetros de MessageInputField permanezcan sin cambios,no se volverá a montar
 *  - por el contrario,cuando el texto cambia,Sólo este componente está reorganizado.,InputBar herramientas en Sheet/
 *    Vista previa de la imagen/Chip de documento, etc.(Extraído a Composable independiente o si es un bloque)no afectado
 *
 * estado interno(Detección de pegado)Con gestión del ciclo de vida de los componentes,Se restablece automáticamente cuando se reorganiza la barra de entrada al cambiar de sesión.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowScope.MessageInputField(
    text: String,
    isStreaming: Boolean,
    isDrawMode: Boolean,
    enterToSend: Boolean,
    showExpandButton: Boolean,
    focusRequester: FocusRequester,
    mentionTransform: VisualTransformation,
    pasteAsFileEnabled: Boolean,
    pasteAsFileThreshold: Int,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onNavigateInputHistory: (Int) -> Unit,
    onAddPastedTextAsDocument: (String) -> Unit,
    onExpand: () -> Unit,
) {
    // ── Estado de detección de pegado de texto largo ──────────────────────────────────────────
    // v1.0.47 P5-2: Grabar el último texto ingresado,Se utiliza para determinar si se pega una sección grande.
    var prevInputText by remember { mutableStateOf(text) }
    // Pegar contenido por confirmar(newText=Texto nuevo completo, inserted=Fragmento pegado extraído)
    var pendingPaste by remember { mutableStateOf<Pair<String, String>?>(null) }

    /**
     * manejo de cambios de entrada,Pegado de bloques de grandes secciones.
     * Pegar detectado(Número de personajes nuevos >= [pasteAsFileThreshold])Almacenado temporalmente para confirmación,La ventana emergente permite a los usuarios elegir
     * "Adjuntar como archivo" o "Pegar directamente",En lugar de insertarlo directamente en el cuadro de entrada.
     */
    fun handleInputChange(newText: String) {
        // v1.0.74 fix: Descartar silenciosamente cuando se exceda el límite superior,Los usuarios piensan que no hay respuesta al escribir;Cambiar para truncar y continuar escribiendo
        if (newText.length > INPUT_TEXT_MAX_LENGTH) {
            val truncated = newText.take(INPUT_TEXT_MAX_LENGTH)
            if (truncated == prevInputText) return
            prevInputText = truncated
            onTextChanged(truncated)
            return
        }
        val delta = newText.length - prevInputText.length
        if (pasteAsFileEnabled && delta >= pasteAsFileThreshold && pendingPaste == null) {
            val inserted = extractInsertedSegment(prevInputText, newText)
            if (inserted.length >= pasteAsFileThreshold) {
                pendingPaste = newText to inserted
                return // No actualizar inmediatamente text,Espere la selección de usuario
            }
        }
        prevInputText = newText
        onTextChanged(newText)
    }

    // Entrada clara externa(enviar/Cambiar de sesión/Navegación histórica)Sincronización horaria prevInputText,evitar errores de juicio
    LaunchedEffect(text) {
        if (pendingPaste == null) prevInputText = text
    }

    // ── marca de borrador ─────────────────────────────────────────────────────
    // v2.6.7: Eliminado. Draft solo es responsable de "aplicaciones relacionadas"/"La sesión no se perderá" y no quedarán rastros en la interfaz.
    // Requisitos del usuario: no se requieren mensajes ni respuestas especiales, simplemente haga que el cuadro de entrada sea persistente.

    // ── TextField ────────────────────────────────────────────────────
    // Introduce la clave para enviar(enterToSend Cuando se enciende,Enter enviar,Shift+Enter nueva línea)
    MuseTextField(
        value = text,
        onValueChange = { handleInputChange(it) },
        // v1.0.72: El fondo del cuadro de entrada es transparente — El fondo de la isla es el contenedor.,Evite la superposición de bloques de colores sólidos en los cuadros de entrada"bloque blanco"
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier
            .weight(1f)
            .heightIn(min = MuseIconSizes.inputMinHeight, max = MusePaddings.maxMessageFieldHeight)
            .focusRequester(focusRequester)
            .onKeyEvent { event ->
                if (enterToSend && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (event.nativeKeyEvent.metaState and KeyEvent.META_SHIFT_ON) == 0
                ) {
                    if (text.isNotBlank() && !isStreaming) {
                        onSend()
                    }
                    true
                } else if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                    // Reparación de auditoría (8.6): Permitir el movimiento del cursor cuando se utilizan varias líneas de texto,No secuestrar la navegación para la historia
                    !text.contains('\n')
                ) {
                    // flecha arriba → El historial de entrada pasa a ser más antiguo(Solo escenario de entrada de una sola línea)
                    onNavigateInputHistory(-1)
                    true
                } else if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                    // Reparación de auditoría (8.6): Igual que arriba,No secuestrar cuando se utilizan varias líneas
                    !text.contains('\n')
                ) {
                    // flecha hacia abajo → El historial de entrada se mueve para actualizarse
                    onNavigateInputHistory(1)
                    true
                } else {
                    false
                }
            },
        placeholder = {
            // El cuadro de entrada vacío debe dar un mensaje de entrada: antes estaba completamente en blanco y los nuevos usuarios no sabían que podían escribir aquí.
            // El texto debe utilizar frases cortas y desaparecer automáticamente cuando haya contenido, por lo que no ocupará espacio horizontal durante mucho tiempo.
            Text(
                text = if (isDrawMode) {
                    stringResource(R.string.chat_placeholder_draw)
                } else {
                    stringResource(R.string.chat_placeholder_simple)
                },
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        keyboardOptions = KeyboardOptions(
            imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default,
        ),
        keyboardActions = KeyboardActions(
            onSend = {
                if (text.isNotBlank() && !isStreaming) {
                    onSend()
                }
            },
        ),
        visualTransformation = mentionTransform,
    )

    // ── Botón expandir(Entrada de pantalla completa) ──────────────────────────────────────────
    if (showExpandButton && !isStreaming) {
        MuseTactileButton(
            icon = MuseIcons.maximize,
            onClick = onExpand,
            contentDescription = stringResource(R.string.chat_expand_input_cd),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = MuseIconSizes.touchTarget,
            iconSize = MuseIconSizes.iconMedium,
        )
    }

    // ── Cuadro de diálogo de confirmación de pegado de bloque grande ──────────────────────────────────────────
    val pending = pendingPaste
    if (pending != null) {
        val (fullText, inserted) = pending
        val charCount = inserted.length
        MuseDialog(
            onDismissRequest = {
                // Cancelar:No pegar ni transferir archivos,Mantener la entrada original
                pendingPaste = null
            },
            title = stringResource(R.string.chat_paste_as_file_title),
            content = {
                Text(
                    stringResource(R.string.chat_paste_as_file_msg, charCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmText = stringResource(R.string.chat_paste_as_file_attach),
            onConfirm = {
                onAddPastedTextAsDocument(inserted)
                prevInputText = fullText.removeRange(
                    fullText.indexOf(inserted),
                    fullText.indexOf(inserted) + inserted.length,
                )
                pendingPaste = null
            },
            dismissText = stringResource(R.string.chat_paste_as_file_paste),
            onDismiss = {
                prevInputText = fullText
                onTextChanged(fullText)
                pendingPaste = null
            },
        )
    }
}

/**
 * F-3: Botón único de la barra de herramientas de acceso directo(Cuadrado pequeño con esquinas redondeadas.,El estado activo está resaltado con color primario.).
 */
@Composable
private fun QuickBarAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    // v2.x: efectos interactivos — Presione para comprimir y expandir + transición de color activa(Comentarios de autodibujo,sin ondulaciones)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS),
        label = "quickBarPress",
    )
    val bgColor by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = MuseMotion.tween(MuseAnimation.TACTILE_MS),
        label = "quickBarBg",
    )
    val fgColor by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = MuseMotion.tween(MuseAnimation.TACTILE_MS),
        label = "quickBarFg",
    )
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = fgColor,
            modifier = Modifier.size(MuseIconSizes.iconMedium),
        )
    }
}

/**
 * v2.4.5: Finalización en línea del comando de barra diagonal — entrada `/` comienzo(Sin espacios)Los candidatos aparecen encima de la isla de entrada.
 *
 * anteriormente [io.zer0.muse.ui.chat.SlashCommand.allCommandNames] Sólo definido, nunca llamado por ninguna UI,
 * entrada del usuario `/` No puedo ver ninguna indicación;Aquí la tabla de comandos está conectada nuevamente al campo de entrada.
 * La descripción del comando reutiliza la existente `slash_command_*` Recursos(Cero traducciones nuevas).
 */
@Composable
private fun SlashCommandSuggestions(
    commands: List<io.zer0.muse.ui.chat.SlashCommand>,
    onPick: (io.zer0.muse.ui.chat.SlashCommand) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(vertical = MusePaddings.tightGap)) {
            commands.take(6).forEach { cmd ->
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onPick(cmd) },
                        )
                        .padding(horizontal = MusePaddings.screen, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(cmd.descriptionResId),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
