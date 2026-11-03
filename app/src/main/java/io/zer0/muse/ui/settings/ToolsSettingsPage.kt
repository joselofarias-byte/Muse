package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.tools.SessionPermissionMode
import io.zer0.muse.tools.ToolApprovalPolicy
import io.zer0.muse.tools.ToolConfigStore
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsSegmentedRow
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.statusColors
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v1.0.20: Página de gestión de aprobación de herramientas — Estrategia de aprobación granular de una sola herramienta.
 *
 * con [io.zer0.muse.ui.ToolsScreen] la diferencia:
 *  - ToolsScreen es una pantalla de solo lectura(Lista de herramientas + ventana emergente de detalles),Incontrolable
 *  - Esta página es una página de administración grabable.:Cada herramienta puede cambiar entre tres estrategias.
 *    - ALWAYS_ALLOW:siempre permitido(Equivalente a la lista blanca de la herramienta.)
 *    - ASK_EVERY_TIME:pregunta cada vez(Predeterminado)
 *    - ALWAYS_DENY:siempre rechace(Equivale a desactivar la herramienta.)
 *
 * La estrategia de la tercera marcha dura hasta [ToolConfigStore],por [io.zer0.muse.tools.ToolPermissionResolver]
 * Lea y determine el estado de aprobación antes de cada llamada a la herramienta.
 *
 * Estructura de la página:
 *  1. cuadro de búsqueda superior(Por nombre de herramienta/Filtro de descripción)
 *  2. Agrupar por nivel de riesgo:SAFE / NORMAL / HIGH
 *  3. Una fila por herramienta:Nombre + Descripción + Mando segmentado de tres posiciones
 *  4. Tarjeta de instrucciones inferior:Explicar la relación entre la estrategia de tres niveles y el modo de permiso de sesión.
 *
 * estilo y [ChatSettingsPage] consistente(SettingsSubPageScaffold + SettingsGroup + MuseCapsuleTab).
 */
