package io.zer0.muse.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantCardExporter
import io.zer0.muse.data.assistant.AssistantCardImportBridge
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.assistant.CharacterCardImporter
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.museAnimateItem
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * Página de lista de asistentes —— Completamente reescrito.
 *
 * Puntos de diseño:
 *  - titular MuseTopBar,Entrada de tarjeta de personaje de importación a la derecha
 *  - fondo blanco cálido,Agrupación de tarjetas de esquinas redondeadas blancas.
 *  - Entrada a la memoria a largo plazo independiente CardGroup
 *  - Lista de asistentes CardGroup: avatar + nombre + estado actual punto verde + vista previa del mensaje del sistema + más menús
 *  - Área de operación CardGroup: Nuevo asistente/Importar tarjeta de personaje de SillyTavern
 *  - Todos los comentarios de prensa se desvanecen y se tocan ligeramente,Sin ondulaciones materiales
 */
@Composable
fun AssistantScreen(onBack: () -> Unit, onOpenDetail: (String) -> Unit, viewModel: ChatViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val repo: AssistantRepository = koinInject()
    val settings: SettingsRepository = koinInject()
    val defaultAssistantId by settings.defaultAssistantIdFlow.collectAsStateWithLifecycle(initialValue = "default")
    // Correcciones en la interfaz (persistencia-2): Boolean Cambio de estado de la ventana emergente rememberSaveable
    var showDefaultAssistantPicker by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Correcciones en la interfaz (persistencia-2): AssistantEntity no puede directamente saveable,Cambiar para guardar id(String) Buscar de nuevo
    var actionSheetAssistantId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var exportTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    // v2.x: Tarjeta de personaje por confirmar para importación.(Análisis completado,Escribe solo después de que el usuario confirme)
    var pendingImportCard by remember { mutableStateOf<AssistantCardExporter.ParsedCard?>(null) }

    // Correcciones en la interfaz (persistencia-2): Entidad de verificación inversa por identificación guardada(Lista de ViewModel state,El proceso aún se puede recuperar después de la reconstrucción.;
    // id Si no lo encuentra, busque como null,La ventana emergente, naturalmente, no se mostrará.,Bajada segura)
    val actionSheetAssistant = actionSheetAssistantId?.let { id -> state.assistants.find { it.id == id } }
    val deleteTarget = deleteTargetId?.let { id -> state.assistants.find { it.id == id } }
    val exportTarget = exportTargetId?.let { id -> state.assistants.find { it.id == id } }

    // i18n: Precargar recursos de cadena
    val screenTitle = stringResource(R.string.assistant_screen_title)
    val importCardCd = stringResource(R.string.assistant_import_card_cd)
    val importPreviewTitle = stringResource(R.string.assistant_import_preview_title)
    val importPreviewConfirm = stringResource(R.string.assistant_import_preview_confirm)
    val emptyCreateText = stringResource(R.string.assistant_empty_create)
    val unnamedText = stringResource(R.string.assistant_unnamed)
    val currentText = stringResource(R.string.assistant_current)
    val moreCd = stringResource(R.string.assistant_more_cd)
    val newActionText = stringResource(R.string.assistant_new_action)
    val moveUpCd = stringResource(R.string.assistant_move_up_cd)
    val moveUpText = stringResource(R.string.assistant_move_up)
    val moveDownCd = stringResource(R.string.assistant_move_down_cd)
    val moveDownText = stringResource(R.string.assistant_move_down)
    val cloneCd = stringResource(R.string.assistant_clone_cd)
    val cloneText = stringResource(R.string.assistant_clone)
    val exportCardCd = stringResource(R.string.assistant_export_card_cd)
    val exportCardText = stringResource(R.string.assistant_export_card)
    val deleteCd = stringResource(R.string.assistant_delete_cd)
    val deleteText = stringResource(R.string.assistant_delete)
    val closeText = stringResource(R.string.assistant_close)
    val deleteDialogTitle = stringResource(R.string.assistant_delete_title)
    val importSillyTavernCd = stringResource(R.string.assistant_import_sillytavern_cd)
    val importSillyTavernText = stringResource(R.string.assistant_import_sillytavern)
    val toastSillyTavernImported = stringResource(R.string.assistant_toast_sillytavern_imported)
    val toastSillyTavernImportFailed = stringResource(R.string.assistant_toast_sillytavern_import_failed)
    val sectionAssistants = stringResource(R.string.assistant_section_my_assistants)
    val sectionActions = stringResource(R.string.assistant_section_actions)
    val defaultAssistantTitle = stringResource(R.string.assistant_default_title)
    val defaultAssistantPickerTitle = stringResource(R.string.assistant_default_picker_title)
    val defaultAssistantHint = stringResource(R.string.assistant_default_hint)

    // Exportar tarjeta de personaje launcher(SAF CreateDocument,.muse-assistant Archivo)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val target = exportTarget
        exportTargetId = null
        if (uri != null && target != null) {
            scope.launch {
                runCatching { AssistantCardExporter.export(context, target, uri) }
                    .onSuccess { MuseToast.show(context.getString(R.string.assistant_toast_exported, target.name)) }
                    .onFailure { MuseToast.show(context.getString(R.string.assistant_toast_export_failed, it.message ?: ""), 3500) }
            }
        }
    }

    // Importar cartas de personajes launcher(SAF OpenDocument) — v2.x: Analizar primero y luego obtener una vista previa y confirmar,Escribir después de la confirmación
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            scope.launch {
                runCatching { AssistantCardExporter.parse(context, it) }
                    .onSuccess { parsed ->
                        if (parsed != null) {
                            pendingImportCard = parsed
                        } else {
                            MuseToast.show(context.getString(R.string.assistant_toast_import_invalid), 3500)
                        }
                    }
                    .onFailure { MuseToast.show(context.getString(R.string.assistant_toast_import_failed, it.message ?: ""), 3500) }
            }
        }
    }

    // v2.x: Haga clic externamente para abrir el archivo del paquete de personajes.(.muse-assistant)→ Analizar y obtener una vista previa para confirmar
    val pendingExternalCardUri by AssistantCardImportBridge.pendingUri.collectAsStateWithLifecycle()
    LaunchedEffect(pendingExternalCardUri) {
        val uri = pendingExternalCardUri ?: return@LaunchedEffect
        AssistantCardImportBridge.consume()
        runCatching { AssistantCardExporter.parse(context, uri) }
            .onSuccess { parsed ->
                if (parsed != null) {
                    pendingImportCard = parsed
                } else {
                    MuseToast.show(context.getString(R.string.assistant_toast_import_invalid), 3500)
                }
            }
            .onFailure { MuseToast.show(context.getString(R.string.assistant_toast_import_failed, it.message ?: ""), 3500) }
    }

    // Importar tarjetas de personajes de SillyTavern launcher
    val importSillyTavernLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            scope.launch {
                CharacterCardImporter.importAuto(context, it)
                    .onSuccess { entity ->
                        val now = System.currentTimeMillis()
                        val saved = entity.copy(
                            id = Uuid.random().toString(),
                            createdAt = now,
                            updatedAt = now,
                        )
                        repo.upsert(saved)
                        MuseToast.show(toastSillyTavernImported.format(saved.name))
                    }
                    .onFailure { MuseToast.show(toastSillyTavernImportFailed.format(it.message ?: ""), 3500) }
            }
        }
    }

    fun createNewAssistant() {
        val now = System.currentTimeMillis()
        val newId = "assistant-$now"
        scope.launch {
            repo.upsert(
                AssistantEntity(
                    id = newId,
                    name = context.getString(R.string.assistant_new_default_name),
                    // v1.0.51: Nuevo asistente precarga predeterminado prompt(Carga desde activos,respaldo con la configuración regional)
                    systemPrompt = repo.loadDefaultPrompt(),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            onOpenDetail(newId)
        }
    }

    fun cloneAssistant(assistant: AssistantEntity) {
        val now = System.currentTimeMillis()
        val cloneId = "assistant-$now-clone"
        scope.launch {
            repo.upsert(
                assistant.copy(
                    id = cloneId,
                    name = context.getString(R.string.assistant_clone_name, assistant.name),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = screenTitle,
                onBack = onBack,
                largeTitle = true,
                actions = {
                    MuseTactileButton(
                        icon = MuseIcons.download,
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        contentDescription = importCardCd,
                        size = MuseIconSizes.touchTarget,
                        iconSize = MuseIconSizes.iconMedium,
                    )
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = MusePaddings.screen),
            contentPadding = PaddingValues(vertical = MusePaddings.sectionGap),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
        ) {
            // lista de asistentes
            if (state.isAssistantsLoading) {
                // v2.x: Finalización del efecto de movimiento — Entrada a la pantalla esqueleto(ficha animateItem)
                item {
                    Box(museAnimateItem()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                        ) {
                            repeat(6) {
                                io.zer0.muse.ui.common.surface.AvatarRowSkeleton()
                            }
                        }
                    }
                }
            } else {
                // v2.x: Finalización del efecto de movimiento — Entrada a la baraja de cartas(ficha animateItem)
                item {
                    Box(museAnimateItem()) {
                        CardGroup(
                            title = {
                                Text(defaultAssistantTitle)
                            },
                        ) {
                            item(
                                onClick = { showDefaultAssistantPicker = true },
                                leadingContent = {
                                    Icon(
                                        imageVector = MuseIcons.brain,
                                        contentDescription = null,
                                        modifier = Modifier.size(MuseIconSizes.icon),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                headlineContent = {
                                    Text(defaultAssistantTitle)
                                },
                                supportingContent = {
                                    val currentDefault = state.assistants.firstOrNull { it.id == defaultAssistantId }
                                    Text(currentDefault?.name?.ifBlank { null } ?: defaultAssistantTitle)
                                },
                                trailingContent = { ChevronRight() },
                            )
                        }
                    }
                }

                // v2.x: Finalización del efecto de movimiento — Entrada a la baraja de cartas(ficha animateItem)
                item {
                    Box(museAnimateItem()) {
                        CardGroup(
                            title = {
                                Text(sectionAssistants)
                            },
                        ) {
                            if (state.assistants.isEmpty()) {
                                item(
                                    onClick = { createNewAssistant() },
                                    leadingContent = {
                                        Icon(
                                            imageVector = MuseIcons.plus,
                                            contentDescription = null,
                                            modifier = Modifier.size(MuseIconSizes.icon),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    headlineContent = {
                                        Text(
                                            text = emptyCreateText,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                )
                            } else {
                                state.assistants.forEach { assistant ->
                                    item(
                                        key = assistant.id,
                                        onClick = { onOpenDetail(assistant.id) },
                                        onLongClick = { actionSheetAssistantId = assistant.id },
                                        leadingContent = {
                                            AssistantAvatar(assistant = assistant, avatarSize = 44.dp)
                                        },
                                        headlineContent = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                                            ) {
                                                Text(
                                                    text = assistant.name.ifBlank { unnamedText },
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                if (assistant.id == state.currentAssistant?.id) {
                                                    CurrentIndicator(text = currentText)
                                                }
                                            }
                                        },
                                        supportingContent = {
                                            val desc = assistant.summary.ifBlank {
                                                assistant.systemPrompt.take(40).replace("\n", " ")
                                            }
                                            if (desc.isNotEmpty()) {
                                                Text(
                                                    text = desc,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        },
                                        trailingContent = {
                                            MuseTactileButton(
                                                icon = MuseIcons.moreVertical,
                                                onClick = { actionSheetAssistantId = assistant.id },
                                                contentDescription = moreCd,
                                                size = MuseIconSizes.touchTarget,
                                                iconSize = MuseIconSizes.iconMedium,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // área de operación:Sólo se muestra si hay un asistente.,Evite la duplicación de estados vacíos."Nuevo"
                // v2.x: Finalización del efecto de movimiento — Entrada a la baraja de cartas(ficha animateItem)
                if (state.assistants.isNotEmpty()) {
                    item {
                        Box(museAnimateItem()) {
                            CardGroup(
                                title = {
                                    Text(sectionActions)
                                },
                            ) {
                                item(
                                    onClick = { createNewAssistant() },
                                    leadingContent = {
                                        Icon(
                                            imageVector = MuseIcons.plus,
                                            contentDescription = null,
                                            modifier = Modifier.size(MuseIconSizes.icon),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    headlineContent = {
                                        Text(
                                            text = newActionText,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    trailingContent = { ChevronRight() },
                                )
                                item(
                                    onClick = {
                                        runCatching {
                                            importSillyTavernLauncher.launch(
                                                arrayOf("image/png", "application/json", "*/*"),
                                            )
                                        }.onFailure {
                                            MuseToast.show(toastSillyTavernImportFailed.format(it.message ?: ""), 3500)
                                        }
                                    },
                                    leadingContent = {
                                        Icon(
                                            imageVector = MuseIcons.image,
                                            contentDescription = importSillyTavernCd,
                                            modifier = Modifier.size(MuseIconSizes.icon),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    headlineContent = {
                                        Text(
                                            text = importSillyTavernText,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    },
                                    trailingContent = { ChevronRight() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDefaultAssistantPicker) {
        MuseDialog(
            onDismissRequest = { showDefaultAssistantPicker = false },
            title = defaultAssistantPickerTitle,
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (state.assistants.isEmpty()) {
                        Text(
                            text = defaultAssistantHint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    } else {
                        state.assistants.forEach { assistant ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showDefaultAssistantPicker = false
                                        scope.launch { settings.saveDefaultAssistantId(assistant.id) }
                                    }
                                    .padding(vertical = MusePaddings.itemGap),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AssistantAvatar(assistant = assistant, avatarSize = 36.dp)
                                Spacer(Modifier.width(MusePaddings.contentGap))
                                Text(
                                    text = assistant.name.ifBlank { unnamedText },
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                if (assistant.id == defaultAssistantId) {
                                    Text(
                                        text = currentText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmText = closeText,
            onConfirm = { showDefaultAssistantPicker = false },
            onDismiss = { showDefaultAssistantPicker = false },
        )
    }

    // Menú de operación(MuseDialog)
    actionSheetAssistant?.let { assistant ->
        val currentIndex = state.assistants.indexOf(assistant)
        val canMoveUp = currentIndex > 0
        val canMoveDown = currentIndex >= 0 && currentIndex < state.assistants.size - 1

        MuseDialog(
            onDismissRequest = { actionSheetAssistantId = null },
            title = assistant.name.ifBlank { unnamedText },
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // avatar de cabeza
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AssistantAvatar(assistant = assistant, avatarSize = 56.dp)
                    }
                    Spacer(Modifier.size(12.dp))

                    // subir
                    if (canMoveUp) {
                        ActionMenuRow(
                            icon = MuseIcons.arrowUp,
                            contentDescription = moveUpCd,
                            text = moveUpText,
                            onClick = {
                                scope.launch {
                                    val upper = state.assistants[currentIndex - 1]
                                    // P2-7: intercambiar verdad sortIndex,En lugar de escribir el subíndice de la lista como sortIndex(≥3 Los artículos se confundirán)
                                    repo.upsert(
                                        assistant.copy(
                                            sortIndex = upper.sortIndex,
                                            updatedAt = System.currentTimeMillis(),
                                        ),
                                    )
                                    repo.upsert(
                                        upper.copy(
                                            sortIndex = assistant.sortIndex,
                                            updatedAt = System.currentTimeMillis(),
                                        ),
                                    )
                                }
                                actionSheetAssistantId = null
                            },
                        )
                    }

                    // bajar
                    if (canMoveDown) {
                        ActionMenuRow(
                            icon = MuseIcons.arrowDown,
                            contentDescription = moveDownCd,
                            text = moveDownText,
                            onClick = {
                                scope.launch {
                                    val lower = state.assistants[currentIndex + 1]
                                    // P2-7: intercambiar verdad sortIndex,En lugar de escribir el subíndice de la lista como sortIndex
                                    repo.upsert(
                                        assistant.copy(
                                            sortIndex = lower.sortIndex,
                                            updatedAt = System.currentTimeMillis(),
                                        ),
                                    )
                                    repo.upsert(
                                        lower.copy(
                                            sortIndex = assistant.sortIndex,
                                            updatedAt = System.currentTimeMillis(),
                                        ),
                                    )
                                }
                                actionSheetAssistantId = null
                            },
                        )
                    }

                    // clonar
                    ActionMenuRow(
                        icon = MuseIcons.copy,
                        contentDescription = cloneCd,
                        text = cloneText,
                        onClick = {
                            cloneAssistant(assistant)
                            actionSheetAssistantId = null
                        },
                    )

                    // U-26: habilitar/desactivar(Ocultarse de la lista de candidatos después de la desactivación,No afecta las sesiones existentes.)
                    ActionMenuRow(
                        icon = if (assistant.enabled) MuseIcons.eyeOff else MuseIcons.eye,
                        contentDescription = stringResource(
                            if (assistant.enabled) R.string.assistant_disable_cd else R.string.assistant_enable_cd,
                        ),
                        text = stringResource(
                            if (assistant.enabled) R.string.assistant_disable else R.string.assistant_enable,
                        ),
                        onClick = {
                            scope.launch { repo.setEnabled(assistant.id, !assistant.enabled) }
                            actionSheetAssistantId = null
                        },
                    )

                    // Exportar tarjeta de personaje
                    ActionMenuRow(
                        icon = MuseIcons.upload,
                        contentDescription = exportCardCd,
                        text = exportCardText,
                        onClick = {
                            exportTargetId = assistant.id
                            actionSheetAssistantId = null
                            val safeName = assistant.name
                                .ifBlank { "assistant" }
                                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                            exportLauncher.launch("$safeName.muse-assistant")
                        },
                    )

                    // Eliminar (v2.6.2: Trae el tuyo/El asistente predeterminado también se puede eliminar —— La capa inferior tiene la protección de "cambiar automáticamente el puntero predeterminado a otro asistente".
                    // Sólo deténgase si solo queda un asistente. Para implementación anterior `id != "default"` La entrada está oculta y los usuarios no pueden eliminar el asistente integrado. )
                    ActionMenuRow(
                        icon = MuseIcons.trash,
                        contentDescription = deleteCd,
                        text = deleteText,
                        tint = MaterialTheme.colorScheme.error,
                        onClick = {
                            deleteTargetId = assistant.id
                            actionSheetAssistantId = null
                        },
                    )
                }
            },
            onConfirm = null,
            dismissText = closeText,
            onDismiss = { actionSheetAssistantId = null },
        )
    }

    // Eliminar cuadro de diálogo de confirmación
    deleteTarget?.let { target ->
        ConfirmDeleteDialog(
            title = deleteDialogTitle,
            itemName = target.name,
            // MEM-04: Nombra al asistente + explica las consecuencias(Su banco de memoria independiente será eliminado.;Las conversaciones que ya le pertenecían permanecen pero pierden sus vínculos.)
            consequence = stringResource(R.string.assistant_delete_consequence),
            onConfirm = {
                // v2.x(comentarios de los usuarios): El asistente predeterminado también se puede eliminar (protección condicional)——
                // ① Mantener al menos dos asistentes;
                // ② Lo que se elimina es el asistente predeterminado (id=default o el puntero predeterminado apunta a),
                //    Cambie automáticamente el puntero predeterminado a otro asistente para evitar"No hay valores predeterminados disponibles".
                scope.launch {
                    val all = repo.getAll()
                    if (all.size < 2) {
                        MuseToast.show(
                            context.getString(R.string.err_chat_assistant_delete_last),
                        )
                        return@launch
                    }
                    val currentDefaultId = settings.getDefaultAssistantId()
                    if (target.id == currentDefaultId || target.id == "default") {
                        val successor = all.firstOrNull { it.id != target.id }
                        if (successor != null) {
                            settings.saveDefaultAssistantId(successor.id)
                        }
                    }
                    repo.delete(target.id)
                }
                deleteTargetId = null
            },
            onDismiss = { deleteTargetId = null },
        )
    }

    // v2.x: Cuadro de diálogo de confirmación de vista previa de importación(Aparece una vez completado el análisis.,Escribir después de la confirmación)
    pendingImportCard?.let { card ->
        MuseDialog(
            onDismissRequest = { pendingImportCard = null },
            title = importPreviewTitle,
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AssistantAvatar(assistant = card.entity, avatarSize = 72.dp)
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    Text(
                        text = card.entity.name.ifBlank { unnamedText },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val previewText = card.entity.systemPrompt.trim()
                    if (previewText.isNotEmpty()) {
                        Spacer(Modifier.height(MusePaddings.auxGap))
                        Text(
                            text = previewText.take(160),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmText = importPreviewConfirm,
            onConfirm = {
                pendingImportCard?.let { target ->
                    pendingImportCard = null
                    scope.launch {
                        runCatching { AssistantCardExporter.commitParsed(context, repo, target) }
                            .onSuccess { MuseToast.show(context.getString(R.string.assistant_toast_imported, it.name)) }
                            .onFailure { MuseToast.show(context.getString(R.string.assistant_toast_import_failed, it.message ?: ""), 3500) }
                    }
                }
            },
            onDismiss = { pendingImportCard = null },
        )
    }
}

/**
 * "actual"indicador de estado:Punto verde + texto,Se utiliza para identificar al asistente seleccionado actualmente.
 */
@Composable
private fun CurrentIndicator(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MuseShapes.pill,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * Línea del menú de acciones:icono + texto,Sin fronteras,Se puede hacer clic en toda la fila.
 */
@Composable
private fun ActionMenuRow(
    icon: ImageVector,
    contentDescription: String,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .heightIn(min = MuseIconSizes.touchTarget)
            .padding(horizontal = MusePaddings.screen, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.auxGap),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(MuseIconSizes.iconMedium),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
        )
    }
}

/**
 * indicador de flecha derecha,Coherente con el estilo de elemento de lista/grupo de tarjetas.
 */
@Composable
private fun ChevronRight(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(
        imageVector = MuseIcons.chevronRight,
        contentDescription = null,
        modifier = modifier.size(MuseIconSizes.iconMedium),
        tint = tint,
    )
}
