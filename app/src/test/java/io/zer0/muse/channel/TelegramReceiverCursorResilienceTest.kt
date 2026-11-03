package io.zer0.muse.channel

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-CHANNEL-F2: Telegram La lectura del cursor local de un bucle de recepción no debe provocar que el trabajo finalice permanentemente.
 *
 * En la implementación original loadOffset `error("Telegram offset Formato de archivo no válido")` Tirar fuera del circuito,
 * offset Una vez que el archivo está dañado,Al recibir un trabajo, este muere cada vez que se inicia sin reiniciar. —— El canal está permanentemente desconectado.
 */
class TelegramReceiverCursorResilienceTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/channel/TelegramReceiver.kt").readText()

    @Test
    fun `corrupt offset file no longer throws`() {
        val src = source()
        assertTrue(
            "loadOffset no debe usarse nuevamente error() Lanzar una excepción y terminar job",
            !src.contains("error(\"Telegram offset Formato de archivo no válido\")"),
        )
        assertTrue(
            "El desplazamiento corrupto debería registrar una advertencia y descartar el cursor",
            src.contains("Telegram offset Archivo corrupto"),
        )
    }

    @Test
    fun `cursor initialization failure retries instead of exiting loop`() {
        val src = source()
        assertTrue(
            "La inicialización del cursor debe incluir runCatching,No retroceder y volver a intentarlo en lugar de salir del bucle",
            src.contains("runCatching { TelegramCursor(config.id, loadOffset(config.id)) }"),
        )
    }
}
