package io.zer0.muse.tools.channel

import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel

/**
 * Herramienta de lectura del contexto del chat grupal — Phone Session En el modo, el agente lee más historial de chat grupal.
 *
 * tiempo de llamada:
 *  - El agente predeterminado solo puede ver los mensajes más recientes,Llame a esta herramienta cuando necesite más contexto
 *  - Leer hasta 50 artículos
 *  - Pase [contextProvider] La persona que llama proporciona la devolución de llamada con el texto del mensaje histórico.(formateado)
 *
 * Diferencia con el channel_read_context existente en SkillExecutor:
 *  - SkillExecutor versión:LLM pasar chatId/limit,Sesión principal ajustable
 *  - esta versión:chatId atado por marco,Solo aceptar parámetro de límite,Solo para uso de sesión telefónica de chat grupal
 */
class ChannelReadContextTool(
    private val groupChatId: String,
    private val contextProvider: suspend (limit: Int) -> String,
) {

    /**
     * Definición de herramienta.
     *
     * limit es un parámetro opcional,parameterTypes marcado como integer,Al hacer que LLM genere un esquema JSON
     * el tipo es integer(Evite la falta de comunicación de LLM "20" La cadena provoca ambigüedad en el análisis).
     * Valor predeterminado 20,Rango 1..50.
     */
    fun toolDef() = ToolRegistry.ToolDef(
        name = "channel_read_context",
        description = "Leer más mensajes del historial de chat grupal,Le ayuda a comprender el contexto completo de la conversación actual." +
            "Por defecto solo puedes ver los mensajes más recientes,Llame a esta herramienta si necesita más contexto.",
        parameters = mapOf(
            "limit" to "Opcional. Número de mensajes históricos para leer,alcance 1-50,Predeterminado 20",
        ),
        required = emptySet(),
        category = "built-in",
        parameterTypes = mapOf("limit" to "integer"),
        riskLevel = ToolRiskLevel.NORMAL,
    )

    /**
     * herramienta de ejecución:analizar limit(Predeterminado 20,alcance 1..50),Pase [contextProvider] Devolución de llamada para obtener el historial.
     *
     * @param args Parámetros de la herramienta,LLM Los argumentos entrantes JSON analizados map
     * @return Texto formateado del historial de chat grupal
     */
    suspend fun execute(args: Map<String, String>): String {
        if (groupChatId.isBlank()) {
            return "error: La herramienta de chat grupal no está vinculada correctamente(chatId esta vacio)"
        }
        val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
        return contextProvider(limit)
    }
}
