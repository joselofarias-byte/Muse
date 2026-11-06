package io.zer0.muse.ui.artifact

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.util.ShareIntentHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v2.0.1: Determinación del idioma de vista previa de contenido enriquecido(svg / html / chart / mermaid) — type Vuelva a verificar con el idioma.
 * El visor y el portal "Modo de navegador de pantalla completa" comparten los mismos criterios.
 */
fun richPreviewLanguage(type: String, language: String?): String? {
    val richTypes = listOf("svg", "html", "chart", "mermaid")
    return when {
        type.lowercase() in richTypes -> type.lowercase()
        language?.lowercase() in richTypes -> language?.lowercase()
        else -> null
    }
}

/** Al guardar como texto,El nombre del archivo oculto cuando el título está vacío después de la limpieza. */
internal const val DEFAULT_ARTIFACT_FILE_NAME = "artifact"

/**
 * Generar SAF a partir del título del producto Guardar nombre del archivo(Los caracteres ilegales se reemplazan con guiones bajos.,Mantener chino, inglés y números.).
 *
 * @param extension extensión punteada,Como por ejemplo ".md";Devolver si no se transmite ".txt"
 */
internal fun artifactExportFileName(title: String, extension: String = ".txt"): String {
    val sanitized = title.trim()
        .map { ch -> if (ch.isLetterOrDigit() || ch in "-_") ch else '_' }
        .joinToString("")
        .trim('_')
        .take(40)
    return "${sanitized.ifBlank { DEFAULT_ARTIFACT_FILE_NAME }}$extension"
}

/**
 * Determine la extensión de exportación según el tipo de producto y el idioma.
 *
 * Tablas con Markdown a .md (GFM Las tablas se pueden representar directamente en la mayoría de los editores),
 * JSON Para .json, el código se mueve según el idioma y el resto se devuelve a .txt.
 */
internal fun artifactExportExtension(type: String, language: String?): String {
    val byType =
        mapOf(
            "table" to ".md",
            "markdown" to ".md",
            "json" to ".json",
            "csv" to ".csv",
            "html" to ".html",
            "svg" to ".svg",
        )
    val byLanguage =
        mapOf(
            "kotlin" to ".kt",
            "python" to ".py",
            "javascript" to ".js",
            "js" to ".js",
            "typescript" to ".ts",
            "ts" to ".ts",
            "java" to ".java",
            "json" to ".json",
            "xml" to ".xml",
            "html" to ".html",
            "sql" to ".sql",
            "bash" to ".sh",
            "sh" to ".sh",
            "shell" to ".sh",
            "yaml" to ".yml",
            "yml" to ".yml",
            "css" to ".css",
        )
    val normalizedType = type.lowercase()
    // Exportación única: primero verifique por tipo, luego verifique por idioma cuando el tipo sea código y regrese si no hay resultado. .txt
    return byType[normalizedType]
        ?: (if (normalizedType == "code") byLanguage[language?.lowercase()] else null)
        ?: ".txt"
}

/** MIME utilizado para exportar.SAF CreateDocument requiere que elija la aplicación predeterminada. */
internal fun artifactExportMime(type: String): String = when (type.lowercase()) {
    "table", "markdown" -> "text/markdown"
    "json" -> "application/json"
    "csv" -> "text/csv"
    "html" -> "text/html"
    "svg" -> "image/svg+xml"
    else -> "text/plain"
}

/**
 * Ventana emergente del visor de productos —— Mostrar título, contenido completo y botón copiar.
 *
 * Phase 2 completo: Se agregaron las acciones "Compartir" y "Guardar como texto" a la línea de título.,
 * Compartir y reutilizar [ShareIntentHelper],Guardar como SAF CreateDocument(app El modo guardar ya existe).
 */
