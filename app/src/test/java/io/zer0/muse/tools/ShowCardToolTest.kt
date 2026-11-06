package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueba única de identidad de tarjeta y vinculación de datos.
 *
 * La identidad de una tarjeta es la identificación del mensaje que la lleva, y esta identificación no es visible para el modelo. Si algún enlace de este enlace está roto,
 * El rendimiento es simplemente "los datos de la tarjeta se han actualizado pero la tarjeta no ha cambiado", no se informa ningún error ni se registra ningún registro. —— Así que fíjate con las pruebas.
 */
class ShowCardToolTest {

    @Test
    fun `Apunta al mensaje actual cuando se omite card_id`() {
        assertEquals("msg-1", ShowCardTool.resolveCardTarget(null, "msg-1"))
        assertEquals("msg-1", ShowCardTool.resolveCardTarget("", "msg-1"))
        assertEquals("msg-1", ShowCardTool.resolveCardTarget("   ", "msg-1"))
    }

    @Test
    fun `Card_id dado explícitamente tiene prioridad sobre el mensaje actual`() {
        assertEquals("msg-old", ShowCardTool.resolveCardTarget("msg-old", "msg-now"))
        assertEquals("msg-old", ShowCardTool.resolveCardTarget("  msg-old  ", "msg-now"))
    }

    @Test
    fun `Resolver como vacío cuando no hay una identificación de mensaje de contexto`() {
        assertEquals("", ShowCardTool.resolveCardTarget(null, null))
        assertEquals("", ShowCardTool.resolveCardTarget("", null))
    }

    @Test
    fun `El recibo contiene el mensaje vinculado a la tarjeta id`() {
        val result = ShowCardTool.execute(
            args = mapOf("title" to "demo", "code" to "<div>hi</div>"),
            messageId = "msg-42",
        )

        assertTrue("El modelo necesita saber a qué mensaje está vinculada esta tarjeta.", result.contains("msg-42"))
    }

    @Test
    fun `Los recibos no son locuaces cuando no hay una identificación del mensaje`() {
        val result = ShowCardTool.execute(
            args = mapOf("title" to "demo", "code" to "<div>hi</div>"),
            messageId = null,
        )

        assertFalse(result.contains("Card bound to message"))
        assertTrue(result.contains("rendered"))
    }
}
