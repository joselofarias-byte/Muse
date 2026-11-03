package io.zer0.muse.ui.common.media

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.zer0.muse.ui.common.surface.MuseDialogWindowEffect
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * P2-13: Elemento del menú contextual del escritorio.
 *
 * @param label mostrar texto(localizado)
 * @param icon icono opcional(Alinear los hábitos de las aplicaciones de escritorio,Mostrar a la izquierda)
 * @param onClick Haga clic en devolución de llamada(El componente automáticamente dismiss)
 * @param destructive ¿Es una operación peligrosa?(Eliminar etc),true El icono y el texto cambian a color rojo de error.
 */
data class ContextMenuItem(
    val label: String,
    val icon: ImageVector? = null,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
)

/**
 * P2-13: Menú contextual del botón derecho del escritorio.
 *
 * Puntos de diseño(Alinear el contexto de la aplicación de escritorio,en lugar de celular ActionSheet):
 *  - recipiente:Surface + [MuseShapes.medium] esquinas redondeadas + color de superficie + ninguno tonalElevation
 *  - Ancho:Fijo 220dp(Alinear el ancho del menú contextual de macOS/VS Code)
 *  - artículo:Icono izquierdo + texto + área de clic de ancho completo,Altamente satisfecho [MuseIconSizes.touchTarget] linea roja
 *  - Artículos peligrosos([ContextMenuItem.destructive] = true)Resaltar con error en rojo
 *  - La transparencia cambia al presionar(sustituto ripple,con [MuseDialog] Estilo de botón consistente)
 *  - No usado Material3 AlertDialog / Button(Cumplir con las restricciones de tokens de diseño del proyecto.)
 *
 * mostrar ubicación:Compose `Dialog` Centrado por defecto,Suficiente para usar en contexto de escritorio;Si necesitas seguir el cursor más tarde,
 * Se puede utilizar en el exterior `Popup` + offset sustituto,Pero está fuera del alcance de esta tarea.
 *
 * @param items Lista de elementos del menú(El orden es el orden de visualización.)
 * @param onDismiss Cerrar la devolución de llamada después de que el usuario haga clic en externo/Esc/seleccione el elemento del menú
 */
@Composable
fun DesktopContextMenu(items: List<ContextMenuItem>, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            // contexto de escritorio:Haga clic afuera para cerrar + Esc para cerrar(Alinear los hábitos de las aplicaciones de escritorio)
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
            decorFitsSystemWindows = false,
        ),
    ) {
        MuseDialogWindowEffect()
        Surface(
            shape = MuseShapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = MuseElevation.low,
            shadowElevation = MuseElevation.modal,
            modifier = Modifier.widthIn(min = 200.dp, max = 240.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = MusePaddings.tightGap),
            ) {
                items.forEachIndexed { index, item ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.height(MusePaddings.tightGap))
                    }
                    ContextMenuRow(item = item, onDismiss = onDismiss)
                }
            }
        }
    }
}

/**
 * Fila de elemento de menú único — Área de clic de ancho completo + icono izquierdo + texto + información de transparencia de prensa.
 */
@Composable
private fun ContextMenuRow(item: ContextMenuItem, onDismiss: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS),
        label = "contextMenuRowScale",
    )
    val tint = if (item.destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MuseIconSizes.touchTarget)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = {
                    item.onClick()
                    onDismiss()
                },
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .padding(horizontal = MusePaddings.screen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding),
    ) {
        if (item.icon != null) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
        } else {
            // Marcador de posición cuando no hay ningún icono,Mantener el texto alineado
            Spacer(modifier = Modifier.width(MuseIconSizes.iconMedium))
        }
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = tint,
        )
    }
}
