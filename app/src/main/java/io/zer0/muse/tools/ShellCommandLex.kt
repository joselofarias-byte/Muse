package io.zer0.muse.tools

import java.io.File

/**
 * Capa léxica utilizada para límites de comando:Divida una cadena de shell en unidades decidibles.
 *
 *  - [ShellSegment]:Presione el carácter de control de shell(`|` `||` `&&` `;` `&` nueva línea)Recorta una sección de comando.,
 *    Una pequeña herramienta que toma "nombre de comando de primer nivel/parámetro/ruta similar a parámetro".
 *  - [ShellLocator]:Coloque un parámetro de ruta en el punto de referencia del espacio de trabajo para determinar el punto de aterrizaje(Dentro del espacio de trabajo/raíz del espacio de trabajo /
 *    `/data` Datos privados de la aplicación/partición del sistema/volumen de almacenamiento/raíz del dispositivo/fuera del espacio de trabajo/no se pueden determinar).
 *  - [ShellCommandLex]:Juicio de la forma del parámetro.(Cambio de recursividad, modo de escritura mundial, punto de encuentro inofensivo).
 *
 * aquí es deliberado**No**Hacer análisis semántico de shell:Sólo se realiza la coincidencia a nivel de segmento y parámetro. El criterio de fuerza destructiva en sí es
 * [ShellCommandBoundary],¿Existe una organización basada en "¿Qué se puede destruir con una sola orden"?,Aquí sólo respondemos "donde toca".
 */

/** Qué se puede encontrar con un parámetro de ruta. */
internal enum class ShellScope {
    /** Estrictamente dentro del espacio de trabajo:El alcance normal de la herramienta. */
    WORKSPACE,

    /** La propia raíz del espacio de trabajo. */
    WORKSPACE_ROOT,

    /** `/data` Aplicación de datos privados fuera del espacio de trabajo.(Contiene otras aplicaciones). */
    APP_DATA,

    /** Partición del sistema e interfaz del kernel. */
    SYSTEM,

    /** Volúmenes de almacenamiento visibles para el usuario. */
    STORAGE,

    /** Raíz del dispositivo `/`. */
    DEVICE_ROOT,

    /** `/dev/null` Puntos de reunión tan inofensivos. */
    SINK,

    /** Otros caminos fuera del espacio de trabajo. */
    OUTSIDE,

    /** No puedo decir el punto de aterrizaje(Ruta relativa sin base de directorio de trabajo). */
    UNKNOWN,
}

/** Punto de caída de los parámetros de ruta,Y si llega a \"todo el directorio\" o \"a un subconjunto del directorio\". */
internal data class ShellTarget(val scope: ShellScope, val wholeDirectory: Boolean)

/**
 * un comando:Su delimitador es el mismo que el de la ficha cortada.
 * `|` La sección es un comando que se "alimenta con el resultado de la sección anterior".;`&&`/`||`/`;` Cada segmento es un comando independiente.
 */
internal class ShellSegment(val separator: String, val tokens: List<String>) {

    /** El aguas arriba alimenta la salida a esta sección a través de la tubería. */
    val isPipeInput: Boolean get() = separator == "|"

    /** Subíndice de comando de primer nivel:saltar `FOO=bar` Prefijo de variable de entorno con `nohup`/`timeout` Este tipo de comando de empaquetado. */
    private fun verbIndex(): Int? = tokens.indexOfFirst { token ->
        !ENV_ASSIGNMENT.matches(token) && bareName(token) !in COMMAND_WRAPPERS
    }.takeIf { it >= 0 }

    /** Nombre del comando de primer nivel(Eliminar el prefijo de ruta y convertirlo a minúsculas). */
    fun verb(): String? = verbIndex()?.let { bareName(tokens[it]) }

    /** Parámetros después del comando de primer nivel. */
    fun args(): List<String> = verbIndex()?.let { tokens.drop(it + 1) } ?: emptyList()

    /** El token \"como ruta\" en el parámetro: opción de exclusión y `of=x` Tales operandos. */
    fun pathArgs(): List<String> = args().filterNot { it.startsWith("-") || OPERAND_ASSIGNMENT.matches(it) }

    private fun bareName(token: String) = token.substringAfterLast('/').lowercase()

