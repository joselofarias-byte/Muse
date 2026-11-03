package io.zer0.muse.tools

import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.muse.data.session.SearchResult
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueba de recuperación de texto original entre sesiones —— El punto es**límite de privacidad**.
 *
 * Ítem ​​6 de la Autoevaluación del Asistente: "Ser capaz de recordar cosas discutidas en otros lugares". Técnicamente simplemente elimine el filtro sessionId,
 * Pero entonces el personaje A puede leer la conversación entre el usuario y el personaje B. Entonces se implementa como**Aislar por asistente**:
 * Busca solo otras conversaciones con el mismo asistente.
 *
 * La primera prioridad de esta prueba no es "puedo buscar", sino "no puedo encontrar lo que no debería buscarse".
 */
class SearchConversationScopeTest {

    private val repo = mockk<SessionRepository>(relaxed = true)

    private fun session(id: String, assistantId: String, title: String = id) = SessionEntity(
        id = id,
        title = title,
        createdAt = 0L,
        updatedAt = 0L,
        assistantId = assistantId,
    )

    private fun result(sessionId: String, content: String, role: String = "USER") = SearchResult(
        messageId = "m-$sessionId-$content",
        sessionId = sessionId,
        sessionTitle = sessionId,
        contentSnippet = content,
        role = role,
        createdAt = 0L,
        content = content,
    )

    private fun context(sessionId: String) = ToolExecutionContext(scope = "main", spaceId = "default", sessionId = sessionId)

    @Test
    fun `default scope searches only the current session`() = runBlocking {
        coEvery { repo.searchMessagesInSession("s1", "musica", 8) } returns listOf(result("s1", "Charló sobre el jugador."))
        coEvery { repo.searchMessages("musica") } returns listOf(result("s2", "música en otros lugares"))

        val out =
            SearchConversationTool.execute(
                args = mapOf("query" to "musica"),
                sessionRepository = repo,
                enabled = true,
                context = context("s1"),
            )

        assertTrue(out.contains("Charló sobre el jugador."))
        assertFalse("No debería abarcar sesiones de forma predeterminada", out.contains("música en otros lugares"))
    }

    @Test
    fun `assistant scope includes the same assistant's other sessions`() = runBlocking {
        coEvery { repo.getSessionById("s1") } returns session("s1", "assistant-a")
        coEvery { repo.getSessionById("s2") } returns session("s2", "assistant-a", "sesión de la semana pasada")
        // v2.6.7 fix (AUDIT F16): El filtrado entre sesiones se cambia para recuperar el mismo conjunto de identificadores de sesión del asistente a la vez.
        coEvery { repo.sessionsOfAssistant("assistant-a") } returns listOf("s1", "s2")
        coEvery { repo.searchMessages("musica") } returns listOf(
            result("s1", "Contenido de esta conversación"),
            result("s2", "De lo que hablamos la semana pasada"),
        )

        val out =
            SearchConversationTool.execute(
                args = mapOf("query" to "musica", "scope" to "assistant"),
                sessionRepository = repo,
                enabled = true,
                context = context("s1"),
            )

        assertTrue(out.contains("De lo que hablamos la semana pasada"))
        assertTrue("Marcar la fuente entre sesiones", out.contains("de") || out.contains("from"))
    }

    @Test
    fun `assistant scope never leaks another assistant's conversations`() = runBlocking {
        coEvery { repo.getSessionById("s1") } returns session("s1", "assistant-a")
        coEvery { repo.getSessionById("s3") } returns session("s3", "assistant-b")
        // s2 Pertenece al mismo asistente,s3 Pertenece a otro asistente.
        coEvery { repo.sessionsOfAssistant("assistant-a") } returns listOf("s1", "s2")
        coEvery { repo.searchMessages("secreto") } returns listOf(
            result("s2", "Contenidos con el asistente."),
            result("s3", "Conversaciones privadas con otros personajes."),
        )

        val out =
            SearchConversationTool.execute(
                args = mapOf("query" to "secreto", "scope" to "assistant"),
                sessionRepository = repo,
                enabled = true,
                context = context("s1"),
            )

        assertTrue(out.contains("Contenidos con el asistente."))
        assertFalse("Nunca leas conversaciones entre otros asistentes y usuarios", out.contains("Conversaciones privadas con otros personajes."))
    }

    @Test
    fun `unknown scope falls back to current session`() = runBlocking {
        coEvery { repo.searchMessagesInSession("s1", "x", 8) } returns listOf(result("s1", "esta sesión"))

        val out =
            SearchConversationTool.execute(
                args = mapOf("query" to "x", "scope" to "everything"),
                sessionRepository = repo,
                enabled = true,
                context = context("s1"),
            )

        assertTrue(out.contains("esta sesión"))
    }

    @Test
    fun `disabled recall returns an error and touches nothing`() = runBlocking {
        val out =
            SearchConversationTool.execute(
                args = mapOf("query" to "x"),
                sessionRepository = repo,
                enabled = false,
                context = context("s1"),
            )

        assertTrue(out.startsWith("Error:"))
    }

    @Test
    fun `missing session id is rejected`() = runBlocking {
        val out =
            SearchConversationTool.execute(
                args = mapOf("query" to "x"),
                sessionRepository = repo,
                enabled = true,
                context = ToolExecutionContext(scope = "main", spaceId = "default", sessionId = null),
            )

        assertTrue(out.startsWith("Error:"))
    }

    @Test
    fun `no match reports it plainly`() = runBlocking {
        coEvery { repo.searchMessagesInSession("s1", "Ninguno", 8) } returns emptyList()

        val out =
            SearchConversationTool.execute(
                args = mapOf("query" to "Ninguno"),
                sessionRepository = repo,
                enabled = true,
                context = context("s1"),
            )

        assertTrue(out.contains("No matching"))
    }
}
