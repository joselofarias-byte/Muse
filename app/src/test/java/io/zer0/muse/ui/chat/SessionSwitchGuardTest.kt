package io.zer0.muse.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.3.1: Prueba única de protección del número de secuencia de cambio de sesión(pura logica,Sin dependencias de Android).
 *
 * Los guardias son estados de instancia.,Cada instancia de interfaz contiene una copia; cada caso de uso crea una nueva instancia.,Ningún estado es compartido.
 */
class SessionSwitchGuardTest {
    private val guard = SessionSwitchGuard()

    @Test
    fun `latest token is never stale`() {
        val token = guard.begin()
        assertFalse(guard.isStale(token))
    }

    @Test
    fun `begin returns increasing tokens`() {
        val first = guard.begin()
        val second = guard.begin()
        assertTrue(second > first)
    }

    @Test
    fun `begin issues distinct tokens`() {
        val first = guard.begin()
        val second = guard.begin()
        assertTrue(first != second)
    }

    @Test
    fun `superseded token becomes stale`() {
        val old = guard.begin()
        guard.begin()
        assertTrue(guard.isStale(old))
    }

    /** invariante:Después de cambiar en rápida sucesión,Solo se puede enviar el último cambio iniciado,Todo lo anterior caducará. */
    @Test
    fun `rapid switching keeps only the last committable`() {
        val first = guard.begin()
        val second = guard.begin()
        val third = guard.begin()
        assertTrue(guard.isStale(first))
        assertTrue(guard.isStale(second))
        assertFalse(guard.isStale(third))
    }

    /**
     * invariante:Los números de serie de las dos instancias de interfaz no interfieren entre sí.
     *
     * Esta es exactamente la razón para la creación de instancias: un nuevo cambio iniciado en una instancia no debería permitir que caduque el cambio recién iniciado por otra instancia.
     */
    @Test
    fun `separate instances do not invalidate each other`() {
        val other = SessionSwitchGuard()
        val mine = guard.begin()
        other.begin()
        assertFalse("Cambiar a otra instancia no debería hacer que caduque el token de esta instancia.", guard.isStale(mine))
    }
}
