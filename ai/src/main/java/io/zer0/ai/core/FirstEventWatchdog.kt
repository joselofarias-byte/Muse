package io.zer0.ai.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R-AI-04: razonamiento profundo/El modelo de contexto muy largo relaja el tiempo de espera del primer evento,El modelo normal sigue siendo el predeterminado.
 *
 * v2.x: Predeterminado 15s→45s, razonamiento/Archivo de contexto muy largo 60s→90s.15s hacer cola lenta/El modelo de arranque en frío es demasiado corto —
 * Si el primer token es un poco lento, se considerará erróneamente como una transmisión inactiva, la transmisión se cancelará y se reenviará la solicitud de no transmisión.:Los usuarios se ven obligados a esperar el doble de tiempo,
 * y responder con“Toda la sección aparece a la vez.”aparecer de una manera(Comentarios de los usuarios).
 */
internal fun Model.firstEventTimeoutMs(defaultMs: Long = 45_000L): Long =
    if (supportsReasoning() || (contextWindow ?: 0) > 200_000) 90_000L else defaultMs

/**
 * B3-01: Vigilancia del primer evento.
 *
 * SSE Una vez establecida la conexión, si [timeoutMs] No se recibieron eventos válidos dentro,Cancelar automáticamente el flujo aguas arriba,
 * Cambiar a no transmisión [fallback] Inténtalo de nuevo,y lo envía a la UI [ChatStreamEvent.FallbackNotice].
 *
 * Revisión de corrección (2.0 B-25): callbackFlow → channelFlow — callbackFlow Capacidad del canal interno
 * Fijo 64,Producción rápida/consumo lento(Como la interfaz de usuario que limita el corte adaptativo de 50 ms)Cuando trySend devuelve falso, la película se pierde silenciosamente.,
 * y aguas abajo `.buffer(UNLIMITED)` no puede cambiar su capacidad interna;channelFlow El canal interno es siempre
 * UNLIMITED,El mismo esquema que la corrección C-35 de OpenAIProvider.
 */
