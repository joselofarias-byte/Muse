package io.zer0.muse.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.zer0.common.AppJson
import io.zer0.muse.R
import io.zer0.muse.data.audit.AuditLogEntity
import io.zer0.muse.data.audit.AuditLogger
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.util.ShareIntentHelper
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.compose.koinInject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P2-4: Página de registro de auditoría — Mostrar registros de operaciones clave dentro de la aplicación,Y admite filtrado de categorías/búsqueda de palabras clave/exportación JSON.
 *
 * estructura:
 *  - arriba MuseTopBar(Volver + Título + Exportar JSON + Botón Borrar)
 *  - área de filtro:categoría FlowRow Chips(Todo/Llamadas API/Operaciones de usuario/Autenticación/Sistema)+ cuadro de búsqueda de palabras clave
 *  - El LazyColumn del medio muestra las entradas del registro.(Cargando página,100 artículos por página,Filtrado de clientes)
 *  - por entrada:Marca de tiempo(yyyy-MM-dd HH:mm:ss)+ Icono de categoría + action + target + success/fail marca
 *  - Mantenga presionado para mostrar MuseDialog en su totalidad detail JSON
 *  - No se muestran registros en absoluto"Aún no hay registros de registro";Hay registros pero no se muestran resultados después del filtrado."No hay registros coincidentes"
 *
 * fuente de datos:[AuditLogger.query] Consulta de página,Primera vez cargando la página 1,
 * Desplácese hasta el final para cargar automáticamente la página siguiente. El filtrado de categorías y palabras clave se completa en el lado del cliente.,
 * Por lo tanto, los resultados visibles después del filtrado están limitados por la cantidad cargada.,Los usuarios pueden desplazarse hasta la parte inferior para continuar cargando más datos sin procesar.
 *
 * @param onBack devolución de llamada(Inyectado por NavHost)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuditLogPage(onBack: () -> Unit) {
    val auditLogger: AuditLogger = koinInject()
    val scope = rememberCoroutineScope()
    val widthClass = rememberWindowWidthClass()
    val context = LocalContext.current

    var logs by remember { mutableStateOf<List<AuditLogEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf(0) }
    // Ventana emergente de detalles:Mostrar la entrada de registro completa al presionar prolongadamente detail JSON
    var detailDialog by remember { mutableStateOf<AuditLogEntity?>(null) }
    // Borrar ventana emergente de confirmación
    var showClearDialog by remember { mutableStateOf(false) }
    // P2-4 mejorar:Filtrado de categorías + búsqueda de palabras clave
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val pageSize = 100
    val dateFormat = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL_SEC, Locale.getDefault()) }

    // Primera vez cargando la página 1
    LaunchedEffect(Unit) {
        isLoading = true
        val first = auditLogger.query(limit = pageSize, offset = 0)
        logs = first
        hasMore = first.size == pageSize
        page = 1
        isLoading = false
    }

    fun loadMore() {
        if (isLoading || !hasMore) return
        scope.launch {
            isLoading = true
            val next = auditLogger.query(limit = pageSize, offset = page * pageSize)
            logs = logs + next
            hasMore = next.size == pageSize
            if (hasMore) page += 1
            isLoading = false
        }
    }

    // Filtrado de clientes:Categoría + Palabras clave(ignorar caso,partido action / target / detail)
    val filteredLogs = remember(logs, selectedCategory, searchQuery) {
        val cat = selectedCategory
        val q = searchQuery.trim().lowercase(Locale.getDefault())
        if (cat == null && q.isEmpty()) {
            logs
        } else {
            logs.filter { log ->
                (cat == null || log.category == cat) &&
                    (
                        q.isEmpty() ||
                            log.action.lowercase(Locale.getDefault()).contains(q) ||
                            log.target.lowercase(Locale.getDefault()).contains(q) ||
                            log.detail.lowercase(Locale.getDefault()).contains(q)
                        )
            }
        }
    }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.settings_audit_log),
                onBack = onBack,
                largeTitle = true,
                actions = {
                    // Exportar JSON(Serializa el registro filtrado actual en un archivo JSON y compártelo mediante ACTION_SEND)
                    MuseTactileButton(
                        icon = MuseIcons.share,
                        onClick = { shareAuditLogsAsJson(context, filteredLogs) },
                        contentDescription = stringResource(R.string.audit_log_export),
                    )
                    MuseTactileButton(
                        icon = MuseIcons.trash,
                        onClick = { showClearDialog = true },
                        contentDescription = stringResource(R.string.audit_log_clear),
                    )
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (logs.isEmpty() && !isLoading) {
                // No hay registros en absoluto:Estado vacío de pantalla completa(No mostrar el área de filtro)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding()),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.audit_log_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (widthClass == WindowWidthClass.Expanded) {
                                Modifier.widthIn(max = 720.dp)
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = MusePaddings.screen),
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        bottom = innerPadding.calculateBottomPadding() + MusePaddings.screen,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                ) {
                    // área de filtro:Fichas de categoría + cuadro de búsqueda de palabras clave
                    item(key = "filter_header") {
                        FilterHeader(
                            selectedCategory = selectedCategory,
                            onCategorySelected = { selectedCategory = it },
                            searchQuery = searchQuery,
                            onSearchQueryChanged = { searchQuery = it },
                        )
                    }
                    // recuento de resultados:Mostrar solo si hay filtros"Mostrando X elementos/total de Y elementos"
                    if (selectedCategory != null || searchQuery.isNotBlank()) {
                        item(key = "result_count") {
                            Text(
                                text = stringResource(
                                    R.string.audit_log_result_count,
                                    filteredLogs.size,
                                    logs.size,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = MusePaddings.tightGap),
                            )
                        }
                    }
                    if (filteredLogs.isEmpty()) {
                        // Hay registros originales pero no hay coincidencias después del filtrado.
                        item(key = "empty_filtered") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = MusePaddings.contentGap * 3),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.audit_log_no_match),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        items(filteredLogs, key = { it.id }) { log ->
                            AuditLogRow(
                                log = log,
                                dateFormat = dateFormat,
                                onLongClick = { detailDialog = log },
                            )
                        }
                    }
                    // Cargador de páginas:Se muestra cada vez que hay más datos sin procesar.,El usuario se desplaza hasta la parte inferior para activar la carga de la página siguiente.
                    if (hasMore) {
                        item(key = "footer_loader") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = MusePaddings.contentGap),
                                contentAlignment = Alignment.Center,
                            ) {
                                MuseSpinner(
                                    size = MuseIconSizes.iconMedium,
                                )
                            }
                            LaunchedEffect(Unit) { loadMore() }
                        }
                    }
                }
            }
        }
    }

    // Ventana emergente de detalles:Mostrar completo detail JSON
    detailDialog?.let { log ->
        MuseDialog(
            onDismissRequest = { detailDialog = null },
            title = formatDialogTitle(log, dateFormat),
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start,
                ) {
                    InfoLine(label = "Action", value = log.action)
                    if (log.target.isNotBlank()) {
                        InfoLine(label = "Target", value = log.target)
                    }
                    InfoLine(label = "Success", value = if (log.success) "true" else "false")
                    if (log.detail.isNotBlank() && log.detail != "{}") {
                        Spacer(Modifier.height(MusePaddings.contentGap))
                        Text(
                            text = "Detail",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(MusePaddings.tightGap))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MuseShapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ) {
                            Text(
                                text = prettyJson(log.detail),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(MusePaddings.contentGap),
                            )
                        }
                    }
                }
            },
            confirmText = stringResource(R.string.common_confirm),
            onConfirm = { detailDialog = null },
            dismissText = null,
        )
    }

    // Borrar ventana emergente de confirmación
    if (showClearDialog) {
        MuseDialog(
            onDismissRequest = { showClearDialog = false },
            title = stringResource(R.string.audit_log_clear),
            content = {
                Text(
                    text = stringResource(R.string.audit_log_clear_confirm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            },
            confirmText = stringResource(R.string.audit_log_clear),
            onConfirm = {
                scope.launch {
                    auditLogger.clearAll()
                    logs = emptyList()
                    hasMore = false
                    page = 0
                    selectedCategory = null
                    searchQuery = ""
                }
                showClearDialog = false
            },
            destructive = true,
        )
    }
}

/**
 * área de filtro:Chips FlowRow de categoría + cuadro de búsqueda de palabras clave.
 *
 * Chips:Todo/Llamadas API/Operaciones de usuario/Autenticación/Sistema,Radio mutuamente excluyentes,Haga clic en el mismo elemento nuevamente para anular su selección.
 * cuadro de búsqueda:prensa en tiempo real action / target / detail coincidencia difusa(ignorar caso).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterHeader(
    selectedCategory: String?,
    onCategorySelected: (String?) -> Unit,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
) {
    val categories = remember {
        listOf(
            null to R.string.audit_log_category_all,
            "api_call" to R.string.audit_log_category_api,
            "user_action" to R.string.audit_log_category_user,
            "auth" to R.string.audit_log_category_auth,
            "system" to R.string.audit_log_category_system,
            // F-08: Agent recibo corriente(Registro de máquina de estado de ejecución de herramienta)
            "agent_run" to R.string.audit_log_category_agent,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.tightGap),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            categories.forEach { (cat, labelRes) ->
                MuseChip(
                    selected = selectedCategory == cat,
                    onClick = {
                        onCategorySelected(if (selectedCategory == cat) null else cat)
                    },
                    label = stringResource(labelRes),
                )
            }
        }
        MuseTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = stringResource(R.string.audit_log_search_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = MuseIcons.search,
                    contentDescription = null,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
            },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                {
                    MuseTactileButton(
                        icon = MuseIcons.x,
                        onClick = { onSearchQueryChanged("") },
                        contentDescription = stringResource(R.string.audit_log_clear_search),
                        iconSize = MuseIconSizes.iconSmall,
                    )
                }
            } else {
                null
            },
            singleLine = true,
        )
    }
}

/**
 * Vista de filas de un único registro de auditoría.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AuditLogRow(log: AuditLogEntity, dateFormat: SimpleDateFormat, onLongClick: () -> Unit) {
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icono de categoría(Fondo redondo + iconos diferentes)
            CategoryIcon(category = log.category)
            Spacer(Modifier.size(MusePaddings.contentGap))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = log.action,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.size(MusePaddings.contentGap))
                    // success / fail marca(Expresar usando iconos de materiales,seguir proyecto"prohibido emoji"Acuerdo)
                    val statusColor = if (log.success) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                    val statusIcon = if (log.success) {
                        MuseIcons.check
                    } else {
                        MuseIcons.x
                    }
                    Surface(
                        shape = CircleShape,
                        color = statusColor.copy(alpha = 0.15f),
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(MuseIconSizes.iconTiny),
                            )
                        }
                    }
                }
                Spacer(Modifier.size(MusePaddings.tightGap))
                // Marca de tiempo
                Text(
                    text = dateFormat.format(Date(log.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // target(Si es así)
                if (log.target.isNotBlank()) {
                    Text(
                        text = "→ ${log.target}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Icono de categoría — prensa [category] Muestra diferentes iconos y colores de fondo.
 *
 *  - api_call: Api icono + primary
 *  - user_action: Person icono + secondary
 *  - auth: Lock icono + tertiary
 *  - system: Settings icono + surfaceVariant
 *  - Otros: Icono de configuración predeterminada
 */
@Composable
private fun CategoryIcon(category: String) {
    val (icon, tint) = when (category) {
        "api_call" -> MuseIcons.code to MaterialTheme.colorScheme.onSurfaceVariant
        "user_action" -> MuseIcons.user to MaterialTheme.colorScheme.onSurfaceVariant
        "auth" -> MuseIcons.lock to MaterialTheme.colorScheme.onSurfaceVariant
        "system" -> MuseIcons.computer to MaterialTheme.colorScheme.onSurfaceVariant
        else -> MuseIcons.computer to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = CircleShape,
        color = tint.copy(alpha = 0.15f),
        modifier = Modifier.size(MuseIconSizes.iconLarge),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
        }
    }
}

