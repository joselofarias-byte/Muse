package io.zer0.muse.ui.memory

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Coordenadas de nodos de constelación memorizadas de manera determinista para usar con pruebas de regresión de diseño y de interfaz de usuario. */
data class ConstellationPoint(val x: Dp, val y: Dp)

/** v2.0 constelación de cúmulos:centro de nodo(dp valor numérico)con puntos de anclaje del racimo. */
data class ConstellationCenter(val x: Float, val y: Float)

data class ClusterAnchor(
    val category: String,
    val x: Float,
    val y: Float,
    /** Radio del anillo de nodos dentro del grupo(dp),Se utiliza para dibujar fondos con luz suave. */
    val spread: Float,
)

data class ClusterConstellation(
    val nodeCenters: Map<Long, ConstellationCenter>,
    val clusters: List<ClusterAnchor>,
)

private const val GOLDEN_ANGLE = 2.399963f // esquina dorada(radianes)

/** Orden de visualización de categorías(Consistente con las etiquetas de categoría de la página de memoria.),La categoría desconocida ocupa el último lugar. */
private val CATEGORY_ORDER = listOf(
    "identity",
    "preference",
    "relationship",
    "event",
    "goal",
    "medical",
)

/**
 * v2.0 Diseño de constelación de memoria:Agrupar primero por categoría,Luego se distribuyen en círculo dentro del grupo.
 *
 * En comparación con el antiguo esparcidor en espiral simple,Los recuerdos de un mismo tema se reúnen para formar"grupo de constelaciones",Repartido entre racimos con cuernos dorados.,
 * En general más legible;El diseño está completamente determinado por la colección de nodos.,La misma entrada siempre da como resultado la misma imagen.
 * Coordenadas de retorno como centro del nodo(dp valor numérico),Utilizado directamente para dibujar en Canvas.
 */
internal fun buildClusterConstellation(nodes: List<MemoryGraphNode>, sizeDp: Float): ClusterConstellation {
    if (nodes.isEmpty()) return ClusterConstellation(emptyMap(), emptyList())
    val groups = nodes.groupBy { it.category.lowercase() }.entries
        .sortedWith(
            compareBy(
                { entry -> CATEGORY_ORDER.indexOf(entry.key).let { if (it < 0) CATEGORY_ORDER.size else it } },
                { entry -> entry.key },
            ),
        )
    val center = sizeDp / 2f
    val clusterCount = groups.size
    val spread = sizeDp * 0.29f // v2.0 Ajustes de lanzamiento:0.26→0.29,Más espacio entre clusters
    val anchors = mutableListOf<ClusterAnchor>()
    val nodeCenters = mutableMapOf<Long, ConstellationCenter>()

    groups.forEachIndexed { ci, entry ->
        val angle = ci * GOLDEN_ANGLE
        val ringRadius = if (clusterCount <= 1) {
            0f
        } else {
            spread * (0.32f + 0.68f * ci.toFloat() / (clusterCount - 1).toFloat())
        }
        val cx = center + cos(angle) * ringRadius
        val cy = center + sin(angle) * ringRadius
        val members = entry.value.sortedBy { it.factId }
        val count = members.size
        val nodeRing = clusterNodeRing(count)
        members.forEachIndexed { ni, node ->
            val a = ni * GOLDEN_ANGLE
            // Jitter fijo:Deje que los nodos del grupo no caigan completamente sobre el círculo geométrico.,Mantenga el diseño correcto
            val jitter = 1f - (node.factId % 5) * 0.05f
            val nx = cx + cos(a) * nodeRing * jitter
            val ny = cy + sin(a) * nodeRing * jitter
            nodeCenters[node.factId] = ConstellationCenter(nx, ny)
        }
        anchors += ClusterAnchor(entry.key, cx, cy, maxOf(nodeRing, 90f))
    }
    return ClusterConstellation(nodeCenters, anchors)
}

