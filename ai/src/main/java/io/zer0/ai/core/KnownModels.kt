package io.zer0.ai.core

/**
 * Diccionario de metainformación de modelos conocidos.(Obtén la primicia sin conexión).
 *
 * Mantener localmente una tabla de metainformación de los modelos principales.,
 * Permita que los escenarios fuera de línea o la interfaz ascendente /models completen las capacidades del modelo incluso cuando no se devuelva metainformación.
 *
 * con [ModelContextWindowRegistry] / [ModelRegistry] relación:
 *  - [ModelContextWindowRegistry] Sólo derivación contextWindow(campo único);
 *  - [ModelRegistry] Derivación mediante secuencia de tokens abilities / modalities(forma regular);
 *  - [KnownModels] Proporcionar metainformación más completa de forma codificada.(contextWindow + maxOutputTokens
 *    + modalities + abilities + pricing + description),Los tres se complementan.
 *
 * prioridad de llamada(por [ModelRegistry.enrich] coordinar):
 *  1. [Model] Ya posee campos(Usuario/Provider configuración explícita)
 *  2. [KnownModels.lookup] Completa todo
 *  3. [ModelRegistry] / [ModelContextWindowRegistry] lógica de inferencia
 *
 * fuente de datos:Documentos públicos de cada fabricante.(A partir de 2025-08). Números conservadores,Los campos no autorizados se dejan nulos.
 * Pricing unidad:USD per 1M tokens(prompt / completion).
 */
object KnownModels {

    // ──────────────────────────────────────────────────────────────
    // 1.Modality enumeración
    // ──────────────────────────────────────────────────────────────

    /**
     * Enumeración modal.wireName con [Model.inputModalities] / [Model.outputModalities]
     * Las cuerdas utilizadas en("text" / "image" / "audio" / "video" / "file").
     */
    enum class Modality(val wireName: String) {
        TEXT("text"),
        IMAGE("image"),
        AUDIO("audio"),
        VIDEO("video"),
        FILE("file"),
    }

    // ──────────────────────────────────────────────────────────────
    // 2.KnownModelInfo clase de datos
    // ──────────────────────────────────────────────────────────────

    /**
     * Metainformación sobre un único modelo conocido.
     *
     * Todos los campos pueden estar vacíos,Quedan campos sin confirmar null,Depende de la persona que llama decidir si utilizarlo.
     *
     * @param contextWindow tamaño de la ventana de contexto(token numero)
     * @param maxOutputTokens Número máximo de tokens emitidos a la vez
     * @param inputModalities Colección modal de entrada(Un conjunto vacío significa no declarado)
     * @param outputModalities Colección modal de salida
     * @param abilities conjunto de capacidades(TOOL / REASONING)
     * @param pricingPromptPer1M prompt precio final(USD / 1M tokens)
     * @param pricingCompletionPer1M completion precio final(USD / 1M tokens)
     * @param description breve descripción(Opcional)
     */
    data class KnownModelInfo(
        val contextWindow: Int? = null,
        val maxOutputTokens: Int? = null,
        val inputModalities: Set<Modality> = emptySet(),
        val outputModalities: Set<Modality> = emptySet(),
        val abilities: Set<ModelAbility> = emptySet(),
        val pricingPromptPer1M: Double? = null,
        val pricingCompletionPer1M: Double? = null,
        /**
         * v2.5.3 (P4-3): El precio unitario de la entrada de acierto de caché (USD / 1M tokens).
         * null = Desconocido o indistinguible (basado en el precio normal de los insumos).
         */
        val pricingCachedInputPer1M: Double? = null,
        /**
         * v2.5.3 (P4-3): El precio unitario de la escritura en caché (USD / 1M tokens).
         * null = Desconocido o este proveedor no cobra una tarifa de escritura en caché (Anthropic Se cobra tarifa de escritura,OpenAI No aceptado).
         */
        val pricingCacheWritePer1M: Double? = null,
        val description: String? = null,
    )

    // ──────────────────────────────────────────────────────────────
    // 3. Constantes internas y herramientas.
    // ──────────────────────────────────────────────────────────────

    private val TEXT_IN: Set<Modality> = setOf(Modality.TEXT)
    private val VISION_IN: Set<Modality> = setOf(Modality.TEXT, Modality.IMAGE)
    private val TEXT_OUT: Set<Modality> = setOf(Modality.TEXT)

    private val TOOL: ModelAbility = ModelAbility.TOOL
    private val REASONING: ModelAbility = ModelAbility.REASONING
    private val TOOL_ONLY: Set<ModelAbility> = setOf(TOOL)
    private val REASONING_ONLY: Set<ModelAbility> = setOf(REASONING)

    /**
     * provider+modelId llave de unión(Todo en minúsculas).
     */
    private data class ProviderKey(val provider: String, val modelId: String)

    /**
     * Transferencia parcial/La plataforma de agregación agregará el prefijo del proveedor antes del ID del modelo.(Como openrouter/,openai/),
     * Después de despegarlo, verifique el ID del modelo principal.
     */
    private val KNOWN_PREFIXES: List<String> = listOf(
        "openrouter/",
        "opencode-go/",
        "anthropic/",
        "google/",
        "openai/",
        "meta-llama/",
        "mistralai/",
        "nousresearch/",
        "deepinfra/",
        "togethercomputer/",
        "accounts/fireworks/models/",
        "presets/",
    )

