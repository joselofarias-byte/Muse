package io.zer0.muse.proactive

/**
 * Protocolo para el botón "Esta vez no" en notificaciones de mensajes proactivos.
 *
 * Las constantes y el análisis se colocan aquí en lugar de estar metidos en el receptor: el receptor es un shell de Android,
 * Cuando quiera poner a prueba el juicio de que "si cambia la acción, no debería haber contabilidad", no debe crear primero una [android.content.Intent].
 */
object ProactiveDismissProtocol {

    /** Solo se puede acceder dentro de la aplicación: la transmisión la envía el PendingIntent de la aplicación. */
    const val ACTION_DISMISS = "io.zer0.muse.action.PROACTIVE_DISMISS"

    /** La identificación de la cuenta de resultados que se va a marcar. */
    const val EXTRA_OUTCOME_ID = "extra_outcome_id"

    /** La identificación de la notificación se descartará fácilmente (0 Indica que no hay procesamiento). */
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

    /**
     * Obtenga la identificación del registro que se marcará en la transmisión; devuelve nulo si no está en este protocolo o no tiene una identificación.
     *
     * action Debe coincidir: los receptores se registran mediante el filtro de acción, pero siempre que alguien lo especifique explícitamente
     * La transmisión aún se puede transmitir, por lo que se vuelve a juzgar aquí para evitar recibir accidentalmente transmisiones de otras personas.
     */
    fun dismissedOutcomeId(action: String?, extra: String?): String? {
        if (action != ACTION_DISMISS) return null
        return extra?.takeIf { it.isNotBlank() }
    }
}
