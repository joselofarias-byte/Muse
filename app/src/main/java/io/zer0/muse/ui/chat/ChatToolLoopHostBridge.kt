package io.zer0.muse.ui.chat

import io.zer0.ai.ChatService
import io.zer0.ai.core.Model
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.chat.rewrite.ConversationEventDraft
import io.zer0.muse.data.chat.rewrite.ConversationService
import io.zer0.muse.data.chat.rewrite.MessageCommit
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.session.ConversationSessionManager
import io.zer0.muse.tools.ToolApprovalState
import io.zer0.muse.transformer.ContextCompressTransformer
import io.zer0.muse.ui.ChatErrorType
import kotlin.uuid.Uuid

/**
 * v2.x Refactorizar S1: [ChatToolLoopRunner] Acceda a la interfaz de puente estrecho de ChatViewModel.
 *
 * razón de ser:Cuerpo de bucle de herramienta(1392 OK)Después de mudarse como un todo,Aún necesito acceder a una pequeña cantidad del ViewModel original.
 * Miembros y métodos. Utilice interfaces explícitas para declarar centralmente estos accesos,En lugar de pasar el ViewModel como un todo ——
 * La superficie de dependencia es visible, mensurable y reemplazable.
 *
 * Propiedades de migración:Extracción pura. Todos los métodos de esta interfaz son para miembros originales de VM./metodológico**Reenviar directamente**,
 * No cambia ninguna semántica.ChatViewModel Implementar esta interfaz en forma de anulación,No se escribe ningún código de reenvío.
 */
@Suppress("TooManyFunctions", "LongParameterList", "ComplexInterface")
internal interface ChatToolLoopHostBridge {

    // ── Estado ───────────────────────────────────────────────────────────

    /** Originales `_state` / `_messages` titular. */
    val stateStore: ChatStateStore

    /** Originales `generationState`(toolAssistantId / activeToolSessionId / toolMediaMessages). */
    val generationState: io.zer0.muse.ui.chat.ChatGenerationState

    // ── depender de(campo constructor original,VM Anulación lateral expuesta)────────────────────────

    val appContext: android.content.Context
    val settings: SettingsRepository
    val sessionRepository: SessionRepository
    val chatService: ChatService

    /**
     * Compresor de contexto.
     *
     * Solo se usa para "recuperación de desbordamiento de contexto": cuando el proveedor considera que la solicitud ha excedido la ventana del modelo, presionará una ronda de historial y luego la reenviará.
     * runner Solo se llama a su transformación, sin cambiar nada de su comportamiento o semántica de umbral.
     */
    val contextCompressTransformer: ContextCompressTransformer
    val sessionManager: ConversationSessionManager
    val notificationManager: MuseNotificationManager
    val conversationService: ConversationService
    val messageCommit: MessageCommit
    val artifactRepository: io.zer0.muse.data.artifact.ArtifactRepository
    val chatGenerationManager: io.zer0.muse.schedule.ChatGenerationManager
    val sessionPermissionStore: io.zer0.muse.tools.SessionPermissionStore
    val toolOrchestrator: io.zer0.muse.tools.ToolOrchestrator

    // ── Reenvío del método de VM original(La firma corresponde al lado de la VM uno a uno.)──────────────────────────

    fun addError(type: ChatErrorType, message: String, isRecoverable: Boolean = true)
    fun classifyErrorType(message: String, throwable: Throwable? = null): ChatErrorType
    fun clearStreamingStateIfLatest(state: StreamRunState, finalPhase: io.zer0.muse.ui.ChatStreamPhase? = null): Boolean
    fun collectStickyToolNames(history: List<UIMessage>): Set<String>
    fun currentSessionIdForApproval(): String?
    fun nextToolGenerationToken(): Long
    fun streamFlushIntervalMs(builderLength: Int): Long
    fun canUseToolModelForRound(history: List<UIMessage>, toolModel: Model): Boolean
    fun resumeOverlapToDrop(duplicateRemaining: String, delta: String): Int
    fun shouldReplaceOnResumeRewrite(duplicateTotal: Int, consumedChars: Int, minOverlap: Int = 6): Boolean
    fun shouldReplaceOnResumeSupersede(original: String?, attemptText: String?, currentContent: String): Boolean
    suspend fun persistCheckpointAndReleaseOutbox(
        state: StreamRunState,
        sessionId: String,
        userMessageId: String,
        assistantMessageId: String,
        content: String,
        createdAt: Long,
    )
    suspend fun persistCurrentAssistant(sessionId: String, assistantId: Uuid, msg: UIMessage? = null)
    suspend fun persistToolMessageMedia(sessionId: String?, assistantId: Uuid)
    suspend fun recordConversationShadow(event: ConversationEventDraft)
    suspend fun requestToolApprovalForSession(
        sessionId: String,
        toolName: String,
        toolCallId: String,
        argsPreview: String,
        args: Map<String, Any?>,
    ): ToolApprovalState
    fun updateAssistant(
        id: Uuid,
        content: String,
        reasoning: String? = null,
        imageBase64List: List<String>? = null,
        imageUrls: List<String>? = null,
        isStreaming: Boolean = false,
    )
    suspend fun updateAssistantWithVisualTransform(
        assistantId: Uuid,
        content: String,
        reasoning: String? = null,
        isStreaming: Boolean = false,
    )
    suspend fun updateContextTokenCount()
    suspend fun waitForNetworkRecovery(timeoutMs: Long): Boolean

    /** Originales `taskCardCoordinator`. */
    val generationController: ChatGenerationController
    val coroutineScope: kotlinx.coroutines.CoroutineScope
    val accessor: ChatStateAccessor
    val taskCardCoordinator: ChatTaskCardCoordinator

    /** Originales `messageController`. */
    val messageController: ChatMessageController
}
