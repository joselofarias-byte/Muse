package io.zer0.memory.pin

import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Almacenamiento de memoria de pines.
 *
 * El contenido que el usuario pide activamente a la IA que recuerde siempre se conserva en el mensaje del sistema.
 * Almacenamiento de doble formato:
 *  - JSON Archivo (lectura y escritura estructurada y programática)
 *  - Markdown Archivo (legible por humanos, depuración)/exportar)
 *
 * Deduplicación: No se agregará el mismo contenido repetidamente.
 * Combinación de archivos dobles: combine por ID y prevalecerá la entrada más reciente en UpdatedAt.
 */
class PinnedMemoryStore(
    private val storageDir: File,
) {

    companion object {
        private const val TAG = "PinnedMemoryStore"
        private const val JSON_FILE = "pinned-memory.json"
        private const val MD_FILE = "pinned.md"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val jsonFile = File(storageDir, JSON_FILE)
    private val mdFile = File(storageDir, MD_FILE)

    // Revisión de corrección (2.0 C-04): mtime caché — Reutilice los resultados de la memoria cuando el archivo no haya cambiado.
    // SystemPromptAssembler Se llama cada vez que se ensambla el indicador del sistema. renderForPrompt,
    // Leer archivos dobles cada vez sin caché(JSON + Markdown fusionar),E/S de disco innecesarias durante conversaciones de alta frecuencia.
    @Volatile
    private var cacheJsonMtime: Long = -1L

    @Volatile
    private var cacheMdMtime: Long = -1L

    @Volatile
    private var cachedEntries: List<PinnedEntry>? = null

    /**
     * Revisión de corrección (B-20): Bloqueo de escritura a nivel de proceso — add/removeById/removeByKeyword/removeByContent/
     * removeByContentFlexible/replace LoadEntries+writeAmbos son leídos-cambiar-escribir,Llamadas simultáneas
     * Se sobrescribirá con la instantánea anterior de la otra parte.(Actualizaciones faltantes). Todos los métodos de escritura mantienen el bloqueo en su totalidad.,Se garantiza que LoadEntries+writeBoth será atómico.
     * (Consistente con el modo de bloqueo de lápida de FactStore,pero por ejemplo nivel;PinnedMemoryStore No existe ningún escenario en el que varias instancias compartan archivos.)
     */
    private val writeLock = Mutex()

    @Serializable
    data class PinnedEntry(
        val id: String,
        val content: String,
        val createdAt: String,
        val updatedAt: String,
        /** null Indica una versión antigua/Pin global explícito;No nulo significa que el asistente está fijado de forma privada. */
        val assistantId: String? = null,
    )

    /** Cargar todos los recuerdos anclados (JSON Fusionado con Markdown, lo más nuevo tiene prioridad). */
    suspend fun getAll(): List<PinnedEntry> = withContext(Dispatchers.IO) {
        loadEntries()
    }

    /** Añade un recuerdo fijado. Elimina la duplicación de contenido idéntico. ID de entrada de devolución. */
    suspend fun add(content: String, assistantId: String? = null): String = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val trimmed = content.trim()
            if (trimmed.isEmpty()) return@withLock ""
            val scopedAssistantId = assistantId?.trim()?.takeIf { it.isNotEmpty() }
            val existing = loadEntries()
            // Eliminar duplicados
            if (existing.any { it.content == trimmed && it.assistantId == scopedAssistantId }) {
                Logger.d(TAG, "pinned memory dedup: already exists")
                return@withLock existing.first { it.content == trimmed && it.assistantId == scopedAssistantId }.id
            }
            val now = Instant.now().toString()
            val entry = PinnedEntry(
                id = UUID.randomUUID().toString(),
                content = trimmed,
                createdAt = now,
                updatedAt = now,
                assistantId = scopedAssistantId,
            )
            writeBoth(existing + entry)
            Logger.d(TAG, "pinned memory added: ${entry.id}")
            entry.id
        }
    }

    /** Eliminar por identificación. Devuelve si tuvo éxito. */
    suspend fun removeById(id: String): Boolean = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val existing = loadEntries()
            val filtered = existing.filter { it.id != id }
            if (filtered.size == existing.size) return@withLock false
            writeBoth(filtered)
            Logger.d(TAG, "pinned memory removed by id: $id")
            true
        }
    }

    /** Eliminar por palabra clave (el primer elemento cuyo contenido contiene la palabra clave). Devuelve si tuvo éxito. */
    suspend fun removeByKeyword(keyword: String): Boolean = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val existing = loadEntries()
            val target = existing.firstOrNull { it.content.contains(keyword, ignoreCase = true) }
            if (target == null) return@withLock false
            val filtered = existing.filter { it.id != target.id }
            writeBoth(filtered)
            Logger.d(TAG, "pinned memory removed by keyword: $keyword")
            true
        }
    }

    /**
     * F-8: Elimine exactamente según el contenido (el primer elemento idéntico después de eliminar los espacios en blanco iniciales y finales).
     *
     * con [removeByKeyword](Coincidencia inclusiva) tiene una semántica diferente y se utiliza cuando la interfaz de usuario de la página de memoria no está fijada.
     * Elimine el contenido correspondiente con precisión para evitar eliminar accidentalmente otros elementos fijados que contengan este texto.
     */
    suspend fun removeByContent(content: String): Boolean = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val target = loadEntries().firstOrNull { it.content.trim() == content.trim() }
            if (target == null) return@withLock false
            removeByIdLocked(target.id)
        }
    }

    /**
     * Revisión de corrección (B-18): Desenganchado conservadoramente — Priorizar coincidencias exactas,Degenera en coincidencia de inclusión cuando falla la coincidencia exacta
     * (Contiene ignoreCase),Siempre que el contenido de la faceta haya sido reescrito(PinnedMemoryStore Sin reescritura,Pero el lado de la inyección externa
     * la redacción puede cambiar)Limpia los residuos restantes. Nunca elimines un elemento por error y devuélvelo al primer elemento con el contenido original.
     * Devuelve si la eliminación fue exitosa.
     */
    suspend fun removeByContentFlexible(content: String): Boolean = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val trimmed = content.trim()
            val existing = loadEntries()
            val target = existing.firstOrNull { it.content.trim() == trimmed }
                ?: existing.firstOrNull { it.content.contains(trimmed, ignoreCase = true) }
            if (target == null) return@withLock false
            writeBoth(existing.filter { it.id != target.id })
            Logger.d(TAG, "pinned memory removed (flexible): ${target.id}")
            true
        }
    }

    /** Reemplaza el contenido de la identificación especificada. Devuelve si tuvo éxito. */
    suspend fun replace(id: String, newContent: String): Boolean = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val trimmed = newContent.trim()
            if (trimmed.isEmpty()) return@withLock false
            val existing = loadEntries()
            val idx = existing.indexOfFirst { it.id == id }
            if (idx < 0) return@withLock false
            val updated = existing.toMutableList()
            updated[idx] = updated[idx].copy(content = trimmed, updatedAt = Instant.now().toString())
            writeBoth(updated)
            Logger.d(TAG, "pinned memory replaced: $id")
            true
        }
    }

    /** Genera texto inyectado en el indicador del sistema (todas las memorias fijadas unidas). */
    suspend fun renderForPrompt(): String = withContext(Dispatchers.IO) {
        renderEntries(loadEntries())
    }

    /** Generar una memoria adhesiva visible para el asistente especificado;Las entradas antiguas solo se devuelven si lo global está permitido explícitamente. */
    suspend fun renderForAssistant(assistantId: String, includeGlobal: Boolean): String = withContext(Dispatchers.IO) {
        renderEntries(getForAssistant(assistantId, includeGlobal))
    }

    /** Consulta la memoria adhesiva visible para el asistente especificado. */
    suspend fun getForAssistant(assistantId: String, includeGlobal: Boolean): List<PinnedEntry> = withContext(Dispatchers.IO) {
        val normalized = assistantId.trim()
        loadEntries().filter { entry ->
            entry.assistantId == normalized || (includeGlobal && entry.assistantId == null)
        }
    }

    /** Presione el contenido en el alcance del asistente para desanclarlo.,Evita que la memoria del mismo nombre borre accidentalmente los datos de otros asistentes. */
    suspend fun removeByIdForAssistant(
        id: String,
        assistantId: String,
        includeGlobal: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val existing = loadEntries()
            val target = existing.firstOrNull { entry ->
                entry.id == id &&
                    (entry.assistantId == assistantId || (includeGlobal && entry.assistantId == null))
            } ?: return@withLock false
            writeBoth(existing.filter { it.id != target.id })
            Logger.d(TAG, "pinned memory removed for assistant: ${target.id}")
            true
        }
    }

    /** Presione el contenido en el alcance del asistente para desanclarlo.,Evita que la memoria del mismo nombre borre accidentalmente los datos de otros asistentes. */
    suspend fun removeByContentFlexibleForAssistant(
        content: String,
        assistantId: String,
        includeGlobal: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val trimmed = content.trim()
            val existing = loadEntries()
            val target = existing.firstOrNull { entry ->
                entry.assistantId == assistantId ||
                    (includeGlobal && entry.assistantId == null)
            }?.takeIf { entry ->
                entry.content.trim() == trimmed || entry.content.contains(trimmed, ignoreCase = true)
            }
            if (target == null) return@withLock false
            writeBoth(existing.filter { it.id != target.id })
            Logger.d(TAG, "pinned memory removed for assistant: ${target.id}")
            true
        }
    }

    private fun renderEntries(entries: List<PinnedEntry>): String {
        if (entries.isEmpty()) return ""
        return buildString {
            appendLine("## Pinned Memories (Lo que los usuarios piden para recordar siempre)")
            for (e in entries) {
                appendLine("- ${e.content}")
            }
        }.trimEnd()
    }

    // ─── interno I/O ───

    /**
     * Revisión de corrección (B-20): La persona que llama ya debe tener en espera [writeLock] Eliminar por identificación.
     * removeByContent Ya dentro de withLock,no otra vez removeById(Tomará repetidamente el punto muerto de bloqueo).
     */
    private fun removeByIdLocked(id: String): Boolean {
        val existing = loadEntries()
        val filtered = existing.filter { it.id != id }
        if (filtered.size == existing.size) return false
        writeBoth(filtered)
        Logger.d(TAG, "pinned memory removed by id: $id")
        return true
    }

    private fun loadEntries(): List<PinnedEntry> {
        // C-04: mtime sin cambios → Volver directamente al caché
        val jsonMtime = if (jsonFile.exists()) jsonFile.lastModified() else -1L
        val mdMtime = if (mdFile.exists()) mdFile.lastModified() else -1L
        val cached = cachedEntries
        if (cached != null && jsonMtime == cacheJsonMtime && mdMtime == cacheMdMtime) {
            return cached
        }
        val entries = mergeByNewest(readJson(), readMarkdown())
        cachedEntries = entries
        cacheJsonMtime = jsonMtime
        cacheMdMtime = mdMtime
        return entries
    }

    private fun mergeByNewest(primary: List<PinnedEntry>, secondary: List<PinnedEntry>): List<PinnedEntry> {
        val byId = linkedMapOf<String, PinnedEntry>()
        for (entry in primary) byId[entry.id] = entry
        for (entry in secondary) {
            val existing = byId[entry.id]
            if (existing == null || compareTimestamps(entry.updatedAt, existing.updatedAt) > 0) {
                byId[entry.id] = entry
            }
        }
        return byId.values.toList()
    }

    private fun compareTimestamps(a: String, b: String): Int {
        val aTime = runCatching { Instant.parse(a) }.getOrNull()
        val bTime = runCatching { Instant.parse(b) }.getOrNull()
        return when {
            aTime == null && bTime == null -> 0
            aTime == null -> -1
            bTime == null -> 1
            else -> aTime.compareTo(bTime)
        }
    }

    private fun readJson(): List<PinnedEntry> {
        if (!jsonFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(PinnedEntry.serializer()), jsonFile.readText())
        }.getOrElse {
            Logger.w(TAG, "readJson failed: ${it.message}")
            emptyList()
        }
    }

    private fun readMarkdown(): List<PinnedEntry> {
        if (!mdFile.exists()) return emptyList()
        val lines = runCatching { mdFile.readLines() }.getOrElse {
            Logger.w(TAG, "readMarkdown failed: ${it.message}")
            return emptyList()
        }
        val entries = mutableListOf<PinnedEntry>()
        var pendingContent: String? = null
        var pendingMeta: Map<String, String> = emptyMap()
        for (line in lines) {
            val trimmed = line.trim()
            val bullet = Regex("^[-*+]\\s+(.+)$").find(trimmed)
            val meta =
                Regex(
                    "^<!--\\s*id: ([^|]+)\\| created: ([^|]+)(?:\\| updated: ([^|]+))?" +
                        "(?:\\| assistant: ([^|]*))?\\s*-->$",
                ).find(trimmed)
            when {
                bullet != null -> {
                    pendingContent?.let { content ->
                        val metaId = pendingMeta["id"] ?: UUID.randomUUID().toString()
                        val created = pendingMeta["created"] ?: Instant.now().toString()
                        val updated = pendingMeta["updated"] ?: created
                        entries.add(
                            PinnedEntry(
                                id = metaId,
                                content = content,
                                createdAt = created,
                                updatedAt = updated,
                                assistantId = pendingMeta["assistant"]?.takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                    pendingContent = bullet.groupValues[1].trim()
                    pendingMeta = emptyMap()
                }
                meta != null -> {
                    val updatedValue = meta.groupValues[3].takeIf { it.isNotBlank() }?.trim() ?: meta.groupValues[2].trim()
                    pendingMeta = mapOf(
                        "id" to meta.groupValues[1].trim(),
                        "created" to meta.groupValues[2].trim(),
                        "updated" to updatedValue,
                        "assistant" to meta.groupValues.getOrNull(4).orEmpty().trim(),
                    )
                }
            }
        }
        pendingContent?.let { content ->
            val metaId = pendingMeta["id"] ?: UUID.randomUUID().toString()
            val created = pendingMeta["created"] ?: Instant.now().toString()
            val updated = pendingMeta["updated"] ?: created
            entries.add(
                PinnedEntry(
                    id = metaId,
                    content = content,
                    createdAt = created,
                    updatedAt = updated,
                    assistantId = pendingMeta["assistant"]?.takeIf { it.isNotBlank() },
                ),
            )
        }
        return entries
    }

    private fun writeBoth(entries: List<PinnedEntry>) {
        // C-04: Invalidar explícitamente el caché después de escribir(Escribir en el mismo milisegundo puede mantener el tiempo sin cambios,no puede confiar únicamente en mtime)
        cachedEntries = null
        storageDir.mkdirs()
        // Revisión de corrección (B-20): Archivo temporal + cambiar el nombre del reemplazo atómico,Evite que el proceso sea eliminado/Escribe la mitad y deja la mitad del archivo.
        // (JSON El análisis falló;Consistente con el modo de escritura de lápida de FactStore).
        writeAtomic(jsonFile, json.encodeToString(ListSerializer(PinnedEntry.serializer()), entries))
        // Markdown
        val md = buildString {
            appendLine("# Pinned Memories")
            appendLine()
            for (e in entries) {
                appendLine("- ${e.content}")
                appendLine(
                    "  <!-- id: ${e.id} | created: ${e.createdAt} | updated: ${e.updatedAt} " +
                        "| assistant: ${e.assistantId.orEmpty()} -->",
                )
                appendLine()
            }
        }
        writeAtomic(mdFile, md)
    }

    /** Escribir en el archivo de destino;Escriba primero el archivo temporal y luego cambie el nombre del reemplazo atómico,rename Recurrir a la escritura directa en caso de error(haz tu mejor esfuerzo). */
    private fun writeAtomic(file: File, content: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(file)) {
            file.writeText(content)
            tmp.delete()
        }
    }
}
