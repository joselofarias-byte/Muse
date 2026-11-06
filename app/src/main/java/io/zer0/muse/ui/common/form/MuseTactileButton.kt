package io.zer0.muse.ui.common.form

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion

/**
 * icono botón uniforme pieza — Úselo para todos los botones de "solo íconos, sin texto" en toda la aplicación.
 *
 * El área táctil es siempre [size](Predeterminado [MuseIconSizes.touchTarget] = 48dp),
 * El círculo visual está formado por [container] con [visualSize] Decisión:
 *  - [MuseIconContainer.None]  Sin color de fondo, icono en línea (visual = área táctil)
 *  - [MuseIconContainer.Tonal] Icono de barra superior e inferior redondo claro (visual = 36dp)
 *  - [MuseIconContainer.Solid] Color del tema fondo redondo, operación principal.
 *  - [MuseIconContainer.Neutral] Fondo neutro, no seleccionado/Secundaria
 *
 * Comentarios de prensa:Degradado de color del icono (200ms easeOutCubic) Compensación del 35%,
 * Superposición de escala de 0,92x cuando hay un fondo; no aparecen ondas de material.
 *
 * Uso:
 * ```
 * MuseTactileButton(
 *     icon = MuseIcons.sliders,
 *     onClick = { ... },
 *     contentDescription = "ajustes",
 *     container = MuseIconContainer.Tonal,
 * )
 * ```
 *
 * @param icon vector icono
 * @param onClick Haga clic en devolución de llamada
 * @param modifier modificador
 * @param size Diámetro del área táctil (Predeterminado [MuseIconSizes.touchTarget] = 48dp)
 * @param iconSize Tamaño visual del icono (Predeterminado [MuseIconSizes.icon] = 24dp)
 * @param contentDescription Descripción de accesibilidad (obligatoria para los botones de íconos)
 * @param tint Color del icono sin color de fondo (Predeterminado `onSurface`; Cuando hay un color de fondo, el [container] decidir)
 * @param enabled ¿Está disponible?
 * @param container Variante de color base
 * @param visualSize Diámetro del color de fondo circular; El valor predeterminado no es ningún color de fondo. [size], dependiendo del color de fondo [MuseIconSizes.topBarSolid] = 36dp
 */
@Composable
fun MuseTactileButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = MuseIconSizes.touchTarget,
    iconSize: Dp = MuseIconSizes.icon,
    contentDescription: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    container: MuseIconContainer = MuseIconContainer.None,
    visualSize: Dp? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val (rawContainer, rawContent) = when (container) {
        MuseIconContainer.None -> Color.Transparent to tint
        MuseIconContainer.Tonal -> MuseActionColors.tonalContainer to MuseActionColors.tonalContent
        MuseIconContainer.Solid -> MuseActionColors.container to MuseActionColors.content
        MuseIconContainer.Neutral -> MuseActionColors.neutralContainer to MuseActionColors.neutralContent
    }

    // La dirección del cambio de color presionado depende del "color de fondo presionado real": si no hay color de fondo, mire la superficie de la página, si hay un color de fondo, mire el color del contenedor.
    val pressBase = if (container == MuseIconContainer.None) {
        MaterialTheme.colorScheme.surface
    } else {
        rawContainer
    }
    val isLight = pressBase.luminance() > 0.5f
    val pressedColor = if (isLight) lighten(rawContent, 0.35f) else darken(rawContent, 0.65f)

    val targetTint = when {
        !enabled -> rawContent.copy(alpha = MuseActionColors.disabledAlpha)
        isPressed -> pressedColor
        else -> rawContent
    }
    val animatedTint by animateColorAsState(
        targetValue = targetTint,
        animationSpec = MuseMotion.tween(
            durationMillis = MuseAnimation.TACTILE_MS,
            easing = MuseAnimation.EaseOutCubic,
        ),
        label = "tactile_tint",
    )

    val hasContainer = container != MuseIconContainer.None
    val scale by animateFloatAsState(
        targetValue = if (isPressed && hasContainer) 0.92f else 1f,
        animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS),
        label = "tactile_scale",
    )

    val effectiveVisual = visualSize ?: if (hasContainer) MuseIconSizes.topBarSolid else size

    // Nota: Color.Transparent no se puede utilizar directamente copy(alpha) —— Transparent si“negro + alpha 0”,
    // copy(alpha = 1f) Se volverá negro opaco y el botón del ícono sin color de fondo se convertirá en un círculo negro sólido.
    // Por lo tanto, cuando no hay color de fondo, se debe utilizar tal cual (para mantener la verdadera transparencia); sólo cuando tenga un color de fondo, deshabilite la reducción de transparencia.
    val boxColor = if (!enabled && rawContainer != Color.Transparent) {
        rawContainer.copy(alpha = MuseActionColors.disabledAlpha)
    } else {
        rawContainer
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(effectiveVisual)
                .clip(CircleShape)
                .background(boxColor)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
                tint = animatedTint,
            )
        }
    }
}

/** Variante de color de fondo del botón del icono. */
enum class MuseIconContainer {
    /** Sin color de fondo, iconos en línea. */
    None,

    /** Fondo redondo claro (botón icono de la barra superior). */
    Tonal,

    /** Fondo redondo de color temático (operación principal). */
    Solid,

    /** Fondo neutro (secundario/no seleccionado). */
    Neutral,
}

private fun lighten(color: Color, ratio: Float): Color = color.copy(
    red = (color.red + (1f - color.red) * ratio).coerceAtMost(1f),
    green = (color.green + (1f - color.green) * ratio).coerceAtMost(1f),
    blue = (color.blue + (1f - color.blue) * ratio).coerceAtMost(1f),
)

private fun darken(color: Color, factor: Float): Color = color.copy(
    red = (color.red * factor).coerceAtLeast(0f),
    green = (color.green * factor).coerceAtLeast(0f),
    blue = (color.blue * factor).coerceAtLeast(0f),
)
