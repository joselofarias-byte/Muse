package io.zer0.muse.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** B7-07: Entrada basada en datos para el panel de herramientas plus. */
internal data class ToolEntry(
    val icon: ImageVector,
    val title: String,
    val subtitle: String? = null,
    val isActive: Boolean = false,
    val showArrow: Boolean = true,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
)

/**
 * v1.0.72: Panel de herramientas de signo más de campo de entrada(Reescritura de capa flotante).
 *
 * Diseño(de abajo hacia arriba):
 *  - área de medios superior:primera grilla = Vista previa de la cámara en vivo(Haga clic para ingresar a la cámara del sistema para tomar fotografías.),
 *    Seguido de fotos recientes en el álbum.(Miniatura horizontal).
 *  - Fila de pestaña de función:Álbumes/Documentos/Artículos(base de conocimientos) / Habilidades / Comisión / Dibujo(icono de paisaje+texto).
 *  - Lista de herramientas:Mantener el controlador de datos original entries(búsqueda en internet/pensamiento profundo/Reiniciar contexto, etc.).
 *
 * Permisos de la cámara:La vista previa requiere permiso de la CÁMARA;Cuando no esté autorizado, el icono de la cámara se mostrará en el primer cuadro.,Haga clic en Solicitar permiso.
 * tomar fotos:Haga clic en el marco de vista previa → TakePicture contract Ajustar la cámara del sistema,Después de tomar las fotografías, agrégalas para enviarlas.
 */
