package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2.6: SSE fixture replay — Texto vacío/solo razonamiento/repetición de cuadros/Marcos fuera de servicio/fotograma tras error/
 * finish continuación delta/herramienta posterior/Prueba de corte de comportamiento normalizado para cada escenario.
 *
 * También cubre el núcleo de aceptación M2.:Agregación de eventos de transmisión y no transmisión para la misma respuesta lógica
 * [ChatCompletion.toEventSequence] Convertir,eventualmente [NormalizedChatResult] Equivalente.
 */
class ChatEventNormalizationTest {

    // ── toEventSequence:sin transmisión → secuencia de eventos ───────────────────────────

    @Test
    fun `empty text with reasoning only still yields valid event sequence`() {
        val completion = ChatCompletion(
            text = "",
            reasoningContent = "internal scratchpad",
        )
        val events = completion.toEventSequence()
        assertTrue(events.first() is ChatStreamEvent.ReasoningDelta)
        assertTrue(events.last() is ChatStreamEvent.Done)
        // Sólo el razonamiento produce también resultados agregados válidos.(No informar falsamente texto vacío)
        val result = events.normalize()
        assertEquals("", result.text)
        assertEquals("internal scratchpad", result.reasoning)
    }

    @Test
    fun `non-streaming completion converts to full event sequence in contract order`() {
        val completion = ChatCompletion(
            text = "answer",
            finishReason = "stop",
            toolCalls = listOf(ToolCall(id = "call_1", name = "lookup", arguments = "{\"q\":\"x\"}")),
            reasoningContent = "think",
            usageTokens = UsageTokens(promptTokens = 3, completionTokens = 5),
            citationUrls = listOf("https://a.example"),
            thinkingSignature = "sig",
        )
        val kinds = completion.toEventSequence().map { it::class.simpleName }
        assertEquals(
            listOf(
                "ReasoningDelta",
                "ContentDelta",
                "ToolCallDelta",
                "UsageDelta",
                "CitationDelta",
                "Done",
            ),
            kinds,
        )
    }

    @Test
    fun `empty completion still emits done`() {
        val events = ChatCompletion(text = "").toEventSequence()
        assertEquals(listOf("Done"), events.map { it::class.simpleName })
    }

    // ── Reglas de agregación de transmisión ──────────────────────────────────────────────────

    @Test
    fun `content and reasoning deltas accumulate in arrival order`() {
        val result = listOf<ChatStreamEvent>(
            ChatStreamEvent.ReasoningDelta("a"),
            ChatStreamEvent.ReasoningDelta("b"),
            ChatStreamEvent.ContentDelta("hel"),
            ChatStreamEvent.ContentDelta("lo"),
            ChatStreamEvent.Done("stop"),
        ).normalize()
        assertEquals("hello", result.text)
        assertEquals("ab", result.reasoning)
        assertEquals("stop", result.finishReason)
    }

    @Test
    fun `duplicate snapshot frames are replaced not appended`() {
        val reducer = ChatEventReducer()
        val snapshot = ChatStreamEvent.ToolCallDelta(
            index = 0,
            id = "call_1",
            name = "lookup",
            argumentsDelta = "{\"q\":\"x\"}",
            isSnapshot = true,
        )
        reducer.reduce(snapshot)
        // Reproducir el mismo cuadro de instantánea(Repetir escena de fotograma):La semántica de reemplazo elimina naturalmente la duplicación
        reducer.reduce(snapshot)
        val result = reducer.toResult()
        assertEquals(1, result.toolCalls.size)
        assertEquals("{\"q\":\"x\"}", result.toolCalls.single().arguments)
    }

    @Test
    fun `incremental argument fragments accumulate across frames`() {
        val result = listOf<ChatStreamEvent>(
            ChatStreamEvent.ToolCallDelta(index = 0, id = "call_1", name = "f", argumentsDelta = "{\"a\":"),
            ChatStreamEvent.ToolCallDelta(index = 0, argumentsDelta = "1}"),
            ChatStreamEvent.Done("tool_calls"),
        ).normalize()
        assertEquals(1, result.toolCalls.size)
        assertEquals("{\"a\":1}", result.toolCalls.single().arguments)
        assertEquals("call_1", result.toolCalls.single().id)
        assertEquals("f", result.toolCalls.single().name)
    }