    companion object {
        private val SEGMENT_SEPARATOR = Regex("""\|\||&&|[;|&\r\n]""")
        private val WHITESPACE = Regex("\\s+")

        /** `FOO=bar rm -rf x` adentro `FOO=bar` ¿Es la asignación de variable de entorno?,No es una orden. */
        private val ENV_ASSIGNMENT = Regex("""[A-Za-z_][A-Za-z0-9_]*=.*""")

        /** `dd of=x` este tipo `key=value` El operando no es un parámetro posicional.,Camino equivocado para mirar. */
        private val OPERAND_ASSIGNMENT = Regex("""[a-z]{1,4}=.*""")

        /** comando contenedor:El verdadero comando de primer nivel está detrás de esto.,Omítelos al elegir nombres de comandos. */
        private val COMMAND_WRAPPERS = setOf(
            "env", "nohup", "time", "timeout", "nice", "ionice", "xargs", "watch", "command", "exec",
            "busybox", "toybox",
        )

        fun split(command: String): List<ShellSegment> {
            val result = mutableListOf<ShellSegment>()
            var cursor = 0
            var separator = ""
            for (match in SEGMENT_SEPARATOR.findAll(command)) {
                append(result, separator, command.substring(cursor, match.range.first))
                separator = match.value
                cursor = match.range.last + 1
            }
            append(result, separator, command.substring(cursor))
            return result
        }

        private fun append(into: MutableList<ShellSegment>, separator: String, text: String) {
            val tokens = tokenize(text)
            if (tokens.isNotEmpty()) into += ShellSegment(separator, tokens)
        }

        private fun tokenize(text: String): List<String> =
            text.trim().split(WHITESPACE).filter { it.isNotEmpty() }.map(ShellCommandLex::stripQuotes)
    }
}

/**
 * Determinador del punto de caída de la ruta:Basado en el directorio del espacio de trabajo de la herramienta.,Clasifique las rutas en los parámetros del comando.
 *
 * El espacio de trabajo en el dispositivo es `/data/user/0/<pkg>/files/workspace`,y `/data/data/<pkg>` son los mismos datos
 * Método de escritura de enlaces suaves,Por lo tanto, la ruta absoluta debe escribirse de dos maneras: "tal cual" y "después de desbloquear el enlace suave". —— De lo contrario
 * `cat x > /data/data/<pkg>/files/workspace/out.txt` La escritura normal en este espacio de trabajo se considerará erróneamente como fuera de los límites.
 *
 * @param workspaceDir Directorio de trabajo de la herramienta;Cuando es nulo, la ruta relativa no puede determinar el punto de aterrizaje.,Nunca participes en la interceptación.
 */
internal class ShellLocator(private val workspaceDir: File?) {

    private val workspacePosix: String? = workspaceDir?.let { dir ->
        toPosix(runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath))
    }

    /** Coloque un parámetro de ruta en el punto de referencia del espacio de trabajo para determinar el punto de aterrizaje. */
    fun target(raw: String): ShellTarget {
        val trimmed = ShellCommandLex.stripQuotes(raw).trim()
        if (trimmed.isEmpty()) return ShellTarget(ShellScope.UNKNOWN, wholeDirectory = true)
        val path = expandHome(trimmed).removeSurrounding("$(", ")").removeSurrounding("(", ")")
        val lastSegment = path.substringAfterLast('/')
        val globbed = lastSegment.isNotEmpty() && lastSegment.all { it == '*' || it == '?' }
        // `*` Pulsa todos los contenidos del directorio.,`*.log` Golpea solo un subconjunto —— Diferentes niveles de poder destructivo.
        val container = if (globbed) path.substringBeforeLast('/', "").ifEmpty { "." } else path
        return ShellTarget(classify(absoluteize(normalizeTrailing(container))), wholeDirectory = !globbed || lastSegment == "*")
    }

    private fun classify(path: String?): ShellScope = when {
        path == null -> ShellScope.UNKNOWN
        path == "/" -> ShellScope.DEVICE_ROOT
        // La determinación del espacio de trabajo debe colocarse antes de /data: el espacio de trabajo en sí está colgado debajo de /data
        resolvesToWorkspaceRoot(path) -> ShellScope.WORKSPACE_ROOT
        resolvesInsideWorkspace(path) -> ShellScope.WORKSPACE
        ShellCommandLex.isHarmlessSink(path) -> ShellScope.SINK
        SYSTEM_PATHS.any { path == it || path.startsWith("$it/") } -> ShellScope.SYSTEM
        path == "/data" || path.startsWith("/data/") -> ShellScope.APP_DATA
        STORAGE_PATHS.any { path == it || path.startsWith("$it/") } -> ShellScope.STORAGE
        else -> ShellScope.OUTSIDE
    }

    private fun resolvesToWorkspaceRoot(path: String) =
        path == workspacePosix || variants(path).any { it == workspacePosix }

    private fun resolvesInsideWorkspace(path: String) =
        insideWorkspace(path) || variants(path).any { insideWorkspace(it) }

    private fun insideWorkspace(absolute: String) =
        workspacePosix != null && absolute != workspacePosix && absolute.startsWith("$workspacePosix/")

    /** Otras formas de escribir la misma ruta absoluta(Desatar la cadena suave);Si no puedes encontrar una forma más profunda de escribir, déjala como está. */
    private fun variants(path: String): List<String> {
        if (!path.startsWith("/")) return emptyList()
        val canonical = runCatching { toPosix(File(path).canonicalPath) }.getOrDefault(path)
        return if (canonical == path) emptyList() else listOf(canonical)
    }

    /**
     * Las rutas relativas se resuelven en rutas absolutas según el espacio de trabajo.;Devuelto cuando no hay una línea base del espacio de trabajo null ——
     * Si no puede determinar el punto de aterrizaje, no participará en la interceptación.,Evite la muerte accidental.
     */
    private fun absoluteize(path: String): String? {
        if (path.startsWith("/")) return path
        if (workspaceDir == null) return null
        return runCatching { File(workspaceDir, path).canonicalPath }.getOrNull()?.let(::toPosix)
    }

    /** Android En el proceso de solicitud `~`/`$HOME` Expandir a la raíz del dispositivo,No puede considerarse como \"el propio nido del usuario\". */
    private fun expandHome(path: String): String {
        val forms = listOf("~", "\$HOME", "\${HOME}")
        if (path in forms) return "/"
        val prefix = forms.firstOrNull { path.startsWith("$it/") } ?: return path
        return path.removePrefix(prefix)
    }

    private fun normalizeTrailing(path: String) = path.trimEnd('/').ifEmpty { "/" }

    private fun toPosix(path: String) = path.replace('\\', '/')

    companion object {
        /**
         * Rutas que serán afectadas por estos prefijos:Partición de solo lectura o interfaz del kernel de toda la máquina,El terminal no se puede utilizar para fines normales.
         * `/dev/block` `/dev/disk` columna única —— `/dev/null` Estos nodos inofensivos no están incluidos en esta lista.
         */
        private val SYSTEM_PATHS = listOf(
            "/system", "/vendor", "/product", "/apex", "/boot", "/recovery", "/sbin",
            "/proc", "/sys", "/etc", "/linkerconfig", "/dev/block", "/dev/disk",
        )

        private val STORAGE_PATHS = listOf("/sdcard", "/storage", "/mnt", "/oem", "/data/media")
    }
}

