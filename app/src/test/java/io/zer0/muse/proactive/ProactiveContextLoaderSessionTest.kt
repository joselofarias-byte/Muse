package io.zer0.muse.proactive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.data.ProactiveMessageConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.proactive.ProactiveOutcomeRepository
import io.zer0.muse.data.proactive.ProactiveRelationRepository
import io.zer0.muse.data.proactive.ProactiveThoughtRepository
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.schedule.ChatGenerationManager
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * Elección de ubicación para mensajes activos: envíalos donde termines la conversación.
 *
 * Utilice SessionRepository real + sala de memoria, debido a las condiciones de exclusión (eliminado/archivado/teléfono pequeño). /
 * Ningún usuario habla) están escritos en SQL —— Usar un almacén falso para reproducir los resultados significa que no se prueba nada.
 * `source` Se requiere una afirmación en cada nivel: determina si "a qué sala se envió el mensaje" se puede revisar desde el registro.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProactiveContextLoaderSessionTest {

    private companion object {
        const val ASSISTANT_ID = "a1"
        const val MINUTE = 60_000L
    }

    private lateinit var context: Context
    private lateinit var database: MuseDb
    private lateinit var repository: SessionRepository
    private lateinit var settings: SettingsRepository
    private lateinit var assistant: AssistantEntity

    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SessionRepository(
            sessionDao = database.sessionDao(),
            messageDao = database.messageDao(),
            database = database,
            context = context,
            messageImageStore = MessageImageStore(File(context.cacheDir, "proactive-session-${UUID.randomUUID()}")),
        )
        settings = mockk(relaxed = true)
        coEvery { settings.getViewedSessionId() } returns null
        every { settings.proactiveMessageConfigFlow } returns
            flowOf(ProactiveMessageConfig(agentSessionEnabled = true))
        assistant = mockk(relaxed = true)
        every { assistant.id } returns ASSISTANT_ID
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun loader(): ProactiveContextLoader = ProactiveContextLoader(
        stores = ProactiveStores(
            thoughts = mockk<ProactiveThoughtRepository>(relaxed = true),
            relations = mockk<ProactiveRelationRepository>(relaxed = true),
            outcomes = mockk<ProactiveOutcomeRepository>(relaxed = true),
        ),
        runtime = ProactiveRuntime(
            settings = settings,
            sessions = repository,
            assistants = mockk(relaxed = true),
            notifier = mockk<MuseNotificationManager>(relaxed = true),
            generations = mockk<ChatGenerationManager>(relaxed = true),
        ),
        sources = ProactiveSources(factStore = mockk(relaxed = true), milestoneDao = mockk(relaxed = true)),
    )

    private suspend fun seedSession(
        id: String,
        updatedAt: Long,
        assistantId: String = ASSISTANT_ID,
        isAgentSession: Boolean = false,
        archived: Boolean = false,
        deletedAt: Long? = null,
    ) {
        database.sessionDao().insert(
            SessionEntity(
                id = id,
                title = id,
                createdAt = updatedAt,
                updatedAt = updatedAt,
                assistantId = assistantId,
                isAgentSession = isAgentSession,
                archived = archived,
                deletedAt = deletedAt,
            ),
        )
    }

    private suspend fun seedUserMessage(sessionId: String, at: Long, content: String = "¿Está ahí?") {
        database.messageDao().upsert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = "USER",
                content = content,
                createdAt = at,
            ),
        )
    }

    private suspend fun seedAssistantMessage(sessionId: String, at: Long) {
        database.messageDao().upsert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = "ASSISTANT",
                content = "en",
                createdAt = at,
            ),
        )
    }

    @Test
    fun `recent active plain session wins over older agent session`() = runTest {
        seedSession("plain", updatedAt = now - 5 * MINUTE)
        seedUserMessage("plain", at = now - 6 * MINUTE)
        seedSession("agent", updatedAt = now - 600 * MINUTE, isAgentSession = true)
        seedUserMessage("agent", at = now - 601 * MINUTE)

        val pick = loader().resolveSessionPick(assistant)

        assertEquals("Debe escribirse en una conversación conversacional ordinaria.", "plain", pick?.sessionId)
        assertEquals(ProactiveContextLoader.SessionSource.RECENT_ACTIVE, pick?.source)
        // resolveSession Es una entrada externa y la semántica de la firma permanece sin cambios: sigue siendo la misma. id
        assertEquals("plain", loader().resolveSession(assistant))
    }

    @Test
    fun `plain session wins even when agent session is newer`() = runTest {
        // Comentarios de los usuarios "Los mensajes activos siempre se envían en la página del Agente":Agent Tab La actualización es incluso posterior, por lo que no debería ocupar el lugar.
        seedSession("plain", updatedAt = now - 600 * MINUTE)
        seedUserMessage("plain", at = now - 601 * MINUTE)
        seedSession("agent", updatedAt = now - 5 * MINUTE, isAgentSession = true)
        seedUserMessage("agent", at = now - 6 * MINUTE)

        val pick = loader().resolveSessionPick(assistant)

        assertEquals("Agent Tab No importa lo nuevo que sea, nunca tomes la iniciativa de publicar noticias.", "plain", pick?.sessionId)
        assertEquals(ProactiveContextLoader.SessionSource.RECENT_ACTIVE, pick?.source)
    }

    @Test
    fun `agent session is used when the user only chatted there`() = runTest {
        seedSession("agent", updatedAt = now - 5 * MINUTE, isAgentSession = true)
        seedUserMessage("agent", at = now - 6 * MINUTE)

        val pick = loader().resolveSessionPick(assistant)

        assertEquals("Si solo conversaste en el Agente, escríbelo en esa sesión del Agente", "agent", pick?.sessionId)
        assertEquals(ProactiveContextLoader.SessionSource.RECENT_ANY, pick?.source)
    }

    @Test
    fun `agent session is skipped when the switch is off`() = runTest {
        // Apagar "Agent Después de "Sesión": Cuando solo está disponible la sesión del Agente, es mejor no enviarla que caer en la pestaña Agente.
        every { settings.proactiveMessageConfigFlow } returns
            flowOf(ProactiveMessageConfig(agentSessionEnabled = false))
        seedSession("agent", updatedAt = now - 5 * MINUTE, isAgentSession = true)
        seedUserMessage("agent", at = now - 6 * MINUTE)

        val pick = loader().resolveSessionPick(assistant)

        assertEquals("No ingrese a la sesión del Agente después del cierre", null, pick)
    }

    @Test
    fun `plain session still wins when the switch is off`() = runTest {
        every { settings.proactiveMessageConfigFlow } returns
            flowOf(ProactiveMessageConfig(agentSessionEnabled = false))
        seedSession("plain", updatedAt = now - 600 * MINUTE)
        seedUserMessage("plain", at = now - 601 * MINUTE)
        seedSession("agent", updatedAt = now - 5 * MINUTE, isAgentSession = true)
        seedUserMessage("agent", at = now - 6 * MINUTE)

        val pick = loader().resolveSessionPick(assistant)

        assertEquals("Las conversaciones ordinarias se pueden escribir como de costumbre.", "plain", pick?.sessionId)
    }

    @Test
    fun `existing agent session is reused instead of creating a new one`() = runTest {
        // Crear uno nuevo cada vez hará que la página del Agente acumule más sesiones vacías; el existente debe reutilizarse.
        seedSession("agent-old", updatedAt = now - 900 * MINUTE, isAgentSession = true)
        seedAssistantMessage("agent-old", at = now - 900 * MINUTE)

        val pick = loader().resolveSessionPick(assistant)

        assertEquals("Las sesiones de agentes existentes deben reutilizarse", "agent-old", pick?.sessionId)
        assertEquals(ProactiveContextLoader.SessionSource.EXISTING_AGENT, pick?.source)
    }

    @Test
    fun `deleted session is skipped`() = runTest {
        seedSession("deleted", updatedAt = now - 1 * MINUTE, deletedAt = now - 1 * MINUTE)
        seedUserMessage("deleted", at = now - 2 * MINUTE)
        seedSession("plain", updatedAt = now - 300 * MINUTE)
        seedUserMessage("plain", at = now - 301 * MINUTE)

        val pick = loader().resolveSessionPick(assistant)

        assertEquals("No se permite escribir sesiones eliminadas temporalmente", "plain", pick?.sessionId)
        assertEquals(ProactiveContextLoader.SessionSource.RECENT_ACTIVE, pick?.source)
    }

    @Test
    fun `archived session is skipped`() = runTest {
        seedSession("archived", updatedAt = now - 1 * MINUTE, archived = true)
        seedUserMessage("archived", at = now - 2 * MINUTE)
        seedSession("plain", updatedAt = now - 300 * MINUTE)
        seedUserMessage("plain", at = now - 301 * MINUTE)

        val pick = loader().resolveSessionPick(assistant)

        assertEquals("No se permite escribir conversaciones archivadas", "plain", pick?.sessionId)
        assertEquals(ProactiveContextLoader.SessionSource.RECENT_ACTIVE, pick?.source)
    }

    @Test
    fun `viewed session is picked when no session has user messages`() = runTest {
        // El asistente solo tiene una habitación antigua a su nombre que no habla (solo se eliminan los mensajes activos) y no se puede obtener en el nivel 1.
        seedSession("agent-old", updatedAt = now - 900 * MINUTE, isAgentSession = true)
        seedAssistantMessage("agent-old", at = now - 900 * MINUTE)
        // El usuario acaba de abrir una nueva sesión que aún no ha hablado y actualmente está siendo vista.
        seedSession("viewed", updatedAt = now - 1 * MINUTE)
        coEvery { settings.getViewedSessionId() } returns "viewed"

        val pick = loader().resolveSessionPick(assistant)

        assertEquals("Debe escribirse en la sesión que el usuario está viendo.", "viewed", pick?.sessionId)
        assertEquals(ProactiveContextLoader.SessionSource.VIEWED, pick?.source)
    }

    @Test
    fun `viewed session of another assistant is ignored and a new agent session is created`() = runTest {
        seedSession("other-assistant", updatedAt = now - 1 * MINUTE, assistantId = "a2")
        coEvery { settings.getViewedSessionId() } returns "other-assistant"

        val pick = loader().resolveSessionPick(assistant)

        assertNotNull("Deberías crear una nueva sesión.", pick)
        assertNotEquals("No escribas en las sesiones de otros asistentes.", "other-assistant", pick?.sessionId)
        assertEquals(ProactiveContextLoader.SessionSource.NEW_AGENT, pick?.source)
        val created = database.sessionDao().getById(pick!!.sessionId)
        assertNotNull(created)
        assertTrue("La recién creada es la sesión del Agente.", created!!.isAgentSession)
        assertEquals(ASSISTANT_ID, created.assistantId)
    }


    @Test
    fun `no session at all falls back to a new agent session`() = runTest {
        val pick = loader().resolveSessionPick(assistant)

        assertNotNull(pick)
        assertEquals(ProactiveContextLoader.SessionSource.NEW_AGENT, pick?.source)
        val created = database.sessionDao().getById(pick!!.sessionId)
        assertNotNull(created)
        assertTrue(created!!.isAgentSession)
        assertEquals("Las nuevas sesiones deben estar vinculadas al asistente.", ASSISTANT_ID, created.assistantId)
    }

    @Test
    fun `Cuando la cantidad total de materiales supere el presupuesto, se descartarán los elementos más antiguos`() = runTest {
        // 40 Artículo × 600 Palabras: un solo artículo no es largo, pero cuando se apila, contiene más de 20.000 palabras.
        // La función del presupuesto es limitar el monto total, debiendo perderse el segmento más antiguo.
        val total = ProactiveContextLoader.RECENT_MESSAGE_COUNT
        seedSession("busy", updatedAt = now)
        repeat(total) { index ->
            seedUserMessage(
                sessionId = "busy",
                at = now - (total - index) * MINUTE,
                content = "m" + index.toString().padStart(2, '0') + "x".repeat(597),
            )
        }

        val context = loader().loadSessionContext("busy", ASSISTANT_ID)

        val chars = context.recentMessages.sumOf { it.content.length }
        assertTrue(
            "La longitud total del material debe estar dentro del presupuesto:$chars > ${ProactiveContextLoader.CONTEXT_CHAR_BUDGET}",
            chars <= ProactiveContextLoader.CONTEXT_CHAR_BUDGET,
        )
        // Presupuesto 6000/cada uno 600 = últimos 10
        assertEquals("Sólo se deben conservar los últimos diez elementos.", 10, context.recentMessages.size)
        assertTrue(
            "El intervalo de retención debe comenzar en el elemento 31 (los 30 elementos más antiguos se descartan)",
            context.recentMessages.first().content.startsWith("m30"),
        )
        assertTrue(
            "El último debe ser el último.",
            context.recentMessages.last().content.startsWith("m39"),
        )
        assertEquals("El último tiempo de intervención debe ser el último del intervalo reservado.", now - MINUTE, context.lastUserMessageAt)
    }
}
