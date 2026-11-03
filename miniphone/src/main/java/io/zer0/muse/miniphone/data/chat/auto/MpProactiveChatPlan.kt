package io.zer0.muse.miniphone.data.chat.auto

import io.zer0.muse.miniphone.data.moment.auto.MpMomentAutoPlan
import java.time.ZoneId

/**
 * El resultado del juicio de "¿Debería tomar la iniciativa de enviar mensajes privados en esta ronda?"
 *
 * solo [Send] Seguirá bajando:Los mensajes privados proactivos son la guinda del pastel,Si alguno de los requisitos previos no se cumple, preferiría no emitirse.
 */
sealed interface MpProactiveChatDecision {
    /** Es hora de publicar. */
    data object Send : MpProactiveChatDecision

    /** No publicar esta vez,ver la razón [reason]. */
    data class Skip(val reason: MpProactiveChatSkipReason) : MpProactiveChatDecision
}

/**
 * El motivo de no enviar mensajes privados de forma proactiva.
 *
 * [value] es el valor escrito en el registro,en forma de `Saltar mensajes privados activos | reason=too_soon`;
 * Utilice logotipos fijos en inglés en lugar de oraciones en chino,Esto es para que grep y las estadísticas se puedan realizar directamente después.
 */
enum class MpProactiveChatSkipReason(
    val value: String,
) {
    /** El interruptor \"Carácter de mensaje privado activo\" está desactivado. */
    DISABLED("disabled"),

    /** periodo de tranquilidad(22–08),No te molestes. */
    QUIET_HOUR("quiet_hour"),

    /** El usuario todavía estaba hablando hace un momento.(10 en minutos):Interrumpir una frase en este punto interrumpirá la conversación. */
    USER_JUST_ACTIVE("user_just_active"),

    /** No hay un solo personaje en el lado anfitrión. */
    NO_CHARACTER("no_character"),

    /** No hay conversaciones para empezar:Ni un solo mensaje,Los personajes que hablan pueden parecer acoso. */
    NO_HISTORY("no_history"),

    /** No hay suficiente tiempo desde el último mensaje privado proactivo [MpProactiveChatPlan.MIN_INTERVAL_MS]. */
    TOO_SOON("too_soon"),

    /** Posibilidad de perder:La mayoría de los despertadores no envían nada. */
    PROBABILITY("probability"),

    /** La sentencia es que será enviado.,Pero esta frase no se generó.(Salida en blanco),No queda en el inventario. */
    NO_CONTENT("no_content"),
}

/**
 * Puro criterio para enviar mensajes privados de forma proactiva.
 *
 * Aquí no hay reloj, ni fuente aleatoria, ni host. —— La hora y el número aleatorio los entrega la persona que llama.,
 * Entonces "23 "¿Debería publicarse?" "¿Debería publicarse 3 horas después de la última vez?" se puede lograr en la prueba JVM pura.
 *
 * Se fija el orden de la sentencia.(el orden es prioridad,Volver al golpe anterior,Los parámetros de entrada también están ordenados en este orden.):
 * 1. cambiar
 * 2. periodo de tranquilidad
 * 3. El usuario acaba de volverse activo(10 habló en cuestión de minutos)
 * 4. ¿Hay un papel?
 * 5. ¿Hay alguna conversación que tener?
 * 6. intervalo desde la última vez
 * 7. probabilidad
 * 8. Ni siquiera te detengas → [MpProactiveChatDecision.Send]
 *
 * Cuanto más libres de problemas sean las condiciones, mayor será la prioridad.:Los cambios, los períodos de tiempo, la actividad del usuario y la protección contra vibraciones son juicios puramente locales.,Primero pueden bloquear la mayoría de los despertares.,
 * No se realiza ni una sola llamada de red. La propia sesión de selección tiene un coste.,Por lo tanto, la persona que llama primero selecciona la sesión y establece la "hora del último mensaje del usuario".
 * Entrégalo —— Selección de sesión en [decide] completado antes,En la cadena de decisiones sólo quedan comparaciones puras.
 *
 * Los límites del período de tranquilidad comparten la misma definición que los del círculo de amigos.(22 Haga clic para comenzar,8 detenerse),No lo reescribas aquí,
 * Para evitar que en el futuro las dos fronteras se separen.
 */
