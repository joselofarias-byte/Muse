@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod", "TooManyFunctions", "ReturnCount", "TooGenericExceptionCaught", "SwallowedException", "MaxLineLength")
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.zer0.muse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseActionSheetRow
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.markdown.MarkdownText
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Barra de búsqueda ranurada — Surface + BasicTextField,surfaceVariant fondo,Esquinas redondeadas.
 */
@Composable
internal fun MemorySearchBar(query: String, onQueryChange: (String) -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.screen, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = MuseIcons.search,
                contentDescription = stringResource(R.string.memory_screen_search_cd),
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.memory_screen_search_fact_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
    }
}

/**
 * v9: Filtrar elementos de la memoria por categoría(null=Todos).
 */
internal fun filterMemoryItemsByCategory(items: List<MemoryItem>, category: String?): List<MemoryItem> {
    return if (category == null) {
        items
    } else {
        items.filter {
            it.category.equals(category, ignoreCase = true)
        }
    }
}

internal fun isToday(createdAt: String?): Boolean {
    if (createdAt == null) return false
    return try {
        val date = java.time.Instant.parse(createdAt)
        val today = java.time.LocalDate.now()
        val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        itemDate == today
    } catch (e: Exception) {
        false
    }
}

internal fun isThisWeek(createdAt: String?): Boolean {
    if (createdAt == null) return false
    return try {
        val date = java.time.Instant.parse(createdAt)
        val today = java.time.LocalDate.now()
        val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val weekStart = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        !itemDate.isBefore(weekStart) && !itemDate.isAfter(today)
    } catch (e: Exception) {
        false
    }
}

internal fun isThisMonth(createdAt: String?): Boolean {
    if (createdAt == null) return false
    return try {
        val date = java.time.Instant.parse(createdAt)
        val today = java.time.LocalDate.now()
        val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        itemDate.year == today.year && itemDate.month == today.month
    } catch (e: Exception) {
        false
    }
}

/**
 * v9: Clasificar y filtrar cápsulas — Todo/Datos fundamentales/Preferencias/Experiencias/Relaciones/Objetivos/Médico.
 *
 * Estilo de dibujo de referencia:Desplazamiento horizontal,Utilice la pastilla oscura inverseSurface para artículos seleccionados,Los colores no seleccionados son gris claro.
 */
@Composable
internal fun CategoryFilterChipRow(selectedCategory: String?, onSelect: (String?) -> Unit) {
    val scrollState = rememberScrollState()
    val categories = listOf(
        null to stringResource(R.string.memory_category_all),
        "identity" to stringResource(R.string.memory_category_identity),
        "preference" to stringResource(R.string.memory_category_preference),
        "event" to stringResource(R.string.memory_category_event),
        "relationship" to stringResource(R.string.memory_category_relationship),
        "goal" to stringResource(R.string.memory_category_goal),
        "medical" to stringResource(R.string.memory_category_medical),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = MusePaddings.screen, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        categories.forEach { (category, label) ->
            val selected = selectedCategory == category
            CategoryChip(
                label = label,
                selected = selected,
                onClick = { onSelect(category) },
            )
        }
    }
}

@Composable
internal fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MuseShapes.pill,
        color = if (selected) {
            MaterialTheme.colorScheme.inverseSurface
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.inverseOnSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * v8: Línea de filtro de alcance — Desplace MuseChip hacia los lados.
 */
@Composable
internal fun ScopeFilterChipRow(options: List<ScopeOption>, selectedScope: String?, onSelect: (String?) -> Unit) {
    val scrollState = rememberScrollState()

    Text(
        text = stringResource(R.string.memory_scope_label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = 2.dp),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = MusePaddings.screen, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val isSelected = selectedScope == option.id
            MuseChip(
                selected = isSelected,
                onClick = { onSelect(option.id) },
                label = option.displayName,
            )
        }
    }
}

