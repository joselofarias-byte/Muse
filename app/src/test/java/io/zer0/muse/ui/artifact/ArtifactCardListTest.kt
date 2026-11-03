package io.zer0.muse.ui.artifact

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.zer0.muse.R
import io.zer0.muse.data.artifact.ArtifactEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phase 2: ArtifactCardList Prueba de comportamiento de plegado excesivo.
 *
 * v2.1.0: El umbral de plegado se ajusta con el paradigma de la ficha de producto.(Columna de tarjeta grande de ancho completo,De forma predeterminada, se mostrarán un máximo de las 3 primeras imágenes.);
 * Cambiar la copia plegable a cadena de recursos,Obtener dinámicamente según la ubicación actual,Asegúrese de que las afirmaciones sean coherentes con la representación.
 * El ancho de la pantalla se amplía a 1600dp,Evite falsos negativos causados ​​por vistas recortadas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1600dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtifactCardListTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    /** La copia plegada se obtiene en la misma ubicación que la parte renderizada.(No codifique chino/ingles). */
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun moreText(hidden: Int): String = context.getString(R.string.artifact_more_count, hidden)

    private fun artifact(index: Int) = ArtifactEntity(
        id = "artifact-$index",
        sessionId = "session-1",
        messageId = "message-1",
        title = "producto$index",
        type = "code",
        content = "val x = $index",
        language = "kotlin",
    )

    @Test
    fun `Cuando hay más de tres, se muestra una barra plegable al final.`() {
        composeTestRule.setContent {
            MaterialTheme {
                ArtifactCardList(artifacts = (1..7).map(::artifact), onArtifactClick = {})
            }
        }
        composeTestRule.onNodeWithText("producto3").assertExists()
        composeTestRule.onNodeWithText("producto4").assertDoesNotExist()
        composeTestRule.onNodeWithText(moreText(4)).assertExists()
    }

    @Test
    fun `Haga clic en la barra plegable para expandir todos los productos.`() {
        composeTestRule.setContent {
            MaterialTheme {
                ArtifactCardList(artifacts = (1..7).map(::artifact), onArtifactClick = {})
            }
        }
        composeTestRule.onNodeWithText(moreText(4)).performClick()
        composeTestRule.onNodeWithText("producto4").assertExists()
        composeTestRule.onNodeWithText("producto7").assertExists()
        composeTestRule.onNodeWithText(moreText(4)).assertDoesNotExist()
    }

    @Test
    fun `No muestre tarjetas dobladas si hay tres o menos`() {
        composeTestRule.setContent {
            MaterialTheme {
                ArtifactCardList(artifacts = (1..3).map(::artifact), onArtifactClick = {})
            }
        }
        composeTestRule.onNodeWithText("producto3").assertExists()
        composeTestRule.onNodeWithText(moreText(1)).assertDoesNotExist()
    }

    @Test
    fun `Haga clic en la tarjeta para recuperar el producto correspondiente`() {
        var clicked: ArtifactEntity? = null
        composeTestRule.setContent {
            MaterialTheme {
                ArtifactCardList(
                    artifacts = listOf(artifact(1)),
                    onArtifactClick = { clicked = it },
                )
            }
        }
        composeTestRule.onNodeWithText("producto1").performClick()
        org.junit.Assert.assertEquals("artifact-1", clicked?.id)
    }
}
