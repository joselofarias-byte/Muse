package io.zer0.muse.ui.markdown

/**
 * Truncamiento de vista previa de Markdown según la estructura.
 *
 * Antecedentes (auditoría §11-9): La vista previa de la tarjeta de colección fue anteriormente `body.take(280)` —— Puros cortes a nivel de personaje.
 * Hay dos tipos de consecuencias, las cuales son causadas por la capa de representación y la capa de datos no puede verlas:
 *  - cortar sin cerrar ``` En la valla de código: la valla nunca se cierra y el contenido posterior se convierte en un bloque de código en su conjunto;
 *  - Cortar `**`,`*`,`_`,`[N]` Este tipo de marca emparejada está en el medio: la marca está medio encerrada en el texto,
 *    A veces se convierte en un asterisco literal y, a veces, todo el texto está en negrita./enlace.
 *
 * El enfoque aquí es de dos niveles:
 *  1. **Agregado por bloque**: Primero presione la línea en blanco para cortar en segmentos, acumule segmento por segmento y deténgase cuando esté cerca del límite superior. Reserva de bloque completo significa
 *     Las listas, referencias, tablas y bloques de código generalmente están completos y no aparecerán."media mesa".
 *  2. **Reparación de cola**: Solo corte con fuerza en el límite superior cuando la pieza individual sea demasiado larga y hágalo nuevamente después de cortar."estructura abierta"
 *     Corrección (vallas/código en línea/énfasis emparejado/comillas entre corchetes), preferiría mostrar algunas palabras menos,
 *     Tampoco da medias notas al renderizador.
 *
 * Función pura, no dependiente de Android, fácil de realizar pruebas individuales.
 */
object MarkdownPreviewTruncation {

    /** Longitud máxima para vistas previas de tarjetas de colección. */
    const val DEFAULT_LIMIT: Int = 280

    /** Elipsis. Ponlo aquí en lugar de la persona que llama, garantizado."Truncar"y"marca de truncamiento"Siempre presente al mismo tiempo o no al mismo tiempo. */
    const val ELLIPSIS: String = "…"

    /** Marcadores de viaje de la cerca del código:``` o ~~~. */
    private const val FENCE_MARK: String = "```"

    /** Elimina las marcas de la cola en la selección del círculo. El orden es muy particular: largo primero y luego corto para evitar ** ser soltero * Procesar con antelación. */
    private val INLINE_MARKERS = listOf("**", "__", "*", "_", "[")

    /**
     * poner [text] corte no más de [limit] Vista previa de personajes sin cortar la estructura Markdown.
     *
     * - El texto original no excede [limit]:Devolver tal cual (sin puntos suspensivos)
     * - Extra largo: volver `prefijo + [ELLIPSIS]`
     * - El resultado no estará dentro de los límites de código abierto/código en línea/caracteres de énfasis/mitad `[N]` medio
     */
    fun preview(text: String, limit: Int = DEFAULT_LIMIT): String {
        val safeLimit = limit.coerceAtLeast(1)
        return if (text.length <= safeLimit) text else truncated(text, safeLimit)
    }

    private fun truncated(text: String, limit: Int): String = repairOpenConstructs(collectBlocks(text, limit)).trimEnd() + ELLIPSIS

    /** Acumule en bloques por líneas en blanco, no devuelva más de [limit] El prefijo del bloque completo. La primera pieza degeneró en un corte duro cuando era demasiado larga. */
    private fun collectBlocks(text: String, limit: Int): String {
        val blocks = mutableListOf<String>()
        val current = StringBuilder()
        text.split('\n').forEach { line ->
            if (line.isBlank() && current.isNotEmpty()) {
                blocks += current.toString()
                current.setLength(0)
            } else if (line.isNotBlank()) {
                if (current.isNotEmpty()) current.append('\n')
                current.append(line)
            }
        }
        if (current.isNotEmpty()) blocks += current.toString()
        if (blocks.isEmpty()) return text

        val first = blocks.first()
        return if (first.length > limit) {
            hardCut(first, limit)
        } else {
            blocks.drop(1).fold(first) { acc, block ->
                if (acc.length + 2 + block.length <= limit) "$acc\n\n$block" else acc
            }
        }
    }

