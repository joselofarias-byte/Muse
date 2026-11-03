package io.zer0.muse.tools

import io.zer0.common.Logger
import io.zer0.common.resultOf

/**
 * v1.200: Agregación de resultados multiagente y resolución de conflictos.
 *
 * Cuando varios agentes dan resultados diferentes para la misma tarea,Unifique la producción a través de las siguientes estrategias:
 * - Merge: Empalme por fuente,Preservar todas las perspectivas
 * - Vote: clasificar/voto mayoritario de respuesta corta
 * - ExpertReview: por longitud/La confianza selecciona el resultado único más detallado
 * - ConfidenceWeighted: Fusionar ponderado por la confianza(Resultados requeridos confidence)
 * - Consensus: Intenta encontrar consenso primero,Cuando no hay consenso, se degrada a Merge y se marcan las diferencias.
 * - LlmReview: Llame a un LLM independiente para revisar exhaustivamente a todos los candidatos,salida de fusión de salida
 *               llmReviewer Degradado a cuando falta o no se puede llamar ExpertReview
 */
object AgentResultAggregator {

    /**
     * La unidad más pequeña que se puede agregar.
     *
     * @param source Identificación de fuente(Nombre del asistente/Nodo de equipo id)
     * @param content texto del resultado
     * @param confidence nivel de confianza opcional 0..1
     */
    data class Candidate(
        val source: String,
        val content: String,
        val confidence: Float? = null,
    )

    /**
     * Resultados agregados.
     *
     * @param output texto de salida final
     * @param strategy estrategias utilizadas
     * @param conflicts Puntos de conflicto detectados(Vacío si no hay conflicto)
     * @param candidates lista de candidatos originales
     */
    data class Aggregation(
        val output: String,
        val strategy: Strategy,
        val conflicts: List<String> = emptyList(),
        val candidates: List<Candidate> = emptyList(),
    ) {
        val hasConflict: Boolean get() = conflicts.isNotEmpty()
    }

    enum class Strategy {
        MERGE,
        VOTE,
        EXPERT_REVIEW,
        CONFIDENCE_WEIGHTED,
        CONSENSUS,
        LLM_REVIEW,
    }

    /**
     * Resultados agregados de los candidatos.
     *
     * @param candidates Lista de resultados de candidatos
     * @param strategy estrategia de agregación
     * @param question pregunta original/Tarea(Se utiliza para juzgar la similitud de las respuestas al votar/contexto de revisión de LLM,Opcional)
     * @param llmReviewer LLM Revisar la devolución de llamada,solo [Strategy.LLM_REVIEW] Usar.
     *        Pasar en la lista de candidatos frente a la pregunta original,Devolver el texto fusionado completo;Devolver un valor nulo se considera un error de revisión.,Rebajado a EXPERT_REVIEW.
     *        Logger.w cuando es nulo y baja a EXPERT_REVIEW.
     */
    suspend fun aggregate(
        candidates: List<Candidate>,
        strategy: Strategy = Strategy.MERGE,
        question: String? = null,
        llmReviewer: (suspend (List<Candidate>, String?) -> String?)? = null,
    ): Aggregation {
        val valid = candidates.filter { it.content.isNotBlank() }
        if (valid.isEmpty()) {
            return Aggregation(output = "", strategy = strategy, candidates = candidates)
        }
        if (valid.size == 1) {
            return Aggregation(output = valid.first().content, strategy = strategy, candidates = candidates)
        }

        return when (strategy) {
            Strategy.MERGE -> merge(valid)
            Strategy.VOTE -> vote(valid, question)
            Strategy.EXPERT_REVIEW -> expertReview(valid)
            Strategy.CONFIDENCE_WEIGHTED -> confidenceWeighted(valid)
            Strategy.CONSENSUS -> consensus(valid)
            Strategy.LLM_REVIEW -> llmReview(valid, question, llmReviewer)
        }
    }

