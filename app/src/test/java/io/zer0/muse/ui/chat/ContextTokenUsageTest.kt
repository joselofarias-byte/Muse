package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.util.TokenEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContextTokenUsage] prueba de calibre.
 *
 * Concéntrate en tres cosas:
 *  1. Cuando no hay mensajes con uso en la sesión, los resultados deben compararse con estimaciones puramente locales.**byte por byte idéntico**(camino degradado, no se permiten nuevas diferencias);
 *  2. Cuando hay uso = Medición del nivel de agua real(prompt+completion) + Estimación después del nivel del agua y el sistema indica que no repite el cálculo;
 *  3. Cuando la medición real es significativamente menor que la estimación (valor cuestionable), la estimación se utiliza como límite inferior.
 */
class ContextTokenUsageTest {

    private val systemPrompt = "You are a helpful assistant. Responda en chino y sea conciso."

    private fun user(content: String, promptTokens: Int? = null, completionTokens: Int? = null, cachedTokens: Int? = null): UIMessage =
        UIMessage(
            role = MessageRole.USER,
            content = content,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cachedTokens = cachedTokens,
        )

    private fun assistant(content: String, promptTokens: Int? = null, completionTokens: Int? = null, cachedTokens: Int? = null): UIMessage =
        UIMessage(
            role = MessageRole.ASSISTANT,
            content = content,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cachedTokens = cachedTokens,
        )

    // ── 1. Ruta de degradación: ningún uso debe ser exactamente igual que la estimación pura ──────────────────────

    @Test
    fun `Sin ningún uso, es exactamente igual que la estimación puramente local`() {
        val messages = listOf(
            user("Ayúdame a ver la estructura del proyecto."),
            assistant("El proyecto se divide en aplicación,ai,memory Tres módulos."),
            user("Seamos más detallados y hablemos de las dependencias."),
            assistant("app Depende de la inteligencia artificial y la memoria,ai No depende de la memoria."),
        )
        val pure = TokenEstimator.estimate(messages, systemPrompt)
        assertEquals(
            "Sin uso, la ocupación debe ser igual a una estimación pura y sin sesgos.",
            pure,
            ContextTokenUsage.estimate(messages, systemPrompt),
        )
    }

    @Test
    fun `usage Los datos antiguos cuyos campos sean todos nulos también se devolverán a la estimación pura`() {
        val messages = listOf(
            user("Hola"),
            assistant("Hola, ¿en qué puedo ayudarte?"),
        )
        assertEquals(-1, ContextTokenUsage.lastMeasuredIndex(messages))
        assertEquals(
            TokenEstimator.estimate(messages, systemPrompt),
            ContextTokenUsage.estimate(messages, systemPrompt),
        )
    }

    @Test
    fun `promptTokens 0 significa que no hay usage`() {
        val messages = listOf(
            user("Hola"),
            assistant("Hola", promptTokens = 0, completionTokens = 128),
        )
        assertEquals(-1, ContextTokenUsage.lastMeasuredIndex(messages))
        assertEquals(
            TokenEstimator.estimate(messages, systemPrompt),
            ContextTokenUsage.estimate(messages, systemPrompt),
        )
    }

    @Test
    fun `Una lista vacía devuelve 0 sin generar una excepción`() {
        assertEquals(0, ContextTokenUsage.estimate(emptyList(), ""))
        assertEquals(0, TokenEstimator.estimate(emptyList(), ""))
    }

    // ── 2. Selección de línea de nivel de agua. ────────────────────────────────────────────────

    @Test
    fun `El nivel del agua toma el último mensaje del asistente con el uso`() {
        val messages = listOf(
            user("primera pregunta"),
            assistant("Primera respuesta", promptTokens = 1_000, completionTokens = 100),
            user("segunda pregunta"),
            assistant("Segunda respuesta", promptTokens = 2_000, completionTokens = 200),
            user("la tercera pregunta"),
            assistant("todavía no usage"),
        )
        assertEquals(3, ContextTokenUsage.lastMeasuredIndex(messages))
    }

    @Test
    fun `el uso en mensajes que no son del asistente no participa en las marcas de agua`() {
        val messages = listOf(
            user("entrada con números", promptTokens = 9_000, completionTokens = 9_000),
            assistant("Sin respuesta de uso"),
        )
        assertEquals(-1, ContextTokenUsage.lastMeasuredIndex(messages))
        assertEquals(
            TokenEstimator.estimate(messages, systemPrompt),
            ContextTokenUsage.estimate(messages, systemPrompt),
        )
    }

    // ── 3. algoritmo de fusión ────────────────────────────────────────────────────

