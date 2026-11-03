package io.zer0.muse.ui.common.surface

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseCornerRadius

/**
 * Recipiente de vidrio esmerilado.
 *
 * Proporciona efectos visuales de vidrio esmerilado para campos de entrada u otros componentes.:
 *  - fondo de color de superficie translúcida
 *  - outline@20% alpha frontera (1px)
 *  - esquinas redondeadas 20dp
 *
 * Nota: Compose El filtro de fondo nativo debe coincidir [androidx.compose.ui.graphics.RenderEffect]
 * Solo disponible en Android 12+, Utilice el esquema de compatibilidad aquí (Translucidez + simulación de micro-desenfoque).
 *
 * Uso:
 * ```
 * MuseGlassContainer {
 *     // Contenido del campo de entrada
 * }
 * ```
 *
 * @param modifier modificador
 * @param cornerRadius radio de esquina (Predeterminado 20dp)
 * @param blurRadius radio de desenfoque (Predeterminado 0dp, Solo tiene efecto en Android 12+)
 * @param content Contenido del contenedor
 */
@Composable
fun MuseGlassContainer(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = MuseCornerRadius.CARD.dp, // 20dp
    blurRadius: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val glassBg = colorScheme.surface.copy(alpha = 0.85f)
    val borderColor = colorScheme.outline.copy(alpha = 0.20f)
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .clip(shape)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .then(
                if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier,
            ),
    ) {
        // capa de fondo translúcido
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    drawRect(color = glassBg)
                },
        )
        content()
    }
}
