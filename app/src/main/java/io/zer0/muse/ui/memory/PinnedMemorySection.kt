package io.zer0.muse.ui.memory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.memory.pin.PinnedMemoryStore
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.theme.MuseMotion

/**
 * Área de gestión de memoria anclada.
 *
 * Muestra todos los recuerdos anclados y el botón de eliminar.
 */
@Composable
fun PinnedMemorySection(pinnedEntries: List<PinnedMemoryStore.PinnedEntry>, onRemove: (String) -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = pinnedEntries.isNotEmpty(),
        modifier = modifier,
        enter = MuseMotion.expandFadeEnter(),
        exit = MuseMotion.expandFadeExit(),
    ) {
        MuseSurface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(MuseIcons.pin, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Text(
                        text = stringResource(R.string.review_pinned_memories_count, pinnedEntries.size),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 8.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                // v1.0.90 fix: LazyColumn se usó originalmente aquí, pero este componente se incluyó en MemoryScreen
                // LazyColumn item adentro —— Cuando una lista está anidada dentro de una lista, la capa interna será ilimitada"altura máxima",
                // Lanza IllegalStateException directamente y falla. Cámbielo a una expansión normal dentro de una pantalla (la forma existente de escribir preguntas similares en el proyecto).
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    pinnedEntries.forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = entry.content,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                // LAYOUT-01: Row Ancho del texto interno + límite de línea, evita el ajuste de línea palabra por palabra al ampliar el tamaño de fuente
                                // (A la derecha hay un botón de eliminación de ancho fijo que deja 2 líneas de contenido legible en el texto de la memoria.)
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            MuseTactileButton(
                                icon = MuseIcons.trash,
                                onClick = { onRemove(entry.id) },
                                contentDescription = stringResource(R.string.memory_unpin_cd),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}
