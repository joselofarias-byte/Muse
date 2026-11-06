package io.zer0.muse.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ReasoningLevel
import io.zer0.common.Logger
import io.zer0.common.Result
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.pin.PinnedMemoryStore
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantCardExporter
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.assistant.AvatarStorage
import io.zer0.muse.data.assistant.CharacterCardExporter
import io.zer0.muse.data.lorebook.LorebookRepository
import io.zer0.muse.data.promptinjection.PromptInjectionRepository
import io.zer0.muse.data.quickmsg.QuickMessageRepository
import io.zer0.muse.data.sharing.CharacterSharer
import io.zer0.muse.data.sharing.ShareableAssistant
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseDropdown
import io.zer0.muse.ui.common.form.MuseSlider
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.museAnimateItem
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.CardGroupScope
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.settings.SettingsSubPageScaffold
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File

// ──────────────────────────────────────────────────────────────────────────────
// Ayudante de guardado instantáneo: no es necesario ViewModel,Leer directamente AssistantRepository
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Designación de colección [assistantId] correspondiente [AssistantEntity],Siga automáticamente los cambios del almacén.
 * Devolver nulo significa que aún no se ha cargado.
 */
@Composable
fun rememberAssistant(assistantId: String): AssistantEntity? {
    val repo: AssistantRepository = koinInject()
    return produceState<AssistantEntity?>(initialValue = null, assistantId) {
        repo.observeAll.map { list -> list.find { it.id == assistantId } }.collect { value = it }
    }.value
}

/**
 * Devuelve un cierre de actualización:Lea la última entidad de DB cuando se le llame,Solicitud [transform] Vuelve a escribir más tarde.
 * Se utiliza para guardar instantáneamente subpáginas.(Cada vez que un campo cambia upsert).
 */
@Composable
fun rememberAssistantUpdater(assistantId: String): ((AssistantEntity) -> AssistantEntity) -> Unit {
    val repo: AssistantRepository = koinInject()
    val scope = rememberCoroutineScope()
    // Reparación de auditoría (3.4): Mutex lectura en serie-cambiar-Escribir. Actualizaciones en rápida sucesión(Como arrastre del control deslizante de temperatura)tiempo
    // Cada rutina se ejecuta en orden. getById → upsert,Evite que se completen varias rutinas fuera de secuencia y que los resultados antiguos sobrescriban los nuevos.
    val mutex = remember { Mutex() }
    return remember(assistantId) {
        { transform ->
            scope.launch {
                mutex.withLock {
                    val current = repo.getById(assistantId) ?: return@withLock
                    repo.upsert(transform(current))
                }
            }
        }
    }
}

/**
 * Cuadro de texto antivibración:Entrada almacenada en caché local,Deje de ingresar durante 400 ms antes de volver a escribir DB,Evite la inserción cada vez que se presiona una tecla, lo que provoca escrituras frecuentes en la base de datos.
 *
 * - [value] es el valor persistente actual(de DB);Los cambios externos se sincronizan con el borrador local.
 * - [onPersist] Se activa después de que finaliza el sistema antivibración.,El valor pasado se pasa. [transform] El resultado convertido.
 * - [transform] Se utiliza para la conversión antes de la persistencia.(Como por ejemplo `take(2)` longitud limitada,`serializeTagsForEdit` serialización).
 */
@Composable
@Suppress("FunctionNaming")
internal fun DebouncedTextField(
    value: String,
    onPersist: (String) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    transform: (String) -> String = { it },
) {
    var draft by remember { mutableStateOf(value) }
    // Cambios de valor externo(Como reescritura de base de datos)Sincronizar con borrador
    LaunchedEffect(value) {
        if (draft != value) draft = value
    }
    // Retrasar la persistencia de 400 ms después de los cambios en el borrador(Restablecer el tiempo en cada entrada,Realizar anti-vibración)
    LaunchedEffect(draft) {
        if (draft != value) {
            delay(400)
            onPersist(transform(draft))
        }
    }
    // MEM-02: Salir/Fuerza antes de girar la pantalla flush — Al salir de la página dentro de la ventana anti-vibración,La última edición no se pierde.
    // Utilice RememberUpdatedState para leer el último borrador.,onDispose Si aún quedan cambios no escritos, se escribirán inmediatamente.
    val latestDraft by rememberUpdatedState(draft)
    val latestValue by rememberUpdatedState(value)
    DisposableEffect(Unit) {
        onDispose {
            if (latestDraft != latestValue) {
                onPersist(transform(latestDraft))
            }
        }
    }
    MuseTextField(
        value = draft,
        onValueChange = { v -> draft = v },
        label = label,
        singleLine = singleLine,
        modifier = modifier,
    )
}

