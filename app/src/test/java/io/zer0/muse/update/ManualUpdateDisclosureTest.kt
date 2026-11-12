package io.zer0.muse.update

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import io.zer0.muse.ui.settings.ManualUpdateDetailsDialog
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManualUpdateDisclosureTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `manual details disclose external language before opening a website`() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("es"))
            fontScale = 2f
        }
        val localized = base.createConfigurationContext(configuration)
        val release = UpdateChecker.ReleaseInfo(
            tagName = "v999.0.0",
            name = "English external title",
            body = "未翻译的更新说明",
            htmlUrl = "https://github.com/Zer0Qing/Muse/releases/tag/v999.0.0",
            publishedAt = 0L,
            apkAssets = emptyList(),
        )
        var dismissed = false
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalResources provides localized.resources,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(1f, 2f),
            ) {
                MaterialTheme {
                    ManualUpdateDetailsDialog(release, onDismiss = { dismissed = true })
                }
            }
        }
        compose.onNodeWithText(localized.getString(R.string.update_external_content_notice)).assertIsDisplayed()
        compose.onNodeWithText(localized.getString(R.string.update_open_external_site)).assertIsDisplayed()
        compose.onNodeWithText(UpdateNotifier.OFFICIAL_DOWNLOAD_PAGE).assertIsDisplayed()
        compose.onNodeWithText(release.body).assertDoesNotExist()
        compose.onNodeWithText(release.name).assertDoesNotExist()
        compose.onNodeWithText(localized.getString(R.string.action_cancel)).performClick()
        assertTrue(dismissed)
    }
}
