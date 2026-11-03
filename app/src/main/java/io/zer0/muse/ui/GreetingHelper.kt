package io.zer0.muse.ui

import android.content.res.Resources
import io.zer0.common.Logger
import io.zer0.memory.fact.FactEntity
import io.zer0.muse.R
import java.time.LocalDate
import java.time.LocalTime

object GreetingHelper {
    /** Presupuesto de caracteres de una sola línea para recordatorios personalizados en el área de saludo de la página de inicio. */
    internal const val PERSONALIZED_HINT_MAX_LENGTH = 18

    /** Presupuesto de caracteres de una sola línea para el resumen diario del área de saludo de la página de inicio. */
    internal const val DAILY_SUMMARY_HINT_MAX_LENGTH = 24

    /**
     * v2.7: Visualización de resumen diario**Suelto**Límite superior de seguridad (objetivo no truncado).
     *
     * La capa de presentación ya no limita el resumen a 24 palabras; la longitud está controlada por el lado de la generación. Esto solo evita la salida anormal del modelo.
     * El artículo extremadamente largo llena la página de inicio y se genera normalmente (≤60 palabras) es mucho menor que este valor.
     */
    internal const val DAILY_SUMMARY_DISPLAY_SAFETY_LIMIT = 200

    /** v2.3.1: entorno sin recursos(Pruebas unitarias)El patrón de fechas a continuación está detallado con todo detalle. */
    private const val DEFAULT_DATE_PATTERN = "MMM d, EEEE"

    /**
     * I18N-01: Priorizar cadenas de recursos,Sin recursos(Pruebas unitarias)Recurrir a las constantes chinas.
     * @param args con marcadores de posición de recursos(%1$s espera)Correspondencia uno a uno.
     */
    private fun zh(res: Resources?, id: Int, fallback: String, vararg args: Any): String = if (res != null) {
        res.getString(id, *args)
    } else if (args.isEmpty()) {
        fallback
    } else {
        String.format(fallback, *args)
    }

    // Devolver el saludo según el tiempo
    fun getTimeGreeting(hour: Int = LocalTime.now().hour, res: Resources? = null): String {
        val id = when (hour) {
            in 5..10 -> R.string.greeting_morning
            in 11..13 -> R.string.greeting_noon
            in 14..17 -> R.string.greeting_afternoon
            in 18..22 -> R.string.greeting_evening
            else -> R.string.greeting_late_night
        }
        return zh(
            res,
            id,
            when (hour) {
                in 5..10 -> "buenos dias"
                in 11..13 -> "buen mediodía"
                in 14..17 -> "buenas tardes"
                in 18..22 -> "buenas noches"
                else -> "es tarde en la noche"
            },
        )
    }

    // Obtener términos solares (v1.0.72: Calcule por año usando la fórmula de la estrella de longevidad,Tabla alternativa de fechas fijas)
    //
    // fondo:La implementación anterior era una tabla de fechas fijas.(Triple(8,8,"Principios de otoño")),Pero los términos solares son eventos astronómicos.,
    // Fechas diferentes cada año(2026 El comienzo del otoño está aquí. 8/7,vieja declaración 8/8 → Comentarios de los usuarios"El comienzo del otoño fue ayer.").
    //
    // fórmula de longevidad: D = floor(Y*0.2422 + C) - floor(Y/4)
    //   Y = Últimos dos dígitos del año,[] = Redondear hacia abajo,C = Constantes de siglo para cada término solar.(21 Adecuado durante siglos,Error < 1 dia;
    //   20 necesidades del siglo C-1)
    fun getSolarTerm(date: LocalDate = LocalDate.now()): String? {
        // 24 Términos solares constante de siglo(orden: 1yue xiaohan/Mucho frío...muchas nevadas en diciembre/solsticio de invierno)
        val cValues = doubleArrayOf(
            5.4055, 20.12, // 1mes
            3.87, 18.73, // 2mes
            5.63, 20.646, // 3mes
            4.81, 20.1, // 4mes
            5.52, 21.04, // 5mes
            5.678, 21.37, // 6mes
            7.108, 22.83, // 7mes
            7.5, 23.13, // 8mes
            7.646, 23.042, // 9mes
            8.318, 23.438, // 10mes
            7.438, 22.36, // 11mes
            7.18, 21.94, // 12mes
        )
        val termNames = listOf(
            "osamu", "gran frio", "Principio de primavera", "lluvia", "Despertar de los insectos", "equinoccio",
            "Qingming", "Guyu", "Principio de verano", "Xiaoman", "miscanto", "solsticio de verano",
            "Xiaoshu", "Gran calor", "Principios de otoño", "Fin del calor del verano", "rocío blanco", "equinoccio de otoño",
            "rocio frio", "escarcha", "Principio del invierno", "Xiaoxue", "fuertes nevadas", "solsticio de invierno",
        )

        /** fórmula de longevidad: Un determinado término solar en un determinado año.(índice 0-23)¿En qué número cae? */
        fun solarTermDay(year: Int, termIndex: Int): Int {
            val y = (year % 100).toDouble()
            val centuryAdj = if (year in 1900..1999) 1.0 else 0.0 // 20 siglo C-1
            return (Math.floor(y * 0.2422 + cValues[termIndex] - centuryAdj) - Math.floor(y / 4.0)).toInt()
        }

        /** El término solar nombre de un día determinado.(Sin reglas null). */
        fun termFor(d: LocalDate): String? {
            for (idx in termNames.indices) {
                if (idx / 2 + 1 != d.monthValue) continue
                if (solarTermDay(d.year, idx) == d.dayOfMonth) return termNames[idx]
            }
            return null
        }

        // Comprobar si hoy o mañana es término solar (avisar con antelación)
        termFor(date)?.let { return "hoy es $it" }
        termFor(date.plusDays(1))?.let { return "mañana es $it" }
        return null
    }

