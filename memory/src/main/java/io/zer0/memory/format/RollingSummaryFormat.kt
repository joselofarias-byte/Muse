package io.zer0.memory.format

// NOTE-i18n: section Los títulos participan en la comparación de resultados de LLM como contratos de análisis de rebajas,Solo se puede extraer después de que se separen el contrato y la redacción y se cambie la estructura.

/**
 * Contrato en formato resumen rodante de fuente única.
 *
 * El resumen debe contener dos párrafos de título: Hechos importantes(Key Facts) + que paso(Timeline).
 * Esta es la base contractual para que compileFacts extraiga hechos——Cualquier resumen que rompa esta estructura se considera mal formado.
 *
 * Usuario:
 *  - [io.zer0.memory.summary.SessionSummaryManager](Resumen de salida)
 *  - [io.zer0.memory.compile.MemoryCompiler](segmento de datos de consumo)
 *  - [io.zer0.memory.prompt.RollingSummaryPrompt](prompt Plantilla)
 */
object RollingSummaryFormat {

    /** facts Título de la sección(Cualquier nivel 1-6,No distingue entre mayúsculas y minúsculas). subíndice 0=chino, 1=Inglés. */
    val FACT_SECTION_TITLES = listOf("hechos importantes", "Key Facts")

    /** timeline Título de la sección. subíndice 0=chino, 1=Inglés. */
    val TIMELINE_SECTION_TITLES = listOf("que paso", "Timeline")

    /** Número máximo de reintentos para la reparación del formato(Si excede, se arrojará un error.,La persona que llama toma el canal de falla.). */
    const val MAX_REPAIRS = 1

    // 2026-11-01 Mejoras de rendimiento:Estas regularidades se construyeron originalmente en el lugar de la función.
    // parseMarkdownHeading si**por fila**llamado (extractMarkdownSection / hasFactSectionHeading /
    // locateHeading / CompiledMemoryState.stripLeadingHeadings Todos son bucles línea por línea),
    // Entonces, cada línea necesita crear 2 nuevos java.util.regex.Pattern.memory.md Con miles de filas esto es puro desperdicio.
    private val HEADING_RE = Regex("^(#{1,6})[ \\t]+(.+?)[ \\t]*$")
    private val TRAILING_HASHES_RE = Regex("[ \\t]+#+[ \\t]*$")
    private val LINE_BREAK_RE = Regex("\\r?\\n")
    private val BULLET_PREFIX_RE = Regex("^[-*+][ \\t]+")

    private fun isZh(locale: String): Boolean = locale.let { it.startsWith("es") || it.startsWith("zh") }

    /** Obtenga el texto del título de la sección de hechos por configuración regional. */
    fun getFactSectionTitle(locale: String = "es"): String = if (isZh(locale)) FACT_SECTION_TITLES[0] else FACT_SECTION_TITLES[1]

    /** Obtenga el texto del título de la sección de la línea de tiempo por configuración regional. */
    fun getTimelineSectionTitle(locale: String = "es"): String =
        if (isZh(locale)) TIMELINE_SECTION_TITLES[0] else TIMELINE_SECTION_TITLES[1]

    /** prompt en"Requisitos de formato de salida"Copiar bloque. */
    fun buildFormatRequirements(locale: String = "es"): String {
        if (!isZh(locale)) {
            return """
## Output Format
The final answer must contain exactly two third-level headings, in fixed order:
1. The first line must be `### Key Facts`
2. The second heading must be `### Timeline`

Use unordered lists under both headings; each item must start with `- `.
If a section has no content, write one item: `- None`.
Do not output any preamble, conclusion, XML tags, or code fences outside those headings.
            """.trimIndent()
        }
        return """
## Formato de salida
La respuesta final debe contener sólo dos títulos de tercer nivel, con el texto y el orden del título fijos:
1. La primera línea debe ser `### hechos importantes`
2. El segundo título debe ser `### que paso`

El texto bajo ambos encabezados debe usar una lista desordenada y los elementos de la lista deben comenzar con `- ` Comienzo.
Si una sección no tiene contenido, también genere un elemento de lista:`- Ninguno`.
Además del título, no publique el prefacio, la posdata,XML etiqueta o bloque de código.
        """.trimIndent()
    }

    /** Directiva de sistema estable llamada por corrección de formato. */
    fun buildRepairPrompt(locale: String = "es"): String {
        val requirements = buildFormatRequirements(locale)
        if (!isZh(locale)) {
            return """
You are the format repairer for rolling summaries. The previous draft violates the required fixed structure and cannot be parsed. Rearrange the information in the draft into the required structure: do not add, remove, or rewrite factual content, do not explain, and output only the full repaired summary.

$requirements
            """.trimIndent()
        }
        return """
Usted es el reparador de formato para los resúmenes desplazables. El borrador del resumen generado en el paso anterior no se ajusta a la estructura requerida y no se puede analizar. Reorganice la información del borrador proporcionado en la estructura prescrita tal como está: no agregue, elimine ni reescriba el contenido fáctico, no explique y genere directamente el texto completo del resumen reparado.

$requirements
        """.trimIndent()
    }

