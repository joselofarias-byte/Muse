package io.zer0.muse.rag

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.zer0.muse.data.knowledge.KnowledgeChunkDao
import io.zer0.muse.data.knowledge.KnowledgeChunkEntity
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsDao
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * v2.x: índice de transmisión([RagService.indexDocumentStreamed])prueba única — Los enlaces de importación de archivos grandes vuelven a la línea de defensa.
 *
 * fondo:La importación de la base de conocimientos cambió de "lectura de texto completo en la memoria + fragmentación completa" a "ingesta de transmisión por ventana deslizante",Admite archivos de cualquier tamaño.
 * Fije la semántica clave aquí:Contenido completo(No más truncamientos de 500.000 caracteres), escritura incrustada en lotes,
 * La redirección limpia bloques antiguos, secuencias vacías y no destruye los datos existentes cuando las dimensiones no coinciden.
 */
class RagStreamedIndexTest {

    private class FakeEmbeddingProvider(private val dim: Int = 8) : EmbeddingProvider {
        override val id = "fake"
        override val displayName = "Fake Provider"
        override val dimension = dim
        override val modelName = "fake-model"
        val batches = mutableListOf<List<String>>()

        override suspend fun embed(texts: List<String>): List<FloatArray> {
            batches.add(texts.toList())
            return texts.map { t -> FloatArray(dim) { i -> (t.length + i).toFloat() } }
        }
    }

    private fun buildService(
        chunkDao: KnowledgeChunkDao,
        docDao: KnowledgeDocDao,
        ftsDao: KnowledgeChunkFtsDao,
        provider: EmbeddingProvider,
        indexFile: java.io.File? = null,
    ): RagService {
        val embeddingService = mockk<EmbeddingService>(relaxed = true)
        coEvery { embeddingService.getProvider(any()) } returns provider
        return RagService(
            chunkDao = chunkDao,
            docDao = docDao,
            ftsDao = ftsDao,
            docTitleProvider = { emptyMap() },
            embeddingService = embeddingService,
            indexFile = indexFile,
        )
    }

    private class FingerprintedEmbeddingProvider(
        override val dimension: Int = 2,
        override val modelName: String = "current-model",
    ) : EmbeddingProvider {
        override val id = "fake"
        override val displayName = "Fake Provider"

        override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { text ->
            when {
                text == "query" -> FloatArray(dimension) { if (it == 0) 1f else 0f }
                text.startsWith("new") -> FloatArray(dimension) { if (it == 0) 0.8f else if (it == 1) 0.6f else 0f }
                else -> FloatArray(dimension) { if (it == 0) 1f else 0f }
            }
        }
    }

    private class VectorPageReadCounter(var count: Int = 0)

    private data class FingerprintReindexFixture(
        val tempDir: java.io.File,
        val service: RagService,
        val config: RagConfig,
        val pageReads: VectorPageReadCounter,
    )

    private fun createFingerprintReindexFixture(
        provider: FingerprintedEmbeddingProvider = FingerprintedEmbeddingProvider(),
    ): FingerprintReindexFixture {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "rag_fingerprint_${System.nanoTime()}")
        check(tempDir.mkdirs())
        val documents = listOf(
            KnowledgeDocEntity("old-doc", "Old", content = "old content", kbId = "default"),
            KnowledgeDocEntity("new-doc", "New", content = "new content", kbId = "default"),
            KnowledgeDocEntity("empty-doc", "Empty", content = "", kbId = "default"),
        )
        val chunks = mutableListOf(
            KnowledgeChunkEntity(
                id = "old-chunk",
                docId = documents.first().id,
                content = "old chunk",
                embeddingBlob = VectorSearchService.floatArrayToBlob(floatArrayOf(1f, 0f)),
            ),
            KnowledgeChunkEntity(
                id = "empty-old-chunk",
                docId = documents.last().id,
                content = "stale content",
                embeddingBlob = VectorSearchService.floatArrayToBlob(floatArrayOf(1f, 0f)),
            ),
        )
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        val pageReads = VectorPageReadCounter()
        coEvery { chunkDao.getFirstIndexedEmbeddingDim() } returns 2
        coEvery { chunkDao.getAllWithEmbedding() } coAnswers { chunks.toList() }
        coEvery { chunkDao.getByDoc(any()) } coAnswers { chunks.filter { it.docId == firstArg() } }
        coEvery { chunkDao.deleteByDoc(any()) } coAnswers {
            val remaining = chunks.filterNot { it.docId == firstArg<String>() }
            chunks.clear()
            chunks.addAll(remaining)
        }
        coEvery { chunkDao.insertAll(any()) } coAnswers { chunks.addAll(firstArg<List<KnowledgeChunkEntity>>()) }
        coEvery { chunkDao.countIndexed() } coAnswers { chunks.size }
        coEvery { chunkDao.getPageWithEmbedding(any(), any()) } coAnswers {
            pageReads.count++
            chunks.toList()
        }
        coEvery { docDao.getByKbIds(any()) } returns documents
        coEvery { docDao.getAll() } returns documents
        coEvery { docDao.getByIds(any()) } coAnswers {
            val ids = firstArg<List<String>>().toSet()
            documents.filter { it.id in ids }
        }

