package io.zer0.muse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ManualUpdateStartupWiringTest {
    private fun source(name: String): String = listOf(
        Path.of("src/main/java/io/zer0/muse/$name"),
        Path.of("app/src/main/java/io/zer0/muse/$name"),
    ).first(Files::exists).let(Files::readString)

    @Test
    fun `startup never wires an update request`() {
        val application = source("MuseApp.kt")
        assertFalse(application.contains("updateNotifier"))
        assertFalse(application.contains("checkAndNotify"))
        assertFalse(application.contains("checkLatestRelease"))
    }

    @Test
    fun `localized content waits for the persisted language instead of system default`() {
        val activity = source("MainActivity.kt")
        assertTrue(activity.contains("settings.languageFlow.collectAsStateWithLifecycle(initialValue = null)"))
        assertTrue(activity.contains("!startupReady || resolvedLanguage == null"))
        assertTrue(activity.contains("RuntimeLocaleProvider(lang = resolvedLanguage)"))
        assertFalse(activity.contains("settings.languageFlow.collectAsStateWithLifecycle(initialValue = \"system\")"))
    }
}
