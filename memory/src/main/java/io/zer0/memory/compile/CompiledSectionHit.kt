package io.zer0.memory.compile

/**
 * Una línea de éxito en el resumen de la compilación.
 *
 * @param sectionKey El segmento donde se encuentra el golpe.(facts / today / week / longterm)
 * @param text El texto original de la línea de éxito.(Se han eliminado los espacios en blanco iniciales y finales.,Truncar si es demasiado largo)
 */
data class CompiledSectionHit(
    val sectionKey: String,
    val text: String,
)

/** ¿Cuántos personajes se pueden incluir en un solo golpe?:Un párrafo de prosa no debe mover el párrafo completo al resultado de la herramienta. */
internal const val MAX_COMPILED_HIT_CHARS = 200

/**
 * Divida la cadena de consulta en palabras para escanear subcadenas.
 *
 * Sólo presione“letras/Segmento continuo de números.”cortar,sin participio:Tome las palabras eliminadas directamente contains,
 * Cortarlo en pedazos más pequeños que los términos de búsqueda puede amplificar los resultados falsos. Sólo queda una palabra repetida.
 */
internal fun compiledSearchTerms(query: String): List<String> {
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
 * Realice un escaneo de subcadenas dentro del texto segmentado del resumen compilado.
 *
 * Una fila que contiene cualquier término de consulta se considera candidata.,Ordenar por número de palabras clave en orden descendente(Mantener el orden del texto original si el número es el mismo),
 * La misma línea de texto aparece sólo una vez. Este es un complemento fuera de la tabla de hechos.:El párrafo resumen es la prosa resumida por LLM.,
 * El texto original a menudo no se encuentra en absoluto en los hechos.,Sólo se pueden recuperar mediante comparación de texto.
 *
 * @param sections Clave de sección y texto completo de la sección,Administrar en orden de inyección
 * @param query Cadena de consulta del usuario
 * @param limit Número máximo de artículos devueltos
 */
internal fun scanCompiledSections(
    sections: List<Pair<String, String>>,
    query: String,
    limit: Int,
): List<CompiledSectionHit> {
    if (limit <= 0) return emptyList()
    val terms = compiledSearchTerms(query)
    if (terms.isEmpty()) return emptyList()
    return sections.asSequence()
        .filter { (_, content) -> content.isNotBlank() }
        .flatMap { (sectionKey, content) -> content.lineSequence().map { sectionKey to it } }
        .map { (sectionKey, rawLine) -> sectionKey to rawLine.trim().removePrefix("-").trim() }
        .filter { (_, line) -> line.isNotEmpty() }
        .distinctBy { (_, line) -> line }
        .mapNotNull { (sectionKey, line) ->
            val matched = terms.count { line.contains(it) }
            if (matched == 0) null else CompiledSectionHit(sectionKey, line.take(MAX_COMPILED_HIT_CHARS)) to matched
        }
        .toList()
        .sortedByDescending { it.second }
        .map { it.first }
        .take(limit)
}
