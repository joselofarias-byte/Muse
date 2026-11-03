package io.zer0.muse.ui

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reparación de auditoría (A-16): "El cierre de la fusión de medios de noticias devuelve las barandillas".
 *
 * fondo: v1.0.75 "La imagen sin formato solo muestra enlaces."Corrección de errores en línea — Agent Loop cerrando
 * finalAssistantMessage Sin campos multimedia,Se perderá la sobrescritura directa del mismo mensaje de identificación. exec* escrito
 * fotos/vídeo/Código QR. Las correcciones anteriores no tenían ninguna cobertura de prueba única(Barandilla de punto cero de corrección de errores en línea).
 */
class ChatMediaMergeTest {

    private fun assistantMsg(
        imageUrls: List<String> = emptyList(),
        imageBase64List: List<String> = emptyList(),
        videoFileUri: String? = null,
    ) = UIMessage(
        role = MessageRole.ASSISTANT,
        content = "Responder texto",
        imageUrls = imageUrls,
        imageBase64List = imageBase64List,
        videoFileUri = videoFileUri,
    )

    @Test
    fun `finalAssistant Cuando traigas tus propios medios, prevalecerá esto`() {
        val final = assistantMsg(imageUrls = listOf("https://a.com/1.png"))
        val existing = assistantMsg(imageUrls = listOf("https://old.com/x.png"), videoFileUri = "https://old.com/v.mp4")
        val merged = mergeFinalAssistantMedia(final, existing)
        assertEquals(listOf("https://a.com/1.png"), merged.imageUrls)
        // Respaldo independiente por campo: final Ninguno videoFileUri → Revertir vídeo existente
        assertEquals("https://old.com/v.mp4", merged.videoFileUri)
    }

    @Test
    fun `finalAssistant Alternativa para conservar los medios del mensaje existente cuando no hay medios`() {
        val final = assistantMsg()
        val existing = assistantMsg(
            imageUrls = listOf("https://a.com/1.png", "https://a.com/2.png"),
            imageBase64List = listOf("YmFzZTY0"),
            videoFileUri = "https://a.com/v.mp4",
        )
        val merged = mergeFinalAssistantMedia(final, existing)
        assertEquals(listOf("https://a.com/1.png", "https://a.com/2.png"), merged.imageUrls)
        assertEquals(listOf("YmFzZTY0"), merged.imageBase64List)
        assertEquals("https://a.com/v.mp4", merged.videoFileUri)
    }

    @Test
    fun `existingMsg Cuando es nulo, los medios están vacíos`() {
        val final = assistantMsg()
        val merged = mergeFinalAssistantMedia(final, null)
        assertEquals(emptyList<String>(), merged.imageUrls)
        assertEquals(emptyList<String>(), merged.imageBase64List)
        assertNull(merged.videoFileUri)
        assertEquals("Responder texto", merged.content) // Los campos que no son medios no se ven afectados
    }

    @Test
    fun `finalAssistant Cuando tanto los medios como los existentes tienen medios, el final tiene prioridad y no se superpone`() {
        val final = assistantMsg(imageUrls = listOf("https://new.com/n.png"), videoFileUri = "https://new.com/n.mp4")
        val existing = assistantMsg(imageUrls = listOf("https://old.com/o.png"), videoFileUri = "https://old.com/o.mp4")
        val merged = mergeFinalAssistantMedia(final, existing)
        assertEquals(listOf("https://new.com/n.png"), merged.imageUrls)
        assertEquals("https://new.com/n.mp4", merged.videoFileUri)
    }

    @Test
    fun `media Falta el campo pero el contenido permanece_El texto de la rueda de herramientas no retrocede`() {
        val final = assistantMsg(imageBase64List = listOf("aGk="))
        val existing = assistantMsg(imageUrls = listOf("https://old.com/o.png"))
        val merged = mergeFinalAssistantMedia(final, existing)
        // final si base64,imageUrls esta vacio → Revertir lo existente URL(Ambos conviven sin conflicto.)
        assertEquals(listOf("https://old.com/o.png"), merged.imageUrls)
        assertEquals(listOf("aGk="), merged.imageBase64List)
    }
}
