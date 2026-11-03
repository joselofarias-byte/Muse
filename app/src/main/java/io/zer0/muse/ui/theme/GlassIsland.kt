package io.zer0.muse.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * v2.6: Contenedor reutilizable "isla de cristal" —— Barra superior tres islas / isla de entrada / más menú / botón unificado, vaya aquí,
 * Asegure una textura de vidrio consistente.
 *
 * Motor y degradación por [glassSurfaceModifier] toma de decisiones unificada (FROST=backdrop / WATER=liquid /
 * versión baja=Vidrio falso), este contenedor ya no depende directamente de un motor específico, ni distingue entre fuentes de neblina o fondo.
 *
 * Volver a cuando el vidrio no está habilitado [solidColor] Fondo de color sólido, la persona que llama no necesita bifurcarse.
 *
 * @param shape forma de isla (cápsula/esquinas redondeadas/redondo)
 * @param solidColor El color base sólido cuando el vidrio está cerrado (también el color base del tinte del vidrio)
 * @param contentPadding relleno de contenido de la isla
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun GlassIsland(
    config: LiquidGlassConfig,
    shape: Shape,
    solidColor: Color,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val useGlass = config.enabled
    val base =
        if (useGlass) {
            glassSurfaceModifier(
                shape = shape,
                surfaceColor = solidColor,
                config = config,
                backdrop = LocalLayerBackdrop.current,
                waterState = LocalWaterGlassState.current,
            )
        } else {
            Modifier.clip(shape).then(Modifier.background(solidColor))
        }
    // v2.6: Color del contenido de la isla de cristal adaptable —— Cuando el fondo sea oscuro, utilice un color claro para el contenido de la isla; de lo contrario, utilice el color del contenido del tema.
    val islandContent =
        if (useGlass && LocalGlassIsBackgroundDark.current) {
            Color.White.copy(alpha = 0.92f)
        } else {
            LocalContentColor.current
        }
    Box(modifier = modifier.then(base).padding(contentPadding)) {
        CompositionLocalProvider(LocalContentColor provides islandContent) {
            content()
        }
    }
}
