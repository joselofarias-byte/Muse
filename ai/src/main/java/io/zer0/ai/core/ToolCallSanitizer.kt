package io.zer0.ai.core

import io.zer0.common.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Tool call Limpieza de parámetros.
 *
 * Compatible con JSON truncado, concatenación de múltiples objetos JSON y argumentos vacíos devueltos por algunas estaciones de transferencia OpenAI.
 * Se conservan los parámetros reparables; cuando es completamente irrecuperable, la llamada a la herramienta se conserva y se cambia a `{}`, deja que la capa de ejecución de la herramienta regrese
 * Los errores explícitos de parámetros faltantes se pasan al modelo para volver a intentarlo, y la única llamada a la herramienta no se puede eliminar silenciosamente, lo que genera una interfaz de usuario vacía.
 */
object ToolCallSanitizer {

    fun isValid(toolCall: ToolCall): Boolean = isValidName(toolCall.name) && toolCall.arguments.isNotBlank()

    /**
     * v2.6.7: Si el nombre de la herramienta es realmente un nombre de herramienta.
     *
     * Antecedentes (registro de mediciones reales del usuario): Algunos modelos (GLM-4-9B etc.) escribirá las líneas de texto en function.name,
     * Entonces parece `name="¡Está bien, Ziqi! Crearé un gráfico interactivo.…show_card"`.
     * La implementación anterior solo se verificó `name.isNotBlank()`Entonces esta cadena de caracteres chinos pasó por limpieza, aprobación y ejecución como herramienta.
     * Finalmente con “herramienta desconocida” El fracaso, manifestado como“La tarjeta no se puede generar en absoluto.”.
     *
     * El nombre de la herramienta en el protocolo siempre es `[A-Za-z0-9_-]` Un identificador que contenga otros caracteres no puede ser un nombre de herramienta real.
     * Estas llamadas deben descartarse por completo y dejar ir al orquestador.“parámetros/excepción de llamada”La rama solicita al modelo que vuelva a intentarlo,
     * En lugar de utilizar basura como nombre de herramienta para ejecutar.
     */
    fun isValidName(name: String): Boolean {
        // Longitud máxima: el nombre real de la herramienta es mucho más corto que esto; descripciones demasiado largas se mezclan con el texto.
        val withinLimit = name.isNotBlank() && name.length <= 64
        return withinLimit && name.all { c ->
            (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '_' || c == '-'
        }
    }

    /**
     * Llamadas de herramientas de limpieza:
     *  - Los nombres de herramientas vacíos se descartan
     *  - El nombre de la herramienta está contaminado por el texto (incluido el chino)./espacio/puntuación) primero intente restaurar el nombre de la herramienta al final,
     *    No se puede restaurar y no coincide. [knownToolNames] solo tíralo
     *  - ilegal/Los argumentos vacíos intentan reparar; cuando no se pueda reparar, cámbielo a `{}`, sin perder la llamada
     *
     * @param knownToolNames El nombre de la herramienta que realmente estará expuesta al modelo en esta ronda. Sólo se puede restaurar la lista indicada;
     *   Pasar un conjunto vacío significa que la persona que llama no tiene una lista. No se hacen conjeturas en este momento y las llamadas ilegales aún se descartan.
     */
    fun sanitize(
        toolCalls: List<ToolCall>,
        knownToolNames: Set<String> = emptySet(),
    ): List<ToolCall> = toolCalls.mapNotNull { tc ->
        if (tc.name.isBlank()) {
            safeWarn("Descartar llamadas a herramientas:name esta vacio, id=${tc.id}")
            return@mapNotNull null
        }
        val effectiveName =
            if (isValidName(tc.name)) {
                tc.name
            } else {
                val salvaged = salvageName(tc.name, knownToolNames)
                if (salvaged == null) {
                    // No imprima el texto original del nombre (que puede contener contenido del usuario), solo proporcione la longitud y la categoría del primer carácter.
                    safeWarn(
                        "Descartar llamadas a herramientas:name No es un identificador legal(id=${tc.id}, len=${tc.name.length})" +
                            ", se sospecha que el modelo ha escrito el texto en el nombre de la función y no se puede restaurar.",
                    )
                    return@mapNotNull null
                }
                safeWarn("Nombre de la herramienta mezclado en texto,Restaurado(id=${tc.id}, len=${tc.name.length} -> $salvaged)")
                salvaged
            }
        val repaired = repairArguments(tc.arguments)
        if (repaired == null) {
            safeWarn(
                "El parámetro de herramienta no se puede analizar,Mantenga la llamada y baje a {}: " +
                    safeArgumentsForLog(effectiveName, tc.arguments),
            )
            tc.copy(name = effectiveName, arguments = "{}")
        } else {
            if (repaired != tc.arguments) {
                safeWarn(
                    "Parámetros de herramienta fijos: ${safeArgumentsForLog(effectiveName, tc.arguments)}" +
                        " repaired=[REDACTED]",
                )
            }
            tc.copy(name = effectiveName, arguments = repaired)
        }
    }

    /** Formularios de identificador permitidos al final de los nombres de las herramientas. */
    private val TRAILING_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_-]*$")

