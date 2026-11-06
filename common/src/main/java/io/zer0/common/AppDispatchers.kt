package io.zer0.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Entrada del planificador global,Todos los módulos se pueden recuperar desde aquí. dispatcher,
 * Es conveniente para probar avatares posteriores o cambiar de plataforma.
 */
object AppDispatchers {
    val io: CoroutineDispatcher get() = Dispatchers.IO
    val default: CoroutineDispatcher get() = Dispatchers.Default
    val main: CoroutineDispatcher get() = Dispatchers.Main
}
