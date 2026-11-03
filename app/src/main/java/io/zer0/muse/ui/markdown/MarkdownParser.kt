package io.zer0.muse.ui.markdown

/**
 * Markdown elementos a nivel de bloque AST(Autoimplementación ligera,No se introducen bibliotecas de terceros.).
 *
 * Tipos de nivel de bloque admitidos:
 *  - [MarkdownBlock.Paragraph]: párrafo(Contiene formato en línea:Negrita/cursiva/código en línea/enlace)
 *  - [MarkdownBlock.Heading]: Título # ## ###
 *  - [MarkdownBlock.CodeBlock]: bloque de código de cerca ```...```
 *  - [MarkdownBlock.ListItem]: elemento de la lista(Ordenado 1. / Sin ordenar - o *)
 *  - [MarkdownBlock.Quote]: Cotización >
 *  - [MarkdownBlock.Divider]: linea horizontal ---
 *  - [MarkdownBlock.Blank]: linea en blanco(para el espaciado de párrafos)
 *  - [MarkdownBlock.Formula]: Phase 8.6 LaTeX bloque de fórmulas $$...$$
 *  - [MarkdownBlock.Table]: v1.97 Markdown mesa | a | b |
 */
sealed class MarkdownBlock {
    /** párrafo(Una o varias líneas de texto,El formato en línea consiste en [parseInline] Proceso). */
    data class Paragraph(val text: String) : MarkdownBlock()

    /** título.level 1-6 Corresponde a # a ######. */
    data class Heading(val level: Int, val text: String) : MarkdownBlock()

    /** Bloques de código de valla.language Disponible(Sin anotaciones de idioma). */
    data class CodeBlock(val language: String?, val code: String) : MarkdownBlock()

    /** Listar elementos.ordered=true ordenado,index es el numero de serie;false trastorno. */
    data class ListItem(val ordered: Boolean, val index: Int, val text: String) : MarkdownBlock()

    /** Bloque de cotización.text Eliminado > prefijo. */
    data class Quote(val text: String) : MarkdownBlock()

    /** Phase 8.6: LaTeX Bloque de fórmulas.latex si $$ ... $$ contenido entre(sin delimitador). */
    data class Formula(val latex: String) : MarkdownBlock()

    /** v1.97: Markdown mesa.headers para la fila del encabezado,rows es la fila de datos. */
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock()

    /** Divisores horizontales. */
    object Divider : MarkdownBlock()

    /** linea en blanco(espaciado de párrafo). */
    object Blank : MarkdownBlock()
}

// H-MD2 Reparación: Todas las expresiones regulares se promueven al nivel de archivo. private val,Evite la compilación repetida de parseMarkdown para cada línea
private val FENCE_REGEX = Regex("^```(.*)$")
private val HEADING_REGEX = Regex("^(#{1,6})\\s+(.+)$")
private val ORDERED_LIST_REGEX = Regex("^(\\d+)\\.\\s+(.+)$")
private val UNORDERED_LIST_REGEX = Regex("^[-*]\\s+(.+)$")

// v1.97: Reglas gramaticales de imágenes — ![](url) y ![alt](url),La imagen se ha renderizado a través de displayImageUris.,saltar en el texto
private val IMAGE_SYNTAX_REGEX = Regex("!\\[[^\\]]*\\]\\([^)]+\\)")

/** v1.97: Analizar filas de la tabla,Devuelve el contenido de cada celda.;Devuelve nulo para filas que no son de tabla. */
private fun parseTableRow(line: String): List<String>? {
    val trimmed = line.trim()
    if (!trimmed.contains("|")) return null
    val content = trimmed.trimStart('|').trimEnd('|')
    if (content.isBlank()) return null
    return content.split("|").map { it.trim() }
}

/** v1.97: Determinar si es una línea separadora de tabla(|---|---| o |:---:|---:|). */
private fun isTableSeparator(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.contains("|")) return false
    val content = trimmed.trimStart('|').trimEnd('|')
    if (content.isBlank()) return false
    return content.split("|").all { cell ->
        val c = cell.trim()
        c.isNotEmpty() && c.all { it == '-' || it == ':' }
    }
}

/**
 * Analiza el texto de Markdown en una lista de elementos a nivel de bloque.
 *
 * Estrategia: escaneo progresivo,Identificar bloques de código de cerca(```...```)máxima prioridad,
 *        El resto determina el tipo según el primer símbolo de la línea.,Las líneas en blanco están reservadas para espacios en blanco para separar párrafos.
 *
 * Phase 8.6: apoyo $$...$$ bloque de fórmulas(Líneas únicas o múltiples,a $$ es el delimitador de apertura y cierre).
 *
 * Sintaxis no compatible(mesa/Nota al pie/fotos)Imprima el párrafo tal como está.
 */
fun parseMarkdown(text: String): List<MarkdownBlock> {
    val lines = text.split(MARKDOWN_LINE_SPLIT)
    return parseMarkdownPass(lines, 0).blocks
}

