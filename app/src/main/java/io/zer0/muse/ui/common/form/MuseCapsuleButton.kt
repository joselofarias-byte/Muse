package io.zer0.muse.ui.common.form

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseCapsuleButtonSizes
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge

/**
 * botón de cápsula — sustituto Material3 [Button]/[OutlinedButton]/[TextButton].
 *
 * Visión:contenido de ancho completo o abrazado,48dp altura mínima,24dp esquinas redondeadas([MuseShapes.huge]),
 * Zoom ligeramente cuando se presiona(0.97x)No hay ondas. Color de marca para el botón principal./Fondo negro + texto blanco,
 * El botón secundario usa el color del contenedor del tema + su color inverso, y el botón de texto usa un fondo transparente + color primario.
 *
 * Cuando la página comercial requiere tres formularios: "icono + texto", "eliminar botón rojo" y "en progreso",
 * Utilice siempre este componente [leadingIcon] / [destructive] / [loading], no recurras al botón nativo.
 *
 * @param text texto del botón
 * @param onClick Haga clic en devolución de llamada
 * @param modifier modificador
 * @param enabled ¿Se puede hacer clic?
 * @param variant Variaciones de estilo de botón [IosCapsuleButtonVariant.Primary]/[Secondary]/[Text]
 * @param fillWidth Ya sea para llenar el ancho disponible(Predeterminado true)
 * @param leadingIcon Icono frontal opcional
 * @param trailingIcon Iconos de publicación opcionales (casilla de verificación, indicación de estado, etc.)
 * @param loading En progreso:Mostrar círculos pequeños y desactivar clics
 * @param destructive Operación peligrosa(Eliminar/Borrar):El contenedor tiene el color del tema incorrecto y está cubierto. [variant] color de fondo
 */
@Composable
fun MuseCapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: IosCapsuleButtonVariant = IosCapsuleButtonVariant.Primary,
    fillWidth: Boolean = true,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    loading: Boolean = false,
    destructive: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) MuseCapsuleButtonSizes.pressScale else 1f,
        animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS),
        label = "capsuleBtnScale",
    )

    // Calibre de color del tema: el botón principal adopta un color principal de tema muy saturado; los botones secundarios adoptan un color de contenedor de tema poco saturado;
    // destructive Sobrescriba los dos primeros y tome el color incorrecto del tema (elimine/operaciones claras).
    val (backgroundColor, contentColor) = when {
        destructive -> MuseActionColors.dangerContainer to MuseActionColors.dangerContent
        variant == IosCapsuleButtonVariant.Primary -> MuseActionColors.container to MuseActionColors.content
        variant == IosCapsuleButtonVariant.Secondary -> MuseActionColors.tonalContainer to MuseActionColors.tonalContent
        else -> Color.Transparent to MaterialTheme.colorScheme.primary
    }
    val transparentContainer = variant == IosCapsuleButtonVariant.Text && !destructive

    val clickable = enabled && !loading
    // En progreso no está deshabilitado: todavía aparece como una apariencia utilizable altamente saturada, simplemente no acepta clics.
    // v1.0.92: Desactivar fondo dividido/Transparencia de contenido — En la implementación anterior, ambos multiplicados por el mismo bajo alpha,
    // Bajo el tema claro, el botón aparece como un bloque gris sólido y el texto es casi invisible.(Comentarios de los usuarios:Botón de traducción deshabilitado como bloque negro).
    val containerAlpha = if (clickable || loading) 1f else MuseActionColors.disabledAlpha
    val contentAlpha = if (clickable || loading) 1f else 0.75f

    val boxModifier = if (fillWidth) {
        modifier.fillMaxWidth()
    } else {
        modifier
    }

    Box(
        modifier = boxModifier
            .heightIn(min = MuseIconSizes.touchTarget)
            .clip(MuseShapes.huge)
            .background(
                color = if (transparentContainer) {
                    Color.Transparent
                } else {
                    backgroundColor.copy(alpha = containerAlpha)
                },
                shape = MuseShapes.huge,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                enabled = clickable,
                onClick = onClick,
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .padding(horizontal = MusePaddings.messageGap, vertical = MusePaddings.itemGap),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (loading) {
                MuseSpinner(
                    size = MuseIconSizes.iconSmall,
                    color = contentColor.copy(alpha = contentAlpha),
                )
                Spacer(Modifier.width(MusePaddings.contentGap))
            } else if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = contentAlpha),
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
                Spacer(Modifier.width(MusePaddings.contentGap))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = contentColor.copy(alpha = contentAlpha),
                textAlign = TextAlign.Center,
                // Los botones de cápsula son siempre de una sola línea: en pantallas estrechas/tamaños de fuente grandes, es mejor usar elipses que comprimir el texto en filas verticales.
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailingIcon != null) {
                Spacer(Modifier.width(MusePaddings.contentGap))
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = contentAlpha),
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
            }
        }
    }
}

enum class IosCapsuleButtonVariant {
    Primary,
    Secondary,
    Text,
}
