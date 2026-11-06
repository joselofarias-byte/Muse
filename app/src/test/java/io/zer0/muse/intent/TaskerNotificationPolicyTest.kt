package io.zer0.muse.intent

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-CHANNEL-F10: Tasker Las notificaciones de resultados no deben estar codificadas policy="always".
 *
 * enlace principal(ChatGenerationController)Establecer el usuario never/when_unfocused/always
 * entrante notifyChatCompletedWithPolicy,La ruta de Tasker estaba previamente codificada always,
 * haciendo que el usuario elija“nunca notificar”La notificación sigue apareciendo cada vez que se activa Tasker.
 */
class TaskerNotificationPolicyTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/intent/TaskerTriggerReceiver.kt").readText()

    @Test
    fun `tasker notification reads user policy`() {
        val src = source()
        val start = src.indexOf("AUD-CHANNEL-F10")
        assertTrue("AUD-CHANNEL-F10 bloque de protección no encontrado", start >= 0)
        val body = src.substring(start, (start + 900).coerceAtMost(src.length))
        assertTrue(
            "Debe leer la política de notificación al usuario",
            body.contains("settings?.notificationPolicyFlow?.first()"),
        )
        assertTrue(
            "La política de lectura debe pasarse a la notificación. API",
            body.contains("policy = policy"),
        )
    }

    @Test
    fun `no hardcoded always policy remains`() {
        val src = source()
        val code = src.lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertTrue(
            "Todavía codificado policy = \"always\"(excepto comentarios)",
            !code.contains("policy = \"always\""),
        )
    }
}
