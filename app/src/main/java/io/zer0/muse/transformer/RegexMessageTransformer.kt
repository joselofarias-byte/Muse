package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.assistant.AssistantRegex

/**
 * v1.97: Reemplazo regular Transformer — Aplique reglas regulares a nivel de asistente al texto del usuario/asistente en la canalización del mensaje.
 *
 * Ver la estructura de reglas [AssistantRegex].
 *
 * comportamiento:
 *  - de context.extra("regex_rules") Obtenga la lista de reglas analizadas previamente
 *    (ChatViewModel Deserializado de AssistantEntity.regexRulesJson)
 *    extra contrato: key="regex_rules",Tipo List<AssistantRegex>,puede ser null
 *  - Aplicar solo visualOnly=false reglas(visualOnly=true Las reglas solo se reemplazan cuando se muestra la interfaz de usuario.,No afecta la entrada de LLM)
 *  - Determinar el alcance de la aplicación según el alcance afectado:
 *    - "user": solo mensajes de usuario
 *    - "assistant": solo mensajes del asistente(El asistente responde en un contexto histórico)
 *    - "both": user + assistant noticias
 *  - SYSTEM Los mensajes no se reemplazan(Evite dañar la estructura de avisos del sistema.)
 *  - Omita una sola regla cuando falle la compilación normal.,No bloquear otras reglas
 *
 * posición en la tubería:después de TemplateTransformer,ThinkTagTransformer antes.
 * De esta manera, las variables de la plantilla se reemplazan primero.,Las reglas regulares entran en vigor según el texto final.
 */
class RegexMessageTransformer : Transformer {

    override val name: String = "Regex"

    override suspend fun transform(messages: List<UIMessage>, context: TransformContext): List<UIMessage> {
        val rules = (context.extra("regex_rules") as? List<*>)?.filterIsInstance<AssistantRegex>()
            ?: return messages
        if (rules.isEmpty()) return messages

        // solo visualOnly=false Las reglas se aplican en el proceso.
        val pipelineRules = rules.filter { it.enabled && !it.visualOnly && it.findRegex.isNotBlank() }
        if (pipelineRules.isEmpty()) return messages

        var changed = false
        val result = messages.map { msg ->
            // SYSTEM Los mensajes no se reemplazan,Evite dañar la estructura de avisos del sistema.
            if (msg.role == MessageRole.SYSTEM) return@map msg

            val scope = when (msg.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                else -> return@map msg
            }
            val newContent = RegexTransformer.applyRules(
                text = msg.content,
                rules = pipelineRules,
                scope = scope,
                visualOnly = false,
            )
            if (newContent != msg.content) {
                changed = true
                msg.copy(content = newContent)
            } else {
                msg
            }
        }
        return if (changed) result else messages
    }
}