fun Flow<ChatStreamEvent>.withFirstEventWatchdog(
    timeoutMs: Long = 15_000L,
    fallback: suspend () -> ChatCompletion,
    // Reparación de auditoría (7.8): Cheque de cancelación opcional — La persona que llama pasa"Si el usuario se ha detenido"juicio,
    // fallback Comprobar antes de disparar,Evite que el usuario siga enviando una solicitud de facturación después de detenerse.
    abortCheck: () -> Boolean = { false },
): Flow<ChatStreamEvent> = channelFlow {
    val firstEventReceived = AtomicBoolean(false)
    val finished = AtomicBoolean(false)
    val fallbackStarted = AtomicBoolean(false)
    val meaningfulEventReceived = AtomicBoolean(false)
    lateinit var upstreamJob: Job

    suspend fun emitFallback(notice: String, cancelUpstream: Boolean) {
        if (abortCheck() || !fallbackStarted.compareAndSet(false, true)) return
        finished.set(true)
        if (cancelUpstream) upstreamJob.cancel()
        trySend(ChatStreamEvent.FallbackNotice(notice))
        try {
            val completion = fallback()
            completion.reasoningContent?.takeIf { it.isNotBlank() }?.let {
                trySend(ChatStreamEvent.ReasoningDelta(it))
            }
            // v1.0.90 (S-6): Cuando se ha recibido contenido en streaming,Ya no se adjunta el texto completo de la reversión. —
            // Anteriormente incondicional trySend(ContentDelta(completion.text)),Si la transmisión ha generado parte del contenido
            // (Modelos como el GLM-4-9B tienen un comportamiento de transmisión anormal,Genera el fragmento primero),El texto completo está empalmado con,
            // El mensaje se convierte"fragmento + respuesta completa"(Gran superposición en los comentarios de los usuarios).
            // En este momento solo se conservará el contenido mostrado.,Enviar mensaje de aviso de reserva(UI Puede solicitar al usuario que vuelva a intentarlo.).
            if (completion.text.isNotBlank() && !meaningfulEventReceived.get()) {
                trySend(ChatStreamEvent.ContentDelta(completion.text))
            } else if (completion.text.isNotBlank()) {
                trySend(
                    ChatStreamEvent.Error(
                        "Transmisión de contenido de salida pero no completado,El contenido actual se ha conservado.(Generación reintentable)",
                        null,
                    ),
                )
            }
            if (completion.citationUrls.isNotEmpty()) {
                trySend(ChatStreamEvent.CitationDelta(completion.citationUrls))
            }
            completion.toolCalls?.forEachIndexed { index, tc ->
                trySend(
                    ChatStreamEvent.ToolCallDelta(
                        index = index,
                        id = tc.id,
                        name = tc.name,
                        argumentsDelta = tc.arguments,
                    ),
                )
            }
            trySend(ChatStreamEvent.Done(completion.finishReason))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            close(cancelled)
            throw cancelled
        } catch (t: Throwable) {
            trySend(ChatStreamEvent.Error(t.message ?: "Falló el respaldo sin transmisión", t))
        } finally {
            close()
        }
    }

    upstreamJob = launch {
        try {
            collect { event ->
                if (finished.get()) return@collect
                if (!firstEventReceived.get() &&
                    event !is ChatStreamEvent.Error &&
                    event !is ChatStreamEvent.StreamInterrupted
                ) {
                    firstEventReceived.set(true)
                }
                when (event) {
                    is ChatStreamEvent.ContentDelta -> {
                        if (event.delta.isNotEmpty()) meaningfulEventReceived.set(true)
                        trySend(event)
                    }
                    is ChatStreamEvent.ReasoningDelta -> {
                        if (event.delta.isNotEmpty()) meaningfulEventReceived.set(true)
                        trySend(event)
                    }
                    is ChatStreamEvent.ImageDelta,
                    is ChatStreamEvent.ToolCallDelta,
                    is ChatStreamEvent.CitationDelta,
                    -> {
                        meaningfulEventReceived.set(true)
                        trySend(event)
                    }
                    is ChatStreamEvent.Done -> {
                        // Algunos relés compatibles con OpenAI solo devolverán un Listo vacío después de configurar SSE.
                        // Esta no es una respuesta normal que se pueda mostrar.,Realizar inmediatamente una solicitud sin transmisión,Evite que los usuarios vean mensajes vacíos y esperen largos periodos de tiempo.
                        if (!meaningfulEventReceived.get()) {
                            emitFallback("La respuesta de transmisión está vacía y se está cambiando el método de solicitud.", cancelUpstream = false)
                        } else {
                            trySend(event)
                            finished.set(true)
                            close()
                        }
                    }
                    is ChatStreamEvent.Error,
                    is ChatStreamEvent.StreamInterrupted,
                    is ChatStreamEvent.FallbackNotice,
                    is ChatStreamEvent.UsageDelta,
                    -> {
                        trySend(event)
                        if (event is ChatStreamEvent.Error ||
                            event is ChatStreamEvent.StreamInterrupted
                        ) {
                            finished.set(true)
                            close()
                        }
                    }
                }
            }
            // flujo aguas arriba“Termina sin ningún evento.”(como transmisión vacía/desconexión SSE inmediatamente) Se ha confirmado que no se generará más contenido,
            // El primer supervisor de eventos no debería seguir esperando hasta los 45/90. Reutilice inmediatamente las alternativas que no son de transmisión para evitar que los usuarios vean por mucho tiempo
            // loading;SSE que permanece abierto no irá aquí mientras el usuario se detenga/Una ruta de error explícita primero establecerá el valor finalizado en verdadero.
            if (!meaningfulEventReceived.get() && !finished.get()) {
                emitFallback("La respuesta de transmisión está vacía y se está cambiando el método de solicitud.", cancelUpstream = false)
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                // Cancelar/abortar: marque el final y propague hacia arriba, no considere la cancelación como una transmisión fallida,
                // También evita que el organismo de control continúe activando el respaldo después de la cancelación.
                finished.set(true)
                throw t
            }
            if (!finished.get()) {
                finished.set(true)
                trySend(ChatStreamEvent.Error(t.message ?: "stream failed", t))
                close()
            }
        }
    }

    val watchdogJob = launch {
        delay(timeoutMs)
        if (!firstEventReceived.get() && !finished.get()) {
            // Reparación de auditoría (7.8): No más mensajes cuando el usuario se ha detenido fallback,Guarde una solicitud de facturación.
            emitFallback("La red es lenta, el método de solicitud ha sido cambiado.", cancelUpstream = true)
        }
    }

    awaitClose {
        upstreamJob.cancel()
        watchdogJob.cancel()
    }
}
