package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.2: La herramienta realiza pruebas de estrategia presupuestaria.
 *
 * Aceptación de cobertura:Las tormentas de herramientas se pueden detener sin gastar mucho dinero;Se reconocen llamadas repetidas;
 * La salida excede el límite de truncamiento;El presupuesto de tiempo se puede configurar para iniciar y detener.
 */
class ToolExecutionPolicyTest {

    @Test
    fun `normal alternating calls are always allowed`() {
        val policy = ToolExecutionPolicy()
        repeat(10) { i ->
            val decision = policy.beforeExecute("tool_$i", "{\"i\":$i}")
            assertTrue("No. $i las llamadas deben ser liberadas", decision.allowed)
            policy.afterExecute("tool_$i", "{\"i\":$i}", success = true)
        }
        assertEquals(10, policy.executedCalls)
    }

    @Test
    fun `total call budget stops the storm`() {
        val policy = ToolExecutionPolicy(ToolExecutionLimits(maxTotalCalls = 5))
        repeat(5) { i ->
            assertTrue(policy.beforeExecute("t", "{\"n\":$i}").allowed)
            policy.afterExecute("t", "{\"n\":$i}", success = true)
        }
        val blocked = policy.beforeExecute("t", "{\"n\":6}")
        assertFalse(blocked.allowed)
        assertEquals(ToolExecutionPolicy.StopReason.MAX_TOTAL_CALLS, blocked.reason)
    }

    @Test
    fun `repeated identical calls are fingerprinted and blocked`() {
        val policy = ToolExecutionPolicy(ToolExecutionLimits(maxConsecutiveIdenticalCalls = 3))
        val args = "{\"path\":\"/data/log.txt\"}"
        repeat(3) {
            assertTrue(policy.beforeExecute("read_file", args).allowed)
            policy.afterExecute("read_file", args, success = true)
        }
        val blocked = policy.beforeExecute("read_file", args)
        assertFalse("La cuarta llamada idéntica consecutiva debe ser interceptada.", blocked.allowed)
        assertEquals(ToolExecutionPolicy.StopReason.REPEATED_IDENTICAL_CALL, blocked.reason)
        // Los detalles de la huella digital no devuelven el texto original completo del parámetro
        assertFalse(blocked.detail.contains("/data/log.txt"))
    }

    @Test
    fun `different call resets consecutive repeat counter`() {
        val policy = ToolExecutionPolicy(ToolExecutionLimits(maxConsecutiveIdenticalCalls = 2))
        val args = "{\"p\":1}"
        repeat(2) {
            assertTrue(policy.beforeExecute("read_file", args).allowed)
            policy.afterExecute("read_file", args, success = true)
        }
        // Haz una llamada diferente
        assertTrue(policy.beforeExecute("list_dir", "{}").allowed)
        policy.afterExecute("list_dir", "{}", success = true)
        // Las llamadas consecutivas idénticas vuelven a contar,Liberado si no se alcanza el límite superior
        assertTrue(policy.beforeExecute("read_file", args).allowed)
    }

    @Test
    fun `consecutive failures stop execution until a success resets`() {
        val policy = ToolExecutionPolicy(ToolExecutionLimits(maxConsecutiveFailures = 3))
        repeat(3) {
            assertTrue(policy.beforeExecute("t", "{\"n\":$it}").allowed)
            policy.afterExecute("t", "{\"n\":$it}", success = false)
        }
        val blocked = policy.beforeExecute("t", "{\"n\":4}")
        assertFalse(blocked.allowed)
        assertEquals(ToolExecutionPolicy.StopReason.CONSECUTIVE_FAILURES, blocked.reason)

        // En la nueva instancia de estrategia:Falló dos veces y tuvo éxito una vez.,Borrar combinación
        val other = ToolExecutionPolicy(ToolExecutionLimits(maxConsecutiveFailures = 3))
        repeat(2) { i ->
            other.beforeExecute("t", "{\"n\":$i}")
            other.afterExecute("t", "{\"n\":$i}", success = false)
        }
        other.beforeExecute("t", "{\"n\":ok}")
        other.afterExecute("t", "{\"n\":ok}", success = true)
        assertTrue(other.beforeExecute("t", "{\"n\":next}").allowed)
    }

