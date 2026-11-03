@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "TooManyFunctions")

package io.zer0.muse.ui.quicknotes

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.quicknote.QuickNoteEntity
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.clearMuseWindowDim
import io.zer0.muse.ui.markdown.MarkdownText
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Cápsula de metainformación de la tarjeta(Marca de tiempo de carpeta/recordatorio). */
@Composable
internal fun NoteMetaChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        shape = MuseShapes.pill,
        color = tint.copy(alpha = 0.10f),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MusePaddings.iconPadding,
                vertical = MusePaddings.labelVerticalGap,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(MuseIconSizes.iconTiny),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Mostrar el tiempo relativo según el dibujo de diseño.:Hoy HH:mm / Ayer / Hace N días / MM-dd.
 *
 * Correcciones en la interfaz (Rendimiento-3): Ya no cada tarjeta inicia su propio bucle infinito de los años 60 produce la rutina estatal,
 * now Pasado por ticker compartido a nivel de página(QuickNotesScreen Transmisión de rutina única),
 * La tarjeta solo vuelve a calcular el texto de la hora cuando se actualiza el teletipo.
 */
@Composable
internal fun formatNoteTime(timestamp: Long, now: Long): String {
    val fmtTime = remember { SimpleDateFormat(MuseDateFormats.TIME_SHORT, Locale.getDefault()) }
    val fmtDate = remember { SimpleDateFormat(MuseDateFormats.DATE_SHORT, Locale.getDefault()) }
    if (timestamp <= 0) return ""

    val nowCal = Calendar.getInstance().apply { timeInMillis = now }
    val noteCal = Calendar.getInstance().apply { timeInMillis = timestamp }
    val daysDiff = ((now - timestamp) / 86_400_000).toInt()

    return when {
        nowCal.get(Calendar.YEAR) == noteCal.get(Calendar.YEAR) &&
            nowCal.get(Calendar.DAY_OF_YEAR) == noteCal.get(Calendar.DAY_OF_YEAR) -> {
            "${stringResource(R.string.memory_filter_today)} ${fmtTime.format(Date(timestamp))}"
        }
        daysDiff == 1 -> stringResource(R.string.memory_created_yesterday)
        daysDiff in 2..6 -> stringResource(R.string.memory_created_days_ago, daysDiff)
        else -> fmtDate.format(Date(timestamp))
    }
}

@Composable
internal fun formatReminderAt(timestamp: Long): String {
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    return fmt.format(Date(timestamp))
}