object MpProactiveChatPlan {
    /** Intervalo mínimo entre dos mensajes privados activos:4 horas. */
    const val MIN_INTERVAL_MS: Long = 4L * 60L * 60L * 1000L

    /** Tiempo de recuperación después de que el usuario habla:10 minutos. */
    const val USER_ACTIVE_WINDOW_MS: Long = 10L * 60L * 1000L

    /** La probabilidad de enviarlo realmente es del 35%: el 65% restante de los despertares no enviará nada.,No actualice la pantalla. */
    const val SEND_PROBABILITY: Float = 0.35f

    /** Obtener la hora actual(La zona horaria proporcionada por la persona que llama.,\"¿Qué hora es ahora\" según lo ve el usuario?). */
    fun hourOf(nowMillis: Long, zone: ZoneId): Int = MpMomentAutoPlan.hourOf(nowMillis, zone)

    /** Si cae en el período de tranquilidad.:22 De 0:00 a 8:00 del día siguiente,Cruza el punto cero. */
    fun isQuietHour(hourOfDay: Int): Boolean = MpMomentAutoPlan.isQuietHour(hourOfDay)

    /**
     * Esta ronda no va a funcionar.
     *
     * @param lastUserMessageAtMillis La hora del último mensaje de usuario en la conversación seleccionada.;`null`/`0` Indica que el usuario no ha hablado aquí
     * @param lastProactiveAtMillis La hora en la que se envió correctamente el último mensaje privado activo;`null`/`0` Dijo que nunca se había publicado.,No juzgado como "demasiado rápido"
     * @param random dados de probabilidad,valor `[0, 1)`;`>=` [SEND_PROBABILITY] Simplemente sáltatelo. Sólo se llamará cuando los primeros siete elementos no estén bloqueados.
     */
    // La tabla de parámetros corresponde uno a uno con la cadena de juicio.(el orden es prioridad):Cuando se incluye un objeto, no se puede ver el orden de determinación.
    @Suppress("LongParameterList")
    fun decide(
        nowMillis: Long,
        enabled: Boolean,
        lastUserMessageAtMillis: Long?,
        characterCount: Int,
        hasEligibleSession: Boolean,
        lastProactiveAtMillis: Long?,
        random: () -> Float,
        zone: ZoneId = ZoneId.systemDefault(),
    ): MpProactiveChatDecision = when {
        !enabled -> MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.DISABLED)
        isQuietHour(hourOf(nowMillis, zone)) -> MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.QUIET_HOUR)
        isUserJustActive(nowMillis, lastUserMessageAtMillis) ->
            MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.USER_JUST_ACTIVE)

        characterCount <= 0 -> MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.NO_CHARACTER)
        !hasEligibleSession -> MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.NO_HISTORY)
        isTooSoon(nowMillis, lastProactiveAtMillis) -> MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.TOO_SOON)
        random() >= SEND_PROBABILITY -> MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.PROBABILITY)
        else -> MpProactiveChatDecision.Send
    }

    /** El usuario habló en 10 minutos.:Interrumpir en este momento es interrumpir. nunca hablé(`null` / `0`)No se considera activo. */
    private fun isUserJustActive(nowMillis: Long, lastUserMessageAtMillis: Long?): Boolean {
        if (lastUserMessageAtMillis == null || lastUserMessageAtMillis <= 0L) return false
        return nowMillis - lastUserMessageAtMillis < USER_ACTIVE_WINDOW_MS
    }

    /** nunca publicado(`null` / `0`)No \"demasiado rápido\",De lo contrario, nunca se enviará el primer despertador. */
    private fun isTooSoon(nowMillis: Long, lastProactiveAtMillis: Long?): Boolean {
        if (lastProactiveAtMillis == null || lastProactiveAtMillis <= 0L) return false
        return nowMillis - lastProactiveAtMillis < MIN_INTERVAL_MS
    }
}
