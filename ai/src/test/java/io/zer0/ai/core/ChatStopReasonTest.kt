package io.zer0.ai.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Provider Prueba de normalización del motivo de parada. */
class ChatStopReasonTest {

    @Test
    fun `recognizes length limited reasons across providers`() {
        listOf("length", "max_tokens", "MAX_TOKENS", "max_output_tokens", "token_limit_exceeded")
            .forEach { assertTrue("Se debe identificar el motivo de la terminación de la duración.: $it", ChatStopReason.isLengthLimited(it)) }
    }

    @Test
    fun `does not classify natural completion or tool calls as truncation`() {
        listOf(null, "stop", "end_turn", "completed", "tool_calls", "SAFETY")
            .forEach { assertFalse("No debe reconocerse como motivo de rescisión por duración: $it", ChatStopReason.isLengthLimited(it)) }
    }

    /**
     * v2.6.1 (#5): Identificación del motivo de parada de llamada de herramienta —— usado para bloquear“reasoning Cuenta toda la historia”.
     *
     * Cuando el modelo se detiene con tool_calls,La salida pertenece al protocolo de la herramienta.;Incluso si el texto está vacío, los pensamientos no se pueden mostrar en la pantalla como respuesta.
     */
    @Test
    fun `recognizes tool call stop reasons`() {
        listOf("tool_calls", "TOOL_CALLS", "function_call", "tool call", "function_calls")
            .forEach { assertTrue("Debe reconocerse como parada de llamada de herramienta: $it", ChatStopReason.isToolCallStop(it)) }
    }

    @Test
    fun `does not treat regular completion as tool call stop`() {
        listOf(null, "stop", "length", "max_tokens", "completed", "end_turn")
            .forEach { assertFalse("No debe reconocerse como parada de llamada de herramienta: $it", ChatStopReason.isToolCallStop(it)) }
    }
}
