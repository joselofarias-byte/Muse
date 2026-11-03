package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ConversationCompressor] Prueba única de lógica de bloqueo y presupuesto.
 *
 * Cobertura 2026-09-29 Revisar los resultados:"Límite superior de solicitud de bloque único ≈38.4 esvástica(256×1500)Ventana de modelo necesariamente supercomprimida".
 */
class ConversationCompressorChunkTest {
    private fun msg(chars: Int): UIMessage = UIMessage(role = MessageRole.USER, content = "x".repeat(chars))

    @Test
    fun `El presupuesto de bloque único se calcula en función de la ventana del modelo y está sujeto a límites superior e inferior`() {
        // Ventana desconocida → default conservador(32k × 0.4)
        assertEquals(12_800, ConversationCompressor.chunkBudgetChars(null))
        assertEquals(12_800, ConversationCompressor.chunkBudgetChars(0))
        // ventana grande → No exceda el límite superior(Evite bloques de gran tamaño)
        assertEquals(48_000, ConversationCompressor.chunkBudgetChars(512_000))
        // pequeña ventana → No menos que el límite inferior(De lo contrario quedará demasiado picado.)
        assertEquals(6_000, ConversationCompressor.chunkBudgetChars(8_000))
    }

    @Test
    fun `Las sesiones largas se organizan según el presupuesto de los personajes en lugar de dividir un trozo de superventana`() {
        // antigua implementación:256 Artículo(límite superior)× 1500 personaje(Límite superior único)= 384_000 Personajes agrupados en un mensaje
        val messages = List(256) { msg(1_500) }
        val budget = ConversationCompressor.chunkBudgetChars(null)
        val chunks = ConversationCompressor.chunkMessages(messages, budget)

        assertTrue("Debe cortarse en varios trozos.,real ${chunks.size} bloquear", chunks.size >= 30)
        chunks.forEach { chunk ->
            val chars = chunk.sumOf { minOf(it.content.length, 1_500) }
            assertTrue("Monobloque $chars Los personajes superan el presupuesto. $budget(dentro del bloque ${chunk.size} Artículo)", chars <= budget)
        }
        assertEquals("No puedes perder el mensaje cortándolo en pedazos.", 256, chunks.sumOf { it.size })
    }

    @Test
    fun `El límite superior en el número de artículos sigue vigente`() {
        val messages = List(300) { msg(10) }
        val chunks = ConversationCompressor.chunkMessages(messages, 1_000_000)
        assertEquals(2, chunks.size)
        assertEquals(256, chunks[0].size)
        assertEquals(44, chunks[1].size)
    }

    @Test
    fun `Los mensajes extralargos se facturan según la longitud truncada sin perder el mensaje`() {
        // Un único mensaje enviado al mensaje se truncará a MAX_MSG_CHARS(1500),Los bloques se facturan según la longitud truncada.:
        // 10 Un mensaje de 20.000 caracteres ≈ Cada elemento solo cuenta 1500 → 3 artículos por bloque cuando el presupuesto es 5000
        val messages = List(10) { msg(20_000) }
        val budget = 5_000
        val chunks = ConversationCompressor.chunkMessages(messages, budget)

        assertEquals(4, chunks.size)
        assertEquals(listOf(3, 3, 3, 1), chunks.map { it.size })
        assertEquals("No puedes perder el mensaje cortándolo en pedazos.", 10, chunks.sumOf { it.size })
        chunks.forEach { chunk ->
            val chars = chunk.sumOf { minOf(it.content.length, 1_500) }
            assertTrue("Monobloque $chars Los personajes superan el presupuesto. $budget", chars <= budget)
        }
    }

    @Test
    fun `La lista vacía devuelve un bloque vacío`() {
        assertTrue(ConversationCompressor.chunkMessages(emptyList(), 1_000).isEmpty())
    }
}
