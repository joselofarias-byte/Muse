package io.zer0.ai.core

import io.zer0.common.ErrorCode
import io.zer0.common.Logger
import io.zer0.common.toMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * v1.0.1: Provider Clase sellada de normalización de errores.
 *
 * La cadena de reemplazo contiene coincidencias con clasificación de error de tipo seguro,suministro ChatViewModel / ChatService /
 * Provider Todos los niveles consumen uniformemente.
 *
 * principios de diseño:
 *  - Cada tipo de error lleva la información mínima necesaria.(code / retryAfterSec / cause)
 *  - [isRetryable] Centrarse en la expresión"¿Vale la pena intentarlo de nuevo?"estrategia,La persona que llama ya no razona por sí sola
 *  - [Retry-After] El análisis del encabezado se hunde hasta el tiempo de construcción,En lugar de realizar un análisis repetido para cada persona que llama
 *
 * tipo semántica:
 *  - [Network]: IOException / Tiempo de espera/Desconexión,Se puede volver a intentar
 *  - [RateLimit]: 429 / RESOURCE_EXHAUSTED,Se puede volver a intentar;Prefiero usar el encabezado Retry-After
 *  - [ServerError]: 5xx(Contiene Anthropic 529 overloaded),Se puede volver a intentar
 *  - [AuthError]: 401 / 403,No reintentable(Problemas de credenciales)
 *  - [InvalidRequest]: 400 / 413 / 422 / 404,No reintentable(Hay algún problema con la solicitud en sí.,No tiene sentido volver a intentarlo)
 *  - [Cancelled]: Cancelación de rutina o cancelación de señal de aborto,No reintentable
 *  - [Unknown]: Error no reconocido,No reintentar de forma predeterminada(Evite problemas desconocidos con reintentos infinitos)
 */
sealed class ProviderError {
    /** HTTP código de estado(Si de la respuesta HTTP),null Indica un error no HTTP(Ru Chun IOException). */
    abstract val httpCode: Int?

    /** ¿Vale la pena intentarlo de nuevo?(Depende del tipo específico). */
    abstract val isRetryable: Boolean

    /** Mensaje de error legible por humanos(Contiene clasificación de código de estado + resumen del cuerpo.). */
    abstract val displayMessage: String

    /** originales throwable(Opcional,para iniciar sesión y depurar). */
    open val cause: Throwable? = null

    /** Excepción/tiempo de espera/desconexión de red. */
    data class Network(
        override val displayMessage: String,
        override val cause: Throwable? = null,
    ) : ProviderError() {
        override val httpCode: Int? = null
        override val isRetryable: Boolean = true
    }

    /**
     * Limitación de corriente(429 / RESOURCE_EXHAUSTED).
     *
     * @param retryAfterSec Retry-After El número de segundos analizados desde el encabezado.,null Indica no proporcionado.
     *   Este valor se debe utilizar con preferencia al volver a intentarlo.,El valor predeterminado es el retroceso exponencial.
     */
    data class RateLimit(
        override val httpCode: Int? = 429,
        val retryAfterSec: Int? = null,
        override val displayMessage: String,
        override val cause: Throwable? = null,
    ) : ProviderError() {
        override val isRetryable: Boolean = true
    }

    /** error del servidor(500-599,Contiene Anthropic 529 overloaded). */
    data class ServerError(
        override val httpCode: Int,
        override val displayMessage: String,
        override val cause: Throwable? = null,
    ) : ProviderError() {
        override val isRetryable: Boolean = true
    }

    /** Certificación/error de permiso(401 / 403),No es posible volver a intentarlo. */
    data class AuthError(
        override val httpCode: Int? = null,
        override val displayMessage: String,
        override val cause: Throwable? = null,
    ) : ProviderError() {
        override val isRetryable: Boolean = false
    }

    /** Error de parámetro de solicitud(400 / 422 / 404),No es posible volver a intentarlo. */
    data class InvalidRequest(
        override val httpCode: Int? = null,
        override val displayMessage: String,
        override val cause: Throwable? = null,
    ) : ProviderError() {
        override val isRetryable: Boolean = false
    }

