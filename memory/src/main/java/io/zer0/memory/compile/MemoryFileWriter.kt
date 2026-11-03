package io.zer0.memory.compile

import io.zer0.common.Logger
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * v6: Salida del archivo del producto de compilación de memoria.
 *
 * poner [MemoryCompiler] Cada contenido generado se escribe en el directorio privado de la aplicación al mismo tiempo.,Conveniente:
 *  - Depuración del desarrollador(Abra el archivo directamente para ver qué recuerda la IA.)
 *  - copia de seguridad/restaurar(Los archivos son más fáciles de migrar que Room DB)
 *  - Proporcionar experiencia de observación de agent-dir/memory.md
 *
 * estructura de salida:
 *  - {baseDir}/memory/memory.md      Cuatro recuerdos tras el montaje
 *  - {baseDir}/memory/daily/{date}.md Resumen por día(ventana enrollable)
 *
 * v6.1: Se agregó un método auxiliar para rodar la cinta transportadora durante el día.(list/read/assemble/roll),Apoye la semana de asamblea cero LLM.
 */
class MemoryFileWriter(
    /** directorio raíz,Normalmente Context.filesDir. */
    private val baseDir: File,
) {

    private val memoryDir by lazy { File(baseDir, "memory").apply { mkdirs() } }

    /** Explicit targets are isolated below memory/{scope}/{space}; null keeps the legacy root. */
    private fun targetDir(target: MemoryCompileTarget?): File {
        if (target == null) return memoryDir
        val dir = File(memoryDir, "${safePathSegment(target.normalizedScope)}/${safePathSegment(target.normalizedSpaceId)}")
        dir.mkdirs()
        return dir
    }

    private fun dailyDir(target: MemoryCompileTarget?): File = File(targetDir(target), "daily").apply { mkdirs() }

    /** Encode path separators and traversal tokens rather than allowing user-controlled paths. */
    private fun safePathSegment(value: String): String {
        val encoded = buildString {
            value.forEach { c ->
                if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || c == '.') {
                    append(c)
                } else {
                    append('_').append(c.code.toString(16).padStart(4, '0'))
                }
            }
        }
        return encoded.takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "_"
    }

    // v1.0.47: Registre el último contenido escrito de Memory.md,Si es lo mismo, omita IO,Evite la escritura repetida de archivos pequeños de 59 bytes.
    //
    // 2026-11-01 Mejoras de rendimiento:Eliminar duplicados y cambiar a**prensa target**.
    // Estaba incluido en las condiciones anteriores. `target == null`,Y leer camino `MemoryCompiler.readCompiledMemoryMarkdown`
    // Siempre llama con objetivo —— Entonces "leer la memoria una vez" equivale a "escribir el archivo atómicamente una vez"(escribir archivo temporal + rename,
    // Más targetDir/dailyDir mkdirs). Cada personaje en el cambio de conversación y el chat grupal seguirá esta ruta de lectura en cada ronda de respuesta.
    // Después de cambiar para registrar el contenido por destino,Cuando el contenido no ha cambiado, el directorio ni siquiera se toca.
    private val lastWrittenByTarget = LinkedHashMap<String, String>()

    // C-06: dedup Utilice objetos de bloqueo independientes,synchronized Requiere cualquier monitor que no esté vacío.
    private val memoryMdLock = Any()

    /**
     * Clave de deduplicación:null target Se utiliza la antigua semántica del "directorio raíz" y el ámbito/espacio normalizado se utiliza para valores no nulos.
     * Utilice claves lógicas en lugar de rutas de directorio absolutas,Esto es para evitar que se active la ruta rápida. [targetDir] mkdirs en.
     */
    private fun targetKey(target: MemoryCompileTarget?): String =
        if (target == null) "" else "${target.normalizedScope}/${target.normalizedSpaceId}"

    companion object {
        private const val TAG = "MemoryFileWriter"

        /** El límite superior del objetivo rastreado por la tabla de deduplicación; si excede el límite del objetivo, todo el objetivo será limpiado y reconstruido. */
        private const val MAX_TRACKED_TARGETS = 16

        /** week El segmento muestra los N días lógicos terminados antes de hoy.;Las entradas más antiguas se incluyen en las de largo plazo. */
        const val DAILY_WINDOW_RETENTION_DAYS = 6

        /** week.md Tapa dura de longitud total(Número de caracteres),Aproximadamente del mismo tamaño que el segmento semanal de LLM que reemplazó. */
        const val WEEK_ASSEMBLY_MAX_CHARS = 1200

        private val DAILY_FILE_RE = Regex("^(\\d{4}-\\d{2}-\\d{2})\\.md$")
        private val DAILY_HEADING_RE = Regex("^#{1,2}\\s*(\\d{4}-\\d{2}-\\d{2})\\s*$")
    }

    /**
     * Escriba la memoria ensamblada.md.
     *
     * @param content Contenido completo de rebajas(cuatro recuerdos)
     * @param locale Idioma,solo para troncos
     */
    fun writeMemoryMd(content: String, locale: String = "es", target: MemoryCompileTarget? = null) {
        // C-06: dedup Cerrar — leer ruta(readCompiledMemoryMarkdown)Este método se llamará,
        // Lectura concurrente-Verificar y actuar debe serializarse al escribir en el mismo archivo.
        synchronized(memoryMdLock) {
            val key = targetKey(target)
            if (content == lastWrittenByTarget[key]) {
                Logger.d(TAG, "memory.md unchanged, skip write (${content.length} chars)")
                return
            }
            runCatching {
                val dir = targetDir(target)
                val file = File(dir, "memory.md")
                // C-06: Archivo temporal + átomo rename,Evite que los procesos a medio escribir se eliminen y dejen archivos corruptos
                writeAtomically(file, content)
                // El límite superior es sólo para mantener "target El "crecimiento infinito" es una situación extrema; si realmente se desborda, será limpiado y reconstruido en su totalidad.,
                // El peor resultado es escribir el archivo una vez más.,No afecta la corrección.
                if (lastWrittenByTarget.size >= MAX_TRACKED_TARGETS && !lastWrittenByTarget.containsKey(key)) {
                    lastWrittenByTarget.clear()
                }
                lastWrittenByTarget[key] = content
            }.onFailure {
                Logger.w(TAG, "Error al escribir en la memoria.md: ${it.message}", it)
            }
        }
        Logger.d(TAG, "memory.md updated (${content.length} chars, locale=$locale)")
    }

    /** Leer el contenido actual de Memory.md,No existe o devuelve nulo en caso de error. */
    fun readMemoryMd(target: MemoryCompileTarget? = null): String? = runCatching {
        val file = File(targetDir(target), "memory.md")
        if (file.exists()) file.readText() else null
    }.getOrElse {
        Logger.w(TAG, "No se pudo leer la memoria.md: ${it.message}")
        null
    }

    /**
     * Escribir resumen por día daily/{date}.md.
     *
     * @param date ISO Fecha(yyyy-MM-dd)
     * @param content Texto resumido del día.(Sin encabezado de fecha;La persona que llama lo agregará él mismo.)
     */
    fun writeDailyMd(date: String, content: String, target: MemoryCompileTarget? = null) {
        runCatching {
            val body = content.trim()
            val file = File(dailyDir(target), "$date.md")
            // C-06: Archivo temporal + átomo rename
            if (body.isEmpty()) {
                writeAtomically(file, "")
            } else {
                writeAtomically(file, "## $date\n\n$body\n")
            }
            Logger.d(TAG, "daily/$date.md updated (${body.length} chars)")
        }.onFailure {
            Logger.w(TAG, "escribir daily/$date.md falló: ${it.message}", it)
        }
    }

    /**
     * C-06: escrituras atómicas — Primero escriba .tmp en el mismo directorio y luego sobrescríbalo con cambiar nombre,
     * proceso matado/No queda ningún archivo de objeto truncado al escribir a mitad de camino(Si usa readText directamente en la ruta de lectura, se leerá la mitad del contenido.).
     *
     * Revisión de corrección (2.0 C-05): rename La recuperación del fracaso ya no es sencilla writeText — Eliminar el objetivo primero y luego rename,
     * Deje que el camino alternativo también mantenga la semántica atómica.(La sobrescritura directa reintroducirá la ventana de truncamiento al revertir).
     */
    private fun writeAtomically(file: File, content: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(file)) {
            // rename falló(Muy raro,Si el objetivo está ocupado):Eliminar el objetivo primero y luego rename,Si aún falla, simplemente escriba los detalles directamente.
            val targetGone = file.delete() || !file.exists()
            if (targetGone && tmp.renameTo(file)) return
            file.writeText(content)
        }
    }

    /** Leer el texto del archivo diario de la fecha especificada.(despegar "## {date}" mirar hacia arriba),Devuelve una cadena vacía si no existe o falla. */
    fun readDailyEntryBody(date: String, target: MemoryCompileTarget? = null): String = runCatching {
        val file = File(dailyDir(target), "$date.md")
        if (!file.exists()) return@runCatching ""
        normalizeDailyBody(file.readText())
    }.getOrElse {
        Logger.w(TAG, "leer daily/$date.md falló: ${it.message}")
        ""
    }

    /** Escribir manualmente el texto de un diario de un solo día.(Edición de usuario/reescritura autorizada),El formato es coherente con el producto compileDaily. */
    fun writeDailyEntryBody(date: String, body: String, target: MemoryCompileTarget? = null) {
        writeDailyMd(date, body, target)
    }

    /** Listar todas las entradas diarias del archivo,Ordenado por fecha. */
    fun listDailyEntries(target: MemoryCompileTarget? = null): List<DailyEntry> = runCatching {
        dailyDir(target).listFiles { _, name -> DAILY_FILE_RE.matches(name) }
            ?.map { DailyEntry(it.nameWithoutExtension, it) }
            ?.sortedBy { it.date }
            ?: emptyList()
    }.getOrElse {
        Logger.w(TAG, "No se pudieron enumerar los archivos diarios: ${it.message}")
        emptyList()
    }

    /**
     * lista reciente [maxDays] entradas diarias.
     *
     * @param maxDays Días máximos de retención,Predeterminado [DAILY_WINDOW_RETENTION_DAYS]
     */
    fun listRecentDailyEntries(maxDays: Int = DAILY_WINDOW_RETENTION_DAYS, target: MemoryCompileTarget? = null): List<DailyEntry> {
        return listDailyEntries(target).takeLast(maxDays)
    }

    /**
     * Reúna el contenido de Week.md a partir de archivos simples en el directorio daily/:Tome los últimos N días de entradas del diario y concatenelos en orden de fecha positivo.
     * Cero llamadas de LLM.
     *
     * @param maxDays Días de ventana,Predeterminado [DAILY_WINDOW_RETENTION_DAYS]
     * @param maxChars Longitud total máxima,Predeterminado [WEEK_ASSEMBLY_MAX_CHARS]
     * @return Cadena de rebajas ensamblada(El segmento vacío devuelve una cadena vacía)
     */
    fun assembleWeekFromDaily(
        maxDays: Int = DAILY_WINDOW_RETENTION_DAYS,
        maxChars: Int = WEEK_ASSEMBLY_MAX_CHARS,
        target: MemoryCompileTarget? = null,
    ): String = runCatching {
        val entries = listRecentDailyEntries(maxDays, target)
        val blocks = entries.mapNotNull { entry ->
            val text = entry.file.readText().trim()
            text.takeIf { it.isNotEmpty() }
        }
        if (blocks.isEmpty()) return@runCatching ""

        var content = blocks.joinToString("\n\n")
        if (content.length > maxChars) {
            val kept = blocks.toMutableList()
            while (kept.size > 1 && kept.joinToString("\n\n").length > maxChars) {
                kept.removeAt(0)
            }
            content = kept.joinToString("\n\n")
            if (content.length > maxChars) {
                content = content.take(maxChars)
            }
            Logger.w(TAG, "assembleWeekFromDaily: La longitud total excede el límite superior.($maxChars palabra),Truncado desde la entrada más antigua")
        }
        "$content\n"
    }.getOrElse {
        Logger.w(TAG, "assembleWeekFromDaily falló: ${it.message}", it)
        ""
    }

    /**
     * Fusionar y devolver el contenido de las entradas diarias sacadas de la ventana de N días,Elimine el archivo fuente después del éxito.
     * Es responsabilidad de la persona que llama plegar los contenidos combinados devueltos en un archivo a largo plazo.
     *
     * @param referenceDate fecha de referencia(yyyy-MM-dd),Predeterminado hoy
     * @param retentionDays días de retención,Predeterminado [DAILY_WINDOW_RETENTION_DAYS]
     * @return Contenido de rebajas combinado(Vacío significa que no hay contenido que deba plegarse)y lista de fechas fallidas
     */
    fun rollDailyWindow(
        referenceDate: String = LocalDate.now().toString(),
        retentionDays: Int = DAILY_WINDOW_RETENTION_DAYS,
        target: MemoryCompileTarget? = null,
    ): RollResult = runCatching {
        val cutoff = runCatching {
            LocalDate.parse(referenceDate).minusDays(retentionDays.toLong()).toString()
        }.getOrElse {
            Logger.w(TAG, "rollDailyWindow: referenceDate El análisis falló($referenceDate),saltar")
            return@runCatching RollResult(emptyList(), emptyList(), "")
        }

        val entries = listDailyEntries(target).filter { it.date < cutoff }
        if (entries.isEmpty()) {
            return@runCatching RollResult(emptyList(), emptyList(), "")
        }

        val blocks = entries.mapNotNull { entry ->
            // v1.0.51: daily El archivo fue escrito por writeDailyMd con "## $date" mirar hacia arriba,
            // Utilice el texto original del archivo directamente aquí.,Evite contaminar la entrada de pliegues a largo plazo con encabezados repetidos.
            val text = entry.file.readText().trim()
            text.takeIf { it.isNotEmpty() }
        }

        if (blocks.isEmpty()) {
            // Todos los archivos vacíos:Limpiar directamente
            entries.forEach { runCatching { it.file.delete() } }
            return@runCatching RollResult(entries.map { it.date }, emptyList(), "")
        }

        val combined = blocks.joinToString("\n\n")
        RollResult(entries.map { it.date }, emptyList(), combined)
    }.getOrElse {
        Logger.w(TAG, "rollDailyWindow falló: ${it.message}", it)
        RollResult(emptyList(), emptyList(), "")
    }

    /** Elimine el archivo diario de la fecha especificada después de que el plegado sea exitoso.;En caso de error, el archivo fuente se conserva para volver a intentarlo en la siguiente ronda. */
    fun deleteDailyFiles(dates: List<String>, target: MemoryCompileTarget? = null) {
        dates.forEach { date ->
            runCatching {
                File(dailyDir(target), "$date.md").delete()
            }.onFailure {
                Logger.w(TAG, "Eliminar daily/$date.md falló: ${it.message}")
            }
        }
    }

    /** Elimine el archivo diario en la fecha especificada. */
    fun deleteDailyMd(date: String, target: MemoryCompileTarget? = null) {
        runCatching {
            File(dailyDir(target), "$date.md").delete()
            File(dailyDir(target), "$date.md.fingerprint").delete()
        }.onFailure {
            Logger.w(TAG, "Eliminar daily/$date.md falló: ${it.message}")
        }
    }

    /** Leer el archivo diario de la fecha especificada. fingerprint,Devuelve nulo si no está presente. */
    fun readDailyFingerprint(date: String, target: MemoryCompileTarget? = null): String? = runCatching {
        val file = File(dailyDir(target), "$date.md.fingerprint")
        if (file.exists()) file.readText().trim() else null
    }.getOrElse {
        Logger.w(TAG, "leer daily/$date.md.fingerprint falló: ${it.message}")
        null
    }

    /** Escriba la huella digital del archivo diario en la fecha especificada. */
    fun writeDailyFingerprint(date: String, fingerprint: String, target: MemoryCompileTarget? = null) {
        runCatching {
            File(dailyDir(target), "$date.md.fingerprint").writeText(fingerprint)
        }.onFailure {
            Logger.w(TAG, "escribir daily/$date.md.fingerprint falló: ${it.message}", it)
        }
    }

    /** Elimine el archivo de huellas digitales diario para la fecha especificada. */
    fun deleteDailyFingerprint(date: String, target: MemoryCompileTarget? = null) {
        runCatching {
            File(dailyDir(target), "$date.md.fingerprint").delete()
        }.onFailure {
            Logger.w(TAG, "Eliminar daily/$date.md.fingerprint falló: ${it.message}")
        }
    }

    /** Eliminar todos los archivos diarios y fingerprint(Para restablecer la memoria). */
    fun clearAllDailyFiles(target: MemoryCompileTarget? = null) {
        runCatching {
            dailyDir(target).listFiles { _, name ->
                DAILY_FILE_RE.matches(name) || name.endsWith(".md.fingerprint")
            }?.forEach { it.delete() }
        }.onFailure {
            Logger.w(TAG, "No se pudo borrar el archivo diario: ${it.message}", it)
        }
    }

    /**
     * Eliminar el directorio de memoria de un alcance.(Eso es `memory/<scope>/` Todos los archivos bajo).
     *
     * Eliminar solo este directorio de alcance: el raíz `memory.md` con `daily/` Es una versión antigua del producto de un solo espacio.,
     * no pertenece a ninguna scope;Los directorios en otros ámbitos tampoco se ven afectados.
     *
     * @return Número de archivos eliminados(Cuando el directorio no existe, se 0)
     */
    fun deleteScopeDir(scope: String): Int = runCatching {
        val dir = scope.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { File(memoryDir, safePathSegment(it)) }
            ?.takeIf { it.isDirectory }
        val deleted = dir?.walkTopDown()?.count { it.isFile } ?: 0
        dir?.deleteRecursively()
        deleted
    }.getOrElse {
        Logger.w(TAG, "No se pudo eliminar el directorio de alcance: ${it.message}", it)
        0
    }

    /** Agregar metainformación al encabezado del archivo(tiempo de generacion),Fácil de depurar. */
    fun buildHeader(note: String): String {
        return "<!-- Generated: ${Instant.now()} | $note -->\n\n"
    }

    /** Quitar archivos diarios de "## {date}" mirar hacia arriba,Volver al texto. */
    private fun normalizeDailyBody(raw: String): String {
        val lines = raw.lines()
        val bodyLines = mutableListOf<String>()
        var skippedHeading = false
        for (line in lines) {
            if (!skippedHeading && DAILY_HEADING_RE.matches(line.trim())) {
                skippedHeading = true
                continue
            }
            bodyLines.add(line)
        }
        return bodyLines.joinToString("\n").trim()
    }

    /** Entrada de archivo de diario de un solo día. */
    data class DailyEntry(
        val date: String,
        val file: File,
    )

    /** Resultados de ventana celular. */
    data class RollResult(
        val folded: List<String>,
        val failed: List<String>,
        val combinedContent: String,
    )
}
