@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@file:OptIn(ExperimentalLayoutApi::class)

package io.zer0.muse.ui.schedule

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.AppJson
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.schedule.AutomationConfig
import io.zer0.muse.data.schedule.AutomationConfig.toAction
import io.zer0.muse.data.schedule.AutomationConfig.toCondition
import io.zer0.muse.data.schedule.AutomationConfig.toIdsJson
import io.zer0.muse.data.schedule.AutomationConfig.toIdsList
import io.zer0.muse.data.schedule.AutomationConfig.toJson
import io.zer0.muse.data.schedule.ScheduledTaskDao
import io.zer0.muse.data.schedule.ScheduledTaskEntity
import io.zer0.muse.data.schedule.ScheduledTaskExecutionDao
import io.zer0.muse.data.schedule.ScheduledTaskExecutionEntity
import io.zer0.muse.data.schedule.TaskIdName
import io.zer0.muse.schedule.BackgroundWakeDiagnostics
import io.zer0.muse.schedule.CronExpression
import io.zer0.muse.schedule.ScheduledTaskRunner
import io.zer0.muse.schedule.WakeDiagnostics
import io.zer0.muse.schedule.WakeState
import io.zer0.muse.schedule.WakeStopReason
import io.zer0.muse.schedule.WakeWorkEntry
import io.zer0.muse.schedule.WakeWorkKind
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseFloatingButton
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseStatusColors
import io.zer0.muse.ui.theme.statusColors
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.104: Convierta la expresión Cron de 5 campos en una descripción china legible por humanos.
 *
 * Cubrir patrones comunes:todos los dias/cada hora/por minuto/jornada laboral/fin de semana/Especificar semana/Especifique la fecha.
 * Expresiones complejas devueltas sin anular null(UI No mostrar descripción,recurrir a la expresión original).
 * Orden de campo:minute hour day-of-month month day-of-week
 */
private fun describeCron(expr: String, res: Resources): String? {
    val parts = expr.trim().split(Regex("\\s+"))
    if (parts.size != 5) return null
    val (minute, hour, dom, month, dow) = parts

    fun weekdayName(day: String): String? = when (day) {
        "0", "7" -> res.getString(R.string.schedule_cron_sun)
        "1" -> res.getString(R.string.schedule_cron_mon)
        "2" -> res.getString(R.string.schedule_cron_tue)
        "3" -> res.getString(R.string.schedule_cron_wed)
        "4" -> res.getString(R.string.schedule_cron_thu)
        "5" -> res.getString(R.string.schedule_cron_fri)
        "6" -> res.getString(R.string.schedule_cron_sat)
        else -> null
    }
    fun fmtTime(h: String, m: String): String {
        val hh = h.padStart(2, '0')
        val mm = m.padStart(2, '0')
        return "$hh:$mm"
    }

    // todos los dias H:M
    if (dom == "*" && month == "*" && dow == "*" && minute != "*" && hour != "*" && !minute.contains("-") && !hour.contains("-")) {
        return res.getString(R.string.schedule_cron_daily, fmtTime(hour, minute))
    }
    // M minutos por hora
    if (hour == "*" && dom == "*" && month == "*" && dow == "*" && minute != "*" && !minute.contains("-")) {
        return res.getString(R.string.schedule_cron_hourly, minute)
    }
    // por minuto
    if (minute == "*" && hour == "*" && dom == "*" && month == "*" && dow == "*") {
        return res.getString(R.string.schedule_cron_every_minute)
    }
    // jornada laboral H:M(dow = 1-5)
    if (dom == "*" && month == "*" && dow == "1-5" && minute != "*" && hour != "*" && !minute.contains("-") && !hour.contains("-")) {
        return res.getString(R.string.schedule_cron_workday, fmtTime(hour, minute))
    }
    // fin de semana H:M(dow = 0,6 o 6,0)
    if (dom == "*" && month == "*" && (dow == "0,6" || dow == "6,0") && minute != "*" && hour != "*" && !minute.contains("-") && !hour.contains("-")) {
        return res.getString(R.string.schedule_cron_weekend, fmtTime(hour, minute))
    }
    // Especificar semana H:M(dow es un solo número o una lista de comas)
    if (dom == "*" && month == "*" && dow != "*" && minute != "*" && hour != "*" && !minute.contains("-") && !hour.contains("-")) {
        val dayNames = dow.split(",").mapNotNull { weekdayName(it.trim()) }
        if (dayNames.isNotEmpty()) {
            return res.getString(R.string.schedule_cron_weekdays, dayNames.joinToString(","), fmtTime(hour, minute))
        }
    }
    // día designado de cada mes H:M
    if (dom != "*" && month == "*" && dow == "*" && minute != "*" && hour != "*" && !minute.contains("-") && !hour.contains("-") && !dom.contains("-")) {
        return res.getString(R.string.schedule_cron_monthly, dom, fmtTime(hour, minute))
    }
    return null
}

// M-SC2 Reparación: interval Originalmente el valor en inglés se mostraba directamente(once/hourly/daily/weekly/cron),
// Ahora mapeo unificado a etiquetas chinas,Evitar que los usuarios vean el valor de enumeración original
@Composable
private fun intervalToLabel(interval: String): String = when (interval) {
    "once" -> stringResource(R.string.schedule_interval_once)
    "hourly" -> stringResource(R.string.schedule_interval_hourly)
    "daily" -> stringResource(R.string.schedule_interval_daily)
    "weekly" -> stringResource(R.string.schedule_interval_weekly)
    "cron" -> stringResource(R.string.schedule_interval_cron)
    else -> interval
}

/**
 * U-13: CALL_TOOL Herramientas comunes para la acción Plantillas de llenado rápido(Ayude a los usuarios a evitar escribir a mano dentro de la página avanzada toolId/JSON).
 * Incluir solo herramientas públicas estables y abiertas(corresponder ToolRegistry.BUILT_IN_TOOL_IDS),No contiene herramientas de alto riesgo.
 */
private data class ToolTemplate(
    val id: String,
    val label: String,
    val paramsJson: String,
)

