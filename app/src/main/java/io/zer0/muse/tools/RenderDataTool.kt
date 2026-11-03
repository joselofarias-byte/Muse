package io.zer0.muse.tools

import io.zer0.common.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Structured chart/table renderer for tool results.
 *
 * Charts are emitted as the existing ```card format and tables as bounded
 * Markdown. The chat tool-result renderer owns the visual presentation.
 */
object RenderDataTool {

    const val NAME = "render_data"

    // Keep only structural guardrails; the orchestrator can spill large results to a complete
    // file reference, so this renderer must not reject or truncate otherwise valid user data.
    private const val MAX_COLUMNS = 64
    private const val MAX_ROWS = 2_000

    private val CHART_TYPES = setOf("bar", "line", "donut")

    fun toolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME,
        description =
        "Muestra datos estructurados directamente en el chat.type=bar/line/donut cuando los datos necesitan ser " +
            "{\"labels\":[...],\"values\":[...]};type=table cuando los datos necesitan ser " +
            "{\"columns\":[...],\"rows\":[[...]]}. Los datos solo se procesan localmente y no acceden a la red.",
        parameters = mapOf(
            "type" to "Requerido:bar | line | donut | table",
            "title" to "Requerido,Título del gráfico o tabla",
            "data" to "Datos JSON requeridos:Uso de gráficos labels/values,Uso de la mesa columns/rows",
        ),
        required = setOf("type", "title", "data"),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
        // data son objetos anidados (etiquetas/valores de gráfico, columnas/filas de tabla).
        // La implementación anterior no declaraba el tipo. → schema De forma predeterminada, está marcada como cadena y el modelo la pasará como una cadena JSON con escape.
        // El defecto de reducción de escape de la extracción de parámetros superpuestos provocará un error en el análisis. Aquí el objeto se declara explícitamente.
        parameterTypes = mapOf("data" to "object"),
    )

    fun execute(args: Map<String, String>): String {
        val type = args["type"]?.trim()?.lowercase()
        val title = args["title"]?.let(::cleanText)
        val rawData = args["data"]?.trim()
        val data = rawData?.let { runCatching { AppJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val rendered =
            when {
                type == null -> error("desaparecido type")
                title == null -> error("desaparecido title")
                title.isBlank() -> error("title no puede estar vacío")
                rawData == null -> error("desaparecido data")
                data == null -> error("data Debe ser un objeto JSON")
                type in CHART_TYPES -> renderChart(type, title, data)
                type == "table" -> renderTable(title, data)
                else -> error("type Solo soporta barra,line,donut o table")
            }
        return rendered
    }

    private fun renderChart(type: String, title: String, data: JsonObject): String {
        val labels = stringArray(data["labels"])
        val values = numberArray(data["values"])
        // Cada una de las ramas de verificación produce un texto de error; Sólo cuando todo pasa, se produce la tarjeta JSON. ambos lados son cuerdas,
        // Por lo tanto, no se requiere ningún criterio de tipo adicional y no se incluirá la redacción incorrecta por error. ```card bloque de código.
        return when {
            labels == null -> error("Las etiquetas de los gráficos deben ser una matriz de cadenas.")
            values == null -> error("Los valores del gráfico deben ser una matriz finita de números.")
            labels.isEmpty() || values.isEmpty() -> error("Los datos del gráfico no pueden estar vacíos")
            labels.size != values.size -> error("labels Debe tener la misma longitud que los valores.")
            labels.size > MAX_ROWS -> error("Los datos del gráfico admiten hasta $MAX_ROWS punto")
            else -> {
                val cardJson =
                    buildJsonObject {
                        put("type", JsonPrimitive(type))
                        put("title", JsonPrimitive(title))
                        put("labels", buildJsonArray { labels.forEach { add(JsonPrimitive(it)) } })
                        put("values", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
                    }
                "```card\n$cardJson\n```"
            }
        }
    }

    private fun renderTable(title: String, data: JsonObject): String {
        val columns = stringArray(data["columns"])
        val rows = data["rows"] as? JsonArray
        val shapeError = validateTableShape(columns, rows)
        val normalizedRows = if (shapeError == null && columns != null && rows != null) {
            rows.map { stringArray(it) }
        } else {
            null
        }
        val rowsError = if (columns != null && normalizedRows != null) {
            normalizedRows.withIndex()
                .firstOrNull { (_, cells) -> cells == null || cells.size != columns.size }
                ?.let { (index, cells) ->
                    if (cells == null) {
                        error("No. ${index + 1} la fila debe ser una cadena/conjunto de números")
                    } else {
                        error("No. ${index + 1} El número de filas y columnas no coincide con el de las columnas.")
                    }
                }
        } else {
            null
        }
        return when {
            shapeError != null -> shapeError
            rowsError != null -> rowsError
            columns != null && normalizedRows != null -> renderTableBody(title, columns, normalizedRows)
            else -> error("Los datos de la tabla están incompletos.")
        }
    }

    /** mesa"forma"Verificación de nivel (columna en sí, número de fila), devuelve nulo para indicar que se aprobó. */
    private fun validateTableShape(columns: List<String>?, rows: JsonArray?): String? = when {
        columns == null -> error("Las columnas de la tabla deben ser una matriz de cadenas.")
        columns.isEmpty() -> error("La tabla requiere al menos una columna.")
        columns.size > MAX_COLUMNS -> error("El formulario admite hasta $MAX_COLUMNS columna")
        rows == null -> error("Las filas de la tabla deben ser una matriz bidimensional")
        rows.size > MAX_ROWS -> error("El formulario admite hasta $MAX_ROWS OK")
        else -> null
    }

    private fun renderTableBody(title: String, columns: List<String>, rows: List<List<String>?>): String = buildString {
        appendLine("### $title")
        appendLine("| ${columns.joinToString(" | ")} |")
        appendLine("| ${columns.joinToString(" | ") { "---" }} |")
        rows.forEach { row ->
            appendLine("| ${row.orEmpty().joinToString(" | ")} |")
        }
    }.trimEnd()

    private fun stringArray(element: kotlinx.serialization.json.JsonElement?): List<String>? {
        val array = element as? JsonArray ?: return null
        val cleaned = array.map { primitive ->
            (primitive as? JsonPrimitive)?.contentOrNull()?.let(::cleanText)?.takeIf { it.isNotBlank() }
        }
        return cleaned.takeIf { values -> values.all { it != null } }?.mapNotNull { it }
    }

    private fun numberArray(element: kotlinx.serialization.json.JsonElement?): List<Double>? {
        val array = element as? JsonArray ?: return null
        val numbers = array.map { primitive ->
            (primitive as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }
        }
        return numbers.takeIf { values -> values.all { it != null } }?.mapNotNull { it }
    }

    private fun cleanText(value: String): String = value.replace('|', ' ').replace('\r', ' ').replace('\n', ' ').trim()

    private fun error(message: String): String = "Error: $message"

    private fun JsonPrimitive.contentOrNull(): String? = content.takeIf { it.isNotBlank() }
}
