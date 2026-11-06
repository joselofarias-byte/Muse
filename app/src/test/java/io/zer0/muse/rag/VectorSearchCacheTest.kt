package io.zer0.muse.rag

import io.zer0.muse.rag.VectorSearchService.ChunkWithDoc
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Caché vectorial: las condiciones de búsqueda alternativas no deben penetrar entre sí.
 *
 * Antecedentes: curry ≤2000 chunk Se cargará y almacenará en caché toda la tabla de vectores. La antigua implementación sólo queda**una porción**, la clave cambia en cualquier momento;
 * Los más comunes en el uso real son "sin filtrado (el diálogo principal usa toda la base de datos)" y "un filtro fijo (@mention / Orientación)
 * aparecer alternativamente —— El almacenamiento en caché de una sola entrada recargará toda la tabla de un lado a otro entre los dos.
 *
 * Aquí, se utilizan alternativamente dos condiciones de filtro para afirmar:
 *  - A → B → A Después de eso,A No se debe volver a cargar toda la tabla;
 *  - El límite superior de entradas entra en vigor (no crecerá ilimitadamente hasta convertirse en un agujero negro de memoria).
 *
 * Nota: Búsqueda dirigida (docIds) inyectando `chunkPageByDocIdsProvider` Cuando se utiliza la ruta de paginación, no se almacenará en caché en absoluto.
 * Por lo tanto, aquí se utiliza el filtrado de metadatos (el proveedor de metadatos no se inyecta en producción y caerá en la ruta del caché).
 */
class VectorSearchCacheTest {

    private val dim = 4

    private fun chunk(index: Int, tag: String) = ChunkWithDoc(
        chunkId = "c-$index",
        docId = "doc-1",
        docTitle = "t",
        content = "content-$index",
        embedding = "",
        embeddingBlob = VectorSearchService.floatArrayToBlob(FloatArray(dim) { 0.5f }),
        chunkIndex = index,
        metadataJson = """{"tag":"$tag"}""",
        createdAt = 0L,
    )

    private class CountingProvider(val chunks: List<ChunkWithDoc>) {
        val pageCalls = AtomicInteger(0)
        val rowsLoaded = AtomicInteger(0)

        suspend fun page(limit: Int, offset: Int): List<ChunkWithDoc> {
            pageCalls.incrementAndGet()
            val page = chunks.drop(offset).take(limit)
            rowsLoaded.addAndGet(page.size)
            return page
        }
    }

    private fun service(provider: CountingProvider) = VectorSearchService(
        chunkPageProvider = { limit, offset -> provider.page(limit, offset) },
        chunkCountProvider = { provider.chunks.size },
    )

    private fun filterOf(tag: String): VectorSearchService.MetadataFilter =
        VectorSearchService.MetadataFilter(tag = tag)

    @Test
    fun `alternating_filters_do_not_thrash_the_cache`() = runBlocking {
        val provider = CountingProvider(List(20) { chunk(it, if (it % 2 == 0) "a" else "b") })
        val service = service(provider)
        val query = FloatArray(dim) { 0.5f }

        service.search(query, topK = 3, threshold = -1f, metadataFilter = filterOf("a"))
        val afterFirst = provider.rowsLoaded.get()
        assertTrue("Se debe cargar toda la tabla por primera vez.", afterFirst >= 20)

        service.search(query, topK = 3, threshold = -1f, metadataFilter = filterOf("b"))
        val afterSecond = provider.rowsLoaded.get()
        assertTrue("Cambiar una condición de filtro requiere cargarlo nuevamente (la caché solo se diferencia por condición)", afterSecond > afterFirst)

        service.search(query, topK = 3, threshold = -1f, metadataFilter = filterOf("a"))
        assertEquals(
            "A → B → A Después de eso, A debe acceder al caché y no puede volver a cargar la tabla completa.",
            afterSecond,
            provider.rowsLoaded.get(),
        )
    }

    @Test
    fun `repeated_same_filter_hits_cache`() = runBlocking {
        val provider = CountingProvider(List(20) { chunk(it, "a") })
        val service = service(provider)
        val query = FloatArray(dim) { 0.5f }

        service.search(query, topK = 3, threshold = -1f, metadataFilter = filterOf("a"))
        val rows = provider.rowsLoaded.get()
        service.search(query, topK = 3, threshold = -1f, metadataFilter = filterOf("a"))
        assertEquals("No se deben sobrecargar consultas repetidas con las mismas condiciones.", rows, provider.rowsLoaded.get())
    }

    @Test
    fun `cache_entry_count_is_bounded`() = runBlocking {
        val provider = CountingProvider(List(20) { chunk(it, "a") })
        val service = service(provider)
        val query = FloatArray(dim) { 0.5f }

        listOf("a", "b", "c", "d").forEach { tag ->
            service.search(query, topK = 3, threshold = -1f, metadataFilter = filterOf(tag))
        }
        // Límite superior 2: volver al principio "a" La sobrecarga es necesaria (lo que demuestra que no existe un crecimiento ilimitado)
        val before = provider.rowsLoaded.get()
        service.search(query, topK = 3, threshold = -1f, metadataFilter = filterOf("a"))
        assertTrue("Las entradas más antiguas deben eliminarse una vez superado el límite superior.", provider.rowsLoaded.get() > before)
        assertTrue("El límite de entrada debe ser 2", VectorSearchService.MAX_CACHE_ENTRIES == 2)
    }

    @Test
    fun `invalidate_cache_forces_reload`() = runBlocking {
        val provider = CountingProvider(List(20) { chunk(it, "a") })
        val service = service(provider)
        val query = FloatArray(dim) { 0.5f }

        service.search(query, topK = 3, threshold = -1f, metadataFilter = filterOf("a"))
        val rows = provider.rowsLoaded.get()
        service.invalidateCache()
        service.search(query, topK = 3, threshold = -1f, metadataFilter = filterOf("a"))
        assertTrue("Debe recargarse después del vencimiento.", provider.rowsLoaded.get() > rows)
    }
}
