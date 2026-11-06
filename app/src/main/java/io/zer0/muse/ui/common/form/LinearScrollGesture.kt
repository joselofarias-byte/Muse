package io.zer0.muse.ui.common.form

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * larga sesión**Gesto de desplazamiento lineal**(v2.5.9 para reemplazar la pista visual).
 *
 * Interacción: En la zona activa en el lado derecho de la pantalla.**Pulsación larga**Active (mensaje de vibración ligera), luego deslice hacia arriba y hacia abajo para desplazarse linealmente por el contenido.
 *
 * ## ¿Por qué no hacer pistas visuales?
 *
 * LazyColumn La altura total no está expuesta y solo se puede utilizar"La altura real de los elementos medidos + la extrapolación de la altura promedio de los elementos no medidos"estimación
 * (Ver [LinearScrollModel]). entrada larga (imagen/bloque de código) es mucho mayor que la altura promedio, y el error de estimación
 * Extremadamente grande. cualquiera"Posición absoluta del dedo → Ubicación del contenido de destino"El mapeo (la naturaleza de las órbitas visuales) se verá afectado por errores.
 * Ubicación incorrecta —— Aparece como un arrastre"flotando al azar". El mapeo incremental evita esta contradicción fundamental: presione la línea de base de la instantánea instantánea,
 * Durante el arrastre, sólo**Desplazamiento de los dedos**convertido a**Desplazamiento de contenido**, la línea de base permanece sin cambios → Continuo, imposible de saltar.
 *
 * ## mapeo
 *
 * `Desplazamiento de contenido = Desplazamiento de los dedos/altura de la zona caliente × Rango de desplazamiento accesible`. Equivalente semántico de la barra de desplazamiento: el pulgar se mueve desde la posición actual
 * Al deslizarse por toda la altura de la zona activa, el contenido simplemente se desplaza por todo el rango accesible. El error en la estimación de la altura total sólo afecta"Sensibilidad", no más
 * influencia"Corrección de la colocación".
 *
 * No responderá si aún no se ha medido (el modelo no está disponible), lo que garantiza que no se produzcan daños accidentales.
 */
internal val RAIL_TOUCH_WIDTH: Dp = 40.dp

/** La cantidad de píxeles para compensar el desplazamiento al arrastrar hasta el final (desplazarse hasta el final real, evitando el error de estimación de altura total; el desplazamiento se fijará al límite por sí solo). */
private const val BOTTOM_OVERSCROLL_PX = 1_000_000f

/** Listar altura visible (px). Priorice el tamaño de la ventana gráfica; retroceda al desplazamiento final de la ventana gráfica cuando el diseño aún no esté terminado. */
internal fun listViewportPx(state: LazyListState): Float {
    val h = state.layoutInfo.viewportSize.height.toFloat()
    return if (h > 0f) h else state.layoutInfo.viewportEndOffset.toFloat()
}

/**
 * Modelo de altura del contenido: poner"posición de desplazamiento"Convierta del calibre del número de serie del artículo al calibre del volumen del contenido.
 *
 * LazyColumn Sólo están expuestos los elementos actualmente visibles y la altura total se estima a partir de dos fuentes:
 *  - La altura real del elemento medido (exacta, de visibleItemsInfo);
 *  - Para artículos no medidos"Altura media medida"Extrapolación (aproximación).
 */
internal class LinearScrollModel {
    private val measured = HashMap<Int, Int>()
    private var dirty = true
    private var avgCache = 0f
    private var prefix: FloatArray = FloatArray(0)

    /** Actualice el caché con la altura del elemento actualmente visible (cada llamada de reorganización es suficiente). */
    fun update(state: LazyListState) {
        state.layoutInfo.visibleItemsInfo.forEach { info ->
            if (info.size > 0 && measured[info.index] != info.size) {
                measured[info.index] = info.size
                dirty = true
            }
        }
    }

    private fun avgHeight(): Float {
        if (measured.isEmpty()) return 0f
        if (dirty) avgCache = measured.values.sum().toFloat() / measured.size
        return avgCache
    }

    /** Reconstruir la suma del prefijo:prefix[i] = La altura acumulada de los primeros i elementos (px). */
    private fun ensurePrefix(count: Int) {
        if (!dirty && prefix.size == count + 1) return
        val avg = avgHeight()
        val arr = FloatArray(count + 1)
        var sum = 0f
        for (i in 0 until count) {
            arr[i] = sum
            sum += measured[i]?.toFloat() ?: avg
        }
        arr[count] = sum
        prefix = arr
        dirty = false
    }

    /** Altura total estimada del contenido (px); devuelve 0 cuando no hay medición. */
    fun totalPx(state: LazyListState): Float {
        val avg = avgHeight()
        if (avg <= 0f) return 0f
        val count = state.layoutInfo.totalItemsCount
        ensurePrefix(count)
        return prefix.getOrElse(count) { 0f }
    }

    /** No. [index] El desplazamiento inicial de la barra (px). */
    fun offsetPx(index: Int): Float {
        val safe = index.coerceAtLeast(0)
        return if (safe < prefix.size) prefix[safe] else 0f
    }

