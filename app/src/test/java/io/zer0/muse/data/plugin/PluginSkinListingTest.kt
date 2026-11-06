package io.zer0.muse.data.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.common.AppJson
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.tools.script.ToolDeclaration
import io.zer0.muse.ui.theme.BubbleRole
import io.zer0.muse.ui.theme.BubbleRoleStyle
import io.zer0.muse.ui.theme.BubbleSkin
import io.zer0.muse.ui.theme.BubbleSkinValidator
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Se instaló el complemento ui-skin como prueba para la fuente de piel de burbuja.
 *
 * Portada:Las máscaras legales se pueden enumerar y aún se pueden leer a través de la ruta de "revalidación" incluso si falta el archivo de marcador de posición de entrada JS.
 * piel ilegal(schema/Contraste), borradores no confirmados, deshabilitados, desinstalados y confianza revocada no producirán máscaras renderizables,
 * Los complementos que no son de interfaz de usuario no generan entradas de máscara.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginSkinListingTest {

    /**
     * La versión de la aplicación se proporciona explícitamente en el caso de uso.
     *
     * La instalación del complemento verificará la minAppVersion del manifiesto y [PluginManager] El valor predeterminado es
     * BuildConfig.VERSION_NAME: Cuando se compila sin -PversionName localmente, es el valor centinela 0.0.0-dev.
     * es más pequeño que el 1.0.0 requerido por el complemento, el caso de uso será“Los parámetros de construcción no están incluidos.”Y fracasó.
     */
    private val testAppVersion = "2.6.17"

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Utilice un editor independiente para cada paquete de complementos ID,Evite que varias claves públicas compitan por la misma raíz de entrada de confianza dentro del mismo método de prueba. */
    private fun publisherIdFor(pluginId: String): String = "publisher.$pluginId"

    private val validStyle = BubbleRoleStyle(
        surfaceArgb = 0xFF1B2A38L,
        contentArgb = 0xFFF2F7FBL,
        radiusDp = 16f,
    )

    private val lowContrastStyle = validStyle.copy(
        surfaceArgb = 0xFFEEEEEE,
        contentArgb = 0xFFDDDDDD,
    )

    @Test
    fun installedUiSkinIsListedAndReloadsWithoutJsEntryFile() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val trustStore = PluginTrustStore(trustFile)
        val manager = PluginManager(context, skillRepoMock(), trustStore, appVersionName = testAppVersion)
        val skin = bubbleSkin("aurora-bubble")
        val zip = signedZip(
            id = "skin-pack",
            kind = PluginSecurityGate.UI_SKIN_KIND,
            capabilities = listOf(PluginSecurityGate.UI_SKIN_CAPABILITY),
            extraFiles = mapOf(PluginPackageLoader.UI_SKIN_ENTRY to encode(skin)),
            trustStore = trustStore,
        )

        val installed = installTrusted(manager, zip)
        val listed = manager.listInstalledSkins()
        assertEquals(1, listed.size)
        assertEquals("skin-pack", listed.single().pluginId)
        assertEquals("1.0.0", listed.single().version)
        assertTrue(listed.single().enabled)
        assertEquals("aurora-bubble", listed.single().skin.id)

        // El paquete skin no tiene entrada JS.:Después de eliminar los archivos de marcador de posición vacíos que quedaron durante la instalación,La ruta de reverificación aún debe estar disponible,
        // De lo contrario, skin.json se puede instalar pero nunca se volverá a leer.(Pruebas de regresión).
        val placeholder = File(context.filesDir, "plugins/skin-pack/main.js")
        assertTrue(placeholder.exists())
        assertTrue(placeholder.delete())

        val verified = manager.loadVerifiedPlugin("skin-pack")
        assertNotNull(verified)
        assertEquals("", verified!!.entryCode)
        // El resumen del contenido sigue siendo coherente con el registro de instalación al volver a leer(loadVerifiedPlugin La verificación del resumen interno es un requisito previo).
        assertEquals(installed.contentSha256, manager.findPlugin("skin-pack")?.contentSha256)
        assertEquals("aurora-bubble", manager.listInstalledSkins().single().skin.id)
        trustFile.delete()
        Unit
    }

    @Test
    fun pluginWithoutEntryFileIsStillRejectedForNonSkinKinds() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val trustStore = PluginTrustStore(trustFile)
        val manager = PluginManager(context, skillRepoMock(), trustStore, appVersionName = testAppVersion)
        val zip = signedZip(
            id = "tool-pack",
            kind = "tool",
            capabilities = listOf("resource.read"),
            tools = listOf(tool("hello", "hello")),
            entryCode = "function hello(){ return 'ok'; }",
            trustStore = trustStore,
        )
        installTrusted(manager, zip)

        val entry = File(context.filesDir, "plugins/tool-pack/main.js")
        assertTrue(entry.exists())
        assertTrue(entry.delete())

        // Los archivos de entrada que faltan para complementos que no son de interfaz de usuario siguen siendo errores,No puedes confiar en la tolerancia a fallas del paquete de skin para dejarte ir.
        assertNull(manager.loadVerifiedPlugin("tool-pack"))
        assertTrue(manager.listInstalledSkins().isEmpty())
        trustFile.delete()
        Unit
    }

    @Test
    fun invalidPluginSkinsAreFilteredOut() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val trustStore = PluginTrustStore(trustFile)
        val manager = PluginManager(context, skillRepoMock(), trustStore, appVersionName = testAppVersion)

        // El contraste no está a la altura
        installTrusted(
            manager,
            signedZip(
                id = "low-contrast-pack",
                kind = PluginSecurityGate.UI_SKIN_KIND,
                capabilities = listOf(PluginSecurityGate.UI_SKIN_CAPABILITY),
                extraFiles = mapOf(
                    PluginPackageLoader.UI_SKIN_ENTRY to encode(
                        bubbleSkin("low-contrast", style = lowContrastStyle),
                    ),
                ),
                trustStore = trustStore,
            ),
        )
        // No compatible schemaVersion
        installTrusted(
            manager,
            signedZip(
                id = "future-schema-pack",
                kind = PluginSecurityGate.UI_SKIN_KIND,
                capabilities = listOf(PluginSecurityGate.UI_SKIN_CAPABILITY),
                extraFiles = mapOf(
                    PluginPackageLoader.UI_SKIN_ENTRY to encode(
                        bubbleSkin(
                            "future-schema",
                            schemaVersion = BubbleSkinValidator.CURRENT_SCHEMA + 1,
                        ),
                    ),
                ),
                trustStore = trustStore,
            ),
        )

        // El complemento en sí se instaló correctamente.,Pero las máscaras ilegales no se pueden seleccionar ni mostrar.
        assertEquals(2, manager.list().size)
        assertTrue(manager.listInstalledSkins().isEmpty())
        assertTrue(manager.listInstalledSkins(includeDisabled = true).isEmpty())
        trustFile.delete()
        Unit
    }

    @Test
    fun nonSkinPluginsProduceNoSkinEntry() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val trustStore = PluginTrustStore(trustFile)
        val manager = PluginManager(context, skillRepoMock(), trustStore, appVersionName = testAppVersion)
        installTrusted(
            manager,
            signedZip(
                id = "tool-pack",
                kind = "tool",
                capabilities = listOf("resource.read"),
                tools = listOf(tool("hello", "hello")),
                entryCode = "function hello(){ return 'ok'; }",
                trustStore = trustStore,
            ),
        )

        assertTrue(manager.list().single().enabled)
        assertTrue(manager.listInstalledSkins().isEmpty())
        assertTrue(manager.listInstalledSkins(includeDisabled = true).isEmpty())
        trustFile.delete()
        Unit
    }

    @Test
    fun disabledOrUninstalledPluginSkinStopsBeingUsable() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val trustStore = PluginTrustStore(trustFile)
        val manager = PluginManager(context, skillRepoMock(), trustStore, appVersionName = testAppVersion)
        val zip = signedZip(
            id = "skin-pack",
            kind = PluginSecurityGate.UI_SKIN_KIND,
            capabilities = listOf(PluginSecurityGate.UI_SKIN_CAPABILITY),
            extraFiles = mapOf(
                PluginPackageLoader.UI_SKIN_ENTRY to encode(bubbleSkin("aurora-bubble")),
            ),
            trustStore = trustStore,
        )
        val installed = installTrusted(manager, zip)
        assertEquals(1, manager.listInstalledSkins().size)

        // Ya no está disponible después de desactivarse,Sin embargo, aún puede aparecer como "No disponible" en la página de configuración.
        manager.setEnabled(installed.id, false)
        assertTrue(manager.listInstalledSkins().isEmpty())
        val unavailable = manager.listInstalledSkins(includeDisabled = true)
        assertEquals(1, unavailable.size)
        assertFalse(unavailable.single().enabled)
        assertEquals("aurora-bubble", unavailable.single().skin.id)

        // Estará disponible nuevamente después de volver a habilitarlo.
        manager.setEnabled(installed.id, true)
        assertEquals(1, manager.listInstalledSkins().size)

        // Desaparece completamente después de la desinstalación,El lado del consumidor recurre al valor predeterminado incorporado.
        manager.uninstall(installed.id)
        assertTrue(manager.listInstalledSkins().isEmpty())
        assertTrue(manager.listInstalledSkins(includeDisabled = true).isEmpty())
        trustFile.delete()
        Unit
    }

    @Test
    fun revokedTrustMakesInstalledSkinUnavailable() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val trustStore = PluginTrustStore(trustFile)
        val manager = PluginManager(context, skillRepoMock(), trustStore, appVersionName = testAppVersion)
        val zip = signedZip(
            id = "skin-pack",
            kind = PluginSecurityGate.UI_SKIN_KIND,
            capabilities = listOf(PluginSecurityGate.UI_SKIN_CAPABILITY),
            extraFiles = mapOf(
                PluginPackageLoader.UI_SKIN_ENTRY to encode(bubbleSkin("aurora-bubble")),
            ),
            trustStore = trustStore,
        )
        installTrusted(manager, zip)
        assertEquals(1, manager.listInstalledSkins().size)

        assertTrue(trustStore.revoke(publisherIdFor("skin-pack")))

        // La revisión de firma falla después de la revocación del fideicomiso:No renderizable,Se muestra solo como artículos no disponibles.
        assertTrue(manager.listInstalledSkins().isEmpty())
        assertFalse(manager.listInstalledSkins(includeDisabled = true).single().enabled)
        trustFile.delete()
        Unit
    }

    @Test
    fun unsignedDraftSkinNeverBecomesUsable() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val trustStore = PluginTrustStore(trustFile)
        val manager = PluginManager(context, skillRepoMock(), trustStore, appVersionName = testAppVersion)
        val zip = unsignedSkinZip(
            id = "draft-skin",
            extraFiles = mapOf(
                PluginPackageLoader.UI_SKIN_ENTRY to encode(bubbleSkin("draft-bubble")),
            ),
        )
        val preview = manager.reviewFromFile(zip).getOrThrow().preview
        assertEquals(PluginSecurityGate.SignatureStatus.UNSIGNED, preview.signatureStatus)
        val draft = manager.installDraftFromFile(zip, preview).getOrThrow()
        assertFalse(draft.enabled)

        assertTrue(manager.listInstalledSkins().isEmpty())
        val unavailable = manager.listInstalledSkins(includeDisabled = true)
        assertEquals(1, unavailable.size)
        assertFalse(unavailable.single().enabled)
        trustFile.delete()
        Unit
    }

    @Test
    fun registryMutationsBumpSkinRevision() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val trustStore = PluginTrustStore(trustFile)
        val manager = PluginManager(context, skillRepoMock(), trustStore, appVersionName = testAppVersion)
        val zip = signedZip(
            id = "skin-pack",
            kind = PluginSecurityGate.UI_SKIN_KIND,
            capabilities = listOf(PluginSecurityGate.UI_SKIN_CAPABILITY),
            extraFiles = mapOf(
                PluginPackageLoader.UI_SKIN_ENTRY to encode(bubbleSkin("aurora-bubble")),
            ),
            trustStore = trustStore,
        )
        val before = manager.revisionFlow.value

        val installed = installTrusted(manager, zip)
        assertTrue(manager.revisionFlow.value > before)

        val afterInstall = manager.revisionFlow.value
        manager.setEnabled(installed.id, false)
        assertTrue(manager.revisionFlow.value > afterInstall)

        val afterToggle = manager.revisionFlow.value
        manager.uninstall(installed.id)
        assertTrue(manager.revisionFlow.value > afterToggle)
        trustFile.delete()
        Unit
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun bubbleSkin(id: String, schemaVersion: Int = 1, style: BubbleRoleStyle = validStyle): BubbleSkin = BubbleSkin(
        schemaVersion = schemaVersion,
        id = id,
        name = "Skin $id",
        light = mapOf(BubbleRole.USER to style, BubbleRole.ASSISTANT to style),
        dark = mapOf(BubbleRole.USER to style, BubbleRole.ASSISTANT to style),
    )

    private fun encode(skin: BubbleSkin): String = AppJson.encodeToString(BubbleSkin.serializer(), skin)

    private fun tool(name: String, functionName: String): ToolDeclaration = ToolDeclaration(
        name = name,
        description = "test tool",
        parametersJson = "{}",
        requiredJson = "[]",
        functionName = functionName,
    )

    private suspend fun installTrusted(manager: PluginManager, file: File): PluginManager.InstalledPlugin {
        val preview = manager.reviewFromFile(file).getOrThrow().preview
        val result = manager.installConfirmedFromFile(file, preview)
        assertTrue("install failed: ${result.exceptionOrNull()}", result.isSuccess)
        return result.getOrThrow()
    }

    /**
     * Genere un paquete de complemento firmado que se haya escrito en la raíz de confianza local;Manifiesto de cobertura de firma + contenido de entrada + archivos adjuntos,
     * Consistente con la entrada de firma de PluginSecurityGate.
     */
    private fun signedZip(
        id: String,
        kind: String,
        capabilities: List<String>,
        tools: List<ToolDeclaration> = emptyList(),
        entryCode: String = "",
        extraFiles: Map<String, String> = emptyMap(),
        trustStore: PluginTrustStore,
    ): File {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = generator.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val publisherId = publisherIdFor(id)
        val unsignedManifest = PluginManifest(
            id = id,
            name = "Test Plugin $id",
            version = "1.0.0",
            entry = "main.js",
            kind = kind,
            capabilities = capabilities,
            tools = tools,
            signature = PluginSignature(publisherId, publicKey, ""),
        )
        val unsignedPackage = PluginPackageLoader.LoadedPluginPackage(
            manifest = unsignedManifest,
            entryCode = entryCode,
            extraFiles = extraFiles,
        )
        val signature = Signature.getInstance(PluginSecurityGate.SIGNATURE_ALGORITHM).apply {
            initSign(keyPair.private)
            update(PluginSecurityGate.signaturePayload(unsignedPackage))
        }.sign()
        val manifest = unsignedManifest.copy(
            signature = unsignedManifest.signature!!.copy(
                signature = Base64.getEncoder().encodeToString(signature),
            ),
        )
        trustStore.trust(publisherId, publicKey).getOrThrow()
        return writeZip(
            manifestJson = AppJson.encodeToString(PluginManifest.serializer(), manifest),
            entryCode = entryCode,
            extraFiles = extraFiles,
        )
    }

    /** Paquete de aspectos sin firmar:Sólo permitir la instalación como borrador deshabilitado,No se puede convertir en una máscara renderizable. */
    private fun unsignedSkinZip(id: String, extraFiles: Map<String, String>): File {
        val manifest = PluginManifest(
            id = id,
            name = "Draft Skin $id",
            version = "1.0.0",
            kind = PluginSecurityGate.UI_SKIN_KIND,
            capabilities = listOf(PluginSecurityGate.UI_SKIN_CAPABILITY),
        )
        return writeZip(
            manifestJson = AppJson.encodeToString(PluginManifest.serializer(), manifest),
            entryCode = "",
            extraFiles = extraFiles,
        )
    }

    private fun writeZip(manifestJson: String, entryCode: String, extraFiles: Map<String, String>): File {
        val bytes = ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                fun put(name: String, content: String) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content.toByteArray())
                    zos.closeEntry()
                }
                put("manifest.json", manifestJson)
                // ui-skin El paquete no lleva entrada JS.,Consistente con las restricciones de PluginPackageLoader.
                if (entryCode.isNotEmpty()) put("main.js", entryCode)
                extraFiles.forEach { (name, content) -> put(name, content) }
            }
            bos.toByteArray()
        }
        return File(context.cacheDir, "skin_test_${System.nanoTime()}.muse-plugin").apply {
            writeBytes(bytes)
        }
    }

    /** Real DAO regresa para identificaciones desocupadas null;relaxed mock Devuelve no vacío por defecto mock,La detección de conflictos puede activarse por error. */
    private fun skillRepoMock(): SkillRepository = mockk<SkillRepository>(relaxed = true).also { repo ->
        coEvery { repo.getById(any()) } returns null
    }
}
