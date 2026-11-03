package io.zer0.muse.tools

import io.zer0.memory.compile.CompiledSectionHit
import io.zer0.memory.fact.FactStore

/**
 * search_memory Herramientas.
 *
 * Deje que LLM recupere activamente la memoria a largo plazo. apoyo:
 *  - Búsqueda de texto completo(FTS4 + CJK 2-gram,dos etapas:Prioridad precisa + cualquier acierto está garantizado)
 *  - Etiquetar coincidencia exacta(OR lógica,Orden descendente por número de coincidencias)
 *  - Filtro de rango de fechas(ISO 8601 cuerda,Opcional)
 *  - Volver al resumen compilado cuando no se puede encontrar la tabla de hechos(Aquellos párrafos de prosa en los que se inyectan palabras clave.)
 */
object SearchMemoryTool {

    /**
     * Puerto de búsqueda de resúmenes compilados.
     *
     * facts La tabla contiene solo hechos estructurados.,El párrafo HOY/SEMANA/LARGO PLAZO inyectado con palabras clave es la prosa resumida por LLM,
     * El texto original a menudo no se encuentra en los hechos. No pueden considerarse como“memoria programada”usar,pero de hecho“Visible para los usuarios”,
     * Por lo tanto, sólo retrocede cuando la búsqueda de hechos está vacía.,E indicar claramente la fuente en los resultados.
     */
    fun interface CompiledSummarySearch {
        /**
         * @param query Cadena de consulta del usuario
         * @param scope alcance de la memoria
         * @param spaceId espacio de memoria
         * @param limit Número máximo de artículos devueltos
         */
        suspend fun search(query: String, scope: String, spaceId: String, limit: Int): List<CompiledSectionHit>
    }

    fun toolDef() = ToolRegistry.ToolDef(
        name = "search_memory",
        description = "Busque en la memoria a largo plazo del usuario datos sobre él. Cuando la respuesta puede depender de sus preferencias, experiencias, identidad, objetivos." +
            "O usado en conversaciones anteriores. Separe varias palabras clave con espacios,Presionará primero“Todos los éxitos”Presione nuevamente“cualquier golpe”Dos rondas de búsquedas;" +
            "Cada resultado viene con fact id,Se puede pasar directamente a delete_memory." +
            "Es posible que algunos recuerdos tempranos hayan salido de la recuperación automática según la frecuencia de uso.(No más inyección automática de contexto),Una búsqueda explícita aún puede recuperarlos.;" +
            "Entonces, si no puede encontrarlo, no concluya directamente que no está en la memoria.",
        parameters = mapOf(
            "query" to "Required. Keywords to search for in memory facts.",
            "tags" to "Optional. Comma-separated tag names (OR logic).",
            "from" to "Optional. ISO 8601 lower bound on the fact's time, inclusive.",
            "to" to "Optional. ISO 8601 upper bound on the fact's time, inclusive.",
            "limit" to "Optional. Max results, default 10.",
        ),
        required = setOf("query"),
        category = "built-in",
        parameterTypes = mapOf("limit" to "integer"),
        riskLevel = ToolRiskLevel.SAFE,
    )

    suspend fun execute(args: Map<String, String>, factStore: FactStore): String =
        executeInternal(args, factStore, null, null)

    suspend fun execute(args: Map<String, String>, factStore: FactStore, executionContext: ToolExecutionContext): String =
        executeInternal(args, factStore, executionContext, null)

    /**
     * Entrada de ejecución con reserva de resumen de compilación.
     *
     * @param compiledSearch Puerto de búsqueda de resumen cuando la tabla de hechos está completamente vacía;Cuando es nulo, no se realiza ninguna reserva.
     */
    suspend fun execute(
        args: Map<String, String>,
        factStore: FactStore,
        executionContext: ToolExecutionContext,
        compiledSearch: CompiledSummarySearch?,
    ): String = executeInternal(args, factStore, executionContext, compiledSearch)

