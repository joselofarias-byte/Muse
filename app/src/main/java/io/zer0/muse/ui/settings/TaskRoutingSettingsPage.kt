package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.routing.UtilityModelBinding
import io.zer0.muse.data.routing.UtilityModelHealth
import io.zer0.muse.data.routing.UtilityTier
import io.zer0.muse.ui.ModelSwitchSheet
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v2.x: Página de configuración del modelo auxiliar(Originales"Enrutamiento de tareas"reconstrucción de página).
 *
 * Selección de modelo para modelos de tres niveles para converger uniformemente tareas auxiliares en segundo plano:
 *  - Modelo de dispositivo: Tareas breves como generación de títulos, clasificación ligera, rueda de herramientas de conversación, mensaje de portada, resumen de canal, etc.;
 *  - modelo de herramienta grande: Compresión de contexto, recuperación de memoria, resumen de actividades, descomposición de tareas.(subagente)Espere escenas que requieran comprensión.;
 *  - Modelo de ayuda visual: Comprensión de imágenes / PDF OCR,Haga clic para ingresar a la configuración de la página independiente.(Selección de modelo + prueba de sonda).
 *
 * reglas en cascada: Deje las herramientas grandes en blanco → Reutilizar gadgets;Gadgets dejados en blanco → Regresar al modelo de diálogo principal.
 * Banda uniforme de encuadernación. Provider,Pulsa con precisión el canal seleccionado(Se corrigió la falla histórica en la sincronización entre canales entre canales con la misma ID.).
 *
 * Descripción: Originales"Divide automáticamente el modelo principal según el contenido de la conversación."La función de descarga de tareas se ha eliminado en su totalidad según las solicitudes de los usuarios.
 * (El lado de Android tiene demasiados escenarios y altas barreras de uso.);TaskRoutingConfig La estructura de almacenamiento se conserva para compatibilidad con datos históricos.
 */
