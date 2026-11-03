package io.zer0.ai.core

/**
 * M2.4/M2.5: Límites de eventos unificados para streaming y no streaming.
 *
 * - [ChatCompletion.toEventSequence]:Convierta resultados únicos que no sean de transmisión en streamChat()
 *   lo mismo [ChatStreamEvent] secuencia de eventos(contenido → pensar → Herramientas → Dosis → Cotización → Completo).
 *   Anteriormente, esta lógica solo existía en el chat de la capa de aplicaciones. UI(io.zer0.muse.ui.chat.ChatResponsePolicy),
 *   M2 Iniciar y descender al módulo ai como única implementación de normalización,app La capa se cambia para delegar llamada.
 * - [ChatEventReducer]:Agrega secuencias de eventos de cualquier fuente en [NormalizedChatResult],
 *   suministro"transmisión/Equivalente de proyección final sin streaming"Las pruebas de aceptación se utilizan con la repetición de partidos SSE.
 *
 * convención de secuencia de eventos(Consistente con el KDoc de ChatStreamEvent):
 *  ReasoningDelta → ContentDelta → ImageDelta* → ToolCallDelta*(index orden ascendente) →
 *  UsageDelta* → CitationDelta → Done
 */

/**
 * Convierta un resultado completo que no sea de transmisión en una secuencia de eventos equivalente a la transmisión.
 *
 * Texto vacío, respuesta de sólo razonamiento(Comportamiento sin transmisión de algunos modelos de inferencia)También produce secuencias válidas.:
 * reasoning Los eventos se emiten como de costumbre.,La persona que llama determina que la respuesta es válida basándose en esto.,En lugar de informar falsamente un error de texto vacío.
 */
fun ChatCompletion.toEventSequence(): List<ChatStreamEvent> = buildList {
    reasoningContent?.takeIf { it.isNotEmpty() }?.let {
        add(
            ChatStreamEvent.ReasoningDelta(
                delta = it,
                signature = thinkingSignature,
                encryptedContent = thinkingEncryptedContent,
            ),
        )
    }
    text.takeIf { it.isNotEmpty() }?.let { add(ChatStreamEvent.ContentDelta(it)) }
    toolCalls.orEmpty().forEachIndexed { index, toolCall ->
        add(
            ChatStreamEvent.ToolCallDelta(
                index = index,
                id = toolCall.id,
                name = toolCall.name,
                argumentsDelta = toolCall.arguments,
                // Los resultados que no son de transmisión contienen instantáneas completas de los parámetros,Los consumidores deberían reemplazar en lugar de agregar
                isSnapshot = true,
            ),
        )
    }
    usageTokens?.let { add(ChatStreamEvent.UsageDelta(it)) }
    citationUrls.takeIf { it.isNotEmpty() }?.let { add(ChatStreamEvent.CitationDelta(it)) }
    add(ChatStreamEvent.Done(finishReason))
}

/**
 * M2.4: Resultados de chat normalizados — El producto unificado de agregación de streaming y conversión sin streaming.
 *
 * La misma respuesta lógica sin importar dónde esté streamChat() Aún así completeText() → toEventSequence(),
 * por [ChatEventReducer] obtenido después de la polimerización [NormalizedChatResult] debe ser igual(M2 Aceptación).
 *
 * @param text Texto agregado(Una cadena vacía indica que no hay texto)
 * @param reasoning Pensamientos agregados(null significa ninguno)
 * @param toolCalls Llamadas de herramientas agregadas(Ordenar por índice ascendente)
 * @param finishReason Motivo de finalización(Done transporte de eventos)
 * @param usage último evento de uso(Provider Cuando no se devuelve, es null)
 * @param citationUrls Cotización URL(Mantenga el orden de los eventos y elimine la duplicación)
 * @param thinkingSignature Piensa en la firma(Anthropic / OpenAI Responses)
 * @param thinkingEncryptedContent OpenAI Responses encrypted_content
 * @param imageBase64List Gemini inlineData Incremento de imagen(en orden de llegada)
 * @param error mensaje de evento de error(Error/StreamInterrupted);null Indica que el flujo se completó normalmente.
 * @param interrupted ¿Es StreamInterrupted(Interrumpido después de recibir parte del contenido.)
 * @param postFinishFramesDropped Done El número de fotogramas delta que llegaron y se eliminaron después(Observación de anomalías del protocolo.)
 */
