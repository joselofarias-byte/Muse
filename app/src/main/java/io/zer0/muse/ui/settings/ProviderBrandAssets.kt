package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.zer0.ai.core.ProviderType
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.BrandAnthropic
import io.zer0.muse.ui.theme.BrandDeepSeek
import io.zer0.muse.ui.theme.BrandGemini
import io.zer0.muse.ui.theme.BrandOpenAI

/**
 * Provider Mapeo de recursos de marca.
 *
 * v2.0 Revisión:
 *  - Avatar de"Círculo degradado + icono abstracto"Cambiar a"Ladrillos con logo cuadrado redondeado en tonos de marca":
 *    Iconos lineales para fabricantes conocidos.(Tabler line estilo),Marca denominativa de las iniciales del fabricante desconocido,
 *    esquinas redondeadas uniformes/color de fondo/Tamaño del trazo,Evite los problemas anteriores de estilo de círculo degradado desordenado y bajo reconocimiento.
 *  - Los colores de la marca aún provienen de theme/StatusColors.kt Colores de marca fijos,El fabricante desconocido revierte el color principal del tema.
 */

/** Volver al icono de marca del proveedor(Fabricantes conocidos);Regreso desconocido null,por [ProviderLogo] Representa la marca denominativa. */
@Composable
fun providerBrandIconOrNull(type: ProviderType, name: String): ImageVector? {
    val lower = name.lowercase()
    return when {
        lower.contains("copilot") || lower.contains("github") -> MuseIcons.brandGithub
        lower.contains("google") || lower.contains("gemini") || lower.contains("palm") -> MuseIcons.brandGoogle
        lower.contains("telegram") -> MuseIcons.brandTelegram
        lower.contains("bing") -> MuseIcons.brandBing
        lower.contains("deepseek") -> MuseIcons.waveSine
        lower.contains("anthropic") || lower.contains("claude") -> MuseIcons.feather
        lower.contains("openai") || lower.contains("gpt") || lower.contains("codex") -> MuseIcons.star
        lower.contains("qwen") || lower.contains("significado general") -> MuseIcons.globe
        lower.contains("zhipu") || lower.contains("glm") || lower.contains("espectro de sabiduría") -> MuseIcons.hexagon
        lower.contains("kimi") || lower.contains("moonshot") -> MuseIcons.moonStars
        lower.contains("doubao") || lower.contains("volc") || lower.contains("bolsa de frijol") -> MuseIcons.flame
        lower.contains("step") || lower.contains("paso") -> MuseIcons.wind
        lower.contains("silicon") || lower.contains("A base de silicio") -> MuseIcons.stack
        lower.contains("hunyuan") || lower.contains("Hunyuan") -> MuseIcons.atom
        lower.contains("baidu") || lower.contains("Baidu") || lower.contains("Qianfan") -> MuseIcons.compass
        lower.contains("modelscope") || lower.contains("Magia") -> MuseIcons.box
        lower.contains("infini") || lower.contains("No hay duda") -> MuseIcons.infinity
        lower.contains("mimo") || lower.contains("xiaomi") || lower.contains("Xiaomi") -> MuseIcons.brandAndroid
        lower.contains("agnes") -> MuseIcons.wind
        lower.contains("minimax") -> MuseIcons.stack
        lower.contains("ollama") -> MuseIcons.server
        lower.contains("openrouter") -> MuseIcons.route
        lower.contains("groq") -> MuseIcons.bolt
        lower.contains("together") -> MuseIcons.users
        lower.contains("mistral") -> MuseIcons.wind
        lower.contains("fireworks") -> MuseIcons.flame
        lower.contains("perplexity") -> MuseIcons.compass
        lower.contains("deepinfra") -> MuseIcons.server
        lower.contains("xai") || lower.contains("grok") -> MuseIcons.planet
        lower.contains("relay") || lower.contains("tránsito") || lower.contains("oneapi") ||
            lower.contains("newapi") || lower.contains("aihubmix") || lower.contains("api2d") ||
            lower.contains("deepbricks") || lower.contains("opencode") -> MuseIcons.hierarchy
        type == ProviderType.ANTHROPIC -> MuseIcons.feather
        type == ProviderType.GEMINI -> MuseIcons.brandGoogle
        else -> null
    }
}

/** Volver al color de la marca del proveedor(Color de marca fijo,No cambia con el tema.,definido en theme/StatusColors.kt). */
@Composable
fun providerBrandColor(type: ProviderType, name: String): Color {
    val lower = name.lowercase()
    return when {
        lower.contains("deepseek") -> BrandDeepSeek
        type == ProviderType.OPENAI -> BrandOpenAI
        type == ProviderType.ANTHROPIC -> BrandAnthropic
        type == ProviderType.GEMINI -> BrandGemini
        else -> MaterialTheme.colorScheme.primary
    }
}

/** Convierta ProviderType a nombre para mostrar en chino. */
@Composable
fun providerDisplayTypeName(type: ProviderType): String = when (type) {
    ProviderType.OPENAI -> stringResource(R.string.settings_provider_type_openai)
    ProviderType.ANTHROPIC -> stringResource(R.string.settings_provider_type_anthropic)
    ProviderType.GEMINI -> stringResource(R.string.settings_provider_type_gemini)
    ProviderType.OPENAI_RESPONSES -> stringResource(R.string.settings_provider_type_openai_responses)
}

/**
 * Provider logo Ladrillo — Cuadrado redondeado + base luminosa en tono de marca + ícono lineal/Marca denominativa del primer carácter.
 *
 * Fabricantes conocidos(tabler Icono lineal):Color de marca de icono para colorear;Fabricante desconocido:Mostrar la primera letra del nombre./Marca de carácter chino.
 * color de fondo = Color de la marca 10% de transparencia.;Accidente cerebrovascular = Color de la marca 18% de transparencia.;Las esquinas redondeadas tienen por defecto 12 dp.
 */
@Composable
fun ProviderLogo(type: ProviderType, name: String, modifier: Modifier = Modifier, size: Dp = 40.dp, cornerRadius: Dp = 12.dp) {
    val tint = providerBrandColor(type, name)
    val icon = providerBrandIconOrNull(type, name)
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.18f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                // El tamaño del icono se redondea al 52 % del ladrillo.,Garantizar proporciones consistentes en diferentes tamaños.
                modifier = Modifier.size(size * 0.52f),
            )
        } else {
            Text(
                text = providerMonogram(name),
                color = tint,
                fontWeight = FontWeight.SemiBold,
                // Calcule el tamaño de la marca denominativa en un 42% del tamaño del ladrillo(Visualmente proporcional al icono)
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}

/** Toma la primera letra del nombre./Caracteres chinos como marcas denominativas;Omita símbolos como paréntesis. */
internal fun providerMonogram(displayName: String): String {
    val trimmed = displayName.trim()
    if (trimmed.isEmpty()) return "?"
    val first = trimmed.firstOrNull { it.isLetterOrDigit() } ?: trimmed.first()
    return first.uppercaseChar().toString()
}
