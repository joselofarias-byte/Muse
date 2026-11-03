package io.zer0.muse.data.preset

import android.content.Context
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.OAuthConfig
import io.zer0.ai.core.ProviderCategory
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ProviderType
import io.zer0.muse.R

/**
 * v1.0.6: Lista de proveedores preestablecidos — 37 proveedores estándar integrados,
 * Otros 8 exclusivos de 1muse están reservados(deepinfra / lingyi / github-copilot / 6 plantilla de estación de transferencia),
 * Un total de 45 proveedores prediseñados.
 *
 * Alinear contenido(Según las especificaciones estándar del proveedor.):
 *  - proveedor id / displayName / baseUrl / defaultApi Acuerdo / authType
 *  - Lista de modelos de chat predeterminados integrados por proveedor(De la lista de modelos predeterminada incorporada)
 *  - 4 Proveedores de Plan de codificación(dashscope-coding / kimi-coding / volcengine-coding / zhipu-coding)
 *    marca specific.codingPlan = true
 *  - minimax / minimax-token-plan Sigue el protocolo de Mensajes Antrópicos(baseUrl = /anthropic)
 *  - xai-oauth Siga el protocolo de respuestas de OpenAI(type = OPENAI_RESPONSES)
 *  - ollama ir authType=none Semántica(allowMissingApiKey = true)
 *
 * 1muse Reservas especiales:
 *  - SiliconFlow Modelos gratuitos precargados con 8 modelos + botón UI "rellene modelos gratuitos con un clic"
 *  - GitHub Copilot OAuth Predeterminado
 *  - deepinfra / lingyi dos en el extranjero/Proveedores nacionales
 *  - 6 plantilla de estación de transferencia(opencode / api2d / aihubmix / deepbricks / oneapi / newapi)
 *
 * Clasificación:
 *  - funcionario en el extranjero(15): OpenAI / Anthropic / Gemini / xAI / xAI-OAuth / OpenAI-Codex /
 *    GitHub-Copilot / Groq / Together / Mistral / OpenRouter / DeepInfra / Fireworks /
 *    Perplexity / Ollama
 *  - funcionario nacional(22): DeepSeek / Tongyi / Zhipu / Kimi / Doubao / Baichuan / Zero One / Paso / SiliconFlow /
 *    Hunyuan / Baidu / Moda / Wuwen Xinqiong / XiaomiMiMo / picanteAgnes / MiniMax /
 *    BailianoCoding / Kimi-Coding / bolsa de frijolCoding / espectro de sabiduríaCoding /
 *    MiniMax-Token-Plan / XiaomiMiMo-Token-Plan
 *  - estación de transferencia(6): OpenCode / API2D / AiHubMix / DeepBricks / OneAPI / NewAPI
 *
 * llamador: SettingsRepository.presetProviders(primer comienzo / Onboarding / "agregar Provider" visualización de la hora)
 */
