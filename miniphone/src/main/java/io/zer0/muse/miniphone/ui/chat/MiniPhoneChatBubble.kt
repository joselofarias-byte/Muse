package io.zer0.muse.miniphone.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.miniphone.api.MpActivityKind
import io.zer0.muse.miniphone.data.db.MpMessageStatus
import io.zer0.muse.miniphone.ui.shell.MiniPhoneTimeFormat
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlin.math.abs

/**
 * Una línea completa de un mensaje.
 *
 * Mantente a la derecha, mantente a la izquierda.,Con avatares en ambos extremos.(Consistente con los hábitos de chat único:Puedes saber quién lo dijo de un vistazo.);
 * El sistema solicita no participar en la división de columnas izquierda y derecha,Utilice únicamente un carácter pequeño y centrado. El texto principal sólo se dibuja en el mensaje de texto.:Imágenes y voces
 * Cada uno se reemplaza con su propio nodo. El ancho de línea se calcula a partir de la lista y luego se pasa.,Las propias burbujas no saben qué tan ancha es la pantalla.
 *
 * Menú de burbujas de pulsación larga(Copiar/eliminar/reenviar),La eliminación requiere confirmación;Los mensajes que envíes también se mostrarán junto a las burbujas.
 * Enviar estado —— Se envía una simple línea de texto.,Una bandera en la que se puede hacer clic para indicar un error.
 *
 * @param self Tu propio nombre y avatar(Dibuja las iniciales del avatar de la derecha.)
 * @param peer El nombre y avatar de la otra parte.
 * @param senderLabel ¿Quién dijo esta noticia en el grupo?;mensaje privado null,No escribas nombres junto a las burbujas.
 * @param onOpenImage Haga clic en la imagen
 * @param messageActions Reenviar y eliminar;Hacerlo o no depende del menú.
 */
@Composable
internal fun ChatMessageRow(
    message: ChatMessageUi,
    self: ChatPersona,
    peer: ChatPersona,
    maxBubbleWidth: Dp,
    onOpenImage: (String) -> Unit,
    messageActions: ChatMessageActions = ChatMessageActions(),
    senderLabel: String? = null,
) {
    if (message.isSystem) {
        SystemNoticeRow(message = message)
        return
    }
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val clipboard = LocalClipboardManager.current
    val onResend = messageActions.onResend
    val onDelete = messageActions.onDelete
    var menuOpen by remember(message.id) { mutableStateOf(false) }
    var confirmingDelete by remember(message.id) { mutableStateOf(false) }
    val openMenu: () -> Unit = { menuOpen = true }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MiniPhoneChatTags.message(message.id))
            .padding(horizontal = dimens.pageMargin, vertical = 4.dp),
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!message.isMine) {
            MiniPhoneChatAvatar(name = peer.name, avatarUri = peer.avatarUri, size = dimens.avatarBubble)
            Spacer(Modifier.width(BubbleAvatarGap))
        }
        if (senderLabel != null && !message.isMine) {
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = senderLabel,
                    style = typography.timestamp.copy(color = MiniPhoneTheme.colors.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(MiniPhoneChatTags.senderName(message.id)),
                )
                Spacer(Modifier.height(SenderNameGap))
                MessageBubbleContent(
                    message = message,
                    maxBubbleWidth = maxBubbleWidth,
                    onOpenImage = onOpenImage,
                    onLongPress = openMenu,
                )
            }
        } else {
            MessageBubbleContent(
                message = message,
                maxBubbleWidth = maxBubbleWidth,
                onOpenImage = onOpenImage,
                onLongPress = openMenu,
            )
        }
        if (message.isMine) {
            if (message.status != MpMessageStatus.SENT) {
                Spacer(Modifier.width(StatusGap))
                ChatDeliveryState(message = message, onRetry = { onResend(message.id) })
            }
            Spacer(Modifier.width(BubbleAvatarGap))
            MiniPhoneChatAvatar(
                name = self.name,
                avatarUri = self.avatarUri,
                size = dimens.avatarBubble,
                modifier = Modifier.testTag(MiniPhoneChatTags.SELF_AVATAR),
            )
        }
        if (menuOpen) {
            ChatMessageMenu(
                message = message,
                onDismiss = { menuOpen = false },
                onAction = { action ->
                    when (action) {
                        ChatMenuAction.COPY -> clipboard.setText(AnnotatedString(message.content))
                        ChatMenuAction.DELETE -> confirmingDelete = true
                        ChatMenuAction.RESEND -> onResend(message.id)
                    }
                },
            )
        }
    }
    if (confirmingDelete) {
        ChatDeleteConfirmDialog(
            onDismiss = { confirmingDelete = false },
            onConfirm = {
                confirmingDelete = false
                onDelete(message.id)
            },
        )
    }
}

