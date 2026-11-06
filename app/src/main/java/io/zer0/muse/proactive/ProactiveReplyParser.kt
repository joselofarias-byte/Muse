package io.zer0.muse.proactive

import io.zer0.common.Logger

/**
 * Analiza la respuesta del modelo a una solicitud de mensaje no solicitado.
 *
 * Está separado en archivos porque la estrategia de tolerancia a fallas aquí se ajustará repetidamente (la forma en que los diferentes modelos escupen formatos es muy diferente),
 * Y no tiene nada que ver con "cómo construir palabras clave". Cambiar uno no debería cambiar el otro.
 *
 * Prioridad de tolerancia a fallos: si puede reconocer JSON, utilice JSON; si no puede reconocer JSON pero hay texto, acéptelo fácilmente;
 * Simplemente trátelo como si no se pareciera a nada.**falló**en lugar de saltar —— Los dos deben estar separados,
 * Porque los tratamientos de "modelo que se escapa" y "modelo que decide no contarlo" son completamente opuestos:
 * Vale la pena volver a intentar lo primero, pero volver a intentar lo segundo solo lo obligará a forzarlo.
 */
object ProactiveReplyParser {

    private const val TAG = "ProactiveReply"

    /** Límite superior estricto para texto (palabras). Si excede esta longitud, se debe cortar, de acuerdo con \"una a tres oraciones\" en las palabras clave. */
    const val MAX_CONTENT_CHARS = 200

    /** El texto principal debe tener al menos esta cantidad de palabras (la puntuación y los espacios en blanco no cuentan), de lo contrario equivale a no decir nada. */
    const val MIN_CONTENT_CHARS = 2

    /** Puntuación al final de las frases. La prioridad de truncamiento queda detrás de estos caracteres y no corta media palabra. */
    private const val SENTENCE_ENDS = ". ! ?!?…;;\n"

    /** Analiza los resultados. */
    sealed interface Result {
        /** No es momento de decirlo ahora. El motivo solo se ingresa en el registro y no se muestra al usuario. */
        data class Skip(val reason: String) : Result

        /** Digamos que el texto es [content]. */
        data class Message(val thoughtId: String?, val content: String, val tone: String) : Result

        /** La salida no está disponible. con [Skip] Deben separarse: el primero debe volver a juzgarse y el segundo es una decisión normal. */
        data class Failed(val reason: String) : Result
    }

    fun parse(raw: String): Result {
        val text = stripFences(raw).trim()
        val parsed = when {
            text.isEmpty() -> Result.Failed("El modelo devuelve contenido vacío")
            else -> extractJsonObject(text)?.let { parseJson(it) } ?: fallbackToProse(text)
        }
        return if (parsed is Result.Message) validate(parsed) else parsed
    }

    /**
     * Verificación física por mensaje de texto.
     *
     * La capa de análisis es el único lugar que puede impedir que "el modelo escriba una composición breve": escribir "de una a tres oraciones" en la palabra clave es solo una sugerencia.
     * Los usuarios de mensajes de texto largos enviados solo pueden ver las primeras docenas de palabras en la barra de notificaciones, por lo que también podrían suprimirlo aquí.
     * Demasiado corto por la misma razón. —— Una palabra o signo de puntuación no es "discurso" y se trata como un salto (no es un error y no se debe volver a intentar).
     */
    private fun validate(message: Result.Message): Result {
        val content = message.content.trim()
        if (content.count { it.isLetterOrDigit() } < MIN_CONTENT_CHARS) {
            Logger.w(TAG, "El texto es demasiado corto, haga clic en Saltar | len=${content.length}")
            return Result.Skip("El texto es demasiado corto (${content.length} palabras)")
        }
        if (content.length <= MAX_CONTENT_CHARS) return message.copy(content = content)
        val truncated = truncateToSentence(content)
        Logger.i(TAG, "El texto es demasiado largo y ha sido truncado. | from=${content.length} | to=${truncated.length}")
        return message.copy(content = truncated)
    }