/**
 * v8: La insignia de alcance de un único elemento de hecho.
 */
@Composable
internal fun ScopeBadge(scope: String?) {
    if (scope.isNullOrBlank() || scope == "main") return
    val label = stringResource(R.string.memory_scope_assistant) + " · " + scope.take(6)
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * Lista de resultados de búsqueda — CardGroup recipiente.
 */
@Composable
internal fun SearchResultsList(
    results: List<MemoryItem>,
    isSearching: Boolean,
    onDelete: (String) -> Unit,
    headerContent: @Composable () -> Unit = {},
) {
    if (isSearching) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(MusePaddings.emptyStateGap),
            contentAlignment = Alignment.Center,
        ) {
            MuseSpinner(
                size = 24.dp,
            )
        }
        return
    }
    if (results.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(MusePaddings.emptyStateGap),
            contentAlignment = Alignment.Center,
        ) {
            // ST-03: unidad del estado vacío MuseEmptyState
            MuseEmptyState(title = stringResource(R.string.memory_screen_no_match))
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = MusePaddings.screen,
            vertical = MusePaddings.contentGap,
        ),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        item { headerContent() }
        item {
            Text(
                text = stringResource(R.string.memory_screen_search_results, results.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Correcciones en la interfaz (Rendimiento-2): Los resultados de la búsqueda cambiaron de la representación de columna completa de CardGroup a
        // LazyColumn nivel superior items(key=id) Azulejo,Representación de carga diferida;Cada fila de tarjetas independientes con esquinas redondeadas
        // (El diseño alinea la estructura de título/soporte/final del elemento CardGroup original.).
        itemsIndexed(results, key = { _, item -> item.id }) { _, item ->
            MuseSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = MuseShapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                elevation = MuseElevation.card,
                tonalElevation = MuseElevation.none,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        MemoryRowHeadline(item)
                        MemoryRowSupporting(item)
                    }
                    Spacer(Modifier.width(MusePaddings.contentGap))
                    MemoryRowTrailing(
                        item = item,
                        onDelete = { onDelete(item.id) },
                        onEdit = null,
                        onSetImportance = null,
                    )
                }
            }
        }
    }
}

/**
 * v9: Mostrar elementos de la memoria agrupados por categoría(Estilo de dibujo de referencia).
 *
 * Cuando no se selecciona ninguna categoría específica,Agrupar por categoría,Cada grupo tiene un subtítulo + mazo de cartas independiente.
 * Cuando se selecciona una categoría,Las entradas de esta categoría se muestran directamente,Los títulos de grupo ya no se repiten.
 */
internal fun LazyListScope.categoryGroupedMemoryListItems(
    items: List<MemoryItem>,
    selectedCategory: String?,
    onDelete: (String) -> Unit,
    onEdit: (MemoryItem) -> Unit,
    onSetImportance: (MemoryItem) -> Unit,
    onTogglePin: (MemoryItem) -> Unit,
) {
    if (items.isEmpty()) {
        item {
            EmptyHint(text = stringResource(R.string.memory_screen_no_fact))
        }
        return
    }

    // Orden de clasificación(Según la imagen de referencia.):identity / preference / event / relationship / goal / medical / other
    val categoryOrder = listOf("identity", "preference", "event", "relationship", "goal", "medical", "other")

    // Cuando se alcanza la categoría seleccionada,Colocar la lista en mosaico directamente
    if (selectedCategory != null) {
        // Correcciones en la interfaz (Rendimiento-2): por item { MemoryCardGroup } La representación completa cambió a mosaicos de nivel superior
        memoryCardGroupItems(
            items = items,
            onDelete = onDelete,
            onEdit = onEdit,
            onSetImportance = onSetImportance,
            onTogglePin = onTogglePin,
        )
        return
    }

    // Ninguna categoría seleccionada:Agrupar por categoría
    val grouped = items.groupBy {
        it.category?.lowercase()?.takeIf { c -> categoryOrder.contains(c) } ?: "other"
    }

    categoryOrder.forEach { category ->
        val groupItems = grouped[category] ?: emptyList()
        if (groupItems.isEmpty()) return@forEach

        item {
            CategorySectionHeader(
                title = categoryDisplayName(category),
                count = groupItems.size,
            )
        }
        // Correcciones en la interfaz (Rendimiento-2): dentro del grupo por item { MemoryCardGroup } La representación completa cambió a mosaicos de nivel superior
        memoryCardGroupItems(
            items = groupItems,
            onDelete = onDelete,
            onEdit = onEdit,
            onSetImportance = onSetImportance,
            onTogglePin = onTogglePin,
        )
    }
}

