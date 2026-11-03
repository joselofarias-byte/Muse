package io.zer0.muse.tools

import io.zer0.ai.core.Model

/**
 * v1.0.47 P6-2: Las herramientas débiles se llaman verificadores de modelos.
 *
 * Identificar modelos con capacidades débiles de llamada de herramientas(Como las primeras versiones de DeepSeek R1/V3 y algunos modelos de código abierto.),
 * Degradar estos modelos en modo agente:
 *  - Deshabilitar llamadas de herramientas paralelas(Cambiar a serie)
 *  - UI Indica al usuario que la capacidad de llamada de la herramienta del modelo actual es débil
 *
 * Base del juicio:Coincidencia de patrones de identificación del modelo. Los modelos de herramientas débiles conocidos pasan [WEAK_TOOL_PATTERNS] definición.
 * Los modelos incomparables tienen por defecto STRONG(Llamada de herramienta normal).
 */
object WeakToolUseDetector {

    /**
     * Patrones de identificación de modelos llamados por herramientas débiles conocidas(regular,No distingue entre mayúsculas y minúsculas).
     *
     * - DeepSeek R1 Serie(reasoning modelo,La llamada a la herramienta es inestable)
     * - DeepSeek V3/v3 versión anterior(v3.2 Hubo problemas conocidos con llamadas anteriores a herramientas paralelas.)
     * - Qwen versión anterior(Qwen2 El formato de llamada de herramienta anterior era inestable)
     * - Modelo parcialmente minimalista(mini/tiny/nano nivel,El número de parámetros no es suficiente para estabilizar la llamada a la herramienta.)
     */
    private val WEAK_TOOL_PATTERNS = listOf(
        Regex("(?i)deepseek-r1"),
        Regex("(?i)deepseek-v3(?!\\.2)"), // v3 pero excluir v3.2
        Regex("(?i)deepseek-chat(?!.+v3\\.2)"),
        Regex("(?i)qwen-?2(?![.\\d])"), // Qwen2 pero no Qwen2.5+
        Regex("(?i)\\b(mini|tiny|nano)\\b.*\\b(instruct|chat)\\b"),
        Regex("(?i)gemma-?2(?![.\\d])"), // Gemma2 pero no Gemma3
    )

    /**
     * Detecta si el modelo dado es un modelo de llamada de herramientas débil.
     *
     * @param model Modelo actualmente seleccionado(null regresar cuando false,Tratar de forma conservadora)
     * @return true Indica que la herramienta modelo tiene una capacidad de llamada débil,Recomendar bajar de categoría
     */
    fun isWeakToolModel(model: Model?): Boolean {
        if (model == null) return false
        // Los modelos que no admiten llamadas a herramientas no se cuentan"débil",más bien"No compatible"
        if (!model.supportsToolCalling()) return false
        return WEAK_TOOL_PATTERNS.any { it.containsMatchIn(model.id) }
    }

    /**
     * Obtenga una copia de consejos de degradación para modelos de herramientas débiles.
     * Devuelve nulo para indicar un modelo de herramienta no débil,No se requieren indicaciones.
     */
    fun getWeakToolHint(model: Model?): String? {
        // Prueba vacía explícita,Deje que el compilador convierta inteligentemente el modelo a no vacío
        if (model == null || !isWeakToolModel(model)) return null
        return when {
            model.id.contains(Regex("(?i)deepseek-r1")) ->
                "DeepSeek R1 Las llamadas a herramientas en serie son inestables,Degradado a llamada en serie"
            model.id.contains(Regex("(?i)deepseek")) ->
                "DeepSeek Las llamadas a herramientas paralelas en versiones anteriores pueden ser anormales,Degradado a llamada en serie"
            model.id.contains(Regex("(?i)qwen")) ->
                "Qwen El formato de llamada de herramientas de versiones anteriores es inestable,Degradado a llamada en serie"
            else ->
                "La capacidad de llamada de la herramienta del modelo actual es débil,Degradado a llamada en serie"
        }
    }
}
