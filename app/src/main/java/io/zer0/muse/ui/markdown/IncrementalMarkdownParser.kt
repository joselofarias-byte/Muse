package io.zer0.muse.ui.markdown

/**
 * Analizador de fragmentos de Markdown incremental en streaming(A3 Elemento del plan).
 *
 * fondo: Transmisión de mensajes por ~80ms Agregar un fragmento de texto,MarkdownText Originalmente, la cantidad total de texto en cada cuadro era
 * (Crece a medida que los mensajes crecen hasta alcanzar miles de caracteres.)re [parseMarkdown]. Este caché del analizador se ha estabilizado.
 * bloque de encabezado,Vuelva a escanear solo la cola adjunta,y mantener los bloques sin cambios como la misma instancia——Compose Durante la reorganización
 * Se omitirán los elementos subcomponibles con parámetros sin cambios.,Reduciendo así el costo de la reorganización del hilo principal.
 *
 * estrategia incremental(base para la corrección — agregar número de línea = Número de saltos de línea en texto antiguo):
 *  - agregar puro(El texto nuevo tiene el prefijo del texto antiguo.)tiempo,Solo se vuelve a escanear el final de la línea después del punto de adición;
 *  - Si hay un bloque abierto al final(bloque de código de cerca/fórmula de varias líneas/mesa,Puede seguir añadiendo),
 *    Vuelva a escanear la fila inicial del bloque y descarte sus instancias antiguas.(Constante del punto inicial del bloque abierto <= Agregar línea de puntos);
 *  - Agregue la línea de puntos si existe antes"Encabezado de tabla potencial"(Contiene | de filas,Después de agregar separadores, se actualizará a una tabla.),
 *    Cobertura de fila de puntos adicional al volver a escanear——Agregue la línea de puntos que es la última línea del texto anterior.,Naturalmente contiene este encabezado potencial.;
 *  - Los bloques cerrados restantes conservan las instancias originales y las reutilizan directamente.(Caché de nodo estable).
 *
 * frontera: Token de transmisión agregado al eslogan de cierre(``` / $$)Inevitablemente se producirá un salto de línea después,Por lo tanto
 * "Agregar caracteres dentro del eslogan de cierre"Inalcanzable en transmisión real;Si la entrada se construye manualmente(Defensivo),
 * Este analizador recurre al análisis completo para garantizar la corrección.
 *
 * garantía semántica: Agregue cualquier texto varias veces con cualquier granularidad,parse Los resultados acumulados de == Monto total de una sola vez
 * [parseMarkdown] el resultado(Validado por MarkdownParserTest). El texto no es puro anexo.
 * (reemplazado/Cargando historial/Edición de contenido)Regrese automáticamente al análisis completo y restablezca el caché.
 */
class IncrementalMarkdownParser {
    private val stableBlocks = mutableListOf<MarkdownBlock>()
    private val blockStartLines = mutableListOf<Int>()
    private var openStartLine = -1
    private var lastFullText = ""

    /**
     * Analiza el texto actual. Las escenas transmitidas deben transmitir el texto completo cada vez.(Solo agregar la relación con la última vez),Sólo se volvió a barrer internamente la parte trasera.
     */
    fun parse(text: String): List<MarkdownBlock> {
        // El incremento solo se realiza cuando el contenido simplemente se agrega y el contenido cambia.;De lo contrario(texto vacío/reemplazar/historia/primer fotograma/sin cambios)Caché completo o multiplexado
        val isAppendable = text.isNotEmpty() &&
            text.length >= lastFullText.length &&
            text.startsWith(lastFullText) &&
            text != lastFullText
        if (isAppendable) {
            val reparseStart = resolveReparseStart(text)
            if (reparseStart >= 0) {
                return reparseFrom(reparseStart, text.split(MARKDOWN_LINE_SPLIT), text)
            }
        }
        return if (text == lastFullText) stableBlocks.toList() else fullParse(text)
    }

