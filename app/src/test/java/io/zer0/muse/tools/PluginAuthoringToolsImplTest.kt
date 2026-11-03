package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.muse.data.plugin.LocalAuthorKeyStore
import io.zer0.muse.data.plugin.PluginManager
import io.zer0.muse.data.plugin.PluginTrustStore
import io.zer0.muse.data.skill.SkillRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * author_plugin Pruebas de capa de herramientas: análisis de parámetros, falla estructurada, borrador de semántica y niveles de aprobación.
 *
 * límite de la capa de datos ver `data/plugin/PluginAuthoringTest`; Aquí sólo se bloquea el "texto de retorno que el modelo puede ver".
 * y "La herramienta entra en aprobación con ALTO riesgo".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginAuthoringToolsImplTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun authorPluginIsRegisteredAsBuiltInSkillAndRoutable() {
        val skills = SkillExecutor.BUILT_IN_SKILLS
        val definition = skills.firstOrNull { it.id == "author_plugin" }

        assertTrue("author_plugin Debe estar incorporado skill", definition != null)
        assertEquals("author_plugin", definition?.implementationKotlin)
        assertTrue("author_plugin Debe ser enrutable por SkillExecutor", "author_plugin" in SkillExecutor.ROUTABLE_SKILL_IMPL)
    }

    @Test
    fun authorPluginRequiresApprovalInAskAndStrictModes() {
        // El borrador en sí no es ejecutable, pero el usuario debe aprobar primero "permitir que el modelo escriba el código que será firmado por el usuario".
        assertEquals(
            ToolApprovalState.Pending,
            ToolPermissionResolver.resolve("author_plugin", null, SessionPermissionMode.ASK, null),
        )
        assertEquals(
            ToolApprovalState.Pending,
            ToolPermissionResolver.resolve("author_plugin", null, SessionPermissionMode.STRICT, null),
        )
    }

    @Test
    fun malformedToolsReturnStructuredError() = runBlocking {
        val impl = PluginAuthoringToolsImpl(context, manager())

        val result = impl.authorPlugin(
            mapOf(
                "id" to "authored-tool",
                "name" to "herramienta asistente",
                "version" to "1.0.0",
                "code" to "function hello() { return 'hi'; }",
                "tools" to "not-json",
            ),
        )

        assertTrue("El fracaso debe regresar estructurado. JSON: $result", result.contains("\"error\""))
    }

    @Test
    fun validationFailureReturnsReadableStructuredError() = runBlocking {
        val impl = PluginAuthoringToolsImpl(context, manager())

        val result = impl.authorPlugin(
            mapOf(
                "id" to "authored-tool",
                "name" to "herramienta asistente",
                "version" to "not-semver",
                "code" to "function hello() { return 'hi'; }",
                "tools" to TOOLS_JSON,
            ),
        )

        assertTrue("El fracaso debe regresar estructurado. JSON: $result", result.contains("\"error\""))
        assertTrue("El motivo debe explicar el problema del número de versión.: $result", result.contains("versión semántica"))
    }

    @Test
    fun successfulDraftTellsModelItIsDisabledAndNeedsUserSigning() = runBlocking {
        val manager = manager()
        val impl = PluginAuthoringToolsImpl(context, manager)

        val result = impl.authorPlugin(
            mapOf(
                "id" to "authored-tool",
                "name" to "herramienta asistente",
                "description" to "redactado por asistente",
                "version" to "1.0.0",
                "code" to "function hello() { return 'hi'; }",
                "tools" to TOOLS_JSON,
            ),
        )

        // El texto devuelto al modelo debe indicar: borrador sin firmar, deshabilitado, que requiere firma del usuario y activación en la gestión de complementos.
        // Assert acepta redacción publicitaria tanto en chino como en inglés (Robolectric Zona predeterminada).
        assertTrue(result.contains("authored-tool"))
        assertTrue("Cabe señalar que el proyecto no está firmado.: $result", result.contains("sin firmar") || result.contains("Unsigned"))
        assertTrue(
            "Se debe pedir a los usuarios que firmen y se les debe habilitar: $result",
            result.contains("Firmar y habilitar") || result.contains("Sign and enable"),
        )
        val draft = requireNotNull(manager.findPlugin("authored-tool"))
        assertFalse(draft.enabled)
        assertFalse(draft.installationConfirmed)
        assertEquals(null, manager.loadVerifiedPlugin("authored-tool"))
    }

    @Test
    fun missingPluginManagerReturnsStructuredError() = runBlocking {
        val impl = PluginAuthoringToolsImpl(context, pluginManager = null)

        val result = impl.authorPlugin(mapOf("id" to "x", "code" to "function f() {}"))

        assertTrue(result.contains("\"error\""))
    }

    private fun manager(): PluginManager = PluginManager(
        context = context,
        skillRepository = mockk<SkillRepository>(relaxed = true).also { repo ->
            coEvery { repo.getById(any()) } returns null
        },
        trustStore = PluginTrustStore(File(context.cacheDir, "trust_${System.nanoTime()}.json")),
        appVersionName = "1.0.89",
        localAuthorKeyStore = LocalAuthorKeyStore(File(context.cacheDir, "author_key_${System.nanoTime()}.json")),
    )

    private companion object {
        const val TOOLS_JSON =
            """[{"name":"hello","description":"saluda","parametersJson":"{}","requiredJson":"[]","functionName":"hello"}]"""
    }
}
