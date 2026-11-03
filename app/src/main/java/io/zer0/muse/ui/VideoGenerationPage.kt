package io.zer0.muse.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.video.VideoGenRequest
import io.zer0.ai.video.VideoGenerationService
import io.zer0.ai.video.VideoTaskStatus
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseIndeterminateProgressBar
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.ByteArrayOutputStream

/**
 * VideoTaskStatus enumerado Saver(rememberSaveable No se puede guardar la enumeración directamente,Serializar con nombre/Deserialización).
 */
private val VideoTaskStatusSaver = Saver<VideoTaskStatus?, String>(
    save = { it?.name ?: "" },
    restore = { name ->
        if (name.isBlank()) {
            null
        } else {
            runCatching { VideoTaskStatus.valueOf(name) }.getOrNull()
        }
    },
)

/**
 * P2-8: Página de generación de vídeo — Página de herramientas en pantalla completa.
 *
 * Diseño:
 *  - MuseTopBar:Volver + Título "Generación de Vídeo"
 *  - área de formulario(desplazable):
 *    - Prompt Cuadro de entrada(múltiples líneas)
 *    - Selección de modelo(ke ling v1 / v2,MuseCapsuleTab estilo)
 *    - Selección de duración(5s / 10s)
 *    - resolución(720p / 1080p)
 *    - Imagen de referencia(Seleccionar del álbum de fotos local,Opcional;Déjelo en blanco y vaya a Vincent Video)
 *    - API Key Cuadro de entrada(modo contraseña)
 *  - Botón "Generar vídeo"(Surface + clickable,No hay necesidad Material3 Button)
 *  - Área de estado de la tarea:Barra de progreso + texto de estado
 *  - Después de la finalización:Tarjeta URL del vídeo + botón "Abrir vídeo"(Intent.ACTION_VIEW)
 *
 * ficha de diseño:MuseShapes / MusePaddings,No utiliza el botón predeterminado Material3.
 */
