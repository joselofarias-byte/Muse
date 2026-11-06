package io.zer0.muse.tools

import io.zer0.common.Logger
import io.zer0.memory.fact.FactStore
import kotlinx.coroutines.CancellationException

/**
 * v2.x: save_memory Herramientas — El asistente escribe explícitamente un elemento en la memoria a largo plazo.(hechos).
 *
 * con extracción automática(después de la conversación MemoryExtract)diferente:Este es el modelo activo."escríbelo"canal,
 * Los usuarios dicen"recuerda…"Entrega instantánea(source=user_explicit,confidence=1.0).
 * Alcance/El espacio lo inyecta el anfitrión. [ToolExecutionContext] decidir,Los parámetros del modelo no se pueden falsificar;
 * Las escrituras todavía van a FactStore.add para la desensibilización de PII y la fusión de similitudes.
 */
object SaveMemoryTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "save_memory",
        description = "Almacenar un hecho en la memoria a largo plazo del usuario. Cuando un usuario te pide explícitamente que recuerdes algo,o declarar hechos importantes a largo plazo" +
            "(Preferencias, identidad, objetivos, salud, fechas importantes.)cuando se usa. una frase a la vez;" +
            "No guarde pequeñas conversaciones y detalles de misiones únicas.",
        parameters = mapOf(
            "content" to "Required. The fact to remember — concise, one sentence, user's language.",
            "tags" to "Optional. Comma-separated keywords for later retrieval.",
            "importance" to "Optional. 0=normal, 1=important, 2=critical (health/safety). Default 0.",
            "category" to "Optional. preference/identity/event/relationship/goal/medical/other.",
        ),
        required = setOf("content"),
        category = "built-in",
        parameterTypes = mapOf("importance" to "integer"),
        // Mismo calibre que pin_memory:La escritura en memoria a largo plazo es sensible a la privacidad,volver HIGH
        riskLevel = ToolRiskLevel.HIGH,
    )

    suspend fun execute(args: Map<String, String>, factStore: FactStore, executionContext: ToolExecutionContext): String {
        val content = args["content"]?.trim().orEmpty()
        if (content.isEmpty()) return "Error: content parameter is required."
        val tags = args["tags"]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val importance = (args["importance"]?.trim()?.toIntOrNull() ?: 0).coerceIn(0, 2)
        val category = args["category"]?.trim()?.takeIf { it.isNotEmpty() } ?: "general"
        val id =
            try {
                factStore.add(
                    FactStore.Fact(
                        fact = content,
                        tags = tags,
                        importance = importance,
                        category = category,
                        source = "user_explicit",
                        confidence = 1.0f,
                    ),
                    scope = executionContext.scope,
                    spaceId = executionContext.spaceId,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Logger.e("SaveMemoryTool", "save_memory Error de escritura: ${error.message}", error)
                return "Error: failed to save memory: ${error.message ?: "storage error"}"
            }
        return if (id > 0) {
            "Saved to long-term memory (id: $id)."
        } else {
            "Error: failed to save memory."
        }
    }
}
