@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "TooManyFunctions",
    "UseCheckOrError",
)

@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package io.zer0.muse.ui.groupchat

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.ui.AssistantVideoCard
import io.zer0.muse.ui.SmartImage
import io.zer0.muse.ui.chat.StickerAwareMarkdownBody
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseSlider
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.formatMessageTime
import io.zer0.muse.ui.theme.MuseAvatarSize
import io.zer0.muse.ui.theme.MuseBubbleStyles
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.ByteArrayOutputStream

/**
 * Burbuja de mensaje de chat grupal — distinguir user(lado derecho)/ assistant(Izquierda + avatar).
 *
 * @param message entidad de mensaje
 * @param assistants Lista de todos los asistentes(Para encontrar avatares)
 * @param chatPrefs Preferencias de chat(Controlar la visualización del estado de ánimo/razonamiento y la expansión predeterminada)
 * @param isMoodExpanded mood Estado de expansión controlada fuera del bloque.(null Indica que se utiliza el valor predeterminado)
 * @param isReasoningExpanded reasoning Estado de expansión controlada fuera del bloque.(null Indica que se utiliza el valor predeterminado)
 * @param onToggleMoodExpanded mood Devolución de llamada de alternancia de expansión de bloque
 * @param onToggleReasoningExpanded reasoning Devolución de llamada de alternancia de expansión de bloque
 */
