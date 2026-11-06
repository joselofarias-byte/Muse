package io.zer0.muse.ui.artifact

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.markdown.CodeHighlighter
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.largeCard

/**
 * tarjeta de producto —— v2.0.1 Paradigma de tarjeta grande (tarjeta de ancho completo: encabezado/área de vista previa/fila de información).
 *
 * - Categoría de página web (html / svg / chart / mermaid): La barra de marcador de posición le indica que puede hacer clic para obtener una vista previa de la página web.
 *   Haga clic para ingresar al modo de navegador de pantalla completa (SettingsSearchBridge Vista previa en pantalla completa del mismo conjunto);
 * - Categoría de código: las primeras 6 líneas de fuente monoespaciada;
 * - Texto/Otro: Vista previa de las primeras 4 líneas de texto.
 *
 * Se puede hacer clic en toda la tarjeta (abra el visor/navegador de pantalla completa); operaciones como compartir y guardar que se proporcionan en el visor.
 * La versión anterior es una tarjeta pequeña de ancho fijo de 180 dp con información delgada y"Archivo"Inconsistencia mental (comentarios de los usuarios"demasiado feo/En blanco").
 */
@Composable
fun ArtifactCard(artifact: ArtifactEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val meta = artifactMetaLabel(type = artifact.type, language = artifact.language)
    val isWeb = richPreviewLanguage(artifact.type, artifact.language) != null
    val isCode = artifact.type.lowercase() == "code"
    val charCount = artifact.content.length

    MuseSurface(
        onClick = onClick,
        shape = MuseShapes.largeCard,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Button
            },
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Encabezado: escriba icono + título + escriba logotipo
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = artifactTypeIcon(artifact.type),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = artifact.title.ifBlank { stringResource(R.string.artifact_untitled) },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // área de vista previa
            if (isWeb) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MuseShapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = MuseIcons.languages,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.artifact_open_web_preview),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // v2.0.1: Resumen de contenido — El primer párrafo de texto después de eliminar etiquetas.(Comprender rápidamente el contenido de la página.)
                val plainPreview = remember(artifact.content) {
                    io.zer0.muse.util.stripHtmlComprehensive(artifact.content)
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .take(80)
                }
                if (plainPreview.isNotBlank()) {
                    Text(
                        text = plainPreview,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                val previewText =
                    remember(artifact.content, isCode) {
                        if (isCode) {
                            artifact.content.lines().take(6).joinToString("\n")
                        } else {
                            artifact.content
                                .lines()
                                .asSequence()
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                // Filas separadas por tabla (|---|---|) no es informativo sobre la vista previa
                                .filterNot { it.matches(Regex("^[|\\s:-]{3,}$")) }
                                .map { previewLine ->
                                    // Filas de datos de la tabla: las líneas verticales se reemplazan por puntos para leerse como un resumen de una línea
                                    if (previewLine.startsWith("|")) {
                                        previewLine
                                            .trim('|')
                                            .split("|")
                                            .map { it.trim() }
                                            .filter { it.isNotEmpty() }
                                            .joinToString(" · ")
                                    } else {
                                        previewLine.removePrefix("#").trim()
                                    }
                                }
                                .filter { it.isNotEmpty() }
                                .take(4)
                                .joinToString("\n")
                        }
                    }
                // v2.0.1: Los fragmentos de código tienen sintaxis resaltada (reutilizando CodeHighlighter)
                val highlightedPreview = if (isCode) {
                    CodeHighlighter.highlight(previewText, artifact.language)
                } else {
                    null
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MuseShapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = highlightedPreview ?: androidx.compose.ui.text.AnnotatedString(previewText),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = if (isCode) MuseMonoFontFamily else null,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (isCode) 6 else 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // línea de mensaje
            Text(
                text = stringResource(R.string.artifact_char_count, charCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** Cuando la lista supera este número,Pantalla trasera "+N" doblar tarjeta(por [ArtifactCardList] uso). */
internal const val MAX_VISIBLE_ARTIFACT_CARDS = 3

/** Tipo de producto + Fuente(lenguaje de código)Redacción combinada,Como por ejemplo "código · Kotlin". */
@Composable
internal fun artifactMetaLabel(type: String, language: String?): String {
    val typeLabel = artifactTypeLabel(type)
    val source = language?.takeIf { it.isNotBlank() } ?: return typeLabel
    return "$typeLabel · $source"
}

/** El nombre para mostrar localizado del tipo de producto.(Los tipos desconocidos vuelven a ser primitivos type,Pantalla de tipo vacío"Otros"). */
@Composable
internal fun artifactTypeLabel(type: String): String = when (type.lowercase()) {
    "code" -> stringResource(R.string.artifact_type_code)
    "image" -> stringResource(R.string.artifact_type_image)
    "html" -> stringResource(R.string.artifact_type_html)
    "svg" -> stringResource(R.string.artifact_type_svg)
    "chart" -> stringResource(R.string.artifact_type_chart)
    "mermaid" -> stringResource(R.string.artifact_type_mermaid)
    "text", "markdown" -> stringResource(R.string.artifact_type_text)
    else -> type.ifBlank { stringResource(R.string.artifact_type_other) }
}

/** más allá [MAX_VISIBLE_ARTIFACT_CARDS] de la cola "+N" tira plegable,Haga clic para expandir todo. */
@Composable
internal fun ArtifactOverflowCard(hiddenCount: Int, onClick: () -> Unit) {
    val description = stringResource(R.string.artifact_more_count, hiddenCount)
    MuseSurface(
        onClick = onClick,
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = description,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun artifactTypeIcon(type: String) = when (type.lowercase()) {
    "code" -> MuseIcons.code
    "image" -> MuseIcons.image
    "html", "svg" -> MuseIcons.languages
    else -> MuseIcons.fileText
}
