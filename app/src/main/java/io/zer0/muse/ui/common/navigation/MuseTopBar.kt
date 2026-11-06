package io.zer0.muse.ui.common.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.museTopBarInsets
import io.zer0.muse.ui.theme.MusePaddings

/**
 * barra superior universal — Reemplaza el material TopAppBar / LargeTopAppBar.
 *
 * estructura:statusBarsPadding → Fila del botón de retorno → Large Title(Opcional).
 * Se aplica a todas las subpáginas.(Establecer subpágina/asistente/base de conocimientos/memoria/Traducción, etc.).
 *
 * @param title texto del título
 * @param onBack devolución de llamada(Si es nulo, no muestra el botón Atrás)
 * @param largeTitle Si se debe utilizar el estilo de título grande(32dp Negrita)
 * @param actions Área de operación derecha(botón me gusta/icono)
 * @param modifier modificador
 */
@Composable
fun MuseTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    largeTitle: Boolean = false,
    actions: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // v1.132: background Debe preceder a statusBarsPadding,De lo contrario, el fondo sólo se dibuja padding
            // área de contenido después,El área de la barra de estado será transparente.(La barra de estado de la página de segundo nivel confirma de forma transparente que se debe a este problema de secuencia.)
            .background(MaterialTheme.colorScheme.background)
            .museTopBarInsets(),
    ) {
        // Botón Atrás + contraparte del título(modo normal)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.chipInnerTight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        MuseIcons.arrowLeft,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            }
            if (!largeTitle) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    // v2.0.1: Se omite una sola línea del título de la columna superior — Cuando hay muchos botones de operación (como el navegador de productos de 4 teclas), el título ya no se doblará en varias líneas.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(MusePaddings.bubbleInner),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            actions()
        }
        // Large Title linea exclusiva(v1.0.19: El tamaño de fuente cambió para usar el token displayLarge,Cumple con el estándar de bloque de título grande de 34 sp.)
        if (largeTitle) {
            Text(
                text = title,
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = MusePaddings.messageGap, vertical = MusePaddings.tightGap),
            )
        }
    }
}
