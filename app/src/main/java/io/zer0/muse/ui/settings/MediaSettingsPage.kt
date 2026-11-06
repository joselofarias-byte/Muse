package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.MediaConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.SpeechProviderResolver
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseSelectionSheet
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSliderRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.speech.TtsEngineSwitch
import io.zer0.muse.ui.speech.TtsManager
import io.zer0.muse.ui.speech.VoiceInfo
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v0.32: Página de configuración de medios.
 *
 * Parámetros que controlan la grabación de voz y la transmisión de voz TTS:
 *  - Frecuencia de muestreo de grabación/tasa de bits
 *  - TTS cambiar/velocidad al hablar/tono/Idioma
 *  - Enrutamiento de audio predeterminado del sistema
 */
@Composable
fun MediaSettingsPage(
    onBack: () -> Unit,
    /** P2-9: Abra la página de clonación de voz.(De \"transmisión de voz(TTS)\"Ingrese a la entrada del grupo). */
    onOpenVoiceCloning: () -> Unit = {},
    /** Salir cuando el modelo candidato esté vacío: El proveedor gestiona el modelo de manera uniforme y hay que volver a esa página para agregarlo. */
    onOpenProviderSettings: () -> Unit = {},
) {
    val settings: SettingsRepository = koinInject()
    val ttsManager: TtsManager = koinInject()
    val config by settings.mediaConfigFlow.collectAsStateWithLifecycle(initialValue = MediaConfig())
    val scope = rememberCoroutineScope()

    SettingsSubPageScaffold(title = stringResource(R.string.settings_media_page_title), onBack = onBack) {
        // ── 1. Transmisión de voz(TTS) ──
        item { SectionLabel(stringResource(R.string.settings_media_tts_section)) }
        item {
            SettingsGroup(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                SettingsSliderRow(
                    icon = MuseIcons.microphone,
                    title = stringResource(R.string.settings_media_speech_rate),
                    subtitle = stringResource(R.string.settings_media_speech_rate_subtitle),
                    value = config.ttsSpeechRate,
                    valueRange = 0.5f..2.0f,
                    steps = 14,
                    valueText = "%.1fx".format(config.ttsSpeechRate),
                    onValueChange = { v ->
                        scope.launch { settings.saveMediaConfig(config.copy(ttsSpeechRate = v)) }
                    },
                )
                SettingsGroupDivider()
                SettingsSliderRow(
                    icon = MuseIcons.microphone,
                    title = stringResource(R.string.settings_media_pitch),
                    subtitle = stringResource(R.string.settings_media_pitch_subtitle),
                    value = config.ttsPitch,
                    valueRange = 0.5f..2.0f,
                    steps = 14,
                    valueText = "%.1fx".format(config.ttsPitch),
                    onValueChange = { v ->
                        scope.launch { settings.saveMediaConfig(config.copy(ttsPitch = v)) }
                    },
                )
                // TTS selección de sonido
                SettingsGroupDivider()
                TtsVoiceSelector(
                    ttsManager = ttsManager,
                    currentVoice = config.ttsVoiceName,
                    onVoiceSelected = { voiceName ->
                        scope.launch { settings.saveMediaConfig(config.copy(ttsVoiceName = voiceName)) }
                    },
                )
            }
        }

        // ── 2. Motor TTS en la nube ──
        item { SectionLabel(stringResource(R.string.settings_media_cloud_tts_section)) }
        item {
            CloudTtsConfigSection(
                config = config,
                settings = settings,
                ttsManager = ttsManager,
                onOpenProviderSettings = onOpenProviderSettings,
            )
        }

        // ── v1.99(4.8): Parámetros avanzados de Cloud TTS(Se muestra solo en Cloud Engine)──
        if (config.ttsEngine != "system" && hasAdvancedParams(config.ttsEngine)) {
            item { SectionLabel(stringResource(R.string.settings_media_tts_advanced_section)) }
            item {
                AdvancedTtsParamsSection(
                    config = config,
                    settings = settings,
                )
            }
        }

        // ── P2-9: Portal de clonación de voz(independiente SettingsGroup,Ingrese desde debajo del motor TTS en la nube)──
        item {
            SettingsGroup(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                SettingsItemRow(
                    icon = MuseIcons.microphone,
                    title = stringResource(R.string.voice_cloning_title),
                    subtitle = stringResource(R.string.voice_cloning_new_voice),
                    onClick = onOpenVoiceCloning,
                ) { ChevronRight() }
            }
        }

        // El dispositivo de salida sigue el sistema Android y el enfoque de audio actual, sin duplicar abstracciones dentro de Muse.
        // ── Biblioteca de stickers (capacidades multimedia, devueltas desde la página de chat)──
        item { StickerSettingsSection(settings = settings, scope = scope) }
    }
}

