package io.zer0.muse.ui.common.form

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge

/**
 * Seleccionar componentes de la cápsula — Reemplaza Material3 FilterChip / AssistChip.
 *
 * características visuales:
 *  - Estado seleccionado:primary Color de fondo + texto onPrimary + peso de fuente seminegrita
 *  - No seleccionado:surfaceVariant translúcido(0.5 alpha)+ onSurfaceVariant Texto + Peso de fuente medio
 *  - esquinas redondeadas [MuseShapes.semiLarge],Ninguno ripple(Surface onClick Predeterminado Ninguno ripple)
 *  - Opcional leadingIcon / trailingIcon(como el botón de cerrar X)
 *  - Estado no disponible:alpha 0.38 + Deshabilitar clics(Surface enabled=false)
 *  - CMP-04: Semántica de las elecciones parciales(role=Checkbox + selected)con toque mínimo de 48dp
 *
 * usado para:Filtrado de etiquetas, cambio de categorías, etiquetas que se pueden cerrar, selección rápida de plantillas de palabras y otros escenarios.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MuseChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    // UI-FIX A: seleccionado=Texto blanco sobre fondo negro sólido; sin marcar=Base neutra opaca.
    // La implementación anterior usaba SurfaceVariant@50% de translucidez cuando no estaba marcada, presionando el contenido como una máscara, que ha sido cancelada.
    // v2.x: efectos interactivos — Presione para comprimir y expandir + transición elástica del color del estado seleccionado(Comentarios de autodibujo,sin ondulaciones)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS),
        label = "chipPress",
    )
    val bgColor by animateColorAsState(
        targetValue = if (selected) MuseActionColors.container else MuseActionColors.neutralContainer,
        animationSpec = MuseMotion.tween(MuseAnimation.TACTILE_MS),
        label = "chipBg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MuseActionColors.content else MuseActionColors.neutralContent,
        animationSpec = MuseMotion.tween(MuseAnimation.TACTILE_MS),
        label = "chipContent",
    )
    Surface(
        shape = MuseShapes.semiLarge,
        color = bgColor,
        contentColor = contentColor,
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .alpha(if (enabled) 1f else 0.38f)
            // CMP-04/A11Y-01: Seleccionar Semántica + Toque Mínimo 48dp(TalkBack legible"seleccionado")
            .heightIn(min = 48.dp)
            .semantics {
                this.selected = selected
                role = Role.Checkbox
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = MusePaddings.itemGap, vertical = MusePaddings.labelVerticalGap),
        ) {
            if (leadingIcon != null) leadingIcon()
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
            if (trailingIcon != null) trailingIcon()
        }
    }
}
