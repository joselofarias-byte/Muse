package io.zer0.muse.ui.common.surface

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseCornerRadius
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseMotion

/**
 * Efecto de presión de tarjeta — El componente central de la interacción táctil.
 *
 * cuando se presiona:
 *  - Degradado de color de fondo (200ms easeOutCubic): Aclaramiento del color claro 55%,El modo oscuro se ilumina ligeramente 18%
 *  - Zoom opcional (0.98): Reduzca ligeramente el tamaño del"caer en"sentido
 *    v1.0.23: La curva de escala cambió de interpolación a spring(MediumBouncy + StiffnessMediumLow),
 *    La presión y el rebote vienen con un ligero exceso.,Proporcionar comentarios flexibles
 *  - Retroalimentación táctil opcional: toque
 *
 * Sin efecto dominó (indication = null), estándar alternativo `clickable {}` Superficie.
 *
 * Uso:
 * ```
 * MuseCardPress(onClick = { ... }) {
 *     Text("Contenido de la tarjeta")
 * }
 * ```
 *
 * @param onClick Haga clic en devolución de llamada
 * @param modifier modificador
 * @param shape forma de tarjeta (Esquinas redondeadas predeterminadas de 12 dp)
 * @param containerColor color de fondo (Predeterminado `surface`)
 * @param elevation altura de la sombra (Predeterminado 0dp, estilo plano)
 * @param enableScale Ya sea para habilitar la compresión y el escalado (Predeterminado false)
 * @param enableHaptic Si habilitar la retroalimentación táctil (Predeterminado true)
 * @param content Contenido de la tarjeta
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MuseCardPress(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(MuseCornerRadius.BUTTON.dp),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    elevation: Dp = MuseElevation.none,
    enableScale: Boolean = false,
    enableHaptic: Boolean = true,
    /** v1.0.16: Devolución de llamada opcional con pulsación larga,Se utiliza para menús de pulsación larga y otros escenarios.;Cuando está vacío, se utiliza un clic normal. */
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val reducedMotion = MuseMotion.isReducedMotion()
    val haptic = LocalHapticFeedback.current

    // Presione gradiente de color: Ambos temas están sólo ligeramente iluminados.,Evite las barras blancas y los destellos fuertes.
    val isLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val pressedColor = if (isLight) {
        containerColor.copy(
            red = (containerColor.red + (1f - containerColor.red) * 0.18f).coerceAtMost(1f),
            green = (containerColor.green + (1f - containerColor.green) * 0.18f).coerceAtMost(1f),
            blue = (containerColor.blue + (1f - containerColor.blue) * 0.18f).coerceAtMost(1f),
        )
    } else {
        containerColor.copy(
            red = (containerColor.red + (1f - containerColor.red) * 0.18f).coerceAtMost(1f),
            green = (containerColor.green + (1f - containerColor.green) * 0.18f).coerceAtMost(1f),
            blue = (containerColor.blue + (1f - containerColor.blue) * 0.18f).coerceAtMost(1f),
        )
    }

    val animatedColor by animateColorAsState(
        targetValue = if (isPressed) pressedColor else containerColor,
        animationSpec = MuseMotion.tween(
            durationMillis = MuseAnimation.TACTILE_MS + 20, // 220ms, Ligeramente más lento que los botones de íconos
            easing = MuseAnimation.EaseOutCubic,
        ),
        label = "card_press_color",
    )

    // v1.0.23: Presione la compresión y cambie a la curva del resorte del resorte.,Mejorar la retroalimentación elástica.
    // La transición de curva lineal entre interpolación original es rígida,spring La amortiguación media + la rigidez media-baja permiten que la tarjeta presione y rebote
    // Viene con un ligero exceso,Simula la sensación física de presionar un material suave con las yemas de los dedos.,Se hace eco de la textura cálida.
    // gradiente de color conservado MuseMotion.tween(Los cambios de color táctiles no requieren elasticidad,Lineal y más estable).
    val animatedScale by animateFloatAsState(
        targetValue = if (isPressed && enableScale) 0.98f else 1f,
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
        },
        label = "card_press_scale",
    )

    // v1.0.16: Seleccione según si hay onLongClick clickable/combinedClickable,Ambos no tienen máscara de ondulación de material.
    val pressModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null, // sin ondulaciones
            // v1.0.74 fix (Auditoría inicial 3.4): suplemento role=Button,Alinear con rama en la que se puede hacer clic
            // (Se perdió la rama Clickable combinada original role,TalkBack No puedo leer la semántica del botón)
            role = Role.Button,
            onClick = {
                if (enableHaptic) {
                    MuseHaptics.light(haptic)
                }
                onClick()
            },
            onLongClick = onLongClick,
        )
    } else {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null, // sin ondulaciones
            role = Role.Button,
            onClick = {
                if (enableHaptic) {
                    MuseHaptics.light(haptic)
                }
                onClick()
            },
        )
    }

    Surface(
        modifier = modifier
            .scale(animatedScale)
            .then(pressModifier),
        shape = shape,
        color = animatedColor,
        shadowElevation = elevation,
    ) {
        content()
    }
}
