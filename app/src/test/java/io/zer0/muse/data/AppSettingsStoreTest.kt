package io.zer0.muse.data

import android.content.Context
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
    fun saveLanguage_updatesFlowAndSyncCache() = runBlocking {
        val store = AppSettingsStore(context)
        store.saveLanguage("en")
        assertEquals("en", store.languageFlow.first())
        assertEquals("en", store.getLanguageSync())
    }
}
