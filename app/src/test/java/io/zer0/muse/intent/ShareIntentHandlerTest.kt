package io.zer0.muse.intent

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.data.assistant.AssistantCardExporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.x: ShareIntentHandler El paquete de funciones recibe pruebas de rama.
 *
 * Portada: VIEW + application/x-muse-assistant → ImportAssistantCard;
 * Otros mimos no se activarán por error.; Las regresiones de ruta de texto ACTION_SEND existentes no se ven afectadas.
 */
class ShareIntentHandlerTest {

    private val context = mockk<Context>(relaxed = true)

    @Test
    fun `view intent with pack mime returns ImportAssistantCard`() = runBlocking {
        val intent = mockk<Intent>(relaxed = true)
        val uri = mockk<Uri>(relaxed = true)
        every { intent.action } returns Intent.ACTION_VIEW
        every { intent.data } returns uri
        every { intent.type } returns AssistantCardExporter.MIME_TYPE

        val result = ShareIntentHandler(context).handle(intent)

        assertTrue("debería regresar ImportAssistantCard,real: $result", result is ShareIntentHandler.ShareResult.ImportAssistantCard)
        assertEquals(
            uri,
            (result as ShareIntentHandler.ShareResult.ImportAssistantCard).uri,
        )
    }

    @Test
    fun `view intent with unrelated mime is not treated as card import`() = runBlocking {
        val intent = mockk<Intent>(relaxed = true)
        val uri = mockk<Uri>(relaxed = true)
        every { intent.action } returns Intent.ACTION_VIEW
        every { intent.data } returns uri
        every { intent.type } returns "application/pdf"

        val result = ShareIntentHandler(context).handle(intent)

        assertTrue("El mimo del paquete sin función no debería activar la importación,real: $result", result !is ShareIntentHandler.ShareResult.ImportAssistantCard)
    }

    @Test
    fun `send text path still prefills (regression)`() = runBlocking {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.action } returns Intent.ACTION_SEND
        every { intent.data } returns null
        every { intent.getStringExtra(Intent.EXTRA_TEXT) } returns "Texto compartido"

        val result = ShareIntentHandler(context).handle(intent)

        assertTrue("Debería tomar el camino precargado,real: $result", result is ShareIntentHandler.ShareResult.PrefillText)
        assertEquals("Texto compartido", (result as ShareIntentHandler.ShareResult.PrefillText).text)
    }
}
