package io.zer0.muse.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext

/**
 * v1.132: WorkManager de respaldo en la nube cubre a todos los trabajadores.
 *
 * [CloudBackupScheduler] El sondeo de rutina solo es efectivo cuando el proceso de la aplicación está activo,App La copia de seguridad programada no se puede activar después de ser eliminada.
 * Este trabajador se programa periódicamente a través de WorkManager(Android Periodo mínimo 15 minutos),El sistema también puede eliminar un proceso.
 * levantar la ejecución,completo"App Copia de seguridad automática después de ser asesinado"brecha de confiabilidad.
 *
 * doWork llamar [CloudBackupScheduler.checkAndSyncForWorker] Verificación única de vencimiento:
 *  - autoSync=false o isConfigured=false saltar directamente
 *  - desde la última sincronización < intervalHours salto de tiempo(No caducado)
 *  - Se activa cuando expira [BackupService.exportToCloud] subir
 *
 * Compensaciones de diseño(con [ScheduledTaskWorker] Alineación):
 *  - Sin setExpedited: Doze puede posponer las tareas de respaldo,conformarse a"Ahorra energía"objetivo
 *  - No se requieren restricciones de red:Cuando no hay red, se registra el fallo interno de exportToCloud.,La próxima vez que se active el Trabajador, se intentará nuevamente.
 *  - Regresar Result.success() En lugar de reintentar: devolver el reintento hará que WorkManager lo vuelva a intentar repetidamente en un corto período de tiempo.
 *    (Predeterminado 30 segundos)Un problema de red inherentemente irresoluble,Al contrario, consume más electricidad.
 *
 * Koin obtener dependencia:WorkManager Se ejecuta en el proceso principal de forma predeterminada.,Worker MuseApp.onCreate se ha ejecutado al inicio,
 * Koin Inicializado. si en Safe Mode(startKoin falló),GlobalContext esta vacio,
 * La excepción detectada aquí devuelve el éxito y esta vez se salta.,Espere a que se restablezca el próximo inicio.
 */
class CloudBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val koin = resultOf { GlobalContext.get() }.getOrNull()
        if (koin == null) {
            // H-SCHED-4: Alineado con ScheduledTaskWorker,Safe Mode Devolver el éxito en lugar de retry,
            // Evite que WorkManager desperdicie energía reintentando infinitamente dentro de un intervalo de 30 segundos
            Logger.w(TAG, "Koin no inicializado(Safe Mode?),Omitir la ejecución de este trabajador")
            return Result.success()
        }
        // v1.xxx: Control maestro de programación backend — Omitir el cuerpo ejecutable al apagar,El calendario periódico en sí sigue siendo,Reabrir para restaurar
        val workEnabled =
            scheduleWorkEnabledOrFalse(
                resultOf { koin.get<io.zer0.muse.data.SettingsRepository>()?.scheduleWorkEnabledFlow?.first() }
                    .getOrNull(),
            )
        if (!workEnabled) {
            Logger.i(TAG, "Se ha cerrado el control maestro de programación en segundo plano.,Saltar esta ejecución")
            return Result.success()
        }
        val scheduler = resultOf { koin.get<CloudBackupScheduler>() }.getOrNull()
        if (scheduler == null) {
            Logger.w(TAG, "CloudBackupScheduler El análisis falló,Omitir la ejecución de este trabajador")
            return Result.success()
        }
        resultOf { scheduler.checkAndSyncForWorker() }
            .onError { msg, t -> Logger.w(TAG, "checkAndSyncForWorker failed: ${t?.message ?: msg}") }
        // independientemente del éxito interno,Ambos devuelven éxito: la verificación de sincronización automática + el juicio de vencimiento están todos en checkAndSync,
        // Devolver el reintento hará que WorkManager lo vuelva a intentar con frecuencia(Predeterminado 30 segundos),Al contrario, consume más electricidad.
        return Result.success()
    }

    companion object {
        private const val TAG = "CloudBackupWorker"
        const val UNIQUE_WORK_NAME = "muse_cloud_backup_worker"
    }
}
