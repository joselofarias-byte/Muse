package io.zer0.ai.anthropic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Anthropic Messages API Objeto de transferencia de datos.
 *
 * Diferencias clave con las finalizaciones de chat de OpenAI:
 *  - system es un campo de nivel superior,No en la matriz de mensajes
 *  - Los eventos de streaming se distinguen por tipo(message_start / content_block_start / content_block_delta / message_delta / message_stop)
 *  - Para autenticación x-api-key + anthropic-version header,No Bearer
 *  - thinking modelo(claude-3-7-sonnet / claude-opus-4)cadena de pensamiento thinking_delta
 *
 * Phase 8.1 (M9): agregar [cache_control] Soporte de campo Prompt Caching,
 * Cuando está habilitado, Claude almacenará en caché las indicaciones del sistema y los prefijos de conversaciones largas.,5m/1h TTL,
 * Las solicitudes repetidas solo cuentan el 10% del costo del token de entrada.
 *
 * Prompt Caching campo:
 *  - [AnthropicSystemBlock.cache_control]: system prompt etiqueta de caché
 *  - [AnthropicMessage.cache_control]: etiqueta de caché de mensajes(Se utiliza para prefijos de conversaciones largas.)
 *  - [AnthropicRequest.system] Cambiar a List<AnthropicSystemBlock> Admite etiquetas de caché
 *
 * v1.80 (H-ANT1~4 / M-ANT5 / L-ANT1~3): Eventos de transmisión extendida DTO,
 * apoyo thinking_delta / input_json_delta / signature_delta / error / content_block_start / message_start.
 */

@Serializable
internal data class AnthropicRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    val system: List<AnthropicSystemBlock>? = null,
    val max_tokens: Int,
    val temperature: Double? = null,
    val stream: Boolean = false,
    /**
     * Phase 8.1 (M11): thinking Configuración(extended thinking).
     * null Indica no habilitado;Presione cuando no sea nulo [io.zer0.ai.core.ReasoningLevel] cartografía.
     */
    val thinking: AnthropicThinking? = null,
    /**
     * v1.80 (H-ANT2): lista de definición de herramientas,Habilite la llamada a funciones nativas antrópicas.
     * null Indica que la llamada a herramientas no está habilitada;Si no está vacío, se transmitirá de forma transparente a la matriz de herramientas.
     */
    val tools: List<AnthropicTool>? = null,
)

/**
 * v1.80 (H-ANT2): Anthropic Definición de herramienta.
 *
 * Diferencias con OpenAI:Anthropic usar `input_schema`(en vez de `parameters`),
 * Y es necesario declarar el nivel superior del esquema. `"type": "object"`.
 *
 * @param name nombre de la función
 * @param description Descripción(LLM Decide si llamar basándose en esto)
 * @param input_schema parámetros JSON Schema(Paso directo JsonElement,Evite el análisis repetido)
 */
@Serializable
internal data class AnthropicTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonElement,
)

/**
 * system prompt bloquear(apoyo cache_control).
 * Escenario de cadena única envuelto en una lista de un solo elemento [{type: "text", text: "..."}].
 */
@Serializable
internal data class AnthropicSystemBlock(
    val type: String = "text",
    val text: String,
    val cache_control: AnthropicCacheControl? = null,
)

/**
 * Etiqueta de control de caché.
 * @param type Fijo "ephemeral"(caché temporal)
 */
@Serializable
internal data class AnthropicCacheControl(
    val type: String = "ephemeral",
    @SerialName("ttl") val ttl: String? = null, // "5m" / "1h",No transferir el valor predeterminado 5m
)

/**
 * thinking Configuración(Anthropic extended thinking).
 * @param type Fijo "enabled"
 * @param budget_tokens Piense en la cantidad de fichas de presupuesto
 */
@Serializable
internal data class AnthropicThinking(
    val type: String = "enabled",
    val budget_tokens: Int,
)

@Serializable
internal data class AnthropicMessage(
    val role: String, // "user" / "assistant"
    val content: JsonElement, // Phase 8.6: Texto plano JsonPrimitivo o multimodal JsonArray
    val cache_control: AnthropicCacheControl? = null,
)

@Serializable
internal data class AnthropicCompletionResponse(
    val content: List<AnthropicContentBlock>,
    val stop_reason: String? = null,
    /** v1.0.53 Phase 3: token Dosis(Se devolvió la respuesta sin transmisión). */
    val usage: AnthropicUsage? = null,
)

/**
 * Bloques de contenido.
 * v1.80 (L-ANT3): aumentar [id]/[name]/[input] Los campos admiten el análisis de bloques tool_use.
 */
