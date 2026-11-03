package io.zer0.memory.budget

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingType
import com.knuddels.jtokkit.api.IntArrayList
import io.zer0.ai.core.Model
import io.zer0.common.Logger

/**
 * LLM token Herramienta de presupuesto.
 *
 * reasoning Los modelos (con campos de pensamiento/razonamiento) deben estar fuera de los maxTokens visibles
 * Reserve tokens de pensamiento adicionales; de lo contrario, el modelo gastará su presupuesto en pensar y el resultado real se truncará.
 *
 * v1.55: Truncar para usar la codificación exacta jtokkit BPE (recuento de caracteres alternativo)/4 heurístico),
 * Recurrir a la heurística original cuando falla la inicialización.
 *
 * v1.79 (B-20): Agregado a la rebaja de memoria ensamblada.**Cultivo por prioridad de segmento**.
 * assembly el orden es facts → today → week → longterm(Ver
 * MemoryCompiler.assembleCompiledMarkdown), mientras que trunctrate trunca desde la cola
 * El primero que se descarta es el de largo plazo (el recuerdo más largo y valioso). Ahora, cuando el presupuesto excede el presupuesto, se divide en segmentos.
 * Priorice mantener completamente la primera sección de hechos y la última sección a largo plazo, y solo corte la mitad del día de hoy/semana y regístrelas en el registro.
 */
object LlmBudget {

    /** reasoning El número predeterminado de tokens de búfer del modelo. */
    const val DEFAULT_REASONING_HEADROOM_TOKENS = 1024

    private const val DEFAULT_TAG = "LlmBudget"

    @Volatile
    private var encoding: Encoding? = null

