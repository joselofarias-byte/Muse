package io.zer0.muse.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueba única de análisis proactivo de respuestas a mensajes.
 *
 * El punto es `thought_id`: Es la única evidencia de "de qué se trata este mensaje",
 * El nivel superior se basa en él para marcar las preocupaciones como si se hubieran expresado, permitiendo así que "dejar de repetir automáticamente lo mismo una y otra vez" surta efecto.
 * Si no se puede analizar, no informará un error ni afectará la entrega. Sólo detendrá silenciosamente la retirada del libro mayor. ——
 * Es este tipo de falla silenciosa la que el evaluador debe observar.
 */
class ProactiveReplyParserTest {

    @Test
    fun `JSON Thought_id en será analizado`() {
        val raw = """{"skip": false, "content": "¿Qué pasó con ese incidente?", "tone": "cuidar", "thought_id": "th_123"}"""

        val result = ProactiveReplyParser.parse(raw)

        assertTrue(result is ProactiveReplyParser.Result.Message)
        assertEquals("th_123", (result as ProactiveReplyParser.Result.Message).thoughtId)
        assertEquals("¿Qué pasó con ese incidente?", result.content)
    }

    @Test
    fun `El think_id vacío se trata como ninguno`() {
        // La palabra de aviso requiere "dejar la cadena vacía si no proviene de una preocupación". Dejarlo vacío significa una salida normal, no una salida anormal.
        val raw = """{"skip": false, "content": "Acabo de ver algo que me recordó a ti.", "tone": "Compartir", "thought_id": ""}"""

        val result = ProactiveReplyParser.parse(raw) as ProactiveReplyParser.Result.Message

        assertNull(result.thoughtId)
    }

    @Test
    fun `El campo Thought_id faltante no afecta el análisis del texto`() {
        // Los formatos y modelos antiguos que no quieran escribir un campo más deben poder enviarse con normalidad.
        val raw = """{"skip": false, "content": "¿Has comido?", "tone": "mencionado casualmente"}"""

        val result = ProactiveReplyParser.parse(raw) as ProactiveReplyParser.Result.Message

        assertNull(result.thoughtId)
        assertEquals("¿Has comido?", result.content)
    }

    @Test
    fun `La cadena nula no se considera válida id`() {
        val raw = """{"skip": false, "content": "Ajá", "thought_id": "null"}"""

        val result = ProactiveReplyParser.parse(raw) as ProactiveReplyParser.Result.Message

        assertNull(result.thoughtId)
    }

    @Test
    fun `skip Ignorar el contenido y thought_id`() {
        val raw = """{"skip": true, "reason": "Nada que recoger", "content": "no debe usarse", "thought_id": "th_1"}"""

        val result = ProactiveReplyParser.parse(raw)

        assertTrue(result is ProactiveReplyParser.Result.Skip)
    }

    @Test
    fun `El texto extralargo se corta dentro del límite superior según los límites de la oración`() {
        val sentence = "Recuerdo muy bien el día en que pasamos por aquella tienda pequeña y dijiste que querías comer sus fideos."
        val longBody = sentence + "continuación".repeat(30)
        val raw = """{"skip": false, "content": "$longBody", "tone": "cuidar"}"""

        val result = ProactiveReplyParser.parse(raw) as ProactiveReplyParser.Result.Message

        assertTrue(
            "El límite superior no debe excederse después del truncamiento:${result.content.length}",
            result.content.length <= ProactiveReplyParser.MAX_CONTENT_CHARS,
        )
        assertTrue("Debe detenerse en oraciones completas:${result.content.takeLast(8)}", result.content.endsWith("."))
        assertTrue("El truncamiento debe conservar el comienzo del texto original.", longBody.startsWith(result.content))
    }

    @Test
    fun `No se ha dicho ni una sola palabra`() {
        val raw = """{"skip": false, "content": "s"}"""

        val result = ProactiveReplyParser.parse(raw)

        assertTrue("No se debe enviar ni una sola palabra de texto.", result is ProactiveReplyParser.Result.Skip)
    }

    @Test
    fun `Sólo la puntuación se considera no dicha`() {
        val raw = """{"skip": false, "content": ". . . !"}"""

        val result = ProactiveReplyParser.parse(raw)

        assertTrue(result is ProactiveReplyParser.Result.Skip)
    }
}
