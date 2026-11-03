package io.zer0.muse.workspace

import android.content.Context
import android.net.Uri
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Responsable del espacio de trabajo.
 *
 * El espacio de trabajo se compone de varios "montajes". Cada montaje es un directorio autorizado por separado con sus propios bits de capacidad.
 * (¿Se puede enumerar, leer y escribir el directorio)? Montaje predeterminado [WorkspaceMount.MAIN_ID] Apunta a la
 * Directorio fijo. Si la ruta no incluye un prefijo, siempre caerá sobre él.——Los datos antiguos, las llamadas a herramientas antiguas y los hábitos de modelos antiguos siguen siendo los de siempre.
 *
 * Sintaxis de ruta:`ID de montaje:/ruta relativa`. El prefijo sólo reconoce identidades registradas.`a/b.jpg` Este camino relativo ordinario
 * No será mal juzgado como una montura.
 *
 * Límite de seguridad: cada uno montado. [WorkspaceFs] Usted es responsable de determinar la ruta relativa a su propio directorio raíz;
 * Esta categoría requiere tres controles adicionales.——posición de habilidad,`..` Segmento, rango visible de sesión.
 *
 * @param context Contexto de la aplicación (utilizado para localizar filesDir)
 */
class WorkspaceManager(private val context: Context) {

    /** El directorio raíz de montaje predeterminado sigue la ruta histórica. */
    val rootDir: File = File(context.filesDir, MAIN_DIR_NAME).apply {
        if (!exists()) mkdirs()
    }

    private val mountStore = WorkspaceMountStore(context)

    private val bindingStore = WorkspaceSessionBindingStore(context)

    private val fsLock = Any()

    private val fsCache = HashMap<String, WorkspaceFs>()

    /**
     * Escribir bloqueos asignados por ruta. Leer el mismo archivo—cambiar—Escribe en serie, diferentes archivos en paralelo.
     *
     * Acuerdo:`No llame al método público que se bloqueará dentro del método bloqueado.` —— El que se utiliza aquí es no reentrante.
     * [kotlinx.coroutines.sync.Mutex], el reingreso esperará hasta que se agote el tiempo de espera.
     */
    private val pathLocks = WorkspacePathLock()

    // ── Gestión de montaje ──────────────────────────────────────────────────────────────

    /** Todos están montados y el montaje predeterminado ocupa el primer lugar. */
    suspend fun mounts(): List<WorkspaceMount> = mountStore.mounts()

    /** Verifique una montura por identificación. */
    suspend fun findMount(id: String): WorkspaceMount? = mountStore.find(id)

    /**
     * Agregue o actualice una montura.
     *
     * Sólo se registra la autorización y no se tocan los archivos del disco.
     */
    suspend fun upsertMount(mount: WorkspaceMount): WorkspaceMountStore.MountStoreResult {
        val result = mountStore.upsert(mount)
        if (result is WorkspaceMountStore.MountStoreResult.Success) invalidateFs(mount.id)
        return result
    }

    /**
     * Retire una montura.
     *
     * Al mismo tiempo, borre las referencias al mismo en cada sesión para evitar dejar marcas colgantes.
     *
     * @return ¿Realmente eliminaste un elemento?
     */
    suspend fun removeMount(id: String): Boolean {
        val removed = mountStore.remove(id)
        if (removed) {
            invalidateFs(id)
            bindingStore.forgetMount(id)
        }
        return removed
    }

    /** Registre el tiempo de uso para la clasificación de interfaces. */
    suspend fun touchMount(id: String) {
        mountStore.touch(id)
    }

    /** El ID de montaje disponible para una sesión;null No indica restricciones. */
    suspend fun sessionMountIds(sessionId: String?): Set<String>? = bindingStore.allowedMountIds(sessionId)

    /** Establecer las monturas disponibles para una sesión; pasar un conjunto vacío significa cancelar la restricción. */
    suspend fun setSessionMounts(sessionId: String, ids: Set<String>) {
        bindingStore.setAllowed(sessionId, ids)
    }

    // ── Interno: objetivo de análisis ────────────────────────────────────────────────────────

    /** El resultado de un análisis: en qué montaje se encuentra, la ruta relativa y qué sistema de archivos se utiliza. */
    private class Target(
        val mount: WorkspaceMount,
        val relative: String,
        val fs: WorkspaceFs,
        val display: String,
    )

