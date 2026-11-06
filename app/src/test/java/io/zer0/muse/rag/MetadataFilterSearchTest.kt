package io.zer0.muse.rag

import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsDao
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsHit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B4-03: RAG Prueba de filtrado de metadatos.
 *
 * Cubrir puntos de aceptación:
 *  - sin filtro = cantidad total(metadataFilter Valor predeterminado nulo/vacío → Las personas que llaman existentes se comportan sin cambios)
 *  - Después de filtrar por documentId,enlace vectorial con BM25(Búsqueda híbrida)Los resultados del enlace son consistentes
 *  - La coincidencia de título/fuente funciona igual de bien en ambos enlaces
 *  - [VectorSearchService.MetadataFilter.matches] Determinación dimensión por dimensión
 *
 * datos de prueba:2 bloques cada uno para ambos documentos,Todos los vectores de bloque están en la misma dirección que la consulta.(El coseno es siempre 1.0),
 * Entonces, el conjunto de resultados solo está determinado por el filtrado de metadatos.,No afectado por el umbral de similitud.
 */
class MetadataFilterSearchTest {

    private val dim = 4

    /** Todos los bloques comparten el mismo vector.,Y en la misma dirección que el vector de consulta. → La similitud del coseno es siempre 1,0. */
    private val sharedVector = FloatArray(dim) { 0.5f }

    private data class Chunk(
        val chunkId: String,
        val docId: String,
        val docTitle: String,
        val metadataJson: String,
        val createdAt: Long = 0L,
    )

    private val chunks = listOf(
        Chunk("A1", "doc-A", "Principios generales del derecho contractual", """{"source":"upload/legal"}"""),
        Chunk("A2", "doc-A", "Principios generales del derecho contractual", """{"source":"upload/legal"}"""),
        Chunk("B1", "doc-B", "ley de responsabilidad extracontractual", """{"source":"web"}"""),
        Chunk("B2", "doc-B", "ley de responsabilidad extracontractual", """{"source":"web"}"""),
    )

    private fun toChunkWithDoc(c: Chunk) = VectorSearchService.ChunkWithDoc(
        chunkId = c.chunkId,
        docId = c.docId,
        docTitle = c.docTitle,
        content = "content-${c.chunkId}",
        embedding = "",
        embeddingBlob = VectorSearchService.floatArrayToBlob(sharedVector),
        chunkIndex = 0,
        metadataJson = c.metadataJson,
        createdAt = c.createdAt,
    )

    private fun vectorService() = VectorSearchService(
        chunkPageProvider = { limit, offset -> chunks.map(::toChunkWithDoc).drop(offset).take(limit) },
        chunkCountProvider = { chunks.size },
    )

    /** BM25 El golpe abarca dos documentos:Proporcione ejemplos que \"deben ser excluidos por el enlace BM25\" para filtrar casos de uso. */
    private fun ftsDao(): KnowledgeChunkFtsDao {
        val fts = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        coEvery { fts.searchBm25(any(), any()) } returns listOf(
            KnowledgeChunkFtsHit(chunkId = "A1", score = -10.0),
            KnowledgeChunkFtsHit(chunkId = "B1", score = -11.0),
            KnowledgeChunkFtsHit(chunkId = "A2", score = -12.0),
        )
        return fts
    }

    private fun hybridService(vector: VectorSearchService) = HybridSearchService(
        ftsDao = ftsDao(),
        vectorSearch = vector,
        bm25MetaResolver = { ids ->
            ids.associateWith { id ->
                val c = chunks.first { it.chunkId == id }
                HybridSearchService.ChunkMeta(
                    docId = c.docId,
                    docTitle = c.docTitle,
                    content = "content-$id",
                    chunkIndex = 0,
                    metadataJson = c.metadataJson,
                    createdAt = c.createdAt,
                )
            }
        },
    )

    @Test
    fun `no filter returns all documents on both paths`() = runBlocking {
        val vector = vectorService()
        val vectorHits = vector.search(sharedVector, topK = 10, threshold = 0f)
        assertEquals(
            "El enlace vectorial debe devolver todos los bloques sin filtrar",
            chunks.map { it.chunkId }.toSet(),
            vectorHits.map { it.chunkId }.toSet(),
        )
        assertEquals(setOf("doc-A", "doc-B"), vectorHits.map { it.docId }.toSet())

        val hybridHits = hybridService(vector).hybridSearch(
            query = "Agravio de derecho contractual",
            queryVector = sharedVector,
            topK = 10,
            threshold = 0f,
            mmrLambda = 1f,
        )
        assertEquals(
            "Mezclar sin filtración(BM25+Vector)El enlace debería devolver ambos documentos.",
            setOf("doc-A", "doc-B"),
            hybridHits.map { it.docId }.toSet(),
        )
    }

