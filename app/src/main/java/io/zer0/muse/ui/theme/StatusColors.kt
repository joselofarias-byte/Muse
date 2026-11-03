package io.zer0.muse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Color de estado semántico — Definición de pares de colores claros y oscuros.,Cambia automáticamente con MuseTheme.
 *
 * Propósito:Éxito/advertencia/error/información/cinco tipos neutrales de semántica de estado,
 * Cubriendo los niveles de riesgo de las herramientas,Snackbar Comentarios, nivel de registro, ícono de tipo de documento y otros escenarios comerciales.
 *
 * principios de diseño:
 *  - Utilice un color más oscuro para temas claros.(Garantizar un contraste inferior poco profundo ≥ 4.5:1)
 *  - Utilice un color más claro para temas oscuros.(Contraste profundo garantizado)
 *  - [highlight] amarillo translúcido,Común a temas oscuros y claros.(Indicadores de resaltado/depuración de búsqueda)
 *
 * Uso:
 * ```
 * color = MaterialTheme.statusColors.success
 * ```
 */
data class MuseStatusColors(
    val success: Color,
    val warning: Color,
    val error: Color,
    val info: Color,
    val neutral: Color,
    /** Color base iluminador translúcido(Buscar resultados/marcadores de depuración),Universal para ambos tonos. */
    val highlight: Color,
)

/** Color de estado del tema claro — Material engranaje profundo,Legible sobre fondo poco profundo. */
val LightStatusColors = MuseStatusColors(
    success = Color(0xFF2E7D32),
    warning = Color(0xFFEF6C00),
    error = Color(0xFFD32F2F),
    info = Color(0xFF1976D2),
    neutral = Color(0xFF616161),
    highlight = Color(0x66FFEB3B),
)

/** Color de estado del tema oscuro — Material archivo brillante,Legible en profundidad. */
val DarkStatusColors = MuseStatusColors(
    success = Color(0xFF66BB6A),
    warning = Color(0xFFFFB74D),
    error = Color(0xFFEF5350),
    info = Color(0xFF64B5F6),
    neutral = Color(0xFF9E9E9E),
    highlight = Color(0x66FFEB3B),
)

val LocalStatusColors = compositionLocalOf { LightStatusColors }

/** Fácil acceso: `MaterialTheme.statusColors.success`. */
@Suppress("UnusedReceiverParameter")
val MaterialTheme.statusColors: MuseStatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStatusColors.current

/**
 * Color de resaltado de sintaxis de código — Colores oscuros y claros en pares.,Cambia automáticamente con MuseTheme.
 *
 * Subido desde ui/markdown/CodeHighlighter.kt,Hacer que el código resaltado forme parte del sistema temático,
 * Los temas personalizados se pueden sobrescribir más tarde.
 */
data class MuseCodeColors(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val annotation: Color,
    /** diff Agregar nueva fila(+). Color del éxito de la reutilización semántica,Los temas superficiales y profundos tienen cada uno niveles correspondientes. */
    val diffAdded: Color,
    /** diff Eliminar fila(-). Reutilización semántica de colores de error. */
    val diffRemoved: Color,
    /** diff hunk cabeza(+++/---/@@). Reutilización semántica de colores de información. */
    val diffHunk: Color,
)

/** Resaltado de código de tema claro(Paleta de luces CodeHighlighter original). */
val LightCodeColors = MuseCodeColors(
    keyword = Color(0xFF537D96),
    string = Color(0xFF10A37F),
    comment = Color(0xFF8A8275),
    number = Color(0xFFB8702C),
    annotation = Color(0xFF9C8A2C),
    diffAdded = Color(0xFF2E7D32),
    diffRemoved = Color(0xFFD32F2F),
    diffHunk = Color(0xFF1976D2),
)

/** Resaltado de código de tema oscuro(Paleta oscura Original CodeHighlighter,Versión iluminada). */
val DarkCodeColors = MuseCodeColors(
    keyword = Color(0xFF8AB4CC),
    string = Color(0xFF7FD4B0),
    comment = Color(0xFFA89F8E),
    number = Color(0xFFD49060),
    annotation = Color(0xFFC8B860),
    diffAdded = Color(0xFF66BB6A),
    diffRemoved = Color(0xFFEF5350),
    diffHunk = Color(0xFF64B5F6),
)

val LocalCodeColors = compositionLocalOf { LightCodeColors }

/** Fácil acceso: `MaterialTheme.codeColors.keyword`. */
@Suppress("UnusedReceiverParameter")
val MaterialTheme.codeColors: MuseCodeColors
    @Composable
    @ReadOnlyComposable
    get() = LocalCodeColors.current

// ── Color de la marca(valor fijo,No cambia con el tema.) ─────────────────────────────────────
// Color de identificación de la marca del proveedor de servicios de terceros,Debe ser consistente con la marca.,No cambia bajo temas oscuros y claros.

/** DeepSeek Marca azul. */
val BrandDeepSeek = Color(0xFF4D6BFA)

/** OpenAI Marca verde. */
val BrandOpenAI = Color(0xFF10A37F)

/** Anthropic Marca marrón. */
val BrandAnthropic = Color(0xFFD4A574)

/** Google Gemini Marca azul. */
val BrandGemini = Color(0xFF4285F4)
