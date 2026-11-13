package io.zer0.muse.miniphone.ui

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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.miniphone.ui.shell.DiscoverPage
import io.zer0.muse.miniphone.ui.shell.MiniPhoneFeature
import io.zer0.muse.miniphone.ui.shell.MiniPhoneFeatureCatalog
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import org.junit.Assert.assertEquals
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
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoverSpanishLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun allSpanishFeaturesRemainReadableAndClickableAt320dp() = verifyFeatures(1f)

    @Test
    fun allSpanishFeaturesRemainReadableWithDoubleFontSize() = verifyFeatures(2f)

    private fun verifyFeatures(fontScale: Float) {
        val base: Context = ApplicationProvider.getApplicationContext()
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("es"))
        })
        var opened: MiniPhoneFeature? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MaterialTheme {
                    MiniPhoneTheme(darkTheme = true) {
                        Box(Modifier.width(320.dp)) {
                            DiscoverPage(onOpenFeature = { opened = it })
                        }
                    }
                }
            }
        }
        for (feature in MiniPhoneFeatureCatalog.entries) {
            compose.onNodeWithTag("mp-discover-grid").performScrollToNode(hasTestTag(feature.tag))
            val label = context.getString(feature.labelRes)
            val layouts = mutableListOf<TextLayoutResult>()
            val node = compose.onNodeWithText(label, useUnmergedTree = true)
            node.assertIsDisplayed()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val result = layouts.single()
            assertFalse("Truncated label: $label", result.hasVisualOverflow)
            for (line in 0 until result.lineCount - 1) {
                val end = result.getLineEnd(line, visibleEnd = true)
                assertTrue("Word broken inside $label", end <= 0 || end >= label.length ||
                    !label[end - 1].isLetter() || !label[end].isLetter())
            }
            compose.onNodeWithTag(feature.tag).performClick()
            compose.runOnIdle { assertEquals(feature, opened) }
        }
    }
}