    /**
     * LLM Estrategia de revisión integral.
     *
     * - llmReviewer es nulo: registre Logger.w y luego baje a EXPERT_REVIEW
     * - llmReviewer La llamada devuelve texto nulo/vacío.:Después de iniciar sesión en Logger.w, baje a EXPERT_REVIEW
     * - Al regresar normalmente,Salida de texto fusionado LLM,Y márcalo en los conflictos. "Revisión completa por LLM"
     */
    private suspend fun llmReview(
        candidates: List<Candidate>,
        question: String?,
        llmReviewer: (suspend (List<Candidate>, String?) -> String?)?,
    ): Aggregation {
        if (llmReviewer == null) {
            Logger.w("AgentResultAggregator", "llm_review desaparecido llmReviewer,degradado a expert_review")
            return expertReview(candidates)
        }
        // H-LLM1: usar resultOf{} Envolver llamada suspendida,Lanzamiento correcto CancellationException
        val reviewed = resultOf { llmReviewer(candidates, question) }
            .onError { msg, t ->
                Logger.w("AgentResultAggregator", "llm_review excepción de llamada,degradado a expert_review: $msg", t)
            }
            .getOrNull()
        if (reviewed.isNullOrBlank()) {
            Logger.w("AgentResultAggregator", "llm_review Devolver resultado vacío,degradado a expert_review")
            return expertReview(candidates)
        }
        return Aggregation(
            output = reviewed.trim(),
            strategy = Strategy.LLM_REVIEW,
            conflicts = listOf("Revisión completa por LLM(candidato ${candidates.size} un)"),
            candidates = candidates,
        )
    }

    /** Empalmar a todos los candidatos,Marque la fuente. */
    private fun merge(candidates: List<Candidate>): Aggregation {
        val output = buildString {
            appendLine("Resumen de resultados de múltiples agentes:")
            candidates.forEachIndexed { idx, c ->
                appendLine()
                appendLine("--- Fuente ${idx + 1}: ${c.source} ${c.confidence?.let { "(confianza ${"%.2f".format(it)})" } ?: ""}---")
                appendLine(c.content)
            }
        }.trimEnd()
        return Aggregation(output = output, strategy = Strategy.MERGE, candidates = candidates)
    }

    /**
     * Estrategia de votación.
     *
     * respuesta corta(<=50 palabra)Grupo después de la normalización,Devuelve la respuesta más votada.
     * Si el porcentaje de votos más alto es menor que 0.5,considerado un conflicto,Anotación de diferencias en la producción.
     */
    private fun vote(candidates: List<Candidate>, question: String?): Aggregation {
        val shortCandidates = candidates.filter { it.content.length <= 150 }
        val target = if (shortCandidates.size >= 2) shortCandidates else candidates

        val groups = target.groupBy { normalizeForVote(it.content) }
        val ranked = groups.entries.sortedByDescending { it.value.size }
        val winner = ranked.first()
        val total = target.size
        val winnerRatio = winner.value.size.toFloat() / total

        val conflicts = if (winnerRatio < 0.5f) {
            listOf("La votación no formó una mayoría clara.:votación más alta ${winner.value.size}/$total (${"%.0f".format(winnerRatio * 100)}%)")
        } else {
            emptyList()
        }

        val output = buildString {
            appendLine("Resultados de la votación(total $total billete):")
            ranked.forEachIndexed { idx, (answer, votes) ->
                appendLine("${idx + 1}. [${votes.size} billete] $answer")
            }
            if (conflicts.isNotEmpty()) {
                appendLine()
                appendLine("Nota: ${conflicts.first()}")
            }
            appendLine()
            appendLine("Adopción final: ${winner.key}")
        }.trimEnd()

        return Aggregation(output = output, strategy = Strategy.VOTE, conflicts = conflicts, candidates = candidates)
    }

