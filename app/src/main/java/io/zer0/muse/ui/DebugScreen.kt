package io.zer0.muse.ui

// ST-01 → ST-09: Descripción de la cobertura del estado de error(última ronda"Esta página no requiere un estado de error."La conclusión es demasiado estrecha.,Completado).
//  - Lista de registros:fuente de datos DebugLogStore.getAll() Para lectura pura de memoria(buffer.toList()),Sondear ningún paso de DB/red fallido,
//    Por lo tanto, la lista en sí no tiene ramas de carga/error.;
//  - Panel de integridad de la base de datos(DbIntegritySheet):fuente de datos IntegrityChecker.getLatestStatus() Consulta por habitación,
//    Lanzará una excepción → El panel unifica MuseLoadingState + MuseErrorStateBox(Reintentar si falla);
//  - Exportación fallida:DebugLogStore.exportToFile() Cuando se devuelve nulo toast(debug_export_failed_no_logs),
//    FileProvider.getUriForFile El fracaso también toast(debug_export_failed_no_uri).

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.zer0.muse.R
import io.zer0.muse.data.stats.DbIntegrityLogEntity
import io.zer0.muse.data.stats.IntegrityChecker
import io.zer0.muse.debug.DebugLogEntry
import io.zer0.muse.debug.DebugLogStore
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseDropdown
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.common.state.MuseLoadingState
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseStatusColors
import io.zer0.muse.ui.theme.statusColors
import io.zer0.muse.ui.theme.tiny
import io.zer0.muse.util.ShareIntentHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Página de registro de depuración — Admite visualización, filtrado y exportación.
 *
 * Función:
 *  - Título superior de MuseTopBar"registro de depuración" + Botón Atrás + área de operación(pausa/Continuar, exportar, borrar)
 *  - fila de filtro:nivel desplegable(ALL/ERROR/WARN/INFO/DEBUG) + Tag Búsqueda + búsqueda de palabras clave
 *  - Registro de visualización del cuerpo principal LazyColumn,Cada mensaje se puede ampliar para ver el mensaje completo. + stack trace
 *  - Desplazarse automáticamente hacia abajo(Último registro);Si el usuario está en la parte inferior cuando llega un nuevo registro, el usuario continuará siguiéndolo.,
 *    De lo contrario, deténgase en la posición original.;El botón de pausa congela el desplazamiento
 *
 * fuente de datos:[DebugLogStore.getAll] Encuesta cada 500 ms. Iniciar sesión por [Logger.sink] empujar.
 *
 * @param onBack devolución de llamada(Inyectado por NavHost)
 */
