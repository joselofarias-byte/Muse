package io.zer0.ai.registry

import io.zer0.ai.core.KnownModels
import io.zer0.ai.core.KnownModels.Modality
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.ModelCatalogEntry
import io.zer0.ai.core.ModelContextWindowRegistry
import io.zer0.ai.core.ModelVerification

/**
 * Registro de capacidades del modelo.
 *
 * Definir las capacidades del modelo principal a través de reglas declarativas; cuando llega la identificación del modelo,
 * El registro devuelve capacidades analizadas/modal/Herramientas.
 *
 * El módulo superior lo utiliza para adaptar automáticamente los parámetros de la solicitud:
 *  - Ya sea para enviar herramientas (llamadas a funciones)
 *  - Si incluir imágenes (entrada visual)
 *  - Ya sea para habilitar la inferencia/pensar
 */
object ModelRegistry {

    // ─── OpenAI ───

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
    private val OPENAI_O_MODELS = defineModel {
        tokens(tokenRegex("^o$"), tokenRegex("^\\d+$"))
        visionInput()
        toolReasoningAbility()
    }

    // v1.0.8: GPT-5 Serie(buque insignia / mini / nano / codex espera)
    private val GPT_5 = defineModel {
        tokens("gpt", "5")
        notTokens(".")
        visionInput()
        toolReasoningAbility()
    }
    private val GPT_5_1 = defineModel {
        tokens("gpt", "5", "1")
        visionInput()
        toolReasoningAbility()
    }
    private val GPT_5_4 = defineModel {
        tokens("gpt", "5", "4")
        visionInput()
        toolReasoningAbility()
    }
    private val GPT_5_CODEX = defineModel {
        tokens("gpt", "5", "codex")
        toolReasoningAbility()
    }
    private val GPT_4_TURBO = defineModel {
        tokens("gpt", "4", "turbo")
        visionInput()
        toolAbility()
    }
    private val GPT_4 = defineModel {
        tokens("gpt", "4")
        notTokens("o")
        toolAbility()
    }

    // ─── Anthropic ───

    private val CLAUDE_3_5_SONNET = defineModel {
        tokens("claude", "3", "5", "sonnet")
        visionInput()
        toolAbility()
    }
    private val CLAUDE_3_5_HAIKU = defineModel {
        tokens("claude", "3", "5", "haiku")
        toolAbility()
    }
    private val CLAUDE_3_7 = defineModel {
        tokens("claude", "3", "7")
        visionInput()
        toolReasoningAbility()
    }
    private val CLAUDE_4 = defineModel {
        tokens("claude", "4")
        notTokens("5")
        visionInput()
        toolReasoningAbility()
    }
    private val CLAUDE_4_5 = defineModel {
        tokens("claude", "4", "5")
        visionInput()
        toolReasoningAbility()
    }
    private val CLAUDE_OPUS = defineModel {
        tokens("claude", "opus")
        visionInput()
        toolReasoningAbility()
    }
    private val CLAUDE_SONNET = defineModel {
        tokens("claude", "sonnet")
        visionInput()
        toolAbility()
    }

    // ─── Google Gemini ───

    private val GEMINI_2_0_FLASH = defineModel {
        tokens("gemini", "2", "0", "flash")
        visionInput()
        toolAbility()
        visionGrounding("gemini")
    }
    private val GEMINI_2_5_FLASH = defineModel {
        tokens("gemini", "2", "5", "flash")
        visionInput()
        toolReasoningAbility()
        visionGrounding("gemini")
    }
    private val GEMINI_2_5_PRO = defineModel {
        tokens("gemini", "2", "5", "pro")
        visionInput()
        toolReasoningAbility()
        visionGrounding("gemini")
    }
    private val GEMINI_1_5_PRO = defineModel {
        tokens("gemini", "1", "5", "pro")
        visionInput()
        toolAbility()
        visionGrounding("gemini")
    }
    private val GEMINI_1_5_FLASH = defineModel {
        tokens("gemini", "1", "5", "flash")
        visionInput()
        toolAbility()
        visionGrounding("gemini")
    }
    private val GEMINI_PRO = defineModel {
        tokens("gemini", "pro")
        notTokens("1")
        notTokens("2")
        notTokens("3")
        visionInput()
        toolAbility()
    }
    private val GEMINI_FLASH = defineModel {
        tokens("gemini", "flash")
        notTokens("1")
        notTokens("2")
        notTokens("3")
        visionInput()
        toolAbility()
    }