        val indexFile = java.io.File(tempDir, "hnsw.bin")
        HnswVectorIndex().apply {
            add("old-chunk", floatArrayOf(1f, 0f))
            add("empty-old-chunk", floatArrayOf(1f, 0f))
            save(indexFile, "fake\u0000old-model\u00002")
        }
        val embeddingService = mockk<EmbeddingService>(relaxed = true)
        coEvery { embeddingService.getProvider(any()) } returns provider
        val service = RagService(
            chunkDao = chunkDao,
            docDao = docDao,
            ftsDao = ftsDao,
            docTitleProvider = { mapOf("old-doc" to "Old", "new-doc" to "New") },
            embeddingService = embeddingService,
            indexFile = indexFile,
        )
        val config = RagConfig(chunkSize = 100, chunkOverlap = 10, markdownAware = false, threshold = 0.1f)
        return FingerprintReindexFixture(tempDir, service, config, pageReads)
    }

    @Test
    fun `streamed index preserves all content across windows and batches`() = runBlocking {
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        coEvery { chunkDao.getFirstIndexedEmbeddingDim() } returns null
        val provider = FakeEmbeddingProvider()
        val service = buildService(chunkDao, docDao, ftsDao, provider)

        // 300 segmento,cada párrafo ~200 personaje → ~64K personaje;windowChars=2048 → múltiples ventanas;chunkSize=300 → Múltiples bloques y múltiples lotes
        val segments = (0 until 300).map { i -> "SEG%03d ".format(i) + "Contenido del texto".repeat(48) }
        val fullText = segments.joinToString("\n\n")
        val pieces = fullText.chunked(4096)
        val progress = mutableListOf<Int>()
        val count = service.indexDocumentStreamed(
            docId = "doc-stream-1",
            textPieces = flow { pieces.forEach { emit(it) } },
            ragConfig = RagConfig(chunkSize = 300, chunkOverlap = 30, markdownAware = false),
            windowChars = 2048,
            onProgress = { progress.add(it) },
        )

        assertTrue("Se deben producir múltiples trozos.,real $count", count > 50)
        assertTrue("Debe integrarse en lotes(número de lote=${provider.batches.size})", provider.batches.size >= 2)
        assertEquals("El progreso debe incrementarse hasta el final.", count, progress.last())
        assertEquals("El progreso no debe ser decreciente.", progress.sorted(), progress)
        val all = provider.batches.flatten().joinToString("\n")
        for (tag in listOf("SEG000", "SEG150", "SEG299")) {
            assertTrue("Párrafo faltante $tag", all.contains(tag))
        }
    }

    @Test
    fun `streamed index does not truncate beyond legacy 500K char limit`() = runBlocking {
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        coEvery { chunkDao.getFirstIndexedEmbeddingDim() } returns null
        val provider = FakeEmbeddingProvider()
        val service = buildService(chunkDao, docDao, ftsDao, provider)

        // 600K+ personaje(Más allá de la antigua línea de corte de 500K),Marcas centinela en la cabeza y la cola.
        val head = "HEAD-MARK "
        val tail = " TAIL-MARK"
        val body = "relleno de texto largo".repeat(120_000)
        val fullText = head + body + tail
        val pieces = fullText.chunked(8192)
        val count = service.indexDocumentStreamed(
            docId = "doc-big",
            textPieces = flow { pieces.forEach { emit(it) } },
            ragConfig = RagConfig(chunkSize = 1000, chunkOverlap = 100, markdownAware = false),
            windowChars = 8192,
        )

        assertTrue("Los archivos grandes deberían producir más bloques,real $count", count > 100)
        val all = provider.batches.flatten().joinToString("\n")
        assertTrue("Falta el contenido del encabezado", all.contains("HEAD-MARK"))
        assertTrue("Falta el contenido de la cola(El truncamiento de 500K de la implementación anterior perderá cola)", all.contains("TAIL-MARK"))
    }

    @Test
    fun `streamed index clears previous chunks before writing new ones`() = runBlocking {
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        coEvery { chunkDao.getFirstIndexedEmbeddingDim() } returns null
        val provider = FakeEmbeddingProvider()
        val service = buildService(chunkDao, docDao, ftsDao, provider)

        service.indexDocumentStreamed(
            docId = "doc-same",
            textPieces = flowOf("Este es un documento de prueba que necesita ser reindexado.,El contenido es suficiente para generar al menos un fragmento."),
            ragConfig = RagConfig(chunkSize = 100, chunkOverlap = 10),
            windowChars = 1024,
        )

        coVerify { chunkDao.deleteByDoc("doc-same") }
        coVerify { ftsDao.deleteByDoc("doc-same") }
        coVerify { chunkDao.insertAll(any()) }
    }

    @Test
    fun `streamed index with empty flow keeps existing chunks untouched`() = runBlocking {
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        coEvery { chunkDao.getFirstIndexedEmbeddingDim() } returns null
        val provider = FakeEmbeddingProvider()
        val service = buildService(chunkDao, docDao, ftsDao, provider)

        val count = service.indexDocumentStreamed(
            docId = "doc-empty",
            textPieces = flowOf<String>(),
            ragConfig = RagConfig(),
            windowChars = 1024,
        )

        assertEquals(0, count)
        coVerify(exactly = 0) { chunkDao.deleteByDoc(any()) }
        coVerify(exactly = 0) { chunkDao.insertAll(any()) }
    }

    @Test
    fun `streamed index aborts on dimension mismatch without clearing old chunks`() = runBlocking {
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        // Ya hay 16 dimensiones en la biblioteca. chunk,El nuevo proveedor genera 8 dimensiones → Debe cancelar sin limpiar bloques viejos
        coEvery { chunkDao.getFirstIndexedEmbeddingDim() } returns 16
        val provider = FakeEmbeddingProvider(dim = 8)
        val service = buildService(chunkDao, docDao, ftsDao, provider)

        try {
            service.indexDocumentStreamed(
                docId = "doc-dim",
                textPieces = flowOf("No se deben escribir datos cuando las dimensiones no coincidan."),
                ragConfig = RagConfig(chunkSize = 100, chunkOverlap = 10),
                windowChars = 1024,
            )
            fail("Se debe generar una excepción de discrepancia de dimensiones")
        } catch (e: IllegalStateException) {
            assertTrue("La información de excepción debe indicar una discrepancia de dimensiones: ${e.message}", e.message.orEmpty().contains("dimension mismatch"))
        }
        coVerify(exactly = 0) { chunkDao.deleteByDoc("doc-dim") }
        coVerify(exactly = 0) { chunkDao.insertAll(any()) }
    }

    @Test
    fun `partial reindex after embedding model switch uses full vector search until all KBs rebuild`() = runBlocking {
        val fixture = createFingerprintReindexFixture()
        try {
            fixture.service.indexDocument("new-doc", "new document content", fixture.config)
            val beforeFullReindex = fixture.service.retrieve("query", 1, 0.1f, fixture.config)
            assertTrue("No mezcle vectores de bases de datos antiguas con puntuaciones de modelos nuevos después de que las huellas dactilares no coincidan", beforeFullReindex.isEmpty())
            assertEquals("Toda la recuperación de vectores debe desactivarse durante la discrepancia de huellas dactilares.", 0, fixture.pageReads.count)
            fixture.service.saveVectorIndex()
            assertThrows(HnswEmbeddingFingerprintMismatchException::class.java) {
                HnswVectorIndex().load(
                    java.io.File(fixture.tempDir, "hnsw.bin"),
                    "fake\u0000current-model\u00002",
                )
            }

            val failures = fixture.service.reindexAllInKbs(listOf("default"), fixture.config)
            assertTrue("La reconstrucción completa de KB debería realizarse correctamente: $failures", failures.isEmpty())
            fixture.pageReads.count = 0
            val afterFullReindex = fixture.service.retrieve("query", 3, 0.1f, fixture.config)
            assertEquals("El vecino más cercano debería seguir siendo correcto después de una reconstrucción completa", "chunk-old-doc-0", afterFullReindex.first().chunkId)
            assertEquals(setOf("chunk-old-doc-0", "chunk-new-doc-0"), afterFullReindex.map { it.chunkId }.toSet())
            assertEquals("La recuperación de HNSW después de la reconstrucción completa no debería revertir la tabla de vectores de escaneo", 0, fixture.pageReads.count)
        } finally {
            fixture.tempDir.listFiles()?.forEach { it.delete() }
            fixture.tempDir.delete()
        }
    }

    @Test
    fun `full reindex can replace every vector after embedding dimension changes`() = runBlocking {
        val fixture = createFingerprintReindexFixture(
            provider = FingerprintedEmbeddingProvider(dimension = 3, modelName = "new-dimension-model"),
        )
        try {
            val failures = fixture.service.reindexAllInKbs(listOf("default"), fixture.config)

            assertTrue("El cambio y la reconstrucción completos de las dimensiones de la base de datos deberían tener éxito: $failures", failures.isEmpty())
            fixture.pageReads.count = 0
            val results = fixture.service.retrieve("query", 3, 0.1f, fixture.config)
            assertEquals(setOf("chunk-old-doc-0", "chunk-new-doc-0"), results.map { it.chunkId }.toSet())
            assertEquals("Debe volver a habilitarse después de completar el cambio y la reconstrucción completos de la dimensión de la base de datos. HNSW", 0, fixture.pageReads.count)
        } finally {
            fixture.tempDir.listFiles()?.forEach { it.delete() }
            fixture.tempDir.delete()
        }
    }

    @Test
    fun `oversized plain chunk is split when markdownAware merges structureless text`() = runBlocking {
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        coEvery { chunkDao.getFirstIndexedEmbeddingDim() } returns null
        val provider = FakeEmbeddingProvider()
        val service = buildService(chunkDao, docDao, ftsDao, provider)

        // markdownAware=true(Predeterminado)+ Texto largo sin estructura Markdown:TextChunker fusionarse en trozos enormes,
        // La segmentación secundaria garantizada de la ingestión de streaming debe reducirse al nivel targetSize,Evite que grandes trozos entren en incrustación/recuperación
        val body = "Texto largo de párrafo puro".repeat(20_000) // 120K personaje,Sin saltos de línea, sin puntuación
        val chunkSize = 500
        val count = service.indexDocumentStreamed(
            docId = "doc-plain",
            textPieces = flowOf(body),
            ragConfig = RagConfig(chunkSize = chunkSize, chunkOverlap = 50, markdownAware = true),
            windowChars = 8192,
        )

        assertTrue("Se deben producir múltiples trozos.,real $count", count > 50)
        val maxLen = provider.batches.flatten().maxOf { it.length }
        assertTrue("La longitud del bloque debe cortarse en ${chunkSize * 2} dentro,Máximo real $maxLen", maxLen <= chunkSize * 2)
    }

    @Test
    fun `oversized code block is preserved under fallback split`() = runBlocking {
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        coEvery { chunkDao.getFirstIndexedEmbeddingDim() } returns null
        val provider = FakeEmbeddingProvider()
        val service = buildService(chunkDao, docDao, ftsDao, provider)

        // bloque de código grande(exceder 2×chunkSize)debe conservarse en su totalidad,No afectado por la división garantizada
        val codeBlock = "```\n" + "val line = 1\n".repeat(300) + "```"
        val text = "Texto de descripción.\n\n$codeBlock\n\nEl fin."
        val count = service.indexDocumentStreamed(
            docId = "doc-code",
            textPieces = flowOf(text),
            ragConfig = RagConfig(chunkSize = 200, chunkOverlap = 20, markdownAware = true),
            windowChars = 4096,
        )

        assertTrue("Debe producir al menos un bloque.", count >= 1)
        val codeChunk = provider.batches.flatten().firstOrNull { it.contains("```") }
        assertTrue("El bloque de código debería existir.", codeChunk != null)
        assertTrue("Los bloques de código deben conservarse en su totalidad.(No demasiado largo y difícil de cortar.),longitud real ${codeChunk!!.length}", codeChunk.length > 200 * 2)
    }
}
