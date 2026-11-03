package io.zer0.muse.tools

import io.zer0.muse.tools.ToolRequirements.Channel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.7: Determinación de la disponibilidad de herramientas —— tool_status de fundación.
 *
 * La queja principal del autoexamen del asistente es: "No sé qué herramienta se puede utilizar realmente". Esta prueba asegura:
 *  - Las herramientas dentro de la aplicación están disponibles con cualquier permiso (los modelos no se pueden vincular a la memoria)/No me atrevo a utilizar la tarjeta);
 *  - Las herramientas que requieren un canal se consideran correctamente como no disponibles cuando falta el canal (evitando "déjame hacer clic en él" y luego fallar);
 *  - anyOf La semántica (ya sea Accesibilidad o Shell será suficiente) no se confunde con la semántica de allOf (ambas deben ser verdaderas).
 */
class ToolRequirementsTest {

    @Test
    fun `app-internal tools are usable without any channel`() {
        val none = emptySet<Channel>()
        // Estos son los lotes de modelos más utilizados y también deben estar disponibles cuando faltan permisos.
        listOf("show_card", "save_memory", "search_memory", "todo_write", "calculator", "find_tools")
            .forEach { tool ->
                assertTrue("$tool No debe depender de los canales del dispositivo", ToolRequirements.of(tool).isUnconditional)
                assertTrue("$tool debería estar disponible", ToolRequirements.isUsable(tool, none))
            }
    }

    @Test
    fun `screen tools require accessibility or shell`() {
        val req = ToolRequirements.of("screen_tap")
        assertTrue("Debe confiar en los canales de pantalla", req.anyOf.contains(Channel.ACCESSIBILITY))
        assertTrue("Shell también debería estar disponible", req.anyOf.contains(Channel.SHELL))

        assertFalse("No disponible cuando no hay ningún canal", ToolRequirements.isUsable("screen_tap", emptySet()))
        assertTrue("Sin barreras", ToolRequirements.isUsable("screen_tap", setOf(Channel.ACCESSIBILITY)))
        assertTrue("La concha también está disponible", ToolRequirements.isUsable("screen_tap", setOf(Channel.SHELL)))
    }

    @Test
    fun `shell-only tools reject accessibility alone`() {
        // device_shell No se puede hacer sin barreras: sólo no se puede permitir el acceso sin barreras.
        assertFalse(
            "La accesibilidad no es un sustituto Shell",
            ToolRequirements.isUsable("device_shell", setOf(Channel.ACCESSIBILITY)),
        )
        assertTrue(
            "Shell Listo",
            ToolRequirements.isUsable("device_shell", setOf(Channel.SHELL)),
        )
        assertTrue(
            "Root También cubierto Shell",
            ToolRequirements.isUsable("device_shell", setOf(Channel.ROOT)),
        )
    }

    @Test
    fun `single runtime permissions gate their own tools`() {
        val none = emptySet<Channel>()
        assertFalse(ToolRequirements.isUsable("get_recent_notifications", none))
        assertTrue(
            ToolRequirements.isUsable("get_recent_notifications", setOf(Channel.NOTIFICATION_LISTENER)),
        )
        assertFalse(ToolRequirements.isUsable("screen_time", setOf(Channel.ACCESSIBILITY)))
        assertTrue(ToolRequirements.isUsable("screen_time", setOf(Channel.USAGE_STATS)))
    }

    @Test
    fun `unknown tool defaults to unconditional`() {
        // Cuando se olvida registrar una nueva herramienta, se considerará disponible (el modelo la probará una vez).
        // No lo juzgues erróneamente como no disponible (el modelo abandonará permanentemente una herramienta que realmente funciona).
        assertTrue(ToolRequirements.of("some_new_tool_never_registered").isUnconditional)
    }
}
