package io.zer0.muse.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.UIMessage
import io.zer0.muse.R
import io.zer0.muse.ui.common.state.MuseProgressBar
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.util.TokenEstimator

/**
 * P1 UI: Barra de estadísticas de Compact Token debajo del botón de acceso directo al mensaje del Asistente.
 *
 * v2.0: de"Estimación de un solo mensaje"En su lugar, diferenciar la entrada/salida:
 *  - entrada:Priorizar la verdad reportada por el proveedor promptTokens;Devolver estimación de contexto cuando no esté disponible(prefijo ~ representa una estimación)
 *  - salida:Dar prioridad a la verdad completionTokens;Cuando no esté disponible, utilice la estimación de BPE del texto de este mensaje
 * Diseño de una sola fila:entrada · salida · Porcentaje de contexto ocupado + barra de progreso fina.
 */
@Composable
fun TokenStatsBar(
    message: UIMessage,
    historyTokens: Int,
    contextWindow: Int,
    promptTokens: Int? = null,
    completionTokens: Int? = null,
    /**
     * Si este mensaje todavía se está transmitiendo.
     *
     * No se realiza ninguna estimación local durante la transmisión: el último mensaje de ayuda se reorganizará en cada fragmento y el BPE aumenta linealmente según la longitud del texto.
     * (5KB Texto ≈ 1–2ms), todos caen en el hilo principal —— No es un precio que valga la pena pagar por una cifra que sigue cambiando.
     * Cuanto más largos sean el texto y el código fuente de la tarjeta, más obvio será.
     */
    streaming: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val estimatedOutput = remember(message, streaming) {
        if (streaming) 0 else (TokenEstimator.estimate(listOf(message)) - 4).coerceAtLeast(0)
    }
    val outputTokens = completionTokens ?: estimatedOutput
    // contextTokenCount Lista completa de mensajes presionada al cerrar(Contiene la respuesta de este asistente.)Revalorización;
    // Las estimaciones de producción se deducen cuando no hay uso del proveedor.,Obtener una aproximación del contexto de entrada,Evite contar dos veces las salidas y las entradas.
    val inputTokens = promptTokens ?: (historyTokens - estimatedOutput).coerceAtLeast(0)
    val inputEstimated = promptTokens == null
    val outputEstimated = completionTokens == null

    // Agregar dos elementos medidos;El estado estimado se cuenta directamente utilizando el contexto completo.(que ya contiene la salida),Evite la doble suma.
    val used = if (promptTokens != null && completionTokens != null) {
        (inputTokens + outputTokens).coerceAtLeast(0)
    } else {
        historyTokens.coerceAtLeast(0)
    }
    val ratio = if (contextWindow > 0) {
        (used.toFloat() / contextWindow).coerceIn(0f, 1f)
    } else {
        0f
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = buildString {
                append(stringResource(R.string.chat_token_prompt))
                append(" ")
                if (inputEstimated) append("~")
                append(formatTokenCount(inputTokens))
                append(" · ")
                append(stringResource(R.string.chat_token_output))
                append(" ")
                if (streaming && completionTokens == null) {
                    // Aún generando: este número cambia en cada cuadro, lo que indica directamente"Por determinar"Más honesto que dar un número falso.
                    append("…")
                } else {
                    if (outputEstimated) append("~")
                    append(formatTokenCount(outputTokens))
                }
                if (contextWindow > 0) {
                    append(" · ")
                    append(stringResource(R.string.chat_token_usage))
                    append(" ")
                    append((ratio * 100).toInt())
                    append("%")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (contextWindow > 0) {
            MuseProgressBar(
                progress = ratio,
                modifier = Modifier
                    .width(56.dp)
                    .height(2.dp)
                    .clip(MuseShapes.medium),
                color = when {
                    ratio >= 0.9f -> MaterialTheme.colorScheme.error
                    ratio >= 0.7f -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

/** Número de token compacto:1 Utilizado por más de 10.000 12.3k,Evite números largos que ocupen espacio en una sola línea. */
internal fun formatTokenCount(value: Int): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 10_000 -> "%.1fk".format(value / 1000.0)
    else -> value.toString()
}