private val TOOL_TEMPLATES = listOf(
    ToolTemplate("clipboard_read", "leer portapapeles", "{}"),
    ToolTemplate("get_current_time", "Obtener la hora actual", "{\"timezone\":\"Asia/Shanghai\"}"),
    ToolTemplate("calculator", "calcular", "{\"expression\":\"1+2*3\"}"),
    ToolTemplate("browser_navigate", "Abrir página web", "{\"url\":\"https://example.com\"}"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTasksScreen(
    onBack: () -> Unit,
    initialTaskId: String? = null,
    dao: ScheduledTaskDao = koinInject(),
    executionDao: ScheduledTaskExecutionDao = koinInject(),
    assistantRepository: AssistantRepository = koinInject(),
    runner: ScheduledTaskRunner = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    // v1.48: h13 initialValue usar null,Ingresar al estado de carga por primera vez en lugar de mostrar el estado
    // ST-01: DAO Flow Agregar captura de error + reintento.loadError independiente de produceState,
    // producer Reinicie solo cuando cambie retryKey,Evite escribir loadError en el productor para desencadenar un bucle infinito de reorganización.
    var tasksRetryKey by remember { mutableStateOf(0) }
    var tasksLoadError by remember { mutableStateOf<String?>(null) }
    val tasks by produceState<List<ScheduledTaskEntity>?>(null, tasksRetryKey) {
        value = null
        try {
            dao.observeAll().collect { value = it }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            tasksLoadError = e.message ?: context.getString(R.string.common_load_failed)
        }
    }
    // lista de asistentes(para nuevo/Seleccione el asistente de ejecución al editar una tarea)
    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())
    var showCreate by remember { mutableStateOf(false) }
    var taskToEdit by remember { mutableStateOf<ScheduledTaskEntity?>(null) }
    var showWakeDiagnostics by rememberSaveable { mutableStateOf(false) }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.schedule_title),
                onBack = onBack,
                largeTitle = true,
                actions = {
                    // estado de alerta de fondo:Cuando la programación en segundo plano es invisible,Los usuarios sólo pueden adivinar. panel de solo lectura,Sin cambios de programación.
                    IconButton(onClick = { showWakeDiagnostics = true }) {
                        Icon(
                            MuseIcons.gauge,
                            contentDescription = stringResource(R.string.wake_diagnostics_open),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            // L-SC5: uso MuseFloatingButton,Consistente con el estilo de la aplicación.
            MuseFloatingButton(
                icon = MuseIcons.plus,
                onClick = { showCreate = true },
                contentDescription = stringResource(R.string.schedule_new_task),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        val tasksList = tasks
        // ST-01: Cargando estado de error fallido + inténtalo de nuevo(Sólo se muestra si no hay datos,Evite la superposición con listas existentes)
        if (tasksLoadError != null && tasksList == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                MuseErrorStateBox(
                    message = tasksLoadError.orEmpty(),
                    onRetry = {
                        tasksLoadError = null
                        tasksRetryKey++
                    },
                )
            }
        } else if (tasksList == null) {
            // v1.48: h13 Mostrar indicador de carga centrada en la primera carga
            Column(
                Modifier.fillMaxSize().padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MuseSpinner()
            }
        } else if (tasksList.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MuseEmptyState(
                    icon = MuseIcons.clock,
                    title = stringResource(R.string.schedule_empty_title),
                    subtitle = stringResource(R.string.schedule_empty_subtitle),
                    actionText = stringResource(R.string.schedule_new_task),
                    onAction = { showCreate = true },
                )
            }
        } else {
            LaunchedEffect(initialTaskId, tasksList) {
                val targetIndex = initialTaskId?.let { id -> tasksList.indexOfFirst { it.id == id } } ?: -1
                if (targetIndex >= 0) {
                    listState.scrollToItem(targetIndex)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(tasksList, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        executionDao = executionDao,
                        runner = runner,
                        initiallyExpanded = task.id == initialTaskId,
                        onToggle = { enabled -> scope.launch { dao.setEnabled(task.id, enabled) } },
                        onEdit = { taskToEdit = it },
                        onDelete = {
                            scope.launch {
                                executionDao.deleteByTaskId(task.id)
                                dao.delete(task.id)
                                MuseToast.show(context.getString(R.string.schedule_deleted))
                            }
                        },
                    )
                }
            }
        }
    }

    // Nueva ventana emergente de tarea
    if (showCreate) {
        TaskDialog(
            existingTask = null,
            assistants = assistants,
            onDismiss = { showCreate = false },
            onSave = { task ->
                scope.launch {
                    dao.upsert(task)
                    showCreate = false
                }
            },
        )
    }
    // Editar ventana emergente de tarea(Complete previamente los campos al pasar una tarea existente)
    taskToEdit?.let { editing ->
        TaskDialog(
            existingTask = editing,
            assistants = assistants,
            onDismiss = { taskToEdit = null },
            onSave = { task ->
                scope.launch {
                    dao.upsert(task)
                    taskToEdit = null
                }
            },
        )
    }
    if (showWakeDiagnostics) {
        BackgroundWakeSheet(onDismiss = { showWakeDiagnostics = false })
    }
}

@Composable
private fun TaskCard(
    task: ScheduledTaskEntity,
    executionDao: ScheduledTaskExecutionDao,
    runner: ScheduledTaskRunner,
    initiallyExpanded: Boolean = false,
    onToggle: (Boolean) -> Unit,
    onEdit: (ScheduledTaskEntity) -> Unit,
    onDelete: () -> Unit,
) {
    // P1-7: Los elementos de la tarea se pueden ampliar,Mostrar el historial de las últimas 5 ejecuciones
    var expanded by rememberSaveable(task.id) { mutableStateOf(initiallyExpanded) }
    var executions by remember { mutableStateOf<List<ScheduledTaskExecutionEntity>>(emptyList()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // U-25: "Ver historial completo"estado de diálogo
    var showAllHistory by remember { mutableStateOf(false) }
    var allHistory by remember { mutableStateOf<List<ScheduledTaskExecutionEntity>>(emptyList()) }
    // "Ejecutar inmediatamente"El estado de ejecución del botón.(para depurar,llamar ScheduledTaskRunner.executeTask)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var executing by remember { mutableStateOf(false) }

    // Cargue los últimos 20 registros de ejecución al expandir(U-25: Aumentó de 5 a 20)
    LaunchedEffect(task.id, expanded) {
        if (expanded) {
            executions = executionDao.queryByTaskId(task.id).take(20)
        }
    }

    val enabledTint = if (task.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // cabeza:icono de estado ladrillo + nombre/Contenido + interruptor de inicio/parada(Haga clic en toda la fila para expandir el historial de ejecución)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(MuseShapes.small)
                        .background(enabledTint.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MuseIcons.clock,
                        contentDescription = null,
                        tint = enabledTint,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = task.name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.size(2.dp))
                    Text(
                        text = task.prompt.take(80),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                MuseSwitch(checked = task.enabled, onCheckedChange = onToggle)
            }

            // Línea de logotipo de metainformación:Ciclo/próxima ejecución/última ejecución
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 68.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TaskMetaChip(text = intervalToLabel(task.interval))
                if (task.nextRunAt > 0) {
                    val nextFmt = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_SHORT, Locale.getDefault()) }
                    TaskMetaChip(
                        text = stringResource(R.string.schedule_next_run, nextFmt.format(Date(task.nextRunAt))),
                        highlighted = task.enabled,
                    )
                }
                if (task.lastRunAt > 0) {
                    val lastFmt = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_SHORT, Locale.getDefault()) }
                    TaskMetaChip(
                        text = stringResource(R.string.schedule_last_run_short, lastFmt.format(Date(task.lastRunAt))),
                    )
                }
            }

            Spacer(Modifier.size(10.dp))

            // linea de operacion:Ejecutar inmediatamente/editar/eliminar/expansión del historial
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (executing) {
                    Box(
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                        contentAlignment = Alignment.Center,
                    ) {
                        MuseSpinner(size = MuseIconSizes.iconSmallTiny)
                    }
                } else {
                    MuseCapsuleButton(
                        text = stringResource(R.string.schedule_run_now),
                        onClick = {
                            scope.launch {
                                executing = true
                                resultOf { runner.executeTask(task) }
                                executing = false
                                // Si se ha ampliado una vez completada la ejecución,Actualizar el historial de ejecución
                                if (expanded) {
                                    executions = executionDao.queryByTaskId(task.id).take(20)
                                }
                                // U-25: toast Resumen de resultados de esta ejecución(éxito/Fallo + vista previa de salida)
                                val latest = resultOf { executionDao.queryByTaskId(task.id) }.getOrNull()?.firstOrNull()
                                val msg = if (latest == null) {
                                    context.getString(R.string.schedule_executed)
                                } else {
                                    when (latest.status) {
                                        "failed" -> context.getString(
                                            R.string.schedule_run_failed_detail,
                                            latest.errorMessage.ifBlank { latest.replySummary }.take(60),
                                        )
                                        "skipped" -> context.getString(R.string.schedule_condition_not_met)
                                        else -> context.getString(
                                            R.string.schedule_run_success_detail,
                                            latest.replySummary.take(60),
                                        )
                                    }
                                }
                                MuseToast.show(msg)
                            }
                        },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                    )
                }
                MuseCapsuleButton(
                    text = stringResource(R.string.schedule_edit),
                    onClick = { onEdit(task) },
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.schedule_delete),
                    onClick = { showDeleteConfirm = true },
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(MuseIconSizes.touchTarget)
                        .clip(MuseShapes.small)
                        .clickable { expanded = !expanded },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                        contentDescription = if (expanded) {
                            stringResource(R.string.schedule_collapse_history)
                        } else {
                            stringResource(R.string.schedule_expand_history)
                        },
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // P1-7: Mostrar el historial de ejecución reciente cuando se expanda(Hora + icono de estado + resumen de respuesta),Marca roja de tarea fallida
            if (expanded) {
                ExecutionHistorySection(
                    executions = executions,
                    initiallyExpandLatest = initiallyExpanded,
                    // U-25: Ver historial completo(Límite de consulta: 100 artículos)
                    onViewAll = {
                        scope.launch {
                            allHistory = executionDao.queryAllByTaskId(task.id)
                        }
                        showAllHistory = true
                    },
                )
            }
        }
    }
    // U-25: Cuadro de diálogo de todo el historial de ejecución
    if (showAllHistory) {
        MuseDialog(
            onDismissRequest = { showAllHistory = false },
            title = stringResource(R.string.schedule_view_all_history),
            content = {
                if (allHistory.isEmpty()) {
                    Text(
                        stringResource(R.string.schedule_no_executions),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    // v2.0.1 fix(La máquina real falla): MuseDialog El área de contenido viene con verticalScroll —
                    // No se permite anidar más contenedores de desplazamiento aquí(La capa interior tendrá la restricción de altura infinita.,La prueba real colapsará).
                    Column(Modifier.fillMaxWidth()) {
                        allHistory.forEach { exec ->
                            ExecutionRow(execution = exec)
                            Spacer(Modifier.size(6.dp))
                        }
                    }
                }
            },
            onConfirm = null,
            dismissText = stringResource(R.string.action_close),
            onDismiss = { showAllHistory = false },
        )
    }
    if (showDeleteConfirm) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.schedule_delete_task),
            itemName = task.name,
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

