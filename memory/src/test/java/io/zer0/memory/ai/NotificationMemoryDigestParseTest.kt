package io.zer0.memory.ai

import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.Model
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.llm.MemoryLlmClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.6.2(comentarios de los usuarios): Notificación → Análisis y pruebas de regresión de palabras clave de preparadores de memoria.
 *
 * Bloqueado:LLM Salida JSON (con think/fences/Tanto el ruido delantero como el trasero) se pueden analizar correctamente en listas de hechos;
 * Las palabras clave incluyen los criterios de juicio de "vale la pena recordar" y "no vale la pena recordar" en el entorno chino.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationMemoryDigestParseTest {

    private val noopLlm = object : MemoryLlmClient {
        override suspend fun callText(
            systemPrompt: String,
            userContent: String,
            model: Model?,
            temperature: Float,
            maxTokens: Int,
            timeoutMs: Long,
        ): String = "{}"
    }

    private fun newDigest(): NotificationMemoryDigest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return NotificationMemoryDigest(
            factDbProvider = FactDbProvider(context),
            llmClient = noopLlm,
        )
    }

    @Test
    fun `plain json parses`() {
        val r = newDigest().parseResult("""{"facts": ["El envío urgente ha llegado a la estación de Cainiao", "Reunión mañana a las 10 en punto."]}""")
        assertEquals(listOf("El envío urgente ha llegado a la estación de Cainiao", "Reunión mañana a las 10 en punto."), r?.facts)
    }

    @Test
    fun `json inside markdown fence parses`() {
        val raw = "```json\n{\"facts\": [\"Fecha de amortización 5.\"]}\n```"
        assertEquals(listOf("Fecha de amortización 5."), newDigest().parseResult(raw)?.facts)
    }

    @Test
    fun `json after think block parses`() {
        val raw = "<think>Veamos primero qué notificaciones hay…</think>{\"facts\": [\"Cita de examen físico el miércoles.\"]}"
        assertEquals(listOf("Cita de examen físico el miércoles."), newDigest().parseResult(raw)?.facts)
    }

    @Test
    fun `json with leading noise parses`() {
        val raw = "Bien, aquí están los resultados:{\"facts\": [\"código de recogida 1234\"]} Terminado"
        assertEquals(listOf("código de recogida 1234"), newDigest().parseResult(raw)?.facts)
    }

    @Test
    fun `empty facts list parses`() {
        assertEquals(emptyList<String>(), newDigest().parseResult("""{"facts": []}""")?.facts)
    }

    @Test
    fun `missing facts field defaults to empty`() {
        assertEquals(emptyList<String>(), newDigest().parseResult("""{"other": 1}""")?.facts)
    }

    @Test
    fun `no json returns null`() {
        assertNull(newDigest().parseResult("En absoluto JSON"))
        assertNull(newDigest().parseResult(""))
    }

    @Test
    fun `system prompt contains keep and drop guidance`() {
        val prompt = NotificationMemoryDigest.buildSystemPrompt("zh-CN", null)
        assertTrue(prompt.contains("vale la pena recordar"))
        assertTrue(prompt.contains("No vale la pena recordar"))
        assertTrue(prompt.contains("facts"))
    }

    @Test
    fun `system prompt embeds existing memories when provided`() {
        val prompt = NotificationMemoryDigest.buildSystemPrompt("zh-CN", "- A los usuarios les gusta tomar té.")
        assertTrue(prompt.contains("A los usuarios les gusta tomar té."))
    }

    @Test
    fun `english prompt works too`() {
        val prompt = NotificationMemoryDigest.buildSystemPrompt("en-US", null)
        assertTrue(prompt.contains("Worth remembering"))
        assertTrue(prompt.contains("Not worth remembering"))
    }
}