/** L10 Reparación: división de nueva línea(compatibles Windows \r\n con el viejo Mac \r),cantidad total/El uso compartido incremental garantiza números de línea consistentes. */
internal val MARKDOWN_LINE_SPLIT = Regex("\\r?\\n")

/**
 * Resultados intermedios de un solo paso de análisis.
 *
 * @param blocks de [startLine] Comience con los trozos analizados.
 * @param blockStartLines con [blocks] Número de línea inicial correspondiente uno a uno de cada bloque(texto relativamente completo,Truncamiento incremental).
 * @param openStartLine El texto termina estando aún en"abierto"bloquear la línea de inicio para el estado;Ningún bloque abierto es -1.
 *        bloque abierto = Bloques de código de valla/fórmulas/tablas de varias líneas —— Estos bloqueos pueden continuar durante las transmisiones adjuntas.,
 *        El análisis incremental debe volver a escanearse desde esta línea(ver [IncrementalMarkdownParser]).
 */
internal data class ParsePassResult(
    val blocks: List<MarkdownBlock>,
    val blockStartLines: List<Int>,
    val openStartLine: Int,
)

/**
 * de [startLine] Análisis de un solo paso [lines],Devuelve la lista de bloques, la línea de inicio bloque por bloque y el inicio del bloque abierto.
 *
 * A3: Suministro corporal de circulación central. [parseMarkdown](cantidad total)con [IncrementalMarkdownParser](incremento)compartir,
 * Asegúrese de que la semántica de los dos sea coherente. bloque abierto(valla/fórmula de varias líneas/mesa)Registrar la línea de inicio mientras el final del texto aún no está cerrado,
 * Analiza incrementalmente el siguiente fotograma y vuelve a escanear desde esta línea;bloques restantes de una sola línea(párrafo/Título/lista/Cotizaciones, etc.)Naturalmente estable,Se puede reutilizar.
 */
