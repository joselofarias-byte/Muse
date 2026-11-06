package io.zer0.muse.ui.common.state

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseMotion

/**
 * Animación de carga de flujo óptico de venas de hojas.
 *
 * El ícono es una hoja con una estrella en la punta. En lugar de encontrar otro círculo universal, dividimos el ícono en acciones:
 * La luz fluye a lo largo de las venas de la hoja desde el pecíolo hasta la punta de la hoja, converge en la estrella, se detiene por un momento y luego regresa por el camino original.
 * El final de una ronda y el comienzo de la siguiente están en el mismo cuadro, por lo que parece ser un respiro continuo en lugar de una repetición.
 *
 * Sólo se utiliza en situaciones en las que "el usuario realmente está esperando" (carga de página completa, generación de texto).
 * Sigue usando esos pequeños círculos de más de diez dp en el botón. [MuseSpinner] —— Con este tamaño lo único que queda de la hoja es una masa de puntos verdes;
 * No tan bueno como un arco limpio.
 *
 * @param size Longitud lateral. Por debajo de 24 dp, los detalles de las venas de las hojas se verán borrosos, por lo que se recomienda que el usuario mantenga un tamaño mayor.
 * @param color Color principal. Utilice su versión de menor opacidad para el cuerpo de la hoja y su propia versión para el flujo de luz y la estrella.
 */
@Composable
fun MuseLeafLoader(modifier: Modifier = Modifier, size: Dp = 40.dp, color: Color = LocalContentColor.current) {
    val reducedMotion = MuseMotion.isReducedMotion()
    val progress = if (reducedMotion) {
        0.55f
    } else {
        val transition = rememberInfiniteTransition(label = "muse_leaf")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = CYCLE_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "muse_leaf_progress",
        )
        animated
    }
    val shape = remember { LeafShape() }

    Canvas(modifier = modifier.size(size)) {
        // El sistema de coordenadas de la ruta es fijo. 100×100, el lienzo es el píxel correspondiente al tamaño.
        // No convertir equivale a "tomar una imagen de 100 de ancho e incrustarla en un marco de 39 píxeles".—— Sólo queda expuesta la pequeña pieza de la esquina superior izquierda.
        // Y ese pequeño trozo cae exactamente en el área inferior derecha del lienzo, y parece que la hoja está torcida hacia la esquina inferior derecha.
        // Nota: El parámetro exterior también se llama tamaño (Dp), ingrese DrawScope y obtenga explícitamente this.size para determinar el tamaño del lienzo.
        val canvas = this.size
        val unit = canvas.minDimension / LEAF_ARTBOARD
        // Mueva el centro del cuadro delimitador de contenido al centro del lienzo: tanto el escalado como la rotación giran alrededor del mismo punto.
        // De esta manera, no importa cómo se ajuste el ángulo, los gráficos no saldrán del marco.
        // La traducción aquí ocurre después del escalado (traducción de la capa externa), por lo que el desplazamiento se realiza en el sistema de coordenadas del lienzo.
        // Diferencia absoluta, no se puede multiplicar. unit——Multiplicar es reducir nuevamente el "desplazamiento que se ha escalado una vez".
        // El resultado sólo está centrado cuando la unidad es exactamente igual a 1, y el resto de las dimensiones se empujan hacia un lado y se desbordan.
        val center = Offset(CONTENT_CENTER_X, CONTENT_CENTER_Y)
        translate(
            left = canvas.width / 2f - CONTENT_CENTER_X,
            top = canvas.height / 2f - CONTENT_CENTER_Y,
        ) {
            scale(unit, unit, pivot = center) {
                rotate(degrees = LEAF_TILT_DEGREES, pivot = center) {
                    drawLeafBody(shape, color, progress)
                    drawVeinFlow(shape, color, progress)
                    drawStar(shape, color, progress)
                }
            }
        }
    }
}

/** La duración de un ciclo. Deje suficiente tiempo para \"respirar\". Esperar por menos de este número parecerá urgente. */
private const val CYCLE_MS = 6_000

/** La longitud del lado del lienzo virtual utilizada por la ruta. Todos los puntos de control se basan en este sistema de coordenadas. */
private const val LEAF_ARTBOARD = 100f

/**
 * Dato de alineación: el centro del cuadro delimitador de la propia hoja.
 *
 * No tome el cuadro delimitador fusionado de "cuerpo de la hoja + estrella de la punta de la hoja": la estrella solo aparece en un corto período de tiempo cuando las venas de la hoja convergen.
 * El resto del tiempo no está ahí, mientras la posición del lienzo es constante.——Contando la estrella, la ausencia de la estrella hace que toda la hoja parezca más baja.
 * centro de la hoja y=48.8, incluido el centro estrella y=44.3, una diferencia de 4,5,32dp La caída es de 1,4 dp.
 */
private const val CONTENT_CENTER_X = 50.06f
private const val CONTENT_CENTER_Y = 48.8f

