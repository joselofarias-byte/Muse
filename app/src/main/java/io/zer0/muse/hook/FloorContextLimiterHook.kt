package io.zer0.muse.hook

import io.zer0.ai.core.MessageRole
import io.zer0.common.Logger
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * P1-4: Gancho de contexto limitado estilo piso.
 *
 * Tome el mensaje de USUARIO como"piso",Mantener los niveles N más recientes(completo USER + ASSISTANT + TOOL cadena),
 * Evite interrumpir una conversación en medio y dejar el contexto incompleto.
 *
 * Compresión basada en el número de token(ConversationCompressor)la diferencia:
 *  - Compresión: Resumir noticias antiguas en resúmenes,Reducir el uso de tokens
 *  - Restricciones de piso: Truncar directamente los mensajes antiguos que excedan los límites mínimos,Asegúrese de que cada capa esté completa
 *
 * Orden de ejecución: Las restricciones del piso se aplican después de la compresión.,Como corte final.
 *
 * Configuración:
 *  - floorLimiterEnabled: cambiar(Predeterminado false)
 *  - floorLimit: Número de pisos(8/16/32,Predeterminado 16)
 */
class FloorContextLimiterHook(
    private val settings: SettingsRepository,
) : PromptFinalizeHook {

    companion object {
        private const val TAG = "FloorContextLimiter"
    }

    override val id: String = "floor_context_limiter"
    override val priority: Int = 30 // baja prioridad,Ejecutado después de otro PromptFinalizeHook

    override suspend fun beforeFinalizePrompt(event: PromptFinalizeEvent): PromptFinalizeResult {
        val enabled = runCatching { settings.floorLimiterEnabledFlow.first() }.getOrDefault(false)
        if (!enabled) return PromptFinalizeResult(event.preparedHistory)

        val floorLimit = runCatching { settings.floorLimitFlow.first() }.getOrDefault(16)
        if (floorLimit <= 0) return PromptFinalizeResult(event.preparedHistory)

        val history = event.preparedHistory
        // Separe los mensajes del SISTEMA y los que no son del SISTEMA
        val systemMsgs = history.filter { it.role == MessageRole.SYSTEM }
        val nonSystemMsgs = history.filter { it.role != MessageRole.SYSTEM }

        // Estadísticas piso USUARIO
        val userCount = nonSystemMsgs.count { it.role == MessageRole.USER }
        if (userCount <= floorLimit) return PromptFinalizeResult(event.preparedHistory)

        // Cuenta regresiva FloorLimit desde el final USER,Mantener todos los mensajes desde esta posición hasta el final.
        var keepFromIndex = 0
        var countedUsers = 0
        for (i in nonSystemMsgs.indices.reversed()) {
            if (nonSystemMsgs[i].role != MessageRole.USER) continue
            countedUsers++
            if (countedUsers == floorLimit) {
                keepFromIndex = i
                break
            }
        }

        val trimmed = systemMsgs + nonSystemMsgs.drop(keepFromIndex)
        val removedCount = history.size - trimmed.size
        if (removedCount > 0) {
            Logger.i(TAG, "Restricciones de piso: mantener reciente $floorLimit Mensaje de USUARIO de capa,Truncar $removedCount mensaje antiguo")
        }
        return PromptFinalizeResult(trimmed)
    }
}
