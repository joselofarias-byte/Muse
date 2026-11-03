package io.zer0.muse.ui.chat

import android.content.Context
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.chat.ConversationTreeSnapshotStore
import io.zer0.muse.schedule.ChatGenerationManager
import io.zer0.muse.session.ConversationSessionManager
import io.zer0.muse.tools.SessionPermissionStore

/**
 * v1.x: orquestación de sesiones(createNewSession/switchSession/restartContext espera)paquete de dependencia.
 *
 * Cierre el estado compartido requerido para el cambio del ciclo de vida de la sesión/Servicio + devolución de llamada de responsabilidad cruzada,suministro ChatSessionController
 * Realizar la creación de sesiones sin dependencia inversa de ChatViewModel/cambiar/Reanudar.ChatViewModel privado
 * de stopTts/disposeAsr/notifySessionEnd/currentSessionIdForApproval Inyectado mediante devolución de llamada.
 */
@Suppress("LongParameterList")
internal class SessionDeps(
    val stateStore: ChatStateStore,
    val settings: SettingsRepository,
    val assistantRepository: AssistantRepository,
    val sessionPermissionStore: SessionPermissionStore,
    val sessionManager: ConversationSessionManager,
    val appContext: Context,
    val onStopTts: () -> Unit,
    val onDisposeAsr: () -> Unit,
    val onNotifySessionEnd: () -> Unit,
    val currentSessionIdForApproval: () -> String?,
    val globalActiveProviderId: () -> String?,
    val globalSelectedModelId: () -> String?,
    val onSend: () -> Unit,
    val messageController: ChatMessageController,
    val chatGenerationManager: ChatGenerationManager,
    val onClearDelegation: () -> Unit,
    val onCancelPendingApprovals: (String?) -> Unit,
    val treeSnapshotStore: ConversationTreeSnapshotStore?,
    val restorePendingApprovalsForSession: (sessionId: String) -> Unit,
    val activeProviderForSession: (sessionId: String?) -> String?,
    val selectedModelForSession: (sessionId: String?) -> String?,
    val onSessionSwitched: (sessionId: String) -> Unit,
    val requeueOutboxForSession: suspend (sessionId: String) -> Unit,
)
