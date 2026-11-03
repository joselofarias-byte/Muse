package io.zer0.muse.tools.reminder

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Recordatorio de contratos de pedidos reordenados.
 *
 * Lo que se mide aquí es**Secuencia de declaraciones**, no el resultado del comportamiento —— El comportamiento requiere contexto real y canal de notificación.
 * No puedo levantarme durante la prueba única (Robolectric Se producirá un bloqueo nativo).
 *
 * ¿Por qué vale la pena bloquearlo por separado? Si primero envía una notificación y luego realiza el pedido, y el proceso se detiene entre los dos pasos,
 * La próxima vez que vuelvas a realizar un pedido, verás que el recordatorio todavía está "caducado", así que lo compensaré nuevamente.
 * El usuario recibe dos idénticos "(suplemento)" Fíjate, y este camino es extremadamente difícil de reproducir en una máquina real.
 */
class ReminderCatchUpOrderTest {

    @Test
    fun `Los recordatorios vencidos deben colocarse y eliminarse antes de emitir una notificación de reemplazo.`() {
        val source = File("src/main/java/io/zer0/muse/tools/reminder/ReminderStore.kt").readText()
        val start = source.indexOf("private fun rescheduleAllLocked(")
        val end = source.indexOf("private fun catchUpNotify", startIndex = start)
        require(start >= 0 && end > start) { "No se puede localizar el límite del método rescheduleAllLocked" }
        val body = source.substring(start, end)

        val persistAt = body.indexOf("writeAll(remaining)")
        val notifyAt = body.indexOf("catchUpNotify(it)")

        assertTrue("En el método de reordenamiento, el pedido debe realizarse una vez", persistAt >= 0)
        assertTrue("Los recordatorios vencidos deben emitirse después de realizar el pedido.", notifyAt >= 0)
        assertTrue(
            "La colocación debe preceder a la notificación de reemisión: notificar primero y luego realizar el pedido resultará en una reemisión repetida cuando el proceso finalice.",
            persistAt < notifyAt,
        )
    }
}
