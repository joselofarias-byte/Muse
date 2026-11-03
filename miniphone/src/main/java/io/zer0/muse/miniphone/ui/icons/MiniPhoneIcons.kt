package io.zer0.muse.miniphone.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Pequeño conjunto de iconos autodibujados de teléfono celular.
 *
 * Usar todo [ImageVector.Builder] dibujar del camino,Un camino y un conjunto de coordenadas.,No cites la biblioteca de íconos ni la expliques detalladamente. Material Icons:
 * Los íconos del escritorio, la barra de estado y los botones del encabezado requieren el mismo trazo(1.7 Unidad Línea Delgada + Extremo Redondo),Se desmorona cuando cambias la biblioteca.
 *
 * El color es siempre negro puro.,por `Icon(tint = ...)` Teñido uniforme.
 */

private val IconSize = 24.dp
private const val VIEWPORT_SIZE = 24f
private const val LINE_WIDTH = 1.7f
private const val THIN_LINE_WIDTH = 1.5f

/** Coeficiente de aproximación del arco:Desplazamiento del punto de control equivalente a Bézier de cuatro segmentos de radio r. */
private const val ARC_FACTOR = 0.5523f

private fun lineIcon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = IconSize,
    defaultHeight = IconSize,
    viewportWidth = VIEWPORT_SIZE,
    viewportHeight = VIEWPORT_SIZE,
).apply(block).build()

private fun ImageVector.Builder.strokePath(width: Float = LINE_WIDTH, pathBuilder: PathBuilder.() -> Unit) {
    path(
        pathBuilder = pathBuilder,
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}

private fun ImageVector.Builder.fillPath(pathBuilder: PathBuilder.() -> Unit) {
    path(pathBuilder = pathBuilder, fill = SolidColor(Color.Black))
}

/** redondo:Cuatro secciones de ortografía cúbica de Bézier,para avatar/Los gráficos como las brújulas deben ser redondos. */
private fun PathBuilder.circle(cx: Float, cy: Float, radius: Float) {
    val k = radius * ARC_FACTOR
    moveTo(cx - radius, cy)
    curveTo(cx - radius, cy - k, cx - k, cy - radius, cx, cy - radius)
    curveTo(cx + k, cy - radius, cx + radius, cy - k, cx + radius, cy)
    curveTo(cx + radius, cy + k, cx + k, cy + radius, cx, cy + radius)
    curveTo(cx - k, cy + radius, cx - radius, cy + k, cx - radius, cy)
    close()
}

/** rectángulo redondeado:Las cuatro esquinas también usan Bezier.,El radio de la esquina es tan pequeño como 1 y no está borroso. */
private fun PathBuilder.roundedRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
    val k = radius * ARC_FACTOR
    moveTo(left + radius, top)
    horizontalLineTo(right - radius)
    curveTo(right - radius + k, top, right, top + radius - k, right, top + radius)
    verticalLineTo(bottom - radius)
    curveTo(right, bottom - radius + k, right - radius + k, bottom, right - radius, bottom)
    horizontalLineTo(left + radius)
    curveTo(left + radius - k, bottom, left, bottom - radius + k, left, bottom - radius)
    verticalLineTo(top + radius)
    curveTo(left, top + radius - k, left + radius - k, top, left + radius, top)
    close()
}

/** barra de estado:señal de cuatro cuadros,Cuanto más alto, más fuerte. */
private fun signalIcon(): ImageVector = lineIcon("mp-signal") {
    strokePath(width = 2.2f) {
        moveTo(4f, 15.6f)
        lineTo(4f, 18.6f)
        moveTo(8.6f, 12.8f)
        lineTo(8.6f, 18.6f)
        moveTo(13.2f, 9.8f)
        lineTo(13.2f, 18.6f)
        moveTo(17.8f, 6.4f)
        lineTo(17.8f, 18.6f)
    }
}

