package io.zer0.muse.ui.common.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * Fila de elementos de configuración con interruptores — Icono izquierdo + título + subtítulo,Interruptor derecho.
 *
 * Alinee el interruptor de configuración del sistema visualmente.
 * CMP-09: Única implementación de la línea de conmutación en todo el proyecto.(Ex gerente general `SwitchRow` Eliminado y movido todo aquí.);
 * El icono puede estar vacío y el subtítulo puede estar vacío.,La diferencia morfológica entre ambos solo se expresa mediante parámetros.,No más segundos conjuntos de componentes.
 *
 * @param icon icono izquierdo(null no se mostrará)
 * @param title título principal
 * @param subtitle subtítulo(null no se mostrará)
 * @param checked estado del interruptor
 * @param onCheckedChange cambiar de devolución de llamada
 * @param contentPadding Relleno de contenido de fila. Predeterminado [MusePaddings.cardInner],con
 *   [SettingsGroup] / [SettingsItemRow] alineación de líneas dentro;formulario de ancho completo(Añadido a sí mismo
 *   `MusePaddings.screen` Márgenes horizontales y `MuseTextField` Misma columna)pasar `PaddingValues(0.dp)`,
 *   Evite la doble sangría.
 */
@Composable
fun SettingsSwitchRow(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentPadding: PaddingValues = MusePaddings.cardInner,
) {
    val enabledText = stringResource(R.string.common_state_enabled)
    val disabledText = stringResource(R.string.common_state_disabled)
    val rowInteractionSource = remember { MutableInteractionSource() }
    val isRowPressed by rowInteractionSource.collectIsPressedAsState()
    val colorScheme = MaterialTheme.colorScheme
    val isLight = colorScheme.surface.luminance() > 0.5f
    val pressColor = if (isLight) Color.Black.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.08f)
    val rowBgColor by animateColorAsState(
        targetValue = if (isRowPressed) pressColor else Color.Transparent,
        animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS, easing = MuseAnimation.EaseOutCubic),
        label = "settingsSwitchRowBg",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBgColor)
            .clickable(
                interactionSource = rowInteractionSource,
                indication = null,
            ) { onCheckedChange(!checked) }
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        if (icon != null) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = MuseShapes.small,
                color = colorScheme.primaryContainer.copy(alpha = 0.15f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                // LAYOUT-01: El título está restringido. Sólo el ancho del peso no es suficiente. —— Cuando el ancho restante es extremadamente pequeño, el ajuste palabra por palabra hará que toda la línea sea más alta.
                // Cortar de tarjeta exterior(Patrones de accidentes en línea). El límite de 1 línea + puntos suspensivos es el último recurso.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        MuseSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics {
                stateDescription = if (checked) enabledText else disabledText
            },
        )
    }
}
