package io.zer0.muse.rag

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Entradas de "sólo mirada/sólo escritura" para la caché de vectores y el tiempo de espera que se debe conservar para las recuperaciones de RAG+Semántica del disyuntor.
 *
 * `RagService` La diferencia entre la incrustación de consultas y la reorganización de la memoria es fundamental:
 *  - Reordenamiento de la memoria: el fracaso es simplemente ordenar y degenerar en el orden de las palabras → Puede devolver nulo silenciosamente;
 *  - RAG Recuperar: punto final"Acepta conexión pero nunca responde"debe ser `withTimeout` interrumpido por**anormal**gatillo
 *    5 Interrupción del circuito de un minuto; de lo contrario, el usuario esperará hasta el readTimeout de OkHttp en la ruta crítica de envío de mensajes.
 *
 * Por lo tanto, la capa de caché solo proporciona dos acciones independientes: "verificar" y "escribir". La persona que llama todavía recupera los datos con tiempo de espera.
 * Este conjunto de casos de uso bloquea estos dos puntos para evitar que lleguen tarde."Práctico"Cámbielo a embedWithCache y retire silenciosamente el disyuntor.
 */
class RagQueryVectorCacheTest {

    @Test
    fun `peek_returns_null_before_store_and_hit_after`() {
        val store = EmbeddingVectorStore()
        assertNull("No se deben encontrar textos no escritos", store.peek("fp", "pregunta"))
        store.store("fp", "pregunta", floatArrayOf(0.5f, 0.25f))
        assertNotNull("Debe poder encontrarse después de escribir.", store.peek("fp", "pregunta"))
    }

    @Test
    fun `peek_does_not_pollute_hit_miss_statistics`() {
        val store = EmbeddingVectorStore()
        store.peek("fp", "señorita")
        assertTrue("Sólo comprobar el caché no debería contar como un error", store.misses == 0)
        assertTrue("Sólo comprobar el caché no debería contar como un acierto.", store.hits == 0)
    }

    @Test
    fun `stored_vector_is_reused_by_batch_embed_without_extra_call`() = runBlockingTest {
        val store = EmbeddingVectorStore()
        var calls = 0
        val provider = object : EmbeddingProvider {
            override val id: String = "stub"
            override val displayName: String = "Stub"
            override val dimension: Int = 2
            override val modelName: String = "stub"
            override suspend fun embed(texts: List<String>): List<FloatArray> {
                calls += 1
                return texts.map { floatArrayOf(1f, 1f) }
            }
        }
        store.store("fp", "hechos", floatArrayOf(0.1f, 0.2f))
        val vectors = store.embed(provider, "fp", listOf("hechos"))
        assertNotNull(vectors)
        assertTrue("No se debe volver a llamar después de acceder al caché. provider", calls == 0)
    }

    @Test
    fun `different_fingerprint_does_not_see_stored_vector`() {
        val store = EmbeddingVectorStore()
        store.store("fp-a", "pregunta", floatArrayOf(1f))
        assertNull("Diferentes huellas dactilares deben considerarse un error (cambiar modelo/Cambiar clave)", store.peek("fp-b", "pregunta"))
    }

    // ── contrato de cableado ──────────────────────────────────────────────

    private fun source(relative: String): String =
        File("src/main/java/io/zer0/muse/$relative").readText()

    @Test
    fun `rag_query_embedding_keeps_timeout_and_circuit_breaker`() {
        val src = source("rag/RagService.kt")
        assertTrue("Primero debe verificar el caché", src.contains("embeddingService.cachedVector(ragConfig, query)"))
        assertTrue("Incluso si fallas, todavía tienes que irte. withTimeout", src.contains("withTimeout(EMBED_QUERY_TIMEOUT_MS) { provider.embed(query) }"))
        assertTrue(
            "La fusión todavía se desencadena por excepciones",
            src.contains("embeddingFailureUntil = System.currentTimeMillis() + EMBEDDING_CIRCUIT_BREAKER_MS"),
        )
        assertTrue("Volver a escribir en la caché después de la recuperación", src.contains("embeddingService.rememberVector(ragConfig, query, queryVector)"))
        assertTrue(
            "No se debe cambiar a embedWithCache (convierte las excepciones en nulas y elimina el disyuntor)",
            !src.contains("embedWithCache(ragConfig, listOf(query))"),
        )
    }
}

/** Consistente con otros casos de uso en el proyecto: escenarios que requieren cuerpos de rutina pero no requieren TestDispatcher. */
private fun runBlockingTest(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
