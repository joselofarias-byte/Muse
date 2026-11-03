package io.zer0.muse.rag

import io.zer0.ai.ProviderConfigStore
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.common.Logger
import io.zer0.muse.data.ProviderConfigChangeBus
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Embedding Provider Invalidación de huellas dactilares de caché y visibilidad degradada.
 *
 * Cubre tres cosas:
 *  1. La misma configuración getsProvider llega a la misma instancia varias veces(El almacenamiento en caché realmente funciona)
 *  2. Provider Cualquier cambio en apiKey/baseUrl/model/id reconstruye la instancia
 *  3. Recurrir a la implementación de palabras clave locales cuando el proveedor de la nube no esté disponible,Y dejar registro de advertencia y estado degradado
 */
class EmbeddingServiceCacheTest {

    private val client = OkHttpClient()

    @After
    fun tearDown() {
        Logger.sink = null
    }

    // ── huella digital ────────────────────────────────────────────────

    @Test
    fun sameConfigReusesProviderInstance() = runBlocking {
        val store = FakeProviderStore(listOf(openAiProvider()))
        val service = EmbeddingService(store, client)
        val config = cloudConfig()

        val first = service.getProvider(config)
        val second = service.getProvider(config)

        assertSame("Se debe reutilizar la misma instancia del Proveedor cuando la configuración permanece sin cambios.", first, second)
        assertEquals("cloud-openai-main", first.id)
    }

    @Test
    fun changingApiKeyRebuildsProvider() = runBlocking {
        val store = FakeProviderStore(listOf(openAiProvider()))
        val service = EmbeddingService(store, client)
        val config = cloudConfig()
        val first = service.getProvider(config)

        store.providers = listOf(openAiProvider(apiKey = "sk-rotated-key"))

        assertNotSame("apiKey Debe reconstruirse después de los cambios. Provider", first, service.getProvider(config))
    }

    @Test
    fun changingBaseUrlRebuildsProvider() = runBlocking {
        val store = FakeProviderStore(listOf(openAiProvider()))
        val service = EmbeddingService(store, client)
        val config = cloudConfig()
        val first = service.getProvider(config)

        store.providers = listOf(openAiProvider(baseUrl = "https://relay.example.com/v1"))

        assertNotSame("baseUrl Debe reconstruirse después de los cambios. Provider", first, service.getProvider(config))
    }

    @Test
    fun changingModelRebuildsProvider() = runBlocking {
        val store = FakeProviderStore(listOf(openAiProvider()))
        val service = EmbeddingService(store, client)
        val first = service.getProvider(cloudConfig(cloudModel = "text-embedding-3-small"))

        val second = service.getProvider(cloudConfig(cloudModel = "text-embedding-3-large"))

        assertNotSame("embedding El modelo debe reconstruirse después de los cambios. Provider", first, second)
        assertEquals("text-embedding-3-large", second.modelName)
    }

    @Test
    fun providerConfigChangeNotificationInvalidatesCache() = runBlocking {
        val store = FakeProviderStore(listOf(openAiProvider()))
        val service = EmbeddingService(store, client)
        val config = cloudConfig()
        val first = service.getProvider(config)

        // La huella digital de configuración permanece completamente sin cambios,La caché sólo puede invalidarse mediante la difusión de la capa de datos.
        ProviderConfigChangeBus.notifyChanged()

        val second = service.getProvider(config)
        assertNotSame("Los cambios de configuración deben reconstruirse después de la transmisión. Provider", first, second)
    }

    // ── Visibilidad de degradación ──────────────────────────────────────────