/** Juicio de la forma del parámetro.:No tiene nada que ver con el punto de aterrizaje.,Basta con mirar qué operación describe este token/cadena de parámetros. */
internal object ShellCommandLex {

    private val HARMLESS_SINKS = listOf("/dev/null", "/dev/zero", "/dev/stdout", "/dev/stderr", "/dev/tty")

    private val WORLD_WRITABLE_LAST_DIGITS = charArrayOf('6', '7')

    /** Declarar explícitamente \"stdin Este es el parámetro de mi fuente de comando\". */
    private val STDIN_CODE_ARGS = setOf("-", "-s", "--stdin")

    fun isHarmlessSink(path: String) = HARMLESS_SINKS.any { path == it || path.startsWith("$it/") }

    /** `-r`/`-R`/`-rf`/`--recursive`:interruptor recursivo(Las opciones cortas combinadas se ven carácter por carácter). */
    fun isRecursiveFlag(token: String) =
        token == "--recursive" ||
            (token.startsWith("-") && !token.startsWith("--") && token.any { it == 'r' || it == 'R' })

    /** Modo de escritura mundial:`777`/`776`/`o+w`/`a+w` Este tipo de escritura permite que otros usuarios escriban permisos. */
    fun isWorldWritableMode(token: String): Boolean {
        if (token.startsWith("-")) return false
        if (token.contains("+w") && (token.startsWith("a") || token.startsWith("o") || token == "+w")) return true
        val octal = token.filter { it.isDigit() }
        return octal.length == 3 && octal.last() in WORLD_WRITABLE_LAST_DIGITS
    }

    fun stripQuotes(token: String) = token.removeSurrounding("\"").removeSurrounding("'")

    /**
     * ¿Se considera la aguas arriba del oleoducto?**código de comando**leer:
     *  - El intérprete no tiene argumentos de guión.,o el parámetro es `-`/`-s`/`--stdin` → stdin es la fuente del comando
     *  - shell Mientras no lo traigas `-c` Simplemente lea el comando de stdin
     * `… | python3 -m json.tool`,`… | python3 fmt.py`,`… | sh -c 'echo hi'` Ninguno de ellos cuenta.
     */
    fun stdinIsCommandSource(args: List<String>, isShell: Boolean) = when {
        args.isEmpty() || args.first() in STDIN_CODE_ARGS -> true
        isShell -> args.none { it == "-c" }
        else -> false
    }

    /** ¿Los parámetros están extrayendo contenido remoto?(`eval "$(curl …)"` Este tipo de criterio se utiliza). */
    fun loadsRemoteContent(token: String) =
        token.contains("curl") || token.contains("wget") || token.contains("://")
}
