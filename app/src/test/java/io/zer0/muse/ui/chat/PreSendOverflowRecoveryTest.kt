package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Auto-rescate de desbordamiento de contexto antes de enviar (ChatGenerationController) prueba única.
 *
 * La intercepción en sí no se elimina, sólo se le antepone "compresión + revaluación":
 *  - intercepción de golpe → Presione una ronda → Reestimar → Si no supera el límite, envíalo normalmente;
 *  - Aún se excede después de presionar/no puedo presionar → Continúe interceptando e informe el error original (la copia permanece sin cambios);
 *  - Solo presiona una vez en todo el proceso, no hay reintentos.
 *
 * El portón y el que está en funcionamiento ([ContextOverflowRecoveryTest]) deliberadamente**independiente**: Uno es la estimación local,
 * Uno es un rechazo real por parte del proveedor y hay dos fuentes desencadenantes (consulte los comentarios en ChatGenerationController para conocer los motivos).
 */
class PreSendOverflowRecoveryTest {

    @Test
    fun `blocked send compresses once and continues when the new payload fits`() = runBlocking {
        val original = historyOf(12)
        val compressed = historyOf(4)
        var compressCalls = 0
        var overflowChecks = 0

        val recovered =
            recoverPreSendOverflow(
                history = original,
                compress = {
                    compressCalls++
                    compressed
                },
                stillOverflows = {
                    overflowChecks++
                    false
                },
            )

        assertEquals(1, compressCalls)
        assertEquals(1, overflowChecks)
        assertEquals("Después de presionar se enviará normalmente sin exceder la ventana.", compressed, recovered)
    }

    @Test
    fun `still over the hard limit after compression keeps the block`() = runBlocking {
        val original = historyOf(12)
        var compressCalls = 0
        var overflowChecks = 0

        val recovered =
            recoverPreSendOverflow(
                history = original,
                compress = {
                    compressCalls++
                    historyOf(4)
                },
                stillOverflows = {
                    overflowChecks++
                    true
                },
            )

        assertNull("Después de presionar, todavía excede la ventana. → sigue interceptando,Informar error original", recovered)
        assertEquals(1, compressCalls)
        assertEquals("Solo revalúa una vez,No presione repetidamente", 1, overflowChecks)
    }

    @Test
    fun `compressor that cannot free space gives up without re-estimating`() = runBlocking {
        val original = historyOf(12)
        var compressCalls = 0
        var overflowChecks = 0

        val unavailable =
            recoverPreSendOverflow(
                history = original,
                compress = {
                    compressCalls++
                    null
                },
                stillOverflows = {
                    overflowChecks++
                    false
                },
            )
        assertNull("La compresión no está disponible/no se puede liberar espacio → Informar un error directamente", unavailable)

        val unchanged =
            recoverPreSendOverflow(
                history = original,
                compress = {
                    compressCalls++
                    // El resultado de la conversión devuelto por el compresor tal como está es nulo para la persona que llama, lo cual también se trata aquí."El número de artículos no ha cambiado."
                    original
                },
                stillOverflows = {
                    overflowChecks++
                    false
                },
            )
        assertNull("No corto → No te salves", unchanged)

        assertEquals(2, compressCalls)
        assertEquals("Si no está en corto, no es necesario volver a evaluarlo. payload", 0, overflowChecks)
    }

    @Test
    fun `pre-send block attempts recovery before refusing the send`() {
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt"),
                Path.of("app/src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("ChatGenerationController.kt not found")

        val recoveryIndex = source.indexOf("recoverPreSendOverflow(")
        // Utilice un posicionamiento de anclaje de una sola línea (el archivo es CRLF y el empalme de texto entre líneas no coincidirá)
        val blockIndex = source.indexOf("contextOverflowBlocked = true")
        assertTrue("No se encontró la llamada de autorrescate previa al envío", recoveryIndex > 0)
        assertTrue("Conjunto de intercepción no encontrado", blockIndex > 0)
        assertTrue("El autorrescate debe ocurrir antes de que se establezca la intercepción.", recoveryIndex < blockIndex)

        val block = source.substring(recoveryIndex, blockIndex)
        assertTrue("Una vez que se acabe la presión, tendré que volver a escribir la historia.", block.contains("truncatedHistory = compressedHistory"))
        // sólo afirmar"Reevaluar utilizando candidatos comprimidos"Esta semántica; Método de escritura de llamadas independientes (la ronda 37 proporciona esta estimación).
        // Con la adición de una caché de recuento compartida, hay más parámetros. El ancla original requiere una llamada completa en una sola línea y los cambios de formato puro se considerarán regresión).
        assertTrue("Reestimar después de presionar payload", block.contains("prefixMessages + candidate"))

        // La intercepción aún se conserva (la red de seguridad final), pero hay un autorrescate adicional al frente.
        assertTrue("No se deben quitar los bloques", block.contains("if (compressedHistory != null)"))
        assertTrue("El camino de la redacción publicitaria de interceptación sigue ahí", source.substring(blockIndex).contains("refuse to send"))
    }

    private fun historyOf(size: Int): List<UIMessage> = (1..size).map { index ->
        UIMessage(
            role = if (index % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
            content = "message $index",
        )
    }
}
