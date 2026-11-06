package io.zer0.muse.budget

import io.zer0.memory.budget.LlmBudget
import io.zer0.muse.util.TokenEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LlmBudget token prueba de límites truncados(Se ha probado el módulo de memoria suplementaria.).
 *
 * Centrarse en cubrir los escenarios de límites requeridos por las tareas.:
 *  - Lista de mensajes vacía/texto vacío
 *  - Un solo mensaje no supera el límite → Volver como está
 *  - El número total de tokens en varios mensajes supera el límite. → Truncar y mantener el contenido del encabezado
 *  - frontera:token El número es exactamente igual a maxTokens
 *  - frontera:Un solo mensaje ha superado maxTokens
 *
 * Nota:[LlmBudget.truncateToTokenBudget] recibir soltero String(memoria markdown),
 * no List<UIMessage>. aquí"múltiples mensajes"Se refiere a múltiples fragmentos de texto después de empalmar.,Verifique el comportamiento de truncamiento.
 *
 * semántica de truncamiento:Implementar texto retenido**cabeza**(N superior token),Agregar al final "…(memory truncated)" marca.
 */
class LlmBudgetTest {

    // ── Texto vacío/lista de mensajes vacía ─────────────────────────────────────────────

    @Test
    fun `El texto vacío vuelve vacío`() {
        assertEquals("", LlmBudget.truncateToTokenBudget("", 100))
    }

    @Test
    fun `El presupuesto de texto vacío es 0 y también devuelve vacío`() {
        assertEquals("", LlmBudget.truncateToTokenBudget("", 0))
    }

    @Test
    fun `El texto en blanco no se trunca`() {
        // El número de tokens de texto en blanco es 0,No excederá el límite
        val result = LlmBudget.truncateToTokenBudget("   ", 10)
        assertEquals("   ", result)
    }

    // ── Un solo mensaje no supera el límite ──────────────────────────────────────────────────

    @Test
    fun `Un solo mensaje no supera el límite Volver al texto original`() {
        val text = "Hello World"
        val result = LlmBudget.truncateToTokenBudget(text, 1000)
        assertEquals(text, result)
    }

    @Test
    fun `budget Si es mucho mayor que la cantidad de tokens de texto, se devolverá el texto original`() {
        val text = "Este es un texto corto."
        val result = LlmBudget.truncateToTokenBudget(text, 10000)
        assertEquals(text, result)
    }

    // ── El número total de tokens en varios mensajes supera el límite.,Validar truncamiento desde head ────────────────────────────

    @Test
    fun `Después de unir varios mensajes, se truncan y se marcan cuando superan el límite`() {
        // Simular la unión de múltiples mensajes de memoria.
        val text = buildString {
            appendLine("memoria1:A los usuarios les encanta programar")
            appendLine("memoria2:Uso del usuario Kotlin")
            appendLine("memoria3:Usuarios que desarrollan aplicaciones de Android")
            appendLine("memoria4:Nombre del proyecto del usuario Muse")
            appendLine("memoria5:A los usuarios les gustan los temas oscuros")
            appendLine("memoria6:Uso del usuario TRAE IDE")
            appendLine("memoria7:A los usuarios les gusta tomar café.")
            appendLine("memoria8:El horario laboral del usuario es 9-18")
        }
        // Establecer un muy pequeño budget(2 token),truncamiento forzado
        val result = LlmBudget.truncateToTokenBudget(text, 2)
        assertTrue("Los resultados truncados deben contener etiquetas: $result", result.contains("(memory truncated)"))
        assertTrue("El resultado truncado debe ser más corto que el texto original.", result.length < text.length)
    }

