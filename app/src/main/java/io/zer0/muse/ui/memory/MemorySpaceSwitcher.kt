package io.zer0.muse.ui.memory

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.memory.space.MemorySpaceEntity
import io.zer0.memory.space.MemorySpaceWithCount
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseActionSheetRow
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseFormDialog
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.museAnimateItem
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge
import org.koin.androidx.compose.koinViewModel

/**
 * v1.0.52 P2-2: Conmutador de espacio de memoria(Desplácese horizontalmente por la lista de MuseChip).
 *
 * Mostrar todo Space,El usuario hace clic para cambiar el espacio actual.
 * Después de cambiar, MemoryViewModel.selectSpace persistirá en SettingsRepository,
 * Y active loadAll para volver a filtrar la lista de hechos por spaceId.
 *
 * con [io.zer0.muse.ui.MemoryScreen.ScopeFilterChipRow] la diferencia:
 *  - Space conmutador:Aislar por escena(trabajo/vida/aprender),Ortogonal al alcance
 *  - Scope filtrar:Cuarentena por agente(asistente principal/sub-asistente)
 */
@Composable
fun SpaceSwitcherRow(spaces: List<MemorySpaceEntity>, selectedSpaceId: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    if (spaces.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MuseIcons.folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = MusePaddings.screen),
        )
        spaces.forEach { space ->
            MuseChip(
                selected = space.id == selectedSpaceId,
                onClick = { onSelect(space.id) },
                label = space.name,
            )
        }
    }
}

/**
 * v1.0.52 P2-2: Página de gestión del espacio de memoria — Space Interfaz CRUD.
 *
 * Función:
 *  - Mostrar lista de todos los espacios(Contiene el número de hechos.)
 *  - crear nuevo Space(botón superior +)
 *  - Cambiar nombre Space(Haga clic en el icono de edición)
 *  - Eliminar Space(El espacio predeterminado no se puede eliminar,Mover los datos a los valores predeterminados antes de eliminarlos)
 */
@Composable
fun MemorySpaceManageScreen(onBack: () -> Unit, viewModel: MemorySpaceViewModel = koinViewModel()) {
    val spaces by viewModel.spacesWithCount.collectAsStateWithLifecycle()
    val operationMessage by viewModel.operationMessage.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<MemorySpaceWithCount?>(null) }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.memory_space_manage_title),
                onBack = onBack,
                actions = {
                    MuseTactileButton(
                        icon = MuseIcons.plus,
                        onClick = { showCreateDialog = true },
                        contentDescription = stringResource(R.string.memory_space_create),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            itemsIndexed(spaces, key = { _, it -> it.id }) { index, space ->
                // v2.x: Finalización del efecto de movimiento — Admisión de entrada a la lista de espacios/Reorganizar la transición(subir/Bajar traerá animación.)
                Box(museAnimateItem()) {
                    SpaceRow(
                        space = space,
                        onRename = { renameTarget = space },
                        onDelete = { viewModel.deleteSpace(space.id) },
                        onMoveUp = {
                            if (index > 0) {
                                viewModel.reorderSpaces(
                                    spaces.toMutableList().apply {
                                        add(index - 1, removeAt(index))
                                    }.map { it.id },
                                )
                            }
                        },
                        onMoveDown = {
                            if (index < spaces.lastIndex) {
                                viewModel.reorderSpaces(
                                    spaces.toMutableList().apply {
                                        add(index + 1, removeAt(index))
                                    }.map { it.id },
                                )
                            }
                        },
                    )
                }
            }
        }

        // Comentarios de operación
        operationMessage?.let { msg ->
            MuseSurface(
                modifier = Modifier.padding(padding),
                shape = MuseShapes.semiLarge,
                color = MuseActionColors.neutralContainer,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                    modifier = Modifier.padding(
                        start = MusePaddings.itemGap,
                        end = MusePaddings.tightGap,
                        top = MusePaddings.tightGap,
                        bottom = MusePaddings.tightGap,
                    ),
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MuseActionColors.neutralContent,
                        modifier = Modifier.weight(1f),
                        // LAYOUT-01: Row Ancho del texto interno + límite de línea, evita el ajuste de línea palabra por palabra al ampliar el tamaño de fuente
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MuseCapsuleButton(
                        text = stringResource(R.string.common_confirm),
                        onClick = viewModel::clearOperationMessage,
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                    )
                }
            }
        }

        if (showCreateDialog) {
            SpaceCreateDialog(
                onConfirm = { name ->
                    viewModel.createSpace(name)
                    showCreateDialog = false
                },
                onDismiss = { showCreateDialog = false },
            )
        }
        renameTarget?.let { target ->
            SpaceRenameDialog(
                initialName = target.name,
                onConfirm = { newName ->
                    viewModel.renameSpace(target.id, newName)
                    renameTarget = null
                },
                onDismiss = { renameTarget = null },
            )
        }
    }
}

@Suppress("LongMethod", "FunctionNaming")
@Composable
private fun SpaceRow(
    space: MemorySpaceWithCount,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    // v2.x unificación: Las acciones en línea convergen en el menú "Más"(Evite que las columnas de texto de la pantalla estrechas sean comprimidas por varios botones)
    val moreCd = stringResource(R.string.settings_mcp_more)
    var menuExpanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = space.name,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.memory_space_fact_count, space.factCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MuseTactileButton(
                icon = MuseIcons.moreVertical,
                onClick = { menuExpanded = true },
                contentDescription = moreCd,
            )
        }
    }

    if (menuExpanded) {
        MuseDialog(
            onDismissRequest = { menuExpanded = false },
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    MuseActionSheetRow(
                        icon = MuseIcons.edit,
                        text = stringResource(R.string.memory_space_rename),
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    MuseActionSheetRow(
                        icon = MuseIcons.arrowUp,
                        text = stringResource(R.string.prompt_template_manager_move_up),
                        onClick = {
                            menuExpanded = false
                            onMoveUp()
                        },
                    )
                    MuseActionSheetRow(
                        icon = MuseIcons.arrowDown,
                        text = stringResource(R.string.prompt_template_manager_move_down),
                        onClick = {
                            menuExpanded = false
                            onMoveDown()
                        },
                    )
                    if (space.id != MemorySpaceEntity.DEFAULT_SPACE_ID) {
                        MuseActionSheetRow(
                            icon = MuseIcons.trash,
                            text = stringResource(R.string.memory_space_delete),
                            contentColor = MaterialTheme.colorScheme.error,
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            },
            confirmText = stringResource(R.string.common_close),
            onConfirm = { menuExpanded = false },
            onDismiss = { menuExpanded = false },
        )
    }
}

@Composable
private fun SpaceCreateDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    MuseFormDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.memory_space_create_title),
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onConfirm(name.trim()) },
        onDismiss = onDismiss,
        content = {
            MuseTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.memory_space_name_label)) },
                singleLine = true,
            )
        },
    )
}

@Composable
private fun SpaceRenameDialog(initialName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    MuseFormDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.memory_space_rename_title),
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onConfirm(name.trim()) },
        onDismiss = onDismiss,
        content = {
            MuseTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.memory_space_name_label)) },
                singleLine = true,
            )
        },
    )
}