@Composable
internal fun categoryDisplayName(category: String): String {
    return when (category) {
        "identity" -> stringResource(R.string.memory_category_identity)
        "preference" -> stringResource(R.string.memory_category_preference)
        "event" -> stringResource(R.string.memory_category_event)
        "relationship" -> stringResource(R.string.memory_category_relationship)
        "goal" -> stringResource(R.string.memory_category_goal)
        "medical" -> stringResource(R.string.memory_category_medical)
        else -> stringResource(R.string.memory_category_other)
    }
}

/**
 * Título del grupo de clasificación — centro/Texto pequeño justificado a la izquierda.
 */
@Composable
internal fun CategorySectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // LAYOUT-01: El título del grupo toma el ancho restante (el recuento aún está fijo a la derecha) y ya no se ajusta palabra por palabra cuando se amplía el tamaño de fuente.
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // LAYOUT-01: El recuento es un número cuantitativo al final de la línea. Solo se limita a la línea y no toma el ancho (el ancho lo empujará desde el borde derecho hasta el centro de la línea)
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
        )
    }
}

/**
 * Correcciones en la interfaz (Rendimiento-2): Agrupación de filas de memoria — Se cambió de la representación completa de @Composable Column a
 * LazyListScope items(key=id) Azulejo superior,Representación de carga diferida.
 * Cada fila de tarjetas independientes con esquinas redondeadas(Reemplaza el juego completo original de tarjetas con esquinas redondeadas + interior. divider).
 */
internal fun LazyListScope.memoryCardGroupItems(
    items: List<MemoryItem>,
    onDelete: (String) -> Unit,
    onEdit: (MemoryItem) -> Unit,
    onSetImportance: (MemoryItem) -> Unit,
    onTogglePin: (MemoryItem) -> Unit,
) {
    itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
        MuseSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            elevation = MuseElevation.card,
            tonalElevation = MuseElevation.none,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            MemoryFactRow(
                item = item,
                onDelete = { onDelete(item.id) },
                onEdit = onEdit,
                onSetImportance = onSetImportance,
                onTogglePin = onTogglePin,
            )
        }
    }
}

/**
 * v1.x: sola línea de memoria — Ancho de texto completo(Se puede mostrar completamente con saltos de línea.) + meta OK + ⋮ Menú de operación.
 * Operación(fijar hacia arriba/importancia/Editar/Eliminar)En el menú,No más desplazamiento del texto principal.
 */
