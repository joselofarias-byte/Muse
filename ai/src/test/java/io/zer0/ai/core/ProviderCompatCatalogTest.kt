package io.zer0.ai.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.zer0.ai.registry.ModelRegistry as RuntimeModelRegistry
import io.zer0.common.Logger

/**
 * Prioridad derivada de compatibilidad:[Directorio] > [tabla codificada] > [Valor predeterminado].
 *
 * Directorio(model-catalog)declarado explícitamente en `compat.thinkingFormat` modelo,Los comportamientos van por directorio;
 * Directorio no escrito(O el vocabulario no se puede mapear)modelo,El comportamiento es exactamente el mismo que antes de acceder al directorio. —— todavía por
 * [ProviderCompatRules] El prefijo de host/modelo está determinado por una tabla codificada;Si faltan ambos, el valor predeterminado es nulo.
 */
class ProviderCompatCatalogTest {

    private val openCodeGo = "https://opencode.ai/zen/go/v1"
    private val deepSeek = "https://api.deepseek.com/v1"
    private val kimi = "https://api.kimi.com/v1"
    private val siliconFlow = "https://api.siliconflow.cn/v1"
    private val unknownRelay = "https://tokenrhythm.studio/v1"

    @After
    fun tearDown() {
        RuntimeModelRegistry.installCatalog(ModelCatalog())
    }

    private fun install(providerId: String, modelId: String, compat: CompatProfile?) {
        RuntimeModelRegistry.installCatalog(
            ModelCatalog(
                providers = mapOf(providerId to mapOf(modelId to ModelCatalogEntry(compat = compat))),
            ),
        )
    }

    // ── Directorio escribe valor → El comportamiento sigue el valor del directorio. ──────────────────────────────

    @Test
    fun `catalog declaration overrides the host table`() {
        // Tabla codificada para juzgar el protocolo DeepSeek en opencode.ai,El catálogo declara zhipu para este modelo.
        assertEquals(
            ThinkingFormat.DEEPSEEK,
            ProviderCompatRules.resolve(ProviderType.OPENAI, openCodeGo, "glm-5.2").thinkingFormat,
        )

        install("opencode-go", "glm-5.2", CompatProfile(thinkingFormat = "zhipu"))

        assertEquals(
            ThinkingFormat.ZHIPU,
            ProviderCompatRules.resolve(ProviderType.OPENAI, openCodeGo, "glm-5.2").thinkingFormat,
        )
    }

    @Test
    fun `catalog declaration fills a model the table leaves at the default`() {
        // Tabla codificada para reglas sin modelo glm-5.2,host La capa también sólo cae a DeepSeek;Cuando un directorio declara kimi, el directorio debería prevalecer.
        install("moonshot", "glm-5.2", CompatProfile(thinkingFormat = "kimi"))

        assertEquals(
            ThinkingFormat.KIMI,
            ProviderCompatRules.resolve(ProviderType.OPENAI, siliconFlow, "glm-5.2").thinkingFormat,
        )
    }

    @Test
    fun `model exposes the catalog declaration`() {
        install("opencode-go", "glm-5.2", CompatProfile(thinkingFormat = "zhipu"))

        assertEquals(
            ThinkingFormat.ZHIPU,
            Model(id = "glm-5.2", providerId = "opencode-go").catalogThinkingFormat,
        )
        assertNull(Model(id = "no-such-model", providerId = "opencode-go").catalogThinkingFormat)
    }

    // ── El directorio no tiene ningún valor escrito. → Tabla codificada alternativa,El comportamiento es el mismo que antes de acceder. ────────────────

    @Test
    fun `catalog entry without compat keeps the hardcoded result`() {
        // Hay este modelo en el catálogo.,pero no escribi compat → Siga las reglas de host de api.deepseek.com y siga DEEPSEEK.
        install("deepseek", "deepseek-v4-flash", null)

        assertEquals(
            ThinkingFormat.DEEPSEEK,
            ProviderCompatRules.resolve(ProviderType.OPENAI, deepSeek, "deepseek-v4-flash").thinkingFormat,
        )
    }

    @Test
    fun `unknown model keeps the hardcoded host and prefix results`() {
        install("opencode-go", "glm-5.2", CompatProfile(thinkingFormat = "zhipu"))

        assertEquals(
            ThinkingFormat.DEEPSEEK,
            ProviderCompatRules.resolve(ProviderType.OPENAI, deepSeek, "deepseek-reasoner").thinkingFormat,
        )
        assertEquals(
            ThinkingFormat.KIMI,
            ProviderCompatRules.resolve(ProviderType.OPENAI, kimi, "kimi-k2.5").thinkingFormat,
        )
        assertEquals(
            ThinkingFormat.QWEN,
            ProviderCompatRules.resolve(ProviderType.OPENAI, siliconFlow, "Qwen/Qwen3.5-4B").thinkingFormat,
        )
        assertEquals(
            ThinkingFormat.ZHIPU,
            ProviderCompatRules.resolve(ProviderType.OPENAI, siliconFlow, "zhipu/GLM-4-thinking").thinkingFormat,
        )
    }

    @Test
    fun `unknown relay still refuses vendor extension fields`() {
        install("kimi-coding", "kimi-for-coding", CompatProfile(thinkingFormat = "kimi"))

        // Las estaciones de transferencia desconocidas no emiten campos extendidos como pensar/enable_thinking(v1.0.53 tratamiento conservador).
        assertNull(ProviderCompatRules.resolve(ProviderType.OPENAI, unknownRelay, "kimi-for-coding").thinkingFormat)
        // Siga el directorio únicamente si conoce el punto final.
        assertEquals(
            ThinkingFormat.KIMI,
            ProviderCompatRules.resolve(ProviderType.OPENAI, kimi, "kimi-for-coding").thinkingFormat,
        )
    }