@Composable
internal fun GroupChatMessageBubble(
    message: GroupChatMessageEntity,
    assistants: List<AssistantEntity>,
    chatPrefs: io.zer0.muse.data.ChatPreferences = io.zer0.muse.data.ChatPreferences(),
    isMoodExpanded: Boolean? = null,
    isReasoningExpanded: Boolean? = null,
    onToggleMoodExpanded: () -> Unit = {},
    onToggleReasoningExpanded: () -> Unit = {},
    // v1.77: Mantenga presionado para que aparezca el menú de operación.(copiar/eliminar)
    onLongClick: (Rect, Offset?) -> Unit = { _, _ -> },
    // v1.0.74 fix (Auditoría inicial 1.4): Resaltado de salto de búsqueda — El mensaje de destino se resalta brevemente para solicitar al usuario que lo localice.
    highlighted: Boolean = false,
    // v1.x: Modo de selección múltiple
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onSelectToggle: () -> Unit = {},
    /** HTML/SVG Bloqueo de código de devolución de llamada de vista previa de pantalla completa. */
    onHtmlPreview: (String) -> Unit = {},
    /**
     * Devolución de llamada de devolución de tarjeta.
     *
     * Las tarjetas en el chat grupal y en el chat individual están implementadas en el mismo conjunto de tarjetas, las cuales no deben usarse porque“en el chat grupal”se convierte en una imagen estática ——
     * cambiar la tarjeta/control deslizante/Si el botón no se mueve, el usuario simplemente pensará que está roto. Reciba la devolución de llamada aquí,
     * La capa de chat grupal completará la respuesta como un mensaje de chat grupal (el contexto del chat grupal es el chat grupal y no debe saltar al chat individual).
     */
    onCardAction: ((io.zer0.muse.ui.markdown.CardAction) -> Unit)? = null,
    /** Host-owned bubble skin; null keeps the existing group-chat appearance. */
    bubbleSkin: io.zer0.muse.ui.theme.BubbleSkin? = null,
) {
    val isUser = message.senderType == "user"
    // Resolve once per role/theme so user and agent bubbles share the same resolver
    // and fallback path as the single-chat renderer.
    val resolvedSkin = bubbleSkin?.let { skin ->
        io.zer0.muse.ui.theme.BubbleSkinResolver.resolve(
            skin,
            if (isUser) {
                io.zer0.muse.ui.theme.BubbleRole.USER
            } else {
                io.zer0.muse.ui.theme.BubbleRole.GROUP_ASSISTANT
            },
            darkTheme = androidx.compose.foundation.isSystemInDarkTheme(),
        )
    }
    val haptic = LocalHapticFeedback.current
    var messageBounds by remember { mutableStateOf(Rect.Zero) }
    var pressPointLocal by remember { mutableStateOf(Offset.Zero) }
    val interactionSource = remember { MutableInteractionSource() }
    // v1.0.74 fix (Auditoría inicial 6.1): Ancho máximo de burbuja según el ancho de la pantalla 70%,pantalla grande/pantalla horizontal/Las fuentes grandes ya no son demasiado estrechas
    val maxBubbleWidth = with(androidx.compose.ui.platform.LocalConfiguration.current) {
        (screenWidthDp * 0.7f).dp
    }
    // v1.x: En el modo de selección múltiple, haga clic en el mensaje para cambiar de selección,Mantenga pulsado para salir del menú
    val bubbleClick: () -> Unit = { if (selectionMode) onSelectToggle() }
    val pressTracker = Modifier.pointerInput(message.id) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            pressPointLocal = down.position
            waitForUpOrCancellation()
        }
    }
    val pressPointInWindow = pressPointLocal.takeIf {
        it != Offset.Zero && messageBounds != Rect.Zero
    }?.let { point ->
        Offset(messageBounds.left + point.x, messageBounds.top + point.y)
    }
    if (isUser) {
        // Mensajes de usuario:burbuja derecha
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // v1.0.74 fix: Resaltado de salto de búsqueda
                .then(
                    if (highlighted) {
                        Modifier
                            .clip(MuseShapes.semiLarge)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                    } else {
                        Modifier
                    },
                ),
            horizontalArrangement = Arrangement.End,
        ) {
            Column(
                // v1.0.74 fix (Auditoría inicial 6.1): Ancho de pantalla 70% en lugar de fijo 280dp
                modifier = Modifier.widthIn(max = maxBubbleWidth),
                horizontalAlignment = Alignment.End,
            ) {
                // v1.0.29: combinedClickable Mover a la superficie,Evite eventos táctiles que consuman MarkdownText/Text
                // Causa que la capa de fila combinada en la que se puede hacer clic no se active cuando se presiona durante mucho tiempo(Solo puedes mantener presionada la zona vacía)
                Surface(
                    shape = resolvedSkin?.style?.radiusDp?.dp
                        ?.let { androidx.compose.foundation.shape.RoundedCornerShape(it) }
                        ?: MuseBubbleStyles.userBubbleShape(),
                    color = resolvedSkin?.let { androidx.compose.ui.graphics.Color(it.style.surfaceArgb) }
                        ?: MuseBubbleStyles.userSurfaceColor(),
                    border = if (selected) {
                        androidx.compose.foundation.BorderStroke(
                            1.5.dp,
                            MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        null
                    },
                    modifier = Modifier
                        .then(pressTracker)
                        .onGloballyPositioned { coordinates ->
                            messageBounds = coordinates.boundsInWindow()
                        }
                        .combinedClickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = bubbleClick,
                            onLongClick = {
                                MuseHaptics.medium(haptic)
                                onLongClick(messageBounds, pressPointInWindow)
                            },
                        ),
                ) {
                    Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                        // v2.x: marca de susurro
                        if (message.whisperTargetId != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 4.dp),
                            ) {
                                Icon(
                                    imageVector = MuseIcons.lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(12.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.groupchat_message_whisper),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                        Text(
                            text = message.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        // Adjunto de imagen
                        MessageImageGrid(
                            imageBase64Json = message.imageBase64Json,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                // CHAT-04: Agregar marca de tiempo a los mensajes de los usuarios del chat grupal(Igual que el chat individual,Alinear a la derecha)
                if (chatPrefs.showTimestamp) {
                    Text(
                        text = formatMessageTime(message.timestamp, use24Hour = chatPrefs.use24Hour),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 2.dp, end = MusePaddings.tinyGap),
                    )
                }
            }
        }
    } else {
        // Agent noticias:Izquierda + avatar + senderName
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // v1.0.74 fix: Resaltado de salto de búsqueda
                .then(
                    if (highlighted) {
                        Modifier
                            .clip(MuseShapes.semiLarge)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                    } else {
                        Modifier
                    },
                ),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            // avatar(También puedes mantener presionado para activar el menú.)
            val assistant = remember(message.senderId, assistants) {
                assistants.find { it.id == message.senderId }
            }
            val avatarInteraction = remember { MutableInteractionSource() }
            if (assistant != null) {
                Box(
                    modifier = Modifier
                        .size(MuseAvatarSize.inline)
                        .combinedClickable(
                            interactionSource = avatarInteraction,
                            indication = null,
                            onClick = {},
                            onLongClick = {
                                MuseHaptics.medium(haptic)
                                onLongClick(messageBounds, pressPointInWindow)
                            },
                        ),
                ) {
                    AssistantAvatar(
                        assistant = assistant,
                        avatarSize = MuseAvatarSize.inline,
                    )
                }
            } else {
                // Ten todo en cuenta:Avatar redondo de letra inicial
                Box(
                    modifier = Modifier
                        .size(MuseAvatarSize.inline)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .combinedClickable(
                            interactionSource = avatarInteraction,
                            indication = null,
                            onClick = {},
                            onLongClick = {
                                MuseHaptics.medium(haptic)
                                onLongClick(messageBounds, pressPointInWindow)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = message.senderName.firstOrNull()?.toString() ?: "A",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // Burbujas
            // v1.0.74 fix (Auditoría inicial 6.1): Ancho de pantalla 70% en lugar de fijo 280dp
            Column(modifier = Modifier.widthIn(max = maxBubbleWidth)) {
                Text(
                    // CHAT-04: Agregar marca de tiempo a los mensajes del agente del chat grupal(Igual que el chat individual)
                    text = if (chatPrefs.showTimestamp) {
                        "${message.senderName} · ${formatMessageTime(message.timestamp, use24Hour = chatPrefs.use24Hour)}"
                    } else {
                        message.senderName
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
                // v1.46: MOOD etiquetar las cápsulas(Agent Manuscrito interno,plegable)
                if (chatPrefs.showMoodBlock) {
                    message.mood?.takeIf { it.isNotBlank() }?.let { mood ->
                        val moodExpanded = isMoodExpanded ?: chatPrefs.moodExpandedByDefault
                        MoodCapsule(
                            mood = mood,
                            expanded = moodExpanded,
                            onToggle = onToggleMoodExpanded,
                        )
                    }
                }
                // v1.46: bloques del proceso de pensamiento(plegable)
                if (chatPrefs.showReasoning) {
                    message.reasoning?.takeIf { it.isNotBlank() }?.let { reasoning ->
                        val reasoningExpanded = isReasoningExpanded ?: chatPrefs.reasoningExpandedByDefault
                        GroupChatExpandableBlock(
                            title = stringResource(R.string.groupchat_reasoning),
                            content = reasoning,
                            expanded = reasoningExpanded,
                            onToggle = onToggleReasoningExpanded,
                            titleColor = MaterialTheme.colorScheme.outline,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                }
                // v1.0.29: combinedClickable Mover a la superficie,Evite que MarkdownText consuma eventos táctiles
                Surface(
                    shape = resolvedSkin?.style?.radiusDp?.dp
                        ?.let { androidx.compose.foundation.shape.RoundedCornerShape(it) }
                        ?: MuseBubbleStyles.assistantBubbleShape(),
                    color = resolvedSkin?.let { androidx.compose.ui.graphics.Color(it.style.surfaceArgb) }
                        ?: MuseBubbleStyles.assistantSurfaceColor(),
                    border = if (selected) {
                        androidx.compose.foundation.BorderStroke(
                            1.5.dp,
                            MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        null
                    },
                    modifier = Modifier
                        .then(pressTracker)
                        .onGloballyPositioned { coordinates ->
                            messageBounds = coordinates.boundsInWindow()
                        }
                        .combinedClickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = bubbleClick,
                            onLongClick = {
                                MuseHaptics.medium(haptic)
                                onLongClick(messageBounds, pressPointInWindow)
                            },
                        ),
                ) {
                    Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                        // v2.x: Etiquetas de memes [[sticker:Clasificación]] → Tarjeta con imagen pura(El chat grupal y el chat individual están en el mismo proceso)
                        StickerAwareMarkdownBody(
                            text = message.body,
                            stickerSeed = message.id.hashCode().toLong(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            onHtmlPreview = onHtmlPreview,
                            onCardAction = onCardAction,
                            cardId = message.id,
                        )
                        // Adjunto de imagen
                        MessageImageGrid(
                            imageBase64Json = message.imageBase64Json,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        // v2.x cierre del legado:El chat grupal genera tarjeta de resultados de video(generate_video escrito videoFileUri)
                        // Reutilizar chat único AssistantVideoCard,Haga clic para abrir el reproductor del sistema.
                        message.videoFileUri?.takeIf { it.isNotBlank() }?.let { videoUri ->
                            AssistantVideoCard(
                                videoUri = videoUri,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * MOOD etiquetar las cápsulas — fondo verde claro,Texto en color de la marca,Puede ampliar para ver el manuscrito completo.
 */
@Composable
internal fun MoodCapsule(mood: String, expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MuseShapes.medium,
        modifier = Modifier
            .padding(bottom = 6.dp)
            .clickable { onToggle() },
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "MOOD",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // ST-10 (A11Y-03): En fuentes grandes, el título ocupa el ancho restante y no puede tener más de 2 líneas.,Evite presionar el ícono de expandir a la derecha
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                    contentDescription = if (expanded) {
                        stringResource(R.string.groupchat_collapse)
                    } else {
                        stringResource(R.string.groupchat_expand)
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = mood,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Bloque plegable de chat grupal — Se utiliza para el estado de ánimo/proceso de pensamiento.
 */
@Composable
internal fun GroupChatExpandableBlock(
    title: String,
    content: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    titleColor: Color,
    containerColor: Color,
) {
    Surface(
        color = containerColor,
        shape = MuseShapes.medium,
        tonalElevation = 0.dp,
        modifier = Modifier
            .widthIn(max = 360.dp)
            .padding(bottom = 6.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(vertical = MusePaddings.tinyGap),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = titleColor,
                    // ST-10 (A11Y-03): Igual que arriba — El título puede ajustarse + límite de 2 líneas,El icono no está comprimido.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                    contentDescription = if (expanded) {
                        stringResource(R.string.groupchat_collapse)
                    } else {
                        stringResource(R.string.groupchat_expand)
                    },
                    tint = titleColor,
                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Cuadrícula de imagen de mensaje — Mostrar usuarios dentro de burbujas/Agent Enviar imágenes adjuntas.
 */
@Composable
internal fun MessageImageGrid(imageBase64Json: String, modifier: Modifier = Modifier) {
    val images = remember(imageBase64Json) {
        // L4: Utilice resultOf en su lugar runCatching,getOrNull sustituto getOrDefault
        resultOf {
            AppJson.decodeFromString(ListSerializer(String.serializer()), imageBase64Json)
        }.getOrNull() ?: emptyList()
    }
    if (images.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        items(images, key = { it }) { image ->
            SmartImage(
                model = image,
                contentDescription = stringResource(R.string.groupchat_image),
                modifier = Modifier
                    .size(120.dp)
                    .clip(MuseShapes.semiLarge),
            )
        }
    }
}

/**
 * "pensando..."estado de espera — Agent Se muestra durante la respuesta.
 * v1.104: Si currentSpeaker no está vacío,mostrar"XXX pensando..."y resaltar su foto de perfil;
 *         Volver a general cuando esté vacío"pensando..."(compatible con versiones anteriores).
 */
@Composable
internal fun ThinkingIndicator(currentSpeaker: AssistantEntity? = null) {
    // comentarios de prensa:No más avatares redondos + “pensando…”Las burbujas ocupan una línea entera. —— Habrá una línea adicional encima del campo de entrada.
    // “quien esta respondiendo”mensaje de texto. Ahora simplemente deja un círculo al lado del nombre del orador.,El estado de pensamiento es claro de un vistazo.
    val displayName = currentSpeaker?.name?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.groupchat_streaming_title)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = displayName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = MusePaddings.tightGap),
        )
        Spacer(Modifier.width(MusePaddings.contentGap))
        MuseSpinner(
            size = 12.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * v2.x: Juicio de plegado redondo — Regresar (punto de partida del segmento, Duan Chang, ¿Es la sección media?) o null(miembros no consecutivos de la sección asistente).
 *
 * segmento asistente continuo ≥4 tiempo:Mantenga los primeros 2 artículos + el último artículo,Métete en el medio,
 * por [CollapsedRoundBar] proporcionar"Haga clic para ampliar"; Leer largas rondas de discusión ya no consume la pantalla.
 */
internal fun groupCollapseSegment(messages: List<GroupChatMessageEntity>, index: Int): Triple<Int, Int, Boolean>? {
    val m = messages.getOrNull(index) ?: return null
    if (m.senderType != "assistant" || m.messageType != "normal") return null
    var start = index
    while (start > 0) {
        val p = messages[start - 1]
        if (p.senderType == "assistant" && p.messageType == "normal") start-- else break
    }
    var end = index
    while (end < messages.size - 1) {
        val n = messages[end + 1]
        if (n.senderType == "assistant" && n.messageType == "normal") end++ else break
    }
    val isMiddle = index >= start + 2 && index < end
    return Triple(start, end - start + 1, isMiddle)
}

/** v2.x: Barra de aviso plegable redonda — Colapso a mitad del discurso,Haga clic para ampliar. */
@Composable
internal fun CollapsedRoundBar(hiddenCount: Int, onExpand: () -> Unit) {
    Surface(
        onClick = onExpand,
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen),
    ) {
        Text(
            text = stringResource(R.string.groupchat_collapsed_round, hiddenCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        )
    }
}

/**
 * v2.x: tarjeta de votacion — messageType=="vote" Representación exclusiva.
 *
 * Distinción visual entre votación y discusión.:traer"votar"Credencial + nombre del orador + tarjeta de contenido del ticket.
 */
@Composable
internal fun GroupVoteCard(message: GroupChatMessageEntity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen),
        verticalAlignment = Alignment.Top,
    ) {
        // "votar"insignia
        Surface(
            shape = MuseShapes.small,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Icon(
                    imageVector = MuseIcons.check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.groupchat_vote_card_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message.senderName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(2.dp))
            Surface(
                shape = MuseShapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
    }
}

/**
 * v2.x: Tarjeta de resumen de discusión — messageType=="summary" Representación exclusiva.
 *
 * El resumen ya no se muestra en burbujas normales.:Se modificó para incluir título, contenido y tres acciones de aterrizaje.(Copiar/Compartir/Guardar como documento compartido)
 * tarjetas estructuradas — maquillar"discutir → ejecutable"la última milla.
 * Después de guardar como documento compartido,Todos los agentes lo verán automáticamente en el mensaje del sistema durante la próxima discusión.
 */
@Composable
internal fun GroupSummaryCard(
    message: GroupChatMessageEntity,
    onCopy: () -> Unit = {},
    onShare: () -> Unit = {},
    onSaveAsSharedDoc: () -> Unit = {},
    onContinue: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // línea de título:icono + "Resumen de la discusión" + resumidor
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = MuseIcons.fileText,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.groupchat_summary_card_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = message.senderName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        // tarjeta de texto
        Surface(
            shape = MuseShapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = message.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(MusePaddings.cardInner),
            )
        }
        // fila de acción de aterrizaje
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MuseCapsuleButton(
                text = stringResource(R.string.groupchat_summary_copy),
                onClick = onCopy,
                variant = IosCapsuleButtonVariant.Text,
            )
            MuseCapsuleButton(
                text = stringResource(R.string.groupchat_summary_share),
                onClick = onShare,
                variant = IosCapsuleButtonVariant.Text,
            )
            MuseCapsuleButton(
                text = stringResource(R.string.groupchat_summary_save_doc),
                onClick = onSaveAsSharedDoc,
                variant = IosCapsuleButtonVariant.Secondary,
            )
            // v2.x: Continuar implementando — Devolver el resumen al cuadro de entrada,Los usuarios pueden agregar instrucciones o @miembros para continuar con el progreso.
            MuseCapsuleButton(
                text = stringResource(R.string.groupchat_summary_continue),
                onClick = onContinue,
                variant = IosCapsuleButtonVariant.Text,
            )
        }
    }
}

/**
 * Campo de entrada de chat grupal — Menú Plus + entrada de texto + botón enviar.
 *
 * @param text Texto de entrada actual
 * @param onTextChange Devolución de llamada de cambio de texto
 * @param onSend Enviar devolución de llamada
 * @param onOpenToolSheet Abrir devolución de llamada del menú plus
 * @param enabled ¿Está disponible?(Agent Deshabilitado durante la respuesta)
 * @param canSend ¿Es posible enviar
 * @param members Lista de miembros del chat grupal(@mention Para autocompletar)
 */
@Composable
internal fun GroupChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onOpenToolSheet: () -> Unit,
    onOpenMeetingSheet: () -> Unit = {},
    enabled: Boolean,
    canSend: Boolean,
    members: List<AssistantEntity> = emptyList(),
    /** v2.6.7: Se está generando el chat grupal —— La tecla de envío se convierte en la tecla de parada. */
    isGenerating: Boolean = false,
    /**
     * v2.6.7: Detenga la compilación actual.
     *
     * GroupChatViewModel.stopGeneration() Siempre existe pero no tiene llamadas y no hay entrada en la interfaz.
     * El usuario sólo puede esperar después de realizar la solicitud.
     */
    onStop: (() -> Unit)? = null,
) {
    // @mention Estado de autocompletar:El usuario hace clic afuera para cerrar la publicación. false,Ingrese @ nuevamente para activar la configuración true
    var showMentionDropdown by remember { mutableStateOf(false) }
    // Después de insertar el miembro @, coloque el cursor en el semáforo al final(El enlace Pure String no puede cambiar el cursor,Impulsado por eso MuseTextField)
    var caretTick by remember { mutableIntStateOf(0) }
    // Encuentra el último del final del texto. @,Si no hay espacio después de @, es válida la consulta de mención
    val mentionQuery: String? = remember(text) {
        val atIndex = text.lastIndexOf('@')
        if (atIndex < 0) return@remember null
        val afterAt = text.substring(atIndex + 1)
        // @ No hay espacios en blanco después — Consulta de mención válida(Una cadena vacía significa recién ingresada @)
        if (afterAt.isEmpty() || !afterAt.any { it.isWhitespace() }) afterAt else null
    }
    // Vuelva a abrir el menú desplegable cuando el usuario ingrese una nueva @
    LaunchedEffect(mentionQuery) {
        if (mentionQuery != null) showMentionDropdown = true
    }
    // Filtrar miembros coincidentes(Incluir texto de consulta por nombre,No distingue entre mayúsculas y minúsculas;Priorización de alias largos)
    val filteredMembers = remember(mentionQuery, members) {
        if (mentionQuery == null) {
            emptyList()
        } else if (mentionQuery.isEmpty()) {
            members.sortedByDescending { it.name.length }
        } else {
            members
                .filter { it.name.contains(mentionQuery, ignoreCase = true) }
                .sortedByDescending { it.name.length }
        }
    }
    // v1.0.28: Reescritura del cuadro de entrada,Alinear estilos de página de tareas(MuseTextField + Botones redondos en la misma fila.,Retire el bloque de color de la superficie exterior)
    // v1.0.72: Entra en la Isla Grande — Menú Plus + cuadro de entrada + enviar todos los paquetes en una gran columna redondeada(Igual que el campo de entrada de chat único)
    // v1.0.72: Hacer estilo isla(Fondo sólido + esquinas redondeadas + sombra)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        // v2.4.5 (Alineación de la imagen de referencia): Consistente con la isla de entrada de chat única——Cápsula Real + Desproyección.
        shape = MuseShapes.pill,
        tonalElevation = io.zer0.muse.ui.theme.MuseElevation.low,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            // La barra de navegación del sistema y el teclado se manejan de manera uniforme mediante la barra de operación inferior exterior.
            // v2.4.5: Unifique márgenes compactos con una única isla de entrada de chat,y levante el borde inferior.
            .padding(
                horizontal = MusePaddings.inputIslandHorizontal,
                vertical = MusePaddings.inputIslandVertical,
            )
            .padding(bottom = MusePaddings.inputIslandBottomGap),
    ) {
        Box {
            // @mention Menú desplegable de autocompletar(Ancla encima del cuadro de entrada)
            // focusable = false:Autocompletar no puede captar el foco de la ventana,De lo contrario, se quitará el foco del teclado y del cuadro de entrada.,
            // La interpretación es "el teclado desaparece después de escribir @ y la finalización no aparece".
            MuseAnchoredMenu(
                expanded = showMentionDropdown && filteredMembers.isNotEmpty(),
                onDismissRequest = { showMentionDropdown = false },
                focusable = false,
            ) {
                filteredMembers.take(8).forEach { member ->
                    MuseListItem(
                        onClick = {
                            // Reemplace @query con @memberName(Agrega espacio al final,Fácil de seguir escribiendo)
                            val atIndex = text.lastIndexOf('@')
                            if (atIndex >= 0) {
                                val newText = text.substring(0, atIndex) + "@${member.name} "
                                onTextChange(newText)
                                // Después de insertar, mueva el cursor hasta el final.,Conveniente para seguir escribiendo directamente
                                caretTick++
                            }
                            showMentionDropdown = false
                        },
                        headlineContent = { Text(member.name) },
                    )
                }
            }
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    // v2.4.5 (Alineación de la imagen de referencia): El relleno y el chat único se reducen a 6 dp.
                    .padding(
                        horizontal = MusePaddings.inputIslandInnerHorizontal,
                        vertical = MusePaddings.inputIslandRowVertical,
                    ),
                // v1.0.72 fix: Bottom La alineación da como resultado el signo más/botón de enviar visual"torcido"(Al ingresar varias líneas),
                // Se cambió a CenterVertically para ser coherente con el campo de entrada de chat único.
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                // Entrada de menú adicional(Reservar,Pero en lugar de un pequeño botón de icono,No más círculos grandes Surface)
                MuseTactileButton(
                    icon = MuseIcons.plus,
                    onClick = onOpenToolSheet,
                    contentDescription = stringResource(R.string.groupchat_tools),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    enabled = enabled,
                    size = MuseIconSizes.touchTarget,
                    iconSize = MuseIconSizes.icon,
                )
                // v2.x: Entrada de operación de conferencias — votar/Resumen/@/miembro/contexto/El editor asciende desde el menú más a la entrada del primer nivel.
                MuseTactileButton(
                    icon = MuseIcons.clipboard,
                    onClick = onOpenMeetingSheet,
                    contentDescription = stringResource(R.string.groupchat_meeting),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    enabled = enabled,
                    size = MuseIconSizes.touchTarget,
                    iconSize = MuseIconSizes.icon,
                )
                MuseTextField(
                    value = text,
                    onValueChange = onTextChange,
                    caretAtEndTick = caretTick,
                    // El cuadro de entrada vacío debe tener un mensaje de entrada: anteriormente, se configuró en nulo para guardar espacio horizontal.
                    // El resultado es solo las flechas + y enviar, y el usuario no sabe que puede escribir aquí.
                    placeholder = {
                        Text(
                            text = stringResource(R.string.chat_placeholder_simple),
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    enabled = enabled,
                    // v1.0.72: El fondo del cuadro de entrada es transparente(fondo de la isla como contenedor)
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    singleLine = false,
                )
                // boton enviar — CHAT-11: Táctil unificado de 48 dp + círculo de color principal de 36 dp + ícono de 18 dp(Igual que el chat individual)
                // v2.6.7: Cambiar a tecla de parada durante la generación
                val stopClickable = isGenerating && onStop != null
                Box(
                    modifier = Modifier
                        .size(MuseIconSizes.touchTarget)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = stopClickable || (enabled && canSend),
                            onClick = { if (stopClickable) onStop?.invoke() else onSend() },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(MuseIconSizes.stopButton)
                            .clip(CircleShape)
                            .background(
                                if (stopClickable || (enabled && canSend)) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            // v2.6.4: El botón de enviar y el chat único se unifican en aviones de papel.
                            // v2.6.7: Utilice el icono de detener durante la generación para evitar que los usuarios piensen erróneamente que pueden enviar otro.
                            imageVector = if (stopClickable) MuseIcons.stop else MuseIcons.send,
                            contentDescription = stringResource(
                                if (stopClickable) R.string.groupchat_stop_generating else R.string.groupchat_send,
                            ),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Barra de estado de actividad del chat grupal — Muestra el chip de estado de cada agente en la rotación actual.
 *
 * IDLE El estado se filtra y no se muestra;Cuando no hay actividad, toda la columna no ocupa espacio.
 * Diseño:Filas de virutas horizontales compactas,Cada chip contiene icono + nombre + copia de estado;
 * REPLYING Estado más animación de respiración.,ERROR Combinación de colores incorrecta,NO_REPLY Pantalla debilitada.
 *
 * @param activities Lista de actividades de cada agente en el chat grupal actual
 */
@Composable
internal fun AgentActivityBar(activities: List<AgentActivity>) {
    // Filtrar el estado INACTIVO(Inactivo predeterminado,no mostrar)
    val visible = activities.filter { it.status != AgentActivityStatus.IDLE }
    if (visible.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = MusePaddings.screen, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            visible.forEach { activity ->
                AgentActivityChip(activity)
            }
        }
    }
}

/**
 * Estado de actividad del agente único chip — Icono + nombre + copia de estado,El color cambia con el estado.
 *
 * REPLYING uso estatal [rememberInfiniteTransition] Hacer animación alfa de respiración.,Permitir que los usuarios perciban"Salida".
 */
@Composable
internal fun AgentActivityChip(activity: AgentActivity) {
    val bgColor = when (activity.status) {
        AgentActivityStatus.VIEWING -> MaterialTheme.colorScheme.secondaryContainer
        AgentActivityStatus.REPLYING -> MaterialTheme.colorScheme.primaryContainer
        AgentActivityStatus.NO_REPLY -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        AgentActivityStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
        AgentActivityStatus.IDLE -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (activity.status) {
        AgentActivityStatus.VIEWING -> MaterialTheme.colorScheme.onSecondaryContainer
        AgentActivityStatus.REPLYING -> MaterialTheme.colorScheme.onPrimaryContainer
        AgentActivityStatus.NO_REPLY -> MaterialTheme.colorScheme.onSurfaceVariant
        AgentActivityStatus.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        AgentActivityStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when (activity.status) {
        AgentActivityStatus.VIEWING -> MuseIcons.eye
        AgentActivityStatus.REPLYING -> MuseIcons.edit
        AgentActivityStatus.NO_REPLY -> MuseIcons.ban
        AgentActivityStatus.ERROR -> MuseIcons.alertCircle
        AgentActivityStatus.IDLE -> MuseIcons.ban
    }
    val statusLabel = when (activity.status) {
        AgentActivityStatus.VIEWING -> stringResource(R.string.groupchat_activity_viewing)
        AgentActivityStatus.REPLYING -> stringResource(R.string.groupchat_activity_replying)
        AgentActivityStatus.NO_REPLY -> stringResource(R.string.groupchat_activity_no_reply)
        AgentActivityStatus.ERROR -> stringResource(R.string.groupchat_activity_error)
        AgentActivityStatus.IDLE -> ""
    }
    // REPLYING animación de respiración:alpha en 1f↔0.5f ciclo entre,deja que el chip tenga"Salida"retroalimentación visual
    // v1.0.74 fix (Auditoría inicial 6.4): Crea animación infinita solo al RESPONDER,
    // Los fotogramas de animación no se ejecutan en estados que no sean RESPUESTA(La implementación original fue creada incondicionalmente.,También se conduce cada cuadro cuando está parado.).
    val pulseAlpha = if (
        activity.status == AgentActivityStatus.REPLYING &&
        !io.zer0.muse.ui.theme.MuseMotion.isReducedMotion()
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "activity_pulse")
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.5f,
            animationSpec = infiniteRepeatable(
                animation = MuseMotion.tween(io.zer0.muse.ui.theme.MuseAnimation.LOOP_SLOW_MS),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse_alpha",
        ).value
    } else if (activity.status == AgentActivityStatus.REPLYING) {
        0.75f
    } else {
        1f
    }
    val chipAlpha = if (activity.status == AgentActivityStatus.REPLYING) pulseAlpha else 1f
    Surface(
        shape = MuseShapes.semiLarge,
        color = bgColor,
        contentColor = contentColor,
        modifier = Modifier.alpha(chipAlpha),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            val agentInitial = remember(activity.assistantName) {
                activity.assistantName.firstOrNull()?.toString() ?: ""
            }
            Text(
                text = "$agentInitial $statusLabel",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/**
 * Menú de chat grupal más — v1.0.72: área de medios(Vista previa en vivo de la cámara + miniatura del álbum) + Pestaña de desplazamiento.
 *
 * Función:Foto de la cámara/Álbum de imágenes recientes/Imágenes/Adjuntos/Base de conocimientos/Plantilla de mensajes
 */
@Composable
internal fun GroupChatToolSheet(
    onPickImage: () -> Unit,
    onPickDocument: () -> Unit,
    onInsertKnowledge: () -> Unit,
    onPickPromptTemplate: () -> Unit,
    // v1.0.72: área de medios(Cámara + Álbum)
    hasGalleryPermission: Boolean = false,
    onRequestGalleryPermission: () -> Unit = {},
    onPickGalleryImage: (Uri) -> Unit = {},
    onCaptureImage: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Permisos de vista previa de la cámara
    val cameraPermission = android.Manifest.permission.CAMERA
    val hasCameraPermission = remember {
        ContextCompat.checkSelfPermission(context, cameraPermission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    var cameraGranted by remember { mutableStateOf(hasCameraPermission) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraGranted = granted }

    // Álbum de fotos recientes
    var recentImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    LaunchedEffect(hasGalleryPermission) {
        if (hasGalleryPermission) {
            recentImages = withContext(Dispatchers.IO) {
                queryRecentGalleryImages(context, 6)
            }
        }
    }

    MuseBottomSheet(
        onDismissRequest = onDismiss,
        // UI-FIX(verdugón): Resulta que no hay espacios en blanco a izquierda y derecha, y el texto de descripción y el área de medios se empujan directamente al borde de la pantalla.
        horizontalPadding = MusePaddings.screen,
    ) {
        Text(
            text = stringResource(R.string.groupchat_pick_content),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(12.dp))
        // v1.0.72: área de medios — Vista previa en vivo de la cámara + miniatura del álbum(Unificado con chat único más menú)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // ST-10 (A11Y-03): Fijo 128dp → heightIn(min = 128.dp),Cuando el tamaño de fuente se amplía 1,3x
                // Bloque interno de 128 dp ampliable por altura de fuente,Recorte de altura no codificado
                .heightIn(min = 128.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = MusePaddings.tightGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        ) {
            // Vista previa en vivo de la cámara(primera grilla)
            if (cameraGranted) {
                CameraLivePreviewBox(
                    modifier = Modifier.size(128.dp).clip(MuseShapes.extraLarge),
                    onTap = onCaptureImage,
                )
            } else {
                GroupMediaCard(
                    icon = MuseIcons.camera,
                    label = stringResource(R.string.chat_tool_camera),
                    modifier = Modifier.size(128.dp),
                ) { cameraPermissionLauncher.launch(cameraPermission) }
            }
            // Miniaturas de fotografías recientes en el álbum.
            if (hasGalleryPermission) {
                recentImages.forEach { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = stringResource(R.string.chat_gallery_image_cd),
                        modifier = Modifier
                            .size(128.dp)
                            .clip(MuseShapes.extraLarge)
                            .clickable { onPickGalleryImage(uri) },
                        contentScale = ContentScale.Crop,
                    )
                }
            } else {
                GroupMediaCard(
                    icon = MuseIcons.image,
                    label = stringResource(R.string.chat_authorize_gallery),
                    modifier = Modifier.size(128.dp),
                ) { onRequestGalleryPermission() }
            }
        }
        Spacer(Modifier.height(16.dp))
        // rodar tab(Icono de círculo + texto debajo,Unificado con chat único más menú)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = MusePaddings.tightGap)
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.screen),
        ) {
            GroupToolTab(MuseIcons.image, stringResource(R.string.groupchat_image)) {
                onPickImage()
                onDismiss()
            }
            GroupToolTab(MuseIcons.paperclip, stringResource(R.string.chat_tool_attachment)) {
                onPickDocument()
                onDismiss()
            }
            GroupToolTab(MuseIcons.book, stringResource(R.string.chat_tool_knowledge)) {
                onInsertKnowledge()
                onDismiss()
            }
            GroupToolTab(MuseIcons.template, stringResource(R.string.chat_prompt_templates_title)) {
                onPickPromptTemplate()
                onDismiss()
            }
        }
    }
}

/**
 * v2.x: Panel de operación de conferencia de chat grupal — Entrada de nivel 1 promocionada desde el menú plus.
 *
 * Llevar a cabo debates sobre operaciones de alta frecuencia de tipo control.:Iniciar una votación/resumir la discusión/@mencionar miembros/lista de miembros/contexto del chat grupal/editar el chat grupal.
 * La implementación original estaba enterrada profundamente en la posición de desplazamiento horizontal de la décima pantalla del menú más.,Comentarios de los usuarios"El botón está demasiado escondido.",Por tanto, retire el panel independiente.
 */
@Composable
internal fun GroupChatMeetingSheet(
    onOpenMembers: () -> Unit,
    onLaunchVote: () -> Unit,
    onLaunchSummary: () -> Unit,
    onOpenContext: () -> Unit,
    onMentionMember: () -> Unit,
    onEditGroup: () -> Unit,
    onDismiss: () -> Unit,
) {
    MuseBottomSheet(
        onDismissRequest = onDismiss,
        horizontalPadding = MusePaddings.screen,
    ) {
        Text(
            text = stringResource(R.string.groupchat_meeting),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(12.dp))
        // rodar tab(Icono de círculo + texto debajo,Unificar con menú plus) — Alta frecuencia primero:votar/Resumen/@/miembro/contexto/Editar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = MusePaddings.tightGap)
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.screen),
        ) {
            GroupToolTab(MuseIcons.check, stringResource(R.string.groupchat_tool_vote)) {
                onLaunchVote()
                onDismiss()
            }
            GroupToolTab(MuseIcons.fileText, stringResource(R.string.groupchat_tool_summary)) {
                onLaunchSummary()
                onDismiss()
            }
            GroupToolTab(MuseIcons.at, stringResource(R.string.groupchat_tool_mention)) {
                onMentionMember()
                onDismiss()
            }
            GroupToolTab(MuseIcons.users, stringResource(R.string.groupchat_tool_members)) {
                onOpenMembers()
                onDismiss()
            }
            GroupToolTab(MuseIcons.folder, stringResource(R.string.groupchat_tool_context)) {
                onOpenContext()
                onDismiss()
            }
            GroupToolTab(MuseIcons.edit, stringResource(R.string.groupchat_edit_cd)) {
                onEditGroup()
                onDismiss()
            }
        }
    }
}

/** v1.0.72: Chat grupal más desplazamiento del menú tab(Icono redondo + texto independiente debajo). */
@Composable
private fun GroupToolTab(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .widthIn(min = 60.dp)
            .clip(MuseShapes.extraLarge)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(44.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            // ST-10 (A11Y-03): suplemento overflow,Si hay más de 2 líneas, se omitirán en lugar de ser cortadas por el contenedor principal.
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** v1.112: Fila del menú de herramientas de chat grupal(Icono izquierdo + título/Subtítulo + flecha derecha). */
@Composable
internal fun GroupChatToolRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = MuseShapes.semiLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(MuseIconSizes.icon),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Icon(
                imageVector = MuseIcons.arrowRight,
                contentDescription = null,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** v1.112: Divisor del menú de la herramienta de chat grupal. */
@Composable
internal fun GroupChatToolDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        thickness = 0.5.dp,
    )
}

/**
 * v2.6.7: Vista previa de la línea del archivo adjunto que se enviará.
 *
 * antes `pendingFileAttachments` Solo participa en el juicio canSend, la interfaz no se representa en absoluto ——
 * Si el usuario selecciona un archivo adjunto, no puede verlo ni eliminarlo (removePendingFileAttachment cero llamadas).
 * Aquí se muestra un nombre de archivo desplazable horizontalmente + una fila de botón de eliminación.
 */
@Composable
fun PendingAttachmentsRow(attachments: List<FileAttachment>, onRemove: (Int) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        itemsIndexed(attachments) { index, file ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(MuseShapes.semiLarge)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
                io.zer0.muse.ui.common.media.AttachmentRemoveButton(
                    contentDescription = stringResource(R.string.groupchat_delete),
                    onClick = { onRemove(index) },
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
            }
        }
    }
}

/**
 * Vista previa de la línea de imagen que se enviará — Haga clic en la esquina superior derecha para eliminar.
 */
@Composable
fun PendingImagesRow(images: List<String>, onRemove: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        items(images, key = { it }) { image ->
            Box(modifier = Modifier.size(64.dp)) {
                io.zer0.muse.ui.SmartImage(
                    model = image,
                    contentDescription = stringResource(R.string.groupchat_pending_image),
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(MuseShapes.semiLarge),
                )
                io.zer0.muse.ui.common.media.AttachmentRemoveButton(
                    contentDescription = stringResource(R.string.groupchat_delete),
                    onClick = { onRemove(image) },
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}

/**
 * Cuadro de diálogo de lista de miembros.
 */
@Composable
internal fun MembersDialog(memberNames: List<String>, memberCount: Int, onDismiss: () -> Unit) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.groupchat_members_title, memberCount),
        content = {
            if (memberNames.isEmpty()) {
                Text(
                    text = stringResource(R.string.groupchat_no_members),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    memberNames.forEach { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.groupchat_close),
        onConfirm = onDismiss,
        dismissText = null,
    )
}

/**
 * v1.97: Editar diálogo de chat grupal — Cambiar nombre + cambiar miembros.
 *
 * prensa [CreateGroupChatDialog] estilo,Complete previamente el nombre del chat grupal actual y los miembros seleccionados,
 * Llamado al guardar [GroupChatViewModel.updateChat].
 *
 * @param initialName Nombre del chat grupal actual(Precargado)
 * @param assistants Lista de todos los asistentes(Candidato a miembro)
 * @param initialMemberIds Lista de identificación de miembro actual(Preselección)
 * @param onDismiss Cerrar devolución de llamada
 * @param onConfirm Confirmar devolución de llamada(newName, newMemberIds)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditGroupChatDialog(
    // v1.0.74 fix (Auditoría inicial 6.3): estable key — Utilice chatId en lugar de initialMemberIds,
    // De lo contrario, cuando se actualice el flujo de metadatos del chat grupal, los cambios clave restablecerán a los miembros en la edición del usuario./Selección de modo.
    dialogKey: String,
    initialName: String,
    assistants: List<AssistantEntity>,
    initialMemberIds: List<String>,
    initialDiscussionMode: String = "round_robin",
    initialAutoMaxRounds: Int = 5,
    initialHostId: String? = null,
    initialReplyLengthMode: String = "standard",
    initialObserverIds: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (
        newName: String,
        newMemberIds: List<String>,
        newDiscussionMode: String,
        newAutoMaxRounds: Int,
        newHostId: String?,
        newReplyLengthMode: String,
        newObserverIds: List<String>,
    ) -> Unit,
) {
    // v1.97: Utilice RememberSaveable para conservar el estado de edición.,Gira la pantalla sin perderla
    // v1.0.74 fix (Auditoría inicial 6.3): key Para uso unificado dialogKey(chatId),Evite la actualización de metadatos y restablezca las ediciones
    var name by rememberSaveable(dialogKey) { mutableStateOf(initialName) }
    var selectedMemberIds by rememberSaveable(dialogKey) {
        mutableStateOf(initialMemberIds.toSet())
    }
    var showErrors by rememberSaveable(dialogKey) { mutableStateOf(false) }
    // v2.x: Estado del modo de discusión
    var discussionMode by rememberSaveable(dialogKey) { mutableStateOf(initialDiscussionMode) }
    var autoMaxRounds by rememberSaveable(dialogKey) { mutableStateOf(initialAutoMaxRounds) }
    var hostId by rememberSaveable(dialogKey) { mutableStateOf(initialHostId ?: "") }
    // v2.x: Estado del engranaje de longitud del discurso(brief/standard/detailed)
    var replyLengthMode by rememberSaveable(dialogKey) { mutableStateOf(initialReplyLengthMode) }
    // v2.x: miembro observador(No participe en la rotación de oradores predeterminada.)
    var observerIds by rememberSaveable(dialogKey) { mutableStateOf(initialObserverIds.toSet()) }

    val maxNameLength = 30
    val nameError = showErrors && name.isBlank()
    val memberError = showErrors && selectedMemberIds.isEmpty()

    // Lista de modos de discusión
    val modeOptions = listOf(
        "round_robin" to R.string.groupchat_mode_round_robin,
        "auto" to R.string.groupchat_mode_auto,
        "debate" to R.string.groupchat_mode_debate,
        "host" to R.string.groupchat_mode_host,
    )
    val modeDescMap = mapOf(
        "round_robin" to R.string.groupchat_mode_round_robin_desc,
        "auto" to R.string.groupchat_mode_auto_desc,
        "debate" to R.string.groupchat_mode_debate_desc,
        "host" to R.string.groupchat_mode_host_desc,
    )

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.groupchat_edit_title),
        content = {
            // Cuadro de entrada del nombre del chat grupal
            MuseTextField(
                value = name,
                onValueChange = { newName ->
                    showErrors = false
                    name = newName.take(maxNameLength)
                },
                label = { Text(stringResource(R.string.groupchat_edit_name_hint)) },
                singleLine = true,
                isError = nameError,
                supportingText = if (nameError) {
                    { Text(stringResource(R.string.groupchat_name_required), color = MaterialTheme.colorScheme.error) }
                } else {
                    { Text("${name.length}/$maxNameLength") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            // Selección de miembros
            Text(
                text = stringResource(R.string.groupchat_select_members),
                style = MaterialTheme.typography.labelMedium,
                color = if (memberError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (assistants.isEmpty()) {
                Text(
                    text = stringResource(R.string.groupchat_no_assistants),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                ) {
                    items(assistants, key = { it.id }) { assistant ->
                        val selected = assistant.id in selectedMemberIds
                        MuseChip(
                            selected = selected,
                            onClick = {
                                showErrors = false
                                selectedMemberIds = if (selected) {
                                    selectedMemberIds - assistant.id
                                } else {
                                    selectedMemberIds + assistant.id
                                }
                            },
                            label = assistant.name,
                        )
                    }
                }
            }
            // v2.x: miembro observador — Especificar entre miembros seleccionados,No participe en la rotación de oradores predeterminada.
            val selectedAssistants = assistants.filter { it.id in selectedMemberIds }
            if (selectedAssistants.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.groupchat_observers),
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
                    items(selectedAssistants, key = { "obs_${it.id}" }) { assistant ->
                        val isObserver = assistant.id in observerIds
                        MuseChip(
                            selected = isObserver,
                            onClick = {
                                observerIds = if (isObserver) {
                                    observerIds - assistant.id
                                } else {
                                    observerIds + assistant.id
                                }
                            },
                            label = assistant.name,
                        )
                    }
                }
            }
            if (memberError) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.groupchat_edit_member_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.groupchat_selected_members, selectedMemberIds.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth(),
            )

            // ── v2.x: Selección del modo de discusión ──
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.groupchat_mode_title),
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
                items(modeOptions, key = { it.first }) { (mode, labelRes) ->
                    MuseChip(
                        selected = discussionMode == mode,
                        onClick = { discussionMode = mode },
                        label = stringResource(labelRes),
                    )
                }
            }
            // Descripción del modo actual
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(modeDescMap[discussionMode] ?: R.string.groupchat_mode_round_robin_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )

            // ── v2.x: Rango de longitud del discurso(corto/Estándar/detallado) ──
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.groupchat_length_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "brief" to R.string.groupchat_length_brief,
                    "standard" to R.string.groupchat_length_standard,
                    "detailed" to R.string.groupchat_length_detailed,
                ).forEach { (lengthMode, labelRes) ->
                    MuseChip(
                        selected = replyLengthMode == lengthMode,
                        onClick = { replyLengthMode = lengthMode },
                        label = stringResource(labelRes),
                    )
                }
            }

            // Auto modo:Control deslizante de número máximo de rondas
            if (discussionMode == "auto") {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.groupchat_mode_auto_rounds),
                        style = MaterialTheme.typography.bodyMedium,
                        // LAYOUT-01: El nombre del elemento de configuración toma el ancho restante + límite de línea,Sólo se conserva el valor de la marcha en el lado derecho,
                        // Evitar que se amplíe el tamaño de fuente/En una ventana estrecha, los nombres se comprimen en una columna por valores numéricos.
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "$autoMaxRounds",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        // LAYOUT-01: Las columnas numéricas están limitadas a 1 fila.
                        maxLines = 1,
                    )
                }
                MuseSlider(
                    value = autoMaxRounds.toFloat(),
                    onValueChange = { autoMaxRounds = it.toInt().coerceIn(1, 20) },
                    valueRange = 1f..20f,
                    steps = 18,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Modo moderador:Seleccionar anfitrión
            if (discussionMode == "host") {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.groupchat_mode_select_host),
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
                    // "No seleccionar"Opciones
                    item(key = "no_host") {
                        MuseChip(
                            selected = hostId.isBlank(),
                            onClick = { hostId = "" },
                            label = stringResource(R.string.groupchat_mode_no_host),
                        )
                    }
                    // Sólo los miembros seleccionados pueden ser seleccionados como moderadores.
                    items(assistants.filter { it.id in selectedMemberIds }, key = { it.id }) { assistant ->
                        MuseChip(
                            selected = hostId == assistant.id,
                            onClick = { hostId = assistant.id },
                            label = assistant.name,
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.groupchat_edit_btn),
        onConfirm = {
            val trimmedName = name.trim()
            if (trimmedName.isNotBlank() && selectedMemberIds.isNotEmpty()) {
                onConfirm(
                    trimmedName,
                    selectedMemberIds.toList(),
                    discussionMode,
                    autoMaxRounds,
                    hostId.ifBlank { null },
                    replyLengthMode,
                    observerIds.toList(),
                )
            } else {
                showErrors = true
            }
        },
        dismissText = stringResource(R.string.groupchat_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * H5: Codifique el mapa de bits en una cadena base64.
 *
 * Disminuir la compresión de calidad con JPEG,Hasta que la cadena base64 no supere los 2 MB.
 * Al final, si aún supera los 2 MB, se generará una excepción.,Solicita al usuario que seleccione una imagen más pequeña.
 */
internal fun readImageBytes(context: android.content.Context, uri: Uri): ByteArray {
    val stream = context.contentResolver.openInputStream(uri)
    if (stream != null) return stream.use { it.readBytes() }
    val fd = context.contentResolver.openFileDescriptor(uri, "r")
        ?: throw IllegalStateException("No se puede leer la imagen")
    return fd.use { java.io.FileInputStream(it.fileDescriptor).use { fis -> fis.readBytes() } }
}

internal fun encodeBitmapToBase64(bitmap: Bitmap): String {
    val maxBase64Length = 2 * 1024 * 1024 // 2MB(base64 Longitud máxima de la cuerda)
    val baos = ByteArrayOutputStream()
    var quality = 90
    var base64: String
    do {
        baos.reset()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.DEFAULT)
        if (base64.length > maxBase64Length) {
            quality -= 10
        } else {
            break
        }
    } while (quality >= 20)

    if (base64.length > maxBase64Length) {
        throw IllegalStateException("Imagen demasiado grande,Por favor elige una imagen más pequeña")
    }
    return base64
}

/** v1.0.72: Tarjeta multimedia del menú Group Chat Plus(cámara/Marcador de posición autorizado). */
@Composable
private fun GroupMediaCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

/** v1.0.72: Vista previa de la cámara en vivo(CameraX,Implementación de la reutilización del menú de signo más de chat único). */
@Composable
private fun CameraLivePreviewBox(modifier: Modifier, onTap: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnTap by rememberUpdatedState(onTap)
    // tiene referencia del proveedor;Grupo de hojas de panel(onRelease)Desvincular cuando,Evitar que la cámara permanezca vinculada después de cerrar el menú
    // en Actividad REANUDA(La luz indicadora está siempre encendida./Consumo de energía/Bloquear otras aplicaciones de cámara).
    val cameraProviderHolder = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                setOnClickListener { currentOnTap() }
            }
            runCatching {
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    runCatching {
                        val provider = providerFuture.get()
                        cameraProviderHolder[0] = provider
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                    }.onFailure { e -> Logger.w("GroupChatToolSheet", "Error al vincular la vista previa de la cámara", e) }
                }, ContextCompat.getMainExecutor(ctx))
            }.onFailure { e -> Logger.w("GroupChatToolSheet", "Falló la inicialización de la vista previa de la cámara", e) }
            previewView
        },
        onRelease = {
            cameraProviderHolder[0]?.unbindAll()
            cameraProviderHolder[0] = null
        },
        modifier = modifier,
    )
}

/** v1.0.72: Consulta las últimas imágenes en el álbum del sistema. */
private fun queryRecentGalleryImages(context: Context, maxCount: Int): List<Uri> {
    return runCatching {
        val uris = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            var count = 0
            while (cursor.moveToNext() && count < maxCount) {
                val id = cursor.getLong(idColumn)
                uris.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))
                count++
            }
        }
        uris
    }.onFailure { e -> Logger.w("GroupChatToolSheet", "No se pudo consultar el álbum", e) }.getOrDefault(emptyList())
}