    @Test
    fun `Mantener el contenido del encabezado después del truncamiento`() {
        // Verifique que el resultado después del truncamiento comience desde el principio del texto original(la cabeza esta reservada)
        val text = "El primer párrafo es relativamente largo y se utiliza para probar el comportamiento de truncamiento.. El segundo párrafo también es muy largo y sirve para comprobar que se ha quitado la cola.."
        val result = LlmBudget.truncateToTokenBudget(text, 3)
        // Después del truncamiento, debería comenzar con los primeros caracteres del texto original.
        assertTrue("El encabezado debe conservarse después del truncamiento: result='$result'", result.startsWith(text.take(3)))
        // debe contener marcadores de truncamiento
        assertTrue(result.contains("(memory truncated)"))
    }

    @Test
    fun `El contenido del mensaje de encabezado existe después de que se truncan varios mensajes`() {
        val msg1 = "El primer recuerdo es muy importante de conservar."
        val msg2 = "El segundo recuerdo también es importante."
        val msg3 = "El tercer recuerdo se puede descartar."
        val text = "$msg1\n$msg2\n$msg3"
        // use más grande pero no lo suficientemente grande para acomodar a todos budget
        val totalTokens = TokenEstimator.estimate(text)
        val budget = (totalTokens / 2).coerceAtLeast(1)
        val result = LlmBudget.truncateToTokenBudget(text, budget)
        // Contenido del encabezado(msg1 las primeras palabras de)debería existir
        assertTrue("El encabezado del primer mensaje debe conservarse después del truncamiento.: $result", result.contains(msg1.take(3)))
    }

    // ── frontera:exactamente igual a maxTokens ────────────────────────────────────────

    @Test
    fun `token Volver al texto original cuando el número sea exactamente igual al presupuesto`() {
        val text = "Hello World"
        // Utilice TokenEstimator para medir la cantidad exacta de tokens(BPE Mismo origen que LlmBudget cl100k_base)
        val exactTokens = TokenEstimator.estimate(text)
        assertTrue("El número de token del texto de prueba debe > 0", exactTokens > 0)
        // budget Exactamente igual al número de tokens
        val result = LlmBudget.truncateToTokenBudget(text, exactTokens)
        assertEquals("token numero == budget debe volver al texto original", text, result)
        assertFalse("no debe contener marcadores de truncamiento", result.contains("(memory truncated)"))
    }

    @Test
    fun `token Truncado cuando el número es exactamente igual al presupuesto más 1`() {
        val text = "Hello World This Is A Test"
        val exactTokens = TokenEstimator.estimate(text)
        // budget Menos que la cantidad de tokens 1,debería desencadenar el truncamiento
        val result = LlmBudget.truncateToTokenBudget(text, exactTokens - 1)
        assertTrue("budget = tokens - 1 debe ser truncado: $result", result.contains("(memory truncated)"))
    }

    // ── frontera:Un solo mensaje ha superado maxTokens ────────────────────────────────────

    @Test
    fun `Un solo mensaje se trunca cuando excede maxTokens`() {
        val text = "This is a very long single message that definitely exceeds the token budget " +
            "and should be truncated with the memory truncated marker appended at the end"
        val result = LlmBudget.truncateToTokenBudget(text, 3)
        assertTrue("Los mensajes únicos que exceden el límite deben truncarse: $result", result.contains("(memory truncated)"))
        assertTrue("El texto truncado debe ser más corto que el original.", result.length < text.length)
    }

    @Test
    fun `Un único mensaje muy largo se trunca y se conserva el encabezado token`() {
        val text = "abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz"
        val result = LlmBudget.truncateToTokenBudget(text, 2)
        // Se deben conservar los primeros caracteres del encabezado.
        assertTrue("Se debe conservar la cabeza.: $result", result.isNotEmpty())
        assertTrue("debe contener marcadores de truncamiento", result.contains("(memory truncated)"))
    }

    // ── budget es 0 o negativo(sin limite) ────────────────────────────────────

    @Test
    fun `budget Volver al texto original para 0(sin limite)`() {
        val text = "Hello World"
        assertEquals(text, LlmBudget.truncateToTokenBudget(text, 0))
    }

    @Test
    fun `budget Devuelve el texto original para números negativos(sin limite)`() {
        val text = "Hello World"
        assertEquals(text, LlmBudget.truncateToTokenBudget(text, -1))
    }

