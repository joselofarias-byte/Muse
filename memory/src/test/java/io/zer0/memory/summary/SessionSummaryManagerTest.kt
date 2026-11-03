package io.zer0.memory.summary

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.UIMessage
import io.zer0.memory.llm.MemoryLlmClient
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSummaryManagerTest {

    @Test
    fun rollingSummaryInputKeepsOnlyAssistantVibeFromMoodMetadata() = runTest {
        val dao = EmptySessionSummaryDao()
        val llmClient = CapturingMemoryLlmClient(
            response = """
            ### hechos importantes
            - Ninguno
            ### que paso
            - Los asistentes prometieron hacer un seguimiento.
            """.trimIndent(),
        )

        val manager = SessionSummaryManager(dao, llmClient)
        manager.createRollingSummaryDraft(
            sessionId = "session-1",
            messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    content = "Continuaré dando seguimiento a este asunto.",
                    mood = "Vibe: un poco preocupado\nSparks: Asociaciones temporales que me vienen a la mente\nReflections: Todavía necesito comprobar\nWill: Sigue comprobando",
                    createdAt = 1_798_914_240_000L,
                ),
            ),
            model = null,
        )

        assertTrue(llmClient.userContent.contains("Los sentimientos expresados por el asistente en ese momento."))
        assertTrue(llmClient.userContent.contains("Vibe: un poco preocupado"))
        assertFalse(llmClient.userContent.contains("Sparks:"))
        assertFalse(llmClient.userContent.contains("Reflections:"))
        assertFalse(llmClient.userContent.contains("Will:"))
        assertTrue(llmClient.userContent.contains("Continuaré dando seguimiento a este asunto."))
    }

    @Test
    fun concurrentRollingSummaryForSameSessionSerializesCriticalSection() = runTest {
        // v2.6.2(auditoría de memoria): Cuando dos operaciones de resumen continuo de la misma sesión son simultáneas,
        // createRollingSummaryDraft(incluidas las llamadas y escrituras de LLM) deben serializarse dentro del bloqueo y no deben superponerse.
        // De lo contrario, las dos llamadas generarán resúmenes basados ​​en la misma instantánea anterior y la escritura posterior sobrescribirá la escritura anterior.
        val dao = InMemorySessionSummaryDao()
        val llm = ConcurrencyProbeLlmClient()
        val manager = SessionSummaryManager(dao, llm)

        val older = listOf(UIMessage(role = MessageRole.USER, content = "Artículo 1", createdAt = 1L))
        val newer = listOf(
            UIMessage(role = MessageRole.USER, content = "Artículo 1", createdAt = 1L),
            UIMessage(role = MessageRole.USER, content = "Artículo 2", createdAt = 2L),
        )

        val a = async { manager.rollingSummary("s1", older, null) }
        val b = async { manager.rollingSummary("s1", newer, null) }
        a.await()
        b.await()

        assertTrue("Las secciones críticas de una misma sesión no deben superponerse", llm.maxConcurrent <= 1)
    }
}

private class EmptySessionSummaryDao : SessionSummaryDao {
    override suspend fun upsert(entity: SessionSummaryEntity) = Unit
    override suspend fun get(sessionId: String): SessionSummaryEntity? = null
    override suspend fun getAll(): List<SessionSummaryEntity> = emptyList()
    override suspend fun getInRange(
        startISO: String,
        endISO: String,
        since: String?,
        assistantId: String?,
        spaceId: String?,
    ): List<SessionSummaryEntity> = emptyList()
    override suspend fun getDirty(): List<SessionSummaryEntity> = emptyList()
    override suspend fun markProcessed(sessionId: String, now: String) = Unit
    override suspend fun deleteAll() = Unit
    override suspend fun deleteById(sessionId: String) = Unit
    // Cuando se elimina el asistente, se debe borrar el resumen bajo su nombre; no hay filas en los datos falsos vacíos que puedan eliminarse.
    override suspend fun deleteByAssistant(assistantId: String): Int = 0
}

private class CapturingMemoryLlmClient(
    private val response: String,
) : MemoryLlmClient {
    var userContent: String = ""
        private set

    override suspend fun callText(
        systemPrompt: String,
        userContent: String,
        model: Model?,
        temperature: Float,
        maxTokens: Int,
        timeoutMs: Long,
    ): String {
        this.userContent = userContent
        return response
    }
}

/** v2.6.2: Una memoria DAO de lectura y escritura utilizada para verificar el estado final después de escrituras simultáneas. */
private class InMemorySessionSummaryDao : SessionSummaryDao {
    private val rows = mutableMapOf<String, SessionSummaryEntity>()

    override suspend fun upsert(entity: SessionSummaryEntity) {
        rows[entity.sessionId] = entity
    }

    override suspend fun get(sessionId: String): SessionSummaryEntity? = rows[sessionId]

    override suspend fun getAll(): List<SessionSummaryEntity> = rows.values.toList()

    override suspend fun getInRange(
        startISO: String,
        endISO: String,
        since: String?,
        assistantId: String?,
        spaceId: String?,
    ): List<SessionSummaryEntity> = rows.values.toList()

    override suspend fun getDirty(): List<SessionSummaryEntity> = rows.values.toList()

    override suspend fun markProcessed(sessionId: String, now: String) = Unit

    override suspend fun deleteAll() {
        rows.clear()
    }

    override suspend fun deleteById(sessionId: String) {
        rows.remove(sessionId)
    }

    override suspend fun deleteByAssistant(assistantId: String): Int {
        val victims = rows.filterValues { it.assistantId == assistantId }.keys.toList()
        victims.forEach { rows.remove(it) }
        return victims.size
    }
}

/** v2.6.2: Detecta si las llamadas de LLM se superponen en la sección crítica de una misma sesión. */
private class ConcurrencyProbeLlmClient : MemoryLlmClient {
    private var concurrent = 0
    var maxConcurrent = 0
        private set

    override suspend fun callText(
        systemPrompt: String,
        userContent: String,
        model: Model?,
        temperature: Float,
        maxTokens: Int,
        timeoutMs: Long,
    ): String {
        concurrent++
        maxConcurrent = maxOf(maxConcurrent, concurrent)
        // ventana fija:Cuando no exista bloqueo, dentro de esta ventana entrará la segunda llamada a la misma sesión.,Exponiendo así la superposición.
        delay(200)
        concurrent--
        return "### hechos importantes\n- hechos\n### que paso\n- pasó"
    }
}
