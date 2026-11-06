package io.zer0.muse.ui.common.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.theme.MusePaddings

/**
 * Eliminar cuadro de diálogo de confirmación(Eliminar confirmación)— Todo el sitio**solo**Eliminar componente de confirmación(MEM-04).
 *
 * estructura de oración unificada(Sistema de tres líneas):
 *  1. Título:acción(Como por ejemplo"Eliminar Lorebook")
 *  2. `Confirmar para eliminar "{nombre del objeto}"?` — **Objetos con nombre**
 *  3. Declaración de consecuencias [consequence] — Descripción"¿Qué pasará si lo borro?";Degenera a genérico cuando no se proporciona"Esta acción no se puede deshacer."
 * clave primaria fija destructive(error rojo),El botón secundario es Cancelar.
 *
 * MEM-04 Explicación unificada:Los tres conjuntos de textos anteriores coexistieron —— este componente([R.string.common_confirm_delete_message]),
 * `MemoryScreen` de `memory_delete_confirm_message`,`MomentCard` de `moment_delete_confirm`;
 * Ahora, los dos últimos lugares también utilizan este componente y proporcionan una copia de nomenclatura y una explicación de las consecuencias, respectivamente.,No más escribir un conjunto de patrones de oraciones cada uno.
 *
 * Uso:
 * ```kotlin
 * ConfirmDeleteDialog(
 *     title = stringResource(R.string.assistant_delete_title),
 *     itemName = assistant.name,
 *     consequence = stringResource(R.string.assistant_delete_consequence),
 *     onConfirm = { repo.delete(assistant.id) },
 *     onDismiss = { target = null },
 * )
 * ```
 *
 * @param title Título del diálogo(Como por ejemplo"Eliminar Lorebook")
 * @param itemName Nombre del elemento eliminado(**debe ser nombrado**,Mostrado en"Confirmar para eliminar "X"?")
 * @param onConfirm Confirmar eliminación de devolución de llamada(Eliminar lógica incluida + cerrar diálogo)
 * @param onDismiss Cancelar devolución de llamada(Cerrar diálogo)
 * @param consequence Declaración de consecuencias(Requerido después de nombrar el objeto.,Como por ejemplo"También se eliminarán sus conversaciones y recuerdos.");Utilice texto genérico cuando esté vacío
 */
@Composable
fun ConfirmDeleteDialog(title: String, itemName: String, onConfirm: () -> Unit, onDismiss: () -> Unit, consequence: String? = null) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.common_confirm_delete_message, itemName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(MusePaddings.tightGap))
                Text(
                    text = consequence
                        ?: stringResource(R.string.common_confirm_delete_consequence_default),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmText = stringResource(R.string.common_delete),
        onConfirm = onConfirm,
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
        destructive = true,
    )
}
