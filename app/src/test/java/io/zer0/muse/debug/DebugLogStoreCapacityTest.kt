package io.zer0.muse.debug

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Capacidad del buffer circular y orden de eliminación.
 *
 * Una vez que el límite superior se eleva a 2000, los errores más comunes son "eliminar un elemento más" o "eliminar desde el principio". Ambos errores aparecen de la siguiente manera en la máquina real:
 * "A los registros exportados les faltan los últimos minutos", por lo que ambos extremos aquí afirman que el número de registros llega al máximo y el más antiguo se descarta.
 */
class DebugLogStoreCapacityTest {

    @Before
    @After
    fun resetBuffer() {
        DebugLogStore.clear()
    }

    @Test
    fun `buffer keeps newest 2000 entries and evicts the oldest`() {
        repeat(2001) { index ->
            DebugLogStore.log(DebugLogStore.LEVEL_INFO, "CapacityTest", "entry-$index")
        }

        assertEquals(2000, DebugLogStore.size())

        val all = DebugLogStore.getAll()
        assertEquals(2000, all.size)
        assertEquals("entry-1", all.first().message)
        assertEquals("entry-2000", all.last().message)
        assertNull(all.firstOrNull { it.message == "entry-0" })
    }

    @Test
    fun `entries stay in insertion order after eviction starts`() {
        repeat(2000) { index ->
            DebugLogStore.log(DebugLogStore.LEVEL_INFO, "CapacityTest", "entry-$index")
        }
        // Cuando el cupo esté lleno, se escribirá otra entrada, se eliminará la entrada más antigua y el orden del resto se mantendrá sin cambios.
        DebugLogStore.log(DebugLogStore.LEVEL_INFO, "CapacityTest", "entry-2000")

        val messages = DebugLogStore.getAll().map { it.message }
        assertEquals(2000, messages.size)
        assertEquals(messages.sortedBy { it.removePrefix("entry-").toInt() }, messages)
    }
}
