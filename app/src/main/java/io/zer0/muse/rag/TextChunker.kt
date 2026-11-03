package io.zer0.muse.rag

/**
 * v1.54: Fragmentador recursivo de texto.
 *
 * v1.133 Expandir:
 *  - [markdownAware]:identificar ``` Bloque de código/nivel de encabezado de Markdown/fila de tabla,Mantener el conjunto sin cortar.
 *  - [chunkByToken]:Dividir en trozos por número de ficha(número de caracteres de sustitución,Mezclar chino e inglés es más preciso)
 *
 * estrategia de fragmentación(Tres niveles de recursividad):
 * 1. dividido por párrafo(doble nueva línea \n\n dividir)
 * 2. Si el párrafo es demasiado largo, divídelo en oraciones.(periodo chino/signo de interrogación/Signo de exclamación + inglés .!?)
 * 3. Si la frase aún es demasiado larga, córtala por caracteres.
 * 4. Fusionar pequeños bloques adyacentes para cerrar [targetSize]
 * 5. Reservado entre bloques adyacentes [overlap] Superposición de personajes(continuidad semántica)
 *
 * @param targetSize Tamaño del bloque objetivo([chunkByToken]=true el tiempo es el número de fichas,De lo contrario, el número de caracteres,Predeterminado 500)
 * @param overlap Los bloques adyacentes se superponen(Igual que la unidad targetSize,Predeterminado 50)
 * @param markdownAware v1.133: Ya sea para comprender la estructura de Markdown(bloque de código/Título/Mantener la mesa entera.)
 * @param chunkByToken v1.133: Ya sea para dividir en trozos por número simbólico(true cuando la unidad targetSize es token)
 */
