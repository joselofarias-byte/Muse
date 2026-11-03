package io.zer0.ai.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * OpenAI Chat Completions API solicitud/Responder a DTO.
 *
 * Phase 7 Expandir:tool_calls / tool_call_id / tools campo(function calling).
 * Phase 8.6 Expandir:content Cambiar a JsonElement,Soporte multimodal(string o conjunto de piezas).
 * La denominación de los campos se alinea estrictamente con la documentación oficial de OpenAI.
 */

// ════════════════════════════════════════════════════════════════════════════
// v1.0.7: OpenAI Responses API DTO(/v1/responses punto final)
//
// Corresponde al protocolo openai-responses/openai-codex-responses.
// Diferencias clave con la API de finalización de chat:
//  - Cuerpo de la solicitud:messages → input;system role → instructions Campos de nivel superior;
//    max_tokens → max_output_tokens;Nuevo reasoning: {effort, summary}
//  - Respuesta de transmisión:event: response.output_text.delta + data: {delta: "..."},
//    etiqueta final response.completed(concurrencia data: [DONE])
//  - respuesta sin transmisión:choices[0].message.content → output[] matriz,
//    reasoning / function_call Todos ellos son salidas de alto nivel. sibling(no en el mensaje)
// ════════════════════════════════════════════════════════════════════════════

/**
 * v1.0.7: Responses API Cuerpo de solicitud.
 *
 * @param model modelo id
 * @param input matriz de entrada(Reemplazo para finalizar el chat messages)
 * @param instructions Comandos del sistema(sustituto system role,Campos de nivel superior)
 * @param stream Ya sea para transmitir(Codex aplicación del protocolo true)
 * @param max_output_tokens Límite superior de salida(sustituto max_tokens)
 * @param temperature Temperatura de muestreo
 * @param tools Definición de herramienta(Igual que la estructura de finalización del chat.,Pero puede contener tipos extendidos como image_generation)
 * @param reasoning Configuración de inferencia(effort + summary)
 * @param store Si persistir(Codex aplicación del protocolo false)
 * @param include Elementos de salida adicionales.B5-03 pasar ["reasoning.encrypted_content"],El servidor estará ahí.
 *   reasoning item Regresar en encrypted_content(El único portador para la reproducción de cadenas de pensamiento de múltiples rondas);
 *   Cuando no se pasa, encrypted_content siempre es null,La repetición no se puede activar.
 */
@Serializable
internal data class ResponsesRequest(
    val model: String,
    val input: List<ResponsesInputItem>,
    val instructions: String? = null,
    val stream: Boolean = true,
    val max_output_tokens: Int? = null,
    val temperature: Float? = null,
    val tools: List<ResponsesTool>? = null,
    val tool_choice: String? = null,
    val reasoning: ResponsesReasoningConfig? = null,
    val store: Boolean? = null,
    val include: List<String>? = null,
)

/**
 * v1.0.7: Responses API Configuración de inferencia.
 *
 * @param effort fuerza de razonamiento:"minimal" / "low" / "medium" / "high"
 * @param summary tipo de resumen:"auto" / "concise" / "detailed" / "none"(Opcional)
 */
@Serializable
internal data class ResponsesReasoningConfig(
    val effort: String,
    val summary: String? = null,
)

/**
 * v1.0.7: Responses API input Elementos de matriz.
 *
 * @param role papel:"user" / "assistant" / "system" / "developer"
 * @param content contenido(string o matriz ContentBlock,Preserve la flexibilidad con JsonElement).
 *   solo `type="message"` Usar.
 * @param output Texto del resultado de la herramienta.**solo `type="function_call_output"` uso**.
 *   Responses API La especificación requiere que este artículo se utilice `output` Lleva el resultado; si se usa incorrectamente `content`,
 *   El servidor responderá 422 `input: missing field 'output'`(v2.6.7 reparar).
 * @param type item Tipo;null=ordinario message;"reasoning"=Reproducción de inferencia item;"function_call"=Reproducción de llamadas de herramientas
 * @param id reasoning item de id(para reproducción);function_call de id
 * @param call_id function_call de call_id(corresponder tool_call_id)
 * @param name function_call nombre de la función
 * @param arguments function_call Cadena JSON de parámetro
 * @param summary reasoning item conjunto resumido de(Transmisión transparente tal como es durante la reproducción.)
 * @param encrypted_content reasoning item carga útil cifrada(Tecla de reproducción)
 */
