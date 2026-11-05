package io.zer0.muse.schedule

import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class ChatGenerationServiceSafeModeTest {
    @Test
    fun `service can be destroyed without Koin in Safe Mode`() {
        if (GlobalContext.getOrNull() != null) stopKoin()
        Robolectric.buildService(ChatGenerationService::class.java).create().destroy()
    }
}
