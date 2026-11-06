package io.zer0.muse.ui.quicknotes

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import io.zer0.muse.data.AppearanceSettingsStore
import io.zer0.muse.ui.theme.CustomTheme
import io.zer0.muse.ui.theme.DarkAiBubble
import io.zer0.muse.ui.theme.DarkInk
import io.zer0.muse.ui.theme.Ink
import io.zer0.muse.ui.theme.LightAiBubble
import io.zer0.muse.ui.theme.findPresetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Una instantánea del color del tema Material utilizado por la ventana flotante del sistema.
 *
 * La ventana flotante no está en el árbol de redacción.,Por lo tanto no se puede leer directamente. [androidx.compose.material3.MaterialTheme].
 * Aquí reutilizamos la configuración del tema de MuseTheme y la lógica de generación de ColorScheme.,Convertir colores a los utilizados por la Vista nativa
 * ARGB Int,Garantice la coherencia visual en temas de color dinámicos, personalizados, preestablecidos y en blanco y negro.
 */
internal data class QuickCaptureThemeColors(
    val primary: Int,
    val onPrimary: Int,
    val surface: Int,
    val onSurface: Int,
    val surfaceVariant: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
) {
    companion object {
        /** Establece el color mínimo disponible cuando la lectura aún no se ha completado,Manténgase legible mediante el modo claro y oscuro del sistema. */
        fun fallback(context: Context): QuickCaptureThemeColors {
            val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val mono = findPresetTheme("mono")
            val scheme = if (dark) mono.darkScheme else mono.lightScheme
            return scheme.toQuickCaptureThemeColors()
        }
    }
}

private data class ThemeInputs(
    val themeMode: String,
    val themeId: String,
    val darkThemeId: String,
    val dynamicColor: Boolean,
    val customThemes: List<CustomTheme>,
    val highContrast: Boolean,
)

/**
 * Monitoreo y [MuseTheme] Misma configuración de apariencia,Se utiliza para aplicar aspectos en tiempo real a ventanas flotantes nativas en servicios.
 */
internal fun observeQuickCaptureThemeColors(context: Context): Flow<QuickCaptureThemeColors> {
    val appContext = context.applicationContext
    val appearance = AppearanceSettingsStore(appContext)
    val inputs = combine(
        combine(
            appearance.themeModeFlow,
            appearance.themeIdFlow,
            appearance.darkThemeIdFlow,
            appearance.dynamicColorFlow,
            appearance.customThemesFlow,
        ) { themeMode, themeId, darkThemeId, dynamicColor, customThemes ->
            ThemeInputs(
                themeMode = themeMode,
                themeId = themeId,
                darkThemeId = darkThemeId,
                dynamicColor = dynamicColor,
                customThemes = customThemes,
                highContrast = false,
            )
        },
        appearance.highContrastFlow,
    ) { base, highContrast ->
        base.copy(highContrast = highContrast)
    }
    return inputs
        .map { resolveQuickCaptureThemeColors(appContext, it) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
}

private fun resolveQuickCaptureThemeColors(context: Context, inputs: ThemeInputs): QuickCaptureThemeColors {
    val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val dark = when (inputs.themeMode) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    // v1.0.92: El tema independiente del modo oscuro ha estado desconectado — Utilice uniformemente la versión oscura del tema claro.
    val resolvedThemeId = inputs.themeId
    val scheme = when {
        inputs.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> {
            val custom = inputs.customThemes.firstOrNull { it.id == resolvedThemeId }
            if (custom != null) {
                custom.generateColorScheme(dark)
            } else {
                val preset = findPresetTheme(resolvedThemeId)
                if (dark) preset.darkScheme else preset.lightScheme
            }
        }
    }
    val effectiveScheme = if (inputs.highContrast) {
        scheme.toQuickCaptureHighContrast(dark)
    } else {
        scheme
    }
    return effectiveScheme.toQuickCaptureThemeColors()
}

private fun ColorScheme.toQuickCaptureHighContrast(dark: Boolean): ColorScheme = if (dark) {
    copy(
        background = androidx.compose.ui.graphics.Color.Black,
        onBackground = androidx.compose.ui.graphics.Color.White,
        surface = androidx.compose.ui.graphics.Color.Black,
        onSurface = androidx.compose.ui.graphics.Color.White,
        surfaceVariant = DarkAiBubble,
        onSurfaceVariant = DarkInk,
    )
} else {
    copy(
        background = androidx.compose.ui.graphics.Color.White,
        onBackground = androidx.compose.ui.graphics.Color.Black,
        surface = androidx.compose.ui.graphics.Color.White,
        onSurface = androidx.compose.ui.graphics.Color.Black,
        surfaceVariant = LightAiBubble,
        onSurfaceVariant = Ink,
    )
}

private fun ColorScheme.toQuickCaptureThemeColors(): QuickCaptureThemeColors = QuickCaptureThemeColors(
    primary = primary.toArgb(),
    onPrimary = onPrimary.toArgb(),
    surface = surface.toArgb(),
    onSurface = onSurface.toArgb(),
    surfaceVariant = surfaceVariant.toArgb(),
    onSurfaceVariant = onSurfaceVariant.toArgb(),
    outline = outline.toArgb(),
)
