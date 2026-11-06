package io.zer0.ai.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Objetivo [ToolCallArgsAccumulator] prueba única.
 *
 * Reproduzca que cuando modelos como DeepSeek V4 transmiten y devuelven la misma llamada de herramienta, los argumentos se dividen en varios objetos JSON completos.
 * ({"limit":20}{"query":"..."}), verifique que el acumulador fusione campos en lugar de concatenar cadenas.
 */
class ToolCallArgsAccumulatorTest {

    @Test
    fun `string fragments accumulate into valid json`() {
        val acc = ToolCallArgsAccumulator()
        acc.append("{\"lim")
        acc.append("it\":20}")
        assertEquals("""{"limit":20}""", acc.current())
        assertTrue(acc.isValidJson())
    }

    @Test
    fun `concatenated json objects merge fields instead of string concat`() {
        val acc = ToolCallArgsAccumulator()
        acc.append("""{"limit":20}""")
        acc.append("""{"query":"clima"}""")
        val merged = acc.current()
        // Debe ser un único objeto JSON legal con ambos campos en
        assertTrue("got: $merged", acc.isValidJson())
        assertTrue(merged.contains("\"limit\""))
        assertTrue(merged.contains("\"query\""))
        assertFalse(merged.contains("}{"))
    }

    @Test
    fun `three concatenated objects all merge`() {
        val acc = ToolCallArgsAccumulator()
        acc.append("""{"a":1}""")
        acc.append("""{"b":2}""")
        acc.append("""{"c":3}""")
        assertTrue(acc.isValidJson())
        val raw = acc.current()
        assertTrue(raw.contains("\"a\""))
        assertTrue(raw.contains("\"b\""))
        assertTrue(raw.contains("\"c\""))
    }

    @Test
    fun `partial fragment then completed object merges`() {
        // El primer fragmento es un fragmento no cerrado y el segundo fragmento está completo (incremento de cadena normal, cortado fuera de las comillas)
        val acc = ToolCallArgsAccumulator()
        acc.append("""{"query":"""")
        acc.append("""El tiempo en Pekín"}""")
        assertEquals("""{"query":"El tiempo en Pekín"}""", acc.current())
        assertTrue(acc.isValidJson())
    }

    @Test
    fun `truncated final fragment reports invalid but keeps prefix`() {
        // Se corta la última pieza ({"query":"xxx Falta soporte derecho),isValidJson=false
        val acc = ToolCallArgsAccumulator()
        acc.append("""{"query":"El tiempo en Pekín""")
        assertFalse(acc.isValidJson())
        // No se lanza ninguna excepción y el prefijo se reserva para el juicio del nivel superior.
        assertTrue(acc.current().startsWith("{"))
    }

    @Test
    fun `later object field overrides earlier same key`() {
        val acc = ToolCallArgsAccumulator()
        acc.append("""{"query":"viejo","limit":5}""")
        acc.append("""{"query":"nuevo"}""")
        val raw = acc.current()
        assertTrue(acc.isValidJson())
        // query Prevalecerá lo que llegue más tarde.
        assertTrue(raw.contains("\"query\":\"nuevo\""))
        assertTrue(raw.contains("\"limit\""))
    }

    @Test
    fun `nested object value survives merge`() {
        val acc = ToolCallArgsAccumulator()
        acc.append("""{"opts":{"a":1}}""")
        acc.append("""{"query":"x"}""")
        assertTrue(acc.isValidJson())
        assertTrue(acc.current().contains("\"opts\""))
        assertTrue(acc.current().contains("\"query\""))
    }

    @Test
    fun `empty and blank appends are no-ops`() {
        val acc = ToolCallArgsAccumulator()
        acc.append("")
        acc.append("   ")
        assertEquals("", acc.current())
        // Un acumulador vacío no es JSON válido, pero no se arroja
        assertFalse(acc.isValidJson())
    }

    @Test
    fun `single complete object passes through`() {
        val acc = ToolCallArgsAccumulator()
        acc.append("""{"query":"clima","max_results":5}""")
        assertTrue(acc.isValidJson())
        assertEquals("""{"query":"clima","max_results":5}""", acc.current())
    }

    @Test
    fun `hasMergedObjects true after concatenated object fragments`() {
        val acc = ToolCallArgsAccumulator()
        // La fase de incremento de cadena normal no debe marcar la fusión
        acc.append("""{"query":""")
        assertFalse(acc.hasMergedObjects())
        acc.append("""x"}""")
        assertFalse(acc.hasMergedObjects())
        // Dos objetos completos se marcan sólo cuando están fragmentados
        val acc2 = ToolCallArgsAccumulator()
        acc2.append("""{"limit":5}""")
        acc2.append("""{"query":"x"}""")
        assertTrue(acc2.hasMergedObjects())
    }
}