// ──────────────────────────────────────────────────────────────────────────────
// Página de agregación de detalles: Encabezado + 5 entradas de configuración
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Página de agregación de detalles del asistente — Avatar + nombre + sistemaResumen de avisos + 5 entradas de subpágina.
 *
 * P1-4 Adaptación de tableta:[onBack] Cambiar a anulable(Expanded No es necesario un botón de retorno en la columna derecha en el modo de doble columna).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantDetailPage(
    assistantId: String,
    onBack: (() -> Unit)? = null,
    onOpenBasic: () -> Unit,
    onOpenPrompt: () -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenAdvanced: () -> Unit,
) {
    val assistant = rememberAssistant(assistantId)
    val update = rememberAssistantUpdater(assistantId)
    val titleDefault = stringResource(R.string.assistant_detail_title_default)
    val title = assistant?.name?.ifBlank { titleDefault } ?: titleDefault

    // v1.0.28: La página de detalles del asistente muestra directamente la entrada de selección de modelo.,Evitar que los usuarios no sepan que la página básica tiene selección de modelo.
    val settings: SettingsRepository = koinInject()
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val allModels = remember(providers) { providers.flatMap { it.models } }
    val globalSelectedModelId by settings.selectedModelIdFlow.collectAsStateWithLifecycle(initialValue = null)
    var showModelPicker by remember { mutableStateOf(false) }

    // ── SillyTavern Exportación de tarjetas de personajes (PNG/JSON) ──
    // SillyTavern La tarjeta es un formato común en la industria.: PNG tEXt chunk (key="chara") guardar base64 JSON, o puro JSON
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // SAF Saque la entidad que se exportará durante la devolución de llamada (Evite la reorganización y desalineación causada por la lectura del estado del asistente en el lanzamiento lambda)
    var exportPngTarget by remember { mutableStateOf<AssistantEntity?>(null) }
    var exportJsonTarget by remember { mutableStateOf<AssistantEntity?>(null) }

    val toastExported = stringResource(R.string.assistant_toast_sillytavern_exported)
    val toastExportFailed = stringResource(R.string.assistant_toast_sillytavern_export_failed)

    // Cargar avatar desde avatarImageUrl Bitmap; Fallo o no se devolvió ningún avatar null (El exportador utiliza un PNG transparente de 1x1 como vector.)
    fun loadAvatarBitmap(a: AssistantEntity): Bitmap? {
        if (a.avatarImageUrl.isBlank()) return null
        return runCatching {
            val file = File(a.avatarImageUrl)
            if (!file.exists()) return null
            BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull()
    }

    val exportPngLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        val target = exportPngTarget
        exportPngTarget = null
        if (uri != null && target != null) {
            scope.launch {
                val avatar = loadAvatarBitmap(target)
                CharacterCardExporter.exportToPng(context, target, avatar, uri)
                    .onSuccess { MuseToast.show(toastExported) }
                    .onFailure { MuseToast.show(toastExportFailed.format(it.message ?: ""), 3500) }
            }
        }
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val target = exportJsonTarget
        exportJsonTarget = null
        if (uri != null && target != null) {
            scope.launch {
                CharacterCardExporter.exportToJson(context, target, uri)
                    .onSuccess { MuseToast.show(toastExported) }
                    .onFailure { MuseToast.show(toastExportFailed.format(it.message ?: ""), 3500) }
            }
        }
    }

    SettingsSubPageScaffold(title = title, onBack = onBack) {
        item {
            // cabeza: Gran avatar + nombre + sistemaResumen rápido
            // v2.x: Finalización del efecto de movimiento — entrada de cabeza(ficha animateItem)
            Column(
                modifier = museAnimateItem()
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AssistantAvatar(assistant = assistant ?: placeholderAssistant(), avatarSize = 80.dp)
                Text(
                    text = assistant?.name?.ifBlank { stringResource(R.string.assistant_detail_unnamed) }
                        ?: stringResource(R.string.assistant_detail_loading),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (assistant != null) {
                    val desc = assistant.summary.ifBlank {
                        assistant.systemPrompt.take(100).let {
                            it + if (assistant.systemPrompt.length > 100) "..." else ""
                        }
                    }
                    if (desc.isNotBlank()) {
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // v1.0.28: Portal de selección de modelos — Muestre directamente el modelo utilizado actualmente en la página de detalles,Haga clic para cambiar
        // v2.x: Finalización del efecto de movimiento — Entrada a la baraja de cartas(ficha animateItem)
        item {
            CardGroup(modifier = museAnimateItem()) {
                item(
                    onClick = { showModelPicker = true },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.sparkle,
                            contentDescription = null,
                            tint = if (assistant?.modelId != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    headlineContent = {
                        val a = assistant
                        val globalModelName = allModels
                            .firstOrNull { it.id == globalSelectedModelId }?.name
                            ?.substringAfterLast('/')
                            ?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.assistant_detail_global_default)
                        val currentModelName = a?.modelId?.let { mid ->
                            allModels.firstOrNull { it.id == mid }?.name ?: mid
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.assistant_detail_exclusive_model),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (currentModelName != null) {
                                Text(
                                    text = currentModelName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.assistant_detail_using_global_model, globalModelName),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    trailingContent = { ChevronRight() },
                )
            }
        }
        item {
            CardGroup(
                modifier = museAnimateItem().padding(horizontal = 0.dp), // inset-guard: allow(La lista exterior ya proporciona márgenes de pantalla)
            ) {
                item(
                    onClick = onOpenBasic,
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(MuseIcons.robot) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_basic)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_basic_desc)) },
                    trailingContent = { ChevronRight() },
                )
                item(
                    onClick = onOpenPrompt,
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(MuseIcons.fileText) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_prompt)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_prompt_desc)) },
                    trailingContent = { ChevronRight() },
                )
                item(
                    onClick = onOpenExtensions,
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(MuseIcons.puzzle) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_extensions)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_extensions_desc)) },
                    trailingContent = { ChevronRight() },
                )
                item(
                    onClick = onOpenMemory,
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(MuseIcons.brain) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_memory)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_memory_desc)) },
                    trailingContent = { ChevronRight() },
                )
                item(
                    onClick = onOpenAdvanced,
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(MuseIcons.flask) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_advanced)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_advanced_desc)) },
                    trailingContent = { ChevronRight() },
                )
            }
        }
        // SillyTavern Compartir tarjeta de personaje (PNG/JSON Exportar)
        // Exportar el asistente actual a un formato común en la industria., Se puede importar a otras aplicaciones que admitan tarjetas de personajes de SillyTavern.
        item {
            CardGroup(
                modifier = museAnimateItem(),
                title = { Text(stringResource(R.string.assistant_detail_share_section)) },
            ) {
                item(
                    onClick = {
                        val a = assistant ?: return@item
                        exportPngTarget = a
                        val safeName = a.name.ifBlank { "assistant" }
                            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        exportPngLauncher.launch("$safeName.png")
                    },
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(MuseIcons.image) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_export_png)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_export_png_desc)) },
                    trailingContent = { ChevronRight() },
                )
                item(
                    onClick = {
                        val a = assistant ?: return@item
                        exportJsonTarget = a
                        val safeName = a.name.ifBlank { "assistant" }
                            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        exportJsonLauncher.launch("$safeName.json")
                    },
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(MuseIcons.fileText) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_export_json)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_export_json_desc)) },
                    trailingContent = { ChevronRight() },
                )
            }
        }
        // Muse Compartir tarjeta de personaje(Compartir sistema Intent)
        item {
            CardGroup(
                modifier = museAnimateItem(),
                title = { Text(stringResource(R.string.assistant_detail_share_section)) },
            ) {
                // v2.x: Enviar paquete de personajes completo(Contiene avatar,La otra parte puede importar directamente) — Entrada más utilizada,primer lugar
                item(
                    onClick = {
                        val a = assistant ?: return@item
                        scope.launch {
                            val ok = AssistantCardExporter.share(context, a)
                            if (!ok) {
                                MuseToast.show(
                                    context.getString(R.string.assistant_detail_share_muse_failed, "pack"),
                                    3000,
                                )
                            }
                        }
                    },
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(MuseIcons.share) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_share_pack)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_share_pack_desc)) },
                    trailingContent = { ChevronRight() },
                )
                item(
                    onClick = {
                        val a = assistant ?: return@item
                        val shareable = ShareableAssistant(
                            name = a.name.ifBlank { "Assistant" },
                            description = a.systemPrompt.take(200),
                            systemPrompt = a.systemPrompt,
                            temperature = a.temperature ?: 0.8f,
                            topP = a.topP ?: 0.95f,
                            maxTokens = a.maxTokens ?: 2048,
                            emoji = a.avatarEmoji,
                            avatarUrl = a.avatarImageUrl,
                        )
                        val uri = CharacterSharer.generateCardPng(context, shareable)
                        if (uri != null) {
                            CharacterSharer.shareCard(context, uri)
                        } else {
                            MuseToast.show(
                                context.getString(
                                    R.string.assistant_detail_share_muse_failed,
                                    "card generation",
                                ),
                                3000,
                            )
                        }
                    },
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(MuseIcons.share) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_share_muse_card)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_share_muse_card_desc)) },
                    trailingContent = { ChevronRight() },
                )
                item(
                    onClick = {
                        val a = assistant ?: return@item
                        val shareable = ShareableAssistant(
                            name = a.name.ifBlank { "Assistant" },
                            description = a.systemPrompt.take(200),
                            systemPrompt = a.systemPrompt,
                            temperature = a.temperature ?: 0.8f,
                            topP = a.topP ?: 0.95f,
                            maxTokens = a.maxTokens ?: 2048,
                            emoji = a.avatarEmoji,
                        )
                        val json = CharacterSharer.exportToJson(shareable)
                        val safeName = a.name.ifBlank { "assistant" }
                            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        CharacterSharer.shareJson(context, json, "${safeName}_muse.json")
                    },
                    leadingContent = { io.zer0.muse.ui.common.form.MuseSettingsIcon(MuseIcons.fileText) },
                    headlineContent = { Text(stringResource(R.string.assistant_detail_share_muse_json)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_share_muse_json_desc)) },
                    trailingContent = { ChevronRight() },
                )
            }
        }
    }

    // v1.0.28: Diálogo de selección de modelo(Comparte el mismo patrón de interfaz de usuario que AssistantBasicPage)
    if (showModelPicker) {
        AssistantModelPickerDialog(
            assistant = assistant,
            providers = providers,
            allModels = allModels,
            globalSelectedModelId = globalSelectedModelId,
            update = update,
            onDismiss = { showModelPicker = false },
        )
    }
}

