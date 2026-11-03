@file:Suppress("FunctionNaming", "LongMethod")

package io.zer0.muse.ui.translate

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseIconContainer
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.favoriteStarTint
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Área de historial de traducción.
 *
 * [history] ha sido utilizado por la persona que llama basándose en [favoritesOnly] Get Good (historial completo/colección únicamente),
 * [totalHistoryCount] Pase la cantidad de elementos sin filtrar, que solo se utiliza para determinar si se muestra el interruptor de favorito. ——
 * De lo contrario, el interruptor desaparecerá cuando la colección esté vacía y el usuario quedará atrapado en la lista vacía y no podrá regresar.
 */
@Suppress("LongParameterList")
@Composable
internal fun TranslateHistorySection(
    history: List<TranslateViewModel.TranslateHistoryItem>,
    totalHistoryCount: Int,
    favoritesOnly: Boolean,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    onItemClick: (TranslateViewModel.TranslateHistoryItem) -> Unit,
    onClearClick: () -> Unit,
    onToggleFavorite: (TranslateViewModel.TranslateHistoryItem) -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                Icon(
                    imageVector = MuseIcons.history,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
                Text(
                    text = stringResource(R.string.translate_page_history_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                // §11-6: La colección de traducciones solía estar completamente desconectada —— Se puede hacer clic en las estrellas, colocarlas en la biblioteca e ingresarlas en state.favorites.
                // Pero la interfaz de usuario nunca muestra favoritos. Añade uno aquí"Ver solo colecciones"Proyección, calibre y círculo de amigos.
                // favoritesOnly Mismo tipo (el mismo interruptor determina la fuente de la lista), no se abrirá ninguna página nueva.
                // El cambio se juzga por el "Historial completo" en lugar de por la lista actual; de lo contrario, el cambio desaparecerá cuando la colección esté vacía.
                // El usuario está atrapado en la lista vacía y no puede regresar.
                if (totalHistoryCount > 0) {
                    MuseTactileButton(
                        icon = MuseIcons.star,
                        onClick = { onFavoritesOnlyChange(!favoritesOnly) },
                        contentDescription = stringResource(
                            if (favoritesOnly) {
                                R.string.translate_page_history_show_all
                            } else {
                                R.string.translate_page_history_favorites_only
                            },
                        ),
                        tint = favoriteStarTint(favoritesOnly),
                        container = if (favoritesOnly) MuseIconContainer.Tonal else MuseIconContainer.None,
                    )
                    MuseTactileButton(
                        icon = MuseIcons.trash,
                        onClick = onClearClick,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (history.isEmpty()) {
            // ST-03: El estado vacío unifica MuseEmptyState. El estado vacío de la vista de colección debe ser el mismo que"sin historia en absoluto"Distinguir.
            MuseEmptyState(
                title = stringResource(
                    if (favoritesOnly) {
                        R.string.translate_page_history_no_favorites
                    } else {
                        R.string.translate_page_history_empty
                    },
                ),
                modifier = Modifier.padding(vertical = MusePaddings.itemGap),
            )
            return
        }

        Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap)) {
            history.forEach { item ->
                TranslateHistoryItemCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onToggleFavorite = { onToggleFavorite(item) },
                    onLongPressCopy = {
                        // v2.4.6: Mantenga presionado para copiar"Texto original + traducción"(trae ambos,Fácil de pegar y usar directamente)
                        val ctx = context
                        copyToClipboard(
                            ctx,
                            ctx.getString(
                                R.string.translate_history_copy_format,
                                item.sourceText,
                                item.translatedText,
                            ),
                        )
                        MuseToast.show(ctx.getString(R.string.translate_history_copied))
                    },
                )
            }
        }
    }
}

/**
 * Tarjeta de historial de traducción única — Estilo de dibujo de diseño.
 *
 * arriba:Chip de flujo de lenguaje + tiempo relativo + estrella favorita
 * céntrico:original + texto original(línea única omitida)
 * abajo:traducción + traducción(línea única omitida)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranslateHistoryItemCard(
    item: TranslateViewModel.TranslateHistoryItem,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onLongPressCopy: () -> Unit,
) {
    val timeText = remember(item.timestamp) { formatHistoryTime(item.timestamp) }

    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongPressCopy,
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = MusePaddings.itemGap, vertical = MusePaddings.auxGap),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // fuente → idioma de destino chip
                Surface(
                    shape = MuseShapes.pill,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        // LAYOUT-01: Las cápsulas de lenguaje en la tarjeta son textos cortos en línea.(El ancho de línea está determinado por el contenido.,Sin ancho),Límite a 1 línea para evitar el ajuste palabra por palabra.
                        Text(
                            text = item.sourceLanguage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Icon(
                            imageVector = MuseIcons.swapHorizontal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(MuseIconSizes.iconTiny),
                        )
                        Text(
                            text = item.targetLanguage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    // LAYOUT-01: La marca de tiempo es un texto breve con un ancho fijo al final de la línea.,Límite de 1 línea,Evite que los botones favoritos lo aprieten en una columna.
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                    )
                    MuseTactileButton(
                        // §11-8: Un icono de una sola estrella + tinte + tres niveles de color de fondo expresan el estado seleccionado (no hay ningún contorno de estrella disponible).
                        icon = MuseIcons.star,
                        onClick = onToggleFavorite,
                        contentDescription = stringResource(
                            if (item.favorite) {
                                R.string.translate_page_favorite_remove
                            } else {
                                R.string.translate_page_favorite_add
                            },
                        ),
                        tint = favoriteStarTint(item.favorite),
                        // Se ha recogido el fondo sólido y redondo, y no hay fondo si no se ha recogido. La diferencia en los bloques de color es más resistente a la deriva de temas claros y oscuros que la diferencia de color.
                        container = if (item.favorite) MuseIconContainer.Tonal else MuseIconContainer.None,
                        size = MuseIconSizes.touchTarget,
                        iconSize = MuseIconSizes.iconSmall,
                    )
                }
            }
            // original + texto original
            HistoryTextLine(
                label = stringResource(R.string.translate_page_history_source_short),
                text = item.sourceText,
            )
            // traducción + traducción
            HistoryTextLine(
                label = stringResource(R.string.translate_page_history_translated_short),
                text = item.translatedText,
            )
        }
    }
}

/** en la tarjeta de historia"etiqueta + contenido"Línea única. */
@Composable
private fun HistoryTextLine(label: String, text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        // LAYOUT-01: "Texto original/La etiqueta "Traducción" es una palabra corta que determina el ancho al principio de la línea.(ancho restante del texto weight),Límite de 1 línea.
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 2.dp),
            maxLines = 1,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Formatee las marcas de tiempo históricas como tiempos relativos. */
private fun formatHistoryTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "justo ahora"
        diff < 60 * 60_000 -> "${diff / 60_000} hace minutos"
        diff < 24 * 60 * 60_000 -> "${diff / (60 * 60_000)} hace horas"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
