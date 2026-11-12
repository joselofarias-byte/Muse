package io.zer0.muse.schedule

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.R
import io.zer0.muse.data.DailySummarySnapshot
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DailySummaryLocalizationTest {
    @Test
    fun fallbackUsesSelectedLanguageAndPersistsProvenance() = runTest {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val chinese = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        })
        val spanish = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("es-ES"))
        })
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.languageFlow } returns flowOf("es")
        coEvery { settings.claimDailySummarySlot(any()) } returns true
        coEvery { settings.isDailySummarySlotCompleted(any()) } returns true
        every { settings.dailySummaryFlow } returns flowOf(DailySummarySnapshot("2026-11-12", "English summary", "en-US"))
        val service = DailySummaryService(settings, mockk(relaxed = true), mockk(relaxed = true), null, null, chinese)

        assertTrue(service.generateForSlot("2026-11-12#0900", LocalDate.of(2026, 11, 12), false))

        coVerify { settings.saveDailySummary("2026-11-12", spanish.getString(R.string.daily_summary_light_fallback), "es-ES") }
        coVerify { settings.releaseDailySummarySlot("2026-11-12#0900#es-ES") }
        coVerify { settings.completeDailySummarySlot("2026-11-12#0900#es-ES") }
    }

    @Test
    fun cacheNeverDisplaysUnknownOrDifferentLanguage() {
        assertNull(DailySummarySnapshot("2026-11-12", "旧摘要").textForLocale("es-ES"))
        val summary = DailySummarySnapshot("2026-11-12", "Resumen", "es-ES")
        assertEquals("Resumen", summary.textForLocale("es-ES"))
        assertNull(summary.textForLocale("en-US"))
        assertTrue(dailySummaryPrompt("es-ES").contains("language es-ES only"))
    }
}
