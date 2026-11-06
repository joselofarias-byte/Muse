package io.zer0.muse.tools

import android.content.Context
import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.groupchat.GroupChatRepository
import io.zer0.muse.data.plugin.PluginManager
import io.zer0.muse.data.skill.SkillEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * Phase 8.8: Skill actuador(Kotlin Realización directa,No hay necesidad QuickJS).
 *
 * Según [SkillEntity.implementationKotlin] Ruta a la ejecución de la función Kotlin predefinida.
 * Actualmente admite 4 integrados skill:
 *  - read_file — Leer archivos en el entorno limitado de la aplicación
 *  - write_file — Escribir archivos en el entorno limitado de la aplicación
 *  - http_get — HTTP GET Solicitar
 *  - http_post — HTTP POST Solicitar
 *
 * principios de diseño:
 *  - Las operaciones de archivos están restringidas al entorno limitado de la aplicación.(filesDir/cacheDir),Evite la lectura y escritura no autorizadas
 *  - HTTP Solicitar tiempo de espera establecido en 30 segundos,Cuerpo de respuesta máximo 1MB
 *  - Todas las IO se realizan en IO Dispatcher
 *
 * @param context Solicitud Context(usado para filesDir / cacheDir)
 * @param client OkHttpClient(http_get / http_post usar,Inyectado por Koin named("chat"))
 */
