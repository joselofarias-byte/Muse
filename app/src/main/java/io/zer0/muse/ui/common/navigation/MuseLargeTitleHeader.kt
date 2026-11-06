package io.zer0.muse.ui.common.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MusePaddings

/**
 * v2.0.1: Encabezado de título grande desplazable (compartido configurando la página de inicio/configurando la subpágina).
 *
 * Como primer elemento de LazyColumn, se inicia a medida que se desplaza el contenido; la barra de búsqueda del techo se proporciona por separado en la página/esqueleto
 * (Consulte la barra de búsqueda adhesiva de SettingsScreen y SettingsSubPageScaffold).
 *
 * Nota: Este componente**no contiene**relleno de la barra de estado——StatusBarsPadding es procesado uniformemente por el contenedor de desplazamiento externo.
 * Evite el doble acolchado.
 *
 * @param onBack Devolución de llamada; no represente la fila de retorno cuando sea nula (como en el modo de doble columna de la tableta).
 */
@Composable
fun MuseLargeTitleHeader(title: String, onBack: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (onBack != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.chipInnerTight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MuseTopBarIconButton(
                    icon = MuseIcons.arrowLeft,
                    contentDescription = stringResource(R.string.action_back),
                    onClick = onBack,
                    solid = false,
                )
                Spacer(Modifier.weight(1f))
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(
                horizontal = MusePaddings.messageGap,
                vertical = MusePaddings.tightGap,
            ),
        )
    }
}

/**
 * v2.0.1: Botón de retorno al techo — Después de empujar el cabezal de desplazamiento hacia afuera, el botón de retorno de fondo redondo aparece gradualmente en la esquina superior izquierda.
 *
 * con [MuseLargeTitleHeader] Usados ​​juntos: este botón aparecerá gradualmente después de que el contenedor de desplazamiento exterior determine el estado de inserción.
 * Asegúrese de que el título grande (incluida la línea de retorno) aún se pueda mostrar después de desplazarse fuera de la pantalla. El tamaño predeterminado está alineado con la tecla de retorno del cabezal.
 */
@Composable
fun MuseStickyBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    MuseTopBarIconButton(
        icon = MuseIcons.arrowLeft,
        contentDescription = stringResource(R.string.action_back),
        onClick = onBack,
        modifier = modifier,
        solid = true,
    )
}
