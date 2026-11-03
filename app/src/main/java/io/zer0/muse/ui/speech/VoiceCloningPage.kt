package io.zer0.muse.ui.speech

import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.R
import io.zer0.muse.data.MediaConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * P2-9: Página de clonación de voz — Página de herramientas en pantalla completa.
 *
 * Diseño:
 *  - MuseTopBar:Volver + Título "Clon de voz"
 *  - Provider elegir(FilterChip,Actualmente solo elevenlabs,Se puede ampliar más tarde)
 *  - API Key Cuadro de entrada(OutlinedTextField + PasswordVisualTransformation)
 *  - Formulario "Clonar nueva voz":Entrada de nombre de voz + botón Seleccionar audio de muestra + botón Enviar(Surface + clickable)
 *  - Clonar lista de voces(LazyColumn):Cada línea muestra nombre + ID de voz + botón de eliminar
 *
 * ficha de diseño:[MuseShapes] / [MusePaddings],No utilizar Material3 por defecto Button / AlertDialog
 * (Para el botón enviar Surface+clickable,Eliminar ventana emergente de confirmación [MuseDialog]).
 */
@Composable
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
fun VoiceCloningPage(
    onBack: () -> Unit,
    voiceCloningService: VoiceCloningService = koinInject(),
    elevenLabsProvider: ElevenLabsVoiceCloningProvider = koinInject(),
    fishProvider: FishAudioVoiceCloningProvider = koinInject(),
    settings: SettingsRepository = koinInject(),
    /** v2.6.7 fix (AUDIT F21): Para uso de prueba. */
    ttsManager: TtsManager = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── estado del formulario(rememberSaveable Rotación garantizada/Conservar después de cambios de configuración;Uri Utilice recordar para evitar problemas de compatibilidad de serialización.)──
    var selectedProvider by rememberSaveable { mutableStateOf("elevenlabs") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }
    var voiceName by rememberSaveable { mutableStateOf("") }
    var selectedAudioUri by remember { mutableStateOf<Uri?>(null) }
    var selectedAudioLabel by rememberSaveable { mutableStateOf("") }

    // ── estado de funcionamiento ──
    var isCloning by rememberSaveable { mutableStateOf(false) }
    var isLoadingList by rememberSaveable { mutableStateOf(false) }
    var isDeleting by rememberSaveable { mutableStateOf(false) }
    val voices = remember { mutableStateListOf<ClonedVoice>() }

    // Eliminar el estado de la ventana emergente de confirmación(No usado Material3 AlertDialog,uso MuseDialog)
    var voiceToDelete by remember { mutableStateOf<ClonedVoice?>(null) }

    // ── Selector de archivos de audio(SAF OpenDocument,elegir audio/* Tipo)──
    val pickAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            selectedAudioUri = uri
            // Mostrar nombre de archivo(Obtenga lastPathSegment de Uri como identificador legible)
            selectedAudioLabel = uri.lastPathSegment?.substringAfterLast('/')
                ?: uri.toString()
        }
    }

    // ── captación previa stringResource(No se puede llamar directamente dentro de lambda no componible)──
    val strSuccess = stringResource(R.string.voice_cloning_success)
    val strKeySaved = stringResource(R.string.voice_cloning_key_saved)
    val strKeyFromProvider = stringResource(R.string.voice_cloning_key_from_provider)
    val strAudioRequired = stringResource(R.string.voice_cloning_audio_required)
    val strEmpty = stringResource(R.string.voice_cloning_list_empty)
    val strDeleteConfirm = stringResource(R.string.voice_cloning_delete_confirm)

    // v1.0.74 fix (Auditoría inicial 1.5): apiKey Activar solicitud de red por carácter → 500ms debounce.
    // Implementación original LaunchedEffect(selectedProvider, apiKey) Aquí apiKey es el estado de entrada en tiempo real.,
    // Cada vez que el usuario escribe un carácter, listClonedVoices,Los retrasos en la entrada y la cuota de API se queman.
    LaunchedEffect(selectedProvider) {
        snapshotFlow { apiKey.trim() }
            .debounce(500)
            .collect { key ->
                if (key.isBlank()) {
                    voices.clear()
                    return@collect
                }
                // Transmitir transparentemente apiKey a ElevenLabs provider(Los proveedores posteriores se pueden ampliar como setter)
                if (selectedProvider == "elevenlabs") {
                    elevenLabsProvider.apiKey = key
                }
                if (selectedProvider == "fish") {
                    fishProvider.apiKey = key
                    fishProvider.endpoint = settings.mediaConfigFlow.first().ttsEndpoint
                }
                isLoadingList = true
                voiceCloningService.listClonedVoices(selectedProvider)
                    .onSuccess { list ->
                        voices.clear()
                        voices.addAll(list)
                    }
                    .onError { msg, _ ->
                        MuseToast.show(msg, 3000)
                    }
                isLoadingList = false
            }
    }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.voice_cloning_title),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        // v1.132: Toda la página se puede desplazar uniformemente.(Formulario + lista de voces clonadas desplazarse juntas),
        // Ya no permite que la lista de voces se fije en un área aproximada en la parte inferior
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
        ) {
            // ── Selección de proveedor de servicios(FilterChip OK)──
            FormSection(label = stringResource(R.string.voice_cloning_provider)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    voiceCloningService.availableProviders().forEach { pid ->
                        MuseChip(
                            selected = pid == selectedProvider,
                            onClick = { selectedProvider = pid },
                            label = pid,
                        )
                    }
                }
            }

            // ── API Key entrada ──
            FormSection(label = stringResource(R.string.voice_cloning_api_key)) {
                MuseTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("xi-api-key") },
                    singleLine = true,
                    visualTransformation = if (apiKeyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        // v1.132: Reemplazar originales emoji(🙈/👁)para Material Icons,Consistente con el estilo ProviderSection
                        MuseTactileButton(
                            icon = if (apiKeyVisible) {
                                MuseIcons.eyeOff
                            } else {
                                MuseIcons.eye
                            },
                            onClick = { apiKeyVisible = !apiKeyVisible },
                            contentDescription = if (apiKeyVisible) {
                                stringResource(R.string.settings_common_hide)
                            } else {
                                stringResource(R.string.settings_common_show)
                            },
                        )
                    },
                )
            }

            // ── Clonar nueva forma de voz ──
            FormSection(label = stringResource(R.string.voice_cloning_new_voice)) {
                // Entrada de nombre de voz
                MuseTextField(
                    value = voiceName,
                    onValueChange = { voiceName = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.voice_cloning_voice_name)) },
                    singleLine = true,
                    enabled = !isCloning,
                    leadingIcon = {
                        Icon(
                            imageVector = MuseIcons.user,
                            contentDescription = null,
                            modifier = Modifier.size(MusePaddings.iconPadding * 2),
                        )
                    },
                )

                Spacer(Modifier.height(MusePaddings.contentGap))

                // Botón Seleccionar audio de muestra(Surface + clickable,No hay necesidad Material3 Button)
                Surface(
                    shape = MuseShapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isCloning) {
                            pickAudioLauncher.launch(arrayOf("audio/*"))
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    ) {
                        Icon(
                            imageVector = MuseIcons.waveSine,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(MusePaddings.iconPadding * 2),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.voice_cloning_select_audio),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (selectedAudioLabel.isNotBlank()) {
                                Text(
                                    text = selectedAudioLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(MusePaddings.contentGap))

                // botón enviar(Surface + clickable,No hay necesidad Material3 Button)
                Surface(
                    shape = MuseShapes.medium,
                    color = if (isCloning) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isCloning) {
                            if (apiKey.isBlank()) {
                                MuseToast.show(strAudioRequired, 2500)
                                return@clickable
                            }
                            if (voiceName.isBlank()) {
                                MuseToast.show(
                                    context.getString(R.string.voice_cloning_voice_name),
                                    2500,
                                )
                                return@clickable
                            }
                            val uri = selectedAudioUri
                            if (uri == null) {
                                MuseToast.show(strAudioRequired, 2500)
                                return@clickable
                            }
                            scope.launch {
                                isCloning = true
                                // Leer archivo de audio + codificación base64(Debe estar en el hilo IO)
                                val base64Result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        context.contentResolver.openInputStream(uri)?.use {
                                            Base64.encodeToString(
                                                it.readBytes(),
                                                Base64.NO_WRAP,
                                            )
                                        } ?: throw java.io.IOException(
                                            "Cannot open audio stream",
                                        )
                                    }
                                }
                                val base64 = base64Result.getOrElse { e ->
                                    isCloning = false
                                    MuseToast.show(
                                        context.getString(
                                            R.string.voice_cloning_failed,
                                            e.message ?: "read audio failed",
                                        ),
                                        3000,
                                    )
                                    return@launch
                                }

                                // Transmitir transparentemente apiKey a provider(Evitar que el usuario se active justo después de realizar cambios LaunchedEffect)
                                if (selectedProvider == "elevenlabs") {
                                    elevenLabsProvider.apiKey = apiKey.trim()
                                }
                                if (selectedProvider == "fish") {
                                    fishProvider.apiKey = apiKey.trim()
                                    fishProvider.endpoint = settings.mediaConfigFlow.first().ttsEndpoint
                                }

                                voiceCloningService.cloneVoice(
                                    providerId = selectedProvider,
                                    name = voiceName.trim(),
                                    sampleAudioBase64 = base64,
                                ).onSuccess { voiceId ->
                                    MuseToast.show(strSuccess, 2000)
                                    // B6-04: Si la clonación tiene éxito, se establecerá automáticamente como el tono TTS actual; en modo manual, cambie el
                                    // API Key almacenar juntos —— El resultado de simplemente escribir el motor y el timbre es: el timbre parece haber cambiado,
                                    // La síntesis en la nube falló directamente porque la clave estaba vacía y silenciosamente volvió al TTS del sistema.
                                    var followProvider = false
                                    runCatching {
                                        val media = settings.mediaConfigFlow.first()
                                        followProvider = media.ttsFollowProvider
                                        settings.saveMediaConfig(
                                            media.afterVoiceClone(
                                                engine = selectedProvider,
                                                voiceId = voiceId,
                                                apiKey = apiKey,
                                            ),
                                        )
                                    }.onSuccess {
                                        MuseToast.show(
                                            if (followProvider) strKeyFromProvider else strKeySaved,
                                            2500,
                                        )
                                    }
                                    // Restablecer formulario
                                    voiceName = ""
                                    selectedAudioUri = null
                                    selectedAudioLabel = ""
                                    // Actualizar lista
                                    voiceCloningService.listClonedVoices(selectedProvider)
                                        .onSuccess { list ->
                                            voices.clear()
                                            voices.addAll(list)
                                        }
                                }.onError { msg, _ ->
                                    MuseToast.show(
                                        context.getString(R.string.voice_cloning_failed, msg),
                                        3000,
                                    )
                                }
                                isCloning = false
                            }
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MusePaddings.cardInner.calculateTopPadding()),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isCloning) {
                            MuseSpinner(
                                size = MusePaddings.iconPadding * 2,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.size(MusePaddings.iconPadding))
                        } else {
                            Icon(
                                imageVector = MuseIcons.plus,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(MusePaddings.iconPadding * 2),
                            )
                            Spacer(Modifier.size(MusePaddings.iconPadding))
                        }
                        Text(
                            text = stringResource(R.string.voice_cloning_submit),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isCloning) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(MusePaddings.sectionGap))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(MusePaddings.contentGap))

            // ── Clonar lista de voces(Sigue desplazándote hacia arriba y hacia abajo,Ya no hay fondo fijo)──
            // v1.132: Utilice forEach en su lugar LazyColumn(verticalScroll No se puede anidar dentro LazyColumn),
            // Haga que la lista de voces clonadas se desplace con el formulario como toda la página
            if (isLoadingList && voices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MusePaddings.sectionGap),
                    contentAlignment = Alignment.Center,
                ) {
                    MuseSpinner(
                        size = MusePaddings.iconPadding * 3,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else if (voices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MusePaddings.sectionGap),
                    contentAlignment = Alignment.Center,
                ) {
                    // ST-03: unidad del estado vacío MuseEmptyState
                    MuseEmptyState(title = strEmpty)
                }
            } else {
                // título de la lista
                Text(
                    text = stringResource(R.string.voice_cloning_list_title),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                voices.forEach { voice ->
                    ClonedVoiceRow(
                        voice = voice,
                        isDeleting = isDeleting,
                        onDelete = { voiceToDelete = voice },
                        // v2.6.7 fix (AUDIT F21): Ajustar directamente TtsManager.speakWithClonedVoice
                        // —— Este método ya se implementó pero no se realizaron ninguna llamada.
                        onPreview = {
                            val sample = context.getString(R.string.tts_preview_sample_text)
                            runCatching {
                                ttsManager.speakWithClonedVoice(
                                    text = sample,
                                    utteranceId = "preview_${voice.voiceId}",
                                    voiceId = voice.voiceId,
                                    providerId = selectedProvider,
                                )
                            }.onFailure {
                                scope.launch {
                                    MuseToast.show(
                                        context.getString(R.string.tts_preview_failed, it.message),
                                        3000,
                                    )
                                }
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(MusePaddings.screen))
        }
    }

    // ── Eliminar ventana emergente de confirmación(MuseDialog,No hay necesidad Material3 AlertDialog)──
    voiceToDelete?.let { target ->
        MuseDialog(
            onDismissRequest = { voiceToDelete = null },
            title = stringResource(R.string.voice_cloning_title),
            content = {
                Text(
                    text = strDeleteConfirm,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.voice_cloning_submit),
            onConfirm = {
                val target0 = target
                voiceToDelete = null
                scope.launch {
                    isDeleting = true
                    if (selectedProvider == "elevenlabs") {
                        elevenLabsProvider.apiKey = apiKey.trim()
                    }
                    if (selectedProvider == "fish") {
                        fishProvider.apiKey = apiKey.trim()
                        fishProvider.endpoint = settings.mediaConfigFlow.first().ttsEndpoint
                    }
                    voiceCloningService.deleteVoice(
                        providerId = selectedProvider,
                        voiceId = target0.voiceId,
                    ).onSuccess {
                        voices.removeIf { it.voiceId == target0.voiceId }
                    }.onError { msg, _ ->
                        MuseToast.show(msg, 3000)
                    }
                    isDeleting = false
                }
            },
            destructive = true,
        )
    }
}

/**
 * Agrupación de formularios(título + contenido),Coherente con el estilo VideoGenerationPage.
 */
@Composable
private fun FormSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
        content()
    }
}

/**
 * Clonar línea de voz — Nombre + ID de voz + botón de eliminar.
 */
@Composable
@Suppress("LongMethod") // Tarjeta de una sola línea: icono/texto/Tener dos botones en el mismo lugar facilita la lectura
private fun ClonedVoiceRow(
    voice: ClonedVoice,
    isDeleting: Boolean,
    onDelete: () -> Unit,
    /** v2.6.7 fix (AUDIT F21): audición —— Después de clonar la implementación anterior, solo puede ir manualmente a la configuración de medios y cambiar ttsVoice. */
    onPreview: () -> Unit = {},
) {
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            // lado izquierdo:Person icono
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                modifier = Modifier.size(MusePaddings.iconPadding * 4),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MuseIcons.user,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(MusePaddings.iconPadding * 2),
                    )
                }
            }
            // medio:Nombre + voiceId
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = voice.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = voice.voiceId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // lado derecho:Vista previa + Eliminar (v2.6.7 fix AUDIT F21: Entrada de audición suplementaria)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier
                    .size(MusePaddings.touchTarget)
                    .clickable(enabled = !isDeleting) { onPreview() },
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MuseIcons.volume,
                        contentDescription = stringResource(R.string.tts_preview_action),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(MusePaddings.iconPadding * 2),
                    )
                }
            }
            // lado derecho:boton borrar(redondo Surface + clickable,Sin IconButton + estilo Material3 predeterminado)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                modifier = Modifier
                    .size(MusePaddings.touchTarget)
                    .clickable(enabled = !isDeleting) { onDelete() },
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MuseIcons.trash,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(MusePaddings.iconPadding * 2),
                    )
                }
            }
        }
    }
}