    // obtener festival
    fun getFestival(date: LocalDate = LocalDate.now()): String? {
        val festivals = listOf(
            Triple(1, 1, "día de año nuevo"), Triple(2, 14, "dia de san valentin"),
            Triple(3, 8, "dia de la mujer"), Triple(5, 1, "dia del trabajo"),
            Triple(5, 4, "dia de la juventud"), Triple(6, 1, "dia del niño"),
            Triple(7, 1, "Día de la fundación del partido"), Triple(8, 1, "dia del ejercito"),
            Triple(9, 10, "dia del maestro"), Triple(10, 1, "dia nacional"),
            Triple(12, 25, "navidad"),
        )
        val today = festivals.firstOrNull { it.first == date.monthValue && it.second == date.dayOfMonth }
        if (today != null) return "hoy es ${today.third}"
        val tomorrow = date.plusDays(1)
        val tmr = festivals.firstOrNull { it.first == tomorrow.monthValue && it.second == tomorrow.dayOfMonth }
        if (tmr != null) return "mañana es ${tmr.third}"
        return null
    }

    /**
     * Palabras clave de eventos recientes — Los aciertos en la memoria de estas palabras se tratarán como"eventos recientes"(examen/viajar/reuniones, etcétera).
     * Haga coincidir el campo de tiempo de la memoria después del golpe (mañana/pasado mañana/3 En unos días) genera recordatorios personalizados.
     */
    private val EVENT_KEYWORDS = listOf(
        "examen", "Examen de ingreso a posgrado", "examen de ingreso a la universidad", "Fin del plazo", "defensa", "entrevista",
        "vuelos", "tren", "tren de alta velocidad", "avión", "boletos aéreos", "poner en marcha", "viaje de regreso", "viaje de negocios", "viajar", "viajar",
        "reunión", "reunión", "informe", "Fecha límite", "Regístrate", "Enviar",
        "Examen físico", "Visita de seguimiento", "Revisión", "cirugia", "conseguir medicina",
        "reembolso", "pagar el alquiler", "pagar", "deadline", "DDL",
        "cumpleaños", "aniversario", "citas", "fiesta", "boda",
    )

    /**
     * Seleccionar candidatos para eventos recientes(Visitas de palabras clave en los próximos 1 a 3 días),Devuelve la lista de texto de materia original.
     * Para que LLM lo use como entrada al generar recordatorios personalizados;Si no hay candidatos, se devuelve una lista vacía.
     */
    fun recentEvents(facts: List<FactEntity>, today: LocalDate = LocalDate.now()): List<String> {
        if (facts.isEmpty()) return emptyList()
        val events = mutableListOf<String>()
        for (fact in facts) {
            val text = fact.fact ?: continue
            val time = fact.time ?: continue
            if (!EVENT_KEYWORDS.any { text.contains(it) }) continue
            val eventDate = runCatching { LocalDate.parse(time.substringBefore("T")) }
                .onFailure { Logger.w("GreetingHelper", "Ignorar campos de tiempo de memoria no resueltos", it) }
                .getOrNull() ?: continue
            val diff = java.time.temporal.ChronoUnit.DAYS.between(today, eventDate)
            if (diff !in 1..3) continue
            val whenText = when (diff) {
                1L -> "mañana"
                2L -> "pasado mañana"
                else -> "${diff}reina del cielo"
            }
            events += "$whenText$text"
        }
        return events
    }

