package io.zer0.muse.tools

import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * v1.0.81: Analizador JSON de parámetros de herramienta.
 *
 * Analice la cadena JSON tool_call.arguments pasada por LLM en `Map<String, String>`,
 * Para uso de capas de ejecución como SkillExecutor/ToolRegistry.
 *
 * principios de diseño (schema Ideas sencillas):
 *  - solo aceptar**Un único objeto JSON completo**. Hay una capa de historia `}{` Tolerancia a fallos para dividir y fusionar para tolerar
 *    DeepSeek V4 Streaming deletrea argumentos en `{"a":1}{"b":2}` problema; pero el parámetro de segmentación está truncado
 *    (Sin cerrar) perderá campos silenciosamente, sí web_search "Parámetro faltante: query" causa directa.
 *  - La causa raíz del empalme JSON ya está `OpenAIProvider.ToolCallArgsAccumulator` Corrección (fusión inteligente de múltiples
 *    JSON fragmentación de objetos). Los argumentos que llegan aquí son siempre un único objeto completo y ya no están fragmentados.
 *  - Si el análisis falla, se devuelve un mapa vacío (no se genera ninguna excepción) y se registra. En consecuencia, la capa de ejecución devuelve un mensaje claro sobre el parámetro faltante.LLM
 *    Regenerar parámetros completos en la siguiente ronda.——Observable, reintentable, sin pérdida de campo silenciosa.
 *  - valor que no es una cadena (objeto/matriz/numeros/booleanos) se serializan uniformemente en cadenas y se mantienen con la capa de ejecución `Map<String,String>`
 *    acuerdo.
 */
object ToolArgsParser {

    private const val TAG = "ToolArgsParser"

    /**
     * Analizar tool_call.arguments JSON.
     *
     * @param json LLM cadena de argumentos pasados
     * @param toolName Nombre de la herramienta (solo se usa para registros para localizar qué herramienta no se pudo analizar)
     * @return Mapa de parámetros; el análisis falla o los parámetros vacíos devuelven vacío map
     */
    fun parse(json: String?, toolName: String = "unknown"): Map<String, String> {
        if (json.isNullOrBlank()) {
            Logger.w(TAG, "$toolName arguments esta vacio")
            return emptyMap()
        }
        val trimmed = json.trim()
        if (!trimmed.startsWith("{")) {
            Logger.w(
                TAG,
                "$toolName arguments No es un objeto JSON(primer personaje='${trimmed.firstOrNull()}'),Volver vacío map",
            )
            return emptyMap()
        }
        val parsed = resultOf {
            val obj = JSON.decodeFromString(JsonObject.serializer(), trimmed)
            obj.entries.associate { (k, v) -> k to stringifyValue(v) }
        }.onError { msg, _ ->
            Logger.w(
                TAG,
                "$toolName arguments El análisis falló: $msg " +
                    ToolDataPrivacy.safeArgumentsForPreview(toolName, trimmed),
            )
        }.getOrNull()
        return parsed ?: emptyMap()
    }

    /** valor que no es una cadena (objeto/matriz/numeros/Booleano/null) se serializa en una cadena; las comillas exteriores se eliminan de la cadena. */
    private fun stringifyValue(v: JsonElement): String = when (v) {
        is JsonPrimitive -> v.content
        else -> JSON.encodeToString(JsonElement.serializer(), v)
    }

    // Utilice instancias Json independientes para evitar interferencias en la configuración global; ignorar las claves desconocidas.
    private val JSON = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
