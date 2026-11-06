package io.zer0.muse.ui.markdown

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.statusColors

/**
 * v1.0.53: Representación de tarjeta de datos(Corresponde a la herramienta show_card).
 *
 * Tres tipos de gráficos:bar(columnar)/ line(Polilínea)/ donut(anillo).
 * Hay un botón de descarga PNG en la esquina superior derecha de la tarjeta.(por [onDownload] devolución de llamada,Implementado por la persona que llama Canvas→Bitmap→álbum de fotos).
 */
@Composable
fun DataCardRenderer(card: DataCard, onDownload: ((DataCard) -> Unit)? = null) {
    val colorScheme = MaterialTheme.colorScheme
    val accent = MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.large,
        color = colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
            // Fila de título + botón de descarga
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = card.title.ifBlank { stringResource(R.string.data_card_default_title) },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    // LAYOUT-01: El título de la tarjeta (no el texto) está limitado a 1 línea —— El botón de descarga a la derecha apretará la barra de título cuando se amplíe el tamaño de fuente.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onDownload != null) {
                    MuseTactileButton(
                        icon = MuseIcons.download,
                        onClick = { onDownload(card) },
                        contentDescription = stringResource(R.string.data_card_download),
                        tint = colorScheme.onSurfaceVariant,
                        size = 48.dp,
                        iconSize = 16.dp,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // área del gráfico
            when (card.type) {
                "bar" -> BarChart(card, accent)
                "line" -> LineChart(card, accent)
                "donut" -> DonutChart(card, accent)
            }

            // Leyenda(punto de color + etiqueta + valor).
            // donut La leyenda comparte la misma paleta de colores que los sectores.,Elige colores en el mismo orden.,Garantía "i-ésimo sector = Leyenda del artículo i".
            val infoColor = MaterialTheme.statusColors.info
            val donutPalette = remember(accent, infoColor) { donutSectorPalette(accent, infoColor) }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                card.labels.zip(card.values).take(MAX_LEGEND_ITEMS).forEachIndexed { index, (label, value) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(legendSwatchColor(card.type, index, accent, donutPalette)),
                        )
                        Text(
                            text = "$label $value",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (card.labels.size > MAX_LEGEND_ITEMS) {
                    Text(
                        text = "+${card.labels.size - MAX_LEGEND_ITEMS}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.statusColors.neutral,
                    )
                }
            }
        }
    }
}

/** El número máximo de elementos mostrados en la leyenda.(más allá de la visualización +N). */
private const val MAX_LEGEND_ITEMS = 6

/**
 * Cálculo del ancho de una sola columna.
 *
 * datos vacíos(barCount<=0)Regresar 0,La persona que llama no dibuja,evitar `x / 0` tirar ArithmeticException;
 * El lienzo es demasiado estrecho, lo que hace que el ancho disponible se reduzca a 0,Evite pasar un ancho negativo a Canvas.
 */
internal fun barWidthFor(canvasWidth: Float, barCount: Int, gap: Float): Float {
    if (barCount <= 0) return 0f
    val available = canvasWidth - gap * (barCount - 1)
    return (available / barCount).coerceAtLeast(0f)
}

/** Paleta de sectores del gráfico de anillos(Leyenda compartida con sectores,Índices en el mismo orden.). */
internal fun donutSectorPalette(accent: Color, infoColor: Color): List<Color> = listOf(
    accent,
    accent.copy(alpha = 0.75f),
    accent.copy(alpha = 0.5f),
    accent.copy(alpha = 0.3f),
    infoColor,
)

/** No. [index] sectores/color de leyenda(Recorrer los colores cuando se excede la longitud de la paleta). */
internal fun sectorColor(palette: List<Color>, index: Int): Color =
    if (palette.isEmpty()) Color.Unspecified else palette[index % palette.size]

/** Puntos de color de leyenda:donut Mismo orden y mismo color que el sector.,Los gráficos restantes utilizan colores de acento temático. */
internal fun legendSwatchColor(cardType: String, index: Int, accent: Color, donutPalette: List<Color>): Color =
    if (cardType == "donut") sectorColor(donutPalette, index) else accent

/** Gráfico de barras. */
@Composable
private fun BarChart(card: DataCard, accent: Color) {
    val maxValue = card.values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        val barCount = card.values.size
        // Los datos vacíos no se extraen directamente.:antigua implementación (w - gap * (n - 1)) / n en n == 0 lanzamiento de tiempo ArithmeticException
        if (barCount == 0) return@Canvas
        val w = size.width
        val h = size.height
        val gap = 6.dp.toPx()
        val barWidth = barWidthFor(w, barCount, gap)
        card.values.forEachIndexed { index, value ->
            val barHeight = (value / maxValue) * h
            drawRoundRect(
                color = if (index == card.values.lastIndex) accent else accent.copy(alpha = 0.55f),
                topLeft = Offset(x = index * (barWidth + gap), y = h - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            )
        }
    }
}

/** Gráfico de líneas. */
@Composable
private fun LineChart(card: DataCard, accent: Color) {
    val maxValue = card.values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    val minValue = card.values.minOrNull()?.coerceAtMost(0f) ?: 0f
    val range = (maxValue - minValue).coerceAtLeast(1f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        val w = size.width
        val h = size.height
        val padding = 8.dp.toPx()
        val stepX = if (card.values.size > 1) (w - padding * 2) / (card.values.size - 1) else 0f
        val points = card.values.mapIndexed { index, value ->
            Offset(
                x = padding + index * stepX,
                y = h - padding - ((value - minValue) / range) * (h - padding * 2),
            )
        }
        // Conectar
        for (i in 1 until points.size) {
            drawLine(
                color = accent.copy(alpha = 0.6f),
                start = points[i - 1],
                end = points[i],
                strokeWidth = 2.dp.toPx(),
            )
        }
        // puntos de datos
        points.forEachIndexed { index, point ->
            drawCircle(
                color = if (index == points.lastIndex) accent else accent.copy(alpha = 0.8f),
                radius = 3.dp.toPx(),
                center = point,
            )
        }
    }
}

/** Gráfico de anillos. */
@Composable
private fun DonutChart(card: DataCard, accent: Color) {
    val total = card.values.sum().coerceAtLeast(1f)
    // El color del tema se lee en el contexto Composable,Canvas El dibujo no se puede llamar dentro de lambda
    val infoColor = MaterialTheme.statusColors.info
    val palette = remember(accent, infoColor) { donutSectorPalette(accent, infoColor) }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .size(120.dp)
                .padding(4.dp),
        ) {
            val strokeWidth = 16.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2
            val center = Offset(size.width / 2, size.height / 2)
            var startAngle = -90f
            card.values.forEachIndexed { index, value ->
                val sweep = (value / total) * 360f
                drawArc(
                    color = sectorColor(palette, index),
                    startAngle = startAngle,
                    sweepAngle = sweep - 1f, // pequeña brecha
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                startAngle += sweep
            }
        }
    }
}
