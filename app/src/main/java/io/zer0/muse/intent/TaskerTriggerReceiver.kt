package io.zer0.muse.intent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.notification.MuseNotificationTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.java.KoinJavaComponent

/**
 * v2.x: Tasker Receptor vinculado.
 *
 * Se agregó recientemente la evaluación comparativa de capacidades del proyecto de referencia: Permitir la plataforma de automatización de Android (Tasker / MacroDroid etc.) vía transmisión
 * Active Muse para que ejecute una ronda de diálogo y los resultados se devolverán como notificaciones. llenar"Orquestación de automatización externa Muse"brecha.
 *
 * Uso (Tasker Acción "Enviar intención"):
 *  - Acción:`io.zer0.muse.action.TASKER_TRIGGER`
 *  - Nombre del paquete:`io.zer0.muse`
 *  - Parámetros adicionales:`prompt` = Texto para enviar a AI (obligatorio)
 *
 * Modelo de ejecución: ronda única en segundo plano (sin interfaz de usuario), reutilización [ChatService] El mismo conjunto de análisis de modelos que el enlace principal.
 * Sesión de redacción de resultados (nueva sesión dedicada)+ Enviar notificación, que tiene el mismo origen que la acción de IA de las tareas programadas.
 *
 * Seguridad: solo responda a acciones explícitas de este paquete (no exporte ninguna recepción de Intent); la palabra rápida se completa una vez,
 * No otorgue permiso de ejecución a la herramienta (para evitar transmisiones externas que activen indirectamente las operaciones del dispositivo). Si necesita capacidades de herramientas, utilice tareas programadas en la aplicación.
 */
class TaskerTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TASKER_TRIGGER) return
        val prompt = intent.getStringExtra(EXTRA_PROMPT)?.trim().orEmpty()
        if (prompt.isEmpty()) {
            Logger.w(TAG, "Tasker Activador que falta mensaje, ignorar")
            return
        }
        val pendingResult = goAsync()
        @Suppress("TooGenericExceptionCaught")
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // v2.6.7 fix (AUDIT F22): La base de datos no se eliminará dentro de la ventana de recuperación.
                // La antigua implementación appendMessage restaura directamente la transacción clearConversationStateForRestore
                // Esta ronda de escritura se sobrescribirá. —— Se muestra como "Tasker Se activa pero no queda ningún diálogo."
                if (io.zer0.common.ProcessWriteGate.restoring) {
                    Logger.w(TAG, "Recuperación de copia de seguridad en progreso, omitiendo el activador de Tasker")
                    return@launch
                }
                val settings = runCatching {
                    KoinJavaComponent.get<io.zer0.muse.data.SettingsRepository>(
                        io.zer0.muse.data.SettingsRepository::class.java,
                    )
                }.getOrNull()
                // AUD-20261011-TASKER-ABUSE: tres líneas de defensa —— El interruptor está apagado por defecto + longitud máxima + límite de frecuencia.
                // Este receptor es una entrada exportada sin permiso.,Se puede enviar cualquier solicitud.;
                // La facturación se puede activar cíclicamente sin defensa LLM(Se ha verificado que la ejecución de la herramienta no se puede activar indirectamente.,Por lo tanto, la brecha es un abuso de costos.).
                val decision = TaskerTriggerGuard.evaluate(
                    enabled = settings?.taskerTriggerEnabledFlow?.first() ?: false,
                    promptLength = prompt.length,
                    nowMs = System.currentTimeMillis(),
                    lastAcceptedAtMs = lastAcceptedAt,
                )
                if (decision != TaskerTriggerGuard.Decision.ACCEPTED) {
                    Logger.w(TAG, "Tasker Disparador denegado: $decision")
                    return@launch
                }
                lastAcceptedAt = System.currentTimeMillis()
                runTrigger(context.applicationContext, prompt)
            } catch (e: Exception) {
                Logger.e(TAG, "Tasker La ejecución del disparador falló: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun runTrigger(app: Context, prompt: String) {
        val chatService = KoinJavaComponent.get<ChatService>(ChatService::class.java)
        val sessionRepo = KoinJavaComponent.get<SessionRepository>(SessionRepository::class.java)
        val assistantRepo = KoinJavaComponent.get<AssistantRepository>(AssistantRepository::class.java)

        val assistantId = "default"
        val assistant = resultOf { assistantRepo.getById(assistantId) }.getOrNull()
        val messages = listOf(UIMessage(role = MessageRole.USER, content = prompt))
        val completion = withTimeoutOrNull(LLM_TIMEOUT_MS) {
            chatService.completeText(
                messages = messages,
                temperature = assistant?.temperature,
                maxTokens = assistant?.maxTokens,
            )
        } ?: run {
            Logger.w(TAG, "Tasker Activar el tiempo de espera de la IA")
            return
        }
        val reply = io.zer0.muse.transformer.stripThinkTags(completion.text).ifBlank { "(respuesta vacía)" }

        // Coloque la base de datos en una sesión dedicada (creada por primera vez y reutilizada en sesiones posteriores con el mismo nombre), que tiene el mismo origen que la tarea programada.
        val sessionId = resultOf {
            val existing =
                sessionRepo.observeSessions().first().firstOrNull { it.title == TASKER_SESSION_TITLE }
            if (existing != null) {
                existing.id
            } else {
                val id = sessionRepo.createSession(assistantId)
                sessionRepo.renameSession(id, TASKER_SESSION_TITLE)
                id
            }
        }.getOrNull()
        if (sessionId != null) {
            resultOf {
                sessionRepo.appendMessage(sessionId, UIMessage(role = MessageRole.USER, content = prompt))
                sessionRepo.appendMessage(sessionId, UIMessage(role = MessageRole.ASSISTANT, content = reply))
            }.onError { msg, _ -> Logger.w(TAG, "Tasker La sesión no pudo iniciar sesión en la base de datos: $msg") }
        }

        // Resultado de devolución de notificación
        // v2.6.0 (AUD-CHANNEL-F10): Cambiar la política de notificación global del usuario —— La implementación original estaba codificada
        // policy = "always",Incluso si el usuario selecciona“nunca notificar”,La notificación seguirá apareciendo cada vez que se active Tasker.
        resultOf {
            val settings = runCatching {
                KoinJavaComponent.get<io.zer0.muse.data.SettingsRepository>(
                    io.zer0.muse.data.SettingsRepository::class.java,
                )
            }.getOrNull()
            val policy = settings?.notificationPolicyFlow?.first() ?: "when_unfocused"
            MuseNotificationManager(app).notifyChatCompletedWithPolicy(
                policy = policy,
                sessionTitle = "Tasker gatillo",
                preview = reply,
                target = if (sessionId != null) MuseNotificationTarget.Session(sessionId) else MuseNotificationTarget.Home,
            )
        }.onError { msg, _ -> Logger.w(TAG, "Tasker Notificación fallida: $msg") }
    }

    companion object {
        private const val TAG = "TaskerTriggerReceiver"

        /** Tasker Acción explícita que se debe enviar. */
        const val ACTION_TASKER_TRIGGER = "io.zer0.muse.action.TASKER_TRIGGER"

        /** Tasker La clave adicional de la palabra de aviso entrante. */
        const val EXTRA_PROMPT = "prompt"

        /** Tasker Título de sesión privada activada (reutilización del mismo nombre). */
        const val TASKER_SESSION_TITLE = "Tasker gatillo"

        private const val LLM_TIMEOUT_MS = 120_000L

        /**
         * AUD-20261011-TASKER-ABUSE: Última hora de lanzamiento(Punto de referencia de limitación de frecuencia en el proceso).
         *
         * Receiver Cada onReceive puede ser una nueva instancia(manifest registro estático),
         * Por lo tanto, el estado límite de frecuencia se coloca en companion;El calibre aceptable se restablece después de reiniciar el proceso.
         * (Con el interruptor de persistencia desactivado de forma predeterminada,no constituye un bypass).
         */
        @Volatile
        private var lastAcceptedAt: Long? = null
    }
}
