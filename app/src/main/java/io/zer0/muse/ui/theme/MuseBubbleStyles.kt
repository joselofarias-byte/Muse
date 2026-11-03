package io.zer0.muse.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Estilo de compartir burbujas de mensajes(Decisión D1: Mover el chat único al chat grupal).
 *
 * charla privada [io.zer0.muse.ui.MessageBubble] Comparta el valor predeterminado de este grupo con el chat grupal GroupChatMessageBubble,
 * Elimina dos cambios de apariencia(CHAT-01/CHAT-02):
 *  - Burbuja de usuario: surfaceVariant Abajo + cola inferior derecha 6dp,texto onSurface/bodyMedium
 *  - Burbuja asistente: surfaceVariant@60% Tarjeta de luz + cola inferior izquierda 6dp,texto onSurface/bodyMedium
 *  - Ancho: Burbuja máximo 70% del ancho de pantalla(Mismo calibre que el chat grupal maxBubbleWidth)
 *
 * piel(BubbleSkin)Todavía solo cubre el caparazón cuando está presente.(color/esquinas redondeadas/relleno/Ancho),Los valores predeterminados son consistentes con este sitio.
 */
object MuseBubbleStyles {
    /** Relación de pantalla de ancho máximo de burbuja(70%,Alinear el chat grupal maxBubbleWidth). */
    const val MAX_WIDTH_FRACTION = 0.70f

    /**
     * Color de fondo predeterminado de la burbuja del usuario(sin piel).
     *
     * v2.0: de surfaceVariant(Casi del mismo color que el fondo.,Comentarios de los usuarios"burbujas invisibles")Cambiar al color principal y teñido claro.,
     * brillante/Se puede contrastar con el fondo bajo temas oscuros.;acariciado por [userBorderColor] Dale ventaja.
     */
    @Composable
    fun userSurfaceColor(): Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)

    /** Trazo predeterminado de la burbuja del usuario(sin piel)— color principal 24%,Aclare los límites de las burbujas. */
    @Composable
    fun userBorderColor(): Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)

    /**
     * Color de fondo predeterminado de la burbuja del asistente(sin piel)— Tarjeta de color claro.
     *
     * v2.0: Utilice SurfaceVariant para mezclar onSurface 6% —— Superficie puraVariante en temas parciales
     * (Como tema monocromático #F6F6F8 vs fondo #FBFBFC)Casi del mismo color que el fondo.,Comentarios de los usuarios"burbujas invisibles";
     * Brillante después de mezclar el color./Visibilidad estable bajo tema oscuro.
     */
    @Composable
    fun assistantSurfaceColor(): Color = androidx.compose.ui.graphics.lerp(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurface,
        0.06f,
    )

    /** Trazo predeterminado de la burbuja del asistente(sin piel)— outlineVariant,Dale a las tarjetas de colores claros un borde claro. */
    @Composable
    fun assistantBorderColor(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f)

    /** Color de texto predeterminado de la burbuja del usuario(sin piel). */
    @Composable
    fun userContentColor(): Color = MaterialTheme.colorScheme.onSurface

    /** ancho máximo de burbuja(70% ancho de pantalla,Consistente con el chat grupal maxBubbleWidth). */
    @Composable
    fun maxBubbleWidth(): Dp = with(LocalConfiguration.current) { (screenWidthDp * MAX_WIDTH_FRACTION).dp }

    /** Forma de burbuja de usuario:filete de cuerpo [radiusDp](null Usar 20dp predeterminado)+ Cola inferior derecha de 6 dp. */
    fun userBubbleShape(radiusDp: Float? = null): RoundedCornerShape {
        val r = (radiusDp ?: MuseCornerRadius.CARD.toFloat()).dp
        return RoundedCornerShape(r, r, MuseCornerRadius.BUBBLE_TAIL.dp, r)
    }

    /** Forma de burbuja asistente:filete de cuerpo [radiusDp](null Usar 20dp predeterminado)+ Cola inferior izquierda de 6dp. */
    fun assistantBubbleShape(radiusDp: Float? = null): RoundedCornerShape {
        val r = (radiusDp ?: MuseCornerRadius.CARD.toFloat()).dp
        return RoundedCornerShape(r, r, r, MuseCornerRadius.BUBBLE_TAIL.dp)
    }
}
