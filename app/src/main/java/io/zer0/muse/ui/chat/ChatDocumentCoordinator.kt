package io.zer0.muse.ui.chat

import android.content.Context
import android.net.Uri
import io.zer0.common.AppDispatchers
import io.zer0.common.FileSizeFormatter
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.doc.DocumentParser
import io.zer0.muse.rag.SessionAttachmentService
import io.zer0.muse.workspace.WorkspaceManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * v1.105 Fase 1: Coordinador de análisis de documentos extraído de ChatViewModel.
 *
 * v2.6.13: El archivo adjunto se cambia a "Colocar el original en el espacio de trabajo + dejar solo la cita en el mensaje".
 *
 * El enlace antiguo está "descomprimido"/analizar → Inserte el texto completo en el mensaje":zip Simplemente escupe caracteres confusos,docx/xlsx Después de la descompresión
 * Miles de palabras entran en el cuerpo del mensaje. Lo que ve el usuario es un montón de burbujas de texto y lo que ve el modelo son fragmentos truncados.
 * Enlace actual:
 *  1. Importe el original (bytes tal cual) al espacio de trabajo `attachments/` , los conflictos de nombres de archivos agregarán automáticamente números de serie;
 *  2. Coloque solo una línea de referencia (ruta relativa + tamaño) en el mensaje. Si desea utilizar el modelo, utilice la herramienta de archivo para leerlo a pedido;
 *  3. paquete comprimido/binario**Sin descompresión**y ya no insertará ningún texto extraído en el contexto;
 *  4. Los archivos adjuntos de texto sin formato seguirán leyendo el texto una vez, pero solo se usarán para el recuento de palabras del chip y el índice de recuperación de archivos adjuntos de conversaciones.
 *     **No entrar prompt**(Ambas cosas ya no dependen de "poner texto en el mensaje").
 *
 * Responsabilidades:
 *  - pickDocument: Seleccione el archivo y póngalo en el disco + genere la línea de referencia y guárdelo pendingDocuments
 *  - addPastedText: Grandes secciones de texto pegado también se colocan en el espacio de trabajo y siguen la misma ruta de referencia.
 *
 * No retenido state,Pase [accessor] Leer y escribir.
 */
