package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * I-AUDIT: ContentSafetyRules pruebas de límites estrictos — Completa la puerta de aprobación"Prueba cero"La tercera pieza de la brecha.
 *
 * Auditar estados P1-1 ToolPermissionResolver / ParamPolicies / ContentSafetyRules tres
 * Completamente no probado. Los dos primeros están cubiertos por ToolPermissionResolverTest,Esta clase agrega límites estrictos previos a la ejecución.
 * (js Procesos peligrosos/transferencia de información confidencial/archivos locales/recorrido de ruta/rutas absolutas)La barandilla de regreso.
 */
class ContentSafetyRulesTest {

    @Test
    fun `execute_javascript Bloquear la ejecución de procesos peligrosos`() {
        assertEquals(
            "js-dangerous-process",
            ContentSafetyRules.check("execute_javascript", "rm -rf /tmp/x")?.ruleId,
        )
        assertEquals(
            "js-dangerous-process",
            ContentSafetyRules.check("execute_javascript", "Runtime.getRuntime().exec(\"id\")")?.ruleId,
        )
    }

    @Test
    fun `execute_javascript Bloquear información confidencial para que no se transmita al exterior`() {
        assertEquals(
            "js-exfil-token",
            ContentSafetyRules.check("execute_javascript", "document.cookie")?.ruleId,
        )
    }

    @Test
    fun `open_url Bloquear archivos locales`() {
        assertEquals("url-local-file", ContentSafetyRules.check("open_url", "file:///etc/passwd")?.ruleId)
    }

    @Test
    fun `workspace_write Intercepción de recorrido de ruta y rutas absolutas`() {
        assertEquals("workspace-traversal", ContentSafetyRules.check("workspace_write", "../etc/passwd")?.ruleId)
        assertEquals("workspace-absolute-path", ContentSafetyRules.check("workspace_write", "/etc/passwd")?.ruleId)
    }

    @Test
    fun `Ruta relativa segura con herramienta desconocida que no cumple ninguna regla`() {
        assertNull(ContentSafetyRules.check("workspace_write", "files/note.txt"))
        assertNull(ContentSafetyRules.check("unknown_tool", "rm -rf /"))
    }

    @Test
    fun `El contenido del texto no participa en la coincidencia de reglas de ruta`() {
        // El camino es legal. Hay puntos suspensivos y escritura de ruta absoluta en el texto, por lo que no se puede bloquear como fuera de límites.
        val args =
            mapOf(
                "path" to "notes/todo.md",
                "content" to "Pendiente: Espere 3 días... Ver /usr/local/bin con C:\\Windows",
                "append" to "false",
            )
        assertNull(ContentSafetyRules.checkArgs("workspace_write", args))
    }

    @Test
    fun `El camino en sí cruza el límite y todavía está bloqueado`() {
        assertEquals(
            "workspace-traversal",
            ContentSafetyRules.checkArgs(
                "workspace_write",
                mapOf("path" to "../etc/passwd", "content" to "x"),
            )?.ruleId,
        )
        assertEquals(
            "workspace-absolute-path",
            ContentSafetyRules.checkArgs(
                "workspace_write",
                mapOf("path" to "/etc/passwd", "content" to "x"),
            )?.ruleId,
        )
    }
}