    @Test
    fun `out of order tool call indexes are sorted by index`() {
        val result = listOf<ChatStreamEvent>(
            tc(2, "c", "third"),
            tc(0, "a", "first"),
            tc(1, "b", "second"),
            ChatStreamEvent.Done("tool_calls"),
        ).normalize()
        assertEquals(listOf("a", "b", "c"), result.toolCalls.map { it.id })
    }

    /** Asistencia para pruebas fuera de servicio:Construya un marco de instantánea completo. */
    private fun tc(index: Int, id: String, name: String): ChatStreamEvent.ToolCallDelta =
        ChatStreamEvent.ToolCallDelta(index = index, id = id, name = name, argumentsDelta = "{}", isSnapshot = true)

    @Test
    fun `deltas after done are dropped and counted`() {
        val result = listOf<ChatStreamEvent>(
            ChatStreamEvent.ContentDelta("final"),
            ChatStreamEvent.Done("stop"),
            // finish después delta(Excepción de protocolo):No se permite agregarlo al texto.
            ChatStreamEvent.ContentDelta("GHOST"),
            ChatStreamEvent.ReasoningDelta("GHOST"),
            ChatStreamEvent.ToolCallDelta(index = 0, id = "x", name = "y", argumentsDelta = "{}", isSnapshot = true),
        ).normalize()
        assertEquals("final", result.text)
        assertNull(result.reasoning)
        assertTrue(result.toolCalls.isEmpty())
        assertEquals(3, result.postFinishFramesDropped)
    }

    @Test
    fun `deltas after done with null finish reason are dropped too`() {
        // Revisión de corrección(P2)Regresar:Done(null) El mismo es el estado final.,Los fotogramas incrementales posteriores deben descartarse
        val result = listOf<ChatStreamEvent>(
            ChatStreamEvent.ContentDelta("final"),
            ChatStreamEvent.Done(null),
            ChatStreamEvent.ContentDelta("GHOST"),
            ChatStreamEvent.ReasoningDelta("GHOST"),
            ChatStreamEvent.ToolCallDelta(index = 0, id = "x", name = "y", argumentsDelta = "{}", isSnapshot = true),
        ).normalize()
        assertEquals("final", result.text)
        assertNull(result.finishReason)
        assertNull(result.reasoning)
        assertTrue(result.toolCalls.isEmpty())
        assertEquals(3, result.postFinishFramesDropped)
    }

    @Test
    fun `error after partial content keeps partial text`() {
        val result = listOf<ChatStreamEvent>(
            ChatStreamEvent.ContentDelta("partial answer"),
            ChatStreamEvent.Error("connection reset"),
        ).normalize()
        assertEquals("partial answer", result.text)
        assertEquals("connection reset", result.error)
        assertFalse(result.interrupted)
    }

    @Test
    fun `stream interrupted preserves content and sets interrupted flag`() {
        val result = listOf<ChatStreamEvent>(
            ChatStreamEvent.ContentDelta("half of the answer"),
            ChatStreamEvent.StreamInterrupted("network switched"),
        ).normalize()
        assertEquals("half of the answer", result.text)
        assertTrue(result.interrupted)
        assertEquals("network switched", result.error)
    }

    @Test
    fun `usage last wins and citations dedupe preserving order`() {
        val result = listOf<ChatStreamEvent>(
            ChatStreamEvent.UsageDelta(UsageTokens(promptTokens = 1, completionTokens = 2)),
            ChatStreamEvent.UsageDelta(UsageTokens(promptTokens = 4, completionTokens = 8)),
            ChatStreamEvent.CitationDelta(listOf("https://a", "https://b")),
            ChatStreamEvent.CitationDelta(listOf("https://b", "https://c")),
            ChatStreamEvent.Done("stop"),
        ).normalize()
        assertEquals(UsageTokens(promptTokens = 4, completionTokens = 8), result.usage)
        assertEquals(listOf("https://a", "https://b", "https://c"), result.citationUrls)
    }

