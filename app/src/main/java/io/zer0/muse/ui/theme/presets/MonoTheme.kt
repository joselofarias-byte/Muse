package io.zer0.muse.ui.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.zer0.muse.R
import io.zer0.muse.ui.theme.*

// 6. tema blanco y negro (Mono - escala de grises minimalista,tema predeterminado)
// v1.0.25: Reemplaza el tema AMOLED original.primary para negro puro(color claro)/blanco puro(oscuro), todo en escala de grises y sin colores de marca.
// v1.0.90: Suavizado general de la escala de grises —— El negro puro y el blanco puro están demasiado llenos, y los espacios entre las palabras y el fondo, y entre los bloques y el fondo, son todos difíciles.
// v1.0.90: Mueva la escala de grises un paso más hacia el lado blanco. —— La versión anterior presionaba el bloque gris hacia el negro, lo que me aburría.
//   Ahora los polos sólo están ligeramente suavizados.(fondo blanco -> #FBFBFC ligeramente gris,tinta negra pura -> #1E1E22 negro suave;
//   fondo oscuro -> #101014 Todavía casi OLED negro,tinta blanca pura -> #E9E9ED blanco suave),
//   El bloque gris en sí desaparece."gris blanquecino", la diferencia de nivel es muy pequeña(Color claro 246/242/239/235, color oscuro 32/38/42/47),
//   Hay una transición mate entre la tarjeta y la parte inferior, no un espacio entre la luz y la oscuridad. La estructura de toda la escala de grises sin colores de marca permanece sin cambios.
// ─────────────────────────────────────────────────────────────────────────────
private val MonoLightBg = Color(0xFFFBFBFC)
private val MonoLightInk = Color(0xFF1E1E22)
private val MonoLightSurfaceVariant = Color(0xFFF6F6F8)
private val MonoLightOnSurfaceVariant = Color(0xFF75757E)
private val MonoLightContainer = Color(0xFFF2F2F4)
private val MonoLightContainerHigh = Color(0xFFEFEFF2)
private val MonoLightContainerDim = Color(0xFFEBEBEF)
private val MonoDarkBg = Color(0xFF101014)
private val MonoDarkInk = Color(0xFFE9E9ED)
private val MonoDarkSurfaceVariant = Color(0xFF202024)
private val MonoDarkOnSurfaceVariant = Color(0xFF9C9CA4)
private val MonoDarkContainer = Color(0xFF26262A)
private val MonoDarkContainerHigh = Color(0xFF2A2A2F)
private val MonoDarkContainerBright = Color(0xFF34343A)

val MonoTheme = PresetTheme(
    id = "mono",
    nameResId = R.string.theme_mono,
    lightScheme = lightColorScheme(
        // primary para negro suave:CTA Los botones, los estados seleccionados y las burbujas de usuario están todos en negro suave con caracteres claros.
        primary = MonoLightInk,
        onPrimary = MonoLightBg,
        primaryContainer = MonoLightContainer,
        onPrimaryContainer = MonoLightInk,
        inversePrimary = MonoLightBg,
        secondary = MonoLightOnSurfaceVariant,
        onSecondary = MonoLightBg,
        secondaryContainer = MonoLightContainer,
        onSecondaryContainer = MonoLightInk,
        tertiary = MonoLightOnSurfaceVariant,
        onTertiary = MonoLightBg,
        tertiaryContainer = MonoLightContainer,
        onTertiaryContainer = MonoLightInk,
        // surface gradiente de contenedor:Base ligeramente gris,profundizar gradualmente(Reducir la brecha,gris brumoso)
        surfaceContainer = MonoLightSurfaceVariant,
        surfaceContainerLow = MonoLightBg,
        surfaceContainerHigh = MonoLightContainerHigh,
        surfaceDim = MonoLightContainerDim,
        surfaceBright = Color(0xFFFDFDFE),
        background = MonoLightBg,
        onBackground = MonoLightInk,
        surface = MonoLightBg,
        onSurface = MonoLightInk,
        surfaceVariant = MonoLightSurfaceVariant,
        onSurfaceVariant = MonoLightOnSurfaceVariant,
        surfaceTint = MonoLightInk,
        inverseSurface = MonoLightInk,
        inverseOnSurface = MonoLightBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerLightContainer,
        onErrorContainer = Danger,
        outline = Color(0xFFDBDBE0),
        outlineVariant = MonoLightContainerHigh,
        scrim = Color.Black,
    ),
    darkScheme = darkColorScheme(
        // primary para blanco suave:CTA Los botones, los estados seleccionados y las burbujas de usuario son todos de color blanco suave con caracteres oscuros.
        primary = MonoDarkInk,
        onPrimary = MonoDarkBg,
        primaryContainer = MonoDarkContainer,
        onPrimaryContainer = MonoDarkInk,
        inversePrimary = MonoDarkBg,
        secondary = MonoDarkOnSurfaceVariant,
        onSecondary = MonoDarkBg,
        secondaryContainer = MonoDarkContainer,
        onSecondaryContainer = MonoDarkInk,
        tertiary = MonoDarkOnSurfaceVariant,
        onTertiary = MonoDarkBg,
        tertiaryContainer = MonoDarkContainer,
        onTertiaryContainer = MonoDarkInk,
        // surface gradiente de contenedor:Base negra suave,Destacar paso a paso
        surfaceContainer = MonoDarkSurfaceVariant,
        surfaceContainerLow = MonoDarkBg,
        surfaceContainerHigh = MonoDarkContainerHigh,
        surfaceDim = MonoDarkBg,
        surfaceBright = MonoDarkContainerBright,
        background = MonoDarkBg,
        onBackground = MonoDarkInk,
        surface = MonoDarkBg,
        onSurface = MonoDarkInk,
        surfaceVariant = MonoDarkSurfaceVariant,
        onSurfaceVariant = MonoDarkOnSurfaceVariant,
        surfaceTint = MonoDarkInk,
        inverseSurface = MonoDarkInk,
        inverseOnSurface = MonoDarkBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerDarkContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF38383E),
        outlineVariant = MonoDarkContainerHigh,
        scrim = Color.Black,
    ),
)

// ─────────────────────────────────────────────────────────────────────────────
