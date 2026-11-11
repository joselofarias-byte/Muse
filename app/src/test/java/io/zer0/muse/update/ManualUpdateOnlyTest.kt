package io.zer0.muse.update

import android.content.Context
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import io.zer0.common.Result
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ManualUpdateOnlyTest {
    private val context = mockk<Context>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val checker = mockk<UpdateChecker>()
    private val notifier = UpdateNotifier(settings, checker)

    @Test
    fun automaticCheckNeverReadsPreferencesOrContactsServer() = runBlocking {
        assertEquals(UpdateCheckResult.Skipped, notifier.checkAndNotify(context))

        verify { settings wasNot Called }
        verify { checker wasNot Called }
        verify { context wasNot Called }
    }

    @Test
    fun manualCheckStillContactsServerWithoutCreatingStartupCacheOrNotification() = runBlocking {
        val release = UpdateChecker.ReleaseInfo(
            tagName = "v999.0.0",
            name = "Muse",
            body = "",
            htmlUrl = "https://example.com",
            publishedAt = 0L,
            apkAssets = emptyList(),
        )
        coEvery { checker.checkLatestRelease() } returns Result.Success(release)

        val result = notifier.checkManually(context)

        assertTrue(result is UpdateCheckResult.NewVersion)
        coVerify(exactly = 1) { checker.checkLatestRelease() }
        coVerify(exactly = 1) { settings.saveLastUpdateCheckTime(any()) }
        coVerify(exactly = 0) { settings.saveLatestReleaseInfo(any()) }
        verify(exactly = 0) { context.getSystemService(any<String>()) }
    }

    @Test
    fun manualCheckUsesSpanishFallbackWhenNetworkExceptionHasNoMessage() = runBlocking {
        coEvery { checker.checkLatestRelease() } throws IOException()

        assertEquals(UpdateCheckResult.Failed("Error de red"), notifier.checkManually(context))
    }

    @Test
    fun manualCheckPreservesExternalNetworkErrorDetails() = runBlocking {
        coEvery { checker.checkLatestRelease() } throws IOException("Connection reset by peer")

        assertEquals(
            UpdateCheckResult.Failed("Connection reset by peer"),
            notifier.checkManually(context),
        )
    }
}