    /**
     * Recupera recordatorios personalizados (cumpleaños, eventos recientes) de la memoria.
     *
     * Prioridad: mañana > pasado mañana > 3 en unos días. Se toma el primero con la misma prioridad.
     * Las propinas devueltas han sido truncadas por el presupuesto de una sola línea de la página de inicio (≤ 18 palabras).
     */
    fun getMemoryHint(facts: List<FactEntity>, today: LocalDate = LocalDate.now(), res: Resources? = null): String? {
        if (facts.isEmpty()) return null
        var best: Pair<Int, String>? = null // (diffDays, hint)
        for (fact in facts) {
            val text = fact.fact ?: continue
            // Comprobar fecha de nacimiento (admite"El cumpleaños es en el mes X.Xdia")
            if (text.contains("cumpleaños") || text.contains("nacido")) {
                val dateMatch = Regex("""(\d{1,2})[月/-](\d{1,2})""").find(text)
                if (dateMatch != null) {
                    val month = dateMatch.groupValues[1].toIntOrNull() ?: continue
                    val day = dateMatch.groupValues[2].toIntOrNull() ?: continue
                    // Reparación de auditoría (2.2): cita ilegal(Como por ejemplo"13mes45dia")Lanzar DateTimeException y bloquear;
                    // Esta función debe ejecutarse en la página de guía de sesión vacía.,La memoria colapsará si contiene dicho texto.
                    if (month !in 1..12 || day !in 1..31) continue
                    val birthdayThisYear = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: continue
                    val diff = java.time.temporal.ChronoUnit.DAYS.between(today, birthdayThisYear)
                    // I18N-01: Recursos recordatorios,Prueba de respaldo al chino.
                    val hint = when {
                        diff == 0L -> zh(res, R.string.greeting_today_birthday, "hoy es cumpleaños")
                        diff == 1L -> zh(res, R.string.greeting_tomorrow_birthday, "mañana es cumpleaños")
                        diff in 2..7L -> zh(res, R.string.greeting_days_birthday, "%1\$des el cumpleaños de la reina", diff)
                        else -> null
                    }
                    if (hint != null) best = betterHint(best, diff, hint)
                }
                continue
            }
            // Eventos recientes: visitas a palabras clave + campo de tiempo dentro de los 3 días
            if (EVENT_KEYWORDS.any { text.contains(it) }) {
                val time = fact.time
                if (time == null) continue
                val eventDate = runCatching { LocalDate.parse(time.substringBefore("T")) }
                    .onFailure { Logger.w("GreetingHelper", "Ignorar campos de tiempo de memoria no resueltos", it) }
                    .getOrNull() ?: continue
                val diff = java.time.temporal.ChronoUnit.DAYS.between(today, eventDate)
                if (diff !in 1..3) continue
                val keyword = EVENT_KEYWORDS.firstOrNull { text.contains(it) } ?: "cosa"
                val hint = when (diff) {
                    1L -> zh(res, R.string.greeting_tomorrow_event, "habrá mañana%1\$s:%2\$s", keyword, text.take(16))
                    else -> zh(res, R.string.greeting_days_event, "%1\$dEn unos días%2\$s:%3\$s", diff, keyword, text.take(14))
                }
                best = betterHint(best, diff, hint)
            }
        }
        return best?.second
            ?.replace("Usuario", "tu")
            ?.let { compactGreetingText(it, PERSONALIZED_HINT_MAX_LENGTH) }
    }

