package io.zer0.muse.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reglas de representación para bloques de códigos QR en modo "solo local".
 *
 * La dirección de bucle invertido se dibuja como un código QR. Si lo escanea con otros dispositivos, solo se escaneará una vez. Por lo tanto, el bloque completo no se representa en este modo (no queda ningún marco vacío).
 * Pongámoslo en una oración; el código QR solo aparece después de abrir el acceso LAN.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebAccessQrBlockTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun localOnlyModeRendersHintInsteadOfQrCode() {
        compose.setContent {
            MaterialTheme {
                WebAccessQrBlock(WebAccessAddress(url = "http://127.0.0.1:8765", localOnly = true))
            }
        }

        compose.onNodeWithTag(WEB_ACCESS_QR_TAG).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.settings_web_access_local_only_hint)).assertIsDisplayed()
    }

    @Test
    fun lanModeRendersQrCode() {
        compose.setContent {
            MaterialTheme {
                WebAccessQrBlock(WebAccessAddress(url = "http://192.168.1.23:8765", localOnly = false))
            }
        }

        compose.onNodeWithTag(WEB_ACCESS_QR_TAG).assertExists()
        compose.onNodeWithText(context.getString(R.string.settings_web_access_local_only_hint)).assertDoesNotExist()
    }

    /** El conmutador LAN está encendido pero no se obtiene la IP: no hay una dirección codificable y no se procesa el bloque completo. */
    @Test
    fun missingUrlRendersNoQrCode() {
        compose.setContent {
            MaterialTheme {
                WebAccessQrBlock(WebAccessAddress(url = null, localOnly = false))
            }
        }

        compose.onNodeWithTag(WEB_ACCESS_QR_TAG).assertDoesNotExist()
    }
}
