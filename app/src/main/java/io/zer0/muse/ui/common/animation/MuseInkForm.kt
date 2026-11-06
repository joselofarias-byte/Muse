package io.zer0.muse.ui.common.animation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * La geometría de la forma de la tinta: las tres formas comparten una familia de funciones de radio de coordenadas polares.
 *
 * Esta es la restricción clave para todo el conjunto de deformaciones. Las tres formas (hojas/estrellas/anillos) reciben radios en el mismo ángulo,
 * La deformación es el radio interpolado. Debido a que la topología es siempre consistente, no habrá problemas como desgarros, desalineación o conexión inversa de los puntos de control.
 * Tampoco es necesario realizar un fundido cruzado entre formas.
 *
 * Después de cambiar a un trazo, la misma función también se reutiliza para realizar el trazo inicial del "dibujo de un trazo" y la luz viaja a lo largo del contorno.
 */

/** Forma de la hoja: punta en t=0, el extremo redondo está en t=π. */
internal const val FORM_LEAF = 0f

/** Forma de estrella: cuatro brazos. */
internal const val FORM_SPARK = 1f

/** Forma de anillo: asentada. */
internal const val FORM_RING = 2f

/** Número total de formas para anillos.→Se considera que las hojas están adyacentes al girar. */
internal const val FORM_COUNT = 3

/** Número de segmentos de muestreo de contorno.18dp Bajo el tamaño, los 96 segmentos ya no son visibles a simple vista. */
private const val CONTOUR_SEGMENTS = 96

private const val TWO_PI = (Math.PI * 2).toFloat()

/** Radio de coordenadas polares: cada una de las tres formas da un radio en el mismo ángulo. */
internal fun formRadius(form: Int, angle: Float): Float = when (form) {
    // Hoja:sin(angle/2) forma de media hoja, punta en angle=0, redondo hacia adentro angle=π. Un índice inferior a 1 hace que las hojas se vuelvan regordetas.
    0 -> 0.02f + 0.98f * (abs(sin(angle / 2f))).pow(0.8f)
    // Estrella: Cuatro brazos.|cos 2θ| en [0,1] Hay cuatro picos y se presiona la parte inferior para evitar que la cintura colapse hacia el centro.
    1 -> 0.34f + 0.66f * (abs(cos(2f * angle))).pow(0.7f)
    // Lazo: Cerrado y ligeramente ondulado, evitando la rigidez de un círculo geométrico.
    else -> 0.66f + 0.09f * cos(3f * angle)
}

/** Peso de transición suave entre las dos formas (use smoothstep para evitar cambios bruscos de velocidad al principio y al final). */
internal fun smooth01(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

/**
 * Valor del patrón en un momento determinado del ciclo:0 hojas → 1 estrella → 2 anillo → Volver a 0.
 * Deje pausas entre cada párrafo para permitir que se vea cada formulario en lugar de deslizarse por completo.
 */
internal fun formAt(progress: Float): Float = when {
    progress < 0.34f -> FORM_LEAF
    progress < 0.46f -> FORM_LEAF + smooth01((progress - 0.34f) / 0.12f) * (FORM_SPARK - FORM_LEAF)
    progress < 0.60f -> FORM_SPARK
    progress < 0.72f -> FORM_SPARK + smooth01((progress - 0.60f) / 0.12f) * (FORM_RING - FORM_SPARK)
    progress < 0.86f -> FORM_RING
    else -> FORM_RING + smooth01((progress - 0.86f) / 0.14f) * (FORM_COUNT - FORM_RING)
}

/** La proporción de una determinada forma bajo el valor de la forma actual, utilizada para desvanecerse dentro y fuera de los elementos adjuntos (tallos de hojas, luz errante). */
internal fun formWeight(form: Float, target: Float): Float {
    val direct = abs(form - target)
    // anillo(2) con hojas(0) Adyacente al rebobinar, la distancia es 3-|pobre| Plan.
    val wrapped = FORM_COUNT - direct
    return (1f - minOf(direct, wrapped)).coerceIn(0f, 1f)
}

/** El primer 16% es dibujar el contorno mismo y luego mantenerlo intacto. */
internal fun revealAt(progress: Float): Float = (progress / 0.16f).coerceIn(0f, 1f)

/**
 * Genere rutas de contorno basadas en valores de forma.
 *
 * @param form Valor del formulario, el decimal entre los segmentos completos representa el progreso de la interpolación entre los dos formularios.
 * @param center Centro de lienzo
 * @param radius Radio disponible dentro del lienzo
 */
internal fun contourPath(form: Float, center: Offset, radius: Float): Path {
    val base = form.toInt().coerceIn(0, FORM_COUNT - 1)
    val next = (base + 1) % FORM_COUNT
    val mix = smooth01(form - base)
    val path = Path()
    for (i in 0..CONTOUR_SEGMENTS) {
        val angle = i.toFloat() / CONTOUR_SEGMENTS * TWO_PI
        val r = formRadius(base, angle) * (1f - mix) + formRadius(next, angle) * mix
        val x = center.x + r * radius * cos(angle)
        val y = center.y + r * radius * sin(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}
