package io.zer0.muse.ui.chat

/**
 * Entrada externa de solo lectura para la ocupación superior e inferior de la conversación actual.
 *
 * La ocupación es el estado de la memoria de ViewModel (recalculado después de cada ronda de generación). La capa de herramientas solo obtiene
 * [io.zer0.muse.tools.ToolExecutionContext], ChatUiState no es visible. Aquí hay un
 * Punto de enlace a nivel de proceso: enlace una vez cuando se inicia ChatViewModel (es un singleton a nivel de proceso), leído por el lado de la herramienta
 * Es el número en el círculo de ocupación y no habrá "inconsistencia entre la tasa de ocupación informada por la herramienta y la interfaz".
 *
 * Sin consolidar (tarea en segundo plano/Devuelve nulo si el cuadro de diálogo no está abierto), la persona que llama debe omitir toda la línea en lugar de informar 0.
 */
object ContextOccupancyProbe {

    @Volatile
    private var supplier: (() -> Pair<Int, Int>?)? = null

    /** Atar al lector; el que se vincula más tarde anula el que se vincula primero (recarga en caliente)/Cuando existan múltiples instancias prevalecerá la última conversación). */
    fun bind(reader: () -> Pair<Int, Int>?) {
        supplier = reader
    }

    /** (Actualmente ocupado, límite superior); Devuelve nulo si no está vinculado o no se pudo leer. */
    fun current(): Pair<Int, Int>? = runCatching { supplier?.invoke() }.getOrNull()
}
