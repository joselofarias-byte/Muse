package io.zer0.ai.core

import kotlinx.serialization.Serializable

/**
 * Provider Tipo de vendedor. decidir [ProviderRegistry] Qué rama tomar para construir el Proveedor.
 *
 * Codifique explícitamente el tipo de protocolo en ProviderType.
 *
 * - [OPENAI]: OpenAI Oficial + todos los protocolos compatibles con OpenAI(Azure / Tránsito/Ollama et al.),ir Chat Completions
 *   (Acuerdo `openai-completions`)
 * - [ANTHROPIC]: Anthropic Claude + MiniMax(Usando el protocolo de Mensajes Antrópicos),ir Messages API
 *   (Acuerdo `anthropic-messages`)
 * - [GEMINI]: Google Gemini,ir generateContent / streamGenerateContent
 *   (Acuerdo `google-generative-ai`)
 * - [OPENAI_RESPONSES]: OpenAI Responses API(/v1/responses),Para xAI Grok OAuth y más
 *   (Acuerdo `openai-responses` / `openai-codex-responses`)
 *
 * v1.0.6 Nuevo [OPENAI_RESPONSES],Proveedor de la API de Respuestas(Como por ejemplo xAI Grok OAuth).
 */
@Serializable
enum class ProviderType {
    OPENAI,
    ANTHROPIC,
    GEMINI,
    OPENAI_RESPONSES,
    ;

    companion object {
        /** Tipo predeterminado(Compatibilidad con versiones anteriores cuando la configuración anterior no tiene campo de tipo). */
        val DEFAULT = OPENAI
    }
}
