package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.6.0: show_card Análisis de parámetros de herramienta. —— Entrada al renderizado de tarjetas en línea.
 *
 * Portada:HTML/SVG normal, datos anidados, cadena JSON escapada, falta código de reserva.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShowCardArgsTest {

    @Test
    fun `parses html code and inline data`() {
        val spec = parseShowCardArgs(
            """{"title":"t","code":"<div style=\"x\">hi</div>","data":"{\"values\":[1,2]}"}""",
        )
        assertTrue(spec != null)
        assertEquals("<div style=\"x\">hi</div>", spec!!.code)
        assertEquals("{\"values\":[1,2]}", spec.data)
        assertFalse(spec.isSvg)
    }

    @Test
    fun `detects svg code`() {
        val spec = parseShowCardArgs("""{"code":"<svg xmlns=\"...\"><circle/></svg>"}""")
        assertTrue(spec != null)
        assertTrue(spec!!.isSvg)
    }

    @Test
    fun `decodes escaped json string code`() {
        // Los modelos suelen pasar HTML como cadenas JSON con escape.;JSONObject Debe ser el texto original después de la decodificación.
        val spec = parseShowCardArgs("""{"code":"<div class=\"a\">x</div>"}""")
        assertEquals("<div class=\"a\">x</div>", spec!!.code)
    }

    @Test
    fun `nested object data is kept as json text`() {
        val spec = parseShowCardArgs("""{"code":"<div/>","data":{"values":[1,2,3]}}""")
        assertTrue(spec != null)
        assertTrue(spec!!.data!!.contains("\"values\""))
    }

    @Test
    fun `missing code returns null`() {
        assertNull(parseShowCardArgs("""{"title":"only title"}"""))
        assertNull(parseShowCardArgs("""{"code":"   "}"""))
        assertNull(parseShowCardArgs(""))
        assertNull(parseShowCardArgs("not json"))
    }

    // ── v2.6.7: Streaming de producto semiacabado (la tarjeta se mostrará cuando se acabe de generar)──────────────────────

    /**
     * Puntos débiles medidos: los parámetros de la herramienta se ensamblan pieza por pieza en un formato de transmisión y el JSON completo no se establece hasta el final.
     * La implementación anterior siempre regresaba durante la transmisión. null → Primero se muestra como una tarjeta de herramientas y luego cambia a una tarjeta después de salir y volver a ingresar.
     * Ahora debería ser posible extraer el código llegado del JSON incompleto, permitiendo que la tarjeta crezca a medida que fluye.
     */
    @Test
    fun `partial json still yields code for live rendering`() {
        // La cadena no está cerrada y el objeto exterior tampoco está cerrado.
        val spec = parseShowCardArgs("""{"title":"t","code":"<div>hello""")
        assertTrue(spec != null)
        assertEquals("<div>hello", spec!!.code)
    }

    @Test
    fun `partial json decodes escapes while streaming`() {
        val spec = parseShowCardArgs("""{"code":"<div class=\"a\">x""")
        assertEquals("<div class=\"a\">x", spec!!.code)
    }

    @Test
    fun `partial json without code yet returns null`() {
        assertNull(parseShowCardArgs("""{"title":"half"""))
    }

    @Test
    fun `partial svg is detected while streaming`() {
        val spec = parseShowCardArgs("""{"code":"<svg xmlns='x'><circle""")
        assertTrue(spec!!.isSvg)
    }
}
