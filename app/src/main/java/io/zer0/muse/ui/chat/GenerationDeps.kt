package io.zer0.muse.ui.chat

import android.content.Context
import io.zer0.ai.core.UIMessage
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.audit.AuditLogger
import io.zer0.muse.data.chat.rewrite.ConversationService
import io.zer0.muse.data.milestone.MilestoneChecker
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.mcp.McpRegistry
import io.zer0.muse.rag.RagService
import io.zer0.muse.schedule.UserActivityProfile
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.transformer.SystemPromptAssembler
import io.zer0.muse.transformer.TransformerPipeline
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.SessionMemoryCache
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/**
 * v1.x: generar/Envíe el paquete de dependencia de la canalización.
 *
 * Consolide los servicios y el estado compartido compartido por send/enqueueSend/launchStream y sus ayudantes en un único parámetro de constructor.,
 * Evite la explosión de parámetros de construcción de ChatGenerationController.ChatViewModel Todavía es necesario proporcionar algunas devoluciones de llamadas de métodos.
 * (addError/generateImage/launchStream),Porque son implementaciones privadas dentro de fachada.,Utilice devoluciones de llamada en lugar de dependencias inversas.
 */
@Suppress("LongParameterList")
internal class GenerationDeps(
    val accessor: ChatStateAccessor,
    val stateStore: ChatStateStore,
    val generationState: ChatGenerationState,
    val settings: SettingsRepository,
    val sessionRepository: SessionRepository,
    val appContext: Context,
    val activityProfile: UserActivityProfile,
    val auditLogger: AuditLogger,
    val idListJson: Json,
    val messageController: ChatMessageController,
    val addError: (ChatErrorType, String, Boolean) -> Unit,
    val generateImage: (prompt: String, sessionId: String) -> Unit,
    val sessionMemoryCache: SessionMemoryCache,
    val systemPromptCache: SystemPromptCache,
    val toolRegistry: ToolRegistry,
    val systemPromptAssembler: SystemPromptAssembler,
    val assistantRepository: AssistantRepository,
    val ragService: RagService,
    val conversationService: ConversationService,
    val streamCoordinator: ChatStreamCoordinator,
    val mcpRegistry: McpRegistry?,
    val milestoneChecker: MilestoneChecker?,
    val runToolLoop: suspend (StreamRunState) -> Boolean,
    val persistInterruptedAssistant: suspend (
        sessionId: String,
        partialMsg: UIMessage?,
        expectedAssistantId: Uuid?,
        durationMs: Long?,
    ) -> Unit,
    val classifyErrorType: (message: String, throwable: Throwable?) -> ChatErrorType,
    val transformerPipeline: TransformerPipeline,
    val memoryTicker: MemoryTicker,
    val refreshContextInfo: suspend () -> Unit,
    val triggerAutoCompress: suspend (sessionId: String) -> Unit,
    /** Enrutamiento automático de tareas;Devuelve verdadero para indicar que la delegación está completa,En esta ronda ya no se iniciará la generación de modelos ordinarios. */
    val maybeAutoRoute: suspend (text: String, assistantMessageId: Uuid, sessionId: String) -> Boolean,
    /**
     * v2.6.9: Omitir notificación cuando el usuario habla (bucle cerrado de retroalimentación de mensaje activo).
     *
     * como una devolución de llamada en lugar de depender directamente de ProactiveScheduler: ese tipo de dependencia haría ChatViewModel
     * La referencia mutua con el programador también filtrará los detalles de almacenamiento de los mensajes activos a la capa de sesión.
     * La implementación predeterminada está vacía y el comportamiento cuando no está ensamblado es consistente con la versión anterior.
     *
     * Sin colgar: la persona que llama envía una ruta activa y no puede bloquearla para registrar un comentario.
     * El implementador decide dónde iniciar la rutina.
     */
    val onUserSpoke: (sessionId: String) -> Unit = {},
)
