package io.zer0.muse.ui.quicknotes
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.quicknote.NoteCipher
import io.zer0.muse.data.quicknote.QuickNoteDao
import io.zer0.muse.data.quicknote.QuickNoteEntity
import io.zer0.muse.tools.reminder.ReminderAlarmReceiver
import io.zer0.muse.tools.reminder.ReminderAlarmScheduler
import io.zer0.muse.tools.reminder.ReminderStore
import io.zer0.muse.ui.common.feedback.MuseToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * v1.0.17: Registre rápidamente el estado de la interfaz de usuario.
 *
 * @param notes Lista de registros rápidos mostrados actualmente(Búsqueda aplicada/etiqueta/Filtrado de carpetas,traer al frente)
 * @param searchKeyword Buscar palabras clave(Una cadena vacía significa que no hay filtrado)
 * @param selectedTag Etiqueta actualmente seleccionada(null Indica que no hay filtrado)
 * @param selectedFolder Carpeta actualmente seleccionada(null significa todo,Una cadena vacía indica no clasificado)
 * @param allTags Todas las etiquetas disponibles(Derivado de notas activas,Desordenamiento de minúsculas)
 * @param folders Lista de todas las carpetas que no están vacías(Derivado de observeFolders)
 * @param trashItems Entrada a la papelera de reciclaje
 * @param showTrash Si mostrar el panel de la papelera de reciclaje
 * @param isInitialLoading primera bandera de carga(Evite el estado flash)
 * @param hasMore ¿Hay más registros para cargar?(Paginación)
 */
data class QuickNotesUiState(
    val notes: List<QuickNoteEntity> = emptyList(),
    val searchKeyword: String = "",
    val selectedTag: String? = null,
    val selectedFolder: String? = null,
    val allTags: List<String> = emptyList(),
    val folders: List<String> = emptyList(),
    val trashItems: List<QuickNoteEntity> = emptyList(),
    val showTrash: Boolean = false,
    val isInitialLoading: Boolean = true,
    val hasMore: Boolean = false,
    /** ST-01: Listar información sobre errores de carga(null=normales). */
    val error: String? = null,
)