    // ─── DeepSeek ───

    private val DEEPSEEK_V3 = defineModel {
        tokens("deepseek", "v", "3")
        toolAbility()
    }
    private val DEEPSEEK_CHAT = defineModel {
        tokens("deepseek", "chat")
        toolAbility()
    }
    private val DEEPSEEK_R1 = defineModel {
        tokens("deepseek", "r", "1")
        reasoningAbility()
    }
    private val DEEPSEEK_REASONER = defineModel {
        tokens("deepseek", "reasoner")
        reasoningAbility()
    }

    // v1.0.8: DeepSeek V4 Serie(Contiene flash/pro y otras variantes)
    private val DEEPSEEK_V4 = defineModel {
        tokens("deepseek", "v", "4")
        toolReasoningAbility()
    }
    private val DEEPSEEK_OCR = defineModel {
        tokens("deepseek", "ocr")
        visionInput()
    }

    // ─── Qwen ───

    private val QWEN_MAX = defineModel {
        tokens("qwen", "max")
        toolAbility()
    }
    private val QWEN_PLUS = defineModel {
        tokens("qwen", "plus")
        toolAbility()
    }
    private val QWEN_TURBO = defineModel {
        tokens("qwen", "turbo")
        toolAbility()
    }
    private val QWEN_3_5 = defineModel {
        tokens("qwen", "3", "5")
        visionInput()
        toolReasoningAbility()
    }
    private val QWEN_VL = defineModel {
        tokens("qwen", "vl")
        visionInput()
        visionGrounding("qwen")
    }
    private val QWEN_QWQ = defineModel {
        tokens("qwq")
        reasoningAbility()
    }

    // v1.0.1 (P3): Qwen2-VL Serie(Modelo de visión de código abierto,Común en estaciones de transferencia)
    // v1.0.4: apoyo grounding(qwen Formato bbox_2d + point_2d)
    private val QWEN2_VL = defineModel {
        tokens("qwen", "2", "vl")
        visionInput()
        visionGrounding("qwen")
    }

    // ─── Otros ───

    private val GLM_4 = defineModel {
        tokens("glm", "4")
        toolAbility()
    }
    private val GLM_4_6 = defineModel {
        tokens("glm", "4", "6")
        toolReasoningAbility()
    }
    private val GLM_3 = defineModel {
        tokens("glm", "3")
        toolAbility()
    }

    // v1.0.1 (P3): GLM-4V Serie(Modelo visual de espectro inteligente,Común en estaciones de transferencia)
    private val GLM_4V = defineModel {
        tokens("glm", "4", "v")
        visionInput()
        toolAbility()
    }

    // v1.0.1 (P3): GLM-V Serie(Abreviatura del modelo visual del espectro de sabiduría,Como por ejemplo glm-v-4plus)
    private val GLM_V = defineModel {
        tokens("glm", "v")
        visionInput()
    }
    private val DOUBAO_PRO = defineModel {
        tokens("doubao", "pro")
        toolAbility()
    }

    // v1.0.1 (P3): Doubao Vision Serie(Modelo visual del motor del volcán.)
    private val DOUBAO_VISION = defineModel {
        tokens("doubao", "vision")
        visionInput()
        toolAbility()
    }
    private val MINIMAX = defineModel {
        tokens("minimax", "abab")
        toolAbility()
    }
    private val MINIMAX_M3 = defineModel {
        tokens("minimax", "m", "3")
        visionInput()
        toolAbility()
    }

    // v1.0.8: MiniMax M2.5 / M2.7 / M1 Serie
    private val MINIMAX_M2_5 = defineModel {
        tokens("minimax", "m", "2", "5")
        toolAbility()
    }
    private val MINIMAX_M2_7 = defineModel {
        tokens("minimax", "m", "2", "7")
        toolAbility()
    }
    private val MINIMAX_M1 = defineModel {
        tokens("minimax", "m", "1")
        toolAbility()
    }
    private val GROK = defineModel {
        tokens("grok")
        visionInput()
        toolAbility()
    }
    private val KIMI = defineModel {
        tokens("kimi", "moonshot")
        toolAbility()
    }

