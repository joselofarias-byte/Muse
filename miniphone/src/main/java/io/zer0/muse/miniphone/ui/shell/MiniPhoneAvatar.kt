package io.zer0.muse.miniphone.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * Avatar.
 *
 * Este módulo no tiene la capacidad de cargar imágenes.(Galería de imágenes no citadas),Por lo tanto, el avatar se dibuja uniformemente como un medallón con "carácter inicial + fondo fino de color acentuado".;
 * El uri del avatar del personaje no participa en el renderizado por el momento.,Espere a que el módulo se conecte a la imagen y cárguelo antes de cambiar a la imagen real.
 */
@Composable
internal fun MiniPhoneAvatar(name: String, size: Dp, modifier: Modifier = Modifier) {
    val colors = MiniPhoneTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.accent.copy(alpha = AVATAR_FILL_ALPHA))
            .border(AvatarStrokeWidth, colors.accent.copy(alpha = AVATAR_STROKE_ALPHA), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialOf(name),
            style = TextStyle(
                fontSize = (size.value * INITIAL_SCALE).sp,
                fontWeight = FontWeight.Medium,
                color = colors.accent,
            ),
        )
    }
}

/** Toma la primera letra.:Convertir letras latinas a mayúsculas,Caracteres chinos tal como son.,Un nombre vacío proporciona un marcador de posición. */
internal fun initialOf(name: String): String {
    val first = name.trim().firstOrNull() ?: return MISSING_INITIAL
    return first.uppercaseChar().toString()
}

/** La relación entre el texto del avatar y el diámetro.:El pequeño avatar no se agrupará. */
private const val INITIAL_SCALE: Float = 0.4f
private const val AVATAR_FILL_ALPHA: Float = 0.14f
private const val AVATAR_STROKE_ALPHA: Float = 0.22f
private val AvatarStrokeWidth: Dp = 1.dp

private const val MISSING_INITIAL: String = "?"
