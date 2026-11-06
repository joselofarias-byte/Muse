package io.zer0.muse.data.plugin.market

import io.zer0.common.AppJson
import io.zer0.muse.data.plugin.PluginPackageLoader
import io.zer0.muse.data.plugin.PluginSecurityGate
import io.zer0.muse.ui.theme.BubbleSkinValidator
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Utilice el validador propio de la aplicación para verificar la aceptación `tools/market-signer` productos de mercado producidos.
 *
 * Este es el criterio autorizado para "si la herramienta de firma es coherente palabra por palabra con el algoritmo de la aplicación": siempre que pase esta prueba,
 * La aplicación definitivamente aceptará directorios en línea y paquetes de complementos. El producto es generado por el script en
 * `app/build/market-signing/out`(Directorio de compilación, no incluido en la biblioteca); omitir si falta,
 * Por lo tanto, la CI no fallará sin artefactos firmados localmente.
 */
class MarketSigningFixtureTest {

    private val fixtureRoot = File("build/market-signing/out")

    @Serializable
    private data class CatalogKey(
        val keyId: String,
        val publicKey: String,
        val fingerprint: String,
    )

    @Serializable
    private data class PublicKeys(
        val catalog: CatalogKey,
        val publishers: Map<String, String>,
    )

    @Test
    fun signedArtifactsFromOfflineToolAreAcceptedByApp() {
        val keysFile = File(fixtureRoot, "public-keys.json")
        val catalogFile = File(fixtureRoot, "signed-catalog.json")
        assumeTrue("Producto de firma no encontrado, omitir (es necesario ejecutar tools/market-signer/sign.py primero)", keysFile.isFile && catalogFile.isFile)

        val keys = AppJson.decodeFromString<PublicKeys>(keysFile.readText())
        val signed = AppJson.decodeFromString<SignedPluginCatalog>(catalogFile.readText())

        val verification = PluginCatalogVerifier.verify(
            signed = signed,
            trustedKeys = mapOf(keys.catalog.keyId to keys.catalog.publicKey),
            nowEpochMs = System.currentTimeMillis(),
        )
        assertTrue("Error en la verificación de la firma del directorio: ${verification.reason}", verification.valid)
        assertFalse(signed.payload.entries.isEmpty())

        signed.payload.entries.forEach { entry ->
            val artifactFile = File(File(fixtureRoot, "packages"), "${entry.id}-${entry.version}.muse-plugin")
            assertTrue("Paquete de complementos faltante: ${artifactFile.name}", artifactFile.isFile)
            val bytes = artifactFile.readBytes()
            assertEquals("El tamaño del paquete no coincide con el directorio: ${entry.id}", entry.artifactBytes, bytes.size.toLong())
            assertEquals("El resumen del paquete no coincide con el directorio: ${entry.id}", entry.artifactSha256, sha256Hex(bytes))

            val loaded = PluginPackageLoader.loadFromZip(bytes)
            val package_ = (loaded as? PluginPackageLoader.Result.Ok)?.package_
            assertTrue("Error al cargar el paquete: $loaded", package_ != null)
            val decision = PluginSecurityGate.review(package_!!, keys.publishers)

            assertTrue("Firma no confiable: ${decision.reason}", decision.isInstallable)
            assertEquals(PluginSecurityGate.SignatureStatus.VALID_TRUSTED, decision.signature.status)
            assertEquals("El resumen del contenido no coincide con la tabla de contenidos.: ${entry.id}", entry.manifestSha256, decision.preview.contentSha256)
            assertEquals(entry.id, decision.preview.id)
            assertEquals(entry.version, decision.preview.version)
            assertEquals(entry.publisherKeyFingerprint, decision.preview.publisherKeyFingerprint)
        }
    }

    @Test
    fun tamperedPackageIsRejectedEvenWithTrustedPublisher() {
        val keysFile = File(fixtureRoot, "public-keys.json")
        val catalogFile = File(fixtureRoot, "signed-catalog.json")
        assumeTrue("Producto exclusivo no encontrado, omitir", keysFile.isFile && catalogFile.isFile)

        val keys = AppJson.decodeFromString<PublicKeys>(keysFile.readText())
        val signed = AppJson.decodeFromString<SignedPluginCatalog>(catalogFile.readText())
        val entry = signed.payload.entries.first()
        val bytes = File(File(fixtureRoot, "packages"), "${entry.id}-${entry.version}.muse-plugin").readBytes()

        // Comparación negativa: el emisor confiable + el código de entrada modificado aún deben fallar; de lo contrario, el paso anterior no tiene sentido.
        val loaded = PluginPackageLoader.loadFromZip(bytes) as PluginPackageLoader.Result.Ok
        val tampered = loaded.package_.copy(entryCode = loaded.package_.entryCode + "\n// tampered\n")
        val decision = PluginSecurityGate.review(tampered, keys.publishers)

        assertFalse(decision.isInstallable)
        assertEquals(PluginSecurityGate.SignatureStatus.INVALID, decision.signature.status)
    }

