package io.zer0.memory.state

import io.zer0.memory.format.RollingSummaryFormat

/**
 * Compilar la gestión del estado del producto.
 *
 * Responsabilidades:
 *  - section body estandarizar(ir <think> bloquear, eliminar la línea de título, presionar varias líneas vacías)
 *  - LLM Normalizar los resultados de salida(Deshazte de vallas y bloques prepensados.)
 *  - reset Marcar leído y escrito(Marca de agua de restablecimiento de memoria,Filtrar resúmenes antiguos)
 */
object CompiledMemoryState {

    /** Compile la constante del nombre del archivo del producto. */
    val COMPILED_FILES = listOf("memory.md", "facts.md", "today.md", "week.md", "longterm.md")

    // 2026-11-01 Mejoras de rendimiento:Estas regularidades se construyeron originalmente en el sitio dentro del cuerpo funcional.
    // normalizeSectionBody Cada llamada necesita crear 3 nuevas.stripLeadingHeadings 1 uno,
    // normalizeLlmOutput 3 ; y readCompiledMemoryMarkdown llamará al
    // normalizeSectionBody cuatro veces (facts/today/week/longterm), superpuesto para leer una vez
    // Simplemente recopile más de una docena de patrones de memoria. Todas las constantes se compilan solo una vez.
    private val JSON_STRING_ARRAY_RE =
        Regex("""^\s*\[\s*("(?:[^"\\]|\\.)*"(?:\s*,\s*"(?:[^"\\]|\\.)*")*)\s*]\s*$""", RegexOption.MULTILINE)
    private val JSON_QUOTED_RE = Regex(""""(?:[^"\\]|\\.)*"""")
    private val MANY_BLANK_LINES_RE = Regex("\n{3,}")
    private val LINE_BREAK_RE = Regex("\\r?\\n")
    private val FENCED_BLOCK_RE = Regex("""^\s*```(?:markdown|md|text)?\s*\n([\s\S]*?)\n```\s*$""")
    private val THINK_BLOCK_RE = Regex("<think(?:ing)?>[\\s\\S]*?</think(?:ing)?>", RegexOption.IGNORE_CASE)
    private val THINK_OPEN_TAIL_RE = Regex("<think(?:ing)?>[\\s\\S]*$", RegexOption.IGNORE_CASE)
    private val THINK_OPEN_RE = Regex("<think(?:ing)?>", RegexOption.IGNORE_CASE)
    private val THINK_CLOSE_RE = Regex("</think(?:ing)?>", RegexOption.IGNORE_CASE)

    /**
     * estandarizar section body:
     *  - Quitar <think>...</think> bloquear(Etiqueta de pensamiento de salida del modelo parcial)
     *  - Eliminar la línea principal del encabezado de rebajas al principio(Evite la contaminación del encabezado de salida LLM # assemble)
     *    Nota: Solo elimine los títulos consecutivos que comiencen con el cuerpo.,Preservar la estructura de los subtítulos en el cuerpo del texto.
     *    (Por ejemplo, en la sección de hechos ### Work / ### Personal subcategoría)
     *  - Comprime varias líneas vacías en líneas únicas vacías
     *  - JSON matriz de cadenas ["a","b"] girar bullet list
     */
    fun normalizeSectionBody(value: String): String {
        if (value.isEmpty()) return ""
        var s = value
        s = stripThinkTagBlocks(s)
        // Elimine solo las líneas de encabezado consecutivas que comiencen con el cuerpo(pretítulo),Conserve la estructura de los subtítulos dentro del cuerpo del texto.
        // Deje de eliminar cuando encuentre la primera línea que no esté en blanco ni sin título,El contenido posterior permanece tal cual.
        s = stripLeadingHeadings(s)
        // JSON matriz de cadenas → bullet list (["a","b"] → "- a\n- b")
        val jsonArrMatch = JSON_STRING_ARRAY_RE.find(s)
        if (jsonArrMatch != null) {
            val items = JSON_QUOTED_RE.findAll(jsonArrMatch.groupValues[1])
                .map { it.value.trim('"') }
                .toList()
            if (items.isNotEmpty()) {
                s = items.joinToString("\n") { "- $it" }
            }
        }
        // Compresión Envoltura de 3+ líneas Envoltura de 2 líneas
        s = s.replace(MANY_BLANK_LINES_RE, "\n\n")
        return s.trim()
    }

    /**
     * Quita las líneas de encabezado consecutivas al principio.(Permitir líneas en blanco entre las líneas del encabezado).
     * Deténgase cuando encuentre la primera línea que no esté en blanco ni sin título,Contenido de seguimiento(Contiene subtítulos)Déjalo como está.
     */
    private fun stripLeadingHeadings(value: String): String {
        val lines = value.split(LINE_BREAK_RE)
        var idx = 0
        // Saltar líneas vacías iniciales + líneas de encabezado
        while (idx < lines.size) {
            val line = lines[idx]
            if (line.isBlank()) {
                idx++
                continue
            }
            if (RollingSummaryFormat.parseMarkdownHeading(line) != null) {
                idx++
                continue
            }
            break
        }
        return lines.drop(idx).joinToString("\n")
    }

    /**
     * Normalizar la salida de LLM:
     *  - Quitar ```markdown / ``` valla
     *  - eliminar prefijo <think> bloquear
     *  - trim
     */
    fun normalizeLlmResult(value: String, source: String = ""): String {
        if (value.isEmpty()) return ""
        var s = value
        // ir ```markdown\n...\n``` valla
        val fenceMatch = FENCED_BLOCK_RE.find(s)
        if (fenceMatch != null) s = fenceMatch.groupValues[1]
        s = stripThinkTagBlocks(s)
        return s.trim()
    }

    /** Quitar <think>...</think> / <thinking>...</thinking> bloquear. */
    fun stripThinkTagBlocks(value: String): String {
        if (value.isEmpty()) return ""
        var s = value
        // bloque cerrado
        s = s.replace(THINK_BLOCK_RE, "")
        // Bloque restante sin cerrar al principio(Algunos modelos solo emiten <think> comienzo)
        s = s.replace(THINK_OPEN_TAIL_RE, "")
        return s
    }

    /** Comprueba si hay algún huérfano <think> etiqueta de apertura(Sin cierre). */
    fun hasDanglingLeadingThinkTag(value: String): Boolean = THINK_OPEN_RE.containsMatchIn(value) &&
        !THINK_CLOSE_RE.containsMatchIn(value)
}
