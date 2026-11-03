package io.zer0.ai.openai

import io.zer0.ai.core.ChatRequest
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.ToolDefinition
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v2.6.14: `reasoning_content` Guardia de fuente para reproducción (tomando el enlace de ensamblaje de solicitud real).
 *
 * Utilice MockWebServer para capturar el cuerpo de la solicitud real y bloquearlo:
 *  - El bloque de pensamiento histórico entre modelos no aparece en la solicitud (además: solo se dejan marcadores de posición de cadena vacía cuando el protocolo requiere que el campo debe existir);
 *  - Los bloques de pensamiento histórico del mismo modelo se reproducen con normalidad;
 *  - Los bloques de pensamiento producidos por el protocolo firmado (con firma/contenido_encriptado) no son reproducidos por contenido_razonamiento.
 */
class ReasoningReplayModelGuardWireTest {

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

    private val tool = ToolDefinition(
        name = "getWeather",
        description = "comprobar el clima",
        parametersJsonSchema = """{"type":"object","properties":{"city":{"type":"string"}}}""",
    )

    private fun provider(): OpenAIProvider = OpenAIProvider(
        ProviderConfig(
            id = "reasoning-guard-test",
            displayName = "Reasoning Guard Test",
            type = ProviderType.OPENAI,
            baseUrl = server.url("/v1").toString(),
            apiKey = "test-key",
            specific = ProviderSpecificConfig.OpenAI(),
        ),
    )

    private fun enqueueOk() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"total_tokens":7}}""",
            ),
        )
    }

    private fun request(
        history: UIMessage,
        modelId: String,
        withTools: Boolean,
    ): ChatRequest = ChatRequest(
        messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "system prompt"),
            UIMessage(role = MessageRole.USER, content = "hello"),
            history,
            UIMessage(role = MessageRole.USER, content = "next"),
        ),
        model = Model(id = modelId, providerId = "test"),
        maxTokens = 256,
        tools = if (withTools) listOf(tool) else null,
    )

    private fun capturedAssistantMessage(): JsonObject {
        val body = server.takeRequest().body.readUtf8()
        val messages: JsonArray = AppJson.parseToJsonElement(body).jsonObject["messages"] as JsonArray
        return messages.map { it.jsonObject }.first { it["content"]?.jsonPrimitive?.content == "answer" }
    }

    @Test
    fun `same model replays reasoning content`() = runTest {
        enqueueOk()
        val history = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "answer",
            reasoning = "own thinking",
            modelId = "deepseek-v4",
        )

        provider().completeText(request(history, "deepseek-v4", withTools = true))

        val message = capturedAssistantMessage()
        assertEquals("own thinking", message["reasoning_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `cross model reasoning never leaves the client`() = runTest {
        enqueueOk()
        val history = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "answer",
            reasoning = "foreign thinking",
            thinkingSignature = "sig-anthropic",
            modelId = "claude-sonnet-4-5",
        )

        provider().completeText(request(history, "deepseek-v4", withTools = true))

        val message = capturedAssistantMessage()
        // El contenido no será publicado;DeepSeek El protocolo requiere que el campo exista cuando se incluyen herramientas, por lo que solo se permite la ocupación de cadenas vacías.
        assertEquals("", message["reasoning_content"]?.jsonPrimitive?.content)
        assertFalse(message.toString().contains("foreign thinking"))
    }

    @Test
    fun `cross model reasoning is omitted when request carries no tools`() = runTest {
        enqueueOk()
        val history = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "answer",
            reasoning = "foreign thinking",
            modelId = "claude-sonnet-4-5",
        )

        provider().completeText(request(history, "deepseek-v4", withTools = false))

        val message = capturedAssistantMessage()
        assertNull("No se envía cuando no hay herramientas. reasoning_content", message["reasoning_content"])
        assertFalse(message.toString().contains("foreign thinking"))
    }

    @Test
    fun `signature borne thinking is not replayed through reasoning content`() = runTest {
        enqueueOk()
        // El historial no registra el modelo de salida (datos antiguos), pero el patrón de carga útil ha revelado que provino de un protocolo de tipo firma.
        val history = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "answer",
            reasoning = "responses reasoning",
            thinkingEncryptedContent = "enc-payload",
            modelId = null,
        )

        provider().completeText(request(history, "deepseek-v4", withTools = true))

        val message = capturedAssistantMessage()
        assertEquals("", message["reasoning_content"]?.jsonPrimitive?.content)
        assertFalse(message.toString().contains("responses reasoning"))
        assertTrue("La solicitud sigue siendo legal después de bajar de categoría (el texto se enviará como de costumbre)", message["content"]?.jsonPrimitive?.content == "answer")
    }
}
