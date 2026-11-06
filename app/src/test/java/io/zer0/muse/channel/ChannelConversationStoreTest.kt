package io.zer0.muse.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChannelConversationStore.applyCompression] Prueba única.
 *
 * Cobertura clave 2026-09-29 Revisar los hallazgos"El resumen de desplazamiento del canal pierde rondas":
 * La implementación original es"leer instantánea → sintonizar LLM → Reescritura completa",Las nuevas rondas de agregados se descartan durante la compactación.
 */
class ChannelConversationStoreTest {
    private fun seed(channelId: String, from: String, count: Int, prefix: String = "turn"): List<ChannelConversationStore.Turn> {
        ChannelConversationStore.clear(channelId, from)
        repeat(count) { i ->
            ChannelConversationStore.append(channelId, from, if (i % 2 == 0) "user" else "assistant", "$prefix-$i")
        }
        return ChannelConversationStore.conversation(channelId, from)?.turns.orEmpty()
    }

    @Test
    fun `La reescritura de compresión conserva las rondas recién llegadas durante la compresión`() {
        val channelId = "ch-regression"
        val from = "peer-regression"
        val turns = seed(channelId, from, 6)
        val snapshot = ChannelConversationStore.conversation(channelId, from)!!
        val drained = turns.take(4)

        // Compresión en curso(LLM durante la llamada)El usuario publicó otro
        ChannelConversationStore.append(channelId, from, "user", "Nuevos mensajes durante la compresión")

        val applied =
            ChannelConversationStore.applyCompression(
                channelId = channelId,
                from = from,
                snapshotSummary = snapshot.summary,
                drainedTurns = drained,
                summary = "ResumenA",
            )

        assertTrue("debe escribirse correctamente", applied)
        val now = ChannelConversationStore.conversation(channelId, from)!!
        assertEquals("ResumenA", now.summary)
        // Se quitaron las 4 ruedas comprimidas.;Las últimas 2 rondas retenidas + la nueva llegada durante la compresión deben estar en
        assertEquals(listOf("turn-4", "turn-5", "Nuevos mensajes durante la compresión"), now.turns.map { it.text })
    }

    @Test
    fun `Escritura abandonada cuando el resumen ha sido actualizado por otra compactación`() {
        val channelId = "ch-cas"
        val from = "peer-cas"
        val turns = seed(channelId, from, 6)
        val snapshot = ChannelConversationStore.conversation(channelId, from)!!

        // Primera compresión exitosa
        assertTrue(
            ChannelConversationStore.applyCompression(channelId, from, snapshot.summary, turns.take(4), "ResumenA"),
        )
        val afterFirst = ChannelConversationStore.conversation(channelId, from)!!

        // La segunda compresión utiliza"antes de la compresión"instantánea de(El resumen está vacío)→ debe rendirse,Los extractos no se pueden sobrescribir con contenido antiguo.,
        // Tampoco puedo realizar 4 rondas más.(De lo contrario, los mensajes nuevos después de la última ronda se eliminarán por error.)
        val applied =
            ChannelConversationStore.applyCompression(channelId, from, snapshot.summary, turns.take(4), "ResumenB")

        assertFalse("El resumen ha sido actualizado.,Se debe abandonar la escritura.", applied)
        val afterSecond = ChannelConversationStore.conversation(channelId, from)!!
        assertEquals("ResumenA", afterSecond.summary)
        assertEquals(afterFirst.turns.map { it.text }, afterSecond.turns.map { it.text })
    }

    @Test
    fun `Cancelar la escritura cuando la ronda límite no coincide`() {
        val channelId = "ch-boundary"
        val from = "peer-boundary"
        val turns = seed(channelId, from, 6)
        val snapshot = ChannelConversationStore.conversation(channelId, from)!!

        // Las conversaciones se borran y se reconstruyen durante la compresión.(Las rondas de límites ya no son las mismas:El texto es diferente)
        seed(channelId, from, 6, prefix = "rebuilt")

        val applied =
            ChannelConversationStore.applyCompression(channelId, from, snapshot.summary, turns.take(4), "ResumenA")

        assertFalse("Si los límites no coinciden, debes rendirte.", applied)
        assertEquals(6, ChannelConversationStore.conversation(channelId, from)!!.turns.size)
    }

    @Test
    fun `La compresión normal elimina las rondas que se han fusionado en el resumen y escribe el resumen`() {
        val channelId = "ch-happy"
        val from = "peer-happy"
        val turns = seed(channelId, from, 5)

        val applied =
            ChannelConversationStore.applyCompression(
                channelId = channelId,
                from = from,
                snapshotSummary = "",
                drainedTurns = turns.take(3),
                summary = "ResumenX",
            )

        assertTrue(applied)
        val now = ChannelConversationStore.conversation(channelId, from)!!
        assertEquals("ResumenX", now.summary)
        assertEquals(listOf("turn-3", "turn-4"), now.turns.map { it.text })
        assertTrue("updatedAt debe ser renovado", now.updatedAt > 0L)
    }

    @Test
    fun `Deje de escribir cuando no haya rondas para elegir`() {
        val channelId = "ch-empty"
        val from = "peer-empty"
        seed(channelId, from, 3)

        val applied =
            ChannelConversationStore.applyCompression(channelId, from, "", emptyList(), "ResumenY")

        assertFalse(applied)
        assertEquals("", ChannelConversationStore.conversation(channelId, from)!!.summary)
    }

    @Test
    fun `Los reintentos de respuesta automática no agregarán rondas repetidamente y permanecerán idempotentes después de la compresión`() {
        val channelId = "ch-dispatch-idempotency"
        val from = "peer-dispatch-idempotency"
        val dispatchId = "dispatch-1"
        ChannelConversationStore.clear(channelId, from)

        appendDispatched(channelId, from, "user", "Mensaje recibido", dispatchId)
        appendDispatched(channelId, from, "assistant", "Responder listo para enviar", dispatchId)
        appendDispatched(channelId, from, "user", "Escritura repetida", dispatchId)

        val beforeCompression = ChannelConversationStore.conversation(channelId, from)!!
        assertEquals(listOf("Mensaje recibido", "Responder listo para enviar"), beforeCompression.turns.map { it.text })
        assertTrue(
            ChannelConversationStore.applyCompression(
                channelId,
                from,
                beforeCompression.summary,
                beforeCompression.turns,
                "Resumen de la conversación",
            ),
        )

        appendDispatched(channelId, from, "user", "Mensaje recibido", dispatchId)
        appendDispatched(channelId, from, "assistant", "Responder listo para enviar", dispatchId)

        val afterRetry = ChannelConversationStore.conversation(channelId, from)!!
        assertEquals("Resumen de la conversación", afterRetry.summary)
        assertTrue(afterRetry.turns.isEmpty())
    }

    private fun appendDispatched(channelId: String, from: String, role: String, text: String, dispatchId: String) {
        ChannelConversationStore.appendTurn(
            channelId,
            from,
            ChannelConversationStore.Turn(role = role, text = text, dispatchId = dispatchId),
        )
    }
}
