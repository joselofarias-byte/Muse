package io.zer0.muse.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * MCP Las herramientas exponen los contratos de límites:
 * Una lista de enlaces de MCP auxiliar vacía no puede incorporar todas las herramientas de servidor externo conectadas.
 */
class McpToolIsolationContractTest {

    @Test
    fun `empty assistant MCP binding must not mean every server`() {
        val source = File("src/main/java/io/zer0/muse/ui/chat/ChatStreamCoordinator.kt").readText()
        assertFalse(
            "MCP Las herramientas deben requerir un enlace explícito al servidor y no pueden usar una lista vacía para permitir todos server",
            source.contains("!isMcpTool || configuredMcpServerIds.isEmpty()"),
        )
    }
}
