package io.zer0.muse.tools

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v1.201: LLM Agregador de reseñas completo.
 *
 * Enviar los resultados de varios subagentes a una convocatoria de LLM independiente para una revisión exhaustiva,
 * Producir el resultado final después de la fusión.(Marcar puntos de conflicto/perspectivas complementarias/respuestas integrales).
 *
 * - Inyectar a través de Koin [ChatService] con [SettingsRepository]
 * - El modelo de revisión está tomado de [SettingsRepository.multiAgentConfigCache] de reviewModelId,
 *   Si no está configurado, se utiliza el modelo predeterminado de proveedor activo.
 * - temperature=0.3(La revisión debe ser estable.),maxTokens=2000
 * - tiempo de espera de llamada 30s,Devuelve nulo en el tiempo de espera para permitir que el flujo ascendente baje a EXPERT_REVIEW
 * - usar [resultOf]{} paquete,Lanzamiento correcto CancellationException
 *
 * Esta clase es apátrida @Single,puede ser [TeamWorkflowExecutor] / [SkillExecutor] Compartir.
 */
class LlmAggregator(
    private val chatService: ChatService,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * Revisión exhaustiva de los resultados de los candidatos de múltiples agentes,Devuelve el texto combinado.
     *
     * @param candidates Lista de resultados de candidatos(Contenido vacío filtrado,La persona que llama es responsable.)
     * @param question tarea original/pregunta,como contexto de revisión;null considerado como "No proporcionado"
     * @return El texto de salida final después de la fusión.;Llamada fallida/tiempo de espera agotado/respuesta vacía devuelta null,Manejado por degradación ascendente
     */
    suspend fun review(candidates: List<AgentResultAggregator.Candidate>, question: String?): String? {
        if (candidates.isEmpty()) return null

        val prompt = buildReviewPrompt(candidates, question)
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = SYSTEM_PROMPT),
            UIMessage(role = MessageRole.USER, content = prompt),
        )

        // H-LLM2: resultOf{} Envolver llamada suspendida,Lanzamiento correcto CancellationException;
        // withTimeoutOrNull Ejecución general del paquete,Regreso del tiempo de espera null
        val completion = resultOf {
            withTimeoutOrNull(REVIEW_TIMEOUT_MS) {
                val (model, providerConfig) = resolveReviewModel()
                chatService.completeText(
                    messages = messages,
                    model = model,
                    temperature = REVIEW_TEMPERATURE,
                    maxTokens = REVIEW_MAX_TOKENS,
                    providerConfig = providerConfig,
                )
            }
        }.onError { msg, t ->
            Logger.w("LlmAggregator", "review excepción de llamada: $msg", t)
        }.getOrNull() ?: return null

        val text = completion.text.trim()
        return text.ifBlank { null }
    }

    /**
     * Modelo de revisión analítica.
     *
     * - reviewModelId esta vacio:Regresar (null, null),Primer modelo que utiliza el proveedor activo de ChatService
     * - reviewModelId No vacío:Recorra todos los proveedores para encontrar modelos coincidentes,Devolver si lo encuentra (Model, ProviderConfig)
     * - No se encontró ningún modelo coincidente:Regresar (null, null),Volver al comportamiento y registro predeterminados
     */
    private suspend fun resolveReviewModel(): Pair<Model?, ProviderConfig?> {
        val modelId = settingsRepository.multiAgentConfigCache.reviewModelId
        if (modelId.isNullOrBlank()) return null to null
        val providers = settingsRepository.getAllProviders()
        for (provider in providers) {
            val matched = provider.models.firstOrNull { it.id == modelId }
            if (matched != null) {
                return matched to provider
            }
        }
        Logger.w("LlmAggregator", "reviewModelId=$modelId No encontrado en todos los proveedores,Volver al modelo predeterminado")
        return null to null
    }

    /** Revisión de estructura prompt(chino). */
    private fun buildReviewPrompt(candidates: List<AgentResultAggregator.Candidate>, question: String?): String {
        val candidatesBlock = candidates.mapIndexed { i, c ->
            val meta = buildString {
                append("Fuente: ${c.source}")
                c.confidence?.let { append(", confianza: $it") }
            }
            "### candidato ${i + 1} ($meta)\n${c.content}"
        }.joinToString("\n\n")

        return """
Usted es un revisor de resultados de múltiples agentes. Aquí están las respuestas de varios asistentes a la misma tarea.,Por favor considere todas las perspectivas,
Marcar puntos de conflicto,Produce la salida de fusión final.

## tarea original
${question ?: "No proporcionado"}

## Resultados de los candidatos
$candidatesBlock

## Requisitos de salida
1. Si hay un conflicto,Enumere primero los puntos de conflicto(conciso)
2. Luego dé la respuesta final completa.(Sin títulos de sección,contenido directo)
3. No repitas el contenido del candidato,Fusión en lugar de empalme
4. Mantenlo dentro de las 1500 palabras.
        """.trimIndent()
    }

    companion object {
        private const val REVIEW_TIMEOUT_MS = 30_000L
        private const val REVIEW_TEMPERATURE = 0.3f
        private const val REVIEW_MAX_TOKENS = 2000

        private const val SYSTEM_PROMPT = "Eres un revisor riguroso de los resultados de múltiples agentes.,Bueno para integrar diferentes perspectivas, resolver conflictos y producir respuestas integrales de alta calidad."
    }
}