    // v1.0.8: Kimi K2 Serie(Como por ejemplo kimi-k2, kimi-k2.5, kimi-k2.7 espera)
    private val KIMI_K2 = defineModel {
        tokens("kimi", "k", "2")
        toolReasoningAbility()
    }

    // v1.0.53: Kimi K2.6 — versión multimodal(Visión+Entrada de texto),La puntuación de la regla exacta es mayor que KIMI_K2,Sin lesiones accidentales k2/k2.5
    private val KIMI_K2_6 = defineModel {
        tokens("kimi", "k", "2", "6")
        visionInput()
        toolReasoningAbility()
    }

    // v1.0.1 (P3): Kimi Vision(Moonshot modelo visual,Como por ejemplo moonshot-v1-8k-vision-preview)
    private val KIMI_VISION = defineModel {
        tokens("kimi", "vision")
        visionInput()
    }
    private val YI = defineModel {
        tokens("yi")
        visionInput()
    }
    private val LLAMA_3 = defineModel {
        tokens("llama", "3")
        toolAbility()
    }
    private val MISTRAL_LARGE = defineModel {
        tokens("mistral", "large")
        visionInput()
        toolAbility()
    }
    private val MISTRAL = defineModel {
        tokens("mistral")
        notTokens("large")
        toolAbility()
    }

    // v1.0.1 (P3): InternVL Serie(Modelo de visión de código abierto,OpenRouter/HuggingFace común)
    private val INTERN_VL = defineModel {
        tokens("intern", "vl")
        visionInput()
    }

    // v1.0.1 (P3): CogVLM Serie(Modelo visual de código abierto de Tsinghua)
    private val COG_VLM = defineModel {
        tokens("cog", "vlm")
        visionInput()
    }

    // v1.0.1 (P3): Step-VL Serie(Modelo visual de estrella de paso)
    private val STEP_VL = defineModel {
        tokens("step", "vl")
        visionInput()
    }

    // v1.0.1 (P3): LLaVA Serie(Modelo de visión de código abierto)
    private val LLAVA = defineModel {
        tokens("llava")
        visionInput()
    }

    // v1.0.1 (P3): Pixtral Serie(Mistral modelo visual)
    private val PIXTRAL = defineModel {
        tokens("pixtral")
        visionInput()
    }

    // ─── Toda la lista de modelos ───

    private val ALL_MODELS = listOf(
        GPT4O, GPT_4_1, OPENAI_O_MODELS, GPT_5, GPT_5_1, GPT_5_4, GPT_5_CODEX,
        GPT_4_TURBO, GPT_4,
        CLAUDE_3_5_SONNET, CLAUDE_3_5_HAIKU, CLAUDE_3_7, CLAUDE_4, CLAUDE_4_5,
        CLAUDE_OPUS, CLAUDE_SONNET,
        GEMINI_2_0_FLASH, GEMINI_2_5_FLASH, GEMINI_2_5_PRO,
        GEMINI_1_5_PRO, GEMINI_1_5_FLASH, GEMINI_PRO, GEMINI_FLASH,
        DEEPSEEK_V3, DEEPSEEK_CHAT, DEEPSEEK_R1, DEEPSEEK_REASONER, DEEPSEEK_V4, DEEPSEEK_OCR,
        QWEN_MAX, QWEN_PLUS, QWEN_TURBO, QWEN_3_5, QWEN_VL, QWEN2_VL, QWEN_QWQ,
        GLM_4_6, GLM_4, GLM_3, GLM_4V, GLM_V,
        DOUBAO_PRO, DOUBAO_VISION,
        MINIMAX, MINIMAX_M3, MINIMAX_M2_5, MINIMAX_M2_7, MINIMAX_M1,
        GROK, KIMI, KIMI_K2, KIMI_K2_6, KIMI_VISION, YI,
        LLAMA_3, MISTRAL_LARGE, MISTRAL,
        // v1.0.1 (P3): Código abierto/Modelos visuales comunes de estaciones de transferencia.
        INTERN_VL, COG_VLM, STEP_VL, LLAVA, PIXTRAL,
    )