@Composable
internal fun MuseToolSheet(
    context: Context,
    hapticFeedback: HapticFeedback,
    onPickImage: (Boolean) -> Unit,
    onPickGalleryImage: (Uri) -> Unit,
    entries: List<ToolEntry>,
    onDismiss: () -> Unit,
) {
    MuseBottomSheet(
        onDismissRequest = onDismiss,
        maxHeightFraction = 0.78f,
        horizontalPadding = MusePaddings.screen,
        bottomContentSpacing = 0.dp,
    ) {
        Text(
            text = stringResource(R.string.chat_tools_pick_content),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        Spacer(Modifier.height(MusePaddings.contentGap))

        // ── primera linea:Vista previa de fotos + fotos de álbumes recientes ──
        val cameraPermission = android.Manifest.permission.CAMERA
        val hasCameraPermission = remember {
            ContextCompat.checkSelfPermission(context, cameraPermission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        var cameraGranted by remember { mutableStateOf(hasCameraPermission) }
        val cameraPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> cameraGranted = granted }

        val galleryPermission = remember {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
        }
        var galleryGranted by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, galleryPermission) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED,
            )
        }
        val galleryPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> galleryGranted = granted }
        var recentImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
        var selectedGalleryImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
        LaunchedEffect(galleryGranted) {
            recentImages = if (galleryGranted) {
                withContext(Dispatchers.IO) {
                    queryRecentGalleryImages(context, maxCount = 12)
                }
            } else {
                emptyList()
            }
            selectedGalleryImages = selectedGalleryImages.filter { it in recentImages }
        }

        // tomar fotos:Cámara del sistema intent(TakePicture No se requiere permiso de la CÁMARA,FileProvider guardar)
        var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
        val takePictureLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture(),
        ) { success ->
            val uri = pendingCameraUri
            if (success && uri != null) {
                onPickGalleryImage(uri)
            }
            pendingCameraUri = null
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TelegramMediaHeight)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            if (cameraGranted) {
                Box(
                    modifier = Modifier
                        .size(TelegramMediaHeight)
                        .clip(MuseShapes.extraLarge),
                ) {
                    CameraLivePreviewBox(
                        modifier = Modifier.fillMaxSize(),
                        onTap = {
                            MuseHaptics.light(hapticFeedback)
                            val file = File.createTempFile("muse_capture_", ".jpg", context.cacheDir)
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            pendingCameraUri = uri
                            takePictureLauncher.launch(uri)
                        },
                    )
                }
            } else {
                ToolMediaCard(
                    icon = MuseIcons.camera,
                    label = stringResource(R.string.chat_tool_camera),
                    modifier = Modifier.size(TelegramMediaHeight),
                    onClick = { cameraPermissionLauncher.launch(cameraPermission) },
                )
            }

            if (galleryGranted && recentImages.isNotEmpty()) {
                recentImages.forEach { uri ->
                    GalleryThumbnail(
                        uri = uri,
                        selectionIndex = selectedGalleryImages.indexOf(uri),
                        onClick = {
                            selectedGalleryImages = if (uri in selectedGalleryImages) {
                                selectedGalleryImages - uri
                            } else if (selectedGalleryImages.size < MAX_GALLERY_SELECTION) {
                                selectedGalleryImages + uri
                            } else {
                                selectedGalleryImages
                            }
                        },
                    )
                }
            } else if (!galleryGranted) {
                ToolMediaCard(
                    icon = MuseIcons.image,
                    label = stringResource(R.string.chat_authorize_gallery),
                    modifier = Modifier.size(TelegramMediaHeight),
                    onClick = { galleryPermissionLauncher.launch(galleryPermission) },
                )
            }

            // Mantener la entrada completa del álbum del sistema,También puede seleccionar medios directamente cuando no hay imágenes recientes.
            ToolMediaCard(
                icon = MuseIcons.image,
                label = stringResource(R.string.chat_tool_photo),
                modifier = Modifier.size(TelegramMediaHeight),
                onClick = {
                    MuseHaptics.light(hapticFeedback)
                    onPickImage(false)
                },
            )
        }

        if (selectedGalleryImages.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.moment_images_selected, selectedGalleryImages.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(MusePaddings.contentGap))
                MuseCapsuleButton(
                    text = stringResource(R.string.action_send),
                    onClick = {
                        selectedGalleryImages.forEach(onPickGalleryImage)
                        selectedGalleryImages = emptyList()
                        onDismiss()
                    },
                    variant = IosCapsuleButtonVariant.Secondary,
                    leadingIcon = MuseIcons.send,
                    fillWidth = false,
                )
            }
        }

        // v2.x: Nuevo estándar para la página de configuración de alineación del espaciado de particiones dentro de los paneles(cardGap 20)—— Área de medios/Interruptores de herramientas/Accesorios
        // Dividir el mismo nivel entre tres particiones."espaciado de grupos",ContentGap todavía se usa dentro del grupo para mantenerlo compacto.
        Spacer(Modifier.height(MusePaddings.cardGap))

        // ── segunda linea:Búsqueda en Internet + pensamiento profundo ──
        val findEntry: (String) -> ToolEntry? = { keyword ->
            entries.firstOrNull { it.title.contains(keyword) || keyword in it.title }
        }
        val webSearchEntry = findEntry("Redes")
        val deepThinkingEntry = findEntry("pensamiento profundo")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            webSearchEntry?.let { entry ->
                QuickAttachTab(
                    icon = entry.icon,
                    label = entry.title,
                    isActive = entry.isActive,
                    onClick = entry.onClick,
                    compact = true,
                    dense = true,
                    modifier = Modifier.weight(1f),
                )
            }
            deepThinkingEntry?.let { entry ->
                QuickAttachTab(
                    icon = entry.icon,
                    label = entry.title,
                    isActive = entry.isActive,
                    onClick = entry.onClick,
                    onLongClick = entry.onLongClick,
                    compact = true,
                    dense = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(MusePaddings.cardGap))

        // ── la tercera linea:Álbumes, archivos adjuntos, citas y otros elementos diversos, manténgalos pequeños y desplácese hacia los lados ──
        val miscEntries = buildList {
            add(
                QuickAttachEntry(
                    icon = MuseIcons.image,
                    label = stringResource(R.string.chat_gallery_cd),
                    onClick = {
                        MuseHaptics.light(hapticFeedback)
                        onPickImage(false)
                    },
                ),
            )
            entries.firstOrNull { it.title.contains("Accesorios") || it.title.contains("Documentación") }?.let { entry ->
                add(QuickAttachEntry(entry.icon, entry.title, onClick = entry.onClick))
            }
            entries.firstOrNull { it.title.contains("base de conocimientos") }?.let { entry ->
                add(QuickAttachEntry(entry.icon, entry.title, onClick = entry.onClick))
            }
            // La tercera capa alberga todo el resto del material: palabras clave, habilidades, dibujos, delegados, contextos de reinicio, etc.
            // Excluya únicamente los dos interruptores centrales en el segundo nivel y las entradas que se han colocado en esta fila para evitar duplicaciones.
            val usedTitles = map { it.label }.toSet()
            entries
                .filter { it.title !in usedTitles }
                .filter { it.title != webSearchEntry?.title && it.title != deepThinkingEntry?.title }
                .forEach { entry ->
                    add(
                        QuickAttachEntry(
                            icon = entry.icon,
                            label = entry.title,
                            isActive = entry.isActive,
                            onClick = entry.onClick,
                            onLongClick = entry.onLongClick,
                        ),
                    )
                }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            miscEntries.forEach { entry ->
                QuickAttachTab(
                    icon = entry.icon,
                    label = entry.label,
                    isActive = entry.isActive,
                    onClick = entry.onClick,
                    onLongClick = entry.onLongClick,
                    compact = true,
                    modifier = Modifier
                        .wrapContentWidth()
                        .widthIn(min = 82.dp),
                )
            }
        }
    }
}

