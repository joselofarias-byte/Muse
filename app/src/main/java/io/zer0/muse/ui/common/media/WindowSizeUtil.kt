package io.zer0.muse.ui.common.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import io.zer0.muse.ui.theme.MuseBreakpoints

/**
 * P1-4 Adaptación de tableta:Clasificación del ancho de ventana.
 *
 * umbral(Material de alineación 3 Especificación de clase de tamaño de ventana,Los valores convergen a [MuseBreakpoints] ficha):
 *  - [Compact]:  < 600dp(Pantalla vertical del teléfono celular)
 *  - [Medium]:   600dp - 839dp(Pantalla vertical de tableta/pantalla horizontal de teléfono celular)
 *  - [Expanded]: ≥ 840dp(Tableta horizontal/escritorio)
 *
 * Instrucciones de implementación:El proyecto no está actualmente presentado. androidx.compose.material3:material3-window-size-class
 * dependencia independiente,Por lo tanto usado [LocalConfiguration] El juicio screenWidthDp(Semánticamente equivalente a WindowSizeClass
 * Juicio basado en windowSizeDp,Y se puede llamar directamente en cualquier ámbito Composable,No es necesario el contenedor BoxWithConstraints).
 */
enum class WindowWidthClass {
    /** Pantalla vertical del teléfono celular(< 600dp). */
    Compact,

    /** Pantalla vertical de tableta/pantalla horizontal de teléfono celular(600dp - 839dp). */
    Medium,

    /** Tableta horizontal/escritorio(≥ 840dp). */
    Expanded,
}

/**
 * Inferido en función del ancho de la ventana actual [WindowWidthClass].
 *
 * En cambios de configuración(Girar, dividir pantalla)LocalConfiguration se volverá a ensamblar cuando,Vuelve automáticamente a la nueva calificación.
 *
 * Ejemplo de uso:
 * ```
 * val widthClass = rememberWindowWidthClass()
 * if (widthClass == WindowWidthClass.Expanded) {
 *     // Diseño de doble columna
 * } else {
 *     // diseño de una sola columna
 * }
 * ```
 */
@Composable
fun rememberWindowWidthClass(): WindowWidthClass {
    val configuration = LocalConfiguration.current
    return remember(configuration.screenWidthDp) {
        when {
            configuration.screenWidthDp >= MuseBreakpoints.EXPANDED_DP -> WindowWidthClass.Expanded
            configuration.screenWidthDp >= MuseBreakpoints.MEDIUM_DP -> WindowWidthClass.Medium
            else -> WindowWidthClass.Compact
        }
    }
}
