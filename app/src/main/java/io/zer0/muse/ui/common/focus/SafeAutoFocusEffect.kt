package io.zer0.muse.ui.common.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * v1.0.74 fix: Enfoque automático seguro — Resolviendo fallas ocasionales en Huawei Android 10
 * `IllegalStateException: FocusRequester is not initialized`.
 *
 * Causa fundamental del colapso:Inmediatamente en LaunchedEffect requestFocus(),Sin embargo, es posible que los componentes de destino aún no estén ensamblados.:
 *  - LazyColumn item Los contenidos de se combinan en la etapa de medida.(combinación de retardo),y LaunchedEffect
 *    Comienza inmediatamente después de aplicar los cambios de composición. → focusRequester modifier todavía no attach
 *  - Rama de representación condicional(isSearching espera)No sincronizado con el ciclo de vida del efecto. → ventana de carrera
 *
 * Reparación:Espere un fotograma primero(Asegúrese de que todas las medidas/combinaciones del cuadro anterior estén completas,modifier Ya attach),
 * Solicitar enfoque nuevamente;Si aún falla, se omitirá silenciosamente.(no choques,Enfoque natural para la próxima interacción).
 */
@Composable
fun SafeAutoFocusEffect(focusRequester: FocusRequester) {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }
}

/** Igual que arriba,Pero las condiciones desencadenantes pueden ser controladas por la persona que llama.(Úselo cuando necesite leer otro estado).
 * v1.0.74 fix (Auditoría inicial 3.8): Originales LaunchedEffect(Unit) Solo evaluado una vez condition,
 * Si la primera condición del marco es false(Si el estado de búsqueda se establece en verdadero más adelante)Después de eso, nunca vuelve a enfocarse.
 * Utilice snapshotFlow para observar en su lugar,Concéntrese cuando la condición se haga realidad.
 */
@Composable
fun SafeAutoFocusEffect(condition: () -> Boolean, focusRequester: FocusRequester) {
    LaunchedEffect(focusRequester) {
        snapshotFlow { condition() }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                withFrameNanos { }
                runCatching { focusRequester.requestFocus() }
            }
    }
}
