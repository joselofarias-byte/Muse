package io.zer0.muse.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.53: DataCardParser Pruebas unitarias(Guía de casos de uso de aceptación de la fase 5).
 */
class DataCardParserTest {

    @Test
    fun `Tarjeta de registro legal analizada con éxito`() {
        val md = """
            Los siguientes son los datos:
            ```card
            {"type":"bar","title":"Volumen de noticias esta semana","labels":["uno","dos","tres"],"values":[12,30,18]}
            ```
        """.trimIndent()
        val card = DataCardParser.parse(md)
        assertTrue(card != null)
        assertEquals("bar", card?.type)
        assertEquals("Volumen de noticias esta semana", card?.title)
        assertEquals(3, card?.labels?.size)
        assertEquals(12f, card?.values?.get(0))
    }

    @Test
    fun `line También compatible con tarjetas de donas.`() {
        assertTrue(DataCardParser.parseJson("""{"type":"line","labels":["a","b"],"values":[1,2]}""") != null)
        assertTrue(DataCardParser.parseJson("""{"type":"donut","labels":["a","b"],"values":[1,2]}""") != null)
    }

    @Test
    fun `Devolución JSON ilegal null`() {
        assertNull(DataCardParser.parseJson("""{invalid json"""))
    }

    @Test
    fun `Se devolvió el tipo de gráfico no admitido null`() {
        assertNull(DataCardParser.parseJson("""{"type":"pie","labels":["a"],"values":[1]}"""))
    }

    @Test
    fun `labels Devuelve inconsistente con el número de valores. null`() {
        assertNull(DataCardParser.parseJson("""{"type":"bar","labels":["a","b"],"values":[1]}"""))
    }

    @Test
    fun `Devuelve una lista de valores vacíos. null`() {
        assertNull(DataCardParser.parseJson("""{"type":"bar","labels":[],"values":[]}"""))
    }

    @Test
    fun `No se devolvió ningún bloque de tarjeta null`() {
        assertNull(DataCardParser.parse("# documento ordinario\nsin tarjeta"))
    }

    @Test
    fun `containsCardBlock Detección`() {
        assertTrue(DataCardParser.containsCardBlock("```card\n{}```"))
        assertFalse(DataCardParser.containsCardBlock("texto normal"))
    }
}