    /**
     * La clave pública del lado de publicación debe ser coherente con la raíz de confianza del directorio oficial integrada en el paquete de instalación.
     *
     * Esta es una prueba de control para "las aplicaciones publicadas pueden verificar los directorios en línea": usar la clave privada incorrecta para firmar el directorio fallará aquí.
     * En lugar de esperar a que el usuario vea la "clave de firma de directorio desconocida" en el dispositivo.
     */
    @Test
    fun publishedCatalogIsSignedByBuiltInOfficialRoot() {
        val keysFile = File(fixtureRoot, "public-keys.json")
        val catalogFile = File(fixtureRoot, "signed-catalog.json")
        assumeTrue("Producto exclusivo no encontrado, omitir", keysFile.isFile && catalogFile.isFile)

        val keys = AppJson.decodeFromString<PublicKeys>(keysFile.readText())
        val signed = AppJson.decodeFromString<SignedPluginCatalog>(catalogFile.readText())

        assertEquals(PluginMarketDefaults.CATALOG_ROOT_KEY_ID, keys.catalog.keyId)
        assertEquals(PluginMarketDefaults.CATALOG_ROOT_PUBLIC_KEY, keys.catalog.publicKey)
        assertEquals(PluginMarketDefaults.CATALOG_ROOT_FINGERPRINT, keys.catalog.fingerprint)
        assertEquals(PluginMarketDefaults.CATALOG_ID, signed.payload.catalogId)

        // Utilice únicamente la verificación de firma de raíz de confianza incorporada en el paquete de instalación para demostrar que la configuración del mercado lista para usar está disponible.
        val verification = PluginCatalogVerifier.verify(
            signed = signed,
            trustedKeys = PluginMarketDefaults.catalogRootKeys,
            nowEpochMs = System.currentTimeMillis(),
        )
        assertTrue("La raíz de confianza integrada no puede verificar el directorio publicado: ${verification.reason}", verification.valid)
    }

    /**
     * El paquete de máscara debe ser analizable por el modelo de máscara del host y pasar la verificación del host (rango de valores + contraste WCAG).
     *
     * La piel son datos declarativos. Si la verificación del host falla, la burbuja incorporada se revertirá silenciosamente; si no se comprueba antes de publicar,
     * Después de que el usuario lo instale, sentirá que "la instalación es inútil". La misma verificación de contraste se realiza en el lado del script de publicación; aquí está el lado de la aplicación.
     */
    @Test
    fun skinPackagesParseAndPassHostValidation() {
        val keysFile = File(fixtureRoot, "public-keys.json")
        val catalogFile = File(fixtureRoot, "signed-catalog.json")
        assumeTrue("Producto exclusivo no encontrado, omitir", keysFile.isFile && catalogFile.isFile)

        val keys = AppJson.decodeFromString<PublicKeys>(keysFile.readText())
        val signed = AppJson.decodeFromString<SignedPluginCatalog>(catalogFile.readText())
        val skinEntries = signed.payload.entries.filter {
            PluginSecurityGate.UI_SKIN_CAPABILITY in it.capabilities
        }
        assumeTrue("No hay ningún paquete de skin en el directorio, omítalo", skinEntries.isNotEmpty())

        skinEntries.forEach { entry ->
            val bytes = File(File(fixtureRoot, "packages"), "${entry.id}-${entry.version}.muse-plugin").readBytes()
            val loaded = PluginPackageLoader.loadFromZip(bytes) as PluginPackageLoader.Result.Ok
            val skin = loaded.package_.skin
            assertTrue("El paquete de piel no resolvió la piel.: ${entry.id}", skin != null)
            assertEquals("Los skins no deben llevar entradas ejecutables.: ${entry.id}", "", loaded.package_.entryCode)
            val errors = BubbleSkinValidator.validate(skin!!)
            assertTrue("La máscara no pasó la verificación del host.: ${entry.id} $errors", errors.isEmpty())
            assertEquals(entry.id, skin.id)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
