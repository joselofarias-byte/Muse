package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.zer0.muse.automation.appcontrol.AndroidAppController
import io.zer0.muse.automation.appcontrol.AppControlTools
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.tools.AutomationTools
import io.zer0.muse.automation.vdisplay.VirtualDisplayClient
import io.zer0.muse.automation.vdisplay.VirtualDisplayServerManager
import io.zer0.muse.automation.vdisplay.VirtualDisplayTool
import io.zer0.muse.tools.defaultTool.UIToolsRegistrar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P0-3 Regresar:Barandillas de coherencia del libro mayor de nivel de riesgo tres de la herramienta.
 *
 * tres libros mayores:
 *  1. Registrar cuenta — El campo RiskLevel cuando cada registrador registra ToolDef
 *  2. tabla de riesgo explícito — [ToolPermissionResolver.explicitRiskTable](la única fuente verdadera)
 *  3. Análisis en tiempo de ejecución — [ToolPermissionResolver.riskLevelFor](Tabla explícita + inferencia de prefijo)
 *
 * restricción:
 *  - Cualquier instrumento que aparezca en una tabla de riesgo explícita.,La cuenta de registro no debe ser inferior al nivel de la tabla.(En particular, HIGH no se puede degradar a NORMAL,
 *    De lo contrario, el subagente/tarea programada/modo CONFIABLE omitirá la aprobación);
 *  - riskLevelFor Las herramientas conocidas de alto riesgo deben analizarse como HIGH,No tiene nada que ver con el valor registrado.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolRiskLedgerConsistencyTest {

    private fun buildLedger(): ToolRegistry {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = ToolRegistry(context)
        // Crear una instancia del registrador coherente con el enlace de inicio de la aplicación(Consulte ToolRegistrarBootstrapper para obtener una lista de registradores.).
        // Las anulaciones deben ser explícitas:Los registros que no se pudieron construir deben enumerarse UNAVAILABLE_REGISTRARS,Un registrador que falte en ambos hará que esta prueba falle——
        // De lo contrario, la cobertura del libro mayor se reducirá silenciosamente con la reconstrucción.,Las barreras de coherencia simplemente no existen.
        val unavailable = mutableListOf<String>()
        REGISTRARS.forEach { (name, build) ->
            runCatching { build(context, registry) }.onFailure { unavailable += name }
        }
        val unexpected = unavailable - UNAVAILABLE_REGISTRARS
        assertTrue(
            "No se puede crear una instancia del siguiente registrador,Hay una brecha de cobertura en el libro de riesgos.(Si está interesado, agregue UNAVAILABLE_REGISTRARS y escriba el motivo.):\n" +
                unexpected.joinToString("\n"),
            unexpected.isEmpty(),
        )
        assertTrue(
            "La cobertura del registrador es demasiado baja(${REGISTRARS.size - unavailable.size}/${REGISTRARS.size}),La barandilla del libro mayor se ha deteriorado.",
            REGISTRARS.size - unavailable.size >= 12,
        )
        // pin_memory / unpin_memory Registrado dinámicamente por AgentToolsRegistrar,ToolDef estático se fusiona directamente con el libro mayor
        registry.register(PinMemoryTool.toolDef()) { PinMemoryTool.execute(emptyMap(), mockk()) }
        registry.register(UnpinMemoryTool.toolDef()) { UnpinMemoryTool.execute(emptyMap(), mockk()) }
        return registry
    }

    private companion object {
        /** Registrador no construido:Debe haber una razón para todo,Evite que la cobertura se corte silenciosamente. */
        val UNAVAILABLE_REGISTRARS: Set<String> = setOf("AgentToolsRegistrar")

        /**
         * con `ToolRegistrarBootstrapper` La lista de registros mantiene una tabla de constructores consistente.
         *
         * Construir solo(La inscripción se produce en la sede de cada Registrador. init{});Esta prueba solo se preocupa por el nombre registrado de ToolDef y el nivel de riesgo.,
         * No se ejecutan herramientas,Entonces, si tiene grandes dependencias, simplemente use un simulacro relajado.
         */
        val REGISTRARS: List<Pair<String, (Context, ToolRegistry) -> Unit>> = listOf(
            "EncodingToolsRegistrar" to { c, r -> EncodingToolsRegistrar(c, r) },
            "CoreToolsRegistrar" to { c, r -> CoreToolsRegistrar(c, r) },
            "WeatherToolsRegistrar" to { c, r -> WeatherToolsRegistrar(c, r) },
            "ClipboardToolsRegistrar" to { c, r -> ClipboardToolsRegistrar(c, r) },
            "NetworkTextToolsRegistrar" to { c, r -> NetworkTextToolsRegistrar(c, r) },
            "ReminderToolsRegistrar" to { c, r -> ReminderToolsRegistrar(c, r) },
            "CalendarToolsRegistrar" to { c, r -> CalendarToolsRegistrar(c, r) },
            "PhoneToolsRegistrar" to { c, r -> PhoneToolsRegistrar(c, r) },
            "SystemToolsRegistrar" to { c, r -> SystemToolsRegistrar(c, r) },
            "ResourceToolsRegistrar" to { c, r -> ResourceToolsRegistrar(c, r) },
            "QuickNoteToolsRegistrar" to { c, r -> QuickNoteToolsRegistrar(c, r) },
            "ScheduledTaskToolsRegistrar" to { c, r -> ScheduledTaskToolsRegistrar(c, r) },
            "TranslateToolsRegistrar" to { _, r -> TranslateToolsRegistrar(r) },
            "TtsToolsRegistrar" to { c, r -> TtsToolsRegistrar(c, r) },
            "WorkspaceToolsRegistrar" to { _, r ->
                WorkspaceToolsRegistrar(r, mockk(relaxed = true))
            },
            "FileToolsRegistrar" to { c, r -> FileToolsRegistrar(r, c, c.filesDir) },
            "ShellSandboxToolRegistrar" to { c, r -> ShellSandboxToolRegistrar(r, c.filesDir) },
            "UIToolsRegistrar" to { c, r ->
                UIToolsRegistrar(r, mockk(relaxed = true), c)
            },
            "AutomationTools" to { _, r -> AutomationTools(mockk(relaxed = true)).register(r) },
            "AppControlTools" to { c, r -> AppControlTools(c, mockk<AndroidAppController>(relaxed = true)).register(r) },
            "RootToolsRegistrar" to { c, r -> RootToolsRegistrar(r, mockk<AutomationManager>(relaxed = true), c) },
            "VirtualDisplayTool" to { c, r ->
                VirtualDisplayTool(
                    c,
                    mockk<VirtualDisplayClient>(relaxed = true),
                    mockk<VirtualDisplayServerManager>(relaxed = true),
                    mockk<AutomationManager>(relaxed = true),
                ).register(r)
            },
            "PdfVisionToolsRegistrar" to { c, r ->
                PdfVisionToolsRegistrar(r, mockk(relaxed = true), c, c.filesDir)
            },
            // P2-23: herramientas de generación de medios(fotos/vídeo/código qr),Registrado originalmente por ChatViewModel
            "MediaGenToolsRegistrar" to { c, r ->
                MediaGenToolsRegistrar(
                    r,
                    MediaGenToolsImpl(
                        c,
                        mockk(relaxed = true),
                        mockk(relaxed = true),
                        mockk(relaxed = true),
                        mockk(relaxed = true),
                        mockk(relaxed = true),
                    ),
                )
            },
            // v2.0: OAuth herramienta conectora(connector_list / call_connector)
            "ConnectorToolsRegistrar" to { _, r ->
                ConnectorToolsRegistrar(r, mockk(relaxed = true))
            },
            // v2.0.1: Herramienta de mercado de complementos(plugin_market_search / plugin_market_install)
            "PluginMarketToolsRegistrar" to { _, r ->
                PluginMarketToolsRegistrar(r, mockk(relaxed = true))
            },
        )
    }

    @Test
    fun `registration ledger must not downgrade tools below explicit risk table`() {
        val registry = buildLedger()
        val explicitTable = ToolPermissionResolver.explicitRiskTable()

        val mismatches = registry.listTools()
            .filter { def -> explicitTable[def.name]?.let { it != def.riskLevel } == true }
            .map { def ->
                "${def.name}: registered=${def.riskLevel}, table=${explicitTable[def.name]}"
            }
            .sorted()

        assertTrue(
            "El libro de registro es inconsistente con la tabla de riesgo explícita(El valor de registro es inferior al de One True Source,resultará en subagentes/tareas programadas/TRUSTED Omitir aprobación):\n" +
                mismatches.joinToString("\n"),
            mismatches.isEmpty(),
        )
    }

    @Test
    fun `high risk tools resolve to HIGH regardless of registration`() {
        val highNames = listOf(
            "send_email", "open_url", "open_maps",
            "toggle_wifi", "toggle_bluetooth", "toggle_flashlight",
            "set_brightness", "set_volume", "set_alarm", "set_timer",
            "automation_workflow", "virtual_screen", "virtual_screen_input",
            "screen_read", "screen_current_app", "screen_tap", "screen_tap_text",
            "screen_swipe", "screen_pinch", "screen_swipe_path", "screen_input", "screen_launch_app",
            "app_force_stop", "app_clear_data", "app_uninstall",
            "settings_get", "settings_put", "network_toggle", "am_start",
            "list_packages", "logcat_tail", "input_inject",
            "workspace_write", "workspace_delete", "workspace_mkdir", "workspace_move",
            "pin_memory", "unpin_memory", "make_phone_call",
        )
        for (name in highNames) {
            assertEquals("$name debe ser analizado como HIGH", ToolRiskLevel.HIGH, ToolPermissionResolver.riskLevelFor(name))
        }
    }

    @Test
    fun `safe and normal tools keep their classification`() {
        assertEquals(ToolRiskLevel.SAFE, ToolPermissionResolver.riskLevelFor("web_search"))
        assertEquals(ToolRiskLevel.SAFE, ToolPermissionResolver.riskLevelFor("read_file"))
        assertEquals(ToolRiskLevel.SAFE, ToolPermissionResolver.riskLevelFor("get_current_time"))
        assertEquals(ToolRiskLevel.NORMAL, ToolPermissionResolver.riskLevelFor("write_file"))
        assertEquals(ToolRiskLevel.NORMAL, ToolPermissionResolver.riskLevelFor("notify"))
        assertEquals(ToolRiskLevel.NORMAL, ToolPermissionResolver.riskLevelFor("schedule_reminder"))
    }
}