    /**
     * Condense el texto del modelo, la caché o la regla en un mensaje de una sola línea que se puede utilizar en el área de saludo de la página de inicio.
     *
     * Las palabras rápidas y los maxTokens son solo restricciones suaves,Es posible que las cachés antiguas o los proveedores de excepciones aún devuelvan texto largo,
     * Por lo tanto, se debe implementar una normalización uniforme y un límite de longitud estricto antes de la visualización.
     */
    internal fun compactGreetingText(text: String?, maxChars: Int): String? {
        if (text.isNullOrBlank() || maxChars <= 0) return null
        // UI-FIX: El mensaje de versión de las reglas vinculará los hechos al texto original."(tiempo:…)"luchar juntos,Retire esta nota final antes de mostrarla
        // No se trata solo de metadatos internos, sino que también es fácil llenar toda la línea, lo que hace que parezca que está truncada.
        // Nota:Para palabras \u escapar,Evite que las puertas chinas codificadas traten las expresiones regulares como una copia visible para el usuario.
        val normalized = text
            .replace(Regex("[((]\\s*\\u65F6\\u95F4\\s*[::][^))]*[))]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val compacted = normalized
            .take(maxChars)
            .trimEnd(' ', ',', ',', '.', '.', ';', ';', ':', ':')
        if (compacted.isBlank()) return null
        // v1.0.92: Termine explícitamente con una elipse cuando la longitud sea demasiado larga. — La antigua implementación corta directamente,Comentarios de los usuarios"Visualización siempre incompleta";
        // mantener la longitud total≤maxChars(take(maxChars-1) + Elipsis).
        return if (normalized.length > maxChars) {
            compacted.take((maxChars - 1).coerceAtLeast(1)) + "…"
        } else {
            compacted
        }
    }

    /**
     * Normalice el resumen diario del último día y muéstrelo en el área de saludo de la página de inicio.
     *
     * v2.7(Comentarios de los usuarios): No más líneas individuales/Compresión dura de caracteres cortos —— La capa de visualización no se trunca y el texto se ajusta de forma natural.
     * Organizar hacia abajo (el contenido a continuación se mueve hacia abajo a su vez). La longitud es generada por el lado (DailySummaryService palabra rápida
     * con maxTokens). Aquí solo hacemos:
     *  - Elimine las notas finales de metadatos internos (p. ej.“(tiempo:…)”) y espacio en blanco adicional
     *  - uno**suelto**Límite superior de seguridad (para evitar que el modelo se salga de control y artículos extremadamente largos), que excede con creces la longitud normal de generación
     *
     * El resumen puede generarse en segundo plano, por lo que solo acepte contenido de hoy o de ayer para evitar que los usuarios no lo abran durante unos días.
     * Todavía veo artículos vencidos después de presentar la solicitud.
     */
    fun getDailySummaryHint(summary: String?, summaryDate: String?, today: LocalDate = LocalDate.now()): String? {
        if (summary.isNullOrBlank() || summaryDate.isNullOrBlank()) return null
        val date = runCatching {
            LocalDate.parse(summaryDate.substringBefore("T"))
        }.getOrNull() ?: return null
        val age = java.time.temporal.ChronoUnit.DAYS.between(date, today)
        if (age !in 0..1) return null
        return normalizeGreetingText(summary, DAILY_SUMMARY_DISPLAY_SAFETY_LIMIT)
    }

    /**
     * v2.7: Sólo normalizar (eliminar notas finales)/en blanco)+ Límite superior de seguridad flexible, sin truncamiento de caracteres cortos.
     *
     * con [compactGreetingText](Para mensajes cortos de una sola línea, corte con elipses) Distinguir:
     * Los resúmenes diarios son textos que se pueden mostrar en varias líneas y no deben acortarse a nivel de presentación.
     * [safetyLimit] Solo evita que el modelo genere de manera anormal artículos extremadamente largos que abrumarán la página de inicio. La longitud normal es mucho menor que ésta.
     */
    internal fun normalizeGreetingText(text: String?, safetyLimit: Int): String? {
        val normalized = text?.takeIf { it.isNotBlank() }?.let { raw ->
            raw
                .replace(Regex("[((]\\s*\\u65F6\\u95F4\\s*[::][^))]*[))]"), " ")
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
        }?.takeIf { it.isNotBlank() } ?: return null
        return if (safetyLimit > 0 && normalized.length > safetyLimit) {
            normalized.take(safetyLimit).trimEnd() + "…"
        } else {
            normalized
        }
    }

    /** Sigue siendo más urgente (diff más pequeños) puntas. */
    private fun betterHint(best: Pair<Int, String>?, diff: Long, hint: String): Pair<Int, String> {
        return if (best == null || diff < best.first) diff.toInt() to hint else best
    }

    /**
     * v2.6.7 fix (AUDIT F8): Solicitar tareas pendientes sin terminar en todas las sesiones (“Aún quedan N sesiones con asuntos pendientes”).
     *
     * `SessionTodoRepository` de `sessionsWithOpenItems` / `countSessionsWithOpenItems`
     * Anteriormente, no hubo ninguna convocatoria para el puesto completo. —— Los datos se guardan pero“Puede recordar proactivamente”El objetivo no fue entregado.
     * El saludo de la página de inicio es el lugar de entrega de menor costo: sin interrupciones, sin ventanas emergentes y visible para los usuarios tan pronto como lo abren.
     *
     * @param openSessionCount Número de sesiones con temas pendientes (0 o un número negativo devuelve nulo)
     */
    fun getOpenTodoHint(openSessionCount: Int, res: Resources? = null): String? {
        if (openSessionCount <= 0) return null
        // Sin recursos (prueba unitaria/Devuelve nulo cuando es muy temprano), no hay literales chinos incorporados:
        // El control de acceso CJK codificado está "sólo hacia abajo pero no hacia arriba". Agregar uno nuevo aquí hará que la deuda aumente.
        return res?.getString(R.string.greeting_open_todos, openSessionCount)
    }

    // Indicaciones de memoria (humanizadas)— I18N-01: Recursos de redacción,Prueba de respaldo al chino.
    fun getMemoryCountText(count: Int, assistantName: String = "Muse", res: Resources? = null): String {
        return when (count) {
            0 -> zh(res, R.string.greeting_memory_0, "$assistantName No estoy familiarizado contigo todavía", assistantName)
            in 1..9 -> zh(res, R.string.greeting_memory_few, "$assistantName Conociéndote poco a poco", assistantName)
            in 10..49 -> zh(res, R.string.greeting_memory_mid, "$assistantName Ya recordado $count Recuerdos, empezando a volverse familiares.", assistantName, count)
            in 50..99 -> zh(res, R.string.greeting_memory_many, "$assistantName Ya estoy muy familiarizado contigo", assistantName)
            in 100..199 -> zh(res, R.string.greeting_memory_max, "$assistantName hablarte de todo", assistantName)
            else -> zh(res, R.string.greeting_memory_huge, "$assistantName Te entiendo mejor que nadie, lo he recordado. $count memoria", assistantName, count)
        }
    }

    /**
     * v2.x: Fila de fecha de inicio(sustituto original"familiaridad"línea de recuento de memoria) — Ejemplo:9mes27día sábado.
     *
     * v2.3.1: Patrón de fecha por recurso de cadena [R.string.home_date_pattern] Disponible por idioma
     * (zh/ja「MmesdJapón EEEE",ko「M월 d일 EEEE”, el resto “MMM d, EEEE"),No más codificación estricta en el código;
     * Sin recursos(Pruebas unitarias)regresar cuando [DEFAULT_DATE_PATTERN]. Volver a la fecha ISO cuando se produce una excepción de formato.
     */
    fun getDateLine(date: LocalDate = LocalDate.now(), res: Resources? = null): String {
        val locale = runCatching { res?.configuration?.locales?.get(0) }.getOrNull()
            ?: java.util.Locale.getDefault()
        val pattern = runCatching { res?.getString(R.string.home_date_pattern) }.getOrNull()
            ?: DEFAULT_DATE_PATTERN
        return runCatching {
            date.format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))
        }.getOrElse { date.toString() }
    }

