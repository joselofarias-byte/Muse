package io.zer0.muse.data.plugin.market

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Prueba de configuración del mercado de complementos: directorio oficial incorporado, se puede sobrescribir pero la raíz de confianza no se puede debilitar.
 *
 * Siga la convención de prueba de la Tienda: cada caso de uso primero borra la cobertura y la raíz de confianza personalizada que dejó el caso de uso anterior para garantizar que el orden sea irrelevante.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginMarketSettingsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val settings get() = PluginMarketSettings(context)

    private fun reset() = runBlocking {
        settings.resetCatalogUrl()
        settings.catalogRootKeys().keys
            .filter { it !in PluginMarketDefaults.catalogRootKeys }
            .forEach { settings.removeCatalogRootKey(it) }
    }

    private fun generatedPublicKey(): String {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        return Base64.getEncoder().encodeToString(generator.generateKeyPair().public.encoded)
    }

    @Test
    fun marketWorksOutOfTheBoxWithBuiltInOfficialCatalog() = runBlocking {
        reset()

        assertEquals(PluginMarketDefaults.CATALOG_URL, settings.catalogUrl())
        assertEquals("", settings.catalogUrlOverride())
        assertEquals(PluginMarketDefaults.catalogRootKeys, settings.catalogRootKeys())
    }

    // ── P0-9: La raíz del directorio de confianza es revocable ──

    @Test
    fun removeCatalogRootKey_removesUserRootOnly() = runBlocking {
        reset()

        settings.saveCatalogRootKey("user-root", generatedPublicKey())
        assertTrue(settings.catalogRootKeys().containsKey("user-root"))

        settings.removeCatalogRootKey("user-root")

        assertEquals("Después de revocar la raíz de confianza personalizada, solo debe permanecer la raíz integrada", PluginMarketDefaults.catalogRootKeys, settings.catalogRootKeys())
    }

    @Test
    fun removeCatalogRootKey_cannotRemoveBuiltInRoot() = runBlocking {
        reset()

        val builtinId = PluginMarketDefaults.catalogRootKeys.keys.first()
        settings.removeCatalogRootKey(builtinId)

        assertEquals("La raíz de confianza oficial incorporada no se puede eliminar", PluginMarketDefaults.catalogRootKeys, settings.catalogRootKeys())
    }

    @Test
    fun userOverrideWinsAndCanBeResetToOfficial() = runBlocking {
        reset()

        settings.saveCatalogUrl("https://mirror.example.com/catalog.json")
        assertEquals("https://mirror.example.com/catalog.json", settings.catalogUrl())
        assertEquals("https://mirror.example.com/catalog.json", settings.catalogUrlOverride())
        // Sobrescribir el directorio no afecta la raíz de confianza oficial: siempre participa en la verificación de firmas.
        assertEquals(PluginMarketDefaults.catalogRootKeys, settings.catalogRootKeys())

        settings.resetCatalogUrl()
        assertEquals(PluginMarketDefaults.CATALOG_URL, settings.catalogUrl())
    }

    @Test
    fun builtInRootKeyCannotBeOverwrittenOrRemoved() = runBlocking {
        reset()

        val replaced = settings.saveCatalogRootKey(
            PluginMarketDefaults.CATALOG_ROOT_KEY_ID,
            generatedPublicKey(),
        )
        assertTrue("El keyId incorporado debe denegar la escritura.", replaced.isFailure)

        settings.removeCatalogRootKey(PluginMarketDefaults.CATALOG_ROOT_KEY_ID)
        assertEquals(PluginMarketDefaults.catalogRootKeys, settings.catalogRootKeys())
    }

    @Test
    fun userRootKeysAreMergedWithBuiltInKeys() = runBlocking {
        reset()

        val custom = generatedPublicKey()
        assertTrue(settings.saveCatalogRootKey("my-root", custom).isSuccess)

        val effective = settings.catalogRootKeys()
        assertEquals(custom, effective["my-root"])
        assertEquals(PluginMarketDefaults.CATALOG_ROOT_PUBLIC_KEY, effective[PluginMarketDefaults.CATALOG_ROOT_KEY_ID])

        settings.removeCatalogRootKey("my-root")
        assertFalse(settings.catalogRootKeys().containsKey("my-root"))
        assertEquals(PluginMarketDefaults.catalogRootKeys, settings.catalogRootKeys())
    }

    @Test
    fun invalidPublicKeyIsRejectedAndKeepsTrustRootsIntact() = runBlocking {
        reset()

        val result = settings.saveCatalogRootKey("bad-root", "not-a-key")
        assertTrue(result.isFailure)
        assertEquals(PluginMarketDefaults.catalogRootKeys, settings.catalogRootKeys())
    }

    @Test
    fun exposeUrlFlowMatchesSuspendAccessor() = runBlocking {
        reset()
        settings.saveCatalogUrl("https://mirror.example.com/catalog.json")

        assertEquals(settings.catalogUrl(), settings.catalogUrlFlow.first())
        settings.resetCatalogUrl()
    }
}
