@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package io.zer0.muse.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.R
import io.zer0.muse.data.AgentTeam
import io.zer0.muse.data.MultiAgentConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.tools.DelegationContract
import io.zer0.muse.ui.ModelSwitchSheet
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseDropdown
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSliderRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.UUID

/**
 * v1.25: Página de gestión del equipo de colaboración multiagente.
 *
 * Función:
 *  - interruptor principal:habilitar/Deshabilitar la colaboración entre múltiples agentes
 *  - lista de equipo:Nombre para mostrar, descripción, avatar de miembro(hasta 3,+N)
 *  - Haga clic en Editar equipo,Pulsación larga/Haga clic en el icono de eliminar para eliminar(Necesito confirmar)
 *  - Botón cápsula "Nuevo equipo" en la parte inferior
 */
@Composable
fun MultiAgentSettingsPage(onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val assistantRepository: AssistantRepository = koinInject()
    val config by settings.multiAgentConfigFlow.collectAsStateWithLifecycle(initialValue = MultiAgentConfig())
    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = null)
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeProviderId by settings.activeProviderIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()

    var editingTeam by remember { mutableStateOf<AgentTeam?>(null) }
    var teamToDelete by remember { mutableStateOf<AgentTeam?>(null) }
    var showReviewModelSheet by remember { mutableStateOf(false) }
    val unnamedTeam = stringResource(R.string.settings_multi_agent_unnamed_team)

    fun updateConfig(block: (MultiAgentConfig) -> MultiAgentConfig) {
        // M3: Utilice el método de actualización atómica,evitar leer-cambiar-Escribir condiciones de carrera
        scope.launch { settings.updateMultiAgentConfig(block) }
    }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_multi_agent_title), onBack = onBack) {
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = MuseIcons.users,
                    title = stringResource(R.string.settings_multi_agent_enable),
                    subtitle = stringResource(R.string.settings_multi_agent_enable_subtitle),
                    checked = config.enabled,
                    onCheckedChange = { v ->
                        updateConfig { it.copy(enabled = v) }
                    },
                )
            }
        }

        // v1.201: LLM Revisión integral del área de configuración global.
        item { SectionLabel(stringResource(R.string.multi_agent_llm_review_enabled)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = MuseIcons.user,
                    title = stringResource(R.string.multi_agent_llm_review_enabled),
                    subtitle = stringResource(R.string.multi_agent_llm_review_desc),
                    checked = config.llmReviewEnabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveLlmReviewEnabled(v) }
                    },
                )
                SettingsGroupDivider()
                SettingsItemRow(
                    icon = MuseIcons.user,
                    title = stringResource(R.string.multi_agent_llm_review_model),
                    subtitle = resolveReviewModelName(
                        reviewModelId = config.reviewModelId,
                        providers = providers,
                        activeProviderId = activeProviderId,
                    ),
                    onClick = { showReviewModelSheet = true },
                )
            }
        }

        item { SectionLabel(stringResource(R.string.settings_multi_agent_team_list)) }

        if (config.teams.isEmpty()) {
            // v1.48: h14 Utilice el componente MuseEmptyState en su lugar cuando la lista del equipo esté vacía.
            item {
                MuseEmptyState(
                    icon = MuseIcons.users,
                    title = stringResource(R.string.settings_multi_agent_no_team),
                    subtitle = stringResource(R.string.settings_multi_agent_no_team_hint),
                    actionText = stringResource(R.string.settings_multi_agent_new_team),
                    onAction = { editingTeam = AgentTeam(id = "") },
                )
            }
        } else {
            items(
                items = config.teams,
                key = { it.id },
            ) { team ->
                TeamCard(
                    team = team,
                    assistants = assistants ?: emptyList(),
                    onClick = { editingTeam = team },
                    onDelete = { teamToDelete = team },
                )
            }
        }

        // v1.0.92: Plantilla rápida del equipo de redacción — Precarga con un clic para el proceso de colaboración(Los miembros se pueden agregar en orden en el editor.)
        item {
            val writingTeamName = stringResource(R.string.settings_multi_agent_writing_team_name)
            val writingTeamDesc = stringResource(R.string.settings_multi_agent_writing_team_desc)
            SettingsGroup {
                SettingsItemRow(
                    icon = MuseIcons.edit,
                    title = stringResource(R.string.settings_multi_agent_writing_team_template),
                    subtitle = stringResource(R.string.settings_multi_agent_writing_team_template_desc),
                    onClick = {
                        editingTeam = AgentTeam(id = "", name = writingTeamName, description = writingTeamDesc)
                    },
                )
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MuseShapes.pill)
                    // A11Y-02: tocar el objetivo ≥48dp
                    .defaultMinSize(minHeight = MuseIconSizes.touchTarget)
                    .clickable { editingTeam = AgentTeam(id = "") },
                color = MaterialTheme.colorScheme.primary,
                shape = MuseShapes.pill,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = MuseIcons.plus,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_multi_agent_new_team),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        // v1.201: Estrategia de pausa en la colaboración entre humanos y máquinas
        item { SectionLabel(stringResource(R.string.delegation_pause_section_title)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.delegation_pause_before_team),
                    checked = config.pausePolicy.pauseBeforeTeam,
                    onCheckedChange = { v ->
                        updateConfig { it.copy(pausePolicy = it.pausePolicy.copy(pauseBeforeTeam = v)) }
                    },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.delegation_pause_before_member),
                    checked = config.pausePolicy.pauseBeforeEachMember,
                    onCheckedChange = { v ->
                        updateConfig { it.copy(pausePolicy = it.pausePolicy.copy(pauseBeforeEachMember = v)) }
                    },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.delegation_pause_intermediate),
                    checked = config.pausePolicy.pauseOnIntermediateResult,
                    onCheckedChange = { v ->
                        updateConfig { it.copy(pausePolicy = it.pausePolicy.copy(pauseOnIntermediateResult = v)) }
                    },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.delegation_pause_high_risk),
                    checked = config.pausePolicy.pauseOnHighRisk,
                    onCheckedChange = { v ->
                        updateConfig { it.copy(pausePolicy = it.pausePolicy.copy(pauseOnHighRisk = v)) }
                    },
                )
                DelegationTimeoutSliderRow(
                    timeoutSec = config.pausePolicy.autoTimeoutSec,
                    onValueChange = { newSec ->
                        updateConfig { it.copy(pausePolicy = it.pausePolicy.copy(autoTimeoutSec = newSec)) }
                    },
                )
            }
        }
    }

    editingTeam?.let { team ->
        TeamEditDialog(
            team = team,
            assistants = assistants ?: emptyList(),
            onDismiss = { editingTeam = null },
            onSave = { saved ->
                val now = System.currentTimeMillis()
                val isNew = saved.id.isBlank()
                val finalTeam = if (isNew) {
                    saved.copy(id = UUID.randomUUID().toString(), createdAt = now, updatedAt = now)
                } else {
                    saved.copy(updatedAt = now)
                }
                updateConfig {
                    if (isNew) {
                        it.copy(teams = it.teams + finalTeam)
                    } else {
                        it.copy(teams = it.teams.map { t -> if (t.id == finalTeam.id) finalTeam else t })
                    }
                }
                editingTeam = null
            },
        )
    }

    teamToDelete?.let { team ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.settings_multi_agent_delete_team),
            itemName = team.name.ifBlank { unnamedTeam },
            onConfirm = {
                updateConfig {
                    it.copy(
                        teams = it.teams.filter { t -> t.id != team.id },
                        defaultTeamId = if (it.defaultTeamId == team.id) null else it.defaultTeamId,
                    )
                }
                teamToDelete = null
            },
            onDismiss = { teamToDelete = null },
        )
    }

    // v1.201: LLM Panel de selección de modelo de revisión
    // descripción del diseño:El modelo de revisión pertenece a"Configuraciones globales",La activación de alternancia no está permitida aquí Provider(evitar efectos secundarios),
    // Solo permite al usuario elegir de la lista de modelos para los cuales el Proveedor está actualmente activo;Si necesita utilizar otros modelos de proveedor,
    // Los usuarios primero deben ir a la configuración del proveedor para cambiar la activación. Provider,Regrese a esta página para seleccionar un modelo.
    // LlmAggregator.resolveReviewModel() Se buscará por id en todos los Proveedores,El cambio entre proveedores no afecta la resolución.
    if (showReviewModelSheet) {
        ModelSwitchSheet(
            providers = providers,
            activeProviderId = activeProviderId,
            selectedModelId = config.reviewModelId,
            onPickProvider = { /* El modelo de revisión no cambia la activación global Provider,Implementación vacía para evitar efectos secundarios. */ },
            onPickModel = { modelId ->
                if (!modelId.isNullOrBlank()) {
                    scope.launch { settings.saveReviewModelId(modelId) }
                }
                showReviewModelSheet = false
            },
            onRefreshModels = { /* La selección del modelo de revisión no proporciona actualización manual,Depende de la página de configuración global del proveedor */ },
            isFetchingModels = false,
            fetchModelsError = null,
            onDismiss = { showReviewModelSheet = false },
        )
    }
}

