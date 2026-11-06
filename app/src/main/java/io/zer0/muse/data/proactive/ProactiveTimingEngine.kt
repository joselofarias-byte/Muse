package io.zer0.muse.data.proactive

/**
 * Motor de sincronización para mensajes proactivos.
 *
 * Este objeto es la única parte de "juicio" de todo el mecanismo, y el resto es transporte.
 * Entonces está escrito como**función pura**: No lea la configuración, no toque la base de datos, no mire el reloj del sistema,
 * Todas las entradas entran a través de parámetros y todas las conclusiones salen a través de valores de retorno.
 *
 * La decisión sólo responde a una pregunta:**¿Puedes decirme ahora?**. No respondas “¿debería decirlo o no?”
 *
 * "Puede" es interpretable: no molestar y punto, generar, solo charlar, calmarse, cuota.
 * "Debería o no" solo se puede calificar, y si la puntuación está por debajo del umbral, se generará una oración para explicar por qué no decirlo.
 * Esa explicación no significa nada para los usuarios. —— No sabía lo que estabas pensando, simplemente sentía como si una máquina lo pesara.
 *
 * Orden de sentencia:
 *
 *  Q1 [Verdict.QUIET_HOUR] / [Verdict.USER_ACTIVE] / [Verdict.IN_CONVERSATION] / [Verdict.COOLDOWN]
 *      ¿Es ahora un buen momento para interrumpir?—— Puramente local, costo cero.
 *  Q2 [Verdict.NO_BUDGET]
 *      ¿Aún está disponible el cupo?—— No significa no, vuelve mañana.
 *
 * Todos los juicios a nivel de contenido se dejan para el paso de generación: proporcione al modelo la conversación más reciente y déjelo decidir si hay un huatuan o no.
 * Si el modelo no puede decirlo, simplemente diga omitir, que es mucho más confiable que adivinar con un montón de pesos.
 *
 * El juicio se escribe como una cadena. `?:` con un `when` expresión (salida única),
 * No es una cuestión de estilo: el orden de las cuatro preguntas es el núcleo semántico de este mecanismo.
 * Después de estar disperso en múltiples retornos, es fácil cambiar la posición del juicio y perder de vista las dependencias posteriores de él.
 */
object ProactiveTimingEngine {

    /** Si el usuario ha hablado en los últimos 10 minutos no se considera \"activo\", está interrumpiendo la conversación. */
    const val USER_ACTIVE_WINDOW_MS = 10 * 60 * 1000L

    /**
     * El intervalo predeterminado en minutos entre dos mensajes no solicitados.
     *
     * con [io.zer0.muse.data.ProactiveMessageConfig.intervalMinutes] Los valores predeterminados son los mismos:
     * En la configuración anterior, este campo siempre ha sido 240 y la interfaz siempre se ha escrito así. La sentencia debe basarse en el mismo número.
     */
    const val DEFAULT_INTERVAL_MINUTES = 240

    /** Límite inferior del intervalo (minutos). Un poco más corto y no estará \"activo\", sino simplemente deslizando la pantalla. */
    const val MIN_INTERVAL_MINUTES = 30

    /** Límite superior del intervalo (minutos,24 horas). Un poco más y se convierte en \"cuántas veces al año hablamos\". */
    const val MAX_INTERVAL_MINUTES = 1440

    private const val MINUTE_MS = 60_000L

    const val DAILY_GUARANTEE_MS = 22L * 60 * 60 * 1000L

