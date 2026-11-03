package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.core.ProviderConfig
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.rag.RagConfig
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSliderRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.statusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * v1.56: RAG Página de configuración de búsqueda de la base de conocimientos.
 *
 * Elementos de configuración:
 *  - RAG interruptor principal(inyección automática)
 *  - Embedding Fuente(API en la nube/local ONNX)
 *  - Selección de proveedor de nube(Seleccione de la lista de proveedores configurados)
 *  - Nombre del modelo de incrustación en la nube
 *  - Buscar topK(1-10)
 *  - umbral de similitud(0.0-1.0)
 *  - Tamaño del fragmento(100-2000 personaje)
 *  - Trozos superpuestos(0-200 personaje)
 */
@Composable
fun RagSettingsPage(onBack: () -> Unit, onManageKbs: () -> Unit = {}) {
    val context = LocalContext.current
    val settings: SettingsRepository = koinInject()
    val embeddingService: io.zer0.muse.rag.EmbeddingService = koinInject()
    val config by settings.ragConfigFlow.collectAsStateWithLifecycle(initialValue = RagConfig())
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList<ProviderConfig>())
    // El backend de inserción actualmente efectivo;null Indica que la búsqueda aún no se ha ejecutado,La tarjeta de estado no se muestra en este momento.
    val activeBackend by embeddingService.activeBackend.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // P2-4: embedding Estado de la prueba de conectividad
    var embeddingTestRunning by remember { mutableStateOf(false) }
    var embeddingTestResult by remember { mutableStateOf<TestResult?>(null) }

    // P2-7: ONNX Selección de archivo de modelo (SAF). Copie al directorio privado de la aplicación para evitar la caducidad de la autorización URI de SAF.
    val onnxPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = java.io.File(context.filesDir, "muse_onnx").apply { mkdirs() }
                        val target = java.io.File(dir, "embedding.onnx")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("selected file stream is null: unable to read the picked document")
                        target.absolutePath
                    }
                }
                saved.onSuccess { path ->
                    settings.saveRagConfig(config.copy(localModelPath = path))
                    MuseToast.show(context.getString(R.string.settings_rag_onnx_picked))
                }.onFailure { e ->
                    MuseToast.show(context.getString(R.string.settings_rag_onnx_pick_failed, e.message ?: ""))
                }
            }
        }
    }

    // Al ingresar a la página, el backend real que entra en vigencia se analizará una vez.:De lo contrario, la tarjeta de estado no aparecerá hasta la primera recuperación.,
    // El motivo de la degradación es exactamente lo que los usuarios más necesitan ver cuando acaban de cambiar la configuración.
    LaunchedEffect(
        config.embeddingSource,
        config.cloudProviderId,
        config.cloudModel,
        config.localModelPath,
    ) {
        resolveActiveBackendQuietly(embeddingService, config)
    }

    // M-RAG1: estado temporal,Evite escribir cada vez que cambie Slider/TextField DataStore,Sólo al soltar(debounce)guardar cuando
    var cloudModelTemp by remember(config.cloudModel) { mutableStateOf(config.cloudModel) }
    var topKTemp by remember(config.topK) { mutableStateOf(config.topK) }
    var thresholdTemp by remember(config.threshold) { mutableStateOf(config.threshold) }
    var chunkSizeTemp by remember(config.chunkSize) { mutableStateOf(config.chunkSize) }
    var chunkOverlapTemp by remember(config.chunkOverlap) { mutableStateOf(config.chunkOverlap) }
    // v1.133: Búsqueda avanzada de estado temporal
    var mmrLambdaTemp by remember(config.mmrLambda) { mutableStateOf(config.mmrLambda) }
    var tokenBudgetTemp by remember(config.tokenBudget) { mutableStateOf(config.tokenBudget) }
    // B4-02: Estado temporal del peso de recuperación híbrido
    var hybridBm25WeightTemp by remember(config.hybridBm25Weight) { mutableStateOf(config.hybridBm25Weight) }
    var hybridVectorWeightTemp by remember(config.hybridVectorWeight) { mutableStateOf(config.hybridVectorWeight) }
    // TextField Ninguno onValueChangeFinished,Ahorra con rebote
    LaunchedEffect(cloudModelTemp) {
        delay(300)
        if (cloudModelTemp != config.cloudModel) {
            scope.launch {
                runCatching {
                    settings.saveRagConfig(config.copy(cloudModel = cloudModelTemp))
                    MuseToast.show(context.getString(R.string.rag_saved_success))
                }.onFailure {
                    MuseToast.show(context.getString(R.string.rag_save_failed))
                }
            }
        }
    }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_rag_page_title), onBack = onBack) {
        // ── interruptor principal ──
        item { SectionLabel(stringResource(R.string.settings_rag_injection_section)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = MuseIcons.sparkle,
                    title = stringResource(R.string.settings_rag_auto_inject),
                    subtitle = stringResource(R.string.settings_rag_auto_inject_subtitle),
                    checked = config.enabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveRagConfig(config.copy(enabled = v)) }
                    },
                )
            }
        }

        // ── Recuperación de archivos adjuntos de sesión(De experimental a regular;Activado de forma predeterminada) ──
        item { SectionLabel(stringResource(R.string.settings_rag_attachment_section)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = MuseIcons.file,
                    title = stringResource(R.string.settings_experiments_session_attachment_rag),
                    subtitle = stringResource(R.string.settings_experiments_session_attachment_rag_subtitle),
                    checked = config.sessionAttachmentEnabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveRagConfig(config.copy(sessionAttachmentEnabled = v)) }
                    },
                )
            }
        }

        // ── Embedding Fuente ──
        item { SectionLabel(stringResource(R.string.settings_rag_embedding_source_section)) }
        item {
            SettingsGroup {
                EmbeddingSourceOption(
                    icon = MuseIcons.cloud,
                    title = stringResource(R.string.settings_rag_cloud_api),
                    subtitle = stringResource(R.string.settings_rag_cloud_api_subtitle),
                    selected = config.embeddingSource == RagConfig.EmbeddingSource.CLOUD,
                    onClick = {
                        scope.launch {
                            settings.saveRagConfig(config.copy(embeddingSource = RagConfig.EmbeddingSource.CLOUD))
                        }
                    },
                )
                SettingsGroupDivider()
                EmbeddingSourceOption(
                    icon = MuseIcons.cloudOff,
                    title = stringResource(R.string.settings_rag_local_keyword),
                    subtitle = stringResource(R.string.settings_rag_local_keyword_subtitle),
                    // v1.x: LOCAL con LOCAL_KEYWORD(Palabras clave sin conexión predeterminadas)son considerados como"locales"Estado seleccionado,
                    // Cuando no está seleccionado(Predeterminado LOCAL_KEYWORD)También debería aparecer como seleccionado.,Nunca vuelvas a aparecer"No seleccione ninguno"
                    selected = config.embeddingSource != RagConfig.EmbeddingSource.CLOUD,
                    onClick = {
                        scope.launch {
                            settings.saveRagConfig(
                                // Limpia también los campos de nubes.:De lo contrario, los usuarios que se hayan configurado con la nube en el historial serán devueltos después de una migración única. CLOUD,
                                // Las "palabras clave locales" nunca se pueden leer.
                                config.copy(
                                    embeddingSource = RagConfig.EmbeddingSource.LOCAL_KEYWORD,
                                    cloudProviderId = "",
                                    cloudModel = "",
                                ),
                            )
                        }
                    },
                )
            }
        }

        // ── El backend de inserción actualmente efectivo ──
        // Cuando falla la configuración de la nube, EmbeddingService recurrirá a palabras clave locales.(Disminución de la capacidad de recuperación semántica.),
        // Esta degradación debe mostrarse al usuario.,No puedes simplemente dejarlo en el registro.
        if (activeBackend != null) {
            item { SectionLabel(stringResource(R.string.settings_rag_active_backend_section)) }
            item { ActiveBackendCard(activeBackend) }
        }

        // ── P2-7: Selección de modelo ONNX nativo (visualización en modo no NUBE)──
        // Informe de auditoría: RagConfig.localModelPath Presione en el directorio ui/ 0, los usuarios normales nunca podrán coincidir
        // ONNX modelo,LOCAL El modo se degrada permanentemente a LOCAL_KEYWORD (512 Frecuencia de palabras dihash, recuperación no semántica).
        if (config.embeddingSource != RagConfig.EmbeddingSource.CLOUD) {
            item { SectionLabel(stringResource(R.string.settings_rag_onnx_section)) }
            item {
                SettingsGroup {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_rag_onnx_model_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (config.localModelPath.isBlank()) {
                                stringResource(R.string.settings_rag_onnx_model_empty)
                            } else {
                                config.localModelPath
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MuseCapsuleButton(
                                text = stringResource(R.string.settings_rag_onnx_pick),
                                onClick = { onnxPicker.launch(arrayOf("*/*")) },
                                variant = IosCapsuleButtonVariant.Secondary,
                                fillWidth = false,
                            )
                            if (config.localModelPath.isNotBlank()) {
                                MuseCapsuleButton(
                                    text = stringResource(R.string.settings_rag_onnx_clear),
                                    onClick = {
                                        scope.launch {
                                            settings.saveRagConfig(config.copy(localModelPath = ""))
                                        }
                                    },
                                    variant = IosCapsuleButtonVariant.Secondary,
                                    fillWidth = false,
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.settings_rag_onnx_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }

        // ── Configuración de la nube(Sólo se muestra en modo NUBE)──
        if (config.embeddingSource == RagConfig.EmbeddingSource.CLOUD) {
            item { SectionLabel(stringResource(R.string.settings_rag_cloud_provider_section)) }
            item {
                SettingsGroup {
                    if (providers.isEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MusePaddings.cardInner),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = MuseIcons.flask,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.settings_rag_no_provider_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    } else {
                        val baseUrlNotSetText = stringResource(R.string.settings_rag_baseurl_not_set)
                        providers.forEach { provider ->
                            EmbeddingSourceOption(
                                icon = MuseIcons.cloud,
                                title = provider.displayName.ifBlank { provider.id },
                                subtitle = provider.baseUrl.ifBlank { baseUrlNotSetText },
                                selected = config.cloudProviderId == provider.id,
                                onClick = {
                                    scope.launch {
                                        settings.saveRagConfig(
                                            if (config.cloudProviderId == provider.id) {
                                                config.copy(cloudProviderId = "")
                                            } else {
                                                config.copy(cloudProviderId = provider.id)
                                            },
                                        )
                                    }
                                },
                            )
                        }
                        if (config.cloudProviderId.isNotBlank()) {
                            SettingsGroupDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(MusePaddings.cardInnerSpaced),
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_rag_deselect_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // Entrada del nombre del modelo
            item { SectionLabel(stringResource(R.string.settings_rag_embedding_model_section)) }
            item {
                SettingsGroup {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_rag_model_name_hint),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        MuseTextField(
                            // M-RAG1: Vincular estado temporal,Publicado por rebote de LaunchedEffect DataStore
                            value = cloudModelTemp,
                            onValueChange = { v ->
                                cloudModelTemp = v
                            },
                            placeholder = {
                                Text(
                                    when {
                                        providers.isEmpty() -> "text-embedding-3-small"
                                        config.cloudProviderId.isNotBlank() -> {
                                            val p = providers.find { it.id == config.cloudProviderId }
                                            if (p?.displayName?.contains("openai", true) == true) {
                                                "text-embedding-3-small"
                                            } else {
                                                "embedding-2"
                                            }
                                        }
                                        else -> "text-embedding-3-small"
                                    },
                                )
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                        Text(
                            text = stringResource(R.string.settings_rag_common_models),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        // P2-3(informe de auditoría): No más filtrado forzado de la lista de proveedores —— es la estación de transferencia
                        // Proporciona incrustación, que no se puede determinar estáticamente (hay modelos de diálogo y
                        // embedding modelo). Cambie a un mensaje claro + proporcione verificación con un solo clic.
                        Text(
                            text = stringResource(R.string.settings_rag_embedding_provider_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        // P2-4(informe de auditoría): embedding / rerank No hay pruebas de conectividad.
                        // Falta de coincidencia (nombre de modelo incorrecto, saldo insuficiente,Provider No compatible) Solo puede averiguarlo después de reconstruir el índice.
                        // Y la información de falla solo se ingresa en logcat. Ahora puedes insertar una oración con un solo clic. "ping" Y haga eco de las dimensiones y el consumo de tiempo.
                        Spacer(Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MuseCapsuleButton(
                                text = if (embeddingTestRunning) {
                                    stringResource(R.string.settings_rag_embedding_testing)
                                } else {
                                    stringResource(R.string.settings_rag_embedding_test)
                                },
                                onClick = {
                                    if (!embeddingTestRunning) {
                                        embeddingTestRunning = true
                                        embeddingTestResult = null
                                        scope.launch {
                                            val cfg = runCatching { settings.getRagConfig() }.getOrNull()
                                            if (cfg == null) {
                                                embeddingTestResult =
                                                    TestResult(
                                                        false,
                                                        context.getString(
                                                            R.string.settings_rag_embedding_test_failed,
                                                            context.getString(R.string.settings_rag_config_read_failed),
                                                        ),
                                                    )
                                                embeddingTestRunning = false
                                                return@launch
                                            }
                                            val outcome = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    val provider = embeddingService.getProvider(cfg)
                                                    val started = System.currentTimeMillis()
                                                    val vec = provider.embed("ping")
                                                    vec.size to (System.currentTimeMillis() - started)
                                                }
                                            }
                                            embeddingTestResult = outcome.fold(
                                                onSuccess = { (dim, ms) ->
                                                    TestResult(true, context.getString(R.string.settings_rag_embedding_test_ok, dim, ms))
                                                },
                                                onFailure = { e ->
                                                    TestResult(
                                                        false,
                                                        context.getString(
                                                            R.string.settings_rag_embedding_test_failed,
                                                            e.message?.take(160) ?: e::class.simpleName ?: "",
                                                        ),
                                                    )
                                                },
                                            )
                                            embeddingTestRunning = false
                                        }
                                    }
                                },
                                variant = IosCapsuleButtonVariant.Secondary,
                                fillWidth = false,
                            )
                        }
                        embeddingTestResult?.let { r ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = r.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (r.ok) MaterialTheme.statusColors.success else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        // ── Parámetros de recuperación ──
        item { SectionLabel(stringResource(R.string.settings_rag_search_params_section)) }
        item {
            SettingsGroup {
                SettingsSliderRow(
                    icon = MuseIcons.stack,
                    title = stringResource(R.string.settings_rag_top_k),
                    // M-RAG1: Vincular estado temporal,Sólo escribe cuando te sueltes DataStore
                    value = topKTemp.toFloat(),
                    valueRange = 1f..10f,
                    steps = 8,
                    valueText = "$topKTemp",
                    onValueChange = { v ->
                        topKTemp = v.roundToInt()
                    },
                    onValueChangeFinished = {
                        if (topKTemp != config.topK) {
                            scope.launch { settings.saveRagConfig(config.copy(topK = topKTemp)) }
                        }
                    },
                )
                SettingsGroupDivider()
                SettingsSliderRow(
                    icon = MuseIcons.gauge,
                    title = stringResource(R.string.settings_rag_similarity_threshold),
                    subtitle = stringResource(R.string.settings_rag_similarity_threshold_subtitle),
                    // M-RAG1: Vincular estado temporal,Sólo escribe cuando te sueltes DataStore
                    value = thresholdTemp,
                    valueRange = 0f..1f,
                    steps = 19,
                    valueText = "%.2f".format(thresholdTemp),
                    onValueChange = { v ->
                        thresholdTemp = v
                    },
                    onValueChangeFinished = {
                        if (thresholdTemp != config.threshold) {
                            scope.launch { settings.saveRagConfig(config.copy(threshold = thresholdTemp)) }
                        }
                    },
                )
            }
        }

        // ── v1.0.47 P7-1: analizador de documentos ──
        item { SectionLabel(stringResource(R.string.settings_rag_parser_section)) }
        item {
            SettingsGroup {
                EmbeddingSourceOption(
                    icon = MuseIcons.deviceMobile,
                    title = stringResource(R.string.settings_rag_parser_local),
                    subtitle = stringResource(R.string.settings_rag_parser_local_subtitle),
                    selected = config.documentParserType == RagConfig.ParserType.LOCAL,
                    onClick = {
                        scope.launch {
                            settings.saveRagConfig(config.copy(documentParserType = RagConfig.ParserType.LOCAL))
                        }
                    },
                )
                SettingsGroupDivider()
                EmbeddingSourceOption(
                    icon = MuseIcons.cloud,
                    title = stringResource(R.string.settings_rag_parser_cloud),
                    subtitle = stringResource(R.string.settings_rag_parser_cloud_subtitle),
                    selected = config.documentParserType == RagConfig.ParserType.CLOUD,
                    onClick = {
                        scope.launch {
                            settings.saveRagConfig(config.copy(documentParserType = RagConfig.ParserType.CLOUD))
                        }
                    },
                )
                SettingsGroupDivider()
                EmbeddingSourceOption(
                    icon = MuseIcons.fileText,
                    title = stringResource(R.string.settings_rag_parser_mineru),
                    subtitle = stringResource(R.string.settings_rag_parser_mineru_subtitle),
                    selected = config.documentParserType == RagConfig.ParserType.MINERU,
                    onClick = {
                        scope.launch {
                            settings.saveRagConfig(config.copy(documentParserType = RagConfig.ParserType.MINERU))
                        }
                    },
                )
            }
        }
        // CLOUD / MINERU endpoint Configuración(Mostrar solo cuando se selecciona)
        if (config.documentParserType == RagConfig.ParserType.CLOUD) {
            item {
                SettingsGroup {
                    EndpointInputRow(
                        title = stringResource(R.string.settings_rag_cloud_parser_endpoint),
                        value = config.cloudParserEndpoint,
                        onValueChange = { v ->
                            scope.launch { settings.saveRagConfig(config.copy(cloudParserEndpoint = v)) }
                        },
                    )
                }
            }
        }
        if (config.documentParserType == RagConfig.ParserType.MINERU) {
            item {
                SettingsGroup {
                    EndpointInputRow(
                        title = stringResource(R.string.settings_rag_mineru_endpoint),
                        value = config.mineruEndpoint,
                        onValueChange = { v ->
                            scope.launch { settings.saveRagConfig(config.copy(mineruEndpoint = v)) }
                        },
                    )
                    SettingsGroupDivider()
                    EndpointInputRow(
                        title = stringResource(R.string.settings_rag_mineru_token),
                        value = config.mineruToken,
                        placeholder = "sk-...",
                        onValueChange = { v ->
                            scope.launch { settings.saveRagConfig(config.copy(mineruToken = v)) }
                        },
                    )
                }
            }
        }

        // ── Parámetros de fragmentación ──
        item { SectionLabel(stringResource(R.string.settings_rag_chunk_params_section)) }
        item {
            SettingsGroup {
                SettingsSliderRow(
                    icon = MuseIcons.layoutColumns,
                    title = stringResource(R.string.settings_rag_chunk_size),
                    subtitle = stringResource(R.string.settings_rag_chunk_size_subtitle),
                    // M-RAG1: Vincular estado temporal,Sólo escribe cuando te sueltes DataStore
                    value = chunkSizeTemp.toFloat(),
                    valueRange = 100f..2000f,
                    steps = 18,
                    valueText = "$chunkSizeTemp",
                    onValueChange = { v ->
                        chunkSizeTemp = v.roundToInt()
                    },
                    onValueChangeFinished = {
                        if (chunkSizeTemp != config.chunkSize) {
                            scope.launch { settings.saveRagConfig(config.copy(chunkSize = chunkSizeTemp)) }
                        }
                    },
                )
                SettingsGroupDivider()
                SettingsSliderRow(
                    icon = MuseIcons.layoutColumns,
                    title = stringResource(R.string.settings_rag_chunk_overlap),
                    subtitle = stringResource(R.string.settings_rag_chunk_overlap_subtitle),
                    // M-RAG1: Vincular estado temporal,Sólo escribe cuando te sueltes DataStore
                    value = chunkOverlapTemp.toFloat(),
                    valueRange = 0f..200f,
                    steps = 19,
                    valueText = "$chunkOverlapTemp",
                    onValueChange = { v ->
                        chunkOverlapTemp = v.roundToInt()
                    },
                    onValueChangeFinished = {
                        if (chunkOverlapTemp != config.chunkOverlap) {
                            scope.launch { settings.saveRagConfig(config.copy(chunkOverlap = chunkOverlapTemp)) }
                        }
                    },
                )
                SettingsGroupDivider()
                // v1.133: Markdown fragmentación perceptiva
                SettingsSwitchRow(
                    icon = MuseIcons.layoutColumns,
                    title = stringResource(R.string.settings_rag_markdown_aware),
                    subtitle = stringResource(R.string.settings_rag_markdown_aware_subtitle),
                    checked = config.markdownAware,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveRagConfig(config.copy(markdownAware = v)) }
                    },
                )
                SettingsGroupDivider()
                // v1.133: Trozo por ficha
                SettingsSwitchRow(
                    icon = MuseIcons.layoutColumns,
                    title = stringResource(R.string.settings_rag_chunk_by_token),
                    subtitle = stringResource(R.string.settings_rag_chunk_by_token_subtitle),
                    checked = config.chunkByToken,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveRagConfig(config.copy(chunkByToken = v)) }
                    },
                )
            }
        }

        // ── v1.133: Búsqueda avanzada ──
        item { SectionLabel(stringResource(R.string.settings_rag_advanced_section)) }
        item {
            SettingsGroup {
                // MMR Diversidad
                SettingsSliderRow(
                    icon = MuseIcons.affiliate,
                    title = stringResource(R.string.settings_rag_mmr_lambda),
                    subtitle = stringResource(R.string.settings_rag_mmr_lambda_subtitle),
                    value = mmrLambdaTemp,
                    valueRange = 0f..1f,
                    steps = 19,
                    valueText = "%.2f".format(mmrLambdaTemp),
                    onValueChange = { v ->
                        mmrLambdaTemp = v
                    },
                    onValueChangeFinished = {
                        if (mmrLambdaTemp != config.mmrLambda) {
                            scope.launch { settings.saveRagConfig(config.copy(mmrLambda = mmrLambdaTemp)) }
                        }
                    },
                )
                SettingsGroupDivider()
                // Búsqueda híbrida
                SettingsSwitchRow(
                    icon = MuseIcons.swapHorizontal,
                    title = stringResource(R.string.settings_rag_hybrid),
                    subtitle = stringResource(R.string.settings_rag_hybrid_subtitle),
                    checked = config.hybridEnabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveRagConfig(config.copy(hybridEnabled = v)) }
                    },
                )
                SettingsGroupDivider()
                SettingsSliderRow(
                    icon = MuseIcons.typography,
                    title = stringResource(R.string.settings_rag_hybrid_bm25_weight),
                    subtitle = stringResource(R.string.settings_rag_hybrid_bm25_weight_subtitle),
                    value = hybridBm25WeightTemp,
                    valueRange = 0f..2f,
                    steps = 19,
                    valueText = "%.2f".format(hybridBm25WeightTemp),
                    onValueChange = { v -> hybridBm25WeightTemp = v },
                    onValueChangeFinished = {
                        if (hybridBm25WeightTemp != config.hybridBm25Weight) {
                            scope.launch { settings.saveRagConfig(config.copy(hybridBm25Weight = hybridBm25WeightTemp)) }
                        }
                    },
                )
                SettingsGroupDivider()
                SettingsSliderRow(
                    icon = MuseIcons.atom,
                    title = stringResource(R.string.settings_rag_hybrid_vector_weight),
                    subtitle = stringResource(R.string.settings_rag_hybrid_vector_weight_subtitle),
                    value = hybridVectorWeightTemp,
                    valueRange = 0f..2f,
                    steps = 19,
                    valueText = "%.2f".format(hybridVectorWeightTemp),
                    onValueChange = { v -> hybridVectorWeightTemp = v },
                    onValueChangeFinished = {
                        if (hybridVectorWeightTemp != config.hybridVectorWeight) {
                            scope.launch { settings.saveRagConfig(config.copy(hybridVectorWeight = hybridVectorWeightTemp)) }
                        }
                    },
                )
                SettingsGroupDivider()
                // Rerank
                SettingsSwitchRow(
                    icon = MuseIcons.sort,
                    title = stringResource(R.string.settings_rag_rerank),
                    subtitle = stringResource(R.string.settings_rag_rerank_subtitle),
                    checked = config.rerankEnabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveRagConfig(config.copy(rerankEnabled = v)) }
                    },
                )
                SettingsGroupDivider()
                // Token presupuesto
                SettingsSliderRow(
                    icon = MuseIcons.wallet,
                    title = stringResource(R.string.settings_rag_token_budget),
                    subtitle = stringResource(R.string.settings_rag_token_budget_subtitle),
                    value = tokenBudgetTemp.toFloat(),
                    valueRange = 0f..8000f,
                    steps = 79,
                    valueText = if (tokenBudgetTemp == 0) "∞" else "$tokenBudgetTemp",
                    onValueChange = { v ->
                        tokenBudgetTemp = v.roundToInt()
                    },
                    onValueChangeFinished = {
                        if (tokenBudgetTemp != config.tokenBudget) {
                            scope.launch { settings.saveRagConfig(config.copy(tokenBudget = tokenBudgetTemp)) }
                        }
                    },
                )
            }
        }

        // ── Descripción ──
        // v1.133: Portal de gestión de bases de conocimientos múltiples(Colocar antes del título de la sección de descripción.,como independiente section)
        item { SectionLabel(stringResource(R.string.settings_rag_manage_kbs)) }
        item {
            SettingsGroup {
                SettingsItemRow(
                    icon = MuseIcons.folder,
                    title = stringResource(R.string.settings_rag_manage_kbs),
                    subtitle = stringResource(R.string.settings_rag_manage_kbs_subtitle),
                    onClick = onManageKbs,
                ) {
                    ChevronRight()
                }
            }
        }

        item { SectionLabel(stringResource(R.string.settings_rag_notes_section)) }
        item {
            SettingsGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_rag_note1),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.settings_rag_note2),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.settings_rag_note3),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Analizar activamente el backend de incrustación actualmente efectivo,Evite que la tarjeta de estado tenga que esperar a la primera recuperación.
 *
 * Si se ha analizado, se devolverá directamente.,Evite reconstruir la instancia del Proveedor cada vez que ingresa a la página(La ruta de búsqueda y la página de configuración comparten el mismo caché).
 * Registrar solo cuando falla el análisis,La tarjeta de estado permanece sin procesar,No está permitido bloquear la página de configuración.
 *
 * LOCAL(ONNX)El modo solo crea una instancia de Proveedor y verifica si el archivo existe.,El modelo y la sesión ONNX no se cargan hasta la primera inserción.,
 * Por eso se puede llamar incondicionalmente aquí.
 */
@Suppress("TooGenericExceptionCaught")
private suspend fun resolveActiveBackendQuietly(
    embeddingService: io.zer0.muse.rag.EmbeddingService,
    config: RagConfig,
) {
    if (embeddingService.activeBackend.value != null) return
    try {
        embeddingService.getProvider(config)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(
            "RagSettingsPage",
            "No se pudo analizar el backend incrustado(source=${config.embeddingSource}): ${e.message ?: e::class.java.simpleName}",
        )
    }
}

/**
 * Tarjeta de estado "Qué backend de inserción se está utilizando actualmente".
 *
 * La línea principal es el nombre para mostrar del backend + nombre del modelo.;Agregue una línea de razones al bajar de categoría(Cuando falten motivos, utilizar la copia de seguridad en los recursos.).
 * [state] Cuando es nulo, no se representa todo el bloque.
 */
@Composable
private fun ActiveBackendCard(state: io.zer0.muse.rag.EmbeddingService.BackendState?) {
    val summary = resolveActiveBackendSummary(
        state = state,
        degradedFallback = stringResource(R.string.settings_rag_active_backend_degraded_unknown),
    ) ?: return
    SettingsGroup {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
        ) {
            Text(
                text = stringResource(R.string.settings_rag_active_backend_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = summary.primary,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
            val warning = summary.warning
            if (warning != null) {
                Text(
                    text = warning,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun EmbeddingSourceOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SettingsItemRow(
        icon = icon,
        title = title,
        subtitle = subtitle.takeIf { it.isNotBlank() },
        onClick = onClick,
        trailing = {
            if (selected) {
                Icon(
                    imageVector = MuseIcons.check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    )
}

/**
 * v1.0.47 P7-1: endpoint línea de entrada(para la configuración del analizador CLOUD/MINERU).
 * Ahorra con rebote(Consistente con el modelo de nube),Evite escribir en el DataStore cada vez que se presiona una tecla.
 */
@Composable
private fun EndpointInputRow(
    title: String,
    value: String,
    placeholder: String = "https://example.com/api/parse",
    onValueChange: (String) -> Unit,
) {
    var temp by remember(value) { mutableStateOf(value) }
    LaunchedEffect(temp) {
        delay(300)
        if (temp != value) onValueChange(temp)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MuseTextField(
            value = temp,
            onValueChange = { temp = it },
            placeholder = { Text(placeholder) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
}

/** P2-4: embedding Resultado de la prueba (exitoso)/Fallo + mensaje legible). */
private data class TestResult(val ok: Boolean, val message: String)
