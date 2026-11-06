package io.zer0.memory.deep

import io.zer0.memory.fact.FactStore

/**
 * D6 Número 2: DeepMemory Extraiga la capa de verificación de atomicidad de la salida.
 *
 * fondo: Extracto prompt([io.zer0.memory.prompt.FactExtractionPrompt])Solicitado"Una afirmación por hecho",
 * Sin embargo, el resultado de LLM no está garantizado. Haga una verificación determinista aquí antes de escribir en la tabla de hechos,poner"Los conectivos concatenan múltiples afirmaciones."entrada
 * Dividido de forma conservadora en varias tiras.,inferior"un hecho un párrafo"de acumulación de stock.
 *
 * Estrategia(Preferiría no desmantelar,No lastimes accidentalmente):
 *  1. Sólo reconoce los conectivos fuertes/punto y coma —— “;;”, y“y/y/al mismo tiempo”con comas“Además/Además/y”;
 *     Comas ordinarias en oraciones,“y/o”no desmantelar(Como por ejemplo“Me gusta la fotografía y los viajes.”una sola preferencia).
 *  2. Después de dividir, cada sección debe ≥ [MIN_PART_CHARS] palabra,El número de párrafos cae en 2..[MAX_PARTS],De lo contrario renunciar a la división,Déjalo como está.
 *  3. Al menos dos párrafos contienen cada uno morfemas predicados.(o palabra inglesa),evitar poner“enumeración de sustantivos”Eliminado por error.
 *  4. Demasiado corto en general( < [MIN_TOTAL_CHARS])No procesado,Evite frases cortas por error.
 *  5. Cualquier situación que no cumpla con las condiciones. → Devuelve el texto original tal como está.,Nunca pierdas una palabra.
 *
 * No cambies la tabla de hechos. schema(predicate Incluido en el número 3);Las entradas divididas copian metadatos del mismo origen
 * (tags/time/importance/category/confidence/source/entityKey/sessionId espera).
 * Relación con el mensaje de extracción: prompt si"capa de persuasión",Esta categoría es"bolsillo inferior",Los dos no son sustitutos el uno del otro.
 */
object FactAtomicityValidator {

    /** El texto general por debajo de esta longitud no está dividido(Evite frases cortas accidentales). */
    const val MIN_TOTAL_CHARS = 6

    /** La longitud mínima de cada segmento después de la división. */
    const val MIN_PART_CHARS = 4

    /** Un solo hecho puede dividirse en varios párrafos como máximo.,Exceder el límite se considera una salida anormal.,No lo desmanteles. */
    const val MAX_PARTS = 5

    /** Resultados de la verificación. */
    data class Result(
        val facts: List<FactStore.Fact>,
        /** El número de entradas originales que se dividieron. */
        val splitCount: Int,
        /** Número neto de entradas agregadas después de la división(facts.size - entrada size). */
        val addedCount: Int,
    )

    /**
     * Realizar una verificación de atomicidad en la salida extraída,Devuelve la lista dividida de hechos.
     * Las entradas que no se pueden dividir de forma segura se dejan intactas,Por lo tanto, al resultado nunca le falta información.
     */
    fun enforce(facts: List<FactStore.Fact>): Result {
        if (facts.isEmpty()) return Result(facts, 0, 0)
        val out = ArrayList<FactStore.Fact>(facts.size)
        var splitCount = 0
        for (fact in facts) {
            val parts = splitAssertions(fact.fact)
            if (parts.size >= 2) {
                parts.forEach { part -> out += fact.copy(fact = part) }
                splitCount++
            } else {
                out += fact
            }
        }
        return Result(out, splitCount, out.size - facts.size)
    }

    /**
     * Intente dividir un texto de hechos en varias afirmaciones.
     * Devuelve una lista de un solo elemento cuando no se cumplen las condiciones de división(es decir, texto original),La persona que llama utiliza esto para determinar si se ha producido una división.
     */
    fun splitAssertions(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.length < MIN_TOTAL_CHARS) return listOf(trimmed)
        if (!SPLIT_POINTS.containsMatchIn(trimmed)) return listOf(trimmed)

        val parts = trimmed.split(SPLIT_POINTS)
            .map { cleanPart(it) }
            .filter { it.isNotEmpty() }
        if (parts.size < 2 || parts.size > MAX_PARTS) return listOf(trimmed)
        if (parts.any { it.length < MIN_PART_CHARS }) return listOf(trimmed)
        // Al menos dos párrafos contienen cada uno un predicado.,excluir“enumeraciones de sustantivos separadas por conectivos”lesión accidental
        if (parts.count { partHasPredicate(it) } < 2) return listOf(trimmed)

        val distinct = parts.distinct()
        if (distinct.size < 2) return listOf(trimmed)
        return distinct
    }

    /** Quita espacios y puntuación sobrante en los extremos del fragmento. */
    private fun cleanPart(part: String): String =
        part.trim().trimEnd('。', '，', '；', ';', '.', '、', '！', '!', '？', '?', '¿', '¡', ',').trim()

    /** El fragmento parece una afirmación si contiene un verbo o predicado conocido. */
    private fun partHasPredicate(part: String): Boolean {
        val lower = part.lowercase()
        return PREDICATE_TOKENS.any { lower.contains(it) }
    }

    /**
     * Conectivos fuertes. La "y" suelta no parte (es una preferencia única: "fotografía y viajes").
     * Las alternativas más largas van primero para que ", y además" no se quede en "y además".
     */
    private val SPLIT_POINTS = Regex(
        "(?:;|,\\s*y además|,\\s*además|,\\s*al mismo tiempo|,\\s*así como|\\by además\\b|\\bal mismo tiempo\\b)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Predicados en español e inglés. No vale cualquier palabra latina: en español casi todo
     * el texto lo es y la puerta dejaría de filtrar enumeraciones de sustantivos.
     */
    private val PREDICATE_TOKENS = listOf(
        "gusta", "ama", "odia", "teme", "espera", "quiere", "planea", "prepara",
        "trabaja", "vive", "estudia", "necesita", "alérg", "vegetariano",
        "busca", "escribe", "corre", "nada", "viaja", "escala",
        "works", "likes", "lives", "wants", "needs",
    )
}
