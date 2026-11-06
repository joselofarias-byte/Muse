package io.zer0.muse.util

import android.content.Context
import io.zer0.muse.R

/**
 * analizar [io.zer0.common.ErrorCode.toMessage] Se generó el mensaje de prefijo ERR_ y se asignó a una cadena localizada.
 * ai/memory Los módulos pasan tipos de error a través de la enumeración ErrorCode,app Aquí es donde la capa analiza y muestra texto legible por el usuario.
 */
object ErrorMessages {

    private val PREFIX_REGEX = Regex("^ERR_([a-z_]+)(?::(.+))?")

    /** Palabras clave de error de red → recursos localizados,Empareja los golpes en orden y regresa.(impulsado por datos,Evitar cuando aparezcan advertencias de complejidad de sucursales). */
    private val NETWORK_ERROR_KEYWORDS: List<Pair<List<String>, Int>> = listOf(
        listOf("unable to resolve", "unknownhost") to R.string.err_chat_network_unresolvable,
        listOf("timeout") to R.string.err_chat_network_timeout,
        listOf("401", "403") to R.string.err_chat_auth_invalid,
        listOf("429") to R.string.err_chat_rate_limited,
        listOf("500", "502", "503") to R.string.err_chat_server_error,
        listOf("stream", "eof") to R.string.err_chat_stream_broken,
    )

    /** Si el mensaje está en formato ERR_, devuelve una cadena localizada,De lo contrario, devuelva el mensaje original. */
    fun resolve(context: Context, message: String): String {
        val m = PREFIX_REGEX.find(message) ?: return message
        val key = m.groupValues[1]
        val args = m.groupValues[2].split(",").filter { it.isNotEmpty() }
        val rest = message.substring(m.range.last + 1).trimStart()
        val resolved = resolveKey(context, key, args)
        // C-13: resolveKey Devuelve nulo si la localización no es posible(Ver abajo),En este punto, el mensaje original se devuelve tal cual. —
        // Todavía contiene el prefijo ERR_ y todos los parámetros.,Asegúrese de que los detalles del error no se pierdan silenciosamente debido a un error de localización.
        if (resolved == null) return message
        return if (rest.isNotEmpty()) "$resolved $rest" else resolved
    }

    /**
     * Clasificación del mensaje original de error de red([resolve] Cuando el prefijo ERR_ no puede coincidir).
     *
     * Las palabras clave de los mensajes de excepción se clasifican en redacción publicitaria localizada. Esta responsabilidad comienza con ChatViewModel(Alrededor de 8287 líneas God class)
     * mantén la boca cerrada,Mantenga la función pura y pueda probarse de forma independiente.
     */
    fun classifyNetworkError(context: Context, throwable: Throwable): String {
        val raw = throwable.message ?: ""
        val resolved = resolve(context, raw)
        if (resolved != raw) return resolved
        val msg = raw.lowercase()
        val match = NETWORK_ERROR_KEYWORDS.firstOrNull { entry -> entry.first.any { msg.contains(it) } }
        return if (match != null) {
            context.getString(match.second)
        } else {
            // issue #3.3: no más take(80) Trunca el error original.
            // La implementación anterior reducía los errores largos a 80 palabras y no tenía entrada de copia.,Los usuarios no tienen forma de comprobarlo.(Los informes de errores de la estación de transferencia suelen tardar mucho tiempo JSON).
            // Se conserva el texto original completo.,Copia proporcionada por UI Side Fold +.
            val detail = throwable.localizedMessage
                ?: throwable::class.simpleName
                ?: context.getString(R.string.err_chat_unknown)
            context.getString(R.string.err_chat_request_failed, detail)
        }
    }

    /**
     * Analiza una cadena localizada para una sola clave.
     *
     * @return resultados localizados;Si la clave no está asignada, regrese "ERR_$key"(Comportamiento existente);
     *         Si getString falla con los parámetros(Indica que falta la traducción local %1$s/%1$d Carácter de formato)Regresar null,
     *         por [resolve] Vuelve al mensaje original.
     */
    private fun resolveKey(context: Context, key: String, args: List<String>): String? {
        val resId = mapping[key] ?: return "ERR_$key"
        return try {
            context.getString(resId, *args.toTypedArray())
        } catch (e: Exception) {
            // C-13: Traducción que no es zh después de eliminar caracteres de formato,GetString con parámetros arroja NotFoundException.
            // La implementación original volvió a no tener parámetros. getString(resId) será descartado en silencio %1$s/%1$d Detalles de los parámetros;
            // Devuelve nulo en su lugar para volver al mensaje original.,Los detalles del error no se pierden.zh La traducción conserva los caracteres de formato.,
            // Esta rama no se activa,zh El comportamiento se mantiene sin cambios.
            null
        }
    }

    private val mapping: Map<String, Int> = mapOf(
        "no_provider_configured" to R.string.err_no_provider_configured,
        "no_model_selected" to R.string.err_no_model_selected,
        "auth_failed" to R.string.err_auth_failed,
        "rate_limited" to R.string.err_rate_limited,
        "request_timeout" to R.string.err_request_timeout,
        "service_unavailable" to R.string.err_service_unavailable,
        "permission_denied" to R.string.err_permission_denied,
        "invalid_response" to R.string.err_invalid_response,
        "api_error" to R.string.err_api_error,
        "resource_exhausted" to R.string.err_resource_exhausted,
        "invalid_argument" to R.string.err_invalid_argument,
        "precondition_failed" to R.string.err_precondition_failed,
        "overloaded" to R.string.err_overloaded,
        "not_found" to R.string.err_not_found,
        "stream_interrupted" to R.string.err_stream_interrupted,
        "network_error" to R.string.err_network_error,
        "image_gen_failed" to R.string.err_image_gen_failed,
        "image_response_too_large" to R.string.err_image_response_too_large,
        "image_empty_response" to R.string.err_image_empty_response,
        "image_no_results" to R.string.err_image_no_results,
        "image_unsupported_model" to R.string.err_image_unsupported_model,
        "image_reference_too_large" to R.string.err_image_reference_too_large,
        "image_reference_download_failed" to R.string.err_image_reference_download_failed,
        "image_invalid_uri" to R.string.err_image_invalid_uri,
        "image_api_key_missing" to R.string.err_image_api_key_missing,
        "vertex_ai_config_invalid" to R.string.err_vertex_ai_config_invalid,
        "vertex_ai_token_failed" to R.string.err_vertex_ai_token_failed,
        "memory_token_budget_invalid" to R.string.err_memory_token_budget_invalid,
        "memory_config_invalid" to R.string.err_memory_config_invalid,
    )
}
