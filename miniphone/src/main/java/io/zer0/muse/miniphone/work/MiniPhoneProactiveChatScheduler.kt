package io.zer0.muse.miniphone.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.zer0.common.Logger
import io.zer0.common.resultOf
import java.util.concurrent.TimeUnit

/**
 * El puerto de registro de tareas periódicas para los mensajes privados activos del rol.
 *
 * Por el puerto de registro en segundo plano del módulo([MiniPhoneMomentScheduler.start])Agendar juntos,El anfitrión no necesita reconocer una entrada adicional.
 *
 * Puntos de registro:
 * - **Ciclo 3 horas**:Al mismo ritmo que el círculo de amigos.,pero**Retraso inicial 90 minutos**:Los dos están escalonados por medio día.,
 *   No se despertarán en el mismo momento para acceder a Internet y CPU
 * - **restricción**:Necesidad de conectarse a Internet(La generación debe conectarse), la batería no está baja(Cuando la batería esté baja, no levante el teléfono del usuario para generar datos.)
 * - **única tarea + KEEP**:Solo hay una instancia al mismo tiempo.;Ya registrado, conserva el anterior.,Sin ciclo de reinicio
 *   (De lo contrario, el temporizador se borrará cada vez que se produzca un arranque en frío.,Nunca llega al punto)
 */
object MiniPhoneProactiveChatScheduler {

    /** Nombre de tarea único. Cambiarlo dejará una tarea periódica huérfana,Sólo puedo confiar en [stop] Cancelar explícitamente. */
    const val UNIQUE_WORK_NAME: String = "miniphone_proactive_chat"

    /** ciclo:3 horas. */
    const val REPEAT_INTERVAL_HOURS: Long = 3L

    /** El primer horario se retrasa 90 minutos.:Escalona tareas con el círculo de amigos,Evite apoderarse de recursos al mismo tiempo. */
    const val INITIAL_DELAY_MINUTES: Long = 90L

    /** Registrar tareas periódicas. Es seguro llamar repetidamente(KEEP). */
    fun start(context: Context) {
        resultOf {
            val request = PeriodicWorkRequestBuilder<MpProactiveChatWorker>(REPEAT_INTERVAL_HOURS, TimeUnit.HOURS)
                .setInitialDelay(INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }.onError { msg, t -> Logger.w(TAG, "Error en el registro de mensajes privados activos | reason=${t?.message ?: msg}", t) }
    }

    /** Cancelar tareas periódicas(la próxima vez [start] Será reprogramado). */
    fun stop(context: Context) {
        resultOf { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME) }
            .onError { msg, t -> Logger.w(TAG, "Falló la cancelación de mensaje privado no solicitado | reason=${t?.message ?: msg}", t) }
    }

    private const val TAG: String = "MiniPhoneChat"
}
