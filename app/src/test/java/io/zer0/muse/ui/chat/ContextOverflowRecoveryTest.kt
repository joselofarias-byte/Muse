package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Recuperación de prueba única del desbordamiento de contexto (la solicitud excede la ventana del modelo).
 *
 * Se cubren cuatro cosas:
 *  1. Criterio: Se puede identificar la copia de desbordamiento de cada proveedor, pero no se puede identificar la copia sin desbordamiento de 400/límite actual;
 *  2. Una posibilidad: reenviar después de la compresión de desbordamiento por primera vez y sin recuperación la segunda vez;
 *  3. Cuando el compresor no puede liberar espacio (regresa como está), no se retransmitirá y el error original se informará directamente;
 *  4. Cableado:runner De hecho, la rama de desbordamiento se encuentra antes de la "salida de error" y se vuelve a emitir utilizando el historial comprimido.
 *
 * Descripción:`ChatToolLoopRunner.streamRound` El anfitrión es ChatViewModel, manejarlo solo requiere un sustituto
 * Hay docenas de miembros, por lo que el comportamiento de "compresión + retransmisión" se concreta aquí con lógica pura extraída + aserciones de cableado de código fuente.
 */
class ContextOverflowRecoveryTest {

    // ── criterio ─────────────────────────────────────────────────────────────

    @Test
    fun `recognizes overflow messages from different providers`() {
        val overflowMessages =
            listOf(
                // OpenAI oficial
                "HTTP 400 [invalid request]: This model's maximum context length is 128000 tokens. " +
                    "However, your messages resulted in 137077 tokens. Please reduce the length of the " +
                    "messages. [code=context_length_exceeded] (invalid_request_error)",
                // Anthropic
                "HTTP 400 [invalid request] (invalid_request_error): prompt is too long: 210000 tokens > 200000 maximum",
                // Gemini
                "HTTP 400 (INVALID_ARGUMENT) [invalid argument]: The input token count (1050000) exceeds " +
                    "the maximum number of tokens allowed (1048576).",
                // DashScope / Qwen
                "HTTP 400 (invalid argument): Range of input length should be [1, 129024]",
                // Kimi
                "HTTP 400 [invalid request]: exceeded model token limit",
                // El cuerpo de la solicitud es demasiado grande.
                "HTTP 413 [payload too large]: request entity too large",
                // Estación de transferencia china
                "HTTP 400: La entrada es demasiado larga, acorte la conversación.",
            )
        overflowMessages.forEach { message ->
            assertTrue("Debe considerarse como desbordamiento.: $message", ContextOverflowSignals.matches(message))
        }
    }

    @Test
    fun `does not treat other 400 or rate limit as overflow`() {
        val otherErrors =
            listOf(
                "HTTP 400 [invalid request]: Invalid parameter: temperature",
                "HTTP 400 [invalid request]: max_tokens is too large for this model",
                "HTTP 429 [rate limited]: Rate limit reached for tokens per min (TPM)",
                "HTTP 401 [authentication failed]: Invalid API key",
                "Transmisión de contenido de salida pero no completado,El contenido actual se ha conservado.(Generación reintentable)",
                "",
            )
        otherErrors.forEach { message ->
            assertFalse("No debe considerarse como un desbordamiento.: $message", ContextOverflowSignals.matches(message))
        }
        assertFalse(ContextOverflowSignals.matches(null))
    }

    // ── una oportunidad ─────────────────────────────────────────────────────────

    @Test
    fun `first overflow compresses once and resends with the compressed history`() = runBlocking {
        val original = historyOf(6)
        val compressed = historyOf(2)
        val recovery = ContextOverflowRecovery()
        var compressCalls = 0

        val recovered =
            recoverFromContextOverflow(
                error = OPENAI_OVERFLOW,
                streamInterrupted = false,
                recovery = recovery,
                compress = {
                    compressCalls++
                    compressed
                },
            )

        assertEquals(1, compressCalls)
        assertEquals("El historial comprimido debe entregarse a la persona que llama para su retransmisión.", compressed, recovered)
        assertTrue("La retransmisión utiliza el historial comprimido en lugar del historial original.", recovered!!.size < original.size)
        assertTrue("se debe aprovechar la oportunidad", recovery.used)
    }

    @Test
    fun `second overflow in the same request is not recovered again`() = runBlocking {
        val recovery = ContextOverflowRecovery()
        var compressCalls = 0
        val compress: suspend () -> List<UIMessage>? = {
            compressCalls++
            historyOf(2)
        }

        val first =
            recoverFromContextOverflow(OPENAI_OVERFLOW, streamInterrupted = false, recovery = recovery, compress = compress)
        val second =
            recoverFromContextOverflow(OPENAI_OVERFLOW, streamInterrupted = false, recovery = recovery, compress = compress)

        assertTrue("La primera vez debe ser restaurada.", first != null)
        assertNull("El error original debe informarse directamente la segunda vez.,No más recuperación", second)
        assertEquals("Comprimir solo una vez durante todo el ciclo de vida de la solicitud", 1, compressCalls)
    }