    @Test
    fun danglingCloudProviderIdFallsBackToKeywordAndWarns() = runBlocking {
        val store = FakeProviderStore(listOf(openAiProvider()))
        val service = EmbeddingService(store, client)
        val warnings = mutableListOf<String>()
        Logger.sink = { level, tag, message, _ ->
            if (level == "WARN" && tag == "EmbeddingService") warnings.add(message)
        }

        val provider = service.getProvider(cloudConfig(cloudProviderId = "provider-that-was-deleted"))

        assertTrue("El cloudProviderId pendiente debe recurrir a la implementación de palabras clave locales", provider is LocalKeywordEmbeddingProvider)
        assertTrue(
            "Las rebajas deben dejar registros de advertencia rastreables,real:$warnings",
            warnings.any { it.contains("provider-that-was-deleted") && it.contains("no existe") },
        )
        val state = service.activeBackend.value
        assertNotNull(state)
        assertTrue("activeBackend Debe estar marcado para bajar de categoría", state!!.degraded)
        assertTrue("El motivo de la degradación debe poder mostrarse", state.reason.orEmpty().contains("provider-that-was-deleted"))
        assertEquals("local-keyword", state.backendId)
    }

    @Test
    fun disabledCloudProviderFallsBackToKeywordAndWarns() = runBlocking {
        val store = FakeProviderStore(listOf(openAiProvider(enabled = false)))
        val service = EmbeddingService(store, client)
        val warnings = mutableListOf<String>()
        Logger.sink = { level, tag, message, _ ->
            if (level == "WARN" && tag == "EmbeddingService") warnings.add(message)
        }

        val provider = service.getProvider(cloudConfig(cloudProviderId = "openai-main"))

        assertTrue("Los proveedores desactivados ya no podrán utilizarse embedding", provider is LocalKeywordEmbeddingProvider)
        assertTrue("Debe haber un registro de advertencia para desactivar la degradación,real:$warnings", warnings.any { it.contains("Desactivado") })
    }

    @Test
    fun blankApiKeyFallsBackToKeywordAndWarns() = runBlocking {
        val store = FakeProviderStore(listOf(openAiProvider(apiKey = "")))
        val service = EmbeddingService(store, client)
        val warnings = mutableListOf<String>()
        Logger.sink = { level, tag, message, _ ->
            if (level == "WARN" && tag == "EmbeddingService") warnings.add(message)
        }

        val provider = service.getProvider(cloudConfig())

        assertTrue(provider is LocalKeywordEmbeddingProvider)
        assertTrue("API Key La degradación provocada por estar vacío debe tener un registro de advertencia.,real:$warnings", warnings.any { it.contains("API Key esta vacio") })
    }

    @Test
    fun localKeywordSourceIsNotReportedAsDegraded() = runBlocking {
        val store = FakeProviderStore(emptyList())
        val service = EmbeddingService(store, client)

        val provider = service.getProvider(RagConfig(embeddingSource = RagConfig.EmbeddingSource.LOCAL_KEYWORD))

        assertTrue(provider is LocalKeywordEmbeddingProvider)
        val state = service.activeBackend.value
        assertNotNull(state)
        assertFalse("La selección explícita de palabras clave locales por parte del usuario no cuenta como degradación", state!!.degraded)
    }

    // ── fixtures ────────────────────────────────────────────

    private fun cloudConfig(
        cloudProviderId: String = "openai-main",
        cloudModel: String = "text-embedding-3-small",
    ): RagConfig = RagConfig(
        embeddingSource = RagConfig.EmbeddingSource.CLOUD,
        cloudProviderId = cloudProviderId,
        cloudModel = cloudModel,
    )

    private fun openAiProvider(
        apiKey: String = "sk-test-key",
        baseUrl: String = "https://api.openai.com/v1",
        enabled: Boolean = true,
    ): ProviderConfig = ProviderConfig(
        id = "openai-main",
        displayName = "OpenAI",
        type = ProviderType.OPENAI,
        baseUrl = baseUrl,
        apiKey = apiKey,
        enabled = enabled,
    )

    private class FakeProviderStore(var providers: List<ProviderConfig>) : ProviderConfigStore {
        override suspend fun get(): ProviderConfig? = providers.firstOrNull { it.enabled }

        override suspend fun getAllProviders(): List<ProviderConfig> = providers
    }
}
