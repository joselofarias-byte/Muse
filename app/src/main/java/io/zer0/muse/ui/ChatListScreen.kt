package io.zer0.muse.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.Logger
import io.zer0.memory.fact.FactDao
import io.zer0.memory.fact.FactEntity
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.session.FolderEntity
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.notification.MuseNotificationTarget
import io.zer0.muse.schedule.DailySummaryService
import io.zer0.muse.schedule.DailySummaryWorker
import io.zer0.muse.schedule.GreetingHintGenerator
import io.zer0.muse.transformer.InternalMarkupSanitizer
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseActionSheetRow
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.horizontalGestureExclusion
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.museAnimateItem
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.MuseDivider
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseCornerRadius
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** v1.x: Saludo Notificación de recordatorio personalizado ID(Escalonado de otros ID de notificación). */
private const val GREETING_NOTIFY_ID = 1010

/**
 * Página del centro de tareas —— Reconstrúyalo en la página de inicio de la tarea de acuerdo con el borrador del diseño.
 *
 * estructura visual:
 *  - Saludo del título superior + subtítulo de cantidad de memoria
 *  - Barra de entrada global:Introduzca cualquier idea,Botón de envío redondo verde a la derecha
 *  - Fijado:tarjeta blanca con esquinas redondeadas,Punto de estado + título + subtítulo + hora + flecha
 *  - carpeta:Icono de carpeta + nombre + cantidad
 *  - Recientemente:Conversación no fijada o mensaje de estado vacío
 *  - base de conocimientos:tarjeta de entrada,Mostrar número de documentos
 *
 * Todas las funciones originales conservadas.:
 *  - Anclar, archivar, carpeta, cambiar nombre, eliminar
 *  - Deslizar para eliminar(Desliza hacia la izquierda) / Archivo(Desliza hacia la derecha)
 *  - Menú de pulsación larga
 */
