package io.zer0.muse.ui.common.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * Línea de configuración común — Icono izquierdo + título + subtítulo,Contenido final a la derecha.
 *
 * Alinee visualmente los elementos de la lista de configuraciones del sistema.
 *
 * @param icon icono izquierdo(null no se mostrará)
 * @param title título principal
 * @param subtitle subtítulo(null no se mostrará,pequeñas letras grises)
 * @param onClick Haga clic en devolución de llamada(null no se puede hacer clic)
 * @param enabled Ya sea para habilitar el clic(Predeterminado true;false El objeto visual permanece sin cambios pero el clic no es válido.)
 * @param trailing Contenido final a la derecha(Vacío predeterminado,Puede contener flechas/valores/interruptores, etc.)
 */
@Deprecated(
    "CMP-06: co-exists with MuseListItem; consolidate to MuseListItem",
)
@Composable
fun SettingsItemRow(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    titleMaxLines: Int = 1,
    trailing: @Composable (() -> Unit)? = null,
) {
    val rowInteractionSource = remember { MutableInteractionSource() }
    val isRowPressed by rowInteractionSource.collectIsPressedAsState()
    // prensa: blanco/desplazamiento negro 55%, 220ms easeOutCubic
    val colorScheme = MaterialTheme.colorScheme
    val isLight = colorScheme.surface.luminance() > 0.5f
    val pressColor = if (isLight) Color.Black.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.08f)
    val rowBgColor by animateColorAsState(
        targetValue = if (isRowPressed) pressColor else Color.Transparent,
        animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS, easing = MuseAnimation.EaseOutCubic),
        label = "settingsItemBg",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBgColor)
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(
                        interactionSource = rowInteractionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        if (icon != null) {
            // 36dp ranura de icono(Bloque de color de fondo redondeado de 8 dp)
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
                maxLines = titleMaxLines.coerceAtLeast(1),
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
        if (trailing != null) {
            trailing()
        }
    }
}
