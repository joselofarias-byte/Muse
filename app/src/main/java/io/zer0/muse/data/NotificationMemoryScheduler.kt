package io.zer0.muse.data

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.ai.NotificationMemoryDigest
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.notification.MuseNotificationListenerService
import io.zer0.muse.notification.NotificationRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * v2.6.2(comentarios de los usuarios): Notificación → Clasificación y programación del fondo de la memoria.
 *
 * Los usuarios esperan que el asistente “conozca” las notificaciones recibidas y las mencione de forma natural en los mensajes activos. Este planificador es responsable de:
 *  1. Periódicamente (siguiendo el ritmo del ticker de la memoria) extrae notificaciones recopiladas por el servicio de escucha de notificaciones que aún no se han resuelto.
 *  2. entregar [NotificationMemoryDigest](Vaya al modelo de herramienta grande) Peinar/integrar/Eliminar duplicados
 *  3. Después de colocarse en la biblioteca, los datos de la clase de notificación se inyectan en el contexto del mensaje activo con memoria (consulte ProactiveMessageRunner).
 *
 * ritmo:Alinear con la compilación de memoria —— Predeterminado cada [POLL_INTERVAL_MS] Verifique una vez; solo si es nuevo,
 * Solo las notificaciones dentro de la ventana de antigüedad ajustarán el LLM para evitar que los tokens estén inactivos y quemados.
 *
 * Condiciones previas:
 *  - El usuario tiene derechos de notificación autorizados ([MuseNotificationListenerService.isConnected])
 *  - La función de memoria está activada ([SettingsRepository.isMemoryEnabled])
 *  - interruptor principal [SettingsRepository.notificationMemoryEnabledFlow](Desactivado de forma predeterminada, habilitado explícitamente por el usuario)
 */
class NotificationMemoryScheduler(
    private val settings: SettingsRepository,
    private val digest: NotificationMemoryDigest,
    private val memoryTicker: MemoryTicker,
    private val scope: CoroutineScope,
) {

    private var job: kotlinx.coroutines.Job? = null

    /** La última marca de tiempo de notificación procesada la última vez para evitar clasificar repetidamente las notificaciones antiguas. */
    @Volatile
    private var lastProcessedAt: Long = 0L

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            Logger.i(TAG, "NotificationMemoryScheduler started")
            // Inicializar el cursor al iniciar:Solo se procesan las notificaciones recibidas después del inicio (las notificaciones históricas no se rastrean para evitar una inyección única).
            lastProcessedAt = System.currentTimeMillis()
            while (isActive) {
                runCatching { tickOnce() }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else Logger.w(TAG, "tick falló: ${it.message}") }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Cardado único (para pruebas/gatillo manual). */
    suspend fun tickOnce(): NotificationMemoryDigest.DigestResult? {
        val fresh = collectFresh() ?: return null
        return runDigest(fresh)
    }

    /** Recopile notificaciones nuevas después del cursor; Devuelve nulo si no hay nuevas notificaciones o no se cumplen las condiciones previas. */
    private suspend fun collectFresh(): List<NotificationRecord>? {
        if (!shouldRun()) return null
        val windowStart = System.currentTimeMillis() - WINDOW_MS
        val since = maxOf(windowStart, lastProcessedAt)
        return MuseNotificationListenerService.recentNotifications.value
            .filter { it.timestamp > since }
            .sortedBy { it.timestamp }
            .takeIf { it.isNotEmpty() }
    }

    /** Condiciones previas:El interruptor de memoria de notificación + función de memoria + autorización de notificación están todos listos. */
    private suspend fun shouldRun(): Boolean = settings.notificationMemoryEnabledFlow.first() &&
        settings.isMemoryEnabled() &&
        MuseNotificationListenerService.isConnected()

    /** Envíe un lote de notificaciones nuevas al modelo de herramienta grande para que las clasifique y almacene. */
    private suspend fun runDigest(fresh: List<NotificationRecord>): NotificationMemoryDigest.DigestResult {
        val assistantId = settings.getDefaultAssistantId().ifBlank { "main" }
        val scopeId = if (assistantId == "default") "main" else assistantId
        val spaceId = settings.currentSpaceIdFlow.first().ifBlank { "default" }
        val result = digest.digest(
            notifications = fresh.map { it.toInput() },
            scope = scopeId,
            spaceId = spaceId,
            model = null,
            // Enrutamiento desde MemoryLlmClient a un archivo de herramienta grande
            locale = settings.getLanguageSync().let { if (it == "system" || it.isBlank()) "es" else it },
        )
        // Independientemente de si se coloca en la base de datos o no, el cursor avanza para evitar enviar el mismo lote de notificaciones a LLM repetidamente.
        lastProcessedAt = fresh.maxOf { it.timestamp }
        if (result.extracted > 0) {
            // Activa una compilación de memoria para permitir que nuevos hechos ingresen a memoria.md (consistente con la migración de reabastecimiento).
            resultOf { memoryTicker.tick() }
                .onError { msg, t -> Logger.w(TAG, "Después de la notificación de abandono tick() falló: $msg", t) }
        }
        return result
    }
    private fun NotificationRecord.toInput() = NotificationMemoryDigest.NotificationInput(
        packageName = packageName,
        appLabel = appLabel,
        title = title,
        text = text,
        timestamp = timestamp,
    )

    companion object {
        private const val TAG = "NotifMemorySched"

        /** intervalo de verificación (10 minutos)——La clasificación de notificaciones no es un requisito en tiempo real; la baja frecuencia es suficiente. */
        private const val POLL_INTERVAL_MS = 10 * 60 * 1000L

        /** Solo se ordenarán las notificaciones dentro de las últimas 24 horas (ventana de puntualidad). */
        private const val WINDOW_MS = 24 * 60 * 60 * 1000L
    }
}