    /**
     * La capacidad de resolver un ID de modelo determinado.
     * Devuelve las definiciones que mejor coinciden ordenadas por puntuación.
     *
     * v1.135: Primera coincidencia según el modelId original;Despegue la transferencia si falla/Prefijo de plataforma de agregación, inténtelo de nuevo,
     * resolver `opencode-go/deepseek-v3` Este tipo de identificación no puede identificar el problema.
     */
    fun resolveDefinitions(modelId: String): List<ModelDefinition> {
        val result = matchDefinitions(modelId)
        if (result.isNotEmpty()) return result
        val bare = stripAggregatorPrefix(modelId)
        if (bare != modelId) return matchDefinitions(bare)
        return emptyList()
    }

    private fun matchDefinitions(modelId: String): List<ModelDefinition> {
        var bestScore: Int? = null
        val matches = mutableListOf<ModelDefinition>()
        for (model in ALL_MODELS) {
            val score = model.matchScore(modelId) ?: continue
            when {
                bestScore == null || score > bestScore -> {
                    bestScore = score
                    matches.clear()
                    matches.add(model)
                }
                score == bestScore -> matches.add(model)
            }
        }
        return matches
    }

    /**
     * Franja de tránsito común/Prefijo de plataforma de agregación(Como openrouter/,opencode-go/).
     * Sin prefijo,luego devuelve el ultimo `/` la parte despues(Ten todo en cuenta).
     *
     * v1.0.1 (P3): Complemente los prefijos de estaciones de tránsito comunes en todo el país(siliconflow/,dashscope/,baichuan/,lingyi/ espera),
     *  Lógica consecutiva(substringAfterLast("/"))De hecho, puede manejar cualquier prefijo.,Sin embargo, una lista explícita evita
     *  Algunos tienen el prefijo de números de versión.(Como por ejemplo "accounts/fireworks/models/")Despojado por error.
     */
    private fun stripAggregatorPrefix(modelId: String): String {
        val raw = modelId.trim().lowercase()
        // v2.6.1: Primero retire la etiqueta del paquete principal de la estación de tránsito.(Como por ejemplo `[ais]gemini-3.8-flash`),Luego procéselo de acuerdo con el prefijo de agregación.
        val deLabeled = io.zer0.ai.core.stripChannelGroupLabels(raw)
        val prefixes = listOf(
            // Estación de agregación en el extranjero
            "openrouter/", "opencode-go/", "anthropic/", "google/", "openai/",
            "meta-llama/", "mistralai/", "nousresearch/", "deepinfra/", "togethercomputer/",
            "accounts/fireworks/models/", "presets/",
            // v1.0.1 (P3): Estación de transferencia nacional/estación de agregación
            "siliconflow/", "dashscope/", "baichuan/", "lingyi/", "lingyiwanwu/",
            "stepfun/", "zhipu/", "bigmodel/", "minimax/", "moonshot/",
            "doubao/", "volcengine/", "ark/", "hunyuan/", "qwen/", "aliyun/",
        )
        for (prefix in prefixes) {
            if (deLabeled.startsWith(prefix)) return deLabeled.removePrefix(prefix)
        }
        return deLabeled.substringAfterLast("/").takeIf { it.isNotBlank() } ?: deLabeled
    }

    /**
     * La capacidad de analizar la ID del modelo correspondiente.
     */
    fun resolveAbilities(modelId: String): Set<ModelAbility> = resolveDefinitions(modelId).flatMap { it.abilities }.toSet()

    /**
     * Analice el modo de entrada correspondiente al ID del modelo.
     */
    fun resolveInputModalities(modelId: String): Set<String> {
        val defs = resolveDefinitions(modelId)
        if (defs.isEmpty()) return setOf("text")
        return defs.flatMap { it.inputModalities }.toSet()
    }

    /**
     * Analice el modo de salida correspondiente al ID del modelo.
     */
    fun resolveOutputModalities(modelId: String): Set<String> {
        val defs = resolveDefinitions(modelId)
        if (defs.isEmpty()) return setOf("text")
        return defs.flatMap { it.outputModalities }.toSet()
    }

    /**
     * Compruebe si el modelo admite entrada visual.
     */
    fun supportsVision(modelId: String): Boolean = "image" in resolveInputModalities(modelId)

    /**
     * Compruebe si el modelo admite llamadas a herramientas.
     */
    fun supportsToolCalling(modelId: String): Boolean = ModelAbility.TOOL in resolveAbilities(modelId)

    /**
     * Compruebe si el modelo admite la inferencia./Pensar.
     */
    fun supportsReasoning(modelId: String): Boolean = ModelAbility.REASONING in resolveAbilities(modelId)

