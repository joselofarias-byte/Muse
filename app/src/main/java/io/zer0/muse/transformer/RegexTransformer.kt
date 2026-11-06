package io.zer0.muse.transformer

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRegex
import kotlinx.serialization.builtins.ListSerializer
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.97: Reemplazo regular Transformer — Aplique reglas regulares a nivel de asistente al texto del mensaje.
 *
 * Ejecutar en canalización de mensajes,Colocado después de PlaceholderTransformer y antes de enviarlo a LLM.
 * Reemplazar solo contenido de texto,No afecta la estructura del mensaje.
 *
 * Nota:Este objeto es una clase de herramienta.,Implementación de interfaz sin transformador. Consulte RegexMessageTransformer para conocer la implementación real de Transformer.
 */
object RegexTransformer {

    private const val TAG = "RegexTransformer"

    /** JSON Serializador. */
    private val serializer = ListSerializer(AssistantRegex.serializer())

    /**
     * M-REG1: Caché de compilación regular,Utilice una cadena de regla como clave.
     * Los conjuntos de reglas son generalmente estables(Múltiples rondas de reutilización después de la configuración del usuario),El almacenamiento en caché de los resultados de la compilación evita volver a compilar la expresión regular de cada mensaje.
     * Utilice ConcurrentHashMap para garantizar la seguridad del acceso simultáneo a múltiples corrutinas.
     */
    private val regexCache = ConcurrentHashMap<String, Regex>()

    /**
     * Analiza una lista de reglas regulares de AssistantEntity.
     */
    fun parseRules(assistant: AssistantEntity): List<AssistantRegex> {
        return parseRulesFromJson(assistant.regexRulesJson)
    }

    /**
     * Analizar una lista de reglas regulares de una cadena JSON(Para que UI llame directamente,No es necesario construir un completo AssistantEntity).
     */
    fun parseRulesFromJson(json: String): List<AssistantRegex> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            AppJson.decodeFromString(serializer, json)
        } catch (e: Exception) {
            Logger.w(TAG, "No se pudieron analizar las reglas regulares", e)
            emptyList()
        }
    }

    /**
     * Serializar la lista de reglas regulares en una cadena JSON(por la persistencia).
     */
    fun serializeRules(rules: List<AssistantRegex>): String {
        return AppJson.encodeToString(serializer, rules)
    }

    /**
     * Aplique reglas de reemplazo regulares al texto.
     *
     * @param text texto original
     * @param rules Lista de reglas regulares
     * @param scope Alcance del mensaje actual:"user" o "assistant"
     * @param visualOnly true=Filtrar solo reglas de solo visual;false=Filtrar solo reglas no visuales
     * @return Texto reemplazado
     */
    fun applyRules(text: String, rules: List<AssistantRegex>, scope: String, visualOnly: Boolean = false): String {
        if (rules.isEmpty() || text.isEmpty()) return text

        var result = text
        for (rule in rules) {
            if (!rule.enabled) continue
            if (rule.findRegex.isBlank()) continue
            // Comprobar coincidencia de rango
            val scopeMatch = when (rule.affectingScope) {
                "both" -> true
                scope -> true
                else -> false
            }
            if (!scopeMatch) continue
            // Comprobar coincidencias solo visuales
            if (rule.visualOnly != visualOnly) continue

            result = try {
                // M-REG1: Reutilizar instancias Regex almacenadas en caché,Evite recompilar cada mensaje(rule.findRegex generalmente estable)
                val regex = regexCache.getOrPut(rule.findRegex) { Regex(rule.findRegex) }
                regex.replace(result, rule.replaceString)
            } catch (e: Exception) {
                Logger.w(TAG, "El reemplazo regular falló: ${rule.name}(${rule.findRegex})", e)
                result
            }
        }
        return result
    }
}
