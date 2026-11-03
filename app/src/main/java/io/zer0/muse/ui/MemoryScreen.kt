@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:Suppress("LongMethod")

package io.zer0.muse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.memory.fact.MemoryLegacyReset
import io.zer0.memory.space.MemorySpaceEntity
import io.zer0.memory.summary.MemoryDbArchiveRecovery
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseActionSheetRow
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.museAnimateItem
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.memory.MemoryGraphSettingsDialog
import io.zer0.muse.ui.memory.MemoryGraphView
import io.zer0.muse.ui.memory.MemoryGraphViewModel
import io.zer0.muse.ui.memory.MemoryTimelineFilterRow
import io.zer0.muse.ui.memory.MonthHeader
import io.zer0.muse.ui.memory.PinnedMemorySection
import io.zer0.muse.ui.memory.TimelineEventCard
import io.zer0.muse.ui.memory.TimelineItem
import io.zer0.muse.ui.memory.TimelineItemActions
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId

/**
 * Observatorio de la Memoria.
 *
 * Tarjeta de descripción general superior + conmutación de segmento compacto de tres segmentos (flujo de memoria/base de hechos/constelación de memoria),
 * El área de contenido solo muestra el segmento actual, evitando una lista infinitamente larga en una sola página y evitando cápsulas de filtro de desplazamiento horizontal.
 */
