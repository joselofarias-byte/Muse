package io.zer0.muse.automation.capability

import android.os.Build
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.core.PermissionLevel
import io.zer0.muse.automation.executors.RootExecutor
import io.zer0.muse.hook.PromptContext
import io.zer0.muse.tools.system.ShizukuAuthorizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [DeviceCapabilityProfile] / [DeviceCapabilityHook] Pruebas de orientación.
 *
 * Bloquea dos cosas:
 *  1. Reflexión de representación de archivos**cierto**Disponibilidad del canal (qué capa está disponible, si se pueden tomar capturas de pantalla, la capa más alta),
 *     No interprete "sin autoridad" como "la capacidad no existe", ni afirme falsamente que está disponible;
 *  2. Hook Límites de comportamiento: sin inyección de subagente, sin excepción cuando falla la detección y sin pérdida de archivo completo.
 *
 * [RootExecutor]/[ShizukuAuthorizer] Utilice inyección simulada (consulte los parámetros de construcción inyectables de AutomationManager),
 * En realidad, no ejecutará su/bind Shizuku.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceCapabilityProfileTest {

    /**
     * Asigne directamente el estado del permiso al administrador.
     *
     * Hook La prueba se centra en "cómo renderizar después de obtener el estado", en lugar de detectar si se puede ejecutar en el entorno de prueba.
     * (Robolectric El servicio de accesibilidad siempre estará deshabilitado, lo que convertirá la afirmación en una implementación oculta).
     */
    private fun managerWithState(accessibility: Boolean, shell: Boolean, root: Boolean): AutomationManager {
        val state = AutomationManager.PermissionState(
            accessibilityEnabled = accessibility,
            shellEnabled = shell,
            rootEnabled = root,
            shizukuState = if (shell) {
                ShizukuAuthorizer.ShizukuState.READY
            } else {
                ShizukuAuthorizer.ShizukuState.NOT_INSTALLED
            },
            shizukuMessage = "test",
        )
        return mockk<AutomationManager>().apply {
            coEvery { refreshPermissions() } returns state
            every { permissionState } returns MutableStateFlow(state)
        }
    }

    private fun promptContext(forSubagent: Boolean = false) = PromptContext(
        assistantId = "a1",
        sessionId = "s1",
        locale = "zh",
        forSubagent = forSubagent,
    )

    // ── Propiedades derivadas de archivos ────────────────────────────────────────────

    @Test
    fun `no channel means no control capability and no screenshot`() {
        val profile = DeviceCapabilityProfile()
        assertEquals(PermissionLevel.NONE, profile.highestLevel)
        assertFalse(profile.hasControlChannel)
        assertFalse(profile.canScreenshot)
    }

    @Test
    fun `accessibility screenshot follows Android API capability`() {
        val profile = DeviceCapabilityProfile.fromFlags(accessibility = true, shizuku = false, root = false)
        assertEquals(PermissionLevel.ACCESSIBILITY, profile.highestLevel)
        assertTrue(profile.hasControlChannel)
        assertEquals(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE, profile.accessibilityScreenshot)
        assertEquals(profile.accessibilityScreenshot, profile.canScreenshot)
    }

    @Test
    fun `shizuku enables screenshot and outranks accessibility`() {
        val profile = DeviceCapabilityProfile.fromFlags(accessibility = true, shizuku = true, root = false)
        assertEquals(PermissionLevel.SHELL, profile.highestLevel)
        assertTrue(profile.canScreenshot)
    }

    @Test
    fun `root is the highest level`() {
        val profile = DeviceCapabilityProfile.fromFlags(accessibility = false, shizuku = true, root = true)
        assertEquals(PermissionLevel.ROOT, profile.highestLevel)
        assertTrue(profile.canScreenshot)
    }

    // ── Representación orientada al modelo ──────────────────────────────────────────

    @Test
    fun `rendered profile states each channel truthfully`() {
        val text = DeviceCapabilityProfile.fromFlags(
            accessibility = true,
            shizuku = false,
            root = false,
            virtualDisplay = false,
            nodeRuntime = true,
        ).renderForModel()

        assertTrue(text.contains("Perfil de capacidad del dispositivo"))
        assertTrue(text.contains("Accesibilidad(leer pantalla/gesto/entrada): Disponible"))
        assertTrue(text.contains("Shell canal(Shizuku/adb: input/screencap/pm/am/settings): No disponible"))
        assertTrue(text.contains("Root(su: Lectura y escritura de partición del sistema/Control total): No disponible"))
        assertTrue(text.contains("Capacidad de captura de pantalla: No disponible"))
        assertTrue(text.contains("Tiempo de ejecución de nodo incorporado(Ejecutar script/Procesamiento de datos/MCP stdio): Disponible"))
        assertTrue(text.contains("Nivel más alto de autoridad actual: Accesibilidad"))
    }

    @Test
    fun `rendered profile warns when no control channel exists`() {
        val text = DeviceCapabilityProfile().renderForModel()
        assertTrue(text.contains("Nivel más alto de autoridad actual: Ninguno (solo puede realizar operaciones dentro de la aplicación, no puede controlar otras aplicaciones)"))
        assertTrue(text.contains("Capacidad de captura de pantalla: No disponible"))
    }

    @Test
    fun `operating guide covers channel selection verification and escalation`() {
        val guide = DeviceCapabilityProfile().renderOperatingGuide()
        // Detectar primero antes de actuar
        assertTrue(guide, guide.contains("Confirma tu habilidad antes de actuar."))
        // Seleccione el canal de acuerdo con el nivel suficiente más bajo (evite tocar Root tan pronto como comience)
        assertTrue(guide, guide.contains("Seleccione el canal según el nivel más bajo suficiente"))
        // Las herramientas de la capa de aplicación no requieren autorización automatizada → Los procedimientos deben estar escritos; de lo contrario, el modelo omitirá la capa menos problemática.
        assertTrue(guide, guide.contains("app_list"))
        assertTrue(guide, guide.contains("app_launch"))
        assertTrue(guide, guide.contains("app_settings"))
        assertTrue(guide, guide.contains("app_force_stop"))
        // Cada paso de la verificación
        assertTrue(guide, guide.contains("Verifica cada paso"))
        // Repetir el proceso de escritura del guión.
        assertTrue(guide, guide.contains("Guión primero"))
        // Falta de autoridad para decir la verdad.
        assertTrue(guide, guide.contains("Si te falta permiso, di la verdad."))
        assertTrue(guide, guide.contains("display=auto"))
        assertTrue(guide, guide.contains("Esta pantalla virtual exclusiva"))
    }

    // ── Hook límites de comportamiento ──────────────────────────────────────────

    @Test
    fun `hook injects profile and guide for a normal session`() = runTest {
        val hook = DeviceCapabilityHook(
            automationManager = managerWithState(accessibility = true, shell = false, root = false),
            nodeRuntimeProbe = { true },
        )
        val injected = hook.afterComposeSystemPrompt(promptContext())

        assertTrue(injected, injected.contains("Perfil de capacidad del dispositivo"))
        assertTrue(injected, injected.contains("Procedimientos de toma de decisiones para el funcionamiento de teléfonos celulares."))
        assertTrue(injected, injected.contains("Nivel más alto de autoridad actual: Accesibilidad"))
        assertTrue(injected, injected.contains("Tiempo de ejecución de nodo incorporado(Ejecutar script/Procesamiento de datos/MCP stdio): Disponible"))
    }

    @Test
    fun `hook stays silent for subagents`() = runTest {
        val hook = DeviceCapabilityHook(
            automationManager = managerWithState(accessibility = true, shell = true, root = true),
        )
        assertEquals("", hook.afterComposeSystemPrompt(promptContext(forSubagent = true)))
    }

    @Test
    fun `hook reports root when all three channels are available`() = runTest {
        val hook = DeviceCapabilityHook(
            automationManager = managerWithState(accessibility = true, shell = true, root = true),
            virtualDisplayProbe = { true },
        )
        val injected = hook.afterComposeSystemPrompt(promptContext())
        assertTrue(injected.contains("Nivel más alto de autoridad actual: Root"))
        assertTrue(injected.contains("Capacidad de captura de pantalla: Disponible"))
        assertTrue(injected.contains("pantalla virtual(Inicie la aplicación en una pantalla separada,No perturbar la interfaz actual del usuario.): Disponible"))
    }

    @Test
    fun `hook never throws even when probes fail`() = runTest {
        // Tanto la pantalla virtual como la detección de nodos generan excepciones: el archivo aún debe renderizarse (la capacidad está marcada como no disponible) y no debe lanzarse hacia arriba.
        val hook = DeviceCapabilityHook(
            automationManager = managerWithState(accessibility = false, shell = false, root = false),
            virtualDisplayProbe = { error("boom") },
            nodeRuntimeProbe = { error("boom") },
        )
        val injected = hook.afterComposeSystemPrompt(promptContext())
        assertTrue(injected.contains("Perfil de capacidad del dispositivo"))
        assertTrue(injected.contains("pantalla virtual(Inicie la aplicación en una pantalla separada,No perturbar la interfaz actual del usuario.): No disponible"))
    }

    @Test
    fun `hook id and priority keep it ahead of worldbook`() {
        val hook = DeviceCapabilityHook(
            automationManager = managerWithState(accessibility = false, shell = false, root = false),
        )
        assertEquals("device_capability_profile", hook.id)
        assertTrue("Los archivos del dispositivo deben preceder Worldbook(60) inyectar", hook.priority > 60)
    }
}