@Composable
private fun MemoryFactRow(
    item: MemoryItem,
    onDelete: () -> Unit,
    onEdit: (MemoryItem) -> Unit,
    onSetImportance: (MemoryItem) -> Unit,
    onTogglePin: (MemoryItem) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = MusePaddings.screen, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = buildMetaText(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (item.importance > 0) {
                    ImportanceTag(importance = item.importance)
                }
            }
        }
        Box {
            MuseTactileButton(
                icon = MuseIcons.moreVertical,
                onClick = { menuOpen = true },
                contentDescription = stringResource(R.string.memory_menu_cd),
                tint = MaterialTheme.colorScheme.outline,
                iconSize = 20.dp,
            )
            MuseAnchoredMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                MuseListItem(
                    onClick = {
                        menuOpen = false
                        onTogglePin(item)
                    },
                    headlineContent = {
                        Text(
                            stringResource(if (item.pinnedAt == null) R.string.memory_menu_pin else R.string.memory_menu_unpin),
                        )
                    },
                )
                MuseListItem(
                    onClick = {
                        menuOpen = false
                        onSetImportance(item)
                    },
                    headlineContent = { Text(stringResource(R.string.memory_menu_importance)) },
                )
                MuseListItem(
                    onClick = {
                        menuOpen = false
                        onEdit(item)
                    },
                    headlineContent = { Text(stringResource(R.string.memory_menu_edit)) },
                )
                MuseListItem(
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                    headlineContent = {
                        Text(
                            stringResource(R.string.memory_menu_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }
        }
    }
}

/**
 * Contenido principal de la tarjeta de memoria — Consistente con la imagen de referencia.:Texto + fecha/Alcance meta + etiqueta de importancia.
 */
@Composable
internal fun MemoryCardContent(item: MemoryItem) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = item.content,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = buildMetaText(item),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            if (item.importance > 0) {
                ImportanceTag(importance = item.importance)
            }
        }
    }
}

@Composable
internal fun buildMetaText(item: MemoryItem): String {
    val dateText = item.time?.take(10)
        ?: item.createdAt?.take(10)
        ?: ""
    val scopeText = if (!item.scope.isNullOrBlank() && item.scope != "main") {
        stringResource(R.string.memory_scope_assistant) + " · " + item.scope.take(6)
    } else {
        ""
    }
    // rastreable:completar una línea“éxitos recientes”,Permitir que el usuario vea si la memoria todavía está viva o no.
    val hitText = daysSinceIso(item.lastHitAt)?.let { days ->
        stringResource(R.string.memory_meta_last_hit, days)
    } ?: ""
    val countText = if (item.hitCount > 0) {
        stringResource(R.string.memory_meta_hit_count, item.hitCount)
    } else {
        ""
    }
    val parts = listOf(dateText, scopeText, hitText, countText).filter { it.isNotBlank() }
    return parts.joinToString(" · ")
}

/**
 * Asistencia de trazabilidad:ISO tiempo → Número de días desde hoy(No se puede analizar la devolución null).
 * Sólo para visualización,No involucrado en ningún juicio.
 */
internal fun daysSinceIso(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return runCatching {
        val day = java.time.LocalDate.parse(iso.take(10))
        java.time.temporal.ChronoUnit.DAYS.between(day, java.time.LocalDate.now())
    }.getOrNull()
}

/**
 * etiqueta de importancia(Verde a la derecha/pastilla roja).
 */
