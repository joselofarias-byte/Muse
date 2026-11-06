package io.zer0.muse.ui.markdown

/**
 * MOOD Análisis segmentado de fragmentos.
 *
 * MOOD El contenido está naturalmente segmentado, de forma típica:
 *
 * ```
 * Vibe: Sentimientos en una frase
 * Sparks:
 *   - Tres asociaciones
 * Reflections:
 *   - Tres reflexiones
 * Will:
 *   - dos testamentos
 * ```
 *
 * Anteriormente, todo el párrafo se presentaba como texto sin formato.`-` La sangría y los saltos de línea colapsan en una sola pieza y se lee como un desastre.
 * Esto se divide en "secciones + elementos".UI Se puede escribir según la estructura.
 *
 * Compensaciones de diseño:
 *  - Sólo reconoce el comienzo de la línea. `etiqueta:` Forma, no adivines otras formas de escribir;
 *  - Cuando no hay etiqueta, el párrafo completo se devuelve como una sola sección sin perder el contenido;
 *  - El texto de la etiqueta se conserva tal cual (el vocabulario propio del modelo, no traducido).
 */
internal object MoodSectionParser {
    /** Título de la sección: Se permiten etiquetas en chino e inglés y dos puntos en chino e inglés. */
    private val HEADER = Regex(
        "^(Vibe|Sparks|Reflections|Will|estado de ánimo|inspiración|Reflexión|voluntad)\\s*[::]\\s*(.*)$",
        RegexOption.IGNORE_CASE,
    )

    private val BULLET_PREFIXES = listOf("-", "•", "*", "·")

    internal data class Section(
        val title: String?,
        val lines: List<String>,
    )

    /** Entradas: distinguir entre párrafos ordinarios y elementos de lista,UI Decide si quieres sacar puntos en función de esto. */
    internal data class Line(val text: String, val bullet: Boolean)

    fun parse(raw: String): List<Section> {
        if (raw.isBlank()) return emptyList()

        val sections = mutableListOf<Section>()
        var currentTitle: String? = null
        var currentLines = mutableListOf<String>()

        fun flush() {
            if (currentTitle != null || currentLines.any { it.isNotBlank() }) {
                sections += Section(currentTitle, currentLines.toList())
            }
            currentTitle = null
            currentLines = mutableListOf()
        }

        for (line in raw.lineSequence()) {
            val header = HEADER.matchEntire(line.trim())
            if (header != null) {
                flush()
                currentTitle = header.groupValues[1]
                val inline = header.groupValues[2].trim()
                if (inline.isNotEmpty()) currentLines += inline
            } else {
                currentLines += line.trimEnd()
            }
        }
        flush()

        // Elimine las líneas en blanco al principio y al final de cada sección para evitar espacios en blanco excesivos en la parte superior e inferior cuando se expande el estado plegado.
        return sections.map { section ->
            section.copy(lines = section.lines.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() })
        }
    }

    /** Determine si una línea es un elemento de lista o un párrafo. */
    fun toLine(text: String): Line {
        val trimmed = text.trimStart()
        val bullet = BULLET_PREFIXES.any { trimmed.startsWith(it) }
        val content = if (bullet) {
            trimmed.drop(1).trim()
        } else {
            trimmed
        }
        return Line(content, bullet)
    }
}
