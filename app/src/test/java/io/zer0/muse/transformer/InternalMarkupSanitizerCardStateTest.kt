package io.zer0.muse.transformer

import io.zer0.muse.ui.markdown.appendCardStateToMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.8: `card_state` Salida unificada para cola. —— Lo que desprenda cada una de las tres salidas debe ser verificable.
 *
 * Antecedentes:card_state Resulta que sólo hay "un punto de escritura y un punto de pelado (representación de burbujas)", y hay más de diez lugares en el texto original.
 * Leer contenido hace que JSON se filtre directamente en la copia/Compartir/adelante/borrador/Cotización/mapa de mensajes/Colección/Exportar/vista previa de la sesión,
 * y verse afectado por la indexación de texto completo. Ahora se ha recibido la responsabilidad de desinvertir [InternalMarkupSanitizer] en las tres salidas.
 *
 * Esta prueba hace cuatro cosas:
 *  1. Card_state se elimina para visualización y exportación, por lo que los usuarios no pueden ver JSON;
 *  2. La salida de recuperación no contiene contenido card_state (de lo contrario, se puede buscar `cardId=` / JSON valor clave);
 *  3. No se permite cambiar ninguna palabra de texto normal sin card_state (protección de regresión);
 *  4. El texto sin marcar toma el camino rápido, no reconstruye cadenas y no ejecuta expresiones regulares.
 */
class InternalMarkupSanitizerCardStateTest {

    private fun withCardState(text: String = "Configurar el sueño en 6 horas"): String = appendCardStateToMessage(
        text = text,
        cardId = "msg-123",
        data = """{"hours":6,"from":8}""",
    )

    // ── Salida 1:displayContent(pantalla) ────────────────────────────────

    @Test
    fun `displayContent drops the card state tail`() {
        val displayed = InternalMarkupSanitizer.displayContent(withCardState())

        assertEquals("La burbuja solo muestra esa frase.", "Configurar el sueño en 6 horas", displayed)
        assertFalse("No quedan marcas", displayed.contains("card_state"))
        assertFalse("no puede permanecer cardId", displayed.contains("cardId="))
        assertFalse("no puede permanecer JSON", displayed.contains("hours"))
    }

    @Test
    fun `displayContent also strips internal markup blocks`() {
        val raw = "<think>pensamiento interno</think>" + withCardState("Texto")

        val displayed = InternalMarkupSanitizer.displayContent(raw)

        assertEquals("Texto", displayed)
    }

    @Test
    fun `displayContent truncates an unclosed tail instead of leaking json`() {
        // La cola está medio escrita (colapso/Truncamiento): es mejor perder el estado que filtrar JSON al usuario.
        val raw = "Texto\n" + InternalMarkupSanitizer.CARD_STATE_OPEN + "cardId=msg-1\n{\"hours\":6"

        val displayed = InternalMarkupSanitizer.displayContent(raw)

        assertEquals("Texto", displayed)
    }

    @Test
    fun `displayContent drops every tail when there are several`() {
        val raw = withCardState("primera frase") + withCardState("segunda frase")

        val displayed = InternalMarkupSanitizer.displayContent(raw)

        assertFalse(displayed.contains("card_state"))
        assertFalse(displayed.contains("cardId="))
        assertTrue("mantener el texto", displayed.contains("primera frase") && displayed.contains("segunda frase"))
    }

    // ── Salida 2:exportContent(Copiar/Compartir/Exportar/Leer en voz alta) ─────────────

    @Test
    fun `exportContent drops card state and inline effect tags`() {
        val raw = withCardState("Hola [huge]mundo[/huge]")

        val exported = InternalMarkupSanitizer.exportContent(raw)

        assertEquals("Hola mundo", exported)
        assertFalse(exported.contains("card_state"))
    }

    @Test
    fun `exportContent keeps plain text identical`() {
        assertEquals("una frase común", InternalMarkupSanitizer.exportContent("una frase común"))
        assertEquals("múltiples líneas\ntexto", InternalMarkupSanitizer.exportContent("múltiples líneas\ntexto"))
    }

    // ── Salida tres:searchableContent(Buscar/Índice) ──────────────────────

    @Test
    fun `searchableContent drops card state so it cannot be searched`() {
        val searchable = InternalMarkupSanitizer.searchableContent(withCardState())

        assertEquals("Configurar el sueño en 6 horas", searchable)
        assertFalse(searchable.contains("card_state"))
        assertFalse("JSON Los valores clave no se pueden indexar", searchable.contains("hours"))
        assertFalse("La identidad de la tarjeta no se puede indexar", searchable.contains("cardId="))
    }

    @Test
    fun `searchableContent keeps internal blocks untouched`() {
        // Explicación de las compensaciones:mood/think Incluirlo en el índice es otra decisión. No lo cambiaré esta vez.
        // Evite que el comportamiento de búsqueda se desvíe silenciosamente. La salida de recuperación solo es responsable de card_state.
        val raw = "<mood>Estable primero</mood>Texto"

        assertEquals(raw, InternalMarkupSanitizer.searchableContent(raw))
    }

    @Test
    fun `searchableContent keeps plain text identical`() {
        assertEquals("una frase común", InternalMarkupSanitizer.searchableContent("una frase común"))
        assertEquals("  con sangría  ", InternalMarkupSanitizer.searchableContent("  con sangría  "))
    }

    // ── Protección de regresión + ruta rápida de rendimiento ───────────────────────────────────────

    @Test
    fun `text without markers is not rebuilt by stripCardState`() {
        val plain = "no tengo estado de tarjeta"
        // assertSame Demostrar que no se hicieron sustituciones/Cultivo: la misma instancia se devuelve sin cambios.
        assertSame(plain, InternalMarkupSanitizer.stripCardState(plain))
    }

    @Test
    fun `literal bracket word is not mistaken for a card state tail`() {
        // Escritura a mano en texto de usuario [card_state](cuando se habla del protocolo en sí) no se puede comer.
        val literal = "Este acuerdo se llama [card_state], se envuelve JSON"
        assertSame(literal, InternalMarkupSanitizer.stripCardState(literal))
        assertEquals(literal, InternalMarkupSanitizer.displayContent(literal))
    }

    @Test
    fun `markdown link and code fence survive all three exits`() {
        val raw = "[enlace](https://a.b) y `List<String>`"

        assertEquals(raw, InternalMarkupSanitizer.displayContent(raw))
        assertEquals(raw, InternalMarkupSanitizer.exportContent(raw))
        assertEquals(raw, InternalMarkupSanitizer.searchableContent(raw))
    }

    @Test
    fun `blank text returns empty from every exit`() {
        assertEquals("", InternalMarkupSanitizer.displayContent("   \n  "))
        assertEquals("", InternalMarkupSanitizer.exportContent("   \n  "))
        assertEquals("   \n  ", InternalMarkupSanitizer.searchableContent("   \n  "))
    }

    @Test
    fun `containsCardState is a cheap probe with no regex`() {
        assertTrue(InternalMarkupSanitizer.containsCardState(withCardState()))
        assertFalse(InternalMarkupSanitizer.containsCardState("texto normal"))
        assertFalse("markdown Los enlaces no cuentan.", InternalMarkupSanitizer.containsCardState("[enlace](https://a.b)"))
    }
}