    /**
     * Capacidad mejorada para utilizar el análisis del registro. [Model].
     * Sólo se completan los campos que no se han configurado explícitamente.
     *
     * v1.0.8: Mejorar los enlaces finales — Cuando falla la regla del token,Recurrir a [KnownModels] con
     * [ModelContextWindowRegistry],completo contextWindow / maxOutputTokens / modalities / abilities.
     *
     * v1.0.8 (7.4): Detección de etiquetado incorrecto en la estación de transferencia — Incluso si el registro falla(defs esta vacio),También usado KnownModels
     *  Cubra las afirmaciones falsas de upstream como fuente autorizada:
     *  - declaración ascendente supportsVision=true Pero los KnownModels están marcados explícitamente como modelos de texto sin formato.(inputModalities={text})
     *    → Sujeto a modelos conocidos,Quitar imagen modal,supportsVision=false
     *  - declaración ascendente supportsVideo=true Pero KnownModels no está marcado video(outputModalities no contiene video)
     *    → Ignorar la declaración ascendente,supportsVideo=false
     *  Solo se activa cuando los KnownModels se declaran explícitamente(Evite coberturas erróneas de modelos desconocidos).
     */
    // ──────────────────────────────────────────────────────────────
    // Catálogo de capacidades de modelos(Doble vía 1)
    // ──────────────────────────────────────────────────────────────

    /** Catálogo de capacidades actualmente vigente;Iniciado por la capa de aplicación./Pasar después de actualizar [installCatalog] Inyectar. */
    @Volatile
    private var catalog: io.zer0.ai.core.ModelCatalog = io.zer0.ai.core.ModelCatalog()

    /** inyectar/Reemplazar directorio de capacidades(Seguridad del hilo:volatile Reemplazo completo). */
    fun installCatalog(newCatalog: io.zer0.ai.core.ModelCatalog) {
        catalog = newCatalog
        // Compatibilidad derivada([io.zer0.ai.core.ProviderCompatRules])Leer el mismo directorio:
        // Los perfiles de compatibilidad declarados explícitamente en los directorios tienen prioridad sobre las tablas de host codificadas.
        io.zer0.ai.core.ProviderCompatRules.installCatalog(newCatalog)
    }

    /**
     * v2.x(Comentarios del usuario n.º 3-1): Si el ID del proveedor es**Proveedor de lógica incorporado**.
     *
     * Base del juicio:Si la clave existe en la sección de proveedores de directorio. Proveedores creados por el usuario(Incluye estación de transferencia)de providerId
     * se genera en tiempo de ejecución(Como por ejemplo provider-xxxx), no en el directorio integrado → Devuelve falso.
     *
     * Propósito:El catálogo de capacidades es autorizado para los proveedores integrados.(Error de etiquetado aguas arriba corregible), para proveedores no integrados, es solo como referencia ——
     * Evite bloquear el mismo nombre de modelo conectado a la estación de transferencia (consulte la lógica de degradación de ModelCapabilityQuery.visionInput para obtener más detalles).
     */
    fun isBuiltInCatalogProvider(providerId: String?): Boolean {
        if (providerId.isNullOrBlank()) return false
        return catalog.providers.containsKey(providerId)
    }

    /** consulta de directorio:Primero presione proveedorId + modeloId para acertar con precisión,Luego regrese a la búsqueda global por nombre de modelo. */
    private fun catalogEntryFor(providerId: String, modelId: String): io.zer0.ai.core.ModelCatalogEntry? {
        if (modelId.isBlank()) return null
        val current = catalog
        if (current.providers.isEmpty()) return null
        if (providerId.isNotBlank()) {
            current.entryOf(providerId, modelId)?.let { return it }
        }
        // El ID de proveedor del proveedor creado por el usuario se genera en tiempo de ejecución.,No coincide con el nombre lógico del directorio → Busque globalmente por nombre de modelo.
        return current.entryByModelId(modelId)
    }

    /** Consultar entradas del modelo en el directorio activo actual,Proporciona límites de verificación previa de capacidad para reutilizar la misma fuente de verdad. */
    fun catalogEntryOf(modelId: String, providerId: String? = null): ModelCatalogEntry? = catalogEntryFor(providerId.orEmpty(), modelId)

