package io.zer0.muse.web

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** session.rename / archive / delete Pruebas de aislamiento de enfoque. */
class HostSessionCommandsTest {

    private class RecordingMutations : HostSessionMutations {
        val renames = mutableListOf<Pair<String, String>>()
        val archives = mutableListOf<Pair<String, Boolean>>()
        val deletes = mutableListOf<String>()

        override fun rename(sessionId: String, title: String) {
            renames += sessionId to title
        }

        override fun archive(sessionId: String, archived: Boolean) {
            archives += sessionId to archived
        }

        override fun delete(sessionId: String) {
            deletes += sessionId
        }
    }

    private fun command(type: String, sessionId: String?): HostCommand = when (type) {
        HostSessionCommands.TYPE_RENAME -> HostCommand(
            type = type,
            requestId = "req-$type",
            sessionId = sessionId,
            title = "nuevo título",
        )
        HostSessionCommands.TYPE_ARCHIVE -> HostCommand(
            type = type,
            requestId = "req-$type",
            sessionId = sessionId,
            archived = true,
        )
        else -> HostCommand(type = type, requestId = "req-$type", sessionId = sessionId)
    }

    @Test
    fun `session writes are rejected with focus_not_held when another connection holds the focus`() = runTest {
        val mutations = RecordingMutations()
        val focus = HostFocusTokenHolder()
        val commands = HostSessionCommands(focus, mutations)
        focus.acquire("connection-a", "session-1")

        HostSessionCommands.types.forEach { type ->
            val event = commands.handle(command(type, "session-1"), connectionId = "connection-b")

            assertEquals("$type Debería devolver un evento de error", HostSessionCommands.EVENT_ERROR, event.type)
            assertEquals(HostSessionCommands.ERROR_FOCUS_NOT_HELD, event.error)
            assertEquals("req-$type", event.requestId)
        }

        assertTrue("Una conexión que no mantiene el foco no debe modificar la sesión.", mutations.renames.isEmpty())
        assertTrue(mutations.archives.isEmpty())
        assertTrue(mutations.deletes.isEmpty())
    }

    @Test
    fun `session writes take effect for the connection holding the focus`() = runTest {
        val mutations = RecordingMutations()
        val focus = HostFocusTokenHolder()
        val commands = HostSessionCommands(focus, mutations)
        focus.acquire("connection-a", "session-1")

        HostSessionCommands.types.forEach { type ->
            val event = commands.handle(command(type, "session-1"), connectionId = "connection-a")

            assertEquals("$type debe ser aceptado", HostSessionCommands.EVENT_ACCEPTED, event.type)
            assertEquals("session-1", event.sessionId)
            assertEquals("req-$type", event.requestId)
        }

        assertEquals(listOf("session-1" to "nuevo título"), mutations.renames)
        assertEquals(listOf("session-1" to true), mutations.archives)
        assertEquals(listOf("session-1"), mutations.deletes)
    }

    @Test
    fun `released focus lets the next connection write`() = runTest {
        val mutations = RecordingMutations()
        val focus = HostFocusTokenHolder()
        val commands = HostSessionCommands(focus, mutations)
        focus.acquire("connection-a", null)
        focus.release("connection-a")

        val event = commands.handle(command(HostSessionCommands.TYPE_DELETE, "session-9"), connectionId = "connection-b")

        assertEquals(HostSessionCommands.EVENT_ACCEPTED, event.type)
        assertEquals(listOf("session-9"), mutations.deletes)
    }

    @Test(expected = HostProtocolException::class)
    fun `rename without a title is a protocol error`() = runTest {
        val commands = HostSessionCommands(HostFocusTokenHolder(), RecordingMutations())

        commands.handle(
            HostCommand(type = HostSessionCommands.TYPE_RENAME, sessionId = "session-1", title = "   "),
            connectionId = "connection-a",
        )
    }

    @Test(expected = HostProtocolException::class)
    fun `archive without the archived flag is a protocol error`() = runTest {
        val commands = HostSessionCommands(HostFocusTokenHolder(), RecordingMutations())

        commands.handle(
            HostCommand(type = HostSessionCommands.TYPE_ARCHIVE, sessionId = "session-1"),
            connectionId = "connection-a",
        )
    }

    @Test(expected = HostProtocolException::class)
    fun `session write without a session id is a protocol error`() = runTest {
        val commands = HostSessionCommands(HostFocusTokenHolder(), RecordingMutations())

        commands.handle(
            HostCommand(type = HostSessionCommands.TYPE_DELETE, sessionId = "  "),
            connectionId = "connection-a",
        )
    }
}
