package io.zer0.ai.openai

import io.zer0.ai.core.ChatRequest
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R-TEST-01: OpenAIProvider stream-guard Pruebas de regresión de máquinas de estados.
 *
 * Cubra los criterios clave después de la reparación del R-AI-01:
 *  - El nombre vacío tool_call vuelve a ser texto al finalizar(guard colgar/restaurar)
 *  - reasoning primero en llegar,content Termina normalmente cuando estás retrasado,Rebobinar sin error
 *  - Acabado vacío. La razón todavía funciona bien. Done
 *
 * La ruta de red completa para la parada anticipada que activa el respaldo sin transmisión viene dada por FirstEventWatchdogTest(orquestación alternativa)
 * con ProviderRequestBodySnapshotTest(Formato de solicitud sin transmisión)cobertura común;
 * MockWebServer Existe una competencia de tiempo entre la desconexión de SSE y las solicitudes secundarias de respaldo.,La simulación no se repite aquí.
 */
class StreamGuardTest {

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

    private fun provider(): OpenAIProvider = OpenAIProvider(
        ProviderConfig(
            id = "openai-test",
            displayName = "OpenAI Test",
            type = ProviderType.OPENAI,
            baseUrl = server.url("/v1").toString(),
            apiKey = "sk-test",
        ),
    )

    private fun request(content: String = "hello"): ChatRequest = ChatRequest(
        messages = listOf(UIMessage(role = MessageRole.USER, content = content)),
        model = Model(id = "gpt-4o-mini", providerId = "openai-test"),
        maxTokens = 64,
    )