@Composable
internal fun ImportanceTag(importance: Int) {
    if (importance <= 0) return
    val text = if (importance >= 2) {
        stringResource(R.string.memory_importance_critical)
    } else {
        stringResource(R.string.memory_importance_important)
    }
    val color = if (importance >= 2) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = MuseShapes.pill,
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * Área de operación de la tarjeta de memoria — Editar/eliminar/establecer importancia.
 */
@Composable
internal fun MemoryCardTrailing(
    item: MemoryItem,
    onDelete: (() -> Unit)?,
    onEdit: ((MemoryItem) -> Unit)?,
    onSetImportance: ((MemoryItem) -> Unit)?,
    onTogglePin: ((MemoryItem) -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onSetImportance != null) {
            MuseTactileButton(
                icon = MuseIcons.star,
                onClick = { onSetImportance(item) },
                contentDescription = stringResource(R.string.memory_sync_set_importance_cd),
                tint = when (item.importance) {
                    2 -> MaterialTheme.colorScheme.error
                    1 -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.outline
                },
                iconSize = 20.dp,
            )
        }
        if (onEdit != null) {
            if (onTogglePin != null) {
                MuseTactileButton(
                    icon = MuseIcons.pin,
                    onClick = { onTogglePin(item) },
                    contentDescription = stringResource(
                        if (item.pinnedAt == null) R.string.memory_pin_cd else R.string.memory_unpin_cd,
                    ),
                    tint = if (item.pinnedAt != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    iconSize = 20.dp,
                )
            }
            MuseTactileButton(
                icon = MuseIcons.edit,
                onClick = { onEdit(item) },
                contentDescription = stringResource(R.string.memory_screen_edit_cd),
                tint = MaterialTheme.colorScheme.outline,
                iconSize = 20.dp,
            )
        }
        if (onDelete != null) {
            MuseTactileButton(
                icon = MuseIcons.trash,
                onClick = { onDelete() },
                contentDescription = stringResource(R.string.memory_screen_delete_cd),
                tint = MaterialTheme.colorScheme.outline,
                iconSize = 20.dp,
            )
        }
    }
}

/**
 * Área de título de la línea de memoria — Insignia de importancia + título.
 */
@Composable
internal fun MemoryRowHeadline(item: MemoryItem) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (item.importance > 0) {
            ImportanceBadge(importance = item.importance)
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Área de contenido de la línea de memoria — markdown contenido + etiquetas/tiempo/Meta alcance.
 */
@Composable
internal fun MemoryRowSupporting(item: MemoryItem) {
    Column {
        if (item.content.isNotBlank()) {
            Spacer(Modifier.size(4.dp))
            MarkdownText(
                text = item.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val showScopeBadge = !item.scope.isNullOrBlank() && item.scope != "main"
        if (item.tags.isNotEmpty() || item.time != null || showScopeBadge) {
            Spacer(Modifier.size(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.tags.forEach { tag ->
                    Text(
                        text = "#$tag",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (item.time != null) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = item.time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                ScopeBadge(scope = item.scope)
            }
        }
        val createdAtText = formatCreatedAtText(item.createdAt)
        if ((item.source == "Fact" && item.sessionId != null) || createdAtText != null) {
            Spacer(Modifier.size(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (item.source == "Fact" && item.sessionId != null) {
                    Text(
                        text = stringResource(R.string.memory_source_session, item.sessionId.take(8)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (createdAtText != null) {
                    Text(
                        text = createdAtText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/**
 * Área de operación de la línea de memoria — Establecer importancia/editar/eliminar.
 */
@Composable
internal fun MemoryRowTrailing(
    item: MemoryItem,
    onDelete: ((MemoryItem) -> Unit)?,
    onEdit: ((MemoryItem) -> Unit)?,
    onSetImportance: ((MemoryItem) -> Unit)?,
) {
    // v2.x unificación: ≥2 Convergir al menú "Más" al realizar acciones dentro de una fila(Evite que las columnas de texto de la pantalla estrechas sean comprimidas por varios botones)
    val moreCd = stringResource(R.string.settings_mcp_more)
    var menuExpanded by remember { mutableStateOf(false) }
    val hasMultipleActions = listOfNotNull(onSetImportance, onEdit, onDelete).size >= 2
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!hasMultipleActions && onSetImportance != null) {
            MuseTactileButton(
                icon = MuseIcons.star,
                onClick = { onSetImportance(item) },
                contentDescription = stringResource(R.string.memory_sync_set_importance_cd),
                tint = when (item.importance) {
                    2 -> MaterialTheme.colorScheme.error
                    1 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.outline
                },
                iconSize = 20.dp,
            )
        }
        if (!hasMultipleActions && onEdit != null) {
            MuseTactileButton(
                icon = MuseIcons.edit,
                onClick = { onEdit(item) },
                contentDescription = stringResource(R.string.memory_screen_edit_cd),
                tint = MaterialTheme.colorScheme.outline,
                iconSize = 20.dp,
            )
        }
        if (!hasMultipleActions && onDelete != null) {
            MuseTactileButton(
                icon = MuseIcons.trash,
                onClick = { onDelete(item) },
                contentDescription = stringResource(R.string.memory_screen_delete_cd),
                tint = MaterialTheme.colorScheme.outline,
                iconSize = 20.dp,
            )
        }
        if (hasMultipleActions) {
            MuseTactileButton(
                icon = MuseIcons.moreVertical,
                onClick = { menuExpanded = true },
                contentDescription = moreCd,
                tint = MaterialTheme.colorScheme.outline,
                iconSize = 20.dp,
            )
        }
    }

    if (menuExpanded) {
        MuseDialog(
            onDismissRequest = { menuExpanded = false },
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (onSetImportance != null) {
                        MuseActionSheetRow(
                            icon = MuseIcons.star,
                            text = stringResource(R.string.memory_sync_set_importance_cd),
                            contentColor = when (item.importance) {
                                2 -> MaterialTheme.colorScheme.error
                                1 -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.outline
                            },
                            onClick = {
                                menuExpanded = false
                                onSetImportance(item)
                            },
                        )
                    }
                    if (onEdit != null) {
                        MuseActionSheetRow(
                            icon = MuseIcons.edit,
                            text = stringResource(R.string.memory_screen_edit_cd),
                            onClick = {
                                menuExpanded = false
                                onEdit(item)
                            },
                        )
                    }
                    if (onDelete != null) {
                        MuseActionSheetRow(
                            icon = MuseIcons.trash,
                            text = stringResource(R.string.memory_screen_delete_cd),
                            contentColor = MaterialTheme.colorScheme.error,
                            onClick = {
                                menuExpanded = false
                                onDelete(item)
                            },
                        )
                    }
                }
            },
            confirmText = stringResource(R.string.common_close),
            onConfirm = { menuExpanded = false },
            onDismiss = { menuExpanded = false },
        )
    }
}

/**
 * v4: Insignia de importancia.
 */
@Composable
internal fun ImportanceBadge(importance: Int) {
    if (importance <= 0) return
    val icon = if (importance >= 2) MuseIcons.alertTriangle else MuseIcons.alertCircle
    val text = if (importance >= 2) {
        stringResource(R.string.memory_importance_critical)
    } else {
        stringResource(R.string.memory_importance_important)
    }
    val color = if (importance >= 2) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * v4: Calcule la copia en tiempo relativo según creadoAt.
 */
@Composable
internal fun formatCreatedAtText(createdAt: String?): String? {
    if (createdAt == null) return null
    val days = try {
        Duration.between(Instant.parse(createdAt), Instant.now()).toDays()
    } catch (e: Exception) {
        return null
    }
    return when {
        days <= 0L -> stringResource(R.string.memory_created_today)
        days == 1L -> stringResource(R.string.memory_created_yesterday)
        else -> stringResource(R.string.memory_created_days_ago, days.toInt())
    }
}

/**
 * Cuadro de visualización de la pila de errores.
 */
@Composable
internal fun ErrorTraceBox(trace: String, onRetry: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MusePaddings.cardInnerSpaced),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // LAYOUT-01: El título del error ocupa el ancho restante (solo un botón de copia de ancho fijo a la derecha)
            Text(
                text = stringResource(R.string.memory_screen_load_failed),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            MuseTactileButton(
                icon = MuseIcons.copy,
                onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("Muse Error Trace", trace),
                    )
                    MuseToast.show(context.getString(R.string.memory_screen_copied_trace))
                },
                contentDescription = stringResource(R.string.memory_screen_copy_trace_cd),
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
            shape = MuseShapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Text(
                text = trace,
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .padding(MusePaddings.itemGap),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = MuseMonoFontFamily,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        MuseCapsuleButton(
            text = stringResource(R.string.memory_screen_retry),
            onClick = onRetry,
            leadingIcon = MuseIcons.refresh,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
    }
}

/**
 * Mensaje de estado vacío.
 */
@Composable
internal fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.largeGap),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}
