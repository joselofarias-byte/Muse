package io.zer0.muse.ui.common.surface

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Muse La adaptación de la barra del sistema es la única entrada.
 *
 * Reglas:
 * - El contenedor de la barra superior es responsable de la barra de estado y statusBarsPadding ya no se agrega al contenido de la página.
 * - barra inferior/El panel inferior es responsable general de la barra de navegación, el área de gestos del sistema y el IME, y el contenido interno solo maneja el espacio comercial.
 * - La capa de fondo se puede ampliar de forma inmersiva, pero la capa de contenido en la que se puede hacer clic debe utilizar el modificador de área segura correspondiente.
 */
fun Modifier.museTopBarInsets(): Modifier = statusBarsPadding()

fun Modifier.museBottomBarInsets(): Modifier = navigationBarsPadding()
    .imePadding()

fun Modifier.museDialogInsets(): Modifier = imePadding().navigationBarsPadding()

/**
 * v2.0.1: Acolchado superior confiable de la barra de estado (solo para estructuras nuevas de techo/desplazamiento).
 *
 * Algunos entornos (como la pantalla virtual MuMu) informan las inserciones de barras de estado en la aplicación como 0/valor mínimo.
 * Esto permitirá que los elementos del techo y las ventanas gráficas de desplazamiento invadan la barra de estado; cuando las inserciones son anormalmente pequeñas, volverá a valores conservadores.
 * insets Normalmente se utiliza el valor de verdad del sistema.
 *
 * con [museTopBarInsets] La diferencia: este último es una entrada normal en la barra superior de la página (comportamiento histórico,
 * Confíe en la altura del diseño para evitar la barra de estado); esta función es para“El contenido desplazable es responsable del área segura superior”nueva estructura
 * (Establezca una barra de búsqueda en la parte superior de la página, un título desplazable, etc.).
 */
@Composable
fun Modifier.museSafeTopInsetPadding(): Modifier {
    val density = LocalDensity.current
    val insetsTop = WindowInsets.statusBars.getTop(density)
    val minValid = with(density) { MIN_VALID_TOP_DP.dp.roundToPx() }
    val fallback = with(density) { FALLBACK_TOP_DP.dp.roundToPx() }
    val topPx = if (insetsTop < minValid) fallback else insetsTop
    return this.then(Modifier.padding(top = with(density) { topPx.toDp() }))
}

/** Relleno superior inferior: excepción de inserción de barra de estado (0 / extremadamente pequeño). */
private const val FALLBACK_TOP_DP = 30

/** considerado como“inusualmente pequeño”El umbral de las inserciones. */
private const val MIN_VALID_TOP_DP = 8
