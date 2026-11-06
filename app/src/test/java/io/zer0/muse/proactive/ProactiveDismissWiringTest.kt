package io.zer0.muse.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Mensaje activo cuatro tableros de clavos de cableado de circuito cerrado.
 *
 * Casi todas estas rutas se basan en "cómo cambia el estado en la biblioteca" y "dónde se ajustará cuando se haga clic en un determinado botón".
 * La prueba única puede cubrir la primera, pero "si el receptor está registrado o no / si hay un botón para colgar en la notificación". /
 * "¿La inspección ha ajustado realmente la fecha de caducidad?" Este tipo de cosas solo se pueden ver en el código fuente. Este tipo de omisión se puede compilar,
 * La prueba única también es toda verde. Solo me enteré cuando no hubo respuesta cuando hice clic en el dispositivo real, así que lo fijé con un escaneo.
 */
class ProactiveDismissWiringTest {

    @Test
    fun `Sólo se reclamarán las acciones contenidas en este acuerdo`() {
        assertEquals(
            "o1",
            ProactiveDismissProtocol.dismissedOutcomeId(ProactiveDismissProtocol.ACTION_DISMISS, "o1"),
        )
        assertNull(
            "Otras acciones no deben reclamarse",
            ProactiveDismissProtocol.dismissedOutcomeId("io.zer0.muse.action.OTHER", "o1"),
        )
        assertNull(
            "Las retransmisiones sin acción no se cuentan",
            ProactiveDismissProtocol.dismissedOutcomeId(null, "o1"),
        )
        assertNull(
            "La transmisión con identificación vacía no tiene ningún objeto marcable",
            ProactiveDismissProtocol.dismissedOutcomeId(ProactiveDismissProtocol.ACTION_DISMISS, "  "),
        )
    }

    @Test
    fun `Los receptores de acciones de notificación están registrados con el indicador de no exportación`() {
        val app = source("io/zer0/muse/MuseApp.kt")
        val call = callSite(app, "ContextCompat.registerReceiver(")
        assertTrue("El registrado debe ser un receptor activo de acciones de mensajes:$call", call.contains("ProactiveDismissReceiver()"))
        assertTrue("La condición del filtro debe ser una acción de este protocolo:$call", call.contains("ProactiveDismissProtocol.ACTION_DISMISS"))
        assertTrue(
            "RECEIVER_NOT_EXPORTED debe declararse explícitamente: esta transmisión solo debe ser enviada por la propia aplicación:$call",
            call.contains("ContextCompat.RECEIVER_NOT_EXPORTED"),
        )
        assertTrue(
            "Basta con no exportar y no se debe cambiar a RECEIVER_EXPORTED:$call",
            !call.contains("ContextCompat.RECEIVER_EXPORTED"),
        )
    }

    @Test
    fun `La notificación de mensaje activo se cuelga y esta vez no se utiliza ningún botón`() {
        val notifier = source("io/zer0/muse/notification/MuseNotificationManager.kt")
        assertTrue(
            "Debe haber un botón de acción en la notificación; de lo contrario, el usuario no tendrá forma de \"no usarlo esta vez\".",
            notifier.contains("builder.addAction("),
        )
        assertTrue(
            "El destino del clic del botón debe ser la misma transmisión de acción.",
            notifier.contains("buildDismissIntent(") &&
                callSite(notifier, "private fun buildDismissIntent(").contains("ProactiveDismissReceiver"),
        )
        assertTrue(
            "Utilice recursos de cadena independientes para la copia del botón, no los reutilice con el título",
            notifier.contains("R.string.notif_proactive_action_dismiss"),
        )
        assertTrue(
            "Broadcast PendingIntent debe ser inmutable,Android 12+ Existe el riesgo de sobrescritura cuando el sistema emite una variable PendingIntent.",
            callSite(notifier, "private fun buildDismissIntent(").contains("PendingIntent.FLAG_IMMUTABLE"),
        )
    }

    @Test
    fun `Durante la inspección se realizó cierre de vencimientos y filtrado de candidatos`() {
        val runner = source("io/zer0/muse/proactive/ProactiveCycleRunner.kt")
        assertTrue("El cierre vencido debe quedar colgado en la inspección, no configurar cronómetro", runner.contains("expireOverdue(now)"))
        assertTrue("El filtrado de candidatos utiliza conclusiones aprendidas", runner.contains("unpopularKinds("))
        assertTrue(
            "La identificación de la cuenta resultante debe transmitirse de forma transparente a la notificación; de lo contrario, el botón no sabrá en qué elemento se hizo clic.",
            runner.contains("outcomeId = outcomeId"),
        )
    }

    @Test
    fun `Marcar mensajes activos como leídos al cambiar de sesión`() {
        val controller = source("io/zer0/muse/ui/chat/ChatSessionController.kt")
        assertTrue(
            "Los enlaces profundos de notificación, los teléfonos celulares pequeños y los chats principales convergen para cambiar de sesión, por lo que debe marcarlo como leído aquí.",
            controller.contains(".onSessionOpened(sessionId)"),
        )
    }

    // ══════════════════════════════════════════════════════════════

    /** Tome una pequeña sección del código fuente después de un determinado punto de llamada para limitar el alcance de la aserción y evitar ambigüedades con cadenas del mismo nombre en otras partes de todo el archivo. */
    private fun callSite(text: String, marker: String): String {
        val start = text.indexOf(marker)
        if (start < 0) fail("No puedo encontrarlo en el código fuente. $marker")
        return text.substring(start, minOf(text.length, start + WINDOW))
    }

    private fun source(relative: String): String {
        val roots = listOf("app/src/main/java", "src/main/java", "../app/src/main/java")
        val file = roots.map { File(it, relative) }.firstOrNull { it.isFile }
            ?: fail("Código fuente no encontrado $relative(directorio de trabajo ${File(".").absolutePath})")
        return file.readText()
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    private companion object {
        /** ¿A qué distancia del punto de llamada? suficiente para ajustarse a la lista de argumentos de una llamada de varias líneas. */
        const val WINDOW = 700
    }
}