    data class Input(
        val now: Long,
        val relation: ProactiveRelationEntity,
        val allowedHourStart: Int,
        val allowedHourEnd: Int,
        val allDayAllowed: Boolean,
        /** La marca de tiempo de la última vez que habló el usuario,0 Indica desconocido. */
        val lastUserMessageAt: Long,
        /** ¿Hay alguna generación de respuesta en progreso? */
        val isGenerating: Boolean,
        /** La hora en que se envió el último mensaje activo,0 Dijo que nunca sucedió. */
        val lastProactiveSentAt: Long,
        /**
         * El momento en que la última ronda de inspección entró en la generación,0 Indica que el proceso aún no ha pasado.
         *
         * con [lastProactiveSentAt] Son dos cosas que no se pueden sustituir entre sí: la cuenta de resultados no se puede escribir hasta que la noticia esté efectivamente en la base de datos;
         * El paso de generación dura hasta decenas de segundos. —— Durante este período de tiempo, no había nada en la cuenta.
         * Si sólo miramos los resultados,**La ronda que se genera no existe en la capa de datos.**,
         * Por lo tanto, se considerará que otra ronda de inspecciones "ha pasado bastante tiempo desde la última" y se dará a conocer.
         * Regístrese en este momento antes de ajustar el modelo, solo para llenar la ventana vacía.
         */
        val lastProactiveAttemptAt: Long = 0L,
        /**
         * Parámetros de ritmo: intervalo, jitter del día e ID del asistente que participa en el jitter.
         *
         * Estos tres son un grupo: para cambiar el intervalo, debes conocer el límite superior de jitter y "quién lo envía".
         * Dividirlo en tres campos en mosaico haría [Input] Los parámetros de construcción exceden el rango legible,
         * También hace que la persona que llama piense que no tienen nada que ver entre sí.
         */
        val pacing: Pacing = Pacing(),

        val dailyGuaranteeMs: Long = DAILY_GUARANTEE_MS,
    )

    /**
     * Un conjunto de parámetros de ritmo.
     *
     * El intervalo base proviene de la configuración del usuario (nivel "Actividad" o control deslizante personalizado),
     * El límite superior de fluctuación también proviene de la configuración, y el intervalo después de que los dos se superponen es la puerta dura real (consulte [effectiveIntervalMinutes]).
     */
    data class Pacing(
        /** Intervalo base (minutos). atrapado durante el juicio [MIN_INTERVAL_MINUTES]..[MAX_INTERVAL_MINUTES]. */
        val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
        /** Desplazamiento máximo del día (en minutos).0 Indica que no hay temblores. */
        val randomOffsetMinutes: Int = 0,
        /** La identificación del asistente participa en el hash \"fecha + asistente\", de modo que la inquietud de diferentes asistentes cae en diferentes minutos. */
        val assistantId: String = "",
    )

    /** Determina la conclusión. */
    enum class Verdict {
        /** Si se dictan todas las sentencias, se puede emitir. */
        OK,

        /** Durante el periodo de no molestar. */
        QUIET_HOUR,

        /** El usuario está activo (acaba de hablar) y \"activo\" en este momento significa interrumpir. */
        USER_ACTIVE,

        /** Se está generando la respuesta, espere hasta que finalice. */
        IN_CONVERSATION,

        /** El usuario dijo \"No me molestes últimamente\". */
        COOLDOWN,

        /** Demasiado cerca del último mensaje activo. */
        TOO_SOON,

        /** La cuota se ha agotado hoy. */
        NO_BUDGET,
    }

    /** Resultado del juicio. Traiga suficientes detalles para escribir un registro que facilite la resolución de problemas \"por qué no se publicó\". */
    data class Decision(
        val verdict: Verdict,
        val remainingBudget: Float,
        val hourOfDay: Int,
        /** Esta vez, determine el intervalo real utilizado (incluido el desplazamiento del día). Mírelo primero cuando solucione el problema \"por qué aún no ha llegado al punto\". */
        val effectiveIntervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,

        val dailyGuarantee: Boolean = false,
    ) {
        val shouldSend: Boolean get() = verdict == Verdict.OK
    }

    /**
     * Después de responder cuatro preguntas, regrese a la conclusión.
     *
     * El orden no se puede revertir. Por ejemplo, la verificación del presupuesto debe realizarse después de la idoneidad. ——
     * De lo contrario, cuando el presupuesto sea 0, el registro escribirá "No tengo nada que decir", pero la verdad es "Tengo algo que decir pero la cuota se ha agotado hoy".
     * Las direcciones de la investigación en estos dos casos son completamente opuestas.
     */
    fun evaluate(input: Input): Decision {
        val hour = hourOfDay(input.now)
        val remaining = remainingBudget(input.relation)
        val intervalMinutes = effectiveIntervalMinutes(input)

        val quiet = !input.allDayAllowed &&
            isInQuietHours(hour, input.allowedHourStart, input.allowedHourEnd)

        val timingBlock = checkTiming(input, hour, intervalMinutes * MINUTE_MS)

        // La garantía solo omite el intervalo; nunca la cuota ni un intento reciente.
        val intervalMs = intervalMinutes * MINUTE_MS
        val attemptedRecently = tooSoon(input.now, input.lastProactiveAttemptAt, intervalMs)
        val usedGuarantee = input.dailyGuaranteeMs > 0L && !quiet && !attemptedRecently &&
            remaining >= 1f &&
            timingBlock == Verdict.TOO_SOON &&
            (
                input.lastProactiveSentAt <= 0L ||
                    input.now - input.lastProactiveSentAt >= input.dailyGuaranteeMs
                )

        val verdict = when {
            usedGuarantee -> Verdict.OK
            else -> timingBlock ?: if (remaining < 1f) Verdict.NO_BUDGET else Verdict.OK
        }

        return Decision(
            verdict = verdict,
            remainingBudget = remaining,
            hourOfDay = hour,
            effectiveIntervalMinutes = intervalMinutes,
            dailyGuarantee = usedGuarantee,
        )
    }

