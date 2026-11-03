package io.zer0.muse.ui

import android.content.ClipData
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.common.FileSizeFormatter
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseFloatingButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.common.state.MuseLoadingState
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.workspace.WorkspaceManager
import io.zer0.muse.workspace.WorkspaceMount
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P2-7: Interfaz de usuario del administrador de archivos del espacio de trabajo.
 *
 * estructura:
 *  - arriba [MuseTopBar]:camino actual(visualización del directorio raíz"espacio de trabajo",Los subdirectorios muestran el nombre del directorio actual + regresan al nivel anterior)
 *  - medio [LazyColumn]:directorio primero/El archivo está detrás,cada uno [WorkspaceManager.WorkspaceEntry]
 *    Haga clic en el directorio para ingresar al subdirectorio.;Haga clic en el archivo para que aparezca [MuseDialog] Mostrar contenido;
 *    Mantenga presionado para reproducir [MuseDialog] Menú de operación(Eliminar/renombrar/copiar ruta)
 *  - abajo [MuseFloatingButton]:aparecer"Nuevo"cuadro de diálogo
 *    (Nuevo archivo/Nueva carpeta Elige uno de los dos)
 *  - Visualización de directorio vacío [MuseEmptyState]
 *
 * La seguridad del camino es proporcionada por [WorkspaceManager.resolveSafe] Garantía estricta,UI Sólo se muestran los resultados devueltos.
 *
 * @param onBack devolución de llamada(Inyectado por NavHost,Haga clic en la flecha de retorno de la barra superior para activar)
 */
