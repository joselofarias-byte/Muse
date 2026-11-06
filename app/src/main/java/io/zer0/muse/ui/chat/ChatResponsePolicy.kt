package io.zer0.muse.ui.chat

import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.toEventSequence

/** Determine si el upstream se niega a utilizar la opción_herramienta requerida en el modo de pensamiento. */
internal fun isThinkingToolChoiceUnsupported(message: String): Boolean {
    val normalized = message.lowercase()
    return normalized.contains("thinking mode") &&
        (normalized.contains("tool_choice") || normalized.contains("tool choice"))
}

/** Solo se permite un reintento de compatibilidad para la opción_herramienta requerida cuando no hay resultados. */
internal fun shouldRetryToolChoiceCompatibility(
    message: String,
    toolChoice: String?,
    retryUsed: Boolean,
    hasMeaningfulOutput: Boolean,
): Boolean = toolChoice == "required" &&
    !retryUsed &&
    !hasMeaningfulOutput &&
    isThinkingToolChoiceUnsupported(message)

/**
 * Convierta una respuesta única en una secuencia de eventos que pueda ser procesada por un consumidor de streaming existente.
 *
 * M2.4: La lógica de conversión se hunde en el módulo ai([ChatCompletion.toEventSequence]),
 * Esta función está reservada como entrada compatible.,Asegúrese de que haya una persona que llama(chat UI/prueba)No se requieren cambios.
 */
internal fun completionToStreamEvents(completion: ChatCompletion): List<ChatStreamEvent> = completion.toEventSequence()
