package io.zer0.muse.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseCapsuleTab
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.museAnimateItem
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.common.state.MuseLoadingState
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.statusColors
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * v0.45: Página de búsqueda global independiente.
 *
 * Ingrese desde el botón de búsqueda en la esquina superior derecha de la página de inicio,Desliza hacia la derecha para ingresar. Función:
 *  - cuadro de búsqueda superior(enfoque automático)
 *  - Sin entrada:Gran área de estado vacío + sugerencias de búsqueda populares chip
 *  - hay entrada(v2.x: Cambio de pestaña superior"sesión/Contenido del mensaje"):
 *      - Tab=sesión:Mostrar título de la sesión/Vista previa final de los resultados coincidentes
 *      - Tab=Contenido del mensaje:Mostrar resultados de coincidencia del contenido del mensaje(FTS4/LIKE + Resaltado amarillo de palabra clave),
 *        Haga clic para saltar a la sesión correspondiente y pase messageId para posicionamiento de desplazamiento + resaltado breve
 *
 * fuente de datos:
 *  - Tab=sesión:La memoria de ejecución contiene filtrado en state.sessions
 *  - Tab=Contenido del mensaje:ir [ChatViewModel.searchMessageContent](SessionRepository.searchMessageContentFlow,
 *    FTS4 snippet + LIKE Ten todo en cuenta)
 *
 * v2.x: Nuevo [onOpenMessage] devolución de llamada,Tab=Contenido del mensaje Se activa cuando se hace clic en el elemento del mensaje,
 * MainActivity En consecuencia, switchSession + setTargetMessage y luego regrese a HOME,
 * ChatScreen Supervise el posicionamiento de desplazamiento de targetMessageId + resaltado breve.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SearchScreen(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    /**
     * v2.x: Tab=Contenido del mensaje Haga clic en el elemento del mensaje para saltar a la devolución de llamada.
     * @param sessionId sesión objetivo id
     * @param messageId mensaje objetivo id(Para posicionamiento de desplazamiento de ChatScreen)
     * @param query Buscar palabras clave(Se utiliza para resaltar texto dentro de MessageBubble)
     */
    onOpenMessage: (sessionId: String, messageId: String, query: String) -> Unit = { _, _, _ -> },
    viewModel: ChatViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query = state.searchQuery
    // v2.x: Búsqueda actual Tab(0=sesión, 1=Contenido del mensaje)
    val searchTab = state.searchTab
    val focusRequester = remember { FocusRequester() }

    // Cuadro de búsqueda de enfoque automático
    // v1.0.74 fix: Espere un fotograma antes de solicitar,Evite fallas cuando el sistema de enfoque no esté listo(Prueba real de Huawei Android 10)
    io.zer0.muse.ui.common.focus.SafeAutoFocusEffect(focusRequester)

    // Cuando la entrada cambia(rebote 300ms)Actualizar consulta y activar búsqueda
    // v2.x: Cambiar según la pestaña de búsqueda searchSessions() o searchMessageContent()
    LaunchedEffect(query, searchTab) {
        if (query.isNotBlank()) {
            delay(300)
            viewModel.updateSearchQuery(query)
            if (searchTab == 1) {
                viewModel.searchMessageContent()
            } else {
                viewModel.search()
            }
        } else {
            viewModel.clearSearch()
        }
    }

    MusePageScaffold(
        topBarHandlesInsets = false,
        topBar = {
            // Barra de búsqueda ranurada:Surface Groove + Icono de búsqueda + Campo de texto básico + Cancelar texto
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier
                    .fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MusePaddings.screen, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = MuseShapes.semiLarge,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = MuseIcons.search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            BasicTextField(
                                value = query,
                                onValueChange = { viewModel.updateSearchQuery(it) },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focusRequester),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Normal,
                                ),
                                decorationBox = { innerTextField ->
                                    Box {
                                        if (query.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.search_placeholder),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        innerTextField()
                                    }
                                },
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.search_cancel),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.clickable { onBack() },
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // v2.x: Mostrar cápsula al ingresar Alternar pestaña(Contenido de la conversación/mensaje)
            if (query.isNotBlank()) {
                MuseCapsuleTab(
                    tabs = listOf(
                        stringResource(R.string.search_tab_sessions),
                        stringResource(R.string.search_tab_message_content),
                    ),
                    selectedIndex = searchTab,
                    onSelect = { viewModel.switchSearchTab(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MusePaddings.screen, vertical = 4.dp),
                )
            }

            if (query.isBlank()) {
                // Sin entrada:Gran área de espacio vacío.
                EmptySearchState(
                    onSuggestionClick = { suggestion ->
                        viewModel.updateSearchQuery(suggestion)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                PullToRefreshBox(
                    isRefreshing = if (searchTab == 0) state.isSearching else state.isSearchingMessages,
                    onRefresh = {
                        if (searchTab == 1) viewModel.searchMessageContent() else viewModel.search()
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (searchTab == 0) {
                        // Tab=sesión:Título de la sesión/Vista previa de coincidencia + coincidencia de contenido del mensaje
                        SearchResults(
                            query = query,
                            sessions = state.sessions,
                            messageResults = state.searchResults,
                            isSearching = state.isSearching,
                            searchError = state.searchError,
                            onRetry = { viewModel.search() },
                            onOpenSession = onOpenSession,
                            onOpenMessage = onOpenMessage,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        // Tab=Contenido del mensaje:Mostrar resultados de fragmentos de FTS4,Haga clic para saltar a transferir messageId
                        MessageSearchResults(
                            query = query,
                            messageResults = state.messageResults,
                            isSearching = state.isSearchingMessages,
                            searchError = state.searchError,
                            onRetry = { viewModel.searchMessageContent() },
                            onOpenMessage = onOpenMessage,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

/** Estado vacío sin entrada:Ícono de búsqueda gris centrado + sugerencia + chip de palabras sugeridas. */
@Composable
private fun EmptySearchState(onSuggestionClick: (String) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = MuseIcons.search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                modifier = Modifier.size(80.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.search_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(24.dp))
            // Sugerencias de búsqueda populares chip(basado en sesión/Contenido del mensaje,No contiene elementos de configuración)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    R.string.search_suggestion_today,
                    R.string.search_suggestion_summary,
                    R.string.search_suggestion_report,
                    R.string.search_suggestion_idea,
                ).forEach { resId ->
                    val label = stringResource(resId)
                    MuseChip(
                        selected = false,
                        onClick = { onSuggestionClick(label) },
                        label = label,
                    )
                }
            }
        }
    }
}

/** Tab=Resultados de búsqueda de conversaciones:Título de la sesión/Vista previa de coincidencia + coincidencia de contenido del mensaje(Estilo de dibujo de referencia). */
@Composable
private fun SearchResults(
    query: String,
    sessions: List<io.zer0.muse.data.session.SessionEntity>,
    messageResults: List<io.zer0.muse.data.session.SearchResult>,
    isSearching: Boolean,
    searchError: String? = null,
    onRetry: () -> Unit = {},
    onOpenSession: (String) -> Unit,
    onOpenMessage: (sessionId: String, messageId: String, query: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // v1.0.62: El estado de error unificado tiene prioridad sobre la visualización del estado de carga/vacío,Evite falsas alarmas al desconectarse“Sin resultados”
    if (searchError != null) {
        MuseErrorStateBox(
            message = searchError,
            onRetry = onRetry,
            modifier = modifier,
        )
        return
    }
    // M-SS1: Filtrar resultados usando recordar caché,Evite cálculos repetidos para cada recomposición.(La implementación original se reinicia cada vez. filter)
    val matchedSessions = remember(sessions, query) {
        filterSearchSessions(sessions, query)
    }
    val uniqueMessageResults = remember(messageResults) { uniqueSearchResults(messageResults) }

    val hasAny = matchedSessions.isNotEmpty() || uniqueMessageResults.isNotEmpty()

    // Buscando y sin resultados:centro loading
    if (!hasAny && isSearching) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MuseLoadingState()
                Text(
                    text = stringResource(R.string.search_searching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        return
    }

    // No hay resultados coincidentes
    if (!hasAny && !isSearching) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.search_no_result),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.search_try_other),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = MusePaddings.screen,
            vertical = 8.dp,
        ),
    ) {
        // resultados de la sesión section
        if (matchedSessions.isNotEmpty()) {
            item(key = "section_sessions") { SectionTitle(stringResource(R.string.search_section_sessions)) }
            itemsIndexed(
                matchedSessions,
                key = { index, session -> "session_${session.id}_$index" },
            ) { _, session ->
                Box(modifier = museAnimateItem()) {
                    // No puedes usar ifBlank simplemente para cubrir el título: algo como "..."Este contenido sin sentido no se considera vacío,
                    // Se representará como puntos suspensivos y el usuario no podrá saber qué conversación es.
                    // Aquí se requieren al menos dos caracteres que no sean de puntuación; de lo contrario, volverá a la primera oración de la vista previa.
                    val rawTitle = session.title.trim()
                    val meaningfulTitle = rawTitle.takeIf { candidate ->
                        candidate.count { it.isLetterOrDigit() } >= 2
                    }
                    val fallbackPreview = session.lastMessagePreview
                        .trim()
                        .lineSequence()
                        .firstOrNull()
                        ?.take(20)
                        ?.takeIf { it.isNotBlank() }
                    SessionResultRow(
                        title = meaningfulTitle
                            ?: fallbackPreview
                            ?: stringResource(R.string.search_new_session),
                        preview = session.lastMessagePreview,
                        updatedAt = session.updatedAt,
                        onClick = { onOpenSession(session.id) },
                    )
                }
            }
        }
        // Contenido del mensaje section(FTS resultado)
        if (uniqueMessageResults.isNotEmpty()) {
            item(key = "section_messages") { SectionTitle(stringResource(R.string.search_section_messages)) }
            itemsIndexed(
                uniqueMessageResults,
                key = { index, result -> "msg_${result.messageId}_$index" },
            ) { _, result ->
                Box(modifier = museAnimateItem()) {
                    MessageSearchResultRow(
                        sessionTitle = result.sessionTitle,
                        content = result.content,
                        fallbackSnippet = result.contentSnippet,
                        query = query,
                        timestamp = result.createdAt,
                        onClick = { onOpenMessage(result.sessionId, result.messageId, query) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/** Elementos de resultados de la sesión:Ícono de burbuja de chat + título + subtítulo a la izquierda(Vista previa/Fuente · tiempo relativo). */
@Composable
private fun SessionResultRow(title: String, preview: String, updatedAt: Long, onClick: () -> Unit) {
    val context = LocalContext.current
    val timeText = remember(updatedAt, context) { formatSearchRelativeTime(updatedAt, context) }
    val subtitle = if (preview.isNotBlank()) {
        "$preview · $timeText"
    } else {
        timeText
    }
    Surface(
        onClick = onClick,
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = MuseElevation.card,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MuseIcons.chat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

// ── v2.x: Tab=Contenido del mensaje Visualización de resultados de búsqueda ──────────────────────────────────

/**
 * v2.x: Tab=Contenido del mensaje Lista de resultados de búsqueda.
 *
 * mostrar [SearchResult] lista(Título de la sesión + fragmento de contenido(Resaltar)+ tiempo),
 * Haga clic para llamar [onOpenMessage] Salte a la sesión correspondiente y pase messageId para posicionamiento de desplazamiento + resaltado breve.
 *
 * resaltado del clip:[SearchResult.contentSnippet] por FTS4 snippet() generar,Coincidir token con [ ] paquete,
 * [buildHighlightedSnippet] Después del análisis, agregue un fondo amarillo translúcido a la parte correspondiente.
 *
 * Resultados vacíos + Buscando:centro loading;Resultados vacíos + no buscar:No hay mensaje coincidente.
 */
@Composable
private fun MessageSearchResults(
    query: String,
    messageResults: List<io.zer0.muse.data.session.SearchResult>,
    isSearching: Boolean,
    searchError: String? = null,
    onRetry: () -> Unit = {},
    onOpenMessage: (sessionId: String, messageId: String, query: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // v1.0.62: El estado de error unificado tiene prioridad sobre la visualización del estado de carga/vacío
    if (searchError != null) {
        MuseErrorStateBox(
            message = searchError,
            onRetry = onRetry,
            modifier = modifier,
        )
        return
    }
    // Sin resultados + Buscando:Carga centrada de gran tamaño + redacción publicitaria
    if (messageResults.isEmpty() && isSearching) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MuseLoadingState()
                Text(
                    text = stringResource(R.string.search_searching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        return
    }

    // Sin resultados + sin buscar:No hay mensaje coincidente
    if (messageResults.isEmpty() && !isSearching) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.search_no_result),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.search_try_other),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        return
    }

    val uniqueMessageResults = remember(messageResults) { uniqueSearchResults(messageResults) }
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = MusePaddings.screen,
            vertical = 8.dp,
        ),
    ) {
        // Al buscar, se muestra un pequeño número en la parte superior cargando + redacción(El resultado ya está disponible pero aún se está adjuntando.)
        if (isSearching) {
            item(key = "loading") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MuseSpinner(
                        size = 20.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.search_searching),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "section_messages_content") { SectionTitle(stringResource(R.string.search_section_message_content)) }
        itemsIndexed(
            uniqueMessageResults,
            key = { index, result -> "msg_content_${result.messageId}_$index" },
        ) { _, result ->
            // Tarea 2:Tab=Elemento de resultado del contenido del mensaje — Priorice el uso del texto original para extraer el contexto de las dos oraciones antes y después + resaltado de palabras clave
            MessageSearchResultRow(
                sessionTitle = result.sessionTitle,
                content = result.content,
                fallbackSnippet = result.contentSnippet,
                query = query,
                timestamp = result.createdAt,
                onClick = { onOpenMessage(result.sessionId, result.messageId, query) },
            )
        }
    }
}

internal fun filterSearchSessions(
    sessions: List<io.zer0.muse.data.session.SessionEntity>,
    query: String,
): List<io.zer0.muse.data.session.SessionEntity> = sessions
    .filter {
        it.title.contains(query, ignoreCase = true) ||
            it.lastMessagePreview.contains(query, ignoreCase = true)
    }
    .distinctBy { it.id }
    .take(20)

internal fun uniqueSearchResults(results: List<io.zer0.muse.data.session.SearchResult>): List<io.zer0.muse.data.session.SearchResult> =
    results.distinctBy { it.messageId }

/**
 * Tarea 2: filas de resultados de búsqueda de mensajes unificados(Tab=sesión/Contenido del mensaje compartido).
 *
 * mostrar:
 *  - fila superior:nombre de la sesión(lado izquierdo,Peso 1f + puntos suspensivos)+ tiempo(lado derecho,labelSmall)
 *  - abajo:2 oraciones antes y después del contexto,Coincidir palabra clave con fondo amarillo translúcido + resaltado en negrita
 *
 * Prioridad de fuente de contexto:
 *  1. [content](Texto original)No vacío → usar [extractContext] Extrae 2 oraciones antes y después.,usar [buildHighlightedText] Resaltar query
 *  2. De lo contrario(FTS4 snippet camino)→ usar [fallbackSnippet],usar [buildHighlightedSnippet] analizar [xxx] marca
 *
 * @param sessionTitle Título de la sesión
 * @param content mensaje original(puede estar vacío — FTS4 snippet Cuando la ruta no vuelve al texto original)
 * @param fallbackSnippet FTS4 snippet Contiene [xxx] fragmento etiquetado(content Usado cuando está vacío)
 * @param query Buscar palabras clave(para resaltar)
 * @param timestamp Marca de tiempo de creación del mensaje
 * @param onClick Haga clic en devolución de llamada(Saltar a la conversación correspondiente + desplazarse hasta messageId)
 */
@Composable
private fun MessageSearchResultRow(
    sessionTitle: String,
    content: String,
    fallbackSnippet: String,
    query: String,
    timestamp: Long,
    onClick: () -> Unit,
) {
    // Tarea 2: Priorizar el uso del texto original para extraer el contexto de las dos oraciones antes y después,Se utiliza cuando no hay texto original disponible. FTS4 snippet([xxx] marca)
    val displayText = remember(content, fallbackSnippet, query) {
        if (content.isNotBlank()) {
            extractContext(content, query, sentencesAround = 2)
        } else {
            fallbackSnippet
        }
    }
    // Tarea 2: resaltar — Cuando el texto original esté disponible, utilice directamente la consulta para resaltarlo.;De lo contrario, analice los fragmentos de FTS4 [xxx] marca
    // v1.0.52: El color de resaltado se lee desde el color de estado del tema.,No más codificación de colores nude
    val highlightColor = MaterialTheme.statusColors.highlight
    val annotatedText = remember(displayText, content, query, highlightColor) {
        if (content.isNotBlank()) {
            buildHighlightedText(displayText, query, highlightColor)
        } else {
            buildHighlightedSnippet(displayText, highlightColor)
        }
    }
    Surface(
        onClick = onClick,
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = MuseElevation.card,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            // fila superior:Nombre de la sesión + hora(lado derecho)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = sessionTitle.ifBlank { stringResource(R.string.search_new_session) },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(8.dp))
                val context = LocalContext.current
                Text(
                    text = remember(timestamp, context) { formatSearchTimestamp(timestamp, context) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Tarea 2: contexto de 2 oraciones + resaltado de palabras clave(maxLines=3 acomodar un contexto más largo)
            if (annotatedText.isNotEmpty()) {
                Text(
                    text = annotatedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * Tarea 2: Desde [content] Extracción y comparación del texto original. [query] antes y después de la frase [sentencesAround] contexto de la oración.
 *
 * prensa [. ! ?.!?\n] cortar oraciones(mantener separador),Después de encontrar la oración correspondiente, toma las palabras antes y después. [sentencesAround] Empalme de oraciones.
 * Cuando no se encuentre la posición coincidente, retroceda al punto anterior. (2*sentencesAround+1) oración.
 *
 * Se utiliza para el contexto de visualización del elemento de resultado de búsqueda,Permita que los usuarios determinen si es el mensaje de destino antes de hacer clic.
 */
/** Puntuación regular al final de las oraciones.(Chino. ! ? + ASCII .!? + nueva línea),Las aserciones de ancho cero dejan el delimitador al final de la oración. */
private val SENTENCE_SPLIT_REGEX = "(?<=[. ! ?.!?\n])".toRegex()

private fun extractContext(content: String, query: String, sentencesAround: Int = 2): String {
    if (content.isBlank()) return ""
    // Cortar oraciones según la puntuación al final de la oración.,Mantener delimitadores al final de las oraciones.
    val sentences = content.split(SENTENCE_SPLIT_REGEX).filter { it.isNotBlank() }
    if (sentences.isEmpty()) return content.take(200)

    val matchIdx = content.indexOf(query, ignoreCase = true)
    // Encuentra el índice de la oración coincidente.(Acumular longitud oración por oración,hasta cubrir matchIdx)
    var matchSentenceIdx = 0
    if (matchIdx >= 0) {
        var pos = 0
        for ((i, s) in sentences.withIndex()) {
            if (matchIdx < pos + s.length) {
                matchSentenceIdx = i
                break
            }
            pos += s.length
            matchSentenceIdx = i
        }
    }
    val startIdx = (matchSentenceIdx - sentencesAround).coerceAtLeast(0)
    val endIdx = (matchSentenceIdx + sentencesAround + 1).coerceAtMost(sentences.size)
    val context = sentences.subList(startIdx, endIdx).joinToString("").trim()
    return context.ifBlank { content.take(200) }
}

/**
 * Tarea 2: Poner [text] todos los partidos en [query] subcadena de(No distingue entre mayúsculas y minúsculas)Resalta con fondo amarillo translúcido + negrita.
 *
 * Se utiliza para resaltar palabras clave cuando los elementos de resultados de búsqueda muestran el contexto del texto original.
 */
private fun buildHighlightedText(text: String, query: String, highlightColor: Color): AnnotatedString {
    if (query.isBlank()) return buildAnnotatedString { append(text) }
    return buildAnnotatedString {
        var idx = 0
        while (idx < text.length) {
            val found = text.indexOf(query, idx, ignoreCase = true)
            if (found < 0) {
                append(text.substring(idx))
                break
            }
            if (found > idx) {
                append(text.substring(idx, found))
            }
            withStyle(SpanStyle(background = highlightColor, fontWeight = FontWeight.Bold)) {
                append(text.substring(found, found + query.length))
            }
            idx = found + query.length
        }
    }
}

/**
 * v2.x: Pon el fragmento de FTS4 [xxx] marcado convertido a AnnotatedString,
 * Pieza a juego más fondo amarillo translúcido.(Color(0x66FFEB3B)).
 *
 * snippet() Generar formato de fragmento:"prefijo[partido]sufijo",Puede contener múltiples [xxx] segmento.
 * Al analizar [ ] eliminación de marcas,Aplica SpanStyle al texto coincidente original.
 */
private fun buildHighlightedSnippet(snippet: String, highlightColor: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < snippet.length) {
        val start = snippet.indexOf('[', i)
        if (start < 0) {
            // El resto es todo texto normal.
            append(snippet.substring(i))
            break
        }
        if (start > i) {
            append(snippet.substring(i, start))
        }
        val end = snippet.indexOf(']', start)
        if (end < 0) {
            // abierto '[' Salida tal cual,Evite tragar personajes
            append(snippet.substring(start))
            break
        }
        val matched = snippet.substring(start + 1, end)
        withStyle(SpanStyle(background = highlightColor)) {
            append(matched)
        }
        i = end + 1
    }
}

/** v2.x: Dar formato a la marca de tiempo como tiempo relativo(Justo / Hace N minutos / Hoy HH:mm / Ayer / MM-dd). */
private fun formatSearchTimestamp(timestamp: Long, context: Context): String = formatSearchRelativeTime(timestamp, context)

/** Formato de tiempo relativo de la lista de búsqueda(Hoy HH:mm / Ayer / MM-dd). */
private fun formatSearchRelativeTime(timestamp: Long, context: Context): String {
    if (timestamp <= 0) return ""
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val dayMillis = TimeUnit.DAYS.toMillis(1)
    val timeSdf = SimpleDateFormat(MuseDateFormats.TIME_SHORT, Locale.getDefault())
    val dateSdf = SimpleDateFormat(MuseDateFormats.DATE_SHORT, Locale.getDefault())
    val calNow = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val calTarget = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    val isSameDay = calNow.get(java.util.Calendar.YEAR) == calTarget.get(java.util.Calendar.YEAR) &&
        calNow.get(java.util.Calendar.DAY_OF_YEAR) == calTarget.get(java.util.Calendar.DAY_OF_YEAR)
    val isYesterday = calNow.get(java.util.Calendar.YEAR) == calTarget.get(java.util.Calendar.YEAR) &&
        calNow.get(java.util.Calendar.DAY_OF_YEAR) - calTarget.get(java.util.Calendar.DAY_OF_YEAR) == 1
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> context.getString(R.string.chat_list_time_just_now)
        diff < TimeUnit.HOURS.toMillis(1) -> context.getString(R.string.chat_list_time_minutes_ago, diff / TimeUnit.MINUTES.toMillis(1))
        isSameDay -> context.getString(R.string.search_time_today, timeSdf.format(Date(timestamp)))
        isYesterday -> context.getString(R.string.search_time_yesterday, timeSdf.format(Date(timestamp)))
        diff < dayMillis * 7 -> context.getString(R.string.chat_list_time_days_ago, diff / dayMillis)
        else -> dateSdf.format(Date(timestamp))
    }
}
