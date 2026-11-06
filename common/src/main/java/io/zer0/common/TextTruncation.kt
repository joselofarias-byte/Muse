package io.zer0.common

import kotlin.math.max
import kotlin.math.min

/**
 * Herramienta de truncamiento de texto.
 *
 * ## por qué es necesario"Protege la cabeza + protege la cola"
 *
 * Deje solo el truncamiento de la cabeza (`text.take(n)`) para los resultados de la herramienta y los informes de errores son**Perder información clave**: La línea de error del registro,
 * La conclusión de salida del comando y la declaración de retorno del código suelen estar al final. Al comprimir el contexto, truncarlo en una sola línea. Si sólo queda el encabezado,
 * Lo que ve el modelo es siempre"llamada iniciada"pero no puedo ver"cual es el resultado".
 *
 * ## UTF-8 seguro
 *
 * prensa**bytes**Divida y alinee los límites de los personajes. Por Java/Kotlin `Char` La división de subíndices se realizará en la pareja sustituta (emoji,
 * Parte del área de extensión CJK) está cortada por la mitad, lo que da como resultado personajes sustitutos huérfanos.——La serialización JSON descendente falla directamente.
 */
object TextTruncation {

    /** Predeterminado: la cabeza conserva la proporción (el resto se deja para la cola). */
    private const val DEFAULT_HEAD_RATIO = 0.4

    /** Valor predeterminado: mantener la relación de cola. */
    private const val DEFAULT_TAIL_RATIO = 0.4

    /** Trunca el resultado.[truncated]=false Significa que el texto original no excede el límite y se devuelve tal cual. */
    data class Result(
        val text: String,
        val truncated: Boolean,
        val originalLength: Int,
        val omittedLength: Int,
    )

    /**
     * exceder [maxLength] Al conservar la cabeza, la cola y el medio, reemplácelos con las marcas omitidas.
     *
     * @param maxLength El límite superior de la longitud que desencadena el truncamiento (en caracteres; segmentación internamente segura basada en bytes UTF-8)
     * @param marker Cómo se generan las etiquetas omitidas (la cantidad de caracteres omitidos se proporciona de forma predeterminada para facilitar que el modelo determine la cantidad de información)
     */
    fun headTail(text: String, maxLength: Int, marker: (Int, Int) -> String = ::defaultMarker): Result =
        if (maxLength <= 0 || text.length <= maxLength) {
            Result(text, truncated = false, originalLength = text.length, omittedLength = 0)
        } else {
            val headLength = max(1, (maxLength * DEFAULT_HEAD_RATIO).toInt())
            val tailLength = max(1, (maxLength * DEFAULT_TAIL_RATIO).toInt())
            val head = text.takeSurrogateSafe(headLength)
            val tail = text.takeLastSurrogateSafe(tailLength)
            val omitted = text.length - head.length - tail.length
            if (omitted <= 0) {
                // El límite superior extremadamente pequeño conduce a una superposición de la cabeza y la cola: degenera en un truncamiento de cabeza puro, al menos no se genera contenido duplicado.
                val only = text.takeSurrogateSafe(maxLength)
                Result(only, truncated = true, text.length, text.length - only.length)
            } else {
                Result(
                    text = head + marker(omitted, text.length) + tail,
                    truncated = true,
                    originalLength = text.length,
                    omittedLength = omitted,
                )
            }
        }

    /** Entrada conveniente: solo texto truncado. */
    fun headTailText(text: String, maxLength: Int): String = headTail(text, maxLength).text

    /**
     * exceder [maxLength] Sólo se conserva la cola y el comienzo se reemplaza por una marca de omisión.
     *
     * Contenido utilizado para la "conclusión al final": salida de comandos, registros de compilación, informes de prueba. Este tipo de texto comienza con un registro de progreso,
     * Lo que realmente importa es el informe de errores, el caso de uso fallido y el estado de salida en las últimas líneas; truncar por cabeza significa retener la parte menos importante.
     *
     * @param maxLength Longitud máxima (en caracteres) que desencadena el truncamiento; el corte no destruye los pares sustitutos.
     */
    fun tail(text: String, maxLength: Int, marker: (Int, Int) -> String = ::defaultTailMarker): Result {
        if (maxLength <= 0 || text.length <= maxLength) {
            return Result(text, truncated = false, originalLength = text.length, omittedLength = 0)
        }
        val kept = text.takeLastSurrogateSafe(maxLength)
        val omitted = text.length - kept.length
        return Result(
            text = marker(omitted, text.length) + kept,
            truncated = true,
            originalLength = text.length,
            omittedLength = omitted,
        )
    }

    /** Entrada conveniente: simplemente guarde el texto después del avance. */
    fun tailText(text: String, maxLength: Int): String = tail(text, maxLength).text

    /** Marca de omisión predeterminada: indica la cantidad de omisión y la extensión del texto original para evitar que el modelo piense erróneamente que lo que ve lo es todo. */
    private fun defaultMarker(omitted: Int, original: Int): String =
        "\n\n[... Omitido $omitted palabra (texto original $original palabras)...]\n\n"

    /** Marca de puntos suspensivos truncados en la cola: la marca omitida está al principio y la marca se coloca primero, seguida de la cola retenida. */
    private fun defaultTailMarker(omitted: Int, original: Int): String =
        "[... Comienzo omitido $omitted palabra (texto original $original palabras), la siguiente es la parte final ...]\n\n"

    /**
     * Tomar desde cero [count] caracteres sin cortar el par sustituto (el sustituto alto es seguido por el sustituto bajo para formar un par).
     */
    private fun String.takeSurrogateSafe(count: Int): String {
        if (count >= length) return this
        val end = if (count > 0 && Character.isHighSurrogate(this[count - 1])) count - 1 else count
        return substring(0, min(end, length))
    }

    /**
     * Tomar de la cola [count] caracteres y no comienza en el medio de la pareja sustituta.
     */
    private fun String.takeLastSurrogateSafe(count: Int): String {
        if (count >= length) return this
        var start = length - count
        if (start in 1 until length && Character.isLowSurrogate(this[start])) {
            start += 1
        }
        return substring(min(start, length))
    }
}