    /** corte duro para [limit] Caracteres, intente caer en el límite del salto de línea (especialmente obvio en las filas de la tabla y los elementos de la lista). */
    private fun hardCut(text: String, limit: Int): String {
        val raw = limit.coerceAtMost(text.length)
        val lastNewline = text.lastIndexOf('\n', raw - 1)
        val cut = if (lastNewline > limit / 2) lastNewline else raw
        return text.substring(0, cut)
    }

    /**
     * Después de reparar y cortar"estructura abierta", para que el renderizador siempre obtenga fragmentos autoconsistentes.
     *
     * Primero corta la valla del código. —— dentro de la valla `**`,`[` Todos son valores literales y no deben enviarse a comprobaciones en línea posteriores.
     */
    private fun repairOpenConstructs(text: String): String {
        val withoutFence = closeCodeFence(text)
        val withoutInlineCode = dropUnbalancedTail(withoutFence, "`")
        return INLINE_MARKERS.fold(withoutInlineCode) { acc, marker -> dropUnbalancedTail(acc, marker) }
    }

    /**
     * La valla codificada no está cerrada: corte toda la sección de la última valla abierta no cerrada.
     *
     * elegir"cortar"en lugar de"inventa uno ``` Refinamiento": Al parchear la valla, la última mitad del código en la vista previa se considerará como
     * Los bloques de código legal se representan para parecerse al contenido mismo; cortarlo es más honesto, han dicho los puntos suspensivos
     * El usuario tiene más que decir.
     */
    private fun closeCodeFence(text: String): String {
        var openIndex = -1
        var offset = 0
        text.split('\n').forEach { line ->
            if (line.trimStart().startsWith(FENCE_MARK) || line.trimStart().startsWith("~~~")) {
                openIndex = if (openIndex < 0) offset else -1
            }
            offset += line.length + 1
        }
        return if (openIndex >= 0) text.substring(0, openIndex) else text
    }

    /**
     * corta el ultimo"marca de pedido"Lo que sigue.
     *
     * Las marcas de acento grave en el código en línea se cuentan encima del texto original (la máscara las cubrirá) y otras etiquetas se cuentan encima de la cadena enmascarada. ——
     * La cadena de máscara tiene la misma longitud que el texto original y el subíndice se puede utilizar directamente para cortar el texto original.
     */
    private fun dropUnbalancedTail(text: String, marker: String): String {
        val masked = if (marker == "`") text else maskCodeAndEscapes(text)
        val cut = unbalancedIndex(masked, marker)
        return if (cut > 0) text.substring(0, cut) else text
    }

    /** El índice inicial del marcador no emparejado;-1 Indica no.`[` prensa"¿Hay algo más tarde? `]`"juez. */
    private fun unbalancedIndex(masked: String, marker: String): Int {
        if (marker == "[") {
            val open = masked.lastIndexOf('[')
            return if (open >= 0 && masked.indexOf(']', open) < 0) open else -1
        }
        val positions = mutableListOf<Int>()
        var index = masked.indexOf(marker)
        while (index >= 0) {
            positions += index
            index = masked.indexOf(marker, index + marker.length)
        }
        return if (positions.size % 2 == 0) -1 else positions.last()
    }

    /**
     * Reemplace los caracteres de escape y los códigos en línea con espacios de igual longitud para garantizar que los subíndices estén alineados con el texto original.
     *
     * Si no se elimina el enmascaramiento, el segmento de código `*` Puede interferir con el recuento de pares y provocar un truncamiento excesivo.
     */
    private fun maskCodeAndEscapes(text: String): String {
        val chars = text.toCharArray()
        var i = 0
        while (i < chars.size) {
            i += when {
                chars[i] == '\\' && i + 1 < chars.size -> {
                    chars[i] = ' '
                    chars[i + 1] = ' '
                    2
                }
                chars[i] == '`' -> maskCodeSpan(text, chars, i)
                else -> 1
            }
        }
        return String(chars)
    }

    /** cubrir de [start] El comienzo de un fragmento de código en línea entre comillas invertidas devuelve la longitud del paso. */
    private fun maskCodeSpan(text: String, chars: CharArray, start: Int): Int {
        var end = start
        while (end < chars.size && chars[end] == '`') end++
        val runLength = end - start
        val closing = text.indexOf("`".repeat(runLength), end)
        val stop = (if (closing >= 0) closing + runLength else end).coerceAtMost(chars.size)
        for (j in start until stop) chars[j] = ' '
        return stop - start
    }
}
