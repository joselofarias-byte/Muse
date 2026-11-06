package io.zer0.muse.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El tiempo de espera para la inspección de la tarea programada.
 *
 * La implementación anterior tiene un tiempo de inactividad fijo de 60 segundos: se activa una vez por minuto si no hay tareas caducadas y es necesario buscar GlobalContext cada vez. +
 * DataStore Leer + dos consultas de base de datos. Cámbielo a "Dormir según el último tiempo de vencimiento, pero**Solo se permiten menores de 60 años.**"Después de eso,
 * Clava la frontera aquí —— especialmente"No puedo dormir más de 60 años"Este artículo: Al crear una nueva tarea que se ejecutará en 5 segundos,
 * El sondeo fijo solo puede esperar 60 segundos como máximo. Dormir más tiempo es una regresión conductual.
 */
class ScheduledTaskPollDelayTest {

    private val now = 1_000_000L
    private val poll = 60_000L
    private val minDelay = 1_000L

    private fun delay(nextDueAt: Long?) = ScheduledTaskRunner.computePollDelay(
        now = now,
        nextDueAt = nextDueAt,
        pollInterval = poll,
        minDelay = minDelay,
    )

    @Test
    fun `no_task_keeps_the_minute_heartbeat`() {
        assertEquals(poll, delay(null))
    }

    @Test
    fun `already_due_task_waits_only_the_minimum`() {
        assertEquals(minDelay, delay(now - 5_000))
        assertEquals(minDelay, delay(now))
    }

    @Test
    fun `near_due_task_sleeps_exactly_until_then`() {
        assertEquals(10_000L, delay(now + 10_000))
        // El tiempo de vencimiento está a solo 1 ms: aún se debe respetar el límite inferior y no se permite el ralentí con 0 retraso.
        assertEquals(minDelay, delay(now + 1))
    }

    @Test
    fun `far_due_task_is_capped_at_the_poll_interval`() {
        assertEquals(poll, delay(now + 5 * 60_000))
        assertEquals(poll, delay(now + poll))
    }

    @Test
    fun `delay_never_exceeds_poll_interval_nor_drops_below_minimum`() {
        val probes = listOf(-100_000L, 0L, 1L, 500L, 1_000L, 59_999L, 60_000L, 3_600_000L)
        probes.forEach { offset ->
            val value = delay(now + offset)
            assertTrue("El retraso es necesario ≥ Límite inferior:offset=$offset value=$value", value >= minDelay)
            assertTrue("El retraso es necesario ≤ Límite de sondeo:offset=$offset value=$value", value <= poll)
        }
    }
}
