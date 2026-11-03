package io.zer0.muse.ui.common.animation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseAnimation
import kotlin.math.cos
import kotlin.math.sin

/** El ángulo de inclinación de toda la forma de tinta con respecto al lienzo es coherente con la postura de la figura principal. */
private const val TILT_DEGREES = -38f

/** La proporción de la longitud de la luz que viaja a lo largo del contorno. */
private const val TRAVEL_FRACTION = 0.28f

/** Piensa en las longitudes de los lados de las formas de tinta en el indicador. */
val MuseThinkingGlyphSize = 18.dp

/** El desplazamiento de los dos extremos del pecíolo con respecto al centro del lienzo, en múltiplos del radio;drawStem Comparte el mismo conjunto de números que el cálculo de alineación. */
private const val STEM_X0 = 0.06f
private const val STEM_Y0 = 0.02f
private const val STEM_X1 = 0.30f
private const val STEM_Y1 = 0.10f

/** Número de segmentos de contorno para alinear las muestras. La misma magnitud que el contorno, no píxel por píxel. */
private const val ALIGN_SAMPLES = 96

private const val TWO_PI = (Math.PI * 2).toFloat()

/**
 * El indicador en forma de tinta de la etapa de espera: una hoja, una estrella y un anillo, los tres se transforman entre sí.
 *
 * ¿Por qué utilizar "forma" en lugar de "girar en círculos": girar en círculos transmite "algo está ocupado", mientras que los cambios en la forma transmiten
 * "Algo está creciendo." El primero es el lenguaje de la máquina y el segundo es el sentimiento que queremos dar a la gente.
 *
 * Precio: Trazo de lienzo puro, sin WebView, sin mapa de bits superpuesto. La geometría se define en MuseInkForm.kt.
 */

/** Cuerpo en forma de tinta. */
@Composable
internal fun MuseInkGlyph(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    animate: Boolean = true,
    cycleMs: Int = MuseAnimation.GLYPH_CYCLE_MS,
) {
    if (!animate) {
        Canvas(modifier) { drawGlyphFrame(progress = 0f, color = color, travel = 0f) }
        return
    }
    val transition = rememberInfiniteTransition(label = "muse_ink_glyph")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = cycleMs, easing = LinearEasing),
        ),
        label = "glyph_progress",
    )
    val travel by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = MuseAnimation.GLYPH_TRAVEL_MS, easing = LinearEasing),
        ),
        label = "glyph_travel",
    )
    Canvas(modifier) { drawGlyphFrame(progress = progress, color = color, travel = travel) }
}

private fun DrawScope.drawGlyphFrame(progress: Float, color: Color, travel: Float) {
    val radius = size.minDimension / 2f * 0.92f
    val center = Offset(size.width / 2f, size.height / 2f)
    val form = formAt(progress)
    // El contorno de la forma de la hoja es naturalmente asimétrico en coordenadas polares: la punta es angle=0, extremo redondo en angle=π,
    // Luego gire alrededor del centro del lienzo. -38°, el centro de su cuadro delimitador visual sale del centro geométrico (18dp Aproximadamente 1,7 dp de abajo a izquierda,
    // Aproximadamente 1,3 dp menos). Esto se puede ver de un vistazo en el pequeño icono.“torcido”.
    // Entonces, primero calcule el centro del cuadro delimitador de la forma actual y luego traslade todo el grupo de dibujos hacia atrás para que la tinta caiga en el centro del lienzo.
    val bounds = inkBounds(form, radius)
    val hairline = Stroke(
        width = 1.6.dp.toPx(),
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )
    translate(left = -bounds.center.x, top = -bounds.center.y) {
        rotate(degrees = TILT_DEGREES, pivot = center) {
            // No hagas una revelación de "un solo trazo" aquí: la revelación requiere que el contorno no sea visible cuando el progreso se restablece a cero.
            // Y el final del bucle es bucle.→La deformación de las hojas, las dos se pelearán por las costuras.——
            // Lo que el usuario ve es que la hoja desaparece repentinamente y se vuelve a dibujar. Ahora el contorno es siempre visible,
            // La "sensación de crecimiento" depende enteramente de la propia interpolación morfológica y puede conectarse de principio a fin.
            val path = contourPath(form, center, radius)
            drawPath(path, color, style = hairline)
            drawSparkTravel(path, color, travel, form)
            drawStem(center, radius, color, formWeight(form, FORM_LEAF))
        }
    }
}

