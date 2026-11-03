package io.zer0.muse.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.PendingMessage
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings

/**
 * v1.205 B2: Barra de cola de mensajes a enviar — Una vista previa uno por uno de los mensajes en cola durante la generación.
 *
 * Cada chip: haga clic en texto=Editar cuadro de entrada de relleno,Pequeño botón de envío a la izquierda=Enviar por separado,lado derecho X=Quitar;
 * Mostrar título al principio de la cola+contar,Botón borrar al final. solo en [queue] Representado por ChatScreen cuando no es nulo.
 */
@Composable
internal fun PendingQueueBar(
    queue: List<PendingMessage>,
    onSend: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MusePaddings.contentGap,
                vertical = MusePaddings.compactChipVertical,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            // LAYOUT-01: Los títulos y recuentos son etiquetas cortas de ancho fijo al principio de la línea.(El ancho restante pertenece a LazyRow. weight),
            // No se puede tomar el ancho(Competirá con el chip de mensajes por el ancho.),Limitarlo a 1 línea evitará el ajuste de línea palabra por palabra cuando se amplíe el tamaño de fuente.
            Text(
                text = stringResource(R.string.chat_pending_queue_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.chat_pending_count, queue.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                itemsIndexed(queue, key = { index, _ -> index }) { index, item ->
                    PendingQueueChip(
                        item = item,
                        onSend = { onSend(index) },
                        onEdit = { onEdit(index) },
                        onRemove = { onRemove(index) },
                    )
                }
            }
            MuseTactileButton(
                icon = MuseIcons.trash,
                onClick = onClear,
                contentDescription = stringResource(R.string.chat_pending_clear),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = MuseIconSizes.touchTarget,
                iconSize = MuseIconSizes.iconMedium,
            )
        }
    }
}

/**
 * v1.205 B2: Ponga en cola un solo chip.
 *
 * Diseño:boton enviar(Enviar por separado) | Vista previa del texto(Haga clic=Editar relleno) | Quitar botón.
 * Las entradas de imágenes puras tienen texto vacío,Copia de marcador de posición para vista previa [R.string.chat_pending_image_only].
 */
@Composable
private fun PendingQueueChip(item: PendingMessage, onSend: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = CircleShape,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = MusePaddings.tightGap),
        ) {
            MuseTactileButton(
                icon = MuseIcons.send,
                onClick = onSend,
                contentDescription = stringResource(R.string.chat_pending_send_cd),
                tint = MaterialTheme.colorScheme.primary,
                size = MuseIconSizes.touchTarget,
                iconSize = MuseIconSizes.iconSmall,
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onEdit),
            ) {
                Text(
                    text = item.text.ifBlank { stringResource(R.string.chat_pending_image_only) },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
            }
            MuseTactileButton(
                icon = MuseIcons.x,
                onClick = onRemove,
                contentDescription = stringResource(R.string.chat_pending_remove_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = MuseIconSizes.touchTarget,
                iconSize = MuseIconSizes.iconSmall,
            )
        }
    }
}