@Composable
fun TaskRoutingSettingsPage(
    onBack: () -> Unit,
    /** v2.x: Abrir página independiente de ayuda visual(Entrada visual a los bloques modelo auxiliares.). */
    onOpenVision: () -> Unit = {},
) {
    val settings: SettingsRepository = koinInject()
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeProviderId by settings.activeProviderIdFlow.collectAsStateWithLifecycle(initialValue = null)
    // v2.x: Modelo global por defecto(dialogo principal/Agent compartir)
    val selectedModelId by settings.selectedModelIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val currentModelName = remember(providers, activeProviderId, selectedModelId) {
        val provider = providers.firstOrNull { it.id == activeProviderId } ?: providers.firstOrNull()
        val model = provider?.models?.firstOrNull { it.id == selectedModelId } ?: provider?.models?.firstOrNull()
        model?.name ?: "—"
    }
    var showMainModelPicker by remember { mutableStateOf(false) }
    // v2.x: Modelo auxiliar(artilugio/grandes herramientas)Estado de enlace + visualización del modelo visual
    val utilityBinding by settings.utilityModelBindingFlow.collectAsStateWithLifecycle(initialValue = null)
    val utilityLargeBinding by settings.utilityLargeModelBindingFlow.collectAsStateWithLifecycle(initialValue = null)
    val visionModelId by settings.visionModelIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val visionProviderId by settings.visionProviderIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var editingProviderId by remember { mutableStateOf<String?>(null) }
    // v2.x: Estado de edición del modelo auxiliar(null=Ninguno;De lo contrario, el archivo que se está editando)
    var editingUtilityTier by remember { mutableStateOf<UtilityTier?>(null) }
    // v2.x: Nombre para mostrar del modelo auxiliar(Al vincularse con el proveedor, dé prioridad a los aciertos precisos.)
    val utilitySmallLabel = remember(utilityBinding, providers) {
        utilityBinding?.let { b ->
            providers.firstOrNull { it.id == b.providerId }?.models?.firstOrNull { it.id == b.modelId }?.name
                ?: providers.flatMap { it.models }.firstOrNull { it.id == b.modelId }?.name
        }
    }
    val utilityLargeLabel = remember(utilityLargeBinding, providers) {
        utilityLargeBinding?.let { b ->
            providers.firstOrNull { it.id == b.providerId }?.models?.firstOrNull { it.id == b.modelId }?.name
                ?: providers.flatMap { it.models }.firstOrNull { it.id == b.modelId }?.name
        }
    }
    val visionLabel = remember(visionModelId, visionProviderId, providers) {
        if (visionModelId.isNullOrBlank()) {
            null
        } else {
            providers.firstOrNull { it.id == visionProviderId }?.models?.firstOrNull { it.id == visionModelId }?.name
                ?: providers.flatMap { it.models }.firstOrNull { it.id == visionModelId }?.name
        }
    }
    // v2.x: Palabras de indicación de compresión personalizadas(Inicio de página de memoria propia)
    val customCompressPrompt by settings.customCompressPromptFlow.collectAsStateWithLifecycle(initialValue = null)
    // Pensamiento profundo predeterminado: si el diálogo principal permite el razonamiento de forma predeterminada
    val chatPrefs by settings.chatPreferencesFlow
        .collectAsStateWithLifecycle(initialValue = ChatPreferences())
    var showCompressPromptDialog by remember { mutableStateOf(false) }
    var compressPromptDraft by remember(customCompressPrompt) { mutableStateOf(customCompressPrompt.orEmpty()) }
    val utilitySmallInheritText = stringResource(R.string.settings_agent_tool_model_not_set_inherit)
    val utilityLargeInheritText = stringResource(R.string.settings_agent_utility_large_not_set_inherit)

    val utilityFailures by UtilityModelHealth.failures.collectAsStateWithLifecycle()
    var utilityHealthCheckedAt by remember { mutableStateOf(System.currentTimeMillis()) }
    val utilityFailure = remember(utilityFailures, utilityLargeBinding, utilityBinding, utilityHealthCheckedAt) {
        listOfNotNull(utilityLargeBinding, utilityBinding)
            .firstNotNullOfOrNull { binding -> UtilityModelHealth.lastFor(binding.providerId, binding.modelId) }
    }

    LaunchedEffect(utilityFailure) {
        utilityFailure?.let { failure ->
            val expiresAt = failure.at + 24 * 60 * 60 * 1000L
            delay((expiresAt - System.currentTimeMillis() + 1).coerceAtLeast(1))
            utilityHealthCheckedAt = System.currentTimeMillis()
        }
    }

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_task_routing_aux_section),
        onBack = onBack,
    ) {
        // v2.x: División del grupo — El "modelo de diálogo principal" y el "modelo auxiliar" son independientes label(Eliminar la ambigüedad de las capas mixtas)
        item { SectionLabel(stringResource(R.string.settings_task_routing_main_models_label)) }
        item {
            SettingsGroup(modifier = Modifier.padding(top = 4.dp)) {
                // Modelo de diálogo principal(incumplimiento global) — chat principal/Agent modelo maestro compartido
                SettingsItemRow(
                    icon = MuseIcons.chat,
                    title = stringResource(R.string.settings_agent_current_model),
                    subtitle = currentModelName + " · " + stringResource(R.string.settings_agent_current_model_hint),
                    onClick = { showMainModelPicker = true },
                ) {
                    ChevronRight()
                }
                SettingsGroupDivider()
                // Pensamiento profundo de forma predeterminada: no es necesario presionar un botón para cada nueva sesión
                SettingsSwitchRow(
                    icon = MuseIcons.atom,
                    title = stringResource(R.string.settings_chat_default_deep_thinking),
                    subtitle = stringResource(R.string.settings_chat_default_deep_thinking_subtitle),
                    checked = chatPrefs.defaultDeepThinking,
                    onCheckedChange = { v ->
                        scope.launch { settings.updateChatPreferences { it.copy(defaultDeepThinking = v) } }
                    },
                )
            }
        }
        utilityFailure?.let { failure ->
            item(key = "aux_utility_failure") {
                Column(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_task_routing_utility_failed_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text =
                        stringResource(
                            R.string.settings_task_routing_utility_failed_desc,
                            failure.modelId,
                            failure.message,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
        item { SectionLabel(stringResource(R.string.settings_task_routing_aux_models_label)) }
        item {
            SettingsGroup(modifier = Modifier.padding(top = 4.dp)) {
                // Modelo de dispositivo
                SettingsItemRow(
                    icon = MuseIcons.wrench,
                    title = stringResource(R.string.settings_agent_tool_model_title),
                    subtitle = stringResource(R.string.settings_task_routing_utility_small_desc),
                    onClick = {
                        editingProviderId = utilityBinding?.providerId ?: activeProviderId
                        editingUtilityTier = UtilityTier.SMALL
                    },
                ) {
                    ModelValue(
                        text = utilitySmallLabel ?: utilitySmallInheritText,
                        bound = utilityBinding != null,
                    )
                }
                SettingsGroupDivider()
                // modelo de herramienta grande
                SettingsItemRow(
                    icon = MuseIcons.bolt,
                    title = stringResource(R.string.settings_agent_subagent_model_title),
                    subtitle = stringResource(R.string.settings_task_routing_utility_large_desc),
                    onClick = {
                        editingProviderId = utilityLargeBinding?.providerId ?: activeProviderId
                        editingUtilityTier = UtilityTier.LARGE
                    },
                ) {
                    ModelValue(
                        text = utilityLargeLabel ?: utilityLargeInheritText,
                        bound = utilityLargeBinding != null,
                    )
                }
                SettingsGroupDivider()
                // Modelo de compresión: siga el modelo predeterminado del diálogo principal y no lo configure por separado
                SettingsItemRow(
                    icon = MuseIcons.gauge,
                    title = stringResource(R.string.settings_chat_compress_model),
                    subtitle = stringResource(R.string.settings_chat_compress_model_default),
                    onClick = null,
                )
                SettingsGroupDivider()
                // v2.x: Palabras de indicación de compresión personalizadas(Inicio de página de memoria propia;La compresión contextual es responsabilidad de las grandes herramientas)
                SettingsItemRow(
                    icon = MuseIcons.edit,
                    title = stringResource(R.string.settings_memory_custom_compress_prompt),
                    subtitle =
                    customCompressPrompt?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.settings_memory_custom_compress_prompt_default),
                    onClick = {
                        compressPromptDraft = customCompressPrompt.orEmpty()
                        showCompressPromptDialog = true
                    },
                ) { ChevronRight() }
                SettingsGroupDivider()
                // Modelo de ayuda visual(Saltar a configuración de página independiente)
                SettingsItemRow(
                    icon = MuseIcons.eye,
                    title = stringResource(R.string.settings_vision_title),
                    subtitle = visionLabel ?: stringResource(R.string.settings_task_routing_vision_unset),
                    onClick = onOpenVision,
                ) {
                    ChevronRight()
                }
            }
        }
    }

    // v2.x: Ventana emergente de selección de modelo predeterminado global(Agrupar por proveedor,Haga clic para cambiar de proveedor y seleccionar modelo)
    if (showMainModelPicker) {
        MainModelPickerDialog(
            providers = providers,
            activeProviderId = activeProviderId,
            selectedModelId = selectedModelId,
            onPick = { providerId, modelId ->
                scope.launch {
                    settings.setActiveProvider(providerId)
                    settings.saveSelectedModel(modelId)
                }
                showMainModelPicker = false
            },
            onDismiss = { showMainModelPicker = false },
        )
    }

    // v2.x: Ventana emergente de palabra de solicitud de compresión personalizada(Inicio de página de memoria propia)
    if (showCompressPromptDialog) {
        MuseDialog(
            onDismissRequest = { showCompressPromptDialog = false },
            title = stringResource(R.string.settings_memory_custom_compress_prompt),
            content = {
                MuseTextField(
                    value = compressPromptDraft,
                    onValueChange = { compressPromptDraft = it },
                    label = { Text(stringResource(R.string.settings_memory_custom_compress_prompt_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                scope.launch { settings.saveCustomCompressPrompt(compressPromptDraft.trim().takeIf { it.isNotBlank() }) }
                showCompressPromptDialog = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showCompressPromptDialog = false },
        )
    }

    // v2.x: Ventana emergente de edición de modelo auxiliar(artilugio/Gran intercambio de herramientas,ModelSwitchSheet Con selección de proveedor)
    editingUtilityTier?.let { tier ->
        val currentBinding = when (tier) {
            UtilityTier.SMALL -> utilityBinding
            UtilityTier.LARGE -> utilityLargeBinding
            else -> null
        }
        ModelSwitchSheet(
            providers = providers,
            activeProviderId = editingProviderId ?: activeProviderId,
            selectedModelId = currentBinding?.modelId,
            onPickProvider = { providerId -> editingProviderId = providerId },
            onPickModel = { modelId ->
                val providerId = editingProviderId ?: activeProviderId
                val binding = if (modelId != null && providerId != null) {
                    UtilityModelBinding(providerId = providerId, modelId = modelId)
                } else {
                    null
                }
                scope.launch { saveUtilityBinding(settings, tier, binding) }
                editingUtilityTier = null
                editingProviderId = null
            },
            // Los modelos auxiliares requieren"vinculación explícita"con"no obligado(cascada)"distinguible;
            // El ModelSwitchSheet del chat normal aún conserva la semántica de enlace clara original.
            onPickDefaultModel = {
                val providerId = editingProviderId ?: activeProviderId
                val defaultModelId = providers.firstOrNull { it.id == providerId }
                    ?.models?.firstOrNull()?.id
                val binding = if (providerId != null && defaultModelId != null) {
                    UtilityModelBinding(providerId = providerId, modelId = defaultModelId)
                } else {
                    null
                }
                scope.launch { saveUtilityBinding(settings, tier, binding) }
                editingUtilityTier = null
                editingProviderId = null
            },
            onRefreshModels = { /* La lista de modelos se mantiene en la página de configuración del proveedor. */ },
            isFetchingModels = false,
            fetchModelsError = null,
            onDismiss = {
                editingUtilityTier = null
                editingProviderId = null
            },
        )
    }
}

/** v2.x: Guardar modelo auxiliar vinculante por engranaje(null=encuadernación clara,toma la cascada). */
private suspend fun saveUtilityBinding(settings: SettingsRepository, tier: UtilityTier, binding: UtilityModelBinding?) {
    when (tier) {
        UtilityTier.SMALL -> settings.saveUtilityModelBinding(binding)
        UtilityTier.LARGE -> settings.saveUtilityLargeModelBinding(binding)
        else -> Unit
    }
}

/**
 * v2.x: Ventana emergente de selección de modelo predeterminado global — Listar todos los modelos agrupados por Proveedor,
 * Haga clic para cambiar para activar el proveedor y establecer el modelo predeterminado global(dialogo principal/Agent compartir).
 */
@Composable
private fun MainModelPickerDialog(
    providers: List<io.zer0.ai.core.ProviderConfig>,
    activeProviderId: String?,
    selectedModelId: String?,
    onPick: (providerId: String, modelId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_agent_select_model),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                providers.forEach { provider ->
                    if (provider.models.isNotEmpty()) {
                        Text(
                            text = provider.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                        provider.models.forEach { model ->
                            val isSelected = provider.id == activeProviderId && model.id == selectedModelId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(provider.id, model.id) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = model.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                    // LAYOUT-01: Row Anchura el texto interior + limita la línea para evitar que el ícono de verificación a la derecha lo comprima en una columna al ampliar el tamaño de fuente
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = MuseIcons.check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (providers.isEmpty() || providers.all { it.models.isEmpty() }) {
                    Text(
                        text = stringResource(R.string.settings_agent_no_models_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        },
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    )
}

/** Valor del modelo de cola — Alinear a la derecha el valor de una sola línea + flecha estándar.
 *
 * v2.x: Reemplazar originales“Las cápsulas contienen frases largas + flechas incrustadas.”estilo — El texto largo apretará la barra de título,Y se repite la semántica de las flechas en línea y los clics en las filas.
 * Atado = color normal;no obligado = debilitar el color("heredar/Reutilizar"Consejos);Elipses extralargas.
 */
@Composable
private fun ModelValue(text: String, bound: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (bound) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 140.dp),
        )
        Spacer(Modifier.width(4.dp))
        ChevronRight()
    }
}
