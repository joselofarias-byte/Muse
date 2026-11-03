package io.zer0.muse.data

import io.zer0.ai.core.Model
import io.zer0.memory.fact.DedupVerdict
import io.zer0.memory.llm.MemoryLlmClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v12: LlmFactDedupJudge Pruebas de comportamiento de análisis y degradación.(No es realmente melodioso LLM). */
class LlmFactDedupJudgeTest {

    private class StubLlm(private val response: String) : MemoryLlmClient {
        var lastSystem: String? = null
        override suspend fun callText(
            systemPrompt: String,
            userContent: String,
            model: Model?,
            temperature: Float,
            maxTokens: Int,
            timeoutMs: Long,
        ): String {
            lastSystem = systemPrompt
            return response
        }
    }

    @Test
    fun `parses clean json`() = runTest {
        val judge = LlmFactDedupJudge(StubLlm("""{"same": true, "confidence": 0.95, "reason": "lo mismo"}"""))
        val v = judge.judge("a", "b", null, null)
        assertTrue(v.same)
        assertTrue(v.highConfidenceSame)
    }

    @Test
    fun `parses json inside markdown fence`() = runTest {
        val judge = LlmFactDedupJudge(StubLlm("```json\n{\"same\": false, \"confidence\": 0.8, \"reason\": \"diferente\"}\n```"))
        val v = judge.judge("a", "b", null, null)
        assertFalse(v.same)
    }

    @Test
    fun `parses json with extra text around`() = runTest {
        val judge = LlmFactDedupJudge(StubLlm("OK,El análisis es el siguiente.:\n{\"same\": true, \"confidence\": 0.9, \"reason\": \"Misma semántica\"}\nTerminado"))
        val v = judge.judge("a", "b", null, null)
        assertTrue(v.same)
    }

    @Test
    fun `llm exception degrades to not same`() = runTest {
        val llm = object : MemoryLlmClient {
            override suspend fun callText(
                systemPrompt: String,
                userContent: String,
                model: Model?,
                temperature: Float,
                maxTokens: Int,
                timeoutMs: Long,
            ): String = throw RuntimeException("timeout")
        }
        val judge = LlmFactDedupJudge(llm)
        val v = judge.judge("a", "b", null, null)
        assertFalse("Las excepciones deben degradarse a un nivel diferente.", v.same)
    }

    @Test
    fun `garbage output degrades to not same`() = runTest {
        val judge = LlmFactDedupJudge(StubLlm("Este es el modelo chateando en lugar de emitiendo JSON"))
        val v = judge.judge("a", "b", null, null)
        assertFalse("No se puede analizar, debe degradarse a diferente", v.same)
        assertEquals(DedupVerdict.NOT_SAME, v)
    }

    @Test
    fun `prompt mentions redacted placeholder semantics`() = runTest {
        val llm = StubLlm("""{"same": true, "confidence": 0.9, "reason": "ok"}""")
        val judge = LlmFactDedupJudge(llm)
        judge.judge("[REDACTED]Me gusta la fotografía", "A Zhang San le gusta la fotografía", "Zhang San", "Zhang San")
        assertTrue("system prompt debe contener [REDACTED] descripción semántica", llm.lastSystem!!.contains("[REDACTED]"))
        assertTrue("system prompt debe incluir guía física", llm.lastSystem!!.contains("entidad"))
    }
}