    /**
     * Resolver una ruta de usuario en un destino ejecutable.
     *
     * Verificación de tres etapas: si el montaje existe, si la sesión es visible y si se cumplen los bits de capacidad; luego rechazar `..` segmento.
     *
     * @param path Camino original, se puede tomar. `mount:/` prefijo
     * @param need Las capacidades requeridas para esta operación,null Indica que no hay verificación
     * @param scope El conjunto de monturas disponibles para esta sesión,null No indica ninguna restricción
     * @param allowRoot Si se debe permitir el apuntamiento directo al directorio raíz de montaje
     */
    private suspend fun target(path: String, need: MountCapability?, scope: Set<String>?, allowRoot: Boolean): Target {
        val ids = mountStore.mountIds()
        val parsed = WorkspacePathParser.parse(path, ids)
        val mountId = parsed.mountId ?: WorkspacePathParser.DEFAULT_MOUNT_ID
        val mount = mountStore.find(mountId) ?: throw WorkspaceIoException("el monte no existe: $mountId")
        checkVisible(mount, scope)
        checkCapability(mount, need)
        checkPath(parsed, allowRoot)
        return Target(mount, parsed.relative, fsFor(mount), displayOf(mount, parsed.relative))
    }

    /** Verificación del rango de visibilidad de la sesión. */
    private fun checkVisible(mount: WorkspaceMount, scope: Set<String>?) {
        if (scope != null && mount.id !in scope) {
            throw WorkspaceIoException("「${mount.label}” no está disponible en esta sesión")
        }
    }

    /** Verificación de bits de capacidad. */
    private fun checkCapability(mount: WorkspaceMount, need: MountCapability?) {
        if (need != null && need !in mount.capabilities) {
            throw WorkspaceIoException(deniedMessage(mount, need))
        }
    }

    /** Validación del segmento de ruta: Rechazar `..`;Se niega a montar el directorio raíz bajo demanda. */
    private fun checkPath(parsed: WorkspacePath, allowRoot: Boolean) {
        if (WorkspacePathParser.hasParentSegment(parsed.relative)) {
            throw WorkspaceIoException("La ruta no puede contener ..")
        }
        if (!allowRoot && parsed.isMountRoot) {
            throw WorkspaceIoException("Esta operación no puede afectar directamente al directorio raíz de montaje.")
        }
    }

    /** Indicar palabras cuando la habilidad es insuficiente. */
    private fun deniedMessage(mount: WorkspaceMount, need: MountCapability): String = when (need) {
        MountCapability.WRITE -> "「${mount.label}”es un montaje de solo lectura y no se puede escribir, eliminar ni mover."
        MountCapability.READ -> "「${mount.label}\"Sin permiso de lectura"
        MountCapability.LIST -> "「${mount.label}\"No se puede enumerar el directorio"
    }

    /** Recuperar (y almacenar en caché) un sistema de archivos montado. */
    private fun fsFor(mount: WorkspaceMount): WorkspaceFs {
        synchronized(fsLock) {
            fsCache[mount.id]?.let { return it }
            val fs = when (mount.provider) {
                MountProvider.LOCAL -> {
                    val root = mount.rootPath?.takeIf { it.isNotBlank() }
                        ?: throw WorkspaceIoException("Monte \"${mount.label}\"Falta la ruta del directorio")
                    LocalWorkspaceFs(File(root))
                }
                MountProvider.SAF -> {
                    val uri = mount.treeUri?.takeIf { it.isNotBlank() }
                        ?: throw WorkspaceIoException("Monte \"${mount.label}\"Falta autorización de directorio")
                    SafWorkspaceFs(context, Uri.parse(uri))
                }
            }
            fsCache[mount.id] = fs
            return fs
        }
    }

    private fun invalidateFs(mountId: String) {
        synchronized(fsLock) { fsCache.remove(mountId) }
    }

    /** El montaje predeterminado utiliza una ruta relativa y otros montajes vuelven a agregar el prefijo para facilitar que la persona que llama lo devuelva tal como está. */
    private fun displayOf(mount: WorkspaceMount, relative: String): String =
        if (mount.id == WorkspaceMount.MAIN_ID) relative else WorkspacePathParser.format(mount.id, relative)

    // ── Interno: bloqueo de ruta ──────────────────────────────────────────────────────────

    /** en [t] Ejecución dentro del bloqueo de este camino. [block]. */
    private suspend fun <T> lockedBy(t: Target, block: suspend () -> T): T =
        pathLocks.withLock(t.mount.id, t.relative, t.display, block)

