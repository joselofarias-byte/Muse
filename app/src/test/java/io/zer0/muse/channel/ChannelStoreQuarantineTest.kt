package io.zer0.muse.channel

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-CHANNEL-F4: La recuperación del almacenamiento del canal falla y los archivos defectuosos deben ponerse en cuarentena,En lugar de borrar silenciosamente y luego sobrescribir.
 *
 * La implementación original solo registra registros cuando falla la decodificación y continúa usando una lista vacía.,Las escrituras posteriores sobrescriben el archivo defectuoso con contenido nuevo.,
 * La escena original está perdida y es imposible de diagnosticar. Los proyectos ya tienen la convención AtomicFileStore.quarantine.
 */
class ChannelStoreQuarantineTest {

    private fun source(name: String): String = java.io.File("src/main/java/io/zer0/muse/channel/$name").readText()

    @Test
    fun `inbox restore failure quarantines the file`() {
        val src = source("ChannelInbox.kt")
        assertTrue(
            "La recuperación de la bandeja de entrada falla y los archivos defectuosos deben ponerse en cuarentena",
            src.contains("AtomicFileStore.quarantine(inboxFile"),
        )
    }

    @Test
    fun `conversation store restore failure quarantines the file`() {
        val src = source("ChannelConversationStore.kt")
        assertTrue(
            "La recuperación del historial de conversaciones falla y los archivos defectuosos deben ponerse en cuarentena",
            src.contains("AtomicFileStore.quarantine(target"),
        )
    }

    @Test
    fun `webhook ledger oversize quarantines instead of throwing`() {
        val src = source("WebhookEventDeduplicator.kt")
        assertTrue(
            "Los libros de contabilidad de exceso de capacidad deben aislarse y reabrirse,En lugar de requerir arrojar un error",
            src.contains("AtomicFileStore.quarantine(file, \"webhook_ledger_oversize\")"),
        )
        assertTrue(
            "No debería haber más require(...) error de capacidad",
            !src.contains("require(loaded.size <= MAX_ENTRIES)"),
        )
    }
}
