package io.zer0.muse.tools

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ¿Qué pruebas de regresión de herramientas integradas puede ver el asistente predeterminado?
 *
 * [ToolRegistry.BUILT_IN_TOOL_IDS] Al mismo tiempo, es "el conjunto de capacidades del asistente recién instalado" y "la fuente complementaria del asistente para usuarios antiguos".
 * (Consulte la lógica de migración de AssistantRepository), por lo que faltar el registro hará que ciertos tipos de herramientas sean completamente invisibles en el chat.——
 * Esta solía ser la herramienta de gestión de MCP:registry Está registrado, pero no está en la lista integrada y el asistente predeterminado no puede verlo.
 */
class BuiltInToolIdsTest {

    @Test
    fun mcpManagementToolsAreExposedToTheDefaultAssistant() {
        // Correspondencia uno a uno con el nombre registrado por McpRegistry.registerManagementTools.
        val mcpManagementTools = listOf(
            "mcp_mgmt_list",
            "mcp_mgmt_configure",
            "mcp_mgmt_remove",
            "mcp_mgmt_bind_assistant",
            "mcp_mgmt_reconnect",
        )

        mcpManagementTools.forEach { tool ->
            assertTrue(
                "Falta la lista de herramientas integradas $tool, el asistente predeterminado no podrá llamarlo en el chat",
                ToolRegistry.BUILT_IN_TOOL_IDS.contains(tool),
            )
        }
    }

    @Test
    fun scriptRuntimesAreExposedToTheDefaultAssistant() {
        // v2.6.1 Regresar: Las herramientas de ejecución integradas deben registrarse en BUILT_IN_TOOL_IDS——
        // Es la fuente de la lista blanca de herramientas auxiliares predeterminadas IdsJson.,La inscripción faltante estará en resolveToolsAndModel
        // el escenario se filtra(Prioriza la familia de palabras clave ToolExposurePolicy),find_tools La carga tampoco se puede omitir.
        // Este fue el caso de ejecutar_python_script: El tiempo de ejecución y el modelo están registrados pero la herramienta no es visible en absoluto.,
        // Sólo puedes encontrar el intérprete en shell/Node(Ralentí repetido medido por los usuarios).
        listOf("execute_javascript", "execute_node_script", "execute_python_script").forEach { tool ->
            assertTrue(
                "Falta la lista de herramientas integradas $tool, el asistente predeterminado no podrá llamarlo en el chat",
                ToolRegistry.BUILT_IN_TOOL_IDS.contains(tool),
            )
        }
    }

    @Test
    fun appControlToolsAreExposedToTheDefaultAssistant() {
        // v2.6.2 Regresar: Conjunto de herramientas de control de aplicaciones (AppControlTools Registro) Me perdí categorías de registro y listas blancas antes,
        // Como resultado, el asistente predeterminado no puede verlos en absoluto (no Hengfa, pero es invisible)——los usuarios quieren"Deja que AI me abra uno App"No puedo hacerlo.
        listOf(
            "app_list",
            "app_launch",
            "app_settings",
            "app_force_stop",
            "app_clear_data",
            "app_uninstall",
        ).forEach { tool ->
            assertTrue(
                "Falta la lista de herramientas integradas $tool, el asistente predeterminado no podrá llamarlo en el chat",
                ToolRegistry.BUILT_IN_TOOL_IDS.contains(tool),
            )
        }
    }

    @Test
    fun builtInToolIdsAreUniqueAndNonBlank() {
        val ids = ToolRegistry.BUILT_IN_TOOL_IDS
        assertTrue("No debería haber espacios vacíos en la lista de herramientas integradas. id", ids.none { it.isBlank() })
        assertTrue("La lista de herramientas integradas no debería tener duplicados. id", ids.distinct().size == ids.size)
    }
}
