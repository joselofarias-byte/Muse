package io.zer0.ai.openai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.0.74: DeepSeek Prueba oficial de normalización del nombre del modelo API.
 * listModels Regreso con fecha/Variaciones de casos(Como por ejemplo DeepSeek-V4-Flash-0731),pero /chat/completions
 * Sólo se aceptan nombres canónicos(deepseek-v4-pro / deepseek-v4-flash),Enviar la identificación original directamente 400(Pruebas de usuarios reales).
 */
class DeepSeekModelNameTest {

    private val deepseekBase = "https://api.deepseek.com/v1"
    private val thirdPartyBase = "https://api.thirdparty.com/v1"

    @Test
    fun `flash with date suffix normalized`() {
        assertEquals("deepseek-v4-flash", normalizeDeepSeekModelId("DeepSeek-V4-Flash-0731", deepseekBase))
    }

    @Test
    fun `flash lowercase with date normalized`() {
        assertEquals("deepseek-v4-flash", normalizeDeepSeekModelId("deepseek-v4-flash-0731", deepseekBase))
    }

    @Test
    fun `flash canonical unchanged`() {
        assertEquals("deepseek-v4-flash", normalizeDeepSeekModelId("deepseek-v4-flash", deepseekBase))
    }

    @Test
    fun `pro with date suffix normalized`() {
        assertEquals("deepseek-v4-pro", normalizeDeepSeekModelId("DeepSeek-V4-Pro-0731", deepseekBase))
    }

    @Test
    fun `pro canonical unchanged`() {
        assertEquals("deepseek-v4-pro", normalizeDeepSeekModelId("deepseek-v4-pro", deepseekBase))
    }

    @Test
    fun `bare v4 maps to pro`() {
        assertEquals("deepseek-v4-pro", normalizeDeepSeekModelId("deepseek-v4", deepseekBase))
    }

    @Test
    fun `non-v4 deepseek models unchanged`() {
        assertEquals("deepseek-chat", normalizeDeepSeekModelId("deepseek-chat", deepseekBase))
        assertEquals("deepseek-reasoner", normalizeDeepSeekModelId("deepseek-reasoner", deepseekBase))
    }

    @Test
    fun `third party baseUrl not normalized`() {
        // Las estaciones de tránsito pueden tener nombres personalizados,Sin normalización(Evite romper modelos personalizados)
        assertEquals("DeepSeek-V4-Flash-0731", normalizeDeepSeekModelId("DeepSeek-V4-Flash-0731", thirdPartyBase))
    }

    // ── v2.6.1: Eliminación de etiquetas de paquetes principales de estaciones de retransmisión ────────────────────────────

    @Test
    fun `channel group label stripped`() {
        assertEquals("gemini-3.8-flash", stripChannelGroupLabelsImpl("[ais]gemini-3.8-flash"))
    }

    @Test
    fun `multiple group labels stripped`() {
        assertEquals("claude-3", stripChannelGroupLabelsImpl("[A][vip]claude-3"))
    }

    @Test
    fun `clean model name untouched`() {
        assertEquals("deepseek-v4-flash", stripChannelGroupLabelsImpl("deepseek-v4-flash"))
    }

    @Test
    fun `provider slash prefix preserved`() {
        // El prefijo de barra diagonal es necesario para la ruta de tránsito,No es una etiqueta de grupo —— No se puede despojar por error.
        assertEquals("openai/gpt-4o", stripChannelGroupLabelsImpl("openai/gpt-4o"))
    }

    @Test
    fun `pure label without model name falls back`() {
        // etiqueta pura(Sin nombre de modelo real)No se debe pelar en hilos vacíos.,Vuelva al valor original para evitar vaciar el modelo.
        assertEquals("[ais]", stripChannelGroupLabelsImpl("[ais]"))
    }

    @Test
    fun `brackets inside model name are not touched`() {
        // Los corchetes en medio del nombre del modelo no son etiquetas iniciales.,Sin pelar.
        assertEquals("model[1]", stripChannelGroupLabelsImpl("model[1]"))
    }
}