    /**
     * Obtenga el nombre real de la herramienta a partir del nombre contaminado por el texto.
     *
     * En el registro de medición real, el modelo enviará las líneas y los nombres de las funciones en una cadena:
     * `Sure, I can help with that. I'll create a card. Please hold on a moment.show_card`.
     * Toda la cadena contiene espacios y puntuación, pero**El último párrafo**Es el nombre de una herramienta genuina. Desechar toda la llamada equivale a dejar que el usuario
     * Se vuelve a intentar el mismo fallo repetidamente, por lo que se saca la cola y se reutiliza.
     *
     * Sólo se deben utilizar herramientas que puedan igualar la exposición actual. Cuando no se puede obtener la lista, se devolverá nulo: sin una lista, solo puede confiar en adivinar.
     * Y las palabras en inglés aparecerán en cualquier momento del texto ("please hold on a moment"), una suposición incorrecta ejecutará una palabra como herramienta.
     */
    internal fun salvageName(rawName: String, knownToolNames: Set<String>): String? {
        if (knownToolNames.isEmpty()) return null
        val candidate = TRAILING_IDENTIFIER.find(rawName.trim())?.value ?: return null
        if (candidate.length > 64) return null
        if (candidate !in knownToolNames) return null
        return candidate
    }

    /**
     * Diagnostic-only representation. Tool arguments can contain passwords, clipboard
     * contents, or text injected into another app; never put the raw JSON in logs.
     */
    internal fun safeArgumentsForLog(toolName: String, arguments: String): String =
        "tool=$toolName args=[REDACTED] length=${arguments.length}"

    private fun safeWarn(message: String) {
        runCatching { Logger.w("ToolCallSanitizer", message) }
    }

    /** arguments ¿Es legal? JSON(object/array/Ambos escalares son aceptables.). */
    fun isValidJson(arguments: String): Boolean = runCatching {
        Json.parseToJsonElement(arguments)
        true
    }.getOrDefault(false)

    /** Intente arreglar el truncamiento común en las estaciones de tránsito/Argumentos de empalme. */
    internal fun repairArguments(raw: String): String? {
        val text = raw.trim()
        if (text.isBlank()) return "{}"
        if (isValidJson(text)) return text

        // Procesar primero {"a":1}{"b":2}: Tome los objetos de nivel superior uno por uno y combínelos, este último cubriendo las claves con el mismo nombre.
        splitTopLevelObjects(text)?.takeIf { it.size > 1 }?.let { pieces ->
            val merged = pieces.mapNotNull { piece ->
                runCatching { Json.parseToJsonElement(piece) as? JsonObject }.getOrNull()
            }
            if (merged.size == pieces.size) {
                return mergeObjects(merged).toString()
            }
        }

        // Calcula la cadena cerrada y el contenedor cuando el modelo se trunca al final de los argumentos. Sólo objetivo/El valor al principio de la matriz está listo.
        if (text.startsWith("{") || text.startsWith("[")) {
            val balanced = balanceJson(text)
            if (balanced != null && isValidJson(balanced)) return balanced
        }
        return null
    }

    private fun mergeObjects(objects: List<JsonObject>): JsonObject = buildJsonObject {
        objects.forEach { obj -> obj.forEach { (key, value) -> put(key, value) } }
    }

    private fun splitTopLevelObjects(text: String): List<String>? {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        text.forEachIndexed { index, c ->
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') inString = false
                return@forEachIndexed
            }
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth < 0) return null
                    if (depth == 0 && start >= 0) {
                        parts += text.substring(start, index + 1)
                        start = -1
                    }
                }
            }
        }
        return if (depth == 0 && !inString && start < 0) parts else null
    }

    private fun balanceJson(text: String): String? {
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        text.forEach { c ->
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') inString = false
                return@forEach
            }
            when (c) {
                '"' -> inString = true
                '{' -> stack.addLast('}')
                '[' -> stack.addLast(']')
                '}', ']' -> if (stack.isEmpty() || stack.removeLast() != c) return null
            }
        }
        val suffix = buildString {
            if (inString) append('"')
            while (stack.isNotEmpty()) append(stack.removeLast())
        }
        return text + suffix
    }
}
