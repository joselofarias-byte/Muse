package io.zer0.muse.proactive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.schedule.scheduleWorkEnabledOrFalse
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext

/**
 * Backend de WorkManager para mensajes proactivos (versión reconstruida).
 *
 * La diferencia clave con el antiguo trabajador es**Las responsabilidades han cambiado.**: El antiguo trabajador puede activar dos llamadas LLM cada vez que se activa.
 * Entonces "App Asesinados + despertados cada 15 minutos" es en sí mismo un gasto continuo.
 *
 * En el nuevo diseño, Worker es sólo un despertador: configura [ProactiveScheduler.runCycle],
 * La primera mitad de runCycle (control general → cambiar → Seleccionar asistente → relación → limpiar → contabilidad → Cuatro preguntas para determinar)
 * Todas las operaciones locales. La mayoría de los despertares volverán directamente cuando el "libro mayor esté vacío" o "el momento no sea el correcto".
 * No se enviará ni una sola solicitud de red.
 *
 * 15 Los minutos son el límite inferior del ciclo de WorkManager y no se pueden eludir. Pero como despertarse es gratis,
 * Este límite inferior ya no es una cuestión de coste, sino sólo una cuestión de precisión. —— Y la precisión está a cargo de la clase INMEDIATA.
 * La ventana de antigüedad es completa (la ventana es lo suficientemente amplia y no necesita ser precisa al minuto).
 */
class ProactiveWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val koin = resultOf { GlobalContext.get() }.getOrNull()
        val workEnabled = koin != null && scheduleWorkEnabledOrFalse(
            resultOf { koin.get<io.zer0.muse.data.SettingsRepository>()?.scheduleWorkEnabledFlow?.first() }
                .getOrNull(),
        )
        val scheduler = koin?.takeIf { workEnabled }
            ?.let { resultOf { it.get<ProactiveScheduler>() }.getOrNull() }

        when {
            koin == null -> Logger.w(TAG, "Koin no inicializado(Safe Mode?), saltate esta vez Worker")
            !workEnabled -> Logger.i(TAG, "El control maestro de programación en segundo plano se ha cerrado y se omitirá esta ejecución.")
            scheduler == null -> Logger.w(TAG, "ProactiveScheduler El análisis falló, omita esta vez Worker")
            // Cuando hay exactamente una ronda en marcha en el proceso, este despertar no tiene sentido: el cronometraje se volverá a juzgar una vez finalizada esa ronda.
            // Esta vez, si ingresa, solo será juzgado nuevamente por "Recién hablado" y la configuración se leerá en vano.
            scheduler.isCycleInFlight -> Logger.i(TAG, "La inspección en proceso está en progreso, omita esta vez Worker")
            else -> resultOf { scheduler.runCycle(ProactiveScheduler.TRIGGER_WORKER) }
                .onError { msg, t -> Logger.w(TAG, "La inspección falló: ${t?.message ?: msg}") }
        }

        // Siempre devuelva el éxito: los mensajes activos no son tareas que deban entregarse.
        // Devolver el reintento hará que el sistema reintente repetidamente un problema sin solución en 30 segundos.
        return Result.success()
    }

    companion object {
        private const val TAG = "ProactiveWorker"
        const val UNIQUE_WORK_NAME = "muse_proactive_worker_v2"
    }
}