    /**
     * Arma el saludo completo.
     *
     * Prioridad personalizada: eventos recientes (exámenes) en la memoria/vuelos/reuniones, etc.), utilice únicamente indicaciones de memoria;
     * Cuando no haya tiempo, entonces sigue los términos solares./Elija uno al azar durante las vacaciones y utilícelo como mensaje general (evite que sea el mismo cada vez).
     * Control de longitud: sólo se conserva un sufijo y el conjunto ≤ Aproximadamente 28 palabras.
     */
    fun buildGreeting(
        facts: List<FactEntity>,
        hour: Int = LocalTime.now().hour,
        date: LocalDate = LocalDate.now(),
        dailySummary: String? = null,
        dailySummaryDate: String? = null,
    ): String {
        val prefix = getTimeGreeting(hour)
        // 1. Prioridad del resumen diario,Deje que los elementos generados por la noche vayan seguidos de un saludo cuando abra la página de inicio la próxima vez.
        getDailySummaryHint(dailySummary, dailySummaryDate, date)?.let { hint ->
            return "$prefix,$hint"
        }
        // 2. La memoria indica prioridad (personalizada)
        getMemoryHint(facts, date)?.let { hint ->
            return "$prefix,$hint"
        }
        // 3. Sin consejos personalizados: términos solares/Elija uno al azar durante las vacaciones (la información general se reemplaza al azar)
        val extras = listOfNotNull(getSolarTerm(date), getFestival(date))
        if (extras.isNotEmpty()) {
            return "$prefix,${extras.random()}"
        }
        return prefix
    }
}
