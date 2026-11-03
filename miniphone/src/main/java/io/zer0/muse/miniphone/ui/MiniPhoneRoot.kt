package io.zer0.muse.miniphone.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.zer0.muse.miniphone.ui.shell.MiniPhoneShell
import io.zer0.muse.miniphone.ui.shell.MiniPhoneShellActions
import io.zer0.muse.miniphone.ui.shell.MiniPhoneShellViewModel
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import org.koin.androidx.compose.koinViewModel

/**
 * Entrada para pequeño módulo de telefonía celular.
 *
 * solo haz tres cosas:Obtenga el ViewModel de shell de Koin, recopile una copia de su estado y proporcione el estado al shell en el tema.
 * El límite entre estado y renderizado está aquí —— Los siguientes son todos componentes sin estado.,Puede introducir datos directamente en la prueba.
 *
 * Este módulo aún no se ha conectado a la navegación del host.,Actualmente, esta entrada solo se utiliza para vistas previas independientes y pruebas de interfaz de usuario.
 */
@Composable
fun MiniPhoneRoot(modifier: Modifier = Modifier) {
    val viewModel: MiniPhoneShellViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsState()
    MiniPhoneTheme {
        MiniPhoneShell(
            state = state,
            modifier = modifier,
            onOpenCharacterChat = viewModel::openOrCreateThread,
            actions = MiniPhoneShellActions(
                onToggleAutoMoment = viewModel::setAutoMomentEnabled,
                onToggleChatProactive = viewModel::setChatProactiveEnabled,
                onToggleThreadPin = viewModel::setThreadPinned,
                onMarkThreadRead = viewModel::markThreadRead,
                onDeleteThread = viewModel::deleteThread,
                onClearConversations = viewModel::clearConversations,
                onCreateGroup = viewModel::createGroup,
            ),
        )
    }
}
