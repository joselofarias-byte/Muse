package io.zer0.muse.ui

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolCallInfo
import io.zer0.ai.core.UIMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cálculo previo de palabras clave para obras ilustradas.
 *
 * Antes del cambio, era "escanear cada elemento visible hacia adelante", el total O(n²) Y el mismo parámetro se analiza repetidamente.
 * Después del cambio, la lista se puede escanear de una sola vez, por lo que es necesario aclarar dos cosas aquí:
 *  - Las palabras clave obtenidas para cada mensaje son exactamente las mismas que los resultados del escaneo hacia adelante uno por uno (incluido "Si el más reciente está vacío, no se realizarán más búsquedas");
 *  - JSON se analiza solo una vez por llamada de compilación.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageGenPromptIndexTest {

    // ── datos de casos de uso ────────────────────────────────────────────────────────

    private fun user(text: String) = UIMessage(role = MessageRole.USER, content = text)

    private fun assistant(text: String) = UIMessage(role = MessageRole.ASSISTANT, content = text)

    /** La llamada a la herramienta todavía está en la memoria (toolCalls camino). */
    private fun genCallMessage(prompt: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        content = "",
        toolCalls = listOf(ToolCall(id = "call-1", name = "generate_image", arguments = """{"prompt":"$prompt"}""")),
    )

    /** Ruta de persistencia para la reproducción después del reinicio (toolCallInfo camino). */
    private fun genInfoMessage(arguments: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        content = "",
        toolCallInfo = ToolCallInfo(
            toolName = "generate_image",
            arguments = arguments,
            result = "ok",
            isSuccess = true,
        ),
    )

    /** Implementación antes del cambio: el mensaje de índice se busca hacia arriba para generar la imagen más reciente y cada mensaje se escanea de forma independiente. */
    private fun legacyPrompts(messages: List<UIMessage>): Map<String, String?> = messages.indices.associate { index ->
        val genCall =
            messages.take(index + 1).asReversed().firstNotNullOfOrNull { m ->
                val fromCalls = m.toolCalls?.firstOrNull { it.name == "generate_image" }
                val fromInfo = m.toolCallInfo?.takeIf { it.toolName == "generate_image" }
                when {
                    fromCalls != null -> fromCalls.arguments
                    fromInfo != null -> fromInfo.arguments
                    else -> null
                }
            }
        val prompt =
            genCall
                ?.let { args -> runCatching { JSONObject(args).optString("prompt") }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
                ?.let { if (it.length > 60) it.take(60) + "…" else it }
        messages[index].id.toString() to prompt
    }

    private fun promptOf(args: String): String? =
        runCatching { JSONObject(args).optString("prompt") }.getOrNull()

    // ── caso de uso ────────────────────────────────────────────────────────────

    @Test
    fun `precomputed prompts equal the per message forward scan`() {
        val messages = listOf(
            user("dibujar un gato"),
            genCallMessage("primer gato"),
            assistant("Está bien, ven ahora mismo"),
            genInfoMessage("""{"prompt":"el segundo perro"}"""),
            assistant("Completo"),
        )

        var parseCount = 0
        val actual = imageGenPromptsByMessage(messages) { args ->
            parseCount++
            promptOf(args)
        }

        assertEquals("El resultado debe ser coherente con el escaneo hacia adelante uno por uno.", legacyPrompts(messages), actual)
        assertEquals("Las llamadas de dos generaciones solo deben analizar JSON dos veces (anteriormente, cada mensaje se analizaba una vez)", 2, parseCount)

        val ids = messages.map { it.id.toString() }
        assertNull("El primer mensaje no está precedido por ninguna llamada de compilación.", actual[ids[0]])
        assertEquals("primer gato", actual[ids[1]])
        assertEquals("Utilice la palabra más reciente al revés", "primer gato", actual[ids[2]])
        assertEquals("el segundo perro", actual[ids[3]])
        assertEquals("el segundo perro", actual[ids[4]])
    }

    @Test
    fun `blank prompt yields null without scanning further back`() {
        val messages = listOf(
            genCallMessage("el primero"),
            genInfoMessage("""{"prompt":"   "}"""),
            assistant("fin"),
        )

        var parseCount = 0
        val actual = imageGenPromptsByMessage(messages) { args ->
            parseCount++
            promptOf(args)
        }

        assertEquals("Cuando la palabra del mensaje más reciente está vacía, el valor general es nulo y no retrocede a un momento anterior.", legacyPrompts(messages), actual)
        assertEquals(2, parseCount)
        val ids = messages.map { it.id.toString() }
        assertEquals("el primero", actual[ids[0]])
        assertNull(actual[ids[1]])
        assertNull(actual[ids[2]])
    }

    @Test
    fun `long prompt is truncated exactly like before`() {
        val long = "largo".repeat(80)
        val messages = listOf(genCallMessage(long), assistant("bueno"))

        val actual = imageGenPromptsByMessage(messages)
        val ids = messages.map { it.id.toString() }

        assertEquals("Las palabras breves extralargas están limitadas a 60 caracteres y agregan puntos suspensivos.", "largo".repeat(12) + "…", actual[ids[0]])
        assertEquals(legacyPrompts(messages), actual)
    }

    @Test
    fun `messages without any generation keep a null prompt`() {
        val messages = listOf(user("Hola"), assistant("en"))

        var parseCount = 0
        val actual = imageGenPromptsByMessage(messages) { args ->
            parseCount++
            promptOf(args)
        }

        assertEquals("No se deben analizar parámetros sin generar una llamada", 0, parseCount)
        assertEquals(
            messages.map { it.id.toString() }.associateWith { null },
            actual,
        )
    }
}
