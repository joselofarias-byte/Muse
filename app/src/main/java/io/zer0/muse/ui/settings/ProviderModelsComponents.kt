@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod", "TooManyFunctions", "ReturnCount", "TooGenericExceptionCaught", "SwallowedException", "MaxLineLength", "ComplexCondition", "UseCheckOrError")

package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.tablericons.Search
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.ModelContextWindowRegistry
import io.zer0.ai.core.ModelRegistry
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.common.AppJson
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun ModelsTab(
    config: ProviderConfig,
    modelsState: SnapshotStateList<Model>,
    isFetching: Boolean,
    onFetch: (Boolean) -> Unit,
    onAddModel: () -> Unit,
    // v1.0.8 (7.6): Devolución de llamada de verificación de estado del modelo + transmisión transparente de estado(Pasado desde ProviderEditPage)
    onTestModel: (Model) -> Unit = {},
    modelTestStatuses: Map<String, ModelTestStatus> = emptyMap(),
) {
    var editingModel by remember { mutableStateOf<Model?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (modelsState.isEmpty()) {
            // P2-5: usar weight(1f) Deje que el estado vacío llene el espacio restante debajo del botón
            //  (Column En fillMaxSize, obtendrás la altura completa del padre.,en lugar de altura restante,causar desbordamiento)
            EmptyModelsState(
                isFetching = isFetching,
                onFetch = onFetch,
                onAddModel = onAddModel,
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = MusePaddings.contentGap),
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MuseCapsuleButton(
                            text = stringResource(R.string.settings_provider_clear_models),
                            onClick = { modelsState.clear() },
                            enabled = modelsState.isNotEmpty(),
                            variant = IosCapsuleButtonVariant.Text,
                            fillWidth = false,
                        )
                        MuseCapsuleButton(
                            text = stringResource(R.string.settings_provider_refresh_models),
                            onClick = { onFetch(true) },
                            enabled = !isFetching,
                            loading = isFetching,
                            variant = IosCapsuleButtonVariant.Text,
                            fillWidth = false,
                        )
                    }
                }
                items(modelsState.toList(), key = { "${it.providerId}:${it.id}" }) { model ->
                    ProviderModelRow(
                        model = model,
                        providerType = config.type,
                        providerName = config.displayName,
                        isAdded = true,
                        onAction = { editingModel = model },
                        // v1.0.8 (7.6): Pasar la devolución de llamada de verificación de estado + estado actual(Consulta por modelo.id)
                        onTest = { onTestModel(model) },
                        testStatus = modelTestStatuses[model.id] ?: ModelTestStatus.Idle,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 68.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = 0.5.dp,
                    )
                }
            }
        }
    }

    editingModel?.let { model ->
        ModelAbilityEditorDialog(
            model = model,
            onDismiss = { editingModel = null },
            onSave = { updated ->
                val idx = modelsState.indexOfFirst { it.id == updated.id }
                if (idx >= 0) modelsState[idx] = updated
                editingModel = null
            },
            onDelete = {
                modelsState.removeAll { it.id == model.id }
                editingModel = null
            },
        )
    }
}

