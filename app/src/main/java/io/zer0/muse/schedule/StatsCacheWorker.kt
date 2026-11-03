package io.zer0.muse.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext

/**
 * v1.134: Trabajador de actualización de caché de agregación de estadísticas.
 *
 * [StatsCacheManager] La clase ya existe(v1.107)Pero nunca se ha conectado al sistema de despacho.(Componente huérfano).
 * Este trabajador se inicia a través de la tarea periódica diaria de WorkManager.,llamar [StatsCacheManager.refreshAll]
 * Actualizar completamente todas las cachés agregadas(Número de mensajes/Distribución del modelo/Distribución horaria/Top Conversación, etc.),Evite que la página de estadísticas se abra cada vez
 * Activa un escaneo GROUP BY de tabla completa.
 *
 * Compensaciones de diseño(con [ScheduledTaskWorker] consistente):
 *  - Periodo 24 horas(1 vez por día,Cumple las convenciones de anotación de clases de StatsCacheManager)
 *  - No utilice setExpedited: el caché de estadísticas no es urgente
 *  - No se requieren restricciones de red:Operaciones de bases de datos locales
 *  - Regresar Result.success():En caso de falla, solo se registra una advertencia.,La próxima vez que se active el Trabajador, se intentará nuevamente.
 *
 * Koin obtener dependencia:WorkManager Se ejecuta en el proceso principal de forma predeterminada.,Worker MuseApp.onCreate se ha ejecutado al inicio,
 * Koin Inicializado. si en Safe Mode(startKoin falló),GlobalContext esta vacio,
 * La excepción detectada aquí devuelve el éxito y esta vez se salta.,Espere a que se restablezca el próximo inicio.
 */
class StatsCacheWorker(
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
        val manager = resultOf { koin.get<io.zer0.muse.data.stats.StatsCacheManager>() }.getOrNull()
        if (manager == null) {
            Logger.w(TAG, "StatsCacheManager El análisis falló,Omitir la ejecución de este trabajador")
            return Result.success()
        }
        resultOf { manager.refreshAll() }
            .onError { msg, t -> Logger.w(TAG, "refreshAll failed: ${t?.message ?: msg}") }
        // independientemente del éxito interno,Todo devuelve éxito: el error solo registra advertencias
        return Result.success()
    }

    companion object {
        private const val TAG = "StatsCacheWorker"
        const val UNIQUE_WORK_NAME = "muse_stats_cache_worker"
    }
}
