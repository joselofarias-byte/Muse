package io.zer0.muse.ui.stats

import android.content.Context
import android.content.Intent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.museAnimateItem
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseScaffold
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge
import org.koin.androidx.compose.koinViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * v2.2: Página de estadísticas — Refactorización del estilo del tablero.
 *
 * Puntos clave de la refactorización:
 *  - Encabezado del panel:números grandes(Número total de mensajes 36sp Bold)+ Subtítulo + botón Exportar
 *  - Cuadrícula de métricas clave:2 OK × 3 Columna que muestra 6 indicadores.(dialogo total/noticias generales/promedio diario/más activo/este mes/dias consecutivos)
 *  - Filtro de rango de tiempo:Sólo afecta a la cuadrícula de métricas clave.(semántica clara,Mantenga otras tarjetas en su totalidad)
 *  - mapa de calor:Lienzo único + dibujo drawRoundRect(Reemplazado 371 Box),Haga clic en soporte tooltip
 *  - Gráfico de tendencias:7/30/90 Cambio de ventana de día,Gráfico de líneas + línea promedio(línea discontinua) + resaltado final
 *  - Distribución horaria:Canvas 24 histograma raíz,Utilice color primario para los picos.
 *  - Proporción de asistente:Canvas gráfico de anillos(donut),Sistema de color en escala de grises
 *  - Top modelo:Top 5 + "Ver todo"Expandir,Barra de progreso en escala de grises
 *  - estado de datos vacíos:Icono + Consejo + Botón de acción
 *  - unidad visual:Todas las tarjetas MuseShapes.extraLarge,texto onSurface/onSurfaceVariant,
 *    Gráfico en escala de grises(surfaceVariant/onSurfaceVariant/outline),Para uso máximo primary
 *  - para todos los iconos TablerIcons,Tinte de icono para onSurface/onSurfaceVariant
 */
