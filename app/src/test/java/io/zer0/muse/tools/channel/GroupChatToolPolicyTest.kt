package io.zer0.muse.tools.channel

import io.zer0.ai.core.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B8-03 / v2.x (B3): Prueba de estrategia de entrada y salida de herramienta de medios de chat grupal. */
class GroupChatToolPolicyTest {

    private fun def(name: String) = ToolDefinition(
        name = name,
        description = name,
        parametersJsonSchema = "{}",
    )

    @Test
    fun mediaFilterKeepsOnlyEnabledMediaTools() {
        // v2.x cierre del legado: generate_video El canal de vídeo también se publica una vez finalizado.,Hay tres herramientas multimedia disponibles
        val input = listOf(
            def("web_search"),
            def("generate_image"),
            def("generate_video"),
            def("generate_qr_code"),
            def("calculator"),
        )

        val result = GroupChatToolPolicy.filterMediaTools(input)

        assertEquals(listOf("generate_image", "generate_video", "generate_qr_code"), result.map { it.name })
        assertTrue(result.all { it.name in GroupChatToolPolicy.ENABLED_MEDIA_TOOLS })
        assertFalse(result.any { it.name in GroupChatToolPolicy.BLOCKED_MEDIA_TOOLS })
    }

    @Test
    fun regularFilterDropsBlockedMediaAndHighRisk() {
        // P1-11: Las herramientas de alto riesgo deben estar bloqueadas mediante listas blancas de riesgo;herramientas multimedia(generate_* prefijo = SAFE)aquí no
        // Eliminado del filtro Herramientas generales(Inyectado por separado por filterMediaTools).
        val input = listOf(
            def("web_search"),
            def("generate_image"),
            def("generate_qr_code"),
            def("generate_video"),
            def("send_sms"),
            def("workspace_write"),
            def("calculator"),
        )

        val result = GroupChatToolPolicy.filterRegularTools(input)

        assertEquals(
            listOf("web_search", "generate_image", "generate_qr_code", "generate_video", "calculator"),
            result.map { it.name },
        )
        assertFalse(result.any { it.name in GroupChatToolPolicy.BLOCKED_MEDIA_TOOLS })
    }

    @Test
    fun keepsRegularToolsWhenNoMediaToolsPresent() {
        val input = listOf(def("web_search"), def("schedule_reminder"))

        val result = GroupChatToolPolicy.filterRegularTools(input)

        assertEquals(2, result.size)
        assertTrue(result.any { it.name == "web_search" })
        assertTrue(result.any { it.name == "schedule_reminder" })
    }

    @Test
    fun filtersHighRiskToolsFromDirectExecutionChannel() {
        // P1-11: Herramientas generales de chat grupal sin aprobación,Las listas blancas de riesgo deben bloquear la ejecución directa de instrumentos de alto riesgo
        val input = listOf(
            def("web_search"),
            def("send_sms"),
            def("workspace_write"),
            def("calculator"),
        )

        val result = GroupChatToolPolicy.filterRegularTools(input)

        assertEquals(listOf("web_search", "calculator"), result.map { it.name })
    }
}
