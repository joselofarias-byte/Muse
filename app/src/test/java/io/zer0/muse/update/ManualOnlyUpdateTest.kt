package io.zer0.muse.update

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.zer0.common.Result
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ManualOnlyUpdateTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val checker = mockk<UpdateChecker>()
    private val release = UpdateChecker.ReleaseInfo(
        tagName = "v999.0.0",
        name = "External release title",
        body = "未翻译的更新说明",
        htmlUrl = "https://github.com/Zer0Qing/Muse/releases/tag/v999.0.0",
        publishedAt = 0L,
        apkAssets = emptyList(),
    )

    @Suppress("DEPRECATION")
    @Test
    fun `legacy preferences and cached releases cannot enable automatic requests`() = runBlocking {
        for (enabled in listOf(true, false)) {
            for (cache in listOf(null, "{\"tag_name\":\"v999.0.0\"}", "invalid legacy cache")) {
                val settings = legacySettings(enabled, cache)
                val notifier = UpdateNotifier(settings, checker)
                for (forced in listOf(false, true)) {
                    assertEquals(UpdateCheckResult.Skipped, notifier.checkAndNotify(context, forced))
                }
                verify { settings wasNot Called }
            }
        }
        coVerify(exactly = 0) { checker.checkLatestRelease() }
        assertNoNotifications()
    }

    @Test
    fun `manual check ignores legacy settings and returns new version without banner cache or notification`() = runBlocking {
        val settings = legacySettings(enabled = false, cache = "legacy cached release")
        coEvery { checker.checkLatestRelease() } returns Result.Success(release)

        assertEquals(UpdateCheckResult.NewVersion(release), UpdateNotifier(settings, checker).checkManually(context))

        coVerify(exactly = 1) { checker.checkLatestRelease() }
        verify { settings wasNot Called }
        assertNoNotifications()
    }

    @Test
    fun `manual check returns current version and failures distinctly`() = runBlocking {
        val settings = legacySettings(enabled = true, cache = null)
        val notifier = UpdateNotifier(settings, checker)
        coEvery { checker.checkLatestRelease() } returns Result.Success(release.copy(tagName = "v0.0.0"))
        assertEquals(UpdateCheckResult.UpToDate, notifier.checkManually(context))
        coEvery { checker.checkLatestRelease() } throws IOException("offline")
        assertEquals(UpdateCheckResult.Failed("offline"), notifier.checkManually(context))
        verify { settings wasNot Called }
        assertNoNotifications()
    }

    @Test(expected = CancellationException::class)
    fun `manual cancellation is not reported as an update failure`() = runBlocking {
        coEvery { checker.checkLatestRelease() } throws CancellationException("cancelled")
        UpdateNotifier(legacySettings(enabled = true, cache = null), checker).checkManually(context)
        Unit
    }

    @Test
    fun `Home has no update banner or legacy cache subscription`() {
        val source = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .map { File(it, "io/zer0/muse/ui/HomeScreen.kt") }
            .first { it.isFile }
            .readText()
        for (removedRoute in listOf("UpdateAvailableBanner", "latestReleaseInfoFlow", "ignoredUpdateVersionFlow", "io.zer0.muse.update")) {
            assertTrue("Home must not restore $removedRoute", removedRoute !in source)
        }
    }

    private fun legacySettings(enabled: Boolean, cache: String?): SettingsRepository = mockk<SettingsRepository>().also {
        every { it.updateCheckEnabledFlow } returns flowOf(enabled)
        every { it.lastUpdateCheckTimeFlow } returns flowOf(0L)
        every { it.latestReleaseInfoFlow } returns flowOf(cache)
    }

    private fun assertNoNotifications() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
