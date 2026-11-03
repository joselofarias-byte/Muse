package io.zer0.ai.core

/**
 * v2.5.3 (P2-1): "Declaración a nivel de modelo" de la estrategia de reproducción de inferencia.
 *
 * Antecedentes:2026 Desde mediados de año,Kimi K3 / GLM 4.7+ / DeepSeek v4 A la espera de que se lance uno tras otro un lote de modelos de "Pensamiento Reservado".
 * Requieren que los mensajes históricos del asistente vuelvan a razonarse tal como están; de lo contrario, se degradarán silenciosamente o incluso recibirán directamente un error 400.
 *
 * Problema de situación actual: el contrato de repetición anteriormente solo estaba disponible en [ProviderCompat](supongo basado en el host baseUrl),
 * El mismo modelo en diferentes hosts (conexión directa oficial versus sitio de agregación)/tránsito) sacará conclusiones diferentes,
 * Y es fácil pasar por alto la configuración al agregar nuevos modelos. Aquí la "estrategia de repetición" se promociona a un ciudadano de primera.**Declaración a nivel de modelo**:
 * Primero declare por identificación del modelo,ProviderCompat El valor del host actúa como una anulación.
 *
 * con [ReasoningCarrier] / [ReasoningReplayPolicy] relación:
 *  - Este objeto describe la declaración "cómo se debe reproducir este modelo";
 *  - ProviderCompat.reasoningReplayContract Describir "cómo se puede reproducir realmente el enlace actual";
 *  - Orden de análisis:ProviderCompat declaración explícita > Declaración a nivel de modelo > Predeterminado (sin reproducción).
 */

/**
 * v2.5.3: Declaración de reproducción a nivel de modelo.
 *
 * @param carrier portador de reproducción (wire posición de campo), ver [ReasoningCarrier]
 * @param policy Estrategia de repetición, ver [ReasoningReplayPolicy]
 * @param requiresNonEmptyReasoning ¿Se requiere que "los campos se publiquen incluso si no hay contenido pensado?"
 *   parte del acuerdo (Kimi/DeepSeek Modo de pensamiento) En la solicitud con herramientas, si el mensaje del asistente histórico carece de este campo, responderá directamente a 400.
 *   Por lo tanto, es necesario enviar un marcador de posición de cadena vacío; se omiten otros campos faltantes del protocolo.
 */
data class ModelReasoningDeclaration(
    val carrier: ReasoningCarrier,
    val policy: ReasoningReplayPolicy,
    val requiresNonEmptyReasoning: Boolean = false,
)

/**
 * v2.5.3 (P2-1): Inferir declaración de reproducción de inferencia por identificación del modelo.
 *
 * Reglas: La nueva serie de modelos "Pensamientos reservados" se reproducirá completa de forma predeterminada.
 * Sólo la reproducción de la rueda de herramientas se diferencia por protocolo, modelo antiguo/Los modelos desconocidos no se declaran (use ProviderCompat o no reproduzca de forma predeterminada).
 *
 * IMPORTANTE: este analizador**Declare únicamente la estrategia y no inyecte ningún campo de extensión del proveedor.**, por lo que es seguro para puntos de tránsito desconocidos.
 * (A diferencia de pensar en la inyección de formato que activa un 400).
 */
object ModelReasoningReplayResolver {
    /** Normalización de identificación del modelo: estación de desagregación/Prefijo del fabricante, convertido a minúsculas. */
    private fun normalize(modelId: String): String {
        val raw = modelId.trim().lowercase()
        val prefixes =
            listOf(
                "openrouter/", "opencode-go/", "siliconflow/", "dashscope/",
                "deepseek-ai/", "zhipu/", "qwen/", "openai/", "anthropic/",
                "moonshotai/", "google/", "vertex/",
            )
        val stripped = prefixes.firstOrNull { raw.startsWith(it) }?.let(raw::removePrefix) ?: raw
        // Después de eliminar el prefijo de agregación, es posible que el segmento de proveedor aún esté presente.(Como por ejemplo "deepseek/deepseek-v4"), tome el último párrafo de manera uniforme.
        return stripped.substringAfterLast('/').takeIf { it.isNotBlank() } ?: stripped
    }

    /**
     * Infiere declaraciones de reproducción a nivel de modelo; devuelve nulo si no hay ninguno (providerCompat o el comportamiento predeterminado toma el control).
     */
    fun declare(modelId: String?): ModelReasoningDeclaration? {
        val normalized = modelId?.let(::normalize)
        return if (normalized.isNullOrBlank()) null else declarationFor(normalized)
    }

