package io.zer0.muse.web

import java.text.Normalizer

/**
 * Normalización de consultas de búsqueda en Internet.
 *
 * LLM A veces, la expresión de búsqueda en el proceso de pensamiento se inserta en la consulta tal como está, por ejemplo:
 * `""ornith1.5-35ba3b”modelo OR model"`.
 * Las comillas mal formadas harán que Bing trate todo el párrafo como una frase incorrecta/Expresión del operador, el resultado está completamente distorsionado.
 * Aquí solo limpiamos el ruido del formato, conservando las palabras clave y el OR normal y otros términos de búsqueda.
 */
object WebSearchQueryNormalizer {

    private val WHITESPACE = Regex("\\s+")
    private val INVISIBLE = Regex("[\\u200B-\\u200D\\uFEFF]")

    /**
     * coloquial/Prefijos educados y fórmulas de preguntas — Los motores de búsqueda no obtienen ningún beneficio de ellos,En cambio, diluye las palabras clave.
     * Sólo si la consulta es más larga(Como una oración en lugar de una cadena de palabras clave)Eliminar sólo cuando,La palabra clave corta no se mueve.
     */
    private val LEADING_FILLERS = listOf(
        "Disculpe", "por favor ayudame", "ayudame", "compruébalo por mí", "buscame", "Ayúdame a buscar", "problema", "molestarte",
        "puede", "¿Es posible?", "puede ayudarme", "quiero saber", "quiero saber", "ya sabes", "pregunto",
        "Compruébalo", "comprobar", "Buscar", "Buscar", "¿Hay alguna", "¿Qué hay allí?", "que es", "como",
        "please", "can you", "could you", "i want to know", "i wonder", "tell me",
    )

    private val TRAILING_FILLERS = listOf(
        "?",
        "todavía",
        "barra",
        "ah",
        "si",
        "gracias",
        "gracias",
        "gracias",
    )

    /** Sólo hazlo si excede esta longitud."refinamiento del lenguaje hablado",Evite eliminar accidentalmente consultas cortas normales. */
    private const val REFINE_MIN_LENGTH = 12

    /** El límite superior de la longitud de la consulta refinada.(Truncar más allá del límite de la palabra). */
    private const val REFINE_MAX_LENGTH = 64

    fun normalize(raw: String): String {
        var query = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .replace('\u2018', ' ')
            .replace('\u2019', ' ')
            .replace('\u201C', ' ')
            .replace('\u201D', ' ')
            .replace('\u300C', ' ')
            .replace('\u300D', ' ')
            .replace(INVISIBLE, " ")
            .replace(WHITESPACE, " ")
            .trim()

        // Cuando el número de comillas no está emparejado o el modelo envuelve toda la consulta en varias capas de comillas,
        // Elimine las comillas dobles ASCII para evitar que los motores de búsqueda introduzcan un estado de análisis de frases incorrecto.
        val quoteCount = query.count { it == '"' }
        if (quoteCount % 2 != 0 || query.startsWith('"') || query.endsWith('"')) {
            query = query.replace("\"", "")
        }

        return query.replace(WHITESPACE, " ").trim()
    }

    /**
     * Refinamiento de consultas — poner"como una frase"La consulta converge en una cadena de palabras clave.,Mejorar la precisión de la búsqueda.
     *
     * principios de diseño:
     *  1. conservador — Sólo cuando la consulta es obviamente más larga(≥ [REFINE_MIN_LENGTH] personaje)habilitado cuando;
     *     corto keyword(Como por ejemplo "DeepSeek V4 Flash modelo")Déjalo como está;
     *  2. Eliminar solo el ruido hablado — Deshazte de las palabras amables al principio de las frases./Clichés de preguntas, partículas modales al final de la oración,sin participio/eliminar palabras vacías
     *     (Existe un alto riesgo de eliminación accidental de palabras vacías en chino,Prefiero ser conservador);
     *  3. Truncamiento extralargo — exceder [REFINE_MAX_LENGTH] truncar en los límites de las palabras,Evite que todo el párrafo se utilice como consulta.
     *
     * Nota:Esta función es idempotente. — refine(refine(x)) == refine(x).
     */
    fun refine(raw: String): String {
        var query = normalize(raw)
        if (query.length < REFINE_MIN_LENGTH) return query

        // 1. Eliminar palabras de relleno del comienzo de las oraciones.(bucle,Manejo de superposiciones,Como por ejemplo"Por favor compruébalo por mí")
        var changed = true
        while (changed) {
            changed = false
            for (filler in LEADING_FILLERS) {
                if (query.startsWith(filler, ignoreCase = true) && query.length > filler.length) {
                    query = query.substring(filler.length).trimStart(' ', ',', ',', '.', '?', '?', ':', ':')
                    changed = true
                }
            }
        }

        // 2. Eliminar partículas modales al final de las oraciones.(carácter único,Solo ve a un nivel)
        for (filler in TRAILING_FILLERS) {
            if (query.endsWith(filler) && query.length > filler.length) {
                query = query.dropLast(filler.length).trimEnd(' ', ',', ',', '.', '?', '?')
                break
            }
        }

        // 3. Truncar en el límite de la palabra para longitudes extremadamente largas
        if (query.length > REFINE_MAX_LENGTH) {
            val cut = query.take(REFINE_MAX_LENGTH)
            val boundary = cut.lastIndexOf(' ')
            query = if (boundary > REFINE_MAX_LENGTH / 2) cut.substring(0, boundary).trim() else cut.trim()
        }

        return query.ifBlank { normalize(raw) }
    }
}
