package io.zer0.muse.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseSearchBar
import io.zer0.muse.ui.common.form.MuseSettingsIcon
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseLargeTitleHeader
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.MuseDialogWindowEffect
import io.zer0.muse.ui.common.surface.museModalScrim
import io.zer0.muse.ui.common.surface.museSafeTopInsetPadding
import io.zer0.muse.ui.settings.SettingsCatalog
import io.zer0.muse.ui.settings.SettingsEntryAction
import io.zer0.muse.ui.settings.SettingsGroupKind
import io.zer0.muse.ui.settings.SettingsGroupSpec
import io.zer0.muse.ui.settings.SettingsSearchCatalog
import io.zer0.muse.ui.settings.SettingsSearchRecord
import io.zer0.muse.ui.settings.filterSettingsSearch
import io.zer0.muse.ui.settings.rememberSettingsActionHost
import io.zer0.muse.ui.settings.settingsBreadcrumb
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge

/** La etiqueta de prueba para la fila de clasificación de la página de inicio; la prueba cuenta las filas de clasificación según ella y afirma que la página de inicio no tiene entradas de subpágina. */
internal const val SETTINGS_GROUP_ROW_TAG: String = "settings-group-row"

/**
 * Configurar la página de inicio:12 Cada categoría de primer nivel tiene una línea (icono + nombre de la categoría + descripción de una oración + flecha hacia la derecha).
 *
 * Los elementos (páginas/interruptores en los que se puede hacer clic) están todos en la página de categorías: los 48 elementos originales estaban agrupados en la misma lista de desplazamiento.
 * Las páginas y los interruptores se ven exactamente iguales y los usuarios no pueden distinguir cuál es la entrada y cuál es el estado. Categoría y orden de entrada y título
 * Todo de [SettingsCatalog], el índice de búsqueda también se toma de la misma lista y los tres lugares no se desplazarán por separado.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAccount: () -> Unit = {},
    onOpenGroup: (SettingsGroupKind) -> Unit = {},
    onNavigate: (Any) -> Unit = {},
) {
    val actionHost = rememberSettingsActionHost()
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    SettingsHomeList(
        onBack = onBack,
        onOpenAccount = onOpenAccount,
        onOpenGroup = onOpenGroup,
        onOpenSearch = { isSearching = true },
    )

    if (isSearching) {
        SettingsSearchPalette(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onDismiss = {
                isSearching = false
                searchQuery = ""
            },
            onSelect = { record ->
                isSearching = false
                searchQuery = ""
                when (val action = record.action) {
                    is SettingsEntryAction.Navigate -> onNavigate(action.route)
                    // El switch no tiene una página independiente. Cae a la página de categoría donde se encuentra y el usuario pasa allí.
                    is SettingsEntryAction.Toggle -> onOpenGroup(record.group)
                    is SettingsEntryAction.Action -> actionHost.run(action.id)
                }
            },
        )
    }
}

/**
 * Configure la lista de la página de inicio: título grande + barra de búsqueda de techo + tarjeta de cuenta + 14 filas de categorías.
 *
 * v2.0.1: Título grande (item 0) se inicia con el desplazamiento y la barra de búsqueda (item 1) después de llegar a la cima, absorba el techo; absorber el techo en este momento dentro
 * La barra de búsqueda de color sólido se desvanece, la barra de búsqueda de vidrio exterior (Haze El fondo dentro de la cápsula está borroso) toma el relevo.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SettingsHomeList(
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenGroup: (SettingsGroupKind) -> Unit,
    onOpenSearch: () -> Unit,
) {
    val settingsListState = rememberLazyListState()
    val isSearchBarStuck by remember {
        derivedStateOf { settingsListState.firstVisibleItemIndex >= 1 }
    }
    // Progreso de la transición del techo (0 = color sólido normal,1 = techo de cristal); el espacio interno se comparte con el vidrio exterior para garantizar la sincronización del fundido cruzado.
    val searchStuckProgress by animateFloatAsState(
        targetValue = if (isSearchBarStuck) 1f else 0f,
        animationSpec = MuseMotion.tween(MuseAnimation.FAST_NORMAL_MS),
        label = "searchStuckProgress",
    )
    val surfaceHazeState = remember { HazeState() }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        // v2.0.1: La búsqueda se ha cambiado a una capa flotante sin cambiar la barra de herramientas superior.
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                state = settingsListState,
                modifier = Modifier
                    .fillMaxSize()
                    // La lista mantiene el margen interior de la barra de estado (el título grande se desplaza con la lista y la barra de búsqueda está suspendida debajo de la barra de estado).
                    .museSafeTopInsetPadding()
                    // Como fuente de contenido para el desenfoque de fondo Haze (la barra de búsqueda del vidrio del techo lee el contenido de la lista).
                    .hazeSource(state = surfaceHazeState),
                contentPadding = PaddingValues(
                    // Una vez que se levanta el techo, stickyHeader se separa del flujo de contenido y la barra de marcador de posición ya no tiene espacio.
                    // Los elementos de la lista se desplazarán directamente debajo de la barra de búsqueda. Aquí hay un suplemento según el progreso del techo.
                    // El relleno de igual altura garantiza que el contenido siempre permanezca debajo de la barra de búsqueda.
                    top = innerPadding.calculateTopPadding() +
                        (MusePaddings.searchBarHeight + MusePaddings.contentGap) * searchStuckProgress,
                    bottom = innerPadding.calculateBottomPadding() + MusePaddings.sectionGap,
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                ),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.cardGap),
            ) {
                item(key = "settings_large_header") {
                    MuseLargeTitleHeader(
                        title = stringResource(R.string.settings_screen_title),
                        onBack = onBack,
                    )
                }
                stickyHeader(key = "settings_search_bar") {
                    SettingsStickySearchBar(progress = searchStuckProgress, onOpenSearch = onOpenSearch)
                }
                item(key = "account") {
                    io.zer0.muse.ui.account.AccountCard(
                        onClick = onOpenAccount,
                        modifier = Modifier.padding(horizontal = MusePaddings.screen),
                    )
                }
                item(key = "settings_groups") {
                    SettingsGroupCards(onOpenGroup = onOpenGroup)
                }
            }

            // Barra de búsqueda de vidrio de techo (capa de cobertura)——Tome el control después de desplazarse hasta la parte superior de la lista: solo haga el siguiente contenido dentro de la forma de cápsula
            // fondo borroso (Haze), no hay máscara fuera de la cápsula; la barra de búsqueda de marcador de posición interna se desvanece simultáneamente.
            if (searchStuckProgress > 0.01f) {
                MuseSearchBar(
                    text = stringResource(R.string.settings_search_hint),
                    onClick = onOpenSearch,
                    glass = true,
                    hazeState = surfaceHazeState,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .museSafeTopInsetPadding()
                        // Deje un espacio extra de contenido en la parte inferior para evitar que el borde inferior de la cápsula se pegue al divisor.
                        .padding(
                            start = MusePaddings.screen,
                            end = MusePaddings.screen,
                            top = 6.dp,
                            bottom = MusePaddings.contentGap,
                        )
                        .alpha(searchStuckProgress),
                )
            }
        }
    }
}

/** Barra de búsqueda de marcador de posición: se desplaza con el contenido y es reemplazada por la barra de búsqueda de vidrio exterior una vez que se levanta el techo. */
@Composable
private fun SettingsStickySearchBar(progress: Float, onOpenSearch: () -> Unit) {
    // Cuando se levanta el techo, esta capa se separará del flujo de contenido y los elementos que se encuentran debajo pasarán por debajo de ella, por lo que se necesita una capa de fondo para cubrirla.
    // El fondo solo cubre la altura del cuerpo de la cápsula (excluyendo la posición de respiración superior e inferior de 6 dp) para evitar un espesor excesivo de la leucorrea en la barra superior.
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MusePaddings.searchBarHeight, max = MusePaddings.searchBarHeight)
                .padding(vertical = 6.dp)
                .background(MaterialTheme.colorScheme.background.copy(alpha = progress)),
        )
        MuseSearchBar(
            text = stringResource(R.string.settings_search_hint),
            onClick = onOpenSearch,
            modifier = Modifier
                .padding(horizontal = MusePaddings.screen, vertical = 6.dp)
                .alpha(1f - progress),
        )
    }
}

