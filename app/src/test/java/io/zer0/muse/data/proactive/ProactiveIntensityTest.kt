package io.zer0.muse.data.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueba única de nivel activo.
 *
 * Este conjunto de engranajes controla dos cosas diferentes: cuántas cosas puedes decir en un día (floor/cap), y**¿Qué se puede decir?**(allowedKinds).
 * Este último no tiene el lado del consumidor desde hace mucho tiempo. —— La enumeración dice "Los archivos silenciosos solo dicen lo que hay que decir", pero la capa de programación real nunca filtra.
 * Por lo tanto, los usuarios que eligieron silencio aún podrán compartir y obtener hitos. La semántica está clavada aquí para evitar volver a ese estado.
 */
class ProactiveIntensityTest {

    @Test
    fun `Sólo se permite decir cosas específicas en modo silencioso`() {
        assertTrue(ProactiveIntensity.QUIET.allows(ThoughtKind.REMINDER))
        assertFalse("Los archivos silenciosos no deben compartirse activamente", ProactiveIntensity.QUIET.allows(ThoughtKind.SHARE))
        assertFalse("Los archivos silenciosos no deben informar nodos de relación", ProactiveIntensity.QUIET.allows(ThoughtKind.MILESTONE))
        assertFalse("No deberías preocuparte activamente por las conversaciones triviales durante los momentos de tranquilidad.", ProactiveIntensity.QUIET.allows(ThoughtKind.CARE))
    }

    @Test
    fun `De vez en cuando, se me recuerda que puedo recibir llamadas y atención, pero no puedo compartir`() {
        assertTrue(ProactiveIntensity.RESERVED.allows(ThoughtKind.FOLLOW_UP))
        assertTrue(ProactiveIntensity.RESERVED.allows(ThoughtKind.CARE))
        assertFalse(ProactiveIntensity.RESERVED.allows(ThoughtKind.SHARE))
        assertFalse(ProactiveIntensity.RESERVED.allows(ThoughtKind.MILESTONE))
    }

    @Test
    fun `Estándar y superiores no limitan el tipo`() {
        listOf(
            ProactiveIntensity.STANDARD,
            ProactiveIntensity.ACTIVE,
            ProactiveIntensity.CLINGY,
        ).forEach { level ->
            ThoughtKind.entries.forEach { kind ->
                assertTrue("${level.name} debería estar permitido ${kind.name}", level.allows(kind))
            }
        }
    }

    @Test
    fun `El número máximo diario de artículos es 3, 8, 15, 30 e ilimitado según el nivel`() {
        assertEquals(3, ProactiveIntensity.QUIET.cap)
        assertEquals(8, ProactiveIntensity.RESERVED.cap)
        assertEquals(15, ProactiveIntensity.STANDARD.cap)
        assertEquals(30, ProactiveIntensity.ACTIVE.cap)
        assertEquals(Int.MAX_VALUE, ProactiveIntensity.CLINGY.cap)
        assertTrue(ProactiveIntensity.CLINGY.unlimited)
        assertFalse(ProactiveIntensity.ACTIVE.unlimited)
    }

    @Test
    fun `Sólo el modo silencioso requiere que hables cuando sucede algo`() {
        assertTrue(ProactiveIntensity.QUIET.onlyWhenNeeded)
        assertFalse(ProactiveIntensity.RESERVED.onlyWhenNeeded)
        assertFalse(ProactiveIntensity.STANDARD.onlyWhenNeeded)
        assertFalse(ProactiveIntensity.ACTIVE.onlyWhenNeeded)
        assertFalse(ProactiveIntensity.CLINGY.onlyWhenNeeded)
    }

    @Test
    fun `Filtrar candidatos por equipo puede eliminar tipos que no deberían mencionarse en ese equipo`() {
        val ledger = listOf(
            thought(id = "t1", kind = ThoughtKind.REMINDER),
            thought(id = "t2", kind = ThoughtKind.SHARE),
            thought(id = "t3", kind = ThoughtKind.MILESTONE),
            thought(id = "t4", kind = ThoughtKind.CARE),
        )

        val quiet = ledger.filter { ProactiveIntensity.QUIET.allows(it.kindEnum()) }
        assertEquals(listOf("t1"), quiet.map { it.id })

        val reserved = ledger.filter { ProactiveIntensity.RESERVED.allows(it.kindEnum()) }
        assertEquals(listOf("t1", "t4"), reserved.map { it.id })

        val standard = ledger.filter { ProactiveIntensity.STANDARD.allows(it.kindEnum()) }
        assertEquals(ledger.map { it.id }, standard.map { it.id })
    }

    @Test
    fun `Los nombres de equipos ilegales vuelven a los predeterminados`() {
        assertEquals(ProactiveIntensity.DEFAULT, ProactiveIntensity.fromName("NOT_A_LEVEL"))
        assertEquals(ProactiveIntensity.DEFAULT, ProactiveIntensity.fromName(null))
        assertEquals(ProactiveIntensity.QUIET, ProactiveIntensity.fromName("QUIET"))
    }

    private fun thought(id: String, kind: ThoughtKind) = PendingThoughtEntity(
        id = id,
        assistantId = "a1",
        kind = kind.name,
        seed = "para probar",
        weight = 0.5f,
    )
}
