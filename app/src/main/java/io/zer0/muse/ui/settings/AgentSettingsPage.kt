package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.ProactiveMessageConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.proactive.ProactivePace
import io.zer0.muse.data.proactive.QuietHoursWindow
import io.zer0.muse.schedule.DailySummaryWorker
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseSlider
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.Locale

/**
 * v1.27: Agent Configurar la página secundaria.
 *
 * Centralice todas las configuraciones relacionadas con el agente:
 *  - Agent Selección de asistente predeterminada(Qué asistente elegir como asistente complementario predeterminado para la pestaña Agente)
 *  - Portal de colaboración multiagente(Saltar a gestión de equipos)
 *  - Configuración de mensajes activos(interruptor + intervalo + desplazamiento aleatorio + especificado Agent)
 *  - Agent preferencias de comportamiento(Saludos/contexto automático, etc.)
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun AgentSettingsPage(
    onBack: () -> Unit,
    onOpenMultiAgentSettings: () -> Unit,
    onOpenAgentDm: () -> Unit = {},
    /** v2.6.7 fix (AUDIT F9): Entrada de la página de gestión de preferencias del usuario. */
    onOpenPreferences: () -> Unit = {},
    /** El área Agente/colaboración está oculta cuando la página de mensaje activo independiente reutiliza el mismo conjunto de componentes de configuración. */
    proactiveOnly: Boolean = false,
    showProactiveSettings: Boolean = false,
) {
    val settings: SettingsRepository = koinInject()
    val assistantRepository: AssistantRepository = koinInject()
    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = null)
    val proactiveConfig by settings.proactiveMessageConfigFlow.collectAsStateWithLifecycle(
        initialValue = ProactiveMessageConfig(),
    )
    // v1.0.72: Pulsador de resumen diario
    val dailySummaryEnabled by settings.dailySummaryEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    // v1.xxx: Período de resumen diario(24 hora por hora)
    val dailySummarySlots by settings.dailySummarySlotsFlow.collectAsStateWithLifecycle(initialValue = listOf(0, 9, 12, 21))
    // v1.xxx: AI Número diario de publicaciones en Momentos(0 = cerrar)
    val dailyMomentCount by settings.dailyMomentCountFlow.collectAsStateWithLifecycle(initialValue = 2)
    // v2.6.7(P0-1): Modo de contenido de Momentos (determina la dinámica/Comentario/Perspectiva generativa del diario)
    val momentContentMode by settings.momentContentModeFlow
        .collectAsStateWithLifecycle(initialValue = io.zer0.muse.data.moment.MomentContentMode.REAL)
    // v1.xxx: Interruptor de control maestro de despacho backend
    val scheduleWorkEnabled by settings.scheduleWorkEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    // v2.x: El interruptor de mantenimiento regresa de la página de memoria(Comportamiento en segundo plano a nivel del sistema)
    val keepAwake by settings.keepAwakeFlow.collectAsStateWithLifecycle(initialValue = false)
    val autoLaunch by settings.autoLaunchFlow.collectAsStateWithLifecycle(initialValue = false)
    val nightPatrolEnabled by settings.nightPatrolEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val multiAgentConfig by settings.multiAgentConfigFlow.collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.data.MultiAgentConfig(),
    )
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // v1.0.72: Envío de prueba de mensaje activo(Evite los desencadenantes repetidos)
    var testSending by remember { mutableStateOf(false) }
    // Mensaje cuando el botón de prueba está bloqueado por "La ronda anterior aún se está generando". Tómelo bien durante el período de combinación,
    // Solo hay alcance de rutina en la devolución de llamada de clic y no se puede obtener stringResource.
    val testAlreadyRunningText = stringResource(R.string.settings_agent_test_already_running)
    // P0: Nivel de actividad/Período de no molestar/Contenido activo/Ventanas emergentes avanzadas
    var showPacePicker by remember { mutableStateOf(false) }
    var showQuietHoursPicker by remember { mutableStateOf(false) }
    var showContentPicker by remember { mutableStateOf(false) }
    var showAdvancedDialog by remember { mutableStateOf(false) }
    // v2.6.9: Reconstrucción proactiva de mensajes —— Panel de contabilidad preocupado
    val proactiveThoughts: io.zer0.muse.data.proactive.ProactiveThoughtRepository = koinInject()
    val proactiveRelations: io.zer0.muse.data.proactive.ProactiveRelationRepository = koinInject()
    val proactiveScheduler: io.zer0.muse.proactive.ProactiveScheduler = koinInject()
    var showLedger by remember { mutableStateOf(false) }
    var ledgerThoughts by remember {
        mutableStateOf<List<io.zer0.muse.data.proactive.PendingThoughtEntity>>(emptyList())
    }
    var ledgerRelation by remember {
        mutableStateOf<io.zer0.muse.data.proactive.ProactiveRelationEntity?>(null)
    }

    var showAssistantPicker by remember { mutableStateOf(false) }
    var showOffsetPicker by remember { mutableStateOf(false) }
    var showGuaranteePicker by remember { mutableStateOf(false) }
    var showSlotsDialog by remember { mutableStateOf(false) }
    // v1.xxx: AI Ventana emergente para seleccionar el número diario de publicaciones en Momentos
    var showMomentCountPicker by remember { mutableStateOf(false) }
    // v2.6.7(P0-1): Selección del modo de contenido de Momentos (realista/basado en personajes)
    var showMomentContentModePicker by remember { mutableStateOf(false) }
    // v1.x: Mantenga las ventanas emergentes de inicio ejecutándose en segundo plano(entrada pasiva)
    var showKeepAliveGuide by remember { mutableStateOf(false) }
    var showTemperaturePicker by remember { mutableStateOf(false) }

    // v2.6.x: Cuando esté abierto todo el día, simplemente escriba "abierto todo el día" en el resumen,Evite mostrar algo sin sentido. 23:00 – 23:00
    // El período de no molestar y el campo de almacenamiento permitidoHourStart/End son complementarios.,
    // Aquí usamos QuietHoursWindow para la conversión.,No más negaciones escritas a mano en esta página.
    val quietHours = QuietHoursWindow.AllowedWindow(
        proactiveConfig.allowedHourStart,
        proactiveConfig.allowedHourEnd,
    ).toQuietHours()
    val quietHoursLabel = if (proactiveConfig.allDayAllowed) {
        stringResource(R.string.settings_agent_all_day_title)
    } else {
        quietHours.label()
    }
    // Permitir que la ventana esté vacía(Mismo comienzo y final)= Nunca envíes mensajes no solicitados. Esta configuración debe estar visible en la página.,
    // De lo contrario, el usuario sólo puede esperar.“Ni un solo mensaje”Descúbrelo tú mismo.
    val allowedWindow = QuietHoursWindow.AllowedWindow(
        proactiveConfig.allowedHourStart,
        proactiveConfig.allowedHourEnd,
    )
    val allowedWindowIsEmpty = !proactiveConfig.allDayAllowed && allowedWindow.isEmpty
    val contentSummary = buildList {
        if (dailySummaryEnabled) add(stringResource(R.string.settings_agent_content_daily_summary))
        if (nightPatrolEnabled) add(stringResource(R.string.settings_agent_content_night_diary))
        if (dailyMomentCount > 0) add(stringResource(R.string.settings_agent_content_moment))
    }
    val contentLabel = if (contentSummary.isEmpty()) {
        stringResource(R.string.settings_agent_content_none)
    } else {
        contentSummary.joinToString(" / ")
    }

    // v2.6.9: Los datos del libro mayor se actualizan cuando se abre el panel; al mismo tiempo, se eliminan la cuota, el nivel y el tiempo de reutilización del día actual.
    // Para uso en filas y paneles de entrada. Póngalo en LaunchedEffect en lugar de leer la biblioteca cada vez que se reorganiza.
    // Esto se debe a que abarca tres tablas y no debe volver a ejecutarse cada vez que se presiona una tecla en el cuadro de entrada.
    LaunchedEffect(showLedger, proactiveConfig.enabled) {
        if (!proactiveConfig.enabled) return@LaunchedEffect
        runCatching {
            val assistantId = proactiveConfig.agentId.takeIf { it.isNotBlank() } ?: "default"
            ledgerRelation = proactiveRelations.getOrCreate(assistantId, System.currentTimeMillis())
            ledgerThoughts = proactiveThoughts.getOpen(assistantId)
        }
    }

    if (showLedger) {
        val now = System.currentTimeMillis()
        val relation = ledgerRelation
        ProactiveLedgerSheet(
            state = LedgerUiState(
                thoughts = ledgerThoughts,
                intensity = relation?.intensityEnum()
                    ?: io.zer0.muse.data.proactive.ProactiveIntensity.DEFAULT,
                remainingBudget = relation?.let {
                    io.zer0.muse.data.proactive.ProactiveTimingEngine.remainingBudget(it)
                } ?: 0f,
                cooldownUntil = relation?.cooldownUntil ?: 0L,
                now = now,
            ),
            onIntensityChange = { level ->
                scope.launch {
                    val assistantId = proactiveConfig.agentId.takeIf { it.isNotBlank() } ?: "default"
                    proactiveRelations.setIntensity(assistantId, level)
                    ledgerRelation = proactiveRelations.getOrCreate(assistantId, System.currentTimeMillis())
                }
            },
            onResetIntensity = {
                scope.launch {
                    val assistantId = proactiveConfig.agentId.takeIf { it.isNotBlank() } ?: "default"
                    val today =
                        java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                    proactiveRelations.resetIntensity(assistantId, today)
                    ledgerRelation = proactiveRelations.getOrCreate(assistantId, System.currentTimeMillis())
                }
            },
            onCooldown = {
                scope.launch {
                    val assistantId = proactiveConfig.agentId.takeIf { it.isNotBlank() } ?: "default"
                    proactiveRelations.startCooldown(assistantId, System.currentTimeMillis())
                    ledgerRelation = proactiveRelations.getOrCreate(assistantId, System.currentTimeMillis())
                }
            },
            onClearCooldown = {
                scope.launch {
                    val assistantId = proactiveConfig.agentId.takeIf { it.isNotBlank() } ?: "default"
                    proactiveRelations.clearCooldown(assistantId)
                    ledgerRelation = proactiveRelations.getOrCreate(assistantId, System.currentTimeMillis())
                }
            },
            onDismiss = { showLedger = false },
        )
    }

    SettingsSubPageScaffold(
        title = if (proactiveOnly) {
            stringResource(
                R.string.settings_agent_proactive_title,
            )
        } else {
            stringResource(R.string.settings_agent_page_title)
        },
        onBack = onBack,
    ) {
        if (!proactiveOnly) {
            // ── Agent Asistente predeterminado ──
            item { SectionLabel(stringResource(R.string.settings_agent_section_assistant)) }
            item {
                SettingsGroup(
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    val currentAgent = assistants?.find { it.id == proactiveConfig.agentId }
                        ?: assistants?.firstOrNull { it.id == "default" }
                        ?: assistants?.firstOrNull()
                    SettingsItemRow(
                        icon = MuseIcons.user,
                        title = stringResource(R.string.settings_agent_default_assistant_title),
                        subtitle = if (assistants == null) {
                            stringResource(
                                R.string.settings_agent_loading,
                            )
                        } else {
                            currentAgent?.name ?: stringResource(R.string.settings_agent_use_first_assistant)
                        },
                        onClick = { showAssistantPicker = true },
                    ) {
                        ChevronRight()
                    }
                    // v2.6.7 fix (AUDIT F9): Preferencias del usuario (requisitos a largo plazo recordados por el asistente).
                    // Lectura previa de PreferenceStore/Eliminar/Desactivar todas las posiciones y cero llamadas,
                    // Los usuarios no pueden ver ni eliminar una preferencia que no recuerdan correctamente.
                    SettingsGroupDivider()
                    SettingsItemRow(
                        icon = MuseIcons.check,
                        title = stringResource(R.string.settings_preference_title),
                        subtitle = stringResource(R.string.settings_preference_subtitle),
                        onClick = onOpenPreferences,
                    ) {
                        ChevronRight()
                    }
                }
            }

            // ── Colaboración entre múltiples agentes ──
            item { SectionLabel(stringResource(R.string.settings_agent_multi_agent_section)) }
            item {
                SettingsGroup(
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    SettingsItemRow(
                        icon = MuseIcons.users,
                        title = stringResource(R.string.settings_agent_collab_team),
                        subtitle = if (multiAgentConfig.enabled) {
                            stringResource(R.string.settings_agent_multi_agent_enabled, multiAgentConfig.teams.size)
                        } else {
                            stringResource(R.string.settings_agent_manage_collab_team)
                        },
                        onClick = onOpenMultiAgentSettings,
                    ) {
                        ChevronRight()
                    }
                    SettingsGroupDivider()
                    // v1.126: Agent Entrada a la bandeja de entrada de mensajes privados
                    SettingsItemRow(
                        icon = MuseIcons.mail,
                        title = stringResource(R.string.agent_dm_title),
                        subtitle = stringResource(R.string.settings_agent_dm_subtitle),
                        onClick = onOpenAgentDm,
                    ) {
                        ChevronRight()
                    }
                }
            }
        }
        if (showProactiveSettings) {
            // ── mensajes no solicitados ──
            item { SectionLabel(stringResource(R.string.settings_agent_proactive_section)) }
            item {
                SettingsGroup(
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    SettingsSwitchRow(
                        icon = MuseIcons.bell,
                        title = stringResource(R.string.settings_agent_proactive_title),
                        subtitle = stringResource(R.string.settings_agent_proactive_subtitle),
                        checked = proactiveConfig.enabled,
                        onCheckedChange = { v ->
                            scope.launch { settings.saveProactiveMessageConfig(proactiveConfig.copy(enabled = v)) }
                        },
                    )
                    if (proactiveConfig.enabled) {
                        // P0 Convergencia: Los cuatro parámetros de implementación de "Intervalo/Compensación/Probabilidad/Límite diario" se fusionan en uno
                        // v2.6.9: Entrada de libro mayor —— Haga clic en él para ver en qué está pensando recientemente y cuántas veces puede hablar de ello hoy.
                        // Y un clic para silenciarlo durante 7 días. Esta parte de la interfaz de usuario es cómo se implementa el nuevo mecanismo:
                        // Los usuarios pueden ver "¿Por qué me busca?" y "¿Cuándo se calla?"
                        SettingsGroupDivider()
                        SettingsItemRow(
                            icon = MuseIcons.note,
                            title = stringResource(R.string.settings_proactive_v2_ledger),
                            subtitle = if (ledgerThoughts.isEmpty()) {
                                stringResource(R.string.settings_proactive_v2_empty)
                            } else {
                                stringResource(
                                    R.string.settings_proactive_v2_ledger_sub,
                                    ledgerThoughts.size,
                                )
                            },
                            onClick = { showLedger = true },
                        ) {
                            ChevronRight()
                        }
                        // UI-FIX: Etiqueta de subgrupo —— Originalmente, más de una docena de filas de interruptores estaban dispuestas en el mismo piso y era difícil saber cuáles estaban en un grupo.
                        // P0 Convergencia: Los cuatro parámetros de implementación de "Intervalo/Compensación/Probabilidad/Límite diario" se fusionan en uno
                        // El “nivel de iniciativa” que los usuarios pueden expresar(menos/Estándar/mucho);Compensación aleatoria y la temperatura bajó a "Alta".
                        ProactiveGroupLabel(stringResource(R.string.settings_agent_group_pace))
                        SettingsGroupDivider()
                        SettingsItemRow(
                            icon = MuseIcons.calendarTime,
                            title = stringResource(R.string.settings_proactive_v2_intensity),
                            subtitle = stringResource(
                                when (
                                    ledgerRelation?.intensityEnum()
                                        ?: io.zer0.muse.data.proactive.ProactiveIntensity.DEFAULT
                                ) {
                                    io.zer0.muse.data.proactive.ProactiveIntensity.QUIET ->
                                        R.string.proactive_intensity_quiet
                                    io.zer0.muse.data.proactive.ProactiveIntensity.RESERVED ->
                                        R.string.proactive_intensity_reserved
                                    io.zer0.muse.data.proactive.ProactiveIntensity.STANDARD ->
                                        R.string.proactive_intensity_standard
                                    io.zer0.muse.data.proactive.ProactiveIntensity.ACTIVE ->
                                        R.string.proactive_intensity_active
                                    io.zer0.muse.data.proactive.ProactiveIntensity.CLINGY ->
                                        R.string.proactive_intensity_clingy
                                },
                            ),
                            onClick = { showLedger = true },
                        ) {
                            ChevronRight()
                        }
                        SettingsGroupDivider()
                        // P0 Convergencia: Los dos controles deslizantes de "Permitir inicio/fin del período" se combinan en un rango de "período DND"
                        // Permitir mensaje rojo cuando la ventana esté vacía (mismo inicio y final):Ninguna de esta configuración será enviada.
                        // No dejes que los usuarios esperen hasta enterarse“¿Por qué no hay movimiento?”.
                        SettingsItemRow(
                            icon = MuseIcons.moon,
                            title = stringResource(R.string.settings_agent_quiet_hours_title),
                            subtitle = if (allowedWindowIsEmpty) {
                                quietHoursLabel + " · " +
                                    stringResource(R.string.settings_agent_quiet_hours_empty_warning)
                            } else {
                                quietHoursLabel
                            },
                            onClick = { showQuietHoursPicker = true },
                        ) {
                            ChevronRight()
                        }
                        SettingsGroupDivider()
                        // v2.x: La línea original "Enviar agente" ha sido eliminada. — con "Agent El "Asistente predeterminado" tiene el mismo valor y la misma ventana emergente,Combinados en un solo portal
                        val senderAgent = assistants?.find { it.id == proactiveConfig.agentId }
                        SettingsItemRow(
                            icon = MuseIcons.user,
                            title = stringResource(R.string.settings_agent_default_assistant_title),
                            subtitle = senderAgent?.name ?: stringResource(R.string.settings_agent_default_assistant_fallback),
                            onClick = { showAssistantPicker = true },
                        ) {
                            ChevronRight()
                        }
                        // P0 Convergencia: Temperatura/Probabilidad/Envío de prueba/Sólo sesión de agente Ingrese "Avanzado"
                        SettingsGroupDivider()
                        SettingsItemRow(
                            icon = MuseIcons.robot,
                            title = stringResource(R.string.settings_agent_group_advanced),
                            subtitle = stringResource(R.string.settings_agent_advanced_subtitle),
                            onClick = { showAdvancedDialog = true },
                        ) {
                            ChevronRight()
                        }
                        // P0 Convergencia: Resumen diario/diario nocturno/círculo de amigos están incluidos en la entrada "contenido activo"
                        ProactiveGroupLabel(stringResource(R.string.settings_agent_group_daily))
                        SettingsGroupDivider()
                        SettingsItemRow(
                            icon = MuseIcons.calendarStats,
                            title = stringResource(R.string.settings_agent_content_title),
                            subtitle = contentLabel,
                            onClick = { showContentPicker = true },
                        ) {
                            ChevronRight()
                        }
                    }
                }
            }

            // P0: Partición independiente "backend y confiabilidad" —— El maestro de tareas en segundo plano controla todas las tareas periódicas en segundo plano.,
            // Desacoplado del interruptor de mensaje activo;Mantener el arranque ejecutándose en segundo plano también va aquí.
            item { SectionLabel(stringResource(R.string.settings_agent_group_background)) }
            item {
                SettingsGroup(modifier = Modifier.padding(top = 8.dp)) {
                    SettingsSwitchRow(
                        icon = MuseIcons.switch,
                        title = stringResource(R.string.settings_agent_schedule_work_title),
                        subtitle = stringResource(R.string.settings_agent_schedule_work_subtitle),
                        checked = scheduleWorkEnabled,
                        onCheckedChange = { v ->
                            scope.launch { settings.saveScheduleWorkEnabled(v) }
                        },
                    )
                    SettingsGroupDivider()
                    // v2.x: El interruptor de mantenimiento regresa de la página de memoria(Comportamiento en segundo plano a nivel del sistema)
                    SettingsSwitchRow(
                        icon = MuseIcons.bolt,
                        title = stringResource(R.string.settings_memory_keep_awake),
                        subtitle = stringResource(R.string.settings_memory_keep_awake_subtitle),
                        checked = keepAwake,
                        onCheckedChange = { v ->
                            scope.launch { settings.saveKeepAwake(v) }
                        },
                    )
                    SettingsGroupDivider()
                    SettingsSwitchRow(
                        icon = MuseIcons.power,
                        title = stringResource(R.string.settings_memory_auto_launch),
                        subtitle = stringResource(R.string.settings_memory_auto_launch_subtitle),
                        checked = autoLaunch,
                        onCheckedChange = { v ->
                            scope.launch { settings.saveAutoLaunch(v) }
                        },
                    )
                    SettingsGroupDivider()
                    SettingsItemRow(
                        icon = MuseIcons.lifebuoy,
                        title = stringResource(R.string.settings_agent_keep_alive_title),
                        subtitle = stringResource(R.string.settings_agent_keep_alive_subtitle),
                        onClick = { showKeepAliveGuide = true },
                    ) {
                        ChevronRight()
                    }
                }
            }
        }
    }

    // ── v1.xxx: Ventana emergente de selección de período de resumen diario(Opción múltiple de chip de punto completo,Reemplazar la entrada de coma de texto simple anterior;Todavía almacenado con horas separadas por comas.)──
    if (showSlotsDialog) {
        // Sólo se conservan los valores iniciales legales 0-23;usar remember(no saveable)guardar Set,Evite que los tipos que no son de paquete fallen durante la reconstrucción del proceso
        var selectedSlots by remember { mutableStateOf(dailySummarySlots.filter { it in 0..23 }.toSet()) }
        // Se muestran en grupos según los períodos de tiempo naturales 0-23 Todo a la hora,Centrarse en períodos de tiempo comunes
        val slotGroups = listOf(
            stringResource(R.string.settings_agent_daily_summary_slots_group_early) to listOf(0, 1, 2, 3, 4, 5),
            stringResource(R.string.settings_agent_daily_summary_slots_group_morning) to listOf(6, 7, 8, 9, 10, 11),
            stringResource(R.string.settings_agent_daily_summary_slots_group_noon) to listOf(12, 13, 14, 15, 16, 17),
            stringResource(R.string.settings_agent_daily_summary_slots_group_evening) to listOf(18, 19, 20, 21, 22, 23),
        )
        val hasSelection = selectedSlots.isNotEmpty()
        MuseDialog(
            onDismissRequest = { showSlotsDialog = false },
            title = stringResource(R.string.settings_agent_daily_summary_slots_title),
            confirmEnabled = hasSelection,
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    slotGroups.forEach { (groupLabel, hours) ->
                        Text(
                            text = groupLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            hours.forEach { h ->
                                val selected = h in selectedSlots
                                MuseChip(
                                    selected = selected,
                                    onClick = {
                                        // Haga clic para cambiar instantáneamente el estado seleccionado
                                        selectedSlots = if (selected) selectedSlots - h else selectedSlots + h
                                    },
                                    label = String.format(Locale.getDefault(), "%02d:00", h),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.size(12.dp))
                    // abajo:Aviso rojo cuando no hay selección;De lo contrario, se mostrará la descripción china del período de tiempo seleccionado actualmente.
                    Text(
                        text = if (hasSelection) {
                            stringResource(
                                R.string.settings_agent_daily_summary_slots_selected,
                                selectedSlots.sorted().joinToString(",") {
                                    String.format(Locale.getDefault(), "%02d:00", it)
                                },
                            )
                        } else {
                            stringResource(R.string.settings_agent_daily_summary_slots_empty_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (hasSelection) FontWeight.Medium else FontWeight.SemiBold,
                        color = if (hasSelection) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                // Guardar horas ordenadas separadas por comas,compatibles SettingsRepository.saveDailySummarySlots
                scope.launch {
                    settings.saveDailySummarySlots(selectedSlots.sorted())
                    // Sincronice WorkManager inmediatamente después de los cambios de configuración; de lo contrario, sólo se reprogramará la próxima vez que se inicie el proceso,
                    // La hora anterior continúa y la nueva espera hasta el próximo arranque en frío.
                    DailySummaryWorker.scheduleNext(context)
                }
                showSlotsDialog = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showSlotsDialog = false },
        )
    }

    // ── v1.xxx: AI Ventana emergente para seleccionar el número diario de publicaciones en Momentos(0 = cerrar)──
    if (showMomentCountPicker) {
        var countValue by rememberSaveable { mutableStateOf(dailyMomentCount.coerceIn(0, 5)) }
        val alignedValue = countValue.coerceIn(0, 5)
        MuseDialog(
            onDismissRequest = { showMomentCountPicker = false },
            title = stringResource(R.string.settings_agent_moment_count_title),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (alignedValue <= 0) {
                            stringResource(R.string.settings_agent_moment_count_off)
                        } else {
                            stringResource(R.string.settings_agent_moment_count_per_day, alignedValue)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    MuseSlider(
                        value = alignedValue.toFloat(),
                        onValueChange = { countValue = it.toInt() },
                        valueRange = 0f..5f,
                        // 0..5 6 archivos en total,steps=4 → start/end 4 paradas entre
                        steps = 4,
                        valueFormatter = { if (it.toInt() <= 0) "cerrar" else "${it.toInt()} Artículo/dia" },
                    )
                }
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                scope.launch { settings.saveDailyMomentCount(alignedValue) }
                showMomentCountPicker = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showMomentCountPicker = false },
        )
    }

    // ── v2.6.7(P0-1): Ventana emergente de selección del modo de contenido de Moments (tipo real/personaje)──
    if (showMomentContentModePicker) {
        MuseDialog(
            onDismissRequest = { showMomentContentModePicker = false },
            title = stringResource(R.string.settings_moment_content_mode_title),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_moment_content_mode_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    io.zer0.muse.data.moment.MomentContentMode.entries.forEach { mode ->
                        val selected = momentContentMode == mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { settings.saveMomentContentMode(mode) }
                                    showMomentContentModePicker = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when (mode) {
                                        io.zer0.muse.data.moment.MomentContentMode.REAL ->
                                            stringResource(R.string.settings_moment_content_mode_real)
                                        io.zer0.muse.data.moment.MomentContentMode.CHARACTER ->
                                            stringResource(R.string.settings_moment_content_mode_character)
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = when (mode) {
                                        io.zer0.muse.data.moment.MomentContentMode.REAL ->
                                            stringResource(R.string.settings_moment_content_mode_real_desc)
                                        io.zer0.muse.data.moment.MomentContentMode.CHARACTER ->
                                            stringResource(R.string.settings_moment_content_mode_character_desc)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            if (selected) {
                                Icon(
                                    imageVector = MuseIcons.check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmText = stringResource(R.string.action_close),
            onConfirm = { showMomentContentModePicker = false },
        )
    }

    // ── P0: Ventana emergente de selección de nivel de actividad(menos/Estándar/mucho;Si no es posible empujar hacia atrás, se mostrará la pantalla personalizada.) ──
    if (showPacePicker) {
        val currentPace = ProactivePace.from(
            proactiveConfig.intervalMinutes,
            proactiveConfig.sendProbability,
        )
        MuseDialog(
            onDismissRequest = { showPacePicker = false },
            title = stringResource(R.string.settings_agent_pace_title),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_agent_pace_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    ProactivePace.values().forEach { pace ->
                        val selected = currentPace == pace
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        settings.saveProactiveMessageConfig(
                                            proactiveConfig.copy(
                                                intervalMinutes = pace.intervalMinutes,
                                                sendProbability = pace.sendProbability,
                                                randomOffsetMinutes = proactiveConfig.randomOffsetMinutes
                                                    .coerceIn(0, pace.intervalMinutes),
                                            ),
                                        )
                                    }
                                    showPacePicker = false
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = paceTitle(pace),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = paceSubtitle(pace),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            if (selected) {
                                Icon(
                                    MuseIcons.check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showPacePicker = false },
        )
    }

    // ── P0: Ventana emergente del período No molestar(Iniciar y finalizar dos controles deslizantes) ──
    //
    // Explicación semántica (importante):Esta ventana emergente edita "Tiempo de no molestar",El campo de almacenamiento es "Permitir ventana de envío".,
    // Los dos se complementan. La implementación anterior escribió esta conversión como `startHour = allowedHourEnd` / `allowedHourStart = e`,
    // En dirección opuesta y con ochenta líneas de diferencia.,Al leer el código, es fácil juzgar mal el“doble negación bug”(El viaje de ida y vuelta real es constante),
    // Al realizar cambios, es muy fácil cambiar solo la mitad de la ventana y, de hecho, darle la vuelta a la ventana.
    // Todas las conversiones ahora se recopilan en QuietHoursWindow,Aquí sólo nos ocupamos de la semántica de "no molestar".
    if (showQuietHoursPicker) {
        val storedQuiet = QuietHoursWindow.AllowedWindow(
            proactiveConfig.allowedHourStart,
            proactiveConfig.allowedHourEnd,
        ).toQuietHours()
        var startHour by rememberSaveable { mutableStateOf(storedQuiet.startHour) }
        var endHour by rememberSaveable { mutableStateOf(storedQuiet.endHour) }
        // v2.6.x: Interruptor abierto todo el día.allowedHourStart/End Es un intervalo que está cerrado por la izquierda y abierto por la derecha.,No puedo recuperar 24 horas completas
        // (23-23 = No se envía a nadie en el intervalo vacío.,23-0 = Sólo 23 puntos cubiertos),Así que dale un interruptor principal separado.
        var allDay by rememberSaveable { mutableStateOf(proactiveConfig.allDayAllowed) }
        val s = startHour.coerceIn(0, 23)
        val e = endHour.coerceIn(0, 23)
        MuseDialog(
            onDismissRequest = { showQuietHoursPicker = false },
            title = stringResource(R.string.settings_agent_quiet_hours_title),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Abierto todo el día:Después de la apertura, ya no es necesario seleccionar las horas de inicio y finalización.
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_agent_all_day_title),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(R.string.settings_agent_all_day_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        MuseSwitch(checked = allDay, onCheckedChange = { allDay = it })
                    }
                    if (allDay) {
                        Text(
                            text = stringResource(R.string.settings_agent_all_day_subtitle),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            text = QuietHoursWindow.QuietHours(s, e).label(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        // No molestar empieza y termina igual = Permitir que la ventana esté vacía = Nunca envíes mensajes no solicitados.
                        // Esta combinación podría guardarse silenciosamente antes,Los usuarios sólo verán“Ni un solo mensaje”.
                        if (s == e) {
                            Text(
                                text = stringResource(R.string.settings_agent_quiet_hours_empty_warning),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        Text(
                            text = stringResource(R.string.settings_agent_quiet_hours_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        Text(
                            text = stringResource(R.string.settings_agent_quiet_hours_start),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        MuseSlider(
                            value = s.toFloat(),
                            onValueChange = { startHour = it.toInt() },
                            valueRange = 0f..23f,
                            valueFormatter = { String.format(Locale.getDefault(), "%02d:00", it.toInt()) },
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = stringResource(R.string.settings_agent_quiet_hours_end),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        MuseSlider(
                            value = e.toFloat(),
                            onValueChange = { endHour = it.toInt() },
                            valueRange = 0f..23f,
                            valueFormatter = { String.format(Locale.getDefault(), "%02d:00", it.toInt()) },
                        )
                    }
                }
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                // Escribe directamente la semántica de no molestar;La conversión se entrega a QuietHoursWindow,No más negaciones manuscritas aquí.
                val allowed = QuietHoursWindow.QuietHours(s, e).toAllowed()
                scope.launch {
                    settings.saveProactiveMessageConfig(
                        proactiveConfig.copy(
                            allowedHourStart = allowed.startHour,
                            allowedHourEnd = allowed.endHour,
                            allDayAllowed = allDay,
                        ),
                    )
                }
                showQuietHoursPicker = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showQuietHoursPicker = false },
        )
    }

    // ── P0: Ventanas emergentes de contenido activo(Resumen diario / diario nocturno / círculo de amigos) ──
    if (showContentPicker) {
        MuseDialog(
            onDismissRequest = { showContentPicker = false },
            title = stringResource(R.string.settings_agent_content_title),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_agent_content_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    SettingsGroup {
                        SettingsSwitchRow(
                            icon = MuseIcons.calendarStats,
                            title = stringResource(R.string.settings_agent_content_daily_summary),
                            subtitle = stringResource(R.string.settings_agent_daily_summary_subtitle),
                            checked = dailySummaryEnabled,
                            onCheckedChange = { v ->
                                scope.launch { settings.saveDailySummaryEnabled(v) }
                            },
                        )
                        if (dailySummaryEnabled) {
                            SettingsGroupDivider()
                            SettingsItemRow(
                                icon = MuseIcons.clock,
                                title = stringResource(R.string.settings_agent_daily_summary_slots_title),
                                subtitle = dailySummarySlots.joinToString(" ") { String.format(Locale.getDefault(), "%02d:00", it) },
                                onClick = {
                                    showContentPicker = false
                                    showSlotsDialog = true
                                },
                            ) {
                                ChevronRight()
                            }
                        }
                        SettingsGroupDivider()
                        SettingsSwitchRow(
                            icon = MuseIcons.moon,
                            title = stringResource(R.string.settings_agent_content_night_diary),
                            subtitle = stringResource(R.string.settings_agent_night_patrol_subtitle),
                            checked = nightPatrolEnabled,
                            onCheckedChange = { v ->
                                scope.launch { settings.saveNightPatrolEnabled(v) }
                            },
                        )
                        SettingsGroupDivider()
                        SettingsSwitchRow(
                            icon = MuseIcons.calendarStats,
                            title = stringResource(R.string.settings_agent_content_moment),
                            subtitle = if (dailyMomentCount > 0) {
                                stringResource(R.string.settings_agent_moment_count_per_day, dailyMomentCount)
                            } else {
                                stringResource(R.string.settings_agent_moment_count_off)
                            },
                            checked = dailyMomentCount > 0,
                            onCheckedChange = { v ->
                                scope.launch { settings.saveDailyMomentCount(if (v) 2 else 0) }
                            },
                        )
                        if (dailyMomentCount > 0) {
                            SettingsGroupDivider()
                            SettingsItemRow(
                                icon = MuseIcons.clock,
                                title = stringResource(R.string.settings_agent_moment_count_title),
                                subtitle = stringResource(R.string.settings_agent_moment_count_per_day, dailyMomentCount),
                                onClick = {
                                    showContentPicker = false
                                    showMomentCountPicker = true
                                },
                            ) {
                                ChevronRight()
                            }
                            // v2.6.7(P0-1): Modo de contenido de momentos — Realista/personaje de doble nivel
                            SettingsGroupDivider()
                            SettingsItemRow(
                                icon = MuseIcons.typography,
                                title = stringResource(R.string.settings_moment_content_mode_title),
                                subtitle = when (momentContentMode) {
                                    io.zer0.muse.data.moment.MomentContentMode.REAL ->
                                        stringResource(R.string.settings_moment_content_mode_real_desc)
                                    io.zer0.muse.data.moment.MomentContentMode.CHARACTER ->
                                        stringResource(R.string.settings_moment_content_mode_character_desc)
                                },
                                onClick = {
                                    showContentPicker = false
                                    showMomentContentModePicker = true
                                },
                            ) {
                                ChevronRight()
                            }
                        }
                    }
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showContentPicker = false },
        )
    }

    // ── P0: Ventanas emergentes avanzadas(Solo sesión de agente/Generar temperatura/Compensación aleatoria/Envío de prueba) ──
    if (showAdvancedDialog) {
        MuseDialog(
            onDismissRequest = { showAdvancedDialog = false },
            title = stringResource(R.string.settings_agent_group_advanced),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_agent_advanced_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    SettingsGroup {
                        SettingsSwitchRow(
                            icon = MuseIcons.user,
                            title = stringResource(R.string.settings_agent_agent_session_enabled),
                            subtitle = stringResource(R.string.settings_agent_agent_session_enabled_subtitle),
                            checked = proactiveConfig.agentSessionEnabled,
                            onCheckedChange = { v ->
                                scope.launch {
                                    settings.saveProactiveMessageConfig(
                                        proactiveConfig.copy(agentSessionEnabled = v),
                                    )
                                }
                            },
                        )
                        SettingsGroupDivider()
                        SettingsItemRow(
                            icon = MuseIcons.clock,
                            title = stringResource(R.string.settings_agent_daily_guarantee),
                            subtitle = guaranteeLabel(proactiveConfig.dailyGuaranteeHours),
                            onClick = {
                                showAdvancedDialog = false
                                showGuaranteePicker = true
                            },
                        ) {
                            ChevronRight()
                        }
                        SettingsGroupDivider()
                        SettingsItemRow(
                            icon = MuseIcons.switch,
                            title = stringResource(R.string.settings_agent_temperature),
                            subtitle = "%.1f".format(proactiveConfig.temperature),
                            onClick = {
                                showAdvancedDialog = false
                                showTemperaturePicker = true
                            },
                        ) {
                            ChevronRight()
                        }
                        SettingsGroupDivider()
                        SettingsItemRow(
                            icon = MuseIcons.switch,
                            title = stringResource(R.string.settings_agent_random_offset),
                            subtitle = offsetLabel(proactiveConfig.randomOffsetMinutes),
                            onClick = {
                                showAdvancedDialog = false
                                showOffsetPicker = true
                            },
                        ) {
                            ChevronRight()
                        }
                        SettingsGroupDivider()
                        SettingsItemRow(
                            icon = MuseIcons.bell,
                            title = stringResource(R.string.settings_agent_test_message),
                            subtitle = if (testSending) {
                                stringResource(R.string.settings_agent_test_generating)
                            } else {
                                stringResource(R.string.settings_agent_test_message_subtitle)
                            },
                            onClick = {
                                if (testSending) return@SettingsItemRow
                                testSending = true
                                scope.launch {
                                    try {
                                        // v2.2.1: Mostrar resultados con sinceridad(Incluyendo razones de falla),reemplazar fijo"Enviado"redacción
                                        // v2.6.9: Cambiar a la versión reconstruida del programador —— El viejo Runner ya no se inicia,
                                        // Si continúa señalándolo, el botón de prueba tomará un enlace que en realidad no existe.
                                        val result = proactiveScheduler.runCycle(
                                            io.zer0.muse.proactive.ProactiveScheduler.TRIGGER_MANUAL,
                                            force = true,
                                        )
                                        android.widget.Toast.makeText(
                                            context,
                                            // El motivo para saltarme es "todavía estaba corriendo en la última ronda". Si quieres decirle esto a la gente,
                                            // Utilice recursos en lugar de mostrar frases en chino directamente en el registro.
                                            if (result.alreadyRunning) {
                                                testAlreadyRunningText
                                            } else {
                                                result.detail
                                            },
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    } catch (e: Exception) {
                                        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                                        Logger.w("AgentSettingsPage", "Falló la prueba de mensaje no solicitado: ${e.message}")
                                        android.widget.Toast.makeText(
                                            context,
                                            context.getString(R.string.settings_agent_test_failed, e.message),
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    } finally {
                                        testSending = false
                                    }
                                }
                            },
                        ) {
                            ChevronRight()
                        }
                    }
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showAdvancedDialog = false },
        )
    }

    // ── Ventana emergente de selección de asistente ──
    if (showAssistantPicker) {
        MuseDialog(
            onDismissRequest = { showAssistantPicker = false },
            title = stringResource(R.string.settings_agent_select_assistant_title),
            content = {
                Column {
                    // v1.74: Evitación de captura de variables locales !!(Las propiedades delegadas no pueden smart-cast)
                    val assistantList = assistants
                    when {
                        assistantList == null -> {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(MusePaddings.screen),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MuseSpinner(
                                    size = 20.dp,
                                )
                            }
                        }
                        assistantList.isEmpty() -> {
                            Text(
                                text = stringResource(R.string.settings_agent_no_assistants_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(MusePaddings.screen),
                            )
                        }
                        else -> {
                            assistantList.forEach { assistant ->
                                val selected = assistant.id == proactiveConfig.agentId ||
                                    (proactiveConfig.agentId.isBlank() && assistant.id == "default")
                                AgentPickerRow(
                                    assistant = assistant,
                                    selected = selected,
                                    onClick = {
                                        scope.launch {
                                            settings.saveProactiveMessageConfig(
                                                proactiveConfig.copy(agentId = assistant.id),
                                            )
                                        }
                                        showAssistantPicker = false
                                    },
                                )
                            }
                        }
                    }
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showAssistantPicker = false },
        )
    }

    // ── Ventana emergente de selección de desplazamiento aleatorio(v1.30: Cambiar a la personalización del control deslizante,Duración del paso 15 minutos,alcance 0 ~ valor del intervalo)──
    if (showOffsetPicker) {
        var sliderMinutes by rememberSaveable { mutableStateOf(proactiveConfig.randomOffsetMinutes) }
        val maxOffset = proactiveConfig.intervalMinutes
        // v1.95: Ajuste continuo,Ya no está alineado con pasos de 15 minutos(no más largo que el intervalo actual)
        val alignedMinutes = sliderMinutes.coerceIn(0, maxOffset)
        MuseDialog(
            onDismissRequest = { showOffsetPicker = false },
            title = stringResource(R.string.settings_agent_random_offset),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = offsetLabel(alignedMinutes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_agent_offset_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    MuseSlider(
                        value = alignedMinutes.toFloat(),
                        onValueChange = { sliderMinutes = it.toInt() },
                        valueRange = 0f..maxOffset.toFloat(),
                        // v1.95: Ajuste continuo,eliminar steps(Pasos originales de 15 minutos.)
                        valueFormatter = { "${it.toInt()} min" },
                    )
                    // Etiqueta de referencia de agrupación
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // LAYOUT-01: Las etiquetas de escala en ambos extremos del control deslizante son texto corto con un ancho fijo.(Alinee ambos extremos y entregue SpaceBetween),
                        // Sin ancho,Solo 1 línea evita el ajuste palabra por palabra cuando se amplía el tamaño de fuente.
                        Text(
                            stringResource(R.string.settings_agent_offset_off_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                        )
                        Text(
                            intervalLabel(maxOffset),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                        )
                    }
                }
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                scope.launch {
                    settings.saveProactiveMessageConfig(
                        proactiveConfig.copy(randomOffsetMinutes = alignedMinutes),
                    )
                }
                showOffsetPicker = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showOffsetPicker = false },
        )
    }

    if (showGuaranteePicker) {
        var hours by rememberSaveable { mutableStateOf(proactiveConfig.dailyGuaranteeHours) }
        MuseDialog(
            onDismissRequest = { showGuaranteePicker = false },
            title = stringResource(R.string.settings_agent_daily_guarantee),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = guaranteeLabel(hours),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_agent_daily_guarantee_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    MuseSlider(
                        value = hours.toFloat(),
                        onValueChange = { hours = it.toInt() },
                        valueRange = 0f..48f,
                        valueFormatter = { it.toInt().toString() },
                    )
                }
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                scope.launch {
                    settings.saveProactiveMessageConfig(
                        proactiveConfig.copy(dailyGuaranteeHours = hours),
                    )
                }
                showGuaranteePicker = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showGuaranteePicker = false },
        )
    }

    if (showTemperaturePicker) {
        var sliderValue by rememberSaveable { mutableStateOf(proactiveConfig.temperature) }
        // Alinear a 0,1 pasos:kotlin.math.round(Float) Regresar Float,Evite la ambigüedad de sobrecarga de Math.round
        val alignedValue = (kotlin.math.round(sliderValue * 10f) / 10f).coerceIn(0f, 2f)
        MuseDialog(
            onDismissRequest = { showTemperaturePicker = false },
            title = stringResource(R.string.settings_agent_temperature),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "%.1f".format(alignedValue),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_agent_temperature_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    MuseSlider(
                        value = alignedValue,
                        onValueChange = { sliderValue = it },
                        valueRange = 0f..2f,
                        valueFormatter = { "%.1f".format(it) },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // LAYOUT-01: Igual que arriba,Las marcas de escala en ambos extremos del control deslizante de temperatura están limitadas a 1 línea.
                        Text(
                            stringResource(R.string.settings_agent_temperature_stable),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                        )
                        Text(
                            stringResource(R.string.settings_agent_temperature_creative),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                        )
                    }
                }
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                scope.launch {
                    settings.saveProactiveMessageConfig(proactiveConfig.copy(temperature = alignedValue))
                }
                showTemperaturePicker = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showTemperaturePicker = false },
        )
    }

    // ── v1.x: Sigue arrancando en segundo plano(entrada pasiva,Haga clic para aparecer)──
    if (showKeepAliveGuide) {
        KeepAliveGuideDialog(onDismiss = { showKeepAliveGuide = false })
    }
}

/** Página de configuración de mensajes activos independientes: reutilice la configuración completa de mensajes activos de la página del Agente sin duplicar la lógica de estado. */
@Composable
fun ProactiveMessageSettingsPage(onBack: () -> Unit) {
    AgentSettingsPage(
        onBack = onBack,
        onOpenMultiAgentSettings = {},
        proactiveOnly = true,
        showProactiveSettings = true,
    )
}

/** El asistente selecciona filas:Avatar + Nombre + Instrucciones de radio. */
@Composable
private fun AgentPickerRow(assistant: AssistantEntity, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistantAvatar(
            assistant = assistant,
            avatarSize = 36.dp,
            modifier = Modifier.clip(CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = assistant.name.ifBlank { stringResource(R.string.settings_agent_unnamed_assistant) },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(MuseIcons.check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Convierta los minutos de intervalo en una copia legible.
 * v1.30: Cambiado de unidad de hora a unidad de minutos,Admite granularidad de subdivisión, como 15/30/45 minutos.
 */
@Composable
private fun intervalLabel(minutes: Int): String {
    val m = minutes.coerceAtLeast(15)
    return when {
        m < 60 -> stringResource(R.string.settings_agent_interval_minutes, m)
        m == 60 -> stringResource(R.string.settings_agent_interval_hourly)
        m % 60 == 0 -> {
            val h = m / 60
            if (h == 24) {
                stringResource(R.string.settings_agent_interval_daily)
            } else {
                stringResource(R.string.settings_agent_interval_hours, h)
            }
        }
        else -> {
            val h = m / 60
            val rem = m % 60
            stringResource(R.string.settings_agent_interval_hours_minutes, h, rem)
        }
    }
}

/**
 * Convierta minutos compensados ​​​​al azar en una copia legible.
 * v1.30: Cambiado de unidad de hora a unidad de minutos,Admite granularidad de subdivisión, como 15/30/45 minutos.
 */
@Composable
private fun offsetLabel(minutes: Int): String {
    if (minutes == 0) return stringResource(R.string.settings_agent_offset_disabled)
    return when {
        minutes < 60 -> stringResource(R.string.settings_agent_offset_minutes, minutes)
        minutes % 60 == 0 -> stringResource(R.string.settings_agent_offset_hours, minutes / 60)
        else -> stringResource(R.string.settings_agent_offset_hours_minutes, minutes / 60, minutes % 60)
    }
}


@Composable
private fun guaranteeLabel(hours: Int): String {
    if (hours <= 0) return stringResource(R.string.settings_agent_daily_guarantee_off)
    return stringResource(R.string.settings_agent_daily_guarantee_hours, hours)
}


@Composable
private fun ProactiveGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp),
    )
}

/** P0: Título de engranaje horizontal automático. */
@Composable
private fun paceTitle(pace: ProactivePace): String = when (pace) {
    ProactivePace.LIGHT -> stringResource(R.string.settings_agent_pace_light)
    ProactivePace.STANDARD -> stringResource(R.string.settings_agent_pace_standard)
    ProactivePace.HEAVY -> stringResource(R.string.settings_agent_pace_heavy)
    ProactivePace.EXTREME -> stringResource(R.string.settings_agent_pace_extreme)
}

/** P0: Descripción de marchas de nivel activo. */
@Composable
private fun paceSubtitle(pace: ProactivePace): String = when (pace) {
    ProactivePace.LIGHT -> stringResource(R.string.settings_agent_pace_light_desc)
    ProactivePace.STANDARD -> stringResource(R.string.settings_agent_pace_standard_desc)
    ProactivePace.HEAVY -> stringResource(R.string.settings_agent_pace_heavy_desc)
    ProactivePace.EXTREME -> stringResource(R.string.settings_agent_pace_extreme_desc)
}