    /** Entrada dinámica para llamadas de corrección de formato: motivo del error + borrador a corregir. */
    fun buildRepairInput(locale: String = "es", issues: List<String> = emptyList(), summaryText: String = ""): String {
        val zh = isZh(locale)
        val issuesLabel = if (zh) "## Motivo del error de verificación" else "## Validation Failures"
        val draftLabel = if (zh) "## Borrador por arreglar" else "## Draft To Repair"
        val issueLines = issues
            .map { "- ${it.trim()}" }
            .filter { it != "- " }
            .joinToString("\n")
            .ifEmpty { if (zh) "- desconocido" else "- unknown" }

        return "$issuesLabel\n\n$issueLines\n\n$draftLabel\n\n<draft-summary>\n$summaryText\n</draft-summary>"
    }

    /** Analizar líneas de encabezado de rebajas(1-6 nivel). */
    data class Heading(val level: Int, val title: String)

    fun parseMarkdownHeading(line: String): Heading? {
        val match = HEADING_RE.find(line) ?: return null
        val title = match.groupValues[2].replace(TRAILING_HASHES_RE, "").trim()
        return Heading(match.groupValues[1].length, title)
    }

    private fun normalizeTitle(title: String): String = title.trim().lowercase()

    /** Extraiga el texto del párrafo del título del primer resultado en rebajas(al siguiente hermano o título superior). */
    fun extractMarkdownSection(markdown: String, titles: List<String>): String {
        if (markdown.isEmpty()) return ""
        val wanted = titles.map(::normalizeTitle).toHashSet()
        val lines = markdown.split(LINE_BREAK_RE)

        for (i in lines.indices) {
            val heading = parseMarkdownHeading(lines[i]) ?: continue
            if (normalizeTitle(heading.title) !in wanted) continue

            val body = mutableListOf<String>()
            for (j in (i + 1) until lines.size) {
                val next = parseMarkdownHeading(lines[j])
                if (next != null && next.level <= heading.level) break
                body.add(lines[j])
            }
            return body.joinToString("\n").trim()
        }
        return ""
    }

    /** Si hay un título de sección de hechos en el resumen(No requiere que el texto no esté vacío.). */
    fun hasFactSectionHeading(markdown: String): Boolean {
        if (markdown.isEmpty()) return false
        val wanted = FACT_SECTION_TITLES.map(::normalizeTitle).toHashSet()
        for (line in markdown.split(LINE_BREAK_RE)) {
            val heading = parseMarkdownHeading(line) ?: continue
            if (normalizeTitle(heading.title) in wanted) return true
        }
        return false
    }

    /** Extraiga la sección de hechos del resumen. */
    fun extractFactSection(markdown: String): String = extractMarkdownSection(markdown, FACT_SECTION_TITLES)

    /** facts Si el cuerpo del párrafo es una marca vacía explícita(- Ninguno / - None). */
    fun isEmptyFactSection(text: String): Boolean {
        val lines = text.split(LINE_BREAK_RE)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return true
        return lines.all { line ->
            val itemText = line.replace(BULLET_PREFIX_RE, "").trim().lowercase()
            itemText == "Ninguno" || itemText == "none"
        }
    }

    private fun locateHeading(lines: List<String>, titles: List<String>): Pair<Int, Int>? {
        val wanted = titles.map(::normalizeTitle).toHashSet()
        for (i in lines.indices) {
            val heading = parseMarkdownHeading(lines[i]) ?: continue
            if (normalizeTitle(heading.title) in wanted) return i to heading.level
        }
        return null
    }

    data class ValidationResult(val ok: Boolean, val issues: List<String>)

    /**
     * Verificación de estructura antes de escribir, interceptando cuatro tipos de problemas que socavan los supuestos de extracción de compileFacts:
     *  1. Título de la sección de hechos faltantes
     *  2. Falta el título de la sección de la línea de tiempo
     *  3. timeline El título es más profundo que y después del título de los hechos.(facts El párrafo no puede concluirse.)
     *  4. facts El texto del párrafo está vacío.(Escritura explícita cuando el contrato requiere nulo: ninguno / - None)
     */
    fun validate(text: String): ValidationResult {
        val issues = mutableListOf<String>()
        val lines = text.split(LINE_BREAK_RE)

        val fact = locateHeading(lines, FACT_SECTION_TITLES)
        val timeline = locateHeading(lines, TIMELINE_SECTION_TITLES)

        if (fact == null) issues += """missing fact section heading ("### hechos importantes" / "### Key Facts")"""
        if (timeline == null) issues += """missing timeline section heading ("### que paso" / "### Timeline")"""
        if (fact != null && timeline != null && timeline.first > fact.first && timeline.second > fact.second) {
            issues += "timeline heading is nested deeper than the fact heading, so the fact section cannot be delimited"
        }
        if (fact != null) {
            val body = extractFactSection(text)
            if (body.isEmpty()) {
                issues += """fact section body is empty; write "- Ninguno" / "- None" when there are no facts"""
            }
        }
        return ValidationResult(issues.isEmpty(), issues)
    }
}