    /**
     * Ejecutar dentro del bloqueo de ambas rutas simultáneamente [block](Operaciones como mover y copiar requieren cambiar ambos extremos).
     *
     * Ordenar por clave,`A→B` con `B→A` Durante la concurrencia, ambas partes parten de la misma clave y no serán iguales entre sí.
     */
    private suspend fun <T> lockedByBoth(first: Target, second: Target, block: suspend () -> T): T {
        val keyA = pathLocks.keyOf(first.mount.id, first.relative)
        val keyB = pathLocks.keyOf(second.mount.id, second.relative)
        if (keyA == keyB) return lockedBy(first, block)
        val (head, tail) = if (keyA < keyB) first to second else second to first
        return lockedBy(head) { lockedBy(tail, block) }
    }

    // ── Directorios y archivos ────────────────────────────────────────────────────────────

    /**
     * Lista de contenidos.
     *
     * El directorio raíz del montaje predeterminado trae montajes adicionales: el directorio raíz es el punto de partida de todas las entradas.
     * Enumerar solo el contenido de un disco hará que otros montajes sean invisibles en la vista predeterminada.
     *
     * @param relativePath Ruta relativa, que puede tener un prefijo de montaje. Una cadena vacía indica el directorio raíz de montaje predeterminado.
     * @param scope El conjunto de monturas disponibles para esta sesión,null No indica ninguna restricción
     */
    suspend fun listDir(relativePath: String, scope: Set<String>? = null): ListResult = withContext(Dispatchers.IO) {
        try {
            val t = target(relativePath, MountCapability.LIST, scope, allowRoot = true)
            val entries = t.fs.list(t.relative).map { entry ->
                WorkspaceEntry(
                    name = entry.name,
                    relativePath = displayOf(t.mount, entry.relativePath),
                    isDirectory = entry.isDirectory,
                    size = entry.size,
                    lastModified = entry.lastModified,
                )
            }
            val atDefaultRoot = t.mount.id == WorkspaceMount.MAIN_ID && t.relative.isEmpty()
            ListResult.Success(if (atDefaultRoot) otherMountEntries(scope) + entries else entries)
        } catch (e: WorkspaceIoException) {
            ListResult.Error(e.message ?: "No se pudo listar el directorio")
        }
    }

    /** Las entradas de montaje restantes en la vista raíz: un montaje por línea y la ruta de retorno se escribe como `ID de montaje:/` Eso cae sobre ese monte. */
    private suspend fun otherMountEntries(scope: Set<String>?): List<WorkspaceEntry> =
        mountStore.mounts()
            .filter { it.id != WorkspaceMount.MAIN_ID }
            .filter { scope == null || it.id in scope }
            .filter { it.canList }
            .map { mount ->
                WorkspaceEntry(
                    name = mount.id,
                    relativePath = WorkspacePathParser.format(mount.id, ""),
                    isDirectory = true,
                    size = 0L,
                    lastModified = mount.lastUsedAt,
                )
            }

    /**
     * Leer archivo de texto (límite superior [MAX_READ_BYTES]).
     *
     * Toda la lectura debe pasar el bloqueo de ruta: cuando se escribe el mismo archivo, lo que se lee debe ser el contenido después de escribirlo.
     * No puede ser un estado intermedio que se trunca a la mitad.`workspace_edit` de lectura—juez—escribir ver también [editFile].
     */
    suspend fun readFile(relativePath: String, scope: Set<String>? = null): ReadResult = withContext(Dispatchers.IO) {
        try {
            val t = target(relativePath, MountCapability.READ, scope, allowRoot = false)
            lockedBy(t) {
                val size = t.fs.size(t.relative)
                if (size > MAX_READ_BYTES) {
                    return@lockedBy ReadResult.Error("Archivo demasiado grande($size bytes),El límite superior de lectura es $MAX_READ_BYTES bytes")
                }
                if (!t.fs.exists(t.relative) || t.fs.isDirectory(t.relative)) {
                    return@lockedBy ReadResult.Error("El archivo no existe: ${t.display}")
                }
                ReadResult.Success(t.fs.readText(t.relative))
            }
        } catch (e: WorkspaceIoException) {
            ReadResult.Error(e.message ?: "Error de lectura")
        }
    }

    /** Leer archivos de texto; Los archivos grandes se paginan por intervalos de caracteres. */
    suspend fun readFilePage(
        relativePath: String,
        offsetChars: Int? = null,
        lengthChars: Int? = null,
        scope: Set<String>? = null,
    ): ReadResult = withContext(Dispatchers.IO) {
        try {
            val t = target(relativePath, MountCapability.READ, scope, allowRoot = false)
            lockedBy(t) {
                if (!t.fs.exists(t.relative) || t.fs.isDirectory(t.relative)) {
                    return@lockedBy ReadResult.Error("El archivo no existe: ${t.display}")
                }
                val paginated = offsetChars != null || lengthChars != null || t.fs.size(t.relative) > MAX_READ_BYTES
                val content = if (paginated) {
                    t.fs.readTextRange(
                        relative = t.relative,
                        offsetChars = offsetChars ?: 0,
                        lengthChars = lengthChars ?: io.zer0.muse.tools.TOOL_OUTPUT_READ_PAGE_CHARS,
                    )
                } else {
                    t.fs.readText(t.relative)
                }
                ReadResult.Success(content)
            }
        } catch (e: WorkspaceIoException) {
            ReadResult.Error(e.message ?: "Error de lectura")
        }
    }