/**
 * TTS selector de sonido — El menú desplegable enumera los sonidos TTS disponibles en el sistema.
 */
@Composable
private fun TtsVoiceSelector(ttsManager: TtsManager, currentVoice: String, onVoiceSelected: (String) -> Unit) {
    val voices = remember { ttsManager.getAvailableVoices() }

    var expanded by remember { mutableStateOf(false) }
    val defaultLabel = stringResource(R.string.settings_media_tts_voice_default)
    val displayName = if (currentVoice.isNotBlank()) currentVoice else defaultLabel

    Box {
        SettingsItemRow(
            icon = MuseIcons.microphone,
            title = stringResource(R.string.settings_media_tts_voice_selector),
            // MEM-08: Las listas vacías ya no desaparecen — El subtítulo explica
            subtitle = if (voices.isEmpty()) {
                stringResource(R.string.settings_media_tts_voice_empty)
            } else {
                displayName
            },
            onClick = { if (voices.isNotEmpty()) expanded = true },
        ) {
            ChevronRight()
        }
        // MEM-08: Cambio de lista navegable MuseSelectionSheet(DropdownMenu No apto para listas largas)
        if (expanded) {
            MuseSelectionSheet(
                title = stringResource(R.string.settings_media_tts_voice_selector),
                onDismissRequest = { expanded = false },
            ) {
                MuseListItem(
                    headlineContent = { Text(defaultLabel) },
                    onClick = {
                        onVoiceSelected("")
                        expanded = false
                    },
                )
                voices.forEach { voice ->
                    MuseListItem(
                        headlineContent = { Text(voice.name) },
                        onClick = {
                            onVoiceSelected(voice.name)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * v1.97: Área de configuración del motor Cloud TTS.
 *
 * Selección de motor(system + 11 nube de inicio Provider)→ API Key / Voice / Model / Endpoint forma.
 * system El modo muestra solo la selección del motor.,Ocultar el formulario.
 * botón de interacción de formulario [AsrSection]:OutlinedTextField + Botón Guardar + Comentarios sobre el brindis.
 *
 * v1.99(4.4): Se agregó un botón "Extraer lista de timbres" debajo de la entrada del timbre.,llamar [TtsManager.listCloudVoices]
 * Extraiga y muestre dinámicamente los timbres disponibles,Escribe después de seleccionar [MediaConfig.ttsVoice].
 */
@Composable
@Suppress("LongMethod") // motor/seguimiento/Credenciales/timbre/modelo/Las direcciones de la interfaz pertenecen a la misma pieza. Si los desmontas, tendrás que saltar repetidamente.
private fun CloudTtsConfigSection(
    config: MediaConfig,
    settings: SettingsRepository,
    ttsManager: TtsManager,
    onOpenProviderSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val systemLabel = stringResource(R.string.settings_media_tts_engine_system)
    val savedToast = stringResource(R.string.settings_media_tts_saved)
    val fetchFailedLabel = stringResource(R.string.settings_media_tts_voice_fetch_failed)
    val voiceResetLabel = stringResource(R.string.settings_media_tts_voice_reset)
    val pickLabel = stringResource(R.string.settings_media_tts_voice_pick)

    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        // Selección de motor(system + 11 nube de inicio)
        var engineExpanded by remember { mutableStateOf(false) }
        // stringResource Debe llamarse directamente en el alcance @Composable,no se puede anidar dentro de let/lambda
        val matchedResId = TtsManager.CLOUD_TTS_ENGINES
            .firstOrNull { it.first == config.ttsEngine }?.second
        val currentLabel = if (config.ttsEngine == "system" || matchedResId == null) {
            systemLabel
        } else {
            stringResource(matchedResId)
        }

        Box {
            SettingsItemRow(
                icon = MuseIcons.cloud,
                title = stringResource(R.string.settings_media_tts_engine),
                subtitle = currentLabel,
                onClick = { engineExpanded = true },
            ) {
                ChevronRight()
            }
            MuseAnchoredMenu(
                expanded = engineExpanded,
                onDismissRequest = { engineExpanded = false },
            ) {
                // Cambie el motor y verifique la identificación del tono al mismo tiempo: el tono del motor antiguo solo será reemplazado por 4xx en el motor nuevo. Es mejor borrar y volver a seleccionar.
                val switchEngine: (String) -> Unit = { engineId ->
                    val switched = TtsEngineSwitch.apply(config, engineId)
                    scope.launch { settings.saveMediaConfig(switched.config) }
                    engineExpanded = false
                    if (switched.voiceReset) MuseToast.show(voiceResetLabel, 2500)
                }
                MuseListItem(
                    onClick = { switchEngine("system") },
                    headlineContent = { Text(systemLabel) },
                )
                TtsManager.CLOUD_TTS_ENGINES.forEach { (engineId, labelRes) ->
                    MuseListItem(
                        onClick = { switchEngine(engineId) },
                        headlineContent = { Text(stringResource(labelRes)) },
                    )
                }
            }
        }

        // system Modal no muestra forma.
        if (config.ttsEngine == "system") return@SettingsGroup

        // Seguir la configuración del proveedor(Después de la apertura, el punto final/clave API se obtiene del proveedor.,Los modelos se seleccionan a mano aquí.)
        TtsFollowProviderSection(config = config, settings = settings)

        // API Key
        SettingsGroupDivider()
        var apiKey by remember(config.ttsEngine) { mutableStateOf(config.ttsApiKey) }
        var apiKeyVisible by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
        ) {
            MuseTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(stringResource(R.string.settings_media_tts_api_key)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                // Las credenciales las proporciona el proveedor cuando se sigue a un proveedor,Los valores manuales se conservan pero no se pueden editar.
                enabled = !config.ttsFollowProvider,
                visualTransformation = if (apiKeyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    MuseCapsuleButton(
                        text = stringResource(
                            if (apiKeyVisible) {
                                R.string.settings_media_tts_show
                            } else {
                                R.string.settings_media_tts_hide
                            },
                        ),
                        onClick = { apiKeyVisible = !apiKeyVisible },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                    )
                },
            )
            MuseCapsuleButton(
                text = stringResource(R.string.settings_media_tts_save_api_key),
                onClick = {
                    scope.launch {
                        settings.saveMediaConfig(config.copy(ttsApiKey = apiKey.trim()))
                        MuseToast.show(savedToast)
                    }
                },
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
                enabled = !config.ttsFollowProvider,
            )
        }

        // timbre / Voice ID(Entrada manual + tracción dinámica)
        SettingsGroupDivider()
        var voice by remember(config.ttsEngine) { mutableStateOf(config.ttsVoice) }
        var fetchedVoices by remember(config.ttsEngine) {
            mutableStateOf<List<VoiceInfo>>(emptyList())
        }
        var isFetching by remember(config.ttsEngine) { mutableStateOf(false) }
        var fetchError by remember(config.ttsEngine) { mutableStateOf(false) }
        var voicePickerExpanded by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
        ) {
            MuseTextField(
                value = voice,
                onValueChange = { voice = it },
                label = { Text(stringResource(R.string.settings_media_tts_voice)) },
                placeholder = { Text(stringResource(R.string.settings_media_tts_voice_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MuseCapsuleButton(
                    text = stringResource(R.string.settings_media_tts_save_voice),
                    onClick = {
                        scope.launch {
                            settings.saveMediaConfig(config.copy(ttsVoice = voice.trim()))
                            MuseToast.show(savedToast)
                        }
                    },
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
                MuseTactileButton(
                    icon = MuseIcons.refresh,
                    onClick = {
                        scope.launch {
                            isFetching = true
                            fetchError = false
                            val result = ttsManager.listCloudVoices()
                            if (result.isEmpty()) {
                                fetchError = true
                            } else {
                                fetchedVoices = result
                            }
                            isFetching = false
                        }
                    },
                    contentDescription = null,
                    enabled = !isFetching,
                )
            }
            if (fetchError) {
                Text(
                    text = fetchFailedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (fetchedVoices.isNotEmpty()) {
                MuseCapsuleButton(
                    text = pickLabel,
                    onClick = { voicePickerExpanded = true },
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
                // MEM-08: Cambio de lista navegable MuseSelectionSheet(Descripción de la banda de sonido,Las listas largas se pueden desplazar)
                if (voicePickerExpanded) {
                    MuseSelectionSheet(
                        title = pickLabel,
                        onDismissRequest = { voicePickerExpanded = false },
                    ) {
                        fetchedVoices.forEach { v ->
                            MuseListItem(
                                headlineContent = { Text(v.name) },
                                supportingContent = v.description?.takeIf { it.isNotBlank() }?.let { desc ->
                                    { Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                                },
                                onClick = {
                                    voice = v.id
                                    scope.launch {
                                        settings.saveMediaConfig(config.copy(ttsVoice = v.id))
                                        MuseToast.show(savedToast)
                                    }
                                    voicePickerExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }

        // Modelo: Los candidatos provienen de proveedores, aquí solo nos encargamos de seleccionar uno
        SettingsGroupDivider()
        val ttsProviders by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
        val modelProvider = remember(ttsProviders, config.ttsProviderId) {
            SpeechModelPicker.candidateProvider(ttsProviders, config.ttsProviderId)
        }
        // El modelo existe ttsModelId cuando se sigue al proveedor, existe el modo manual ttsModel
        val currentModelId = if (config.ttsFollowProvider) config.ttsModelId else config.ttsModel
        SpeechModelSection(
            title = stringResource(R.string.settings_media_tts_model),
            currentModelId = currentModelId,
            provider = modelProvider,
            emptyLabel = stringResource(R.string.settings_media_tts_model_placeholder),
            onPick = { modelId ->
                scope.launch { settings.saveMediaConfig(SpeechModelPicker.applyToTts(config, modelId)) }
            },
            onOpenProviders = onOpenProviderSettings,
        )

        // Personalizar Endpoint
        SettingsGroupDivider()
        var endpoint by remember(config.ttsEngine) { mutableStateOf(config.ttsEndpoint) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
        ) {
            MuseTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                label = { Text(stringResource(R.string.settings_media_tts_endpoint)) },
                placeholder = { Text(stringResource(R.string.settings_media_tts_endpoint_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                // Al seguir a un proveedor, la dirección de la interfaz la proporciona el proveedor.,Los valores manuales se conservan pero no se pueden editar.
                enabled = !config.ttsFollowProvider,
            )
            MuseCapsuleButton(
                text = stringResource(R.string.settings_media_tts_save_endpoint),
                onClick = {
                    scope.launch {
                        settings.saveMediaConfig(config.copy(ttsEndpoint = endpoint.trim()))
                        MuseToast.show(savedToast)
                    }
                },
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
                enabled = !config.ttsFollowProvider,
            )
        }
    }
}

/**
 * Bloque "Seguir configuración de proveedor" de Cloud TTS.
 *
 * Después de abrir, el punto final/clave API se toma del proveedor seleccionado;Seleccione el modelo en la fila "Modelo" a continuación (escriba ttsModelId siguiendo el modo),
 * Los valores introducidos manualmente se conservan tal como están.(Simplemente atenuado y no editable),Al apagar el interruptor se restablece inmediatamente la configuración manual.
 * Proveedor eliminado/Cuando un modelo está desactivado o no seleccionado,Configuración manual de respaldo sintético,Las sugerencias se dan aquí de forma sincrónica.
 */
@Composable
private fun TtsFollowProviderSection(config: MediaConfig, settings: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val unsetLabel = stringResource(R.string.settings_speech_follow_unset)

    SettingsGroupDivider()
    SettingsSwitchRow(
        icon = MuseIcons.cloud,
        title = stringResource(R.string.settings_media_tts_follow_provider),
        subtitle = stringResource(R.string.settings_media_tts_follow_provider_subtitle),
        checked = config.ttsFollowProvider,
        onCheckedChange = { follow ->
            scope.launch { settings.saveMediaConfig(config.copy(ttsFollowProvider = follow)) }
        },
    )

    if (config.ttsFollowProvider) {
        val selectedProvider = providers.firstOrNull { it.id == config.ttsProviderId }
        var providerExpanded by remember { mutableStateOf(false) }

        SettingsGroupDivider()
        Box {
            SettingsItemRow(
                icon = MuseIcons.server,
                title = stringResource(R.string.settings_media_tts_follow_provider_picker),
                subtitle = selectedProvider?.displayName ?: unsetLabel,
                onClick = { providerExpanded = true },
            ) {
                ChevronRight()
            }
            MuseAnchoredMenu(
                expanded = providerExpanded,
                onDismissRequest = { providerExpanded = false },
            ) {
                providers.forEach { provider ->
                    MuseListItem(
                        headlineContent = { Text(provider.displayName) },
                        onClick = {
                            // Selección clara del modelo al cambiar de proveedor,Evite enviar solicitudes con modelos del proveedor anterior
                            scope.launch {
                                settings.saveMediaConfig(
                                    config.copy(ttsProviderId = provider.id, ttsModelId = ""),
                                )
                            }
                            providerExpanded = false
                        },
                    )
                }
            }
        }

        // La selección del modelo no se repite aquí: la línea "Modelo" a continuación se escribe automáticamente presionando el interruptor de seguimiento. ttsModelId / ttsModel

        // El tono no sigue al proveedor.:Se recuerda a los usuarios que confirmen ellos mismos la compatibilidad,Evite la impresión errónea de que se ha asumido toda la credencial
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
        ) {
            Text(
                text = stringResource(R.string.settings_media_tts_follow_voice_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val resolved = SpeechProviderResolver.resolveMedia(config, providers)
        if (resolved.isProviderFallback) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.cardInner),
            ) {
                Text(
                    text = stringResource(
                        R.string.settings_speech_follow_fallback_notice,
                        stringResource(speechFollowFallbackReasonRes(resolved.fallbackReason)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Recursos de redacción publicitaria por motivos alternativos(Los motivos desconocidos se tratan como \"Proveedor no encontrado\".,No exponer cadenas internas en la interfaz de usuario). */
internal fun speechFollowFallbackReasonRes(reason: String?): Int = when (reason) {
    SpeechProviderResolver.REASON_PROVIDER_DISABLED -> R.string.settings_speech_follow_reason_provider_disabled
    SpeechProviderResolver.REASON_MODEL_MISSING -> R.string.settings_speech_follow_reason_model_missing
    else -> R.string.settings_speech_follow_reason_provider_missing
}

/**
 * v1.99(4.8): Área de configuración de parámetros avanzados de Cloud TTS.
 *
 * Representación basada en las condiciones de capacidad del motor.:
 *  - ElevenLabs: stability / similarity_boost control deslizante
 *  - MiniMax: Entrada de texto emocional + control deslizante de velocidad del habla
 *  - OpenAI compatibles(openai/dashscope/groq/step/edge): Control deslizante de velocidad de voz + formato de audio
 *  - Otros: Solo control deslizante de velocidad de conversación(Si es compatible)
 */
@Composable
private fun AdvancedTtsParamsSection(config: MediaConfig, settings: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val savedToast = stringResource(R.string.settings_media_tts_saved)
    val engine = config.ttsEngine

    // estado de edición local(Reiniciar con el interruptor del motor)
    var stability by remember(engine) { mutableStateOf(config.ttsStability) }
    var similarity by remember(engine) { mutableStateOf(config.ttsSimilarityBoost) }
    var emotion by remember(engine) { mutableStateOf(config.ttsEmotion) }
    var speed by remember(engine) { mutableStateOf(config.ttsCloudSpeed) }
    var responseFormat by remember(engine) { mutableStateOf(config.ttsResponseFormat.ifBlank { "mp3" }) }
    var formatExpanded by remember { mutableStateOf(false) }

    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        if (engine == "elevenlabs") {
            SettingsSliderRow(
                icon = MuseIcons.shield,
                title = stringResource(R.string.settings_media_tts_stability),
                subtitle = stringResource(R.string.settings_media_tts_stability),
                value = stability,
                valueRange = 0f..1f,
                steps = 9,
                valueText = "%.2f".format(stability),
                onValueChange = { stability = it },
            )
            SettingsGroupDivider()
            SettingsSliderRow(
                icon = MuseIcons.waveSine,
                title = stringResource(R.string.settings_media_tts_similarity),
                subtitle = stringResource(R.string.settings_media_tts_similarity),
                value = similarity,
                valueRange = 0f..1f,
                steps = 9,
                valueText = "%.2f".format(similarity),
                onValueChange = { similarity = it },
            )
            SettingsGroupDivider()
        }

        if (engine == "minimax") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.cardInner),
            ) {
                MuseTextField(
                    value = emotion,
                    onValueChange = { emotion = it },
                    label = { Text(stringResource(R.string.settings_media_tts_emotion)) },
                    placeholder = { Text(stringResource(R.string.settings_media_tts_emotion_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            SettingsGroupDivider()
        }

        if (supportsSpeed(engine)) {
            SettingsSliderRow(
                icon = MuseIcons.play,
                title = stringResource(R.string.settings_media_tts_cloud_speed),
                subtitle = stringResource(R.string.settings_media_tts_cloud_speed),
                value = speed,
                valueRange = 0.25f..4.0f,
                steps = 14,
                valueText = "%.2fx".format(speed),
                onValueChange = { speed = it },
            )
            SettingsGroupDivider()
        }

        if (supportsResponseFormat(engine)) {
            Box {
                SettingsItemRow(
                    icon = MuseIcons.template,
                    title = stringResource(R.string.settings_media_tts_response_format),
                    subtitle = responseFormat,
                    onClick = { formatExpanded = true },
                ) {
                    ChevronRight()
                }
                MuseAnchoredMenu(
                    expanded = formatExpanded,
                    onDismissRequest = { formatExpanded = false },
                ) {
                    listOf("mp3", "opus", "aac", "flac", "wav").forEach { fmt ->
                        MuseListItem(
                            onClick = {
                                responseFormat = fmt
                                formatExpanded = false
                            },
                            headlineContent = { Text(fmt) },
                        )
                    }
                }
            }
            SettingsGroupDivider()
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
        ) {
            MuseCapsuleButton(
                text = stringResource(R.string.settings_media_tts_save_advanced),
                onClick = {
                    scope.launch {
                        settings.saveMediaConfig(
                            config.copy(
                                ttsStability = stability,
                                ttsSimilarityBoost = similarity,
                                ttsEmotion = emotion.trim(),
                                ttsCloudSpeed = speed,
                                ttsResponseFormat = responseFormat,
                            ),
                        )
                        MuseToast.show(savedToast)
                    }
                },
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
            )
        }
    }
}

/**
 * Si el motor admite el parámetro de velocidad de voz(OpenAI compatibles + MiniMax + Edge).
 */
private fun supportsSpeed(engine: String): Boolean = engine in listOf("openai", "minimax", "dashscope", "groq", "step", "edge")

/**
 * Si el motor admite la selección de formato de audio(OpenAI compatibles + Edge).
 */
private fun supportsResponseFormat(engine: String): Boolean = engine in listOf("openai", "dashscope", "groq", "step", "edge")

/**
 * ¿El motor tiene parámetros avanzados configurables?(Determinar si se muestran "parámetros avanzados"section).
 */
private fun hasAdvancedParams(engine: String): Boolean = engine == "elevenlabs" || engine == "minimax" ||
    supportsSpeed(engine) || supportsResponseFormat(engine)
