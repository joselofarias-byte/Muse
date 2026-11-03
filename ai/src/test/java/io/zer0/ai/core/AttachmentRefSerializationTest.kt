package io.zer0.ai.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contrato de serialización para metadatos de archivos adjuntos de mensajes.
 *
 * Estos JSON se escribirán en `messages.attachmentsJson` Y permanecer en Curry por mucho tiempo.
 * Entonces ambas direcciones deben ser estables:
 *  - Los nuevos campos deben poder guardarse (de lo contrario, si se pierde la ruta del archivo adjunto, ya no se podrá hacer clic en el archivo original);
 *  - Se debe volver a leer el JSON antiguo (de lo contrario, los mensajes históricos no podrán analizarse directamente después de la actualización).
 */
class AttachmentRefSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `workspacePath Capaz de ir y venir`() {
        val original = AttachmentRef(
            id = "a1",
            name = "informe.pdf",
            mimeType = "application/pdf",
            size = 1024L,
            workspacePath = "attachments/informe.pdf",
        )

        val encoded = json.encodeToString(AttachmentRef.serializer(), original)
        val decoded = json.decodeFromString(AttachmentRef.serializer(), encoded)

        assertEquals(original, decoded)
        assertEquals("attachments/informe.pdf", decoded.workspacePath)
    }

    @Test
    fun `El antiguo JSON se puede analizar sin workspacePath`() {
        // Esta forma proviene de datos escritos en la biblioteca antes de que se introdujera workspacePath.
        val legacy = """{"id":"a1","name":"old.pdf","mimeType":"application/pdf","size":10,"sourceType":"FILE"}"""

        val decoded = json.decodeFromString(AttachmentRef.serializer(), legacy)

        assertEquals("old.pdf", decoded.name)
        assertNull(decoded.workspacePath)
    }

    @Test
    fun `Los campos no aparecen en JSON cuando no hay ruta`() {
        // El valor predeterminado no se colocará en el disco y los datos antiguos no se expandirán sin sentido.
        val encoded = json.encodeToString(
            AttachmentRef.serializer(),
            AttachmentRef(id = "a1", name = "x.txt"),
        )

        assertEquals(false, encoded.contains("workspacePath"))
    }
}