    private fun sse(vararg events: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .setBody(events.joinToString(separator = "\n\n") { "data: $it\n" } + "\n\n")

    @Test
    fun `La llamada a la herramienta de nombre vacío vuelve a ser texto cuando termina`() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"content":"Uno, dos, tres, cuatro, cinco, seis, siete, ocho, noventa"},"finish_reason":null}]}""",
                """{"choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_1","function":{"name":"","arguments":"{\"city\":\"Pekín\"}"}}]},"finish_reason":null}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                """[DONE]""",
            ),
        )

        val events = withTimeout(10_000) { provider().streamChat(request()).toList() }

        assertTrue(
            "Los argumentos para la llamada a la herramienta de nombre vacío deben restaurarse a texto",
            events.any { it is ChatStreamEvent.ContentDelta && it.delta.contains("Pekín") },
        )
        assertFalse("no debe ser emitido ToolCallDelta", events.any { it is ChatStreamEvent.ToolCallDelta })
        assertTrue("Debería terminar con Listo", events.last() is ChatStreamEvent.Done)
    }

    @Test
    fun `reasoning No retrocederá accidentalmente cuando el contenido se retrase primero`() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"Ha sido un largo proceso de reflexión."},"finish_reason":null}]}""",
                """{"choices":[{"index":0,"delta":{"content":"Contenido de la respuesta final"},"finish_reason":null}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                """[DONE]""",
            ),
        )

        val events = withTimeout(10_000) { provider().streamChat(request()).toList() }

        assertTrue("debería ser salida reasoning", events.any { it is ChatStreamEvent.ReasoningDelta })
        assertTrue("debería ser salida content", events.any { it is ChatStreamEvent.ContentDelta && it.delta == "Contenido de la respuesta final" })
        assertTrue("Debería terminar con Listo", events.last() is ChatStreamEvent.Done)
    }

    @Test
    fun `Final vacío La razón sigue siendo normal Done`() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"content":"Uno, dos, tres, cuatro, cinco, seis, siete, ocho, noventa"},"finish_reason":""}]}""",
                """[DONE]""",
            ),
        )

        val events = withTimeout(10_000) { provider().streamChat(request()).toList() }

        assertTrue(events.any { it is ChatStreamEvent.ContentDelta })
        assertTrue("Debería funcionar bien después de un final vacío. Done", events.last() is ChatStreamEvent.Done)
    }

    @Test
    fun `Después de desconectar el nombre de la herramienta vacía, se debe completar el respaldo sin transmisión y se debe devolver la llamada a la herramienta`() = runBlocking {
        val malformedToolChunks = (0..10).map { index ->
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":$index,"id":"call_bad_$index","function":{"name":"","arguments":"{\"chunk\":\"$index-very-long\"}"}}]},"finish_reason":null}]}"""
        }
        server.enqueue(
            sse(
                *(
                    malformedToolChunks + listOf(
                        """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
                        """[DONE]""",
                    )
                    ).toTypedArray(),
            ),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"choices":[{"index":0,"message":{"role":"assistant","content":"","tool_calls":[{"id":"call_fallback","type":"function","function":{"name":"echo","arguments":"{\"text\":\"ok\"}"}}]},"finish_reason":"tool_calls"}]}""",
                ),
        )

        val events = withTimeout(10_000) { provider().streamChat(request()).toList() }

        assertTrue(
            "El respaldo sin transmisión debe devolver el nombre válido de la herramienta a la capa superior",
            events.any { it is ChatStreamEvent.ToolCallDelta && it.name == "echo" },
        )
        assertTrue(
            "El respaldo sin transmisión debe devolver los parámetros de la herramienta a la capa superior",
            events.any { it is ChatStreamEvent.ToolCallDelta && it.argumentsDelta?.contains("\"ok\"") == true },
        )
        assertTrue("Una vez completada la reversión, Flow debe emitir Done", events.last() is ChatStreamEvent.Done)
    }

    @Test
    fun `Cuando el respaldo sin transmisión falla continuamente, se debe emitir un error en lugar del cierre de solo hecho`() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"content":"a"},"finish_reason":"stop"}]}""",
                """[DONE]""",
            ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"upstream unavailable"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"upstream unavailable"}}"""),
        )

        val events = withTimeout(10_000) { provider().streamChat(request()).toList() }

        assertTrue(
            "Si la reversión falla continuamente, se debe pasar un error claro a la capa superior.",
            events.any { it is ChatStreamEvent.Error },
        )
        assertFalse(
            "El error de reversión no se puede disfrazar como una finalización normal de solo hecho",
            events.last() is ChatStreamEvent.Done,
        )
    }

    /**
     * v2.6.1 (#5): Se encontró un recurso alternativo sin transmisión finish_reason=tool_calls cuando,reasoning No cuentes toda la historia.
     *
     * Medición real del usuario: cuando el modelo se detiene para ajustar la herramienta pero la llamada no se analiza, la lógica anterior reasoning_content
     * v2.6.1 (#5) → v2.7 Medidas de despido (confirmación del usuario“Está bien vomitar, pero no vaciar.”).
     *
     * Ruta de activación: transmisión solo de razonamiento (content es 0)→ stream-guard La sentencia terminó prematuramente →
     * Respaldo sin transmisión → El contenido de la respuesta alternativa está vacío,reasoning_content No vacío,finish_reason=tool_calls
     * **Pero no se analizó ni una sola llamada a la herramienta.**(Malformación del protocolo/degradación aguas arriba).
     * issue #5 Plan C (v2.6.4 (de): Malformación del protocolo**No vomitar por primera vez. reasoning**, pero un error provoca un reintento.
     *
     * Ruta de activación: transmisión solo de razonamiento (content es 0)→ stream-guard La sentencia terminó prematuramente →
     * Respaldo sin transmisión → El contenido de la respuesta alternativa está vacío,reasoning_content No vacío,finish_reason=tool_calls
     * **Pero no se analizó ni una sola llamada a la herramienta.**(Malformación del protocolo/degradación aguas arriba).
     *
     * Comportamiento anterior: trate el razonamiento directamente como texto (los usuarios ven fugas de pensamiento, consulte el número 5).
     * Nuevo comportamiento: informe un error primero y deje que la capa superior lo reenvíe; Sólo retirará la garantía si no se puede guardar. Nunca vacío.
     */
    @Test
    fun `La reversión sin transmisión no se detecta la primera vez que el protocolo de la herramienta tiene un formato incorrecto reasoning`() = runBlocking {
        val thinkingText = "Primero déjame determinar si necesito llamar a la herramienta.,Este paso es el razonamiento interno."
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"$thinkingText"},"finish_reason":null}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
                """[DONE]""",
            ),
        )
        // Respuesta alternativa sin transmisión:content vacío,reasoning_content No vacío,finish_reason=tool_calls, ninguno tool_calls
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"choices":[{"index":0,"message":{"role":"assistant","content":"","reasoning_content":"$thinkingText"},""" +
                        """"finish_reason":"tool_calls"}]}""",
                ),
        )

        val events = withTimeout(15_000) { provider().streamChat(request()).toList() }

        assertFalse(
            "Por primera vez, el razonamiento no debe utilizarse como texto del protocolo deformidad (para evitar fugas de pensamiento)",
            events.any { it is ChatStreamEvent.ContentDelta && it.delta.contains("razonamiento interno") },
        )
        assertTrue(
            "Se debe informar un error para que la capa superior pueda volver a intentarlo.",
            events.any { it is ChatStreamEvent.Error },
        )
        assertTrue(
            "reasoning todavía debería emitirse como un proceso de pensamiento",
            events.any { it is ChatStreamEvent.ReasoningDelta },
        )
    }

    /**
     * Contraste: no utilice el razonamiento como texto cuando lo llame una herramienta (#5 apelación original).
     *
     * Cuando el modelo se detiene para ajustar las herramientas, las rondas posteriores continuarán produciendo resultados; Si tratas tus pensamientos como texto, aparecerán directamente en la pantalla.
     */
    @Test
    fun `El respaldo sin transmisión encuentra llamadas a herramientas y no revela los detalles cuando hay llamadas a herramientas`() = runBlocking {
        val thinkingText = "Primero déjame determinar si necesito llamar a la herramienta.,Este paso es un razonamiento interno y no debe mostrarse en la pantalla."
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"$thinkingText"},"finish_reason":null}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
                """[DONE]""",
            ),
        )
        // Respuesta alternativa sin transmisión: con completa tool_calls
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"choices":[{"index":0,"message":{"role":"assistant","content":"","reasoning_content":"$thinkingText",""" +
                        """"tool_calls":[{"id":"call_1","type":"function","function":{"name":"echo","arguments":"{}"}}]},""" +
                        """"finish_reason":"tool_calls"}]}""",
                ),
        )

        val events = withTimeout(15_000) { provider().streamChat(request()).toList() }

        assertTrue(
            "La llamada a la herramienta debe devolverse a la capa superior.",
            events.any { it is ChatStreamEvent.ToolCallDelta },
        )
        assertFalse(
            "Cuando hay que ejecutar una llamada a una herramienta,reasoning No cuentes toda la historia",
            events.any { it is ChatStreamEvent.ContentDelta && it.delta.contains("razonamiento interno") },
        )
    }

    /**
     * v2.6.1 (#5) Comparación inversa: verdadera·reasoning-only Modelo (motivo de parada distinto de tool_calls)
     * La línea final aún debe conservarse; de ​​lo contrario, el usuario verá una respuesta vacía.
     */
    @Test
    fun `El respaldo sin transmisión mantiene el razonamiento cuando se encuentran motivos de detención comunes`() = runBlocking {
        val thinkingText = "Este es un contenido de pensamiento relativamente largo que se utiliza para desencadenar el final prematuro del flujo y retener el comportamiento evasivo."
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"$thinkingText"},"finish_reason":null}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                """[DONE]""",
            ),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"choices":[{"index":0,"message":{"role":"assistant","content":"","reasoning_content":"$thinkingText"},""" +
                        """"finish_reason":"stop"}]}""",
                ),
        )

        val events = withTimeout(15_000) { provider().streamChat(request()).toList() }

        assertTrue(
            "Por razones comunes para detenerse, se debe escribir solo razonamiento en el texto principal para evitar respuestas vacías.",
            events.any { it is ChatStreamEvent.ContentDelta && it.delta.contains("Piensa en el contenido") },
        )
    }
}