    @Test
    fun `time budget only active when configured`() {
        // Desactivado de forma predeterminada
        val defaultPolicy = ToolExecutionPolicy()
        assertFalse(defaultPolicy.isTimeBudgetExhausted(nowMs = defaultPolicy.startedAtMs + Long.MAX_VALUE / 2))

        val bounded = ToolExecutionPolicy(ToolExecutionLimits(totalBudgetMs = 1_000))
        assertFalse(bounded.isTimeBudgetExhausted(nowMs = bounded.startedAtMs + 500))
        assertTrue(bounded.isTimeBudgetExhausted(nowMs = bounded.startedAtMs + 2_500))
    }

    @Test
    fun `oversized output is truncated with notice`() {
        val policy = ToolExecutionPolicy(ToolExecutionLimits(maxOutputChars = 100))
        val big = "x".repeat(250)
        val (clamped, truncated) = policy.clampOutput(big)
        assertTrue(truncated)
        assertTrue(clamped.startsWith("x".repeat(100)))
        assertTrue(clamped.contains("La salida está truncada"))

        // Devolver como está sin exceder el límite
        val (same, wasTruncated) = policy.clampOutput("small")
        assertFalse(wasTruncated)
        assertEquals("small", same)
    }

    @Test
    fun `default output policy does not truncate a large result`() {
        val output = "tool response ".repeat(20_000)

        val (forwarded, wasTruncated) = ToolExecutionPolicy().clampOutput(output)

        assertFalse(wasTruncated)
        assertEquals(output, forwarded)
    }

    @Test
    fun `default round budget does not stop after one million output characters`() {
        val policy = ToolExecutionPolicy()
        policy.recordOutputChars(1_000_001)

        assertTrue(policy.checkRoundBudget().allowed)
    }

    @Test
    fun `fingerprint is stable for identical calls and differs otherwise`() {
        val policy = ToolExecutionPolicy()
        val a1 = policy.fingerprint("read_file", "{\"p\":1}")
        val a2 = policy.fingerprint("read_file", "{\"p\":1}")
        val b = policy.fingerprint("read_file", "{\"p\":2}")
        val c = policy.fingerprint("write_file", "{\"p\":1}")
        assertEquals(a1, a2)
        assertFalse(a1 == b)
        assertFalse(a1 == c)
    }

    @Test
    fun `consecutive failure threshold is exposed for loop abort`() {
        val policy = ToolExecutionPolicy(ToolExecutionLimits(maxConsecutiveFailures = 3))
        assertFalse(policy.shouldAbortOnConsecutiveFailures())
        assertEquals(0, policy.consecutiveFailuresCount)
        repeat(3) { i ->
            policy.beforeExecute("t", "{\"n\":$i}")
            policy.afterExecute("t", "{\"n\":$i}", success = false)
        }
        assertTrue(policy.shouldAbortOnConsecutiveFailures())
        assertEquals(3, policy.consecutiveFailuresCount)
    }

    @Test
    fun `round progress blocks only after repeated signature hits limit`() {
        val policy = ToolExecutionPolicy(ToolExecutionLimits(maxNoProgressRounds = 2))
        assertTrue(policy.checkRoundProgress("sig-A").allowed)
        assertTrue(policy.checkRoundProgress("sig-A").allowed)
        val blocked = policy.checkRoundProgress("sig-A")
        assertFalse(blocked.allowed)
        assertEquals(ToolExecutionPolicy.StopReason.REPEATED_IDENTICAL_CALL, blocked.reason)
        assertEquals(2, policy.noProgressRoundsCount)
    }

    @Test
    fun `round progress resets on changed signature`() {
        val policy = ToolExecutionPolicy(ToolExecutionLimits(maxNoProgressRounds = 2))
        assertTrue(policy.checkRoundProgress("sig-A").allowed)
        assertTrue(policy.checkRoundProgress("sig-B").allowed)
        assertTrue(policy.checkRoundProgress("sig-B").allowed)
        assertTrue(policy.checkRoundProgress("sig-A").allowed)
        assertTrue(policy.checkRoundProgress("sig-A").allowed)
    }

