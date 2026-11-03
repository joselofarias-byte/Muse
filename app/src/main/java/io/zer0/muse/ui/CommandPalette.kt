package io.zer0.muse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import compose.icons.tablericons.Search
import io.zer0.muse.R
import io.zer0.muse.data.session.SearchResult
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.museAnimateItem
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseDialogWindowEffect
import io.zer0.muse.ui.common.surface.museModalScrim
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/**
 * C1 panel de comando global — Escritorio Ctrl+K / Portal unificado evocado por el botón de búsqueda de la página de inicio.
 *
 * Reconocimiento automático de dos modos.:
 *  - a `/` comienzo:modo comando,Listar acciones ejecutables(Nueva conversación/ajustes/búsqueda completa),Enter Ejecutar.
 *  - Otros:Modo de búsqueda instantánea,Reutilizar [ChatViewModel.search]/[searchMessageContent]
 *    (FTS4 + LIKE revertir),Mostrar los resultados de la conversación y los resultados de los mensajes simultáneamente;Haga clic para reutilizar los existentes
 *    [ChatViewModel.switchSession]/[openMessageFromSearch] Enlace de salto.
 *
 * Coexistiendo con una página de búsqueda separada(Retención profunda de búsqueda en tres dominios,/salto de entrada de búsqueda se proporciona en el panel).
 * teclado:↑/↓ seleccionar,Enter ejecución,Esc Cerca.
 */