    private fun resolveCatalogAbilities(model: Model, entry: ModelCatalogEntry): Set<ModelAbility> = buildSet {
        addAll(model.abilities)
        // toolUse De forma predeterminada, las capacidades de la herramienta se conservan según el contrato existente.,Sólo se desactiva si es explícitamente falso.
        when (entry.toolUse?.supportsTools) {
            false -> remove(ModelAbility.TOOL)
            true -> add(ModelAbility.TOOL)
            null -> Unit
        }
        when (entry.reasoning) {
            true -> add(ModelAbility.REASONING)
            false -> remove(ModelAbility.REASONING)
            null -> Unit
        }
    }

    private fun resolveCatalogInputModalities(model: Model, entry: ModelCatalogEntry): Set<String> = when (entry.image) {
        true -> setOf("text", "image")
        false -> setOf("text")
        null -> model.inputModalities
    }

    private fun resolveCatalogVerification(model: Model, contextWindow: Int?, maxOutputTokens: Int?): ModelVerification {
        val hasInvalidOutputLimit = contextWindow?.let { window ->
            window > 0 && maxOutputTokens?.let { limit -> limit > window } == true
        } == true
        return if (hasInvalidOutputLimit || model.verification == ModelVerification.SUSPICIOUS) {
            ModelVerification.SUSPICIOUS
        } else {
            ModelVerification.VERIFIED
        }
    }

    /** Aplicar campos de catálogo declarados al modelo.;visión no declarada/El campo de inferencia conserva los resultados del análisis existente. */
    private fun applyCatalogEntry(model: Model, entry: io.zer0.ai.core.ModelCatalogEntry): Model {
        val contextWindow = entry.context?.toInt() ?: model.contextWindow
        val maxOutputTokens = entry.maxOutput?.toInt() ?: model.maxOutputTokens
        return model.copy(
            contextWindow = contextWindow,
            maxOutputTokens = maxOutputTokens,
            abilities = resolveCatalogAbilities(model, entry),
            inputModalities = resolveCatalogInputModalities(model, entry),
            supportsVision = entry.image ?: model.supportsVision,
            verification = resolveCatalogVerification(model, contextWindow, maxOutputTokens),
        )
    }