@Composable
internal fun QuickNoteActionMenu(
    note: QuickNoteEntity,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onSetFolder: () -> Unit = {},
    onSetReminder: () -> Unit = {},
    onToggleEncrypt: () -> Unit = {},
    /**
     * v2.6.7: Agregue archivos adjuntos de imágenes.
     *
     * La tarjeta de notas ya mostrará "N "Adjunto" (léase acceso lateral), pero no hay ningún lugar en todo el almacén donde se pueda escribir.
     * Ese indicador nunca enciende —— addAttachment No ha habido ninguna llamada.
     */
    onAttachImage: (() -> Unit)? = null,
    /** v2.6.7: La lista de archivos adjuntos actuales (pasada por el análisis de la persona que llama, este componente no toca DAO). */
    attachments: List<String> = emptyList(),
    /**
     * v2.6.7: Retire los archivos adjuntos.
     *
     * Sin esta entrada, los archivos adjuntos sólo se pueden sumar pero no restar; y removeAttachment se convertirá en
     * Otro método muerto para "implementar llamadas completas pero cero" —— Esto es lo que he estado cultivando en las últimas rondas.
     */
    onRemoveAttachment: ((String) -> Unit)? = null,
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = note.title.takeIf { it.isNotBlank() },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                QuickNoteActionRow(
                    icon = MuseIcons.edit,
                    label = stringResource(R.string.quick_notes_edit),
                    onClick = onEdit,
                )
                QuickNoteActionRow(
                    icon = MuseIcons.copy,
                    label = stringResource(R.string.quick_notes_copy),
                    onClick = onCopy,
                )
                // v2.6.7: Entrada de archivo adjunto (clasificada en edición/Después de copiar, antes de operaciones destructivas)
                if (onAttachImage != null) {
                    QuickNoteActionRow(
                        icon = MuseIcons.paperclip,
                        label = stringResource(R.string.quick_notes_attach_image),
                        onClick = onAttachImage,
                    )
                }
                // v2.6.7: Los archivos adjuntos se han eliminado uno por uno.
                if (onRemoveAttachment != null) {
                    attachments.forEachIndexed { index, path ->
                        QuickNoteActionRow(
                            icon = MuseIcons.x,
                            label = stringResource(R.string.quick_notes_remove_image, index + 1),
                            tint = MaterialTheme.colorScheme.error,
                            onClick = { onRemoveAttachment(path) },
                        )
                    }
                }
                // Establecer carpeta
                QuickNoteActionRow(
                    icon = MuseIcons.folderOpen,
                    label = stringResource(R.string.quick_notes_folder_set),
                    onClick = onSetFolder,
                )
                // Establecer recordatorio
                QuickNoteActionRow(
                    icon = MuseIcons.bell,
                    label = if (note.reminderAt > 0) {
                        stringResource(R.string.quick_notes_reminder_cancel)
                    } else {
                        stringResource(R.string.quick_notes_reminder_set)
                    },
                    onClick = onSetReminder,
                )
                // Cifrado/Descifrar
                QuickNoteActionRow(
                    icon = MuseIcons.lock,
                    label = if (note.encrypted) {
                        stringResource(R.string.quick_notes_encrypt_off)
                    } else {
                        stringResource(R.string.quick_notes_encrypt_on)
                    },
                    onClick = onToggleEncrypt,
                )
                QuickNoteActionRow(
                    icon = MuseIcons.trash,
                    label = stringResource(R.string.quick_notes_delete),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
        },
        dismissText = stringResource(R.string.quick_notes_cancel),
        onDismiss = onDismiss,
    )
}

