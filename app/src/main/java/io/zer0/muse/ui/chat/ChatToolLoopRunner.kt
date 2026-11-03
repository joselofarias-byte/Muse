package io.zer0.muse.ui.chat
import io.zer0.muse.transformer.ContextBudget
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.ChatStopReason
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.Perf
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.chat.InternalPromptMarkers
import io.zer0.muse.data.artifact.ArtifactExtractor
import io.zer0.muse.data.chat.rewrite.ConversationEventDraft
import io.zer0.muse.data.chat.rewrite.ConversationEventType
import io.zer0.muse.data.chat.rewrite.ConversationRebuildFlagStore
import io.zer0.muse.data.chat.rewrite.MessageCommitRequest
import io.zer0.muse.data.chat.rewrite.buildCommitParts
import io.zer0.muse.data.session.ConversationTurnEntity
import io.zer0.muse.notification.MuseNotificationTarget
import io.zer0.muse.session.WarmupHistory
import io.zer0.muse.tools.SessionToolLoadRegistry
import io.zer0.muse.tools.StreamRoundParams
import io.zer0.muse.tools.StreamRoundResult
import io.zer0.muse.tools.ToolApprovalState
import io.zer0.muse.tools.ToolExposurePolicy
import io.zer0.muse.tools.ToolLoopHost
import io.zer0.muse.tools.ToolLoopParams
import io.zer0.muse.tools.ToolRoundPresentationPolicy
import io.zer0.muse.transformer.CompressionPolicy
import io.zer0.muse.transformer.TransformContext
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.ChatStreamPhase
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.mergeFinalAssistantMedia
import io.zer0.muse.ui.selectSteeringMessages
import io.zer0.muse.util.ErrorMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * v2.6.15: El sello del modelo de salida de esta ronda de mensajes del asistente.
 *
 * El mismo modelo de protección para la reproducción de bloques de pensamiento se basa en este campo para determinar la fuente (ver ProviderPayloadNormalizer.stripCrossModelReasoning):
 * Sin él, los bloques del pensamiento histórico siempre serán considerados como“Fuente desconocida”, el guardia es sólo de nombre.
 *
 * Orden de valor: el modelo realmente utilizado en esta ronda > El sello que ya existe en el mensaje. ¿Por qué tiene prioridad este modelo redondo? El mensaje de marcador de posición al enviar solo se puede usar
 * La selección hecha en ese momento es una estimación. Una vez iniciada la generación real, puede ser diferente debido al cambio de modelo exclusivo del asistente/modelo de rueda de herramientas/respaldo.
 * El contenido de este mensaje se produjo en esa ronda; usar el sello antiguo lo marcaría como otro modelo, pero lo despojaría de su reproducción legítima.
 */
internal fun roundAssistantModelId(roundModel: Model?, existing: UIMessage?): String? =
    roundModel?.id?.takeIf { it.isNotBlank() } ?: existing?.modelId?.takeIf { it.isNotBlank() }

/**
 * Criterios para el desbordamiento del contexto (solicitud más allá de la ventana del modelo).
 *
 * provider Cada empresa utiliza palabras diferentes y las diferentes versiones de la misma empresa también cambiarán su redacción, por lo que los criterios se convierten en "tabla de frases + tabla de códigos de estado":
 * Para agregar una nueva empresa, solo necesita agregar una fila a la tabla y la lógica de juicio no cambia. La entrada es el texto de error detallado por el proveedor.
 * (Se incluyen el código de estado HTTP y el resumen del cuerpo de la respuesta; consulte parseErrorMessage de cada proveedor).
 *
 * No dejarlo ir deliberadamente "too many tokens""token limit"Frases amplias como: limitación actual (TPM) también aparece a menudo en redacción publicitaria,
 * Una vez que se juzga erróneamente como desbordamiento, una solicitud de compresión se desperdiciará durante la limitación actual y el contexto se acortará.
 */
internal object ContextOverflowSignals {
    /** Un solo resultado provocará un desbordamiento (convierta a minúsculas antes de la comparación, de modo que todo en la tabla esté en minúsculas). */
    private val PHRASES: List<String> =
        listOf(
            // OpenAI sistema (incluyendo DeepSeek / Moonshot / la mayoría de las estaciones de transferencia)
            "context_length_exceeded",
            "maximum context length",
            "context length exceeded",
            "reduce the length of the messages",
            // Representación de clase de ventana
            "context window exceeded",
            "context window exceeds",
            "exceeds the context window",
            "exceeds the maximum number of tokens",
            "maximum number of tokens allowed",
            // Anthropic
            "prompt is too long",
            // Longitud de entrada universal
            "input is too long",
            "input length exceeds",
            "range of input length",
            "exceeded model token limit",
            "Entrada demasiado larga",
            "la entrada es demasiado larga",
            // El cuerpo de la solicitud es demasiado grande: el mismo tratamiento que "no se puede acomodar el contexto" (reenviar después de la compresión)
            "request entity too large",
            "request is too large",
            "payload too large",
        )

    /**
     * Los criterios deben aparecer en pares.
     *
     * Los redactores chinos suelen utilizar las palabras "contexto" y "más allá"./"Demasiado largo" separado, cualquier palabra es demasiado amplia (como
     * 「max_tokens Error del parámetro "Excede la longitud del contexto"), por lo que ambos deben aparecer al mismo tiempo.
     */
    private val PAIRS: List<Pair<String, String>> =
        listOf(
            "contexto" to "súper",
            "contexto" to "demasiado tiempo",
            "context" to "too long",
        )

    /** Criterios de código de estado:413 Semánticamente significa \"el cuerpo de la solicitud no cabe\", lo que se trata de la misma manera que el desbordamiento. */
    private val STATUS_PATTERNS: List<Regex> = listOf(Regex("""\bhttp\s+413\b"""))

    /** Si el texto del error es un desbordamiento de contexto.null / Las cadenas vacías siempre se rechazan. */
    fun matches(message: String?): Boolean {
        val text = message?.trim().orEmpty()
        if (text.isEmpty()) return false
        val lower = text.lowercase()
        if (PHRASES.any { lower.contains(it) }) return true
        if (PAIRS.any { (first, second) -> lower.contains(first) && lower.contains(second) }) return true
        return STATUS_PATTERNS.any { it.containsMatchIn(lower) }
    }
}

/**
 * Una puerta de "un solo paso" para la recuperación de desbordamiento de contexto.
 *
 * Solo se permite una "compresión + reenvío" en todo el ciclo de vida de la solicitud: la compresión inevitablemente acortará el historial y la compresión repetida acortará el historial.
 * Al final, todavía golpeé la ventana, pero perdí el contenido real. Por lo tanto, primero ocupe el espacio y luego comprímalo: después de ocupar el espacio, no importa si es un éxito o un fracaso, ya no importará.
 * Inténtelo la segunda vez e informe el error original directamente.
 */
internal class ContextOverflowRecovery {
    /** Si se ha utilizado la oportunidad de recuperación para esta solicitud. */
    var used: Boolean = false
        private set

    /** En caso de intentar la recuperación: copia desbordada + no corte de red + oportunidad aún disponible. */
    fun canRecover(error: String?, streamInterrupted: Boolean): Boolean =
        !used && !streamInterrupted && ContextOverflowSignals.matches(error)

    /** Aproveche la única oportunidad de recuperación para esta solicitud. */
    fun markUsed() {
        used = true
    }
}

/**
 * Recuperación después del desbordamiento: comprime una ronda del historial. Si tiene éxito, el historial comprimido se entregará a la persona que llama para que lo reenvíe.
 *
 * @param compress Acción de compresión real; devuelve nulo si el compresor no pudo liberar espacio (muy pocos mensajes/sin ganancia neta).
 * @return historia comprimida;null Indica abandonar la recuperación y la persona que llama debe informar directamente el error original.
 */
internal suspend fun recoverFromContextOverflow(
    error: String?,
    streamInterrupted: Boolean,
    recovery: ContextOverflowRecovery,
    compress: suspend () -> List<UIMessage>?,
): List<UIMessage>? {
    if (!recovery.canRecover(error, streamInterrupted)) return null
    // Primero ocupe el espacio y luego comprima: la compresión en sí requiere una solicitud de modelo y la oportunidad no se puede devolver incluso si falla.
    recovery.markUsed()
    return compress()
}

