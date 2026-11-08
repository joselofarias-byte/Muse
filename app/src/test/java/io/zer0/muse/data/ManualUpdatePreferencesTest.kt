package io.zer0.muse.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SettingsRepositoryTestApplication::class)
class ManualUpdatePreferencesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun repository() = SettingsRepository(context, mockk(relaxed = true))

    @Test
    fun newInstallDefaultsToManualUpdates() = runBlocking {
        context.museSettingsDataStore.edit { it.clear() }
        val settings = repository()

        assertFalse(settings.updateCheckEnabledFlow.first())
        assertNull(settings.latestReleaseInfoFlow.first())
    }

    @Test
    fun existingInstallOrRestoredBackupCannotReenableChecksOrStartupBanner() = runBlocking {
        context.museSettingsDataStore.edit {
            it[booleanPreferencesKey("update_check_enabled")] = true
            it[stringPreferencesKey("latest_release_info_json")] = "cached release from the previous installation"
        }
        val settings = repository()

        assertFalse(settings.updateCheckEnabledFlow.first())
        assertNull(settings.latestReleaseInfoFlow.first())
        settings.saveUpdateCheckEnabled(true)
        assertFalse(context.museSettingsDataStore.data.first()[booleanPreferencesKey("update_check_enabled")]!!)
        assertFalse(repository().updateCheckEnabledFlow.first())
    }
}
