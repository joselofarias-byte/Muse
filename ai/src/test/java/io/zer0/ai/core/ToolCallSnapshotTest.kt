package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de regresión: los parámetros completos de tool_call enviados para respaldos que no son de transmisión deben**Portada**En lugar de agregar a los parámetros ya acumulados en la etapa de transmisión.
 *
 * Síntomas en línea (chat grupal): fase de transmisión finish_reason=tool_calls Pero el contenido está vacío. → Activar respaldo sin transmisión →
 * El respaldo devuelve parámetros completos → El agregador une los dos segmentos (log len=230 → argsLen=473, casi exactamente dos veces)→
 * El JSON detallado ya no es válido → Analizar campos vacíos → channel_reply Informe "content No puede estar vacío"→ Una ronda de silencio.
 */
class ToolCallSnapshotTest {

    @Test
    fun `Los fotogramas de instantáneas sobrescriben los parámetros que se han acumulado durante la fase de transmisión`() {
        val partial = """{"content":"la mitad"""
        val full = """{"content":"oración completa"}"""

        val events = listOf(
            // Etapa de streaming: sólo a mitad de camino
            ChatStreamEvent.ToolCallDelta(
                index = 0,
                id = "call_1",
                name = "channel_reply",
                argumentsDelta = partial,
            ),
            // Respaldo sin transmisión: parámetros completos, instantánea etiquetada
            ChatStreamEvent.ToolCallDelta(
                index = 0,
                id = "call_1",
                name = "channel_reply",
                argumentsDelta = full,
                isSnapshot = true,
            ),
        )

        val result = events.normalize()
        assertEquals(1, result.toolCalls.size)
        assertEquals(
            "Las instantáneas deben sobrescribirse en lugar de agregarse; los parámetros finales deben ser iguales a los parámetros completos",
            full,
            result.toolCalls.first().arguments,
        )
    }

    @Test
    fun `Sigue siendo una adición incremental sin instantánea (control)`() {
        val a = """{"content":"antes"""
        val b = """Media frase"}"""

        val events = listOf(
            ChatStreamEvent.ToolCallDelta(index = 0, id = "call_1", name = "channel_reply", argumentsDelta = a),
            ChatStreamEvent.ToolCallDelta(index = 0, argumentsDelta = b),
        )

        val result = events.normalize()
        assertEquals(
            "Los marcos incrementales ordinarios deben empalmarse en completos. JSON",
            """{"content":"primera mitad de la oración"}""",
            result.toolCalls.first().arguments,
        )
    }

    @Test
    fun `La reversión repetida no escribirá los parámetros dos veces`() {
        val full = """{"content":"Hola"}"""
        val events = listOf(
            ChatStreamEvent.ToolCallDelta(index = 0, name = "channel_reply", argumentsDelta = """{"con"""),
            ChatStreamEvent.ToolCallDelta(
                index = 0,
                name = "channel_reply",
                argumentsDelta = full,
                isSnapshot = true,
            ),
            // Caso extremo: el respaldo se activa dos veces
            ChatStreamEvent.ToolCallDelta(
                index = 0,
                name = "channel_reply",
                argumentsDelta = full,
                isSnapshot = true,
            ),
        )

        val result = events.normalize()
        val args = result.toolCalls.first().arguments
        assertEquals("Después de dos instantáneas, solo debería haber una copia de los parámetros completos.", full, args)
        assertTrue("La longitud del parámetro no debe duplicarse", args.length < full.length * 2)
    }
}