/**
 * Cápsula de metainformación de tareas:Visión unificada de ciclo/próxima ejecución/última ejecución.
 */
@Composable
private fun TaskMetaChip(text: String, highlighted: Boolean = false) {
    Surface(
        shape = MuseShapes.huge,
        color = if (highlighted) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * P1-7: Área de historial de ejecución.
 *
 * Procese los últimos 5 registros de ejecución después de expandir la tarjeta de tareas. Cada registro contiene:
 *  - icono de estado(Círculo de verificación de éxito/fracaso Error)
 *  - tiempo de ejecución
 *  - Resumen de respuesta(éxito)o mensaje de error(falló),Los fallos están marcados en rojo.
 */
@Composable
private fun ExecutionHistorySection(
    executions: List<ScheduledTaskExecutionEntity>,
    initiallyExpandLatest: Boolean = false,
    /** U-25: Se muestra cuando no está vacío"Ver historial completo"entrada. */
    onViewAll: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.schedule_execution_history),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.size(4.dp))
        if (executions.isEmpty()) {
            Text(
                stringResource(R.string.schedule_no_executions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        } else {
            executions.forEachIndexed { index, exec ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                ExecutionRow(
                    execution = exec,
                    initiallyExpanded = initiallyExpandLatest && index == 0,
                )
            }
        }
        // U-25: Ver todas las entradas históricas
        if (onViewAll != null && executions.isNotEmpty()) {
            Spacer(Modifier.size(4.dp))
            Text(
                text = stringResource(R.string.schedule_view_all_history),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(MuseShapes.small)
                    .clickable(onClick = onViewAll)
                    .padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ExecutionRow(execution: ScheduledTaskExecutionEntity, initiallyExpanded: Boolean = false) {
    val failed = execution.status == "failed"
    // rondas perdidas:Esta ronda no terminó normalmente.,No se puede mostrar la marca de verificación "ejecución exitosa"
    val missed = execution.status == ScheduledTaskRunner.STATUS_MISSED
    val summary = if (failed) execution.errorMessage else execution.replySummary
    val canExpand = summary.length > 120
    var expanded by rememberSaveable(execution.id) { mutableStateOf(initiallyExpanded) }
    // L-SC3 Reparación: Reemplace la codificación con el token MuseDateFormats.DATE_TIME_FULL_SEC "MM-dd HH:mm:ss"
    val fmt = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL_SEC, Locale.getDefault()) }
    val iconTint = when {
        failed -> MaterialTheme.colorScheme.error
        missed -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.primary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS, easing = MuseAnimation.GrowEase),
            )
            .clickable(
                enabled = canExpand,
                role = Role.Button,
                onClick = { expanded = !expanded },
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (failed || missed) MuseIcons.alertCircle else MuseIcons.circleCheck,
            contentDescription = when {
                failed -> stringResource(R.string.schedule_execution_failed)
                missed -> stringResource(R.string.schedule_execution_missed)
                else -> stringResource(R.string.schedule_execution_success)
            },
            tint = iconTint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(8.dp))
        Column {
            // Hora + Toda la línea se resalta en rojo en caso de falla
            val timeColor = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
            Text(
                text = fmt.format(Date(execution.executedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = timeColor,
            )
            if (summary.isNotEmpty()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                )
            }
        }
        if (canExpand) {
            Icon(
                imageVector = if (expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                contentDescription = stringResource(
                    if (expanded) R.string.schedule_collapse_history else R.string.schedule_expand_history,
                ),
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * v1.137: Nuevo/Editar ventana emergente de tarea(Admite activación condicional, múltiples tipos de acciones y tareas en cadena).
 */
@Composable
private fun TaskDialog(
    existingTask: ScheduledTaskEntity?,
    assistants: List<AssistantEntity>,
    dao: ScheduledTaskDao = koinInject(),
    onDismiss: () -> Unit,
    onSave: (ScheduledTaskEntity) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Campos básicos
    var name by rememberSaveable { mutableStateOf(existingTask?.name ?: "") }
    var prompt by rememberSaveable { mutableStateOf(existingTask?.prompt ?: "") }
    var assistantId by rememberSaveable { mutableStateOf(existingTask?.assistantId ?: "default") }
    var interval by rememberSaveable { mutableStateOf(existingTask?.interval ?: "daily") }
    var cronExpr by rememberSaveable { mutableStateOf(existingTask?.cronExpr ?: "") }

    // Campos de automatización
    val initialCondition =
        remember(existingTask?.conditionJson) { existingTask?.conditionJson?.toCondition() ?: AutomationConfig.Condition() }
    var conditionType by rememberSaveable { mutableStateOf(initialCondition.type) }
    var conditionKeyword by rememberSaveable { mutableStateOf(initialCondition.config["keyword"]?.toString()?.trim('"') ?: "") }
    var conditionTag by rememberSaveable { mutableStateOf(initialCondition.config["tag"]?.toString()?.trim('"') ?: "") }
    var conditionStartHour by rememberSaveable { mutableStateOf(initialCondition.config["startHour"]?.toString()?.trim('"') ?: "0") }
    var conditionEndHour by rememberSaveable { mutableStateOf(initialCondition.config["endHour"]?.toString()?.trim('"') ?: "23") }

    val initialActionConfig =
        remember(existingTask?.actionConfigJson) { existingTask?.actionConfigJson?.toAction()?.config ?: JsonObject(emptyMap()) }
    var actionType by rememberSaveable { mutableStateOf(existingTask?.actionType ?: AutomationConfig.Action.AI_PROMPT) }
    var noteTitle by rememberSaveable { mutableStateOf(initialActionConfig["title"]?.toString()?.trim('"') ?: "") }
    var noteContent by rememberSaveable { mutableStateOf(initialActionConfig["content"]?.toString()?.trim('"') ?: "") }
    var noteTags by rememberSaveable { mutableStateOf(initialActionConfig["tags"]?.toString()?.trim('"') ?: "") }
    var toolId by rememberSaveable { mutableStateOf(initialActionConfig["toolId"]?.toString()?.trim('"') ?: "") }
    var toolParams by rememberSaveable {
        mutableStateOf(
            when (val p = initialActionConfig["params"]) {
                is JsonObject -> AppJson.encodeToString(JsonObject.serializer(), p) else -> p?.toString()?.trim('"') ?: "{}"
            },
        )
    }
    var notifyTitle by rememberSaveable { mutableStateOf(initialActionConfig["title"]?.toString()?.trim('"') ?: "") }
    var notifyMessage by rememberSaveable { mutableStateOf(initialActionConfig["message"]?.toString()?.trim('"') ?: "") }

    var nextTaskIds by rememberSaveable { mutableStateOf(existingTask?.nextTaskIdsJson?.toIdsList() ?: emptyList()) }
    var chainCandidates by remember { mutableStateOf<List<io.zer0.muse.data.schedule.TaskIdName>>(emptyList()) }
    // v1.0.17: Mapeo completo de tareas,Se utiliza para la detección de bucles de tareas en cadena.(DFS atravesar nextTaskIdsJson)
    var allTasksMap by remember { mutableStateOf<Map<String, ScheduledTaskEntity>>(emptyMap()) }
    LaunchedEffect(existingTask?.id) {
        chainCandidates = dao.listChainCandidates(existingTask?.id ?: "")
        allTasksMap = dao.getAll().associateBy { it.id }
    }

    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val errNameRequired = stringResource(R.string.schedule_err_name_required)
    val errContentRequired = stringResource(R.string.schedule_err_content_required)
    val errCronInvalid = stringResource(R.string.schedule_err_cron_invalid)

    val isEdit = existingTask != null
    val selectedAssistantName = assistants.firstOrNull { it.id == assistantId }?.name
        ?: if (assistantId == "default" || assistantId.isBlank()) stringResource(R.string.schedule_default_assistant) else assistantId

    val nextRunPreview = remember(interval, cronExpr) {
        // La vista previa es del mismo calibre que la biblioteca desplegable.:No existe una "próxima vez" para tareas únicas,No mostrar vista previa
        ScheduledTaskRunner.computeNextRun(interval, cronExpr.trim(), System.currentTimeMillis())
            .takeIf { it > 0 }
    }
    val previewFmt = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, Locale.getDefault()) }
    val previewText = nextRunPreview?.let { stringResource(R.string.schedule_next_run_preview, previewFmt.format(Date(it))) }

    // Detección de modificaciones no guardadas
    var showDiscardConfirm by rememberSaveable { mutableStateOf(false) }
    val initialName = remember(existingTask?.name) { existingTask?.name ?: "" }
    val initialPrompt = remember(existingTask?.prompt) { existingTask?.prompt ?: "" }
    val initialAssistantId = remember(existingTask?.assistantId) { existingTask?.assistantId ?: "default" }
    val initialInterval = remember(existingTask?.interval) { existingTask?.interval ?: "daily" }
    val initialCronExpr = remember(existingTask?.cronExpr) { existingTask?.cronExpr ?: "" }
    val initialActionType = remember(existingTask?.actionType) { existingTask?.actionType ?: AutomationConfig.Action.AI_PROMPT }
    val initialNextTaskIds = remember(existingTask?.nextTaskIdsJson) { existingTask?.nextTaskIdsJson?.toIdsList() ?: emptyList<String>() }
    val hasUnsavedChanges = remember(
        name, prompt, assistantId, interval, cronExpr, actionType, conditionType,
        conditionKeyword, conditionTag, conditionStartHour, conditionEndHour,
        noteTitle, noteContent, noteTags, toolId, toolParams, notifyTitle, notifyMessage, nextTaskIds,
        initialName, initialPrompt, initialAssistantId, initialInterval, initialCronExpr, initialActionType, initialNextTaskIds,
    ) {
        val conditionChanged = conditionType != initialCondition.type ||
            conditionKeyword != (initialCondition.config["keyword"]?.toString()?.trim('"') ?: "") ||
            conditionTag != (initialCondition.config["tag"]?.toString()?.trim('"') ?: "") ||
            conditionStartHour != (initialCondition.config["startHour"]?.toString()?.trim('"') ?: "0") ||
            conditionEndHour != (initialCondition.config["endHour"]?.toString()?.trim('"') ?: "23")
        name != initialName || prompt != initialPrompt || assistantId != initialAssistantId ||
            interval != initialInterval || cronExpr != initialCronExpr || actionType != initialActionType ||
            conditionChanged || nextTaskIds != initialNextTaskIds
    }
    val dismissWithCheck = { if (hasUnsavedChanges) showDiscardConfirm = true else onDismiss() }
    BackHandler { dismissWithCheck() }

    MuseDialog(
        onDismissRequest = { dismissWithCheck() },
        title = if (isEdit) stringResource(R.string.schedule_edit_title) else stringResource(R.string.schedule_new_task),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MuseTextField(value = name, onValueChange = {
                    name = it
                }, label = { Text(stringResource(R.string.schedule_task_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                MuseTextField(value = prompt, onValueChange = {
                    prompt = it
                }, label = {
                    Text(stringResource(R.string.schedule_task_content))
                }, placeholder = {
                    Text(
                        stringResource(R.string.schedule_content_placeholder),
                    )
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp))
                AssistantSelector(
                    selectedName = selectedAssistantName,
                    assistants = assistants,
                    onSelect = { assistantId = it },
                )
                // v1.0.28: Utilice FlowRow para el ajuste automático de líneas en su lugar,Evite que 5 opciones se queden sin espacio en la fila horizontal
                // causa"Personalizar Cron"El texto de igual longitud se contrae verticalmente.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        "once" to stringResource(R.string.schedule_interval_once),
                        "hourly" to stringResource(R.string.schedule_interval_hourly),
                        "daily" to stringResource(R.string.schedule_interval_daily),
                        "weekly" to stringResource(R.string.schedule_interval_weekly),
                        "cron" to stringResource(R.string.schedule_interval_cron),
                    ).forEach { (value, label) ->
                        Surface(
                            shape = MuseShapes.small,
                            color = if (interval == value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable(
                                role = Role.Button,
                            ) {
                                interval = value
                            },
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (interval == value) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                if (interval == "cron") {
                    MuseTextField(value = cronExpr, onValueChange = {
                        cronExpr = it
                    }, label = {
                        Text(stringResource(R.string.schedule_cron_label))
                    }, placeholder = {
                        Text(
                            stringResource(R.string.schedule_cron_placeholder),
                        )
                    }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            stringResource(R.string.schedule_preset_workday_9) to "0 9 * * 1-5",
                            stringResource(R.string.schedule_preset_daily_8) to "0 8 * * *",
                            stringResource(R.string.schedule_interval_hourly) to "0 * * * *",
                            stringResource(R.string.schedule_preset_midnight) to "0 0 * * *",
                        ).forEach { (label, expr) ->
                            Surface(
                                shape = MuseShapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.clickable(
                                    role = Role.Button,
                                ) {
                                    cronExpr = expr
                                },
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                    if (cronExpr.isNotBlank()) {
                        val desc = remember(cronExpr) { describeCron(cronExpr.trim(), context.resources) } // Correcciones en la interfaz (i18n-5)
                        if (desc != null) {
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (previewText != null) {
                    Text(
                        previewText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                // v1.137: Configuración automatizada avanzada
                MuseCapsuleButton(
                    text = stringResource(if (showAdvanced) R.string.schedule_hide_advanced else R.string.schedule_show_advanced),
                    onClick = { showAdvanced = !showAdvanced },
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
                if (showAdvanced) {
                    AutomationConditionSection(
                        conditionType = conditionType,
                        onConditionTypeChange = { conditionType = it },
                        keyword = conditionKeyword,
                        onKeywordChange = { conditionKeyword = it },
                        tag = conditionTag,
                        onTagChange = { conditionTag = it },
                        startHour = conditionStartHour,
                        onStartHourChange = { conditionStartHour = it },
                        endHour = conditionEndHour,
                        onEndHourChange = { conditionEndHour = it },
                    )
                    AutomationActionSection(
                        actionType = actionType,
                        onActionTypeChange = { actionType = it },
                        noteTitle = noteTitle,
                        onNoteTitleChange = { noteTitle = it },
                        noteContent = noteContent,
                        onNoteContentChange = { noteContent = it },
                        noteTags = noteTags,
                        onNoteTagsChange = { noteTags = it },
                        toolId = toolId,
                        onToolIdChange = { toolId = it },
                        toolParams = toolParams,
                        onToolParamsChange = { toolParams = it },
                        notifyTitle = notifyTitle,
                        onNotifyTitleChange = { notifyTitle = it },
                        notifyMessage = notifyMessage,
                        onNotifyMessageChange = { notifyMessage = it },
                    )
                    AutomationChainSection(
                        candidates = chainCandidates,
                        selectedIds = nextTaskIds,
                        onSelectionChange = { newIds ->
                            // v1.0.17: Detección de bucle de tareas en cadena — Compruebe si se formará un bucle al seleccionar una nueva tarea
                            val currentTaskId = existingTask?.id ?: ""
                            val addedId = newIds.firstOrNull { it !in nextTaskIds }
                            if (addedId != null && currentTaskId.isNotBlank() &&
                                hasChainCycle(addedId, currentTaskId, allTasksMap)
                            ) {
                                MuseToast.show(context.getString(R.string.schedule_chain_cycle_error)) // Correcciones en la interfaz (i18n-5)
                            } else {
                                nextTaskIds = newIds
                            }
                        },
                    )
                }

                errorMessage?.let { msg ->
                    Text(text = msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmText = if (isEdit) stringResource(R.string.schedule_save) else stringResource(R.string.schedule_create_btn),
        onConfirm = {
            if (name.isBlank()) {
                errorMessage = errNameRequired
                return@MuseDialog
            }
            if (prompt.isBlank()) {
                errorMessage = errContentRequired
                return@MuseDialog
            }
            // U-13: CALL_TOOL Comprobación previa de la acción — El ID de herramienta no está vacío + los parámetros deben ser legales JSON
            if (actionType == AutomationConfig.Action.CALL_TOOL) {
                if (toolId.isBlank()) {
                    errorMessage = context.getString(R.string.schedule_err_tool_id_required)
                    return@MuseDialog
                }
                val parseError = runCatching {
                    AppJson.decodeFromString(kotlinx.serialization.json.JsonElement.serializer(), toolParams)
                }.exceptionOrNull()
                if (parseError != null) {
                    errorMessage = context.getString(
                        R.string.schedule_err_tool_params_invalid,
                        parseError.message ?: "JSON error de sintaxis",
                    )
                    return@MuseDialog
                }
            }
            val now = System.currentTimeMillis()
            val cronValue = if (interval == "cron") cronExpr.trim() else ""
            if (interval == "cron") {
                // cron Si la expresión es ilegal, aún así debe bloquearse antes de guardarla.;ScheduledTaskRunner Los valores ilegales sólo se degradan a
                // "No hay próxima ejecución",El guardado silencioso convierte los errores de sintaxis del usuario en una tarea que se ejecuta una vez y desaparece.
                val parsed = runCatching { CronExpression.parse(cronValue) }.getOrNull()
                if (parsed == null) {
                    errorMessage = errCronInvalid
                    return@MuseDialog
                }
            }
            val nextRun = ScheduledTaskRunner.initialNextRunAt(interval, cronValue, now)

            val conditionConfig = when (conditionType) {
                AutomationConfig.Condition.TIME_RANGE -> JsonObject(
                    mapOf(
                        "startHour" to kotlinx.serialization.json.JsonPrimitive(conditionStartHour.toIntOrNull() ?: 0),
                        "endHour" to kotlinx.serialization.json.JsonPrimitive(conditionEndHour.toIntOrNull() ?: 23),
                    ),
                )
                AutomationConfig.Condition.QUICK_NOTE_EXISTS -> JsonObject(
                    mapOf(
                        "tag" to kotlinx.serialization.json.JsonPrimitive(conditionTag),
                        "keyword" to kotlinx.serialization.json.JsonPrimitive(conditionKeyword),
                    ),
                )
                AutomationConfig.Condition.CONTAINS -> JsonObject(
                    mapOf("keyword" to kotlinx.serialization.json.JsonPrimitive(conditionKeyword)),
                )
                else -> JsonObject(emptyMap())
            }
            val condition = AutomationConfig.Condition(type = conditionType, config = conditionConfig)

            val actionConfig = when (actionType) {
                AutomationConfig.Action.CREATE_QUICK_NOTE -> JsonObject(
                    mapOf(
                        "title" to kotlinx.serialization.json.JsonPrimitive(noteTitle),
                        "content" to kotlinx.serialization.json.JsonPrimitive(noteContent),
                        "tags" to kotlinx.serialization.json.JsonPrimitive(noteTags),
                    ),
                )
                AutomationConfig.Action.CALL_TOOL -> JsonObject(
                    mapOf(
                        "toolId" to kotlinx.serialization.json.JsonPrimitive(toolId),
                        "params" to runCatching {
                            AppJson.decodeFromString(kotlinx.serialization.json.JsonElement.serializer(), toolParams)
                        }.getOrElse { JsonObject(emptyMap()) },
                    ),
                )
                AutomationConfig.Action.NOTIFY -> JsonObject(
                    mapOf(
                        "title" to kotlinx.serialization.json.JsonPrimitive(notifyTitle),
                        "message" to kotlinx.serialization.json.JsonPrimitive(notifyMessage),
                    ),
                )
                else -> JsonObject(emptyMap())
            }
            val action = AutomationConfig.Action(type = actionType, config = actionConfig)

            errorMessage = null
            val id = existingTask?.id ?: "task-$now"
            onSave(
                ScheduledTaskEntity(
                    id = id,
                    name = name.trim(),
                    prompt = prompt.trim(),
                    assistantId = assistantId,
                    interval = interval,
                    cronExpr = cronValue,
                    enabled = existingTask?.enabled ?: true,
                    nextRunAt = nextRun,
                    lastRunAt = existingTask?.lastRunAt ?: 0,
                    createdAt = existingTask?.createdAt ?: now,
                    updatedAt = now,
                    dedicatedSessionId = existingTask?.dedicatedSessionId ?: "",
                    conditionJson = condition.toJson(),
                    actionType = action.type,
                    actionConfigJson = action.toJson(),
                    nextTaskIdsJson = nextTaskIds.toIdsJson(),
                    parentTaskId = existingTask?.parentTaskId ?: "",
                    retryCount = existingTask?.retryCount ?: 0,
                    maxRetries = existingTask?.maxRetries ?: 3,
                ),
            )
        },
        dismissText = stringResource(R.string.schedule_cancel),
        onDismiss = { dismissWithCheck() },
    )
    if (showDiscardConfirm) {
        MuseDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = stringResource(R.string.schedule_unsaved_title),
            content = { Text(text = stringResource(R.string.schedule_unsaved_message), style = MaterialTheme.typography.bodyMedium) },
            confirmText = stringResource(R.string.schedule_discard),
            onConfirm = {
                showDiscardConfirm = false
                onDismiss()
            },
            dismissText = stringResource(R.string.schedule_cancel),
            onDismiss = { showDiscardConfirm = false },
            destructive = true,
        )
    }
}

/**
 * Selector de ayuda:Mostrar el nombre del asistente seleccionado actualmente,Haga clic para expandir la selección del menú desplegable,Vincular ID del asistente.
 */
@Composable
private fun AssistantSelector(selectedName: String, assistants: List<AssistantEntity>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = MuseShapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().clickable { expanded = true },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.schedule_assistant),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    selectedName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    MuseIcons.chevronDown,
                    stringResource(R.string.schedule_select_assistant),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        MuseAnchoredMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (assistants.isEmpty()) {
                MuseListItem(
                    onClick = { expanded = false },
                    headlineContent = { Text(stringResource(R.string.schedule_no_assistants)) },
                )
            } else {
                assistants.forEach { a ->
                    MuseListItem(
                        onClick = {
                            onSelect(a.id)
                            expanded = false
                        },
                        headlineContent = {
                            Column {
                                Text(a.name.ifBlank { a.id })
                                // v1.0.17: Etiqueta de capacidad — Nombre del modelo + número de herramientas de encuadernación.
                                val toolCount = runCatching {
                                    AppJson.decodeFromString<List<String>>(a.toolIdsJson).size
                                }.getOrNull() ?: 0
                                val modelLabel = a.modelId ?: stringResource(R.string.schedule_default_model) // Correcciones en la interfaz (i18n-5)
                                Text(
                                    stringResource(R.string.schedule_assistant_tools, modelLabel, toolCount), // Correcciones en la interfaz (i18n-5)
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * v1.137: Área de configuración del disparador condicional.
 *
 * Admite seleccionar tipos de condiciones y completar los parámetros correspondientes:
 *  - always: Ejecutar incondicionalmente
 *  - network_available: Ejecutado cuando la red está disponible.
 *  - time_range: Ejecutar dentro del rango de horas especificado
 *  - contains: task.prompt Ejecutado cuando se incluye la palabra clave especificada
 *  - quick_note_exists: Existe una etiqueta coincidente/Ejecutado durante la grabación rápida de palabras clave.
 */
@Composable
private fun AutomationConditionSection(
    conditionType: String,
    onConditionTypeChange: (String) -> Unit,
    keyword: String,
    onKeywordChange: (String) -> Unit,
    tag: String,
    onTagChange: (String) -> Unit,
    startHour: String,
    onStartHourChange: (String) -> Unit,
    endHour: String,
    onEndHourChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.schedule_condition_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                AutomationConfig.Condition.ALWAYS to stringResource(R.string.schedule_condition_type_always),
                AutomationConfig.Condition.NETWORK_AVAILABLE to stringResource(R.string.schedule_condition_type_network_available),
                AutomationConfig.Condition.TIME_RANGE to stringResource(R.string.schedule_condition_type_time_range),
                AutomationConfig.Condition.CONTAINS to stringResource(R.string.schedule_condition_type_contains),
                AutomationConfig.Condition.QUICK_NOTE_EXISTS to stringResource(R.string.schedule_condition_type_quick_note_exists),
            ).forEach { (value, label) ->
                Surface(
                    shape = MuseShapes.small,
                    color = if (conditionType == value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable(role = Role.Button) { onConditionTypeChange(value) },
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (conditionType == value) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        when (conditionType) {
            AutomationConfig.Condition.TIME_RANGE -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    MuseTextField(
                        value = startHour,
                        onValueChange = { onStartHourChange(it.filter { c -> c.isDigit() }.take(2)) },
                        label = { Text(stringResource(R.string.schedule_condition_start_hour)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    MuseTextField(
                        value = endHour,
                        onValueChange = { onEndHourChange(it.filter { c -> c.isDigit() }.take(2)) },
                        label = { Text(stringResource(R.string.schedule_condition_end_hour)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            AutomationConfig.Condition.CONTAINS -> {
                MuseTextField(
                    value = keyword,
                    onValueChange = onKeywordChange,
                    label = { Text(stringResource(R.string.schedule_condition_keyword)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AutomationConfig.Condition.QUICK_NOTE_EXISTS -> {
                MuseTextField(
                    value = tag,
                    onValueChange = onTagChange,
                    label = { Text(stringResource(R.string.schedule_condition_tag)) },
                    placeholder = { Text(stringResource(R.string.schedule_condition_tag_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MuseTextField(
                    value = keyword,
                    onValueChange = onKeywordChange,
                    label = { Text(stringResource(R.string.schedule_condition_keyword)) },
                    placeholder = { Text(stringResource(R.string.schedule_condition_keyword_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * v1.137: Área de configuración de acciones.
 *
 * Admite seleccionar tipos de acciones y completar los parámetros correspondientes:
 *  - ai_prompt: Llame a AI para ejecutar prompt(Predeterminado)
 *  - create_quick_note: Crea un registro rápido
 *  - call_tool: Llame a la herramienta especificada
 *  - notify: Enviar notificación
 */
@Composable
private fun AutomationActionSection(
    actionType: String,
    onActionTypeChange: (String) -> Unit,
    noteTitle: String,
    onNoteTitleChange: (String) -> Unit,
    noteContent: String,
    onNoteContentChange: (String) -> Unit,
    noteTags: String,
    onNoteTagsChange: (String) -> Unit,
    toolId: String,
    onToolIdChange: (String) -> Unit,
    toolParams: String,
    onToolParamsChange: (String) -> Unit,
    notifyTitle: String,
    onNotifyTitleChange: (String) -> Unit,
    notifyMessage: String,
    onNotifyMessageChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.schedule_action_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                AutomationConfig.Action.AI_PROMPT to stringResource(R.string.schedule_action_type_ai_prompt),
                AutomationConfig.Action.CREATE_QUICK_NOTE to stringResource(R.string.schedule_action_type_create_quick_note),
                AutomationConfig.Action.CALL_TOOL to stringResource(R.string.schedule_action_type_call_tool),
                AutomationConfig.Action.NOTIFY to stringResource(R.string.schedule_action_type_notify),
            ).forEach { (value, label) ->
                Surface(
                    shape = MuseShapes.small,
                    color = if (actionType == value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable(role = Role.Button) { onActionTypeChange(value) },
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (actionType == value) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        when (actionType) {
            AutomationConfig.Action.CREATE_QUICK_NOTE -> {
                MuseTextField(
                    value = noteTitle,
                    onValueChange = onNoteTitleChange,
                    label = { Text(stringResource(R.string.schedule_action_note_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MuseTextField(
                    value = noteContent,
                    onValueChange = onNoteContentChange,
                    label = { Text(stringResource(R.string.schedule_action_note_content)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                )
                MuseTextField(
                    value = noteTags,
                    onValueChange = onNoteTagsChange,
                    label = { Text(stringResource(R.string.schedule_action_note_tags)) },
                    placeholder = { Text(stringResource(R.string.schedule_action_note_tags_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AutomationConfig.Action.CALL_TOOL -> {
                MuseTextField(
                    value = toolId,
                    onValueChange = onToolIdChange,
                    label = { Text(stringResource(R.string.schedule_action_tool_id)) },
                    placeholder = { Text(stringResource(R.string.schedule_action_tool_id_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MuseTextField(
                    value = toolParams,
                    onValueChange = onToolParamsChange,
                    label = { Text(stringResource(R.string.schedule_action_tool_params)) },
                    placeholder = { Text(stringResource(R.string.schedule_action_tool_params_placeholder)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                )
                // U-13: Plantillas de llenado rápido de herramientas comunes(Verifique el ID de la herramienta Autocompletar y los parámetros de muestra)
                var showToolTemplates by remember { mutableStateOf(false) }
                Box {
                    Surface(
                        shape = MuseShapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable { showToolTemplates = true },
                    ) {
                        Text(
                            text = stringResource(R.string.schedule_tool_templates_title),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    MuseAnchoredMenu(
                        expanded = showToolTemplates,
                        onDismissRequest = { showToolTemplates = false },
                    ) {
                        TOOL_TEMPLATES.forEach { t ->
                            MuseListItem(
                                onClick = {
                                    onToolIdChange(t.id)
                                    onToolParamsChange(t.paramsJson)
                                    showToolTemplates = false
                                },
                                headlineContent = { Text(t.label) },
                            )
                        }
                    }
                }
                // U-13: Descripción de la herramienta avanzada,Guíe a los usuarios para que prioricen el uso de descripciones de IA
                Text(
                    text = stringResource(R.string.schedule_tool_advanced_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            AutomationConfig.Action.NOTIFY -> {
                MuseTextField(
                    value = notifyTitle,
                    onValueChange = onNotifyTitleChange,
                    label = { Text(stringResource(R.string.schedule_action_notify_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MuseTextField(
                    value = notifyMessage,
                    onValueChange = onNotifyMessageChange,
                    label = { Text(stringResource(R.string.schedule_action_notify_message)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                )
            }
        }
    }
}

/**
 * v1.0.17: Compruebe si puede regresar comenzando desde taskId y siguiendo la cadena nextTaskIdsJson targetId(formar un anillo).
 *
 * DFS Atravesar cadenas de tareas posteriores,Si encuentra targetId en el camino, significa que unirse a la tarea formará una cadena circular.
 */
private fun hasChainCycle(
    taskId: String,
    targetId: String,
    allTasks: Map<String, ScheduledTaskEntity>,
    visited: MutableSet<String> = mutableSetOf(),
): Boolean {
    if (taskId == targetId) return true
    if (taskId in visited) return false
    visited.add(taskId)
    val task = allTasks[taskId] ?: return false
    val nextIds = task.nextTaskIdsJson.toIdsList()
    for (nextId in nextIds) {
        if (hasChainCycle(nextId, targetId, allTasks, visited)) return true
    }
    return false
}

/**
 * v1.137: Área de configuración de tareas en cadena.
 *
 * Selección múltiple de tareas posteriores de la lista de tareas candidatas,La ejecución exitosa de la tarea actual desencadenará la ejecución inmediata de estas tareas.
 */
@Composable
private fun AutomationChainSection(candidates: List<TaskIdName>, selectedIds: List<String>, onSelectionChange: (List<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.schedule_chain_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (candidates.isEmpty()) {
            Text(
                stringResource(R.string.schedule_chain_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        } else {
            candidates.forEach { candidate ->
                val selected = selectedIds.contains(candidate.id)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectionChange(if (selected) selectedIds - candidate.id else selectedIds + candidate.id) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MuseSwitch(checked = selected, onCheckedChange = null)
                    Spacer(Modifier.size(12.dp))
                    Text(candidate.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ── estado de alerta de fondo ─────────────────────────────────────────────────────
// Cuando la tarea programada "no se ejecutó ni una sola vez",No quedará ningún rastro en el historial de ejecución de la propia aplicación.:
// sin despertar → Sin registro de ejecución → En la página de tareas, el usuario sólo ve que la "Última ejecución" se detuvo hace mucho tiempo.
// Lo único que puede responder "si el sistema ha programado este despertar" es la propia cola de WorkManager.,
// Así que aquí la cola está distribuida tal como está.(Estado/próxima ejecución/motivo del cierre del sistema),Y proporcione una instantánea reproducible de solo texto.
// solo lectura:Sin registro, sin cancelación, sin reprogramación de ninguna tarea.

/**
 * Panel de estado de activación en segundo plano.
 *
 * La fuente de datos es la propia cola de WorkManager.(ver [BackgroundWakeDiagnostics]):Cuando la tarea programada "no se ejecutó ni una sola vez",
 * No quedará ningún rastro en el historial de ejecución de la propia aplicación.,Sólo aquí se puede responder "si el sistema ha programado este despertar".
 */
@Composable
private fun BackgroundWakeSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var reloadKey by remember { mutableStateOf(0) }
    val diagnostics by produceState<WakeDiagnostics?>(initialValue = null, reloadKey) {
        value = BackgroundWakeDiagnostics.collectWithSettings(context)
    }
    MuseBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.wake_diagnostics_title),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = stringResource(R.string.wake_diagnostics_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        val report = diagnostics
        if (report == null) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                MuseSpinner()
            }
        } else {
            WakeDiagnosticsHeader(report)
            Spacer(Modifier.size(8.dp))
            if (report.entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.wake_diagnostics_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                report.entries.forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    WakeEntryRow(entry)
                }
            }
            Spacer(Modifier.size(12.dp))
            MuseCapsuleButton(
                text = stringResource(R.string.wake_diagnostics_copy),
                onClick = { copyWakeSnapshot(context, report) },
                leadingIcon = MuseIcons.copy,
            )
            Spacer(Modifier.size(8.dp))
            MuseCapsuleButton(
                text = stringResource(R.string.wake_diagnostics_refresh),
                onClick = { reloadKey++ },
                variant = IosCapsuleButtonVariant.Secondary,
                leadingIcon = MuseIcons.refresh,
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = stringResource(R.string.wake_diagnostics_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** Principales datos medioambientales:Interruptor de control maestro, exención de optimización de la batería,WorkManager está disponible. */
@Composable
private fun WakeDiagnosticsHeader(report: WakeDiagnostics) {
    val statusColors = MaterialTheme.statusColors
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val masterState = when (report.backgroundWorkEnabled) {
            true -> stringResource(R.string.wake_diagnostics_total_control_on)
            false -> stringResource(R.string.wake_diagnostics_total_control_off)
            null -> stringResource(R.string.wake_diagnostics_total_control_unknown)
        }
        Text(
            text = stringResource(R.string.wake_diagnostics_total_control, masterState),
            style = MaterialTheme.typography.bodySmall,
            color = if (report.backgroundWorkEnabled == false) {
                statusColors.warning
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            text = stringResource(
                R.string.wake_diagnostics_battery,
                stringResource(
                    if (report.batteryOptimizationExempt) {
                        R.string.wake_diagnostics_granted
                    } else {
                        R.string.wake_diagnostics_denied
                    },
                ),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        report.error?.let { message ->
            Text(
                text = stringResource(R.string.wake_diagnostics_read_failed, message),
                style = MaterialTheme.typography.bodySmall,
                color = statusColors.error,
            )
        }
    }
}

/** Tarea única:Tipo + estado + siguiente ejecución + número de reintentos + motivo del apagado del sistema. */
@Composable
private fun WakeEntryRow(entry: WakeWorkEntry) {
    val statusColors = MaterialTheme.statusColors
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = wakeKindLabel(entry.kind),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = wakeStateLabel(entry.state),
                style = MaterialTheme.typography.labelMedium,
                color = wakeStateColor(entry.state, statusColors),
            )
        }
        Text(
            text = entry.uniqueName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                R.string.wake_diagnostics_next_run,
                formatWakeTime(entry.nextRunAtMillis) ?: stringResource(R.string.wake_diagnostics_next_run_none),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val extras = buildList {
            add(stringResource(R.string.wake_diagnostics_attempts, entry.runAttemptCount))
            if (entry.historyCount > 1) add(stringResource(R.string.wake_diagnostics_history, entry.historyCount - 1))
            if (entry.constrained) add(stringResource(R.string.wake_diagnostics_constrained))
        }
        Text(
            text = extras.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        if (entry.stopReason != WakeStopReason.NOT_STOPPED) {
            Text(
                text = stringResource(
                    R.string.wake_diagnostics_stop_reason,
                    wakeStopReasonLabel(entry.stopReason),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.stopReason.isExternalLimit) {
                    statusColors.warning
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun wakeKindLabel(kind: WakeWorkKind): String = when (kind) {
    WakeWorkKind.SCHEDULED_TASK -> stringResource(R.string.wake_kind_scheduled_task)
    WakeWorkKind.PROACTIVE -> stringResource(R.string.wake_kind_proactive)
    WakeWorkKind.DAILY_SUMMARY -> stringResource(R.string.wake_kind_daily_summary)
    WakeWorkKind.CLOUD_BACKUP -> stringResource(R.string.wake_kind_cloud_backup)
    WakeWorkKind.AUTO_BACKUP -> stringResource(R.string.wake_kind_auto_backup)
    WakeWorkKind.STATS_CACHE -> stringResource(R.string.wake_kind_stats_cache)
}

@Composable
private fun wakeStateLabel(state: WakeState): String = when (state) {
    WakeState.ENQUEUED -> stringResource(R.string.wake_state_enqueued)
    WakeState.RUNNING -> stringResource(R.string.wake_state_running)
    WakeState.BLOCKED -> stringResource(R.string.wake_state_blocked)
    WakeState.SUCCEEDED -> stringResource(R.string.wake_state_succeeded)
    WakeState.FAILED -> stringResource(R.string.wake_state_failed)
    WakeState.CANCELLED -> stringResource(R.string.wake_state_cancelled)
    WakeState.NOT_REGISTERED -> stringResource(R.string.wake_state_not_registered)
    WakeState.UNKNOWN -> stringResource(R.string.wake_state_unknown)
}

@Composable
private fun wakeStopReasonLabel(reason: WakeStopReason): String = when (reason) {
    WakeStopReason.NOT_STOPPED -> stringResource(R.string.wake_stop_not_stopped)
    WakeStopReason.UNKNOWN -> stringResource(R.string.wake_stop_unknown)
    WakeStopReason.CANCELLED_BY_APP -> stringResource(R.string.wake_stop_cancelled_by_app)
    WakeStopReason.PREEMPT -> stringResource(R.string.wake_stop_preempt)
    WakeStopReason.TIMEOUT -> stringResource(R.string.wake_stop_timeout)
    WakeStopReason.DEVICE_STATE -> stringResource(R.string.wake_stop_device_state)
    WakeStopReason.CONSTRAINT_BATTERY -> stringResource(R.string.wake_stop_battery)
    WakeStopReason.CONSTRAINT_CHARGING -> stringResource(R.string.wake_stop_charging)
    WakeStopReason.CONSTRAINT_NETWORK -> stringResource(R.string.wake_stop_network)
    WakeStopReason.DEVICE_IDLE -> stringResource(R.string.wake_stop_device_idle)
    WakeStopReason.CONSTRAINT_STORAGE -> stringResource(R.string.wake_stop_storage)
    WakeStopReason.QUOTA -> stringResource(R.string.wake_stop_quota)
    WakeStopReason.BACKGROUND_RESTRICTION -> stringResource(R.string.wake_stop_background_restriction)
    WakeStopReason.APP_STANDBY -> stringResource(R.string.wake_stop_app_standby)
    WakeStopReason.USER -> stringResource(R.string.wake_stop_user)
    WakeStopReason.SYSTEM_PROCESSING -> stringResource(R.string.wake_stop_system_processing)
    WakeStopReason.ESTIMATED_APP_LAUNCH_TIME_CHANGED -> stringResource(R.string.wake_stop_launch_time)
}

private fun wakeStateColor(state: WakeState, colors: MuseStatusColors): Color = when (state) {
    WakeState.ENQUEUED -> colors.info
    WakeState.RUNNING -> colors.success
    WakeState.BLOCKED -> colors.warning
    WakeState.SUCCEEDED -> colors.success
    WakeState.FAILED -> colors.error
    WakeState.CANCELLED -> colors.neutral
    WakeState.NOT_REGISTERED -> colors.error
    WakeState.UNKNOWN -> colors.neutral
}

/** La hora local mostrada al usuario en el panel.;El tiempo en la instantánea está dado por [BackgroundWakeDiagnostics.formatSnapshot] Salida en formato fijo. */
private fun formatWakeTime(millis: Long?): String? = millis?.let { at ->
    SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, Locale.getDefault()).format(Date(at))
}

private fun copyWakeSnapshot(context: Context, report: WakeDiagnostics) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(
        ClipData.newPlainText("Muse Wake Snapshot", BackgroundWakeDiagnostics.formatSnapshot(report)),
    )
    MuseToast.show(context.getString(R.string.wake_diagnostics_copied))
}