    fun enhanceModel(model: Model): Model {
        // v2.6.1: Los modelos cuyas capacidades hayan sido editadas explícitamente por el usuario se devolverán directamente tal como están. —— No más registro/Documentación de especificaciones cubierta.
        // Reparación“Encienda el interruptor multimodal y vuelva a cambiarlo después de guardar.”: La configuración del usuario se almacena en ProviderConfig.models,
        // Cuando se vuelve a leer, la calibración de capacidad de esta función lo sobrescribe, lo que provoca que se pierdan las modificaciones del usuario.
        if (model.userEdited) return model
        val catalogEntry = catalogEntryFor(model.providerId, model.id)
        // Primero analice las especificaciones codificadas y KnownModels,Permitir que los campos no declarados en el catálogo tengan valores alternativos confiables.

        val defs = resolveDefinitions(model.id)
        val knownInfo = KnownModels.lookup(model.id)

        val resolvedAbilities = defs.flatMap { it.abilities }.toSet()
        val resolvedInput = defs.flatMap { it.inputModalities }.toSet()
        val resolvedOutput = defs.flatMap { it.outputModalities }.toSet()
        val resolvedTools = defs.flatMap { it.builtInTools }.toSet()
        // v1.0.4: Obtener el primer no nulo visionCapabilities(Gemini/Qwen-VL etc. modelo de puesta a tierra)
        val resolvedVisionCaps = defs.firstNotNullOfOrNull { it.visionCapabilities }

        // abilities: El modelo ya existe > registry > KnownModels
        val newAbilities = when {
            model.abilities.isNotEmpty() -> model.abilities
            resolvedAbilities.isNotEmpty() -> resolvedAbilities
            !knownInfo?.abilities.isNullOrEmpty() -> knownInfo.abilities
            else -> emptySet()
        }

        // inputModalities: Cuando el registro llega a un modelo conocido,Usar el registro como autoridad(Cubrir aguas arriba/Declaración de error de la estación de transferencia);
        // Mantenga la lógica original cuando falte(El modelo ya existe > KnownModels > Predeterminado).
        // v1.137: Corregir el modelo de texto de etiqueta incorrecto de la estación de tránsito(Como por ejemplo DeepSeek V4)para vision,
        // Un problema que provoca que se omitan las ayudas visuales, que las imágenes se envíen directamente a modelos de solo texto para activar un 400 o que se traten como en blanco.
        // v1.0.8 (7.4): fortalecer aún más — Incluso si la declaración ascendente de inputModalities contiene image(impuro text),
        //  Siempre que los KnownModels estén marcados explícitamente como texto sin formato(inputModalities={text}),Anule la declaración ascendente.
        //
        // issue #3.1: Excepciones a "la declaración ascendente tiene prioridad" —— aguas arriba**apoyo estatal explícito image** no cubierto.
        // anteriormente defs.isNotEmpty() Sobrescribir incondicionalmente la propia declaración del modelo con los resultados del registro.,conducir a la estación de transferencia
        // Un modelo del mismo nombre que sí admite multimodalidad.(Como por ejemplo deepseek-v4.1-flash)Determinado como texto sin formato mediante etiquetas integradas,
        // El reconocimiento de imágenes no está disponible en absoluto. El registro integrado es sólo una suposición del modelo principal.,La declaración ascendente es la capacidad real.,
        // En caso de conflicto entre ambos, prevalecerá el de aguas arriba.(Los usuarios aún pueden corregir manualmente las yuxtaposiciones en Editar capacidades del modelo userEdited).
        val declaredImage = "image" in model.inputModalities
        val newInput = when {
            defs.isNotEmpty() && !declaredImage -> resolvedInput
            model.inputModalities.size == 1 && "text" in model.inputModalities -> {
                val knownInput = knownInfo?.inputModalities?.map { it.wireName }?.toSet()
                when {
                    !knownInput.isNullOrEmpty() -> knownInput
                    else -> model.inputModalities
                }
            }
            // v1.0.8 (7.4): Detección de etiquetado incorrecto en la estación de transferencia — La declaración ascendente contiene una imagen, pero los KnownModels están marcados explícitamente como texto sin formato.
            else -> {
                val knownInput = knownInfo?.inputModalities?.map { it.wireName }?.toSet()
                if (!knownInput.isNullOrEmpty() && "image" !in knownInput) {
                    // KnownModels Declarar explícitamente que el modelo es texto plano.(no contiene image),Cubrir el etiquetado incorrecto aguas arriba
                    knownInput
                } else {
                    model.inputModalities
                }
            }
        }

        // outputModalities: Igual que la lógica de inputModalities
        // v1.0.8 (7.4): Detección de etiquetado incorrecto en la estación de transferencia — La declaración ascendente contiene video pero los KnownModels no están marcados video
        val newOutput = when {
            defs.isNotEmpty() -> resolvedOutput
            model.outputModalities.size == 1 && "text" in model.outputModalities -> {
                val knownOutput = knownInfo?.outputModalities?.map { it.wireName }?.toSet()
                when {
                    !knownOutput.isNullOrEmpty() -> knownOutput
                    else -> model.outputModalities
                }
            }
            // v1.0.8 (7.4): Detección de etiquetado incorrecto en la estación de transferencia — La declaración ascendente contiene vídeo pero KnownModels no. video
            else -> {
                val knownOutput = knownInfo?.outputModalities?.map { it.wireName }?.toSet()
                if (!knownOutput.isNullOrEmpty() && "video" !in knownOutput && "video" in model.outputModalities) {
                    // KnownModels Declarar explícitamente que la salida del modelo no contiene video,Ignorar el etiquetado incorrecto ascendente
                    knownOutput
                } else {
                    model.outputModalities
                }
            }
        }

        // contextWindow: El modelo ya existe > KnownModels > ModelContextWindowRegistry
        val newContextWindow = model.contextWindow
            ?: knownInfo?.contextWindow
            ?: ModelContextWindowRegistry.lookup(model.id)

        // maxOutputTokens: El modelo ya existe > KnownModels
        val newMaxOutputTokens = model.maxOutputTokens ?: knownInfo?.maxOutputTokens

        // v1.0.53: Calcular la credibilidad de los datos — VERIFIED / SUSPICIOUS / UNVERIFIED
        // - Acceda al documento de especificaciones local(knownInfo no nulo o defs no nulo)Comience con VERIFICADO
        // - Detectar campos de excepción declarados por upstream,Si hay una excepción, se degradará a SUSPICIOUS
        // - Entonces falta el documento local. UNVERIFIED(mantener el valor predeterminado)
        val hitLocalRegistry = knownInfo != null || defs.isNotEmpty()
        val verification = if (!hitLocalRegistry) {
            ModelVerification.UNVERIFIED
        } else {
            // Detección de anomalías:Declaración ascendente de campos que entran en conflicto con el documento de especificación local
            var suspicious = false
            // anormal1: La ventana de contexto ascendente es 0 o negativa(error obvio)
            if (model.contextWindow != null && model.contextWindow <= 0) suspicious = true
            // anormal2: declaración ascendente supportsVision=true Pero los KnownModels están marcados como texto sin formato.
            if (model.supportsVision && knownInfo?.inputModalities?.isNotEmpty() == true &&
                Modality.IMAGE !in knownInfo.inputModalities
            ) {
                suspicious = true
            }
            // anormal3: declaración ascendente supportsVideo=true Pero la salida de vídeo no está marcada por KnownModels.
            if (model.supportsVideo && knownInfo?.outputModalities?.isNotEmpty() == true &&
                Modality.VIDEO !in knownInfo.outputModalities
            ) {
                suspicious = true
            }
            // anormal4: declaración ascendente maxOutputTokens=0(error obvio)
            if (model.maxOutputTokens != null && model.maxOutputTokens <= 0) suspicious = true
            // anormal5: El límite superior de salida no puede ser mayor que la ventana de contexto; sólo marca sospechoso y no reescribe silenciosamente el directorio de usuarios.
            val hasInvalidOutputLimit = newContextWindow?.let { window ->
                window > 0 && newMaxOutputTokens?.let { limit -> limit > window } == true
            } == true
            if (hasInvalidOutputLimit) suspicious = true

            if (suspicious) ModelVerification.SUSPICIOUS else ModelVerification.VERIFIED
        }

        val resolvedModel = model.copy(
            abilities = newAbilities,
            inputModalities = newInput,
            outputModalities = newOutput,
            tools = if (model.tools.isEmpty()) resolvedTools else model.tools,
            // v1.137: registry Cuando accede a supportVision/supportsVideo se deriva completamente de newInput/newOutput,
            // No retener posibles etiquetas erróneas desde aguas arriba(Las estaciones repetidoras a menudo etiquetan erróneamente los modelos de texto plano como vision).
            // Mantenga la lógica original cuando falte(aguas arriba > newInput).
            // v1.0.8 (7.4): fortalecer aún más — Incluso si el registro falla(defs esta vacio),
            //  Si KnownModels indica explícitamente que no contiene image/video,También anula los soportesVision/supportsVideo mal etiquetados en sentido ascendente.
            supportsVision = when {
                defs.isNotEmpty() -> "image" in newInput
                // v1.0.8 (7.4): KnownModels Cuando se declara explícitamente como texto sin formato,Tomando soporte upstreamVision como false
                knownInfo?.inputModalities?.isNotEmpty() == true &&
                    "image" !in (knownInfo.inputModalities.map { it.wireName }.toSet()) -> false
                else -> model.supportsVision || "image" in newInput
            },
            supportsVideo = when {
                defs.isNotEmpty() -> "video" in newOutput
                // v1.0.8 (7.4): KnownModels Cuando se indica explícitamente que la salida no contiene vídeo,Ignorar aguas arriba supportsVideo
                knownInfo?.outputModalities?.isNotEmpty() == true &&
                    "video" !in (knownInfo.outputModalities.map { it.wireName }.toSet()) -> false
                else -> model.supportsVideo || "video" in newOutput
            },
            // v1.0.4: Valores resueltos con registro sólo si el modelo no está declarado explícitamente
            visionCapabilities = model.visionCapabilities ?: resolvedVisionCaps,
            contextWindow = newContextWindow,
            maxOutputTokens = newMaxOutputTokens,
            // v1.0.53: Complete la anotación de credibilidad de los datos,Para que la interfaz de usuario solicite al usuario
            verification = verification,
        )
        // Los campos de directorio explícitos tienen prioridad;La imagen/razonamiento que falta conserva la capacidad existente de analizar los resultados anteriores.
        return catalogEntry?.let { applyCatalogEntry(resolvedModel, it) } ?: resolvedModel
    }
}
