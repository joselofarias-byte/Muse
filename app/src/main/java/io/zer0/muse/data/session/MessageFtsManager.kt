package io.zer0.muse.data.session

/**
 * Phase 10.3: FTS4 Gestor de segmentación de palabras chinas.
 *
 * Adopte la solución ngram de capa de aplicación(Evite dependencias de NDK + compatible con todas las versiones de Android):
 *  - chino(CJK ideograma unificado + kana)Haz una ventana corredera de 2 gramos.,espacio separado
 *  - ingles/Los números conservan las palabras originales.(Minúsculas)
 *  - puntuación/El espacio como separador
 *
 * indexación:Convertir mensaje.content a ngram,Almacenar en la tabla virtual message_fts
 * Al consultar:Convertir consulta a ngram,usar FTS4 MATCH(Semántica Y predeterminada)
 *
 * Ejemplo:
 *  - "hola mundo" → "hola buen mundo mundo"
 *  - "hello Hola" → "hello Hola"
 *  - "GPT-4 modelo" → "gpt 4 modelo"(GPT-4 Dividir en gpt y 4)
 *
 * recordar:2-gram Cubre la mayoría de las coincidencias difusas chinas(Excepto consulta de una sola palabra,movimiento de una sola palabra LIKE fallback).
 * Volumen del índice:Aproximadamente 2 veces el texto original(chino 2-gram,El inglés no está inflado.).
 */
object MessageFtsManager {

    /** CJK 2-gram El tamaño de la ventana de la ventana corredera. */
    private const val NGRAM_SIZE = 2

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
     */
    fun toNgram(text: String): String {
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
            // También se añade la última palabra.(Admite consulta de última palabra)
            if (s.isNotEmpty()) {
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
     * - Varias fichas están conectadas con espacios.(FTS4 Semántica Y predeterminada)
     * - Escapar de caracteres especiales(FTS4 de " * : espera)
     */
    fun toMatchQuery(query: String): String {
        val ngram = toNgram(query)
        if (ngram.isBlank()) return ""
        // Escapar de los caracteres especiales de FTS4:Envuelva cada uno entre comillas dobles token
        return ngram.split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { "\"${it.replace("\"", "")}\"" }
    }

    /**
     * Convierta términos de consulta en expresiones FTS5 MATCH(unicode61 Índice de texto original).
     *
     * - cada párrafo separado por espacios en blanco como una frase(entre comillas dobles),Y semántica entre segmentos
     * - Las comillas dobles se convierten en dos comillas dobles según las reglas FTS5
     * - puro en blanco/La puntuación devuelve una cadena vacía,La persona que llama usa LIKE para llegar al fondo de las cosas.
     */
    fun toFts5MatchQuery(query: String): String {
        return query.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { "\"${it.replace("\"", "\"\"")}\"" }
    }
}
