package io.zer0.muse.data.proactive

/**
 * Determinación del "período de no molestar" de los mensajes activos (P1-2).
 *
 * ¿Por qué extraerlo por separado?
 * dialogo principal [io.zer0.muse.schedule.ProactiveMessageRunner] Este conjunto de lógica de ventana ya existe en
 * (allowedHourStart / allowedHourEnd / allDayAllowed, incluidos los límites nocturnos y de 24 horas),
 * Pero es un código privado integrado en el proceso de inspección. Los mensajes privados proactivos de IA en teléfonos pequeños también deben cumplir con el mismo conjunto de reglas
 * No molestar a la configuración——Recrear una copia no sólo derivará, sino que también hará que los usuarios encuentren"La conversación principal es tranquila, un pequeño teléfono celular envía mensajes privados en mitad de la noche.".
 *
 * Aquí solo se realiza la determinación pura de la función, no se lee ninguna configuración, no se envía ninguna notificación y la persona que llama pasa el valor de configuración.
 * La semántica de límites sigue siendo coherente con ProactiveMessageRunner:
 *  - start <= end: Horario ordinario, izquierda cerrada y derecha abierta [start, end)
 *  - start > end: Período nocturno,[start, 24) ∪ [0, end)
 *  - allDayAllowed: Suelte directamente (el interruptor existe precisamente porque
 *    allowedHourStart/End No se pueden expresar las 24 horas completas)
 */
object QuietHoursPolicy {

    /**
     * Si la hora actual está dentro de la ventana de envío permitida.
     *
     * @param currentHour 0-23
     * @param allowedHourStart Horas de inicio permitidas (inclusive)
     * @param allowedHourEnd Horas de finalización permitidas (exclusivo)
     * @param allDayAllowed permitido todo el dia
     */
    fun isWithinAllowedWindow(currentHour: Int, allowedHourStart: Int, allowedHourEnd: Int, allDayAllowed: Boolean): Boolean {
        if (allDayAllowed) return true
        val hour = currentHour.coerceIn(0, 23)
        return if (allowedHourStart <= allowedHourEnd) {
            hour in allowedHourStart until allowedHourEnd
        } else {
            hour >= allowedHourStart || hour < allowedHourEnd
        }
    }
}
