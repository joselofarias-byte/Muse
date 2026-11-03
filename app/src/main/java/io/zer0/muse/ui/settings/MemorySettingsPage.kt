package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.memory.ticker.MemoryConfig
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSegmentedRow
import io.zer0.muse.ui.common.settings.SettingsSliderRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v0.32: Página de configuración avanzada del sistema de memoria.
 *
 * Aumentar el umbral codificado de MemoryTicker a configurable:
 *  - token presupuesto(Afecta la cantidad de memoria inyectada en el indicador del sistema.)
 *  - Coeficiente de atenuación λ(Controla la velocidad del olvido)
 *  - Bonificación de golpe(Los recuerdos que se mencionan a menudo no son fáciles de desaparecer.)
 *  - umbral de compilación(No se ingresarán memorias con puntuaciones inferiores a esta. memory.md)
 *  - factor de olvido(1.0 Normal / 2.0 Olvídate el doble de rápido)
 *
 * v1.0.51: El interruptor de la biblioteca de experiencias se mueve a la página de configuración del chat.;La política de notificación se movió a la página de configuración del chat.
 * v2.x: mantente despierto/Vaya al grupo "Backend y confiabilidad" en la página del Agente después del inicio.
 */
@Composable
fun MemorySettingsPage(onBack: () -> Unit, onOpenMemorySpace: () -> Unit = {}) {
    val settings: SettingsRepository = koinInject()
    val memoryConfig by settings.memoryConfigFlow.collectAsStateWithLifecycle(initialValue = MemoryConfig())
    // v2.x: Página de recursos de autoayuda para el interruptor maestro de memoria a largo plazo Volver a inicio(Control maestro de la función de memoria)
    val memoryEnabled by settings.memoryEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    // v2.x: mantente despierto/El inicio automático se ha movido al grupo "Backend y confiabilidad" en la página del Agente;Las palabras de indicación de compresión personalizadas se han movido a la página del modelo auxiliar.
    val scope = rememberCoroutineScope()
    // Cambio de biblioteca de experiencias: un componente del sistema de memoria, devuelto desde la página de chat.
    val experienceEnabled by settings.experienceEnabledFlow
        .collectAsStateWithLifecycle(initialValue = false)
    // Límite de contexto basado en piso: cuántas rondas recientes se retienen en el contexto (devuelto desde la página de chat)
    val floorLimiterEnabled by settings.floorLimiterEnabledFlow
        .collectAsStateWithLifecycle(initialValue = false)
    val floorLimit by settings.floorLimitFlow.collectAsStateWithLifecycle(initialValue = 16)

    // v1.78 (#19): Control deslizante antivibración — Solo se actualiza al arrastrar localConfig,Deténgase durante 400 ms antes de continuar DataStore
    //
    // No disponible aquí remember(memoryConfig) Crear clave: Luego, localConfig se reconstruirá cada vez que guarde y redistribuya.
    // Arrastre el arrastre en curso hasta el último valor guardado (un arrastre lento hará que aparezca, ya que "el control deslizante no se puede mover después de arrastrarlo una vez").
    // Cambio al estado local residente + marca sucia: la sincronización externa no se aceptará cuando haya cambios locales no guardados.
    var localConfig by remember { mutableStateOf(memoryConfig) }
    var localConfigDirty by remember { mutableStateOf(false) }

    // Los cambios de configuración externos (carga del primer cuadro, modificación en otro lugar) solo se aceptarán si no hay cambios locales para guardar.
    LaunchedEffect(memoryConfig) {
        if (!localConfigDirty) localConfig = memoryConfig
    }

    LaunchedEffect(localConfig) {
        if (!localConfigDirty) return@LaunchedEffect
        delay(400)
        // v1.78 (H7): Verificar antes de guardar,rechazar compileThreshold >= baseImportance Otras configuraciones peligrosas
        val err = MemoryConfig.validate(localConfig)
        if (err != null) {
            io.zer0.muse.ui.common.feedback.MuseToast.show(err)
            localConfig = memoryConfig // Retroceder al último valor legal
            localConfigDirty = false
        } else {
            settings.saveMemoryConfig(localConfig)
            localConfigDirty = false
        }
    }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_memory_page_title), onBack = onBack) {
        // ── 0. gestión del espacio de memoria(P2-2) ──
        item { SectionLabel(stringResource(R.string.memory_space_entry)) }
        item {
            SettingsGroup {
                SettingsItemRow(
                    icon = null,
                    title = stringResource(R.string.memory_space_manage_title),
                    subtitle = "Administre múltiples espacios de memoria como trabajo/vida/estudio,Sin interferencias entre sí al cambiar",
                    onClick = onOpenMemorySpace,
                )
            }
        }
        // ── 1. sistema de memoria ──
        item { SectionLabel(stringResource(R.string.settings_memory_system_section)) }
        item {
            SettingsGroup {
                // v2.x: Página de recursos de autoayuda para el interruptor maestro de memoria a largo plazo Volver a inicio
                SettingsSwitchRow(
                    icon = MuseIcons.atom,
                    title = stringResource(R.string.settings_assistant_memory_enable),
                    subtitle = stringResource(R.string.settings_assistant_memory_enable_subtitle),
                    checked = memoryEnabled,
                    onCheckedChange = { v -> scope.launch { settings.saveMemoryEnabled(v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.history,
                    title = stringResource(R.string.settings_memory_conversation_recall),
                    subtitle = stringResource(R.string.settings_memory_conversation_recall_subtitle),
                    checked = localConfig.conversationRecallEnabled,
                    onCheckedChange = { enabled ->
                        localConfigDirty = true
                        localConfig = localConfig.copy(conversationRecallEnabled = enabled)
                    },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.server,
                    title = stringResource(R.string.settings_memory_experience_lib),
                    subtitle = stringResource(R.string.settings_memory_experience_lib_subtitle),
                    checked = experienceEnabled,
                    onCheckedChange = { v -> scope.launch { settings.saveExperienceEnabled(v) } },
                )
                SettingsGroupDivider()
                SettingsSliderRow(
                    icon = MuseIcons.server,
                    iconContentDescription = stringResource(R.string.settings_memory_token_budget),
                    title = stringResource(R.string.settings_memory_token_budget),
                    subtitle = stringResource(R.string.settings_memory_token_budget_subtitle),
                    value = localConfig.tokenBudget.toFloat(),
                    valueRange = 500f..6000f,
                    steps = 10,
                    valueText = "${localConfig.tokenBudget}",
                    onValueChange = { v ->
                        localConfigDirty = true
                        localConfig = localConfig.copy(tokenBudget = v.toInt())
                    },
                )
                SettingsGroupDivider()
                SettingsSliderRow(
                    icon = MuseIcons.trendingDown,
                    iconContentDescription = stringResource(R.string.settings_memory_decay_rate),
                    title = stringResource(R.string.settings_memory_decay_rate_title),
                    subtitle = stringResource(R.string.settings_memory_decay_rate_subtitle),
                    value = localConfig.decayPerDay,
                    valueRange = 0.005f..0.06f,
                    steps = 10,
                    valueText = "%.3f".format(localConfig.decayPerDay),
                    onValueChange = { v ->
                        localConfigDirty = true
                        localConfig = localConfig.copy(decayPerDay = v)
                    },
                )
                SettingsGroupDivider()
                SettingsSliderRow(
                    icon = MuseIcons.bolt,
                    iconContentDescription = stringResource(R.string.settings_memory_hit_bonus),
                    title = stringResource(R.string.settings_memory_hit_bonus),
                    // v7: hitBonus Ya conectado factScore / cutoffDays / applyDecay
                    subtitle = stringResource(R.string.settings_memory_hit_bonus_subtitle),
                    value = localConfig.hitBonus,
                    valueRange = 0f..15f,
                    steps = 14,
                    valueText = "%.1f".format(localConfig.hitBonus),
                    onValueChange = { v ->
                        localConfigDirty = true
                        localConfig = localConfig.copy(hitBonus = v)
                    },
                )
                SettingsGroupDivider()
                SettingsSliderRow(
                    icon = MuseIcons.arrowsVertical,
                    iconContentDescription = stringResource(R.string.settings_memory_compile_threshold),
                    title = stringResource(R.string.settings_memory_compile_threshold),
                    subtitle = stringResource(R.string.settings_memory_compile_threshold_subtitle, localConfig.baseImportance),
                    value = localConfig.compileThreshold,
                    valueRange = 1f..(localConfig.baseImportance - 0.5f),
                    steps = 17,
                    valueText = "%.1f".format(localConfig.compileThreshold),
                    onValueChange = { v ->
                        localConfigDirty = true
                        localConfig = localConfig.copy(compileThreshold = v)
                    },
                )
                SettingsGroupDivider()
                SettingsSliderRow(
                    icon = MuseIcons.gauge,
                    iconContentDescription = stringResource(R.string.settings_memory_forget_speed),
                    title = stringResource(R.string.settings_memory_forget_speed),
                    subtitle = stringResource(R.string.settings_memory_forget_speed_subtitle),
                    value = localConfig.forgetSpeed,
                    valueRange = 0.5f..3f,
                    steps = 24,
                    valueText = "%.1fx".format(localConfig.forgetSpeed),
                    onValueChange = { v ->
                        localConfigDirty = true
                        localConfig = localConfig.copy(forgetSpeed = v)
                    },
                )
                SettingsGroupDivider()
                // v1.78 (#21): Restaurar botón predeterminado
                SettingsActionRow(
                    title = stringResource(R.string.settings_memory_restore_default),
                    subtitle = stringResource(R.string.settings_memory_restore_default_subtitle),
                    onClick = { localConfig = MemoryConfig() },
                )
            }
        }
        // ── ventana contextual ──
        item { SectionLabel(stringResource(R.string.settings_screen_group_context_window)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = MuseIcons.arrowsVertical,
                    title = stringResource(R.string.settings_chat_floor_limiter),
                    subtitle = stringResource(R.string.settings_chat_floor_limiter_subtitle),
                    checked = floorLimiterEnabled,
                    onCheckedChange = { v -> scope.launch { settings.saveFloorLimiterEnabled(v) } },
                )
                if (floorLimiterEnabled) {
                    SettingsGroupDivider()
                    val floorOptions =
                        listOf(
                            stringResource(R.string.settings_chat_floor_count, 8),
                            stringResource(R.string.settings_chat_floor_count, 16),
                            stringResource(R.string.settings_chat_floor_count, 32),
                        )
                    val floorIndex =
                        when (floorLimit) {
                            8 -> 0
                            32 -> 2
                            else -> 1
                        }
                    SettingsSegmentedRow(
                        icon = MuseIcons.arrowsVertical,
                        title = stringResource(R.string.settings_chat_floor_keep),
                        subtitle = stringResource(R.string.settings_chat_floor_keep_subtitle),
                        options = floorOptions,
                        selectedIndex = floorIndex,
                        onSelectedChange = { idx ->
                            val limit =
                                when (idx) {
                                    0 -> 8
                                    2 -> 32
                                    else -> 16
                                }
                            scope.launch { settings.saveFloorLimit(limit) }
                        },
                    )
                }
            }
        }
    }
}

/**
 * v1.78 (#21): Fila de acción en la que se puede hacer clic(Como por ejemplo"Restaurar valor predeterminado").
 */
@Composable
private fun SettingsActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
