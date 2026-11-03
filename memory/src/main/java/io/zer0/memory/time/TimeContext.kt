package io.zer0.memory.time

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Herramientas de contexto temporal.
 *
 * Responsabilidades:
 *  - Analizar la zona horaria de la memoria (por defecto Asia/Shanghai)
 *  - Calcular el día lógico (volver al día anterior antes de las 4 a. m. para evitar que se corten las conversaciones nocturnas)“mañana”)
 *  - Dar formato a la hora con zona horaria (para etiquetado de tiempo resumido)
 *  - Construir a partir de la lista de mensajes source_time_range
 *  - Extraer señales horarias del texto resumido (YYYY-MM-DD HH:MM)
 *  - Normalizar el tiempo de hecho (verificar si el tiempo extraído por LLM está dentro del rango de origen)
 */
object TimeContext {

    /** Zona horaria predeterminada. */
    const val DEFAULT_TIMEZONE = "Asia/Shanghai"

    /** Punto de corte lógico del día: antes de las 04:00 de la mañana se cuenta el día anterior (para evitar que se corten las conversaciones nocturnas)“mañana”). */
    const val LOGICAL_DAY_CUTOVER_HOUR = 4

    /** 6 Paso por hora (para usar con CollectLocalDatesInRange, un día para usuarios que cruzan la noche puede abarcar 2 días locales). */
    const val SIX_HOURS_MS = 6L * 60 * 60 * 1000

    /** collectLocalDatesInRange Límite de iteración (para evitar bucles infinitos). */
    const val COLLECT_MAX_ITERATIONS = 400

    /**
     * Reparación de auditoría (C-03): Ventana de lanzamiento de fecha futura (días).
     * Escenario:"el próximo miércoles""Mudarse la próxima semana"etc. Los eventos del futuro cercano basados en representaciones relativas no caen dentro de la fecha local de la sesión,
     * La lógica original siempre devolverá nulo, lo que provocará la pérdida de información de tiempo; déjalo ir aquí [hoy, hoy+30 dia] la fecha futura cercana de
     * Las ventanas superiores todavía se consideran rechazos de fabricación de LLM.
     */
    const val FUTURE_DATE_WINDOW_DAYS = 30L

    private val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val summaryTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    /** Analizar la cadena de zona horaria, volver al valor predeterminado si falla. */
    fun resolveTimeZone(raw: String?): ZoneId = runCatching { ZoneId.of(raw?.takeIf { it.isNotBlank() } ?: DEFAULT_TIMEZONE) }
        .getOrElse { ZoneId.of(DEFAULT_TIMEZONE) }

    /** Día lógico:04:00 Antes cuenta como el día anterior. Devuelve el punto de inicio de las 00:00 del día lógico. */
    fun logicalDayFor(now: Instant = Instant.now(), zone: ZoneId = ZoneId.of(DEFAULT_TIMEZONE)): LogicalDay {
        val zdt = ZonedDateTime.ofInstant(now, zone)
        val cutoff = zdt.toLocalDate().atStartOfDay(zone).plusHours(LOGICAL_DAY_CUTOVER_HOUR.toLong())
        val logicalDate = if (zdt.isBefore(cutoff)) {
            zdt.toLocalDate().minusDays(1)
        } else {
            zdt.toLocalDate()
        }
        val rangeStart = logicalDate.atStartOfDay(zone).plusHours(LOGICAL_DAY_CUTOVER_HOUR.toLong()).toInstant()
        return LogicalDay(
            logicalDate = logicalDate,
            rangeStart = rangeStart,
        )
    }

    /** Formatear la hora con la zona horaria (utilizada para etiquetar la hora en el resumen, como "2026-07-04 15:30 +08:00"). */
    fun formatSummaryTimestamp(instant: Instant, zone: ZoneId = ZoneId.of(DEFAULT_TIMEZONE)): String {
        val zdt = ZonedDateTime.ofInstant(instant, zone)
        return zdt.format(summaryTimeFormatter)
    }

    /** Formatee la marca de tiempo ISO. */
    fun formatIso(instant: Instant = Instant.now()): String = instant.toString()

    /**
     * Construya source_time_range a partir de una lista de mensajes.
     * Devuelve una lista de fechas locales que abarcan la marca de tiempo mínima y máxima.
     */
    fun sourceTimeRangeOf(timestamps: List<String>, zone: ZoneId = ZoneId.of(DEFAULT_TIMEZONE)): SourceTimeRange? {
        val instants = timestamps.mapNotNull { parseInstant(it) }
        if (instants.isEmpty()) return null
        val start = instants.min()
        val end = instants.max()
        val localDates = collectLocalDatesInRange(start, end, zone)
        return SourceTimeRange(
            start = start.toString(),
            end = end.toString(),
            timezone = zone.id,
            localDates = localDates.map { it.toString() },
        )
    }

    /** recoger [start, end] Las fechas locales se extendieron en el medio (en pasos de 6 horas para evitar bucles infinitos). */
    private fun collectLocalDatesInRange(start: Instant, end: Instant, zone: ZoneId): List<LocalDate> {
        if (start.isAfter(end)) return emptyList()
        val dates = linkedSetOf<LocalDate>()
        var cursor = start
        var iterations = 0
        while (!cursor.isAfter(end) && iterations < COLLECT_MAX_ITERATIONS) {
            dates += ZonedDateTime.ofInstant(cursor, zone).toLocalDate()
            cursor = cursor.plusMillis(SIX_HOURS_MS)
            iterations++
        }
        // Agregue el final al final de la fecha de hoy (para evitar omitir pasos)
        dates += ZonedDateTime.ofInstant(end, zone).toLocalDate()
        return dates.toList()
    }

