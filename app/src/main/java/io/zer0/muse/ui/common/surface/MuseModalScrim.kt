package io.zer0.muse.ui.common.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Shared visual scrim for modal surfaces; platform window dim remains disabled.
 *
 * Los temas oscuro y claro deben elegir alfa respectivamente: el fondo del tema oscuro ya es muy oscuro y se utiliza el 38% de color negro del tema claro.
 * Hará que la capa de máscara sea casi invisible y la ventana emergente y el fondo se verán borrosos y grises. Los colores más oscuros requieren un enmascaramiento más intenso.
 * Empuje la capa inferior lejos, para que los dos niveles tengan valores separados.
 */
@Composable
internal fun museModalScrimColor(): Color {
    val scheme = MaterialTheme.colorScheme
    val darkBackground = scheme.background.luminance() < 0.5f
    return scheme.scrim.copy(alpha = if (darkBackground) 0.62f else 0.38f)
}

/**
 * Máscara modal:**Las áreas de la barra de estado y de la barra de navegación no están atenuadas.**.
 *
 * Anteriormente, solo se dejaba el área segura de la barra de navegación y la barra de estado estaba completamente cubierta por la máscara. —— Bajo el tema claro, aparece como "una ventana emergente".
 * La barra de estado de repente se vuelve negra", lo que obviamente está separado del fondo blanco cuando no hay una ventana emergente. La barra de estado también se deja aquí.
 * Deje que la barra del sistema mantenga siempre la apariencia original de la ventana del host y la máscara solo cubra el área de contenido real.
 */
@Composable
internal fun Modifier.museModalScrim(): Modifier = statusBarsPadding()
    .navigationBarsPadding()
    .background(museModalScrimColor())
