package io.zer0.ai.core

/**
 * Extracto del historial fuera de la ventana de contexto (determinista, cero llamadas de LLM).
 *
 * Antecedentes: el historial de sesiones excede la ventana de contexto ([List.limitContextWithContext] truncamiento),
 * Los mensajes fuera de la ventana anteriormente se descartaban silenciosamente——En una sesión que utiliza muchas herramientas, la ventana se llena rápidamente con mensajes de herramientas.
 * Las conversaciones anteriores en lenguaje natural (solicitudes de usuarios, respuestas del asistente) desaparecen directamente y el modelo se comporta como"amnesia".
 *
 * Este extractor procesa algunos mensajes fuera de la ventana antes del truncamiento.**Extractos de texto línea por línea**(retener usuario/Lenguaje natural del asistente
 * Fragmento de texto original + resumen determinista de la llamada a la herramienta), genera un mensaje de SISTEMA insertado antes de la ventana,
 * Deje que el modelo siempre vea los puntos clave de conversaciones anteriores y sepa exactamente"Historia anterior presentada en forma de extracto.".
 *
 * Restricciones de diseño:
 * - Función pura, sin red, sin LLM: el resultado solo depende de la lista de mensajes de entrada, comprobable, retraso cero;
 * - Entrada de solo lectura, no modifica ningún dato persistente;
 * - prensa"nuevo→viejo"Llenar el presupuesto de personajes, las partes anteriores que exceden el presupuesto se contraen para omitir la descripción;
 * - SYSTEM Los mensajes (solicitud inyectada dinámicamente/RAG, etc.) se omiten y no se extraen.
 */
object ContextHistoryDigest {
    /** Marcado estable de mensajes extraídos para facilitar la depuración y la identificación de proyecciones duplicadas. */
    const val DIGEST_MARKER: String = "[HISTORY_DIGEST]"

    private const val DEFAULT_MAX_CHARS = 6000
    private const val USER_ENTRY_CHARS = 400
    private const val ASSISTANT_ENTRY_CHARS = 300
    private const val TOOL_ENTRY_CHARS = 120
    private const val MAX_CALL_NAMES = 8

    private val WHITESPACE_REGEX = Regex("\\s+")

    /**
     * Convierta el mensaje fuera de ventana en un mensaje de SISTEMA extraído.
     *
     * @param dropped Mensajes truncados fuera de la ventana de contexto (cronológicamente, antiguos)→nuevo)
     * @param maxChars Presupuesto de caracteres para extractos de texto; si se excede el presupuesto, solo se conservan las piezas más nuevas
     * @return Extraer mensaje; devuelve nulo si no hay contenido disponible (la persona que llama conserva el comportamiento de truncamiento original)
     */
    fun build(dropped: List<UIMessage>, maxChars: Int = DEFAULT_MAX_CHARS): UIMessage? {
        if (dropped.isEmpty()) return null

        val lines = ArrayDeque<String>()
        var used = 0
        for (index in dropped.indices.reversed()) {
            val line = entryLine(dropped[index]) ?: continue
            if (used + line.length + 1 > maxChars) break
            lines.addFirst(line)
            used += line.length + 1
        }
        if (lines.isEmpty()) return null

        val omitted = dropped.size - lines.size
        val content =
            buildString {
                append(DIGEST_MARKER)
                append(" Aquí hay extractos de una conversación anterior.(en orden cronológico,Cuanto más atrás, más nuevo,Solo para referencia de contexto de continuación,No es una nueva directiva de usuario):\n")
                lines.forEach { line ->
                    append("- ").append(line).append('\n')
                }
                if (omitted > 0) {
                    append("(antes ").append(omitted).append(" El mensaje no se expandió debido al límite de longitud)\n")
                }
            }
        return UIMessage(role = MessageRole.SYSTEM, content = content)
    }

    /** Línea de extracto de un solo mensaje; sin contenido de extracto (texto vacío/Pure SYSTEM) devuelve nulo. */
    private fun entryLine(msg: UIMessage): String? = when (msg.role) {
        MessageRole.USER -> normalize(msg.content, USER_ENTRY_CHARS)?.let { "Usuario: $it" }
        MessageRole.ASSISTANT -> assistantLine(msg)
        MessageRole.TOOL -> normalize(msg.content, TOOL_ENTRY_CHARS)?.let { "Resultados de la herramienta: $it" }
        MessageRole.SYSTEM -> null
    }

    /** Mensaje del asistente: conserva el texto en lenguaje natural; información de llamada de herramienta como anotaciones auxiliares. */
    private fun assistantLine(msg: UIMessage): String? {
        val text = normalize(msg.content, ASSISTANT_ENTRY_CHARS)
        val toolHint =
            when {
                !msg.toolCalls.isNullOrEmpty() ->
                    "herramienta de llamada: " + msg.toolCalls.take(MAX_CALL_NAMES).joinToString(", ") { it.name }

                msg.toolCallInfo != null -> {
                    val info = msg.toolCallInfo
                    val status = if (info.isSuccess) "éxito" else "falló"
                    val preview = normalize(info.result, TOOL_ENTRY_CHARS)
                    if (preview == null) "herramienta de llamada: ${info.toolName}($status)" else "herramienta de llamada: ${info.toolName}($status) → $preview"
                }

                else -> null
            }
        return when {
            text != null && toolHint != null -> "asistente: $text | $toolHint"
            text != null -> "asistente: $text"
            else -> toolHint
        }
    }

    /** Aplanar los espacios en blanco y truncarlos para [cap] personajes; el contenido vacío devuelve nulo. */
    private fun normalize(text: String, cap: Int): String? {
        val flat = text.replace(WHITESPACE_REGEX, " ").trim()
        if (flat.isBlank()) return null
        return if (flat.length > cap) flat.take(cap) + "…" else flat
    }
}