/**
 * v1.201: Analice el nombre para mostrar del modelo de revisión en la interfaz de usuario.
 *
 * reglas:
 *  - reviewModelId esta vacio → "Modelo predeterminado"(Respaldo al primer modelo del proveedor)
 *  - Buscar por id en todos los proveedores → Modelo de devolución name(Si el nombre está vacío, recurra a id)
 *  - No se encontró ningún modelo coincidente → "modelo desconocido(<id>)"(Quizás el usuario cambió de proveedor o eliminó el modelo.)
 *
 * Nota:Esta función solo muestra el nombre en la capa UI,LlmAggregator.resolveReviewModel() lo haré al llamar
 * El mismo proceso de búsqueda y degradación.,La lógica de búsqueda de los dos sigue siendo consistente.
 */
private fun resolveReviewModelName(reviewModelId: String?, providers: List<ProviderConfig>, activeProviderId: String?): String {
    if (reviewModelId.isNullOrBlank()) return "Modelo predeterminado"
    // Primera búsqueda en el Proveedor activo(situación más común)
    val activeProvider = providers.firstOrNull { it.id == activeProviderId }
    activeProvider?.models?.firstOrNull { it.id == reviewModelId }?.let { model ->
        return model.name.ifBlank { model.id }
    }
    // En segundo lugar, busque en todos los proveedores.(Es posible que el usuario haya cambiado el proveedor activo, pero reviewModelId aún apunta al modelo del proveedor anterior.)
    for (provider in providers) {
        val matched = provider.models.firstOrNull { it.id == reviewModelId }
        if (matched != null) {
            return matched.name.ifBlank { matched.id }
        }
    }
    return "modelo desconocido($reviewModelId)"
}

