package io.zer0.muse.ui.a11y

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A11Y-07: RTL regresar (`supportsRtl="true"` Pero no ha habido ninguna verificación antes).
 *
 * **Cobertura (declaración honesta,A11Y-07 revisión)**: Este archivo es una prueba Robolectric/JVM,
 * **Solo haga afirmaciones de dirección de diseño, no regresión de captura de pantalla a nivel de píxel**(Sin muestreo de mapa de bits, sin comparación de puntos de referencia).
 * en el nombre de la clase "Screenshot" Es un nombramiento histórico y sus capacidades reales son las siguientes:
 * 1. `ar-rSA` locale La dirección de diseño de la raíz inferior es [LayoutDirection.Rtl](Aserción a nivel de atributo);
 * 2. RTL El texto a continuación en realidad se encuentra en el lado derecho del contenedor principal según las coordenadas de píxeles y tiene el tamaño de representación real.
 *    (Esto demuestra que la dirección no solo es correcta en los atributos, sino que también afecta la composición tipográfica).
 *
 * **no cubierto**: dispositivo real/Comparación de capturas de pantalla a nivel de píxeles en el simulador (fuente, espaciado, icono de espejo,
 * Saltos de línea aleatorios bidireccionales, etc.). Referenciado por comentarios anteriores. `RtlScreenshotInstrumentedTest`
 * en `app/src/androidTest/` no existe en; si desea agregarlo más tarde, debe agregarlo. instrumented
 * Tome capturas de pantalla para probar y ejecutar en AVD/dispositivo real; esta sección debe actualizarse simultáneamente.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "ar-rSA")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RtlScreenshotRegressionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Redacción de muestra:Árabe mixto/China y Japón/latín,Evite que el texto latino sin formato enmascare errores de orientación RTL. */
    private val rtlSample = "مرحبا Muse · ajustes · Settings"

    @Test
    fun `ar locale root layout direction is RTL`() {
        var captured by mutableStateOf(LayoutDirection.Ltr)
        composeTestRule.setContent {
            MaterialTheme {
                captured = LocalLayoutDirection.current
                Text(text = rtlSample)
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(LayoutDirection.Rtl, captured)
    }

    @Test
    fun `ar locale text is laid out right aligned with real size`() {
        var textX by mutableStateOf(-1f)
        var widthPx by mutableStateOf(-1f)
        var heightPx by mutableStateOf(-1f)
        composeTestRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = rtlSample,
                        modifier = Modifier.onGloballyPositioned {
                            textX = it.positionInParent().x
                            widthPx = it.size.width.toFloat()
                            heightPx = it.size.height.toFloat()
                        },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        assertTrue("RTL El siguiente texto debe colocarse en el lado derecho del contenedor principal en lugar del borde izquierdo.(textX=$textX)", textX > 0f)
        assertTrue("RTL El texto debe tener el ancho de representación real.(width=$widthPx)", widthPx > 0f)
        assertTrue("RTL El texto debe tener una altura real renderizada(height=$heightPx)", heightPx > 0f)
    }
}
