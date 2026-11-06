package io.zer0.ai.anthropic

import io.zer0.ai.core.ChatRequest
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v2.6.14: Anthropic thinking Bloquear guardias de reproducción.
 *
 * Anthropic Verifique la firma de los bloques de pensamiento, de modo que solo se permitan reproducir bloques de pensamiento con "mismo modelo + firma":
 *  - Sin firma: sin reproducción (el envío solo será considerado como un bloqueo ilegal por parte del upstream);
 *  - Modelo cruzado: el bloque de pensamiento se eliminó en la salida del Proveedor y tampoco se reproduce;
 *  - Después de bajar de categoría, solo queda el bloque de texto en el turno del asistente y la solicitud sigue siendo legal.
 */
class AnthropicThinkingReplayGuardTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        Logger.enabled = false
    }

    @After
    fun tearDown() {
        server.shutdown()
        Logger.enabled = true
    }

    private fun enqueueOk() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":2}}
                """.trimIndent(),
            ),
        )
    }

    private fun provider(): AnthropicProvider = AnthropicProvider(
        ProviderConfig(
            id = "anthropic-guard-test",
            displayName = "Anthropic Guard Test",
            type = ProviderType.ANTHROPIC,
            baseUrl = server.url("/v1").toString(),
            apiKey = "sk-ant-test",
            specific = ProviderSpecificConfig.Anthropic(),
        ),
    )

    private fun request(history: UIMessage, modelId: String): ChatRequest = ChatRequest(
        messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "system prompt"),
            UIMessage(role = MessageRole.USER, content = "hello"),
            history,
            UIMessage(role = MessageRole.USER, content = "next"),
        ),
        model = Model(id = modelId, providerId = "test"),
        maxTokens = 512,
    )

    /** Saque el bloque de contenido del turno del asistente en el cuerpo de la solicitud. */
    private fun capturedAssistantBlocks(): JsonArray {
        val body = server.takeRequest().body.readUtf8()
        val messages: JsonArray = AppJson.parseToJsonElement(body).jsonObject["messages"] as JsonArray
        val assistant = messages.map { it.jsonObject }
            .first { it["role"]?.jsonPrimitive?.content == "assistant" }
        return assistant["content"] as JsonArray
    }

    private fun JsonArray.blocksOfType(type: String): List<JsonObject> =
        map { it.jsonObject }.filter { it["type"]?.jsonPrimitive?.content == type }

    @Test
    fun `signed thinking block is replayed for same model`() = runTest {
        enqueueOk()
        val history = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "answer",
            reasoning = "own thinking",
            thinkingSignature = "sig-own",
            modelId = "claude-sonnet-4-5",
        )

        provider().completeText(request(history, "claude-sonnet-4-5"))

        val thinking = capturedAssistantBlocks().blocksOfType("thinking").single()
        assertEquals("own thinking", thinking["thinking"]?.jsonPrimitive?.content)
        assertEquals("sig-own", thinking["signature"]?.jsonPrimitive?.content)
    }

    @Test
    fun `unsigned thinking block is not sent`() = runTest {
        enqueueOk()
        val history = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "answer",
            reasoning = "unsigned thinking",
            modelId = "claude-sonnet-4-5",
        )

        provider().completeText(request(history, "claude-sonnet-4-5"))

        val blocks = capturedAssistantBlocks()
        assertTrue("No envíes bloques de pensamiento sin firma.", blocks.blocksOfType("thinking").isEmpty())
        // Sigue siendo una solicitud legítima después de bajar de categoría: el texto se enviará como de costumbre
        assertEquals(
            "answer",
            blocks.blocksOfType("text").single()["text"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `cross model thinking block is not sent`() = runTest {
        enqueueOk()
        val history = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "answer",
            reasoning = "foreign thinking",
            thinkingSignature = "sig-other-model",
            modelId = "claude-haiku-4",
        )

        provider().completeText(request(history, "claude-sonnet-4-5"))

        val blocks = capturedAssistantBlocks()
        assertTrue("No se deben generar bloqueos de pensamiento al cruzar modelos.", blocks.blocksOfType("thinking").isEmpty())
        assertEquals(
            "answer",
            blocks.blocksOfType("text").single()["text"]?.jsonPrimitive?.content,
        )
    }
}
