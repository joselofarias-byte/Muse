package io.zer0.muse.ui.chat
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.chat.PendingToolCallStore
import io.zer0.muse.tools.ToolApprovalState
import io.zer0.muse.tools.captureLargeToolOutput
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.ToolCallRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v2.x Refactor S5: orquestación de llamadas a herramientas que se restaurarán extraídas de ChatViewModel.
 *
 * Ubicación original:ChatViewModel.resumePendingToolCalls.
 *
 * Propiedades de migración:**extracción pura**——Mueve el código tal como está.,No hay cambios en ninguna lógica./orden/Semántica de excepción.
 * ChatViewModel Mantenga la llamada del delegado en su posición original.
 */
@Suppress(
    "LongParameterList",
    "LongMethod",
    "ComplexMethod",
    "TooGenericExceptionCaught",
    "ReturnCount",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "UnusedPrivateMember", "LoopWithTooManyJumpStatements", "ComplexCondition",
)
internal class ChatToolResumeCoordinator(
    private val host: ChatToolApprovalHostBridge,
) {

    /** Constantes migradas desde ChatViewModel(Referencia directa al enlace de recuperación original.). */
    private companion object {
        const val TOOL_TIMEOUT_MS = 120_000L
    }
    fun resumePendingToolCalls(chatId: String) {
        // Evite conflictos con compilaciones de transmisión en curso
        if (host.stateStore.state.value.isStreaming) {
            host.addError(ChatErrorType.UNKNOWN, host.appContext.getString(R.string.err_chat_resume_busy))
            return
        }
        host.coroutineScope.launch {
            val pendings =
                resultOf { PendingToolCallStore.getForChat(chatId) }
                    .onError { msg, t ->
                        Logger.e("ChatVM", "resumePendingToolCalls getForChat falló: $msg", t)
                        host.addError(
                            ChatErrorType.UNKNOWN,
                            host.appContext.getString(R.string.err_chat_resume_read_failed, t?.message ?: msg),
                        )
                    }.getOrNull() ?: emptyList()
            if (pendings.isEmpty()) {
                host.stateStore.state.update { it.copy(pendingToolCallCount = 0) }
                return@launch
            }
            // La espera de aprobación no se puede falsificar ni liberar automáticamente después de reiniciar; el usuario debe descartarlo explícitamente.
            // o por posteriores especialistas“Solicitar aprobación”El proceso recrea el contexto de aprobación de la generación actual.
            val approvalPending =
                pendings.filter {
                    it.executionState == PendingToolCallStore.APPROVAL_PENDING
                }
            if (approvalPending.isNotEmpty()) {
                host.stateStore.state.update { it.copy(pendingToolCallCount = pendings.size) }
                host.addError(
                    ChatErrorType.TOOL_ERROR,
                    "si ${approvalPending.size} Una llamada de herramienta está esperando aprobación antes de finalizar el proceso y la recuperación automática ha sido bloqueada; descarte y reinicie la solicitud.",
                    true,
                )
                Logger.w(
                    "ChatVM",
                    "Denegar la herramienta pendiente de aprobación de recuperación automática: count=${approvalPending.size}, sessionId=$chatId",
                )
                return@launch
            }
            // Cargar la lista de habilidades habilitadas,construir id → SkillEntity mapeo(Consistente con la lógica dentro de launchStream)
            // v1.0.47 P3: Cobertura de habilidades a nivel de sesión — prioridad session.skillIdsJson(no"[]"y no vacio),
            // De lo contrario, recurra a assistant.skillIdsJson(Comportamiento predeterminado sin cambios)
            val sessionSkillIdsJson =
                host.stateStore.state.value.sessions
                    .firstOrNull { it.id == chatId }?.skillIdsJson
            val effectiveSkillIdsJson =
                if (!sessionSkillIdsJson.isNullOrEmpty() && sessionSkillIdsJson != "[]") {
                    sessionSkillIdsJson
                } else {
                    host.stateStore.state.value.currentAssistant?.skillIdsJson
                }
            val enabledSkillIds =
                effectiveSkillIdsJson?.let { json ->
                    runCatching { host.idListJson.decodeFromString<List<String>>(json) }.getOrNull()
                }
            // Reparación de auditoría (A-04/A-05/A-06): con ChatStreamCoordinator.resolveToolsAndModel
            // mismo conjunto de filtros — La herramienta local tiene el mismo nombre que habilidad y channel_* No se puede ingresar la habilidad de chat grupal skillMap,
            // La definición y ejecución están unificadas e implementadas localmente.,La sesión principal no puede pretender ser un agente para hablar en el chat grupal.
            val localToolNames = host.toolRegistry.listTools().map { it.name }.toSet()
            val skillMap =
                resultOf { host.skillRepository.listEnabledByIds(enabledSkillIds) }
                    .getOrNull()
                    ?.filterNot { it.id in localToolNames || it.id.startsWith("channel_") }
                    ?.associateBy { it.id } ?: emptyMap()

            // v1.0.4 (P0): entrar"Esperando el primero token"Etapa + Establecer texto de progreso de recuperación de herramientas,
            // Hacer que ThinkingBubble aparezca durante la ejecución de la herramienta"ejecutando web_search (1/3)…"
            // (Resulta que esta etapa isStreaming=false,ThinkingBubble no mostrar,El usuario ve en blanco)
            host.stateStore.state.update {
                it.copy(
                    isStreaming = true,
                    isWaitingFirstToken = true,
                    toolProgressMessage = host.appContext.getString(R.string.tool_resume_starting),
                    errors = emptyList(),
                )
            }

            // Ejecutar herramientas pendientes una por una,Construir mensaje HERRAMIENTA
            val now = System.currentTimeMillis()
            for ((stepIndex, pending) in pendings.withIndex()) {
                // Actualizar el texto de progreso en cada paso.(skill El onProgress interno será anulado aún más como"Buscando..."Esperando redacción publicitaria específica)
                host.stateStore.state.update {
                    it.copy(
                        toolProgressMessage =
                        host.appContext.getString(
                            R.string.tool_resume_step,
                            pending.toolName,
                            stepIndex + 1,
                            pendings.size,
                        ),
                    )
                }
                // P0-6: Vuelva a ejecutar la aprobación antes de la recuperación — protegerse contra"Después de guardar y antes de escribir el estado de aprobación"Las llamadas de alto riesgo que fueron eliminadas se ejecutaron sin aprobación.
                // Cuando la sentencia esté Pendiente, se volverá a abrir la tarjeta de aprobación para esperar la decisión del usuario.;Deseche el registro y omítalo cuando se considere denegado/respondido.
                val recheck = host.recheckApprovalForResume(chatId, pending)
                if (recheck is ToolApprovalState.Denied) {
                    resultOf { PendingToolCallStore.remove(pending.toolCallId) }
                        .onError { msg, t -> Logger.w("ChatVM", "No se pudieron descartar las herramientas rechazadas en la restauración: $msg", t) }
                    Logger.w("ChatVM", "Herramienta rechazada y descartada al restaurar: tool=${pending.toolName}, sessionId=$chatId")
                    continue
                }
                if (recheck is ToolApprovalState.Answered) {
                    resultOf { PendingToolCallStore.remove(pending.toolCallId) }
                        .onError { msg, t -> Logger.w("ChatVM", "No se pudieron descartar las herramientas respondidas durante la recuperación: $msg", t) }
                    Logger.w("ChatVM", "La herramienta recibe una respuesta personalizada al restaurar,descartar: tool=${pending.toolName}, sessionId=$chatId")
                    continue
                }
                val toolResult =
                    resultOf {
                        withTimeoutOrNull(TOOL_TIMEOUT_MS) {
                            val skill = skillMap[pending.toolName]
                            if (skill != null) {
                                // v1.0.4 (P0): Pasar devolución de llamada de progreso,SkillExecutor Al llamar a web_search, etc.
                                // Las herramientas que consumen mucho tiempo serán devueltas antes"Buscando..."Esperar texto localizado,Anular el valor predeterminado"ejecutando xxx"
                                host.skillExecutor.execute(
                                    skill = skill,
                                    argumentsJson = pending.arguments,
                                    onProgress = { msg ->
                                        host.stateStore.state.update { state -> state.copy(toolProgressMessage = msg) }
                                    },
                                    sessionId = chatId,
                                )
                            } else {
                                withContext(Dispatchers.IO) {
                                    host.routeGuard.executeFromJson(pending.toolName, pending.arguments)
                                }
                            }
                        }
                    }.getOrNull() ?: host.appContext.getString(
                        R.string.err_chat_tool_timeout,
                        pending.toolName,
                        (TOOL_TIMEOUT_MS / 1000).toInt(),
                    )
                val finalResult =
                    captureLargeToolOutput(
                        context = host.appContext,
                        filePrefix = "resumed_${pending.toolCallId}",
                        output = toolResult,
                    )
                // Construir mensaje HERRAMIENTA:mantener original toolCallId,Que LLM pueda corresponder al emitido anteriormente. tool_calls
                val toolMsg =
                    UIMessage(
                        role = MessageRole.TOOL,
                        content = finalResult,
                        toolCallId = pending.toolCallId,
                    )
                // Agregue a través del descriptor de acceso CAS para evitar que las actualizaciones de transmisión simultáneas sobrescriban los mensajes de HERRAMIENTA con instantáneas antiguas.
                host.stateAccessor.updateMessages { messages -> messages + toolMsg }
                // persistir en DB(para que LLM pueda seguir viendo los resultados de la herramienta la próxima vez que se inicie)
                resultOf { host.sessionRepository.upsertMessage(chatId, toolMsg) }
                    .onError { msg, t ->
                        Logger.e("ChatVM", "resumePendingToolCalls upsertMessage falló: $msg", t)
                        host.addError(
                            ChatErrorType.UNKNOWN,
                            host.appContext.getString(R.string.err_chat_tool_result_save_failed, t?.message ?: msg),
                        )
                    }
                // Sincronizar registros con toolCallHistory(InputBar Pantalla dinámica de cápsula)
                val isSuccess = host.isToolResultSuccess(finalResult)
                host.stateStore.state.update {
                    it.copy(
                        toolCallHistory =
                        it.toolCallHistory +
                            ToolCallRecord(
                                toolName = pending.toolName,
                                arguments = pending.arguments,
                                result = finalResult,
                                isSuccess = isSuccess,
                                timestamp = now,
                            ),
                    )
                }
                // Quitar de la tienda pendiente(Ejecución completada)
                resultOf { PendingToolCallStore.remove(pending.toolCallId) }
                    .onError { msg, t -> Logger.w("ChatVM", "resumePendingToolCalls remove falló: $msg", t) }
            }

            // Borrar recuento pendiente(Banner ocultar)+ Borrar texto de progreso de recuperación de herramienta
            // (ThinkingBubble volverá al valor predeterminado"Pensando",Hasta que llegue el primer token de launchStream)
            host.stateStore.state.update {
                it.copy(
                    pendingToolCallCount = 0,
                    toolProgressMessage = null,
                )
            }

            // Agregar mensaje de marcador de posición de ASISTENTE vacío,Active launchStream para permitir que LLM continúe respondiendo según los resultados de la herramienta.
            val assistantMsg = UIMessage(role = MessageRole.ASSISTANT, content = "")
            host.stateAccessor.updateMessages { messages -> messages + assistantMsg }
            host.stateStore.state.update {
                it.copy(
                    isStreaming = true,
                    // v1.0.3: También entra la transferencia de currículum de punto de interrupción"Esperando el primero token"escenario
                    isWaitingFirstToken = true,
                    errors = emptyList(),
                )
            }
            host.launchStream(assistantMsg.id, chatId)
        }
    }
}
