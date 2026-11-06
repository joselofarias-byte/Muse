package io.zer0.muse.ui.common.state

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill

/**
 * Unificar componentes de estado vacío,Todas las páginas de la lista están multiplexadas en estado vacío.
 *
 * v1.0.20 Actualización visual:
 *  - El ícono cambió de un ícono desnudo gris frío a un contenedor de fondo redondo y cálido(primary 8% Ícono inferior + principal 50%)
 *  - Crea una textura suave y cálida.
 *  - Mantener la firma del parámetro sin cambios,20 Cero cambios para la persona que llama
 *
 * v1.0.23 Efecto de micromovimiento de admisión.:
 *  - Fundido + deslizar hacia arriba(20px)Transición,Mejorar la sofisticación de la página de estado
 *  - uso [MuseAnimation.NORMAL_MS](240ms)+ [MuseAnimation.EaseOutCubic] Curva,
 *    Consistente con el ritmo de presión de la tarjeta y cambio de panel.
 *  - Primer fotograma retrasado 1 fotograma(LaunchedEffect gatillo),Asegúrese de que AnimatedVisibility realice la transición de entrada correctamente
 *
 * @param icon icono superior(Bandeja de entrada predeterminada,semántica de datos vacíos)
 * @param title Copia del título
 * @param subtitle copia de subtítulos(Opcional,Descripción gris)
 * @param actionText Copiar botón de acción(Opcional,Provisto de [onAction] Mostrar juntos)
 * @param onAction Devolución de llamada del botón de acción(Opcional)
 * @param modifier modificador
 */
@Composable
fun MuseEmptyState(
    icon: ImageVector = MuseIcons.inbox,
    title: String,
    subtitle: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // v1.0.23: Activar la transición de entrada cuando se combina por primera vez(AnimatedVisibility inicial visible=true No se activa ninguna animación,
    // debe comenzar desde false → true Sonará cuando salte. enter).
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS, easing = MuseAnimation.EaseOutCubic),
        ) + slideInVertically(
            animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS, easing = MuseAnimation.EaseOutCubic),
            // v2.6.7: El desplazamiento recibió el 5%. Originalmente la mitad de la altura (50%), para grandes piezas de contenido, como estados vacíos
            // Se muestra como"Toda la pieza fue levantada"; El estado corto debe aumentar suave y ligeramente.
            initialOffsetY = { (it * MuseAnimation.SLIDE_FRACTION_CONTENT).toInt() },
        ),
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(MusePaddings.screen * 2),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            // Iconos pequeños (ya no envueltos en un círculo grande)
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(MuseIconSizes.iconLarge), // 32dp
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), // gris
            )
            // Título
            if (title.isNotBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // subtítulo
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }
            // Botón de acción (si lo hay)
            if (actionText != null && onAction != null) {
                Button(
                    onClick = onAction,
                    shape = MuseShapes.pill,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(actionText)
                }
            }
        }
    }
}
