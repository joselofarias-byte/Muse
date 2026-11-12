package io.zer0.muse.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class GreetingHeaderLocalizationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun spanish_header_is_localized_on_initial_render_even_with_chinese_base_context() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val chinese = Configuration(base.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val spanish = Configuration(chinese).apply { setLocale(Locale.forLanguageTag("es")) }
        val spanishContext = base.createConfigurationContext(spanish)
        val expected = GreetingHelper.getTimeGreeting(res = spanishContext.resources)

        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides base.createConfigurationContext(chinese),
                LocalConfiguration provides spanish,
                LocalResources provides spanishContext.resources,
            ) {
                MaterialTheme { GreetingHeader(facts = emptyList()) }
            }
        }

        compose.onNodeWithText(expected).assertIsDisplayed()
        listOf("早上好", "中午好", "下午好", "晚上好", "深夜了").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        assertEquals("Buenos días", GreetingHelper.getTimeGreeting(9, spanishContext.resources))
        assertFalse(expected.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN })
    }
}
