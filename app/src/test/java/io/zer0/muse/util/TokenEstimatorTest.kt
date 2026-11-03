package io.zer0.muse.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.56: TokenEstimator Pruebas unitarias(BPE tokenizer).
 *
 * Verifique que la integración de jtokkit sea correcta,Tanto el conteo preciso como el respaldo heurístico funcionan.
 */
class TokenEstimatorTest {

    @Test
    fun `Texto vacío devuelto 0`() {
        assertEquals(0, TokenEstimator.estimate(""))
        assertEquals(0, TokenEstimator.estimate("   "))
    }

    @Test
    fun `El número de token de texto en inglés es mayor que 0`() {
        val tokens = TokenEstimator.estimate("Hello World")
        assertTrue("El número de token en inglés debe > 0,real $tokens", tokens > 0)
    }

    @Test
    fun `El número de token de texto chino es mayor que 0`() {
        val tokens = TokenEstimator.estimate("hola mundo")
        assertTrue("El número de token chino debe > 0,real $tokens", tokens > 0)
    }

    @Test
    fun `El número de tokens en texto largo es mayor que en texto corto`() {
        val short = TokenEstimator.estimate("Hello")
        val long = TokenEstimator.estimate("Hello World This Is A Longer Text")
        assertTrue("Los tokens de texto largos deberían ser más: short=$short, long=$long", long > short)
    }

    @Test
    fun `Aproximadamente 4 caracteres en inglés. per token`() {
        // "hello" = 1 token (cl100k_base),Verifique aproximadamente el orden de magnitud.
        val tokens = TokenEstimator.estimate("hello hello hello hello")
        assertTrue("5 hola cita 5+ token,real $tokens", tokens >= 4)
    }

    @Test
    fun `Aproximadamente 1 o 2 caracteres chinos per token`() {
        // Los caracteres chinos suelen tener entre 1 y 2 caracteres en BPE/token
        val tokens = TokenEstimator.estimate("hola hola hola hola") // 8 palabra
        assertTrue("8 un contrato de palabra chino 4-8 token,real $tokens", tokens in 3..12)
    }

    @Test
    fun `El texto mixto cuenta correctamente`() {
        val tokens = TokenEstimator.estimate("Hello hola mundo mundo")
        assertTrue("Los tokens de texto mixto deben > 2,real $tokens", tokens > 2)
    }

    @Test
    fun `estimate messages lista`() {
        val messages = listOf(
            io.zer0.ai.core.UIMessage(
                role = io.zer0.ai.core.MessageRole.USER,
                content = "Hello World",
            ),
            io.zer0.ai.core.UIMessage(
                role = io.zer0.ai.core.MessageRole.ASSISTANT,
                content = "Hi there! How can I help you today?",
            ),
        )
        val total = TokenEstimator.estimate(messages, systemPrompt = "You are a helpful assistant.")
        assertTrue("El número total de tokens en la lista de mensajes debe ser > 5,real $total", total > 5)
    }

    @Test
    fun `system prompt ser contado`() {
        val withoutSys = TokenEstimator.estimate(
            listOf(io.zer0.ai.core.UIMessage(role = io.zer0.ai.core.MessageRole.USER, content = "test")),
            systemPrompt = "",
        )
        val withSys = TokenEstimator.estimate(
            listOf(io.zer0.ai.core.UIMessage(role = io.zer0.ai.core.MessageRole.USER, content = "test")),
            systemPrompt = "You are a very helpful assistant that helps users with their questions.",
        )
        assertTrue("Debería haber más tokens cuando hay un aviso del sistema.", withSys > withoutSys)
    }

    // ── Casos de uso complementarios:Lista de mensajes vacía / gastos generales por mensaje / mezcla de chino e inglés ─────────────────

    @Test
    fun `Lista de mensajes vacía y no se devuelve ningún mensaje del sistema 0`() {
        val total = TokenEstimator.estimate(emptyList(), systemPrompt = "")
        assertEquals("La lista de mensajes vacía + el mensaje del sistema vacío debe ser 0,real $total", 0, total)
    }

