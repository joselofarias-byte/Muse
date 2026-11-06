package io.zer0.muse.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.zer0.muse.R
import io.zer0.muse.data.STICKER_FREQ_FREQUENT
import io.zer0.muse.data.STICKER_FREQ_NORMAL
import io.zer0.muse.data.STICKER_FREQ_OCCASIONALLY
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.sticker.StickerItem
import io.zer0.muse.data.sticker.StickerLibraryRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.FullScreenMediaViewer
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.compose.foundation.lazy.items as lazyItems

/**
 * v1.95: Área de gestión de biblioteca de paquetes de expresión — Habilitar interruptor + probabilidad de envío + importar zip + filtro de categoría + cuadrícula de vista previa.
 *
 * La capa exterior es LazyColumn,Usado aquí FlowRow(en vez de LazyVerticalGrid)Cuadrícula de vista previa del diseño,
 * Evite conflictos de desplazamiento anidados. El almacenamiento de la biblioteca de emojis es independiente de Room,Pase [StickerLibraryRepository]
 * Leer y escribir en el directorio filesDir/stickers.
 *
 * interacción:
 *  - interruptor de habilitación:Controla si la IA puede enviar emoticonos al responder
 *  - probabilidad de envío:0-100 control deslizante continuo,AI Esta probabilidad se llama cada vez que respondes. send_sticker
 *  - Importar zip: seleccionar vía SAF zip,Ordenar automáticamente por estructura de carpetas
 *  - Cápsulas clasificadas:El primero es"Todos",El resto son varias categorías.;Haga clic para alternar el filtro
 *  - Vista previa de la cuadrícula:80x80dp miniatura,Mantenga presionado para que aparezca el cuadro de diálogo de confirmación de eliminación
 *  - Estado vacío:Icono gris + título + subtítulo
 *
 * @param settings Configurar almacén(leer y escribir sticker_enabled / sticker_frequency)
 * @param scope alcance de rutina(Iniciar importación/Eliminar/Guardar operación)
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun StickerLibrarySection(settings: SettingsRepository, scope: CoroutineScope) {
    val context = LocalContext.current
    val localizedResources = androidx.compose.ui.platform.LocalResources.current
    // v2.6.2: Copia del mensaje de análisis previo (use plantilla + reemplace con parámetros)—— Úselo directamente en la devolución de llamada lambda
    // context.getString Se activará lint LocalContextGetResourceValueCall.
    //
    // v2.6.3 reparar(issue #9 / ajustes→El chat colapsará):
    // Debe usarse aquí**sin ginseng** `stringResource(id)`. La versión interna con parámetros es
    // Resources.getString(id, args),será ejecutado inmediatamente String.format,
    // poner cuerda "%1$d" Rellenar %1$d ranura entera →
    // IllegalFormatConversionException: d != java.lang.String.
    // La versión sin parámetros no requiere formato.,Lo que se devuelve es el %1$d texto de plantilla original,
    // abajo .replace("%1\$d", ...) Trabaja como siempre,Y la versión sin parámetros no generará esa pelusa.
    val stickerDeletedFormat = androidx.compose.ui.res.stringResource(R.string.settings_sticker_deleted_toast)
    val stickerClearedFormat = androidx.compose.ui.res.stringResource(R.string.settings_sticker_cleared_toast)
    val stickerRepo: StickerLibraryRepository = koinInject()
    val stickerEnabled by settings.stickerEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    // v2.x: Enviar rango de frecuencia(Marcar enlace;Reemplazar la probabilidad de envío anterior)
    val stickerFrequency by settings.stickerFrequencyFlow.collectAsStateWithLifecycle(initialValue = STICKER_FREQ_NORMAL)

    // disparador de actualización:importar/Aumentar después de la eliminación para recargar categorías y listas
    var refreshTrigger by remember { mutableStateOf(0) }
    // Categoría actualmente seleccionada;Cadena vacía significa"Todos"
    var selectedCategory by remember { mutableStateOf("") }
    // Elementos de emoticones que se eliminarán(El cuadro de diálogo de confirmación de eliminación aparece cuando no es nulo)
    var pendingDelete by remember { mutableStateOf<StickerItem?>(null) }
    // v1.0.52: Haga clic para obtener una vista previa del elemento de emoticón(Cuando no es nulo, aparece el visor de pantalla completa)
    var previewItem by remember { mutableStateOf<StickerItem?>(null) }
    // v1.112 (F1-F2): Modo de eliminación por lotes
    var batchDeleteMode by remember { mutableStateOf(false) }
    val selectedIds = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    // v1.112: Eliminación por lotes/Confirmación clara
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var showClearAllConfirm by remember { mutableStateOf(false) }

    // Lista de categorías(importar/Actualizar después de la eliminación)
    val categories by produceState(initialValue = emptyList<String>(), refreshTrigger) {
        value = stickerRepo.listCategories()
    }

    // Lista de emoticones(Filtrar por categoría seleccionada;importar/Eliminar/Actualizar al cambiar de categoría)
    val stickers by produceState(
        initialValue = emptyList<StickerItem>(),
        refreshTrigger,
        selectedCategory,
    ) {
        value = stickerRepo.listStickers(selectedCategory.takeIf { it.isNotBlank() })
    }

    // importar zip launcher(SAF OpenDocument,limitado zip MIME)
    // v1.0.54: Usar en su lugar importUri(Identificar automáticamente zip/imágenes) + Devolución de llamada de progreso,La importación de paquetes grandes a equipos antiguos requiere comentarios visibles
    var importingSticker by remember { mutableStateOf(false) }
    var stickerImportProgress by remember { mutableStateOf<Float?>(null) }
    var stickerImportText by remember { mutableStateOf("") }
    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            importingSticker = true
            stickerImportProgress = null
            stickerImportText = ""
            scope.launch {
                stickerRepo.importUri(uri) { phase, done, total ->
                    stickerImportProgress =
                        if (total != null && total > 0) {
                            (done.toFloat() / total).coerceIn(0f, 1f)
                        } else {
                            null
                        }
                    stickerImportText =
                        if (total != null && total > 0) {
                            "$phase $done/$total"
                        } else {
                            localizedResources.getString(R.string.settings_sticker_import_progress, phase, done)
                        }
                }
                    .onSuccess { count ->
                        MuseToast.show(localizedResources.getString(R.string.settings_sticker_imported, count))
                        refreshTrigger++
                    }
                    .onError { msg, _ ->
                        MuseToast.show(localizedResources.getString(R.string.settings_sticker_import_failed, msg), 3500)
                    }
                importingSticker = false
                stickerImportProgress = null
            }
        }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsGroup {
            // interruptor de habilitación
            SettingsSwitchRow(
                icon = MuseIcons.image,
                title = stringResource(R.string.settings_sticker_enabled),
                subtitle = stringResource(R.string.settings_sticker_enabled_subtitle),
                checked = stickerEnabled,
                onCheckedChange = { v -> scope.launch { settings.saveStickerEnabled(v) } },
            )
            SettingsGroupDivider()
            // v2.x: Enviar rango de frecuencia(Marcar enlace;Reemplaza el antiguo control deslizante de probabilidad.)
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.cardInner),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = MuseIcons.sliders,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_sticker_frequency),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_sticker_frequency_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            STICKER_FREQ_OCCASIONALLY to R.string.settings_sticker_freq_occasional,
                            STICKER_FREQ_NORMAL to R.string.settings_sticker_freq_normal,
                            STICKER_FREQ_FREQUENT to R.string.settings_sticker_freq_frequent,
                        ).forEach { (value, labelRes) ->
                            MuseChip(
                                selected = stickerFrequency == value,
                                onClick = {
                                    scope.launch {
                                        settings.saveStickerFrequency(value)
                                        // v2.x: Seleccionar una marcha significa que quieres usar emoticones — Enciende automáticamente el interruptor principal.
                                        if (!stickerEnabled) settings.saveStickerEnabled(true)
                                    }
                                },
                                label = stringResource(labelRes),
                            )
                        }
                    }
                }
            }
            SettingsGroupDivider()
            // importar zip(Seleccionar archivo a través de SAF)
            SettingsItemRow(
                icon = MuseIcons.upload,
                title = stringResource(R.string.settings_sticker_upload_zip),
                subtitle = stringResource(R.string.settings_sticker_upload_zip_subtitle),
                onClick = {
                    importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                },
            ) { ChevronRight() }
            // v1.0.54: Importar texto de progreso(La barra de progreso no sigue el progreso real.,Los comentarios de los usuarios solo mantienen el texto)
            if (importingSticker) {
                Column(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    if (stickerImportText.isNotBlank()) {
                        Text(
                            text = stickerImportText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            // v1.112 (F1-F2): Eliminar/borrar entradas por lotes
            if (stickers.isNotEmpty()) {
                SettingsGroupDivider()
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MuseCapsuleButton(
                        text =
                        if (batchDeleteMode) {
                            stringResource(R.string.settings_sticker_batch_done)
                        } else {
                            stringResource(R.string.settings_sticker_batch_delete)
                        },
                        onClick = {
                            batchDeleteMode = !batchDeleteMode
                            if (!batchDeleteMode) selectedIds.clear()
                        },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                        modifier = Modifier.weight(1f),
                    )
                    MuseCapsuleButton(
                        text = stringResource(R.string.settings_common_clear_all),
                        onClick = { showClearAllConfirm = true },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Etiquetas de la cápsula de clasificación(Sólo se muestra si hay emoticonos.).
        // El número de categorías proviene del contenido importado y debe estar acotado; de lo contrario, la cuadrícula de vista previa que aparece a continuación se eliminará del área visible.
        if (stickers.isNotEmpty()) {
            LazyRow(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp, max = 56.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                item(key = "all") {
                    MuseChip(
                        selected = selectedCategory.isBlank(),
                        onClick = { selectedCategory = "" },
                        label = stringResource(R.string.settings_sticker_category_all),
                    )
                }
                lazyItems(categories, key = { it }) { cat ->
                    MuseChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = cat,
                    )
                }
            }
        }

        // Vista previa de malla/estado vacío
        if (stickers.isEmpty()) {
            // Estado vacío:Icono gris + título + subtítulo
            Box(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = MuseIcons.image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_sticker_empty_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.settings_sticker_empty_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            // v1.112 (F5): Utilice LazyVerticalGrid en su lugar FlowRow,Carga diferida de 600 fotos sin retrasos
            // La altura se fija en 3 líneas.(240dp),Desplazable internamente;Agregar clave para permitir que Compose se reorganice con precisión
            val gridColumns = GridCells.Adaptive(80.dp)
            LazyVerticalGrid(
                columns = gridColumns,
                modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(
                    items = stickers,
                    key = { it.id },
                ) { item ->
                    val file = stickerRepo.getStickerFileByPath(item.relativePath)
                    val isSelected = selectedIds[item.id] == true
                    Box(
                        modifier =
                        Modifier
                            .size(80.dp)
                            .clip(MuseShapes.medium)
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                },
                            )
                            .combinedClickable(
                                onClick = {
                                    if (batchDeleteMode) {
                                        selectedIds[item.id] = !isSelected
                                    } else {
                                        // v1.0.52: Haga clic para obtener una vista previa de la imagen grande en modo no por lotes
                                        previewItem = item
                                    }
                                },
                                onLongClick = {
                                    if (!batchDeleteMode) pendingDelete = item
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model =
                            coil.request.ImageRequest.Builder(context)
                                .data(file)
                                // v1.112 (F5): Limitar el tamaño de decodificación a 80dp,Evite desperdiciar memoria al decodificar imágenes grandes a resolución completa
                                .size(160)
                                .crossfade(false)
                                .build(),
                            contentDescription = item.fileName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // v1.112 (F1-F2): Capas de máscara seleccionadas en modo de eliminación por lotes
                        if (batchDeleteMode && isSelected) {
                            Box(
                                modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = MuseIcons.check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                    }
                }
            }
            // v1.112 (F1-F2): En modo de eliminación por lotes,Mostrar el botón de eliminar en la parte inferior
            if (batchDeleteMode && selectedIds.isNotEmpty()) {
                Surface(
                    onClick = { showBatchDeleteConfirm = true },
                    color = MaterialTheme.colorScheme.error,
                    shape = MuseShapes.pill,
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_sticker_delete_selected, selectedIds.count { it.value }),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onError,
                        textAlign = TextAlign.Center,
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                    )
                }
            }
        }
    }

    // Eliminar cuadro de diálogo de confirmación(Aparecerá una ventana emergente después de presionar prolongadamente el paquete de emoticonos.)
    pendingDelete?.let { item ->
        MuseDialog(
            onDismissRequest = { pendingDelete = null },
            title = stringResource(R.string.settings_sticker_delete_confirm),
            content = { Text(item.fileName) },
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                scope.launch {
                    stickerRepo.deleteSticker(item.id)
                    pendingDelete = null
                    refreshTrigger++
                }
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { pendingDelete = null },
            destructive = true,
        )
    }

    // v1.0.52: Haga clic en la imagen para obtener una vista previa(Visor de medios en pantalla completa para reutilizar mensajes de chat)
    previewItem?.let { item ->
        val file = stickerRepo.getStickerFileByPath(item.relativePath)
        FullScreenMediaViewer(
            images = listOf("file://${file.absolutePath}"),
            initialIndex = 0,
            onDismiss = { previewItem = null },
        )
    }

    // v1.112 (F1-F2): Confirmación de eliminación por lotes
    if (showBatchDeleteConfirm) {
        val count = selectedIds.count { it.value }
        MuseDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = stringResource(R.string.settings_sticker_batch_confirm_title),
            content = { Text(stringResource(R.string.settings_sticker_batch_confirm_content, count)) },
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                scope.launch {
                    val ids = selectedIds.filter { it.value }.keys
                    val deleted = stickerRepo.deleteStickers(ids)
                    MuseToast.show(stickerDeletedFormat.replace("%1\$d", deleted.toString()))
                    selectedIds.clear()
                    batchDeleteMode = false
                    showBatchDeleteConfirm = false
                    refreshTrigger++
                }
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showBatchDeleteConfirm = false },
            destructive = true,
        )
    }

    // v1.112 (F1-F2): Borrar toda la confirmación
    if (showClearAllConfirm) {
        MuseDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = stringResource(R.string.settings_sticker_clear_all_title),
            content = { Text(stringResource(R.string.settings_sticker_clear_all_confirm_content)) },
            confirmText = stringResource(R.string.settings_common_clear),
            onConfirm = {
                scope.launch {
                    val deleted = stickerRepo.clearAll()
                    MuseToast.show(stickerClearedFormat.replace("%1\$d", deleted.toString()))
                    selectedIds.clear()
                    batchDeleteMode = false
                    showClearAllConfirm = false
                    refreshTrigger++
                }
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showClearAllConfirm = false },
            destructive = true,
        )
    }
}

/**
 * E3: Fondo degradado de chat preestablecido(Degradado lineal de dos colores,ARGB Long valor de color).
 * Sólo se muestran bloques de colores.,El nombre no se muestra(Evite introducir 6 grupos ×7 Costos de traducción de idiomas).
 */
internal val PRESET_CHAT_GRADIENTS: List<Pair<Long, Long>> =
    listOf(
        // mañana azul
        0xFFE3F2FDL to 0xFF90CAF9L,
        // mar profundo
        0xFF1A2980L to 0xFF26D0CEL,
        // Vuelo nocturno
        0xFF0F2027L to 0xFF2C5364L,
        // oro cálido
        0xFFFCEABBL to 0xFFF8B500L,
        // turquesa
        0xFF43C6ACL to 0xFF191654L,
        // puesta de sol
        0xFF800000L to 0xFFE8B298L,
    )

/**
 * Bloque completo de configuraciones de stickers: títulos agrupados + gestión de biblioteca.
 *
 * Empaquetado en un único elemento componible, la persona que llama solo necesita un elemento para evitar estirar el método de la página.
 */
@Composable
internal fun StickerSettingsSection(settings: SettingsRepository, scope: CoroutineScope) {
    Column {
        SectionLabel(stringResource(R.string.settings_chat_sticker_section))
        StickerLibrarySection(settings = settings, scope = scope)
    }
}