@Composable
fun WorkspaceScreen(
    onBack: () -> Unit,
    /** v2.0.1: Abra el centro de productos (entrada de la barra superior). */
    onOpenArtifacts: () -> Unit = {},
) {
    val workspaceManager: WorkspaceManager = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val widthClass = rememberWindowWidthClass()

    // Ruta relativa al directorio actual(cadena vacía = directorio raíz)
    // Correcciones en la interfaz (persistencia-1): currentPath cambiar rememberSaveable,rotar/La reconstrucción del proceso no pierde el directorio actual.
    var currentPath by rememberSaveable { mutableStateOf("") }
    // Lista de entradas en el directorio actual
    // Correcciones en la interfaz (persistencia-1): WorkspaceEntry Para tipos complejos personalizados,La lista no puede ser directamente saveable,mantener remember(Después de la reconstrucción por reload() recargar)
    var entries by remember { mutableStateOf<List<WorkspaceManager.WorkspaceEntry>>(emptyList()) }
    var isLoading by rememberSaveable { mutableStateOf(false) }
    var isImporting by rememberSaveable { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // Estado emergente
    // Correcciones en la interfaz (persistencia-1): Los objetivos emergentes son todos tipos de objetos complejos(WorkspaceEntry?/CreateType?),no puede directamente saveable,mantener remember;
    // La ventana emergente es un estado interactivo temporal.,El apagado automático después de la reconstrucción del proceso es aceptable
    // Ver ventana emergente de contenido del archivo
    var viewingEntry by remember { mutableStateOf<WorkspaceManager.WorkspaceEntry?>(null) }
    // v2.0.1: PDF Destino de vista previa del documento (pdf.js renderizado a pantalla completa)
    var docPreviewEntry by remember { mutableStateOf<WorkspaceManager.WorkspaceEntry?>(null) }
    var viewingContent by remember { mutableStateOf<String?>(null) }
    var viewingLoading by rememberSaveable { mutableStateOf(false) }
    // Ventana emergente del menú de operación de pulsación larga
    var menuEntry by remember { mutableStateOf<WorkspaceManager.WorkspaceEntry?>(null) }
    // Eliminar ventana emergente de confirmación
    var deleteEntry by remember { mutableStateOf<WorkspaceManager.WorkspaceEntry?>(null) }
    // Cambiar nombre de ventana emergente
    var renameEntry by remember { mutableStateOf<WorkspaceManager.WorkspaceEntry?>(null) }
    // Nueva ventana emergente(Tipo:Archivo/Carpeta)
    var createDialog by remember { mutableStateOf<CreateType?>(null) }
    // Tabla de montaje y ventana emergente de gestión de montaje
    var mounts by remember { mutableStateOf<List<WorkspaceMount>>(emptyList()) }
    var mountSheetOpen by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    /**
     * Vuelva a cargar el contenido del directorio actual.
     * Se llama cada vez que se ingresa a un subdirectorio/agrega, elimina o modifica,Asegúrese de que la interfaz de usuario sea coherente con el sistema de archivos.
     */
    fun reload() {
        scope.launch {
            isLoading = true
            errorMsg = null
            when (val r = workspaceManager.listDir(currentPath)) {
                is WorkspaceManager.ListResult.Success -> {
                    entries = r.entries
                    errorMsg = null
                }
                is WorkspaceManager.ListResult.Error -> {
                    entries = emptyList()
                    errorMsg = r.message
                }
            }
            isLoading = false
        }
    }

    val importFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            // Directorio de destino fijo después de que regresa el selector de archivos,Esto evita que los usuarios cambien de directorio durante la copia y provoquen que los archivos caigan en la ubicación incorrecta.
            val targetDirectory = currentPath
            scope.launch {
                isImporting = true
                var importedCount = 0
                val failures = mutableListOf<String>()
                uris.forEach { uri ->
                    when (val result = workspaceManager.importFile(uri, targetDirectory)) {
                        is WorkspaceManager.FileImportResult.Success -> importedCount++
                        is WorkspaceManager.FileImportResult.Error -> failures += result.message
                    }
                }
                isImporting = false
                reload()
                if (failures.isEmpty()) {
                    MuseToast.show(context.getString(R.string.workspace_import_success, importedCount))
                } else {
                    MuseToast.show(
                        context.getString(
                            R.string.workspace_import_failed,
                            failures.first().take(120),
                        ),
                    )
                }
            }
        }
    }

    // Tabla de montaje: lea una vez al ingresar a la página y actualice después de agregar y eliminar.
    LaunchedEffect(Unit) {
        mounts = workspaceManager.mounts()
    }

    val knownMountIds = remember(mounts) { mounts.mapTo(mutableSetOf()) { it.id } }
    val currentMountId = remember(currentPath, knownMountIds) {
        val prefix = currentPath.substringBefore(':', "")
        if (prefix.isNotEmpty() && prefix in knownMountIds) prefix else WorkspaceMount.MAIN_ID
    }
    val currentMount = mounts.firstOrNull { it.id == currentMountId }
    val currentReadOnly = currentMount?.isReadOnly == true

    // Seleccione un directorio como nuevo montaje: obtenga la autorización persistente y regístrese nuevamente.
    val mountDirectoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val granted = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
                true
            }.getOrDefault(false)
            val label = mountLabelFromUri(uri)
            val mount = WorkspaceMount(
                id = nextMountId(label, workspaceManager.mounts().mapTo(mutableSetOf()) { it.id }),
                label = label,
                provider = io.zer0.muse.workspace.MountProvider.SAF,
                treeUri = uri.toString(),
                capabilities = if (granted) {
                    WorkspaceMount.DEFAULT_CAPABILITIES
                } else {
                    WorkspaceMount.READ_ONLY_CAPABILITIES
                },
                createdAt = System.currentTimeMillis(),
            )
            when (val r = workspaceManager.upsertMount(mount)) {
                is io.zer0.muse.workspace.WorkspaceMountStore.MountStoreResult.Success -> {
                    mounts = r.mounts
                    MuseToast.show(context.getString(R.string.workspace_mount_added, label))
                }
                is io.zer0.muse.workspace.WorkspaceMountStore.MountStoreResult.Error -> {
                    MuseToast.show(context.getString(R.string.workspace_mount_failed, r.message))
                }
            }
        }
    }

    // Reparación de auditoría (3.1): Recargar al ingresar a la página/cambiar de directorio.
    // La lógica de carga está directamente incorporada LaunchedEffect,currentPath Cuando se produzcan cambios, la antigua rutina se cancelará automáticamente.,
    // Evite que el listDir inicial sobrescriba el resultado de la carga del nuevo directorio después de regresar tarde.
    LaunchedEffect(currentPath) {
        isLoading = true
        errorMsg = null
        when (val r = workspaceManager.listDir(currentPath)) {
            is WorkspaceManager.ListResult.Success -> {
                entries = r.entries
                errorMsg = null
            }
            is WorkspaceManager.ListResult.Error -> {
                entries = emptyList()
                errorMsg = r.message
            }
        }
        isLoading = false
    }

    // Calcular el nombre para mostrar del directorio actual(directorio raíz = "espacio de trabajo",Montar raíz = Nombre de montaje,subdirectorio = Nombre del último párrafo)
    val currentName = when {
        currentPath.isEmpty() -> stringResource(R.string.workspace_title)
        currentPath.endsWith(":/") || currentPath.endsWith(":") ->
            currentMount?.label ?: stringResource(R.string.workspace_title)
        else -> currentPath.substringAfterLast('/')
    }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = currentName,
                onBack = if (currentPath.isEmpty()) onBack else ({ currentPath = parentPath(currentPath) }),
                largeTitle = true,
                actions = {
                    MuseTactileButton(
                        icon = MuseIcons.folder,
                        onClick = { mountSheetOpen = true },
                        contentDescription = stringResource(R.string.workspace_mounts_title),
                    )
                    MuseTactileButton(
                        icon = MuseIcons.download,
                        onClick = {
                            runCatching {
                                importFilesLauncher.launch(arrayOf("*/*"))
                            }.onFailure {
                                MuseToast.show(context.getString(R.string.workspace_import_failed, it.message))
                            }
                        },
                        contentDescription = stringResource(R.string.workspace_import_file),
                        enabled = !isImporting && !currentReadOnly,
                    )
                    // v2.0.1: Entrada al centro de productos
                    MuseTactileButton(
                        icon = MuseIcons.fileText,
                        onClick = onOpenArtifacts,
                        contentDescription = stringResource(R.string.artifact_center_title),
                    )
                    // adicionalmente disponible en el subdirectorio"Volver al directorio raíz"botón de acceso directo
                    if (currentPath.isNotEmpty()) {
                        MuseTactileButton(
                            icon = MuseIcons.arrowLeft,
                            onClick = { currentPath = "" },
                            contentDescription = stringResource(R.string.workspace_title),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (!currentReadOnly) {
                MuseFloatingButton(
                    icon = MuseIcons.plus,
                    onClick = { createDialog = CreateType.FILE },
                    contentDescription = stringResource(R.string.workspace_new_file),
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                isImporting -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = innerPadding.calculateTopPadding()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            MuseSpinner()
                            Spacer(Modifier.size(MusePaddings.contentGap))
                            Text(stringResource(R.string.workspace_import_file))
                        }
                    }
                }
                isLoading && entries.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = innerPadding.calculateTopPadding()),
                        contentAlignment = Alignment.Center,
                    ) {
                        MuseLoadingState()
                    }
                }
                errorMsg != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = innerPadding.calculateTopPadding()),
                        contentAlignment = Alignment.Center,
                    ) {
                        MuseErrorStateBox(
                            message = errorMsg ?: "",
                            onRetry = { reload() },
                        )
                    }
                }
                entries.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = innerPadding.calculateTopPadding()),
                        contentAlignment = Alignment.Center,
                    ) {
                        MuseEmptyState(
                            icon = MuseIcons.folder,
                            title = stringResource(R.string.workspace_empty),
                            subtitle = stringResource(R.string.workspace_empty_subtitle),
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (widthClass == WindowWidthClass.Expanded) {
                                    Modifier.widthIn(max = 720.dp)
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = MusePaddings.screen),
                        contentPadding = PaddingValues(
                            top = innerPadding.calculateTopPadding(),
                            bottom = innerPadding.calculateBottomPadding() + MusePaddings.screen * 2,
                        ),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                    ) {
                        // Cuando el directorio actual no es la raíz,primera pantalla"Volver al nivel anterior"
                        if (currentPath.isNotEmpty()) {
                            item(key = "__parent__") {
                                ParentRow(onClick = { currentPath = parentPath(currentPath) })
                            }
                        }
                        items(entries, key = { it.relativePath }) { entry ->
                            WorkspaceEntryRow(
                                entry = entry,
                                dateFormat = dateFormat,
                                onClick = {
                                    if (entry.isDirectory) {
                                        // Ingrese al subdirectorio
                                        currentPath = entry.relativePath
                                    } else {
                                        // v2.0.1: PDF Ir a la vista previa del documento (pdf.js), y el resto siguen siendo ventanas emergentes de texto.
                                        if (entry.relativePath.endsWith(".pdf", ignoreCase = true)) {
                                            docPreviewEntry = entry
                                        } else {
                                            // Leer el contenido del archivo(Mostrar en ventana emergente)
                                            viewingEntry = entry
                                            viewingContent = null
                                            viewingLoading = true
                                            scope.launch {
                                                when (val r = workspaceManager.readFile(entry.relativePath)) {
                                                    is WorkspaceManager.ReadResult.Success -> viewingContent = r.content
                                                    is WorkspaceManager.ReadResult.Error -> viewingContent = "Error: ${r.message}"
                                                }
                                                viewingLoading = false
                                            }
                                        }
                                    }
                                },
                                onLongClick = { menuEntry = entry },
                            )
                        }
                    }
                }
            }
        }
    }

    // Ventana emergente de vista de contenido del archivo
    viewingEntry?.let { entry ->
        MuseDialog(
            onDismissRequest = {
                viewingEntry = null
                viewingContent = null
            },
            title = entry.name,
            content = {
                // Nota:No utilice interna verticalScroll(MuseDialog Se ha proporcionado un contenedor de desplazamiento en la capa exterior.,
                // Se activará el desplazamiento anidado IllegalStateException — Ver memoria del proyecto)
                when {
                    viewingLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = MusePaddings.sectionGap),
                            contentAlignment = Alignment.Center,
                        ) {
                            MuseSpinner(
                                size = MuseIconSizes.iconMedium,
                            )
                        }
                    }
                    viewingContent != null -> {
                        // El contenido de archivos largos se desplaza y se muestra utilizando el desplazamiento vertical externo de MuseDialog.
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MuseShapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ) {
                            Text(
                                text = viewingContent ?: "",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(MusePaddings.contentGap),
                            )
                        }
                    }
                }
            },
            confirmText = stringResource(R.string.common_confirm),
            onConfirm = {
                viewingEntry = null
                viewingContent = null
            },
            dismissText = null,
        )
    }

    // v2.0.1: PDF Vista previa del documento (pdf.js renderizado a pantalla completa)
    docPreviewEntry?.let { entry ->
        io.zer0.muse.ui.artifact.DocPreviewDialog(
            fileName = entry.name,
            loadBytes = { workspaceManager.readBytes(entry.relativePath) },
            onDismiss = { docPreviewEntry = null },
        )
    }

    // Ventana emergente del menú de operación de pulsación larga
    menuEntry?.let { entry ->
        MuseDialog(
            onDismissRequest = { menuEntry = null },
            title = entry.name,
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    MenuActionRow(
                        icon = MuseIcons.edit,
                        label = stringResource(R.string.workspace_rename),
                        onClick = {
                            renameEntry = entry
                            menuEntry = null
                        },
                    )
                    MenuActionRow(
                        icon = MuseIcons.copy,
                        label = stringResource(R.string.workspace_path_copied),
                        onClick = {
                            copyToClipboard(context, entry.relativePath)
                            MuseToast.show(context.getString(R.string.workspace_path_copied))
                            menuEntry = null
                        },
                    )
                    MenuActionRow(
                        icon = MuseIcons.trash,
                        label = stringResource(R.string.common_delete),
                        onClick = {
                            deleteEntry = entry
                            menuEntry = null
                        },
                        destructive = true,
                    )
                }
            },
            confirmText = stringResource(R.string.common_cancel),
            onConfirm = { menuEntry = null },
            dismissText = null,
        )
    }

    // Eliminar ventana emergente de confirmación
    deleteEntry?.let { entry ->
        MuseDialog(
            onDismissRequest = { deleteEntry = null },
            title = stringResource(R.string.common_delete),
            content = {
                Text(
                    text = stringResource(R.string.workspace_delete_confirm, entry.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                val target = entry
                deleteEntry = null
                scope.launch {
                    when (val r = workspaceManager.delete(target.relativePath)) {
                        is WorkspaceManager.OpResult.Success -> {
                            // Reparación de auditoría (8.10): uso original common_confirm("OK")Errores de redacción
                            MuseToast.show(context.getString(R.string.chat_list_deleted_toast))
                            reload()
                        }
                        is WorkspaceManager.OpResult.Error -> {
                            MuseToast.show(r.message)
                        }
                    }
                }
            },
            destructive = true,
        )
    }

    // Cambiar nombre de ventana emergente
    renameEntry?.let { entry ->
        var newName by remember(entry.relativePath) { mutableStateOf(entry.name) }
        MuseDialog(
            onDismissRequest = { renameEntry = null },
            title = stringResource(R.string.workspace_rename),
            content = {
                MuseTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
            },
            confirmText = stringResource(R.string.common_confirm),
            onConfirm = {
                val name = newName.trim()
                // El nombre está vacío o contiene caracteres no válidos(/ o ..)Aparecen mensajes emergentes de vez en cuando y no cierra la ventana emergente
                if (name.isEmpty() || name.contains('/') || name.contains("..")) {
                    MuseToast.show(context.getString(R.string.workspace_file_name))
                    return@MuseDialog
                }
                // Cierra la ventana emergente sin cambiar el nombre.,No ejecutado IO
                if (name == entry.name) {
                    renameEntry = null
                    return@MuseDialog
                }
                val to = if (entry.relativePath.contains('/')) {
                    entry.relativePath.substringBeforeLast('/') + "/" + name
                } else {
                    name
                }
                val target = entry
                renameEntry = null
                scope.launch {
                    when (val r = workspaceManager.move(target.relativePath, to)) {
                        is WorkspaceManager.OpResult.Success -> reload()
                        is WorkspaceManager.OpResult.Error -> MuseToast.show(r.message)
                    }
                }
            },
        )
    }

    // Nueva ventana emergente de archivo/carpeta
    if (mountSheetOpen) {
        MuseDialog(
            onDismissRequest = { mountSheetOpen = false },
            title = stringResource(R.string.workspace_mounts_title),
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    mounts.forEach { mount ->
                        val selected = mount.id == currentMountId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MuseShapes.medium)
                                .clickable {
                                    currentPath = if (mount.id == WorkspaceMount.MAIN_ID) "" else mount.id + ":/"
                                    mountSheetOpen = false
                                }
                                .padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.contentGap),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                        ) {
                            Text(
                                text = mount.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f),
                            )
                            if (mount.isReadOnly) {
                                Text(
                                    text = stringResource(R.string.workspace_mount_read_only),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!mount.system) {
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        scope.launch {
                                            workspaceManager.removeMount(mount.id)
                                            mounts = workspaceManager.mounts()
                                            if (currentMountId == mount.id) currentPath = ""
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.workspace_mount_remove))
                                }
                            }
                        }
                    }
                    androidx.compose.material3.TextButton(
                        onClick = {
                            mountSheetOpen = false
                            runCatching { mountDirectoryLauncher.launch(null) }
                                .onFailure {
                                    MuseToast.show(
                                        context.getString(R.string.workspace_mount_failed, it.message),
                                    )
                                }
                        },
                    ) {
                        Text(stringResource(R.string.workspace_mount_add))
                    }
                }
            },
            confirmText = stringResource(R.string.common_close),
            onConfirm = { mountSheetOpen = false },
        )
    }

    createDialog?.let { type ->
        var newName by remember { mutableStateOf("") }
        val titleRes = when (type) {
            CreateType.FILE -> R.string.workspace_new_file
            CreateType.FOLDER -> R.string.workspace_new_folder
        }
        MuseDialog(
            onDismissRequest = { createDialog = null },
            title = stringResource(titleRes),
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    MuseTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        placeholder = {
                            Text(text = stringResource(R.string.workspace_file_name))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                    // botón de acceso directo:En la ventana emergente de nuevo archivo, cambie a"Crear nueva carpeta"operaciones de acceso directo
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    ) {
                        QuickSwitchChip(
                            icon = MuseIcons.file,
                            label = stringResource(R.string.workspace_new_file),
                            selected = type == CreateType.FILE,
                            onClick = { createDialog = CreateType.FILE },
                        )
                        QuickSwitchChip(
                            icon = MuseIcons.folderPlus,
                            label = stringResource(R.string.workspace_new_folder),
                            selected = type == CreateType.FOLDER,
                            onClick = { createDialog = CreateType.FOLDER },
                        )
                    }
                }
            },
            confirmText = stringResource(R.string.common_confirm),
            onConfirm = {
                val name = newName.trim()
                if (name.isEmpty() || name.contains('/') || name.contains("..")) {
                    MuseToast.show(context.getString(R.string.workspace_file_name))
                    return@MuseDialog
                }
                val target = if (currentPath.isEmpty()) name else "$currentPath/$name"
                val t = type
                createDialog = null
                scope.launch {
                    val result = when (t) {
                        CreateType.FILE -> workspaceManager.writeFile(target, "")
                        CreateType.FOLDER -> workspaceManager.mkdir(target)
                    }
                    when (result) {
                        is WorkspaceManager.OpResult.Success -> reload()
                        is WorkspaceManager.OpResult.Error -> MuseToast.show(result.message)
                    }
                }
            },
        )
    }
}

