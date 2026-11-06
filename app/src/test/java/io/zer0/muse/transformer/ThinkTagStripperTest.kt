package io.zer0.muse.transformer

import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.0.74: stripThinkTags prueba de pelado — Contiene cierre/no cerrado/Escenas de límites anidadas. */
class ThinkTagStripperTest {

    @Test
    fun `strip closed think block`() {
        assertEquals("Título", stripThinkTags("<think>Piensa en el contenido</think>Título"))
    }

    @Test
    fun `strip closed block with newlines`() {
        assertEquals("Título", stripThinkTags("<think>\nprimera línea de pensamiento\nsegunda línea de pensamiento\n</think>Título"))
    }

    @Test
    fun `strip unclosed think at start`() {
        // Escenarios de comentarios de los usuarios: El modelo sólo produce <think> El comienzo no está cerrado. → El título está contaminado.
        assertEquals("", stripThinkTags("<think>El tema de conversación es sobre la elección del equipo fotográfico."))
    }

    @Test
    fun `strip unclosed think after closed block`() {
        assertEquals("Texto", stripThinkTags("<think>A</think>Texto<think>B"))
    }

    @Test
    fun `keep text without think tags`() {
        assertEquals("título normal", stripThinkTags("título normal"))
    }

    @Test
    fun `strip all closed blocks`() {
        assertEquals("unb", stripThinkTags("<think>1</think>un<think>2</think>b"))
    }

    @Test
    fun `strip quotes after tags`() {
        assertEquals("\"Título\"", stripThinkTags("<think>x</think>\"Título\""))
    }

    @Test
    fun `handle empty input`() {
        assertEquals("", stripThinkTags(""))
        assertEquals("", stripThinkTags("   "))
    }

    @Test
    fun `strip thinking variant`() {
        assertEquals("Título", stripThinkTags("<thinking>razonamiento</thinking>Título"))
    }

    @Test
    fun `strip case insensitive`() {
        assertEquals("Título", stripThinkTags("<THINK>razonamiento</THINK>Título"))
    }
}
