package io.zer0.muse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.AppJson
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.tools.SkillExecutor
import io.zer0.muse.tools.SkillImporter
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.compose.koinInject

/**
 * Phase 8.8: Skill(Habilidades/Herramientas)Página de gestión.
 *
 * enumerar todo Skill,Habilitación de soporte/Deshabilitar el cambio y la visualización de detalles(Si el parámetro Schema / id / está integrado).
 * Sigue visualmente el estilo musa de papel cálido.(SettingsGroup + SectionLabel).
 *
 * Las habilidades integradas son proporcionadas por [MuseApp] Al iniciar seed(ver [SkillExecutor.BUILT_IN_SKILLS]),
 * Esta página es de solo lectura para mostrar su metainformación.,No se puede eliminar/Editar(Las habilidades personalizadas se pueden ampliar más adelante).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillScreen(onBack: () -> Unit, skillRepository: SkillRepository = koinInject()) {
    val skills by skillRepository.observeAll.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val localizedResources = androidx.compose.ui.platform.LocalResources.current
    var detailTarget by remember { mutableStateOf<SkillEntity?>(null) }
    var deleteConfirmTarget by remember { mutableStateOf<SkillEntity?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importDialogVisible by remember { mutableStateOf(false) }
    val builtInIds = remember {
        SkillExecutor.BUILT_IN_SKILLS.map { it.id }.toSet()
    }

    // v0.23: .skill.json Importación de archivos launcher
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            importDialogVisible = true
            try {
                val text = withContext(Dispatchers.IO) {
                    // v1.73: usar use{} Asegúrese de que InputStream/BufferedReader esté cerrado,Evite fugas de FD
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        // v1.114: Limitar lectura 10MB,Prevenir archivos muy grandes OOM
                        val MAX_READ_BYTES = 10L * 1024 * 1024
                        val sb = StringBuilder()
                        val buffer = CharArray(8192)
                        var total = 0L
                        input.bufferedReader().use { reader ->
                            while (true) {
                                val read = reader.read(buffer)
                                if (read <= 0) break
                                total += read
                                if (total > MAX_READ_BYTES) {
                                    error("Archivo demasiado grande,exceder ${MAX_READ_BYTES / 1024 / 1024}MB Límite")
                                }
                                sb.append(buffer, 0, read)
                            }
                        }
                        sb.toString()
                    } ?: ""
                }
                val recipeResult = SkillImporter.parseRecipe(text)
                if (recipeResult is SkillImporter.RecipeParseResult.Ok) {
                    var count = 0
                    recipeResult.skills.forEach { skill ->
                        resultOf { skillRepository.upsert(skill) }
                            .onSuccess { count++ }
                            .onError { msg, _ -> MuseToast.show(context.getString(R.string.skill_operation_failed, msg)) }
                    }
                    importMessage = context.getString(R.string.skill_recipe_imported, count)
                } else {
                    when (val result = SkillImporter.parse(text)) {
                        is SkillImporter.Result.Ok -> {
                            // M-SKUI1: Utilice resultOf en su lugar runCatching,evitar tragar CancellationException
                            resultOf { skillRepository.upsert(result.skill) }
                                .onSuccess { importMessage = context.getString(R.string.skill_imported, result.skill.name) }
                                .onError { msg, _ -> importMessage = context.getString(R.string.skill_import_failed, msg) }
                        }
                        is SkillImporter.Result.Err -> importMessage = result.reason
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // H-SKUI1: Detectar excepciones en el proceso de importación,Establecer avisos de error para evitar fallas
                importMessage = context.getString(R.string.skill_import_error, e.message?.take(80))
            } finally {
                importing = false
                importDialogVisible = false
            }
        }
    }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.skill_management),
                onBack = onBack,
                actions = {
                    MuseTactileButton(
                        icon = MuseIcons.share,
                        onClick = {
                            scope.launch {
                                val list = (skills ?: emptyList()).filterNot { it.id in builtInIds }
                                if (list.isEmpty()) {
                                    MuseToast.show(context.getString(R.string.skill_recipe_empty))
                                    return@launch
                                }
                                val json = resultOf { SkillImporter.exportRecipe(list) }.getOrNull()
                                if (json != null) {
                                    val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(android.content.Intent.EXTRA_TEXT, json)
                                        putExtra(android.content.Intent.EXTRA_SUBJECT, "Muse Skills")
                                    }
                                    runCatching { context.startActivity(android.content.Intent.createChooser(sendIntent, null)) }
                                }
                            }
                        },
                        contentDescription = stringResource(R.string.skill_recipe_export_cd),
                        enabled = !importing,
                    )
                    MuseTactileButton(
                        icon = MuseIcons.upload,
                        onClick = {
                            runCatching { importLauncher.launch(arrayOf("application/json", "text/plain")) }
                                .onFailure { importMessage = context.getString(R.string.skill_open_picker_failed, it.message) }
                        },
                        contentDescription = stringResource(R.string.skill_import_cd),
                        enabled = !importing,
                    )
                },
            )
        },
    ) { innerPadding ->
        val skillList = skills
        when {
            skillList == null -> {
                Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                    MuseSpinner()
                }
            }
            else -> {
                // v1.71: Las propiedades delegadas no pueden smart-cast,Capturar valores no nulos con variables locales,Eliminar duplicados !!
                // L-SK1: Después de capturar con valor local, el compilador automáticamente lo envía de forma inteligente en la rama else para que no sea nulo.,No es necesario !!
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.skill_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    // v2.6.13: Inicio y parada de habilidades personalizadas con un solo clic.
                    // Solo funciona en habilidades no integradas ni complementarias: las herramientas integradas son las funciones de esta aplicación, y las habilidades complementarias comienzan y terminan con el complemento.
                    bulkToggleTargets(skillList, builtInIds).takeIf { it.isNotEmpty() }?.let { targets ->
                        item {
                            val enabledCount = targets.count { it.enabled }
                            val allEnabled = enabledCount == targets.size
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.skill_bulk_summary,
                                        targets.size,
                                        enabledCount,
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = stringResource(
                                        if (allEnabled) {
                                            R.string.skill_bulk_disable_all
                                        } else {
                                            R.string.skill_bulk_enable_all
                                        },
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clickable {
                                            scope.launch {
                                                val target = !allEnabled
                                                targets.forEach { skill ->
                                                    resultOf { skillRepository.setEnabled(skill.id, target) }
                                                        .onError { msg, _ ->
                                                            MuseToast.show(
                                                                context.getString(R.string.skill_operation_failed, msg),
                                                            )
                                                        }
                                                }
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                    items(skillList, key = { it.id }) { skill ->
                        SkillRow(
                            skill = skill,
                            isBuiltIn = skill.id in builtInIds,
                            onToggleEnabled = { enabled ->
                                scope.launch {
                                    // M-SKUI1: Utilice resultOf en su lugar runCatching,evitar tragar CancellationException
                                    resultOf { skillRepository.setEnabled(skill.id, enabled) }
                                        .onError { msg, _ -> MuseToast.show(context.getString(R.string.skill_operation_failed, msg)) }
                                }
                            },
                            onShowDetail = { detailTarget = skill },
                        )
                    }
                    if (skillList.isEmpty()) {
                        item {
                            MuseEmptyState(
                                icon = MuseIcons.puzzle,
                                title = stringResource(R.string.skill_no_skill_title),
                                subtitle = stringResource(R.string.skill_no_skill_subtitle),
                            )
                        }
                    }
                }
            }
        }
    }

    detailTarget?.let { skill ->
        SkillDetailDialog(
            skill = skill,
            isBuiltIn = skill.id in builtInIds,
            onDismiss = { detailTarget = null },
            onDelete = if (skill.id in builtInIds) {
                null
            } else {
                {
                    detailTarget = null
                    deleteConfirmTarget = skill
                }
            },
            // v1.0.92: Exportar/Compartir habilidad como .skill.json(Se puede volver a importar)
            onExport = {
                val json = resultOf { SkillImporter.exportToJson(skill) }
                    .onError { msg, _ -> MuseToast.show(context.getString(R.string.skill_operation_failed, msg)) }
                    .getOrNull()
                if (json != null) {
                    val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(android.content.Intent.EXTRA_TEXT, json)
                        putExtra(android.content.Intent.EXTRA_SUBJECT, skill.name)
                    }
                    resultOf {
                        context.startActivity(android.content.Intent.createChooser(sendIntent, null))
                    }.onError { msg, _ ->
                        MuseToast.show(context.getString(R.string.skill_operation_failed, msg))
                    }
                }
            },
            // v1.0.92: Restaurar habilidades integradas a las definiciones de fábrica(Sólo los elementos integrados proporcionan acceso;Restablecimiento y reactivación de idempotente)
            onRestore = if (skill.id in builtInIds) {
                {
                    val template = SkillExecutor.BUILT_IN_SKILLS.firstOrNull { it.id == skill.id }
                    if (template != null) {
                        scope.launch {
                            resultOf { skillRepository.upsert(template) }
                                .onSuccess {
                                    MuseToast.show(
                                        context.getString(R.string.skill_restored, skill.name.ifBlank { skill.id }),
                                    )
                                }
                                .onError { msg, _ ->
                                    MuseToast.show(context.getString(R.string.skill_operation_failed, msg))
                                }
                        }
                    }
                }
            } else {
                null
            },
        )
    }

    deleteConfirmTarget?.let { skill ->
        MuseDialog(
            onDismissRequest = { deleteConfirmTarget = null },
            title = stringResource(R.string.skill_delete_confirm_title),
            content = {
                Text(
                    text = stringResource(R.string.skill_delete_confirm_message, skill.name.ifBlank { skill.id }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.skill_delete),
            onConfirm = {
                deleteConfirmTarget = null
                scope.launch {
                    resultOf { skillRepository.delete(skill.id) }
                        .onError { msg, _ -> MuseToast.show(localizedResources.getString(R.string.skill_delete_failed_ui, msg)) }
                }
            },
            dismissText = stringResource(R.string.skill_close),
            onDismiss = { deleteConfirmTarget = null },
            destructive = true,
        )
    }

    // v1.0.92: Los resultados de la importación se cambian a una sola vez Toast — La implementación anterior usa AlertDialog y es necesario hacer clic manualmente"Lo tengo",
    // Los usuarios se confunden fácilmente cuando se les superpone la ventana emergente de progreso de la importación.(auditoría A-4);Toast desaparecer automáticamente,éxito/El fracaso es directo.
    LaunchedEffect(importMessage) {
        importMessage?.let { msg ->
            MuseToast.show(msg)
            importMessage = null
        }
    }

    if (importDialogVisible) {
        MuseDialog(
            // Al regresar, solo se desactiva la visualización del progreso, la tarea de importación continúa ejecutándose y los resultados se actualizan cuando se completa.
            onDismissRequest = { importDialogVisible = false },
            title = stringResource(R.string.skill_importing),
            content = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MuseSpinner(
                        size = 28.dp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.skill_importing_detail), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmText = "",
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Composable
private fun SkillRow(skill: SkillEntity, isBuiltIn: Boolean, onToggleEnabled: (Boolean) -> Unit, onShowDetail: () -> Unit) {
    val unnamedText = stringResource(R.string.skill_unnamed)
    val stateLabel = if (skill.enabled) stringResource(R.string.skill_enabled) else stringResource(R.string.skill_disabled)
    // captación previa semantics contentDescription(semantics lambda no @Composable,no se puede llamar directamente stringResource)
    val rowCd = stringResource(R.string.skill_row_cd, skill.name.ifBlank { unnamedText }, stateLabel)
    SettingsGroup {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onShowDetail() }
                .semantics {
                    contentDescription = rowCd
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = MuseIcons.puzzle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = skill.name.ifBlank { unnamedText },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (skill.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    )
                    if (!skill.enabled) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = stringResource(R.string.skill_disabled),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (isBuiltIn) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = stringResource(R.string.skill_built_in),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (skill.description.isNotEmpty()) {
                    Text(
                        text = skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            MuseSwitch(
                checked = skill.enabled,
                onCheckedChange = onToggleEnabled,
                modifier = Modifier.semantics {
                    stateDescription = stateLabel
                },
            )
            MuseTactileButton(
                icon = MuseIcons.info,
                onClick = onShowDetail,
                contentDescription = stringResource(R.string.skill_detail_cd),
                size = MuseIconSizes.touchTarget,
                iconSize = 20.dp,
            )
        }
    }
}

@Composable
private fun SkillDetailDialog(
    skill: SkillEntity,
    isBuiltIn: Boolean,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    /** v1.0.92: Exportar/Compartir devolución de llamada(.skill.json texto,Se puede volver a importar). */
    onExport: (() -> Unit)? = null,
    /** v1.0.92: Restaurar devolución de llamada predeterminada(Solo proporcionado por habilidades integradas;null La entrada no se muestra cuando). */
    onRestore: (() -> Unit)? = null,
) {
    val unnamedText = stringResource(R.string.skill_unnamed)
    val builtInSeedText = stringResource(R.string.skill_built_in_seed)
    val customText = stringResource(R.string.skill_custom)
    MuseDialog(
        onDismissRequest = onDismiss,
        title = skill.name.ifBlank { unnamedText },
        content = {
            // v1.0.7 arreglar falla:MuseDialog El área de contenido viene con verticalScroll,El anidamiento fallará
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (skill.description.isNotEmpty()) {
                    Text(
                        text = skill.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.skill_parameters_schema),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                // v1.???: Analizar el esquema JSON sin formato en una lista de parámetros legible
                ParameterSchemaView(
                    parametersJson = skill.parametersJson,
                    requiredSet = skill.requiredJson
                        .takeIf { it.isNotBlank() }
                        ?.let { json ->
                            resultOf {
                                AppJson.decodeFromString(kotlinx.serialization.json.JsonArray.serializer(), json)
                                    .mapNotNull { it.jsonPrimitive.contentOrNull }
                                    .toSet()
                            }.getOrNull()
                        } ?: emptySet(),
                )
                HorizontalDivider()
                DetailLine(label = "ID", value = skill.id)
                DetailLine(label = stringResource(R.string.skill_label_category), value = skill.category)
                DetailLine(label = stringResource(R.string.skill_label_implementation), value = skill.implementationKotlin)
                DetailLine(
                    label = stringResource(R.string.skill_label_source),
                    value = if (isBuiltIn) builtInSeedText else customText,
                )
                // v0.29 P1-5: Ejemplos de escenarios de uso de capacidades(Ayude a los usuarios a comprender cuándo activar)
                rememberSkillUseCases()[skill.id]?.let { useCase ->
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.skill_use_cases),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = useCase,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // v1.0.92: Exportar/Comparte esto skill(.skill.json,Se puede volver a importar)
                if (onExport != null) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MuseShapes.medium)
                            .clickable(onClick = onExport)
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = MuseIcons.share,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                        Text(
                            text = stringResource(R.string.action_share),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                // v1.0.92: Restaurar valor predeterminado(Solo incorporado skill;Restablecer las definiciones de fábrica y volver a habilitar)
                if (onRestore != null) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MuseShapes.medium)
                            .clickable(onClick = onRestore)
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = MuseIcons.refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                        Text(
                            text = stringResource(R.string.skill_restore_default),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.skill_close),
        onConfirm = onDismiss,
        dismissText = if (onDelete != null) stringResource(R.string.skill_delete) else null,
        onDismiss = onDelete,
    )
}

/**
 * poner parametersJson(JSON Schema) Representado en una lista de parámetros legible.
 * Visualización de cada parámetro.:nombre,type, si es necesario,description.
 */
@Composable
private fun ParameterSchemaView(parametersJson: String, requiredSet: Set<String>) {
    val params: List<Pair<String, JsonObject?>> = remember(parametersJson) {
        val schemaObj = resultOf<JsonObject> {
            AppJson.decodeFromString(JsonObject.serializer(), parametersJson)
        }.getOrNull() ?: return@remember emptyList()
        val properties = schemaObj["properties"]?.jsonObject ?: return@remember emptyList()
        properties.entries.map { (name, def) ->
            name to (def as? JsonObject)
        }
    }

    if (params.isEmpty()) {
        Text(
            text = parametersJson.ifBlank { "{}" },
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MuseMonoFontFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        params.forEach { (name, def) ->
            val type = def?.get("type")?.jsonPrimitive?.contentOrNull ?: "string"
            val description = def?.get("description")?.jsonPrimitive?.contentOrNull ?: ""
            val isRequired = name in requiredSet
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = type,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (isRequired) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = "Requerido",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** v0.29 P1-5: Descripción del escenario de uso de habilidades integradas(Ayude a los usuarios a comprender cuándo activar). */
@Composable
private fun rememberSkillUseCases(): Map<String, String> = mapOf(
    "read_file" to stringResource(R.string.skill_use_case_read_file),
    "write_file" to stringResource(R.string.skill_use_case_write_file),
    "http_get" to stringResource(R.string.skill_use_case_http_get),
    "http_post" to stringResource(R.string.skill_use_case_http_post),
    "web_search" to stringResource(R.string.skill_use_case_web_search),
    "web_fetch" to stringResource(R.string.skill_use_case_web_fetch),
    "knowledge_search" to stringResource(R.string.skill_use_case_knowledge_search),
    "arxiv_search" to stringResource(R.string.skill_use_case_arxiv_search),
    "install_skill" to stringResource(R.string.skill_use_case_install_skill),
)

@Composable
private fun DetailLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // LAYOUT-01: El prefijo de etiqueta fijo al comienzo de la línea solo se limita a la línea y no toma el ancho (el ancho restante se deja hacia el lado derecho para tomar el valor)
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
        )
        // LAYOUT-01: El valor es el texto principal y se toma el ancho restante; el contenido puede ser más largo (como se muestra en las instrucciones de implementación) y se conserva la capacidad de ajustar varias líneas.
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Una colección de funciones de "inicio y parada con un solo botón".
 *
 * Se excluyen dos categorías:
 *  - Habilidades incorporadas (`SkillExecutor.BUILT_IN_SKILLS`): Son los brazos y piernas de esta aplicación,
 *    Desactivarlo por completo equivale a quitar todas las herramientas del modelo, que no es lo que el usuario quiere expresar cuando hace clic en "Desactivar todo";
 *  - Habilidades de complemento (`plugin:` Enrutamiento): Su inicio y parada siguen al complemento, y los cambios individuales entrarán en conflicto con el estado del complemento.
 *
 * Se extrae la función pura porque este criterio determina el impacto de "un clic" y merece una prueba por separado.
 */
internal fun bulkToggleTargets(
    skills: List<SkillEntity>,
    builtInIds: Set<String>,
): List<SkillEntity> = skills.filterNot { skill ->
    skill.id in builtInIds || skill.implementationKotlin.startsWith("plugin:")
}
