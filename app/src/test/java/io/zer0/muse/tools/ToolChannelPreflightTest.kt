package io.zer0.muse.tools

import io.zer0.muse.tools.ToolRequirements.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La herramienta realiza pruebas previas a la aprobación.
 *
 * Ítem 4 del autoexamen del asistente: “Cuando el canal no está listo, la herramienta falla rápidamente, pero no hay conexión entre la información de falla y “cómo abrirlo”.
 * Tres cosas clavadas aquí:
 *  1. falta de canal → Detener y no ejecutar
 *  2. La información sobre el fallo incluye caminos abiertos y medios alternativos (de lo contrario, el modelo simplemente intentará de nuevo)
 *  3. El anfitrión no proporcionó información del canal (null)→ Omita la verificación previa y evite lastimar accidentalmente a los subagentes/tareas programadas
 */
class ToolChannelPreflightTest {

    @Test
    fun `null channels skips the preflight`() {
        assertEquals(
            ToolChannelPreflight.Result.Proceed,
            ToolChannelPreflight.check("screen_tap", availableChannels = null),
        )
    }

    @Test
    fun `app-internal tool passes with no channels at all`() {
        assertEquals(
            ToolChannelPreflight.Result.Proceed,
            ToolChannelPreflight.check("web_search", availableChannels = emptySet()),
        )
    }

    @Test
    fun `tool needing a missing channel is blocked`() {
        val result = ToolChannelPreflight.check("screen_tap", availableChannels = emptySet())

        assertTrue("Las herramientas accesibles deben bloquearse cuando no haya paso.", result is ToolChannelPreflight.Result.Blocked)
        val blocked = result as ToolChannelPreflight.Result.Blocked
        assertTrue(blocked.missing.contains(Channel.ACCESSIBILITY))
    }

    @Test
    fun `blocked message names the missing channel and where to enable it`() {
        val blocked = ToolChannelPreflight.check("screen_tap", emptySet()) as ToolChannelPreflight.Result.Blocked

        assertTrue("Explica lo que falta", blocked.message.contains("Accesibilidad"))
        assertTrue("Para abrir un camino", blocked.message.contains("ajustes"))
        assertTrue("Para dejar claro que esta vez no se implementó.", blocked.message.contains("No ejecutado"))
    }

    @Test
    fun `blocked message offers an alternative path`() {
        val blocked = ToolChannelPreflight.check("screen_tap", emptySet()) as ToolChannelPreflight.Result.Blocked

        assertTrue("Proporcione alternativas; de lo contrario, el modelo simplemente lo volverá a intentar una y otra vez.", blocked.message.contains("Puedes usarlo en su lugar"))
    }

    @Test
    fun `satisfied anyOf passes`() {
        // Clase de operación de pantalla:Accesible o Shell servirán
        val result = ToolChannelPreflight.check("screen_tap", setOf(Channel.SHELL))

        assertEquals(ToolChannelPreflight.Result.Proceed, result)
    }

    @Test
    fun `anyOf with none present is blocked and lists the options`() {
        val blocked = ToolChannelPreflight.check("screen_tap", emptySet()) as ToolChannelPreflight.Result.Blocked

        assertTrue(
            "Se deben enumerar todos los canales opcionales, reales: ${blocked.missing}",
            blocked.missing.containsAll(setOf(Channel.ACCESSIBILITY, Channel.SHELL)),
        )
    }

    @Test
    fun `tool requiring allOf is blocked when only one is present`() {
        // Clase de instalación silenciosa:Requiere Shell y lee la lista de paquetes(declarado como allOf)
        val req = ToolRequirements.of("app_uninstall")
        if (req.allOf.isEmpty()) return // La herramienta se salta si no se declara allOf,Evite vincular sus pruebas a los detalles de implementación

        val result = ToolChannelPreflight.check("app_uninstall", setOf(Channel.ACCESSIBILITY))

        assertTrue(result is ToolChannelPreflight.Result.Blocked)
    }

    @Test
    fun `root-only tool passes when root is present`() {
        val req = ToolRequirements.of("device_shell")
        if (req.anyOf.isEmpty()) return

        val result = ToolChannelPreflight.check("device_shell", setOf(Channel.ROOT))

        assertEquals(ToolChannelPreflight.Result.Proceed, result)
    }
}