/**
 * v1.0.17: registro rápido ViewModel — Reemplaza la forma en que QuickNotesScreen contiene QuickNoteStore directamente.
 *
 * Responsabilidades:
 *  - de [QuickNoteDao] Flow recopila registros normales + registros de papelera de reciclaje
 *  - Mantener el estado del filtro de palabras clave/etiquetas/carpetas de búsqueda,y derivado allTags / folders / notes ver
 *  - expuesto add / update / delete / restore / deletePermanent / setPinned / clearTrash Operación
 *  - v1.0.18: Agregar carpeta/tipo de contenido/recordatorio/cifrado/importación y exportación/paginación
 *
 * fuente de datos:Room(Almacenamiento de archivos JSON alternativo),Pase [io.zer0.muse.tools.quicknote.QuickNoteStore.migrateToRoom]
 * Importe datos JSON antiguos todos a la vez cuando se inicie la aplicación Room,La Sala siguiente es la única fuente verdadera.
 *
 * v1.0.18: aumento de estructura [context](Se utiliza para ReminderStore + AlarmManager para programar recordatorios).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickNotesViewModel(
    private val dao: QuickNoteDao,
    private val context: Context,
) : ViewModel() {

    private val reminderStore = ReminderStore(context)
    private val noteCipher = NoteCipher()

    // v2.6.7: Almacenamiento de archivos adjuntos de notas.addAttachment Cero llamadas anteriores —— La tarjeta mostrará "N archivos adjuntos
    // (Lea el pase lateral), pero no hay lugar para escribir en toda la posición, lo que equivale a un indicador que siempre está vacío.
    private val attachmentStore = io.zer0.muse.data.quicknote.NoteAttachmentStore(context)

    private val _searchKeyword = MutableStateFlow("")
    private val _selectedTag = MutableStateFlow<String?>(null)
    private val _selectedFolder = MutableStateFlow<String?>(null)
    private val _showTrash = MutableStateFlow(false)

    // ST-01: Lista de información de error de carga + fuente de activación de reintento.
    // DAO Flow Escribir cuando falla la colección _loadError,por retryLoad() Borrar e incrementar la fuente de activación
    // Hacer flatMapLatest volver a suscribirse Room Flow,Implementar reintentos.
    private val _loadError = MutableStateFlow<String?>(null)
    private val retryTrigger = MutableStateFlow(0)

    /** v1.0.18: Paginación — Límite de carga actual(loadMore se incrementa cuando se alcanza el fondo). */
    private val _currentLimit = MutableStateFlow(DEFAULT_LIMIT)
    private val currentLimit: StateFlow<Int> = _currentLimit

    /** Grabación normal(deleted=0),Ordene por actualizado en orden descendente, arriba primero. */
    private val activeNotesFlow: Flow<List<QuickNoteEntity>> = retryTrigger.flatMapLatest {
        dao.observeActive(limit = MAX_LIMIT).catch { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.w(TAG, "No se pudieron cargar registros rápidos: ${e.message}")
            _loadError.value = e.message ?: context.getString(R.string.common_load_failed)
            emit(emptyList())
        }
    }

    /** Registros de la papelera de reciclaje(deleted=1),Ordenar por eliminadoEn descendente. */
    private val trashFlow: Flow<List<QuickNoteEntity>> = retryTrigger.flatMapLatest {
        dao.observeTrash().catch { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.w(TAG, "No se pudo cargar la papelera de reciclaje: ${e.message}")
            _loadError.value = e.message ?: context.getString(R.string.common_load_failed)
            emit(emptyList())
        }
    }

    /** v1.0.18: Todas las carpetas que no estén vacías. */
    private val foldersFlow: Flow<List<String>> = retryTrigger.flatMapLatest {
        dao.observeFolders().catch { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.w(TAG, "No se pudo cargar la carpeta: ${e.message}")
            _loadError.value = e.message ?: context.getString(R.string.common_load_failed)
            emit(emptyList())
        }
    }

    /**
     * Estado completo de la interfaz de usuario:
     *  - notes: Aplicar palabras clave de búsqueda + etiquetas + lista filtrada de carpetas(Presione currentLimit nuevamente para truncar)
     *  - allTags: Derivado de ActiveNotes completo(Desordenamiento de minúsculas)
     *  - folders: Tomado de carpetasFlow
     *  - trashItems: Tomado directamente de trashFlow
     *  - showTrash: Cambiado por usuario
     *  - hasMore: filtered.size > currentLimit(Aún quedan registros descargados)
     */
    val state: StateFlow<QuickNotesUiState> =
        combine(
            activeNotesFlow,
            trashFlow,
            foldersFlow,
            _searchKeyword,
            _selectedTag,
            _selectedFolder,
            _showTrash,
            currentLimit,
            _loadError,
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            val active = values[0] as List<QuickNoteEntity>

            @Suppress("UNCHECKED_CAST")
            val trash = values[1] as List<QuickNoteEntity>

            @Suppress("UNCHECKED_CAST")
            val folders = values[2] as List<String>
            val keyword = values[3] as String
            val tag = values[4] as String?
            val folder = values[5] as String?
            val showTrash = values[6] as Boolean
            val limit = values[7] as Int
            val loadError = values[8] as String?

            val filtered = filterNotes(active, keyword, tag, folder)
            val allTags = active
                .flatMap { it.tags }
                .map { it.lowercase() }
                .distinct()
                .sorted()
            QuickNotesUiState(
                notes = filtered.take(limit),
                searchKeyword = keyword,
                selectedTag = tag,
                selectedFolder = folder,
                allTags = allTags,
                folders = folders,
                trashItems = trash,
                showTrash = showTrash,
                isInitialLoading = false,
                hasMore = filtered.size > limit,
                error = loadError,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = QuickNotesUiState(),
        )

    /** Actualizar las palabras clave de búsqueda. Una cadena vacía borra la búsqueda. */
    fun onSearchKeywordChange(keyword: String) {
        _searchKeyword.value = keyword
    }

    /** Alternar filtrado de etiquetas. Haga clic en la misma etiqueta nuevamente para anular su selección. */
    fun onTagSelected(tag: String) {
        _selectedTag.value = if (_selectedTag.value == tag) null else tag
    }

    /**
     * v1.0.18: Alternar filtrado de carpetas.
     * - null: Mostrar todas las carpetas
     * - "": Sin categoría(folder Registros vacíos)
     * - Otros: Especificar carpeta
     * Haga clic en la misma carpeta nuevamente para anular su selección.(volver a todos).
     */
    fun onFolderSelected(folder: String?) {
        _selectedFolder.value = if (_selectedFolder.value == folder) null else folder
    }

    /** Alternar visualización del panel de la papelera de reciclaje/Oculto. */
    fun toggleTrash(show: Boolean) {
        _showTrash.value = show
    }

    /**
     * v1.0.18: cargar más(Paginación).
     * Se llama cuando se llega al final de la lista.,Actualiza la vista después de incrementar el límite.
     */
    fun loadMore() {
        if (_currentLimit.value >= MAX_LIMIT) return
        _currentLimit.value = (_currentLimit.value + DEFAULT_LIMIT).coerceAtMost(MAX_LIMIT)
    }

    /**
     * ST-01: Vuelva a intentar la entrada después de un error al cargar la lista.
     * Borre el mensaje de error e incremente la fuente de activación,flatMapLatest Volveré a suscribirme a la recarga de Room Flow.
     */
    fun retryLoad() {
        _loadError.value = null
        retryTrigger.value++
    }

    /**
     * La notificación garantiza que el registro de destino entre en la lista actual cuando se haga clic en él.
     *
     * La página de registro rápido solo muestra los primeros 50 registros de forma predeterminada; los recordatorios pueden provenir de registros anteriores.
     * Por lo tanto, primero lea el objetivo para confirmar que todavía es un registro válido, luego relaje el límite de visualización de esta página y déjelo en la página para el posicionamiento de desplazamiento.
     */
    fun revealNote(noteId: String) {
        viewModelScope.launch {
            val note = dao.getById(noteId)
            if (note != null && !note.deleted) {
                _currentLimit.value = MAX_LIMIT
            }
        }
    }

    /**
     * Añade una nota rápida.
     *
     * v1.0.18: Agregar parámetro carpeta/tipo de contenido(Carpeta vacía predeterminada + plain).
     */
    fun add(title: String, content: String, tags: List<String>, folder: String = "", contentType: String = "plain") {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            dao.upsert(
                QuickNoteEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    title = title,
                    content = content,
                    tags = tags,
                    pinned = false,
                    deleted = false,
                    deletedAt = 0,
                    createdAt = now,
                    updatedAt = now,
                    folder = folder,
                    contentType = contentType,
                ),
            )
        }
    }

    /**
     * Actualizar registro especificado(actualización parcial).
     *
     * v1.0.18: aumentar folder / contentType / reminderAt parámetros(Pase nulo para indicar que no hay modificación).
     */
    fun update(
        id: String,
        title: String? = null,
        content: String? = null,
        tags: List<String>? = null,
        folder: String? = null,
        contentType: String? = null,
        reminderAt: Long? = null,
    ) {
        viewModelScope.launch {
            val existing = dao.getById(id) ?: return@launch
            val newContent = content
            // Reparación de auditoría (0.6): También se consideran cadenas vacías."Contenido sin cambios",El texto cifrado original no debe sobrescribirse con cifrado de cadena vacía.
            // Cuando el cuadro de diálogo abre la nota cifrada, el contenido está vacío.,Si el usuario solo cambia el título,content Pasar cadena vacía,
            // Implementación original newContent != null voluntad encrypt("") escribir,El contenido original se pierde permanentemente.
            val finalEncryptedContent = if (existing.encrypted) {
                if (newContent != null && newContent.isNotBlank()) {
                    runCatching { noteCipher.encrypt(newContent) }.getOrElse {
                        Logger.w(TAG, "Falló el cifrado de actualización de notas rápidas: ${it.message}")
                        existing.encryptedContent
                    }
                } else {
                    existing.encryptedContent
                }
            } else {
                existing.encryptedContent
            }
            val finalContent = if (existing.encrypted) "" else (newContent ?: existing.content)
            dao.upsert(
                existing.copy(
                    title = title ?: existing.title,
                    content = finalContent,
                    encryptedContent = finalEncryptedContent,
                    tags = tags ?: existing.tags,
                    folder = folder ?: existing.folder,
                    contentType = contentType ?: existing.contentType,
                    reminderAt = reminderAt ?: existing.reminderAt,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            // Sincronizar el despertador con recordatorio
            if (reminderAt != null) {
                syncReminder(existing.id, existing.title, existing.content, reminderAt)
            }
        }
    }

    /** Mover a la papelera de reciclaje(soft delete). */
    fun delete(id: String) {
        viewModelScope.launch { dao.moveToTrash(id) }
    }

    /** Restaurar desde la Papelera de reciclaje. */
    fun restore(id: String) {
        viewModelScope.launch { dao.restore(id) }
    }

    /** Eliminar permanentemente un solo registro(No recuperable). */
    fun deletePermanent(id: String) {
        viewModelScope.launch {
            // v2.6.7: Obtenga primero la lista de archivos adjuntos y luego elimine la biblioteca —— Una vez que se pierde un registro, no se puede leer y el archivo queda huérfano permanentemente.
            val attachments = withContext(Dispatchers.IO) {
                dao.getById(id)?.let { parseAttachments(it.attachmentsJson) } ?: emptyList()
            }
            dao.deletePermanent(id)
            if (attachments.isNotEmpty()) {
                withContext(Dispatchers.IO) { attachmentStore.deleteAll(attachments) }
            }
        }
    }

    /** Alternar estado fijado. */
    fun setPinned(id: String, pinned: Boolean) {
        viewModelScope.launch { dao.setPinned(id, pinned) }
    }

    /**
     * v1.0.18: Configure la carpeta.
     * folder Pasar una cadena vacía significa salir de la carpeta(Sin categoría).
     */
    fun setFolder(id: String, folder: String) {
        viewModelScope.launch { dao.setFolder(id, folder) }
    }

    /**
     * v1.0.18: Cambiar tipo de contenido(plain ↔ markdown).
     */
    fun setContentType(id: String, contentType: String) {
        viewModelScope.launch { dao.setContentType(id, contentType) }
    }

    /**
     * v1.0.18: Establecer recordatorios.
     * reminderAt=0 Indica cancelar el recordatorio.;De lo contrario, envíe a través de ReminderStore + AlarmManager.
     */
    fun setReminder(id: String, title: String, message: String, reminderAt: Long) {
        viewModelScope.launch {
            dao.setReminderAt(id, reminderAt)
            syncReminder(id, title, message, reminderAt)
        }
    }

    /**
     * v1.0.18: Cancelar recordatorio.
     */
    fun cancelReminder(id: String) {
        viewModelScope.launch {
            dao.setReminderAt(id, 0)
            ReminderAlarmScheduler.cancel(context, id)
            // ReminderStore También puede haber entradas correspondientes en,intenta eliminar
            reminderStore.list().firstOrNull { it.id == id }?.let { reminderStore.remove(it.id) }
        }
    }

    /**
     * v1.0.18: Cambiar el estado de cifrado.
     *
     * Se implementa el cifrado: NoteCipher Cifrado y descifrado real AES-GCM utilizando Android Keystore,
     * Formato de texto cifrado base64(iv):base64(ciphertext),Exportar/La importación conserva el contenido cifrado.
     * Cuando falla el descifrado, se migrará el antiguo texto cifrado del marcador de posición.(Evite la pérdida de datos de versiones antiguas).
     */
    fun setEncrypted(id: String, encrypted: Boolean) {
        viewModelScope.launch {
            val existing = dao.getById(id) ?: return@launch
            if (encrypted) {
                // Reparación de auditoría (0.7): El texto sin formato no debe degradarse si falla el cifrado.
                // La implementación original de getOrElse devuelve escritura de texto sin formato. encryptedContent,UI mostrar"cifrado"texto plano,
                // Los usuarios creen erróneamente que están protegidos. Déjelo como está y avise en caso de error.
                if (existing.content.isBlank()) {
                    MuseToast.show("Nada que cifrar")
                    return@launch
                }
                val cipher = runCatching { noteCipher.encrypt(existing.content) }.getOrNull()
                if (cipher == null) {
                    MuseToast.show("Error de cifrado,Las notas permanecen como están.")
                    Logger.w(TAG, "Falló el cifrado rápido de notas,Mantenga el texto sin formato sin marcar para cifrarlo")
                    return@launch
                }
                dao.setEncrypted(id, true, cipher)
                MuseToast.show("cifrado")
            } else {
                // Reparación de auditoría (0.7): El descifrado falla y se conservan el texto cifrado y la marca de cifrado.,El texto cifrado no debe incluirse en el contenido como texto sin formato.
                if (existing.encryptedContent.isNotBlank()) {
                    val plain = runCatching { noteCipher.decrypt(existing.encryptedContent) }.getOrNull()
                    if (plain == null) {
                        MuseToast.show("Falló el descifrado,Las notas permanecen cifradas")
                        Logger.w(TAG, "Falló el descifrado rápido de la nota,mantener texto cifrado")
                        return@launch
                    }
                    dao.setEncrypted(id, false, plain)
                } else {
                    dao.setEncrypted(id, false, existing.content)
                }
                MuseToast.show("Descifrado")
            }
        }
    }

    /**
     * v1.0.18: Agregue la ruta del archivo adjunto de la imagen.
     * attachmentsJson Almacenado como cadena de matriz JSON.
     *
     * v2.6.7: Cambiar a internal —— Lo externo debería ir [attachImage](Es responsable de content URI
     * La entidad del archivo se copia en el directorio de la aplicación) para evitar que la persona que llama transmita erróneamente un URI de álbum que se lee solo una vez y luego deja de ser válido.
     */
    internal fun addAttachment(id: String, path: String) {
        viewModelScope.launch {
            val existing = dao.getById(id) ?: return@launch
            val list = parseAttachments(existing.attachmentsJson).toMutableList()
            if (path !in list) list.add(path)
            dao.upsert(existing.copy(attachmentsJson = encodeAttachments(list), updatedAt = System.currentTimeMillis()))
        }
    }

    /**
     * v2.6.7: Agregue archivos adjuntos desde el selector de mapas del sistema.
     *
     * Primero se deben copiar los bytes en el directorio privado de la aplicación:content URI Sólo permiso de lectura temporal,
     * Guardar solo el URI hará que la nota adjunta se convierta en una imagen rota la próxima vez que se abra.
     * Devolver falso indica que la importación falló y la persona que llama avisa al usuario.
     */
    fun attachImage(id: String, uri: android.net.Uri, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) { attachmentStore.importOne(uri) }
            if (path == null) {
                MuseToast.show(context.getString(R.string.quick_notes_attachment_failed))
                onResult(false)
                return@launch
            }
            addAttachment(id, path)
            onResult(true)
        }
    }

    /**
     * v2.6.7: Eliminar (y eliminar) un archivo adjunto.
     *
     * Simplemente eliminar la ruta de la lista sin eliminar el archivo perderá almacenamiento; por el contrario, eliminar el archivo primero y luego escribir la biblioteca
     * No escribir deja un registro sucio que apunta a un archivo inexistente. Tome el último orden: primero asegúrese de que las listas sean consistentes.
     */
    fun removeAttachment(id: String, uriString: String) {
        viewModelScope.launch {
            val existing = dao.getById(id) ?: return@launch
            val list = parseAttachments(existing.attachmentsJson).filterNot { it == uriString }
            dao.upsert(existing.copy(attachmentsJson = encodeAttachments(list), updatedAt = System.currentTimeMillis()))
            // Elimine únicamente archivos administrados por este almacenamiento:attachmentsJson Se pueden incorporar rutas externas con los datos importados,
            // Sin esta restricción, se producirá la eliminación arbitraria de archivos.
            if (attachmentStore.isManaged(uriString)) {
                withContext(Dispatchers.IO) { attachmentStore.delete(uriString) }
            }
        }
    }

    /** Vaciar toda la papelera de reciclaje(Primero tome la última instantánea y luego elimínela permanentemente una por una.). */
    fun clearTrash() {
        viewModelScope.launch {
            val trash = trashFlow.first()
            // v2.6.7: Recicle sincrónicamente los archivos adjuntos; de lo contrario, la Papelera de reciclaje estará llena de imágenes huérfanas cuando la vacíe.
            val doomed = trash.flatMap { parseAttachments(it.attachmentsJson) }
            trash.forEach { dao.deletePermanent(it.id) }
            if (doomed.isNotEmpty()) {
                withContext(Dispatchers.IO) { attachmentStore.deleteAll(doomed) }
            }
        }
    }

    // ── v1.0.18: Exportar/Importar ──────────────────────────────────────────────────

    /**
     * v1.0.18: Exporte todos los registros normales como cadenas Markdown.
     *
     * Cada formato de registro:
     * ```
     * # Título
     *
     * Texto
     *
     * > etiqueta: #tag1 #tag2 | carpeta: folder | Hora de actualización: yyyy-MM-dd HH:mm
     * ---
     * ```
     */
    suspend fun exportToMarkdown(): String {
        val notes = dao.observeActive(limit = MAX_LIMIT).first()
        if (notes.isEmpty()) return ""
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return buildString {
            notes.forEach { note ->
                if (note.title.isNotBlank()) {
                    appendLine("# ${note.title}")
                    appendLine()
                }
                if (note.content.isNotBlank()) {
                    appendLine(note.content)
                    appendLine()
                }
                val meta = buildList {
                    if (note.tags.isNotEmpty()) {
                        add(
                            context.getString(R.string.quick_notes_export_tags, note.tags.joinToString(" ") { "#$it" }),
                        )
                    }
                    if (note.folder.isNotBlank()) add(context.getString(R.string.quick_notes_export_folder, note.folder))
                    add(context.getString(R.string.quick_notes_export_updated_at, fmt.format(java.util.Date(note.updatedAt))))
                }.joinToString(" | ")
                appendLine("> $meta")
                appendLine("---")
                appendLine()
            }
        }.trimEnd()
    }

    /**
     * v1.0.18: Exporte todos los registros normales como cadena JSON.
     * Contiene campos completos(folder / contentType / tags / Marca de tiempo, etc.),Se puede utilizar para la migración entre dispositivos.
     */
    suspend fun exportToJson(): String {
        val notes = dao.observeActive(limit = MAX_LIMIT).first()
        val dtos = notes.map { it.toExportDto() }
        return AppJson.encodeToString(ListSerializer(QuickNoteExportDto.serializer()), dtos)
    }

    /**
     * v1.0.18: Importar registros desde una cadena JSON.
     *
     * - JSON Formateado por [exportToJson] salida([QuickNoteExportDto] lista)
     * - Los identificadores existentes se sobrescribirán(OnConflictStrategy.REPLACE)
     * - Los registros importados se marcan como eliminados false(Regreso forzado al estado normal.)
     * v2.0: Devolver si falla el análisis -1(con“Exitoso pero sin récord”0 distinción),El proceso de importación solicita la redacción correspondiente.
     *
     * @return Número de registros importados correctamente;JSON Devuelve cuando no se puede analizar -1
     */
    suspend fun importFromJson(json: String): Int {
        return try {
            val dtos = AppJson.decodeFromString(ListSerializer(QuickNoteExportDto.serializer()), json)
            dtos.forEach { dto ->
                dao.upsert(dto.toEntity())
            }
            dtos.size
        } catch (e: Exception) {
            Logger.w(TAG, "No se pudieron importar registros rápidos: ${e.message}")
            -1
        }
    }

    // ── auxiliar interno ──────────────────────────────────────────────────────────────

    /**
     * Aplique palabras clave de búsqueda + etiquetas + filtros de carpeta.
     *
     * concordancia de palabras clave title/content/tags(No distingue entre mayúsculas y minúsculas);
     * lista de etiquetas coincidentes(No distingue entre mayúsculas y minúsculas,Coincide exactamente con una sola etiqueta);
     * La carpeta coincide con el campo de la carpeta(La carpeta de cadena vacía coincide con sin categoría).
     *
     * v1.0.18(5.10 Optimización de búsqueda de texto completo): Cuando hay una palabra clave de búsqueda,Ordene por relevancia en lugar de mantener el orden original.
     * Puntuación de relevancia(Cuanto más alto, más adelante):
     *  - Los títulos son exactamente iguales.(+100)/ Coincidencia de prefijo de título(+60)/ El título contiene(+30)
     *  - El contenido contiene(+10)/ la etiqueta contiene(+15)
     *  - Peso extra para fijado(+50),Dar prioridad a los registros fijados cuando sean de relevancia similar
     * Ordenar por actualizado en en orden descendente al mismo tiempo(Nuevos registros primero). Mantener el orden original cuando no haya palabras clave(Arriba + actualizadoEn orden descendente).
     */
    private fun filterNotes(notes: List<QuickNoteEntity>, keyword: String, tag: String?, folder: String?): List<QuickNoteEntity> {
        var filtered = notes
        if (keyword.isNotBlank()) {
            val kw = keyword.lowercase()
            filtered = filtered.filter {
                it.title.lowercase().contains(kw) ||
                    it.content.lowercase().contains(kw) ||
                    it.tags.any { t -> t.lowercase().contains(kw) }
            }
        }
        if (!tag.isNullOrBlank()) {
            filtered = filtered.filter { note ->
                note.tags.any { it.equals(tag, ignoreCase = true) }
            }
        }
        if (folder != null) {
            filtered = filtered.filter { it.folder == folder }
        }
        // v1.0.18(5.10): Ordenar por relevancia al buscar
        if (keyword.isBlank()) return filtered
        val kw = keyword.lowercase()
        return filtered
            .map { it to relevanceScore(it, kw) }
            .sortedWith(
                compareByDescending<Pair<QuickNoteEntity, Int>> { it.second }
                    .thenByDescending { it.first.pinned }
                    .thenByDescending { it.first.updatedAt },
            )
            .map { it.first }
    }

    /** Calcule la puntuación de relevancia de un solo registro para una palabra clave(Cuanto más grande, más relevante). */
    private fun relevanceScore(note: QuickNoteEntity, kw: String): Int {
        var score = 0
        val title = note.title.lowercase()
        val content = note.content.lowercase()
        when {
            title == kw -> score += 100
            title.startsWith(kw) -> score += 60
            title.contains(kw) -> score += 30
        }
        if (content.contains(kw)) score += 10
        if (note.tags.any { it.lowercase().contains(kw) }) score += 15
        if (note.pinned) score += 50
        return score
    }

    /**
     * v1.0.18: Sincronizar el despertador con recordatorio.
     * reminderAt=0 Cancelar alarma,De lo contrario registre un despertador preciso(Si no hay un permiso preciso, se degradará a no preciso y se guiará al usuario para que lo abra.).
     */
    private fun syncReminder(id: String, title: String, message: String, reminderAt: Long) {
        if (reminderAt <= 0) {
            ReminderAlarmScheduler.cancel(context, id)
            return
        }
        // Depositar primero ReminderStore(Eliminado por ReminderAlarmReceiver después de activarse)
        reminderStore.list().firstOrNull { it.id == id }?.let { reminderStore.remove(it.id) }
        reminderStore.add(
            title = title,
            message = message,
            triggerAtMillis = reminderAt,
            id = id,
            targetType = ReminderAlarmReceiver.TARGET_QUICK_NOTE,
            targetId = id,
        )
        val result = ReminderAlarmScheduler.schedule(
            context = context,
            id = id,
            title = title,
            message = message,
            triggerAtMillis = reminderAt,
            targetType = ReminderAlarmReceiver.TARGET_QUICK_NOTE,
            targetId = id,
        )
        when {
            !result.scheduled -> {
                reminderStore.markScheduleFailed(id, result.error ?: "alarm_schedule_failed")
                MuseToast.show(context.getString(R.string.tool_reminder_schedule_failed), 3500L)
            }
            result.needsExactPermission -> {
                // Se ha degradado a un despertador poco preciso. Primero asegúrese de que "sonará" y luego guíe al usuario para que habilite el permiso "Despertador y recordatorio".
                reminderStore.markScheduled(id, exact = false, atMillis = System.currentTimeMillis())
                MuseToast.show(context.getString(R.string.reminder_exact_permission_toast))
                ReminderAlarmScheduler.requestExactAlarmPermission(context)
            }
            else -> reminderStore.markScheduled(id, exact = true, atMillis = System.currentTimeMillis())
        }
    }

    companion object {
        private const val TAG = "QuickNotesViewModel"

        /** v1.0.18: Número de artículos cargados por primera vez(Inicio de salto de página limit). */
        private const val DEFAULT_LIMIT = 50

        /** v1.0.18: Número máximo de artículos cargados(observeActive El límite superior LIMIT de). */
        private const val MAX_LIMIT = 500

        /** Analiza los archivos adjuntos Json en una lista de rutas. */
        fun parseAttachments(json: String): List<String> {
            if (json.isBlank()) return emptyList()
            return try {
                AppJson.decodeFromString<List<String>>(json)
            } catch (_: Exception) {
                emptyList()
            }
        }

        /** La lista de rutas codificadas es adjuntosJson. */
        fun encodeAttachments(list: List<String>): String {
            return if (list.isEmpty()) "" else AppJson.encodeToString(list)
        }
    }
}