/**
 * El cuadro delimitador de la forma actual (incluido el ángulo de inclinación) en el lienzo, siendo el origen el centro del lienzo.
 *
 * Los pecíolos también están incluidos: sólo están centrados en el contorno y los tallos todavía sobresalen, por lo que todavía parecen torcidos.
 */
private fun inkBounds(form: Float, radius: Float): Rect {
    val base = form.toInt().coerceIn(0, FORM_COUNT - 1)
    val next = (base + 1) % FORM_COUNT
    val mix = smooth01(form - base)
    val rad = Math.toRadians(TILT_DEGREES.toDouble())
    val cosT = cos(rad).toFloat()
    val sinT = sin(rad).toFloat()

    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE

    fun include(x: Float, y: Float) {
        val rx = x * cosT - y * sinT
        val ry = x * sinT + y * cosT
        if (rx < minX) minX = rx
        if (rx > maxX) maxX = rx
        if (ry < minY) minY = ry
        if (ry > maxY) maxY = ry
    }

    for (i in 0 until ALIGN_SAMPLES) {
        val angle = i.toFloat() / ALIGN_SAMPLES * TWO_PI
        val r = (formRadius(base, angle) * (1f - mix) + formRadius(next, angle) * mix) * radius
        include(r * cos(angle), r * sin(angle))
    }
    if (formWeight(form, FORM_LEAF) > 0.02f) {
        include(STEM_X0 * radius, STEM_Y0 * radius)
        include(STEM_X1 * radius, STEM_Y1 * radius)
    }
    return Rect(minX, minY, maxX, maxY)
}

/**
 * Un tramo de luz errante en forma de estrella: Tome un tramo del 28% de la circunferencia del contorno, avance por el camino y la cabeza de la línea alcanzará el final de la línea.
 * Dibuja solo cuando la forma de estrella represente una proporción lo suficientemente alta como para evitar el follaje./Hay una línea brillante inesperada en el anillo.
 */
private fun DrawScope.drawSparkTravel(path: Path, color: Color, travel: Float, form: Float) {
    val weight = formWeight(form, FORM_SPARK)
    if (weight < 0.02f) return
    val measure = PathMeasure()
    measure.setPath(path, false)
    val length = measure.length
    if (length <= 0f) return
    val segmentLength = length * TRAVEL_FRACTION
    val start = travel * length
    val window = Path()
    val end = start + segmentLength
    if (end <= length) {
        measure.getSegment(start, end, window, true)
    } else {
        measure.getSegment(start, length, window, true)
        val head = Path()
        measure.getSegment(0f, end - length, head, true)
        window.addPath(head)
    }
    drawPath(
        path = window,
        color = color.copy(alpha = weight),
        style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round),
    )
}

/** Pecíolo: sólo visible cuando la hoja está en forma y se desvanece cuando se deforma. */
private fun DrawScope.drawStem(center: Offset, radius: Float, color: Color, weight: Float) {
    if (weight < 0.02f) return
    drawLine(
        color = color.copy(alpha = weight * 0.85f),
        start = Offset(center.x + radius * STEM_X0, center.y + radius * STEM_Y0),
        end = Offset(center.x + radius * STEM_X1, center.y + radius * STEM_Y1),
        strokeWidth = 1.4.dp.toPx(),
        cap = StrokeCap.Round,
    )
}