/**
 * La inclinación de las hojas con respecto a la vertical.
 *
 * El cuerpo de la figura tiene aproximadamente 44 grados (desde el pecíolo hasta la punta con respecto a la línea horizontal). El camino de abajo está dibujado a 56 grados,
 * Así que gíralo 12 grados aquí para alinearlo. Para cambiar el ángulo, solo necesita mover este número y no es necesario tocar el camino en sí.
 */
private const val LEAF_TILT_DEGREES = -12f

/** El cuerpo de la hoja aparece y desaparece: la mayor parte del tiempo durante toda la ronda, con un desvanecimiento al principio y al final. */
private const val BODY_FADE_IN_END = 0.14f
private const val BODY_FADE_OUT_START = 0.90f

/** Flujo óptico de las venas de la hoja: desde el pecíolo hasta la punta de la hoja y de regreso al pecíolo. */
private const val FLOW_START = 0.06f
private const val FLOW_REACH_TIP = 0.30f
private const val FLOW_RETURN_START = 0.66f
private const val FLOW_RETURN_END = 0.84f

/** Estrella: La punta de la hoja crece cuando brilla y se encoge cuando sale. */
private const val STAR_GROW_START = 0.32f
private const val STAR_GROW_END = 0.46f
private const val STAR_FADE_START = 0.86f

/**
 * Geometría de la hoja.
 *
 * Las coordenadas se basan en 100×100 El lienzo es isomorfo al ícono: el pecíolo está en la parte inferior izquierda y la punta de la hoja en la parte superior derecha.
 * Tres venas de las hojas se extienden desde el pecíolo y convergen hacia la punta de la hoja, con estrellas cayendo afuera y por encima de la punta de la hoja.
 */
private class LeafShape {
    /** Contorno de la hoja. */
    val body: Path = Path().apply {
        moveTo(33.2f, 73.4f)
        cubicTo(30.6f, 55.4f, 38.4f, 33.6f, 66.6f, 24.2f)
        cubicTo(71.2f, 41.6f, 55.4f, 66.2f, 33.2f, 73.4f)
        close()
    }

    /** Las tres nervaduras de las hojas están dispuestas en orden desde la más larga (unida al borde exterior) hasta la más corta (unida al lado del pecíolo). */
    val veins: List<Path> = listOf(
        Path().apply {
            moveTo(34.4f, 70.6f)
            cubicTo(41.2f, 57.4f, 50.2f, 42.6f, 65.4f, 25.8f)
        },
        Path().apply {
            moveTo(33.6f, 71.6f)
            cubicTo(41.6f, 62.2f, 50.6f, 50.4f, 61.2f, 36.4f)
        },
        Path().apply {
            moveTo(33.4f, 72.2f)
            cubicTo(41.8f, 66.4f, 49.8f, 58.2f, 55.4f, 48.6f)
        },
    )

    /** La estrella de cuatro puntas en la punta de la hoja. */
    val star: Path = Path().apply {
        moveTo(66.6f, 15.2f)
        cubicTo(67.5f, 19.1f, 68.4f, 20f, 72.3f, 20.9f)
        cubicTo(68.4f, 21.8f, 67.5f, 22.7f, 66.6f, 26.6f)
        cubicTo(65.7f, 22.7f, 64.8f, 21.8f, 60.9f, 20.9f)
        cubicTo(64.8f, 20f, 65.7f, 19.1f, 66.6f, 15.2f)
        close()
    }

    /** La longitud de cada una de las tres venas de la hoja se utiliza para cortar la \"serpentina\" en proporción. */
    val veinLengths: List<Float> by lazy {
        veins.map { path ->
            PathMeasure().apply { setPath(path, false) }.length
        }
    }
}

/** Cuerpo de la hoja: Todo el redondo está presente, la cabeza y la cola son ligeras. */
private fun DrawScope.drawLeafBody(shape: LeafShape, color: Color, progress: Float) {
    val alpha = when {
        progress < BODY_FADE_IN_END -> progress / BODY_FADE_IN_END
        progress < BODY_FADE_OUT_START -> 1f
        else -> ((1f - progress) / (1f - BODY_FADE_OUT_START)).coerceIn(0f, 1f)
    } * BODY_BODY_MAX_ALPHA
    if (alpha <= 0.01f) return
    drawPath(path = shape.body, color = color.copy(alpha = alpha))
}

/** La luz fluye a lo largo de las venas de las hojas: desde el pecíolo hasta la punta de la hoja en el viaje de ida, y de regreso por el camino original en el viaje de regreso. */
private fun DrawScope.drawVeinFlow(shape: LeafShape, color: Color, progress: Float) {
    val travel = veinTravel(progress) ?: return
    val color = color.copy(alpha = travel.alpha * VEIN_MAX_ALPHA)
    shape.veins.forEachIndexed { index, path ->
        // Las tres venas comienzan un poco escalonadas y parecen iluminarse en secuencia en lugar de parpadear al mismo tiempo.
        val local = (travel.fraction - index * VEIN_STAGGER).coerceIn(0f, 1f)
        if (local <= 0f) return@forEachIndexed
        val segment = subPath(path, local)
        drawPath(
            path = segment,
            color = color,
            style = Stroke(width = veinWidth, cap = StrokeCap.Round),
        )
    }
}