/** v1.0.72: Entrada de fila de la pestaña de función(Con activación opcional + pulsación larga). */
private data class QuickAttachEntry(
    val icon: ImageVector,
    val label: String,
    val isActive: Boolean = false,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
)

/** v1.0.72: Función tab(Icono redondo + texto independiente debajo,no truncar). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickAttachTab(
    icon: ImageVector,
    label: String,
    isActive: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    compact: Boolean = false,
    dense: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // v2.x: Efectos de interacción reelaborados — Presione para comprimir y expandir + transición elástica de color(Comentarios de autodibujo,Ninguno ripple)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // v2.x: reduced-motion Rebajar — sistema"Desactivar animación"Utilice el ajuste para cortar recto,no es una primavera
    val reducedMotion = MuseMotion.isReducedMotion()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            )
        },
        label = "quickTabPress",
    )
    val containerColor by animateColorAsState(
        targetValue = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
        animationSpec = MuseMotion.tween(MuseAnimation.TACTILE_MS),
        label = "quickTabContainer",
    )
    val circleColor by animateColorAsState(
        targetValue = if (isActive) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
        },
        animationSpec = MuseMotion.tween(MuseAnimation.TACTILE_MS),
        label = "quickTabCircle",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isActive) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = MuseMotion.tween(MuseAnimation.TACTILE_MS),
        label = "quickTabContent",
    )
    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .heightIn(
                min = when {
                    dense -> 64.dp
                    compact -> 48.dp
                    else -> 72.dp
                },
            )
            .clip(if (compact) MuseShapes.pill else MuseShapes.large)
            // v2.x: fondo antes de hacer clic;Presione comentarios para hacer zoom+color(autopintado,Consistente con la decisión global de reducción de la onda expansiva)
            .background(containerColor)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                },
            )
            .padding(
                horizontal = when {
                    dense -> MusePaddings.tightGap
                    compact -> MusePaddings.itemGap
                    else -> MusePaddings.contentGap
                },
                vertical = when {
                    dense -> MusePaddings.tinyGap
                    compact -> MusePaddings.tinyGap
                    else -> MusePaddings.tightGap
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(
            if (dense) MusePaddings.tightGap else MusePaddings.contentGap,
        ),
    ) {
        Surface(
            shape = CircleShape,
            color = circleColor,
            modifier = Modifier.size(
                when {
                    dense -> 36.dp
                    compact -> 32.dp
                    else -> 48.dp
                },
            ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = contentColor,
                    modifier = Modifier.size(
                        when {
                            dense -> MuseIconSizes.iconSmall
                            compact -> MuseIconSizes.iconSmall
                            else -> MuseIconSizes.icon
                        },
                    ),
                )
            }
        }
        Text(
            text = label,
            style = if (compact || dense) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
            color = contentColor,
            maxLines = if (compact && !dense) 1 else 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = if (compact && !dense) Modifier else Modifier.weight(1f),
        )
        if (isActive && !compact) {
            Icon(
                imageVector = MuseIcons.check,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
        } else if (!compact && onLongClick != null) {
            Icon(
                imageVector = MuseIcons.chevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
        }
    }
}

/** Miniaturas de imágenes de álbumes recientes,Admite múltiples selecciones y muestra el orden de selección. */
@Composable
private fun GalleryThumbnail(uri: Uri, selectionIndex: Int, onClick: () -> Unit) {
    val shape = MuseShapes.extraLarge
    Box(
        modifier = Modifier
            .size(TelegramMediaHeight)
            .clip(shape)
            .then(
                if (selectionIndex >= 0) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
    ) {
        SmartImage(
            model = uri,
            contentDescription = stringResource(R.string.chat_gallery_image_cd),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (selectionIndex >= 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)),
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(MusePaddings.tinyGap)
                    .size(26.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = (selectionIndex + 1).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * v1.0.72: Vista previa de la cámara en vivo(CameraX).
 *
 * enlace del ciclo de vida LocalLifecycleOwner,Vista previa montada en PreviewView;
 * Haga clic en el marco de vista previa para activar [onTap](Inicie la cámara del sistema para tomar fotografías de la persona que llama).
 */
@Composable
private fun CameraLivePreviewBox(modifier: Modifier, onTap: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnTap by rememberUpdatedState(onTap)
    // tiene referencia del proveedor;Grupo de hojas de panel(onRelease)Desvincular cuando,Evitar que la cámara se cierre después del menú más
    // Aún vinculado a actividad REANUDA(La luz indicadora está siempre encendida./Consumo de energía/Bloquear otras aplicaciones de cámara).
    val cameraProviderHolder = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                setOnClickListener { currentOnTap() }
            }
            runCatching {
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    runCatching {
                        val provider = providerFuture.get()
                        cameraProviderHolder[0] = provider
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                        )
                    }.onFailure { e ->
                        Logger.w("MuseToolSheet", "Error al vincular la vista previa de la cámara", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))
            }.onFailure { e ->
                Logger.w("MuseToolSheet", "Falló la inicialización de la vista previa de la cámara", e)
            }
            previewView
        },
        onRelease = {
            cameraProviderHolder[0]?.unbindAll()
            cameraProviderHolder[0] = null
        },
        modifier = modifier,
    )
}

