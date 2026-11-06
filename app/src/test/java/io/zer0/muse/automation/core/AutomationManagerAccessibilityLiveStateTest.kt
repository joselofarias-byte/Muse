package io.zer0.muse.automation.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.muse.automation.executors.MuseAccessibilityService
import io.zer0.muse.automation.executors.RootExecutor
import io.zer0.muse.tools.system.ShizukuAuthorizer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.7 Volver: Servicios de Accesibilidad**conexión asíncrona**Finalmente, el estado del permiso debe reflejar la situación real.
 *
 * ## recurrente bug
 *
 * `AutomationManager.permissionState` es un valor almacenado en caché, sólo en [AutomationManager.refreshPermissions]
 * actualizado cuando se llama explícitamente (App inicio, regresando de la página de configuración, después de solicitar Root). Pero los servicios de accesibilidad están conectados de forma asincrónica:
 * Después de que el usuario enciende el interruptor en la configuración del sistema `MuseAccessibilityService.onServiceConnected` Simplemente ejecuta.
 * Si el servicio no está conectado al iniciar (caché a11y=false), y luego el servicio se conectó pero nadie lo actualizó.
 * El caché permanecerá en false —— Error de cálculo de verificación previa de la herramienta basado en esto“No autorizado”,
 * Sin embargo, cuando el asistente de permisos lee la configuración del sistema en tiempo real, muestra“Ya encendido”(Fenómenos medidos por los usuarios).
 *
 * ## comportamiento bloqueado
 *
 * [AutomationManager.liveSnapshot] cada llamada**Redetectar accesibilidad**(consulta ligera sincrónica),
 * Por lo tanto, el servicio devuelve verdadero inmediatamente después de conectarse y no depende del caché al inicio.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutomationManagerAccessibilityLiveStateTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        // Las instancias estáticas son globales y deben limpiarse entre pruebas para evitar contaminar otros casos de uso.
        io.zer0.muse.accessibility.MuseAccessibilityService.instance = null
    }

    private fun manager(): AutomationManager {
        val shizuku = mockk<ShizukuAuthorizer>(relaxed = true).apply {
            coEvery { diagnose() } returns ShizukuAuthorizer.ShizukuStatus(
                ShizukuAuthorizer.ShizukuState.NOT_INSTALLED,
                "No instalado Shizuku",
            )
        }
        val root = mockk<RootExecutor>(relaxed = true).apply {
            coEvery { isAvailable() } returns false
        }
        return AutomationManager(context = context, shizukuAuthorizer = shizuku, rootExecutor = root)
    }

    private fun fakeService(connected: Boolean): MuseAccessibilityService {
        // Establecer campo privado conectado con instancia real + reflexión:
        // AccessibilityService es una clase de marco de Android,mockk Inestabilidad del agente bajo Robolectric;
        // y isAccessibilityServiceEnabled() No abierto y no puede ser anulado por subclases anónimas.
        // Nota: Debe utilizar una subclase del módulo de la aplicación (automation.executors.MuseAccessibilityService),
        // Porque AccessibilityExecutor lee su instancia complementaria (haciéndolo en la instancia de la clase base as? conversión).
        val svc = MuseAccessibilityService()
        val field = io.zer0.muse.accessibility.MuseAccessibilityService::class.java.getDeclaredField("connected")
        field.isAccessible = true
        field.setBoolean(svc, connected)
        return svc
    }

    @Test
    fun `cached state stays false until refresh, but liveSnapshot reflects a late connection`() = runTest {
        val mgr = manager()

        // El servicio no está conectado al inicio → Caché detectado a11y=false
        mgr.refreshPermissions()
        assertFalse("Accesibilidad no conectada al inicio", mgr.permissionState.value.accessibilityEnabled)

        // Luego, el usuario activa la accesibilidad en la configuración del sistema. → Conexión asíncrona de servicio (instancia estática después de simular onServiceConnected)
        io.zer0.muse.accessibility.MuseAccessibilityService.instance = fakeService(connected = true)

        // Afirmación clave: el caché sigue siendo falso (bug root), pero la instantánea en tiempo real ya puede reflejar el estado real
        assertFalse("El caché no se actualiza automáticamente (este es el error)", mgr.permissionState.value.accessibilityEnabled)
        assertTrue("liveSnapshot Debe reflejar los servicios de accesibilidad conectados en tiempo real.", mgr.liveSnapshot().accessibilityEnabled)
    }

    @Test
    fun `liveSnapshot writes back to cached state for observers`() = runTest {
        val mgr = manager()
        mgr.refreshPermissions()
        io.zer0.muse.accessibility.MuseAccessibilityService.instance = fakeService(connected = true)

        mgr.liveSnapshot()

        // Los resultados de la detección en tiempo real deben volver a escribirse en la caché para mantener coherentes las IU que dependen del estado de permiso.
        assertTrue("El caché debe actualizarse sincrónicamente después de la detección en tiempo real.", mgr.permissionState.value.accessibilityEnabled)
    }

    @Test
    fun `liveSnapshot reflects disconnection`() = runTest {
        val mgr = manager()
        io.zer0.muse.accessibility.MuseAccessibilityService.instance = fakeService(connected = true)
        mgr.refreshPermissions()
        assertTrue("El caché al conectarse debe ser true", mgr.permissionState.value.accessibilityEnabled)

        // Servicio desconectado
        io.zer0.muse.accessibility.MuseAccessibilityService.instance = null

        assertFalse("Después de desconectar liveSnapshot se debe false", mgr.liveSnapshot().accessibilityEnabled)
    }

    @Test
    fun `liveSnapshot is cheap and does not require refresh`() = runTest {
        val mgr = manager()
        // No se ha llamado a refrescoPermissions, el caché es el valor predeterminado inicial (a11y=false)
        io.zer0.muse.accessibility.MuseAccessibilityService.instance = fakeService(connected = true)

        assertTrue(
            "Incluso si nunca se actualiza,liveSnapshot También debe reflejar directamente el estado del servicio.",
            mgr.liveSnapshot().accessibilityEnabled,
        )
    }
}
