package io.zer0.ai.core

/**
 * Ventanas de contexto de modelo común(token numero)Registro.
 *
 * Se utiliza para rellenar al extraer la lista de modelos ascendentes. [Model.contextWindow](La interfaz upstream /models generalmente no devuelve este campo),
 * Y ChatViewModel aprovecha la situación cuando el modelo no declara contextWindow,Evite ocupar anillos que siempre muestren signos de interrogación.
 *
 * Reglas coincidentes(Ambos no distinguen entre mayúsculas y minúsculas,Pruebe en orden de prioridad):
 *  1. Coincidencia exacta primero(Como por ejemplo "gpt-4o-2024-08-06" golpear "gpt-4o")
 *  2. La coincidencia de prefijos ocupa el segundo lugar(Como por ejemplo "deepseek-v4" golpear "deepseek" Marca predeterminada)
 *  3. Inferencia del número de token del nombre del modelo(Como por ejemplo "model-128k" → 128000, "model-1m" → 1000000)
 *  4. Valor predeterminado a nivel de marca(Como por ejemplo "deepseek" comienzo → 64000)
 *  5. Regreso sin golpe null
 */
object ModelContextWindowRegistry {

    // v1.80 (M-CORE10): Admite registro dinámico en tiempo de ejecución(Los nuevos modelos salen online sin publicar)
    // v1.80 (L-CORE14): Agregar límite de capacidad,Prevenir maliciosos/El registro infinito de llamadores excepcionales provoca pérdidas de memoria.
    //   Envuelto con sincronizado LinkedHashMap(LRU),Las entradas más antiguas serán eliminadas cuando se supere el límite.
    private const val DYNAMIC_REGISTRY_MAX_SIZE = 256
    private val dynamicRegistry: MutableMap<String, Int> =
        object : java.util.LinkedHashMap<String, Int>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean {
                return size > DYNAMIC_REGISTRY_MAX_SIZE
            }
        }

    // ── preciso/Tabla de coincidencia de prefijos ──
    private val REGISTRY: List<Pair<String, Int>> = listOf(
        // OpenAI Serie
        "gpt-4o" to 128000,
        "gpt-4o-mini" to 128000,
        "gpt-4-turbo" to 128000,
        "gpt-4" to 8192,
        "gpt-3.5-turbo" to 16385,
        "o1" to 200000,
        "o3" to 200000,
        "o4-mini" to 200000,
        // Claude Serie
        "claude-3-5-sonnet" to 200000,
        "claude-3-5-haiku" to 200000,
        "claude-3-7-sonnet" to 200000,
        "claude-3-opus" to 200000,
        "claude-sonnet-4" to 200000,
        "claude-opus-4" to 200000,
        "claude-3-haiku" to 200000,
        // Gemini Serie
        "gemini-2.0-flash" to 1000000,
        "gemini-2.0-flash-lite" to 1000000,
        "gemini-1.5-pro" to 2000000,
        "gemini-1.5-flash" to 1000000,
        "gemini-2.5-pro" to 1000000,
        "gemini-2.5-flash" to 1000000,
        // DeepSeek búsqueda profunda
        "deepseek-chat" to 64000,
        "deepseek-reasoner" to 64000,
        "deepseek-v3" to 64000,
        "deepseek-r1" to 64000,
        "deepseek-v4" to 1000000,
        "deepseek-v4-pro" to 1000000,
        "deepseek-v4-flash" to 1000000,
        "deepseek-v3.1" to 64000,
        // significado general
        "qwen-max" to 32000,
        "qwen-plus" to 131072,
        "qwen-turbo" to 1000000,
        "qwen2.5" to 131072,
        "qwen3" to 131072,
        "qwen3.7" to 128000,
        "qwen3.6" to 128000,
        // espectro de sabiduría
        "glm-4" to 128000,
        "glm-4-plus" to 128000,
        "glm-4-flash" to 128000,
        "glm-4.5" to 128000,
        "glm-5" to 128000,
        "glm-5.1" to 128000,
        "glm-5.2" to 128000,
        // lado oscuro de la luna
        "moonshot-v1-8k" to 8000,
        "moonshot-v1-32k" to 32000,
        "moonshot-v1-128k" to 128000,
        "kimi-k2" to 128000,
        "kimi-k2.7" to 256000,
        "kimi-k2.6" to 256000,
        "kimi-k2.7-code" to 256000,
        // OpenCode Go / Otros
        "mimo" to 128000,
        "mimo-v2.5" to 128000,
        "mimo-v2.5-pro" to 128000,
        "minimax" to 128000,
        "minimax-m3" to 1000000,
        "minimax-m2.7" to 128000,
        "minimax-m2.5" to 128000,
        // bolsa de frijol
        "doubao-pro" to 32000,
        "doubao-lite" to 32000,
        // Baichuan
        "baichuan2" to 4096,
        // cero uno
        "yi-large" to 16384,
        "yi-medium" to 16384,
        // paso
        "step-2" to 32000,
        "step-1" to 8000,
        // Mistral Serie
        "mistral-large" to 128000,
        "mistral-small" to 32000,
        "mixtral" to 32000,
        // Meta Llama Serie
        "llama-3.1" to 128000,
        "llama-3.2" to 128000,
        "llama-3.3" to 128000,
        // Google Gemma Serie
        "gemma-2" to 8192,
        "gemma-3" to 128000,
        // xAI Grok Serie
        "grok-2" to 131072,
        "grok-3" to 131072,
        // Cohere Serie
        "command-r" to 128000,
        "command-r-plus" to 128000,
        // Perplexity Serie
        "llama-3.1-sonar" to 127072,
    )

    // ── Valores predeterminados a nivel de marca(Cuando falla la coincidencia de prefijo,Descubra por marca) ──
    // v1.0.8 (7.2): Cambiar a coincidencia fina con distinción de versión — Coincidencia exacta primero,El prefijo coincide con el bolsillo.
    // antigua implementación "deepseek" -> 64000 si deepseek-v4(1M contexto)Seriamente subestimado,
    // Ahora elimine la consulta de prioridad de la tabla de precisión PRECISE_CONTEXT,Utilice BRAND_DEFAULTS para cubrir errores.
    private val PRECISE_CONTEXT: Map<String, Int> = mapOf(
        // DeepSeek Serie(Grandes diferencias de versión: V3=64K, V4=1M)
        "deepseek-v3" to 64000,
        "deepseek-v3.1" to 64000,
        "deepseek-v4" to 1000000,
        "deepseek-v4-pro" to 1000000,
        "deepseek-v4-flash" to 1000000,
        "deepseek-r1" to 64000,
        "deepseek-chat" to 64000,
        "deepseek-reasoner" to 64000,
        "deepseek-coder" to 128000,
        // Serie Tongyi Qwen(max=32K, plus/turbo/coder=1M)
        "qwen-max" to 32000,
        "qwen-plus" to 131072,
        "qwen-turbo" to 1000000,
        "qwen-long" to 1000000,
        "qwen3-max" to 131072,
        "qwen3-coder-plus" to 1048576,
        // Serie GLM(unificar 128K)
        "glm-4-plus" to 128000,
        "glm-4-flash" to 128000,
        "glm-4.5" to 128000,
        "glm-4.6" to 128000,
        "glm-5" to 128000,
        "glm-5.2" to 128000,
        // Serie de pufs(pro=32K, 1.5/1.6=128K)
        "doubao-pro" to 32000,
        "doubao-1.5-pro" to 128000,
        "doubao-1.6" to 128000,
        // Kimi Serie(v1=8K/32K/128K, k2=128K/256K)
        "kimi-k2" to 128000,
        "kimi-k2.7" to 256000,
        // Agnes Serie(chat=256K/128K, image/video=0 No aplicable)
        "agnes-2.0-flash" to 131072,
        "agnes-2.0-pro" to 131072,
        "agnes-2.5-flash" to 262144,
        "agnes-2.5-pro-alpha" to 262144,
        "agnes-2.5-pro-beta" to 262144,
        "agnes-3.0-flash" to 262144,
        "agnes-image-2.1-flash" to 0,
        "agnes-image-2.0" to 0,
        "agnes-image-2.5-flash" to 0,
        "agnes-video-v2.0" to 0,
        "agnes-video-2.5" to 0,
        "agnes-video-2.5-flash" to 0,
    )

    private val BRAND_DEFAULTS: List<Pair<String, Int>> = listOf(
        "deepseek" to 64000,
        "gpt-4" to 128000,
        "gpt-3.5" to 16385,
        "claude" to 200000,
        "gemini" to 1000000,
        "qwen" to 131072,
        "glm" to 128000,
        "moonshot" to 128000,
        "kimi" to 128000,
        "minimax" to 128000,
        "mimo" to 128000,
        "doubao" to 32000,
        "yi" to 16384,
        "step" to 32000,
        "mistral" to 32000,
        "llama" to 128000,
        "gemma" to 8192,
        "grok" to 131072,
        "command" to 128000,
        // v1.0.8: Se agregó la portada de la marca Agnes.(chat Modelo predeterminado 128K)
        "agnes" to 131072,
    )

    // ── Inferencia del número de token del nombre del modelo regular(Como por ejemplo "model-128k" / "model-1m" / "model-200k") ──
    private val TOKEN_PATTERN = Regex("""(\d+)([km])""", RegexOption.IGNORE_CASE)

    /**
     * Transferencia parcial/La plataforma de agregación agregará el prefijo del proveedor antes del ID del modelo.(Como openrouter/,opencode-go/).
     * Primero elimine estos prefijos conocidos,Luego use el ID del modelo principal para que coincida con el registro.
     */
    private val KNOWN_PREFIXES = listOf(
        "opencode-go/",
        "openrouter/",
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

    /**
     * v1.80 (M-CORE10): Registre la ventana de contexto del modelo en tiempo de ejecución,No se requiere ninguna versión para admitir nuevos modelos.
     * El ID de modelo exacto registrado tiene prioridad sobre el REGISTRO integrado.
     *
     * v1.80 (L-CORE14): Agregar protección sincronizada(LRU LinkedHashMap No es seguro para subprocesos),
     * La capacidad excede [DYNAMIC_REGISTRY_MAX_SIZE] Las entradas más antiguas se eliminan automáticamente.
     */
    fun register(modelId: String, contextWindow: Int) {
        synchronized(dynamicRegistry) {
            dynamicRegistry[modelId.lowercase().trim()] = contextWindow
        }
    }

    /**
     * Consulta la ventana de contexto por modelId. Devuelve nulo en caso de error.
     *
     * Prioridad de partido:Registro dinámico → prefijo de tira → preciso(REGISTRY) → prefijo de límite → inferencia de token de nombre
     *            → PRECISE_CONTEXT tabla de versiones exactas → Marca predeterminada → null
     *
     * v1.0.8 (7.2): Se agregó la capa de coincidencia de versión exacta PRECISE_CONTEXT.,Consulta antes del valor predeterminado de la marca,
     *  resolver "deepseek" 64.000 pares deepseek-v4(1M)Un problema seriamente subestimado.
     */
    fun lookup(modelId: String): Int? {
        var id = modelId.lowercase().trim()
        if (id.isBlank()) return null

        // v1.80 (M-CORE10): Prioridad de registro dinámico
        synchronized(dynamicRegistry) { dynamicRegistry[id] }?.let { return it }

        // 0. Elimina los prefijos de proveedores comunes
        KNOWN_PREFIXES.firstOrNull { id.startsWith(it) }?.let { prefix ->
            id = id.removePrefix(prefix)
            // Después de eliminar el prefijo, verifique nuevamente el registro dinámico.
            synchronized(dynamicRegistry) { dynamicRegistry[id] }?.let { return it }
        }

        // 1. coincidencia exacta
        REGISTRY.firstOrNull { (pattern, _) -> id == pattern }?.let { return it.second }

        // 2. Coincidencia de prefijos de límites(v1.80 M-CORE7: Agregar restricciones de límites,prevenir "gpt-4" partido "gpt-4.5")
        //    partido "gpt-4o-2024-08-06" golpear "gpt-4o"(seguido de - o hasta el final)
        REGISTRY.firstOrNull { (pattern, _) ->
            id == pattern || id.startsWith("$pattern-") || id.startsWith("${pattern}_")
        }?.let { return it.second }

        // 3. Inferencia del número de token del nombre del modelo(Como por ejemplo "model-128k" → 128000, "model-1m" → 1000000)
        //    Consigue el último partido(evitar "8k-128k" Este tipo de adquisición 8k)
        TOKEN_PATTERN.findAll(id).lastOrNull()?.let { match ->
            val num = match.groupValues[1].toIntOrNull() ?: return@let
            val unit = match.groupValues[2].lowercase()
            return when (unit) {
                "k" -> num * 1000
                "m" -> num * 1_000_000
                else -> null
            }
        }

        // v1.0.8 (7.2): PRECISE_CONTEXT coincidencia de versión exacta(Priorizar la marca)
        //  También admite prefijos de límites:Como por ejemplo "deepseek-v4-pro-1024" golpear "deepseek-v4-pro"
        PRECISE_CONTEXT[id]?.let { return it }
        PRECISE_CONTEXT.entries.firstOrNull { (pattern, _) ->
            id.startsWith("$pattern-") || id.startsWith("${pattern}_")
        }?.let { return it.value }

        // 4. imagen/Los modelos de generación de vídeo no son adecuados para el contexto textual: hay que detener a las marcas antes de que revelen su verdad.
        //    De lo contrario, agnes-image-2.5-flash será "agnes" El nombre de la marca está mal escrito como 131072.
        if (id.contains("image") || id.contains("video") || id.contains("embed")) return 0

        // 5. Valor predeterminado a nivel de marca
        BRAND_DEFAULTS.firstOrNull { (brand, _) -> id.startsWith(brand) }?.let { return it.second }

        return null
    }
}
