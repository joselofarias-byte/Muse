package io.zer0.ai.registry

import io.zer0.ai.core.BuiltInTool
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.VisionCapabilities

/**
 * Registro de capacidades del modelo DSL.
 *
 * Describir familias de modelos con reglas declarativas: cada definición contiene un conjunto de condiciones coincidentes y metainformación de capacidad.
 * Soporte:
 *  - Coincidencia de secuencia de token ordenada:tokens("gpt", "4", "o")
 *  - Coincidencia inversa:notTokens("mini")
 *  - Coincidencia exacta de identificación:exact("gpt-4o-2024-05-13")
 *  - Ficha normal:tokenRegex("^o$")
 *  - Alternativas:tokens("gpt|chatgpt")
 *
 * Uso:
 * ```kotlin
 * val GPT4O = defineModel {
 *     tokens("gpt", "4", "o")
 *     visionInput()
 *     toolAbility()
 * }
 * ```
 */

// --- publico API ---

interface ModelSelector {
    fun match(modelId: String): Boolean
}

class ModelDefinition(
    private val rule: ModelRule,
    val inputModalities: Set<String>,
    val outputModalities: Set<String>,
    val abilities: Set<ModelAbility>,
    val builtInTools: Set<BuiltInTool>,
    /** v1.0.4: Declaración visual de capacidad de conexión a tierra(null significa no declarado,No sobrescribir los valores del modelo existente). */
    val visionCapabilities: VisionCapabilities? = null,
) : ModelSelector {
    override fun match(modelId: String): Boolean {
        val tokens = tokenize(modelId)
        return rule.evaluate(modelId, tokens) != null
    }

    fun matchScore(modelId: String): Int? {
        val tokens = tokenize(modelId)
        return rule.evaluate(modelId, tokens)
    }

    internal fun matchScore(modelId: String, tokens: List<String>): Int? = rule.evaluate(modelId, tokens)
}

class ModelGroup internal constructor(
    private val members: List<ModelSelector>,
) : ModelSelector {
    override fun match(modelId: String): Boolean = members.any { it.match(modelId) }
}

fun defineModel(block: ModelDefinitionBuilder.() -> Unit): ModelDefinition = ModelDefinitionBuilder().apply(block).build()

fun defineGroup(block: ModelGroupBuilder.() -> Unit): ModelGroup = ModelGroupBuilder().apply(block).build()

fun tokenRegex(pattern: String): TokenSpec = RegexTokenSpec(pattern.toRegex(RegexOption.IGNORE_CASE))

// --- constructor ---

class ModelDefinitionBuilder {
    private val rules = mutableListOf<ModelRule>()
    private val inputModalities = mutableSetOf("text")
    private val outputModalities = mutableSetOf("text")
    private val abilities = mutableSetOf<ModelAbility>()
    private val builtInTools = mutableSetOf<BuiltInTool>()

    /** v1.0.4: Capacidad visual de puesta a tierra. */
    private var visionCapabilities: VisionCapabilities? = null

    fun tokens(vararg specs: String) {
        rules += OrderedTokenRule(specs.map(::parseTokenSpec))
    }

    fun tokens(vararg specs: TokenSpec) {
        rules += OrderedTokenRule(specs.toList())
    }

    fun notTokens(vararg specs: String) {
        rules += AbsentTokenRule(specs.map(::parseTokenSpec))
    }

    fun notTokens(vararg specs: TokenSpec) {
        rules += AbsentTokenRule(specs.toList())
    }

    fun exact(id: String) {
        rules += ExactIdRule(id)
    }

    fun visionInput() {
        inputModalities.add("image")
    }

    fun audioInput() {
        inputModalities.add("audio")
    }

    fun videoInput() {
        inputModalities.add("video")
    }

    fun imageOutput() {
        outputModalities.add("image")
    }

    fun toolAbility() {
        abilities.add(ModelAbility.TOOL)
    }

    fun reasoningAbility() {
        abilities.add(ModelAbility.REASONING)
    }

    fun toolReasoningAbility() {
        abilities.add(ModelAbility.TOOL)
        abilities.add(ModelAbility.REASONING)
    }

    fun builtInTool(vararg tools: BuiltInTool) {
        builtInTools.addAll(tools)
    }