    private suspend fun executeInternal(
        args: Map<String, String>,
        factStore: FactStore,
        executionContext: ToolExecutionContext?,
        compiledSearch: CompiledSummarySearch?,
    ): String {
        val query = args["query"]?.trim().orEmpty()
        if (query.isEmpty()) return "Error: query parameter is required."

        val tags = args["tags"]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        val from = args["from"]?.trim()?.takeIf { it.isNotEmpty() }
        val to = args["to"]?.trim()?.takeIf { it.isNotEmpty() }
        val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 10

        val dateRange = if (from != null || to != null) {
            FactStore.DateRange(from = from, to = to)
        } else {
            null
        }
        val facts = searchFacts(factStore, query, tags, dateRange, limit, executionContext)

        if (facts.isEmpty()) {
            val hits = searchCompiledSummary(query, limit, executionContext, compiledSearch)
            return if (hits.isEmpty()) "No matching memories found." else renderCompiledHits(hits)
        }

        return buildString {
            appendLine("Found ${facts.size} memory fact(s):")
            appendLine()
            facts.forEachIndexed { index, fact ->
                appendLine("${index + 1}. ${fact.fact}")
                appendLine("   ID: ${fact.id}")
                if (fact.tags.isNotEmpty()) {
                    appendLine("   Tags: ${fact.tags.joinToString(", ")}")
                }
                if (fact.category.isNotBlank() && fact.category != "general") {
                    appendLine("   Category: ${fact.category}")
                }
                if (fact.confidence < 1.0f) {
                    appendLine("   Confidence: ${fact.confidence}")
                }
                fact.time?.let { appendLine("   Time: $it") }
                appendLine()
            }
        }.trimEnd()
    }

    /** Dependiendo de si existe un contexto/etiqueta de ejecución, se realiza una búsqueda de etiquetas o una búsqueda de texto completo en dos etapas, respectivamente. */
    private suspend fun searchFacts(
        factStore: FactStore,
        query: String,
        tags: List<String>,
        dateRange: FactStore.DateRange?,
        limit: Int,
        executionContext: ToolExecutionContext?,
    ): List<FactStore.Fact> = when {
        executionContext != null && tags.isNotEmpty() -> factStore.searchByTagsScoped(
            queryTags = tags,
            scope = executionContext.scope,
            spaceId = executionContext.spaceId,
            dateRange = dateRange,
            limit = limit,
        )
        executionContext != null -> factStore.searchFullTextScopedTwoStage(
            query = query,
            scope = executionContext.scope,
            spaceId = executionContext.spaceId,
            limit = limit,
        )
        tags.isNotEmpty() -> factStore.searchByTags(queryTags = tags, dateRange = dateRange, limit = limit)
        else -> factStore.searchFullTextTwoStage(query, limit)
    }

    /**
     * Consulte el resumen compilado cuando la tabla de hechos esté completamente vacía.
     *
     * Sin contexto de ejecución no hay alcance,No adivines el valor predeterminado:Ese tipo de ruta de llamadas preferiría no retroceder.,
     * No lea los resúmenes de otras personas entre los asistentes.
     */
    private suspend fun searchCompiledSummary(
        query: String,
        limit: Int,
        executionContext: ToolExecutionContext?,
        compiledSearch: CompiledSummarySearch?,
    ): List<CompiledSectionHit> {
        if (compiledSearch == null || executionContext == null) return emptyList()
        val hits = compiledSearch.search(query, executionContext.scope, executionContext.spaceId, limit)
        return hits.take(limit)
    }

    /** Representación de visitas resumidas:Marcar artículo por artículo“compilar resumen”con el segmento,Distinguirse claramente de los hechos. */
    private fun renderCompiledHits(hits: List<CompiledSectionHit>): String = buildString {
        appendLine("De hecho, no hay coincidencia en la memoria.;Lo siguiente es un extracto del resumen compilado.(La fuente está marcada como“compilar resumen·Nombre de la sección”,No es una entrada en la tabla de hechos.,No fact id):")
        appendLine()
        hits.forEachIndexed { index, hit ->
            appendLine("${index + 1}. [compilar resumen·${hit.sectionKey}] ${hit.text}")
            appendLine()
        }
    }.trimEnd()
}
