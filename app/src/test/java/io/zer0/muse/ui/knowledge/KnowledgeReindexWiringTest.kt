package io.zer0.muse.ui.knowledge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KnowledgeReindexWiringTest {

    @Test
    fun `single document reindex does not pass the preview directly to indexDocument`() {
        val source = File("src/main/java/io/zer0/muse/ui/knowledge/KnowledgeScreen.kt").readText()

        assertFalse(
            "El contenido doc. de un documento extenso puede ser solo una vista previa. La reindexación de un solo documento debe utilizar el enlace de recuperación de contenido original.",
            source.contains("ragService.indexDocument(doc.id, doc.content"),
        )
    }

    @Test
    fun `knowledge importers use persistable document access`() {
        // v2.4.6: La página de administración de la base de conocimientos se ha fusionado con KnowledgeScreen en la misma página.,Busque únicamente en la página única fusionada.
        val screen = File("src/main/java/io/zer0/muse/ui/knowledge/KnowledgeScreen.kt").readText()

        assertFalse(screen.contains("ActivityResultContracts.GetContent()"))
        assertTrue(screen.contains("persistKnowledgeUriReadPermission(context, uri)"))
    }
}
