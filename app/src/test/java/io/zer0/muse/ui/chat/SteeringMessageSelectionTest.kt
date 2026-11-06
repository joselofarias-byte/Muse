package io.zer0.muse.ui.chat

import io.zer0.muse.ui.PendingMessage
import io.zer0.muse.ui.selectSteeringMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Juicio de recogida de interrupción (selectSteeringMessages).
 *
 * El objetivo principal es evitar la contaminación entre sesiones: inyectar mensajes en cola que pertenecen a otras sesiones como interrupciones permitirá a los usuarios verlos en la conversación.
 * Si usted no ha dicho nada, esa frase se convertirá en la base del razonamiento posterior del modelo. Por lo tanto, no se aceptarán inscripciones cuyo propietario sea desconocido.
 * Permanezca en la cola y tome la ruta de envío original —— Perderse algo es sólo un paso tardío; tomarlo mal es contaminación de datos.
 */
class SteeringMessageSelectionTest {

    @Test
    fun `only messages enqueued in the same session are steerable`() {
        val queue = listOf(
            PendingMessage("A palabras conversacionales", sessionId = SESSION_A),
            PendingMessage("B palabras conversacionales", sessionId = SESSION_B),
        )

        val selected = selectSteeringMessages(queue, SESSION_A)

        assertEquals(listOf("A palabras conversacionales"), selected.map { it.text })
    }

    @Test
    fun `message with unknown session is not steerable`() {
        val queue = listOf(
            PendingMessage("Sin sesión al hacer cola", sessionId = null),
            PendingMessage("Los datos antiguos no tienen este campo.", sessionId = null),
        )

        assertTrue(selectSteeringMessages(queue, SESSION_A).isEmpty())
    }

    @Test
    fun `message with images is not steerable even in the same session`() {
        val queue = listOf(
            PendingMessage("mensaje con imagenes", images = listOf("base64"), sessionId = SESSION_A),
            PendingMessage("texto plano", sessionId = SESSION_A),
        )

        assertEquals(listOf("texto plano"), selectSteeringMessages(queue, SESSION_A).map { it.text })
    }

    @Test
    fun `blank text is not steerable`() {
        val queue = listOf(PendingMessage("   ", sessionId = SESSION_A))

        assertTrue(selectSteeringMessages(queue, SESSION_A).isEmpty())
    }

    private companion object {
        const val SESSION_A = "session-a"
        const val SESSION_B = "session-b"
    }
}