@Composable
fun StatsScreen(onBack: () -> Unit, onOpenSession: (String) -> Unit = {}, viewModel: StatsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    MuseScaffold(
        topBar = {
            DashboardHeader(
                state = state,
                onBack = onBack,
                onExport = { shareStatsSummary(context, state) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        // v2.x: Finalización del efecto de movimiento — Cargar/Estado vacío/El cambio de contenido en tres estados requiere que el token aparezca y desaparezca(Misma especificación Crossfade que ChatScreen)
        val statsContentState = when {
            state.isLoading -> 0
            state.totalMessages == 0 && state.totalSessions == 0 -> 1
            else -> 2
        }
        Crossfade(
            targetState = statsContentState,
            animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS),
            label = "statsContent",
            modifier = Modifier.fillMaxSize(),
        ) { kind ->
            when (kind) {
                0 -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        MuseSpinner()
                    }
                }
                1 -> {
                    EmptyStatsState(
                        onAction = onBack,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = innerPadding.calculateTopPadding(),
                            bottom = innerPadding.calculateBottomPadding() + 16.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
                    ) {
                        // 1. Filtro de rango de tiempo(Sólo afecta la cuadrícula de indicadores a continuación)
                        // v2.x: Finalización del efecto de movimiento — admisión con tarjeta(ficha animateItem)
                        item(key = "time_range_filter") {
                            Box(museAnimateItem()) {
                                TimeRangeFilterRow(
                                    currentRange = state.timeRange,
                                    onRangeChange = { viewModel.setTimeRange(it) },
                                    modifier = Modifier.padding(horizontal = MusePaddings.screen),
                                )
                            }
                        }

                        // 2. Cuadrícula de métricas clave 2×3(respuesta timeRange)
                        // v2.x: Finalización del efecto de movimiento — admisión con tarjeta(ficha animateItem)
                        item(key = "metrics_grid") {
                            Box(museAnimateItem()) {
                                KeyMetricsGrid(
                                    state = state,
                                    modifier = Modifier.padding(horizontal = MusePaddings.screen),
                                )
                            }
                        }

                        // 3. Mapa de calor de actividad(soltero Canvas,historia completa)
                        // v2.x: Finalización del efecto de movimiento — admisión con tarjeta(ficha animateItem)
                        item(key = "heatmap") {
                            Box(museAnimateItem()) {
                                HeatmapCard(
                                    messagesPerDay = state.messagesPerDay,
                                    modifier = Modifier.padding(horizontal = MusePaddings.screen),
                                )
                            }
                        }

                        // 4. tendencia activa(7/30/90 ventana del cielo,Polilínea + línea media)
                        // v2.x: Finalización del efecto de movimiento — admisión con tarjeta(ficha animateItem)
                        item(key = "trend") {
                            Box(museAnimateItem()) {
                                TrendCard(
                                    messagesPerDay = state.messagesPerDay,
                                    modifier = Modifier.padding(horizontal = MusePaddings.screen),
                                )
                            }
                        }

                        // 5. Distribución activa horaria(24 histograma raíz)
                        // v2.x: Finalización del efecto de movimiento — admisión con tarjeta(ficha animateItem)
                        item(key = "hourly") {
                            Box(museAnimateItem()) {
                                HourlyDistributionCard(
                                    hourlyDistribution = state.hourlyDistribution,
                                    modifier = Modifier.padding(horizontal = MusePaddings.screen),
                                )
                            }
                        }

                        // 6. Proporción de uso del asistente(gráfico de anillos)
                        // v2.x: Finalización del efecto de movimiento — admisión con tarjeta(ficha animateItem)
                        item(key = "assistant_donut") {
                            Box(museAnimateItem()) {
                                AssistantDonutCard(
                                    assistantCounts = state.assistantCounts,
                                    modifier = Modifier.padding(horizontal = MusePaddings.screen),
                                )
                            }
                        }

                        // 7. Top modelo(Top 5 + Expandir)
                        // v2.x: Finalización del efecto de movimiento — admisión con tarjeta(ficha animateItem)
                        item(key = "top_models") {
                            Box(museAnimateItem()) {
                                TopModelsCard(
                                    modelCounts = state.modelCounts,
                                    modifier = Modifier.padding(horizontal = MusePaddings.screen),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
// ── 1. Encabezado del panel ──────────────────────────────────────────────────────

/**
 * Encabezado del panel:Botón Atrás + Botón Exportar + Número grande(Número total de mensajes) + subtitular.
 *
 * - números grandes:36sp Bold,onSurface Color
 * - subtítulo:"Total X conversaciones · Y dia activo"
 * - esquina superior derecha:MuseIcons.share botón exportar,Haga clic para activar el sistema compartido
 */
@Composable
private fun DashboardHeader(state: StatsViewModel.StatsUiState, onBack: () -> Unit, onExport: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.tightGap, vertical = MusePaddings.tinyGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MuseTactileButton(
                icon = MuseIcons.arrowLeft,
                onClick = onBack,
                contentDescription = stringResource(R.string.stats_back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            MuseTactileButton(
                icon = MuseIcons.share,
                onClick = onExport,
                contentDescription = stringResource(R.string.stats_export_cd),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        // números grandes:Número total de mensajes
        Text(
            text = formatCount(state.totalMessages),
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp, // Estadísticas de números grandes, nivel de visualización fijo.
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )
        // subtítulo:Total X conversaciones · Y dia activo
        val activeDays = state.messagesPerDay.keys.size
        Text(
            text = stringResource(R.string.stats_dashboard_subtitle, state.totalSessions, activeDays),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}

// ── 2. estado de datos vacíos ──────────────────────────────────────────────────────

/**
 * estado de datos vacíos:ChartBar Icono + texto de aviso + "para iniciar una conversación"botón.
 */
@Composable
private fun EmptyStatsState(onAction: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(MusePaddings.largeGap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = MuseIcons.messages,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = stringResource(R.string.stats_empty_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Surface(
            shape = MuseShapes.semiLarge,
            color = MaterialTheme.colorScheme.onSurface,
            contentColor = MaterialTheme.colorScheme.surface,
            onClick = onAction,
        ) {
            Text(
                text = stringResource(R.string.stats_empty_action),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

// ── 3. Cuadrícula de métricas clave ────────────────────────────────────────────────────

/**
 * Cuadrícula de métricas clave:2 OK × 3 columna,6 indicadores(dialogo total/noticias generales/promedio diario/más activo/este mes/dias consecutivos).
 *
 * cada indicador:Números grandes + etiquetas pequeñas + iconos(Escala de grises tint).
 */
@Composable
private fun KeyMetricsGrid(state: StatsViewModel.StatsUiState, modifier: Modifier = Modifier) {
    MuseSurface(
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                MetricCell(
                    icon = MuseIcons.chat,
                    value = formatCount(state.totalSessions),
                    label = stringResource(R.string.stats_total_sessions),
                    modifier = Modifier.weight(1f),
                )
                MetricCell(
                    icon = MuseIcons.messages,
                    value = formatCount(state.totalMessages),
                    label = stringResource(R.string.stats_total_messages),
                    modifier = Modifier.weight(1f),
                )
                MetricCell(
                    icon = MuseIcons.chartLine,
                    value = formatAvg(state.avgMessagesPerDay),
                    label = stringResource(R.string.stats_avg_daily),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                MetricCell(
                    icon = MuseIcons.calendarStats,
                    value = state.mostActiveDay?.second?.let { formatCount(it) } ?: "—",
                    label = stringResource(R.string.stats_most_active_day),
                    modifier = Modifier.weight(1f),
                )
                MetricCell(
                    icon = MuseIcons.calendarTime,
                    value = formatCount(state.messagesThisMonth),
                    label = stringResource(R.string.stats_this_month),
                    modifier = Modifier.weight(1f),
                )
                MetricCell(
                    icon = MuseIcons.flame,
                    value = state.streakDays.toString(),
                    label = stringResource(R.string.stats_streak),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                MetricCell(
                    icon = MuseIcons.chat,
                    value = formatTokenCount(state.totalCharsReceived),
                    label = stringResource(R.string.stats_total_tokens_label),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** unidad indicadora única:icono(Escala de grises)+ Números grandes + etiquetas pequeñas. */
@Composable
private fun MetricCell(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

// ── 4. Filas de filtro de rango de tiempo ──────────────────────────────────────────────────

/**
 * Filas de filtro de rango de tiempo:desplazamiento horizontal MuseChip(Todo/Este mes/Esta semana/Hoy).
 * Filtrado de respuesta de cuadrícula de métricas clave únicamente;Las tarjetas gráficas permanecen a todo volumen.
 */
@Composable
private fun TimeRangeFilterRow(currentRange: StatsTimeRange, onRangeChange: (StatsTimeRange) -> Unit, modifier: Modifier = Modifier) {
    val ranges = listOf(
        StatsTimeRange.ALL_TIME to R.string.stats_range_all,
        StatsTimeRange.THIS_MONTH to R.string.stats_range_month,
        StatsTimeRange.THIS_WEEK to R.string.stats_range_week,
        StatsTimeRange.TODAY to R.string.stats_range_today,
    )
    val allCd = stringResource(R.string.stats_range_cd)
    val selectedCd = stringResource(R.string.stats_range_selected)
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ranges.size) { index ->
            val (range, labelRes) = ranges[index]
            MuseChip(
                selected = currentRange == range,
                onClick = { onRangeChange(range) },
                label = stringResource(labelRes),
                modifier = Modifier.semantics {
                    contentDescription = "$allCd: ${if (currentRange == range) "$selectedCd " else ""}${ranges[index].first.name}"
                },
            )
        }
    }
}

// ── 5. Tarjeta de mapa de calor(soltero Canvas) ─────────────────────────────────────────

/**
 * Tarjeta de mapa de calor:Título + Cuadrícula de lienzo + Leyenda.
 *
 * - Single Canvas + dibujo drawRoundRect 53 semanas × 7 dia(Reemplazado 371 Box)
 * - combinación de colores:5 Escala de grises(Transparencia 0/0.2/0.4/0.7/1.0)usar onSurface,No hay necesidad primary
 * - Haga clic en la celda para mostrar la cantidad de mensajes del día. tooltip
 */
@Composable
private fun HeatmapCard(messagesPerDay: Map<LocalDate, Int>, modifier: Modifier = Modifier) {
    var selectedCell by remember { mutableStateOf<Pair<LocalDate, Int>?>(null) }

    MuseSurface(
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Text(
                text = stringResource(R.string.stats_heatmap),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Tooltip OK:Muestra el número de mensajes para la fecha seleccionada.,o rápido
            Text(
                text = selectedCell?.let { (date, count) ->
                    stringResource(
                        R.string.stats_tooltip_messages,
                        date.toString(),
                        count,
                    )
                } ?: stringResource(R.string.stats_tooltip_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CanvasHeatmap(
                messagesPerDay = messagesPerDay,
                onCellTap = { date, count -> selectedCell = date to count },
            )
            // Leyenda:menos ↔ mucho
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.stats_less),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(2.dp))
                val onSurface = MaterialTheme.colorScheme.onSurface
                val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
                val heatmapLegendCd = stringResource(R.string.stats_heatmap_legend_cd)
                Canvas(
                    modifier = Modifier
                        .width(70.dp)
                        .height(10.dp)
                        .semantics { contentDescription = heatmapLegendCd },
                ) {
                    val cellSize = 10.dp.toPx()
                    val spacing = 2.dp.toPx()
                    val steps = listOf(0f, 0.2f, 0.4f, 0.7f, 1f)
                    steps.forEachIndexed { idx, alpha ->
                        val color = if (alpha == 0f) surfaceVariant else onSurface.copy(alpha = alpha)
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(x = idx * (cellSize + spacing), y = 0f),
                            size = Size(cellSize, cellSize),
                            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                        )
                    }
                }
                Spacer(Modifier.width(2.dp))
                Text(
                    text = stringResource(R.string.stats_more),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Canvas mapa de calor:53 semana × 7 dia,Dibujo de un solo nodo.
 *
 * - columna fija izquierda:Etiquetas del día de la semana.(Mon/Wed/Fri)
 * - área de desplazamiento hacia la derecha:Fila de etiqueta de mes + cuadrícula de lienzo
 * - haga clic en golpear:Calculado en base al desplazamiento weekIdx/dow,fecha atrasada
 * - combinación de colores:5 Escala de grises(0/0.2/0.4/0.7/1.0),usar onSurface,No hay necesidad primary
 */
@Composable
private fun CanvasHeatmap(messagesPerDay: Map<LocalDate, Int>, onCellTap: (LocalDate, Int) -> Unit) {
    val today = LocalDate.now()
    val startSunday = today
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        .minusWeeks(52)

    val numWeeks = 53
    // cuartiles(Sólo contó los días con novedades.)
    val activeCounts = messagesPerDay.values.filter { it > 0 }.sorted()
    val q1 = activeCounts.getOrElse((activeCounts.size * 0.25).toInt()) { 1 }
    val q2 = activeCounts.getOrElse((activeCounts.size * 0.50).toInt()) { 2 }
    val q3 = activeCounts.getOrElse((activeCounts.size * 0.75).toInt()) { 3 }

    val cellSize = 11.dp
    val cellSpacing = 2.dp
    val monthLabelHeight = 14.dp

    val density = LocalDensity.current
    val cellPx = with(density) { cellSize.toPx() }
    val spacingPx = with(density) { cellSpacing.toPx() }
    val stepPx = cellPx + spacingPx
    val cornerPx = with(density) { 2.dp.toPx() }
    val gridWidthDp = (numWeeks * (cellSize.value + cellSpacing.value) - cellSpacing.value).dp
    val gridHeightDp = (7 * (cellSize.value + cellSpacing.value) - cellSpacing.value).dp

    // Etiquetas del día de la semana.:Mostrar solo lunes/miércoles/viernes para guardar espacio(Sun=0 índice)
    val dowLabels = listOf("", "Mon", "", "Wed", "", "Fri", "")

    val onSurface = MaterialTheme.colorScheme.onSurface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outline

    // Desplazamiento inicial hacia la derecha(última semana)
    val scrollState = rememberScrollState(initial = Int.MAX_VALUE)

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // columna fija izquierda:Deje la parte superior en blanco + etiquetas de 7 días de la semana
        Column(
            modifier = Modifier.width(16.dp),
            verticalArrangement = Arrangement.spacedBy(cellSpacing),
        ) {
            Spacer(Modifier.height(monthLabelHeight + 2.dp))
            dowLabels.forEach { label ->
                Box(
                    modifier = Modifier.size(cellSize),
                    contentAlignment = Alignment.Center,
                ) {
                    if (label.isNotEmpty()) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.7,
                            color = outline,
                        )
                    }
                }
            }
        }

        // área de desplazamiento hacia la derecha:Fila de etiqueta de mes + cuadrícula de lienzo
        Column(
            modifier = Modifier.horizontalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // fila de etiqueta de mes:Atravesando 53 semanas,Consigue el primero de esa semana todas las semanas. dayOfMonth==1 fecha
            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    val weekStart = startSunday.plusDays((weekIdx * 7).toLong())
                    val labelDate = (0..6)
                        .map { weekStart.plusDays(it.toLong()) }
                        .firstOrNull { it.dayOfMonth == 1 }
                    Box(
                        modifier = Modifier
                            .width(cellSize)
                            .height(monthLabelHeight),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        if (labelDate != null) {
                            Text(
                                text = if (labelDate.monthValue == 1) {
                                    labelDate.year.toString()
                                } else {
                                    labelDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                                },
                                modifier = Modifier.wrapContentWidth(unbounded = true),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.75,
                                color = outline,
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            val heatmapCd = stringResource(R.string.stats_heatmap_cd, numWeeks)
            // Canvas Asunto del mapa de calor:53 columna(semana)× 7 OK(dia),nodo único
            Canvas(
                modifier = Modifier
                    .width(gridWidthDp)
                    .height(gridHeightDp)
                    .semantics { contentDescription = heatmapCd }
                    .pointerInput(messagesPerDay) {
                        detectTapGestures { offset ->
                            val weekIdx = (offset.x / stepPx).toInt()
                            val dow = (offset.y / stepPx).toInt()
                            if (weekIdx in 0 until numWeeks && dow in 0..6) {
                                val date = startSunday.plusDays((weekIdx * 7 + dow).toLong())
                                if (!date.isAfter(today)) {
                                    val count = messagesPerDay[date] ?: 0
                                    onCellTap(date, count)
                                }
                            }
                        }
                    },
            ) {
                for (weekIdx in 0 until numWeeks) {
                    for (dow in 0..6) {
                        val date = startSunday.plusDays((weekIdx * 7 + dow).toLong())
                        val isFuture = date.isAfter(today)
                        val count = if (isFuture) 0 else (messagesPerDay[date] ?: 0)
                        val alpha = when {
                            isFuture -> -1f
                            count == 0 -> 0f
                            count <= q1 -> 0.2f
                            count <= q2 -> 0.4f
                            count <= q3 -> 0.7f
                            else -> 1f
                        }
                        val color = when {
                            alpha < 0f -> surfaceVariant.copy(alpha = 0.3f)
                            alpha == 0f -> surfaceVariant
                            else -> onSurface.copy(alpha = alpha)
                        }
                        val x = weekIdx * stepPx
                        val y = dow * stepPx
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(x, y),
                            size = Size(cellPx, cellPx),
                            cornerRadius = CornerRadius(cornerPx, cornerPx),
                        )
                    }
                }
            }
        }
    }
}

// ── 6. tarjetas de tendencia(7/30/90 día + media celular) ────────────────────────────────

/**
 * tarjetas de tendencia:Cambio de ventana de tiempo(7/30/90 dia)+ Gráfico de líneas + línea promedio(línea discontinua)+ Finalizar lo más destacado.
 *
 * - fuente de datos:messagesPerDay(historia completa,Captura local por ventana)
 * - Canvas Dibujar polilínea + puntos finales(primary)+ linea promedio(outline línea discontinua)
 * - eje horizontal:7 Mostrar día de la semana,30/90 visualización del día M/d
 */
@Composable
private fun TrendCard(messagesPerDay: Map<LocalDate, Int>, modifier: Modifier = Modifier) {
    var windowDays by remember { mutableStateOf(7) }
    val today = LocalDate.now()

    // Capture datos de tendencias por ventana(oldest → today)
    val trendData = remember(messagesPerDay, windowDays) {
        (0 until windowDays).map { offset ->
            val date = today.minusDays((windowDays - 1 - offset).toLong())
            date to (messagesPerDay[date] ?: 0)
        }
    }
    val counts = trendData.map { it.second }
    val maxCount = counts.maxOrNull()?.takeIf { it > 0 } ?: 0
    val avg = if (counts.isNotEmpty()) counts.average() else 0.0
    val hasData = counts.any { it > 0 }

    val onSurface = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val windowOptions = listOf(
        7 to R.string.stats_trend_window_7d,
        30 to R.string.stats_trend_window_30d,
        90 to R.string.stats_trend_window_90d,
    )

    MuseSurface(
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.stats_trend_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // valor final(Número de mensajes hoy)
                if (hasData) {
                    Text(
                        text = formatCount(counts.lastOrNull() ?: 0),
                        style = MaterialTheme.typography.titleSmall,
                        color = onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Cambio de ventana de tiempo
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(windowOptions.size) { idx ->
                    val (days, labelRes) = windowOptions[idx]
                    MuseChip(
                        selected = windowDays == days,
                        onClick = { windowDays = days },
                        label = stringResource(labelRes),
                    )
                }
            }

            if (!hasData) {
                EmptyStatsHint()
            } else {
                // gráfico de líneas
                val trendChartCd = stringResource(R.string.stats_trend_chart_cd)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                ) {
                    Canvas(modifier = Modifier.fillMaxSize().semantics { contentDescription = trendChartCd }) {
                        val w = size.width
                        val h = size.height
                        if (counts.isEmpty() || maxCount == 0) return@Canvas
                        val stepX = if (counts.size > 1) w / (counts.size - 1) else w
                        val padding = 6.dp.toPx()
                        val usableH = h - padding * 2

                        // linea promedio(línea discontinua)
                        val avgRatio = (avg.toFloat() / maxCount).coerceIn(0f, 1f)
                        val avgY = padding + (1f - avgRatio) * usableH
                        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                        drawLine(
                            color = outline,
                            start = Offset(0f, avgY),
                            end = Offset(w, avgY),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = dashEffect,
                        )

                        // camino de polilínea
                        val path = Path()
                        counts.forEachIndexed { idx, count ->
                            val x = idx * stepX
                            val ratio = count.toFloat() / maxCount
                            val y = padding + (1f - ratio) * usableH
                            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(
                            path = path,
                            color = onSurface,
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                        )

                        // punto final(hoy)Resaltado de círculo sólido
                        val lastIdx = counts.lastIndex
                        val lastX = lastIdx * stepX
                        val lastRatio = counts[lastIdx].toFloat() / maxCount
                        val lastY = padding + (1f - lastRatio) * usableH
                        drawCircle(
                            color = primary,
                            radius = 4.dp.toPx(),
                            center = Offset(lastX, lastY),
                        )
                    }
                }
                // etiqueta promedio
                Text(
                    text = stringResource(R.string.stats_avg_label, avg),
                    style = MaterialTheme.typography.labelSmall,
                    color = onSurfaceVariant,
                )
                // Etiquetas de eje horizontal
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val labelPositions = if (windowDays == 7) {
                        (0 until windowDays).toList()
                    } else {
                        listOf(0, windowDays / 3, windowDays * 2 / 3, windowDays - 1)
                    }
                    labelPositions.forEach { pos ->
                        val (date, _) = trendData[pos]
                        val label = if (windowDays == 7) {
                            date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.CHINESE)
                        } else {
                            "${date.monthValue}/${date.dayOfMonth}"
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (pos == windowDays - 1) onSurface else outline,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

// ── 7. Tarjeta de distribución horaria(24 histograma raíz) ────────────────────────────────────

/**
 * Tarjetas de distribución activas horarias:Canvas 24 histograma raíz,Para uso máximo primary,Utilice onSurfaceVariant para el resto.
 */
@Composable
private fun HourlyDistributionCard(hourlyDistribution: List<Int>, modifier: Modifier = Modifier) {
    val hours = if (hourlyDistribution.size == 24) {
        hourlyDistribution
    } else {
        IntArray(24) { idx ->
            hourlyDistribution.getOrElse(idx) { 0 }
        }.toList()
    }
    val maxCount = hours.maxOrNull()?.takeIf { it > 0 } ?: 0
    val peakHour = hours.indexOfFirst { it == maxCount && maxCount > 0 }
    val hasData = maxCount > 0

    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface

    MuseSurface(
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Text(
                text = stringResource(R.string.stats_hourly_dist),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = onSurface,
            )

            if (!hasData) {
                EmptyStatsHint()
            } else {
                val hourlyChartCd = stringResource(R.string.stats_hourly_chart_cd)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                ) {
                    Canvas(modifier = Modifier.fillMaxSize().semantics { contentDescription = hourlyChartCd }) {
                        val w = size.width
                        val h = size.height
                        val barSpacing = 2.dp.toPx()
                        val totalSpacing = barSpacing * 23
                        val barWidth = (w - totalSpacing) / 24
                        val padding = 4.dp.toPx()
                        val usableH = h - padding

                        hours.forEachIndexed { idx, count ->
                            val ratio = if (maxCount > 0) count.toFloat() / maxCount else 0f
                            val barHeight = ratio * usableH
                            val x = idx * (barWidth + barSpacing)
                            val y = h - barHeight
                            val color = if (idx == peakHour) primary else onSurfaceVariant.copy(alpha = 0.6f)
                            drawRoundRect(
                                color = color,
                                topLeft = Offset(x, y),
                                size = Size(barWidth, barHeight.coerceAtLeast(2.dp.toPx())),
                                cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
                            )
                        }
                    }
                }
                // eje horizontal:0, 6, 12, 18, 23
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    listOf(0, 6, 12, 18, 23).forEach { hour ->
                        Text(
                            text = "${hour}h",
                            style = MaterialTheme.typography.labelSmall,
                            color = outline,
                        )
                    }
                }
            }
        }
    }
}

// ── 8. Asistente utiliza tarjeta de proporción(gráfico de anillos) ──────────────────────────────────────

/**
 * Asistente utiliza tarjeta de proporción:Canvas gráfico de anillos(donut),Esquema de color en escala de grises.
 *
 * - segmento de anillo:Orden descendente por cantidad,Utilice diferentes escalas de grises para los primeros 5 segmentos,El resto cae bajo"Otros"
 * - El centro muestra el número total de mensajes.
 * - leyenda a continuación:Nombre del asistente + cantidad
 */
@Composable
private fun AssistantDonutCard(assistantCounts: List<StatsViewModel.AssistantUsage>, modifier: Modifier = Modifier) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    // Sistema de color en escala de grises(de profundo a superficial)
    val grayScale = listOf(
        onSurface,
        onSurface.copy(alpha = 0.7f),
        onSurface.copy(alpha = 0.5f),
        onSurface.copy(alpha = 0.35f),
        onSurface.copy(alpha = 0.2f),
    )

    // Orden descendente por cantidad,Antes de recoger 5,El resto cae bajo"Otros"
    val sortedCounts = assistantCounts.sortedByDescending { it.count }
    val totalCount = sortedCounts.sumOf { it.count }
    val displayed = sortedCounts.take(5)
    val otherCount = sortedCounts.drop(5).sumOf { it.count }
    val segments = displayed.mapIndexed { idx, usage ->
        Triple(usage.assistantName, usage.count, grayScale.getOrElse(idx) { grayScale.last() })
    }.toMutableList()
    val otherLabel = stringResource(R.string.stats_other)
    if (otherCount > 0) {
        segments.add(Triple(otherLabel, otherCount, grayScale.last().copy(alpha = 0.1f)))
    }

    MuseSurface(
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Text(
                text = stringResource(R.string.stats_assistant_usage),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = onSurface,
            )

            if (totalCount == 0) {
                EmptyStatsHint()
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // gráfico de anillos
                    Box(
                        modifier = Modifier.size(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val modelDonutCd = stringResource(R.string.stats_model_donut_cd)
                        Canvas(modifier = Modifier.fillMaxSize().semantics { contentDescription = modelDonutCd }) {
                            val w = size.width
                            val h = size.height
                            val cx = w / 2
                            val cy = h / 2
                            val radius = minOf(w, h) / 2 - 4.dp.toPx()
                            val strokeWidth = 16.dp.toPx()
                            val topLeft = Offset(cx - radius, cy - radius)
                            val arcSize = Size(radius * 2, radius * 2)

                            // anillo de fondo(surfaceVariant)
                            drawArc(
                                color = surfaceVariant,
                                startAngle = 0f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth),
                            )

                            // segmentación
                            var startAngle = -90f // empezar desde arriba
                            segments.forEach { (_, count, color) ->
                                val sweep = if (totalCount > 0) 360f * count / totalCount else 0f
                                if (sweep > 0f) {
                                    drawArc(
                                        color = color,
                                        startAngle = startAngle,
                                        sweepAngle = sweep,
                                        useCenter = false,
                                        topLeft = topLeft,
                                        size = arcSize,
                                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                                    )
                                    startAngle += sweep
                                }
                            }
                        }
                        // Centro:Número total de mensajes
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = formatCount(totalCount),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = onSurface,
                            )
                            Text(
                                text = stringResource(R.string.stats_total_messages),
                                style = MaterialTheme.typography.labelSmall,
                                color = onSurfaceVariant,
                            )
                        }
                    }

                    // Leyenda
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        segments.forEach { (name, count, color) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(MaterialTheme.shapes.extraSmall)
                                        .background(color),
                                )
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = formatCount(count),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = outline,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── 9. Top tarjeta modelo(Top 5 + Expandir) ───────────────────────────────────

/**
 * Top tarjeta modelo:Mostrado por defecto Top 5,"Ver todo"Expandir para mostrar todo.
 *
 * - por línea:Clasificación + nombre del modelo + cantidad + porcentaje + barra de progreso(Escala de grises)
 * - Para barra de progreso onSurface(profundo)acolchado + superficiePista variante,Top 1 Resaltar con primaria
 */
@Composable
private fun TopModelsCard(modelCounts: List<StatsViewModel.ModelUsage>, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val primary = MaterialTheme.colorScheme.primary

    MuseSurface(
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.stats_top_functions),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = onSurface,
                )
                if (modelCounts.size > 5) {
                    Row(
                        modifier = Modifier.clickable { expanded = !expanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        // LAYOUT-01: "Ver todo/"Colapso" es un grupo de acción compacto al final de la línea.(El ancho de línea está determinado por el contenido.,Más ancho estirará toda la línea),
                        // Por lo tanto, sólo se permite 1 línea de información.,Evite que los caracteres queden comprimidos en saltos de línea mediante chevron cuando se amplía el tamaño de fuente.
                        Text(
                            text = stringResource(if (expanded) R.string.stats_collapse else R.string.stats_view_all),
                            style = MaterialTheme.typography.labelMedium,
                            color = onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            imageVector = if (expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                            contentDescription = null,
                            tint = onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            val displayed = if (expanded) modelCounts else modelCounts.take(5)
            if (displayed.isEmpty()) {
                EmptyStatsHint()
            } else {
                val maxCount = (displayed.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
                displayed.forEachIndexed { idx, usage ->
                    val ratio = usage.count.toFloat() / maxCount
                    val isTop = idx == 0
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Clasificación + nombre del modelo
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                // LAYOUT-01: Las insignias de clasificación son textos breves puramente decorativos.(El ancho restante pertenece al nombre del modelo a la derecha. weight),Límite de 1 línea.
                                Text(
                                    text = "#${idx + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isTop) onSurface else outline,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                                Text(
                                    text = usage.modelName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // Cantidad · Porcentaje
                            Text(
                                text = "${formatCount(usage.count)} · ${(usage.percentage * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = outline,
                            )
                        }
                        // barra de progreso:rastrear + llenar(Escala de grises,Top 1 usar primary)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(surfaceVariant),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(ratio)
                                    .fillMaxHeight()
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(if (isTop) primary else onSurface.copy(alpha = 0.5f)),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Función auxiliar ──────────────────────────────────────────────────────────

/** Texto de sugerencia de datos vacío. */
@Composable
private fun EmptyStatsHint() {
    Text(
        text = stringResource(R.string.stats_no_data),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
    )
}

/**
 * Formato de números grandes:>= 1M usar "M" sufijo,>= 1K usar "K" sufijo(a 1 decimal,Los números enteros omiten decimales).
 */
private fun formatCount(count: Int): String = when {
    count >= 1_000_000 -> formatWithSuffix(count / 1_000_000.0, "M")
    count >= 1_000 -> formatWithSuffix(count / 1_000.0, "K")
    else -> count.toString()
}

/** Formato promedio:Dashu toma el sufijo K/M,Mantenga 1 decimal. */
private fun formatAvg(avg: Double): String = when {
    avg >= 1_000_000 -> formatWithSuffix(avg / 1_000_000.0, "M")
    avg >= 1_000 -> formatWithSuffix(avg / 1_000.0, "K")
    else -> String.format(Locale.US, "%.1f", avg)
}

private fun formatTokenCount(chars: Long): String {
    val estimatedTokens = chars / 2 // estimación aproximada:Chino alrededor de 2 caracteres./token
    return formatCount(estimatedTokens.toInt())
}

/** Formato con sufijo:Redondear a 1 decimal,Omita el punto decimal para la parte entera. */
private fun formatWithSuffix(value: Double, suffix: String): String {
    val rounded = (value * 10).toLong() / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        "${rounded.toLong()}$suffix"
    } else {
        String.format(Locale.US, "%.1f", rounded) + suffix
    }
}

/**
 * Construir texto de resumen estadístico(Para compartir exportaciones).
 */
private fun buildStatsSummaryText(context: Context, state: StatsViewModel.StatsUiState): String = buildString {
    appendLine(context.getString(R.string.stats_summary_header))
    appendLine()
    appendLine(context.getString(R.string.stats_summary_total_sessions, state.totalSessions))
    appendLine(context.getString(R.string.stats_summary_total_messages, state.totalMessages))
    appendLine(context.getString(R.string.stats_summary_ai_replies, state.totalAiMessages))
    appendLine(context.getString(R.string.stats_summary_user_messages, state.totalUserMessages))
    appendLine(context.getString(R.string.stats_summary_streak_days, state.streakDays))
    appendLine(context.getString(R.string.stats_summary_week_messages, state.messagesThisWeek))
    appendLine(context.getString(R.string.stats_summary_month_messages, state.messagesThisMonth))
    appendLine(context.getString(R.string.stats_summary_avg_daily, String.format(Locale.US, "%.1f", state.avgMessagesPerDay)))
    state.mostActiveDay?.let { (date, count) ->
        appendLine(context.getString(R.string.stats_summary_most_active_day, date, count))
    }
    appendLine()
    if (state.modelCounts.isNotEmpty()) {
        appendLine(context.getString(R.string.stats_summary_models_header))
        state.modelCounts.take(10).forEachIndexed { i, m ->
            appendLine(
                context.getString(R.string.stats_summary_model_line, i + 1, m.modelName, m.count, (m.percentage * 100).toInt().toString()),
            )
        }
    }
    appendLine()
    if (state.assistantCounts.isNotEmpty()) {
        appendLine(context.getString(R.string.stats_summary_assistants_header))
        state.assistantCounts.forEach { a ->
            appendLine(context.getString(R.string.stats_summary_assistant_line, a.assistantName, a.count))
        }
    }
}

/**
 * Llamar al sistema compartido,Exportar texto de resumen estadístico.
 */
private fun shareStatsSummary(context: Context, state: StatsViewModel.StatsUiState) {
    val summary = buildStatsSummaryText(context, state)
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.stats_export_subject))
        putExtra(Intent.EXTRA_TEXT, summary)
    }
    val chooser = Intent.createChooser(sendIntent, context.getString(R.string.stats_export_chooser))
    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(chooser) }
}