class ChatDocumentCoordinator(
    private val accessor: ChatStateAccessor,
    private val documentParser: DocumentParser,
    private val settings: SettingsRepository,
) {

    private val tag = "ChatVM"

    companion object {
        /**
         * Número máximo de caracteres en los resultados del análisis de documentos(Evite explotar cuadros de entrada y mensajes demasiado largos).
         * v1.136 T10: Aumentó de 4000 a 32000,Adaptarse a la ventana de contexto más amplia de los LLM modernos.
         * v2.6.13: Límite de longitud máxima y truncamiento del índice de búsqueda de sesiones solo para "Pegar texto en el disco",
         * Ya no se utiliza como longitud del cuerpo del mensaje.
         */
        const val DOC_MAX_CHARS = 32000

        /** El directorio donde se coloca el archivo adjunto en el espacio de trabajo (en relación con la raíz del espacio de trabajo). */
        const val ATTACHMENT_DIR = "attachments"

        /**
         * Capaz de leer texto y contar palabras./La extensión de texto sin formato que se recuperará.
         *
         * solo van estos [DocumentParser] Rama de lectura de texto;docx/xlsx/pptx/pdf/epub/zip
         * Nunca descomprimas ni analices —— El documento original ya está en el espacio de trabajo y usted mismo debe leer el contenido del modelo.
         */
        private val PLAIN_TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml",
            "yaml", "yml", "log", "ini", "toml", "html", "htm", "kt", "py", "js", "ts",
        )
    }

    /**
     * Después de seleccionar el archivo: el original se importa al espacio de trabajo, dejando solo una línea de referencia en el mensaje.
     *
     * Audio/El vídeo mantiene su enlace original (conserva Uri + MIME y lo envía al modelo multimodal tal como está) y no hace referencia a él en el espacio de trabajo.
     */
    fun pickDocument(uri: Uri, context: Context, reportError: (String) -> Unit) {
        if (accessor.snapshot.isStreaming) return
        accessor.coroutineScope.launch(AppDispatchers.io) {
            runCatching { importAttachment(uri, context, reportError) }
                .onFailure { t ->
                    Logger.e(tag, "attachment import failed", t)
                    reportError(context.getString(R.string.chat_attachment_import_failed, t.message ?: ""))
                }
        }
    }

    /** Pasos específicos para importar archivos adjuntos (eliminar [pickDocument] para reducir la complejidad ciclomática). */
    private suspend fun importAttachment(uri: Uri, context: Context, reportError: (String) -> Unit) {
        val fileName = queryDisplayName(context, uri) ?: context.getString(R.string.chat_doc_default_name)
        val mime = context.contentResolver.getType(uri).orEmpty()
        val sizeBytes = queryFileSize(context, uri)

        // v2.6.6: Audio/Los vídeos ya no están obligados a convertirse en texto.
        //
        // Audio/No hay ningún texto que se pueda extraer del video, y el análisis solo informará "vacío o no compatible" y el usuario no puede enviarlo en absoluto.
        // El multimedia binario conserva el Uri + MIME original y se envía tal cual con el mensaje a modelos con capacidad multimodal.
        if (mime.startsWith("audio/") || mime.startsWith("video/")) {
            accessor.update {
                it.copy(
                    pendingDocuments = it.pendingDocuments + PendingDocument(
                        name = fileName,
                        content = "",
                        charCount = 0,
                        uri = uri.toString(),
                        mimeType = mime,
                        sizeBytes = sizeBytes,
                    ),
                )
            }
        } else {
            importAsWorkspaceReference(uri, context, fileName, mime, reportError)
        }
    }

    /** La ruta de los archivos adjuntos no multimedia: el archivo original se coloca en el espacio de trabajo + solo queda una línea de referencia en el mensaje. */
    private suspend fun importAsWorkspaceReference(
        uri: Uri,
        context: Context,
        fileName: String,
        mime: String,
        reportError: (String) -> Unit,
    ) {
        val workspace = resolveWorkspace()
        val stored = workspace?.let { storeOriginal(it, uri, fileName, context, reportError) }
        if (workspace == null) {
            reportError(context.getString(R.string.chat_attachment_import_failed, "workspace unavailable"))
        }
        if (stored == null) return

        // Lea el texto principal solo cuando lea texto sin formato: solo se usa para el recuento de palabras del chip + recuperación de archivos adjuntos de sesión, y no se escribirá en el mensaje.
        val extracted = if (isPlainText(mime, fileName)) {
            runCatching { documentParser.parseResult(uri, context).getOrNull() }.getOrNull()
        } else {
            null
        }

        accessor.update {
            it.copy(
                pendingDocuments = it.pendingDocuments + PendingDocument(
                    name = fileName,
                    content = context.getString(
                        R.string.chat_attachment_workspace_ref,
                        stored.relativePath,
                        FileSizeFormatter.format(stored.bytes),
                    ),
                    charCount = extracted?.length ?: 0,
                    workspacePath = stored.relativePath,
                    mimeType = mime,
                    sizeBytes = stored.bytes,
                ),
            )
        }

        indexForSessionSearch(fileName, extracted)
    }

    /** Escribe el original en el espacio de trabajo. `attachments/`; Si falla, se informará un error y se devolverá nulo. */
    private suspend fun storeOriginal(
        workspace: WorkspaceManager,
        uri: Uri,
        fileName: String,
        context: Context,
        reportError: (String) -> Unit,
    ): WorkspaceManager.FileImportResult.Success? {
        // El directorio del disco se crea bajo demanda (importFile Requiere que el directorio de destino ya exista).
        if (workspace.mkdir(ATTACHMENT_DIR) is WorkspaceManager.OpResult.Error) {
            reportError(context.getString(R.string.chat_attachment_import_failed, ATTACHMENT_DIR))
            return null
        }
        return when (val imported = workspace.importFile(uri, ATTACHMENT_DIR, fileName)) {
            is WorkspaceManager.FileImportResult.Success -> imported
            is WorkspaceManager.FileImportResult.Error -> {
                reportError(context.getString(R.string.chat_attachment_import_failed, imported.message))
                null
            }
        }
    }

    /**
     * v1.0.92: Recuperación de archivos adjuntos de sesión (configuraciones → Búsqueda de base de conocimientos → Recuperación de archivos adjuntos de sesión, habilitada de forma predeterminada).
     *
     * v2.6.13: Indexe sólo cuando obtenga el texto sin formato; paquete comprimido/Los documentos de Office ya no se descomprimen para obtener el texto completo.
     * Cualquier error sólo se registra —— "Enviar archivo adjunto" en sí no debe verse afectado por la indexación de recuperación.
     */
    private suspend fun indexForSessionSearch(fileName: String, extracted: String?) {
        runCatching {
            val text = extracted?.take(DOC_MAX_CHARS)
            if (text.isNullOrBlank() || !settings.ragConfigFlow.first().sessionAttachmentEnabled) return
            val st = accessor.snapshot
            val sid = if (st.isAgentMode) st.agentSessionId else st.currentSessionId
            if (sid.isNullOrBlank()) return
            GlobalContext.get().get<SessionAttachmentService>()
                .indexSessionAttachment(sessionId = sid, name = fileName, content = text)
            Logger.i(tag, "Archivos adjuntos de sesión enviados para indexación | sessionId=$sid | name=$fileName")
        }.onFailure { Logger.w(tag, "Error al acceder al índice del archivo adjunto de la sesión(No afecta el envío de documentos)", it) }
    }

    /**
     * v2.6.13: Pegue una sección grande de texto como archivo adjunto —— Suéltelo también en el espacio de trabajo, dejando solo la referencia en el mensaje.
     *
     * con [pickDocument] Compartir un canal: escriba primero el espacio de trabajo `attachments/`y luego coloque la línea de referencia en
     * pendingDocuments. De esta manera, "pegar texto largo" y "cargar texto" son lo mismo para el modelo:
     * Todos pueden leer el texto original a través de workspace_read, en lugar de meter miles de palabras en esta ronda de contexto.
     */
    fun addPastedText(text: String, context: Context, reportError: (String) -> Unit) {
        if (text.isBlank()) return
        val stamp = java.text.SimpleDateFormat("MMdd_HHmm", java.util.Locale.getDefault())
            .format(java.util.Date())
        val fileName = context.getString(R.string.chat_pasted_doc_name, stamp)
        accessor.coroutineScope.launch(AppDispatchers.io) {
            try {
                val workspace = resolveWorkspace()
                if (workspace == null) {
                    // Vuelva al comportamiento anterior cuando el espacio de trabajo no está disponible: el texto va directamente al mensaje, al menos sin permitir que el usuario pegue en vano.
                    val fallback = text.take(DOC_MAX_CHARS)
                    accessor.update {
                        it.copy(
                            pendingDocuments = it.pendingDocuments + PendingDocument(
                                name = fileName,
                                content = fallback,
                                charCount = fallback.length,
                            ),
                        )
                    }
                    return@launch
                }
                if (workspace.mkdir(ATTACHMENT_DIR) is WorkspaceManager.OpResult.Error) {
                    reportError(context.getString(R.string.chat_attachment_import_failed, ATTACHMENT_DIR))
                    return@launch
                }
                val write = workspace.writeFile("$ATTACHMENT_DIR/$fileName", text)
                if (write is WorkspaceManager.OpResult.Error) {
                    reportError(context.getString(R.string.chat_attachment_import_failed, write.message))
                    return@launch
                }
                val bytes = text.toByteArray(Charsets.UTF_8).size.toLong()
                accessor.update {
                    it.copy(
                        pendingDocuments = it.pendingDocuments + PendingDocument(
                            name = fileName,
                            content = context.getString(
                                R.string.chat_attachment_workspace_ref,
                                "$ATTACHMENT_DIR/$fileName",
                                FileSizeFormatter.format(bytes),
                            ),
                            charCount = text.length,
                            workspacePath = "$ATTACHMENT_DIR/$fileName",
                            mimeType = "text/plain",
                            sizeBytes = bytes,
                        ),
                    )
                }
            } catch (t: Exception) {
                Logger.e(tag, "pasted text import failed", t)
                reportError(context.getString(R.string.chat_attachment_import_failed, t.message ?: ""))
            }
        }
    }

    /** Responsable del espacio de trabajo:Koin Semifallo; prueba/Devuelve nulo cuando no se puede obtener el entorno degradado. */
    private fun resolveWorkspace(): WorkspaceManager? = runCatching {
        GlobalContext.get().get<WorkspaceManager>()
    }.getOrNull()

    /** Si se trata de un archivo adjunto de texto puro (decidir si leer el texto para contar palabras y buscar). */
    private fun isPlainText(mime: String, fileName: String): Boolean {
        if (mime.startsWith("text/")) return true
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in PLAIN_TEXT_EXTENSIONS
    }

    /** Consultar nombre para mostrar desde SAF URI(ContentResolver.query). */
    /**
     * Leer el tamaño del archivo (bytes). Devuelve 0 si no se lee (solo para visualización, no afecta el envío).
     */
    private fun queryFileSize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }.getOrNull() ?: 0L

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            }
        }.getOrNull()
    }
}