    /**
     * Censurado en los límites de la oración. [MAX_CONTENT_CHARS] Dentro de.
     *
     * Busque primero el último signo de puntuación al final de la oración; si es demasiado pronto (menos de la mitad del límite superior), recurra a la siguiente mejor opción.
     * Cortar en el último espacio en blanco (el texto en inglés se basa en esto para no cortar palabras); Si no funciona, córtalo con fuerza.
     */
    private fun truncateToSentence(text: String): String {
        val head = text.take(MAX_CONTENT_CHARS)
        val sentenceEnd = head.indexOfLast { it in SENTENCE_ENDS }
        if (sentenceEnd >= MAX_CONTENT_CHARS / 2) return head.substring(0, sentenceEnd + 1).trim()
        val space = head.indexOfLast { it.isWhitespace() }
        if (space >= MAX_CONTENT_CHARS / 2) return head.substring(0, space).trim()
        return head.trim()
    }

    /** Ya es un caso JSON puro. */
    private fun parseJson(json: String): Result {
        val skipValue = Regex("\"skip\"\\s*:\\s*(true|false)")
            .find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()

        val reason = Regex("\"reason\"\\s*:\\s*\"([^\"]*)\"").find(json)
            ?.groupValues?.get(1).orEmpty()

        val content = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)
            ?.groupValues?.get(1)
            ?.let { unescape(it) }
            ?.trim()
            .orEmpty()

        return when {
            skipValue == true -> Result.Skip(reason.ifBlank { "No es adecuado hablar del juicio modelo ahora" })
            content.isNotBlank() -> {
                val thoughtId = Regex("\"thought_id\"\\s*:\\s*\"([^\"]*)\"").find(json)
                    ?.groupValues?.get(1)
                    ?.takeIf { it.isNotBlank() && it != "null" }
                val tone = Regex("\"tone\"\\s*:\\s*\"([^\"]*)\"").find(json)
                    ?.groupValues?.get(1).orEmpty()
                Result.Message(thoughtId, content, tone)
            }
            // JSON No hay ninguno skip=true No hay contenido: trátelo como un fracaso y deje que la capa superior lo intente nuevamente.
            else -> Result.Failed("JSON Campo de contenido faltante")
        }
    }

    private fun fallbackToProse(text: String): Result {
        if (!looksLikeProse(text)) return Result.Failed("La salida no se puede analizar")
        Logger.w(TAG, "Modelo no devuelto como JSON, aceptado como cuerpo de texto sin formato")
        return Result.Message(null, text, "")
    }

    private fun stripFences(raw: String): String =
        raw.replace(Regex("^```(?:json)?\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("\\s*```$", RegexOption.MULTILINE), "")

    /** Extraiga el primer objeto JSON equilibrado de la salida, que puede mezclarse con texto explicativo. */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        var depth = 0
        var inString = false
        var escaped = false
        var stop = -1
        for (i in start.coerceAtLeast(0) until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) {
                        stop = i
                        break
                    }
                }
            }
        }
        return if (start >= 0 && stop > start) text.substring(start, stop + 1) else null
    }

    /** JSON Se restauran los escapes comunes en las cuerdas. */
    private fun unescape(s: String): String = s
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    /**
     * Determine si el resultado parece un párrafo normal.
     *
     * Umbral: longitud razonable, sin código obvio/Estructura clave-valor.
     * Esta cobertura existe porque algunos modelos pequeños simplemente se niegan a devolver JSON.
     * Y a menudo está disponible cuando está escrito. —— Tirarlo a la basura sería un fracaso en vano.
     */
    private fun looksLikeProse(text: String): Boolean {
        val structural = text.count { it == '{' || it == '}' || it == ':' }
        return text.length in 4..800 &&
            !text.contains("\"skip\"") &&
            !text.contains("thought_id") &&
            structural < 3
    }
}