/** Filas de valores-clave en la ventana emergente de detalles. */
@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.tightGap),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // LAYOUT-01: La clave es una palabra corta fija.,Límite de 1 línea;El valor puede ser muy largo. target(Nombre de la herramienta/camino/ID),
        // Tome el ancho restante y limítelo a 3 líneas,Preservar la legibilidad de varias líneas,No se comprimirá en una columna cuando se amplíe el tamaño de fuente.
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Título de la ventana emergente de detalles(tiempo + action). */
private fun formatDialogTitle(log: AuditLogEntity, dateFormat: SimpleDateFormat): String {
    return dateFormat.format(Date(log.timestamp))
}

/**
 * Formatee una cadena JSON compacta en un formato legible por humanos con sangría.
 *
 * Análisis utilizando una máquina de estados minimalista.,Evite introducir dependencias adicionales de la biblioteca JSON. Devuelve sin cambios al ingresar JSON ilegal.
 */
private fun prettyJson(json: String): String {
    if (json.isBlank()) return ""
    val builder = StringBuilder(json.length * 2)
    var indent = 0
    var inString = false
    var escaped = false
    for (ch in json) {
        if (inString) {
            builder.append(ch)
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '"') {
                inString = false
            }
            continue
        }
        when (ch) {
            '"' -> {
                inString = true
                builder.append(ch)
            }
            '{', '[' -> {
                builder.append(ch)
                indent++
                builder.append('\n').append("  ".repeat(indent))
            }
            '}', ']' -> {
                indent = (indent - 1).coerceAtLeast(0)
                builder.append('\n').append("  ".repeat(indent)).append(ch)
            }
            ',' -> {
                builder.append(ch)
                builder.append('\n').append("  ".repeat(indent))
            }
            ':' -> {
                builder.append(": ")
            }
            ' ', '\t', '\n', '\r' -> {
                // Saltar el espacio en blanco original,Reemplazar con nuestro formato
            }
            else -> {
                builder.append(ch)
            }
        }
    }
    return builder.toString()
}