/** La estrella en la punta de la hoja: se traza cuando las nervaduras de la hoja convergen y se retrae cuando sale. */
private fun DrawScope.drawStar(shape: LeafShape, color: Color, progress: Float) {
    val grow = starGrow(progress)
    if (grow <= 0f) return
    val alpha = starAlpha(progress)
    if (alpha <= 0.01f) return
    val radius = LEAF_ARTBOARD * STAR_PIVOT_SCALE
    val pivot = Offset(shape.star.getBounds().center.x, shape.star.getBounds().center.y)
    val scale = 0.55f + 0.45f * grow
    rotate(degrees = 0f, pivot = pivot) {
        scale(scale, scale, pivot) {
            drawPath(
                path = shape.star,
                color = color.copy(alpha = alpha * STAR_MAX_ALPHA),
                style = Stroke(width = starWidth, cap = StrokeCap.Round),
            )
        }
    }
    // Un pequeño halo en el centro de la estrella evita que toda la estrella sea solo una línea delgada y parezca débil sobre un fondo oscuro.
    if (grow > 0.6f) {
        drawCircle(
            color = color.copy(alpha = alpha * STAR_HALO_ALPHA * (grow - 0.6f) / 0.4f),
            radius = radius,
            center = pivot,
        )
    }
}

/** \"¿A dónde fueron las venas de las hojas?\" Devolver nulo significa que no debería haber luz en este marco. */
private data class VeinTravel(val fraction: Float, val alpha: Float)

private fun veinTravel(progress: Float): VeinTravel? = when {
    progress < FLOW_START -> null
    progress < FLOW_REACH_TIP ->
        VeinTravel(
            fraction = ((progress - FLOW_START) / (FLOW_REACH_TIP - FLOW_START)).coerceIn(0f, 1f),
            alpha = 1f,
        )
    progress < FLOW_RETURN_START -> VeinTravel(fraction = 1f, alpha = 1f)
    progress < FLOW_RETURN_END ->
        VeinTravel(
            fraction = 1f - ((progress - FLOW_RETURN_START) / (FLOW_RETURN_END - FLOW_RETURN_START))
                .coerceIn(0f, 1f),
            alpha = 1f,
        )
    else -> null
}

/** Progreso del rastreo de estrellas 0...1. */
private fun starGrow(progress: Float): Float = when {
    progress < STAR_GROW_START -> 0f
    progress < STAR_GROW_END ->
        ((progress - STAR_GROW_START) / (STAR_GROW_END - STAR_GROW_START)).coerceIn(0f, 1f)
    else -> 1f
}

/** Transparencia de la estrella: parpadea una vez cuando crece y se apaga cuando sale. */
private fun starAlpha(progress: Float): Float = when {
    progress < STAR_GROW_START -> 0f
    progress < STAR_GROW_END -> (progress - STAR_GROW_START) / (STAR_GROW_END - STAR_GROW_START)
    progress < STAR_FADE_START -> {
        // En el medio hay dos destellos de luz, que son pequeños y no llaman la atención.
        val breathe = 0.78f + 0.22f * kotlin.math.abs(
            kotlin.math.cos((progress - STAR_GROW_END) * STAR_FLICKER_TURNS * Math.PI.toFloat()),
        )
        breathe
    }
    else -> ((1f - progress) / (1f - STAR_FADE_START)).coerceIn(0f, 1f)
}

/** antes de tomar el camino [fraction] Una sección de longitud utilizada para dibujar \"la luz que fluye a través de ella\". */
private fun subPath(path: Path, fraction: Float): Path {
    val measure = PathMeasure().apply { setPath(path, false) }
    val destination = Path()
    measure.getSegment(0f, measure.length * fraction.coerceIn(0f, 1f), destination, true)
    return destination
}

// ── parámetros visuales ────────────────────────────────────────────
private const val BODY_BODY_MAX_ALPHA = 0.30f
private const val VEIN_MAX_ALPHA = 0.95f
private const val VEIN_STAGGER = 0.10f
private const val STAR_MAX_ALPHA = 0.95f
private const val STAR_HALO_ALPHA = 0.22f
private const val STAR_PIVOT_SCALE = 0.045f
private const val STAR_FLICKER_TURNS = 5f

/** El ancho de la línea se calcula en función del lienzo virtual y no sigue los píxeles reales. —— De lo contrario, se duplicará el grosor de línea del mismo código en diferentes tamaños. */
private val DrawScope.veinWidth: Float get() = LEAF_ARTBOARD * 0.028f
private val DrawScope.starWidth: Float get() = LEAF_ARTBOARD * 0.028f
