package io.zer0.muse.ui

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Restricciones de disponibilidad para barras de acciones de selección múltiple.
 *
 * Las líneas de origen se eliminan durante la fusión. Cuando no se encuentra la fuente cuando se elimina la base de datos, solo se informará "fallo de fusión", pero el usuario no ha cambiado nada.——
 * Por lo tanto, las eliminaciones deben bloquearse durante este período, no solo cambiar la entrada de combinación al control giratorio.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemorySelectionActionsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun setActions(selectedCount: Int, mergeRunning: Boolean, onMerge: () -> Unit = {}, onDelete: () -> Unit = {}) {
        composeTestRule.setContent {
            MaterialTheme {
                // En producción, estos tres botones están dispuestos en la fila de MuseTopBar; en las pruebas, también se requieren contenedores en fila.
                // De lo contrario, el diseño raíz los apila en el origen y haga clic en el botón para llevarlos todos a la parte superior.
                Row {
                    MemorySelectionActions(
                        selectedCount = selectedCount,
                        mergeRunning = mergeRunning,
                        onCancel = {},
                        onMerge = onMerge,
                        onDelete = onDelete,
                    )
                }
            }
        }
    }

    @Test
    fun mergingBlocksDeleteAndReplacesMergeEntry() {
        val merged = AtomicBoolean(false)
        val deleted = AtomicBoolean(false)
        setActions(
            selectedCount = 2,
            mergeRunning = true,
            onMerge = { merged.set(true) },
            onDelete = { deleted.set(true) },
        )

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.memory_center_merge_action))
            .assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.memory_center_merge_running))
            .assertExists()

        val delete = composeTestRule.onNodeWithContentDescription(context.getString(R.string.action_delete))
        delete.assertExists()
        delete.assertIsNotEnabled()
        delete.performClick()

        assertFalse("deleting the sources mid-merge would make the merge fail for no visible reason", deleted.get())
        assertFalse("the merge entry must not fire twice", merged.get())
    }

    @Test
    fun idleSelectionKeepsBothActionsAvailable() {
        val merged = AtomicBoolean(false)
        val deleted = AtomicBoolean(false)
        setActions(
            selectedCount = 2,
            mergeRunning = false,
            onMerge = { merged.set(true) },
            onDelete = { deleted.set(true) },
        )

        val merge = composeTestRule.onNodeWithContentDescription(context.getString(R.string.memory_center_merge_action))
        merge.assertIsEnabled()
        merge.performClick()
        val delete = composeTestRule.onNodeWithContentDescription(context.getString(R.string.action_delete))
        delete.assertIsEnabled()
        delete.performClick()

        assertTrue(merged.get())
        assertTrue(deleted.get())
    }

    @Test
    fun singleSelectionDisablesMergeButStillAllowsDelete() {
        setActions(selectedCount = 1, mergeRunning = false)

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.memory_center_merge_action))
            .assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.action_delete))
            .assertIsEnabled()
    }
}
