package io.zer0.muse.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import io.zer0.muse.data.preset.PresetProviders
import io.zer0.muse.ui.theme.PresetThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "es-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun themeLabelsFitAtNarrowWidth() = checkThemeLabels(1f)

    @Test
    fun themeLabelsFitAtTwoHundredPercentFont() = checkThemeLabels(2f)

    private fun checkThemeLabels(fontScale: Float) {
        var selected = ""
        setNarrowContent(fontScale) {
            StepTheme(selectedThemeId = "mono", onThemeSelected = { selected = it })
        }
        PresetThemes.forEach { theme ->
            val label = context.getString(theme.nameResId)
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performScrollTo().assertIsDisplayed().assertTextFits()
        }
        val last = PresetThemes.last()
        compose.onNodeWithText(context.getString(last.nameResId)).performClick()
        assertEquals(last.id, selected)
    }

    @Test
    fun restoreBackupWrapsAndRemainsActionableAtTwoHundredPercentFont() {
        var route = ""
        setNarrowContent(2f) { StepComplete(onOpenSection = { route = it }) }
        val restore = compose.onNodeWithText(context.getString(R.string.onboarding_restore_from_backup))
        restore.performScrollTo().assertIsDisplayed().assertTextFits(requireMultiline = true)
        restore.performClick()
        assertEquals("backup", route)
    }

    @Test
    fun compactProviderSelectorAtNarrowWidth() = checkProviderSelector(1f)

    @Test
    fun compactProviderSelectorAtTwoHundredPercentFont() = checkProviderSelector(2f)

    private fun checkProviderSelector(fontScale: Float) {
        val presets = PresetProviders(context)
        val initial = presets.onboardingPresets.first { it.id != "preset_custom_openai" }
        val custom = presets.onboardingPresets.first { it.id == "preset_custom_openai" }
        var tested = false
        var skipped = false
        setNarrowContent(fontScale) {
            var selected by remember { mutableStateOf(initial.id) }
            StepProviderConfig(
                presetProviders = presets,
                selectedPresetId = selected,
                onPresetSelected = { selected = it },
                apiKey = "test-key",
                onApiKeyChange = {},
                customBaseUrl = "https://example.com/v1",
                onCustomBaseUrlChange = {},
                apiKeyVisible = false,
                onToggleApiKeyVisible = {},
                testStatus = TestStatus.Idle,
                onTestConnection = { tested = true },
                onSkip = { skipped = true },
            )
        }
        compose.onNodeWithText(custom.displayName).assertDoesNotExist()
        val selector = compose.onNodeWithText(initial.displayName)
        selector.performScrollTo().assertIsDisplayed().assertTextFits()
        selector.performTouchInput { swipeUp() }
        compose.onNodeWithText(custom.displayName).assertDoesNotExist()
        selector.performScrollTo().performTouchInput { click() }
        presets.onboardingPresets.forEach { preset ->
            assertTrue(
                compose.onAllNodesWithText(preset.displayName, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty(),
            )
        }
        compose.onNodeWithText(custom.displayName, useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed().assertTextFits().performClick()
        compose.onNodeWithText(custom.displayName)
            .performScrollTo().assertIsDisplayed().assertTextFits(requireMultiline = fontScale == 2f)
        compose.onNodeWithText(initial.displayName).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.onboarding_provider_custom_baseurl_label))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.onboarding_provider_test_button))
            .performScrollTo().assertIsEnabled().performClick()
        assertTrue(tested)
        compose.onNodeWithText(context.getString(R.string.onboarding_provider_skip))
            .performScrollTo().performClick()
        assertTrue(skipped)
    }

    @Test
    fun spanishGreetingIsFixedFromFirstFrame() {
        compose.mainClock.autoAdvance = false
        setNarrowContent(1f) { StepWelcome() }
        compose.onNodeWithText("Hola, encantado.").assertExists()
        compose.onNodeWithText("Hi, nice to meet you.").assertDoesNotExist()
        compose.onNodeWithText("嗨，终于见面了。").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1600)
        compose.onNodeWithText("Hola, encantado.").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(20000)
        compose.onNodeWithText("Hola, encantado.").assertIsDisplayed()
        compose.onNodeWithText("Hi, nice to meet you.").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.onboarding_welcome_greetings_caption)).assertDoesNotExist()
    }

    private fun setNarrowContent(fontScale: Float, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) { content() }
                }
            }
        }
    }

    private fun SemanticsNodeInteraction.assertTextFits(requireMultiline: Boolean = false): SemanticsNodeInteraction {
        val results = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("Text must expose its measured layout", results.isNotEmpty())
        results.forEach { result ->
            assertFalse("${result.layoutInput.text}: size=${result.size}, width=${result.didOverflowWidth}, height=${result.didOverflowHeight}, lines=${result.lineCount}, constraints=${result.layoutInput.constraints}", result.hasVisualOverflow)
            if (requireMultiline) assertTrue("Label should wrap without shrinking", result.lineCount > 1)
        }
        return this
    }
}
