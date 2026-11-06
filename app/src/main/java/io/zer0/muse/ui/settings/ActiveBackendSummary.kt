package io.zer0.muse.ui.settings

import io.zer0.muse.rag.EmbeddingService

/** Un resumen del backend de inserción actualmente efectivo para mostrar en la página de configuración. */
data class ActiveBackendSummary(
    /** Copia de la línea principal: nombre para mostrar del backend y nombre del modelo, en forma de \"palabras clave locales\" · local-keyword-v1”. */
    val primary: String,
    /** El motivo de la rebaja; si no se degrada, es nulo y la persona que llama decide si muestra la línea de advertencia en función de esto. */
    val warning: String?,
)

/** El separador entre el nombre para mostrar y el nombre del modelo en la línea principal. */
private const val PRIMARY_SEPARATOR = " · "

/**
 * poner [EmbeddingService.BackendState] Convertido en dos líneas de copia que se pueden representar directamente en la página de configuración.
 *
 * bajar de categoría ([EmbeddingService.BackendState.degraded]=true) debe dar no vacío [ActiveBackendSummary.warning]:
 * Se utiliza cuando falta el motivo. [degradedFallback] Manténgase a salvo, evite"Usando otro backend"El asunto volvió a quedar en silencio.
 *
 * @param state La instantánea real del backend;null Indica que no ha sido analizado (aún no ha sido buscado)
 * @param degradedFallback Texto detallado al degradar, pero el backend no proporciona un motivo (cadena localizada pasada por la persona que llama)
 * @return resumen;[state] Si es nulo, devuelve nulo y la persona que llama no representará el bloque completo en consecuencia.
 */
fun resolveActiveBackendSummary(
    state: EmbeddingService.BackendState?,
    degradedFallback: String,
): ActiveBackendSummary? {
    if (state == null) return null
    val displayName = state.displayName.trim()
    val modelName = state.modelName.trim()
    val primary = if (modelName.isEmpty()) displayName else "$displayName$PRIMARY_SEPARATOR$modelName"
    val reason = state.reason?.trim()
    val warning = when {
        !state.degraded -> null
        !reason.isNullOrEmpty() -> reason
        else -> degradedFallback
    }
    return ActiveBackendSummary(primary = primary, warning = warning)
}
