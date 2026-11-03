@file:Suppress("FunctionNaming", "LongMethod")

package io.zer0.muse.ui

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.zer0.muse.R
import io.zer0.muse.crash.MuseCrashHandler
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.util.ShareIntentHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// P1-4:Panel de registro de fallos — Coloque el registro de fallos que MuseCrashHandler ya tiene pero que solo usa SafeMode
//       Lista/empaquetado ZIP/capacidad de uso compartido único,También expuesto al usuario en modo normal.
//
//  fuente de datos:MuseCrashHandler.listCrashLogs(context) → List<File>
//  paquete  :MuseCrashHandler.packageCrashLogsToZip(context) → File?(cacheDir/zip)
//  Compartir  :FileProvider + ACTION_SEND(Vaya a lo que se ha declarado en file_paths.xml files-path / cache-path)
// ════════════════════════════════════════════════════════════════════════════

/**
 * Panel inferior del registro de accidentes.
 *
 * comportamiento:
 *  - Tirón asíncrono al abrir [MuseCrashHandler.listCrashLogs],Mostrar en orden descendente por mtime
 *  - Se puede hacer clic en cada registro de fallos para expandirlo.,Antes de la vista previa en línea [CRASH_PREVIEW_CHARS] personaje
 *  - arriba"Exportar ZIP y compartir"llamada de botón [MuseCrashHandler.packageCrashLogsToZip]
 *    Empaquetar todos los registros de fallos + información del dispositivo,Compartir mediante ACTION_SEND
 *  - Lado derecho de cada tronco"Compartir"botón para compartir el archivo .txt individualmente
 *
 * descripción del diseño:SafeModeScreen existente shareCrashLog,Aquí se reutiliza la misma habilidad en modo normal.
 */
