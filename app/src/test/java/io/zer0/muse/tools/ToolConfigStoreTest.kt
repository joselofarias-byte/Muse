package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Prueba de almacenamiento de políticas de aprobación de herramientas.
 *
 * Contratos clave:**No configurado ≠ El usuario permite explícitamente**. La capa de datos debe poder distinguir entre estos dos estados,
 * De lo contrario, la ruta de aprobación principal considerará "no configurado" como "el usuario ha sido liberado".ASK/STRICT modo
 * Las aprobaciones debidas según el nivel de riesgo se omitirán por completo (defecto histórico).
 *
 * DataStore Compartido dentro del mismo proceso de prueba, por lo que cada caso de uso utiliza un nombre de herramienta diferente para garantizar el aislamiento.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolConfigStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val store get() = ToolConfigStore(context)

    @Test
    fun unconfiguredToolHasNoExplicitPolicy() = runBlocking {
        assertNull(store.getConfiguredPolicy("probe_unconfigured"))
        // Los descriptores de acceso compatibles aún devuelven la semántica de "versión predeterminada", pero la ruta principal de aprobación no debería usarla para tomar decisiones.
        assertEquals(ToolApprovalPolicy.ALWAYS_ALLOW, store.getPolicy("probe_unconfigured"))
        assertEquals(ToolApprovalState.Auto, store.resolveApprovalState("probe_unconfigured"))
    }

    @Test
    fun explicitAllowIsStoredAndDistinguishableFromUnset() = runBlocking {
        store.setPolicy("probe_explicit_allow", ToolApprovalPolicy.ALWAYS_ALLOW)

        assertEquals(ToolApprovalPolicy.ALWAYS_ALLOW, store.getConfiguredPolicy("probe_explicit_allow"))
        assertNull(store.getConfiguredPolicy("probe_still_unset"))
    }

    @Test
    fun denyAndAskPoliciesRoundTrip() = runBlocking {
        store.setPolicy("probe_deny", ToolApprovalPolicy.ALWAYS_DENY)
        store.setPolicy("probe_ask", ToolApprovalPolicy.ASK_EVERY_TIME)

        assertEquals(ToolApprovalPolicy.ALWAYS_DENY, store.getConfiguredPolicy("probe_deny"))
        assertEquals(ToolApprovalPolicy.ASK_EVERY_TIME, store.getConfiguredPolicy("probe_ask"))
        assertEquals(
            ToolApprovalState.Denied("Tool disabled by user").toString(),
            store.resolveApprovalState("probe_deny").toString(),
        )
        assertEquals(ToolApprovalState.Pending, store.resolveApprovalState("probe_ask"))
    }

    @Test
    fun unconfiguredToolStillRequiresApprovalUnderAskMode() = runBlocking {
        // Contrato de cableado para ruta de producción: no configurado → null → Determinador por modo+Determinado por el nivel de riesgo.
        val policy = store.getConfiguredPolicy("probe_ask_high")
        assertEquals(
            ToolApprovalState.Pending,
            ToolPermissionResolver.resolve(
                toolName = "probe_ask_high",
                risk = ToolRiskLevel.HIGH,
                mode = SessionPermissionMode.ASK,
                perToolPolicy = policy,
            ),
        )
        assertEquals(
            ToolApprovalState.Pending,
            ToolPermissionResolver.resolve(
                toolName = "probe_ask_high",
                risk = ToolRiskLevel.NORMAL,
                mode = SessionPermissionMode.ASK,
                perToolPolicy = policy,
            ),
        )
    }

    @Test
    fun explicitAllowStillBypassesApprovalInAskMode() = runBlocking {
        // Los usuarios deben continuar sin molestar después de hacer clic en "Permitir siempre". Esta semántica no puede ser destruida por la solución anterior.
        store.setPolicy("probe_allow_bypass", ToolApprovalPolicy.ALWAYS_ALLOW)
        val policy = store.getConfiguredPolicy("probe_allow_bypass")

        assertEquals(
            ToolApprovalState.Auto,
            ToolPermissionResolver.resolve(
                toolName = "probe_allow_bypass",
                risk = ToolRiskLevel.HIGH,
                mode = SessionPermissionMode.ASK,
                perToolPolicy = policy,
            ),
        )
    }
}
