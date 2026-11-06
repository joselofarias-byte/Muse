package io.zer0.muse.miniphone.ui.shell

import androidx.compose.runtime.Immutable

/**
 * Cuatro devoluciones de llamada de acción en mi página.
 *
 * Reúnalos en lugar de usarlos como parámetros uno por uno.:Dos interruptores más dos entradas ya son cuatro elementos,Pasarlos sin apretar hará que la lista de parámetros sea más larga que su contenido.
 */
@Immutable
internal class MePageActions(
    val onToggleAutoMoment: (Boolean) -> Unit = {},
    val onToggleChatProactive: (Boolean) -> Unit = {},
    val onOpenMyMoments: () -> Unit = {},
    val onClearConversations: () -> Unit = {},
)
