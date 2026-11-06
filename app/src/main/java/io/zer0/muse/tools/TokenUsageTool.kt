package io.zer0.muse.tools

import io.zer0.muse.data.stats.UsageRecordDao
import io.zer0.muse.data.stats.UsageTotals
import java.time.Instant
import java.time.ZoneId

/**
 * get_token_usage Herramienta: El modelo autoverifica su propio consumo de tokens y ocupación de contexto.
 *
 * Los datos están en capas, cada una de las cuales proviene del lugar donde realmente se recuerdan:
 *  - cantidad total/Hoy:`usage_records` Canalización (una línea por solicitud de modelo, incluidas solicitudes que no son de diálogo, como compresión y creación de títulos),
 *    Reutilización directa [UsageRecordDao] La consulta de agregación existente no introduce un segundo conjunto de calibres.
 *  - Esta sesión:`usage_records` No hay dimensión de sesión. El número de esta sesión se toma de la medición real del proveedor en mensajes.
 *    Campo de uso (inyectado en el lector por el host). La identificación de la sesión está dada por [ToolExecutionContext] Siempre que el modelo no pueda ser falsificado,
 *    Si no puede obtenerlo, simplemente informe la situación general y no adivine según la ventana de tiempo.
 *  - Ocupación de contexto: pertenece al estado de memoria de ViewModel y el host la inyecta en el proveedor; si no se inyecta, se omite toda la línea.
 *
 * La salida se fija en unas pocas líneas de texto breve y los números se abrevian como k/M, que se pueden leer en una pantalla.
 */
object TokenUsageTool {

    const val NAME = "get_token_usage"

    private const val SCOPE_TODAY = "today"
    private const val SCOPE_SESSION = "session"
    private const val SCOPE_ALL = "all"

    private const val TOP_MODEL_LIMIT = 5
    private const val TOP_SOURCE_LIMIT = 4

    /** Uso acumulativo de esta sesión (requests / entrada/salida). */
    data class SessionUsage(
        val requests: Int,
        val promptTokens: Long,
        val completionTokens: Long,
    )