class SkillExecutor(
    private val context: Context,
    private val client: OkHttpClient,
    /** v0.24: web_search usar(puede ser null,Sin inyección durante la prueba). */
    private val webSearchService: io.zer0.muse.web.WebSearchService? = null,
    /** v0.24: knowledge_search usar(puede ser null). */
    private val knowledgeDocDao: io.zer0.muse.data.knowledge.KnowledgeDocDao? = null,
    /** v0.24: install_skill usar(puede ser null,Evite las dependencias circulares). */
    private val skillRepository: io.zer0.muse.data.skill.SkillRepository? = null,
    /** v0.46: delegate_agent usar — Tune Assistant realiza una ronda de LLM. */
    private val chatService: ChatService,
    /** v0.46: delegate_agent usar — Obtenga la configuración del subasistente basada en asistenteId. */
    private val assistantRepository: AssistantRepository,
    /** v1.30: herramienta de chat grupal(channel_reply / channel_pass / channel_read_context)Usar. */
    private val groupChatRepository: GroupChatRepository? = null,
    /** v1.54: knowledge_search Para búsqueda de vectores(puede ser null,Bajar a ME GUSTA la búsqueda). */
    private val ragService: io.zer0.muse.rag.RagService? = null,
    /** v1.54: Obtener configuración RAG(Selección de proveedor de incrustación para la recuperación de vectores). */
    private val ragConfigProvider: suspend () -> io.zer0.muse.rag.RagConfig = { io.zer0.muse.rag.RagConfig() },
    /** v1.95: herramienta de emoticonos(list_stickers / send_sticker)Usar. */
    private val stickerLibraryRepository: io.zer0.muse.data.sticker.StickerLibraryRepository? = null,
    /** v1.???: generate_image Usar. */
    private val imageService: io.zer0.ai.image.ImageService? = null,
    private val imageDrawConfigProvider: suspend () -> Pair<io.zer0.ai.core.ProviderConfig?, String?> = { null to null },
    /** v1.200: Configuración del equipo multiagente proporcionada,Utilizado por delegadoAgent para manejar objetivos de EQUIPO. */
    private val multiAgentConfigProvider: () -> io.zer0.muse.data.MultiAgentConfig = { io.zer0.muse.data.MultiAgentConfig() },
    /** v1.201: LLM Agregador de reseñas completo,TeamWorkflowExecutor La estrategia LLM_REVIEW se utiliza cuando;Se degrada a EXPERT_REVIEW cuando es nulo. */
    private val llmAggregator: LlmAggregator? = null,
    /** v1.201: Gestor de pausas delegado,null para omitir todos los puntos de pausa. */
    private val pauseManager: DelegationPauseManager? = null,
    /** v1.201: Rastreador de enlaces delegados,null El enlace no está grabado.ChatViewModel Comparta la misma instancia para la presentación de la interfaz de usuario. */
    private val delegationChainTracker: DelegationChainTracker? = null,
    /** v1.202: Agent Almacén de mensajes privados,Se utiliza para rellenar el resultado en uno una vez completada la delegación. sub-agent → main-agent DM.
     *  No enviar cuando sea nulo DM(Entorno de prueba o degradación cuando no se inyecta). */
    private val agentDmRepository: io.zer0.muse.data.agentdm.AgentDmRepository? = null,
    /** v1.202: Comentarios sobre los resultados de la delegación asincrónica(Infraestructura central de delegación sin bloqueo).
     *  cuando es nulo nonBlocking=true Bajar automáticamente al modo de bloqueo. */
    private val deferredResultStore: DeferredResultStore? = null,
    /** v1.202: Administrador de hilos de subagente(Hilo de sesión de agente secundario reanudable,La ejecución en serie evita la contención de simultaneidad).
     *  cuando es nulo nonBlocking=true Bajar automáticamente al modo de bloqueo. */
    private val subagentThreadStore: io.zer0.muse.data.subagent.SubagentThreadStore? = null,
    /** v1.0.53: Limitador de simultaneidad global de subagente(Koin inyectar). Todos los portales de delegación(TeamWorkflowExecutor nodo paralelo / delegateAgent nonBlocking / SubagentRunner)comparten la misma cuota. */
    private val agentConcurrencyLimiter: AgentConcurrencyLimiter,
    /** v1.0.53 Phase 2: Registro de recuperación de puntos de interrupción del flujo de trabajo(Koin inyectar);null TeamWorkflowExecutor omite la lectura y escritura del diario. */
    private val journal: WorkflowJournal? = null,
    /**
     * v1.0.53 Phase 5: GroupChatScheduler Carga diferida provider(Evite dependencias circulares con SkillExecutor).
     *
     * agent_phone Se llama cuando la herramienta activa un chat privado susurrado. [io.zer0.muse.schedule.GroupChatScheduler.launchWhisper].
     * Análisis diferido utilizando la forma lambda:SkillExecutor Regístrese en Koin antes de GroupChatScheduler,
     * Pero dentro de lambda get() Se analiza sólo cuando la herramienta se ejecuta realmente.,En este punto, GroupChatScheduler se ha inicializado.
     *
     * cuando es nulo(entorno de prueba)agent_phone devoluciones de herramientas"No configurado"Consejos.
     */
    private val groupChatSchedulerProvider: (() -> io.zer0.muse.schedule.GroupChatScheduler?)? = null,
    /** B6-01: Administrador de complementos externo(puede ser null,El entorno de prueba no inyecta). */
    private val pluginManager: PluginManager? = null,
    /** P1-3b: Archivo/Implementación de herramienta de directorio público(puede ser null,El entorno de prueba no inyecta). */
    private val fileTools: SkillFileToolsImpl? = null,
    /** P1-3b: Buscar/HTTP Implementación de herramientas(puede ser null,El entorno de prueba no inyecta). */
    private val searchTools: SkillSearchToolsImpl? = null,
    /** P1-3b: Skill Implementación de herramientas de gestión.(puede ser null,El entorno de prueba no inyecta). */
    private val managementTools: SkillManagementToolsImpl? = null,
    /** P1-3b: medios de comunicación/JS/Implementación de herramientas de complemento(puede ser null,El entorno de prueba no inyecta). */
    private val mediaTools: SkillMediaToolsImpl? = null,
    /** P1-3b: Implementación de herramienta de traducción(puede ser null,El entorno de prueba no inyecta). */
    /** P1-3e: Agent Flujo de trabajo/Implementación de herramienta de chat grupal(puede ser null,El entorno de prueba no inyecta). */
    private val agentTools: SkillAgentToolsImpl? = null,
    /** P1-3e: delegateAgent darse cuenta(puede ser null,El entorno de prueba no inyecta). */
    private val delegateTools: SkillDelegateAgentImpl? = null,
    private val translateTools: TranslateToolsImpl? = null,
    /** Implementación de la herramienta de complemento autoescrita del asistente(puede ser null,El entorno de prueba no inyecta). */
    private val pluginAuthoringTools: PluginAuthoringToolsImpl? = null,
) {
    /**
     * Ejecutar habilidad.
     * @param skill skill entidad(Contiene implementación de enrutamiento Kotlin key)
     * @param argumentsJson LLM La cadena JSON del parámetro pasado
     * @param onProgress v0.49: Devolución de llamada de progreso,herramientas que requieren mucho tiempo(web_fetch/web_search/delegate_agent/install_skill)
     *                   Llamado antes de la ejecución.,Para que la interfaz de usuario se muestre en TaskStep"Buscando..."Esperando texto de progreso. Implementación vacía predeterminada,No interrumpe las llamadas existentes.
     * @return Cadena de resultado de ejecución
     */
    suspend fun execute(
        skill: SkillEntity,
        argumentsJson: String,
        onProgress: (String) -> Unit = {},
        turnKey: String = "default",
        sessionId: String = "default",
    ): String = withContext(Dispatchers.IO) {
        val args = ToolArgsParser.parse(argumentsJson, skill.id)
        // H-SE1: Usar en su lugar resultOf{}(Lanzamiento correcto CancellationException),Evite runCatching al tragar señales de cancelación de rutina
        resultOf {
            when (skill.implementationKotlin) {
                "read_file" -> fileTools?.execReadFile(args) ?: context.getString(R.string.skill_impl_not_configured)
                "write_file" -> fileTools?.execWriteFile(args) ?: context.getString(R.string.skill_impl_not_configured)
                "http_get" -> searchTools?.execHttpGet(args) ?: context.getString(R.string.skill_impl_not_configured)
                "http_post" -> searchTools?.execHttpPost(args) ?: context.getString(R.string.skill_impl_not_configured)
                // v0.24: Búsqueda y adquisición de información.
                "web_search" -> {
                    onProgress(context.getString(R.string.skill_progress_searching))
                    searchTools?.execWebSearch(args, turnKey) ?: context.getString(R.string.skill_impl_not_configured)
                }
                "web_fetch" -> {
                    onProgress(context.getString(R.string.skill_progress_fetching))
                    searchTools?.execWebFetch(args) ?: context.getString(R.string.skill_impl_not_configured)
                }
                "knowledge_search" -> searchTools?.execKnowledgeSearch(args) ?: context.getString(R.string.skill_impl_not_configured)
                "arxiv_search" -> searchTools?.execArxivSearch(args) ?: context.getString(R.string.skill_impl_not_configured)
                // v0.24: autoexpansión(install_skill = LLM Generar definiciones de habilidades y almacenarlas en la base de datos.)
                "install_skill" -> {
                    onProgress(context.getString(R.string.skill_progress_installing))
                    managementTools?.installSkill(args) ?: context.getString(R.string.skill_impl_not_configured)
                }
                // Complemento autoescrito del asistente:Sólo produce borradores sin firmar.,Solo se puede ejecutar después de que el usuario lo firme y lo habilite en la administración de complementos.
                "author_plugin" -> {
                    onProgress(context.getString(R.string.plugin_author_progress))
                    pluginAuthoringTools?.authorPlugin(args) ?: context.getString(R.string.skill_impl_not_configured)
                }
                // v0.46: Colaboración entre múltiples agentes(Delegar subasistentes para realizar tareas.)
                "delegate_agent" -> {
                    onProgress(context.getString(R.string.skill_progress_delegating))
                    execDelegateAgent(args)
                }
                // v1.55: Agent Flujo de trabajo(Planificación estructurada de tareas)
                "task_plan" -> {
                    onProgress(context.getString(R.string.skill_progress_planning))
                    agentTools?.execTaskPlan(args, sessionId) ?: context.getString(R.string.skill_impl_not_configured)
                }
                "update_plan_step" -> agentTools?.execUpdatePlanStep(args, sessionId) ?: context.getString(R.string.skill_impl_not_configured)
                // v1.30: herramienta de chat grupal(Habla en un chat grupal de múltiples agentes/saltar/Leer contexto)
                "channel_reply" -> agentTools?.execChannelReply(args) ?: context.getString(R.string.skill_impl_not_configured)
                "channel_pass" -> agentTools?.execChannelPass(args) ?: context.getString(R.string.skill_impl_not_configured)
                "channel_read_context" -> agentTools?.execChannelReadContext(args) ?: context.getString(R.string.skill_impl_not_configured)
                // v1.0.53 Phase 5: Agent Phone(El agente principal activa un chat privado entre los miembros del chat grupal.)
                "agent_phone" -> {
                    onProgress(context.getString(R.string.skill_progress_whispering))
                    agentTools?.execAgentPhone(args) ?: context.getString(R.string.skill_impl_not_configured)
                }
                // Clase de gestión de archivos
                "list_dir" -> fileTools?.execListDir(args) ?: context.getString(R.string.skill_impl_not_configured)
                "delete_file" -> fileTools?.execDeleteFile(args) ?: context.getString(R.string.skill_impl_not_configured)
                "file_exists" -> fileTools?.execFileExists(args) ?: context.getString(R.string.skill_impl_not_configured)
                // Clases de transferencia de archivos y directorios públicos.
                "file_download" -> fileTools?.execFileDownload(args) ?: context.getString(R.string.skill_impl_not_configured)
                "read_public_file" -> fileTools?.execReadPublicFile(args) ?: context.getString(R.string.skill_impl_not_configured)
                "save_to_downloads" -> fileTools?.execSaveToDownloads(args) ?: context.getString(R.string.skill_impl_not_configured)
                "list_public_files" -> fileTools?.execListPublicFiles(args) ?: context.getString(R.string.skill_impl_not_configured)
                // Skill Gestión
                "list_skills" -> managementTools?.listSkills(args) ?: context.getString(R.string.skill_impl_not_configured)
                "uninstall_skill" -> managementTools?.uninstallSkill(args) ?: context.getString(R.string.skill_impl_not_configured)
                "disable_skill" -> managementTools?.disableSkill(args) ?: context.getString(R.string.skill_impl_not_configured)
                "enable_skill" -> managementTools?.enableSkill(args) ?: context.getString(R.string.skill_impl_not_configured)
                "update_skill" -> managementTools?.updateSkill(args) ?: context.getString(R.string.skill_impl_not_configured)
                // v1.95: Herramienta de biblioteca de emoticonos
                "list_stickers" -> mediaTools?.execListStickers(args) ?: context.getString(R.string.skill_impl_not_configured)
                "send_sticker" -> mediaTools?.execSendSticker(args) ?: context.getString(R.string.skill_impl_not_configured)
                // Nuevas herramientas
                "generate_image" -> {
                    onProgress(context.getString(R.string.skill_progress_generating_image))
                    mediaTools?.execGenerateImage(args) ?: context.getString(R.string.skill_impl_not_configured)
                }
                "translate" -> translateTools?.execTranslate(args) ?: context.getString(R.string.skill_impl_not_configured)
                "generate_qr" -> mediaTools?.execGenerateQr(args) ?: context.getString(R.string.skill_impl_not_configured)
                // JS caja de arena:Permitir que LLM ejecute código JavaScript en el sistema Skill
                // (La entrada principal es ToolRegistry.execute_javascript,Aquí está la rama de enrutamiento de SkillExecutor.,Para llamadas de habilidad)
                "execute_javascript" -> mediaTools?.execExecuteJavascript(args) ?: context.getString(R.string.skill_impl_not_configured)
                // Habilidades de palabras rápidas:No ejecuta ninguna implementación de Kotlin,Devuelve sólo el texto de instrucción codificado al modelo.(Los parámetros de llamada siempre se ignoran)
                else ->
                    if (SkillImporter.isPromptSkill(skill.implementationKotlin)) {
                        managementTools?.execPromptSkill(skill, args) ?: context.getString(R.string.skill_impl_not_configured)
                    } else if (skill.implementationKotlin.startsWith("plugin:")) {
                        mediaTools?.execPluginTool(skill, argumentsJson) ?: context.getString(R.string.skill_impl_not_configured)
                    } else {
                        context.getString(R.string.skill_unknown_impl, skill.implementationKotlin)
                    }
            }
        }.onError { msg, t ->
            Logger.e("SkillExecutor", "skill ${skill.id} La ejecución falló: $msg", t)
        }.getOrNull() ?: context.getString(R.string.skill_exec_exception)
    }

    /** v1.55: Para que ChatViewModel lea la programación activa. */
    fun getActivePlans(): Map<String, io.zer0.muse.ui.taskcard.AgentPlan> = agentTools?.getActivePlans().orEmpty()

    fun getActivePlans(sessionId: String): Map<String, io.zer0.muse.ui.taskcard.AgentPlan> = agentTools?.getActivePlans(sessionId).orEmpty()

    /** Devuelva el plan de recuperación histórico al ejecutor task_plan/update_plan_step de la sesión actual. */
    fun restoreActivePlans(plans: Map<String, io.zer0.muse.ui.taskcard.AgentPlan>, sessionId: String = "default") {
        agentTools?.restoreActivePlans(plans, sessionId)
    }

    /**
     * v2.6.4: Cierra el plan sin terminar (convierte el paso PENDIENTE / EN_PROGRESO en CANCELADO).
     * Se llama cuando el bucle de herramientas sale para garantizar que la tarjeta del plan no se detenga en el medio para siempre.
     */
    fun settleUnfinishedPlans(sessionId: String = "default"): Map<String, io.zer0.muse.ui.taskcard.AgentPlan> =
        agentTools?.settleUnfinishedPlans(sessionId).orEmpty()

    // v1.0.81: parseArgs Extraído a ToolArgsParser (comprobable único) y ya no incluido en SkillExecutor.

    // ── Implementación de habilidades integradas ──────────────────────────────────────────────────────

    /** Leer archivos en el entorno limitado de la aplicación(Calificar subrutas filesDir / cacheDir). */
    private fun validatePublicUrl(url: String): Boolean {
        val uri =
            try {
                java.net.URI(url)
            } catch (e: Exception) {
                return false
            }
        val host = uri.host?.lowercase() ?: return false
        if (host == "localhost") return false
        // Verificación secundaria después de la resolución DNS IP(protegerse contra DNS rebinding):Siempre que algún resultado del análisis apunte a la intranet, será rechazado.
        val addresses =
            try {
                java.net.InetAddress.getAllByName(host)
            } catch (e: Exception) {
                return false
            }
        return addresses.all { addr ->
            // IPv4 red privada/bucle invertido/Los métodos integrados de InetAddress anulan el enlace local, etc.
            if (addr.isLoopbackAddress || addr.isAnyLocalAddress ||
                addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
                addr.isMulticastAddress
            ) {
                return@all false
            }
            // IPv6 red privada fc00::/7(InetAddress.isSiteLocalAddress Devoluciones para IPv6 false,Requiere juicio manual)
            if (addr is java.net.Inet6Address) {
                val bytes = addr.address
                // fc00::/7 Los 7 primeros son 1111110,Es decir, el primer rango de bytes. 0xfc..0xfd
                if ((bytes[0].toInt() and 0xFE) == 0xFC) return@all false
            }
            true
        }
    }

    /** HTTP GET pedido. en el fracaso(404/tiempo de espera/La conexión falló)Bajar a fragmentos de búsqueda;401/403 Espere errores comerciales y no baje de categoría. */

    /**
     * install_skill — LLM Genere definiciones de habilidades usted mismo y guárdelas en la biblioteca(Phase 2 autoexpansión).
     *
     * LLM Genere la cadena de definición de habilidad en formato .skill.json,por [SkillImporter.parse] Almacenar después de la verificación.
     * restricciones de seguridad:implementationKotlin Debe ser una de las 4 implementaciones integradas.,No se admite la ejecución de código arbitrario.
     */
    suspend fun delegateAgent(
        request: DelegationContract.DelegationRequest,
        policy: DelegationPauseManager.PausePolicy = DelegationPauseManager.PausePolicy(),
    ): DelegationContract.DelegationResult {
        return delegateTools?.delegateAgent(request, policy)
            ?: DelegationContract.DelegationResult(
                requestId = request.requestId,
                success = false,
                error = "delegateAgent No configurado",
                metadata =
                DelegationContract.DelegationResult.ResultMetadata(
                    startedAt = System.currentTimeMillis(),
                    finishedAt = System.currentTimeMillis(),
                    durationMs = 0,
                ),
            )
    }

    /**
     * v0.46: Colaboración entre múltiples agentes — Delegue tareas a subayudantes designados para su ejecución.
     *
     * LLM Activado por tool_call,parámetros:
     *  - assistantId: sub-asistente id(Requerido,Como por ejemplo "researcher"/"writer" espera)
     *  - task: Descripción de la tarea(Requerido,lenguaje natural)
     *  - context: contexto opcional(Opcional,Información complementaria)
     *
     * Proceso de ejecución:
     *  1. Obtenga la configuración del subasistente de AssistantRepository según el asistenteId
     *  2. Construya una lista de mensajes utilizando el sistemaPrompt + descripción de tarea del subayudante
     *  3. Llame a ChatService.completeText y ejecute una ronda(temperature/maxTokens Configurar con el subasistente)
     *  4. Devuelve el texto generado por el subasistente al menú principal. LLM
     *
     * Nota:
     *  - Los subasistentes se llaman de forma independiente. LLM,Streaming loopback sin tomar el launchStream principal
     *  - Devuelve una cadena de mensaje de error en caso de error.(El maestro LLM puede ver el error y decidir si intentarlo de nuevo.)
     *  - No recursivo(El subasistente ya no se puede ajustar delegate_agent,Evite el anidamiento infinito)
     */
    @Deprecated("Utilice delegadoAgent en su lugar", ReplaceWith("delegateAgent"))
    private suspend fun execDelegateAgent(args: Map<String, String>): String {
        // v1.202 Transformación 1: Entrada de delegación unificada — execDelegateAgent si LLM tool_call "delegate_agent" entrada,
        // Pero no se dio ningún aviso antes. DelegationChainTracker,Causó que la delegación de LLM no fuera visible en las tarjetas de enlace de la interfaz de usuario.
        // Ahora delega a estructurado delegateAgent,Obtenga automáticamente seguimiento de enlaces + puntos de pausa + integración de DM + llamadas a herramientas de subagente.
        // Nota:execDelegateAgent cadena de retorno(tool result),delegateAgent Regresar DelegationResult,
        // Aquí result.resultText se devuelve como una cadena. En caso de error, devuelva result.error para permitir que el LLM principal vea el error.
        val assistantId =
            args["assistantId"]?.trim()
                ?: return context.getString(R.string.skill_missing_param_assistant_id)
        val task =
            args["task"]?.trim()
                ?: return context.getString(R.string.skill_missing_param_task)
        if (task.isBlank()) return context.getString(R.string.skill_task_blank)
        val contextInfo = args["context"]?.trim().orEmpty()
        // timeout: Predeterminado 60 segundos;response_format: Predeterminado text,Opcional json
        val timeoutSec = args["timeout"]?.toLongOrNull()?.coerceIn(1L, 600L) ?: 60L
        val responseFormat = args["response_format"]?.takeIf { it.isNotBlank() } ?: "text"

        // Construir mensaje de contexto(Trate el contexto opcional como un párrafo adicional en los mensajes de USUARIO)
        val contextMessages: List<UIMessage> =
            if (contextInfo.isNotBlank()) {
                listOf(UIMessage(role = MessageRole.USER, content = "contexto:\n$contextInfo"))
            } else {
                emptyList()
            }

        // Estructuración de construcciones DelegationRequest,confiado a delegateAgent(Seguimiento automático de enlaces/pausa/DM/llamada de herramienta)
        val requestId = "delegate-toolcall-${System.currentTimeMillis()}-${(100..999).random()}"
        val request =
            DelegationContract.DelegationRequest(
                requestId = requestId,
                task = task,
                targetType = DelegationContract.DelegationRequest.TargetType.ASSISTANT,
                targetId = assistantId,
                contextMessages = contextMessages,
                timeoutSec = timeoutSec.toInt().coerceAtLeast(1),
                responseFormat =
                when (responseFormat.lowercase()) {
                    "json" -> DelegationContract.DelegationRequest.ResponseFormat.JSON
                    "markdown", "md" -> DelegationContract.DelegationRequest.ResponseFormat.MARKDOWN
                    "code" -> DelegationContract.DelegationRequest.ResponseFormat.CODE
                    else -> DelegationContract.DelegationRequest.ResponseFormat.TEXT
                },
            )
        val result = delegateAgent(request)

        // Convertir DelegationResult en cadena(tool result)
        // Devuelve una cadena de error en caso de error(El maestro LLM puede ver el error y decidir si intentarlo de nuevo.)
        if (!result.success) {
            return result.error ?: context.getString(R.string.skill_unknown_error)
        }
        val resultText = result.resultText
        // Obtener el nombre del subasistente(de metadata,Si no está disponible, baje a assistantId)
        val assistantName = result.metadata.assistantName ?: assistantId

        // response_format=json Retorno estructurado JSON,De lo contrario, devolverá el texto prefijado.
        return if (responseFormat.equals("json", ignoreCase = true)) {
            buildJsonObject {
                put("assistantId", JsonPrimitive(assistantId))
                put("assistantName", JsonPrimitive(assistantName))
                put("result", JsonPrimitive(resultText))
                put("success", JsonPrimitive(true))
                // Viene con modo sin bloqueo taskId/threadId,Consulta de seguimiento de LLM del proveedor
                result.taskId?.let { put("taskId", JsonPrimitive(it)) }
                result.threadId?.let { put("threadId", JsonPrimitive(it)) }
            }.toString()
        } else {
            buildString {
                appendLine(context.getString(R.string.skill_delegate_done, assistantName))
                appendLine(resultText)
            }
        }
    }

    // ── v1.55: Agent Flujo de trabajo(Planificación estructurada de tareas)──────────────────────────────

    // ── Clase de gestión de archivos ──────────────────────────────────────────────────────

    /** list_dir — Listar archivos y subdirectorios en un directorio(dentro de la caja de arena). */

    // ── Skill Gestión ────────────────────────────────────────────────────

    /** list_skills — Lista instalada Skill,Filtrado opcional por categoría. */

    /** uninstall_skill — Desinstalar Skill(Eliminar por identificación,O buscar por nombre y eliminar). */
    companion object {
        /**
         * Plantillas de habilidades predefinidas(Escribir en la base de datos en el primer inicio).
         * v2.x: Empieza a sembrar y listo. SkillRepository.seedOrRefreshBuiltIn —
         * Si falta, inserte,El campo Actualizar sólo definición ya existe(Mantener el estado de inicio y detención del usuario).
         */
        val BUILT_IN_SKILLS: List<SkillEntity> =
            listOf(
                SkillEntity(
                    id = "read_file",
                    name = "leer archivo",
                    description = "Lea archivos de texto dentro del entorno limitado de la aplicación. La ruta es relativa a filesDir; Los archivos de más de 1 MB se devuelven automáticamente en segmentos, utilizando offset_chars/length_chars para continuar leyendo sin descartar el contenido posterior.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "path",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Ruta del archivo relativa a filesDir,Como por ejemplo 'notes/todo.txt'")
                                    },
                                )
                                put(
                                    "offset",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Opcional,Número de línea inicial(Empezar desde 0),Predeterminado 0")
                                    },
                                )
                                put(
                                    "length",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Opcional,Número de líneas leídas,Predeterminado 0=Todos")
                                    },
                                )
                                put(
                                    "offset_chars",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Opcional,Punto de partida de la paginación de caracteres; use el último cursor devuelto al leer la siguiente sección de un archivo muy grande")
                                    },
                                )
                                put(
                                    "length_chars",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Opcional,Longitud de la página de caracteres; hasta 65536 caracteres devueltos cada vez")
                                    },
                                )
                                put(
                                    "encoding",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Opcional,Codificación de archivos,Predeterminado utf-8(apoyo utf-16)")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("path"))))
                    }.toString(),
                    requiredJson = """["path"]""",
                    implementationKotlin = "read_file",
                    category = "file",
                ),
                SkillEntity(
                    id = "write_file",
                    name = "escribir archivo",
                    description = "Escribe texto en un archivo dentro del entorno limitado de la aplicación. Las rutas son relativas a filesDir.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "path",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Ruta del archivo relativa a filesDir")
                                    },
                                )
                                put(
                                    "content",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "El contenido del texto a escribir.")
                                    },
                                )
                                put(
                                    "append",
                                    buildJsonObject {
                                        put("type", "boolean")
                                        put("description", "Si escribir adicionalmente(Anulación falsa predeterminada)")
                                    },
                                )
                                put(
                                    "create_dirs",
                                    buildJsonObject {
                                        put("type", "boolean")
                                        put("description", "Opcional,Si se deben crear automáticamente directorios principales,Predeterminado true")
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    JsonPrimitive("path"),
                                    JsonPrimitive("content"),
                                ),
                            ),
                        )
                    }.toString(),
                    requiredJson = """["path","content"]""",
                    implementationKotlin = "write_file",
                    category = "file",
                ),
                SkillEntity(
                    id = "http_get",
                    name = "HTTP GET",
                    description = "Realiza una solicitud HTTP GET y devuelve una respuesta completa;Las respuestas largas se guardan en un archivo de aplicación que se puede leer por partes.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "url",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Solicitar URL,http:// o https://")
                                    },
                                )
                                put(
                                    "headers",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Opcional,Encabezado de solicitud JSON,Como por ejemplo {\"Authorization\":\"Bearer xxx\"}")
                                    },
                                )
                                put(
                                    "timeout",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Opcional,Segundos de tiempo de espera,Predeterminado 30")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("url"))))
                    }.toString(),
                    requiredJson = """["url"]""",
                    implementationKotlin = "http_get",
                    category = "http",
                ),
                SkillEntity(
                    id = "http_post",
                    name = "HTTP POST",
                    description = "Realiza una solicitud HTTP POST y devuelve una respuesta completa;Las respuestas largas se guardan en un archivo de aplicación que se puede leer por partes.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "url",
                                    buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "Solicitar URL",
                                        )
                                    },
                                )
                                put(
                                    "body",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Solicitar contenido del cuerpo")
                                    },
                                )
                                put(
                                    "content_type",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Content-Type,Predeterminado application/json")
                                    },
                                )
                                put(
                                    "headers",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Opcional,Encabezado de solicitud JSON")
                                    },
                                )
                                put(
                                    "timeout",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Opcional,Segundos de tiempo de espera,Predeterminado 30")
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    JsonPrimitive("url"),
                                    JsonPrimitive("body"),
                                ),
                            ),
                        )
                    }.toString(),
                    requiredJson = """["url","body"]""",
                    implementationKotlin = "http_post",
                    category = "http",
                ),
                // ── v0.24: Búsqueda y adquisición de información. ──────────────────────────────────────
                SkillEntity(
                    id = "web_search",
                    name = "búsqueda web",
                    // v1.0.75 fix (Revisión de herramientas 01): Eliminar redundancia time_period,Pautas de uso complementarias de rango de fechas
                    description = "Utilice el motor de búsqueda configurado(SearXNG/Tavily)Busca en la web. Título de retorno,URL y abstracto. Esta herramienta se llama cuando un usuario hace una pregunta que requiere la información más reciente. cuando usar: Los usuarios preguntaron sobre la necesidad de lo último/Problemas de información en tiempo real(Precio, versión, política, situación del mercado, climatología, etc.),O cuando tu conocimiento es incierto. Se requiere límite de tiempo date_range(Como dijo el usuario'la semana pasada',pasar past_week). no usar: Preguntas de sentido común o cuando el usuario ha proporcionado información suficiente;Si los resultados de la búsqueda están incompletos, cambie la palabra clave y busque nuevamente.,No lo inventes.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "query",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Requerido,Buscar palabras clave. Las preguntas de los usuarios chinos utilizan directamente palabras clave chinas,Se pueden agregar calificadores clave para mejorar la precisión.")
                                    },
                                )
                                put(
                                    "max_results",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Opcional,Número máximo de artículos devueltos(1-10),Predeterminado 5")
                                    },
                                )
                                put(
                                    "date_range",
                                    buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "Opcional,Limitar el rango de tiempo del resultado,valor: past_day / past_week / past_month / past_year. Usuario mencionado'Recientemente/Lo último/esta semana/este mes'Complete cuando,Dejar en blanco si no hay requisito de tiempo",
                                        )
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("query"))))
                    }.toString(),
                    requiredJson = """["query"]""",
                    implementationKotlin = "web_search",
                    category = "search",
                ),
                SkillEntity(
                    id = "web_fetch",
                    name = "raspado web",
                    description = "Obtener el texto de la página web de la URL especificada(Eliminar etiquetas HTML automáticamente,Devolver texto sin formato). Se utiliza para leer el contenido de texto completo de la URL devuelta por web_search. cuando usar: Lea el texto completo de la URL devuelta por web_search,O cuando el usuario proporciona una URL explícita. no usar: Cuando no hay URL;La página requiere iniciar sesión/La descripción no se puede obtener durante la interacción.,No adivines el contenido.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "url",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Páginas para rastrear URL,http:// o https://")
                                    },
                                )
                                put(
                                    "headers",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Opcional,Encabezado de solicitud JSON")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("url"))))
                    }.toString(),
                    requiredJson = """["url"]""",
                    implementationKotlin = "web_fetch",
                    category = "search",
                ),
                SkillEntity(
                    id = "knowledge_search",
                    name = "Búsqueda de base de conocimientos",
                    // v1.0.75 fix (Revisión de herramientas 01): Borrar límites con web_search,
                    // Originales"Puede ser llamado primero cuando esté relacionado con documentos importados."demasiado borroso,El modelo intenta primero la búsqueda local y luego regresa a la búsqueda, desperdiciando rondas.
                    description = "Búsqueda semántica en documentos de la base de conocimientos importados activamente por los usuarios.(Búsqueda vectorial + título/coincidencia de contenido). Úselo solo si puede haber una respuesta en la base de conocimientos del usuario.(Si el usuario ha importado datos y ha preguntado'¿Qué dice en el documento que guardé antes?'). Utilice datos generales, información en tiempo real y preguntas que se puedan encontrar en línea. web_search,No pruebes con esta herramienta primero. Los usuarios preguntan sobre las funciones propias de la aplicación muse(Cómo utilizar el pensamiento profundo/Cómo configurar mensajes activos)tiempo,pasar include_internal=true Se puede recuperar la documentación de funciones integradas.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "query",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Buscar palabras clave")
                                    },
                                )
                                put(
                                    "top_k",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Opcional,Devuelve el límite superior del número de artículos.,Predeterminado 5")
                                    },
                                )
                                put(
                                    "threshold",
                                    buildJsonObject {
                                        put("type", "number")
                                        put("description", "Opcional,umbral de similitud(0-1 sistema decimal,Puntuación no perfecta 100),Predeterminado 0.3. Filtrar aquellos que están por debajo del umbral")
                                    },
                                )
                                put(
                                    "include_internal",
                                    buildJsonObject {
                                        put("type", "boolean")
                                        put(
                                            "description",
                                            "Opcional,Si se debe incluir documentación de la función integrada de la aplicación muse,Por defecto es falso. Solo cuando el usuario le pide a la aplicación muse que funcione(Como por ejemplo'Cómo utilizar el pensamiento profundo''Cómo configurar mensajes activos')biografía del tiempo true",
                                        )
                                    },
                                )
                                put(
                                    "folder",
                                    buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "Opcional,Limitar carpetas de búsqueda(Camino dentro de la base de conocimiento,Como por ejemplo'contrato'o'contrato/2026')." +
                                                "Cada carpeta es un dominio de búsqueda independiente.,Después de ingresar, busque únicamente dentro de la carpeta y sus subcarpetas.,No se vinculará a otras carpetas." +
                                                "Se pasa cuando el usuario solo quiere comprobar una carpeta determinada.",
                                        )
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("query"))))
                    }.toString(),
                    requiredJson = """["query"]""",
                    implementationKotlin = "knowledge_search",
                    category = "knowledge",
                ),
                SkillEntity(
                    id = "arxiv_search",
                    name = "arXiv búsqueda de papel",
                    description = "Buscar artículos académicos en arXiv(Informática, Física, Matemáticas, etc.). Devuelve el título del artículo, el enlace, la fecha de publicación y el resumen. Se llama cuando los usuarios hacen preguntas relacionadas con investigaciones y artículos académicos.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "query",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Buscar palabras clave(se prefiere el ingles),Como por ejemplo 'transformer attention'")
                                    },
                                )
                                put(
                                    "max_results",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Opcional,Número máximo de artículos devueltos(1-10),Predeterminado 5")
                                    },
                                )
                                put(
                                    "category",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Opcional,Clasificación de materias,Como por ejemplo cs.AI/cs.CL")
                                    },
                                )
                                put(
                                    "date_from",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Opcional,fecha de inicio(YYYY-MM-DD)")
                                    },
                                )
                                put(
                                    "date_to",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Opcional,fecha de finalización(YYYY-MM-DD)")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("query"))))
                    }.toString(),
                    requiredJson = """["query"]""",
                    implementationKotlin = "arxiv_search",
                    category = "search",
                ),
                // ── v0.24: autoexpansión ─────────────────────────────────────────────
                SkillEntity(
                    id = "install_skill",
                    name = "Instalación Skill",
                    description = "Deje que el asistente genere la nueva definición de habilidad e instálela en el dispositivo del usuario.skill_json El parámetro es una cadena JSON en formato .skill.json. Campos obligatorios: id, name, description, category, implementationKotlin, parametersJson.category valor: file/http/search/knowledge/system/agent/sticker/custom.implementationKotlin Debe ser una de las implementaciones integradas.(read_file/write_file/http_get/http_post/web_search/web_fetch/knowledge_search/arxiv_search),No se admite la ejecución de código arbitrario. Después de la instalación, los usuarios pueden configurar→Skill Ver en/Iniciar y detener. Habilidades de palabras rápidas:implementationKotlin pasar \"prompt\",El texto del comando se coloca en el campo de solicitud.(También aceptado \"prompt:<texto>\" forma;Texto sujeto a lista negra de inyección y límite de 8KB). El parámetro de entrada opcional se puede pasar cuando se puede llamar a la palabra habilidad.:en el texto del comando {{input}} Los marcadores de posición serán sustituidos,Si no hay ningún marcador de posición, la entrada se agrega al final del comando.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "skill_json",
                                    buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "skill Definir cadena JSON(.skill.json Formato). Ejemplo: {\"id\":\"fetch_weather\",\"name\":\"comprobar el clima\",\"description\":\"Consulta el clima de la ciudad\",\"parametersJson\":\"{\\\"type\\\":\\\"object\\\",\\\"properties\\\":{\\\"url\\\":{\\\"type\\\":\\\"string\\\"}}}\",\"implementationKotlin\":\"http_get\"}",
                                        )
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("skill_json"))))
                    }.toString(),
                    requiredJson = """["skill_json"]""",
                    implementationKotlin = "install_skill",
                    category = "system",
                ),
                SkillEntity(
                    id = "author_plugin",
                    name = "Escribir un complemento",
                    description = "Deje que el asistente escriba un complemento implementado con herramientas JS.code Es el código fuente de entrada JS (la implementación de la herramienta debe ser la declaración de función de nivel superior),tools es una serie de declaraciones de herramientas (cada elemento contiene name/description/parametersJson/requiredJson/functionName), las capacidades opcionales solo pueden tomar recurso.read/ui/ui.mood. Esta herramienta solo genera [borrador sin firmar]: el borrador está deshabilitado, no es ejecutable y no se puede llamar al asistente; debe ser configurado por el usuario en \"Configuración\" → Revise la lista de herramientas y el código en \"Administración de complementos\" y haga clic en \"Firmar y activar\" antes de que la firma con la clave de autor local surta efecto.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "id",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "ID del complemento: letras minúsculas, números, guiones bajos, guiones,Comience con una letra o un número,Como por ejemplo 'daily_summary'")
                                    },
                                )
                                put(
                                    "name",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Nombre para mostrar del complemento,Como por ejemplo 'resumen diario'")
                                    },
                                )
                                put(
                                    "description",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Descripción de uso del complemento")
                                    },
                                )
                                put(
                                    "version",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "versión semántica,Como por ejemplo '1.0.0'")
                                    },
                                )
                                put(
                                    "code",
                                    buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "JS Código fuente de entrada(≤256KB). El nombre de función de cada herramienta debe tener una declaración de función de nivel superior en el código fuente.,Como por ejemplo 'function summarize(args) { return ...; }'",
                                        )
                                    },
                                )
                                put(
                                    "tools",
                                    buildJsonObject {
                                        put("type", "array")
                                        put("description", "matriz de declaración de herramientas,Consistente con el campo de herramientas del manifiesto.")
                                        put(
                                            "items",
                                            buildJsonObject {
                                                put("type", "object")
                                                put(
                                                    "properties",
                                                    buildJsonObject {
                                                        put("name", buildJsonObject { put("type", "string") })
                                                        put("description", buildJsonObject { put("type", "string") })
                                                        put("parametersJson", buildJsonObject { put("type", "string") })
                                                        put("requiredJson", buildJsonObject { put("type", "string") })
                                                        put("functionName", buildJsonObject { put("type", "string") })
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                                put(
                                    "capabilities",
                                    buildJsonObject {
                                        put("type", "array")
                                        put(
                                            "description",
                                            "Matriz de habilidades opcional,Tomado de la lista blanca:resource.read / ui / ui.mood con capacidades de puente controladas(network / storage.read / storage.write / clipboard.read / clipboard.write / notify / device.info), no declarado por defecto",
                                        )
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    JsonPrimitive("id"),
                                    JsonPrimitive("name"),
                                    JsonPrimitive("version"),
                                    JsonPrimitive("code"),
                                    JsonPrimitive("tools"),
                                ),
                            ),
                        )
                    }.toString(),
                    requiredJson = """["id","name","version","code","tools"]""",
                    implementationKotlin = "author_plugin",
                    category = "system",
                ),
                // ── v0.46: Colaboración entre múltiples agentes ────────────────────────────────────────
                SkillEntity(
                    id = "delegate_agent",
                    name = "Subasistente delegado",
                    description = "Delegar tareas a subasistentes designados para su ejecución.,Se utiliza para la colaboración con múltiples asistentes. entrante assistantId(asistente id)y task(Descripción de la tarea),Opcional context(contexto). El subasistente utilizará su propia personalidad y capacidades para completar la tarea de forma independiente y devolver los resultados.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "assistantId",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "sub-asistente id,Como por ejemplo default / researcher / writer Esperar. Se puede ver en la página de gestión del asistente.")
                                    },
                                )
                                put(
                                    "task",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Descripción de la tarea a delegar,lenguaje natural")
                                    },
                                )
                                put(
                                    "context",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Información contextual complementaria opcional")
                                    },
                                )
                                put(
                                    "timeout",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Opcional,Segundos de tiempo de espera,Predeterminado 60")
                                    },
                                )
                                put(
                                    "response_format",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Opcional,Formato de devolución,text(Predeterminado)o json")
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    JsonPrimitive("assistantId"),
                                    JsonPrimitive("task"),
                                ),
                            ),
                        )
                    }.toString(),
                    requiredJson = """["assistantId","task"]""",
                    implementationKotlin = "delegate_agent",
                    category = "agent",
                ),
                // ── v1.30: herramienta de chat grupal ─────────────────────────────────────────────
                SkillEntity(
                    id = "channel_reply",
                    name = "Discurso de chat grupal",
                    description = "Envíe un mensaje como el agente especificado en el chat grupal especificado. entrante chatId(chat grupal id),assistantId(hablar agent id)y body(Texto del mensaje). El mensaje se guardará en el historial de chat grupal.,Visible para otros miembros y usuarios.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "chatId",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "chat grupal id")
                                    },
                                )
                                put(
                                    "assistantId",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "hablando agent id(assistantId)")
                                    },
                                )
                                put(
                                    "body",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Contenido del texto del mensaje")
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    JsonPrimitive("chatId"),
                                    JsonPrimitive("assistantId"),
                                    JsonPrimitive("body"),
                                ),
                            ),
                        )
                    }.toString(),
                    requiredJson = """["chatId","assistantId","body"]""",
                    implementationKotlin = "channel_reply",
                    category = "agent",
                ),
                SkillEntity(
                    id = "channel_pass",
                    name = "El chat grupal se salta esta ronda",
                    description = "Saltar hablar en el carrusel de chat grupal(no enviar mensaje). entrante chatId(chat grupal id)y assistantId(agent id). Se llama cuando el agente cree que no necesita hablar en ese momento.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "chatId",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "chat grupal id")
                                    },
                                )
                                put(
                                    "assistantId",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "saltar para hablar agent id")
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    JsonPrimitive("chatId"),
                                    JsonPrimitive("assistantId"),
                                ),
                            ),
                        )
                    }.toString(),
                    requiredJson = """["chatId","assistantId"]""",
                    implementationKotlin = "channel_pass",
                    category = "agent",
                ),
                SkillEntity(
                    id = "channel_read_context",
                    name = "Leer el contexto del chat grupal",
                    description = "Lea los últimos mensajes del chat grupal especificado como contexto. entrante chatId(chat grupal id),Opcional limit(Límite superior del número de artículos.,Predeterminado 20). Devuelve una lista de mensajes formateados,Contiene el nombre del remitente, la hora y el contenido.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "chatId",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "chat grupal id")
                                    },
                                )
                                put(
                                    "limit",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Opcional,Número máximo de elementos leídos(1-100),Predeterminado 20")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("chatId"))))
                    }.toString(),
                    requiredJson = """["chatId"]""",
                    implementationKotlin = "channel_read_context",
                    category = "agent",
                ),
                // ── v1.0.53 Phase 5: Agent Phone(El agente principal activa un chat privado entre los miembros del chat grupal.) ─────
                SkillEntity(
                    id = "agent_phone",
                    name = "Agent chat privado",
                    description = "Iniciar un chat privado con el agente especificado en el chat grupal(whisper). entrante chatId(chat grupal id),targetAssistantId(objetivo agent id)y message(Contenido de chat privado). Los mensajes de chat privados solo son visibles para el agente objetivo,No participar en el contexto de rotación del chat grupal,Y se mantendrá en el libro mayor de sesión independiente.(App Se puede continuar después de reiniciar). Regresar inmediatamente después de llamar,Las respuestas del agente objetivo llegan de forma asincrónica al flujo de mensajes del chat grupal.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "chatId",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "chat grupal id(Se utiliza para aislar el contexto de susurro.)")
                                    },
                                )
                                put(
                                    "targetAssistantId",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "agente objetivo id(assistantId)")
                                    },
                                )
                                put(
                                    "message",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Texto de mensaje de chat privado")
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    JsonPrimitive("chatId"),
                                    JsonPrimitive("targetAssistantId"),
                                    JsonPrimitive("message"),
                                ),
                            ),
                        )
                    }.toString(),
                    requiredJson = """["chatId","targetAssistantId","message"]""",
                    implementationKotlin = "agent_phone",
                    category = "agent",
                ),
                // ── Clase de gestión de archivos ─────────────────────────────────────────────────
                SkillEntity(
                    id = "list_dir",
                    name = "directorio de lista",
                    description = "Enumere los archivos y subdirectorios en el directorio especificado dentro del entorno limitado de la aplicación. Una entrada por línea,prefijo de carpeta [D],prefijo de archivo [F],El tamaño del archivo está marcado al final. Las rutas son relativas a filesDir.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "path",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Ruta del directorio relativa a filesDir,Como por ejemplo 'notes' o ''(directorio raíz)")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("path"))))
                    }.toString(),
                    requiredJson = """["path"]""",
                    implementationKotlin = "list_dir",
                    category = "file",
                ),
                SkillEntity(
                    id = "delete_file",
                    name = "Eliminar archivos",
                    description = "Elimine archivos o directorios vacíos dentro del entorno limitado de la aplicación. Las rutas son relativas a filesDir. La eliminación de un directorio que no esté vacío fallará. Admite eliminación por lotes(pasar paths).",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "path",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Ruta de archivo o directorio vacío relativa a filesDir(soltero,Alternativa a los caminos)")
                                    },
                                )
                                put(
                                    "paths",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Opcional,Lista de rutas para la eliminación por lotes,Separados por coma o nueva línea,Como por ejemplo 'a.txt,b.txt'. Elige uno de la ruta,tener prioridad sobre path")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("path"))))
                    }.toString(),
                    requiredJson = """["path"]""",
                    implementationKotlin = "delete_file",
                    category = "file",
                ),
                SkillEntity(
                    id = "file_exists",
                    name = "Determinar que el archivo existe",
                    description = "Determine si existe el archivo o directorio en la ruta especificada en el entorno limitado de la aplicación. La devolución existe o no_existe. Las rutas son relativas a filesDir.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "path",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Ruta relativa a filesDir")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("path"))))
                    }.toString(),
                    requiredJson = """["path"]""",
                    implementationKotlin = "file_exists",
                    category = "file",
                ),
                // ── Directorio público y transferencia de archivos ─────────────────────────────────────────
                SkillEntity(
                    id = "file_download",
                    name = "Descargar archivo",
                    description = "Descargue un archivo desde una URL al entorno limitado de la aplicación. Admite un período de tiempo de espera especificado(Predeterminado 60 segundos). Los archivos descargados se guardan en la ruta relativa especificada en filesDir,Cree automáticamente directorios principales.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "url",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Descargar dirección,http:// o https://")
                                    },
                                )
                                put(
                                    "path",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Ruta relativa para guardar en sandbox(relativo a filesDir),Como por ejemplo 'downloads/file.zip'")
                                    },
                                )
                                put(
                                    "timeout",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Opcional,Segundos de tiempo de espera,Predeterminado 60")
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    JsonPrimitive("url"),
                                    JsonPrimitive("path"),
                                ),
                            ),
                        )
                    }.toString(),
                    requiredJson = """["url","path"]""",
                    implementationKotlin = "file_download",
                    category = "file",
                ),
                SkillEntity(
                    id = "read_public_file",
                    name = "Leer archivos públicos",
                    description = "Leer archivos públicos a través de content:// URI. List_public_files legibles devueltos por URI(Contiene MediaStore URI),También puedes leer el uso compartido de usuarios./Abra el URI pasado. Devuelva contenido de texto(límite superior 1MB). Nota:Sólo admite archivos de texto,Los archivos binarios pueden estar confusos.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "uri",
                                    buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "Archivo URI,Como resultado de list_public_files uri=content://media/... o SAF entrante content://...",
                                        )
                                    },
                                )
                                put(
                                    "encoding",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Opcional,Codificación de archivos,Predeterminado utf-8")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("uri"))))
                    }.toString(),
                    requiredJson = """["uri"]""",
                    implementationKotlin = "read_public_file",
                    category = "file",
                ),
                SkillEntity(
                    id = "save_to_downloads",
                    name = "Guardar en el directorio de descargas",
                    description = "Guarde el contenido de texto o los archivos locales de la zona de pruebas en el directorio de descargas del sistema.Android 10+ Escribiendo a través de MediaStore,Android 9 Y lo siguiente escribe directamente el directorio de descarga público. Admite volcado de archivos binarios al pasar file_path(No es necesario leer_archivo antes de escribir texto).",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "content",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Contenido de texto que se guardará(Alternativa a file_path)")
                                    },
                                )
                                put(
                                    "file_path",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Opcional,Ruta del archivo fuente dentro del entorno sandbox(relativamente filesDir),Transferir directamente a Download,Se admite binario. Elige uno del contenido")
                                    },
                                )
                                put(
                                    "filename",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "nombre de archivo,Como por ejemplo 'notes.txt'")
                                    },
                                )
                                put(
                                    "mime_type",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Opcional,MIME Tipo,Texto predeterminado/sin formato. Se recomienda especificar explícitamente al volcar archivos binarios.(Como por ejemplo image/png)")
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    JsonPrimitive("filename"),
                                ),
                            ),
                        )
                    }.toString(),
                    requiredJson = """["filename"]""",
                    implementationKotlin = "save_to_downloads",
                    category = "file",
                ),
                SkillEntity(
                    id = "list_public_files",
                    name = "Listar directorios públicos",
                    description = "Listar directorios públicos especificados(Downloads/Documents/Pictures/Music espera)archivo. Consulta a través de MediaStore,Devuelve el nombre y el tamaño del archivo. Columna predeterminada Downloads,Máximo 50,Ordenar por hora de modificación en orden inverso.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "directory",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Opcional,nombre del directorio,Como por ejemplo Downloads/Documents/Pictures/Music,Predeterminado Downloads")
                                    },
                                )
                                put(
                                    "limit",
                                    buildJsonObject {
                                        // v1.52: El tipo de corrección es integer(La cadena original no coincide con parseIntOrNull real)
                                        put("type", "integer")
                                        put("description", "Opcional,Número máximo de artículos devueltos,Predeterminado 50")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf()))
                    }.toString(),
                    requiredJson = """[]""",
                    implementationKotlin = "list_public_files",
                    category = "file",
                ),
                // ── Skill Gestión ───────────────────────────────────────────────
                SkillEntity(
                    id = "list_skills",
                    name = "lista Skill",
                    description = "Listar todos los instalados Skill,El formato de cada línea es 'id | name | category | enabled/disabled'. Filtrado opcional por categoría.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "category",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Opcional,Filtrar por categoría,Como por ejemplo file/http/search/knowledge/system/agent/skill")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf()))
                    }.toString(),
                    requiredJson = """[]""",
                    implementationKotlin = "list_skills",
                    category = "skill",
                ),
                SkillEntity(
                    id = "uninstall_skill",
                    name = "Desinstalar Skill",
                    description = "Desinstalar(Eliminar)Habilidad instalada. Necesita pasar id o name(al menos uno),Utilice la identificación primero. Después de la eliminación, la habilidad ya no está disponible.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "id",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Para ser desinstalado skill id")
                                    },
                                )
                                put(
                                    "name",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Opcional,Buscar por nombre y desinstalar(id Usado cuando no se transmite)")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf()))
                    }.toString(),
                    requiredJson = """[]""",
                    implementationKotlin = "uninstall_skill",
                    category = "skill",
                ),
                SkillEntity(
                    id = "disable_skill",
                    name = "Desactivar Skill",
                    description = "desactivar instalado Skill(no borrar,Simplemente configúrelo como no disponible). Se puede restaurar más tarde habilitándolo. Es necesario pasar la identificación.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "id",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "estar discapacitado skill id")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("id"))))
                    }.toString(),
                    requiredJson = """["id"]""",
                    implementationKotlin = "disable_skill",
                    category = "skill",
                ),
                SkillEntity(
                    id = "enable_skill",
                    name = "habilitar Skill",
                    description = "Habilitar instalado Skill(Simétrico con enable_skill). Es necesario pasar la identificación. Las habilidades integradas permiten habilitar;Las habilidades del complemento comienzan y terminan con el complemento,Debe ir a la página de administración de complementos para operar,Esta herramienta lo rechazará.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "id",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "estar habilitado skill id")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("id"))))
                    }.toString(),
                    requiredJson = """["id"]""",
                    implementationKotlin = "enable_skill",
                    category = "skill",
                ),
                SkillEntity(
                    id = "update_skill",
                    name = "actualizar Skill",
                    description = "Actualizar habilidades creadas por el usuario name / description / parametersJson(JSON Schema cuerda). La habilidad de palabra de aviso además admite la sustitución del texto del comando con el parámetro de aviso. Solo se pueden modificar las habilidades creadas por los usuarios: se rechazarán las habilidades reservadas integradas y las habilidades complementarias.;El contenido actualizado aún se somete a verificación de importación.(Mantener lista blanca de ID/categoría/Inyectar lista negra),Después de la actualización, la identificación permanece sin cambios y permanece habilitada.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "id",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Usuario para actualizar skill id")
                                    },
                                )
                                put(
                                    "name",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Opcional,nuevo nombre para mostrar")
                                    },
                                )
                                put(
                                    "description",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Opcional,Nueva descripción de la herramienta(El modelo decide si llamar)")
                                    },
                                )
                                put(
                                    "parametersJson",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Opcional,Nueva cadena de esquema JSON de parámetro(Las habilidades con palabras rápidas ignoran este parámetro)")
                                    },
                                )
                                put(
                                    "prompt",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Opcional,Solo habilidades con palabras rápidas:nuevo texto de comando(límite superior 8KB)")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("id"))))
                    }.toString(),
                    requiredJson = """["id"]""",
                    implementationKotlin = "update_skill",
                    category = "skill",
                ),
                // v1.55: Agent Flujo de trabajo — Planificación estructurada de tareas
                SkillEntity(
                    id = "task_plan",
                    name = "Crear plan de tareas",
                    description = "Cuando se enfrenta a tareas complejas de varios pasos,Comience creando un plan estructurado y luego ejecútelo paso a paso. Pasar el título del plan y la lista de pasos(Cada paso incluye un título y una descripción.). El plan se muestra en la interfaz de usuario como una lista de verificación rastreable. Después de la creación, realice los pasos en orden.,Llame a update_plan_step para actualizar el estado después de completar cada paso.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "title",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Título del programa,Describe brevemente el objetivo general.")
                                    },
                                )
                                put(
                                    "steps",
                                    buildJsonObject {
                                        put("type", "array")
                                        put("description", "Lista de pasos,Dispuestos por orden de ejecución.")
                                        put(
                                            "items",
                                            buildJsonObject {
                                                put("type", "object")
                                                put(
                                                    "properties",
                                                    buildJsonObject {
                                                        put(
                                                            "title",
                                                            buildJsonObject {
                                                                put("type", "string")
                                                                put("description", "título del paso(corto)")
                                                            },
                                                        )
                                                        put(
                                                            "description",
                                                            buildJsonObject {
                                                                put("type", "string")
                                                                put("description", "Descripción detallada de los pasos.,Incluir qué hacer y resultados esperados.")
                                                            },
                                                        )
                                                    },
                                                )
                                                put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("title"))))
                                            },
                                        )
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("title"), JsonPrimitive("steps"))))
                    }.toString(),
                    requiredJson = """["title","steps"]""",
                    implementationKotlin = "task_plan",
                    category = "agent",
                ),
                SkillEntity(
                    id = "update_plan_step",
                    name = "Actualizar los pasos de planificación",
                    description = "Actualizar el estado de un paso en un plan de tareas. Pasar planId y stepIndex(de0empezar),y nuevo estado(done/failed/in_progress/skipped)y resumen de resultados opcional.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "planId",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "planear id(task_plan regresó planId)")
                                    },
                                )
                                put(
                                    "stepIndex",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "índice de pasos(Empezar desde 0)")
                                    },
                                )
                                put(
                                    "status",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "nuevo estado:done(Completo)/ failed(falló)/ in_progress(ejecutando)/ skipped(saltar)")
                                    },
                                )
                                put(
                                    "result",
                                    buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "Opcional,Resumen de los resultados de la ejecución del paso.",
                                        )
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                listOf(JsonPrimitive("planId"), JsonPrimitive("stepIndex"), JsonPrimitive("status")),
                            ),
                        )
                    }.toString(),
                    requiredJson = """["planId","stepIndex","status"]""",
                    implementationKotlin = "update_plan_step",
                    category = "agent",
                ),
                // ── v1.95: Herramienta de biblioteca de emoticonos ─────────────────────────────────────────
                SkillEntity(
                    id = "list_stickers",
                    name = "lista de memes",
                    description = "Lista los emoticonos disponibles en la biblioteca de emoticones(Filtrar por categoría). Solo disponible si el usuario ha subido un meme. Determinar el estado de ánimo de la conversación antes de enviarla.:usuario enojado/Cuando estés triste, prioriza las categorías de consuelo, ternura y curación.;feliz/Cuando estés emocionado, prioriza las categorías de celebración y divertidas.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "category",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Opcional,Filtrar emoticonos por categoría(como los gatos/Gouzi). Si no se aprueba, enumere todos;Se recomienda combinar las emociones de la conversación en clasificaciones apropiadas.")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf()))
                    }.toString(),
                    requiredJson = """[]""",
                    implementationKotlin = "list_stickers",
                    category = "sticker",
                ),
                SkillEntity(
                    id = "send_sticker",
                    name = "Enviar emoticones",
                    description = "Enviar un emoticón al usuario. Primero use list_stickers para ver los emoticonos disponibles,Entonces utiliza esta herramienta para enviar. Principios de selección de imágenes.:Haga coincidir el estado de ánimo y el contexto de la conversación,enojado/Mensajes calmantes y curativos cuando estás triste.,Celebración y publicaciones divertidas cuando estás feliz.,Evite publicar memes tontos sobre temas serios.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "id",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Emoticones para enviar id(Obtener de list_stickers)")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("id"))))
                    }.toString(),
                    requiredJson = """["id"]""",
                    implementationKotlin = "send_sticker",
                    category = "sticker",
                ),
                // ── v1.???: Nuevas herramientas ──────────────────────────────────────────────
                SkillEntity(
                    id = "generate_image",
                    name = "Generar imágenes",
                    description = "Genera imágenes basadas en descripciones de texto. Llame al modelo de dibujo AI(Requiere configuración de proveedor de dibujos compatible con OpenAI). Devuelve la URL de la imagen. cuando usar: Solicitudes de usuario para generar imágenes./cartel/Tiempo de cobertura. no usar: Cuando el modelo de dibujo no está configurado(fallará,Se debe informar a los usuarios para que establezcan la configuración.);prompt Intenta describir el tema en inglés./estilo/Composición.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "prompt",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Descripción de la imagen,el ingles es mejor,Como por ejemplo 'a cute cat sitting on a sofa'")
                                    },
                                )
                                put(
                                    "size",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Opcional,Tamaño de imagen,Como por ejemplo 1024x1024/1792x1024/1024x1792,Predeterminado 1024x1024")
                                    },
                                )
                                put(
                                    "reference_image",
                                    buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "Opcional,URL de la imagen de referencia o base64(Se utiliza para hacer dibujos./edición de imágenes);Se llama al punto final del gráfico cuando no está vacío. Nota:LLM No se puede acceder al álbum de fotos local del usuario,El usuario inyecta la imagen de referencia local después de seleccionarla del álbum en la tarjeta de aprobación de la herramienta.,LLM Este parámetro no es obligatorio y no se puede completar al llamar",
                                        )
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("prompt"))))
                    }.toString(),
                    requiredJson = """["prompt"]""",
                    implementationKotlin = "generate_image",
                    category = "image",
                ),
                SkillEntity(
                    id = "translate",
                    name = "Traducción",
                    description = "Traduce el texto al idioma especificado. Se puede especificar el idioma de origen(Opcional)y el idioma de destino(Requerido). Utilice modelos de IA para una traducción de alta calidad.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "text",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Contenido del texto a traducir")
                                    },
                                )
                                put(
                                    "target_language",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "idioma de destino,Como los chinos/English/日本語/한국어")
                                    },
                                )
                                put(
                                    "source_language",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Opcional,idioma de origen,Como los chinos/English/Lectura automática")
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    JsonPrimitive("text"),
                                    JsonPrimitive("target_language"),
                                ),
                            ),
                        )
                    }.toString(),
                    requiredJson = """["text","target_language"]""",
                    implementationKotlin = "translate",
                    category = "custom",
                ),
                // B-07: generate_qr La habilidad incorporada está fuera de línea. — Duplicado y semánticamente conflictivo con la herramienta local generate_qr_code
                // (skill La versión devuelve la ruta del archivo cacheDir,La versión local muestra el URI de datos en la conversación). Mantenga la versión local.
                // implementationKotlin="generate_qr" El despacho queda,Compatible con usuarios que hayan importado esta habilidad.
            )

        /**
         * B-32: Colección de implementación enrutable de habilidades incorporada — con [execute] dentro when(implementationKotlin) de
         * Correspondencia exclusiva con clave de sucursal uno a uno(plugin: Excepto prefijos y ramas desconocidas).
         *
         * Para pruebas de conformidad de barandillas(ver app/src/test/.../tools/ToolRegistrySmokeTest.kt)atravesar
         * [BUILT_IN_SKILLS] Afirmar que cada habilidad incorporada puede ser [execute] Ruta a la implementación correspondiente,
         * prevenir"Se agregó una habilidad incorporada pero se perdió la rama al enrutar"o"rama eliminada"Hace que Skill_unknown_impl se camine en silencio.
         *
         * Nota:generate_qr Se ha implementado fuera de línea.(B-07),excluir de este conjunto;Pero la rama de envío permanece por compatibilidad con datos antiguos.
         * Esta colección debe ser [execute] La rama cuando permanece sincronizada,La colección es sólo para probar barandillas.,No impulsa el enrutamiento.
         */
        internal val ROUTABLE_SKILL_IMPL: Set<String> =
            setOf(
                // Archivo
                "read_file", "write_file", "list_dir", "delete_file", "file_exists",
                // HTTP/Buscar/información
                "http_get", "http_post", "web_search", "web_fetch", "knowledge_search", "arxiv_search",
                // autoexpansión/Agent/chat grupal
                "install_skill", "author_plugin", "delegate_agent", "task_plan", "update_plan_step",
                "channel_reply", "channel_pass", "channel_read_context", "agent_phone",
                // directorio público de archivos
                "file_download", "read_public_file", "save_to_downloads", "list_public_files",
                // Skill gestión
                "list_skills", "uninstall_skill", "disable_skill", "enable_skill", "update_skill",
                // Emoticones/medios de comunicación/Traducción/JS
                "list_stickers", "send_sticker", "generate_image", "translate", "execute_javascript",
            )
    }
}
