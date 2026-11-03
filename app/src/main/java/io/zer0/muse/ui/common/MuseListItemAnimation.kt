package io.zer0.muse.ui.common

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion

// E5 (Especialización front-end H4): Primitivas de animación de elementos de lista — Admisión unificada a la lista principal./Transición de desplazamiento.
// Consistente con las especificaciones de animación de la línea de mensajes de ChatScreen(SLOW_MS Fundido de aparición + desplazamiento NORMAL_MS);
// reduced-motion Bajar a 0 duración(Llega inmediatamente,No reproduzcas animación.).
// Uso:Aplicar a la raíz del elemento dentro del bloque de elementos de LazyColumn/LazyRow
//   items(list) { item -> Box(museAnimateItem()) { ItemView(...) } }
// Nota:MuseMotion.isReducedMotion() para @Composable(Leer la configuración del sistema),Por lo tanto, esta primitiva también debe ser @Composable.
@Composable
internal fun LazyItemScope.museAnimateItem(enabled: Boolean = true): Modifier {
    val reducedMotion = MuseMotion.isReducedMotion()
    val animate = enabled && !reducedMotion
    return Modifier.animateItem(
        fadeInSpec = if (!animate) {
            MuseMotion.tween(0)
        } else {
            MuseMotion.tween(MuseAnimation.SLOW_MS, easing = MuseAnimation.EaseOutCubic)
        },
        placementSpec = if (!animate) {
            MuseMotion.tween(0)
        } else {
            MuseMotion.tween(MuseAnimation.NORMAL_MS, easing = MuseAnimation.EaseOutCubic)
        },
    )
}
