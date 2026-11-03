package io.zer0.muse.data.proactive

/**
 * Mapeo y verificación entre períodos permitidos y períodos de no molestar.
 *
 * Los dos conjuntos de expresiones son dos formas de decir lo mismo: el usuario completa "qué horas de interrupción están permitidas",
 * El juicio se basa en "qué horas no molestan". Ambos deben ser estrictamente recíprocos,
 * Y el usuario puede completar una ventana de párrafo completo durante el período de silencio. —— Eso significa que nunca será enviado.
 * Hay que diagnosticarlo mientras se guarda, en lugar de esperar unos días más tarde para descubrir que no se ha enviado ni un solo mensaje.
 */
/**
 * La única asignación entre "Período de no molestar" y el campo de almacenamiento "Permitir ventana de envío".
 *
 * Antecedentes (comentarios de los usuarios "Ni un solo mensaje"):
 * La ventana emergente en la página de configuración es **no molestar** Semántica (título "Período de no molestar", mensaje "No se enviarán mensajes activos durante este período"),
 * Y campos persistentes `allowedHourStart/End` si **permitir** Semántica —— Los dos son complementarios y en direcciones exactamente opuestas.
 * La implementación anterior escribía esta relación complementaria directamente en la interfaz de usuario (`startHour = allowedHourEnd`),
 * Tienes que hacer una negación en tu mente cada vez. Las personas que leen el código pueden fácilmente juzgarlo erróneamente como un error (esta vez).
 * También es muy fácil cambiar una de las direcciones en la dirección incorrecta al realizar cambios (eso realmente conducirá a iniciar y detener el intercambio después de guardar).
 *
 * Ahora centrémonos aquí en las relaciones complementarias,UI Solo lectura, sin derivación: el control deslizante cambia a "No molestar al inicio".
 * Lo que se escribe sigue siendo "No molestar", y la conversión se realiza aquí a la vez.
 */
object QuietHoursWindow {
    /** No molestar punto (0-23 A la hora, izquierda cerrada y derecha abierta. [start, end),start > end significa cruzar la noche). */
    data class QuietHours(val startHour: Int, val endHour: Int) {
        /** La ventana de permiso de envío correspondiente. */
        fun toAllowed(): AllowedWindow = QuietHoursWindow.toAllowed(this)

        /** No molestar empieza y termina igual = Permitir que la ventana esté vacía = Nunca enviado. */
        val isEmptyAllowed: Boolean get() = startHour == endHour

        /** en forma de 22:00 – 08:00. */
        fun label(): String = "%02d:00 – %02d:00".format(startHour, endHour)
    }

    /** Permitir ventana de envío (0-23 A la hora, izquierda cerrada y derecha abierta. [start, end),start > end significa cruzar la noche). */
    data class AllowedWindow(val startHour: Int, val endHour: Int) {
        /** Periodo correspondiente de no molestar. */
        fun toQuietHours(): QuietHours = QuietHoursWindow.fromAllowed(this)

        /** Permitir que la ventana esté vacía (start == end)—— Envío cero estructural. */
        val isEmpty: Boolean get() = startHour == endHour

        /** Permitir todos los puntos en la ventana (0-23), una ventana vacía devuelve un conjunto vacío. */
        fun hours(): Set<Int> = if (isEmpty) {
            emptySet()
        } else if (startHour < endHour) {
            (startHour until endHour).toSet()
        } else {
            ((startHour until 24) + (0 until endHour)).toSet()
        }
    }

    /** permitir ventana → Período de no molestar (límite establecido complementario): El momento en que finaliza el permiso es el inicio del no molestar. */
    fun fromAllowed(allowed: AllowedWindow): QuietHours = QuietHours(
        startHour = allowed.endHour.coerceIn(0, 23),
        endHour = allowed.startHour.coerceIn(0, 23),
    )

    /** No molestar periodo → Permitir ventana (con [fromAllowed] estrictamente recíproco). */
    fun toAllowed(quiet: QuietHours): AllowedWindow = AllowedWindow(
        startHour = quiet.endHour.coerceIn(0, 23),
        endHour = quiet.startHour.coerceIn(0, 23),
    )

    /**
     * Si la ventana de permiso es consistente con el período de silencio codificado del motor de puntuación (22:00-08:00)**completamente coincidente**.
     *
     * Cuando se superponga completamente, cada hora que se libere la puerta de tiempo exterior será bloqueada nuevamente por la puerta de tiempo silenciosa del motor. ——
     * El resultado de multiplicar las dos puertas es“nunca enviar”, y solo se pueden ver las horas de silencio en el registro, no las del usuario.
     * ¿Qué papel juega la configuración de la ventana en esto? Esta combinación debe informarse explícitamente.
     */
    fun fullyShadowedByEngineQuietHours(allowed: AllowedWindow, engineQuietStart: Int, engineQuietEnd: Int): Boolean {
        val allowedHours = allowed.hours()
        if (allowedHours.isEmpty()) return false
        return allowedHours.all { it >= engineQuietStart || it < engineQuietEnd }
    }
}
