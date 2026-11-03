package io.zer0.muse.data.preset

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelCatalog
import io.zer0.ai.core.ModelCatalogEntry
import io.zer0.ai.core.ToolUseSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.6.0:La lista de modelos va al directorio de la nube. —— Codex espera“autoridad de directorio”Los modelos de proveedores ya no están preestablecidos en la aplicación.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelCatalogCloudBridgeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun codexCloudCatalog(): ModelCatalog = ModelCatalog(
        schemaVersion = 1,
        publishedAt = "2026-09-30T06:11:52",
        providers = mapOf(
            "openai-codex-oauth" to mapOf(
                "gpt-6.1-sol" to ModelCatalogEntry(
                    name = "GPT-6.1 Sol",
                    context = 400_000,
                    maxOutput = 128_000,
                    image = true,
                    reasoning = true,
                    toolUse = ToolUseSpec(supportsTools = true),
                ),
                "gpt-6-luna" to ModelCatalogEntry(
                    name = "GPT-6 Luna",
                    context = 400_000,
                    image = true,
                    reasoning = true,
                    toolUse = ToolUseSpec(supportsTools = true),
                ),
            ),
        ),
    )

    @Test
    fun codexPreset_hasNoBuiltInModels() {
        // No hay un modelo de Codex preestablecido en la aplicación (la lista está sujeta al directorio de la nube).
        val preset = PresetProviders(context).bySpecId("openai_codex")
        assertTrue(preset != null)
        assertEquals(0, preset!!.models.size)
    }

    @Test
    fun codexPreset_exposesCloudCatalogModels() {
        // Después de inyectar en el directorio de la nube, la página de selección predeterminada debería ver el modelo Codex en el directorio.
        val store = ModelCatalogStore(context, cloudCatalog = { codexCloudCatalog() })
        val preset = PresetProviders(context, store).bySpecId("openai_codex")
        assertTrue(preset != null)
        val ids = preset!!.models.map { it.id }.toSet()
        assertTrue("Se espera que contenga gpt-6.1-sol, real=$ids", ids.contains("gpt-6.1-sol"))
        assertTrue("Se espera que contenga gpt-6-luna, real=$ids", ids.contains("gpt-6-luna"))
    }

    @Test
    fun codexMerge_discardsStalePersistedModels() {
        // Los modelos antiguos retirados pueden permanecer en la configuración local de los usuarios que han iniciado sesión;
        // La lista de proveedores autorizados del directorio está determinada completamente por el directorio en la nube y los modelos más antiguos deben descartarse.
        val store = ModelCatalogStore(context, cloudCatalog = { codexCloudCatalog() })
        val stale = listOf(
            Model(id = "gpt-5.2-codex", name = "GPT-5.2 Codex", providerId = "preset_openai_codex"),
            Model(id = "gpt-5.2", name = "GPT-5.2", providerId = "preset_openai_codex"),
        )
        val projected = store.mergeIntoModels(
            providerId = "openai-codex-oauth",
            models = stale,
            runtimeProviderId = "preset_openai_codex",
        )
        val ids = projected.map { it.id }.toSet()
        assertFalse("Los modelos retirados deben descartarse, los actuales.=$ids", ids.contains("gpt-5.2-codex"))
        assertFalse(ids.contains("gpt-5.2"))
        assertTrue(ids.contains("gpt-6.1-sol"))
        // Las identidades en tiempo de ejecución conservan el prefijo preset_ para facilitar la búsqueda por ID de proveedor.
        assertEquals("preset_openai_codex", projected.first { it.id == "gpt-6.1-sol" }.providerId)
    }

    @Test
    fun nonAuthoritativeProvider_catalogOnlyOverridesFieldsNotMembership() {
        // Proveedores ordinarios: el catálogo solo cubre campos y no se agregan nuevos modelos en función del catálogo (para evitar completar todos los modelos históricos).
        val store = ModelCatalogStore(context, cloudCatalog = { codexCloudCatalog() })
        val own = listOf(Model(id = "gpt-4o", name = "GPT-4o", providerId = "preset_openai"))
        val projected = store.mergeIntoModels(
            providerId = "openai",
            models = own,
            runtimeProviderId = "preset_openai",
        )
        assertEquals(listOf("gpt-4o"), projected.map { it.id })
    }
}
