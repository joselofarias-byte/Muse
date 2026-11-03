package io.zer0.muse.ui

/**
 * Deduplicación de registros de diagnóstico de "mensajes no renderizados".
 *
 * La lista visible del chat es introducida por produceState,messages Vuelva a calcular tan pronto como cambie —— Durante la salida de streaming, puede cambiar siete u ocho veces por segundo.,
 * mismo par (hidden, total) se escribirá repetidamente en el registro de depuración,Elimine la información de diagnóstico que realmente desea ver.
 *
 * Sólo una cosa que hacer aquí:Recuerde el último valor reportado. Ya no se recordarán llegadas consecutivas del mismo valor,Recuerde solo cuando el valor cambie.;
 * hidden Borrar memoria al volver a 0,Dejar que "el mismo par de números vuelva a aparecer" todavía cuenta como un nuevo evento.
 */
internal class ChatRenderLogGate {

    private var lastReported: Pair<Int, Int>? = null

    /** ¿Debería escribir un registro esta vez? */
    fun shouldLog(hidden: Int, total: Int): Boolean {
        if (hidden <= 0) {
            lastReported = null
            return false
        }
        val current = hidden to total
        if (current == lastReported) return false
        lastReported = current
        return true
    }
}