@Composable
internal fun ModelAbilityEditorDialog(model: Model, onDismiss: () -> Unit, onSave: (Model) -> Unit, onDelete: () -> Unit) {
    var supportsTools by remember { mutableStateOf(model.abilities.contains(ModelAbility.TOOL) || model.abilities.isEmpty()) }
    var supportsReasoning by remember { mutableStateOf(model.abilities.contains(ModelAbility.REASONING) || model.abilities.isEmpty()) }
    var supportsStreaming by remember { mutableStateOf(model.supportsStreaming) }
    var supportsImageOutput by remember { mutableStateOf(model.supportsImageOutput()) }
    var supportsVision by remember { mutableStateOf(model.supportsVisionInput()) }
    var supportsVideo by remember { mutableStateOf(model.supportsVideo) }
    var contextWindow by remember { mutableStateOf(model.contextWindow?.toString() ?: "") }
    var maxOutputTokens by remember { mutableStateOf(model.maxOutputTokens?.toString() ?: "") }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_provider_edit_model_abilities),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = model.id,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        // LAYOUT-01: La identificación del modelo toma el ancho restante + límite de línea(id puede ser muy largo),
                        // Evite que el botón "Detección con un clic" a la derecha lo apriete en una fila.
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // v1.97: Capacidad de detección automática con un solo clic(Coincidencia basada en el token ModelRegistry)
                    MuseTactileButton(
                        icon = MuseIcons.wand,
                        onClick = {
                            val abilities = ModelRegistry.lookupAbilities(model.id)
                            val inputMods = ModelRegistry.lookupInputModalities(model.id)
                            val outputMods = ModelRegistry.lookupOutputModalities(model.id)
                            supportsTools = ModelAbility.TOOL in abilities
                            supportsReasoning = ModelAbility.REASONING in abilities
                            supportsVision = "image" in inputMods
                            supportsImageOutput = "image" in outputMods
                            // Cuando contextWindow no está configurado explícitamente,Por cierto, completa el formulario de registro para obtener el valor más bajo.
                            if (contextWindow.isBlank()) {
                                ModelContextWindowRegistry.lookup(model.id)?.let {
                                    contextWindow = it.toString()
                                }
                            }
                        },
                        contentDescription = null,
                    )
                }
                Spacer(Modifier.size(MusePaddings.contentGap))
                AbilitySwitchRow(stringResource(R.string.settings_provider_ability_tools), supportsTools) { supportsTools = it }
                AbilitySwitchRow(stringResource(R.string.settings_provider_ability_reasoning), supportsReasoning) { supportsReasoning = it }
                AbilitySwitchRow(stringResource(R.string.settings_provider_ability_streaming), supportsStreaming) { supportsStreaming = it }
                AbilitySwitchRow(
                    stringResource(R.string.settings_provider_ability_image_output),
                    supportsImageOutput,
                ) { supportsImageOutput = it }
                AbilitySwitchRow(stringResource(R.string.settings_provider_ability_vision), supportsVision) { supportsVision = it }
                AbilitySwitchRow(stringResource(R.string.settings_provider_ability_video), supportsVideo) { supportsVideo = it }
                Spacer(Modifier.size(MusePaddings.itemGap))
                MuseTextField(
                    value = contextWindow,
                    onValueChange = { contextWindow = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.settings_provider_context_window)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.size(MusePaddings.contentGap))
                MuseTextField(
                    value = maxOutputTokens,
                    onValueChange = { maxOutputTokens = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.settings_provider_max_output_tokens)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.size(MusePaddings.itemGap))
                MuseCapsuleButton(
                    text = stringResource(R.string.settings_provider_remove_model),
                    onClick = onDelete,
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        },
        confirmText = stringResource(R.string.settings_common_save),
        onConfirm = {
            val newAbilities = buildSet {
                if (supportsTools) add(ModelAbility.TOOL)
                if (supportsReasoning) add(ModelAbility.REASONING)
            }
            val newOutput = buildSet {
                add("text")
                if (supportsImageOutput) add("image")
                if (supportsVideo) add("video")
            }
            val newInput = buildSet {
                add("text")
                if (supportsVision) add("image")
            }
            onSave(
                model.copy(
                    abilities = newAbilities,
                    supportsStreaming = supportsStreaming,
                    supportsVision = supportsVision,
                    supportsVideo = supportsVideo,
                    outputModalities = newOutput,
                    inputModalities = newInput,
                    contextWindow = contextWindow.toIntOrNull(),
                    maxOutputTokens = maxOutputTokens.toIntOrNull(),
                    // v2.6.1: Marcar usuario para edición explícita —— Impide que EnhanceModel anule estas configuraciones con el registro.
                    userEdited = true,
                ),
            )
        },
        dismissText = stringResource(R.string.settings_common_cancel),
        onDismiss = onDismiss,
    )
}

@Composable
internal fun AbilitySwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.tightGap),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // LAYOUT-01: El nombre de la habilidad toma el ancho restante + límite de línea.,Evite que el MuseSwitch correcto lo apriete
        // (Formulario de incidencias online con el mismo origen que SettingsSwitchRow).
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        MuseSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
internal fun EmptyModelsState(
    isFetching: Boolean,
    onFetch: (Boolean) -> Unit,
    onAddModel: () -> Unit,
    // P2-5: entrante externo modifier,Permite que la persona que llama use weight(1f) Deja que ocupe el espacio restante.
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.settings_provider_no_models),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.settings_provider_no_models_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.size(MuseIconSizes.icon))
        Row(
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        ) {
            MuseCapsuleButton(
                text = stringResource(R.string.settings_provider_refresh_models),
                onClick = { onFetch(true) },
                enabled = !isFetching,
                loading = isFetching,
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
            )
            MuseTactileButton(
                icon = MuseIcons.plus,
                onClick = onAddModel,
                contentDescription = null,
            )
        }
    }
}

