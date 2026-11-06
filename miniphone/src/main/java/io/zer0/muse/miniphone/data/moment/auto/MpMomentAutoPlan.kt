package io.zer0.muse.miniphone.data.moment.auto

import java.time.Instant
import java.time.ZoneId

/**
 * El resultado del juicio de "¿debería servir esta ronda?"
 *
 * solo [Generate] Seguirá bajando,El resto terminará donde están. —— La generación de fondo es la guinda del pastel,
 * Si alguno de los requisitos previos no se cumple, preferiría no emitirse.
 */
sealed interface MpMomentAutoDecision {
    /** Es hora de publicar. */
    data object Generate : MpMomentAutoDecision

    /** No publicar esta vez,ver la razón [reason]. */
    data class Skip(val reason: MpMomentSkipReason) : MpMomentAutoDecision
}

/**
 * Razón para no generar.
 *
 * [value] es el valor escrito en el registro,en forma de `Saltar tiempo en Momentos | reason=too_soon`;
 * Utilice logotipos fijos en inglés en lugar de oraciones en chino,Esto es para que grep y las estadísticas se puedan realizar directamente después.
 */
enum class MpMomentSkipReason(
    val value: String,
) {
    /** El interruptor \"Enviar automáticamente a Momentos\" está desactivado. */
    DISABLED("disabled"),

    /** periodo de tranquilidad([MpMomentAutoPlan.QUIET_FROM_HOUR]–[MpMomentAutoPlan.QUIET_TO_HOUR]),No te molestes. */
    QUIET_HOURS("quiet_hours"),

    /** No es suficiente desde la última generación. [MpMomentAutoPlan.MIN_INTERVAL_MS]:WorkManager El período de,Puede activarse continuamente. */
    TOO_SOON("too_soon"),

    /** Posibilidad de perder:El espacio en blanco es más importante que deslizar la pantalla. */
    PROBABILITY("probability"),

    /** No hay un solo personaje en el lado anfitrión. */
    NO_CHARACTER("no_character"),
}

/**
 * Genere juicios puros dinámicos en segundo plano.
 *
 * Aquí no hay reloj, ni fuente aleatoria, ni host. —— La hora y el número aleatorio los entrega la persona que llama.,
 * Entonces "23 "¿Debería enviarse el botón?" "¿Debería enviarse el botón 30 minutos después de la última vez?" se puede anclar en la prueba JVM pura.
 *
 * La orden de sentencia se fija en:cambiar → periodo de tranquilidad → Anti-vibración → probabilidad → No hay roles.
 * Cuanto más libres de problemas sean las condiciones, mayor será la prioridad.:El período de tranquilidad y el anti-vibración son juicios puramente locales.,Primero, pueden bloquear la mayoría de los despertares.,
 * No se realiza ni una sola llamada de red.
 */
object MpMomentAutoPlan {
    /** Inicio del período de tranquilidad(Contiene):22 punto. */
    const val QUIET_FROM_HOUR: Int = 22

    /** fin del periodo de tranquilidad(no contiene):8 punto. */
    const val QUIET_TO_HOUR: Int = 8

    /** Intervalo mínimo entre dos generaciones.:90 minutos. */
    const val MIN_INTERVAL_MS: Long = 90L * 60L * 1000L

    /** La probabilidad de enviarlo realmente es del 60%: el 40% restante de los despertares no enviarán nada.,Deje la línea de tiempo en blanco. */
    const val PUBLISH_PROBABILITY: Double = 0.6

    /** Obtener la hora actual(La zona horaria proporcionada por la persona que llama.,\"¿Qué hora es ahora\" según lo ve el usuario?). */
    fun hourOf(nowMillis: Long, zone: ZoneId): Int = Instant.ofEpochMilli(nowMillis).atZone(zone).hour

    /** Si cae en el período de tranquilidad.:[QUIET_FROM_HOUR] Desde, hasta el día siguiente [QUIET_TO_HOUR] Detener,Cruza el punto cero. */
    fun isQuietHour(hourOfDay: Int): Boolean = hourOfDay >= QUIET_FROM_HOUR || hourOfDay < QUIET_TO_HOUR

    /**
     * Esta ronda no va a funcionar.
     *
     * @param lastGeneratedAtMillis El momento de la última construcción exitosa.;`null`/`0` significa que nunca se ha generado
     * @param roll Los dados de probabilidad de esta vez,valor `[0, 1)`;`>=` [PUBLISH_PROBABILITY] solo salta
     * @param hasCharacter Si el anfitrión tiene actualmente roles disponibles
     */
    fun decide(
        nowMillis: Long,
        lastGeneratedAtMillis: Long?,
        roll: Double,
        hasCharacter: Boolean,
        enabled: Boolean = true,
        zone: ZoneId = ZoneId.systemDefault(),
    ): MpMomentAutoDecision = when {
        !enabled -> MpMomentAutoDecision.Skip(MpMomentSkipReason.DISABLED)
        isQuietHour(hourOf(nowMillis, zone)) -> MpMomentAutoDecision.Skip(MpMomentSkipReason.QUIET_HOURS)
        isTooSoon(nowMillis, lastGeneratedAtMillis) -> MpMomentAutoDecision.Skip(MpMomentSkipReason.TOO_SOON)
        roll >= PUBLISH_PROBABILITY -> MpMomentAutoDecision.Skip(MpMomentSkipReason.PROBABILITY)
        !hasCharacter -> MpMomentAutoDecision.Skip(MpMomentSkipReason.NO_CHARACTER)
        else -> MpMomentAutoDecision.Generate
    }

    /** Dar un mensaje de rango de \"Escribe algo en este artículo\" por horas,Dáselo al generador del anfitrión como material. */
    fun hintFor(hourOfDay: Int): String = when (hourOfDay) {
        in 8..10 -> "Me desperté por la mañana y anoté algunas palabras sobre mi estado actual."
        in 11..13 -> "Estoy libre al mediodía, hablemos de lo que estoy haciendo."
        in 14..17 -> "En el tiempo fragmentado de la tarde, recuerda una frase."
        in 18..21 -> "Llámalo una noche y hablemos de hoy casualmente."
        else -> "Todavía no he dormido por la noche, así que suspiré casualmente."
    }

    /** nunca generado(`null` / `0`)No \"demasiado rápido\",De lo contrario, nunca se enviará el primer despertador. */
    private fun isTooSoon(nowMillis: Long, lastGeneratedAtMillis: Long?): Boolean {
        if (lastGeneratedAtMillis == null || lastGeneratedAtMillis <= 0L) return false
        return nowMillis - lastGeneratedAtMillis < MIN_INTERVAL_MS
    }
}
