package io.zer0.muse.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.theme.MuseDateFormats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A5: Capa emergente de información del mensaje — Haga clic para ver el modelo/tiempo/Consume mucho tiempo/Token Dosificación.
 *
 * fuente de datos(Todo de [UIMessage],Rellenado cuando lo genera ChatViewModel,ver finalizeResponse):
 *  - [UIMessage.modelId] / [UIMessage.createdAt]:Información básica
 *  - [UIMessage.durationMs]:Tiempo total necesario para generar(Con ciclo de herramienta;Los caminos interrumpidos también se rellenan)
 *  - [UIMessage.promptTokens] / [UIMessage.completionTokens] / [UIMessage.reasoningTokens] /
 *    [UIMessage.cachedTokens] / [UIMessage.cacheWriteTokens]:provider Valor real(Uso de streamingDelta último valor)
 *
 * El caché se muestra en dos líneas.:[UIMessage.cachedTokens](leer)con [UIMessage.cacheWriteTokens](escribir)El precio unitario es diferente,
 * Sintetizar un número de "caché" hace que el usuario piense que la parte de escritura es barata. Si la cantidad escrita es 0 o no hay una medición real, no se ocupará ninguna fila.
 *
 * Token estado de visualización:
 *  - Hay medidas reales(Cualquier subelemento no es null)→ Mostrar todos los artículos + etiqueta "Medida real"
 *  - No hay prueba real, pero son noticias del asistente. → Ocupación estimada local(Aproximadamente 2 personajes/token,Misma heurística que StatsPage)+ Pestaña "Estimación".
 *    razones comerciales:provider Cuando no se devuelve el uso(datos antiguos/Puerta de enlace no compatible/interrumpir el flujo)Todavía brinda a los usuarios una referencia de orden de magnitud.,
 *    Utilice la etiqueta "estimación" para indicar valores no oficiales,No pretendas ser un probador.
 *  - Mensajes de usuario → "No hay datos generados"
 *
 * entrada:MessageBubble Mantenga presionado el elemento "Información del mensaje" en el menú extendido/menú contextual del escritorio.
 */
@Composable
fun MessageInfoSheet(msg: UIMessage, onDismiss: () -> Unit) {
    val modelLabel = stringResource(R.string.msg_info_model)
    val timeLabel = stringResource(R.string.msg_info_time)
    val durationLabel = stringResource(R.string.msg_info_duration)
    val tokensTitle = stringResource(R.string.msg_info_tokens_title)
    val promptLabel = stringResource(R.string.msg_info_prompt)
    val completionLabel = stringResource(R.string.msg_info_completion)
    val reasoningLabel = stringResource(R.string.msg_info_reasoning)
    val cachedLabel = stringResource(R.string.msg_info_cached)
    val cacheWriteLabel = stringResource(R.string.msg_info_cache_write)
    val totalLabel = stringResource(R.string.msg_info_total)
    val measuredTag = stringResource(R.string.msg_info_measured)
    val estimatedTag = stringResource(R.string.msg_info_estimated)
    val unknown = stringResource(R.string.msg_info_unknown)
    val noData = stringResource(R.string.msg_info_no_data)

    val timeText = SimpleDateFormat(
        MuseDateFormats.DATE_TIME_SHORT,
        Locale.getDefault(),
    ).format(Date(msg.createdAt))

    val hasMeasured = msg.promptTokens != null || msg.completionTokens != null

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.msg_info_title),
        confirmText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
        dismissText = null,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                InfoRow(modelLabel, msg.modelId?.takeIf { it.isNotBlank() } ?: unknown)
                InfoRow(timeLabel, timeText)
                // Consume mucho tiempo:Mensajes de usuario/No mostrar la fila cuando los datos antiguos son nulos
                msg.durationMs?.let { InfoRow(durationLabel, formatDuration(it)) }

                Text(
                    text = tokensTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (hasMeasured) {
                    InfoRow(promptLabel, "${msg.promptTokens ?: 0}", measuredTag)
                    InfoRow(completionLabel, "${msg.completionTokens ?: 0}", measuredTag)
                    InfoRow(reasoningLabel, "${msg.reasoningTokens ?: 0}", measuredTag)
                    InfoRow(cachedLabel, "${msg.cachedTokens ?: 0}", measuredTag)
                    // Escritura en caché en una sola columna: el precio unitario de escritura es mayor que el precio de entrada y no se puede combinar con el "acierto en caché" de la fila anterior.
                    (msg.cacheWriteTokens ?: 0).takeIf { it > 0 }?.let { writeTokens ->
                        InfoRow(cacheWriteLabel, "$writeTokens", measuredTag)
                    }
                    InfoRow(
                        totalLabel,
                        "${(msg.promptTokens ?: 0) + (msg.completionTokens ?: 0)}",
                        measuredTag,
                    )
                } else if (msg.role == MessageRole.ASSISTANT && msg.content.isNotBlank()) {
                    // Espacio estimado ocupado:provider Si no se devuelve el uso, se estimará aproximadamente en función de la longitud del texto.(Aproximadamente 2 personajes/token)
                    val estimated = (msg.content.length / 2).coerceAtLeast(1)
                    InfoRow(totalLabel, "$estimated", estimatedTag)
                } else {
                    InfoRow(noData, "")
                }
            }
        },
    )
}

/** etiqueta-fila de valor;si [tag](Medición real/estimación)Al agregar una etiqueta de fuente pequeña después del valor. */
@Composable
private fun InfoRow(label: String, value: String, tag: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // LAYOUT-01: El ancho restante de la etiqueta no es suficiente. —— Cuando el ancho restante es extremadamente pequeño, los caracteres chinos aún se ajustarán palabra por palabra para hacer que toda la línea sea más alta.,Por tanto, todos están restringidos.
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            // LAYOUT-01: Las columnas de valores están limitadas a una sola fila.,Evite que los valores largos comprima las etiquetas en una columna.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (tag != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = tag,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** Generar formato que requiere mucho tiempo: <1s usar ms;<60s Usa segundos(1 decimales);De lo contrario dividir+artículos de segunda clase. */
private fun formatDuration(ms: Long): String = when {
    ms < 1_000L -> "$ms ms"
    ms < 60_000L -> String.format(Locale.US, "%.1f s", ms / 1000f)
    else -> "${ms / 60_000L} min ${(ms % 60_000L) / 1000L} s"
}
