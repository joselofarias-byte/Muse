package io.zer0.muse.ui.chat

import io.mockk.mockk
import io.zer0.muse.tools.ToolRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the production shapes for tool terminal states.
 *
 * The orchestrator synthesises bracketed markers (`[tiempo de espera]`, `[interrumpir]`) and hooks/MCP
 * bridges return structured `{"error": ...}` payloads; the previous bare-word prefix
 * list matched none of them, so timeouts were reported as successes.
 */
class ToolResultSuccessTest {

    private fun coordinator() = ChatTaskCardCoordinator(
        accessor = mockk(relaxed = true),
        toolRegistry = mockk<ToolRegistry>(relaxed = true),
    )

    @Test
    fun bracketedTerminalStatesAreFailures() {
        val coordinator = coordinator()

        assertFalse(coordinator.isToolResultSuccess("[tiempo de espera] La herramienta web_search no responde durante 120 segundos,terminado"))
        assertFalse(coordinator.isToolResultSuccess("[interrumpir] Ejecución de herramienta read_file cancelada"))
        assertFalse(coordinator.isToolResultSuccess("[La salida de la herramienta está truncada: 40000 caracteres en total]"))
    }

    @Test
    fun structuredErrorPayloadsAreFailures() {
        val coordinator = coordinator()

        assertFalse(coordinator.isToolResultSuccess("""{"error": "Tool denied by user"}"""))
        assertFalse(coordinator.isToolResultSuccess("""{"error":"hook blocked"}"""))
    }

    @Test
    fun legacyPlainTextFailuresStillFail() {
        val coordinator = coordinator()

        assertFalse(coordinator.isToolResultSuccess("error: unknown tool"))
        assertFalse(coordinator.isToolResultSuccess("Excepción de ejecución de herramienta: timeout"))
        assertFalse(coordinator.isToolResultSuccess("El archivo no existe: /tmp/a.txt"))
    }

    @Test
    fun normalResultsStaySuccessful() {
        val coordinator = coordinator()

        assertTrue(coordinator.isToolResultSuccess("3 resultados encontrados"))
        assertTrue(coordinator.isToolResultSuccess("""{"items": [1, 2, 3]}"""))
        assertTrue(coordinator.isToolResultSuccess(""))
    }
}