/**
 * tarjeta de equipo:Nombre + descripción + avatar de miembro(hasta 3,+N) + Botón eliminar.
 */
@Composable
private fun TeamCard(team: AgentTeam, assistants: List<AssistantEntity>, onClick: () -> Unit, onDelete: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val unnamedTeam = stringResource(R.string.settings_multi_agent_unnamed_team)
    val members = remember(team.memberIds, assistants) {
        team.memberIds.mapNotNull { id -> assistants.find { it.id == id } }
    }

    MuseSurface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    MuseHaptics.medium(haptic)
                    onDelete()
                },
            ),
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = team.name.ifBlank { unnamedTeam },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (team.description.isNotBlank()) {
                    Text(
                        text = team.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (members.isNotEmpty()) {
                    MemberAvatarRow(members = members)
                } else {
                    Text(
                        text = stringResource(R.string.settings_multi_agent_no_members),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                    )
                }
            }

            MuseTactileButton(
                icon = MuseIcons.trash,
                onClick = onDelete,
                contentDescription = stringResource(R.string.settings_multi_agent_delete),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * Fila de avatar de miembro:Mostrar hasta los primeros 3 avatares,+N logos redondos para el resto.
 */
@Composable
private fun MemberAvatarRow(members: List<AssistantEntity>) {
    val shown = members.take(3)
    val overflow = members.size - shown.size

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((-8).dp),
    ) {
        shown.forEach { assistant ->
            AssistantAvatar(
                assistant = assistant,
                avatarSize = 28.dp,
                modifier = Modifier.border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = CircleShape,
                ),
            )
        }
        if (overflow > 0) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$overflow",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * creación de equipo/Editar ventana emergente:Nombre, descripción, selección múltiple de miembros, disposición del flujo de trabajo.
 *
 * v1.0.19: Editar nodos de flujo de trabajo incrustados en esta ventana emergente(Estrategia de agregación/lista de nodos/adición, eliminación y modificación de nodos/gestión de dependencias)
 *  El punto de entrada preferido para la orquestación del flujo de trabajo,Experiencia de usuario consistente(No es necesario saltar a la página de pantalla completa).
 */
@Composable
private fun TeamEditDialog(team: AgentTeam, assistants: List<AssistantEntity>, onDismiss: () -> Unit, onSave: (AgentTeam) -> Unit) {
    // Correcciones en la interfaz (persistencia-9): Revisión del borrador del formulario rememberSaveable,key usar team.id(identificación estable)en lugar de una instancia de equipo;
    // String campo(name/description)directo saveable
    var name by rememberSaveable(team.id) { mutableStateOf(team.name) }
    var description by rememberSaveable(team.id) { mutableStateOf(team.description) }
    // selectedIds(Set)/workflow es un tipo complejo,no puede directamente saveable,mantener remember(Borrador emergente temporal,Es aceptable volver al valor original después de la reconstrucción.)
    var selectedIds by remember(team.id) { mutableStateOf(team.memberIds.toSet()) }
    var workflow by remember(team.id) { mutableStateOf(team.workflow ?: DelegationContract.TeamWorkflow()) }
    var editingNode by remember { mutableStateOf<DelegationContract.TeamWorkflowNode?>(null) }
    var nameError by remember { mutableStateOf(false) }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = if (team.id.isBlank()) {
            stringResource(
                R.string.settings_multi_agent_new_team,
            )
        } else {
            stringResource(R.string.settings_multi_agent_edit_team)
        },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MuseTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = it.isBlank()
                    },
                    label = { Text(stringResource(R.string.settings_multi_agent_team_name)) },
                    placeholder = { Text(stringResource(R.string.settings_multi_agent_team_name_hint)) },
                    singleLine = true,
                    isError = nameError,
                    supportingText = if (nameError) {
                        { Text(stringResource(R.string.settings_multi_agent_team_name_empty)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                MuseTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.settings_multi_agent_description)) },
                    placeholder = { Text(stringResource(R.string.settings_multi_agent_description_hint)) },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.settings_multi_agent_select_members),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (assistants.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_multi_agent_no_assistants),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                    )
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        assistants.forEach { assistant ->
                            val selected = assistant.id in selectedIds
                            MuseChip(
                                selected = selected,
                                onClick = {
                                    selectedIds = if (selected) {
                                        selectedIds - assistant.id
                                    } else {
                                        selectedIds + assistant.id
                                    }
                                },
                                label = assistant.name,
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            imageVector = MuseIcons.check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }

                // Orquestación del flujo de trabajo
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_multi_agent_workflow),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.settings_multi_agent_workflow_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                Text(
                    text = stringResource(R.string.settings_multi_agent_aggregation),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DelegationContract.TeamWorkflow.AggregationStrategy.entries.forEach { strategy ->
                        val selected = workflow.aggregationStrategy == strategy
                        MuseChip(
                            selected = selected,
                            onClick = { workflow = workflow.copy(aggregationStrategy = strategy) },
                            label = aggregationName(strategy),
                            leadingIcon = if (selected) {
                                {
                                    Icon(
                                        imageVector = MuseIcons.check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.settings_multi_agent_add_node),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (workflow.nodes.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_multi_agent_no_workflow),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        workflow.nodes.forEach { node ->
                            WorkflowNodeRow(
                                node = node,
                                assistants = assistants,
                                onEdit = { editingNode = node },
                                onDelete = {
                                    workflow = workflow.copy(
                                        nodes = workflow.nodes.filter { it.id != node.id },
                                    )
                                },
                            )
                        }
                    }
                }
                MuseTactileButton(
                    icon = MuseIcons.plus,
                    onClick = {
                        editingNode = DelegationContract.TeamWorkflowNode(
                            id = "",
                            assistantId = "",
                        )
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.Start),
                )
            }
        },
        confirmText = stringResource(R.string.settings_multi_agent_save),
        onConfirm = {
            if (name.isBlank()) {
                nameError = true
                return@MuseDialog
            }
            onSave(
                team.copy(
                    name = name.trim(),
                    description = description.trim(),
                    memberIds = assistants.filter { it.id in selectedIds }.map { it.id },
                    workflow = workflow,
                ),
            )
        },
        dismissText = stringResource(R.string.settings_multi_agent_cancel),
        onDismiss = onDismiss,
    )

    editingNode?.let { node ->
        WorkflowNodeEditDialog(
            node = node,
            assistants = assistants,
            existingNodeIds = workflow.nodes.map { it.id }.filter { it.isNotBlank() && it != node.id },
            onDismiss = { editingNode = null },
            onSave = { saved ->
                val finalNode = if (saved.id.isBlank()) {
                    saved.copy(id = "node-${UUID.randomUUID()}")
                } else {
                    saved
                }
                workflow = workflow.copy(
                    nodes = if (node.id.isBlank()) {
                        workflow.nodes + finalNode
                    } else {
                        workflow.nodes.map { if (it.id == node.id) finalNode else it }
                    },
                )
                editingNode = null
            },
        )
    }
}

