package io.zer0.muse.automation.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.zer0.muse.automation.executors.RootExecutor
import io.zer0.muse.automation.executors.RootRequestFailure
import io.zer0.muse.automation.executors.RootRequestResult
import io.zer0.muse.tools.system.ShizukuAuthorizer
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
 * [AutomationManager.requestRoot] prueba de orientación —— UI Valor de retorno dependiente y ruta de actualización de estado.
 *
 * [RootExecutor] Inyectar con simulacro(Ver los parámetros del constructor inyectable del administrador.),Por lo tanto, en realidad no se ejecutará. su:
 * Los motivos de fallo de bloqueo, como "sin autorización/tiempo de espera de autorización", se pueden devolver a la página tal como está,Y el estado del permiso debe volver a detectarse después de la solicitud.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutomationManagerRootRequestTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun shizukuMock(): ShizukuAuthorizer = mockk<ShizukuAuthorizer>().apply {
        coEvery { diagnose() } returns ShizukuAuthorizer.ShizukuStatus(
            ShizukuAuthorizer.ShizukuState.NOT_INSTALLED,
            "No instalado Shizuku",
        )
    }

    private fun manager(root: RootExecutor, shizuku: ShizukuAuthorizer = shizukuMock()): AutomationManager = AutomationManager(
        context = context,
        shizukuAuthorizer = shizuku,
        rootExecutor = root,
    )

    @Test
    fun `request without su binary returns no su reason and keeps root disabled`() = runTest {
        val root = mockk<RootExecutor>()
        coEvery { root.requestRootAccess() } returns RootRequestResult.failed(RootRequestFailure.NO_SU_BINARY)
        coEvery { root.isAvailable() } returns false
        val manager = manager(root)

        val result = manager.requestRoot()

        assertFalse(result.granted)
        assertEquals(RootRequestFailure.NO_SU_BINARY, result.failure)
        assertFalse(manager.permissionState.value.rootEnabled)
        // Debe volver a sondear después de la solicitud,en lugar de utilizar el antiguo estado
        coVerify(exactly = 1) { root.requestRootAccess() }
        coVerify(atLeast = 1) { root.isAvailable() }
    }

    @Test
    fun `timed out request surfaces timeout reason and refreshes state`() = runTest {
        val root = mockk<RootExecutor>()
        coEvery { root.requestRootAccess() } returns RootRequestResult.failed(RootRequestFailure.TIMEOUT)
        coEvery { root.isAvailable() } returns false
        val manager = manager(root)

        val result = manager.requestRoot()

        assertFalse(result.granted)
        assertEquals(RootRequestFailure.TIMEOUT, result.failure)
        assertFalse(manager.permissionState.value.rootEnabled)
        coVerify(exactly = 1) { root.isAvailable() }
    }

    @Test
    fun `granted request refreshes permission state to root enabled`() = runTest {
        val root = mockk<RootExecutor>()
        coEvery { root.requestRootAccess() } returns RootRequestResult.Granted
        coEvery { root.isAvailable() } returns true
        val manager = manager(root)

        // antes de la solicitud:No hay estado inicial root
        assertFalse(manager.permissionState.value.rootEnabled)

        val result = manager.requestRoot()

        assertTrue(result.granted)
        assertNull(result.failure)
        // Después de solicitar al administrador automáticamente refresh,La tarjeta se vuelve verde en consecuencia.
        assertTrue(manager.permissionState.value.rootEnabled)
        coVerify(exactly = 1) { root.requestRootAccess() }
    }

    @Test
    fun `denied request keeps previously enabled root off after refresh`() = runTest {
        val root = mockk<RootExecutor>()
        coEvery { root.requestRootAccess() } returns RootRequestResult.failed(RootRequestFailure.DENIED)
        coEvery { root.isAvailable() } returns false
        val manager = manager(root)

        val result = manager.requestRoot()

        assertEquals(RootRequestFailure.DENIED, result.failure)
        assertFalse(manager.permissionState.value.rootEnabled)
        assertFalse(manager.permissionState.value.anyEnabled)
    }
}