    /**
     * Calcule el número de línea inicial de la nueva exploración. Devolver -1 indica que es necesario revertir el monto total(dependencia directa de la tabla).
     *
     * punto de partida = Agregue la línea que contiene el primer carácter del contenido.(Posicionamiento del personaje,Evite la desalineación del número de línea vacía al final),
     * bloque abierto(valla/fórmula/El formulario no está cerrado.)Retrocede hasta su línea de partida si está presente.
     */
    private fun resolveReparseStart(text: String): Int {
        val appendPos = lastFullText.length
        val appended = text.substring(appendPos)
        val lines = text.split(MARKDOWN_LINE_SPLIT)
        // Cuando el contenido agregado comienza con una nueva línea,Esta nueva línea es solo un separador de línea.,La primera línea de contenido sigue
        val firstContentChar = if (appended.startsWith('\n')) appendPos + 1 else appendPos
        val lineStart = text.lastIndexOf('\n', firstContentChar - 1) + 1
        var reparseStart = text.substring(0, lineStart).count { it == '\n' }
        if (openStartLine >= 0) reparseStart = minOf(openStartLine, reparseStart)

        // dependencia directa de la tabla(líneas alternativas): Si la fila anterior al punto de inicio de la nueva exploración es un encabezado potencial/divisor(Contiene |),
        // Esta fila puede convertirse en un encabezado de tabla porque la siguiente fila se promociona a una línea separadora.,Volver a escanear el punto de partida no puede cubrirlo.
        // Las escenas de mesa son raras en el streaming,Devuelva el importe total para garantizar la corrección.;El texto normal no se ve afectado.
        if (reparseStart > 0 && lines[reparseStart - 1].contains('|')) {
            return -1
        }
        // El punto adicional cae dentro de la antigua última fila.(appendPos > lineStart)y la línea es una línea de marca de cierre(``` / $$)tiempo,
        // Volver a escanear directamente la línea se considerará erróneamente como una nueva marca abierta. → Retroceda hasta la línea inicial del bloque correspondiente y escanee nuevamente.
        // Punto de partida del bloque abierto(fence/Lanzamiento de fórmula)debe ser retenido,De lo contrario, se perderá el estado abierto.
        if (appendPos > lineStart && reparseStart < lines.size && isClosureMarker(lines[reparseStart])) {
            if (!(openStartLine >= 0 && reparseStart == openStartLine)) {
                reparseStart = blockStartLines.lastOrNull { it < reparseStart } ?: reparseStart
            }
        }
        return reparseStart
    }

    private fun fullParse(text: String): List<MarkdownBlock> {
        val lines = text.split(MARKDOWN_LINE_SPLIT)
        val result = parseMarkdownPass(lines, 0)
        stableBlocks.clear()
        stableBlocks.addAll(result.blocks)
        blockStartLines.clear()
        blockStartLines.addAll(result.blockStartLines)
        openStartLine = result.openStartLine
        lastFullText = text
        return result.blocks
    }

    private fun reparseFrom(startLine: Int, lines: List<String>, text: String): List<MarkdownBlock> {
        // descartar la línea de salida >= startLine de bloques viejos(bloque abierto/Encabezado de tabla potencial/Agregar línea de puntos),Mantenga el resto en la misma instancia.
        val firstDropped = blockStartLines.indexOfFirst { it >= startLine }
        if (firstDropped >= 0) {
            blockStartLines.subList(firstDropped, blockStartLines.size).clear()
            stableBlocks.subList(firstDropped, stableBlocks.size).clear()
        }
        val result = parseMarkdownPass(lines, startLine)
        stableBlocks.addAll(result.blocks)
        blockStartLines.addAll(result.blockStartLines)
        openStartLine = result.openStartLine
        lastFullText = text
        return stableBlocks.toList()
    }

    private fun isClosureMarker(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("```") || trimmed == "$$"
    }
}
