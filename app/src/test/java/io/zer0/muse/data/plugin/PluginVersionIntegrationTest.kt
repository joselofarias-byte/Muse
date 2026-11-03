package io.zer0.muse.data.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.common.AppJson
import io.zer0.muse.data.skill.SkillRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pruebas de integración de estrategia de versión y rollback (siguiendo la ruta de instalación real, sin sustitutos).
 *
 * Hay cuatro cosas bloqueadas: la versión anterior se puede revertir después de la actualización, se rechaza la instalación de versión anterior pero se permite la reversión, se rechaza el reemplazo del editor,
 * Los borradores sin firmar no pueden anular las instalaciones firmadas; y la reversión debe fallar después de modificar las copias históricas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginVersionIntegrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val appVersion = "1.0.89"

    private fun skillRepoMock(): SkillRepository = mockk<SkillRepository>(relaxed = true).also { repo ->
        coEvery { repo.getById(any()) } returns null
    }

    private fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    /** Construir un [keyPair] Archivo de paquete de complemento realmente firmado. */
    private fun signedPackage(
        id: String,
        version: String,
        keyPair: KeyPair,
        publisherId: String = "publisher.test",
        minAppVersion: String = "1.0.0",
    ): File {
        val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val entryCode = "function hello() { return 'v$version'; }"
        val unsignedManifest = PluginManifest(
            id = id,
            name = "Versioned Plugin",
            version = version,
            minAppVersion = minAppVersion,
            entry = "main.js",
            capabilities = listOf("resource.read"),
            tools = listOf(
                io.zer0.muse.tools.script.ToolDeclaration(
                    name = "hello",
                    description = "say hello",
                    parametersJson = "{}",
                    requiredJson = "[]",
                    functionName = "hello",
                ),
            ),
            signature = PluginSignature(publisherId, publicKey, ""),
        )
        val unsignedPackage = PluginPackageLoader.LoadedPluginPackage(unsignedManifest, entryCode)
        val signature = Signature.getInstance(PluginSecurityGate.SIGNATURE_ALGORITHM).apply {
            initSign(keyPair.private)
            update(PluginSecurityGate.signaturePayload(unsignedPackage))
        }.sign()
        val manifest = unsignedManifest.copy(
            signature = unsignedManifest.signature!!.copy(
                signature = Base64.getEncoder().encodeToString(signature),
            ),
        )
        val file = File(context.cacheDir, "$id-$version-${System.nanoTime()}.muse-plugin")
        file.writeBytes(
            zip(
                manifestJson = AppJson.encodeToString(PluginManifest.serializer(), manifest),
                entryCode = entryCode,
            ),
        )
        return file
    }

    private fun unsignedPackage(id: String, version: String): File {
        val manifest = PluginManifest(
            id = id,
            name = "Draft Plugin",
            version = version,
            entry = "main.js",
            capabilities = listOf("resource.read"),
            tools = listOf(
                io.zer0.muse.tools.script.ToolDeclaration(
                    name = "hello",
                    description = "say hello",
                    parametersJson = "{}",
                    requiredJson = "[]",
                    functionName = "hello",
                ),
            ),
        )
        val file = File(context.cacheDir, "$id-$version-${System.nanoTime()}.muse-plugin")
        file.writeBytes(
            zip(
                manifestJson = AppJson.encodeToString(PluginManifest.serializer(), manifest),
                entryCode = "function hello() { return 'draft'; }",
            ),
        )
        return file
    }

    private fun zip(manifestJson: String, entryCode: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifestJson.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("main.js"))
            zip.write(entryCode.toByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun manager(trustFile: File): PluginManager = PluginManager(
        context = context,
        skillRepository = skillRepoMock(),
        trustStore = PluginTrustStore(trustFile),
        appVersionName = appVersion,
    )

    private suspend fun install(manager: PluginManager, file: File): Result<PluginManager.InstalledPlugin> {
        val preview = manager.reviewFromFile(file).getOrThrow().preview
        return manager.installConfirmedFromFile(file, preview, trustPublisher = true)
    }

    @Test
    fun upgradeKeepsPreviousVersionAvailableForRollback() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val manager = manager(trustFile)
        val publisher = newKeyPair()

        assertTrue(install(manager, signedPackage("rollback-plugin", "1.0.0", publisher)).isSuccess)
        assertTrue(install(manager, signedPackage("rollback-plugin", "1.1.0", publisher)).isSuccess)
        assertEquals("1.1.0", manager.findPlugin("rollback-plugin")?.version)

        val retained = manager.listRetainedVersions("rollback-plugin").map { it.version }
        assertTrue("Las versiones antiguas deben conservarse después de la actualización.: $retained", retained.contains("1.0.0"))
        assertTrue(retained.contains("1.1.0"))

        // Regresando a 1.0.0: nueva verificación + reemplazo atómico después de la comparación del resumen de contenido.
        val rolledBack = manager.rollbackTo("rollback-plugin", "1.0.0")
        assertTrue("rollback failed: ${rolledBack.exceptionOrNull()}", rolledBack.isSuccess)
        assertEquals("1.0.0", manager.findPlugin("rollback-plugin")?.version)
        assertTrue(manager.loadEntryCode("rollback-plugin")?.contains("'v1.0.0'") == true)
        trustFile.delete()
        Unit
    }

    @Test
    fun downgradeInstallIsRejected() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val manager = manager(trustFile)
        val publisher = newKeyPair()

        assertTrue(install(manager, signedPackage("downgrade-plugin", "2.0.0", publisher)).isSuccess)
        val downgraded = install(manager, signedPackage("downgrade-plugin", "1.0.0", publisher))

        assertTrue("Las instalaciones degradadas deben rechazarse", downgraded.isFailure)
        assertTrue(
            downgraded.exceptionOrNull()?.message.orEmpty().contains("Rebajar"),
        )
        assertEquals("2.0.0", manager.findPlugin("downgrade-plugin")?.version)
        trustFile.delete()
        Unit
    }

    @Test
    fun differentPublisherKeyCannotOverwriteInstalledPlugin() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val manager = manager(trustFile)

        assertTrue(install(manager, signedPackage("lineage-plugin", "1.0.0", newKeyPair())).isSuccess)
        val hijack = install(manager, signedPackage("lineage-plugin", "1.1.0", newKeyPair()))

        assertTrue("Los complementos con el mismo nombre que cambian las claves públicas deben rechazarse", hijack.isFailure)
        assertTrue(hijack.exceptionOrNull()?.message.orEmpty().contains("Emisor"))
        assertEquals("1.0.0", manager.findPlugin("lineage-plugin")?.version)
        trustFile.delete()
        Unit
    }

    @Test
    fun pluginRequiringNewerAppVersionIsRejected() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val manager = manager(trustFile)

        val tooNew = install(
            manager,
            signedPackage("future-plugin", "1.0.0", newKeyPair(), minAppVersion = "2.0.0"),
        )

        assertTrue(tooNew.isFailure)
        assertTrue(tooNew.exceptionOrNull()?.message.orEmpty().contains("App versión"))
        assertNull(manager.findPlugin("future-plugin"))
        trustFile.delete()
        Unit
    }

    @Test
    fun unsignedDraftCannotOverwriteSignedInstall() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val manager = manager(trustFile)
        assertTrue(install(manager, signedPackage("draft-guard", "1.0.0", newKeyPair())).isSuccess)

        val draftFile = unsignedPackage("draft-guard", "1.1.0")
        val draftPreview = manager.reviewFromFile(draftFile).getOrThrow().preview
        val draft = manager.installDraftFromFile(draftFile, draftPreview)

        assertTrue("Los borradores sin firmar no pueden sobrescribir las instalaciones firmadas", draft.isFailure)
        assertTrue(draft.exceptionOrNull()?.message.orEmpty().contains("sin firmar"))
        assertEquals(PluginSecurityGate.SignatureStatus.VALID_TRUSTED, manager.findPlugin("draft-guard")?.signatureStatus)
        trustFile.delete()
        Unit
    }

    @Test
    fun tamperedRetainedVersionIsRejectedOnRollback() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val manager = manager(trustFile)
        val publisher = newKeyPair()
        assertTrue(install(manager, signedPackage("tamper-plugin", "1.0.0", publisher)).isSuccess)
        assertTrue(install(manager, signedPackage("tamper-plugin", "1.1.0", publisher)).isSuccess)

        val retained = manager.listRetainedVersions("tamper-plugin").first { it.version == "1.0.0" }
        val entry = File(retained.directory, "main.js")
        assertTrue("La copia retenida debe contener el archivo de entrada.", entry.isFile)
        entry.writeText("function hello() { return 'tampered'; }")

        val rollback = manager.rollbackTo("tamper-plugin", "1.0.0")
        assertTrue("Las copias históricas que hayan sido modificadas deben negarse a ser revertidas.", rollback.isFailure)
        assertEquals("1.1.0", manager.findPlugin("tamper-plugin")?.version)
        trustFile.delete()
        Unit
    }

    @Test
    fun rollbackTargetMustExist() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val manager = manager(trustFile)
        assertTrue(install(manager, signedPackage("missing-target", "1.0.0", newKeyPair())).isSuccess)

        val rollback = manager.rollbackTo("missing-target", "9.9.9")
        assertTrue(rollback.isFailure)
        assertNotNull(manager.findPlugin("missing-target"))
        trustFile.delete()
        Unit
    }
}
