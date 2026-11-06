package io.zer0.muse.tools

import io.zer0.ai.core.ToolDefinition
import io.zer0.muse.data.skill.SkillEntity

/**
 * El único criterio de filtrado para "las herramientas que el modelo realmente puede utilizar en esta ronda".
 *
 * Cuando el mismo conjunto de reglas se distribuye en tres lugares, aparecerán tres cantidades:function schema Problema
 * ([io.zer0.muse.ui.chat.ChatStreamCoordinator]), índice de herramienta de palabra rápida
 * ([io.zer0.muse.transformer.SystemPromptAssembler]),find_tools Buscar catálogo
 * ([FindToolsTool]). Si escribe un conjunto de palabras clave y un directorio de búsqueda respectivamente, la cantidad reportada definitivamente será mayor que la cantidad real exigible.
 * El modelo forma entonces una percepción falsa de las capacidades. La regla aquí solo está escrita una vez y ajustada en tres lugares.
 */
object ToolSchemaRules {

    /** Filtrar por: Herramientas del asistente actual/Habilidades/MCP Vincular la política de aprobación de la herramienta al usuario. */
    data class Criteria(
        val configuredToolIds: Set<String> = emptySet(),
        val mcpServerIds: Set<String> = emptySet(),
        val configuredSkillIds: Set<String> = emptySet(),
        val deniedToolNames: Set<String> = emptySet(),
    )

    /**
     * Si la herramienta local entra en esta ronda de esquema.
     *
     * - vacío [Criteria.configuredToolIds] Mantener la semántica antigua (todas las herramientas integradas están disponibles);
     * - find_tools Es un canal de finalización jerárquicamente restringido que permanece disponible en cualquier lista blanca;
     * - Herramientas explícitamente deshabilitadas por el usuario (ALWAYS_DENY) se bloquea en la capa de exposición y no se deja hasta la capa de ejecución para rechazarlo. ——
     *   De lo contrario, el modelo verá la herramienta, la llamará, será rechazada en cada ronda y luego la ajustará en la siguiente ronda;
     * - Cuando no hay ningún servicio MCP vinculado,`mcp_<server>__<tool>` No se emitirá.
     */
    fun localSelected(name: String, criteria: Criteria): Boolean {
        if (name in criteria.deniedToolNames) return false
        val selectedByTool =
            criteria.configuredToolIds.isEmpty() || name in criteria.configuredToolIds ||
                name == FindToolsTool.TOOL_NAME
        val isMcpTool = name.startsWith("mcp_") && name.contains("__")
        val selectedByMcp = !isMcpTool || criteria.mcpServerIds.any { name.startsWith("mcp_${it}__") }
        return selectedByTool && selectedByMcp
    }

    /**
     * Si la habilidad entra en el esquema de esta ronda.
     *
     * Se deben eliminar las habilidades con el mismo nombre que las herramientas locales: después de la deduplicación, la definición conservará la versión local. Si la habilidad todavía está en la ruta,
     * "El modelo ve la definición local y realmente ejecuta la implementación de la habilidad" (conjuntos de parámetros inconsistentes y semántica de comportamiento inconsistente).
     * `channel_*` ChannelToolFactory solo inyecta el conjunto de tres piezas del chat grupal en el contexto del chat grupal y la sesión principal debe eliminarse.
     * De lo contrario, puede utilizar la versión de habilidad para hablar en cualquier chat grupal como cualquier agente, evitando la protección de vinculación de identidad.
     */
    fun skillSelected(id: String, localToolNames: Set<String>, criteria: Criteria): Boolean {
        if (id in localToolNames || id.startsWith("channel_")) return false
        return criteria.configuredSkillIds.isEmpty() || id in criteria.configuredSkillIds
    }

    /** Filtrar definiciones de herramientas locales (el mismo conjunto de reglas en el lado de emisión y en el lado de conteo). */
    fun selectLocalTools(all: List<ToolDefinition>, criteria: Criteria): List<ToolDefinition> =
        all.filter { localSelected(it.name, criteria) }

    /** Habilidades de filtrado (deduplicación que preserva el orden y luego filtrado según reglas). */
    fun selectSkills(all: List<SkillEntity>, localToolNames: Set<String>, criteria: Criteria): List<SkillEntity> =
        all.distinctBy { it.id }.filter { skillSelected(it.id, localToolNames, criteria) }

    /** El nombre real de la herramienta emitido en esta ronda de esquema: las herramientas locales tienen prioridad sobre las habilidades con el mismo nombre. */
    fun callableNames(localToolNames: Collection<String>, skillIds: Collection<String>): Set<String> {
        val locals = localToolNames.toSet()
        return locals + skillIds.filterNot { it in locals }
    }

    /** Directorios visibles de sesión:[visibleNames] No se realiza ninguna reducción cuando es nulo (no registrado, como rutas antiguas como subagentes). */
    fun visibleSubset(tools: List<ToolRegistry.ToolDef>, visibleNames: Set<String>?): List<ToolRegistry.ToolDef> =
        if (visibleNames == null) tools else tools.filter { it.name in visibleNames }
}