@Serializable
internal data class AnthropicContentBlock(
    val type: String, // "text" / "thinking" / "tool_use" / "tool_result"
    val text: String = "",
    val id: String? = null, // tool_use Bloquear llamadas a herramientas id
    val name: String? = null, // tool_use nombre de la función de bloque
    val input: JsonElement? = null, // tool_use Parámetros de entrada del bloque
)

// ── eventos de transmisión DTO ──

/**
 * Transmisión de eventos de ESS.
 *
 * v1.80 Expandir:
 *  - [index] / [content_block]: content_block_start evento(H-ANT2 / L-ANT2)
 *  - [message]: message_start cuerpo del evento,Contiene usage(L-ANT1)
 *  - [usage]: message_delta lleno de acontecimientos usage
 *  - [error]: error evento(H-ANT4)
 */
@Serializable
internal data class AnthropicStreamEvent(
    val type: String,
    val delta: AnthropicDelta? = null,
    val index: Int? = null, // content_block_start/stop El subíndice del bloque
    val content_block: AnthropicContentBlock? = null, // content_block_start bloquear información
    val message: AnthropicMessageStart? = null, // message_start cuerpo del mensaje(Contiene usage)
    val usage: AnthropicUsage? = null, // message_delta de usage
    val error: AnthropicErrorDetail? = null, // error Detalles de error del evento.
)

/**
 * message_start cuerpo del mensaje en el evento(L-ANT1).
 * Anthropic Regresar {"type":"message_start","message":{"usage":{...}}}.
 */
@Serializable
internal data class AnthropicMessageStart(
    val usage: AnthropicUsage? = null,
)

/**
 * token Dosis(L-ANT30).
 * usado para message_start(message.usage) y message_delta(nivel superior usage).
 */
@Serializable
internal data class AnthropicUsage(
    val input_tokens: Int = 0,
    val output_tokens: Int = 0,
    val cache_read_input_tokens: Int = 0,
    /** v1.80 (L-ANT4): Prompt Caching La cantidad de tokens contados al crear el caché(para estadísticas de costos). */
    val cache_creation_input_tokens: Int = 0,
)

/**
 * Delta de transmisión.
 *
 * v1.80 Expandir:
 *  - [thinking]: thinking_delta Pensamientos sobre el evento(H-ANT1)
 *  - [partial_json]: input_json_delta incremento del parámetro de herramienta(H-ANT2)
 *  - [signature]: signature_delta incremento de firma(M-ANT5)
 */
@Serializable
internal data class AnthropicDelta(
    val type: String? = null,
    val text: String? = null,
    val thinking: String? = null, // thinking_delta contenido de pensamiento
    val partial_json: String? = null, // input_json_delta incremento del parámetro de herramienta
    val signature: String? = null, // signature_delta incremento de firma
    val stop_reason: String? = null,
)

/**
 * content_block_start Representación estructurada de eventos.(L-ANT2).
 * Anthropic Regresar {"type":"content_block_start","index":0,"content_block":{...}}.
 */
@Serializable
internal data class AnthropicContentBlockStart(
    val index: Int = 0,
    val content_block: AnthropicContentBlock? = null,
)

// ── error ──

@Serializable
internal data class AnthropicErrorBody(
    val type: String? = null,
    val error: AnthropicErrorDetail? = null,
)

@Serializable
internal data class AnthropicErrorDetail(
    val type: String? = null,
    val message: String? = null,
)

/**
 * GET /v1/models respuesta.data[].id es la identificación del modelo.
 * v1.80 (L-ANT8): Agregue has_more/last_id para admitir la paginación.
 */
@Serializable
internal data class AnthropicModelsResponse(
    val data: List<AnthropicModelInfo> = emptyList(),
    val has_more: Boolean = false,
    val first_id: String? = null,
    val last_id: String? = null,
)

@Serializable
internal data class AnthropicModelInfo(
    val id: String = "",
)

/**
 * v1.0.53 Phase 3: Anthropic usage -> [io.zer0.ai.core.UsageTokens].
 */
@Deprecated(
    "La asignación se ha movido a AnthropicProvider.anthropicUsageTokens, este método elimina silenciosamente los campos almacenados en caché",
    ReplaceWith("anthropicUsageTokens(this, output_tokens)"),
)
internal fun AnthropicUsage.toUsageTokens(): io.zer0.ai.core.UsageTokens = io.zer0.ai.core.UsageTokens(
    promptTokens = input_tokens,
    completionTokens = output_tokens,
    reasoningTokens = 0, // Anthropic No se cuentan por separado reasoning tokens
)
