package io.zer0.muse.tools

import io.zer0.muse.workspace.WorkspaceManager
import io.zer0.muse.workspace.WorkspaceMount
import kotlinx.coroutines.runBlocking

/**
 * Conjunto de herramientas de administración de archivos del espacio de trabajo (AI invocable).
 *
 * Registra 10 herramientas para [ToolRegistry]:
 *  1. workspace_list   — Listar subclaves en un directorio
 *  2. workspace_read   — Leer archivos de texto, los archivos grandes se paginan mediante el cursor de carácter
 *  3. workspace_write  — Escribir en un archivo de texto (sobrescribir o agregar)
 *  4. workspace_edit   — Reemplace un determinado párrafo del texto original en el archivo con otro párrafo y devuelva el nivel de línea diff
 *  5. workspace_delete — Eliminar un archivo o directorio
 *  6. workspace_mkdir  — Crear directorio
 *  7. workspace_move   — Mover o cambiar nombre
 *  8. workspace_copy   — Copiar
 *  9. workspace_grep   — Buscar contenido por texto o expresiones regulares
 * 10. workspace_mounts — Listar los soportes disponibles y sus capacidades
 *
 * Escritura de ruta:`ID de montaje:/ruta relativa`. Cuando no se escribe ningún prefijo, cae al montaje predeterminado, por lo que las llamadas antiguas todavía están disponibles.
 * El prefijo solo reconoce identificadores de montaje registrados y los dos puntos que aparecen en nombres de archivos normales no se juzgarán erróneamente.
 *
 * Seguridad: La ruta consta de [WorkspaceManager] Análisis, bit de habilidad (puede leer/Write) y los límites de montaje se verifican allí;
 * La capa de herramientas no indica la ruta por sí sola ni proporciona una entrada para evitar la verificación.
 */
@Suppress("TooManyFunctions")
object WorkspaceTool {

    /** workspace_list Nombre de la herramienta. */
    const val NAME_LIST = "workspace_list"

    /** workspace_read Nombre de la herramienta. */
    const val NAME_READ = "workspace_read"

    /** workspace_write Nombre de la herramienta. */
    const val NAME_WRITE = "workspace_write"

    /** workspace_edit Nombre de la herramienta. */
    const val NAME_EDIT = "workspace_edit"

    /** workspace_delete Nombre de la herramienta. */
    const val NAME_DELETE = "workspace_delete"

    /** workspace_mkdir Nombre de la herramienta. */
    const val NAME_MKDIR = "workspace_mkdir"

    /** workspace_move Nombre de la herramienta. */
    const val NAME_MOVE = "workspace_move"

    /** workspace_copy Nombre de la herramienta. */
    const val NAME_COPY = "workspace_copy"

    /** workspace_grep Nombre de la herramienta. */
    const val NAME_GREP = "workspace_grep"

    /** workspace_mounts Nombre de la herramienta. */
    const val NAME_MOUNTS = "workspace_mounts"

    /** Todos los nombres de herramientas (para una fácil sincronización con [ToolRegistry.BUILT_IN_TOOL_IDS]). */
    val ALL_TOOL_NAMES: List<String> = listOf(
        NAME_LIST,
        NAME_READ,
        NAME_WRITE,
        NAME_EDIT,
        NAME_DELETE,
        NAME_MKDIR,
        NAME_MOVE,
        NAME_COPY,
        NAME_GREP,
        NAME_MOUNTS,
    )

    /** Todas las definiciones de herramientas (registradas en ToolRegistry). */
    fun toolDefs(): List<ToolRegistry.ToolDef> = listOf(
        listToolDef(),
        readToolDef(),
        writeToolDef(),
        editToolDef(),
        deleteToolDef(),
        mkdirToolDef(),
        moveToolDef(),
        copyToolDef(),
        grepToolDef(),
        mountsToolDef(),
    )

