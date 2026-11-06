package io.zer0.muse.schedule

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.zer0.common.Logger
import io.zer0.muse.backup.BackupService
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.notification.MuseNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * v1.98: La copia de seguridad en la nube carga automáticamente el programador con regularidad.
 *
 * cuando [CloudBackupConfig.autoSync] = true y [CloudBackupConfig.isConfigured] = true tiempo,
 * cada [CloudBackupConfig.autoSyncIntervalHours] Llamada automática cada hora. [BackupService.exportToCloud].
 *
 * Diseño y [ScheduledTaskRunner] consistente:Sondeo en appScope,Compruebe la caducidad cada 10 minutos.,
 * Al vencimiento, se activa la carga y se actualiza lastSyncAt.
 *
 * v1.132: Administrador de trabajo agregado([registerWorkManagerFallback]) — App Después de ser asesinado, el sistema
 * Sube cada 15 minutos [CloudBackupWorker] ejecutar [checkAndSyncForWorker],con [ScheduledTaskWorker]
 * El mecanismo ascendente sigue siendo el mismo. El sondeo de rutina es responsable de"App mientras esté vivo"en tiempo real,WorkManager responsable"App cuando lo matan"Fiabilidad.
 *
 * Los usuarios pueden ir a Configuración → Copia de seguridad en la nube → Personaliza el intervalo de horas en Copia de seguridad automática(Predeterminado 24 horas).
 */