/** B7-07: Tarjeta de acceso directo a medios en el menú Herramientas. */
@Composable
private fun ToolMediaCard(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(MuseIconSizes.iconLarge),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(MusePaddings.auxGap))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** B7-07: Fila de lista en el menú Herramientas. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolListRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    isActive: Boolean = false,
    showArrow: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            )
            .padding(vertical = MusePaddings.listRowVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.screen),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(MuseIconSizes.icon),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(MusePaddings.tinyGap))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        if (isActive) {
            Icon(
                imageVector = MuseIcons.check,
                contentDescription = null,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        } else if (showArrow) {
            Icon(
                imageVector = MuseIcons.chevronRight,
                contentDescription = null,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** v1.0.72: altura del área de medios(cámara/Portal de fotos tamaño uniforme). */
private val TelegramMediaHeight = 128.dp

private const val MAX_GALLERY_SELECTION = 4

/** Consulta las últimas imágenes en el álbum del sistema. El panel se volverá a leer cada vez que se abra.,Garantizado para mostrar el contenido más reciente. */
private fun queryRecentGalleryImages(context: Context, maxCount: Int): List<Uri> {
    return runCatching {
        val uris = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext() && uris.size < maxCount) {
                uris += ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(idColumn),
                )
            }
        }
        uris
    }.onFailure { e -> Logger.w("MuseToolSheet", "No se pudo consultar el álbum", e) }.getOrDefault(emptyList())
}
