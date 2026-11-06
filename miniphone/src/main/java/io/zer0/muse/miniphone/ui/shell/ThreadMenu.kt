package io.zer0.muse.miniphone.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.zer0.muse.miniphone.ui.chat.ChatMenuPositionProvider
import io.zer0.muse.miniphone.ui.theme.MiniPhoneColors
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * Qué puedes hacer manteniendo presionada una conversación.
 *
 * El valor sólo describe la "operación",[label] Es esa línea en el menú.;Que un determinado elemento deba aparecer o no depende de [threadMenuActionsFor] decidir,
 * La interfaz solo dibuja en orden los artículos devueltos.
 */
internal enum class ThreadMenuAction(
    val label: String,
) {
    PIN("fijar hacia arriba"),
    UNPIN("Desanclar"),
    MARK_READ("Marcar como leído"),
    DELETE("Eliminar sesión"),
}

/**
 * Mantenga presionado el elemento del menú para una conversación.,El orden es el orden de presentación.
 *
 * - Anclar/desanclar:Dos direcciones para lo mismo,Sólo aparece el ejecutable actualmente
 * - Marcar como leído:Cuando el valor no leído es 0, no hay nada que borrar.,No es necesario colocar un solo punto y no habrá respuesta.
 * - Eliminar sesión:siempre hay
 */
internal fun threadMenuActionsFor(thread: MiniPhoneThreadUi): List<ThreadMenuAction> = buildList {
    add(if (thread.pinned) ThreadMenuAction.UNPIN else ThreadMenuAction.PIN)
    if (thread.unreadCount > 0) add(ThreadMenuAction.MARK_READ)
    add(ThreadMenuAction.DELETE)
}

/** Devolución de llamada de acción en la línea de sesión. Los tres solo traen sesiones id,La interfaz no necesita saber en qué tabla escribir. */
@Immutable
internal class ThreadMenuActions(
    val onTogglePin: (threadId: String, pinned: Boolean) -> Unit = { _, _ -> },
    val onMarkRead: (threadId: String) -> Unit = {},
    val onDelete: (threadId: String) -> Unit = {},
)

/**
 * Menú de sesión de pulsación larga.
 *
 * El mismo conjunto de capas flotantes autodibujadas que el menú de mensajes en la página de chat:El color de fondo, las esquinas redondeadas y el tamaño de fuente se toman de este módulo. token,
 * Deshabilite la ventana emergente predeterminada de Material. La posición es el diseño principal donde lo colocó la persona que llama.(línea de sesión)Sujeto a:
 * Priorizar pegar encima de la línea,Si no cabe en la parte superior, gíralo hacia abajo.,Los lados izquierdo y derecho están intercalados dentro de los márgenes de la página.
 */
@Composable
internal fun ThreadRowMenu(
    thread: MiniPhoneThreadUi,
    onDismiss: () -> Unit,
    onAction: (ThreadMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = threadMenuActionsFor(thread)
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val density = LocalDensity.current
    val gapPx = with(density) { MenuGap.roundToPx() }
    val marginPx = with(density) { dimens.pageMargin.roundToPx() }
    val positionProvider = remember(gapPx, marginPx) {
        ChatMenuPositionProvider(alignEnd = false, gapPx = gapPx, marginPx = marginPx)
    }
    val shape = RoundedCornerShape(dimens.cardRadius)
    Popup(
        onDismissRequest = onDismiss,
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = modifier
                .testTag(MiniPhoneTags.THREAD_MENU)
                .width(MenuWidth)
                .clip(shape)
                .background(colors.surface)
                .border(dimens.hairline, colors.divider, shape),
        ) {
            actions.forEachIndexed { index, action ->
                if (index > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(dimens.hairline)
                            .background(colors.divider),
                    )
                }
                Text(
                    text = action.label,
                    style = typography.body.copy(color = action.colorIn(colors)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(MiniPhoneTags.threadMenuAction(action))
                        .clickable {
                            onAction(action)
                            onDismiss()
                        }
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                )
            }
        }
    }
}

/** Confirme antes de eliminar la sesión. Si lo eliminas, desaparecerá.:La conversación desaparece junto con los mensajes que contiene. */
@Composable
internal fun ThreadDeleteConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(dimens.cardRadius)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .testTag(MiniPhoneTags.THREAD_DELETE_DIALOG)
                .width(DialogWidth)
                .clip(shape)
                .background(colors.surface)
                .border(dimens.hairline, colors.divider, shape)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                text = DELETE_TITLE,
                style = typography.title.copy(color = colors.textPrimary),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = DELETE_HINT,
                style = typography.caption.copy(color = colors.textSecondary),
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                DialogAction(
                    label = "Cancelar",
                    color = colors.textSecondary,
                    tag = MiniPhoneTags.THREAD_DELETE_CANCEL,
                    onClick = onDismiss,
                )
                Spacer(Modifier.width(8.dp))
                DialogAction(
                    label = "Eliminar",
                    color = colors.unreadDot,
                    tag = MiniPhoneTags.THREAD_DELETE_CONFIRM,
                    onClick = onConfirm,
                )
            }
        }
    }
}

/** Un botón de texto en el cuadro de confirmación.:Sin fondo, dejando suficiente área de presión. */
@Composable
private fun DialogAction(label: String, color: Color, tag: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MiniPhoneTheme.typography.body.copy(color = color),
        modifier = Modifier
            .testTag(tag)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .heightIn(min = DialogActionHeight)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** acción peligrosa(Eliminar)Usa rojo ladrillo,Usa el color principal para el resto. */
private fun ThreadMenuAction.colorIn(colors: MiniPhoneColors): Color =
    if (this == ThreadMenuAction.DELETE) colors.unreadDot else colors.textPrimary

private val MenuWidth = 132.dp
private val MenuGap = 6.dp
private val DialogWidth = 280.dp

private val DialogActionHeight = 36.dp

private const val DELETE_TITLE: String = "Eliminar esta conversación?"
private const val DELETE_HINT: String = "La conversación y todos los mensajes que contenga se eliminarán de este teléfono.,No se puede recuperar."
