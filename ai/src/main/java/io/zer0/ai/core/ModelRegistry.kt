package io.zer0.ai.core

/**
 * v1.97: Registro de capacidad del modelo — Derivar automáticamente modalidades/habilidades basadas en la coincidencia del token del nombre del modelo.
 *
 * Capacidades de coincidencia y derivación basadas en el token de nombre del modelo,idea central:
 *  - Cortar modelId en secuencia de token(Letras consecutivas/números consecutivos/caracteres individuales)
 *  - usar `defineModel { tokens(...); notTokens(...); exact(...); input(...); output(...); ability(...) }`
 *    Declarar reglas y capacidades coincidentes para cada familia de modelos
 *  - partido adoptado"subsecuencia ordenada"Estrategia(Permitir que los tokens se intercalen con otros. token),Seleccionar por puntuación del partido
 *  - Pases de capa de aplicación [lookupInputModalities] / [lookupOutputModalities] / [lookupAbilities] /
 *    [enrich] Cuatro API para obtener resultados de derivación
 *
 * con [ModelContextWindowRegistry] usados juntos:antigua capacidad de derivación,Este último deriva la ventana de contexto.
 *
 * Nota:Este registro sólo cubre familias de modelos comunes.;Devuelve una colección vacía en caso de error.(Depende de la persona que llama decidir la estrategia de encubrimiento.).
 *
 * Las funciones están convergiendo.:Este proyecto también existe. io.zer0.ai.registry.ModelRegistry(Derivación de la capacidad del modelo de adquisición /
 * Eliminación de prefijos/mejora de modelo,Incluyendo detección de etiquetado incorrecto en estaciones de transferencia, credibilidad de datos,grounding Esperando nuevas habilidades).
 * Para obtener nuevas capacidades, utilice la versión de registro.;Esta versión principal está reservada solo para compatibilidad con llamantes existentes.,No más expansión de nuevas capacidades.
 */
object ModelRegistry {

    // ──────────────────────────────────────────────────────────────
    // 1.Tokenizer:Cortar modelId en secuencia de token en minúsculas
    // ──────────────────────────────────────────────────────────────

