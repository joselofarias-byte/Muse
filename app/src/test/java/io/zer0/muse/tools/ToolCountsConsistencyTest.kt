package io.zer0.muse.tools

import io.zer0.ai.core.ToolDefinition
import io.zer0.muse.data.skill.SkillEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La coherencia de la herramienta de tres niveles cuenta con el nivel de consumo real.
 *
 * El primer nivel debe ser igual a la cantidad de herramientas de esquema emitidas por ChatStreamCoordinator, y el segundo nivel debe ser igual a find_tools
 * El número de consultas vacías del tercer nivel es igual al número total de registros locales. Además, utilice "implementación de filtrado antes de los cambios" como criterio para comparar uno por uno.
 * Se asegura que esta convergencia sólo unificó el calibre y no cambió los resultados del filtrado.
 */
class ToolCountsConsistencyTest {

    private fun localTool(name: String, category: String = "built-in") = ToolRegistry.ToolDef(
        name = name,
        description = "$name desc",
        parameters = mapOf("p" to "param"),
        required = setOf("p"),
        category = category,
        riskLevel = ToolPermissionResolver.riskLevelFor(name),
    )

    private fun aiDef(name: String) = ToolDefinition(
        name = name,
        description = "$name desc",
        parametersJsonSchema = "{\"type\":\"object\",\"properties\":{}}",
    )

    private fun skill(id: String) = SkillEntity(id = id, name = id, description = "$id desc")

    /** Registro local (contiene find_tools y herramientas para ambos servicios MCP). */
    private val registered = listOf(
        localTool("find_tools"),
        localTool("browser_click", "phone"),
        localTool("workspace_write", "file"),
        localTool("generate_image", "system"),
        localTool("mcp_sv1__search", "mcp"),
        localTool("mcp_sv2__read", "mcp"),
    )

    /** Skills habilitadas por cada asistente: una con el mismo nombre que la herramienta local y un prefijo canal_. */
    private val skills = listOf(
        skill("generate_image"),
        skill("channel_broadcast"),
        skill("my_skill"),
        skill("other_skill"),
        skill("my_skill"),
    )

    /** Los find_tools registrados en esta sesión pueden buscar en el directorio de habilidades (incluidas entradas distintas a find_tools). */
    private val sessionSkillDefs = listOf(localTool("my_skill", "skill"), localTool("other_skill", "skill"))

    // ── Implementación de filtrado antes de cambios (por regla general, no involucrado en el código de producción) ─────────────────────

    private fun legacyLocalDefs(
        configuredToolIds: Set<String>,
        mcpServerIds: Set<String>,
        denied: Set<String>,
    ): List<ToolDefinition> = registered.map { aiDef(it.name) }.filter { definition ->
        val notDenied = definition.name !in denied
        val selectedByTool =
            configuredToolIds.isEmpty() || definition.name in configuredToolIds ||
                definition.name == FindToolsTool.TOOL_NAME
        val isMcpTool = definition.name.startsWith("mcp_") && definition.name.contains("__")
        val selectedByMcp = !isMcpTool || mcpServerIds.any { s -> definition.name.startsWith("mcp_${s}__") }
        notDenied && selectedByTool && selectedByMcp
    }

    private fun legacySkills(configuredSkillIds: Set<String>): List<SkillEntity> {
        val localToolNames = registered.map { it.name }.toSet()
        return skills
            .distinctBy { it.id }
            .filterNot { it.id in localToolNames || it.id.startsWith("channel_") }
            .filter { configuredSkillIds.isEmpty() || it.id in configuredSkillIds }
    }

    /** matriz de escena: superposición vacía/lista no vacía,MCP vinculante,ALWAYS_DENY. */
    private val scenarios = listOf(
        ToolSchemaRules.Criteria(),
        ToolSchemaRules.Criteria(configuredToolIds = setOf("browser_click", "workspace_write")),
        ToolSchemaRules.Criteria(mcpServerIds = setOf("sv1")),
        ToolSchemaRules.Criteria(deniedToolNames = setOf("browser_click", "my_skill")),
        ToolSchemaRules.Criteria(
            configuredToolIds = setOf("browser_click"),
            mcpServerIds = setOf("sv2"),
            configuredSkillIds = setOf("my_skill"),
            deniedToolNames = setOf("workspace_write"),
        ),
    )

