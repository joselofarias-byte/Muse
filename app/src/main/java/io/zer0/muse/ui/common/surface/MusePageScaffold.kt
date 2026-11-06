package io.zer0.muse.ui.common.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Las páginas ordinarias tienen un esqueleto unificado.
 *
 * topBar De forma predeterminada, se considera que la persona que llama ha pasado [MuseTopBar] Barra de estado del proceso; se puede pasar la barra superior personalizada
 * [topBarHandlesInsets] = false, manejado por el esqueleto. De esta manera“quien es responsable status bar”Sólo hay un lugar.
 */
@Composable
fun MusePageScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    containerColor: Color = MaterialTheme.colorScheme.background,
    topBarHandlesInsets: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            if (topBarHandlesInsets) {
                topBar()
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(containerColor)
                        .museTopBarInsets(),
                ) { topBar() }
            }
        },
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        containerColor = containerColor,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding -> content(innerPadding) }
}
