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
 * Puerto de registro para tareas programadas en segundo plano del módulo.
 *
 * Llamado por el anfitrión cuando "hay un teléfono celular pequeño disponible" [start] Una vez, al apagar el pequeño celular [stop].
 * Este módulo no tiene su propio Application,No hay ninguna entrada de inicialización en el manifiesto. —— No empieces en secreto en el módulo.:
 * WorkManager es el mismo tiempo de ejecución que el host ya está usando,El momento del registro es una decisión del producto del anfitrión.
 *
 * El anfitrión sólo reconoce esta entrada.,Entonces los dos conjuntos de tareas periódicas en el módulo(Actualizaciones de momentos, mensajes privados activos de personajes.)Todo programado aquí:
 * Nos vemos si tomas la iniciativa de enviar un mensaje privado. [MiniPhoneProactiveChatScheduler].
 *
 * Puntos de registro:
 * - **Ciclo 3 horas**:Baja frecuencia, no perturbadora.WorkManager El ciclo es inherentemente inexacto(El sistema presionará la batería./Volver al modo de espera),
 *   Por lo tanto, la antivibración no depende de la precisión del período.,Depende del juicio del corredor.
 * - **restricción**:Necesidad de conectarse a Internet(La generación debe conectarse), la batería no está baja(Cuando la batería esté baja, no levante el teléfono del usuario para generar datos.)
 * - **única tarea + KEEP**:Solo hay una instancia al mismo tiempo.;Ya registrado, conserva el anterior.,Sin ciclo de reinicio
 *   (De lo contrario, el temporizador se borrará cada vez que se produzca un arranque en frío.,Nunca llega al punto)
 */
object MiniPhoneMomentScheduler {

    /** Nombre de tarea único. Cambiarlo dejará una tarea periódica huérfana,Sólo puedo confiar en [stop] Cancelar explícitamente. */
    const val UNIQUE_WORK_NAME: String = "miniphone_auto_moment"

    /** ciclo:3 horas. */
    private const val REPEAT_INTERVAL_HOURS: Long = 3L

    /** Registrar tareas periódicas. Es seguro llamar repetidamente(KEEP). */
    fun start(context: Context) {
        resultOf {
            val request = PeriodicWorkRequestBuilder<MpMomentWorker>(REPEAT_INTERVAL_HOURS, TimeUnit.HOURS)
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
        }.onError { msg, t -> Logger.w(TAG, "Error en el registro programado de Momentos | reason=${t?.message ?: msg}", t) }
        // mensaje privado activo:Mismo período de 3 horas + retraso inicial de 90 minutos,A medio latido de la cima
        MiniPhoneProactiveChatScheduler.start(context)
    }

    /** Cancelar tareas periódicas(la próxima vez [start] Será reprogramado). */
    fun stop(context: Context) {
        resultOf { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME) }
            .onError { msg, t -> Logger.w(TAG, "La cancelación programada de Momentos falló | reason=${t?.message ?: msg}", t) }
        MiniPhoneProactiveChatScheduler.stop(context)
    }

    private const val TAG: String = "MiniPhoneMoment"
}