@Composable
fun VideoGenerationPage(
    onBack: () -> Unit,
    videoService: VideoGenerationService = koinInject(),
    settingsRepo: SettingsRepository = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // v1.132: Lea todos los proveedores habilitados desde SettingsRepository,Permitir que los usuarios elijan
    // (No más codificación del cuadro de entrada de la clave API de Kling,Después de que el usuario agrega un proveedor en el menú de proveedores, puede seleccionarlo aquí)
    val providersState by settingsRepo.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val enabledProviders = remember(providersState) {
        providersState.filter { it.enabled && it.apiKey.isNotBlank() }
    }

    // estado del formulario(rememberSaveable Rotación garantizada/Conservar después de cambios de configuración)
    var prompt by rememberSaveable { mutableStateOf("") }
    var selectedModel by rememberSaveable { mutableStateOf("") }
    var duration by rememberSaveable { mutableStateOf(5) }
    var resolution by rememberSaveable { mutableStateOf("720p") }
    // v1.143: Cambiar la imagen de referencia a la selección de archivos locales,Lo que se salva es data URI(data:image/jpeg;base64,...)
    var referenceImageUri by rememberSaveable { mutableStateOf("") }
    // v1.132: proveedor seleccionado ID(Seleccionar entre los proveedores configurados por el usuario),Reemplazar la entrada apiKey original
    var selectedProviderId by rememberSaveable { mutableStateOf("") }
    // Proveedor actualmente seleccionado(estado derivado)
    val selectedProvider: ProviderConfig? = remember(enabledProviders, selectedProviderId) {
        enabledProviders.firstOrNull { it.id == selectedProviderId }
            ?: enabledProviders.firstOrNull()
    }
    // Seleccionar automáticamente el primer proveedor(Al ingresar por primera vez o cuando se elimina el proveedor seleccionado)
    LaunchedEffect(enabledProviders) {
        if (selectedProviderId.isBlank() && enabledProviders.isNotEmpty()) {
            selectedProviderId = enabledProviders.first().id
        } else if (selectedProviderId.isNotBlank() &&
            enabledProviders.none { it.id == selectedProviderId }
        ) {
            selectedProviderId = enabledProviders.firstOrNull()?.id ?: ""
        }
    }

    // Lista de modelos que admiten salida de video según el proveedor actual
    val videoModels = remember(selectedProvider) {
        selectedProvider?.models?.filter { it.supportsVideoOutput() } ?: emptyList()
    }
    // Selecciona automáticamente el primer modelo de vídeo.
    LaunchedEffect(videoModels) {
        if (selectedModel.isBlank() && videoModels.isNotEmpty()) {
            selectedModel = videoModels.first().id
        } else if (selectedModel.isNotBlank() && videoModels.none { it.id == selectedModel }) {
            selectedModel = videoModels.firstOrNull()?.id ?: ""
        }
    }

    // Estado de la tarea
    var isGenerating by rememberSaveable { mutableStateOf(false) }
    var taskStatus by rememberSaveable(stateSaver = VideoTaskStatusSaver) { mutableStateOf<VideoTaskStatus?>(null) }
    var statusMessage by rememberSaveable { mutableStateOf("") }
    var videoUrl by rememberSaveable { mutableStateOf("") }
    var errorMessage by rememberSaveable { mutableStateOf("") }

    // 9.5 Reparación: recuerdeSaveable se restaurará después de que se reconstruya el proceso. isGenerating=true,Pero el contexto de la tarea se pierde.,
    // En este punto, la carga nunca desaparece. Verifique y reinicie al ingresar a la página por primera vez.
    LaunchedEffect(Unit) {
        if (isGenerating) {
            isGenerating = false
        }
    }

    // v1.143: Selector de imágenes locales,Después de la selección, comprímalo en URI de datos como imagen de referencia.
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let { pickedUri ->
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        compressVideoReferenceImageToDataUri(uri = pickedUri, context = context)
                    }
                }.onSuccess { result ->
                    referenceImageUri = result.dataUri
                    MuseToast.show(
                        context.getString(R.string.video_gen_image_compressed, result.describe()),
                        2500,
                    )
                }.onFailure { e ->
                    MuseToast.show(context.getString(R.string.video_gen_image_load_failed, e.message ?: ""))
                }
            }
        }
    }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.video_gen_title),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            // ── Prompt entrada ──
            FormSection(label = stringResource(R.string.video_gen_prompt)) {
                MuseTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    placeholder = { Text(stringResource(R.string.video_gen_prompt)) },
                    enabled = !isGenerating,
                    maxLines = 6,
                )
            }

            // ── Selección de modelo(Dinámico:Filtre modelos de proveedores actuales que admitan salida de video)──
            FormSection(label = stringResource(R.string.video_gen_model)) {
                if (videoModels.isEmpty()) {
                    Surface(
                        shape = MuseShapes.semiLarge,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Actualmente no hay modelos del proveedor que admitan salida de vídeo. Por favor vaya a \"Configuración\"→Habilite la capacidad \"Salida de video\" para el modelo en \"Modelos y servicios\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(MusePaddings.cardInner),
                        )
                    }
                } else {
                    Surface(
                        shape = MuseShapes.semiLarge,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(MusePaddings.tightGap),
                            verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                        ) {
                            videoModels.forEach { model ->
                                val isSelected = model.id == selectedModel
                                Surface(
                                    shape = MuseShapes.medium,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !isGenerating) {
                                            selectedModel = model.id
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(MusePaddings.cardInner),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = model.name,
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontWeight = FontWeight.SemiBold,
                                            ),
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Selección de duración ──
            FormSection(label = stringResource(R.string.video_gen_duration)) {
                SegmentedOptions(
                    options = listOf(5 to "5s", 10 to "10s"),
                    selected = duration,
                    onSelect = { duration = it },
                    enabled = !isGenerating,
                )
            }

            // ── Selección de resolución ──
            FormSection(label = stringResource(R.string.video_gen_resolution)) {
                SegmentedOptions(
                    options = listOf("720p" to "720p", "1080p" to "1080p"),
                    selected = resolution,
                    onSelect = { resolution = it },
                    enabled = !isGenerating,
                )
            }

            // ── Imagen de referencia(archivo local,Opcional)──
            FormSection(label = stringResource(R.string.video_gen_reference_image)) {
                if (referenceImageUri.isBlank()) {
                    Surface(
                        shape = MuseShapes.semiLarge,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isGenerating) {
                                imagePicker.launch("image/*")
                            },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MusePaddings.cardInner),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = MuseIcons.image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(MusePaddings.iconPadding * 2),
                            )
                            Spacer(Modifier.size(MusePaddings.iconPadding))
                            Text(
                                text = stringResource(R.string.video_gen_select_image),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .clip(MuseShapes.small),
                    ) {
                        SmartImage(
                            model = referenceImageUri,
                            contentDescription = stringResource(R.string.video_gen_reference_image),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        MuseTactileButton(
                            icon = MuseIcons.x,
                            onClick = { referenceImageUri = "" },
                            contentDescription = stringResource(R.string.video_gen_clear_image),
                            tint = MaterialTheme.colorScheme.onSurface,
                            size = MuseIconSizes.touchTarget,
                            iconSize = 16.dp,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .background(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }

            // ── Selección de proveedores(v1.132: Leer desde el repositorio de configuración,No más entradas de clave API codificadas)──
            FormSection(label = stringResource(R.string.video_gen_provider)) {
                if (enabledProviders.isEmpty()) {
                    // Cuando no hay ningún proveedor disponible, se le solicita al usuario que vaya al menú de proveedores para agregarlo primero.
                    Surface(
                        shape = MuseShapes.semiLarge,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.video_gen_no_provider),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(MusePaddings.cardInner),
                        )
                    }
                } else {
                    // Lista de proveedores(SegmentedOptions,elección única)
                    // Renderizado con Superficie+lista en la que se puede hacer clic,Seleccionar resaltado primary
                    Surface(
                        shape = MuseShapes.semiLarge,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(MusePaddings.tightGap),
                            verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                        ) {
                            enabledProviders.forEach { provider ->
                                val isSelected = provider.id == selectedProvider?.id
                                Surface(
                                    shape = MuseShapes.medium,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !isGenerating) {
                                            selectedProviderId = provider.id
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(MusePaddings.cardInner),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = provider.displayName,
                                                style = MaterialTheme.typography.bodyLarge.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                ),
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.onPrimary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                            )
                                            if (provider.baseUrl.isNotBlank()) {
                                                Text(
                                                    text = provider.baseUrl,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (isSelected) {
                                                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    },
                                                )
                                            }
                                        }
                                        // API Key Vista previa de los 4 últimos(Informar al usuario que ha sido configurado. key)
                                        Text(
                                            text = "••••${provider.apiKey.takeLast(4)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Botón generar vídeo(Surface + clickable,No hay necesidad Material3 Button)──
            Surface(
                shape = MuseShapes.medium,
                color = if (isGenerating || selectedProvider == null || videoModels.isEmpty()) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isGenerating && selectedProvider != null && videoModels.isNotEmpty()) {
                        if (prompt.isBlank()) {
                            MuseToast.show(context.getString(R.string.video_gen_prompt))
                            return@clickable
                        }
                        val provider = selectedProvider ?: return@clickable
                        scope.launch {
                            isGenerating = true
                            taskStatus = VideoTaskStatus.PENDING
                            statusMessage = context.getString(R.string.video_gen_pending)
                            errorMessage = ""
                            videoUrl = ""

                            val request = VideoGenRequest(
                                prompt = prompt,
                                model = selectedModel,
                                referenceImages = referenceImageUri.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList(),
                                duration = duration,
                                resolution = resolution,
                            )
                            // v1.137: Enrutamiento por specId/host a través de VideoProviderRegistry,
                            // No más coincidencias difíciles por ID de proveedor(Reparación preset_kling ≠ kling enrutamiento bug)
                            val result = videoService.generateVideo(provider, request)
                            isGenerating = false
                            result.onSuccess { url ->
                                taskStatus = VideoTaskStatus.SUCCESS
                                statusMessage = context.getString(R.string.video_gen_success)
                                videoUrl = url
                                MuseToast.show(context.getString(R.string.video_gen_success))
                            }.onFailure { e ->
                                taskStatus = VideoTaskStatus.FAILED
                                val msg = e.message ?: "unknown error"
                                errorMessage = msg
                                statusMessage = context.getString(R.string.video_gen_failed, msg)
                                MuseToast.show(statusMessage)
                            }
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
                    if (isGenerating) {
                        MuseSpinner(
                            size = MusePaddings.iconPadding,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.size(MusePaddings.iconPadding))
                    } else {
                        Icon(
                            imageVector = MuseIcons.movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(MusePaddings.iconPadding * 2),
                        )
                        Spacer(Modifier.size(MusePaddings.iconPadding))
                    }
                    Text(
                        text = stringResource(R.string.video_gen_submit),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isGenerating) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        },
                    )
                }
            }

            // ── Área de estado de la tarea ──
            if (isGenerating || taskStatus != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                StatusSection(
                    status = taskStatus,
                    statusMessage = statusMessage,
                    isGenerating = isGenerating,
                )
            }

            // ── Vista previa del vídeo una vez finalizado ──
            if (videoUrl.isNotBlank() && taskStatus == VideoTaskStatus.SUCCESS) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                VideoResultCard(
                    videoUrl = videoUrl,
                    onOpen = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }
                            .onFailure {
                                MuseToast.show(context.getString(R.string.video_gen_no_app))
                            }
                    },
                )
            }

            // Deja la parte inferior en blanco
            Spacer(Modifier.height(MusePaddings.sectionGap))
        }
    }
}

