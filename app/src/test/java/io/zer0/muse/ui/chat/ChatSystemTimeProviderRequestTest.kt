package io.zer0.muse.ui.chat

import io.zer0.ai.ChatService
import io.zer0.ai.ProviderConfigStore
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Demuestre que el tiempo actual fluye desde el resultado del ensamblaje del chat hasta el cuerpo final de la solicitud compatible con OpenAI.
 *
 * Esta prueba no reemplaza el dispositivo ChatGenerationController/UI aceptación;
 * solo se bloquea“ChatService/Provider La serialización no pierde los mensajes del SISTEMA”borde.
 */
class ChatSystemTimeProviderRequestTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `current time system message reaches final provider request`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""),
        )
        val model = Model(id = "time-test-model", providerId = "time-test")
        val config = ProviderConfig(
            id = "time-test",
            displayName = "Time Test",
            type = ProviderType.OPENAI,
            baseUrl = server.url("/v1").toString(),
            apiKey = "test-key",
            models = listOf(model),
            specific = ProviderSpecificConfig.OpenAI(),
        )
        val service = ChatService(
            object : ProviderConfigStore {
                override suspend fun get(): ProviderConfig = config
            },
        )
        val messages = composeSystemPromptMessages(
            staticPrompt = "stable prompt",
            dynamicPrompt = "hora actual: 2026-10-05 12:34:56 lunes",
        ) + UIMessage(role = MessageRole.USER, content = "¿Qué hora es ahora?")

        service.completeText(messages = messages, model = model)

        val body = AppJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val serializedMessages = body["messages"]?.jsonArray.orEmpty()
        assertTrue(
            "La solicitud final del Proveedor debe conservar el mensaje del SISTEMA de hora actual.",
            serializedMessages.any { message ->
                message.jsonObject["role"]?.jsonPrimitive?.content == "system" &&
                    message.jsonObject["content"]?.jsonPrimitive?.content?.contains("hora actual:") == true
            },
        )
    }
}