/**
 * v1.0.18: Exportación rápida de registros/importar DTO(JSON formato de serialización).
 * con [QuickNoteEntity] Alineación de campo,Pero no incluye anotación de habitación.,Migre fácilmente entre dispositivos.
 */
@Serializable
data class QuickNoteExportDto(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("content") val content: String,
    @SerialName("tags") val tags: List<String>,
    @SerialName("pinned") val pinned: Boolean,
    @SerialName("folder") val folder: String = "",
    @SerialName("content_type") val contentType: String = "plain",
    @SerialName("attachments_json") val attachmentsJson: String = "",
    @SerialName("reminder_at") val reminderAt: Long = 0,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("encrypted") val encrypted: Boolean = false,
    @SerialName("encrypted_content") val encryptedContent: String = "",
)

/** poner [QuickNoteEntity] Convertir para exportar DTO. */
private fun QuickNoteEntity.toExportDto(): QuickNoteExportDto = QuickNoteExportDto(
    id = id,
    title = title,
    content = content,
    tags = tags,
    pinned = pinned,
    folder = folder,
    contentType = contentType,
    attachmentsJson = attachmentsJson,
    reminderAt = reminderAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    encrypted = encrypted,
    encryptedContent = encryptedContent,
)

/** Convertir exportación DTO a [QuickNoteEntity](Para importar,deleted=false/encrypted=false). */
private fun QuickNoteExportDto.toEntity(): QuickNoteEntity = QuickNoteEntity(
    id = id,
    title = title,
    content = content,
    tags = tags,
    pinned = pinned,
    deleted = false,
    deletedAt = 0,
    createdAt = createdAt,
    updatedAt = updatedAt,
    folder = folder,
    contentType = contentType,
    attachmentsJson = attachmentsJson,
    reminderAt = reminderAt,
    encrypted = encrypted,
    encryptedContent = encryptedContent,
)