class TextChunker(
    private val targetSize: Int = 500,
    private val overlap: Int = 50,
    private val markdownAware: Boolean = false,
    private val chunkByToken: Boolean = false,
) {
    data class Chunk(
        val content: String,
        val index: Int,
        /** v1.133: chunk Metadatos(Puede incluir sección/página/etiquetas, etc.,para filtrar). */
        val metadata: Map<String, String> = emptyMap(),
    )

    companion object {
        private val sentenceEnd = Regex("(?<=[. ! ?!?])|(?<=[.!?])\\s+")

        /** ``` Reconocimiento de límites de bloques de código(abierto/par cerrado). */
        private val codeFence = Regex("^```\\w*\\s*$", RegexOption.MULTILINE)

        /** Markdown línea de título(# ~ ######). */
        private val mdHeader = Regex("^(#{1,6})\\s+(.+)$", RegexOption.MULTILINE)

        /** Markdown fila de la tabla(|...|...|). */
        private val mdTableRow = Regex("^\\|.*\\|\\s*$", RegexOption.MULTILINE)
    }

    /**
     * Divida el texto largo en varios fragmentos.
     * El texto vacío devuelve una lista vacía;El texto breve vuelve directamente al bloque único.
     */
    fun split(text: String): List<Chunk> {
        if (text.isBlank()) return emptyList()
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n").trim()

        // v1.133: Markdown-aware modo — Primero corte según la estructura.,Presione tamaño nuevamente para cortar
        if (markdownAware) {
            return splitMarkdownAware(normalized)
        }

        if (!chunkByToken) {
            return splitByChars(normalized)
        }

        // v1.133: token-based modo
        return splitByTokens(normalized)
    }

    // ── patrón de recuento de caracteres(Implementación original v1.54)──
    private fun splitByChars(text: String): List<Chunk> {
        if (text.length <= targetSize) return listOf(Chunk(text, 0))
        val paragraphs = text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
        val sentences = mutableListOf<String>()
        for (para in paragraphs) {
            if (para.length <= targetSize) {
                sentences.add(para)
            } else {
                sentences.addAll(splitLongParagraph(para))
            }
        }
        return mergeWithOverlap(sentences)
    }

    /** Dividir párrafos muy largos por oraciones(Puntuación en chino e inglés.). */
    private fun splitLongParagraph(para: String): List<String> {
        val result = mutableListOf<String>()
        val parts = sentenceEnd.split(para).map { it.trim() }.filter { it.isNotEmpty() }
        for (part in parts) {
            if (part.length <= targetSize) {
                result.add(part)
            } else {
                var i = 0
                while (i < part.length) {
                    val end = minOf(i + targetSize, part.length)
                    result.add(part.substring(i, end))
                    i = end
                }
            }
        }
        return result
    }

    private fun mergeWithOverlap(pieces: List<String>): List<Chunk> {
        if (pieces.isEmpty()) return emptyList()
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (piece in pieces) {
            if (current.isNotEmpty() && current.length + 1 + piece.length > targetSize) {
                chunks.add(current.toString().trim())
                val tail = current.takeLast(overlap)
                current.clear()
                current.append(tail)
                current.append(piece)
            } else {
                if (current.isNotEmpty()) current.append("\n")
                current.append(piece)
            }
        }
        if (current.isNotBlank()) chunks.add(current.toString().trim())
        return chunks.mapIndexed { idx, content -> Chunk(content, idx) }
    }

    // ── v1.133: Markdown-aware modo ──
    /**
     * por límite de estructura(bloque de código/Título/mesa)Priorizar la división,Preservar la integridad semántica.
     * Presione nuevamente después de cortar [targetSize] Corte más dentro de los límites de la estructura.
     */
    private fun splitMarkdownAware(text: String): List<Chunk> {
        // 1. Presiona el texto"unidad estructural"dividir en pieces,Cada pieza es un bloque semántico indivisible.
        val pieces = splitByMarkdownStructure(text)
        // 2. Fusionar adyacente por targetSize piece,Preservar los límites de la estructura
        return mergeMarkdownPieces(pieces)
    }

    /** Identificar los límites de la estructura Markdown,Devuelve una lista de unidades estructurales.(por unidad = bloque de código/Párrafo de título/mesa/Párrafo ordinario). */
    private fun splitByMarkdownStructure(text: String): List<MdPiece> {
        val pieces = mutableListOf<MdPiece>()
        val lines = text.split("\n")
        val current = StringBuilder()
        var currentHeader: String? = null
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            // ``` Comienza el bloque de código
            if (codeFence.matches(line)) {
                // flush Texto plano acumulado actual
                if (current.isNotBlank()) {
                    pieces.add(MdPiece(current.toString().trim(), currentHeader, PieceType.TEXT))
                    current.clear()
                }
                // Recoge todo el bloque de código(Incluyendo apertura y cierre. ```
                val codeBlock = StringBuilder(line).append("\n")
                i++
                while (i < lines.size && !codeFence.matches(lines[i])) {
                    codeBlock.append(lines[i]).append("\n")
                    i++
                }
                if (i < lines.size) { // cerrado ```
                    codeBlock.append(lines[i]).append("\n")
                    i++
                }
                pieces.add(MdPiece(codeBlock.toString().trimEnd(), currentHeader, PieceType.CODE))
                continue
            }

            // línea de título
            mdHeader.find(line)?.let { m ->
                if (current.isNotBlank()) {
                    pieces.add(MdPiece(current.toString().trim(), currentHeader, PieceType.TEXT))
                    current.clear()
                }
                currentHeader = m.groupValues[2].trim()
                pieces.add(MdPiece(line, currentHeader, PieceType.HEADER))
                i++
                continue
            }

            // fila de la tabla(continuo |...|...| filas como una pieza)
            if (mdTableRow.matches(line)) {
                if (current.isNotBlank()) {
                    pieces.add(MdPiece(current.toString().trim(), currentHeader, PieceType.TEXT))
                    current.clear()
                }
                val table = StringBuilder(line).append("\n")
                i++
                while (i < lines.size && mdTableRow.matches(lines[i])) {
                    table.append(lines[i]).append("\n")
                    i++
                }
                pieces.add(MdPiece(table.toString().trimEnd(), currentHeader, PieceType.TABLE))
                continue
            }

            // linea ordinaria:acumular
            current.append(line).append("\n")
            i++
        }
        if (current.isNotBlank()) pieces.add(MdPiece(current.toString().trim(), currentHeader, PieceType.TEXT))

        return pieces
    }

    /** Fusionar piezas de Markdown para cerrar targetSize,bloque de código/La forma en su totalidad no está cortada. */
    private fun mergeMarkdownPieces(pieces: List<MdPiece>): List<Chunk> {
        if (pieces.isEmpty()) return emptyList()
        val chunks = mutableListOf<Chunk>()
        val current = StringBuilder()
        var currentHeader: String? = null
        var currentTypes = mutableListOf<PieceType>()
        // P1-2 Reparación (informe de auditoría): valor antiguo targetSize * 2 Permite una sola pieza en“El bloque actual está vacío.”tiempo
        // Inserte el párrafo completo (porque appendTextPieza sólo está en current.isNotEmpty() al ras),
        // El usuario establece 400 pero obtiene bloques cercanos a 800 —— El parámetro no surte efecto.
        // Cambie a dividir estrictamente según targetSize para garantizar que el tamaño del bloque sea controlable.
        val plainChunkLimit = targetSize.coerceAtLeast(1)

        fun flush() {
            if (current.isNotBlank()) {
                val content = current.toString().trimEnd()
                if (content.isNotBlank()) {
                    val meta = mutableMapOf<String, String>()
                    currentHeader?.let { meta["section"] = it }
                    chunks.add(Chunk(content, chunks.size, meta))
                }
                current.clear()
                currentTypes.clear()
            }
        }

        fun appendTextPiece(content: String, header: String?) {
            val pieceLen = measureSize(content)
            val currentLen = measureSize(current.toString())
            if (current.isNotEmpty() && currentLen + 1 + pieceLen > targetSize) {
                // P1-1 Reparación (informe de auditoría): Primero debes buscar la cola y luego enjuagar.
                // Implementación antigua primero flush() Limpiar la corriente, entonces current.toString().takeLast(...),
                // Lo que obtienes es siempre una cadena vacía.——markdownAware Habilitado de forma predeterminada, lo que da como resultado que todos los usuarios
                // La configuración de "superposición de bloques" es completamente inválida (UI ajustable,DB existe, pero nunca entra en vigor).
                val tail = current.toString().takeLast(overlap.coerceAtLeast(0))
                flush()
                if (tail.isNotBlank()) current.append(tail).append("\n")
            }
            if (current.isEmpty()) currentHeader = header
            if (current.isNotEmpty()) current.append("\n")
            current.append(content)
            currentTypes.add(PieceType.TEXT)
        }

        for (piece in pieces) {
            val pieceLen = measureSize(piece.content)
            // bloque de código/mesa:Incluso si se excede el tamaño objetivo, todo el(no más cortes,Garantizar la integridad semántica)
            if (piece.type == PieceType.CODE || piece.type == PieceType.TABLE) {
                if (current.isNotEmpty()) flush()
                currentHeader = piece.header
                val meta = mutableMapOf<String, String>()
                piece.header?.let { meta["section"] = it }
                meta["type"] = piece.type.name.lowercase()
                chunks.add(Chunk(piece.content, chunks.size, meta))
                continue
            }

            // No se puede incluir texto plano no estructurado y muy largo en una inserción en un bloque gigante.
            // bloque de código/La tabla aún se conserva en su totalidad en la rama de semántica estructural anterior.
            val fragments = if (pieceLen > plainChunkLimit) {
                splitOversizedPlainText(piece.content)
            } else {
                listOf(piece.content)
            }
            fragments.forEach { fragment -> appendTextPiece(fragment, piece.header) }
        }
        flush()
        return chunks
    }

    /** Divida texto plano largo y no estructurado en segmentos fusionables del tamaño objetivo,superpuesto por [mergeMarkdownPieces] Procesamiento unificado. */
    private fun splitOversizedPlainText(text: String): List<String> {
        val boundedTarget = targetSize.coerceAtLeast(1)
        if (!chunkByToken) return text.chunked(boundedTarget)

        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(
                text.length,
                start + estimateTokensToChars(boundedTarget).coerceAtLeast(1),
            )
            while (end > start + 1 && estimateTokens(text.substring(start, end)) > boundedTarget) {
                end--
            }
            if (end <= start) end = minOf(start + 1, text.length)
            result += text.substring(start, end)
            start = end
        }
        return result
    }

    private enum class PieceType { HEADER, TEXT, CODE, TABLE }
    private data class MdPiece(val content: String, val header: String?, val type: PieceType)

    // ── v1.133: token-based modo ──
    private fun splitByTokens(text: String): List<Chunk> {
        // Simplifique la implementación:Acumulación estimada por token,Si excede el tamaño objetivo, se cortará. Mezclar chino e inglés es más preciso.
        val paragraphs = text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var currentTokens = 0

        for (para in paragraphs) {
            val paraTokens = measureSize(para)
            if (current.isNotEmpty() && currentTokens + paraTokens > targetSize) {
                chunks.add(current.toString().trim())
                // overlap:Obtenga el texto sobre tokens de superposición del final del bloque actual
                val tail = current.toString().takeLast(estimateTokensToChars(overlap))
                current.clear()
                current.append(tail)
                currentTokens = measureSize(tail)
            }
            if (current.isNotEmpty()) current.append("\n")
            current.append(para)
            currentTokens += paraTokens
        }
        if (current.isNotBlank()) chunks.add(current.toString().trim())
        return chunks.mapIndexed { idx, content -> Chunk(content, idx) }
    }

    /** Medir el tamaño del texto:token Estimación del token de retorno del patrón,El modo de caracteres devuelve el número de caracteres. */
    private fun measureSize(text: String): Int = if (chunkByToken) estimateTokens(text) else text.length

    /** Convierta aproximadamente la cantidad de tokens en la cantidad de caracteres(chino 1 token≈1.5 personaje,ingles 1 token≈4 personaje,compromiso 2.5). */
    private fun estimateTokensToChars(tokens: Int): Int = (tokens * 2.5).toInt().coerceAtLeast(0)

    /** v1.55: Número de token exacto(Basado en codificación BPE,jtokkit Recurrir a la estimación heurística cuando falla la inicialización). */
    fun estimateTokens(text: String): Int = io.zer0.muse.util.TokenEstimator.estimate(text).coerceAtLeast(1)
}