// ── Componentes y funciones auxiliares internos ──────────────────────────────────────────────────────

/**
 * Nuevo tipo(Archivo/Carpeta).
 */
private enum class CreateType { FILE, FOLDER }

/**
 * Calcule la ruta relativa del directorio principal. El padre del directorio raíz sigue siendo la cadena vacía.
 */
private fun parentPath(path: String): String {
    if (path.isEmpty()) return ""
    // Cuando se proporciona un prefijo de montaje, solo retrocede un nivel dentro del montaje y luego regresa a la raíz del montaje para regresar al espacio de trabajo predeterminado.
    val prefixEnd = path.indexOf(":/")
    if (prefixEnd >= 0) {
        val rest = path.substring(prefixEnd + 2)
        if (rest.isEmpty()) return ""
        val cut = rest.lastIndexOf('/')
        val prefix = path.substring(0, prefixEnd)
        return if (cut < 0) "$prefix:/" else "$prefix:/${rest.substring(0, cut)}"
    }
    val idx = path.lastIndexOf('/')
    return if (idx < 0) "" else path.substring(0, idx)
}

/**
 * Obtenga un nombre de directorio legible por humanos del URI de autorización.
 *
 * El último segmento del URI del directorio se parece a `primary:DCIM/Camera`, tome el último nivel después de los dos puntos.
 */