internal fun parseMarkdownPass(lines: List<String>, startLine: Int): ParsePassResult {
    val blocks = mutableListOf<MarkdownBlock>()
    val blockStartLines = mutableListOf<Int>()
    var openStartLine = -1
    var i = startLine
    var orderedIndex = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // ── bloque de código de cerca ────────────────────────────────────────────────
        val fenceMatch = FENCE_REGEX.matchEntire(trimmed)
        if (fenceMatch != null) {
            val fenceStart = i
            val language = fenceMatch.groupValues[1].trim().ifBlank { null }
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            // foundCloser: while debido al cierre ``` y salir(i todavía dentro de los límites);Si se agota y sale, no se cierra.
            // Tenga en cuenta que no se puede utilizar i >= lines.size Determinado no cerrado——También pasaré del final cuando la línea de cierre sea exactamente la última línea.
            val foundCloser = i < lines.size
            // i Punto para cerrar ```(si existe),saltar
            if (foundCloser) i++
            blocks.add(MarkdownBlock.CodeBlock(language, codeLines.joinToString("\n")))
            blockStartLines.add(fenceStart)
            // No se encontró ningún cierre ``` marca de tiempo abierta,La transmisión de datos adjuntos requiere volver a escanearse desde vallaStart
            if (!foundCloser) openStartLine = fenceStart
            orderedIndex = 0
            continue
        }

        // ── Phase 8.6: bloque de fórmulas $$...$$ ─────────────────────────────────
        // fórmula de una sola línea: $$ E = mc^2 $$ en la misma linea
        // fórmula de varias líneas: $$ empezar,líneas siguientes hasta $$ fin
        // M2 Reparación: Los bloques de fórmulas empiezan a juzgarse de forma más estricta,evitar $$ texto plano para prefijo(Como por ejemplo "$$ precio","$100")Identificado erróneamente como una fórmula.
        // lógica de decisión: Sólo si toda la línea resulta ser "$$",o cierre de pares(a $$ comenzando con $$ final y longitud>4,Como por ejemplo "$$ E=mc^2 $$")
        // Solo entonces ingresa al bloque de fórmulas.;simplemente $$ El texto normal al principio se procesa como párrafos.,No más tragar los párrafos siguientes.
        val isFormulaBlock = trimmed == "$$" ||
            (trimmed.startsWith("$$") && trimmed.endsWith("$$") && trimmed.length > 4)
        if (isFormulaBlock) {
            val formulaStart = i
            val afterOpen = trimmed.removePrefix("$$")
            // cierre de pares: $$ E = mc^2 $$
            if (afterOpen.contains("$$")) {
                val latex = afterOpen.substringBefore("$$").trim()
                if (latex.isNotEmpty()) {
                    blocks.add(MarkdownBlock.Formula(latex))
                    blockStartLines.add(formulaStart)
                    orderedIndex = 0
                    i++
                    continue
                }
            }
            // múltiples líneas: $$ empezar,hasta el proximo $$ de filas
            val formulaLines = mutableListOf<String>()
            // Dentro de la industria $$ El contenido después del(Si no está vacío)
            val firstLine = afterOpen.trim()
            if (firstLine.isNotEmpty()) formulaLines.add(firstLine)
            i++
            var closed = false
            while (i < lines.size) {
                val fl = lines[i]
                if (fl.trim().contains("$$")) {
                    val beforeClose = fl.substringBefore("$$").trim()
                    if (beforeClose.isNotEmpty()) formulaLines.add(beforeClose)
                    closed = true
                    i++
                    break
                }
                formulaLines.add(fl)
                i++
            }
            if (formulaLines.isNotEmpty()) {
                blocks.add(MarkdownBlock.Formula(formulaLines.joinToString("\n").trim()))
                blockStartLines.add(formulaStart)
            }
            if (!closed && i < lines.size) i++ // tolerancia a fallas:no cerrado $$ también salta
            // No se encontró ningún cierre $$ marca de tiempo abierta,La transmisión adjunta requiere volver a escanear desde formulaStart
            if (!closed) openStartLine = formulaStart
            orderedIndex = 0
            continue
        }

        // H-MD2 Reparación: La expresión regular ha sido promovida al nivel de archivo. private val,Cotización directa aquí
        val headingMatch = HEADING_REGEX.matchEntire(trimmed)
        val orderedListMatch = ORDERED_LIST_REGEX.matchEntire(trimmed)
        val unorderedListMatch = UNORDERED_LIST_REGEX.matchEntire(trimmed)

        // v1.97: Reconocimiento de mesa — La línea actual contiene | y la siguiente línea es la línea separadora
        val tableHeaders = parseTableRow(line)
        if (tableHeaders != null && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
            val tableStart = i
            val rows = mutableListOf<List<String>>()
            i += 2
            while (i < lines.size) {
                val row = parseTableRow(lines[i])
                if (row != null && row.size == tableHeaders.size) {
                    rows.add(row)
                    i++
                } else {
                    break
                }
            }
            blocks.add(MarkdownBlock.Table(tableHeaders, rows))
            blockStartLines.add(tableStart)
            // El texto termina todavía en la fila de datos de la tabla.(Puede seguir añadiendo)marca de tiempo abierta
            if (i >= lines.size) openStartLine = tableStart
            orderedIndex = 0
            continue
        }

        when {
            // ── linea en blanco ──────────────────────────────────────────────────
            trimmed.isEmpty() -> {
                blocks.add(MarkdownBlock.Blank)
                blockStartLines.add(i)
                orderedIndex = 0
            }
            // ── linea horizontal ────────────────────────────────────────────────
            trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                blocks.add(MarkdownBlock.Divider)
                blockStartLines.add(i)
                orderedIndex = 0
            }
            // ── Título # a ###### ─────────────────────────────────────
            headingMatch != null -> {
                val level = headingMatch.groupValues[1].length
                val content = headingMatch.groupValues[2].trim()
                blocks.add(MarkdownBlock.Heading(level, content))
                blockStartLines.add(i)
                orderedIndex = 0
            }
            // ── Cotización > ────────────────────────────────────────────────
            // L-MD13 Reparación: usar trimStart { it == '>' } Manejar referencias anidadas(>> >>> espera),
            // Originalmente removePrefix(">") solo ve a uno >,Las referencias anidadas permanecen > prefijo
            trimmed.startsWith(">") -> {
                val content = trimmed.trimStart { it == '>' }.trim()
                blocks.add(MarkdownBlock.Quote(content))
                blockStartLines.add(i)
                orderedIndex = 0
            }
            // ── lista ordenada 1. 2. ────────────────────────────────────────
            orderedListMatch != null -> {
                val idx = orderedListMatch.groupValues[1].toIntOrNull() ?: (++orderedIndex)
                if (idx == 1) orderedIndex = 1 else orderedIndex = idx
                val content = orderedListMatch.groupValues[2].trim()
                blocks.add(MarkdownBlock.ListItem(ordered = true, index = idx, text = content))
                blockStartLines.add(i)
            }
            // ── lista desordenada - o * ───────────────────────────────────────
            unorderedListMatch != null -> {
                val content = unorderedListMatch.groupValues[1].trim()
                blocks.add(MarkdownBlock.ListItem(ordered = false, index = 0, text = content))
                blockStartLines.add(i)
                orderedIndex = 0
            }
            // ── párrafo(Predeterminado) ──────────────────────────────────────────
            else -> {
                // v1.97: Saltar la sintaxis de la imagen de rebajas ![](url),La imagen se ha renderizado a través de displayImageUris.
                val cleaned = IMAGE_SYNTAX_REGEX.replace(trimmed, "").trim()
                if (cleaned.isNotEmpty()) {
                    blocks.add(MarkdownBlock.Paragraph(cleaned))
                    blockStartLines.add(i)
                }
                orderedIndex = 0
            }
        }
        i++
    }

    return ParsePassResult(blocks, blockStartLines, openStartLine)
}
