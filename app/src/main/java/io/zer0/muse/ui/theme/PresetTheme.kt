package io.zer0.muse.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import io.zer0.muse.ui.theme.presets.*

/**
 * Portal de registro de temas predeterminado.
 *
 * Cada conjunto de temas ha sido desmantelado. [io.zer0.muse.ui.theme.presets] Empaquetar archivos separados,
 * La definición de tipo, la función de búsqueda y el registro se conservan aquí, y la ruta de referencia externa permanece sin cambios.
 */
@Immutable
data class PresetTheme(
    val id: String,
    val nameResId: Int,
    val lightScheme: ColorScheme,
    val darkScheme: ColorScheme,
)

/**
 * Busque el tema preestablecido por ID; si no lo encuentra, recurra al predeterminado (warm_paper).
 */
fun findPresetTheme(id: String): PresetTheme = PresetThemes.firstOrNull { it.id == id } ?: WarmPaperTheme

val PresetThemes: List<PresetTheme> = listOf(
    MonoTheme,
    WarmPaperTheme,
    SakuraTheme,
    OceanTheme,
    SpringTheme,
    AutumnTheme,
    SumiTheme,
    WashiTheme,
    AizomeTheme,
    TwilightPurpleTheme,
    AmberGoldTheme,
    DuskRoseTheme,
)