/**
 * 14 filas de categorías en la página de inicio.
 *
 * Por separado en una función combinada, la prueba puede renderizarla directamente sin DI y buscar en capas flotantes, filas de clasificación de números y filas de clasificación de puntos.
 */
@Composable
internal fun SettingsGroupCards(
    onOpenGroup: (SettingsGroupKind) -> Unit,
    modifier: Modifier = Modifier,
    groups: List<SettingsGroupSpec> = SettingsCatalog.groups,
) {
    CardGroup(modifier = modifier.padding(horizontal = MusePaddings.screen)) {
        groups.forEach { group ->
            item(
                key = group.kind,
                modifier = Modifier.testTag(SETTINGS_GROUP_ROW_TAG),
                onClick = { onOpenGroup(group.kind) },
                leadingContent = { MuseSettingsIcon(group.icon) },
                headlineContent = { Text(stringResource(group.titleRes)) },
                supportingContent = { Text(stringResource(group.subtitleRes)) },
                trailingContent = { ChevronRight() },
            )
        }
    }
}

/**
 * Establecer superposición de búsqueda — Cuadro de búsqueda cápsula + configuración de filtro en tiempo real.
 *
 * La fila de resultados muestra rutas de navegación (categorizadas › entrada): La búsqueda es un escenario en el que "sabes lo que estás buscando" y sólo se proporciona el nombre de la entrada.
 * No puedo decir en qué piso está y no sé adónde iré si hago clic en él.
 */