/** entidad auxiliar de marcador de posición(Se utiliza para renderizar el avatar al cargar.). */
private fun placeholderAssistant() = AssistantEntity(id = "", name = "")

/**
 * CONS-04: Diálogo de selección de modelo(Página de detalles del asistente/página básica compartida,La convergencia se repite UI).
 *
 * parte superior sobresaliente"Usar valor predeterminado global"tarjeta,Los modelos se enumeran a continuación, agrupados por Proveedor.
 *
 * @param assistant Entidad auxiliar actual(null Indica cargando)
 * @param providers Lista de proveedores disponibles
 * @param allModels Lista plana de modelos para todos los Proveedores.(Se utiliza para mostrar el nombre del modelo predeterminado global.)
 * @param globalSelectedModelId Modelo global seleccionado actualmente id
 * @param update Vuelve a escribir el cierre de actualización del ayudante.(Consistente con el tipo de retorno de RememberAssistantUpdater)
 * @param onUseGlobalDefault Haga clic"Usar valor predeterminado global"lógica de actualización(Cada persona que llama conserva el comportamiento original.)
 * @param onDismiss Cerrar devolución de llamada de diálogo
 */
@Composable
private fun AssistantModelPickerDialog(
    assistant: AssistantEntity?,
    providers: List<ProviderConfig>,
    allModels: List<Model>,
    globalSelectedModelId: String?,
    update: ((AssistantEntity) -> AssistantEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    // P3-5: La semántica "Usar valor predeterminado global" converge en componentes — Los dos pulsadores se pasaban previamente por separado.
    // copy(modelId=null) con copy(modelId=null, providerId=null),causado por la deriva
    // Aún queda un lugar providerId;claro unificado modelId+providerId,Sigue completamente el valor predeterminado global.
    val useGlobalDefault: () -> Unit = { update { it.copy(modelId = null, providerId = null) } }
    val globalDefaultSelected = stringResource(R.string.assistant_detail_global_default_selected)
    val globalDefaultCard = stringResource(R.string.assistant_detail_use_global_default_card)
    val globalModelName = allModels
        .firstOrNull { it.id == globalSelectedModelId }?.name
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.assistant_detail_global_default)
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.assistant_detail_select_model),
        confirmText = stringResource(R.string.assistant_detail_close),
        onConfirm = onDismiss,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val currentModelId = assistant?.modelId
                Surface(
                    color = if (currentModelId == null) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = MuseShapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            useGlobalDefault()
                            onDismiss()
                        }
                        .padding(vertical = 4.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Text(
                            text = if (currentModelId == null) globalDefaultSelected else globalDefaultCard,
                            fontWeight = FontWeight.Medium,
                            color = if (currentModelId == null) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            text = globalModelName,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (currentModelId == null) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                providers.forEach { provider ->
                    if (provider.models.isNotEmpty()) {
                        Text(
                            text = provider.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                        provider.models.forEach { model ->
                            MuseCapsuleButton(
                                text = if (model.id == currentModelId) {
                                    stringResource(R.string.assistant_detail_model_selected, model.name)
                                } else {
                                    model.name
                                },
                                onClick = {
                                    update { it.copy(modelId = model.id, providerId = provider.id) }
                                    onDismiss()
                                },
                                variant = IosCapsuleButtonVariant.Text,
                            )
                        }
                    }
                }
            }
        },
    )
}

