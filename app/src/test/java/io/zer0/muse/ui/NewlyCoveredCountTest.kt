package io.zer0.muse.ui

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Prueba de cálculo numérico "Este nuevo resumen de fusión" (B3 Ensayos complementarios de compresión y disposición).
 *
 * Puntos propensos a errores indicados en los comentarios de la función: la identificación del mensaje es un UUID aleatorio sin orden, cualquier inferencia se basa en el orden de identificación
 * Todos los cálculos serán incorrectos con datos reales.——Se debe utilizar una lista de mensajes ordenados.
 */
class NewlyCoveredCountTest {

    private fun msg(content: String): UIMessage = UIMessage(
        id = Uuid.random(),
        role = MessageRole.USER,
        content = content,
    )

    @Test
    fun `empty covered ids count zero`() {
        val messages = listOf(msg("a"), msg("b"))
        assertEquals(0, ChatViewModel.newlyCoveredCountStatic(messages, emptySet(), null))
    }

    @Test
    fun `no previous boundary counts all covered`() {
        val m1 = msg("a")
        val m2 = msg("b")
        val m3 = msg("c")
        val messages = listOf(m1, m2, m3)
        assertEquals(3, ChatViewModel.newlyCoveredCountStatic(messages, setOf(m1.id.toString(), m2.id.toString(), m3.id.toString()), null))
    }

    @Test
    fun `previous boundary splits count to messages after it`() {
        val m1 = msg("a")
        val m2 = msg("b")
        val m3 = msg("c")
        val messages = listOf(m1, m2, m3)
        // El límite es m2 → solo cuenta m3
        assertEquals(1, ChatViewModel.newlyCoveredCountStatic(messages, setOf(m2.id.toString(), m3.id.toString()), m2.id.toString()))
    }

    @Test
    fun `boundary not found falls back to counting from start`() {
        val m1 = msg("a")
        val m2 = msg("b")
        val messages = listOf(m1, m2)
        assertEquals(2, ChatViewModel.newlyCoveredCountStatic(messages, setOf(m1.id.toString(), m2.id.toString()), "missing-boundary"))
    }

    @Test
    fun `count only messages inside covered set`() {
        val m1 = msg("a")
        val m2 = msg("b")
        val m3 = msg("c")
        val messages = listOf(m1, m2, m3)
        // El conjunto de cobertura contiene sólo m1/m3 (m2 no comprimido)
        assertEquals(2, ChatViewModel.newlyCoveredCountStatic(messages, setOf(m1.id.toString(), m3.id.toString()), null))
    }
}