class CloudBackupScheduler(
    private val backupService: BackupService,
    private val settings: SettingsRepository,
    private val appScope: CoroutineScope,
    private val notificationManager: MuseNotificationManager,
) {
    private var job: Job? = null

    /**
     * B-11: Exclusión mutua en proceso —— 10min Encuestas de rutina y 15min Worker(Mismo proceso principal)Se llamará simultáneamente
     * [checkAndSync] repetición del disparador exportToCloud,Utilice Mutex para serializar secciones críticas.
     */
    private val syncMutex = Mutex()

    /**
     * B-11: Bandera de preferencia de memoria —— Activar escritura antes de la carga,Evite llamadas repetidas fuera de secciones críticas
     * Desencadenado nuevamente dentro de los compañeros. Las etiquetas de memoria solo son visibles para el mismo proceso.;Proceso cruzado(WorkManager proceso independiente)
     * Todavía hay una ventana de carrera,ver [checkAndSync] Nota.
     */
    @Volatile
    private var syncStartedAt: Long = 0L

    companion object {
        private const val TAG = "CloudBackupSched"

        /** Intervalo de sondeo:10 Comprueba si caduca cada minuto.(No es necesario más a menudo,Intervalo de respaldo mínimo 1 hora). */
        private const val POLL_INTERVAL_MS = 10 * 60 * 1000L

        /** Intervalo mínimo:1 horas(Evite que la configuración del usuario sea demasiado pequeña, lo que provocará cargas frecuentes). */
        private const val MIN_INTERVAL_HOURS = 1

        /** B-11: Duración de la protección de preferencia de ventana misma(Obtener intervalo de sondeo,Cubre posibles activaciones repetidas dentro de una encuesta.). */
        private const val SYNC_GUARD_MS = 10 * 60 * 1000L
    }

    /** Inicie el sondeo programado. Llamado en Application.onCreate. */
    fun start() {
        job?.cancel()
        job = appScope.launch {
            Logger.i(TAG, "Scheduler started")
            while (isActive) {
                try {
                    checkAndSync()
                } catch (e: Exception) {
                    if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                    Logger.w(TAG, "Poll error: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Detener la votación programada. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * v1.132: Registre las tareas periódicas de WorkManager para obtener más detalles.
     *
     * en [io.zer0.muse.MuseApp.onCreate] Llamado una vez.KEEP Estrategia:Si ya existe, conserva el antiguo. schedule,
     * Evite registros duplicados.15 Los minutos son el período mínimo del sistema Android.
     *
     * Sin restricciones de red/aceleradas establecidas:conformarse a"Ahorra energía"objetivo,Cuando no hay red, se registra el fallo interno de exportToCloud.,
     * Se intentará nuevamente la próxima vez que se active el Trabajador. con [ScheduledTaskWorker] El diseño del fondo del bolsillo está perfectamente alineado.
     */
    fun registerWorkManagerFallback(context: Context) {
        try {
            val request = PeriodicWorkRequestBuilder<CloudBackupWorker>(
                15,
                TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                CloudBackupWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            Logger.i(TAG, "WorkManager fallback registered")
        } catch (e: Exception) {
            Logger.w(TAG, "registerWorkManagerFallback failed: ${e.message}")
        }
    }

    /**
     * v1.132: suministro [CloudBackupWorker] Entrada de cheque única llamada(Worker Se utiliza cuando el sistema inicia el proceso.).
     *
     * Compartido con encuestas de rutina. [checkAndSync] darse cuenta,Worker Koin en proceso ha sido inicializado(MuseApp.onCreate
     * ejecutado),La resolución de dependencia es normal.
     */
    suspend fun checkAndSyncForWorker() = checkAndSync()

    /**
     * Comprobar si caducidad,La carga se activa cuando caduca.
     * Condiciones:autoSync=true && isConfigured=true && desde la última sincronización ≥ intervalHours.
     *
     * B-11: uso [syncMutex] serie en proceso,Encuesta de rutina garantizada de 10 minutos y 15min Worker
     * No ingresará a la sección crítica de carga al mismo tiempo.;escribir antes del disparador [syncStartedAt] como marca de preferencia,
     * Llamadas simultáneas fuera de la sección crítica(Ruta rápida para bloquear no adquirida)También haga la misma intercepción de ventana.
     * ventana de proceso cruzado:WorkManager Si se ejecuta en un proceso independiente,DataStore/Las etiquetas de memoria pertenecen a diferentes procesos.,
     * En teoría, todavía se puede activar una carga repetida al mismo tiempo.,en última instancia por [BackupService.exportToCloud] Escribe idempotente.
     *
     * B-25: Control maestro de programación backend — Omitir el cuerpo ejecutable al apagar,El calendario periódico en sí sigue siendo,Vuelva a abrir para restaurar.
     */
    private suspend fun checkAndSync() {
        // B-25: Saltar directamente cuando el control maestro está cerrado(Leer el valor de la caché del almacén de datos,bajo costo)
        if (!readScheduleWorkEnabledOrFalse { settings.scheduleWorkEnabledFlow.first() }) {
            Logger.i(TAG, "Se ha cerrado el control maestro de programación en segundo plano.,Saltar la comprobación de la copia de seguridad en la nube")
            return
        }
        // B-11: Comprobación de preferencia rápida(desbloqueado):Si ya hay una carga en la misma ventana, se omitirá.,Evite los desencadenantes repetidos
        val now = System.currentTimeMillis()
        if (syncStartedAt > 0 && now - syncStartedAt < SYNC_GUARD_MS) {
            Logger.d(TAG, "La copia de seguridad en la nube está en camino(syncStartedAt apoderarse),Saltar este cheque")
            return
        }
        syncMutex.withLock {
            val config = settings.cloudBackupConfigFlow.first()
            if (!config.autoSync || !config.isConfigured) return@withLock

            val intervalHours = config.autoSyncIntervalHours.coerceAtLeast(MIN_INTERVAL_HOURS)
            val intervalMs = intervalHours * 60 * 60 * 1000L
            val lockNow = System.currentTimeMillis()
            if (lockNow - config.lastSyncAt < intervalMs) {
                Logger.d(TAG, "Not due yet: ${(lockNow - config.lastSyncAt) / 60000}min elapsed, need ${intervalMs / 60000}min")
                return@withLock
            }

            // B-11: Anteponer la marca de inicio de sincronización de escritura antes de activar,Formulario de preferencia en el proceso
            syncStartedAt = lockNow
            Logger.i(TAG, "Auto backup due (${(lockNow - config.lastSyncAt) / 3600000}h elapsed, interval=${intervalHours}h), uploading...")
            try {
                val outcome = backupService.exportToCloud()
                when (outcome) {
                    io.zer0.muse.backup.BackupService.CloudBackupOutcome.SUCCESS ->
                        Logger.i(TAG, "Auto backup succeeded")
                    io.zer0.muse.backup.BackupService.CloudBackupOutcome.WRITE_FAILED ->
                        Logger.w(TAG, "Auto backup failed: write failed")
                    io.zer0.muse.backup.BackupService.CloudBackupOutcome.VERIFY_FAILED ->
                        Logger.w(TAG, "Auto backup failed: read-back verification failed")
                    io.zer0.muse.backup.BackupService.CloudBackupOutcome.NOT_CONFIGURED ->
                        Logger.d(TAG, "Auto backup skipped: not configured")
                    // B-9: La contraseña de respaldo expiró(Keystore perdido)Procesamiento de fallos y alarmas.
                    io.zer0.muse.backup.BackupService.CloudBackupOutcome.PASSWORD_UNAVAILABLE ->
                        Logger.w(TAG, "Auto backup failed: backup password unavailable, please re-set it in cloud backup settings")
                }
                // v1.141 F1 + F-04: Recordatorio de copia de seguridad — Copia de seguridad automática programada completada/Se enviará una notificación del sistema en caso de falla.,
                // Fracaso por razón(escribir/verificar)Recordatorio de clasificación,Mantenga a los usuarios informados sobre las interrupciones en las copias de seguridad y verifique las configuraciones de la nube
                notificationManager.notifyAutoBackup(outcome)
            } finally {
                // Liberar la marca de preferencia después de cargar,Permitir la programación normal de la siguiente ventana
                syncStartedAt = 0L
            }
        }
    }
}
