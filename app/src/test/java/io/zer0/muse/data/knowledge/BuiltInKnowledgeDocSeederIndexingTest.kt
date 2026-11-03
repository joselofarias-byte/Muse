package io.zer0.muse.data.knowledge

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-RAG-F1: Las decisiones pendientes para los índices de documentos integrados deben compararse con la huella digital del modelo de incrustación.
 *
 * Anteriormente, sureIndexed solo analizaba chunkCount/contentHash: cambiar el modelo de incrustación(especialmente
 * Modelos con las mismas dimensiones.)después,El documento no se reconstruirá si ya está indexado.,Mezclando vectores antiguos y nuevos → La calidad de la recuperación se degrada silenciosamente.
 */
class BuiltInKnowledgeDocSeederIndexingTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/data/knowledge/BuiltInKnowledgeDocSeeder.kt").readText()

    /** Interceptación segura:Tome un segmento desde el punto de anclaje.(No cruces la línea). */
    private fun body(src: String, anchor: String): String {
        val start = src.indexOf(anchor)
        assertTrue("no encontrado $anchor", start >= 0)
        val end = (start + 900).coerceAtMost(src.length)
        return src.substring(start, end)
    }

    @Test
    fun `pending filter compares embedding model fingerprint`() {
        val src = source()
        val body = body(src, "private fun needsIndexing(")
        assertTrue(
            "El índice a determinar debe compararse con embeddingModel y RagConfig.embeddingModelKey(config)",
            body.contains("doc.embeddingModel == RagConfig.embeddingModelKey(config)") &&
                body.contains("!fingerprintMatches"),
        )
    }

    @Test
    fun `pending filter still covers chunk count and content hash`() {
        val src = source()
        val body = body(src, "private fun needsIndexing(")
        assertTrue(body.contains("doc.chunkCount <= 0"))
        assertTrue(body.contains("doc.contentHash == RagService.computeContentHash(doc.content)"))
        assertTrue(body.contains("!contentMatches"))
    }

    @Test
    fun `reindex writes current embedding model key`() {
        val src = source()
        // Reescritura después de un índice de anclaje exitoso(seed Hay otro lugar en el párrafo. upsert,No se puede apuntar con el primer golpe)
        val start = src.indexOf("chunkCount = chunks,")
        assertTrue("Reescritura de índice no encontrada", start >= 0)
        val body = src.substring(start, start + 300)
        assertTrue(
            "La reescritura debe actualizar la huella digital del modelo incrustado",
            body.contains("embeddingModel = RagConfig.embeddingModelKey(config)"),
        )
    }
}
