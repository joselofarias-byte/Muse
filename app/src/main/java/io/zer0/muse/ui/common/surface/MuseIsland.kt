package io.zer0.muse.ui.common.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge

/**
 * v1.0.72: Muse componente de especificación de isla — Todos los componentes flotantes de la interfaz de usuario(bloque de cotización/barra de aviso/Cápsulas de herramientas, etc.)Utilice este estilo de manera uniforme.
 *
 * isla = Forma redondeada + fondo translúcido.(Máscara de ancho no completo) + microsombreado,Suspendido independientemente sobre el contenido de la página.
 *
 * normativo(Artículos obligatorios):
 *  - Para el fondo [MaterialTheme.colorScheme.surfaceVariant] translúcido(Predeterminado 0.45),
 *    Deshabilitar fondos de color sólido de ancho completo(eso seria considerado\"máscara\")\n  - Esquinas redondeadas por defecto 16dp(Sensación de isla;Las cápsulas pequeñas se pueden personalizar usando CircleShape)\n  - con ligero sombreado,Separado visualmente del fondo\n  - No utilice un bloque de color de fondo que abarque todo el ancho\n *
 * Uso:
 * ```kotlin\n * MuseIsland(modifier = Modifier.fillMaxWidth()) { ... }\n * ```\n *\n * @param modifier modificador exterior(El ancho lo determina la persona que llama./margen)\n * @param shape forma(Esquinas redondeadas predeterminadas de 16 dp;La cápsula redonda se puede transferir. CircleShape)\n * @param backgroundAlpha transparencia de fondo(0.45 = isla translucida;1.0 = cápsulas de color sólido)\n * @param content contenido de la isla\n */
@Composable
fun MuseIsland(
    modifier: Modifier = Modifier,
    // CMP-08: ficha de esquina redondeada(16dp = MuseShapes.semiLarge)
    shape: RoundedCornerShape = MuseShapes.semiLarge,
    backgroundAlpha: Float = 0.45f,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = backgroundAlpha),
        shape = shape,
        tonalElevation = MuseElevation.low,
        shadowElevation = MuseElevation.medium,
        modifier = modifier,
    ) {
        Box(modifier = Modifier, content = content)
    }
}
