@file:Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod", "ReturnCount")

package io.zer0.muse.ui.memory

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseLoadingState
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.statusColors
import kotlin.math.hypot
import kotlin.math.min

/**
 * vista de la constelación de la memoria (v2.0 rediseñar).
 *
 * estructura visual:
 *  - agrupar por categoría:Mismo tema formación de la memoria."grupo de constelaciones",Una capa de luz suave en la parte inferior del grupo.,La parte superior del grupo es la etiqueta de clasificación.;
 *  - nodo = Resplandor + punto sólido + anillo de trazo,El círculo exterior del nodo superior tiene una estrella en forma de cruz.;
 *  - La arista de relación es una curva de Bézier cuadrática.,Las relaciones conflictivas están representadas por líneas de puntos rojas.;
 *  - Entrando al encuadre automático por primera vez(Toda la constelación está ampliada en la ventana gráfica.),Zoom de gestos/Arrastrar,La esquina inferior derecha se puede restablecer.;
 *  - Ocultar automáticamente las etiquetas de los nodos normales al hacer zoom más pequeño,Sólo mantén lo importante/fijar hacia arriba/Seleccionar nodo,Evite ponerse blando.
 */
@Composable
fun MemoryGraphView(
    state: MemoryGraphViewModel.GraphState,
    modifier: Modifier = Modifier,
    onNodeAction: ((MemoryGraphNode, NodeAction) -> Unit)? = null,
    onEdgeAction: ((MemoryGraphEdge, EdgeAction) -> Unit)? = null,
    /** v2.6.2(comentarios de los usuarios): Devolución de llamada de conexión de borde manual (nodo de origen, nodo objetivo, tipo de relación). */
    onManualLink: ((MemoryGraphNode, MemoryGraphNode, String) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val statusColors = MaterialTheme.statusColors
    var selectedNode by remember { mutableStateOf<MemoryGraphNode?>(null) }
    // v2.6.2: Estado de conexión manual —— ordenado“Conexión manual”Luego ingrese al modo de selección de destino y luego haga clic en otro nodo para que aparezca el tipo de relación.
    var linkSourceNode by remember { mutableStateOf<MemoryGraphNode?>(null) }
    var linkTargetNode by remember { mutableStateOf<MemoryGraphNode?>(null) }

    if (state.isLoading) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) { MuseLoadingState() }
        return
    }
    if (state.error != null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(state.error, color = colors.error, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    if (state.nodes.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            MuseEmptyState(
                title = stringResource(R.string.memory_graph_empty_title),
                subtitle = stringResource(R.string.memory_graph_empty_subtitle),
            )
        }
        return
    }

    val nodes = state.nodes
    // v2.x: Finalización del efecto de movimiento — Admisión de los signos del zodíaco en su conjunto. fade + scale(Solo transición general,Sin nivel de nodo stagger,La capa de dibujo permanece como está.).
    var entryStarted by remember { mutableStateOf(false) }
    LaunchedEffect(nodes) { entryStarted = true }
    val entryProgress by animateFloatAsState(
        targetValue = if (entryStarted) 1f else 0f,
        animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS),
        label = "memoryGraphEntry",
    )
    // El tamaño del lienzo está determinado por el radio del anillo de nodos del grupo más grande,Asegúrese de que los nodos siempre estén dibujados dentro del lienzo.
    val sizeDp = remember(nodes) {
        val maxClusterSize = nodes.groupingBy { it.category.lowercase() }.eachCount().values.maxOrNull() ?: 1
        val ring = clusterNodeRing(maxClusterSize)
        maxOf(1100f, (ring + 220f) / 0.22f)
    }
    val constellation = remember(nodes, sizeDp) { buildClusterConstellation(nodes, sizeDp) }
    val categoryLabels = nodes.map { it.category.lowercase() }.distinct()
        .associateWith { categoryLabel(it) }

    val density = LocalDensity.current
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var framed by remember(nodes) { mutableStateOf(false) }

    // Entrada por primera vez/cambio de lote de datos:Toda la constelación está centrada.
    LaunchedEffect(viewportSize, sizeDp, framed) {
        if (framed || viewportSize == IntSize.Zero) return@LaunchedEffect
        val canvasPx = with(density) { sizeDp.dp.toPx() }
        val fit = min(viewportSize.width / canvasPx, viewportSize.height / canvasPx)
        val fitZoom = fit.coerceIn(0.45f, 1.05f)
        zoom = fitZoom
        pan = Offset(
            (viewportSize.width - canvasPx * fitZoom) / 2f,
            (viewportSize.height - canvasPx * fitZoom) / 2f,
        )
        framed = true
    }

    val resetView = {
        val canvasPx = with(density) { sizeDp.dp.toPx() }
        if (viewportSize != IntSize.Zero) {
            val fit = min(viewportSize.width / canvasPx, viewportSize.height / canvasPx)
            val fitZoom = fit.coerceIn(0.45f, 1.05f)
            zoom = fitZoom
            pan = Offset(
                (viewportSize.width - canvasPx * fitZoom) / 2f,
                (viewportSize.height - canvasPx * fitZoom) / 2f,
            )
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            // v2.x: Finalización del efecto de movimiento — Admisión en su conjunto:Fundir + acercar ligeramente(0.96→1.0)
            .graphicsLayer {
                alpha = entryProgress
                val entryScale = 0.96f + 0.04f * entryProgress
                scaleX = entryScale
                scaleY = entryScale
            }
            .onSizeChanged { viewportSize = it }
            .pointerInput(nodes) {
                detectTransformGestures { centroid, panChange, zoomChange, _ ->
                    val newZoom = (zoom * zoomChange).coerceIn(0.35f, 2.5f)
                    val scale = newZoom / zoom
                    // Zoom con centro de gestos como punto de anclaje,Luego superponga el desplazamiento de arrastre
                    pan = Offset(
                        centroid.x - (centroid.x - pan.x) * scale + panChange.x,
                        centroid.y - (centroid.y - pan.y) * scale + panChange.y,
                    )
                    zoom = newZoom
                }
            }
            .pointerInput(nodes, constellation) {
                detectTapGestures { tapOffset ->
                    val canvasX = (tapOffset.x - pan.x) / zoom
                    val canvasY = (tapOffset.y - pan.y) / zoom
                    val hit = nodes.minByOrNull { node ->
                        val center = constellation.nodeCenters[node.factId] ?: return@minByOrNull Float.MAX_VALUE
                        val cx = with(density) { center.x.dp.toPx() }
                        val cy = with(density) { center.y.dp.toPx() }
                        val dx = canvasX - cx
                        val dy = canvasY - cy
                        dx * dx + dy * dy
                    }
                    if (hit != null) {
                        val center = constellation.nodeCenters[hit.factId]
                        if (center != null) {
                            val cx = with(density) { center.x.dp.toPx() }
                            val cy = with(density) { center.y.dp.toPx() }
                            val hitRadius = with(density) {
                                (nodeRadiusDp(hit).dp + 12.dp).toPx()
                            } / zoom
                            val dx = canvasX - cx
                            val dy = canvasY - cy
                            if (dx * dx + dy * dy <= hitRadius * hitRadius) {
                                // v2.6.2: modo objetivo —— Haga clic en un nodo diferente y aparecerá el tipo de relación.
                                val src = linkSourceNode
                                if (src != null && src.factId != hit.factId) {
                                    linkTargetNode = hit
                                } else {
                                    selectedNode = hit
                                }
                            }
                        }
                    }
                }
            },
    ) {
        val toneResolver: (String) -> Color = { category -> nodeToneColor(category, colors, statusColors) }
        val labelColor = colors.onSurface
        val labelBg = colors.surface
        val labelBorder = colors.outlineVariant
        val selectedColor = colors.primary

        Canvas(
            modifier = Modifier
                .size(sizeDp.dp)
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            val cw = size.width
            val ch = size.height

            // fondo:Luz suave central + cuadrícula de puntos,dado"mapa estelar"fundación
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(colors.primary.copy(alpha = 0.05f), Color.Transparent),
                    center = Offset(cw / 2f, ch / 2f),
                    radius = cw * 0.55f,
                ),
            )
            val gridStep = 76.dp.toPx()
            val gridDot = 1.1.dp.toPx()
            var gx = gridStep
            while (gx < cw) {
                var gy = gridStep
                while (gy < ch) {
                    drawCircle(
                        color = colors.outline.copy(alpha = 0.14f),
                        radius = gridDot,
                        center = Offset(gx, gy),
                    )
                    gy += gridStep
                }
                gx += gridStep
            }

            // Luz suave en racimo + Categoría Tags
            constellation.clusters.forEach { anchor ->
                val tone = toneResolver(anchor.category)
                val clusterCenter = Offset(anchor.x.dp.toPx(), anchor.y.dp.toPx())
                val halo = anchor.spread * 1.55f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(tone.copy(alpha = 0.10f), Color.Transparent),
                        center = clusterCenter,
                        radius = halo.dp.toPx(),
                    ),
                    radius = halo.dp.toPx(),
                    center = clusterCenter,
                )
                val label = categoryLabels[anchor.category] ?: anchor.category
                drawChipLabel(
                    text = label,
                    center = Offset(clusterCenter.x, clusterCenter.y - (anchor.spread + 40f).dp.toPx()),
                    textColor = colors.onSurfaceVariant,
                    background = labelBg.copy(alpha = 0.9f),
                    border = labelBorder,
                    density = density,
                    fontSize = 10.sp, // Etiqueta de grupo en línea de lienzo:Tamaño de fuente fijo(Escala con gráfica,No utilices fuentes temáticas)
                    letterSpacing = 1.5f,
                    maxWidthPx = 180.dp.toPx(),
                )
            }

            // ventaja de la relación:Bézier cuadrático,Relación contradictoria línea punteada roja
            state.edges.forEach { edge ->
                val a = constellation.nodeCenters[edge.sourceFactId] ?: return@forEach
                val b = constellation.nodeCenters[edge.targetFactId] ?: return@forEach
                val aCenter = Offset(a.x.dp.toPx(), a.y.dp.toPx())
                val bCenter = Offset(b.x.dp.toPx(), b.y.dp.toPx())
                val dx = bCenter.x - aCenter.x
                val dy = bCenter.y - aCenter.y
                val dist = hypot(dx, dy).coerceAtLeast(0.001f)
                // desplazamiento vertical,Dibuja un ligero arco,Evite la superposición completa de múltiples bordes
                val curve = dist * 0.08f
                val mid = Offset(
                    (aCenter.x + bCenter.x) / 2f - dy / dist * curve,
                    (aCenter.y + bCenter.y) / 2f + dx / dist * curve,
                )
                val isContradiction = edge.relationType.equals("contradicts", ignoreCase = true)
                val lineColor = when {
                    isContradiction -> colors.error.copy(alpha = 0.55f)
                    edge.weight >= 0.7f -> colors.primary.copy(alpha = 0.34f)
                    else -> colors.outline.copy(alpha = 0.22f)
                }
                val path = Path().apply {
                    moveTo(aCenter.x, aCenter.y)
                    quadraticBezierTo(mid.x, mid.y, bCenter.x, bCenter.y)
                }
                drawPath(
                    path = path,
                    color = lineColor,
                    style = Stroke(
                        width = if (edge.weight < 0.3f) 0.9.dp.toPx() else 1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = if (isContradiction) {
                            PathEffect.dashPathEffect(floatArrayOf(7f, 5f), 0f)
                        } else {
                            null
                        },
                    ),
                )
                if (edge.weight >= 0.7f && !isContradiction) {
                    // Punto final de relación fuerte pequeño punto de luz,Fortalecer"Enlace estelar"sentido
                    drawCircle(colors.primary.copy(alpha = 0.5f), radius = 2.2.dp.toPx(), center = mid)
                }
            }

            // nodo
            nodes.forEach { node ->
                val center = constellation.nodeCenters[node.factId] ?: return@forEach
                val nodeCenter = Offset(center.x.dp.toPx(), center.y.dp.toPx())
                val tone = toneResolver(node.category)
                val isSelected = selectedNode?.factId == node.factId
                val radius = nodeRadiusDp(node).dp.toPx()

                // Luz suave tres capas.,Haz que los nodos parezcan estrellas brillantes en lugar de"El círculo adjunto"
                drawCircle(tone.copy(alpha = 0.09f), radius = radius * 2.2f, center = nodeCenter)
                drawCircle(tone.copy(alpha = 0.16f), radius = radius * 1.5f, center = nodeCenter)
                drawCircle(
                    color = tone.copy(alpha = if (isSelected) 1f else 0.92f),
                    radius = radius,
                    center = nodeCenter,
                )
                drawCircle(
                    color = if (isSelected) tone else colors.onSurfaceVariant.copy(alpha = 0.42f),
                    radius = radius,
                    center = nodeCenter,
                    style = Stroke(width = if (isSelected) 2.dp.toPx() else 0.9.dp.toPx()),
                )

                // Nodo superior:estrella cruzada
                if (node.isPinned) {
                    val beam = radius * 3.1f
                    val beamWidth = 1.3.dp.toPx()
                    val beamColor = tone.copy(alpha = 0.75f)
                    drawLine(
                        beamColor,
                        Offset(nodeCenter.x - beam, nodeCenter.y),
                        Offset(nodeCenter.x + beam, nodeCenter.y),
                        beamWidth,
                        StrokeCap.Round,
                    )
                    drawLine(
                        beamColor,
                        Offset(nodeCenter.x, nodeCenter.y - beam),
                        Offset(nodeCenter.x, nodeCenter.y + beam),
                        beamWidth,
                        StrokeCap.Round,
                    )
                }

                // etiqueta:Si el zoom es demasiado pequeño, solo se conservarán los nodos importantes/superiores/seleccionados.
                val showLabel = isSelected || node.isPinned || node.importance >= 2 || zoom >= 0.72f
                if (showLabel) {
                    val labelGap = radius + 10.dp.toPx()
                    val toRight = nodeCenter.x < cw / 2f
                    val labelX = if (toRight) nodeCenter.x + labelGap else nodeCenter.x - labelGap
                    drawChipLabel(
                        text = node.title,
                        center = Offset(labelX, nodeCenter.y),
                        textColor = if (isSelected) selectedColor else labelColor,
                        background = if (isSelected) {
                            selectedColor.copy(alpha = 0.12f)
                        } else {
                            labelBg.copy(alpha = 0.86f)
                        },
                        border = if (isSelected) selectedColor.copy(alpha = 0.45f) else labelBorder,
                        density = density,
                        fontSize = 11.sp, // Etiqueta de nodo en línea de lienzo:Tamaño de fuente fijo(Igual que arriba)
                        anchorRight = !toRight,
                        maxWidthPx = 168.dp.toPx(),
                    )
                }
            }
        }

        // botón de reinicio
        Surface(
            onClick = resetView,
            shape = RoundedCornerShape(12.dp),
            color = colors.surface.copy(alpha = 0.92f),
            border = androidx.compose.foundation.BorderStroke(0.6.dp, colors.outlineVariant),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(MusePaddings.cardInner),
        ) {
            Text(
                text = stringResource(R.string.memory_graph_reset_view),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        // Panel de operación del nodo seleccionado
        selectedNode?.let { node ->
            val related = state.edges.filter { it.sourceFactId == node.factId || it.targetFactId == node.factId }
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(MusePaddings.cardInner),
                shape = RoundedCornerShape(16.dp),
                color = colors.surface,
                tonalElevation = 3.dp,
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = node.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${categoryLabel(node.category)} · ${stringResource(R.string.memory_graph_node_related, related.size)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                    // v2.6.2(comentarios de los usuarios): Entrada de conexión manual —— Haga clic para ingresar al modo de selección de objetivos.
                    if (onManualLink != null) {
                        if (linkSourceNode == null) {
                            MuseCapsuleButton(
                                text = stringResource(R.string.memory_graph_link_action),
                                onClick = {
                                    linkSourceNode = node
                                    selectedNode = null
                                },
                                variant = IosCapsuleButtonVariant.Text,
                                fillWidth = false,
                                modifier = Modifier.heightIn(min = 44.dp),
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.memory_graph_link_pick_target),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.primary,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        onNodeAction?.let { action ->
                            val actionModifier = Modifier.heightIn(min = 44.dp)
                            MuseCapsuleButton(
                                text = stringResource(R.string.memory_menu_edit),
                                onClick = { action(node, NodeAction.EDIT) },
                                variant = IosCapsuleButtonVariant.Text,
                                fillWidth = false,
                                modifier = actionModifier,
                            )
                            MuseCapsuleButton(
                                text = stringResource(R.string.memory_menu_pin),
                                onClick = { action(node, NodeAction.PIN) },
                                variant = IosCapsuleButtonVariant.Text,
                                fillWidth = false,
                                modifier = actionModifier,
                            )
                            MuseCapsuleButton(
                                text = stringResource(R.string.memory_menu_delete),
                                onClick = {
                                    action(node, NodeAction.DELETE)
                                    selectedNode = null
                                },
                                variant = IosCapsuleButtonVariant.Text,
                                fillWidth = false,
                                modifier = actionModifier,
                            )
                        }
                        MuseCapsuleButton(
                            text = stringResource(R.string.action_close),
                            onClick = { selectedNode = null },
                            variant = IosCapsuleButtonVariant.Text,
                            fillWidth = false,
                            modifier = Modifier.heightIn(min = 44.dp),
                        )
                    }
                }
            }
        }

        // v2.6.2(comentarios de los usuarios): Ventana emergente de selección de tipo de relación para conexiones manuales.
        val srcNode = linkSourceNode
        val tgtNode = linkTargetNode
        if (onManualLink != null && srcNode != null && tgtNode != null) {
            io.zer0.muse.ui.common.feedback.MuseDialog(
                onDismissRequest = { linkTargetNode = null },
                title = stringResource(R.string.memory_graph_link_type_title),
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { linkTargetNode = null },
                content = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "related_to" to R.string.memory_graph_link_type_related,
                            "causes" to R.string.memory_graph_link_type_causes,
                            "part_of" to R.string.memory_graph_link_type_part_of,
                            "contradicts" to R.string.memory_graph_link_type_contradicts,
                        ).forEach { (type, labelRes) ->
                            MuseCapsuleButton(
                                text = stringResource(labelRes),
                                onClick = {
                                    onManualLink(srcNode, tgtNode, type)
                                    linkSourceNode = null
                                    linkTargetNode = null
                                },
                                variant = IosCapsuleButtonVariant.Text,
                                fillWidth = true,
                            )
                        }
                    }
                },
            )
        }
    }
}

