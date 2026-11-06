package io.zer0.muse.util

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Caché de recuento de tokens a nivel de mensaje.
 *
 * Antecedentes: el mismo lote de historial en un conjunto de solicitudes se estimará varias veces (presupuesto total, presupuesto histórico, juicio por exceso de límite,
 * ganancia posterior a la compresión), ejecutando cl100k BPE en cada mensaje en cada pasada. prensa de caché**Identidad de instancia**reutilizar,
 * Aquí están bloqueadas tres cosas: el valor es consistente con la implementación original, la misma instancia solo se cuenta una vez y debe recalcularse si se cambia la instancia.
 */
class MessageTokenCacheTest {

    private fun msg(content: String, role: MessageRole = MessageRole.USER) =
        UIMessage(role = role, content = content)

    @Test
    fun `numbers_match_uncached_estimate`() {
        val messages = listOf(
            msg("Hola, ayúdame a resumir este documento."),
            msg("Bien, déjame explicarlo desde tres ángulos.……", MessageRole.ASSISTANT),
            msg("continuar"),
        )
        val uncached = TokenEstimator.estimate(messages)
        val cached = TokenEstimator.estimate(messages, cache = MessageTokenCache())
        assertEquals("El almacenamiento en caché no debe alterar las estimaciones", uncached, cached)
    }

    @Test
    fun `each_message_is_counted_once_per_cache`() {
        val messages = (1..20).map { msg("No. $it pieza de contenido histórico, de longitud adecuada para activar la codificación") }
        val cache = MessageTokenCache()

        TokenEstimator.estimate(messages, cache = cache)
        assertEquals("Todos deberían fallar en el primer pase.", 20, cache.misses)
        assertEquals(0, cache.hits)

        // Segundo paso: el mismo lote de ejemplos (este es el camino utilizado para el presupuesto histórico y el juicio por exceso de límite)
        TokenEstimator.estimate(messages, cache = cache)
        assertEquals("No vuelvas a correr por segunda vez. BPE", 20, cache.misses)
        assertEquals("El segundo pase debería dar en el blanco.", 20, cache.hits)
    }

    @Test
    fun `sub_list_reuses_parent_counts`() {
        // dropWhile / drop Los elementos de la sublista de salida son el mismo lote de instancias que la lista principal y deben llegar al caché.
        val system = msg("Aviso del sistema", MessageRole.SYSTEM)
        val messages = listOf(system) + (1..5).map { msg("historia $it") }
        val cache = MessageTokenCache()

        TokenEstimator.estimate(messages, cache = cache)
        val afterFirst = cache.misses
        TokenEstimator.estimate(messages.dropWhile { it.role == MessageRole.SYSTEM }, cache = cache)

        assertEquals("Las sublistas no deberían producir nuevos errores", afterFirst, cache.misses)
    }

    @Test
    fun `different_instances_are_counted_separately`() {
        val cache = MessageTokenCache()
        val first = msg("Mismo contenido")
        val second = msg("Mismo contenido")

        val a = TokenEstimator.estimate(listOf(first), cache = cache)
        val b = TokenEstimator.estimate(listOf(second), cache = cache)

        assertEquals("Se debe obtener el mismo valor incluso si el contenido es el mismo y las instancias son diferentes.", a, b)
        assertEquals("Los diferentes casos deben contarse por separado.", 2, cache.misses)
    }

    @Test
    fun `counted_value_covers_reasoning_tool_calls_and_images`() {
        val plain = msg("contenido")
        val rich = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "contenido",
            reasoning = "proceso de pensamiento".repeat(10),
            toolCalls = listOf(
                io.zer0.ai.core.ToolCall(
                    id = "call-1",
                    name = "show_card",
                    arguments = "{\"language\":\"svg\",\"code\":\"<svg/>\"}",
                ),
            ),
            imageUrls = listOf("https://example.com/a.png"),
        )
        assertTrue(
            "Contiene pensamiento/Herramientas/Las imágenes deben tener mucho más mensaje que el texto sin formato.",
            TokenEstimator.countMessage(rich) > TokenEstimator.countMessage(plain) + 900,
        )
    }

    @Test
    fun `concurrent use does not corrupt the map`() {
        // P25 Promueva este caché a una instancia compartida a nivel de proceso, y la actualización de ocupación y el ensamblaje de palabras del mensaje provendrán de diferentes subprocesos al mismo tiempo.
        // IdentityHashMap La colocación concurrente dañará la estructura (posiblemente un bucle infinito), así que use subprocesos múltiples para hacerlo nuevamente.
        val cache = MessageTokenCache()
        val messages = (1..50).map { i ->
            UIMessage(role = MessageRole.USER, content = "Mensajes de prueba simultáneos $i".repeat(20))
        }
        val expected = messages.map { TokenEstimator.countMessage(it) }
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = (1..8).map {
            Thread {
                try {
                    repeat(200) { round ->
                        val index = (round + Thread.currentThread().hashCode()) % messages.size
                        check(cache.count(messages[index]) == expected[index]) { "Conteos inconsistentes" }
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(20_000) }
        assertTrue("El acceso simultáneo no debería generar excepciones: ${errors.firstOrNull()}", errors.isEmpty())
    }
}
