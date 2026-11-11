package io.zer0.muse.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppSettingsStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun language_defaultsToSpanish() = runBlocking {
        val store = AppSettingsStore(context)
        assertEquals("es", store.languageFlow.first())
        assertEquals("es", store.getLanguageSync())
    }

    @Test
    fun explicitlySelectedSystem_isPreserved() = runBlocking {
        val store = AppSettingsStore(context)
        store.saveLanguage("system")
        assertEquals("system", store.languageFlow.first())
        assertEquals("system", store.getLanguageSync())
        assertEquals("system", AppSettingsStore(context).languageFlow.first())
        assertEquals("system", AppSettingsStore(context).getLanguageSync())
    }

    @Test
    fun migration_replacesImplicitSystemCacheWithSpanish() = runBlocking {
        val store = AppSettingsStore(context)
        context.museSettingsDataStore.edit { it.remove(stringPreferencesKey("language")) }
        context.getSharedPreferences("muse_language_cache", Context.MODE_PRIVATE)
            .edit().putString("language", "system").commit()

        store.migrateLanguageCache()

        assertEquals("es", store.languageFlow.first())
        assertEquals("es", store.getLanguageSync())
    }

    @Test
    fun migration_preservesExplicitSystemAndRebuildsMissingCache() = runBlocking {
        val store = AppSettingsStore(context)
        store.saveLanguage("system")
        context.getSharedPreferences("muse_language_cache", Context.MODE_PRIVATE)
            .edit().remove("language").commit()

        store.migrateLanguageCache()

        assertEquals("system", store.languageFlow.first())
        assertEquals("system", store.getLanguageSync())
    }

    @Test
    fun migration_afterExplicitSystemSaveAcrossInstances_preservesSelection() = runBlocking {
        val migratingStore = AppSettingsStore(context)
        val savingStore = AppSettingsStore(context)
        migratingStore.migrateLanguageCache()
        savingStore.saveLanguage("system")

        migratingStore.migrateLanguageCache()

        assertEquals("system", savingStore.languageFlow.first())
        assertEquals("system", migratingStore.getLanguageSync())
        assertEquals("system", savingStore.getLanguageSync())
    }

    @Test
    fun saveLanguage_updatesFlowAndSyncCache() = runBlocking {
        val store = AppSettingsStore(context)
        store.saveLanguage("en")
        assertEquals("en", store.languageFlow.first())
        assertEquals("en", store.getLanguageSync())
    }
}
