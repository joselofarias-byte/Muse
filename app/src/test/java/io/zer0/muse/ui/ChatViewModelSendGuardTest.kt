package io.zer0.muse.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-TEST-06: ChatViewModel Enviar máquina de estado de guardia lógica pura.
 */
class ChatViewModelSendGuardTest {

    @Test
    fun `blank text without images is rejected`() {
        assertFalse(canStartGeneration("", emptyList(), isStreaming = false, isCreatingAgentSession = false))
        assertFalse(canStartGeneration("   ", emptyList(), isStreaming = false, isCreatingAgentSession = false))
    }

    @Test
    fun `text or images allow generation`() {
        assertTrue(canStartGeneration("Hola", emptyList(), isStreaming = false, isCreatingAgentSession = false))
        assertTrue(
            canStartGeneration(
                "",
                listOf("data:image/png;base64,xxx"),
                isStreaming = false,
                isCreatingAgentSession = false,
            ),
        )
    }

    @Test
    fun `streaming blocks new generation`() {
        assertFalse(canStartGeneration("Hola", emptyList(), isStreaming = true, isCreatingAgentSession = false))
    }

    @Test
    fun `agent session creation blocks reentry`() {
        assertFalse(canStartGeneration("Hola", emptyList(), isStreaming = false, isCreatingAgentSession = true))
    }
}
