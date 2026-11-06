package io.zer0.muse.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La insignia y los detalles no están completos hasta que se renderizan. —— Sólo la función de juicio no puede detectar "lo que es invisible en la lista".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryStatusBadgeUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun factRow(item: MemoryItem) {
        composeTestRule.setContent {
            MaterialTheme {
                MemoryFactRow(item = item)
            }
        }
    }

    private fun factItem(
        mergedIntoId: Long? = null,
        suppressed: Boolean = false,
        superseded: Boolean = false,
    ): MemoryItem = MemoryItem(
        id = "7",
        title = "como el cafe",
        content = "como el cafe",
        source = "Fact",
        scope = "main",
        mergedIntoId = mergedIntoId,
        suppressed = suppressed,
        superseded = superseded,
    )

    @Test
    fun mergedRowShowsMergedBadge() {
        factRow(factItem(mergedIntoId = 42L))

        composeTestRule.onNodeWithText(context.getString(R.string.memory_status_merged)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.memory_status_superseded)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.memory_status_suppressed)).assertDoesNotExist()
    }

    @Test
    fun healthyRowShowsNoBadge() {
        factRow(factItem())

        composeTestRule.onNodeWithText(context.getString(R.string.memory_status_merged)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.memory_status_suppressed)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.memory_status_superseded)).assertDoesNotExist()
    }

    @Test
    fun rowWithAllThreeStatesShowsAllBadges() {
        // Los tres estados son independientes entre sí: cuando todos están dentro, ninguno de ellos podrá ser tragado, pero el secundario se dibujará más ligero.
        factRow(factItem(mergedIntoId = 42L, suppressed = true, superseded = true))

        composeTestRule.onNodeWithText(context.getString(R.string.memory_status_superseded)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.memory_status_merged)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.memory_status_suppressed)).assertExists()
    }

    @Test
    fun detailDialogExplainsHitStates() {
        val note = context.getString(R.string.memory_status_merged_detail) + "\n" +
            context.getString(R.string.memory_status_suppressed_detail)

        composeTestRule.setContent {
            MaterialTheme {
                FactEditDialog(
                    title = "Editar memoria",
                    initialContent = "como el cafe",
                    statusNote = note,
                    onDismiss = {},
                    onConfirm = { _, _ -> },
                )
            }
        }

        composeTestRule.onNodeWithText(note).assertIsDisplayed()
    }
}
