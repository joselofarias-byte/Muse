package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.LinearScrollGesture
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * v1.61-B: Usando la página del tutorial — Una guía gráfica para principiantes.
 *
 * Trate a los usuarios como novatos que no entienden nada de tecnología.,Explique varias funciones de Muse en un lenguaje sencillo.
 * Dividido en ocho capítulos.,Utilice tarjetas con esquinas redondeadas para cada capítulo.(MuseShapes.large)paquete,Configuración del sistema de alineación de estilos.
 * prohibido emoji,Deshabilite el estilo de bloque nativo de Android.
 *
 * v1.0.16: Agregue una barra vertical para saltar rápidamente de capítulo en el lado derecho,Haga clic en el número de capítulo correspondiente para desplazarse y localizarlo rápidamente.
 *
 * v1.0.17:
 *  - Agregar cuadro de búsqueda en la parte superior(MuseTextField),Ingrese palabras clave para filtrar secciones coincidentes(Título+Texto).
 *  - Las tarjetas de capítulo se pueden plegar/Expandir,Expandir el Capítulo 1 de forma predeterminada,Resto de los pliegues;Para expandir el ícono use MuseIcons.chevronDown/Right.
 *  - La barra de salto se cambia para mostrar la primera palabra del capítulo.(abierto/Partido/dia/alto/un/numero/a menudo),Utilice el estilo blanco y negro de onSurface cuando esté seleccionado.
 *  - Utilice RememberSaveable para guardar el índice del último capítulo visto.,Al ingresar a la página, se desplaza automáticamente a la última posición vista.
 *
 * v2.x Refactorizar(Comentarios de los usuarios"La barra deslizante de la derecha salta una por una y no sigue el contenido."):
 *  - La implementación original de LazyColumn es una para cada capítulo. item(El próximo capítulo es muy largo.),botón indicador"Capítulos visibles"Resalta todo el bloque,
 *    Y haga clic solo para desplazarse hasta la parte superior del capítulo. → El cuerpo se siente abultado y los clics son imprecisos.
 *  - Ahora cambiado a"Encabezado del capítulo + cada sección"son independientes LazyColumn item(lista plana):
 *    · resaltado del indicador = La sección correspondiente al elemento actualmente visible.,Desplázate para seguir continuamente;
 *    · Haga clic en el punto indicador = Desplázate hasta la sección exacta(Los capítulos contraídos se expandirán primero y luego se ubicarán.);
 *    · El indicador en sí migra automáticamente con el desplazamiento.,Mantenga siempre el punto actual en el área visible.
 *  - Sincronización visual simplificada:Tarjeta independiente del encabezado del capítulo;Tarjetas compactas sección por sección,Separación clara.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTutorialPage(onBack: () -> Unit) {
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // Metadatos del capítulo(Icono + título ID de recurso + lista de secciones).
    val chapters = remember {
        listOf(
            TutorialChapterData(
                icon = MuseIcons.rocket,
                titleRes = R.string.settings_tutorial_ch1_title,
                sections = listOf(
                    TutorialSection(R.string.settings_tutorial_ch1_s1_title, R.string.settings_tutorial_ch1_s1_content),
                    TutorialSection(R.string.settings_tutorial_ch1_s2_title, R.string.settings_tutorial_ch1_s2_content),
                    TutorialSection(R.string.settings_tutorial_ch1_s3_title, R.string.settings_tutorial_ch1_s3_content),
                    TutorialSection(R.string.settings_tutorial_ch1_s4_title, R.string.settings_tutorial_ch1_s4_content),
                    TutorialSection(R.string.settings_tutorial_ch1_s5_title, R.string.settings_tutorial_ch1_s5_content),
                    TutorialSection(R.string.settings_tutorial_ch1_s6_title, R.string.settings_tutorial_ch1_s6_content),
                    TutorialSection(R.string.settings_tutorial_ch1_s7_title, R.string.settings_tutorial_ch1_s7_content),
                    TutorialSection(R.string.settings_tutorial_ch1_s8_title, R.string.settings_tutorial_ch1_s8_content),
                ),
            ),
            TutorialChapterData(
                icon = MuseIcons.key,
                titleRes = R.string.settings_tutorial_ch2_title,
                sections = listOf(
                    TutorialSection(R.string.settings_tutorial_ch2_s1_title, R.string.settings_tutorial_ch2_s1_content),
                    TutorialSection(R.string.settings_tutorial_ch2_s2_title, R.string.settings_tutorial_ch2_s2_content),
                    TutorialSection(R.string.settings_tutorial_ch2_s3_title, R.string.settings_tutorial_ch2_s3_content),
                    TutorialSection(R.string.settings_tutorial_ch2_s4_title, R.string.settings_tutorial_ch2_s4_content),
                    TutorialSection(R.string.settings_tutorial_ch2_s5_title, R.string.settings_tutorial_ch2_s5_content),
                    TutorialSection(R.string.settings_tutorial_ch2_s6_title, R.string.settings_tutorial_ch2_s6_content),
                    TutorialSection(R.string.settings_tutorial_ch2_s7_title, R.string.settings_tutorial_ch2_s7_content),
                ),
            ),
            TutorialChapterData(
                icon = MuseIcons.chat,
                titleRes = R.string.settings_tutorial_ch3_title,
                sections = listOf(
                    TutorialSection(R.string.settings_tutorial_ch3_s1_title, R.string.settings_tutorial_ch3_s1_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s2_title, R.string.settings_tutorial_ch3_s2_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s3_title, R.string.settings_tutorial_ch3_s3_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s4_title, R.string.settings_tutorial_ch3_s4_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s5_title, R.string.settings_tutorial_ch3_s5_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s6_title, R.string.settings_tutorial_ch3_s6_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s7_title, R.string.settings_tutorial_ch3_s7_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s8_title, R.string.settings_tutorial_ch3_s8_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s9_title, R.string.settings_tutorial_ch3_s9_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s10_title, R.string.settings_tutorial_ch3_s10_content),
                ),
            ),
            TutorialChapterData(
                icon = MuseIcons.stars,
                titleRes = R.string.settings_tutorial_ch4_title,
                sections = listOf(
                    TutorialSection(R.string.settings_tutorial_ch4_s1_title, R.string.settings_tutorial_ch4_s1_content),
                    TutorialSection(R.string.settings_tutorial_ch4_s2_title, R.string.settings_tutorial_ch4_s2_content),
                    TutorialSection(R.string.settings_tutorial_ch4_s3_title, R.string.settings_tutorial_ch4_s3_content),
                    TutorialSection(R.string.settings_tutorial_ch4_s4_title, R.string.settings_tutorial_ch4_s4_content),
                    TutorialSection(R.string.settings_tutorial_ch4_s5_title, R.string.settings_tutorial_ch4_s5_content),
                    TutorialSection(R.string.settings_tutorial_ch4_s6_title, R.string.settings_tutorial_ch4_s6_content),
                    TutorialSection(R.string.settings_tutorial_ch4_s7_title, R.string.settings_tutorial_ch4_s7_content),
                    TutorialSection(R.string.settings_tutorial_ch4_s8_title, R.string.settings_tutorial_ch4_s8_content),
                    TutorialSection(R.string.settings_tutorial_ch4_s9_title, R.string.settings_tutorial_ch4_s9_content),
                    TutorialSection(R.string.settings_tutorial_ch4_s10_title, R.string.settings_tutorial_ch4_s10_content),
                    TutorialSection(R.string.settings_tutorial_ch4_s11_title, R.string.settings_tutorial_ch4_s11_content),
                    TutorialSection(R.string.settings_tutorial_ch4_s12_title, R.string.settings_tutorial_ch4_s12_content),
                    TutorialSection(R.string.settings_tutorial_ch4_s13_title, R.string.settings_tutorial_ch4_s13_content),
                ),
            ),
            TutorialChapterData(
                icon = MuseIcons.palette,
                titleRes = R.string.settings_tutorial_ch5_title,
                sections = listOf(
                    TutorialSection(R.string.settings_tutorial_ch5_s1_title, R.string.settings_tutorial_ch5_s1_content),
                    TutorialSection(R.string.settings_tutorial_ch5_s2_title, R.string.settings_tutorial_ch5_s2_content),
                    TutorialSection(R.string.settings_tutorial_ch5_s3_title, R.string.settings_tutorial_ch5_s3_content),
                    TutorialSection(R.string.settings_tutorial_ch5_s4_title, R.string.settings_tutorial_ch5_s4_content),
                    TutorialSection(R.string.settings_tutorial_ch5_s5_title, R.string.settings_tutorial_ch5_s5_content),
                ),
            ),
            TutorialChapterData(
                icon = MuseIcons.database,
                titleRes = R.string.settings_tutorial_ch6_title,
                sections = listOf(
                    TutorialSection(R.string.settings_tutorial_ch6_s1_title, R.string.settings_tutorial_ch6_s1_content),
                    TutorialSection(R.string.settings_tutorial_ch6_s2_title, R.string.settings_tutorial_ch6_s2_content),
                    TutorialSection(R.string.settings_tutorial_ch6_s3_title, R.string.settings_tutorial_ch6_s3_content),
                    TutorialSection(R.string.settings_tutorial_ch6_s4_title, R.string.settings_tutorial_ch6_s4_content),
                    TutorialSection(R.string.settings_tutorial_ch6_s5_title, R.string.settings_tutorial_ch6_s5_content),
                ),
            ),
            TutorialChapterData(
                icon = MuseIcons.help,
                titleRes = R.string.settings_tutorial_ch7_title,
                sections = listOf(
                    TutorialSection(R.string.settings_tutorial_ch7_s1_title, R.string.settings_tutorial_ch7_s1_content),
                    TutorialSection(R.string.settings_tutorial_ch7_s2_title, R.string.settings_tutorial_ch7_s2_content),
                    TutorialSection(R.string.settings_tutorial_ch7_s3_title, R.string.settings_tutorial_ch7_s3_content),
                    TutorialSection(R.string.settings_tutorial_ch7_s4_title, R.string.settings_tutorial_ch7_s4_content),
                    TutorialSection(R.string.settings_tutorial_ch7_s5_title, R.string.settings_tutorial_ch7_s5_content),
                    TutorialSection(R.string.settings_tutorial_ch7_s6_title, R.string.settings_tutorial_ch7_s6_content),
                ),
            ),
            TutorialChapterData(
                icon = MuseIcons.plug,
                titleRes = R.string.settings_tutorial_ch8_title,
                sections = listOf(
                    TutorialSection(R.string.settings_tutorial_ch8_s1_title, R.string.settings_tutorial_ch8_s1_content),
                    TutorialSection(R.string.settings_tutorial_ch8_s2_title, R.string.settings_tutorial_ch8_s2_content),
                    TutorialSection(R.string.settings_tutorial_ch8_s3_title, R.string.settings_tutorial_ch8_s3_content),
                    TutorialSection(R.string.settings_tutorial_ch8_s4_title, R.string.settings_tutorial_ch8_s4_content),
                    TutorialSection(R.string.settings_tutorial_ch8_s5_title, R.string.settings_tutorial_ch8_s5_content),
                    TutorialSection(R.string.settings_tutorial_ch8_s6_title, R.string.settings_tutorial_ch8_s6_content),
                ),
            ),
        )
    }
    val chapterCount = chapters.size

    // Precargar todas las cadenas de sección(Título+Texto),Se utiliza para el filtrado de búsqueda.
    val searchableSections = remember(context, chapters) {
        chapters.flatMapIndexed { chapterIndex, chapter ->
            chapter.sections.map { section ->
                SearchableSection(
                    chapterIndex = chapterIndex,
                    chapterTitle = context.getString(chapter.titleRes),
                    chapterIcon = chapter.icon,
                    sectionTitle = context.getString(section.titleRes),
                    content = context.getString(section.contentRes),
                )
            }
        }
    }

    // estado de búsqueda(Válido dentro de esta sesión,Restablecer al salir de la página).
    var searchQuery by remember { mutableStateOf("") }
    val isSearching = searchQuery.isNotBlank()

    // Estado de expansión del capítulo:Expandir el Capítulo 1 de forma predeterminada,Dobla el resto.
    var expandedChapters by remember { mutableStateOf<Set<Int>>(setOf(0)) }

    // Índice del último capítulo visto(Persistencia entre sesiones).
    var lastViewedChapter by rememberSaveable { mutableStateOf(0) }

    // Resultados del filtro de búsqueda.
    val filteredSections = if (isSearching) {
        searchableSections.filter { section ->
            section.sectionTitle.contains(searchQuery, ignoreCase = true) ||
                section.content.contains(searchQuery, ignoreCase = true)
        }
    } else {
        emptyList()
    }

    // v2.x: Lista de elementos planos(Encabezado del capítulo + secciones para ampliar el capítulo.),Para renderizado de mosaicos LazyColumn.
    val flatItems = remember(chapters, expandedChapters) {
        buildList {
            chapters.forEachIndexed { ci, ch ->
                add(TutorialFlatItem.ChapterHeader(ci))
                if (ci in expandedChapters) {
                    ch.sections.indices.forEach { si -> add(TutorialFlatItem.Section(ci, si)) }
                }
            }
        }
    }

    // Desplácese automáticamente al último capítulo visto al ingresar a la página(v2.x: Localice el encabezado del capítulo. flat index).
    LaunchedEffect(Unit) {
        if (!isSearching && lastViewedChapter in 1 until chapterCount) {
            val headerIndex = flatItems.indexOfFirst {
                it is TutorialFlatItem.ChapterHeader && it.chapterIndex == lastViewedChapter
            }
            if (headerIndex >= 0) listState.scrollToItem(headerIndex)
        }
    }

    // Monitorear los capítulos actualmente visibles,Guardar índice persistentemente(No actualiza al buscar,evitar la contaminación).
    LaunchedEffect(listState, isSearching, flatItems) {
        if (!isSearching) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .collect { itemIndex ->
                    val item = flatItems.getOrNull(itemIndex.coerceIn(0, (flatItems.size - 1).coerceAtLeast(0)))
                    val chapterIndex = when (item) {
                        is TutorialFlatItem.ChapterHeader -> item.chapterIndex
                        is TutorialFlatItem.Section -> item.chapterIndex
                        else -> lastViewedChapter
                    }.coerceIn(0, chapterCount - 1)
                    if (chapterIndex != lastViewedChapter) {
                        lastViewedChapter = chapterIndex
                    }
                }
        }
    }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.settings_tutorial_title),
                onBack = onBack,
                largeTitle = true,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding(),
        ) {
            // lado izquierdo:Lista de contenido del capítulo
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = MusePaddings.screen),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + MusePaddings.screen,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // cuadro de búsqueda superior — siempre como elemento 0
                item {
                    MuseTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.settings_tutorial_search_placeholder),
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
                                    onClick = { searchQuery = "" },
                                    contentDescription = stringResource(R.string.quick_notes_clear_search),
                                    iconSize = MuseIconSizes.iconSmall,
                                )
                            }
                        } else {
                            null
                        },
                        singleLine = true,
                    )
                }

                if (isSearching) {
                    // Vista de resultados de búsqueda — Lista de secciones planas
                    if (filteredSections.isEmpty()) {
                        item {
                            Surface(
                                shape = MuseShapes.large,
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_tutorial_search_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    } else {
                        items(filteredSections, key = { "${it.chapterIndex}:${it.sectionTitle}" }) { section ->
                            SearchResultCard(section = section)
                        }
                    }
                } else {
                    // Vista completa del capítulo — v2.x: Azulejos a barras(Encabezado de capítulo + sección independiente por sección item),
                    // El indicador puede seguir continuamente el desplazamiento y hacer clic con precisión en el nudo.
                    items(flatItems, key = { it.key }) { item ->
                        when (item) {
                            is TutorialFlatItem.ChapterHeader -> {
                                val chapter = chapters[item.chapterIndex]
                                TutorialChapterHeader(
                                    icon = chapter.icon,
                                    titleRes = chapter.titleRes,
                                    isExpanded = item.chapterIndex in expandedChapters,
                                    onToggleExpand = {
                                        expandedChapters = if (item.chapterIndex in expandedChapters) {
                                            expandedChapters - item.chapterIndex
                                        } else {
                                            expandedChapters + item.chapterIndex
                                        }
                                    },
                                )
                            }
                            is TutorialFlatItem.Section -> {
                                val section = chapters[item.chapterIndex].sections[item.sectionIndex]
                                TutorialSectionCard(
                                    titleRes = section.titleRes,
                                    contentRes = section.contentRes,
                                )
                            }
                        }
                    }
                }
            }

            // v2.5.9: zona caliente derecha — Mantenga presionado para activar y luego deslice hacia arriba y hacia abajo para desplazarse linealmente (no se dibujan más pistas visuales).
            if (!isSearching) {
                LinearScrollGesture(
                    listState = listState,
                    modifier =
                    Modifier
                        .fillMaxHeight()
                        .padding(end = 4.dp, top = innerPadding.calculateTopPadding()),
                )
            }
        }
    }
}

