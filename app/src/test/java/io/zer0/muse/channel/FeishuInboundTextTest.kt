package io.zer0.muse.channel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AUD-CHANNEL-F8: El webhook entrante de Feishu debe analizarse content JSON,Mismo diámetro que la ruta de conexión larga.
 *
 * La ruta del webhook original toma directamente la cadena original de message.content({"text":"Hola"})cuando el texto,
 * El modelo recibe el JSON y puede volver a leerlo.;Las rutas de conexión largas se analizarán y se eliminarán los marcadores de posición @.
 */
class FeishuInboundTextTest {

    @Test
    fun `text content json is parsed to plain text`() {
        val out = FeishuReceiver.parseFeishuMessageText(
            content = """{"text":"Hola"}""",
            messageType = "text",
        )
        assertEquals("Hola", out)
    }

    @Test
    fun `at placeholder is stripped`() {
        val out = FeishuReceiver.parseFeishuMessageText(
            content = """{"text":"@_user_1 ¿Está ahí?"}""",
            messageType = "text",
        )
        assertEquals("¿Está ahí?", out)
    }

    @Test
    fun `non-text message types map to placeholders`() {
        assertEquals("[fotos]", FeishuReceiver.parseFeishuMessageText("""{"image_key":"x"}""", "image"))
        assertEquals("[Voz]", FeishuReceiver.parseFeishuMessageText("""{"file_key":"x"}""", "audio"))
        assertEquals("[Archivo]", FeishuReceiver.parseFeishuMessageText("""{"file_key":"x"}""", "file"))
    }

    @Test
    fun `malformed content falls back to empty`() {
        assertEquals("", FeishuReceiver.parseFeishuMessageText("not-json", "text"))
    }
}
