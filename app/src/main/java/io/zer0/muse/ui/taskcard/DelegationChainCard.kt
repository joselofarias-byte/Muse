package io.zer0.muse.ui.taskcard

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.tools.DelegationChainTracker
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.tiny
import org.koin.compose.koinInject

/**
 * v1.201: Tarjetas de enlace de delegado.
 *
 * Muestre visualmente el asistente principal una vez → sub-asistente → Enlace de delegación de Zizi Assistant,
 * Contiene el estado del nodo(RUNNING/COMPLETED/FAILED/TIMEOUT/CANCELLED/PAUSED),
 * Profundice en el motivo del error, la estructura del árbol de subnodos y haga clic para ver los detalles.
 *
 * @param roots Lista de nodos de delegación de nivel superior(parentRequestId == null)
 * @param modifier Modificación externa
 */
@Composable
fun DelegationChainCard(roots: List<DelegationChainTracker.ChainNode>, modifier: Modifier = Modifier) {
    val assistantRepository: AssistantRepository = koinInject()
    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())
    val assistantById = remember(assistants) { assistants.associateBy { it.id } }
    // enlace vacío:Mostrar copia inmediata,No renderizar el contenedor de la tarjeta(Evite el ruido de la tarjeta vacía)
    if (roots.isEmpty()) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = MuseShapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            tonalElevation = 0.dp,
        ) {
            Text(
                text = stringResource(R.string.delegation_no_chain),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(MusePaddings.cardInner),
            )
        }
        return
    }

    val totalCount = remember(roots) { countNodes(roots) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            // ── línea de título:Icono + título + número total de nodos ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding),
            ) {
                Icon(
                    imageVector = MuseIcons.hierarchy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.delegation_chain_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = MuseShapes.tiny,
                ) {
                    Text(
                        text = stringResource(R.string.delegation_node_count, totalCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // ── Vincular la lista de nodos raíz ──
            roots.forEach { node ->
                key(node.requestId) {
                    ChainNodeView(node = node, depth = 0, assistantById = assistantById)
                }
            }
        }
    }
}

/**
 * Representación de nodo de enlace único:punto de estado → insignia de objetivo → Resumen de tareas → Consume mucho tiempo,
 * Los nodos fallidos también muestran vistas previas de mensajes de error;PAUSED Visualización de nodos"Esperando confirmación";
 * Cuando hay subnodos, use sangría de cuadro + barra vertical de lienzo para renderizar de forma recursiva.
 */
@Composable
private fun ChainNodeView(
    node: DelegationChainTracker.ChainNode,
    depth: Int,
    assistantById: Map<String, io.zer0.muse.data.assistant.AssistantEntity>,
) {
    var showDetail by remember { mutableStateOf(false) }
    var expanded by remember(node.requestId) { mutableStateOf(true) }
    val indent = (depth * 24).dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showDetail = true }
                .padding(vertical = 4.dp),
        ) {
            // punto de estado(RUNNING Pulso/otro estado círculo sólido)
            StatusDot(status = node.status)

            // B8-04: El nodo muestra el avatar del asistente correspondiente.,equipo/Los nodos desconocidos recurren a los iconos del árbol
            val assistant = node.targetId.let { assistantById[it] }
            if (assistant != null) {
                AssistantAvatar(assistant = assistant, avatarSize = 24.dp)
            } else {
                Icon(
                    imageVector = MuseIcons.hierarchy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }

            // insignia de objetivo(tipo + nombre)
            TargetBadge(targetType = node.targetType, targetName = node.targetName)

            // Resumen de tareas(primeras 80 palabras)
            Text(
                text = node.task.take(80),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            // Consume mucho tiempo
            Text(
                text = formatNodeDuration(node),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // B8-04: Colapso del nodo secundario/Expandir
            if (node.subNodes.isNotEmpty()) {
                MuseTactileButton(
                    icon = if (expanded) MuseIcons.chevronDown else MuseIcons.chevronRight,
                    onClick = { expanded = !expanded },
                    contentDescription = if (expanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 48.dp,
                    iconSize = 18.dp,
                )
            }
        }

        // nodo fallido:Icono de error + vista previa del error(línea única omitida)
        if (node.status == DelegationNodeStatus.FAILED && !node.errorMessage.isNullOrBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, top = 2.dp, bottom = 2.dp),
            ) {
                Icon(
                    imageVector = MuseIcons.alertCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = node.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Nodo de pausa:Espere el texto de confirmación
        if (node.status == DelegationNodeStatus.PAUSED) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, top = 2.dp, bottom = 2.dp),
            ) {
                Icon(
                    imageVector = MuseIcons.pause,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = stringResource(R.string.delegation_status_paused),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // nodo hijo:Box Sangría + Lienzo dibuja líneas verticales + renderizado recursivo
        if (node.subNodes.isNotEmpty() && expanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(top = 4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    val lineColor = MaterialTheme.colorScheme.outlineVariant
                    Canvas(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(2.dp),
                    ) {
                        drawLine(
                            color = lineColor,
                            start = Offset(size.width / 2f, 0f),
                            end = Offset(size.width / 2f, size.height),
                            strokeWidth = 2f,
                        )
                    }
                }
                Spacer(Modifier.width(MusePaddings.contentGap))
                Column(
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    modifier = Modifier.weight(1f),
                ) {
                    node.subNodes.forEach { sub ->
                        key(sub.requestId) {
                            ChainNodeView(node = sub, depth = depth + 1, assistantById = assistantById)
                        }
                    }
                }
            }
        }
    }

    // Ventana emergente de detalles(Haga clic en el nodo para activar)
    if (showDetail) {
        NodeDetailDialog(node = node, onDismiss = { showDetail = false })
    }
}

/** punto de estado:RUNNING Usar animación de pulso,El resto son círculos rellenos. */
@Composable
private fun StatusDot(status: DelegationNodeStatus) {
    val color = when (status) {
        DelegationNodeStatus.RUNNING -> MaterialTheme.colorScheme.primary
        DelegationNodeStatus.COMPLETED -> MaterialTheme.colorScheme.primary
        DelegationNodeStatus.FAILED -> MaterialTheme.colorScheme.error
        DelegationNodeStatus.TIMEOUT -> MaterialTheme.colorScheme.tertiary
        DelegationNodeStatus.CANCELLED -> MaterialTheme.colorScheme.outline
        DelegationNodeStatus.PAUSED -> MaterialTheme.colorScheme.secondary
    }

    if (status == DelegationNodeStatus.RUNNING) {
        // animación de pulso:alpha 0.3f → 1f ida y vuelta
        val alpha = if (MuseMotion.isReducedMotion()) {
            0.65f
        } else {
            val transition = rememberInfiniteTransition(label = "delegationPulse")
            val animatedAlpha by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = MuseMotion.tween(MuseAnimation.LOOP_SLOW_MS),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "delegationPulseAlpha",
            )
            animatedAlpha
        }
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color = color.copy(alpha = alpha)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color = color),
        )
    }
}