data class NormalizedChatResult(
    val text: String = "",
    val reasoning: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val finishReason: String? = null,
    val usage: UsageTokens? = null,
    val citationUrls: List<String> = emptyList(),
    val thinkingSignature: String? = null,
    val thinkingEncryptedContent: String? = null,
    val imageBase64List: List<String> = emptyList(),
    val error: String? = null,
    val interrupted: Boolean = false,
    val postFinishFramesDropped: Int = 0,
)

/**
 * M2.6: [ChatStreamEvent] agregador(fixture replay La función central pura de).
 *
 * reglas de agregación:
 *  - ContentDelta/ReasoningDelta:Adjuntar en orden de llegada.
 *  - ToolCallDelta:Agregado por índice;isSnapshot=true al reemplazar el parámetro de índice(Repetir fotograma de instantánea
 *    Quitar peso de forma natural),De lo contrario, anexar incrementalmente;id/name El primer valor no nulo entra en vigor.;La salida se ordena por índice en orden ascendente.
 *    Nota:Text ContentDelta no realiza deduplicación arbitraria —— Las respuestas legítimas pueden contener fragmentos duplicados.,
 *    La deduplicación de fotogramas duplicados es solo para"Reemplazo completo de instantáneas"Seguridad del marco de llamada de herramienta semántica.
 *  - UsageDelta:último efectivo(Anthropic entrada/La salida se envía en dos).
 *  - CitationDelta:Fusión secuencial y deduplicación.
 *  - Done:grabar finishReason;Los fotogramas delta posteriores se descartan y cuentan.(finish Después delta es una excepción de protocolo).
 *  - Error / StreamInterrupted:Errores de registro e indicadores de interrupción,Contenido agregado retenido(Algunas respuestas no se perderán.).
 */
class ChatEventReducer {

    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    private val toolCalls = sortedMapOf<Int, ToolCallAccumulator>()
    private val images = mutableListOf<String>()
    private val citations = linkedSetOf<String>()
    private var finishReason: String? = null

    /**
     * Revisión de corrección(P2): La determinación del estado final utiliza indicadores explícitos en lugar de `finishReason != null` —
     * [ChatStreamEvent.Done] permitir terminar la razón para ser null(Algunos proveedores no tienen motivos para dejar de hacerlo.),
     * Sólo confiar en FinishReason hará Done(null) Los fotogramas incrementales posteriores no se descartan,No es consistente con el acuerdo.
     */
    private var doneSeen = false
    private var usage: UsageTokens? = null
    private var signature: String? = null
    private var encryptedContent: String? = null
    private var error: String? = null
    private var interrupted = false
    private var postFinishFramesDropped = 0

    /** Agregación evento por evento;Se puede llamar en serie desde cualquier hilo.(No es seguro para subprocesos,El que llama garantiza el orden). */
    fun reduce(event: ChatStreamEvent) {
        if (doneSeen && isIncrementalFrame(event)) {
            // Done Después solo se descartan los fotogramas incrementales.(Observación de anomalías del protocolo.);evento terminal(Error/Usage/Citation)Tratalo como de costumbre
            postFinishFramesDropped++
            return
        }
        when (event) {
            is ChatStreamEvent.ReasoningDelta -> consumeReasoning(event)
            is ChatStreamEvent.ContentDelta -> content.append(event.delta)
            is ChatStreamEvent.ImageDelta -> images.add(event.imageBase64)
            is ChatStreamEvent.ToolCallDelta -> consumeToolCall(event)
            else -> reduceControl(event)
        }
    }

