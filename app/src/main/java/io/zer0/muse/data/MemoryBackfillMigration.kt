package io.zer0.muse.data

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * v1.0.51: Migración de memoria de stock — El resumen continuo de la sesión del historial se inicia por primera vez después de la actualización.
 *
 * fondo:
 *  v1.0.50 Período MemoryLlmClientImpl descartado reasoning_content,Modelo de inferencia respuesta totalmente vacía →
 *  rollingSummary Regresar changed=false → SessionSummaryManager No saveSummary →
 *  session_summaries La tabla no tiene filas para estas sesiones. → compileToday/Daily/Facts No se pueden extraer datos →
 *  memory.md Siempre un marcador de posición de 4 párrafos.
 *
 *  v1.0.51 Se corrigió el engaño de LLM,Pero sólo tiene efecto para nuevas sesiones. Esta categoría es responsable de reemplazar los viejos. session:
 *  1. Iterar a través de todos los activos session,Encuentre aquellos que no tienen filas correspondientes en la tabla session_summaries
 *  2. Lea el importe completo de MessageDao messages → hola SessionSummaryManager.rollingSummary
 *  3. Ajustar después de completar tick() Ejecución completa daily pipeline(compileToday/Daily/Facts/longterm)
 *  4. Indicador de finalización de migración de persistencia,No se ejecutará la próxima vez que se inicie.
 *
 * control de concurrencia:
 *  - [SettingsRepository.tryStartMemoryBackfillMigration] Utilice AtomicBoolean para asegurarse de que solo se ejecute una vez
 *  - [MemoryTicker.backfillSession] Reutilizar doRollingSummary Semaphore(3) Estrangulamiento global
 *  - El fallo de una sola sesión no interrumpe el proceso general,Registro de recuento fallido
 *
 * informe de progreso:
 *  - Pase [MemoryTicker.backfillProgressFlow] informes en tiempo real (total/processed/succeeded/failed)
 *  - MemoryScreen Suscríbase a este flujo para mostrar una barra de progreso en la parte superior
 */
class MemoryBackfillMigration(
    private val sessionRepository: SessionRepository,
    private val memoryTicker: MemoryTicker,
    private val settings: SettingsRepository,
) {
    companion object {
        private const val TAG = "MemoryBackfill"
    }

    /**
     * Verifique y ejecute la migración. Si ha sido migrado, se devolverá directamente.
     *
     * Tres guardias garantizan una seguridad idempotente:
     *  1. DataStore Bit de bandera([SettingsRepository.memoryBackfillMigrationDoneFlow])— Proceso cruzado/Reiniciar la persistencia
     *  2. AtomicBoolean compareAndSet([SettingsRepository.tryStartMemoryBackfillMigration])— Primera llamada anticoncurrencia
     *  3. interruptor de memoria([SettingsRepository.isMemoryEnabled])— Respetar la configuración del usuario de apagar la memoria.
     *
     * @return true Indica que se realizó la migración.(ya sea exitoso o no);false Indica que ha sido migrado./No es necesario migrar/memoria apagada
     */
    suspend fun migrateIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        // Guardia 1: verificación de bandera de persistencia
        if (settings.memoryBackfillMigrationDoneFlow.first()) {
            return@withContext false
        }
        // guardia 2: AtomicBoolean compareAndSet,Evitar la ejecución repetida de primeras llamadas simultáneas
        if (!settings.tryStartMemoryBackfillMigration()) {
            return@withContext false
        }
        // Guardia 3: No se ejecutará si el interruptor de memoria está apagado(Respetar la configuración del usuario),Sin embargo, todavía está marcado como migrado para evitar comprobarlo la próxima vez.
        if (!settings.isMemoryEnabled()) {
            Logger.i(TAG, "apagado de memoria,Saltar migración de reposición")
            settings.saveMemoryBackfillMigrationDone(true)
            return@withContext false
        }

        Logger.i(TAG, "Iniciar la migración de la memoria de stock:Historial de escaneo sin resumen session")
        val model = resultOf { settings.getSelectedModel() }.getOrNull()
        // locale/timeZone Usar valor predeterminado,Consistente con la llamada ChatViewModel.notifyTurn
        val locale = "es"
        val timeZone = io.zer0.memory.time.TimeContext.DEFAULT_TIMEZONE

        val allSessions = resultOf { sessionRepository.getAllActiveSessionsForBackfill() }
            .onError { msg, t -> Logger.w(TAG, "No se pudo obtener la lista de sesiones: $msg", t) }
            .getOrNull() ?: emptyList()
        val summarizedIds = resultOf { memoryTicker.getAllSummarizedSessionIds() }
            .onError { msg, t -> Logger.w(TAG, "No se pudo obtener la lista resumida existente: $msg", t) }
            .getOrNull() ?: emptySet()
        val toBackfill = allSessions.filter { it.id !in summarizedIds }

        if (toBackfill.isEmpty()) {
            Logger.i(TAG, "No es necesario migrar:todos ${allSessions.size} Cada sesión tiene un resumen.")
            settings.saveMemoryBackfillMigrationDone(true)
            return@withContext false
        }

        Logger.i(TAG, "Necesito recuperar una carrera ${toBackfill.size}/${allSessions.size} resumen de sesiones")
        var succeeded = 0
        var failed = 0
        for ((i, session) in toBackfill.withIndex()) {
            // Informe de progreso(Actualmente procesando el i+1o)
            memoryTicker.reportBackfillProgress(
                MemoryTicker.BackfillProgress(
                    total = toBackfill.size,
                    processed = i,
                    succeeded = succeeded,
                    failed = failed,
                    currentSessionId = session.id,
                    done = false,
                ),
            )

            val messages = resultOf { sessionRepository.getAllMessagesForBackfill(session.id) }
                .onError { msg, t -> Logger.w(TAG, "leer session ${session.id.take(8)}… Mensaje fallido: $msg", t) }
                .getOrNull() ?: emptyList()
            if (messages.isEmpty()) {
                // Es normal que una sesión vacía no tenga resumen.,No contado failed
                continue
            }

            val changed = resultOf {
                memoryTicker.backfillSession(session.id, messages, model, locale, timeZone, session.assistantId)
            }.onError { msg, t ->
                Logger.w(TAG, "backfill session ${session.id.take(8)}… falló: $msg", t)
            }.getOrNull() ?: false

            if (changed) succeeded++ else failed++
        }

        // informe final de progreso(done=true,UI De acuerdo con esto, se ocultará automáticamente después de que se muestre el estado de finalización.)
        memoryTicker.reportBackfillProgress(
            MemoryTicker.BackfillProgress(
                total = toBackfill.size,
                processed = toBackfill.size,
                succeeded = succeeded,
                failed = failed,
                currentSessionId = null,
                done = true,
            ),
        )
        Logger.i(TAG, "Ejecución de resumen completada: éxito $succeeded, falló $failed, total ${toBackfill.size}")

        // Ejecución completa daily pipeline,Deja que lleguen nuevos resúmenes memory.md(compileToday/Daily/Facts/longterm)
        resultOf { memoryTicker.tick(model, locale, timeZone) }
            .onError { msg, t -> Logger.w(TAG, "Después de la migración tick() falló: $msg", t) }

        // Indicador de finalización de migración de persistencia
        settings.saveMemoryBackfillMigrationDone(true)
        // v1.0.51: No aquí flujo de progreso claro — Reservar done=true Estado,
        // Permitir a los usuarios ver al ingresar al centro de memoria"Completado"Consejos(Después de que se muestre la página, clear)
        true
    }
}
