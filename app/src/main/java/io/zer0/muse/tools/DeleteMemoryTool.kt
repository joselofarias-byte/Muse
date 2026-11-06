package io.zer0.muse.tools

import io.zer0.memory.fact.FactStore

/**
 * v2.x: delete_memory Herramientas — El asistente borra una memoria a largo plazo(hechos).
 *
 * Orientación:Prioridad `id`(search_memory traído de vuelta en los resultados);De lo contrario utilizar `match` Las palabras clave están actualmente
 * Alcance+Tome el más relevante del espacio. Borrar y listo [FactStore.delete]:Qing FTS, gráfico de conocimiento de Qing
 * Lado huérfano, lápida récord(Evitar que la compilación de resumen posterior agregue contenido eliminado"resurrección").
 *
 * Alcance/El espacio lo inyecta el anfitrión. [ToolExecutionContext] decidir;Rechazar al comparar hechos transespaciales,
 * Los parámetros del modelo no pueden salirse de los límites. No hay una segunda confirmación para la eliminación(Solicitud explícita del usuario):Si lo elimina por error, simplemente guarde_memoria nuevamente.
 */
object DeleteMemoryTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "delete_memory",
        description = "Eliminar un hecho de la memoria a largo plazo del usuario. Cuando un usuario dice que cierta información es incorrecta o está desactualizada,O usado cuando te piden que olvides." +
            "Preciso id(search_memory Dado en los resultados.)o concordancia de palabras clave,Eliminar el espacio de memoria actual" +
            "El que mejor combine. Al corregir hechos,Elimina el antiguo primero,Luego use save_memory para guardar la versión corregida.",
        parameters = mapOf(
            "id" to "Optional. Exact fact id from search_memory results.",
            "match" to "Optional. Keywords to locate the fact when the id is unknown.",
        ),
        required = emptySet(),
        category = "built-in",
        parameterTypes = mapOf("id" to "integer"),
        // Mismo calibre que pin_memory:Los amnésticos a largo plazo son sensibles a la privacidad,volver HIGH
        riskLevel = ToolRiskLevel.HIGH,
    )

    suspend fun execute(args: Map<String, String>, factStore: FactStore, executionContext: ToolExecutionContext): String {
        val idArg = args["id"]?.trim()?.toLongOrNull()
        val match = args["match"]?.trim().orEmpty()
        val target = when {
            idArg != null && idArg > 0 -> factStore.getById(idArg)
            match.isNotEmpty() -> factStore.searchFullTextScoped(
                query = match,
                scope = executionContext.scope,
                spaceId = executionContext.spaceId,
                limit = 5,
            ).firstOrNull()
            else -> null
        } ?: return "No matching memory found to delete."

        // Alcance/chequeo de espacio — Las herramientas de préstamo no pueden salirse de los límites y eliminar otros espacios./hechos del asistente
        if (target.scope != executionContext.scope || target.spaceId != executionContext.spaceId) {
            return "Error: the matched memory belongs to another memory space and cannot be deleted here."
        }

        val assistantId = executionContext.assistantId?.takeIf { it.isNotBlank() }
            ?: if (executionContext.scope == "main") "default" else executionContext.scope
        val removed = factStore.delete(
            id = target.id,
            assistantId = assistantId,
            scope = executionContext.scope,
            spaceId = executionContext.spaceId,
        )
        return if (removed) {
            "Deleted from long-term memory: \"${target.fact}\" (id: ${target.id})."
        } else {
            "Error: failed to delete the memory."
        }
    }
}
