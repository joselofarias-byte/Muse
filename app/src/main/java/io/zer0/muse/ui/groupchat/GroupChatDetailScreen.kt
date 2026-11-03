@file:OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package io.zer0.muse.ui.groupchat

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import io.github.fletchmckee.liquid.liquefiable
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.ui.ChatSelectionBar
import io.zer0.muse.ui.DateSeparator
import io.zer0.muse.ui.ModelSwitchSheet
import io.zer0.muse.ui.common.MuseFloatingActionItem
import io.zer0.muse.ui.common.MuseFloatingActionMenu
import io.zer0.muse.ui.common.MusePopover
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.ChatTopBarScrim
import io.zer0.muse.ui.common.navigation.MuseTopBarIconButton
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.common.surface.museBottomBarInsets
import io.zer0.muse.ui.isSameDay
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.assistantBubble
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.util.ShareIntentHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

/**
 * Página de detalles del chat grupal — Flujo de mensajes + campo de entrada + respuesta de rotación de agentes.
 *
 * diseño(warm-paper estilo):
 *  - Scaffold:TopAppBar(Nombre del chat grupal + regresar + botón de lista de miembros)+ abajo InputBar
 *  - medio:LazyColumn flujo de mensajes,Distinguir entre mensajes de usuario/asistente
 *  - assistant noticias:Izquierda + avatar + senderName
 *  - user noticias:lado derecho
 *  - Agent Mostrado durante la respuesta"pensando..."estado de espera
 *  - El flujo de mensajes se desplaza automáticamente hacia la parte inferior
 *
 * @param chatId chat grupal id
 * @param onBack devolución de llamada
 * @param viewModel chat grupal ViewModel
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GroupChatDetailScreen(
    chatId: String,
    onBack: () -> Unit,
    /** HTML/SVG Devolución de llamada de vista previa de pantalla completa de bloque de código(El parámetro es el código fuente HTML completo.). */
    onHtmlPreview: (String) -> Unit = {},
    /** B0-07: Abra la página de administración de plantillas de Word. */
    onOpenPromptTemplateManager: () -> Unit = {},
    /** v1.0.72: Proveedor de asistente de edición(Ir a la página de configuración de modelo y servicio). */
    onEditAssistantProvider: (String) -> Unit = {},
    viewModel: GroupChatViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // v1.45: Inicializado con la posición de desplazamiento almacenada en caché en ViewModel LazyListState,Cortar páginas/Restaurar ubicación después del fondo
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.listFirstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = state.listFirstVisibleItemScrollOffset,
    )
    // v1.45: Se almacena en caché sincrónicamente cuando cambia la posición de desplazamiento ViewModel
    // v1.100: agregar distinctUntilChanged + sample(100ms) desaceleración,Evite la alta frecuencia al desplazarse
    // Escribir en el estado ViewModel provoca la reorganización de la página completa(desplazarse → escribir state → Reorganización → Bucle implícito de remedición)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .sample(100L)
            .distinctUntilChanged()
            .collect { (index, offset) ->
                viewModel.onListScrollPositionChanged(index, offset)
            }
    }
    // v1.0.74 fix (Auditoría inicial 1.4): consumo jumpTargetId — Después de hacer clic en los resultados de la búsqueda, desplácese hasta el mensaje de destino y resáltelo brevemente.
    // Anteriormente, jumpTargetId solo estaba definido en ViewModel.,UI Sin seguimiento,La función de salto de búsqueda está desactivada silenciosamente.
    var highlightedJumpMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel.jumpTargetId) {
        val targetId = viewModel.jumpTargetId.value ?: return@LaunchedEffect
        // Desplazamiento inicial del área de mensajes: Load_more_indicator superior cuando esté presente index 0
        val msgStart = if (state.isLoadingMore) 1 else 0
        val idx = state.currentMessages.indexOfFirst { it.id == targetId }
        if (idx >= 0) {
            listState.animateScrollToItem(msgStart + idx)
            highlightedJumpMessage = targetId
            // Resalte 2,5 segundos y luego borre
            delay(2500)
            if (highlightedJumpMessage == targetId) highlightedJumpMessage = null
        }
    }
    var showMembersDialog by rememberSaveable { mutableStateOf(false) }
    var showToolSheet by rememberSaveable { mutableStateOf(false) }
    // v2.x: Panel de operación de conferencia(votar/Resumen/@/miembro/contexto/Editar) — Entrada nivel 1 promocionada desde el menú plus
    var showMeetingSheet by remember { mutableStateOf(false) }
    // v1.97: Cuadro de diálogo de edición de chat grupal
    var showEditDialog by rememberSaveable { mutableStateOf(false) }
    // v1.0.72: Función de menú de tres puntos
    var showSearchDialog by rememberSaveable { mutableStateOf(false) }
    var showProviderDialog by rememberSaveable { mutableStateOf(false) }
    var providerTargetAssistantId by remember { mutableStateOf<String?>(null) }
    // v1.77: Mensaje pulsación larga menú + confirmación de eliminación
    var messageMenuTarget by remember { mutableStateOf<GroupChatMessageEntity?>(null) }
    var messageMenuBounds by remember { mutableStateOf(Rect.Zero) }
    var messageMenuPointInWindow by remember { mutableStateOf<Offset?>(null) }
    var deleteMessageTarget by remember { mutableStateOf<GroupChatMessageEntity?>(null) }
    // v1.x: Confirmación de eliminación de lotes de selección múltiple
    var deleteSelectedTarget by remember { mutableStateOf(false) }
    // v2.x: Estado del cuadro de diálogo Respuesta/Susurro/Voto/Resumen de cotización
    var replyToMessage by remember { mutableStateOf<GroupChatMessageEntity?>(null) }
    var whisperTarget by remember { mutableStateOf<GroupChatMessageEntity?>(null) }
    var showVoteDialog by remember { mutableStateOf(false) }
    var showSummaryDialog by remember { mutableStateOf(false) }
    // v2.x: Gestión del contexto del chat grupal sheet(Documentos compartidos en grupo + contexto exclusivo de IA)
    var showContextSheet by remember { mutableStateOf(false) }
    // v2.x: Plegado redondo — Conjunto ampliado de puntos de partida de secciones asistentes consecutivas.(Contraer la parte intermedia de una ronda de discusión larga para mantenerla legible.)
    var expandedRoundStarts by remember { mutableStateOf(setOf<Int>()) }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    // Phase 4: Las burbujas de chat grupal comparten la misma apariencia de anfitrión;null = Apariencia del chat grupal existente(El análisis y el respaldo están dentro del componente de burbuja.).
    // La máscara del complemento tiene el mismo origen que DanChat.:Vuelva a comprobar al leer,Complemento deshabilitado/Volver al valor predeterminado integrado después de la desinstalación.
    val pluginSkinSource: io.zer0.muse.ui.theme.PluginSkinSource =
        org.koin.compose.koinInject()
    val bubbleSkinStore = remember(context, pluginSkinSource) {
        io.zer0.muse.ui.theme.BubbleSkinStore(context, pluginSkinSource)
    }
    val bubbleSkin by bubbleSkinStore.selectedSkinFlow.collectAsStateWithLifecycle(initialValue = null)

    // Selector de imágenes:Las imágenes seleccionadas se agregan a la lista de envío
    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val dataUri = withContext(Dispatchers.IO) {
                    val bytes = readImageBytes(context, uri)
                    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
                    if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                        throw IllegalStateException("No se puede analizar el tamaño de la imagen")
                    }
                    val maxDim = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
                    var sampleSize = 1
                    while (maxDim / sampleSize > 1024) sampleSize *= 2
                    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                        ?: throw IllegalStateException("No se puede decodificar la imagen")
                    try {
                        encodeBitmapToBase64(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
                viewModel.addPendingImage("data:image/jpeg;base64,$dataUri")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Logger.w("GroupChatDetail", "Falló la lectura de la imagen: ${t.message}", t)
            }
        }
    }

    // v1.0.30: Selector de archivos adjuntos — Guardar archivo como archivo adjunto base64
    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val (bytes, mimeType, fileName) = withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: context.contentResolver.openFileDescriptor(uri, "r")?.let { java.io.FileInputStream(it.fileDescriptor) }
                        ?: throw IllegalStateException("No se puede leer el archivo")
                    val data = stream.use { it.readBytes() }
                    Triple(data, context.contentResolver.getType(uri) ?: "application/octet-stream", uri.lastPathSegment ?: "Archivo")
                }
                if (bytes.size > 10 * 1024 * 1024) {
                    // P2-10: Anteriormente, los archivos de gran tamaño se descartaban silenciosamente,El usuario no lo sabe;Cambiar a mensaje explícito
                    Logger.w("GroupChatDetail", "El archivo supera el límite de 10 MB,Rechazado: $fileName")
                    io.zer0.muse.ui.common.feedback.MuseToast.show(
                        context.getString(R.string.err_group_chat_attachment_too_large),
                    )
                    return@launch
                }
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                viewModel.addPendingFileAttachment(FileAttachment(name = fileName, mimeType = mimeType, base64 = base64))
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Logger.w("GroupChatDetail", "Falló la lectura del archivo: ${t.message}", t)
                // P2-10: Los fallos de lectura ya no solo registran,Dar a los usuarios sugerencias visibles
                io.zer0.muse.ui.common.feedback.MuseToast.show(
                    context.getString(R.string.err_group_chat_attachment_read_failed),
                )
            }
        }
    }

    // v1.112 (C2): Prompt Estado de la hoja de selección de plantilla
    var showPromptTemplateSheet by remember { mutableStateOf(false) }
    val settings: io.zer0.muse.data.SettingsRepository = org.koin.compose.koinInject()
    val promptTemplates by settings.promptTemplatesFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    // v1.0.72: Área multimedia del menú Plus — álbum de fotos/Permiso de cámara + toma de fotos + clic en miniatura del álbum
    val galleryPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        android.Manifest.permission.READ_MEDIA_IMAGES
    } else {
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var hasGalleryPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, galleryPermission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    val galleryPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasGalleryPermission = granted }

    // tomar fotos:Cámara del sistema(TakePicture No se requiere permiso de la CÁMARA,FileProvider guardar)
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    // Miniatura del álbum/Resultados de fotos → Compresión base64 → Añadir para enviar
    fun loadUriToPending(uri: Uri) {
        scope.launch {
            try {
                val dataUri = withContext(Dispatchers.IO) {
                    val bytes = readImageBytes(context, uri)
                    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
                    if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                        throw IllegalStateException("No se puede analizar el tamaño de la imagen")
                    }
                    val maxDim = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
                    var sampleSize = 1
                    while (maxDim / sampleSize > 1024) sampleSize *= 2
                    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                        ?: throw IllegalStateException("No se puede decodificar la imagen")
                    try {
                        encodeBitmapToBase64(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
                viewModel.addPendingImage("data:image/jpeg;base64,$dataUri")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Logger.w("GroupChatDetail", "Falló la lectura de la imagen: ${t.message}", t)
            }
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            loadUriToPending(uri)
        }
        pendingCameraUri = null
    }

    // Entrada de foto:Cree FileProvider uri y ajuste la cámara del sistema
    fun startCameraCapture() {
        val file = java.io.File.createTempFile("muse_group_capture_", ".jpg", context.cacheDir)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        pendingCameraUri = uri
        takePictureLauncher.launch(uri)
    }

    // Selecciona el chat grupal al ingresar a la página(Cargar flujo de mensajes)
    LaunchedEffect(chatId) {
        viewModel.selectChat(chatId)
    }

    // M7: Regresar automáticamente a la página de lista cuando se elimina un chat grupal
    LaunchedEffect(state.chatDeleted) {
        if (state.chatDeleted) onBack()
    }

    // v1.126: Borrar mensajes de error automáticamente(Restablecer después de mostrar durante 3 segundos)
    LaunchedEffect(state.errorMessage) {
        if (state.errorMessage != null) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearError()
        }
    }

    // Desplácese automáticamente hasta el final cuando cambie la lista de mensajes
    // H-GC1 Reparación: AnimateScrollToItem incondicional original hasta el final,Los usuarios se verán obligados a retroceder al ver los mensajes históricos.
    // Cambiar a:Sólo si listState ya está cerca del final(Dentro de los últimos 2 artículos)Desplazarse automáticamente cuando,
    // No hay interrupción cuando el usuario desliza activamente hacia arriba para ver el historial.
    // v1.53-GC: Hay un elemento más al principio de la lista."cargar más"indicador item,El desplazamiento automático hacia abajo todavía se basa en el mensaje lastIndex.
    // (Indicador de pensamiento detrás del mensaje,no afecta"Llegó al fondo"juez).
    LaunchedEffect(state.currentMessages.size, state.isAgentResponding, state.streamingContent?.length) {
        if (state.currentMessages.isEmpty()) return@LaunchedEffect
        val lastIndex = state.currentMessages.lastIndex
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val isNearBottom = lastVisible >= lastIndex - 2 || lastVisible < 0
        if (isNearBottom) {
            // v1.0.74 fix (Auditoría inicial 1.6): streamingContent Después de agregar la longitud a la clave,
            // La salida de streaming también activará el desplazamiento cuando crezca token por token,Los usuarios pueden ver el contenido que se genera.
            listState.animateScrollToItem(lastIndex)
        }
    }

    // v1.53-GC: Desliza hacia arriba para cargar más noticias históricas — Llegar al principio de la lista(firstVisibleItemIndex==0)y se activa cuando se cumplen las condiciones.
    // Configuración de ViewModel después de activar lastHistoryLoadCount,El LaunchedEffect a continuación ajusta la posición de desplazamiento en consecuencia,
    // Omitir la cantidad de elementos recién insertados,Hacer que firstVisibleItemIndex se convierta en lastHistoryLoadCount(>0),
    // evitando así repetidos disparos en la parte superior(El usuario debe deslizar activamente hacia arriba nuevamente para continuar con la carga.).
    var savedScrollOffset by remember { mutableStateOf(0) }
    val loadMoreTrigger by remember {
        derivedStateOf {
            state.hasMoreHistory &&
                !state.isLoadingMore &&
                !state.isAgentResponding &&
                state.currentMessages.isNotEmpty() &&
                listState.firstVisibleItemIndex == 0
        }
    }
    LaunchedEffect(loadMoreTrigger) {
        if (loadMoreTrigger) {
            // registro actual offset,Mantenga la posición visual al saltar a una nueva ubicación después de completar la carga
            savedScrollOffset = listState.firstVisibleItemScrollOffset
            viewModel.loadMoreHistory()
        }
    }
    // v1.53-GC: Ajuste la posición de desplazamiento después de que se complete la carga,Evita que la posición visual salte.
    // (El mensaje que estaba en la parte superior ahora está en lastHistoryLoadCount)
    LaunchedEffect(state.lastHistoryLoadCount) {
        if (state.lastHistoryLoadCount > 0) {
            listState.scrollToItem(state.lastHistoryLoadCount, savedScrollOffset)
            viewModel.clearHistoryLoadCount()
            savedScrollOffset = 0
        }
    }

    val chatName = state.currentChat?.name ?: stringResource(R.string.groupchat_default_name)

    // v1.0.74: Fondo personalizado del chat grupal(Compartir configuración con fondo de chat)
    val chatBackground by org.koin.compose.koinInject<io.zer0.muse.data.SettingsRepository>()
        .chatBackgroundFlow
        .collectAsState(initial = null)

    Box(modifier = Modifier.fillMaxSize()) {
        // v2.6: Capa de fondo de vidrio a nivel de página —— Isomórfico con chat único.
        // clave:backdrop La capa solo cubre el fondo.,Contenido como nodo hermano,evitar
        // “toma de vidrio backdrop → backdrop Contiene vidrio”recursión de renderizado(Causará el desbordamiento de la pila de RenderThread).
        val glassConfig = io.zer0.muse.ui.theme.LocalLiquidGlass.current
        val glassActive = glassConfig.enabled && io.zer0.muse.ui.theme.isRealGlassSupported()
        val pageLayerBackdrop =
            if (glassActive) com.kyant.backdrop.backdrops.rememberLayerBackdrop() else null
        val pageWaterState =
            if (glassActive) io.github.fletchmckee.liquid.rememberLiquidState() else null
        val backgroundIsDark =
            if (!chatBackground.isNullOrBlank()) {
                true
            } else {
                androidx.compose.material3.MaterialTheme.colorScheme.background.luminance() < 0.5f
            }
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .then(
                    if (pageLayerBackdrop != null) {
                        Modifier.layerBackdrop(pageLayerBackdrop)
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (pageWaterState != null) {
                        Modifier.liquefiable(pageWaterState)
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (!chatBackground.isNullOrBlank()) {
                io.zer0.muse.ui.SmartImage(
                    model = chatBackground,
                    contentDescription = stringResource(R.string.groupchat_bg_cd), // Correcciones en la interfaz (i18n-1)
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(
            io.zer0.muse.ui.theme.LocalLayerBackdrop provides
                (pageLayerBackdrop ?: io.zer0.muse.ui.theme.LocalLayerBackdrop.current),
            io.zer0.muse.ui.theme.LocalWaterGlassState provides
                (pageWaterState ?: io.zer0.muse.ui.theme.LocalWaterGlassState.current),
            io.zer0.muse.ui.theme.LocalGlassIsBackgroundDark provides backgroundIsDark,
        ) {
            MusePageScaffold(
                // v1.0.52: La barra superior y el área de entrada inferior son respectivamente responsables de la inserción de la barra del sistema, de modo que el contenido de la página no se repita.
                topBar = {
                    // Menú de tres puntos: Buscar/Editar chat grupal/Editar proveedores asistentes
                    Box(modifier = Modifier.fillMaxWidth()) {
                        // Fondo degradado muy claro para la barra superior de íconos desnudos:noticias(O personalizar el fondo del chat)rodará debajo de la barra superior,
                        // Sin él, el icono y el nombre del grupo se superpondrían en el contenido.
                        // v2.5.0: Sincronización con chat único — Fuente de vidrio global + isla de vidrio independiente.
                        ChatTopBarScrim(
                            modifier = Modifier.matchParentSize(),
                            glassActive = io.zer0.muse.ui.theme.LocalLiquidGlass.current.enabled,
                            glassConfig = io.zer0.muse.ui.theme.LocalLiquidGlass.current,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = MusePaddings.screen, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                        ) {
                            // isla izquierda:Regresar(Componentes circulares compartidos,Chatea con Dan/Barra superior de la página de inicio del mismo tamaño)
                            MuseTopBarIconButton(
                                icon = MuseIcons.arrowLeft,
                                contentDescription = stringResource(R.string.groupchat_back),
                                onClick = onBack,
                            )
                            // Nakajima:Nombre del chat grupal(La altura es la misma que la de Danchaozhongdao.:48dp,Los botones redondos izquierdo y derecho 40dp abren la capa)
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = chatName,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            // isla derecha:Menú de tres puntos(Componentes circulares compartidos)
                            var showTopMenu by rememberSaveable { mutableStateOf(false) }
                            Box(
                                modifier = Modifier.size(MuseIconSizes.touchTarget),
                                contentAlignment = Alignment.Center,
                            ) {
                                MuseTopBarIconButton(
                                    icon = MuseIcons.moreVertical,
                                    contentDescription = stringResource(R.string.chat_top_menu_cd),
                                    onClick = { showTopMenu = true },
                                    tint = if (showTopMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                if (showTopMenu) {
                                    // v1.0.90: Mantenga la capa flotante de tres puntos en la esquina superior derecha (se cambió al panel inferior y se volvió a cambiar según los comentarios).
                                    MuseFloatingActionMenu(
                                        items = listOf(
                                            MuseFloatingActionItem(
                                                key = "search",
                                                icon = MuseIcons.search,
                                                label = stringResource(R.string.groupchat_search),
                                                onClick = {
                                                    showTopMenu = false
                                                    showSearchDialog = true
                                                },
                                            ),
                                            MuseFloatingActionItem(
                                                key = "edit",
                                                icon = MuseIcons.edit,
                                                label = stringResource(R.string.groupchat_edit_cd),
                                                onClick = {
                                                    showTopMenu = false
                                                    showEditDialog = true
                                                },
                                            ),
                                            MuseFloatingActionItem(
                                                key = "provider",
                                                // UI-FIX: De acuerdo con el menú de chat único, los proveedores utilizan iconos de chips en lugar de flashes.
                                                icon = MuseIcons.memoryChip,
                                                label = stringResource(R.string.groupchat_edit_provider),
                                                onClick = {
                                                    showTopMenu = false
                                                    showProviderDialog = true
                                                },
                                            ),
                                            // v2.x: Archivo/Desarchivar
                                            MuseFloatingActionItem(
                                                key = "archive",
                                                icon = MuseIcons.archive,
                                                label = if ((state.currentChat?.isArchived ?: false)) {
                                                    stringResource(
                                                        R.string.groupchat_unarchive,
                                                    )
                                                } else {
                                                    stringResource(R.string.groupchat_archive)
                                                },
                                                onClick = {
                                                    showTopMenu = false
                                                    val chat = state.currentChat ?: return@MuseFloatingActionItem
                                                    viewModel.toggleArchive(chat.id, !chat.isArchived)
                                                },
                                            ),
                                        ),
                                        onDismiss = { showTopMenu = false },
                                        glassActive = io.zer0.muse.ui.theme.LocalLiquidGlass.current.enabled,
                                    )
                                }
                            }
                        }
                    }
                },
                containerColor = if (chatBackground.isNullOrBlank()) {
                    MaterialTheme.colorScheme.background
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
                bottomBar = {
                    Column(
                        modifier = Modifier.museBottomBarInsets(),
                    ) {
                        // A4: Barra de operación por lotes de selección múltiple — Componente unificado ChatSelectionBar compartido con chat único
                        if (state.selectedMessageIds.isNotEmpty()) {
                            val selectedText = state.currentMessages
                                .filter { it.id in state.selectedMessageIds }
                                .joinToString("\n\n") { "${it.senderName}: ${it.body}" }
                            ChatSelectionBar(
                                count = state.selectedMessageIds.size,
                                onSelectAll = { viewModel.selectAllVisible() },
                                onCopy = {
                                    if (selectedText.isNotBlank()) {
                                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                        cm?.setPrimaryClip(
                                            android.content.ClipData.newPlainText("muse-selected", selectedText),
                                        )
                                    }
                                },
                                onDelete = { deleteSelectedTarget = true },
                                onExport = {
                                    if (selectedText.isNotBlank()) {
                                        val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_TEXT, selectedText)
                                        }
                                        ShareIntentHelper.startChooserSafely(context, sendIntent)
                                    }
                                },
                                onExit = { viewModel.clearSelection() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = MusePaddings.screen, vertical = 6.dp),
                            )
                        }
                        // ActivityHub: Barra de estado activa compacta encima del cuadro de entrada,Muestra el chip de estado de cada agente en la rotación actual.
                        // IDLE El estado lo filtra internamente AgentActivityBar y no se muestra.;Cuando no hay actividad, toda la columna no ocupa espacio.
                        AgentActivityBar(activities = state.activities)
                        // v2.x: Girar la tira de control del director — pausa/continuar/saltar restante(pausa+continuar=Un solo paso adelante,Para después de cada uno)
                        if (state.isAgentResponding) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = MusePaddings.screen, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (!state.roundPaused) {
                                    MuseCapsuleButton(
                                        text = stringResource(R.string.groupchat_round_pause),
                                        onClick = { viewModel.pauseRound() },
                                        variant = IosCapsuleButtonVariant.Text,
                                    )
                                } else {
                                    MuseCapsuleButton(
                                        text = stringResource(R.string.groupchat_round_resume),
                                        onClick = { viewModel.resumeRound() },
                                        variant = IosCapsuleButtonVariant.Primary,
                                    )
                                    MuseCapsuleButton(
                                        text = stringResource(R.string.groupchat_round_skip),
                                        onClick = { viewModel.skipRoundRemaining() },
                                        variant = IosCapsuleButtonVariant.Text,
                                    )
                                }
                                // v2.x: Visualización de colas — Los miembros restantes que hablarán en esta ronda.
                                if (state.upcomingSpeakers.isNotEmpty()) {
                                    Text(
                                        text = stringResource(R.string.groupchat_round_upcoming) + " " +
                                            state.upcomingSpeakers.joinToString(" → "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                        // Vista previa de la línea de imagen que se enviará
                        if (state.pendingImages.isNotEmpty()) {
                            PendingImagesRow(
                                images = state.pendingImages,
                                onRemove = { viewModel.removePendingImage(it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = MusePaddings.screen, vertical = 8.dp),
                            )
                        }
                        // v2.6.7: Vista previa de la línea del archivo adjunto que se enviará
                        // Anteriormente, pendienteFileAttachments solo participaba en el juicio canSend y la interfaz no se representaba en absoluto.
                        // Los usuarios no pueden ver ni eliminar (removePendingFileAttachment cero llamadas).
                        if (state.pendingFileAttachments.isNotEmpty()) {
                            PendingAttachmentsRow(
                                attachments = state.pendingFileAttachments,
                                onRemove = { viewModel.removePendingFileAttachment(it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = MusePaddings.screen, vertical = 8.dp),
                            )
                        }
                        // Analizar miembros del chat grupal(Para el filtrado automático de finalización de @menciones)
                        val chat = state.currentChat
                        val memberIds = remember(chat) {
                            chat?.let { viewModel.parseMemberIds(it) } ?: emptyList()
                        }
                        val members = remember(memberIds, state.assistants) {
                            memberIds.mapNotNull { id -> state.assistants.find { it.id == id } }
                        }
                        GroupChatInputBar(
                            text = state.inputText,
                            onTextChange = { viewModel.updateInput(it) },
                            onSend = {
                                viewModel.sendMessage(state.inputText)
                                keyboard?.hide()
                            },
                            onOpenToolSheet = {
                                MuseHaptics.light(haptic)
                                showToolSheet = true
                            },
                            onOpenMeetingSheet = {
                                MuseHaptics.light(haptic)
                                showMeetingSheet = true
                            },
                            enabled = !state.isAgentResponding,
                            canSend = state.inputText.isNotBlank() ||
                                state.pendingImages.isNotEmpty() ||
                                state.pendingFileAttachments.isNotEmpty(),
                            members = members,
                            // v2.6.7: Durante la generación, cambie la clave de envío a la tecla de parada (stopGeneration llamada nula antes)
                            isGenerating = state.isAgentResponding,
                            onStop = {
                                MuseHaptics.light(haptic)
                                viewModel.stopGeneration()
                            },
                        )
                    }
                },
            ) { innerPadding ->
                // v1.126: pancarta de error(superposición sobre el contenido)
                val errorMsg = state.errorMessage
                if (errorMsg != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(innerPadding)
                            .padding(MusePaddings.cardInnerSpaced),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = errorMsg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
                // v1.77: Estado de inicio de mensaje vacío(El nuevo chat grupal no se muestra en blanco después de ingresar)
                if (state.currentMessages.isEmpty() && !state.isAgentResponding) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(io.zer0.muse.R.drawable.ic_muse_logo),
                                contentDescription = null,
                                modifier = Modifier.size(72.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.groupchat_empty_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            // v1.0.72: No pad top — La lista de mensajes se extiende detrás de la isla flotante en la parte superior.(efecto levitante)
                            .padding(bottom = innerPadding.calculateBottomPadding()),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = MusePaddings.screen,
                            end = MusePaddings.screen,
                            // v1.0.72: La cima da paso a tres islas suspendidas.
                            top = innerPadding.calculateTopPadding(),
                            bottom = MusePaddings.itemGap,
                        ),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                    ) {
                        // v1.53-GC: Indicador de carga superior al deslizar hacia arriba para cargar más historial(Chat único de referencia HistoryLoadMorePlaceholder).
                        // isLoadingMore=true inserta esto item(ocupar index 0),Una vez completada la carga(lastHistoryLoadCount>0)
                        // Ajuste scrollToItem por LaunchedEffect para omitir la cantidad de elementos recién insertados,Mantén la posición visual sin saltar.
                        if (state.isLoadingMore) {
                            item(key = "load_more_indicator") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = MusePaddings.contentGap),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    MuseSpinner(
                                        size = 16.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.chat_loading_more_history),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        itemsIndexed(
                            items = state.currentMessages,
                            key = { _, it -> it.id },
                        ) { index, message ->
                            // v2.x: Plegado redondo — segmento asistente continuo ≥4 Cuando la tira no está expandida.,sección media(primero2/fin1afuera)cerrar
                            val collapseSeg = groupCollapseSegment(state.currentMessages, index)
                            if (collapseSeg != null && collapseSeg.second >= 4 && collapseSeg.third &&
                                collapseSeg.first !in expandedRoundStarts
                            ) {
                                return@itemsIndexed
                            }
                            // CHAT-04: Agregar separador de fecha en el chat grupal(lapso de tiempo,Reutilizar la implementación de chat único)
                            val prevMessage = state.currentMessages.getOrNull(index - 1)
                            if (prevMessage == null || !isSameDay(prevMessage.timestamp, message.timestamp)) {
                                DateSeparator(timestamp = message.timestamp)
                            }
                            // v2.x: Contraer información sobre herramientas — Después del segundo párrafo al principio del párrafo, el mensaje se cierra en el párrafo medio.
                            if (collapseSeg != null && collapseSeg.second >= 4 &&
                                index == collapseSeg.first + 1 && collapseSeg.first !in expandedRoundStarts
                            ) {
                                CollapsedRoundBar(
                                    hiddenCount = collapseSeg.second - 3,
                                    onExpand = { expandedRoundStarts = expandedRoundStarts + collapseSeg.first },
                                )
                            }
                            val expandedState = state.messageExpandedStates[message.id]
                            // v2.x: Tarjeta exclusiva para resumir mensajes. — Copiar/Compartir/Guardar como documento compartido(Cristalización de la discusión)
                            if (message.messageType == "summary") {
                                GroupSummaryCard(
                                    message = message,
                                    onCopy = {
                                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("muse-summary", message.body))
                                    },
                                    onShare = {
                                        val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_TEXT, message.body)
                                        }
                                        ShareIntentHelper.startChooserSafely(context, sendIntent)
                                    },
                                    onSaveAsSharedDoc = { viewModel.saveSummaryAsSharedDoc(message.id) },
                                    onContinue = {
                                        // v2.x: Continuar implementando — Resumen devuelve el cuadro de entrada,Puede agregar instrucciones o @members para continuar con el progreso.
                                        val body = if (message.body.length > 500) message.body.take(500) + "…" else message.body
                                        val prefix = if (state.inputText.isBlank()) "" else state.inputText + "\n\n"
                                        val marker = context.getString(R.string.groupchat_continue_summary_prefix)
                                        viewModel.updateInput(prefix + marker + body)
                                    },
                                )
                            } else if (message.messageType == "vote") {
                                GroupVoteCard(message = message)
                            } else {
                                GroupChatMessageBubble(
                                    message = message,
                                    assistants = state.assistants,
                                    chatPrefs = state.chatPreferences,
                                    bubbleSkin = bubbleSkin,
                                    // v1.0.74 fix (Auditoría inicial 1.4): Resaltado de salto de búsqueda
                                    highlighted = message.id == highlightedJumpMessage,
                                    isMoodExpanded = expandedState?.isMoodExpanded,
                                    isReasoningExpanded = expandedState?.isReasoningExpanded,
                                    onToggleMoodExpanded = { viewModel.toggleMessageMoodExpanded(message.id) },
                                    onToggleReasoningExpanded = { viewModel.toggleMessageReasoningExpanded(message.id) },
                                    // v1.77: Mantenga presionado para que aparezca el menú de operación.
                                    onLongClick = { bounds, pointInWindow ->
                                        MuseHaptics.medium(haptic)
                                        if (state.selectedMessageIds.isNotEmpty()) {
                                            viewModel.toggleMessageSelection(message.id)
                                        } else {
                                            messageMenuBounds = bounds
                                            messageMenuPointInWindow = pointInWindow
                                            messageMenuTarget = message
                                        }
                                    },
                                    // v1.x: Modo de selección múltiple
                                    selectionMode = state.selectedMessageIds.isNotEmpty(),
                                    selected = message.id in state.selectedMessageIds,
                                    onSelectToggle = { viewModel.toggleMessageSelection(message.id) },
                                    onHtmlPreview = onHtmlPreview,
                                    // La devolución de la tarjeta recae en**Chat grupal actual**: Las tarjetas del chat grupal pertenecen naturalmente a este chat grupal.
                                    // No deberías saltar a un chat privado.Save Actualmente no hay una ubicación para mostrar artefactos en el chat grupal y se ha implementado un mensaje legible.
                                    onCardAction = { action ->
                                        when (action) {
                                            is io.zer0.muse.ui.markdown.CardAction.Send -> {
                                                val body = if (action.data.isNullOrBlank()) {
                                                    action.text
                                                } else {
                                                    io.zer0.muse.ui.markdown.appendCardStateToMessage(
                                                        text = action.text,
                                                        cardId = action.cardId,
                                                        data = action.data,
                                                    )
                                                }
                                                if (body.isNotBlank()) viewModel.sendMessage(body)
                                            }
                                            // La recogida/guardado de la devolución de la tarjeta requiere el artefacto y el sistema de recogida del anfitrión;
                                            // No respondas primero al chat grupal para evitar hacer“Se hizo clic pero no hubo respuesta.”entrada falsa.
                                            else -> Unit
                                        }
                                    },
                                )
                            }
                        }
                        // Agent Mientras responde"pensando..."Estado
                        if (state.isAgentResponding) {
                            item(key = "thinking_indicator") {
                                ThinkingIndicator(currentSpeaker = state.currentSpeaker)
                            }
                        }
                        // v1.x: Salida de transmisión de chat grupal — Visualización en tiempo real del contenido generado.(Será reemplazado por noticias oficiales después de ser eliminado.)
                        state.streamingContent?.takeIf { it.isNotBlank() }?.let { streaming ->
                            item(key = "streaming_content") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = MusePaddings.screen, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = state.currentSpeaker?.name
                                            ?: stringResource(R.string.groupchat_streaming_title),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                                    )
                                    Surface(
                                        shape = MuseShapes.assistantBubble,
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    ) {
                                        Text(
                                            text = streaming,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(MusePaddings.cardInner),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Diálogo de lista de miembros
            if (showMembersDialog) {
                val chat = state.currentChat
                val memberIds = remember(chat) {
                    chat?.let { viewModel.parseMemberIds(it) } ?: emptyList()
                }
                val members = remember(memberIds, state.assistants) {
                    memberIds.mapNotNull { id -> state.assistants.find { it.id == id } }
                }
                MembersDialog(
                    memberNames = members.map { it.name },
                    memberCount = memberIds.size,
                    onDismiss = { showMembersDialog = false },
                )
            }

            // Menú más(panel de herramientas)
            if (showToolSheet) {
                GroupChatToolSheet(
                    onPickImage = { imageLauncher.launch("image/*") },
                    onPickDocument = {
                        runCatching {
                            documentLauncher.launch(arrayOf("text/*", "application/pdf"))
                        }.onFailure {
                            Logger.w("GroupChatDetail", "El selector de archivos no pudo iniciarse", it)
                        }
                    },
                    onInsertKnowledge = {
                        // v1.112: Inserte la marca @ al final del cuadro de entrada,El usuario completa manualmente el nombre del documento.
                        val current = state.inputText
                        val prefix = if (current.isBlank() || current.endsWith(" ") || current.endsWith("\n")) "" else " "
                        viewModel.updateInput("$current$prefix@")
                    },
                    onPickPromptTemplate = { showPromptTemplateSheet = true },
                    // v1.0.72: Parámetros del área de medios
                    hasGalleryPermission = hasGalleryPermission,
                    onRequestGalleryPermission = { galleryPermissionLauncher.launch(galleryPermission) },
                    onPickGalleryImage = { uri -> loadUriToPending(uri) },
                    onCaptureImage = { startCameraCapture() },
                    onDismiss = { showToolSheet = false },
                )
            }

            // v2.x: Panel de operación de conferencia — votar/Resumen/@/miembro/contexto/Editar(Entrada rápida promocionada desde el menú plus)
            if (showMeetingSheet) {
                GroupChatMeetingSheet(
                    onOpenMembers = { showMembersDialog = true },
                    onLaunchVote = { showVoteDialog = true },
                    onLaunchSummary = { showSummaryDialog = true },
                    onOpenContext = { showContextSheet = true },
                    onMentionMember = {
                        val current = state.inputText
                        val prefix = if (current.isBlank() || current.endsWith(" ") || current.endsWith("\n")) "" else " "
                        viewModel.updateInput("$current$prefix@")
                    },
                    onEditGroup = { showEditDialog = true },
                    onDismiss = { showMeetingSheet = false },
                )
            }

            // v1.112 (C2): Prompt Selección de plantilla sheet
            if (showPromptTemplateSheet) {
                MuseBottomSheet(onDismissRequest = { showPromptTemplateSheet = false }) {
                    Text(
                        text = stringResource(R.string.chat_prompt_templates_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // v1.114: agregar key(it.id),Cuando la lista cambia, Compose puede reutilizar combinaciones de elementos por clave.,
                        // evitar agregar/Reorganizar columnas enteras al eliminar una plantilla(PromptTemplate.id solo).
                        items(items = promptTemplates, key = { it.id }) { template ->
                            Surface(
                                onClick = {
                                    val current = state.inputText
                                    val newText = if (current.isBlank()) template.content else "$current\n\n${template.content}"
                                    viewModel.updateInput(newText)
                                    showPromptTemplateSheet = false
                                },
                                color = MaterialTheme.colorScheme.surface,
                                shape = MuseShapes.semiLarge,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(MusePaddings.itemGap)) {
                                    Text(
                                        text = template.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (template.category.isNotBlank()) {
                                        Text(
                                            text = template.category,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                MuseCapsuleButton(
                    text = stringResource(R.string.prompt_template_manage_entry),
                    onClick = {
                        showPromptTemplateSheet = false
                        onOpenPromptTemplateManager()
                    },
                    variant = IosCapsuleButtonVariant.Text,
                )
            }

            // v1.97: Editar diálogo de chat grupal(Cambiar nombre/cambiar miembros)
            if (showEditDialog) {
                val chat = state.currentChat
                val initialMemberIds = remember(chat) {
                    chat?.let { viewModel.parseMemberIds(it) } ?: emptyList()
                }
                EditGroupChatDialog(
                    dialogKey = chatId,
                    initialName = chat?.name ?: "",
                    // Mantenga visibles a los miembros discapacitados en los chats grupales históricos,Sin embargo, no se permiten agregar asistentes que no estén autorizados a unirse al chat grupal o que hayan sido deshabilitados.
                    assistants = state.assistants.filter { (it.allowGroupChat && it.enabled) || it.id in initialMemberIds },
                    initialMemberIds = initialMemberIds,
                    initialDiscussionMode = chat?.discussionMode ?: "round_robin",
                    initialAutoMaxRounds = chat?.autoMaxRounds ?: 5,
                    initialHostId = chat?.hostId,
                    initialReplyLengthMode = chat?.replyLengthMode ?: "standard",
                    initialObserverIds = chat?.let { viewModel.parseObserverIds(it) } ?: emptyList(),
                    onDismiss = { showEditDialog = false },
                    onConfirm = { newName, newMemberIds, newMode, newMaxRounds, newHostId, newReplyLengthMode, newObserverIds ->
                        viewModel.updateChat(
                            chatId = chatId,
                            name = newName,
                            memberIds = newMemberIds,
                            discussionMode = newMode,
                            autoMaxRounds = newMaxRounds,
                            hostId = newHostId,
                            replyLengthMode = newReplyLengthMode,
                            observerIds = newObserverIds,
                        )
                        showEditDialog = false
                    },
                )
            }

            // v1.0.72: Cuadro de diálogo de búsqueda de chat grupal(Menú de tres puntos → Buscar)
            if (showSearchDialog) {
                var query by rememberSaveable { mutableStateOf("") }
                // Búsqueda anti-vibración:Disparo después de detener la entrada durante 300 ms
                LaunchedEffect(query) {
                    delay(300)
                    viewModel.searchMessages(query)
                }
                MuseDialog(
                    onDismissRequest = {
                        showSearchDialog = false
                        viewModel.searchMessages("")
                    },
                    title = stringResource(R.string.groupchat_search),
                    content = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            MuseTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.groupchat_search_hint)) },
                                singleLine = true,
                            )
                            Spacer(Modifier.height(MusePaddings.contentGap))
                            if (state.isSearching) {
                                Box(modifier = Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                    MuseSpinner(
                                        size = 24.dp,
                                    )
                                }
                            } else if (query.isNotBlank() && state.searchResults.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.groupchat_search_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    textAlign = TextAlign.Center,
                                )
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 320.dp)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    state.searchResults.forEach { result ->
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    showSearchDialog = false
                                                    viewModel.searchMessages("")
                                                    // Saltar a este mensaje(Desplácese hasta la posición correspondiente)
                                                    viewModel.jumpToMessage(result.id)
                                                }
                                                .padding(vertical = 8.dp),
                                        ) {
                                            Text(
                                                text = result.senderName.ifBlank {
                                                    stringResource(
                                                        R.string.groupchat_sender_user,
                                                    )
                                                },
                                                // Correcciones en la interfaz (i18n-1)
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            Text(
                                                text = result.body,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                    }
                                }
                            }
                        }
                    },
                    confirmText = stringResource(R.string.action_close),
                    onConfirm = {
                        showSearchDialog = false
                        viewModel.searchMessages("")
                    },
                    dismissText = stringResource(R.string.groupchat_cancel),
                    onDismiss = {
                        showSearchDialog = false
                        viewModel.searchMessages("")
                    },
                )
            }

            // v1.0.72: Proveedor de asistente de edición — dos pasos:elegir miembros → ModelSwitchSheet(Guardar en el asistente)
            if (showProviderDialog) {
                MuseDialog(
                    onDismissRequest = { showProviderDialog = false },
                    title = stringResource(R.string.groupchat_edit_provider_title),
                    content = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            state.assistants.forEach { assistant ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showProviderDialog = false
                                            providerTargetAssistantId = assistant.id
                                        }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = MuseIcons.user,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(MusePaddings.contentGap))
                                    Text(
                                        text = assistant.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(
                                        imageVector = MuseIcons.sparkle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    },
                    confirmText = stringResource(R.string.action_close),
                    onConfirm = { showProviderDialog = false },
                    dismissText = stringResource(R.string.groupchat_cancel),
                    onDismiss = { showProviderDialog = false },
                )
            }

            // Después de seleccionar miembros:Abra ModelSwitchSheet para configurar el proveedor del miembro./modelo
            providerTargetAssistantId?.let { assistantId ->
                val target = state.assistants.firstOrNull { it.id == assistantId }
                if (target != null) {
                    // Leer proveedor actual/modelo(Prefiere la configuración por asistente del asistente,De lo contrario, recurra al nivel global.)
                    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
                    val activeProviderId by settings.activeProviderIdFlow.collectAsStateWithLifecycle(initialValue = null)
                    val selectedModelId by settings.selectedModelIdFlow.collectAsStateWithLifecycle(initialValue = null)
                    ModelSwitchSheet(
                        providers = providers,
                        activeProviderId = target.providerId ?: activeProviderId,
                        selectedModelId = target.modelId ?: selectedModelId,
                        onPickProvider = { providerId ->
                            // Cambiar de proveedor:guardar ahora providerId(Modelo retrasado)
                            viewModel.updateAssistantModel(assistantId, providerId, target.modelId)
                        },
                        onPickModel = { modelId ->
                            viewModel.updateAssistantModel(
                                assistantId,
                                target.providerId ?: activeProviderId,
                                modelId,
                            )
                        },
                        onRefreshModels = { providerId ->
                            // Actualizar lista de modelos(Las escenas de chat grupal no se actualizan de forma independiente por el momento.)
                        },
                        isFetchingModels = false,
                        fetchModelsError = null,
                        onDismiss = { providerTargetAssistantId = null },
                    )
                }
            }

            // v2.x: Menú de operación de pulsación larga de mensaje — burbuja de mensaje de anclaje,Ya no utilices una máscara de pantalla completa para centrar la ventana emergente.
            messageMenuTarget?.let { msg ->
                MusePopover(
                    anchorBounds = messageMenuBounds,
                    gapDp = 8,
                    anchorPointInWindow = messageMenuPointInWindow,
                    onDismiss = { messageMenuTarget = null },
                ) {
                    val scheme = MaterialTheme.colorScheme
                    val textColor = scheme.onSurface
                    val iconBlock = scheme.surfaceVariant
                    val divider = scheme.outlineVariant
                    Surface(
                        color = scheme.surface,
                        shape = MuseShapes.extraLarge,
                        shadowElevation = 8.dp,
                        tonalElevation = 0.dp,
                        modifier = Modifier.widthIn(min = 200.dp, max = 260.dp),
                    ) {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                text = if (msg.senderType == "user") stringResource(R.string.groupchat_my_message) else msg.senderName,
                                style = MaterialTheme.typography.labelMedium,
                                color = textColor.copy(alpha = 0.6f),
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                            )
                            GroupChatActionRow(MuseIcons.copy, stringResource(R.string.groupchat_copy), textColor, iconBlock) {
                                messageMenuTarget = null
                                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("message", msg.body))
                                io.zer0.muse.ui.common.feedback.MuseToast.show(context.getString(R.string.groupchat_copied))
                            }
                            GroupChatActionRow(MuseIcons.chat, stringResource(R.string.groupchat_reply), textColor, iconBlock) {
                                messageMenuTarget = null
                                replyToMessage = msg
                            }
                            GroupChatActionRow(MuseIcons.square, stringResource(R.string.groupchat_select), textColor, iconBlock) {
                                messageMenuTarget = null
                                viewModel.startSelection(msg.id)
                            }
                            // AI noticias → Regenerar/Susurrar
                            if (msg.senderType == "assistant") {
                                GroupChatActionRow(MuseIcons.refresh, stringResource(R.string.groupchat_regenerate), textColor, iconBlock) {
                                    messageMenuTarget = null
                                    viewModel.regenerateAgentMessage(msg.senderId)
                                }
                                GroupChatActionRow(MuseIcons.eye, stringResource(R.string.groupchat_whisper), textColor, iconBlock) {
                                    messageMenuTarget = null
                                    whisperTarget = msg
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .height(0.5.dp)
                                    .background(divider),
                            )
                            GroupChatActionRow(
                                MuseIcons.trash,
                                stringResource(R.string.groupchat_delete),
                                MaterialTheme.colorScheme.error,
                                iconBlock,
                            ) {
                                messageMenuTarget = null
                                deleteMessageTarget = msg
                            }
                        }
                    }
                }
            }

            // v1.77: Eliminar mensaje de confirmación
            deleteMessageTarget?.let { msg ->
                MuseDialog(
                    onDismissRequest = { deleteMessageTarget = null },
                    title = stringResource(R.string.groupchat_delete_message_title),
                    content = {
                        Text(
                            text = stringResource(R.string.groupchat_delete_message_confirm),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    confirmText = stringResource(R.string.groupchat_delete),
                    onConfirm = {
                        deleteMessageTarget = null
                        viewModel.deleteMessage(msg.id)
                    },
                    dismissText = stringResource(R.string.groupchat_cancel),
                    onDismiss = { deleteMessageTarget = null },
                )
            }

            // v1.x: Confirmación de eliminación de lotes de selección múltiple
            if (deleteSelectedTarget) {
                MuseDialog(
                    onDismissRequest = { deleteSelectedTarget = false },
                    title = stringResource(R.string.groupchat_delete_selected_title, state.selectedMessageIds.size),
                    content = {
                        Text(
                            text = stringResource(R.string.groupchat_delete_selected_confirm),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    confirmText = stringResource(R.string.groupchat_delete),
                    onConfirm = {
                        deleteSelectedTarget = false
                        viewModel.deleteSelectedMessages()
                    },
                    dismissText = stringResource(R.string.groupchat_cancel),
                    onDismiss = { deleteSelectedTarget = false },
                )
            }

            // v2.x: Diálogo de respuesta a cotización
            replyToMessage?.let { msg ->
                // Correcciones en la interfaz (persistencia-10): Cita del borrador de respuesta rememberSaveable,Girar sin perder el contenido de entrada
                var replyText by rememberSaveable { mutableStateOf("") }
                MuseDialog(
                    onDismissRequest = { replyToMessage = null },
                    title = stringResource(R.string.groupchat_reply),
                    content = {
                        Column {
                            Text(
                                text = stringResource(R.string.groupchat_reply_quote, msg.senderName, msg.body.take(80)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            )
                            MuseTextField(
                                value = replyText,
                                onValueChange = { replyText = it },
                                label = { Text(stringResource(R.string.groupchat_reply_hint)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                    confirmText = stringResource(R.string.groupchat_send),
                    onConfirm = {
                        if (replyText.isNotBlank()) {
                            viewModel.sendMessage(replyText.trim())
                            replyToMessage = null
                        }
                    },
                    dismissText = stringResource(R.string.groupchat_cancel),
                    onDismiss = { replyToMessage = null },
                )
            }

            // v2.x: cuadro de diálogo de mensaje privado
            whisperTarget?.let { target ->
                var whisperText by remember { mutableStateOf("") }
                MuseDialog(
                    onDismissRequest = { whisperTarget = null },
                    title = stringResource(R.string.groupchat_whisper_to, target.senderName),
                    content = {
                        Column {
                            Text(
                                text = stringResource(R.string.groupchat_whisper_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            )
                            MuseTextField(
                                value = whisperText,
                                onValueChange = { whisperText = it },
                                label = { Text(stringResource(R.string.groupchat_whisper_hint)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                    confirmText = stringResource(R.string.groupchat_send),
                    onConfirm = {
                        if (whisperText.isNotBlank()) {
                            viewModel.sendWhisper(target.senderId, whisperText.trim())
                            whisperTarget = null
                        }
                    },
                    dismissText = stringResource(R.string.groupchat_cancel),
                    onDismiss = { whisperTarget = null },
                )
            }

            // v2.x: Iniciar diálogo de votación
            if (showVoteDialog) {
                var voteTopic by remember { mutableStateOf("") }
                MuseDialog(
                    onDismissRequest = { showVoteDialog = false },
                    title = stringResource(R.string.groupchat_vote_title),
                    content = {
                        MuseTextField(
                            value = voteTopic,
                            onValueChange = { voteTopic = it },
                            label = { Text(stringResource(R.string.groupchat_vote_topic_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    confirmText = stringResource(R.string.groupchat_vote),
                    onConfirm = {
                        if (voteTopic.isNotBlank()) {
                            viewModel.launchVote(voteTopic.trim())
                            showVoteDialog = false
                        }
                    },
                    dismissText = stringResource(R.string.groupchat_cancel),
                    onDismiss = { showVoteDialog = false },
                )
            }

            // v2.x: Diálogo de resumen(Elija un resumidor)
            if (showSummaryDialog) {
                MuseDialog(
                    onDismissRequest = { showSummaryDialog = false },
                    title = stringResource(R.string.groupchat_summary_title),
                    content = {
                        Column {
                            Text(
                                text = stringResource(R.string.groupchat_summary_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            )
                            val memberIdsOfChat =
                                state.currentChat?.let { c -> viewModel.parseMemberIds(c) } ?: emptyList()
                            state.assistants.filter { it.id in memberIdsOfChat }
                                .forEach { assistant ->
                                    MuseCapsuleButton(
                                        text = assistant.name,
                                        onClick = {
                                            viewModel.launchSummary(assistant.id)
                                            showSummaryDialog = false
                                        },
                                        variant = IosCapsuleButtonVariant.Text,
                                        fillWidth = false,
                                    )
                                }
                            // El primer miembro se utiliza por defecto.
                            MuseCapsuleButton(
                                text = stringResource(R.string.groupchat_summary_auto),
                                onClick = {
                                    viewModel.launchSummary(null)
                                    showSummaryDialog = false
                                },
                                variant = IosCapsuleButtonVariant.Text,
                                fillWidth = false,
                            )
                        }
                    },
                    onConfirm = null,
                    dismissText = stringResource(R.string.groupchat_cancel),
                    onDismiss = { showSummaryDialog = false },
                )
            }

            // v2.x: Gestión del contexto del chat grupal sheet(Documentos compartidos en grupo + contexto exclusivo de IA)
            if (showContextSheet) {
                val chat = state.currentChat
                val sharedDocs = remember(chat) {
                    chat?.let { viewModel.parseSharedDocs(it) } ?: emptyList()
                }
                val privateContextMap = remember(chat) {
                    chat?.let { viewModel.parseMemberPrivateContext(it) } ?: emptyMap()
                }
                val memberIds = remember(chat) {
                    chat?.let { viewModel.parseMemberIds(it) } ?: emptyList()
                }
                val members = remember(memberIds, state.assistants) {
                    memberIds.mapNotNull { id -> state.assistants.find { it.id == id } }
                }
                // Nuevos campos de entrada de documentos
                var newDocTitle by remember { mutableStateOf("") }
                var newDocContent by remember { mutableStateOf("") }
                // Contexto específico del miembro en la edición actual(assistantId -> Editar texto chino)
                val editingContexts = remember { mutableStateOf<Map<String, String>>(emptyMap()) }

                MuseBottomSheet(onDismissRequest = { showContextSheet = false }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // ── Documentos compartidos en grupo ──
                        Text(
                            text = stringResource(R.string.groupchat_context_shared_docs),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.groupchat_context_shared_docs_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (sharedDocs.isEmpty()) {
                            Text(
                                text = stringResource(R.string.groupchat_context_no_docs),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        } else {
                            sharedDocs.forEach { doc ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = MuseShapes.semiLarge,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(modifier = Modifier.padding(MusePaddings.itemGap)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(
                                                text = doc.title,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Medium,
                                                // LAYOUT-01: El título del documento toma el ancho restante + límite a 2 líneas,Evite que el botón de recuento y eliminación de palabras se comprima en una columna de la derecha.
                                                modifier = Modifier.weight(1f),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = stringResource(R.string.groupchat_context_doc_chars, doc.content.length),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.outline,
                                                // LAYOUT-01: El límite de recuento de palabras es 1 línea.,No participe en la indulgencia.
                                                maxLines = 1,
                                            )
                                            MuseTactileButton(
                                                icon = MuseIcons.x,
                                                onClick = { viewModel.removeSharedDoc(doc.id) },
                                                contentDescription = stringResource(R.string.groupchat_delete),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // Agregar nuevo formulario de documento
                        MuseTextField(
                            value = newDocTitle,
                            onValueChange = { newDocTitle = it },
                            label = { Text(stringResource(R.string.groupchat_context_doc_title)) },
                            placeholder = { Text(stringResource(R.string.groupchat_context_doc_title_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        MuseTextField(
                            value = newDocContent,
                            onValueChange = { newDocContent = it },
                            label = { Text(stringResource(R.string.groupchat_context_doc_content)) },
                            placeholder = { Text(stringResource(R.string.groupchat_context_doc_content_hint)) },
                            minLines = 3,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        MuseCapsuleButton(
                            text = stringResource(R.string.groupchat_context_add_doc),
                            onClick = {
                                if (newDocTitle.isNotBlank() && newDocContent.isNotBlank()) {
                                    viewModel.addSharedDoc(newDocTitle, newDocContent)
                                    newDocTitle = ""
                                    newDocContent = ""
                                }
                            },
                            enabled = newDocTitle.isNotBlank() && newDocContent.isNotBlank(),
                            variant = IosCapsuleButtonVariant.Text,
                            fillWidth = false,
                            modifier = Modifier.align(Alignment.End),
                        )

                        // divisor
                        Surface(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp),
                        ) {}

                        // ── AI Contexto exclusivo ──
                        Text(
                            text = stringResource(R.string.groupchat_context_private_context),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.groupchat_context_private_context_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (members.isEmpty()) {
                            Text(
                                text = stringResource(R.string.groupchat_no_members),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            members.forEach { assistant ->
                                val savedText = privateContextMap[assistant.id] ?: ""
                                val editingText = editingContexts.value[assistant.id] ?: savedText
                                val isEditing = editingContexts.value.containsKey(assistant.id)
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = MuseShapes.semiLarge,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(modifier = Modifier.padding(MusePaddings.itemGap)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(
                                                text = assistant.name,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Medium,
                                                // LAYOUT-01: El nombre del miembro toma el ancho restante + límite de fila.,Evite ser "preparado/El estado "No establecido" está alineado con el botón de edición.
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (!isEditing) {
                                                Text(
                                                    text = stringResource(
                                                        if (savedText.isNotBlank()) {
                                                            R.string.groupchat_context_private_context_set
                                                        } else {
                                                            R.string.groupchat_context_private_context_empty
                                                        },
                                                    ),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.outline,
                                                )
                                                MuseCapsuleButton(
                                                    text = stringResource(R.string.groupchat_edit),
                                                    onClick = {
                                                        editingContexts.value = editingContexts.value + (assistant.id to savedText)
                                                    },
                                                    variant = IosCapsuleButtonVariant.Text,
                                                    fillWidth = false,
                                                )
                                            }
                                        }
                                        if (isEditing) {
                                            MuseTextField(
                                                value = editingText,
                                                onValueChange = { newText ->
                                                    editingContexts.value = editingContexts.value + (assistant.id to newText)
                                                },
                                                label = { Text(stringResource(R.string.groupchat_context_private_context_hint)) },
                                                minLines = 2,
                                                maxLines = 6,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 8.dp),
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.End,
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                MuseCapsuleButton(
                                                    text = stringResource(R.string.groupchat_context_clear),
                                                    onClick = {
                                                        // Limpiar el contexto exclusivo del miembro
                                                        viewModel.setMemberPrivateContext(assistant.id, "")
                                                        editingContexts.value = editingContexts.value - assistant.id
                                                    },
                                                    variant = IosCapsuleButtonVariant.Text,
                                                    fillWidth = false,
                                                )
                                                MuseCapsuleButton(
                                                    text = stringResource(R.string.groupchat_context_save),
                                                    onClick = {
                                                        viewModel.setMemberPrivateContext(assistant.id, editingText)
                                                        editingContexts.value = editingContexts.value - assistant.id
                                                    },
                                                    variant = IosCapsuleButtonVariant.Text,
                                                    fillWidth = false,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } // CompositionLocalProvider(Fuente de cristal a nivel de página)
    } // fondo Box(v1.0.74 Fondo de chat personalizado)
}

/** v1.0.72: Línea de menú de pulsación larga del chat grupal(Coincidencia de color fija:Bloque base de iconos + texto). */
@Composable
private fun GroupChatActionRow(icon: ImageVector, text: String, textColor: Color, iconBlockColor: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(9.dp),
            color = iconBlockColor,
            modifier = Modifier.size(30.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = text,
                    modifier = Modifier.size(16.dp),
                    tint = textColor,
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
        )
    }
}
