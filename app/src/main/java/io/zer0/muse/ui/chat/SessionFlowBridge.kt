package io.zer0.muse.ui.chat

/**
 * v1.x: [ChatSessionController] Paquete de devolución de llamada de responsabilidad cruzada.
 * Implementado por ChatViewModel,Haga que el controlador de sesión no dependa del host.
 */
interface SessionFlowBridge {
    suspend fun refreshContext()
    fun detachStreaming()
    fun onForkError(throwable: Throwable)

    /**
     * P0-5: Detiene todas las compilaciones en curso para la sesión especificada(Nivel de aplicación + cancelación de nivel de sesión).
     * Eliminar/Se debe llamar primero al archivar una sesión.,Evite que las devoluciones de llamada de streaming continúen escribiendo en sesiones eliminadas temporalmente.
     */
    fun stopGenerationForSession(sessionId: String?)

    /**
     * P0-5: Marcar esta sesión para supresión de escritura — Colocación de streaming en tránsito(persist / upsert)saltar,
     * Prevenir eliminado/Las sesiones archivadas se escriben y después de la recuperación."resurrección". Se borra automáticamente cuando se inicia una nueva transmisión.
     */
    fun suppressSessionWrites(sessionId: String?)
}
