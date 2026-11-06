package io.zer0.muse.ui.chat

private const val QUOTE_PREFIX = "> "

/**
 * Analice el bloque citado desde el principio del contenido.
 *
 * Regresar Pair<Contenido de la cotización(eliminar prefijo), Texto>. Si no hay ningún prefijo de referencia, se devuelve nulo al contenido.
 */
fun parseQuotedContent(content: String): Pair<String?, String> {
    val lines = content.lines()
    if (lines.isEmpty()) return null to content
    val quoteLines = mutableListOf<String>()
    var i = 0
    while (i < lines.size && lines[i].startsWith(QUOTE_PREFIX)) {
        quoteLines.add(lines[i].removePrefix(QUOTE_PREFIX))
        i++
    }
    // Saltar líneas vacías después de las comillas
    while (i < lines.size && lines[i].isBlank()) {
        i++
    }
    if (quoteLines.isEmpty()) return null to content
    val quote = quoteLines.joinToString("\n")
    val body = lines.drop(i).joinToString("\n")
    return quote to body
}

/**
 * Concatene el contenido citado con el texto principal en un formato persistente.
 *
 * Formato:Antes de cada línea de cita, agregue "> ",Seguido de una línea en blanco y texto.
 */
fun buildQuotedContent(quote: String, body: String): String {
    val trimmedQuote = quote.trim()
    val trimmedBody = body.trim()
    return if (trimmedQuote.isEmpty()) {
        trimmedBody
    } else {
        trimmedQuote.lines().joinToString("\n") { "> $it" } + "\n\n" + trimmedBody
    }
}