@Composable
fun DebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // ── estado del filtro ───────────────────────────────────────────────────────
    // Filtrado de niveles:ALL Indica que no hay filtrado;Los valores restantes están alineados con DebugLogStore.LEVELS
    var levelFilter by remember { mutableStateOf("ALL") }
    var tagQuery by remember { mutableStateOf("") }
    var keywordQuery by remember { mutableStateOf("") }

    // ── estado de desplazamiento ───────────────────────────────────────────────────────
    // paused:El usuario pausa activamente el seguimiento;atBottom:Si el usuario se encuentra actualmente en la parte inferior(Se utiliza para determinar si se debe seguir automáticamente)
    var paused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            info.index >= listState.layoutInfo.totalItemsCount - 1
        }
    }

    // ── Datos de registro(sondeo DebugLogStore) ──────────────────────────────────
    var allLogs by remember { mutableStateOf<List<DebugLogEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        // Tira de él inmediatamente por primera vez.,Evite el parpadeo de los espacios en blanco
        allLogs = DebugLogStore.getAll()
        // Encuesta cada 500 ms a partir de entonces;Logger es el camino de alta frecuencia,500ms Logre un equilibrio entre experiencia y rendimiento
        while (true) {
            delay(500)
            allLogs = DebugLogStore.getAll()
        }
    }

    // Aplicar filtro(Hazlo en el lado Composable,Evite que DebugLogStore mantenga el estado de filtrado)
    val filteredLogs by remember(levelFilter, tagQuery, keywordQuery, allLogs) {
        derivedStateOf {
            val tagTrim = tagQuery.trim()
            val kwTrim = keywordQuery.trim()
            allLogs.asSequence()
                .filter { levelFilter == "ALL" || it.level == levelFilter }
                .filter { tagTrim.isEmpty() || it.tag.contains(tagTrim, ignoreCase = true) }
                .filter {
                    kwTrim.isEmpty() ||
                        it.message.contains(kwTrim, ignoreCase = true) ||
                        (it.throwable?.contains(kwTrim, ignoreCase = true) == true)
                }
                .toList()
        }
    }

    // ── Lógica de desplazamiento automático ───────────────────────────────────────────────────
    // filteredLogs Al cambiar:Si no está en pausa y el usuario está en la parte inferior,Desplazarse hasta el último elemento
    LaunchedEffect(filteredLogs.size) {
        if (!paused && atBottom && filteredLogs.isNotEmpty()) {
            // Utilice scrollToItem en su lugar animateScrollToItem,La animación se congelará cuando lleguen nuevos registros con alta frecuencia.
            listState.scrollToItem(filteredLogs.lastIndex)
        }
    }
    // entrada por primera vez:Desplácese hasta la parte inferior para mostrar los registros más recientes
    LaunchedEffect(Unit) {
        if (allLogs.isNotEmpty()) {
            listState.scrollToItem(allLogs.lastIndex)
        }
    }
    // Monitorear los cambios en la parte inferior:Reanudar automáticamente el seguimiento cuando el usuario se desplaza manualmente hasta el final(Lo mismo que cancelar la suspensión.)
    LaunchedEffect(Unit) {
        snapshotFlow { atBottom }.collect { bottom ->
            if (bottom && paused) paused = false
        }
    }

    // ── Borrar ventana emergente de confirmación ───────────────────────────────────────────────────
    var showClearDialog by remember { mutableStateOf(false) }
    if (showClearDialog) {
        MuseDialog(
            onDismissRequest = { showClearDialog = false },
            title = stringResource(R.string.debug_clear_logs_title),
            content = { Text(stringResource(R.string.debug_clear_logs_confirm, DebugLogStore.size())) },
            confirmText = stringResource(R.string.debug_action_clear),
            destructive = true, // ST-02: Borrar el registro es una operación irreversible.,Botón de confirmación en color rojo de error
            onConfirm = {
                DebugLogStore.clear()
                allLogs = emptyList()
                showClearDialog = false
                MuseToast.show(context.getString(R.string.debug_cleared_toast))
            },
            onDismiss = { showClearDialog = false },
        )
    }

    // ── estado expandido:Registre el actualmente expandido entry(Utilice marca de tiempo+etiqueta+mensaje como key,Simple y aproximadamente único.) ──
    var expandedKey by remember { mutableStateOf<String?>(null) }

    // ── Panel de registro de fallos(P1-4:Coloque la lista de registro de fallos existente pero no basada en la interfaz de usuario de MuseCrashHandler/Capacidad de exportación revelada)
    //  - entrada:MuseTopBar Icono de historial activado
    //  - Listado en el panel listCrashLogs() Todos los archivos de registro de fallos,Se puede ampliar para ver contenido, compartir elementos individuales y compartir paquetes ZIP con un solo clic.
    var showCrashSheet by remember { mutableStateOf(false) }
    if (showCrashSheet) {
        CrashLogSheet(onDismiss = { showCrashSheet = false })
    }

    // ── Panel de análisis de datos locales(P3-2:Exponer los indicadores recopilados por LocalAnalyticsTracker pero no basados en UI)
    //  - entrada:MuseTopBar Icono de análisis activado
    //  - Mostrar DAU/MAU/sesiones totales en el panel/noticias generales/Número de salidas/Tasa de accidentes + retención D1/D7/D30 + uso de funciones Top 10
    var showAnalyticsSheet by remember { mutableStateOf(false) }
    if (showAnalyticsSheet) {
        AnalyticsSheet(onDismiss = { showAnalyticsSheet = false })
    }

    // ── Panel de integridad de la base de datos(P3-3:Exponer los resultados de la verificación de integridad existente de IntegrityChecker pero no basados en la interfaz de usuario)
    //  - entrada:MuseTopBar Icono de salud y seguridad encendido
    //  - Los últimos resultados de la verificación de integridad se muestran en el panel.(Estado/tamaño de base de datos/hora)+ "Compruébalo ahora"botón
    var showDbIntegritySheet by remember { mutableStateOf(false) }
    if (showDbIntegritySheet) {
        DbIntegritySheet(onDismiss = { showDbIntegritySheet = false })
    }

    ScaffoldLayout(
        levelFilter = levelFilter,
        onLevelChange = { levelFilter = it },
        tagQuery = tagQuery,
        onTagChange = { tagQuery = it },
        keywordQuery = keywordQuery,
        onKeywordChange = { keywordQuery = it },
        paused = paused,
        onTogglePause = { paused = !paused },
        onClear = { showClearDialog = true },
        onShowCrashLogs = { showCrashSheet = true },
        onShowAnalytics = { showAnalyticsSheet = true },
        onShowDbIntegrity = { showDbIntegritySheet = true },
        // v1.0.4 (P3-7): Copie el registro filtrado actual al portapapeles con un clic(texto plano),Conveniente para que los usuarios de pruebas internas peguen comentarios
        onCopy = {
            if (filteredLogs.isEmpty()) {
                MuseToast.show(context.getString(R.string.debug_no_logs_to_copy))
            } else {
                val text = buildString {
                    filteredLogs.forEach { entry ->
                        append(entry.formatLine())
                        append('\n')
                    }
                }
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("Muse Debug Log", text),
                )
                MuseToast.show(context.getString(R.string.debug_logs_copied_toast, filteredLogs.size))
            }
        },
        onExport = {
            val file = DebugLogStore.exportToFile()
            if (file == null) {
                MuseToast.show(context.getString(R.string.debug_export_failed_no_logs))
            } else {
                val uri = runCatching {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )
                }.getOrNull()
                if (uri == null) {
                    MuseToast.show(context.getString(R.string.debug_export_failed_no_uri))
                } else {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ShareIntentHelper.startChooserSafely(
                        context,
                        intent,
                        context.getString(R.string.debug_share_log_chooser),
                    )
                }
            }
        },
        logs = filteredLogs,
        listState = listState,
        expandedKey = expandedKey,
        onToggleExpand = { key -> expandedKey = if (expandedKey == key) null else key },
        onBack = onBack,
    )
}

