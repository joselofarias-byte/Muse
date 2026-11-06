package io.zer0.muse.rag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * P2-33: La capacidad de carga de búsquedas dirigidas a bases de datos grandes es limitada.
 *
 * Escenario de defecto:@mention / El asistente vincula el identificador de búsqueda direccional de KB. `Int.MAX_VALUE` pasar a provider,
 * provider Completado `getByDocIds(...).drop/take` —— Ámbito de búsqueda completo(dakuko >2000 chunk)
 * Cargar la memoria una vez,Luego descarte la página.
 *
 * Después de la reparación:prensa [VectorSearchService.BATCH_SIZE] extracción de paginación,Volumen de carga por llamada de proveedor
 * Hay un límite superior,Y los resultados de la búsqueda son estrictamente equivalentes a "carga completa única"(La colección es consistente con la clasificación.).
 */
class VectorSearchServicePagingTest {

    private val dim = 8

    private fun vec(seed: Int): FloatArray {
        val random = java.util.Random(seed.toLong() * 7919L + 13L)
        return FloatArray(dim) { random.nextFloat() * 2f - 1f }
    }

    private fun chunk(index: Int, docId: String = "doc-1") = VectorSearchService.ChunkWithDoc(
        chunkId = "chunk-$index",
        docId = docId,
        docTitle = "title-$docId",
        content = "content-$index",
        embedding = "",
        embeddingBlob = VectorSearchService.floatArrayToBlob(vec(index)),
        chunkIndex = index,
    )

    /** Registre cada solicitud de llamada limit,Y afirme que una sola carga no exceda BATCH_SIZE. */
    private class PagedProbe(private val all: List<VectorSearchService.ChunkWithDoc>) {
        val requestedLimits = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val calls = AtomicInteger(0)

        suspend fun page(
            @Suppress(
                "UNUSED_PARAMETER",
            ) docIds: List<String>,
            limit: Int,
            offset: Int,
        ): List<VectorSearchService.ChunkWithDoc> {
            calls.incrementAndGet()
            requestedLimits.add(limit)
            return all.drop(offset).take(limit)
        }
    }

    @Test
    fun `large scoped search pages the provider and never over-loads`() = runBlocking {
        val scopeChunks = (0 until 2_500).map { chunk(it) } // > CACHE_THRESHOLD(2000)
        val probe = PagedProbe(scopeChunks)
        val service = VectorSearchService(
            chunkPageProvider = { _, _ -> error("Las búsquedas dirigidas no pueden abarcar toda la búsqueda. provider") },
            chunkCountProvider = { error("Las búsquedas dirigidas no deben leer el recuento completo de la base de datos.") },
            chunkPageByDocIdsProvider = { docIds, limit, offset -> probe.page(docIds, limit, offset) },
        )

        val results = service.search(
            queryVector = vec(7),
            topK = 5,
            threshold = 0.0f,
            mmrLambda = 1.0f,
            scopeDocIds = listOf("doc-1"),
        )

        assertEquals(5, results.size)
        assertTrue("La recuperación dirigida debe realizarse en páginas.", probe.calls.get() >= 5)
        assertTrue(
            "La cantidad de carga en una llamada de un solo proveedor debe tener un límite superior(real=${probe.requestedLimits}),No se le permite aparecer Int.MAX_VALUE",
            probe.requestedLimits.all { it <= VectorSearchService.BATCH_SIZE },
        )
        assertFalse(results.any { it.docId != "doc-1" })

        // Con carga completa + clasificación estable + take(topK) La ingenua implementación de la equivalencia
        val expected = referenceTopK(scopeChunks, vec(7), topK = 5)
        assertEquals(expected.map { it.first }, results.map { it.chunkId })
        assertScoresClose(expected.map { it.second }, results.map { it.score })
    }

    @Test
    fun `paged scoped search is equivalent to a single full load`() = runBlocking {
        val scopeChunks = (0 until 2_500).map { chunk(it) }
        val probe = PagedProbe(scopeChunks)
        val paged = VectorSearchService(
            chunkPageProvider = { _, _ -> error("unused") },
            chunkCountProvider = { error("unused") },
            chunkPageByDocIdsProvider = { docIds, limit, offset -> probe.page(docIds, limit, offset) },
        )
        // viejo sustituto semántico:ignorar limit,La primera llamada devuelve todo el rango de búsqueda.(Equivalente a la antigua carga Int.MAX_VALUE)
        val legacy = VectorSearchService(
            chunkPageProvider = { _, _ -> error("unused") },
            chunkCountProvider = { error("unused") },
            chunkPageByDocIdsProvider = { _, _, offset -> if (offset == 0) scopeChunks else emptyList() },
        )
        val query = vec(11)

        listOf(1.0f, 0.7f).forEach { lambda ->
            listOf(0.0f, 0.2f).forEach { threshold ->
                val pagedResult = paged.search(query, topK = 8, threshold = threshold, mmrLambda = lambda, scopeDocIds = listOf("doc-1"))
                val legacyResult = legacy.search(query, topK = 8, threshold = threshold, mmrLambda = lambda, scopeDocIds = listOf("doc-1"))
                assertEquals(
                    "λ=$lambda threshold=$threshold conjunto de candidatos/La clasificación debe ser coherente con la carga completa.",
                    legacyResult.map { it.chunkId },
                    pagedResult.map { it.chunkId },
                )
                assertScoresClose(legacyResult.map { it.score }, pagedResult.map { it.score })
            }
        }
    }