/** insignia de objetivo:mostrar targetType + targetName,Similar a las etiquetas de carpetas en el árbol de archivos. */
@Composable
private fun TargetBadge(targetType: String, targetName: String) {
    val bg = if (targetType == "team") {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val fg = if (targetType == "team") {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(percent = 50),
    ) {
        Text(
            text = targetName.ifBlank { targetType },
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 96.dp)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** Ventana emergente de detalles del nodo:Muestra tareas completas/resultados/errores/tiempo necesario. */
@Composable
private fun NodeDetailDialog(node: DelegationChainTracker.ChainNode, onDismiss: () -> Unit) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.delegation_chain_title),
        onConfirm = onDismiss,
        dismissText = null,
        content = {
            // v1.0.7 arreglar falla:MuseDialog El área de contenido viene con verticalScroll,El anidamiento fallará
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                DetailRow(
                    label = stringResource(R.string.delegation_target_label),
                    value = "${node.targetName} (${node.targetType})",
                )
                DetailRow(
                    label = stringResource(R.string.delegation_task_label),
                    value = node.task,
                )
                DetailRow(
                    label = stringResource(R.string.delegation_duration_label),
                    value = formatNodeDuration(node),
                )
                node.resultPreview?.let { preview ->
                    DetailRow(
                        label = stringResource(R.string.delegation_result_label),
                        value = preview,
                    )
                }
                node.errorMessage?.let { err ->
                    DetailRow(
                        label = stringResource(R.string.delegation_error_label),
                        value = err,
                        isError = true,
                    )
                }
            }
        },
    )
}

/** Detalles en la ventana emergente"etiqueta:valor"DE ACUERDO. */
@Composable
private fun DetailRow(label: String, value: String, isError: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/** Número total de nodos estadísticos(Contiene nodos secundarios). */
private fun countNodes(roots: List<DelegationChainTracker.ChainNode>): Int {
    return roots.sumOf { 1 + countNodes(it.subNodes) }
}

/**
 * Formatear nodos lleva tiempo.
 * - RUNNING → "ejecutando..."
 * - Completado → "1.2s" / "2m 30s" / "150ms"
 *
 * Se puede llamar como @Composable [stringResource] tirar"ejecutando..."Redacción publicitaria.
 */
@Composable
private fun formatNodeDuration(node: DelegationChainTracker.ChainNode): String {
    val runningText = stringResource(R.string.delegation_status_running)
    if (node.status == DelegationNodeStatus.RUNNING) {
        return runningText
    }
    val finished = node.finishedAt ?: return runningText
    val ms = finished - node.startedAt
    return when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "${ms / 1000.0}s"
        else -> {
            val min = ms / 60_000
            val sec = (ms % 60_000) / 1000
            "${min}m ${sec}s"
        }
    }
}
