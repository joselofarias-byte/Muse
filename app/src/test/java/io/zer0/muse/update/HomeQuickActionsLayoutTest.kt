package io.zer0.muse.update

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import io.zer0.muse.ui.HomeQuickActionCapsule
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
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeQuickActionsLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `all Spanish actions fit and work at 320dp`() = verifyActions(1f)

    @Test
    fun `all Spanish actions wrap without clipping at 320dp and 200 percent font`() = verifyActions(2f)

    private fun verifyActions(fontScale: Float) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("es"))
            this.fontScale = fontScale
        }
        val localized = base.createConfigurationContext(configuration)
        val labels = listOf(
            R.string.chat_list_scheduled_tasks,
            R.string.chat_list_quick_notes,
            R.string.chat_list_quick_translate,
            R.string.chat_list_new_task,
        ).map(localized::getString)
        val clicked = mutableListOf<Int>()
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalResources provides localized.resources,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(1f, fontScale),
            ) {
                MaterialTheme {
                    Box(Modifier.width(320.dp).testTag("action-container")) {
                        HomeQuickActionCapsule(
                            onOpenScheduledTasks = { clicked += 0 },
                            onOpenQuickNotes = { clicked += 1 },
                            onOpenQuickTranslate = { clicked += 2 },
                            onCreateNewTask = { clicked += 3 },
                            expanded = true,
                            onToggleExpanded = {},
                        )
                    }
                }
            }
        }
        val container = compose.onNodeWithTag("action-container").fetchSemanticsNode().boundsInRoot
        labels.forEach { label ->
            val node = compose.onNodeWithText(label).assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("$label must stay inside the left edge", bounds.left >= container.left)
            assertTrue("$label must stay inside the right edge", bounds.right <= container.right)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Text layout is required for $label", layouts.isNotEmpty())
            assertFalse("$label must not be clipped", layouts.single().hasVisualOverflow)
            node.performClick()
        }
        assertEquals(listOf(0, 1, 2, 3), clicked)
    }
}
