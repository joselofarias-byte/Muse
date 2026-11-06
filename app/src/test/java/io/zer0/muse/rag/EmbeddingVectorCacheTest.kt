package io.zer0.muse.rag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * embedding Caché vectorial.
 *
 * El reordenamiento de la correlación de la memoria requiere "query + Todos los datos candidatos se envían para su inclusión.
 * Este es un viaje de ida y vuelta HTTPS bajo el origen de la nube y los datos candidatos casi no han cambiado. —— Lo que está bloqueado aquí es
 * "Los vectores de hechos solo se cuentan una vez y se cargan solo una vez", y"Cambió el modelo/Key No reutilice vectores antiguos más adelante".
 */
class EmbeddingVectorCacheTest {

    /** Registre los parámetros de entrada de cada llamada por lotes para su afirmación."¿Qué texto va realmente en la solicitud?". */
    private class RecordingProvider(
        private val vectorSize: Int = 4,
        private val failAfter: Int = Int.MAX_VALUE,
    ) : EmbeddingProvider {
        override val id: String = "recording"
        override val displayName: String = "Recording"
        override val dimension: Int = vectorSize
        override val modelName: String = "recording-model"
        val batches = mutableListOf<List<String>>()

        override suspend fun embed(texts: List<String>): List<FloatArray> {
            if (batches.size >= failAfter) error("stub failure")
            batches += texts
            return texts.map { text ->
                FloatArray(vectorSize) { index -> (text.length + index).toFloat() }
            }
        }
    }

    private fun store(): EmbeddingVectorStore = EmbeddingVectorStore()

    @Test
    fun `second_call_with_same_texts_hits_cache_without_any_request`() = runBlocking {
        val store = store()
        val provider = RecordingProvider()
        val texts = listOf("Consulta", "Hecho 1", "Hecho 2")

        val first = store.embed(provider, "fp", texts)
        assertNotNull(first)
        assertEquals(1, provider.batches.size)
        assertEquals("Por primera vez, todos los textos deben enviarse juntos", texts, provider.batches[0])

        val second = store.embed(provider, "fp", texts)
        assertNotNull(second)
        assertEquals("No se debe enviar ninguna solicitud por segunda vez.", 1, provider.batches.size)
        assertEquals(first!!.size, second!!.size)
    }

    @Test
    fun `only_uncached_texts_are_requested`() = runBlocking {
        val store = store()
        val provider = RecordingProvider()
        val facts = listOf("Hecho 1", "Hecho 2", "Hecho tres")

        store.embed(provider, "fp", facts)
        assertEquals(3, provider.batches[0].size)

        // La única consulta es nueva: todos los datos deben coincidir y solo queda uno en la solicitud.
        store.embed(provider, "fp", listOf("nueva consulta") + facts)
        assertEquals(2, provider.batches.size)
        assertEquals("La solicitud solo debe contener datos sin caché query", listOf("nueva consulta"), provider.batches[1])
    }

    @Test
    fun `different_fingerprint_does_not_reuse_vectors`() = runBlocking {
        val store = store()
        val provider = RecordingProvider()

        store.embed(provider, "fp-openai-3-small", listOf("hechos"))
        store.embed(provider, "fp-openai-3-large", listOf("hechos"))

        assertEquals("Después de cambiar la huella digital del modelo, se debe recuperar el vector nuevamente.", 2, provider.batches.size)
    }

    @Test
    fun `provider_failure_returns_null_and_does_not_poison_cache`() = runBlocking {
        val store = store()
        val failing = RecordingProvider(failAfter = 0)

        assertNull("Si no se recupera el vector, se debe devolver null para permitir que la persona que llama retroceda.", store.embed(failing, "fp", listOf("hechos")))
        assertEquals(0, store.hits)

        // Después de cambiar a un proveedor normal, aún se debe solicitar el mismo texto (lo que indica falla sin escribir caché)
        val healthy = RecordingProvider()
        val vectors = store.embed(healthy, "fp", listOf("hechos"))
        assertNotNull(vectors)
        assertEquals(1, healthy.batches.size)
    }

    @Test
    fun `result_length_mismatch_returns_null`() = runBlocking {
        val store = store()
        val liar = object : EmbeddingProvider {
            override val id: String = "liar"
            override val displayName: String = "Liar"
            override val dimension: Int = 2
            override val modelName: String = "liar-model"

            override suspend fun embed(texts: List<String>): List<FloatArray> =
                texts.dropLast(1).map { floatArrayOf(1f, 2f) }
        }
        assertNull(
            "Cuando el número de elementos no coincide, el conjunto debe fallar y el vector mal colocado no se puede asignar a otro texto.",
            store.embed(liar, "fp", listOf("a", "b")),
        )
    }

    @Test
    fun `cache_is_bounded_and_evicts_least_recently_used`() {
        val cache = EmbeddingVectorCache(maxEntries = 3)
        repeat(5) { index -> cache.put(cache.key("fp", "text-$index"), floatArrayOf(index.toFloat())) }
        assertEquals(3, cache.size())
        // El texto-0 más antiguo ha sido eliminado.
        assertNull(cache.get(cache.key("fp", "text-0")))
        assertNotNull(cache.get(cache.key("fp", "text-4")))
    }

    @Test
    fun `key_is_stable_and_content_sensitive`() {
        val cache = EmbeddingVectorCache()
        assertEquals(cache.key("fp", "mismo texto"), cache.key("fp", "mismo texto"))
        assertTrue(cache.key("fp", "textoA") != cache.key("fp", "textoB"))
        assertTrue(cache.key("fp1", "mismo texto") != cache.key("fp2", "mismo texto"))
    }
}