@Composable
internal fun CrashLogSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current

    // ── Estado de los datos ─────────────────────────────────────────────────────────
    var logs by remember { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // El nombre del archivo de registro de fallos actualmente expandido.(null = Contraer todo)
    var expandedFile by remember { mutableStateOf<String?>(null) }
    // Contenido del archivo cargado de forma diferida después de la expansión(Evite leer todos los registros en la memoria a la vez)
    var expandedContent by remember { mutableStateOf<String?>(null) }

    // Extraiga la lista de registro de fallos una vez al abrir(Leer archivosDir es IO,poner Dispatchers.IO)
    LaunchedEffect(Unit) {
        logs = withContext(Dispatchers.IO) { MuseCrashHandler.listCrashLogs(context) }
        loading = false
    }

    // expandedFile Al cambiar,Leer el contenido del archivo correspondiente de forma asincrónica(Truncar para obtener una vista previa de la longitud,Evite archivos grandes OOM)
    LaunchedEffect(expandedFile) {
        val name = expandedFile
        if (name == null) {
            expandedContent = null
        } else {
            val file = logs.firstOrNull { it.name == name }
            expandedContent = if (file != null) {
                withContext(Dispatchers.IO) {
                    runCatching { file.readText().take(CRASH_PREVIEW_CHARS) }.getOrNull()
                }
            } else {
                null
            }
        }
    }

    MuseBottomSheet(onDismissRequest = onDismiss) {
        // ── Fila de título + botón de exportación ZIP ────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.debug_crash_log_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                // LAYOUT-01: Row Ancho del texto interno + límite de línea, evita el ajuste de línea palabra por palabra al ampliar el tamaño de fuente
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // ZIP Empaquetar y compartir:Tome la ruta zip incluso si solo hay 1 registro de fallos,cinturón unificado device_info
            MuseCapsuleButton(
                text = stringResource(R.string.debug_export_zip_and_share),
                onClick = { shareCrashZip(context) },
                leadingIcon = MuseIcons.share,
                fillWidth = false,
            )
        }

        Spacer(Modifier.height(MusePaddings.contentGap))

        // ── contar + rama estatal ──────────────────────────────────────────────
        when {
            loading -> {
                Text(
                    text = stringResource(R.string.debug_loading_crash_logs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = MusePaddings.contentGap),
                )
            }
            logs.isEmpty() -> {
                Text(
                    text = stringResource(R.string.debug_no_crash_logs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = MusePaddings.contentGap),
                )
            }
            else -> {
                Text(
                    text = stringResource(R.string.debug_crash_log_count, logs.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(MusePaddings.tightGap))
                // Limitar la altura máxima,Evite listas demasiado largas BottomSheet
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    items(
                        items = logs,
                        key = { it.name },
                    ) { file ->
                        CrashLogItem(
                            file = file,
                            expanded = expandedFile == file.name,
                            expandedContent = if (expandedFile == file.name) expandedContent else null,
                            onToggleExpand = {
                                expandedFile = if (expandedFile == file.name) null else file.name
                            },
                            onShare = { shareCrashFile(context, file) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Una única entrada del registro de fallos.
 *
 * Cuando está plegado:Nombre del archivo + tamaño + hora de modificación + botón compartir
 * Cuando se expande:Agregar vista previa en línea debajo de la información contraída(fuente monoespaciada,Se puede desplazar verticalmente)
 */
@Composable
private fun CrashLogItem(file: File, expanded: Boolean, expandedContent: String?, onToggleExpand: () -> Unit, onShare: () -> Unit) {
    // El nombre del archivo tiene el formato crash-20260721-153012.txt;Mostrar el nombre del archivo original directamente,Manténgalo consistente con el disco para una fácil solución de problemas
    val sizeStr = remember(file.length()) { DebugFormatters.formatFileSize(file.length()) }
    val timeStr = remember(file.lastModified()) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(file.lastModified()))
    }

    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand),
    ) {
        Column(modifier = Modifier.padding(horizontal = MusePaddings.itemGap, vertical = MusePaddings.auxGap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = MuseIcons.fileText,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = MuseMonoFontFamily,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "$timeStr · $sizeStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MuseTactileButton(
                    icon = MuseIcons.share,
                    onClick = onShare,
                    contentDescription = stringResource(R.string.debug_cd_share_crash_log),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Después de desplegar:Vista previa en línea(Fuente monoespaciada + desplazamiento vertical)
            AnimatedVisibility(
                visible = expanded,
                enter = MuseMotion.expandFadeEnter(),
                exit = MuseMotion.expandFadeExit(),
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    val preview = expandedContent
                    Surface(
                        shape = MuseShapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = preview ?: stringResource(R.string.debug_loading),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(MusePaddings.contentGap)
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                    if (preview != null && preview.length >= CRASH_PREVIEW_CHARS) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.debug_crash_preview_truncated, CRASH_PREVIEW_CHARS),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Empaqueta todos los registros de fallos en un ZIP y compártelos mediante ACTION_SEND.
 *
 * ZIP Contenido proporcionado por [MuseCrashHandler.packageCrashLogsToZip] decidir:device_info.txt + cada uno crash-*.txt.
 * El archivo se encuentra en cacheDir(Expuesto a través de la ruta de caché en file_paths.xml FileProvider).
 */
private fun shareCrashZip(context: Context) {
    val zipFile = MuseCrashHandler.packageCrashLogsToZip(context)
    if (zipFile == null) {
        MuseToast.show(context.getString(R.string.debug_export_failed_no_crash_logs))
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            zipFile,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "muse crash logs — ${zipFile.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ShareIntentHelper.startChooserSafely(
            context,
            intent,
            context.getString(R.string.debug_share_crash_zip_chooser),
        )
    }.onFailure {
        MuseToast.show(context.getString(R.string.debug_share_failed_no_uri))
    }
}

/**
 * Compartir un único archivo de registro de fallos(.txt).
 *
 * El archivo se encuentra en filesDir/crash/(Expuesto a través de la ruta de archivos en file_paths.xml FileProvider).
 */
private fun shareCrashFile(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "muse crash log — ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ShareIntentHelper.startChooserSafely(
            context,
            intent,
            context.getString(R.string.debug_share_crash_log_chooser),
        )
    }.onFailure {
        MuseToast.show(context.getString(R.string.debug_share_failed_no_uri))
    }
}

/** Número máximo de caracteres para la vista previa en línea del registro de fallos,Evite leer una pila muy grande en estado Redactar a la vez. */
private const val CRASH_PREVIEW_CHARS = 2000