    /** Descripción del parámetro de ruta, varias herramientas comparten la misma oración. */
    private const val PATH_HINT = "La ruta se puede escribir y el ID de montaje es:/Camino relativo; si no se escribe ningún prefijo, caerá en el espacio de trabajo predeterminado. Primero use workspace_mounts para verificar los identificadores de montaje disponibles."

    /** diff Incluya algunas líneas de contexto antes y después del bloque de cambio en el recibo. */
    private const val DIFF_CONTEXT_LINES = 3

    private fun listToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_LIST,
        description = "Enumere los archivos y subdirectorios en un directorio en el espacio de trabajo (los directorios primero, los archivos al final)." +
            "Devuelve una entrada por línea:name | type(dir/file) | size | lastModified." + PATH_HINT,
        parameters = mapOf(
            "path" to "Opcional,ruta del directorio,cadena vacía o \"/\" Representa el directorio raíz del espacio de trabajo predeterminado.",
        ),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    private fun readToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_READ,
        description = "Leer el contenido del archivo de texto especificado en el espacio de trabajo(UTF-8). Segmentación automática de archivos grandes.,Utilice offset_chars/length_chars para continuar leyendo." +
            PATH_HINT,
        parameters = mapOf(
            "path" to "Requerido,ruta del archivo",
            "offset_chars" to "Opcional,cursor de inicio de carácter;Utilice el último offset_chars devuelto cuando continúe la lectura.",
            "length_chars" to "Opcional,Número de caracteres en una sola página.,máximo ${io.zer0.muse.tools.TOOL_OUTPUT_READ_PAGE_CHARS}.",
        ),
        required = setOf("path"),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    private fun writeToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_WRITE,
        description = "Escribir archivo de texto en el espacio de trabajo(UTF-8). Anulación predeterminada;append=true Cuando se adjunta. El límite de contenido único es de 10 MB." +
            "Si el directorio principal no existe, se creará automáticamente. Por favor escriba documentos largos en lotes.:Escribe primero el primer párrafo.,Para uso posterior append=true Añade sección por sección." +
            "Se rechazarán los montajes de solo lectura." + PATH_HINT,
        parameters = mapOf(
            "path" to "Requerido,ruta del archivo",
            "content" to "Requerido,El contenido del texto a escribir.",
            "append" to "Opcional,Si escribir adicionalmente(Anulación falsa predeterminada). Al escribir archivos largos, se recomienda adjuntarlos en lotes.",
        ),
        required = setOf("path", "content"),
        category = "built-in",
        // El tipo debe declararse; de ​​lo contrario, el esquema enviado al modelo dirá agregar como cadena.
        // Y el lado de ejecución usa String.toBoolean() explicar,"1"/"yes"/"si" Volverá silenciosamente a sobrescribir.
        parameterTypes = mapOf("append" to "boolean"),
        riskLevel = ToolRiskLevel.HIGH,
    )

    private fun editToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_EDIT,
        description =
        "Editar archivos parcialmente(Modificar texto en el espacio de trabajo): Reemplazar un determinado párrafo del texto original con otro párrafo(UTF-8)." +
            "old_string Debe coincidir textualmente con el contenido del documento.,y aparece solo una vez en todo el documento —— 0 o más ocurrencias resultarán en rechazo," +
            "Esta herramienta no adivinará qué cambios realizar. Si desea cambiar varios lugares, llámelo varias veces con suficiente contexto.new_string Pasar una cadena vacía significa eliminar el segmento." +
            "Devolver el nivel de fila con éxito diff(Primera línea cambiada + bloque @@),Puede comprobar directamente qué líneas se han modificado." +
            "Para modificar el archivo, primero use workspace_read o workspace_grep para ubicar el texto original.,No reescribas todo. Se rechazarán los montajes de solo lectura." + PATH_HINT,
        parameters =
        mapOf(
            "path" to "Requerido,ruta del archivo",
            "old_string" to "Requerido,El fragmento de texto original que se va a reemplazar.;Debe coincidir textualmente y ser único en todo el archivo.",
            "new_string" to "Requerido,Nuevo fragmento después del reemplazo.;Una cadena vacía significa eliminar el fragmento.",
        ),
        required = setOf("path", "old_string", "new_string"),
        category = "built-in",
        riskLevel = ToolRiskLevel.HIGH,
    )

    private fun deleteToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_DELETE,
        description = "Eliminar archivos o directorios en el espacio de trabajo. Los directorios se eliminarán de forma recursiva.(Contiene todos los niños),No recuperable. Se rechazarán los montajes de solo lectura." + PATH_HINT,
        parameters = mapOf(
            "path" to "Requerido,ruta para eliminar",
        ),
        required = setOf("path"),
        category = "built-in",
        riskLevel = ToolRiskLevel.HIGH,
    )

    private fun mkdirToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_MKDIR,
        description = "Crear un directorio dentro del espacio de trabajo.(Soporte multinivel). Ya se considera exitoso. Se rechazarán los montajes de solo lectura." + PATH_HINT,
        parameters = mapOf(
            "path" to "Requerido,ruta del directorio",
        ),
        required = setOf("path"),
        category = "built-in",
        riskLevel = ToolRiskLevel.HIGH,
    )

    private fun moveToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_MOVE,
        description = "Mover o cambiar el nombre de archivos dentro del espacio de trabajo/Directorio. Si el objetivo ya existe, fallará.;No se puede mover entre monturas,Utilice workspace_copy para montaje cruzado." +
            PATH_HINT,
        parameters = mapOf(
            "from" to "Requerido,ruta de origen",
            "to" to "Requerido,ruta de destino",
        ),
        required = setOf("from", "to"),
        category = "built-in",
        riskLevel = ToolRiskLevel.HIGH,
    )

    private fun copyToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_COPY,
        description = "Copie archivos o directorios dentro del espacio de trabajo. Falla si el objetivo ya existe." +
            "Los directorios se pueden copiar directamente dentro del mismo montaje.;Solo se puede copiar un único archivo entre montajes. El montaje de destino requiere permiso de escritura." + PATH_HINT,
        parameters = mapOf(
            "from" to "Requerido,ruta de origen(Se requiere permiso de lectura)",
            "to" to "Requerido,ruta de destino(Necesita permiso de escritura)",
        ),
        required = setOf("from", "to"),
        category = "built-in",
        riskLevel = ToolRiskLevel.HIGH,
    )

    private fun grepToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_GREP,
        description =
        "Buscar contenido en archivos del espacio de trabajo,Camino de regreso:Número de línea:contenido. Úselo para localizar el archivo antes de cambiarlo.,No lea todo el documento en contexto." +
            "búsqueda recursiva,Omita automáticamente archivos binarios y de más de 2 MB.is_regex=true Cuando el patrón se interpreta según reglas regulares." +
            "Deténgase en 100 visitas y marque truncado.path Cuando está vacío, se buscan todas las monturas disponibles.",
        parameters =
        mapOf(
            "pattern" to "Requerido,texto para encontrar;is_regex=true es una expresión regular",
            "path" to "Opcional,Buscar ruta para el punto de partida,Todos los soportes disponibles por defecto",
            "is_regex" to "Opcional,Si interpretar de acuerdo con reglas regulares pattern(Predeterminado false)",
            "max_results" to "Opcional,Número máximo de visitas,Predeterminado 100",
        ),
        required = setOf("pattern"),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    private fun mountsToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_MOUNTS,
        description =
        "Listar los montajes del espacio de trabajo disponibles actualmente:logotipo | Nombre | Habilidad | Fuente." +
            "El prefijo de la ruta debe escribirse aquí.,por ejemplo `album:/DCIM/a.jpg`;" +
            "El que está marcado como principal es el montaje predeterminado.,No es necesario escribir un prefijo. Se marcarán los montajes de solo lectura.",
        parameters = emptyMap(),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    /**
     * Herramientas de ejecución.
     *
     * @param name Nombre de la herramienta (debe ser [ALL_TOOL_NAMES] uno)
     * @param args parámetros map
     * @param manager inyectado [WorkspaceManager] Ejemplo
     * @param sessionId ID de sesión actual, utilizado para que el rango de montaje sea visible para la sesión; vacío significa sin límite
     * @return Cadena de resultado de la ejecución (retorno en caso de error) "Error: ..." mensaje de prefijo)
     */
    fun execute(name: String, args: Map<String, String>, manager: WorkspaceManager, sessionId: String? = null): String = runBlocking {
        val scope = manager.sessionMountIds(sessionId)
        when (name) {
            NAME_MOUNTS -> renderMounts(manager.mounts())
            NAME_LIST -> executeList(args, manager, scope)
            NAME_READ -> executeRead(args, manager, scope)
            NAME_WRITE -> executeWrite(args, manager, scope)
            NAME_EDIT -> executeEdit(args, manager, scope)
            NAME_DELETE -> executeDelete(args, manager, scope)
            NAME_MKDIR -> executeMkdir(args, manager, scope)
            NAME_MOVE -> executeMove(args, manager, scope)
            NAME_COPY -> executeCopy(args, manager, scope)
            NAME_GREP -> executeGrep(args, manager, scope)
            else -> "Error: Nombre de herramienta desconocido $name"
        }
    }

    /** Represente la tabla de montaje en filas para que la vea el modelo. */
    private fun renderMounts(mounts: List<WorkspaceMount>): String {
        if (mounts.isEmpty()) return "(nada montado)"
        return mounts.joinToString("\n") { mount ->
            val caps = buildList {
                if (mount.canList) add("list")
                if (mount.canRead) add("read")
                if (mount.canWrite) add("write")
            }.joinToString("+").ifEmpty { "none" }
            val source = when (mount.provider) {
                io.zer0.muse.workspace.MountProvider.LOCAL -> "directorio local"
                io.zer0.muse.workspace.MountProvider.SAF -> "Directorio de autorización del sistema"
            }
            "${mount.id} | ${mount.label} | $caps | $source"
        }
    }

    /** workspace_list ejecutor. */
    private suspend fun executeList(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val path = args["path"]?.trim() ?: ""
        return when (val r = manager.listDir(path, scope)) {
            is WorkspaceManager.ListResult.Success -> {
                if (r.entries.isEmpty()) {
                    "(empty)"
                } else {
                    r.entries.joinToString("\n") { e ->
                        val type = if (e.isDirectory) "dir" else "file"
                        "${e.relativePath} | $type | ${e.size} | ${e.lastModified}"
                    }
                }
            }
            is WorkspaceManager.ListResult.Error -> "Error: ${r.message}"
        }
    }

    /** workspace_read ejecutor. */
    private suspend fun executeRead(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val path = args["path"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: Se requiere la ruta del parámetro"
        val offsetChars = args["offset_chars"]?.toIntOrNull()
        val lengthChars = args["length_chars"]?.toIntOrNull()
        return when (val r = manager.readFilePage(path, offsetChars, lengthChars, scope)) {
            is WorkspaceManager.ReadResult.Success -> r.content
            is WorkspaceManager.ReadResult.Error -> "Error: ${r.message}"
        }
    }

    /** workspace_write ejecutor. */
    private suspend fun executeWrite(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val path = args["path"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: Se requiere la ruta del parámetro"
        val content = args["content"] ?: return "Error: Contenido del parámetro requerido"
        val append = args["append"]?.toBoolean() ?: false
        val r = if (append) {
            manager.appendFile(path, content, scope)
        } else {
            manager.writeFile(path, content, scope)
        }
        return when (r) {
            is WorkspaceManager.OpResult.Success ->
                "OK: Ya${if (append) "anexar" else "escribir"} $path(${content.length} personaje)"
            is WorkspaceManager.OpResult.Error -> "Error: ${r.message}"
        }
    }

    /** workspace_edit Cuerpo de ejecución: determine el fragmento único en la instantánea de texto completo → reemplazar → Realizar el pedido → Diferencia de nivel de línea. */
    private suspend fun executeEdit(
        args: Map<String, String>,
        manager: WorkspaceManager,
        scope: Set<String>?,
    ): String {
        val path = args["path"]?.trim()?.takeIf { it.isNotEmpty() }
        val oldString = args["old_string"]
        val newString = args["new_string"]
        if (path == null || oldString == null || newString == null) {
            val missing = listOfNotNull(
                "path".takeIf { path == null },
                "old_string".takeIf { oldString == null },
                "new_string".takeIf { newString == null },
            )
            return "Error: parámetros ${missing.joinToString("/")} Requerido"
        }
        // Se debe leer el texto completo: la comparación de fragmentos se debe ejecutar en una instantánea del texto completo y la mitad del contenido leído en las páginas no se puede comparar.
        // La lectura y la escritura están envueltas en el mismo bloqueo de ruta ([WorkspaceManager.editFile]):
        // Solo bloquear la escritura significa que no hay bloqueo——Dos llamadas simultáneas leerán cada una el contenido antiguo y lo escribirán respectivamente, perdiendo una actualización.
        return when (val r = manager.editFile(path, scope) { original ->
            when (val plan = planEdit(original, oldString, newString, path)) {
                is EditPlan.Rejected -> WorkspaceManager.EditDecision.Abort(plan.message)
                is EditPlan.Applied -> WorkspaceManager.EditDecision.Commit(plan.updated)
            }
        }) {
            is WorkspaceManager.EditResult.Applied -> renderEdited(path, r)
            is WorkspaceManager.EditResult.Rejected -> "Error: ${r.message}"
            is WorkspaceManager.EditResult.Failed -> "Error: ${r.message}"
        }
    }

    /** Recibos de cambio devueltos: primera fila modificada + fragmento a nivel de fila. */
    private fun renderEdited(path: String, applied: WorkspaceManager.EditResult.Applied): String {
        val diff = diffLines(applied.original, applied.updated)
        return buildString {
            appendLine("OK: workspace_edit actualizado $path")
            appendLine(
                "Cambiar: primera línea cambiada ${diff.firstChangedLine};" +
                    "Texto original ${diff.oldLineCount} OK → nuevo contenido ${diff.newLineCount} OK",
            )
            append(diff.hunk)
        }
    }

    /**
     * Realizar una sustitución de fragmento único sobre el texto original completo.
     *
     * Aquí se juzgan los tres tipos de rechazo previo: el fragmento no se puede encontrar, el fragmento no es único y el contenido antiguo y nuevo es el mismo.
     * No se produce ninguna acción escrita durante la fase de sentencia, por lo que el expediente debe permanecer intacto cuando se rechaza.
     *
     * @param original Contenido completo del archivo
     * @param oldString Fragmento a reemplazar
     * @param newString Fragmento reemplazado
     * @param path Ruta de visualización (solo se usa para copia de error)
     */
    internal fun planEdit(original: String, oldString: String, newString: String, path: String): EditPlan {
        val occurrences = countOccurrences(original, oldString)
        val rejection = when {
            oldString.isEmpty() -> "old_string No puede estar vacío: los fragmentos vacíos coincidirán en cualquier lugar,No estoy seguro de dónde cambiar"
            oldString == newString -> "El contenido antiguo y el nuevo son los mismos y no se requiere ninguna modificación:new_string Literalmente idéntico a old_string"
            occurrences > 1 -> "old_string en $path aparecer en $occurrences veces, proporcione más contexto para que sea único" +
                "(Esta herramienta no reemplazará sólo la primera)"
            occurrences == 0 -> editMissingMessage(original, oldString, path)
            else -> null
        }
        if (rejection != null) return EditPlan.Rejected("$rejection(Archivo sin cambios)")
        val at = original.indexOf(oldString)
        val updated = original.replaceRange(at, at + oldString.length, newString)
        return EditPlan.Applied(updated, diffLines(original, updated))
    }

    /** [needle] en [haystack] El número de ocurrencias en; las apariciones superpuestas también se cuentan una vez (es mejor informar duplicados si hay más y únicas si hay menos). */
    internal fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = haystack.indexOf(needle)
        while (from >= 0) {
            count++
            from = haystack.indexOf(needle, from + 1)
        }
        return count
    }

    /** Avisar cuando no se puede encontrar el fragmento; indique el motivo por el cual el archivo es una nueva línea CRLF y old_string usa LF. */
    private fun editMissingMessage(original: String, oldString: String, path: String): String {
        val crlfHint = if (original.contains("\r\n") && original.replace("\r\n", "\n").contains(oldString)) {
            ";El archivo utiliza ajuste de línea CRLF,old_string Los saltos de línea también deben escribirse como CRLF para que coincidan palabra por palabra."
        } else {
            ""
        }
        return "No se puede encontrar el fragmento:old_string en $path no existe en,Utilice workspace_read o workspace_grep para comprobar primero el texto original." +
            "(La sangría y los saltos de línea deben ser coherentes palabra por palabra.)$crlfHint"
    }

    /**
     * Diferencia a nivel de fila.
     *
     * El reemplazo de un solo punto garantiza que el cambio sea un bloque continuo, por lo que este bloque se puede determinar con un sufijo común y un sufijo, y no se requiere LCS general;
     * Presione nuevamente [DIFF_CONTEXT_LINES] El contexto de la fila se representa como @@ trozo.
     *
     * @param oldText Texto completo antes del reemplazo
     * @param newText Texto completo después del reemplazo
     */
    internal fun diffLines(oldText: String, newText: String): EditDiff {
        val oldLines = oldText.split('\n')
        val newLines = newText.split('\n')
        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) {
            prefix++
        }
        val shared = minOf(oldLines.size - prefix, newLines.size - prefix)
        var suffix = 0
        while (suffix < shared && oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]) {
            suffix++
        }
        val removed = oldLines.subList(prefix, oldLines.size - suffix)
        val added = newLines.subList(prefix, newLines.size - suffix)
        val before = minOf(DIFF_CONTEXT_LINES, prefix)
        val after = minOf(DIFF_CONTEXT_LINES, suffix)
        val hunkStart = prefix - before + 1
        val body = buildString {
            appendLine("@@ -$hunkStart,${before + removed.size + after} +$hunkStart,${before + added.size + after} @@")
            for (i in prefix - before until prefix) appendLine(" ${oldLines[i].trimEnd('\r')}")
            removed.forEach { appendLine("-${it.trimEnd('\r')}") }
            added.forEach { appendLine("+${it.trimEnd('\r')}") }
            for (i in 0 until after) appendLine(" ${oldLines[oldLines.size - suffix + i].trimEnd('\r')}")
        }.trimEnd()
        return EditDiff(
            firstChangedLine = prefix + 1,
            oldLineCount = removed.size,
            newLineCount = added.size,
            hunk = body,
        )
    }

    /** workspace_delete ejecutor. */
    private suspend fun executeDelete(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val path = args["path"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: Se requiere la ruta del parámetro"
        return when (val r = manager.delete(path, scope)) {
            is WorkspaceManager.OpResult.Success -> "OK: Eliminado $path"
            is WorkspaceManager.OpResult.Error -> "Error: ${r.message}"
        }
    }

    /** workspace_mkdir ejecutor. */
    private suspend fun executeMkdir(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val path = args["path"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: Se requiere la ruta del parámetro"
        return when (val r = manager.mkdir(path, scope)) {
            is WorkspaceManager.OpResult.Success -> "OK: Directorio creado $path"
            is WorkspaceManager.OpResult.Error -> "Error: ${r.message}"
        }
    }

    /** workspace_move ejecutor. */
    private suspend fun executeMove(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val from = args["from"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: Parámetro requerido"
        val to = args["to"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: El parámetro es obligatorio"
        return when (val r = manager.move(from, to, scope)) {
            is WorkspaceManager.OpResult.Success -> "OK: movido $from -> $to"
            is WorkspaceManager.OpResult.Error -> "Error: ${r.message}"
        }
    }

    /** workspace_copy ejecutor. */
    private suspend fun executeCopy(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val from = args["from"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: Parámetro requerido"
        val to = args["to"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: El parámetro es obligatorio"
        return when (val r = manager.copy(from, to, scope)) {
            is WorkspaceManager.OpResult.Success -> "OK: copiado $from -> $to"
            is WorkspaceManager.OpResult.Error -> "Error: ${r.message}"
        }
    }

    /** workspace_grep ejecutor. */
    private suspend fun executeGrep(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val pattern = args["pattern"]?.takeIf { it.isNotEmpty() }
            ?: return "Error: Se requiere patrón de parámetros"
        val path = args["path"]?.trim() ?: ""
        val isRegex = args["is_regex"]?.toBoolean() ?: false
        val maxResults = args["max_results"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
        return when (val r = manager.grep(pattern, path, isRegex, maxResults, scope)) {
            is WorkspaceManager.GrepResult.Success -> renderGrepMatches(r)
            is WorkspaceManager.GrepResult.Error -> "Error: ${r.message}"
        }
    }

    /** Representar visitas como rutas:Número de línea:contenido. */
    private fun renderGrepMatches(result: WorkspaceManager.GrepResult.Success): String {
        if (result.matches.isEmpty()) return "ningún golpe(escaneado ${result.scannedFiles} archivos)"
        return buildString {
            result.matches.forEach { m ->
                appendLine("${m.path}:${m.lineNumber}:${m.line}")
            }
            if (result.truncated) {
                append("... El número de visitas alcanza el límite superior.,Resultado truncado;Por favor, reduzca el camino o haga el patrón más preciso")
            }
        }.trimEnd()
    }
}

/**
 * Registro de herramientas del espacio de trabajo.
 *
 * Utilice el registro basado en contexto: el alcance de montaje visible para la sesión depende de [ToolExecutionContext.sessionId] Toma,
 * El registro ordinario no puede obtener información de la sesión.
 *
 * @param toolRegistry Registro de herramientas
 * @param workspaceManager administrador del espacio de trabajo
 */
class WorkspaceToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val workspaceManager: WorkspaceManager,
) {
    init {
        registerAll()
    }

    /** Registre todas las herramientas del espacio de trabajo. */
    fun registerAll() {
        WorkspaceTool.toolDefs().forEach { def ->
            toolRegistry.registerWithContext(def) { args, context ->
                WorkspaceTool.execute(def.name, args, workspaceManager, context.sessionId)
            }
        }
    }
}

/** Diferencia a nivel de línea con reemplazo de un fragmento. */
internal data class EditDiff(
    /** El número de línea de la primera línea modificada (1 de). */
    val firstChangedLine: Int,
    /** El número de líneas originales que fueron reemplazadas. */
    val oldLineCount: Int,
    /** El número de líneas de contenido nuevo después del reemplazo. */
    val newLineCount: Int,
    /** @@ trozo de texto con contexto. */
    val hunk: String,
)

/** El resultado del juicio del reemplazo de fragmentos. */
internal sealed class EditPlan {
    /** Rechazo: Con motivo, el archivo no ha sido modificado. */
    data class Rejected(val message: String) : EditPlan()

    /** Se puede colocar en el disco: el texto completo modificado + la diferencia requerida para el recibo. */
    data class Applied(val updated: String, val diff: EditDiff) : EditPlan()
}