    /** Q1 de portón duro. Devolver no vacío significa que se detuvo. */
    private fun checkTiming(input: Input, hour: Int, intervalMs: Long): Verdict? = when {
        !input.allDayAllowed &&
            isInQuietHours(hour, input.allowedHourStart, input.allowedHourEnd) -> Verdict.QUIET_HOUR

        input.relation.cooldownUntil > input.now -> Verdict.COOLDOWN

        // Las protecciones de conversación deben preceder al intervalo que la garantía puede omitir.
        input.isGenerating -> Verdict.IN_CONVERSATION

        input.lastUserMessageAt > 0 &&
            input.now - input.lastUserMessageAt < USER_ACTIVE_WINDOW_MS -> Verdict.USER_ACTIVE

        tooSoon(input.now, input.lastProactiveSentAt, intervalMs) -> Verdict.TOO_SOON

        // La última ronda no comenzó lo suficientemente larga, por lo que también se consideró "demasiado reñida".
        // Este elemento es la barrera de seguridad en la ventana de generación: la cuenta de resultados aún está vacía, pero el intento se ha registrado.
        tooSoon(input.now, input.lastProactiveAttemptAt, intervalMs) -> Verdict.TOO_SOON

        else -> null
    }

    /** distancia [timestamp] insuficiente [intervalMs] Incluso si está demasiado cerca;0 Quiere decir que nunca ha sucedido y no participa en el juicio. */
    private fun tooSoon(now: Long, timestamp: Long, intervalMs: Long): Boolean = timestamp > 0 && now - timestamp < intervalMs

    /**
     * El intervalo realmente utilizado en esta determinación: el intervalo básico configurado por el usuario + el desplazamiento del día.
     *
     * El intervalo básico está sujeto a [MIN_INTERVAL_MINUTES]..[MAX_INTERVAL_MINUTES]:
     * El rango legal del control deslizante de la página de configuración es más amplio y la configuración anterior puede almacenarse durante 15 minutos o varios días.
     * Usar estos valores directamente para juzgar hará que el asistente se convierta en un despertador o en silencio.
     */
    fun effectiveIntervalMinutes(input: Input): Int {
        val base = input.pacing.intervalMinutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
        val offset = dailyOffsetMinutes(
            dayKey(input.now),
            input.pacing.assistantId,
            input.pacing.randomOffsetMinutes,
        )
        return base + offset
    }

    /**
     * Compensación de fluctuación del día (en minutos).
     *
     * La misma "fecha + ID de asistente" obtendrá el mismo desplazamiento, que cambiará a lo largo de los días. —— No es necesario aleatorizar cada vez,
     * Esto se debe a que cada aleatorización equivale a convertir el intervalo en un intervalo, que es ajustado por el usuario.4 La "hora" de repente llegará temprano y tarde;
     * El desplazamiento fijo por día no sólo conserva la sensación natural de "no decir la hora", sino que también hace que el ritmo dentro del mismo día sea predecible.
     * El hash se basa únicamente en la cadena misma y no depende de ningún estado que no sea fuentes aleatorias y zonas horarias.
     */
    fun dailyOffsetMinutes(dateKey: String, assistantId: String, maxOffsetMinutes: Int): Int {
        val max = maxOffsetMinutes.coerceIn(0, MAX_INTERVAL_MINUTES)
        if (max == 0) return 0
        return "$dateKey|$assistantId".hashCode().mod(max + 1)
    }

