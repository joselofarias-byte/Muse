package io.zer0.muse.ui.common.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseCornerRadius
import io.zer0.muse.ui.theme.MusePaddings

/**
 * AI Burbuja de sugerencia después de la respuesta.
 *
 * Muestra hasta 3 respuestas sugeridas debajo del mensaje de respuesta de IA,Los usuarios pueden enviar rápidamente con un solo clic.
 * uso de fondo `primaryContainer@42% alpha` (light) / `white@8% alpha` (dark),
 * esquinas redondeadas 16dp, relleno 12×8dp.
 *
 * Uso:
 * ```
 * SuggestionBubbles(
 *     suggestions = listOf("continuar", "Explicación detallada", "cambiar el camino"),
 *     onSuggestionClick = { text -> onSend(text) },
 * )
 * ```
 *
 * @param suggestions Lista de textos sugeridos (Toma como máximo los 3 primeros.)
 * @param onSuggestionClick Haga clic en devolución de llamada de sugerencia
 * @param modifier modificador
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestionBubbles(suggestions: List<String>, onSuggestionClick: (String) -> Unit, modifier: Modifier = Modifier) {
    if (suggestions.isEmpty()) return

    val colorScheme = MaterialTheme.colorScheme
    val isLight = colorScheme.surface.luminance() > 0.5f
    val bubbleColor = if (isLight) {
        colorScheme.primaryContainer.copy(alpha = 0.42f)
    } else {
        colorScheme.onSurface.copy(alpha = 0.08f)
    }
    val textColor = colorScheme.onSurface.copy(alpha = 0.85f)
    val shape = RoundedCornerShape(MuseCornerRadius.SEMI_LARGE.dp) // 16dp

    FlowRow(
        modifier = modifier.padding(top = MusePaddings.contentGap),
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.labelVerticalGap),
        maxItemsInEachRow = 3,
    ) {
        suggestions.take(3).forEach { suggestion ->
            Surface(
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onSuggestionClick(suggestion) },
                ),
                shape = shape,
                color = bubbleColor,
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.labelMedium,
                    color = textColor,
                    modifier = Modifier.padding(MusePaddings.bubbleInner),
                    maxLines = 1,
                )
            }
        }
    }
}