/** radio del nodo:fijar hacia arriba/La importancia alta es mayor,Los nodos ordinarios son los más pequeños. */
private fun nodeRadiusDp(node: MemoryGraphNode): Float = when {
    node.isPinned || node.importance >= 2 -> 15f
    node.importance == 1 -> 12f
    else -> 9f
}

/**
 * dibujar uno"etiqueta de la cápsula":Rectángulo redondeado inferior claro + trazo + una sola línea de texto.
 *
 * @param center centro de anclaje de etiquetas;anchorRight=true Indica que el texto está adjunto a la izquierda del punto de anclaje.(Alinear a la derecha)
 */
private fun DrawScope.drawChipLabel(
    text: String,
    center: Offset,
    textColor: Color,
    background: Color,
    border: Color,
    density: androidx.compose.ui.unit.Density,
    fontSize: androidx.compose.ui.unit.TextUnit,
    maxWidthPx: Float,
    anchorRight: Boolean = false,
    letterSpacing: Float = 0f,
) {
    if (text.isBlank()) return
    val paint = android.graphics.Paint().apply {
        this.color = textColor.toArgb()
        textSize = with(density) { fontSize.toPx() }
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.LEFT
        // letterSpacing La unidad es em,Convierta aquí según múltiplos de tamaño de fuente
        this.letterSpacing = letterSpacing / fontSize.value.coerceAtLeast(1f)
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
    }
    val ellipsis = "…"
    val maxTextWidth = maxWidthPx - 16.dp.toPx()
    val displayText = if (paint.measureText(text) > maxTextWidth) {
        var cut = text.length
        while (cut > 0 && paint.measureText(text.substring(0, cut) + ellipsis) > maxTextWidth) cut--
        text.substring(0, cut) + ellipsis
    } else {
        text
    }
    val textWidth = paint.measureText(displayText)
    val chipWidth = textWidth + 16.dp.toPx()
    val chipHeight = paint.textSize + 12.dp.toPx()
    val left = if (anchorRight) center.x - chipWidth else center.x
    val top = center.y - chipHeight / 2f
    val corner = chipHeight / 2f
    drawRoundRect(
        color = background,
        topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(chipWidth, chipHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
    )
    drawRoundRect(
        color = border,
        topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(chipWidth, chipHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
        style = Stroke(width = 0.7.dp.toPx()),
    )
    drawContext.canvas.nativeCanvas.drawText(
        displayText,
        left + 8.dp.toPx(),
        top + chipHeight / 2f + paint.textSize / 3f,
        paint,
    )
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)

enum class NodeAction { EDIT, DELETE, PIN, LINK }
enum class EdgeAction { CONFIRM, DELETE }

@Composable
private fun categoryLabel(category: String): String = stringResource(categoryStringRes(category))

private fun categoryStringRes(category: String): Int = when (category.lowercase()) {
    "preference" -> R.string.memory_graph_category_preference
    "identity" -> R.string.memory_graph_category_identity
    "event" -> R.string.memory_graph_category_event
    "relationship" -> R.string.memory_graph_category_relationship
    "goal" -> R.string.memory_graph_category_goal
    "medical" -> R.string.memory_graph_category_medical
    else -> R.string.memory_graph_category_general
}

private fun nodeToneColor(
    category: String,
    scheme: androidx.compose.material3.ColorScheme,
    statusColors: io.zer0.muse.ui.theme.MuseStatusColors,
): Color = when (category.lowercase()) {
    "preference" -> scheme.secondary
    "identity" -> scheme.primary
    "event" -> scheme.tertiary
    "relationship" -> scheme.primary.copy(alpha = 0.82f)
    "goal" -> statusColors.success
    "medical" -> statusColors.warning
    else -> scheme.primary
}
