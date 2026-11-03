package io.zer0.muse.miniphone.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.miniphone.data.chat.auto.MpProactiveChatRunner
import org.koin.core.context.GlobalContext

/**
 * Un Trabajador que periódicamente permite al personaje enviar un mensaje privado en segundo plano.
 *
 * La ontología sólo hace tres cosas.:Tomado de Koin [MpProactiveChatRunner], corre durante una ronda y regresa con éxito independientemente del resultado.
 * El juicio de "fuego o no" está todo en el corredor.(Función pura + registro),No hay negocios involucrados aquí.
 *
 * nunca regresar `retry`:No es necesario entregar mensajes no solicitados,Si fallas, espera al siguiente ciclo. —— Devolver el reintento sólo hará que el sistema
 * Vuelva a conectarse a la red en 30 segundos con el mismo contexto a prueba de fallos.,Convierte una interrupción en tres seguidas.
 *
 * Koin no puedo conseguirlo(modo seguro/módulo no ensamblado)También devuelve éxito: no podrás conseguirlo aunque lo intentes de nuevo.
 */
class MpProactiveChatWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val runner = resultOf { GlobalContext.get().get<MpProactiveChatRunner>() }
            .onError { msg, t -> Logger.w(TAG, "Error en el mensaje privado no solicitado | reason=koin_missing | ${t?.message ?: msg}", t) }
            .getOrNull()
        if (runner == null) return Result.success()
        resultOf { runner.run() }
            .onError { msg, t -> Logger.w(TAG, "Error en el mensaje privado no solicitado | reason=runner_error | ${t?.message ?: msg}", t) }
        return Result.success()
    }

    companion object {
        private const val TAG: String = "MiniPhoneChat"
    }
}