    /**
     * Reglas de segmentación:Agrupa letras consecutivas en un párrafo.,Agrupar números consecutivos en un párrafo.,Otros personajes se convierten individualmente token;Todo en minúsculas.
     *
     * Ejemplo:
     *  - "gpt-4o" → ["gpt", "-", "4", "o"]
     *  - "gpt-5.1" → ["gpt", "-", "5", ".", "1"]
     *  - "claude-sonnet-4.5-20250929" → ["claude", "-", "sonnet", "-", "4", ".", "5", "-", "20250929"]
     *  - "o3-mini" → ["o", "-", "3", "-", "mini"]
     */
    private fun tokenize(modelId: String): List<String> {
        val tokens = mutableListOf<String>()
        val input = modelId.lowercase()
        var index = 0
        while (index < input.length) {
            val ch = input[index]
            when {
                ch.isLetter() -> {
                    val start = index
                    while (index < input.length && input[index].isLetter()) index++
                    tokens.add(input.substring(start, index))
                }
                ch.isDigit() -> {
                    val start = index
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

    // ──────────────────────────────────────────────────────────────
    // 2.TokenSpec:Primitiva de coincidencia de token único(literal"o"Conjunto / Regular)
    // ──────────────────────────────────────────────────────────────

    sealed interface TokenSpec {
        fun matches(token: String): Boolean
    }

    /** literal"o"colección,Pase "a|b|c" analizar gramaticalmente. */
    private data class TokenAlternatives(val options: Set<String>) : TokenSpec {
        override fun matches(token: String): Boolean = options.contains(token)
    }

    /** Partido regular. */
    private data class TokenRegex(val regex: Regex) : TokenSpec {
        override fun matches(token: String): Boolean = regex.matches(token)
    }

    private fun parseTokenSpec(spec: String): TokenSpec {
        val options = spec.split('|')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        return TokenAlternatives(options)
    }

    // ──────────────────────────────────────────────────────────────
    // Tres.TokenMatcher:Comparador de puntuación(null Indica que no hay coincidencia)
    // ──────────────────────────────────────────────────────────────

    /**
     * Familias de modelos compatibles(definición única o agrupación).
     * Pases de código externo [match] Determinar si modelId pertenece a esta familia,No hay necesidad de preocuparse por la implementación interna.
     */
    fun interface Matchable {
        fun match(modelId: String): Boolean
    }

    private const val EXACT_ID_BONUS = 1000

    internal interface TokenMatcher {
        fun score(modelId: String, tokens: List<String>): Int?
    }

    private object MatchNone : TokenMatcher {
        override fun score(modelId: String, tokens: List<String>): Int? = null
    }

    /** Toma múltiples coincidencias AND(Si alguno no coincide, el todo no coincide.). */
    private class AndMatcher(private val matchers: List<TokenMatcher>) : TokenMatcher {
        override fun score(modelId: String, tokens: List<String>): Int? {
            var total = 0
            for (matcher in matchers) {
                val s = matcher.score(modelId, tokens) ?: return null
                total += s
            }
            return total
        }
    }

    /** modelId exactamente igual(ignorar caso),puntuación = EXACT_ID_BONUS + tokens.size. */
    private class ExactIdMatcher(private val id: String) : TokenMatcher {
        override fun score(modelId: String, tokens: List<String>): Int? {
            return if (modelId.equals(id, ignoreCase = true)) EXACT_ID_BONUS + tokens.size else null
        }
    }

    /**
     * coincidencia de subsecuencia secuencial:tokens Cada una de las especificaciones debe aparecer en orden en spec,Pero se permite intercalar otras fichas en el medio.
     * puntuación de acierto = specs.size(Cuanto más larga y específica se dé prioridad a).
     */
    private class TokenSequenceMatcher(private val specs: List<TokenSpec>) : TokenMatcher {
        override fun score(modelId: String, tokens: List<String>): Int? {
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

    /** afirmación negativa:0 puntos si la secuencia no aparece,Si está presente, no coincide. */
    private class NotTokenSequenceMatcher(private val specs: List<TokenSpec>) : TokenMatcher {
        private val matcher = TokenSequenceMatcher(specs)
        override fun score(modelId: String, tokens: List<String>): Int? {
            return if (matcher.score(modelId, tokens) == null) 0 else null
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Cuatro.ModelDefinition + DSL Builder
    // ──────────────────────────────────────────────────────────────

    /**
     * Definición de familia de modelo único.
     * @param matcher comparador principal(Determinar si acierto + puntuación)
     * @param inputModalities Entrada modal al golpear(Como por ejemplo ["text"] / ["text","image"])
     * @param outputModalities Modal de salida al impactar
     * @param abilities Habilidad establecida al golpear(TOOL / REASONING)
     */
    internal class ModelDefinition(
        private val matcher: TokenMatcher,
        val inputModalities: Set<String>,
        val outputModalities: Set<String>,
        val abilities: Set<ModelAbility>,
    ) : Matchable {
        fun matchScore(modelId: String): Int? = matcher.score(modelId, modelId.lowercase().let { tokenize(it) })
        override fun match(modelId: String): Boolean = matchScore(modelId) != null
    }

    /** Agrupación familiar modelo(Si algún miembro acierta, se considera un acierto.). */
    internal class ModelGroup(internal val members: List<ModelDefinition>) : Matchable {
        override fun match(modelId: String): Boolean = members.any { it.match(modelId) }
    }

    /** DSL builder. */
    private class ModelDefinitionBuilder {
        private val matchers = mutableListOf<TokenMatcher>()
        private val inputModalities = mutableSetOf("text")
        private val outputModalities = mutableSetOf("text")
        private val abilities = mutableSetOf<ModelAbility>()

        /** Hacer coincidir la secuencia del token secuencialmente(literal"o"Gramática,Como por ejemplo "a|b|c"). */
        fun tokens(vararg specs: String) {
            matchers.add(TokenSequenceMatcher(specs.map { parseTokenSpec(it) }))
        }

        /** Hacer coincidir la secuencia del token secuencialmente(Soporte regular). */
        fun tokens(vararg specs: TokenSpec) {
            matchers.add(TokenSequenceMatcher(specs.toList()))
        }

        /** afirmación negativa:token La secuencia no aparece. */
        fun notTokens(vararg specs: String) {
            matchers.add(NotTokenSequenceMatcher(specs.map { parseTokenSpec(it) }))
        }

        /** modelId exactamente igual(ignorar caso). */
        fun exact(id: String) {
            matchers.add(ExactIdMatcher(id))
        }

        /** modo de entrada(Predeterminado ["text"]). */
        fun input(vararg modalities: String) {
            inputModalities.clear()
            inputModalities.addAll(modalities)
        }

        /** Modo de salida(Predeterminado ["text"]). */
        fun output(vararg modalities: String) {
            outputModalities.clear()
            outputModalities.addAll(modalities)
        }

        /** Habilidad(TOOL / REASONING). */
        fun ability(vararg abilities: ModelAbility) {
            this.abilities.addAll(abilities)
        }

        // ── Combinación conveniente ──
        fun visionInput() = input("text", "image")
        fun imageOutput() = output("text", "image")
        fun toolAbility() = ability(ModelAbility.TOOL)
        fun reasoningAbility() = ability(ModelAbility.REASONING)
        fun toolReasoningAbility() = ability(ModelAbility.TOOL, ModelAbility.REASONING)

        fun build(): ModelDefinition {
            val matcher = when {
                matchers.isEmpty() -> MatchNone
                matchers.size == 1 -> matchers.first()
                else -> AndMatcher(matchers.toList())
            }
            return ModelDefinition(
                matcher = matcher,
                inputModalities = inputModalities.toSet(),
                outputModalities = outputModalities.toSet(),
                abilities = abilities.toSet(),
            )
        }
    }

    private class ModelGroupBuilder {
        private val members = mutableListOf<ModelDefinition>()

        /** Agregar miembro,Acepta ModelDefinition o anidado ModelGroup(Expandir automáticamente). */
        fun add(vararg items: Matchable) {
            items.forEach { item ->
                when (item) {
                    is ModelDefinition -> members.add(item)
                    is ModelGroup -> members.addAll(item.members)
                    else -> error("add(Matchable) solo aceptar ModelDefinition/ModelGroup,recibido: $item")
                }
            }
        }
        fun build(): ModelGroup = ModelGroup(members.toList())
    }

    // ── DSL entrada ──
    private fun defineModel(block: ModelDefinitionBuilder.() -> Unit): ModelDefinition = ModelDefinitionBuilder().apply(block).build()

    private fun defineGroup(block: ModelGroupBuilder.() -> Unit): ModelGroup = ModelGroupBuilder().apply(block).build()

    /** Especificación de token normal. */
    fun tokenRegex(pattern: String): TokenSpec = TokenRegex(pattern.toRegex(RegexOption.IGNORE_CASE))

    // ──────────────────────────────────────────────────────────────
    // 5. Lista de definiciones de familia modelo(Cubre los modelos convencionales.)
    // ──────────────────────────────────────────────────────────────

    // ── OpenAI Serie ──
    private val GPT4O = defineModel {
        tokens("gpt", "4", "o")
        visionInput()
        toolAbility()
    }
    private val GPT_4_1 = defineModel {
        tokens("gpt", "4", "1")
        visionInput()
        toolAbility()
    }

    /** OpenAI o Modelo de inferencia serial(o1/o3/o4-mini espera). */
    private val _OPENAI_O_MODELS = defineModel {
        tokens(tokenRegex("^o$"), tokenRegex("^\\d+$"))
        visionInput()
        toolReasoningAbility()
    }
    val OPENAI_O_MODELS: Matchable = _OPENAI_O_MODELS
    private val GPT_OSS = defineModel {
        tokens("gpt", "oss")
        input("text")
        toolReasoningAbility()
    }
    private val GPT_5 = defineModel {
        tokens("gpt", "5")
        // Excluir versiones segmentadas con puntos decimales(Como por ejemplo gpt-5.1)y variantes de chat
        notTokens("gpt", "5", ".")
        notTokens("gpt", "5", "chat")
        visionInput()
        toolReasoningAbility()
    }
    private val GPT_5_1 = defineModel {
        tokens("gpt", "5", "1")
        visionInput()
        toolReasoningAbility()
    }
    private val GPT_5_2 = defineModel {
        tokens("gpt", "5", "2")
        visionInput()
        toolReasoningAbility()
    }
    private val GPT_5_3 = defineModel {
        tokens("gpt", "5", "3")
        visionInput()
        toolAbility()
    }
    private val GPT_5_4 = defineModel {
        tokens("gpt", "5", "4")
        visionInput()
        toolReasoningAbility()
    }
    private val GPT_5_4_MINI = defineModel {
        tokens("gpt", "5", "4", "mini")
        visionInput()
        toolReasoningAbility()
    }
    private val GPT_5_4_NANO = defineModel {
        tokens("gpt", "5", "4", "nano")
        visionInput()
        toolReasoningAbility()
    }
    private val GPT_5_5 = defineModel {
        tokens("gpt", "5", "5")
        visionInput()
        toolReasoningAbility()
    }
    private val GPT_5_6 = defineModel {
        tokens("gpt", "5", "6")
        visionInput()
        toolReasoningAbility()
    }
    val GPT_SERIES: Matchable = defineGroup {
        add(
            GPT4O, GPT_4_1, _OPENAI_O_MODELS, GPT_OSS,
            GPT_5, GPT_5_1, GPT_5_2, GPT_5_3, GPT_5_4, GPT_5_4_MINI, GPT_5_4_NANO,
            GPT_5_5, GPT_5_6,
        )
    }

    // ── Google Gemini Serie ──
    private val GEMINI_20_FLASH = defineModel {
        tokens("gemini", "2", "0", "flash")
        visionInput()
        toolAbility()
    }
    private val GEMINI_2_5_FLASH = defineModel {
        tokens("gemini", "2", "5", "flash")
        notTokens("image")
        visionInput()
        toolReasoningAbility()
    }
    private val GEMINI_2_5_PRO = defineModel {
        tokens("gemini", "2", "5", "pro")
        visionInput()
        toolReasoningAbility()
    }
    private val GEMINI_2_5_IMAGE = defineModel {
        tokens("gemini", "2", "5", "flash", "image")
        visionInput()
        imageOutput()
    }
    private val GEMINI_3_PRO_IMAGE = defineModel {
        tokens("gemini", "3", "pro", "image")
        visionInput()
        imageOutput()
    }
    private val GEMINI_NANO_BANANA = defineModel {
        tokens("nano", "banana")
        visionInput()
        imageOutput()
    }
    private val GEMINI_3_PRO = defineModel {
        tokens("gemini", "3", "pro")
        visionInput()
        toolReasoningAbility()
    }
    private val GEMINI_3_FLASH = defineModel {
        tokens("gemini", "3", "flash")
        visionInput()
        toolReasoningAbility()
    }
    private val GEMINI_3_1_PRO_PREVIEW = defineModel {
        tokens("gemini", "3", "1", "pro", "preview")
        visionInput()
        toolReasoningAbility()
    }
    private val GEMINI_3_1_FLASH_IMAGE = defineModel {
        tokens("gemini", "3", "1", "flash", "image")
        visionInput()
        imageOutput()
        reasoningAbility()
    }
    private val GEMINI_3_5 = defineModel {
        tokens("gemini", "3", "5")
        visionInput()
        toolReasoningAbility()
    }
    private val GEMINI_FLASH_LATEST = defineModel {
        exact("gemini-flash-latest")
        visionInput()
        toolReasoningAbility()
    }
    private val GEMINI_PRO_LATEST = defineModel {
        exact("gemini-pro-latest")
        visionInput()
        toolReasoningAbility()
    }
    private val GEMINI_LATEST = defineGroup {
        add(GEMINI_FLASH_LATEST, GEMINI_PRO_LATEST)
    }
    private val GEMINI_3_SERIES = defineGroup {
        add(GEMINI_3_PRO, GEMINI_3_FLASH, GEMINI_3_1_PRO_PREVIEW, GEMINI_3_5)
    }
    val GEMINI_SERIES: Matchable = defineGroup {
        add(GEMINI_20_FLASH, GEMINI_2_5_FLASH, GEMINI_2_5_PRO)
        add(GEMINI_3_SERIES, GEMINI_LATEST)
    }

    // ── Anthropic Claude Serie ──
    private val CLAUDE_SONNET_3_5 = defineModel {
        tokens("claude", "3", "5", "sonnet")
        visionInput()
        toolReasoningAbility()
    }
    private val CLAUDE_SONNET_3_7 = defineModel {
        tokens("claude", "3", "7", "sonnet")
        visionInput()
        toolReasoningAbility()
    }
    private val CLAUDE_4 = defineModel {
        tokens("claude", "4")
        visionInput()
        toolReasoningAbility()
    }
    private val CLAUDE_4_5 = defineModel {
        tokens("claude", "4", "5")
        visionInput()
        toolReasoningAbility()
    }
    private val CLAUDE_SONNET_4_6 = defineModel {
        tokens("claude", "sonnet", "4", "6")
        visionInput()
        toolReasoningAbility()
    }
    private val CLAUDE_OPUS_4_6 = defineModel {
        tokens("claude", "opus", "4", "6")
        visionInput()
        toolReasoningAbility()
    }
    private val CLAUDE_OPUS_4_7 = defineModel {
        tokens("claude", "opus", "4", "7")
        visionInput()
        toolReasoningAbility()
    }
    private val CLAUDE_OPUS_4_8 = defineModel {
        tokens("claude", "opus", "4", "8")
        visionInput()
        toolReasoningAbility()
    }
    val CLAUDE_SERIES: Matchable = defineGroup {
        add(
            CLAUDE_SONNET_3_5,
            CLAUDE_SONNET_3_7,
            CLAUDE_4,
            CLAUDE_4_5,
            CLAUDE_SONNET_4_6,
            CLAUDE_OPUS_4_6,
            CLAUDE_OPUS_4_7,
            CLAUDE_OPUS_4_8,
        )
    }

    // ── DeepSeek Serie ──
    private val DEEPSEEK_V3_MODEL = defineModel {
        tokens("deepseek", "v", "3")
        input("text")
        toolAbility()
    }
    private val DEEPSEEK_CHAT = defineModel {
        tokens("deepseek", "chat")
        input("text")
        toolAbility()
    }
    private val DEEPSEEK_R1_MODEL = defineModel {
        tokens("deepseek", "r", "1")
        input("text")
        toolReasoningAbility()
    }
    private val DEEPSEEK_REASONER = defineModel {
        tokens("deepseek", "reasoner")
        input("text")
        toolReasoningAbility()
    }
    private val DEEPSEEK_V3_1 = defineModel {
        tokens("deepseek", "v", "3", "1")
        input("text")
        toolReasoningAbility()
    }
    private val DEEPSEEK_V3_2 = defineModel {
        tokens("deepseek", "v", "3", "2")
        input("text")
        toolReasoningAbility()
    }
    private val DEEPSEEK_V4_FLASH = defineModel {
        tokens("deepseek", "v", "4", "flash")
        input("text")
        toolReasoningAbility()
    }
    private val DEEPSEEK_V4_PRO = defineModel {
        tokens("deepseek", "v", "4", "pro")
        input("text")
        toolReasoningAbility()
    }
    private val DEEPSEEK_OCR = defineModel {
        tokens("deepseek", "ocr")
        visionInput()
    }
    val DEEPSEEK_V3: Matchable = defineGroup { add(DEEPSEEK_V3_MODEL, DEEPSEEK_CHAT) }
    val DEEPSEEK_R1: Matchable = defineGroup { add(DEEPSEEK_R1_MODEL, DEEPSEEK_REASONER) }
    val DEEPSEEK_SERIES: Matchable = defineGroup {
        add(
            DEEPSEEK_V3_MODEL, DEEPSEEK_CHAT, DEEPSEEK_R1_MODEL, DEEPSEEK_REASONER,
            DEEPSEEK_V3_1, DEEPSEEK_V3_2, DEEPSEEK_V4_FLASH, DEEPSEEK_V4_PRO, DEEPSEEK_OCR,
        )
    }

    // ── Serie Tongyi Qwen ──
    private val QWEN_3 = defineModel {
        tokens("qwen", "3")
        input("text")
        toolReasoningAbility()
    }
    private val QWEN_3_5 = defineModel {
        tokens("qwen", "3", "5")
        visionInput()
        toolReasoningAbility()
    }
    private val QWEN_3_6 = defineModel {
        tokens("qwen", "3", "6")
        visionInput()
        toolReasoningAbility()
    }
    private val QWEN_3_7 = defineModel {
        tokens("qwen", "3", "7")
        visionInput()
        toolReasoningAbility()
    }

    /** Qwen-MT Modelo específico de traducción,Sin herramientas/Habilidades de razonamiento. */
    private val _QWEN_MT = defineModel {
        tokens("qwen", "mt")
        input("text")
    }
    val QWEN_MT: Matchable = _QWEN_MT
    val QWEN_SERIES: Matchable = defineGroup {
        add(QWEN_3, QWEN_3_5, QWEN_3_6, QWEN_3_7, _QWEN_MT)
    }

    // ── bolsa de frijol Doubao ──
    private val DOUBAO_1_6 = defineModel {
        tokens("doubao", "1", "6")
        visionInput()
        toolReasoningAbility()
    }
    private val DOUBAO_1_8 = defineModel {
        tokens("doubao", "1", "8")
        visionInput()
        toolReasoningAbility()
    }
    val DOUBAO_SERIES: Matchable = defineGroup { add(DOUBAO_1_6, DOUBAO_1_8) }

    // ── xAI Grok ──
    private val GROK_4 = defineModel {
        tokens("grok", "4")
        visionInput()
        toolReasoningAbility()
    }
    val GROK_SERIES: Matchable = defineGroup { add(GROK_4) }

    // ── lado oscuro de la luna Kimi ──
    private val KIMI_K2 = defineModel {
        tokens("kimi", "k", "2")
        input("text")
        toolReasoningAbility()
    }
    private val KIMI_K2_5 = defineModel {
        tokens("kimi", "k", "2", "5")
        visionInput()
        toolReasoningAbility()
    }
    private val KIMI_K2_6 = defineModel {
        tokens("kimi", "k", "2", "6")
        visionInput()
        toolReasoningAbility()
    }
    val KIMI_SERIES: Matchable = defineGroup { add(KIMI_K2, KIMI_K2_5, KIMI_K2_6) }

    // ── paso Step ──
    private val STEP_3 = defineModel {
        tokens("step", "3")
        visionInput()
        toolReasoningAbility()
    }
    private val STEP_3_7_FLASH = defineModel {
        tokens("step", "3", "7", "flash")
        visionInput()
        toolReasoningAbility()
    }
    val STEP_SERIES: Matchable = defineGroup { add(STEP_3, STEP_3_7_FLASH) }

    // ── Intern ──
    private val INTERN_S1 = defineModel {
        tokens("intern", "s", "1")
        visionInput()
        toolReasoningAbility()
    }
    val INTERN_SERIES: Matchable = defineGroup { add(INTERN_S1) }

    // ── espectro de sabiduría GLM ──
    private val GLM_4_5 = defineModel {
        tokens("glm", "4", "5")
        input("text")
        toolReasoningAbility()
    }
    private val GLM_4_6 = defineModel {
        tokens("glm", "4", "6")
        input("text")
        toolReasoningAbility()
    }
    private val GLM_4_7 = defineModel {
        tokens("glm", "4", "7")
        input("text")
        toolReasoningAbility()
    }
    private val GLM_5 = defineModel {
        tokens("glm", "5")
        input("text")
        toolReasoningAbility()
    }
    private val GLM_5_1 = defineModel {
        tokens("glm", "5", "1")
        input("text")
        toolReasoningAbility()
    }
    val GLM_SERIES: Matchable = defineGroup {
        add(GLM_4_5, GLM_4_6, GLM_4_7, GLM_5, GLM_5_1)
    }

    // ── MiniMax ──
    private val MINIMAX_M2 = defineModel {
        tokens("minimax", "m", "2")
        input("text")
        toolReasoningAbility()
    }
    private val MINIMAX_M2_5 = defineModel {
        tokens("minimax", "m", "2", "5")
        input("text")
        toolReasoningAbility()
    }
    private val MINIMAX_M2_7 = defineModel {
        tokens("minimax", "m", "2", "7")
        input("text")
        toolReasoningAbility()
    }
    private val MINIMAX_M3 = defineModel {
        tokens("minimax", "m", "3")
        visionInput()
        toolReasoningAbility()
    }
    val MINIMAX_SERIES: Matchable = defineGroup {
        add(MINIMAX_M2, MINIMAX_M2_5, MINIMAX_M2_7, MINIMAX_M3)
    }

    // ── Xiaomi MiMo ──
    private val XIAOMI_MIMO_V2 = defineModel {
        tokens("mimo", "v", "2")
        input("text")
        toolReasoningAbility()
    }
    private val XIAOMI_MIMO_V2_PRO = defineModel {
        tokens("mimo", "v", "2", "pro")
        input("text")
        toolReasoningAbility()
    }
    private val XIAOMI_MIMO_V2_5 = defineModel {
        tokens("mimo", "v", "2", "5")
        visionInput()
        toolReasoningAbility()
    }
    private val XIAOMI_MIMO_V2_5_PRO = defineModel {
        tokens("mimo", "v", "2", "5", "pro")
        input("text")
        toolReasoningAbility()
    }
    val MIMO_SERIES: Matchable = defineGroup {
        add(XIAOMI_MIMO_V2, XIAOMI_MIMO_V2_PRO, XIAOMI_MIMO_V2_5, XIAOMI_MIMO_V2_5_PRO)
    }

    // ──────────────────────────────────────────────────────────────
    // 6.ALL_MODELS lista maestra(resolveModels Atravesar en orden de declaración)
    // ──────────────────────────────────────────────────────────────

    private val ALL_MODELS: List<ModelDefinition> = listOf(
        // OpenAI
        GPT4O, GPT_4_1, _OPENAI_O_MODELS, GPT_OSS,
        GPT_5, GPT_5_1, GPT_5_2, GPT_5_3, GPT_5_4, GPT_5_4_MINI, GPT_5_4_NANO,
        GPT_5_5, GPT_5_6,
        // Gemini
        GEMINI_20_FLASH, GEMINI_2_5_FLASH, GEMINI_2_5_PRO, GEMINI_2_5_IMAGE,
        GEMINI_3_PRO_IMAGE, GEMINI_NANO_BANANA, GEMINI_3_PRO, GEMINI_3_FLASH,
        GEMINI_3_1_PRO_PREVIEW, GEMINI_3_1_FLASH_IMAGE, GEMINI_3_5,
        GEMINI_FLASH_LATEST, GEMINI_PRO_LATEST,
        // Claude
        CLAUDE_SONNET_3_5, CLAUDE_SONNET_3_7, CLAUDE_4, CLAUDE_4_5,
        CLAUDE_SONNET_4_6, CLAUDE_OPUS_4_6, CLAUDE_OPUS_4_7, CLAUDE_OPUS_4_8,
        // DeepSeek
        DEEPSEEK_V3_MODEL, DEEPSEEK_CHAT, DEEPSEEK_R1_MODEL, DEEPSEEK_REASONER,
        DEEPSEEK_V3_1, DEEPSEEK_V3_2, DEEPSEEK_V4_FLASH, DEEPSEEK_V4_PRO, DEEPSEEK_OCR,
        // Qwen
        QWEN_3, QWEN_3_5, QWEN_3_6, QWEN_3_7, _QWEN_MT,
        // bolsa de frijol / Grok / Kimi / Step / Intern / GLM / MiniMax / MiMo
        DOUBAO_1_6, DOUBAO_1_8, GROK_4,
        KIMI_K2, KIMI_K2_5, KIMI_K2_6,
        STEP_3, STEP_3_7_FLASH, INTERN_S1,
        GLM_4_5, GLM_4_6, GLM_4_7, GLM_5, GLM_5_1,
        MINIMAX_M2, MINIMAX_M2_5, MINIMAX_M2_7, MINIMAX_M3,
        XIAOMI_MIMO_V2, XIAOMI_MIMO_V2_PRO, XIAOMI_MIMO_V2_5, XIAOMI_MIMO_V2_5_PRO,
    )

    // ──────────────────────────────────────────────────────────────
    // 7. Entrada de análisis(Externamente API)
    // ──────────────────────────────────────────────────────────────

    /**
     * Obtenga la puntuación más alta entre todas las definiciones de aciertos(Capacidad de fusión en caso de empate.).
     * Si falla, se devuelve una lista vacía.
     */
    private fun resolveModels(modelId: String): List<ModelDefinition> {
        if (modelId.isBlank()) return emptyList()
        val normalized = stripAggregatorPrefix(modelId)
        val candidates = listOf(modelId, normalized).distinct()
        var bestScore: Int? = null
        val matches = mutableListOf<ModelDefinition>()
        for (candidate in candidates) {
            for (model in ALL_MODELS) {
                val score = model.matchScore(candidate) ?: continue
                when {
                    bestScore == null || score > bestScore -> {
                        bestScore = score
                        matches.clear()
                        matches.add(model)
                    }
                    score == bestScore && model !in matches -> matches.add(model)
                }
            }
        }
        return matches
    }

    /** La capacidad de hacer coincidir modelos después de eliminar los prefijos de agregación comunes, conservando el ID original solo para la solicitud en sí. */
    private fun stripAggregatorPrefix(modelId: String): String {
        val raw = modelId.trim().lowercase()
        // v2.6.1: Primero retire la etiqueta del paquete principal de la estación de tránsito.(Como por ejemplo `[ais]gemini-3.8-flash`),Luego procéselo de acuerdo con el prefijo de agregación.
        val deLabeled = stripChannelGroupLabels(raw)
        val prefixes = listOf(
            "openrouter/", "opencode-go/", "siliconflow/", "dashscope/",
            "deepseek-ai/", "zhipu/", "qwen/", "openai/", "anthropic/",
        )
        return prefixes.firstOrNull { deLabeled.startsWith(it) }?.let(deLabeled::removePrefix)
            ?: deLabeled.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: deLabeled
    }

    /**
     * Modalidad de entrada de consulta. Devuelve una colección vacía si falla(Depende de la persona que llama decidir,normalmente por defecto ["text"]).
     */
    fun lookupInputModalities(modelId: String): Set<String> = resolveModels(modelId).flatMap { it.inputModalities }.toSet()

    /**
     * Consulta el modo de salida. Si se falla, se devuelve una colección vacía.
     */
    fun lookupOutputModalities(modelId: String): Set<String> = resolveModels(modelId).flatMap { it.outputModalities }.toSet()

    /**
     * Capacidades de consulta. Si se falla, se devuelve una colección vacía.
     * Orden de devolución arreglada:TOOL en frente,REASONING Después.
     */
    fun lookupAbilities(modelId: String): Set<ModelAbility> {
        val abilities = resolveModels(modelId).flatMap { it.abilities }.toSet()
        return buildSet {
            if (ModelAbility.TOOL in abilities) add(ModelAbility.TOOL)
            if (ModelAbility.REASONING in abilities) add(ModelAbility.REASONING)
        }
    }

    /**
     * Determine si modelId admite entrada visual.
     */
    fun supportsVisionInput(modelId: String): Boolean = "image" in lookupInputModalities(modelId)

    /**
     * Determinar si modelId admite la salida de imágenes(modelo de dibujo).
     */
    fun supportsImageOutput(modelId: String): Boolean = "image" in lookupOutputModalities(modelId)

    /**
     * Determine si modelId admite llamadas a herramientas.
     */
    fun supportsToolCalling(modelId: String): Boolean = ModelAbility.TOOL in lookupAbilities(modelId)

    /**
     * Determine si modelId admite la cadena de inferencia.
     */
    fun supportsReasoning(modelId: String): Boolean = ModelAbility.REASONING in lookupAbilities(modelId)

    /**
     * Determinar si modelId coincide con la familia de modelos especificada(Como por ejemplo [GPT_SERIES] / [CLAUDE_SERIES] / [GEMINI_SERIES]).
     */
    fun matches(modelId: String, group: Matchable): Boolean {
        if (modelId.isBlank()) return false
        return group.match(modelId)
    }

    // ──────────────────────────────────────────────────────────────
    // 8.Model Conveniente enrich(Complete los campos faltantes,Mantener la configuración manual existente)
    // ──────────────────────────────────────────────────────────────

    /**
     * dado un [Model](Generalmente solo id/name),Completar automáticamente según el registro.:
     *  - [Model.abilities]:si está vacío,Completar desde el registro
     *  - [Model.inputModalities]:Si solo el valor predeterminado "text",Completar desde el registro(puede agregar "image")
     *  - [Model.outputModalities]:Si solo el valor predeterminado "text",Completar desde el registro(puede agregar "image"/"video")
     *  - [Model.supportsVision]:Si el registro determina que admite la entrada de imágenes,establecer en true
     *  - [Model.supportsVideo]:Si salidaModalidades contiene "video",establecer en true
     *  - [Model.contextWindow]:si está vacío,confiar [ModelContextWindowRegistry] Ten todo en cuenta
     *  - [Model.maxOutputTokens]:si está vacío,Priorizar desde [KnownModels] Ten todo en cuenta
     *
     * Prioridad de finalización(Cada campo se juzga de forma independiente.,El valor vacío solo baja):
     *  1. [Model] Ya posee campos(Usuario/Provider configuración explícita)
     *  2. [KnownModels.lookup] Completa todo(Contiene contextWindow / maxOutputTokens / modalities / abilities)
     *  3. [ModelRegistry] token inferencia + [ModelContextWindowRegistry] ventana contextual
     *
     * ha sido establecido explícitamente(No vacío/valor no predeterminado)los campos no se sobrescribirán,Evite romper los ajustes manuales del usuario.
     *
     * v1.0.8: Agregar soportesFinalización de video,Y ajustar la prioridad de las habilidades a Model > Registry > KnownModels,
     * con [io.zer0.ai.registry.ModelRegistry.enhanceModel] Sea consistente.
     */
    fun enrich(model: Model): Model {
        val id = model.id
        if (id.isBlank()) return model

        // Cobertura de segundo nivel:Consulte primero el diccionario KnownModels(Tabla de metainformación sin conexión)
        val knownInfo = KnownModels.lookup(id)

        val detectedAbilities = lookupAbilities(id)
        val detectedInput = lookupInputModalities(id)
        val detectedOutput = lookupOutputModalities(id)

        // abilities: Model Ya → Registry → KnownModels
        val newAbilities = when {
            model.abilities.isNotEmpty() -> model.abilities
            detectedAbilities.isNotEmpty() -> detectedAbilities
            !knownInfo?.abilities.isNullOrEmpty() -> knownInfo.abilities
            else -> emptySet()
        }

        // inputModalities: solo si es predeterminado ["text"] Sólo entonces intenta sobrescribir
        val newInput = if (model.inputModalities.size == 1 && "text" in model.inputModalities) {
            val knownInput = knownInfo?.inputModalities?.map { it.wireName }?.toSet()
            when {
                !knownInput.isNullOrEmpty() -> knownInput
                detectedInput.isNotEmpty() -> detectedInput
                else -> model.inputModalities
            }
        } else {
            model.inputModalities
        }

        // outputModalities: Igual que arriba
        val newOutput = if (model.outputModalities.size == 1 && "text" in model.outputModalities) {
            val knownOutput = knownInfo?.outputModalities?.map { it.wireName }?.toSet()
            when {
                !knownOutput.isNullOrEmpty() -> knownOutput
                detectedOutput.isNotEmpty() -> detectedOutput
                else -> model.outputModalities
            }
        } else {
            model.outputModalities
        }

        // supportsVision: Reservado si es explícitamente cierto,En caso contrario, completar según detección.
        val newSupportsVision = model.supportsVision || ("image" in newInput)
        // v1.0.8: Compatible con sincronización de vídeo con outputModalities
        val newSupportsVideo = model.supportsVideo || ("video" in newOutput)

        // contextWindow: Model Ya → KnownModels → ModelContextWindowRegistry
        val newContextWindow = model.contextWindow
            ?: knownInfo?.contextWindow
            ?: ModelContextWindowRegistry.lookup(id)

        // maxOutputTokens: Model Ya → KnownModels(No hay otras fuentes de información.)
        val newMaxOutputTokens = model.maxOutputTokens ?: knownInfo?.maxOutputTokens

        return model.copy(
            abilities = newAbilities,
            inputModalities = newInput,
            outputModalities = newOutput,
            supportsVision = newSupportsVision,
            supportsVideo = newSupportsVideo,
            contextWindow = newContextWindow,
            maxOutputTokens = newMaxOutputTokens,
        )
    }
}
