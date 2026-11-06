package io.zer0.ai.core

/**
 * Modelo de estrategia de análisis del presupuesto de resultados.
 *
 * en metadatos del modelo [Model.maxOutputTokens] es el límite superior de la capacidad del modelo,
 * Los maxTokens pasados ​​por la persona que llama son solo el presupuesto para esta solicitud. El presupuesto final no puede exceder ningún límite superior conocido.
 *
 * Reglas:
 *  - Cuando la persona que llama pasa un número positivo, utilice min(presupuesto de la persona que llama, Límite superior del modelo);
 *  - Cuando la persona que llama no aprueba un presupuesto, si el directorio modelo tiene un límite superior, se utilizará el límite superior para evitar depender del valor predeterminado implícito del proveedor que es demasiado pequeño;
 *  - Si la persona que llama pasa 0 o un número negativo, se considera que no está configurado;
 *  - Cuando se desconoce el límite superior del modelo, se retiene el número positivo de la persona que llama. Si la persona que llama no lo configura, el proveedor continúa usando su propio valor predeterminado.
 */
object ModelOutputPolicy {

    /**
     * Calcule la cantidad de tokens de entrada que se enviarán al proveedor.
     *
     * Esta es una estimación conservadora para fines de protección del presupuesto.,No es un estándar de facturación:Chino por caracter,ASCII Presione unos 4 caracteres/token,
     * Reserva aprox. para cada imagen 1000 token,e incorporar el razonamiento/Llamadas a herramientas y esquema de herramientas.
     */
    fun estimateInputTokens(messages: List<UIMessage>, tools: List<ToolDefinition>? = null): Int {
        var total = 0L
        for (message in messages) {
            total += MESSAGE_OVERHEAD_TOKENS
            total += estimateTextTokens(message.content)
            total += estimateTextTokens(message.reasoning.orEmpty())
            total += message.imageBase64List.size * IMAGE_TOKEN_RESERVE
            message.toolCalls.orEmpty().forEach { call ->
                total += estimateTextTokens(call.name)
                total += estimateTextTokens(call.arguments)
            }
        }
        tools.orEmpty().forEach { tool ->
            total += estimateTextTokens(tool.name)
            total += estimateTextTokens(tool.description)
            total += estimateTextTokens(tool.parametersJsonSchema)
        }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Reserve espacio para la salida según la cantidad de tokens de entrada; no se hacen conjeturas cuando se desconoce la ventana de contexto.
     *
     * Este es el segundo límite del presupuesto de solicitud: el modelo maxOutputTokens es el límite superior de capacidad.
     * contextWindow - inputTokens - reserveTokens ¿Está disponible el límite superior para esta solicitud?
     *
     * NOTA: La ventana de contexto es la entrada+El resultado se comparte y todo el contexto restante no puede considerarse como el presupuesto de resultado actual.
     * Cuando el modelo no declara maxOutputTokens y la persona que llama no proporciona un presupuesto, se devuelve nulo (devuelve el valor predeterminado del Proveedor).
     * Evite ampliar max_tokens cerca de la ventana de contexto y ser rechazado con un 400 por parte de upstream.
     */
    fun resolveForContext(requestedMaxTokens: Int?, model: Model, inputTokens: Int, reserveTokens: Int = 1_024): Int? {
        require(inputTokens >= 0) { "inputTokens must not be negative" }
        require(reserveTokens >= 0) { "reserveTokens must not be negative" }
        val contextWindow = model.contextWindow?.takeIf { it > 0 }
        if (contextWindow == null) return resolve(requestedMaxTokens, model)
        val available = (contextWindow - inputTokens - reserveTokens).coerceAtLeast(1)
        val modelBound = model.maxOutputTokens?.takeIf { it > 0 }
        val requested = requestedMaxTokens?.takeIf { it > 0 }
        // Cuando el modelo no declara un límite superior de producción, disponible (todo el contexto restante) no se puede utilizar directamente como presupuesto de producción actual:
        //  - A la ventana de contexto se accede mediante+Compartir resultados, todos los resultados no son válidos;
        //  - Los proveedores generalmente establecen otro límite superior para una única salida (por ejemplo, 65536), que inevitablemente será rechazada por los 400 ascendentes después de la amplificación.
        // Por lo tanto: no hay límite de modelo ni presupuesto proporcionado por la persona que llama → Devuelve nulo y devuelve el valor predeterminado del propio proveedor;
        //      La persona que llamó dio un presupuesto. → Sólo ajustarse al contexto restante, no expandirse hacia afuera.
        val contextBound = if (modelBound == null && requested == null) {
            null
        } else {
            minOf(available, modelBound ?: available)
        }
        return contextBound?.let { minOf(requested ?: it, it) }
    }

    /**
     * Analice el límite superior del token de salida finalmente enviado al proveedor para esta solicitud.
     *
     * No amplíe el número positivo establecido explícitamente por la persona que llama sin autorización; solo si la persona que llama no lo configura, se utilizará el límite superior de la capacidad declarada en el directorio del modelo.
     */
    fun resolve(requestedMaxTokens: Int?, model: Model): Int? {
        val requested = requestedMaxTokens?.takeIf { it > 0 }
        val declaredModelLimit = model.maxOutputTokens?.takeIf { it > 0 }
        // Los límites de salida no pueden exceder la ventana de contexto del modelo; Los valores del catálogo se conservan tal cual y se representan mediante ModelRegistry
        // Para marcar valores sospechosos e imposibles, aquí se bloquean solo en el nivel del presupuesto de solicitud real.
        val contextLimit = model.contextWindow?.takeIf { it > 0 }
        val modelLimit = if (declaredModelLimit != null && contextLimit != null) {
            minOf(declaredModelLimit, contextLimit)
        } else {
            declaredModelLimit
        }
        return when {
            requested != null && modelLimit != null -> minOf(requested, modelLimit)
            requested != null -> requested
            modelLimit != null -> modelLimit
            else -> null
        }
    }

    /** Devuelve verdadero cuando el presupuesto explícito se ajusta al límite superior del modelo, para su uso en registros de diagnóstico y UI. */
    fun wasClamped(requestedMaxTokens: Int?, model: Model): Boolean {
        val requested = requestedMaxTokens?.takeIf { it > 0 }
        val declaredModelLimit = model.maxOutputTokens?.takeIf { it > 0 }
        if (requested == null || declaredModelLimit == null) return false
        val contextLimit = model.contextWindow?.takeIf { it > 0 }
        val modelLimit = if (contextLimit != null) {
            minOf(declaredModelLimit, contextLimit)
        } else {
            declaredModelLimit
        }
        return requested > modelLimit
    }

    private fun estimateTextTokens(text: String): Int {
        if (text.isBlank()) return 0
        var cjk = 0
        var other = 0
        text.forEach { ch ->
            if (isCjk(ch)) cjk++ else other++
        }
        return cjk + ((other + ASCII_CHARS_PER_TOKEN - 1) / ASCII_CHARS_PER_TOKEN)
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF ||
            code in 0x3040..0x30FF ||
            code in 0xAC00..0xD7AF
    }

    private const val ASCII_CHARS_PER_TOKEN = 4
    private const val MESSAGE_OVERHEAD_TOKENS = 4
    private const val IMAGE_TOKEN_RESERVE = 1_000
}