private fun mountLabelFromUri(uri: android.net.Uri): String {
    val raw = uri.lastPathSegment?.substringAfterLast(':')?.trim().orEmpty()
    val name = raw.substringAfterLast('/').trim()
    return name.ifEmpty { "Mounted" }
}

/**
 * Genere una ID de montaje legal y única a partir del nombre del directorio.
 *
 * En el logotipo solo se permiten letras minúsculas, números, guiones bajos y guiones, y debe comenzar con una letra.
 */
private fun nextMountId(label: String, taken: Set<String>): String {
    val base = label.lowercase()
        .map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
        .joinToString("")
        .trim('_')
        .ifEmpty { "mount" }
    val normalized = if (base.first() in 'a'..'z') base else "m$base"
    val stem = normalized.take(24)
    if (stem !in taken) return stem
    var index = 1
    while (true) {
        val candidate = "${stem.take(20)}_$index"
        if (candidate !in taken) return candidate
        index++
    }
}

/**
 * Copiar texto al portapapeles del sistema(usado para"copiar ruta"Operación).
 */
private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
        as android.content.ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("workspace_path", text))
}

/**
 * "Volver al nivel anterior"OK(Sólo se muestra en subdirectorios).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParentRow(onClick: () -> Unit) {
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MuseIcons.arrowLeft,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
            Spacer(Modifier.size(MusePaddings.contentGap))
            Text(
                text = "..",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Fila de entrada del espacio de trabajo(directorio/archivo).
 *
 * Haga clic para ingresar a un subdirectorio o ver el contenido del archivo;Mantenga presionado para que aparezca el menú de operación.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceEntryRow(
    entry: WorkspaceManager.WorkspaceEntry,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // icono de tipo(Directorio/Archivo)colocado sobre un fondo circular,Mejorar la jerarquía visual
            val icon = if (entry.isDirectory) MuseIcons.folder else MuseIcons.fileText
            val iconTint = if (entry.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Surface(
                shape = CircleShape,
                color = iconTint.copy(alpha = 0.12f),
                modifier = Modifier.size(MuseIconSizes.touchTarget),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                }
            }
            Spacer(Modifier.size(MusePaddings.contentGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(MusePaddings.tightGap))
                Text(
                    text = if (entry.isDirectory) {
                        dateFormat.format(Date(entry.lastModified))
                    } else {
                        "${formatSize(entry.size)} · ${dateFormat.format(Date(entry.lastModified))}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Los tipos de archivos muestran iconos de edición adicionales,Solicitar ver
            if (!entry.isDirectory) {
                MuseTactileButton(
                    icon = MuseIcons.edit,
                    onClick = onClick,
                    contentDescription = stringResource(R.string.action_edit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Mantenga presionada una fila de acción única en el menú.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MenuActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.contentGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
            Spacer(Modifier.size(MusePaddings.contentGap))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Crear una nueva ventana emergente"Archivo/carpeta"Cambie rápidamente de cápsulas.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickSwitchChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MuseShapes.pill,
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        modifier = Modifier
            .combinedClickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MusePaddings.contentGap),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
            Spacer(Modifier.size(MusePaddings.tightGap))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Número de bytes formateados en una cadena legible por humanos(B/KB/MB).
 */
private fun formatSize(bytes: Long): String = FileSizeFormatter.format(bytes)
