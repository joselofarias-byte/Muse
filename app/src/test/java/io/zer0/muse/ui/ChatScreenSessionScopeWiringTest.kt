package io.zer0.muse.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Guard the UI-only pending-tool recovery callbacks from regressing to task-session scope.
 */
class ChatScreenSessionScopeWiringTest {

    @Test
    fun `pending tool recovery uses the displayed mode-aware session`() {
        val source = locateChatScreenSource()

        assertFalse(
            source.contains("state.currentSessionId?.let { viewModel.resumePendingToolCalls(it) }"),
        )
        assertFalse(
            source.contains("state.currentSessionId?.let { viewModel.discardPendingToolCalls(it) }"),
        )
        assertTrue(
            source.contains("effectiveChatSessionId(state)?.let { viewModel.resumePendingToolCalls(it) }"),
        )
        assertTrue(
            source.contains("effectiveChatSessionId(state)?.let { viewModel.discardPendingToolCalls(it) }"),
        )
        assertFalse(
            source.contains("SessionTodoBar(sessionId = state.currentSessionId ?: \"\")"),
        )
        assertTrue(
            source.contains("SessionTodoBar(sessionId = effectiveChatSessionId(state).orEmpty())"),
        )
        assertFalse(source.contains("LaunchedEffect(state.currentSessionId)"))
        assertFalse(source.contains("LaunchedEffect(state.hasDraft, state.currentSessionId)"))
        assertFalse(source.contains("LaunchedEffect(state.targetMessageId, state.currentSessionId)"))
        assertTrue(source.contains("LaunchedEffect(effectiveChatSessionId(state))"))
        // v2.6.7: Restaurar borradores para que no se muestren como brindis ni se muestren etiquetas —— Requisitos del usuario“No se requieren indicaciones, simplemente haga que el cuadro de entrada sea persistente.”.
        // Resulta que aquí hay una afirmación. LaunchedEffect(state.hasDraft, effectiveChatSessionId(state)),
        // El brindis se eliminó según lo solicitado y la afirmación se revirtió a“no debería haber aparecido”.
        assertFalse(
            source.contains("LaunchedEffect(state.hasDraft, effectiveChatSessionId(state))"),
        )
        assertTrue(source.contains("LaunchedEffect(state.targetMessageId, effectiveChatSessionId(state))"))
        assertFalse(source.contains("trackedSessionId = state.currentSessionId"))
        assertFalse(source.contains("val sessionId = state.currentSessionId"))
    }

    private fun locateChatScreenSource(): String {
        val candidates =
            listOf(
                Path.of("src/main/java/io/zer0/muse/ui/ChatScreen.kt"),
                Path.of("app/src/main/java/io/zer0/muse/ui/ChatScreen.kt"),
            )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("Unable to locate ChatScreen.kt from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