/** barra de estado:Wi-Fi,Tres arcos más un punto. */
private fun wifiIcon(): ImageVector = lineIcon("mp-wifi") {
    strokePath {
        moveTo(3f, 9.4f)
        curveTo(5.4f, 7.2f, 8.6f, 6f, 12f, 6f)
        curveTo(15.4f, 6f, 18.6f, 7.2f, 21f, 9.4f)
    }
    strokePath {
        moveTo(6.2f, 12.9f)
        curveTo(7.8f, 11.5f, 9.8f, 10.6f, 12f, 10.6f)
        curveTo(14.2f, 10.6f, 16.2f, 11.5f, 17.8f, 12.9f)
    }
    strokePath {
        moveTo(9.4f, 16.2f)
        curveTo(10.1f, 15.6f, 11f, 15.2f, 12f, 15.2f)
        curveTo(13f, 15.2f, 13.9f, 15.6f, 14.6f, 16.2f)
    }
    fillPath { circle(12f, 19.2f, 1.2f) }
}

/** barra de estado:Caja de alimentación + electrodo + bloque de alimentación. */
private fun batteryIcon(): ImageVector = lineIcon("mp-battery") {
    strokePath(width = THIN_LINE_WIDTH) { roundedRect(2.6f, 7.4f, 19.2f, 16.6f, 2.8f) }
    fillPath { roundedRect(19.8f, 10.6f, 21.6f, 13.4f, 1.2f) }
    fillPath { roundedRect(4.6f, 9.4f, 14.2f, 14.6f, 1.8f) }
}

/** noticias:Una burbuja con una cola inferior izquierda. */
private fun messageIcon(): ImageVector = lineIcon("mp-message") {
    strokePath {
        moveTo(5.6f, 4f)
        horizontalLineTo(18.4f)
        curveTo(19.5f, 4f, 20.4f, 4.9f, 20.4f, 6f)
        verticalLineTo(13.8f)
        curveTo(20.4f, 14.9f, 19.5f, 15.8f, 18.4f, 15.8f)
        horizontalLineTo(9.6f)
        lineTo(5.6f, 19.6f)
        verticalLineTo(15.8f)
        curveTo(4.5f, 15.8f, 3.6f, 14.9f, 3.6f, 13.8f)
        verticalLineTo(6f)
        curveTo(3.6f, 4.9f, 4.5f, 4f, 5.6f, 4f)
        close()
    }
}

/** libreta de direcciones:una tarjeta de contacto —— La tarjeta perfila una figura humana. */
private fun contactsIcon(): ImageVector = lineIcon("mp-contacts") {
    strokePath { roundedRect(4f, 3.4f, 20f, 20.6f, 3.2f) }
    strokePath { circle(12f, 9.6f, 2.7f) }
    strokePath {
        moveTo(7.8f, 17.2f)
        curveTo(7.8f, 14.7f, 9.7f, 13.2f, 12f, 13.2f)
        curveTo(14.3f, 13.2f, 16.2f, 14.7f, 16.2f, 17.2f)
    }
}

/** descubrir:brújula,Agrega una aguja oblicua al anillo exterior. */
private fun discoverIcon(): ImageVector = lineIcon("mp-discover") {
    strokePath { circle(12f, 12f, 8.4f) }
    fillPath {
        moveTo(15.9f, 8.1f)
        lineTo(13.4f, 13.4f)
        lineTo(8.1f, 15.9f)
        lineTo(10.6f, 10.6f)
        close()
    }
}

/** yo:una sola figura,Separado de la libreta de direcciones y de las tarjetas de visita. */
private fun meIcon(): ImageVector = lineIcon("mp-me") {
    strokePath { circle(12f, 7.8f, 3.4f) }
    strokePath {
        moveTo(5f, 19.6f)
        curveTo(5f, 15.9f, 8.1f, 13.4f, 12f, 13.4f)
        curveTo(15.9f, 13.4f, 19f, 15.9f, 19f, 19.6f)
    }
}

/** Momentos:una estrella de cuatro puntas,No choca con el disco de la brújula. */
private fun momentsIcon(): ImageVector = lineIcon("mp-moments") {
    strokePath(width = 1.6f) {
        moveTo(12f, 3.4f)
        lineTo(13.9f, 10.1f)
        lineTo(20.6f, 12f)
        lineTo(13.9f, 13.9f)
        lineTo(12f, 20.6f)
        lineTo(10.1f, 13.9f)
        lineTo(3.4f, 12f)
        lineTo(10.1f, 10.1f)
        close()
    }
}