    @Test
    fun `non overflow error never triggers compression`() = runBlocking {
        val recovery = ContextOverflowRecovery()
        var compressCalls = 0

        val recovered =
            recoverFromContextOverflow(
                error = "HTTP 400 [invalid request]: Invalid parameter: temperature",
                streamInterrupted = false,
                recovery = recovery,
                compress = {
                    compressCalls++
                    historyOf(2)
                },
            )

        assertNull(recovered)
        assertEquals(0, compressCalls)
        assertFalse("Los criterios de falta no deberían aprovechar las oportunidades.", recovery.used)
    }

    @Test
    fun `interrupted stream is never treated as overflow`() = runBlocking {
        val recovery = ContextOverflowRecovery()
        var compressCalls = 0

        val recovered =
            recoverFromContextOverflow(
                error = OPENAI_OVERFLOW,
                streamInterrupted = true,
                recovery = recovery,
                compress = {
                    compressCalls++
                    historyOf(2)
                },
            )

        assertNull(recovered)
        assertEquals(0, compressCalls)
    }

    @Test
    fun `compressor with nothing to compress gives up without a second attempt`() = runBlocking {
        val recovery = ContextOverflowRecovery()
        var compressCalls = 0
        val compress: suspend () -> List<UIMessage>? = {
            compressCalls++
            // Rendimiento cuando el compresor no tiene ganancia neta/no hay intervalo de compresión: regresa como está,runner El lado está doblado hacia null
            null
        }

        val first =
            recoverFromContextOverflow(OPENAI_OVERFLOW, streamInterrupted = false, recovery = recovery, compress = compress)
        val second =
            recoverFromContextOverflow(OPENAI_OVERFLOW, streamInterrupted = false, recovery = recovery, compress = compress)

        assertNull("Si no se puede presionar, no se recuperará.", first)
        assertNull(second)
        assertEquals("Si no se puede presionar, no lo volveré a intentar.", 1, compressCalls)
    }

    // ── Cableado ─────────────────────────────────────────────────────────────

    @Test
    fun `runner recovers before the error outlet and resends the compressed history`() {
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt"),
                Path.of("app/src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("ChatToolLoopRunner.kt not found")

        val recoveryBlock =
            source.substringAfter("// Recuperación de desbordamiento de contexto (la solicitud excede la ventana del modelo)")
                .substringBefore("streamError?.let {")
        assertTrue("Restaurar la puerta unificada", recoveryBlock.contains("recoverFromContextOverflow("))
        assertTrue("Reutilización de la compresión de compresores disponibles en el mercado", recoveryBlock.contains("compressForOverflowRecovery("))
        assertTrue(
            "La historia comprimida debe retransmitirse en esta ronda.",
            recoveryBlock.contains("return streamRound(") &&
                recoveryBlock.contains("history = overflowHistory"),
        )
        assertTrue(
            "El historial comprimido se reescribe en todo el ciclo.",
            recoveryBlock.contains("conversationHistory.clear()") &&
                recoveryBlock.contains("conversationHistory.addAll(overflowHistory)"),
        )

        // La recuperación debe ocurrir antes de la "salida de error":streamError Intente recuperarse primero y solo dé el paso final si falla.
        // updateAssistant + StreamRoundResult.Error.
        val recoveryIndex = source.indexOf("// Recuperación de desbordamiento de contexto (la solicitud excede la ventana del modelo)")
        // Utilice una línea única en la salida de error como ancla (sin texto entre líneas: el archivo es CRLF, deletree \n no coincidirá)
        val errorOutletIndex = source.indexOf("val displayMsg = ErrorMessages.resolve(host.appContext, it)")
        assertTrue("No se encontró la rama de recuperación de desbordamiento", recoveryIndex > 0)
        assertTrue("Salida de error no encontrada", errorOutletIndex > 0)
        assertTrue("La rama de recuperación debe estar antes de la salida del error.", recoveryIndex < errorOutletIndex)
    }

    private fun historyOf(size: Int): List<UIMessage> = (1..size).map { index ->
        UIMessage(
            role = if (index % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
            content = "message $index",
        )
    }

    private companion object {
        const val OPENAI_OVERFLOW: String =
            "HTTP 400 [invalid request]: This model's maximum context length is 128000 tokens. " +
                "However, your messages resulted in 137077 tokens. [code=context_length_exceeded]"
    }
}
