package io.zer0.muse.tools

import io.zer0.muse.data.experience.ExperienceEntity
import io.zer0.muse.data.experience.ExperienceRepository
import java.util.UUID

/**
 * record_experience Herramientas.
 *
 * Registre la experiencia aprendida en la biblioteca de experiencias.
 */
object RecordExperienceTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "record_experience",
        description = "Registre una experiencia en la base de datos de experiencias. Situaciones aplicables:Los usuarios señalan tus errores, muestran descontento o reiteran algo.;" +
            "Pruebas muchos métodos y encuentras lo que funciona.;Los usuarios dicen \"haz esto la próxima vez\" o \"no hagas aquello\"." +
            "Una frase por elemento,Sea específico.",
        parameters = mapOf(
            "category" to "Required. Category, 2-4 words, e.g. 'tool usage', 'search tips', 'response style'.",
            "content" to "Required. The experience content, concise and direct, one sentence.",
        ),
        required = setOf("category", "content"),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    suspend fun execute(args: Map<String, String>, repo: ExperienceRepository): String {
        val category = args["category"]?.trim()?.replace(Regex("^#+\\s*"), "")
            ?: return "Error: category parameter is required."
        val content = args["content"]?.trim()
            ?: return "Error: content parameter is required."
        if (category.isEmpty() || content.isEmpty()) {
            return "Error: category and content cannot be empty."
        }
        // La herramienta de memoria ya se está ejecutando en la cadena de herramientas para colgar.,Llame a Room para suspender DAO directamente.
        // RunBlocking anidado bloqueará el hilo de ejecución,Realizado antes de rellenar los resultados de la herramienta“Escrito pero aún ejecutándose”espejismo.
        val existing = repo.getAll()
        if (existing.any { it.content == content && it.category.equals(category, ignoreCase = true) }) {
            return "Duplicate: this experience is already recorded."
        }
        val title = content.take(50) + if (content.length > 50) "..." else ""
        val entity = ExperienceEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            content = content,
            category = category,
            source = "llm",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        repo.upsert(entity)
        return "Experience recorded under '$category': $content"
    }
}