    /** Leer bytes sin formato (escenarios binarios como vista previa de documentos). */
    suspend fun readBytes(relativePath: String, scope: Set<String>? = null): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val t = target(relativePath, MountCapability.READ, scope, allowRoot = false)
            t.fs.readBytes(t.relative)
        }.getOrNull()
    }

    /** Escribir archivos de texto (en serie para la misma ruta, en paralelo para archivos diferentes). */
    suspend fun writeFile(relativePath: String, content: String, scope: Set<String>? = null): OpResult = withContext(Dispatchers.IO) {
        if (content.toByteArray(Charsets.UTF_8).size.toLong() > MAX_WRITE_BYTES) {
            return@withContext OpResult.Error("El contenido es demasiado grande.,El límite de escritura es $MAX_WRITE_BYTES bytes")
        }
        try {
            val t = target(relativePath, MountCapability.WRITE, scope, allowRoot = false)
            lockedBy(t) { t.fs.writeText(t.relative, content, append = false) }
            OpResult.Success
        } catch (e: WorkspaceIoException) {
            OpResult.Error(e.message ?: "Error de escritura")
        }
    }

    /**
     * leer—cambiar—Escribir: lea el archivo completo en el mismo bloqueo de ruta y entréguelo [decide] Sentencia, escriba de acuerdo con el resultado del juicio.
     *
     * El bloqueo debe cubrir toda la sección desde la lectura hasta la escritura; de lo contrario, dos llamadas simultáneas leerán el contenido anterior y luego lo escribirán respectivamente.
     * El que se escriba más tarde descartará el que se escribió primero y ambas partes obtendrán el éxito.
     *
     * @param relativePath ruta del archivo
     * @param scope El conjunto de monturas disponibles para esta sesión,null No indica ninguna restricción
     * @param decide Emitir juicios sobre el texto original completo; Sólo haga cálculos puros y no inicie más llamadas de bloqueo.
     */
    suspend fun editFile(
        relativePath: String,
        scope: Set<String>? = null,
        decide: (original: String) -> EditDecision,
    ): EditResult = withContext(Dispatchers.IO) {
        try {
            val t = target(relativePath, MountCapability.READ, scope, allowRoot = false)
            lockedBy(t) {
                val size = t.fs.size(t.relative)
                if (size > MAX_READ_BYTES) {
                    return@lockedBy EditResult.Failed("Archivo demasiado grande($size bytes),El límite superior de lectura es $MAX_READ_BYTES bytes")
                }
                if (!t.fs.exists(t.relative) || t.fs.isDirectory(t.relative)) {
                    return@lockedBy EditResult.Failed("El archivo no existe: ${t.display}")
                }
                commitEdit(relativePath, scope, t.fs.readText(t.relative), decide)
            }
        } catch (e: WorkspaceIoException) {
            EditResult.Failed(e.message ?: "Editar falló")
        }
    }

    /** [editFile] La segunda mitad del segmento de retención de cerraduras: juicio + reescritura. La persona que llama ya debe tener un bloqueo en la ruta. */
    private suspend fun commitEdit(
        relativePath: String,
        scope: Set<String>?,
        original: String,
        decide: (original: String) -> EditDecision,
    ): EditResult {
        val decision = decide(original)
        if (decision is EditDecision.Abort) return EditResult.Rejected(decision.message)
        val content = (decision as EditDecision.Commit).content
        if (content.toByteArray(Charsets.UTF_8).size.toLong() > MAX_WRITE_BYTES) {
            return EditResult.Failed("El contenido es demasiado grande.,El límite de escritura es $MAX_WRITE_BYTES bytes")
        }
        // El permiso de escritura se determina antes de volver a escribir: leer en un soporte de solo lectura es legal, pero lo que se debe informar es "no se puede escribir".
        val writable = target(relativePath, MountCapability.WRITE, scope, allowRoot = false)
        writable.fs.writeText(writable.relative, content, append = false)
        return EditResult.Applied(original, content)
    }

    /** Anexar escrito. */
    suspend fun appendFile(relativePath: String, content: String, scope: Set<String>? = null): OpResult = withContext(Dispatchers.IO) {
        if (content.toByteArray(Charsets.UTF_8).size.toLong() > MAX_WRITE_BYTES) {
            return@withContext OpResult.Error("El contenido es demasiado grande.,El límite superior para una sola adición es $MAX_WRITE_BYTES bytes")
        }
        try {
            val t = target(relativePath, MountCapability.WRITE, scope, allowRoot = false)
            lockedBy(t) { t.fs.writeText(t.relative, content, append = true) }
            OpResult.Success
        } catch (e: WorkspaceIoException) {
            OpResult.Error(e.message ?: "Error al agregar")
        }
    }

    /** Eliminar archivos o directorios (recursividad de directorios). */
    suspend fun delete(relativePath: String, scope: Set<String>? = null): OpResult = withContext(Dispatchers.IO) {
        try {
            val t = target(relativePath, MountCapability.WRITE, scope, allowRoot = false)
            lockedBy(t) { t.fs.delete(t.relative) }
            OpResult.Success
        } catch (e: WorkspaceIoException) {
            OpResult.Error(e.message ?: "Error al eliminar")
        }
    }

    /** Cree un directorio (con múltiples niveles). */
    suspend fun mkdir(relativePath: String, scope: Set<String>? = null): OpResult = withContext(Dispatchers.IO) {
        try {
            val t = target(relativePath, MountCapability.WRITE, scope, allowRoot = false)
            lockedBy(t) { t.fs.mkdirs(t.relative) }
            OpResult.Success
        } catch (e: WorkspaceIoException) {
            OpResult.Error(e.message ?: "Error al crear el directorio")
        }
    }

    /** Mover o cambiar el nombre (limitado al mismo montaje). Tanto la ruta de origen como la de destino están bloqueadas. */
    suspend fun move(from: String, to: String, scope: Set<String>? = null): OpResult = withContext(Dispatchers.IO) {
        try {
            val src = target(from, MountCapability.WRITE, scope, allowRoot = false)
            val dst = target(to, MountCapability.WRITE, scope, allowRoot = false)
            if (src.mount.id != dst.mount.id) {
                return@withContext OpResult.Error("No se puede mover entre monturas,Por favor use copia en su lugar")
            }
            lockedByBoth(src, dst) { src.fs.move(src.relative, dst.relative) }
            OpResult.Success
        } catch (e: WorkspaceIoException) {
            OpResult.Error(e.message ?: "Movimiento fallido")
        }
    }

    /** Copiar (copiar directamente dentro del mismo soporte, mover bytes entre soportes). Tanto la ruta de origen como la de destino están bloqueadas. */
    suspend fun copy(from: String, to: String, scope: Set<String>? = null): OpResult = withContext(Dispatchers.IO) {
        try {
            val src = target(from, MountCapability.READ, scope, allowRoot = false)
            val dst = target(to, MountCapability.WRITE, scope, allowRoot = false)
            lockedByBoth(src, dst) { copyLocked(src, dst) }
        } catch (e: WorkspaceIoException) {
            OpResult.Error(e.message ?: "Copia fallida")
        }
    }

    /** Copie el cuerpo dentro del segmento de retención de la cerradura. */
    private suspend fun copyLocked(src: Target, dst: Target): OpResult {
        if (src.mount.id == dst.mount.id) {
            src.fs.copy(src.relative, dst.relative)
            return OpResult.Success
        }
        if (src.fs.isDirectory(src.relative)) return OpResult.Error("Solo se puede copiar un único archivo entre montajes")
        val bytes = src.fs.readBytes(src.relative)
            ?: return OpResult.Error("No se pudo leer el archivo fuente: ${src.display}")
        bytes.inputStream().use { dst.fs.writeStream(dst.relative, it, MAX_WRITE_BYTES) }
        return OpResult.Success
    }

    // ── Buscar ──────────────────────────────────────────────────────────────────

    /**
     * Busque contenido dentro del espacio de trabajo.
     *
     * Recorra los montajes visibles en esta sesión, escaneando archivo por archivo. Omita archivos de gran tamaño, archivos binarios y archivos no decodificables.
     *
     * @param pattern El texto que se va a encontrar, o regular ([isRegex] es verdad)
     * @param relativePath Punto de inicio de búsqueda, todos montan el directorio raíz de forma predeterminada
     * @param scope El conjunto de monturas disponibles para esta sesión,null No indica ninguna restricción
     */
    suspend fun grep(
        pattern: String,
        relativePath: String = "",
        isRegex: Boolean = false,
        maxResults: Int = DEFAULT_GREP_MAX_RESULTS,
        scope: Set<String>? = null,
    ): GrepResult = withContext(Dispatchers.IO) {
        if (pattern.isEmpty()) return@withContext GrepResult.Error("pattern no puede estar vacío")
        val regex = if (isRegex) {
            matchOrNull(pattern) ?: return@withContext GrepResult.Error("Error de sintaxis habitual: $pattern")
        } else {
            null
        }
        try {
            val starts = resolveGrepStarts(relativePath, scope)
            val matches = mutableListOf<GrepMatch>()
            var scanned = 0
            var truncated = false
            for (start in starts) {
                if (matches.size >= maxResults) {
                    truncated = true
                    break
                }
                val outcome = scanMount(start, regex, pattern, maxResults, matches)
                scanned += outcome.scanned
                if (outcome.reachedCap) truncated = true
            }
            GrepResult.Success(matches, scanned, truncated)
        } catch (e: WorkspaceIoException) {
            GrepResult.Error(e.message ?: "La búsqueda falló")
        }
    }

    /** Los resultados de un análisis: cuántos archivos se analizaron y si se alcanzó el límite de aciertos. */
    private data class ScanOutcome(val scanned: Int, val reachedCap: Boolean)

    /** Punto de partida de la búsqueda: un camino corresponde a un monte; cuando el camino está vacío, se expande a todos los soportes visibles. */
    private suspend fun resolveGrepStarts(relativePath: String, scope: Set<String>?): List<Target> {
        val ids = mountStore.mountIds()
        val parsed = WorkspacePathParser.parse(relativePath, ids)
        if (parsed.mountId != null || parsed.relative.isNotEmpty()) {
            return listOf(target(relativePath, MountCapability.READ, scope, allowRoot = true))
        }
        return mountStore.mounts()
            .filter { scope == null || it.id in scope }
            .filter { MountCapability.READ in it.capabilities }
            .map { Target(it, "", fsFor(it), it.id) }
    }

    /** Escanee una montura y agregue visitas a [out]. */
    private suspend fun scanMount(
        start: Target,
        regex: Regex?,
        pattern: String,
        maxResults: Int,
        out: MutableList<GrepMatch>,
    ): ScanOutcome {
        val files = try {
            start.fs.walkFiles(start.relative, MAX_GREP_FILES)
        } catch (e: WorkspaceIoException) {
            Logger.w(TAG, "grep El recorrido falló: ${e.message}")
            return ScanOutcome(0, false)
        }
        var scanned = 0
        for (rel in files) {
            if (out.size >= maxResults) return ScanOutcome(scanned, true)
            scanned++
            if (collectHits(start, rel, regex, pattern, maxResults, out)) return ScanOutcome(scanned, true)
        }
        return ScanOutcome(scanned, false)
    }

    /** Escanear un archivo y escribir resultados en [out];Devuelve si se debe detener porque se alcanza el límite superior. */
    private suspend fun collectHits(
        start: Target,
        rel: String,
        regex: Regex?,
        pattern: String,
        maxResults: Int,
        out: MutableList<GrepMatch>,
    ): Boolean {
        val size = runCatching { start.fs.size(rel) }.getOrDefault(0L)
        if (size > MAX_GREP_FILE_BYTES) return false
        val text = runCatching { start.fs.readText(rel) }.getOrNull() ?: return false
        if (text.indexOf('\u0000') >= 0) return false
        val display = displayOf(start.mount, rel)
        text.lineSequence().forEachIndexed { idx, line ->
            if (out.size >= maxResults) return@forEachIndexed
            val hit = if (regex != null) regex.containsMatchIn(line) else line.contains(pattern)
            if (hit) out.add(GrepMatch(display, idx + 1, line.take(MAX_GREP_LINE_CHARS)))
        }
        return out.size >= maxResults
    }

    /** Compilado regular, devuelve nulo si la sintaxis es ilegal. */
    private fun matchOrNull(pattern: String): Regex? = try {
        Regex(pattern)
    } catch (e: Exception) {
        Logger.w(TAG, "grep La compilación regular falló: ${e.message}")
        null
    }

    // ── importar ──────────────────────────────────────────────────────────────────

    /**
     * Importa un archivo desde el URI seleccionado por el selector de archivos del sistema.
     *
     * La copia en streaming no lee el archivo completo en la memoria; el número de serie se agrega automáticamente cuando el objetivo tiene el mismo nombre.
     * La determinación del número de serie y la realización del pedido se completan dentro de la misma retención de bloqueo (consulte [importInto]):
     * Si solo se bloquea la escritura, dos importaciones simultáneas calcularán el mismo número de secuencia y la que se escriba más tarde sobrescribirá la que se escribió primero.
     * URI Solo lectura durante esta llamada, no se retiene ninguna autorización a largo plazo.
     *
     * @param uri Archivos devueltos por el selector del sistema. URI
     * @param targetDirectory Directorio de destino. Una cadena vacía indica el directorio raíz de montaje predeterminado.
     * @param displayName Nombre de archivo de destino opcional, consulte al sistema si está vacío
     */
    suspend fun importFile(uri: Uri, targetDirectory: String, displayName: String? = null, scope: Set<String>? = null): FileImportResult =
        withContext(Dispatchers.IO) {
            try {
                val dir = target(targetDirectory, MountCapability.WRITE, scope, allowRoot = true)
                val rawName = displayName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: queryDisplayName(uri)
                    ?: "imported-file"
                importInto(dir, sanitizeImportedName(rawName), uri, scope)
            } catch (e: WorkspaceIoException) {
                FileImportResult.Error(e.message ?: "Error al importar el archivo")
            } catch (e: Exception) {
                Logger.w(TAG, "importFile falló: ${e.message}", e)
                FileImportResult.Error("Error al importar el archivo: ${e.message}")
            }
        }

    /**
     * Ontología de importación con bloqueo: primero calcule el número de serie bajo el bloqueo "directorio de destino + nombre de archivo básico" y luego colóquelo en el disco.
     *
     * El bloqueo de nombre básico en la capa exterior consiste en poner en cola importaciones simultáneas con el mismo nombre en lugar de calcular exactamente el mismo número de secuencia;
     * Se toma otro bloqueo en el nombre del archivo real para serializarlo con la llamada para escribir explícitamente el archivo.
     * Los dos bloqueos solo se mantendrán al mismo tiempo si se importan a esta ruta y el orden es fijo (primero el nombre base, luego el nombre real);
     * Si una operación de ruta dual, como mover/copiar, encuentra el mismo par de rutas, el peor de los casos es que el bloqueo se agote y no se bloquee permanentemente.
     */
    private suspend fun importInto(dir: Target, safeName: String, uri: Uri, scope: Set<String>?): FileImportResult {
        val base = target(displayOf(dir.mount, joinRelative(dir.relative, safeName)), MountCapability.WRITE, scope, allowRoot = false)
        return lockedBy(base) {
            val candidateRel = uniqueName(dir, safeName)
            val dest = target(displayOf(dir.mount, candidateRel), MountCapability.WRITE, scope, allowRoot = false)
            if (pathLocks.keyOf(dest.mount.id, dest.relative) == pathLocks.keyOf(base.mount.id, base.relative)) {
                streamInto(dest, uri)
            } else {
                lockedBy(dest) { streamInto(dest, uri) }
            }
        }
    }

    /** Copie la secuencia URI en [dest](La persona que llama es responsable de mantener el candado). */
    private suspend fun streamInto(dest: Target, uri: Uri): FileImportResult {
        val input = context.contentResolver.openInputStream(uri)
            ?: return FileImportResult.Error("No se puede leer el archivo seleccionado")
        var bytes = 0L
        input.use { stream -> bytes = dest.fs.writeStream(dest.relative, stream, MAX_IMPORT_BYTES) }
        return FileImportResult.Success(dest.display, bytes)
    }

    /** Busque un nombre de archivo que no entre en conflicto en el directorio de destino. */
    private suspend fun uniqueName(dir: Target, safeName: String): String {
        var candidate = joinRelative(dir.relative, safeName)
        if (!dir.fs.exists(candidate)) return candidate
        val dot = safeName.lastIndexOf('.')
        val stem = if (dot > 0) safeName.substring(0, dot) else safeName
        val extension = if (dot > 0) safeName.substring(dot) else ""
        var suffix = 1
        while (true) {
            candidate = joinRelative(dir.relative, "$stem ($suffix)$extension")
            if (!dir.fs.exists(candidate)) return candidate
            suffix++
        }
    }

    /** Concatene la ruta relativa del directorio con el nombre del archivo. */
    private fun joinRelative(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent/$name"

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }
    }.onFailure { Logger.w(TAG, "No se pudo leer el nombre del archivo de importación: ${it.message}") }.getOrNull()

    private fun sanitizeImportedName(raw: String): String {
        val leaf = raw.trim().substringAfterLast('/').substringAfterLast('\\')
        val cleaned = leaf
            .replace(Regex("[\\u0000-\\u001F]"), "_")
            .replace("..", "_")
            .trim()
        return cleaned.take(MAX_FILE_NAME_LENGTH).ifBlank { "imported-file-${UUID.randomUUID().toString().take(8)}" }
    }

    // ── Tipo de resultado (facilita el procesamiento unificado por UI/herramientas)──────────────────────────────────────

    /**
     * Un directorio en el espacio de trabajo./Entrada de archivo.
     *
     * @param name Nombre para mostrar (sin ruta)
     * @param relativePath Ruta que se puede devolver: el montaje predeterminado es una ruta relativa, otros montajes usan `mount:/` prefijo
     * @param isDirectory ¿Es un directorio?
     * @param size Número de bytes (el directorio siempre es 0)
     * @param lastModified Marca de tiempo de la última modificación (milisegundos)
     */
    data class WorkspaceEntry(
        val name: String,
        val relativePath: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
    )

    /**
     * Un éxito de contenidos.
     *
     * @param path ruta del archivo
     * @param lineNumber número de línea (1 (de)
     * @param line contenido de la línea de éxito
     */
    data class GrepMatch(
        val path: String,
        val lineNumber: Int,
        val line: String,
    )

    /** [grep] resultado. */
    sealed class GrepResult {
        /**
         * @param matches Lista de resultados (hasta elementos maxResults)
         * @param scannedFiles Número real de archivos escaneados
         * @param truncated Si se debe detener antes de tiempo porque el número de visitas alcanza el límite superior
         */
        data class Success(
            val matches: List<GrepMatch>,
            val scannedFiles: Int,
            val truncated: Boolean,
        ) : GrepResult()

        data class Error(val message: String) : GrepResult()
    }

    /** listDir resultados. */
    sealed class ListResult {
        data class Success(val entries: List<WorkspaceEntry>) : ListResult()

        data class Error(val message: String) : ListResult()
    }

    /** readFile resultados. */
    sealed class ReadResult {
        data class Success(val content: String) : ReadResult()

        data class Error(val message: String) : ReadResult()
    }

    /** Operaciones comunes (write/delete/mkdir/move/copy) resultados. */
    sealed class OpResult {
        object Success : OpResult()

        data class Error(val message: String) : OpResult()
    }

    /**
     * leer—cambiar—Escriba la conclusión del paso "cambio", por [editFile] La persona que llama se proporciona en el texto original completo.
     */
    sealed class EditDecision {
        /** Deja de escribir,[message] Regresa a la persona que llama sin cambios; el archivo permanece intacto. */
        data class Abort(val message: String) : EditDecision()

        /** escribir de nuevo [content]. */
        data class Commit(val content: String) : EditDecision()
    }

    /** [editFile] resultado. */
    sealed class EditResult {
        /** Escrito de nuevo;[original] con [updated] Para que la persona que llama muestre la diferencia. */
        data class Applied(val original: String, val updated: String) : EditResult()

        /** Desestimado en fase de sentencia, el expediente permanece inalterado. */
        data class Rejected(val message: String) : EditResult()

        /** Error de lectura o escritura (incluida la espera del tiempo de espera de bloqueo). */
        data class Failed(val message: String) : EditResult()
    }

    /** Resultados de la importación de archivos. */
    sealed class FileImportResult {
        data class Success(val relativePath: String, val bytes: Long) : FileImportResult()

        data class Error(val message: String) : FileImportResult()
    }

    companion object {
        private const val TAG = "WorkspaceManager"

        /** El nombre del directorio montado predeterminado. */
        private const val MAIN_DIR_NAME = "workspace"

        /** Tamaño máximo de archivo leído:1MB. */
        const val MAX_READ_BYTES: Long = 1L * 1024 * 1024

        /** Tamaño máximo de archivo de escritura:10MB. */
        const val MAX_WRITE_BYTES: Long = 10L * 1024 * 1024

        /** Límite de importación de archivos:256MB, la copia en streaming no produce picos de memoria de archivos completos. */
        const val MAX_IMPORT_BYTES: Long = 256L * 1024 * 1024

        private const val MAX_FILE_NAME_LENGTH = 180
    }
}

/** grep El límite superior del tamaño de un solo archivo: si excede el tamaño, no se leerá. */
private const val MAX_GREP_FILE_BYTES: Long = 2L * 1024 * 1024

/** grep Número máximo de archivos para comprobar. */
private const val MAX_GREP_FILES: Int = 2000

/** grep Límite de visitas predeterminado. */
private const val DEFAULT_GREP_MAX_RESULTS: Int = 100

/** grep Longitud de truncamiento de la línea de acierto. */
private const val MAX_GREP_LINE_CHARS: Int = 400
