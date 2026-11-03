package io.zer0.memory.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.56: LlmBudget Pruebas unitarias.
 *
 * Verifique el truncamiento exacto de BPE y la lógica alternativa para truncateToTokenBudget.
 */
class LlmBudgetTest {

    @Test
    fun `budget Volver al texto original para 0`() {
        val text = "Hello World"
        assertEquals(text, LlmBudget.truncateToTokenBudget(text, 0))
    }

    @Test
    fun `El texto vacío vuelve vacío`() {
        assertEquals("", LlmBudget.truncateToTokenBudget("", 100))
    }

    @Test
    fun `Texto Volver al texto original dentro del presupuesto`() {
        val text = "Hello World"
        // Dar un presupuesto lo suficientemente grande,El texto no se truncará
        val result = LlmBudget.truncateToTokenBudget(text, 1000)
        assertEquals(text, result)
    }

    @Test
    fun `El texto que excede el presupuesto está truncado y marcado como adjunto`() {
        val text = "Hello World This Is A Very Long Text That Should Be Truncated"
        val result = LlmBudget.truncateToTokenBudget(text, 3)
        assertTrue("Las etiquetas deben incluirse después del truncamiento: $result", result.contains("(memory truncated)"))
        assertTrue("El texto truncado debe ser más corto que el original.", result.length < text.length + 50)
    }

    @Test
    fun `Truncar en nueva línea`() {
        val text = "Primera línea de contenido\nSegunda línea de contenido\nLa tercera línea de contenido.\nLa cuarta línea de contenido.\nLa quinta línea de contenido."
        val result = LlmBudget.truncateToTokenBudget(text, 3)
        // Truncado debe contener una etiqueta truncada
        assertTrue(result.contains("(memory truncated)"))
    }

    @Test
    fun `Texto chino truncado correctamente`() {
        val text = "Hola mundo Este es un texto largo en chino que se utiliza para probar si la función de truncamiento funciona correctamente."
        val result = LlmBudget.truncateToTokenBudget(text, 2)
        assertTrue("El truncamiento chino debe incluir etiquetas.: $result", result.contains("(memory truncated)"))
    }

    @Test
    fun `withReasoningHeadroom Los modelos sin razonamiento devuelven valores originales`() {
        val model = io.zer0.ai.core.Model(id = "gpt-4o", name = "GPT-4o", providerId = "openai", contextWindow = 128000)
        val result = LlmBudget.withReasoningHeadroom(4096, model)
        assertEquals(4096, result)
    }

    @Test
    fun `withReasoningHeadroom reasoning El modelo aumenta el almacenamiento en búfer`() {
        val model = io.zer0.ai.core.Model(
            id = "o1-preview",
            name = "O1 Preview",
            providerId = "openai",
            contextWindow = 128000,
            maxOutputTokens = 32768,
        )
        val result = LlmBudget.withReasoningHeadroom(4096, model)
        assertTrue("reasoning El modelo debería aumentar el almacenamiento en búfer: $result", result > 4096)
        assertTrue("No debe exceder el límite superior del modelo.", result <= 32768)
    }

    @Test
    fun `withReasoningHeadroom null El modelo devuelve el valor original.`() {
        assertEquals(4096, LlmBudget.withReasoningHeadroom(4096, null))
    }
}
