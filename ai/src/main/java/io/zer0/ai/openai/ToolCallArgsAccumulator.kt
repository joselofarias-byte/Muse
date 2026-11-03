package io.zer0.ai.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * v1.0.81: Acumulador de argumentos de streaming tool_call.
 *
 * Antecedentes del problema:
 *  OpenAI La transmisión canónica tool_call.arguments es el incremento de cadena (`{"lim` + `it":20}`),
 *  Simplemente agregue para completar el JSON. Pero modelos como DeepSeek V4/La estación de transferencia llamará a la misma herramienta.
 *  arguments a**Múltiples objetos JSON completos**Se emite la fragmentación del formulario:
 *
 *  ```
 *  {"limit":20}
 *  {"query":"clima"}
 *  ```
 *
 *  Anexar ciego deletreará `{"limit":20}{"query":"clima"}`, que es JSON ilegal, aguas abajo SkillExecutor
 *  parseArgs se basa en dividir y fusionar segmentos para compensarlo. Si encuentra un segmento no cerrado, perderá el campo y finalmente activará"Parámetro faltante: query".
 *
 * Este acumulador está en [append] Hay dos situaciones en las que se distingue el tiempo:
 *  1. Incremento de cadena (más común): búfer actual + nuevo fragmento, el conjunto se puede analizar en JsonObject y retener.
 *  2. Fragmentación completa del objeto: el búfer actual ya es un JsonObject completo y el nuevo fragmento se recorta con `{` principio y él mismo
 *     También es un JsonObject completo, que combina dos objetos.**Fusión de campos**(El campo que llega más tarde con el mismo nombre sobrescribe el campo que llega antes), generando un único
 *     Objeto Json legal.
 *
 * Truncar/Los fragmentos no cerrados se adjuntan como de costumbre,[isValidJson] Devuelve falso, la capa superior decide esperar los fragmentos posteriores
 * Todavía informa un error y nunca pierde campos silenciosamente.
 */
internal class ToolCallArgsAccumulator {

    private val buffer = StringBuilder()

    /** Si se produjeron varias fusiones completas de fragmentos de objetos JSON. */
    private var mergedObjects = false

    /** La cadena sin procesar acumulada actual. */
    fun current(): String = buffer.toString()

    /** El número acumulado actual de caracteres. */
    val length: Int get() = buffer.length

    /** Si el contenido ha sido acumulado. */
    fun isNotEmpty(): Boolean = buffer.isNotEmpty()

    /** Acumula un fragmento de incremento de argumentos. */
    fun append(fragment: String?) {
        val piece = fragment ?: return
        if (piece.isBlank()) return

        // Caso 2: el búfer ya es un objeto completo y el nuevo fragmento también es un objeto completo → Fusionar campos.
        val existingObj = parseObjectOrNull(buffer.toString())
        if (existingObj != null) {
            val newObj = parseObjectOrNull(piece.trim())
            if (newObj != null) {
                mergedObjects = true
                val merged = mergeObjects(existingObj, newObj)
                buffer.setLength(0)
                buffer.append(merged.toString())
                return
            }
        }

        // Caso 1: Incremento de cadena normal.append Si todo se empalma en varios objetos (obj1}{obj2}),
        // Dividir segmentos y fusionar; De lo contrario, manténgalos como están (los objetos individuales o los segmentos no cerrados no se volverán a serializar para evitar cambios en los espacios en blanco)./formato).
        buffer.append(piece)
        val combined = buffer.toString()
        if (parseObjectOrNull(combined) != null) return // Ya es un objeto único legal, manténgalo como está.
        val mergedFromConcat = tryMergeConcatenatedObjects(combined)
        if (mergedFromConcat != null) {
            mergedObjects = true
            buffer.setLength(0)
            buffer.append(mergedFromConcat.toString())
        }
    }

    /** Si el contenido acumulado actual es un objeto JSON legal. */
    fun isValidJson(): Boolean = parseObjectOrNull(buffer.toString()) != null

    /**
     * ¿Se han producido varias fusiones completas de fragmentos de objetos JSON?(DeepSeek V4 Espere a que el modelo arguments
     * dividir en {"a":1}{"b":2} múltiples objetos).
     *
     * cuando es cierto,El río arriba ya no puede"Fragmento delta original"Enviar a aguas abajo append(Eso lo volverá a ensamblar en una cuerda empalmada.),
     * En lugar de enviar [current]()(Completar después de fusionar JSON)como instantánea,Downstream utiliza instantáneas en lugar de anexos.
     */
    fun hasMergedObjects(): Boolean = mergedObjects

    private fun parseObjectOrNull(raw: String): JsonObject? {
        val text = raw.trim()
        if (!text.startsWith("{") || !text.endsWith("}")) return null
        return runCatching {
            val el = Json.parseToJsonElement(text)
            el as? JsonObject
        }.getOrNull()
    }

    /**
     * Detección "obj1}{obj2}{obj3}" Esta cadena concatenada se divide en varios JsonObjects y se fusiona.
     * Devuelve nulo cuando no se puede desmontar (tome la ruta de incremento de cadena normal).
     */
    private fun tryMergeConcatenatedObjects(raw: String): JsonObject? {
        val text = raw.trim()
        if (!text.startsWith("{")) return null
        // Objetos de nivel superior divididos profundamente usando paréntesis
        val objects = splitTopLevelObjects(text) ?: return null
        if (objects.size < 2) return null
        var merged: JsonObject? = null
        for (objText in objects) {
            val obj = parseObjectOrNull(objText) ?: return null
            merged = if (merged == null) obj else mergeObjects(merged, obj)
        }
        return merged
    }

    /** Dividido por par de llaves de nivel superior, devolviendo el texto original de cada objeto. Devuelve nulo si es ilegal. */
    private fun splitTopLevelObjects(text: String): List<String>? {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escape = false
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                if (escape) {
                    escape = false
                } else if (c == '\\') {
                    escape = true
                } else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        parts.add(text.substring(start, i + 1))
                        start = -1
                    } else if (depth < 0) {
                        return null // corchetes no coinciden
                    }
                }
            }
        }
        if (depth != 0 || inString) return null // no cerrado
        return parts.takeIf { it.isNotEmpty() }
    }

    private fun mergeObjects(a: JsonObject, b: JsonObject): JsonObject = buildJsonObject {
        // Pon todos los campos de un primero.
        a.forEach { (k, v) -> put(k, v) }
        // b Sobrescribir campos con el mismo nombre; si ambos lados son objetos, fusionar recursivamente
        b.forEach { (k, v) ->
            val existing = a[k]
            if (existing is JsonObject && v is JsonObject) {
                put(k, mergeObjects(existing, v))
            } else {
                put(k, v)
            }
        }
    }
}
