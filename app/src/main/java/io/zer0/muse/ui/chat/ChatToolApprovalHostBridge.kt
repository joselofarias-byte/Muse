package io.zer0.muse.ui.chat
import io.zer0.muse.chat.PendingToolCallStore
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.session.ConversationSessionManager
import io.zer0.muse.tools.SessionPermissionStore
import io.zer0.muse.tools.ToolApprovalState
import io.zer0.muse.ui.PendingToolApproval
import kotlinx.serialization.json.Json

/**
 * v2.x Refactorizar S4/S5: [ChatToolApprovalCoordinator] con [ChatToolResumeCoordinator]
 * Acceda a la interfaz de puente estrecho de ChatViewModel(Aprobación y restauración del mismo dominio.,compartir un puente).
 *
 * Propiedades de migración:Extracción pura.VM Implementado con objetos anónimos.,Todo por**Reenviar directamente**,Sin lógica.
 */
internal interface ChatToolApprovalHostBridge {

    // ── depender de ───────────────────────────────────────────────────────────

    val stateStore: ChatStateStore

    /** Shared CAS accessor for message-list updates that may race streaming writes. */
    val stateAccessor: ChatStateAccessor
    val appContext: android.content.Context
    val settings: SettingsRepository
    val sessionPermissionStore: SessionPermissionStore
    val toolConfigStore: io.zer0.muse.tools.ToolConfigStore?
    val toolRegistry: io.zer0.muse.tools.ToolRegistry
    val notificationManager: MuseNotificationManager
    val sessionManager: ConversationSessionManager
    val skillRepository: SkillRepository

    /** El analizador idListJson para la VM original. */
    val idListJson: Json
    val coroutineScope: kotlinx.coroutines.CoroutineScope

    // ── Contenedor de estado de aprobación(Variabilidad de la VM original. Map,El estado de propiedad no cambia esta vez.)──────────────

    val toolApprovalResults: MutableMap<String, kotlinx.coroutines.CompletableDeferred<ToolApprovalState>>
    val toolApprovalSessions: MutableMap<String, String>
    val pendingToolApprovalRecords: MutableMap<String, PendingToolApproval>

    /** Originales `approvalTimeoutPaused`(leer). */
    val approvalTimeoutPaused: Boolean

    /** ¿Está la aplicación en primer plano?(ProcessLifecycleOwner El controlador ON_START/ON_STOP). */
    val appInForeground: Boolean

    // ── reenvío de métodos ───────────────────────────────────────────────────────

    fun addError(type: io.zer0.muse.ui.ChatErrorType, message: String, isRecoverable: Boolean = true)
    val skillExecutor: io.zer0.muse.tools.SkillExecutor
    val routeGuard: io.zer0.muse.tools.ToolRouteExecutionGuard
    suspend fun recheckApprovalForResume(chatId: String, pending: PendingToolCallStore.PendingToolCall): ToolApprovalState?
    val sessionRepository: io.zer0.muse.data.session.SessionRepository
    fun isToolResultSuccess(result: String): Boolean
    fun launchStream(assistantId: kotlin.uuid.Uuid, sessionId: String, isNewBranch: Boolean = false)
    fun currentSessionIdForApproval(): String?
}