class PresetProviders(
    private val context: Context,
    private val modelCatalog: ModelCatalogStore? = null,
) {

    // ── Constantes de punto final(Gestión centralizada,Conveniente para la modificación unificada)─────────────────────────────
    private val ENDPOINT_GROQ = "https://api.groq.com/openai/v1"
    private val ENDPOINT_TOGETHER = "https://api.together.xyz/v1"
    private val ENDPOINT_MISTRAL = "https://api.mistral.ai/v1"
    private val ENDPOINT_OPENROUTER = "https://openrouter.ai/api/v1"
    private val ENDPOINT_DEEPINFRA = "https://api.deepinfra.com/v1/openai"
    private val ENDPOINT_FIREWORKS = "https://api.fireworks.ai/inference/v1"
    private val ENDPOINT_PERPLEXITY = "https://api.perplexity.ai"
    private val ENDPOINT_OLLAMA = "http://localhost:11434/v1"
    private val ENDPOINT_DEEPSEEK = "https://api.deepseek.com/v1"
    private val ENDPOINT_QWEN = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    private val ENDPOINT_ZHIPU = "https://open.bigmodel.cn/api/paas/v4"
    private val ENDPOINT_MOONSHOT = "https://api.moonshot.cn/v1"
    private val ENDPOINT_DOUBAO = "https://ark.cn-beijing.volces.com/api/v3"
    private val ENDPOINT_BAICHUAN = "https://api.baichuan-ai.com/v1"
    private val ENDPOINT_LINGYI = "https://api.lingyiwanwu.com/v1"
    private val ENDPOINT_STEPFUN = "https://api.stepfun.com/v1"
    private val ENDPOINT_HUNYUAN = "https://api.hunyuan.cloud.tencent.com/v1"
    private val ENDPOINT_BAIDU = "https://qianfan.baidubce.com/v2"
    private val ENDPOINT_MODELSCOPE = "https://api-inference.modelscope.cn/v1"
    private val ENDPOINT_INFINI = "https://cloud.infini-ai.com/maas/v1"
    private val ENDPOINT_MIMO = "https://api.xiaomimimo.com/v1"
    private val ENDPOINT_MIMO_TOKEN_PLAN = "https://token-plan-cn.xiaomimimo.com/v1"
    private val ENDPOINT_AGNES = "https://apihub.agnes-ai.com/v1"
    private val ENDPOINT_MINIMAX = "https://api.minimaxi.com/anthropic"
    private val ENDPOINT_DASHSCOPE_CODING = "https://coding.dashscope.aliyuncs.com/v1"
    private val ENDPOINT_KIMI_CODING = "https://api.kimi.com/coding/v1"
    private val ENDPOINT_DOUBAO_CODING = "https://ark.cn-beijing.volces.com/api/coding/v3"
    private val ENDPOINT_ZHIPU_CODING = "https://api.z.ai/api/coding/paas/v4"
    private val ENDPOINT_OPENCODE = "https://opencode.ai/zen/go/v1"
    private val ENDPOINT_API2D = "https://oa.api2d.net/v1"
    private val ENDPOINT_AIHUBMIX = "https://aihubmix.com/v1"
    private val ENDPOINT_DEEPBRICKS = "https://api.deepbricks.ai/v1"

    // P2-5: SiliconFlow Puntos finales compatibles con la plataforma OpenAI(Coherente con SiliconFlowFreeModels.BASE_URL)
    private val ENDPOINT_SILICONFLOW = SiliconFlowFreeModels.BASE_URL

    // ── agrupación de proveedores ─────────────────────────────────────────────────

    /** Fabricantes oficiales en el extranjero.(15 un). */
    val overseas: List<ProviderConfig> = listOf(
        openai(),
        anthropic(),
        gemini(),
        xai(),
        xaiOauth(),
        openaiCodex(),
        githubCopilot(),
        groq(),
        together(),
        mistral(),
        openrouter(),
        deepInfra(),
        fireworks(),
        perplexity(),
        ollama(),
    )

    /** Fabricante oficial nacional(Contiene SiliconFlow normal,Coding Plan con Token Plan). */
    val domestic: List<ProviderConfig> = listOf(
        deepseek(),
        qwen(),
        zhipu(),
        moonshot(),
        doubao(),
        baichuan(),
        lingyi(),
        stepfun(),
        // Proveedores comunes de SiliconFlow;El respaldo del modelo gratuito se conserva de forma independiente como un ajuste preestablecido interno
        siliconFlow(),
        // v1.0.6: Agregar proveedores nacionales
        hunyuan(),
        baiduCloud(),
        modelscope(),
        infini(),
        mimo(),
        agnes(),
        minimax(),
        // v1.0.6: Coding Plan Serie
        dashscopeCoding(),
        kimiCoding(),
        volcengineCoding(),
        zhipuCoding(),
        // v1.0.6: Token Plan Serie
        minimaxTokenPlan(),
        mimoTokenPlan(),
    )

    /** estación de transferencia(6 un,1muse único). */
    val relay: List<ProviderConfig> = listOf(
        opencode(),
        api2d(),
        aihubmix(),
        deepbricks(),
        oneapiTemplate(),
        newapiTemplate(),
    )

    /**
     * Personalice las plantillas de proveedores compatibles con OpenAI.
     * Los usuarios deben completar manualmente baseUrl + apiKey después de seleccionarlo en la página de guía.
     */
    private fun customOpenAI() = ProviderConfig(
        id = "preset_custom_openai",
        displayName = context.getString(R.string.onboarding_provider_custom),
        type = ProviderType.OPENAI,
        baseUrl = "",
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.RELAY,
        specific = ProviderSpecificConfig.Custom(),
        models = emptyList(),
    )

    /**
     * Proveedores seleccionados mostrados en la página de introducción.
     * en el extranjero/Nacional/La transferencia requerirá 3 de cada uno en una proporción estrictamente 1:1:1.,y agregar opciones personalizadas.
     *
     * SiliconFlow Proveedores ordinarios fijados en la cima,Los usuarios pueden completar su propia clave API en la página de configuración.
     */
    val onboardingPresets: List<ProviderConfig> = (
        overseas.take(3) +
            domestic.take(3) +
            relay.take(3) +
            customOpenAI()
        ).map { preset ->
        preset.copy(specId = preset.id.removePrefix("preset_").ifBlank { null })
    }

    /**
     * Todos los proveedores preestablecidos(15 + 22 + 6 + 1 Plantilla personalizada = 43 + 1 un).
     *
     * v1.0.7: Cada preset se completa automáticamente specId(Eliminar de la identificación "preset_" prefijo),
     * suministro [io.zer0.ai.core.ProviderSpecMerger] Fusionar modelo predeterminado de especificaciones + superposición de usuario en tiempo de ejecución.
     */
    /**
     * P1 Superposición del catálogo de modelos: use los campos combinados de ModelCatalogStore para sobrescribir el modelo integrado
     * contextWindow / maxOutputTokens / vision / tools / reasoning.
     *
     * v2.6.0:Sí“autoridad de directorio”La lista predeterminada de proveedores (como Codex) está vacía y el directorio de la nube se usa directamente aquí.
     * La lista de modelos hace que la página de selección predeterminada sea consistente con los modelos reales disponibles (ya no hay códigos integrados).
     */
    private fun overlayCatalog(preset: ProviderConfig): List<Model> {
        val catalog = modelCatalog ?: return preset.models
        val specKey = CatalogProviderIds.toCatalogId(preset.id.removePrefix("preset_").ifBlank { preset.id })
        return catalog.mergeIntoModels(
            providerId = specKey,
            models = preset.models,
            runtimeProviderId = preset.id,
        )
    }

    val all: List<ProviderConfig> = (overseas + domestic + relay + customOpenAI()).map { preset ->
        preset.copy(
            specId = preset.id.removePrefix("preset_").ifBlank { null },
            models = overlayCatalog(preset),
        )
    }

    /** Preajustes de tiempo de ejecución internos,No participa en la configuración del proveedor ni en la visualización del selector predeterminado. */
    private val internalPresets: List<ProviderConfig> = listOf(siliconFlowFree()).map { preset ->
        preset.copy(
            specId = preset.id.removePrefix("preset_").ifBlank { null },
            models = overlayCatalog(preset),
        )
    }

    /** Encuentre el proveedor de aprovisionamiento por identificación. */
    fun byId(id: String): ProviderConfig? = (all + internalPresets).firstOrNull { it.id == id }

    /**
     * v1.0.7: Encuentre especificaciones de proveedores de aprovisionamiento por specId(Para que ProviderSpecMerger combine modelos predeterminados).
     * @param specId Identificación de especificaciones incorporada(Como por ejemplo "openai"/"deepseek",no contiene "preset_" prefijo)
     */
    fun bySpecId(specId: String): ProviderConfig? = (all + internalPresets).firstOrNull { it.specId == specId }

    // ── Asistencia en la construcción de modelos. ──────────────────────────────────────────────

    /**
     * Construir modelo de chat(Simplificar el código repetitivo).
     *
     * @param providerId pertenecer Provider id(Igual que ProviderConfig.id)
     * @param id modelo id(campos modelo enviados a la API)
     * @param name Nombre para mostrar del modelo(El valor predeterminado es el mismo que id)
     * @param contextWindow ventana contextual(token numero),null Indica desconocido
     * @param maxOutputTokens salida máxima token,null Indica que se utiliza el valor predeterminado ascendente
     * @param supportsVision Ya sea para admitir la entrada de imágenes
     * @param reasoning Ya sea para apoyar la cadena de inferencia(thinking / o Serie)
     * @param supportsTools Ya sea para admitir llamadas a funciones
     */
    @Suppress("ktlint:standard:function-naming")
    private fun chatModel(
        providerId: String,
        id: String,
        name: String = id,
        contextWindow: Int? = null,
        maxOutputTokens: Int? = null,
        supportsVision: Boolean = false,
        reasoning: Boolean = false,
        supportsTools: Boolean = false,
    ): Model = Model(
        id = id,
        name = name,
        providerId = providerId,
        contextWindow = contextWindow,
        maxOutputTokens = maxOutputTokens,
        supportsVision = supportsVision,
        abilities = buildSet {
            if (reasoning) add(ModelAbility.REASONING)
            if (supportsTools) add(ModelAbility.TOOL)
        },
    )

    // ── funcionario en el extranjero ──────────────────────────────────────────────────────

    private fun openai() = ProviderConfig(
        id = "preset_openai",
        displayName = "OpenAI",
        type = ProviderType.OPENAI,
        baseUrl = "",
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_openai", "gpt-4o", "GPT-4o", contextWindow = 128_000, supportsVision = true, supportsTools = true),
            chatModel("preset_openai", "gpt-4o-mini", "GPT-4o mini", contextWindow = 128_000, supportsVision = true, supportsTools = true),
            chatModel("preset_openai", "o3-mini", "o3-mini", contextWindow = 200_000, reasoning = true, supportsTools = true),
            chatModel("preset_openai", "gpt-4.1", "GPT-4.1", contextWindow = 1_047_576, supportsTools = true),
            chatModel("preset_openai", "gpt-4.1-mini", "GPT-4.1 mini", contextWindow = 1_047_576, supportsTools = true),
        ),
    )

    private fun anthropic() = ProviderConfig(
        id = "preset_anthropic",
        displayName = "Anthropic Claude",
        type = ProviderType.ANTHROPIC,
        baseUrl = "",
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel(
                "preset_anthropic",
                "claude-sonnet-4-5-20250514",
                "Claude Sonnet 4.5",
                contextWindow = 200_000,
                supportsVision = true,
                reasoning = true,
                supportsTools = true,
            ),
            chatModel(
                "preset_anthropic",
                "claude-opus-4-1-20250805",
                "Claude Opus 4.1",
                contextWindow = 200_000,
                supportsVision = true,
                reasoning = true,
                supportsTools = true,
            ),
            chatModel(
                "preset_anthropic",
                "claude-haiku-4-5-20251001",
                "Claude Haiku 4.5",
                contextWindow = 200_000,
                supportsVision = true,
                supportsTools = true,
            ),
            chatModel(
                "preset_anthropic",
                "claude-3-5-sonnet-20241022",
                "Claude 3.5 Sonnet",
                contextWindow = 200_000,
                supportsVision = true,
                supportsTools = true,
            ),
            chatModel("preset_anthropic", "claude-3-5-haiku-20241022", "Claude 3.5 Haiku", contextWindow = 200_000, supportsTools = true),
        ),
    )

    private fun gemini() = ProviderConfig(
        id = "preset_gemini",
        displayName = "Google Gemini",
        type = ProviderType.GEMINI,
        baseUrl = "",
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel(
                "preset_gemini",
                "gemini-2.5-flash",
                "Gemini 2.5 Flash",
                contextWindow = 1_048_576,
                supportsVision = true,
                reasoning = true,
                supportsTools = true,
            ),
            chatModel(
                "preset_gemini",
                "gemini-2.5-pro",
                "Gemini 2.5 Pro",
                contextWindow = 1_048_576,
                supportsVision = true,
                reasoning = true,
                supportsTools = true,
            ),
            chatModel(
                "preset_gemini",
                "gemini-2.0-flash",
                "Gemini 2.0 Flash",
                contextWindow = 1_048_576,
                supportsVision = true,
                supportsTools = true,
            ),
        ),
    )

    /**
     * P1-6: xAI Proveedor predeterminado(api-key Certificación).
     *
     * Siga el protocolo compatible con OpenAI(type=OPENAI),baseUrl Utilice el punto final oficial de xAI.
     * También admite inicio de sesión OAuth(OAuthConfig.XAI_PRESET),Los usuarios pueden elegir api-key u OAuth.
     */
    private fun xai() = ProviderConfig(
        id = "preset_xai",
        displayName = "xAI Grok",
        type = ProviderType.OPENAI,
        baseUrl = "https://api.x.ai/v1",
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel(
                "preset_xai",
                "grok-4",
                "Grok 4",
                contextWindow = 256_000,
                supportsVision = true,
                reasoning = true,
                supportsTools = true,
            ),
            chatModel(
                "preset_xai",
                "grok-4-heavy",
                "Grok 4 Heavy",
                contextWindow = 256_000,
                supportsVision = true,
                reasoning = true,
                supportsTools = true,
            ),
            chatModel("preset_xai", "grok-3", "Grok 3", contextWindow = 131_072, supportsVision = true, supportsTools = true),
            chatModel("preset_xai", "grok-3-mini", "Grok 3 mini", contextWindow = 131_072, reasoning = true, supportsTools = true),
        ),
        oauthConfig = OAuthConfig.XAI_PRESET,
    )

    /**
     * v1.0.6: xAI OAuth Proveedor predeterminado(Siga el protocolo de respuestas de OpenAI).
     *
     * xAI OAuth Predeterminado:Opte por el protocolo OAuth + openai-responses,
     * baseUrl Apunte al punto final del proxy xAI OAuth(https://cli-chat-proxy.grok.com).
     *
     * con [xai] diferencia:
     *  - type = OPENAI_RESPONSES(Vaya a /v1/responses en su lugar /v1/chat/completions)
     *  - OAuth es el único método de autenticación(dejar en blanco apiKey,Consíguelo a través de OAuth access_token)
     *  - Enfoque de la lista de modelos Grok 4.5+(Responses API Modelos compatibles)
     */
    private fun xaiOauth() = ProviderConfig(
        id = "preset_xai_oauth",
        displayName = "xAI Grok (OAuth Responses)",
        type = ProviderType.OPENAI_RESPONSES,
        baseUrl = "https://cli-chat-proxy.grok.com",
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel(
                "preset_xai_oauth",
                "grok-4.5",
                "Grok 4.5",
                contextWindow = 500_000,
                maxOutputTokens = 128_000,
                supportsVision = true,
                reasoning = true,
                supportsTools = true,
            ),
            chatModel(
                "preset_xai_oauth",
                "grok-4.5-latest",
                "Grok 4.5 Latest",
                contextWindow = 500_000,
                maxOutputTokens = 128_000,
                supportsVision = true,
                reasoning = true,
                supportsTools = true,
            ),
            chatModel(
                "preset_xai_oauth",
                "grok-build-latest",
                "Grok Build Latest",
                contextWindow = 500_000,
                maxOutputTokens = 128_000,
                supportsVision = true,
                reasoning = true,
                supportsTools = true,
            ),
            chatModel(
                "preset_xai_oauth",
                "grok-4.3",
                "Grok 4.3",
                contextWindow = 1_000_000,
                maxOutputTokens = 128_000,
                supportsVision = true,
                reasoning = true,
                supportsTools = true,
            ),
        ),
        oauthConfig = OAuthConfig.XAI_OAUTH_PRESET,
        specific = ProviderSpecificConfig.OpenAI(useResponseApi = true),
        allowMissingApiKey = true, // OAuth Permitir espacio vacío antes de obtener el token apiKey
    )

    /**
* v2.x(comentarios de los usuarios): OpenAI Codex Proveedor predeterminado —— ChatGPT Inicie sesión con su cuenta de suscripción.
*
* Acuerdo verdadero verificado (Codex CLI Mismo estilo):
*  - punto final de inferencia: `https://chatgpt.com/backend-api/codex/responses`(Responses Acuerdo, tomar cuota de suscripción);
*  - Certificación: OAuth Authorization Code + PKCE(Ver [OAuthConfig.OPENAI_CODEX_PRESET]),
*    access_token Cuando sea al portador, adicionalmente se requiere `ChatGPT-Account-ID` encabezado (analizado desde id_token);
*  - modelo: Codex Departamento (gpt-5.x / codex variante), lista fija;
*  - Completamente independiente de la ruta de facturación API de api.openai.com.
*/
    private fun openaiCodex() = ProviderConfig(
        id = "preset_openai_codex",
        displayName = "OpenAI Codex (ChatGPT Número de cuenta)",
        type = ProviderType.OPENAI,
        baseUrl = "https://chatgpt.com/backend-api/codex",
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        // v2.6.0: Lista de modelos**Totalmente determinado por el catálogo de modelos en la nube.**(Tabla de contenido providerId `openai-codex-oauth`),
        // No preestablecido dentro de la aplicación —— ChatGPT Los modelos Codex suscritos se retiraron con estrategia oficial/Las listas codificadas recientemente agregadas caducan fácilmente.
        // ver ModelCatalogStore.CATALOG_AUTHORITATIVE_PROVIDERS con CatalogProviderIds.
        models = emptyList(),
        oauthConfig = OAuthConfig.OPENAI_CODEX_PRESET,
        specific = ProviderSpecificConfig.OpenAI(useResponseApi = true),
        allowMissingApiKey = true, // OAuth Permitir espacio vacío antes de obtener el token apiKey
    )

    /**
     * v1.134 P2-1: GitHub Copilot Proveedor predeterminado(ir GitHub OAuth Device Flow).
     *
     * 1muse Único. Después de que el usuario escanee el código QR con su cuenta de GitHub para autorizar,
     * access_token como apiKey,Copilot Proxy de puerta de enlace para modelos como GPT-4o/Claude.
     */
    private fun githubCopilot() = ProviderConfig(
        id = "preset_github_copilot",
        displayName = "GitHub Copilot",
        type = ProviderType.OPENAI,
        baseUrl = "https://api.githubcopilot.com",
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_github_copilot", "gpt-4o", "GPT-4o", contextWindow = 128_000, supportsVision = true, supportsTools = true),
            chatModel(
                "preset_github_copilot",
                "claude-sonnet-4-5-20250514",
                "Claude Sonnet 4.5",
                contextWindow = 200_000,
                supportsVision = true,
                reasoning = true,
                supportsTools = true,
            ),
            chatModel(
                "preset_github_copilot",
                "gemini-2.5-flash",
                "Gemini 2.5 Flash",
                contextWindow = 1_048_576,
                supportsVision = true,
                reasoning = true,
                supportsTools = true,
            ),
        ),
        oauthConfig = OAuthConfig.GITHUB_COPILOT_PRESET,
    )

    private fun groq() = ProviderConfig(
        id = "preset_groq",
        displayName = "Groq",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_GROQ,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_groq", "llama-3.3-70b-versatile", "Llama 3.3 70B Versatile", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_groq", "llama-3.1-8b-instant", "Llama 3.1 8B Instant", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_groq", "mixtral-8x7b-32768", "Mixtral 8x7B", contextWindow = 32_768, supportsTools = true),
        ),
    )

    private fun together() = ProviderConfig(
        id = "preset_together",
        displayName = "Together AI",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_TOGETHER,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel(
                "preset_together",
                "meta-llama/Llama-3.3-70B-Instruct-Turbo",
                "Llama 3.3 70B Turbo",
                contextWindow = 131_072,
                supportsTools = true,
            ),
            chatModel("preset_together", "deepseek-ai/DeepSeek-R1", "DeepSeek R1", contextWindow = 131_072, reasoning = true),
        ),
    )

    private fun mistral() = ProviderConfig(
        id = "preset_mistral",
        displayName = "Mistral AI",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_MISTRAL,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_mistral", "mistral-large-latest", "Mistral Large", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_mistral", "mistral-small-latest", "Mistral Small", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_mistral", "codestral-latest", "Codestral", contextWindow = 262_144, supportsTools = true),
        ),
    )

    private fun openrouter() = ProviderConfig(
        id = "preset_openrouter",
        displayName = "OpenRouter",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_OPENROUTER,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(), // plataforma de agregación,La lista de modelos la extrae el usuario de /models
    )

    /** 1muse Único. */
    private fun deepInfra() = ProviderConfig(
        id = "preset_deepinfra",
        displayName = "DeepInfra",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_DEEPINFRA,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(),
    )

    private fun fireworks() = ProviderConfig(
        id = "preset_fireworks",
        displayName = "Fireworks AI",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_FIREWORKS,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel(
                "preset_fireworks",
                "accounts/fireworks/models/llama-v3p3-70b-instruct",
                "Llama 3.3 70B",
                contextWindow = 131_072,
                supportsTools = true,
            ),
            chatModel(
                "preset_fireworks",
                "accounts/fireworks/models/deepseek-r1",
                "DeepSeek R1",
                contextWindow = 131_072,
                reasoning = true,
            ),
        ),
    )

    /** v1.0.6: Perplexity Predeterminado. */
    private fun perplexity() = ProviderConfig(
        id = "preset_perplexity",
        displayName = "Perplexity",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_PERPLEXITY,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_perplexity", "sonar-pro", "Sonar Pro", contextWindow = 200_000, supportsTools = true),
            chatModel("preset_perplexity", "sonar", "Sonar", contextWindow = 127_072, supportsTools = true),
            chatModel(
                "preset_perplexity",
                "sonar-reasoning",
                "Sonar Reasoning",
                contextWindow = 127_072,
                reasoning = true,
                supportsTools = true,
            ),
        ),
    )

    /**
     * v1.0.6: Ollama Valor predeterminado local.
     *
     * - authType=none → allowMissingApiKey=true(No es necesario apiKey)
     * - baseUrl=http://localhost:11434/v1(punto final local)
     * - models=lista vacía(Determinado por el modelo configurado localmente del usuario.,Se puede extraer de /modelos)
     */
    private fun ollama() = ProviderConfig(
        id = "preset_ollama",
        displayName = "Ollama (Local)",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_OLLAMA,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(), // Determinado por el modelo de extracción de ollama local del usuario.
        allowMissingApiKey = true,
    )

    // ── funcionario nacional ──────────────────────────────────────────────────────

    private fun deepseek() = ProviderConfig(
        id = "preset_deepseek",
        displayName = context.getString(R.string.preset_provider_deepseek),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_DEEPSEEK,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_deepseek", "deepseek-chat", "DeepSeek V3", contextWindow = 64_000, supportsTools = true),
            chatModel("preset_deepseek", "deepseek-reasoner", "DeepSeek R1", contextWindow = 64_000, reasoning = true),
        ),
    )

    private fun qwen() = ProviderConfig(
        id = "preset_qwen",
        displayName = context.getString(R.string.preset_provider_qwen),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_QWEN,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_qwen", "qwen-max", "Qwen Max", contextWindow = 32_768, supportsTools = true),
            chatModel("preset_qwen", "qwen-plus", "Qwen Plus", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_qwen", "qwen-turbo", "Qwen Turbo", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_qwen", "qwen-long", "Qwen Long", contextWindow = 1_048_576, supportsTools = true),
            chatModel("preset_qwen", "qwen-vl-max", "Qwen VL Max", contextWindow = 32_768, supportsVision = true, supportsTools = true),
            chatModel("preset_qwen", "qwen-vl-plus", "Qwen VL Plus", contextWindow = 131_072, supportsVision = true, supportsTools = true),
            chatModel("preset_qwen", "qwen3-coder-plus", "Qwen3 Coder Plus", contextWindow = 1_048_576, supportsTools = true),
        ),
    )

    private fun zhipu() = ProviderConfig(
        id = "preset_zhipu",
        displayName = context.getString(R.string.preset_provider_zhipu),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_ZHIPU,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_zhipu", "glm-4-plus", "GLM-4 Plus", contextWindow = 128_000, supportsTools = true),
            chatModel("preset_zhipu", "glm-4-flash", "GLM-4 Flash", contextWindow = 128_000, supportsTools = true),
            chatModel("preset_zhipu", "glm-4-air", "GLM-4 Air", contextWindow = 128_000, supportsTools = true),
            chatModel(
                "preset_zhipu",
                "glm-4.5",
                "GLM-4.5",
                contextWindow = 128_000,
                supportsVision = true,
                reasoning = true,
                supportsTools = true,
            ),
            chatModel(
                "preset_zhipu",
                "glm-4.6",
                "GLM-4.6",
                contextWindow = 128_000,
                supportsVision = true,
                reasoning = true,
                supportsTools = true,
            ),
        ),
    )

    private fun moonshot() = ProviderConfig(
        id = "preset_moonshot",
        displayName = context.getString(R.string.preset_provider_moonshot),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_MOONSHOT,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_moonshot", "moonshot-v1-128k", "Moonshot V1 128K", contextWindow = 128_000, supportsTools = true),
            chatModel("preset_moonshot", "moonshot-v1-32k", "Moonshot V1 32K", contextWindow = 32_768, supportsTools = true),
            chatModel("preset_moonshot", "moonshot-v1-8k", "Moonshot V1 8K", contextWindow = 8_192, supportsTools = true),
            chatModel("preset_moonshot", "kimi-k2", "Kimi K2", contextWindow = 256_000, supportsTools = true),
        ),
    )

    private fun doubao() = ProviderConfig(
        id = "preset_doubao",
        displayName = context.getString(R.string.preset_provider_doubao),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_DOUBAO,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        // La identificación del modelo de Doubao es en realidad endpoint id,Los usuarios deben completar el punto de acceso después de crearlo en la consola de Volcano Engine.
        models = emptyList(),
    )

    private fun baichuan() = ProviderConfig(
        id = "preset_baichuan",
        displayName = context.getString(R.string.preset_provider_baichuan),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_BAICHUAN,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_baichuan", "Baichuan4-Turbo", "Baichuan4 Turbo", contextWindow = 128_000, supportsTools = true),
            chatModel("preset_baichuan", "Baichuan4-Air", "Baichuan4 Air", contextWindow = 128_000, supportsTools = true),
        ),
    )

    /** 1muse único(Zero One Thing ha dejado de funcionar gradualmente,Reservado para usuarios existentes). */
    private fun lingyi() = ProviderConfig(
        id = "preset_lingyi",
        displayName = context.getString(R.string.preset_provider_lingyi),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_LINGYI,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_lingyi", "yi-large", "Yi Large", contextWindow = 32_768, supportsTools = true),
            chatModel("preset_lingyi", "yi-medium", "Yi Medium", contextWindow = 16_384, supportsTools = true),
            chatModel("preset_lingyi", "yi-lightning", "Yi Lightning", contextWindow = 16_384, supportsTools = true),
        ),
    )

    private fun stepfun() = ProviderConfig(
        id = "preset_stepfun",
        displayName = context.getString(R.string.preset_provider_stepfun),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_STEPFUN,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_stepfun", "step-2-16k", "Step 2 16K", contextWindow = 16_384, supportsTools = true),
            chatModel("preset_stepfun", "step-1-flash", "Step 1 Flash", contextWindow = 32_768, supportsTools = true),
        ),
    )

    /** Ajustes preestablecidos comunes de proveedores de SiliconFlow. */
    private fun siliconFlow() = ProviderConfig(
        id = "preset_siliconflow",
        displayName = context.getString(R.string.preset_siliconflow),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_SILICONFLOW,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(),
    )

    /**
     * P2-5: SiliconFlow Ajustes preestablecidos de modelos gratuitos.
     *
     * Este es el proveedor de tiempo de ejecución interno.,No aparece en la lista de configuración del proveedor;La recepción todavía puede pasar.
     * [io.zer0.ai.core.FreeModelConfig] Utilice modelos gratuitos.
     *
     * v1.0.18: Mecanismo de respaldo de modelo gratuito — Pasado cuando el usuario no completa la apiKey
     * [io.zer0.ai.core.FreeModelConfig] Inyectar incorporado fallback key,
     * Se pueden llamar directamente dos modelos gratuitos, THUDM/GLM-4-9B-0414 y Qwen/Qwen3-8B.
     * Después de que el usuario completa su apiKey, el respaldo falla.,Utilice la clave de usuario para llamar a los modelos remotos y desbloquear más modelos.
     *
     * - [allowMissingApiKey]=true: Permitir no completar key(fallback Mecanismo para encubrir)
     * - [models] Solo precargado con 2 modelos gratuitos(SiliconFlow sucursal libre);
     *   Después de que el usuario complete la clave, puede hacer clic en el botón "Rellenar modelo gratuito con un clic".([SiliconFlowFreeModels.MODELS])
     *   O llame al control remoto /modelos para obtener todo
     * - 1muse único:UI Proporciona un botón para "rellenar modelo gratuito con un solo clic"(Solo se muestra cuando baseUrl llega a Siliconflow.cn)
     */
    private fun siliconFlowFree() = ProviderConfig(
        id = SiliconFlowFreeModels.PROVIDER_ID,
        displayName = context.getString(R.string.preset_siliconflow_free),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_SILICONFLOW,
        apiKey = "", // vacío,Activar el mecanismo de reserva de modelo libre FreeModelConfig
        builtIn = true,
        hiddenFromSettings = true,
        category = ProviderCategory.OFFICIAL,
        allowMissingApiKey = true, // v1.0.18: Permitir no completar key,Cubierto por clave alternativa
        models = listOf(
            // v1.0.18: Consistente con FreeModelConfig.FREE_MODEL_IDS
            chatModel(
                SiliconFlowFreeModels.PROVIDER_ID,
                "THUDM/GLM-4-9B-0414",
                "GLM-4-9B 0414 (gratis)",
                contextWindow = 32_768,
                supportsTools = true,
            ),
            chatModel(
                SiliconFlowFreeModels.PROVIDER_ID,
                "Qwen/Qwen3-8B",
                "Qwen3-8B (gratis)",
                contextWindow = 32_768,
                supportsTools = true,
            ),
        ),
    )

    // ── funcionario nacional(Nuevo)──────────────────────────────

    /** v1.0.6: Tencent Hunyuan. */
    private fun hunyuan() = ProviderConfig(
        id = "preset_hunyuan",
        displayName = context.getString(R.string.preset_provider_hunyuan),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_HUNYUAN,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_hunyuan", "hunyuan-turbos-latest", "Hunyuan Turbos", contextWindow = 256_000, supportsTools = true),
            chatModel("preset_hunyuan", "hunyuan-large-latest", "Hunyuan Large", contextWindow = 32_000, supportsTools = true),
        ),
    )

    /** v1.0.6: Nube inteligente de Baidu/Wen Xin. */
    private fun baiduCloud() = ProviderConfig(
        id = "preset_baidu_cloud",
        displayName = context.getString(R.string.preset_provider_baidu_cloud),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_BAIDU,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel(
                "preset_baidu_cloud",
                "ernie-4.5-turbo-vl-32k",
                "ERNIE 4.5 Turbo VL",
                contextWindow = 32_768,
                supportsVision = true,
                supportsTools = true,
            ),
            chatModel("preset_baidu_cloud", "ernie-4.0-turbo-128k", "ERNIE 4.0 Turbo 128K", contextWindow = 128_000, supportsTools = true),
        ),
    )

    /** v1.0.6: Modelo mágico: alcance. */
    private fun modelscope() = ProviderConfig(
        id = "preset_modelscope",
        displayName = context.getString(R.string.preset_provider_modelscope),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_MODELSCOPE,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel(
                "preset_modelscope",
                "Qwen/Qwen3-235B-A22B",
                "Qwen3 235B A22B",
                contextWindow = 131_072,
                reasoning = true,
                supportsTools = true,
            ),
        ),
    )

    /** v1.0.6: Infinito. */
    private fun infini() = ProviderConfig(
        id = "preset_infini",
        displayName = context.getString(R.string.preset_provider_infini),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_INFINI,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_infini", "deepseek-r1", "DeepSeek R1", contextWindow = 64_000, reasoning = true),
            chatModel("preset_infini", "deepseek-v3-0324", "DeepSeek V3", contextWindow = 64_000, supportsTools = true),
        ),
    )

    /** v1.0.6: Xiaomi MiMo. */
    private fun mimo() = ProviderConfig(
        id = "preset_mimo",
        displayName = context.getString(R.string.preset_provider_mimo),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_MIMO,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_mimo", "mimo-v2.5-pro", "MiMo V2.5 Pro", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_mimo", "mimo-v2.5", "MiMo V2.5", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_mimo", "mimo-v2-pro", "MiMo V2 Pro", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_mimo", "mimo-v2-flash", "MiMo V2 Flash", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_mimo", "mimo-v2-omni", "MiMo V2 Omni", contextWindow = 131_072, supportsVision = true, supportsTools = true),
        ),
    )

    /**
     * v1.0.6: Inés AI.
     *
     * v1.0.8: Lista completa de modelos — 2 modelo de chat + 1 modelo gráfico + 1 modelo de vídeo,
     * Cubre las diversas capacidades modales de Agnes.Agnes Siga el protocolo compatible con OpenAI,
     * GET /v1/models La lista completa de modelos upstream que se pueden extraer.(Ver [io.zer0.ai.openai.OpenAIProvider.listModels]).
     *
     * v1.0.8 (8.6): listModels Verificación de compatibilidad — No es necesario cambiar el código,Sólo comentarios.
     *  Verificado que OpenAIProvider.listModels es totalmente compatible con Agnes:
     *   1. URL empalme:resolvedBaseUrl + "/models" → https://apihub.agnes-ai.com/v1/models ✓
     *   2. Autenticación:Bearer token(Consistente con la documentación oficial de Agnes.)✓
     *   3. Análisis de respuesta:OpenAIModelsResponse Estándar {"data":[...]} Formato ✓
     *   4. Campo de capacidad:7.3 La resolución de nombres de campos múltiples anula lo que devuelve Agnes capabilities/modalities ✓
     *   5. Clasificación de errores:7.5 El registro en cascada registra etiquetas de clasificación en cada escena de 401/403/404/5xx. ✓
     *   6. ventana contextual:context_length Los campos se registran automáticamente en ModelContextWindowRegistry ✓
     */
    private fun agnes() = ProviderConfig(
        id = "preset_agnes",
        displayName = "Agnes AI",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_AGNES,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            // chat modelo(Admite entrada visual + llamada de herramienta)
            // El valor de contexto se toma de forma conservadora según el límite inferior medido real:379k token La entrada todavía responde normalmente,
            // Complete 256k aquí para dejar un margen de seguridad suficiente y no marque 512k inflados.
            chatModel(
                "preset_agnes",
                "agnes-2.5-flash",
                "Agnes 2.5 Flash",
                contextWindow = 262_144,
                supportsTools = true,
                supportsVision = true,
            ),
            chatModel(
                "preset_agnes",
                "agnes-2.5-pro-alpha",
                "Agnes 2.5 Pro Alpha",
                contextWindow = 262_144,
                supportsTools = true,
                supportsVision = true,
            ),
            // modelo grafico(Modo de imagen de salida)
            Model(
                id = "agnes-image-2.1-flash",
                name = "Agnes Image 2.1 Flash",
                providerId = "preset_agnes",
                // El modelo de gráfico sin formato no se aplica a la ventana de contexto de texto
                contextWindow = 0,
                // Marcar como modelo de generación de imágenes(Salida solo modal de imagen,Sin entrada ni salida de texto)
                inputModalities = emptySet(),
                outputModalities = setOf("image"),
            ),
            // modelo de vídeo(Modo de salida de vídeo)
            Model(
                id = "agnes-video-2.5",
                name = "Agnes Video 2.5",
                providerId = "preset_agnes",
                supportsVideo = true,
                contextWindow = 0,
                inputModalities = emptySet(),
                outputModalities = setOf("video"),
            ),
        ),
    )

    /**
     * v1.0.6: MiniMax Predeterminado.
     *
     * Sigue el protocolo de Mensajes Antrópicos(defaultApi=anthropic-messages),
     * baseUrl señalar a https://api.minimaxi.com/anthropic(MiniMax Punto final oficial compatible con Anthropic).
     */
    private fun minimax() = ProviderConfig(
        id = "preset_minimax",
        displayName = "MiniMax",
        type = ProviderType.ANTHROPIC,
        baseUrl = ENDPOINT_MINIMAX,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel(
                "preset_minimax",
                "MiniMax-M3",
                "MiniMax M3",
                contextWindow = 1_000_000,
                supportsVision = true,
                reasoning = true,
                supportsTools = true,
            ),
            chatModel("preset_minimax", "MiniMax-M2.7", "MiniMax M2.7", contextWindow = 1_000_000, supportsTools = true),
            chatModel("preset_minimax", "MiniMax-M2.5", "MiniMax M2.5", contextWindow = 1_000_000, supportsTools = true),
            chatModel("preset_minimax", "MiniMax-M2.1", "MiniMax M2.1", contextWindow = 1_000_000, supportsTools = true),
            chatModel("preset_minimax", "MiniMax-M2", "MiniMax M2", contextWindow = 1_000_000, supportsTools = true),
        ),
    )

    // ── Coding Plan Serie─────────────────

    /**
     * v1.0.6: Plan de codificación Bailian de Alibaba Cloud.
     *
     * con [qwen] diferencia:Utilice un punto final de codificación dedicado(coding.dashscope.aliyuncs.com),
     * Programación enfocada en listas de modelos(qwen3-coder Serie),apiKey Generalmente con un prefijo único.
     */
    private fun dashscopeCoding() = ProviderConfig(
        id = "preset_dashscope_coding",
        displayName = context.getString(R.string.preset_provider_dashscope_coding),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_DASHSCOPE_CODING,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        specific = ProviderSpecificConfig.OpenAI(codingPlan = true),
        models = listOf(
            chatModel("preset_dashscope_coding", "qwen3-coder-plus", "Qwen3 Coder Plus", contextWindow = 1_048_576, supportsTools = true),
            chatModel("preset_dashscope_coding", "qwen3-coder-next", "Qwen3 Coder Next", contextWindow = 1_048_576, supportsTools = true),
            chatModel("preset_dashscope_coding", "qwen3-coder-flash", "Qwen3 Coder Flash", contextWindow = 1_048_576, supportsTools = true),
        ),
    )

    /**
     * v1.0.6: Kimi Coding Plan.
     *
     * Utilice un punto final de codificación dedicado(api.kimi.com/coding/v1),El modelo es kimi-for-coding,
     * apoyo thinking(kimi Formato),El nivel de inferencia predeterminado es alto.
     */
    private fun kimiCoding() = ProviderConfig(
        id = "preset_kimi_coding",
        displayName = "Kimi Coding Plan",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_KIMI_CODING,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        specific = ProviderSpecificConfig.OpenAI(codingPlan = true),
        models = listOf(
            chatModel(
                "preset_kimi_coding",
                "kimi-for-coding",
                "Kimi for Coding",
                contextWindow = 262_144,
                reasoning = true,
                supportsTools = true,
            ),
        ),
    )

    /**
     * v1.0.6: Plan de codificación del paquete Bean Pack de Volcano Engine.
     *
     * Utilice un punto final de codificación dedicado(ark.cn-beijing.volces.com/api/coding/v3),El modelo es doubao-seed-code.
     */
    private fun volcengineCoding() = ProviderConfig(
        id = "preset_volcengine_coding",
        displayName = context.getString(R.string.preset_provider_volcengine_coding),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_DOUBAO_CODING,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        specific = ProviderSpecificConfig.OpenAI(codingPlan = true),
        models = listOf(
            chatModel("preset_volcengine_coding", "doubao-seed-code", "Doubao Seed Code", contextWindow = 262_144, supportsTools = true),
        ),
    )

    /**
     * v1.0.6: Plan de codificación GLM.
     *
     * Utilice un punto final de codificación dedicado(api.z.ai/api/coding/paas/v4),Programación enfocada en modelos.
     */
    private fun zhipuCoding() = ProviderConfig(
        id = "preset_zhipu_coding",
        displayName = context.getString(R.string.preset_provider_zhipu_coding),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_ZHIPU_CODING,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        specific = ProviderSpecificConfig.OpenAI(codingPlan = true),
        models = listOf(
            chatModel("preset_zhipu_coding", "glm-5.2", "GLM-5.2", contextWindow = 131_072, reasoning = true, supportsTools = true),
            chatModel("preset_zhipu_coding", "glm-5-turbo", "GLM-5 Turbo", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_zhipu_coding", "glm-4.7", "GLM-4.7", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_zhipu_coding", "glm-4.5-air", "GLM-4.5 Air", contextWindow = 131_072, supportsTools = true),
        ),
    )

    // ── Token Plan Serie──────────────

    /**
     * v1.0.6: MiniMax Token Plan.
     *
     * con [minimax] Puntos finales y protocolos compartidos(Anthropic Messages),pero sigue siendo independiente provider id,
     * Método de facturación del Plan Token para MiniMax(Paquete de tokens de reserva).
     */
    private fun minimaxTokenPlan() = ProviderConfig(
        id = "preset_minimax_token_plan",
        displayName = "MiniMax Token Plan",
        type = ProviderType.ANTHROPIC,
        baseUrl = ENDPOINT_MINIMAX,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel(
                "preset_minimax_token_plan",
                "MiniMax-M3",
                "MiniMax M3",
                contextWindow = 1_000_000,
                supportsVision = true,
                reasoning = true,
                supportsTools = true,
            ),
            chatModel("preset_minimax_token_plan", "MiniMax-M2.7", "MiniMax M2.7", contextWindow = 1_000_000, supportsTools = true),
            chatModel("preset_minimax_token_plan", "MiniMax-M2.5", "MiniMax M2.5", contextWindow = 1_000_000, supportsTools = true),
            chatModel("preset_minimax_token_plan", "MiniMax-M2.1", "MiniMax M2.1", contextWindow = 1_000_000, supportsTools = true),
            chatModel("preset_minimax_token_plan", "MiniMax-M2", "MiniMax M2", contextWindow = 1_000_000, supportsTools = true),
        ),
    )

    /**
     * v1.0.6: Plan de tokens Xiaomi MiMo.
     *
     * Utilice el punto final dedicado del plan de tokens(token-plan-cn.xiaomimimo.com),con [mimo] La lista de modelos es la misma.,
     * Pero el método de facturación es el paquete de tokens de precompra.
     */
    private fun mimoTokenPlan() = ProviderConfig(
        id = "preset_mimo_token_plan",
        displayName = "Xiaomi MiMo Token Plan",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_MIMO_TOKEN_PLAN,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_mimo_token_plan", "mimo-v2.5-pro", "MiMo V2.5 Pro", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_mimo_token_plan", "mimo-v2.5", "MiMo V2.5", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_mimo_token_plan", "mimo-v2-pro", "MiMo V2 Pro", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_mimo_token_plan", "mimo-v2-flash", "MiMo V2 Flash", contextWindow = 131_072, supportsTools = true),
            chatModel(
                "preset_mimo_token_plan",
                "mimo-v2-omni",
                "MiMo V2 Omni",
                contextWindow = 131_072,
                supportsVision = true,
                supportsTools = true,
            ),
        ),
    )

    // ── estación de transferencia(1muse único)──────────────────────────────────────────

    /**
     * OpenCode Go — OpenCode Servicio oficial de suscripción modelo low cost.
     *
     * ID del modelo guardado localmente con el prefijo opencode-go/,se pasará antes de la solicitud real
     * ProviderSpecificConfig.OpenAI.stripModelPrefix Pelado automático.
     */
    private fun opencode() = ProviderConfig(
        id = "preset_opencode",
        displayName = "OpenCode Go",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_OPENCODE,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.RELAY,
        specific = ProviderSpecificConfig.OpenAI(stripModelPrefix = "opencode-go/"),
        models = listOf(
            chatModel("preset_opencode", "opencode-go/claude-sonnet-4", "Claude Sonnet 4", contextWindow = 200_000, supportsTools = true),
            chatModel("preset_opencode", "opencode-go/gpt-5", "GPT-5", contextWindow = 400_000, supportsTools = true),
            chatModel("preset_opencode", "opencode-go/qwen3-coder", "Qwen3 Coder", contextWindow = 262_144, supportsTools = true),
        ),
    )

    /** API2D estación de transferencia — Transferencia OpenAI de veteranos nacionales. */
    private fun api2d() = ProviderConfig(
        id = "preset_api2d",
        displayName = context.getString(R.string.preset_provider_api2d),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_API2D,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.RELAY,
        specific = ProviderSpecificConfig.Custom(),
        models = listOf(
            chatModel("preset_api2d", "gpt-4o", "GPT-4o", contextWindow = 128_000, supportsVision = true, supportsTools = true),
            chatModel("preset_api2d", "gpt-4o-mini", "GPT-4o mini", contextWindow = 128_000, supportsVision = true, supportsTools = true),
            chatModel("preset_api2d", "claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet", contextWindow = 200_000, supportsTools = true),
        ),
    )

    /** AiHubMix estación de transferencia — Agregado OpenAI/Claude/Gemini, etc. */
    private fun aihubmix() = ProviderConfig(
        id = "preset_aihubmix",
        displayName = context.getString(R.string.preset_provider_aihubmix),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_AIHUBMIX,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.RELAY,
        specific = ProviderSpecificConfig.Custom(),
        models = listOf(
            chatModel("preset_aihubmix", "gpt-4o", "GPT-4o", contextWindow = 128_000, supportsVision = true, supportsTools = true),
            chatModel("preset_aihubmix", "claude-sonnet-4-20250514", "Claude Sonnet 4", contextWindow = 200_000, supportsTools = true),
            chatModel(
                "preset_aihubmix",
                "gemini-2.5-flash",
                "Gemini 2.5 Flash",
                contextWindow = 1_000_000,
                supportsVision = true,
                supportsTools = true,
            ),
        ),
    )

    /** DeepBricks estación de transferencia — OpenAI Compatible. */
    private fun deepbricks() = ProviderConfig(
        id = "preset_deepbricks",
        displayName = context.getString(R.string.preset_provider_deepbricks),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_DEEPBRICKS,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.RELAY,
        specific = ProviderSpecificConfig.Custom(),
        models = listOf(
            chatModel("preset_deepbricks", "gpt-4o", "GPT-4o", contextWindow = 128_000, supportsVision = true, supportsTools = true),
            chatModel(
                "preset_deepbricks",
                "claude-3-5-sonnet-20241022",
                "Claude 3.5 Sonnet",
                contextWindow = 200_000,
                supportsTools = true,
            ),
        ),
    )

    /** OneAPI Plantilla de transferencia autoconstruida — Autoimplementación del usuario,baseUrl Déjelo en blanco. */
    private fun oneapiTemplate() = ProviderConfig(
        id = "preset_oneapi",
        displayName = context.getString(R.string.preset_provider_oneapi),
        type = ProviderType.OPENAI,
        baseUrl = "", // Autoimplementación del usuario,Dejar en blanco para que los usuarios lo completen
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.RELAY,
        specific = ProviderSpecificConfig.Custom(),
        models = emptyList(),
    )

    /** NewAPI Plantilla de transferencia autoconstruida — OneAPI Horquilla mejorada. */
    private fun newapiTemplate() = ProviderConfig(
        id = "preset_newapi",
        displayName = context.getString(R.string.preset_provider_newapi),
        type = ProviderType.OPENAI,
        baseUrl = "", // Autoimplementación del usuario,Dejar en blanco para que los usuarios lo completen
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.RELAY,
        specific = ProviderSpecificConfig.Custom(),
        models = emptyList(),
    )
}