    /** La fecha de la marca de tiempo (zona horaria local,`yyyy-MM-dd`). Depende de él para cambiar el desplazamiento en el cielo. */
    fun dayKey(timestamp: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        val month = cal.get(java.util.Calendar.MONTH) + 1
        val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
        return buildString {
            append(cal.get(java.util.Calendar.YEAR))
            append('-')
            if (month < 10) append('0')
            append(month)
            append('-')
            if (day < 10) append('0')
            append(day)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // presupuesto
    // ══════════════════════════════════════════════════════════════

    /**
     * Saldo restante actual.
     *
     * ```
     * Cuota diaria = Límite de nivel (sin límite para el nivel adherente)
     * budget   = max(0, Cuota diaria − Enviado hoy)
     * ```
     *
     * La cantidad está completamente determinada por la marcha seleccionada por el usuario: Quiet 3 / Restraint 8 / Standard 15 / Active 30 / Adherencia ilimitada.
     * En la versión anterior, se utilizaba "valor de aprendizaje de piso + intimidad" para calcular la cuota. Si el usuario optaba por ser pegajoso, sólo podía enviar 5 mensajes por día.
     * Es inconsistente con la promesa de la interfaz y ha sido abandonado. La intimidad todavía participa en otras determinaciones temporales, pero ya no determina el número de elementos.
     */
    fun remainingBudget(relation: ProactiveRelationEntity): Float {
        val allowance = dailyAllowance(relation)
        return (allowance - relation.spentToday).coerceIn(0f, allowance)
    }

    /** El cupo total del día (no hay límite para los que se ciñen a personas). */
    fun dailyAllowance(relation: ProactiveRelationEntity): Float = relation.intensityEnum().let { if (it.unlimited) Float.MAX_VALUE else it.cap.toFloat() }

    /**
     * Restablecimiento del juicio entre días.
     *
     * true Indica que la persona que llama debe poner el recuento del presupuesto en cero antes de continuar.
     * Se coloca aquí en lugar de en DAO porque "cross-day" es semántica comercial, no semántica de almacenamiento.
     */
    fun needsBudgetReset(relation: ProactiveRelationEntity, todayDate: String): Boolean = relation.budgetDate != todayDate

    // ══════════════════════════════════════════════════════════════
    // Herramientas
    // ══════════════════════════════════════════════════════════════

    /** Si la preocupación ha expirado.EVERGREEN Nunca caduca. */
    fun isExpired(thought: PendingThoughtEntity, now: Long): Boolean {
        val expiresAt = thought.expiresAt ?: return false
        return now > expiresAt
    }

    /** La hora correspondiente a la marca de tiempo (0-23). */
    fun hourOfDay(timestamp: Long): Int {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(java.util.Calendar.HOUR_OF_DAY)
    }

    /**
     * Ya sea durante el periodo de no molestar.
     *
     * Reutilizar [QuietHoursPolicy] En lugar de escribir otro usted mismo: Semántica de límites de No molestar
     * (Cerrando por la izquierda y abriéndose por la derecha, cruzando la noche) Ya hay una realización única, y tarde o temprano surgirá una nueva,
     * La realización de esta deriva es "enviar ocasionalmente un mensaje más durante ciertos períodos", lo cual es extremadamente difícil de reproducir.
     */
    fun isInQuietHours(hour: Int, start: Int, end: Int, allDayAllowed: Boolean = false): Boolean = !QuietHoursPolicy.isWithinAllowedWindow(hour, start, end, allDayAllowed)

    /** Traduzca la conclusión del juicio en una oración que pueda publicarse en la página de configuración. */
    fun describe(decision: Decision): String = when (decision.verdict) {
        Verdict.QUIET_HOUR -> "Ahora es el período de no molestar (${decision.hourOfDay}puntos)"
        Verdict.USER_ACTIVE -> "Acabamos de charlar, esperando que te quedes callado un rato."
        Verdict.IN_CONVERSATION -> "Respondiendo, no interrumpas"
        Verdict.COOLDOWN -> "Me pediste que no te molestara recientemente"
        Verdict.TOO_SOON -> "Acabamos de hablar, hablemos en un rato."
        Verdict.NO_BUDGET -> "Basta de hablar por hoy, hablemos de ello mañana."
        Verdict.OK -> "puede enviar"
    }
}