    private fun declarationFor(id: String): ModelReasoningDeclaration? =
        when (matchFamily(id)) {
            ReasoningFamily.OPENAI_RESPONSES -> preserveDeclaration(ReasoningCarrier.REASONING_ITEMS)
            ReasoningFamily.ANTHROPIC -> toolTurnDeclaration(ReasoningCarrier.THINKING_BLOCKS)
            ReasoningFamily.GEMINI -> toolTurnDeclaration(ReasoningCarrier.THOUGHT_SIGNATURE)
            ReasoningFamily.OPENROUTER -> preserveDeclaration(ReasoningCarrier.REASONING_DETAILS)
            ReasoningFamily.REASONING_CONTENT_STRICT -> reasoningContentDeclaration(requireNonEmpty = true)
            ReasoningFamily.REASONING_CONTENT_LENIENT -> reasoningContentDeclaration(requireNonEmpty = false)
            ReasoningFamily.NONE -> null
        }

    /** Familia de reproducción: poner“Nombre del modelo → Declaración”Convergencia de un conjunto de predicados a una sola coincidencia. */
    private enum class ReasoningFamily {
        OPENAI_RESPONSES,
        ANTHROPIC,
        GEMINI,
        OPENROUTER,
        REASONING_CONTENT_STRICT,
        REASONING_CONTENT_LENIENT,
        NONE,
    }

    private fun matchFamily(id: String): ReasoningFamily {
        carrierOnlyFamily(id)?.let { return it }
        return contentFamily(id)
    }

    /** Fabricantes con transportistas exclusivos (Responses/Anthropic/Gemini/OpenRouter). */
    private fun carrierOnlyFamily(id: String): ReasoningFamily? =
        when {
            id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") || id.startsWith("gpt-5") ->
                ReasoningFamily.OPENAI_RESPONSES
            id.startsWith("claude") -> ReasoningFamily.ANTHROPIC
            id.startsWith("gemini") -> ReasoningFamily.GEMINI
            id.startsWith("openrouter") || id.contains("reasoning") -> ReasoningFamily.OPENROUTER
            else -> null
        }

    /** reasoning_content El modelo del sistema de pensamiento del transportista se puede dividir en dos grupos según si los campos se pueden guardar. */
    private fun contentFamily(id: String): ReasoningFamily =
        when {
            STRICT_CONTENT_PREFIXES.any { id.startsWith(it) } || (id.contains("deepseek") && id.contains("reason")) ->
                ReasoningFamily.REASONING_CONTENT_STRICT
            LENIENT_CONTENT_PREFIXES.any { id.startsWith(it) } -> ReasoningFamily.REASONING_CONTENT_LENIENT
            id.contains("glm-4.7") || (id.startsWith("glm-4") && id.contains("thinking")) ->
                ReasoningFamily.REASONING_CONTENT_STRICT
            id.startsWith("doubao") && id.contains("thinking") -> ReasoningFamily.REASONING_CONTENT_LENIENT
            else -> ReasoningFamily.NONE
        }

    private fun preserveDeclaration(carrier: ReasoningCarrier) =
        ModelReasoningDeclaration(carrier = carrier, policy = ReasoningReplayPolicy.PRESERVE)

    private fun toolTurnDeclaration(carrier: ReasoningCarrier) =
        ModelReasoningDeclaration(carrier = carrier, policy = ReasoningReplayPolicy.REQUIRE_TOOL_CALL)

    private fun reasoningContentDeclaration(requireNonEmpty: Boolean) =
        ModelReasoningDeclaration(
            carrier = ReasoningCarrier.REASONING_CONTENT,
            policy = ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
            requiresNonEmptyReasoning = requireNonEmpty,
        )
}

/** Si el campo no se puede omitir, se debe considerar el prefijo (el ascendente del campo faltante es directamente 400). */
private val STRICT_CONTENT_PREFIXES =
    listOf("deepseek-v4", "deepseek-v3", "deepseek-r1", "deepseek-reasoner", "kimi-k", "kimi-thinking", "glm-z1", "glm-5")

/** Piense en el prefijo cuando se puede omitir el campo (omita el campo cuando el contenido esté vacío para guardar tokens). */
private val LENIENT_CONTENT_PREFIXES =
    listOf("qwen3", "qwq-", "qwen-vl", "mimo", "longcat")

