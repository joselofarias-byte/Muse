package io.zer0.muse.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppSettingsStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetLanguagePreferences() = runBlocking {
        context.museSettingsDataStore.edit { it.clear() }
        context.getSharedPreferences("muse_language_cache", Context.MODE_PRIVATE).edit().clear().commit()
        Unit
    }

    @Test
    fun language_defaultsToSpanish() = runBlocking {
        val store = AppSettingsStore(context)
        assertEquals("es", store.getLanguageSync())
        assertEquals("es", store.languageFlow.first())
    }

    @Test
    fun explicitSystem_updatesFlowAndSyncCache() = runBlocking {
        val store = AppSettingsStore(context)
        store.saveLanguage("system")
        assertEquals("system", store.languageFlow.first())
        assertEquals("system", store.getLanguageSync())
    }

    @Test
    fun existingLanguage_replacesStaleSyncCache() = runBlocking {
        context.museSettingsDataStore.edit { it[stringPreferencesKey("language")] = "en" }
        val cache = context.getSharedPreferences("muse_language_cache", Context.MODE_PRIVATE)
        cache.edit().putString("language", "system").commit()

        val store = AppSettingsStore(context)
        assertEquals("en", store.languageFlow.first())
        withTimeout(5_000) {
            while (cache.getString("language", null) != "en") delay(10)
        }
        assertEquals("en", store.getLanguageSync())
    }

    @Test
    fun existingSystemCache_isPreservedInFlowAndSyncCache() = runBlocking {
        val cache = context.getSharedPreferences("muse_language_cache", Context.MODE_PRIVATE)
        cache.edit().putString("language", "system").commit()
        val store = AppSettingsStore(context)
        assertEquals("system", store.getLanguageSync())
        assertEquals("system", store.languageFlow.first())
        assertEquals("system", store.getLanguageSync())
    }

    @Test
    fun saveLanguage_updatesFlowAndSyncCache() = runBlocking {
        val store = AppSettingsStore(context)
        store.saveLanguage("en")
        assertEquals("en", store.languageFlow.first())
        assertEquals("en", store.getLanguageSync())
    }
}
