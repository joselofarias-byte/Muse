package io.zer0.muse.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conformación de recuperación de canales: dividir segmentos y suprimir líneas vacías.
 *
 * Concéntrese en cubrir tres cosas:QQ Se permite un máximo de 5 respuestas pasivas para un mensaje entrante y no se pueden dejar grandes líneas en blanco entre párrafos.
 * Fusionar la cola en lugar del texto cortado cuando se excede el límite superior.
 */
class ChannelReplyFormatterTest {

    @Test
    fun `Mantenga un solo párrafo de texto sin formato como está`() {
        val plan = ChannelReplyFormatter.plan("Hola, ¿cómo estás hoy?")
        assertEquals(listOf("Hola, ¿cómo estás hoy?"), plan)
    }

    @Test
    fun `Varios párrafos separados por líneas en blanco se dividen en varios párrafos`() {
        val plan = ChannelReplyFormatter.plan("primer párrafo\n\nSegundo párrafo\n\nPárrafo 3")
        assertEquals(listOf("primer párrafo", "Segundo párrafo", "Párrafo 3"), plan)
    }

    @Test
    fun `Las líneas en blanco continuas no dejarán párrafos vacíos`() {
        val plan = ChannelReplyFormatter.plan("comienzo\n\n\n\nfinalizando")
        assertEquals(listOf("comienzo", "finalizando"), plan)
    }

    @Test
    fun `Fusionar la cola cuando haya más de cinco párrafos`() {
        val text = (1..8).joinToString("\n\n") { "No.${it}segmento" }
        val plan = ChannelReplyFormatter.plan(text)
        // QQ Se permiten un máximo de 5 respuestas pasivas para un mensaje entrante, y el quinto párrafo debe contener 5~8 Párrafo y entrar
        assertEquals(ChannelReplyFormatter.MAX_SEGMENTS, plan.size)
        assertTrue("El último párrafo debe contener el contenido fusionado.", plan.last().contains("No.8segmento"))
        assertTrue("El último párrafo debe contener la5segmento", plan.last().contains("No.5segmento"))
    }

    @Test
    fun `Las respuestas completamente en blanco aparecen en una lista vacía`() {
        assertEquals(emptyList<String>(), ChannelReplyFormatter.plan("   \n\n \t \n"))
    }

    @Test
    fun `Las líneas vacías sobrantes dentro de un solo párrafo se aplanan`() {
        val flat = ChannelReplyFormatter.flatten("primera linea\n\n\nsegunda linea")
        assertEquals("primera linea\nsegunda linea", flat)
    }

    @Test
    fun `Se conservan nuevas líneas individuales dentro de los párrafos`() {
        // Los saltos de línea dentro de los párrafos son parte de la composición tipográfica (como las listas) y no se pueden suprimir por completo.
        val plan = ChannelReplyFormatter.plan("No.1OK\nNo.2OK")
        assertEquals(listOf("No.1OK\nNo.2OK"), plan)
    }

    @Test
    fun `markdown Las etiquetas no se sobrescriben`() {
        val text = "**centrarse**\n\n```kotlin\nval a = 1\n```"
        val plan = ChannelReplyFormatter.plan(text)
        assertEquals(2, plan.size)
        assertTrue(plan[0].contains("**centrarse**"))
        assertTrue(plan[1].contains("```kotlin"))
    }

    @Test
    fun `CRLF ser normalizado`() {
        val plan = ChannelReplyFormatter.plan("un\r\n\r\nb")
        assertEquals(listOf("un", "b"), plan)
    }

    @Test
    fun `max Devuelve vacío si el parámetro es ilegal`() {
        assertEquals(emptyList<String>(), ChannelReplyFormatter.segments("un\n\nb", max = 0))
    }
}