    private fun loadEncoding(): Encoding? {
        if (encoding != null) return encoding
        return runCatching {
            val e = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE)
            encoding = e
            e
        }.getOrNull()
    }

    /**
     * Agregue un búfer de razonamiento a los maxTokens visibles y luego fije el límite superior del modelo.
     */
    fun withReasoningHeadroom(visibleMaxTokens: Int, model: Model?): Int {
        if (model == null) return visibleMaxTokens
        // v2.x: Priorizar el uso de campos de capacidad del modelo — A juzgar solo por la subcadena de identificación se perderán nuevos modelos de inferencia
        // (Como por ejemplo deepseek-flash/v4-pro: directorio abilities=[TOOL, REASONING] Pero la identificación no contiene las siguientes palabras clave),
        // Como resultado, no se añade margen de pensamiento y el texto queda exprimido por el razonamiento.(finishReason=length corte presupuestario mínimo).
        val isReasoning = io.zer0.ai.core.ModelAbility.REASONING in model.abilities ||
            model.id.contains("o1", ignoreCase = true) ||
            model.id.contains("o3", ignoreCase = true) ||
            model.id.contains("o4", ignoreCase = true) ||
            model.id.contains("-thinking", ignoreCase = true) ||
            model.id.contains("reasoning", ignoreCase = true) ||
            model.id.contains("deepseek-r", ignoreCase = true)

        if (!isReasoning) return visibleMaxTokens

        val buffered = visibleMaxTokens + DEFAULT_REASONING_HEADROOM_TOKENS
        val cap = model.maxOutputTokens?.takeIf { it > 0 } ?: Int.MAX_VALUE
        return buffered.coerceAtMost(cap)
    }

    /**
     * v1.55 Coeficiente de retroceso heurístico(ingles ~4 personaje/token,El chino es más alto pero aproximado.).
     * Solo se usa si falla la inicialización de jtokkit.
     */
    private const val CHARS_PER_TOKEN = 4

    /** v1.78: tokenBudget límite superior,Evite que Int.MAX_VALUE provoque que maxChars se desborde a un número negativo. */
    private const val MAX_TOKEN_BUDGET = 1_000_000

    // ── B-20: Modelo de párrafo recortado por párrafo ─────────────────────────────────────────

    /** Una de las rebajas de memoria ensambladas. `##` segmento. */
    internal data class Segment(
        val heading: String, // en forma de "## hechos importantes"
        val body: String, // Duan Zhengwen(sin titulo)
    ) {
        fun render(): String = if (body.isEmpty()) heading else "$heading\n\n$body"
    }

    /**
     * Presione desde la rebaja de la memoria ensamblada `## ` Los títulos recortan los párrafos de nivel superior.
     *
     * confiar solo en"Cada párrafo comienza con una línea. `## ` de"estructura,El texto del título no está analizado —— Por lo tanto para
     * en/Títulos en diferentes idiomas en inglés (datos importantes/Key facts…) son robustos y el orden posicional
     * Mantener el orden de montaje(facts → today → week → longterm).
     * Si no es texto párrafos(Ninguno `## ` o solo un párrafo)Devolver lista vacía,Regreso al truncamiento completo del segmento por parte de la persona que llama.
     */
    internal fun splitSegments(markdown: String): List<Segment> {
        val normalized = markdown.replace("\r\n", "\n")
        val lines = normalized.split('\n')
        var currentHeading: String? = null
        val bodies = mutableListOf<Segment>()
        val pending = StringBuilder()

        fun flush() {
            val heading = currentHeading ?: return
            // B-20: usar trim('\n') en vez de trimEnd — El separador de línea en blanco después del título terminará con"Preámbulo \n"
            // Introducir texto,causando que el cuerpo tenga saltos de línea redundantes,render Se producen tres saltos de línea consecutivos.
            bodies.add(Segment(heading.trim(), pending.toString().trim('\n')))
            pending.setLength(0)
        }

        for (line in lines) {
            if (line.startsWith("## ")) {
                flush()
                currentHeading = line
            } else {
                pending.append(line).append('\n')
            }
        }
        flush()
        if (bodies.size < 2) return emptyList()
        return bodies
    }

    /**
     * Serializar los resultados del recorte de párrafos markdown(Deja una línea en blanco entre párrafos.,Todo termina con una nueva línea.),Formato y
     * MemoryCompiler.assembleCompiledMarkdown Sea consistente.
     */
    private fun renderSegments(segments: List<Segment>): String = segments.joinToString("\n\n", postfix = "\n") { it.render() }

    /**
     * Cuente el número de fichas en un fragmento de texto.(Recurrir al número de caracteres en caso de error/4). puro conteo,Sin truncamiento.
     */
    private fun tokensOf(text: String): Int {
        val enc = loadEncoding()
        if (enc != null) {
            val count = runCatching { enc.countTokens(text) }.getOrNull()
            if (count != null && count >= 0) return count
        }
        return (text.length / CHARS_PER_TOKEN) + 1 // Evite que 0 provoque que una cadena vacía se juzgue erróneamente por exceder el presupuesto
    }

    /**
     * B-20: Reducción de la memoria de recorte por prioridad de segmento.
     *
     * prioridad:primer párrafo(facts)con el último párrafo(longterm)como alta prioridad,mantener intacto;medio
     * today/week como baja prioridad,Descartar primero cuando el presupuesto sea bajo/apretar today/week,Y nunca te quedes en silencio
     * —— cada descarte/Recuerda apretarlo por un tiempo. [Logger.w].
     *
     * asignación presupuestaria:Mantenga intactos los segmentos de alta prioridad primero;El presupuesto restante se divide en partes iguales entre todos los segmentos de baja prioridad.,
     * Utilizado en cada párrafo. [truncateToTokenBudgetWhole] Apretar desde la cola. Si no hay espacio para un título en un párrafo
     * (El presupuesto es demasiado pequeño.),Luego, se descarta y registra toda la sección.
     *
     * Volver vacío/null La señal centinela retrocederá y truncará toda la sección.,Entonces el tipo de devolución es fijo. List<Segment>,
     * Pase"Volver a la lista original"Deje que la persona que llama recorra todo el camino truncado.
     *
     * [budget] <= 0 Considerado ilimitado.
     */
    internal fun truncateBySegments(segments: List<Segment>, budget: Int): List<Segment> {
        if (budget <= 0) return segments
        if (segments.size < 3) return segments // Sólo dos conjuntos de segmentos.,Ninguno"prescindible en el medio",Dáselo a toda la sección y truncalo.

        val highIdx = listOf(0, segments.size - 1) // facts, longterm
        val lowIdx = (1 until segments.size - 1).toList()

        // Costo general del segmento de alta prioridad(Serialización + líneas en blanco entre párrafos)
        val highCost = tokensOf(highIdx.joinToString("\n\n") { segments[it].render() })

        // El segmento de alta prioridad en sí está por encima del presupuesto:No se puede conservar por segmento,Devuelve la lista original y deja que la persona que llama trunca toda la sección
        if (highCost >= budget) {
            Logger.w(
                DEFAULT_TAG,
                "LlmBudget.truncateBySegments: segmento de alta prioridad(facts+longterm)Está por encima del presupuesto" +
                    "(necesitar $highCost token >= presupuesto $budget), Truncamiento de sección completa alternativa",
            )
            return segments
        }

        // El presupuesto restante se divide en partes iguales entre los segmentos de baja prioridad.
        val remaining = budget - highCost
        val perSection = (remaining / (lowIdx.size + 1)).coerceAtLeast(1)

        val result = mutableListOf<Segment>()
        for (idx in 0 until segments.size) {
            if (idx in highIdx) {
                // alta prioridad:Déjalo como está
                result.add(segments[idx])
            } else {
                // baja prioridad:Ajuste desde la cola dentro del presupuesto distribuido uniformemente
                val full = segments[idx].render()
                val trimmed = truncateToTokenBudgetWhole(full, perSection)
                if (trimmed != full) {
                    val inChars = full.length - trimmed.length
                    Logger.w(
                        DEFAULT_TAG,
                        "LlmBudget.truncateBySegments: Presupuesto insuficiente,Segmento de cultivo '${segments[idx].heading}' " +
                            "(drop $inChars personaje / ${full.length} personaje)",
                    )
                }
                if (trimmed.isBlank()) {
                    // Ni siquiera guardé el título.,Descartar el segmento directamente(registrado)
                    Logger.w(
                        DEFAULT_TAG,
                        "LlmBudget.truncateBySegments: Presupuesto insuficiente,descartar segmento '${segments[idx].heading}'",
                    )
                    continue
                }
                result.add(parseSingleSegment(trimmed, segments[idx].heading))
            }
        }
        return result
    }

    /**
     * poner el segmento único(puede haber sido recortado)desarmalo [Segment]. porque [truncateToTokenBudgetWhole] Reservar
     * cabeza,Siempre que el texto original termine con [originalHeading] El título se puede quitar directamente desde el principio.;De lo contrario siga las reglas generales.
     * Desmontar el primero. `## ` DE ACUERDO. Si no se puede dividir en párrafos, el texto completo se considerará texto principal.(No entra en conflicto con la estructura de párrafos anteriores.).
     */
    private fun parseSingleSegment(block: String, originalHeading: String): Segment {
        val trimmed = block.trim()
        if (trimmed == originalHeading) return Segment(originalHeading, "")
        if (trimmed.startsWith(originalHeading)) {
            val body = trimmed.removePrefix(originalHeading).removePrefix("\n\n").trimStart('\n')
            return Segment(originalHeading, body)
        }
        // Inicio de línea dividida universal `## `
        val nl = trimmed.indexOf('\n')
        if (trimmed.startsWith("## ") && nl > 0) {
            val heading = trimmed.substring(0, nl).trim()
            val body = trimmed.substring(nl).trimStart('\n')
            return Segment(heading, body)
        }
        return Segment(originalHeading, trimmed)
    }

    /**
     * Rebaja de memoria de recorte suave para [tokenBudget] token Dentro de.
     *
     * v1.55: Conteo preciso usando jtokkit BPE(Recurrir al número de caracteres en caso de error/4).
     * Cuando se exceda el presupuesto, intente finalizar en el salto de línea más cercano.,Evite los residuos de media hilera.
     * budget <= 0 Tratado como sin restricciones(Volver al texto original).
     *
     * v1.79 (B-20): Si el texto se agrupa en varios párrafos memory markdown,Priorizar por prioridad de segmento
     * Cultivo(Mantenga los primeros hechos y el último párrafo. longterm,Cultivo today/week);De lo contrario mantener
     * Todo el comportamiento original se trunca desde la cola. Sin firma para la persona que llama/Comportamiento disruptivo(El texto que no es un párrafo se comporta sin cambios).
     */
    fun truncateToTokenBudget(text: String, tokenBudget: Int): String {
        if (tokenBudget <= 0) return text
        if (text.isEmpty()) return text

        val segments = splitSegments(text)
        if (segments.size >= 3) {
            val result = truncateBySegments(segments, tokenBudget)
            if (result != segments) {
                return renderSegments(result)
            }
        }

        return truncateToTokenBudgetWhole(text, tokenBudget)
    }

    /**
     * v1.79: lógica de truncamiento completa original,Separar en métodos privados para"Recortar por segmento([truncateBySegments])"
     * respaldo con el párrafo completo([truncateToTokenBudget])compartido. Preservar el truncamiento exacto de BPE v1.55 vs.
     * v1.78 heurística alternativa,El comportamiento se mantiene sin cambios.
     */
    private fun truncateToTokenBudgetWhole(text: String, tokenBudget: Int): String {
        // v1.78: Evite que un tokenBudget excesivamente grande provoque maxChars = tokenBudget * 4 El desbordamiento es negativo
        val budget = tokenBudget.coerceAtMost(MAX_TOKEN_BUDGET)

        val enc = loadEncoding()
        if (enc != null) {
            val count = runCatching { enc.countTokens(text) }.getOrDefault(-1)
            if (count >= 0 && count <= budget) return text
            if (count > budget) {
                // Truncamiento exacto:encode → Toma el primer presupuesto. token → decode
                val decoded = runCatching {
                    val encoded = enc.encode(text)
                    val truncated = IntArrayList(budget)
                    for (i in 0 until budget) {
                        truncated.add(encoded.get(i))
                    }
                    enc.decode(truncated)
                }.getOrNull()
                if (decoded != null) {
                    val lastNl = decoded.lastIndexOf('\n')
                    val end = if (lastNl > decoded.length / 2) lastNl else decoded.length
                    return decoded.substring(0, end.coerceIn(0, decoded.length)).trimEnd() + "\n…(memory truncated)"
                }
            }
        }

        // revertir:heurística de recuento de caracteres — v1.78 Utilice Long para calcular la prevención de desbordamiento
        val maxChars = budget.toLong() * CHARS_PER_TOKEN
        if (text.length <= maxChars) return text
        val cutEnd = maxChars.coerceAtMost(text.length.toLong()).toInt()
        val cut = text.substring(0, cutEnd)
        val lastNl = cut.lastIndexOf('\n')
        val end = if (lastNl > cutEnd / 2) lastNl else cutEnd
        return text.substring(0, end.coerceIn(0, text.length)).trimEnd() + "\n…(memory truncated)"
    }
}
