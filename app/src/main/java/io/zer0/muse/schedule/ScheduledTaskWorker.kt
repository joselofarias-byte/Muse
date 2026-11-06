package io.zer0.muse.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext

/**
 * v1.104 P3: ScheduledTaskRunner El WorkManager tiene todos los detalles.
 *
 * ScheduledTaskRunner El sondeo de rutina solo es efectivo cuando el proceso de la aplicación está activo,App Las tareas cronometradas no se pueden activar después de haber sido asesinado.
 * Este trabajador se programa periódicamente a través de WorkManager(Android Periodo mínimo 15 minutos),Incluso si se finaliza un proceso, el sistema puede iniciarlo y ejecutarlo.
 *
 * doWork llamar [ScheduledTaskRunner.tickOnceForWorker] Escanee todas las tareas pendientes a la vez:
 *  - Los canales de notificación se crean de forma idempotente cuando el proceso de trabajo se ejecuta por primera vez.
 *  - executeTask Internamente, resultOf se ha utilizado para encapsular llamadas de LLM/escribir bibliotecas/notificaciones.,Cualquier falla será registrada failed execution
 *
 * Compensaciones de diseño:
 *  - Sin setExpedited: Doze puede posponer las tareas,conformarse a"Ahorra energía"objetivo;Las tareas programadas no son inherentemente urgentes ni en tiempo real
 *  - No se requieren restricciones de red:Cuando no hay red, la llamada LLM fallará pero se registrará.,La próxima vez que se active el Trabajador, se volverá a intentar la tarea caducada.
 *  - Regresar Result.success() En lugar de reintentar: ejecutar tarea, el error se registra internamente y se adelanta next_run_at,
 *    Devolver el reintento hará que el sistema reintente con frecuencia un problema de red que no tiene solución.,Más consumo de electricidad
 *
 * Koin obtener dependencia:WorkManager Se ejecuta en el proceso principal de forma predeterminada.,Worker MuseApp.onCreate se ha ejecutado al inicio,
 * Koin Inicializado. si en Safe Mode(startKoin falló),GlobalContext esta vacio,
 * La excepción detectada aquí devuelve el éxito y esta vez se salta.,Espere a que se restablezca el próximo inicio.
 */
class ScheduledTaskWorker(
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
        val runner = resultOf { koin.get<ScheduledTaskRunner>() }.getOrNull()
        if (runner == null) {
            Logger.w(TAG, "ScheduledTaskRunner El análisis falló,Omitir la ejecución de este trabajador")
            return Result.success()
        }
        resultOf { runner.tickOnceForWorker() }
            .onError { msg, t -> Logger.w(TAG, "tickOnceForWorker failed: ${t?.message ?: msg}") }
        // independientemente del éxito interno,Ambos devuelven éxito: la tarea ejecutada ha registrado atómicamente el error + avance next_run_at,
        // Devolver el reintento hará que WorkManager lo vuelva a intentar repetidamente dentro de un corto período de tiempo.(Predeterminado 30 segundos),Al contrario, consume más electricidad.
        return Result.success()
    }

    companion object {
        private const val TAG = "ScheduledTaskWorker"
        const val UNIQUE_WORK_NAME = "muse_scheduled_task_worker"
    }
}
