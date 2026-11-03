package io.zer0.muse.tools

import io.zer0.muse.workspace.WorkspaceIoException
import io.zer0.muse.workspace.WorkspacePathLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * El comportamiento del bloqueo de ruta en sí: serialización por ruta, paralelización entre rutas, tiempo de espera de bloqueo, excepción y cancelación no abandonarán el bloqueo.
 *
 * El entorno de Android no se toca aquí, el bloqueo es una implementación de rutina pura; Vea el cableado a nivel del espacio de trabajo.
 * [WorkspaceEditConcurrencyTest].
 */
class WorkspacePathLockTest {

    /** Registre el número máximo de secciones críticas simultáneas. */
    private class ConcurrencyProbe {
        private val active = AtomicInteger(0)

        /** pico histórico. */
        private val peak = AtomicInteger(0)

        val maxActive: Int get() = peak.get()

        suspend fun <T> record(block: suspend () -> T): T {
            val now = active.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                return block()
            } finally {
                active.decrementAndGet()
            }
        }
    }

    @Test
    fun `Se asignan diferentes métodos de escritura del mismo archivo al mismo bloqueo`() {
        val lock = WorkspacePathLock()
        val base = lock.keyOf("main", "a.txt")

        assertEquals(base, lock.keyOf("main", "./a.txt"))
        assertEquals(base, lock.keyOf("main", "/a.txt"))
        assertEquals(base, lock.keyOf("main", "a.txt/"))
        // Este es el mismo archivo en un sistema de archivos que no distingue entre mayúsculas y minúsculas.
        assertEquals(base, lock.keyOf("main", "A.TXT"))
        // Diferentes archivos y diferentes montajes son independientes entre sí.
        assertNotEquals(base, lock.keyOf("main", "b.txt"))
        assertNotEquals(base, lock.keyOf("album", "a.txt"))
    }

    @Test
    fun `Las entradas simultáneas en la misma ruta se serializan`() = runBlocking {
        val lock = WorkspacePathLock()
        val probe = ConcurrencyProbe()
        val done = AtomicInteger(0)

        coroutineScope {
            (1..8).map {
                async(Dispatchers.Default) {
                    lock.withLock("main", "same.txt", "same.txt") {
                        probe.record {
                            delay(10)
                            done.incrementAndGet()
                        }
                    }
                }
            }.awaitAll()
        }

        assertEquals("Un mismo archivo no debe tener dos titulares", 1, probe.maxActive)
        assertEquals("8 Todas las llamadas deben completarse.", 8, done.get())
    }

    @Test
    fun `Los diferentes caminos no se bloquean entre sí`() = runBlocking {
        val lock = WorkspacePathLock()
        val probe = ConcurrencyProbe()

        coroutineScope {
            (1..6).map { i ->
                async(Dispatchers.Default) {
                    lock.withLock("main", "f$i.txt", "f$i.txt") {
                        probe.record { delay(50) }
                    }
                }
            }.awaitAll()
        }

        assertTrue("Diferentes archivos deberían poder ejecutarse en paralelo,pico real=${probe.maxActive}", probe.maxActive > 1)
    }

    @Test
    fun `Espere a que expire el tiempo de bloqueo, indique un error claro y regrese inmediatamente`() = runBlocking {
        val lock = WorkspacePathLock(acquireTimeoutMs = 100)
        val holding = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            lock.withLock("main", "busy.txt", "busy.txt") {
                holding.complete(Unit)
                delay(2_000)
            }
        }
        holding.await()

        val startedAt = System.currentTimeMillis()
        val failure = runCatching { lock.withLock("main", "busy.txt", "busy.txt") { } }.exceptionOrNull()
        val elapsed = System.currentTimeMillis() - startedAt

        assertTrue("Debería ser un error de IO en el espacio de trabajo,real=$failure", failure is WorkspaceIoException)
        assertTrue(
            "El texto de error debe indicar que está esperando a que expire el tiempo de bloqueo.,real=${failure?.message}",
            failure?.message?.contains("Esperando que se agote el tiempo de bloqueo del archivo") == true,
        )
        assertTrue("No debes esperar hasta que se acabe el candado antes de regresar.,Realmente esperé ${elapsed}ms", elapsed < 1_500)
        holder.join()
    }

    @Test
    fun `El bloqueo aún se libera después de que la sección crítica genera una excepción`() = runBlocking {
        val lock = WorkspacePathLock(acquireTimeoutMs = 500)

        val boom = runCatching {
            lock.withLock("main", "a.txt", "a.txt") { throw IllegalStateException("boom") }
        }
        assertTrue("Las excepciones deben lanzarse tal cual,real=$boom", boom.exceptionOrNull() is IllegalStateException)

        var ran = false
        lock.withLock("main", "a.txt", "a.txt") { ran = true }
        assertTrue("La ruta de excepción no libera el bloqueo:Las llamadas posteriores no pueden entrar", ran)
    }

    @Test
    fun `El candado sigue disponible después de cancelar el servicio del camarero`() = runBlocking {
        val lock = WorkspacePathLock(acquireTimeoutMs = 200)
        val holding = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            lock.withLock("main", "c.txt", "c.txt") {
                holding.complete(Unit)
                delay(300)
            }
        }
        holding.await()

        val waiter = launch(Dispatchers.Default) {
            lock.withLock("main", "c.txt", "c.txt") { }
        }
        delay(50)
        waiter.cancel()
        holder.join()

        var ran = false
        lock.withLock("main", "c.txt", "c.txt") { ran = true }
        assertTrue("El camarero cancelado le quitó el candado.", ran)
    }
}