/**
 * La hoja inferior que aparece después de extraer con éxito el modelo ascendente.
 *
 * Puntos de mejora:
 *  - No más contexto de escritura manual(K),Para uso unificado [ModelContextWindowRegistry] inferencia automática
 *  - La casilla de verificación cambió a clic en la fila + marca de verificación derecha
 *  - La barra de búsqueda, los títulos de los grupos y los botones inferiores utilizan colores cálidos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FetchedModelsPickerSheet(
    models: List<Model>,
    existingIds: Set<String>,
    providerType: ProviderType,
    providerName: String,
    onDismiss: () -> Unit,
    onConfirm: (List<Model>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var groupExpanded by remember { mutableStateOf(true) }

    val filtered = remember(query, models) {
        if (query.isBlank()) models else models.filter { it.id.contains(query, ignoreCase = true) }
    }

    // La cantidad seleccionada se almacena en caché utilizando deriveStateOf,Evite que confirmText se reestructure cada vez count
    val selectedCount by remember {
        derivedStateOf { filtered.count { selected[it.id] == true } }
    }

    // MuseDialog sustituto original ModalBottomSheet,Evite los atascos de malla en máquinas reales
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_provider_select_models),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.settings_provider_models_count, models.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                Spacer(Modifier.size(MusePaddings.itemGap))

                // Barra de búsqueda ranurada
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = MuseShapes.small,
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    Icon(
                        imageVector = MuseIcons.search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        // v2.0: placeholder Cambia tu ruta decorationBox(Uso estándar)y declarar la acción de búsqueda de IME,
                        // Alinee los comportamientos de lectura de pantalla y métodos de entrada con el cuadro de búsqueda del sistema.
                        androidx.compose.foundation.text.BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                            ),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (query.isBlank()) {
                                        Text(
                                            text = stringResource(R.string.settings_provider_search_models),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                    }
                    if (query.isNotBlank()) {
                        MuseTactileButton(
                            icon = MuseIcons.x,
                            onClick = { query = "" },
                            contentDescription = stringResource(R.string.settings_provider_clear),
                            tint = MaterialTheme.colorScheme.outline,
                            iconSize = MuseIconSizes.iconSmall,
                        )
                    }
                }

                // Seleccionar todo/Borrar
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = MusePaddings.contentGap),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = stringResource(R.string.settings_provider_select_all),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { filtered.forEach { selected[it.id] = true } },
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.settings_provider_clear),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { filtered.forEach { selected[it.id] = false } },
                    )
                }

                // Título del grupo(plegable)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { groupExpanded = !groupExpanded }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ProviderLogo(
                        type = providerType,
                        name = providerName,
                        size = MuseIconSizes.iconLarge,
                        cornerRadius = 10.dp,
                    )
                    Text(
                        text = providerName.ifBlank { stringResource(R.string.settings_provider_model_list) },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (groupExpanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                        contentDescription = if (groupExpanded) {
                            stringResource(
                                R.string.settings_common_collapse,
                            )
                        } else {
                            stringResource(R.string.settings_common_expand)
                        },
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Lista de modelos(Cambiar a Column,MuseDialog El área de contenido viene con desplazamiento)
                if (groupExpanded) {
                    filtered.forEach { m ->
                        val isSelected = selected[m.id] ?: false
                        val effectiveTokens = m.contextWindow ?: ModelContextWindowRegistry.lookup(m.id)
                        val contextText = formatContextWindow(effectiveTokens, stringResource(R.string.settings_provider_unknown))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected[m.id] = !isSelected }
                                .padding(vertical = MusePaddings.itemGap),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                        ) {
                            // Estado seleccionado a la izquierda:círculo de marca de verificación
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .background(
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                        },
                                        shape = CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = MuseIcons.check,
                                        contentDescription = stringResource(R.string.settings_provider_selected),
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(MuseIconSizes.iconTiny),
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = m.id,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                    if (m.id in existingIds) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            stringResource(R.string.settings_provider_already_exists),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                }
                                if (m.name != m.id) {
                                    Text(
                                        text = m.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                ModelAbilityChips(model = m)
                            }

                            // Etiqueta de ventana contextual
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MuseShapes.small,
                            ) {
                                Text(
                                    text = contextText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        },
        confirmText = stringResource(R.string.settings_provider_add_with_count, selectedCount),
        onConfirm = {
            onConfirm(
                filtered.filter { selected[it.id] == true }.map { m ->
                    m.copy(
                        contextWindow = m.contextWindow ?: ModelContextWindowRegistry.lookup(m.id),
                    )
                },
            )
        },
        dismissText = stringResource(R.string.settings_common_cancel),
        onDismiss = onDismiss,
    )
}

/** Formatee el número de token como K/M para mostrarlo(Como por ejemplo 128000 -> "128K", 1000000 -> "1M").null Regresar [unknownLabel]. */
internal fun formatContextWindow(tokens: Int?, unknownLabel: String): String {
    if (tokens == null) return unknownLabel
    return when {
        tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
        tokens >= 1000 -> "${tokens / 1000}K"
        else -> tokens.toString()
    }
}

