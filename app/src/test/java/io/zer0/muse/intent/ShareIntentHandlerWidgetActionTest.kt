package io.zer0.muse.intent

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Prueba de análisis de la acción de hacer clic en el widget de escritorio.
 *
 * Este enlace estaba roto anteriormente en "El widget guarda el asistente seleccionado.,Pero nadie lo lee,Solo puedes encontrarlo haciendo clic en el teléfono real.,
 * Así que fija la capa de análisis aquí.:La nueva sesión debe traer la identificación del asistente pasada por el widget.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShareIntentHandlerWidgetActionTest {

    private fun intent(action: String, extras: Map<String, String> = emptyMap()): Intent = Intent().apply {
        putExtra(ShareIntentHandler.EXTRA_WIDGET_ACTION, action)
        extras.forEach { (key, value) -> putExtra(key, value) }
    }

    @Test
    fun `new session carries the assistant chosen in the widget`() {
        val result = ShareIntentHandler.parseWidgetAction(
            intent(
                ShareIntentHandler.WIDGET_ACTION_NEW_SESSION,
                mapOf(ShareIntentHandler.EXTRA_WIDGET_ASSISTANT_ID to "assistant-1"),
            ),
        )

        assertEquals(ShareIntentHandler.ShareResult.NewSession("assistant-1"), result)
    }

    @Test
    fun `voice input action starts a voice session on the chosen assistant`() {
        val result = ShareIntentHandler.parseWidgetAction(
            intent(
                ShareIntentHandler.WIDGET_ACTION_VOICE_INPUT,
                mapOf(ShareIntentHandler.EXTRA_WIDGET_ASSISTANT_ID to "assistant-2"),
            ),
        )

        assertEquals(ShareIntentHandler.ShareResult.NewSession("assistant-2", startVoice = true), result)
    }

    @Test
    fun `plain new session does not auto-start voice`() {
        val result = ShareIntentHandler.parseWidgetAction(
            intent(
                ShareIntentHandler.WIDGET_ACTION_NEW_SESSION,
                mapOf(ShareIntentHandler.EXTRA_WIDGET_ASSISTANT_ID to "assistant-1"),
            ),
        )

        val session = result as ShareIntentHandler.ShareResult.NewSession
        assertFalse("\"Nuevo Diálogo\" no debe encender el micrófono, de lo contrario no habrá diferencia si enciende el micrófono en ambos puntos", session.startVoice)
    }

    @Test
    fun `new session without assistant id falls back to the default assistant`() {
        val result = ShareIntentHandler.parseWidgetAction(
            intent(ShareIntentHandler.WIDGET_ACTION_NEW_SESSION),
        )

        assertEquals(ShareIntentHandler.ShareResult.NewSession(null), result)
    }

    @Test
    fun `blank assistant id is treated as unspecified`() {
        val result = ShareIntentHandler.parseWidgetAction(
            intent(
                ShareIntentHandler.WIDGET_ACTION_NEW_SESSION,
                mapOf(ShareIntentHandler.EXTRA_WIDGET_ASSISTANT_ID to "   "),
            ),
        )

        assertEquals(ShareIntentHandler.ShareResult.NewSession(null), result)
    }

    @Test
    fun `open chats maps to the session list`() {
        assertEquals(
            ShareIntentHandler.ShareResult.OpenChats,
            ShareIntentHandler.parseWidgetAction(intent(ShareIntentHandler.WIDGET_ACTION_OPEN_CHATS)),
        )
    }

    @Test
    fun `open session carries the session id`() {
        val result = ShareIntentHandler.parseWidgetAction(
            intent(
                ShareIntentHandler.WIDGET_ACTION_OPEN_SESSION,
                mapOf(ShareIntentHandler.EXTRA_WIDGET_SESSION_ID to "session-9"),
            ),
        )

        assertEquals(ShareIntentHandler.ShareResult.OpenSession("session-9"), result)
    }

    @Test
    fun `open session without session id is ignored`() {
        assertNull(
            ShareIntentHandler.parseWidgetAction(intent(ShareIntentHandler.WIDGET_ACTION_OPEN_SESSION)),
        )
    }

    @Test
    fun `intent without widget action is not a widget action`() {
        assertNull(ShareIntentHandler.parseWidgetAction(Intent()))
        assertNull(ShareIntentHandler.parseWidgetAction(null))
    }

    @Test
    fun `unknown widget action is ignored`() {
        assertNull(ShareIntentHandler.parseWidgetAction(intent("something_else")))
    }
}
