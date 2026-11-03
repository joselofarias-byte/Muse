package io.zer0.muse.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.RagCitation
import io.zer0.muse.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phase 2: RAG Referencia de deduplicación de claves de chip, descripción de accesibilidad y prueba de acción de expansión.
 *
 * Utilice el calificador zh-rCN para que la cadena de aserción sea coherente con el recurso predeterminado.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RagCitationChipsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun citation(index: Int, title: String = "Documentación$index") = RagCitation(
        index = index,
        docId = "doc-$index",
        docTitle = title,
        chunkId = "chunk-$index",
        chunkIndex = 0,
        snippet = "contenido del fragmento$index",
        score = 0.9f,
        matchType = "vector",
    )

    @Test
    fun `Repita el índice y solo renderice uno después de la deduplicación chip`() {
        composeTestRule.setContent {
            MaterialTheme {
                RagCitationChips(
                    citations = listOf(citation(1), citation(1, title = "Documentos duplicados")),
                )
            }
        }
        assertEquals(1, composeTestRule.onAllNodesWithText("[1]").fetchSemanticsNodes().size)
    }

    @Test
    fun `chip Proporcionar número de referencia y nombre del documento contentDescription`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = context.getString(
            R.string.chat_citation_chip_a11y,
            1,
            "Documentación1",
            context.getString(R.string.action_expand),
        )
        composeTestRule.setContent {
            MaterialTheme { RagCitationChips(citations = listOf(citation(1))) }
        }
        composeTestRule.onNodeWithContentDescription(expected).assertExists()
    }

    @Test
    fun `Mostrar resumen y copiar acción después de la expansión`() {
        composeTestRule.setContent {
            MaterialTheme { RagCitationChips(citations = listOf(citation(1))) }
        }
        composeTestRule.onNodeWithText("[1]").performClick()
        composeTestRule.onNodeWithText("contenido del fragmento1").assertExists()
        composeTestRule.onNodeWithText("Copiar resumen").assertExists()
        // No mostrar "Abrir documento" cuando no se proporciona ninguna devolución de llamada
        composeTestRule.onNodeWithText("Abrir documento").assertDoesNotExist()
    }

    @Test
    fun `Cuando se proporciona onOpenDocument, se muestra la acción y se vuelve a llamar a la referencia correspondiente`() {
        var opened: RagCitation? = null
        composeTestRule.setContent {
            MaterialTheme {
                RagCitationChips(
                    citations = listOf(citation(2)),
                    onOpenDocument = { opened = it },
                )
            }
        }
        composeTestRule.onNodeWithText("[2]").performClick()
        composeTestRule.onNodeWithText("Abrir documento").performClick()
        assertEquals(2, opened?.index)
    }
}