    /** Piensa incrementalmente:Acumulación de texto + firma/El contenido cifrado está cubierto con valores que no están vacíos. */
    private fun consumeReasoning(event: ChatStreamEvent.ReasoningDelta) {
        reasoning.append(event.delta)
        event.signature?.let { signature = it }
        event.encryptedContent?.let { encryptedContent = it }
    }

    /** finalidad/evento de control(Uso, referencia, finalización, error, desconexión, aviso de degradación). */
    private fun reduceControl(event: ChatStreamEvent) = when (event) {
        is ChatStreamEvent.UsageDelta -> usage = event.usage
        is ChatStreamEvent.CitationDelta -> citations.addAll(event.urls)
        is ChatStreamEvent.Done -> {
            finishReason = event.finishReason
            // Revisión de corrección(P2): Done(null) También es el estado final.,debe ser configurado,Los fotogramas incrementales posteriores se descartarán
            doneSeen = true
        }
        is ChatStreamEvent.Error -> recordFailure(event.message, interrupted = false)
        is ChatStreamEvent.StreamInterrupted -> recordFailure(event.message, interrupted = true)
        else -> Unit
    }

    /** Marcos de llamada de herramientas agregados por índice(Reemplazo de instantánea/anexo incremental). */
    private fun consumeToolCall(event: ChatStreamEvent.ToolCallDelta) {
        val acc = toolCalls.getOrPut(event.index) { ToolCallAccumulator() }
        acc.consume(event)
    }

    /** error/Detener el flujo y ajustar cuentas:Preservar el contenido agregado(Algunas respuestas no se perderán.). */
    private fun recordFailure(message: String, interrupted: Boolean) {
        error = message
        this.interrupted = interrupted
    }

    /** Decisión de marco incremental:Done Las tramas que aparecen más tarde son excepciones al protocolo.,Descartar y contar. */
    private fun isIncrementalFrame(event: ChatStreamEvent): Boolean = when (event) {
        is ChatStreamEvent.ContentDelta,
        is ChatStreamEvent.ReasoningDelta,
        is ChatStreamEvent.ToolCallDelta,
        is ChatStreamEvent.ImageDelta,
        -> true
        else -> false
    }

    fun reduceAll(events: Iterable<ChatStreamEvent>) {
        events.forEach(::reduce)
    }

    /** Resultados de agregación de salida;Se puede llamar varias veces(Volver a la instantánea,No cambia el estado de agregación.). */
    fun toResult(): NormalizedChatResult = NormalizedChatResult(
        text = content.toString(),
        reasoning = reasoning.toString().ifEmpty { null },
        toolCalls = toolCalls.map { (index, acc) -> acc.toToolCall(index) },
        finishReason = finishReason,
        usage = usage,
        citationUrls = citations.toList(),
        thinkingSignature = signature,
        thinkingEncryptedContent = encryptedContent,
        imageBase64List = images.toList(),
        error = error,
        interrupted = interrupted,
        postFinishFramesDropped = postFinishFramesDropped,
    )

    /**
     * Acumulador de herramientas que requiere un único índice.
     * id/name Obtener el primer valor no nulo(Los fragmentos posteriores suelen ser null);Los parámetros se reemplazan o agregan de acuerdo con la semántica de isSnapshot.
     */
    private class ToolCallAccumulator {
        var id: String? = null
        var name: String? = null
        private val arguments = StringBuilder()

        fun consume(event: ChatStreamEvent.ToolCallDelta) {
            if (id == null && event.id != null) id = event.id
            if (name == null && event.name != null) name = event.name
            val delta = event.argumentsDelta ?: return
            if (event.isSnapshot) {
                arguments.setLength(0)
                arguments.append(delta)
            } else {
                arguments.append(delta)
            }
        }

        fun toToolCall(index: Int): ToolCall = ToolCall(
            id = id ?: "call_index_$index",
            name = name ?: "",
            arguments = arguments.toString(),
        )
    }
}

/**
 * Entrada conveniente:La secuencia de eventos se agrega en [NormalizedChatResult].
 */
fun List<ChatStreamEvent>.normalize(): NormalizedChatResult = ChatEventReducer().also { it.reduceAll(this) }.toResult()
