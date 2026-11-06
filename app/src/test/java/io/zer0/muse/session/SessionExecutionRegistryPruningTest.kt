package io.zer0.muse.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3: SessionExecutionRegistry activo/Separación de historia y pruebas de poda de estado final.
 *
 * Portada:
 *  - Una vez que el estado final se migra a la tabla de historial, la semántica de instantánea/estado permanece sin cambios.;
 *  - TTL cosecha caducada(inyectar reloj,certeza);
 *  - El límite de capacidad se elimina del antiguo al nuevo al terminar en;
 *  - Los registros activos nunca se recortan;
 *  - register/start/finish/fail/markCancelled Se mantiene la semántica existente.(Repetir estado final, llegada tardía tras cancelación.).
 *
 * correr: ./gradlew :app:testDebugUnitTest --tests "*SessionExecutionRegistryPruningTest*"
 */
class SessionExecutionRegistryPruningTest {

    private var now = 1_000L

    private fun identity(generationId: String) = GenerationIdentity(
        sessionId = "session-1",
        turnId = "turn-1",
        generationId = generationId,
        streamId = "stream-$generationId",
    )

    private fun registry(retentionMs: Long = 60_000L, maxHistory: Int = 256) = SessionExecutionRegistry(
        historyRetentionMs = retentionMs,
        maxHistoryRecords = maxHistory,
        nowMs = { now },
    )

    @Test
    fun `terminal records move to history while snapshot and state semantics stay intact`() {
        val registry = registry()
        val id = registry.register(identity("g1"), ExecutionKind.TOOL)
        assertTrue(registry.start(id))
        now = 2_000L

        assertTrue(registry.finish(id))

        // Tabla activa borrada,La tabla histórica conserva los registros estatales finales.(Compatible con diagnóstico)
        assertTrue(registry.activeSnapshot("session-1").isEmpty())
        assertEquals(1, registry.historySnapshot("session-1").size)
        assertEquals(1, registry.snapshot("session-1").size)
        assertEquals(ExecutionState.COMPLETED, registry.state(id))
        assertFalse(registry.isCurrent(identity("g1")))
        // El valor iniciado/terminado del registro de estado final proviene del reloj inyectado.
        val record = registry.historySnapshot().single()
        assertEquals(1_000L, record.startedAt)
        assertEquals(2_000L, record.finishedAt)
    }

    @Test
    fun `repeated terminal transitions on same id stay idempotent`() {
        val registry = registry()
        val id = registry.register(identity("g1"), ExecutionKind.TOOL)
        registry.start(id)
        assertTrue(registry.finish(id))
        // Terminar/fallar nuevamente en la historia del estado final para mantener la idempotencia,Estado sin cambios
        assertTrue(registry.finish(id))
        assertTrue(registry.fail(id))
        assertTrue(registry.markCancelled(id))
        assertEquals(ExecutionState.COMPLETED, registry.state(id))
        assertEquals(1, registry.historySnapshot().size)
    }

    @Test
    fun `late completion after cancel request remains cancelled in history`() {
        val registry = registry()
        val id = registry.register(identity("g1"), ExecutionKind.TOOL)
        registry.start(id)
        assertTrue(registry.requestCancel(id, "user_stop"))
        now = 5_000L

        assertTrue(registry.finish(id))

        assertEquals(ExecutionState.CANCELLED, registry.state(id))
        assertTrue(registry.activeSnapshot().isEmpty())
        assertEquals(1, registry.historySnapshot().size)
    }

    @Test
    fun `expired history is pruned by ttl`() {
        val registry = registry(retentionMs = 1_000L)
        val id = registry.register(identity("g1"), ExecutionKind.TOOL)
        registry.start(id)
        assertTrue(registry.finish(id))

        // No caducado:Sin recortar
        now = 1_500L
        assertEquals(0, registry.prune())
        assertEquals(ExecutionState.COMPLETED, registry.state(id))

        // TTL excedido: el estado/instantánea ya no es visible después del recorte
        now = 3_000L
        assertEquals(1, registry.prune())
        assertNull(registry.state(id))
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `history capacity evicts oldest finished records`() {
        val registry = registry(retentionMs = 1_000_000L, maxHistory = 2)
        val ids = mutableListOf<String>()
        listOf("g1", "g2", "g3").forEachIndexed { index, generation ->
            now = 1_000L + index * 1_000L
            val id = registry.register(identity(generation), ExecutionKind.LLM)
            registry.start(id)
            assertTrue(registry.finish(id))
            ids += id
        }

        // Recorta automáticamente cada transición de estado final:Capacidad 2,El g1 más antiguo ha sido retirado.
        assertEquals(2, registry.historySnapshot().size)
        assertNull(registry.state(ids[0]))
        assertEquals(ExecutionState.COMPLETED, registry.state(ids[1]))
        assertEquals(ExecutionState.COMPLETED, registry.state(ids[2]))
        assertEquals(0, registry.prune())
        assertEquals(2, registry.historySnapshot().size)
    }

    @Test
    fun `active records are never pruned even with zero retention`() {
        val registry = registry(retentionMs = 0L, maxHistory = 1)
        val id = registry.register(identity("g1"), ExecutionKind.BROWSER)
        registry.start(id)
        // Otro registro de estado final activa el recorte automático,Pero se deben conservar los registros activos
        val finished = registry.register(identity("g2"), ExecutionKind.TOOL)
        registry.finish(finished)

        assertEquals(0, registry.prune())
        assertEquals(ExecutionState.RUNNING, registry.state(id))
        assertEquals(1, registry.activeSnapshot().size)
    }

    @Test
    fun `terminal transition auto prunes expired history`() {
        val registry = registry(retentionMs = 100L)
        val first = registry.register(identity("g1"), ExecutionKind.TOOL)
        registry.start(first)
        assertTrue(registry.finish(first))

        now += 1_000L
        val second = registry.register(identity("g2"), ExecutionKind.LLM)
        registry.start(second)
        assertTrue(registry.finish(second))

        // El registro de la primera llegada se ha recortado automáticamente.,Sólo conserva el último
        assertNull(registry.state(first))
        assertEquals(ExecutionState.COMPLETED, registry.state(second))
        assertEquals(1, registry.historySnapshot().size)
    }

    @Test
    fun `session cancellation only scans active records`() {
        val registry = registry()
        val finished = registry.register(identity("g1"), ExecutionKind.TOOL)
        registry.start(finished)
        assertTrue(registry.finish(finished))

        val activeId = registry.register(identity("g1"), ExecutionKind.TOOL)
        registry.start(activeId)

        assertEquals(listOf(activeId), registry.requestCancelForSession("session-1"))
        assertEquals(ExecutionState.COMPLETED, registry.state(finished))
        assertEquals(ExecutionState.CANCEL_REQUESTED, registry.state(activeId))
        assertFalse(registry.requestCancel("does-not-exist"))
    }

    @Test
    fun `prune on unknown retention keeps fresh records`() {
        val registry = registry(retentionMs = 10_000L)
        val id = registry.register(identity("g1"), ExecutionKind.MEDIA)
        registry.start(id)
        assertTrue(registry.fail(id))

        now += 5_000L
        assertEquals(0, registry.prune())
        assertEquals(ExecutionState.FAILED, registry.state(id))
    }
}