    /** Cancelar(corrutina o AbortSignal),No es posible volver a intentarlo. */
    data class Cancelled(
        override val displayMessage: String = "cancelled",
        override val cause: Throwable? = null,
    ) : ProviderError() {
        override val httpCode: Int? = null
        override val isRetryable: Boolean = false
    }

    /** error no reconocido,De forma predeterminada, no es posible volver a intentarlo. */
    data class Unknown(
        override val httpCode: Int? = null,
        override val displayMessage: String,
        override val cause: Throwable? = null,
    ) : ProviderError() {
        override val isRetryable: Boolean = false
    }

    /**
     * v2.6.8(comentarios de los usuarios): Cuota/Saldo insuficiente (HTTP 402 Códigos de error equivalentes con cada empresa).
     *
     * ¿Por qué no regresar? [InvalidRequest]:402 No tiene nada que ver con los parámetros de la solicitud. El mensaje que ve el usuario es
     * pero lo es“Los parámetros, la longitud del contexto o las restricciones de capacidad del modelo no cumplen con los requisitos”, así que ajusté repetidamente los parámetros y cambié los modelos,
     * Lo que realmente necesitas hacer es recargar. Esta es una guía equivocada que desviará a los usuarios.
     *
     * No reintentable: el saldo no se restablecerá al volver a intentarlo; volver a intentarlo sólo será una pérdida de tiempo.
     */
    data class InsufficientQuota(
        override val httpCode: Int? = 402,
        override val displayMessage: String,
        override val cause: Throwable? = null,
    ) : ProviderError() {
        override val isRetryable: Boolean = false
    }

