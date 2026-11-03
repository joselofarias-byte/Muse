@file:Suppress("FunctionNaming", "LongMethod")

package io.zer0.muse.ui

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.analytics.AnalyticsSnapshot
import io.zer0.muse.data.analytics.LocalAnalyticsTracker
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.safeFraction
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import org.koin.compose.koinInject

// ════════════════════════════════════════════════════════════════════════════
// P3-2:Panel de análisis de datos locales — Exponer los indicadores recopilados por LocalAnalyticsTracker pero no basados en UI
//
//  fuente de datos:LocalAnalyticsTracker.getSnapshot() → AnalyticsSnapshot
//         LocalAnalyticsTracker.getFeatureUsage() → List<Pair<String, Int>>(Ordenado de forma descendente por conteo)
//  Privacidad:Todos los datos son solo locales. DataStore,No se reportó nada
// ════════════════════════════════════════════════════════════════════════════

/**
 * Panel inferior de análisis de datos locales.
 *
 * Mostrar contenido:
 *  - Tarjeta indicadora básica:DAU Hoy / MAU este mes / Total de sesiones / Total de mensajes / Número de inicios / Número de bloqueos(Contiene tasa de accidentes)
 *  - mantener la tarjeta:D1 / D7 / D30 marca de retención
 *  - Última fecha activa
 *  - Uso de funciones Top 10(Ordenar de forma descendente por conteo,Aviso de tiempo vacío)
 */
@Composable
internal fun AnalyticsSheet(onDismiss: () -> Unit) {
    val tracker: LocalAnalyticsTracker = koinInject()

    var snapshot by remember { mutableStateOf<AnalyticsSnapshot?>(null) }
    var featureUsage by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        snapshot = tracker.getSnapshot()
        featureUsage = tracker.getFeatureUsage()
        loading = false
    }

    MuseBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.debug_analytics_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                // LAYOUT-01: El título ampliado se agregará al límite de líneas. Si el ancho restante es extremadamente pequeño, ya no se ajustará palabra por palabra.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(MusePaddings.contentGap))

        when {
            loading -> {
                Text(
                    text = stringResource(R.string.debug_loading_analytics),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = MusePaddings.contentGap),
                )
            }
            snapshot == null -> {
                Text(
                    text = stringResource(R.string.debug_analytics_load_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = MusePaddings.contentGap),
                )
            }
            else -> {
                val s = snapshot!!
                // ── indicadores básicos:2 cuadrícula de columnas ─────────────────────────────────────────
                MetricGrid(
                    metrics = listOf(
                        stringResource(R.string.debug_metric_dau_today) to s.dauToday.toString(),
                        stringResource(R.string.debug_metric_mau_month) to s.mauMonth.toString(),
                        stringResource(R.string.debug_metric_total_sessions) to s.totalSessions.toString(),
                        stringResource(R.string.debug_metric_total_messages) to s.totalMessages.toString(),
                        stringResource(R.string.debug_metric_launch_count) to s.launchCount.toString(),
                        stringResource(R.string.debug_metric_crash_count) to
                            stringResource(
                                R.string.debug_metric_crash_count_value,
                                s.crashCount,
                                formatPercent(s.crashRate),
                            ),
                    ),
                )

                Spacer(Modifier.height(MusePaddings.contentGap))

                // ── mantener la tarjeta ─────────────────────────────────────────────────
                RetentionCard(
                    d1 = s.d1Retention,
                    d7 = s.d7Retention,
                    d30 = s.d30Retention,
                )

                Spacer(Modifier.height(MusePaddings.contentGap))

                // ── Otro estado:Primera conversación/sistema de memoria/activo recientemente ──────────────────
                StatusRow(
                    label = stringResource(R.string.debug_status_first_chat),
                    value = if (s.firstChatCompleted) {
                        stringResource(R.string.debug_value_yes)
                    } else {
                        stringResource(R.string.debug_value_no)
                    },
                )
                StatusRow(
                    label = stringResource(R.string.debug_status_memory_adopted),
                    value = if (s.memoryAdopted) {
                        stringResource(R.string.debug_value_yes)
                    } else {
                        stringResource(R.string.debug_value_no)
                    },
                )
                if (s.lastActiveDate.isNotBlank()) {
                    StatusRow(label = stringResource(R.string.debug_status_last_active), value = s.lastActiveDate)
                }

                Spacer(Modifier.height(MusePaddings.contentGap))

                // ── Uso de funciones Top 10 ──────────────────────────────────────────
                FeatureUsageSection(usage = featureUsage)
            }
        }
    }
}