@Composable
fun ToolsSettingsPage(onBack: () -> Unit) {
    val settings: io.zer0.muse.data.SettingsRepository = koinInject()
    val toolRegistry: ToolRegistry = koinInject()
    val toolConfigStore: ToolConfigStore = koinInject()
    val scope = rememberCoroutineScope()
    // Política de aprobación predeterminada global (reubicada de la página de chat a la página de herramientas)
    val permissionMode by settings.defaultSessionPermissionModeFlow
        .collectAsStateWithLifecycle(initialValue = SessionPermissionMode.ASK)

    // Lista de herramientas(Lectura sincrónica ConcurrentHashMap,No es necesario IO)
    val tools = remember { toolRegistry.listTools().sortedBy { it.name } }

    // El actual mapa político persistente(toolName → policy)
    val policies by toolConfigStore.policiesFlow.collectAsStateWithLifecycle(initialValue = emptyMap())

    // consulta de búsqueda(estado local,No persistente)
    var searchQuery by remember { mutableStateOf("") }

    // Herramienta de filtrado por término de búsqueda(Devolver todo cuando la consulta esté vacía)
    val filteredTools = remember(searchQuery, tools) {
        if (searchQuery.isBlank()) {
            tools
        } else {
            tools.filter { tool ->
                tool.name.contains(searchQuery, ignoreCase = true) ||
                    tool.description.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    // Agrupar por nivel de riesgo(orden:SAFE → NORMAL → HIGH)
    val grouped = remember(filteredTools) {
        filteredTools.groupBy { it.riskLevel }.toSortedMap(compareBy { it.ordinal })
    }

    SettingsSubPageScaffold(
        title = stringResource(R.string.tools_settings_title),
        onBack = onBack,
    ) {
        // ── Política de aprobación predeterminada global ──
        // Las anulaciones para herramientas individuales se enumeran a continuación; Los valores predeterminados se muestran aquí.
        item { SectionLabel(stringResource(R.string.settings_chat_tool_approval_section)) }
        item {
            SettingsGroup {
                val modeOptions =
                    listOf(
                        stringResource(R.string.settings_chat_tool_approval_trusted),
                        stringResource(R.string.settings_chat_tool_approval_ask),
                        stringResource(R.string.settings_chat_tool_approval_strict),
                    )
                val modeIndex =
                    when (permissionMode) {
                        SessionPermissionMode.TRUSTED -> 0
                        SessionPermissionMode.ASK -> 1
                        SessionPermissionMode.STRICT -> 2
                    }
                SettingsSegmentedRow(
                    icon = MuseIcons.shieldCheck,
                    title = stringResource(R.string.settings_chat_tool_approval_title),
                    subtitle = stringResource(R.string.settings_chat_tool_approval_subtitle),
                    options = modeOptions,
                    selectedIndex = modeIndex,
                    onSelectedChange = { idx ->
                        val newMode =
                            when (idx) {
                                0 -> SessionPermissionMode.TRUSTED
                                2 -> SessionPermissionMode.STRICT
                                else -> SessionPermissionMode.ASK
                            }
                        scope.launch { settings.setDefaultSessionPermissionMode(newMode) }
                    },
                )
                SettingsGroupDivider()
                // Descripción del modo actual (visualización dinámica)
                val modeDescRes =
                    when (permissionMode) {
                        SessionPermissionMode.TRUSTED -> R.string.settings_chat_tool_approval_trusted_desc
                        SessionPermissionMode.ASK -> R.string.settings_chat_tool_approval_ask_desc
                        SessionPermissionMode.STRICT -> R.string.settings_chat_tool_approval_strict_desc
                    }
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = MuseIcons.lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                    Text(
                        text = stringResource(modeDescRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        // ── cuadro de búsqueda superior ────────────────────────────────────────────────
        item(key = "search") {
            MuseTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.tools_settings_search_hint)) },
                leadingIcon = {
                    Icon(MuseIcons.search, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            MuseIcons.refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                singleLine = true,
            )
        }

        // ── Estadísticas ─────────────────────────────────────────────────
        item(key = "stats") {
            val allowedCount = tools.count { policies[it.name] == ToolApprovalPolicy.ALWAYS_ALLOW }
            val deniedCount = tools.count { policies[it.name] == ToolApprovalPolicy.ALWAYS_DENY }
            val askCount = tools.size - allowedCount - deniedCount
            Text(
                text = stringResource(
                    R.string.tools_settings_stats,
                    tools.size,
                    allowedCount,
                    askCount,
                    deniedCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }

        // ── Se muestra en grupos por nivel de riesgo. ───────────────────────────────────────
        grouped.forEach { (riskLevel, toolsInGroup) ->
            item(key = "header_${riskLevel.name}") {
                SectionLabel(stringResource(riskLevelSectionTitle(riskLevel)))
            }
            item(key = "group_${riskLevel.name}") {
                SettingsGroup {
                    toolsInGroup.forEachIndexed { index, tool ->
                        if (index > 0) SettingsGroupDivider()
                        ToolPolicyRow(
                            toolName = tool.name,
                            toolDescription = tool.description,
                            riskLevel = tool.riskLevel,
                            currentPolicy = policies[tool.name] ?: ToolApprovalPolicy.ALWAYS_ALLOW,
                            onPolicyChange = { newPolicy ->
                                scope.launch {
                                    toolConfigStore.setPolicy(tool.name, newPolicy)
                                }
                            },
                        )
                    }
                }
            }
        }

        // ── Estado vacío(No se encontraron resultados)──────────────────────────────────────
        if (filteredTools.isEmpty()) {
            item(key = "empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = MuseIcons.search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.tools_settings_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }

        // ── Tarjeta de instrucciones inferior ─────────────────────────────────────────────
        item(key = "footer_header") {
            SectionLabel(stringResource(R.string.tools_settings_footer_section))
        }
        item(key = "footer") {
            // v1.0.52: Color de estado semántico,El modo oscuro cambia automáticamente al modo brillante
            val statusColors = MaterialTheme.statusColors
            SettingsGroup {
                PolicyExplanationRow(
                    icon = MuseIcons.check,
                    iconTint = statusColors.success,
                    title = stringResource(R.string.tools_settings_policy_always_allow),
                    description = stringResource(R.string.tools_settings_policy_always_allow_desc),
                )
                SettingsGroupDivider()
                PolicyExplanationRow(
                    icon = MuseIcons.help,
                    iconTint = statusColors.warning,
                    title = stringResource(R.string.tools_settings_policy_ask),
                    description = stringResource(R.string.tools_settings_policy_ask_desc),
                )
                SettingsGroupDivider()
                PolicyExplanationRow(
                    icon = MuseIcons.ban,
                    iconTint = statusColors.error,
                    title = stringResource(R.string.tools_settings_policy_always_deny),
                    description = stringResource(R.string.tools_settings_policy_always_deny_desc),
                )
            }
        }
    }
}

/**
 * Filas de estrategia para instrumentos individuales — Icono + nombre + descripción + control segmentado de tres posiciones.
 *
 * El cambio de política se mantendrá inmediatamente para [ToolConfigStore],Entra en vigor la próxima vez que se llama a la herramienta.
 */
@Composable
private fun ToolPolicyRow(
    toolName: String,
    toolDescription: String,
    riskLevel: ToolRiskLevel,
    currentPolicy: ToolApprovalPolicy,
    onPolicyChange: (ToolApprovalPolicy) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Icono de nivel de riesgo(v1.0.52: Color de estado semántico,El modo oscuro cambia automáticamente al modo brillante)
        val statusColors = MaterialTheme.statusColors
        val (icon, iconTint) = when (riskLevel) {
            ToolRiskLevel.SAFE -> MuseIcons.shieldCheck to statusColors.success
            ToolRiskLevel.NORMAL -> MuseIcons.wrench to statusColors.warning
            ToolRiskLevel.HIGH -> MuseIcons.alertTriangle to statusColors.error
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = toolName,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = MuseMonoFontFamily,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (toolDescription.isNotBlank()) {
                Text(
                    text = toolDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            // Control segmentado de tres posiciones
            val options = listOf(
                stringResource(R.string.tools_settings_policy_always_allow_short),
                stringResource(R.string.tools_settings_policy_ask_short),
                stringResource(R.string.tools_settings_policy_always_deny_short),
            )
            val selectedIndex = when (currentPolicy) {
                ToolApprovalPolicy.ALWAYS_ALLOW -> 0
                ToolApprovalPolicy.ASK_EVERY_TIME -> 1
                ToolApprovalPolicy.ALWAYS_DENY -> 2
            }
            io.zer0.muse.ui.common.form.MuseCapsuleTab(
                tabs = options,
                selectedIndex = selectedIndex,
                onSelect = { idx ->
                    val newPolicy = when (idx) {
                        0 -> ToolApprovalPolicy.ALWAYS_ALLOW
                        2 -> ToolApprovalPolicy.ALWAYS_DENY
                        else -> ToolApprovalPolicy.ASK_EVERY_TIME
                    }
                    onPolicyChange(newPolicy)
                },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * línea de descripción de política — icono + título + descripción(Tarjeta de instrucciones inferior para).
 */
@Composable
private fun PolicyExplanationRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    description: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp),
        )
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Asigna niveles de riesgo a ID de recursos de encabezado de grupo. */
private fun riskLevelSectionTitle(level: ToolRiskLevel): Int = when (level) {
    ToolRiskLevel.SAFE -> R.string.tools_settings_section_safe
    ToolRiskLevel.NORMAL -> R.string.tools_settings_section_normal
    ToolRiskLevel.HIGH -> R.string.tools_settings_section_high
}
