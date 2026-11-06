package io.zer0.muse.util

import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.coroutines.CoroutineContext

/**
 * v0.53: Manejador de excepciones de rutina global(Tolerancia a fallos a nivel empresarial).
 *
 * Evite que las excepciones no detectadas en la rutina provoquen que la aplicación falle.
 * Se registrarán las excepciones,No se propagará al hilo predeterminado UncaughtExceptionHandler.
 *
 * Uso:
 * ```
 * viewModelScope.launch(GlobalCoroutineExceptionHandler) { ... }
 * // Combinado con despachador:
 * viewModelScope.launch(AppDispatchers.io + GlobalCoroutineExceptionHandler) { ... }
 * ```
 *
 * Nota:Este procesador solo cubre el resultado final"No atrapado por try-catch"excepción.
 * Las excepciones que han sido detectadas mediante intento de código comercial no activarán este controlador.
 * CancellationException Este procesador no se activará(La semántica de cancelación de corrutinas es manejada por el marco de corrutinas.).
 */
val GlobalCoroutineExceptionHandler = CoroutineExceptionHandler { _: CoroutineContext, throwable ->
    Logger.e("Coroutine", "Uncaught coroutine exception", throwable)
}