    private fun stripPrefix(id: String): String {
        for (prefix in KNOWN_PREFIXES) {
            if (id.startsWith(prefix)) return id.removePrefix(prefix)
        }
        return id
    }

    /** Elimine el prefijo de la plataforma de agregación y conviértalo a minúsculas para juzgar por la familia de modelos (como Anthropic's `claude` prefijo). */
    internal fun normalizeModelIdForFamily(modelId: String): String = stripPrefix(modelId.trim().lowercase())

    // ──────────────────────────────────────────────────────────────
    // 4. Diccionario de modelos preestablecidos(Todos están codificados con modelId en minúsculas)
    // ──────────────────────────────────────────────────────────────

    private val known: Map<String, KnownModelInfo> = buildMap {
        // ── OpenAI Serie ──
        put(
            "gpt-4o",
            KnownModelInfo(
                contextWindow = 128000,
                maxOutputTokens = 16384,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                pricingPromptPer1M = 2.50,
                pricingCompletionPer1M = 10.00,
                description = "OpenAI GPT-4o Buque insignia multimodal",
            ),
        )
        put(
            "gpt-4o-mini",
            KnownModelInfo(
                contextWindow = 128000,
                maxOutputTokens = 16384,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                pricingPromptPer1M = 0.15,
                pricingCompletionPer1M = 0.60,
                description = "OpenAI GPT-4o mini Versión ligera",
            ),
        )
        put(
            "gpt-4-turbo",
            KnownModelInfo(
                contextWindow = 128000,
                maxOutputTokens = 4096,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                pricingPromptPer1M = 10.00,
                pricingCompletionPer1M = 30.00,
                description = "OpenAI GPT-4 Turbo",
            ),
        )
        put(
            "gpt-4",
            KnownModelInfo(
                contextWindow = 8192,
                maxOutputTokens = 4096,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                pricingPromptPer1M = 30.00,
                pricingCompletionPer1M = 60.00,
                description = "OpenAI GPT-4 versión original(8K)",
            ),
        )
        put(
            "gpt-3.5-turbo",
            KnownModelInfo(
                contextWindow = 16385,
                maxOutputTokens = 4096,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                pricingPromptPer1M = 0.50,
                pricingCompletionPer1M = 1.50,
                description = "OpenAI GPT-3.5 Turbo",
            ),
        )
        put(
            "o1",
            KnownModelInfo(
                contextWindow = 200000,
                maxOutputTokens = 100000,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                pricingPromptPer1M = 15.00,
                pricingCompletionPer1M = 60.00,
                description = "OpenAI o1 modelo de inferencia",
            ),
        )
        put(
            "o1-mini",
            KnownModelInfo(
                contextWindow = 128000,
                maxOutputTokens = 65536,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = REASONING_ONLY,
                pricingPromptPer1M = 3.00,
                pricingCompletionPer1M = 12.00,
                description = "OpenAI o1-mini modelo de inferencia",
            ),
        )
        put(
            "o1-pro",
            KnownModelInfo(
                contextWindow = 200000,
                maxOutputTokens = 100000,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                pricingPromptPer1M = 150.00,
                pricingCompletionPer1M = 600.00,
                description = "OpenAI o1-pro Inferencia de alto rendimiento",
            ),
        )
        put(
            "o3",
            KnownModelInfo(
                contextWindow = 200000,
                maxOutputTokens = 100000,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                pricingPromptPer1M = 10.00,
                pricingCompletionPer1M = 40.00,
                description = "OpenAI o3 modelo de inferencia",
            ),
        )
        put(
            "o3-mini",
            KnownModelInfo(
                contextWindow = 200000,
                maxOutputTokens = 100000,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                pricingPromptPer1M = 1.10,
                pricingCompletionPer1M = 4.40,
                description = "OpenAI o3-mini modelo de inferencia",
            ),
        )
        put(
            "o4-mini",
            KnownModelInfo(
                contextWindow = 200000,
                maxOutputTokens = 100000,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                pricingPromptPer1M = 1.10,
                pricingCompletionPer1M = 4.40,
                description = "OpenAI o4-mini modelo de inferencia",
            ),
        )
        put(
            "gpt-5",
            KnownModelInfo(
                contextWindow = 400000,
                maxOutputTokens = 128000,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                pricingPromptPer1M = 5.00,
                pricingCompletionPer1M = 15.00,
                description = "OpenAI GPT-5 buque insignia",
            ),
        )
        put(
            "gpt-5-mini",
            KnownModelInfo(
                contextWindow = 400000,
                maxOutputTokens = 128000,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                pricingPromptPer1M = 0.50,
                pricingCompletionPer1M = 1.50,
                description = "OpenAI GPT-5 mini",
            ),
        )
        // v1.0.6: Nuevo GPT-5.1 / Codex
        put(
            "gpt-5.1",
            KnownModelInfo(
                contextWindow = 400000,
                maxOutputTokens = 128000,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                pricingPromptPer1M = 5.00,
                pricingCompletionPer1M = 15.00,
                description = "OpenAI GPT-5.1",
            ),
        )
        put(
            "gpt-5-codex",
            KnownModelInfo(
                contextWindow = 400000,
                maxOutputTokens = 128000,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                description = "OpenAI GPT-5 Codex modelo de código",
            ),
        )

        // ── Anthropic Claude Serie ──
        put(
            "claude-3-opus",
            KnownModelInfo(
                contextWindow = 200000,
                maxOutputTokens = 4096,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                pricingPromptPer1M = 15.00,
                pricingCompletionPer1M = 75.00,
                description = "Anthropic Claude 3 Opus",
            ),
        )
        put(
            "claude-3-sonnet",
            KnownModelInfo(
                contextWindow = 200000,
                maxOutputTokens = 4096,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                pricingPromptPer1M = 3.00,
                pricingCompletionPer1M = 15.00,
                description = "Anthropic Claude 3 Sonnet",
            ),
        )
        put(
            "claude-3-haiku",
            KnownModelInfo(
                contextWindow = 200000,
                maxOutputTokens = 4096,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                pricingPromptPer1M = 0.25,
                pricingCompletionPer1M = 1.25,
                description = "Anthropic Claude 3 Haiku",
            ),
        )
        put(
            "claude-3.5-sonnet",
            KnownModelInfo(
                contextWindow = 200000,
                maxOutputTokens = 8192,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                pricingPromptPer1M = 3.00,
                pricingCompletionPer1M = 15.00,
                description = "Anthropic Claude 3.5 Sonnet",
            ),
        )
        put(
            "claude-3.5-haiku",
            KnownModelInfo(
                contextWindow = 200000,
                maxOutputTokens = 8192,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                pricingPromptPer1M = 0.80,
                pricingCompletionPer1M = 4.00,
                description = "Anthropic Claude 3.5 Haiku",
            ),
        )
        put(
            "claude-3.7-sonnet",
            KnownModelInfo(
                contextWindow = 200000,
                maxOutputTokens = 64000,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                pricingPromptPer1M = 3.00,
                pricingCompletionPer1M = 15.00,
                description = "Anthropic Claude 3.7 Sonnet(Expande tu pensamiento)",
            ),
        )
        put(
            "claude-sonnet-4",
            KnownModelInfo(
                contextWindow = 200000,
                maxOutputTokens = 64000,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                pricingPromptPer1M = 3.00,
                pricingCompletionPer1M = 15.00,
                description = "Anthropic Claude Sonnet 4",
            ),
        )
        put(
            "claude-opus-4",
            KnownModelInfo(
                contextWindow = 200000,
                maxOutputTokens = 32000,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                pricingPromptPer1M = 15.00,
                pricingCompletionPer1M = 75.00,
                description = "Anthropic Claude Opus 4",
            ),
        )
        // v1.0.6: Nueva serie Claude 4.5 / 4.1
        put(
            "claude-sonnet-4-5",
            KnownModelInfo(
                contextWindow = 200000,
                maxOutputTokens = 64000,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                pricingPromptPer1M = 3.00,
                pricingCompletionPer1M = 15.00,
                description = "Anthropic Claude Sonnet 4.5",
            ),
        )
        put(
            "claude-opus-4-1",
            KnownModelInfo(
                contextWindow = 200000,
                maxOutputTokens = 32000,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                pricingPromptPer1M = 15.00,
                pricingCompletionPer1M = 75.00,
                description = "Anthropic Claude Opus 4.1",
            ),
        )
        put(
            "claude-haiku-4-5",
            KnownModelInfo(
                contextWindow = 200000,
                maxOutputTokens = 16384,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                pricingPromptPer1M = 1.00,
                pricingCompletionPer1M = 5.00,
                description = "Anthropic Claude Haiku 4.5",
            ),
        )

        // ── Google Gemini Serie ──
        put(
            "gemini-1.5-pro",
            KnownModelInfo(
                contextWindow = 2000000,
                maxOutputTokens = 8192,
                inputModalities = setOf(Modality.TEXT, Modality.IMAGE, Modality.AUDIO, Modality.VIDEO, Modality.FILE),
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                pricingPromptPer1M = 1.25,
                pricingCompletionPer1M = 5.00,
                description = "Google Gemini 1.5 Pro(2M contexto)",
            ),
        )
        put(
            "gemini-1.5-flash",
            KnownModelInfo(
                contextWindow = 1000000,
                maxOutputTokens = 8192,
                inputModalities = setOf(Modality.TEXT, Modality.IMAGE, Modality.AUDIO, Modality.VIDEO, Modality.FILE),
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                pricingPromptPer1M = 0.075,
                pricingCompletionPer1M = 0.30,
                description = "Google Gemini 1.5 Flash",
            ),
        )
        put(
            "gemini-2.0-flash",
            KnownModelInfo(
                contextWindow = 1000000,
                maxOutputTokens = 8192,
                inputModalities = setOf(Modality.TEXT, Modality.IMAGE, Modality.AUDIO, Modality.VIDEO, Modality.FILE),
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                pricingPromptPer1M = 0.10,
                pricingCompletionPer1M = 0.40,
                description = "Google Gemini 2.0 Flash",
            ),
        )
        put(
            "gemini-2.5-pro",
            KnownModelInfo(
                contextWindow = 2000000,
                maxOutputTokens = 8192,
                inputModalities = setOf(Modality.TEXT, Modality.IMAGE, Modality.AUDIO, Modality.VIDEO, Modality.FILE),
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                pricingPromptPer1M = 1.25,
                pricingCompletionPer1M = 10.00,
                description = "Google Gemini 2.5 Pro",
            ),
        )
        put(
            "gemini-2.5-flash",
            KnownModelInfo(
                contextWindow = 1000000,
                maxOutputTokens = 65536,
                inputModalities = setOf(Modality.TEXT, Modality.IMAGE, Modality.AUDIO, Modality.VIDEO, Modality.FILE),
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                pricingPromptPer1M = 0.30,
                pricingCompletionPer1M = 2.50,
                description = "Google Gemini 2.5 Flash",
            ),
        )

        // ── DeepSeek Serie ──
        put(
            "deepseek-chat",
            KnownModelInfo(
                contextWindow = 64000,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                pricingPromptPer1M = 0.27,
                pricingCompletionPer1M = 1.10,
                description = "DeepSeek V3 conversación general",
            ),
        )
        put(
            "deepseek-reasoner",
            KnownModelInfo(
                contextWindow = 64000,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = REASONING_ONLY,
                pricingPromptPer1M = 0.55,
                pricingCompletionPer1M = 2.19,
                description = "DeepSeek R1 modelo de inferencia",
            ),
        )
        put(
            "deepseek-coder",
            KnownModelInfo(
                contextWindow = 128000,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                description = "DeepSeek Coder modelo de código",
            ),
        )

        // ── Moonshot / Kimi Serie ──
        put(
            "moonshot-v1-8k",
            KnownModelInfo(
                contextWindow = 8000,
                maxOutputTokens = 2048,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                description = "Moonshot v1 8K contexto",
            ),
        )
        put(
            "moonshot-v1-32k",
            KnownModelInfo(
                contextWindow = 32000,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                description = "Moonshot v1 32K contexto",
            ),
        )
        put(
            "moonshot-v1-128k",
            KnownModelInfo(
                contextWindow = 128000,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                description = "Moonshot v1 128K contexto",
            ),
        )
        put(
            "kimi-k2",
            KnownModelInfo(
                contextWindow = 128000,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                description = "Moonshot Kimi K2",
            ),
        )
        // v1.0.53: Kimi K2.6 — versión multimodal(Visión+texto),Herramientas de soporte+razonamiento.
        // Corrección:El etiquetado incorrecto anterior de TEXT_IN provocó que la lógica de corrección de errores v1.137 sobrescribiera la estación de transferencia como texto sin formato, incluso si declaraba multimodalidad.,
        // Las imágenes sólo se pueden utilizar como ayuda visual y no directamente.
        put(
            "kimi-k2.6",
            KnownModelInfo(
                contextWindow = 128000,
                maxOutputTokens = 8192,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                description = "Moonshot Kimi K2.6(multimodal)",
            ),
        )
        put(
            "kimi-k1",
            KnownModelInfo(
                contextWindow = 128000,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = REASONING_ONLY,
                description = "Moonshot Kimi K1",
            ),
        )

        // ── Serie Tongyi Qwen ──
        put(
            "qwen-max",
            KnownModelInfo(
                contextWindow = 32000,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                description = "Buque insignia Tongyi Qianwen Max",
            ),
        )
        put(
            "qwen-plus",
            KnownModelInfo(
                contextWindow = 131072,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                description = "Tongyi Qianwen Plus",
            ),
        )
        put(
            "qwen-turbo",
            KnownModelInfo(
                contextWindow = 1000000,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                description = "Tongyi Qianwen Turbo",
            ),
        )
        put(
            "qwen3-235b",
            KnownModelInfo(
                contextWindow = 131072,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                description = "Tongyi Qianwen 3 235B",
            ),
        )
        put(
            "qwen2.5-72b",
            KnownModelInfo(
                contextWindow = 131072,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                description = "Tongyi Qianwen 2.5 72B",
            ),
        )
        put(
            "qwen2.5-coder",
            KnownModelInfo(
                contextWindow = 131072,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                description = "Modelo de código codificador Tongyi Qianwen 2.5",
            ),
        )

        // ── Puf Byte serie Doubao ──
        put(
            "doubao-pro",
            KnownModelInfo(
                contextWindow = 32000,
                maxOutputTokens = 4096,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                description = "Puf de bytes Pro",
            ),
        )
        put(
            "doubao-lite",
            KnownModelInfo(
                contextWindow = 32000,
                maxOutputTokens = 4096,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                description = "Puf de bytes Lite",
            ),
        )
        put(
            "doubao-1.6",
            KnownModelInfo(
                contextWindow = 128000,
                maxOutputTokens = 8192,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                description = "Puf de bytes 1.6",
            ),
        )
        put(
            "doubao-1.5-pro",
            KnownModelInfo(
                contextWindow = 128000,
                maxOutputTokens = 8192,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                description = "Puf de bytes 1.5 Pro",
            ),
        )

        // ── Serie GLM ──
        put(
            "glm-4-plus",
            KnownModelInfo(
                contextWindow = 128000,
                maxOutputTokens = 4096,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                description = "espectro de sabiduría GLM-4-Plus",
            ),
        )
        put(
            "glm-4-air",
            KnownModelInfo(
                contextWindow = 128000,
                maxOutputTokens = 4096,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                description = "espectro de sabiduría GLM-4-Air(ligero)",
            ),
        )
        put(
            "glm-4-flash",
            KnownModelInfo(
                contextWindow = 128000,
                maxOutputTokens = 4096,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                description = "espectro de sabiduría GLM-4-Flash(gratis)",
            ),
        )
        put(
            "glm-4.5",
            KnownModelInfo(
                contextWindow = 128000,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                description = "espectro de sabiduría GLM-4.5",
            ),
        )

        // ── Mistral Serie ──
        put(
            "mistral-large",
            KnownModelInfo(
                contextWindow = 128000,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                pricingPromptPer1M = 2.00,
                pricingCompletionPer1M = 6.00,
                description = "Mistral Large 2",
            ),
        )
        put(
            "mistral-medium",
            KnownModelInfo(
                contextWindow = 32000,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                pricingPromptPer1M = 0.40,
                pricingCompletionPer1M = 1.20,
                description = "Mistral Medium",
            ),
        )
        put(
            "mistral-small",
            KnownModelInfo(
                contextWindow = 32000,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                pricingPromptPer1M = 0.20,
                pricingCompletionPer1M = 0.60,
                description = "Mistral Small",
            ),
        )
        put(
            "codestral",
            KnownModelInfo(
                contextWindow = 32000,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                pricingPromptPer1M = 0.30,
                pricingCompletionPer1M = 0.90,
                description = "Mistral Codestral modelo de código",
            ),
        )
        put(
            "pixtral",
            KnownModelInfo(
                contextWindow = 128000,
                maxOutputTokens = 8192,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                pricingPromptPer1M = 0.15,
                pricingCompletionPer1M = 0.15,
                description = "Mistral Pixtral multimodal",
            ),
        )

        // ── xAI Grok Serie ──
        put(
            "grok-2",
            KnownModelInfo(
                contextWindow = 131072,
                maxOutputTokens = 8192,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                pricingPromptPer1M = 2.00,
                pricingCompletionPer1M = 10.00,
                description = "xAI Grok 2",
            ),
        )
        put(
            "grok-3",
            KnownModelInfo(
                contextWindow = 131072,
                maxOutputTokens = 8192,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                pricingPromptPer1M = 3.00,
                pricingCompletionPer1M = 15.00,
                description = "xAI Grok 3",
            ),
        )
        put(
            "grok-4",
            KnownModelInfo(
                contextWindow = 256000,
                maxOutputTokens = 8192,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                description = "xAI Grok 4",
            ),
        )
        // v1.0.6: Se agregó la serie Grok 4.5 / 4.3(Principalmente caminar xAI OAuth Responses)
        put(
            "grok-4.5",
            KnownModelInfo(
                contextWindow = 500000,
                maxOutputTokens = 128000,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                description = "xAI Grok 4.5",
            ),
        )
        put(
            "grok-4.5-latest",
            KnownModelInfo(
                contextWindow = 500000,
                maxOutputTokens = 128000,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                description = "xAI Grok 4.5 Latest",
            ),
        )
        put(
            "grok-build-latest",
            KnownModelInfo(
                contextWindow = 500000,
                maxOutputTokens = 128000,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                description = "xAI Grok Build Latest",
            ),
        )
        put(
            "grok-4.3",
            KnownModelInfo(
                contextWindow = 1000000,
                maxOutputTokens = 128000,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                description = "xAI Grok 4.3(1M contexto)",
            ),
        )

        // ── MiniMax abab Serie ──
        put(
            "abab6.5",
            KnownModelInfo(
                contextWindow = 245768,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                description = "MiniMax abab6.5",
            ),
        )
        put(
            "abab7",
            KnownModelInfo(
                contextWindow = 245768,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                description = "MiniMax abab7",
            ),
        )
        put(
            "minimax-m3",
            KnownModelInfo(
                contextWindow = 1000000,
                maxOutputTokens = 8192,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = TOOL_ONLY,
                description = "MiniMax M3 modelo visual",
            ),
        )

        // ── VELOCIDAD Serie Agnes ──
        // v1.0.8: Agnes chat / image / video modelo(Alineación PresetProviders.agnes)
        put(
            "agnes-2.0-flash",
            KnownModelInfo(
                contextWindow = 131072,
                maxOutputTokens = 8192,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL),
                description = "Agnes 2.0 Flash diálogo multimodal",
            ),
        )
        put(
            "agnes-2.0-pro",
            KnownModelInfo(
                contextWindow = 131072,
                maxOutputTokens = 8192,
                inputModalities = VISION_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL),
                description = "Agnes 2.0 Pro diálogo multimodal",
            ),
        )
        // modelo grafico:context=0(No aplicable),Salida solo modal de imagen
        put(
            "agnes-image-2.1-flash",
            KnownModelInfo(
                contextWindow = 0,
                maxOutputTokens = null,
                inputModalities = emptySet(),
                outputModalities = setOf(Modality.IMAGE),
                abilities = emptySet(),
                description = "Agnes Image 2.1 Flash Modelo de diagrama de Vincent",
            ),
        )
        // modelo de vídeo:context=0(No aplicable),Solo modo de salida de video
        put(
            "agnes-video-v2.0",
            KnownModelInfo(
                contextWindow = 0,
                maxOutputTokens = null,
                inputModalities = emptySet(),
                outputModalities = setOf(Modality.VIDEO),
                abilities = emptySet(),
                description = "Agnes Video 2.0 Vicente vídeo modelo",
            ),
        )

        // ── Incorporaciones recientes de nuevos modelos(2025-08 Se lanzará más tarde)──
        // DeepSeek V4 Serie(Alinee ModelContextWindowRegistry para que coincida exactamente)
        put(
            "deepseek-v4",
            KnownModelInfo(
                contextWindow = 1000000,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                description = "DeepSeek V4 buque insignia(1M contexto)",
            ),
        )
        put(
            "deepseek-v4-pro",
            KnownModelInfo(
                contextWindow = 1000000,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                description = "DeepSeek V4 Pro",
            ),
        )
        put(
            "deepseek-v4-flash",
            KnownModelInfo(
                contextWindow = 1000000,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL),
                description = "DeepSeek V4 Flash Versión ligera",
            ),
        )
        // El buque insignia Tongyi Qwen3-Max
        put(
            "qwen3-max",
            KnownModelInfo(
                contextWindow = 131072,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL, REASONING),
                description = "Tongyi Qianwen 3 Max buque insignia",
            ),
        )
        put(
            "qwen3-coder-plus",
            KnownModelInfo(
                contextWindow = 1048576,
                maxOutputTokens = 8192,
                inputModalities = TEXT_IN,
                outputModalities = TEXT_OUT,
                abilities = setOf(TOOL),
                description = "Modelo de código Tongyi Qianwen 3 Coder Plus",
            ),
        )
    }

    // ──────────────────────────────────────────────────────────────
    // 5.Provider cobertura especifica(Principalmente anotado para plataformas de agregación como OpenRouter pricing)
    // ──────────────────────────────────────────────────────────────

    /**
     * OpenRouter Tabla de cobertura de precios de plataformas de agregación.
     * key para (provider, modelId) Todo en minúsculas;value para (promptPer1M, completionPer1M).
     * Pasar el golpe [KnownModelInfo.copy] fusionarse con la base [lookup] Como resultado(No sobrescriba campos conocidos que no sean nulos).
     */
    private val providerPricing: Map<ProviderKey, Pair<Double, Double>> = mapOf(
        // OpenRouter Modelos populares en pricing(USD / 1M tokens),Sólo como referencia
        ProviderKey("openrouter", "gpt-4o") to (2.50 to 10.00),
        ProviderKey("openrouter", "gpt-4o-mini") to (0.15 to 0.60),
        ProviderKey("openrouter", "gpt-4-turbo") to (10.00 to 30.00),
        ProviderKey("openrouter", "claude-3-opus") to (15.00 to 75.00),
        ProviderKey("openrouter", "claude-3.5-sonnet") to (3.00 to 15.00),
        ProviderKey("openrouter", "claude-3.5-haiku") to (0.80 to 4.00),
        ProviderKey("openrouter", "claude-sonnet-4") to (3.00 to 15.00),
        ProviderKey("openrouter", "claude-opus-4") to (15.00 to 75.00),
        ProviderKey("openrouter", "gemini-2.5-pro") to (1.25 to 10.00),
        ProviderKey("openrouter", "gemini-2.5-flash") to (0.30 to 2.50),
        ProviderKey("openrouter", "deepseek-chat") to (0.27 to 1.10),
        ProviderKey("openrouter", "deepseek-reasoner") to (0.55 to 2.19),
        ProviderKey("openrouter", "grok-3") to (3.00 to 15.00),
        ProviderKey("openrouter", "mistral-large") to (2.00 to 6.00),
        ProviderKey("openrouter", "qwen3-235b") to (0.20 to 0.60),
    )

    // ──────────────────────────────────────────────────────────────
    // 6. Externo API
    // ──────────────────────────────────────────────────────────────

    /**
     * Búsqueda que no distingue entre mayúsculas y minúsculas por modelId.
     * Eliminará automáticamente los prefijos de proveedores comunes(Como por ejemplo "openrouter/gpt-4o" → "gpt-4o").
     * Devuelve nulo en caso de error.
     */
    fun lookup(modelId: String): KnownModelInfo? {
        val raw = modelId.trim().lowercase()
        if (raw.isEmpty()) return null

        // 1. coincidencia exacta
        known[raw]?.let { return it }

        // 2. Quite el prefijo y luego haga coincidir exactamente
        val stripped = stripPrefix(raw)
        if (stripped != raw) {
            known[stripped]?.let { return it }
        }

        // 3. Coincidencia de prefijos de límites(Como por ejemplo "gpt-4o-2024-08-06" golpear "gpt-4o")
        known.entries.firstOrNull { (pattern, _) ->
            raw == pattern || raw.startsWith("$pattern-") || raw.startsWith("${pattern}_") ||
                stripped == pattern || stripped.startsWith("$pattern-") || stripped.startsWith("${pattern}_")
        }?.let { return it.value }

        return null
    }

    /**
     * Búsqueda secundaria por proveedor + modelId.
     *
     * proceso:
     *  1. Usar primero [lookup] Obtener metainformación básica(Incluido en este diccionario pricing)
     *  2. comprobar de nuevo [providerPricing] ¿Existe alguna cobertura de precios específica para este proveedor?,
     *     Usar si está disponible [KnownModelInfo.copy] fusionar(Complete solo los campos de precios que sean nulos en los resultados subyacentes)
     *  3. Regresar directamente cuando el resultado básico es nulo null
     */
    fun lookupProvider(provider: String, modelId: String): KnownModelInfo? {
        val base = lookup(modelId) ?: return null
        val p = provider.trim().lowercase()
        if (p.isEmpty()) return base

        val raw = modelId.trim().lowercase()
        val stripped = stripPrefix(raw)

        // Verifique el proveedor específico pricing(Pruebe el prefijo sin formato/eliminado por separado modelId)
        val pricing = providerPricing[ProviderKey(p, raw)]
            ?: providerPricing[ProviderKey(p, stripped)]
            ?: return base

        return base.copy(
            pricingPromptPer1M = base.pricingPromptPer1M ?: pricing.first,
            pricingCompletionPer1M = base.pricingCompletionPer1M ?: pricing.second,
        )
    }

    /**
     * para depurar:Volver a todos los ajustes preestablecidos modelId(minúscula,orden alfabético).
     */
    fun knownModelIds(): List<String> = known.keys.sorted()
}