@Serializable
internal data class ResponsesInputItem(
    val role: String? = null,
    val content: JsonElement? = null,
    val output: JsonElement? = null,
    val type: String? = null,
    val id: String? = null,
    val call_id: String? = null,
    val name: String? = null,
    val arguments: String? = null,
    val summary: JsonElement? = null,
    val encrypted_content: String? = null,
)

/**
 * v1.0.7: Responses API Respuesta sin transmisión.
 *
 * @param id response id(Como por ejemplo "resp_xxx")
 * @param status Estado(Como por ejemplo "completed",equivalente a finish_reason)
 * @param model modelo id
 * @param output Matriz de salida(message / reasoning / function_call esperando la cima sibling)
 * @param output_text Texto de acceso directo de nivel superior(Si existe, úselo directamente.,Equivalente a empalmar message.content.output_text)
 * @param usage token Dosis
 */
@Serializable
internal data class ResponsesResult(
    val id: String? = null,
    val status: String? = null,
    val model: String? = null,
    val output: List<ResponsesOutputItem> = emptyList(),
    @SerialName("output_text")
    val outputText: String? = null,
    val usage: ResponsesUsage? = null,
    /** Responses API en status=incomplete razones específicas dadas en ese momento. */
    @SerialName("incomplete_details")
    val incompleteDetails: ResponsesIncompleteDetails? = null,
)

@Serializable
internal data class ResponsesIncompleteDetails(
    val reason: String? = null,
)

@Serializable
internal data class ResponsesOutputItem(
    val type: String, // "message" | "reasoning" | "function_call" | "image_generation_call"
    val id: String? = null,
    val role: String? = null,
    val status: String? = null,
    val content: List<ResponsesContentBlock>? = null,
    @SerialName("call_id")
    val callId: String? = null,
    val name: String? = null,
    val arguments: String? = null,
    val summary: JsonElement? = null,
    @SerialName("encrypted_content")
    val encryptedContent: String? = null,
    val result: String? = null, // image_generation_call El resultado base64 de
)

@Serializable
internal data class ResponsesContentBlock(
    val type: String, // "output_text" | "text" | "refusal" | "input_text" | "input_image"
    val text: String? = null,
    val annotations: List<ResponsesAnnotation> = emptyList(),
)

@Serializable
internal data class ResponsesAnnotation(
    val type: String? = null,
    val url: String? = null,
    val title: String? = null,
)

@Serializable
internal data class ResponsesUsage(
    val input_tokens: Int? = null,
    val output_tokens: Int? = null,
    val reasoning_tokens: Int? = null,
    val total_tokens: Int? = null,
)

/**
 * v1.0.7: Responses API Transmisión de eventos de ESS.
 *
 * tipo de evento crítico:
 *  - response.output_text.delta:Incremento de texto(delta campo)
 *  - response.output_text.done:Fin del texto(text campo,Texto completo de la portada.)
 *  - response.reasoning_summary_text.delta:Delta de resumen de razonamiento(delta campo)
 *  - response.reasoning_summary_text.done:Resumen final del razonamiento(text campo)
 *  - response.function_call_arguments.delta:Incremento del parámetro de llamada de herramienta(item_id + delta)
 *  - response.output_item.added:Nuevo output item(item campo,Como se inicia function_call)
 *  - response.completed:final de la corriente(response campo,Contiene la matriz de salida final.)
 *
 * Todos los campos son opcionales,Diferentes tipos de eventos utilizan diferentes campos;type Es el campo discriminante.
 */
@Serializable
internal data class ResponsesStreamEvent(
    val type: String,
    val delta: String? = null,
    val text: String? = null,
    val item: JsonElement? = null,
    val item_id: String? = null,
    val output_index: Int? = null,
    val summary_index: Int? = null,
    val response: ResponsesResult? = null,
)

