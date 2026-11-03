package io.zer0.muse.data.proactive

import io.zer0.memory.fact.FactStore
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Derive “cosas memorables” a partir de recuerdos e hitos.
 *
 * Este paso es**reglas puramente locales**, no llama a ningún modelo, por lo que se puede ejecutar de forma gratuita con cada programación.
 * Ésta es la clave para que todo el diseño pueda tener combustible pero no quemar dinero: la contabilidad no genera costos simbólicos adicionales.
 *
 * Las reglas son deliberadamente conservadoras. Llenar el libro mayor con basura destruirá todo el mecanismo. ——
 * Una vez que los usuarios descubren que "no creo que lo que recuerda sea importante", todos los mensajes proactivos parecerán baratos.
 * Así que prefiero omitirlo: aquí sólo se reconocen tres tipos de señales (eventos futuros, condición física, planes incumplidos),
 * Y los asuntos triviales del chat diario se reflejan en el diálogo. `note_thought` La herramienta decide si recordarlo o no.
 */
object ThoughtDeriver {

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L

    /** ¿Con cuánta anticipación empiezas a pensar en eventos futuros? Escribirlo demasiado pronto puede convertirse en ruido a largo plazo. */
    private const val EVENT_LOOKAHEAD_DAYS = 3L

    /** Una vez finalizado el evento, deje 26 horas adicionales (incluido el día), después de las cuales este artículo dejará de ser completamente válido. */
    private const val EVENT_TAIL_HOURS = 26L

    /** Los recuerdos que han sido tocados solo participan en la derivación durante un tiempo determinado y los recuerdos antiguos no se recuerdan repetidamente. */
    private const val FACT_FRESHNESS_DAYS = 7L

    /** Palabras clave del estado físico. Los golpes se tratan como CUIDADO. */
    private val CARE_KEYWORDS = listOf(
        "insomnio", "no puedo dormir", "quedarse despierto hasta tarde", "frio", "fiebre", "enfermo", "dolor de cabeza", "dolor de cabeza",
        "ansiedad", "presión", "triste", "abajo", "emo", "cansado", "tan cansado", "cansado", "enamorado", "pelea",
    )

    /** Palabras clave de planificación inacabadas. Un hit se trata como FOLLOW_UP. */
    private val INTENT_KEYWORDS = listOf(
        "quiero ver", "planear", "listo para ir", "planear", "quiero ir", "quiero probar", "quiero aprender",
        "considerar", "reflexionar", "Mira de nuevo", "esperame",
    )

    /**
     * Derivando preocupaciones de un lote de recuerdos.
     *
     * @param alreadyTracked Ya existe un ID de fuente asociado, omita los que han sido desmerecidos (DAO La capa también viajará una vez)
     */
    fun fromFacts(
        facts: List<FactStore.Fact>,
        assistantId: String,
        now: Long,
        alreadyTracked: Set<String> = emptySet(),
    ): List<PendingThoughtEntity> = facts.mapNotNull { fact ->
        deriveOne(fact, assistantId, now, factRef(fact), alreadyTracked)
    }

    /** memoria única → Como mucho una preocupación. Las tres reglas se prueban en orden de prioridad. */
    private fun deriveOne(
        fact: FactStore.Fact,
        assistantId: String,
        now: Long,
        ref: String,
        alreadyTracked: Set<String>,
    ): PendingThoughtEntity? {
        val text = fact.fact.trim()
        // Demasiado viejo para recordarlo: un problema menor que mencioné la semana pasada, pero es extraño volver a preguntar sobre ello esta semana.
        val age = parseMillis(fact.createdAt)?.let { now - it } ?: 0L
        val eligible = text.isNotBlank() && ref !in alreadyTracked && age <= FACT_FRESHNESS_DAYS * DAY_MS
        val base = Base(assistantId, ref, text, now, ThoughtSource.MEMORY)
        return if (eligible) {
            asEvent(base, fact) ?: asCare(base) ?: asIntent(base)
        } else {
            null
        }
    }

