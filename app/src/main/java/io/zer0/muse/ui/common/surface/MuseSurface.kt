package io.zer0.muse.ui.common.surface

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MuseShapes

/**
 * Muse UI Kit — Primitivas de contenedor [MuseSurface].
 *
 * v1.0.26: Unifique la implementación de todas las tarjetas/contenedores de grupos/rectángulos en los que se puede hacer clic,
 * eliminar manuales `Modifier.clip().background().shadow()` La combinación produce un borde gris grueso sobre un fondo claro,
 * Comportamiento inestable como penetración de sombras y bloques de color anidados.
 *
 * Características:
 *  - por Material3 [Surface] Procesamiento unificado clip / color / shadow,Consigue tonos suaves y naturales
 *  - Comentarios de prensa opcionales(gradiente de color + táctil,sin ondulaciones)
 *  - Compresión opcional para expandir.(Desactivado de forma predeterminada)
 *  - El color presionado cambia hacia el blanco en el modo claro y hacia el negro en el modo oscuro.,Adaptarse al tema del papel cálido
 *
 * Yohara [io.zer0.muse.ui.common.surface.MuseCardPress] diferencia:
 *  - De superficie, no manual Surface + scale + clickable,Más estable y más ligero
 *  - denominación unificada `Muse` espacio de nombres
 *  - No obligatorio `indication = null`,Determinado por la persona que llama(pressed Cuando el gradiente de color es suficiente retroalimentación)
 *
 * Uso:
 * ```
 * // tarjeta estática
 * MuseSurface(shape = MuseShapes.extraLarge, elevation = MuseElevation.card) {
 *     Text("contenido")
 * }
 * // tarjetas en las que se puede hacer clic(Respuesta automática de prensa + háptica)
 * MuseSurface(
 *     onClick = { ... },
 *     shape = MuseShapes.extraLarge,
 *     elevation = MuseElevation.card,
 * ) {
 *     Text("tarjetas en las que se puede hacer clic")
 * }
 * ```
 *
 * @param onClick Haga clic en devolución de llamada(null Indica que no se puede hacer clic,Contenedor de exhibición puro)
 * @param modifier modificador
 * @param shape forma(Predeterminado extraLarge,es decir, esquinas redondeadas de 20 dp,En línea con las principales esquinas redondeadas de la tarjeta.)
 * @param color color de fondo(Predeterminado surface,Material3 La superposición de tonos tonalElevation se manejará automáticamente)
 * @param elevation altura de la sombra(Predeterminado 0dp,pegatina plana;Sugerencias de escenas de cartas 1-2dp)
 * @param tonalElevation elevación de tono(Predeterminado 0dp,Evite bloques de colores anidados en modo claro)
 * @param border Borde opcional(Predeterminado null,Sugerencias de escenas de cartas 0.5dp outlineVariant)
 * @param enablePressedFeedback Si se debe habilitar la retroalimentación del gradiente de color de la prensa(solo [onClick] Válido cuando no está vacío,Predeterminado true)
 * @param enableHaptic Si habilitar la retroalimentación táctil(Predeterminado true)
 * @param enableScale Ya sea para habilitar la compresión y el escalado(Predeterminado false,Sólo se recomienda activarlo en escenarios especiales como tarjetas grandes.)
 * @param content contenido
 */
@Composable
fun MuseSurface(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    shape: Shape = MuseShapes.extraLarge,
    color: Color = MaterialTheme.colorScheme.surface,
    elevation: Dp = MuseElevation.none,
    tonalElevation: Dp = MuseElevation.none,
    border: BorderStroke? = null,
    enablePressedFeedback: Boolean = true,
    enableHaptic: Boolean = true,
    enableScale: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (onClick == null) {
        // Contenedor de exhibición puro:Sin comentarios de prensa,Usar directamente Surface
        Surface(
            modifier = modifier,
            shape = shape,
            color = color,
            tonalElevation = tonalElevation,
            shadowElevation = elevation,
            border = border,
            content = { content() },
        )
        return
    }

    // contenedor en el que se puede hacer clic:Presione gradiente de color + retroalimentación táctil(sin ondulaciones)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current

    // Presione gradiente de color: Ambos temas están sólo ligeramente iluminados.,Evite las barras blancas y los destellos fuertes.
    val isLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val pressedColor = if (isLight) {
        color.copy(
            red = (color.red + (1f - color.red) * 0.18f).coerceAtMost(1f),
            green = (color.green + (1f - color.green) * 0.18f).coerceAtMost(1f),
            blue = (color.blue + (1f - color.blue) * 0.18f).coerceAtMost(1f),
        )
    } else {
        color.copy(
            red = (color.red + (1f - color.red) * 0.18f).coerceAtMost(1f),
            green = (color.green + (1f - color.green) * 0.18f).coerceAtMost(1f),
            blue = (color.blue + (1f - color.blue) * 0.18f).coerceAtMost(1f),
        )
    }

    val animatedColor by animateColorAsState(
        targetValue = if (isPressed && enablePressedFeedback) pressedColor else color,
        animationSpec = MuseMotion.tween(
            durationMillis = MuseAnimation.TACTILE_MS,
            easing = MuseAnimation.EaseOutCubic,
        ),
        label = "muse_surface_color",
    )

    Surface(
        modifier = modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            role = Role.Button,
            onClick = {
                if (enableHaptic) {
                    MuseHaptics.light(haptic)
                }
                onClick()
            },
        ),
        shape = shape,
        color = animatedColor,
        tonalElevation = tonalElevation,
        shadowElevation = elevation,
        border = border,
        content = { content() },
    )
}
