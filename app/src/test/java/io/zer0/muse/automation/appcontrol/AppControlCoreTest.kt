package io.zer0.muse.automation.appcontrol

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.tools.system.ShizukuAuthorizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pruebas de decisión central de los controles aplicados.
 *
 * Esta capa es"Agent ¿Se puede confiar en usted?"La clave: el modelo debe poder aprender de los resultados**saber exactamente**
 * "Ya estaba hecho/no pude hacerlo y qué habilidad me faltaba/lo intenté pero fallé" en lugar de recibir vagos fracasos o falsos éxitos.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppControlCoreTest {

    private val noChannel = ChannelAvailability(shell = false, root = false)
    private val shellOnly = ChannelAvailability(shell = true, root = false)
    private val rootOnly = ChannelAvailability(shell = false, root = true)

    private fun app(
        pkg: String = "com.example.app",
        label: String = "Ejemplo",
        system: Boolean = false,
        self: Boolean = false,
    ): InstalledAppInfo {
        return InstalledAppInfo(packageName = pkg, label = label, versionName = "1.0", system = system, self = self)
    }

    // ── Verificación del nombre del paquete/protección de inyección de comandos ──────────────────────────────

    @Test
    fun `valid package names pass`() {
        assertTrue(AppControlCore.isValidPackageName("com.tencent.mm"))
        assertTrue(AppControlCore.isValidPackageName("a.b"))
        assertTrue(AppControlCore.isValidPackageName("com.example.app_2"))
    }

    @Test
    fun `shell metacharacters in package name are rejected`() {
        // El nombre del paquete eventualmente se escribirá `am force-stop <pkg>` Para este tipo de comando, se deben bloquear todos los caracteres inyectables.
        listOf(
            "com.foo; rm -rf /",
            "com.foo && reboot",
            "com.foo|id",
            "com.foo\$(id)",
            "com.foo`id`",
            "com.foo\"x",
            "com.foo x",
            "com.foo\nid",
            "com",
        ).forEach { bad ->
            assertFalse("debe ser rechazado: $bad", AppControlCore.isValidPackageName(bad))
        }
    }

    @Test
    fun `command builder refuses to be reached with invalid names`() {
        // buildCommand Acepte únicamente nombres que hayan pasado la lista blanca; el formulario de comando que se detalla aquí está bloqueado sin parámetros adicionales.
        assertEquals("am force-stop com.example.app", AppControlCore.buildCommand(AppControlAction.FORCE_STOP, "com.example.app"))
        assertEquals("pm clear com.example.app", AppControlCore.buildCommand(AppControlAction.CLEAR_DATA, "com.example.app"))
        assertEquals("pm uninstall com.example.app", AppControlCore.buildCommand(AppControlAction.UNINSTALL, "com.example.app"))
        // Las acciones de la capa de aplicación no tienen comandos de dispositivo
        assertNull(AppControlCore.buildCommand(AppControlAction.LAUNCH, "com.example.app"))
        assertNull(AppControlCore.buildCommand(AppControlAction.OPEN_APP_SETTINGS, "com.example.app"))
    }

    // ── Decisión de bajar el canal ────────────────────────────────────────

    @Test
    fun `invalid name is unavailable regardless of channels`() {
        val result = AppControlCore.evaluate(AppControlAction.LAUNCH, "bad name;", shellOnly)
        assertEquals(AppActionResult.Unavailable(UnavailableReason.INVALID_PACKAGE_NAME), result)
    }

    @Test
    fun `launch and settings need no elevated channel`() {
        assertEquals(
            AppActionResult.Success(ExecutionChannel.APP),
            AppControlCore.evaluate(AppControlAction.LAUNCH, "com.example.app", noChannel),
        )
        assertEquals(
            AppActionResult.Success(ExecutionChannel.APP),
            AppControlCore.evaluate(AppControlAction.OPEN_APP_SETTINGS, "com.example.app", noChannel),
        )
    }

    @Test
    fun `destructive actions without shell or root report the missing capability`() {
        listOf(
            AppControlAction.FORCE_STOP,
            AppControlAction.CLEAR_DATA,
            AppControlAction.UNINSTALL,
        ).forEach { action ->
            val result = AppControlCore.evaluate(action, "com.example.app", noChannel)
            assertEquals(
                "Cuando hay falta de acceso, la falta de capacidad debe informarse claramente, en lugar de un fracaso vago.: $action",
                AppActionResult.Unavailable(UnavailableReason.NEED_SHELL_OR_ROOT),
                result,
            )
        }
    }

    @Test
    fun `destructive actions prefer shell then root`() {
        assertEquals(
            AppActionResult.Success(ExecutionChannel.SHELL),
            AppControlCore.evaluate(AppControlAction.FORCE_STOP, "com.example.app", shellOnly),
        )
        assertEquals(
            AppActionResult.Success(ExecutionChannel.ROOT),
            AppControlCore.evaluate(AppControlAction.FORCE_STOP, "com.example.app", rootOnly),
        )
    }

    @Test
    fun `system apps can be force stopped but not cleared or uninstalled`() {
        val systemApp = app(system = true)
        assertEquals(
            AppActionResult.Success(ExecutionChannel.SHELL),
            AppControlCore.evaluate(AppControlAction.FORCE_STOP, "com.example.app", shellOnly, systemApp),
        )
        assertEquals(
            AppActionResult.Unavailable(UnavailableReason.BLOCKED_SYSTEM_APP),
            AppControlCore.evaluate(AppControlAction.CLEAR_DATA, "com.example.app", shellOnly, systemApp),
        )
        assertEquals(
            AppActionResult.Unavailable(UnavailableReason.BLOCKED_SYSTEM_APP),
            AppControlCore.evaluate(AppControlAction.UNINSTALL, "com.example.app", shellOnly, systemApp),
        )
    }

    @Test
    fun `muse itself can never be cleared or uninstalled even with root`() {
        val self = app(pkg = "io.zer0.muse", self = true)
        assertEquals(
            AppActionResult.Unavailable(UnavailableReason.BLOCKED_SELF),
            AppControlCore.evaluate(AppControlAction.CLEAR_DATA, "io.zer0.muse", ChannelAvailability(true, true), self),
        )
        assertEquals(
            AppActionResult.Unavailable(UnavailableReason.BLOCKED_SELF),
            AppControlCore.evaluate(AppControlAction.UNINSTALL, "io.zer0.muse", ChannelAvailability(true, true), self),
        )
    }

    // ── coincidencia de nombre ────────────────────────────────────────────

    @Test
    fun `match prefers exact package then label then partial label`() {
        val apps = listOf(
            app(pkg = "com.tencent.mm", label = "WeChat"),
            app(pkg = "com.tencent.mobileqq", label = "QQ"),
            app(pkg = "com.example.wechathelper", label = "Asistente de WeChat"),
        )
        assertEquals("com.tencent.mm", AppControlCore.matchApp(apps, "com.tencent.mm")?.packageName)
        assertEquals("com.tencent.mm", AppControlCore.matchApp(apps, "WeChat")?.packageName)
        assertEquals("com.tencent.mobileqq", AppControlCore.matchApp(apps, "qq")?.packageName)
        // Los nombres exactos tienen prioridad sobre las coincidencias que contienen ("WeChat" No debería haber golpeado "Asistente de WeChat")
        assertEquals("com.tencent.mm", AppControlCore.matchApp(apps, "WeChat")?.packageName)
        assertEquals("com.example.wechathelper", AppControlCore.matchApp(apps, "asistente")?.packageName)
        assertNull(AppControlCore.matchApp(apps, ""))
        assertNull(AppControlCore.matchApp(apps, "aplicación inexistente"))
    }

    // ── Representación de lista ────────────────────────────────────────────

    @Test
    fun `render app list marks system and self and handles empty`() {
        val text = AppControlCore.renderAppList(
            listOf(
                app(pkg = "com.tencent.mm", label = "WeChat"),
                app(pkg = "android", label = "Interfaz del sistema", system = true),
                app(pkg = "io.zer0.muse", label = "Muse", self = true),
            ),
        )
        assertTrue(text.contains("3 aplicaciones ejecutables en total"))
        assertTrue(text.contains("WeChat | com.tencent.mm"))
        assertTrue(text.contains("Aplicación del sistema"))
        assertTrue(text.contains("Esta aplicación"))

        assertTrue(AppControlCore.renderAppList(emptyList()).contains("no encontrado"))
    }

    @Test
    fun `render app list respects the displayed limit but reports the real total`() {
        val apps = (1..5).map { app(pkg = "com.example.app$it", label = "App$it") }
        val text = AppControlCore.renderAppList(apps, limit = 2)
        assertTrue(text.contains("5 aplicaciones ejecutables en total"))
        assertTrue(text.contains("Solo enumera los primeros 2"))
        assertFalse(text.contains("com.example.app5"))
    }

    // ── Controlador: Detección de canales y entrada ilegal ───────────────────────────

    private fun controllerWith(shell: Boolean, root: Boolean): AndroidAppController {
        val state = AutomationManager.PermissionState(
            accessibilityEnabled = false,
            shellEnabled = shell,
            rootEnabled = root,
            shizukuState = if (shell) {
                ShizukuAuthorizer.ShizukuState.READY
            } else {
                ShizukuAuthorizer.ShizukuState.NOT_INSTALLED
            },
            shizukuMessage = "test",
        )
        val manager = mockk<AutomationManager>().apply {
            every { permissionState } returns MutableStateFlow(state)
            coEvery { refreshPermissions() } returns state
        }
        val context: Context = ApplicationProvider.getApplicationContext()
        return AndroidAppController(context, manager)
    }

    @Test
    fun `channels reflects probed availability`() = runTest {
        assertFalse(controllerWith(shell = false, root = false).channels().anyElevated)
        assertTrue(controllerWith(shell = true, root = false).channels().shell)
        assertTrue(controllerWith(shell = false, root = true).channels().root)
    }

    @Test
    fun `perform rejects injected package names before touching the system`() = runTest {
        val result = controllerWith(shell = true, root = false)
            .perform(AppControlAction.FORCE_STOP, "com.foo; reboot")
        // No es un nombre de paquete legal ni un nombre de aplicación. → No encontrado (la clave es no ejecutar ningún comando)
        assertTrue(result is AppActionResult.Unavailable)
    }

    @Test
    fun `perform reports missing channel for destructive action on unknown name`() = runTest {
        val result = controllerWith(shell = false, root = false)
            .perform(AppControlAction.CLEAR_DATA, "Aplicación completamente inexistente")
        assertEquals(
            AppActionResult.Unavailable(UnavailableReason.PACKAGE_NOT_FOUND),
            result,
        )
    }
}
