// v1.205 D2: Representación dedicada de los resultados de las herramientas — árbol recursivo/Las vistas son PascalCase Composable,
// El archivo contiene 13 funciones.(recipiente+hojas+ver),Las prácticas del proyecto utilizan exenciones a nivel de archivo(MessageBubbleTail precedente).
@file:Suppress("TooManyFunctions")

package io.zer0.muse.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.markdown.DataCardParser
import io.zer0.muse.ui.markdown.DataCardRenderer
import io.zer0.muse.ui.theme.MusePaddings
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.205 D2: Representación dedicada de los resultados de las herramientas — Reconocer automáticamente tipos de resultados y seleccionar vistas:
 * - JSON(a { / [ Comienzo y analizable)→ Árbol JSON plegable
 * - unified diff(Contiene encabezado de diferencia / @@ hunk / +/- OK)→ Vista de diferencias a nivel de fila
 * - mesa(| separar varias líneas)→ mesa estructurada
 * - Otros → texto plano(Consistente con el viejo comportamiento)
 *
 * El reconocimiento es heurístico.:Cualquier error de análisis volverá a ser texto sin formato.,No lances excepciones para colapsar las burbujas de mensajes.
 */
@Composable
internal fun ToolResultRenderer(result: String, modifier: Modifier = Modifier) {
    val kind = remember(result) { detectResultKind(result) }
    when (kind) {
        ResultKind.DATA_CARD -> {
            val card = remember(result) { DataCardParser.parse(result) }
            if (card == null) {
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = modifier,
                )
            } else {
                Box(modifier = modifier) {
                    DataCardRenderer(card)
                }
            }
        }
        ResultKind.JSON -> JsonTreeView(result, modifier)
        ResultKind.DIFF -> DiffView(result, modifier)
        ResultKind.TABLE -> TableView(result, modifier)
        ResultKind.PLAIN -> Text(
            text = result,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}

private enum class ResultKind { PLAIN, DIFF, JSON, TABLE, DATA_CARD }

/** Reconocimiento de tipo heurístico:JSON > diff > table > plain(El texto plano nunca se confundirá con una tabla). */
private fun detectResultKind(text: String): ResultKind {
    val trimmed = text.trim()
    if (DataCardParser.parse(trimmed) != null) return ResultKind.DATA_CARD
    val isJson = trimmed.isNotEmpty() &&
        (trimmed.startsWith("{") || trimmed.startsWith("[")) &&
        parseJsonOrNull(trimmed) != null
    return when {
        isJson -> ResultKind.JSON
        isDiffLike(text) -> ResultKind.DIFF
        isTableLike(text) -> ResultKind.TABLE
        else -> ResultKind.PLAIN
    }
}

/** unified diff Características:tiene encabezado diferente/hunk cabeza,O hay varias líneas + y - al mismo tiempo. */
private fun isDiffLike(text: String): Boolean {
    val lines = text.lines()
    if (lines.size < 3) return false
    val hasHeader = lines.any { it.startsWith("diff --git") || it.startsWith("@@") }
    val hasAdds = lines.count { it.startsWith("+") } >= 2
    val hasDels = lines.count { it.startsWith("-") } >= 2
    return hasHeader || hasAdds && hasDels
}

/** características de la mesa:Al menos 2 líneas que contengan |,y contiene | proporción de fila ≥60%. */
private fun isTableLike(text: String): Boolean {
    val lines = text.lines()
    val pipeLines = lines.filter { it.contains("|") }
    return pipeLines.size >= 2 && pipeLines.size.toFloat() / lines.size >= 0.6f
}

private fun parseJsonOrNull(text: String): Any? {
    val compact = text.trim()
    return try {
        JSONObject(compact)
    } catch (_: Exception) {
        try {
            JSONArray(compact)
        } catch (_: Exception) {
            null
        }
    }
}

// ── JSON árbol ──────────────────────────────────────────────────────────────

private const val MAX_JSON_DEPTH = 6

@Composable
private fun JsonTreeView(jsonText: String, modifier: Modifier) {
    val root = remember(jsonText) { parseJsonOrNull(jsonText) }
    if (root == null) {
        Text(
            text = jsonText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    Column(modifier = modifier) {
        JsonNode(key = null, value = root, depth = 0)
    }
}

@Composable
private fun JsonNode(key: String?, value: Any, depth: Int) {
    when (value) {
        is JSONObject -> JsonContainerNode(key, value, depth)
        is JSONArray -> JsonArrayNode(key, value, depth)
        else -> JsonLeafRow(key, value)
    }
}

@Composable
private fun JsonContainerNode(key: String?, obj: JSONObject, depth: Int) {
    var expanded by remember { mutableStateOf(depth < MAX_JSON_DEPTH) }
    val keys = obj.keys().asSequence().toList()
    Column {
        JsonToggleRow(key, "{${keys.size}}", expanded, depth) { expanded = !expanded }
        if (expanded) {
            keys.forEach { childKey ->
                val child = obj.opt(childKey)
                JsonNode(
                    key = childKey,
                    value = if (child == JSONObject.NULL) "null" else child,
                    depth = depth + 1,
                )
            }
        }
    }
}

@Composable
private fun JsonArrayNode(key: String?, arr: JSONArray, depth: Int) {
    var expanded by remember { mutableStateOf(depth < MAX_JSON_DEPTH) }
    val size = arr.length()
    Column {
        JsonToggleRow(key, "[$size]", expanded, depth) { expanded = !expanded }
        if (expanded) {
            repeat(size) { idx ->
                val child = arr.opt(idx)
                JsonNode(
                    key = null,
                    value = if (child == JSONObject.NULL) "null" else child,
                    depth = depth + 1,
                )
            }
        }
    }
}

@Composable
private fun JsonToggleRow(key: String?, preview: String, expanded: Boolean, depth: Int, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 12).dp)
            .clickable(onClick = onToggle),
    ) {
        Text(
            text = if (expanded) "▾ " else "▸ ",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.primary,
        )
        if (key != null) {
            Text(
                text = "\"$key\": ",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = preview,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun JsonLeafRow(key: String?, value: Any) {
    Row(modifier = Modifier.fillMaxWidth()) {
        if (key != null) {
            Text(
                text = "\"$key\": ",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = formatJsonLeaf(value),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = when (value) {
                is Boolean, is Int, is Long, is Double -> MaterialTheme.colorScheme.primary
                is String -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private fun formatJsonLeaf(value: Any): String = when (value) {
    is String -> "\"$value\""
    else -> value.toString()
}

// ── diff ver ────────────────────────────────────────────────────────────

private const val STRUCTURED_RENDER_LINE_THRESHOLD = 300

@Composable
private fun DiffView(diffText: String, modifier: Modifier) {
    val lines = remember(diffText) { diffText.lines() }
    // Very large diffs stay complete but use one text node instead of composing one
    // row per line. This avoids silently dropping the tail while keeping the chat
    // list responsive for command output with thousands of lines.
    if (lines.size > STRUCTURED_RENDER_LINE_THRESHOLD) {
        Text(
            text = diffText,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    val addColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    val delColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
    val hunkColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
    Column(modifier = modifier) {
        lines.forEach { line ->
            val bg = when {
                line.startsWith("+") && !line.startsWith("+++") -> addColor
                line.startsWith("-") && !line.startsWith("---") -> delColor
                line.startsWith("@@") -> hunkColor
                else -> null
            }
            Row(
                modifier = if (bg != null) {
                    Modifier.fillMaxWidth().background(bg)
                } else {
                    // Las líneas de diferencias normales no tienen color de fondo,Renderizar con estilo predeterminado
                    Modifier.fillMaxWidth()
                },
            ) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── vista de tabla ─────────────────────────────────────────────────────────────

@Composable
private fun TableView(tableText: String, modifier: Modifier) {
    val rows = remember(tableText) { parseTableRows(tableText) }
    Column(modifier = modifier) {
        rows.forEachIndexed { index, cells ->
            Row(modifier = Modifier.fillMaxWidth()) {
                cells.forEachIndexed { cellIndex, cell ->
                    Text(
                        text = cell,
                        style = if (index == 0) {
                            MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = MusePaddings.contentGap)
                            .widthIn(min = 40.dp),
                    )
                }
            }
        }
    }
}

/** Regularidad de filas separadas de tabla(--- / |---|---| espera),Las constantes a nivel de archivo evitan reconstruir cada análisis. */
private val TABLE_SEPARATOR_REGEX = Regex("^[|:\\-\\s]+$")

/** analizar | mesa separada:eliminar líneas vacías/separador de linea pura,celúla trim,Hay un límite superior en el número de filas y columnas. */
internal fun parseTableRows(tableText: String): List<List<String>> {
    val rows = tableText.lines()
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.matches(TABLE_SEPARATOR_REGEX) }
        .map { line ->
            line.split("|")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
        .filter { it.size >= 2 }
        .toList()
    val maxCols = rows.maxOfOrNull { it.size } ?: return emptyList()
    return rows.map { row ->
        if (row.size < maxCols) row + List(maxCols - row.size) { "" } else row
    }
}
