package io.zer0.muse.tools

import io.zer0.ai.core.ToolDefinition

/**
 * Lightweight boundary for resumed/background tool calls.
 * A turn may outlive dynamic MCP/skill registration, so callers must validate
 * the current registry before invoking ToolRegistry directly.
 */
class ToolRouteExecutionGuard(private val registry: ToolRegistry) {
    fun validateLocal(name: String, snapshot: ToolRouteSnapshot? = null): String? {
        val current = registry.listTools().firstOrNull { it.name == name }
            ?: return "La herramienta no existe o se ha cerrado sesión.: $name"
        if (snapshot != null) {
            when (val route = snapshot.routeFor(name)) {
                null -> return "La herramienta no está expuesta en la ruta actual.: $name"
                is ToolRouteSnapshot.Route.Skill -> return "El recorrido de la herramienta se ha cambiado a skill: $name"
                ToolRouteSnapshot.Route.Local -> {
                    val expected = snapshot.definitions.firstOrNull { it.name == name }
                    // Compare against the same normalized ToolDefinition that was exposed to the model.
                    // ToolDef.rawParametersJsonSchema is null for built-in tools whose schema is generated
                    // from parameters/required/parameterTypes; comparing it directly would reject every
                    // such tool as "definition changed" on resumed execution.
                    val currentDefinition = registry.listToolsAsToolDefinitions()
                        .firstOrNull { it.name == name }
                    if (expected != null &&
                        (currentDefinition == null || !definitionMatches(expected, currentDefinition))
                    ) {
                        return "Se ha cambiado la definición de la herramienta y se rechaza la ejecución.: $name"
                    }
                }
            }
        }
        return null
    }

    suspend fun executeFromJson(
        name: String,
        argumentsJson: String,
        snapshot: ToolRouteSnapshot? = null,
        executionContext: ToolExecutionContext? = null,
    ): String {
        validateLocal(name, snapshot)?.let { return "Error: $it" }
        return if (executionContext == null) {
            registry.executeFromJson(name, argumentsJson)
        } else {
            registry.executeFromJson(name, argumentsJson, executionContext)
        }
    }

    private fun definitionMatches(expected: ToolDefinition, current: ToolDefinition): Boolean = expected.name == current.name &&
        expected.description == current.description &&
        expected.parametersJsonSchema == current.parametersJsonSchema
}