/**
 * v2.5.3 (P4-3): Token Uso + Cálculo de Costos.
 *
 * Soporte presione enter/golpe de caché/escrituras en caché/Genera cuatro tipos de puntos de precio. Cuando se desconoce el precio unitario, devuelve nulo de forma conservadora (sin adivinar el precio).
 * Utilice adición saturada para evitar el desbordamiento.
 */
object TokenCostCalculator {
    /** Desglose de la dosis única. */
    data class Usage(
        val promptTokens: Long = 0,
        val cachedTokens: Long = 0,
        /** Token de escritura de caché (Anthropic cache_creation_input_tokens etcétera). */
        val cacheWriteTokens: Long = 0,
        val completionTokens: Long = 0,
        val reasoningTokens: Long = 0,
        /**
         * [promptTokens] Ya sea para incluir la parte de caché (los calibres rápidos de las dos compañías son diferentes y deben declararse explícitamente):
         *  - OpenAI / Gemini de `prompt_tokens` Contiene aciertos de caché, predeterminado true → Restar al fijar el precio
         *  - Anthropic de `input_tokens` Esa es una entrada no almacenada en caché → Establecer en falso al fijar el precio**Sin resta**
         */
        val promptTokensIncludeCache: Boolean = true,
    ) {
        /** Adición de saturación: Evite la envoltura de desbordamiento largo. */
        fun plus(other: Usage): Usage =
            Usage(
                promptTokens = satAdd(promptTokens, other.promptTokens),
                cachedTokens = satAdd(cachedTokens, other.cachedTokens),
                cacheWriteTokens = satAdd(cacheWriteTokens, other.cacheWriteTokens),
                completionTokens = satAdd(completionTokens, other.completionTokens),
                reasoningTokens = satAdd(reasoningTokens, other.reasoningTokens),
                // Los calibres deben ser consistentes al realizar la suma; de lo contrario, la suma en sí misma no tiene sentido; elija el más estricto (si no resta, no perderá la tarifa de entrada)
                promptTokensIncludeCache = promptTokensIncludeCache && other.promptTokensIncludeCache,
            )

        companion object {
            fun satAdd(a: Long, b: Long): Long {
                val sum = a + b
                // Detección de desbordamiento del mismo número
                return if ((a xor b) >= 0 && (sum xor a) < 0) {
                    if (a >= 0) Long.MAX_VALUE else Long.MIN_VALUE
                } else {
                    sum
                }
            }
        }
    }