/**
 * Analice el contenido del archivo JSON de la cuenta de servicio de Google.
 *
 * Google Service Account JSON Campos estándar(Lleva solo los tres artículos que necesitas.):
 *  - `private_key`: PEM PKCS#8 cuerda
 *  - `client_email`: Correo electrónico de la cuenta de servicio
 *  - `project_id`: GCP Proyecto ID
 *
 * @return Triple(email, privateKey, projectId);Devolver si falta algún campo clave null
 */
internal fun parseServiceAccountJson(jsonText: String): Triple<String, String, String>? {
    return runCatching {
        val obj = AppJson.decodeFromString(JsonObject.serializer(), jsonText)
        val email = obj["client_email"]?.jsonPrimitive?.contentOrNull ?: return null
        val key = obj["private_key"]?.jsonPrimitive?.contentOrNull ?: return null
        val projectId = obj["project_id"]?.jsonPrimitive?.contentOrNull ?: ""
        Triple(email, key, projectId)
    }.getOrNull()
}

/**
 * Extraiga valores de una cadena JSON mediante una ruta JSON con puntos simples.
 *
 * Admite campos de objetos y subíndices de matrices(Como por ejemplo $.data.total_usage o $.data.items.0.value).
 * Devuelve nulo si falla el análisis o la ruta no existe.
 */
internal fun extractJsonPath(json: String, path: String): String? {
    val keys = path.trim()
        .removePrefix("$")
        .removePrefix(".")
        .split(".")
        .filter { it.isNotBlank() }
    val root = runCatching {
        Json.parseToJsonElement(json)
    }.getOrNull() ?: return null
    var current: JsonElement = root
    for (key in keys) {
        current = when (current) {
            is JsonObject -> current[key] ?: return null
            is JsonArray -> {
                val index = key.toIntOrNull() ?: return null
                current.getOrNull(index) ?: return null
            }
            else -> return current.toString()
        }
    }
    return when (current) {
        is JsonPrimitive -> current.contentOrNull ?: current.toString()
        else -> current.toString()
    }
}

/** Dar formato al mapa en cada fila "Key: Value" de texto de varias líneas. */
internal fun formatCustomHeaders(map: Map<String, String>): String {
    return map.entries.joinToString("\n") { "${it.key}: ${it.value}" }
}

/** poner cada linea "Key: Value" analizado como Map,Se ignoran las líneas con formatos ilegales. */
internal fun parseCustomHeaders(text: String): Map<String, String> {
    return text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx == -1) return@mapNotNull null
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (key.isEmpty()) return@mapNotNull null
            key to value
        }
        .toMap()
}

/** Formatear el mapa en una cadena JSON,Devuelve una cadena vacía en caso de error. */
internal fun formatCustomBody(map: Map<String, JsonElement>): String {
    if (map.isEmpty()) return ""
    return runCatching {
        AppJson.encodeToString(JsonObject.serializer(), JsonObject(map))
    }.getOrDefault("")
}

/** Analiza la cadena JSON en Map,Devuelve un mapa vacío en caso de error. */
internal fun parseCustomBody(text: String): Map<String, JsonElement> {
    val trimmed = text.trim()
    if (trimmed.isBlank()) return emptyMap()
    return runCatching {
        AppJson.parseToJsonElement(trimmed).jsonObject.toMap()
    }.getOrDefault(emptyMap())
}