@Composable
fun ArtifactViewerDialog(artifact: ArtifactEntity, onDismiss: () -> Unit, onCopy: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isMonospace = when (artifact.type.lowercase()) {
        "code" -> true
        else -> false
    }
    // v2.0.1: La determinación del contenido enriquecido analiza tanto el tipo como el idioma (los extractores a menudo clasifican HTML como type=code + language=html,
    // La antigua sentencia sólo reconoce el tipo, lo que resulta en "HTML Solo hay una cadena de código cuando se hace clic en el producto", corregido según los comentarios de los usuarios).
    val richLanguage = richPreviewLanguage(artifact.type, artifact.language)
    val isRichContent = richLanguage != null
    // v2.6.10: markdown / document / table / json Utilice el renderizado Markdown.
    // Estos tipos de texto son en sí mismos sintaxis Markdown (tablas, listas, bloques de código),
    // Antes, se insertaba todo el texto y cuando los usuarios hacían clic en el producto, veían las líneas verticales y los asteriscos originales.
    val isMarkdownContent =
        !isRichContent &&
            artifact.type.lowercase() in setOf("markdown", "document", "table", "json", "csv")
    val exportExtension =
        remember(artifact.type, artifact.language) { artifactExportExtension(artifact.type, artifact.language) }
    val exportMime = remember(artifact.type) { artifactExportMime(artifact.type) }
    val exportFileName =
        remember(artifact.title, exportExtension) { artifactExportFileName(artifact.title, exportExtension) }

    // guardar como texto:SAF Selecciona el destino y escribe(Exportar con notificaciones/Copia de seguridad y exportación del mismo modelo.)
    // v2.6.10: MIME por tipo de producto, tabla/Markdown Guardar como .md,JSON guardar .json,
    // ya no es uniforme text/plain —— Ningún editor analizará la tabla guardada de esta manera como una tabla.
    val saveTextLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(exportMime),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(artifact.content.toByteArray(Charsets.UTF_8))
                        } != null
                    }.getOrDefault(false)
                }
                MuseToast.show(
                    context.getString(
                        if (saved) R.string.artifact_saved else R.string.artifact_save_failed,
                    ),
                )
            }
        }
    }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = null,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // línea de título
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = artifactTypeIcon(artifact.type),
                        contentDescription = artifactTypeLabel(artifact.type),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = artifact.title.ifBlank { stringResource(R.string.artifact_untitled) },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // Phase 2: Compartir texto del producto
                    MuseTactileButton(
                        icon = MuseIcons.share,
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_SUBJECT,
                                    artifact.title.ifBlank {
                                        context.getString(R.string.artifact_untitled)
                                    },
                                )
                                putExtra(Intent.EXTRA_TEXT, artifact.content)
                            }
                            ShareIntentHelper.startChooserSafely(
                                context = context,
                                shareIntent = shareIntent,
                                chooserTitle = context.getString(R.string.artifact_share_chooser),
                            )
                        },
                        contentDescription = stringResource(R.string.action_share),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = MuseIconSizes.touchTarget,
                        iconSize = 20.dp,
                    )
                    // Phase 2: Guardar como archivo de texto
                    MuseTactileButton(
                        icon = MuseIcons.deviceFloppy,
                        onClick = { saveTextLauncher.launch(exportFileName) },
                        contentDescription = stringResource(R.string.artifact_save_text),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = MuseIconSizes.touchTarget,
                        iconSize = 20.dp,
                    )
                    MuseTactileButton(
                        icon = MuseIcons.x,
                        onClick = onDismiss,
                        contentDescription = stringResource(R.string.artifact_close),
                        size = MuseIconSizes.touchTarget,
                        iconSize = 20.dp,
                    )
                }
                // área de contenido
                Surface(
                    color = if (isMonospace) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    shape = MuseShapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    if (isRichContent) {
                        // v1.62: svg/html/chart/mermaid Renderizar con RichContentCard
                        // No hay ninguna ventana emergente navController,Deshabilitar el botón de vista previa en pantalla completa(Evite clics no válidos)
                        io.zer0.muse.ui.markdown.RichContentCard(
                            language = richLanguage ?: artifact.type.lowercase(),
                            content = artifact.content,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            showPreviewButton = false,
                        )
                    } else if (isMarkdownContent) {
                        // Markdown Texto: entréguelo al MarkdownText existente en el proyecto.
                        // Admite tablas/listas/bloques de código GFM, y las tablas ya no se degradan a texto vertical.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            io.zer0.muse.ui.markdown.MarkdownText(
                                text = artifact.content,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        // Productos de texto largo: el área de contenido se desplaza sola (Surface Límite de altura: 320 dp),
                        // Para evitar que Surface recorte contenido demasiado largo, el desplazamiento exterior solo puede mover todo el panel y la parte recortada nunca será visible.
                        Text(
                            text = artifact.content,
                            style = if (isMonospace) {
                                MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = MuseMonoFontFamily,
                                )
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.artifact_copy),
        // L6 Limitaciones conocidas: Copia no marcada como confidencial(artifact El contenido puede contener información confidencial.),No hay distinción por ahora;El juicio posterior se puede combinar con la detección de contenido.
        onConfirm = { onCopy(artifact.content) },
        dismissText = null,
    )
}
