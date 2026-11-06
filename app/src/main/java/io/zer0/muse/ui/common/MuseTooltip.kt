package io.zer0.muse.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup

// E4 (Especialización front-end H8): Universales Tooltip — Al pasar el cursor sobre el escritorio se muestran sugerencias de burbujas.
// El toque celular no genera eventos PointerEventType.Enter/Exit,Naturalmente no desencadenante,No se requiere juicio de plataforma.
// Caja de herramientas de desarrollo propio en lugar de material3: Project material3 es la línea experimental 1.4.0-alpha04,
// TooltipBox es ExperimentalMaterial3Api y el estilo no coincide con el lenguaje de diseño del proyecto Muse.
// Posicionamiento:BottomCenter + Un desplazamiento Y negativo hace que la burbuja flote 6 dp por encima del contenido del ancla.
@Composable
internal fun MuseTooltip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val gapPx = with(LocalDensity.current) { 6.dp.toPx().toInt() }
    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    when (event.type) {
                        PointerEventType.Enter -> visible = true
                        PointerEventType.Exit -> visible = false
                        else -> {}
                    }
                }
            }
        },
    ) {
        content()
        if (visible) {
            Popup(
                alignment = Alignment.BottomCenter,
                offset = IntOffset(0, -gapPx),
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    tonalElevation = 2.dp,
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
