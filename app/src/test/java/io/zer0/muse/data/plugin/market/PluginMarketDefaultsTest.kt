package io.zer0.muse.data.plugin.market

import io.zer0.muse.data.plugin.PluginSecurityGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Autoprueba incorporada para constantes del directorio oficial.
 *
 * El síntoma de valores integrados escritos incorrectamente es "se rechazan todos los directorios del cliente" y este tipo de error no se puede ver durante la compilación.
 * Por tanto, aquí se fija la legalidad, forma canónica y huella digital de la clave pública: cualquier modificación manual debe hacer que esta prueba falle.
 */
class PluginMarketDefaultsTest {

    @Test
    fun builtInCatalogUrlIsHttpsOnOfficialHost() {
        val url = PluginMarketDefaults.CATALOG_URL
        assertTrue("debe ser https", url.startsWith("https://"))
        assertEquals("https://museai.ltd/muse-market/signed-catalog.json", url)
    }

    @Test
    fun builtInRootKeyIdMatchesBakedKeyMap() {
        assertEquals(
            mapOf(PluginMarketDefaults.CATALOG_ROOT_KEY_ID to PluginMarketDefaults.CATALOG_ROOT_PUBLIC_KEY),
            PluginMarketDefaults.catalogRootKeys,
        )
    }

    @Test
    fun builtInRootKeyIsCanonicalP256PublicKeyWithExpectedFingerprint() {
        val encoded = PluginMarketDefaults.CATALOG_ROOT_PUBLIC_KEY
        // App escribir/Se requiere Canonical Base64 al comparar raíces de confianza; los valores integrados ya deben estar en forma canónica; de lo contrario, la canonicalización produce cadenas diferentes.
        val canonical = PluginSecurityGate.canonicalPublicKey(encoded).getOrThrow()
        assertEquals("La clave pública incorporada debe ser un estándar canónico. Base64", encoded, canonical)

        val der = Base64.getDecoder().decode(canonical)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        val ecKey = key as? ECPublicKey ?: error("La clave pública incorporada no es una clave pública EC")
        assertEquals("debe ser P-256", 256, ecKey.params.curve.field.fieldSize)

        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(der)
            .joinToString("") { "%02x".format(it) }
        assertEquals(PluginMarketDefaults.CATALOG_ROOT_FINGERPRINT, fingerprint)
    }
}
