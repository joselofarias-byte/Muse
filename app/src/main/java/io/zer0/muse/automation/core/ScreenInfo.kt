package io.zer0.muse.automation.core

/**
 * Captura de pantalla de información.
 *
 * Sintetizado a partir del árbol de control de accesibilidad + resumen de dumpsys,Para que la IA juzgue"Qué hay en la pantalla y dónde hacer clic".
 */
data class ScreenInfo(
    /** El nombre del paquete de interfaz actual. */
    val packageName: String? = null,
    /** Nombre de la clase de actividad actual(Si es deseable). */
    val activityName: String? = null,
    /** Interactivo/Una lista legible de nodos de control. */
    val nodes: List<UiNode> = emptyList(),
    /** ancho de captura de pantalla(Píxel),Se utiliza para convertir coordenadas normalizadas en coordenadas absolutas. */
    val screenWidth: Int = 0,
    /** Altura de captura de pantalla(Píxel). */
    val screenHeight: Int = 0,
    /** Etiqueta de fuente de datos: accessibility / shell / root. */
    val source: String = "",
) {
    /**
     * Seleccione el nodo en el árbol de control que más probablemente represente el objetivo del usuario.
     * Prioridad de puntuación: usabilidad → coincidencia exacta → prefijo/contiene coincidencias → Capacidad de hacer clic → Campo de texto.
     * De esta manera, el agente visual y el flujo de trabajo semántico no harán clic aleatoriamente en el texto decorativo con el mismo nombre debido a cambios en el orden transversal del árbol.
     */
    fun findBestTextNode(query: String, exact: Boolean = false): UiNode? {
        val normalized = query.trim()
        if (normalized.isEmpty()) return null
        return nodes.asSequence()
            .filter { it.isEnabled }
            .mapNotNull { node ->
                val text = node.text?.trim()?.takeIf { it.isNotEmpty() }
                val description = node.contentDescription?.trim()?.takeIf { it.isNotEmpty() }
                val label = text ?: description ?: return@mapNotNull null
                val normalizedLabel = label.trim()
                val matches = if (exact) {
                    normalizedLabel == normalized
                } else {
                    normalizedLabel.equals(normalized, ignoreCase = true) ||
                        normalizedLabel.startsWith(normalized, ignoreCase = true) ||
                        normalizedLabel.contains(normalized, ignoreCase = true)
                }
                if (!matches) return@mapNotNull null
                val matchScore = when {
                    normalizedLabel == normalized -> 1_000
                    normalizedLabel.equals(normalized, ignoreCase = true) -> 900
                    normalizedLabel.startsWith(normalized, ignoreCase = true) -> 700
                    else -> 500
                }
                val fieldScore = if (text != null) 30 else 0
                val interactionScore = when {
                    node.isClickable -> 120
                    node.isEditable -> 60
                    else -> 0
                }
                node to (matchScore + interactionScore + fieldScore)
            }
            .sortedByDescending { it.second }
            .firstOrNull()
            ?.first
    }

    /** Choose a stable resource-id target, accepting either the full id or the suffix after '/'. */
    fun findBestViewIdNode(viewId: String): UiNode? {
        val query = viewId.trim().removePrefix("@").removePrefix("id/")
        if (query.isEmpty()) return null
        return nodes.asSequence()
            .filter { it.isEnabled }
            .mapNotNull { node ->
                val candidate = node.viewIdResourceName?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val suffix = candidate.substringAfterLast('/')
                if (candidate != query && suffix != query && candidate != "id/$query") return@mapNotNull null
                val score = (if (candidate == query) 1_000 else 900) +
                    (if (node.isClickable) 120 else if (node.isEditable) 60 else 0)
                node to score
            }
            .sortedByDescending { it.second }
            .firstOrNull()
            ?.first
    }

    /** Genere resúmenes de texto compactos para que los lea la IA. */
    fun toSummary(maxNodes: Int = 40): String = buildString {
        appendLine("Solicitud actual: ${packageName ?: "desconocido"}")
        if (!activityName.isNullOrBlank()) appendLine("interfaz: $activityName")
        appendLine("resolución: ${screenWidth}x$screenHeight")
        appendLine("Número de controles: ${nodes.size}")
        appendLine("---")
        nodes.take(maxNodes).forEachIndexed { i, n ->
            appendLine("[$i] ${n.toShortString()}")
        }
        if (nodes.size > maxNodes) appendLine("... también ${nodes.size - maxNodes} nodos")
    }
}

/**
 * UI Nodo de control(Representación unificada entre capas,Bloquear accesibilidad/dumpsys diferencia).
 */
data class UiNode(
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val viewIdResourceName: String? = null,
    /** Controlar la esquina superior izquierda x(píxeles absolutos). */
    val boundsLeft: Int = 0,
    val boundsTop: Int = 0,
    /** x en la esquina inferior derecha del control. */
    val boundsRight: Int = 0,
    val boundsBottom: Int = 0,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isChecked: Boolean? = null,
    val isEnabled: Boolean = true,
) {
    /** Centro x,Para que la IA regrese al hacer clic en el objetivo. */
    val centerX: Int get() = (boundsLeft + boundsRight) / 2
    val centerY: Int get() = (boundsTop + boundsBottom) / 2

    fun toShortString(): String {
        val label = text?.take(30)
            ?: contentDescription?.take(30)
            ?: viewIdResourceName?.substringAfterLast('/')
            ?: className?.substringAfterLast('.')
            ?: "Sin nombre"
        val tags = buildList {
            if (isClickable) add("Se puede pedir")
            if (isEditable) add("Se puede ingresar")
            if (isScrollable) add("desplazable")
            if (isChecked == true) add("comprobado")
            if (!isEnabled) add("Desactivar")
        }
        val tagStr = if (tags.isEmpty()) "" else " [${tags.joinToString(",")}]"
        val bounds = "($centerX,$centerY)"
        return "$label$tagStr $bounds"
    }
}
