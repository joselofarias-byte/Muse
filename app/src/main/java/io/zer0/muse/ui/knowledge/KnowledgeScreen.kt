@file:Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod", "TooManyFunctions", "ThrowsCount")

package io.zer0.muse.ui.knowledge

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.FileSizeFormatter
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import io.zer0.muse.rag.KnowledgeFolderBrowser
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseFloatingButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.museAnimateItem
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.settings.SettingField
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.statusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// L-KUI1: Límite de truncamiento de contenido,Evite que los documentos de gran tamaño causen OOM
private const val MAX_CONTENT_LENGTH = 500_000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeScreen(
    onBack: () -> Unit,
    dao: KnowledgeDocDao = koinInject(),
    ragService: io.zer0.muse.rag.RagService = koinInject(),
    documentParser: io.zer0.muse.doc.DocumentParser = koinInject(),
    ocrManager: io.zer0.muse.doc.OcrManager = koinInject(),
    settings: io.zer0.muse.data.SettingsRepository = koinInject(),
    // F-31: base de conocimientos DAO,para listar"base de conocimientos objetivo"para la selección de importación
    kbDao: io.zer0.muse.data.knowledge.KnowledgeBaseDao = koinInject(),
    // v2.4.6: carpeta DAO — Permitir que las carpetas vacías existan de forma independiente,y servir como unidad de aislamiento de recuperación
    folderDao: io.zer0.muse.data.knowledge.KnowledgeFolderDao = koinInject(),
    // v1.0.53: Devolución de llamada de enrutamiento de biblioteca de portada(Detalles del documento → Página de gestión de portadas)
    onOpenCoverManager: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // v1.0.74 fix (Auditoría inicial 6.5): Buscar 300 ms anti-vibración — Originales remember(searchQuery) Cada carácter escrito
    // Reiniciar consulta de flujo de sala,Ingrese el retraso. Utilice snapshotFlow+debounce para controlar los términos de consulta reales.
    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        snapshotFlow { searchQuery }
            .debounce(300)
            .collect { debouncedQuery = it }
    }
    // v1.66: Cambio de clasificación de la base de conocimientos(Originalmente solo se hizo clic updated_at DESC,Ahora admite 4 tipos de clasificación)
    var sortMode by remember { mutableStateOf(KnowledgeSortMode.UPDATED) }
    // v1.0.62: Botón de actualización desplegable,El incremento automático reconstruirá el flujo DAO y activará una nueva consulta.
    var refreshKey by remember { mutableStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    // v1.48: h13 Al cargar por primera vez, utilice nulo para mostrar el estado de carga.;Retención de escena de búsqueda emptyList,Evite el estado de carga flash cada vez
    // M-KB1: Escape de los caracteres comodín LIKE al realizar una búsqueda(% _ \),Compatible con DAO ESCAPE '\' cláusula
    // usar observeAllUser() Excluir a nivel de base de datos is_internal=true documentación de desarrollo interno,Evite la exposición de los usuarios.
    // v1.0.74 fix (Auditoría inicial 6.5): Utilice la consulta debouncedQuery para impulsar la consulta.
    // ST-01: DAO Flow Agregar captura de error + reintento.loadError independiente de produceState,
    // producer Reinicie solo cuando cambie retryKey,Evite escribir loadError en el productor para desencadenar un bucle infinito de reorganización.
    var docsRetryKey by remember { mutableStateOf(0) }
    var docsLoadError by remember { mutableStateOf<String?>(null) }
    val docs by produceState<List<KnowledgeDocEntity>?>(
        if (debouncedQuery.isBlank()) null else emptyList(),
        debouncedQuery,
        refreshKey,
        docsRetryKey,
    ) {
        value = if (debouncedQuery.isBlank()) null else emptyList()
        try {
            if (debouncedQuery.isBlank()) {
                dao.observeAllUser().collect { value = it }
            } else {
                dao.search(
                    io.zer0.muse.data.knowledge.KnowledgeDocDao.escapeLikeQuery(debouncedQuery),
                ).collect { value = it }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            docsLoadError = e.message ?: context.getString(R.string.common_load_failed)
        }
    }
    // v1.0.62: Muestra el indicador brevemente al tirar hacia abajo para actualizar,Proporcionar comentarios perceptibles para volver a consultar
    LaunchedEffect(refreshKey) {
        if (refreshKey > 0) {
            isRefreshing = true
            delay(500)
            isRefreshing = false
        }
    }
    var detailTarget by remember { mutableStateOf<KnowledgeDocEntity?>(null) }
    // F-32: Destino de cambio de nombre/edición de texto del documento
    var renameTarget by remember { mutableStateOf<KnowledgeDocEntity?>(null) }
    var editContentTarget by remember { mutableStateOf<KnowledgeDocEntity?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf("") }
    // v1.67-B: La importación se puede cancelar — Guardar referencia de trabajo,Limpiar documentos de trabajo en curso tras la cancelación
    var importJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // v1.67-A: Estado de reindexación
    var reindexing by remember { mutableStateOf(false) }
    var reindexDialogVisible by remember { mutableStateOf(false) }
    var reindexProgress by remember { mutableStateOf("") }
    var reindexTarget by remember { mutableStateOf<KnowledgeDocEntity?>(null) }
    // v1.0.53: AI Estado de generación de portada
    var generatingCover by remember { mutableStateOf(false) }
    val coverGenerator: io.zer0.muse.tools.CoverGenerator = koinInject()
    // v1.0.47 P7-3: Consejos amigables para archivos que son demasiado grandes
    var fileSizeWarning by remember { mutableStateOf<Pair<String, String>?>(null) }

    // F-31: Importar base de conocimientos de destino(cadena vacía = Base de conocimientos predeterminada). Elige el objetivo primero KB,Seleccione el archivo nuevamente.
    var importTargetKbId by rememberSaveable { mutableStateOf("") }
    var importTargetFolderPath by rememberSaveable { mutableStateOf("") }
    var browsingKbId by rememberSaveable { mutableStateOf<String?>(null) }
    var currentFolderPath by rememberSaveable { mutableStateOf("") }
    var moveFolderTarget by remember { mutableStateOf<KnowledgeDocEntity?>(null) }
    var showImportTargetDialog by rememberSaveable { mutableStateOf(false) }
    val kbs by kbDao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    // v2.4.6: carpeta explícita(Contiene carpetas vacías). Registros de carpeta en la KB explorada actualmente.
    val explicitFolders by produceState<List<io.zer0.muse.data.knowledge.KnowledgeFolderEntity>>(
        emptyList(),
        browsingKbId,
    ) {
        val kb = browsingKbId
        if (kb == null) {
            value = emptyList()
        } else {
            folderDao.observeByKb(kb).collect { value = it }
        }
    }

    // v2.4.6: Ciclo de vida de la base de conocimientos.(KB Nuevo/Cambiar nombre/Eliminar/Reindexar) — Fusionado desde la página de administración
    var creatingKb by remember { mutableStateOf(false) }
    var editingKb by remember { mutableStateOf<io.zer0.muse.data.knowledge.KnowledgeBaseEntity?>(null) }
    var deletingKb by remember { mutableStateOf<io.zer0.muse.data.knowledge.KnowledgeBaseEntity?>(null) }
    var reindexConfirmKb by remember { mutableStateOf<io.zer0.muse.data.knowledge.KnowledgeBaseEntity?>(null) }
    var kbReindexDialogVisible by remember { mutableStateOf(false) }
    var kbReindexProgressPair by remember { mutableStateOf(0 to 0) }
    // v2.4.6: Ciclo de vida de la carpeta(Nuevo/Cambiar nombre/Eliminar)
    var creatingFolder by remember { mutableStateOf(false) }
    var renamingFolder by remember { mutableStateOf<String?>(null) }
    var deletingFolder by remember { mutableStateOf<String?>(null) }

    // F-33: embedding Detección de cambio de modelo — La clave de configuración actual es la misma que"El índice más reciente utilizado key"Solicitar volver a indexar cuando sea inconsistente
    val currentRagConfig by settings.ragConfigFlow.collectAsStateWithLifecycle(initialValue = io.zer0.muse.rag.RagConfig())
    val lastEmbedKey by settings.lastEmbeddingModelKeyFlow.collectAsStateWithLifecycle(initialValue = null)
    val embeddingMigrated = remember(lastEmbedKey, currentRagConfig) {
        lastEmbedKey != null && lastEmbedKey != io.zer0.muse.rag.RagConfig.embeddingModelKey(currentRagConfig)
    }

    // v1.54: Soporte para importar txt/md/pdf/docx/epub/images(OCR),Dividido automáticamente en trozos después de la importación+Generar índice de vector de incrustación
    // Importación de un solo archivo extraído como función de suspensión local:Regresar true = El archivo fue importado exitosamente,false = Salir temprano(no lideró).
    // Estado global de la interfaz de usuario(importing / importProgress / importJob)El final lo gestiona de manera uniforme la persona que llama.
    //
    // ReturnCount Razones de la supresión:Aquí hay varias rutas fallidas importadas archivo por archivo (el archivo excede el límite, el análisis falla, el contenido está vacío,
    // El número de bloques es 0 y no se pueden encontrar después de colocarlos en la biblioteca.……), correspondiendo cada uno de ellos a un motivo real que debe ser informado al usuario.
    // Dividirlo en una docena de funciones pequeñas cortará el proceso de "importación única" en partes ilegibles y hará que sea más fácil pasar por alto la limpieza de productos semiacabados.
    @Suppress("ReturnCount")
    suspend fun importOneDocument(uri: Uri, targetKbId: String, targetFolderPath: String?): Boolean {
        val folderPath = targetFolderPath.orEmpty()
        importProgress = context.getString(R.string.knowledge_reading_file)
        val now = System.currentTimeMillis()
        var createdDocId: String? = null
        return try {
            // L-KUI2: Consíguelo con ContentResolver DISPLAY_NAME,Más confiable que lastPathSegment
            val fileName = withContext(Dispatchers.IO) {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                } ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast('%')
            } ?: "doc-$now"
            val lowerName = fileName.lowercase()
            val archiveFormat = ArchiveFormat.fromFileName(lowerName)
            if (archiveFormat != null) {
                importArchiveBundle(
                    archiveUri = uri,
                    archiveName = fileName,
                    format = archiveFormat,
                    deps = ArchiveImportDeps(
                        context = context,
                        kbDao = kbDao,
                        docDao = dao,
                        ragService = ragService,
                        settings = settings,
                        documentParser = documentParser,
                        onProgress = { importProgress = it },
                    ),
                )
                return true
            }
            // v1.0.47 P7-3: documento analizado/Las imágenes todavía están limitadas a 50 MB; El texto sin formato está sujeto a indexación de transmisión.
            val maxFileSizeBytes = 50L * 1024 * 1024
            val fileSize = withContext(Dispatchers.IO) {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getLong(idx) else -1L
                } ?: -1L
            }
            val isParsedDoc = lowerName.endsWith(".pdf") || lowerName.endsWith(".docx") ||
                lowerName.endsWith(".doc") || lowerName.endsWith(".epub") ||
                lowerName.endsWith(".pptx")
            val isImage = lowerName.endsWith(".png") || lowerName.endsWith(".jpg") ||
                lowerName.endsWith(".jpeg") || lowerName.endsWith(".bmp") ||
                lowerName.endsWith(".webp")
            val isTextCandidate = !isParsedDoc && !isImage
            val unsupportedTextCandidate =
                isTextCandidate &&
                    (KnowledgeArchivePolicy.isArchiveFile(lowerName) || looksBinary(uri, context))
            if (fileSize > maxFileSizeBytes && (!isTextCandidate || unsupportedTextCandidate)) {
                fileSizeWarning = formatFileSize(fileSize) to formatFileSize(maxFileSizeBytes)
                return false
            }
            val parserConfig = try {
                settings.getRagConfig()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                Logger.w("KnowledgeScreen", "No se pudo leer la configuración de RAG: ${e.message}")
                io.zer0.muse.rag.RagConfig()
            }
            if (isTextCandidate && !unsupportedTextCandidate) {
                val docId = "doc-$now"
                createdDocId = docId
                val fileType = when {
                    lowerName.endsWith(".md") || lowerName.endsWith(".markdown") -> "md"
                    lowerName.endsWith(".csv") -> "csv"
                    lowerName.endsWith(".json") -> "json"
                    else -> "txt"
                }
                val preview = StringBuilder()
                val digest = MessageDigest.getInstance("SHA-256")
                val textFlow = flow {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException(context.getString(R.string.knowledge_import_failed_read))
                    input.use { stream ->
                        stream.bufferedReader().use { reader ->
                            val buffer = CharArray(64 * 1024)
                            var totalChars = 0L
                            while (true) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                totalChars += count
                                if (totalChars > maxFileSizeBytes) {
                                    error("Archivo demasiado grande,exceder ${maxFileSizeBytes / 1024 / 1024}MB Límite")
                                }
                                if (preview.length < MAX_CONTENT_LENGTH) {
                                    preview.append(
                                        buffer,
                                        0,
                                        minOf(count, MAX_CONTENT_LENGTH - preview.length),
                                    )
                                }
                                val piece = String(buffer, 0, count)
                                digest.update(piece.toByteArray(Charsets.UTF_8))
                                emit(piece)
                            }
                        }
                    }
                }.flowOn(Dispatchers.IO)
                dao.upsert(
                    KnowledgeFolderBrowser.withFolderPath(
                        KnowledgeDocEntity(
                            id = docId,
                            title = fileName,
                            content = "",
                            filePath = uri.toString(),
                            fileType = fileType,
                            createdAt = now,
                            updatedAt = now,
                            kbId = targetKbId,
                        ),
                        folderPath,
                    ),
                )
                importProgress = context.getString(R.string.knowledge_chunking)
                val chunkCount = ragService.indexDocumentStreamed(
                    docId = docId,
                    textPieces = textFlow,
                    ragConfig = parserConfig,
                    onProgress = { indexed ->
                        importProgress = context.getString(R.string.knowledge_import_indexed_chunks, indexed)
                    },
                )
                if (chunkCount <= 0) {
                    resultOf { ragService.deleteDocument(docId) }
                    MuseToast.show(context.getString(R.string.knowledge_import_empty))
                    return false
                }
                dao.upsert(
                    (dao.getById(docId) ?: return false).copy(
                        content = preview.toString(),
                        chunkCount = chunkCount,
                        embeddingModel = io.zer0.muse.rag.RagConfig.embeddingModelKey(parserConfig),
                        contentHash = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                scope.launch { settings.saveLastEmbeddingModelKey(io.zer0.muse.rag.RagConfig.embeddingModelKey(parserConfig)) }
                browsingKbId = targetKbId
                currentFolderPath = folderPath
                MuseToast.show(context.getString(R.string.knowledge_imported_indexed, fileName, chunkCount))
                return true
            }
            // Seleccione el método de análisis según la extensión.
            val content = when {
                isParsedDoc -> {
                    importProgress = context.getString(R.string.knowledge_parsing_doc)
                    val parsed = withContext(Dispatchers.IO) {
                        documentParser.parseResult(
                            uri,
                            context,
                            parserConfig.documentParserType,
                            parserConfig.cloudParserEndpoint,
                            parserConfig.mineruEndpoint,
                            parserConfig.mineruToken,
                        )
                    }
                    if (parsed is io.zer0.common.Result.Error) {
                        MuseToast.show(parsed.message)
                        return false
                    }
                    val text = parsed.getOrNull()
                    if (text.isNullOrBlank()) {
                        MuseToast.show(context.getString(R.string.knowledge_import_empty))
                        return false
                    }
                    text
                }
                isImage -> {
                    importProgress = context.getString(R.string.knowledge_ocr)
                    ocrManager.recognize(uri, context)
                }
                else -> {
                    // El resto no debe leerse como texto: paquete comprimido/El binario ha sido rechazado arriba.
                    // v2.3.1: paquete comprimido/guardia binaria — con página de administración(F-31)Calibre unificado. Antes no había ningún guardia en la página principal.,
                    // El binario se leerá como texto. → Escriba caracteres confusos en la biblioteca y cree índices vectoriales(Recuperación silenciosa de la contaminación,2026-09 retroalimentación)
                    MuseToast.show(context.getString(R.string.knowledge_import_unsupported))
                    return false
                }
            }
            if (content.isNullOrBlank()) {
                MuseToast.show(context.getString(R.string.knowledge_import_empty))
                return false
            }
            val fileType = when {
                lowerName.endsWith(".md") || lowerName.endsWith(".markdown") -> "md"
                lowerName.endsWith(".pdf") -> "pdf"
                lowerName.endsWith(".docx") || lowerName.endsWith(".doc") -> "docx"
                lowerName.endsWith(".epub") -> "epub"
                lowerName.endsWith(".csv") -> "csv"
                lowerName.endsWith(".json") -> "json"
                lowerName.endsWith(".png") || lowerName.endsWith(".jpg") ||
                    lowerName.endsWith(".jpeg") -> "ocr"
                else -> "txt"
            }
            val docId = "doc-$now"
            createdDocId = docId
            // L-KUI1: Extraer constantes,Avisos de registro cuando se trunca
            val truncatedContent = if (content.length > MAX_CONTENT_LENGTH) {
                Logger.w("KnowledgeScreen", "El contenido excede $MAX_CONTENT_LENGTH personaje,truncado(Originales ${content.length} palabra)")
                content.take(MAX_CONTENT_LENGTH)
            } else {
                content
            }
            dao.upsert(
                KnowledgeDocEntity(
                    id = docId,
                    title = fileName,
                    content = truncatedContent,
                    filePath = uri.toString(),
                    fileType = fileType,
                    createdAt = now,
                    updatedAt = now,
                    // F-31: Escribir en la base de conocimientos de destino seleccionada al importar(cadena vacía → Base de conocimientos predeterminada)
                    kbId = targetKbId,
                ).let { KnowledgeFolderBrowser.withFolderPath(it, folderPath) },
            )
            // v1.54: Fragmentación automática + generación de índice de vectores de incrustación
            importProgress = context.getString(R.string.knowledge_chunking)
            val ragConfig = parserConfig
            // v1.103: No más excepciones para tragar silenciosamente.runCatching Pon las razones cuando fallas toast,
            // Informar a los usuarios por qué no se creó el índice(red/Nombre del modelo/Provider Incompatibles etc),en lugar de simplemente ver"Importado pero no indexado".
            var indexError: Throwable? = null
            var indexErrorMsg: String? = null
            val indexResult = resultOf {
                ragService.indexDocumentStreamed(
                    docId = docId,
                    textPieces = kotlinx.coroutines.flow.flow {
                        content.chunked(io.zer0.muse.rag.RagService.STREAM_WINDOW_CHARS).forEach { emit(it) }
                    },
                    ragConfig = ragConfig,
                )
            }.onError { msg, t ->
                indexErrorMsg = msg
                indexError = t
            }
            val chunkCount = indexResult.getOrNull()
            if (chunkCount != null && chunkCount > 0) {
                // Actualizar el número de fragmentos y el modelo de incrustación del documento.
                val modelName = ragConfig.cloudModel.ifBlank { "cloud-default" }
                dao.upsert(
                    (dao.getById(docId) ?: return false).copy(
                        chunkCount = chunkCount,
                        embeddingModel = modelName,
                        // Hash the complete extracted text, not the DB preview, so reindexing can
                        // distinguish truncated content instead of silently treating it as canonical.
                        contentHash = io.zer0.muse.rag.RagService.computeContentHash(content),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                // F-33: Registre la configuración de incrustación utilizada en este índice.,para detección de traspaso
                scope.launch { settings.saveLastEmbeddingModelKey(io.zer0.muse.rag.RagConfig.embeddingModelKey(ragConfig)) }
                browsingKbId = targetKbId
                currentFolderPath = folderPath
                MuseToast.show(context.getString(R.string.knowledge_imported_indexed, fileName, chunkCount))
            } else {
                // v1.103: Mostrar motivos específicos cuando falla la indexación,Dirija al usuario a la configuración de RAG para verificar la configuración
                val reason = indexErrorMsg?.take(100)
                val msg = if (reason.isNullOrBlank()) {
                    context.getString(R.string.knowledge_imported_no_index, fileName)
                } else {
                    "${context.getString(R.string.knowledge_imported_no_index, fileName)}\n" +
                        context.getString(R.string.knowledge_index_error_reason, reason)
                }
                MuseToast.show(msg)
                Logger.w("KnowledgeScreen", "indexDocument failed for $fileName", indexError)
            }
            // documento analizado/Ruta de la imagen: vaya aquí para indicar que el archivo se ha colocado en la biblioteca.(La indexación o no está informada por el brindis anterior)
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            // v1.67-B: El usuario cancela la importación,Limpiar documentos semiacabados + fragmentos generados
            createdDocId?.let { id ->
                resultOf { ragService.deleteDocument(id) }
            }
            MuseToast.show(context.getString(R.string.knowledge_import_cancelled))
            throw e
        } catch (e: java.io.IOException) {
            MuseToast.show(context.getString(R.string.knowledge_import_failed_read))
            false
        } catch (e: Exception) {
            MuseToast.show(context.getString(R.string.knowledge_import_failed, e.message?.take(80) ?: ""))
            false
        }
    }

    // v2.6.x: La importación admite múltiples lotes de selección. base de conocimientos objetivo/Las carpetas se calculan antes del inicio.,Evite recálculos archivo por archivo.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val targetKbId = importTargetKbId.ifBlank { "default" }
        val targetFolderPath = importTargetFolderPath
        // v1.67-B: Guardar referencia de trabajo para cancelación(La cancelación interrumpirá todo el ciclo del lote.)
        importJob = scope.launch {
            importing = true
            var succeeded = 0
            var failed = 0
            try {
                uris.forEachIndexed { index, uri ->
                    importProgress = context.getString(R.string.knowledge_import_batch_progress, index + 1, uris.size)
                    persistKnowledgeUriReadPermission(context, uri)
                    if (importOneDocument(uri, targetKbId, targetFolderPath)) succeeded++ else failed++
                }
            } finally {
                importing = false
                importProgress = ""
                importJob = null
                importTargetFolderPath = ""
            }
            // Mantener la experiencia original cuando solo se selecciona un archivo,Resumen de no jugar
            if (uris.size > 1) {
                MuseToast.show(context.getString(R.string.knowledge_import_batch_done, succeeded, failed))
            }
        }
    }

    /**
     * Restaure el contenido original para la reindexación de un solo documento.
     *
     * documento grande [KnowledgeDocEntity.content] Sólo una vista previa;content:// / file:// La fuente aún está disponible.
     * La relectura, las entradas internas del paquete comprimido y otras fuentes irrecuperables permitirán a RagService dar una razón clara del error.
     */
    suspend fun originalDocumentContent(doc: KnowledgeDocEntity, ragConfig: io.zer0.muse.rag.RagConfig): Flow<String>? {
        val path = doc.filePath
        if (!path.startsWith("content://") && !path.startsWith("file://")) return null
        val uri = path.toUri()
        return when (doc.fileType.lowercase(Locale.ROOT)) {
            "pdf", "docx", "epub", "pptx" -> flow {
                val parsed = documentParser
                    .parseResult(
                        uri,
                        context,
                        ragConfig.documentParserType,
                        ragConfig.cloudParserEndpoint,
                        ragConfig.mineruEndpoint,
                        ragConfig.mineruToken,
                    )
                    .getOrNull()
                    .orEmpty()
                if (parsed.isNotBlank()) emit(parsed)
            }.flowOn(Dispatchers.IO)

            "ocr" -> flow {
                val recognized = ocrManager.recognize(uri, context)
                if (recognized.isNotBlank()) emit(recognized)
            }.flowOn(Dispatchers.IO)

            else -> flow {
                val input = context.contentResolver.openInputStream(uri)
                    ?: error(context.getString(R.string.knowledge_source_unavailable))
                input.use { stream ->
                    stream.bufferedReader().use { reader ->
                        val buffer = CharArray(64 * 1024)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            if (count > 0) emit(String(buffer, 0, count))
                        }
                    }
                }
            }.flowOn(Dispatchers.IO)
        }
    }

    // v1.67-A: Reindexar documentos existentes(Disponible después de reemplazar el modelo de incrustación o cuando falla la indexación)
    fun reindexDoc(doc: KnowledgeDocEntity) {
        reindexTarget = doc
        reindexing = true
        reindexDialogVisible = true
        reindexProgress = context.getString(R.string.knowledge_reindex_progress)
        scope.launch {
            try {
                // M-KUI1: Quitar runCatching,Cambiar a try-catch y volver a lanzar CancellationException
                val ragConfig = try {
                    settings.getRagConfig()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: java.io.IOException) {
                    Logger.w("KnowledgeScreen", "No se pudo leer la configuración de RAG: ${e.message}")
                    io.zer0.muse.rag.RagConfig()
                }
                val chunkCount = ragService.reindexDocument(
                    docId = doc.id,
                    ragConfig = ragConfig,
                    sourceContentProvider = { sourceDoc ->
                        originalDocumentContent(sourceDoc, ragConfig)
                    },
                )
                if (chunkCount > 0) {
                    // F-33: Actualizar después de reindexar"La configuración de incrustación utilizada en el índice más reciente."
                    scope.launch { settings.saveLastEmbeddingModelKey(io.zer0.muse.rag.RagConfig.embeddingModelKey(ragConfig)) }
                    MuseToast.show(context.getString(R.string.knowledge_reindexed, doc.title, chunkCount))
                } else {
                    MuseToast.show(context.getString(R.string.knowledge_reindex_failed, doc.title))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                MuseToast.show(context.getString(R.string.knowledge_reindex_failed, e.message?.take(80) ?: ""))
            } finally {
                reindexing = false
                reindexDialogVisible = false
                reindexProgress = ""
                reindexTarget = null
            }
        }
    }

    // R-UI-01: Reparación/Reconstruir el índice FTS(Limpiar la reconstrucción de la tabla de sombras + volver a indexar completamente el documento actual)
    fun repairKnowledgeFts() {
        reindexTarget = null
        reindexing = true
        reindexDialogVisible = true
        reindexProgress = context.getString(R.string.knowledge_repairing)
        scope.launch {
            try {
                val ragConfig = try {
                    settings.getRagConfig()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: java.io.IOException) {
                    Logger.w("KnowledgeScreen", "No se pudo leer la configuración de RAG: ${e.message}")
                    io.zer0.muse.rag.RagConfig()
                }
                val failures = ragService.repairKnowledgeFtsIndex(ragConfig) { current, total ->
                    reindexProgress = context.getString(R.string.knowledge_generating_vector, current, total)
                }
                // F-33: Actualización después de completar la reindexación completa"La configuración de incrustación utilizada en el índice más reciente.",eliminar"Se recomienda volver a indexar"advertencia temprana
                scope.launch { settings.saveLastEmbeddingModelKey(io.zer0.muse.rag.RagConfig.embeddingModelKey(ragConfig)) }
                if (failures.isEmpty()) {
                    MuseToast.show(context.getString(R.string.knowledge_fts_repaired))
                } else {
                    // P2-11 Reparación (informe de auditoría): La implementación anterior solo solicitaba "N "El artículo falló" sin decir el motivo.
                    // Los errores reales (como HTTP 402 Saldo insuficiente) solo van a logcat y el usuario no tiene forma de actuar.
                    // Los dos primeros motivos se muestran después de la agregación y la deduplicación.
                    val reasons = failures.values
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .take(2)
                        .joinToString(";")
                        .take(160)
                    val msg = if (reasons.isBlank()) {
                        context.getString(R.string.knowledge_fts_repaired_partial, failures.size)
                    } else {
                        context.getString(R.string.knowledge_fts_repaired_partial_with_reason, failures.size, reasons)
                    }
                    MuseToast.show(msg)
                    Logger.w("KnowledgeScreen", "La reindexación falló ${failures.size} Capítulo, razón: $reasons")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                MuseToast.show(context.getString(R.string.knowledge_fts_repair_failed, e.message?.take(80) ?: ""))
            } finally {
                reindexing = false
                reindexDialogVisible = false
                reindexProgress = ""
                reindexTarget = null
            }
        }
    }

    /**
     * v1.0.53: AI Genere una portada y escriba la portada del documento.
     *
     * proceso: CoverGenerator Imágenes sin procesar almacenadas en la base de datos. → FrontmatterParser.withCover escribir de nuevo →
     * dao.upsert persistencia → Actualiza la lista. No se pudo generar el mensaje Toast,Sin estatus residual.
     */
    fun generateCoverFor(doc: KnowledgeDocEntity) {
        if (generatingCover) return
        generatingCover = true
        scope.launch {
            try {
                var failureMsg: String? = null
                val item = coverGenerator.generateCover(
                    title = doc.title,
                    description = doc.content.take(200),
                ).onError { msg, _ -> failureMsg = msg }.getOrNull()
                if (item == null) {
                    MuseToast.show(
                        context.getString(
                            if (failureMsg?.contains("Modelo de dibujo no configurado") == true) {
                                R.string.cover_generate_no_provider
                            } else {
                                R.string.cover_generate_failed
                            },
                            failureMsg?.take(60) ?: "",
                        ),
                    )
                    return@launch
                }
                // escribir de nuevo frontmatter(covers/ ruta relativa)
                val newContent = io.zer0.muse.common.markdown.FrontmatterParser.withCover(
                    doc.content,
                    "covers/${item.fileName}",
                )
                val sourceWasComplete = doc.contentHash.isNotBlank() &&
                    doc.contentHash == io.zer0.muse.rag.RagService.computeContentHash(doc.content)
                dao.upsert(
                    doc.copy(
                        content = newContent,
                        // Do not turn a preview into a false claim that it contains the full source.
                        contentHash = if (sourceWasComplete) {
                            io.zer0.muse.rag.RagService.computeContentHash(newContent)
                        } else {
                            doc.contentHash
                        },
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                MuseToast.show(context.getString(R.string.cover_applied))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                MuseToast.show(context.getString(R.string.cover_generate_failed, e.message?.take(60) ?: ""))
            } finally {
                generatingCover = false
            }
        }
    }

    // v2.5.0 fix: El gesto de retorno del sistema y la tecla de retorno de la barra superior salen al mismo nivel. —— Buscar > Carpeta un nivel arriba > KB lista > Salga de la base de conocimientos.
    // carpeta anterior/KB No hay ningún BackHandler conectado a la vista y el gesto del sistema sale directamente de toda la página de la base de conocimientos.
    BackHandler(enabled = true) {
        when {
            searchQuery.isNotBlank() -> searchQuery = ""
            currentFolderPath.isNotBlank() -> currentFolderPath = KnowledgeFolderBrowser.parent(currentFolderPath)
            browsingKbId != null -> browsingKbId = null
            else -> onBack()
        }
    }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.knowledge_title),
                onBack = {
                    when {
                        searchQuery.isNotBlank() -> searchQuery = ""
                        currentFolderPath.isNotBlank() -> currentFolderPath = KnowledgeFolderBrowser.parent(currentFolderPath)
                        browsingKbId != null -> browsingKbId = null
                        else -> onBack()
                    }
                },
                largeTitle = true,
                actions = {
                    // v2.4.6: Crear una nueva base de conocimientos(Disponible en vista superior)
                    if (browsingKbId == null) {
                        MuseTactileButton(
                            icon = MuseIcons.plus,
                            onClick = { creatingKb = true },
                            contentDescription = stringResource(R.string.kb_manage_create),
                        )
                    }
                    // v1.66: Entrada del interruptor de clasificación(ventana emergente de acción)
                    MuseTactileButton(
                        icon = MuseIcons.sort,
                        onClick = { showSortMenu = true },
                        contentDescription = stringResource(R.string.knowledge_sort),
                    )
                    MuseTactileButton(
                        icon = MuseIcons.refresh,
                        onClick = { repairKnowledgeFts() },
                        contentDescription = stringResource(R.string.knowledge_repair_index),
                        tint = if (reindexing) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        enabled = !reindexing,
                    )
                },
            )
        },
        floatingActionButton = {
            MuseFloatingButton(
                icon = MuseIcons.plus,
                // F-31: Seleccione primero la base de conocimientos de destino,Seleccionar archivo nuevamente(Agregar documentación a esta base de conocimientos)
                onClick = {
                    if (!importing) {
                        importTargetKbId = browsingKbId ?: "default"
                        importTargetFolderPath =
                            KnowledgeFolderBrowser.importFolderPath(
                                targetKbId = importTargetKbId,
                                browsingKbId = browsingKbId,
                                currentFolderPath = currentFolderPath,
                                searchQuery = searchQuery,
                            )
                        showImportTargetDialog = true
                    }
                },
                contentDescription = stringResource(R.string.knowledge_import),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = MusePaddings.screen),
        ) {
            // Barra de búsqueda ranurada(surfaceVariant Fondo + Entrada sin marco + Esquinas redondeadas + Búsqueda/Icono de borrar)
            Surface(
                shape = MuseShapes.semiLarge,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = MuseIcons.search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = stringResource(R.string.knowledge_search_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        MuseTactileButton(
                            icon = MuseIcons.x,
                            onClick = { searchQuery = "" },
                            contentDescription = stringResource(R.string.knowledge_clear),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            size = 48.dp,
                            iconSize = 18.dp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(MusePaddings.itemGap))

            // F-33: embedding Modelo cambiado → Barra de sugerencias + reindexación con un clic(Reutilice RepairKnowledgeFts y recupérelo completo)
            if (embeddingMigrated && !reindexing) {
                androidx.compose.material3.Surface(
                    shape = MuseShapes.semiLarge,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            MuseIcons.info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.knowledge_embedding_migrated),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        MuseCapsuleButton(
                            text = stringResource(R.string.knowledge_reindex),
                            onClick = { repairKnowledgeFts() },
                            enabled = !reindexing,
                            fillWidth = false,
                        )
                    }
                }
                Spacer(Modifier.height(MusePaddings.itemGap))
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { refreshKey++ },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                val docsList = docs
                // v2.x: Finalización del efecto de movimiento — Cargar/error/vacío/La conmutación de lista de cuatro estados requiere que el token aparezca y desaparezca
                val knowledgeContentState = when {
                    docsLoadError != null && docsList.isNullOrEmpty() -> 0
                    docsList == null -> 1
                    else -> 2
                }
                Crossfade(
                    targetState = knowledgeContentState,
                    animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS),
                    label = "knowledgeContent",
                    modifier = Modifier.fillMaxSize(),
                ) { kind ->
                    when (kind) {
                        0 -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                MuseErrorStateBox(
                                    message = docsLoadError.orEmpty(),
                                    onRetry = {
                                        docsLoadError = null
                                        docsRetryKey++
                                    },
                                )
                            }
                        }
                        1 -> {
                            // v1.0.62: Mostrar indicador circular en la primera carga
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                MuseSpinner()
                            }
                        }
                        else -> {
                            // v0.43: Ocultar documentación de desarrollo(isInternal=true Los documentos internos solo están disponibles para LLM para consultar a través de Knowledge_search,No se muestra a los usuarios)
                            // v1.133: Utilice el juicio de campo isInternal en su lugar(sustituto original fileType="devdoc" codificado,Coherente con la etiqueta MIGRATION_38_39)
                            // v1.66: Ordenar por modo de clasificación(DAO solo updated_at DESC,Las dimensiones restantes se ordenan en la interfaz de usuario.)
                            // M-Kn1: Utilice recordar almacenar en caché el filtro+ordenar resultados,Evite recálculos para cada reorganización
                            val visibleDocs = remember(docsList, sortMode) {
                                docsList.orEmpty()
                                    .filterNot { it.isInternal }
                                    .sortedWith(sortMode.comparator)
                            }
                            val searching = searchQuery.isNotBlank()
                            val selectedKb = browsingKbId
                            val kbDocs = remember(visibleDocs, selectedKb) {
                                if (selectedKb == null) emptyList() else visibleDocs.filter { it.kbId == selectedKb }
                            }
                            val childFolders = remember(kbDocs, currentFolderPath, searching, explicitFolders) {
                                if (selectedKb == null || searching) {
                                    emptyList()
                                } else {
                                    // v2.4.6: fusionar"Subcarpetas deducidas de documentos."con"Carpetas vacías en la tabla de carpetas explícitas",
                                    // Permite mostrar carpetas sin documentos.
                                    val fromDocs = KnowledgeFolderBrowser.childFolders(kbDocs, currentFolderPath)
                                    val parentPrefix =
                                        if (currentFolderPath.isEmpty()) "" else "$currentFolderPath/"
                                    val explicitChildren = explicitFolders.mapNotNull { f ->
                                        val p = KnowledgeFolderBrowser.normalize(f.path)
                                        if (p.isEmpty() || p == currentFolderPath) return@mapNotNull null
                                        if (!p.startsWith(parentPrefix)) return@mapNotNull null
                                        val childName = p.removePrefix(parentPrefix).substringBefore('/')
                                        if (childName.isBlank()) return@mapNotNull null
                                        val childPath =
                                            if (currentFolderPath.isEmpty()) childName else "$currentFolderPath/$childName"
                                        KnowledgeFolderBrowser.Folder(name = childName, path = childPath, documentCount = 0)
                                    }
                                    (fromDocs + explicitChildren)
                                        .distinctBy { it.path }
                                        .sortedBy { it.name.lowercase() }
                                }
                            }
                            val folderFiles = remember(kbDocs, currentFolderPath, visibleDocs, searching) {
                                if (searching) {
                                    visibleDocs
                                } else {
                                    KnowledgeFolderBrowser.documentsInFolder(kbDocs, currentFolderPath)
                                        .sortedWith(sortMode.comparator)
                                }
                            }
                            when {
                                searching && visibleDocs.isEmpty() -> MuseEmptyState(
                                    icon = MuseIcons.fileText,
                                    title = stringResource(R.string.knowledge_no_match_title),
                                    subtitle = stringResource(R.string.knowledge_no_match_subtitle),
                                )
                                !searching && selectedKb == null -> {
                                    if (kbs.isEmpty()) {
                                        MuseEmptyState(
                                            icon = MuseIcons.bookOpen,
                                            title = stringResource(R.string.knowledge_empty_title),
                                            subtitle = stringResource(R.string.knowledge_empty_subtitle),
                                        )
                                    } else {
                                        LazyColumn(
                                            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 80.dp),
                                        ) {
                                            // v2.4.6: nivel superior = Lista de la base de conocimientos(La base de conocimientos es la raíz y las carpetas son subdirectorios.)
                                            items(kbs, key = { it.id }) { kb ->
                                                Box(museAnimateItem()) {
                                                    KbRow(
                                                        kb = kb,
                                                        actions = KbRowActions(
                                                            onOpen = {
                                                                browsingKbId = kb.id
                                                                currentFolderPath = ""
                                                            },
                                                            onEdit = { editingKb = kb },
                                                            onDelete = { deletingKb = kb },
                                                            onReindex = { reindexConfirmKb = kb },
                                                            onAddDocument = {
                                                                importTargetKbId = kb.id
                                                                importTargetFolderPath = ""
                                                                showImportTargetDialog = true
                                                            },
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                !searching && childFolders.isEmpty() && folderFiles.isEmpty() -> MuseEmptyState(
                                    icon = MuseIcons.folder,
                                    title = stringResource(R.string.knowledge_empty_title),
                                    subtitle = stringResource(R.string.knowledge_empty_subtitle),
                                )
                                else -> {
                                    LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 80.dp),
                                    ) {
                                        if (!searching && selectedKb != null) {
                                            item(key = "folder-path") {
                                                val kbName = kbs.firstOrNull { it.id == selectedKb }?.name ?: selectedKb
                                                val displayPath = listOf(kbName, currentFolderPath)
                                                    .filter { it.isNotBlank() }
                                                    .joinToString(" / ")
                                                Text(
                                                    displayPath,
                                                    modifier = Modifier.padding(vertical = MusePaddings.tightGap),
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            item(key = "folder-help") {
                                                Text(
                                                    text = stringResource(R.string.knowledge_folder_browser_hint),
                                                    modifier = Modifier.padding(vertical = MusePaddings.tightGap),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            items(childFolders, key = { "folder:${it.path}" }) { folder ->
                                                Box(museAnimateItem()) {
                                                    KnowledgeFolderCard(
                                                        title = folder.name,
                                                        detail = stringResource(R.string.knowledge_folder_item_files, folder.documentCount),
                                                        onClick = { currentFolderPath = folder.path },
                                                        // v2.4.6: Cambiar nombre = Cambiar el final del camino;Eliminar = Eliminar recursivamente subrutas
                                                        onRename = { renamingFolder = folder.path },
                                                        onDelete = { deletingFolder = folder.path },
                                                    )
                                                }
                                            }
                                            // v2.4.6: Crear nueva entrada de carpeta(Colóquelo después de la subcarpeta.)
                                            item(key = "new-folder") {
                                                NewFolderRow(onClick = { creatingFolder = true })
                                            }
                                        }
                                        items(folderFiles, key = { it.id }) { doc ->
                                            Box(museAnimateItem()) {
                                                DocCard(
                                                    doc = doc,
                                                    highlight = searchQuery.takeIf { it.isNotBlank() },
                                                    onClick = { detailTarget = doc },
                                                    onDelete = {
                                                        scope.launch {
                                                            ragService.deleteDocument(doc.id)
                                                        }
                                                        MuseToast.show(context.getString(R.string.knowledge_deleted))
                                                    },
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
        }
    }

    detailTarget?.let { doc ->
        DocDetailDialog(
            doc = doc,
            onDismiss = { detailTarget = null },
            onReindex = { reindexDoc(doc) },
            onOpenCoverManager = onOpenCoverManager,
            onGenerateCover = { generateCoverFor(doc) },
            generatingCover = generatingCover,
            // F-32: Cambiar nombre/editar entrada de texto
            onRename = { renameTarget = doc },
            onEditContent = { editContentTarget = doc },
            onMoveToFolder = {
                detailTarget = null
                moveFolderTarget = doc
            },
        )
    }

    moveFolderTarget?.let { doc ->
        var destination by remember(doc.id) { mutableStateOf(KnowledgeFolderBrowser.folderPath(doc)) }
        var moveInProgress by remember(doc.id) { mutableStateOf(false) }
        MuseDialog(
            onDismissRequest = { if (!moveInProgress) moveFolderTarget = null },
            title = stringResource(R.string.knowledge_move_folder_title),
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
                    SettingField(
                        label = stringResource(R.string.knowledge_move_to_folder),
                        value = destination,
                        onValueChange = { destination = it },
                    )
                    Text(
                        text = stringResource(R.string.knowledge_move_folder_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmText = stringResource(
                if (moveInProgress) R.string.knowledge_move_saving else R.string.action_save,
            ),
            confirmEnabled = !moveInProgress,
            onConfirm = {
                val targetPath = KnowledgeFolderBrowser.normalize(destination)
                if (!moveInProgress) {
                    moveInProgress = true
                    scope.launch {
                        resultOf {
                            dao.upsert(
                                KnowledgeFolderBrowser.withFolderPath(doc, targetPath)
                                    .copy(updatedAt = System.currentTimeMillis()),
                            )
                        }.onSuccess {
                            browsingKbId = doc.kbId
                            currentFolderPath = targetPath
                            refreshKey++
                            moveFolderTarget = null
                            MuseToast.show(context.getString(R.string.knowledge_move_success))
                        }.onError { message, error ->
                            moveInProgress = false
                            Logger.w("KnowledgeScreen", "No se pudo mover el documento a la carpeta: $message", error)
                            MuseToast.show(
                                context.getString(
                                    R.string.knowledge_move_failed,
                                    message.take(80).ifBlank { context.getString(R.string.common_load_failed) },
                                ),
                            )
                        }
                    }
                }
            },
            dismissText = if (moveInProgress) null else stringResource(R.string.common_cancel),
            onDismiss = { if (!moveInProgress) moveFolderTarget = null },
        )
    }

    // F-32: Cambiar nombre del cuadro de diálogo(Guardar en documento title)
    renameTarget?.let { doc ->
        var newTitle by remember(doc.id) { mutableStateOf(doc.title.trim()) }
        MuseDialog(
            onDismissRequest = { renameTarget = null },
            title = stringResource(R.string.knowledge_rename_doc),
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
                    SettingField(
                        label = stringResource(R.string.knowledge_rename_new_title),
                        value = newTitle,
                        onValueChange = { newTitle = it },
                    )
                }
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                val trimmed = newTitle.trim()
                if (trimmed.isNotBlank() && trimmed != doc.title) {
                    scope.launch {
                        dao.upsert(doc.copy(title = trimmed, updatedAt = System.currentTimeMillis()))
                        MuseToast.show(context.getString(R.string.knowledge_renamed, trimmed))
                    }
                }
                renameTarget = null
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { renameTarget = null },
        )
    }

    // F-32: Cuadro de diálogo de edición de texto(documento de texto)— Actualizar contenido y aviso"La reindexación entra en vigor después de guardar"
    editContentTarget?.let { doc ->
        var draft by remember(doc.id) { mutableStateOf(doc.content) }
        var saving by remember(doc.id) { mutableStateOf(false) }
        MuseDialog(
            onDismissRequest = {
                if (!saving) editContentTarget = null
            },
            title = stringResource(R.string.knowledge_edit_content),
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
                    Text(
                        stringResource(R.string.knowledge_edit_content_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 320.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MuseShapes.small,
                            )
                            .padding(10.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmText = stringResource(R.string.knowledge_edit_save_reindex),
            onConfirm = {
                saving = true
                scope.launch {
                    val newContent = draft
                    dao.upsert(
                        doc.copy(
                            content = newContent,
                            // Saving the editor makes this text the new authoritative document content.
                            contentHash = io.zer0.muse.rag.RagService.computeContentHash(newContent),
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    try {
                        val ragConfig = try {
                            settings.getRagConfig()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: java.io.IOException) {
                            io.zer0.muse.rag.RagConfig()
                        }
                        val chunkCount = ragService.indexDocument(doc.id, newContent, ragConfig)
                        if (chunkCount > 0) {
                            dao.upsert(
                                (dao.getById(doc.id) ?: doc).copy(
                                    chunkCount = chunkCount,
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                        // F-33: Registre esta configuración de incrustación después de cambiar el texto y volver a recuperarlo.
                        scope.launch { settings.saveLastEmbeddingModelKey(io.zer0.muse.rag.RagConfig.embeddingModelKey(ragConfig)) }
                        MuseToast.show(context.getString(R.string.knowledge_content_edited))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        MuseToast.show(context.getString(R.string.knowledge_reindex_failed, e.message?.take(80) ?: ""))
                    } finally {
                        saving = false
                        editContentTarget = null
                    }
                }
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { if (!saving) editContentTarget = null },
        )
    }

    if (importing) {
        MuseDialog(
            // v1.67-B: La importación se puede cancelar
            onDismissRequest = { importJob?.cancel() },
            title = stringResource(R.string.knowledge_importing),
            content = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MuseSpinner(
                        size = 28.dp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        importProgress.ifBlank { stringResource(R.string.knowledge_reading_default) },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmText = stringResource(R.string.knowledge_cancel),
            onConfirm = { importJob?.cancel() },
            dismissText = null,
        )
    }

    // v1.0.47 P7-3: Ventana emergente amigable que indica que el archivo es demasiado grande
    fileSizeWarning?.let { (actual, limit) ->
        MuseDialog(
            onDismissRequest = { fileSizeWarning = null },
            title = stringResource(R.string.knowledge_file_too_large_title),
            content = {
                Text(
                    text = stringResource(R.string.knowledge_file_too_large_msg, actual, limit),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmText = stringResource(R.string.knowledge_file_too_large_confirm),
            onConfirm = { fileSizeWarning = null },
            dismissText = null,
        )
    }

    // v1.67-A: Reindexar ventana emergente de progreso
    if (reindexDialogVisible) {
        MuseDialog(
            // Lo que se cierra es solo la visualización del progreso y la tarea de reconstrucción continúa ejecutándose para evitar que la clave de retorno sea tragada por una devolución de llamada vacía.
            onDismissRequest = { reindexDialogVisible = false },
            title = stringResource(R.string.knowledge_reindexing),
            content = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MuseSpinner(
                        size = 28.dp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        reindexProgress.ifBlank { stringResource(R.string.knowledge_reindexing_default) },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            // ininterrumpible,Sin botón(onConfirm=null Ocultar botón principal,dismissText=null Ocultar botón secundario)
            onConfirm = null,
            dismissText = null,
        )
    }

    // F-31: Importar cuadro de diálogo de selección de base de conocimientos de destino — Después de seleccionar la KB de destino, inicie el selector de archivos
    if (showImportTargetDialog) {
        MuseDialog(
            onDismissRequest = { showImportTargetDialog = false },
            title = stringResource(R.string.knowledge_import_target_title),
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    Surface(
                        onClick = {
                            importTargetKbId = "default"
                            importTargetFolderPath =
                                KnowledgeFolderBrowser.importFolderPath(
                                    targetKbId = importTargetKbId,
                                    browsingKbId = browsingKbId,
                                    currentFolderPath = currentFolderPath,
                                    searchQuery = searchQuery,
                                )
                            showImportTargetDialog = false
                            importLauncher.launch(arrayOf("*/*"))
                        },
                        shape = MuseShapes.semiLarge,
                        color = if (importTargetKbId == "default" || importTargetKbId.isEmpty()) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = MusePaddings.contentGap, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.knowledge_import_target_default),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                // LAYOUT-01: Row Ancho del texto interno + límite de línea, evita el ajuste de línea palabra por palabra al ampliar el tamaño de fuente
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(MuseIcons.folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    kbs.forEach { kb ->
                        val selected = importTargetKbId == kb.id
                        Surface(
                            onClick = {
                                importTargetKbId = kb.id
                                importTargetFolderPath =
                                    KnowledgeFolderBrowser.importFolderPath(
                                        targetKbId = importTargetKbId,
                                        browsingKbId = browsingKbId,
                                        currentFolderPath = currentFolderPath,
                                        searchQuery = searchQuery,
                                    )
                                showImportTargetDialog = false
                                importLauncher.launch(arrayOf("*/*"))
                            },
                            shape = MuseShapes.semiLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = MusePaddings.contentGap, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = kb.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    // LAYOUT-01: Igual que arriba, el nombre de la base de conocimientos está limitado a 1 línea
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Icon(MuseIcons.folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmText = stringResource(R.string.knowledge_import_target_confirm),
            onConfirm = {
                importTargetKbId = importTargetKbId.ifBlank { "default" }
                importTargetFolderPath =
                    KnowledgeFolderBrowser.importFolderPath(
                        targetKbId = importTargetKbId,
                        browsingKbId = browsingKbId,
                        currentFolderPath = currentFolderPath,
                        searchQuery = searchQuery,
                    )
                showImportTargetDialog = false
                importLauncher.launch(arrayOf("*/*"))
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showImportTargetDialog = false },
        )
    }

    // Ordenar ventana emergente de selección
    if (showSortMenu) {
        MuseDialog(
            onDismissRequest = { showSortMenu = false },
            title = stringResource(R.string.knowledge_sort),
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    KnowledgeSortMode.entries.forEach { mode ->
                        val selected = mode == sortMode
                        Surface(
                            onClick = {
                                sortMode = mode
                                showSortMenu = false
                            },
                            shape = MuseShapes.semiLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = MusePaddings.iconPadding,
                                        vertical = MusePaddings.inputPadding,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding),
                            ) {
                                Icon(
                                    imageVector = mode.icon,
                                    contentDescription = null,
                                    tint = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                )
                                Text(
                                    text = stringResource(mode.labelRes),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    ),
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                if (selected) {
                                    Icon(
                                        imageVector = MuseIcons.check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                                    )
                                }
                            }
                        }
                    }
                }
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showSortMenu = false },
        )
    }

    // ── v2.4.6: Ciclo de vida de la base de conocimientos.(Fusionar desde la página de administración) ──

    // Crear una nueva base de conocimientos
    if (creatingKb) {
        KbEditDialog(
            title = stringResource(R.string.kb_manage_create),
            initialName = "",
            onConfirm = { name, desc ->
                if (name.isBlank()) {
                    MuseToast.show(context.getString(R.string.kb_manage_name_empty))
                } else {
                    scope.launch {
                        val now = System.currentTimeMillis()
                        kbDao.upsert(
                            io.zer0.muse.data.knowledge.KnowledgeBaseEntity(
                                id = "kb-$now",
                                name = name,
                                description = desc,
                                createdAt = now,
                                updatedAt = now,
                            ),
                        )
                        MuseToast.show(context.getString(R.string.kb_manage_created, name))
                    }
                    creatingKb = false
                }
            },
            onDismiss = { creatingKb = false },
        )
    }

    // Cambiar nombre/editar base de conocimientos
    editingKb?.let { kb ->
        KbEditDialog(
            title = stringResource(R.string.kb_manage_edit),
            initialName = kb.name,
            options = KbEditOptions(initialDesc = kb.description),
            onConfirm = { name, desc ->
                if (name.isBlank()) {
                    MuseToast.show(context.getString(R.string.kb_manage_name_empty))
                } else {
                    scope.launch {
                        kbDao.upsert(kb.copy(name = name, description = desc, updatedAt = System.currentTimeMillis()))
                        MuseToast.show(context.getString(R.string.kb_manage_updated, name))
                    }
                    editingKb = null
                }
            },
            onDismiss = { editingKb = null },
        )
    }

    // Eliminar base de conocimientos
    deletingKb?.let { kb ->
        if (kb.id == "default") {
            MuseToast.show(context.getString(R.string.kb_manage_default_no_delete))
            deletingKb = null
        } else {
            ConfirmDeleteDialog(
                title = stringResource(R.string.kb_manage_delete),
                itemName = stringResource(R.string.kb_manage_delete_confirm, kb.name),
                onConfirm = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            dao.getByKbIds(listOf(kb.id)).forEach { doc -> ragService.deleteDocument(doc.id) }
                            folderDao.deleteByKb(kb.id)
                            kbDao.delete(kb.id)
                        }
                        if (browsingKbId == kb.id) {
                            browsingKbId = null
                            currentFolderPath = ""
                        }
                        deletingKb = null
                        MuseToast.show(context.getString(R.string.kb_manage_deleted, kb.name))
                    }
                },
                onDismiss = { deletingKb = null },
            )
        }
    }

    // Reindexar base de datos única(Segunda confirmación)
    reindexConfirmKb?.let { kb ->
        MuseDialog(
            onDismissRequest = { reindexConfirmKb = null },
            title = stringResource(R.string.kb_reindex_all),
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
                    Text(
                        text = stringResource(R.string.kb_reindex_kb_confirm, kb.name, kb.docCount),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.kb_reindex_unavailable_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmText = stringResource(R.string.kb_reindex_all),
            destructive = true,
            onConfirm = {
                reindexConfirmKb = null
                kbReindexProgressPair = 0 to 0
                kbReindexDialogVisible = true
                scope.launch {
                    val ragConfig = runCatching { settings.getRagConfig() }.getOrElse { io.zer0.muse.rag.RagConfig() }
                    resultOf {
                        withContext(Dispatchers.IO) {
                            ragService.reindexAllInKbs(
                                kbIds = listOf(kb.id),
                                ragConfig = ragConfig,
                                onProgress = { done, total -> kbReindexProgressPair = done to total },
                            )
                        }
                    }.onSuccess { failures ->
                        kbReindexDialogVisible = false
                        if (failures.isEmpty()) {
                            MuseToast.show(context.getString(R.string.kb_reindex_done, kbReindexProgressPair.second))
                        } else {
                            MuseToast.show(
                                context.getString(R.string.kb_reindex_failed, failures.values.joinToString().take(120)),
                            )
                        }
                    }.onError { msg, _ ->
                        kbReindexDialogVisible = false
                        MuseToast.show(context.getString(R.string.knowledge_reindex_failed, msg.take(80)))
                    }
                }
            },
            onDismiss = { reindexConfirmKb = null },
        )
    }

    // Reindexar cuadro de diálogo de progreso(KB nivel)
    if (kbReindexDialogVisible) {
        val (done, total) = kbReindexProgressPair
        MuseDialog(
            onDismissRequest = { kbReindexDialogVisible = false },
            title = stringResource(R.string.kb_reindex_all),
            content = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MuseSpinner(size = 28.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (total > 0) "$done / $total" else stringResource(R.string.knowledge_reindexing_default),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            onConfirm = null,
            dismissText = null,
        )
    }

    // ── v2.4.6: Ciclo de vida de la carpeta ──

    // Crear nueva carpeta
    if (creatingFolder) {
        val kbNow = browsingKbId
        KbEditDialog(
            title = stringResource(R.string.knowledge_folder_new),
            initialName = "",
            options = KbEditOptions(
                showDescription = false,
                nameLabel = stringResource(R.string.knowledge_folder_name_label),
            ),
            onConfirm = { name, _ ->
                if (kbNow == null || name.isBlank()) {
                    if (name.isBlank()) MuseToast.show(context.getString(R.string.knowledge_folder_name_empty))
                } else {
                    scope.launch {
                        val path = KnowledgeFolderBrowser.normalize(
                            if (currentFolderPath.isEmpty()) name else "$currentFolderPath/$name",
                        )
                        folderDao.upsert(
                            io.zer0.muse.data.knowledge.KnowledgeFolderEntity(
                                id = "kbfolder-$kbNow-$path",
                                kbId = kbNow,
                                path = path,
                                name = path.substringAfterLast('/'),
                            ),
                        )
                        MuseToast.show(context.getString(R.string.knowledge_folder_created, path))
                    }
                }
                creatingFolder = false
            },
            onDismiss = { creatingFolder = false },
        )
    }

    // Cambiar nombre de carpeta(Cambiar el final del camino)
    renamingFolder?.let { oldPath ->
        val kbNow = browsingKbId
        val oldName = oldPath.substringAfterLast('/')
        KbEditDialog(
            title = stringResource(R.string.knowledge_folder_rename),
            initialName = oldName,
            options = KbEditOptions(
                showDescription = false,
                nameLabel = stringResource(R.string.knowledge_folder_name_label),
            ),
            onConfirm = { newName, _ ->
                if (kbNow == null || newName.isBlank()) {
                    if (newName.isBlank()) MuseToast.show(context.getString(R.string.knowledge_folder_name_empty))
                } else {
                    scope.launch {
                        val parent = KnowledgeFolderBrowser.parent(oldPath)
                        val newPath = KnowledgeFolderBrowser.normalize(
                            if (parent.isEmpty()) newName else "$parent/$newName",
                        )
                        withContext(Dispatchers.IO) {
                            // Registro explícito de carpetas:Cambiar el prefijo de todo el subárbol
                            folderDao.getByPathRecursive(kbNow, oldPath).forEach { f ->
                                val suffix = f.path.removePrefix(oldPath)
                                val nPath = newPath + suffix
                                folderDao.deleteById(f.id)
                                folderDao.upsert(
                                    f.copy(
                                        id = "kbfolder-$kbNow-$nPath",
                                        path = nPath,
                                        name = nPath.substringAfterLast('/'),
                                        updatedAt = System.currentTimeMillis(),
                                    ),
                                )
                            }
                            // Documentación:Cámbielo junto con la ruta de la carpeta del prefijo.
                            dao.getByKbIds(listOf(kbNow)).forEach { doc ->
                                val p = KnowledgeFolderBrowser.folderPath(doc)
                                if (p == oldPath || p.startsWith("$oldPath/")) {
                                    val suffix = p.removePrefix(oldPath)
                                    val nPath = newPath + suffix
                                    dao.upsert(
                                        KnowledgeFolderBrowser.withFolderPath(doc, nPath)
                                            .copy(updatedAt = System.currentTimeMillis()),
                                    )
                                }
                            }
                        }
                        if (currentFolderPath == oldPath || currentFolderPath.startsWith("$oldPath/")) {
                            currentFolderPath = newPath + currentFolderPath.removePrefix(oldPath)
                        }
                        MuseToast.show(context.getString(R.string.knowledge_folder_renamed, newName))
                    }
                }
                renamingFolder = null
            },
            onDismiss = { renamingFolder = null },
        )
    }

    // eliminar carpeta(Contiene subdirectorios;Los documentos no se eliminan,Volver a la raíz después de borrar la información del directorio)
    deletingFolder?.let { path ->
        val kbNow = browsingKbId
        ConfirmDeleteDialog(
            title = stringResource(R.string.knowledge_folder_delete),
            itemName = stringResource(R.string.knowledge_folder_delete_confirm, path),
            onConfirm = {
                if (kbNow != null) {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            folderDao.deleteByPathRecursive(kbNow, path)
                        }
                        if (currentFolderPath == path || currentFolderPath.startsWith("$path/")) {
                            currentFolderPath = KnowledgeFolderBrowser.parent(path)
                        }
                        MuseToast.show(context.getString(R.string.knowledge_folder_deleted))
                    }
                }
                deletingFolder = null
            },
            onDismiss = { deletingFolder = null },
        )
    }
}

@Composable
private fun KnowledgeFolderCard(
    title: String,
    detail: String,
    onClick: () -> Unit,
    // v2.4.6: cambiar el nombre de la carpeta/Eliminar(Sólo acciones explícitas para colgar nodos en el árbol de carpetas;KB Tarjeta no pasada)
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shadowElevation = MuseElevation.card,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Icon(
                imageVector = MuseIcons.folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onRename != null) {
                KbActionIcon(
                    icon = MuseIcons.edit,
                    contentDescription = stringResource(R.string.knowledge_folder_rename),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onRename,
                )
            }
            if (onDelete != null) {
                KbActionIcon(
                    icon = MuseIcons.trash,
                    contentDescription = stringResource(R.string.knowledge_folder_delete),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
            Icon(
                imageVector = MuseIcons.chevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
        }
    }
}

/** v2.4.6: Línea \"Nueva carpeta\" — Entrada de línea punteada(Utilice el color de fondo de SurfaceVariant para distinguir). */
@Composable
private fun NewFolderRow(onClick: () -> Unit) {
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Icon(
                imageVector = MuseIcons.plus,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = stringResource(R.string.knowledge_folder_new),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private data class FileTypeStyle(
    val icon: ImageVector,
    val iconColor: Color,
    val containerColor: Color,
)

@Composable
private fun fileTypeStyle(fileType: String): FileTypeStyle {
    // v1.0.52: Tipo de documento color acceso semántica estado color,El modo oscuro cambia automáticamente al modo brillante para garantizar que los iconos sean legibles
    val statusColors = MaterialTheme.statusColors
    return when (fileType.lowercase(Locale.getDefault())) {
        "pdf" -> FileTypeStyle(
            icon = MuseIcons.fileText,
            iconColor = statusColors.error,
            containerColor = statusColors.error.copy(alpha = 0.10f),
        )
        "md", "markdown" -> FileTypeStyle(
            icon = MuseIcons.fileText,
            iconColor = statusColors.info,
            containerColor = statusColors.info.copy(alpha = 0.10f),
        )
        "txt" -> FileTypeStyle(
            icon = MuseIcons.bookOpen,
            iconColor = statusColors.success,
            containerColor = statusColors.success.copy(alpha = 0.10f),
        )
        else -> FileTypeStyle(
            icon = MuseIcons.fileText,
            iconColor = statusColors.neutral,
            containerColor = statusColors.neutral.copy(alpha = 0.10f),
        )
    }
}

@Composable
private fun DocCard(doc: KnowledgeDocEntity, highlight: String?, onClick: () -> Unit, onDelete: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val style = fileTypeStyle(doc.fileType)
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = MuseElevation.card,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(MusePaddings.cardInnerLoose),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(color = style.containerColor, shape = MuseShapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = style.iconColor,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    doc.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // P0-3(informe de auditoría): Si el reemplazo del índice falla a mitad de camino, los bloques antiguos se eliminarán y los bloques nuevos estarán incompletos.
                // El documento todavía está en la lista pero nunca podrá recuperarse. Antes no había ningún aviso, los usuarios solo pensarían“Esta biblioteca no funciona”.
                // El valor centinela se escribió en embedding_model durante la limpieza, y aquí se identifican y brindan consejos prácticos.
                if (doc.embeddingModel == io.zer0.muse.data.knowledge.KnowledgeDocDao.INDEX_BROKEN_SENTINEL) {
                    Text(
                        text = stringResource(R.string.knowledge_doc_index_broken),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                    )
                }
                // v1.71: Almacenamiento en caché con recordar SimpleDateFormat,Evite crear nuevos objetos al reorganizar elementos de la lista
                val dateFmt = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_SHORT, Locale.getDefault()) }
                Text(
                    stringResource(
                        R.string.knowledge_doc_meta_with_date,
                        doc.fileType,
                        doc.content.length,
                        dateFmt.format(Date(doc.createdAt)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MuseTactileButton(
                icon = MuseIcons.trash,
                onClick = { showDeleteConfirm = true },
                contentDescription = stringResource(R.string.knowledge_delete),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                size = 48.dp,
                iconSize = 18.dp,
            )
        }
    }
    if (showDeleteConfirm) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.knowledge_delete_doc),
            itemName = doc.title,
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

@Composable
private fun DocDetailDialog(
    doc: KnowledgeDocEntity,
    onDismiss: () -> Unit,
    onReindex: () -> Unit,
    // v1.0.53: Capacidad de cobertura(Abrir biblioteca de portadas/generar portada/generar estado)
    onOpenCoverManager: () -> Unit = {},
    onGenerateCover: () -> Unit = {},
    generatingCover: Boolean = false,
    // F-32: Entrada para cambiar el nombre del documento/edición de texto(Sólo los documentos de tipo texto permiten edición de texto.)
    onRename: () -> Unit = {},
    onEditContent: () -> Unit = {},
    onMoveToFolder: () -> Unit = {},
) {
    // v1.0.53: Necesidades de análisis del área de cobertura. context(filesDir/covers)
    val context = LocalContext.current
    // v1.0.4 P2-2:Separe el bloque de metadatos PDF/bloque de tabla de contenido/texto de doc.content,
    // Permitir a los usuarios ver tarjetas de información de documentos estructurados en la ventana emergente de detalles(En lugar de mezclarlo con texto del mismo ancho).
    // DocumentParser Formato de salida:[Información del documento]\ncampo: valor\n...\n\n[Directorio]\n• entrada\n...\n\n<Texto>
    val parsed by remember(doc.id, doc.content) {
        mutableStateOf(parsePdfStructuredContent(doc.content, doc.fileType))
    }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = doc.title,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                // v1.0.53: área de cobertura(frontmatter cover campo → filesDir/covers/)
                val coverPath = io.zer0.muse.common.markdown.FrontmatterParser.parse(doc.content)?.cover
                val coverFile = coverPath?.let { path ->
                    if (path.startsWith("covers/")) {
                        java.io.File(java.io.File(context.filesDir, "covers"), path.removePrefix("covers/"))
                    } else {
                        null
                    }
                }
                if (coverFile != null && coverFile.exists()) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 5f)
                            .clip(MuseShapes.large),
                    ) {
                        coil.compose.AsyncImage(
                            model = coverFile,
                            contentDescription = doc.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // v1.0.53: línea de operación de cubierta(Biblioteca de portadas + generar portada)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.cover_import),
                        onClick = onOpenCoverManager,
                        variant = IosCapsuleButtonVariant.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    MuseCapsuleButton(
                        text = if (generatingCover) stringResource(R.string.cover_generating) else stringResource(R.string.cover_generate),
                        onClick = onGenerateCover,
                        enabled = !generatingCover,
                        loading = generatingCover,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))

                // F-32: linea de operacion(Cambiar nombre/editar texto)— Los documentos de texto proporcionan edición de texto.,El resto simplemente ha cambiado de nombre.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.knowledge_rename_doc),
                        onClick = onRename,
                        variant = IosCapsuleButtonVariant.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    if (isTextDoc(doc.fileType)) {
                        MuseCapsuleButton(
                            text = stringResource(R.string.knowledge_edit_content),
                            onClick = onEditContent,
                            variant = IosCapsuleButtonVariant.Secondary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                MuseCapsuleButton(
                    text = stringResource(R.string.knowledge_move_to_folder),
                    onClick = onMoveToFolder,
                    variant = IosCapsuleButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))

                Text(
                    // v1.67-A: Mostrar el número de mosaicos y el modelo de incrustación.,Permitir a los usuarios determinar si es necesario volver a indexar
                    text = buildString {
                        append(stringResource(R.string.knowledge_doc_chars, doc.fileType, doc.content.length))
                        append(
                            if (doc.chunkCount > 0) {
                                stringResource(
                                    R.string.knowledge_doc_chunks,
                                    doc.chunkCount,
                                )
                            } else {
                                stringResource(R.string.knowledge_doc_not_indexed)
                            },
                        )
                        if (doc.embeddingModel.isNotBlank()) append(" · ${doc.embeddingModel}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(8.dp))

                // v1.0.4 P2-2:PDF tarjeta de metadatos(Mostrar solo si los metadatos están disponibles)
                if (parsed.metadata.isNotEmpty()) {
                    PdfMetadataCard(metadata = parsed.metadata)
                    Spacer(Modifier.height(8.dp))
                }
                // v1.0.4 P2-2:PDF tarjeta de catalogo(plegable,Sólo se muestra cuando hay un directorio)
                if (parsed.outline.isNotEmpty()) {
                    PdfOutlineCard(outline = parsed.outline)
                    Spacer(Modifier.height(8.dp))
                }

                // Texto(Sin metadatos/Reducido a la visualización del contenido original al catalogar.,mantente compatible)
                val bodyText = parsed.body
                Text(
                    bodyText,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MuseMonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        confirmText = stringResource(R.string.knowledge_close),
        onConfirm = onDismiss,
        // v1.67-A: Entrada de reindexación(Disponible después de reemplazar el modelo de incrustación o cuando falla el primer índice)
        dismissText = stringResource(R.string.knowledge_reindex),
        onDismiss = {
            onReindex()
            onDismiss()
        },
    )
}

/**
 * v1.0.4 P2-2:Separe los metadatos/tabla de contenido/texto del PDF de la salida de texto concatenado de DocumentParser.
 *
 * DocumentParser.readPdf El formato de salida de(ver doc/DocumentParser.kt:242-258):
 * ```
 * [Información del documento]
 * Título: xxx
 * Autor: xxx
 *
 * [Directorio]
 * • Capítulo 1
 *   • 1.1 fiesta
 *
 * <Texto>
 * ```
 * Esta función separa las tres piezas.,Proporciona una interfaz de usuario con representación estructurada respectivamente. Documento no PDF(fileType != "pdf")
 * Devolver directamente todo el contenido como cuerpo,Mantenga el comportamiento de visualización original.
 */
private fun parsePdfStructuredContent(content: String, fileType: String): ParsedPdfContent {
    if (fileType.lowercase(Locale.getDefault()) != "pdf" || content.isBlank()) {
        return ParsedPdfContent(body = content)
    }
    // Sólo si el contenido comienza con [Información del documento] o [Directorio] Solo analizado al principio.(Evite tragar texto normal)
    if (!content.startsWith("[Información del documento]") && !content.startsWith("[Directorio]")) {
        return ParsedPdfContent(body = content)
    }

    val metadata = LinkedHashMap<String, String>()
    val outline = mutableListOf<String>()
    val bodyBuilder = StringBuilder()

    var section = "meta" // meta / outline / body
    content.lineSequence().forEach { line ->
        when {
            line == "[Información del documento]" -> section = "meta"
            line == "[Directorio]" -> section = "outline"
            // Las líneas vacías sirven como separadores implícitos para cambios de sección.(DocumentParser Se generan líneas en blanco entre bloques.)
            line.isBlank() && section != "body" -> {
                // No cambiar inmediatamente:Espere hasta que encuentre algo mal [xxx] Y se decidirá cuando no sea una línea en blanco.
            }
            section == "meta" && line.contains(": ") -> {
                val idx = line.indexOf(": ")
                if (idx > 0) {
                    metadata[line.substring(0, idx).trim()] = line.substring(idx + 2).trim()
                }
            }
            section == "outline" && line.startsWith("•") -> {
                outline.add(line.trimStart(' ', '•').trim())
            }
            section == "outline" && line.startsWith("  •") -> {
                outline.add("  " + line.trimStart(' ', '•').trim())
            }
            else -> {
                // No se encontraron metadatos/entrada que no es de directorio,Cambiar al modo cuerpo
                if (section != "body") section = "body"
                bodyBuilder.appendLine(line)
            }
        }
    }

    return ParsedPdfContent(
        metadata = metadata,
        outline = outline,
        body = bodyBuilder.toString().trim(),
    )
}

/** [parsePdfStructuredContent] resultados del análisis. */
private data class ParsedPdfContent(
    val metadata: Map<String, String> = emptyMap(),
    val outline: List<String> = emptyList(),
    val body: String = "",
)

/**
 * PDF tarjeta de metadatos — El nombre del campo + el valor se muestran en pares,limit 8 Evite líneas demasiado largas.
 */
@Composable
private fun PdfMetadataCard(metadata: Map<String, String>) {
    Surface(
        shape = MuseShapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = MuseIcons.info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.knowledge_doc_info),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(6.dp))
            metadata.forEach { (k, v) ->
                Text(
                    text = "$k: $v",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * PDF tarjeta de catalogo — Plegado predeterminado(Evite ocupar demasiado espacio emergente),Haga clic en el título para cambiar y expandir/Doblar.
 */
@Composable
private fun PdfOutlineCard(outline: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = MuseShapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Fila de título en la que se puede hacer clic,Alternar para expandir/doblar
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = MuseIcons.bookOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.knowledge_doc_outline, outline.size),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) {
                        stringResource(
                            R.string.action_collapse,
                        )
                    } else {
                        stringResource(R.string.action_expand)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                outline.forEach { item ->
                    val indent = if (item.startsWith("  ")) 16.dp else 0.dp
                    Text(
                        text = "• " + item.trimStart(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = indent),
                    )
                }
            }
        }
    }
}

/**
 * v1.66: Patrón de clasificación de la base de conocimientos.
 * DAO Sólo admite updated_at DESC,Las dimensiones restantes se ordenan mediante comparadores en la capa de la interfaz de usuario.
 */
enum class KnowledgeSortMode(
    val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val comparator: Comparator<KnowledgeDocEntity>,
) {
    UPDATED(R.string.knowledge_sort_updated, MuseIcons.clock, compareByDescending { it.updatedAt }),
    CREATED(R.string.knowledge_sort_created, MuseIcons.calendar, compareByDescending { it.createdAt }),
    TITLE(R.string.knowledge_sort_title, MuseIcons.sort, compareBy { it.title.lowercase(Locale.getDefault()) }),
    SIZE(R.string.knowledge_sort_size, MuseIcons.database, compareByDescending { it.content.length }),
}

/**
 * v1.0.47 P7-3: Formatee el tamaño del archivo como una cadena legible por humanos.
 */
private fun formatFileSize(bytes: Long): String = FileSizeFormatter.format(bytes)

/**
 * F-32: Determinar si el documento es un tipo de texto con texto editable(md/markdown/txt/csv/json).
 * PDF/docx/epub/ocr etc. El texto concatenado generado por el analizador no pertenece a"tipo de texto",No se proporciona ninguna función de modificación directa de texto.,
 * Evite dañar la información estructurada causada por la modificación errónea de los productos de análisis.
 */
private fun isTextDoc(fileType: String): Boolean = when (fileType.lowercase(Locale.getDefault())) {
    "md", "markdown", "txt", "csv", "json" -> true
    else -> false
}