@Suppress(
    "LongParameterList", "LongMethod", "ComplexMethod", "TooGenericExceptionCaught",
    "ReturnCount", "CyclomaticComplexMethod", "NestedBlockDepth", "ThrowsCount",
    "TooManyFunctions", "LargeClass", "ComplexCondition", "LoopWithTooManyJumpStatements",
)
internal class ChatToolLoopRunner(
    private val host: ChatToolLoopHostBridge,
) {

    /**
     * Registrador de flujo de uso.
     *
     * Utilice contenedores globales para obtener singletons para evitar cambiar toda la cadena de construcción por una capacidad de derivación;
     * Si no se puede obtener, se omitirá en silencio. Las estadísticas faltantes no afectarán el diálogo.
     */
    private val usageRecorder: io.zer0.muse.data.stats.UsageRecorder? by lazy {
        runCatching {
            org.koin.core.context.GlobalContext.get().get<io.zer0.muse.data.stats.UsageRecorder>()
        }.getOrNull()
    }

    /**
     * Anteriormente ChatViewModel.runToolLoop.
     * Todos los pares de miembros de ViewModel/método de acceso [host] puente,Mantener la equivalencia conductual.
     */
    suspend fun run(state: StreamRunState): Boolean {
        val experiments = state.experiments
        val sessionId = state.sessionId
        val sessionTitle = state.sessionTitle
        val streamStartedAt = state.streamStartedAt
        // B5-01: Generar punto de control — Registre esta ronda de mensajes de usuario y la hora de inicio de generación.,Para la recuperación después de que se finalice el proceso.
        val checkpointUserMessageId = state.conversationHistory.lastOrNull { it.role == MessageRole.USER }?.id?.toString() ?: ""
        val checkpointCreatedAt = streamStartedAt
        val pendingRagCitations = state.pendingRagCitations
        val assistant = state.assistant
        val effectiveModel = state.effectiveModel
        val effectiveProviderConfig = state.effectiveProviderConfig
        // C-12: Configuración del modelo de herramienta(puede ser null)—— solo"rueda de herramientas"(Ronda de continuación del resultado de la última ronda que contiene llamadas a herramientas)uso,
        // El ciclo de recuperación final vuelve al modelo principal. effectiveModel,Evite que el modelo de herramienta degrade el objeto visual del modelo principal a enrutamiento de texto.
        val toolModel = state.toolModel
        val toolProviderConfig = state.toolProviderConfig
        val tools = state.tools
        val effectiveTemperature = state.effectiveTemperature
        val topP = assistant?.topP
        val reasoningLevel = state.reasoningLevel
        val searchConfig = host.stateStore.state.value.webSearchConfig
        // v2.6.10: La búsqueda nativa se cambió a "determinación de capacidad" en lugar de "determinación de tipo".
        // Si se debe ir al punto final de Respuestas está determinado por ProviderSpecificConfig.OpenAI.useResponseApi decidir,
        // Los proveedores preestablecidos como DeepSeek son ProviderType.OPENAI + useResponseApi=true,
        // La solicitud llega a /v1/responses. Resulta que solo se reconoce la enumeración OPENAI_RESPONSES, lo que resulta en este tipo de
        // El modo Automático del proveedor nunca obtendrá la búsqueda nativa y volverá al rastreo local.
        val providerSupportsNativeSearch =
            when (effectiveProviderConfig?.type) {
                ProviderType.GEMINI, ProviderType.OPENAI_RESPONSES -> true
                ProviderType.OPENAI ->
                    // Solo la clase OpenAI con Respuestas habilitadas explícitamente tiene la capacidad web_search_preview.
                    (effectiveProviderConfig?.resolvedSpecific() as? ProviderSpecificConfig.OpenAI)
                        ?.useResponseApi == true
                else -> false
            }
        val nativeWebSearchEnabled =
            searchConfig.enabled &&
                searchConfig.mode in
                setOf(
                    io.zer0.muse.web.WebSearchMode.AUTO,
                    io.zer0.muse.web.WebSearchMode.NATIVE,
                ) && providerSupportsNativeSearch
        val localWebSearchEnabled =
            searchConfig.enabled &&
                searchConfig.mode != io.zer0.muse.web.WebSearchMode.OFF &&
                !nativeWebSearchEnabled
        val conversationHistory = state.conversationHistory
        val unmaskPii: (String) -> String = state::unmaskPii
        // El contexto de ejecución de la herramienta es capturado por el host y el modelo no puede cambiar el alcance/espacio de la memoria a través de parámetros.
        val toolExecutionContext =
            io.zer0.muse.tools.ToolExecutionContext(
                scope = assistant?.id?.takeIf { it.isNotBlank() && it != "default" } ?: "main",
                spaceId = host.settings.currentSpaceIdFlow.firstOrNull().orEmpty().ifBlank { "default" },
                assistantId = assistant?.id,
                // v2.x escenario3:sesión de anfitrión id — find_tools La carga tiene efecto por sesión.
                sessionId = state.sessionId,
                // La identidad de una tarjeta es la identificación del mensaje que la lleva. El modelo no puede obtenerlo y solo puede ser inyectado por el anfitrión. ——
                // De lo contrario, update_card_data no tiene ninguna clave confiable disponible.
                messageId = state.assistantId.toString(),
            )

        // v1.42: UI/notificaciones durante la transmisión/token Actualizar usando personajes+Estrangulamiento de doble umbral de tiempo,Reducir la frecuencia de recombinación.
        // Estas variables solo se usan dentro de streamRound,Compartido entre rondas, reiniciado al reintentar(Consistente con la implementación original).
        var firstTokenTime = 0L // v2.3: Hora de llegada del primer token
        var lastLoggedCharCount = 0
        var lastUiUpdateChars = 0
        var lastUiUpdateAt = streamStartedAt
        var lastReasoningUiUpdateChars = 0
        var lastNotifChars = 0
        var lastNotifAt = streamStartedAt
        var lastTokenUpdateChars = 0
        var lastTokenUpdateAt = streamStartedAt
        // v1.43: Realización periódica de pedidos en modo streaming,Evite los cortes de página/Progreso perdido después del fondo.
        var lastPersistChars = 0
        var lastPersistAt = streamStartedAt
        // v1.0.4: Estado de corte adaptativo — solo para ContentDelta(flujo de texto)hacer efecto,No afecta a Reasoning/ToolCall.
        // pendingBuilder Acumular no enviar a la interfaz de usuario delta,50ms Presione cuando se active la aceleración computeAdaptiveSlice() Toma rodajas.
        val pendingBuilder = StringBuilder()
        val chunkIntervals = ArrayDeque<Long>(STREAM_SLIDE_WINDOW)
        var lastChunkAt = 0L
        // Oportunidad de recuperación de desbordamiento de contexto: solicitud completa(Incluye todas las ruedas de herramientas y reintentos internos.)Sólo se permite su uso una vez.
        // Se coloca en el ámbito de ejecución en lugar de en los parámetros porque los reintentos recursivos de streamRound caerán aquí.
        // No es necesario pasar manualmente cada capa y no se restablecerá debido a los reintentos.
        val overflowRecovery = ContextOverflowRecovery()

        /**
         * v1.0.4: Según la velocidad de transmisión(media celular)Calcule la cantidad de caracteres que deben generarse durante esta limitación de 50 ms.
         *
         * - Cuanto mayor sea el intervalo(flujo lento,Como por ejemplo Reasoning)→ rate el mas pequeño → Cuanto más pequeña sea la porción(de grano fino,más pequeño 2)
         * - Cuanto menor sea el intervalo(flujo rápido,texto plano)→ rate cuanto mas grande → Cuanto más grande sea la porción(lote,máximo 240)
         * - Devuelve el valor base cuando no hay muestras. STREAM_SLICE_BASE
         */
        fun computeAdaptiveSlice(currentLength: Int): Int {
            // v2.0: La frecuencia de actualización durante la etapa de texto largo se ralentiza,Amplificación de sincronización de cortes,Asegúrese de que la velocidad de entrada en la pantalla no disminuya.
            val flushScale = host.streamFlushIntervalMs(currentLength).toDouble() / STREAM_THROTTLE_MS.toDouble()
            if (chunkIntervals.isEmpty()) {
                return (STREAM_SLICE_BASE * flushScale).toInt().coerceIn(STREAM_SLICE_MIN, STREAM_SLICE_MAX)
            }
            val avgInterval = chunkIntervals.average().toLong()
            // rate = 50ms / avgInterval:intervalo 50ms → rate=1;intervalo 500ms → rate=0.1;intervalo 5ms → rate=10
            val rate = (50.0 / maxOf(1L, avgInterval)).coerceIn(0.1, 10.0)
            return (STREAM_SLICE_BASE * rate * flushScale).toInt().coerceIn(STREAM_SLICE_MIN, STREAM_SLICE_MAX)
        }

        // Phase 2: El bucle de llamada a la herramienta se hunde en ToolOrchestrator
        // v1.135: Grabar el mensaje actual del asistente id,Para que las herramientas de generación de medios actualicen la interfaz de usuario del mensaje.
        // A-13: Incrementar el token generacional al iniciar una nueva ronda de generación,Invalidar las escrituras de medios realizadas por la ronda anterior de herramientas en vuelo.
        host.generationState.toolAssistantId = state.currentAssistantId
        host.generationState.activeToolSessionId = state.sessionId
        host.nextToolGenerationToken()
        // C-17: Se borra el formulario de registro de medios de esta generación.(Los ID heredados de la generación anterior no participarán al final de esta generación.)
        host.generationState.toolMediaMessages.clear()
        val baseHistorySize = conversationHistory.size
        // v2.x: La ocupación de la definición de herramientas se cuenta una vez en esta ronda de generación y se guarda. —— Lista de herramientas ([tools]) permanece sin cambios durante toda la ronda,
        // El orquestador preguntará dos veces por ronda. contextBudgetTokens(), es un puro desperdicio revisar la codificación BPE del esquema cada vez.
        // TokenEstimator.estimateToolDefinitions Hay otra capa de caché basada en las huellas digitales del contenido del interior. Esta es la segunda capa.
        // Si no se puede obtener, se contará como 0, lo cual es consistente con el comportamiento original (el presupuesto sería un poco más amplio que no calculado).
        var toolDefinitionTokensCache: Int? = null
        // B10: La transmisión de desmontaje en tiempo real de ThinkTag está conectada — punto de suspensión en flushPendingToUi(Actualizaciones de la interfaz de usuario activadas por la limitación),
        // Hecho por updateAssistantWithVisualTransform: primero O(n) contains("<think>") cortocircuito,
        // La función pura de la capa transformadora se llama solo cuando se golpea. splitThinkTagsForStreaming(ver ThinkTagTransformer.kt),
        // Envíe el contenido/razonamiento eliminado a updateAssistant. Ruta de transmisión normal sin etiquetas
        // Cero cambios de comportamiento,50ms / El mecanismo de regulación de cortes adaptativo permanece sin cambios.
        val toolLoopHost =
            object : ToolLoopHost {
                /**
                 * v2.x: Presupuesto de contexto disponible para esta ronda = ventana − La cantidad de reserva y el calibre son consistentes con el disparador de compresión automático.
                 * (Ver [shouldAutoCompress]). Los anfitriones solo se proporcionan aquí"cuanto queda disponible",
                 * El orquestador realiza una reconstrucción local sin solicitud basada en esto para evitar que la ventana se bloquee durante la generación.
                 */
                override fun contextBudgetTokens(): Int {
                    val window = host.stateStore.state.value.contextMaxTokens
                    if (window <= 0) return 0
                    val toolTokens =
                        toolDefinitionTokensCache
                            ?: runCatching {
                                io.zer0.muse.util.TokenEstimator.estimateToolDefinitions(tools)
                            }.getOrDefault(0).also { toolDefinitionTokensCache = it }
                    // Comparte el mismo conjunto de desmontaje que el gatillo de compresión: la reserva de salida y el margen de seguridad deben descontarse juntos.
                    // De lo contrario, creo que todavía se puede bloquear aquí, pero ha comenzado a comprimirse allí y habrá dos juicios en la misma ronda.
                    // La definición del instrumento se calcula en función del lote que realmente se emite en esta ronda ([tools] = definiciones en instantáneas de enrutamiento,
                    // Se reducirá según la situación de acierto en cada ronda, por lo que es el límite superior de la cantidad de envío real); si no lo sacas, la cuota será sistemáticamente más amplia.
                    // No hay posibilidad de compresión si la cadena de herramientas golpea la ventana. Los mensajes del sistema aún transmiten 0:system la noticia esta ahi
                    // conversationHistory Aquí, el arreglador se ha calculado según el historial y se repetirá si se deduce nuevamente.
                    return ContextBudget.compute(
                        contextWindow = window,
                        systemTokens = 0,
                        toolTokens = toolTokens,
                        maxOutputTokens = effectiveModel?.maxOutputTokens ?: 0,
                    ).historyBudget
                }

                /**
                 * Canal de interrupción: entregue las palabras que el usuario puso en la cola para ser enviadas durante la generación, y el orquestador las
                 * Insertar como mensaje de USUARIO antes de la siguiente ronda de solicitud —— El usuario no tiene que esperar a que se complete toda la ejecución ni presionar Detener
                 * Desecha todo el trabajo que ya has hecho.
                 *
                 * Reglas de recogida (es mejor omitir que recoger por error):
                 *  - tomar solo [io.zer0.muse.ui.PendingMessage.sessionId] Entradas consistentes con la sesión actual.
                 *    Los mensajes en cola que pertenecen a otras conversaciones se eliminan, lo que equivale a insertar palabras que el usuario no ha dicho en esa conversación.
                 *    Y se convertirá en la base para el razonamiento posterior del modelo. —— Esto es contaminación de datos, no un problema de latencia.
                 *    sessionId es nulo (datos antiguos/Tampoco se toma ninguna sesión al unirse a la cola).
                 *  - Además, es necesario que se muestre la sesión actual. El contenido puesto en cola después de que el usuario cambia a otra sesión no pertenece a esta ejecución.
                 *    Quédese en la cola y espere a que él mismo lo envíe.
                 *  - Obtenga solo entradas de texto sin formato. Las entradas con imágenes siguen pasando por la cola original: los medios tienen que pasar por el canal de envío
                 *    Soltar y montar enlaces, la segunda implementación aquí soltará archivos adjuntos silenciosamente.
                 *  - Retire y realice el pedido + push UI, la interrupción es un mensaje de usuario real en la sesión; de lo contrario vuelva a entrar
                 *    La conversación verá al asistente responder a una frase que no existe.
                 */
                override suspend fun drainSteeringMessages(): List<String> {
                    val snap = host.accessor.snapshot
                    val displayed =
                        if (snap.isAgentMode) snap.agentSessionId == sessionId else snap.currentSessionId == sessionId
                    if (!displayed) return emptyList()
                    val steering = selectSteeringMessages(snap.sendQueue, sessionId)
                    if (steering.isEmpty()) return emptyList()
                    host.accessor.update { it.copy(sendQueue = it.sendQueue - steering.toSet()) }
                    for (item in steering) {
                        val msg = UIMessage(role = MessageRole.USER, content = item.text)
                        runCatching { host.sessionRepository.appendMessage(sessionId, msg) }
                            .onFailure { Logger.w("ChatVM", "Error al colocar el mensaje de interposición: ${it.message}") }
                        host.accessor.updateMessages { list -> list + msg }
                    }
                    host.messageController.rebuildConversationTree()
                    return steering.map { it.text }
                }

                override suspend fun streamRound(params: StreamRoundParams): StreamRoundResult {
                    val round = params.round
                    // Client-side tools necessarily use follow-up provider requests so the model can
                    // incorporate tool results. Keep reasoning visible for every round when the
                    // user enabled it; providers that do not support reasoning still legitimately
                    // produce no reasoning delta.
                    val exposeRoundThinking = ToolRoundPresentationPolicy.exposeReasoning(round)
                    // A-14: Al comienzo de cada ronda, el ID del mensaje del titular de la ronda actual se sincroniza nuevamente state — Interrumpir la recuperación(catch bloquear)depender de
                    // state.currentAssistantId Posicionamiento"Genera mensajes en esta ronda",Anteriormente, solo se actualizaba en runToolLoop.,
                    // Apunta a la ronda anterior cuando se cancela la mitad del ciclo de la herramienta de rondas múltiples,causa [interrumpido] Noticias equivocadas.
                    state.currentAssistantId = params.currentAssistantId
                    if (!state.shadowTurnStarted && ConversationRebuildFlagStore.current.shadowEventsEnabled) {
                        state.shadowTurnStarted = true
                        val now = System.currentTimeMillis()
                        host.conversationService.startTurn(
                            ConversationTurnEntity(
                                turnId = state.turnId,
                                sessionId = sessionId,
                                inputUserMessageId = checkpointUserMessageId,
                                assistantMessageId = params.currentAssistantId.toString(),
                                phase = "STREAMING",
                                streamId = state.streamId,
                                generationSerial = state.generationSerial,
                                startedAt = state.streamStartedAt,
                                updatedAt = now,
                            ),
                        )
                        host.recordConversationShadow(
                            ConversationEventDraft(
                                sessionId = sessionId,
                                turnId = state.turnId,
                                type = ConversationEventType.ASSISTANT_STARTED,
                                streamId = state.streamId,
                                generationSerial = state.generationSerial,
                                payloadJson = "{\"assistantMessageId\":\"${params.currentAssistantId}\"," +
                                    "\"userMessageId\":\"$checkpointUserMessageId\"",
                            ),
                        )
                    }
                    // Revisión de corrección (2.0 A-02): Destino de escritura de medios de la herramienta de sincronización — host.generationState.toolAssistantId Originalmente sólo en
                    // runToolLoop El punto de partida se asigna una vez.,Desde la ronda 2 en adelante exec* Todos los medios retrocedieron a la primera ronda de ocupaciones de posiciones. A0,
                    // y el texto/La tarjeta de herramientas está en A1/A2;Aquí cada ronda de entrada está sincronizada.,Haz que esta herramienta redonda se ejecute(awaitAll
                    // Completado antes de la siguiente ronda de streamRound)Los medios escriben el mensaje marcador de posición para esta ronda.
                    host.generationState.toolAssistantId = params.currentAssistantId
                    // B5-03: Firma de múltiples rondas de pensamiento./Acumulación de contenido cifrado,Finalmente escribe el mensaje del asistente.
                    var thinkingSignature: String? = null
                    var thinkingEncryptedContent: String? = null
                    // B3-03: Reanudar la transmisión después de la desconexión para eliminar duplicados — Omitir prefijos que sean duplicados de lo que ya se muestra delta,Evite que los usuarios vean texto duplicado
                    var duplicateRemaining: String? = if (params.preservePartialContent) params.builder.toString() else null
                    var firstContentDeltaSeen = false
                    // Gestión de estadísticas de uso: hora de inicio de esta ronda de solicitudes y hora de llegada de la primera palabra.
                    // firstTokenMs Es la percepción del usuario.“rápido o lento”El indicador más directo es más significativo que el tiempo total invertido.
                    val roundStartedAt = System.currentTimeMillis()
                    var firstTokenAt = 0L
                    // v2.0: Reanudar la detección de reescritura — El texto completo acumulado en esta ronda de intentos(Contiene prefijos que la deduplicación omite) + Longitud original.
                    // Cuando el proveedor está reanudando la descarga/Al volver a intentarlo, reescribir desde cero y superponer un área grande con el contenido original.,
                    // Reemplace el contenido antiguo con una nueva ronda de texto completo,En lugar de agregar contenido nuevo al final del artículo anterior(Eso también“repetir respuesta”fuente principal de).
                    var resumeAttemptText: StringBuilder? = if (params.preservePartialContent) StringBuilder() else null
                    val resumeDuplicateTotal = duplicateRemaining?.length ?: 0
                    // v2.0: Instantánea del texto original antes de reanudar la carga(Done escenario“El nuevo texto contiene el comienzo del texto original.”Se utiliza para determinar el fondo del canje.)
                    val resumeOriginalText = duplicateRemaining?.takeIf { it.isNotEmpty() }
                    // v1.0.17: preservePartialContent=true salto de tiempo clear,Conservar parte del contenido recibido por StreamInterrupted
                    if (!params.preservePartialContent) {
                        params.builder.clear()
                        params.reasoningBuilder.clear()
                    } else {
                        Logger.i("ChatVM", "streamRound retry with preservePartialContent, keep ${params.builder.length} chars")
                    }

                    // B5-01: Escribe y genera puntos de control al comienzo de cada ronda.,Asegúrese de que la salida de streaming sea duradera
                    host.persistCheckpointAndReleaseOutbox(
                        state = state,
                        sessionId = sessionId,
                        userMessageId = checkpointUserMessageId,
                        assistantMessageId = params.currentAssistantId.toString(),
                        content = unmaskPii(params.builder.toString()),
                        createdAt = checkpointCreatedAt,
                        // La eliminación de la bandeja de salida solo ocurre después de que el asistente confirma que el punto de control fue exitoso.
                    )

                    // C-12: Selección de modelo para esta ronda. — solo"rueda de herramientas"(Los resultados de la ronda anterior incluyen toolCalls → round>1)usar toolModel,
                    // primera ronda(La respuesta final podrá emitirse directamente y será necesaria la lectura visual de la imagen.)Utilice siempre el modelo principal para las respuestas finales posteriores.,
                    // Evite que todas las rondas se degraden a enrutamiento de texto sin formato después de habilitar la herramienta y configurar el modelo de herramienta.
                    // No configurado toolModel(state.toolModel==null)En este momento, el principal modelo de movimiento constante de esta rama,El comportamiento predeterminado permanece sin cambios;
                    // Si la rueda de herramientas tiene una imagen en el historial pero el modelo de herramienta no tiene visión,También revierta el modelo principal para evitar agregar la imagen 400 a los modelos que no admiten visión.
                    // Revisión de corrección (2.0 A-01): forceMainModel conjunto(Recurrencia de rueda suplementaria del modelo principal)cortocircuito isToolRound,
                    // Prevenir la recursividad de la ronda secundaria;La comprobación de respuesta final de la rueda de herramientas se produce al final del turno.
                    val isToolRound = !params.forceMainModel && params.round > 1
                    val usesToolModel =
                        isToolRound && toolModel != null &&
                            params.history.let { host.canUseToolModelForRound(it, toolModel) }
                    val roundModel = if (usesToolModel) toolModel else effectiveModel
                    val roundProviderConfig = if (usesToolModel) toolProviderConfig else effectiveProviderConfig
                    val latestUserText =
                        params.history
                            .lastOrNull { it.role == MessageRole.USER }
                            ?.content
                            .orEmpty()
                    // La primera ronda de solicitudes de herramientas solo necesita completar el juicio de intención.;La ronda de continuación después de completar los resultados de la herramienta solo necesita leer los resultados y finalizar.
                    // Cerrar un pensamiento profundo y repetido sobre estos dos tipos de solicitudes.,Reserve el pensamiento profundo iniciado por los usuarios para preguntas y respuestas ordinarias y complejas.
                    val roundReasoningLevel =
                        when {
                            params.forceMainModel -> reasoningLevel
                            params.round > 1 ->
                                ToolRoundPresentationPolicy.reasoningLevelForRound(
                                    configured = reasoningLevel,
                                    round = params.round,
                                )
                            // v2.0: Sólo acciones solicitadas explícitamente por el usuario.(intención instrumental)Sólo entonces podremos comprimir esta ronda de pensamiento./Presupuesto de producción;
                            // oraciones cortas ordinarias(Como por ejemplo“Hola”)No más bajas por error,El pensamiento profundo habilitado por el usuario sigue vigente.
                            tools.isNotEmpty() && ToolExposurePolicy.isDirectToolRequest(latestUserText, tools) ->
                                ToolRoundPresentationPolicy.reasoningLevelForDirectTool(
                                    configured = reasoningLevel,
                                    supportsReasoning = roundModel?.supportsReasoning() == true,
                                )
                            else -> reasoningLevel
                        }
                    val configuredMaxTokens = assistant?.maxTokens?.takeIf { it > 0 }
                    val roundMaxTokens =
                        when {
                            params.forceMainModel -> configuredMaxTokens
                            params.round > 1 -> {
                                // v2.x: Solución presupuestaria de la ronda de continuación — El límite superior original de 1024 debe truncarse en dos tipos de escenarios:
                                //   ① Una vez completados los resultados de la herramienta, el modelo debe generar contenido extenso.(escribir archivo/respuesta larga);
                                //   ② El modelo de inferencia se consume con sólo pensarlo 2-4K token, 1024 Casi no hay espacio debajo del texto.
                                // Segunda ronda elevada a 16384: resultado final después de rellenar los resultados de herramientas largas(Después de leer el archivo, escriba el análisis.)Requiere suficiente espacio.
                                val base = (configuredMaxTokens ?: 16_384).coerceAtMost(16_384)
                                io.zer0.memory.budget.LlmBudget.withReasoningHeadroom(base, roundModel)
                            }
                            tools.isNotEmpty() && ToolExposurePolicy.isDirectToolRequest(latestUserText, tools) ->
                                if (roundModel?.supportsReasoning() == true) {
                                    // v2.x: La rueda de herramientas necesita generar parámetros completos de la herramienta.(puede ser muy largo, Como escribir archivos), El 1536 original puede cambiar parámetros.;
                                    // Segunda ronda de aumentos 16384 — Pensando en modelos de inferencia+parámetro largo, Debería haber suficiente espacio
                                    configuredMaxTokens?.coerceAtMost(16_384) ?: 16_384
                                } else {
                                    // v2.x: El 512 original también reducirá los parámetros de longitud., regular al alza 8192
                                    configuredMaxTokens?.coerceAtMost(8_192) ?: 8_192
                                }
                            else ->
                                configuredMaxTokens?.let {
                                    io.zer0.memory.budget.LlmBudget.withReasoningHeadroom(it, roundModel)
                                }
                        }
                    val disableTools = ToolExposurePolicy.shouldDisableTools(latestUserText)
                    val toolChoice =
                        when {
                            params.forceFinalResponse || disableTools -> "none"
                            params.round == 1 &&
                                !params.forceMainModel &&
                                ToolExposurePolicy.shouldRequireTool(latestUserText, tools) -> "required"
                            else -> null
                        }
                    // ToolOrchestrator Después de que falle la solicitud nativa, este campo se reducirá a falso.
                    // La siguiente solicitud de la misma ronda llegará al usuario. API / Bing HTTP / HTTP de Baidu.
                    val nativeSearchForRound =
                        params.nativeWebSearch && params.round == 1 && !params.forceFinalResponse
                    val streamToUi =
                        host.stateStore.state.value.chatPreferences.streamResponse &&
                            (assistant?.streamOutput ?: true)
                    val defaultRoundMode =
                        if (roundReasoningLevel == ReasoningLevel.OFF) {
                            ChatRequestMode.UTILITY
                        } else {
                            ChatRequestMode.CHAT
                        }

                    suspend fun requestRoundFlow(
                        requestedToolChoice: String?,
                        requestedReasoningLevel: ReasoningLevel,
                        requestedMode: ChatRequestMode,
                    ): Flow<ChatStreamEvent> {
                        // v2.0 Reducción de palabras clave de solicitud simple → v2.x escenario1 Actualizar a niveles predeterminados:
                        //   CORE/STANDARD Hengfa;OPTIONAL/GLOBAL Golpes de la familia + rigidez de la sesión(herramientas utilizadas)
                        //   + autorización de sesión("Esta sesión permite") + find_tools Liberación de carga dinámica;GLOBAL señorita/No publicar sin autorización.
                        val stickyToolNames = host.collectStickyToolNames(params.history)
                        val authorizedToolNames = host.sessionPermissionStore.allowedToolsThisSession(state.sessionId)
                        val loadedToolNames = SessionToolLoadRegistry.loadedFor(state.sessionId)
                        // Las habilidades no están clasificadas en ToolCategories,Estrecho con el mismo diámetro que OPCIONAL(ver filterToolsForRequest);
                        // Antes de limitarse, cada habilidad activada se emitirá en cada ronda.,Es la mayor fuente de marcadores de posición de esquemas de herramientas.
                        val skillToolNames = state.skillMap.keys
                        val requestTools =
                            ToolExposurePolicy
                                .filterToolsForRequest(
                                    latestUserText,
                                    tools,
                                    stickyToolNames,
                                    authorizedToolNames,
                                    loadedToolNames,
                                    skillToolNames,
                                )
                                .takeUnless { disableTools || nativeSearchForRound || params.forceFinalResponse }
                                ?: emptyList()
                        val resumeText =
                            params.builder.toString()
                                .takeIf { params.preservePartialContent && it.isNotBlank() }

                        @Suppress("TooGenericExceptionCaught")
                        suspend fun completeTextEvents(): List<ChatStreamEvent> = try {
                            val completion =
                                host.chatService.completeText(
                                    messages =
                                    if (resumeText != null) {
                                        params.history + UIMessage(role = MessageRole.ASSISTANT, content = resumeText)
                                    } else {
                                        params.history
                                    },
                                    model = roundModel,
                                    providerConfig = roundProviderConfig,
                                    tools = requestTools,
                                    toolChoice = requestedToolChoice,
                                    nativeWebSearch = nativeSearchForRound,
                                    temperature = effectiveTemperature,
                                    topP = topP,
                                    maxTokens = roundMaxTokens,
                                    reasoningLevel = requestedReasoningLevel,
                                    mode = requestedMode,
                                    sessionId = sessionId,
                                )
                            completionToStreamEvents(completion)
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            throw ce
                        } catch (error: Exception) {
                            listOf(
                                ChatStreamEvent.Error(
                                    message = error.message ?: "Error en la solicitud sin transmisión",
                                    throwable = error,
                                ),
                            )
                        }
                        val currentTimeIncluded =
                            params.history.any { message ->
                                message.role == MessageRole.SYSTEM &&
                                    message.content.contains(InternalPromptMarkers.TIME_SECTION_PREFIX)
                            }
                        Logger.d(
                            "ChatVM",
                            "provider round request | round=${params.round}" +
                                " | systemMessages=${params.history.count { it.role == MessageRole.SYSTEM }}" +
                                " | currentTime=$currentTimeIncluded" +
                                " | tools=${requestTools.size} | toolChoice=${requestedToolChoice ?: "auto"}",
                        )
                        return if (streamToUi) {
                            host.chatService.streamChat(
                                messages = params.history,
                                model = roundModel,
                                providerConfig = roundProviderConfig,
                                // “No llames a las herramientas”Cerrado duro por la capa de solicitud; No depende del modelo para obedecer la palabra indicada.
                                tools = requestTools,
                                toolChoice = requestedToolChoice,
                                nativeWebSearch = nativeSearchForRound,
                                temperature = effectiveTemperature,
                                topP = topP,
                                maxTokens = roundMaxTokens,
                                reasoningLevel = requestedReasoningLevel,
                                mode = requestedMode,
                                resumeFromText = resumeText,
                                sessionId = sessionId,
                            )
                        } else {
                            // Cuando la salida de streaming está desactivada, se siguen utilizando los mismos parámetros de solicitud, pero completeText se utiliza para realizar una solicitud única.
                            // Compensa el texto del asistente mostrado en el escenario de transmisión del currículum, manteniendo el mismo contexto que streamChat.
                            flow {
                                completeTextEvents().forEach { emit(it) }
                            }
                        }
                    }
                    val imageAccumulator = mutableListOf<String>()
                    val citationUrls = linkedSetOf<String>()
                    val toolCallAccumulator = mutableMapOf<Int, Triple<String?, String?, StringBuilder>>()
                    var streamError: String? = null
                    var doneFinishReason: String? = null
                    // v1.0.15: StreamInterrupted logotipo — La red fue interrumpida después de recibir algún contenido.,
                    //   Espere el evento de recuperación de red de NetworkMonitor y vuelva a intentarlo(no fijo delay),Evite los reintentos ciegos que fallan inmediatamente
                    var streamInterrupted = false
                    // v1.0.17: StreamInterrupted el original throwable,Se utiliza para determinar si hay un error de red.(IOException)
                    var streamInterruptedThrowable: Throwable? = null
                    val pendingFlushMutex = Mutex()

                    suspend fun flushPendingToUi(force: Boolean = false) {
                        if (!streamToUi) return
                        pendingFlushMutex.withLock {
                            if (pendingBuilder.isEmpty()) return@withLock
                            val now = System.currentTimeMillis()
                            if (!force && now - lastUiUpdateAt < host.streamFlushIntervalMs(params.builder.length)) return@withLock
                            val sliceLength =
                                if (force) {
                                    pendingBuilder.length
                                } else {
                                    minOf(computeAdaptiveSlice(params.builder.length), pendingBuilder.length)
                                }
                            params.builder.append(pendingBuilder.substring(0, sliceLength))
                            pendingBuilder.delete(0, sliceLength)
                            host.updateAssistantWithVisualTransform(
                                params.currentAssistantId,
                                unmaskPii(params.builder.toString()),
                                isStreaming = true,
                            )
                            state.uiFlushCount++
                            lastUiUpdateChars = params.builder.length
                            lastUiUpdateAt = now
                        }
                    }

                    // P2-2: reasoning-only fluir(Ninguno ContentDelta)También debe colocarse periódicamente.
                    // Entrada incremental unificada — ContentDelta y ReasoningDelta persisten a través de esta limitación,
                    // evitar"Sólo pensando en el contenido"Después de que la corriente es eliminada por la fuerza, sólo quedan puntos de control vacíos.
                    suspend fun throttledPersist() {
                        val now = System.currentTimeMillis()
                        if (params.builder.length - lastPersistChars < PERSIST_CHAR_THRESHOLD &&
                            now - lastPersistAt < PERSIST_TIME_THRESHOLD_MS
                        ) {
                            return
                        }
                        lastPersistChars = params.builder.length
                        lastPersistAt = now
                        host.chatGenerationManager.touch(sessionId)
                        val persistMsg =
                            host.stateStore.messages.value
                                .firstOrNull { it.id == params.currentAssistantId }
                                ?.copy(
                                    content = unmaskPii(params.builder.toString()),
                                    reasoning = unmaskPii(params.reasoningBuilder.toString()).ifBlank { null },
                                    thinkingSignature = thinkingSignature,
                                    thinkingEncryptedContent = thinkingEncryptedContent,
                                )
                                ?: UIMessage(
                                    id = params.currentAssistantId,
                                    role = MessageRole.ASSISTANT,
                                    content = unmaskPii(params.builder.toString()),
                                    reasoning = unmaskPii(params.reasoningBuilder.toString()).ifBlank { null },
                                    thinkingSignature = thinkingSignature,
                                    thinkingEncryptedContent = thinkingEncryptedContent,
                                )
                        // B-1: Si la sesión se ha eliminado, se omitirán las ubicaciones periódicas y los puntos de control.,Prevenir la eliminación"resurrección".
                        if (!host.generationController.isSessionWritesSuppressed(sessionId)) {
                            host.persistCurrentAssistant(sessionId, params.currentAssistantId, persistMsg)
                            host.persistCheckpointAndReleaseOutbox(
                                state = state,
                                sessionId = sessionId,
                                userMessageId = checkpointUserMessageId,
                                assistantMessageId = params.currentAssistantId.toString(),
                                content = unmaskPii(params.builder.toString()),
                                createdAt = checkpointCreatedAt,
                            )
                        }
                    }

                    var compatibilityRetryUsed = false
                    var retryWithoutToolChoice = false
                    coroutineScope {
                        // La lógica anterior solo verifica la limitación de 50 ms cuando se recibe el siguiente token;
                        // Si el flujo ascendente se detiene brevemente,El constructor pendiente de cola se mantendrá hasta que esté listo y luego se eliminará de inmediato.
                        // La rutina de actualización independiente permite que la interfaz de usuario se actualice de manera estable a tiempo,No cambia el flujo de la red ni el orden de ejecución de las herramientas.
                        val pendingFlushJob =
                            launch {
                                while (isActive) {
                                    delay(host.streamFlushIntervalMs(params.builder.length))
                                    flushPendingToUi()
                                }
                            }
                        try {
                            do {
                                retryWithoutToolChoice = false
                                val requestToolChoice = if (compatibilityRetryUsed) null else toolChoice
                                val requestReasoningLevel =
                                    if (compatibilityRetryUsed) {
                                        ReasoningLevel.OFF
                                    } else {
                                        roundReasoningLevel
                                    }
                                val requestMode =
                                    if (compatibilityRetryUsed) {
                                        ChatRequestMode.UTILITY
                                    } else {
                                        defaultRoundMode
                                    }
                                val flow =
                                    requestRoundFlow(
                                        requestedToolChoice = requestToolChoice,
                                        requestedReasoningLevel = requestReasoningLevel,
                                        requestedMode = requestMode,
                                    )
                                flow.takeWhile { event ->
                                    val hasMeaningfulOutput =
                                        params.builder.isNotEmpty() ||
                                            params.reasoningBuilder.isNotEmpty() ||
                                            pendingBuilder.isNotEmpty() ||
                                            imageAccumulator.isNotEmpty() ||
                                            toolCallAccumulator.isNotEmpty()
                                    val shouldRetry =
                                        event is ChatStreamEvent.Error &&
                                            shouldRetryToolChoiceCompatibility(
                                                message = event.message,
                                                toolChoice = requestToolChoice,
                                                retryUsed = compatibilityRetryUsed,
                                                hasMeaningfulOutput = hasMeaningfulOutput,
                                            )
                                    if (shouldRetry) {
                                        compatibilityRetryUsed = true
                                        retryWithoutToolChoice = true
                                        Logger.w(
                                            "ChatVM",
                                            "thinking mode No compatible tool_choice=required,Cierra esta ronda de pensamiento y vuelve a intentarlo.",
                                        )
                                    }
                                    !shouldRetry
                                }.collect { event ->
                                    when (event) {
                                        is ChatStreamEvent.ContentDelta -> {
                                            // Punto de la primera palabra (solo se registra la primera vez): la hora de llegada de la primera palabra vista por el usuario en modo streaming.
                                            if (firstTokenAt == 0L && event.delta.isNotEmpty()) {
                                                firstTokenAt = System.currentTimeMillis()
                                            }
                                            // C-11: La poda conservadora se utiliza para eliminar duplicados en transmisiones continuas. Sólo se descarta el delta completamente repetido;
                                            // Si un delta se bifurca a la mitad del prefijo antiguo, el prefijo común puede ser simplemente un comienzo coincidente del nuevo contenido,
                                            // Se debe conservar el párrafo completo para evitar que se trague la primera letra real del usuario.
                                            var effectiveDelta = event.delta
                                            if (experiments.debugMode && !firstContentDeltaSeen && event.delta.isNotEmpty()) {
                                                firstContentDeltaSeen = true
                                                Logger.d(
                                                    "ChatVM-Debug",
                                                    "first content delta | sessionId=$sessionId | round=$round | " +
                                                        "preservePartial=${params.preservePartialContent} | " +
                                                        "deltaChars=${event.delta.length} | " +
                                                        "firstCodeUnit=${event.delta[0].code} | " +
                                                        "builderBefore=${params.builder.length} | " +
                                                        "duplicateRemaining=${duplicateRemaining?.length ?: 0}",
                                                )
                                            }
                                            // v2.0: Acumulación del texto completo de intentos de transferencia continuados(Contiene prefijos que se omitieron como duplicados);
                                            // delta golpe de escenario“reescribir desde cero”o Hecho hit en el escenario“El nuevo texto contiene el comienzo del texto original.”Úselo para reemplazar el contenido anterior.
                                            resumeAttemptText?.append(event.delta)
                                            val duplicate = duplicateRemaining
                                            if (duplicate != null) {
                                                val drop = host.resumeOverlapToDrop(duplicate, event.delta)
                                                if (drop == 0) {
                                                    val consumedChars = resumeDuplicateTotal - duplicate.length
                                                    val restartedWithOverlap =
                                                        host.shouldReplaceOnResumeRewrite(
                                                            duplicateTotal = resumeDuplicateTotal,
                                                            consumedChars = consumedChars,
                                                        )
                                                    if (restartedWithOverlap) {
                                                        val rewritten = resumeAttemptText?.toString().orEmpty()
                                                        if (rewritten.isNotEmpty()) {
                                                            params.builder.setLength(0)
                                                            params.builder.append(rewritten)
                                                            lastUiUpdateChars = params.builder.length
                                                            lastNotifChars = params.builder.length
                                                            lastPersistChars = params.builder.length
                                                            Logger.i(
                                                                "ChatVM",
                                                                "resume rewrite detected, replace content | " +
                                                                    "consumed=$consumedChars/$resumeDuplicateTotal | " +
                                                                    "new=${rewritten.length} chars",
                                                            )
                                                        }
                                                        effectiveDelta = ""
                                                    }
                                                    // No se puede demostrar que todo el delta sea contenido de repetición, por lo que la deduplicación se detendrá a partir de esta ronda.
                                                    duplicateRemaining = null
                                                } else {
                                                    val remaining = duplicate.substring(drop).takeIf { it.isNotEmpty() }
                                                    duplicateRemaining = remaining
                                                    if (drop < event.delta.length) {
                                                        // El prefijo antiguo completo se reproduce al comienzo de este delta, siendo la segunda mitad el contenido nuevo.
                                                        effectiveDelta = event.delta.substring(drop)
                                                    } else {
                                                        // Todo el delta se encuentra dentro del contenido mostrado. → Ignora esta vez,Esperando seguimiento delta
                                                        return@collect
                                                    }
                                                }
                                            }
                                            // v1.0.3: El primer token se actualiza inmediatamente. UI,eliminar"loading → mucho texto"falla visual
                                            val isFirstToken = firstTokenTime == 0L
                                            if (isFirstToken) {
                                                firstTokenTime = System.currentTimeMillis()
                                                // P3-9: La primera palabra requiere mucho tiempo para enterrar. — El intervalo desde el inicio de esta ronda de generación hasta el primer token de texto.
                                                Perf.log("chat-first-token", firstTokenTime - streamStartedAt)
                                                // Limpiar ahora"Esperando el primero token"Estado,ThinkingBubble desaparecer,StreamingCursor hacerse cargo
                                                // F-10: Llega la primera ficha → STREAMING
                                                host.stateStore.state.update {
                                                    it.copy(
                                                        isWaitingFirstToken = false,
                                                        streamState = it.streamState.copy(phase = ChatStreamPhase.STREAMING),
                                                    )
                                                }
                                            }
                                            val now = System.currentTimeMillis()
                                            if (streamToUi) {
                                                // v1.0.4: Ruta de corte adaptativa — delta Acumular primero pendingBuilder,
                                                // 50ms Presione cuando se active la aceleración computeAdaptiveSlice() Obtenga los primeros N caracteres y salida,Lograr una entrada suave.
                                                // chunkIntervals La lectura y escritura deben bloquearse con ComputeAdaptiveSlice:
                                                // pendingFlushJob La corrutina lo atraviesa dentro del mutex para calcular el intervalo promedio.
                                                // Si addLast/removeFirst está fuera del bloqueo, se activará ConcurrentModificationException.
                                                pendingFlushMutex.withLock {
                                                    if (lastChunkAt != 0L) {
                                                        chunkIntervals.addLast(now - lastChunkAt)
                                                        if (chunkIntervals.size > STREAM_SLIDE_WINDOW) chunkIntervals.removeFirst()
                                                    }
                                                    lastChunkAt = now
                                                    pendingBuilder.append(effectiveDelta)
                                                }
                                                if (isFirstToken) {
                                                    // El primer token se emite inmediatamente.,Las actualizaciones posteriores se realizan mediante una rutina de actualización independiente a un ritmo de 50 ms.
                                                    flushPendingToUi(force = true)
                                                } else {
                                                    flushPendingToUi()
                                                }
                                            } else {
                                                // UI sin transmisión: acumula directamente en builder(Mantener el comportamiento original,Notificación/La persistencia todavía está limitada por builder.length)
                                                params.builder.append(effectiveDelta)
                                            }
                                            if (params.builder.length - lastNotifChars >= 100 || now - lastNotifAt >= 500) {
                                                lastNotifChars = params.builder.length
                                                lastNotifAt = now
                                                runCatching {
                                                    // v2.5.0 fix: El número de palabras sincronizadas alcanza manager,Sólo las notificaciones del servicio front-end pueden mostrar el progreso real.
                                                    host.chatGenerationManager.updateChars(sessionId, params.builder.length)
                                                    host.notificationManager.updateLiveProgress(
                                                        sessionTitle,
                                                        params.builder.length,
                                                        true,
                                                        MuseNotificationTarget.Session(sessionId),
                                                    )
                                                }.onFailure { Logger.w("ChatVM", "Error en la notificación de progreso de la actualización: ${it.message}") }
                                            }
                                            if (experiments.debugMode && params.builder.length - lastLoggedCharCount >= 100) {
                                                lastLoggedCharCount = params.builder.length
                                                val elapsedMs = System.currentTimeMillis() - streamStartedAt
                                                Logger.d(
                                                    "ChatVM-Debug",
                                                    "streaming | sessionId=$sessionId | round=$round | " +
                                                        "chars=${params.builder.length} | elapsed=${elapsedMs}ms",
                                                )
                                            }
                                            if (params.builder.length - lastTokenUpdateChars >= 400 || now - lastTokenUpdateAt >= 1500) {
                                                lastTokenUpdateChars = params.builder.length
                                                lastTokenUpdateAt = now
                                                host.updateContextTokenCount()
                                            }
                                            // P2-2: reasoning-only Utilice también el mismo método de estrangulamiento y estabilización.(ver throttledPersist)
                                            throttledPersist()
                                        }
                                        is ChatStreamEvent.ReasoningDelta -> {
                                            // v1.0.3: El primer token se actualiza inmediatamente. UI(ReasoningDelta También considerado primero token)
                                            val isFirstToken = firstTokenTime == 0L
                                            if (isFirstToken) {
                                                firstTokenTime = System.currentTimeMillis()
                                                // P3-9: La primera palabra requiere mucho tiempo para enterrar.(reasoning El primer token también se incluye en el cálculo.)
                                                Perf.log("chat-first-token", firstTokenTime - streamStartedAt)
                                                host.stateStore.state.update { it.copy(isWaitingFirstToken = false) }
                                            }
                                            if (exposeRoundThinking) params.reasoningBuilder.append(event.delta)
                                            if (!event.signature.isNullOrBlank()) thinkingSignature = event.signature
                                            if (!event.encryptedContent.isNullOrBlank()) thinkingEncryptedContent = event.encryptedContent
                                            val now = System.currentTimeMillis()
                                            val timeSinceUi = now - lastUiUpdateAt
                                            // v1.0.3: El primer token se actualiza inmediatamente.;Limitación posterior de 12 caracteres o 50 ms
                                            // reasoning-only fluir(content para 0)Debe acelerar por longitud de razonamiento,
                                            // De lo contrario, la interfaz de usuario nunca se actualizará después del primer token.,El usuario sólo ve el primer carácter.
                                            val reasoningCharsSinceUi = params.reasoningBuilder.length - lastReasoningUiUpdateChars
                                            // v2.7: Protección inactiva — content No reconstruya mensajes cuando no estén presentes ni el razonamiento ni el incremento.
                                            // La rama original de solo razonamiento continuará con el texto sin cambios.
                                            // toString + La desensibilización + sustitución de todo el reloj es puro desperdicio.
                                            val contentGrew = params.builder.length != lastUiUpdateChars
                                            val reasoningGrew = reasoningCharsSinceUi > 0
                                            if (streamToUi && (contentGrew || reasoningGrew) && (
                                                    isFirstToken || reasoningCharsSinceUi >= STREAM_UI_CHAR_THRESHOLD ||
                                                        (timeSinceUi >= STREAM_UI_TIME_THRESHOLD_MS && reasoningGrew)
                                                    )
                                            ) {
                                                val flushStartedAt = System.nanoTime()

                                                host.updateAssistant(
                                                    params.currentAssistantId,
                                                    unmaskPii(params.builder.toString()),
                                                    unmaskPii(params.reasoningBuilder.toString()),
                                                    isStreaming = true,
                                                )

                                                state.uiFlushCount++

                                                // v2.7 Muestreo de puntos enterrados: se registra uno cada 8 veces y se utiliza una máquina real para confirmar si el costo del camino caliente aumenta linealmente con la longitud.

                                                if (state.uiFlushCount % 8 == 0) {
                                                    val costMs = (System.nanoTime() - flushStartedAt) / 1_000_000

                                                    Logger.d(
                                                        "StreamPerf",
                                                        "flush=${costMs}ms " +

                                                            "content=${params.builder.length} " +

                                                            "reasoning=${params.reasoningBuilder.length}",
                                                    )
                                                }
                                                lastUiUpdateChars = params.builder.length
                                                lastUiUpdateAt = now
                                                lastReasoningUiUpdateChars = params.reasoningBuilder.length
                                            }
                                            // P2-2: reasoning-only fluir(Ninguno ContentDelta)También colocado según el ciclo de aceleración.
                                            throttledPersist()
                                        }
                                        is ChatStreamEvent.ImageDelta -> {
                                            imageAccumulator.add(event.imageBase64)
                                            val now = System.currentTimeMillis()
                                            if (now - lastUiUpdateAt >= STREAM_UI_TIME_THRESHOLD_MS) {
                                                host.updateAssistant(
                                                    params.currentAssistantId,
                                                    unmaskPii(params.builder.toString()),
                                                    unmaskPii(params.reasoningBuilder.toString()),
                                                    imageAccumulator.toList(),
                                                )
                                                state.uiFlushCount++
                                                lastUiUpdateAt = now
                                            }
                                        }
                                        is ChatStreamEvent.ToolCallDelta -> {
                                            // F-10: Comienza la fase de llamada de herramienta
                                            host.stateStore.state.update {
                                                it.copy(streamState = it.streamState.copy(phase = ChatStreamPhase.TOOLING))
                                            }
                                            val acc =
                                                toolCallAccumulator.getOrPut(event.index) {
                                                    Triple(null, null, StringBuilder())
                                                }
                                            val newId = event.id ?: acc.first
                                            // v2.5.0 fix: Un nombre en blanco no puede sobrescribir el nombre de la herramienta acumulada.(Doble seguro con reparación previa)
                                            val newName = event.name?.takeIf { it.isNotBlank() } ?: acc.second
                                            // v1.0.81: isSnapshot=true El parámetro de tiempo es una instantánea completa.(La fuente ha fusionado varios fragmentos JSON),reemplazar en lugar de agregar
                                            if (event.isSnapshot) {
                                                acc.third.setLength(0)
                                                acc.third.append(event.argumentsDelta ?: "")
                                            } else {
                                                event.argumentsDelta?.let { acc.third.append(it) }
                                            }
                                            toolCallAccumulator[event.index] = Triple(newId, newName, acc.third)
                                            if (experiments.debugMode && event.name != null) {
                                                Logger.d(
                                                    "ChatVM-Debug",
                                                    "toolCallDelta | sessionId=$sessionId | round=$round | " +
                                                        "index=${event.index} | tool=${event.name}",
                                                )
                                            }
                                        }
                                        // A5: provider Uso real medido del token — Posiblemente varias veces por ronda(Anthropic entrada/La salida se desarrolla por separado.),
                                        // cobertura directa state.usageTokens,Garantizado para ser guardado por última vez(cantidad total);provider Conservar si no se devuelve null
                                        is ChatStreamEvent.UsageDelta -> {
                                            state.usageTokens = event.usage
                                        }
                                        is ChatStreamEvent.CitationDelta -> {
                                            citationUrls.addAll(event.urls)
                                        }
                                        is ChatStreamEvent.Done -> {
                                            // v1.0.30: Algunos modelos (como el GLM-4-9B) solo envían el nombre vacío tool_call durante todo el proceso.
                                            // Ninguno ContentDelta/ReasoningDelta,isWaitingFirstToken Todo el proceso no ha sido aclarado.
                                            // Forzar limpieza en evento Listo"Esperando el primero token"estado.
                                            if (host.stateStore.state.value.isWaitingFirstToken) {
                                                host.stateStore.state.update { it.copy(isWaitingFirstToken = false) }
                                            }
                                            // The flush coroutine may still own pendingBuilder until collect returns.
                                            // Defer all content mutations until it is cancelled/joined and drained below.
                                            doneFinishReason = event.finishReason
                                            // Cuenta de uso: En esta ronda se obtuvo el token de medición real del proveedor y se registró una línea de agua.
                                            // Capacidad de omisión, escritura asincrónica, la falla no afecta la conversación.
                                            val usage = state.usageTokens
                                            if (usage != null) {
                                                val finishedAt = System.currentTimeMillis()
                                                val recordModelId = runCatching {
                                                    host.stateStore.state.value.selectedModelId.orEmpty()
                                                }.getOrDefault("")
                                                val recorder = usageRecorder
                                                if (recorder != null) {
                                                    host.coroutineScope.launch(Dispatchers.IO) {
                                                        recorder.record(
                                                            source = io.zer0.muse.data.stats.UsageSource.CHAT,
                                                            modelId = recordModelId,
                                                            promptTokens = usage.promptTokens,
                                                            completionTokens = usage.completionTokens,
                                                            reasoningTokens = usage.reasoningTokens,
                                                            cachedTokens = usage.cachedTokens,
                                                            cacheWriteTokens = usage.cacheWriteTokens,
                                                            firstTokenMs = if (firstTokenAt > 0L) {
                                                                (firstTokenAt - roundStartedAt).coerceAtLeast(0)
                                                            } else {
                                                                0L
                                                            },
                                                            durationMs = (finishedAt - roundStartedAt).coerceAtLeast(0),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        is ChatStreamEvent.Error -> {
                                            streamError = event.message
                                            Logger.e("ChatVM", "stream error", event.throwable)
                                            if (experiments.debugMode) {
                                                Logger.d(
                                                    "ChatVM-Debug",
                                                    "stream Error | sessionId=$sessionId | round=$round | msg=${event.message}",
                                                )
                                            }
                                        }
                                        is ChatStreamEvent.FallbackNotice -> {
                                            MuseToast.show(event.message)
                                        }
                                        is ChatStreamEvent.StreamInterrupted -> {
                                            // v1.0.15: La conexión se interrumpió después de recibir parte del contenido.,Conserva el contenido y espera a que la red se recupere antes de volver a intentarlo.(no fijo delay)
                                            streamError = event.message
                                            streamInterrupted = true
                                            streamInterruptedThrowable = event.throwable
                                            Logger.w("ChatVM", "stream interrupted (partial content kept)", event.throwable)
                                            if (experiments.debugMode) {
                                                Logger.d(
                                                    "ChatVM-Debug",
                                                    "stream Interrupted | sessionId=$sessionId | round=$round | msg=${event.message}",
                                                )
                                            }
                                        }
                                    }
                                }
                                if (retryWithoutToolChoice) {
                                    Logger.i("ChatVM", "tool_choice El reintento de compatibilidad se ha cambiado al modo de utilidad")
                                }
                            } while (retryWithoutToolChoice)
                        } finally {
                            pendingFlushJob.cancelAndJoin()
                        }
                    }

                    // v1.0.4: Pendiente de descarga de fin de flujoContenido restante del generador(Portada Done/Error/StreamInterrupted).
                    // params.builder puede ir por detrás del corte adaptativo pendingBuilder,Aquí, la parte no publicada se escribe toda de una vez.,
                    // Asegúrese de que la actualización finalAssistant/persist obtenga el contenido completo.
                    if (pendingBuilder.isNotEmpty()) {
                        params.builder.append(pendingBuilder)
                        pendingBuilder.clear()
                        if (streamToUi) {
                            host.updateAssistant(params.currentAssistantId, unmaskPii(params.builder.toString()), isStreaming = true)
                            lastUiUpdateChars = params.builder.length
                            lastUiUpdateAt = System.currentTimeMillis()
                        }
                    }

                    // v2.0: Reanudar la transferencia y reescribir el resultado final — El texto de esta ronda de intentos ha reescrito el fragmento inicial del texto original.,
                    // El proveedor de descripción es"Generar desde cero"en lugar de"continuar escribiendo",Reemplazar texto antiguo con texto nuevo,
                    // Eliminar artículos antiguos+Empalme repetido de nuevos artículos.(La situación de no lograr el reemplazo en el método de transmisión se cierra aquí.).
                    if (streamError == null) {
                        resumeOriginalText?.let { original ->
                            val attempt = resumeAttemptText?.toString()
                            if (host.shouldReplaceOnResumeSupersede(original, attempt, params.builder.toString())) {
                                params.builder.setLength(0)
                                params.builder.append(attempt)
                                lastUiUpdateChars = params.builder.length
                                lastPersistChars = params.builder.length
                                lastNotifChars = params.builder.length
                                lastTokenUpdateChars = params.builder.length
                                lastPersistAt = 0L
                                lastNotifAt = 0L
                                lastTokenUpdateAt = 0L
                                throttledPersist()
                                Logger.i(
                                    "ChatVM",
                                    "resume attempt supersedes partial content | old=${original.length} new=${attempt?.length ?: 0}",
                                )
                            }
                        }
                    }

                    // Final content mutations happen only after pending deltas are fully drained.
                    // Reasoning-only fallback must observe the complete body, not just the pre-flush builder.
                    //
                    // v2.6.1 (#5) + v2.7 never-empty Conclusión (confirmado por el usuario)“Está bien vomitar, pero no vaciar.”):
                    //   Cuando el texto esté vacío, use Pensar**única excepción**Sí: esta ronda de hecho ha analizado la llamada a la herramienta que se ejecutará.
                    //   (Las siguientes rondas seguirán produciendo y pensando mientras el texto aparecerá directamente en la pantalla).
                    //   si finish_reason=tool_calls Pero no se resolvió ni una sola llamada (el protocolo está mal formado)/degradación ascendente),
                    //   No se mostrará nada más tarde, así que déjelo en blanco. = El usuario se enfrenta a una respuesta en blanco; En este momento, todavía lo sabe todo (preferiría pensar en ello).
                    if (streamError == null &&
                        params.builder.isEmpty() &&
                        params.reasoningBuilder.isNotEmpty() &&
                        toolCallAccumulator.isEmpty()
                    ) {
                        params.builder.append(params.reasoningBuilder.toString())
                        params.reasoningBuilder.setLength(0)
                    }
                    if (streamError == null && ChatStopReason.isLengthLimited(doneFinishReason)) {
                        params.builder.append("\n\n").append(host.appContext.getString(R.string.err_reply_truncated))
                    }
                    // v2.x(Artículo de exportación de diagnóstico A): El final de la secuencia se clasifica en seguimiento (observador puro, no afecta la lógica)
                    runCatching {
                        io.zer0.muse.diagnostic.GenerationTrace.noteStreamEnd(
                            kind = when {
                                streamError != null -> "failure"
                                doneFinishReason != null -> "done"
                                else -> "closed_no_finish"
                            },
                            finishReason = doneFinishReason,
                        )
                    }
                    if (experiments.debugMode && doneFinishReason != null) {
                        val elapsedMs = System.currentTimeMillis() - streamStartedAt
                        Logger.d(
                            "ChatVM-Debug",
                            "stream Done | sessionId=$sessionId | round=$round | chars=${params.builder.length} | " +
                                "reasoningChars=${params.reasoningBuilder.length} | elapsed=${elapsedMs}ms",
                        )
                    }

                    if (streamError != null) {
                        val retryType = host.classifyErrorType(streamError)
                        // v1.0.1 (P4): Reemplace la capa exterior con params.retryCount streamRetryCount,Cada ronda es independiente.
                        // v1.0.17: StreamInterrupted currículum inteligente — Cuando se ha recibido parte del contenido + error de red,
                        //   Espere a que se recupere la red(la mayoría 30s)para uso posterior preservePartialContent=true Inténtalo de nuevo,
                        //   Mantener el contenido mostrado,UI Solo agregar contenido nuevo(No vuelvas a la primera palabra.).
                        //   Si la red no se recupera después de un tiempo de espera, se reducirá a reintento manual.(Mantenga algo de contenido + isRecoverable).
                        val isNetworkError =
                            streamInterruptedThrowable is java.io.IOException ||
                                (streamInterrupted && retryType == ChatErrorType.NETWORK)
                        if (streamInterrupted &&
                            isNetworkError &&
                            params.builder.isNotEmpty() &&
                            params.retryCount < MAX_STREAM_RETRIES
                        ) {
                            val newRetryCount = params.retryCount + 1
                            Logger.w(
                                "ChatVM",
                                "StreamInterrupted Corte de red,Espere a que se recupere la red(la mayoría ${NETWORK_RECOVERY_TIMEOUT_MS}ms)" +
                                    "(No. $newRetryCount/$MAX_STREAM_RETRIES veces,round=${params.round}): $streamError",
                            )
                            val recovered = host.waitForNetworkRecovery(NETWORK_RECOVERY_TIMEOUT_MS)
                            if (recovered) {
                                Logger.i("ChatVM", "La red ha sido restaurada.,preservePartialContent=true Inténtalo de nuevo")
                                // Restablecer el estado de corte adaptativo(Las muestras de tarifas de la ronda anterior no son aplicables para reanudar transferencias)
                                pendingBuilder.clear()
                                chunkIntervals.clear()
                                lastChunkAt = 0L
                                // Restablecer el recuento de tokens y el tiempo del primer token,Reiniciar la transferencia del currículum
                                // (pero no restablecer builder — preservePartialContent=true Mantener el contenido mostrado)
                                lastNotifChars = params.builder.length
                                lastNotifAt = System.currentTimeMillis()
                                lastTokenUpdateChars = params.builder.length
                                lastTokenUpdateAt = System.currentTimeMillis()
                                lastPersistChars = params.builder.length
                                lastPersistAt = System.currentTimeMillis()
                                // B3-03: UI La reanudación de la deduplicación de capa ha entrado en vigor(duplicateRemaining Saltar prefijos repetidos).
                                // Provider La capa resumeFromText está conectada:Contenido mostrado inyectado como final del mensaje del asistente.,El modelo continúa donde lo dejó.
                                return streamRound(
                                    params.copy(
                                        retryCount = newRetryCount,
                                        preservePartialContent = true,
                                    ),
                                )
                            } else {
                                Logger.w("ChatVM", "Red no restaurada,Bajar a reintento manual")
                            }
                        }
                        if (!streamInterrupted &&
                            (retryType == ChatErrorType.NETWORK || retryType == ChatErrorType.RATE_LIMIT) &&
                            params.retryCount < MAX_STREAM_RETRIES
                        ) {
                            val newRetryCount = params.retryCount + 1
                            // v1.0.16: retirarse 3s/10s/30s,Cubre la duración típica de conmutación en segundo plano(5-15s)
                            val delayMs =
                                when (newRetryCount) {
                                    1 -> 3_000L
                                    2 -> 10_000L
                                    else -> 30_000L
                                }
                            Logger.w(
                                "ChatVM",
                                "stream error($retryType),${delayMs}ms Vuelve a intentarlo más tarde " +
                                    "(No. $newRetryCount/$MAX_STREAM_RETRIES veces,round=${params.round}): $streamError",
                            )
                            kotlinx.coroutines.delay(delayMs)
                            lastUiUpdateChars = 0
                            lastUiUpdateAt = streamStartedAt
                            lastNotifChars = 0
                            lastNotifAt = streamStartedAt
                            lastTokenUpdateChars = 0
                            lastTokenUpdateAt = streamStartedAt
                            lastPersistChars = 0
                            lastPersistAt = streamStartedAt
                            firstTokenTime = 0L
                            // v1.0.4: Restablecer el estado de corte adaptativo,Evite contaminar la nueva ronda con muestras tarifarias de la ronda anterior
                            pendingBuilder.clear()
                            chunkIntervals.clear()
                            lastChunkAt = 0L
                            // v1.0.3: retry volver a entrar"Esperando el primero token"escenario,
                            // Porque el constructor ha sido iniciado por streamRound clear() Borrar,
                            // UI ThinkingBubble se volverá a mostrar hasta que llegue el primer token
                            host.stateStore.state.update { it.copy(isWaitingFirstToken = true) }
                            return streamRound(params.copy(retryCount = newRetryCount))
                        }
                    }

                    // Recuperación de desbordamiento de contexto (la solicitud excede la ventana del modelo): presione una ronda del historial y reenvíe nuevamente.
                    // esto es“De repente apareció un mensaje de error durante el chat y solo pude abrir una nueva sesión.”causa principal——provider He dejado en claro que no puedo dejarlo ir,
                    // Después de presionarlo, normalmente se puede soltar. La solicitud completa se intenta solo una vez (lo más probable es que recoverFromContextOverflow
                    // (Están ocupados en el lugar), si el compresor no produce un historial más corto, se dará por vencido directamente e informará el error original.
                    if (streamError != null) {
                        val overflowHistory =
                            recoverFromContextOverflow(
                                error = streamError,
                                streamInterrupted = streamInterrupted,
                                recovery = overflowRecovery,
                                compress = { compressForOverflowRecovery(params.history, sessionId) },
                            )
                        if (overflowHistory != null) {
                            Logger.w(
                                "ChatVM",
                                "context overflow,Reenviar después de la compresión | round=${params.round} | " +
                                    "messages ${params.history.size} -> ${overflowHistory.size} | error=$streamError",
                            )
                            // El historial comprimido se vuelve a escribir en todo el ciclo: de lo contrario, la siguiente rueda de herramientas llegará a la ventana con el historial original,
                            // Y la oportunidad de recuperación se ha agotado.
                            conversationHistory.clear()
                            conversationHistory.addAll(overflowHistory)
                            // Restablece el mismo estado de transmisión que el reintento de red: el primer token de la nueva ronda se vuelve a cronometrar.
                            // La solicitud de desbordamiento no escupió el contenido (provider rechazado directamente) sin conservar parte del texto.
                            pendingBuilder.clear()
                            chunkIntervals.clear()
                            lastChunkAt = 0L
                            firstTokenTime = 0L
                            lastUiUpdateChars = 0
                            lastUiUpdateAt = streamStartedAt
                            lastNotifChars = 0
                            lastNotifAt = streamStartedAt
                            lastTokenUpdateChars = 0
                            lastTokenUpdateAt = streamStartedAt
                            lastPersistChars = 0
                            lastPersistAt = streamStartedAt
                            host.stateStore.state.update { it.copy(isWaitingFirstToken = true) }
                            return streamRound(
                                params.copy(history = overflowHistory, preservePartialContent = false),
                            )
                        }
                    }

                    streamError?.let {
                        val type = host.classifyErrorType(it)
                        val displayMsg = ErrorMessages.resolve(host.appContext, it)
                        // La salida del bucle de herramientas es uniformemente responsable de informar errores a la interfaz de usuario para evitar que dos tarjetas muestren el mismo error.
                        host.updateAssistant(
                            params.currentAssistantId,
                            unmaskPii(params.builder.toString()),
                            unmaskPii(params.reasoningBuilder.toString()),
                            imageAccumulator.toList(),
                            isStreaming = false,
                        )
                        val partialAssistant = host.stateStore.messages.value.firstOrNull { it.id == params.currentAssistantId }
                        // B-1: Si la sesión ha sido eliminada, se omitirá el error.,Prevenir la eliminación"resurrección".
                        if (partialAssistant != null && !host.generationController.isSessionWritesSuppressed(sessionId)) {
                            try {
                                host.sessionRepository.upsertMessage(sessionId, partialAssistant)
                            } catch (e: Exception) {
                                Logger.e("ChatVM", "streamError upsertMessage failed", e)
                                host.addError(
                                    ChatErrorType.UNKNOWN,
                                    host.appContext.getString(
                                        R.string.err_chat_reply_save_failed,
                                        e.message ?: host.appContext.getString(R.string.err_chat_unknown),
                                    ),
                                )
                            }
                        }
                        // B-24: Sólo autorizado si todavía es la última generación.(Los errores de compilación antiguos no deben borrar el estado de transmisión recién generado)
                        // F-10: Error de transmisión → FAILED
                        host.clearStreamingStateIfLatest(state, finalPhase = ChatStreamPhase.FAILED)
                        // B-02: Exportar sincronización del último contenido de streaming conocido — Interrumpir bloque de captura de solo lectura state.builder,
                        // ronda por ronda params.builder es un objeto local,No se puede construir una respuesta parcial cuando se detiene si no se sincroniza
                        state.builder.setLength(0)
                        state.builder.append(params.builder)
                        state.reasoningBuilder.setLength(0)
                        state.reasoningBuilder.append(params.reasoningBuilder)
                        return StreamRoundResult.Error(type, displayMsg, params.builder.toString(), params.reasoningBuilder.toString())
                    }

                    // Revisión de corrección (2.0 A-01): Si la rueda de herramientas genera directamente la respuesta final(Ninguno toolCalls),Explica esta ronda
                    // En realidad"ronda de respuesta final" — Haz una ronda con el modelo principal.,La respuesta final garantizada la genera el modelo principal.(con arriba
                    // 4840 Comentarios con intención consistente). solo en toolModel != Vuelva a ejecutar el modelo principal;Ronda complementaria fallida(red/Limitación de corriente)
                    // Mantenga las respuestas generadas por toolModel como respaldo,No dejes a los usuarios con respuestas vacías.
                    // finish_reason=length Indica que la salida está truncada por el límite superior del token. Esta ronda de llamadas a herramientas
                    // arguments Lo más probable es que sólo se reciba la mitad. Los parámetros incompletos no se pueden ejecutar (la escritura de archivos y la ejecución de comandos pueden desviarse del
                    // intención original), pero es igualmente perjudicial invalidar toda la ronda de llamadas: el modelo cree que está finalizando normalmente y no recibe ninguna respuesta.
                    // Los usuarios solo ven una frase"Respuesta truncada", la tarea se detiene en su lugar.
                    // Aquí guarde la lista de llamadas y marque esta ronda como"Argumento truncado",hasToolCalls todavía true ——
                    // El orquestador completará un resultado explícito de la herramienta de error para cada llamada, por lo que el ciclo tomará una ronda más.
                    // El modelo verá el motivo en la siguiente ronda de solicitudes y podrá volver a emitir la llamada completa. La ronda extra vale el fracaso silencioso;
                    // El truncamiento repetido todavía está sujeto al límite de ronda existente y a la misma detección de estancamiento de firma, y ​​no se saldrá de control.
                    val toolCallsTruncated =
                        toolCallAccumulator.isNotEmpty() &&
                            doneFinishReason != null &&
                            ChatStopReason.isLengthLimited(doneFinishReason)
                    if (toolCallsTruncated) {
                        Logger.w(
                            "ChatVM",
                            "finish_reason=length truncamiento, epiciclo ${toolCallAccumulator.size} La llamada a la herramienta no se ejecuta y el parámetro de relleno se trunca incorrectamente. | round=$round",
                        )
                    }
                    // NOTA: La cadena de entrega de etiquetas es runner → StreamRoundResult.Success → orquestador;runner Sin cobertura de prueba única
                    // (Depende de todo el host ChatViewModel), debes ejecutar ToolOrchestratorRunLoopTest juntos al cambiar esto.
                    val hasToolCalls = toolCallAccumulator.isNotEmpty()
                    if (usesToolModel && !hasToolCalls && roundModel != effectiveModel) {
                        Logger.i("ChatVM", "La rueda de herramientas produce la respuesta final(round=$round), Haz una ronda con el modelo principal. | model=${effectiveModel?.id}")
                        val toolModelContent = params.builder.toString()
                        val toolModelReasoning = params.reasoningBuilder.toString()
                        // Limpia la acumulación de esta ronda.,Las rondas de compensación empiezan desde cero;corte adaptativo/acelerador/Eliminar duplicados/El estado del primer token se restablece juntos.
                        params.builder.setLength(0)
                        params.reasoningBuilder.setLength(0)
                        imageAccumulator.clear()
                        toolCallAccumulator.clear()
                        pendingBuilder.clear()
                        chunkIntervals.clear()
                        lastChunkAt = 0L
                        duplicateRemaining = null
                        thinkingSignature = null
                        thinkingEncryptedContent = null
                        firstTokenTime = 0L
                        lastUiUpdateChars = 0
                        lastUiUpdateAt = streamStartedAt
                        lastNotifChars = 0
                        lastNotifAt = streamStartedAt
                        lastTokenUpdateChars = 0
                        lastTokenUpdateAt = streamStartedAt
                        lastPersistChars = 0
                        lastPersistAt = streamStartedAt
                        // Vaciar inmediatamente el contenido de la interfaz de usuario + restaurar"Esperando el primero token",Rotación complementaria tras la llegada
                        host.updateAssistant(params.currentAssistantId, content = "", isStreaming = true)
                        host.stateStore.state.update { it.copy(isWaitingFirstToken = true) }
                        val replayed = streamRound(params.copy(forceMainModel = true))
                        // Ronda complementaria fallida:Herramienta de reabastecimiento integralModelo de respuesta(Reemplazar el párrafo completo,Evitar empalmes con parte de la rueda suplementaria.)
                        if (replayed is StreamRoundResult.Error) {
                            Logger.w("ChatVM", "Falló el reabastecimiento de ruedas del modelo principal., Mantener herramientaModelo Responder: ${replayed.message}")
                            params.builder.setLength(0)
                            params.builder.append(toolModelContent)
                            params.reasoningBuilder.setLength(0)
                            params.reasoningBuilder.append(toolModelReasoning)
                            // B-02: Sincronización después del reabastecimiento state.builder,Mantenga la captura interrumpida para leer el contenido reanudado.
                            state.builder.setLength(0)
                            state.builder.append(params.builder)
                            state.reasoningBuilder.setLength(0)
                            state.reasoningBuilder.append(params.reasoningBuilder)
                            host.updateAssistant(
                                params.currentAssistantId,
                                unmaskPii(toolModelContent),
                                unmaskPii(toolModelReasoning).ifBlank { null },
                                isStreaming = false,
                            )
                            host.stateStore.state.update { it.copy(isWaitingFirstToken = false) }
                        }
                        return replayed
                    }

                    // v1.0.54: Primero determine si esta rueda es una rueda de herramientas.,Empujar de nuevo UI —
                    //   rueda de herramientas(si toolCalls)El proceso de pensamiento no se muestra.(hacer una jugada),La rueda de respuesta final se muestra normalmente.
                    host.updateAssistant(
                        params.currentAssistantId,
                        unmaskPii(params.builder.toString()),
                        if (exposeRoundThinking) unmaskPii(params.reasoningBuilder.toString()).ifBlank { null } else null,
                        imageAccumulator.toList(),
                        isStreaming = false,
                    )
                    if (!streamToUi) {
                        host.stateStore.messages.value = host.stateStore.messages.value
                    }

                    val finalizedAssistant = host.stateStore.messages.value.firstOrNull { it.id == params.currentAssistantId }
                    val assistantMessage =
                        if (hasToolCalls) {
                            UIMessage(
                                id = params.currentAssistantId,
                                role = MessageRole.ASSISTANT,
                                content = finalizedAssistant?.content ?: unmaskPii(params.builder.toString()),
                                // v2.6.15: La reconstrucción de la rueda de herramientas también debe llevar el sello del modelo de salida. —— Es precisamente este tipo de mensaje el que viene acompañado de bloqueos de pensamiento.
                                modelId = roundAssistantModelId(roundModel, finalizedAssistant),
                                reasoning = if (exposeRoundThinking) {
                                    finalizedAssistant?.reasoning ?: unmaskPii(params.reasoningBuilder.toString()).ifBlank { null }
                                } else {
                                    null
                                },
                                mood = finalizedAssistant?.mood.takeIf { exposeRoundThinking },
                                reflection = finalizedAssistant?.reflection.takeIf { exposeRoundThinking },
                                thinkingSignature = finalizedAssistant?.thinkingSignature ?: thinkingSignature,
                                thinkingEncryptedContent = finalizedAssistant?.thinkingEncryptedContent ?: thinkingEncryptedContent,
                                imageBase64List = finalizedAssistant?.imageBase64List ?: emptyList(),
                                citationUrls = citationUrls.toList(),
                                // v1.0.88 (R-2): El campo de identidad variante debe dejarse intacto — Rueda de herramientas previamente construida
                                // assistantMessage perdido variantGroupId/variantIndex/variantCount/parentGroupId,
                                // Inténtalo de nuevo(Variantes)+Realice el pedido después de llamar a la herramienta,Al volver a ingresar al árbol de sesiones y reconstruirlo, el mensaje se cuelga en la ubicación incorrecta.,
                                // Se muestra como"Superposición de mensajes/La primera respuesta se convierte en la última.".
                                variantGroupId = finalizedAssistant?.variantGroupId,
                                variantIndex = finalizedAssistant?.variantIndex ?: 0,
                                variantCount = finalizedAssistant?.variantCount ?: 1,
                                parentGroupId = finalizedAssistant?.parentGroupId,
                                toolCalls =
                                toolCallAccumulator.toSortedMap().map { (idx, triple) ->
                                    ToolCall(
                                        // Cada llamada debe coincidir con precisión con el resultado de la herramienta. Falta la identificación o la identificación es una cadena vacía.
                                        // Ninguno de estos funcionará (el proveedor rechazará los identificadores vacíos), todos se sintetizarán de acuerdo con el índice de esta ronda.
                                        id = triple.first?.takeIf { it.isNotBlank() }
                                            ?: "call_${System.currentTimeMillis()}_$idx",
                                        name = triple.second ?: "",
                                        arguments = triple.third.toString(),
                                    )
                                },
                            )
                        } else {
                            val finalAssistant =
                                finalizedAssistant ?: UIMessage(
                                    id = params.currentAssistantId,
                                    role = MessageRole.ASSISTANT,
                                    content = unmaskPii(params.builder.toString()),
                                    reasoning = unmaskPii(params.reasoningBuilder.toString()).ifBlank { null },
                                    thinkingSignature = thinkingSignature,
                                    thinkingEncryptedContent = thinkingEncryptedContent,
                                    imageBase64List = imageAccumulator.toList(),
                                    // v2.6.15: En consonancia con la rueda de herramientas, la respuesta final también registra el modelo real de la rueda.
                                    modelId = roundAssistantModelId(roundModel, null),
                                )
                            val withCitations =
                                finalAssistant.copy(
                                    modelId = roundAssistantModelId(roundModel, finalAssistant),
                                    reasoning = if (exposeRoundThinking) finalAssistant.reasoning else null,
                                    mood = if (exposeRoundThinking) finalAssistant.mood else null,
                                    reflection = if (exposeRoundThinking) finalAssistant.reflection else null,
                                    citationUrls = (finalAssistant.citationUrls + citationUrls).distinct(),
                                    ragCitations = if (pendingRagCitations.isNotEmpty()) {
                                        pendingRagCitations
                                    } else {
                                        finalAssistant.ragCitations
                                    },
                                )
                            withCitations
                        }

                    // B-02: Exportar sincronización del último contenido de streaming conocido(Igual que arriba,Para respuesta parcial del constructor de captura de interrupción)
                    state.builder.setLength(0)
                    state.builder.append(params.builder)
                    state.reasoningBuilder.setLength(0)
                    state.reasoningBuilder.append(params.reasoningBuilder)
                    return StreamRoundResult.Success(
                        assistantMessage = assistantMessage,
                        hasToolCalls = hasToolCalls,
                        contentLength = params.builder.length,
                        firstTokenTime = firstTokenTime,
                        citationUrls = citationUrls.toList(),
                        toolCallsTruncated = toolCallsTruncated,
                    )
                }

                override suspend fun requestToolApproval(
                    toolName: String,
                    toolCallId: String,
                    argsPreview: String,
                    args: Map<String, Any?>,
                ): ToolApprovalState {
                    return host.requestToolApprovalForSession(
                        sessionId = sessionId,
                        toolName = toolName,
                        toolCallId = toolCallId,
                        argsPreview = argsPreview,
                        args = args,
                    )
                }

                override fun onToolLoopError(type: ChatErrorType, message: String, recoverable: Boolean) {
                    host.addError(type, message, recoverable)
                }

                // v2.6.7: Proporcionar el estado del canal del dispositivo,Permite al orquestador bloquear llamadas a herramientas condenadas antes de que se ejecuten.
                // Tome el contenedor global AutomationManager(Mismo método que useRecorder),evitar ser un bypass
                // Capacidad de cambiar toda la cadena estructural.;Volver cuando no esté disponible null = Saltar verificación previa,Mantener el comportamiento original.
                override suspend fun availableDeviceChannels(): Set<io.zer0.muse.tools.ToolRequirements.Channel>? {
                    return runCatching {
                        val automation =
                            org.koin.core.context.GlobalContext.get()
                                .get<io.zer0.muse.automation.core.AutomationManager>()
                        io.zer0.muse.tools.ToolChannelPreflight.channelsOf(automation)
                    }.getOrNull()
                }

                // v1.x: Comience con una sola herramienta/finalizar la devolución de llamada(para registros de depuración)
                //  Ya existe una implementación vacía predeterminada en la interfaz.,Esto cubre la realización de registros de depuración.,Es conveniente realizar un seguimiento del tiempo y el estado de ejecución de la herramienta.
                override fun onToolStart(toolCallId: String, toolName: String) {
                    host.chatGenerationManager.touch(sessionId)
                    // M1.7: Ejecución de herramienta en progreso -> WAITING_TOOL punto de control
                    host.sessionManager.runtime(sessionId)?.markWaitingTool(state.turnId)
                    if (ConversationRebuildFlagStore.current.shadowEventsEnabled) {
                        host.coroutineScope.launch(Dispatchers.IO) {
                            host.recordConversationShadow(
                                ConversationEventDraft(
                                    sessionId = sessionId,
                                    turnId = state.turnId,
                                    type = ConversationEventType.TOOL_CALLED,
                                    streamId = state.streamId,
                                    generationSerial = state.generationSerial,
                                    payloadJson = "{\"toolCallId\":\"$toolCallId\",\"toolName\":\"$toolName\"}",
                                ),
                            )
                        }
                    }
                    if (experiments.debugMode) {
                        Logger.d("ChatVM", "onToolStart | tool=$toolName | id=$toolCallId | sessionId=$sessionId")
                    }
                }

                override fun onToolFinish(toolCallId: String, toolName: String, success: Boolean, durationMs: Long) {
                    host.chatGenerationManager.touch(sessionId)
                    // M1.7: Finaliza la ejecución de la herramienta -> volver GENERATING(Solicitud de continuación o ronda de respuesta final)
                    host.sessionManager.runtime(sessionId)?.markResumed(state.turnId)
                    if (ConversationRebuildFlagStore.current.shadowEventsEnabled) {
                        host.coroutineScope.launch(Dispatchers.IO) {
                            host.recordConversationShadow(
                                ConversationEventDraft(
                                    sessionId = sessionId,
                                    turnId = state.turnId,
                                    type = ConversationEventType.TOOL_RESULT,
                                    streamId = state.streamId,
                                    generationSerial = state.generationSerial,
                                    payloadJson = "{\"toolCallId\":\"$toolCallId\",\"toolName\":\"$toolName\"," +
                                        "\"success\":$success,\"durationMs\":$durationMs}",
                                ),
                            )
                        }
                    }
                    if (experiments.debugMode) {
                        Logger.d(
                            "ChatVM",
                            "onToolFinish | tool=$toolName | success=$success | duration=${durationMs}ms | sessionId=$sessionId",
                        )
                    }
                }
            }

        // v2.x: El límite superior de rondas de herramientas proviene de la configuración.(0=Ilimitado);Valor fijo de reserva de fallo de lectura,No bloquees las conversaciones.
        val toolRoundLimit =
            (resultOf { host.settings.getToolLoopMaxRounds() }.getOrNull() ?: MAX_TOOL_ROUNDS)
                .takeIf { it > 0 } ?: 0

        val toolLoopResult =
            host.toolOrchestrator.runLoop(
                params =
                ToolLoopParams(
                    sessionId = sessionId,
                    // F-12: enlace unificado id(Durante toda la ejecución de la herramienta, auditoría y registro)
                    traceId = state.traceId,
                    initialAssistantId = state.currentAssistantId,
                    baseHistorySize = baseHistorySize,
                    maxRounds = toolRoundLimit,
                    tools = tools,
                    skillMap = state.skillMap,
                    routeSnapshot = state.routeSnapshot,
                    model = effectiveModel,
                    providerConfig = effectiveProviderConfig,
                    temperature = effectiveTemperature,
                    maxTokens = assistant?.maxTokens,
                    reasoningLevel = reasoningLevel,
                    webSearchEnabled = localWebSearchEnabled,
                    nativeWebSearch = nativeWebSearchEnabled,
                    experiments = experiments,
                    assistant = assistant,
                    initialBuilderContent = state.builder.toString(),
                    initialReasoningContent = state.reasoningBuilder.toString(),
                    turnId = state.turnId,
                    generationIdentity = state.generationIdentity,
                    toolExecutionContext = toolExecutionContext,
                    allowToolExecution = !host.stateStore.state.value.chatPreferences.pauseToolExecution,
                ),
                conversationHistory = conversationHistory,
                host = toolLoopHost,
                accessor = host.accessor,
                taskCardCoordinator = host.taskCardCoordinator,
            )

        state.round = toolLoopResult.round
        state.totalCharCount = toolLoopResult.totalCharCount
        state.totalToolCallCount = toolLoopResult.totalToolCallCount
        state.firstTokenTime = toolLoopResult.firstTokenTime
        state.currentAssistantId = toolLoopResult.finalAssistantId
        // Revisión de corrección (2.0 C-17): Ubicación inferior del bolsillo de cierre — exec* El pedido se ha realizado inmediatamente.,Para aquellos que se han registrado
        // Herramienta para escanear mensajes multimedia nuevamente,No se pudo sobrescribir el disco/Contención de procesos y otras ventanas;NonCancellable garantia
        // Cancelar la ruta también se puede completar.(Los inscritos acuden a la sucursal de cancelación. attachMediaToMessage,La idempotencia es inofensiva aquí.).
        if (host.generationState.toolMediaMessages.isNotEmpty()) {
            val sweepSession = host.generationState.activeToolSessionId ?: host.currentSessionIdForApproval()
            host.generationState.toolMediaMessages.forEach { mediaId ->
                host.persistToolMessageMedia(sweepSession, mediaId)
            }
            host.generationState.toolMediaMessages.clear()
        }
        // A-13: token de incremento de cierre,Hacer que se ejecuten las herramientas que aún están en tránsito(La generación de imágenes puede tardar decenas de segundos)Error de escritura de medios
        host.generationState.toolAssistantId = null
        host.generationState.activeToolSessionId = null
        host.nextToolGenerationToken()

        if (!toolLoopResult.success) {
            val err = toolLoopResult.error
            host.recordConversationShadow(
                ConversationEventDraft(
                    sessionId = sessionId,
                    turnId = state.turnId,
                    type = ConversationEventType.TURN_FAILED,
                    streamId = state.streamId,
                    generationSerial = state.generationSerial,
                    payloadJson = "{\"round\":${toolLoopResult.round},\"errorType\":\"${err?.type ?: ChatErrorType.UNKNOWN}\"}",
                ),
            )
            // P2-3: La falla del ciclo de la herramienta debe cerrarse shadow turn,De lo contrario, abra la auditoría de seguimiento a largo plazo./Distorsión de la reconstrucción.
            if (ConversationRebuildFlagStore.current.shadowEventsEnabled && state.shadowTurnStarted) {
                host.conversationService.finishTurn(state.turnId, "FAILED")
            }
            val errMessage = err?.message ?: host.appContext.getString(R.string.err_chat_unknown)
            host.addError(err?.type ?: ChatErrorType.UNKNOWN, errMessage, isRecoverable = err?.type != ChatErrorType.API_KEY)
            // B-24: Sólo autorizado si todavía es la última generación.
            // F-10: El ciclo de la herramienta falló → FAILED
            host.clearStreamingStateIfLatest(state, finalPhase = ChatStreamPhase.FAILED)
            return false
        }

        // Phase 8.5 Solución S16: cuando la llamada a la herramienta alcanza el límite de maxToolRounds y no se genera ninguna respuesta final,
        // Las RagCitations pendientes acumuladas deben agregarse al mensaje actual del asistente.
        if (toolLoopResult.finalAssistantMessage == null && pendingRagCitations.isNotEmpty()) {
            val lastAssistant = host.stateStore.messages.value.firstOrNull { it.id == state.currentAssistantId }
            if (lastAssistant != null && lastAssistant.ragCitations.isEmpty()) {
                val withCitations = lastAssistant.copy(ragCitations = pendingRagCitations)
                host.stateStore.messages.value =
                    host.stateStore.messages.value.map { msg ->
                        if (msg.id == withCitations.id) withCitations else msg
                    }
                // B-1: Si la sesión ha sido eliminada, se omitirán las citas.,Prevenir la eliminación"resurrección".
                if (!host.generationController.isSessionWritesSuppressed(sessionId)) {
                    try {
                        host.sessionRepository.upsertMessage(sessionId, withCitations)
                    } catch (e: Exception) {
                        Logger.e("ChatVM", "upsertMessage(citations) failed", e)
                        host.addError(
                            ChatErrorType.UNKNOWN,
                            host.appContext.getString(
                                R.string.err_chat_ref_save_failed,
                                e.message ?: host.appContext.getString(R.string.err_chat_unknown),
                            ),
                        )
                    }
                }
            }
        }

        // voluntad finalAssistantMessage(Contiene citations + artifacts)escribir UI / DB / branch
        var finalMessagePersistenceFailed = false
        toolLoopResult.finalAssistantMessage?.let { finalAssistant ->
            // v1.0.75 fix (El gráfico sin formato solo muestra la causa raíz del enlace.): finalAssistantMessage Construido por GenerationHandler,
            //   Contiene sólo content + toolCalls,No contiene campos multimedia. Si sobrescribe directamente el mismo mensaje de identificación,
            //   execGenerateImage escrito imageUrls / videoFileUri / imageBase64List se perderá,
            //   Como resultado, la imagen no se representa y el modelo sólo puede repetir la URL. La combinación conserva los campos multimedia del mensaje original.
            // A-16: Fusionar lógica en funciones puras internas [mergeFinalAssistantMedia](Hay una única barandilla de prueba.)
            val existingMsg = host.stateStore.messages.value.firstOrNull { it.id == finalAssistant.id }
            val mergedMedia = mergeFinalAssistantMedia(finalAssistant, existingMsg)
            val withCitations =
                when {
                    toolLoopResult.citationUrls.isNotEmpty() && pendingRagCitations.isNotEmpty() ->
                        mergedMedia.copy(citationUrls = toolLoopResult.citationUrls, ragCitations = pendingRagCitations)
                    toolLoopResult.citationUrls.isNotEmpty() ->
                        mergedMedia.copy(citationUrls = toolLoopResult.citationUrls)
                    pendingRagCitations.isNotEmpty() ->
                        mergedMedia.copy(ragCitations = pendingRagCitations)
                    else -> mergedMedia
                }
            val (replacedContent, artifacts) =
                ArtifactExtractor.extractArtifacts(
                    sessionId = sessionId,
                    messageId = state.currentAssistantId.toString(),
                    content = withCitations.content,
                )
            val withArtifacts =
                if (artifacts.isNotEmpty()) {
                    artifacts.forEach { host.artifactRepository.upsert(it) }
                    withCitations.copy(
                        content = replacedContent,
                        artifactIds = artifacts.map { it.id },
                    )
                } else {
                    withCitations
                }
            host.stateStore.messages.value =
                host.stateStore.messages.value.map { msg ->
                    if (msg.id == withArtifacts.id) withArtifacts else msg
                }
            conversationHistory.add(withArtifacts)
            // v1.0.88 (S-3): Registro de autoverificación de integridad de cierre de transmisión — Registre la longitud y los roles del contenido final,
            // Si los comentarios de los usuarios"Superposición de mensajes/confusión"Puede determinar rápidamente a partir del registro si se trata de un problema de superposición de contenido o de clasificación.
            Logger.i(
                "ChatVM",
                "finalize: msg=${withArtifacts.id.toString().take(8)} len=${withArtifacts.content.length} " +
                    "reasoning=${withArtifacts.reasoning?.length ?: 0} " +
                    "mood=${withArtifacts.mood?.length ?: 0} " +
                    "variant=${withArtifacts.variantGroupId ?: "-"}",
            )
            try {
                if (ConversationRebuildFlagStore.current.useNewConversationService && state.shadowTurnStarted) {
                    val commitResult =
                        host.messageCommit.commit(
                            MessageCommitRequest(
                                turnId = state.turnId,
                                sessionId = sessionId,
                                userMessageId = state.conversationHistory.lastOrNull {
                                    it.role == MessageRole.USER
                                }?.id?.toString().orEmpty(),
                                assistantMessageId = withArtifacts.id.toString(),
                                message = withArtifacts,
                                parts = buildCommitParts(withArtifacts, System.currentTimeMillis()),
                                toolRounds = toolLoopResult.toolRounds,
                            ),
                        )
                    if (commitResult is io.zer0.muse.data.chat.rewrite.MessageCommitResult.Rejected) {
                        error("conversation commit rejected")
                    }
                    // B-1: Si la sesión se eliminó, se omitirán los artefactos.,Prevenir la eliminación"resurrección".
                } else if (!host.generationController.isSessionWritesSuppressed(sessionId)) {
                    host.sessionRepository.upsertMessage(sessionId, withArtifacts)
                }
            } catch (e: Exception) {
                // Cuando el envío falla, no puede continuar con la finalización normal; de lo contrario, aparecerá“La ronda se completa pero el texto no está en la biblioteca.”.
                finalMessagePersistenceFailed = true
                Logger.e("ChatVM", "upsertMessage failed", e)
                host.addError(
                    ChatErrorType.UNKNOWN,
                    host.appContext.getString(
                        R.string.err_chat_reply_save_failed,
                        e.message ?: host.appContext.getString(R.string.err_chat_unknown),
                    ),
                )
            }
            host.messageController.rebuildConversationTree()
        }

        if (finalMessagePersistenceFailed) {
            if (ConversationRebuildFlagStore.current.shadowEventsEnabled && state.shadowTurnStarted) {
                host.recordConversationShadow(
                    ConversationEventDraft(
                        sessionId = sessionId,
                        turnId = state.turnId,
                        type = ConversationEventType.TURN_FAILED,
                        streamId = state.streamId,
                        generationSerial = state.generationSerial,
                        payloadJson = "{\"reason\":\"message_persistence_failed\",\"messageId\":\"${state.currentAssistantId}\"}",
                    ),
                )
                host.conversationService.finishTurn(state.turnId, "FAILED")
            }
            host.clearStreamingStateIfLatest(state, finalPhase = ChatStreamPhase.FAILED)
            return false
        }

        return true
    }

    /**
     * Compresión para recuperación de desbordamiento de contexto: use un compresor disponible para comprimir una ronda del historial y devuelva nulo si no está comprimido.
     *
     * Los parámetros se seleccionan según "la mayor presión posible": umbral de disparo forzado (no limitado por el número de elementos), cola retenida más pequeña,
     * Permitir que el marcado visible se trunque cuando falla el resumen. Motivo: Ir aquí significa que el proveedor ha determinado que la solicitud no se puede liberar.
     * Si continúa siendo conservador y "no presione si no hay beneficio neto", solo le arrojará el error original al usuario.
     *
     * El comportamiento y la semántica del umbral del compresor en sí no cambian, aquí solo se lee su valor de retorno.
     *
     * @return historia comprimida;null Indica que la recuperación es imposible (muy pocos mensajes/ningún rango de compresión/ningún beneficio neto/llamada fallida).
     */
    private suspend fun compressForOverflowRecovery(history: List<UIMessage>, sessionId: String): List<UIMessage>? {
        if (history.size < 2) return null
        // Intervalo compresible = SYSTEM Después del prefijo y antes del último mensaje keepRecent, la cola no puede ocupar todos los mensajes.
        val keepRecent = minOf(OVERFLOW_RECOVERY_KEEP_RECENT, history.size - 1).coerceAtLeast(1)
        val context =
            TransformContext(
                sessionId = sessionId,
                modelId = host.stateStore.state.value.currentAssistant?.modelId,
                extras =
                mapOf(
                    "compress_enabled" to true,
                    "compress_threshold" to CompressionPolicy.FORCE_TRIGGER_THRESHOLD,
                    "compress_keep_recent" to keepRecent,
                    "compress_force_fallback" to true,
                    "compress_char_budget" to
                        WarmupHistory.compressTokenBudgetFor(
                            contextMaxTokens = host.stateStore.state.value.contextMaxTokens,
                        ),
                ),
            )
        val compressed =
            resultOf { host.contextCompressTransformer.transform(history, context) }
                .onError { message, t -> Logger.w("ChatVM", "overflow recovery compress failed: $message", t) }
                .getOrNull() ?: return null
        // El número de artículos no ha cambiado. = El compresor regresa sin cambios y la retransmisión solo volverá a llegar a la misma ventana.
        return compressed.takeIf { it.size < history.size }
    }

    /**
     * Constantes copiadas de ChatViewModel.companion(Referencia directa al runToolLoop original).
     * Los valores se copian palabra por palabra.,Garantizar la equivalencia comportamental.
     */
    private companion object {
        const val STREAM_SLIDE_WINDOW = 10
        const val STREAM_THROTTLE_MS = 50L
        const val STREAM_SLICE_BASE = 40
        const val STREAM_SLICE_MIN = 12
        const val STREAM_SLICE_MAX = 240
        const val STREAM_UI_CHAR_THRESHOLD = 12
        const val STREAM_UI_TIME_THRESHOLD_MS = 60L
        const val PERSIST_CHAR_THRESHOLD = 400
        const val PERSIST_TIME_THRESHOLD_MS = 1_500L
        const val AUTO_COMPRESS_MIN_RESERVE_TOKENS = 16_384
        const val AUTO_COMPRESS_RESERVE_RATIO = 0.1
        const val COMPRESSION_SAFETY_TAIL_MESSAGES = 10
        const val MAX_STREAM_RETRIES = 3
        const val MAX_TOOL_ROUNDS = 12
        const val NETWORK_RECOVERY_TIMEOUT_MS = 30_000L

        /** La cantidad de mensajes finales retenidos después de la compresión de recuperación de desbordamiento: más corta que la compresión normal para garantizar que la ventana realmente se pueda liberar. */
        const val OVERFLOW_RECOVERY_KEEP_RECENT = 4
    }
}
