package io.zer0.memory.fact

/**
 * Facts FTS4 Gestor de segmentación de palabras chinas.
 *
 * Adopte la solución ngram de capa de aplicación(Evite dependencias de NDK + compatible con todas las versiones de Android):
 *  - chino(CJK ideograma unificado + kana)Haz una ventana corredera de 2 gramos.,espacio separado
 *  - ingles/Los números conservan las palabras originales.(Minúsculas)
 *  - puntuación/El espacio como separador
 *
 * indexación:Convertir el contenido de hechos en ngram(Contiene la última palabra),Almacenar en la tabla virtual Facts_fts.
 * Al consultar:Convertir consulta a ngram(No incluye la última palabra.),usar FTS4 MATCH(Predeterminado AND,Se puede cambiar OR).
 *
 * Ejemplo:
 *  - "hola mundo" → "hola buen mundo mundo"
 *  - "hello Hola" → "hello Hola"
 *  - "GPT-4 modelo" → "gpt 4 modelo"
 */
object FactFtsManager {

    /** CJK 2-gram El tamaño de la ventana de la ventana corredera. */
    private const val NGRAM_SIZE = 2

    /** FTS4 MATCH Conector OR en una expresión. */
    private const val OR_OPERATOR = " OR "

    /** Determinar si el personaje es CJK(chino/japonés/coreano). */
    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x4E00..0x9FFF) || // CJK ideogramas unificados
            (code in 0x3400..0x4DBF) || // CJK Expandir A
            (code in 0x3040..0x309F) || // hiragana
            (code in 0x30A0..0x30FF) || // Katakana
            (code in 0xAC00..0xD7AF) // sílabas coreanas
    }

    /**
     * Convertir texto en secuencia de ngram(cadena de token separada por espacios).
     *
     * hacer en chino 2-gram,ingles/Los números conservan las palabras originales.(Minúsculas),La puntuación sirve como separador.
     * Carácter único CJK(aislado)directamente como token(Admite consultas de una sola palabra).
     *
     * @param includeTrailingTailChar Si se debe generar adicionalmente la última palabra del segmento CJK. Es necesario en el lado índice para cubrir.
     *   carácter final de segmento de longitud impar;No requerido en el lado de la consulta —— La última palabra sólo estará restringida según la semántica AND.,No habrá retirada.
     */
    fun toNgram(text: String, includeTrailingTailChar: Boolean = true): String {
        if (text.isBlank()) return ""
        val tokens = mutableListOf<String>()
        val cjkBuffer = StringBuilder()
        val asciiBuffer = StringBuilder()

        fun flushCjk() {
            if (cjkBuffer.isEmpty()) return
            val s = cjkBuffer.toString()
            if (s.length >= NGRAM_SIZE) {
                for (i in 0..s.length - NGRAM_SIZE) {
                    tokens.add(s.substring(i, i + NGRAM_SIZE))
                }
            }
            // También se añade la última palabra.(Úselo en el lado del índice para cubrir el último carácter.)
            if (includeTrailingTailChar && s.isNotEmpty()) {
                tokens.add(s.last().toString())
            }
            cjkBuffer.clear()
        }

        fun flushAscii() {
            if (asciiBuffer.isEmpty()) return
            tokens.add(asciiBuffer.toString().lowercase())
            asciiBuffer.clear()
        }

        for (ch in text) {
            when {
                isCjk(ch) -> {
                    flushAscii()
                    cjkBuffer.append(ch)
                }
                ch.isLetterOrDigit() -> {
                    flushCjk()
                    asciiBuffer.append(ch)
                }
                else -> {
                    flushCjk()
                    flushAscii()
                }
            }
        }
        flushCjk()
        flushAscii()
        return tokens.joinToString(" ")
    }

    /**
     * Convierta términos de consulta en expresiones FTS4 MATCH.
     *
     * - La consulta vacía devuelve una cadena vacía(La persona que llama debe omitir la búsqueda.)
     * - El token único regresa directamente
     * - Varios tokens están conectados con espacios de forma predeterminada.(FTS4 Semántica Y predeterminada);[orSemantics] Cuando sea verdadero, use OR para conectarse
     * - Escapar de caracteres especiales(FTS4 de " * : espera)
     *
     * El lado de la consulta no genera el token de la última palabra: las palabras dispersas en la misma fila de hechos no son de naturaleza compartida. 2-gram,
     * Exigir que se diga la última palabra sólo conducirá a menos retiradas.
     */
    fun toMatchQuery(query: String, orSemantics: Boolean = false): String {
        val ngram = toNgram(query, includeTrailingTailChar = false)
        if (ngram.isBlank()) return ""
        val joiner = if (orSemantics) OR_OPERATOR else " "
        // Escapar de los caracteres especiales de FTS4:Envuelva cada uno entre comillas dobles token
        return ngram.split(' ')
            .filter { it.isNotBlank() }
            .joinToString(joiner) { "\"${it.replace("\"", "")}\"" }
    }

    /**
     * Divida la cadena de consulta en términos de búsqueda:Los espacios en blanco y la puntuación son delimitadores.,No se hace ningún juicio de participio.
     *
     * Para pantalones de bolsillo LIKE:La cadena completa cuando no se puede encontrar la subcadena,Divídelo en palabras y busca una por una.,Cualquier acierto se convierte en candidato.
     * El orden de las palabras y las mayúsculas originales permanecen sin cambios.(LIKE Sensibilidad entre mayúsculas y minúsculas determinada por SQLite),Sólo queda una palabra repetida.
     */
    fun toQueryTerms(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val terms = mutableListOf<String>()
        val buffer = StringBuilder()
        for (ch in query) {
            if (ch.isLetterOrDigit()) {
                buffer.append(ch)
            } else if (buffer.isNotEmpty()) {
                terms.add(buffer.toString())
                buffer.clear()
            }
        }
        if (buffer.isNotEmpty()) terms.add(buffer.toString())
        return terms.distinct()
    }

    /**
     * Determinar si la consulta contiene solo caracteres CJK individuales aislados(En este momento no se puede alcanzar FTS4 de 2 gramos,Debería revertirse LIKE).
     */
    fun shouldFallbackToLike(query: String): Boolean {
        val ngram = toNgram(query, includeTrailingTailChar = false)
        if (ngram.isBlank()) return true
        val tokens = ngram.split(' ').filter { it.isNotBlank() }
        // Cuando todos son tokens de un solo carácter(Ninguno 2-gram),FTS4 Recuerdo inadecuado,revertir LIKE
        return tokens.isNotEmpty() && tokens.all { it.length == 1 }
    }
}
