package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * I-AUDIT: Prueba de regresión de puerta de aprobación de herramientas peligrosas.
 *
 * anteriormente ToolPermissionResolver / ParamPolicies / ContentSafetyRules Ninguna prueba en absoluto,
 * ultra vires/bypass(Confundir rm -rf,file:// URL, modo de permiso de sesión)Se puede fusionar sin alarma(auditoría P1-1).
 * Esta prueba utiliza afirmaciones puramente lógicas para bloquear los cuatro elementos de la puerta de aprobación.:modo de sesión × nivel de riesgo × estrategia de herramienta única × Estrategias paramétricas.
 */
class ToolPermissionResolverTest {

    @Before
    fun setUp() {
        // La estrategia parametrizada es un singleton a nivel de proceso.(ToolPermissionResolver init Registrado una vez),Idempotente y luego regístrate aquí,
        // Asegúrese de que el juicio de open_url/ejecutar_javascript deba surtir efecto en esta prueba.
        ParamPolicies.registerBuiltIn()
    }

    // ── TRUSTED modo:No es necesario aprobar caso por caso todas las herramientas que no estén explícitamente deshabilitadas. ──

    @Test
    fun `trusted mode auto approves all high tools including command tools`() {
        listOf(
            "send_sms",
            "make_phone_call",
            "add_contact",
            "add_calendar_event",
            "execute_javascript",
            "workspace_delete",
            "workspace_write",
            "workspace_move",
            "mcp_mgmt_remove",
            "mcp_mgmt_configure",
            "ui_get_page_info",
            "ui_click",
            "ui_long_press",
            "ui_swipe",
            "ui_set_text",
            "ui_screenshot",
            "ui_back",
            "ui_home",
            "ui_global_action",
            "ui_get_current_app",
            "ui_agent",
            "automation_workflow",
            "execute_shell",
            "execute_node_script",
            "virtual_screen",
            "virtual_screen_input",
            "screen_tap",
            "screen_tap_text",
            "screen_swipe",
            "screen_pinch",
            "screen_swipe_path",
            "screen_input",
            "screen_launch_app",
            "app_force_stop",
            "app_clear_data",
            "app_uninstall",
            "settings_get",
            "settings_put",
            "network_toggle",
            "am_start",
            "list_packages",
            "logcat_tail",
            "input_inject",
            "device_shell",
            "terminal_exec",
            "termux_exec",
        ).forEach { tool ->
            val result = ToolPermissionResolver.resolve(
                toolName = tool,
                risk = ToolRiskLevel.HIGH,
                mode = SessionPermissionMode.TRUSTED,
                perToolPolicy = null,
            )
            assertEquals("TRUSTED abajo $tool No se debe requerir aprobación caso por caso", ToolApprovalState.Auto, result)
        }
    }

    @Test
    fun agentToolsRemainApprovedInTrustedModeThroughTheCentralRiskResolver() {
        listOf(
            "automation_workflow", "virtual_screen", "virtual_screen_input",
            "screen_read", "screen_current_app", "screen_tap", "screen_tap_text",
            "screen_swipe", "screen_pinch", "screen_swipe_path", "screen_input", "screen_launch_app",
            "app_force_stop", "app_clear_data", "app_uninstall",
            "settings_get", "settings_put", "network_toggle", "am_start",
            "list_packages", "logcat_tail", "input_inject",
        ).forEach { tool ->
            val risk = ToolPermissionResolver.riskLevelFor(tool)
            assertEquals("$tool must resolve to HIGH", ToolRiskLevel.HIGH, risk)
            val result = ToolPermissionResolver.resolve(
                toolName = tool,
                risk = risk,
                mode = SessionPermissionMode.TRUSTED,
                perToolPolicy = null,
            )
            assertEquals("TRUSTED abajo $tool No se debe requerir aprobación caso por caso", ToolApprovalState.Auto, result)
        }
    }

    @Test
    fun `trusted mode auto-approves high tools not in the irreversible approval list`() {
        listOf("toggle_wifi", "toggle_bluetooth", "set_brightness", "open_url").forEach { tool ->
            val result = ToolPermissionResolver.resolve(
                toolName = tool,
                risk = ToolRiskLevel.HIGH,
                mode = SessionPermissionMode.TRUSTED,
                perToolPolicy = null,
            )
            assertEquals("TRUSTED abajo $tool debe ser liberado directamente", ToolApprovalState.Auto, result)
        }
    }

    @Test
    fun `trusted mode auto-approves safe and normal tools`() {
        assertEquals(
            ToolApprovalState.Auto,
            ToolPermissionResolver.resolve("get_current_time", ToolRiskLevel.SAFE, SessionPermissionMode.TRUSTED, null),
        )
        assertEquals(
            ToolApprovalState.Auto,
            ToolPermissionResolver.resolve(
                "clipboard_write",
                ToolRiskLevel.NORMAL,
                SessionPermissionMode.TRUSTED,
                null,
            ),
        )
    }

    // ── ASK modo ──

    @Test
    fun `ask mode auto-approves safe, asks normal and high`() {
        assertEquals(
            ToolApprovalState.Auto,
            ToolPermissionResolver.resolve("get_current_time", ToolRiskLevel.SAFE, SessionPermissionMode.ASK, null),
        )
        assertEquals(
            ToolApprovalState.Pending,
            ToolPermissionResolver.resolve("clipboard_write", ToolRiskLevel.NORMAL, SessionPermissionMode.ASK, null),
        )
        assertEquals(
            ToolApprovalState.Pending,
            ToolPermissionResolver.resolve("workspace_delete", ToolRiskLevel.HIGH, SessionPermissionMode.ASK, null),
        )
    }

    // ── P0-6: Semántica de revisión de recuperación de puntos de interrupción ──

    @Test
    fun `ask recovery must re-approve high tools even when saved as executing`() {
        // Restaurando desde el punto de interrupción executionState=EXECUTING Aprobación de repetición de llamada:tomar riesgos riskLevelFor(única fuente de verdad),
        // ASK En modo ALTO la herramienta debe Pending(Solicite la tarjeta de aprobación nuevamente),sin razon"Guardado como en ejecución"Automático directo.
        listOf(
            "send_email",
            "open_url",
            "workspace_write",
            "workspace_delete",
            "make_phone_call",
            "execute_javascript",
        ).forEach { tool ->
            val result = ToolPermissionResolver.resolve(
                toolName = tool,
                risk = ToolPermissionResolver.riskLevelFor(tool),
                mode = SessionPermissionMode.ASK,
                perToolPolicy = null,
            )
            assertEquals("ASK Al restaurar $tool Debe volver a ingresar para su aprobación.", ToolApprovalState.Pending, result)
        }
    }

    @Test
    fun `trusted recovery auto approves high tools via single source`() {
        // Para reanudar la revisión, utilice RiskLevelFor en lugar de registrar el libro mayor.:Incluso si el valor registrado se reduce a NORMAL,
        // TRUSTED Las herramientas de modo irreversible aún deben Pending(Evite eludir la aprobación en la recuperación CONFIANZA).
        listOf(
            "workspace_write",
            "workspace_delete",
            "workspace_move",
            "execute_javascript",
            "send_sms",
            "make_phone_call",
        ).forEach { tool ->
            val result = ToolPermissionResolver.resolve(
                toolName = tool,
                risk = ToolPermissionResolver.riskLevelFor(tool),
                mode = SessionPermissionMode.TRUSTED,
                perToolPolicy = null,
            )
            assertEquals("TRUSTED Al restaurar $tool No se debe requerir aprobación caso por caso", ToolApprovalState.Auto, result)
        }
    }

    // ── STRICT modo ──

    @Test
    fun `strict mode auto-approves only allowlisted safe tools`() {
        assertEquals(
            ToolApprovalState.Auto,
            ToolPermissionResolver.resolve("calculator", ToolRiskLevel.SAFE, SessionPermissionMode.STRICT, null),
        )
        assertEquals(
            ToolApprovalState.Pending,
            ToolPermissionResolver.resolve("workspace_delete", ToolRiskLevel.HIGH, SessionPermissionMode.STRICT, null),
        )
    }

    // ── estrategia de herramienta única ──

    @Test
    fun `always deny forbids the tool in every mode`() {
        SessionPermissionMode.entries.forEach { mode ->
            val result = ToolPermissionResolver.resolve(
                "get_current_time",
                ToolRiskLevel.SAFE,
                mode,
                ToolApprovalPolicy.ALWAYS_DENY,
            )
            assertTrue("ALWAYS_DENY debería estar en $mode rechazar", result is ToolApprovalState.Denied)
        }
    }

    @Test
    fun `always allow overrides mode and auto-approves`() {
        assertEquals(
            ToolApprovalState.Auto,
            ToolPermissionResolver.resolve(
                "workspace_delete",
                ToolRiskLevel.HIGH,
                SessionPermissionMode.TRUSTED,
                ToolApprovalPolicy.ALWAYS_ALLOW,
            ),
        )
        assertEquals(
            ToolApprovalState.Auto,
            ToolPermissionResolver.resolve(
                "workspace_delete",
                ToolRiskLevel.HIGH,
                SessionPermissionMode.STRICT,
                ToolApprovalPolicy.ALWAYS_ALLOW,
            ),
        )
    }

    @Test
    fun `session allow is only a fallback and does not override explicit deny`() {
        assertEquals(
            ToolApprovalPolicy.ALWAYS_ALLOW,
            effectivePerToolPolicy(null, allowedThisSession = true),
        )
        assertEquals(
            ToolApprovalPolicy.ALWAYS_DENY,
            effectivePerToolPolicy(ToolApprovalPolicy.ALWAYS_DENY, allowedThisSession = true),
        )
        assertEquals(
            null,
            effectivePerToolPolicy(null, allowedThisSession = false),
        )
    }

    // ── estrategia paramétrica(ParamPolicies) ──

    @Test
    fun `open_url rejects file scheme, http auto, unknown pending`() {
        val file = ToolPermissionResolver.resolve(
            "open_url",
            ToolRiskLevel.HIGH,
            SessionPermissionMode.TRUSTED,
            null,
            mapOf("url" to "file:///etc/passwd"),
        )
        assertTrue("file:// debe ser rechazado", file is ToolApprovalState.Denied)

        val http = ToolPermissionResolver.resolve(
            "open_url",
            ToolRiskLevel.HIGH,
            SessionPermissionMode.TRUSTED,
            null,
            mapOf("url" to "https://example.com"),
        )
        assertEquals(ToolApprovalState.Auto, http)

        val unknown = ToolPermissionResolver.resolve(
            "open_url",
            ToolRiskLevel.HIGH,
            SessionPermissionMode.TRUSTED,
            null,
            mapOf("url" to "custom-scheme://x"),
        )
        assertEquals("TRUSTED La descarga de un esquema desconocido no requiere tarjeta de aprobación,Déjalo en manos de la propia herramienta", ToolApprovalState.Auto, unknown)
    }

    // ── P0-8: STRICT Los patrones tienen prioridad sobre la parametrización Auto ──

    @Test
    fun `strict mode is not bypassed by param auto for open_url`() {
        // antes de la reparación:https open_url toma la estrategia de parametrización directamente Auto,Regresar antes de una decisión ESTRICTA → Omitir la aprobación.
        // Después de la reparación:STRICT La estrategia de parámetros sólo puede endurecerse,https También debe estar Pendiente.
        val result = ToolPermissionResolver.resolve(
            "open_url",
            ToolRiskLevel.HIGH,
            SessionPermissionMode.STRICT,
            null,
            mapOf("url" to "https://example.com"),
        )
        assertEquals("STRICT abajo open_url(https) Debe ser aprobado", ToolApprovalState.Pending, result)
    }

    @Test
    fun `strict mode still honors param deny for open_url`() {
        // El parámetro denegado se ajusta,STRICT Lo siguiente seguirá surtiendo efecto con la máxima prioridad.
        val result = ToolPermissionResolver.resolve(
            "open_url",
            ToolRiskLevel.HIGH,
            SessionPermissionMode.STRICT,
            null,
            mapOf("url" to "file:///etc/passwd"),
        )
        assertTrue("STRICT file:// todavía debe ser rechazado", result is ToolApprovalState.Denied)
    }

    @Test
    fun `ask mode keeps param auto for open_url`() {
        // Modo no ESTRICTO(ASK)La semántica automática parametrizada no se modifica.
        val result = ToolPermissionResolver.resolve(
            "open_url",
            ToolRiskLevel.HIGH,
            SessionPermissionMode.ASK,
            null,
            mapOf("url" to "https://example.com"),
        )
        assertEquals(ToolApprovalState.Auto, result)
    }

    @Test
    fun `execute_javascript rejects dangerous code patterns`() {
        listOf("rm -rf /tmp/x", "Runtime.getRuntime().exec(\"id\")", "document.cookie").forEach { code ->
            val result = ToolPermissionResolver.resolve(
                "execute_javascript",
                ToolRiskLevel.HIGH,
                SessionPermissionMode.TRUSTED,
                null,
                mapOf("code" to code),
            )
            assertTrue("Los JS peligrosos deben rechazarse: $code", result is ToolApprovalState.Denied)
        }
    }

    // ── Comando lista negra límite estricto ──

    @Test
    fun `isUnsafeCommand blocks metacharacters and blocked executables`() {
        assertTrue(ToolPermissionResolver.isUnsafeCommand("rm -rf /data")) // Lista negra de archivos ejecutables
        assertTrue(ToolPermissionResolver.isUnsafeCommand("echo $(id)")) // sustitución de comando
        assertTrue(ToolPermissionResolver.isUnsafeCommand("ls; rm -rf /")) // separador de comandos
        assertTrue(ToolPermissionResolver.isUnsafeCommand("cat /etc/passwd | sh")) // tubo
        assertFalse(ToolPermissionResolver.isUnsafeCommand("ls")) // Lanzamiento del comando de seguridad
    }

    @Test
    fun `isUnsafeCommand also catches damage the syntax and name checks miss`() {
        // Piso parchado en el paso 0:no contiene ningún carácter de sintaxis prohibido,Tampoco está en la lista negra de archivos ejecutables.
        // (`dd` No en la lista negra,`mkfs.ext4` Ni `mkfs`),Pero el poder destructivo es suficiente para detenerlo.
        assertTrue(ToolPermissionResolver.isUnsafeCommand("dd if=/dev/zero of=/dev/block/sda"))
        assertTrue(ToolPermissionResolver.isUnsafeCommand("mkfs.ext4 /dev/block/sda1"))
        assertTrue(ToolPermissionResolver.isUnsafeCommand("echo pwned > /system/etc/hosts"))
    }

    @Test
    fun `read only sandbox verbs are not caught by the new floor`() {
        // El piso solo suma rechazo,No puede eliminar accidentalmente los formularios de comando en la lista blanca de ejecución_shell.
        listOf(
            "ls", "cat notes.txt", "df", "du -sh", "echo hi", "whoami", "pwd", "tree",
            "stat notes.txt", "find . -name x", "grep TODO notes.txt", "head -n 5 notes.txt",
        ).forEach { command ->
            assertFalse("Los comandos de la lista blanca no deben ser eliminados accidentalmente por el piso:$command", ToolPermissionResolver.isUnsafeCommand(command))
        }
    }

    @Test
    fun `two tiers stay apart from the read-only judgement`() {
        // tubo/Los redireccionamientos todavía se consideran inseguros en entornos limitados de solo lectura,No se ve afectado en la primera marcha del terminal universal. —— Los dos archivos no se pueden combinar en un solo criterio.
        listOf("cat notes.txt | grep x", "ls -la > listing.txt", "echo \$PATH | wc -l").forEach { command ->
            assertTrue("execute_shell Todavía debería mantener estricta:$command", ToolPermissionResolver.isUnsafeCommand(command))
            assertNull("terminal_exec No deberías pararlo en primera.:$command", ShellCommandBoundary.forbidReason(command, null))
        }
    }
}
