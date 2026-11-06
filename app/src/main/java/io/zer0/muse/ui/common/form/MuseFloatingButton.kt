package io.zer0.muse.ui.common.form

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge

/**
 * botón flotante — sustituto Material3 [androidx.compose.material3.FloatingActionButton].
 *
 * diferencias visuales:
 *  - sin ondulación(Reemplazado con escala presionada + degradado de color)
 *  - redondo [MuseShapes.huge] recipiente,primary fondo de color
 *  - Zoom de 0,92 cuando se presiona(curva de resorte),Retroalimentación de compresión analógica
 *  - 56dp El tamaño predeterminado se ajusta a la especificación FAB del material.
 *
 * Uso:
 * ```
 * MuseFloatingButton(
 *     icon = MuseIcons.plus,
 *     onClick = { importLauncher.launch(...) },
 *     contentDescription = "Complemento de importación",
 * )
 * ```
 *
 * @param icon vector icono
 * @param onClick Haga clic en devolución de llamada
 * @param modifier Modifier
 * @param contentDescription Descripción de accesibilidad(Muy recomendable proporcionar)
 * @param containerColor color del contenedor(Predeterminado primary)
 * @param contentColor color del icono(Predeterminado onPrimary)
 * @param size tamaño del botón(Predeterminado 56dp,Cumplir con las dimensiones predeterminadas del Material FAB)
 * @param iconSize Tamaño visual del icono(Predeterminado [MuseIconSizes.icon])
 */
@Composable
fun MuseFloatingButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    containerColor: Color = MuseActionColors.container,
    contentColor: Color = MuseActionColors.content,
    // CMP-08: ficha para caminar de tamaño(56dp = MuseIconSizes.fab,Cumple con las especificaciones de Material FAB)
    size: Dp = MuseIconSizes.fab,
    iconSize: Dp = MuseIconSizes.icon,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val reducedMotion = MuseMotion.isReducedMotion()

    // Zoom de 0,92 cuando se presiona(curva de resorte),Retroalimentación de compresión analógica
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            )
        },
        label = "ios_fab_scale",
    )

    Surface(
        shape = MuseShapes.huge,
        color = containerColor,
        contentColor = contentColor,
        // Keep the modal shadow stable; pressed feedback is expressed by scale only.
        shadowElevation = MuseElevation.modal,
        tonalElevation = MuseElevation.none,
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .semantics {
                role = Role.Button
                if (contentDescription != null) this.contentDescription = contentDescription
            },
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null, // Ya disponible en semántica.
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
