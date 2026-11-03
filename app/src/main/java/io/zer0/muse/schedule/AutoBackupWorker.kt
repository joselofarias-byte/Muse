package io.zer0.muse.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext

/**
 * v1.134: Trabajador de copia de seguridad automática de la base de datos local.
 *
 * [AutoBackupHelper] La clase ya existe(v1.107)Pero nunca se ha conectado al sistema de despacho.(Componente huérfano).
 * Este trabajador se inicia a través de la tarea periódica diaria de WorkManager.,ejecutar:
 *  1. [AutoBackupHelper.backupNow] — WAL checkpoint + Copie muse.db a backups/
 *  2. [AutoBackupHelper.trimOldBackups] — Conserva las últimas 7 copias.,limpiar mayores
 *
 * Compensaciones de diseño(con [ScheduledTaskWorker] consistente):
 *  - Periodo 24 horas(1 vez por día,Cumplir con las convenciones de anotación de la clase AutoBackupHelper)
 *  - No utilice setExpedited: copia de seguridad no urgente
 *  - No se requieren restricciones de red:operación local,No se requiere internet
 *  - La copia de seguridad falla y regresa Result.retry(),Haga que WorkManager vuelva a intentarlo en caso de una falla temporal de E/S/base de datos; limpiar copias de seguridad antiguas después del éxito
 *
 * Koin obtener dependencia:WorkManager Se ejecuta en el proceso principal de forma predeterminada.,Worker MuseApp.onCreate se ha ejecutado al inicio,
 * Koin Inicializado. si en Safe Mode(startKoin falló),GlobalContext esta vacio,
 * La excepción detectada aquí devuelve el éxito y esta vez se salta.,Espere a que se restablezca el próximo inicio.
 */
class AutoBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val koin = resultOf { GlobalContext.get() }.getOrNull()
        if (koin == null) {
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
        val helper = resultOf { koin.get<io.zer0.muse.data.stats.AutoBackupHelper>() }.getOrNull()
        if (helper == null) {
            Logger.w(TAG, "AutoBackupHelper El análisis falló,Omitir la ejecución de este trabajador")
            return Result.success()
        }
        val ok = resultOf { helper.backupNow() }
            .onError { msg, t -> Logger.w(TAG, "backupNow failed: ${t?.message ?: msg}") }
            .getOrNull() ?: false
        if (!ok) {
            // El motivo del error se registra, pero WorkManager aún debe volver a intentarlo con una recuperación temporal de E/S/base de datos.
            return Result.retry()
        }
        helper.trimOldBackups()
        return Result.success()
    }

    companion object {
        private const val TAG = "AutoBackupWorker"
        const val UNIQUE_WORK_NAME = "muse_auto_backup_worker"
    }
}
