package io.zer0.muse.tools

import io.zer0.muse.data.experience.ExperienceRepository

/**
 * recall_experience Herramientas.
 *
 * divulgación progresiva:Devuelve el índice de categoría sin parámetros.,
 * Con un nombre de categoría, se devuelve la experiencia específica de esta categoría.
 */
object RecallExperienceTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "recall_experience",
        description = "Explore la biblioteca de experiencias. Devuelve una descripción general de cada categoría sin parámetros.;Con un nombre de categoría, se devuelve la experiencia específica de esta categoría." +
            "Compruébelo antes de comenzar tareas específicas como escribir código, investigar o analizar problemas. No se requieren conversaciones triviales ni preguntas y respuestas.",
        parameters = mapOf(
            "category" to "Optional. Category name. Omit to get an overview of all categories.",
        ),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    suspend fun execute(args: Map<String, String>, repo: ExperienceRepository): String {
        val category = args["category"]?.trim()
        val all = repo.getAll()
        if (all.isEmpty()) return "Experience library is empty. No experiences recorded yet."

        return if (category.isNullOrEmpty()) {
            // índice de retorno:Agrupar por categoría
            val grouped = all.groupBy { it.category }
            buildString {
                appendLine("Experience Library (${all.size} entries, ${grouped.size} categories):")
                appendLine()
                for ((cat, entries) in grouped) {
                    val snippets = entries.take(3).map { it.content.take(30) }
                    appendLine("# $cat (${entries.size} entries)")
                    appendLine(snippets.joinToString("; "))
                    appendLine()
                }
            }
                .trimEnd()
        } else {
            val matches = all.filter {
                it.category.equals(category, ignoreCase = true) ||
                    it.category.contains(category, ignoreCase = true)
            }
            if (matches.isEmpty()) {
                "No experiences found for category '$category'."
            } else {
                buildString {
                    appendLine("# ${matches.first().category} (${matches.size} entries)")
                    appendLine()
                    for ((i, e) in matches.withIndex()) {
                        appendLine("${i + 1}. ${e.content}")
                    }
                }.trimEnd()
            }
        }
    }
}
