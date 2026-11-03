package io.zer0.muse.ui.chat

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.Perf
import io.zer0.common.ProcessWriteGate
import io.zer0.common.Result
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.chat.InternalPromptMarkers
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantMemoryAccessPolicy
import io.zer0.muse.data.chat.rewrite.ConversationEventDraft
import io.zer0.muse.data.chat.rewrite.ConversationEventType
import io.zer0.muse.data.chat.rewrite.ConversationRebuildFlagStore
import io.zer0.muse.data.chat.rewrite.sha256
import io.zer0.muse.data.session.MessageOutboxEntity
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.notification.MuseNotificationTarget
import io.zer0.muse.schedule.ChatGenerationManager
import io.zer0.muse.schedule.ConversationEndType
import io.zer0.muse.schedule.UserActivityProfile
import io.zer0.muse.session.ConversationSessionManager
import io.zer0.muse.session.ExecutionKind
import io.zer0.muse.session.ExecutionState
import io.zer0.muse.session.SessionExecutionRegistry
import io.zer0.muse.session.TurnPhase
import io.zer0.muse.session.WarmupHistory
import io.zer0.muse.transformer.CompressionPolicy
import io.zer0.muse.transformer.ContextCompressTransformer
import io.zer0.muse.transformer.ConversationCompressor
import io.zer0.muse.transformer.TransformContext
import io.zer0.muse.transformer.UserMessageTimeContext
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.ChatStreamPhase
import io.zer0.muse.ui.ChatUiState
import io.zer0.muse.ui.buildSendText
import io.zer0.muse.ui.canContinueGeneration
import io.zer0.muse.ui.canRegenerate
import io.zer0.muse.ui.canStartGeneration
import io.zer0.muse.ui.resumeFromInterrupted
import io.zer0.muse.util.ErrorMessages
import io.zer0.muse.util.MessageTokenCache
import io.zer0.muse.util.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.uuid.Uuid

/**
 * v1.x: Generar controlador abstraído de ChatViewModel.
 *
 * Responsabilidades(Crear control del ciclo de vida + enviar productor de canalización;launchStream/El ciclo de consumo sigue):
 *  - [stop] Detener la generación de la sesión actual(nivel de aplicación+Cancelación de nivel de sesión, estado de transmisión claro, imagen de cancelación/Traducción y pendiente de aprobación).
 *  - [enqueueSend] Poner en cola de forma optimista un mensaje de usuario(Actividades/auditoría/enrutamiento/outbox/cola,Revertir en caso de falla).
 *
 * Cancelación de tareas subsidiarias de responsabilidad cruzada(fotos/Traducción),pendiente de autorización,addError/generateImage Inyectado mediante devolución de llamada,
 * No hay dependencia inversa de ChatViewModel.
 */
internal fun composeSystemPromptMessages(staticPrompt: String, dynamicPrompt: String): List<UIMessage> = listOfNotNull(
    staticPrompt.takeIf { it.isNotBlank() }?.let { UIMessage(role = MessageRole.SYSTEM, content = it) },
    dynamicPrompt.takeIf { it.isNotBlank() }?.let { UIMessage(role = MessageRole.SYSTEM, content = it) },
)

/** Agent mode owns an independent session; task-session state must never win this lookup. */
internal fun effectiveGenerationSessionId(state: ChatUiState): String? =
    if (state.isAgentMode) state.agentSessionId else state.currentSessionId

/** Proporción de alertas de contexto antes del envío (el historial se trunca agresivamente cuando la ocupación alcanza esta proporción de la ventana). */
internal const val PRESEND_TOKEN_WARNING_RATIO = 0.9f

/**
 * La cantidad de mensajes finales retenidos después de la compresión de autorrescate antes del envío.
 *
 * que la compresión normal ([CompressionPolicy.KEEP_RECENT_DEFAULT]) es más corto, porque entrar aquí es“Ya superé la ventana”.
 * Con la recuperación en ejecución (ChatToolLoopRunner.OVERFLOW_RECOVERY_KEEP_RECENT) toma el mismo valor:
 * par de dos caminos“¿Hasta qué punto”Cada uno no puede tener su propio calibre.
 */
private const val PRESEND_OVERFLOW_KEEP_RECENT = 4

/**
 * Enviar proporción de límite máximo de carga útil — Negarse a enviar cuando lo alcancen (comprimido/El límite aún se excede después del truncamiento).
 *
 * v2.5.7 Reparación: Original 0,98 Demasiado agresivo —— Las estimaciones de BPE del cliente son aproximadas (≠ Recuento real del lado del servidor),
 * 0.98 muy fácil de poner“En realidad no más”La solicitud fue bloqueada por error (prueba de usuario)“La compresión es inútil”).
 * En su lugar, deje un margen para el error de estimación: Estimar > Deténgase en 1,35 veces la ventana real;
 * El rango se entrega al proveedor para una verificación verdadera (se devolverá 400 si es verdadero y excede el límite, que se maneja mediante mapeo de errores).
 */
internal const val CONTEXT_HARD_LIMIT_RATIO = 1.35f

/**
 * Cree los resultados de la instantánea del mensaje del sistema estático.
 *
 * Cadenas vacías y excepciones de compilación**Ninguno está disponible**: Al continuar generando con palabras vacías, el modelo perderá personalidad, descripción de la herramienta,
 * limitaciones de memoria y formato, pero todavía produce un“parece normal”Responder —— Es más engañoso para los usuarios que informar un error directamente.
 */
internal sealed interface StaticPromptOutcome {
    /** Instantánea disponible. */
    data class Ready(val prompt: String) : StaticPromptOutcome

    /** Error de compilación (con motivo diagnosticable). */
    data class Failed(val reason: String) : StaticPromptOutcome

    /** La compilación se realizó correctamente pero el resultado está vacío. */
    data object Empty : StaticPromptOutcome
}

/** Determine si el resultado de la construcción de palabras del mensaje estático está disponible (fuente única de verdad, conveniente para pruebas únicas). */
internal fun staticPromptBuildOutcome(result: Result<String>): StaticPromptOutcome = when {
    result is Result.Error -> StaticPromptOutcome.Failed(result.message)
    result.getOrNull().isNullOrBlank() -> StaticPromptOutcome.Empty
    else -> StaticPromptOutcome.Ready(result.getOrNull().orEmpty())
}

/**
 * Si se alcanzó la línea de advertencia de contexto antes del envío.
 *
 * [windowTokens] debe ser**Modelo real de esta ronda.**ventana (ver [effectiveContextWindowTokens]):
 * El cálculo de acuerdo con el modelo global pasará las solicitudes del modelo de ventana pequeña (por encima del límite 400) y también pasará las solicitudes del modelo de ventana grande.
 * Compresión innecesaria (contexto perdido).
 */
internal fun isNearContextLimit(currentTokens: Int, windowTokens: Int): Boolean = windowTokens > 0 && currentTokens > 0 &&
    currentTokens.toFloat() / windowTokens >= PRESEND_TOKEN_WARNING_RATIO

/** Si la carga útil de envío excede la línea de intercepción estricta (ventana × [CONTEXT_HARD_LIMIT_RATIO]). */
internal fun exceedsHardContextLimit(payloadTokens: Int, windowTokens: Int): Boolean =
    windowTokens > 0 && payloadTokens >= (windowTokens * CONTEXT_HARD_LIMIT_RATIO).toInt()

/**
 * Auto-rescate por desbordamiento antes de enviar: presione primero una ronda del historial, luego evalúe nuevamente después de presionar.
 *
 * El bloqueo no se elimina, solo se agrega un autorrescate delante: el bloqueo es la red de seguridad final (para evitar las inevitables llamadas 400),
 * y muchos“hiperventana”De hecho, se puede enviar comprimiéndolo.
 *
 * Presione solo una vez: esta función solo se llama una vez y se ajusta solo una vez [compress]; no se puede presionar (null / No acortado)
 * Simplemente ríndete sin reevaluarlo por segunda vez o intentarlo de nuevo. —— La represión repetida sólo suprimirá cada vez más la verdadera historia.
 *
 * @param history Historial a enviar (recortado por número de artículos/presupuesto)
 * @param compress acción de compresión;null Indica que no se puede crear espacio (muy pocos mensajes/ningún beneficio neto/la compresión no está disponible)
 * @param stillOverflows Utilice el historial comprimido para volver a calcular la carga útil y ver si aún excede la ventana.
 * @return Cuando se completa la compresión y ya no se excede la ventana, se devolverá el historial comprimido;null Indica que el autorrescate falló y la persona que llama sigue interceptando e informa el error original.
 */
internal suspend fun recoverPreSendOverflow(
    history: List<UIMessage>,
    compress: suspend () -> List<UIMessage>?,
    stillOverflows: suspend (List<UIMessage>) -> Boolean,
): List<UIMessage>? {
    val compressed = compress() ?: return null
    // No es corto: la revalorización tendrá el mismo resultado, no es necesario volver a calcularla. BPE
    if (compressed.size >= history.size) return null
    return compressed.takeIf { !stillOverflows(it) }
}

internal fun shouldRollbackOptimisticSend(state: ChatUiState, requestSessionId: String): Boolean =
    effectiveGenerationSessionId(state) == requestSessionId