/**
 * rejilla indicadora — 2 Pares clave-valor de visualización de columnas,Estilo de tarjeta sencillo.
 */
@Composable
private fun MetricGrid(metrics: List<Pair<String, String>>) {
    // usar columnCount=2 Cuadrícula simplificada de:Representa una línea por par
    val rows = metrics.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
        rows.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                rowItems.forEach { (label, value) ->
                    Surface(
                        shape = MuseShapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(modifier = Modifier.padding(MusePaddings.itemGap)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = value,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                // Si solo hay 1 en la última fila,Agregue un marcador de posición para mantener la cuadrícula alineada
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * mantener la tarjeta:D1 / D7 / D30 Tres etapas de progreso.
 */
@Composable
private fun RetentionCard(d1: Int, d7: Int, d30: Int) {
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.itemGap)) {
            Text(
                text = stringResource(R.string.debug_retention_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                RetentionItem(label = stringResource(R.string.debug_retention_d1), retained = d1)
                RetentionItem(label = stringResource(R.string.debug_retention_d7), retained = d7)
                RetentionItem(label = stringResource(R.string.debug_retention_d30), retained = d30)
            }
        }
    }
}

@Composable
private fun RetentionItem(label: String, retained: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = if (retained > 0) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.size(32.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (retained > 0) {
                        MuseIcons.check
                    } else {
                        MuseIcons.x
                    },
                    contentDescription = null,
                    tint = if (retained > 0) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Fila de par clave-valor(etiqueta + valor). */
@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // LAYOUT-01: La etiqueta toma el ancho restante (el valor todavía está fijo a la derecha) y ya no se ajusta palabra por palabra cuando se amplía el tamaño de fuente.
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // LAYOUT-01: El valor se fija a la derecha (SpaceBetween), solo línea, sin ancho
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Funciones que utilizan la lista Top 10.
 *
 * Mensaje de visualización de lista vacía;Mostrar la barra de progreso del estilo del gráfico de barras cuando no está vacía(Relación con el valor máximo).
 */
@Composable
private fun FeatureUsageSection(usage: List<Pair<String, Int>>) {
    Text(
        text = stringResource(R.string.debug_feature_usage_top),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    if (usage.isEmpty()) {
        Text(
            text = stringResource(R.string.debug_no_feature_usage),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = MusePaddings.contentGap),
        )
    } else {
        val maxCount = usage.maxOf { it.second }.coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            usage.take(10).forEach { (name, count) ->
                FeatureUsageRow(
                    name = name,
                    count = count,
                    // defensa contra el colapso: Meridiano unificado de valor proporcional safeFraction,Evite las fugas de NaN fillMaxWidth
                    fraction = (count.toFloat() / maxCount).safeFraction(),
                )
            }
        }
    }
}

@Composable
private fun FeatureUsageRow(name: String, count: Int, fraction: Float) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        // Gráfico de barras simplificado:Escala expresa usando la altura de la superficie
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(fraction),
                color = MaterialTheme.colorScheme.primary,
                content = {},
            )
        }
    }
}

/** Formatee un número de coma flotante de 0 a 1 a una cadena de porcentaje(a 1 decimal). */
private fun formatPercent(value: Float): String = "%.1f%%".format(value * 100)
