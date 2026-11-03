package io.zer0.muse.data.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.tools.script.ToolDeclaration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

/**
 * Complemento de autoescritura del asistente (borrador sin firmar) → Firma del autor nativo → prueba de límites de seguridad habilitada).
 *
 * Bloquee cuatro tipos de regresión:
 *  1. Los borradores nunca son ejecutables: deshabilitados, no confirmados,loadVerifiedPlugin/loadVerifiedFunction Todos son nulos;
 *  2. La entrada ilegal se rechaza antes de escribir en el disco, sin dejar ningún registro semiacabado ni directorio de complementos;
 *  3. La firma debe ser VALID_TRUSTED/habilitada después de habilitarla/Cargable, y la huella digital del emisor es igual a la huella digital de la clave pública del autor local;
 *  4. Negarse a firmar después de que el proyecto haya sido modificado; la clave del autor local solo se genera una vez y la huella digital es estable;
 *     Los borradores sin firmar no se pueden ejecutar independientemente del orden en que se soliciten.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginAuthoringTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val authorId = LocalAuthorKeyStore.PUBLISHER_ID

    @Test
    fun authoredDraftIsDisabledUnconfirmedAndNotExecutable() = runBlocking {
        val env = env()
        val request = request(capabilities = listOf("resource.read"))

        val result = env.manager.createAuthoredDraft(request)

        assertTrue("draft failed: ${result.exceptionOrNull()}", result.isSuccess)
        val draft = result.getOrThrow()
        assertFalse(draft.enabled)
        assertFalse(draft.installationConfirmed)
        assertEquals(PluginSecurityGate.SignatureStatus.UNSIGNED, draft.signatureStatus)
        assertEquals("", draft.publisherId)
        // El borrador está en el mercado, pero la vista de ejecución y el código de entrada no están disponibles.
        assertTrue(File(context.filesDir, "plugins/${request.id}/main.js").isFile)
        assertNull(env.manager.loadVerifiedPlugin(request.id))
        assertNull(env.manager.loadVerifiedFunction(request.id, "hello"))
        assertNull(env.manager.loadEntryCode(request.id))
        // Los borradores sin firmar no escriben la raíz de la confianza,manifest En él tampoco puede aparecer el sobre de firma.
        assertTrue(env.trustStore.list().isEmpty())
        val manifestText = File(context.filesDir, "plugins/${request.id}/manifest.json").readText()
        assertFalse("No se puede firmar el borrador del manifiesto", manifestText.contains("\"signature\""))
    }

    @Test
    fun invalidRequestsAreRejectedWithoutPartialState() = runBlocking {
        val env = env()
        val cases = listOf(
            "id ilegal" to request(id = "Bad_Id"),
            "El nombre está vacío." to request(name = "  "),
            "Versión ilegal" to request(version = "1.0"),
            "código vacío" to request(code = "   "),
            "El código excede el límite" to request(code = "var x = 1;" + "a".repeat(PluginAuthoringRules.MAX_CODE_BYTES)),
            "la función no existe" to request(
                code = "var x = 1;",
                tools = listOf(tool(functionName = "missingFunction")),
            ),
            "El nombre de la herramienta está vacío" to request(tools = emptyList()),
            "El nombre de la herramienta es ilegal." to request(tools = listOf(tool(name = "bad tool!"))),
            "Nombre de función ilegal" to request(tools = listOf(tool(functionName = "1bad"))),
            "Nombre de herramienta duplicado" to request(
                tools = listOf(tool(name = "hello", functionName = "hello"), tool(name = "hello", functionName = "hello")),
            ),
            // v2.x(Versión Sandbox): la habilidad ya no se rechazará según la lista blanca y se cambiará para verificar que el formato del nombre sea ilegal.
            "El formato del nombre de la habilidad es ilegal." to request(capabilities = listOf("Bad Cap!")),
        )

        cases.forEach { (label, request) ->
            val result = env.manager.createAuthoredDraft(request)
            assertTrue("$label debe fallar", result.isFailure)
            assertTrue(
                "$label Se debe dar una razón legible por humanos",
                result.exceptionOrNull()?.message?.isNotBlank() == true,
            )
            assertNull("$label No se deben dejar entradas de registro.", env.manager.findPlugin(request.id))
            assertFalse(
                "$label No debe quedar ningún directorio de complementos",
                File(context.filesDir, "plugins/${request.id}").exists(),
            )
        }
        assertTrue(env.manager.list().isEmpty())
    }

    @Test
    fun signAndEnableDraftPublishesTrustedExecutablePlugin() = runBlocking {
        val env = env()
        val created = env.manager.createAuthoredDraft(request()).getOrThrow()
        assertNull(env.manager.loadVerifiedPlugin(created.id))

        val signed = env.manager.signAndEnableDraft(created.id)

        assertTrue("sign failed: ${signed.exceptionOrNull()}", signed.isSuccess)
        val plugin = requireNotNull(env.manager.findPlugin(created.id))
        assertEquals(PluginSecurityGate.SignatureStatus.VALID_TRUSTED, plugin.signatureStatus)
        assertTrue(plugin.enabled)
        assertTrue(plugin.installationConfirmed)
        assertEquals(authorId, plugin.publisherId)
        assertEquals(env.keyStore.fingerprint().getOrThrow(), plugin.publisherKeyFingerprint)

        val verified = requireNotNull(env.manager.loadVerifiedPlugin(created.id))
        assertTrue(verified.entryCode.contains("function hello"))
        assertNotNull(env.manager.loadVerifiedFunction(created.id, "hello"))
        assertNull(env.manager.loadVerifiedFunction(created.id, "removedFunction"))

        // La confirmación del usuario significa confiar en la clave pública del autor local: la raíz de confianza debe ser la misma clave pública.
        val trusted = requireNotNull(env.trustStore.find(authorId))
        assertEquals(env.keyStore.publicKeyBase64().getOrThrow(), trusted.publicKey)
    }

    @Test
    fun tamperedDraftIsRejectedBeforeSigning() = runBlocking {
        val env = env()
        env.manager.createAuthoredDraft(request()).getOrThrow()
        File(context.filesDir, "plugins/assistant-tool/main.js")
            .writeText("function hello() { return 'tampered'; }")

        val result = env.manager.signAndEnableDraft("assistant-tool")

        assertTrue(result.isFailure)
        val reason = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("El motivo debe indicar que el contenido ha sido modificado.: $reason", reason.contains("se modificó antes de firmar"))
        val plugin = requireNotNull(env.manager.findPlugin("assistant-tool"))
        assertEquals(PluginSecurityGate.SignatureStatus.UNSIGNED, plugin.signatureStatus)
        assertFalse(plugin.installationConfirmed)
        assertFalse(plugin.enabled)
        assertNull(env.manager.loadVerifiedPlugin("assistant-tool"))
        // La firma se produce después de la revisión; la ruta fallida no debe escribir en la raíz de confianza.
        assertTrue(env.trustStore.list().isEmpty())
    }

    @Test
    fun localAuthorKeyIsGeneratedOnceAndFingerprintIsStable() {
        val keyFile = tmpFile("author_key", ".json")
        val first = LocalAuthorKeyStore(keyFile)
        val publicKey = first.publicKeyBase64().getOrThrow()
        val fingerprint = first.fingerprint().getOrThrow()
        assertTrue(keyFile.isFile)

        val second = LocalAuthorKeyStore(keyFile)
        assertEquals(publicKey, second.publicKeyBase64().getOrThrow())
        assertEquals(fingerprint, second.fingerprint().getOrThrow())
        assertEquals(
            PluginSecurityGate.publicKeyFingerprint(Base64.getDecoder().decode(publicKey)),
            fingerprint,
        )
        keyFile.delete()
        Unit
    }

    @Test
    fun corruptedAuthorKeyFailsClosedWithReadableReason() {
        val keyFile = tmpFile("author_key", ".json")
        keyFile.writeText("{not-json")

        val result = LocalAuthorKeyStore(keyFile).publicKeyBase64()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.isNotBlank() == true)
        keyFile.delete()
        Unit
    }

    @Test
    fun unsignedDraftCannotExecuteRegardlessOfCallOrder() = runBlocking {
        val env = env()
        env.manager.createAuthoredDraft(request()).getOrThrow()

        // Carga directa, habilitación, confirmación, carga nuevamente: no se puede producir ninguna vista ejecutable en ningún orden.
        assertNull(env.manager.loadEntryCode("assistant-tool"))
        env.manager.setEnabled("assistant-tool", true)
        assertFalse(requireNotNull(env.manager.findPlugin("assistant-tool")).enabled)
        assertNull(env.manager.loadVerifiedPlugin("assistant-tool"))

        assertTrue(env.manager.confirmInstallation("assistant-tool").isFailure)
        assertEquals(
            PluginSecurityGate.SignatureStatus.UNSIGNED,
            requireNotNull(env.manager.findPlugin("assistant-tool")).signatureStatus,
        )
        assertFalse(requireNotNull(env.manager.findPlugin("assistant-tool")).installationConfirmed)
        assertNull(env.manager.loadVerifiedPlugin("assistant-tool"))
        assertNull(env.manager.loadVerifiedFunction("assistant-tool", "hello"))
        assertTrue(env.trustStore.list().isEmpty())
    }

    // ── Andamio de prueba ──────────────────────────────────────────────────────────

    private data class Env(
        val manager: PluginManager,
        val trustStore: PluginTrustStore,
        val keyStore: LocalAuthorKeyStore,
    )

    private fun env(appVersion: String = "1.0.89"): Env {
        val keyStore = LocalAuthorKeyStore(tmpFile("author_key", ".json"))
        val trustStore = PluginTrustStore(tmpFile("trust", ".json"))
        val manager = PluginManager(
            context = context,
            skillRepository = skillRepoMock(),
            trustStore = trustStore,
            appVersionName = appVersion,
            localAuthorKeyStore = keyStore,
        )
        return Env(manager, trustStore, keyStore)
    }

    private fun skillRepoMock(): SkillRepository = mockk<SkillRepository>(relaxed = true).also { repo ->
        coEvery { repo.getById(any()) } returns null
    }

    private fun tmpFile(prefix: String, suffix: String): File =
        File(context.cacheDir, "${prefix}_${System.nanoTime()}_${(0..9999).random()}$suffix")

    private fun request(
        id: String = "assistant-tool",
        name: String = "herramienta asistente",
        description: String = "Complemento de ejemplo redactado por un asistente",
        version: String = "1.0.0",
        code: String = DEFAULT_CODE,
        tools: List<ToolDeclaration> = listOf(tool()),
        capabilities: List<String> = emptyList(),
    ): AuthoredPluginRequest = AuthoredPluginRequest(
        id = id,
        name = name,
        description = description,
        version = version,
        code = code,
        tools = tools,
        capabilities = capabilities,
    )

    private fun tool(name: String = "hello", functionName: String = "hello"): ToolDeclaration = ToolDeclaration(
        name = name,
        description = "Herramientas de muestra",
        parametersJson = "{}",
        requiredJson = "[]",
        functionName = functionName,
    )

    private companion object {
        val DEFAULT_CODE = """
            function hello(args) {
              return "hello " + (args.name || "");
            }
        """.trimIndent()
    }
}
