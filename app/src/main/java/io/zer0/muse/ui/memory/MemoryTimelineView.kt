@file:Suppress("FunctionNaming") // Compose Los nombres de los componentes comienzan con una letra mayúscula por convención (consistente con otros archivos de UI en el proyecto)

package io.zer0.muse.ui.memory

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.AmberWarmth
import io.zer0.muse.ui.theme.CoralWhisper
import io.zer0.muse.ui.theme.LavenderDream
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.SageCalm
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Phase 2 2A: Componente de vista de línea de tiempo de memoria — Filtre filas, títulos de meses y tarjetas de eventos.
 *
 * v2.1.x Arreglar "Bloqueo al hacer clic en la línea de tiempo": El MemoryTimelineView original viene con LazyColumn,
 * La representación en el elemento de LazyColumn en la capa exterior de MemoryScreen constituye un desplazamiento anidado, capa interna LazyColumn
 * Restricción de altura infinita medida, tirar "Vertically scrollable component was measured with an
 * infinity maximum height constraints".
 * Ahora aplana el contenido de la línea de tiempo a la capa exterior. LazyListScope(ver MemoryScreen.memoryStreamItems),
 * Este archivo solo mantiene subcomponentes sin desplazamiento.
 */

/** Filtrar filas dentro de la línea de tiempo(Todo / Hechos / Resumen / Hitos). */
@Composable
internal fun MemoryTimelineFilterRow(selected: String, onSelect: (String) -> Unit) {
    val filters = listOf(
        "all" to stringResource(R.string.memory_timeline_filter_all),
        "fact" to stringResource(R.string.memory_timeline_filter_fact),
        "summary" to stringResource(R.string.memory_timeline_filter_summary),
        "milestone" to stringResource(R.string.memory_timeline_filter_milestone),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        filters.forEach { (key, label) ->
            MuseChip(
                selected = selected == key,
                onClick = { onSelect(key) },
                label = label,
            )
        }
    }
}

