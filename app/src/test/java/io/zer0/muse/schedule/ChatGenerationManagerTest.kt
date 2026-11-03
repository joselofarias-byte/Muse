package io.zer0.muse.schedule

import io.zer0.muse.session.ConversationSessionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGenerationManagerTest {

    @Test
    fun `active generation is registered before background coroutine starts`() = runTest {
        val manager = ChatGenerationManager(backgroundScope, ConversationSessionManager(backgroundScope))
        val gate = CompletableDeferred<Unit>()

        manager.launchGeneration(
            sessionId = "session-1",
            assistantId = "assistant-1",
            sessionTitle = "pruebas de fondo",
        ) {
            gate.await()
        }

        val active = manager.activeGeneration.value
        assertEquals("session-1", active?.sessionId)
        assertTrue(active?.isStreaming == true)

        gate.complete(Unit)
        runCurrent()
        assertTrue(manager.activeGeneration.value?.isStreaming == false)
    }

    @Test
    fun `manager preserves caller generation identity`() = runTest {
        val manager = ChatGenerationManager(backgroundScope, ConversationSessionManager(backgroundScope))
        val gate = CompletableDeferred<Unit>()

        manager.launchGeneration(
            sessionId = "session-1",
            assistantId = "assistant-1",
            sessionTitle = "Unificar generaciones",
            generationId = "trace-generation-1",
        ) { gate.await() }

        assertEquals("trace-generation-1", manager.activeGeneration.value?.generationId)
        gate.complete(Unit)
        runCurrent()
    }

    @Test
    fun `generation remains removable after heartbeat replaces display snapshot`() = runTest {
        val manager = ChatGenerationManager(backgroundScope, ConversationSessionManager(backgroundScope))
        val gate = CompletableDeferred<Unit>()

        manager.launchGeneration("session-1", "assistant-1", "prueba") { gate.await() }
        manager.touch("session-1")
        val generationId = manager.activeGenerations.value.getValue("session-1").generationId
        manager.updateSessionTitle("session-1", "Mostrar instantánea")
        assertEquals(generationId, manager.activeGenerations.value.getValue("session-1").generationId)
        assertTrue(manager.activeGenerations.value.containsKey("session-1"))

        gate.complete(Unit)
        runCurrent()
        assertTrue(manager.activeGenerations.value.isEmpty())
    }

    @Test
    fun `all active sessions remain visible while the newest session finishes`() = runTest {
        val manager = ChatGenerationManager(backgroundScope, ConversationSessionManager(backgroundScope))
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()

        manager.launchGeneration("session-1", "assistant-1", "Artículo 1") { firstGate.await() }
        manager.launchGeneration("session-2", "assistant-2", "Artículo 2") { secondGate.await() }
        runCurrent()

        assertEquals(setOf("session-1", "session-2"), manager.activeGenerations.value.keys)

        secondGate.complete(Unit)
        runCurrent()

        assertEquals(setOf("session-1"), manager.activeGenerations.value.keys)
        assertTrue(manager.isStreaming("session-1"))

        firstGate.complete(Unit)
        runCurrent()
        assertTrue(manager.activeGenerations.value.isEmpty())
    }

    @Test
    fun `same session replacement waits for cancelled generation to finish`() = runTest {
        val manager = ChatGenerationManager(backgroundScope, ConversationSessionManager(backgroundScope))
        val oldFinally = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()

        manager.launchGeneration("session-1", "assistant-old", "viejo") {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                oldFinally.complete(Unit)
            }
        }
        runCurrent()

        manager.launchGeneration("session-1", "assistant-new", "nuevo") {
            newStarted.complete(Unit)
        }
        runCurrent()

        assertTrue(oldFinally.isCompleted)
        assertTrue(newStarted.isCompleted)
        assertEquals("assistant-new", manager.activeGeneration.value?.assistantId)
    }

    @Test
    fun `touch updates only the requested session heartbeat`() = runTest {
        val manager = ChatGenerationManager(backgroundScope, ConversationSessionManager(backgroundScope))
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()

        manager.launchGeneration("session-1", "assistant-1", "Artículo 1") { firstGate.await() }
        manager.launchGeneration("session-2", "assistant-2", "Artículo 2") { secondGate.await() }
        runCurrent()

        val firstBefore = manager.activeGenerations.value.getValue("session-1").lastUpdatedAt
        val secondBefore = manager.activeGenerations.value.getValue("session-2").lastUpdatedAt
        manager.touch("session-1")

        assertTrue(manager.activeGenerations.value.getValue("session-1").lastUpdatedAt >= firstBefore)
        assertEquals(secondBefore, manager.activeGenerations.value.getValue("session-2").lastUpdatedAt)

        firstGate.complete(Unit)
        secondGate.complete(Unit)
        runCurrent()
    }
}
