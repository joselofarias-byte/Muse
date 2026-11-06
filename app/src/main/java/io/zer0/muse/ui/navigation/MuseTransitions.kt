package io.zer0.muse.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion

/**
 * Animación de transición de navegación. helper — Elimine 65 bloques duplicados enterTransition/popExitTransition en NavGraph.
 *
 * Proporcionar dos transiciones semánticas.:
 *  - Empuje horizontal: deslizable derecho estándar/Desliza hacia la izquierda(pagina de segundo nivel push),Se utiliza para la mayoría de las páginas secundarias.
 *  - Empuje vertical: página de inicio INICIO transición vertical exclusiva
 *
 * v1.0.16:La duración converge uniformemente a [MuseAnimation] ficha(NAV_HORIZONTAL_MS / NAV_VERTICAL_ENTER_MS /
 * NAV_VERTICAL_EXIT_MS),Se acabó el uso de const privada para eludir el sistema de tokens.
 *
 * Uso:
 * ```
 * composable(
 *     route = MuseRoutes.XXX,
 *     enterTransition = { MuseTransitions.horizontalPushEnter() },
 *     popExitTransition = { MuseTransitions.horizontalPushPopExit() },
 * )
 * ```
 *
 * Nota:slideInHorizontally / fadeIn / slideOutVertically Los constructores de animación son todas funciones de nivel superior.,
 * no depende de AnimatedContentTransitionScope receiver,Por lo tanto, los métodos de este objeto son todos funciones ordinarias.,Se puede pasar directamente
 * `MuseTransitions.xxx()` llamar.
 */
// Los cuatro tipos de transiciones, vertical, horizontal, desvanecimiento puro y sin desvanecimiento, deben agruparse en el mismo vocabulario y exceder el umbral predeterminado de funciones es intencional.
@Suppress("TooManyFunctions")
object MuseTransitions {

    /**
     * v2.6.7: Desplazamiento a nivel de página en píxeles (ancho/alto 1/12 ≈ 8%).
     *
     * antigua implementación `initialOffsetX = { it }` Deje que la página llene toda la pantalla, con easeOutCubic
     * El sentido de la lectura es"Da un paso rápido y luego arrastra hacia atrás a la posición original.".kelivo Página de posición completa/Desplazamiento de contenido en 6%~8%,
     * Lo que se decide hay"¿De dónde viene esta página?", no"¿Hasta dónde quieres deslizar esta página?".
     */
    private fun pageOffset(total: Int): Int = (total * MuseAnimation.SLIDE_FRACTION_PAGE).toInt()

    /** v2.6.7: Desplazamiento de capa menor (1/16 ≈ 6%)—— La página cubierta sale,pop Regreso. */
    private fun secondaryOffset(total: Int): Int = (total * MuseAnimation.SLIDE_FRACTION_SECONDARY).toInt()

    /** Entrada por empuje de nivel estándar(Deslizamiento desde la derecha + aparición gradual). */
    fun horizontalPushEnter(): EnterTransition = slideInHorizontally(
        MuseMotion.tweenSpec(MuseAnimation.ENTER_MS, easing = MuseAnimation.EaseOutCubic),
        initialOffsetX = { pageOffset(it) },
    ) + fadeIn(MuseMotion.tweenSpec(MuseAnimation.ENTER_MS, easing = MuseAnimation.EaseOutCubic))

    /** v1.0.80: Admisión pura de diapositivas(sin desvanecimiento),Evite que la translucidez se vea a través de fondos oscuros.“máscara”. */
    fun horizontalSlideEnter(): EnterTransition = slideInHorizontally(
        MuseMotion.tweenSpec(MuseAnimation.ENTER_MS, easing = MuseAnimation.EaseOutCubic),
        initialOffsetX = { pageOffset(it) },
    )

    /** v1.0.80: Salida puramente deslizante(No se desvanece),con [horizontalSlideEnter] Par. */
    fun horizontalSlidePopExit(): ExitTransition = slideOutHorizontally(
        MuseMotion.tweenSpec(MuseAnimation.EXIT_MS, easing = MuseAnimation.EaseInCubic),
        targetOffsetX = { pageOffset(it) },
    )

