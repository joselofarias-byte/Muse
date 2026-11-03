package io.zer0.muse.intent

/**
 * AUD-20261011-TASKER-ABUSE: Tasker Tres líneas de defensa de costos para la entrada a la radiodifusión(juicio puro,Se puede probar individualmente).
 *
 * fondo:TaskerTriggerReceiver para exported=true Entrada de difusión sin permiso,
 * Cualquier aplicación puede activar la finalización del LLM de facturación en un bucle. Mitigación verificada:completeText sin tools,
 * La transmisión externa no puede desencadenar indirectamente la ejecución de la herramienta y no existe riesgo de fuga de datos del usuario. —— Entonces la brecha es**abuso de costos**
 * spam con notificaciones,en lugar de una escalada de privilegios.
 *
 * tres líneas de defensa:
 *  1) interruptor principal(Desactivado de forma predeterminada):El usuario no responde si no está habilitado en la configuración.;
 *  2) Longitud máxima:Modelo de facturación de paso rápido extralargo,debe negarse;
 *  3) Limitación de frecuencia:La misma transmisión llega repetidamente dentro del intervalo mínimo, que es limitado.
 */
internal object TaskerTriggerGuard {

    /** Resultado del juicio. */
    enum class Decision {
        ACCEPTED,
        REJECTED_DISABLED,
        REJECTED_BLANK,
        REJECTED_TOO_LONG,
        REJECTED_THROTTLED,
    }

    /** prompt Número máximo de caracteres(más allá del rechazo total,Evite la facturación de transferencia de texto gigante). */
    const val MAX_PROMPT_LENGTH = 4_000

    /** Intervalo mínimo de activación(milisegundos):Se limitarán las transmisiones repetidas dentro de este período. */
    const val MIN_INTERVAL_MS = 5_000L

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongParameterList")
    fun evaluate(
        enabled: Boolean,
        promptLength: Int,
        maxPromptLength: Int = MAX_PROMPT_LENGTH,
        nowMs: Long,
        lastAcceptedAtMs: Long?,
        minIntervalMs: Long = MIN_INTERVAL_MS,
    ): Decision {
        if (!enabled) return Decision.REJECTED_DISABLED
        if (promptLength <= 0) return Decision.REJECTED_BLANK
        if (promptLength > maxPromptLength) return Decision.REJECTED_TOO_LONG
        val last = lastAcceptedAtMs
        // devolución de tiempo(now < last)No debe causar bloqueo permanente:Se considera transcurrido el intervalo.
        if (last != null && nowMs >= last && nowMs - last < minIntervalMs) {
            return Decision.REJECTED_THROTTLED
        }
        return Decision.ACCEPTED
    }
}
