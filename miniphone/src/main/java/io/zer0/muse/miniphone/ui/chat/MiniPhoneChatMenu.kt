package io.zer0.muse.miniphone.ui.chat

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
import io.zer0.muse.miniphone.ui.theme.MiniPhoneColors
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * Mensajes menú de pulsación larga.
 *
 * Utilice su propia capa flotante en lugar del menú predeterminado de Material:El color de fondo, las esquinas redondeadas y el tamaño de fuente se toman de este módulo. token,
 * Misma textura que las burbujas y las tarjetas.,No se volverá blanco de repente ni siquiera bajo colores oscuros.
 *
 * La posición se basa en el diseño principal donde se encuentra este componente.(La persona que llama lo pone en la línea de mensajes.):
 * Priorizar pegar encima de esa línea,Si no cabe en la parte superior, gíralo hacia abajo.;Alinee a izquierda y derecha con el lado donde está el mensaje.
 */
@Composable
internal fun ChatMessageMenu(
    message: ChatMessageUi,
    onDismiss: () -> Unit,
    onAction: (ChatMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = chatMenuActionsFor(message)
    if (actions.isEmpty()) return
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val density = LocalDensity.current
    val gapPx = with(density) { MenuGap.roundToPx() }
    val marginPx = with(density) { dimens.pageMargin.roundToPx() }
    val positionProvider = remember(message.isMine, gapPx, marginPx) {
        ChatMenuPositionProvider(alignEnd = message.isMine, gapPx = gapPx, marginPx = marginPx)
    }
    val shape = RoundedCornerShape(dimens.cardRadius)
    Popup(
        onDismissRequest = onDismiss,
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = modifier
                .testTag(MiniPhoneChatTags.MENU)
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
                        .testTag(MiniPhoneChatTags.menuAction(action))
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

/** Confirmación antes de eliminar un mensaje. Confirmar solo una vez,Después de la confirmación, la máquina se eliminará. */
@Composable
internal fun ChatDeleteConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(dimens.cardRadius)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .testTag(MiniPhoneChatTags.DELETE_DIALOG)
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
                    tag = MiniPhoneChatTags.DELETE_CANCEL,
                    onClick = onDismiss,
                )
                Spacer(Modifier.width(8.dp))
                DialogAction(
                    label = "Eliminar",
                    color = colors.unreadDot,
                    tag = MiniPhoneChatTags.DELETE_CONFIRM,
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
private fun ChatMenuAction.colorIn(colors: MiniPhoneColors) = if (this == ChatMenuAction.DELETE) colors.unreadDot else colors.textPrimary

private val MenuWidth = 132.dp
private val MenuGap = 6.dp
private val DialogWidth = 280.dp
private val DialogActionHeight = 36.dp

private const val DELETE_TITLE: String = "Eliminar este mensaje?"
private const val DELETE_HINT: String = "Elimínelo solo del historial de chat de este teléfono,La sesión en sí no se ve afectada."
