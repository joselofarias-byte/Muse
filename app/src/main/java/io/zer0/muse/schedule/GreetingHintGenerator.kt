package io.zer0.muse.schedule

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactEntity
import io.zer0.muse.ui.GreetingHelper
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Generador de recordatorios personalizados de saludo — Deje que LLM organice una copia de recordatorio basada en eventos recientes en la memoria.
 *
 * En comparación con las plantillas de reglas codificadas,LLM La copia generada es más natural, más diversa y no repite palabras.
 * (Como por ejemplo"hay un examen mañana:Vas a asistir mañana..."Este tipo de expresión larga).
 *
 * proceso:
 *  1. [GreetingHelper.recentEvents] Seleccionar candidatos para eventos recientes(Los próximos 1 a 3 días,Visitas de palabras clave)
 *  2. LLM Generar un breve recordatorio(≤ 18 palabra,segunda persona,lenguaje hablado natural)
 *  3. falló/Versión de regla alternativa de tiempo de espera [GreetingHelper.getMemoryHint],No bloquear UI
 *
 * La persona que llama es responsable del almacenamiento en caché.(Generar como máximo una vez al día,ver ChatListScreen).
 */
class GreetingHintGenerator(
    private val chatService: ChatService,
) {

    private val tag = "GreetingHintGenerator"

    /** LLM Generar tiempo de espera(UI La escena no puede esperar mucho,Reversión rápida en caso de fallo). */
    private val LLM_TIMEOUT_MS = 8000L

    /** Longitud máxima de los resultados generados(UI presupuesto de una sola línea). */
    private val maxHintLength = GreetingHelper.PERSONALIZED_HINT_MAX_LENGTH

    /**
     * Generar sufijo de saludo. No hay eventos recientes para regresar null;LLM Regrese a la versión de la regla si falla.
     */
    suspend fun generate(facts: List<FactEntity>): String? {
        val events = GreetingHelper.recentEvents(facts)
        if (events.isEmpty()) return null
        val fallback = GreetingHelper.getMemoryHint(facts)
        if (fallback == null) return null

        val llmHint = resultOf {
            withTimeoutOrNull(LLM_TIMEOUT_MS) {
                chatService.completeText(
                    messages = buildPrompt(events),
                    temperature = 0.8f,
                    maxTokens = 32,
                )
            }
        }.onError { msg, t ->
            Logger.w(tag, "Error en la generación de LLM del recordatorio de saludo: ${t?.message ?: msg},Volver a la versión de la regla")
        }.getOrNull()?.text?.let { io.zer0.muse.transformer.stripThinkTags(it) }

        if (llmHint.isNullOrBlank()) {
            Logger.d(tag, "LLM El resultado está vacío.,Usar versión de reglas")
            return fallback
        }
        val compactHint = GreetingHelper.compactGreetingText(llmHint, maxHintLength)
        if (llmHint.length > maxHintLength) {
            Logger.d(tag, "LLM El resultado es demasiado largo.(${llmHint.length}palabra),comprimido a${maxHintLength}palabra")
        }
        return compactHint ?: fallback
    }

    private fun buildPrompt(events: List<String>): List<UIMessage> {
        val system = UIMessage(
            role = MessageRole.SYSTEM,
            content = buildString {
                appendLine("Estás agregando un recordatorio de eventos recientes al saludo del usuario.")
                appendLine("Sólo basado en los asuntos dados.,no especules;Tiempo relativo en reservas,No repitas palabras de tiempo.")
                appendLine("Conserva solo lo más importante,Genera una oración de 8 a 14 palabras.,usar\"tu\"llamar al usuario,lenguaje hablado natural,Sin comillas, prefijos, paráfrasis ni párrafos.")
            },
        )
        val user = UIMessage(
            role = MessageRole.USER,
            content = events.joinToString("\n"),
        )
        return listOf(system, user)
    }
}