    /** Analiza la marca de tiempo ISO, devuelve nulo si falla. */
    fun parseInstant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }
            .recoverCatching { LocalDate.parse(value).atStartOfDay(ZoneId.of(DEFAULT_TIMEZONE)).toInstant() }
            .getOrNull()
    }

    /** Extraer señales horarias del texto resumido (YYYY-MM-DD HH:MM o pura fecha o pura hora). */
    fun scanSummaryTimeSignals(summary: String): SummaryTimeSignals {
        if (summary.isEmpty()) return SummaryTimeSignals(emptyList(), emptyList(), emptyList())
        val dateTimes = Regex("""\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}""")
            .findAll(summary).map { it.value }.toList()
        val dates = Regex("""\b\d{4}-\d{2}-\d{2}\b""")
            .findAll(summary).map { it.value }.toList()
        val times = Regex("""\b\d{2}:\d{2}\b""")
            .findAll(summary).map { it.value }.toList()
        return SummaryTimeSignals(dateTimes, dates, times)
    }

    /**
     * Tiempo de hecho normalizado.LLM El tiempo contratado deberá satisfacer:
     *  1. Formato YYYY-MM-DDTHH:MM
     *  2. Las fechas deben estar dentro de sourceTimeRange.localDates o ser una fecha en el futuro cercano (≤ now + [FUTURE_DATE_WINDOW_DAYS] días)
     *     —— Nos vemos luego [isAcceptedFactDate](C-03: liberación"el próximo miércoles"Espere al futuro cercano para evitar pérdida de tiempo)
     *  3. Abarca varios días y solo HH:MM → null(No se puede localizar el día)
     */
    fun normalizeFactTimestamp(value: String?, ctx: FactTimeContext): String? {
        if (value.isNullOrBlank()) return null
        val v = value.trim()

        // completo YYYY-MM-DDTHH:MM
        val fullMatch = Regex("""^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2})$""").find(v)
        if (fullMatch != null) {
            val (date, time) = fullMatch.destructured
            return if (isAcceptedFactDate(ctx, date)) "${date}T$time" else null
        }

        // solo HH:MM
        val timeMatch = Regex("""^(\d{2}:\d{2})$""").find(v)
        if (timeMatch != null) {
            val time = timeMatch.groupValues[1]
            // Cuando solo hay una fecha de origen, agregue la fecha
            if (ctx.localDates.size == 1) {
                return "${ctx.localDates[0]}T$time"
            }
            return null // No se puede localizar cuando hay varios días
        }

        // solo YYYY-MM-DD
        val dateMatch = Regex("""^(\d{4}-\d{2}-\d{2})$""").find(v)
        if (dateMatch != null) {
            val date = dateMatch.groupValues[1]
            return if (isAcceptedFactDate(ctx, date)) "${date}T00:00" else null
        }

        return null
    }

    /**
     * Reparación de auditoría (C-03): Determina si una fecha se puede utilizar como fecha de hecho.
     * La fecha local de la sesión se publica directamente; de lo contrario, la liberación cae dentro [hoy, hoy+FUTURE_DATE_WINDOW_DAYS] futuro cercano
     * (como"el próximo miércoles""Mudarse la próxima semana"), LLM infiere estos eventos en función de expresiones relativas, pero no se encuentran dentro de la fecha de origen de la sesión,
     * La lógica original los establece como nulos y pierde la información de tiempo. El LLM aún considera que las fechas fuera de esta ventana son inventadas y las rechazan.
     */
    private fun isAcceptedFactDate(ctx: FactTimeContext, dateStr: String): Boolean {
        if (dateStr in ctx.localDates) return true
        val date = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return false
        val today = LocalDate.now(ZoneId.of(DEFAULT_TIMEZONE))
        val maxFuture = today.plusDays(FUTURE_DATE_WINDOW_DAYS)
        return date >= today && date <= maxFuture
    }

    /** Día de la lógica. */
    data class LogicalDay(
        val logicalDate: LocalDate,
        val rangeStart: Instant,
    )

    /** source_time_range. */
    @Serializable
    data class SourceTimeRange(
        val start: String,
        val end: String,
        val timezone: String,
        val localDates: List<String>,
    )

    /** Señales temporales en texto resumido. */
    data class SummaryTimeSignals(
        val dateTimes: List<String>,
        val dates: List<String>,
        val times: List<String>,
    )

    /** fact Contexto de normalización horaria. */
    data class FactTimeContext(
        val localDates: List<String>,
        /**
         * Señales horarias escaneadas en texto resumido (se conserva la redundancia).
         * C-03 Revisión:normalizeFactTimestamp Este campo no se utiliza actualmente——Esta corrección se implementará en una fecha futura.
         * [isAcceptedFactDate] Determinación de ventana, no es necesario depender de la señal resumida original para participar en la verificación; conservar este campo
         * Firma para la construcción de FactTimeContext solo para compatibilidad con versiones anteriores, si es necesario en el futuro"Sólo HH:MM + fecha del resumen"La desambiguación se puede ampliar aquí.
         */
        val summaryTimes: List<String>,
    )
}
