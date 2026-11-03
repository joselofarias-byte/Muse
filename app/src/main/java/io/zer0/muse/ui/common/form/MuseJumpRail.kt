package io.zer0.muse.ui.common.form

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Barra de salto a la derecha**posicionamiento continuo**Gesto (v2.4.5 unificación).
 *
 * fondo:Hay muchos lugares en la aplicación."Posicionamiento rápido de la barra vertical derecha."——Mapa de mensajes de chat, configuración de barra de sección de tutorial, índice alfabético de libreta de direcciones de teléfonos celulares pequeños.
 * La antigua implementación de la unidad es"prensa → Convertir en un artículo → scrollToItem",entonces:
 *  - Sólo puede detenerse en los límites de los elementos,Salta un fotograma a la vez al arrastrar,La sensación no es un desplazamiento lineal.;
 *  - El contenido extenso quiere saltar entre dos pantallas,Primero debes aterrizar en un elemento.
 *
 * Este modificador pone"Arrastrar → proporción"Esta sección converge en una comprensión,Depende de la persona que llama decidir cómo posicionar la proporción después de obtenerla.
 * (La estructura de lista de diferentes páginas es diferente.:Algunos tienen elementos adicionales delante y algunas entradas no son coherentes con el índice de la lista de objetivos.).
 *
 * La persona que llama normalmente convierte él mismo la proporción en"Posicionamiento aproximado + ajuste fino de entradas de subartículos"para conseguir una sensación lineal —— Referencia
 * [io.zer0.muse.ui.chat.MessageMapBar] con `SettingsTutorialPage` uso.
 *
 * @param onFraction Devolución de llamada de relación de arrastre(0..1,Ya clamp);y Cuanto más grande es, más bajo es.
 * @param onDragStateChange Arrastra para comenzar/finalizar la devolución de llamada,Permite a la persona que llama controlar la visualización y ocultación de la capa flotante.
 */
@Composable
fun Modifier.museJumpRailDrag(onFraction: (Float) -> Unit, onDragStateChange: (Boolean) -> Unit = {}): Modifier = this.pointerInput(Unit) {
    detectDragGestures(
        onDragStart = { offset ->
            onDragStateChange(true)
            onFraction((offset.y / size.height).coerceIn(0f, 1f))
        },
        onDrag = { change, _ ->
            change.consume()
            onFraction((change.position.y / size.height).coerceIn(0f, 1f))
        },
        onDragEnd = { onDragStateChange(false) },
        onDragCancel = { onDragStateChange(false) },
    )
}

/**
 * Asistente de posicionamiento lineal:Convertir la relación de arrastre a"Cantidad de ajuste fino de entrada objetivo + subentrada".
 *
 * regresó [RailTarget.index] es el índice de entrada,[RailTarget.subFraction] es la escala vertical dentro del elemento
 * (0..1). Para personas que llaman `listState.scrollToItem(offset + index)` después,Luego presione altura promedio del artículo
 * `scrollBy(subFraction * avgItemPx)` Puede obtener un posicionamiento continuo.
 */
data class RailTarget(val index: Int, val subFraction: Float)

/**
 * Convierta la relación de arrastre a la relación entre el elemento de destino y el subelemento.
 *
 * @param fraction Escala de arrastre(0..1).
 * @param itemCount Número total de entradas.
 */
fun railTargetFor(fraction: Float, itemCount: Int): RailTarget {
    if (itemCount <= 1) return RailTarget(0, 0f)
    val clamped = fraction.coerceIn(0f, 1f)
    val target = clamped * (itemCount - 1)
    val index = target.toInt().coerceIn(0, itemCount - 1)
    val subFraction = (target - index).coerceIn(0f, 0.99f)
    return RailTarget(index, subFraction)
}
