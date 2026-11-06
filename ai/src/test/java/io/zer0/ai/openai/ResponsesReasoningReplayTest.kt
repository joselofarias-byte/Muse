package io.zer0.ai.openai

import io.zer0.common.AppJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B5-03: OpenAI Responses reasoning Reproducir pruebas de contrato de datos.
 *
 * Bloquear dos estructuras clave,Prevenir la regresión:
 *  1. Solicitar `include` campo — Sin él, el servidor no volverá. encrypted_content,El enlace de reproducción no se puede activar
 *     (2026-09 Conclusión de la investigación:Anteriormente, la falta de este campo provocaba que toda la reproducción quedara inactiva.);
 *  2. Reproducir la forma del elemento de razonamiento. — type/id/encrypted_content/summary(Matriz vacía requerida).
 */
class ResponsesReasoningReplayTest {

    @Test
    fun `request includes reasoning encrypted content opt-in`() {
        val request = ResponsesRequest(
            model = "gpt-5",
            input = emptyList(),
            include = listOf("reasoning.encrypted_content"),
        )
        val root = AppJson.parseToJsonElement(AppJson.encodeToString(request)).jsonObject
        val include = root["include"]!!.jsonArray
        assertEquals(1, include.size)
        assertEquals("reasoning.encrypted_content", include[0].toString().trim('"'))
    }

    @Test
    fun `replay reasoning item carries required shape`() {
        val item = ResponsesInputItem(
            type = "reasoning",
            id = "rs_abc",
            encrypted_content = "enc_payload",
            summary = buildJsonArray { },
        )
        val root = AppJson.parseToJsonElement(AppJson.encodeToString(item)).jsonObject
        assertEquals("reasoning", root["type"]!!.toString().trim('"'))
        assertEquals("rs_abc", root["id"]!!.toString().trim('"'))
        assertEquals("enc_payload", root["encrypted_content"]!!.toString().trim('"'))
        assertTrue(root["summary"] != null && root["summary"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `request without include leaves nothing to serialize for replay`() {
        // Sin inclusión, contenido_encriptado siempre es null(Comportamiento del servidor) — Sólo cierra aquí
        // DTO Las capas no colapsan con los campos predeterminados,Compatible con personas mayores que llaman.
        val request = ResponsesRequest(
            model = "gpt-5",
            input = emptyList(),
        )
        val json = AppJson.encodeToString(request)
        assertTrue(json.isNotBlank())
    }
}