/** Radio del anillo de nodos dentro del grupo(dp),Se utiliza para estimar el tamaño del lienzo y dibujar. */
internal fun clusterNodeRing(count: Int): Float =
    if (count <= 1) 0f else 28f + sqrt(count.toDouble()).toFloat() * 25f // v2.0 Ajustes de lanzamiento:26/22→28/25,Más dispersos

internal fun buildConstellationCoordinates(
    nodes: List<MemoryGraphNode>,
    contentWidth: Dp,
    contentHeight: Dp,
): Map<Long, ConstellationPoint> {
    val sorted = nodes.sortedWith(
        compareByDescending<MemoryGraphNode> { it.isPinned }
            .thenByDescending { it.importance }
            .thenByDescending { it.confidence }
            .thenBy { it.factId },
    )
    val centerX = contentWidth.value / 2f
    val centerY = contentHeight.value / 2f
    val goldenAngle = Math.PI * (3.0 - sqrt(5.0))
    return sorted.mapIndexed { index, node ->
        val radius = 170.0 + sqrt(index.toDouble() + 1.0) * 175.0
        val angle = index * goldenAngle - Math.PI / 2.0
        val x = (centerX + cos(angle) * radius - 85.0).coerceAtLeast(16.0)
        val y = (centerY + sin(angle) * radius - 34.0).coerceAtLeast(16.0)
        node.factId to ConstellationPoint(x.dp, y.dp)
    }.toMap()
}

/**
 * Las etiquetas de categoría son independientes del diseño del nodo y tienen/Otras etiquetas detectan colisiones.
 * La implementación anterior toma directamente el punto promedio de los nodos de clasificación. Cuando haya más nodos, las etiquetas se presionarán en la tarjeta, que parece nodos superpuestos.
 */
internal fun buildCategoryCoordinates(
    groups: Map<String, List<MemoryGraphNode>>,
    nodeCoordinates: Map<Long, ConstellationPoint>,
    labelWidth: Float = 120f,
    labelHeight: Float = 28f,
): Map<String, ConstellationPoint> {
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float)
    fun intersects(a: Rect, b: Rect, gap: Float = 8f): Boolean = a.left < b.right + gap && b.left < a.right + gap &&
        a.top < b.bottom + gap && b.top < a.bottom + gap

    val nodeRects = nodeCoordinates.values.map { point ->
        Rect(point.x.value, point.y.value, point.x.value + 170f, point.y.value + 68f)
    }
    val placed = mutableListOf<Rect>()
    return groups.keys.sorted().associateWith { category ->
        val points = groups[category].orEmpty().mapNotNull { nodeCoordinates[it.factId] }
        val centerX = points.map { it.x.value }.average().toFloat()
        val centerY = points.map { it.y.value }.average().toFloat()
        val goldenAngle = Math.PI * (3.0 - kotlin.math.sqrt(5.0))
        var chosen = Rect(centerX - labelWidth / 2f, centerY - labelHeight / 2f, centerX + labelWidth / 2f, centerY + labelHeight / 2f)
        for (attempt in 0..80) {
            val radius = if (attempt == 0) 0f else 46f + kotlin.math.sqrt(attempt.toDouble()).toFloat() * 34f
            val angle = attempt * goldenAngle - Math.PI / 2.0
            val candidate = Rect(
                centerX + cos(angle).toFloat() * radius - labelWidth / 2f,
                centerY + sin(angle).toFloat() * radius - labelHeight / 2f,
                centerX + cos(angle).toFloat() * radius + labelWidth / 2f,
                centerY + sin(angle).toFloat() * radius + labelHeight / 2f,
            )
            if (nodeRects.none { intersects(candidate, it) } && placed.none { intersects(candidate, it) }) {
                chosen = candidate
                break
            }
            chosen = candidate
        }
        placed += chosen
        ConstellationPoint(chosen.left.dp, chosen.top.dp)
    }
}