    /** Resultados de la estimación de costos;[known] = false Indica que se desconoce el precio unitario,[usd] Sólo se cuentan las partes conocidas. */
    data class Cost(
        val usd: Double,
        val known: Boolean,
    )

    /**
     * Calcule el costo en dólares por uso + precio unitario.
     *
     * Reglas de precios:
     *  - Presione la entrada de caché [KnownModelInfo.pricingCachedInputPer1M](Si falta, se utilizará el precio normal del insumo)
     *  - Prensa de escritura en caché [KnownModelInfo.pricingCacheWritePer1M]; Cuando es nula, esta función no cuenta la parte de escritura.
     *    (por [costFor] prensa [CACHE_WRITE_INPUT_MULTIPLIER] Páselo después de la estimación para evitar adivinar el fabricante aquí)
     *  - **Normalmente introduzca dos calibres, presione [Usage.promptTokensIncludeCache] rama**:
     *      * true(OpenAI / Gemini): entrada no almacenada en caché = promptTokens - cachedTokens - cacheWriteTokens(recortar a 0)
     *      * false(Anthropic):promptTokens Es una entrada no almacenada en caché.**Sin resta**
     *      dos familias `prompt` tienen diferentes significados:OpenAI/Gemini Prompt_tokens contiene aciertos de caché,
     *      Anthropic Los input_tokens no contienen (lectura de caché/escribir una sola columna). El mal uso de la resta puede generar un tamaño de caché grande
     *      La entrada ordinaria se fija en 0 y la tarifa de entrada se pierde directamente.——Y los altos aciertos de caché son la norma.
     *  - El precio unitario es null → [Cost.known] = false
     */
    fun cost(usage: Usage, info: KnownModels.KnownModelInfo?): Cost {
        val inputPrice = info?.pricingPromptPer1M
        val outputPrice = info?.pricingCompletionPer1M
        if (inputPrice == null || outputPrice == null) return Cost(0.0, known = false)
        val cachedPrice = info.pricingCachedInputPer1M ?: inputPrice
        val cacheWritePrice = info.pricingCacheWritePer1M
        val billedCacheWrite = usage.cacheWriteTokens.coerceAtLeast(0)
        val billedCached: Long
        val plainInput: Long
        if (usage.promptTokensIncludeCache) {
            // OpenAI / Gemini: lectura de caché/Están escritos todos los subconjuntos de indicaciones y solo después de restarlos no se descuentan./Entrada normal sin precio de descuento.
            billedCached = usage.cachedTokens.coerceAtMost(usage.promptTokens).coerceAtLeast(0)
            plainInput = (usage.promptTokens - billedCached - billedCacheWrite).coerceAtLeast(0)
        } else {
            // Anthropic:prompt Ya es una entrada no almacenada en caché, lectura en caché/Cada uno se factura de forma independiente, sin resta ni inclusión. 0
            billedCached = usage.cachedTokens.coerceAtLeast(0)
            plainInput = usage.promptTokens.coerceAtLeast(0)
        }
        val usd =
            plainInput / 1_000_000.0 * inputPrice +
                billedCached / 1_000_000.0 * cachedPrice +
                (if (cacheWritePrice != null) billedCacheWrite / 1_000_000.0 * cacheWritePrice else 0.0) +
                usage.completionTokens / 1_000_000.0 * outputPrice
        return Cost(usd = usd, known = true)
    }

