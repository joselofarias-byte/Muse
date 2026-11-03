package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure Kotlin: división de ventana de contexto(conciencia de dependencia de herramientas)prueba. */
class ContextWindowSplitTest {
    @Test
    fun `no-op when history fits the window`() {
        val messages =
            listOf(
                UIMessage(role = MessageRole.USER, content = "u1"),
                UIMessage(role = MessageRole.ASSISTANT, content = "a1"),
            )

        val (dropped, kept) = messages.splitContextWindow(10)

        assertTrue(dropped.isEmpty())
        assertSame(messages, kept)
    }

    @Test
    fun `backtracks out of tool pair when cut lands on tool call`() {
        val messages =
            listOf(
                UIMessage(role = MessageRole.USER, content = "u1"),
                UIMessage(role = MessageRole.ASSISTANT, content = "a1"),
                UIMessage(role = MessageRole.USER, content = "u2"),
                UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(ToolCall("c1", "t", "{}"))),
                UIMessage(role = MessageRole.TOOL, content = "r1", toolCallId = "c1"),
                UIMessage(role = MessageRole.USER, content = "u3"),
            )

        val (dropped, kept) = messages.splitContextWindow(3)

        // takeLast(3) seguirá assistant(tool_call) cortar → Recurrir a u2,Asegúrese de que el par de herramientas esté completo
        assertEquals(2, dropped.size)
        assertEquals(MessageRole.USER, kept.first().role)
        assertEquals("u2", kept.first().content)
        assertTrue(kept.any { it.role == MessageRole.TOOL })
    }

    @Test
    fun `backtracks out of tool pair when cut lands on tool result`() {
        val messages =
            listOf(
                UIMessage(role = MessageRole.USER, content = "u1"),
                UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(ToolCall("c1", "t", "{}"))),
                UIMessage(role = MessageRole.TOOL, content = "r1", toolCallId = "c1"),
                UIMessage(role = MessageRole.ASSISTANT, content = "done"),
            )

        val (dropped, kept) = messages.splitContextWindow(2)

        // takeLast(2) seguirá assistant(tool_call)+TOOL El corte de HERRAMIENTA → Volver al punto de partida del par de herramientas
        assertEquals(0, dropped.size)
        assertEquals(4, kept.size)
    }

    @Test
    fun `limitContextWithContext delegates to window split`() {
        val messages =
            listOf(
                UIMessage(role = MessageRole.USER, content = "u1"),
                UIMessage(role = MessageRole.USER, content = "u2"),
                UIMessage(role = MessageRole.USER, content = "u3"),
            )

        val limited = messages.limitContextWithContext(2)

        assertEquals(2, limited.size)
        assertEquals("u2", limited.first().content)
    }
}
