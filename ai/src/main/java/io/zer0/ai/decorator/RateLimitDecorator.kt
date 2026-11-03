package io.zer0.ai.decorator

import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatRequest
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.Provider
import io.zer0.ai.util.SlidingWindowRateLimiter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * P1-3: Decorador limitante actual.
 *
 * en [Provider] Dos controles frontales se superponen a la capa exterior.(Válido hasta que se emita la solicitud):
 *  1. **RPM Limitación de corriente**([SlidingWindowRateLimiter])—— La ventana deslizante controla el número máximo de solicitudes por minuto,
 *     Suspender y esperar vacante cuando se supere el límite,En lugar de rechazarlo directamente. Rutas de diálogo fáciles de usar(Esperar unos segundos más es mejor que informar un error).
 *  2. **control de concurrencia**([Semaphore])—— Limitar el número de solicitudes en tránsito al mismo tiempo,Evite la concurrencia masiva en un corto período de tiempo
 *     Destruye el río arriba(Especialmente en escenarios con múltiples grupos de claves, KeyRoulette solo se preocupa por el sondeo de claves y no por la concurrencia total.).
 *
 * Si algún parámetro es 0, significa que la restricción correspondiente no está habilitada.(Saltar la lógica de decoración,comisión directa).
 *
 * con [io.zer0.ai.util.KeyRoulette] relación colaborativa:
 *  - RateLimitDecorator si**prefijo**controlar:Limitación actual + puerta de concurrencia antes de que se emita la solicitud
 *  - KeyRoulette si**trasero**controlar:429 Después de regresar, cambie a la siguiente clave e inténtelo nuevamente.
 *  - Superposición de los dos:Pase primero por la puerta limitadora de corriente. → Seleccionar clave para enviar solicitud → 429 Reintento de tecla de corte de tiempo
 *
 * semántica de transmisión:[streamChat] El flujo devuelto está en**Al recolectar**(en lugar de durante la construcción)Obtenga una licencia con limitación de corriente,
 * y posee una licencia simultánea durante todo el período de recopilación de transmisión;Finaliza la colección/Liberado tras cancelación.
 * Esto garantiza que el recuento de simultaneidad refleje los flujos reales en vuelo en lugar de"Colección iniciada pero no iniciada"pedido.
 *
 * @param delegate realidad decorada Provider(OpenAI/Anthropic/Gemini)
 * @param requestLimitPerMinute Número máximo de solicitudes por minuto,0 Indica que no hay límite
 * @param maxConcurrentRequests Número máximo de solicitudes simultáneas en vuelo,0 Indica que no hay límite
 */
class RateLimitDecorator(
    private val delegate: Provider,
    requestLimitPerMinute: Int,
    maxConcurrentRequests: Int,
) : Provider by delegate {

    private val limiter: SlidingWindowRateLimiter? =
        if (requestLimitPerMinute > 0) SlidingWindowRateLimiter(requestLimitPerMinute) else null

    private val semaphore: Semaphore? =
        if (maxConcurrentRequests > 0) Semaphore(maxConcurrentRequests) else null

    override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> = flow {
        // Limitación de corriente:Obtenga la licencia RPM cuando la transmisión comience a recopilarse(Suspendido esperando espacio)
        limiter?.acquire()
        // concurrencia:Licencia mantenida durante todo el período de recopilación de transmisión,fin/liberado al cancelar
        if (semaphore != null) {
            semaphore.acquire()
            try {
                delegate.streamChat(request).collect { emit(it) }
            } finally {
                semaphore.release()
            }
        } else {
            delegate.streamChat(request).collect { emit(it) }
        }
    }

    override suspend fun completeText(request: ChatRequest): ChatCompletion {
        limiter?.acquire()
        return if (semaphore != null) {
            semaphore.withPermit { delegate.completeText(request) }
        } else {
            delegate.completeText(request)
        }
    }
}