    companion object {
        private const val TAG = "ProviderError"

        /**
         * v2.6.8: Determinar si el cuerpo del error es“Cuota/saldo insuficiente”.
         *
         * Cada empresa escribe de diferentes maneras. La captura de pantalla del usuario esta vez es en estilo OpenAI:
         *   `You exceeded your current quota ... type: insufficient_quota`
         * También se utiliza como estación de transferencia. `Saldo insuficiente` / `quota exceeded` / `credit` Esperar.
         * Sólo se utiliza para clasificar los errores en [ProviderError.InsufficientQuota], no cambia ningún umbral de política de reintento.
         */
        fun isInsufficientQuotaBody(body: String?): Boolean {
            if (body.isNullOrBlank()) return false
            val lower = body.lowercase()
            return lower.contains("insufficient_quota") ||
                lower.contains("insufficient quota") ||
                lower.contains("exceeded your current quota") ||
                lower.contains("quota exceeded") ||
                lower.contains("billing") ||
                lower.contains("Saldo insuficiente") ||
                lower.contains("Cuota insuficiente") ||
                lower.contains("Saldo agotado")
        }

        /**
         * v2.5.3 (P2-3): Determine si los detalles de un error son de clase 400 "Bloque de pensamiento vinculado a una sesión diferente".
         *
         * Claude La firma del bloque de pensamiento vincula el prefijo de sesión; después de que el usuario edita el historial,“Tome viejos bloques de pensamiento + nuevo contexto”
         * Enviarlo al upstream devolverá 400 (copia típica:“Thinking block is bound to a different conversation”,
         * “Invalid signature”,“signature ... not valid” etcétera). Este tipo de error se cura solo: elimina el bloque de pensamiento correspondiente y vuelve a intentarlo.
         *
         * Solo se coincide con este tipo de diagnóstico y se generarán otros 400 (parámetros incorrectos, autenticación, etc.) como de costumbre.
         */
        fun isThinkingBlockBondError(body: String?): Boolean {
            if (body.isNullOrBlank()) return false
            val lower = body.lowercase()
            val mentionsThinkingOrSignature =
                lower.contains("thinking") || lower.contains("signature") || lower.contains("thought")
            val isBondError =
                lower.contains("bound to a different conversation") ||
                    lower.contains("different conversation") ||
                    lower.contains("invalid signature") ||
                    lower.contains("signature is invalid") ||
                    lower.contains("signature not valid") ||
                    lower.contains("thought_signature") ||
                    (lower.contains("thinking block") && lower.contains("invalid"))
            return mentionsThinkingOrSignature && isBondError
        }

        /**
         * Normalizado desde código de estado HTTP + cuerpo + arrojable a [ProviderError].
         *
         * prioridad:
         *  1. throwable si IOException → [Network]
         *  2. code == 401 / 403 → [AuthError]
         *  3. code == 400 / 413 / 422 / 404 → [InvalidRequest]
         *  4. code == 429 → [RateLimit](Analizando el encabezado Reintentar después)
         *  5. code in 500..599 → [ServerError]
         *  6. code == null y throwable != null → [Network] o [Unknown]
         *  7. Otros → [Unknown]
         *
         * @param code HTTP código de estado,null Indica un error no HTTP
         * @param body respuesta de error body(truncado)
         * @param retryAfterSec Retry-After primeros segundos(Pasado después de ser analizado por la persona que llama,Evita este tipo de dependencia OkHttp)
         * @param throwable excepción original
         */
        fun from(code: Int?, body: String, retryAfterSec: Int? = null, throwable: Throwable? = null): ProviderError = when {
            // IOException priorizar a Network(Incluso si el código no es null,Si se interrumpe SSE IOException).
            throwable is IOException ->
                Network(
                    displayMessage = safeErrorDetail(throwable.message.orEmpty()) ?: "network error",
                    cause = throwable,
                )
            code != null -> fromHttpStatus(code, body, retryAfterSec)
            throwable != null ->
                Unknown(
                    displayMessage = safeErrorDetail(throwable.message.orEmpty()) ?: "unknown error",
                    cause = throwable,
                )
            else -> Unknown(displayMessage = body.ifBlank { "unknown error" })
        }

        private fun fromHttpStatus(code: Int, body: String, retryAfterSec: Int?): ProviderError = when {
            // v2.6.8: Primero se debe juzgar el saldo insuficiente.
            // Motivo: algunas estaciones de transferencia devuelven una cuota insuficiente en 403 (la copia real en la captura de pantalla del usuario es
            // “Cuota de usuarios insuficiente, saldo restante: -0.08”), si 401/403 se juzga primero, se clasificará como AuthError.
            // El mensaje se convierte“API Key Inválido”—— Otra guía equivocada que desvía a los usuarios.
            code == 402 || isInsufficientQuotaBody(body) -> InsufficientQuota(
                httpCode = code,
                displayMessage = buildDisplayMessage(code, body, "insufficient quota"),
            )
            code == 401 || code == 403 -> AuthError(
                httpCode = code,
                displayMessage = buildDisplayMessage(code, body, "auth"),
            )
            code == 400 || code == 413 || code == 422 || code == 404 -> InvalidRequest(
                httpCode = code,
                displayMessage = buildDisplayMessage(code, body, "invalid request"),
            )
            code == 429 -> RateLimit(
                httpCode = code,
                retryAfterSec = retryAfterSec,
                displayMessage = buildDisplayMessage(code, body, "rate limited"),
            )
            code in 500..599 -> ServerError(
                httpCode = code,
                displayMessage = buildDisplayMessage(code, body, "server error"),
            )
            else -> Unknown(
                httpCode = code,
                displayMessage = buildDisplayMessage(code, body, null),
            )
        }

        /**
         * Normalizar desde Throwable(Sin escenario de respuesta HTTP,Como pura anomalía de la red.).
         */
        fun from(throwable: Throwable): ProviderError {
            return from(code = null, body = "", throwable = throwable)
        }

        /**
         * Analice el encabezado Reintentar después.
         *
         * RFC 7231 Se permiten dos formatos:
         *  - numeros puros(segundos):"120" → 120
         *  - HTTP-date:"Wed, 21 Oct 2026 07:28:00 GMT" → El número de segundos desde este momento.
         *
         * El formato de fecha se calcula redondeando hacia arriba para evitar errores de cálculo al esperar menos de un segundo como vencido; se devuelven fechas pasadas
         * 0, indicando que el servidor permite un reintento inmediato. Si el análisis falla, se devolverá nulo, lo que permitirá a la persona que llama utilizar un retroceso exponencial.
         */
        fun parseRetryAfter(headerValue: String?): Int? {
            if (headerValue.isNullOrBlank()) return null
            val value = headerValue.trim()
            value.toLongOrNull()?.let { seconds ->
                return seconds
                    .takeIf { it >= 0L && it <= Int.MAX_VALUE.toLong() }
                    ?.toInt()
            }
            val target = runCatching {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            }.getOrNull() ?: return null
            val delayMs = target.toEpochMilli() - System.currentTimeMillis()
            if (delayMs <= 0L) return 0
            val seconds = (delayMs + 999L) / 1000L
            return seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        private fun buildDisplayMessage(code: Int, body: String, category: String?): String {
            return buildString {
                append("HTTP ").append(code)
                category?.let { append(" [").append(it).append("]") }
                safeErrorDetail(body)?.let { capped ->
                    append(": ").append(capped)
                }
            }
        }

        /**
         * Keep provider diagnostics useful without copying a response body that may echo
         * prompts, tool arguments, API keys, or redirect parameters.
         */
        internal fun safeErrorDetail(body: String): String? {
            if (body.isBlank()) return null
            val candidate =
                runCatching {
                    val root = Json.parseToJsonElement(body)
                    val error = (root as? JsonObject)?.get("error")
                    val errorObject = error as? JsonObject
                    val message = (errorObject?.get("message") as? JsonPrimitive)?.content
                        ?: (root as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.content }
                    val type = (errorObject?.get("type") as? JsonPrimitive)?.content
                    val code = (errorObject?.get("code") as? JsonPrimitive)?.content
                    listOfNotNull(message, type?.let { "type=$it" }, code?.let { "code=$it" })
                        .joinToString(" | ")
                        .ifBlank { body }
                }.getOrDefault(body)
            val redacted = redactSecrets(candidate)
            return redacted.take(200).ifBlank { null }
        }

        private fun redactSecrets(text: String): String {
            var value = text
            value = Regex(
                """(?i)(api[\s_-]?key|token|password|secret|authorization|cookie)\s*[:=]?\s*["']?[A-Za-z0-9._~+/=-]{6,}""",
            ).replace(value) { "${it.groupValues[1]}=[REDACTED]" }
            value = Regex("""(?i)\b(?:sk|rk|pk|ak)-[A-Za-z0-9][A-Za-z0-9._-]{5,}\b""")
                .replace(value, "[REDACTED]")
            value = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{8,}\b""")
                .replace(value, "Bearer [REDACTED]")
            return value
        }
    }
}

/**
 * v1.0.1: Provider Clase base de excepción,llevar normalizado [ProviderError].
 *
 * Cada proveedor genera una subclase de esta excepción cuando falla el análisis o los errores HTTP.,Se puede obtener directamente después de la captura en la capa superior.
 * [providerError] Ramificación de tipo de campo,No se requiere coincidencia de cadenas.
 *
 * Nota:Esta excepción sólo se utiliza para"Necesidad de pasar información de error hacia arriba."escena(Como por ejemplo completeText).
 * Error en la transmisión de streamChat vía [ChatStreamEvent.Error] pasar,también debe llenarse
 * [ChatStreamEvent.Error.providerError] Los campos son utilizados por los consumidores.
 */
open class ProviderException(
    val providerError: ProviderError,
    message: String = providerError.displayMessage,
    cause: Throwable? = providerError.cause,
) : RuntimeException(message, cause) {
    val httpCode: Int? get() = providerError.httpCode
    val isRetryable: Boolean get() = providerError.isRetryable
}

/**
 * v1.0.1: ChatStreamEvent.Error campos de extensión,llevar normalizado [ProviderError].
 *
 * Para código existente [ChatStreamEvent.Error.message] + [ChatStreamEvent.Error.throwable] error de entrega,
 * La capa superior usa cadenas que contienen para determinar el tipo.,Vulnerable. Nuevo [providerError] después del campo,La capa superior puede directamente
 * `event.providerError?.let { it is ProviderError.RateLimit }` Escriba ramificación.
 *
 * compatible con versiones anteriores:[providerError] es un campo opcional,Predeterminado null;El comportamiento no cambia cuando no se pasa la persona que llama existente.
 */
val ChatStreamEvent.Error.providerError: ProviderError?
    get() {
        // Extracto del arrojable primero.(si es arrojable ProviderException)
        val pe = (throwable as? ProviderException)?.providerError
        if (pe != null) return pe
        // Ten todo en cuenta:Inferido de la cadena del mensaje(Compatible con escenarios donde el antiguo proveedor no lanza ProviderException)
        return runCatching {
            inferFromMessage(message, throwable)
        }.getOrElse {
            Logger.w("ProviderError", "inferFromMessage failed: ${it.message}")
            null
        }
    }

/**
 * v1.0.1: Inferido de la cadena del mensaje de error [ProviderError](camino sucio).
 *
 * Sólo si el Proveedor no es lanzado [ProviderException] usado cuando,Analiza las palabras clave en el mensaje.
 * Mantener la compatibilidad con versiones anteriores,El nuevo código debe construirse directamente [ProviderException].
 *
 * v1.0.1 (P4): Cambiar a public,Para uso de ChatViewModel.classifyErrorType,
 * Reemplaza la cadena original con una coincidencia que contiene.
 *
 * v1.0.27 Phase 5-A: Ahora en desuso,Provider Debe ser arrojado directamente [ProviderException] dejar escribir ruta
 * `(throwable as? ProviderException)?.providerError` Tener un efecto. Reservado como alternativa para la compatibilidad con versiones anteriores,
 * Para uso de proveedores que no han sido migrados.
 */
@Deprecated(
    "Provider Debe ser arrojado directamente ProviderException,usar (throwable as? ProviderException)?.providerError Inferencia de cadenas alternativa",
    ReplaceWith("(throwable as? ProviderException)?.providerError"),
)
fun inferFromMessage(message: String, throwable: Throwable?): ProviderError? {
    if (message.isBlank() && throwable == null) return null
    val safeMessage = ProviderError.safeErrorDetail(message) ?: message.take(200)
    val msg = safeMessage.lowercase()
    return when {
        throwable is IOException ||
            msg.contains("timeout") || msg.contains("network") || msg.contains("red") ->
            ProviderError.Network(safeMessage, throwable)
        msg.contains("401") || msg.contains("403") ||
            msg.contains("api key") || msg.contains("unauthorized") ->
            ProviderError.AuthError(displayMessage = safeMessage, cause = throwable)
        msg.contains("429") || msg.contains("rate limit") ->
            ProviderError.RateLimit(displayMessage = safeMessage, cause = throwable)
        msg.contains("500") || msg.contains("502") || msg.contains("503") ||
            msg.contains("504") || msg.contains("529") || msg.contains("overloaded") ->
            ProviderError.ServerError(httpCode = 529, displayMessage = safeMessage, cause = throwable)
        msg.contains("400") || msg.contains("422") || msg.contains("404") ->
            ProviderError.InvalidRequest(displayMessage = safeMessage, cause = throwable)
        else -> null // No se infiere fuertemente que Unknown,Deje que la capa superior conserve el comportamiento original.
    }
}

/**
 * v1.0.27 Phase 5-A: poner [ErrorCode] asignado a escrito [ProviderError].
 *
 * para activación [ProviderException] escriba la ruta — Provider tirar [ProviderException] después,
 * Para uso del consumidor `(throwable as? ProviderException)?.providerError` Puede obtener errores tipográficos graves,
 * ya no es necesario [inferFromMessage] Análisis de cadenas.
 *
 * [displayMessage] Mantener original [ErrorCode.toMessage] cuerda,compatible con versiones anteriores —
 * Incluso si el consumidor sigue tomando la ruta inferFromMessage,También se puede analizar el tipo correspondiente.
 *
 * Estrategia de mapeo (Basado en la semántica de ErrorCode):
 *  - Solicitar/Error de parámetro → [ProviderError.InvalidRequest] (No reintentable)
 *  - Certificación/Permisos → [ProviderError.AuthError] (No reintentable)
 *  - Limitación de corriente/recursos agotados → [ProviderError.RateLimit] (Se puede volver a intentar)
 *  - error del servidor → [ProviderError.ServerError] (Se puede volver a intentar)
 *  - red/tiempo de espera → [ProviderError.Network] (Se puede volver a intentar)
 *  - Otros → [ProviderError.Unknown] (No reintentable)
 */
fun ErrorCode.toProviderError(vararg args: Any?): ProviderError {
    val message = toMessage(*args)
    return when (this) {
        // Solicitar/Error de parámetro (No reintentable)
        ErrorCode.NO_PROVIDER_CONFIGURED,
        ErrorCode.NO_MODEL_SELECTED,
        ErrorCode.INVALID_RESPONSE,
        ErrorCode.INVALID_ARGUMENT,
        ErrorCode.NOT_FOUND,
        ErrorCode.IMAGE_EMPTY_RESPONSE,
        ErrorCode.IMAGE_NO_RESULTS,
        ErrorCode.IMAGE_UNSUPPORTED_MODEL,
        ErrorCode.IMAGE_INVALID_URI,
        ErrorCode.VERTEX_AI_CONFIG_INVALID,
        ErrorCode.MEMORY_CONFIG_INVALID,
        ErrorCode.MEMORY_TOKEN_BUDGET_INVALID,
        -> ProviderError.InvalidRequest(displayMessage = message)

        // Error de autenticación (No reintentable)
        ErrorCode.AUTH_FAILED,
        ErrorCode.IMAGE_API_KEY_MISSING,
        ErrorCode.VERTEX_AI_TOKEN_FAILED,
        -> ProviderError.AuthError(displayMessage = message)

        // Limitación de corriente/recursos agotados (Se puede volver a intentar)
        ErrorCode.RATE_LIMITED,
        ErrorCode.RESOURCE_EXHAUSTED,
        ErrorCode.IMAGE_RESPONSE_TOO_LARGE,
        ErrorCode.IMAGE_REFERENCE_TOO_LARGE,
        -> ProviderError.RateLimit(displayMessage = message)

        // error del servidor (Se puede volver a intentar)
        ErrorCode.SERVICE_UNAVAILABLE,
        ErrorCode.OVERLOADED,
        ErrorCode.API_ERROR,
        -> ProviderError.ServerError(httpCode = 500, displayMessage = message)

        // red/tiempo de espera (Se puede volver a intentar)
        ErrorCode.REQUEST_TIMEOUT,
        ErrorCode.STREAM_INTERRUPTED,
        ErrorCode.NETWORK_ERROR,
        ErrorCode.IMAGE_REFERENCE_DOWNLOAD_FAILED,
        -> ProviderError.Network(displayMessage = message)

        // Permisos (No reintentable,atribuido a AuthError)
        ErrorCode.PERMISSION_DENIED,
        ErrorCode.PRECONDITION_FAILED,
        -> ProviderError.AuthError(displayMessage = message)

        // Error al generar la imagen (Otros,No reintentable)
        ErrorCode.IMAGE_GEN_FAILED -> ProviderError.Unknown(displayMessage = message)
    }
}

/**
 * v1.0.27 Phase 5-A: Estructura conveniente [ProviderException].
 *
 * equivalente a `ProviderException(errorCode.toProviderError(*args))`,
 * Hacer que el punto de lanzamiento del proveedor sea más conciso:
 *
 * ```kotlin
 * // viejo:
 * throw RuntimeException(ErrorCode.INVALID_RESPONSE.toMessage("empty_body", resp.code))
 * // nuevo:
 * throw errorCode.toProviderException("empty_body", resp.code)
 * ```
 */
fun ErrorCode.toProviderException(vararg args: Any?): ProviderException = ProviderException(toProviderError(*args))