/**
 * Enviar estado junto a la burbuja:Se envía una simple línea de texto.,La falla da como resultado un marcador rojo ladrillo en el que se puede hacer clic.
 *
 * Ninguno de los dos bloquea el texto principal. —— También deberías poder leer la frase que acabas de escribir mientras la envías.
 */
@Composable
private fun ChatDeliveryState(message: ChatMessageUi, onRetry: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    if (message.isSending) {
        Text(
            text = SENDING_TEXT,
            style = typography.timestamp.copy(color = colors.textSecondary),
            modifier = Modifier.testTag(MiniPhoneChatTags.status(message.id)),
        )
        return
    }
    Row(
        modifier = Modifier
            .testTag(MiniPhoneChatTags.status(message.id))
            .clip(RoundedCornerShape(SendFailRadius))
            .clickable(onClick = onRetry)
            .semantics { contentDescription = SEND_FAILED_TEXT }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = SEND_FAILED_MARK, style = typography.caption.copy(color = colors.unreadDot))
        Spacer(Modifier.width(2.dp))
        Text(text = SEND_FAILED_TEXT, style = typography.timestamp.copy(color = colors.unreadDot))
    }
}

/**
 * cuerpo de burbuja:Hay una rama para imágenes, otra para voz y otra para texto.
 *
 * Se selecciona por separado porque el grupo necesita una línea adicional con el nombre del orador encima. —— Ambos diseños comparten la misma burbuja.,
 * De lo contrario, "con nombre" y "sin nombre" tendrán cada uno un código de representación.
 */
@Composable
private fun MessageBubbleContent(message: ChatMessageUi, maxBubbleWidth: Dp, onOpenImage: (String) -> Unit, onLongPress: () -> Unit) {
    if (message.isImage) {
        ChatImageBubble(message = message, maxWidth = maxBubbleWidth, onOpen = onOpenImage, onLongPress = onLongPress)
    } else if (message.isVoice) {
        // El ancho de la barra de voz lo puedes configurar tú mismo(Ordenar por duración),No mires el ancho disponible de burbujas.;El lado del usuario aún no envía voz.,Mantenga la rama de renderizado primero
        ChatVoiceBubble(message = message, onLongPress = onLongPress)
    } else {
        ChatBubble(message = message, maxWidth = maxBubbleWidth, onLongPress = onLongPress)
    }
}

/** cuerpo de burbuja:Esquinas redondeadas uniformes 10dp,El otro lado tiene un borde adicional de 1 dp para separar el fondo blanco del color del papel. */
@Composable
private fun ChatBubble(message: ChatMessageUi, maxWidth: Dp, onLongPress: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val mine = message.isMine
    val shape = RoundedCornerShape(dimens.bubbleRadius)
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val collapsible = message.content.length > COLLAPSE_THRESHOLD
    val shown = if (collapsible && !expanded) {
        message.content.take(COLLAPSE_THRESHOLD).trimEnd() + ELLIPSIS
    } else {
        message.content
    }
    Column(
        modifier = Modifier
            .testTag(MiniPhoneChatTags.bubble(message.id))
            .widthIn(max = maxWidth)
            .clip(shape)
            .background(if (mine) colors.bubbleSelf else colors.bubbleOther)
            .then(if (mine) Modifier else Modifier.border(dimens.hairline, colors.bubbleOtherBorder, shape))
            // Haga clic para ampliar/cerrar(Sólo tiene sentido para textos largos y plegados.),Menú emergente de pulsación larga
            .combinedClickable(
                onClick = { if (collapsible) expanded = !expanded },
                onLongClick = onLongPress,
            )
            .padding(horizontal = BubbleHorizontalPadding, vertical = BubbleVerticalPadding),
    ) {
        Text(
            text = shown,
            style = typography.body.copy(color = if (mine) colors.bubbleSelfText else colors.bubbleOtherText),
        )
        if (collapsible) {
            Text(
                text = if (expanded) COLLAPSE_ACTION else EXPAND_ACTION,
                style = typography.caption.copy(
                    color = if (mine) colors.bubbleSelfText.copy(alpha = 0.85f) else colors.accent,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { expanded = !expanded }
                    .testTag(MiniPhoneChatTags.expand(message.id)),
            )
        }
    }
}

/** Aviso del sistema:Fuentes pequeñas centradas,El negativo en papel separa el texto del mismo. */
@Composable
private fun SystemNoticeRow(message: ChatMessageUi) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MiniPhoneChatTags.message(message.id))
            .padding(horizontal = 32.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message.content,
            style = typography.timestamp.copy(color = colors.textSecondary),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(SystemNoticeRadius))
                .background(colors.surface)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** separación de tiempo:Una línea de texto de tiempo centrado. */
