package io.zer0.muse.ui.settings

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PermissionWizardLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun spanishActionsRemainReadableOnNarrowScreen() {
        assertNarrowCard(1f)
    }

    @Test
    fun spanishActionsWrapWithLargeFontsAndKeepBothCallbacks() {
        assertNarrowCard(2f)
    }

    private fun assertNarrowCard(fontScale: Float) {
        val base: Context = ApplicationProvider.getApplicationContext()
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("es"))
        }
        val context = base.createConfigurationContext(configuration)
        var installed = false
        var refreshed = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MaterialTheme {
                    Box(Modifier.width(240.dp)) {
                        ShizukuChannelCard(
                            installed = false,
                            available = false,
                            authorized = false,
                            permissionGranted = false,
                            versionTooOld = false,
                            onInstall = { installed = true },
                            onAuthorize = {},
                            onStartService = {},
                            onRefresh = { refreshed = true },
                        )
                    }
                }
            }
        }
        val install = context.getString(R.string.shizuku_install_btn)
        val refresh = context.getString(R.string.permission_refresh_status)
        for (label in listOf(install, refresh)) {
            val layouts = mutableListOf<TextLayoutResult>()
            val node = compose.onNodeWithText(label, useUnmergedTree = true)
            node.assertIsDisplayed()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
            assertTrue("No layout for $label", layouts.isNotEmpty())
            assertFalse("Clipped action: $label at font scale $fontScale", layouts.single().hasVisualOverflow)
        }
        val installBounds = compose.onNodeWithText(install).fetchSemanticsNode().boundsInRoot
        val refreshBounds = compose.onNodeWithText(refresh).fetchSemanticsNode().boundsInRoot
        assertTrue("Actions must stack instead of competing for width", installBounds.bottom < refreshBounds.top)
        compose.onNodeWithText(install).performClick()
        compose.onNodeWithText(refresh).performClick()
        compose.runOnIdle {
            assertTrue(installed)
            assertTrue(refreshed)
        }
    }
}