@Composable
private fun SettingsSearchPalette(
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSelect: (SettingsSearchRecord) -> Unit,
) {
    // El recurso se analiza una vez durante el período de combinación: la lógica de filtrado es una función pura y stringResource no se puede ajustar en ella.
    val groupTitles: Map<SettingsGroupKind, String> = SettingsCatalog.groups.associate { group ->
        group.kind to stringResource(group.titleRes)
    }
    val records = SettingsSearchCatalog.records
    val titles: Map<Int, String> = records.map { it.titleRes }.distinct().associateWith { stringResource(it) }
    val results = filterSettingsSearch(
        records = records,
        query = query,
        titleOf = { titles[it].orEmpty() },
        groupTitleOf = { groupTitles[it].orEmpty() },
    )

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
                    .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.largeGap),
            ) {
                SettingsSearchField(query = query, onQueryChange = onQueryChange)
                if (query.isNotBlank()) {
                    SettingsSearchResults(
                        results = results,
                        titles = titles,
                        groupTitles = groupTitles,
                        onSelect = onSelect,
                    )
                }
            }
        }
    }
}

/** Busque la cápsula de entrada en la parte superior de la superposición; capta el foco y toca el teclado cuando aparece por primera vez. */
@Composable
private fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Dialog Es una ventana independiente y no se puede colocar dentro de un marco. En este momento, requestFocus fallará silenciosamente.
    // Cámbielo a "Esperar cuadro + Reintentar" y luego toque el teclado después de que el foco caiga.
    LaunchedEffect(Unit) {
        repeat(5) { attempt ->
            withFrameNanos { }
            val focused = runCatching { focusRequester.requestFocus() }.isSuccess
            if (focused) {
                keyboard?.show()
                return@LaunchedEffect
            }
            if (attempt > 0) kotlinx.coroutines.delay(40L)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.pill,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
        tonalElevation = 0.dp,
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
                imageVector = MuseIcons.search,
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
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.settings_search_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .padding(vertical = MusePaddings.tightGap),
            )
            if (query.isNotEmpty()) {
                MuseTactileButton(
                    icon = MuseIcons.x,
                    onClick = { onQueryChange("") },
                    contentDescription = stringResource(R.string.command_palette_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    iconSize = MuseIconSizes.iconSmall,
                )
            }
        }
    }
}

/** Tarjeta de resultados de búsqueda: número de visitas + fila de ruta de navegación. */
@Composable
private fun SettingsSearchResults(
    results: List<SettingsSearchRecord>,
    titles: Map<Int, String>,
    groupTitles: Map<SettingsGroupKind, String>,
    onSelect: (SettingsSearchRecord) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        if (results.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_search_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(MusePaddings.contentGap),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                item(key = "count") {
                    Text(
                        text = stringResource(R.string.settings_search_result_count, results.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(
                            horizontal = MusePaddings.contentGap,
                            vertical = MusePaddings.tightGap,
                        ),
                    )
                }
                // key Con subíndices: la lista de entradas es teóricamente única, pero Compose genera directamente una excepción para claves duplicadas.
                // Incluso si se produce una duplicación en el futuro después de agregar subíndices, solo se representarán dos líneas y la aplicación no se eliminará.
                itemsIndexed(
                    items = results,
                    key = { index, record -> index.toString() + record.titleRes + record.group.name },
                ) { _, record ->
                    SettingsSearchResultRow(
                        record = record,
                        title = titles[record.titleRes].orEmpty(),
                        groupTitle = groupTitles[record.group].orEmpty(),
                        onSelect = onSelect,
                    )
                }
            }
        }
    }
}

/** Resultado de búsqueda único: icono + título del artículo + ruta de navegación. */
@Composable
private fun SettingsSearchResultRow(
    record: SettingsSearchRecord,
    title: String,
    groupTitle: String,
    onSelect: (SettingsSearchRecord) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(record) }
            .padding(
                horizontal = MusePaddings.contentGap,
                vertical = MusePaddings.itemGap,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        MuseSettingsIcon(record.icon)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = settingsBreadcrumb(groupTitle = groupTitle, entryTitle = title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
