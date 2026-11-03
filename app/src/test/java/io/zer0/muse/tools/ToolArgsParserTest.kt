package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgsParserTest {

    @Test
    fun `normal json parses all fields`() {
        val args = ToolArgsParser.parse("""{"query":"clima","max_results":5}""", "web_search")
        assertEquals("clima", args["query"])
        assertEquals("5", args["max_results"])
    }

    @Test
    fun `nested object value is serialized to string`() {
        val args = ToolArgsParser.parse("""{"opts":{"a":1},"query":"x"}""")
        assertEquals("x", args["query"])
        // Los objetos anidados se serializan en cadenas JSON (solo es necesario que no estén vacíos y la capa de ejecución los consume como cadenas)
        assertTrue(args["opts"]?.contains("a") == true)
    }

    @Test
    fun `array value is serialized to string`() {
        val args = ToolArgsParser.parse("""{"tags":["a","b"]}""")
        assertEquals("""["a","b"]""", args["tags"])
    }

    @Test
    fun `blank input returns empty map`() {
        assertTrue(ToolArgsParser.parse(null).isEmpty())
        assertTrue(ToolArgsParser.parse("").isEmpty())
        assertTrue(ToolArgsParser.parse("   ").isEmpty())
    }

    @Test
    fun `non-object input returns empty map`() {
        // cuerda/matriz/Los valores desnudos no son objetos y se devuelve un mapa vacío (no se genera ninguna excepción)
        assertTrue(ToolArgsParser.parse(""""hello"""").isEmpty())
        assertTrue(ToolArgsParser.parse("[1,2,3]").isEmpty())
    }

    @Test
    fun `truncated unclosed json returns empty map instead of dropping fields`() {
        // Fragmento no cerrado:{"query":"xxx Falta corchete de cierre
        // La antigua segmentación y fusión devolverá silenciosamente algunos campos, pero ahora una falla de análisis estricta devolverá un mapa vacío.
        // Deje que execWebSearch regrese"desaparecido query"Consejo: activa LLM para regenerar parámetros completos.
        val args = ToolArgsParser.parse("""{"query":"El tiempo en Pekín""")
        assertTrue("truncated JSON must not return partial fields, got $args", args.isEmpty())
    }

    @Test
    fun `concatenated json objects are not silently merged`() {
        // {"limit":20}{"query":"x"} Esta cadena concatenada devolverá un mapa vacío si se analiza estrictamente.
        // La causa raíz del empalme se ha solucionado en OpenAIProvider.ToolCallArgsAccumulator.
        // Los argumentos que lleguen aquí ya deberían ser un solo objeto; Aquí sólo se realiza la validación defensiva.
        val args = ToolArgsParser.parse("""{"limit":20}{"query":"x"}""")
        assertTrue(args.isEmpty())
    }

    @Test
    fun `boolean and null values handled`() {
        val args = ToolArgsParser.parse("""{"flag":true,"missing":null}""")
        assertEquals("true", args["flag"])
        // JsonNull.content es una cuerda "null"
        assertEquals("null", args["missing"])
    }
}
