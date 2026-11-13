package io.zer0.muse.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
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
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentSpanishLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun spanishAttachmentActionsFitNarrowScreen() = verifyActions(1f)

    @Test
    fun spanishAttachmentActionsFitWithDoubleFontSize() = verifyActions(2f)

    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
    private fun verifyActions(fontScale: Float) {
        val base: Context = ApplicationProvider.getApplicationContext()
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("es"))
        })
        val labels = listOf(R.string.chat_tool_camera, R.string.chat_authorize_gallery, R.string.chat_tool_photo)
            .map(context::getString)
        val tools = listOf(R.string.chat_web_search_cd, R.string.chat_deep_thinking_cd, R.string.chat_tool_knowledge)
            .map(context::getString)
        val clicked = mutableSetOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MaterialTheme {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        AttachmentActionGrid {
                            for (label in labels) {
                                ToolMediaCard(
                                    icon = MuseIcons.image,
                                    label = label,
                                    modifier = Modifier.weight(1f).heightIn(min = 128.dp * fontScale),
                                    onClick = { clicked.add(label) },
                                )
                            }
                        }
                        AttachmentActionGrid {
                            for (label in tools) {
                                QuickAttachTab(
                                    icon = MuseIcons.book,
                                    label = label,
                                    compact = true,
                                    dense = true,
                                    modifier = Modifier.weight(1f),
                                    onClick = { clicked.add(label) },
                                )
                            }
                        }
                    }
                }
            }
        }
        for (label in labels + tools) {
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("Clipped action: $label", layouts.single().hasVisualOverflow)
            compose.onNodeWithText(label).performClick()
        }
        compose.runOnIdle { assertEquals((labels + tools).toSet(), clicked) }
    }
}