@Composable
private fun QuickNoteActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        onClick = onClick,
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MusePaddings.iconPadding,
                    vertical = MusePaddings.inputPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = tint,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun QuickNoteDialog(existing: QuickNoteEntity?, onDismiss: () -> Unit, onSave: (String, String, List<String>, String) -> Unit) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var content by remember { mutableStateOf(existing?.content ?: "") }
    var tags by remember { mutableStateOf(existing?.tags?.joinToString(",") ?: "") }
    var isMarkdown by remember { mutableStateOf(existing?.contentType == "markdown") }
    var previewMode by remember { mutableStateOf(false) }
    val errorRequired = stringResource(R.string.quick_notes_error_required)
    var errorMessage by remember { mutableStateOf<String?>(null) }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(
            if (existing == null) R.string.quick_notes_new else R.string.quick_notes_edit_title,
        ),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
                MuseTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(R.string.quick_notes_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Markdown Cambio de modo + interruptor de vista previa
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        onClick = { isMarkdown = !isMarkdown },
                        shape = MuseShapes.pill,
                        color = if (isMarkdown) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.quick_notes_toggle_markdown),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isMarkdown) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(
                                horizontal = MusePaddings.iconPadding,
                                vertical = MusePaddings.labelVerticalGap,
                            ),
                        )
                    }
                    if (isMarkdown) {
                        Surface(
                            onClick = { previewMode = !previewMode },
                            shape = MuseShapes.pill,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                        ) {
                            Text(
                                text = stringResource(
                                    if (previewMode) {
                                        R.string.quick_notes_markdown_edit
                                    } else {
                                        R.string.quick_notes_markdown_preview
                                    },
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(
                                    horizontal = MusePaddings.iconPadding,
                                    vertical = MusePaddings.labelVerticalGap,
                                ),
                            )
                        }
                    }
                }
                // Markdown El modo de vista previa se representa con MarkdownText
                if (isMarkdown && previewMode) {
                    Surface(
                        shape = MuseShapes.large,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                    ) {
                        Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                            MarkdownText(
                                text = content,
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                } else {
                    MuseTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text(stringResource(R.string.quick_notes_content_label)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                    )
                }
                MuseTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text(stringResource(R.string.quick_notes_tags_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                errorMessage?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmText = stringResource(R.string.quick_notes_save),
        onConfirm = {
            if (title.isBlank()) {
                errorMessage = errorRequired
                return@MuseDialog
            }
            onSave(
                title.trim(),
                content.trim(),
                tags.split(",").map { it.trim() }.filter { it.isNotBlank() },
                if (isMarkdown) "markdown" else "plain",
            )
        },
        dismissText = stringResource(R.string.quick_notes_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * Panel de papelera de reciclaje — Listar registros eliminados,Admite recuperación/eliminación permanente/borrar todo.
 */
@Composable
internal fun QuickNoteTrashDialog(
    items: List<QuickNoteEntity>,
    onDismiss: () -> Unit,
    onRestore: (QuickNoteEntity) -> Unit,
    onPermanentDelete: (QuickNoteEntity) -> Unit,
    onClearAll: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, Locale.getDefault()) }
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.quick_notes_trash_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Text(
                    text = stringResource(R.string.quick_notes_trash_count, items.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (items.isEmpty()) {
                    Text(
                        text = stringResource(R.string.quick_notes_trash_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = MusePaddings.contentGap),
                    )
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                    ) {
                        items(items, key = { it.id }) { note ->
                            Surface(
                                shape = MuseShapes.semiLarge,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(MusePaddings.cardInner),
                                ) {
                                    Text(
                                        text = note.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (note.content.isNotBlank()) {
                                        Spacer(Modifier.height(MusePaddings.tightGap))
                                        Text(
                                            text = note.content,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(Modifier.height(MusePaddings.auxGap))
                                    Text(
                                        text = fmt.format(Date(note.deletedAt)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                    Spacer(Modifier.height(MusePaddings.contentGap))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                                    ) {
                                        Surface(
                                            onClick = { onRestore(note) },
                                            shape = MuseShapes.pill,
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(
                                                    horizontal = MusePaddings.iconPadding,
                                                    vertical = MusePaddings.labelVerticalGap,
                                                ),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    imageVector = MuseIcons.edit,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                                )
                                                Spacer(Modifier.size(MusePaddings.tightGap))
                                                Text(
                                                    text = stringResource(R.string.quick_notes_restore),
                                                    style = MaterialTheme.typography.labelMedium,
                                                )
                                            }
                                        }
                                        Surface(
                                            onClick = { onPermanentDelete(note) },
                                            shape = MuseShapes.pill,
                                            color = MaterialTheme.colorScheme.errorContainer,
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(
                                                    horizontal = MusePaddings.iconPadding,
                                                    vertical = MusePaddings.labelVerticalGap,
                                                ),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    imageVector = MuseIcons.trash,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                                Spacer(Modifier.size(MusePaddings.tightGap))
                                                Text(
                                                    text = stringResource(R.string.quick_notes_delete_permanent),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Surface(
                        onClick = onClearAll,
                        shape = MuseShapes.semiLarge,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MusePaddings.inputPadding),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = MuseIcons.trash,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(MuseIconSizes.iconSmall),
                            )
                            Spacer(Modifier.size(MusePaddings.tightGap))
                            Text(
                                text = stringResource(R.string.quick_notes_clear_trash),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        dismissText = stringResource(R.string.quick_notes_cancel),
        onDismiss = onDismiss,
    )
}

/** Extraer #etiquetas del texto,Devuelve una lista de etiquetas sin #. */
internal fun extractHashTags(text: String): List<String> {
    val regex = Regex("#([^#\\s,,..!!??\\n]+)")
    return regex.findAll(text)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
}

/**
 * Derivar títulos a partir del contenido.
 * Tome primero la primera línea de texto que no esté vacía;Si la primera línea es demasiado corta o solo contiene etiquetas,Luego toma los primeros 40 caracteres.
 */
internal fun deriveTitle(text: String, tags: List<String>): String {
    val stripped = text.replace(Regex("#[^#\\s,,..!!??\\n]+"), "").trim()
    val firstLine = stripped.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""
    return when {
        firstLine.length >= 3 -> firstLine.take(40)
        stripped.isNotBlank() -> stripped.take(40)
        tags.isNotEmpty() -> tags.first().take(40)
        else -> text.trim().take(40)
    }
}

// ── Grabe rápidamente los componentes de mejora ───────────────────────────────────────────────────────

/**
 * Barra de filtro de carpetas — Cápsula de desplazamiento horizontal delimitada,Evite que los elementos del filtro saquen el historial del área visible.
 * - "Todos"(selectedFolder=null): Mostrar todas las carpetas
 * - "Sin categoría"(selectedFolder=""): folder Registros vacíos
 * - Nombre de carpeta específica: Registros en esta carpeta
 */
@Composable
internal fun QuickNoteFolderFilterRow(
    folders: List<String>,
    selectedFolder: String?,
    onFolderSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp, max = 56.dp),
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        item(key = "all") {
            FolderChip(
                label = stringResource(R.string.quick_notes_folder_all),
                selected = selectedFolder == null,
                onClick = { onFolderSelected(null) },
            )
        }
        item(key = "uncategorized") {
            FolderChip(
                label = stringResource(R.string.quick_notes_folder_uncategorized),
                selected = selectedFolder == "",
                onClick = { onFolderSelected("") },
            )
        }
        items(folders, key = { it }) { folder ->
            FolderChip(
                label = folder,
                selected = selectedFolder == folder,
                onClick = { onFolderSelected(folder) },
            )
        }
    }
}

@Composable
private fun FolderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    MuseChip(
        selected = selected,
        onClick = onClick,
        label = label,
        leadingIcon = {
            Icon(
                imageVector = MuseIcons.folder,
                contentDescription = null,
                modifier = Modifier.size(MuseIconSizes.iconTiny),
            )
        },
    )
}

/**
 * Exportar/Menú de importación — Proporciona tres entradas: exportación Markdown/exportación JSON/importación JSON.
 */
@Composable
internal fun QuickNoteExportMenu(onDismiss: () -> Unit, onExportMarkdown: () -> Unit, onExportJson: () -> Unit, onImportJson: () -> Unit) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.quick_notes_export),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                QuickNoteActionRow(
                    icon = MuseIcons.share,
                    label = stringResource(R.string.quick_notes_export_markdown),
                    onClick = onExportMarkdown,
                )
                QuickNoteActionRow(
                    icon = MuseIcons.share,
                    label = stringResource(R.string.quick_notes_export_json),
                    onClick = onExportJson,
                )
                QuickNoteActionRow(
                    icon = MuseIcons.folderOpen,
                    label = stringResource(R.string.quick_notes_import_json),
                    onClick = onImportJson,
                )
            }
        },
        dismissText = stringResource(R.string.quick_notes_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * Ventana emergente de configuración de carpeta — Listar carpetas existentes + Sin categoría + Cuadro de entrada Nueva carpeta.
 */
@Composable
internal fun QuickNoteFolderDialog(folders: List<String>, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var newFolder by remember { mutableStateOf("") }
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.quick_notes_folder_set),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                QuickNoteActionRow(
                    icon = MuseIcons.folderOpen,
                    label = stringResource(R.string.quick_notes_folder_uncategorized),
                    onClick = { onSelect("") },
                )
                folders.forEach { folder ->
                    QuickNoteActionRow(
                        icon = MuseIcons.folder,
                        label = folder,
                        onClick = { onSelect(folder) },
                    )
                }
                Spacer(Modifier.height(MusePaddings.contentGap))
                Text(
                    text = stringResource(R.string.quick_notes_folder_new),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    MuseTextField(
                        value = newFolder,
                        onValueChange = { newFolder = it },
                        label = { Text(stringResource(R.string.quick_notes_folder_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        onClick = {
                            val name = newFolder.trim()
                            if (name.isNotBlank()) onSelect(name)
                        },
                        shape = MuseShapes.pill,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(
                            text = stringResource(R.string.quick_notes_save),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(
                                horizontal = MusePaddings.iconPadding,
                                vertical = MusePaddings.inputPadding,
                            ),
                        )
                    }
                }
            }
        },
        dismissText = stringResource(R.string.quick_notes_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * Ventana emergente de configuración de recordatorio — Mostrar el estado actual del recordatorio,Haga clic en Configuración para que aparezca la fecha./Selector de tiempo.
 */
@Composable
internal fun QuickNoteReminderDialog(currentReminderAt: Long, onDismiss: () -> Unit, onSet: (Long) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val fmt = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, Locale.getDefault()) }
    val canExact = remember { io.zer0.muse.tools.reminder.ReminderAlarmScheduler.canScheduleExact(context) }

    fun showDatePicker() {
        val now = Calendar.getInstance()
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val cal = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                }
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        cal.set(Calendar.HOUR_OF_DAY, hour)
                        cal.set(Calendar.MINUTE, minute)
                        cal.set(Calendar.SECOND, 0)
                        cal.set(Calendar.MILLISECOND, 0)
                        onSet(cal.timeInMillis)
                    },
                    now.get(Calendar.HOUR_OF_DAY),
                    now.get(Calendar.MINUTE),
                    true,
                ).showWithoutMuseDim()
            },
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH),
        ).showWithoutMuseDim()
    }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.quick_notes_reminder),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Text(
                    text = if (currentReminderAt > 0) {
                        stringResource(R.string.quick_notes_reminder_scheduled, fmt.format(Date(currentReminderAt)))
                    } else {
                        stringResource(R.string.quick_notes_reminder_none)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!canExact) {
                    // Sin el permiso "Despertador y recordatorio", el despertador sólo se puede programar de forma imprecisa (quizás unos minutos más tarde).
                    // Esto proporciona orientación práctica en lugar de permitir que los usuarios piensen que el recordatorio sonará a tiempo.
                    Text(
                        text = stringResource(R.string.reminder_exact_permission_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    QuickNoteActionRow(
                        icon = MuseIcons.bell,
                        label = stringResource(R.string.reminder_exact_permission_action),
                        onClick = { io.zer0.muse.tools.reminder.ReminderAlarmScheduler.requestExactAlarmPermission(context) },
                    )
                }
                QuickNoteActionRow(
                    icon = MuseIcons.bell,
                    label = stringResource(R.string.quick_notes_reminder_set),
                    onClick = { showDatePicker() },
                )
                if (currentReminderAt > 0) {
                    QuickNoteActionRow(
                        icon = MuseIcons.x,
                        label = stringResource(R.string.quick_notes_reminder_cancel),
                        tint = MaterialTheme.colorScheme.error,
                        onClick = onCancel,
                    )
                }
            }
        },
        dismissText = stringResource(R.string.quick_notes_cancel),
        onDismiss = onDismiss,
    )
}

/** Native date/time dialogs should follow Muse's no-dim popup treatment. */
private fun android.app.Dialog.showWithoutMuseDim() {
    setOnShowListener { clearMuseWindowDim(window) }
    show()
    clearMuseWindowDim(window)
}