    /** desplazamiento del contenido desplazado (px). */
    fun scrolledPx(state: LazyListState): Float {
        ensurePrefix(state.layoutInfo.totalItemsCount)
        return offsetPx(state.firstVisibleItemIndex) + state.firstVisibleItemScrollOffset
    }

    /** desplazamiento de contenido (px) cae en qué entrada (verificación inversa usando el prefijo actual). */
    fun indexAtOffset(offset: Float): Int {
        if (prefix.size <= 1) return 0
        var i = 0
        val last = prefix.size - 2
        while (i < last && prefix[i + 1] <= offset) i++
        return i
    }
}

/**
 * Mantenga presionado el lado derecho para desplazarse linealmente por la zona activa.
 *
 * @param listState Lista de objetivos.
 * @param onActiveIndexChange entrada de golpe deslizante**lista de índice absoluto**devolución de llamada (null indica el final).
 */
@Suppress("FunctionNaming")
@Composable
internal fun LinearScrollGesture(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onActiveIndexChange: ((Int?) -> Unit)? = null,
    /** Una pulsación larga activa el cambio de estado (true = Inicie la navegación deslizante,false = final). Se utiliza para mostrar y ocultar señales visuales. */
    onActiveChange: ((Boolean) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var active by remember { mutableStateOf(false) }
    val scrollJob = remember { mutableStateOf<Job?>(null) }
    val model = remember { LinearScrollModel() }
    // Durante la activación**congelar**Modelo de altura: El punto de referencia toma una instantánea cuando se establece una pulsación larga y no acepta nuevas medidas durante el deslizamiento.
    // garantia"Desplazamiento de los dedos → Desplazamiento de contenido"El factor de conversión es estable.
    if (!active) model.update(listState)

    LaunchedEffect(active) {
        if (!active) onActiveIndexChange?.invoke(null)
        onActiveChange?.invoke(active)
    }

    Box(
        modifier =
        modifier
            .width(RAIL_TOUCH_WIDTH)
            .fillMaxHeight()
            .pointerInput(listState) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Mantenga presionado para activar; pulsación corta/Desliza rápidamente sin bloquear y deja que el evento termine de forma natural.
                    val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    active = true
                    runLinearDrag(down, longPress.position.y, model, listState, scrollJob, scope, onActiveIndexChange)
                    active = false
                }
            },
    )
}

/**
 * Se establece el ciclo de arrastre después de una pulsación larga: convierta el desplazamiento del dedo en desplazamiento de contenido (mapeo incremental).
 *
 * `Desplazamiento de contenido = Desplazamiento de los dedos/altura de la zona caliente × Rango de desplazamiento accesible`;El punto de referencia toma una instantánea cuando se establece una pulsación larga y permanece sin cambios durante todo el proceso.
 */
@Suppress("LongParameterList")
private suspend fun AwaitPointerEventScope.runLinearDrag(
    down: PointerInputChange,
    startY: Float,
    model: LinearScrollModel,
    listState: LazyListState,
    scrollJob: MutableState<Job?>,
    scope: CoroutineScope,
    onActiveIndexChange: ((Int?) -> Unit)?,
) {
    val railHeight = size.height.toFloat().coerceAtLeast(1f)
    val totalPx = model.totalPx(listState)
    val viewportPx = listViewportPx(listState)
    val range = (totalPx - viewportPx).coerceAtLeast(0f)
    val startScroll = model.scrolledPx(listState)
    val count = listState.layoutInfo.totalItemsCount
    // Deslízate por toda la altura de la zona caliente. = Desplácese por todo el rango accesible.
    val sensitivity = if (railHeight > 0f) range / railHeight else 0f

    fun seek(targetPx: Float) {
        onActiveIndexChange?.invoke(model.indexAtOffset(targetPx.coerceIn(0f, range)))
        scrollJob.value?.cancel()
        scrollJob.value =
            scope.launch {
                seekList(listState, targetPx, range, count, model)
            }
    }

    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: return
        if (!change.pressed) return
        change.consume()
        if (range > 0f) seek(startScroll + (change.position.y - startY) * sensitivity)
    }
}

/** Lista de posicionamiento de compensación por contenido de destino: ambos extremos caen directamente al cabezal real/Final verdadero, medio por entrada + desplazamiento dentro de la entrada. */
private suspend fun seekList(listState: LazyListState, targetPx: Float, range: Float, count: Int, model: LinearScrollModel) {
    when {
        targetPx <= 0f -> listState.scrollToItem(0)
        count > 0 && range > 0f && targetPx >= range -> {
            listState.scrollToItem(count - 1)
            listState.scrollBy(BOTTOM_OVERSCROLL_PX)
        }
        else -> {
            val t = targetPx.coerceIn(0f, range)
            val index = model.indexAtOffset(t)
            val within = (t - model.offsetPx(index)).coerceAtLeast(0f)
            listState.scrollToItem(index, within.toInt())
        }
    }
}
