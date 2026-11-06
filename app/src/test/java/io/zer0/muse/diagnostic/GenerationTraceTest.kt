package io.zer0.muse.diagnostic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.common.AppJson
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GenerationTrace Verificación de comportamiento central:
 *  - Límite de capacidad del búfer circular
 *  - sessionId Desensibilización al hash (sin texto original)
 *  - JSONL El formato de colocación es legal.
 *  - Cualquier campo no contiene texto (línea roja)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GenerationTraceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun summary(
        sessionId: String = "s1",
        modelId: String = "test-model",
        outcome: String = "completed",
        contentChars: Int = 50,
        reasoningChars: Int = 0,
    ): GenerationTrace.GenerationSummary = GenerationTrace.GenerationSummary(
        sessionId = sessionId,
        modelId = modelId,
        providerType = "openai",
        outcome = outcome,
        elapsedMs = 1000,
        ttftMs = 100,
        contentChars = contentChars,
        reasoningChars = reasoningChars,
        toolCallCount = 0,
        round = 1,
        uiFlushCount = 3,
    )

    @Before
    fun setUp() {
        GenerationTrace.init(context)
        GenerationTrace.clearForTest()
    }

    @After
    fun tearDown() {
        GenerationTrace.clearForTest()
    }

    @Test
    fun `ring buffer caps at RING_CAPACITY`() {
        repeat(GenerationTrace.RING_CAPACITY + 5) { i ->
            GenerationTrace.record(summary(sessionId = "session-$i"))
        }
        assertEquals(GenerationTrace.RING_CAPACITY, GenerationTrace.snapshot().size)
    }

    @Test
    fun `sessionId is hashed not stored raw`() {
        val rawSessionId = "very-secret-session-id-12345"
        GenerationTrace.record(summary(sessionId = rawSessionId))
        val rec = GenerationTrace.snapshot().single()
        assertEquals(8, rec.sessionHash.length)
        assertTrue(rec.sessionHash.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(rec.sessionHash != rawSessionId)
    }

    @Test
    fun `record fields contain no message content`() {
        GenerationTrace.record(summary(modelId = "gpt-4", outcome = "failed", reasoningChars = 10))
        val json = AppJson.encodeToString(GenerationTrace.TraceRecord.serializer(), GenerationTrace.snapshot().single())
        val obj = AppJson.parseToJsonElement(json).jsonObject
        // Aserción de línea roja: no hay texto en el resultado de la serialización/prompt/content campo
        val forbiddenKeys = setOf("content", "prompt", "message", "text", "body", "apikey")
        obj.keys.forEach { key ->
            assertTrue("campo '$key' No se permite aparecer en registros de seguimiento", key.lowercase() !in forbiddenKeys)
        }
    }

    @Test
    fun `trace file is appended on record`() {
        GenerationTrace.record(summary())
        val file = GenerationTrace.traceFileOrNull()
        assertTrue("trace El archivo debería haberse colocado en el disco.", file != null && file.length() > 0)
        file!!.readLines().forEach { line ->
            if (line.isBlank()) return@forEach
            val parsed = AppJson.parseToJsonElement(line).jsonObject
            assertTrue(parsed.containsKey("outcome"))
            assertTrue(parsed.containsKey("sessionHash"))
        }
    }

    @Test
    fun `noteStreamEnd classification is captured`() {
        GenerationTrace.noteStreamEnd("closed_no_finish", null)
        GenerationTrace.record(summary())
        val rec = GenerationTrace.snapshot().single()
        assertEquals("closed_no_finish", rec.streamEndKind)
        assertEquals("", rec.finishReason)
    }
}
