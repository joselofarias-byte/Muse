package io.zer0.muse.web

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.TextTruncation
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.schedule.ChatGenerationManager
import io.zer0.muse.ui.ChatStreamPhase
import io.zer0.muse.ui.ChatUiState
import io.zer0.muse.ui.ChatViewModel
import io.zer0.muse.ui.SsrfGuard
import io.zer0.muse.ui.chat.parseQuotedContent
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.UUID

/**
 * Host Mode WebSocket adapter.
 *
 * ChatViewModel remains the sole owner of generation, persistence, tools and
 * approvals. This adapter only translates browser commands and publishes a
 * session/message snapshot, so the Web client cannot diverge from Android
 * behavior by reimplementing the chat pipeline.
 */
@Suppress("TooManyFunctions")
internal class HostWebSocketGateway(
    private val chatViewModel: ChatViewModel,
    private val generationManager: ChatGenerationManager,
    private val sessionRepo: SessionRepository,
    private val assistantRepo: AssistantRepository,
    private val settingsRepo: SettingsRepository,
    /** Configurar el análisis de redacción publicitaria:Nombre del recurso → Texto localizado(Con el idioma de aplicación actual). */
    private val settingsStrings: HostSettingsStrings,
    /** citas de los medios → `/api/media/<token>` conversión,Comparte la misma cuenta que la ruta de lectura. */
    private val mediaLinks: HostMediaLinks,
    /** Una colección de fuentes de datos para comandos de solo lectura (memoria/base de conocimientos/tareas programadas). */
    private val readSources: HostReadSources = HostReadSources.Empty,
) {

    /** F-39: Token de aislamiento de enfoque/serie multicliente. */
    private val focus = HostFocusTokenHolder()

    /** settings.snapshot / settings.update realización(Incluye lista blanca, verificación de tipos y análisis de redacción.). */
    private val settingsCommands = HostSettingsCommands(RepositorySettingsAccess(settingsRepo), settingsStrings)

    /** session.rename / archive / delete realización(La verificación del enfoque está dentro.). */
    private val sessionCommands = HostSessionCommands(
        focus = focus,
        mutations = object : HostSessionMutations {
            override fun rename(sessionId: String, title: String) = chatViewModel.renameSession(sessionId, title)

            override fun archive(sessionId: String, archived: Boolean) =
                chatViewModel.setSessionArchived(sessionId, archived)

            override fun delete(sessionId: String) = chatViewModel.deleteSession(sessionId)
        },
    )

    private data class ConnectionContext(
        val id: String = UUID.randomUUID().toString(),
        var eventSeq: Long = 0L,
        val history: ArrayDeque<HostEvent> = ArrayDeque(),
        val completedRequestIds: LinkedHashSet<String> = linkedSetOf(),
    )

    private companion object {
        const val MAX_EVENT_HISTORY = 128
        const val MAX_COMPLETED_REQUESTS = 256

        /**
         * `chat.send` Cuánto tiempo esperar para que aparezcan los mensajes de usuario adjuntos con optimismo.
         *
         * Enviar el comando solo significa "entregarlo a ChatViewModel",La identificación del mensaje se genera durante el paso de unirse a la cola.;Espera aquí un rato
         * consíguelo,es dejar `command.accepted` Se puede devolver a la página web para utilizarlo en el estado de representación y se puede utilizar más adelante. `chat.retry` de ese mensaje.
         * Si no puedes esperar, no es un fracaso.:El mensaje ha sido entregado.,solo `messageId` está vacío.
         */
        const val SEND_ACK_TIMEOUT_MS = 2_000L

        /** Límite superior de vista previa del parámetro de herramienta(Alrededor de 500 caracteres). */
        const val TOOL_ARGUMENTS_PREVIEW_LIMIT = 500

        /**
         * El límite máximo de eco para el texto citado (aproximadamente 2000 caracteres).
         *
         * El bloque de referencia en sí es el texto completo del mensaje al que se hace referencia y la instantánea se reenvía con cada incremento de transmisión;2000 palabras suficientes para cubrir citas normales,
         * No enviará un artículo extenso y completo a través de WebSocket repetidamente.`message.quote` Comparte el mismo límite superior que este campo de instantánea.
         */
        const val QUOTE_PREVIEW_LIMIT = 2_000

        /**
         * Límite de vista previa del resultado de la herramienta(Alrededor de 1000 caracteres).
         *
         * leer un archivo/Los resultados de búsqueda en Internet suelen arrojar decenas de resultados. KB,La instantánea se reenvía cada vez que recibe un incremento de transmisión.;
         * 1000 Los caracteres son suficientes para que la página web muestre los dos párrafos antes y después del estado expandido.,Sin enviar el resultado completo a través de WebSocket repetidamente.
         */
        const val TOOL_RESULT_PREVIEW_LIMIT = 1_000

        /** B-31: chat.send Comparte la determinación de texto unificada de la SSRF con otros portales de la red. */
        fun containsBlockedUrl(text: String): Boolean = SsrfGuard.hasBlockedUrlInText(text)

        /** Vista previa de la herramienta Truncar:Mantenga la cabeza y la cola,La cantidad omitida se da en el medio.(Mismo calibre que la compresión de contexto). */
        fun preview(text: String, limit: Int): String = TextTruncation.headTailText(text, limit)
    }

    suspend fun serve(connection: DefaultWebSocketServerSession) = coroutineScope {
        val context = ConnectionContext()
        val sendMutex = Mutex()
        val snapshotJob = launch {
            combine(
                chatViewModel.state,
                chatViewModel.messages,
                generationManager.activeGenerations,
            ) { state, messages, generations ->
                snapshotOf(state, messages, generations[state.currentSessionId])
            }.distinctUntilChanged().collect { event ->
                sendEvent(connection, event, context, sendMutex)
            }
        }
        // F-39: Cuando se establece una nueva conexión, capta la atención.(El titular de los comandos de escritura posteriores.).
        focus.acquire(context.id, chatViewModel.state.value.currentSessionId)
        try {
            sendEvent(connection, currentSnapshot(), context, sendMutex)
            for (frame in connection.incoming) {
                if (frame !is Frame.Text) continue
                try {
                    val command = decodeCommand(frame.data.decodeToString())
                    if (command.protocolVersion != CURRENT_PROTOCOL_VERSION) {
                        throw HostProtocolException("unsupported host protocol version: ${command.protocolVersion}")
                    }
                    if (command.requestId != null && command.requestId in context.completedRequestIds) {
                        sendEvent(
                            connection,
                            HostEvent(type = "command.duplicate", requestId = command.requestId),
                            context,
                            sendMutex,
                        )
                    } else {
                        handle(command, connection, context, sendMutex)
                        command.requestId?.let { rememberCompletedRequest(context, it) }
                    }
                } catch (error: HostProtocolException) {
                    Logger.w("HostWebSocket", "El comando de protocolo falló: ${error.message}", error)
                    sendEvent(
                        connection,
                        HostEvent(type = "error", error = error.message),
                        context,
                        sendMutex,
                    )
                }
            }
        } finally {
            snapshotJob.cancel()
            // F-39: Limpieza del ciclo de vida de la conexión — Liberar el foco cuando esté desconectado,Permita que otros clientes se aprovechen.
            focus.release(context.id)
        }
    }

    @Suppress("CyclomaticComplexMethod", "ThrowsCount", "TooManyFunctions", "LongMethod")
    private suspend fun handle(
        command: HostCommand,
        connection: DefaultWebSocketServerSession,
        context: ConnectionContext,
        sendMutex: Mutex,
    ) {
        val requestId = command.requestId
        when (command.type) {
            "hello" -> sendEvent(
                connection,
                currentSnapshot().copy(requestId = requestId),
                context,
                sendMutex,
            )
            "state.sync" -> syncFromCursor(command, connection, context, sendMutex)
            "session.new" -> {
                // B-30: Cree una nueva sesión escribiendo el comando,Sólo el cliente enfocado puede ejecutar.
                if (!focus.holds(context.id, command.sessionId)) {
                    sendEvent(connection, HostEvent(type = "error", requestId = requestId, error = "focus_not_held"), context, sendMutex)
                    return
                }
                // Existe a priori al especificar un asistente:Crear una sesión que cuelgue de un asistente que no existe hará que la gente piense que se ha seleccionado el asistente equivocado.
                val requestedAssistantId = command.assistantId?.trim()?.takeIf { it.isNotEmpty() }
                if (requestedAssistantId != null && assistantRepo.getById(requestedAssistantId) == null) {
                    sendEvent(
                        connection,
                        HostEvent(type = "error", requestId = requestId, error = "assistant_not_found"),
                        context,
                        sendMutex,
                    )
                    return
                }
                chatViewModel.createNewSession(assistantIdOverride = requestedAssistantId)
                sendEvent(connection, HostEvent(type = "command.accepted", requestId = requestId), context, sendMutex)
            }
            "session.select" -> {
                // F-39: Cambiar de sesión es escribir el comando,Sólo el cliente enfocado puede ejecutar.
                if (!focus.holds(context.id, command.sessionId)) {
                    sendEvent(connection, HostEvent(type = "error", requestId = requestId, error = "focus_not_held"), context, sendMutex)
                    return
                }
                val sessionId = requireSessionId(command)
                chatViewModel.switchSession(sessionId)
                awaitSession(sessionId)
                sendEvent(
                    connection,
                    HostEvent(type = "command.accepted", requestId = requestId, sessionId = sessionId),
                    context,
                    sendMutex,
                )
            }
            "chat.send" -> {
                // F-39: Enviar es un comando de escritura,Sólo el cliente enfocado puede ejecutar.
                if (!focus.holds(context.id, command.sessionId)) {
                    sendEvent(connection, HostEvent(type = "error", requestId = requestId, error = "focus_not_held"), context, sendMutex)
                    return
                }
                val text = command.text?.trim().orEmpty()
                if (text.isEmpty()) throw HostProtocolException("chat.send requires non-empty text")
                // B-31: SSRF protección — Deje que SsrfGuard extraiga del texto http(s) URL Y determine la red privada una por una./bucle invertido/enlace local/
                // IPv6/IP literal,Cualquier acierto es rechazado. Reemplazar el enlace original faltante local/IPv6/Reglas regulares caseras para IP entera,Reutilizar la lógica de juicio unificada.
                if (containsBlockedUrl(text)) {
                    Logger.w("HostWebSocket", "chat.send SSRF interceptar: El texto contiene intranet. URL")
                    throw HostProtocolException("message contains internal URLs (SSRF blocked)")
                }
                command.sessionId?.let { sessionId ->
                    if (chatViewModel.state.value.currentSessionId != sessionId) {
                        chatViewModel.switchSession(sessionId)
                        awaitSession(sessionId)
                    }
                }
                // El objetivo de referencia debe estar dentro de la sesión actual, de lo contrario no se enviará (no se puede traer el texto de otra sesión).
                val quoted = resolveQuotedMessage(command.quotedMessageId)
                if (quoted is QuotedMessage.Rejected) {
                    sendEvent(connection, HostEvent(type = "error", requestId = requestId, error = quoted.reason), context, sendMutex)
                    return
                }
                // Tome una instantánea antes de enviar,El mensaje de usuario agregado después del envío es el ID de mensaje de este momento.
                val previousIds = chatViewModel.messages.value.mapTo(HashSet()) { it.id.toString() }
                // Cita el mecanismo propio de la aplicación: configura responder a antes de enviar, el texto será `> …` El formato se coloca en la biblioteca,
                // Se envía la misma ruta que la referencia del teléfono celular.
                (quoted as? QuotedMessage.Resolved)?.message?.let(chatViewModel::setReplyingTo)
                chatViewModel.updateInput(text)
                chatViewModel.send()
                sendEvent(
                    connection,
                    HostEvent(
                        type = "command.accepted",
                        requestId = requestId,
                        sessionId = chatViewModel.state.value.currentSessionId,
                        messageId = awaitSentUserMessage(previousIds),
                    ),
                    context,
                    sendMutex,
                )
            }
            "chat.retry" -> {
                // Reenviar es un comando de escritura,Mismas reglas que chat.send:Responder sin foco focus_not_held,Y no toques ninguna novedad.
                if (!focus.holds(context.id, command.sessionId)) {
                    sendEvent(connection, HostEvent(type = "error", requestId = requestId, error = "focus_not_held"), context, sendMutex)
                    return
                }
                sendEvent(connection, retryEvent(command), context, sendMutex)
            }
            "tool.approval.resolve" -> {
                // B-12: La aprobación y la liberación son comandos escritos.,Primero debe pasar el aislamiento del foco.,De lo contrario, varios navegadores podrían emitir llamadas a herramientas entre sí simultáneamente.
                if (!focus.holds(context.id, command.sessionId)) {
                    sendEvent(connection, HostEvent(type = "error", requestId = requestId, error = "focus_not_held"), context, sendMutex)
                    return
                }
                val toolCallId = command.generationId?.takeIf { it.isNotBlank() }
                    ?: throw HostProtocolException("tool.approval.resolve requires toolCallId in generationId")
                val decision = command.text?.trim().orEmpty()
                when {
                    // B-12: answered apretar — Esta capa no puede obtener la lista de parámetros declarada por la herramienta para crear una lista blanca de claves.,Para eludir el navegador
                    // El cliente utiliza argOverrides para inyectar claves confidenciales como shell/ruta/comando para sobrescribir los parámetros originales de LLM y omitir la verificación de versión.,
                    // Esta versión deshabilita las anulaciones de parámetros en su totalidad.:answered Equivalente a aprobado,Solo se publican los parámetros sin procesar de LLM(argOverrides fusionar caminos void).
                    decision == "answered" -> chatViewModel.approveToolCall(toolCallId)
                    decision == "approved" -> chatViewModel.approveToolCall(toolCallId)
                    decision == "denied" -> chatViewModel.denyToolCall(toolCallId, "web_denied")
                    else -> throw HostProtocolException("unsupported approval decision: $decision")
                }
                sendEvent(
                    connection,
                    HostEvent(type = "command.accepted", requestId = requestId),
                    context,
                    sendMutex,
                )
            }
            // B-30: Cambiar nombre/Archivar/Eliminar son comandos de escritura — Concéntrese en eventos de verificación y error en HostSessionCommands.
            "session.rename", "session.archive", "session.delete" -> sendEvent(
                connection,
                sessionCommands.handle(command, context.id),
                context,
                sendMutex,
            )
            "chat.regenerate" -> {
                // B-30: Regenerar es un comando de escritura,Sólo el cliente enfocado puede ejecutar.
                if (!focus.holds(context.id, command.sessionId)) {
                    sendEvent(connection, HostEvent(type = "error", requestId = requestId, error = "focus_not_held"), context, sendMutex)
                    return
                }
                chatViewModel.regenerateLastAssistant()
                sendEvent(connection, HostEvent(type = "command.accepted", requestId = requestId), context, sendMutex)
            }
            "chat.continue" -> {
                // B-30: Para continuar generando, escriba el comando,Sólo el cliente enfocado puede ejecutar.
                if (!focus.holds(context.id, command.sessionId)) {
                    sendEvent(connection, HostEvent(type = "error", requestId = requestId, error = "focus_not_held"), context, sendMutex)
                    return
                }
                chatViewModel.continueGeneration()
                sendEvent(connection, HostEvent(type = "command.accepted", requestId = requestId), context, sendMutex)
            }
            "chat.stop" -> {
                // F-39: Detener es un comando de escritura,Sólo el cliente enfocado puede ejecutar.
                if (!focus.holds(context.id, command.sessionId)) {
                    sendEvent(connection, HostEvent(type = "error", requestId = requestId, error = "focus_not_held"), context, sendMutex)
                    return
                }
                chatViewModel.stop()
                sendEvent(
                    connection,
                    HostEvent(
                        type = "command.accepted",
                        requestId = requestId,
                        sessionId = chatViewModel.state.value.currentSessionId,
                    ),
                    context,
                    sendMutex,
                )
            }
            // F-38: Comandos de nivel de mensaje — Copiar/Texto de cotización de regreso al portapapeles del cliente;Los comandos de lectura no requieren verificación de enfoque.
            // Citando requisitos adicionales de que el objetivo pertenece a la sesión actual.:es“Lleve esta oración al cuadro de entrada”,Cita cruzada de otra sesión
            // No hay ningún lugar donde colocarlo en la interfaz. La copia conserva la semántica original.(Obtener solo texto sin formato)sin alterar.
            //
            // con `chat.send.quotedMessageId` división del trabajo:Este comando es“Lea primero el texto citado.”(Vista previa/volver a leer),
            // ese campo es“Enviar con referencia”. El contenido devuelto por ambos es consistente con el diámetro de truncamiento.,Se puede mezclar.
            "message.copy", "message.quote" -> {
                val messageId = command.messageId?.takeIf { it.isNotBlank() }
                    ?: throw HostProtocolException("${command.type} requires messageId")
                val uiMessage = sessionRepo.getMessageAsUiMessage(messageId)
                    ?: throw HostProtocolException("message '$messageId' not found")
                if (command.type == "message.quote" && !belongsToCurrentSession(messageId)) {
                    sendEvent(
                        connection,
                        HostEvent(type = "error", requestId = requestId, error = "message_not_in_current_session"),
                        context,
                        sendMutex,
                    )
                    return
                }
                sendEvent(
                    connection,
                    HostEvent(
                        type = if (command.type == "message.copy") "message.copy" else "message.quote",
                        requestId = requestId,
                        sessionId = chatViewModel.state.value.currentSessionId,
                        messageId = messageId,
                        content = quotePreview(uiMessage.content),
                    ),
                    context,
                    sendMutex,
                )
            }
            // Comando de escritura a nivel de mensaje:Tanto la eliminación como la recopilación solo cambiarán los mensajes visibles de la conversación actual.,Misma regla de enfoque que chat.send.
            "message.delete" -> {
                if (!focus.holds(context.id, command.sessionId)) {
                    sendEvent(connection, HostEvent(type = "error", requestId = requestId, error = "focus_not_held"), context, sendMutex)
                    return
                }
                sendEvent(connection, deleteMessagesEvent(command), context, sendMutex)
            }
            "message.favorite" -> {
                if (!focus.holds(context.id, command.sessionId)) {
                    sendEvent(connection, HostEvent(type = "error", requestId = requestId, error = "focus_not_held"), context, sendMutex)
                    return
                }
                sendEvent(connection, setFavoriteEvent(command), context, sendMutex)
            }
            "history.page" -> {
                val sessionId = requireSessionId(command)
                val limit = (command.limit ?: 50).coerceIn(1, 200)
                val offset = (command.offset ?: 0).coerceAtLeast(0)
                // Reutilizar entradas recientes de límite+compensación en SessionRepository,Entonces recorta esta página en la memoria.(orden ascendente).
                val page = sessionRepo.getRecentMessages(sessionId, offset + limit).drop(offset).take(limit)
                sendEvent(
                    connection,
                    HostEvent(
                        type = "history.page",
                        requestId = requestId,
                        sessionId = sessionId,
                        messages = page.map { it.toHostMessage() },
                    ),
                    context,
                    sendMutex,
                )
            }
            "history.search" -> {
                val query = command.query?.trim().orEmpty()
                if (query.isEmpty()) throw HostProtocolException("history.search requires query")
                val results = sessionRepo.searchMessages(query).take(50)
                val messages = results.map { hit ->
                    HostMessage(
                        id = hit.messageId,
                        role = hit.role.lowercase(),
                        content = hit.content,
                        createdAt = hit.createdAt,
                    )
                }
                sendEvent(
                    connection,
                    HostEvent(
                        type = "history.search",
                        requestId = requestId,
                        content = query,
                        messages = messages,
                    ),
                    context,
                    sendMutex,
                )
            }
            // Establecer una página de inicio:La agrupación y el ingreso los da la App,solo lectura,No se requiere concentración.
            "settings.snapshot" -> sendEvent(
                connection,
                HostEvent(
                    type = "settings.snapshot",
                    requestId = requestId,
                    settings = settingsCommands.snapshot(),
                ),
                context,
                sendMutex,
            )
            // Escritura de configuración:Lista blanca + verificación de tipo,Las claves fuera de la lista blanca devuelven un evento de error en lugar de ignorarse silenciosamente.
            //
            // aquí**No comprobar el foco de la sesión**:Hay una razón por la cual las escrituras de sesiones deben serializarse(Evitar que dos clientes introduzcan mensajes en la misma sesión al mismo tiempo),
            // La configuración global no tiene este problema——Mientras charla por teléfono,No se debe rechazar cambiar un interruptor en una página web;
            // Simplemente cambie la misma clave en ambos extremos al mismo tiempo y luego escriba sobre ella.
            //
            // Solo responde si la escritura es exitosa `command.accepted`:Sigue el nuevo valor**La próxima vez** `settings.snapshot` Problema,
            // No recomiendo una porción extra aquí. Lo que envía el cliente es el valor que quiere mostrar localmente.,Extraiga una instantánea para alinear los resultados normalizados
            // (Por ejemplo, eliminar puntos decimales de los números y enumerar mayúsculas y minúsculas),Evite presionar un árbol de configuración completo para cada interruptor.
            "settings.update" -> {
                when (val result = settingsCommands.update(command.key, command.value)) {
                    is HostSettingsUpdateResult.Applied -> sendEvent(
                        connection,
                        HostEvent(type = "command.accepted", requestId = requestId),
                        context,
                        sendMutex,
                    )
                    is HostSettingsUpdateResult.Rejected -> sendEvent(
                        connection,
                        HostEvent(type = "error", requestId = requestId, error = result.reason),
                        context,
                        sendMutex,
                    )
                }
            }
            // Vista de solo lectura:Ayudante/proveedor/metainformación de sesión.
            "assistants.list" -> sendEvent(
                connection,
                HostReadCommands.assistantsEvent(
                    assistants = assistantRepo.getAll(),
                    defaultAssistantId = settingsRepo.defaultAssistantIdFlow.first(),
                    requestId = requestId,
                    mediaLinks = mediaLinks,
                ),
                context,
                sendMutex,
            )
            "providers.list" -> sendEvent(
                connection,
                HostReadCommands.providersEvent(
                    providers = settingsRepo.providersFlow.first(),
                    selectedModelId = settingsRepo.selectedModelIdFlow.first(),
                    requestId = requestId,
                ),
                context,
                sendMutex,
            )
            "sessions.detail" -> {
                val sessionId = requireSessionId(command)
                val session = sessionRepo.getSessionById(sessionId)
                // No consultar la tabla de mensajes cuando la sesión no existe,Regrese directamente a session_not_found.
                val messageCount = if (session == null) 0 else sessionRepo.getMessageCount(sessionId)
                sendEvent(
                    connection,
                    HostReadCommands.sessionDetailEvent(session, messageCount, requestId),
                    context,
                    sendMutex,
                )
            }
            "assistants.detail" -> {
                val assistantId = command.assistantId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw HostProtocolException("assistants.detail requires assistantId")
                sendEvent(
                    connection,
                    HostReadCommands.assistantDetailEvent(
                        assistant = assistantRepo.getById(assistantId),
                        requestId = requestId,
                        mediaLinks = mediaLinks,
                    ),
                    context,
                    sendMutex,
                )
            }
            // memoria solo lectura:sesión/Lista de entradas y recuperación de palabras clave en el nivel del grupo de memoria principal(Consulte las instrucciones de anulación para HostMemoryReads).
            "memory.list" -> sendEvent(
                connection,
                HostMemoryReads.listEvent(
                    records = readSources.memory.entries(command.assistantId),
                    cursor = command.cursor?.toLongOrNull(),
                    limit = command.limit ?: HostMemoryReads.DEFAULT_LIMIT,
                    requestId = requestId,
                ),
                context,
                sendMutex,
            )
            "memory.search" -> {
                val query = command.query?.trim().orEmpty()
                if (query.isEmpty()) throw HostProtocolException("memory.search requires query")
                val limit = command.limit ?: HostMemoryReads.DEFAULT_LIMIT
                sendEvent(
                    connection,
                    HostMemoryReads.searchEvent(
                        query = query,
                        records = readSources.memory.search(query, command.assistantId, limit),
                        requestId = requestId,
                    ),
                    context,
                    sendMutex,
                )
            }
            // Página de categoría de conocimiento y automatización:Todo solo lectura,No participa en el bloqueo de enfoque.(Hermano con asistentes.lista).
            "knowledge.list" -> sendEvent(
                connection,
                HostKnowledgeReads.listEvent(readSources.knowledge.bases(), requestId),
                context,
                sendMutex,
            )
            "knowledge.documents" -> {
                val kbId = command.kbId?.trim().orEmpty()
                if (kbId.isEmpty()) throw HostProtocolException("knowledge.documents requires kbId")
                sendEvent(
                    connection,
                    HostKnowledgeReads.documentsEvent(
                        kbId = kbId,
                        page = readSources.knowledge.documents(
                            kbId = kbId,
                            cursor = command.cursor?.trim()?.takeIf { it.isNotEmpty() },
                            limit = HostKnowledgeReads.pageLimit(command.limit),
                        ),
                        requestId = requestId,
                    ),
                    context,
                    sendMutex,
                )
            }
            "tasks.list" -> sendEvent(
                connection,
                HostTaskReads.listEvent(readSources.tasks.tasks(), requestId),
                context,
                sendMutex,
            )
            // Estado del sistema:Instantánea de solo lectura de la página de datos y privacidad/página específica de la plataforma,No participa en el bloqueo del enfoque.
            // Las estadísticas de volumen pasan por el programador de IO dentro de la fuente de datos.,No bloquee esta rutina de conexión.
            "system.status" -> sendEvent(
                connection,
                HostSystemStatusReads.event(readSources.system.status(), requestId),
                context,
                sendMutex,
            )
            else -> throw HostProtocolException("unsupported host command: ${command.type}")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun decodeCommand(text: String): HostCommand = try {
        AppJson.decodeFromString<HostCommand>(text)
    } catch (error: Exception) {
        throw HostProtocolException(
            "invalid host command: ${error.message ?: "malformed JSON"}",
            error,
        )
    }

    private suspend fun awaitSession(sessionId: String) {
        try {
            withTimeout(5_000L) {
                chatViewModel.state.first { it.currentSessionId == sessionId }
            }
        } catch (error: TimeoutCancellationException) {
            throw HostProtocolException(
                "session '$sessionId' did not become active within 5 seconds",
                error,
            )
        }
    }

    /** Espere a que aparezcan mensajes de nuevos usuarios agregados con optimismo;Regreso del tiempo de espera null,No afecta a la aceptación del pedido en sí. */
    private suspend fun awaitSentUserMessage(previousIds: Set<String>): String? {
        val message = withTimeoutOrNull(SEND_ACK_TIMEOUT_MS) {
            chatViewModel.messages
                .mapNotNull { messages -> messages.firstOrNull { it.role == MessageRole.USER && it.id.toString() !in previousIds } }
                .first()
        } ?: return null
        return message.id.toString()
    }

    /**
     * `chat.retry` adjudicación y ejecución(El foco ha sido verificado en la rama de comando.).
     *
     * No regreses cuando el objetivo no sea el correcto. `command.accepted`,En su lugar, se proporciona un motivo de error legible.,Evite la página web
     * Se inició la retransmisión del pensamiento.
     */
    @Suppress("ThrowsCount")
    private fun retryEvent(command: HostCommand): HostEvent {
        val messageId = command.messageId?.takeIf { it.isNotBlank() }
            ?: throw HostProtocolException("chat.retry requires messageId")
        return when (retryTarget(messageId)) {
            RetryTarget.NOT_FOUND -> HostEvent(
                type = "error",
                requestId = command.requestId,
                error = "message_not_found",
            )

            RetryTarget.NOT_SUPPORTED -> HostEvent(
                type = "error",
                requestId = command.requestId,
                error = "retry_unsupported_message",
            )

            RetryTarget.RETRYABLE -> {
                chatViewModel.regenerateLastAssistant()
                HostEvent(type = "command.accepted", requestId = command.requestId, messageId = messageId)
            }
        }
    }

    /**
     * `chat.retry` ¿Puede caer en este mensaje?
     *
     * App Solo hay una entrada de reedición en:"Reintentar" en el banner de error,Regenera la última respuesta de ayuda para la rama actual.
     * Así que aquí sólo hay dos tipos de objetivos.:Último mensaje del asistente(La propia burbuja del fracaso)con el último mensaje de usuario
     * (La entrega no se entregó y es necesario volver a ejecutar la ronda.). Todos los demás mensajes serán rechazados. —— El objetivo está equivocado pero renace silenciosamente en otro.,
     * Mucho más peligroso que el rechazo.
     */
    private fun retryTarget(messageId: String): RetryTarget {
        val messages = chatViewModel.messages.value
        val target = messages.firstOrNull { it.id.toString() == messageId } ?: return RetryTarget.NOT_FOUND
        val lastAssistantId = messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
        val lastUserId = messages.lastOrNull { it.role == MessageRole.USER }?.id
        return when {
            target.role == MessageRole.ASSISTANT && target.id == lastAssistantId -> RetryTarget.RETRYABLE
            target.role == MessageRole.USER && target.id == lastUserId -> RetryTarget.RETRYABLE
            else -> RetryTarget.NOT_SUPPORTED
        }
    }

    private enum class RetryTarget { RETRYABLE, NOT_FOUND, NOT_SUPPORTED }

    /** Si el mensaje de destino pertenece a la sesión actual;Si no se puede encontrar la conversación o no se puede encontrar el mensaje, no pertenece. */
    private suspend fun belongsToCurrentSession(messageId: String): Boolean {
        val currentSessionId = chatViewModel.state.value.currentSessionId ?: return false
        return sessionRepo.getMessageById(messageId)?.sessionId == currentSessionId
    }

    /** `chat.send.quotedMessageId` resultados del análisis. */
    private sealed interface QuotedMessage {
        /** Si no hay ningún objetivo de referencia, se enviará con normalidad. */
        data object None : QuotedMessage

        data class Resolved(val message: UIMessage) : QuotedMessage

        data class Rejected(val reason: String) : QuotedMessage
    }

    /**
     * Resolver objetivos de referencia.
     *
     * Reconocer solo mensajes dentro de la sesión actual: enviar con el id de la sesión anterior equivale a enviar el texto entre sesiones, y directamente rechaza y no envía;
     * Si no se puede encontrar el mensaje en la sesión actual (se acaba de eliminar), se dará un error claro y no se degradará silenciosamente al envío normal. ——
     * La desaparición repentina de la barra de referencia que ven los usuarios es más difícil de entender que informar un error.
     */
    private suspend fun resolveQuotedMessage(quotedMessageId: String?): QuotedMessage {
        val id = quotedMessageId?.trim()?.takeIf { it.isNotEmpty() } ?: return QuotedMessage.None
        if (!belongsToCurrentSession(id)) return QuotedMessage.Rejected("message_not_in_current_session")
        val target = sessionRepo.getMessageAsUiMessage(id) ?: return QuotedMessage.Rejected("message_not_found")
        return QuotedMessage.Resolved(target)
    }

    /**
     * `message.delete` adjudicación y ejecución.
     *
     * Elimina solo los mensajes que realmente existen en la sesión actual: si no existen, ingresa el ID de otra sesión. `skippedMessageIds` en lugar de informar un error
     * (Eliminar un mensaje que haya sido eliminado por otro terminal, y“Eliminado exitosamente”Tampoco debería hacer que la interfaz se ponga roja).
     * Todo el comando es atómico: siempre que un objetivo sea el último mensaje de ayuda que se genera, se rechaza todo el lote.
     * no aparece“Se eliminó la mitad”estado intermedio.
     */
    @Suppress("ThrowsCount")
    private fun deleteMessagesEvent(command: HostCommand): HostEvent {
        val requestId = command.requestId
        val requested = command.messageIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (requested.isEmpty()) throw HostProtocolException("message.delete requires messageIds")
        val existingIds = chatViewModel.messages.value.mapTo(HashSet()) { it.id.toString() }
        val targets = requested.filter { it in existingIds }
        if (targets.isNotEmpty() && isStreamingReply(targets)) {
            return HostEvent(type = "error", requestId = requestId, error = "message_is_streaming")
        }
        val messageIds = targets.mapNotNull { runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull() }
        chatViewModel.deleteMessages(messageIds)
        return HostEvent(
            type = "command.accepted",
            requestId = requestId,
            sessionId = chatViewModel.state.value.currentSessionId,
            deletedCount = messageIds.size,
            skippedMessageIds = requested.filterNot { it in existingIds },
        )
    }

    /** ¿Hay alguno en el objetivo?“Se genera la respuesta del asistente.”: Streaming y el último mensaje del asistente de la sesión. */
    private fun isStreamingReply(targets: List<String>): Boolean {
        if (!chatViewModel.state.value.isStreaming) return false
        val lastAssistantId = chatViewModel.messages.value
            .lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.id?.toString()
            ?: return false
        return lastAssistantId in targets
    }

    /**
     * `message.favorite` adjudicación y ejecución.
     *
     * El estado objetivo lo proporciona la persona que llama (no se ve afectado por el valor actual), por lo que repetir el mismo estado es idempotente:
     * No cambiará cuando ya sea el valor objetivo y no se informará ningún error.
     */
    @Suppress("ThrowsCount")
    private suspend fun setFavoriteEvent(command: HostCommand): HostEvent {
        val requestId = command.requestId
        val messageId = command.messageId?.takeIf { it.isNotBlank() }
            ?: throw HostProtocolException("message.favorite requires messageId")
        val favored = command.favored
            ?: throw HostProtocolException("message.favorite requires favored")
        val messageUuid = runCatching {
            kotlin.uuid.Uuid.parse(messageId)
        }.getOrNull()
        if (messageUuid == null || sessionRepo.getMessageById(messageId) == null) {
            return HostEvent(type = "error", requestId = requestId, error = "message_not_found")
        }
        chatViewModel.setMessageFavorite(messageUuid, favored)
        return HostEvent(type = "command.accepted", requestId = requestId, messageId = messageId)
    }

    private fun requireSessionId(command: HostCommand): String = command.sessionId?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw HostProtocolException("${command.type} requires sessionId")

    private suspend fun syncFromCursor(
        command: HostCommand,
        connection: DefaultWebSocketServerSession,
        context: ConnectionContext,
        sendMutex: Mutex,
    ) {
        // El número de secuencia del evento es un número.,Los campos del cursor se recopilan como texto.,Devuelve el valor aquí.
        val requestedCursor = command.cursor?.toLongOrNull()
        val history = context.history.toList()
        val canReplay = requestedCursor != null &&
            (history.isEmpty() || requestedCursor >= history.first().eventSeq - 1L)
        if (canReplay) {
            val replay = history.filter { it.eventSeq > requestedCursor }
            if (replay.isNotEmpty()) {
                replay.forEachIndexed { index, event ->
                    sendRawEvent(
                        connection,
                        event.copy(requestId = if (index == replay.lastIndex) command.requestId else null),
                        sendMutex,
                    )
                }
                return
            }
        }
        sendEvent(
            connection,
            currentSnapshot().copy(requestId = command.requestId),
            context,
            sendMutex,
        )
    }

    private fun rememberCompletedRequest(context: ConnectionContext, requestId: String) {
        context.completedRequestIds += requestId
        while (context.completedRequestIds.size > MAX_COMPLETED_REQUESTS) {
            context.completedRequestIds.remove(context.completedRequestIds.first())
        }
    }

    private suspend fun sendRawEvent(connection: DefaultWebSocketServerSession, event: HostEvent, sendMutex: Mutex) {
        sendMutex.withLock { connection.send(Frame.Text(AppJson.encodeToString(event))) }
    }

    private suspend fun sendEvent(
        connection: DefaultWebSocketServerSession,
        event: HostEvent,
        context: ConnectionContext,
        sendMutex: Mutex,
    ): HostEvent {
        val sequenced = event.copy(
            connectionId = context.id,
            eventSeq = context.eventSeq++,
            cursor = context.eventSeq,
        )
        context.history.addLast(sequenced)
        while (context.history.size > MAX_EVENT_HISTORY) context.history.removeFirst()
        sendMutex.withLock { connection.send(Frame.Text(AppJson.encodeToString(sequenced))) }
        return sequenced
    }

    private fun currentSnapshot(): HostEvent {
        val state = chatViewModel.state.value
        return snapshotOf(
            state,
            chatViewModel.messages.value,
            generationManager.activeGenerations.value[state.currentSessionId],
        )
    }

    private fun snapshotOf(
        state: ChatUiState,
        messages: List<UIMessage>,
        active: ChatGenerationManager.ActiveGeneration? = null,
    ): HostEvent {
        val statusContext = MessageStatusContext.of(state, messages)
        return HostEvent(
            type = "state.snapshot",
            sessionId = state.currentSessionId,
            turnId = active?.turnId,
            generationId = active?.generationId,
            isStreaming = state.isStreaming,
            // Las sesiones archivadas se entregan como de costumbre (se distinguen por la etiqueta de archivado y la página web se filtra según su propio parámetro);
            // Sólo aquellos que realmente están eliminados (deletedAt no vacío).
            sessions = state.sessions.filterNot { it.deletedAt != null }.map { it.toHostSession() },
            messages = messages.map { it.toHostMessage(statusContext) },
            pendingApprovals = chatViewModel.state.value.pendingToolApprovals.map {
                HostApproval(it.toolCallId, it.toolName, it.argumentsPreview)
            },
            // F-36: capabilityFlags Declare únicamente la capacidad de implementar los comandos de protocolo correspondientes.
            // memory Este lote es sólo de lectura. memory.list / memory.search(sesión/Capa de grupo de memoria principal),
            // No existen comandos correspondientes para escritura en memoria y RAG.,No se indica aquí.
            capabilityFlags = listOf(
                "chat",
                "streaming",
                "generation_control",
                "session_crud",
                "tool_approval",
                "history",
                "message_clipboard",
                "android_runtime",
                "settings_snapshot",
                "settings_update",
                "assistants",
                "assistant_detail",
                "providers",
                "session_detail",
                "media",
                "tool_call_details",
                "message_status",
                "message_write",
                "message_multi_select",
                "memory_list",
                "memory_search",
                "knowledge",
                "tasks",
                "system_status",
            ),
        )
    }

    /**
     * Contexto fuera de la instantánea:en la misma lista de mensajes,¿Cuál es la "ronda en curso"?
     *
     * App No persiste el estado a nivel de mensaje(Solo banners de error/transmisión a nivel de sesión),Pon esas señales aquí.
     * Proyectar en un solo mensaje,La semántica es consistente con los comentarios vistos en la aplicación.:
     *  - Último mensaje del asistente + Streaming = streaming;
     *  - El último mensaje del asistente + la ronda terminó con FAILED y el error no se desactivó = failed(La entrada de reintento para el mensaje de error está ahí.);
     *  - El último mensaje es un mensaje de usuario y el primer token aún no ha sido respondido. = sending(La adición optimista ahora está en la pantalla,Aún no entregado al modelo.).
     *
     * paginación de historia(history.page)Los mensajes aquí son todos mensajes anteriores que se han incluido en la base de datos.,usar [Idle]:Siempre enviado.
     */
    private data class MessageStatusContext(
        val lastMessageId: String?,
        val lastAssistantId: String?,
        val streaming: Boolean,
        val waitingFirstToken: Boolean,
        val failed: Boolean,
        val errorText: String?,
    ) {
        companion object {
            val Idle = MessageStatusContext(
                lastMessageId = null,
                lastAssistantId = null,
                streaming = false,
                waitingFirstToken = false,
                failed = false,
                errorText = null,
            )

            fun of(state: ChatUiState, messages: List<UIMessage>): MessageStatusContext = MessageStatusContext(
                lastMessageId = messages.lastOrNull()?.id?.toString(),
                lastAssistantId = messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id?.toString(),
                streaming = state.isStreaming,
                waitingFirstToken = state.isWaitingFirstToken,
                // Este mensaje se considera un error solo si la fase de generación se detiene en FAILED y el error aún se muestra.;Al cerrar el banner de error, los usuarios aceptan el status quo.
                failed = !state.isStreaming &&
                    state.streamState.phase == ChatStreamPhase.FAILED &&
                    state.errors.isNotEmpty(),
                errorText = state.errors.lastOrNull()?.message,
            )
        }
    }

    private fun SessionEntity.toHostSession(): HostSession = HostSession(
        id = id,
        title = title,
        assistantId = assistantId,
        updatedAt = updatedAt,
        archived = archived,
        pinned = pinned,
    )

    private fun UIMessage.toHostMessage(context: MessageStatusContext = MessageStatusContext.Idle): HostMessage {
        val status = statusOf(context)
        return HostMessage(
            id = id.toString(),
            role = role.name.lowercase(),
            content = content,
            reasoning = reasoning,
            modelId = modelId,
            createdAt = createdAt,
            imageUrls = mediaLinks.links(imageUrls, id.toString()),
            toolCallId = toolCallId,
            toolName = toolCallInfo?.toolName,
            citationUrls = citationUrls,
            voiceUrl = mediaLinks.voiceLink(voiceUri, id.toString()),
            voiceDurationMs = voiceDurationMs.takeIf { it > 0L },
            toolCalls = hostToolCalls(),
            status = status,
            errorText = if (status == HostMessageStatus.FAILED) context.errorText else null,
            quotedContent = quotedContentOf(),
        )
    }

    /**
     * Cite el texto citado en la respuesta.
     *
     * La misma regla que las burbujas de mensajes de la aplicación: los campos primero (editar/estado borrador), de lo contrario el mensaje del usuario será analizado `> ` bloque de cotización al principio
     * —— Al enviar, el texto se almacena en este formato, por lo que la instantánea puede restaurar la tarjeta de referencia tal como está.
     */
    private fun UIMessage.quotedContentOf(): String? {
        val raw = quotedContent ?: if (role == MessageRole.USER) parseQuotedContent(content).first else null
        return raw?.takeIf { it.isNotBlank() }?.let(::quotePreview)
    }

    /** Corte de cotización:`message.quote` con la instantánea `quotedContent` comparte esta función. */
    private fun quotePreview(text: String): String = TextTruncation.headTailText(text, QUOTE_PREVIEW_LIMIT)

    private fun UIMessage.statusOf(context: MessageStatusContext): String {
        val messageId = id.toString()
        return when {
            context.failed && role == MessageRole.ASSISTANT && messageId == context.lastAssistantId ->
                HostMessageStatus.FAILED

            context.streaming && role == MessageRole.ASSISTANT && messageId == context.lastAssistantId ->
                HostMessageStatus.STREAMING

            context.streaming && context.waitingFirstToken && role == MessageRole.USER &&
                messageId == context.lastMessageId -> HostMessageStatus.SENDING

            else -> HostMessageStatus.SENT
        }
    }

    /**
     * Llamada de herramienta para este mensaje.
     *
     * Dos fuentes se combinan en una matriz.:[io.zer0.ai.core.UIMessage.toolCalls] Es una solicitud de llamada realizada por el asistente una vez
     * (Sólo nombres y parámetros.,El resultado no está en este mensaje.),[io.zer0.ai.core.UIMessage.toolCallInfo] Es una tarjeta de herramientas con resultados.
     * Las solicitudes pendientes con el mismo nombre de herramienta se fusionarán en la tarjeta de herramientas.,No habrá duplicación de dos artículos.
     */
    private fun UIMessage.hostToolCalls(): List<HostToolCall> {
        val calls = toolCalls?.map { request ->
            HostToolCall(
                callId = request.id,
                name = request.name,
                argumentsPreview = preview(request.arguments, TOOL_ARGUMENTS_PREVIEW_LIMIT),
            )
        }?.toMutableList() ?: mutableListOf()
        val info = toolCallInfo ?: return calls
        val pending = calls.indexOfFirst { it.name == info.toolName && it.resultPreview == null }
        val settled = HostToolCall(
            callId = if (pending >= 0) calls[pending].callId else toolCallId ?: "$id:tool",
            name = info.toolName,
            argumentsPreview = preview(info.arguments, TOOL_ARGUMENTS_PREVIEW_LIMIT),
            // El resultado es nulo en lugar de una cadena vacía.:La página web diferencia entre "no se devolvieron resultados" y "se devolvieron resultados vacíos" basándose en esto.
            resultPreview = info.result.takeIf { it.isNotBlank() }?.let { preview(it, TOOL_RESULT_PREVIEW_LIMIT) },
            success = info.isSuccess,
            durationMs = info.durationMs,
        )
        if (pending >= 0) calls[pending] = settled else calls.add(settled)
        return calls
    }
}