/**
 * v2.x: Tarjeta de encabezado del capítulo del tutorial — Icono + título + estado expandido. Elemento LazyColumn independiente.
 */
@Composable
private fun TutorialChapterHeader(icon: ImageVector, titleRes: Int, isExpanded: Boolean, onToggleExpand: () -> Unit) {
    val title = stringResource(titleRes)
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = if (isExpanded) MuseIcons.chevronDown else MuseIcons.chevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * v2.x: Tarjetas de sección de tutoriales — Título(color principal) + Texto. Elemento LazyColumn independiente.
 */
@Composable
private fun TutorialSectionCard(titleRes: Int, contentRes: Int) {
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(contentRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * v1.0.17: Tarjeta de resultados de búsqueda — Visualización plana de secciones coincidentes.(Incluir títulos de capítulos como contexto.).
 */
@Composable
private fun SearchResultCard(section: SearchableSection) {
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = section.chapterIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = section.chapterTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = section.sectionTitle,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = section.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** v2.x: Elementos de lista plana de la página de tutorial(Encabezado/sección del capítulo). */
private sealed interface TutorialFlatItem {
    val key: String

    data class ChapterHeader(val chapterIndex: Int) : TutorialFlatItem {
        override val key: String get() = "tutorial_ch_$chapterIndex"
    }

    data class Section(val chapterIndex: Int, val sectionIndex: Int) : TutorialFlatItem {
        override val key: String get() = "tutorial_sec_${chapterIndex}_$sectionIndex"
    }
}

/** Sección de tutoriales — ID del recurso del título + ID del recurso del cuerpo. */
private class TutorialSection(
    val titleRes: Int,
    val contentRes: Int,
)

/** v1.0.17: Metadatos del capítulo — Icono + recurso de título + lista de secciones. */
private class TutorialChapterData(
    val icon: ImageVector,
    val titleRes: Int,
    val sections: List<TutorialSection>,
)

/** v1.0.17: Secciones indexables para búsqueda. — Cadena cargada,El filtrado que contiene se puede realizar directamente. */
private class SearchableSection(
    val chapterIndex: Int,
    val chapterTitle: String,
    val chapterIcon: ImageVector,
    val sectionTitle: String,
    val content: String,
)