    /** Revisión de expertos:Seleccione el resultado con el contenido más largo o el nivel de confianza más alto como respuesta autorizada. */
    private fun expertReview(candidates: List<Candidate>): Aggregation {
        val best = candidates.maxWithOrNull(
            compareBy<Candidate> {
                it.confidence ?: 0f
            }.thenBy { it.content.length },
        ) ?: candidates.first()

        val output = buildString {
            appendLine("Resultados de la revisión de expertos(Seleccionado de ${best.source}):")
            appendLine()
            appendLine(best.content)
            if (candidates.size > 1) {
                appendLine()
                appendLine("Fuentes de referencia: ${candidates.map { it.source }.distinct().joinToString(", ")}")
            }
        }.trimEnd()

        return Aggregation(output = output, strategy = Strategy.EXPERT_REVIEW, candidates = candidates)
    }

    /** ponderado por la confianza:Ordene los resultados con confianza por peso,Degenera en Merge cuando no hay confianza. */
    private fun confidenceWeighted(candidates: List<Candidate>): Aggregation {
        val withConfidence = candidates.filter { it.confidence != null }
        if (withConfidence.size < 2) {
            Logger.w("AgentResultAggregator", "confidence_weighted El candidato carece de confianza,degenerar en merge")
            return merge(candidates)
        }
        val sorted = withConfidence.sortedByDescending { it.confidence }
        val totalConfidence = sorted.sumOf { (it.confidence ?: 0f).toDouble() }.toFloat().coerceAtLeast(0.0001f)

        val output = buildString {
            appendLine("Resumen ponderado de confianza(peso total ${"%.2f".format(totalConfidence)}):")
            sorted.forEach { c ->
                val weight = ((c.confidence ?: 0f) / totalConfidence * 100).toInt()
                appendLine("- [${c.source}] peso $weight%: ${c.content.take(200)}")
            }
            appendLine()
            appendLine("Resultado de confianza más alto(${sorted.first().confidence?.let { "%.2f".format(it) } ?: "-"}):")
            appendLine(sorted.first().content)
        }.trimEnd()

        return Aggregation(output = output, strategy = Strategy.CONFIDENCE_WEIGHTED, candidates = candidates)
    }

    /**
     * Estrategia de consenso.
     *
     * Primero intente encontrar una respuesta completamente consistente.;Si no se encuentra,Detectar diferencias y bajar a Merge.
     */
    private fun consensus(candidates: List<Candidate>): Aggregation {
        val groups = candidates.groupBy { normalizeForVote(it.content) }
        val majority = groups.maxByOrNull { it.value.size }

        return if (majority != null && majority.value.size.toFloat() / candidates.size >= 0.5f) {
            Aggregation(
                output = majority.key,
                strategy = Strategy.CONSENSUS,
                candidates = candidates,
            )
        } else {
            val conflicts = detectConflicts(candidates)
            val merged = merge(candidates)
            val output = buildString {
                appendLine("sin acuerdo,Degradado a salida resumida. Se detectaron los siguientes desacuerdos:")
                conflicts.forEach { appendLine("- $it") }
                appendLine()
                append(merged.output)
            }.trimEnd()
            Aggregation(output = output, strategy = Strategy.CONSENSUS, conflicts = conflicts, candidates = candidates)
        }
    }

    /** Texto normalizado para comparación de votos.(Eliminar espacios en blanco, puntuación y minúsculas). */
    private fun normalizeForVote(text: String): String {
        return text.trim()
            .lowercase()
            .replace(Regex("[\\s\\n\\r\\t]+", RegexOption.MULTILINE), " ")
            .replace(Regex("[,. ! ?.,!?;:;;\"'“”]"), "")
            .trim()
    }

    /** Detección sencilla de conflictos:Devuelve un resumen de las diferencias entre los resultados de los candidatos. */
    private fun detectConflicts(candidates: List<Candidate>): List<String> {
        val summaries = candidates.mapIndexed { idx, c ->
            "Fuente ${idx + 1}(${c.source}): ${c.content.take(80)}${if (c.content.length > 80) "..." else ""}"
        }
        return if (summaries.size <= 1) emptyList() else listOf(summaries.joinToString("\n"))
    }
}
