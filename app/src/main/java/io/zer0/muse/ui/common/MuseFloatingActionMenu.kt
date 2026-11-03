@file:Suppress("FunctionNaming", "MatchingDeclarationName")

package io.zer0.muse.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.fakeGlassSurface

/** Elementos de acción independientes en el menú flotante de la barra superior. */
internal data class MuseFloatingActionItem(
    val key: String,
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val tint: Color? = null,
    val checked: Boolean? = null,
    val onClick: () -> Unit,
)

/**
 * El menú flotante de "Más" en la barra superior.
 *
 * v1.0.90 Reelaboración: resultó ser un menú denso de "panel de fondo gris + líneas divisorias finas línea por línea + marcas de final de línea", que visualmente parece un
 * Formulario de configuración del sistema. Ahora presione la aplicación de conversación principal (GPT etc.) reelaboración del lenguaje:
 *  - Utilice la capa menos profunda de base para el panel (surfaceContainerLowest, el color claro significa blanco puro)+ capas de sombra,
 *    Ya no utilices el fondo gris de SurfaceContainerHigh ni la línea divisoria. —— La jerarquía se expresa mediante espacios en blanco y esquinas redondeadas;
 *  - Un icono circular en el lado izquierdo de cada línea (surfaceVariant Abajo + icono neutro), la etiqueta solo deja texto;
 *  - No verifique el estado del interruptor al final de la línea y cámbielo a**El ícono en sí está iluminado con el color del tema.**(primary icono + pieza de icono del contenedor principal),
 *    El estado se muestra directamente en el icono y no hay más control al final de la línea;
 *  - Con el estrechamiento general y la alta presión a 44dp, el panel ya no ocupa la mitad de la pantalla.
 *
 * Deslizamiento lateral/La concordancia entre la animación gradual y el "colapso automático después de hacer clic" permanece sin cambios.
 */
@Composable
internal fun MuseFloatingActionMenu(
    items: List<MuseFloatingActionItem>,
    onDismiss: () -> Unit,
    offset: IntOffset? = null,
    belowAnchorDp: Dp = 56.dp,
    glassActive: Boolean = false,
) {
    val density = LocalDensity.current
    val reducedMotion = MuseMotion.isReducedMotion()
    val duration = if (reducedMotion) 0 else MuseAnimation.FAST_NORMAL_MS
    val resolvedOffset = offset ?: with(density) {
        IntOffset(0, belowAnchorDp.roundToPx())
    }
    Popup(
        onDismissRequest = onDismiss,
        alignment = Alignment.TopEnd,
        offset = resolvedOffset,
    ) {
        // v2.6.1: Popup Es una ventana independiente.,No es posible el muestreo entre ventanas del LayerBackdrop/LiquidState de la ventana principal
        // (Aparece como una máscara negra + agujero transparente en el borde cuadrado de GraphicsLayer). Ya no se usa aquí glassSurfaceModifier,
        // Pero ve directamente fakeGlassSurface(Base de degradado + borde resaltado + trazo),No se requiere fondo.
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(MuseMotion.tween(duration)) +
                scaleIn(
                    animationSpec = MuseMotion.tween(duration),
                    initialScale = 0.94f,
                    transformOrigin = TransformOrigin(1f, 0f),
                ),
        ) {
            Surface(
                shape = MuseShapes.extraLarge,
                color = if (glassActive) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLowest,
                // Cuando se enciende el vidrio, fakeGlassSurface dibuja automáticamente reflejos en los bordes anchos.,Surface ya no se proyecta,
                // De lo contrario, el relleno transparente + sombra alta expondrá un marco gris en la ventana emergente.(Sin color de relleno).
                shadowElevation = if (glassActive) 0.dp else MuseElevation.high,
                tonalElevation = 0.dp,
                // v2.6.1: Hay más menús asociados con la interfaz principal y el interruptor.Popup Es una ventana independiente y no puede probar vidrio real.,
                // Utilice vidrio falso en su lugar(Base de degradado + borde resaltado + trazo)Presenta una textura de vidrio.
                modifier = Modifier
                    .then(
                        if (glassActive) {
                            Modifier.fakeGlassSurface(
                                shape = MuseShapes.extraLarge,
                                surfaceColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .padding(end = 2.dp, top = 4.dp),
            ) {
                Column(
                    // v1.0.90: Barra superior más menús mantenidos compactos —— Ancho estrecho unificado,Evite que las etiquetas largas queden apretadas prematuramente en puntos suspensivos
                    modifier = Modifier
                        .widthIn(min = 160.dp, max = 192.dp)
                        .padding(vertical = 4.dp),
                ) {
                    items.forEach { item ->
                        key(item.key) { MenuRow(item = item) }
                    }
                }
            }
        }
    }
}

/**
 * Fila del menú: icono circular + etiqueta, se puede hacer clic en toda la fila.
 *
 * estado del interruptor ([MuseFloatingActionItem.checked] es cierto) use el color del tema para iluminar el ícono para expresar y no marque el final de la línea.
 */
@Composable
private fun MenuRow(item: MuseFloatingActionItem) {
    val colors = MaterialTheme.colorScheme
    val isOn = item.checked == true
    val labelAlpha = if (item.enabled) 1f else MuseActionColors.disabledAlpha

    val iconTint = when {
        !item.enabled -> colors.onSurfaceVariant.copy(alpha = labelAlpha)
        item.tint != null -> item.tint ?: colors.onSurfaceVariant
        isOn -> colors.primary
        else -> colors.onSurfaceVariant
    }
    val chipColor = if (isOn && item.enabled) colors.primaryContainer else colors.surfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable(enabled = item.enabled, onClick = item.onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(chipColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface.copy(alpha = labelAlpha),
            modifier = Modifier.weight(1f),
        )
    }
}
