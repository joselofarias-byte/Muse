package io.zer0.muse.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import androidx.compose.animation.core.tween as composeTween

/**
 * Muse Estrategia global de animación.
 *
 * Todas las animaciones limitadas deberían pasar. [tween] o [tweenSpec] Crear: el primero se utiliza en el contexto de composición Redactar,
 * Este último se utiliza para devoluciones de llamada que no son componibles, como transiciones de navegación. Ambos comparten el mismo conjunto de reglas de escala de duración, atenuación y animación del sistema.
 * Cuando el sistema apaga la animación, la persona que llama cambia la animación en bucle a un estado estático para evitar continuar generando devoluciones de llamadas de fotogramas.
 */
object MuseMotion {
    private val LocalAnimatorScale = compositionLocalOf { 1f }
    private val staticAnimatorScale = AtomicReference(1f)

    /**
     * Proporcione un estado de zoom animado en la raíz del tema y registre solo un observador de configuración del sistema.
     * Las transiciones de navegación que no son componibles leerán el mismo valor de escala estático para evitar“La transición de la página sigue en movimiento y la animación del contenido se ha detenido.”de inconsistencia.
     */
    @Composable
    fun Provide(content: @Composable () -> Unit) {
        val context = LocalContext.current.applicationContext
        val initialScale = remember(context) { readAnimatorScale(context) }
        var animatorScale by remember(context) { mutableFloatStateOf(initialScale) }
        staticAnimatorScale.set(animatorScale)

        DisposableEffect(context) {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    val value = readAnimatorScale(context)
                    animatorScale = value
                    staticAnimatorScale.set(value)
                }
            }
            val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
            context.contentResolver.registerContentObserver(uri, false, observer)
            onDispose { context.contentResolver.unregisterContentObserver(observer) }
        }

        CompositionLocalProvider(LocalAnimatorScale provides animatorScale) {
            content()
        }
    }

    /** Si está en estado de movimiento reducido (la escala de animación del sistema es 0). */
    @Composable
    fun isReducedMotion(): Boolean = animatorScale() <= 0f

    /** Devuelve la duración real según el escalado dinámico del sistema. */
    @Composable
    fun duration(durationMillis: Int): Int = scaleDuration(durationMillis, animatorScale())

    /**
     * Crea una interpolación que sigue la escala de animación del Compose actual.
     * 0ms Representa finalización inmediata; las animaciones en bucle no deben depender únicamente de 0 ms, sino que la persona que llama debe cambiarlas a contenido estático.
     */
    @Composable
    fun <T> tween(durationMillis: Int, easing: Easing = MuseAnimation.Standard, delayMillis: Int = 0): DurationBasedAnimationSpec<T> =
        tweenForScale(durationMillis, easing, delayMillis, animatorScale())

    /**
     * Cree una interpolación para usar con devoluciones de llamadas que no son Composable, como el asistente de transición de Navigation Compose.
     * El valor de escala está dado por [Provide] Sincronización de un solo observador; manejado de forma segura a 1x cuando no está en el tema.
     */
    fun <T> tweenSpec(durationMillis: Int, easing: Easing = MuseAnimation.Standard, delayMillis: Int = 0): DurationBasedAnimationSpec<T> =
        tweenForScale(
            durationMillis = durationMillis,
            easing = easing,
            delayMillis = delayMillis,
            scale = staticAnimatorScale.get(),
        )

    /** Una transición de entrada unificada pura y gradual. */
    fun fadeEnter(durationMillis: Int = MuseAnimation.NORMAL_MS, delayMillis: Int = 0): EnterTransition = fadeIn(
        tweenSpec(
            durationMillis = durationMillis,
            easing = MuseAnimation.EaseOutCubic,
            delayMillis = delayMillis,
        ),
    )

    /** Una transición de salida unificada que se desvanece puramente. */
    fun fadeExit(durationMillis: Int = MuseAnimation.NORMAL_MS, delayMillis: Int = 0): ExitTransition = fadeOut(
        tweenSpec(
            durationMillis = durationMillis,
            easing = MuseAnimation.EaseInCubic,
            delayMillis = delayMillis,
        ),
    )

    /** Transición de entrada unificada solo para contenido ampliado. */
    fun expandEnter(durationMillis: Int = MuseAnimation.NORMAL_MS): EnterTransition = expandVertically(
        animationSpec = tweenSpec(durationMillis, easing = MuseAnimation.EaseOutCubic),
    )

    /** Transición de salida unificada que solo colapsa el contenido. */
    fun expandExit(durationMillis: Int = MuseAnimation.NORMAL_MS): ExitTransition = shrinkVertically(
        animationSpec = tweenSpec(durationMillis, easing = MuseAnimation.EaseInCubic),
    )

    /** Expandir/Transición de entrada unificada para contenido colapsado. */
    fun expandFadeEnter(durationMillis: Int = MuseAnimation.NORMAL_MS, fadeDurationMillis: Int = durationMillis): EnterTransition =
        expandEnter(durationMillis) + fadeEnter(fadeDurationMillis)

    /** Expandir/Transición de salida unificada para contenido colapsado. */
    fun expandFadeExit(durationMillis: Int = MuseAnimation.NORMAL_MS, fadeDurationMillis: Int = durationMillis): ExitTransition =
        expandExit(durationMillis) + fadeExit(fadeDurationMillis)

    /** Sólo la unidad expandida horizontalmente entra en la transición. */
    fun horizontalExpandEnter(
        durationMillis: Int = MuseAnimation.NORMAL_MS,
        expandFrom: Alignment.Horizontal = Alignment.Start,
    ): EnterTransition = expandHorizontally(
        animationSpec = tweenSpec(durationMillis, easing = MuseAnimation.EaseOutCubic),
        expandFrom = expandFrom,
    )

    /** Transición de salida uniforme solo para retracción horizontal. */
    fun horizontalExpandExit(
        durationMillis: Int = MuseAnimation.NORMAL_MS,
        shrinkTowards: Alignment.Horizontal = Alignment.Start,
    ): ExitTransition = shrinkHorizontally(
        animationSpec = tweenSpec(durationMillis, easing = MuseAnimation.EaseInCubic),
        shrinkTowards = shrinkTowards,
    )

    /** Se expande y desvanece horizontalmente, adecuado para tarjetas de contenido que emergen del borde. */
    fun horizontalExpandFadeEnter(
        durationMillis: Int = MuseAnimation.NORMAL_MS,
        fadeDurationMillis: Int = durationMillis,
        expandFrom: Alignment.Horizontal = Alignment.End,
    ): EnterTransition = horizontalExpandEnter(durationMillis, expandFrom) + fadeEnter(fadeDurationMillis)

    /** Contraer y desvanecerse horizontalmente, para tarjetas de contenido que desaparecen del borde. */
    fun horizontalExpandFadeExit(
        durationMillis: Int = MuseAnimation.NORMAL_MS,
        fadeDurationMillis: Int = durationMillis,
        shrinkTowards: Alignment.Horizontal = Alignment.End,
    ): ExitTransition = horizontalExpandExit(durationMillis, shrinkTowards) + fadeExit(fadeDurationMillis)

    /**
     * Deslícese a lo largo y desaparezca.
     *
     * v2.6.7: El desplazamiento predeterminado es de `it / 2`(media pantalla) recibido `it / 20`(alrededor del 5%).
     * La sensación de lectura a media pantalla con facilidadOutCubic es "Toda la pieza fue levantada";5% Eso es todo "emerger en el acto".
     * Si realmente se necesita un gran desplazamiento (vista previa en pantalla completa, etc.), pase explícitamente inicialOffsetY.
     */
    fun verticalSlideFadeEnter(
        durationMillis: Int = MuseAnimation.CONTENT_ENTER_MS,
        fadeDurationMillis: Int = durationMillis,
        initialOffsetY: (Int) -> Int = { (it * MuseAnimation.SLIDE_FRACTION_CONTENT).roundToInt() },
    ): EnterTransition = slideInVertically(
        animationSpec = tweenSpec(durationMillis, easing = MuseAnimation.EaseOutCubic),
        initialOffsetY = initialOffsetY,
    ) + fadeEnter(fadeDurationMillis)

    /**
     * Deslícese verticalmente y desaparezca.
     *
     * v2.6.7: La salida por defecto es más corta que la entrada (200ms vs 260ms), y el desplazamiento también recibe el 5%.
     */
    fun verticalSlideFadeExit(
        durationMillis: Int = MuseAnimation.CONTENT_EXIT_MS,
        fadeDurationMillis: Int = durationMillis,
        targetOffsetY: (Int) -> Int = { (it * MuseAnimation.SLIDE_FRACTION_CONTENT).roundToInt() },
    ): ExitTransition = slideOutVertically(
        animationSpec = tweenSpec(durationMillis, easing = MuseAnimation.EaseInCubic),
        targetOffsetY = targetOffsetY,
    ) + fadeExit(fadeDurationMillis)

    /**
     * Deslícese y desaparezca horizontalmente.
     *
     * v2.6.7: El desplazamiento predeterminado es desde toda la pantalla (`it`) recibió 1/12 (alrededor del 8%).
     * El deslizamiento horizontal de pantalla completa es la sintaxis de la pila de navegación de iOS; utilizado en la barra de contenido/Parecerá muy pesado en la capa flotante.
     */
    fun horizontalSlideFadeEnter(
        durationMillis: Int = MuseAnimation.CONTENT_ENTER_MS,
        fadeDurationMillis: Int = durationMillis,
        initialOffsetX: (Int) -> Int = { (it * MuseAnimation.SLIDE_FRACTION_PAGE).roundToInt() },
    ): EnterTransition = slideInHorizontally(
        animationSpec = tweenSpec(durationMillis, easing = MuseAnimation.EaseOutCubic),
        initialOffsetX = initialOffsetX,
    ) + fadeEnter(fadeDurationMillis)

    /** Deslícese horizontalmente y desaparezca (la salida es más corta que la entrada). */
    fun horizontalSlideFadeExit(
        durationMillis: Int = MuseAnimation.CONTENT_EXIT_MS,
        fadeDurationMillis: Int = durationMillis,
        targetOffsetX: (Int) -> Int = { (it * MuseAnimation.SLIDE_FRACTION_PAGE).roundToInt() },
    ): ExitTransition = slideOutHorizontally(
        animationSpec = tweenSpec(durationMillis, easing = MuseAnimation.EaseInCubic),
        targetOffsetX = targetOffsetX,
    ) + fadeExit(fadeDurationMillis)

    /**
     * v2.6.7: "crecer en el acto" Admisión —— Zoom + aparición gradual, sin desplazamiento.
     *
     * Evaluación comparativa de Kelivo `AnimatedScale`(70 ubicaciones en todos los ámbitos): los elementos no se mueven,
     * En cambio, crece del 96% al 100% de tamaño. esto es lo menos "bomba" método de entrada,
     * Adecuado para tarjetas, barras de recordatorios, elementos de menú y otras cosas que deberían aparecer en su lugar original.
     *
     * No hay necesidad [MuseAnimation.EaseOutBack]:Bounce aparecerá incómodo en los elementos de la lista que aparecen con frecuencia.
     */
    fun scaleFadeEnter(
        durationMillis: Int = MuseAnimation.CONTENT_ENTER_MS,
        fadeDurationMillis: Int = durationMillis,
        initialScale: Float = 0.96f,
    ): EnterTransition = scaleIn(
        animationSpec = tweenSpec(durationMillis, easing = MuseAnimation.GrowEase),
        initialScale = initialScale,
        transformOrigin = TransformOrigin(0.5f, 0.5f),
    ) + fadeEnter(fadeDurationMillis)

    /** v2.6.7: "Recuperarse en el acto" Salir —— Zoom + Fundido, avanzando hacia el 98% (menos profundo que la entrada). */
    fun scaleFadeExit(
        durationMillis: Int = MuseAnimation.CONTENT_EXIT_MS,
        fadeDurationMillis: Int = durationMillis,
        targetScale: Float = 0.98f,
    ): ExitTransition = scaleOut(
        animationSpec = tweenSpec(durationMillis, easing = MuseAnimation.EaseInCubic),
        targetScale = targetScale,
        transformOrigin = TransformOrigin(0.5f, 0.5f),
    ) + fadeExit(fadeDurationMillis)

    @Composable
    private fun animatorScale(): Float = LocalAnimatorScale.current

    private fun <T> tweenForScale(durationMillis: Int, easing: Easing, delayMillis: Int, scale: Float): DurationBasedAnimationSpec<T> {
        val safeScale = sanitizeScale(scale)
        val scaledDuration = scaleDuration(durationMillis, safeScale)
        val scaledDelay = if (scaledDuration == 0 || delayMillis <= 0 || safeScale <= 0f) {
            0
        } else {
            (delayMillis * safeScale).roundToInt().coerceAtLeast(0)
        }
        return composeTween(
            durationMillis = scaledDuration,
            easing = easing,
            delayMillis = scaledDelay,
        )
    }

    private fun scaleDuration(durationMillis: Int, scale: Float): Int {
        val safeScale = sanitizeScale(scale)
        if (durationMillis <= 0) return 0
        // v2.6.x arreglo de fallas: La animación del sistema escala a 0(Opciones de desarrollador/Animación del modo de ahorro de energía desactivado)tiempo,
        // No limites el tiempo para 0 —— navigation-compose El NavHost es utilizado internamente por
        // SeekableTransitionState Calcular el progreso es la división de playTime / totalDurationNanos,
        // Se calculará una duración total de 0. NaN(0/0),Luego pásalo a seekTo y tíralo.
        // "IllegalArgumentException: Expecting fraction between 0 and 1. Got NaN".
        // 1ms Visualmente todavía está "completo de inmediato",Pero haga que totalDurationNanos no sea 0,Evite la división por cero.
        val scaled = if (safeScale <= 0f) 1 else (durationMillis * safeScale).roundToInt()
        return scaled.coerceAtLeast(1)
    }

    private fun sanitizeScale(value: Float): Float = if (value.isFinite()) value.coerceAtLeast(0f) else 1f

    private fun readAnimatorScale(context: Context): Float {
        return try {
            sanitizeScale(
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f,
                ),
            )
        } catch (e: SecurityException) {
            // Las ROM personalizadas individuales se niegan a leer la configuración del sistema; se tratan como no degradados y se registran para facilitar la resolución de problemas.
            android.util.Log.w("MuseMotion", "read animator scale denied", e)
            1f
        }
    }
}
