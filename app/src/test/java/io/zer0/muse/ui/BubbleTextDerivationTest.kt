package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Valor derivado de representación de texto (`deriveBubbleText`).
 *
 * Esta lógica se escribió previamente en la combinación y se volvió a ejecutar cada vez que se reorganizó (ruta de emoji findAll + reemplazo regular de dos párrafos completos + segmentación de títulos),
 * ahora ambos `remember` En resumen, también se convierte en una función pura. Estos casos de uso solo están disponibles después de la funcionalización pura. —— De lo contrario, "simplemente ajústelo".
 * La "segmentación de títulos" sólo puede depender del ojo humano para ver la interfaz y afecta la presentación del texto de cada mensaje.
 */
class BubbleTextDerivationTest {

    private fun derive(
        content: String,
        body: String = content,
        imageUrls: List<String> = emptyList(),
        imageBase64List: List<String> = emptyList(),
        isStreaming: Boolean = false,
        isCompressed: Boolean = false,
    ) = deriveBubbleText(
        content = content,
        body = body,
        imageUrls = imageUrls,
        imageBase64List = imageBase64List,
        isStreaming = isStreaming,
        isCompressed = isCompressed,
    )

    @Test
    fun `plain_text_passes_through`() {
        val result = derive("Texto normal")
        assertEquals("Texto normal", result.content)
        assertEquals("Texto normal", result.bodyContent)
        assertNull(result.titleText)
    }

    @Test
    fun `heading_is_split_out_of_body`() {
        val result = derive("# línea de título\nPrimer párrafo del texto.")
        assertEquals("# línea de título", result.titleText)
        assertEquals("Primer párrafo del texto.", result.bodyContent)
        assertEquals("línea de título", result.titleText!!.removePrefix("# "))
    }

    @Test
    fun `heading_is_not_split_while_streaming`() {
        val result = derive("# línea de título\nPrimer párrafo del texto.", isStreaming = true)
        assertNull("El título no se cortará durante la transmisión (el contenido seguirá creciendo, pero parpadeará si se corta)", result.titleText)
        assertTrue("El texto debe contener el texto original de la línea del título.", result.bodyContent.contains("# línea de título"))
    }

    @Test
    fun `compressed_marker_is_stripped`() {
        val result = derive("[COMPRESSED]  Texto resumen histórico", isCompressed = true)
        assertEquals("Texto resumen histórico", result.content)
    }

    @Test
    fun `sticker_paths_are_removed_when_message_has_no_images`() {
        val content = "mira esto /data/user/0/io.zer0.muse/files/stickers/a.webp expresión"
        val result = derive(content)
        assertTrue("La ruta de la pegatina no debe quedar en el texto.", !result.content.contains("stickers/a.webp"))
        assertTrue("Debe conservarse el resto del texto.", result.content.contains("mira esto"))
    }

    @Test
    fun `sticker_scan_is_skipped_when_message_has_images`() {
        // Cuando hay imágenes, la cadena de rutas en el texto puede conservarse intencionalmente (doble representación de imágenes y texto) y no puede eliminarse.
        val content = "Texto con imágenes"
        val result = derive(content, imageUrls = listOf("https://example.com/a.png"))
        assertEquals(content, result.content)
    }

    @Test
    fun `artifact_marker_is_removed_from_body`() {
        val marker = "[artifact:123e4567-e89b-12d3-a456-426614174000]"
        val result = derive("Texto$marker fin")
        assertTrue("artifact Las etiquetas no deben aparecer en el texto.", !result.bodyContent.contains("artifact:"))
        assertTrue(result.bodyContent.contains("Texto"))
    }

    @Test
    fun `empty_body_with_images_keeps_empty_and_without_images_uses_space`() {
        assertEquals("", derive("", imageUrls = listOf("x")).content)
        assertEquals(" ", derive("").content)
    }
}