/** La flecha que apunta a la derecha de la lista. */
private fun chevronIcon(): ImageVector = lineIcon("mp-chevron") {
    strokePath(width = 1.6f) {
        moveTo(9.6f, 6.6f)
        lineTo(15f, 12f)
        lineTo(9.6f, 17.4f)
    }
}

/** Botón \"Volver al escritorio\" en la parte superior de la página:Una pantalla más una línea horizontal de Inicio. */
private fun homeIcon(): ImageVector = lineIcon("mp-home") {
    strokePath(width = THIN_LINE_WIDTH) { roundedRect(6.4f, 3.2f, 17.6f, 20.8f, 2.8f) }
    strokePath(width = 1.6f) {
        moveTo(9.8f, 17.4f)
        lineTo(14.2f, 17.4f)
    }
}

/** Flecha de retorno del encabezado:Una barra diagonal más una línea horizontal,con el derecho [chevronIcon] Distinguir. */
private fun backIcon(): ImageVector = lineIcon("mp-back") {
    strokePath(width = 1.8f) {
        moveTo(19f, 12f)
        horizontalLineTo(5.4f)
    }
    strokePath(width = 1.8f) {
        moveTo(11f, 5.4f)
        lineTo(4.6f, 12f)
        lineTo(11f, 18.6f)
    }
}

/** enviar:Un avión de papel mirando hacia arriba y hacia la derecha. */
private fun sendIcon(): ImageVector = lineIcon("mp-send") {
    strokePath(width = 1.7f) {
        moveTo(4.2f, 11.4f)
        lineTo(20.2f, 4.4f)
        lineTo(14.4f, 20.2f)
        lineTo(11.6f, 13.2f)
        close()
    }
    strokePath(width = 1.5f) {
        moveTo(11.6f, 13.2f)
        lineTo(20.2f, 4.4f)
    }
}

/** Entrada de imagen:Marco de imagen + sol + cresta. */
private fun imageIcon(): ImageVector = lineIcon("mp-image") {
    strokePath(width = THIN_LINE_WIDTH) { roundedRect(3.4f, 5.2f, 20.6f, 18.8f, 3f) }
    strokePath(width = 1.6f) { circle(9.2f, 10.2f, 1.7f) }
    strokePath(width = 1.6f) {
        moveTo(4.6f, 16.4f)
        lineTo(9.6f, 12.6f)
        lineTo(13.2f, 15.6f)
        lineTo(15.8f, 13.6f)
        lineTo(19.4f, 16.6f)
    }
}

/** la imagen esta rota:mismo marco de fotos,Hay una grieta en el medio. */
private fun brokenImageIcon(): ImageVector = lineIcon("mp-image-broken") {
    strokePath(width = THIN_LINE_WIDTH) { roundedRect(3.4f, 5.2f, 20.6f, 18.8f, 3f) }
    strokePath(width = 1.6f) {
        moveTo(8.2f, 5.6f)
        lineTo(11.8f, 10.4f)
        lineTo(9.6f, 12.8f)
        lineTo(13.6f, 15.2f)
        lineTo(11.6f, 18.4f)
    }
}

/** barra de voz:jugar,Un triángulo sólido mirando hacia la derecha. */
private fun playIcon(): ImageVector = lineIcon("mp-play") {
    fillPath {
        moveTo(9.4f, 6.2f)
        lineTo(17.8f, 12f)
        lineTo(9.4f, 17.8f)
        close()
    }
}

/** barra de voz:pausa,Dos barras verticales. */
private fun pauseIcon(): ImageVector = lineIcon("mp-pause") {
    fillPath { roundedRect(8.2f, 6.4f, 11.1f, 17.6f, 1.1f) }
    fillPath { roundedRect(12.9f, 6.4f, 15.8f, 17.6f, 1.1f) }
}