@Composable
private fun WorkflowNodeRow(
    node: DelegationContract.TeamWorkflowNode,
    assistants: List<AssistantEntity>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val assistant = remember(node.assistantId, assistants) {
        assistants.find { it.id == node.assistantId }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MuseShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = MuseShapes.small,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            modifier = Modifier.size(width = 32.dp, height = 28.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = modeShortName(node.mode),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = node.name.ifBlank { stringResource(R.string.settings_multi_agent_unnamed_team) },
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = assistant?.name ?: node.assistantId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        MuseTactileButton(
            icon = MuseIcons.edit,
            onClick = onEdit,
            contentDescription = stringResource(R.string.settings_multi_agent_edit_node),
            tint = MaterialTheme.colorScheme.outline,
            size = 48.dp,
            iconSize = 18.dp,
        )
        MuseTactileButton(
            icon = MuseIcons.trash,
            onClick = onDelete,
            contentDescription = stringResource(R.string.settings_multi_agent_delete),
            tint = MaterialTheme.colorScheme.outline,
            size = 48.dp,
            iconSize = 18.dp,
        )
    }
}

@Composable
private fun WorkflowNodeEditDialog(
    node: DelegationContract.TeamWorkflowNode,
    assistants: List<AssistantEntity>,
    existingNodeIds: List<String>,
    onDismiss: () -> Unit,
    onSave: (DelegationContract.TeamWorkflowNode) -> Unit,
) {
    var name by remember(node.id) { mutableStateOf(node.name) }
    var taskTemplate by remember(node.id) { mutableStateOf(node.taskTemplate) }
    var assistantId by remember(node.id) { mutableStateOf(node.assistantId) }
    var mode by remember(node.id) { mutableStateOf(node.mode) }
    var dependencies by remember(node.id) { mutableStateOf(node.dependsOn.toSet()) }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_multi_agent_edit_node),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MuseTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_multi_agent_node_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MuseTextField(
                    value = taskTemplate,
                    onValueChange = { taskTemplate = it },
                    label = { Text(stringResource(R.string.settings_multi_agent_node_task)) },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                MuseDropdown(
                    value = assistantId,
                    onValueChange = { assistantId = it },
                    label = stringResource(R.string.settings_multi_agent_node_assistant),
                    options = assistants.map { it.id to it.name },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(R.string.settings_multi_agent_node_mode),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DelegationContract.TeamWorkflowNode.Mode.entries.forEach { entry ->
                        val selected = mode == entry
                        MuseChip(
                            selected = selected,
                            onClick = { mode = entry },
                            label = modeName(entry),
                            leadingIcon = if (selected) {
                                {
                                    Icon(
                                        imageVector = MuseIcons.check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }

                if (existingNodeIds.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_multi_agent_node_dependencies),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        existingNodeIds.forEach { id ->
                            val selected = id in dependencies
                            MuseChip(
                                selected = selected,
                                onClick = {
                                    dependencies = if (selected) {
                                        dependencies - id
                                    } else {
                                        dependencies + id
                                    }
                                },
                                label = id,
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            imageVector = MuseIcons.check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmText = stringResource(R.string.action_save),
        onConfirm = {
            onSave(
                node.copy(
                    name = name.trim(),
                    taskTemplate = taskTemplate.trim(),
                    assistantId = assistantId,
                    mode = mode,
                    dependsOn = dependencies.toList(),
                ),
            )
        },
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    )
}

private fun aggregationName(strategy: DelegationContract.TeamWorkflow.AggregationStrategy): String = when (strategy) {
    DelegationContract.TeamWorkflow.AggregationStrategy.MERGE -> "fusionar"
    DelegationContract.TeamWorkflow.AggregationStrategy.VOTE -> "votar"
    DelegationContract.TeamWorkflow.AggregationStrategy.EXPERT_REVIEW -> "Revisión de expertos"
    DelegationContract.TeamWorkflow.AggregationStrategy.FIRST_SUCCESS -> "primer éxito"
    DelegationContract.TeamWorkflow.AggregationStrategy.LLM_REVIEW -> "LLM Revisión completa"
}

private fun modeName(mode: DelegationContract.TeamWorkflowNode.Mode): String = when (mode) {
    DelegationContract.TeamWorkflowNode.Mode.SEQUENTIAL -> "de serie"
    DelegationContract.TeamWorkflowNode.Mode.PARALLEL -> "paralelo"
    DelegationContract.TeamWorkflowNode.Mode.CONDITIONAL -> "Condiciones"
}

private fun modeShortName(mode: DelegationContract.TeamWorkflowNode.Mode): String = when (mode) {
    DelegationContract.TeamWorkflowNode.Mode.SEQUENTIAL -> "cuerda"
    DelegationContract.TeamWorkflowNode.Mode.PARALLEL -> "y"
    DelegationContract.TeamWorkflowNode.Mode.CONDITIONAL -> "Artículo"
}

/**
 * v1.201: Fila del control deslizante de tiempo de espera de pausa de delegación(título + valor actual + control deslizante).
 *
 * Rango de valores 60-600 segundos,paso a paso 30,19 puntos discretos en total(steps = 17).
 * CMP-10: El diseño de filas ya no se dibuja solo,Delegación unificada [SettingsSliderRow](Sin forma de icono),
 * con [SettingsSwitchRow] Lo mismo padding,hazlo en [SettingsGroup] Alineación visual interna.
 */
@Composable
private fun DelegationTimeoutSliderRow(timeoutSec: Int, onValueChange: (Int) -> Unit) {
    val secUnit = stringResource(R.string.delegation_pause_sec)
    SettingsSliderRow(
        title = stringResource(R.string.delegation_pause_timeout),
        value = timeoutSec.toFloat(),
        valueRange = 60f..600f,
        steps = 17, // 60, 90, ..., 600 19 valores en total,steps = 19 - 2 = 17
        valueText = "$timeoutSec $secUnit",
        onValueChange = { v -> onValueChange(v.toInt()) },
    )
}
