package io.zer0.muse.ui.common.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * v1.0.29: Muse Universales Scaffold,Procesamiento unificado de inserciones de la barra de estado.
 *
 * Problemas con el historial del proyecto:debajo de borde a borde,Cada página se maneja por sí misma. statusBarsPadding(),
 * Muy fácil de perder,Como resultado, el contenido invade la barra de estado cuando la página se desliza hacia arriba.(muy común,Tuve que arreglarlo manualmente cada vez antes.).
 *
 * Este componente proporciona un comportamiento consistente en todas las páginas.:
 *  - Agregar automáticamente la barra superior statusBarsPadding() + color de fondo,Evitar que el contenido se deslice detrás de la barra de estado
 *  - Deshabilitar andamio de forma predeterminada contentWindowInsets,En cambio, la persona que llama lo controla a través de InnerPadding.
 *  - Sigue siendo compatible con el andamio Material3 API
 *
 * Uso:Ponga cada página `Scaffold(...)` Reemplazar con `MuseScaffold(...)`,
 * Y asegúrese de que lo pasado en topBar ya no se llame a sí mismo internamente statusBarsPadding()/background().
 */
@Composable
fun MuseScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    containerColor: Color = MaterialTheme.colorScheme.background,
    content: @Composable (PaddingValues) -> Unit,
) {
    MusePageScaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        containerColor = containerColor,
        topBarHandlesInsets = false,
        content = content,
    )
}
