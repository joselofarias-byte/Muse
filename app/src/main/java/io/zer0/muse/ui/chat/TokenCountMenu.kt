package io.zer0.muse.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.state.MuseProgressBar
import io.zer0.muse.ui.theme.MusePaddings

/**
 * v1.0.47 P5-3: Token Contar instantánea.
 *
 * Al abrir el menú Token, es [io.zer0.muse.ui.ChatViewModel] Calcular una vez,
 * Evite la codificación BPE cada vez que presione una tecla(Gastos generales de alto rendimiento).
 *
 * @param inputTokens Número de token de texto del cuadro de entrada actual
 * @param historyTokens Mensajes del historial de sesión actual(Contiene reasoning/mood/reflection/toolCalls)token numero
 * @param contextWindow Ventana de contexto del modelo actual(null Indica desconocido,El menú se ha degradado para mostrar solo subelementos)
 */
data class TokenCountSnapshot(
    val inputTokens: Int,
    val historyTokens: Int,
    val contextWindow: Int?,
    /** v2.5.3 (P4-3): Estimación aproximada del costo del historial de esta sesión(USD);null = El precio unitario se desconoce y no se mostrará. */
    val estimatedCostUsd: Double? = null,
)

/**
 * v1.0.47 P5-3: Token Panel de detalles del conteo.
 *
 * Muestra la entrada actual/mensaje del historial/ventana de contexto/barra de progreso disponible restante/ocupada.
 * uso [MuseBottomSheet] en vez de ModalBottomSheet(Malla de máquina real atascada bug).
 */
@Composable
fun TokenCountMenu(snapshot: TokenCountSnapshot, onDismissRequest: () -> Unit) {
    MuseBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_token_menu_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            TokenRow(
                label = stringResource(R.string.chat_token_input),
                value = snapshot.inputTokens,
            )
            TokenRow(
                label = stringResource(R.string.chat_token_history),
                value = snapshot.historyTokens,
            )

            // v2.5.3 (P4-3): Muestra el costo estimado aproximado del historial de esta sesión cuando el precio esté disponible.
            snapshot.estimatedCostUsd?.let { cost ->
                Text(
                    text = stringResource(R.string.chat_token_estimated_cost, String.format(java.util.Locale.US, "%.4f", cost)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val window = snapshot.contextWindow
            if (window != null && window > 0) {
                TokenRow(
                    label = stringResource(R.string.chat_token_context_window),
                    value = window,
                )
                val used = (snapshot.inputTokens + snapshot.historyTokens).coerceAtLeast(0)
                val remaining = (window - used).coerceAtLeast(0)
                TokenRow(
                    label = stringResource(R.string.chat_token_remaining),
                    value = remaining,
                )

                Spacer(modifier = Modifier.height(2.dp))
                val ratio = (used.toFloat() / window.toFloat()).coerceIn(0f, 1f)
                Text(
                    text = stringResource(R.string.chat_token_usage),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MuseProgressBar(
                    progress = ratio,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = when {
                        ratio >= 0.9f -> MaterialTheme.colorScheme.error
                        ratio >= 0.7f -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.chat_token_disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun TokenRow(label: String, value: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatNumber(value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** Miles de formatos,Números grandes fáciles de leer. */
private fun formatNumber(v: Int): String = "%,d".format(v)
