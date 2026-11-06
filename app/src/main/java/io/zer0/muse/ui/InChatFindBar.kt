package io.zer0.muse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.MuseTooltip
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MusePaddings

// A1: Barra de búsqueda durante la sesión — Barra flotante superior de la página de chat:Cuadro de consulta + recuento de visitas + elemento anterior/Siguiente + Cerrar.
// El posicionamiento de desplazamiento y el resaltado de texto se completan cuando la persona que llama reutiliza la canalización setTargetMessage.(highlightedMessageId +
// searchHighlightQuery + targetMessageId),Este componente sólo es responsable de los botones de entrada, conteo y salto.
// 8 parámetros:Estado de consulta + recuento + tres devoluciones de llamada de salto + modifier;La agregación en clases de datos dividirá el punto de llamada,
// Exenta LongParameterList por precedente ChatSelectionBar.
@Suppress("LongParameterList")
@Composable
internal fun InChatFindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    currentIndex: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasMatches = matchCount > 0
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.itemGap, vertical = MusePaddings.tinyGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MuseIcons.search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(MusePaddings.tightGap))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.chat_find_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            Spacer(Modifier.width(MusePaddings.tightGap))
            FindStatusLabel(query = query, matchCount = matchCount, currentIndex = currentIndex)
            // E4 (H8): Botón de navegación de ícono puro más mensaje de desplazamiento(Escritorio);No se muestra ningún evento de desplazamiento en el terminal celular
            MuseTooltip(text = stringResource(R.string.chat_find_prev)) {
                FindNavIconButton(
                    icon = MuseIcons.chevronUp,
                    description = stringResource(R.string.chat_find_prev),
                    enabled = hasMatches,
                    onClick = onPrev,
                )
            }
            MuseTooltip(text = stringResource(R.string.chat_find_next)) {
                FindNavIconButton(
                    icon = MuseIcons.chevronDown,
                    description = stringResource(R.string.chat_find_next),
                    enabled = hasMatches,
                    onClick = onNext,
                )
            }
            MuseTooltip(text = stringResource(R.string.chat_find_close)) {
                FindNavIconButton(
                    icon = MuseIcons.x,
                    description = stringResource(R.string.chat_find_close),
                    enabled = true,
                    onClick = onClose,
                )
            }
        }
    }
}

// A1: Buscar etiquetas de estado — No se muestran visitas"No hay coincidencia"(rojo),Pulsar pantalla"Artículo x/y"
@Composable
private fun FindStatusLabel(query: String, matchCount: Int, currentIndex: Int) {
    val hasMatches = matchCount > 0
    when {
        query.isNotBlank() && !hasMatches -> {
            Text(
                text = stringResource(R.string.chat_find_no_match),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        hasMatches -> {
            Text(
                text = stringResource(R.string.chat_find_count, currentIndex, matchCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// A1: Botón de navegación de la barra de búsqueda — Artículo anterior/Artículo siguiente/Desactivar estilos compartidos;disabled es semitransparente y se puede hacer clic no es válido
@Composable
private fun FindNavIconButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    MuseTactileButton(
        icon = icon,
        onClick = { if (enabled) onClick() },
        contentDescription = description,
        tint = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        },
        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
    )
}
