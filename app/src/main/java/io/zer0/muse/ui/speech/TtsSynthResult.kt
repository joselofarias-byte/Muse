package io.zer0.muse.ui.speech

/**
 * El resultado de una síntesis de voz.
 *
 * Sólo respondo dos cosas: si el motor preferido está listo y si hay alguna garantía disponible. La persona que llama decide qué decirle al usuario en función de esto,
 * No es necesario adivinar a partir del booleano "si la clave no coincide, el tono es incorrecto o la red simplemente no funciona".
 */
sealed interface TtsSynthResult {

    /**
     * El motor preferido se sintetiza directamente con éxito.
     *
     * Cuando el motor es la nube, la nube tiene éxito; cuando el motor está en el sistema, el sistema TTS tiene éxito. —— Ninguna de las dos se considera una rebaja;
     * Por lo tanto, caen en la misma rama en la entrada de síntesis y el comportamiento predeterminado es el mismo que antes de la transformación.
     */
    data object Success : TtsSynthResult

    /**
     * El motor de la nube falló y volvió al sistema TTS y la síntesis fue exitosa (el sonido no era el asignado por el usuario).
     *
     * @param reason Una breve razón por la cual la nube no tuvo efecto (la razón alternativa de la capa de análisis o una breve descripción del error de síntesis)
     */
    data class SystemFallback(val reason: String) : TtsSynthResult

    /** Ni la nube ni el TTS del sistema funcionan correctamente y no hay audio disponible. */
    data class Failure(val reason: String) : TtsSynthResult
}

/**
 * Se determina el resultado de la rama de la nube: la nube tiene éxito; de lo contrario, depende de si el sistema TTS se puede mejorar.
 *
 * El propósito de extraer funciones puras es garantizar que las tres salidas (éxito/retroceso/fracaso) puedan cubrirse mediante una única prueba. —— Realmente levántate una vez
 * El motor TTS del sistema tiene que esperar una devolución de llamada asincrónica y no puede detectar la rama "el sistema está disponible pero falló la síntesis".
 *
 * @param cloudOk ¿La síntesis en la nube es exitosa?
 * @param failureReason La breve razón por la que la nube falla será llevada al [TtsSynthResult.SystemFallback]
 * @param systemTtsReady ¿Está listo el sistema TTS actualmente?
 * @param synthWithSystem Verdadera desafinación de la síntesis TTS del sistema (solo se ejecuta cuando falla la nube y el sistema está listo)
 */
internal suspend fun resolveCloudSynthOutcome(
    cloudOk: Boolean,
    failureReason: String,
    systemTtsReady: Boolean,
    synthWithSystem: suspend () -> Boolean,
): TtsSynthResult {
    if (cloudOk) return TtsSynthResult.Success
    if (!systemTtsReady) return TtsSynthResult.Failure("$failureReason(Sistema TTS no disponible)")
    return if (synthWithSystem()) {
        TtsSynthResult.SystemFallback(failureReason)
    } else {
        TtsSynthResult.Failure("$failureReason(La síntesis TTS del sistema falló)")
    }
}
