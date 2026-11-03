@file:Suppress("FunctionNaming")

package io.zer0.muse.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.UIMessage
import io.zer0.muse.ui.common.form.LinearScrollGesture
import io.zer0.muse.ui.common.form.RAIL_TOUCH_WIDTH
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * A6: Posicionamiento rápido de sesiones largas. — Área activa en el lado derecho del área de chat.
 *
 * Sesión larga (número de mensajes ≥ [MESSAGE_MAP_MIN_MESSAGES]) al conservar la zona caliente transparente del borde derecho:
 * - Generalmente completamente invisible;
 * - **Pulsación larga**Después de la activación (vibración ligera), deslice hacia arriba y hacia abajo para desplazarse linealmente (v2.5.9 No se dibujarán más pistas visuales,
 *   ver la razón [LinearScrollGesture] descripción de mapeo incremental);
 * - Aparecerá una vista previa del mensaje de ubicación durante el deslizamiento para ayudar con el posicionamiento.
 *
 * Oclusión: el área caliente es una tira delgada adherida al borde derecho, que no cubre la línea de operación en la parte inferior del mensaje; la propia línea de operación también cede hacia adentro.
 * La preservación de la posición de desplazamiento entre sesiones está a cargo del caché listState de ChatViewModel. Este componente solo maneja la navegación.
 */
internal const val MESSAGE_MAP_MIN_MESSAGES = 5

/**
 * Toque el ancho de la zona activa (= El espacio de la derecha ocupado por la barra de navegación).
 *
 * v2.5.0:24dp Si se pega al borde derecho de la pantalla, el gesto de retorno del sistema de Android 10+, que se amplió a 40 dp en ese momento, se comerá.
 * v2.6.10(comentarios de los usuarios):40dp Deje una gran franja de espacio en blanco en el lado derecho del área del mensaje, que obviamente es diferente del diseño sin mapa.
 * recibido 26dp —— Ligeramente más ancha que la banda de gestos de retorno (alrededor de 20 dp), una pulsación larga todavía es suficiente para sentir, pero ya no ocupa la mitad del espacio de la pantalla.
 */
internal val MESSAGE_MAP_TOUCH_WIDTH: Dp = 26.dp

/** El lado derecho de la lista de mensajes es el ancho total reservado para la zona activa (zona activa + espacio para respirar). */
internal val MESSAGE_MAP_RESERVED_WIDTH = MESSAGE_MAP_TOUCH_WIDTH + 2.dp

@Composable
internal fun MessageMapBar(messages: List<UIMessage>, listState: LazyListState, messageStartIndex: Int, modifier: Modifier = Modifier) {
    val total = messages.size
    if (total == 0) return

    var activeIndex by remember { mutableStateOf<Int?>(null) }
    // Comentarios de los usuarios: La línea de aviso solo aparece durante la navegación deslizante y normalmente está completamente oculta.
    var gestureActive by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        // Comentarios de los usuarios: "La función de navegación con pulsación larga de la barra lateral desapareció"—— La función es siempre
        // (e56b1920 Para arreglar el "control deslizante visual que flota aleatoriamente", eliminé la pista y la cambié para presionar prolongadamente + arriba y abajo).
        // Pero ser completamente invisible hace que sea imposible descubrirlo.
        //
        // Aquí hay uno más**Aparece sólo durante la operación**La línea de aviso: generalmente invisible, no interfiere con la lectura;
        // Después de presionar prolongadamente para activarlo, aparecerá instantáneamente y pasará al estado activado, y desaparecerá después de soltarlo.
        // No hay mapeo de posición, por lo que no se reproducirá el problema de flotación.
        androidx.compose.animation.AnimatedVisibility(
            visible = gestureActive,
            enter = androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(120),
            ),
            exit = androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(220),
            ),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(0.62f)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            )
        }

        LinearScrollGesture(
            listState = listState,
            onActiveIndexChange = { listIndex ->
                activeIndex = listIndex?.let { (it - messageStartIndex).takeIf { rel -> rel in 0 until total } }
            },
            onActiveChange = { gestureActive = it },
        )

        val previewMessage = activeIndex?.let { messages.getOrNull(it) }
        if (previewMessage != null) {
            MessageMapTooltip(
                msg = previewMessage,
                modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = (-(RAIL_TOUCH_WIDTH - 4.dp)))
                    .padding(vertical = 0.dp),
            )
        }
    }
}

/** A6: Mapa de mensajes vista previa deslizante capa flotante — Muestra los primeros 40 caracteres del mensaje de ubicación. */
@Composable
private fun MessageMapTooltip(msg: UIMessage, modifier: Modifier = Modifier) {
    // v2.6.8: La capa flotante es texto visible para el usuario y la cola del estado de la tarjeta debe retirarse primero y luego truncarse. ——
    // De lo contrario take(40) Lo más probable es que sólo haya sido interceptado. cardId= Fragmento JSON al principio.
    val previewText =
        io.zer0.muse.transformer.InternalMarkupSanitizer.displayContent(msg.content)
            .replace('\n', ' ')
            .trim()
            .take(40)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
        shape = MuseShapes.medium,
        tonalElevation = 2.dp,
        modifier = modifier.widthIn(max = 220.dp),
    ) {
        Text(
            text = previewText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier =
            Modifier.padding(
                horizontal = MusePaddings.tightGap,
                vertical = MusePaddings.tinyGap,
            ),
        )
    }
}
