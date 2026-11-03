package io.zer0.muse.data.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.zer0.common.AppJson
import io.zer0.muse.data.skill.SkillDao
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.data.skill.SkillRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * B6-01: Instalación de complemento externo/Desinstalar/Prueba de verificación de seguridad.
 *
 * Phase 5 P0 Agregar:skill id Codificación anticolisión y rechazo de conflictos, instalación./actualizar/Ciclo de vida de habilidades desinstaladas
 * Conciliación (la desinstalación ya no depende únicamente del manifiesto actual) y verificación de entrada basada en la revalidación del manifiesto antes de la ejecución.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginManagerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun corruptedRegistryIsQuarantinedInsteadOfAccepted() = runBlocking {
        val registry = File(context.filesDir, "plugin_registry.json")
        registry.parentFile?.mkdirs()
        registry.writeText("{not-json")

        val manager = PluginManager(context, mockk(relaxed = true))

        assertTrue(manager.list().isEmpty())
        assertFalse(registry.exists())
        assertTrue(
            registry.parentFile?.listFiles()?.any { it.name.startsWith("plugin_registry.json.corrupt-") } == true,
        )
    }

    // ── P0-9: La raíz de la confianza es revocable.(Misma instancia de tienda que la verificación de instalación.) ──

    @Test
    fun revokePublisher_removesTrustRoot() = runBlocking {
        // Raíz preestablecida del archivo de confianza(Consistente con el formato de persistencia de PluginTrustStore),verificar PluginManager
        // RevokePublisher va a la misma tienda: después de revocar TrustedPublishers está vacío.
        val trustFile = File(context.noBackupFilesDir, "muse_plugin_trust_roots.json")
        trustFile.parentFile?.mkdirs()
        val key = generateEcPublicKey()
        val fingerprint = PluginSecurityGate.publicKeyFingerprint(
            java.util.Base64.getDecoder().decode(key),
        )
        trustFile.writeText(
            """{"publishers":[{"publisherId":"publisher.test","publicKey":"$key","fingerprint":"$fingerprint"}]}""",
        )

        val manager = PluginManager(context, mockk(relaxed = true))
        try {
            assertEquals(1, manager.trustedPublishers().size)
            assertEquals("publisher.test", manager.trustedPublishers()[0].publisherId)

            assertEquals("publisher.test", manager.revokePublisher("publisher.test"))
            assertTrue("La lista de raíces confiables debe estar vacía después de la revocación", manager.trustedPublishers().isEmpty())
            assertEquals("La operación de deshacer repetidamente debería regresar null", null, manager.revokePublisher("publisher.test"))
        } finally {
            trustFile.delete()
        }
    }

    private fun generateEcPublicKey(): String {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return java.util.Base64.getEncoder().encodeToString(generator.generateKeyPair().public.encoded)
    }

    @Test
    fun install_rejectsOversizedPackageBeforeReadingIntoMemory() = runBlocking {
        val file = File(context.cacheDir, "oversized_${System.nanoTime()}.muse-plugin")
        java.io.RandomAccessFile(file, "rw").use { output ->
            output.setLength(PluginManager.MAX_PLUGIN_PACKAGE_BYTES + 1)
        }

        val manager = PluginManager(context, mockk(relaxed = true))
        val result = draftInstall(manager, file)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("excede el límite") == true)
        file.delete()
        Unit
    }

    @Test
    fun install_registersSkillAndPersistsPlugin() = runBlocking {
        val skillRepo = skillRepoMock()
        val manager = PluginManager(context, skillRepo)
        val zip = zip(
            manifest = """
                {
                  "id": "test-plugin",
                  "name": "Test Plugin",
                  "version": "1.0.0",
                  "entry": "main.js",
                  "kind": "tool",
                  "capabilities": ["ui"],
                  "tools": [
                    {
                      "name": "hello",
                      "description": "say hello",
                      "parametersJson": "{}",
                      "requiredJson": "[]",
                      "functionName": "hello"
                    }
                  ]
                }
            """.trimIndent(),
        )

        val result = draftInstall(manager, zip)
        assertTrue("install failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("test-plugin", manager.list().single().id)
        assertTrue(File(context.filesDir, "plugins/test-plugin/main.js").exists())
        coVerify { skillRepo.upsert(match { it.id == PluginManager.skillId("test-plugin", "hello") }) }
    }

    @Test
    fun installedEntryRejectsContentTampering() = runBlocking {
        val skillRepo = skillRepoMock()
        val manager = PluginManager(context, skillRepo)
        val result = draftInstall(manager, zip())
        assertTrue("install failed: ${result.exceptionOrNull()}", result.isSuccess)

        File(context.filesDir, "plugins/test-plugin/main.js").writeText("function hello(){ return 'tampered'; }")

        assertEquals(null, manager.loadEntryCode("test-plugin"))
    }

    @Test
    fun uninstall_removesDirAndSkill() = runBlocking {
        val dao = FakeSkillDao()
        val manager = PluginManager(context, SkillRepository(dao))
        val result = draftInstall(manager, zip())
        assertTrue(result.isSuccess)
        assertEquals(setOf(PluginManager.skillId("test-plugin", "hello")), dao.rows.keys)

        manager.uninstall("test-plugin")

        assertTrue(manager.list().isEmpty())
        assertFalse(File(context.filesDir, "plugins/test-plugin").exists())
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun uninstall_nonexistentPlugin_isHarmlessNoOp() = runBlocking {
        // P5-4: La desinstalación de complementos inexistentes debe ser inofensiva no-op(No se lanzan excepciones ni se generan datos de registro sucios.)
        val dao = FakeSkillDao()
        val manager = PluginManager(context, SkillRepository(dao))
        manager.uninstall("never-installed")
        assertTrue(manager.list().isEmpty())
        assertTrue(dao.rows.isEmpty())
        assertFalse(File(context.filesDir, "plugins/never-installed").exists())
    }

    @Test
    fun install_rejectsExternalFullAccessTrustClaim() = runBlocking {
        val skillRepo = skillRepoMock()
        val manager = PluginManager(context, skillRepo)
        val result = draftInstall(
            manager,
            zip(
                manifest = """
                    {
                      "id": "untrusted-full-access",
                      "name": "Untrusted",
                      "version": "1.0.0",
                      "trust": "full-access",
                      "tools": [{"name": "hello", "description": "x", "parametersJson": "{}", "requiredJson": "[]", "functionName": "hello"}]
                    }
                """.trimIndent(),
            ),
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("espacio aislado") == true)
        assertTrue(manager.list().none { it.id == "untrusted-full-access" })
    }

    @Test
    fun install_acceptsAnyDeclaredCapabilityAfterSandboxOpening() = runBlocking {
        // v2.x(Versión Sandbox): Las capacidades ya no se niegan según la lista blanca y se indica que se puede instalar cualquier capacidad legal.
        val skillRepo = skillRepoMock()
        val manager = PluginManager(context, skillRepo)
        val zip = zip(
            manifest = """
                {
                  "id": "cap-accept",
                  "name": "Cap Accept",
                  "version": "1.0.0",
                  "entry": "main.js",
                  "capabilities": ["system.exec"],
                  "tools": [
                    {
                      "name": "boom",
                      "description": "x",
                      "parametersJson": "{}",
                      "requiredJson": "[]",
                      "functionName": "boom"
                    }
                  ]
                }
            """.trimIndent(),
        )

        val result = draftInstall(manager, zip)
        assertTrue(result.isSuccess)
    }

    @Test
    fun install_rejectsInvalidCapabilityNameFormat() = runBlocking {
        // Después del lanzamiento, el formato del nombre de la habilidad aún se verificará.
        val skillRepo = skillRepoMock()
        val manager = PluginManager(context, skillRepo)
        val zip = zip(
            manifest = """
                {
                  "id": "cap-badformat",
                  "name": "Bad Format",
                  "version": "1.0.0",
                  "entry": "main.js",
                  "capabilities": ["Invalid Cap!"],
                  "tools": [{"name": "t", "description": "x", "parametersJson": "{}", "requiredJson": "[]", "functionName": "t"}]
                }
            """.trimIndent(),
        )
        val result = draftInstall(manager, zip)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("formato del nombre de la capacidad") == true)
        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun install_pluginWithJsEntry_keepsEntryCode() = runBlocking {
        val skillRepo = skillRepoMock()
        val manager = PluginManager(context, skillRepo)
        val zip = zip(
            manifest = """
                {
                  "id": "todo-summary",
                  "name": "Todo Summary",
                  "version": "0.1.0",
                  "entry": "main.js",
                  "kind": "tool",
                  "capabilities": ["resource.read"],
                  "tools": [
                    {
                      "name": "summarize_todos",
                      "description": "summarize todos",
                      "parametersJson": "{}",
                      "requiredJson": "[]",
                      "functionName": "summarizeTodos"
                    }
                  ]
                }
            """.trimIndent(),
            entry = """
                function summarizeTodos(args) {
                  return "count=" + (args.text || "").split("\n").length;
                }
            """.trimIndent(),
        )

        val result = draftInstall(manager, zip)
        assertTrue("install failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertFalse(result.getOrThrow().installationConfirmed)
        assertEquals(null, manager.loadEntryCode("todo-summary"))
        val preview = manager.reviewFromFile(zip).getOrThrow().preview
        val confirmed = manager.installConfirmedFromFile(zip, preview)
        assertTrue(confirmed.isFailure)
        assertTrue(manager.loadEntryCode("todo-summary") == null)
    }

    @Test
    fun signedPluginRequiresExplicitTrustThenLoadsEntryCode() = runBlocking {
        val skillRepo = skillRepoMock()
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val trustStore = PluginTrustStore(trustFile)
        val manager = PluginManager(context, skillRepo, trustStore)
        val signed = signedZip("signed-todo")
        val preview = manager.reviewFromFile(signed.file).getOrThrow().preview

        assertEquals(PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED, preview.signatureStatus)
        assertTrue(manager.installConfirmedFromFile(signed.file, preview).isFailure)
        val trusted = manager.installConfirmedFromFile(signed.file, preview, trustPublisher = true)
        assertTrue("install failed: ${trusted.exceptionOrNull()}", trusted.isSuccess)
        assertEquals(PluginSecurityGate.SignatureStatus.VALID_TRUSTED, manager.list().single { it.id == "signed-todo" }.signatureStatus)
        assertTrue(manager.loadEntryCode("signed-todo")?.contains("function hello") == true)

        trustStore.revoke("publisher.test")
        assertEquals(null, manager.loadEntryCode("signed-todo"))
        trustFile.delete()
        Unit
    }

    // ── P0-1: skill id Codificación anticolisión + rechazo de conflictos durante la instalación ─────────────────────────────

    @Test
    fun skillId_usesLengthPrefixSoUnderscoreValuesCannotCollide() {
        // La codificación antigua combinará dos diferentes (pluginId, toolName) La combinación se asigna a la misma identificación.
        assertEquals(
            PluginManager.legacySkillId("a_b", "c"),
            PluginManager.legacySkillId("a", "b_c"),
        )

        val first = PluginManager.skillId("a_b", "c")
        val second = PluginManager.skillId("a", "b_c")
        assertNotEquals(first, second)
        // El prefijo de longitud permite decodificar el prefijo de identificación sin ambigüedades.
        assertTrue(first.startsWith("plugin_3_a_b_"))
        assertTrue(second.startsWith("plugin_1_a_b_c"))
    }

    @Test
    fun install_rejectsSkillIdOccupiedByExistingNonPluginSkill() = runBlocking {
        val dao = FakeSkillDao()
        val manager = PluginManager(context, SkillRepository(dao))
        val occupiedId = PluginManager.skillId("test-plugin", "hello")
        dao.rows[occupiedId] = SkillEntity(
            id = occupiedId,
            name = "Definido por el usuario skill",
            description = "",
            implementationKotlin = "custom_impl",
            category = "custom",
        )

        val result = draftInstall(manager, zip())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("conflicto") == true)
        assertTrue(manager.list().isEmpty())
        assertFalse(File(context.filesDir, "plugins/test-plugin").exists())
        // Las habilidades existentes no se sobrescribirán.
        assertEquals("custom_impl", dao.rows[occupiedId]?.implementationKotlin)
    }

    @Test
    fun install_rejectsDuplicateToolNamesThatWouldCollide() = runBlocking {
        val dao = FakeSkillDao()
        val manager = PluginManager(context, SkillRepository(dao))
        val manifest = """
            {
              "id": "dup-tools",
              "name": "Dup Tools",
              "version": "1.0.0",
              "entry": "main.js",
              "tools": [
                {"name": "hello", "description": "a", "parametersJson": "{}", "requiredJson": "[]", "functionName": "hello"},
                {"name": "hello", "description": "b", "parametersJson": "{}", "requiredJson": "[]", "functionName": "helloB"}
              ]
            }
        """.trimIndent()

        val result = draftInstall(manager, zip(manifest = manifest))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("duplicado") == true)
        assertTrue(dao.rows.isEmpty())
    }

    // ── P0-2: Instalación/actualizar/Desinstalar la reconciliación del ciclo de vida de habilidades ───────────────────────────────

    @Test
    fun update_removesSkillsDroppedFromNewManifest() = runBlocking {
        val dao = FakeSkillDao()
        val manager = PluginManager(context, SkillRepository(dao))
        assertTrue(
            draftInstall(manager, zip(manifest = pluginManifest("1.0.0", listOf("hello", "bye")))).isSuccess,
        )
        val helloId = PluginManager.skillId("test-plugin", "hello")
        val byeId = PluginManager.skillId("test-plugin", "bye")
        assertEquals(setOf(helloId, byeId), dao.rows.keys)

        // Los restos históricos registrados en la antigua era de codificación también se migrarán y limpiarán durante la actualización.
        val legacyByeId = PluginManager.legacySkillId("test-plugin", "bye")
        dao.rows[legacyByeId] = ownedSkill(legacyByeId, "test-plugin", "bye")

        assertTrue(
            draftInstall(manager, zip(manifest = pluginManifest("1.1.0", listOf("hello")))).isSuccess,
        )

        assertEquals(setOf(helloId), dao.rows.keys)
        assertFalse(dao.rows.containsKey(byeId))
        assertFalse(dao.rows.containsKey(legacyByeId))
    }

    @Test
    fun uninstall_removesSkillsRecordedFromPreviousManifestVersions() = runBlocking {
        val dao = FakeSkillDao()
        val manager = PluginManager(context, SkillRepository(dao))
        assertTrue(
            draftInstall(manager, zip(manifest = pluginManifest("1.0.0", listOf("hello", "bye")))).isSuccess,
        )
        assertTrue(
            draftInstall(manager, zip(manifest = pluginManifest("1.1.0", listOf("hello")))).isSuccess,
        )

        // Simular la interrupción de la limpieza del historial: manifiesto actual (v1.1.0) ya no contiene adiós, pero la antigua habilidad todavía está en la biblioteca.
        val legacyByeId = PluginManager.legacySkillId("test-plugin", "bye")
        dao.rows[legacyByeId] = ownedSkill(legacyByeId, "test-plugin", "bye")

        manager.uninstall("test-plugin")

        assertTrue(dao.rows.isEmpty())
        assertFalse(File(context.filesDir, "plugins/test-plugin").exists())
    }

    @Test
    fun uninstall_keepsSkillsNotOwnedByPlugin() = runBlocking {
        val dao = FakeSkillDao()
        val manager = PluginManager(context, SkillRepository(dao))
        assertTrue(draftInstall(manager, zip()).isSuccess)

        // La misma identificación pero no la implementación de este complemento (usuario/Habilidad incorporada): Se debe conservar la desinstalación.
        val notOwnedId = PluginManager.legacySkillId("test-plugin", "hello")
        dao.rows[notOwnedId] = SkillEntity(
            id = notOwnedId,
            name = "Usuario skill",
            description = "",
            implementationKotlin = "custom_impl",
            category = "custom",
        )

        manager.uninstall("test-plugin")

        assertEquals(setOf(notOwnedId), dao.rows.keys)
    }

    // ── P0-3: Uso revalidado antes de la ejecución. manifest ────────────────────────────────

    @Test
    fun loadVerifiedPlugin_revalidatesManifestInsteadOfRegistryCache() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val manager = PluginManager(context, skillRepoMock(), PluginTrustStore(trustFile))
        val signed = signedZip("verified-plugin")
        val preview = manager.reviewFromFile(signed.file).getOrThrow().preview
        val trusted = manager.installConfirmedFromFile(signed.file, preview, trustPublisher = true)
        assertTrue("install failed: ${trusted.exceptionOrNull()}", trusted.isSuccess)

        // Manipulación de los campos de la caché del registro (simulando una caché contaminada)/campo antiguo), la ruta de ejecución debe ignorarlo.
        val registryFile = File(context.filesDir, "plugin_registry.json")
        val root = AppJson.parseToJsonElement(registryFile.readText()).jsonObject
        val plugins = root.getValue("plugins").jsonArray.map { element ->
            val plugin = element.jsonObject
            if (plugin.getValue("id").jsonPrimitive.content == "verified-plugin") {
                JsonObject(plugin + ("capabilities" to JsonArray(listOf(JsonPrimitive("network")))))
            } else {
                element
            }
        }
        registryFile.writeText(JsonObject(root + ("plugins" to JsonArray(plugins))).toString())

        val reloaded = PluginManager(context, skillRepoMock(), PluginTrustStore(trustFile))
        assertEquals(listOf("network"), reloaded.findPlugin("verified-plugin")?.capabilities)

        val verified = requireNotNull(reloaded.loadVerifiedPlugin("verified-plugin"))
        // capabilities/tools Manifiesto de la revalidación del disco, no del caché del registro.
        assertEquals(listOf("resource.read"), verified.capabilities)
        assertEquals(listOf("hello"), verified.tools.map { it.name })
        assertTrue(verified.entryCode.contains("function hello"))
        // loadEntryCode Manténgalo como una envoltura delgada y el comportamiento no cambia.
        assertEquals(verified.entryCode, reloaded.loadEntryCode("verified-plugin"))
        trustFile.delete()
        Unit
    }

    @Test
    fun loadVerifiedFunction_rejectsFunctionRemovedFromManifest() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val manager = PluginManager(context, skillRepoMock(), PluginTrustStore(trustFile))
        val signed = signedZip("function-gate")
        val preview = manager.reviewFromFile(signed.file).getOrThrow().preview
        val trusted = manager.installConfirmedFromFile(signed.file, preview, trustPublisher = true)
        assertTrue("install failed: ${trusted.exceptionOrNull()}", trusted.isSuccess)

        assertNotNull(manager.loadVerifiedFunction("function-gate", "hello"))
        assertNull(manager.loadVerifiedFunction("function-gate", "removedFunction"))
        // Discapacitado/La vista de ejecución no se devuelve cuando no hay confirmación o el contenido no es verificable.
        assertNull(manager.loadVerifiedFunction("missing-plugin", "hello"))
        trustFile.delete()
        Unit
    }

    /** Real DAO devuelve nulo para identificadores desocupados;relaxed mock De forma predeterminada, se devuelve un simulacro que no está vacío, lo que puede desencadenar la detección de conflictos por error. */
    private fun skillRepoMock(): SkillRepository = mockk<SkillRepository>(relaxed = true).also { repo ->
        coEvery { repo.getById(any()) } returns null
    }

    /** installFromFile Eliminado: reemplazado con el equivalente actual de \"Vista previa primero, luego haga clic para deshabilitar el borrador de la instalación\". */
    private suspend fun draftInstall(manager: PluginManager, file: File): Result<PluginManager.InstalledPlugin> {
        val decision = manager.reviewFromFile(file)
        val preview = decision.getOrElse { error -> return Result.failure(error) }.preview
        return manager.installDraftFromFile(file, preview)
    }

    private data class SignedZip(
        val file: File,
    )

    private fun signedZip(id: String): SignedZip {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = generator.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val entryCode = "function hello() { return 'signed'; }"
        val unsignedManifest = PluginManifest(
            id = id,
            name = "Signed Plugin",
            version = "1.0.0",
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
            signature = PluginSignature("publisher.test", publicKey, ""),
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
        return SignedZip(
            zip(
                manifest = AppJson.encodeToString(PluginManifest.serializer(), manifest),
                entry = entryCode,
            ),
        )
    }

    private fun pluginManifest(version: String, tools: List<String>): String {
        val toolsJson = tools.joinToString(",") { tool ->
            """{"name": "$tool", "description": "say $tool", "parametersJson": "{}", "requiredJson": "[]", "functionName": "$tool"}"""
        }
        return """
            {
              "id": "test-plugin",
              "name": "Test Plugin",
              "version": "$version",
              "entry": "main.js",
              "kind": "tool",
              "capabilities": ["ui"],
              "tools": [$toolsJson]
            }
        """.trimIndent()
    }

    private fun ownedSkill(id: String, pluginId: String, functionName: String): SkillEntity = SkillEntity(
        id = id,
        name = "stale",
        description = "",
        implementationKotlin = "plugin:$pluginId:$functionName",
        category = "plugin",
    )

    private fun zip(
        manifest: String = """
            {
              "id": "test-plugin",
              "name": "Test Plugin",
              "version": "1.0.0",
              "entry": "main.js",
              "tools": [
                {
                  "name": "hello",
                  "description": "say hello",
                  "parametersJson": "{}",
                  "requiredJson": "[]",
                  "functionName": "hello"
                }
              ]
            }
        """.trimIndent(),
        entry: String = "function hello(){ return 'ok'; }",
    ): File {
        val bytes = ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("manifest.json"))
                zos.write(manifest.toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("main.js"))
                zos.write(entry.toByteArray())
                zos.closeEntry()
            }
            bos.toByteArray()
        }
        val file = File(context.cacheDir, "test_${System.nanoTime()}.muse-plugin")
        file.writeBytes(bytes)
        return file
    }

    /** Versión de memoria de SkillDao: instalemos/actualizar/Las afirmaciones del ciclo de vida de las habilidades desinstaladas se basan en el estado real de la fila. */
    private class FakeSkillDao : SkillDao {
        val rows = linkedMapOf<String, SkillEntity>()

        override fun observeAll(): Flow<List<SkillEntity>> = MutableStateFlow(rows.values.toList())

        override suspend fun listEnabled(): List<SkillEntity> = rows.values.filter { it.enabled }

        override suspend fun listEnabledByIds(ids: List<String>): List<SkillEntity> = rows.values.filter { it.enabled && it.id in ids }

        override suspend fun getById(id: String): SkillEntity? = rows[id]

        override suspend fun upsert(entity: SkillEntity) {
            rows[entity.id] = entity
        }

        override suspend fun seedIfAbsent(entity: SkillEntity) {
            rows.putIfAbsent(entity.id, entity)
        }

        override suspend fun refreshBuiltInDefinition(
            id: String,
            name: String,
            description: String,
            parametersJson: String,
            requiredJson: String,
            category: String,
            implementationKotlin: String,
        ) {
            rows[id]?.let {
                rows[id] =
                    it.copy(
                        name = name,
                        description = description,
                        parametersJson = parametersJson,
                        requiredJson = requiredJson,
                        category = category,
                        implementationKotlin = implementationKotlin,
                    )
            }
        }

        override suspend fun update(entity: SkillEntity) {
            rows[entity.id] = entity
        }

        override suspend fun delete(id: String) {
            rows.remove(id)
        }

        override suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long) {
            rows[id]?.let { rows[id] = it.copy(enabled = enabled, updatedAt = updatedAt) }
        }

        override suspend fun getAll(): List<SkillEntity> = rows.values.toList()

        override suspend fun deleteAll() {
            rows.clear()
        }
    }
}
