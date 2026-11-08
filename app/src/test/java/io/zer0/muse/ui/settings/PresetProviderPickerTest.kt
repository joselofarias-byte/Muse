package io.zer0.muse.ui.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.R
import io.zer0.muse.data.preset.PresetProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "es")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PresetProviderPickerTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val presets = PresetProviders(context)

    @Test
    fun `last provider remains reachable and selectable on a narrow screen`() {
        val lastPreset = presets.all.last { preset -> presets.relay.any { it.id == preset.id } }
        var selected: ProviderConfig? = null
        composeTestRule.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(480.dp).testTag("narrow-provider-picker")) {
                    PresetProviderPickerPage({}, { selected = it }, {}, presets)
                }
            }
        }

        saveNarrowPickerScreenshot("provider-picker-narrow-top.png")
        composeTestRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText(lastPreset.displayName))
        composeTestRule.onNodeWithText(lastPreset.displayName).assertIsDisplayed()
        saveNarrowPickerScreenshot("provider-picker-narrow-last-provider.png")
        composeTestRule.onNodeWithText(lastPreset.displayName).performClick()
        composeTestRule.runOnIdle { assertEquals(lastPreset.id, selected?.id) }
    }

    private fun saveNarrowPickerScreenshot(filename: String) {
        composeTestRule.waitForIdle()
        val bitmap = composeTestRule.onNodeWithTag("narrow-provider-picker")
            .captureToImage().asAndroidBitmap()
        assertTrue("The rendered picker must have nonzero dimensions", bitmap.width > 0 && bitmap.height > 0)
        val output = File("build/reports/robolectric/provider-picker", filename)
        check(output.parentFile.mkdirs() || output.parentFile.isDirectory)
        output.outputStream().use { stream ->
            assertTrue("PNG capture must succeed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    @Test
    fun `custom provider stays accessible when search has no matching presets`() {
        var customSelected = false
        val customTitle = context.getString(R.string.settings_preset_custom)
        composeTestRule.setContent {
            MaterialTheme {
                PresetProviderPickerPage({}, {}, { customSelected = true }, presets)
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextInput("no-provider-matches-this-query")
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(customTitle))
        composeTestRule.onNodeWithText(customTitle).assertIsDisplayed().performClick()
        composeTestRule.runOnIdle { assertTrue(customSelected) }
    }
}