/**
 * La configuración de TTS se volverá a escribir después de una clonación exitosa: motor, tono y clave API en el cuadro de entrada en modo manual.
 *
 * tres cosas son una —— Sólo se escriben el motor y el timbre, y la clave no está escrita. El usuario verá "Clonación exitosa + se ha establecido como timbre actual".
 * De hecho, la síntesis en la nube ha estado recurriendo al sistema TTS porque la clave está vacía.
 *
 * Cuando "Seguir la configuración del proveedor" está activado, la clave la proporciona el proveedor y no se mueve aquí. [MediaConfig.ttsApiKey]:
 * Completar el valor de la mano solo dejará una credencial que se usará después de volver al modo manual y puede no ser válida.
 *
 * @param engine Clonar el proveedor utilizado (también es el ID del motor TTS para evitar colgar el VoiceId en otros motores)
 * @param voiceId Clonar el sonido devuelto id
 * @param apiKey Clave API ingresada en la página (escrita en modo manual, sin los espacios iniciales y finales)
 */
internal fun MediaConfig.afterVoiceClone(engine: String, voiceId: String, apiKey: String): MediaConfig = copy(
    ttsEngine = engine,
    ttsVoice = voiceId,
    ttsApiKey = if (ttsFollowProvider) ttsApiKey else apiKey.trim(),
)
