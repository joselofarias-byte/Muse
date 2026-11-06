package io.zer0.muse.ui.common.surface

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Muse UI Kit — divisor primitivo [MuseDivider].
 *
 * v1.0.26: Unifique la implementación de todos los separadores de listas, separadores de tarjetas y agrupación de formularios.,
 * Eliminar el uso desnudo de cada página [HorizontalDivider] Los valores de grosor/color/startIndent no son uniformes.
 *
 * Especificaciones:
 *  - thickness: 0.5dp(Alinear la configuración del sistema:1px línea del cabello)
 *  - color: onSurface @ 13% alpha(v2.0.1: Se modificó para que se derive del color de tinta del texto.——Aproximadamente #E1E1E1 en la tarjeta blanca,
 *    Alinee el color del hilo medido #E0E0E0; El modo oscuro cambia automáticamente a gris oscuro y se adapta a varios temas.
 *    Esquema del valor históricoVariante@70%"normalización del blanqueamiento"Luego se aclaró demasiado y era casi invisible en colores claros. )
 *  - Predeterminado startIndent: 56dp(= 16dp relleno de tarjeta + 24dp leading icon + 16dp icon-to-text gap)
 *    Configuración del sistema de alineación"Separado del lado derecho del contenido principal."estilo
 */
@Composable
fun MuseDivider(
    modifier: Modifier = Modifier,
    startIndent: Dp = 56.dp,
    thickness: Dp = 0.5.dp,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f),
) {
    HorizontalDivider(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startIndent),
        thickness = thickness,
        color = color,
    )
}