    /** ① futuro 1~3 cosas en el dia → Recordatorio inmediato: dejará de ser válido al vencimiento y nunca se volverá a emitir. */
    private fun asEvent(base: Base, fact: FactStore.Fact): PendingThoughtEntity? {
        val date = parseDate(fact.time)
        val days = date?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }
        val isUpcoming = date != null && days != null && days in 1..EVENT_LOOKAHEAD_DAYS
        return if (isUpcoming) {
            draft(
                base = base,
                kind = ThoughtKind.REMINDER,
                urgency = ThoughtUrgency.IMMEDIATE,
                expiresAt = date.atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli() + EVENT_TAIL_HOURS * HOUR_MS,
                weight = 0.85f,
            )
        } else {
            null
        }
    }

    /** ② cuerpo/estado emocional → La clase de ventana se preocupa,48 Es apropiado decir dentro de una hora. */
    private fun asCare(base: Base): PendingThoughtEntity? {
        if (CARE_KEYWORDS.none { base.seed.contains(it) }) return null
        return draft(
            base = base,
            kind = ThoughtKind.CARE,
            urgency = ThoughtUrgency.WINDOW,
            expiresAt = base.now + 2 * DAY_MS,
            weight = 0.7f,
        )
    }

    /** ③ No hay planes de ir → Con una amplia ventana de seguimiento, puedes preguntar \"¿Fuiste allí más tarde?\" dentro de una semana. */
    private fun asIntent(base: Base): PendingThoughtEntity? {
        if (INTENT_KEYWORDS.none { base.seed.contains(it) }) return null
        return draft(
            base = base,
            kind = ThoughtKind.FOLLOW_UP,
            urgency = ThoughtUrgency.WINDOW,
            expiresAt = base.now + 7 * DAY_MS,
            weight = 0.6f,
        )
    }

    /**
     * Derivación de hitos.
     *
     * Sólo se toman las que dentro de los últimos 7 días no han sido cerradas por el usuario, y hay un máximo de una.
     * Los hitos que se realizan una vez por semana ya son el límite superior. Un poco más y se convierte en una transmisión de logros, que no es como hablar con un ser humano.
     */
    fun fromMilestones(
        milestones: List<MilestoneRecord>,
        assistantId: String,
        now: Long,
        alreadyTracked: Set<String> = emptySet(),
    ): List<PendingThoughtEntity> = milestones
        .asSequence()
        .filter { it.dismissedAt == null }
        .filter { now - it.createdAt <= 7 * DAY_MS }
        .filter { "ms_${it.id}" !in alreadyTracked }
        .take(1)
        .map { m ->
            draft(
                base = Base(assistantId, "ms_${m.id}", "${m.title}:${m.message}", now, ThoughtSource.MILESTONE),
                kind = ThoughtKind.MILESTONE,
                urgency = ThoughtUrgency.EVERGREEN,
                expiresAt = null,
                weight = 0.45f,
            )
        }
        .toList()

    /** Proyección mínima de hitos para evitar que este documento dependa de entidades de Sala. */
    data class MilestoneRecord(
        val id: Long,
        val title: String,
        val message: String,
        val createdAt: Long,
        val dismissedAt: Long?,
    )

    // ══════════════════════════════════════════════════════════════
    // Herramientas
    // ══════════════════════════════════════════════════════════════

    /** Los campos que son comunes a todas las reglas impiden que se vuelva a pasar cada borrador. */
    private data class Base(
        val assistantId: String,
        val ref: String,
        val seed: String,
        val now: Long,
        val source: ThoughtSource,
    )

    private fun draft(
        base: Base,
        kind: ThoughtKind,
        urgency: ThoughtUrgency,
        expiresAt: Long?,
        weight: Float,
    ) = PendingThoughtEntity(
        id = "th_${base.now}_${base.ref.hashCode().toUInt().toString(16)}",
        assistantId = base.assistantId,
        kind = kind.name,
        seed = base.seed.take(200),
        source = base.source.name,
        sourceRef = base.ref,
        createdAt = base.now,
        expiresAt = expiresAt,
        urgency = urgency.name,
        weight = weight.coerceIn(0f, 1f),
    )

    /** Identificación estable de memorias para deduplicación y recuperación. */
    private fun factRef(fact: FactStore.Fact): String = "fact_${fact.id}"

    private fun parseMillis(iso: String?): Long? =
        iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun parseDate(raw: String?): LocalDate? {
        val s = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        // La hora en la memoria puede ser una fecha pura o una marca de tiempo ISO completa.
        return runCatching { LocalDate.parse(s.substringBefore("T")) }.getOrNull()
    }
}