// ──────────────────────────────────────────────────────────────────────────────
// Subpágina 1: Conceptos básicos(Información básica + modelo + avatar)
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Subpágina básica — Nombre/Avatar Emoji/Imagen de Avatar/Modelo/Parámetros de muestreo/Nivel de inferencia/Salida de transmisión.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantBasicPage(assistantId: String, onBack: () -> Unit) {
    val assistant = rememberAssistant(assistantId)
    val update = rememberAssistantUpdater(assistantId)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // v1.52: Inyecte SettingsRepository para obtener la lista de proveedores,para selector de modelo
    val settings: SettingsRepository = koinInject()
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // Almacenar en caché una lista plana de modelos para todos los proveedores,Evite que flatMap cree nuevos cada vez que se reorganiza un elemento de la lista List
    val allModels = remember(providers) { providers.flatMap { it.models } }
    // Phase 6: Modelo global seleccionado actualmente id,Se utiliza para mostrar el nombre del modelo predeterminado global.
    val globalSelectedModelId by settings.selectedModelIdFlow.collectAsStateWithLifecycle(initialValue = null)
    // Diálogo de selección de modelo
    var showModelPicker by remember { mutableStateOf(false) }

    // Selector de imagen de avatar(Seleccionar imágenes del álbum de fotos. → Copiar al almacenamiento interno → upsert)
    val pickAvatarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val path = AvatarStorage.copyToInternal(context, uri, assistantId)
                if (path != null) {
                    update { it.copy(avatarImageUrl = path) }
                } else {
                    // v1.69: Notificar al usuario cuando falla el guardado del avatar,Originalmente solo silencioso y no actualizado.
                    MuseToast.show(context.getString(R.string.assistant_detail_avatar_save_failed), 3000)
                }
            }
        }
    }

    SettingsSubPageScaffold(title = stringResource(R.string.assistant_detail_basic), onBack = onBack) {
        val a = assistant
        if (a == null) {
            item { Text(stringResource(R.string.assistant_detail_loading), color = MaterialTheme.colorScheme.outline) }
            return@SettingsSubPageScaffold
        }
        // Baraja de cartas 1: información básica
        // v2.x: Finalización del efecto de movimiento — Entrada a la baraja de cartas(ficha animateItem)
        item {
            CardGroup(modifier = museAnimateItem()) {
                // Avatar + botón de selección de imagen
                item(
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AssistantAvatar(assistant = a, avatarSize = 56.dp)
                            Spacer(Modifier.size(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.assistant_detail_avatar), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = if (a.avatarImageUrl.isNotBlank()) {
                                        stringResource(R.string.assistant_detail_avatar_image_set)
                                    } else if (a.avatarEmoji.isNotBlank()) {
                                        stringResource(R.string.assistant_detail_avatar_emoji)
                                    } else {
                                        stringResource(R.string.assistant_detail_avatar_initial)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    trailingContent = {
                        Row {
                            // v1.69: launch puede ser arrojado ActivityNotFoundException(Sin álbum App),La ejecución original Catching traga silenciosamente excepciones
                            MuseCapsuleButton(
                                text = stringResource(R.string.assistant_detail_pick_image),
                                onClick = {
                                    runCatching { pickAvatarLauncher.launch("image/*") }
                                        .onFailure { MuseToast.show(context.getString(R.string.assistant_detail_no_image_app), 3000) }
                                },
                                variant = IosCapsuleButtonVariant.Text,
                                fillWidth = false,
                            )
                            if (a.avatarImageUrl.isNotBlank()) {
                                MuseCapsuleButton(
                                    text = stringResource(R.string.assistant_detail_clear),
                                    onClick = { update { it.copy(avatarImageUrl = "") } },
                                    variant = IosCapsuleButtonVariant.Text,
                                    fillWidth = false,
                                )
                            }
                        }
                    },
                )
            }
        }
        // Baraja de cartas 2: nombre y nombre Emoji
        // v2.x: Finalización del efecto de movimiento — Entrada a la baraja de cartas(ficha animateItem)
        item {
            CardGroup(modifier = museAnimateItem()) {
                item(
                    headlineContent = {
                        DebouncedTextField(
                            value = a.name,
                            onPersist = { v -> update { it.copy(name = v) } },
                            label = { Text(stringResource(R.string.assistant_detail_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                item(
                    headlineContent = {
                        DebouncedTextField(
                            value = a.avatarEmoji,
                            onPersist = { v -> update { it.copy(avatarEmoji = v) } },
                            label = { Text(stringResource(R.string.assistant_detail_avatar_emoji_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            transform = { it.take(2) },
                        )
                    },
                )
            }
        }
        // Baraja de cartas 3: modelo y parámetros de muestreo
        // v2.x: Finalización del efecto de movimiento — Entrada a la baraja de cartas(ficha animateItem)
        item {
            CardGroup(modifier = museAnimateItem()) {
                // Phase 6: Mejoras en la experiencia de usuario del selector de modelos — Área de clic más grande, ícono frontal, muestra el nombre del modelo global
                item(
                    onClick = { showModelPicker = true },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.sparkle,
                            contentDescription = null,
                            tint = if (a.modelId != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    headlineContent = {
                        val globalModelName = allModels
                            .firstOrNull { it.id == globalSelectedModelId }?.name
                            ?.substringAfterLast('/')
                            ?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.assistant_detail_global_default)
                        val currentModelName = a.modelId?.let { mid ->
                            allModels.firstOrNull { it.id == mid }?.name ?: mid
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Text(stringResource(R.string.assistant_detail_exclusive_model), style = MaterialTheme.typography.bodyMedium)
                            if (currentModelName != null) {
                                Text(
                                    text = currentModelName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.assistant_detail_using_global_model, globalModelName),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    trailingContent = { ChevronRight() },
                )
                // Phase 6: Consejos para nuevos asistentes — Mostrar mensajes de guía cuando no se selecciona ningún modelo exclusivo
                if (a.modelId == null) {
                    item(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.assistant_detail_model_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        },
                    )
                }
                item(
                    headlineContent = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(
                                    R.string.assistant_detail_temperature,
                                    a.temperature?.let { "%.2f".format(it) }
                                        ?: stringResource(R.string.assistant_detail_default),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            MuseSlider(
                                value = a.temperature ?: 1.0f,
                                onValueChange = { v -> update { it.copy(temperature = v) } },
                                valueRange = 0f..2f,
                                steps = 39,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MuseCapsuleButton(
                                    text = stringResource(R.string.assistant_detail_use_default),
                                    onClick = { update { it.copy(temperature = null) } },
                                    variant = IosCapsuleButtonVariant.Text,
                                    fillWidth = false,
                                )
                                MuseCapsuleButton(
                                    text = "0.7",
                                    onClick = { update { it.copy(temperature = 0.7f) } },
                                    variant = IosCapsuleButtonVariant.Text,
                                    fillWidth = false,
                                )
                                MuseCapsuleButton(
                                    text = "1.0",
                                    onClick = { update { it.copy(temperature = 1.0f) } },
                                    variant = IosCapsuleButtonVariant.Text,
                                    fillWidth = false,
                                )
                            }
                        }
                    },
                )
                item(
                    headlineContent = {
                        // v1.0.27: Utilice DebouncedTextField en su lugar para evitar que la composición de IME se vea interrumpida por la actualización de escritura asincrónica
                        DebouncedTextField(
                            value = a.topP?.toString() ?: "",
                            onPersist = { v ->
                                val parsed = v.filter { it.isDigit() || it == '.' }.toFloatOrNull()
                                update { it.copy(topP = parsed) }
                            },
                            label = { Text(stringResource(R.string.assistant_detail_top_p_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                item(
                    headlineContent = {
                        // v1.0.27: Utilice DebouncedTextField en su lugar para evitar que la composición de IME se vea interrumpida por la actualización de escritura asincrónica
                        DebouncedTextField(
                            value = a.maxTokens?.toString() ?: "",
                            onPersist = { v ->
                                val parsed = v.filter(Char::isDigit).toIntOrNull()
                                update { it.copy(maxTokens = parsed) }
                            },
                            label = { Text(stringResource(R.string.assistant_detail_max_tokens_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                item(
                    headlineContent = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.assistant_detail_context_message_count, a.contextMessageSize),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            MuseSlider(
                                value = a.contextMessageSize.toFloat(),
                                onValueChange = { v ->
                                    update { it.copy(contextMessageSize = v.toInt().coerceAtLeast(1)) }
                                },
                                valueRange = 0f..50f,
                                valueFormatter = { "${it.toInt()}" },
                            )
                        }
                    },
                )
                item(
                    headlineContent = {
                        // Menú desplegable de nivel de razonamiento
                        // v2.0.1: Localización del nombre para mostrar (value Mantenga el nombre de la enumeración almacenado como clave).
                        val levelOptions = mutableListOf<Pair<String, String>>()
                        for (level in ReasoningLevel.entries) {
                            levelOptions += level.name to stringResource(reasoningLevelLabelRes(level))
                        }
                        MuseDropdown(
                            value = a.reasoningLevel,
                            onValueChange = { selected -> update { it.copy(reasoningLevel = selected) } },
                            label = stringResource(R.string.assistant_detail_reasoning_level),
                            options = levelOptions,
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_detail_stream_output)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_stream_output_desc)) },
                    trailingContent = {
                        MuseSwitch(
                            checked = a.streamOutput,
                            onCheckedChange = { v -> update { it.copy(streamOutput = v) } },
                        )
                    },
                )
            }
        }
        // Baraja de cartas 4: Introducción + nombre para mostrar + chat grupal(v1.0.19 Assistant Finalización del campo)
        // v2.x: Finalización del efecto de movimiento — Entrada a la baraja de cartas(ficha animateItem)
        item {
            CardGroup(modifier = museAnimateItem()) {
                item(
                    headlineContent = {
                        DebouncedTextField(
                            value = a.summary,
                            onPersist = { v -> update { it.copy(summary = v) } },
                            label = { Text(stringResource(R.string.assistant_detail_summary)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_summary_hint)) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_detail_use_assistant_name)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_use_assistant_name_desc)) },
                    trailingContent = {
                        MuseSwitch(
                            checked = a.useAssistantName,
                            onCheckedChange = { v -> update { it.copy(useAssistantName = v) } },
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_detail_allow_group_chat)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_allow_group_chat_desc)) },
                    trailingContent = {
                        MuseSwitch(
                            checked = a.allowGroupChat,
                            onCheckedChange = { v -> update { it.copy(allowGroupChat = v) } },
                        )
                    },
                )
            }
        }

        // v2.6.2(comentarios de los usuarios): Fondo de chat a nivel de asistente —— Prioridad: nivel de sesión > nivel asistente > Incumplimiento global.
        item {
            val assistantBackgrounds by settings.assistantChatBackgroundFlow.collectAsStateWithLifecycle(
                initialValue = emptyMap(),
            )
            val assistantBg = assistantBackgrounds[assistantId]
            val bgScope = rememberCoroutineScope()
            val bgContext = LocalContext.current
            val bgLauncher =
                rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.PickVisualMedia(),
                ) { uri ->
                    uri?.let { picked ->
                        bgScope.launch {
                            val savedPath = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                io.zer0.muse.ui.settings.ChatBackgroundStore.saveFromUri(
                                    bgContext,
                                    picked,
                                    io.zer0.muse.ui.settings.ChatBackgroundStore.assistantSlot(assistantId),
                                )
                            }
                            if (savedPath != null) {
                                settings.saveAssistantChatBackground(assistantId, savedPath)
                                MuseToast.show(bgContext.getString(R.string.settings_chat_background_saved))
                            } else {
                                MuseToast.show(bgContext.getString(R.string.chat_background_save_failed))
                            }
                        }
                    }
                }
            CardGroup(modifier = museAnimateItem()) {
                item(
                    headlineContent = {
                        Text(stringResource(R.string.settings_chat_background_assistant_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.settings_chat_background_assistant_subtitle))
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (assistantBg != null) {
                                Text(
                                    text = stringResource(R.string.action_clear),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier =
                                    Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable {
                                            bgScope.launch {
                                                settings.saveAssistantChatBackground(assistantId, null)
                                                io.zer0.muse.ui.settings.ChatBackgroundStore.deleteSlot(
                                                    bgContext,
                                                    io.zer0.muse.ui.settings.ChatBackgroundStore.assistantSlot(assistantId),
                                                )
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.settings_chat_background_choose),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier =
                                    Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable {
                                            bgLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                            )
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    },
                    modifier =
                    Modifier.clickable {
                        bgLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
            }
        }
    }

    // Phase 6: Mejoras en la experiencia de usuario del cuadro de diálogo de selección de modelo — parte superior sobresaliente“Usar valor predeterminado global”tarjeta,Mostrar nombre de modelo global
    if (showModelPicker) {
        AssistantModelPickerDialog(
            assistant = assistant,
            providers = providers,
            allModels = allModels,
            globalSelectedModelId = globalSelectedModelId,
            update = update,
            onDismiss = { showModelPicker = false },
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Subpágina 2: Palabras clave
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Subpágina de palabra rápida — systemPrompt / messageTemplate / presetMessagesJson.
 */
@Composable
fun AssistantPromptPage(assistantId: String, onBack: () -> Unit) {
    val assistant = rememberAssistant(assistantId)
    val update = rememberAssistantUpdater(assistantId)

    SettingsSubPageScaffold(title = stringResource(R.string.assistant_detail_prompt), onBack = onBack) {
        val a = assistant
        if (a == null) {
            item { Text(stringResource(R.string.assistant_detail_loading), color = MaterialTheme.colorScheme.outline) }
            return@SettingsSubPageScaffold
        }
        item {
            CardGroup(modifier = museAnimateItem()) {
                item(
                    headlineContent = {
                        DebouncedTextField(
                            value = a.personaDefinition,
                            onPersist = { v -> update { it.copy(personaDefinition = v) } },
                            label = { Text(stringResource(R.string.assistant_detail_persona_definition_label)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        )
                    },
                    supportingContent = {
                        Text(stringResource(R.string.assistant_detail_persona_definition_hint))
                    },
                )
                item(
                    headlineContent = {
                        DebouncedTextField(
                            value = a.thinkingChain,
                            onPersist = { v -> update { it.copy(thinkingChain = v) } },
                            label = { Text(stringResource(R.string.assistant_detail_thinking_chain_label)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        )
                    },
                    supportingContent = {
                        Text(stringResource(R.string.assistant_detail_thinking_chain_hint))
                    },
                )
                item(
                    headlineContent = {
                        DebouncedTextField(
                            value = a.systemPrompt,
                            onPersist = { v -> update { it.copy(systemPrompt = v) } },
                            label = { Text(stringResource(R.string.assistant_detail_system_prompt_label)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                        )
                    },
                )
                item(
                    headlineContent = {
                        DebouncedTextField(
                            value = a.messageTemplate,
                            onPersist = { v -> update { it.copy(messageTemplate = v) } },
                            label = { Text(stringResource(R.string.assistant_detail_message_template_label)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                        )
                    },
                )
                item(
                    headlineContent = {
                        DebouncedTextField(
                            value = a.presetMessagesJson,
                            onPersist = { v -> update { it.copy(presetMessagesJson = v) } },
                            label = { Text(stringResource(R.string.assistant_detail_preset_messages_label)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                        )
                    },
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Subpágina 3: Extensiones(Recursos relacionados)
// ──────────────────────────────────────────────────────────────────────────────

private enum class ExtensionType {
    QUICK_MESSAGE,
    LOREBOOK,
    MODE_INJECTION,
    SKILL,
    MCP_SERVER,
    TOOL,
    KNOWLEDGE_BASE,
}

/** v2.4.6: Entradas de alcance de la base de conocimientos a las que el asistente puede vincular(KB Toda la biblioteca o carpetas dentro de ella.). */
private data class KbScopeItem(
    val id: String,
    val label: String,
    val kbId: String,
    val path: String,
)

@Composable
private fun ExtensionType.titleText(): String = stringResource(
    when (this) {
        ExtensionType.QUICK_MESSAGE -> R.string.assistant_detail_ext_quick_message
        ExtensionType.LOREBOOK -> R.string.assistant_detail_ext_lorebook
        ExtensionType.MODE_INJECTION -> R.string.assistant_detail_ext_mode_injection
        ExtensionType.SKILL -> R.string.assistant_detail_ext_skill
        ExtensionType.MCP_SERVER -> R.string.assistant_detail_ext_mcp_server
        ExtensionType.TOOL -> R.string.assistant_detail_ext_tool
        ExtensionType.KNOWLEDGE_BASE -> R.string.assistant_detail_ext_knowledge_base
    },
)

@Composable
private fun <T> rememberFlowList(flow: Flow<List<T>>): List<T> {
    return produceState<List<T>>(initialValue = emptyList(), flow) {
        flow.collect { value = it }
    }.value
}

/**
 * Ventana emergente de chips de selección múltiple — Utilice FlowRow para mostrar todos los candidatos,Haga clic para cambiar instantáneamente.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> MultiSelectChipsDialog(
    title: String,
    items: List<T>,
    selectedIds: Set<String>,
    itemId: (T) -> String,
    itemLabel: (T) -> String,
    onToggle: (String) -> Unit,
    onSelectionChange: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            if (items.isEmpty()) {
                Text(
                    text = stringResource(R.string.assistant_detail_no_options),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                )
            } else {
                // v1.95: Seleccionar todo/borrar filas de acción
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.assistant_detail_select_all),
                        onClick = {
                            // Seleccionar todo:Consulta todas las opciones
                            onSelectionChange(items.map { itemId(it) })
                        },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                    )
                    Spacer(Modifier.size(8.dp))
                    MuseCapsuleButton(
                        text = stringResource(R.string.assistant_detail_clear_all),
                        onClick = {
                            // Borrar
                            onSelectionChange(emptyList())
                        },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                    )
                }
                // v1.0.92: Se cambió de chips LazyRow horizontales a lista vertical — Más de un candidato,
                // El desplazamiento horizontal no es completo ni difícil(La lista de habilidades medidas por el usuario desborda la pantalla.).
                Column(modifier = Modifier.fillMaxWidth()) {
                    items.forEach { item ->
                        val id = itemId(item)
                        val selected = id in selectedIds
                        MuseListItem(
                            onClick = { onToggle(id) },
                            modifier = Modifier.semantics { this.selected = selected },
                            headlineContent = {
                                Text(
                                    text = itemLabel(item),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                Icon(
                                    imageVector = if (selected) MuseIcons.check else MuseIcons.circle,
                                    contentDescription = null,
                                    tint = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                )
                            },
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.assistant_detail_done),
        onConfirm = onDismiss,
        dismissText = null,
    )
}

/**
 * Expandir subpágina — Mostrar 6 tipos de recursos relacionados,Haga clic en una fila para abrir una ventana emergente de fichas de selección múltiple,Guarde instantáneamente después de cambiar.
 */
@Composable
fun AssistantExtensionsPage(assistantId: String, onBack: () -> Unit) {
    val assistant = rememberAssistant(assistantId)
    val repo: AssistantRepository = koinInject()
    val update = rememberAssistantUpdater(assistantId)

    val qmRepo: QuickMessageRepository = koinInject()
    val loreRepo: LorebookRepository = koinInject()
    val injRepo: PromptInjectionRepository = koinInject()
    val skillRepo: SkillRepository = koinInject()
    val settings: SettingsRepository = koinInject()
    val toolRegistry: ToolRegistry = koinInject()

    val quickMessages = rememberFlowList(qmRepo.observeAll())
    val lorebooks = rememberFlowList(loreRepo.observeAll())
    val promptInjections = rememberFlowList(injRepo.observeAll())
    val skills = rememberFlowList(skillRepo.observeAll)
    val mcpServers = rememberFlowList(settings.mcpServersFlow)
    val tools = remember(toolRegistry) { toolRegistry.listTools() }
    // v1.133: Lista de la base de conocimientos(KB Múltiples opciones)
    val kbDao: io.zer0.muse.data.knowledge.KnowledgeBaseDao = koinInject()
    val knowledgeBases = rememberFlowList(kbDao.observeAll())
    // v2.4.6: lista de carpetas — Hacer que el asistente se vincule a una carpeta(dominio de búsqueda independiente)
    val knowledgeFolderDao: io.zer0.muse.data.knowledge.KnowledgeFolderDao = koinInject()
    val knowledgeFolders = rememberFlowList(knowledgeFolderDao.observeAll())

    var activeType by remember { mutableStateOf<ExtensionType?>(null) }

    SettingsSubPageScaffold(title = stringResource(R.string.assistant_detail_extensions), onBack = onBack) {
        val a = assistant
        if (a == null) {
            item { Text(stringResource(R.string.assistant_detail_loading), color = MaterialTheme.colorScheme.outline) }
            return@SettingsSubPageScaffold
        }
        item {
            CardGroup(modifier = museAnimateItem()) {
                ExtensionRow(
                    type = ExtensionType.QUICK_MESSAGE,
                    count = repo.parseQuickMessageIds(a).size,
                    onClick = { activeType = ExtensionType.QUICK_MESSAGE },
                )
                ExtensionRow(
                    type = ExtensionType.LOREBOOK,
                    count = repo.parseLorebookIds(a).size,
                    onClick = { activeType = ExtensionType.LOREBOOK },
                )
                ExtensionRow(
                    type = ExtensionType.MODE_INJECTION,
                    count = repo.parseModeInjectionIds(a).size,
                    onClick = { activeType = ExtensionType.MODE_INJECTION },
                )
                ExtensionRow(
                    type = ExtensionType.SKILL,
                    count = repo.parseSkillIds(a).size,
                    onClick = { activeType = ExtensionType.SKILL },
                )
                ExtensionRow(
                    type = ExtensionType.MCP_SERVER,
                    count = repo.parseMcpServerIds(a).size,
                    onClick = { activeType = ExtensionType.MCP_SERVER },
                )
                ExtensionRow(
                    type = ExtensionType.TOOL,
                    count = repo.parseToolIds(a).size,
                    onClick = { activeType = ExtensionType.TOOL },
                )
                // v1.133/v2.4.6: base de conocimientos/encuadernación de carpetas(Número de entradas de alcance)
                ExtensionRow(
                    type = ExtensionType.KNOWLEDGE_BASE,
                    count = repo.parseKnowledgeFolderScopes(a).size
                        .takeIf { it > 0 }
                        ?: repo.parseKnowledgeBaseIds(a).size,
                    onClick = { activeType = ExtensionType.KNOWLEDGE_BASE },
                )
            }
        }
    }

    assistant?.let { a ->
        when (val type = activeType) {
            ExtensionType.QUICK_MESSAGE -> MultiSelectChipsDialog(
                title = type.titleText(),
                items = quickMessages,
                selectedIds = repo.parseQuickMessageIds(a).toSet(),
                itemId = { it.id },
                itemLabel = { it.name },
                onToggle = { id ->
                    val current = repo.parseQuickMessageIds(a)
                    val updated = if (id in current) current - id else current + id
                    update { it.copy(quickMessageIdsJson = repo.serializeStringList(updated)) }
                },
                onSelectionChange = { newIds ->
                    update { it.copy(quickMessageIdsJson = repo.serializeStringList(newIds)) }
                },
                onDismiss = { activeType = null },
            )
            ExtensionType.LOREBOOK -> MultiSelectChipsDialog(
                title = type.titleText(),
                items = lorebooks,
                selectedIds = repo.parseLorebookIds(a).toSet(),
                itemId = { it.id },
                itemLabel = { it.name },
                onToggle = { id ->
                    val current = repo.parseLorebookIds(a)
                    val updated = if (id in current) current - id else current + id
                    update { it.copy(lorebookIdsJson = repo.serializeStringList(updated)) }
                },
                onSelectionChange = { newIds ->
                    update { it.copy(lorebookIdsJson = repo.serializeStringList(newIds)) }
                },
                onDismiss = { activeType = null },
            )
            ExtensionType.MODE_INJECTION -> MultiSelectChipsDialog(
                title = type.titleText(),
                items = promptInjections,
                selectedIds = repo.parseModeInjectionIds(a).toSet(),
                itemId = { it.id },
                itemLabel = { it.displayName.takeIf { name -> name.isNotBlank() } ?: it.name },
                onToggle = { id ->
                    val current = repo.parseModeInjectionIds(a)
                    val updated = if (id in current) current - id else current + id
                    update { it.copy(modeInjectionIdsJson = repo.serializeStringList(updated)) }
                },
                onSelectionChange = { newIds ->
                    update { it.copy(modeInjectionIdsJson = repo.serializeStringList(newIds)) }
                },
                onDismiss = { activeType = null },
            )
            ExtensionType.SKILL -> MultiSelectChipsDialog(
                title = type.titleText(),
                items = skills,
                selectedIds = repo.parseSkillIds(a).toSet(),
                itemId = { it.id },
                itemLabel = { it.name },
                onToggle = { id ->
                    val current = repo.parseSkillIds(a)
                    val updated = if (id in current) current - id else current + id
                    update { it.copy(skillIdsJson = repo.serializeStringList(updated)) }
                },
                onSelectionChange = { newIds ->
                    update { it.copy(skillIdsJson = repo.serializeStringList(newIds)) }
                },
                onDismiss = { activeType = null },
            )
            ExtensionType.MCP_SERVER -> MultiSelectChipsDialog(
                title = type.titleText(),
                items = mcpServers,
                selectedIds = repo.parseMcpServerIds(a).toSet(),
                itemId = { it.id },
                itemLabel = { it.name },
                onToggle = { id ->
                    val current = repo.parseMcpServerIds(a)
                    val updated = if (id in current) current - id else current + id
                    update { it.copy(mcpServerIdsJson = repo.serializeStringList(updated)) }
                },
                onSelectionChange = { newIds ->
                    update { it.copy(mcpServerIdsJson = repo.serializeStringList(newIds)) }
                },
                onDismiss = { activeType = null },
            )
            ExtensionType.TOOL -> MultiSelectChipsDialog(
                title = type.titleText(),
                items = tools,
                selectedIds = repo.parseToolIds(a).toSet(),
                itemId = { it.name },
                itemLabel = { it.name },
                onToggle = { id ->
                    val current = repo.parseToolIds(a)
                    val updated = if (id in current) current - id else current + id
                    update { it.copy(toolIdsJson = repo.serializeStringList(updated)) }
                },
                onSelectionChange = { newIds ->
                    update { it.copy(toolIdsJson = repo.serializeStringList(newIds)) }
                },
                onDismiss = { activeType = null },
            )
            // v2.4.6: Enlace de la base de conocimientos — Selección múltiple "Base de conocimientos/Carpeta en la base de conocimientos",escribir
            // AssistantEntity.knowledgeFolderScopesJson(en forma de kbId::path).
            // una carpeta = un campo de búsqueda independiente,No se crucen. Cuando no se selecciona ninguna carpeta, degenerará en el enlace completo de la biblioteca.
            ExtensionType.KNOWLEDGE_BASE -> {
                // Expandir:Una fila por KB(Toda la biblioteca) + Una línea para cada carpeta debajo de ella(dominio independiente)
                val scopeItems: List<KbScopeItem> = run {
                    val foldersByKb = knowledgeFolders.groupBy { it.kbId }
                    knowledgeBases.flatMap { kb ->
                        val wholeKbLabel = stringResource(R.string.assistant_kb_scope_whole)
                        val folderRows = foldersByKb[kb.id].orEmpty().map { f ->
                            KbScopeItem(
                                id = "${kb.id}::${f.path}",
                                label = "${kb.name} / ${f.path}",
                                kbId = kb.id,
                                path = f.path,
                            )
                        }
                        listOf(
                            KbScopeItem(
                                id = "${kb.id}::",
                                label = "${kb.name}$wholeKbLabel",
                                kbId = kb.id,
                                path = "",
                            ),
                        ) + folderRows
                    }
                }
                val currentScopes = repo.parseKnowledgeFolderScopes(a)
                val currentIds = currentScopes.map { "${it.first}::${it.second}" }.toSet()
                MultiSelectChipsDialog(
                    title = type.titleText(),
                    items = scopeItems,
                    selectedIds = currentIds,
                    itemId = { it.id },
                    itemLabel = { it.label },
                    onToggle = { id ->
                        val updated = if (id in currentIds) currentIds - id else currentIds + id
                        val scopes = scopeItems.filter { it.id in updated }.map { it.kbId to it.path }
                        update {
                            it.copy(
                                knowledgeFolderScopesJson = repo.serializeStringList(repo.encodeKnowledgeFolderScopes(scopes)),
                                // Sincronizar lista de ID de KB(Compatible con rutas antiguas y lectura de datos antiguos.)
                                knowledgeBaseIdsJson = repo.serializeStringList(scopes.map { s -> s.first }.distinct()),
                            )
                        }
                    },
                    onSelectionChange = { newIds ->
                        val scopes = scopeItems.filter { it.id in newIds }.map { it.kbId to it.path }
                        update {
                            it.copy(
                                knowledgeFolderScopesJson = repo.serializeStringList(repo.encodeKnowledgeFolderScopes(scopes)),
                                knowledgeBaseIdsJson = repo.serializeStringList(scopes.map { s -> s.first }.distinct()),
                            )
                        }
                    },
                    onDismiss = { activeType = null },
                )
            }
            null -> {}
        }
    }
}

private fun CardGroupScope.ExtensionRow(type: ExtensionType, count: Int, onClick: () -> Unit) {
    item(
        onClick = onClick,
        headlineContent = { Text(type.titleText()) },
        supportingContent = { Text(stringResource(R.string.assistant_detail_selected_count, count)) },
        trailingContent = { ChevronRight() },
    )
}

// ──────────────────────────────────────────────────────────────────────────────
// Subpágina 4: Memoria
// ──────────────────────────────────────────────────────────────────────────────

/**
 * subpágina de memoria — memoryEnabled / useGlobalMemory / enableRecentChatsReference / enableTimeReminder.
 */
@Composable
fun AssistantMemoryPage(assistantId: String, onBack: () -> Unit) {
    val assistant = rememberAssistant(assistantId)
    val update = rememberAssistantUpdater(assistantId)
    // v2.6.2: Copia del mensaje de análisis previo —— El uso de context.getString directamente en la devolución de llamada lambda activará
    // lint LocalContextGetResourceValueCall(Se debe utilizar un recurso de cadena para obtener el valor durante la combinación).
    val missingValueText = androidx.compose.ui.res.stringResource(R.string.assistant_detail_memory_edit_missing)

    // P2-9: Fuente del lado de producción de lectura y escritura de memoria manual store — Asistente predeterminado(vacío/"default")→ hechos globales.db,
    // scope "main";sub-asistente → FactDbProvider biblioteca sucursal facts_<id>.db,scope = assistantId.
    // Consistente con el calibre de lectura de SystemPromptAssembler/ChatViewModel,Se solucionó el problema por el cual el grupo global de lectura y escritura no funcionaba para la IA.
    val factDbProvider: FactDbProvider = koinInject()
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    var showAddFactDialog by remember { mutableStateOf(false) }
    var editingFactId by remember { mutableStateOf<Long?>(null) }
    var factInput by remember { mutableStateOf("") }
    val expandedFactIds = remember { androidx.compose.runtime.mutableStateMapOf<Long, Boolean>() }
    val context = LocalContext.current
    var refreshKey by remember { mutableStateOf(0) }
    val memoryScope: String =
        if (assistant == null || assistant.id.isBlank() || assistant.id == "default") "main" else assistant.id
    val memoryStore: FactStore =
        // P3-4(informe de auditoría): eliminar !! —— memoryScope != "main" implica que el asistente no está vacío,
        // Pero ser explícito es más seguro que confiar en este razonamiento implícito (que puede romperse fácilmente mediante una refactorización posterior).
        if (memoryScope == "main") {
            koinInject<FactStore>()
        } else {
            factDbProvider.getFactStore(assistant?.id ?: memoryScope)
        }
    // Pinned memories use the same assistant scope as the prompt injector.
    val pinnedMemoryStore: PinnedMemoryStore = koinInject()
    val includeGlobalPinned = assistant?.useGlobalMemory == true || assistantId == "default"
    val pinnedMemories by produceState(
        initialValue = emptyList<PinnedMemoryStore.PinnedEntry>(),
        refreshKey,
        assistantId,
        includeGlobalPinned,
    ) {
        value =
            resultOf { pinnedMemoryStore.getForAssistant(assistantId, includeGlobal = includeGlobalPinned) }
                .onError { message, error -> Logger.w("AssistantMemoryPage", "No se pudo leer la memoria fija: $message", error) }
                .getOrNull()
                .orEmpty()
    }
    // spaceId Ponte al día Space,leer con producción(Filtrar por currentSpaceId)Mantenga la misma dimensión.
    val spaceId by produceState(initialValue = "default", refreshKey) {
        value = settings.currentSpaceIdFlow.first().ifBlank { "default" }
    }
    // Nota:Presione alcance+espacio aquí para leer los datos realmente utilizados por la IA en esta tienda.;Incremente la clave de actualización después de agregar o eliminar para activar la repetición.
    val facts by produceState<List<FactStore.Fact>>(initialValue = emptyList(), refreshKey, memoryScope, spaceId) {
        value =
            resultOf { memoryStore.getByScopeAndSpace(memoryScope, spaceId) }
                .onError { message, error -> Logger.w("AssistantMemoryPage", "No se pudo leer la memoria del asistente: $message", error) }
                .getOrNull()
                .orEmpty()
    }

    SettingsSubPageScaffold(title = stringResource(R.string.assistant_detail_memory), onBack = onBack) {
        val a = assistant
        if (a == null) {
            item { Text(stringResource(R.string.assistant_detail_loading), color = MaterialTheme.colorScheme.outline) }
            return@SettingsSubPageScaffold
        }
        item {
            CardGroup(modifier = museAnimateItem()) {
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_detail_enable_memory)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_enable_memory_desc)) },
                    trailingContent = {
                        MuseSwitch(
                            checked = a.memoryEnabled,
                            onCheckedChange = { v -> update { it.copy(memoryEnabled = v) } },
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_detail_use_global_memory)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_use_global_memory_desc)) },
                    trailingContent = {
                        MuseSwitch(
                            checked = a.useGlobalMemory,
                            onCheckedChange = { v -> update { it.copy(useGlobalMemory = v) } },
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_detail_recent_chats_reference)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_recent_chats_reference_desc)) },
                    trailingContent = {
                        MuseSwitch(
                            checked = a.enableRecentChatsReference,
                            onCheckedChange = { v -> update { it.copy(enableRecentChatsReference = v) } },
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_detail_time_reminder)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_time_reminder_desc)) },
                    trailingContent = {
                        MuseSwitch(
                            checked = a.enableTimeReminder,
                            onCheckedChange = { v -> update { it.copy(enableTimeReminder = v) } },
                        )
                    },
                )
            }
        }
        // Entrada de memoria manual
        item {
            CardGroup(modifier = museAnimateItem(), title = { Text(stringResource(R.string.assistant_detail_manual_memory)) }) {
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_detail_add_memory)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_add_memory_desc)) },
                    trailingContent = { ChevronRight() },
                    onClick = { showAddFactDialog = true },
                )
            }
        }
        if (pinnedMemories.isNotEmpty()) {
            item {
                CardGroup(
                    modifier = museAnimateItem(),
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.assistant_detail_global_pinned_count, pinnedMemories.size))
                            Text(
                                text = stringResource(
                                    if (a.useGlobalMemory) {
                                        R.string.assistant_detail_global_pinned_enabled
                                    } else {
                                        R.string.assistant_detail_global_pinned_disabled
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                ) {
                    pinnedMemories.forEach { entry ->
                        item(
                            key = entry.id,
                            headlineContent = {
                                Text(entry.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = { Text(entry.createdAt) },
                        )
                    }
                }
            }
        }
        // Lista de memoria guardada(Admite expansión, mantenga presionado para editar y eliminar)
        item {
            CardGroup(
                modifier = museAnimateItem(),
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.assistant_detail_saved_memory_count, facts.size))
                        if (facts.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.assistant_detail_saved_memory_interaction_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            ) {
                if (facts.isEmpty()) {
                    item(headlineContent = { Text(stringResource(R.string.assistant_detail_no_memory)) })
                } else {
                    facts.forEach { fact ->
                        item(
                            key = fact.id,
                            onClick = {
                                if (expandedFactIds[fact.id] == true) {
                                    expandedFactIds.remove(fact.id)
                                } else {
                                    expandedFactIds[fact.id] = true
                                }
                            },
                            onLongClick = {
                                factInput = fact.fact
                                editingFactId = fact.id
                            },
                            headlineContent = {
                                val expanded = expandedFactIds[fact.id] == true
                                Text(
                                    text = fact.fact,
                                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = { Text(fact.createdAt) },
                            trailingContent = {
                                var showDeleteConfirm by remember { mutableStateOf(false) }
                                MuseTactileButton(
                                    icon = MuseIcons.trash,
                                    onClick = { showDeleteConfirm = true },
                                    contentDescription = stringResource(R.string.assistant_detail_delete_cd),
                                )
                                if (showDeleteConfirm) {
                                    ConfirmDeleteDialog(
                                        title = stringResource(R.string.assistant_detail_delete_memory),
                                        itemName = fact.fact,
                                        // MEM-04: Estructura de oración unificada + explicación de consecuencias(El asistente ya no hace referencia a este recuerdo.)
                                        consequence = stringResource(R.string.assistant_memory_delete_consequence),
                                        onConfirm = {
                                            showDeleteConfirm = false
                                            scope.launch {
                                                val deleted =
                                                    resultOf {
                                                        memoryStore.delete(
                                                            id = fact.id,
                                                            assistantId = if (memoryScope == "main") "default" else memoryScope,
                                                            scope = memoryScope,
                                                            spaceId = spaceId,
                                                        )
                                                    }
                                                        .onError { message, error ->
                                                            Logger.w("AssistantMemoryPage", "No se pudo eliminar la memoria del asistente: $message", error)
                                                        }
                                                        .getOrNull() == true
                                                if (deleted) expandedFactIds.remove(fact.id)
                                                refreshKey++
                                            }
                                        },
                                        onDismiss = { showDeleteConfirm = false },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // Agregar manualmente/Mantenga presionado para editar el cuadro de diálogo compartido.
    if (showAddFactDialog || editingFactId != null) {
        val targetFactId = editingFactId
        val isEditing = targetFactId != null
        fun dismissFactEditor() {
            showAddFactDialog = false
            editingFactId = null
            factInput = ""
        }
        MuseDialog(
            onDismissRequest = ::dismissFactEditor,
            title = stringResource(
                if (isEditing) {
                    R.string.assistant_detail_edit_memory_dialog_title
                } else {
                    R.string.assistant_detail_add_memory_dialog_title
                },
            ),
            content = {
                MuseTextField(
                    value = factInput,
                    onValueChange = { factInput = it },
                    label = { Text(stringResource(R.string.assistant_detail_input_fact)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            },
            confirmText = stringResource(R.string.assistant_detail_save),
            // MEM-07: El contenido vacío desactiva el botón guardar
            confirmEnabled = factInput.isNotBlank(),
            onConfirm = {
                val content = factInput.trim()
                scope.launch {
                    // Keep the same assistant/scope store that prompt assembly reads from.
                    val result =
                        resultOf {
                            if (targetFactId == null) {
                                memoryStore.add(FactStore.Fact(fact = content), scope = memoryScope, spaceId = spaceId)
                                true
                            } else {
                                memoryStore.update(id = targetFactId, content = content, scope = memoryScope)
                            }
                        }
                    val saved = result.getOrNull() ?: false
                    if (saved) {
                        targetFactId?.let { expandedFactIds[it] = true }
                        refreshKey++
                        dismissFactEditor()
                    } else if (result.isError) {
                        val message = (result as? Result.Error)?.let { it.throwable?.message ?: it.message }.orEmpty()
                        MuseToast.show(
                            context.getString(
                                if (targetFactId == null) R.string.memory_add_failed else R.string.memory_edit_failed,
                                message.ifBlank { missingValueText },
                            ),
                        )
                    } else {
                        MuseToast.show(missingValueText)
                    }
                }
            },
            dismissText = stringResource(R.string.assistant_detail_cancel),
            onDismiss = ::dismissFactEditor,
        )
    }
}

/** v2.0.1: Nombre para mostrar localizado a nivel de inferencia (value La coincidencia todavía usa el nombre de enumeración). */
@androidx.annotation.StringRes
private fun reasoningLevelLabelRes(level: ReasoningLevel): Int = when (level) {
    ReasoningLevel.OFF -> R.string.reasoning_level_off
    ReasoningLevel.AUTO -> R.string.reasoning_level_auto
    ReasoningLevel.LOW -> R.string.reasoning_level_low
    ReasoningLevel.MEDIUM -> R.string.reasoning_level_medium
    ReasoningLevel.HIGH -> R.string.reasoning_level_high
    ReasoningLevel.XHIGH -> R.string.reasoning_level_xhigh
}
