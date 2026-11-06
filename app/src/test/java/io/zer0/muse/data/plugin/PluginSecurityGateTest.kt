package io.zer0.muse.data.plugin

import io.zer0.muse.tools.script.ToolDeclaration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Prueba de puerta protectora de instalación externa enchufable de lógica pura. */
class PluginSecurityGateTest {

    @Test
    fun review_exposesCapabilitiesToolsAndCodeDigestWithoutSource() {
        val decision = PluginSecurityGate.review(packageOf())

        assertTrue(decision.allowed)
        assertTrue(decision.requiresConfirmation)
        assertEquals(listOf("resource.read"), decision.preview.capabilities)
        assertEquals(listOf("hello"), decision.preview.tools.map { it.name })
        assertEquals("hello", decision.preview.tools.single().functionName)
        assertEquals(PluginSecurityGate.entrySha256("function hello() { return 'ok'; }"), decision.preview.entrySha256)
        assertTrue(decision.preview.contentSha256.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals("function hello() { return 'ok'; }", decision.preview.toString())
    }

    @Test
    fun review_acceptsAnyCapabilityAfterSandboxOpening() {
        // v2.x(Versión Sandbox): las habilidades ya no se rechazan según una lista fija y se puede pasar cualquier nombre legal.
        val decision = PluginSecurityGate.review(
            packageOf(capabilities = listOf("system.exec")),
        )

        assertTrue(decision.allowed)
    }

    @Test
    fun review_rejectsInvalidCapabilityNameFormat() {
        // Después del lanzamiento, aún se verificará el formato del nombre de la habilidad (validez del personaje).
        val decision = PluginSecurityGate.review(
            packageOf(capabilities = listOf("Invalid Name!")),
        )

        assertFalse(decision.allowed)
        assertTrue(decision.reason?.contains("formato del nombre de la capacidad") == true)
    }

    @Test
    fun review_acceptsBridgeCapabilitiesForExternalPlugins() {
        val bridgeCaps = listOf(
            "network",
            "storage.read",
            "storage.write",
            "clipboard.read",
            "clipboard.write",
            "notify",
            "device.info",
        )
        val decision = PluginSecurityGate.review(packageOf(capabilities = bridgeCaps))

        assertTrue("Deben aceptarse capacidades puente: ${decision.reason}", decision.allowed)
        assertEquals(bridgeCaps, decision.preview.capabilities)
    }

    @Test
    fun review_rejectsSelfDeclaredFullAccess() {
        val decision = PluginSecurityGate.review(packageOf(trust = "full-access"))

        assertFalse(decision.allowed)
        assertTrue(decision.reason?.contains("sandboxed") == true)
    }

    @Test
    fun review_rejectsJavascriptFunctionInjection() {
        val decision = PluginSecurityGate.review(
            packageOf(functionName = "hello;fetch('https://example.test')"),
        )

        assertFalse(decision.allowed)
        assertTrue(decision.reason?.contains("nombre de la función es ilegal") == true)
    }

    @Test
    fun unsignedPackageIsPreviewableButNeverInstallable() {
        val decision = PluginSecurityGate.review(packageOf())

        assertTrue(decision.allowed)
        assertTrue(decision.requiresConfirmation)
        assertFalse(decision.isInstallable)
        assertEquals(PluginSecurityGate.SignatureStatus.UNSIGNED, decision.signature.status)
        assertFalse(decision.requiresPublisherTrust)
    }

    @Test
    fun validUnknownPublisherRequiresTrustBeforeInstall() {
        val signed = signedPackage()
        val untrusted = PluginSecurityGate.review(signed.package_)
        val envelope = signed.package_.manifest.signature!!

        assertTrue(untrusted.allowed)
        assertTrue(untrusted.requiresPublisherTrust)
        assertFalse(untrusted.isInstallable)
        assertEquals(PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED, untrusted.signature.status)

        val trusted = PluginSecurityGate.review(
            signed.package_,
            trustedPublisherKeys = mapOf(envelope.publisherId to envelope.publicKey),
        )
        assertTrue(trusted.isInstallable)
        assertEquals(PluginSecurityGate.SignatureStatus.VALID_TRUSTED, trusted.signature.status)
        assertTrue(PluginSecurityGate.hasSameContentIdentity(untrusted.preview, trusted.preview))
    }

    @Test
    fun tamperingManifestOrEntryInvalidatesSignature() {
        val signed = signedPackage()
        val envelope = signed.package_.manifest.signature!!
        val trustedKeys = mapOf(envelope.publisherId to envelope.publicKey)

        val changedEntry = PluginSecurityGate.review(
            signed.package_.copy(entryCode = "function hello() { return 'changed'; }"),
        )
        val changedManifest = PluginSecurityGate.review(
            signed.package_.copy(manifest = signed.package_.manifest.copy(description = "changed")),
            trustedKeys,
        )

        assertFalse(changedEntry.allowed)
        assertEquals(PluginSecurityGate.SignatureStatus.INVALID, changedEntry.signature.status)
        assertFalse(changedManifest.allowed)
        assertEquals(PluginSecurityGate.SignatureStatus.INVALID, changedManifest.signature.status)
    }

    @Test
    fun anotherKeyCannotImpersonateTrustedPublisherId() {
        val trustedFixture = signedPackage(publisherId = "publisher.same")
        val attackerFixture = signedPackage(publisherId = "publisher.same")
        val trustedEnvelope = trustedFixture.package_.manifest.signature!!
        val decision = PluginSecurityGate.review(
            attackerFixture.package_,
            trustedPublisherKeys = mapOf(trustedEnvelope.publisherId to trustedEnvelope.publicKey),
        )

        assertTrue(decision.allowed)
        assertEquals(PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED, decision.signature.status)
        assertFalse(decision.isInstallable)
    }

    @Test
    fun unsupportedSignatureAlgorithmFailsClosed() {
        val signed = signedPackage()
        val unsupported = signed.package_.copy(
            manifest = signed.package_.manifest.copy(
                signature = signed.package_.manifest.signature!!.copy(algorithm = "Ed25519"),
            ),
        )

        val decision = PluginSecurityGate.review(unsupported)

        assertFalse(decision.allowed)
        assertEquals(PluginSecurityGate.SignatureStatus.UNSUPPORTED, decision.signature.status)
    }

    @Test
    fun installIdentityChangesWhenEntryCodeChanges() {
        val original = PluginSecurityGate.review(packageOf()).preview
        val changed = PluginSecurityGate.review(
            packageOf(entryCode = "function hello() { return 'changed'; }"),
        ).preview

        assertFalse(PluginSecurityGate.hasSameInstallIdentity(original, changed))
    }

    @Test
    fun uiSkinPackageWithoutToolsIsAllowedByTheGate() {
        val decision = PluginSecurityGate.review(uiSkinPackageOf())

        assertTrue(decision.allowed)
        assertTrue(decision.requiresConfirmation)
        // Los paquetes de aspectos sin firmar también están disponibles solo como borradores de cuarentena.,no se puede ejecutar directamente
        assertFalse(decision.isInstallable)
        assertTrue(decision.preview.tools.isEmpty())
        assertEquals(listOf("ui.skin"), decision.preview.capabilities)
    }

    @Test
    fun uiSkinCapabilityIsWhitelisted() {
        val decision = PluginSecurityGate.review(uiSkinPackageOf(capabilities = listOf("ui.skin")))

        assertTrue(decision.allowed)
    }

    @Test
    fun uiSkinPackageMustDeclareUiSkinCapability() {
        val decision = PluginSecurityGate.review(
            uiSkinPackageOf(capabilities = listOf("resource.read")),
        )

        assertFalse(decision.allowed)
        assertTrue(decision.reason?.contains("ui.skin") == true)
    }

    @Test
    fun uiSkinPackageMustNotDeclareExecutableTools() {
        val decision = PluginSecurityGate.review(
            uiSkinPackageOf(tools = listOf(toolDeclaration())),
        )

        assertFalse(decision.allowed)
        assertTrue(decision.reason?.contains("herramientas ejecutables") == true)
    }

    @Test
    fun emptyToolListIsStillRejectedForToolPlugins() {
        val decision = PluginSecurityGate.review(packageOf(tools = emptyList()))

        assertFalse(decision.allowed)
        assertTrue(decision.reason?.contains("ninguna herramienta") == true)
    }

    @Test
    fun emptyOptionalManifestFieldsDoNotParticipateInSerialization() {
        // Bloqueo de retorno(v2.0.0 Accidente de mercado): una vez que los campos predeterminados opcionales ingresen el JSON de verificación, todos los paquetes de mercado publicados
        // periódico“La firma del editor no coincide con el contenido del complemento”.toolCards usar @EncodeDefault(NEVER) mantener el valor predeterminado
        // No serializado;contributes / uiPanel depender de explicitNulls=false(null no salida).
        val manifest = packageOf().manifest
        val json = io.zer0.common.AppJson.encodeToString(PluginManifest.serializer(), manifest)
        assertFalse("Las tarjetas de herramientas vacías no deben ingresar a la serialización", json.contains("toolCards"))
        assertFalse("null contributes No se permite ingresar a la serialización", json.contains("contributes"))
        assertFalse("null uiPanel No se permite ingresar a la serialización", json.contains("uiPanel"))
    }

    @Test
    fun nonEmptyToolCardsParticipatesInSerializationAndSignature() {
        val signed = signedPackage(toolCards = mapOf("hello" to "cards/hello.html"))
        val json = io.zer0.common.AppJson.encodeToString(
            PluginManifest.serializer(),
            signed.package_.manifest,
        )
        assertTrue("Las tarjetas de herramientas no vacías deben participar en la serialización.", json.contains("\"toolCards\""))
        val envelope = checkNotNull(signed.package_.manifest.signature)
        val trusted = PluginSecurityGate.review(
            signed.package_,
            trustedPublisherKeys = mapOf(envelope.publisherId to envelope.publicKey),
        )
        assertTrue("Los paquetes que contienen toolCards deben poder instalarse: ${trusted.reason}", trusted.isInstallable)
        // La manipulación de las ToolCards debe invalidar la firma (los campos realmente participan en la firma).
        val tampered = signed.package_.copy(
            manifest = signed.package_.manifest.copy(
                toolCards = mapOf("hello" to "cards/evil.html"),
            ),
        )
        val decision = PluginSecurityGate.review(tampered)
        assertEquals(PluginSecurityGate.SignatureStatus.INVALID, decision.signature.status)
    }

    private data class SignedFixture(
        val package_: PluginPackageLoader.LoadedPluginPackage,
    )

    private fun signedPackage(publisherId: String = "publisher.test", toolCards: Map<String, String> = emptyMap()): SignedFixture {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = generator.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val unsignedEnvelope = PluginSignature(
            publisherId = publisherId,
            publicKey = publicKey,
            signature = "",
        )
        val unsignedPackage = packageOf(signature = unsignedEnvelope).let { base ->
            base.copy(manifest = base.manifest.copy(toolCards = toolCards))
        }
        val signatureBytes = Signature.getInstance(PluginSecurityGate.SIGNATURE_ALGORITHM).apply {
            initSign(keyPair.private)
            update(PluginSecurityGate.signaturePayload(unsignedPackage))
        }.sign()
        val signedPackage = unsignedPackage.copy(
            manifest = unsignedPackage.manifest.copy(
                signature = unsignedEnvelope.copy(
                    signature = Base64.getEncoder().encodeToString(signatureBytes),
                ),
            ),
        )
        return SignedFixture(signedPackage)
    }

    private fun packageOf(
        trust: String = "sandboxed",
        capabilities: List<String> = listOf("resource.read"),
        functionName: String = "hello",
        entryCode: String = "function hello() { return 'ok'; }",
        signature: PluginSignature? = null,
        tools: List<ToolDeclaration> = listOf(toolDeclaration(functionName)),
    ): PluginPackageLoader.LoadedPluginPackage = PluginPackageLoader.LoadedPluginPackage(
        manifest = PluginManifest(
            id = "safe-plugin",
            name = "Safe Plugin",
            version = "1.0.0",
            trust = trust,
            capabilities = capabilities,
            tools = tools,
            signature = signature,
        ),
        entryCode = entryCode,
        extraFiles = mapOf("README.md" to "safe"),
    )

    /** Phase 4 Paquete de aspectos declarativo:Sin herramientas, sin entrada JS,Solo existe el recurso skin.json. */
    private fun uiSkinPackageOf(
        capabilities: List<String> = listOf("ui.skin"),
        tools: List<ToolDeclaration> = emptyList(),
    ): PluginPackageLoader.LoadedPluginPackage = PluginPackageLoader.LoadedPluginPackage(
        manifest = PluginManifest(
            id = "skin-plugin",
            name = "Skin Plugin",
            version = "1.0.0",
            kind = PluginSecurityGate.UI_SKIN_KIND,
            trust = "sandboxed",
            capabilities = capabilities,
            tools = tools,
        ),
        entryCode = "",
        extraFiles = mapOf(PluginPackageLoader.UI_SKIN_ENTRY to "{}"),
    )

    private fun toolDeclaration(functionName: String = "hello"): ToolDeclaration = ToolDeclaration(
        name = "hello",
        description = "Say hello",
        parametersJson = "{}",
        requiredJson = "[]",
        functionName = functionName,
    )
}
