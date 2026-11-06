package io.zer0.muse.ui.chat

/**
 * Longitud mínima del pliegue superpuesto de la cola:La superposición de dos caracteres chinos es mayoritariamente una coincidencia.("Muy bueno"/"Está bien"),Sólo dos caracteres o más se reconocen como
 * Informes repetidos del mismo texto.;Inglés por carácter,Más de dos caracteres son básicamente un fragmento de palabra completo.
 */
private const val MIN_OVERLAP_CHARS = 2

/**
 * Fusiona un texto ASR con el texto acumulado de esta ronda.
 *
 * ASR La semántica de devolución de llamada de cada Proveedor es "el texto completo de esta sesión hasta el momento".,mientras que las conversaciones de voz permiten conversaciones ASR
 * Termina solo(Final silencioso del lado del servidor)Reabrir más tarde y seguir acumulando,Así se recibirá el mismo texto acumulado al mismo tiempo.:
 *  - El texto completo de todo el párrafo vuelto a publicar.(Contenido anterior ya incluido.)
 *  - Contiene solo texto de partes nuevas de esta conversación.
 *  - El resultado intermedio de la misma frase va creciendo.("Hoy"→"El tiempo de hoy")
 *
 * reglas:
 *  - [incoming] esta vacio → Regresar [accumulated]
 *  - [accumulated] esta vacio → Regresar [incoming]
 *  - [incoming] a [accumulated] comienzo → Regresar [incoming](El servidor dio el texto completo.)
 *  - [accumulated] a [incoming] comienzo → Regresar [accumulated](El servidor resiente el texto antiguo.)
 *  - el resto → anexar;Cuando hay superposición en ambos lados, solo se conservará una copia del segmento superpuesto.
 *
 * Los espacios en blanco iniciales y finales siempre son trim,El chino no añade separadores.,Sólo si ambos lados son alfanuméricos ASCII(Límites de palabras en inglés)Completa un espacio.
 */
internal fun mergeTranscript(accumulated: String, incoming: String): String {
    val acc = accumulated.trim()
    val inc = incoming.trim()
    return when {
        inc.isEmpty() -> acc
        acc.isEmpty() -> inc
        inc.startsWith(acc) -> inc
        acc.startsWith(inc) -> acc
        else -> appendWithOverlap(acc, inc)
    }
}

/** anexar [inc] llegar [acc]:Sólo se conserva una copia de la sección superpuesta. */
private fun appendWithOverlap(acc: String, inc: String): String {
    val overlap = longestSuffixPrefixOverlap(acc, inc)
    if (overlap >= MIN_OVERLAP_CHARS) return acc + inc.substring(overlap)
    // El chino no tiene espacios entre palabras.,Si lo agregas, habrá una palabra más.;Sólo los límites de palabras en inglés requieren un espacio
    val needsSpacer = acc.last().isAsciiWordChar() && inc.first().isAsciiWordChar()
    return if (needsSpacer) "$acc $inc" else acc + inc
}

/** [acc] sufijo con [inc] La longitud más larga de prefijos que se superponen.(La longitud de plegado cuando el mismo texto se informa repetidamente). */
private fun longestSuffixPrefixOverlap(acc: String, inc: String): Int {
    for (len in minOf(acc.length, inc.length) downTo 1) {
        if (acc.regionMatches(acc.length - len, inc, 0, len)) return len
    }
    return 0
}

private fun Char.isAsciiWordChar(): Boolean = code < 0x80 && isLetterOrDigit()
