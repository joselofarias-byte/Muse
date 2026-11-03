package io.zer0.muse.transformer

/**
 * Copia de diagnóstico comprimida contextualmente.
 *
 * ## ¿Por qué poner un objeto solo?
 *
 * Estas cadenas solo se ingresan en el registro (para la resolución de problemas del desarrollador) y no son una copia de la interfaz ni un contenido enviado al modelo.
 * pero `ui/` El directorio tiene"Deshabilitar la codificación de literales chinos"barandilla (`check_hardcoded_cjk.py`),
 * Dejar la copia del registro en ViewModel activará esta barrera y nos obligará a relajar la línea base.——Eso es reducir las restricciones a cambio de conveniencia.
 * Convergir aquí no solo pasa la barandilla, sino que también mantiene el precedente existente (lado del modelo/texto lateral de diagnóstico con `ui/` Desacoplamiento del rango de escaneo).
 */
internal object CompressionDiagnostics {

    /** Compactación completada, punto de control escrito. */
    fun checkpointSaved(ratio: String, covered: Int, before: Int, after: Int): String =
        "Auto-compress Punto de control caído: ratio=$ratio, $covered artículos fusionados en resumen($before → $after Artículo)"

    /** Una vez que se completa la compresión pero no se sobrescribe ningún registro (el nivel del agua está vacío), no se escribe ningún punto de control en esta ronda. */
    const val NO_COVERAGE_RECORD: String = "Auto-compress Completado sin sobrescribir registros(El nivel del agua está vacío.),Sin escritura de puntos de control"

    /** Resumen no disponible, reconstrucción local sin solicitudes y punto de control eliminado. */
    fun localRebuildSaved(covered: Int, before: Int): String =
        "summary unavailable; local rebuild checkpoint saved: covered=$covered of $before"

    /** La reconstrucción local tampoco puede obtener contenido extraible, por lo que se abandonará en esta ronda (se mantendrá el historial completo). */
    const val LOCAL_REBUILD_EMPTY: String = "local rebuild produced nothing; keeping full history"

    /** No se pudo leer el punto de control de la sesión actual, presione"Sin puntos de control"Continuar. */
    fun readCheckpointFailed(message: String?): String = "No se pudo leer el punto de control de la sesión(Tratar como si no hubiera un punto de control): $message"

    /** La escritura del punto de control falló y la ronda continúa con la historia completa. */
    fun writeCheckpointFailed(message: String?): String = "Error al escribir el punto de control de sesión(Esta ronda continúa con la historia completa.): $message"

    /** No se pudo actualizar la recopilación de cobertura de puntos de control. */
    fun refreshCoveredIdsFailed(message: String?): String = "Error al actualizar la recopilación de cobertura del punto de control: $message"

    /** El resultado de la compresión automática ha caducado (el usuario cambió de sesión durante el período) y se descarta. */
    fun staleAutoCompressResult(sessionId: String): String = "Auto-compress Los resultados han caducado(Sesión cambiada),descartar: $sessionId"
}