    fun toolDef() = ToolRegistry.ToolDef(
        name = NAME,
        description = "Consumo de token de consulta y ocupación de contexto: acumulado en esta sesión, hoy/Total acumulado de todas las fuentes, distribución por modelo," +
            "Ocupación del contexto actual y límite superior. Los usuarios preguntan \"¿Cuántos tokens se han utilizado?\" \"¿Cuánto se ha gastado hoy?\" \"¿Cuánto contexto queda?\"" +
            "O llámelo cuando necesite autoverificar los costos. Lectura pura, sin parámetros requeridos.",
        parameters = mapOf(
            "scope" to
                "Optional. 'today' (default) = all sources since local midnight; " +
                "'session' = this conversation only; 'all' = everything recorded so far.",
        ),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    /**
     * Ejecute la consulta.
     *
     * @param dao El registro de uso es de sólo lectura; cuando es nulo, informará claramente que no está disponible y no se utilizarán datos vacíos para fingir que tiene éxito.
     * @param sessionUsage Este lector de uso de sesión (el parámetro de entrada es la identificación de sesión inyectada por el host).
     * @param contextOccupancy proveedor de ocupación de contexto (ocupación actual, Límite superior); si no está disponible, devuelve nulo.
     * @param now La hora actual, se puede inyectar la prueba.
     * @param zone zona horaria,"hoy"Comienza a las 00:00 en esta zona horaria.
     */
    @Suppress("LongParameterList")
    suspend fun execute(
        args: Map<String, String>,
        context: ToolExecutionContext,
        dao: UsageRecordDao?,
        sessionUsage: (suspend (String) -> SessionUsage?)? = null,
        contextOccupancy: (() -> Pair<Int, Int>?)? = null,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (dao == null) return "Error: token usage records are unavailable."
        val scope = args["scope"]?.trim()?.lowercase()?.ifEmpty { SCOPE_TODAY } ?: SCOPE_TODAY
        if (scope != SCOPE_TODAY && scope != SCOPE_SESSION && scope != SCOPE_ALL) {
            return "Error: scope must be 'today', 'session' or 'all'."
        }

        val lines = mutableListOf<String>()
        val sessionId = context.sessionId?.trim().orEmpty()
        val session = readSessionUsage(sessionId, sessionUsage)

        if (scope == SCOPE_SESSION) {
            lines += sessionLine(sessionId, session)
        } else {
            val label =
                if (scope == SCOPE_ALL) {
                    "all time"
                } else {
                    "today " + Instant.ofEpochMilli(now).atZone(zone).toLocalDate() + " (since local midnight)"
                }
            lines += "Token usage · $label"
            lines += windowLines(dao, if (scope == SCOPE_ALL) 0L else startOfLocalDay(now, zone))
            if (sessionId.isNotEmpty() && session != null) lines += sessionLine(sessionId, session)
        }
        contextLine(contextOccupancy)?.let { lines += it }

        return if (lines.isEmpty()) "No token usage recorded yet." else lines.joinToString("\n")
    }

    // ── Lectura de datos ──

    private suspend fun readSessionUsage(
        sessionId: String,
        reader: (suspend (String) -> SessionUsage?)?,
    ): SessionUsage? {
        if (sessionId.isEmpty() || reader == null) return null
        return runCatching { reader(sessionId) }.getOrNull()
    }

    /** Tres líneas de total/fuente/modelo dentro de la ventana de tiempo; si no hay registro, dé una explicación clara. */
    private suspend fun windowLines(dao: UsageRecordDao, sinceMs: Long): String {
        val totals: UsageTotals? = runCatching { dao.totalsSince(sinceMs) }.getOrNull()
        if (totals == null || totals.requests <= 0) return "  total: no requests recorded in this window"
        val models =
            runCatching { dao.topModelsSince(sinceMs, TOP_MODEL_LIMIT) }.getOrDefault(emptyList())
        val sources =
            runCatching { dao.bySourceSince(sinceMs) }.getOrDefault(emptyList())

        val total = totals.promptTokens + totals.completionTokens
        val head = buildString {
            append("  total: ${totals.requests} req · in ${formatTokens(totals.promptTokens)}")
            append(" · out ${formatTokens(totals.completionTokens)} · all ${formatTokens(total)}")
            if (totals.cachedTokens > 0) append(" · cached ${formatTokens(totals.cachedTokens)}")
        }
        val sourceText =
            sources.take(TOP_SOURCE_LIMIT).joinToString(" · ") { row ->
                "${row.source} ${formatTokens(row.promptTokens + row.completionTokens)} (${row.requests})"
            }
        val modelText =
            models.joinToString(" · ") { row -> "${row.modelId} ${formatTokens(row.totalTokens)} (${row.requests})" }

        return buildString {
            appendLine(head)
            if (sourceText.isNotEmpty()) appendLine("  sources: $sourceText")
            if (modelText.isNotEmpty()) append("  models: $modelText")
        }.trimEnd('\n')
    }

    /** línea para esta sesión. Cuando no se pueda obtener el ID de la sesión, o no haya un uso medido real, se darán instrucciones claras sin omisiones ni conjeturas. */
    private fun sessionLine(sessionId: String, usage: SessionUsage?): String = when {
        sessionId.isEmpty() -> "  session: unavailable (current session id is unknown)"
        usage == null -> "  session: no measured usage yet in this conversation"
        usage.requests <= 0 -> "  session: no measured usage yet in this conversation"
        else ->
            "  session: ${usage.requests} req · in ${formatTokens(usage.promptTokens)}" +
                " · out ${formatTokens(usage.completionTokens)}" +
                " · all ${formatTokens(usage.promptTokens + usage.completionTokens)}"
    }

    /** El contexto ocupa esa fila; falta el proveedor o se desconoce el límite superior (0) devuelve nulo y se omite toda la línea. */
    private fun contextLine(provider: (() -> Pair<Int, Int>?)?): String? {
        val pair = runCatching { provider?.invoke() }.getOrNull() ?: return null
        val used = pair.first.coerceAtLeast(0)
        val max = pair.second
        if (max <= 0) return null
        return "  context: ${formatTokens(used.toLong())} / ${formatTokens(max.toLong())} (${used * 100 / max}%)"
    }

    // ── Función pura (para probar el calibre)──

    /** La marca de tiempo de milisegundos de las 00:00 de hoy en la zona horaria local. */
    internal fun startOfLocalDay(nowMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /** Abreviatura numérica:<1k tal cual, de lo contrario presione k / M / B Abreviaturas, los decimales se conservan sólo cuando sea necesario (12.3k / 21k). */
    internal fun formatTokens(value: Long): String {
        val v = value.coerceAtLeast(0)
        return when {
            v < 1_000L -> v.toString()
            v < 1_000_000L -> scaled(v, 1_000L, "k")
            v < 1_000_000_000L -> scaled(v, 1_000_000L, "M")
            else -> scaled(v, 1_000_000_000L, "B")
        }
    }

    private fun scaled(value: Long, unit: Long, suffix: String): String {
        val tenths = value * 10 / unit
        val fraction = tenths % 10
        return if (fraction == 0L) "${tenths / 10}$suffix" else "${tenths / 10}.$fraction$suffix"
    }
}