    // ── Truncamiento de texto chino ───────────────────────────────────────────────────

    @Test
    fun `El texto chino muy largo está truncado correctamente`() {
        val text = "Hola mundo Este es un texto largo en chino que se utiliza para probar si la función de truncamiento funciona correctamente." +
            "Agregar más contenido chino garantiza que la lógica de truncamiento se active cuando se exceda el límite de presupuesto"
        val result = LlmBudget.truncateToTokenBudget(text, 3)
        assertTrue("El truncamiento chino debe incluir etiquetas.: $result", result.contains("(memory truncated)"))
    }

    // ── withReasoningHeadroom frontera ─────────────────────────────────

    @Test
    fun `withReasoningHeadroom null El modelo devuelve el valor original`() {
        assertEquals(4096, LlmBudget.withReasoningHeadroom(4096, null))
    }

    @Test
    fun `withReasoningHeadroom Los modelos sin razonamiento devuelven valores originales`() {
        val model = io.zer0.ai.core.Model(
            id = "gpt-4o",
            name = "GPT-4o",
            providerId = "openai",
            contextWindow = 128000,
        )
        assertEquals(4096, LlmBudget.withReasoningHeadroom(4096, model))
    }

    @Test
    fun `withReasoningHeadroom reasoning El modelo aumenta el buffer sin exceder el límite superior`() {
        val model = io.zer0.ai.core.Model(
            id = "o1-preview",
            name = "O1 Preview",
            providerId = "openai",
            contextWindow = 128000,
            maxOutputTokens = 32768,
        )
        val result = LlmBudget.withReasoningHeadroom(4096, model)
        assertTrue("reasoning El modelo debería aumentar el almacenamiento en búfer: $result", result > 4096)
        assertTrue("No debe exceder el límite superior del modelo.: $result", result <= 32768)
        // Verificar el valor del búfer = 4096 + DEFAULT_REASONING_HEADROOM_TOKENS(1024)
        assertEquals(4096 + LlmBudget.DEFAULT_REASONING_HEADROOM_TOKENS, result)
    }

    @Test
    fun `withReasoningHeadroom Cuando el búfer excede el límite superior del modelo clamp`() {
        // visibleMaxTokens + buffer > maxOutputTokens debe sujetarse a maxOutputTokens
        val model = io.zer0.ai.core.Model(
            id = "o3-mini",
            name = "O3 Mini",
            providerId = "openai",
            contextWindow = 128000,
            maxOutputTokens = 5000, // menos que visibleMaxTokens + 1024
        )
        val result = LlmBudget.withReasoningHeadroom(4096, model)
        assertEquals("debe sujetarse a maxOutputTokens", 5000, result)
    }

    @Test
    fun `withReasoningHeadroom reasoning No cuando el modelo no tiene maxOutputTokens clamp`() {
        val model = io.zer0.ai.core.Model(
            id = "deepseek-r1",
            name = "DeepSeek R1",
            providerId = "deepseek",
            contextWindow = 64000,
            maxOutputTokens = null,
        )
        val result = LlmBudget.withReasoningHeadroom(4096, model)
        assertEquals(4096 + LlmBudget.DEFAULT_REASONING_HEADROOM_TOKENS, result)
    }

    @Test
    fun `withReasoningHeadroom Identificar varios modelos de razonamiento id`() {
        val reasoningIds = listOf("o1-preview", "o3-mini", "o4-mini", "model-thinking", "reasoning-v1", "deepseek-r1")
        for (id in reasoningIds) {
            val model = io.zer0.ai.core.Model(
                id = id,
                name = id,
                providerId = "test",
                contextWindow = 128000,
                maxOutputTokens = 32768,
            )
            val result = LlmBudget.withReasoningHeadroom(4096, model)
            assertTrue("$id Debe ser reconocido como un modelo de razonamiento.(buffer > 4096): $result", result > 4096)
        }
    }
}
