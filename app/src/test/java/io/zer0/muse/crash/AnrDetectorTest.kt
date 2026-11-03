package io.zer0.muse.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AnrDetector] Prueba única de lógica de decisión.
 *
 * Cobertura clave 2026-09-27~29 cinco porciones anr_*.txt causas de los falsos positivos:
 * El proceso sale del fondo y el sistema lo congela. → Regrese a la recepción para descongelar,La primera ronda de inspección trata toda la duración del backend como"El hilo principal no responde".
 */
class AnrDetectorTest {
    private fun detector() = AnrDetector(
        timeoutMs = 5_000L,
        expectedIntervalMs = 2_000L,
        suspendToleranceMs = 3_000L,
    )

    /** Simular una ronda"Despierta a tiempo, el hilo principal también se ejecuta. ping"Detección normal. */
    private fun AnrDetector.tick(nowMs: Long, pongCount: Long) = onCheck(nowMs = nowMs, postedAtMs = nowMs - 2_000L, pongCount = pongCount)

    @Test
    fun `No se emite ningún juicio cuando el latido del corazón es normal. ANR`() {
        val d = detector()
        var pong = 0L
        var t = 10_000L
        repeat(10) {
            t += 2_000L
            pong += 1L
            assertEquals(AnrDetector.Decision.Healthy, d.tick(t, pong))
        }
    }

    @Test
    fun `El hilo principal continuamente no responde y excede el umbral para determinar ANR y solo informa una vez.`() {
        val d = detector()
        var t = 10_000L
        // Las dos primeras rondas de latidos del corazón fueron normales.
        assertEquals(AnrDetector.Decision.Healthy, d.tick(t, 1L))
        t += 2_000L
        assertEquals(AnrDetector.Decision.Healthy, d.tick(t, 2L))

        // Hilo principal atascado:ping El conteo ya no avanza
        t += 2_000L
        assertEquals(AnrDetector.Decision.Healthy, d.tick(t, 2L)) // Bloqueando aprox. 2s,umbral no alcanzado
        t += 2_000L
        assertEquals(AnrDetector.Decision.Healthy, d.tick(t, 2L)) // aprox. 4s
        t += 2_000L
        val blocked = d.tick(t, 2L) // aprox. 6s → juicio
        assertTrue("debe ser juzgado ANR,real $blocked", blocked is AnrDetector.Decision.Blocked)
        assertTrue("La duración del bloqueo debe ser >= 5s,real ${(blocked as AnrDetector.Decision.Blocked).silenceMs}", blocked.silenceMs >= 5_000L)

        // Todavía estancado:Sin informes duplicados
        t += 2_000L
        assertEquals(AnrDetector.Decision.Suppressed, d.tick(t, 2L))

        // restaurar el latido del corazón:rearmarse
        t += 2_000L
        assertEquals(AnrDetector.Decision.Healthy, d.tick(t, 3L))
    }

    @Test
    fun `Una vez congelado el proceso, regrese a la recepción y no se permitirán falsas alarmas. ANR`() {
        val d = detector()
        // antes de congelar:latidos normales del corazón
        assertEquals(AnrDetector.Decision.Healthy, d.tick(10_000L, 7L))

        // El proceso se congela durante 93 segundos.:El propio perro guardián durmió 93 segundos esta ronda.
        // (escena real:App El backend está suspendido por el congelador de aplicaciones en caché,Descongelar después de regresar a la recepción)
        val decision = d.onCheck(nowMs = 103_000L, postedAtMs = 10_000L, pongCount = 7L)
        assertEquals(AnrDetector.Decision.Suspended, decision)

        // Después de la descongelación, el hilo principal ejecuta inmediatamente el trabajo pendiente. ping → normales
        assertEquals(AnrDetector.Decision.Healthy, d.tick(105_000L, 9L))
    }

    @Test
    fun `Dentro de la tolerancia de congelación, la acumulación se seguirá contando como bloqueada.`() {
        val d = detector()
        var t = 10_000L
        d.tick(t, 1L)
        // Solo gasta más en cada ronda 1s(Tolerancia de 3 s no excedida)→ pertenecer a"El perro guardián es normal pero el hilo principal no responde."
        t += 2_000L
        d.onCheck(nowMs = t + 1_000L, postedAtMs = t, pongCount = 1L)
        t += 3_000L
        d.onCheck(nowMs = t, postedAtMs = t - 2_000L, pongCount = 1L)
        t += 2_000L
        val decision = d.onCheck(nowMs = t, postedAtMs = t - 2_000L, pongCount = 1L)
        assertTrue("debe ser juzgado ANR,real $decision", decision is AnrDetector.Decision.Blocked)
    }

    @Test
    fun `Los perros guardianes suspendidos durante la congelación no mostrarán el estado de bloqueo vencido`() {
        val d = detector()
        // Ingrese al bloqueo primero
        var t = 10_000L
        d.tick(t, 1L)
        repeat(3) {
            t += 2_000L
            d.onCheck(nowMs = t, postedAtMs = t - 2_000L, pongCount = 1L)
        }
        // El proceso se congela durante el bloqueo.(Por ejemplo, el usuario se desconecta en este momento. App)
        assertEquals(AnrDetector.Decision.Suspended, d.onCheck(nowMs = t + 60_000L, postedAtMs = t, pongCount = 1L))
        // Descongelar y reanudar el hilo principal:no por"El período antes de la congelación."Informar nuevamente
        assertEquals(AnrDetector.Decision.Healthy, d.tick(t + 62_000L, 2L))
    }
}
