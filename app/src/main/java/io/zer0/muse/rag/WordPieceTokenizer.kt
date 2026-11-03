package io.zer0.muse.rag

/**
 * v1.134: BERT WordPiece tokenizador(Implementación pura de Kotlin,Sin dependencias externas).
 *
 * usado para [OnnxEmbeddingProvider] / [OnnxRerankProvider] Convierta texto en una secuencia de tokens estilo BERT.
 *
 * Algoritmo según funcionario de BERT tokenizer:
 *  1. participio básico:Minúsculas, separación de espacios en blanco, separación de puntuación,CJK segmentación de palabras
 *  2. WordPiece:Codicioso subpalabra coincidente más larga,prefijo "##" Indica continuación de subpalabra
 *  3. El token desconocido está asignado a [UNK]
 *
 * @see <a href="https://github.com/google-research/bert">BERT Implementación oficial</a>
 */
object WordPieceTokenizer {

    /** participio completo:Segmentación básica de palabras + división de subpalabras de Wordpiece. */
    fun tokenize(text: String, vocab: Map<String, Int>, maxInputCharsPerWord: Int = 100): List<String> {
        val basicTokens = basicTokenize(text)
        val result = mutableListOf<String>()
        for (token in basicTokens) {
            result.addAll(wordPiece(token, vocab, maxInputCharsPerWord))
        }
        return result
    }

    /**
     * participio básico:Minúsculas, en blanco/puntuación/CJK Dividir.
     * El token de salida todavía está"palabra"nivel,No contiene etiquetas de subpalabras de WordPieza.
     */
    private fun basicTokenize(text: String): List<String> {
        val cleaned = cleanText(text)
        if (cleaned.isEmpty()) return emptyList()
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in cleaned) {
            when {
                ch.isWhitespace() -> flush(current, tokens)
                isPunctuation(ch) -> {
                    flush(current, tokens)
                    tokens.add(ch.toString())
                }
                isCjkChar(ch) -> {
                    // CJK Los personajes por sí solos se convierten token(BERT acción oficial)
                    flush(current, tokens)
                    tokens.add(ch.toString())
                }
                else -> current.append(ch)
            }
        }
        flush(current, tokens)
        return tokens.map { it.lowercase() }
    }

    private fun flush(sb: StringBuilder, tokens: MutableList<String>) {
        if (sb.isNotEmpty()) {
            tokens.add(sb.toString())
            sb.clear()
        }
    }

    /** Caracteres de control limpios y caracteres de ancho cero.,Especificación en blanco. */
    private fun cleanText(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            val code = ch.code
            if (code == 0 || code == 0xFFFD || isControl(ch)) continue
            if (isWhitespace(ch)) {
                sb.append(' ')
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * WordPiece codicioso partido más largo:de [token] ventaja,Acorta progresivamente la subcadena hasta que aparezca en el vocabulario.
     * Adición de subpalabras no iniciales "##" prefijo. Si no se acierta la palabra completa, regrese [UNK].
     */
    private fun wordPiece(token: String, vocab: Map<String, Int>, maxChars: Int): List<String> {
        if (token.isEmpty()) return emptyList()
        if (token.length > maxChars) return listOf("[UNK]")

        val result = mutableListOf<String>()
        val chars = token.toList()
        var start = 0
        while (start < chars.size) {
            var end = chars.size
            var found: String? = null
            while (start < end) {
                val sub = if (start == 0) {
                    chars.subList(start, end).joinToString("")
                } else {
                    "##" + chars.subList(start, end).joinToString("")
                }
                if (vocab.containsKey(sub)) {
                    found = sub
                    break
                }
                end--
            }
            if (found == null) return listOf("[UNK]")
            result.add(found)
            start = end
        }
        return result
    }

    private fun isWhitespace(ch: Char): Boolean = ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' ||
        Character.getType(ch) == Character.SPACE_SEPARATOR.toInt()

    private fun isControl(ch: Char): Boolean {
        if (ch == '\t' || ch == '\n' || ch == '\r') return false
        val type = Character.getType(ch)
        return type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
    }

    private fun isPunctuation(ch: Char): Boolean {
        val code = ch.code
        if (code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126) return true
        val type = Character.getType(ch)
        return type == Character.START_PUNCTUATION.toInt() ||
            type == Character.END_PUNCTUATION.toInt() ||
            type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.OTHER_PUNCTUATION.toInt() ||
            type == Character.CONNECTOR_PUNCTUATION.toInt() ||
            type == Character.DASH_PUNCTUATION.toInt()
    }

    private fun isCjkChar(ch: Char): Boolean {
        val c = ch.code
        return (c in 0x4E00..0x9FFF) || // CJK Unificar caracteres chinos
            (c in 0x3400..0x4DBF) || // CJK Extension A
            (c in 0x20000..0x2A6DF) || // CJK Extension B
            (c in 0x2A700..0x2B73F) || // CJK Extension C
            (c in 0x2B740..0x2B81F) || // CJK Extension D
            (c in 0x3000..0x303F) || // CJK símbolos y puntuación
            (c in 0xFF00..0xFFEF) // medio tamaño/caracteres de ancho completo
    }
}
