package io.zer0.muse.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.export.ExportFormat
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseCapsuleTab

/**
 * Cuadro de diálogo de selección de formato de exportación(Markdown / HTML / PDF Elige uno de tres).
 *
 * Puntos de diseño:
 *  - selector de segmento([MuseCapsuleTab])Hacer cambio de formato
 *  - Después de seleccionar, haga clic en la parte inferior"Exportar"Devolución de llamada del disparador de botón [onFormatSelected]
 *  - Una breve descripción aparece debajo de cada formato.,Ayude a los usuarios a comprender la diferencia
 *
 * Uso:
 * ```
 * if (showDialog) {
 *     ExportFormatPickerDialog(
 *         onDismiss = { showDialog = false },
 *         onFormatSelected = { format ->
 *             showDialog = false
 *             // Llame al método de exportación correspondiente.
 *         },
 *     )
 * }
 * ```
 *
 * @param onDismiss Cerrar devolución de llamada de diálogo(Cancelar/clic externo)
 * @param onFormatSelected Devolución de llamada después de que el usuario confirme el formato de exportación.
 */
@Composable
fun ExportFormatPickerDialog(onDismiss: () -> Unit, onFormatSelected: (ExportFormat) -> Unit) {
    // Índice de formato seleccionado actualmente,Predeterminado Markdown
    var selectedIndex by remember { mutableStateOf(0) }
    val formats = listOf(ExportFormat.MARKDOWN, ExportFormat.HTML, ExportFormat.PDF)
    val labels = listOf("Markdown", "HTML", "PDF")

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.chat_export_format_title),
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // selector de segmento
                MuseCapsuleTab(
                    tabs = labels,
                    selectedIndex = selectedIndex,
                    onSelect = { selectedIndex = it },
                )
                // Una breve descripción del formato actual.
                Text(
                    text = formatDescription(formats[selectedIndex]),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmText = stringResource(R.string.chat_export_format_confirm),
        onConfirm = {
            onFormatSelected(formats[selectedIndex])
        },
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
    )
}

/** Devuelve una breve descripción en chino de cada formato de exportación. */
@Composable
private fun formatDescription(format: ExportFormat): String = when (format) {
    ExportFormat.MARKDOWN -> stringResource(R.string.chat_export_format_markdown)
    ExportFormat.HTML -> stringResource(R.string.chat_export_format_html)
    ExportFormat.PDF -> stringResource(R.string.chat_export_format_pdf)
}