    @Test
    fun `document id filter applies to both vector and bm25 consistently`() = runBlocking {
        val filter = VectorSearchService.MetadataFilter(docIds = listOf("doc-A"))

        val vector = vectorService()
        val vectorHits = vector.search(sharedVector, topK = 10, threshold = 0f, metadataFilter = filter)
        assertTrue("Los enlaces vectoriales no deben estar vacíos después de filtrar por docId", vectorHits.isNotEmpty())
        assertTrue(
            "Después del filtrado, los enlaces vectoriales solo pueden contener doc-A,real=${vectorHits.map { it.docId }}",
            vectorHits.all { it.docId == "doc-A" },
        )

        // enlace híbrido:lado del vector filtrado,BM25 El bando también debe eliminar los impactos del doc-B.(B1)
        val hybridHits = hybridService(vector).hybridSearch(
            query = "derecho contractual",
            queryVector = sharedVector,
            topK = 10,
            threshold = 0f,
            mmrLambda = 1f,
            metadataFilter = filter,
        )
        assertTrue("Los enlaces híbridos no deben estar vacíos después de filtrar por docId", hybridHits.isNotEmpty())
        assertTrue(
            "BM25(mezclar)Después del filtrado del enlace, solo puede contener doc-A,real=${hybridHits.map { it.docId }}",
            hybridHits.all { it.docId == "doc-A" },
        )
        assertTrue(
            "BM25 El lado debe eliminar los bloques que no sean doc-A fuera de A1/A2.",
            hybridHits.none { it.chunkId == "B1" },
        )
        assertEquals(
            "enlace vectorial con BM25(mezclar)Las colecciones de documentos vinculados deben ser consistentes",
            vectorHits.map { it.docId }.toSet(),
            hybridHits.map { it.docId }.toSet(),
        )
    }

    @Test
    fun `title keyword filter applies to both paths`() = runBlocking {
        val filter = VectorSearchService.MetadataFilter(titleKeyword = "extracontractual")

        val vector = vectorService()
        val vectorHits = vector.search(sharedVector, topK = 10, threshold = 0f, metadataFilter = filter)
        assertTrue(vectorHits.isNotEmpty())
        assertTrue("Después del filtrado del encabezado, los enlaces vectoriales deben quedar solo con doc-B", vectorHits.all { it.docId == "doc-B" })

        val hybridHits = hybridService(vector).hybridSearch(
            query = "extracontractual",
            queryVector = sharedVector,
            topK = 10,
            threshold = 0f,
            mmrLambda = 1f,
            metadataFilter = filter,
        )
        assertTrue(hybridHits.isNotEmpty())
        assertTrue("Después del filtrado de encabezados, los enlaces híbridos deben quedar solo con doc-B", hybridHits.all { it.docId == "doc-B" })
    }

    @Test
    fun `source keyword filter applies to both paths`() = runBlocking {
        val filter = VectorSearchService.MetadataFilter(sourceKeyword = "web")

        val vector = vectorService()
        val vectorHits = vector.search(sharedVector, topK = 10, threshold = 0f, metadataFilter = filter)
        assertTrue(vectorHits.isNotEmpty())
        assertTrue("Después del filtrado de fuente, los enlaces vectoriales deben quedar solo con doc-B", vectorHits.all { it.docId == "doc-B" })

        val hybridHits = hybridService(vector).hybridSearch(
            query = "infracción",
            queryVector = sharedVector,
            topK = 10,
            threshold = 0f,
            mmrLambda = 1f,
            metadataFilter = filter,
        )
        assertTrue(hybridHits.isNotEmpty())
        assertTrue("Después del filtrado de fuente, el enlace híbrido debe quedar solo con doc-B", hybridHits.all { it.docId == "doc-B" })
    }

    @Test
    fun `empty filter is treated as no filter`() = runBlocking {
        val filter = VectorSearchService.MetadataFilter()
        assertTrue("Un filtro completamente vacío debe considerarse como sin filtrado.", filter.isEmpty())

        val hits = vectorService().search(sharedVector, topK = 10, threshold = 0f, metadataFilter = filter)
        assertEquals(
            "Los resultados del filtro vacío deben ser consistentes con los resultados sin filtrado.",
            chunks.map { it.chunkId }.toSet(),
            hits.map { it.chunkId }.toSet(),
        )
    }

    @Test
    fun `metadata filter matches evaluates each dimension`() {
        val filter = VectorSearchService.MetadataFilter(
            docIds = listOf("doc-A"),
            tag = "rag",
            startTime = 100L,
            endTime = 200L,
            titleKeyword = "contractual",
            sourceKeyword = "legal",
        )
        assertFalse(filter.isEmpty())
        val meta = """{"tag":"rag","source":"legal"}"""

        assertTrue("Todas las condiciones golpean", filter.matches("doc-A", "derecho contractual", meta, 150L))
        assertFalse("docId señorita", filter.matches("doc-B", "derecho contractual", meta, 150L))
        assertFalse("Título perdido", filter.matches("doc-A", "ley de daños", meta, 150L))
        assertFalse("Falta la fuente", filter.matches("doc-A", "derecho contractual", """{"tag":"rag","source":"web"}""", 150L))
        assertFalse("tag señorita", filter.matches("doc-A", "derecho contractual", """{"source":"legal"}""", 150L))
        assertFalse("antes que startTime", filter.matches("doc-A", "derecho contractual", meta, 50L))
        assertFalse("más tarde que endTime", filter.matches("doc-A", "derecho contractual", meta, 250L))
    }
}
