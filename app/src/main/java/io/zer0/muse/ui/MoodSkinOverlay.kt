package io.zer0.muse.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import io.zer0.muse.ui.theme.MoodSkinColors
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion

/**
 * B6-02: pelle-d-umore Superposición de piel de emoción en pantalla completa.
 *
 * Representado detrás de la lista de mensajes, cambie el color del estado de ánimo de acuerdo con el estado de ánimo del último mensaje del asistente.
 * rage/rage2/desire/vuoto/moonlight Cada uno tiene un gradiente independiente y un ligero halo de respiración.
 */
@Composable
fun MoodSkinOverlay(skin: String?) {
    if (skin == null || skin == "off") return

    val colors = MoodSkinColors.overlays[skin] ?: MoodSkinColors.defaultOverlay

    val alpha = if (MuseMotion.isReducedMotion()) {
        0.82f
    } else {
        val transition = rememberInfiniteTransition(label = "moodSkin")
        val animatedAlpha by transition.animateFloat(
            initialValue = 0.65f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = MuseMotion.tween(MuseAnimation.LOOP_EXTRA_SLOW_MS * 2),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "moodSkinAlpha",
        )
        animatedAlpha
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(colors),
            )
            .background(color = Color.White.copy(alpha = (1f - alpha) * 0.08f)),
    )
}
