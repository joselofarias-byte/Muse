@file:Suppress("FunctionNaming") // Compose Los nombres de los componentes comienzan con una letra mayúscula por convención (consistente con otros archivos de UI en el proyecto)

package io.zer0.muse.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.MemoryItem
import io.zer0.muse.ui.common.form.MuseActionSheetRow
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog

/**
 * El panel de acción y confirmación de eliminación después de presionar prolongadamente en la tarjeta de línea de tiempo.
 *
 * La tarjeta de la línea de tiempo anteriormente tenía solo una entrada: "Haga clic para expandir el texto completo".,La memoria no se puede borrar —— El modo lista ya existe
 * "Más → Eliminar → Segunda confirmación",La pulsación prolongada en la línea de tiempo también está ocupada por la selección múltiple,Los usuarios solo pueden volver a la vista de lista para eliminar elementos uno por uno.
 *
 * El panel en sí tiene sólo dos acciones.:Ingrese múltiples selecciones(solo hechos), borrar. La eliminación no cae directamente en la base de datos.,En su lugar, reemplácelo primero con
 * [ConfirmDeleteDialog] Confirmación secundaria —— La eliminación es irreversible,Si hace clic en el botón incorrecto, no podrá recuperarlo.
 *
 * @param target La tarjeta que presionaste durante mucho tiempo
 * @param onDelete Devolución de llamada después de que el usuario confirme en el cuadro de confirmación.(Pasar entradas que se han restaurado al formato enrutable)
 * @param onSelectMultiple Sólo tarjetas informativas disponibles;Después de la devolución de llamada, la persona que llama cierra el panel y entra en el estado de selección múltiple.
 * @param onDismiss Cancelar/Haga clic afuera para cerrar
 */
@Composable
internal fun TimelineItemActions(
    target: TimelineItem,
    onDelete: (MemoryItem) -> Unit,
    onDismiss: () -> Unit,
    onSelectMultiple: ((MemoryItem) -> Unit)? = null,
) {
    var confirming by remember(target.id) { mutableStateOf(false) }
    // El objetivo de eliminación con la misma pulsación larga permanece sin cambios.,El cuadro de confirmación y el panel comparten el mismo resultado de restauración
    val routable = remember(target) { target.toMemoryItem() }
    if (confirming) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.memory_delete_confirm_title),
            itemName = routable.title.ifBlank { routable.content },
            consequence = stringResource(R.string.memory_delete_consequence),
            onConfirm = {
                confirming = false
                onDelete(routable)
            },
            onDismiss = onDismiss,
        )
        return
    }
    MuseBottomSheet(onDismissRequest = onDismiss, bottomContentSpacing = 0.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = memoryItemNameHint(target.content),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            onSelectMultiple?.let { select ->
                MuseActionSheetRow(
                    icon = MuseIcons.circleCheck,
                    text = stringResource(R.string.memory_menu_multi_select),
                    onClick = { select(routable) },
                )
            }
            MuseActionSheetRow(
                icon = MuseIcons.trash,
                text = stringResource(R.string.memory_menu_delete),
                contentColor = MaterialTheme.colorScheme.error,
                onClick = { confirming = true },
            )
        }
    }
}

/**
 * tarjeta de línea de tiempo → Elementos que pueden consumirse mediante el envío de la acción RememberItem.
 *
 * La tarjeta solo sale del campo de visualización para el diseño.,Eliminar para volver a [TimelineItem.itemId] + [TimelineItem.scope];
 * El itemId de la entrada de resumen es la sesión. id,Entonces se elimina la que estuvo presionada durante mucho tiempo en lugar de otras conversaciones.
 * La tarjeta no muestra el título.,El nombre del cuadro de confirmación utiliza el nombre corto después de truncar la primera línea del texto.
 */
internal fun TimelineItem.toMemoryItem(): MemoryItem = MemoryItem(
    id = itemId,
    title = memoryItemNameHint(content),
    content = content,
    tags = tags,
    time = createdAt,
    source = source,
    importance = importance,
    sessionId = sessionId,
    createdAt = createdAt,
    scope = scope,
)

/** La primera línea del texto de memoria larga se trunca en un nombre legible(Título del panel/cuadro de confirmación para compartir lista). */
internal fun memoryItemNameHint(content: String): String =
    content.lineSequence().firstOrNull { it.isNotBlank() }?.take(TIMELINE_ACTION_NAME_MAX).orEmpty()

private const val TIMELINE_ACTION_NAME_MAX = 40