    @Test
    fun `tool continuation frames across rounds aggregate into one call`() {
        // Continuación de la herramienta:La fragmentación de parámetros abarca múltiples SSE chunk,Y la identificación/nombre solo aparece en el primer cuadro.
        val result = listOf<ChatStreamEvent>(
            ChatStreamEvent.ToolCallDelta(index = 0, id = "call_9", name = "run_sql", argumentsDelta = "{\"sql\":\"SE"),
            ChatStreamEvent.ToolCallDelta(index = 0, argumentsDelta = "LECT 1\"}"),
            ChatStreamEvent.Done("tool_calls"),
        ).normalize()
        assertEquals(
            listOf(ToolCall(id = "call_9", name = "run_sql", arguments = "{\"sql\":\"SELECT 1\"}")),
            result.toolCalls,
        )
        assertEquals("tool_calls", result.finishReason)
    }

    // ── Equivalente de proyección final en streaming/no streaming(M2 Aceptación) ──────────────────

    @Test
    fun `streaming aggregation equals non-streaming normalization for the same logical response`() {
        // sin transmisión:Resultados completos de una sola vez
        val completion = ChatCompletion(
            text = "respuesta final",
            finishReason = "stop",
            toolCalls = listOf(ToolCall(id = "call_1", name = "lookup", arguments = "{\"q\":\"muse\"}")),
            reasoningContent = "proceso de pensamiento",
            usageTokens = UsageTokens(promptTokens = 10, completionTokens = 20),
            citationUrls = listOf("https://a.example"),
            thinkingSignature = "sig-1",
            thinkingEncryptedContent = "enc-1",
        )

        // transmisión:La misma respuesta lógica llega fragmentada(Índice desordenado + deduplicación de referencia;Las firmas vienen con marcos de razonamiento.)
        val streamed = listOf<ChatStreamEvent>(
            ChatStreamEvent.ReasoningDelta("proceso de ", signature = "sig-1", encryptedContent = "enc-1"),
            ChatStreamEvent.ReasoningDelta("pensamiento"),
            ChatStreamEvent.ContentDelta("respuesta "),
            ChatStreamEvent.ContentDelta("final"),
            ChatStreamEvent.ToolCallDelta(
                index = 0,
                id = "call_1",
                name = "lookup",
                argumentsDelta = "{\"q\":",
            ),
            ChatStreamEvent.ToolCallDelta(index = 0, argumentsDelta = "\"muse\"}"),
            ChatStreamEvent.UsageDelta(UsageTokens(promptTokens = 10, completionTokens = 20)),
            ChatStreamEvent.CitationDelta(listOf("https://a.example")),
            ChatStreamEvent.Done("stop"),
        )

        val fromNonStreaming = completion.toEventSequence().normalize()
        val fromStreaming = streamed.normalize()

        // texto/pensar/Herramientas/equivalencia de referencia;finishReason equivalente
        assertEquals(fromStreaming.text, fromNonStreaming.text)
        assertEquals(fromStreaming.reasoning, fromNonStreaming.reasoning)
        assertEquals(fromStreaming.toolCalls, fromNonStreaming.toolCalls)
        assertEquals(fromStreaming.finishReason, fromNonStreaming.finishReason)
        assertEquals(fromStreaming.usage, fromNonStreaming.usage)
        assertEquals(fromStreaming.citationUrls, fromNonStreaming.citationUrls)
        assertEquals(fromStreaming.thinkingSignature, fromNonStreaming.thinkingSignature)
        assertEquals(fromStreaming.thinkingEncryptedContent, fromNonStreaming.thinkingEncryptedContent)
        // Streaming sin errores/interrumpir/Excepción de protocolo
        assertNull(fromStreaming.error)
        assertFalse(fromStreaming.interrupted)
        assertEquals(0, fromStreaming.postFinishFramesDropped)
    }
}
