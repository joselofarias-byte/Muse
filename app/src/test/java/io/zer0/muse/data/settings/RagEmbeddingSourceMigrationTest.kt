package io.zer0.muse.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.SettingsRepositoryTestApplication
import io.zer0.muse.data.museSettingsDataStore
import io.zer0.muse.rag.RagConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.0.53 embedding Pruebas de regresión de migración de origen.
 *
 * Error histórico: la lógica de migración está escrita en [SettingsRepository.ragConfigFlow] adentro,Cada vez que se lee, el
 * 「LOCAL_KEYWORD + "El campo de nube no está vacío" se reescribe como NUBE. Lo que escribe el usuario después de hacer clic en "Palabras clave locales" es LOCAL,
 * Siempre se lee como CLOUD,La interfaz muestra que se ha seleccionado local y que la ejecución real se realiza en la nube.,embedding 404 Resultando en una invalidación permanente de la recuperación semántica.
 *
 * La migración ahora converge en una llamada única al inicio [SettingsRepository.migrateRagEmbeddingSourceIfNeeded],
 * La ruta de lectura solo decodifica y no reescribe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SettingsRepositoryTestApplication::class)
class RagEmbeddingSourceMigrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Persistencia correspondiente a KEY_RAG_EMBEDDING_SOURCE_MIGRATED en SettingsRepository key(constante privada,Prueba por nombre). */
    private val migratedKey = booleanPreferencesKey("rag_embedding_source_migrated")

    @Before
    fun resetSettings() {
        // Todas las pruebas en la misma JVM comparten una museSettingsDataStore,Debe estar limpio antes de poder afirmarlo."No migrado"escena.
        runBlocking { context.museSettingsDataStore.edit { it.clear() } }
    }

    private fun newRepository(): SettingsRepository = SettingsRepository(context, mockk(relaxed = true))

    private fun isMigrated(): Boolean = runBlocking { context.museSettingsDataStore.data.first()[migratedKey] == true }

    @Test
    fun unmigratedLegacyCloudConfig_isLiftedToCloudAndFlagSet() = runBlocking {
        val repo = newRepository()
        // v1.0.53 Configuración previamente guardada:JSON No hay ningún campo embeddingSource en,Deserializar por defecto LOCAL_KEYWORD,
        // Pero los campos de la nube los configuran los usuarios. La versión anterior se basaba en la reescritura de la ruta de lectura para ocultar el problema.,Ahora la ruta de lectura debe devolverse sin cambios.
        repo.saveRagConfig(RagConfig().copy(cloudModel = "text-embedding-3-small"))
        assertEquals(
            "La ruta de lectura ya no debe reescribir la configuración.(Lo que se escribe es lo que se lee)",
            RagConfig.EmbeddingSource.LOCAL_KEYWORD,
            repo.getRagConfig().embeddingSource,
        )

        repo.migrateRagEmbeddingSourceIfNeeded()

        assertEquals(
            "La configuración de la nube de los usuarios antiguos debe migrarse a la CLOUD,No se puede bajar silenciosamente a la búsqueda de palabras clave",
            RagConfig.EmbeddingSource.CLOUD,
            repo.getRagConfig().embeddingSource,
        )
        assertTrue("La bandera debe establecerse después de que se complete la migración.", isMigrated())
    }

    @Test
    fun afterMigration_explicitLocalChoiceStaysLocal() = runBlocking {
        val repo = newRepository()
        repo.saveRagConfig(RagConfig().copy(cloudModel = "text-embedding-3-small"))
        repo.migrateRagEmbeddingSourceIfNeeded()
        assertTrue(isMigrated())

        // Luego, el usuario selecciona explícitamente "palabras clave locales".:cloudModel Sigue siendo el estado intermedio del antiguo valor.(UI Antes de limpiar).
        repo.saveRagConfig(
            RagConfig().copy(
                embeddingSource = RagConfig.EmbeddingSource.LOCAL_KEYWORD,
                cloudModel = "text-embedding-3-small",
            ),
        )

        assertEquals(
            "El clavo directo de este error.:Una vez completada la migración,Los usuarios deben leer local al seleccionar local",
            RagConfig.EmbeddingSource.LOCAL_KEYWORD,
            repo.getRagConfig().embeddingSource,
        )
    }

    @Test
    fun unmigratedEmptyCloudFields_keepsLocalKeyword() = runBlocking {
        val repo = newRepository()
        // Nuevos usuarios que no han configurado la nube antes/Configuración predeterminada:No debe confundirse con CLOUD(De lo contrario volverá a embedding 404).
        repo.saveRagConfig(RagConfig())

        repo.migrateRagEmbeddingSourceIfNeeded()

        assertEquals(
            "Cuando el campo de nubes está completamente vacío, no se puede levantar. CLOUD",
            RagConfig.EmbeddingSource.LOCAL_KEYWORD,
            repo.getRagConfig().embeddingSource,
        )
        assertTrue(isMigrated())
    }

    @Test
    fun migrationIsIdempotent_secondCallDoesNotWrite() = runBlocking {
        val repo = newRepository()
        repo.saveRagConfig(RagConfig().copy(cloudModel = "text-embedding-3-small"))

        repo.migrateRagEmbeddingSourceIfNeeded()
        val afterFirst = repo.getRagConfig()
        // Otras migraciones de inicio escriben claves ajenas a RAG en segundo plano.
        val ragKeys = setOf(stringPreferencesKey("rag_config_json"), migratedKey)
        val ragPreferences = context.museSettingsDataStore.data
            .map { prefs -> prefs.asMap().filterKeys { it in ragKeys } }
            .distinctUntilChanged()
        val prefsAfterFirst = ragPreferences.first()

        // La segunda llamada no debe cambiar las preferencias RAG ni emitir nuevos valores.
        val emissions = java.util.concurrent.atomic.AtomicInteger(0)
        val job = launch(Dispatchers.Default) {
            ragPreferences.collect { emissions.incrementAndGet() }
        }
        withTimeout(5_000) { while (emissions.get() == 0) delay(10) }
        val baseline = emissions.get()

        repo.migrateRagEmbeddingSourceIfNeeded()
        delay(1_000)
        val afterSecond = emissions.get()
        job.cancel()

        assertEquals("El resultado de la segunda convocatoria deberá ser coherente con el de la primera convocatoria.", afterFirst, repo.getRagConfig())
        assertEquals(
            "La configuración no se puede cambiar durante la segunda llamada.",
            prefsAfterFirst,
            ragPreferences.first(),
        )
        assertEquals("La segunda llamada no debe emitir cambios en las preferencias RAG", baseline, afterSecond)
    }
}
