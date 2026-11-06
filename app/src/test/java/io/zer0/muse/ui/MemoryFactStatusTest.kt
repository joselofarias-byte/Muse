package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reglas de juicio para insignias de estado de memoria.
 *
 * Los tres elementos "fusionados/aparecidos/reemplazados" en la lista son independientes entre sí y se puede acceder a una memoria al mismo tiempo;
 * Sólo hay dos costos por juzgar una escritura incorrecta: etiquetado incorrecto (los usuarios piensan que todavía está en uso) y etiquetado incorrecto (los usuarios piensan que ya no se usa).
 * Por lo tanto, las tres fuentes y la trampa de compartir el mismo número entre sucursales al mismo tiempo quedan fijadas aquí.
 */
class MemoryFactStatusTest {

    private val noneSuperseded = emptySet<String>()

    private fun statusOf(
        scope: String? = "main",
        id: Long = 7L,
        mergedIntoId: Long? = null,
        suppressedAt: String? = null,
        supersededKeys: Set<String> = noneSuperseded,
    ): MemoryFactStatus = memoryFactStatusOf(
        scope = scope,
        id = id,
        mergedIntoId = mergedIntoId,
        suppressedAt = suppressedAt,
        supersededKeys = supersededKeys,
    )

    @Test
    fun healthyRowIsNotInactive() {
        val status = statusOf()

        assertFalse(status.merged)
        assertFalse(status.suppressed)
        assertFalse(status.superseded)
        assertFalse("Una memoria normal no debe marcarse como inválida", status.isInactive)
    }

    @Test
    fun mergedIntoAnotherRowMarksMerged() {
        val status = statusOf(mergedIntoId = 42L)

        assertTrue(status.merged)
        assertFalse(status.suppressed)
        assertFalse(status.superseded)
        assertTrue(status.isInactive)
    }

    @Test
    fun suppressedAtMarksFadedOut() {
        val status = statusOf(suppressedAt = "2026-10-01T08:00:00Z")

        assertTrue(status.suppressed)
        assertFalse(status.merged)
        assertFalse(status.superseded)
        assertTrue(status.isInactive)
    }

    @Test
    fun beingPointedAtByAnotherRowMarksSuperseded() {
        val status = statusOf(supersededKeys = setOf(memoryFactKey("main", 7L)))

        assertTrue(status.superseded)
        assertTrue(status.isInactive)
    }

    @Test
    fun sameIdInAnotherScopeIsNotSuperseded() {
        // Se incrementa el fact.id de la biblioteca principal y de la biblioteca secundaria.,7 El número existe en ambas bibliotecas.
        // Cuando solo se reemplaza el de la biblioteca del subasistente,La biblioteca principal nº 7 no podrá marcarse entonces como "reemplazada".
        val keys = setOf(memoryFactKey("assistant-a", 7L))

        assertFalse("No se puede considerar erróneamente que el mismo número en todas las sucursales está siendo reemplazado.", statusOf(scope = "main", supersededKeys = keys).superseded)
        assertTrue(statusOf(scope = "assistant-a", supersededKeys = keys).superseded)
    }

    @Test
    fun threeStatesAreIndependentAndCanAllHit() {
        val status = statusOf(
            mergedIntoId = 42L,
            suppressedAt = "2026-10-01T08:00:00Z",
            supersededKeys = setOf(memoryFactKey("main", 7L)),
        )

        assertTrue(status.merged)
        assertTrue(status.suppressed)
        assertTrue(status.superseded)
        assertTrue(status.isInactive)
    }

    @Test
    fun memoryItemProjectsSameFlags() {
        val item = MemoryItem(
            id = "7",
            title = "como el cafe",
            content = "como el cafe",
            source = "Fact",
            scope = "main",
            mergedIntoId = 42L,
            suppressed = true,
            superseded = true,
        )

        assertEquals(MemoryFactStatus(merged = true, suppressed = true, superseded = true), item.status)
        assertTrue(item.status.isInactive)
    }

    @Test
    fun itemsWithoutFactStatusStayQuiet() {
        // Summary / Compile / Experience La capa no tiene estos tres conceptos.,Los valores predeterminados no pueden crear una insignia de la nada.
        val item = MemoryItem(id = "s1", title = "Resumen de la sesión", content = "…", source = "Summary")

        assertFalse(item.status.isInactive)
        assertNull(item.mergedIntoId)
    }

    @Test
    fun supersedeKeyKeepsScopeApart() {
        assertEquals("main:7", memoryFactKey("main", 7L))
        assertEquals(":7", memoryFactKey(null, 7L))
        assertNotEquals(memoryFactKey("main", 7L), memoryFactKey("assistant-a", 7L))
    }
}
