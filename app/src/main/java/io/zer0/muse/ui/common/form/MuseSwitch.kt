package io.zer0.muse.ui.common.form

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MuseSwitchSizes

/**
 * v1.51: cambiar — Anular el valor predeterminado de Material3 [androidx.compose.material3.Switch].
 *
 * diferencias visuales(Contraste Material Switch):
 *  - pista de cápsula(51×31dp vs Material 52×32dp pero mas suave)
 *  - pulgar redondo(27dp),Desliza hacia la derecha al abrir,Con animación fluida
 *  - color abierto primary,Cerrar color surfaceVariant(opacity ajustar)
 *  - Pulgar en el trazo principal asegura el contraste.
 *  - Semántica completa a11y:H-SW1 Usar en su lugar Modifier.toggleable(Role.Switch),
 *    Registrar automáticamente acciones de accesibilidad toggleableState + onClick;H-SW2 Paquete exterior Objetivo táctil mínimo de 48 dp.
 *
 * Uso:
 * ```
 * MuseSwitch(checked = enabled, onCheckedChange = { v -> update { it.copy(enabled = v) } })
 * ```
 */
@Composable
fun MuseSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    // CMP-08: Geometría todo va token — órbita/Especificaciones del pulgar ver MuseSwitchSizes,La retracción del pulgar reutiliza MusePaddings.tinyGap.
    val trackWidth = MuseSwitchSizes.trackWidth
    val trackHeight = MuseSwitchSizes.trackHeight
    val thumbSize = MuseSwitchSizes.thumbSize
    val trackPadding = MuseSwitchSizes.trackPadding

    val density = LocalDensity.current
    val trackWidthPx = with(density) { trackWidth.toPx() }
    val trackHeightPx = with(density) { trackHeight.toPx() }
    val thumbSizePx = with(density) { thumbSize.toPx() }
    val trackPaddingPx = with(density) { trackPadding.toPx() }

    // Animación de la posición del pulgar(izquierda/correcto)
    val targetThumbX = if (checked) {
        trackWidthPx - thumbSizePx - trackPaddingPx
    } else {
        trackPaddingPx
    }
    val thumbX by animateFloatAsState(
        targetValue = targetThumbX,
        animationSpec = MuseMotion.tween(MuseAnimation.TACTILE_MS),
        label = "iosSwitchThumb",
    )

    // v2.x: Seguimiento de la transición de color — Conmutación originalmente instantánea(v1.0.74 Registros de auditoría),Los tokens ahora se mueven sincronizados al deslizar el pulgar.
    val trackColor by animateColorAsState(
        targetValue = if (checked) {
            MaterialTheme.colorScheme.primary
        } else {
            // v2.0: La variación de superficie original al 80% tiene solo 1 nivel de color diferente del fondo en el tema claro.(Medición real #FCFCFC vs #FBFBFC),
            // Los orbitales cerrados son casi invisibles;Mezclar en onSurface en su lugar 12%,brillante/Hay pistas claras bajo el tema oscuro.
            androidx.compose.ui.graphics.lerp(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.onSurface,
                0.12f,
            )
        },
        animationSpec = MuseMotion.tween(MuseAnimation.TACTILE_MS),
        label = "iosSwitchTrack",
    )
    val thumbColor = if (checked) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val thumbInnerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)

    // H-SW2: La caja exterior proporciona un objetivo táctil mínimo de 48 dp(La visión sigue siendo 51×31dp,Canvas centro).
    // H-SW1: Utilice Modifier.toggleable en su lugar pointerInput detectTapGestures,
    //        Registro automático Role.Switch + toggleableState + onClick Acciones accesibles,
    //        TalkBack Lee correctamente el estado del interruptor y realiza el cambio de clic.
    // v1.120: pasar explícitamente indication = null Deshabilitar la respuesta de clic predeterminada(ripple/Resaltar rectángulo),
    //         El interruptor en sí tiene una animación de diapositiva con el pulgar como respuesta visual.,La superposición de ondas produce una máscara de caja negra.
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .defaultMinSize(
                minWidth = MuseIconSizes.touchTarget,
                minHeight = MuseIconSizes.touchTarget,
            )
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                onValueChange = { onCheckedChange?.invoke(it) },
                enabled = onCheckedChange != null,
                role = Role.Switch,
            )
            .semantics {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier.size(width = trackWidth, height = trackHeight),
        ) {
            // órbita(forma de cápsula)
            drawRoundRect(
                color = trackColor,
                topLeft = Offset.Zero,
                size = Size(trackWidthPx, trackHeightPx),
                cornerRadius = CornerRadius(trackHeightPx / 2, trackHeightPx / 2),
            )
            // pulgar(redondo)
            drawCircle(
                color = thumbColor,
                radius = thumbSizePx / 2,
                center = Offset(
                    thumbX + thumbSizePx / 2,
                    trackHeightPx / 2,
                ),
            )
            // El anillo interior del pulgar es de color claro cuando se enciende.(decoracion)
            if (checked) {
                drawCircle(
                    color = thumbInnerColor,
                    radius = thumbSizePx / 2 * 0.6f,
                    center = Offset(
                        thumbX + thumbSizePx / 2,
                        trackHeightPx / 2,
                    ),
                )
            }
        }
    }
}