@Serializable
internal data class OpenAIRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val max_tokens: Int? = null,
    val stream: Boolean = true,
    /** Phase 7: lista de definición de herramientas,Habilitar la llamada a funciones. */
    val tools: List<OpenAITool>? = null,
    /** v1.0.40: Estrategias de selección de herramientas.tools=null obligatorio "none" Evite la llamada a la herramienta de alucinación modelo. */
    val tool_choice: String? = null,
    /**
     * Phase 8.5 Reparación: nivel de razonamiento(o1/gpt-5 Campo de razonamiento_esfuerzo de serie).
     * solo si reasoningLevel != OFF/AUTO Enviado cuando el esfuerzo no está vacío.,valor "minimal"/"low"/"medium"/"high".
     */
    val reasoning_effort: String? = null,
    // v1.80 (L-OAI10): Elimine el código inactivo de Response_Format/OpenAIResponseFormat.
    //   Al campo original nunca se le ha asignado un valor(siempre null),Y el modo OpenAI JSON debe coincidir con la descripción del esquema.,
    //   No existe ningún requisito de modo JSON para el negocio actual.,Si está habilitado, la entrada debe diseñarse por separado en lugar de conservar campos muertos.
)

/**
 * OpenAI cuerpo del mensaje.
 *
 * - role=assistant Y cuando la decisión de LLM llama a la herramienta,[toolCalls] No vacío,[content] Puede ser una cadena vacía
 * - role=tool tiempo(Resultados de la herramienta de relleno),[toolCallId] Requerido,[content] ¿Es el texto del resultado de la ejecución de la herramienta?
 *
 * Phase 8.6: [content] Cambiar a [JsonElement] para apoyar la multimodalidad:
 *  - texto plano: `JsonPrimitive("text")`
 *  - multimodal: `JsonArray([{type:"text", text:"..."}, {type:"image_url", image_url:{url:"data:..."}}])`
 */
@Serializable
internal data class OpenAIMessage(
    val role: String,
    val content: JsonElement,
    /** assistant La solicitud de llamada de herramienta transportada por el mensaje. */
    @SerialName("tool_calls")
    val toolCalls: List<OpenAIToolCall>? = null,
    /** tool El correspondiente tool_call_id cuando se repone el mensaje. */
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    /**
     * v1.0.7: Reproducción de contenidos de razonamiento histórico.
     *
     * Solo se utiliza para transmitir el proceso de razonamiento de la ronda anterior de mensajes del asistente tal como está. API,Haz múltiples rondas de diálogo.
     * modelo puede"ver"Mi cadena de pensamientos anterior.carrier=REASONING_CONTENT de fabricantes
     * (Kimi / DeepSeek / MiMo / Zhipu Chat Completions)en assistant + tool_calls
     * escena**Requisitos obligatorios**volver reasoning_content,De lo contrario, se devuelve un error 400 de cierre fallido.
     * (Estos proveedores utilizan Reasoning_content para verificar la integridad de la cadena de pensamiento.,Si falta, se considera que la solicitud ha sido manipulada.).
     *
     * - null:no escribas JSON(explicitNulls=false La serialización del tiempo ignora)
     * - no nulo: escribir `"reasoning_content": "..."` campo
     *
     * Nota:Este campo es el mismo que streaming delta.reasoning_content/non-streaming message.reasoning_content
     *   es el mismo campo de alambre,Pero la semántica es diferente. — transmisión/La no transmisión es un campo de respuesta API(Salida del modelo),
     *   Aquí están los campos de solicitud.(Historial de repetición). El nombre del cable compartido por ambos es una convención de protocolo compatible con OpenAI.
     */
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
)

/** OpenAI Definición de herramienta(type=function). */
@Serializable
internal data class OpenAITool(
    val type: String = "function",
    val function: OpenAIToolFunction,
)

/** Responses API Herramientas: admite herramientas funcionales y herramientas integradas como web_search_preview. */
@Serializable
internal data class ResponsesTool(
    val type: String,
    val name: String? = null,
    val description: String? = null,
    val parameters: JsonElement? = null,
)

