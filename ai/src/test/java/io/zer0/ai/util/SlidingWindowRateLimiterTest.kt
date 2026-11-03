package io.zer0.ai.util

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-3: [SlidingWindowRateLimiter] Pruebas unitarias.
 *
 * usar `runTest` tiempo virtual + inyección `clock = { testScheduler.currentTime }` Deje que la ventana del limitador actual juzgue
 * con `delay` El avance del tiempo virtual de,Verificación determinista"Espera más allá del límite → ventana corredera → liberar".
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SlidingWindowRateLimiterTest {

    @Test
    fun `acquire should allow up to maxRequests without delay`() = runTest {
        val limiter = SlidingWindowRateLimiter(maxRequests = 3, windowMs = 1_000, clock = { testScheduler.currentTime })
        limiter.acquire()
        limiter.acquire()
        limiter.acquire()
        assertEquals(3, limiter.currentCount())
        // 3 Obtener inmediatamente,El tiempo virtual no ha avanzado
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `acquire should wait until window slides when full`() = runTest {
        val limiter = SlidingWindowRateLimiter(maxRequests = 2, windowMs = 1_000, clock = { testScheduler.currentTime })
        limiter.acquire() // t=0
        limiter.acquire() // t=0
        assertEquals(0L, testScheduler.currentTime)

        // tercera vez:la ventana está llena(t=0 Hay 2 artículos a veces),hay que esperar hasta t=1000 Primera ventana deslizable
        limiter.acquire()
        // El tiempo virtual debe adelantarse a 1000(Primera marca de tiempo 0 + windowMs 1000)
        assertEquals(1_000L, testScheduler.currentTime)
        // t=1000 tiempo:cutoff = 1000-1000 = 0,t=0 ambos de <= 0 ser aclarado,Nuevo t=1000 una pieza → size=1
        assertEquals(1, limiter.currentCount())
    }

    @Test
    fun `acquire should reject non-positive maxRequests`() {
        assertThrows(IllegalArgumentException::class.java) {
            SlidingWindowRateLimiter(maxRequests = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SlidingWindowRateLimiter(maxRequests = -1)
        }
    }

    @Test
    fun `acquire should reject non-positive windowMs`() {
        assertThrows(IllegalArgumentException::class.java) {
            SlidingWindowRateLimiter(maxRequests = 1, windowMs = 0)
        }
    }

    @Test
    fun `currentCount should clean expired timestamps`() = runTest {
        val limiter = SlidingWindowRateLimiter(maxRequests = 10, windowMs = 500, clock = { testScheduler.currentTime })
        limiter.acquire() // t=0
        limiter.acquire() // t=0
        assertEquals(2, limiter.currentCount())

        delay(600) // avanzar a t=600,dos t=0 Todo caducado(cutoff=100)
        assertEquals(0, limiter.currentCount())

        // Puede obtenerse nuevamente inmediatamente después de su vencimiento.
        limiter.acquire()
        assertEquals(1, limiter.currentCount())
    }

    @Test
    fun `acquire should serialize concurrent acquires without over-allocating`() = runTest {
        // 5 concurrencia acquire,Pero la ventana sólo permite 2 → espera innecesaria,Al final todo estuvo hecho pero tomó mucho tiempo. ≥ windowMs
        val limiter = SlidingWindowRateLimiter(maxRequests = 2, windowMs = 1_000, clock = { testScheduler.currentTime })
        val results = (1..5).map {
            async { limiter.acquire() }
        }
        results.awaitAll()
        // 5 solicitudes en lotes:2 un t=0,2 un t=1000,1 un t=2000 → tiempo virtual final ≥ 2000
        val now = testScheduler.currentTime
        assertTrue("expected virtual time >= 2000, got $now", now >= 2_000L)
        // todo hecho
        assertEquals(5, results.size)
    }
}