    @Test
    fun `La medición real solo requiere una finalización rápida y sin superposición cached`() {
        // OpenAI El caché es un subconjunto del mensaje;UsageTokens.total También es del mismo calibre.
        val message = assistant("responder", promptTokens = 5_000, completionTokens = 800, cachedTokens = 4_000)
        assertEquals(5_800, ContextTokenUsage.measuredTokens(message))
    }

    @Test
    fun `El valor combinado es igual al nivel de agua estimado más el nivel de agua medido`() {
        val tail = user("Permítanme agregar una pregunta de seguimiento muy larga:" + "Detalles".repeat(40))
        val messages = listOf(
            user("primera pregunta"),
            assistant("Primera respuesta", promptTokens = 40_000, completionTokens = 1_000),
            tail,
        )
        val expected = 41_000 + TokenEstimator.estimate(listOf(tail))
        assertEquals(expected, ContextTokenUsage.estimate(messages, systemPrompt))
    }

    @Test
    fun `Si no hay novedades después del nivel del agua, sólo se contabilizará la medición real`() {
        val messages = listOf(
            user("pregunta"),
            assistant("responder", promptTokens = 50_000, completionTokens = 2_000),
        )
        assertEquals(52_000, ContextTokenUsage.estimate(messages, systemPrompt))
    }

    @Test
    fun `system prompt No incluido repetidamente en la estimación del segmento de cola`() {
        // El aviso largo del sistema ya está incluido en los tokens de aviso medidos; Si se vuelve a incluir la sección de cola, el resultado superará con creces el valor medido.
        val longSystemPrompt = "Las reglas son las siguientes." + "Cumpla estrictamente con los requisitos del formato de salida y no adjunte explicaciones innecesarias.".repeat(60)
        val messages = listOf(
            user("pregunta"),
            assistant("responder", promptTokens = 60_000, completionTokens = 1_500),
        )
        val actual = ContextTokenUsage.estimate(messages, longSystemPrompt)
        assertEquals(61_500, actual)
        assertTrue(
            "El segmento de la cola no se debe contar dos veces. mensaje del sistema:actual=$actual",
            actual <= 61_500,
        )
    }

    // ── 4. Medición real sospechosa: tome la estimación como límite inferior ──────────────────────────────────────

    @Test
    fun `Si la medida real es significativamente menor que la estimación, devuelva la estimación`() {
        val messages = listOf(
            user("Pregunta muy larga:" + "Por favor explique qué hace este código.".repeat(60)),
            assistant("Respuesta larga:" + "Aquí hacemos el análisis primero y luego la conversión.".repeat(60), promptTokens = 1, completionTokens = 1),
        )
        val pure = TokenEstimator.estimate(messages, systemPrompt)
        assertEquals(
            "El escaso uso sospechoso no puede reducir la tasa de ocupación por debajo de la mitad de la estimación",
            pure,
            ContextTokenUsage.estimate(messages, systemPrompt),
        )
    }

    @Test
    fun `blend Recurrir a la estimación cuando la medición real sea cero`() {
        assertEquals(1_000, ContextTokenUsage.blend(measured = 0, tailEstimate = 500, fullEstimate = 1_000))
        assertEquals(1_000, ContextTokenUsage.blend(measured = -1, tailEstimate = 0, fullEstimate = 1_000))
    }

    @Test
    fun `blend El límite de confianza inferior es exactamente la mitad de la estimación`() {
        // Valor combinado × 2 == estimación → No es inferior al límite inferior y se basa en mediciones reales.
        assertEquals(500, ContextTokenUsage.blend(measured = 500, tailEstimate = 0, fullEstimate = 1_000))
        // Si es menor, estará por debajo del límite inferior, así que utilice la estimación para obtener el resultado final.
        assertEquals(1_000, ContextTokenUsage.blend(measured = 499, tailEstimate = 0, fullEstimate = 1_000))
    }

    @Test
    fun `Si la medida real es mayor que la estimación, la medida real se adoptará directamente`() {
        // Hay una desviación entre el tokenizador real y cl100k, chino/Cuando la medición real de la escena multimodal es mayor, la medición real debe prevalecer.
        assertEquals(9_000, ContextTokenUsage.blend(measured = 8_000, tailEstimate = 1_000, fullEstimate = 2_000))
    }

    @Test
    fun `Cuando la estimación es 0, no afecta el valor medido real`() {
        // jtokkit Cuando la inicialización falla + texto vacío, se estima que es 0 y el valor medido real no se puede restablecer a cero.
        assertEquals(3_000, ContextTokenUsage.blend(measured = 3_000, tailEstimate = 0, fullEstimate = 0))
    }
}