    /** Entrada conveniente: presione proveedor + modelId para verificar el precio y luego calcular el precio. */
    fun costFor(provider: String, modelId: String, usage: Usage): Cost {
        val info = runCatching { KnownModels.lookupProvider(provider, modelId) }.getOrNull()
        return cost(usage, info?.let { withEstimatedCacheWritePrice(modelId, it) })
    }

    /**
     * Entrada conveniente:[UsageTokens] Precios directos (lectura de caché/Escribe los puntos enumerados en [UsageTokens.cachedTokens] /
     * [UsageTokens.cacheWriteTokens], aquí los elementos con el mismo nombre se asignan a [Usage]).
     */
    fun costFor(provider: String, modelId: String, usage: UsageTokens): Cost =
        costFor(provider, modelId, fromUsageTokens(usage))

    /** [UsageTokens] → Artículos de valoración. La lectura de caché continúa teniendo en cuenta [Usage.cachedTokens], escritura en caché de una sola columna,prompt El calibre depende de los datos. */
    fun fromUsageTokens(usage: UsageTokens): Usage =
        Usage(
            promptTokens = usage.promptTokens.toLong(),
            cachedTokens = usage.cachedTokens.toLong(),
            cacheWriteTokens = usage.cacheWriteTokens.toLong(),
            completionTokens = usage.completionTokens.toLong(),
            reasoningTokens = usage.reasoningTokens.toLong(),
            promptTokensIncludeCache = usage.promptTokensIncludeCache,
        )

