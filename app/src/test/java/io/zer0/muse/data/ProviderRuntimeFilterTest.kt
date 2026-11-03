package io.zer0.muse.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.data.routing.UtilityModelBinding
import io.zer0.muse.rag.RagConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proveedor en la ruta de lectura en tiempo de ejecución/Limpieza de modelos y filtrado de desactivación.
 *
 * Portada:
 *  - Proveedores desactivados no participan `get()` elección;Volver cuando todos estén desactivados null(No tomes el primero)
 *  - Después de eliminar al proveedor, se borró la referencia de incrustación en la nube de RAG.(Ya no se deja colgado permanentemente)
 *  - Después de eliminar un solo modelo,El enlace imageGenConfig / ragConfig / modelo auxiliar que apunta a él se borra
 *
 * Nota:Varios casos de prueba de la misma clase de prueba comparten archivos de DataStore,Cada caso de uso utiliza su propio prefijo de identificación de proveedor.,
 * Y escriba la configuración global afirmada como la línea base propia del caso de uso.,Evite la contaminación mutua.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SettingsRepositoryTestApplication::class)
class ProviderRuntimeFilterTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun newRepository(): SettingsRepository = SettingsRepository(context, mockk(relaxed = true))

    // ── enabled filtrar ────────────────────────────────────────

    @Test
    fun get_skipsDisabledActiveProviderAndUsesEnabledOne() = runBlocking {
        val repo = newRepository()
        repo.addProvider(provider(id = "skip-disabled", enabled = false, modelIds = listOf("skip-m-off")))
        repo.addProvider(provider(id = "skip-enabled", enabled = true, modelIds = listOf("skip-m-on")))
        repo.setActiveProvider("skip-disabled")

        val active = repo.get()
        assertNotNull(active)
        assertTrue("Se deben omitir los proveedores de activación desactivados", active!!.enabled)
        assertNull("Los proveedores desactivados no deben recuperarse por identificación", repo.getProviderById("skip-disabled"))
        assertNotNull(repo.getProviderById("skip-enabled"))
    }

    @Test
    fun get_returnsNullWhenEveryProviderIsDisabled() = runBlocking {
        val repo = newRepository()
        repo.addProvider(provider(id = "alloff-1", enabled = false, modelIds = listOf("alloff-m1")))
        repo.addProvider(provider(id = "alloff-2", enabled = false, modelIds = listOf("alloff-m2")))
        repo.setActiveProvider("alloff-1")
        // La inicialización del repositorio inyectará proveedores gratuitos de forma asincrónica,Desactívalo aquí también,Asegúrese de hacer valer la premisa de "deshabilitar todo"
        disableEveryProvider(repo)

        assertNull("Volver cuando todos estén desactivados null,No puedes simplemente tomar el primero.", repo.get())
        assertNull(repo.providerConfigFlow.first())
    }

    @Test
    fun get_resolvesProviderWithDefaultConfiguration() = runBlocking {
        // Configuración predeterminada(No hay activación establecida)El próximo diálogo con el propietario aún debe obtenerse del proveedor.
        val repo = newRepository()
        repo.addProvider(provider(id = "only-enabled", enabled = true, modelIds = listOf("only-model")))
        repo.setActiveProvider("only-enabled")

        val active = repo.get()
        assertNotNull("La configuración predeterminada debe poder obtener el proveedor.", active)
        assertEquals("only-enabled", active?.id)
    }

    // ── Limpieza de referencia RAG después de eliminar el proveedor ────────────────────────────

    @Test
    fun deleteProvider_clearsRagEmbeddingReference() = runBlocking {
        val repo = newRepository()
        repo.addProvider(provider(id = "del-emb", enabled = true, modelIds = listOf("del-emb-model")))
        repo.addProvider(provider(id = "del-chat", enabled = true, modelIds = listOf("del-chat-model")))
        repo.saveRagConfig(
            RagConfig(
                embeddingSource = RagConfig.EmbeddingSource.CLOUD,
                cloudProviderId = "del-emb",
                cloudModel = "del-emb-model",
            ),
        )

        repo.deleteProvider("del-emb")

        val rag = repo.getRagConfig()
        assertEquals("cloudProviderId debe borrarse después de eliminar el proveedor", "", rag.cloudProviderId)
        assertEquals("También se debe borrar el nombre del modelo que entra en vigor con el proveedor.", "", rag.cloudModel)
    }

    // ── Eliminar un solo modelo ─────────────────────────────────────────

    @Test
    fun updateProvider_clearsReferencesToRemovedModel() = runBlocking {
        val repo = newRepository()
        repo.addProvider(
            provider(
                id = "upd-model",
                enabled = true,
                modelIds = listOf("upd-text", "upd-image"),
            ),
        )
        repo.saveImageGenConfig(ImageGenConfig(providerId = "upd-model", modelId = "upd-image"))
        repo.saveUtilityModelBinding(UtilityModelBinding(providerId = "upd-model", modelId = "upd-image"))
        repo.saveRagConfig(
            RagConfig(
                embeddingSource = RagConfig.EmbeddingSource.CLOUD,
                cloudProviderId = "upd-model",
                cloudModel = "upd-image",
            ),
        )
        val before = repo.providersFlow.first().first { it.id == "upd-model" }
        assertEquals(listOf("upd-text", "upd-image"), before.models.map { it.id })

        repo.updateProvider(provider(id = "upd-model", enabled = true, modelIds = listOf("upd-text")))

        assertEquals("Una vez eliminado el modelo de imagen, se debe borrar el ID del modelo.", "", repo.imageGenConfigFlow.first().modelId)
        assertEquals("upd-model", repo.imageGenConfigFlow.first().providerId)
        assertNull("El enlace del modelo auxiliar debe dejarse en blanco al apuntar al modelo eliminado.", repo.utilityModelBindingFlow.first())
        assertEquals("RAG El modelo de incrustación en la nube debe borrarse después de eliminarse.", "", repo.getRagConfig().cloudModel)
    }

    @Test
    fun updateProvider_keepsReferencesWhenModelSurvivesElsewhere() = runBlocking {
        val repo = newRepository()
        repo.addProvider(provider(id = "shared-keep", enabled = true, modelIds = listOf("shared-model", "keep-other")))
        repo.addProvider(provider(id = "shared-other", enabled = true, modelIds = listOf("shared-model")))
        repo.saveImageGenConfig(ImageGenConfig(providerId = "shared-keep", modelId = "shared-model"))

        // shared-keep eliminar shared-model,Pero el otro compartido todavía proporciona un modelo con el mismo nombre.
        repo.updateProvider(provider(id = "shared-keep", enabled = true, modelIds = listOf("keep-other")))

        assertEquals(
            "Conservar la selección cuando todavía exista un modelo con el mismo nombre de otros proveedores,No borrar por error",
            "shared-model",
            repo.imageGenConfigFlow.first().modelId,
        )
    }

    private fun provider(
        id: String,
        enabled: Boolean,
        modelIds: List<String>,
    ): ProviderConfig = ProviderConfig(
        id = id,
        displayName = id,
        // La prueba no requiere una clave real: la apiKey vacía omite el cifrado y descifrado del almacén de claves(Robolectric Ninguno AndroidKeyStore)
        apiKey = "",
        enabled = enabled,
        models = modelIds.map { Model(id = it, providerId = id) },
    )

    /** Desactivar todos los proveedores habilitados en la lista actual(Proveedor integrado con inyección asincrónica en la inicialización). */
    private suspend fun disableEveryProvider(repo: SettingsRepository) {
        repeat(5) {
            repo.providersFlow.first()
                .filter { it.enabled }
                .forEach { repo.updateProvider(it.copy(enabled = false)) }
        }
    }
}
