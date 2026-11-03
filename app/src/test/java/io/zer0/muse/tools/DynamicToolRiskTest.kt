package io.zer0.muse.tools

import io.zer0.muse.data.plugin.PluginManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Fuentes de riesgos de las herramientas de registro dinámico.
 *
 * El nombre de la herramienta complementaria/MCP lo elige un tercero. El nivel de riesgo ya no se puede deducir del nombre.:
 * en el nombre `get_` / `read_` No significa solo lectura,Nombra la llamada saliente `get_diagnostics` puede estar en
 * ASK modo sin revisión. Tres cosas para concretar aquí:
 *  1. Suena como una herramienta complementaria inofensiva NORMAL(ASK descargar aprobación),No más tomar SAFE;
 *  2. Los complementos que suenen peligrosos no se trasladarán a otro enlace,Siga también el default conservador;
 *  3. Este ajuste no afecta a las declaraciones de clase de las herramientas integradas.
 */
class DynamicToolRiskTest {

    /** El formulario de registro real de las herramientas complementarias.:PluginManager.skillId(pluginId, toolName). */
    private fun pluginToolId(toolName: String): String = PluginManager.skillId("demo", toolName)

    /** MCP El formulario de registro real de la herramienta.:McpRegistry.registerTools adentro `mcp_{serverId}__{tool}`. */
    private fun mcpToolId(toolName: String): String = "mcp_demo__$toolName"

    @Test
    fun `plugin tool with harmless name is NORMAL not SAFE`() {
        listOf("get_diagnostics", "read_status", "get_info", "list_everything", "search_outbox").forEach { name ->
            val id = pluginToolId(name)
            assertEquals("$id no puedo juzgar SAFE(ASK Una sentencia más baja significa exención de juicio)", ToolRiskLevel.NORMAL, ToolPermissionResolver.riskLevelFor(id))
            assertEquals(
                "$id Debe esperar la aprobación en modo ASK",
                ToolApprovalState.Pending,
                ToolPermissionResolver.resolve(id, null, SessionPermissionMode.ASK, null),
            )
        }
    }

    @Test
    fun `plugin tool is not trusted through a SAFE registration ledger value`() {
        // Registrar cuenta(ToolDef.riskLevel)El valor predeterminado es SEGURO. Una vez que una herramienta de terceros omite una declaración,
        // El enlace anterior considerará directamente el SAFE en el libro mayor como el resultado del juicio. → ASK Bájate de Auto. Aquí debe volver a NORMAL.
        listOf("get_diagnostics", "read_status").forEach { name ->
            val id = pluginToolId(name)
            assertEquals(
                "$id El libro de contabilidad SAFE no se aceptará directamente",
                ToolApprovalState.Pending,
                ToolPermissionResolver.resolve(id, ToolRiskLevel.SAFE, SessionPermissionMode.ASK, null),
            )
        }
    }

    @Test
    fun `plugin tool with dangerous sounding name is not downgraded to SAFE`() {
        listOf("send_email", "delete_account", "execute_shell", "wire_transfer").forEach { name ->
            val id = pluginToolId(name)
            assertEquals("$id no puede ser degradado a SAFE", ToolRiskLevel.NORMAL, ToolPermissionResolver.riskLevelFor(id))
            assertEquals(
                "$id Debe esperar la aprobación en modo ASK",
                ToolApprovalState.Pending,
                ToolPermissionResolver.resolve(id, ToolRiskLevel.SAFE, SessionPermissionMode.ASK, null),
            )
        }
    }

    @Test
    fun `legacy plugin tool ids are also never SAFE`() {
        // Formulario de identificación antiguo antes de la codificación del prefijo de longitud(plugin_<pluginId>_<tool>),Puede que todavía exista en instalaciones históricas.
        val legacy = PluginManager.legacySkillId("demo", "get_diagnostics")
        assertEquals(ToolRiskLevel.NORMAL, ToolPermissionResolver.riskLevelFor(legacy))
        assertEquals(
            ToolApprovalState.Pending,
            ToolPermissionResolver.resolve(legacy, null, SessionPermissionMode.ASK, null),
        )
    }

    @Test
    fun `mcp server tools follow the same conservative default`() {
        listOf("get_diagnostics", "read_status", "info").forEach { name ->
            val id = mcpToolId(name)
            assertEquals("$id no puedo juzgar SAFE", ToolRiskLevel.NORMAL, ToolPermissionResolver.riskLevelFor(id))
            assertEquals(
                "$id Debe esperar la aprobación en modo ASK",
                ToolApprovalState.Pending,
                ToolPermissionResolver.resolve(id, ToolRiskLevel.SAFE, SessionPermissionMode.ASK, null),
            )
        }
    }

    @Test
    fun `built-in tool levels are untouched by the conservative default`() {
        // La inferencia de nombres todavía funciona para las herramientas integradas:Estos nombres están escritos en el código.,El autor de la herramienta no tiene control.
        assertEquals(ToolRiskLevel.SAFE, ToolPermissionResolver.riskLevelFor("web_search"))
        assertEquals(ToolRiskLevel.SAFE, ToolPermissionResolver.riskLevelFor("read_file"))
        assertEquals(ToolRiskLevel.SAFE, ToolPermissionResolver.riskLevelFor("get_current_time"))
        assertEquals(ToolRiskLevel.NORMAL, ToolPermissionResolver.riskLevelFor("write_file"))
        assertEquals(ToolRiskLevel.HIGH, ToolPermissionResolver.riskLevelFor("send_email"))
        assertEquals(ToolRiskLevel.HIGH, ToolPermissionResolver.riskLevelFor("open_url"))

        // Prefijo común de herramienta de gestión MCP/mercado de complementos incorporado,Pero no una herramienta de terceros,El nivel sigue siendo el mismo.
        assertEquals(ToolRiskLevel.SAFE, ToolPermissionResolver.riskLevelFor("plugin_market_search"))
        assertFalse(ToolPermissionResolver.isThirdPartyToolName("plugin_market_search"))
        assertFalse(ToolPermissionResolver.isThirdPartyToolName("mcp_mgmt_list"))
        // mcp_mgmt_* No en la tabla de riesgo explícita,Ir a“herramienta desconocida → NORMAL”Es el calibre que existía antes de este cambio.(sin cambios).
        assertEquals(ToolRiskLevel.NORMAL, ToolPermissionResolver.riskLevelFor("mcp_mgmt_list"))

        // Las herramientas SAFE integradas permanecen libres de revisión en el modo ASK,no verse afectado por este ajuste.
        assertEquals(
            ToolApprovalState.Auto,
            ToolPermissionResolver.resolve("get_current_time", ToolRiskLevel.SAFE, SessionPermissionMode.ASK, null),
        )
        assertEquals(
            ToolApprovalState.Auto,
            ToolPermissionResolver.resolve("mcp_mgmt_list", ToolRiskLevel.SAFE, SessionPermissionMode.ASK, null),
        )
        assertEquals(
            ToolApprovalState.Auto,
            ToolPermissionResolver.resolve("plugin_market_search", ToolRiskLevel.SAFE, SessionPermissionMode.ASK, null),
        )
    }
}
