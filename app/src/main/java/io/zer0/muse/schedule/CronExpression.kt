package io.zer0.muse.schedule

import java.util.Calendar

/**
 * Analizador de expresiones cron ligero de 5 campos(No depende de bibliotecas externas).
 *
 * 5 Orden de campo:minute(0-59) hour(0-23) day-of-month(1-31) month(1-12) day-of-week(0-6,0=domingo).
 *
 * Sintaxis de soporte:
 *  - Conceptos básicos:cualquier valor(*), valor exacto(N), paso(*\/N), rango(N-M), lista(N,M,...)
 *  - v1.134 P3 Sintaxis avanzada:
 *    - `L`:último día(day-of-month:último día del mes;day-of-week:última semana de esta semanaX,Como por ejemplo `5L`=el viernes pasado)
 *    - `LW`:último día laborable(day-of-month)
 *    - `W`:último día laborable(day-of-month,Como por ejemplo `15W`=El día laborable más cercano al día 15.)
 *    - `#`:enésima semanaX(day-of-week,Como por ejemplo `2#3`=3er lunes)
 *
 * @see <a href="https://www.quartz-scheduler.org/documentation/quartz-2.3.0/tutorials/crontrigger.html">Quartz Cron normativo</a>
 */
class CronExpression internal constructor(
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val daysOfMonth: Set<Int>,
    private val months: Set<Int>,
    private val daysOfWeek: Set<Int>,
    /**
     * v1.134 P3: day-of-month Modificadores avanzados para campos(con [daysOfMonth] mutuamente excluyentes,mayor prioridad).
     *
     * - [DayModifier.Last] → último día del mes(corresponder `L`)
     * - [DayModifier.LastWeekday] → El último día hábil del mes.(corresponder `LW`)
     * - [DayModifier.Weekday](N) → El día laborable más cercano a N(corresponder `NW`,N Ya incluido en [daysOfMonth] en)
     */
    private val dayOfMonthModifier: DayModifier = DayModifier.None,
    /** v1.134 P3: day-of-week Modificadores avanzados para campos. */
    private val dayOfWeekModifier: DayOfWeekModifier = DayOfWeekModifier.None,
) {
    /**
     * Calcular la marca de tiempo dada [timestamp] después(no contiene)La marca de tiempo del próximo partido.(alineación minuto).
     *
     * Usando el método transversal minuto a minuto,Recorrido del límite superior [MAX_SCAN_DAYS] Dios evita un ciclo infinito;No se devolvió ninguna coincidencia [NO_MATCH].
     *
     * Revisión de corrección (2.0 C-06): En su lugar no se utilizó ningún centinela coincidente [Long.MAX_VALUE] - 1 —
     * ScheduledTaskRunner El centinela receptor es exactamente [Long.MAX_VALUE](B-12 CLAIM_NEXT_RUN_SENTINEL),
     * Mezclar los dos valores hará"cron No hay coincidencia"mal juzgado como"ha sido recogido",Las tareas nunca se vuelven a ejecutar y los diagnósticos son confusos.
     */
    fun nextRunAfter(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        // Alinear con el inicio del minuto actual,+1 minuto más,Asegúrese de que el resultado llegue estrictamente después de la marca de tiempo.(no contiene)
        cal.timeInMillis = timestamp
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.MINUTE, 1)

        // límite superior:MAX_SCAN_DAYS días desde la marca de tiempo original
        val maxMs = timestamp + MAX_SCAN_DAYS * 24 * 60 * 60 * 1000

        while (cal.timeInMillis <= maxMs) {
            val minute = cal.get(Calendar.MINUTE)
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
            val month = cal.get(Calendar.MONTH) + 1 // Calendar.MONTH para 0-11,convertir a 1-12
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1 // Calendar domingo=1,convertir a 0=domingo

            // B-13: Quartz normativo — day-of-month Restringido al mismo tiempo que el día de la semana(no *)Utilice O al hacer coincidir
            // (Como por ejemplo "0 0 1 * 1" = 1 de cada mes o cada lunes);Sólo un lado está limitado o ambos lo están * Utilice únicamente Y.
            val domRestricted = dayOfMonthModifier != DayModifier.None || daysOfMonth.size < 31
            val dowRestricted = dayOfWeekModifier != DayOfWeekModifier.None || daysOfWeek.size < 7
            val dayMatch = if (domRestricted && dowRestricted) {
                matchDayOfMonth(cal, dayOfMonth) || matchDayOfWeek(cal, dayOfWeek)
            } else {
                matchDayOfMonth(cal, dayOfMonth) && matchDayOfWeek(cal, dayOfWeek)
            }

            if (minute in minutes &&
                hour in hours &&
                month in months &&
                dayMatch
            ) {
                return cal.timeInMillis
            }
            cal.add(Calendar.MINUTE, 1)
        }
        return NO_MATCH
    }

    /**
     * v1.134 P3: Coincide con el campo del día del mes(considerar L / LW / NW modificador).
     *
     * Cuando el modificador existe, el modificador se usa primero para hacer coincidir.;De lo contrario, se utiliza la coincidencia de conjuntos normal.
     */
    private fun matchDayOfMonth(cal: Calendar, dayOfMonth: Int): Boolean {
        return when (dayOfMonthModifier) {
            is DayModifier.Last -> {
                // L:último día del mes
                val lastDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                dayOfMonth == lastDay
            }
            is DayModifier.LastWeekday -> {
                // LW:El último día hábil del mes.(lunes a viernes)
                val lastDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                if (dayOfMonth == lastDay) {
                    val dow = cal.get(Calendar.DAY_OF_WEEK) - 1
                    dow in 1..5 // lunes a viernes
                } else {
                    // Si el último día es fin de semana,ver si es"el viernes pasado"
                    val dow = cal.get(Calendar.DAY_OF_WEEK) - 1
                    if (dow == 6) {
                        // Sábado:El último día laborable es el viernes.(dayOfMonth - 1)
                        dayOfMonth == lastDay - 1
                    } else if (dow == 0) {
                        // domingo:El último día laborable es el viernes.(dayOfMonth - 2)
                        dayOfMonth == lastDay - 2
                    } else {
                        false
                    }
                }
            }
            is DayModifier.Weekday -> {
                // NW:El día laborable más cercano a N(No durante todo el mes)
                val target = dayOfMonthModifier.day
                if (dayOfMonth == target) {
                    val dow = cal.get(Calendar.DAY_OF_WEEK) - 1
                    dow in 1..5 // El día objetivo en sí es un día laborable.
                } else {
                    val dow = cal.get(Calendar.DAY_OF_WEEK) - 1
                    // El día objetivo es el sábado.(N):El último día hábil fue N-1(viernes)
                    if (dow == 6 && dayOfMonth == target - 1 && target - 1 >= 1) {
                        true
                    } // El día objetivo es el domingo.(N):El último día hábil fue N+1(lunes)
                    else if (dow == 1 && dayOfMonth == target + 1) {
                        true
                    } else {
                        false
                    }
                }
            }
            DayModifier.None -> dayOfMonth in daysOfMonth
        }
    }

    /**
     * v1.134 P3: Coincide con el campo del día de la semana(considerar `5L` / `2#3` modificador).
     *
     * Cuando el modificador existe, el modificador se usa primero para hacer coincidir.;De lo contrario, se utiliza la coincidencia de conjuntos normal.
     */
    private fun matchDayOfWeek(cal: Calendar, dayOfWeek: Int): Boolean {
        return when (dayOfWeekModifier) {
            is DayOfWeekModifier.Last -> {
                // `5L`:el viernes pasado — Compruebe si la fecha actual es la última de este mes. dayOfWeek
                val targetDow = dayOfWeekModifier.dayOfWeek
                if (dayOfWeek != targetDow) return false
                val currentDay = cal.get(Calendar.DAY_OF_MONTH)
                val lastDayOfMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                currentDay + 7 > lastDayOfMonth // Fecha actual + 7 más allá de fin de mes,Indica que este es el último día de la semana del mes.
            }
            is DayOfWeekModifier.Nth -> {
                // `2#3`:3er lunes — Consulta el día actual de la semana.
                if (dayOfWeek != dayOfWeekModifier.dayOfWeek) return false
                val currentDay = cal.get(Calendar.DAY_OF_MONTH)
                val occurrence = (currentDay - 1) / 7 + 1 // cual numero(empezar desde 1)
                occurrence == dayOfWeekModifier.occurrence
            }
            DayOfWeekModifier.None -> dayOfWeek in daysOfWeek
        }
    }

    /** v1.134 P3: day-of-month Modificadores para el campo. */
    sealed class DayModifier {
        /** Sin modificadores. */
        object None : DayModifier()

        /** `L`:El último día del mes. */
        object Last : DayModifier()

        /** `LW`:El último día hábil del mes. */
        object LastWeekday : DayModifier()

        /** `NW`:El día laborable más cercano a N(N Ya incluido en [daysOfMonth] en). */
        data class Weekday(val day: Int) : DayModifier()
    }

    /** v1.134 P3: day-of-week Modificadores para el campo. */
    sealed class DayOfWeekModifier {
        /** Sin modificadores. */
        object None : DayOfWeekModifier()

        /** `5L`:Viernes pasado. */
        data class Last(val dayOfWeek: Int) : DayOfWeekModifier()

        /** `2#3`:Lunes 3. */
        data class Nth(val dayOfWeek: Int, val occurrence: Int) : DayOfWeekModifier()
    }

    companion object {
        /** L-004: El límite superior de días para recorrer minuto a minuto.(Cubre años bisiestos + redundancia,Evite bucles interminables). */
        private const val MAX_SCAN_DAYS = 366L

        /**
         * Revisión de corrección (2.0 C-06): "No hay coincidencias dentro de la ventana de escaneo"centinela — con ScheduledTaskRunner
         * Consigue el centinela(CLAIM_NEXT_RUN_SENTINEL = Long.MAX_VALUE)distinguir,prevenir
         * "cron No hay coincidencia"mal juzgado como"ha sido recogido"(La tarea nunca más se ejecutará).
         */
        const val NO_MATCH = Long.MAX_VALUE - 1

        /**
         * Analizar expresiones cron,lanzamiento ilegal [IllegalArgumentException].
         * Los mensajes de excepción están en inglés.(Analizador puro sin dependencia del contexto),La persona que llama usa context.getString para localizar después de la captura.
         */
        fun parse(expr: String): CronExpression {
            val fields = expr.trim().split(Regex("\\s+"))
            if (fields.size != 5) {
                throw IllegalArgumentException("cron expression must have 5 fields: $expr")
            }
            // v1.0.17: Quartz `?` expresar"No especificado"(Se utiliza para exclusión mutua de día del mes/día de la semana.),
            // Reemplazar uniformemente con `*` Coincide con cualquier valor,Evite errores de análisis posteriores.
            val domField = if (fields[2] == "?") "*" else fields[2]
            val dowField = if (fields[4] == "?") "*" else fields[4]
            // v1.134 P3: Primero analice los modificadores avanzados de día del mes y día de la semana.
            val (domSet, domMod) = parseDayOfMonthField(domField)
            val (dowSet, dowMod) = parseDayOfWeekField(dowField)
            return CronExpression(
                minutes = parseField(fields[0], 0, 59),
                hours = parseField(fields[1], 0, 23),
                daysOfMonth = domSet,
                months = parseField(fields[3], 1, 12),
                daysOfWeek = dowSet,
                dayOfMonthModifier = domMod,
                dayOfWeekModifier = dowMod,
            )
        }

        /**
         * v1.134 P3: Analizar el campo del día del mes,Extracto L / LW / NW modificador.
         *
         * Regresar (colección base, modificador). Prioridad del modificador:LW > L > NW > None.
         * La colección subyacente está vacía cuando el modificador está presente(matchDayOfMonth Ver sólo modificadores).
         */
        private fun parseDayOfMonthField(field: String): Pair<Set<Int>, DayModifier> {
            val trimmed = field.trim().uppercase()
            return when {
                trimmed == "LW" -> emptySet<Int>() to DayModifier.LastWeekday
                trimmed == "L" -> emptySet<Int>() to DayModifier.Last
                trimmed.endsWith("W") -> {
                    val numStr = trimmed.dropLast(1)
                    val n = numStr.toIntOrNull()
                        ?: throw IllegalArgumentException("invalid W modifier: $field")
                    if (n !in 1..31) {
                        throw IllegalArgumentException("W modifier day out of bounds: $n (allowed 1-31)")
                    }
                    setOf(n) to DayModifier.Weekday(n)
                }
                else -> parseField(field, 1, 31) to DayModifier.None
            }
        }

        /**
         * v1.134 P3: Analizar el campo del día de la semana,Extracto `5L` / `2#3` modificador.
         *
         * Regresar (colección base, modificador). Prioridad del modificador:# > L > None.
         */
        private fun parseDayOfWeekField(field: String): Pair<Set<Int>, DayOfWeekModifier> {
            val trimmed = field.trim().uppercase()
            // `5L`:el viernes pasado
            if (trimmed.endsWith("L")) {
                val numStr = trimmed.dropLast(1)
                val n = numStr.toIntOrNull()
                    ?: throw IllegalArgumentException("invalid L modifier: $field")
                if (n !in 0..7) {
                    throw IllegalArgumentException("L modifier dayOfWeek out of bounds: $n (allowed 0-7, 7 also=0=Sunday)")
                }
                // 7 equivalente a 0(domingo)
                val normalized = if (n == 7) 0 else n
                return emptySet<Int>() to DayOfWeekModifier.Last(normalized)
            }
            // `2#3`:3er lunes
            if (trimmed.contains("#")) {
                val parts = trimmed.split("#")
                if (parts.size != 2) {
                    throw IllegalArgumentException("invalid # modifier: $field")
                }
                val dow = parts[0].toIntOrNull()
                    ?: throw IllegalArgumentException("invalid # modifier dayOfWeek: $field")
                val occurrence = parts[1].toIntOrNull()
                    ?: throw IllegalArgumentException("invalid # modifier occurrence: $field")
                if (dow !in 0..7) {
                    throw IllegalArgumentException("# modifier dayOfWeek out of bounds: $dow (allowed 0-7)")
                }
                if (occurrence !in 1..5) {
                    throw IllegalArgumentException("# modifier occurrence out of bounds: $occurrence (allowed 1-5)")
                }
                val normalized = if (dow == 7) 0 else dow
                return emptySet<Int>() to DayOfWeekModifier.Nth(normalized, occurrence)
            }
            return parseField(field, 0, 6) to DayOfWeekModifier.None
        }

        /** Analizar un solo campo(Admite listas de comas,Cada párrafo puede ser *,N,* /N,N-M). */
        private fun parseField(field: String, min: Int, max: Int): Set<Int> {
            if (field.isEmpty()) {
                throw IllegalArgumentException("cron field is empty")
            }
            val result = LinkedHashSet<Int>()
            field.split(",").forEach { segment ->
                result.addAll(parseSegment(segment.trim(), min, max))
            }
            if (result.isEmpty()) {
                throw IllegalArgumentException("cron field has no valid values: $field")
            }
            return result
        }

        /** Analizar un solo segmento:cualquier valor *, paso * /N, valor exacto N, rango N-M. */
        private fun parseSegment(segment: String, min: Int, max: Int): Set<Int> {
            // v1.0.17: Quartz `?` expresar"No especificado",considerado como `*` Coincide con cualquier valor.
            if (segment == "*" || segment == "?") {
                return (min..max).toSet()
            }
            if (segment.startsWith("*/")) {
                val step = segment.substring(2).toIntOrNull()
                    ?: throw IllegalArgumentException("invalid step value: $segment")
                if (step <= 0) {
                    throw IllegalArgumentException("step must be positive: $segment")
                }
                return (min..max step step).toSet()
            }
            if (segment.contains("-")) {
                val parts = segment.split("-")
                if (parts.size != 2) {
                    throw IllegalArgumentException("invalid range: $segment")
                }
                val start = parts[0].toIntOrNull()
                    ?: throw IllegalArgumentException("invalid range start: $segment")
                val end = parts[1].toIntOrNull()
                    ?: throw IllegalArgumentException("invalid range end: $segment")
                if (start < min || end > max || start > end) {
                    throw IllegalArgumentException("range out of bounds: $segment (allowed $min-$max)")
                }
                return (start..end).toSet()
            }
            val n = segment.toIntOrNull()
                ?: throw IllegalArgumentException("invalid numeric value: $segment")
            if (n < min || n > max) {
                throw IllegalArgumentException("value out of bounds: $segment (allowed $min-$max)")
            }
            return setOf(n)
        }
    }
}