/**
 * Exporte la lista de registros filtrados a un archivo JSON y compártala mediante ACTION_SEND.
 *
 * JSON estructura:`[{ id, timestamp, iso_time, category, action, target, detail, success }, ...]`
 * El archivo se encuentra en cacheDir(Expuesto a través de la ruta de caché en file_paths.xml FileProvider).
 */
private fun shareAuditLogsAsJson(context: android.content.Context, logs: List<AuditLogEntity>) {
    if (logs.isEmpty()) {
        MuseToast.show(context.getString(R.string.audit_log_export_empty))
        return
    }
    val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
    val jsonArray: JsonArray = buildJsonArray {
        for (log in logs) {
            add(
                buildJsonObject {
                    put("id", log.id)
                    put("timestamp", log.timestamp)
                    put("iso_time", isoFormat.format(Date(log.timestamp)))
                    put("category", log.category)
                    put("action", log.action)
                    put("target", log.target)
                    put("detail", log.detail)
                    put("success", log.success)
                },
            )
        }
    }
    val compactJson = AppJson.encodeToString(JsonArray.serializer(), jsonArray)
    val prettyJsonString = prettyJson(compactJson)

    runCatching {
        val exportFile = File(
            context.cacheDir,
            "audit_logs_export_${System.currentTimeMillis()}.json",
        )
        exportFile.writeText(prettyJsonString)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            exportFile,
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(
                android.content.Intent.EXTRA_SUBJECT,
                context.getString(R.string.audit_log_export_subject, logs.size),
            )
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ShareIntentHelper.startChooserSafely(
            context,
            intent,
            context.getString(R.string.audit_log_export_chooser),
        )
    }.onFailure {
        MuseToast.show(context.getString(R.string.audit_log_export_failed))
    }
}
