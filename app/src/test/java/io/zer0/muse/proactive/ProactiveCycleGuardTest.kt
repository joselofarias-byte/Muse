package io.zer0.muse.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueba única de puerta volante única de mensaje activo.
 *
 * Aquí sólo probamos "si puede haber dos rondas de inspecciones al mismo tiempo" y "tratamos de registrar cuándo quedarse y cuándo retirarse".
 * La determinación del tiempo, la generación y el almacenamiento no se encuentran entre las responsabilidades de esta clase. Mezclarlo hará que sea difícil localizar la causa de la falla.
 *
 * El modo de falla de este conjunto de restricciones es "el usuario recibe dos mensajes casi idénticos".
 * No se generarán excepciones ni se registrarán registros de errores, por lo que la prueba debe monitorearlo.
 */
class ProactiveCycleGuardTest {

    @Test
    fun `Sólo se permite una entrada al mismo tiempo`() {
        val guard = ProactiveCycleGuard()
        val first = guard.tryEnter(now = 1_000L)

        assertNotNull("Debes obtener credenciales al ingresar por primera vez.", first)
        assertNull("La segunda inscripción mientras la ronda anterior aún esté en curso deberá ser rechazada.", guard.tryEnter(now = 1_100L))
        assertTrue(guard.isInFlight)
    }

    @Test
    fun `Después de salir, puedes ingresar a la siguiente ronda`() {
        val guard = ProactiveCycleGuard()
        val entry = guard.tryEnter(now = 1_000L)!!
        guard.leave(entry, sent = false)

        assertFalse(guard.isInFlight)
        assertNotNull("Debería ser liberado después de la ronda anterior.", guard.tryEnter(now = 2_000L))
    }

    @Test
    fun `Las credenciales resaltan el tiempo de intento de la última ronda`() {
        val guard = ProactiveCycleGuard()
        val first = guard.tryEnter(now = 1_000L)!!
        guard.markAttempt(1_500L)
        guard.leave(first, sent = true)

        val second = guard.tryEnter(now = 2_000L)
        assertEquals("Tu propio tiempo de entrada en esta ronda.", 2_000L, second?.enteredAt)
        assertEquals("La pregunta a determinar es ¿cuándo fue la última ronda de pruebas?", 1_500L, second?.previousAttemptAt)
    }

    @Test
    fun `El tiempo del último intento en la primera ronda fue cero`() {
        val guard = ProactiveCycleGuard()
        assertEquals(0L, guard.tryEnter(now = 1_000L)?.previousAttemptAt)
    }

    @Test
    fun `La ronda en la que se envió el mensaje deja huellas de intentos`() {
        // "Seguir corriendo esta ronda" y "corriendo esta ronda" son dos cosas diferentes.
        // Si el tiempo de prueba se borra después del envío, la ronda recién enviada se tratará como si nunca hubiera sucedido en la capa de juicio.
        // La siguiente ronda pasará inmediatamente por la puerta de cronometraje. —— Esto es exactamente lo que provoca el envío duplicado.
        val guard = ProactiveCycleGuard()
        val entry = guard.tryEnter(now = 1_000L)!!
        guard.markAttempt(1_200L)
        guard.leave(entry, sent = true)

        assertEquals(1_200L, guard.tryEnter(now = 5_000L)?.previousAttemptAt)
    }

    @Test
    fun `Rastros de la ronda no enviada de intentos de reversión`() {
        // El modelo dice que no hay nada que conectar, la generación falla y la base de datos no se elimina. —— Ninguno de estos produjo mensajes,
        // Mantener registros de los intentos sólo servirá para retrasar el próximo reexamen.
        val guard = ProactiveCycleGuard()
        val first = guard.tryEnter(now = 1_000L)!!
        guard.markAttempt(1_100L)
        guard.leave(first, sent = true)

        val second = guard.tryEnter(now = 2_000L)!!
        guard.markAttempt(2_100L)
        guard.leave(second, sent = false)

        assertEquals(
            "Retroceder a la última ronda de transmisión exitosa en lugar de borrarla 0",
            1_100L,
            guard.tryEnter(now = 9_000L)?.previousAttemptAt,
        )
    }

    @Test
    fun `Los disparadores rechazados no avanzan el tiempo de intento`() {
        val guard = ProactiveCycleGuard()
        val entry = guard.tryEnter(now = 1_000L)!!
        guard.markAttempt(1_200L)

        assertNull(guard.tryEnter(now = 5_000L))
        guard.leave(entry, sent = true)

        // El tiempo rechazado en realidad no transcurrió y no puede contarse como la "última ronda".
        assertEquals(1_200L, guard.tryEnter(now = 9_000L)?.previousAttemptAt)
    }

    @Test
    fun `Las rondas no registradas no falsificarán los tiempos de intento`() {
        // Las rondas que estén bloqueadas durante No molestar/Crédito insuficiente no se generarán y no habrá ningún intento de marca.
        val guard = ProactiveCycleGuard()
        val entry = guard.tryEnter(now = 1_000L)!!
        guard.leave(entry, sent = false)

        assertEquals(0L, guard.tryEnter(now = 2_000L)?.previousAttemptAt)
    }
}
