package io.zer0.muse.util

import java.io.IOException
import kotlin.random.Random

/**
 * v1.80 (L-NR1): Límite superior de retroceso(milisegundos),Evite que los retrasos se desborden. Largo alcance cuando el factor es alto/varios reintentos.
 * 30 Tapado en segundos —— La retirada que supera este valor no tiene sentido para la experiencia del usuario,Bien podría fallar rápidamente.
 */
private const val MAX_DELAY_MS = 30_000L

/**
 * v0.53: Reintento de solicitud de red(Tolerancia a fallos a nivel empresarial).
 *
 * Realice reintentos de retroceso exponencial para operaciones de E/S que puedan fallar debido a fluctuaciones transitorias de la red:
 *  - capturar solo [IOException](error de capa de red),No volver a intentarlo si se produce una excepción comercial
 *  - retroceso exponencial:initialDelayMs * factor^attempt,evitar avalancha
 *  - Excepción sin formato lanzada en el último error,Mantener la semántica de la persona que llama
 *
 * Uso:
 * ```
 * val resp = retryOnNetworkError(maxRetries = 3) {
 *     client.newCall(req).execute()
 * }
 * ```
 *
 * @param maxRetries Número máximo de intentos(incluyendo la primera vez,Eso es maxRetries=3 Indica que se pueden enviar un máximo de 3 solicitudes)
 * @param initialDelayMs ¿Cuánto tiempo esperar después del primer fracaso?(milisegundos)
 * @param factor factor de retroceso(Intervalo entre reintentos = último intervalo * factor)
 * @param block La implementación real de la red./IO Operación
 * @return block valor de retorno
 * @throws IOException Lanzado cuando todos los reintentos fallan IOException
 */
suspend fun <T> retryOnNetworkError(maxRetries: Int = 3, initialDelayMs: Long = 500, factor: Double = 2.0, block: suspend () -> T): T {
    require(maxRetries >= 1) { "maxRetries must be >= 1" }
    var lastError: IOException? = null
    var delayMs = initialDelayMs
    repeat(maxRetries) { attempt ->
        try {
            return block()
        } catch (e: IOException) {
            lastError = e
            // No esperes a la última vez
            if (attempt < maxRetries - 1) {
                // M4: Agregar fluctuación aleatoria(0-30%),Evite el atronador efecto rebaño causado por varios clientes que reintentan al mismo tiempo
                val jitter = (Random.nextFloat() * 0.3 * delayMs).toLong()
                kotlinx.coroutines.delay(delayMs + jitter)
                // v1.80 (L-NR1): Agregar límite de coerceAtMost,Evite que los retrasos desborden el rango largo cuando el factor es alto/varios reintentos
                delayMs = (delayMs * factor).toLong().coerceAtMost(MAX_DELAY_MS)
            }
        }
    }
    throw lastError ?: IOException("retryOnNetworkError: all retries exhausted")
}
