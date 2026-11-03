package io.zer0.muse.miniphone.ui.chat

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import io.zer0.muse.miniphone.data.db.MpMessageType

/**
 * Qué puedes hacer manteniendo presionado un mensaje.
 *
 * El valor sólo describe la "operación",[label] Es esa línea en el menú. —— Las reglas de decisión se centran en [chatMenuActionsFor],
 * La interfaz solo dibuja en orden los artículos devueltos.
 */
internal enum class ChatMenuAction(
    val label: String,
) {
    COPY("Copiar"),
    DELETE("Eliminar"),
    RESEND("Reenviar"),
}

/**
 * Mantenga presionado el elemento del menú para ver un mensaje.,El orden es el orden de presentación.
 *
 * - Copiar:Sólo el texto tiene cuerpo copiable.,El texto de la imagen y la voz es solo la copia de vista previa.
 * - Eliminar:Los mensajes enviados por cada parte se pueden eliminar
 * - Reenviar:Sólo mensajes de texto enviados por ti mismo,El reenvío toma el enlace "solicitar una respuesta más".
 *
 * El aviso del sistema no es lo que dijo ninguna de las partes.,El menú completo no lo delata.
 */
internal fun chatMenuActionsFor(message: ChatMessageUi): List<ChatMenuAction> {
    if (message.isSystem) return emptyList()
    return buildList {
        if (message.type == MpMessageType.TEXT) add(ChatMenuAction.COPY)
        add(ChatMenuAction.DELETE)
        if (message.canResend) add(ChatMenuAction.RESEND)
    }
}

/**
 * Ubicación del menú:Sobre la línea con el mensaje.,Alinee a izquierda y derecha con el lado donde está el mensaje.
 *
 * Si no hay suficiente espacio arriba, desplácese hasta el final de la fila.;Escondido en los márgenes de ambos lados,No se pegará al borde de la pantalla.
 */
internal class ChatMenuPositionProvider(
    private val alignEnd: Boolean,
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val maxY = (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
        val rawX = if (alignEnd) {
            anchorBounds.right - popupContentSize.width - marginPx
        } else {
            anchorBounds.left + marginPx
        }
        val aboveY = anchorBounds.top - popupContentSize.height - gapPx
        val rawY = if (aboveY >= marginPx) aboveY else anchorBounds.bottom + gapPx
        return IntOffset(rawX.coerceIn(marginPx, maxX), rawY.coerceIn(marginPx, maxY))
    }
}
