package io.zer0.muse.ui.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.zer0.muse.R
import io.zer0.muse.ui.theme.*

// 1. Tema de papel cálido (Predeterminado: mantener los colores existentes de Color.kt)
// L-10: color claro primary(LaurelGreen #2D8C5F)on blanco,Contraste cercano al límite inferior de WCAG AA(4.5:1).
// Esta es la compensación del color de la marca:El verde laurel es el símbolo visual de la musa.,Deliberadamente no profundizar para preservar"Vitalidad verde oscuro"textura;AA nivel mejorado
// (7:1)Obligará a que la primaria sea más oscura y pierda reconocimiento de marca.onPrimary El texto siempre está encerrado en un contenedor oscuro con fondo blanco.,
// El texto a nivel de texto no cae en primario,Por lo tanto, los riesgos reales de accesibilidad son controlables.amoled Usa colores claros warm_paper,Misma nota.
// ─────────────────────────────────────────────────────────────────────────────
val WarmPaperTheme = PresetTheme(
    id = "warm_paper",
    nameResId = R.string.theme_warm_paper,
    lightScheme = lightColorScheme(
        primary = LaurelGreen,
        onPrimary = Color.White,
        primaryContainer = LaurelGreenLightContainer,
        onPrimaryContainer = LaurelGreenDark,
        inversePrimary = StarGold,
        secondary = LaurelGreen,
        onSecondary = Color.White,
        secondaryContainer = LaurelGreenLightContainer,
        onSecondaryContainer = LaurelGreenDark,
        tertiary = LaurelGreen,
        onTertiary = Color.White,
        // M-1: tertiary Mismo color que el primario.,container/onContainer Sincronizar referencia con el color del contenedor principal,
        // evitar"hacer un dibujo"Las insignias y otros puntos de uso de contenedores terciarios vuelven al color púrpura predeterminado.
        tertiaryContainer = LaurelGreenLightContainer,
        onTertiaryContainer = LaurelGreenDark,
        // M-2: surface gradiente de contenedor(color claro:surfaceContainerLow=surface punto de referencia,Cuanto más alto, más oscuro,
        // surfaceDim más oscuro,surfaceBright más brillante),Elimine la opción predeterminada de color gris púrpura.
        surfaceContainer = Color(0xFFF5F2EE),
        surfaceContainerLow = LightBg,
        surfaceContainerHigh = Color(0xFFEDE9E3),
        surfaceDim = Color(0xFFE8E4DE),
        surfaceBright = Color(0xFFFFFCF8),
        background = LightBg,
        onBackground = Ink,
        surface = LightBg,
        onSurface = Ink,
        surfaceVariant = LightAiBubble,
        onSurfaceVariant = Ink,
        surfaceTint = LaurelGreen,
        inverseSurface = Ink,
        inverseOnSurface = LightBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerLightContainer,
        onErrorContainer = Danger,
        outline = Secondary,
        outlineVariant = Divider,
        scrim = Color.Black,
    ),
    darkScheme = darkColorScheme(
        // v1.0.21: Modo oscuro primario en su lugar LaurelGreenBright(#4A9F70),OLED Visibilidad de pantalla mejorada
        primary = LaurelGreenBright,
        onPrimary = Color.White,
        primaryContainer = LaurelGreenDarkContainer,
        onPrimaryContainer = LaurelGreenLight,
        inversePrimary = StarGold,
        secondary = LaurelGreenBright,
        onSecondary = Color.White,
        secondaryContainer = LaurelGreenDarkContainer,
        onSecondaryContainer = LaurelGreenLight,
        tertiary = LaurelGreenBright,
        onTertiary = Color.White,
        // M-1: tertiary Mismo color que el primario.,container/onContainer Sincronizar la referencia con el color del contenedor principal.
        tertiaryContainer = LaurelGreenDarkContainer,
        onTertiaryContainer = LaurelGreenLight,
        // M-2: surface gradiente de contenedor(oscuro:surfaceContainerLow=surface punto de referencia,Cuanto más alto, más brillante,
        // surfaceDim más oscuro,surfaceBright más brillante).surface=negro puro,surfaceDim El mismo negro puro no puede ser más oscuro.
        surfaceContainer = Color(0xFF141416),
        surfaceContainerLow = DarkBg,
        surfaceContainerHigh = Color(0xFF1C1C1E),
        surfaceDim = DarkBg,
        surfaceBright = Color(0xFF2C2C2E),
        background = DarkBg,
        onBackground = DarkInk,
        surface = DarkBg,
        onSurface = DarkInk,
        surfaceVariant = DarkAiBubble,
        onSurfaceVariant = DarkInk,
        surfaceTint = LaurelGreenBright,
        inverseSurface = DarkInk,
        inverseOnSurface = DarkBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerDarkContainer,
        onErrorContainer = Danger,
        outline = Secondary,
        outlineVariant = DarkDivider,
        scrim = Color.Black,
    ),
)

// ─────────────────────────────────────────────────────────────────────────────