@Composable
fun MemoryScreen(
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: MemoryViewModel = koinViewModel(),
    onOpenSession: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scopes by viewModel.availableScopes.collectAsStateWithLifecycle()
    val spaces by viewModel.availableSpaces.collectAsStateWithLifecycle()
    val selectedScope by viewModel.selectedScope.collectAsStateWithLifecycle()
    val selectedSpace by viewModel.selectedSpaceId.collectAsStateWithLifecycle()
    // F-7: Estado del filtro de importancia/intervalo de tiempo
    val importanceFilter by viewModel.importanceFilter.collectAsStateWithLifecycle()
    val timeRangeFilter by viewModel.timeRangeFilter.collectAsStateWithLifecycle()
    val organizing by viewModel.organizeRunning.collectAsStateWithLifecycle()
    val organizeStage by viewModel.organizeStage.collectAsStateWithLifecycle()
    // Fusión de selección múltiple: ViewModel mantiene el borrador, la ejecución y el error, y la interfaz solo es responsable de la visualización y la recopilación.
    val mergeDraft by viewModel.mergeDraft.collectAsStateWithLifecycle()
    val mergeRunning by viewModel.mergeRunning.collectAsStateWithLifecycle()
    val mergeError by viewModel.mergeError.collectAsStateWithLifecycle()
    val archiveRecovery: MemoryDbArchiveRecovery = koinInject()
    val archiveRecoveryScope = rememberCoroutineScope()
    var archiveInfo by remember { mutableStateOf<MemoryDbArchiveRecovery.ArchiveInfo?>(null) }
    var showArchiveRecoveryConfirm by remember { mutableStateOf(false) }
    var archiveRecoveryRunning by remember { mutableStateOf(false) }

    // Contexto: el hecho de que se está viendo el historial de revisiones (el panel de revisión aparece cuando no es nulo)
    var revisionTarget by remember { mutableStateOf<MemoryItem?>(null) }
    val factRevisions by viewModel.factRevisions.collectAsStateWithLifecycle()
    revisionTarget?.let { target ->
        FactRevisionsSheet(
            revisions = factRevisions,
            factContent = target.content,
            onRevert = { revisionId -> viewModel.revertFactToRevision(target.id, revisionId, target.scope) },
            onDismiss = { revisionTarget = null },
        )
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val widthClass = rememberWindowWidthClass()
    // v2.2.1: archivo de memoria/Consejos de recuperación — Una falla en la protección de versiones provocó que la biblioteca real se archivara por error;Recuperar resultados de recuperación únicos después de la reparación
    LaunchedEffect(Unit) {
        val recovered = MemoryLegacyReset.consumeRecovered(context)
        val hadArchive = MemoryLegacyReset.consume(context)
        when {
            recovered != null && recovered > 0 ->
                MuseToast.show(context.getString(R.string.memory_archive_recovered, recovered))
            hadArchive -> MuseToast.show(context.getString(R.string.memory_archive_rebuilt_hint))
        }
        archiveInfo = archiveRecovery.availableArchives()
            .maxByOrNull { it.storedVersion }
    }
    var tab by remember { mutableIntStateOf(0) } // 0=flujo de memoria 1=base de hechos 2=constelación
    var query by remember { mutableStateOf("") }
    var editItem by remember { mutableStateOf<MemoryItem?>(null) }
    var showAddFact by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    // P1-3: Modo de vista de flujo de memoria(false=lista, true=línea de tiempo)
    var streamTimelineMode by remember { mutableStateOf(false) }
    // P1-3: Filtrado interno de la línea de tiempo(Todos/hechos/Resumen/hito)
    var timelineFilter by remember { mutableStateOf("all") }
    // F-10: Entrada de destino para el diálogo de selección de importancia
    var importanceItem by remember { mutableStateOf<MemoryItem?>(null) }
    // U-3: Entradas de destino que requieren confirmación secundaria antes de la eliminación(Cuando no está seleccionado, es null,Sin ventanas emergentes)
    var deleteTarget by remember { mutableStateOf<MemoryItem?>(null) }

    // El objetivo del panel de acciones aparece presionando prolongadamente la tarjeta de la línea de tiempo.
    // Las cosas no se pueden eliminar directamente de la tarjeta de línea de tiempo.,Mantenga presionado el panel de primero en salir(Eliminar/opción múltiple),Eliminar y confirmar nuevamente.
    var timelineActionTarget by remember { mutableStateOf<TimelineItem?>(null) }

    // Destino de confirmación secundario para la eliminación por lotes de selección múltiple;Aparece el cuadro de confirmación cuando no es nulo.
    // La eliminación por lotes elimina varias filas a la vez y no se puede deshacer.,Con solo un clic, se colocará directamente en la base de datos sin espacio para el rescate.
    var pendingBulkDelete by remember { mutableStateOf<List<MemoryItem>?>(null) }

    // v2.6.10: Eliminar varias selecciones.
    // Mi memoria será aleatoria y repetida, y eliminaré elementos uno por uno. Cada vez que elimino un elemento, volverá a la parte superior de la lista y tendré que desplazarme hacia abajo para encontrar el siguiente elemento.
    // Así que mantenga presionado para ingresar múltiples selecciones y elimínelas en lotes después de seleccionarlas una vez.
    var selectionMode by remember { mutableStateOf(false) }
    val selectedFactKeys = remember { androidx.compose.runtime.mutableStateListOf<String>() }

    /** Identificación estable del elemento seleccionado: el fact.id de la biblioteca principal y cada biblioteca secundaria colisionarán, y se debe incluir el alcance. */
    fun factKeyOf(item: MemoryItem): String = "${item.scope.orEmpty()}|${item.id}"

    fun toggleFactSelection(item: MemoryItem) {
        val key = factKeyOf(item)
        if (selectedFactKeys.contains(key)) selectedFactKeys.remove(key) else selectedFactKeys.add(key)
    }

    fun exitSelection() {
        selectionMode = false
        selectedFactKeys.clear()
    }

    /**
     * Se ha enviado la confirmación de la fusión.
     *
     * ViewModel borra el borrador en caso de éxito, lo mantiene y proporciona mergeError en caso de error.
     * Por lo tanto, sólo "el borrador desaparece + esta bandera es verdadera" significa que la fusión fue realmente exitosa y es hora de salir de la selección múltiple.
     */
    var mergeConfirmPending by remember { mutableStateOf(false) }

    /**
     * v2.6.7: Nueva biblioteca de experiencias agregada/Editar destino emergente.
     *
     * ExperienceLibraryCard y ExperienceEditDialog ya se ha escrito pero nunca se ha hecho referencia a él,
     * Como resultado, la experiencia solo puede ser escrita por IA y los usuarios no pueden leerla ni cambiarla. Aquí dos componentes están conectados al flujo de memoria.
     */
    var experienceTarget by remember { mutableStateOf<MemoryItem?>(null) }

    var showAddExperience by remember { mutableStateOf(false) }

    /** v2.6.7: Destino de confirmación secundario para la eliminación única de la memoria del chat grupal. */
    var groupChatMemoryTarget by remember { mutableStateOf<GroupChatMemoryUiItem?>(null) }

    /** v2.6.7: Confirmación secundaria para borrar todos los recuerdos del chat grupal. */
    var showClearGroupChatConfirm by remember { mutableStateOf(false) }

    // v2.6.7: Cambiar a "Memoria de chat grupal"tab cargado (anteriormente cero llamadas a loadGroupChatMemories)
    LaunchedEffect(tab) {
        if (tab == 3) {
            viewModel.loadGroupChatMemories()
        }
    }

    LaunchedEffect(query) {
        delay(300)
        viewModel.search(query)
    }
    LaunchedEffect(viewModel.organizeResult) {
        val result = viewModel.organizeResult.value ?: return@LaunchedEffect
        if (result.startsWith("done:")) {
            val merged = result.removePrefix("done:").toIntOrNull() ?: 0
            MuseToast.show(
                if (merged == 0) {
                    context.getString(R.string.memory_organize_stage_no_duplicates)
                } else {
                    context.getString(R.string.memory_organize_stage_complete, merged)
                },
            )
        } else {
            MuseToast.show(context.getString(R.string.memory_organize_stage_failed))
        }
        viewModel.consumeOrganizeResult()
    }

    LaunchedEffect(mergeError) {
        mergeError?.let {
            // Si hay un error, significa que esta vez la confirmación está fuera de la base de datos (el borrador se conservará para volver a intentarlo): reinicio pendiente,
            // De lo contrario, la marca permanecerá en el estado intermedio de "Confirmación enviada" y la próxima vez que algo borre el borrador, se saldrá de la selección múltiple por error.
            mergeConfirmPending = false
            MuseToast.show(it)
            viewModel.consumeMergeError()
        }
    }
    // Hay dos razones para la desaparición de los borradores: confirmación exitosa (borrada después de ser eliminado) y cancelación del usuario.
    // Sólo el primero sale de la selección múltiple; para cancelar, debe permanecer en el estado de selección múltiple, lo cual es conveniente para fusionar después de cambiar la selección.
    LaunchedEffect(mergeDraft) {
        if (mergeDraft == null && mergeConfirmPending) {
            mergeConfirmPending = false
            exitSelection()
        }
    }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.memory_screen_title),
                onBack = onBack,
                largeTitle = true,
                actions = {
                    if (selectionMode) {
                        // Estado de selección múltiple: mantenga únicamente "Cancelar", "Fusionar" y "Eliminar" para evitar tocar accidentalmente otras entradas
                        MemorySelectionActions(
                            selectedCount = selectedFactKeys.size,
                            mergeRunning = mergeRunning,
                            onCancel = { exitSelection() },
                            onMerge = {
                                val picked =
                                    (state.factItems + state.searchResults)
                                        .distinctBy { factKeyOf(it) }
                                        .filter { factKeyOf(it) in selectedFactKeys }
                                viewModel.prepareMerge(picked)
                            },
                            onDelete = {
                                val picked =
                                    (state.factItems + state.searchResults)
                                        .distinctBy { factKeyOf(it) }
                                        .filter { factKeyOf(it) in selectedFactKeys }
                                // Confirme primero y luego colóquelo en la biblioteca.;Después de la confirmación, borre la selección mientras aún se encuentra en el estado de selección múltiple.(Vea la ventana emergente a continuación)
                                if (picked.isNotEmpty()) pendingBulkDelete = picked
                            },
                        )
                    } else {
                        MuseTactileButton(
                            icon = MuseIcons.brain,
                            onClick = onOpenSettings,
                            contentDescription = stringResource(R.string.settings_memory_page_title),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier =
                Modifier
                    .fillMaxSize()
                    .then(if (widthClass == WindowWidthClass.Expanded) Modifier.widthIn(max = 760.dp) else Modifier),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "memory_overview") {
                    MemoryOverviewCard(
                        factCount = state.factCount,
                        organizing = organizing,
                        stage = organizeStage,
                        scopeLabel =
                        scopes.firstOrNull { it.id == selectedScope }?.displayName
                            ?: stringResource(R.string.memory_center_scope_all),
                        spaceLabel =
                        spaces.firstOrNull { it.id == selectedSpace }?.name
                            ?: stringResource(R.string.memory_center_space_default),
                        onOrganize = viewModel::organizeMemory,
                        onOpenFilter = { showFilter = true },
                    )
                }
                archiveInfo?.let { info ->
                    item(key = "memory_archive_recovery") {
                        MemoryArchiveRecoveryCard(
                            info = info,
                            running = archiveRecoveryRunning,
                            onRestore = { showArchiveRecoveryConfirm = true },
                        )
                    }
                }
                item(key = "memory_tabs") {
                    MemoryCapsuleTabs(selected = tab, onSelect = { tab = it })
                }
                val memoryError = state.errorTrace
                if (memoryError != null) {
                    item(key = "memory_error") {
                        MuseErrorStateBox(
                            message =
                            memoryError.lineSequence().firstOrNull { it.isNotBlank() }?.take(240)
                                ?: stringResource(R.string.memory_graph_load_failed),
                            onRetry = { viewModel.loadAll() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                        )
                    }
                } else {
                    when (tab) {
                        0 ->
                            memoryStreamItems(
                                state = state,
                                scopes = scopes,
                                spaces = spaces,
                                timelineMode = streamTimelineMode,
                                timelineFilter = timelineFilter,
                                onTimelineFilter = { timelineFilter = it },
                                onToggleTimeline = { streamTimelineMode = !streamTimelineMode },
                                onOpenFacts = { tab = 1 },
                                onOpenSession = onOpenSession,
                                onHistory = {
                                    viewModel.loadFactRevisions(it.id, it.scope)
                                    revisionTarget = it
                                },
                                onEdit = { editItem = it },
                                onDelete = { deleteTarget = it },
                                onPin = { viewModel.toggleFactPinned(it.id, it.scope) },
                                onImportance = { importanceItem = it },
                                // Mantenga presionada la tarjeta de línea de tiempo:Salir primero del panel de acciones,Eliminar y confirmar nuevamente.
                                onTimelineActions = { timelineActionTarget = it },
                                selectionMode = selectionMode,
                                isSelected = { factKeyOf(it) in selectedFactKeys },
                                onLongPressItem = {
                                    selectionMode = true
                                    toggleFactSelection(it)
                                },
                                onToggleSelect = { toggleFactSelection(it) },
                            )
                        1 ->
                            memoryFactsItems(
                                state = state,
                                query = query,
                                onQuery = { query = it },
                                onAdd = { showAddFact = true },
                                onHistory = {
                                    viewModel.loadFactRevisions(it.id, it.scope)
                                    revisionTarget = it
                                },
                                onEdit = { editItem = it },
                                onDelete = { deleteTarget = it },
                                onPin = { viewModel.toggleFactPinned(it.id, it.scope) },
                                onImportance = { importanceItem = it },
                                onDismissContradiction = viewModel::dismissContradiction,
                                selectionMode = selectionMode,
                                isSelected = { factKeyOf(it) in selectedFactKeys },
                                onLongPressItem = {
                                    selectionMode = true
                                    toggleFactSelection(it)
                                },
                                onToggleSelect = { toggleFactSelection(it) },
                            )
                        2 ->
                            item(key = "memory_constellation") {
                                MemoryConstellationTab(
                                    scope = selectedScope,
                                    spaceId = selectedSpace,
                                    factCount = state.factCount,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 560.dp),
                                )
                            }
                        // v2.6.7: Pestaña de memoria del chat grupal (ViewModel se implementó antes pero nunca tuvo una entrada)
                        // No se necesita más: si se agrega una pestaña en el futuro, otra cosa la conectará por error. Escriba 3 explícitamente aquí.
                        3 ->
                            groupChatMemoryItems(
                                memories = state.groupChatMemories,
                                isLoading = state.groupChatMemoriesLoading,
                                onDelete = { groupChatMemoryTarget = it },
                                onClearAll = { showClearGroupChatConfirm = true },
                            )
                    }
                    // v2.6.7: Seguido de dos tarjetas que habían sido escritas hace mucho tiempo pero a las que nunca se hizo referencia.
                    // Colóquelo después de cuando en lugar de dentro de MemoryStreamItems. ——
                    // Esa función está en estado de carga./Los estados vacíos se devolverán antes y se omitirán si se colocan dentro.
                    if (tab == 0) {
                        // “AI comprensión de ti”:state.compiledMarkdown Lo leí hace mucho tiempo,
                        // Nunca se ha mostrado antes (recuento de referencias 0), lo que equivale a ocultar la memoria inyectada en el modelo.
                        if (state.compiledMarkdown.isNotBlank()) {
                            item(key = "memory_ai_understanding") {
                                MemorySummaryCard(markdown = state.compiledMarkdown)
                            }
                        }
                        // Biblioteca de experiencias:CRUD + Se han escrito todos los dos componentes de la tarjeta, pero no se han conectado.
                        // Como resultado, la experiencia solo puede ser escrita por IA y los usuarios no pueden leerla ni cambiarla.
                        item(key = "memory_experience_library") {
                            ExperienceLibraryCard(
                                items = state.experienceItems,
                                onAdd = { showAddExperience = true },
                                onEdit = { experienceTarget = it },
                                onDelete = { deleteTarget = it },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearGroupChatConfirm) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.memory_group_chat_clear_confirm_title),
            itemName = stringResource(R.string.memory_group_chat_clear_item_name),
            consequence = stringResource(R.string.memory_group_chat_clear_consequence),
            onConfirm = {
                showClearGroupChatConfirm = false
                viewModel.clearAllGroupChatMemories()
            },
            onDismiss = { showClearGroupChatConfirm = false },
        )
    }

    editItem?.let { item ->
        FactEditDialog(
            title = stringResource(
                if (item.source == "Summary") {
                    R.string.memory_screen_edit_summary
                } else {
                    R.string.memory_screen_edit_fact
                },
            ),
            initialContent = item.content,
            // Las insignias de la lista solo se clasifican junto al motivo principal.,Después de hacer clic en él, explique el estado del hit uno por uno.
            statusNote = memoryStatusNote(item),
            onDismiss = { editItem = null },
            onConfirm = { content, onSaved ->
                routeMemoryItemAction(
                    item = item,
                    onFact = { factId, scope -> viewModel.editFact(factId, content, scope, onSaved) },
                    onSummary = { sessionId -> viewModel.editSummary(sessionId, content, onSaved) },
                    // v2.6.7: Compilar producto (facts/today/week/longterm) ahora es editable
                    onCompile = { sectionKey ->
                        viewModel.editCompile(sectionKey, content)
                        onSaved(true)
                    },
                    // v2.6.7: Ventana emergente dedicada para entradas de experiencias (el título debe cambiarse)/Clasificación/etiquetas, no sólo contenido),
                    // Aquí está la conclusión: la entrada a la ventana emergente de edición la maneja el propio ExperienceLibraryCard.
                    onExperience = { onSaved(false) },
                    onUnsupported = { onSaved(false) },
                )
            },
        )
    }
    if (showAddFact) {
        AddFactDialog(
            onDismiss = { showAddFact = false },
            onConfirm = viewModel::addFact,
        )
    }
    // v2.6.7: Nueva biblioteca de experiencias agregada/Editar.ExperienceEditDialog Ya escribí pero nunca llamé,
    // Continuando aquí. tiene titulo/Clasificación/La etiqueta tiene tres campos, por lo que FactEditDialog (solo contenido) no se puede reutilizar.
    if (showAddExperience) {
        ExperienceEditDialog(
            title = stringResource(R.string.memory_screen_experience_add_dialog_title),
            initialTitle = "",
            initialContent = "",
            initialCategory = "",
            initialTags = "",
            onDismiss = { showAddExperience = false },
            onConfirm = { newTitle, content, category, tags ->
                viewModel.addExperience(newTitle, content, category, tags)
                showAddExperience = false
            },
        )
    }
    experienceTarget?.let { item ->
        ExperienceEditDialog(
            title = stringResource(R.string.memory_screen_experience_edit_title),
            initialTitle = item.title,
            initialContent = item.content,
            initialCategory = item.category.orEmpty(),
            initialTags = item.tags.joinToString(", "),
            onDismiss = { experienceTarget = null },
            onConfirm = { newTitle, content, category, tags ->
                viewModel.editExperience(item.id, newTitle, content, category, tags)
                experienceTarget = null
            },
        )
    }
    // v2.6.7: Confirmación de eliminación de un solo elemento de la memoria del chat grupal
    groupChatMemoryTarget?.let { mem ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.memory_group_chat_delete_confirm_title),
            itemName = mem.groupChatName,
            consequence = stringResource(R.string.memory_group_chat_delete_consequence),
            onConfirm = {
                groupChatMemoryTarget = null
                viewModel.deleteGroupChatMemory(mem.id)
            },
            onDismiss = { groupChatMemoryTarget = null },
        )
    }
    if (showArchiveRecoveryConfirm) {
        MuseDialog(
            onDismissRequest = { if (!archiveRecoveryRunning) showArchiveRecoveryConfirm = false },
            title = stringResource(R.string.memory_archive_recovery_confirm_title),
            content = {
                Text(stringResource(R.string.memory_archive_recovery_confirm))
            },
            confirmText = stringResource(R.string.memory_archive_recovery_action),
            onConfirm = {
                showArchiveRecoveryConfirm = false
                archiveRecoveryRunning = true
                archiveRecoveryScope.launch {
                    val result = archiveRecovery.restoreLatestIfEmpty()
                    result.onSuccess { report ->
                        archiveInfo = null
                        viewModel.loadAll(silent = true)
                        MuseToast.show(
                            context.getString(
                                R.string.memory_archive_recovery_done,
                                report.totalRows,
                            ),
                        )
                    }.onFailure { error ->
                        MuseToast.show(
                            context.getString(
                                R.string.memory_archive_recovery_failed,
                                error.message ?: "",
                            ),
                        )
                    }
                    archiveRecoveryRunning = false
                }
            },
            dismissText = stringResource(R.string.memory_screen_cancel),
            onDismiss = { if (!archiveRecoveryRunning) showArchiveRecoveryConfirm = false },
        )
    }
    // F-10: Selección de importancia(0=ordinario, 1=importante, 2=clave — Los hechos clave nunca decaen)
    importanceItem?.let { item ->
        ImportanceSelectDialog(
            currentImportance = item.importance,
            onDismiss = { importanceItem = null },
            onSelect = { importance ->
                viewModel.setFactImportance(item.id, importance, item.scope)
                importanceItem = null
            },
        )
    }
    // U-3: Confirmar dos veces antes de eliminar,Eliminar la memoria de destino después de la confirmación(Sólo se activa cuando se selecciona el objetivo)
    // MEM-04: Cambiar a un sitio unificado para todo el sitio ConfirmDeleteDialog(Nombra el contenido de la memoria + explica las consecuencias + clave primaria destructiva),
    // El conjunto de palabras Memory_delete_confirm_message ya no se mantiene por separado.
    deleteTarget?.let { item ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.memory_delete_confirm_title),
            itemName = item.title.ifBlank { item.content },
            consequence = stringResource(R.string.memory_delete_consequence),
            onConfirm = {
                routeMemoryItemAction(
                    item = item,
                    onFact = viewModel::deleteFact,
                    onSummary = viewModel::deleteSummary,
                    // v2.6.7: Borrar el producto compilado de esta sección.
                    onCompile = { sectionKey -> viewModel.deleteCompile(sectionKey) },
                    // v2.6.7: Eliminar entrada de experiencia
                    onExperience = { id -> viewModel.deleteExperience(id) },
                    onUnsupported = {},
                )
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
    // Pulsación larga de la tarjeta de línea de tiempo: panel → Segunda confirmación → Siga el mismo conjunto de asignaciones de eliminación basadas en entradas subyacentes.
    // Cancelar (panel o cuadro de confirmación) solo cierra la capa emergente y deja la lista intacta.
    timelineActionTarget?.let { target ->
        TimelineItemActions(
            target = target,
            onSelectMultiple = if (target.source == "Fact") {
                { routable ->
                    timelineActionTarget = null
                    selectionMode = true
                    toggleFactSelection(routable)
                }
            } else {
                null
            },
            onDelete = { routable ->
                routeMemoryItemAction(
                    item = routable,
                    onFact = viewModel::deleteFact,
                    onSummary = viewModel::deleteSummary,
                    onCompile = { sectionKey -> viewModel.deleteCompile(sectionKey) },
                    onExperience = { id -> viewModel.deleteExperience(id) },
                    onUnsupported = {},
                )
                timelineActionTarget = null
            },
            onDismiss = { timelineActionTarget = null },
        )
    }
    // Eliminación por lotes de selección múltiple: confirme la cantidad de elementos antes de colocarlos en la base de datos. Cancelar paradas en el estado de selección múltiple. Después de cambiar la selección, puede hacer clic en eliminar.
    pendingBulkDelete?.let { targets ->
        MemoryBulkDeleteDialog(
            count = targets.size,
            onConfirm = {
                pendingBulkDelete = null
                viewModel.deleteFacts(targets)
                exitSelection()
            },
            onDismiss = { pendingBulkDelete = null },
        )
    }
    // Fusión de selección múltiple: aparecerá una vista previa si el borrador no está vacío. Cancele solo borrar el borrador (deténgase en el estado de selección múltiple) y salga de la selección múltiple después de confirmar el éxito.
    mergeDraft?.let { draft ->
        MemoryMergeDialog(
            draft = draft,
            running = mergeRunning,
            onCancel = {
                mergeConfirmPending = false
                viewModel.cancelMerge()
            },
            onConfirm = { edited ->
                mergeConfirmPending = true
                viewModel.confirmMerge(edited)
            },
        )
    }
    if (showFilter) {
        MuseBottomSheet(onDismissRequest = { showFilter = false }) {
            Column(
                // UI-FIX(verdugón): El panel en sí ya tiene márgenes de pantalla, y agregar 20 dp aquí dará como resultado una doble sangría.
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.memory_center_filter_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                // U-11: "Alcance/espacio de memoria"Filtro por técnico,Ingresos generales ampliables"Filtrado avanzado"área de plegado(Contraer por defecto)
                var advancedFilterExpanded by remember { mutableStateOf(false) }
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { advancedFilterExpanded = !advancedFilterExpanded }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.memory_center_filter_advanced),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        // LAYOUT-01: El título plegado que se haya ampliado se agregará al límite de líneas. Cuando el ancho restante es extremadamente pequeño, ya no se ajustará palabra por palabra.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (advancedFilterExpanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                        contentDescription = stringResource(R.string.memory_center_filter_advanced),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                AnimatedVisibility(
                    visible = advancedFilterExpanded,
                    // v2.x: ficha de complemento spec(estaba por defecto spec)
                    enter =
                    fadeIn(animationSpec = MuseMotion.tween(MuseAnimation.FAST_NORMAL_MS)) +
                        expandVertically(
                            animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS),
                        ),
                    exit =
                    fadeOut(animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS)) +
                        shrinkVertically(
                            animationSpec = MuseMotion.tween(MuseAnimation.FAST_NORMAL_MS),
                        ),
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.memory_center_filter_scope),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        scopes.forEach { option ->
                            MemoryFilterRow(
                                label = option.displayName,
                                selected = option.id == selectedScope,
                                onClick = {
                                    viewModel.selectScope(option.id)
                                    showFilter = false
                                },
                            )
                        }
                        Text(
                            text = stringResource(R.string.memory_center_filter_space),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        spaces.forEach { space ->
                            MemoryFilterRow(
                                label = space.name,
                                selected = space.id == selectedSpace,
                                onClick = {
                                    viewModel.selectSpace(space.id)
                                    showFilter = false
                                },
                            )
                        }
                    }
                }
                // F-7: Filtro de importancia
                Text(
                    text = stringResource(R.string.memory_center_filter_importance),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                MemoryFilterRow(
                    label = stringResource(R.string.memory_center_filter_importance_all),
                    selected = importanceFilter == null,
                    onClick = {
                        viewModel.selectImportanceFilter(null)
                        showFilter = false
                    },
                )
                listOf(
                    0 to R.string.memory_importance_normal,
                    1 to R.string.memory_importance_important,
                    2 to R.string.memory_importance_critical,
                ).forEach {
                        (level, labelRes) ->
                    MemoryFilterRow(
                        label = stringResource(labelRes),
                        selected = importanceFilter == level,
                        onClick = {
                            viewModel.selectImportanceFilter(level)
                            showFilter = false
                        },
                    )
                }
                // F-7: Filtro de rango de tiempo
                Text(
                    text = stringResource(R.string.memory_center_filter_time),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                val timeOptions =
                    listOf(
                        null to R.string.memory_center_filter_time_all,
                        MemoryTimeRange.LAST_7 to R.string.memory_center_filter_time_7,
                        MemoryTimeRange.LAST_30 to R.string.memory_center_filter_time_30,
                        MemoryTimeRange.LAST_90 to R.string.memory_center_filter_time_90,
                    )
                timeOptions.forEach { (range, labelRes) ->
                    MemoryFilterRow(
                        label = stringResource(labelRes),
                        selected = (timeRangeFilter == null && range == null) || (timeRangeFilter != null && timeRangeFilter == range),
                        onClick = {
                            viewModel.selectTimeRangeFilter(range)
                            showFilter = false
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun MemoryCapsuleTabs(selected: Int, onSelect: (Int) -> Unit) {
    // P1-3: Restaurar la pestaña de constelación de memoria (gráfico de constelación real, cargado a través de MemoryGraphViewModel)
    // v2.6.7: Se agregó "Memoria de chat grupal"tab.loadGroupChatMemories / deleteGroupChatMemory /
    // clearAllGroupChatMemories y state.groupChatMemories se han implementado durante mucho tiempo (está escrito en los comentarios
    // "Expuesto en el Centro de la Memoria‘chat grupal’Tab”), pero nunca construyó esto Tab —— La memoria del chat grupal se guarda pero es invisible.
    val tabs =
        listOf(
            stringResource(R.string.memory_center_tab_stream),
            stringResource(R.string.memory_tab_facts),
            stringResource(R.string.memory_center_tab_constellation),
            stringResource(R.string.memory_center_tab_group_chat),
        )
    io.zer0.muse.ui.common.form.MuseCapsuleTab(
        tabs = tabs,
        selectedIndex = selected,
        onSelect = onSelect,
        modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = 12.dp),
    )
}

@Composable
private fun MemoryOverviewCard(
    factCount: Int,
    organizing: Boolean,
    stage: String?,
    scopeLabel: String,
    spaceLabel: String,
    onOrganize: () -> Unit,
    onOpenFilter: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // LAYOUT-01: El logo digital solo se limita a líneas y no ocupa ningún ancho (nuestro banco tiene Spacer(weight(1f)),
                // Agregar peso al número dividirá el espacio en partes iguales y exprimirá la entrada del filtro a la derecha)
                Text(
                    text = factCount.toString(),
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                // LAYOUT-01: El texto de descripción ocupa el ancho restante. — con final de línea Spacer(weight(1f)) Asigne el espacio restante,
                // Visualmente, todavía se ciñe a los números y la entrada del filtro todavía está en el extremo derecho; cuando se amplía el tamaño de la fuente, ya no se ajusta palabra por palabra.
                Text(
                    text = stringResource(R.string.memory_center_fact_count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    modifier = Modifier.clip(MuseShapes.medium).clickable(onClick = onOpenFilter),
                    shape = MuseShapes.medium,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.memory_center_scope_line, scopeLabel, spaceLabel),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = MuseIcons.filter,
                            contentDescription = stringResource(R.string.memory_center_filter_title),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            MuseCapsuleButton(
                text =
                when (stage) {
                    "prepare" -> stringResource(R.string.memory_organize_stage_prepare)
                    "compile" -> stringResource(R.string.memory_organize_stage_compile)
                    "dedup" -> stringResource(R.string.memory_organize_stage_dedup)
                    else -> stringResource(R.string.memory_organize_action)
                },
                onClick = onOrganize,
                enabled = !organizing,
                loading = organizing,
                variant = IosCapsuleButtonVariant.Secondary,
                leadingIcon = MuseIcons.refresh,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MemoryArchiveRecoveryCard(info: MemoryDbArchiveRecovery.ArchiveInfo, running: Boolean, onRestore: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen),
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.memory_archive_recovery_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = stringResource(
                    R.string.memory_archive_recovery_message,
                    info.sessionSummaries,
                    info.compiledSections + info.scopedCompiledSections,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            MuseCapsuleButton(
                text = stringResource(R.string.memory_archive_recovery_action),
                onClick = onRestore,
                enabled = !running,
                loading = running,
                variant = IosCapsuleButtonVariant.Secondary,
                leadingIcon = MuseIcons.refresh,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// F-10/F-6 La transmisión transparente de devolución de llamada aumenta los parámetros:Estructura intrínseca del asistente de nivel de pantalla,Consistente con la convención de ayuda existente de ListScope
@Suppress("LongParameterList")
private fun LazyListScope.memoryStreamItems(
    state: MemoryUiState,
    scopes: List<ScopeOption>,
    spaces: List<MemorySpaceEntity>,
    timelineMode: Boolean,
    timelineFilter: String,
    onTimelineFilter: (String) -> Unit,
    onToggleTimeline: () -> Unit,
    onOpenFacts: () -> Unit,
    onOpenSession: (String) -> Unit,
    onHistory: (MemoryItem) -> Unit,
    onEdit: (MemoryItem) -> Unit,
    onDelete: (MemoryItem) -> Unit,
    onPin: (MemoryItem) -> Unit,
    onImportance: (MemoryItem) -> Unit,
    onTimelineActions: (TimelineItem) -> Unit,
    selectionMode: Boolean,
    isSelected: (MemoryItem) -> Boolean,
    onLongPressItem: (MemoryItem) -> Unit,
    onToggleSelect: (MemoryItem) -> Unit,
) {
    val items = buildMemoryStreamItems(state.factItems, state.summaryItems)
    // P1-3: Cableado del área superior — Visualización enfocada de hechos fijados,Se puede cancelar con un clic
    val pinned = state.factItems.filter { it.pinnedAt != null }
    if (pinned.isNotEmpty()) {
        item(key = "memory_stream_pinned") {
            PinnedMemorySection(
                pinnedEntries =
                pinned.map { item ->
                    io.zer0.memory.pin.PinnedMemoryStore.PinnedEntry(
                        id = item.id,
                        content = item.content,
                        createdAt = item.createdAt ?: item.time.orEmpty(),
                        updatedAt = item.pinnedAt ?: item.createdAt ?: item.time.orEmpty(),
                    )
                },
                onRemove = { id -> items.firstOrNull { it.id == id }?.let(onPin) },
            )
        }
    }
    // MEM-01 (D2): flujo de memoria = Línea de tiempo agregada por tiempo de ocurrencia — Descripción superior y semántica del tiempo.
    item(key = "memory_stream_subtitle") {
        Text(
            text = stringResource(R.string.memory_center_stream_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = 2.dp),
        )
    }
    if (state.isLoading) {
        item(key = "memory_stream_loading") {
            Box(Modifier.fillMaxWidth().heightIn(min = 320.dp), contentAlignment = Alignment.Center) {
                io.zer0.muse.ui.common.state.MuseLoadingState()
            }
        }
        return
    }
    if (items.isEmpty()) {
        item(key = "memory_stream_empty") {
            Box(Modifier.fillMaxWidth().heightIn(min = 320.dp), contentAlignment = Alignment.Center) {
                // ST-03: unidad del estado vacío MuseEmptyState
                MuseEmptyState(
                    title = stringResource(R.string.memory_center_empty_title),
                    subtitle = stringResource(R.string.memory_center_empty_subtitle),
                    actionText = stringResource(R.string.memory_center_open_facts),
                    onAction = onOpenFacts,
                )
            }
        }
        return
    }
    // P1-3: Modo de línea de tiempo — Vista de línea de tiempo agrupada por mes.
    // v2.1.x Arreglar "Bloqueo al hacer clic en la línea de tiempo": El contenido se proyecta plano en la capa exterior. LazyColumn(No más capas internas anidadas LazyColumn,
    // evitar "infinity maximum height constraints" colapsar); Los elementos filtrantes son transportados entonces por el estado exterior.
    if (timelineMode) {
        memoryStreamTimeline(
            items = items,
            timelineFilter = timelineFilter,
            onTimelineFilter = onTimelineFilter,
            onToggleTimeline = onToggleTimeline,
            onOpenActions = onTimelineActions,
            selection =
            MemorySelection(
                active = selectionMode,
                isSelected = isSelected,
                onLongPress = onLongPressItem,
                onToggle = onToggleSelect,
            ),
        )
        return
    }
    // modo lista(Predeterminado):Agrupar por fecha de ocurrencia,Insertar título de la sección de fecha,Hacer perceptible la semántica de la “línea de tiempo”
    item(key = "memory_stream_list_toggle") {
        TimelineModeToggleRow(timelineMode = false, onToggle = onToggleTimeline)
    }
    items.groupBy { (it.createdAt ?: it.time.orEmpty()).take(10) }.forEach { (day, dayItems) ->
        item(key = "stream_day_$day") {
            Text(
                text = day,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = 4.dp),
            )
        }
        // Reparación de auditoría: Igual que arriba — Las identificaciones se repetirán en todos los fragmentos.,key Alcance del complemento.
        items(dayItems, key = { "stream_${it.source}_${it.scope.orEmpty()}_${it.id}" }) { item ->
            // v2.x: Finalización del efecto de movimiento — Entrada de entrada de lista de flujo de memoria/transición de desplazamiento
            Box(modifier = museAnimateItem().padding(horizontal = MusePaddings.screen)) {
                val sourceMeta = if (item.source == "Summary") {
                    val assistantName = scopes.firstOrNull { it.id == item.scope }?.displayName
                        ?: item.scope.orEmpty()
                    val spaceName = spaces.firstOrNull { it.id == item.spaceId }?.name
                        ?: item.spaceId.orEmpty()
                    stringResource(R.string.memory_conversation_meta, assistantName, spaceName)
                } else {
                    null
                }
                MemoryFactRow(
                    item = item,
                    sourceMeta = sourceMeta,
                    onOpenSession = if (item.source == "Summary") onOpenSession else null,
                    onEdit = { onEdit(item) },
                    onDelete = { onDelete(item) },
                    onHistory = if (item.source == "Fact") ({ onHistory(item) }) else null,
                    onPin = if (item.source == "Fact") ({ onPin(item) }) else null,
                    onImportance = if (item.source == "Fact") ({ onImportance(item) }) else null,
                    // Solo se puede eliminar el hecho, por lo que solo se puede ingresar el hecho en selecciones múltiples.
                    selectionMode = selectionMode,
                    isSelected = isSelected(item),
                    onLongPress = if (item.source == "Fact") ({ onLongPressItem(item) }) else null,
                    onToggleSelect = if (item.source == "Fact") ({ onToggleSelect(item) }) else null,
                )
            }
        }
    }
}

/**
 * v2.6.7: Lista de memorias de chat grupal.
 *
 * ViewModel adentro loadGroupChatMemories / deleteGroupChatMemory /
 * clearAllGroupChatMemories y state.groupChatMemories se han implementado desde hace mucho tiempo,
 * Pero nunca se ha colocado en la entrada. —— Si se memoriza algo en un chat grupal, los usuarios no pueden verlo ni borrarlo.
 */
private fun LazyListScope.groupChatMemoryItems(
    memories: List<GroupChatMemoryUiItem>,
    isLoading: Boolean,
    onDelete: (GroupChatMemoryUiItem) -> Unit,
    onClearAll: () -> Unit,
) {
    if (isLoading && memories.isEmpty()) {
        item(key = "group_chat_memory_loading") {
            Box(Modifier.fillMaxWidth().heightIn(min = 240.dp), contentAlignment = Alignment.Center) {
                io.zer0.muse.ui.common.state.MuseLoadingState()
            }
        }
        return
    }
    if (memories.isEmpty()) {
        item(key = "group_chat_memory_empty") {
            Box(Modifier.fillMaxWidth().heightIn(min = 240.dp), contentAlignment = Alignment.Center) {
                MuseEmptyState(
                    title = stringResource(R.string.memory_group_chat_empty_title),
                    subtitle = stringResource(R.string.memory_group_chat_empty_subtitle),
                )
            }
        }
        return
    }
    item(key = "group_chat_memory_header") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.memory_group_chat_count, memories.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Borrar todo: acciones destructivas, doble confirmación
            MuseCapsuleButton(
                text = stringResource(R.string.memory_group_chat_clear_all),
                onClick = onClearAll,
                variant = IosCapsuleButtonVariant.Secondary,
                fillWidth = false,
            )
        }
    }
    items(memories, key = { "gc_mem_${it.id}" }) { mem ->
        Box(
            modifier = museAnimateItem().padding(horizontal = MusePaddings.screen, vertical = 4.dp),
        ) {
            io.zer0.muse.ui.common.surface.MuseSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = MuseShapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            ) {
                Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mem.groupChatName,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(
                                    R.string.memory_group_chat_meta,
                                    mem.assistantName,
                                    mem.timeText,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        MuseTactileButton(
                            icon = MuseIcons.trash,
                            onClick = { onDelete(mem) },
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        text = mem.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/** P1-3: Línea de conmutación de \"lista/línea de tiempo\" del flujo de memoria. */
@Composable
private fun TimelineModeToggleRow(timelineMode: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            false to stringResource(R.string.memory_stream_mode_list),
            true to stringResource(R.string.memory_stream_mode_timeline),
        ).forEach { (mode, label) ->
            io.zer0.muse.ui.common.form.MuseChip(
                selected = timelineMode == mode,
                onClick = { if (timelineMode != mode) onToggle() },
                label = label,
            )
        }
    }
}

@Suppress("LongParameterList")
private fun LazyListScope.memoryFactsItems(
    state: MemoryUiState,
    query: String,
    onQuery: (String) -> Unit,
    onAdd: () -> Unit,
    onHistory: (MemoryItem) -> Unit,
    onEdit: (MemoryItem) -> Unit,
    onDelete: (MemoryItem) -> Unit,
    onPin: (MemoryItem) -> Unit,
    onImportance: (MemoryItem) -> Unit,
    onDismissContradiction: (io.zer0.memory.reflection.MemoryContradictionStore.ContradictionPair) -> Unit,
    selectionMode: Boolean,
    isSelected: (MemoryItem) -> Boolean,
    onLongPressItem: (MemoryItem) -> Unit,
    onToggleSelect: (MemoryItem) -> Unit,
) {
    val items = if (query.isBlank()) state.factItems else state.searchResults
    item(key = "memory_fact_search") {
        MemorySearchBar(
            query = query,
            onQueryChange = onQuery,
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = 4.dp),
        )
    }
    // P2-32: lista de recuerdos conflictivos(Reflexión y detección diaria) — Mostrar cuando no esté disponible,Los usuarios confirman par a par/Borrar
    if (state.contradictions.isNotEmpty() && query.isBlank()) {
        item(key = "memory_contradictions") {
            androidx.compose.material3.ElevatedCard(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MusePaddings.screen, vertical = 4.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.memory_contradictions_title),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                    state.contradictions.forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = pair.a,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "↔",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = pair.b,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            MuseTactileButton(
                                icon = MuseIcons.x,
                                onClick = { onDismissContradiction(pair) },
                                contentDescription = stringResource(R.string.memory_contradictions_dismiss_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    item(key = "memory_fact_header") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // LAYOUT-01: El título de la sección ocupa el ancho restante (solo hay un botón nuevo de ancho fijo a la derecha) y los títulos largos ya no se ajustan palabra por palabra.
            Text(
                text = stringResource(R.string.memory_center_library_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            MuseTactileButton(
                icon = MuseIcons.plus,
                onClick = onAdd,
                contentDescription = stringResource(R.string.memory_screen_add_fact_cd),
            )
        }
        // MEM-01 (D2): Descripción semántica de la biblioteca de elementos — Diferenciado del "flujo de memoria" por agregación de tiempo
        Text(
            text = stringResource(R.string.memory_center_facts_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // UI-FIX(verdugón): El título anterior utiliza márgenes de pantalla, pero el subtítulo no sigue. El texto va directamente al borde izquierdo de la pantalla.
            modifier = Modifier.padding(horizontal = MusePaddings.screen),
        )
    }
    if (state.isLoading) {
        item(key = "memory_facts_loading") {
            Box(Modifier.fillMaxWidth().heightIn(min = 320.dp), contentAlignment = Alignment.Center) {
                io.zer0.muse.ui.common.state.MuseLoadingState()
            }
        }
    } else if (items.isEmpty()) {
        item(key = "memory_facts_empty") {
            Box(Modifier.fillMaxWidth().heightIn(min = 320.dp).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text =
                        if (query.isBlank()) {
                            stringResource(R.string.memory_center_empty_subtitle)
                        } else {
                            stringResource(R.string.memory_screen_empty_content)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    MuseCapsuleButton(
                        text = stringResource(R.string.memory_add_fact),
                        onClick = onAdd,
                        variant = IosCapsuleButtonVariant.Secondary,
                        leadingIcon = MuseIcons.plus,
                        fillWidth = false,
                    )
                }
            }
        }
    } else {
        // Reparación de auditoría: Igual que arriba — El fact.id de la biblioteca principal y la biblioteca secundaria en la vista "Todos" colisionarán.,key Se requiere alcance.
        items(items, key = { "lib_${it.scope.orEmpty()}_${it.id}" }) { item ->
            // v2.x: Finalización del efecto de movimiento — Entrada de lista de base de hechos/transición de desplazamiento
            Box(modifier = museAnimateItem().padding(horizontal = MusePaddings.screen)) {
                MemoryFactRow(
                    item = item,
                    onEdit = { onEdit(item) },
                    onDelete = { onDelete(item) },
                    onHistory = { onHistory(item) },
                    onPin = { onPin(item) },
                    onImportance = { onImportance(item) },
                    selectionMode = selectionMode,
                    isSelected = isSelected(item),
                    onLongPress = if (item.source == "Fact") ({ onLongPressItem(item) }) else null,
                    onToggleSelect = if (item.source == "Fact") ({ onToggleSelect(item) }) else null,
                )
            }
        }
    }
}

@Composable
private fun MemoryConstellationTab(scope: String?, spaceId: String, factCount: Int, modifier: Modifier = Modifier) {
    val graphViewModel: MemoryGraphViewModel = koinViewModel()
    val graphState by graphViewModel.state.collectAsStateWithLifecycle()
    // v2.6.2: Analice previamente y reconstruya la copia del mensaje (evite usar LocalContext para obtener el valor del recurso en lambda para activar lint).
    val rebuildStartedText = androidx.compose.ui.res.stringResource(R.string.memory_graph_rebuild_started)
    val settings = org.koin.compose.koinInject<io.zer0.muse.data.SettingsRepository>()
    val graphConfig by settings.memoryGraphConfigFlow.collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.data.MemoryGraphConfig(),
    )
    var showGraphSettings by remember { mutableStateOf(false) }
    // v2.6.11: Editor de nodos de constelación — Primero obtenga el texto completo y luego abra la ventana. Anteriormente, node.title (truncado a 24 caracteres) se usaba directamente como contenido nuevo que se colocaba en la base de datos.
    // Un clic trunca permanentemente los datos extensos.
    var graphEditTarget by remember { mutableStateOf<Pair<Long, String>?>(null) }
    val ioScope = rememberCoroutineScope()
    LaunchedEffect(scope, spaceId) { graphViewModel.load(scope, spaceId) }
    // v2.6.2: Frecuencia "al abrir la página del horóscopo" —— Agregue un borde cada vez que ingrese (se omitirán los bordes existentes).
    LaunchedEffect(scope, spaceId, graphConfig.edgeBuildFrequency) {
        if (graphConfig.edgeBuildFrequency == io.zer0.muse.data.EdgeBuildFrequency.ON_OPEN) {
            graphViewModel.rebuildEdges(scope, spaceId)
        }
    }
    Column(modifier = modifier.padding(horizontal = MusePaddings.screen, vertical = 4.dp)) {
        // v2.6.2(comentarios de los usuarios): Hay un pequeño botón discreto en el lado derecho de la línea de descripción. Haga clic en la configuración de la constelación (AI profundización + frecuencia del complemento).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.memory_center_constellation_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // v2.6.7: Compensar maxLines —— Hay un botón de ancho fijo en el lado derecho de esta línea. Al ampliar el tamaño de fuente
                // Al texto se le dará un ancho mínimo y se ajustará infinitamente (row-text-width control de acceso).
                maxLines = 3,
                modifier = Modifier.weight(1f).padding(bottom = 8.dp),
            )
            io.zer0.muse.ui.common.form.MuseTactileButton(
                icon = MuseIcons.sliders,
                onClick = { showGraphSettings = true },
                contentDescription = stringResource(R.string.memory_graph_settings_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(32.dp),
                iconSize = 18.dp,
            )
        }
        if (showGraphSettings) {
            MemoryGraphSettingsDialog(
                config = graphConfig,
                onDismiss = { showGraphSettings = false },
                onSave = { updated ->
                    ioScope.launch { settings.saveMemoryGraphConfig(updated) }
                    showGraphSettings = false
                },
                onRebuildNow = {
                    graphViewModel.rebuildEdges(scope, spaceId)
                    showGraphSettings = false
                    io.zer0.muse.ui.common.feedback.MuseToast.show(rebuildStartedText)
                },
            )
        }
        Box(
            modifier =
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(MuseShapes.extraLarge),
        ) {
            // v2.x: Finalización del efecto de movimiento — Cargar/Estado vacío/Cambio de mapa de tres estados. Crossfade(NORMAL_MS, Igual que las especificaciones de ChatScreen).
            // Cambiar espacio de memoria(spaceId)o alcance(scope)Se recarga la constelación del tiempo,También aparecerá y desaparecerá a lo largo de esta transición.
            val constellationState =
                when {
                    graphState.isLoading -> 0
                    graphState.nodes.isEmpty() -> 1
                    else -> 2
                }
            Crossfade(
                targetState = constellationState,
                animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS),
                label = "memoryConstellationContent",
                modifier = Modifier.fillMaxSize(),
            ) { kind ->
                when (kind) {
                    0 ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            io.zer0.muse.ui.common.state.MuseLoadingState()
                        }
                    1 -> {
                        // v2.0: El estado vacío cambia a arriba y centrado. — La altura del contenedor Constellation comienza desde 560 dp,Cuando supera la altura de la primera pantalla.
                        // El punto central vertical desaparecerá de la pantalla.,La copia está cortada en la parte inferior.(La segunda línea de medición real no es visible.).
                        Box(
                            Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 72.dp),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Text(
                                text =
                                stringResource(
                                    if (factCount == 0) {
                                        R.string.memory_center_constellation_empty
                                    } else {
                                        R.string.memory_center_filter_empty
                                    },
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    else -> MemoryGraphView(
                        state = graphState,
                        modifier = Modifier.fillMaxSize(),
                        onNodeAction = { node, action ->
                            when (action) {
                                io.zer0.muse.ui.memory.NodeAction.EDIT ->
                                    // v2.6.11: Primero extraiga el texto completo y luego aparecerá una ventana emergente para editarlo; si no puede conseguirlo (el hecho ya no existe), no aparecerá.
                                    graphViewModel.loadFactContent(node.factId, scope) { full ->
                                        graphEditTarget = full?.let { node.factId to it }
                                    }
                                io.zer0.muse.ui.memory.NodeAction.DELETE -> graphViewModel.deleteNode(node.factId, scope, spaceId)
                                io.zer0.muse.ui.memory.NodeAction.PIN -> graphViewModel.togglePin(node.factId, scope, spaceId)
                                io.zer0.muse.ui.memory.NodeAction.LINK -> Unit
                            }
                        },
                        onManualLink = { src, tgt, type ->
                            graphViewModel.addManualEdge(src.factId, tgt.factId, type, scope, spaceId)
                        },
                    )
                }
            }
        }
    }
    graphEditTarget?.let { (factId, content) ->
        FactEditDialog(
            title = stringResource(R.string.memory_screen_edit_fact),
            initialContent = content,
            onDismiss = { graphEditTarget = null },
            onConfirm = { text, onSaved ->
                graphViewModel.editNode(factId, text, scope, spaceId) { saved -> onSaved(saved) }
            },
        )
    }
}

// F-9: Grupo de texto de operación en línea y transmisión transparente de devolución de llamada:Consistente con las convenciones de filas de listas existentes
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun MemoryFactRow(
    item: MemoryItem,
    sourceMeta: String? = null,
    onOpenSession: ((String) -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onPin: (() -> Unit)? = null,
    onImportance: (() -> Unit)? = null,
    onHistory: (() -> Unit)? = null,
    // v2.6.10: Eliminar varias selecciones — La memoria se arruinará./Recuerde repetidamente, elimine los elementos uno por uno y deslícelos nuevamente a sus posiciones originales repetidamente.
    // Por lo tanto, admite una pulsación prolongada para ingresar múltiples selecciones y borrarlas a la vez.
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onToggleSelect: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onLongPress == null) {
                    Modifier
                } else {
                    Modifier.combinedClickable(
                        onClick = { if (selectionMode) onToggleSelect?.invoke() },
                        onLongClick = onLongPress,
                    )
                },
            ),
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        // v1.0.92: eliminar tonalElevation — En el modo claro, el tono hará que la tarjeta sea completamente gris.,
        // Formado con fondo de página"Sombra inexplicable/Empalme de bloques grises"percepción(Comentarios de la máquina real del usuario);
        // Alinear la orientación existente de CardGroup(Utilice una superficie pura para el fondo y cancele la sombra.).
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (selectionMode) {
                Icon(
                    imageVector = if (isSelected) MuseIcons.circleCheck else MuseIcons.circle,
                    contentDescription = null,
                    tint =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.padding(start = 14.dp, top = 20.dp).size(20.dp),
                )
            }
            Column(
                modifier = Modifier.padding(16.dp).weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            // v2.2.1: Memoria larga, haga clic para expandir — Haga clic en el título/Texto o "Expandir/Contraer" para ver el texto completo
            var expanded by remember(item.id) { mutableStateOf(false) }
            val titleText = item.title.ifBlank { item.content }
            val bodyText = item.content.takeIf { item.title != item.content }
            val canExpand =
                titleText.length > EXPAND_TEXT_THRESHOLD || titleText.contains('\n') ||
                    bodyText?.let { it.length > EXPAND_TEXT_THRESHOLD || it.contains('\n') } == true
            val toggleExpand = { if (canExpand) expanded = !expanded }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                    Modifier
                        .weight(1f)
                        .clickable(enabled = canExpand) { toggleExpand() },
                )
                if (onOpenSession != null && item.sessionId != null) {
                    MuseTactileButton(
                        icon = MuseIcons.chat,
                        onClick = { onOpenSession(item.sessionId) },
                        contentDescription = stringResource(R.string.memory_open_conversation),
                    )
                }
                // LAYOUT-01: Separador decorativo puro — Limite únicamente las líneas para garantizar que nunca se compriman en varias líneas.
                if (item.pinnedAt != null) {
                    Text(
                        "•",
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            bodyText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(enabled = canExpand) { toggleExpand() },
                )
            }
            if (canExpand) {
                Text(
                    text = stringResource(if (expanded) R.string.memory_screen_collapse else R.string.memory_screen_expand),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier =
                    Modifier
                        .clickable { toggleExpand() }
                        .padding(vertical = 2.dp),
                )
            }
            MemoryFactRowFooter(
                item = item,
                sourceMeta = sourceMeta,
                onEdit = onEdit,
                onDelete = onDelete,
                onPin = onPin,
                onImportance = onImportance,
                onHistory = onHistory,
            )
            }
        }
    }
}

/**
 * insignia de estado de memoria —— Fusionado/desaparecido/reemplazado.
 *
 * Los tres estados son datos independientes entre sí.,Un recuerdo puede alcanzar varios objetivos al mismo tiempo. Colocar tres insignias al mismo tiempo en la fila
 * La fila inferior (fuente/Clasificación/tiempo/sesión) lleno,Entonces haga clic en "Reemplazado" > Fusionado > Ordenado por "aparecido":
 * Utilice todo color para el más importante.,El resto todavía está renderizado pero atenuado. —— Tampoco se perderá la verdadera razón.,
 * No permita que una pantalla de insignias robe la atención del texto principal. Las explicaciones completas se enumeran elemento por elemento en la ventana emergente de detalles.
 */
@Composable
private fun MemoryStatusBadges(status: MemoryFactStatus) {
    if (!status.isInactive) return
    val colors = MaterialTheme.colorScheme
    val badges = listOfNotNull(
        if (status.superseded) {
            Triple(stringResource(R.string.memory_status_superseded), colors.errorContainer, colors.onErrorContainer)
        } else {
            null
        },
        if (status.merged) {
            Triple(stringResource(R.string.memory_status_merged), colors.surfaceVariant, colors.onSurfaceVariant)
        } else {
            null
        },
        if (status.suppressed) {
            Triple(stringResource(R.string.memory_status_suppressed), colors.secondaryContainer, colors.onSecondaryContainer)
        } else {
            null
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        badges.forEachIndexed { index, badge ->
            val (label, container, contentColor) = badge
            val muted = index > 0
            Surface(
                shape = MuseShapes.large,
                color = if (muted) container.copy(alpha = MEMORY_STATUS_MUTED_ALPHA) else container,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (muted) contentColor.copy(alpha = MEMORY_STATUS_MUTED_ALPHA) else contentColor,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * Descripción del estado en la ventana emergente de detalles: el mismo conjunto de juicios que la insignia de la lista, haga clic en "Reemplazado" > Fusionado > Ya aparecidos" se enumeran artículo por artículo.
 *
 * Cuando los usuarios hacen clic en un recuerdo identificado, necesitan saber por qué no funciona, en lugar de simplemente ver una palabra.
 */
@Composable
private fun memoryStatusNote(item: MemoryItem): String? {
    val status = item.status
    val lines = listOfNotNull(
        if (status.superseded) stringResource(R.string.memory_status_superseded_detail) else null,
        if (status.merged) stringResource(R.string.memory_status_merged_detail) else null,
        if (status.suppressed) stringResource(R.string.memory_status_suppressed_detail) else null,
    )
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

/**
 * Fila inferior de tarjetas didácticas: Fuente/Clasificación/Menú de operación Hora + "Más".
 *
 * de [MemoryFactRow] Eliminado: El cuerpo principal de la tarjeta sólo es responsable de "¿qué es esta memoria?"
 * Esta línea es responsable de "lo que se puede hacer con ella" y la frecuencia de los cambios entre las dos es diferente.
 */
@Composable
private fun MemoryFactRowFooter(
    item: MemoryItem,
    sourceMeta: String?,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onPin: (() -> Unit)?,
    onImportance: (() -> Unit)?,
    onHistory: (() -> Unit)?,
) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sourceMeta?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Insignia de estado (fusionada/Apareció/reemplazado):Lugar después de la fuente y antes de la clasificación. ——
            // “¿Esto sigue vigente?”que“¿A qué categoría pertenece?”Hay que verlo más.
            MemoryStatusBadges(status = item.status)
            item.category?.takeIf {
                it.isNotBlank()
            }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            item.time?.takeIf {
                it.isNotBlank()
            }?.let { Text(it.take(10), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
            // F-9: Trazabilidad de la sesión de origen
            item.sessionId?.takeIf { it.isNotBlank() }?.let { sid ->
                Text(
                    text = sid.take(MEMORY_FACT_ROW_SESSION_ID_MAX),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            // MEM-06: Opere para ingresar al menú "Más" — No más apretar 4 botones de texto en una fila;El área táctil del elemento del menú se encuentra 48dp
            var showMore by remember { mutableStateOf(false) }
            Box {
                MuseTactileButton(
                    icon = MuseIcons.chevronDown,
                    onClick = { showMore = true },
                    contentDescription = stringResource(R.string.action_more),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = MuseIconSizes.touchTarget,
                    iconSize = MuseIconSizes.iconSmall,
                )
                MuseAnchoredMenu(
                    expanded = showMore,
                    onDismissRequest = { showMore = false },
                    alignEnd = true,
                    minWidth = 196.dp,
                    maxWidth = 240.dp,
                ) {
                    onImportance?.let {
                        MuseActionSheetRow(
                            icon = MuseIcons.star,
                            onClick = {
                                showMore = false
                                it()
                            },
                            text = stringResource(R.string.memory_menu_importance),
                        )
                    }
                    onPin?.let {
                        MuseActionSheetRow(
                            icon = MuseIcons.pin,
                            onClick = {
                                showMore = false
                                it()
                            },
                            text = stringResource(
                                if (item.pinnedAt != null) R.string.memory_menu_unpin else R.string.memory_menu_pin,
                            ),
                        )
                    }
                    onEdit?.let {
                        MuseActionSheetRow(
                            icon = MuseIcons.edit,
                            onClick = {
                                showMore = false
                                it()
                            },
                            text = stringResource(R.string.memory_menu_edit),
                        )
                    }
                    onHistory?.let {
                        MuseActionSheetRow(
                            icon = MuseIcons.history,
                            onClick = {
                                showMore = false
                                it()
                            },
                            text = stringResource(R.string.memory_menu_history),
                        )
                    }
                    onDelete?.let {
                        MuseActionSheetRow(
                            icon = MuseIcons.trash,
                            contentColor = MaterialTheme.colorScheme.error,
                            onClick = {
                                showMore = false
                                it()
                            },
                            text = stringResource(R.string.memory_menu_delete),
                        )
                    }
                }
            }
        }
}

/**
 * Estado de selección múltiple y devoluciones de llamada del flujo de memoria.
 *
 * Empaquetado y paso: Estos cuatro valores siempre entran y salen al mismo tiempo. Dividirlos en cuatro parámetros hará que la firma de cada función de lista sea muy larga.
 */
private class MemorySelection(
    val active: Boolean,
    val isSelected: (MemoryItem) -> Boolean,
    val onLongPress: (MemoryItem) -> Unit,
    val onToggle: (MemoryItem) -> Unit,
)

/**
 * Vista de línea de tiempo del flujo de memoria: agrupada por mes y el grupo se puede filtrar por fuente.
 *
 * de [memoryStreamItems] Sácalo —— Gestiona el área superior, la descripción y la carga al mismo tiempo./Estado vacío y dos vistas,
 * Las ramas están apiladas y es difícil ver "cómo creció este arroyo".
 */
private fun LazyListScope.memoryStreamTimeline(
    items: List<MemoryItem>,
    timelineFilter: String,
    onTimelineFilter: (String) -> Unit,
    onToggleTimeline: () -> Unit,
    onOpenActions: (TimelineItem) -> Unit,
    selection: MemorySelection,
) {
    // El estado de selección múltiple proviene del mismo paquete. Después de tomar el nombre corto, no es necesario que el cuerpo escriba selección.xxx en cada línea.
    val selectionMode = selection.active
    val isSelected = selection.isSelected
    val onToggleSelect = selection.onToggle

    item(key = "memory_stream_timeline_toggle") {
        TimelineModeToggleRow(timelineMode = true, onToggle = onToggleTimeline)
    }
    item(key = "memory_stream_timeline_filters") {
        MemoryTimelineFilterRow(selected = timelineFilter, onSelect = onTimelineFilter)
    }
    val grouped =
        items.groupBy { item ->
            try {
                val instant = Instant.parse(item.createdAt ?: item.time.orEmpty())
                val dt = instant.atZone(ZoneId.systemDefault())
                "${dt.year}-${dt.monthValue.toString().padStart(2, '0')}"
            } catch (_: Exception) {
                "Unknown"
            }
        }.toSortedMap(compareByDescending { it })
    grouped.forEach { (month, monthItems) ->
        val filtered =
            when (timelineFilter) {
                "all" -> monthItems
                "fact" -> monthItems.filter { it.source == "Fact" }
                "summary" -> monthItems.filter { it.source == "Summary" }
                "milestone" -> monthItems.filter { it.source == "Milestone" }
                else -> emptyList()
            }
        if (filtered.isNotEmpty()) {
            item(key = "timeline_month_$month") {
                MonthHeader(month = month)
            }
            // Reparación de auditoría: key Necesito traer source + scope — La vista "Todo" fusiona la biblioteca principal y cada biblioteca secundaria,
            // El fact.id de cada base de datos se incrementará de forma independiente y se repetirá.,Sólo se activará el uso de id como clave LazyColumn
            // "Key was already used" colapsar.
            items(filtered, key = { "timeline_${it.source}_${it.scope.orEmpty()}_${it.id}" }) { item ->
                val timelineItem = TimelineItem(
                    id = "${item.source}_${item.id}",
                    content = item.content,
                    source = item.source,
                    importance = item.importance,
                    createdAt = item.createdAt ?: item.time,
                    tags = item.tags,
                    // La eliminación debe basarse en la identificación subyacente + posicionamiento de la subbiblioteca:El ID del elemento del resumen es sessionId
                    itemId = item.id,
                    sessionId = item.sessionId,
                    scope = item.scope,
                )
                // Una pulsación larga en el modo de selección múltiple aún selecciona;Normalmente presione y mantenga presionado para abrir el panel de acciones.(Eliminar/opción múltiple).
                // La tarjeta de resumen no ingresa a la selección múltiple,No se hace cargo de los gestos en el modo de selección múltiple.
                val cardLongPress: (() -> Unit)? = if (!selectionMode) {
                    { onOpenActions(timelineItem) }
                } else if (item.source == "Fact") {
                    { onToggleSelect(item) }
                } else {
                    null
                }
                // v2.x: Finalización del efecto de movimiento — Animación de entrada unificada de entrada plana de línea de tiempo(Mismas especificaciones que el modo lista)
                Box(museAnimateItem()) {
                    TimelineEventCard(
                        item = timelineItem,
                        selectionMode = selectionMode,
                        isSelected = isSelected(item),
                        onLongPress = cardLongPress,
                        onToggleSelect = if (item.source == "Fact") ({ onToggleSelect(item) }) else null,
                    )
                }
            }
        }
    }

}

// F-9: Duración máxima de la impresión de ID de sesión de origen(Evite que los identificadores largos abrumen el diseño de la fila).
private const val MEMORY_FACT_ROW_SESSION_ID_MAX = 10

/** Cuánto se desvanece la insignia secundaria cuando se alcanzan varios estados en la misma fila(El más fuerte permanece intacto.). */
private const val MEMORY_STATUS_MUTED_ALPHA = 0.55f

/** v2.2.1: entrada de memoria"Ampliable"Umbral de decisión(Número de caracteres);Si excede o contiene saltos de línea, se proporcionará expansión de texto completo. */
private const val EXPAND_TEXT_THRESHOLD = 80

@Composable
private fun MemoryFilterRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(MuseShapes.medium).clickable(onClick = onClick),
        shape = MuseShapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                // LAYOUT-01: El nombre del elemento de filtro se ha ampliado y se ha agregado el límite de líneas para evitar el ajuste palabra por palabra cuando el ancho restante es extremadamente pequeño.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Barra de acciones de selección múltiple: Cancelar/Fusionar/Eliminar.
 *
 * Cuando la fusión está en curso, la entrada de fusión cambia a control giratorio y se desactiva la eliminación: lo más probable es que el usuario haga clic en otro lugar durante los pocos segundos que espera el modelo en la ventana emergente.
 * Una vez que se elimina la línea de origen, si no se puede encontrar el origen cuando se coloca en la biblioteca, solo informará "fallo de fusión" y durante este período, no cambió nada.
 */
@Composable
internal fun MemorySelectionActions(
    selectedCount: Int,
    mergeRunning: Boolean,
    onCancel: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
) {
    MuseTactileButton(
        icon = MuseIcons.x,
        onClick = onCancel,
        contentDescription = stringResource(R.string.action_cancel),
    )
    // Se necesitan unos segundos para esperar a que el modelo genere un borrador: cambie la entrada a una ruleta con 48 dp fijos.
    // Si lo deja gris, no verá ninguna respuesta en unos segundos y los usuarios pensarán que aún no han hecho clic en él.
    if (mergeRunning) {
        val runningDesc = stringResource(R.string.memory_center_merge_running)
        Box(
            modifier = Modifier.size(MuseIconSizes.touchTarget).semantics { contentDescription = runningDesc },
            contentAlignment = Alignment.Center,
        ) {
            MuseSpinner(
                size = MuseIconSizes.icon,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    } else {
        // La combinación requiere al menos dos: cuando es insuficiente, el botón permanece pero se vuelve gris para evitar que la barra de herramientas aparezca y desaparezca con la cantidad de elementos.
        MuseTactileButton(
            icon = MuseIcons.gitMerge,
            onClick = onMerge,
            contentDescription = stringResource(R.string.memory_center_merge_action),
            enabled = selectedCount >= 2,
        )
    }
    MuseTactileButton(
        icon = MuseIcons.trash,
        onClick = onDelete,
        contentDescription = stringResource(R.string.action_delete),
        enabled = !mergeRunning,
        tint = MaterialTheme.colorScheme.error,
    )
}

/**
 * Confirmación secundaria para la eliminación por lotes de selección múltiple.
 *
 * La eliminación por lotes eliminará varias filas,Y no se puede revocar después de haber sido depositado en el almacén.,Entonces, primero publique la cantidad de elementos y luego elimínelos. ——
 * Simplemente haga clic en él y será enviado directamente. deleteFacts,No hay lugar para recuperarse de un toque equivocado.
 */
@Composable
internal fun MemoryBulkDeleteDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDeleteDialog(
        title = stringResource(R.string.memory_bulk_delete_confirm_title),
        itemName = stringResource(R.string.memory_bulk_delete_item_name, count),
        consequence = stringResource(R.string.memory_bulk_delete_consequence, count),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * Ventana emergente de vista previa para combinación de selección múltiple.
 *
 * La mitad superior enumera los textos originales fusionados (solo lectura), para que los usuarios puedan ver claramente "cuáles están fusionados";
 * La mitad inferior es el resultado fusionado editable.——El borrador está escrito como modelo y alguien debe revisar la redacción antes de finalizarlo.
 * El contenido puede ser muy largo sin hacer otra capa de desplazamiento:MuseDialog El área de texto en sí se ha desplazado según la altura visual.
 */
@Composable
internal fun MemoryMergeDialog(
    draft: MemoryMergeDraft,
    running: Boolean,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var edited by remember(draft) { mutableStateOf(draft.mergedText) }
    MuseDialog(
        onDismissRequest = { if (!running) onCancel() },
        title = stringResource(R.string.memory_center_merge_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Text(
                    text = stringResource(R.string.memory_center_merge_sources_title, draft.sources.size),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                draft.sources.forEach { source ->
                    Surface(
                        shape = MuseShapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = source,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(MusePaddings.cardInner),
                        )
                    }
                }
                Spacer(Modifier.height(MusePaddings.labelVerticalGap))
                MuseTextField(
                    value = edited,
                    onValueChange = { edited = it },
                    enabled = !running,
                    label = { Text(stringResource(R.string.memory_center_merge_result_label)) },
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 240.dp),
                )
                if (running) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                    ) {
                        MuseSpinner(
                            size = MuseIconSizes.iconSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.memory_center_merge_running),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.memory_center_merge_confirm),
        // ViewModel rechazará el texto en blanco y aparecerá atenuado de antemano en lugar de no tener respuesta después de hacer clic en él.
        confirmEnabled = edited.isNotBlank() && !running,
        onConfirm = { onConfirm(edited) },
        // No se proporciona ninguna salida durante el período de acceso a la base de datos para evitar que el borrador se haya borrado y la rutina aún se esté escribiendo.
        dismissText = if (running) null else stringResource(R.string.memory_screen_cancel),
        onDismiss = onCancel,
        properties = mergeDialogProperties(running),
    )
}

/**
 * Fusione las propiedades de la ventana emergente de vista previa.
 *
 * No importa si haces clic en el espacio en blanco: si lo tocas accidentalmente mientras estás editando un artículo largo, tirarás el borrador.
 * La clave de retorno está disponible cuando está inactiva y no es válida cuando se elimina la biblioteca.——Utilice el mismo interruptor que el botón cancelar para evitar un comportamiento inconsistente de las dos salidas.
 */
internal fun mergeDialogProperties(running: Boolean): DialogProperties = DialogProperties(
    dismissOnClickOutside = false,
    dismissOnBackPress = !running,
    decorFitsSystemWindows = false,
    usePlatformDefaultWidth = false,
)
