package io.zer0.muse.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * P2-9(informe de auditoría): Hacer desplazamiento horizontal/La superficie de paso de página exime el gesto de retorno del sistema.
 *
 * Problemas señalados en el informe:
 *  - Almacén lleno 0 plazas `systemGestureExclusionRects`
 *  - `MusePaddings.screen = 16.dp`, el contenido está a solo 16 dp del borde de la pantalla,
 *    El sistema vuelve al área de gestos, normalmente a la izquierda y a la derecha. 20dp → Al deslizar el dedo desde el borde, se activará el retorno primero
 *  - Afectados: Inicio HorizontalPager, lista de conversaciones SwipeToDismiss, control de borde de captura rápida, varios LazyRow
 *
 * Uso (agregado a**Horizontales**Superficie de gesto):
 * ```
 * LazyRow(modifier = Modifier.horizontalGestureExclusion())
 * ```
 *
 * Puntos de implementación:`systemGestureExclusionRects` si**Compartiendo toda la vista**lista de
 * Las respectivas asignaciones de múltiples componentes se sobrescribirán entre sí. Aquí, el acumulador se utiliza para consolidar y reescribir.
 * Tenga en cuenta que acepta [android.graphics.Rect], no un Rect de Compose.
 */
@Composable
fun Modifier.horizontalGestureExclusion(width: Dp = 24.dp): Modifier {
    val density = LocalDensity.current
    val view = LocalView.current
    val stripPx: Int = with(density) { width.roundToPx() }

    val registry = remember(view) { GestureExclusionRegistry(view) }
    val token = remember(registry) { registry.acquire() }

    DisposableEffect(registry, token) {
        onDispose { registry.release(token) }
    }

    return this.onGloballyPositioned { coords ->
        val bounds = coords.boundsInWindow()
        if (bounds.width <= 0f || stripPx <= 0) return@onGloballyPositioned
        registry.update(
            token,
            listOf(
                android.graphics.Rect(
                    bounds.left.toInt(),
                    bounds.top.toInt(),
                    (bounds.left + stripPx).toInt(),
                    bounds.bottom.toInt(),
                ),
                android.graphics.Rect(
                    (bounds.right - stripPx).toInt(),
                    bounds.top.toInt(),
                    bounds.right.toInt(),
                    bounds.bottom.toInt(),
                ),
            ),
        )
    }
}

/**
 * Acumulador de área de exclusión de gestos: varios componentes comparten una lista de exclusión en una Vista.
 */
private class GestureExclusionRegistry(private val view: android.view.View) {
    private val regions = mutableMapOf<Int, List<android.graphics.Rect>>()
    private var nextToken = 0

    @Synchronized
    fun acquire(): Int = nextToken++

    @Synchronized
    fun update(token: Int, rects: List<android.graphics.Rect>) {
        regions[token] = rects
        flush()
    }

    @Synchronized
    fun release(token: Int) {
        regions.remove(token)
        flush()
    }

    private fun flush() {
        val merged = regions.values.flatten()
        runCatching { view.systemGestureExclusionRects = merged }
    }
}