// Función de composición a nivel de pantalla:Saludos/Barra de entrada/Buscar/fijar hacia arriba/carpeta/Recientemente/Rama multipartición de la base de conocimientos,
// Cada partición ha sido encargada para la extensión LazyListScope(recentSectionItems/searchResultSection espera),
// Las ramas restantes son estructuras específicas de partición.,Comprobaciones de complejidad exentas según el precedente de GenerationHandler.
@Suppress("CyclomaticComplexMethod")
@Composable
fun ChatListScreen(
    sessions: List<SessionEntity>,
    folders: List<FolderEntity>,
    currentSessionId: String?,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    /** v1.0.27: Envíe y cree una nueva sesión directamente desde el cuadro de entrada de la página de tareas. */
    onCreateWithText: (String) -> Unit = {},
    onDelete: (String) -> Unit,
    onRename: (SessionEntity) -> Unit,
    /** v1.48: Cambiar nombre(traer nuevo nombre),Se corrigió un error en la implementación anterior que causaba que fallara el cambio de nombre al pasar session.title. */
    onRenameTo: (SessionEntity, String) -> Unit = { s, _ -> onRename(s) },
    onTogglePinned: (String) -> Unit,
    /** B7-05: Nuevo orden de identificación después de ordenar con arrastrar y soltar las sesiones fijadas(Persistencia tan pronto como lo dejas ir). */
    onReorderPinned: (List<String>) -> Unit = {},
    onMoveSessionToFolder: (String, String?) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onToggleFolderExpanded: (String, Boolean) -> Unit,
    assistants: List<AssistantEntity> = emptyList(),
    currentAssistant: AssistantEntity? = null,
    /** v0.45: Lista de conversaciones archivadas(Retención de datos históricos,La interfaz de usuario actual ya no muestra la entrada del archivo). */
    archivedSessions: List<SessionEntity> = emptyList(),
    /** v0.45: Sesión de archivo(Menú de pulsación larga del elemento de la lista principal para). */
    onArchive: (String) -> Unit = {},
    /** v0.45: Desarchivar(Menú de pulsación larga del elemento de la lista de archivos para su uso). */
    onUnarchive: (String) -> Unit = {},
    onOpenScheduledTasks: () -> Unit = {},
    onOpenQuickNotes: () -> Unit = {},
    /** v1.0.27: Abra la página de traducción rápida. */
    onOpenQuickTranslate: () -> Unit = {},
    /** v1.0.27: Abra la página de la base de conocimientos. */
    onOpenKnowledgeBase: () -> Unit = {},
    /** v2.0: Abra la página eliminada recientemente. */
    onOpenRecentlyDeleted: () -> Unit = {},
    /** Phase 1 WS5: Asistente abierto/Página de gestión de roles(Uso de CTA en estado de vacío emocional). */
    onOpenAssistants: () -> Unit = {},
    /** v2.x: Socios horizontalmente — Abrir un nuevo chat con un asistente designado(assistantId). */
    onOpenAssistantChat: (String) -> Unit = {},
    /** v1.72: Primera bandera de carga de la lista de sesiones(Evite el estado flash) */
    isSessionsLoading: Boolean = false,
    /** v1.0.62: Información de error de carga de la lista de sesiones(null=normales). */
    sessionsError: String? = null,
    /** v1.0.62: Vuelva a intentar la devolución de llamada si falla la carga de la lista de sesiones. */
    onRetryLoadSessions: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** metahechos DAO,Se utiliza para mostrar el número de memoria en la página de inicio. */
    factDao: FactDao = koinInject(),
    /** Documentación de la base de conocimientos DAO,Se utiliza para mostrar la cantidad de documentos en la página de inicio. */
    knowledgeDocDao: KnowledgeDocDao = koinInject(),
    /** v1.x: Saludo Notificación de recordatorio personalizado(una vez al dia) */
    settings: SettingsRepository = koinInject(),
    /** v1.x: administrador de notificaciones */
    notificationManager: MuseNotificationManager = koinInject(),
    /** v1.x: Generador de recordatorios personalizados de saludo(LLM,No volver a la versión de la regla) */
    greetingHintGenerator: GreetingHintGenerator = koinInject(),
    /** v1.x: La compensación de la recepción del hogar reutiliza directamente el servicio de resumen diario,No confíe únicamente en WorkManager para retrasar la ejecución. */
    dailySummaryService: DailySummaryService = koinInject(),
) {
    // I3: Listar límites de error independientes del área,La compilación de datos de representación de la lista de sesiones falló y solo degradó la región
    RegionErrorBoundary(
        regionName = "list",
        data = { sessions },
    ) {
        // v1.69: Agrupación de carpetas UI — Nuevo estado del cuadro de diálogo de carpeta
        var showCreateFolderDialog by remember { mutableStateOf(false) }
        var newFolderName by remember { mutableStateOf("") }

        // Datos de la página de inicio:Número de documentos de la base de conocimientos
        var docCount by remember { mutableStateOf(0) }
        // Memoria reciente para emparejar saludos(alcance principal,Consigue los últimos 100 artículos;Evite que los recuerdos del juego de roles del subasistente se mezclen con recordatorios)
        var greetingFacts by remember { mutableStateOf<List<FactEntity>>(emptyList()) }
        // v1.x: LLM Sufijo de saludo personalizado generado(Almacenamiento en caché por el día,De lo contrario, recurra a la versión de reglas.)
        var greetingHint by remember { mutableStateOf<String?>(null) }
        // v1.x: El resumen diario lo escribe el trabajador. DataStore,Después de la generación, la página de inicio se puede recibir en tiempo real.
        val dailySummary by settings.dailySummaryFlow.collectAsStateWithLifecycle(initialValue = null)
        // v2.6.7 fix (AUDIT F8): El número de tareas pendientes pendientes entre sesiones.
        // Repository Anteriormente, las dos consultas se mostraban completamente sin llamadas, pero ahora se muestran en la página de inicio.
        var openTodoSessionCount by remember { mutableStateOf(0) }
        val context = LocalContext.current
        LaunchedEffect(Unit) {
            // Primer sistema de entrega para guardar dinero,Luego genere directamente el período actual en primer plano.;De lo contrario, algunas ROM WorkManager
            // gran retraso,Los usuarios sólo ven“buenos dias/buen mediodía/buenas noches”No puedes ver el resumen del asistente al final.
            DailySummaryWorker.enqueueCatchUpIfDue(context, settings)
            withContext(Dispatchers.IO) {
                try {
                    dailySummaryService.generateCurrentSlotIfDue()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w("ChatListScreen", "Error al generar el resumen diario de recepción: ${e.message}", e)
                }
            }
        }
        LaunchedEffect(Unit) {
            // Reparación de auditoría (8.8): quitar la capa interna scope.launch — La implementación original de la rutina interna pertenece a rememberCoroutineScope,
            // No cancelado con LaunchedEffect(abandonar el grupo/Sigue funcionando después de pasar la página.),Y el arranque dual no tiene sentido.
            runCatching { docCount = knowledgeDocDao.countUserVisible() }
            // v2.6.7 fix (AUDIT F8): Cuente el número de sesiones inacabadas (libro mayor de sesiones cruzadas).
            runCatching {
                val repo =
                    org.koin.core.context.GlobalContext.get()
                        .get<io.zer0.muse.data.todo.SessionTodoRepository>()
                openTodoSessionCount = repo.countSessionsWithOpenItems()
            }.onFailure { Logger.w("ChatListScreen", "No se pudo leer el recuento de tareas pendientes: ${it.message}") }
            // Primero debe obtener datos locales antes de generar,El estado de redacción recién establecido no se puede leer en la misma rutina.
            val facts = runCatching { factDao.getAll("main").take(100) }.getOrElse {
                Logger.w("ChatListScreen", "No se pudo leer la memoria de saludo: ${it.message}")
                emptyList()
            }
            greetingFacts = facts
            var resolvedGreetingHint: String? = null
            // v1.x: Saludo personalizado — caché primero(ese dia),LLM generado en caso de error,Regrese a la versión de la regla si falla.
            runCatching {
                val today = java.time.LocalDate.now().toString()
                val cached = settings.getGreetingHintCache()
                if (cached?.startsWith("$today|") == true) {
                    resolvedGreetingHint = GreetingHelper.compactGreetingText(
                        cached.substringAfter("|"),
                        GreetingHelper.PERSONALIZED_HINT_MAX_LENGTH,
                    )
                    greetingHint = resolvedGreetingHint
                } else {
                    greetingHintGenerator.generate(facts)?.let { hint ->
                        resolvedGreetingHint = hint
                        greetingHint = hint
                        settings.saveGreetingHintCache("$today|$hint")
                    }
                }
            }.onFailure { e -> Logger.w("ChatListScreen", "Falló la generación del saludo: ${e.message}") }
            // v1.x: Saludo Notificación de recordatorio personalizado — Hay asuntos recientes y caducará sin notificación hoy.,Envíe una notificación para informar al usuario que el asistente le está prestando atención.(Máximo una vez al día).
            runCatching {
                // Las actualizaciones de estado dentro del mismo LaunchedEffect aún no se han redistribuido,Utilice resultados locales para asegurarse de que también se notifique la primera carga.
                val hint = resolvedGreetingHint ?: GreetingHelper.getMemoryHint(facts, res = context.resources)
                if (hint != null) {
                    val today = java.time.LocalDate.now().toString()
                    val lastNotify = settings.getLastGreetingNotifyDate()
                    if (lastNotify != today) {
                        val titles = context.resources.getStringArray(R.array.greeting_notify_titles)
                        notificationManager.notifyReminder(
                            title = titles.random(),
                            message = hint,
                            notificationId = GREETING_NOTIFY_ID,
                            target = MuseNotificationTarget.Home,
                        )
                        settings.saveLastGreetingNotifyDate(today)
                    }
                }
            }.onFailure { e -> Logger.w("ChatListScreen", "Error en la notificación de recordatorio de saludo: ${e.message}") }
        }

        // Si ha habido conversaciones ese día: Se utiliza para eliminar saludos como "Hoy no se ha hablado nada" que no coinciden con los registros reales.
        // DailySummaryService Escribo esta frase cuando no tengo suficiente material, pero no tener suficiente material no significa que no haya diversión.
        val hasSessionToday = remember(sessions) {
            val startOfDay = java.time.LocalDate
                .now()
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
            sessions.any { it.updatedAt >= startOfDay }
        }

        // v0.36 Optimización del rendimiento:Resultados de clasificación en caché,Evite recalcular cada reorganización.
        val displayedSessions by remember(sessions) {
            mutableStateOf(
                sessions
                    .distinctBy { it.id }
                    .sortedWith(
                        compareByDescending<SessionEntity> { it.pinned }.thenByDescending { it.updatedAt },
                    ),
            )
        }
        val pinned = remember(displayedSessions) { displayedSessions.filter { it.pinned } }
        val expandedFolderIds = remember(folders) { folders.filter { it.expanded }.map { it.id }.toSet() }
        val recent = remember(displayedSessions, expandedFolderIds) {
            displayedSessions.filter { !it.pinned && (it.folderId == null || it.folderId !in expandedFolderIds) }
        }
        // B7-05: Orden optimista durante el arrastre,Después de recibir la actualización del flujo de base de datos, se basará automáticamente en sesiones.
        var pinnedOrder by remember(sessions) { mutableStateOf(pinned.map { it.id }) }
        val orderedPinned = remember(pinned, pinnedOrder) {
            val byId = pinned.associateBy { it.id }
            pinnedOrder.mapNotNull { byId[it] } + pinned.filterNot { it.id in pinnedOrder }
        }
        // U-7: Cambio de vista archivada(U-7 El portal de archivo cambia a esta vista para mostrar conversaciones archivadas)
        var showArchived by remember { mutableStateOf(false) }
        // U-22: Cambio de modo de edición de selección múltiple + conjunto de identificación de sesión seleccionada
        var editMode by remember { mutableStateOf(false) }
        var selectedIds by remember { mutableStateOf(setOf<String>()) }

        MusePageScaffold(
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding()
                    .padding(horizontal = MusePaddings.screen),
            ) {
                // v1.72: Se muestra en la primera carga loading,Evite flashear antes de que se emita DB"Aún no hay tareas"Estado vacío
                if (isSessionsLoading) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
                    ) {
                        repeat(5) {
                            io.zer0.muse.ui.common.surface.SessionCardSkeleton()
                        }
                    }
                } else if (sessionsError != null) {
                    MuseErrorStateBox(
                        message = sessionsError,
                        onRetry = onRetryLoadSessions,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                } else if (showArchived) {
                    // U-7: Vista archivada(Con botón de restauración),por fila de herramientas de página de lista"Archivado"entrada entrada
                    ArchivedSessionsList(
                        archived = archivedSessions,
                        onBack = { showArchived = false },
                        onRestore = onUnarchive,
                        onSelect = onSelect,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                } else if (editMode) {
                    // U-22: Modo de edición de selección múltiple(Seleccionar todo/Eliminar/La ejecución del ciclo de archivo reutiliza una única pieza de lógica)
                    ChatListEditMode(
                        sessions = displayedSessions,
                        selectedIds = selectedIds,
                        onToggleSelect = { id ->
                            selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                        },
                        onClearSelection = { selectedIds = emptySet() },
                        onExit = {
                            editMode = false
                            selectedIds = emptySet()
                        },
                        onDeleteSelected = { ids -> ids.forEach(onDelete) },
                        onArchiveSelected = { ids -> ids.forEach(onArchive) },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                } else {
                    // v2.x: Revisar datos de la barra — Conversaciones con mensajes recientes(Haga clic para continuar)
                    val latestRecallSession = remember(sessions) {
                        sessions.maxByOrNull { it.updatedAt }?.takeIf { it.lastMessagePreview.isNotBlank() }
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        // Correcciones en la interfaz (Rendimiento-1): Eliminar global spacedBy,section El espaciado cambió para cada área.
                        // manual del primer elemento padding(Ver TaskSectionTitle/entrada de conocimiento),
                        // De esta manera, las filas de mensajes se pueden colocar en mosaico encima de LazyColumn con 0 espacios entre filas.,
                        // mantener el original"Todo un juego de cartas"apariencia(Los filetes del primer y último elemento los proporciona SecciónGroupRow).
                        contentPadding = PaddingValues(bottom = MusePaddings.listBottomClearance),
                    ) {
                        // título del saludo
                        item(key = "greeting") {
                            GreetingHeader(
                                facts = greetingFacts,
                                personalizedHint = greetingHint,
                                dailySummaryText = dailySummary?.text,
                                dailySummaryDate = dailySummary?.date,
                                openTodoSessionCount = openTodoSessionCount,
                                hasSessionToday = hasSessionToday,
                            )
                        }

                        // v2.x: Socios horizontalmente — Acceso rápido a los avatares de asistente más utilizados(Haga clic para ir directamente a conversaciones recientes)
                        if (assistants.any { it.enabled }) {
                            item(key = "partners_row") {
                                PartnerRow(
                                    assistants = assistants.filter { it.enabled }.sortedBy { it.sortIndex },
                                    onOpenAssistantChat = onOpenAssistantChat,
                                    onOpenAssistants = onOpenAssistants,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }

                        // v2.x: tira de revisión(candidatoA) — "La última vez que charlé con X, "…"",Puede hacer clic en el artículo completo para ir directamente a la última conversación.
                        if (latestRecallSession != null) {
                            item(key = "recall_bar") {
                                RecallBar(
                                    session = latestRecallSession,
                                    assistantName = assistants.firstOrNull { it.id == latestRecallSession.assistantId }?.name,
                                    onSelect = onSelect,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }

                        // Fijado(Título + independiente para cada conversación item,Carga diferida de mosaicos)
                        if (pinned.isNotEmpty()) {
                            pinnedSectionItems(
                                pinned = orderedPinned,
                                folders = folders,
                                onSelect = onSelect,
                                onDelete = onDelete,
                                onRenameTo = onRenameTo,
                                onTogglePinned = onTogglePinned,
                                onReorderPinned = onReorderPinned,
                                onMoveSessionToFolder = onMoveSessionToFolder,
                                onArchive = onArchive,
                            )
                        }

                        // carpeta(Título + independiente para cada carpeta item,Carga diferida de mosaicos)
                        if (folders.isNotEmpty()) {
                            foldersSectionItems(
                                folders = folders,
                                sessions = displayedSessions,
                                onSelect = onSelect,
                                onDelete = onDelete,
                                onRenameTo = onRenameTo,
                                onTogglePinned = onTogglePinned,
                                onMoveSessionToFolder = onMoveSessionToFolder,
                                onArchive = onArchive,
                                onSelectFolder = { folder -> onToggleFolderExpanded(folder.id, !folder.expanded) },
                                onRenameFolder = onRenameFolder,
                                onDeleteFolder = onDeleteFolder,
                            )
                        }

                        // Recientemente(Título + independiente para cada conversación item,Carga diferida de mosaicos)
                        recentSectionItems(
                            recent = recent,
                            folders = folders,
                            onSelect = onSelect,
                            onDelete = onDelete,
                            onRenameTo = onRenameTo,
                            onTogglePinned = onTogglePinned,
                            onMoveSessionToFolder = onMoveSessionToFolder,
                            onArchive = onArchive,
                            onCreate = onCreate,
                        )

                        // base de conocimientos
                        item(key = "section_knowledge") {
                            KnowledgeEntryCard(
                                docCount = docCount,
                                onClick = onOpenKnowledgeBase,
                                modifier = Modifier.padding(top = MusePaddings.sectionGap),
                            )
                        }
                    }
                }
            }
        }

        // v1.69: Diálogo de nueva carpeta
        if (showCreateFolderDialog) {
            MuseDialog(
                onDismissRequest = { showCreateFolderDialog = false },
                title = stringResource(R.string.chat_list_new_folder),
                content = {
                    MuseTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        placeholder = { Text(stringResource(R.string.chat_list_folder_name_placeholder)) },
                        singleLine = true,
                    )
                },
                confirmText = stringResource(R.string.chat_list_create),
                onConfirm = {
                    if (newFolderName.isNotBlank()) {
                        onCreateFolder(newFolderName.trim())
                    }
                    showCreateFolderDialog = false
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { showCreateFolderDialog = false },
            )
        }
    } // I3: Finalización del límite de error del área de lista
}

/** Título de saludo superior + línea de fecha + recordatorio personalizado. */
@Suppress("LongParameterList") // Compose Los parámetros sólo se pueden colocar en mosaico; fusionarlos en objetos hará que la vista previa/La reorganización es peor
@Composable
private fun GreetingHeader(
    facts: List<FactEntity>,
    /** v1.x: LLM Sufijo personalizado generado(Mostrar primero cuando no está vacío,Si no se genera, volverá a la versión de la regla.). */
    personalizedHint: String? = null,
    /** v1.x: Resumen diario del último día.,Priorice la presentación de señales de memoria sobre los eventos. */
    dailySummaryText: String? = null,
    dailySummaryDate: String? = null,
    /** v2.6.7 fix (AUDIT F8): También está el número de sesiones inacabadas (libro mayor de sesiones cruzadas). */
    openTodoSessionCount: Int = 0,
    /** Si ya hay conversaciones ese día; cuando sea verdadero, no se mostrarán saludos vacíos como \"no he conversado hoy\". */
    hasSessionToday: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // I18N-01: Saludos Análisis de recursos(La ruta de prueba aún puede ajustar directamente el respaldo chino de GreetingHelper).
    val resources = LocalContext.current.resources
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
    ) {
        val timeGreeting = GreetingHelper.getTimeGreeting(res = resources)
        // Cuando ya haya conversaciones ese día, elimina palabras vacías como "Aún no hemos hablado de nada":
        // Es una plantilla para cuando no hay suficiente material y contradice directamente la transcripción de la conversación real a continuación.
        val rawSummaryHint = GreetingHelper.getDailySummaryHint(dailySummaryText, dailySummaryDate)
        val dailySummaryHint = if (hasSessionToday && rawSummaryHint != null && looksLikeEmptyDayHint(rawSummaryHint)) {
            null
        } else {
            rawSummaryHint
        }
        val memoryHint = GreetingHelper.getMemoryHint(facts, res = resources)
        Text(
            text = timeGreeting,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        // v2.x: línea de fecha(sustituto"familiaridad"línea de recuento de memoria)——"9mes27dia sabado"
        Text(
            text = GreetingHelper.getDateLine(res = resources),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        // El resumen diario y los recordatorios recientes ocupan cada uno una línea.
        // v2.7(Comentarios de los usuarios): Las líneas de resumen ya no están restringidas/Truncar —— La capa de visualización no realiza compresión secundaria.
        //   Deje que el texto se ajuste naturalmente y fluya hacia abajo (el contenido a continuación se mueve hacia abajo a su vez). La longitud es generada por
        //   (DailySummaryService Control de palabras rápidas y maxTokens, no integrado en la interfaz de usuario.
        val reminderHint = GreetingHelper.compactGreetingText(
            personalizedHint ?: memoryHint,
            GreetingHelper.PERSONALIZED_HINT_MAX_LENGTH,
        )
        // CHAT-16: Copia del saludo de convergencia — El subtítulo ya no repite los saludos de época del título.(Por ejemplo, "Buenas noches, ¿cómo estás hoy?" Reemplázalo con "¿Cómo estás hoy?")
        val dedupedReminderHint = reminderHint?.let { hint ->
            val stripped = hint.removePrefix(timeGreeting).trimStart(',', ',', ' ').trim()
            stripped.ifBlank { null }
        }
        if (!dailySummaryHint.isNullOrBlank()) {
            Text(
                text = dailySummaryHint,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (!dedupedReminderHint.isNullOrBlank() && dedupedReminderHint != dailySummaryHint) {
            Text(
                text = dedupedReminderHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        // v2.6.7 fix (AUDIT F8): Recordatorios de tareas pendientes en todas las sesiones.
        // Muestra solo cuando no haya más recordatorios personalizados urgentes para evitar tener dos filas en la página de inicio.
        val openTodoHint = GreetingHelper.getOpenTodoHint(openTodoSessionCount, res = resources)
        if (!openTodoHint.isNullOrBlank() &&
            dedupedReminderHint.isNullOrBlank() &&
            dailySummaryHint.isNullOrBlank()
        ) {
            Text(
                text = openTodoHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * v2.x: Mi pareja es horizontal — Asistentes de uso común(Estado habilitado)Entrada rápida de avatar.
 *
 * Haz clic en el avatar del asistente. → Abrir un nuevo chat con el asistente(El artículo de revisión es responsable de"Continuar desde la última vez",Esta entrada es responsable de"Abrir un nuevo chat").
 * fin"+"Entrada para agregar socios.
 */
@Composable
private fun PartnerRow(
    assistants: List<AssistantEntity>,
    onOpenAssistantChat: (String) -> Unit,
    onOpenAssistants: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        assistants.forEach { assistant ->
            Column(
                modifier = Modifier
                    .clip(MuseShapes.medium)
                    .clickable { onOpenAssistantChat(assistant.id) }
                    .padding(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // v2.x: Componente de avatar asistente unificado(fotos→Emoji→Reserva de tres niveles de la primera palabra,Consistente con toda la aplicación.)
                io.zer0.muse.ui.common.media.AssistantAvatar(
                    assistant = assistant,
                    avatarSize = 52.dp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = assistant.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 60.dp),
                )
            }
        }
        // fin"+" — Añadir un socio(Abrir asistente de gestión)
        Column(
            modifier = Modifier
                .clip(MuseShapes.medium)
                .clickable { onOpenAssistants() }
                .padding(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MuseIcons.plus,
                    contentDescription = stringResource(R.string.chat_list_add_partner),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = " ",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * v2.x: tira de revisión(candidatoA) — "💬 La última vez que charlé con X, "…"",Puede hacer clic en el artículo completo para ir directamente a la última conversación.
 */
@Composable
private fun RecallBar(session: SessionEntity, assistantName: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val name = assistantName ?: stringResource(R.string.assistant_repo_default_name)
    val preview = remember(session.lastMessagePreview) {
        InternalMarkupSanitizer.stripForDisplay(session.lastMessagePreview)
            .replace(Regex("""\[artifact:[0-9a-fA-F-]{36}\]"""), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
    if (preview.isBlank()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            // v2.x: con"Recientemente"Lista de tarjetas al ras(ancho completo,eliminar los márgenes izquierdo y derecho)
            .clip(MuseShapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onSelect(session.id) }
            .padding(horizontal = MusePaddings.screen, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // v2.3.2: Deshacer los tokens de íconos de vista previa lejos de los íconos de musa + tokens de tamaño(Originalmente emoji + codificado 14.sp)
        Icon(
            imageVector = MuseIcons.chat,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(MuseIconSizes.iconTiny),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            // no más take(18): El corte brusco dividirá en media oración sin puntos suspensivos (Ellipsis No hay posibilidad de activarlo),
            // Asigne a la línea única Ellipsis el ancho real para determinar la posición de truncamiento.
            text = stringResource(R.string.chat_list_recall_format, name, preview),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = MuseIcons.arrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Barra de entrada global:Fondo redondeado claro + texto de marcador de posición + botón de envío redondo verde. */
@Composable
private fun TaskInputBar(onSend: (String) -> Unit, modifier: Modifier = Modifier) {
    // Correcciones en la interfaz (persistencia-3): Cambiar el contenido de entrada rememberSaveable,rotar/Reconstrucción de procesos sin perder borradores
    var text by rememberSaveable { mutableStateOf("") }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            // v1.137 B5: 56dp → 48dp,Reducir la altura del campo de entrada en la página de tareas
            .heightIn(min = 48.dp),
        // CHAT-12: Ingrese a la ficha para caminar unificada de esquina redondeada de la isla(48dp Alto o bajo pill=24dp igual que enorme,Usado explícitamente huge)
        shape = MuseShapes.huge,
        // v2.0: Oscurecer el fondo(Los comentarios de los usuarios son demasiado débiles.)——Reglas de mezcla de colores adaptables unificadas con el cuadro de entrada
        color = androidx.compose.ui.graphics.lerp(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurface,
            0.06f,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                decorationBox = { innerTextField ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.chat_list_input_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            // CHAT-11: Botón enviar unificado — 48dp Toque + círculo de color principal de 36 dp(Igual que el campo de entrada de chat único)
            Box(
                modifier = Modifier
                    .size(MuseIconSizes.touchTarget)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            if (text.isNotBlank()) {
                                onSend(text)
                                text = ""
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(MuseIconSizes.stopButton)
                        .clip(CircleShape)
                        .background(MuseActionColors.container),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MuseIcons.send,
                        contentDescription = stringResource(R.string.chat_list_send),
                        tint = MuseActionColors.content,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                }
            }
        }
    }
}

/**
 * Correcciones en la interfaz (Rendimiento-1): section Título — La parte del título de la TaskSectionCard original se divide en independientes
 * LazyColumn item. La parte superior viene con espacio entre secciones.(Proporcionado originalmente por LazyColumn spacedBy),
 * Haga que el espaciado del primer elemento en cada área sea consistente con el anterior a la transformación.
 */
@Composable
private fun TaskSectionTitle(title: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            Box(
                modifier = Modifier
                    .padding(
                        // Mantenga la misma línea base que el título del grupo CardGroup: alinee los bordes de la tarjeta,
                        // Evite tener dos bordes izquierdos diferentes de "Datos" y "Recientes" en la misma pantalla.
                        start = MusePaddings.screen,
                        top = MusePaddings.sectionGap,
                        bottom = MusePaddings.labelVerticalGap,
                    )
                    .fillMaxWidth(),
            ) {
                title()
            }
        }
    }
}

/**
 * Correcciones en la interfaz (Rendimiento-1): Contenedor de filas de tarjetas agrupadas — La esquina superior del primer elemento, la esquina inferior del último elemento y el ángulo medio derecho,
 * Fondo + simulación de borde fino original"Un juego completo de cartas con esquinas redondeadas."Apariencia. La persona que llama garantiza el espacio cero entre líneas.
 */
@Composable
private fun SectionGroupRow(index: Int, total: Int, content: @Composable ColumnScope.() -> Unit) {
    val shape = when {
        total == 1 -> MuseShapes.extraLarge
        index == 0 -> RoundedCornerShape(
            topStart = MuseCornerRadius.CARD.dp,
            topEnd = MuseCornerRadius.CARD.dp,
        )
        index == total - 1 -> RoundedCornerShape(
            bottomStart = MuseCornerRadius.CARD.dp,
            bottomEnd = MuseCornerRadius.CARD.dp,
        )
        else -> RoundedCornerShape(0.dp)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        content()
    }
}

/**
 * Correcciones en la interfaz (Rendimiento-1): Área de conversación fijada — LazyColumn La capa superior está embaldosada.
 * título solo item;Cada sesión es independiente. items(key=id) item,Representación de carga diferida.
 * Se mantiene el comportamiento de clasificación por arrastre:Desplazamiento local dentro de la fila + devolución de llamada onReorderPinned después de soltarlo.
 */
private fun LazyListScope.pinnedSectionItems(
    pinned: List<SessionEntity>,
    folders: List<FolderEntity>,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRenameTo: (SessionEntity, String) -> Unit,
    onTogglePinned: (String) -> Unit,
    onReorderPinned: (List<String>) -> Unit,
    onMoveSessionToFolder: (String, String?) -> Unit,
    onArchive: (String) -> Unit,
) {
    item(key = "section_pinned_title") {
        TaskSectionTitle { Text(stringResource(R.string.chat_list_section_pinned)) }
    }
    itemsIndexed(pinned, key = { _, session -> "pinned_${session.id}" }) { index, session ->
        SectionGroupRow(index = index, total = pinned.size) {
            PinnedTaskRow(
                session = session,
                index = index,
                pinnedIds = pinned.map { it.id },
                folders = folders,
                onSelect = onSelect,
                onDelete = onDelete,
                onRenameTo = onRenameTo,
                onTogglePinned = onTogglePinned,
                onReorderPinned = onReorderPinned,
                onMoveSessionToFolder = onMoveSessionToFolder,
                onArchive = onArchive,
            )
            if (index != pinned.lastIndex) {
                MuseDivider()
            }
        }
    }
}

/**
 * Correcciones en la interfaz (Rendimiento-1): Línea fijada(Incluye asa de arrastre) — Originales PinnedTasksCard forEachIndexed
 * Cuerpo extraído como componente de fila independiente.,index La semántica es consistente con el original.(posición dentro del grupo),La lógica de arrastrar y soltar permanece intacta.
 */
@Composable
private fun PinnedTaskRow(
    session: SessionEntity,
    index: Int,
    pinnedIds: List<String>,
    folders: List<FolderEntity>,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRenameTo: (SessionEntity, String) -> Unit,
    onTogglePinned: (String) -> Unit,
    onReorderPinned: (List<String>) -> Unit,
    onMoveSessionToFolder: (String, String?) -> Unit,
    onArchive: (String) -> Unit,
) {
    var dragOffset by remember(session.id) { mutableStateOf(0f) }
    var dragging by remember(session.id) { mutableStateOf(false) }
    val rowHeightPx = with(LocalDensity.current) { 80.dp.toPx() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .offset { IntOffset(0, if (dragging) dragOffset.roundToInt() else 0) },
    ) {
        TaskItem(
            session = session,
            folders = folders,
            onSelect = onSelect,
            onDelete = onDelete,
            onRenameTo = onRenameTo,
            onTogglePinned = onTogglePinned,
            onMoveSessionToFolder = onMoveSessionToFolder,
            onArchive = onArchive,
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = MusePaddings.itemGap)
                .size(44.dp)
                .clip(CircleShape)
                .background(if (dragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                .pointerInput(session.id, index, pinnedIds.size) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            dragging = true
                            dragOffset = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffset += dragAmount.y
                        },
                        onDragEnd = {
                            val target = (index + (dragOffset / rowHeightPx).roundToInt())
                                .coerceIn(0, pinnedIds.lastIndex)
                            dragging = false
                            dragOffset = 0f
                            if (target != index) {
                                val ids = pinnedIds.toMutableList()
                                val id = ids.removeAt(index)
                                ids.add(target, id)
                                onReorderPinned(ids)
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            dragOffset = 0f
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MuseIcons.gripVertical,
                contentDescription = stringResource(R.string.chat_list_reorder),
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun LazyListScope.recentSectionItems(
    recent: List<SessionEntity>,
    folders: List<FolderEntity>,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRenameTo: (SessionEntity, String) -> Unit,
    onTogglePinned: (String) -> Unit,
    onMoveSessionToFolder: (String, String?) -> Unit,
    onArchive: (String) -> Unit,
    onCreate: () -> Unit,
) {
    item(key = "section_recent_title") {
        TaskSectionTitle { Text(stringResource(R.string.chat_list_section_recent)) }
    }
    if (recent.isEmpty()) {
        item(key = "section_recent_empty") {
            SectionGroupRow(index = 0, total = 1) {
                EmptyPromptItem(onClick = onCreate)
            }
        }
    } else {
        itemsIndexed(recent, key = { _, session -> "recent_${session.id}" }) { index, session ->
            // E5 (H4): Entrada de elementos de la lista del área de conversación reciente/animación de desplazamiento
            Box(museAnimateItem()) {
                SectionGroupRow(index = index, total = recent.size) {
                    TaskItem(
                        session = session,
                        folders = folders,
                        onSelect = onSelect,
                        onDelete = onDelete,
                        onRenameTo = onRenameTo,
                        onTogglePinned = onTogglePinned,
                        onMoveSessionToFolder = onMoveSessionToFolder,
                        onArchive = onArchive,
                    )
                    if (index != recent.lastIndex) {
                        MuseDivider()
                    }
                }
            }
        }
    }
}

/** Elemento de mensaje de estado vacío(Se muestra cuando no hay datos en la lista reciente). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmptyPromptItem(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = MusePaddings.screen, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskStatusDot(status = TaskStatus.PENDING)
        Spacer(Modifier.width(MusePaddings.screen))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.chat_list_no_recent_title),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.chat_list_no_recent_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(MusePaddings.contentGap))
        Icon(
            imageVector = MuseIcons.arrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** elemento de tarea única:Fila visual + deslizar hacia la izquierda para eliminar + deslizar hacia la derecha para archivar + menú de pulsación larga. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskItem(
    session: SessionEntity,
    folders: List<FolderEntity>,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRenameTo: (SessionEntity, String) -> Unit,
    onTogglePinned: (String) -> Unit,
    onMoveSessionToFolder: (String, String?) -> Unit,
    onArchive: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var showActionSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    // Estos textos se utilizarán en devoluciones de llamada que no sean @Composable.,Anticípese a tiempo para evitar errores de compilación.
    // U-7: Los comentarios de archivo se reemplazan con redacción que contiene mensajes de entrada de recuperación,Evite que el archivado parezca irreversible
    val archivedToast = stringResource(R.string.chat_list_archived_toast)
    val deletedToast = stringResource(R.string.chat_list_deleted_toast)
    val pinToast = stringResource(
        if (session.pinned) R.string.chat_list_unpin else R.string.chat_list_pin,
    )
    // U-7: Los comentarios del menú de archivo también incluyen consejos de recuperación.
    val archiveToast = stringResource(R.string.chat_list_archived_toast)
    val movedToast = stringResource(R.string.chat_list_move_ungrouped)

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onArchive(session.id)
                    MuseToast.show(archivedToast)
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    showDeleteConfirm = true
                    false
                }
                else -> false
            }
        },
    )

    if (showActionSheet) {
        TaskActionSheet(
            session = session,
            folders = folders,
            onDismiss = { showActionSheet = false },
            onTogglePinned = {
                onTogglePinned(session.id)
                MuseToast.show(pinToast)
            },
            onArchive = {
                onArchive(session.id)
                MuseToast.show(archiveToast)
            },
            onDelete = { showDeleteConfirm = true },
            onRename = { showRenameDialog = true },
            onMoveToFolder = { folderId ->
                onMoveSessionToFolder(session.id, folderId)
                MuseToast.show(movedToast)
            },
        )
    }

    if (showDeleteConfirm) {
        MuseDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = stringResource(R.string.chat_list_delete_session_title),
            content = {
                Text(
                    text = stringResource(
                        R.string.chat_list_delete_session_confirm,
                        sessionTitleText(session),
                    ),
                )
            },
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                onDelete(session.id)
                MuseToast.show(deletedToast)
                showDeleteConfirm = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showDeleteConfirm = false },
            destructive = true,
        )
    }

    if (showRenameDialog) {
        var newName by remember { mutableStateOf(session.title) }
        MuseDialog(
            onDismissRequest = { showRenameDialog = false },
            title = stringResource(R.string.chat_list_rename_session_title),
            content = {
                MuseTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                )
            },
            confirmText = stringResource(R.string.chat_list_confirm),
            onConfirm = {
                if (newName.isNotBlank()) {
                    onRenameTo(session, newName.trim())
                }
                showRenameDialog = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showRenameDialog = false },
        )
    }

    SwipeToDismissBox(
        state = dismissState,
        // P2-9(informe de auditoría): Desliza hacia la izquierda para eliminar/Deslizar hacia la derecha para archivar desde el borde de la pantalla será arrebatado por el gesto de retorno del sistema.
        // Aquí, los bordes izquierdo y derecho se declaran como áreas de exclusión para garantizar que las intenciones de deslizamiento horizontal tengan prioridad.
        modifier = modifier.horizontalGestureExclusion(),
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val color = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primaryContainer
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                else -> Color.Transparent
            }
            val icon = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> MuseIcons.archive
                SwipeToDismissBoxValue.EndToStart -> MuseIcons.trash
                else -> null
            }
            val align = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                else -> Alignment.Center
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color),
                contentAlignment = align,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = when (direction) {
                            SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(MaterialTheme.colorScheme.surface)
                .combinedClickable(
                    onClick = { onSelect(session.id) },
                    onLongClick = {
                        MuseHaptics.heavy(haptic)
                        showActionSheet = true
                    },
                )
                .padding(horizontal = MusePaddings.screen, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TaskStatusDot(session = session)
            Spacer(Modifier.width(MusePaddings.screen))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sessionTitleText(session),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val preview = InternalMarkupSanitizer.stripForDisplay(session.lastMessagePreview)
                    // v2.0.1: limpio [artifact:...] marca interna — La vista previa de la lista ya no está expuesta "…[artifact:7b7a65c4-…]"
                    .replace(Regex("""\[artifact:[0-9a-fA-F-]{36}\]"""), "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                // U-21: Vistas previas de sesiones actualizadas en las últimas 24 horas ligeramente resaltadas(negrita + oscuro),Ya no tiene los mismos permisos que el objeto visual de sesión anterior.
                val isRecentlyUpdated = System.currentTimeMillis() - session.updatedAt < TimeUnit.HOURS.toMillis(24)
                Text(
                    text = if (preview.isNotBlank()) preview else formatTaskStatus(session),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = if (isRecentlyUpdated) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (isRecentlyUpdated) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(MusePaddings.contentGap))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // LAYOUT-01: El espacio de metainformación de cola (hora + flecha) está organizado de acuerdo con el ancho del contenido. Aquí solo se limita a líneas y no toma el ancho:
                // Agregar peso a esta fila de texto hará que la fila interior se coma el ancho de la fila exterior, exprimiendo la columna de título izquierda.
                Text(
                    text = formatTime(session.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = MuseIcons.arrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** Mantenga presionado el menú de acciones inferior. */
@Composable
private fun TaskActionSheet(
    session: SessionEntity,
    folders: List<FolderEntity>,
    onDismiss: () -> Unit,
    onTogglePinned: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onMoveToFolder: (String?) -> Unit,
) {
    MuseBottomSheet(
        onDismissRequest = onDismiss,
        // UI-FIX(verdugón): Resulta que el relleno horizontal se presiona a 0 y el título y la tarjeta de acción se empujan directamente al borde de la pantalla.
        horizontalPadding = MusePaddings.screen,
        bottomContentSpacing = 0.dp,
    ) {
        // MuseBottomSheet Responsable uniforme del único desplazamiento vertical; verticalScroll no se puede anidar aquí.
        // De lo contrario, Android Compose medirá a una altura infinita y fallará directamente.
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Título
            Text(
                text = sessionTitleText(session),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            // Anclar/desanclar
            MuseActionSheetRow(
                icon = MuseIcons.pin,
                text = stringResource(
                    if (session.pinned) R.string.chat_list_unpin else R.string.chat_list_pin,
                ),
                onClick = {
                    onTogglePinned()
                    onDismiss()
                },
            )
            // Cambiar nombre
            MuseActionSheetRow(
                icon = MuseIcons.edit,
                text = stringResource(R.string.chat_list_rename),
                onClick = {
                    onRename()
                    onDismiss()
                },
            )
            // Archivo
            MuseActionSheetRow(
                icon = MuseIcons.archive,
                text = stringResource(R.string.chat_list_archive),
                onClick = {
                    onArchive()
                    onDismiss()
                },
            )
            // Eliminar
            MuseActionSheetRow(
                icon = MuseIcons.trash,
                text = stringResource(R.string.action_delete),
                contentColor = MaterialTheme.colorScheme.error,
                onClick = {
                    onDelete()
                    onDismiss()
                },
            )
            // Mover a la carpeta
            if (folders.isNotEmpty() || session.folderId != null) {
                Spacer(Modifier.height(8.dp))
                MuseDivider(startIndent = 0.dp)
                Spacer(Modifier.height(8.dp))
                MuseActionSheetRow(
                    icon = MuseIcons.arrowRight,
                    text = stringResource(R.string.chat_list_move_ungrouped),
                    onClick = {
                        onMoveToFolder(null)
                        onDismiss()
                    },
                )
                folders.forEach { folder ->
                    if (folder.id != session.folderId) {
                        MuseActionSheetRow(
                            icon = MuseIcons.folder,
                            text = stringResource(R.string.chat_list_move_to, folder.name),
                            onClick = {
                                onMoveToFolder(folder.id)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Correcciones en la interfaz (Rendimiento-1): área de carpeta — LazyColumn La capa superior está embaldosada.
 * título solo item;Cada carpeta es independiente. items(key=id) item,Representación de carga diferida.
 */
private fun LazyListScope.foldersSectionItems(
    folders: List<FolderEntity>,
    sessions: List<SessionEntity>,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRenameTo: (SessionEntity, String) -> Unit,
    onTogglePinned: (String) -> Unit,
    onMoveSessionToFolder: (String, String?) -> Unit,
    onArchive: (String) -> Unit,
    onSelectFolder: (FolderEntity) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
) {
    item(key = "section_folders_title") {
        TaskSectionTitle { Text(stringResource(R.string.chat_list_section_folders)) }
    }
    folders.forEach { folder ->
        item(key = "folder_${folder.id}", contentType = "folder") {
            Box(museAnimateItem()) {
                SectionGroupRow(index = 0, total = 1) {
                    FolderItem(
                        folder = folder,
                        onSelectFolder = onSelectFolder,
                        onRenameFolder = onRenameFolder,
                        onDeleteFolder = onDeleteFolder,
                    )
                }
            }
        }
        if (folder.expanded) {
            val folderSessions = sessions.filter { it.folderId == folder.id }
            itemsIndexed(
                folderSessions,
                key = { _, session -> "folder_${folder.id}_session_${session.id}" },
                contentType = { _, _ -> "session" },
            ) { _, session ->
                TaskItem(
                    session = session,
                    folders = folders,
                    onSelect = onSelect,
                    onDelete = onDelete,
                    onRenameTo = onRenameTo,
                    onTogglePinned = onTogglePinned,
                    onMoveSessionToFolder = onMoveSessionToFolder,
                    onArchive = onArchive,
                )
            }
        }
    }
}

/** elemento de carpeta única:Haga clic para ampliar/Contraer + pulsación larga para cambiar el nombre/borrar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderItem(
    folder: FolderEntity,
    onSelectFolder: (FolderEntity) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
) {
    var showFolderSheet by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showFolderSheet) {
        FolderActionSheet(
            folder = folder,
            onDismiss = { showFolderSheet = false },
            onRename = { showRenameDialog = true },
            onDelete = { showDeleteDialog = true },
        )
    }

    if (showRenameDialog) {
        var newName by remember { mutableStateOf(folder.name) }
        MuseDialog(
            onDismissRequest = { showRenameDialog = false },
            title = stringResource(R.string.chat_list_rename_folder_title),
            content = {
                MuseTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                )
            },
            confirmText = stringResource(R.string.chat_list_confirm),
            onConfirm = {
                if (newName.isNotBlank()) {
                    onRenameFolder(folder.id, newName.trim())
                }
                showRenameDialog = false
                showFolderSheet = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showRenameDialog = false },
        )
    }

    if (showDeleteDialog) {
        MuseDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = stringResource(R.string.chat_list_delete_folder_title),
            content = {
                Text(
                    text = stringResource(
                        R.string.chat_list_delete_folder_confirm,
                        folder.name,
                    ),
                )
            },
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                onDeleteFolder(folder.id)
                showDeleteDialog = false
                showFolderSheet = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showDeleteDialog = false },
            destructive = true,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .combinedClickable(
                onClick = { onSelectFolder(folder) },
                onLongClick = { showFolderSheet = true },
            )
            .padding(horizontal = MusePaddings.screen, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MuseIcons.folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(MusePaddings.screen))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(MusePaddings.contentGap))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // LAYOUT-01: El logotipo de recuento de sesiones es solo de fila y no toma ancho (la columna de título de la fila exterior tiene weight(1f),
            // Cualquier ancho más ancho de la ranura de la cola aquí, a su vez, la apretará)
            Text(
                text = folder.sessionCount.toString(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Icon(
                imageVector = MuseIcons.arrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Menú de carpeta con pulsación larga. */
@Composable
private fun FolderActionSheet(folder: FolderEntity, onDismiss: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    MuseBottomSheet(
        onDismissRequest = onDismiss,
        // El menú de pulsación larga de la carpeta permanece coherente con el menú de pulsación larga de la sesión (márgenes de pantalla estándar).
        horizontalPadding = MusePaddings.screen,
        bottomContentSpacing = 0.dp,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            MuseActionSheetRow(
                icon = MuseIcons.edit,
                text = stringResource(R.string.chat_list_rename),
                onClick = {
                    onRename()
                    onDismiss()
                },
            )
            MuseActionSheetRow(
                icon = MuseIcons.trash,
                text = stringResource(R.string.action_delete),
                contentColor = MaterialTheme.colorScheme.error,
                onClick = {
                    onDelete()
                    onDismiss()
                },
            )
        }
    }
}

/** Tarjeta de entrada a la base de conocimientos. El estado vacío muestra solicitudes para agregar y muestra el nombre de la base de conocimientos cuando hay documentos. */
@Composable
private fun KnowledgeEntryCard(docCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    CardGroup(
        title = { Text(stringResource(R.string.chat_list_section_knowledge)) },
        modifier = modifier.fillMaxWidth(),
    ) {
        item(
            onClick = onClick,
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        // CHAT-16: unificar"+"estilo — Área de iconos arreglada Book,Mantenga solo una flecha de acción al final/signo más
                        imageVector = MuseIcons.book,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            },
            headlineContent = {
                Text(
                    text = stringResource(R.string.chat_list_knowledge_title),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                )
            },
            supportingContent = {
                Text(
                    text = if (docCount == 0) {
                        stringResource(R.string.chat_list_knowledge_empty)
                    } else {
                        stringResource(R.string.chat_list_knowledge_count, docCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Icon(
                    // Utilice las flechas de manera uniforme: en el estado vacío, la copia de la tarjeta ya dice "Haga clic para agregar documento".
                    // Cuelga otro al final. `+` Es como decir la misma acción dos veces.
                    imageVector = MuseIcons.arrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
    }
}

/**
 * CHAT-16: Título de la conversación — Título vacío o puntos suspensivos de marcador de posición("…")Siempre recurra a "nueva sesión".
 * Los datos históricos importados pueden guardar el título como "…",Se muestra directamente como datos incorrectos.
 */
@Composable
private fun sessionTitleText(session: SessionEntity): String {
    val fallback = stringResource(R.string.chat_new_session)
    return io.zer0.muse.data.session.displaySessionTitle(session.title, fallback)
}

/** Inferencia del estado de la tarea(No hay ningún campo de estado en el backend.,Inferido por la actividad desde el front-end). */
private enum class TaskStatus { IN_PROGRESS, PENDING, COMPLETED }

private fun inferTaskStatus(session: SessionEntity, now: Long): TaskStatus {
    // Vaciar sesión / Crear solo sin mensajes -> Por confirmar
    if (session.lastMessagePreview.isBlank() || session.messageCount <= 0) return TaskStatus.PENDING
    val dayMillis = TimeUnit.DAYS.toMillis(1)
    val threeDays = dayMillis * 3
    // 3 Número de actualizaciones y mensajes en días >=2 -> En progreso
    val age = (now - session.updatedAt).coerceAtLeast(0L)
    if (age < threeDays && session.messageCount >= 2) return TaskStatus.IN_PROGRESS
    // No actualizado en más de 3 días. -> Completado
    if (age >= threeDays) return TaskStatus.COMPLETED
    // el resto -> Por confirmar
    return TaskStatus.PENDING
}

@Composable
private fun TaskStatusDot(session: SessionEntity) {
    // El punto de estado solo expresa el ciclo de vida de la sesión y ya no“no leído”Toscamente teñido de rojo.
    // El recuento de no leídos ya se expresa en el mensaje de salto después de ingresar a la conversación, y el punto rojo en la lista hará que los mensajes nuevos comunes parezcan errores.
    val status = remember(session.id, session.updatedAt, session.messageCount) {
        inferTaskStatus(session, System.currentTimeMillis())
    }
    TaskStatusDot(status = status)
}

@Composable
private fun TaskStatusDot(status: TaskStatus) {
    val (color, description) = when (status) {
        TaskStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary to stringResource(R.string.chat_list_status_in_progress)
        TaskStatus.PENDING -> MaterialTheme.colorScheme.tertiary to stringResource(R.string.chat_list_status_pending)
        TaskStatus.COMPLETED -> MaterialTheme.colorScheme.outline to stringResource(R.string.chat_list_status_completed)
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
            .semantics { contentDescription = description },
    )
}

@Composable
private fun formatTaskStatus(session: SessionEntity): String {
    val status = inferTaskStatus(session, System.currentTimeMillis())
    return when (status) {
        TaskStatus.IN_PROGRESS -> stringResource(R.string.chat_list_status_in_progress)
        TaskStatus.PENDING -> stringResource(R.string.chat_list_status_pending)
        TaskStatus.COMPLETED -> stringResource(R.string.chat_list_status_completed)
    }
}

// H-CL1: SimpleDateFormat Promover la reutilización diferida de val a nivel de archivo
private val chatListSdf by lazy {
    SimpleDateFormat(MuseDateFormats.DATE_TIME_SHORT, Locale.getDefault())
}

@Composable
private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val dayMillis = TimeUnit.DAYS.toMillis(1)
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> stringResource(R.string.chat_list_time_just_now)
        diff < TimeUnit.HOURS.toMillis(1) -> stringResource(R.string.chat_list_time_minutes_ago, diff / TimeUnit.MINUTES.toMillis(1))
        diff < dayMillis -> stringResource(R.string.chat_list_time_hours_ago, diff / TimeUnit.HOURS.toMillis(1))
        diff < dayMillis * 2 -> stringResource(R.string.chat_list_time_yesterday)
        diff < dayMillis * 7 -> stringResource(R.string.chat_list_time_days_ago, diff / dayMillis)
        else -> chatListSdf.format(Date(timestamp))
    }
}

/**
 * U-7: fila de herramientas de lista — proporcionar"Archivado"con"Editar(opción múltiple)"Dos entradas.
 * La entrada de archivos resuelve el problema de la no entrada y la irreversibilidad de los archivos;El portal de edición resuelve el problema del alto costo de hacer clic durante mucho tiempo en varias sesiones una por una.
 */
@Composable
private fun ListToolbarRow(archivedCount: Int, onOpenArchived: () -> Unit, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        ChatListToolbarAction(
            icon = MuseIcons.archive,
            label = stringResource(R.string.chat_list_archived_entry, archivedCount),
            onClick = onOpenArchived,
        )
        ChatListToolbarAction(
            icon = MuseIcons.edit,
            label = stringResource(R.string.chat_list_edit),
            onClick = onEdit,
        )
    }
}

/** U-7/U-22: Botón de acción de fila de herramientas(Icono + Copiar). */
@Composable
private fun ChatListToolbarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MuseShapes.medium,
        // v2.0: La fila de herramientas es una operación secundaria. — Desde el color principal sólido hasta tonal(recipiente de baja saturación),
        // Evitar con clave de envío/El estado seleccionado gana peso visual(Las dos cápsulas sólidas originales eran demasiado pesadas.).
        color = MuseActionColors.tonalContainer,
        contentColor = MuseActionColors.tonalContent,
        modifier = Modifier.heightIn(min = 36.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MuseActionColors.tonalContent,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MuseActionColors.tonalContent,
            )
        }
    }
}

/**
 * U-7: Vista de conversación archivada. Volver al principio + título,lista de elementos de la banda"restaurar"botón,
 * Reutilizar [onUnarchive](HomeScreen Atado setSessionArchived(id, false)).
 */
@Composable
private fun ArchivedSessionsList(
    archived: List<SessionEntity>,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val restoreToast = stringResource(R.string.chat_list_restored_toast)
    Column(modifier = modifier.fillMaxWidth()) {
        // arriba:volver + titulo
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MuseTactileButton(
                icon = MuseIcons.arrowLeft,
                onClick = onBack,
                contentDescription = stringResource(R.string.search_back_cd),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.chat_list_archived_entry, archived.size),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        MuseDivider()
        if (archived.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.chat_list_archived_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = MusePaddings.listBottomClearance),
            ) {
                itemsIndexed(archived, key = { _, s -> "archived_${s.id}" }) { _, session ->
                    SectionGroupRow(index = 0, total = 1) {
                        ArchivedSessionRow(
                            session = session,
                            onRestore = {
                                onRestore(session.id)
                                MuseToast.show(restoreToast)
                            },
                            onSelect = { onSelect(session.id) },
                        )
                    }
                }
            }
        }
    }
}

/** U-7: Línea de conversación archivada — Título + hora + botón de reanudar. Haga clic para avanzar en la conversación,Haga clic en Restaurar para cancelar el archivo. */
@Composable
private fun ArchivedSessionRow(session: SessionEntity, onRestore: () -> Unit, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onSelect)
            .padding(horizontal = MusePaddings.screen, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sessionTitleText(session),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatTime(session.updatedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.width(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.chat_list_archived_restore),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(MuseShapes.medium)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                .clickable(onClick = onRestore)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * U-22: Modo de edición de selección múltiple. La barra superior proporciona"Seleccionar todo/Eliminar/Archivo/Salir",
 * Eliminar/Ejecución del ciclo de archivo [onDeleteSelected]/[onArchiveSelected](Reutilizar solo onDelete/onArchive).
 */
@Composable
private fun ChatListEditMode(
    sessions: List<SessionEntity>,
    selectedIds: Set<String>,
    onToggleSelect: (String) -> Unit,
    onClearSelection: () -> Unit,
    onExit: () -> Unit,
    onDeleteSelected: (Set<String>) -> Unit,
    onArchiveSelected: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val archivedToast = stringResource(R.string.chat_list_archived_toast)
    val doneToast = stringResource(R.string.chat_list_edit_done, selectedIds.size)
    val allSelected = sessions.isNotEmpty() && selectedIds.size == sessions.size
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ChatListEditAction(
                label = stringResource(R.string.chat_list_edit_select_all),
                enabled = sessions.isNotEmpty(),
                onClick = {
                    if (allSelected) {
                        onClearSelection()
                    } else {
                        sessions.forEach { onToggleSelect(it.id) }
                    }
                },
            )
            ChatListEditAction(
                label = stringResource(R.string.chat_list_edit_delete),
                enabled = selectedIds.isNotEmpty(),
                onClick = {
                    onDeleteSelected(selectedIds)
                    MuseToast.show(doneToast)
                    onExit()
                },
            )
            ChatListEditAction(
                label = stringResource(R.string.chat_list_edit_archive),
                enabled = selectedIds.isNotEmpty(),
                onClick = {
                    onArchiveSelected(selectedIds)
                    MuseToast.show(archivedToast)
                    onExit()
                },
            )
            Spacer(Modifier.weight(1f))
            ChatListEditAction(
                label = stringResource(R.string.chat_list_edit_exit),
                enabled = true,
                onClick = onExit,
            )
        }
        MuseDivider()
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = MusePaddings.listBottomClearance),
        ) {
            itemsIndexed(sessions, key = { _, s -> "edit_${s.id}" }) { _, session ->
                EditModeRow(
                    session = session,
                    selected = session.id in selectedIds,
                    onClick = { onToggleSelect(session.id) },
                )
            }
        }
    }
}

/** U-22: Línea de conversación única en modo de selección múltiple — Estado comprobado + título + hora,Haga clic para alternar la selección. */
@Composable
private fun EditModeRow(session: SessionEntity, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = MusePaddings.screen, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = MuseIcons.check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.width(MusePaddings.screen))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sessionTitleText(session),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatTime(session.updatedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** U-22: Botón de acción de selección múltiple de la barra superior. */
@Composable
private fun ChatListEditAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        modifier = Modifier
            .clip(MuseShapes.medium)
            .background(
                if (enabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * Determina si un saludo pertenece a una plantilla vacía como "Aún no he hablado hoy".
 *
 * Este tipo de copia está escrita por [io.zer0.muse.schedule.DailySummaryService] Escribe cuando no haya material suficiente para el día,
 * Equivale a "material insuficiente", no significa que el usuario realmente no haya chateado hoy. Cuándo hay sesiones en la página de inicio hoy
 * Manténgalo oculto para evitar conflictos con su propia lista de conversaciones.
 */
private fun looksLikeEmptyDayHint(text: String): Boolean {
    val markers = listOf(
        "No he hablado hoy todavía",
        "Todavía no hoy",
        "No hay nuevas conversaciones hoy.",
        "Ven a mí en cualquier momento si se te ocurre algo.",
    )
    return markers.any { text.contains(it) }
}