@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
internal class ChatGenerationController(
    private val deps: GenerationDeps,
    private val accessor: ChatStateAccessor,
    private val chatGenerationManager: ChatGenerationManager,
    private val sessionManager: ConversationSessionManager,
    private val settings: SettingsRepository,
    private val notificationManager: MuseNotificationManager,
    private val onCancelAncillaryJobs: () -> Unit,
    private val onCancelPendingApprovals: (String?) -> Unit,
    private val executionRegistry: SessionExecutionRegistry? = null,
) {
    private val shadowEventSequencer = ConversationShadowEventSequencer()

    /**
     * Compresor para autorrescate de desbordamiento antes del envío.
     *
     * Utilice el contenedor global para analizar según demanda, sin cambiar la capacidad de omisión. [GenerationDeps] Toda la cadena de construcción de
     * (La misma opción que ChatToolLoopRunner y UsageRecorder). Devuelve nulo si no está disponible:
     * El autorrescate no se produce y el comportamiento es exactamente el mismo que antes de agregar este elemento. —— Informar de un error si se detiene.
     *
     * NOTA: Esto es**segundo** [ContextCompressTransformer] ejemplo, el que tiene ChatViewModel
     * Comparte el panorama general `CompressionSummaryStore`. Esta ruta no es legible `lastCoveredBoundaryId`, ni escribir
     * checkpoint, por lo que las dos instancias actuales se comportan de manera equivalente y no tienen bifurcación de estado. Si desea marcar este camino en el futuro (escriba
     * ContextCheckpoint), primero debes regresar y evaluar esta diferencia de instancia —— en ese momento“Dos instancias son intercambiables.”ya no es cierto.
     */
    private val overflowCompressor: ContextCompressTransformer? by lazy {
        runCatching {
            val koin = org.koin.core.context.GlobalContext.get()
            ContextCompressTransformer(
                chatService = koin.get<ChatService>(),
                compressor = koin.get<ConversationCompressor>(),
            )
        }.onFailure { Logger.w("ChatVM", "overflow compressor unavailable: ${it.message}") }
            .getOrNull()
    }

    /**
     * Compresión de autorrescate antes del envío: utilice un compresor ya preparado para comprimir una ronda del historial y devuelva nulo si no está comprimido.
     *
     * Parámetros y el que está en funcionamiento (ChatToolLoopRunner.compressForOverflowRecovery) toma el mismo conjunto:
     * Aplique un umbral de activación, una cola de retención más corta y permita el truncamiento de marcas visibles cuando falla el resumen. El comportamiento del compresor en sí es similar al
     * La semántica del umbral no cambia, solo se lee su valor de retorno.
     *
     * @return historia comprimida;null Indica que no se puede crear espacio (muy pocos mensajes/sin rango de compresión/sin ganancia neta/compresor no disponible).
     */
    private suspend fun compressHistoryForOverflow(history: List<UIMessage>, sessionId: String): List<UIMessage>? {
        val transformer = overflowCompressor ?: return null
        if (history.size < 2) return null
        // Intervalo compresible = SYSTEM Después del prefijo y antes del último mensaje keepRecent, la cola no puede ocupar todos los mensajes.
        val keepRecent = minOf(PRESEND_OVERFLOW_KEEP_RECENT, history.size - 1).coerceAtLeast(1)
        val context =
            TransformContext(
                sessionId = sessionId,
                modelId = accessor.snapshot.currentAssistant?.modelId,
                extras =
                mapOf(
                    "compress_enabled" to true,
                    "compress_threshold" to CompressionPolicy.FORCE_TRIGGER_THRESHOLD,
                    "compress_keep_recent" to keepRecent,
                    "compress_force_fallback" to true,
                    "compress_char_budget" to
                        WarmupHistory.compressTokenBudgetFor(
                            contextMaxTokens = accessor.snapshot.contextMaxTokens,
                        ),
                ),
            )
        val compressed =
            resultOf { transformer.transform(history, context) }
                .onError { message, t -> Logger.w("ChatVM", "pre-send overflow compress failed: $message", t) }
                .getOrNull() ?: return null
        return compressed.takeIf { it.size < history.size }
    }

    // B-1: Conjunto de supresión de escritura para eliminación de sesión — Registrarse al eliminar mensajes de conversación,Hacer que todas las sesiones de esta sesión estén disponibles para transmisión
    // (persistCurrentAssistant / persistInterruptedAssistant / Refinamiento upsertMessage)
    // saltar,Evitar la eliminación después"resurrección". Nuevo lanzamiento de transmisiónTransmisión borrada al inicio,Permite la regeneración después de la eliminación.
    private val sessionWritesSuppressed: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** La generación de chat no debe continuar realizando nuevas escrituras mientras una restauración de copia de seguridad reemplaza el archivo de base de datos. */
    private fun restoreBlocksChatWrites(operation: String): Boolean {
        if (!ProcessWriteGate.restoring) return false
        Logger.w("ChatVM", "Restauración de copia de seguridad en curso, se omitió la escritura del chat: $operation")
        return true
    }

    /** B-1: Marcar esta sesión como eliminada — Los pedidos de transmisión en tránsito posteriores se omitirán en consecuencia. */
    fun suppressSessionWrites(sessionId: String?) {
        if (sessionId != null) sessionWritesSuppressed.add(sessionId)
    }

    /** B-1: ¿La sesión está en"Supresión de escritura después de la eliminación"estado. */
    fun isSessionWritesSuppressed(sessionId: String): Boolean = sessionId in sessionWritesSuppressed

    /** B-1: Detenga toda generación de mensajes de conversación durante el vuelo al eliminarlos(Nivel de aplicación + cancelación de nivel de sesión). */
    fun stopGenerationForSession(sessionId: String?) {
        sessionId?.let { executionRegistry?.requestCancelForSession(it, "delete_message") }
        chatGenerationManager.stop(sessionId)
        sessionId?.let { sessionManager.cancelGeneration(it) }
    }

    /** punto de usuario"detenerse". */
    fun stop() {
        // Detener únicamente la generación de chats individuales,No afecta el chat grupal
        val sid = effectiveGenerationSessionId(accessor.snapshot)
        sid?.let { executionRegistry?.requestCancelForSession(it, "user_stop") }
        chatGenerationManager.stop(sid)
        // Indicador de cancelación del tiempo de ejecución del registro,distinguir"usuario detenido"falla con excepción;Job Tiene la misma instancia que chatGenerationManager,Repetir cancelar es idempotente.
        sid?.let { sessionManager.cancelGeneration(it) }
        // Borra el foco de generación de la sesión después de que el usuario se detiene.
        accessor.coroutineScope.launch {
            if (resultOf { settings.getGeneratingSessionId() }.getOrNull() == sid) {
                resultOf { settings.saveGeneratingSessionId(null) }
                    .onError { msg, _ -> Logger.w("ChatVM", "saveGeneratingSessionId Error de limpieza: $msg") }
            }
        }
        onCancelAncillaryJobs()
        accessor.update {
            it.copy(
                isStreaming = false,
                isWaitingFirstToken = false,
                isGeneratingImage = false,
                isTranslating = false,
                translatingMessageId = null,
                pendingToolApprovals = emptyList(),
                toolProgressMessage = null,
                // v2.6.4: Finalizar planes inacabados cuando el usuario se detiene —— Convierta el paso PENDIENTE/EN_PROGRESO en CANCELADO.
                // De lo contrario, la tarjeta del plan permanecerá en estado giratorio para siempre (stop Nadie llamará a update_plan_step para avanzarlo).
                agentPlans = it.agentPlans.mapValues { (_, plan) -> plan.settleAsCancelled() },
            )
        }
        // Cancelar todas las llamadas de herramientas pendientes(Evite que la tarjeta de aprobación fantasma + la rutina requestToolApproval se cuelgue después de la detención)
        onCancelPendingApprovals(sid)
        // Notificación:Cancelar la notificación de progreso cuando el usuario se detiene
        runCatching {
            notificationManager.updateLiveProgress("", 0, false)
        }.onFailure { Logger.w("ChatVM", "No se pudo cancelar la notificación de progreso: ${it.message}") }
    }

    /** Enviar la entrada actual. texto vacío(y sin fotos)o ignorar durante la transmisión;isDrawMode Ir a generación de imágenes. */
    @Suppress("ReturnCount")
    fun send() {
        val rawText = accessor.snapshot.input.trim()
        val images = accessor.snapshot.pendingImages
        val docs = accessor.snapshot.pendingDocuments
        // Los metadatos estructurados del archivo adjunto se almacenan con el mensaje. La línea "El archivo adjunto se guardó en el espacio de trabajo" en el texto es para el modelo.
        // No es adecuado para la visualización de la interfaz: no puede ver cuántos archivos hay y no puede hacer clic en ellos.
        // Aquí solo se incluyen metadatos y no se almacenan. extractedText —— El texto se ha escrito en el contenido y al guardar otra copia se expandirá la tabla de mensajes.
        val attachments = docs.map { doc ->
            io.zer0.ai.core.AttachmentRef(
                name = doc.name,
                mimeType = doc.mimeType.ifBlank { "application/octet-stream" },
                size = doc.sizeBytes,
                sourceType = "FILE",
                workspacePath = doc.workspacePath,
            )
        }
        // v1.136 T10: Fusionar el contenido del documento que se enviará en el texto del mensaje(Texto del documento + entrada del usuario)
        var text = buildSendText(rawText, docs.map { it.content })
        val canStart =
            canStartGeneration(
                text,
                images,
                accessor.snapshot.isStreaming,
                deps.generationState.isCreatingAgentSession,
            )
        if (!canStart) {
            return
        }
        // Filtrado de seguridad de contenido de entrada(Anteriormente, SafetyPolicy estaba completamente definida pero no tenía ninguna llamada en todas las posiciones. —— Red de seguridad desconectada).
        // Deje de enviar y avise cuando se encuentre una palabra ilegal clara,La sesión no está escrita.
        val safety = io.zer0.muse.privacy.SafetyPolicy.checkInput(text)
        if (!safety.safe) {
            Logger.w("ChatVM", "Entrada bloqueada por política de seguridad: ${safety.reason}")
            deps.addError(ChatErrorType.UNKNOWN, safety.suggestion ?: "", false)
            return
        }
        // v1.68: Las respuestas a las citas deben deletrear el contenido citado en el cuerpo del mensaje.,LLM Puedes leer la cita original.
        val replyingToLatest =
            accessor.snapshot.replyingTo?.let { r ->
                deps.stateStore.messages.value.find { it.id == r.id } ?: r
            }
        val quoteText =
            accessor.snapshot.replyQuoteOverride?.takeIf { it.isNotBlank() }
                ?: replyingToLatest?.content?.takeIf { it.isNotBlank() }
        if (quoteText != null) {
            text = buildQuotedContent(quoteText, text)
        }

        // v1.28: Agent modo usando independiente agentSessionId,Creado automáticamente cuando no hay sesión
        // v1.79 (M-CV8): Utilice el indicador isCreatingAgentSession para evitar el reingreso,Evite crear dos sesiones con un doble clic rápido
        val sessionId =
            if (accessor.snapshot.isAgentMode) {
                accessor.snapshot.agentSessionId ?: run {
                    if (deps.generationState.isCreatingAgentSession) return
                    deps.generationState.isCreatingAgentSession = true
                    accessor.coroutineScope.launch {
                        try {
                            val assistantId = accessor.snapshot.currentAssistant?.id ?: "default"
                            val id = deps.sessionRepository.createAgentSession(assistantId)
                            accessor.update { it.copy(agentSessionId = id) }
                            // v1.53-A1: Cargar mensajes de sesión del Agente en páginas(La nueva sesión está vacía,Restablecer al mismo tiempo hasMoreHistory)
                            val (msgs, hasMore) = deps.messageController.loadMessagesPaged(id)
                            deps.stateStore.messages.value = msgs
                            accessor.update {
                                it.copy(hasMoreHistory = hasMore, isLoadingMore = false, lastHistoryLoadCount = 0)
                            }
                            enqueueSend(text, images, id, attachments)
                        } finally {
                            deps.generationState.isCreatingAgentSession = false
                        }
                    }
                    return
                }
            } else {
                accessor.snapshot.currentSessionId ?: return
            }

        if (accessor.snapshot.isDrawMode) {
            deps.generateImage(text, sessionId)
            return
        }
        enqueueSend(text, images, sessionId, attachments)
    }

    /** B7-04: Continuar la generación después de la interrupción de la transmisión,Reutilizar la última tira [interrumpido] Continuación del mensaje del asistente marcado. */
    @Suppress("ReturnCount")
    fun continueGeneration() {
        if (accessor.snapshot.isStreaming) return
        val st = accessor.snapshot
        val sessionId = if (st.isAgentMode) st.agentSessionId ?: return else st.currentSessionId ?: return
        val messages = deps.stateStore.messages.value
        val last = messages.lastOrNull() ?: return
        if (!canContinueGeneration(st.isStreaming, last)) return
        val content = resumeFromInterrupted(last.content)
        val resumed = last.copy(content = content)
        deps.stateStore.messages.value = deps.stateStore.messages.value.map { if (it.id == last.id) resumed else it }
        deps.messageController.rebuildConversationTree()
        accessor.update {
            it.copy(isStreaming = true, isWaitingFirstToken = true, errors = emptyList())
        }
        launchStream(last.id, sessionId, false, resumed)
    }

    /** Regenerar la última respuesta del asistente bajo la variante de usuario actual:Mantener las respuestas antiguas como variaciones.,Cree una nueva variante y solicite nuevamente. */
    @Suppress("ReturnCount")
    fun regenerateLastAssistant() {
        if (restoreBlocksChatWrites("regenerate")) {
            reportRegenerateUnavailable("backup restore in progress")
            return
        }
        val st = accessor.snapshot
        val sessionId =
            if (st.isAgentMode) {
                st.agentSessionId ?: return reportRegenerateUnavailable("agent session missing")
            } else {
                st.currentSessionId ?: return reportRegenerateUnavailable("session missing")
            }
        val tree = deps.stateStore.conversationTree.value
        if (!canRegenerate(
                isStreaming = st.isStreaming,
                hasSession = true,
                hasSelectedUserVariant = tree.selectedUserNode != null && tree.selectedUserVariant != null,
            )
        ) {
            return reportRegenerateUnavailable(
                when {
                    st.isStreaming -> "generation already running"
                    tree.selectedUserNode == null -> "selected user branch missing"
                    tree.selectedUserVariant == null -> "selected user message missing"
                    else -> "regeneration not available"
                },
            )
        }
        val update = tree.retryLastAssistant()
        val newMsg = update.newMessage ?: return reportRegenerateUnavailable("reply branch missing")
        deps.stateStore.conversationTree.value = update.tree
        deps.stateStore.messages.value = update.tree.displayMessages
        accessor.update {
            it.copy(isStreaming = true, isWaitingFirstToken = true, errors = emptyList())
        }
        deps.sessionMemoryCache.remove(sessionId)
        // Primero complete el despliegue de la nueva variante de asistente.,Reiniciar generación,Evite las condiciones de carrera de la base de datos de sucursales.
        accessor.coroutineScope.launch {
            val persisted =
                withContext(Dispatchers.IO) {
                    runCatching {
                        deps.sessionRepository.upsertMessage(sessionId, newMsg)
                        update.changedGroupId?.let { groupId ->
                            deps.sessionRepository.updateVariantCount(groupId, newMsg.variantCount)
                        }
                    }.onFailure { e -> Logger.e("ChatVM", "regenerate upsertMessage failed", e) }.isSuccess
                }
            if (persisted) {
                launchStream(newMsg.id, sessionId, true, null)
            } else {
                accessor.update { it.copy(isStreaming = false, isWaitingFirstToken = false) }
                reportRegenerateUnavailable("new reply could not be saved")
            }
        }
    }

    private fun reportRegenerateUnavailable(reason: String) {
        Logger.w("ChatVM", "regenerate ignored: $reason")
        deps.addError(
            ChatErrorType.UNKNOWN,
            deps.appContext.getString(R.string.err_chat_stream_broken),
            false,
        )
    }

    /** v5: Actualización optimista — Los mensajes de usuario se muestran inmediatamente.,No espere a que se escriba la base de datos;La puesta en cola posterior se procesa en serie mediante el bucle de consumo. */
    @Suppress("LongMethod")
    fun enqueueSend(
        text: String,
        images: List<String>,
        sessionId: String,
        attachments: List<io.zer0.ai.core.AttachmentRef> = emptyList(),
    ) {
        if (restoreBlocksChatWrites("enqueueSend")) {
            deps.addError(
                ChatErrorType.UNKNOWN,
                deps.appContext.getString(R.string.err_chat_stream_broken),
                false,
            )
            return
        }
        // v2.1: Registre las actividades de los usuarios en retratos de actividades,y actualizar el tipo de final de la conversación(Impulsar la programación de mensajes proactiva y adaptativa)
        deps.activityProfile.recordActivity()
        deps.activityProfile.setConversationEndType(
            if (UserActivityProfile.containsEndKeyword(text)) {
                ConversationEndType.USER_EXPLICIT_END
            } else {
                ConversationEndType.NATURAL_FADE
            },
        )
        // v2.6.9: Omitir notificación de programador de mensajes activos —— Si esto es en respuesta a un mensaje no solicitado,
        // Escribe de nuevo RESPONDIDO y repone el cupo. No bloquea la ruta de transmisión y no afecta su éxito o fracaso.
        deps.onUserSpoke(sessionId)
        // P2-4: Registro de auditoría — enviar mensaje
        deps.auditLogger.log(
            category = "user_action",
            action = "send_message",
            target = sessionId,
            detail =
            mapOf(
                "text_length" to text.length,
                "image_count" to images.size,
                "assistant_id" to (accessor.snapshot.currentAssistant?.id ?: "default"),
            ),
        )
        // v2.3: El enrutamiento del modelo de tarea solo está vinculado a la solicitud de envío actual,Una evaluación automática no se puede escribir permanentemente como una sobrescritura manual de sesión.
        // De lo contrario, envía uno primero.“escribir codigo”después,El resto de la charla general se atendrá al modelo de código.
        val selectedModel =
            sessionId.let { deps.generationState.sessionModelOverrides[it] }
                ?: deps.generationState.globalSelectedModelId
        val selectedProvider =
            sessionId.let { deps.generationState.sessionProviderOverrides[it] }
                ?: deps.generationState.globalActiveProviderId
        val routed = deps.settings.recommendTaskRoute(text, selectedModel, selectedProvider)
        if (routed != null) {
            // Solo actualiza la instantánea de la interfaz de usuario actual visible para el usuario;Restaurar el manual real una vez completada la generación./Selección global.
            accessor.update {
                it.copy(
                    selectedModelId = routed.modelId ?: it.selectedModelId,
                    activeProviderId = routed.providerId ?: it.activeProviderId,
                )
            }
        }
        // v1.0.47 P5: Registro de historial de entrada(nuevo→viejo,Eliminar duplicados,Truncado a MAX_INPUT_HISTORY)
        val newHistory = (listOf(text) + accessor.snapshot.inputHistory.filter { it != text }).take(MAX_INPUT_HISTORY)
        val userMsg =
            UIMessage(role = MessageRole.USER, content = text, imageBase64List = images, attachments = attachments)
        // P0 Reparación: Forzar asistenteMsg.createdAt para que sea estrictamente posterior a userMsg.createdAt(+1ms),Evite la inestabilidad de la clasificación de bases de datos.
        // v2.6.15: Escriba el modelo de salida. —— El mismo modelo de guardia utilizado por think block replays lo utiliza para determinar la fuente (ver ProviderPayloadNormalizer
        // stripCrossModelReasoning). Lo que se utiliza aquí es el modelo que realmente se utilizará para esta transmisión (el enrutamiento de tareas se ha vuelto a escribir en la instantánea);
        // Si otros modelos (modelos específicos del asistente/modelos de rueda de herramientas/cambio de respaldo) se analizan después de que realmente comienza la transmisión,
        // por [ChatToolLoopRunner] Al final, se reescribe según el modelo real de esta ronda.
        val assistantMsg =
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                createdAt = userMsg.createdAt + 1,
                modelId = accessor.snapshot.selectedModelId,
            )
        // v1.0.15: Escritura asincrónica outbox(garantia"Salga tan pronto como haga clic en enviar"Los mensajes no se pierden cuando)
        val outboxId = Uuid.random().toString()
        val outboxReady =
            accessor.coroutineScope.async(Dispatchers.IO) {
                withContext(NonCancellable) {
                    resultOf {
                        deps.sessionRepository.insertOutbox(
                            MessageOutboxEntity(
                                id = outboxId,
                                sessionId = sessionId,
                                text = text,
                                imageBase64Json = deps.idListJson.encodeToString(images),
                                userMessageId = userMsg.id.toString(),
                                assistantMessageId = assistantMsg.id.toString(),
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                    }.onError { _, t -> Logger.w("ChatVM", "outbox Error de escritura,Esta entrega será bloqueada", t) }.isSuccess
                }
            }
        deps.stateStore.messages.value = deps.stateStore.messages.value + userMsg + assistantMsg
        accessor.update {
            it.copy(
                input = "",
                hasDraft = false,
                pendingImages = emptyList(),
                pendingDocuments = emptyList(),
                replyingTo = null,
                replyQuoteOverride = null,
                // F-10: enviar para entrar CONNECTING(Esperando el primero token)
                streamState = it.streamState.copy(phase = ChatStreamPhase.CONNECTING),
                isStreaming = true,
                // v1.0.3: entrar"Esperando el primero token"escenario,UI mostrar ThinkingBubble
                isWaitingFirstToken = true,
                errors = emptyList(),
                // v1.0.47 P5: Registro de historial de entrada,Restablecer índice de navegación(Salir de la navegación histórica después de enviar)
                inputHistory = newHistory,
                inputHistoryIndex = null,
            )
        }
        val sendResult =
            deps.generationState.sendChannel.trySend(
                SendRequest(
                    text,
                    images,
                    sessionId,
                    userMessage = userMsg,
                    assistantMessageId = assistantMsg.id,
                    outboxId = outboxId,
                    outboxReady = outboxReady,
                    taskRouteSelection = routed,
                ),
            )
        if (sendResult.isFailure) {
            // La cola está llena,Revertir actualización optimista + eliminar outbox(Mensaje no en cola,outbox Inútil)
            accessor.coroutineScope.launch(Dispatchers.IO) {
                val persisted = runCatching { outboxReady.await() }.getOrDefault(false)
                if (persisted) {
                    resultOf { deps.sessionRepository.deleteOutbox(outboxId) }
                }
            }
            accessor.update {
                val filtered =
                    deps.stateStore.messages.value.filterNot { msg ->
                        msg.id == userMsg.id || msg.id == assistantMsg.id
                    }
                deps.stateStore.messages.value = filtered
                it.copy(isStreaming = false, isWaitingFirstToken = false)
            }
            deps.addError(ChatErrorType.UNKNOWN, deps.appContext.getString(R.string.err_chat_queue_full), true)
            return
        }
        deps.messageController.rebuildConversationTree()
    }

    /** Revertir una actualización optimista de una solicitud cuando falla el consumo de la cola(user/assistant Mensaje de marcador de posición). */
    fun rollbackOptimisticSend(req: SendRequest) {
        if (!shouldRollbackOptimisticSend(accessor.snapshot, req.sessionId)) return
        deps.stateStore.messages.value =
            deps.stateStore.messages.value.filterNot { message ->
                message.id == req.userMessage.id || message.id == req.assistantMessageId
            }
    }

    /** Consumir una sola solicitud de envío:Verificación de coincidencia de sesión → Mensaje de usuario de ubicación → Iniciar la construcción → Limpiar la bandeja de salida. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun consumeSendRequest(req: SendRequest) {
        deps.generationState.outboxRecoveryQueuedIds.remove(req.outboxId)
        // Las nuevas solicitudes primero deben confirmar que se ha colocado la bandeja de salida; de lo contrario, eliminar la bandeja de salida inmediatamente después de launchStream dejará una ventana irrecuperable.
        if (req.outboxReady != null && !req.outboxReady.await()) {
            rollbackOptimisticSend(req)
            accessor.update { it.copy(isStreaming = false, isWaitingFirstToken = false) }
            deps.addError(
                ChatErrorType.UNKNOWN,
                deps.appContext.getString(R.string.err_chat_msg_save_failed, "outbox persistence failed"),
                true,
            )
            return
        }
        if (restoreBlocksChatWrites("consumeSendRequest")) {
            rollbackOptimisticSend(req)
            accessor.update { it.copy(isStreaming = false, isWaitingFirstToken = false) }
            return
        }
        val state = accessor.snapshot
        val currentSid =
            if (state.isAgentMode) {
                state.agentSessionId ?: req.sessionId
            } else {
                state.currentSessionId ?: req.sessionId
            }
        if (currentSid != req.sessionId) {
            // Sesión cambiada,La solicitud se omite — Solo revertir actualizaciones optimistas(Mensaje de marcador de posición),Reserve la bandeja de salida para el proceso de recuperación después de volver a cambiar.
            // B-14: No está clara la situación general isStreaming —— Las solicitudes de sesiones antiguas no deben ocultar las animaciones de transmisión de la sesión actual.;
            // Sólo si la sesión actual(List actual)El indicador se reinicia sólo cuando en realidad no hay nada en progreso.
            val currentGenerating = chatGenerationManager.activeGenerations.value.containsKey(currentSid)
            accessor.update { st ->
                val filtered =
                    deps.stateStore.messages.value.filterNot { msg ->
                        msg.id == req.userMessage.id || msg.id == req.assistantMessageId
                    }
                deps.stateStore.messages.value = filtered
                if (currentGenerating) st else st.copy(isStreaming = false, isWaitingFirstToken = false)
            }
            Logger.i("ChatVM", "Omitir solicitudes de la bandeja de salida fuera de la sesión actual: ${req.outboxId}")
            return
        }
        try {
            if (restoreBlocksChatWrites("appendUserMessage")) {
                rollbackOptimisticSend(req)
                accessor.update { it.copy(isStreaming = false, isWaitingFirstToken = false) }
                return
            }
            // P0 Reparación: Reutilizar directamente los creados por enqueueSend userMessage,Asegúrese de que el orden de createAt sea coherente con id.
            deps.sessionRepository.appendMessage(currentSid, req.userMessage)
        } catch (e: Exception) {
            Logger.e("ChatVM", "appendMessage failed", e)
            if (req.retryCount < 1) {
                Logger.i("ChatVM", "Reintentar envío (attempt ${req.retryCount + 1})")
                val retryResult = deps.generationState.sendChannel.trySend(req.copy(retryCount = req.retryCount + 1))
                if (retryResult.isFailure) {
                    Logger.w("ChatVM", "Error al intentar unirse a la cola(La cola está llena)")
                    deps.addError(
                        ChatErrorType.UNKNOWN,
                        deps.appContext.getString(
                            R.string.err_chat_msg_save_failed,
                            if (e.message?.contains("connection is closed", ignoreCase = true) == true) {
                                deps.appContext.getString(R.string.err_chat_db_closed)
                            } else {
                                e.message ?: deps.appContext.getString(R.string.err_chat_unknown)
                            },
                        ),
                        true,
                    )
                    accessor.update { it.copy(isStreaming = false) }
                    resultOf { deps.sessionRepository.deleteOutbox(req.outboxId) }
                }
            } else {
                deps.addError(
                    ChatErrorType.UNKNOWN,
                    deps.appContext.getString(
                        R.string.err_chat_msg_save_failed,
                        if (e.message?.contains("connection is closed", ignoreCase = true) == true) {
                            deps.appContext.getString(R.string.err_chat_db_closed)
                        } else {
                            e.message ?: deps.appContext.getString(R.string.err_chat_unknown)
                        },
                    ),
                    true,
                )
                accessor.update { it.copy(isStreaming = false) }
                resultOf { deps.sessionRepository.deleteOutbox(req.outboxId) }
            }
            return
        }
        rehydrateOptimisticPlaceholders(currentSid, req)
        val delegated = deps.maybeAutoRoute(req.text, req.assistantMessageId, currentSid)
        if (delegated) {
            restoreSelectionForSession(currentSid)
            // delegated La ruta la toma el ejecutor delegado y no pasa por el punto de control de generación local.
            if (!restoreBlocksChatWrites("clearDelegatedOutbox")) {
                resultOf { deps.sessionRepository.deleteOutbox(req.outboxId) }
            }
        } else {
            launchStream(
                assistantId = req.assistantMessageId,
                sessionId = currentSid,
                isNewBranch = false,
                continueFrom = null,
                taskRouteSelection = req.taskRouteSelection,
                outboxId = req.outboxId,
            )
        }
        // La ruta no delegada elimina la bandeja de salida después de que el punto de control de primera generación tiene éxito, conservando la ventana de recuperación antes del inicio.
    }

    /**
     * v2.2.1 Reparación: outbox Restaurando la ruta de desvío(Reenvío después de la reversión de la sesión)Inserción optimista sin enqueueSend.
     * Si el marcador de posición no está en la lista,El guardia independiente de updateAssistant omitirá por completo las actualizaciones de transmisión ——
     * Se muestra como"El backend ha regresado y el frontend no se procesa.,Solo aparecerá después de volver a intentar o volver a ingresar a la sesión.". Aquí el marcador de posición se completa cuando se muestra la sesión actual.
     */
    private fun rehydrateOptimisticPlaceholders(sessionId: String, req: SendRequest) {
        val shown =
            if (accessor.snapshot.isAgentMode) {
                accessor.snapshot.agentSessionId
            } else {
                accessor.snapshot.currentSessionId
            }
        if (shown != sessionId) return
        val messages = deps.stateStore.messages.value
        val hasUser = messages.any { it.id == req.userMessage.id }
        val hasAssistant = messages.any { it.id == req.assistantMessageId }
        if (hasUser && hasAssistant) return
        val additions =
            buildList {
                if (!hasUser) add(req.userMessage)
                if (!hasAssistant) {
                    add(
                        UIMessage(
                            id = req.assistantMessageId,
                            role = MessageRole.ASSISTANT,
                            content = "",
                            createdAt = req.userMessage.createdAt + 1,
                        ),
                    )
                }
            }
        if (additions.isEmpty()) return
        deps.stateStore.messages.value = messages + additions
        deps.messageController.rebuildConversationTree()
        // v2.3.1: converger en una sola línea —— La escritura de varias líneas hará que el registro chino caiga en "líneas que no son de Logger",
        // Juzgado erróneamente como copia codificada de la interfaz de usuario por check_hardcoded_cjk(Esta verificación solo identifica las llamadas de registro por la misma línea)
        val userShort = req.userMessage.id.toString().take(8)
        val assistantShort = req.assistantMessageId.toString().take(8)
        Logger.i("ChatVM", "outbox restaurar: Complete el mensaje del marcador de posición user=$userShort, assistant=$assistantShort")
    }

    /**
     * v2.2.1: Limpiar anulaciones de proveedor/modelo pendientes en la sesión especificada(Provider no existe,O el modelo no esta
     * Provider abajo). Solo proceso"anulación explícita de sesión",No toques al asistente/Configuración global;providers Cuando la carga no está completa
     * saltar,Evite malentendidos. Después de borrar, la generación actual se generará presionando el asistente./Análisis de respaldo global,Al mismo tiempo, se desactivan las actualizaciones posteriores de la pantalla de advertencia.
     */
    private suspend fun healStaleSessionOverride(sessionId: String) {
        val providers = accessor.snapshot.providers
        if (providers.isEmpty()) return
        val state = deps.generationState
        val providerId = state.sessionProviderOverrides[sessionId]
        var changed = false
        if (providerId != null && providers.none { it.id == providerId }) {
            // Provider ha desaparecido:Borrar anulaciones de proveedor + modelo en pares(Configuración anti-medio set,
            // Misma semántica que la limpieza de eliminación de ProviderReferenceCleanup)
            state.sessionProviderOverrides = state.sessionProviderOverrides - sessionId
            state.sessionModelOverrides = state.sessionModelOverrides - sessionId
            runCatching { deps.settings.saveSessionProviderOverride(sessionId, null) }
                .onFailure { e -> Logger.w("ChatVM", "Error al limpiar la cobertura del proveedor pendiente: ${e.message}", e) }
            runCatching { deps.settings.saveSessionModelOverride(sessionId, null) }
                .onFailure { e -> Logger.w("ChatVM", "No se pudo borrar la cobertura del modelo colgante: ${e.message}", e) }
            changed = true
        } else if (providerId != null) {
            val modelId = state.sessionModelOverrides[sessionId]
            // v2.6.7 fix: Lista de modelos**esta vacio**(Nunca se sacó/no se pudo sacar) no significa que el modelo no exista.
            // antigua forma de escribir `models.none { it.id == modelId }` Siempre cierto en una lista vacía,
            // Entonces, en el arranque en frío, en la ventana donde aún no se ha extraído la lista, se sobrescribe el modelo establecido por el usuario por sesión.
            // **eliminarlo directamente**(La limpieza destructiva es más grave que la degradación por errores de juicio).
            // Aquí se utiliza un juicio de tres estados: sólo está claro "la lista no está vacía y no contiene la identificación".
            val binding =
                if (modelId != null) {
                    resolveRequestedModelBinding(providers, providerId, modelId)
                } else {
                    null
                }
            if (binding?.modelMissing == true) {
                state.sessionModelOverrides = state.sessionModelOverrides - sessionId
                runCatching { deps.settings.saveSessionModelOverride(sessionId, null) }
                    .onFailure { e -> Logger.w("ChatVM", "No se pudo borrar la cobertura del modelo colgante: ${e.message}", e) }
                changed = true
            }
        }
        if (changed) {
            Logger.i("ChatVM", "Sesión borrada $sessionId modelo suspendido/Provider Portada")
        }
    }

    /**
     * v2.5.3 (P3-2): Tome una instantánea de la configuración a nivel de sesión cuando se genere por primera vez.
     *
     * Poner el proveedor/modelo/nivel de pensamiento en el momento de la puesta en marcha./Una vez fijada la temperatura, escriba sesiones.configSnapshotJson.
     * - Instantánea existente: no sobrescribir (conservar“La configuración en ese momento.”).
     * - instantánea vacía/Fallo de escritura: salte en silencio y no bloquee la generación.
     */
    private suspend fun captureConfigSnapshotIfAbsent(sessionId: String) {
        val st = accessor.snapshot
        // Las anulaciones a nivel de sesión tienen prioridad (especificadas explícitamente por el usuario) y recurren a la selección actual global si no hay ninguna anulación.
        val modelId = deps.generationState.sessionModelOverrides[sessionId] ?: st.selectedModelId
        val providerId = deps.generationState.sessionProviderOverrides[sessionId] ?: st.activeProviderId
        val snapshot =
            io.zer0.muse.data.session.SessionConfigSnapshot(
                providerId = providerId,
                modelId = modelId,
            )
        runCatching {
            deps.sessionRepository.saveConfigSnapshotIfAbsent(sessionId, snapshot)
        }.onFailure { e -> Logger.w("ChatVM", "No se pudo tomar la instantánea de configuración de la sesión: ${e.message}", e) }
    }

    /** Actualizar rápidamente el recuento de tokens(Llamado cada 200 caracteres o 1000 ms durante la transmisión,evitar reconstruir cada vez system prompt). */
    /**
     * Actualizar las estimaciones de ocupación del contexto.
     *
     * v2.x: [excludedMessageIds] excluir"Se ha incorporado al punto de control de la sesión y no se volverá a enviar al modelo."noticias.
     * La ocupación debe reflejar**Lo que realmente verá el modelo**: De lo contrario, la tasa de ocupación después de la compresión aún se detendrá en el nivel del agua antes de la compresión.
     * La compresión del mismo período de la historia se activará repetidamente en cada ronda (lo cual es costoso y también hará que los usuarios vean el extraño fenómeno de la compresión repetida).
     *
     * v2.x: calibre cambiado a"provider Medición real primero + estimación local de cola"(Ver [ContextTokenUsage]).
     * Cuando no hay ningún mensaje con uso en la sesión, el resultado es el mismo que antes. [TokenEstimator] Estimar.
     */
    suspend fun updateContextTokenCount(excludedMessageIds: Set<String> = emptySet()) {
        val msgsSnapshot = deps.stateStore.messages.value
        val counted =
            if (excludedMessageIds.isEmpty()) {
                msgsSnapshot
            } else {
                msgsSnapshot.filterNot { it.id.toString() in excludedMessageIds }
            }
        val sysPromptSnapshot = deps.systemPromptCache.cachedSystemPrompt
        val tokenCount =
            withContext(Dispatchers.Default) {
                runCatching { ContextTokenUsage.estimate(counted, sysPromptSnapshot) }
                    .onFailure { Logger.w("ChatVM", "TokenEstimator failed: ${it.message}") }
                    .getOrDefault(0)
            }
        accessor.update { it.copy(contextTokenCount = tokenCount) }
    }

    /** Invalidación de la instantánea del mensaje del sistema estático key(assistant/settings/Lista de herramientas/Los cambios en las preferencias desencadenan reconstrucciones). */
    /**
     * X-5/L1-5: Contiene alcance/dimensión espacial**completo**La instantánea caduca key —— Fuente única de verdad.
     *
     * anteriormente [computeStaticSnapshotKey] La persona que llama de (ChatViewModel.refreshContextInfo con
     * Los buildSystemPromptForStream de esta clase) están escritos a mano. `+ "|global=...|scope=...|space=..."`,
     * Las dos concatenaciones literales se repiten por completo. En el futuro, si se agrega una nueva dimensión y solo se realiza un cambio, las dos cachés no estarán sincronizadas.
     * Después de converger aquí, los dos lugares comparten el mismo empalme.
     */
    internal fun computeFullSnapshotKey(
        assistant: AssistantEntity?,
        memoryEnabled: Boolean,
        useGlobalMemory: Boolean,
        memoryScope: String,
        memorySpaceId: String,
        // La conexión entre sesiones se basa en "qué sesión es la actual" para seleccionar la anterior, y la instantánea debe reconstruirse al cambiar de sesión.
        sessionId: String? = null,
    ): String = computeStaticSnapshotKey(assistant, memoryEnabled) +
        "|global=$useGlobalMemory|scope=$memoryScope|space=$memorySpaceId|session=${sessionId.orEmpty()}"

    internal fun computeStaticSnapshotKey(assistant: AssistantEntity?, memoryEnabled: Boolean): String {
        val prefs = accessor.snapshot.chatPreferences
        val registeredToolFingerprint =
            deps.toolRegistry.listTools()
                .sortedBy { it.name }
                .joinToString(";") { tool ->
                    "${tool.name}|${tool.description}|${tool.parameters}|${tool.required.sorted()}"
                }
                .hashCode()
        val state = accessor.snapshot
        val effectiveSessionId = if (state.isAgentMode) state.agentSessionId else state.currentSessionId
        val sessionSkillHash =
            state.sessions
                .firstOrNull { it.id == effectiveSessionId }?.skillIdsJson?.hashCode() ?: 0
        // v1.0.72: Esta sesión no hace referencia a la marca de memoria para agregar claves de caché.
        val sessionIgnoreMemory =
            state.sessions
                .firstOrNull { it.id == effectiveSessionId }?.ignoreMemory ?: false
        return buildString {
            append(assistant?.id ?: "null")
            append("|")
            append(assistant?.updatedAt ?: 0)
            append("|")
            append(assistant?.systemPrompt?.hashCode() ?: 0)
            append("|")
            append(assistant?.toolIdsJson?.hashCode() ?: 0)
            append("|")
            append(assistant?.mcpServerIdsJson?.hashCode() ?: 0)
            append("|")
            append(registeredToolFingerprint)
            append("|")
            append(assistant?.skillIdsJson?.hashCode() ?: 0)
            append("|")
            append(sessionSkillHash)
            append("|")
            append(assistant?.memoryEnabled ?: true)
            append("|")
            append(memoryEnabled)
            append("|")
            append(deps.settings.experienceEnabledCache)
            append("|")
            append(state.multiAgentConfig.enabled)
            append("|")
            append(prefs.showMoodBlock)
            append("|")
            append(prefs.responseStyle)
            append("|")
            append(prefs.responseTone)
            append("|")
            append(sessionIgnoreMemory)
        }
    }

    /** montar system prompt(9 un section)+ Recuperación de memoria relevante + inyección automática de RAG + verificación de truncamiento de contexto previo al envío.
     *
     * @return true = La palabra indicada está disponible y la ronda continúa;false = Las palabras estáticas no están disponibles, la persona que llama debe**Abortar esta ronda**
     *   (No está permitido continuar generando con palabras de aviso vacías. Por el motivo, consulte [StaticPromptOutcome]).
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount")
    suspend fun buildSystemPromptForStream(state: StreamRunState): Boolean {
        with(state) {
            // El cambio de contexto compartido solo controla el retrato global/fijar hacia arriba/Experiencia; Los propios hechos del asistente siguen siendo independientes de las sesiones recientes.
            val memoryEnabled = assistant?.memoryEnabled ?: true
            val useGlobalMemory = assistant?.useGlobalMemory ?: false
            val timeReminderEnabled = assistant?.enableTimeReminder ?: true
            val assistantId = assistant?.id?.takeIf { it.isNotBlank() }
            val effectiveMemoryEnabled = memoryEnabled && deps.settings.isMemoryEnabled()
            // v1.0.72: Esta sesión no se refiere a indicadores de memoria.
            val effSid =
                if (accessor.snapshot.isAgentMode) {
                    accessor.snapshot.agentSessionId
                } else {
                    accessor.snapshot.currentSessionId
                }
            val sessionIgnoreMem =
                accessor.snapshot.sessions
                    .firstOrNull { it.id == effSid }?.ignoreMemory ?: false
            val relevantMemoryAssistantId =
                assistantId?.takeIf {
                    AssistantMemoryAccessPolicy.canInjectAssistantFacts(
                        assistantId = it,
                        useGlobalMemory = useGlobalMemory,
                        memoryEnabled = effectiveMemoryEnabled,
                        forSubagent = false,
                        ignoreMemory = sessionIgnoreMem,
                    )
                }
            val memoryScope = assistantId?.takeIf { it != "default" } ?: "main"
            val memorySpaceId = deps.settings.currentSpaceIdFlow.firstOrNull().orEmpty().ifBlank { "default" }
            // Reutilizar instantáneas estáticas del mensaje del sistema,Solo agregar actualizaciones"hora actual". Alcance/El espacio también pertenece a la identidad instantánea.,
            // De lo contrario, el último mensaje de memoria se reutilizará después de cambiar de Asistente o Espacio.
            // L1-5: La clave completa requiere una única función de fuente de verdad (compartida con ChatViewModel.refreshContextInfo).
            val currentKey =
                computeFullSnapshotKey(
                    assistant = assistant,
                    memoryEnabled = effectiveMemoryEnabled,
                    useGlobalMemory = useGlobalMemory,
                    memoryScope = memoryScope,
                    memorySpaceId = memorySpaceId,
                    // Se debe pasar la misma identidad como ChatViewModel.refreshContextInfo; de lo contrario, las claves en ambos lados de la lectura y la escritura nunca serán iguales.
                    // El primer mensaje después de ingresar a cada sesión reconstruirá completamente la instantánea estática.
                    sessionId = effSid,
                )
            val staticSnapshot =
                if (currentKey == deps.systemPromptCache.cachedStaticSnapshotKey &&
                    deps.systemPromptCache.cachedStaticSystemPrompt.isNotBlank()
                ) {
                    deps.systemPromptCache.cachedStaticSystemPrompt
                } else {
                    // v2.6.x: Falló la construcción de la palabra de aviso estático**No debe degradarse silenciosamente a nulo**(Escritura original `getOrNull() ?: ""`).
                    // El modelo pierde personalidad ante palabras vacías./Descripción de la herramienta/La memoria todavía producirá un“parece normal”responder,
                    // Los usuarios sólo sentirán“AI De repente ya no lo parece”. Se considera no disponible aquí. → Cancelar la ronda actual + error visible.
                    // No usarlo deliberadamente“Última ronda de palabras de aviso de caché”En pocas palabras: ese es un estado implícito y la antigua personalidad se asignará por error al nuevo asistente.
                    when (
                        val outcome =
                            staticPromptBuildOutcome(
                                resultOf {
                                    deps.systemPromptAssembler.buildStaticSnapshot(
                                        assistant = assistant,
                                        memoryEnabled = effectiveMemoryEnabled,
                                        ignoreMemory = sessionIgnoreMem,
                                        useGlobalMemory = useGlobalMemory,
                                        memoryScope = memoryScope,
                                        memorySpaceId = memorySpaceId,
                                        // La conexión entre sesiones depende de ella para excluir "la sesión actual en sí"; de lo contrario, el final de la ronda de diálogo actual se inyectará en la sesión anterior.
                                        sessionId = effSid,
                                    )
                                },
                            )
                    ) {
                        is StaticPromptOutcome.Ready -> {
                            deps.systemPromptCache.cachedStaticSystemPrompt = outcome.prompt
                            deps.systemPromptCache.cachedStaticSnapshotKey = currentKey
                            outcome.prompt
                        }
                        is StaticPromptOutcome.Failed -> {
                            Logger.e(
                                "ChatVM",
                                "static system prompt build failed, abort turn: ${outcome.reason} | " +
                                    "sessionId=$sessionId | assistantId=${assistant?.id ?: "-"}",
                            )
                            return false
                        }
                        StaticPromptOutcome.Empty -> {
                            Logger.e(
                                "ChatVM",
                                "static system prompt built empty, abort turn | " +
                                    "sessionId=$sessionId | assistantId=${assistant?.id ?: "-"}",
                            )
                            return false
                        }
                    }
                }
            val dynamicSection = if (timeReminderEnabled) deps.systemPromptAssembler.buildDynamicSection() else ""
            val userMessageTimeContext = UserMessageTimeContext.build(rawHistory)
            val lastUserInputForPrompt = rawHistory.lastOrNull { it.role == MessageRole.USER }?.content
            // v2.x: Guía de uso de emoticonos(lectura dinámica;La biblioteca esta vacia/Cadena vacía cuando el interruptor está apagado)
            val stickerGuide = resultOf { deps.systemPromptAssembler.buildStickerGuideSection() }.getOrNull().orEmpty()
            // v2.6.8: Segmento de estado civil. Anteriormente, sólo se utilizaba la generación de bypass. build() Lo inyectará, cadena principal de Danchao.
            // El enfoque es buildStaticSnapshot + ensamblaje dinámico, que nunca se ha incluido. Las relaciones cambian con el tiempo de interacción,
            // No se pueden tomar instantáneas estáticas; de lo contrario, cada cambio en la intimidad invalidará todo el caché, por lo que será un segmento dinámico.
            val relationshipSection =
                resultOf { deps.systemPromptAssembler.buildRelationshipSection(assistant) }
                    .onError { msg, throwable ->
                        Logger.w("ChatVM", "buildRelationshipSection falló: $msg", throwable)
                    }
                    .getOrNull()
                    .orEmpty()
            val combinedSystemPrompt =
                buildString {
                    if (staticSnapshot.isNotBlank()) append(staticSnapshot)
                    if (dynamicSection.isNotBlank()) {
                        if (isNotEmpty()) append("\n\n---\n\n")
                        append(dynamicSection)
                    }
                    if (userMessageTimeContext.isNotBlank()) {
                        if (isNotEmpty()) append("\n\n---\n\n")
                        append(userMessageTimeContext)
                    }
                    if (relationshipSection.isNotBlank()) {
                        if (isNotEmpty()) append("\n\n---\n\n")
                        append(relationshipSection)
                    }
                    if (stickerGuide.isNotBlank()) {
                        if (isNotEmpty()) append("\n\n---\n\n")
                        append(stickerGuide)
                    }
                    // Recuperación de memoria relevante(Sólo si la memoria está activada y no es un subasistente;La recuperación falló y se omitió silenciosamente).
                    if (relevantMemoryAssistantId != null) {
                        // buildSystemPrompt Ejecutado antes de aplicarTransformers,En este momento transformedMessages
                        // todavía vacío;Utilice lo que se ha preparado en esta ronda. rawHistory,De lo contrario, la memoria relevante nunca se inyecta.
                        val lastUserInput = lastUserInputForPrompt
                        if (!lastUserInput.isNullOrBlank()) {
                            val relevant =
                                resultOf {
                                    deps.systemPromptAssembler.buildRelevantMemorySection(
                                        currentUserInput = lastUserInput,
                                        store = null,
                                        scope = memoryScope,
                                        spaceId = memorySpaceId,
                                        assistantId = relevantMemoryAssistantId,
                                    )
                                }
                                    .onError { msg, _ -> Logger.w("ChatVM", "buildRelevantMemorySection falló: $msg") }
                                    .getOrNull() ?: ""
                            if (relevant.isNotBlank()) {
                                if (isNotEmpty()) append("\n\n---\n\n")
                                append(relevant)
                            }
                        }
                    }
                    // Rastreo del texto de la conversación:Reutilizar la recuperación del alcance de la sesión actual de message_fts,No copiar mensajes a la base de conocimientos.,
                    // Tampoco inyecta contenido interno del SISTEMA/HERRAMIENTA en el modelo. Los usuarios pueden desactivar esta capacidad en la configuración de la memoria.
                    if (
                        effectiveMemoryEnabled &&
                        !sessionIgnoreMem &&
                        deps.settings.memoryConfigCache.conversationRecallEnabled &&
                        !lastUserInputForPrompt.isNullOrBlank() &&
                        effSid != null
                    ) {
                        val recalled =
                            resultOf {
                                deps.sessionRepository.searchMessagesInSession(
                                    sessionId = effSid,
                                    query = lastUserInputForPrompt,
                                    limit = 8,
                                )
                            }.onError { msg, throwable ->
                                Logger.w("ChatVM", "Error en el rastreo del texto original de la sesión: $msg", throwable)
                            }.getOrNull().orEmpty()
                        val recallSection =
                            ConversationRecallFormatter.build(
                                query = lastUserInputForPrompt,
                                results = recalled,
                                maxTokens = deps.settings.memoryConfigCache.tokenBudget.coerceAtMost(1600),
                            )
                        if (recallSection.isNotBlank()) {
                            if (isNotEmpty()) append("\n\n---\n\n")
                            append(recallSection)
                        }
                        Logger.d(
                            "ChatVM",
                            "conversation recall | enabled=true | sessionId=$effSid | " +
                                "matches=${recalled.size} | injected=${recallSection.isNotBlank()}",
                        )
                    }
                }
            // L1-4: system prompt puerta total —— Cada sección tiene su propio presupuesto (memoria relacionada/Revisar la abrazadera respectiva),
            // Pero después de empalmar system prompt **Sin límite superior de longitud total**(Retrato largo + memoria larga + reseña larga se expandirán cuando se superpongan).
            // Aquí, ContextBudget.SYSTEM_PROMPT se utiliza para realizar una abrazadera total: la cola se trunca más allá del límite y se adjunta con notas de diagnóstico.
            val limitedSystemPrompt =
                io.zer0.muse.context.ContextBudget()
                    .clampText(io.zer0.muse.context.ContextSection.SYSTEM_PROMPT, combinedSystemPrompt)
            val dynamicSystemPrompt =
                if (staticSnapshot.isNotBlank() && limitedSystemPrompt.startsWith(staticSnapshot)) {
                    limitedSystemPrompt
                        .removePrefix(staticSnapshot)
                        .removePrefix("\n\n---\n\n")
                } else {
                    limitedSystemPrompt
                }
            systemMessages = composeSystemPromptMessages(staticSnapshot, dynamicSystemPrompt)
            Logger.d(
                "ChatVM",
                "system prompt dynamic sections | timeReminder=$timeReminderEnabled" +
                    " | currentTimeIncluded=${dynamicSection.contains(InternalPromptMarkers.TIME_SECTION_PREFIX)}" +
                    " | userMessageTimeIncluded=${userMessageTimeContext.isNotBlank()}" +
                    " | systemMessages=${systemMessages.size}",
            )
            deps.systemPromptCache.cachedSystemPrompt = limitedSystemPrompt
            updateContextTokenCount()

            // Verificación exhaustiva de la longitud del contexto antes de enviar:token Truncar agresivamente el historial cuando la ocupación exceda el índice de advertencia.
            // P3-10: Al mismo tiempo, registre si esta ronda está cerca del límite superior. — Solo envíe la carga útil que exceda el límite cuando esté cerca del límite superior.,
            // Evite pagar un presupuesto BPE adicional por envío normal.
            // v2.6.x: Prensa de tamaño de ventana**Modelo real de esta ronda.**(effectiveModel), si no puedes conseguirlo, devuélvelo.
            // UI Calculado presionando el modelo global. contextMaxTokens —— La implementación original solo usa este último,
            // anulación de sesión/Cuando el asistente vincula otro modelo, todos los umbrales están desalineados.
            val windowTokens = effectiveContextWindowTokens(effectiveModel, accessor.snapshot.contextMaxTokens)
            var nearContextLimit = false
            run {
                val maxTokens = windowTokens
                val currentTokens = accessor.snapshot.contextTokenCount
                if (isNearContextLimit(currentTokens, maxTokens)) {
                    val ratio = currentTokens.toFloat() / maxTokens
                    nearContextLimit = true
                    // v2.x Importar calentamiento:El recorte presupuestario está en vigor,Saltar la reducción a la mitad por número de artículos(De lo contrario destruirá toda la intención.)
                    if (rawHistory.size > 5 && !warmupActive) {
                        val newSize = (contextSize / 2).coerceAtLeast(2)
                        if (newSize < contextSize) {
                            Logger.w(
                                "ChatVM",
                                "pre-send context warning: " +
                                    "token=$currentTokens/$maxTokens (${(ratio * 100).toInt()}%), " +
                                    "history truncated $contextSize -> $newSize messages",
                            )
                            contextSize = newSize
                            truncatedHistory = buildContextWindow(rawHistory, contextSize)
                        }
                    }
                }
            }

            prefixMessages =
                buildList<UIMessage> {
                    addAll(systemMessages)
                    // presetMessages(Diálogo predeterminado)
                    assistant?.let { deps.assistantRepository.parsePresetMessages(it) }?.forEach { add(it) }
                    // v1.54: RAG inyección automática(El fallo no bloquea el proceso principal.).
                    val ragConfig = resultOf { deps.settings.getRagConfig() }.getOrNull() ?: io.zer0.muse.rag.RagConfig()
                    val effectiveRagConfig =
                        assistant?.let {
                            runCatching { deps.assistantRepository.mergeRagConfigOverride(it, ragConfig) }
                                .onFailure { e -> Logger.w("ChatViewModel", "mergeRagConfigOverride falló: ${e.message}") }
                                .getOrDefault(ragConfig)
                        } ?: ragConfig
                    if (effectiveRagConfig.enabled) {
                        val lastUser = rawHistory.lastOrNull { it.role == MessageRole.USER }
                        val ragQuery = lastUser?.content?.takeIf { it.isNotBlank() }
                        if (ragQuery != null) {
                            val mentionDocIds =
                                resultOf { deps.ragService.resolveMentionToDocIds(ragQuery) }
                                    .onError { msg, t -> Logger.w("ChatViewModel", "@mention El análisis falló: $msg", t) }
                                    .getOrNull()
                            // Cuando el asistente une KB,Las consultas sin @mención explícita recuperan solo estos KB;
                            // Las menciones explícitas tienen prioridad,Se puede dirigir temporalmente a un documento especificado por el usuario.
                            val boundKnowledgeBaseIds =
                                assistant
                                    ?.let { deps.assistantRepository.parseKnowledgeBaseIds(it) }
                                    .orEmpty()
                            // v2.4.6: Priorizar el uso de "KB+Alcance de la "carpeta"(carpeta = dominio de búsqueda independiente);
                            // Recurra al antiguo enlace de toda la biblioteca cuando no se establece ningún alcance de carpeta.
                            val boundFolderScopes =
                                assistant
                                    ?.let { deps.assistantRepository.parseKnowledgeFolderScopes(it) }
                                    .orEmpty()
                            val boundDocIds =
                                if (mentionDocIds.isNullOrEmpty()) {
                                    resultOf {
                                        if (boundFolderScopes.isNotEmpty()) {
                                            deps.ragService.resolveKbFolderScopes(boundFolderScopes)
                                        } else {
                                            deps.ragService.resolveKnowledgeBaseDocIds(boundKnowledgeBaseIds)
                                        }
                                    }.onError { msg, t ->
                                        Logger.w("ChatViewModel", "No se pudo expandir el asistente vinculado a la base de conocimientos: $msg", t)
                                    }.getOrNull()
                                } else {
                                    emptyList()
                                }
                            val scopeDocIds =
                                mentionDocIds.takeIf { !it.isNullOrEmpty() }
                                    ?: boundDocIds?.takeIf { it.isNotEmpty() }
                            val injection =
                                resultOf {
                                    deps.ragService.buildInjectionContextWithCitations(
                                        ragQuery, effectiveRagConfig, scopeDocIds,
                                    )
                                }.onError { msg, _ ->
                                    deps.addError(
                                        ChatErrorType.NETWORK,
                                        deps.appContext.getString(R.string.err_chat_rag_failed, msg),
                                        true,
                                    )
                                }.getOrNull()
                            if (injection != null) {
                                if (injection.text.isNotBlank()) {
                                    val clampedRagText =
                                        io.zer0.muse.context.ContextBudget().clampText(
                                            io.zer0.muse.context.ContextSection.RAG_CITATION,
                                            injection.text,
                                        )
                                    add(UIMessage(role = MessageRole.SYSTEM, content = clampedRagText))
                                }
                                if (injection.citations.isNotEmpty()) {
                                    pendingRagCitations = injection.citations
                                }
                            }
                        }
                    }
                }

            // P3-10: Desbordamiento del contexto — Revisa la verdad de esta ronda cerca del límite superior de tiempo. payload(system + preset + RAG
            // + Historia truncada)estimación token;Aún alcanza el límite máximo(CONTEXT_HARD_LIMIT_RATIO)Entonces sálvate a ti mismo primero
            // (Revisar una ronda de historia y reestimar),Si falla el autorrescate, configure el bit y rechace enviar.,Revertir el marcador de posición vacío mediante launchStream
            // Y brinde al usuario indicaciones visibles.
            //
            // La semántica de la puerta es la misma que la de operación (ChatToolLoopRunner la rama de recuperación de desbordamiento)**independiente**,
            // Cada ronda de solicitudes se presiona como máximo una vez, en lugar de compartir una bandera:
            //  - aquí está“ventana adicional de estimación local”, puede ser un error (la estimación no incluye la definición de la herramienta) / cache write / reasoning),
            //    Es posible que la compresión no sea realmente necesaria;
            //  - De hecho, el proveedor lo rechazó, lo que supone un verdadero conflicto.
            // Compartir un logotipo hará“error de cálculo”La única posibilidad real de comerse al proveedor es en vano.
            // El costo es el peor de los casos: dos rondas de compresión (aún excediendo -> Aún rechazado por el proveedor -> Presione nuevamente),
            // ambas veces“Mantener resumen + elementos recientes”La compresión no dejará la historia vacía;
            // A cada lado sólo se le permite una vez y eso es todo.
            if (nearContextLimit) {
                // La carga útil de una solicitud debe evaluarse dos veces (original/después de la compresión). El mensaje retenido después de la compresión es**El mismo lote de instancias.**,
                // Compartir una caché de recuento por instancia permite que el segundo paso"porción entera BPE"hasta"Sólo se cuentan los resúmenes nuevos.".
                val payloadTokenCache = MessageTokenCache()
                var payloadTokens =
                    withContext(Dispatchers.Default) {
                        TokenEstimator.estimate(prefixMessages + truncatedHistory, cache = payloadTokenCache)
                    }
                // v2.6.x: La intercepción dura también se basa en la ventana del modelo real de esta ronda (la fuente de la ventana es la misma que la de los windowTokens anteriores).
                val hardMaxTokens = windowTokens
                if (exceedsHardContextLimit(payloadTokens, hardMaxTokens)) {
                    // Sálvate a ti primero: vuelve a contar después de presionar. Si realmente no se supera, no se realizarán más interceptaciones.
                    val compressedHistory =
                        recoverPreSendOverflow(
                            history = truncatedHistory,
                            compress = { compressHistoryForOverflow(truncatedHistory, sessionId) },
                            stillOverflows = { candidate ->
                                payloadTokens =
                                    withContext(Dispatchers.Default) {
                                        TokenEstimator.estimate(
                                            prefixMessages + candidate,
                                            cache = payloadTokenCache,
                                        )
                                    }
                                exceedsHardContextLimit(payloadTokens, hardMaxTokens)
                            },
                        )
                    if (compressedHistory != null) {
                        truncatedHistory = compressedHistory
                        Logger.w(
                            "ChatVM",
                            "pre-send context overflow: Revalorización después de la compresión. payload=$payloadTokens/$hardMaxTokens " +
                                "(${payloadTokens * 100 / hardMaxTokens}%), continuar enviando | sessionId=$sessionId",
                        )
                    } else {
                        contextOverflowBlocked = true
                        Logger.w(
                            "ChatVM",
                            "pre-send context overflow: payload=$payloadTokens/$hardMaxTokens " +
                                "(${payloadTokens * 100 / hardMaxTokens}%), refuse to send | sessionId=$sessionId",
                        )
                    }
                }
            }
        }
        return true
    }

    /** Grabar eventos de sombra de conversación(ConversationRebuild Saltar silenciosamente al cerrar). */
    @Suppress("TooGenericExceptionCaught")
    suspend fun recordConversationShadow(event: ConversationEventDraft) {
        if (!ConversationRebuildFlagStore.current.shadowEventsEnabled) return
        val key = listOf(event.sessionId, event.turnId, event.generationSerial.toString()).joinToString("\u001f")
        shadowEventSequencer.enqueue(key) {
            try {
                deps.conversationService.record(event)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                Logger.w("ChatVM", "conversation shadow event failed: ${event.type}", e)
            }
        }
    }

    /** La reenvío de la solicitud de la bandeja de salida generada al volver a la sesión aún no ha comenzado. */
    suspend fun requeueOutboxForSession(sessionId: String) {
        if (restoreBlocksChatWrites("requeueOutbox")) return
        val pending = resultOf { deps.sessionRepository.getPendingOutbox(sessionId) }.getOrNull().orEmpty()
        for (req in pending) {
            if (!deps.generationState.outboxRecoveryQueuedIds.add(req.id)) continue
            val images =
                runCatching {
                    deps.idListJson.decodeFromString<List<String>>(req.imageBase64Json)
                }.getOrDefault(emptyList())
            val userId = runCatching { Uuid.parse(req.userMessageId) }.getOrElse { Uuid.random() }
            val assistantId = runCatching { Uuid.parse(req.assistantMessageId) }.getOrElse { Uuid.random() }
            val result =
                deps.generationState.sendChannel.trySend(
                    SendRequest(
                        text = req.text,
                        images = images,
                        sessionId = req.sessionId,
                        userMessage =
                        UIMessage(
                            id = userId,
                            role = MessageRole.USER,
                            content = req.text,
                            imageBase64List = images,
                            createdAt = req.createdAt,
                        ),
                        assistantMessageId = assistantId,
                        outboxId = req.id,
                    ),
                )
            if (result.isFailure) {
                deps.generationState.outboxRecoveryQueuedIds.remove(req.id)
                Logger.w("ChatVM", "La puesta en cola de la bandeja de salida falla al volver a la sesión: ${req.id}")
            }
        }
    }

    /** El enrutamiento automático de tareas solo muestra el modelo solicitado actualmente,Restaura la verdadera elección del usuario al final. */
    private fun restoreSelectionAfterTaskRoute(state: StreamRunState) {
        if (state.taskRouteSelection == null) return
        restoreSelectionForSession(state.sessionId)
    }

    private fun restoreSelectionForSession(sessionId: String) {
        val current = accessor.snapshot
        val displayedSessionId = if (current.isAgentMode) current.agentSessionId else current.currentSessionId
        if (displayedSessionId != sessionId) return
        val modelId =
            deps.generationState.sessionModelOverrides[sessionId]
                ?: deps.generationState.globalSelectedModelId
        val providerId =
            deps.generationState.sessionProviderOverrides[sessionId]
                ?: deps.generationState.globalActiveProviderId
        accessor.update { it.copy(selectedModelId = modelId, activeProviderId = providerId) }
    }

    /** Keep one diagnostic summary on every debug-mode terminal path. */
    suspend fun recordDebugSummary(state: StreamRunState, outcome: String) {
        // v2.x(Artículo de exportación de diagnóstico A): El rastro final ingresa incondicionalmente a GenerationTrace (el contenido en sí ha sido insensibilizado,
        // No tiene nada que ver con el modo de depuración.——Los usuarios comunes también pueden exportar cuando tienen problemas). Cualquier excepción se acepta internamente.
        runCatching {
            val selectedModel = resultOf { deps.settings.getSelectedModel() }.getOrNull()
            io.zer0.muse.diagnostic.GenerationTrace.record(
                io.zer0.muse.diagnostic.GenerationTrace.GenerationSummary(
                    sessionId = state.sessionId,
                    modelId = selectedModel?.id ?: "unknown",
                    providerType = state.effectiveProviderConfig?.type?.name ?: "unknown",
                    outcome = outcome,
                    elapsedMs = System.currentTimeMillis() - state.streamStartedAt,
                    ttftMs = if (state.firstTokenTime > 0L) state.firstTokenTime - state.streamStartedAt else -1L,
                    contentChars = state.builder.length,
                    reasoningChars = state.reasoningBuilder.length,
                    toolCallCount = state.totalToolCallCount,
                    round = state.round,
                    uiFlushCount = state.uiFlushCount,
                ),
            )
        }
        if (!state.experiments.debugMode) return
        val elapsedMs = System.currentTimeMillis() - state.streamStartedAt
        val ttftMs = if (state.firstTokenTime > 0L) state.firstTokenTime - state.streamStartedAt else -1L
        val elapsedSec = (elapsedMs / 1000f).coerceAtLeast(0.001f)
        val tokenRate = state.totalCharCount / elapsedSec
        val selectedModel = resultOf { deps.settings.getSelectedModel() }.getOrNull()
        val modelName = selectedModel?.name ?: selectedModel?.id
            ?: deps.appContext.getString(R.string.msg_info_unknown)
        val debugInfo =
            buildString {
                append(deps.appContext.getString(R.string.chat_debug_model_label))
                append(": $modelName")
                append(" | ")
                append(deps.appContext.getString(R.string.chat_debug_duration_label))
                append(": ${elapsedMs}ms")
                if (ttftMs >= 0) {
                    append(" | ")
                    append(deps.appContext.getString(R.string.chat_debug_ttft_label))
                    append(": ${ttftMs}ms")
                }
                append(" | ")
                append(deps.appContext.getString(R.string.chat_debug_rate_label))
                append(": ${"%.1f".format(tokenRate)} tok/s")
                append(" | ")
                append(deps.appContext.getString(R.string.chat_debug_chars_label))
                append(": ${state.totalCharCount}")
                append(" | ")
                append(deps.appContext.getString(R.string.chat_debug_tool_calls_label))
                append(": ${state.totalToolCallCount}")
                append(" | ")
                append(deps.appContext.getString(R.string.chat_debug_round_label))
                append(": ${state.round}")
                append(" | status=$outcome")
                append(" | uiFlush=${state.uiFlushCount}")
            }
        accessor.update { it.copy(debugInfo = debugInfo) }
        Logger.d("ChatVM-Debug", "launchStream terminal | outcome=$outcome | $debugInfo")
    }

    /** Solo borre el estado de transmisión si aún es la última versión(Al disparar en rápida sucesión, no se debe borrar el final de gen-1. gen-2). */
    fun clearStreamingStateIfLatest(state: StreamRunState, finalPhase: ChatStreamPhase = ChatStreamPhase.IDLE): Boolean {
        if (state.generationSerial != deps.generationState.streamGenerationSerial) return false
        accessor.update {
            it.copy(
                isStreaming = false,
                isWaitingFirstToken = false,
                toolProgressMessage = null,
                streamState = it.streamState.copy(phase = finalPhase),
            )
        }
        restoreSelectionAfterTaskRoute(state)
        return true
    }

    /** Iniciar generación de streaming:montar StreamRunState → 6 preparación del paso → ciclo de herramienta → Refinamiento/interrumpir/Persistencia excepcional. */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "TooGenericExceptionCaught")
    fun launchStream(
        assistantId: Uuid,
        sessionId: String,
        isNewBranch: Boolean = false,
        continueFrom: UIMessage? = null,
        taskRouteSelection: io.zer0.muse.data.SettingsRepository.TaskRouteSelection? = null,
        outboxId: String? = null,
    ) {
        if (restoreBlocksChatWrites("launchStream")) {
            accessor.update { it.copy(isStreaming = false, isWaitingFirstToken = false) }
            return
        }
        // v1.94: Borrar el historial de llamadas de herramientas antes de cada inicio de generación de transmisión(InputBar El recuento dinámico de cápsulas se restablece a cero)
        accessor.update { it.copy(toolCallHistory = emptyList()) }
        // B-1: Borrar la sesión"Eliminar la inhibición de escritura",Permite regenerar el disco después de su eliminación.
        sessionWritesSuppressed.remove(sessionId)
        // R-UI-02: Generar persistencia de sesión solo,Evite superponer el enfoque de visualización del usuario.
        accessor.coroutineScope.launch {
            resultOf { settings.saveGeneratingSessionId(sessionId) }
                .onError { msg, _ -> Logger.w("ChatVM", "saveGeneratingSessionId falló: $msg") }
        }
        // La identidad de la nueva generación se asigna antes de que el planificador espere a que finalmente termine la generación anterior. La finalización de la generación anterior no puede borrar la interfaz de usuario de la nueva generación a partir de este momento.
        val generationSerial = ++deps.generationState.streamGenerationSerial
        // Primero cree el estado de flujo y luego proporcione el mismo ID de generación al programador; estado activo,LLM, herramientas y aprobaciones comparten así una identidad generacional.
        val state = StreamRunState(sessionId = sessionId, assistantId = assistantId, isNewBranch = isNewBranch)
        state.outboxId = outboxId
        chatGenerationManager.launchGeneration(
            sessionId = sessionId,
            assistantId = assistantId.toString(),
            sessionTitle =
            accessor.snapshot.sessions.firstOrNull { it.id == sessionId }?.title
                ?: deps.appContext.getString(R.string.chat_new_session),
            generationId = state.generationIdentity.generationId,
        ) {
            if (restoreBlocksChatWrites("launchStream")) {
                accessor.update { it.copy(isStreaming = false, isWaitingFirstToken = false) }
                return@launchGeneration
            }
            val generationJob = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
            val generationExecutionId =
                executionRegistry?.register(
                    identity = state.generationIdentity,
                    kind = ExecutionKind.LLM,
                    cancel = { generationJob?.cancel() },
                )
            generationExecutionId?.let { id -> executionRegistry?.start(id) }
            // v2.2.1: Superposición a nivel de sesión que cuelga con autocuración — Cuando el proveedor/modelo señalado ya no existe,Borre esta sesión primero
            // cobertura flotante(Memoria + persistencia),Evita volver atrás y cepillarte cada vez que generas "requested model binding
            // unavailable" advertencia;asistente/Los enlaces globales no se ven afectados.
            healStaleSessionOverride(sessionId)
            // v2.5.3 (P3-2): Tome una instantánea de la configuración a nivel de sesión en la primera compilación —— Ponga el proveedor/modelo/nivel de pensamiento actual/temperatura
            // Se corrigió que incluso si se cambia el modelo global, la sesión anterior aún presiona“La configuración en ese momento.”Reenviar.
            // Sólo se escribe cuando aún no se ha tomado y no sobrescribe las instantáneas existentes; el fracaso no bloquea la generación.
            captureConfigSnapshotIfAbsent(sessionId)
            // Ayudante de anulación explícita de selección de sesión/Incumplimiento global; la tarea generada captura la configuración al inicio y no se producirá cambio de página durante el proceso.
            state.sessionModelOverride = deps.generationState.sessionModelOverrides[sessionId]
            state.sessionProviderOverride = deps.generationState.sessionProviderOverrides[sessionId]
            state.taskRouteSelection = taskRouteSelection
            state.fallbackModelId = deps.generationState.globalSelectedModelId
            state.fallbackProviderId = deps.generationState.globalActiveProviderId
            // B-24: Utilice el número de secuencia de generación actual asignado en la entrada; Evite las condiciones de carrera cuando espere a que finalmente se complete la generación anterior.
            state.generationSerial = generationSerial
            // M1.1: Iniciar un punto de control de turno en tiempo de ejecución de sesión(NOT_STARTED -> GENERATING).
            sessionManager.beginTurn(sessionId, state.turnId)
            chatGenerationManager.setTurnId(
                sessionId = sessionId,
                generationId = state.generationIdentity.generationId,
                turnId = state.generationIdentity.turnId,
            )
            // B7-04: Preestablezca el contenido generado cuando continúe generando
            continueFrom?.let { state.builder.append(it.content) }
            // P3-9: Etapas que requieren mucho tiempo — preparar/palabra inicial/cada ronda/Cronograma del segmento de cierre,Un registro de depuración puede localizar la sección lenta.
            // (palabra inicial/Cada ronda se complementa con los puntos enterrados Perf de ChatViewModel/ToolOrchestrator)
            val stageTimer = Perf.start("chat-turn")
            try {
                deps.streamCoordinator.prepareHistory(state)
                stageTimer.split("prepare")
                val mcpServerIds =
                    state.assistant
                        ?.let(deps.assistantRepository::parseMcpServerIds)
                        ?.toSet()
                        .orEmpty()
                if (mcpServerIds.isNotEmpty()) {
                    val ready = deps.mcpRegistry?.awaitToolsForServers(mcpServerIds) ?: true
                    if (!ready) {
                        Logger.w("ChatVM", "MCP tools not ready before stream: $mcpServerIds")
                    }
                }
                // v2.6.x: Cuando la palabra del mensaje estático no está disponible (error de compilación/el resultado está vacío)**Abortar esta ronda**,
                // En lugar de seguir generando con palabras vacías. Las respuestas con palabras vacías perderán tu personalidad./Descripción de la herramienta/memoria,
                // El usuario recibió un“respuesta normal”, que es mucho más engañoso que informar errores directamente.
                // elegir“abortar”en lugar de“Continuar + Consejos”razón: No se ha enviado ninguna solicitud en esta ronda y el usuario no ha esperado.
                // El primer token (ocurre en la fase de preparación/aviso), el costo de cancelación es mínimo; Deje que el modelo no genere palabras clave.
                // Contaminará el historial de conversaciones (esta respuesta se considerará su propia declaración en rondas posteriores).
                if (!buildSystemPromptForStream(state)) {
                    deps.addError(
                        ChatErrorType.UNKNOWN,
                        deps.appContext.getString(R.string.err_chat_prompt_unavailable),
                        true,
                    )
                    recordDebugSummary(state, "prompt_unavailable")
                    rollbackPromptFailurePlaceholder(state)
                    clearStreamingStateIfLatest(state, ChatStreamPhase.FAILED)
                    sessionManager.runtime(sessionId)?.markFinished(TurnPhase.FAILED, state.turnId)
                    runCatching {
                        notificationManager.updateLiveProgress("", 0, false)
                    }.onFailure { Logger.w("ChatVM", "No se pudo cancelar la notificación de progreso: ${it.message}") }
                    return@launchGeneration
                }
                stageTimer.split("prompt")
                // P3-10: Desbordamiento del contexto — precompresión(80%)+ truncamiento radical(90%)Cuando la ventana del modelo todavía se excede después,
                // Negarse a enviar esta ronda de solicitudes(Evite las inevitables 400 llamadas no válidas),Haga retroceder el marcador de posición vacío y proporcione un mensaje visible.
                if (state.contextOverflowBlocked) {
                    deps.addError(
                        ChatErrorType.UNKNOWN,
                        deps.appContext.getString(R.string.error_api_context_length),
                        true,
                    )
                    recordDebugSummary(state, "context_overflow")
                    accessor.updateMessages { list ->
                        list.filterNot { msg -> msg.id == state.currentAssistantId && msg.content.isBlank() }
                    }
                    deps.messageController.rebuildConversationTree()
                    clearStreamingStateIfLatest(state, ChatStreamPhase.FAILED)
                    sessionManager.runtime(sessionId)?.markFinished(TurnPhase.FAILED, state.turnId)
                    return@launchGeneration
                }
                deps.streamCoordinator.applyTransformers(state)
                deps.streamCoordinator.resolveToolsAndModel(state)
                deps.streamCoordinator.applyPiiGuard(state)
                deps.streamCoordinator.prepareVisionContext(state)
                stageTimer.split("assemble")
                val success = deps.runToolLoop(state)
                stageTimer.split("stream")
                if (success) {
                    finalizeResponse(state)
                    stageTimer.split("finalize")
                    sessionManager.runtime(sessionId)?.markFinished(TurnPhase.COMPLETED, state.turnId)
                } else {
                    recordDebugSummary(state, "failed")
                    sessionManager.runtime(sessionId)?.markFinished(TurnPhase.FAILED, state.turnId)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                generationExecutionId?.let { executionRegistry?.markCancelled(it) }
                val partialFromBuilder =
                    if (
                        state.builder.isNotEmpty() || state.reasoningBuilder.isNotEmpty()
                    ) {
                        UIMessage(
                            id = state.currentAssistantId,
                            role = MessageRole.ASSISTANT,
                            content = state.unmaskPii(state.builder.toString()),
                            reasoning = state.unmaskPii(state.reasoningBuilder.toString()).ifBlank { null },
                        )
                    } else {
                        withContext(NonCancellable) {
                            runCatching {
                                deps.sessionRepository.getMessageAsUiMessage(state.currentAssistantId.toString())
                            }.getOrNull()
                        }
                    }
                accessor.update { it.copy(streamState = it.streamState.copy(phase = ChatStreamPhase.INTERRUPTED)) }
                sessionManager.runtime(sessionId)?.markFinished(TurnPhase.CANCELLED, state.turnId)
                // B-1: Si se elimina la sesión, se omitirá la interrupción.,Prevenir la eliminación"resurrección".
                if (!isSessionWritesSuppressed(sessionId)) {
                    deps.persistInterruptedAssistant(
                        sessionId,
                        partialFromBuilder,
                        state.currentAssistantId,
                        System.currentTimeMillis() - state.streamStartedAt,
                    )
                }
                withContext(NonCancellable) {
                    runCatching {
                        deps.sessionRepository.deleteGenerationCheckpoints(sessionId, state.streamStartedAt)
                    }.onFailure { Logger.w("ChatVM", "Falló la interrupción de la limpieza de los puntos de control de generación: ${it.message}") }
                }
                if (ConversationRebuildFlagStore.current.shadowEventsEnabled) {
                    withContext(NonCancellable) {
                        recordConversationShadow(
                            ConversationEventDraft(
                                sessionId = sessionId,
                                turnId = state.turnId,
                                type = ConversationEventType.TURN_INTERRUPTED,
                                streamId = state.streamId,
                                generationSerial = state.generationSerial,
                                payloadJson = "{\"contentLength\":${state.builder.length}}",
                            ),
                        )
                        deps.conversationService.finishTurn(state.turnId, "INTERRUPTED")
                    }
                }
                withContext(NonCancellable) {
                    recordDebugSummary(state, "cancelled")
                }
                deps.generationState.nextToolGenerationToken()
                deps.generationState.toolAssistantId = null
                deps.generationState.activeToolSessionId = null
                throw ce
            } catch (t: Exception) {
                generationExecutionId?.let { executionRegistry?.fail(it) }
                Logger.e("ChatVM", "stream failed", t)
                val partialFromBuilder =
                    if (
                        state.builder.isNotEmpty() || state.reasoningBuilder.isNotEmpty()
                    ) {
                        UIMessage(
                            id = state.currentAssistantId,
                            role = MessageRole.ASSISTANT,
                            content = state.unmaskPii(state.builder.toString()),
                            reasoning = state.unmaskPii(state.reasoningBuilder.toString()).ifBlank { null },
                        )
                    } else {
                        withContext(NonCancellable) {
                            runCatching {
                                deps.sessionRepository.getMessageAsUiMessage(state.currentAssistantId.toString())
                            }.getOrNull()
                        }
                    }
                // B-1: Si la sesión se ha eliminado, se omitirá la ubicación anormal.,Prevenir la eliminación"resurrección".
                if (!isSessionWritesSuppressed(sessionId)) {
                    deps.persistInterruptedAssistant(
                        sessionId,
                        partialFromBuilder,
                        state.currentAssistantId,
                        System.currentTimeMillis() - state.streamStartedAt,
                    )
                }
                withContext(NonCancellable) {
                    runCatching {
                        deps.sessionRepository.deleteGenerationCheckpoints(sessionId, state.streamStartedAt)
                    }.onFailure { Logger.w("ChatVM", "Error en los puntos de control de generación de limpieza de excepciones: ${it.message}") }
                }
                deps.generationState.nextToolGenerationToken()
                deps.generationState.toolAssistantId = null
                deps.generationState.activeToolSessionId = null
                val type = deps.classifyErrorType(t.message ?: "", t)
                val msg = ErrorMessages.classifyNetworkError(deps.appContext, t)
                deps.addError(type, msg, type != ChatErrorType.API_KEY)
                recordDebugSummary(state, "failed")
                clearStreamingStateIfLatest(state, ChatStreamPhase.FAILED)
                sessionManager.runtime(sessionId)?.markFinished(TurnPhase.FAILED, state.turnId)
                runCatching {
                    notificationManager.updateLiveProgress("", 0, false)
                }.onFailure { Logger.w("ChatVM", "No se pudo cancelar la notificación de progreso: ${it.message}") }
            } finally {
                // P3-9: sincronización de fase final(Incluyendo preparación/palabra inicial/cada ronda/segmento de cierre;También se registran rutas anormales)
                //
                // Dos notas adicionales: retraso de la primera palabra y tiempos de actualización de la interfaz de usuario de transmisión.
                // Ellos mismos toman Perf.log, pero Perf solo registra ≥500ms escrito en el archivo de registro (chat-first-token normalmente más rápido),
                // Entonces no puedo encontrarlo en el registro en absoluto. Sólo colgándonos de esta línea de chat-turno, que habría sido colocada, podremos tener observabilidad.
                // Los nombres clave se evitan deliberadamente token / key / secret Otras palabras:Logger La desensibilización de la PII
                // `xxx=N` Este tipo de valor clave se reemplaza cuando las credenciales son [Insensibilizado](Primera edición firstToken=… Así se perdió).
                stageTimer.annotate(
                    "ttftMs",
                    if (state.firstTokenTime > 0L) state.firstTokenTime - state.streamStartedAt else -1L,
                )
                stageTimer.annotate("flushes", state.uiFlushCount)
                stageTimer.end()
                val executionState = generationExecutionId?.let { executionRegistry?.state(it) }
                if (generationExecutionId != null && executionState == ExecutionState.RUNNING) {
                    executionRegistry?.finish(generationExecutionId)
                }
                deps.sessionMemoryCache.remove(sessionId)
                deps.milestoneChecker?.checkAndTrigger(sessionId, accessor.snapshot.currentAssistant?.id ?: "default")
            }
        }
    }

    /**
     * v2.6.x: El final cuando la palabra clave no está disponible provoca que se cancele la ronda.
     *
     * La misma semántica que el camino más allá del contexto.:Revierta el marcador de posición del asistente vacío que aún no se ha generado y reconstruya el árbol de sesión.
     * No dejes respuestas vacías en la lista.**no borrar outbox**(Consistente con la ruta contextOverflowBlocked):
     * El fallo puede ser instantáneo (DB Jitter), conserva la bandeja de salida para permitir que el usuario vuelva a intentarlo automáticamente al volver a la sesión;
     * Si falla, se informará nuevamente un error visible y no se silenciará.
     */
    private fun rollbackPromptFailurePlaceholder(state: StreamRunState) {
        accessor.updateMessages { list ->
            list.filterNot { msg -> msg.id == state.currentAssistantId && msg.content.isBlank() }
        }
        deps.messageController.rebuildConversationTree()
    }

    /** Refinamiento:shadow TURN_FINISHED + onGenerationFinish Hooks + persistencia de metadatos + notificaciones + puntos de control limpios. */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    suspend fun finalizeResponse(state: StreamRunState) {
        val experiments = state.experiments
        val sessionId = state.sessionId
        val sessionTitle = state.sessionTitle
        val streamStartedAt = state.streamStartedAt
        // v2.x Importar calentamiento:Borre la marca después de la primera ronda de respuestas exitosas.(Todo el historial solo se puede reponer una vez.;idempotente)
        if (!restoreBlocksChatWrites("finalizeClearWarmup")) {
            resultOf { deps.sessionRepository.clearWarmupPending(sessionId) }
                .onError { msg, _ -> Logger.w("ChatVM", "clearWarmupPending failed: $msg") }
        }
        if (ConversationRebuildFlagStore.current.shadowEventsEnabled &&
            !restoreBlocksChatWrites("finalizeShadowEvents")
        ) {
            val finalMessage = deps.stateStore.messages.value.firstOrNull { it.id == state.currentAssistantId }
            val shadowContent = finalMessage?.content ?: state.builder.toString()
            val shadowLength = finalMessage?.content?.length ?: state.builder.length
            recordConversationShadow(
                ConversationEventDraft(
                    sessionId = sessionId,
                    turnId = state.turnId,
                    type = ConversationEventType.TURN_FINISHED,
                    streamId = state.streamId,
                    generationSerial = state.generationSerial,
                    payloadJson =
                    "{\"messageId\":\"${state.currentAssistantId}\"," +
                        "\"contentLength\":$shadowLength,\"contentHash\":\"${sha256(shadowContent)}\"}",
                ),
            )
            deps.conversationService.finishTurn(state.turnId)
        }

        clearStreamingStateIfLatest(state, ChatStreamPhase.FINISHED)

        // triple gancho:Llamado después de que la generación esté completa. applyOnGenerationFinish,Si se cambia el mensaje final del asistente, se vuelve a escribir en la base de datos.
        resultOf {
            val ctx = state.transformContext ?: return@resultOf
            val currentAssistantId = state.currentAssistantId
            val finalAssistant =
                deps.stateStore.messages.value
                    .firstOrNull { it.id == currentAssistantId } ?: return@resultOf
            val transformed = deps.transformerPipeline.applyOnGenerationFinish(listOf(finalAssistant), ctx)
            val newAssistant = transformed.firstOrNull()
            if (newAssistant != null && newAssistant != finalAssistant) {
                // P2-5: transformer será reconstruido UIMessage,Falta el campo de identidad variante(dependencia de implementación original
                // Siempre nulo _pendingVariantInfo escrito de nuevo,es un camino muerto). Aquí directamente al reemplazar.
                // Mantener campos variantes de finalAssistant,Retire el mecanismo de camino muerto.
                val preservedAssistant =
                    newAssistant.copy(
                        variantGroupId = newAssistant.variantGroupId ?: finalAssistant.variantGroupId,
                        variantIndex = if (newAssistant.variantIndex > 0) newAssistant.variantIndex else finalAssistant.variantIndex,
                        variantCount = if (newAssistant.variantCount > 0) newAssistant.variantCount else finalAssistant.variantCount,
                        parentGroupId = newAssistant.parentGroupId ?: finalAssistant.parentGroupId,
                    )
                // A9: Cubra únicamente los campos realmente producidos por el transformador.(content/mood/reflection/moodSkin),
                // Los campos restantes estarán sujetos a los elementos más recientes de los parámetros de entrada de CAS. Está prohibido reemplazar el conjunto. preservedAssistant,
                // De lo contrario, las instantáneas antiguas sobrescribirán las URL de medios, los metadatos, etc. escritos simultáneamente dentro de la ventana.
                accessor.updateMessages { list ->
                    list.map {
                        if (it.id == currentAssistantId) {
                            it.copy(
                                content = preservedAssistant.content,
                                mood = preservedAssistant.mood,
                                reflection = preservedAssistant.reflection,
                                moodSkin = preservedAssistant.moodSkin,
                                variantGroupId = preservedAssistant.variantGroupId,
                                variantIndex = preservedAssistant.variantIndex,
                                variantCount = preservedAssistant.variantCount,
                                parentGroupId = preservedAssistant.parentGroupId,
                            )
                        } else {
                            it
                        }
                    }
                }
                resultOf {
                    if (!restoreBlocksChatWrites("finalizeWriteAssistantMessage") &&
                        !isSessionWritesSuppressed(sessionId)
                    ) {
                        deps.sessionRepository.upsertMessage(sessionId, preservedAssistant)
                    }
                }
                    .onError { msg, _ -> Logger.w("ChatVM", "onGenerationFinish upsertMessage failed: $msg") }
            }
        }.onError { msg, _ -> Logger.w("ChatVM", "applyOnGenerationFinish failed: $msg") }

        // A5: Generar persistencia de metadatos(provider Uso de token medido + tiempo total invertido),No se bloquea en caso de fallo.
        resultOf {
            if (restoreBlocksChatWrites("finalizeWriteAssistantMeta")) return@resultOf
            val entity = deps.sessionRepository.getMessageById(state.currentAssistantId.toString()) ?: return@resultOf
            val usage = state.usageTokens
            val durationMs = System.currentTimeMillis() - streamStartedAt
            deps.sessionRepository.upsertMessageEntity(
                entity.copy(
                    durationMs = durationMs,
                    promptTokens = usage?.promptTokens,
                    completionTokens = usage?.completionTokens,
                    reasoningTokens = usage?.reasoningTokens,
                    cachedTokens = usage?.cachedTokens,
                    cacheWriteTokens = usage?.cacheWriteTokens,
                ),
            )
            accessor.updateMessages { list ->
                list.map {
                    if (it.id == state.currentAssistantId) {
                        it.copy(
                            durationMs = durationMs,
                            promptTokens = usage?.promptTokens,
                            completionTokens = usage?.completionTokens,
                            reasoningTokens = usage?.reasoningTokens,
                            cachedTokens = usage?.cachedTokens,
                            cacheWriteTokens = usage?.cacheWriteTokens,
                        )
                    } else {
                        it
                    }
                }
            }
        }.onError { msg, _ -> Logger.w("ChatVM", "persist A5 message metadata failed: $msg") }

        // Actualice la ocupación del token de contexto una vez finalizada la transmisión.
        deps.refreshContextInfo()

        // Protección contra desbordamiento de contexto:token Compresión automática en segundo plano cuando la ocupación supera el 80%.
        if (!restoreBlocksChatWrites("finalizeAutoCompress")) {
            resultOf { deps.triggerAutoCompress(sessionId) }
        }

        recordDebugSummary(state, "completed")

        // Notificación:Transmisión completada — enviar"Respuesta completada"Notificación.
        resultOf {
            notificationManager.updateLiveProgress(sessionTitle, 0, false)
            val finalText =
                deps.stateStore.messages.value
                    .firstOrNull { it.id == state.currentAssistantId }?.content.orEmpty()
            val preview = finalText.ifBlank { deps.appContext.getString(R.string.err_chat_reply_generated) }
            val policy = deps.settings.notificationPolicyFlow.first()
            notificationManager.notifyChatCompletedWithPolicy(
                policy = policy,
                sessionTitle = sessionTitle,
                preview = preview,
                target = MuseNotificationTarget.Session(sessionId),
            )
        }.onError { msg, t -> Logger.w("ChatVM", "Error en la notificación de finalización de la transmisión: $msg", t) }

        // Notificación memory ticker(detrás del escenario rollingSummary + daily check).
        // La interfaz de usuario del chat solo puede conservar la página de mensajes más reciente;La canalización de memoria debe leer la sesión completa de la base de datos.,
        // Luego agregue los mensajes de la interfaz de usuario que no se han eliminado en esta ronda.,Evite los hechos iniciales que nunca llegan a ser abstractos./Extracción.
        val uiConversationMessages = deps.stateStore.messages.value
        val dbConversationMessages =
            if (deps.settings.memoryConfigCache.conversationRecallEnabled) {
                resultOf { deps.sessionRepository.getAllMessagesForWarmup(sessionId) }
                    .onError { msg, t -> Logger.w("ChatVM", "notifyTurn full history load failed: $msg", t) }
                    .getOrNull()
                    .orEmpty()
            } else {
                emptyList()
            }
        val dbMessageIds = dbConversationMessages.mapTo(HashSet()) { it.id }
        val conversationMessages =
            if (dbConversationMessages.isEmpty()) {
                uiConversationMessages
            } else {
                dbConversationMessages + uiConversationMessages.filter { it.id !in dbMessageIds }
            }
        val selectedModel = resultOf { deps.settings.getSelectedModel() }.getOrNull()
        val generationAssistantId = state.assistant?.id ?: "default"
        val generationSpaceId =
            (state.transformContext?.extra("current_space") as? String)
                ?.takeIf { it.isNotBlank() }
                ?: deps.settings.currentSpaceIdFlow.firstOrNull().orEmpty().ifBlank { "default" }
        if (!restoreBlocksChatWrites("finalizeNotifyMemory")) {
            runCatching {
                deps.memoryTicker.notifyTurn(
                    sessionId,
                    conversationMessages,
                    selectedModel,
                    assistantId = generationAssistantId,
                    spaceId = generationSpaceId,
                )
            }.onFailure { Logger.w("ChatVM", "notifyTurn failed: ${it.message}") }
        }

        // B5-01/B-23: La construcción termina normalmente.,prensa (sessionId, streamStartedAt) Limpieza precisa de los puntos de control de la generación actual.
        if (!restoreBlocksChatWrites("finalizeClearCheckpoints")) {
            resultOf { deps.sessionRepository.deleteGenerationCheckpoints(sessionId, streamStartedAt) }
                .onError { msg, _ -> Logger.w("ChatVM", "generation checkpoints Error de limpieza: $msg") }
        }
        // R-UI-02: Borre el foco de generación después de la ronda de generación actual.
        if (!restoreBlocksChatWrites("finalizeClearGenerationFocus") &&
            resultOf { deps.settings.getGeneratingSessionId() }.getOrNull() == sessionId
        ) {
            resultOf { deps.settings.saveGeneratingSessionId(null) }
                .onError { msg, _ -> Logger.w("ChatVM", "saveGeneratingSessionId Error de limpieza: $msg") }
        }
        // v2.5.9: Borre el directorio de habilidades registrado en esta sesión para evitar que permanezca después de que finalice la sesión (igual que SessionToolLoadRegistry).
        runCatching { deps.toolRegistry.clearSearchableSkills(sessionId) }
            .onFailure { Logger.w("ChatVM", "clearSearchableSkills falló: ${it.message}") }
        // v2.6.7 fix (AUDIT F12): Notifica al widget de escritorio que se actualice.
        // La implementación anterior solo agrega/Se envió una transmisión durante la configuración y la vista previa del componente permaneció en ese momento durante mucho tiempo.
        // Porque provideGlance de Glance no se volverá a ejecutar.
        runCatching { io.zer0.muse.widget.MuseWidgetRefresher.refreshAll(deps.appContext) }
            .onFailure { Logger.w("ChatVM", "No se pudieron actualizar los widgets del escritorio: ${it.message}") }
    }

    companion object {
        private const val MAX_INPUT_HISTORY = 50
    }
}
