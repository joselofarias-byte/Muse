// base de conocimientos(KB)Componentes del ciclo de vida con carpetas. — Para su reutilización por KnowledgeScreen fusionado.
// Extraído de la KnowledgeBaseManagePage original(KbRow / KbEditDialog),Y agregue nuevas operaciones de carpeta.
@file:Suppress("TooManyFunctions", "FunctionNaming", "LongParameterList")

package io.zer0.muse.ui.knowledge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.knowledge.KnowledgeBaseEntity
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.settings.SettingField
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * v2.4.6: KB conjunto de operaciones de fila — Agregar en un parámetro,Evite LongParameterList.
 */
data class KbRowActions(
    val onOpen: () -> Unit,
    val onEdit: () -> Unit,
    val onDelete: () -> Unit,
    val onReindex: () -> Unit,
    val onAddDocument: () -> Unit,
)

/**
 * KB elemento de la lista — nombre + descripción + número de documentos;lado derecho:Ingresar/agregar documento/reindexar/editar/eliminar.
 *
 * Comparado con la página de administración v1.133 KbRow:Haga clic en toda la fila = Ingrese a la base de conocimientos para explorar carpetas y documentos,
 * Los botones de acción de la derecha permanecen sin cambios.
 */
@Composable
fun KbRow(kb: KnowledgeBaseEntity, actions: KbRowActions) {
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = actions.onOpen)
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                MuseIcons.folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.size(MusePaddings.contentGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    kb.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (kb.description.isNotBlank()) {
                    Text(
                        kb.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    stringResource(R.string.kb_manage_doc_count, kb.docCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            KbActionIcon(
                icon = MuseIcons.refresh,
                contentDescription = stringResource(R.string.kb_reindex_all),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = actions.onReindex,
            )
            KbActionIcon(
                icon = MuseIcons.plus,
                contentDescription = stringResource(R.string.kb_manage_add_doc),
                tint = MaterialTheme.colorScheme.primary,
                onClick = actions.onAddDocument,
            )
            KbActionIcon(
                icon = MuseIcons.edit,
                contentDescription = stringResource(R.string.kb_manage_edit),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = actions.onEdit,
            )
            if (kb.id != "default") {
                KbActionIcon(
                    icon = MuseIcons.trash,
                    contentDescription = stringResource(R.string.kb_manage_delete),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = actions.onDelete,
                )
            }
        }
    }
}

/** KB/Botón de icono de carpeta en línea(40dp Toque objetivo + icono de 20 dp). */
@Composable
fun KbActionIcon(icon: ImageVector, contentDescription: String, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/**
 * v2.4.6: Editar opciones de formulario de ventana emergente — Campos relacionados con la visualización agregada,Evite LongParameterList.
 */
data class KbEditOptions(
    val initialDesc: String = "",
    val showDescription: Boolean = true,
    val nameLabel: String? = null,
)

/**
 * Nombre + Descripción ventana emergente de edición de dos campos(KB Nuevo/Cambiar nombre, crear nueva carpeta/Cambiar nombre común).
 */
@Composable
fun KbEditDialog(
    title: String,
    initialName: String,
    onConfirm: (name: String, desc: String) -> Unit,
    onDismiss: () -> Unit,
    options: KbEditOptions = KbEditOptions(),
) {
    var name by remember { mutableStateOf(initialName) }
    var desc by remember { mutableStateOf(options.initialDesc) }
    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
                SettingField(
                    label = options.nameLabel ?: stringResource(R.string.kb_manage_name_label),
                    value = name,
                    onValueChange = { name = it },
                )
                if (options.showDescription) {
                    SettingField(
                        label = stringResource(R.string.kb_manage_desc_label),
                        value = desc,
                        onValueChange = { desc = it },
                    )
                }
            }
        },
        confirmText = stringResource(R.string.action_save),
        onConfirm = { onConfirm(name.trim(), desc.trim()) },
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
    )
}