// Diálogo a nivel de pantalla: distribución de teclado + comando/Buscar despacho bimodal para estructuras ramificadas intrínsecas,La complejidad sólo supera el umbral 1.
@Suppress("CyclomaticComplexMethod")
@Composable
internal fun CommandPalette(
    onDismiss: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenChat: () -> Unit,
    viewModel: ChatViewModel = koinInject(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedIndex by remember { mutableStateOf(0) }
    // CHAT-15: Reintentar manualmente el activador después de una búsqueda fallida
    var searchRetryKey by remember { mutableStateOf(0) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    // Búsqueda anti-vibración(Reutilizar ChatViewModel search/searchMessageContent,Mismo enlace que SearchScreen)
    // CHAT-15: Unirse searchRetryKey,"Reintentar" después de un error puede reactivar este enlace.
    LaunchedEffect(query, searchRetryKey) {
        if (query.isNotBlank() && !query.startsWith("/")) {
            delay(300)
            viewModel.updateSearchQuery(query)
            viewModel.search()
            viewModel.searchMessageContent()
        } else if (query.isBlank()) {
            viewModel.clearSearch()
        }
    }

    val commands = listOf(
        CommandEntry("/new", R.string.command_palette_new_chat, MuseIcons.plus, onNewChat),
        CommandEntry("/settings", R.string.command_palette_settings, MuseIcons.sliders, onOpenSettings),
        CommandEntry("/search", R.string.command_palette_full_search, MuseIcons.search, onOpenSearch),
    )
    // Filtrado del modo de comando
    val isCommandMode = query.startsWith("/")
    val visibleCommands = if (isCommandMode) {
        commands.filter { it.command.startsWith(query, ignoreCase = true) || query == "/" }
    } else {
        emptyList()
    }
    // Los resultados de búsqueda pueden devolver la misma conversación varias veces por mensaje acertado,El panel de comando solo muestra la entrada de la sesión una vez.
    val sessionResults = remember(state.searchResults) {
        state.searchResults.distinctBy { it.sessionId }
    }
    val messageResults = remember(state.messageResults) {
        state.messageResults.distinctBy { it.messageId }
    }
    val totalItems = visibleCommands.size + sessionResults.size + messageResults.size

    // Ejecutar elemento seleccionado:Ejecutar después del análisis unificado del objetivo.(Comando/Salto de sesión/Salto de mensaje).
    @Suppress("CyclomaticComplexMethod")
    fun execute() {
        val action = resolveSelectionAction(
            selectedIndex = selectedIndex,
            visibleCommands = visibleCommands,
            sessionResults = sessionResults,
            messageResults = messageResults,
            query = query,
            viewModel = viewModel,
            onOpenChat = onOpenChat,
        )
        if (action != null) {
            onDismiss()
            action()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        MuseDialogWindowEffect(forceFullScreen = true)
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .museModalScrim()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.largeGap)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionUp -> {
                                if (totalItems > 0) selectedIndex = (selectedIndex - 1 + totalItems) % totalItems
                                true
                            }
                            Key.DirectionDown -> {
                                if (totalItems > 0) selectedIndex = (selectedIndex + 1) % totalItems
                                true
                            }
                            Key.Enter -> {
                                if (totalItems > 0) execute() else Unit
                                true
                            }
                            Key.Escape -> {
                                onDismiss()
                                true
                            }
                            else -> false
                        }
                    },
            ) {
                // v2.0.1: Barra de búsqueda elíptica (cápsula) — Sensación de vidrio translúcido (más sólido que el vidrio del techo de la página de configuración, mostrando menos).
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.pill,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                    tonalElevation = 0.dp,
                ) {
                    PaletteSearchField(
                        query = query,
                        isCommandMode = isCommandMode,
                        onQueryChange = { newValue ->
                            query = newValue
                            selectedIndex = 0
                        },
                        onClear = {
                            query = ""
                            focusManager.clearFocus()
                        },
                        onExecute = { execute() },
                    )
                }
                // Panel de resultados: 12 dp debajo de la cápsula, tarjeta sólida y redondeada.
                // v2.0.1: Solo se muestra cuando "se ha ingresado contenido" — Manténgalo limpio cuando lo abra por primera vez (comentarios de los usuarios: las coincidencias solo se mostrarán después de la entrada);
                // Ninguna coincidencia corre a cargo del estado vacío dentro del panel;"/" El modo de comando también toma la rama de consulta no vacía.
                // v2.x: Finalización del efecto de movimiento — Aparece el panel de resultados./Desaparecer token deslizante + desvanecimiento (sensación de expansión hacia abajo)
                AnimatedVisibility(
                    visible = query.isNotBlank(),
                    enter = MuseMotion.verticalSlideFadeEnter(initialOffsetY = { -(it * MuseAnimation.SLIDE_FRACTION_CONTENT).toInt() }),
                    exit = MuseMotion.verticalSlideFadeExit(targetOffsetY = { -(it * MuseAnimation.SLIDE_FRACTION_CONTENT).toInt() }),
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        shape = MuseShapes.semiLarge,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp,
                    ) {
                        PaletteResultList(
                            query = query,
                            isCommandMode = isCommandMode,
                            visibleCommands = visibleCommands,
                            sessionResults = sessionResults,
                            messageResults = messageResults,
                            selectedIndex = selectedIndex,
                            // CHAT-15: Buscando/estado fallido
                            isSearching = state.isSearching,
                            searchError = state.searchError,
                            onRetrySearch = { searchRetryKey++ },
                            onSelect = { index ->
                                selectedIndex = index
                                execute()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Analizar el índice seleccionado en acciones específicas;null Indica que no hay elementos ejecutables. */
private fun resolveSelectionAction(
    selectedIndex: Int,
    visibleCommands: List<CommandEntry>,
    sessionResults: List<SearchResult>,
    messageResults: List<SearchResult>,
    query: String,
    viewModel: ChatViewModel,
    onOpenChat: () -> Unit,
): (() -> Unit)? {
    // Cree una lista unificada de elementos en orden de presentación.:comando + sesión + mensaje,El índice está alineado con el índice seleccionado.
    // isCommandMode La búsqueda no se activa cuando,sesión/La lista de mensajes está vacía,Una lista es un conjunto de comandos.
    val entries = buildList {
        visibleCommands.forEach { add(it.action) }
        sessionResults.forEach { session ->
            add {
                viewModel.switchSession(session.sessionId)
                onOpenChat()
            }
        }
        messageResults.forEach { message ->
            add {
                viewModel.openMessageFromSearch(message.sessionId, message.messageId, query)
                onOpenChat()
            }
        }
    }
    return entries.getOrNull(selectedIndex)
}

/** línea de entrada:Icono + cuadro de entrada + botón borrar. */
@Composable
private fun PaletteSearchField(
    query: String,
    isCommandMode: Boolean,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onExecute: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        Icon(
            imageVector = if (isCommandMode) MuseIcons.sliders else MuseIcons.search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(MuseIconSizes.iconSmall),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onExecute() }),
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.command_palette_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
            },
            modifier = Modifier
                .weight(1f)
                .padding(vertical = MusePaddings.tightGap),
        )
        if (query.isNotEmpty()) {
            MuseTactileButton(
                icon = MuseIcons.x,
                onClick = onClear,
                contentDescription = stringResource(R.string.command_palette_clear),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                iconSize = MuseIconSizes.iconSmall,
            )
        }
    }
}