    /**
     * v1.0.4: Declarar modelo para soportar visual grounding(Posicionamiento coordinado).
     *
     * @param outputFormat Formato de salida de coordenadas:"gemini"(yxyx)/"qwen"(bbox_2d)/"anchor"(visual_anchors)/"muse-box"(xyxy Predeterminado)
     */
    fun visionGrounding(outputFormat: String = "muse-box") {
        visionCapabilities = VisionCapabilities(grounding = true, outputFormat = outputFormat)
    }

    fun build(): ModelDefinition {
        val combined = when {
            rules.isEmpty() -> NeverMatchRule
            rules.size == 1 -> rules.first()
            else -> ConjunctionRule(rules.toList())
        }
        return ModelDefinition(
            rule = combined,
            inputModalities = inputModalities.toSet(),
            outputModalities = outputModalities.toSet(),
            abilities = abilities.toSet(),
            builtInTools = builtInTools.toSet(),
            visionCapabilities = visionCapabilities,
        )
    }
}

class ModelGroupBuilder {
    private val members = mutableListOf<ModelSelector>()
    fun add(vararg models: ModelSelector) {
        members.addAll(models)
    }
    fun build(): ModelGroup = ModelGroup(members.toList())
}

// --- Token normativo ---

sealed interface TokenSpec {
    fun matches(token: String): Boolean
}

private data class TokenAlternatives(val options: Set<String>) : TokenSpec {
    override fun matches(token: String): Boolean = options.contains(token)
}

private data class RegexTokenSpec(val regex: Regex) : TokenSpec {
    override fun matches(token: String): Boolean = regex.matches(token)
}

// --- Reglas coincidentes ---

interface ModelRule {
    fun evaluate(modelId: String, tokens: List<String>): Int?
}

private object NeverMatchRule : ModelRule {
    override fun evaluate(modelId: String, tokens: List<String>): Int? = null
}

private class ConjunctionRule(private val rules: List<ModelRule>) : ModelRule {
    override fun evaluate(modelId: String, tokens: List<String>): Int? {
        var total = 0
        for (rule in rules) {
            val score = rule.evaluate(modelId, tokens) ?: return null
            total += score
        }
        return total
    }
}

private class ExactIdRule(private val id: String) : ModelRule {
    override fun evaluate(modelId: String, tokens: List<String>): Int? =
        if (modelId.equals(id, ignoreCase = true)) EXACT_ID_BONUS + tokens.size else null
}

private class OrderedTokenRule(private val specs: List<TokenSpec>) : ModelRule {
    override fun evaluate(modelId: String, tokens: List<String>): Int? {
        if (specs.isEmpty()) return null
        var specIndex = 0
        for (token in tokens) {
            if (specs[specIndex].matches(token)) {
                specIndex++
                if (specIndex == specs.size) return specs.size
            }
        }
        return null
    }
}

private class AbsentTokenRule(private val specs: List<TokenSpec>) : ModelRule {
    private val inner = OrderedTokenRule(specs)
    override fun evaluate(modelId: String, tokens: List<String>): Int? = if (inner.evaluate(modelId, tokens) == null) 0 else null
}

// --- Función auxiliar ---

private fun parseTokenSpec(spec: String): TokenSpec {
    val options = spec.split('|')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()
    return TokenAlternatives(options)
}

private const val EXACT_ID_BONUS = 1000

/**
 * Dividir el ID del modelo en letras/numeros/Fragmento de símbolo.
 * por ejemplo "gpt-4o-mini" → ["gpt", "4", "o", "-", "mini"]
 */
internal fun tokenize(modelId: String): List<String> {
    val tokens = mutableListOf<String>()
    val input = modelId.lowercase()
    var index = 0
    while (index < input.length) {
        val ch = input[index]
        when {
            ch.isLetter() -> {
                val start = index
                index++
                while (index < input.length && input[index].isLetter()) index++
                tokens.add(input.substring(start, index))
            }
            ch.isDigit() -> {
                val start = index
                index++
                while (index < input.length && input[index].isDigit()) index++
                tokens.add(input.substring(start, index))
            }
            else -> {
                tokens.add(ch.toString())
                index++
            }
        }
    }
    return tokens
}