/**
 * Agrupación de formularios(título + contenido).
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
 * selector de segmento(Logre un estilo cápsula con Surface + en el que se puede hacer clic,Misma visión que MuseCapsuleTab;
 * Uno más que MuseCapsuleTab `enabled` Discapacitado,Por lo tanto, se mantiene la implementación privada de esta página.).
 */
@Composable
private fun <T> SegmentedOptions(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, enabled: Boolean = true) {
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.tightGap),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                Surface(
                    shape = MuseShapes.small,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = enabled) { onSelect(value) },
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(vertical = MusePaddings.contentGap),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * Área de estado de la tarea(Barra de progreso + texto de estado).
 */
@Composable
private fun StatusSection(status: VideoTaskStatus?, statusMessage: String, isGenerating: Boolean) {
    // captación previa stringResource(ifBlank lambda no @Composable,no se puede llamar directamente)
    val pendingText = stringResource(R.string.video_gen_pending)
    val processingText = stringResource(R.string.video_gen_processing)
    val successText = stringResource(R.string.video_gen_success)
    val failedText = stringResource(R.string.video_gen_failed, "")
    val fallbackText = when (status) {
        VideoTaskStatus.PENDING -> pendingText
        VideoTaskStatus.PROCESSING -> processingText
        VideoTaskStatus.SUCCESS -> successText
        VideoTaskStatus.FAILED -> failedText
        null -> ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isGenerating) {
                MuseSpinner(
                    size = MusePaddings.iconPadding * 2,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(MusePaddings.iconPadding))
            }
            Text(
                text = statusMessage.ifBlank { fallbackText },
                style = MaterialTheme.typography.bodyMedium,
                color = when (status) {
                    VideoTaskStatus.FAILED -> MaterialTheme.colorScheme.error
                    VideoTaskStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }

        if (isGenerating) {
            MuseIndeterminateProgressBar(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

/**
 * Tarjeta de resultados de vídeo — Mostrar URL del vídeo + botón "Abrir vídeo".
 */
@Composable
private fun VideoResultCard(videoUrl: String, onOpen: () -> Unit) {
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Text(
                text = stringResource(R.string.video_gen_success),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = videoUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Surface(
                shape = MuseShapes.medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen() },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MusePaddings.contentGap),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = MuseIcons.externalLink,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(MusePaddings.iconPadding * 2),
                    )
                    Spacer(Modifier.size(MusePaddings.iconPadding))
                    Text(
                        text = stringResource(R.string.video_gen_open_video),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// Compresión de imágenes/herramientas de codificación(Para uso exclusivo con VideoGenerationPage,Consistente con la lógica de InputBar)
// ═════════════════════════════════════════════════════════════════════════════

/**
 * v1.143: Comprimir el URI de la imagen de referencia seleccionado por el usuario para que se ajuste al tamaño/URI de datos para la restricción de tamaño.
 *
 * Flujo de procesamiento:
 *  1. Primero decodifica los límites para obtener el tamaño original.
 *  2. calcular inSampleSize,encoger el lado largo para [maxSide] cerca(2 reducción de resolución a la potencia de)
 *  3. Después de decodificar para obtener el mapa de bits.,Mientras el lado esté quieto > [maxSide],Escalado preciso con Matrix
 *  4. JPEG Calidad de compresión [quality],base64 Si aún excede [maxBase64Bytes],regla
 *     Reducir gradualmente la calidad/tamaño,Hasta que se cumpla la restricción de volumen o se reduzca el límite inferior
 *
 * tirar [IllegalStateException] Indica falla de compresión(La imagen original no se puede decodificar o aún es demasiado grande después de la compresión).
 */
private fun compressVideoReferenceImageToDataUri(
    uri: Uri,
    context: android.content.Context,
    maxSide: Int = 1024,
    quality: Int = 85,
    maxBase64Bytes: Int = 4 * 1024 * 1024,
): VideoReferenceImage {
    val resolver = context.contentResolver

    // 1. Decodificar tamaño original
    val (origW, origH) = decodeVideoImageBounds(resolver, uri)
    if (origW <= 0 || origH <= 0) {
        error("decode bounds failed for $uri")
    }

    // 2. calcular inSampleSize(2 al poder de,Haga que el lado largo después de reducir la resolución esté lo más cerca posible de maxSide, pero no más de 2 veces)
    var sample = 1
    while (origW / sample / 2 >= maxSide || origH / sample / 2 >= maxSide) sample *= 2

    // 3. decodificado como Bitmap(Después de reducir la resolución)
    var bitmap = decodeVideoSampledBitmap(resolver, uri, sample)
        ?: error("decode bitmap failed for $uri")

    // 4. Escale exactamente dentro de maxSide(Mantener relación de aspecto)
    val scaled = scaleVideoBitmapToMaxSide(bitmap, maxSide)
    if (scaled !== bitmap) {
        bitmap.recycle()
        bitmap = scaled
    }

    // 5. Compresión paso a paso,Hasta que el volumen base64 cumpla con las restricciones o caiga al límite inferior
    var currentQuality = quality
    var currentBmp = bitmap
    var bytes = compressVideoJpeg(currentBmp, currentQuality)
    var base64Len = videoBase64Length(bytes.size)

    // 5.1 Primero intenta solo reducir la calidad.(75 → 65 → 55)
    val qualitySteps = listOf(75, 65, 55)
    var stepIndex = 0
    while (base64Len > maxBase64Bytes && stepIndex < qualitySteps.size) {
        currentQuality = qualitySteps[stepIndex++]
        bytes = compressVideoJpeg(currentBmp, currentQuality)
        base64Len = videoBase64Length(bytes.size)
    }

    // 5.2 Si todavía está por encima del límite, reduzca el tamaño.(768 → 512 → 384)
    val sideSteps = listOf(768, 512, 384)
    var sideIndex = 0
    while (base64Len > maxBase64Bytes && sideIndex < sideSteps.size) {
        val newSide = sideSteps[sideIndex++]
        val shrunk = scaleVideoBitmapToMaxSide(currentBmp, newSide)
        if (shrunk !== currentBmp) {
            currentBmp.recycle()
            currentBmp = shrunk
        }
        bytes = compressVideoJpeg(currentBmp, currentQuality)
        base64Len = videoBase64Length(bytes.size)
    }

    val width = currentBmp.width
    val height = currentBmp.height
    currentBmp.recycle()

    if (base64Len > maxBase64Bytes) {
        // Aún por encima del límite:Negarse a subir,Evite OOM/tiempos de espera
        error("image still too large after compression (${width}x$height, ${bytes.size / 1024}KB)")
    }

    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    val dataUri = "data:image/jpeg;base64,$base64"
    return VideoReferenceImage(
        dataUri = dataUri,
        width = width,
        height = height,
        byteCount = bytes.size,
    )
}

/**
 * Resultados de compresión de imágenes de referencia.
 *
 * @property dataUri en forma de `data:image/jpeg;base64,...` de Data URI,Se puede entregar directamente a VideoProvider
 * @property width ancho después de la compresión
 * @property height Altura después de la compresión
 * @property byteCount Bytes JPEG comprimidos(todavía no base64)
 */
private data class VideoReferenceImage(
    val dataUri: String,
    val width: Int,
    val height: Int,
    val byteCount: Int,
) {
    /** tamaño legible por humanos/Descripción del volumen,Se utiliza para indicaciones de brindis. */
    fun describe(): String {
        val kb = byteCount / 1024
        return "${width}x$height, ${kb}KB"
    }
}

/** Decodificar los límites de la imagen original(ancho y alto),No carga píxeles en la memoria. */
private fun decodeVideoImageBounds(resolver: android.content.ContentResolver, uri: Uri): Pair<Int, Int> {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, opts)
    }
    return opts.outWidth to opts.outHeight
}

/** Decodificar mapa de bits mediante inSampleSize. */
private fun decodeVideoSampledBitmap(resolver: android.content.ContentResolver, uri: Uri, sampleSize: Int): Bitmap? {
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, opts)
    }
}

/** Escale el mapa de bits proporcionalmente al lado largo <= maxSide;Si está satisfecho, devuélvalo tal cual. */
private fun scaleVideoBitmapToMaxSide(src: Bitmap, maxSide: Int): Bitmap {
    val w = src.width
    val h = src.height
    val longSide = maxOf(w, h)
    if (longSide <= maxSide) return src
    val scale = maxSide.toFloat() / longSide
    val newW = (w * scale).toInt().coerceAtLeast(1)
    val newH = (h * scale).toInt().coerceAtLeast(1)
    val matrix = Matrix().apply { setScale(scale, scale) }
    return Bitmap.createBitmap(src, 0, 0, w, h, matrix, true)
}

/** JPEG Comprimido en una matriz de bytes. */
private fun compressVideoJpeg(bmp: Bitmap, quality: Int): ByteArray {
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}

/** base64 El volumen después de la codificación es aproximadamente los bytes originales. * 4/3,Redondeo. */
private fun videoBase64Length(byteCount: Int): Int {
    // cada 3 bytes → 4 personaje;Si es menos de 3, cuente como 3.NO_WRAP No se agregan nuevas líneas.
    return ((byteCount + 2) / 3) * 4
}
