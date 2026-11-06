package io.zer0.muse.web.a2a

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-20261011-A2A-PROTOCOL: JSON-RPC Parámetros de capa/Semántica del código de error.
 *
 * Antecedentes de defectos (.audit REPORT_DOCS_TESTS F2):
 *  - GetTask / CancelTask lo que se lee es JSON-RPC **sobre id**, mientras que la especificación A2A pone task id
 *    ponerse `params.id` —— Un cliente normal nunca podrá recuperar la tarea; la identificación del sobre y la identificación de la tarea son exactamente iguales
 *    solo"Copia del cliente"coincidencia.
 *  - Toda la decodificación falló (incluyendo"JSON válido pero falta method") están asignados a PARSE_ERROR(-32700),
 *    y A2AErrors.INVALID_REQUEST(-32600) Es código muerto y no cumple con JSON-RPC 2.0:
 *    Si el análisis falla, será -32700. Si la estructura de la solicitud es ilegal, será -32600.
 */
class A2aJsonRpcSemanticsTest {

    private val json = Json

    @Test
    fun `task id is read from params not from the envelope id`() {
        val params = json.parseToJsonElement("""{"id":"task-from-params"}""")
        assertEquals(
            "A2A La especificación pone la identificación de la tarea en params.id;Leer la identificación del sobre evitará que los clientes normales recuperen la tarea.",
            "task-from-params",
            A2aJsonRpc.taskIdFrom(params, envelopeId = JsonPrimitive("envelope-id")),
        )
    }

    @Test
    fun `task id falls back to envelope id for legacy clients`() {
        // Compatible con llamadas existentes emitidas por v2.5.8:La identificación del sobre es el uso antiguo de la identificación de la tarea.
        assertEquals(
            "legacy-envelope",
            A2aJsonRpc.taskIdFrom(params = null, envelopeId = JsonPrimitive("legacy-envelope")),
        )
    }

    @Test
    fun `missing method on valid json is invalid request not parse error`() {
        val decision = A2aJsonRpc.classify("""{"jsonrpc":"2.0","id":1}""")
        assertEquals(
            "JSON legal pero falta un método, la estructura de la solicitud es ilegal(-32600),No se ha producido un error al analizar(-32700)",
            A2aErrors.INVALID_REQUEST,
            decision.errorCode,
        )
    }

    @Test
    fun `valid non-object json is invalid request not parse error`() {
        val decision = A2aJsonRpc.classify("[]")
        assertEquals(A2aErrors.INVALID_REQUEST, decision.errorCode)
    }

    @Test
    fun `malformed json is a parse error`() {
        val decision = A2aJsonRpc.classify("""{"jsonrpc":""")
        assertEquals(A2aErrors.PARSE_ERROR, decision.errorCode)
    }

    @Test
    fun `valid send message request is accepted and keeps the envelope id`() {
        val decision = A2aJsonRpc.classify(
            """{"jsonrpc":"2.0","id":7,"method":"message/send","params":{"message":{"parts":[{"kind":"text","text":"hi"}]}}}""",
        )
        assertNull("Las solicitudes legítimas no deberían generar códigos de error", decision.errorCode)
        assertEquals("message/send", decision.method)
        assertEquals(JsonPrimitive(7), decision.envelopeId)
    }

    @Test
    fun `unknown method is rejected with method not found`() {
        val decision = A2aJsonRpc.classify("""{"jsonrpc":"2.0","id":1,"method":"tasks/nope"}""")
        assertEquals(
            "La capa de distribución debe devolver los métodos desconocidos -32601(Aquí sólo se comprueba la estructura.,No debe juzgarse de antemano como un error de análisis.)",
            null,
            decision.errorCode,
        )
        assertTrue(decision.method == "tasks/nope")
    }

    @Test
    fun `empty body is a parse error with null envelope id`() {
        val decision = A2aJsonRpc.classify("")
        assertEquals(A2aErrors.PARSE_ERROR, decision.errorCode)
        assertEquals(JsonNull, decision.envelopeId ?: JsonNull)
    }

    @Test
    fun `blank task id is treated as missing`() {
        val params = buildJsonObject { put("id", "   ") }
        assertNull(A2aJsonRpc.taskIdFrom(params, envelopeId = null))
    }
}
