package io.zer0.muse.ui.common

/**
 * Seguridad de valor proporcional — suministro [androidx.compose.foundation.layout.fillMaxWidth] /
 * fillMaxHeight / fillMaxSize y `animateFloatAsState` etc. Uso de API que requiere un valor de escala de 0..1.
 *
 * Antecedentes (defensa contra accidentes de máquinas reales):
 * Kotlin de `Float.coerceIn` No válido para NaN —— NaN Cualquier comparación involucrada es siempre false,
 * entonces `x.coerceIn(0f, 1f)` Devuelve NaN sin cambios si x es NaN. Pasar NaN a
 * `Modifier.fillMaxWidth(fraction)` puede tirar:
 *   IllegalArgumentException: Expecting fraction between 0 and 1. Got NaN
 * y [androidx.compose.animation.core.animateFloatAsState] Al obtener el valor objetivo de NaN
 * El valor de la animación también será NaN,El mismo bloqueo ocurre después de la propagación mediante fillMaxHeight.
 *
 * Esta función normaliza NaN / Infinity a [fallback](por defecto 0),Los valores restantes convergen a 0...1.
 */
fun Float.safeFraction(fallback: Float = 0f): Float = if (isFinite()) coerceIn(0f, 1f) else fallback.coerceIn(0f, 1f)

/**
 * Escale la seguridad del valor con un límite inferior personalizado.
 *
 * Utilizado para barras onduladas y similares."Mantenga una altura mínima visible incluso cuando esté silenciado"escena:
 * Primero haz la defensa de NaN,luego converger a [min]..1.
 */
fun Float.safeFraction(min: Float, fallback: Float): Float = if (isFinite()) coerceIn(min, 1f) else fallback.coerceIn(min, 1f)