/**
 * v1.136 T10: Adjunto a enviar.
 *
 * v2.6.13: [content] Para los archivos adjuntos normales, ya no es el texto del archivo, sino una referencia de una línea.
 * ("El archivo adjunto se ha guardado en el espacio de trabajo:attachments/x.zip(12.3 MB)"). Si desea ver el contenido del modelo, utilice la herramienta de archivo.
 * ve a leer [workspacePath];El paquete comprimido no se descomprimirá y el binario no se incorporará al contexto como texto.
 *
 * @param name nombre de archivo(Como por ejemplo "report.pdf")
 * @param content Deletrear el contenido del mensaje al enviarlo (archivo adjunto normal = línea de cotización; sonido/vídeo = cadena vacía)
 * @param charCount Número de caracteres en archivos adjuntos de texto sin formato (solo visualización; 0 si el texto no se lee)
 * @param uri Archivo original Uri (como una cadena). solo audio/El vídeo se conserva y se utiliza para pasarlo al modelo multimodal tal como está.
 * @param mimeType MIME tipo. Es una cadena vacía cuando se desconoce.
 * @param sizeBytes Número de bytes en el archivo original; 0 si se desconoce.
 * @param workspacePath Ruta relativa al original dentro del espacio de trabajo; nulo si no está en el espacio de trabajo (multimedia).
 */
data class PendingDocument(
    val name: String,
    val content: String,
    val charCount: Int,
    val uri: String? = null,
    val mimeType: String = "",
    val sizeBytes: Long = 0L,
    val workspacePath: String? = null,
) {
    /** Si se trata de un archivo adjunto binario (audio/vídeo)——Este tipo de texto no requiere inyección de texto y debe transmitirse directamente tal como está. */
    val isBinary: Boolean
        get() = mimeType.startsWith("audio/") || mimeType.startsWith("video/")
}