@Serializable
internal data class OpenAIToolFunction(
    val name: String,
    val description: String,
    /** parámetros JSON Schema(originales JSON,Utilizado por el proveedor Json.decodeFromString(JsonElement.serializer()) analizar). */
    val parameters: JsonElement,
)

/** assistant Solicitud de llamada de herramienta en el mensaje. */
@Serializable
internal data class OpenAIToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAIToolCallFunction,
)

@Serializable
internal data class OpenAIToolCallFunction(
    val name: String,
    val arguments: String,
)

@Serializable
internal data class OpenAIStreamChunk(
    val choices: List<OpenAIChoice> = emptyList(),
    /**
     * A5: transmisión usage — Al solicitar la inyección de stream_options.include_usage,último trozo transportado usage
     * (choices es una matriz vacía). Algunas puertas de enlace volverán sin inyección,Los campos que aceptan valores NULL son compatibles con ambos comportamientos.
     */
    val usage: OpenAICompletionUsage? = null,
)

@Serializable
internal data class OpenAIChoice(
    val index: Int = 0,
    val delta: OpenAIDelta? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

/**
 * Incremento de transmisión.
 *
 * Phase 7: [toolCalls] Es la herramienta de streaming que llama al array incremental.,Cada elemento tiene index/id/function{name/arguments}.
 * arguments es una cadena fragmentada,Necesidad de acumular empalmes según índice.
 */
@Serializable
internal data class OpenAIDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAIDeltaToolCall>? = null,
)

/** Fragmento incremental de streaming tool_call. */
@Serializable
internal data class OpenAIDeltaToolCall(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: OpenAIDeltaToolCallFunction? = null,
)

@Serializable
internal data class OpenAIDeltaToolCallFunction(
    val name: String? = null,
    val arguments: String? = null,
)

/** respuesta sin transmisión(stream=false regresar cuando). */
@Serializable
internal data class OpenAICompletionResponse(
    val choices: List<OpenAICompletionChoice> = emptyList(),
    /** v1.0.53 Phase 3: token Dosis(Chat Completions API Regresar). */
    val usage: OpenAICompletionUsage? = null,
)

/**
 * v1.0.53 Phase 3: Chat Completions API uso de tokens(Alinear los campos de uso de OpenAI).
 *
 * Algunos modelos se devuelven por separado en complete_tokens_details reasoning_tokens(Como por ejemplo o1).
 */
@Serializable
internal data class OpenAICompletionUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("completion_tokens_details") val completionTokensDetails: OpenAICompletionTokensDetails? = null,
    /** A5: prompt golpe de caché token(OpenAI Regresar prompt_tokens_details.cached_tokens). */
    @SerialName("prompt_tokens_details") val promptTokensDetails: OpenAICompletionPromptTokensDetails? = null,
)

@Serializable
internal data class OpenAICompletionTokensDetails(
    @SerialName("reasoning_tokens") val reasoningTokens: Int = 0,
)

/** A5: prompt Detalles — cached_tokens(Aciertos de caché de contexto,solo mostrar,No participa en el consumo total.). */
@Serializable
internal data class OpenAICompletionPromptTokensDetails(
    @SerialName("cached_tokens") val cachedTokens: Int = 0,
)

