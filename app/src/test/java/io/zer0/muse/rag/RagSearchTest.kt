package io.zer0.muse.rag

import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsDao
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsHit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * P5-1: RAG Prueba de lógica central — No se han realizado pruebas de HybridSearchService/VectorSearchService antes.
 *
 * Portada: FTS estructura de consulta,RRF Clasificación por fusión de doble vía,BM25-only Golpear y mantener(P2-31 Regresar), serialización de BLOB vectoriales.
 */
class RagSearchTest {

    // ── FTS Construcción de consultas ────────────────────────────────────────────────────────

    @Test
    fun `buildFtsQuery strips punctuation and keeps alnum tokens`() {
        assertEquals("muse \"memoria\" rag", HybridSearchService.buildFtsQuery("Muse memoria, rag!"))
    }

    @Test
    fun `buildFtsQuery filters single-char tokens`() {
        assertEquals("\"memoria\" \"sistema\"", HybridSearchService.buildFtsQuery("sistema de memoria sistema de memoria"))
    }

    @Test
    fun `buildFtsQuery dedups tokens`() {
        assertEquals("\"Buscar\" \"recordar\"", HybridSearchService.buildFtsQuery("Buscar Buscar Recuperar"))
    }

    @Test
    fun `buildFtsQuery escapes fts special chars`() {
        assertTrue(!HybridSearchService.buildFtsQuery("\"muse\" rag").contains("\""))
        assertEquals("muse rag", HybridSearchService.buildFtsQuery("\"muse\" rag"))
    }

    @Test
    fun `buildFtsQuery long query falls back to OR`() {
        val query = "Modelo de oración de corpus de prueba de fusión mixta de conocimiento vectorial de recuperación de sistema de memoria"
        val fts = HybridSearchService.buildFtsQuery(query)
        assertTrue("Las consultas largas deben degradarse OR,real: $fts", fts.contains(" OR "))
    }

    @Test
    fun `buildFtsQuery blank query falls back to quoted phrase`() {
        assertEquals("\". . .\"", HybridSearchService.buildFtsQuery(". . ."))
    }

    @Test
    fun `buildFtsQuery expands Chinese text into the same ngrams used by the index`() {
        assertEquals("\"conocimiento\" \"base de conocimientos\"", HybridSearchService.buildFtsQuery("base de conocimientos"))
    }

    // ── RRF Fusión ────────────────────────────────────────────────────────────

    private fun searchResult(chunkId: String, score: Float, docId: String = "doc-$chunkId") = VectorSearchService.SearchResult(
        docId = docId,
        docTitle = "title-$chunkId",
        chunkContent = "content-$chunkId",
        score = score,
        chunkIndex = 1,
        chunkId = chunkId,
    )

    @Test
    fun `rrf fusion ranks both-hit result above single-hit`() = runBlocking {
        val fts = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        val vector = mockk<VectorSearchService>(relaxed = true)
        coEvery { vector.search(any(), any(), any(), any(), any(), any()) } returns listOf(
            searchResult("a", 0.9f),
            searchResult("b", 0.7f),
        )
        coEvery { fts.searchBm25(any(), any()) } returns listOf(
            KnowledgeChunkFtsHit(chunkId = "b", score = -12.0),
        )

        val service = HybridSearchService(fts, vector)
        val results = service.hybridSearch("query", floatArrayOf(1f, 0f), topK = 5, threshold = 0.3f, mmrLambda = 0f)

        assertTrue("El fragmento de golpe de doble vía b debe ocupar el primer lugar: ${results.map { it.chunkId }}", results.first().chunkId == "b")
        assertTrue(results.first().bothHit)
        assertEquals("a", results[1].chunkId)
        assertTrue("both-hit La puntuación debería ser mayor", results.first().rrfScore > results[1].rrfScore)
    }

    @Test
    fun `bm25 only hits are retained when resolver injected`() = runBlocking {
        val fts = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        val vector = mockk<VectorSearchService>(relaxed = true)
        coEvery { vector.search(any(), any(), any(), any(), any(), any()) } returns listOf(
            searchResult("a", 0.9f),
        )
        coEvery { fts.searchBm25(any(), any()) } returns listOf(
            KnowledgeChunkFtsHit(chunkId = "c", score = -20.0),
        )
        val service = HybridSearchService(fts, vector, bm25MetaResolver = { ids ->
            ids.associateWith { id ->
                HybridSearchService.ChunkMeta(
                    docId = "doc-c",
                    docTitle = "Documento de acierto exacto",
                    content = "BM25-only Contenido de éxito preciso",
                    chunkIndex = 2,
                )
            }
        })

        val results = service.hybridSearch("nombre propio", floatArrayOf(0f, 1f), topK = 5, threshold = 0.3f, mmrLambda = 0f)

        assertEquals("BM25-only Los golpes no se pueden descartar(P2-31)", setOf("a", "c"), results.map { it.chunkId }.toSet())
        val c = results.first { it.chunkId == "c" }
        assertEquals("doc-c", c.docId)
    }

    @Test
    fun `bm25 only hits dropped when no resolver and no vector meta`() = runBlocking {
        val fts = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        val vector = mockk<VectorSearchService>(relaxed = true)
        coEvery { vector.search(any(), any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { fts.searchBm25(any(), any()) } returns listOf(
            KnowledgeChunkFtsHit(chunkId = "c", score = -20.0),
        )
        val service = HybridSearchService(fts, vector) // resolver = null
        val results = service.hybridSearch("x", floatArrayOf(0f), topK = 5, threshold = 0.3f, mmrLambda = 0f)
        assertTrue("Se debe omitir si no hay metadatos para analizar.(viejo comportamiento)", results.isEmpty())
    }

    // ── Serialización de BLOB vectoriales ────────────────────────────────────────────────────

    @Test
    fun `floatArrayToBlob roundtrips dimension and values`() {
        val input = floatArrayOf(0.5f, -1.25f, 3.125f, 0f)
        val blob = VectorSearchService.floatArrayToBlob(input)
        assertEquals("4 bytes por flotante", input.size * 4, blob.size)
        val buffer = ByteBuffer.wrap(blob).asFloatBuffer()
        val out = FloatArray(input.size)
        buffer.get(out)
        for (i in input.indices) {
            assertEquals("bit-identical float @$i", input[i], out[i])
        }
    }
}
