package io.zer0.muse.web.a2a

import io.zer0.common.AppJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * AUD-20261011-A2A-PROTOCOL: JSON-RPC Lógica pura de análisis de sobres y valor de identificación de tarea(Se puede probar individualmente).
 *
 * con [A2aHandler] división del trabajo:Este objeto sólo es responsable de"mensaje → Determinación de la estructura",
 * No toque ChatViewModel/sesión,Facilita el bloqueo de la semántica del protocolo sin Android.
 */
internal object A2aJsonRpc {

    /** El resultado del juicio de estructura de una solicitud. */
    data class Decision(
        val method: String? = null,
        val envelopeId: JsonElement? = null,
        val params: JsonObject? = null,
        val errorCode: Int? = null,
        val errorMessage: String? = null,
    )

    /**
     * Analiza y evalúa solicitudes JSON-RPC.
     *
     * Distinguir entre dos tipos de fallo (JSON-RPC 2.0):
     *  - El mensaje no es legal. JSON → PARSE_ERROR(-32700)
     *  - JSON legal pero falta método (estructura ilegal)→ INVALID_REQUEST(-32600)
     */
    @Suppress("ReturnCount")
    fun classify(raw: String): Decision {
        val parsed = runCatching { AppJson.parseToJsonElement(raw) }.getOrNull()
            ?: return Decision(errorCode = A2aErrors.PARSE_ERROR, errorMessage = "invalid JSON")
        val obj = parsed as? JsonObject
            ?: return Decision(errorCode = A2aErrors.INVALID_REQUEST, errorMessage = "request must be a JSON object")
        val envelopeId = obj["id"] ?: JsonNull
        if ((obj["jsonrpc"] as? JsonPrimitive)?.contentOrNull != "2.0") {
            return Decision(
                envelopeId = envelopeId,
                errorCode = A2aErrors.INVALID_REQUEST,
                errorMessage = "jsonrpc must be 2.0",
            )
        }
        val method = (obj["method"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return Decision(
                envelopeId = envelopeId,
                errorCode = A2aErrors.INVALID_REQUEST,
                errorMessage = "missing method",
            )
        val params = obj["params"] as? JsonObject
        return Decision(method = method, envelopeId = envelopeId, params = params)
    }

    /**
     * Obtener identificación de tarea: priorizar la especificación A2A `params.id`, ID de sobre alternativo (compatible con llamadas antiguas v2.5.8).
     *
     * Antigua implementación de sobres de sólo lectura id —— El cliente que coloca la identificación de la tarea en los parámetros de acuerdo con la especificación nunca podrá recuperar la tarea.
     */
    fun taskIdFrom(params: JsonElement?, envelopeId: JsonElement?): String? {
        val fromParams = (params as? JsonObject)
            ?.get("id")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotBlank() }
        if (fromParams != null) return fromParams
        return (envelopeId as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