/** Lista de resultados:Representación de tres partes de comando/sesión/mensaje. */
@Composable
private fun PaletteResultList(
    query: String,
    isCommandMode: Boolean,
    visibleCommands: List<CommandEntry>,
    sessionResults: List<SearchResult>,
    messageResults: List<SearchResult>,
    selectedIndex: Int,
    // CHAT-15: Búsqueda/Error
    isSearching: Boolean,
    searchError: String?,
    onRetrySearch: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    // CHAT-15: Desplácese para seguir al seleccionar arriba y abajo en el teclado — Por orden de entrada real de LazyColumn(Contiene el título de la sección)índice de conversión.
    LaunchedEffect(
        selectedIndex,
        isCommandMode,
        query,
        visibleCommands.size,
        sessionResults.size,
        messageResults.size,
    ) {
        val target = if (isCommandMode) {
            if (selectedIndex < visibleCommands.size) selectedIndex + 1 else null
        } else if (query.isNotBlank()) {
            val n = visibleCommands.size
            val m = sessionResults.size
            val k = messageResults.size
            when {
                selectedIndex < n -> if (m > 0) 0 else null
                selectedIndex < n + m -> (if (m > 0) 1 else 0) + (selectedIndex - n)
                else -> (if (m > 0) 1 else 0) + m + (if (k > 0) 1 else 0) + (selectedIndex - n - m)
            }
        } else {
            null
        }
        if (target != null) {
            listState.animateScrollToItem(target.coerceAtLeast(0))
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp),
    ) {
        if (isCommandMode) {
            item(key = "commands_header") {
                PaletteSectionHeader(stringResource(R.string.command_palette_commands))
            }
            itemsIndexed(
                visibleCommands,
                key = { index, entry -> "command_${entry.command}_$index" },
            ) { index, entry ->
                // v2.x: Finalización del efecto de movimiento — Entrada de fila de resultados(ficha animateItem)
                Box(museAnimateItem()) {
                    CommandRow(
                        icon = entry.icon,
                        label = stringResource(entry.labelRes),
                        detail = entry.command,
                        selected = selectedIndex == index,
                        onClick = { onSelect(index) },
                    )
                }
            }
            if (visibleCommands.isEmpty()) {
                item(key = "commands_empty") {
                    PaletteEmpty(stringResource(R.string.command_palette_no_results))
                }
            }
        } else if (query.isNotBlank()) {
            // CHAT-15: Prioridad de falla;Mostrar carga al buscar y no hay resultados.,No se mostrarán más "resultados no coincidentes" por adelantado
            if (searchError != null) {
                item(key = "search_error") {
                    PaletteError(message = searchError, onRetry = onRetrySearch)
                }
            } else if (isSearching && sessionResults.isEmpty() && messageResults.isEmpty()) {
                item(key = "search_loading") {
                    PaletteLoading()
                }
            } else {
                if (sessionResults.isNotEmpty()) {
                    item(key = "sessions_header") {
                        PaletteSectionHeader(stringResource(R.string.command_palette_sessions))
                    }
                    itemsIndexed(
                        sessionResults,
                        key = { index, session -> "s_${session.sessionId}_$index" },
                    ) { index, session ->
                        // v2.x: Finalización del efecto de movimiento — Entrada de fila de resultados(ficha animateItem)
                        Box(museAnimateItem()) {
                            CommandRow(
                                icon = MuseIcons.search,
                                label = session.sessionTitle.ifBlank { session.sessionId },
                                detail = stringResource(R.string.command_palette_open_session),
                                selected = selectedIndex == visibleCommands.size + index,
                                onClick = { onSelect(visibleCommands.size + index) },
                            )
                        }
                    }
                }
                if (messageResults.isNotEmpty()) {
                    item(key = "messages_header") {
                        PaletteSectionHeader(stringResource(R.string.command_palette_messages))
                    }
                    itemsIndexed(
                        messageResults,
                        key = { index, message -> "m_${message.messageId}_$index" },
                    ) { index, message ->
                        // v2.x: Finalización del efecto de movimiento — Entrada de fila de resultados(ficha animateItem)
                        Box(museAnimateItem()) {
                            CommandRow(
                                icon = MuseIcons.search,
                                label = message.sessionTitle.ifBlank { message.sessionId },
                                detail = message.contentSnippet,
                                selected = selectedIndex == visibleCommands.size + sessionResults.size + index,
                                onClick = { onSelect(visibleCommands.size + sessionResults.size + index) },
                            )
                        }
                    }
                }
                if (sessionResults.isEmpty() && messageResults.isEmpty()) {
                    item(key = "search_empty") {
                        PaletteEmpty(stringResource(R.string.command_palette_no_results))
                    }
                }
            }
        } else {
            // consulta vacía:Mostrar mensajes de comando disponibles
            item(key = "empty_hint") {
                PaletteEmpty(stringResource(R.string.command_palette_empty_hint))
            }
        }
    }
}

/** CHAT-15: Estado de búsqueda del panel de comando — Anti-vibración/\"No hay resultados coincidentes\" ya no se mostrará por error durante la consulta. */
@Composable
private fun PaletteLoading() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.largeGap),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MuseSpinner(
            size = MuseIconSizes.iconSmall,
        )
    }
}

/** CHAT-15: La búsqueda en el panel de comando falló — Muestra el error y vuelve a intentarlo. */
@Composable
private fun PaletteError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.largeGap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        MuseCapsuleButton(
            text = stringResource(R.string.action_retry),
            onClick = onRetry,
            variant = IosCapsuleButtonVariant.Text,
            fillWidth = false,
        )
    }
}

/** entrada del panel de comando(comando de barra diagonal). */
private data class CommandEntry(
    val command: String,
    val labelRes: Int,
    val icon: ImageVector,
    val action: () -> Unit,
)

/** Título de la sección del área de resultados. */
@Composable
private fun PaletteSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.tightGap),
    )
}

/** Elemento de resultado de una sola fila(Estado seleccionado resaltado). */
@Composable
private fun CommandRow(icon: ImageVector, label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                } else {
                    Color.Transparent
                },
            )
            .padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.itemGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(MuseIconSizes.iconSmall),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(MusePaddings.tightGap))
    }
}

/** Mensaje de resultado vacío. */
@Composable
private fun PaletteEmpty(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(MusePaddings.contentGap),
    )
}
