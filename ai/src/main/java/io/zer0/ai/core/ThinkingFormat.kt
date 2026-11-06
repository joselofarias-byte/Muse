package io.zer0.ai.core

/**
 * v1.0.7: Piensa en formato de enumeración(9 tipos en total).
 *
 * Los diferentes servicios compatibles con OpenAI están representados por diferentes campos."pensar"parámetros,Esta enumeración se centra en describir estas diferencias.
 * Provider Utilice esta enumeración para determinar qué campos enviar al construir el cuerpo de la solicitud.;ProviderCompatRules prensa
 * baseUrl host + modelId Deducir valores específicos.
 *
 * ## 9 formato
 *
 * - [ANTHROPIC]:Anthropic bloque de pensamiento nativo(Esta enumeración solo se utiliza para la adaptación del protocolo compatible con OpenAI.,
 *   AnthropicProvider Hazte nativo por tu cuenta Messages API,No leas este campo)
 * - [QWEN]:Ali Tong Yi/Qwen-VL Serie,`enable_thinking: bool` + `thinking_budget: int`
 * - [QWEN_CHAT_TEMPLATE]:Qwen3-Instruct etc. modelo chat_template,
 *   `chat_template_kwargs: {enable_thinking: bool}`
 * - [ZHIPU]:Serie GLM-4 / GLM-Z1,`thinking: {type: "enabled"|"disabled", clear_thinking: bool}`
 * - [DEEPSEEK]:DeepSeek-R1 / deepseek-reasoner,Sin parámetros de pensamiento(El servidor está habilitado de forma predeterminada),
 *   sólo a través de `reasoning_content` Cadena de pensamiento de retorno de campo
 * - [OPENROUTER]:OpenRouter agregación,`reasoning: {effort: "low"|"medium"|"high"}`
 *   (Extensiones en el protocolo de finalización de chat,Diferente del razonamiento en la API de Respuestas)
 * - [KIMI]:lado oscuro de la luna Kimi K1/K2,`thinking: {type: "enabled"|"disabled", keep: bool}`
 * - [VOLCENGINE]:motor volcánico Doubao 1.5 Thinking,`thinking: {type: "enabled"|"disabled"}`
 * - [LONGCAT]:meituán LongCat,`thinking: {type: "enabled"|"disabled"}`
 *
 * Nota:
 *  - null(no establecido)Sigue los estándares OpenAI `reasoning_effort` campo(Determinado por ProviderCompat.supportsReasoningEffort)
 *  - [DEEPSEEK] No envíes ningún parámetro de pensamiento.,Pero su contenido de razonamiento debe ser analizable en flujo.(Manejado por delta.reasoningContenido de OpenAIProvider)
 *  - Esta enumeración solo impulsa la construcción del cuerpo de solicitud de OpenAIProvider;AnthropicProvider / GeminiProvider Cada uno realiza un pensamiento original.
 */
enum class ThinkingFormat {
    ANTHROPIC,
    QWEN,
    QWEN_CHAT_TEMPLATE,
    ZHIPU,
    DEEPSEEK,
    OPENROUTER,
    KIMI,
    VOLCENGINE,
    LONGCAT,
}

/**
 * v1.0.7: reasoning portador de reproducción(5 especie carrier).
 *
 * Cuando el mensaje histórico contiene el contenido de la ronda de razonamiento anterior,Diferentes protocolos utilizan diferentes campos de cables para la reproducción.
 * Esta enumeración determina cómo se serializa UIMessage.reasoning/UIMessage.thinkingSignature en el cuerpo de la solicitud.
 *
 * - [REASONING_CONTENT]:Kimi / DeepSeek / MiMo / Zhipu Chat Completions,
 *   en `messages[i].reasoning_content` campo(string),policy Generalmente [ReasoningReplayPolicy.REQUIRE_TOOL_CALL]
 * - [THINKING_BLOCKS]:Anthropic Messages,en `messages[i].content[j]` China y Canadá thinking block
 *   (Manejado por el propio AnthropicProvider,no irse ProviderPayloadNormalizer)
 * - [REASONING_ITEMS]:OpenAI Responses API,en `input[]` adición de hermanos superiores reasoning item
 *   (Manejado por la rama useResponseApi de OpenAIProvider)
 * - [REASONING_DETAILS]:OpenRouter,en `messages[i].reasoning_details: [{type, id, data}]` matriz
 * - [THOUGHT_SIGNATURE]:Gemini native,en `messages[i].content[j]` China y Canadá thought part
 *   (Manejado por el propio GeminiProvider)
 */
enum class ReasoningCarrier {
    REASONING_CONTENT,
    THINKING_BLOCKS,
    REASONING_ITEMS,
    REASONING_DETAILS,
    THOUGHT_SIGNATURE,
}

/**
 * v1.0.7: reasoning Estrategia de repetición(3 especie policy).
 *
 * - [NONE]:Sin reproducción(Predeterminado),El campo de razonamiento histórico no está escrito en el cuerpo de la solicitud.
 * - [PRESERVE]:siempre reservado,Mientras el mensaje histórico tenga el campo de razonamiento, se reproducirá tal cual.
 *   (Anthropic thinking_blocks / OpenAI Responses reasoning_items / OpenRouter reasoning_details /
 *    Gemini thought_signature Todos usan esta estrategia.)
 * - [REQUIRE_TOOL_CALL]:Reproducción solo cuando los mensajes históricos del asistente tengan llamadas a herramientas reasoning_content,
 *   De lo contrario fail closed(Evite que Kimi/DeepSeek informe errores de diálogo de varios turnos debido a la falta de contenido_razonamiento)
 *   (Kimi / DeepSeek / MiMo / Zhipu Chat Completions usa esta estrategia)
 */
enum class ReasoningReplayPolicy {
    NONE,
    PRESERVE,
    REQUIRE_TOOL_CALL,
}

/**
 * v1.0.7: reasoning contrato de repetición(carrier + policy).
 *
 * por [ProviderCompat.reasoningReplayContract] llevar,OpenAIProvider Convertir a OpenAI
 * Consume este contrato cuando haya noticias históricas.,Determina cómo escribir en el campo de razonamiento.
 *
 * @param carrier wire posición de campo(ver [ReasoningCarrier])
 * @param policy Estrategia de repetición(ver [ReasoningReplayPolicy])
 */
data class ReasoningReplayContract(
    val carrier: ReasoningCarrier,
    val policy: ReasoningReplayPolicy,
    /**
     * v2.5.3: REQUIRE_TOOL_CALL + Al utilizar herramientas, ¿el asistente histórico tiene que enviar un marcador de posición de cadena vacía cuando falta el razonamiento?
     *
     * true(Predeterminado, alineado con el modo de pensamiento DeepSeek/Kimi): el campo debe existir; de lo contrario, el flujo ascendente es 400, por lo que si está vacío, se enviará una cadena vacía;
     * false: Omitir el campo cuando el contenido esté vacío (Qwen/Doubao Espere a que se tolere el protocolo predeterminado) y guarde los tokens.
     */
    val requiresNonEmptyReasoning: Boolean = true,
)
