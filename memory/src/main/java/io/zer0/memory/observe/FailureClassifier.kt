package io.zer0.memory.observe

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * D3-P1: Clasificación de fallas en la canalización de memoria.
 *
 * solo responde"¿Quieres volver a intentarlo si esto falla?"esta pregunta,El flujo de control no cambia.
 * Los resultados de la clasificación solo se utilizan para registros de observación.(ver [PipelineLog]);reintento real/La estrategia de recuperación se deja en manos de P3.
 *
 * Semántica de clasificación:
 *  - [RETRYABLE] Fallo transitorio —— Jitter de red, limitación de corriente, servidor 5xx, tiempo de espera,LLM Respuesta vacía y excepción desconocida.
 *  - [PERMANENT] falla determinista —— Errores de configuración, autenticación,4xx, análisis/Error de serialización, el parámetro es ilegal.
 *  - [FATAL]     nivel de proceso/No recuperable —— Cancelación de rutina,OOM,StackOverflow Espere a que aparezcan errores de VM.
 *
 * orden de sentencia:Cancelar/VM error → red/IO Tipo → **Cuota/Estado final del equilibrio → patrón de mensaje(Reintentable tiene prioridad sobre permanente)** → Secretos de tipo de excepción → Reintentable de forma predeterminada.
 * La razón por la que se puede volver a intentar de forma predeterminada:Es mejor intentar la tarea de la memoria una vez más.,No se puede descartar silenciosamente la pena de muerte por razones desconocidas.
 *
 * Cuota/El saldo insuficiente debe preceder a las funciones reintentables:Comúnmente utilizado en aguas arriba"rate limit exceeded: quota"este tipo
 * Expresión de mensaje mezclada con palabras limitantes."el dinero se ha ido",Si lo intentas de nuevo, seguirás recibiendo 402. Esto está relacionado con
 * `ProviderError.InsufficientQuota`(402 / isInsufficientQuotaBody juicio de prioridad)Mismo calibre.
 */
enum class FailureKind {
    RETRYABLE,
    PERMANENT,
    FATAL,
}

object FailureClassifier {

    /** Características del mensaje reintentable(coincidencia de minúsculas). */
    private val RETRYABLE_PATTERNS = listOf(
        "http 429",
        "http 5", // 5xx Error temporal del lado del servidor
        "timeout",
        "timed out",
        "rate limit",
        "too many requests",
        "Limitación de corriente",
        "connection reset",
        "connection refused",
        "broken pipe",
        "unexpected end of stream",
        "eof",
        "temporarily",
        "unavailable",
        "overloaded",
        "respuesta vacía",
        "empty response",
        "failed to connect",
    )

    /** Características del mensaje con error permanente(coincidencia de minúsculas). */
    private val PERMANENT_PATTERNS = listOf(
        "http 400",
        "http 401",
        "http 402",
        "http 403",
        "http 404",
        "http 422",
        "unauthorized",
        "invalid",
        "unsupported",
        "no model",
        "No configurado",
        "no encontrado json",
        "json El análisis falló",
        "parse",
        "El análisis falló",
        "schema",
        "Copia de seguridad y restauración",
    )

    /**
     * Cuota/Saldo/Características del estado final de autenticación(coincidencia de minúsculas).
     *
     * con [PERMANENT_PATTERNS] Mantener por separado:Estas señales representan"No será bueno si lo intentas de nuevo."estado externo
     * (El dinero se acabó / el bono no es válido),debe ser clasificado [RETRYABLE_PATTERNS] Juicio antes,
     * Porque el upstream empaquetará la cuota insuficiente en un mensaje con la redacción del límite actual.
     */
    private val TERMINAL_QUOTA_OR_AUTH_PATTERNS = listOf(
        "http 402",
        "insufficient quota",
        "insufficient_quota",
        "quota exceeded",
        "exceeded your current quota",
        "Saldo insuficiente",
        "Cuota insuficiente",
        "Saldo agotado",
        "http 401",
        "unauthorized",
        "invalid api key",
        "incorrect api key",
        "api key not valid",
    )

    /**
     * Clasificar las excepciones como [FailureKind].
     *
     * @param error excepción atrapada;null Considerado reintentable(Defensivo,Normalmente no debería aparecer)
     */
    fun classify(error: Throwable?): FailureKind {
        if (error == null) return FailureKind.RETRYABLE
        // La cancelación de la rutina es una señal final,No reintentable
        if (error is CancellationException) return FailureKind.FATAL
        // error a nivel de proceso:memoria agotada/Desbordamiento de pila, etc.,No tiene sentido volver a intentarlo
        if (error is VirtualMachineError || error is ThreadDeath) return FailureKind.FATAL
        // red/IO error transitorio
        if (error is SocketTimeoutException || error is IOException) return FailureKind.RETRYABLE

        val msg = (error.message ?: "").lowercase()
        // Cuota/El estado final de autenticación tiene prioridad sobre las funciones reintentables:"rate limit exceeded: quota" Este tipo de noticias son mixtas.
        // palabras limitantes,Pero el verdadero significado es que la cuota se ha agotado.,No tiene sentido volver a intentarlo.
        if (matches(msg, TERMINAL_QUOTA_OR_AUTH_PATTERNS)) return FailureKind.PERMANENT
        if (matches(msg, RETRYABLE_PATTERNS)) return FailureKind.RETRYABLE
        if (matches(msg, PERMANENT_PATTERNS)) return FailureKind.PERMANENT

        // Tipo de cobertura:serialización/analizar/Incluso si la clase de parámetro falla y se vuelve a intentar, no mejorará.
        if (error is SerializationException ||
            error is IllegalArgumentException ||
            error is IllegalStateException
        ) {
            return FailureKind.PERMANENT
        }
        // Excepción desconocida:Se puede volver a intentar la sentencia conservadora
        return FailureKind.RETRYABLE
    }

    /**
     * ¿Es una "cuota"?/Saldo/El estado final de la clase "Autenticación" falló.
     *
     * Corresponde a la persona que llama decidir si desea retrasar el maquillaje.:Upstream ya se está acelerando(como agnes
     * "not asked again before 02:49"),Si lo volvemos a presionar 30 segundos después, continuaremos obteniendo 402.
     * Sólo cubre dos tipos de estados irrecuperables: dinero y vales.,analizar/Si la clase de parámetro falla, aún se permiten ejecuciones de recuperación.(LLM hay aleatoriedad).
     */
    private fun matches(msg: String, patterns: List<String>): Boolean =
        patterns.any { msg.contains(it.lowercase()) }

    fun isTerminalQuotaOrAuth(error: Throwable?): Boolean {
        val msg = (error?.message ?: "").lowercase()
        return msg.isNotBlank() && matches(msg, TERMINAL_QUOTA_OR_AUTH_PATTERNS)
    }
}
