package io.zer0.muse.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tres niveles de tamaño de avatar(CHAT-03:Convergencia 28/32/40/48dp cuatro valores desnudos).
 *  - [inline]: Mini avatar en la línea de mensajes.(charla privada/Junto a la burbuja de mensajes del chat grupal)
 *  - [list]:   Avatar de elemento de lista(Lista de conversaciones/lista de chat grupal)
 *  - [detail]: Detalles/Imagen de guía(Saludos vacíos, etc.)
 */
object MuseAvatarSize {
    // v2.6.10: 28 → 36. El avatar en la línea del mensaje es demasiado pequeño para cubrir la línea del nombre y es difícil saber quién es.
    val inline: Dp = 36.dp
    val list: Dp = 40.dp
    val detail: Dp = 48.dp
}
