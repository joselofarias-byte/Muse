package io.zer0.muse.miniphone.ui.chat

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import io.zer0.muse.miniphone.ui.shell.MiniPhoneAvatar

/**
 * avatar de la página de chat:Si hay una uri, carga la imagen real.,No hay respaldo a las iniciales o no se pudo cargar.
 *
 * Retroceder no es una tapadera,Es la forma de avatar propia del módulo. —— Se utiliza para libretas de direcciones y listas de mensajes.,
 * No habrá dos miradas "sin avatar" en la misma pantalla.
 */
@Composable
internal fun MiniPhoneChatAvatar(name: String, avatarUri: String?, size: Dp, modifier: Modifier = Modifier) {
    if (avatarUri.isNullOrBlank()) {
        MiniPhoneAvatar(name = name, size = size, modifier = modifier)
        return
    }
    // No más reintentos después de que falla la carga:Si el mismo uri falla repetidamente, solo parpadeará.,no tendrá éxito
    var failed by remember(avatarUri) { mutableStateOf(false) }
    if (failed) {
        MiniPhoneAvatar(name = name, size = size, modifier = modifier)
        return
    }
    AsyncImage(
        model = avatarUri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onError = { failed = true },
        modifier = modifier
            .size(size)
            .clip(CircleShape),
    )
}
