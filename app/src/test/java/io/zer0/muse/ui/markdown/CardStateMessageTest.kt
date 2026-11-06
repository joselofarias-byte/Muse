package io.zer0.muse.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.7: Estado de cola de retorno de bucle cerrado de tarjeta —— montar/El pelado debe ser reversible.
 *
 * Esta prueba sigue dos direcciones:
 *  - Lado del modelo:`appendCardStateToMessage` El estado debe incluirse en el cuerpo del mensaje (de lo contrario, no se podrá leer en la siguiente ronda);
 *  - Lado del usuario:`stripCardStateFromMessage` El estado debe borrarse (de lo contrario, aparecerá JSON en la burbuja).
 * Ambas partes son ciertas,"tarjeta termina en tarjeta"Sólo entonces podrá realmente compensarse.
 */
class CardStateMessageTest {

    @Test
    fun `appends card state as machine readable tail`() {
        val text = "Configurar el sueño en 6 horas"
        val merged = appendCardStateToMessage(
            text = text,
            cardId = "msg-123",
            data = """{"hours":6,"from":8}""",
        )

        assertTrue("Se debe conservar el texto.", merged.startsWith(text))
        assertTrue("Debe traer cédula de identidad", merged.contains("cardId=msg-123"))
        assertTrue("Debe tener estatus estructurado.", merged.contains("\"hours\""))
        assertTrue("Debe tener marcas de inicio y fin.", merged.contains(CARD_STATE_OPEN) && merged.contains(CARD_STATE_CLOSE))
    }

    @Test
    fun `strip removes state and keeps the sentence`() {
        val text = "Configurar el sueño en 6 horas"
        val merged = appendCardStateToMessage(text, "msg-123", """{"hours":6}""")

        val displayed = stripCardStateFromMessage(merged)

        assertEquals("La burbuja solo muestra esa frase.", text, displayed)
        assertFalse("No quedan marcas", displayed.contains(CARD_STATE_OPEN))
        assertFalse("no puede permanecer JSON", displayed.contains("hours"))
    }

    @Test
    fun `null or blank data leaves text untouched`() {
        val text = "noticias generales"
        assertEquals(text, appendCardStateToMessage(text, "msg-1", null))
        assertEquals(text, appendCardStateToMessage(text, "msg-1", "   "))
    }

    @Test
    fun `plain text without state is unchanged by strip`() {
        val text = "no tengo estado de tarjeta"
        assertEquals(text, stripCardStateFromMessage(text))
    }

    @Test
    fun `invalid json is still delivered verbatim`() {
        // El script de la tarjeta puede escribir mal JSON. En este momento, prefiero pasar el pase tal como está que perder el estado. ——
        // Cuando el modelo ve un JSON incorrecto, puede juzgarlo por sí mismo. Cuando vea el estado vacío, simplemente pensará que la tarjeta no devolvió ningún contenido.
        val merged = appendCardStateToMessage("Resumen", "msg-9", "{not-json")
        assertTrue(merged.contains("{not-json"))
        assertEquals("Resumen", stripCardStateFromMessage(merged))
    }

    @Test
    fun `missing card id degrades to unknown instead of dropping state`() {
        val merged = appendCardStateToMessage("Resumen", null, """{"a":1}""")
        assertTrue(merged.contains("cardId=unknown"))
    }
}