@Serializable
internal data class OpenAICompletionChoice(
    val index: Int = 0,
    val message: OpenAICompletionMessage? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
internal data class OpenAICompletionMessage(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAIToolCall>? = null,
)

/** Respuesta de error sin transmisión(Solo se usa al incorporar cuerpos de error HTTP en transmisiones SSE). */
@Serializable
internal data class OpenAIErrorBody(
    val error: OpenAIErrorDetail? = null,
)

@Serializable
internal data class OpenAIErrorDetail(
    val message: String? = null,
    val type: String? = null,
    val code: JsonElement? = null,
)

/**
 * Deserializador de lista de cadenas permisivo.
 *
 * Algunas aguas arriba (plan de codificación de volcanes, etc.) devuelven capacidades/modalidades a
 * cadena separada por comas ("code,chat") en lugar de una matriz JSON (["code","chat"]),
 * También hay casos en los que se devuelve nulo. Aquí son todos compatibles: matrices, cadenas separadas por comas,null.
 */
private object FlexibleStringListSerializer : kotlinx.serialization.KSerializer<List<String>?> {
    private val listSerializer = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("FlexibleStringList")

    override fun deserialize(decoder: Decoder): List<String>? {
        val json = decoder as? JsonDecoder
            ?: return try {
                listSerializer.deserialize(decoder)
            } catch (_: Exception) {
                null
            }
        return when (val element = json.decodeJsonElement()) {
            is JsonNull -> null
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive ->
                element.content
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .ifEmpty { null }
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: List<String>?) {
        encoder.encodeSerializableValue(listSerializer, value.orEmpty())
    }
}

/** GET /models respuesta.data[].id es la identificación del modelo. */
@Serializable
internal data class OpenAIModelsResponse(
    val data: List<OpenAIModelInfo>? = null,
)

/**
 * Upstream /models Información de modelo único.
 *
 * v1.132 (H-OAI3): Campos extendidos para soportar OpenRouter / Together / DeepInfra espera
 * Servicio compatible con OpenAI que devuelve metadatos enriquecidos. Todos los campos de extensión tienen valores predeterminados,Estándar OpenAI
 * /models Volver al valor predeterminado cuando la interfaz no regresa(compatible con versiones anteriores).
 *
 * - [id]: modelo id(debe tener)
 * - [context_length]: OpenRouter/Together Espere la ventana de contexto devuelta(token numero)
 * - [top_provider]: OpenRouter información del proveedor(Contiene max_completion_tokens)
 * - [pricing]: OpenRouter información de precios(prompt/completion per token)
 * - [capabilities]: P2-3 Nuevo,Lista de capacidades del modelo declaradas por el servidor.(Como por ejemplo ["vision","tools"]).
 *   Ollama Devuelto en /api/show desde 0.4.0 capabilities,Algunos servicios de agregación también estarán disponibles en
 *   /models llevado en respuesta;Prefiere la inferencia local cuando hay un valor.(ver [io.zer0.ai.ollama.OllamaVisionInferrer]).
 *   Cuando no se devuelve el servicio estándar, es null,No afecta la compatibilidad con versiones anteriores.
 */
@Serializable
internal data class OpenAIModelInfo(
    val id: String = "",
    val context_length: Int? = null,
    val max_completion_tokens: Int? = null,
    val top_provider: OpenAIModelTopProvider? = null,
    val pricing: OpenAIModelPricing? = null,
    @Serializable(with = FlexibleStringListSerializer::class)
    val capabilities: List<String>? = null,
    /**
     * v1.0.8: Parcialmente aguas arriba(OpenRouter / Algunas estaciones de transferencia compatibles con OpenAI)Devuelve la declaración modal.
     * Valores comunes: "text" / "image" / "audio" / "video";Complementario a las capacidades.
     */
    @Serializable(with = FlexibleStringListSerializer::class)
    val modalities: List<String>? = null,
)

@Serializable
internal data class OpenAIModelTopProvider(
    val max_completion_tokens: Int? = null,
)

@Serializable
internal data class OpenAIModelPricing(
    /** prompt precio(per token,La forma de cadena evita la pérdida de precisión.). */
    val prompt: String? = null,
    /** completion precio(per token). */
    val completion: String? = null,
)

/** v1.0.53 Phase 3: Chat Completions usage -> [io.zer0.ai.core.UsageTokens]. */
internal fun OpenAICompletionUsage.toUsageTokens(): io.zer0.ai.core.UsageTokens = io.zer0.ai.core.UsageTokens(
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    reasoningTokens = completionTokensDetails?.reasoningTokens ?: 0,
    cachedTokens = promptTokensDetails?.cachedTokens ?: 0,
)

/** v1.0.53 Phase 3: Responses API usage -> [io.zer0.ai.core.UsageTokens]. */
internal fun ResponsesUsage.toUsageTokens(): io.zer0.ai.core.UsageTokens = io.zer0.ai.core.UsageTokens(
    promptTokens = input_tokens ?: 0,
    completionTokens = output_tokens ?: 0,
    reasoningTokens = reasoning_tokens ?: 0,
)