    @Test
    fun `empty round signature never triggers stuck detection`() {
        val policy = ToolExecutionPolicy(ToolExecutionLimits(maxNoProgressRounds = 1))
        repeat(5) { assertTrue(policy.checkRoundProgress("").allowed) }
    }

    @Test
    fun `max rounds is owned and can expand dynamically`() {
        val policy = ToolExecutionPolicy(initialMaxRounds = 10)
        assertEquals(10, policy.maxRounds)
        policy.updateMaxRounds(17)
        assertEquals(17, policy.maxRounds)
    }

    @Test
    fun `skipped call is not recorded as failure`() {
        // Aprobación rechazada/ruta de intersección presupuestaria:beforeExecute Si realmente no se ejecuta después del lanzamiento,no llamado afterExecute,El recuento se mantiene sin cambios.
        val policy = ToolExecutionPolicy(ToolExecutionLimits(maxConsecutiveFailures = 3))
        policy.afterExecute("t", "{\"a\":1}", success = true)
        assertEquals(1, policy.executedCalls)
        assertEquals(0, policy.consecutiveFailuresCount)
        assertTrue(policy.beforeExecute("t", "{\"a\":2}").allowed)
        // no llamado afterExecute(Simulación no ejecutada)
        assertEquals(1, policy.executedCalls)
        assertEquals(0, policy.consecutiveFailuresCount)
    }

    @Test
    fun `turn stats accumulate emitted calls and streamed chars`() {
        val policy = ToolExecutionPolicy()
        assertEquals(0, policy.emittedToolCallCount)
        assertEquals(0, policy.streamedCharCount)
        policy.recordEmittedToolCalls(3)
        policy.recordStreamedChars(120)
        policy.recordEmittedToolCalls(2)
        policy.recordStreamedChars(40)
        assertEquals(5, policy.emittedToolCallCount)
        assertEquals(160, policy.streamedCharCount)
        // Las estadísticas y los recuentos de ejecución presupuestaria son independientes entre sí.
        assertEquals(0, policy.executedCalls)
    }

    @Test
    fun `round budget reports remaining quota across dimensions`() {
        // Corriendo en vacío:todo esta permitido
        assertTrue(ToolExecutionPolicy().checkRoundBudget().allowed)

        // Total de llamadas agotadas → Intercepción al comienzo de la ronda.
        val calls = ToolExecutionPolicy(ToolExecutionLimits(maxTotalCalls = 2))
        repeat(2) { i ->
            calls.beforeExecute("t", "{\"n\":$i}")
            calls.afterExecute("t", "{\"n\":$i}", success = true)
        }
        val callBlocked = calls.checkRoundBudget()
        assertFalse(callBlocked.allowed)
        assertEquals(ToolExecutionPolicy.StopReason.MAX_TOTAL_CALLS, callBlocked.reason)

        // El número acumulado de caracteres de salida excede el límite. → Intercepción al comienzo de la ronda.
        val chars = ToolExecutionPolicy(ToolExecutionLimits(maxTotalOutputChars = 100))
        chars.recordOutputChars(60)
        chars.recordOutputChars(50)
        assertEquals(110L, chars.totalOutputChars)
        val charBlocked = chars.checkRoundBudget()
        assertFalse(charBlocked.allowed)
        assertEquals(ToolExecutionPolicy.StopReason.MAX_TOTAL_OUTPUT_CHARS, charBlocked.reason)

        // presupuesto de tiempo(inyección de prueba nowMs)→ Intercepción al comienzo de la ronda.
        val timed = ToolExecutionPolicy(ToolExecutionLimits(totalBudgetMs = 1_000))
        assertTrue(timed.checkRoundBudget(nowMs = timed.startedAtMs + 500).allowed)
        assertFalse(timed.checkRoundBudget(nowMs = timed.startedAtMs + 2_500).allowed)
    }

    @Test
    fun `record output chars ignores non-positive and accumulates`() {
        val policy = ToolExecutionPolicy()
        policy.recordOutputChars(0)
        policy.recordOutputChars(-5)
        assertEquals(0L, policy.totalOutputChars)
        policy.recordOutputChars(10)
        policy.recordOutputChars(15)
        assertEquals(25L, policy.totalOutputChars)
    }
}