@Composable
internal fun MonthHeader(month: String) {
    Text(
        text = month,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TimelineEventCard(
    item: TimelineItem,
    // v2.6.10: La cadena de círculos en el lado izquierdo de la línea de tiempo se ha cambiado a un círculo de selección múltiple.——Comentarios de los usuarios de que la memoria está en mal estado/Recuerda repetir,
    // Eliminar elementos uno por uno los devolverá a la parte superior de la lista. Mantener presionada la tecla para seleccionar y eliminar por lotes son las mejores formas de utilizarlos.
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onToggleSelect: (() -> Unit)? = null,
) {
    // v2.7(Comentarios de los usuarios): Se puede hacer clic en artículos extensos sobre tarjetas de línea de tiempo para expandirlos y ver el texto completo.
    //   El contenido anterior era rígido. maxLines=3 Truncado y no hay entrada de expansión. La memoria larga sólo se puede ver en el editor.
    //   El umbral es coherente con el modo de lista MemoryFactRow (con nuevas líneas, se considera ampliable).
    var expanded by remember(item.id) { mutableStateOf(false) }
    val canExpand = item.content.length > TIMELINE_EXPAND_THRESHOLD || item.content.contains('\n')
    val toggleExpand = { if (canExpand) expanded = !expanded }

    // Toda la tarjeta contiene sólo un conjunto de gestos. Texto: Agregar una capa en la que se puede hacer clic a la superficie consumirá la pulsación larga:
    // Cuando su dedo está en el cuerpo principal de la tarjeta, no podrá llegar aquí presionando prolongadamente. Sólo se pueden activar los nodos estrechos de la izquierda.
    // así que haz clic(Expandir/comprobar)y la pulsación larga se recogen aquí, cubriendo toda la tarjeta.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp)
            .then(
                if (onLongPress == null) {
                    Modifier.clickable(enabled = canExpand) { toggleExpand() }
                } else {
                    Modifier.combinedClickable(
                        onClick = { if (selectionMode) onToggleSelect?.invoke() else toggleExpand() },
                        onLongClick = onLongPress,
                    )
                },
            ),
    ) {
        TimelineNode(item, selectionMode, isSelected, onToggleSelect)
        Spacer(Modifier.width(8.dp))
        TimelineEventCardBody(item, expanded, canExpand)
    }
}

/** Nodo en el lado izquierdo de la línea de tiempo (color de importancia/icono + línea de conexión). */
@Composable
private fun TimelineNode(
    item: TimelineItem,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
) {
    val nodeColor = importanceColor(item.importance)
    val nodeIcon = if (item.importance == 2) MuseIcons.star else MuseIcons.circle
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(24.dp),
    ) {
        if (selectionMode) {
            Icon(
                imageVector = if (isSelected) MuseIcons.circleCheck else MuseIcons.circle,
                contentDescription = null,
                tint =
                if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier =
                Modifier
                    .size(16.dp)
                    .then(if (onToggleSelect != null) Modifier.clickable { onToggleSelect() } else Modifier),
            )
        } else {
            Icon(
                imageVector = nodeIcon,
                // A11Y-05: El estatus se transmite por algo más que el color — Descripción del texto de importancia suplementaria
                contentDescription = importanceLabel(item.importance),
                tint = nodeColor,
                modifier = Modifier.size(12.dp),
            )
        }
        // Línea de conexión vertical
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(40.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

/** El cuerpo principal de la tarjeta del evento en el lado derecho de la línea de tiempo (etiqueta + contenido + expansión + tiempo). */
@Composable
private fun TimelineEventCardBody(item: TimelineItem, expanded: Boolean, canExpand: Boolean) {
    val nodeColor = importanceColor(item.importance)
    // El gesto de clic se realiza mediante [TimelineEventCard] Unity toma el control (ver nota allí) y ya no se puede hacer clic aquí.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TimelineEventHeader(item, nodeColor)
            // contenido (v2.7: Se puede ampliar para ver el texto completo, no más truncamiento rígido de 3 líneas)
            Text(
                text = item.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            // v2.7: Expandir/Cerrar la entrada (misma copia que en modo lista)
            if (canExpand) {
                Text(
                    text = stringResource(
                        if (expanded) R.string.memory_screen_collapse else R.string.memory_screen_expand,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            TimelineEventTime(item.createdAt)
        }
    }
}

/** Fila superior de tarjetas de eventos: etiqueta de fuente + estrella de importancia. */
@Composable
private fun TimelineEventHeader(item: TimelineItem, nodeColor: androidx.compose.ui.graphics.Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            shape = MuseShapes.small,
            color = nodeColor.copy(alpha = 0.15f),
        ) {
            Text(
                text = item.source,
                style = MaterialTheme.typography.labelSmall,
                color = nodeColor,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (item.importance > 0) {
            Row {
                repeat(item.importance) {
                    Icon(
                        MuseIcons.star,
                        contentDescription = null,
                        tint = AmberWarmth,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

/** Marca de tiempo del evento (no representada si falla el análisis). */
@Composable
private fun TimelineEventTime(createdAt: String?) {
    val timeStr = remember(createdAt) {
        try {
            val dt = Instant.parse(createdAt).atZone(ZoneId.systemDefault())
            DateTimeFormatter.ofPattern("MM-dd HH:mm").format(dt)
        } catch (_: Exception) {
            ""
        }
    }
    if (timeStr.isNotBlank()) {
        Text(
            text = timeStr,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** importancia → Color del nodo. */
private fun importanceColor(importance: Int) = when (importance) {
    2 -> CoralWhisper
    1 -> LavenderDream
    else -> SageCalm
}

/** importancia → Descripción en texto accesible. */
@Composable
private fun importanceLabel(importance: Int): String = when (importance) {
    2 -> stringResource(R.string.memory_importance_critical)
    1 -> stringResource(R.string.memory_importance_important)
    else -> stringResource(R.string.memory_importance_normal)
}

/**
 * Clase de datos de entrada de línea de tiempo simplificada(Asignado desde MemoryItem en ViewModel).
 */
data class TimelineItem(
    val id: String,
    val content: String,
    val source: String,
    val importance: Int,
    val createdAt: String?,
    val tags: List<String> = emptyList(),
    /**
     * La identificación real: los hechos de la entrada subyacente son fact.id,El resumen es el sessionId al que pertenece.
     *
     * [id] es una pantalla con el prefijo fuente/clave de animación(Las ramas cruzadas provocarán colisiones.),No puedo borrar cosas,La eliminación debe volver a esta identificación original.
     */
    val itemId: String = id,
    /** La sesión a la que pertenece el resumen. id(Las entradas de hechos llevan la sesión de origen),Elimine el resumen y haga clic en él para colocarlo en la biblioteca. */
    val sessionId: String? = null,
    /** La biblioteca asistente afiliada;El fact.id de cada base de datos de sucursal se incrementa de forma independiente.,La eliminación debe incluir el alcance para eliminar el correcto. */
    val scope: String? = null,
)

/** v2.7: tarjeta de línea de tiempo"Ampliable"Umbral de decisión (mismo calibre que el modo de lista MemoryFactRow). */
private const val TIMELINE_EXPAND_THRESHOLD = 80