    /**
     * La caché escribe como un múltiplo del precio de entrada subyacente.
     *
     * Tomado de precios públicos de almacenamiento en caché de avisos antrópicos:5 Las escrituras en caché con TTL por minuto se facturan a 1,25 veces el precio de entrada base
     * (1 El TTL por hora es 2 veces. Esta estimación sólo toma 5 minutos, que es un límite inferior conservador).
     * Sólo para lista de precios [KnownModelInfo.pricingCacheWritePer1M] Modelo faltante; cuando existan valores en la tabla, prevalecerá la tabla.
     */
    const val CACHE_WRITE_INPUT_MULTIPLIER: Double = 1.25

    /**
     * Cuando la lista de precios no escribe el precio unitario en el caché, presione [CACHE_WRITE_INPUT_MULTIPLIER] Estimado a partir de los precios de los insumos.
     *
     * Sólo para la familia de modelos Anthropic (modelId para `claude` al principio,`anthropic/`,`openrouter/` Espere hasta que se haya eliminado el prefijo)
     * Estimación:OpenAI / Gemini No hay facturación de escritura en caché y asignarles un precio de escritura inflaría el costo.
     */
    internal fun withEstimatedCacheWritePrice(modelId: String, info: KnownModels.KnownModelInfo): KnownModels.KnownModelInfo {
        if (info.pricingCacheWritePer1M != null) return info
        val inputPrice = info.pricingPromptPer1M ?: return info
        val family = KnownModels.normalizeModelIdForFamily(modelId)
        if (!family.startsWith("claude")) return info
        return info.copy(pricingCacheWritePer1M = inputPrice * CACHE_WRITE_INPUT_MULTIPLIER)
    }
}