    @Test
    fun `shared rules keep the pre-change filtering result item by item`() {
        scenarios.forEach { criteria ->
            val expectedLocal =
                legacyLocalDefs(criteria.configuredToolIds, criteria.mcpServerIds, criteria.deniedToolNames)
            val actualLocal = ToolSchemaRules.selectLocalTools(registered.map { aiDef(it.name) }, criteria)
            assertEquals("El orden y el contenido de las herramientas locales deben ser los mismos uno por uno.", expectedLocal, actualLocal)

            val expectedSkills = legacySkills(criteria.configuredSkillIds)
            val actualSkills =
                ToolSchemaRules.selectSkills(skills, registered.map { it.name }.toSet(), criteria)
            assertEquals("El orden y el contenido de las habilidades deben ser los mismos uno por uno.", expectedSkills.map { it.id }, actualSkills.map { it.id })
        }
    }

    @Test
    fun `callable count equals the schema actually sent to the model`() {
        scenarios.forEach { criteria ->
            val counts = ToolCounter.compute(
                ToolCounter.Input(
                    localToolDefs = registered.map { aiDef(it.name) },
                    registeredTools = registered,
                    sessionSkillDefs = sessionSkillDefs,
                    sessionVisibleNames = null,
                    enabledSkills = skills,
                    criteria = criteria,
                ),
            )
            // ChatStreamCoordinator Ruta de entrega: herramientas locales seleccionadas + habilidades después de la deduplicación a través de RouteTable definitions
            val outgoing =
                RouteTable.snapshot(
                    legacyLocalDefs(criteria.configuredToolIds, criteria.mcpServerIds, criteria.deniedToolNames),
                    legacySkills(criteria.configuredSkillIds),
                ).definitions
            assertEquals("El primer nivel debe ser igual al número real de herramientas de esquema distribuido.", outgoing.size, counts.callableInSchema)
            assertEquals("El tercer nivel es el número total de registros locales.", registered.size, counts.registeredTotal)
        }
    }

    @Test
    fun `session visible count equals find_tools empty query listing`() {
        val visibleNames = setOf("find_tools", "browser_click", "my_skill", "other_skill")
        val counts = ToolCounter.compute(
            ToolCounter.Input(
                localToolDefs = registered.map { aiDef(it.name) },
                registeredTools = registered,
                sessionSkillDefs = sessionSkillDefs,
                sessionVisibleNames = visibleNames,
                enabledSkills = skills,
                criteria = ToolSchemaRules.Criteria(),
            ),
        )
        // find_tools Ruta de representación de la consulta vacía: el directorio de búsqueda se reduce mediante la regla del mismo nombre y se entrega a fullList
        val searchable =
            ToolSchemaRules.visibleSubset(registered + sessionSkillDefs, visibleNames)
        val header = FindToolsTool.fullList(searchable)
        assertTrue(
            "El segundo nivel debe ser igual al número de consultas vacías de find_tools:$header",
            header.contains("${counts.sessionVisible} tools total"),
        )
        assertTrue("find_tools No se cuenta", counts.sessionVisible == visibleNames.size - 1)
        assertTrue("Los archivos visibles de la sesión no contienen habilidades no registradas", counts.sessionVisible < counts.registeredTotal + sessionSkillDefs.size)
    }

    @Test
    fun `three tiers stay distinct and ordered as designed`() {
        val counts = ToolCounter.compute(
            ToolCounter.Input(
                localToolDefs = registered.map { aiDef(it.name) },
                registeredTools = registered,
                sessionSkillDefs = sessionSkillDefs,
                sessionVisibleNames = null,
                enabledSkills = skills,
                criteria = ToolSchemaRules.Criteria(deniedToolNames = setOf("browser_click")),
            ),
        )
        // El enlace MCP vacío eliminará dos herramientas mcp_, deshabilitará y eliminará una, y las habilidades solo ingresarán las dos que no tengan el mismo nombre que la herramienta local.
        assertEquals(3 + 2, counts.callableInSchema)
        assertEquals("No se realiza ninguna reducción cuando el conjunto visible no está registrado (se deduce el propio find_tools)", 7, counts.sessionVisible)
        assertEquals(6, counts.registeredTotal)
    }

    @Test
    fun `registry entry uses the same numbers as the pure counter`() {
        val counts =
            ToolCounter.compute(
                ToolCounter.Input(
                    localToolDefs = registered.map { aiDef(it.name) },
                    registeredTools = registered,
                    sessionSkillDefs = sessionSkillDefs,
                    sessionVisibleNames = setOf("find_tools", "my_skill"),
                    enabledSkills = skills,
                    criteria = ToolSchemaRules.Criteria(),
                ),
            )
        assertEquals(4 + 2, counts.callableInSchema)
        assertEquals(1, counts.sessionVisible)
    }
}
