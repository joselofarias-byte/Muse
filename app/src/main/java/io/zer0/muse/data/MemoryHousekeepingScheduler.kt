package io.zer0.muse.data

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.agentdm.AgentDmRepository
import io.zer0.muse.data.groupchat.GroupChatMemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * v2.6.2(auditoría de memoria): Programación de limpieza de datos asistida por memoria.
 *
 * Antecedentes:`GroupChatMemoryRepository.cleanupOlderThan` con `AgentDmRepository.cleanupOlderThan`
 * Previamente definido pero sin punto de llamada para posición completa —— La anotación dice"Limpiar regularmente", ningún programador realmente lo llama,
 * Esto da como resultado un crecimiento ilimitado de la memoria del chat grupal y de los mensajes privados de los agentes.
 *
 * Este programador los cuelga: límpielos una vez al inicio y luego cada vez [INTERVAL_MS] Límpielo una vez.
 * La retención predeterminada es de 30 días, ajustable en el sitio de la llamada.
 */
class MemoryHousekeepingScheduler(
    private val settings: SettingsRepository,
    private val groupChatMemoryRepository: GroupChatMemoryRepository,
    private val agentDmRepository: AgentDmRepository,
    private val scope: CoroutineScope,
) {

    private var job: kotlinx.coroutines.Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            Logger.i(TAG, "MemoryHousekeepingScheduler started")
            while (isActive) {
                runCatching { sweep() }
                    .onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        Logger.w(TAG, "Error al borrar la memoria: ${it.message}")
                    }
                delay(INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Limpieza única (para probar/gatillo manual). */
    suspend fun sweep() {
        // Respetar el interruptor maestro de memoria: No limpiar la memoria cuando el usuario la cierra (los datos se conservan para una fácil reapertura).
        if (!settings.isMemoryEnabled()) return
        val keepDays = RETENTION_DAYS
        resultOf { groupChatMemoryRepository.cleanupOlderThan(keepDays) }
            .onError { msg, t -> Logger.w(TAG, "No se pudo borrar la memoria del chat grupal: $msg", t) }
        resultOf { agentDmRepository.cleanupOlderThan(keepDays) }
            .onError { msg, t -> Logger.w(TAG, "No se pudieron borrar los mensajes privados del agente: $msg", t) }
    }

    companion object {
        private const val TAG = "MemoryHousekeeping"

        /** intervalo de limpieza (12 horas). */
        private const val INTERVAL_MS = 12 * 60 * 60 * 1000L

        /** Número de días a conservar. */
        private const val RETENTION_DAYS = 30
    }
}