    @Test
    fun `metadata filtered search pages the provider`() = runBlocking {
        val all = (0 until 2_500).map { chunk(it).copy(metadataJson = """{"tag":"rag"}""") }
        val requestedLimits = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val service = VectorSearchService(
            chunkPageProvider = { _, _ -> error("unused") },
            chunkCountProvider = { error("unused") },
            chunkPageByDocIdsProvider = null,
            chunkPageByMetadataProvider = { _, limit, offset ->
                requestedLimits.add(limit)
                all.drop(offset).take(limit)
            },
        )

        val results = service.search(
            queryVector = vec(3),
            topK = 4,
            threshold = 0.0f,
            mmrLambda = 1.0f,
            metadataFilter = VectorSearchService.MetadataFilter(tag = "rag"),
        )

        assertEquals(4, results.size)
        assertTrue(requestedLimits.size >= 5)
        assertTrue("metadata La ruta tampoco debe cargarse toda a la vez(real=$requestedLimits)", requestedLimits.all { it <= VectorSearchService.BATCH_SIZE })
    }

    @Test
    fun `equal scores keep scan order like a stable sort`() = runBlocking {
        // Consulta del mismo vector + misma dirección → Las puntuaciones son exactamente las mismas.(cos=1.0,No se filtrará por umbral):
        // El top-K acotado debe ser coherente con el orden estable(Mantenga los escaneados antes).
        // Tenga en cuenta que el vector de consulta debe estar en la misma dirección que el vector de bloque.,De lo contrario, el coseno puede ser negativo y ser threshold=0.0 Eliminarlos a todos.
        val aligned = FloatArray(dim) { 0.5f }
        val same = VectorSearchService.floatArrayToBlob(aligned)
        val chunks = (0 until 40).map { index ->
            chunk(index).copy(embeddingBlob = same)
        }
        val service = VectorSearchService(
            chunkPageProvider = { _, _ -> error("unused") },
            chunkCountProvider = { error("unused") },
            chunkPageByDocIdsProvider = { _, limit, offset -> chunks.drop(offset).take(limit) },
        )

        val results = service.search(aligned, topK = 5, threshold = 0.0f, mmrLambda = 1.0f, scopeDocIds = listOf("doc-1"))
        assertEquals(listOf("chunk-0", "chunk-1", "chunk-2", "chunk-3", "chunk-4"), results.map { it.chunkId })
        // Todos los puntajes empatados deben pasar threshold,Evite que este caso de uso degenere en una afirmación inactiva de "los resultados nulos son iguales entre sí"
        assertTrue("Las puntuaciones empatadas no deben filtrarse por umbral(puntuación real=${results.map { it.score }})", results.all { it.score >= 0f })
    }

    /** Implementación sencilla:Carga completa al mismo tiempo + puntuación + orden descendente estable + take(topK)(threshold=0 Mantenga fracciones no negativas cuando). */
    private fun referenceTopK(chunks: List<VectorSearchService.ChunkWithDoc>, query: FloatArray, topK: Int): List<Pair<String, Float>> {
        val queryNorm = kotlin.math.sqrt(query.sumOf { (it * it).toDouble() }).toFloat()
        return chunks.mapNotNull { c ->
            val vector = FloatArray(dim) { i -> java.nio.ByteBuffer.wrap(c.embeddingBlob!!).asFloatBuffer().get(i) }
            val norm = kotlin.math.sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
            if (norm == 0f) return@mapNotNull null
            var dot = 0f
            for (i in 0 until dim) dot += vector[i] * query[i]
            val score = dot / (norm * queryNorm)
            if (score < 0f) return@mapNotNull null
            c.chunkId to score
        }
            .sortedByDescending { it.second }
            .take(topK)
    }

    /** Comparación de puntuaciones:El orden de acumulación de punto flotante en ambos lados es diferente,Se permiten errores de nivel 1e-4. */
    private fun assertScoresClose(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { i ->
            assertTrue(
                "score[$i] Expectativa=${expected[i]} real=${actual[i]}",
                kotlin.math.abs(expected[i] - actual[i]) < 1e-4f,
            )
        }
    }
}