    @Test
    fun `Cuando la lista de mensajes vacía solo contiene mensajes del sistema, es igual a la cantidad de tokens del mensaje del sistema.`() {
        val sysPrompt = "You are a helpful assistant."
        val total = TokenEstimator.estimate(emptyList(), systemPrompt = sysPrompt)
        val sysOnly = TokenEstimator.estimate(sysPrompt)
        assertEquals("La lista de mensajes vacía + systemPrompt debe ser igual al número de token del propio systemPrompt", sysOnly, total)
    }

    @Test
    fun `La sobrecarga por mensaje se incluye correctamente en el total token`() {
        // Mensaje único frente a dos mensajes idénticos:El total de tokens debe duplicarse(Eliminar la interferencia de avisos del sistema)
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.USER,
            content = "Hello World",
        )
        val single = TokenEstimator.estimate(listOf(msg), systemPrompt = "")
        val doubled = TokenEstimator.estimate(listOf(msg, msg), systemPrompt = "")
        // El total de tokens de dos mensajes idénticos debe ser el doble que el de un solo mensaje.(Sin interferencias de aviso del sistema)
        assertEquals("El token de dos mensajes idénticos debe ser el doble que el de un solo mensaje.: single=$single, doubled=$doubled", single * 2, doubled)
    }

    @Test
    fun `El token total de la lista de mensajes es igual al sistema más el contenido de cada mensaje más la sobrecarga fija de cada mensaje.`() {
        // verificar estimate(messages, systemPrompt) = estimate(systemPrompt) + Σ estimate(msg.content) + Gastos generales fijos por mensaje
        // M6: Aproximadamente 4 tokens por mensaje(OpenAI Gastos generales fijos por mensaje del protocolo.:role marca + delimitador)
        val perMessageOverhead = 4
        val sysPrompt = "system"
        val m1 = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.USER,
            content = "first message",
        )
        val m2 = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.ASSISTANT,
            content = "second message",
        )
        val messages = listOf(m1, m2)
        val expected = TokenEstimator.estimate(sysPrompt) +
            TokenEstimator.estimate(m1.content) +
            TokenEstimator.estimate(m2.content) +
            messages.size * perMessageOverhead
        val actual = TokenEstimator.estimate(messages, systemPrompt = sysPrompt)
        assertEquals("El token total de la lista de mensajes debe ser igual a la suma de sus partes.(Incluyendo gastos generales fijos por mensaje)", expected, actual)
    }

    @Test
    fun `La sobrecarga fija por mensaje aumenta linealmente con el número de mensajes.`() {
        // M6: Aproximadamente 4 tokens por mensaje.N Hay más de 1 mensaje idéntico. (N-1)*4 + (N-1)*contentTokens
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.USER,
            content = "Hello World",
        )
        val one = TokenEstimator.estimate(listOf(msg), systemPrompt = "")
        val three = TokenEstimator.estimate(listOf(msg, msg, msg), systemPrompt = "")
        // Tres mensajes son 2 mensajes más que uno.:2 * (4 + contentTokens)
        val delta = three - one
        assertTrue("Tres mensajes son más de uno. $delta,debe ser mayor que 2*4=8", delta >= 2 * 4)
        // Verifique también la linealidad.:three debe ser igual a one + 2 * (Contribución única)
        // Contribución única = 4(perMessageOverhead) + estimate(msg.content)
        val singleContribution = 4 + TokenEstimator.estimate(msg.content)
        assertEquals("Tres mensajes deben ser iguales a uno. + 2*Contribución única", one + 2 * singleContribution, three)
    }

    @Test
    fun `La estimación para texto mixto en chino e inglés es mayor que 0 y entre chino puro e inglés puro.`() {
        val pureEn = TokenEstimator.estimate("Hello World")
        val pureCn = TokenEstimator.estimate("hola mundo")
        val mixed = TokenEstimator.estimate("Hello hola mundo mundo")
        assertTrue("Los tokens de texto mixto deben > 0,real $mixed", mixed > 0)
        // El texto mixto no debe tener menos caracteres que cualquiera de las versiones monolingües.,token El número tampoco debe ser inferior al mínimo.
        val minPure = minOf(pureEn, pureCn)
        assertTrue("texto mixto token($mixed)No debe ser menos que medio puro./mínimo de inglés puro($minPure)", mixed >= minPure)
    }
}