    @Test
    fun `native-protocol vocabulary is not mapped onto the openai compat field`() {
        RuntimeModelRegistry.installCatalog(
            ModelCatalog(
                providers = mapOf(
                    "anthropic" to mapOf(
                        "claude-opus-5" to ModelCatalogEntry(
                            compat = CompatProfile(thinkingFormat = "anthropic"),
                        ),
                    ),
                    "gemini" to mapOf(
                        "gemini-4-argon" to ModelCatalogEntry(
                            compat = CompatProfile(thinkingFormat = "google-thinking-level"),
                        ),
                    ),
                ),
            ),
        )

        // anthropic / google-thinking-level Manejado por el propio proveedor nativo.,No se puede utilizar como campo de extensión para enlaces compatibles con OpenAI.
        assertNull(ProviderCompatRules.resolve(ProviderType.OPENAI, siliconFlow, "claude-opus-5").thinkingFormat)
        assertNull(ProviderCompatRules.resolve(ProviderType.OPENAI, siliconFlow, "gemini-4-argon").thinkingFormat)
    }

    // ── ambos faltan → Valor predeterminado ─────────────────────────────────────

    @Test
    fun `both missing yields the default`() {
        // El directorio está vacío + el host es desconocido + el nombre del modelo es desconocido.
        assertNull(ProviderCompatRules.resolve(ProviderType.OPENAI, unknownRelay, "totally-unknown-model").thinkingFormat)
        // El directorio está vacío + se conoce el host pero no se declara en la tabla.
        assertNull(ProviderCompatRules.resolve(ProviderType.OPENAI, siliconFlow, "totally-unknown-model").thinkingFormat)
        assertNull(ProviderCompatRules.resolve(ProviderType.OPENAI, "", "totally-unknown-model").thinkingFormat)
        // Si no se proporciona modelId, no se accederá al directorio.
        assertNull(ProviderCompatRules.resolve(ProviderType.OPENAI, siliconFlow).thinkingFormat)
    }

    // ── Declaración del proveedor de tolva:Sólo llama a la policía.,No cambiar el comportamiento ────────────────────

    @Test
    fun `ambiguous cross-provider declaration is reported without changing behaviour`() {
        val lines = mutableListOf<String>()
        Logger.sink = { level, tag, msg, _ -> if (tag == "ProviderCompatRules") lines += "$level $msg" }
        try {
            // Formulario 1: Ambas declaraciones tienen valores diferentes. → Informar "conflicto",El acto sigue siendo el primero en aparecer y gana.
            RuntimeModelRegistry.installCatalog(
                ModelCatalog(
                    providers = mapOf(
                        "opencode-go" to mapOf(
                            "glm-5.2" to ModelCatalogEntry(compat = CompatProfile(thinkingFormat = "zhipu")),
                        ),
                        "zhipu" to mapOf(
                            "glm-5.2" to ModelCatalogEntry(compat = CompatProfile(thinkingFormat = "kimi")),
                        ),
                    ),
                ),
            )
            assertEquals(1, lines.size)
            assertTrue(lines.single().contains("Conflicto de declaración de directorio"))
            assertTrue(lines.single().contains("opencode-go=zhipu"))
            assertTrue(lines.single().contains("zhipu=kimi"))
            assertEquals(
                ThinkingFormat.ZHIPU,
                ProviderCompatRules.resolve(ProviderType.OPENAI, siliconFlow, "glm-5.2").thinkingFormat,
            )

            // Formulario 2: Sólo una empresa declara → Presentar una "declaración unilateral"(Los proveedores no declarados toman prestada la declaración de otra persona).
            lines.clear()
            RuntimeModelRegistry.installCatalog(
                ModelCatalog(
                    providers = mapOf(
                        "opencode-go" to mapOf(
                            "glm-5.2" to ModelCatalogEntry(compat = CompatProfile(thinkingFormat = "zhipu")),
                        ),
                        "zhipu" to mapOf("glm-5.2" to ModelCatalogEntry()),
                    ),
                ),
            )
            assertEquals(1, lines.size)
            assertTrue(lines.single().contains("declaración unilateral directorio"))
            assertTrue(lines.single().contains("zhipu=<No declarado>"))
        } finally {
            Logger.sink = null
        }
    }

    @Test
    fun `single provider declaration logs nothing`() {
        val lines = mutableListOf<String>()
        Logger.sink = { level, tag, msg, _ -> if (tag == "ProviderCompatRules") lines += "$level $msg" }
        try {
            install("opencode-go", "glm-5.2", CompatProfile(thinkingFormat = "zhipu"))
            assertTrue(lines.isEmpty())
        } finally {
            Logger.sink = null
        }
    }

    @Test
    fun `other compat fields are untouched by the catalog`() {
        install("opencode-go", "glm-5.2", CompatProfile(thinkingFormat = "zhipu", reasoningProfile = "zhipu-openai"))

        val compat = ProviderCompatRules.resolve(ProviderType.OPENAI, openCodeGo, "glm-5.2")

        // El directorio sólo cubre thinkingFormat;El contrato de reproducción, etc. todavía se derivan del enlace original.(opencode.ai → DeepSeek Acuerdo).
        assertEquals(ReasoningCarrier.REASONING_CONTENT, compat.reasoningReplayContract?.carrier)
        assertEquals(ReasoningReplayPolicy.REQUIRE_TOOL_CALL, compat.reasoningReplayContract?.policy)
    }
}