@Composable
internal fun ChatTimeDividerRow(at: Long) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MiniPhoneChatTags.timeDivider(at))
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = MiniPhoneTimeFormat.separator(at),
            style = typography.timestamp.copy(color = colors.textSecondary),
        )
    }
}

/** \"La otra parte está escribiendo…\":Hay tres puntos saltadores en la burbuja del lado del oponente. */
@Composable
internal fun ChatTypingRow(peerName: String, peerAvatarUri: String?, showName: Boolean = false) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(dimens.bubbleRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MiniPhoneChatTags.TYPING)
            .semantics { contentDescription = TYPING_TEXT }
            .padding(horizontal = dimens.pageMargin, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        MiniPhoneChatAvatar(name = peerName, avatarUri = peerAvatarUri, size = dimens.avatarBubble)
        Spacer(Modifier.width(BubbleAvatarGap))
        Column(horizontalAlignment = Alignment.Start) {
            // Escribe tu nombre en el grupo.:Cuando otra persona escribe en la misma ronda,Cambie esta línea en consecuencia,Ambas sentencias no serán atribuidas a la misma persona.
            if (showName && peerName.isNotBlank()) {
                Text(
                    text = peerName,
                    style = typography.timestamp.copy(color = colors.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(MiniPhoneChatTags.TYPING_NAME),
                )
                Spacer(Modifier.height(SenderNameGap))
            }
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(colors.bubbleOther)
                    .border(dimens.hairline, colors.bubbleOtherBorder, shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                TypingDots()
            }
        }
    }
}

/**
 * "La otra parte está hurgando…":Genera una línea que aparece encima de la burbuja.
 *
 * Solo escribe una oración,Sin marcas de herramientas;Después de que el texto caiga o falle, la lista lo completará en toda la línea.
 */
@Composable
internal fun ChatActivityRow(kind: MpActivityKind) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MiniPhoneChatTags.ACTIVITY)
            .padding(horizontal = dimens.pageMargin, vertical = ActivityRowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = MiniPhoneActivityText.of(kind),
            style = typography.timestamp.copy(color = colors.textSecondary),
        )
    }
}

/** Los tres puntos se iluminan y flotan en secuencia.,Complétalo en un ciclo. */
@Composable
private fun TypingDots() {
    val ink = MiniPhoneTheme.colors.textSecondary
    val transition = rememberInfiniteTransition(label = TYPING_LABEL)
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = DOT_CYCLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = TYPING_PHASE_LABEL,
    )
    Canvas(Modifier.size(width = DotsWidth, height = DotsHeight)) {
        val radius = DotRadius.toPx()
        val gap = DotGap.toPx()
        val startX = size.width / 2f - gap
        val centerY = size.height / 2f
        val liftHeight = DotLift.toPx()
        repeat(DOT_COUNT) { index ->
            val progress = (phase + index.toFloat() / DOT_COUNT) % 1f
            val lift = 1f - abs(progress - 0.5f) * 2f
            drawCircle(
                color = ink.copy(alpha = DOT_BASE_ALPHA + (1f - DOT_BASE_ALPHA) * lift),
                radius = radius,
                center = Offset(startX + index * gap, centerY - liftHeight * lift),
            )
        }
    }
}

/** Se corrigió el espacio entre avatar y burbuja. */
private val BubbleAvatarGap = 8.dp

/** La distancia entre el nombre del hablante del grupo y la burbuja. */
private val SenderNameGap = 2.dp
private val StatusGap = 6.dp
private val SendFailRadius = 4.dp
private val ActivityRowPadding = 6.dp
private val BubbleHorizontalPadding = 12.dp
private val BubbleVerticalPadding = 8.dp
private val SystemNoticeRadius = 6.dp

/** Doblar más que este número de palabras,Déle una \"expansión\" después de doblar. */
private const val COLLAPSE_THRESHOLD: Int = 300

private const val EXPAND_ACTION: String = "Expandir"
private const val COLLAPSE_ACTION: String = "cerrar"
private const val ELLIPSIS: String = "…"
private const val SENDING_TEXT: String = "Enviando"
private const val SEND_FAILED_MARK: String = "!"
private const val SEND_FAILED_TEXT: String = "No emitido,Inténtalo de nuevo"
private const val TYPING_TEXT: String = "La otra parte está escribiendo…"
private const val TYPING_LABEL: String = "mp-typing"
private const val TYPING_PHASE_LABEL: String = "mp-typing-phase"

private const val DOT_CYCLE_MS: Int = 1_200
private const val DOT_COUNT: Int = 3
private const val DOT_BASE_ALPHA: Float = 0.3f
private val DotRadius = 2.5.dp
private val DotGap = 9.dp
private val DotLift = 2.dp
private val DotsWidth = 32.dp
private val DotsHeight = 14.dp