/** Entrada de voz:un micrófono vertical,Hay un arco y un poste corto debajo. */
private fun micIcon(): ImageVector = lineIcon("mp-mic") {
    strokePath(width = 1.7f) { roundedRect(9.4f, 3.2f, 14.6f, 12.8f, 2.6f) }
    strokePath(width = 1.7f) {
        moveTo(6.2f, 11.2f)
        curveTo(6.2f, 15.6f, 8.8f, 18.2f, 12f, 18.2f)
        curveTo(15.2f, 18.2f, 17.8f, 15.6f, 17.8f, 11.2f)
    }
    strokePath(width = 1.7f) {
        moveTo(12f, 18.2f)
        lineTo(12f, 20.8f)
    }
}

/** Me gusta:una forma de corazón,Los dos arcos superiores convergen en una punta afilada. */
private fun heartIcon(): ImageVector = lineIcon("mp-heart") {
    strokePath(width = 1.7f) {
        moveTo(12f, 20f)
        curveTo(9.4f, 17.8f, 4.4f, 14.4f, 4.4f, 10f)
        curveTo(4.4f, 7.3f, 6.4f, 5.2f, 8.9f, 5.2f)
        curveTo(10.4f, 5.2f, 11.5f, 6f, 12f, 6.9f)
        curveTo(12.5f, 6f, 13.6f, 5.2f, 15.1f, 5.2f)
        curveTo(17.6f, 5.2f, 19.6f, 7.3f, 19.6f, 10f)
        curveTo(19.6f, 14.4f, 14.6f, 17.8f, 12f, 20f)
        close()
    }
}

/** Entrada de grupo:Una ventaja. Dos rectas de igual longitud se cruzan,El mismo conjunto de trazos que los otros íconos de líneas finas en la parte superior de la página. */
private fun plusIcon(): ImageVector = lineIcon("mp-plus") {
    strokePath(width = 1.8f) {
        moveTo(12f, 5.2f)
        lineTo(12f, 18.8f)
    }
    strokePath(width = 1.8f) {
        moveTo(5.2f, 12f)
        lineTo(18.8f, 12f)
    }
}

/** seleccionado:Una marca de verificación. */
private fun checkIcon(): ImageVector = lineIcon("mp-check") {
    strokePath(width = 2f) {
        moveTo(5.4f, 12.6f)
        lineTo(10f, 17.2f)
        lineTo(18.6f, 7f)
    }
}

/** escribe un mensaje:un bolígrafo inclinado,Apunte la punta del lápiz hacia abajo. */
private fun penIcon(): ImageVector = lineIcon("mp-pen") {
    strokePath(width = 1.7f) {
        moveTo(4.4f, 19.6f)
        lineTo(5.8f, 15.2f)
        lineTo(16.2f, 4.8f)
        curveTo(17.1f, 3.9f, 18.5f, 3.9f, 19.4f, 4.8f)
        curveTo(20.3f, 5.7f, 20.3f, 7.1f, 19.4f, 8f)
        lineTo(9f, 18.4f)
        close()
    }
    strokePath(width = 1.4f) {
        moveTo(14.8f, 6.2f)
        lineTo(17.8f, 9.2f)
    }
}

object MiniPhoneIcons {
    val Signal: ImageVector = signalIcon()
    val Wifi: ImageVector = wifiIcon()
    val Battery: ImageVector = batteryIcon()
    val Message: ImageVector = messageIcon()
    val Contacts: ImageVector = contactsIcon()
    val Discover: ImageVector = discoverIcon()
    val Me: ImageVector = meIcon()
    val Moments: ImageVector = momentsIcon()
    val Chevron: ImageVector = chevronIcon()
    val Home: ImageVector = homeIcon()
    val Back: ImageVector = backIcon()
    val Send: ImageVector = sendIcon()
    val Image: ImageVector = imageIcon()
    val BrokenImage: ImageVector = brokenImageIcon()
    val Play: ImageVector = playIcon()
    val Pause: ImageVector = pauseIcon()
    val Heart: ImageVector = heartIcon()
    val Pen: ImageVector = penIcon()
    val Mic: ImageVector = micIcon()
    val Plus: ImageVector = plusIcon()
    val Check: ImageVector = checkIcon()
}
