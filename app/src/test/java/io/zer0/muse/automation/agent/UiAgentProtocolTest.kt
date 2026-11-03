package io.zer0.muse.automation.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v2.2.1 GUI Agent anillo:Prueba unitaria de análisis del protocolo de acción.
 */
class UiAgentProtocolTest {

    @Test
    fun `analizar tap`() {
        val action = UiAgentProtocol.parse("""do(action="tap", x=540, y=1200)""")
        assertEquals(UiAgentProtocol.Action.Tap(540, 1200), action)
    }

    @Test
    fun `Analizar el clic de ID de control estable`() {
        val action = UiAgentProtocol.parse(
            """do(action="tap_id", view_id="com.example:id/settings", max_swipes=2, verify_text="Settings")""",
        )
        assertEquals(UiAgentProtocol.Action.TapId("com.example:id/settings", 2, "Settings"), action)
    }

    @Test
    fun `Analizar el clic de coordenadas con etiqueta`() {
        val action = UiAgentProtocol.parse("""do(action="tap", x=10, y=20, label="Settings")""")
        assertEquals(UiAgentProtocol.Action.Tap(10, 20, "Settings"), action)
    }

    @Test
    fun `Análisis de clics semánticos con validación y desplazamiento limitado`() {
        val action = UiAgentProtocol.parse(
            """do(action="tap_text", text="Settings, network", exact=true, max_swipes=3, verify_text="Network")""",
        )
        assertEquals(
            UiAgentProtocol.Action.TapText("Settings, network", exact = true, maxSwipes = 3, verifyText = "Network"),
            action,
        )
    }

    @Test
    fun `Analizar deslizamiento con duración`() {
        val action = UiAgentProtocol.parse("""do(action="swipe", x1=540, y1=1500, x2=540, y2=600, duration=300)""")
        assertEquals(UiAgentProtocol.Action.Swipe(540, 1500, 540, 600, 300L), action)
    }

    @Test
    fun `Analizar el texto citado que contiene comas`() {
        val action = UiAgentProtocol.parse("""do(action="text", text="Hola,mundo")""")
        assertEquals(UiAgentProtocol.Action.TextInput("Hola,mundo"), action)
    }

    @Test
    fun `Analizar emoticones y texto entre corchetes sin truncar la acción`() {
        val action = UiAgentProtocol.parse(
            """do(action="text", text="Send this (then smile :) and continue)")""",
        )
        assertEquals(UiAgentProtocol.Action.TextInput("Send this (then smile :) and continue)"), action)
    }

    @Test
    fun `finish Mantener el texto completo cuando el resultado contenga paréntesis`() {
        val action = UiAgentProtocol.parse("""finish(success=true, result="Sent (with confirmation)")""")
        assertEquals(UiAgentProtocol.Action.Finish("Sent (with confirmation)", completed = true), action)
    }

    @Test
    fun `quoted argument supports escaped quotes and action-like text`() {
        val action = UiAgentProtocol.parse(
            """do(action="text", text="Say \"do(action=click)\" then (continue)")""",
        )
        assertEquals(UiAgentProtocol.Action.TextInput("Say \"do(action=click)\" then (continue)"), action)
    }

    @Test
    fun `finish-like text inside a quoted input is not treated as a finish action`() {
        val action = UiAgentProtocol.parse(
            """do(action="text", text="Please type finish(success=true) literally")""",
        )

        assertEquals(
            UiAgentProtocol.Action.TextInput("Please type finish(success=true) literally"),
            action,
        )
    }

    @Test
    fun `finish distinguishes explicit blocked status from missing completion status`() {
        assertEquals(
            UiAgentProtocol.Action.Finish("No se puede encontrar el objetivo", completed = false),
            UiAgentProtocol.parse("""finish(status="blocked", result="No se puede encontrar el objetivo")"""),
        )
        assertEquals(
            UiAgentProtocol.Action.Finish("maybe done", completed = null),
            UiAgentProtocol.parse("""finish(result="maybe done")"""),
        )
    }

    @Test
    fun `Analizar valor corto sin comillas de clave`() {
        val action = UiAgentProtocol.parse("""do(action="key", key=back)""")
        assertEquals(UiAgentProtocol.Action.Key("back"), action)
    }

    @Test
    fun `analizar finish`() {
        val action = UiAgentProtocol.parse("""finish(success=true, result="Mensaje enviado")""")
        assertEquals(UiAgentProtocol.Action.Finish("Mensaje enviado", completed = true), action)
    }

    @Test
    fun `Toma la última expresión`() {
        val raw = """
            Debo hacer clic en el cuadro de búsqueda.
            do(action="tap", x=1, y=2)
            De hecho, primero debes ingresar a la página de búsqueda y luego hacer clic.
            do(action="tap", x=100, y=200)
        """.trimIndent()
        assertEquals(UiAgentProtocol.Action.Tap(100, 200), UiAgentProtocol.parse(raw))
    }

    @Test
    fun `finish Tiene prioridad si es más tarde finish`() {
        val raw = """do(action="tap", x=1, y=2) entonces finish(success=true, result="Completo")"""
        assertEquals(UiAgentProtocol.Action.Finish("Completo", completed = true), UiAgentProtocol.parse(raw))
    }

    @Test
    fun `La grabación de acción no refleja el texto ingresado`() {
        val secret = "one-time private message"
        val action = UiAgentProtocol.Action.TextInput(secret)

        val description = UiAgentProtocol.describe(action)

        assertEquals("Introducir texto(${secret.length}personaje)", description)
        org.junit.Assert.assertNull(description.takeIf { it.contains(secret) })
    }

    @Test
    fun `wait Predeterminado y abrazadera`() {
        assertEquals(UiAgentProtocol.Action.Wait(800L), UiAgentProtocol.parse("""do(action="wait")"""))
        assertEquals(UiAgentProtocol.Action.Wait(10_000L), UiAgentProtocol.parse("""do(action="wait", ms=99999)"""))
    }

    @Test
    fun `Devoluciones de producción ilegal null`() {
        assertNull(UiAgentProtocol.parse("no se que hacer"))
        assertNull(UiAgentProtocol.parse("""do(action="tap", x=abc, y=1)"""))
        assertNull(UiAgentProtocol.parse("""do(action="dance", x=1, y=1)"""))
    }
}
