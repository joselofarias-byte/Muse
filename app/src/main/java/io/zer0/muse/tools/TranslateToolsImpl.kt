package io.zer0.muse.tools

import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ProviderError
import io.zer0.ai.core.ProviderException
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.transformer.stripThinkTags
import io.zer0.muse.ui.translate.TranslateViewModel
import org.koin.core.context.GlobalContext

/**
 * P1-3b Cancelación de registro: implementación de la herramienta de traducción (migrada desde ToolRegistry.kt tal como está).
 * Registrado en ToolRegistry por TranslateToolsRegistrar.
 */
class TranslateToolsImpl {

    suspend fun execTranslate(args: Map<String, String>): String {
        val text = args["text"]?.takeIf { it.isNotBlank() }
            ?: return "Falta el parámetro requerido: text"
        val targetLanguage = args["target_language"]?.takeIf { it.isNotBlank() }
            ?: return "Falta el parámetro requerido: target_language"
        val sourceLanguage = args["source_language"]?.takeIf { it.isNotBlank() }
        val style = args["style"]?.takeIf { it.isNotBlank() } ?: "Universales"

        // construir system prompt(CONS-01: converger a TranslateViewModel.buildTranslationPrompt,Elimina tres series para lograr la deriva.)
        val systemPrompt = TranslateViewModel.buildTranslationPrompt(
            text = text,
            targetLanguage = targetLanguage,
            sourceLanguage = sourceLanguage ?: TranslateViewModel.SOURCE_AUTO,
            style = style,
        )
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = systemPrompt),
            UIMessage(role = MessageRole.USER, content = text),
        )

        // ChatService Consíguelo a través de Koin(Safe Mode Es posible que el siguiente Koin no se inicialice,Tome resultOf para llegar al fondo de las cosas.)
        val koin = resultOf { GlobalContext.get() }.getOrNull()
            ?: return "Servicio de traducción no disponible(Koin no inicializado)"
        val chatService = resultOf { koin.get<ChatService>() }.getOrNull()
            ?: return "Servicio de traducción no disponible(ChatService No registrado)"

        return resultOf {
            // Prioridad completeText(Devolución única,rapido)
            val translated: String = try {
                val completion = chatService.completeText(messages = messages)
                stripThinkTags(completion.text).trim()
            } catch (e: UnsupportedOperationException) {
                // Provider No implementado completeText,degradar la transmisión
                collectTranslateStream(chatService, messages)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                // Reparación de auditoría (3.6): Ya no se baja a transmisión cuando se cancela la rutina,Cancelar la propagación de la señal hacia arriba
                throw e
            } catch (e: Exception) {
                // Otros errores también degradan la transmisión(Fluctuación de la red, etc.)
                Logger.w("ToolRegistry", "translate completeText falló,Rebajar streamChat", e)
                collectTranslateStream(chatService, messages)
            }
            if (translated.isEmpty()) "El resultado de la traducción está vacío.,Por favor verifique el idioma original o de destino." else translated
        }.onError { msg, _ -> Logger.w("ToolRegistry", "translate falló: $msg") }
            .getOrNull() ?: "La traducción falló,Inténtelo de nuevo más tarde."
    }

    /** Colección en streaming de resultados de traducción(completeText Ruta de degradación cuando no esté disponible). */
    /** Colección en streaming de resultados de traducción(completeText Ruta de degradación cuando no esté disponible). */
    suspend fun collectTranslateStream(chatService: ChatService, messages: List<UIMessage>): String {
        val sb = StringBuilder()
        chatService.streamChat(messages = messages).collect { event ->
            when (event) {
                is ChatStreamEvent.ContentDelta -> sb.append(event.delta)
                is ChatStreamEvent.Error -> throw ProviderException(
                    providerError = ProviderError.Unknown(displayMessage = event.message),
                    cause = event.throwable,
                )
                else -> { /* ignorar ReasoningDelta / ToolCallDelta / ImageDelta / Done espera */ }
            }
        }
        return stripThinkTags(sb.toString()).trim()
    }
}