/**
 * Scaffold embalaje — Separar estado del Composable principal,Hacer que el diseño sea previsualizable/Comprobable.
 *
 * Privado por separado porque no es necesario llamarlo directamente desde el exterior.
 */
@Composable
private fun ScaffoldLayout(
    levelFilter: String,
    onLevelChange: (String) -> Unit,
    tagQuery: String,
    onTagChange: (String) -> Unit,
    keywordQuery: String,
    onKeywordChange: (String) -> Unit,
    paused: Boolean,
    onTogglePause: () -> Unit,
    onClear: () -> Unit,
    onShowCrashLogs: () -> Unit,
    onShowAnalytics: () -> Unit,
    onShowDbIntegrity: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    logs: List<DebugLogEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    expandedKey: String?,
    onToggleExpand: (String) -> Unit,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            MuseTopBar(
                title = stringResource(R.string.debug_screen_title),
                onBack = onBack,
                actions = {
                    // pausa/botón continuar siguiendo
                    MuseTactileButton(
                        icon = if (paused) MuseIcons.play else MuseIcons.pause,
                        onClick = onTogglePause,
                        contentDescription = if (paused) {
                            stringResource(R.string.debug_cd_resume_follow)
                        } else {
                            stringResource(R.string.debug_cd_pause_follow)
                        },
                        tint = if (paused) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    // Entrada del registro de fallos(P1-4):Mostrar la lista de registros de fallos de MuseCrashHandler que se ha descargado + compartir ZIP de empaquetado con un solo clic
                    MuseTactileButton(
                        icon = MuseIcons.history,
                        onClick = onShowCrashLogs,
                        contentDescription = stringResource(R.string.debug_cd_crash_logs),
                    )
                    // Portal de análisis de datos locales(P3-2):Mostrar el DAU/MAU/retención recopilado por LocalAnalyticsTracker/Uso de funciones
                    MuseTactileButton(
                        icon = MuseIcons.chartLine,
                        onClick = onShowAnalytics,
                        contentDescription = stringResource(R.string.debug_cd_analytics),
                    )
                    // Portal de integridad de la base de datos(P3-3):Mostrar los últimos resultados de PRAGMA IntegrityChecker IntegrityChecker
                    MuseTactileButton(
                        icon = MuseIcons.shieldCheck,
                        onClick = onShowDbIntegrity,
                        contentDescription = stringResource(R.string.debug_cd_db_integrity),
                    )
                    // v1.0.4 (P3-7): botón copiar — Copie el registro filtrado actual al portapapeles(texto plano)
                    MuseTactileButton(
                        icon = MuseIcons.copy,
                        onClick = onCopy,
                        contentDescription = stringResource(R.string.debug_cd_copy),
                    )
                    // botón exportar
                    MuseTactileButton(
                        icon = MuseIcons.share,
                        onClick = onExport,
                        contentDescription = stringResource(R.string.debug_cd_export),
                    )
                    // boton borrar
                    MuseTactileButton(
                        icon = MuseIcons.trash,
                        onClick = onClear,
                        contentDescription = stringResource(R.string.debug_cd_clear),
                    )
                },
            )

            // Espaciado entre la barra superior y la fila de filtros
            Spacer(Modifier.height(MusePaddings.contentGap))

            // ── fila de filtro ───────────────────────────────────────────────
            FilterRow(
                levelFilter = levelFilter,
                onLevelChange = onLevelChange,
                tagQuery = tagQuery,
                onTagChange = onTagChange,
                keywordQuery = keywordQuery,
                onKeywordChange = onKeywordChange,
            )

            // Espaciado entre la fila del filtro y el recuento de registros
            Spacer(Modifier.height(MusePaddings.contentGap))

            // ── Recuento de registros ───────────────────────────────────────────────
            Text(
                text = stringResource(R.string.debug_log_count, logs.size) +
                    if (paused) stringResource(R.string.debug_log_count_paused_suffix) else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = MusePaddings.screen),
            )

            // Espaciado entre el recuento y la lista de registros(al menos 8.dp,Evite ajustar LazyColumn demasiado apretado)
            Spacer(Modifier.height(MusePaddings.contentGap))

            // ── Lista de registros ───────────────────────────────────────────────
            if (logs.isEmpty()) {
                EmptyLogs()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = MusePaddings.screen,
                        vertical = MusePaddings.contentGap,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    items(
                        items = logs,
                        // v1.0.28: Utilice la identificación global de incremento automático como key,Evite registros con el mismo contenido en el mismo milisegundo
                        // (Como la activación concurrente de MemoryFileWriter)key El conflicto provoca un bloqueo con IllegalArgumentException
                        key = { entry -> entry.id },
                    ) { entry ->
                        val entryKey = entry.id.toString()
                        LogEntryItem(
                            entry = entry,
                            expanded = expandedKey == entryKey,
                            onClick = { onToggleExpand(entryKey) },
                            // v1.0.4 (P3-7): resaltado de palabras clave — Las palabras clave ingresadas por el usuario en el cuadro de búsqueda se resaltan en el mensaje
                            highlight = keywordQuery.trim(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * fila de filtro:Menú desplegable de niveles + Búsqueda de etiquetas + Búsqueda de palabras clave(El desplazamiento horizontal evita apretar en pantallas estrechas).
 */
@Composable
private fun FilterRow(
    levelFilter: String,
    onLevelChange: (String) -> Unit,
    tagQuery: String,
    onTagChange: (String) -> Unit,
    keywordQuery: String,
    onKeywordChange: (String) -> Unit,
) {
    // ALL + Todos los niveles;MuseDropdown Expectativa List<Pair<value, displayText>>
    val levelOptions = buildList {
        add("ALL" to "ALL")
        DebugLogStore.LEVELS.forEach { add(it to it) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // El desplazamiento horizontal comienza con un espacio en blanco(Alinear con los márgenes horizontales de la pantalla)
        Spacer(Modifier.width(MusePaddings.screen))
        // nivel desplegable(ancho fijo,Evite ocupar demasiado espacio al desplazarse horizontalmente)
        MuseDropdown(
            value = levelFilter,
            onValueChange = onLevelChange,
            label = stringResource(R.string.debug_level_label),
            options = levelOptions,
            modifier = Modifier.width(120.dp),
        )
        Spacer(Modifier.width(MusePaddings.contentGap))
        MuseTextField(
            value = tagQuery,
            onValueChange = onTagChange,
            label = { Text(stringResource(R.string.review_debug_tag)) },
            singleLine = true,
            modifier = Modifier.width(140.dp),
        )
        Spacer(Modifier.width(MusePaddings.contentGap))
        MuseTextField(
            value = keywordQuery,
            onValueChange = onKeywordChange,
            label = { Text(stringResource(R.string.debug_keyword_label)) },
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = MuseIcons.search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier = Modifier.width(180.dp),
        )
        // Deje un espacio en blanco al final del desplazamiento horizontal,Evite que el último control toque el borde derecho.
        Spacer(Modifier.width(MusePaddings.screen))
    }
}

/**
 * Estado vacío:Se muestra cuando no hay registros ni coincidencias.
 */
@Composable
private fun EmptyLogs() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = MuseIcons.bug,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(MusePaddings.contentGap))
            Text(
                text = stringResource(R.string.debug_empty_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(MusePaddings.tightGap))
            Text(
                text = stringResource(R.string.debug_empty_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * Entrada de registro única. Haga clic para alternar para expandir/Doblar.
 *
 * Ampliar para mostrar el mensaje completo(Ninguno maxLines)y throwable stack trace(Si es así).
 * Mensaje máximo 2 líneas cuando está contraído,Elipsis al final.
 */
@Composable
private fun LogEntryItem(
    entry: DebugLogEntry,
    expanded: Boolean,
    onClick: () -> Unit,
    // v1.0.4 (P3-7): resaltado de palabras clave — Cuando no esté vacío, resalte todas las subcadenas coincidentes en el mensaje con un fondo amarillo
    highlight: String = "",
) {
    val timeStr = remember(entry.timestamp) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timestamp))
    }
    // v1.0.52: Colores de estado semánticos en lugar de codificación rígida,El modo oscuro cambia automáticamente al modo brillante
    val statusColors = MaterialTheme.statusColors
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val levelColor = remember(entry.level, statusColors) { levelColor(entry.level, statusColors) }
    // v1.0.4 (P3-7): Construir mensaje como resaltado AnnotatedString
    val messageAnnotated = remember(entry.message, highlight, statusColors, onSurfaceColor) {
        buildAnnotatedString {
            if (highlight.isEmpty()) {
                append(entry.message)
                return@buildAnnotatedString
            }
            // Búsqueda que no distingue entre mayúsculas y minúsculas:Atraviesa todas las posiciones coincidentes,usar yellow background + black text Resaltar
            val text = entry.message
            var idx = 0
            val lowerText = text.lowercase(Locale.US)
            val lowerHl = highlight.lowercase(Locale.US)
            while (idx < text.length) {
                val hit = lowerText.indexOf(lowerHl, idx)
                if (hit < 0) {
                    append(text.substring(idx))
                    break
                }
                if (hit > idx) append(text.substring(idx, hit))
                pushStyle(
                    androidx.compose.ui.text.SpanStyle(
                        background = statusColors.highlight,
                        color = onSurfaceColor,
                    ),
                )
                append(text.substring(hit, hit + highlight.length))
                pop()
                idx = hit + highlight.length
            }
        }
    }

    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
        ) {
            // primera linea:etiqueta de tiempo + nivel + Tag
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MuseMonoFontFamily,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.width(MusePaddings.contentGap))
                LevelChip(level = entry.level, color = levelColor)
                Spacer(Modifier.width(MusePaddings.contentGap))
                Text(
                    text = entry.tag,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(MusePaddings.tightGap))
            // segunda linea:noticias(2 líneas omitidas cuando está doblado,Volumen completo cuando se expande)
            // v1.0.4 (P3-7): Representado con mensajeAnotado,Admite resaltado de palabras clave
            Text(
                text = messageAnnotated,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = if (expanded) TextOverflow.Visible else TextOverflow.Ellipsis,
            )
            // Después de desplegar:throwable stack trace(Si es así)
            AnimatedVisibility(
                visible = expanded && !entry.throwable.isNullOrEmpty(),
                enter = MuseMotion.expandFadeEnter(),
                exit = MuseMotion.expandFadeExit(),
            ) {
                Column {
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    Surface(
                        shape = MuseShapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = entry.throwable.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(MusePaddings.contentGap)
                                .horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Cápsulas con etiqueta de grado(Fondo colorido + iniciales de grado.).
 *
 * convención de color:ERROR Rojo / WARN naranja / INFO azul / DEBUG gris.
 */
@Composable
private fun LevelChip(level: String, color: Color) {
    Surface(
        shape = MuseShapes.tiny,
        color = color.copy(alpha = 0.18f),
    ) {
        Text(
            text = level.take(1),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(MusePaddings.tightGap),
        )
    }
}

/**
 * Cadenas de nivel de mapa para mostrar colores.
 *
 * v1.0.52: La fuente de color cambió a [MuseStatusColors](Pasado después de haber sido leído del tema por la persona que llama a Composable),
 * El modo oscuro utiliza automáticamente colores más brillantes,Evite los rojos intensos en bases profundas/El azul oscuro es ilegible.
 */
private fun levelColor(level: String, colors: MuseStatusColors): Color {
    return when (level) {
        DebugLogStore.LEVEL_ERROR -> colors.error
        DebugLogStore.LEVEL_WARN -> colors.warning
        DebugLogStore.LEVEL_INFO -> colors.info
        DebugLogStore.LEVEL_DEBUG -> colors.neutral
        else -> colors.neutral
    }
}

// ════════════════════════════════════════════════════════════════════════════

// ════════════════════════════════════════════════════════════════════════════
// P3-3:Panel de integridad de la base de datos — Exponer los resultados de verificación existentes pero no basados en la interfaz de usuario de IntegrityChecker
//
//  fuente de datos:IntegrityChecker.getLatestStatus() → DbIntegrityLogEntity?
//  Verificación de gatillo:IntegrityChecker.checkAndLog() → Boolean(Espere los resultados sincrónicamente)
//  Realizar el pedido:Cada resultado de la verificación se escribe en la tabla db_integrity_log,Conservar automáticamente los últimos 30 elementos
// ════════════════════════════════════════════════════════════════════════════

/**
 * Panel inferior de integridad de la base de datos.
 *
 * Mostrar contenido:
 *  - Resultados del último examen.:Estado(ok / error)+ DB Tamaño + Hora de verificación + Detalles
 *  - "Compruébalo ahora"botón:gatillo [IntegrityChecker.checkAndLog],Mostrar cargando + resultados
 *  - Mostrar mensajes de respaldo en estado anormal(Guíe a los usuarios para que restauren desde copias de seguridad automáticas)
 *
 * ST-09: Este panel es la única fuente de datos fallida dentro de DebugScreen(Room Consulta),
 * Por tanto, el estado de carga está unificado. [MuseLoadingState],Fallo de lectura unificada [MuseErrorStateBox] + Botón Reintentar.
 */
@Composable
private fun DbIntegritySheet(onDismiss: () -> Unit) {
    val checker: IntegrityChecker = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var latest by remember { mutableStateOf<DbIntegrityLogEntity?>(null) }
    var loading by remember { mutableStateOf(true) }
    var checking by remember { mutableStateOf(false) }
    // ST-09: Leer motivo del error + botón de reintento(Al volver a intentarlo, los cambios clave desencadenan una nueva ejecución. LaunchedEffect)
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    // Obtenga el último estado una vez al abrir(Fallar con motivo legible + reintentar,No más vueltas desnudas/silencio)
    LaunchedEffect(reloadKey) {
        loading = true
        loadError = null
        try {
            latest = checker.getLatestStatus()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.message?.take(120)
                ?: context.getString(R.string.common_load_failed)
        } finally {
            loading = false
        }
    }

    MuseBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.debug_db_integrity_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                // LAYOUT-01: Row Ancho del texto interno + límite de línea, evita el ajuste de línea palabra por palabra al ampliar el tamaño de fuente
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(MusePaddings.contentGap))

        when {
            loading -> {
                // ST-09: Carga unificada de componentes con estado.(alternativa desnuda Text "Cargando")
                MuseLoadingState(message = stringResource(R.string.debug_loading_db_integrity))
            }
            loadError != null -> {
                // ST-09: Error de lectura → Motivo legible + reintento
                MuseErrorStateBox(
                    message = loadError.orEmpty(),
                    onRetry = {
                        loadError = null
                        reloadKey++
                    },
                )
            }
            else -> {
                latest?.let { entity ->
                    val isOk = entity.status.equals("ok", ignoreCase = true)
                    // ── tarjeta de estado:Icono de círculo + texto de estado + tamaño de base de datos + hora de verificación ────────
                    Surface(
                        shape = MuseShapes.large,
                        color = if (isOk) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        } else {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(MusePaddings.screen),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = if (isOk) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isOk) {
                                            MuseIcons.check
                                        } else {
                                            MuseIcons.x
                                        },
                                        contentDescription = null,
                                        tint = if (isOk) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onError
                                        },
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isOk) {
                                        stringResource(R.string.debug_db_status_ok)
                                    } else {
                                        stringResource(R.string.debug_db_status_error)
                                    },
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isOk) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = stringResource(
                                        R.string.debug_db_size_label,
                                        DebugFormatters.formatFileSize(entity.dbSizeBytes),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.debug_db_check_time_label,
                                        dateFormat.format(Date(entity.checkedAt)),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // ── Mostrar detalles + sugerencias de respaldo cuando ocurren excepciones ─────────────────────────────
                    if (!isOk) {
                        Spacer(Modifier.height(MusePaddings.contentGap))
                        Surface(
                            shape = MuseShapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = entity.details.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.debug_no_details),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .padding(MusePaddings.contentGap)
                                    .heightIn(max = 160.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                        Spacer(Modifier.height(MusePaddings.contentGap))
                        Text(
                            text = stringResource(R.string.debug_db_error_advice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Spacer(Modifier.height(MusePaddings.contentGap))

                    // ── Botón comprobar ahora ─────────────────────────────────────────
                    MuseCapsuleButton(
                        text = if (checking) {
                            stringResource(R.string.debug_checking)
                        } else {
                            stringResource(R.string.debug_check_now)
                        },
                        onClick = {
                            if (checking) return@MuseCapsuleButton
                            scope.launch {
                                checking = true
                                // ST-09: El cheque en sí puede arrojar(Room/PRAGMA anormal)→ Dar una razón legible después de la captura.,No más comprobaciones atascadas
                                val result = runCatching { checker.checkAndLog() }
                                latest = runCatching { checker.getLatestStatus() }.getOrNull()
                                checking = false
                                result.onSuccess { ok ->
                                    MuseToast.show(
                                        context.getString(
                                            if (ok) {
                                                R.string.debug_integrity_check_passed
                                            } else {
                                                R.string.debug_integrity_check_failed
                                            },
                                        ),
                                    )
                                }.onFailure { e ->
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    loadError = e.message?.take(120)
                                        ?: context.getString(R.string.debug_integrity_check_failed)
                                }
                            }
                        },
                        enabled = !checking,
                        loading = checking,
                        leadingIcon = MuseIcons.shieldCheck,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } ?: run {
                    // Sin registro de inspección(Primer uso / la mesa está vacía)
                    Text(
                        text = stringResource(R.string.debug_no_check_record),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = MusePaddings.contentGap),
                    )
                    MuseCapsuleButton(
                        text = if (checking) {
                            stringResource(R.string.debug_checking)
                        } else {
                            stringResource(R.string.debug_check_now)
                        },
                        onClick = {
                            if (checking) return@MuseCapsuleButton
                            scope.launch {
                                checking = true
                                // ST-09: Lo mismo"Hay un registro"rama — El error en la primera verificación también proporciona un motivo legible + reintento
                                val result = runCatching { checker.checkAndLog() }
                                latest = runCatching { checker.getLatestStatus() }.getOrNull()
                                checking = false
                                result.onFailure { e ->
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    loadError = e.message?.take(120)
                                        ?: context.getString(R.string.debug_integrity_check_failed)
                                }
                            }
                        },
                        enabled = !checking,
                        loading = checking,
                        leadingIcon = MuseIcons.shieldCheck,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