    /** Salida de empuje de nivel estándar(Deslizar hacia la derecha + desvanecerse). */
    fun horizontalPushPopExit(): ExitTransition = slideOutHorizontally(
        MuseMotion.tweenSpec(MuseAnimation.EXIT_MS, easing = MuseAnimation.EaseInCubic),
        targetOffsetX = { pageOffset(it) },
    ) + fadeOut(MuseMotion.tweenSpec(MuseAnimation.EXIT_MS, easing = MuseAnimation.EaseInCubic))

    /** salida de empuje horizontal(Deslizar hacia la izquierda 1/3 + desvanecerse)— Salga cuando se sobrescriba la página raíz. */
    fun horizontalPushExit(): ExitTransition = slideOutHorizontally(
        MuseMotion.tweenSpec(MuseAnimation.EXIT_MS, easing = MuseAnimation.EaseInCubic),
        targetOffsetX = { -secondaryOffset(it) },
    ) + fadeOut(MuseMotion.tweenSpec(MuseAnimation.EXIT_MS, easing = MuseAnimation.EaseInCubic))

    /** Entrada emergente horizontal(Deslizamiento 1/3 desde la izquierda + aparición gradual)— Entrada al regresar a la página raíz. */
    fun horizontalPopEnter(): EnterTransition = slideInHorizontally(
        MuseMotion.tweenSpec(MuseAnimation.ENTER_MS, easing = MuseAnimation.EaseOutCubic),
        initialOffsetX = { -secondaryOffset(it) },
    ) + fadeIn(MuseMotion.tweenSpec(MuseAnimation.ENTER_MS, easing = MuseAnimation.EaseOutCubic))

    /** Entrada de inserción vertical dedicada a la página de inicio(Deslizamiento desde abajo + aparición gradual). */
    fun verticalPushEnter(): EnterTransition = slideInVertically(
        animationSpec = MuseMotion.tweenSpec(MuseAnimation.ENTER_MS, easing = MuseAnimation.EaseOutCubic),
        initialOffsetY = { pageOffset(it) },
    ) + fadeIn(MuseMotion.tweenSpec(MuseAnimation.ENTER_MS, easing = MuseAnimation.EaseOutCubic))

    /** Salida de empuje vertical dedicada a la página de inicio(Subir 1/3 + desaparecer). */
    fun verticalPushExit(): ExitTransition = slideOutVertically(
        animationSpec = MuseMotion.tweenSpec(MuseAnimation.EXIT_MS, easing = MuseAnimation.EaseInCubic),
        targetOffsetY = { -secondaryOffset(it) },
    ) + fadeOut(MuseMotion.tweenSpec(MuseAnimation.EXIT_MS, easing = MuseAnimation.EaseInCubic))

    /** Entrada emergente vertical dedicada a la página de inicio(Regresar a la página de inicio desde arriba + aparición gradual). */
    fun verticalPopEnter(): EnterTransition = slideInVertically(
        animationSpec = MuseMotion.tweenSpec(MuseAnimation.ENTER_MS, easing = MuseAnimation.EaseOutCubic),
        initialOffsetY = { -secondaryOffset(it) },
    ) + fadeIn(MuseMotion.tweenSpec(MuseAnimation.ENTER_MS, easing = MuseAnimation.EaseOutCubic))

    /** Salida pop vertical dedicada a la página de inicio(Mover hacia abajo + desaparecer). */
    fun verticalPushPopExit(): ExitTransition = slideOutVertically(
        animationSpec = MuseMotion.tweenSpec(MuseAnimation.EXIT_MS, easing = MuseAnimation.EaseInCubic),
        targetOffsetY = { pageOffset(it) },
    ) + fadeOut(MuseMotion.tweenSpec(MuseAnimation.EXIT_MS, easing = MuseAnimation.EaseInCubic))

    /** Puro desvanecimiento, utilizado para páginas inmersivas de pantalla completa y otras entradas que no son adecuadas para inserción horizontal. */
    fun fadeEnter(): EnterTransition = fadeIn(MuseMotion.tweenSpec(MuseAnimation.NORMAL_MS))

    /** puro desvanecimiento, con [fadeEnter] Par. */
    fun fadeExit(): ExitTransition = fadeOut(MuseMotion.tweenSpec(MuseAnimation.NORMAL_MS))
}
