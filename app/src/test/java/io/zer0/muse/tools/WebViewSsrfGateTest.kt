package io.zer0.muse.tools

import io.zer0.muse.ui.SsrfGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * P2-33: SSRF La resolución de nombres de dominio en frío ya no bloquea el hilo principal(WebView ruta de devolución de llamada).
 *
 * Pase [SsrfGuard.setResolverForTest] Inyectar sustituto de "analizador lento",Verificación determinista:
 *  - El análisis se produce en el hilo de trabajo de Dispatchers.IO,Nunca en el hilo de llamadas.(hilo principal)ejecutar en DNS;
 *  - El primer resultado analizado se escribe en la memoria caché.,Seguimiento [WebViewSsrfGate.isBlockedNow] / [WebViewSsrfGate.isBlockedBlocking]
 *    Las llamadas ya no se activan DNS;
 *  - Cuando falla el caché, la ruta del hilo principal cerrada por error se intercepta inmediatamente.(En lugar de esperar sincrónicamente DNS),La liberación se puede volver a intentar una vez completado el precalentamiento.
 */
class WebViewSsrfGateTest {

    /** Suplente de analizador lento:Registrar el número de llamadas y analizar hilos.,y amplificar la latencia para simular DNS de dominio frío. */
    private class SlowResolver(
        private val delayMs: Long,
        private val addresses: Array<InetAddress>,
    ) : SsrfGuard.HostResolver {
        val calls = AtomicInteger(0)
        val resolveThreads = CopyOnWriteArrayList<String>()

        override fun resolve(host: String): Array<InetAddress> {
            calls.incrementAndGet()
            resolveThreads.add(Thread.currentThread().name)
            val deadline = System.currentTimeMillis() + delayMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    // El análisis doble no responde a las interrupciones.:Simula DNS real y no se puede cancelar.
                }
            }
            return addresses
        }
    }

    @After
    fun tearDown() {
        SsrfGuard.resetForTest()
    }

    private fun gate(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) = WebViewSsrfGate(
        prewarmScope = scope,
        callbackDispatcher = Dispatchers.Unconfined,
        cachedVerdict = SsrfGuard::cachedVerdictOrNull,
        resolveAsync = SsrfGuard::isBlockedAsync,
        resolveBlocking = SsrfGuard::isBlocked,
    )

    /** La IP literal no se activa como verdadera DNS,Asegúrese de que una sola prueba se pueda repetir sin conexión. */
    private fun publicAddresses(): Array<InetAddress> = arrayOf(InetAddress.getByName("8.8.8.8"))

    private fun privateAddresses(): Array<InetAddress> = arrayOf(InetAddress.getByName("127.0.0.1"))

    @Test
    fun `cold host resolution runs on io thread not on the calling thread`() = runBlocking {
        val resolver = SlowResolver(delayMs = 300, addresses = publicAddresses())
        SsrfGuard.setResolverForTest(resolver)
        val gate = gate()

        val callerThread = Thread.currentThread().name
        val blocked = gate.isBlockedAsync("http://cold-ssrf-a.example/")

        assertFalse("Las direcciones de redes públicas deben publicarse", blocked)
        assertEquals(1, resolver.calls.get())
        assertNotEquals("El análisis nunca debe ocurrir en el hilo de llamada(hilo principal)en", callerThread, resolver.resolveThreads.single())
        assertTrue(
            "El análisis debería recaer en el subproceso de trabajo de Dispatchers.IO,real=${resolver.resolveThreads.single()}",
            resolver.resolveThreads.single().startsWith("DefaultDispatcher-worker"),
        )
    }

    @Test
    fun `first resolution is written into cache so later lookups do no dns`() = runBlocking {
        val resolver = SlowResolver(delayMs = 0, addresses = publicAddresses())
        SsrfGuard.setResolverForTest(resolver)
        val gate = gate()
        val url = "http://cold-ssrf-b.example/"

        assertFalse(gate.isBlockedAsync(url))
        // El primer análisis ha sido almacenado en caché. → El caché de solo lectura del hilo principal se puede utilizar para determinar
        assertEquals(false, SsrfGuard.cachedVerdictOrNull(url))
        val startedAt = System.nanoTime()
        assertFalse(gate.isBlockedNow(url))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("Los aciertos de caché no deberían bloquear el hilo de llamada(Medición real ${elapsedMs}ms)", elapsedMs < 100)
        assertFalse(gate.isBlockedBlocking(url))
        assertEquals("El mismo host sólo se resuelve una vez(El caché también sobrescribe el primer análisis.)", 1, resolver.calls.get())
    }

    @Test
    fun `cold host on main thread fails closed without waiting and heals after prewarm`() = runBlocking {
        val resolver = SlowResolver(delayMs = 250, addresses = publicAddresses())
        SsrfGuard.setResolverForTest(resolver)
        val gate = gate()
        val url = "http://cold-ssrf-c.example/"
        val retried = AtomicBoolean(false)

        val startedAt = System.nanoTime()
        val blocked = gate.isBlockedNow(url) { retried.set(true) }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue("El anfitrión frío debe fail-closed", blocked)
        assertTrue("La ruta del hilo principal no debe esperar DNS(Medición real ${elapsedMs}ms)", elapsedMs < 100)
        assertFalse("No lo vuelva a intentar antes de que se complete el precalentamiento.", retried.get())

        withTimeout(5_000) {
            while (!retried.get()) delay(10)
        }
        assertEquals(1, resolver.calls.get())
        // Precalentamiento completado → Los juicios posteriores del mismo host impactan directamente en el caché.,No más intercepciones
        assertFalse(gate.isBlockedNow(url))
    }

    @Test
    fun `cold private host stays blocked and resolves at most once`() = runBlocking {
        val resolver = SlowResolver(delayMs = 0, addresses = privateAddresses())
        SsrfGuard.setResolverForTest(resolver)
        val gate = gate()
        val url = "http://cold-ssrf-d.example/"

        assertTrue("Las direcciones de intranet deben estar bloqueadas", gate.isBlockedAsync(url))
        assertEquals(true, SsrfGuard.cachedVerdictOrNull(url))
        assertTrue(gate.isBlockedNow(url))
        assertTrue(gate.isBlockedBlocking(url))
        assertEquals(1, resolver.calls.get())
    }

    @Test
    fun `off main thread guard resolves on demand and warms cache for main thread callbacks`() = runBlocking {
        val resolver = SlowResolver(delayMs = 0, addresses = publicAddresses())
        SsrfGuard.setResolverForTest(resolver)
        val gate = gate()
        val url = "http://cold-ssrf-e.example/"

        // shouldInterceptRequest Devolución de llamada en el hilo de fondo de WebView → Permite el análisis y la escritura sincrónicos en la caché.
        assertFalse(gate.isBlockedBlocking(url))
        assertEquals(1, resolver.calls.get())
        // Devolución de llamada del hilo principal(Redirigir/onPageStarted)Luego presione directamente el caché
        assertFalse(gate.isBlockedNow(url))
        assertEquals(1, resolver.calls.get())
    }
}
