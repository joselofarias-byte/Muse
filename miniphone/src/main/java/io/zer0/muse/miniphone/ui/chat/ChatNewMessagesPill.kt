package io.zer0.muse.miniphone.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * Avance de conteo para alertas de mensajes nuevos:Una observación de "estado de la lista + número de mensajes" → El nuevo número de elementos que se mostrarán.
 *
 * tres reglas,Sólo se fijan en el estatus, no en los gestos:
 * - La gente ya está en el fondo.:No se "pierde" nada,El conteo siempre vuelve a cero.(Los mensajes nuevos se ven inmediatamente)
 * - La gente no está abajo y el número de bares aumenta:Agregue este incremento al recuento existente
 * - El número de artículos no ha cambiado.(o menos,Por ejemplo, si elimina un elemento usted mismo):el conteo no se mueve,No se convierte en un número negativo aunque no se borre.
 *
 * @param previousTotal El número de mensajes observados por última vez.
 * @param currentTotal El número de mensajes observados esta vez.
 * @param atBottom ¿Se detuvo la persona en el último elemento al observar este momento?
 * @param displayed El número de elementos mostrados actualmente.
 */
internal fun advanceNewMessageCount(previousTotal: Int, currentTotal: Int, atBottom: Boolean, displayed: Int): Int {
    if (atBottom) return 0
    val arrived = currentTotal - previousTotal
    return if (arrived > 0) displayed.coerceAtLeast(0) + arrived else displayed.coerceAtLeast(0)
}

/**
 * ¿La gente se detiene en el último?
 *
 * Diseño de orden inverso(`reverseLayout`)El subíndice 0 es el último.,Adjunto al origen de la ventana gráfica;Una vez que apareció,Se desliza fuera de la ventana gráfica.,
 * Lo que es visible en la primera pantalla ya no contiene el subíndice 0. Criterios para el diseño de resultados de listas de solo lectura,No hay distancia de rodadura acumulada ——
 * Las sacudidas rápidas y los cambios en la altura del contenido no provocarán que se distorsione.
 */
internal fun LazyListState.isAtNewestItem(): Boolean {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return true
    val newest = info.visibleItemsInfo.firstOrNull { item -> item.index == 0 } ?: return false
    // mientras descansa en el fondo,El punto de partida del último cae exactamente en el "punto de partida de la ventana gráfica + relleno inicial"
    return newest.offset - (info.viewportStartOffset + info.beforeContentPadding) <= BOTTOM_TOLERANCE_PX
}

/**
 * Esté atento al estado de la lista y la cantidad de mensajes.,Informe el número derivado de elementos que se mostrarán a la persona que llama.
 *
 * usar [snapshotFlow] en lugar de devolución de llamada de desplazamiento:El diseño de la lista y la cantidad de mensajes en sí son estados observables.,
 * Para cada salto, ¿se vuelve a deducir "¿está en el fondo"?,No es necesario acumular distancia de desplazamiento.
 *
 * @param messageTotal Número total de mensajes actuales(No incluye líneas de marcador de posición como separación de tiempo y "escritura")
 * @param onCountChanged Se le devuelve la llamada cuando cambia el número de elementos mostrados;Un valor de 0 indica que la cápsula no debe mostrarse
 */
@Composable
internal fun NewMessagesEffect(listState: LazyListState, messageTotal: Int, onCountChanged: (Int) -> Unit) {
    val latestTotal by rememberUpdatedState(messageTotal)
    val latestCallback by rememberUpdatedState(onCountChanged)
    LaunchedEffect(listState) {
        var previous = latestTotal
        var shown = 0
        snapshotFlow { latestTotal to listState.isAtNewestItem() }
            .collect { (total, atBottom) ->
                val next = advanceNewMessageCount(
                    previousTotal = previous,
                    currentTotal = total,
                    atBottom = atBottom,
                    displayed = shown,
                )
                previous = total
                if (next != shown) {
                    shown = next
                    latestCallback(next)
                }
            }
    }
}

/** Cápsula de consejos:una corta franja flotante,Haga clic para volver a lo último. */
@Composable
internal fun ChatNewMessagesPill(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Text(
        text = newMessagePillLabel(count),
        style = typography.caption.copy(color = colors.onAccent),
        modifier = modifier
            .testTag(MiniPhoneChatTags.NEW_MESSAGES_PILL)
            .clip(RoundedCornerShape(pillCorner))
            .background(colors.accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** palabras en la capsula:Número de elementos + frase \"Hay algunos elementos que no he leído\". */
internal fun newMessagePillLabel(count: Int): String = "$count nuevos mensajes"

/** Determine la desviación permitida a nivel de píxeles de \"pegarse al fondo\":Es posible que la parada de desplazamiento no caiga exactamente en un píxel entero. */
private const val BOTTOM_TOLERANCE_PX: Int = 2

private val pillCorner = 50.dp
