package io.zer0.muse.miniphone.ui.shell

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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * pagina de mensaje:Lista de sesiones.
 *
 * La estructura de filas se fija como "avatar/nombre/último mensaje/hora/no leído",El orden y la posición se derivan de la escala de tamaños de este módulo.,
 * No sigue el componente de lista del host. Pedido garantizado por DAO(traer al frente,El resto están en orden descendente de últimas noticias.),Aquí sólo soy responsable de pintar.
 *
 * Haga clic para ingresar a la conversación.,Mantenga presionado para reproducir [ThreadRowMenu]:Anclar/desanclar, marcar como leído(Listar solo si hay elementos no leídos), elimina la sesión.
 * El menú y la confirmación de eliminación solo utilizan el estado de esta página.,No afecta los datos de la lista.
 *
 * @param onGoContacts Cuando no hay conversación,Utilice el botón en el estado vacío para enviar al usuario a la libreta de direcciones
 * @param actions Tres acciones en líneas de conversación(Anclar/Marcar como leído/Eliminar),Recibido del caparazón ViewModel
 */
@Composable
internal fun MessagesPage(
    threads: List<MiniPhoneThreadUi>,
    modifier: Modifier = Modifier,
    onOpenThread: (String) -> Unit = {},
    onGoContacts: () -> Unit = {},
    actions: ThreadMenuActions = ThreadMenuActions(),
) {
    // La sesión en la que aparece el menú y la sesión que espera confirmación de eliminación:Solo hay uno a la vez
    var menuThreadId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    if (threads.isEmpty()) {
        ThreadsEmptyState(onGoContacts = onGoContacts, modifier = modifier)
        return
    }
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(items = threads, key = { it.id }) { thread ->
            Box {
                ThreadRow(
                    thread = thread,
                    onOpen = { onOpenThread(thread.id) },
                    onLongPress = { menuThreadId = thread.id },
                )
                if (menuThreadId == thread.id) {
                    ThreadRowMenu(
                        thread = thread,
                        onDismiss = { menuThreadId = null },
                        onAction = { action ->
                            when (action) {
                                ThreadMenuAction.PIN -> actions.onTogglePin(thread.id, true)
                                ThreadMenuAction.UNPIN -> actions.onTogglePin(thread.id, false)
                                ThreadMenuAction.MARK_READ -> actions.onMarkRead(thread.id)
                                ThreadMenuAction.DELETE -> pendingDeleteId = thread.id
                            }
                        },
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = dimens.pageMargin + dimens.avatarList + ThreadTextGap)
                    .height(dimens.hairline)
                    .background(colors.divider),
            )
        }
    }
    val deletingId = pendingDeleteId
    if (deletingId != null) {
        ThreadDeleteConfirmDialog(
            onDismiss = { pendingDeleteId = null },
            onConfirm = {
                actions.onDelete(deletingId)
                pendingDeleteId = null
            },
        )
    }
}

/** Sesión de una línea. */
@Composable
private fun ThreadRow(thread: MiniPhoneThreadUi, onOpen: () -> Unit, onLongPress: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MiniPhoneTags.thread(thread.id))
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = dimens.pageMargin, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            MiniPhoneAvatar(name = thread.name, size = dimens.avatarList)
            if (thread.unreadCount > 0) {
                UnreadBadge(
                    count = thread.unreadCount,
                    threadId = thread.id,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 5.dp, y = (-5).dp),
                )
            }
        }
        Spacer(Modifier.width(ThreadTextGap))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = thread.name,
                    style = typography.body.copy(color = colors.textPrimary, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = relativeTimeOf(thread.lastMessageAt),
                    style = typography.timestamp.copy(color = colors.textSecondary),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = thread.preview.ifBlank { EMPTY_PREVIEW_TEXT },
                style = typography.caption.copy(color = colors.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Subíndice no leído. Si el número excede los dos dígitos, presione 99+.,No dejes que deforme el avatar. */
@Composable
private fun UnreadBadge(count: Int, threadId: String, modifier: Modifier = Modifier) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = dimens.unreadBadge, minHeight = dimens.unreadBadge)
            .clip(CircleShape)
            .background(colors.unreadDot)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > MAX_BADGE_COUNT) FULL_BADGE_TEXT else count.toString(),
            style = typography.timestamp.copy(color = colors.unreadDotText, fontWeight = FontWeight.Medium),
            modifier = Modifier.testTag(MiniPhoneTags.unread(threadId)),
        )
    }
}

/**
 * Lista de sesiones vacía:Un bocadillo dibujado por uno mismo y dos líneas de explicación.,Agregue un botón para ir directamente a la libreta de direcciones.
 *
 * Escribir solo una línea de texto gris equivale a dejar que el usuario adivine "cómo empezar". —— Los botones convierten este paso en un solo clic.
 */
@Composable
private fun ThreadsEmptyState(onGoContacts: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val buttonShape = RoundedCornerShape(dimens.cardRadius)
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(MiniPhoneTags.EMPTY_THREADS)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyBubble()
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Aún no hay chat",
            style = typography.title.copy(color = colors.textPrimary),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Ve a la libreta de direcciones y busca un personaje para comenzar.",
            style = typography.caption.copy(color = colors.textSecondary),
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = GO_CONTACTS_LABEL,
            style = typography.caption.copy(color = colors.accent, fontWeight = FontWeight.Medium),
            modifier = Modifier
                .testTag(MiniPhoneTags.EMPTY_THREADS_ACTION)
                .clip(buttonShape)
                .background(colors.surface)
                .border(dimens.hairline, colors.divider, buttonShape)
                .clickable(onClick = onGoContacts)
                .padding(horizontal = 18.dp, vertical = 9.dp),
        )
    }
}

/** Ilustración vacía:Una burbuja hueca más tres puntos.,El color toma la baja opacidad del color de acento. */
@Composable
private fun EmptyBubble() {
    val ink = MiniPhoneTheme.colors.accent.copy(alpha = 0.4f)
    Canvas(Modifier.size(88.dp)) {
        val strokeWidth = 2.dp.toPx()
        val bubbleWidth = 60.dp.toPx()
        val bubbleHeight = 40.dp.toPx()
        val left = (size.width - bubbleWidth) / 2f
        val top = size.height * 0.16f
        drawRoundRect(
            color = ink,
            topLeft = Offset(left, top),
            size = Size(bubbleWidth, bubbleHeight),
            cornerRadius = CornerRadius(10.dp.toPx()),
            style = Stroke(width = strokeWidth),
        )
        val dotRadius = 2.5.dp.toPx()
        val dotGap = 8.dp.toPx()
        val dotY = top + bubbleHeight / 2f
        val firstDotX = left + bubbleWidth / 2f - dotGap
        for (index in -1..1) {
            drawCircle(color = ink, radius = dotRadius, center = Offset(firstDotX + index * dotGap, dotY))
        }
        val tail = Path().apply {
            moveTo(left + 12.dp.toPx(), top + bubbleHeight)
            lineTo(left + 6.dp.toPx(), top + bubbleHeight + 11.dp.toPx())
            lineTo(left + 26.dp.toPx(), top + bubbleHeight)
            close()
        }
        drawPath(path = tail, color = ink, style = Stroke(width = strokeWidth))
    }
}

/** Se corrigió el espacio entre avatar y texto. */
private val ThreadTextGap = 12.dp

private const val MAX_BADGE_COUNT: Int = 99
private const val FULL_BADGE_TEXT: String = "99+"
private const val EMPTY_PREVIEW_TEXT: String = "Aún no hay noticias"
private const val GO_CONTACTS_LABEL: String = "Ir a la libreta de direcciones"
