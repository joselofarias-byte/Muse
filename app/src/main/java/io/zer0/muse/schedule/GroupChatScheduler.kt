package io.zer0.muse.schedule

import android.content.Context
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.ProcessWriteGate
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.AgentTeam
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantMemoryAccessPolicy
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.groupchat.GroupChatEntity
import io.zer0.muse.data.groupchat.GroupChatGenerationLedgerEntity
import io.zer0.muse.data.groupchat.GroupChatMemoryRepository
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.data.groupchat.GroupChatRepository
import io.zer0.muse.data.subagent.SubagentThreadStore
import io.zer0.muse.rag.RagConfig
import io.zer0.muse.rag.RagService
import io.zer0.muse.tools.DelegationChainTracker
import io.zer0.muse.tools.DelegationContract
import io.zer0.muse.tools.MediaGenHost
import io.zer0.muse.tools.MediaGenHostContext
import io.zer0.muse.tools.MediaGenKind
import io.zer0.muse.tools.MediaGenResult
import io.zer0.muse.tools.MediaGenSlot
import io.zer0.muse.tools.SkillExecutor
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.channel.ChannelToolFactory
import io.zer0.muse.tools.channel.GroupChatToolPolicy
import io.zer0.muse.transformer.SystemPromptAssembler
import io.zer0.muse.ui.groupchat.AgentActivityStatus
import io.zer0.muse.ui.groupchat.FileAttachment
import io.zer0.muse.ui.groupchat.GroupChatActivityHub
import io.zer0.muse.util.MusePatterns
import io.zer0.muse.vision.VisionBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Programador de chat grupal — Después de que el usuario envía un mensaje, se activa en serie al Agente en el chat grupal para que hable por turno.
 *
 * Diseñado según el modelo de chat grupal multi-Agente:
 *  1. El usuario envía un mensaje en el chat grupal.
 *  2. El planificador elimina el chat grupal. memberIds,Ejecutar en serie para cada asistente.:
 *     a. contexto de construcción(N mensajes recientes + indicaciones de identidad del chat grupal)
 *     b. sintonizar [ChatService.streamChat] llamada en streaming(60s tiempo de espera,acumular ContentDelta)
 *     c. LLM El texto devuelto es el discurso del agente.
 *     d. sintonizar [GroupChatRepository.sendMessage] Guardar respuesta del agente
 *     e. Si LLM regresa "[PASS]" o texto vacío,omite esto agent
 *  3. Devuelve la lista de respuestas de todos los agentes en esta ronda.
 *
 * v1.134 P1-4: Cambiar a llamada de streaming streamChat,Elimine el bloqueo completo de paquetes de los años 60 de completeText.
 *
 * Manejo de errores:
 *  - Devuelve una lista vacía cuando el chat grupal no existe
 *  - Agent Saltar si no está presente(registrando)
 *  - LLM Registre cuando la llamada falle y omita el agent
 *  - tiempo de espera(60s)omite esto agent
 *
 * @param groupChatRepository Almacén de chat grupal(Leer chat grupal/Mensaje, guardar respuesta del agente)
 * @param assistantRepository Obtener configuración del asistente(systemPrompt / temperature / maxTokens)
 * @param chatService llamada en streaming LLM(v1.134 P1-4 Cambiar a streamChat)
 * @param settings Leer el seleccionado actualmente Model(activeProvider)
 * @param appScope v1.111: Corrutina a nivel de aplicación,El carrusel de chat grupal se ejecuta aquí(Cortar páginas/Sin interrupciones en segundo plano)
 * @param appContext v1.111: empezar/Detener el servicio de recepción
 * @param chatGenerationManager v1.111: Reutilizar el mecanismo de mantener vivo del chat único(Notificación de servicio de recepción/latido del corazón/Estado)
 * @param skillExecutor Modificación 1: Cuando el chat grupal está asociado a un equipo y el equipo tiene un flujo de trabajo,confiar TeamWorkflowExecutor
 *        ejecución en paralelo/Condiciones/Orquestación de agregación(por SkillExecutor.delegateAgent → TeamWorkflowExecutor).
 * @param activityHub Administrador de estado de actividad de chat grupal.
 *        Actualizar el estado del agente en cada etapa de rotación.(VIEWING/REPLYING/NO_REPLY/ERROR/IDLE),
 *        UI Muestre chips activos a través de una suscripción responsiva de StateFlow.
 * @param delegationChainTracker v1.202: Rastreador de enlaces delegados,en [invokeAgent] llamar
 *        onDelegationStarted/onDelegationFinished Sincronizar el estado del enlace,Haga que el proceso de ejecución del chat grupal sea visible en la interfaz de usuario de la conversación principal.
 *        Si es nulo, no registre el enlace.(Entorno de prueba o degradación cuando no se inyecta).
 */
class GroupChatScheduler(
    private val groupChatRepository: GroupChatRepository,
    private val assistantRepository: AssistantRepository,
    private val chatService: ChatService,
    private val settings: SettingsRepository,
    private val appScope: CoroutineScope,
    private val appContext: Context,
    private val chatGenerationManager: ChatGenerationManager,
    private val visionBridge: VisionBridge,
    private val ragService: RagService,
    private val skillExecutor: SkillExecutor,
    private val activityHub: GroupChatActivityHub,
    private val delegationChainTracker: DelegationChainTracker? = null,
    /**
     * v2.x: Almacén de aislamiento de memoria de chat grupal,puede ser null(Entorno de prueba o degradación cuando no se inyecta).
     *
     * tiempo no vacío,Después de que cada agente completa la respuesta, escribe el resumen de esta ronda de chat grupal en una hoja separada. fact store
     * ([GroupChatMemoryRepository.saveSummary]),**No**Asistente de escritura del sistema de memoria principal.,
     * Evite que los mensajes de chat grupal contaminen el contexto principal de la conversación.
     * SystemPromptAssembler Se utiliza al inyectar `<group_chat_memory>` Las etiquetas se distinguen de la memoria principal.
     */
    private val groupChatMemoryRepository: GroupChatMemoryRepository? = null,
    /**
     * v1.0.53: SystemPromptAssembler Ejemplo,Se utiliza para inyectar en el mensaje del sistema de chat grupal.
     * memoria a largo plazo(`<long_term_memory>`)Chatear recuerdos con grupo.(`<group_chat_memory>`).
     *
     * Reparación"La memoria del chat grupal y otras memorias locales no son interoperables"pregunta:
     *  - antes:El agente del chat grupal escribe después de responder GroupChatMemoryRepository,Pero no se leyó la siguiente vez que se creó el mensaje.
     *  - antes:El agente del chat grupal no tiene conocimiento de la memoria a largo plazo del usuario.
     *  - ahora:Inyecta estos dos recuerdos,Permitir que el agente del chat grupal vea ambos retratos de usuarios,También puedes recordar tus comentarios pasados en los chats grupales.
     *
     * cuando es nulo(entorno de prueba)Bajar a sin inyección,Consistente con el comportamiento original.
     */
    private val systemPromptAssembler: io.zer0.muse.transformer.SystemPromptAssembler? = null,
    /**
     * v1.0.53 Phase 5: Libro mayor de hilos de subagente(Versión persistente).
     *
     * usado para Agent Phone(whisper chat privado)Reutilización Fase 1 Unificación ThreadStore:
     *  - launchWhisper llamar [SubagentThreadStore.getOrCreate] Registrar hilo
     *  - Mensaje privado pasado [SubagentThreadStore.appendMessages] escribir
     *    `filesDir/subagent_sessions/<threadId>.jsonl`,App Recuperable después de reiniciar
     *
     * cuando es nulo(entorno de prueba)Bajar de categoría a no persistente,El comportamiento es coherente con la versión de memoria original.
     */
    private val subagentThreadStore: SubagentThreadStore? = null,
    /** B8-02: Registro global de herramientas,Los miembros del chat grupal pueden acceder a herramientas generales(Cuando es nulo, solo el trío de canales). */
    private val toolRegistry: ToolRegistry? = null,
) {

    /** v2.6.0: El segmento MOOD del chat grupal comparte lo mismo `mood_format` Plantilla (misma fuente verdadera + mismo idioma alternativo). */
    private val moodTemplateLoader: io.zer0.muse.transformer.PromptTemplateLoader by lazy {
        io.zer0.muse.transformer.PromptTemplateLoader(appContext)
    }

    /**
     * Segmento MOOD utilizado para representar el chat grupal.
     *
     * Reutilizar directamente el chat único `mood_format` Plantilla (a través de [PromptTemplateLoader] respaldo por configuración regional de la aplicación),
     * Ya no mantener una copia independiente para evitar la deriva de dos definiciones (la versión anterior de la copia del chat grupal definía a Vibe como"Estado de ánimo del usuario", a diferencia del chat único).
     * Recurrir a cuando falta la plantilla [GROUP_CHAT_MOOD_FALLBACK].
     */
    private fun groupChatMoodSection(): String {
        val lang = settings.getLanguageSync()
        val locale = when (lang) {
            "system", "" -> java.util.Locale.getDefault().language
            else -> lang
        }
        return moodTemplateLoader.render("mood_format", locale = locale, fallback = GROUP_CHAT_MOOD_FALLBACK)
    }

    /**
     * v1.111: Estado de generación activa del chat grupal(Para que ViewModel se suscriba,Restaurar después del corte de página UI).
     *
     * con [ChatGenerationManager.ActiveGeneration] la diferencia:
     *  - ActiveGeneration Es una semántica de chat único.(assistantId = Mensaje de marcador de posición id),Para chat grupal "group" Marcador de posición
     *  - Exposición adicional en esta categoría currentSpeakerId/Name,Para visualización de interfaz de usuario"quien esta pensando"
     */
    data class ActiveGroupGeneration(
        val chatId: String,
        val chatName: String,
        val isResponding: Boolean = true,
        val currentSpeakerId: String? = null,
        val currentSpeakerName: String? = null,
        /** v2.x: Los nombres de los miembros restantes para hablar.(Visualización de colas,UI Visualización de la barra del director"Próximo: A → B"). */
        val upcomingSpeakerNames: List<String> = emptyList(),
        val lastUpdatedAt: Long = System.currentTimeMillis(),
    )

    private val _activeGroupGeneration = MutableStateFlow<ActiveGroupGeneration?>(null)
    val activeGroupGeneration: StateFlow<ActiveGroupGeneration?> = _activeGroupGeneration.asStateFlow()

    // Revisión de corrección (2.0 A-14): Cada función del chat grupal se genera de forma independiente. key — rotación/susurrar/votar/Resumen/renacer
    // previamente compartido "group:$chatId",launchGeneration Para el mismo ID de sesión, cancele primero el anterior job:
    // Enviar un mensaje privado durante la rotación/El renacimiento cancelará toda la rotación.,Y el libro de cuentas no se eliminará cuando se rote y cancele la sucursal.,
    // No más ejecutar en el mismo proceso.(La restauración solo se realiza cuando se inicia la aplicación.),Toda la discusión fue interrumpida en silencio.
    // (Las constantes se definen en el objeto complementario existente a continuación. — Kotlin Sólo se permite uno por categoría. companion)

    private fun groupGenKey(chatId: String, kind: String) = "group:$chatId:$kind"

    /** Revisión de corrección (2.0 A-14): Detener toda generación de chats grupales(Cada función es independiente. key,Cancelar uno por uno). */
    private fun stopAllGroupGenerations(chatId: String) {
        listOf(GC_KEY_RR, GC_KEY_WHISPER, GC_KEY_VOTE, GC_KEY_SUMMARY, GC_KEY_REGENERATE).forEach { kind ->
            chatGenerationManager.stop(groupGenKey(chatId, kind))
        }
    }

    /** v1.111: Si hay una generación activa del chat grupal especificado(Se utiliza para evitar el reingreso.).v1.113: En su lugar, verifique exactamente por chatId.
     *  Revisión de corrección (2.0 A-14): Mira todas las características key(Cualquier función en curso se considera activa.). */
    fun hasActiveGeneration(chatId: String): Boolean = listOf(GC_KEY_RR, GC_KEY_WHISPER, GC_KEY_VOTE, GC_KEY_SUMMARY, GC_KEY_REGENERATE)
        .any { chatGenerationManager.isStreaming(groupGenKey(chatId, it)) }
    // ── B5-02: El chat grupal genera un libro mayor ─────────────────────────────────────────────

    private suspend fun saveLedger(
        ledger: GroupChatGenerationLedgerEntity?,
        chatId: String,
        mode: String,
        round: Int,
        memberIndex: Int,
        memberIdsJson: String? = null,
        status: String = "running",
    ): GroupChatGenerationLedgerEntity? {
        if (ledger == null) return null
        if (restoreBlocksWrites(chatId, "El chat grupal genera un libro mayor")) return ledger
        val updated = ledger.copy(
            chatId = chatId,
            mode = mode,
            round = round,
            memberIndex = memberIndex,
            memberIdsJson = memberIdsJson ?: ledger.memberIdsJson,
            status = status,
            updatedAt = System.currentTimeMillis(),
        )
        resultOf { groupChatRepository.upsertGenerationLedger(updated) }
            .onError { msg, t -> Logger.w(TAG, "No se pudo escribir en el libro mayor del chat grupal: $msg", t) }
        return updated
    }

    /**
     * La ventana de recuperación puede comenzar durante la invocación de un modelo y no puede confiar únicamente en las comprobaciones tempranas de las entradas generadas.
     * Todos los puntos de registro internos del chat grupal se llaman aquí antes de la escritura real y los resultados en la memoria se conservan durante la recuperación.
     * Pero saltarse contamina la base de datos que se reemplaza./Escritura en el almacenamiento de archivos.
     */
    private fun restoreBlocksWrites(chatId: String, operation: String): Boolean {
        if (!ProcessWriteGate.restoring) return false
        Logger.w(TAG, "Copia de seguridad y restauración en curso, omita el chat grupal $chatId de${operation}escribir")
        return true
    }

    private fun parseLedgerMemberIds(ledger: GroupChatGenerationLedgerEntity?): List<String>? {
        return parseLedgerMemberIds(ledger?.memberIdsJson)
    }

    private suspend fun memberAlreadyRepliedSince(chatId: String, memberId: String, since: Long): Boolean {
        // v1.0.74 fix: saveLedger actualizó el ledger.updatedAt pasado por la persona que llama a la hora actual.,
        // Línea de base no válida al reproducir el punto de interrupción(Miembro habló por última vez T0 < momento de repetición → Nunca encontrado → repetir el discurso).
        // Cambiar para leer el libro mayor persistente original de DB updatedAt(La última vez que se guardó el proceso.)como punto de referencia.
        val persisted = groupChatRepository.getPendingGenerationLedgers()
            .firstOrNull { it.chatId == chatId }
        val base = persisted?.updatedAt ?: since
        return groupChatRepository.getRecentMessages(chatId, DEFAULT_CONTEXT_SIZE).any {
            it.senderType == "assistant" && it.senderId == memberId && it.timestamp >= base
        }
    }

    companion object {
        private const val TAG = "GroupChatScheduler"

        // Revisión de corrección (2.0 A-14): Cada función del chat grupal genera un sufijo clave de forma independiente.
        const val GC_KEY_RR = "rr"
        const val GC_KEY_WHISPER = "whisper"
        const val GC_KEY_VOTE = "vote"
        const val GC_KEY_SUMMARY = "summary"
        const val GC_KEY_REGENERATE = "regenerate"

        /** B-16: Conjunto de tres piezas del canal de chat grupal.(Resultado en mensaje de USUARIO/Relleno del método de salida,No entrar assistant tool_calls). */
        val CHANNEL_TOOL_NAMES = setOf("channel_reply", "channel_pass", "channel_read_context")

        /** R-TEST-15: Analizar el orden de los miembros de rotación del libro mayor JSON,Para pruebas unitarias llamar directamente. */
        internal fun parseLedgerMemberIds(memberIdsJson: String?): List<String>? {
            if (memberIdsJson.isNullOrBlank() || memberIdsJson == "[]") return null
            return runCatching {
                AppJson.decodeFromString(ListSerializer(String.serializer()), memberIdsJson)
            }.getOrNull()
        }

        /** R-TEST-15: La asignación de roles de debate es puramente lógica. */
        internal fun generateDebateRoles(count: Int): List<String> {
            val baseRoles = when {
                count <= 2 -> listOf("proponer un plan", "desafío de preguntas")
                count == 3 -> listOf("proponer un plan", "desafío de preguntas", "Mejorar la optimización")
                else -> listOf("proponer un plan", "desafío de preguntas", "Mejorar la optimización", "Prórroga suplementaria")
            }
            return (0 until count).map { baseRoles[it % baseRoles.size] }
        }

        /** Tiempo de espera de llamada de LLM para un solo agente(milisegundos). */
        private const val AGENT_TIMEOUT_MS = 60_000L

        /** Tiempo de espera de decisión de LLM de ronda única(Evite que una transmisión lenta consuma el presupuesto total de 60 segundos). */
        /**
         * Tiempo de espera básico para la toma de decisiones en una sola ronda de chat grupal.
         *
         * Este tiempo de espera cubre toda la sección de "colas de modelos + generación de pensamiento + salida". Un presupuesto ajustado volverá a la normalidad
         * Se juzgó erróneamente como un tiempo de espera y finalmente evolucionó a "todos los asistentes no han hablado en esta ronda" y no hubo comentarios por parte del usuario.
         */
        private const val AGENT_ROUND_TIMEOUT_BASE_MS = 60_000L

/** Tiempo de espera de ronda única cuando el modelo tiene capacidades de inferencia:reasoning El contenido también ocupa este tiempo. */
        private const val AGENT_ROUND_TIMEOUT_REASONING_MS = 150_000L

        /** De forma predeterminada, el chat grupal no inyecta herramientas habituales, solo retiene channel_* Conjunto de tres piezas para evitar tormentas de llamadas de herramientas vacías en modelos pequeños. */
        private const val ENABLE_GROUP_CHAT_REGULAR_TOOLS = false

        /** El número predeterminado de mensajes de contexto. */
        private const val DEFAULT_CONTEXT_SIZE = 20

        /** LLM Devolver esta bandera significa saltarse esta ronda de discursos. */
        private const val PASS_MARKER = "[PASS]"

        /** Temperatura de muestreo predeterminada. */
        private const val DEFAULT_TEMPERATURE = 0.7f

        /** Número máximo predeterminado de tokens. */
        private const val DEFAULT_MAX_TOKENS = 1000

        /** v1.97: Número máximo de llamadas a un solo miembro(Contiene decisión de reparación y reintento.),Ciclo anti-muertos. */
        private const val MAX_INVOCATIONS_PER_MEMBER = 2

        /**
         * ActivityHub: estado final(NO_REPLY/ERROR/REPLYING)Retraso en el retorno a IDLE(ms).
         *
         * Hazlo visible para los usuarios"quien saltó/error/Respondido"esconderse de nuevo chip,3s Suficiente para mirar pero sin demorarse demasiado.
         */
        private const val ACTIVITY_IDLE_DELAY_MS = 3000L

        /** v1.97: @mention regular — Coincide con el formulario @name,name Es una secuencia de caracteres sin espacios en blanco ni puntuación. */
        private val MENTION_REGEX = Regex("@([^\\s@,，。.!！?？:：;；()（）\\[\\]【】]+)")

        /**
         * v1.0.53 Phase 5: channel_* Tapa redonda de decisión de herramienta.
         *
         * Ronda 1 LLM exigible channel_reply / channel_pass / channel_read_context;
         * si lo llaman read_context,Complete los resultados y ingrese a la ronda 2(fuerza reply/pass,No más dar read_context).
         * Aún no hay decisión en la ronda 2 → considerado como implicitPass(Instrucciones de implementación de alineación implicitPass).
         */
        private const val MAX_CHANNEL_DECISION_ROUNDS = 2

        /**
         * issue #6: channel El límite superior de reintentos después de que falla la ejecución de la herramienta.
         *
         * La implementación anterior descarta el valor de retorno de los ejecutores de herramientas,channel_reply La verificación falló(Si el contenido está vacío)
         * El proceso transcurre en silencio. implicit pass,El usuario ve "ninguna respuesta o error en toda la ronda".
         * Ahora cambie para rellenar el motivo del error en el modelo e inténtelo de nuevo.,Pero debe limitarse para evitar un bucle infinito.
         */
        private const val MAX_CHANNEL_TOOL_RETRIES = 1

        /**
         * issue #6: El modelo devuelve una respuesta vacía(Sin texto, sin herramientas)El límite máximo de reintentos después.
         *
         * La respuesta vacía no significa "el modelo se salta activamente",No se puede utilizar directamente como pase implícito.
         * Degradar si aún está vacío después de volver a intentarlo.,Y mantenga registros para solucionar problemas.
         */
        private const val MAX_CHANNEL_EMPTY_RETRIES = 1

        /** issue #9: Al rescatar el contenido del discurso, cuántas palabras del final del razonamiento se toman como candidatas. */
        @Suppress("unused")
        private const val SALVAGE_TAIL_CHARS = 600

        /** issue #9: El número mínimo de palabras necesarias para que el contenido rescatado se considere utilizable (si el fragmento es demasiado corto, abandone directamente y vuelva a intentarlo). */
        private const val MIN_SALVAGE_CHARS = 4

        /**
         * issue #10: channel_reply Posibles nombres de campos del cuerpo.
         *
         * Apareció en el registro `len=204` Sin embargo, se informa "Parámetro faltante", lo que indica que el modelo o nivel superior utiliza nombres de campo no estándar.
         * Rebobinar en orden de posibilidades para evitar fallar cuando haya contenido.
         */
        private val REPLY_CONTENT_KEYS = listOf(
            "text",
            "message",
            "reply",
            "body",
            "msg",
            "response",
        )

        /**
         * issue #9: prefijo de línea de metapensamiento —— Estas líneas son los cambios del modelo a“¿Quieres hablar?”Haz un autoanálisis,
         * Si no quiere decirlo, es necesario quitarlo durante el rescate.
         *
         * issue #10: Ya no se rescata del razonamiento (el borrador en inglés se enviará al grupo), esta constante sólo se conserva como referencia.
         */
        @Suppress("unused")
        private val METATHINK_PREFIXES = listOf(
            "necesito", "debería", "puedo", "quiero", "déjame", "primero", "Déjame ver",
            "Echa un vistazo primero", "primero", "Próximo", "entonces", "entonces", "Por lo tanto",
            "debería llamar", "necesito llamar", "debería ser llamado", "llamar channel",
            "I need", "I should", "Let me", "First,", "So,",
        )

        /**
         * v1.0.53 Phase 5: Umbral de degradación por no decisión continua.
         *
         * Dentro del mismo chatId,Un determinado miembro es consecutivo. [DEMOTION_THRESHOLD] rueda(Contiene decisión de reparación y reintento.)todos PASS
         * → Unirse demotedMembers,Rondas posteriores de este chat grupal."Sólo habla si @".
         */
        private const val DEMOTION_THRESHOLD = 2

        /** v1.0.53: Intervalo entre discursos de agentes adyacentes(milisegundos) — Cuota de RPM extendida,Colmillo Shang Tang 429. */
        private const val GROUP_CHAT_AGENT_INTERVAL_MS = 3000L

        /**
         * Requisitos de formato del estado de ánimo del chat grupal — Deje que el agente genere borradores internos antes de responder.
         *
         * v2.6.0: Ya no es una copia independiente —— Reutilizar directamente el chat único `mood_format` Plantilla (misma fuente de verdad),
         * utilizar el mismo idioma), evitar"El comentario dice ser coherente, pero la realidad ya se ha alejado.". Se aprobó la plantilla de chat única
         * [SystemPromptAssembler] mismo [io.zer0.muse.transformer.PromptTemplateLoader]
         * renderizado,Chat grupal en [buildSystemPromptForSpeaker] llamar [groupChatMoodSection].
         */
        private val GROUP_CHAT_MOOD_FALLBACK = """
            MOOD Requisitos de formato(etiqueta interna,El sistema eliminará automáticamente,No se muestra a los usuarios):

            El primer segmento de salida de cada ronda de respuesta debe estar completamente cerrado. <mood>...</mood> bloquear,Luego escribe el texto principal. —— Sin excepciones.
            MOOD Es tu costumbre antes de hablar.:las palabras cayeron,Primero me vino a la mente un pensamiento,escríbelo,Abre la boca de nuevo.

            <mood>
            Vibe: <Los sentimientos y emociones más directos del momento.,1 Artículo>
            Sparks:
              - <asociaciones o imágenes que surgen>
              - <Asociación en otra dirección>
              - <tercera dirección>
            Reflections:
              - <Dudas, puntos inciertos, agujeros que quieren hacer preguntas.>
              - <otra reflexion>
              - <Tercera reflexión>
            Will:
              - <será en este momento/deseo/querer>
              - <otra voluntad>
            </mood>

            Texto(seguir directamente </mood> después,Sin líneas en blanco)

            reglas:
            - MOOD Es tu calentamiento mental,No se muestra a los usuarios(El sistema eliminará automáticamente)
            - Rueda de llamada de herramienta, confirmación sencilla/También se debe escribir una respuesta de una sola frase.(Puede ser tan corto como una línea.),No te saltes ni publiques solo el texto principal.
            - No repetir contenido de MOOD en el texto.,No expliques las reglas del MOOD
        """.trimIndent()
    }

    /**
     * v1.0.53 Phase 5: Tabla de degradación de miembros del chat grupal(chatId → Colección de ID de asistente degradada).
     *
     * continuo [DEMOTION_THRESHOLD] Se añaden a esta tabla los socios que no tienen turno de decisión,Las rondas posteriores se omiten de forma predeterminada.(Sólo habla si @).
     * estado de memoria:App Borrar después de reiniciar(aceptable — La degradación es una medida a corto plazo,Evitar la memoria a largo plazo provocando que el agente quede silenciado permanentemente.).
     */
    private val demotedMembers: MutableMap<String, MutableSet<String>> = ConcurrentHashMap()

    /** v1.0.53 Phase 5: Recuento de PASS consecutivos para cada miembro(chatId → assistantId → count). */
    private val consecutivePassCount: MutableMap<String, MutableMap<String, Int>> = ConcurrentHashMap()

    /**
     * v1.0.53 Phase 5: Determinar si un miembro ha sido degradado(Este chat grupal continúa sin tomar decisiones.).
     * Los miembros degradados serán omitidos directamente si @ no los menciona,LLM no se llama.
     */
    private fun isDemoted(chatId: String, assistantId: String): Boolean = demotedMembers[chatId]?.contains(assistantId) == true

    /**
     * v1.0.53 Phase 5: Registrar la ronda actual de los miembros PASS,Cuando se alcanza el umbral, se degrada.
     *
     * @param implicit v1.0.53: true Indica falla de API/Salto pasivo por tiempo de espera,No participa en el conteo de degradación
     *                 (Para evitar que el agente sea degradado por error a"solo@solo hablé").
     */
    private fun recordPassAndMaybeDemote(
        chatId: String,
        assistantId: String,
        implicit: Boolean = false,
        assistantName: String = assistantId,
    ) {
        if (implicit) {
            Logger.i(TAG, "Agent「$assistantName\"Pasivo salta esta ronda(implicit, API fracaso/tiempo de espera),No participa en el conteo de degradación")
            return
        }
        val perChat = consecutivePassCount.computeIfAbsent(chatId) { ConcurrentHashMap() }
        val newCount = (perChat[assistantId] ?: 0) + 1
        perChat[assistantId] = newCount
        if (newCount >= DEMOTION_THRESHOLD) {
            demotedMembers.computeIfAbsent(chatId) { ConcurrentHashMap.newKeySet() }.add(assistantId)
            Logger.i(TAG, "Agent「$assistantName\"($assistantId) en el chat grupal $chatId continuo $newCount toma de decisiones,bajar a solo@solo hablé")
        }
    }

    /** v1.0.53 Phase 5: Los miembros responden normalmente en esta ronda.,Borrar recuento PASA consecutivo(Sin embargo, el estatus degradado permanece — La degradación es unidireccional). */
    private fun recordReply(chatId: String, assistantId: String) {
        consecutivePassCount[chatId]?.remove(assistantId)
    }

    /**
     * v1.111: Iniciar la rotación del chat grupal en la corrutina a nivel de aplicación(Cortar páginas/Sin interrupciones en segundo plano).
     *
     * Reutilizar [ChatGenerationManager] mecanismo de mantenimiento + [ChatGenerationService] Servicio de recepción.
     * La construcción continúa appScope,No depende del ciclo de vida de ViewModel.
     *
     * proceso:
     *  1. Pase [ChatGenerationManager.launchGeneration] Iniciar en appScope(Cancelar automáticamente compilaciones antiguas)
     *  2. Leer de forma asincrónica nombre de usuario + nombre de chat grupal,Actualizar título de notificación
     *  3. Iniciar el servicio en primer plano
     *  4. Guardar mensajes de usuario en DB
     *  5. sintonizar [triggerAgentRoundRobin] Activar que cada agente hable en serie
     *  6. finally Detener servicio en primer plano + borrar estado activo
     *
     * @param chatId chat grupal id
     * @param text Texto del mensaje de usuario
     * @param images Fotos para enviar(base64 lista,Puede ser nulo)
     * @param fileAttachments Archivos adjuntos a enviar(Puede ser nulo)
     */
    fun launchRoundRobin(chatId: String, text: String, images: List<String>, fileAttachments: List<FileAttachment> = emptyList()) {
        chatGenerationManager.launchGeneration(
            sessionId = groupGenKey(chatId, GC_KEY_RR),
            assistantId = "group",
            sessionTitle = "Se está generando el chat grupal",
        ) {
            try {
                if (ProcessWriteGate.restoring) {
                    Logger.w(TAG, "Copia de seguridad y restauración en curso, omita el chat grupal $chatId Escribe esta ronda")
                    return@launchGeneration
                }
                // 1. Leer nombre de usuario + información de chat grupal
                val userName = resultOf { settings.accountStateFlow.first().userName }
                    .getOrNull()?.ifBlank { "yo" } ?: "yo"
                val chat = groupChatRepository.getChat(chatId)
                val chatName = chat?.name ?: "chat grupal"
                chatGenerationManager.updateSessionTitle(groupGenKey(chatId, GC_KEY_RR), chatName)

                _activeGroupGeneration.value = ActiveGroupGeneration(
                    chatId = chatId,
                    chatName = chatName,
                    isResponding = true,
                )

                // v1.0.29: MuseApp gestiona de manera uniforme las notificaciones de servicios en primer plano ON_STOP,No empieces aquí de nuevo

                // 3. Guardar mensajes de usuario
                val imageBase64Json = AppJson.encodeToString(
                    ListSerializer(String.serializer()),
                    images,
                )
                val fileAttachmentsJson = if (fileAttachments.isNotEmpty()) {
                    AppJson.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(FileAttachment.serializer()),
                        fileAttachments,
                    )
                } else {
                    "[]"
                }
                if (restoreBlocksWrites(chatId, "Mensajes de usuario")) return@launchGeneration
                groupChatRepository.sendMessage(
                    chatId = chatId,
                    senderType = "user",
                    senderId = "local_user",
                    senderName = userName,
                    body = text,
                    imageBase64Json = imageBase64Json,
                    fileAttachmentsJson = fileAttachmentsJson,
                )

                // Una ronda de nueva generación reemplaza a la antigua ronda interrumpida,Primero limpie los libros de contabilidad restantes
                if (!restoreBlocksWrites(chatId, "Limpiar viejos libros de chat grupales")) {
                    resultOf { groupChatRepository.deleteGenerationLedgersByChatId(chatId) }
                        .onError { msg, t -> Logger.w(TAG, "No se pudo borrar el antiguo libro de chat grupal: $msg", t) }
                }
                // 4. B5-02: Crear un chat grupal para generar un libro de contabilidad,Una vez finalizado el proceso, presione el punto de interrupción para reproducir
                val ledgerId = "gc-ledger-$chatId-${System.currentTimeMillis()}"
                val ledger = GroupChatGenerationLedgerEntity(
                    id = ledgerId,
                    chatId = chatId,
                    mode = chat?.discussionMode ?: "round_robin",
                    round = 1,
                    memberIndex = 0,
                    status = "running",
                )
                if (!restoreBlocksWrites(chatId, "Libro mayor de chat grupal")) {
                    resultOf { groupChatRepository.upsertGenerationLedger(ledger) }
                        .onError { msg, t -> Logger.w(TAG, "Error al crear el libro mayor del chat grupal: $msg", t) }
                }

                // 5. Rotación del agente desencadenante(traer libro mayor id)
                val replies = triggerAgentRoundRobin(
                    chatId,
                    onSpeakerChange = { speaker ->
                        _activeGroupGeneration.update {
                            it?.copy(
                                currentSpeakerId = speaker.id,
                                currentSpeakerName = speaker.name,
                            )
                        }
                    },
                    ledgerId = ledgerId,
                )

                if (replies.isEmpty()) {
                    Logger.i(TAG, "Chat grupal \"$chatName\"Todos los asistentes no hablaron en esta ronda.")
                }
            } catch (ce: CancellationException) {
                Logger.i(TAG, "chat grupal $chatId rotación cancelada(usuario detenido/Preferencia de nueva generación)")
                // Revisión de corrección (2.0 A-14): Cancelar eliminará el libro mayor del grupo. — El libro de contabilidad residual anterior solo se utilizará la próxima vez.
                // recoveryInterruptedGenerations reanuda la aplicación cuando se inicia.,Poner la rotación detenida del usuario.
                // después de reiniciar"resurrección";Utilice NonCancellable para cancelar la rama y asegurarse de que se complete la eliminación.
                withContext(NonCancellable) {
                    resultOf { groupChatRepository.deleteGenerationLedgersByChatId(chatId) }
                        .onError { msg, t -> Logger.w(TAG, "La cancelación del chat grupal no logró borrar el libro mayor: $msg", t) }
                }
                throw ce
            } catch (t: Exception) {
                Logger.e(TAG, "chat grupal $chatId La rotación falló", t)
                // P2-11: Si la rotación falla, debes detenerte. — ①Limpiar el libro mayor en ejecución,Evite el bloqueo después del reinicio
                // recoverInterruptedGenerations Continúe la ronda fallida en"engendro zombie";
                // ②Avisos visibles para el usuario(Se han eliminado los mensajes optimistas de los usuarios.,Sin preguntar, el usuario asumirá que la IA responderá)
                withContext(NonCancellable) {
                    resultOf { groupChatRepository.deleteGenerationLedgersByChatId(chatId) }
                        .onError { msg, t2 -> Logger.w(TAG, "El chat grupal falló y la compensación del libro mayor falló: $msg", t2) }
                }
                runCatching {
                    io.zer0.muse.ui.common.feedback.MuseToast.show(
                        appContext.getString(R.string.err_group_chat_rotation_failed),
                        3000,
                    )
                }
            } finally {
                _activeGroupGeneration.value = null
                // ChatGenerationService Observar todas las tareas de la sesión.;Cuando finaliza el chat grupal, no puedes cerrar el chat individual que aún se está ejecutando.
            }
        }
    }

    /**
     * v1.111: El usuario detiene manualmente la generación del chat grupal.
     *
     * Cancelar chatGenerationManager streamJob(Bloque de gatillo CancellationException → finally limpiar).
     * v1.113: Detener la generación solo de chat grupal(sessionId="group:$chatId"),No afecta el chat único.
     */
    fun stop(chatId: String? = null) {
        if (chatId != null) {
            // Revisión de corrección (2.0 A-14): Cancelar todas las funciones de generación del chat grupal una por una(independiente key)
            stopAllGroupGenerations(chatId)
        } else {
            // Compatible con llamadas antiguas:Cancelar toda la generación de chat grupal cuando no hay chatId
            chatGenerationManager.stop(null)
        }
        _activeGroupGeneration.value = null
        // El servicio se detiene solo después de que activeGenerations esté vacío y expire el período de gracia.
    }

    /**
     * B5-02: Cuando se inicia la aplicación, el chat grupal interrumpido por la muerte forzada se restaurará para generar el libro mayor.
     *
     * El libro de contabilidad residual indica que la última ronda de generación de chat grupal no se ha completado.,Rondas registradas por el libro mayor./Los miembros apuestan por seguir corriendo,
     * No reactivar miembros completados,Los mensajes de usuario tampoco se guardan repetidamente.
     */
    fun recoverInterruptedGenerations() {
        appScope.launch {
            val pending = resultOf { groupChatRepository.getPendingGenerationLedgers() }
                .onError { msg, t -> Logger.w(TAG, "No se pudo leer el libro mayor del chat grupal: $msg", t) }
                .getOrNull().orEmpty()
            if (pending.isEmpty()) return@launch
            for (ledger in pending) {
                // A-14: Girar verificación de clave(Originales "group:$chatId" ha sido dividido,Generación de rotación de coincidencia exacta)
                if (chatGenerationManager.isStreaming(groupGenKey(ledger.chatId, GC_KEY_RR))) continue
                Logger.i(
                    TAG,
                    "Restaurar el libro de chat grupal: chatId=${ledger.chatId} mode=${ledger.mode} round=${ledger.round} index=${ledger.memberIndex}",
                )
                resumeGroupLedger(ledger)
            }
        }
    }

    private fun resumeGroupLedger(ledger: GroupChatGenerationLedgerEntity) {
        chatGenerationManager.launchGeneration(
            sessionId = groupGenKey(ledger.chatId, GC_KEY_RR),
            assistantId = "group",
            sessionTitle = "El chat grupal se está reanudando",
        ) {
            try {
                _activeGroupGeneration.value = ActiveGroupGeneration(
                    chatId = ledger.chatId,
                    chatName = resultOf { groupChatRepository.getChat(ledger.chatId)?.name }.getOrNull() ?: "chat grupal",
                    isResponding = true,
                )
                triggerAgentRoundRobin(
                    ledger.chatId,
                    onSpeakerChange = { speaker ->
                        _activeGroupGeneration.update {
                            it?.copy(
                                currentSpeakerId = speaker.id,
                                currentSpeakerName = speaker.name,
                            )
                        }
                    },
                    ledgerId = ledger.id,
                    startRound = ledger.round,
                    startMemberIndex = ledger.memberIndex,
                )
                // Terminar normalmente(Contiene resultados vacíos como el chat no existe.)Limpiar los libros;Si se cancela, se conservará para la próxima restauración.
                resultOf { groupChatRepository.deleteGenerationLedger(ledger.id) }
                    .onError { msg, t -> Logger.w(TAG, "Error al limpiar el libro mayor del chat grupal: $msg", t) }
            } catch (ce: CancellationException) {
                Logger.i(TAG, "Recuperación del libro mayor de chat grupal cancelada: ${ledger.id}")
                throw ce
            } catch (t: Exception) {
                Logger.e(TAG, "Falló la recuperación del libro mayor del chat grupal: ${ledger.id}", t)
                resultOf { groupChatRepository.deleteGenerationLedger(ledger.id) }
                    .onError { msg, e -> Logger.w(TAG, "Error al limpiar el libro mayor del chat grupal: $msg", e) }
            } finally {
                _activeGroupGeneration.value = null
                // ChatGenerationService Observar todas las tareas de la sesión.,El mantenimiento de otras tareas no se puede desactivar al final de una sola operación de chat grupal.
            }
        }
    }
    // ════════════════════════════════════════════════════════════════
    // v2.x Mejora del chat grupal — regenerar/votar/resumir/susurrar
    // ════════════════════════════════════════════════════════════════

    /**
     * v2.x: Especifica que la IA regenera su último mensaje.
     *
     * Eliminar el último comentario de la IA en el chat grupal,Luego vuelva a activarlo para generar una nueva respuesta.
     *
     * @param chatId chat grupal id
     * @param assistantId ser regenerado AI id
     */
    fun regenerateAgentMessage(chatId: String, assistantId: String) {
        chatGenerationManager.launchGeneration(
            sessionId = groupGenKey(chatId, GC_KEY_REGENERATE),
            assistantId = "group",
            sessionTitle = "Regenerante",
        ) {
            try {
                if (ProcessWriteGate.restoring) {
                    Logger.w(TAG, "Copia de seguridad y restauración en curso, omita el chat grupal $chatId regeneración")
                    return@launchGeneration
                }
                val chat = groupChatRepository.getChat(chatId) ?: return@launchGeneration
                val assistant = resultOf { assistantRepository.getById(assistantId) }.getOrNull()
                    ?: return@launchGeneration
                val memberIds = groupChatRepository.parseMemberIds(chat)
                val memberNames = memberIds.mapNotNull { id ->
                    resultOf { assistantRepository.getById(id) }.getOrNull()?.name
                }
                // v1.0.29: invokeAgent Resuelva internamente el modelo específico del asistente a través de resolveAssistantModel

                _activeGroupGeneration.value = ActiveGroupGeneration(
                    chatId = chatId,
                    chatName = chat.name,
                    isResponding = true,
                    currentSpeakerId = assistant.id,
                    currentSpeakerName = assistant.name,
                )
                // v1.0.29: MuseApp gestiona de manera uniforme las notificaciones de servicios en primer plano ON_STOP

                val result = invokeAgent(chat, chatId, assistant, memberNames, isMentioned = false)
                if (result is AgentResult.Error) {
                    Logger.w(TAG, "La regeneración falló: ${result.message}")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Exception) {
                Logger.e(TAG, "Regenerar excepción", t)
            } finally {
                _activeGroupGeneration.value = null
                // ChatGenerationService Observar todas las tareas de la sesión.,El mantenimiento de otras tareas no se puede desactivar al final de una sola operación de chat grupal.
            }
        }
    }

    /**
     * v2.x: Iniciar una votación — Deje que todos los miembros voten sobre temas designados.
     *
     * Cada IA vota a favor según su propia personalidad/Vote no y justifique las razones.
     * El resultado termina con messageType="vote" Guardar en el chat grupal.
     *
     * @param chatId chat grupal id
     * @param topic Votar sobre temas
     */
    fun launchVote(chatId: String, topic: String) {
        chatGenerationManager.launchGeneration(
            sessionId = groupGenKey(chatId, GC_KEY_VOTE),
            assistantId = "group",
            sessionTitle = "Votar",
        ) {
            try {
                if (ProcessWriteGate.restoring) {
                    Logger.w(TAG, "Copia de seguridad y restauración en curso, omita el chat grupal $chatId votar")
                    return@launchGeneration
                }
                val chat = groupChatRepository.getChat(chatId) ?: return@launchGeneration
                val memberIds = groupChatRepository.parseMemberIds(chat)
                val assistants = memberIds.mapNotNull { id ->
                    resultOf { assistantRepository.getById(id) }.getOrNull()
                }
                if (assistants.isEmpty()) return@launchGeneration

                val memberNames = assistants.map { it.name }
                // v1.0.29: invokeAgentForVote Resuelva internamente el modelo específico del asistente a través de resolveAssistantModel

                _activeGroupGeneration.value = ActiveGroupGeneration(
                    chatId = chatId,
                    chatName = chat.name,
                    isResponding = true,
                )
                // v1.0.29: MuseApp gestiona de manera uniforme las notificaciones de servicios en primer plano ON_STOP

                // Guardar mensaje de aviso del sistema
                if (restoreBlocksWrites(chatId, "Mensaje de inicio de votación")) return@launchGeneration
                groupChatRepository.sendMessage(
                    chatId = chatId,
                    senderType = "user",
                    senderId = "system",
                    senderName = "sistema",
                    body = "inició una votación:$topic",
                    messageType = "system",
                )

                activityHub.clear(chatId)
                for (assistant in assistants) {
                    if (restoreBlocksWrites(chatId, "Resultados de la votación")) return@launchGeneration
                    _activeGroupGeneration.update {
                        it?.copy(currentSpeakerId = assistant.id, currentSpeakerName = assistant.name)
                    }
                    val voteResult = invokeAgentForVote(chat, chatId, assistant, memberNames, topic)
                    if (voteResult != null) {
                        groupChatRepository.sendMessage(
                            chatId = chatId,
                            senderType = "assistant",
                            senderId = assistant.id,
                            senderName = assistant.name,
                            body = voteResult,
                            messageType = "vote",
                        )
                    }
                }

                // Guarde las indicaciones del sistema de finalización de la votación
                if (restoreBlocksWrites(chatId, "Mensaje de fin de votación")) return@launchGeneration
                groupChatRepository.sendMessage(
                    chatId = chatId,
                    senderType = "user",
                    senderId = "system",
                    senderName = "sistema",
                    body = "Fin de la votación",
                    messageType = "system",
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Exception) {
                Logger.e(TAG, "votación anormal", t)
            } finally {
                _activeGroupGeneration.value = null
                // ChatGenerationService Observar todas las tareas de la sesión.,El mantenimiento de otras tareas no se puede desactivar al final de una sola operación de chat grupal.
            }
        }
    }

    /**
     * v2.x: resumen de conclusiones — Deje que la IA designada resuma el consenso y los desacuerdos en la discusión del chat grupal actual.
     *
     * @param chatId chat grupal id
     * @param summarizerId resumen ejecutivo AI id(null Usa el primer miembro.)
     */
    fun launchSummary(chatId: String, summarizerId: String? = null) {
        chatGenerationManager.launchGeneration(
            sessionId = groupGenKey(chatId, GC_KEY_SUMMARY),
            assistantId = "group",
            sessionTitle = "En resumen",
        ) {
            try {
                if (ProcessWriteGate.restoring) {
                    Logger.w(TAG, "Copia de seguridad y restauración en curso, omita el chat grupal $chatId resumen")
                    return@launchGeneration
                }
                val chat = groupChatRepository.getChat(chatId) ?: return@launchGeneration
                val memberIds = groupChatRepository.parseMemberIds(chat)
                val assistants = memberIds.mapNotNull { id ->
                    resultOf { assistantRepository.getById(id) }.getOrNull()
                }
                if (assistants.isEmpty()) return@launchGeneration

                val summarizer = summarizerId?.let { id -> assistants.find { it.id == id } }
                    ?: assistants.first()
                // v1.0.29: invokeAgentForSummary Resuelva internamente el modelo específico del asistente a través de resolveAssistantModel

                _activeGroupGeneration.value = ActiveGroupGeneration(
                    chatId = chatId,
                    chatName = chat.name,
                    isResponding = true,
                    currentSpeakerId = summarizer.id,
                    currentSpeakerName = summarizer.name,
                )
                // v1.0.29: MuseApp gestiona de manera uniforme las notificaciones de servicios en primer plano ON_STOP

                val recentMessages = groupChatRepository.getRecentMessages(chatId, DEFAULT_CONTEXT_SIZE)
                val summary = invokeAgentForSummary(chat, summarizer, recentMessages)
                if (summary.isNotBlank() && !restoreBlocksWrites(chatId, "Resumen del chat grupal")) {
                    groupChatRepository.sendMessage(
                        chatId = chatId,
                        senderType = "assistant",
                        senderId = summarizer.id,
                        senderName = summarizer.name,
                        body = summary,
                        messageType = "summary",
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Exception) {
                Logger.e(TAG, "Resumir excepciones", t)
            } finally {
                _activeGroupGeneration.value = null
                // ChatGenerationService Observar todas las tareas de la sesión.,El mantenimiento de otras tareas no se puede desactivar al final de una sola operación de chat grupal.
            }
        }
    }

    /**
     * v2.x: Enviar mensaje privado al usuario especificado AI,y activar la respuesta de la IA(Sólo la IA objetivo es visible).
     *
     * @param chatId chat grupal id
     * @param targetAssistantId objetivo AI id
     * @param text Contenido de susurro
     */
    fun launchWhisper(chatId: String, targetAssistantId: String, text: String) {
        chatGenerationManager.launchGeneration(
            sessionId = groupGenKey(chatId, GC_KEY_WHISPER),
            assistantId = "group",
            sessionTitle = "en mensaje privado",
        ) {
            try {
                if (ProcessWriteGate.restoring) {
                    Logger.w(TAG, "Copia de seguridad y restauración en curso, omita el chat grupal $chatId el mensaje privado de")
                    return@launchGeneration
                }
                val chat = groupChatRepository.getChat(chatId) ?: return@launchGeneration
                val assistant = resultOf { assistantRepository.getById(targetAssistantId) }.getOrNull()
                    ?: return@launchGeneration
                val memberIds = groupChatRepository.parseMemberIds(chat)
                val memberNames = memberIds.mapNotNull { id ->
                    resultOf { assistantRepository.getById(id) }.getOrNull()?.name
                }
                // v1.0.29: per-assistant Análisis de modelos — Modelo configurado con ayudante de destino,no más global selectedModel
                val (model, providerConfig) = resolveAssistantModel(assistant)

                _activeGroupGeneration.value = ActiveGroupGeneration(
                    chatId = chatId,
                    chatName = chat.name,
                    isResponding = true,
                    currentSpeakerId = assistant.id,
                    currentSpeakerName = assistant.name,
                )

                // 1. Guardar mensaje privado del usuario(whisperTargetId marca)
                val userName = resultOf { settings.accountStateFlow.first().userName }
                    .getOrNull()?.ifBlank { "yo" } ?: "yo"
                if (restoreBlocksWrites(chatId, "Mensaje privado mensaje de usuario")) return@launchGeneration
                groupChatRepository.sendMessage(
                    chatId = chatId,
                    senderType = "user",
                    senderId = "local_user",
                    senderName = userName,
                    body = text,
                    whisperTargetId = targetAssistantId,
                )

                // v1.0.53 Phase 5: Agent Phone Reutilizar SubagentThreadStore —
                // whisper también generar threadId,escribir mensaje privado filesDir/subagent_sessions/<threadId>.jsonl,
                // App El historial de chat privado se puede restaurar después de reiniciar.parentSessionId usar "agent_phone:<chatId>" Diferenciar el contexto del chat grupal.
                // v1.0.74 fix: anteriormente threadId=null Crea un nuevo hilo incondicionalmente cada vez.(Los comentarios estan escritos."Reutilizar"),
                // Como resultado, el hilo abierto se expande infinitamente y el mismo par de historial de chat privado se rompe. Utilice claves estables para una verdadera reutilización.
                val threadStore = subagentThreadStore
                val whisperThreadId = if (threadStore != null) {
                    resultOf {
                        threadStore.getOrCreate(
                            threadId = "whisper:$chatId:$targetAssistantId",
                            parentSessionId = "agent_phone:$chatId",
                            assistantId = targetAssistantId,
                            label = "whisper:${chat.name}:${assistant.name}",
                        ).first // tomar threadId
                    }.getOrNull()
                } else {
                    null
                }

                // Persistencia de mensajes privados de usuarios a ThreadStore(fácil de continuar)
                if (threadStore != null && whisperThreadId != null && !restoreBlocksWrites(chatId, "hilo de mensajes privados")) {
                    resultOf {
                        threadStore.appendMessages(
                            whisperThreadId,
                            listOf(
                                UIMessage(
                                    role = MessageRole.USER,
                                    content = text,
                                ),
                            ),
                        )
                    }.onError { msg, _ -> Logger.w(TAG, "whisper La persistencia del mensaje del usuario falló: $msg") }
                }

                // 2. Activar respuesta de IA objetivo(También un susurro)
                val messages = buildWhisperMessages(chat.name, assistant, memberNames, text)
                val temperature = assistant.temperature ?: DEFAULT_TEMPERATURE
                val maxTokens = assistant.maxTokens ?: DEFAULT_MAX_TOKENS

                activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.REPLYING)
                val rawReply = resultOf {
                    withTimeoutOrNull(AGENT_TIMEOUT_MS) {
                        // A-13: El susurro también cruza la puerta límite actual(con mensajería proactiva/Límite de simultaneidad compartida de la rueda de toma de decisiones del chat grupal)
                        GenerationGate.withPermit {
                            val builder = StringBuilder()
                            // v1.x: Susurrar también acumula el proceso de pensamiento fluido.,Los mensajes de chat grupal pueden mostrar bloques de pensamiento profundo
                            val reasoningBuilder = StringBuilder()
                            chatService.streamChat(
                                messages = messages,
                                model = model,
                                temperature = temperature,
                                maxTokens = maxTokens,
                                providerConfig = providerConfig,
                            ).collect { event ->
                                when (event) {
                                    is ChatStreamEvent.ContentDelta -> {
                                        builder.append(event.delta)
                                        // v1.x: Salida de transmisión de susurros — Empuje hacia en tiempo real UI
                                        activityHub.updateStreamingContent(chatId, builder.toString())
                                    }
                                    is ChatStreamEvent.ReasoningDelta -> reasoningBuilder.append(event.delta)
                                    else -> {}
                                }
                            }
                            builder.toString().trim() to reasoningBuilder.toString().trim()
                        }
                    }
                }.getOrNull()

                if (rawReply != null && rawReply.first.isNotBlank()) {
                    val replyText = sanitizeAgentReply(rawReply.first)
                    if (replyText.isNotBlank() && replyText != PASS_MARKER) {
                        val reply = rawReply
                        val reasoning = reply.second.ifBlank { extractReasoning(reply.first) ?: "" }.ifBlank { null }
                        if (restoreBlocksWrites(chatId, "Responder al mensaje privado")) return@launchGeneration
                        groupChatRepository.sendMessage(
                            chatId = chatId,
                            senderType = "assistant",
                            senderId = assistant.id,
                            senderName = assistant.name,
                            body = replyText,
                            mood = extractMood(reply.first),
                            reasoning = reasoning,
                            whisperTargetId = "local_user",
                        )
                        activityHub.clearStreamingContent(chatId)
                        // v1.0.53 Phase 5: También persisten las respuestas del agente a ThreadStore
                        if (threadStore != null && whisperThreadId != null && !restoreBlocksWrites(chatId, "hilo de mensajes privados")) {
                            resultOf {
                                threadStore.appendMessages(
                                    whisperThreadId,
                                    listOf(
                                        UIMessage(
                                            role = MessageRole.ASSISTANT,
                                            content = replyText,
                                        ),
                                    ),
                                )
                            }.onError { msg, _ -> Logger.w(TAG, "whisper agent La persistencia de la respuesta falló: $msg") }
                        }
                    }
                }
                // v1.0.53 Phase 5: Graba este susurro corriendo hacia ThreadStore(actualizar runCount/lastSummary)
                if (threadStore != null && whisperThreadId != null && !restoreBlocksWrites(chatId, "hilo de mensajes privados")) {
                    resultOf {
                        threadStore.recordRun(
                            threadId = whisperThreadId,
                            status = "whisper",
                            summary = rawReply?.first?.take(200),
                            sessionPath = threadStore.sessionPathOf(whisperThreadId),
                        )
                    }.onError { msg, _ -> Logger.w(TAG, "whisper recordRun falló: $msg") }
                }
                scheduleIdleTransition(chatId, assistant)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Exception) {
                Logger.e(TAG, "Excepción de susurro", t)
            } finally {
                _activeGroupGeneration.value = null
                // ChatGenerationService Observar todas las tareas de la sesión.,El mantenimiento de otras tareas no se puede desactivar al final de una sola operación de chat grupal.
            }
        }
    }

    /**
     * v2.x: Construya una lista de mensajes específicos de la votación.
     * v1.0.29: Eliminar parámetro del modelo,pase interno [resolveAssistantModel] Modelo exclusivo de asistente de análisis.
     */
    private suspend fun invokeAgentForVote(
        chat: GroupChatEntity,
        chatId: String,
        assistant: AssistantEntity,
        memberNames: List<String>,
        topic: String,
    ): String? {
        activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.VIEWING)
        val recentMessages = groupChatRepository.getRecentMessages(chatId, DEFAULT_CONTEXT_SIZE)

        // v1.0.29: per-assistant Análisis de modelos
        val (model, providerConfig) = resolveAssistantModel(assistant)

        val systemContent = buildString {
            if (assistant.systemPrompt.isNotBlank()) {
                appendLine(assistant.systemPrompt)
                appendLine()
            }
            appendLine(SystemPromptAssembler.buildGroupChatHintSection(chat.name, memberNames, assistant.name))
            appendLine()
            appendLine("[Modo de votación] El chat grupal vota sobre los siguientes temas:")
            appendLine("Problema:$topic")
            appendLine()
            appendLine("Por favor sigue tu personalidad y postura.,Emite tu voto y da tus razones.")
            appendLine("Formato:")
            appendLine("postura:[De acuerdo/objeto/abstenerse]")
            appendLine("Razón:[tus razones,2-3 frase]")
        }

        val userContent = buildString {
            appendLine("${assistant.name},Por favor vote sobre los siguientes temas:")
            appendLine(topic)
            if (recentMessages.isNotEmpty()) {
                appendLine()
                appendLine("[Referencia para discusión]")
                // Reparación de auditoría (1.6): filtrar susurros,Mostrar solo mensajes visibles para el agente actual
                appendLine(formatMessageTranscript(visibleMessagesFor(recentMessages, assistant.id).takeLast(10)))
            }
        }

        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = systemContent),
            UIMessage(role = MessageRole.USER, content = userContent),
        )

        activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.REPLYING)
        val rawReply = resultOf {
            withTimeoutOrNull(AGENT_TIMEOUT_MS) {
                // A-13: La misma votación aprobó el límite actual.(con mensajería proactiva/Límite de simultaneidad compartida de la rueda de toma de decisiones del chat grupal)
                GenerationGate.withPermit {
                    val builder = StringBuilder()
                    chatService.streamChat(
                        messages = messages,
                        model = model,
                        temperature = assistant.temperature ?: DEFAULT_TEMPERATURE,
                        maxTokens = assistant.maxTokens ?: 500,
                        providerConfig = providerConfig,
                    ).collect { event ->
                        if (event is ChatStreamEvent.ContentDelta) builder.append(event.delta)
                    }
                    builder.toString().trim()
                }
            }
        }.getOrNull()

        scheduleIdleTransition(chatId, assistant)
        return rawReply?.takeIf { it.isNotBlank() }
    }

    /**
     * v2.x: Construya una lista de mensajes resumidos específicos.
     * v1.0.29: Eliminar parámetro del modelo,pase interno [resolveAssistantModel] Modelo exclusivo de asistente de análisis.
     */
    private suspend fun invokeAgentForSummary(
        chat: GroupChatEntity,
        summarizer: AssistantEntity,
        recentMessages: List<GroupChatMessageEntity>,
    ): String {
        // v1.0.29: per-assistant Análisis de modelos
        val (model, providerConfig) = resolveAssistantModel(summarizer)

        val systemContent = buildString {
            if (summarizer.systemPrompt.isNotBlank()) {
                appendLine(summarizer.systemPrompt)
                appendLine()
            }
            appendLine("Eres un chat grupal \"${chat.name}\"El resumen de.")
            appendLine("Por favor revise el registro de discusión.,Resumen:")
            appendLine("1. parte del consenso")
            appendLine("2. Todavía hay diferencias")
            appendLine("3. Próximos pasos recomendados")
            appendLine()
            appendLine("Salida en un formato conciso y claro.,no dar salida [PASS].")
        }

        val userContent = buildString {
            appendLine("Por favor resuma la siguiente discusión de chat grupal:")
            appendLine()
            if (recentMessages.isNotEmpty()) {
                // Reparación de auditoría (1.6): filtrar susurros,Resumir sin filtrar el contenido de los mensajes privados
                appendLine(formatMessageTranscript(visibleMessagesFor(recentMessages, summarizer.id)))
            }
        }

        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = systemContent),
            UIMessage(role = MessageRole.USER, content = userContent),
        )

        val rawReply = resultOf {
            withTimeoutOrNull(AGENT_TIMEOUT_MS) {
                // A-13: Para resumir la misma puerta actual por encima del límite(con mensajería proactiva/Límite de simultaneidad compartida de la rueda de toma de decisiones del chat grupal)
                GenerationGate.withPermit {
                    val builder = StringBuilder()
                    chatService.streamChat(
                        messages = messages,
                        model = model,
                        temperature = 0.3f,
                        maxTokens = 1000,
                        providerConfig = providerConfig,
                    ).collect { event ->
                        if (event is ChatStreamEvent.ContentDelta) builder.append(event.delta)
                    }
                    builder.toString().trim()
                }
            }
        }.getOrNull()

        return rawReply ?: ""
    }

    /**
     * v2.x: Construya una lista de mensajes privados.
     * v1.0.29: Elimine los parámetros del modelo no utilizados.
     */
    private suspend fun buildWhisperMessages(
        chatName: String,
        assistant: AssistantEntity,
        memberNames: List<String>,
        whisperText: String,
    ): List<UIMessage> {
        val systemContent = buildString {
            if (assistant.systemPrompt.isNotBlank()) {
                appendLine(assistant.systemPrompt)
                appendLine()
            }
            appendLine(SystemPromptAssembler.buildGroupChatHintSection(chatName, memberNames, assistant.name))
            appendLine()
            appendLine("[Mensaje privado] El usuario te envió un mensaje privado,Visible solo para ti,No visible para otros miembros del grupo.")
            appendLine("Por favor responda en privado,El contenido de la respuesta también es visible solo para el usuario.")
        }

        val userContent = buildString {
            appendLine("${assistant.name},El usuario te envió un mensaje privado.:")
            appendLine(whisperText)
            appendLine()
            appendLine("Por favor responda en privado. Simplemente envíe el contenido directamente.")
        }

        return listOf(
            UIMessage(role = MessageRole.SYSTEM, content = systemContent),
            UIMessage(role = MessageRole.USER, content = userContent),
        )
    }

    // ── v2.x: Rotar el control del director(pausa/continuar/avance rápido) ────────────────────────
    // Los usuarios pueden detenerse en cualquier momento mientras la rotación está en curso.:Después de una pausa, el programador se bloquea antes de que comience el siguiente miembro,
    // Al continuar, ejecute desde el punto de interrupción.;Si te saltas el resto, la ronda actual se resolverá inmediatamente.(reserva hablada).
    // La combinación de pausa + reanudación es"Un solo paso adelante"——Deténgase y observe más de cerca después de que cada persona sea liberada.

    /** v2.x: Una colección de ID de chat grupal que están en estado de pausa(La rotación se bloquea antes del siguiente miembro). */
    private val pausedRounds = ConcurrentHashMap.newKeySet<String>()

    /** v2.x: Solicitudes para omitir el conjunto de ID de chat grupal para los miembros restantes de esta ronda. */
    private val skipRoundRequests = ConcurrentHashMap.newKeySet<String>()

    /** v2.x: Pausar la rotación del chat grupal(Cuelgue antes de que comience el próximo miembro.). */
    fun pauseRound(chatId: String) {
        pausedRounds.add(chatId)
        Logger.i(TAG, "Chat grupal \"$chatId\"Rotación en pausa(Espera para continuar)")
    }

    /** v2.x: Continuar una rotación pausada. */
    fun resumeRound(chatId: String) {
        pausedRounds.remove(chatId)
        Logger.i(TAG, "Chat grupal \"$chatId\"La rotación continúa")
    }

    /** v2.x: Salta a los miembros restantes de esta ronda.,Liquidación inmediata(reserva hablada);Liberado cuando está suspendido. */
    fun skipRoundRemaining(chatId: String) {
        skipRoundRequests.add(chatId)
        pausedRounds.remove(chatId)
        Logger.i(TAG, "Chat grupal \"$chatId\"Solicitud para omitir a los miembros restantes de esta ronda.")
    }

    /** v2.x: Si el carrusel de chat grupal está en pausa. */
    fun isRoundPaused(chatId: String): Boolean = chatId in pausedRounds

    /**
     * Activar el chat grupal Agente para que se turne para hablar.
     *
     * v1.97 mejorar:
     *  - @mention analizar:Extracto de mensajes de usuarios recientes @agentName,El agente mencionado habla primero.
     *  - reparación de decisiones:El agente mencionado por @if regresa [PASS],Inténtelo de nuevo"tu eras@mencionado"
     *  - Guard limit:Máximo de llamadas MAX_INVOCATION_PER_MEMBER por miembro,Ciclo anti-muertos
     *
     * @param chatId chat grupal id
     * @return Lista de respuestas de todos los agentes en esta ronda(guardado en DB)
     */

    suspend fun triggerAgentRoundRobin(
        chatId: String,
        /** v1.104: Llamado cuando cada agente comienza a hablar.(para visualización de interfaz de usuario"quien esta pensando") */
        onSpeakerChange: ((AssistantEntity) -> Unit)? = null,
        /** B5-02: El chat grupal genera un libro mayor id,Se utiliza para reproducir según el punto de interrupción después de que se finaliza el proceso. */
        ledgerId: String? = null,
        /** B5-02: Repetir la ronda inicial(round_robin hengwei 1). */
        startRound: Int = 1,
        /** B5-02: Reproducir el índice de miembros iniciales(0-based). */
        startMemberIndex: Int = 0,
    ): List<GroupChatMessageEntity> = withContext(Dispatchers.IO) {
        if (ProcessWriteGate.restoring) {
            Logger.w(TAG, "Copia de seguridad y restauración en curso, omita el chat grupal $chatId rotación")
            return@withContext emptyList()
        }
        // 1. Obtener configuración de chat grupal
        val chat = groupChatRepository.getChat(chatId)
        if (chat == null) {
            Logger.w(TAG, "chat grupal $chatId no existe,saltar la rotación")
            return@withContext emptyList()
        }

        // B5-02: Cargar chat grupal para generar libro de contabilidad(Usado durante la repetición)
        var ledger = if (ledgerId != null) {
            resultOf { groupChatRepository.getGenerationLedger(ledgerId) }.getOrNull()
        } else {
            null
        }
        // Modificación 1: Detección chat.teamId — Cuando un equipo está asociado y el equipo tiene un flujo de trabajo,confiado a TeamWorkflowExecutor
        // ejecución en paralelo/Condiciones/Orquestación de agregación(Los flujos de trabajo configurados por los usuarios en MultiAgentSettingsPage ya no son válidos).
        // teamId Cuando está vacío o el equipo no tiene flujo de trabajo,Mantener la lógica de rotación en serie existente(compatible con versiones anteriores).
        val teamId = chat.teamId
        if (teamId != null) {
            val multiAgentConfig = settings.multiAgentConfigCache
            val team = multiAgentConfig.teams.find { it.id == teamId }
            if (team != null && team.workflow != null) {
                Logger.i(TAG, "Chat grupal \"${chat.name}\"Equipo asociado\"${team.name}\"Y hay workflow,confiar TeamWorkflowExecutor")
                // Extraiga el texto de la tarea de los mensajes recientes de los usuarios(Se alinea con el mensaje de usuario guardado de launchRoundRobin existente)
                val recentForTask = groupChatRepository.getRecentMessages(chatId, DEFAULT_CONTEXT_SIZE)
                val userMessage = recentForTask.lastOrNull { it.senderType == "user" }?.body
                    ?.takeIf { it.isNotBlank() } ?: chat.name

                if (restoreBlocksWrites(chatId, "Flujo de trabajo del equipo")) {
                    return@withContext emptyList()
                }
                val workflowReplies = executeWithWorkflow(chat, chatId, team, userMessage)
                if (ledger != null) {
                    resultOf { groupChatRepository.deleteGenerationLedger(ledger.id) }
                        .onError { msg, t -> Logger.w(TAG, "Error al limpiar el libro mayor del chat grupal: $msg", t) }
                }
                return@withContext workflowReplies
            }
        }

        // v2.x: Distribución según modo de discusión
        when (chat.discussionMode) {
            "auto" -> return@withContext executeAutoDiscussion(chat, chatId, onSpeakerChange, ledger?.id, startRound, startMemberIndex)
            "debate" -> return@withContext executeDebate(chat, chatId, onSpeakerChange, ledger?.id, startMemberIndex)
            "host" -> return@withContext executeHostMode(chat, chatId, onSpeakerChange, ledger?.id, startMemberIndex)
            else -> { /* round_robin: Continuar usando la lógica de rotación en serie original */ }
        }

        val memberIds = groupChatRepository.parseMemberIds(chat)
        if (memberIds.isEmpty()) {
            Logger.w(TAG, "Chat grupal \"${chat.name}\"Ningún miembro,saltar la rotación")
            return@withContext emptyList()
        }

        // 2. Nombre para mostrar del miembro de análisis(Se utiliza para mensajes de chat grupal)
        // v2.x: Los miembros observadores no participan en la rotación predeterminada.(Mirar y discutir en silencio.)
        val observerIds = groupChatRepository.parseObserverIds(chat)
        val assistants = memberIds.mapNotNull { id ->
            resultOf { assistantRepository.getById(id) }.getOrNull()
                ?: run {
                    Logger.w(TAG, "Agent $id no existe,saltar")
                    null
                }
        }.filterNot { it.id in observerIds }
        if (assistants.isEmpty()) {
            Logger.w(TAG, "Chat grupal \"${chat.name}\"No hay ningún miembro hablante válido,saltar la rotación")
            return@withContext emptyList()
        }
        val memberNames = assistants.map { it.name }

        // v1.0.29: Ya no consigues lo global selectedModel — invokeAgent pase interno resolveAssistantModel
        // Analizar cada configuración auxiliar modelId/providerId,Implementar enrutamiento de modelo por asistente.

        // v1.97: 4. analizar @mention — Extraer menciones de mensajes de usuarios recientes agent
        val recentMessages = groupChatRepository.getRecentMessages(chatId, DEFAULT_CONTEXT_SIZE)
        val mentionedAgentIds = parseMentions(recentMessages, assistants)
        if (mentionedAgentIds.isNotEmpty()) {
            Logger.i(TAG, "Chat grupal \"${chat.name}\"@mencionar: ${mentionedAgentIds.joinToString(", ")}")
        }

        // v1.97: 5. Reorganizar el orden de los agentes — Mencionado primero,luego otros miembros

        val ledgerMemberIds = parseLedgerMemberIds(ledger)
        val orderedAssistants = if (ledgerMemberIds != null) {
            ledgerMemberIds.mapNotNull { id -> assistants.firstOrNull { it.id == id } }
        } else {
            assistants.sortedByDescending { it.id in mentionedAgentIds }
        }
        ledger = saveLedger(
            ledger, chatId, "round_robin", 1, startMemberIndex.coerceIn(0, orderedAssistants.size),
            memberIdsJson = groupChatRepository.serializeMemberIds(orderedAssistants.map { it.id }),
        )

        // ActivityHub: Borrar el estado de actividad restante de la ronda anterior.,Evita que el chip NO_REPLY/ERROR de la ronda anterior interfiera con la vista de esta ronda.
        activityHub.clear(chatId)

        val replies = mutableListOf<GroupChatMessageEntity>()

        // 6. disparador en serie para cada agent(Se mencionan Decision Fix)
        // v1.0.53: Agregar espacio entre agentes adyacentes,Cuota de RPM extendida(La API SenseTime tiene un límite actual más estricto,
        // Llamar continuamente a varios agentes hará que la cantidad de solicitudes por minuto se dispare. 429 implicitPass)

        val firstIndex = startMemberIndex.coerceIn(0, orderedAssistants.size)
        for (agentIndex in firstIndex until orderedAssistants.size) {
            // v2.x: control del director — Pausa Suspender/Avance rápido Verificar(Antes de ingresar al siguiente miembro)
            while (chatId in pausedRounds) {
                delay(300)
            }
            if (skipRoundRequests.remove(chatId)) {
                Logger.i(TAG, "Chat grupal \"${chat.name}\"Sáltate a los miembros restantes de esta ronda.(control del director)")
                break
            }
            if (agentIndex > 0) {
                delay(GROUP_CHAT_AGENT_INTERVAL_MS)
            }
            val assistant = orderedAssistants[agentIndex]
            val isMentioned = assistant.id in mentionedAgentIds
            // B5-02: Marcar miembro actual como procesando
            ledger = saveLedger(ledger, chatId, "round_robin", 1, agentIndex, status = "running")
            // B5-02: Anti-duplicación — Cuando el proceso finaliza después de ser eliminado y el miembro ha hablado,empujar hacia adelante directamente
            if (ledger != null && memberAlreadyRepliedSince(chatId, assistant.id, ledger.updatedAt)) {
                Logger.i(TAG, "Agent「${assistant.name}\"Ha hablado después del punto de quiebre,Saltar continuar corriendo")
                ledger = saveLedger(ledger, chatId, "round_robin", 1, agentIndex + 1, status = "running")
                continue
            }
            // v1.0.53 Phase 5: verificación de degradación — Saltar directamente si el miembro degradado no es @,no llamado LLM
            if (!isMentioned && isDemoted(chatId, assistant.id)) {
                Logger.i(TAG, "Agent「${assistant.name}\"Rebajado(Fallo continuo para tomar decisiones.),No mencionado por @,Saltar automáticamente esta ronda")
                activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.NO_REPLY)
                scheduleIdleTransition(chatId, assistant)
                ledger = saveLedger(ledger, chatId, "round_robin", 1, agentIndex + 1, status = "running")
                continue
            }
            // v1.104: Notifique a la interfaz de usuario a quién le toca hablar.
            onSpeakerChange?.invoke(assistant)
            // v2.x: Visualización de colas — Sincroniza a los miembros restantes para hablar.(Visualización de la barra del director"Próximo: A → B")
            _activeGroupGeneration.update {
                it?.copy(upcomingSpeakerNames = orderedAssistants.drop(agentIndex + 1).map { a -> a.name })
            }
            when (val result = invokeAgent(chat, chatId, assistant, memberNames, isMentioned = isMentioned)) {
                is AgentResult.Reply -> {
                    replies.add(result.message)
                    // v1.0.53 Phase 5: respuesta normal,Borrar recuento PASA consecutivo
                    recordReply(chatId, assistant.id)
                }
                is AgentResult.Pass -> {
                    // v1.0.53 Phase 5: grabar PASS(implicit=APILas faltas no se cuentan cuando se omiten pasivamente.),Bajar de categoría cuando se alcanza el umbral
                    recordPassAndMaybeDemote(chatId, assistant.id, implicit = result.implicit, assistantName = assistant.name)
                    // v1.97: reparación de decisiones — El agente mencionado si PASS,Inténtalo de nuevo
                    if (isMentioned) {
                        Logger.i(TAG, "Agent「${assistant.name}\"Estado@mencionar pero PASS,Reintento de reparación de decisión")
                        when (val retry = invokeAgent(chat, chatId, assistant, memberNames, isMentioned = true, isRepair = true)) {
                            is AgentResult.Reply -> {
                                replies.add(retry.message)
                                recordReply(chatId, assistant.id)
                            }
                            is AgentResult.Error -> Logger.w(TAG, "Agent「${assistant.name}\"La reparación de la decisión falló: ${retry.message}")
                            is AgentResult.Pass -> {
                                recordPassAndMaybeDemote(chatId, assistant.id, implicit = retry.implicit, assistantName = assistant.name)
                                Logger.i(TAG, "Agent「${assistant.name}\"La decisión de reparar aún está PASS")
                            }
                        }
                    }
                }
                is AgentResult.Error -> Logger.w(TAG, "Agent「${assistant.name}\"Excepcion de transmision: ${result.message}")
            }
            // B5-02: Procesamiento de miembro actual completado,Avanzar al siguiente miembro
            ledger = saveLedger(ledger, chatId, "round_robin", 1, agentIndex + 1, status = "running")
        }

        if (ledger != null) {
            resultOf { groupChatRepository.deleteGenerationLedger(ledger.id) }
                .onError { msg, t -> Logger.w(TAG, "Error al limpiar el libro mayor del chat grupal: $msg", t) }
        }

        Logger.i(TAG, "Chat grupal \"${chat.name}\"Esta ronda de rotación está completa,${replies.size}/${assistants.size} el agente habla")
        replies
    }

    /**
     * Transformación 1: delegar la ejecución del flujo de trabajo del equipo a TeamWorkflowExecutor.
     *
     * Cuando un chat grupal está asociado con un equipo([GroupChatEntity.teamId])Y el equipo está configurado. [AgentTeam.workflow] tiempo,
     * por [SkillExecutor.delegateAgent] ruta a [io.zer0.muse.tools.TeamWorkflowExecutor],
     * Por nodo de flujo de trabajo(SEQUENTIAL / PARALLEL / CONDITIONAL)ejecutar,y según la estrategia de agregación
     * (MERGE / VOTE / EXPERT_REVIEW / LLM_REVIEW)Resultados resumidos.
     *
     * Una vez completada la ejecución,Guardar resultados resumidos como un mensaje de chat grupal(Habla en nombre del equipo.).
     *
     * @param chat Entidad de chat grupal
     * @param chatId chat grupal id
     * @param team Equipos asociados(El flujo de trabajo confirmado no es null)
     * @param userMessage Texto del mensaje de usuario para esta ronda(Como tarea de equipo)
     * @return Lista que contiene mensajes de discursos individuales del equipo;Devuelve una lista vacía cuando la ejecución falla o no hay un resultado válido.
     */
    private suspend fun executeWithWorkflow(
        chat: GroupChatEntity,
        chatId: String,
        team: AgentTeam,
        userMessage: String,
    ): List<GroupChatMessageEntity> {
        if (restoreBlocksWrites(chatId, "Flujo de trabajo del equipo")) return emptyList()
        val requestId = "group-$chatId-${System.currentTimeMillis()}"
        val request = DelegationContract.DelegationRequest(
            requestId = requestId,
            task = userMessage,
            targetType = DelegationContract.DelegationRequest.TargetType.TEAM,
            targetId = team.id,
            parentSessionId = "group:$chatId",
        )

        val result = resultOf { skillExecutor.delegateAgent(request) }
            .onError { msg, t ->
                Logger.e(TAG, "Chat grupal \"${chat.name}\"Equipo\"${team.name}\"Error en la ejecución del flujo de trabajo: $msg", t)
            }.getOrNull()

        if (result == null || !result.success || result.resultText.isBlank()) {
            Logger.w(TAG, "Chat grupal \"${chat.name}\"Equipo\"${team.name}\"El flujo de trabajo no tiene resultados válidos: ${result?.error ?: "null"}")
            return emptyList()
        }

        // Guarde los resultados del resumen del flujo de trabajo como mensajes de chat grupal(Habla en nombre del equipo.)
        if (restoreBlocksWrites(chatId, "Resultados del flujo de trabajo del equipo")) return emptyList()
        val senderName = team.name.ifBlank { "equipo" }
        val msgId = groupChatRepository.sendMessage(
            chatId = chatId,
            senderType = "assistant",
            senderId = team.id,
            senderName = senderName,
            body = result.resultText,
        )
        // Reparación de auditoría (5.2) adaptarse: sendMessage Volver cuando se haya eliminado el chat grupal null(El mensaje no está en la base de datos.),
        // No se generará ningún mensaje de respuesta en este momento.,Evite tratar los identificadores colgantes como enviados.
        if (msgId == null) return emptyList()
        Logger.i(
            TAG,
            "Chat grupal \"${chat.name}\"Equipo\"${team.name}\"Flujo de trabajo completado,resultText longitud=${result.resultText.length}",
        )
        return listOf(
            GroupChatMessageEntity(
                id = msgId,
                chatId = chatId,
                senderType = "assistant",
                senderId = team.id,
                senderName = senderName,
                body = result.resultText,
                timestamp = System.currentTimeMillis(),
            ),
        )
    }

    // ════════════════════════════════════════════════════════════════
    // v2.x Modo de discusión de chat grupal — Auto / Debate / Host
    // ════════════════════════════════════════════════════════════════

    /**
     * Auto modo de discusión libre — AI diálogo continuo automático entre,No se requiere intervención del usuario.
     *
     * proceso:
     *  1. Después de que el usuario envía un mensaje,En la primera ronda, todos los miembros hablan por turnos.(Igual que round_robin)
     *  2. cada ronda posterior:Obtener mensajes recientes como contexto,Deja que cada agente vuelva a hablar
     *     - Cada agente ve los discursos de otros agentes en la ronda anterior.,Toma tu propia decisión de continuar
     *  3. Condición de terminación(Cualquier satisfacción):
     *     - alcanzar [GroupChatEntity.autoMaxRounds] límite superior
     *     - En una ronda determinada, todos los agentes PASS(Nadie tiene nada nuevo que decir.)
     *     - El usuario detiene o envía un mensaje nuevo manualmente(Cancelación externa)
     *
     * Diferencias con round_robin:round_robin Simplemente corre una ronda y luego detente.;Auto Continúe durante varias rondas hasta la convergencia o el exceso.
     *
     * @param chat Entidad de chat grupal
     * @param chatId chat grupal id
     * @param onSpeakerChange Llamado cuando cada agente comienza a hablar.
     * @return Lista de respuestas acumuladas de agentes para todas las rondas
     */

    private suspend fun executeAutoDiscussion(
        chat: GroupChatEntity,
        chatId: String,
        onSpeakerChange: ((AssistantEntity) -> Unit)?,
        ledgerId: String? = null,
        startRound: Int = 1,
        startMemberIndex: Int = 0,
    ): List<GroupChatMessageEntity> = withContext(Dispatchers.IO) {
        val memberIds = groupChatRepository.parseMemberIds(chat)
        if (memberIds.isEmpty()) {
            Logger.w(TAG, "Auto modo:Chat grupal \"${chat.name}\"Ningún miembro")
            return@withContext emptyList()
        }

        val assistants = memberIds.mapNotNull { id ->
            resultOf { assistantRepository.getById(id) }.getOrNull()
        }
        if (assistants.isEmpty()) {
            Logger.w(TAG, "Auto modo:Chat grupal \"${chat.name}\"No hay miembro válido")
            return@withContext emptyList()
        }

        val memberNames = assistants.map { it.name }
        val maxRounds = chat.autoMaxRounds.coerceAtLeast(1)

        // analizar @mention(Aún es necesario priorizar la primera ronda.@miembros de)
        val recentMessages = groupChatRepository.getRecentMessages(chatId, DEFAULT_CONTEXT_SIZE)
        val mentionedAgentIds = parseMentions(recentMessages, assistants)

        // B5-02: Cargar/Restaurar libro mayor,La lista de miembros ordenados grabada se hereda al reproducir
        var ledger = if (ledgerId != null) {
            resultOf { groupChatRepository.getGenerationLedger(ledgerId) }.getOrNull()
        } else {
            null
        }
        val ledgerMemberIds = parseLedgerMemberIds(ledger)
        val orderedAssistants = if (ledgerMemberIds != null) {
            ledgerMemberIds.mapNotNull { id -> assistants.firstOrNull { it.id == id } }
        } else {
            assistants.sortedByDescending { it.id in mentionedAgentIds }
        }
        ledger = saveLedger(
            ledger, chatId, "auto", startRound.coerceAtLeast(1), startMemberIndex.coerceIn(0, orderedAssistants.size),
            memberIdsJson = groupChatRepository.serializeMemberIds(orderedAssistants.map { it.id }),
        )

        activityHub.clear(chatId)
        val allReplies = mutableListOf<GroupChatMessageEntity>()
        val firstRound = startRound.coerceAtLeast(1)

        for (round in firstRound..maxRounds) {
            Logger.i(TAG, "Auto modo:Chat grupal \"${chat.name}\"No. $round/$maxRounds rueda")
            var roundReplyCount = 0
            val roundStartIndex = if (round == firstRound) startMemberIndex.coerceIn(0, orderedAssistants.size) else 0

            for (memberIndex in roundStartIndex until orderedAssistants.size) {
                val assistant = orderedAssistants[memberIndex]
                // Verifique cada ronda para ver si fue cancelada.(Detención de usuario/preferencia de mensaje nuevo)
                if (!chatGenerationManager.isStreaming(groupGenKey(chatId, GC_KEY_RR))) {
                    Logger.i(TAG, "Auto modo:Construcción cancelada,terminar en el no. $round rueda")
                    return@withContext allReplies
                }

                val isMentioned = assistant.id in mentionedAgentIds && round == 1
                // B5-02: Marcar al miembro actual como en proceso
                ledger = saveLedger(ledger, chatId, "auto", round, memberIndex, status = "running")
                if (ledger != null && memberAlreadyRepliedSince(chatId, assistant.id, ledger.updatedAt)) {
                    Logger.i(TAG, "Auto: Agent「${assistant.name}\"Ha hablado después del punto de quiebre,Saltar continuar corriendo")
                    ledger = saveLedger(ledger, chatId, "auto", round, memberIndex + 1, status = "running")
                    continue
                }

                onSpeakerChange?.invoke(assistant)

                val result = invokeAgent(
                    chat,
                    chatId,
                    assistant,
                    memberNames,
                    isMentioned = isMentioned,
                    isRepair = false,
                )
                when (result) {
                    is AgentResult.Reply -> {
                        allReplies.add(result.message)
                        roundReplyCount++
                    }
                    is AgentResult.Pass -> {
                        // primera ronda@pero PASS,Inténtalo de nuevo
                        if (isMentioned) {
                            val retry = invokeAgent(chat, chatId, assistant, memberNames, isMentioned = true, isRepair = true)
                            if (retry is AgentResult.Reply) {
                                allReplies.add(retry.message)
                                roundReplyCount++
                            }
                        }
                    }
                    is AgentResult.Error -> Logger.w(TAG, "Auto: Agent「${assistant.name}error: ${result.message}")
                }
                // B5-02: Procesamiento de miembro actual completado
                ledger = saveLedger(ledger, chatId, "auto", round, memberIndex + 1, status = "running")
            }

            // Nadie habla en esta ronda. → Discutir la convergencia,terminar
            if (roundReplyCount == 0) {
                Logger.i(TAG, "Auto modo:No. $round Ronda a todos PASS,Discutir la convergencia")
                break
            }
        }

        if (ledger != null) {
            resultOf { groupChatRepository.deleteGenerationLedger(ledger.id) }
                .onError { msg, t -> Logger.w(TAG, "Error al limpiar el libro mayor del chat grupal: $msg", t) }
        }

        Logger.i(TAG, "Auto modo:Chat grupal \"${chat.name}\"Discusión libre completada,total ${allReplies.size} Respuestas")
        allReplies
    }

    /**
     * modo debate — Orden de cadena fija,Cada agente debe responder al punto de vista del anterior.
     *
     * cadena:Preguntas de usuario → A dar un plan → B encontrar lagunas → C mejorar → (bucle)
     * El orden de los miembros está determinado por la lista de miembros.,El mensaje recibido por cada agente enfatizará:
     *  - Su papel en la cadena.(Sugerir soluciones/encontrar lagunas/mejoras)
     *  - Lo que dijo la persona anterior(Debe responder en consecuencia)
     *
     * Diferencias con round_robin:no permitido PASS(Cada personaje del debate debe hablar.),
     * Y el posicionamiento del rol y el discurso de la persona anterior se inyectan en el mensaje.
     *
     * @param chat Entidad de chat grupal
     * @param chatId chat grupal id
     * @param onSpeakerChange Llamado cuando cada agente comienza a hablar.
     * @return Lista de respuestas de todos los agentes en esta ronda
     */

    private suspend fun executeDebate(
        chat: GroupChatEntity,
        chatId: String,
        onSpeakerChange: ((AssistantEntity) -> Unit)?,
        ledgerId: String? = null,
        startMemberIndex: Int = 0,
    ): List<GroupChatMessageEntity> = withContext(Dispatchers.IO) {
        val memberIds = groupChatRepository.parseMemberIds(chat)
        if (memberIds.isEmpty()) {
            Logger.w(TAG, "modo debate:Chat grupal \"${chat.name}\"Ningún miembro")
            return@withContext emptyList()
        }

        val assistants = memberIds.mapNotNull { id ->
            resultOf { assistantRepository.getById(id) }.getOrNull()
        }
        if (assistants.size < 2) {
            Logger.w(TAG, "modo debate:Chat grupal \"${chat.name}\"Menos de 2 miembros,Recurrir a round_robin")
            return@withContext triggerRoundRobinFallback(chat, chatId, assistants, onSpeakerChange, ledgerId, startMemberIndex)
        }

        val memberNames = assistants.map { it.name }

        // B5-02: Cargar/Restaurar libro mayor
        var ledger = if (ledgerId != null) {
            resultOf { groupChatRepository.getGenerationLedger(ledgerId) }.getOrNull()
        } else {
            null
        }
        val ledgerMemberIds = parseLedgerMemberIds(ledger)
        val orderedAssistants = if (ledgerMemberIds != null) {
            ledgerMemberIds.mapNotNull { id -> assistants.firstOrNull { it.id == id } }
        } else {
            assistants
        }
        ledger = saveLedger(
            ledger, chatId, "debate", 1, startMemberIndex.coerceIn(0, orderedAssistants.size),
            memberIdsJson = groupChatRepository.serializeMemberIds(orderedAssistants.map { it.id }),
        )

        activityHub.clear(chatId)
        val replies = mutableListOf<GroupChatMessageEntity>()

        // papel de debate:Asignar por ubicación(Sugerencias/preguntas/mejoras/suplementos)
        val roles = generateDebateRoles(orderedAssistants.size)
        val firstIndex = startMemberIndex.coerceIn(0, orderedAssistants.size)

        for (index in firstIndex until orderedAssistants.size) {
            val assistant = orderedAssistants[index]
            if (!chatGenerationManager.isStreaming(groupGenKey(chatId, GC_KEY_RR))) {
                Logger.i(TAG, "modo debate:Construcción cancelada")
                return@withContext replies
            }

            val role = roles[index]
            // Revisión de corrección (2.0 S-01): La parte inferior del bolsillo también está filtrada por la visibilidad., Evitar los susurros(whisperTargetId=local_user)
            // Filtración a otros en el debate en forma de "última persona habló" agent; con el señor transcript(1891) El diámetro del filtro es el mismo.
            val previousReply = replies.lastOrNull()?.body
                ?: visibleMessagesFor(groupChatRepository.getRecentMessages(chatId, DEFAULT_CONTEXT_SIZE), assistant.id)
                    .lastOrNull { it.senderType == "assistant" }?.body

            // B5-02: Marcar al miembro actual como en proceso
            ledger = saveLedger(ledger, chatId, "debate", 1, index, status = "running")
            if (ledger != null && memberAlreadyRepliedSince(chatId, assistant.id, ledger.updatedAt)) {
                Logger.i(TAG, "debate: Agent「${assistant.name}\"Ha hablado después del punto de quiebre,Saltar continuar corriendo")
                ledger = saveLedger(ledger, chatId, "debate", 1, index + 1, status = "running")
                continue
            }

            onSpeakerChange?.invoke(assistant)

            val result = invokeAgentForDebate(
                chat,
                chatId,
                assistant,
                memberNames,
                role = role,
                speakerIndex = index,
                totalSpeakers = orderedAssistants.size,
                previousReply = previousReply,
            )
            when (result) {
                is AgentResult.Reply -> replies.add(result.message)
                is AgentResult.Pass -> {
                    // no permitido en el debate PASS,Intenta nuevamente enfatizar que debes hablar.
                    Logger.i(TAG, "debate:Agent「${assistant.name}\"Intenta PASS,Inténtalo de nuevo(No se permite saltarse los debates)")
                    val retry = invokeAgentForDebate(
                        chat, chatId, assistant, memberNames,
                        role = role,
                        speakerIndex = index,
                        totalSpeakers = orderedAssistants.size,
                        previousReply = previousReply,
                        isRepair = true,
                    )
                    if (retry is AgentResult.Reply) replies.add(retry.message)
                }
                is AgentResult.Error -> Logger.w(TAG, "debate:Agent「${assistant.name}error: ${result.message}")
            }
            // B5-02: Procesamiento de miembro actual completado
            ledger = saveLedger(ledger, chatId, "debate", 1, index + 1, status = "running")
        }

        if (ledger != null) {
            resultOf { groupChatRepository.deleteGenerationLedger(ledger.id) }
                .onError { msg, t -> Logger.w(TAG, "Error al limpiar el libro mayor del chat grupal: $msg", t) }
        }

        Logger.i(TAG, "modo debate:Chat grupal \"${chat.name}\"La cadena está completa,${replies.size} discurso")
        replies
    }

    /**
     * Modo de enrutamiento del moderador — La IA designada analiza el problema y envía tareas dinámicamente a otros miembros.
     *
     * proceso:
     *  1. La IA moderadora primero analiza el problema del usuario.,JSON de salida especificado:Qué miembros deben hablar, en qué orden y sus respectivas tareas
     *  2. Programador según las indicaciones del moderador,Llame a los miembros enviados en secuencia.
     *  3. El anfitrión ofrece opcionalmente un resumen al final.
     *
     * Si hostId no existe o falla el análisis del host,Recurrir a round_robin.
     *
     * @param chat Entidad de chat grupal
     * @param chatId chat grupal id
     * @param onSpeakerChange Llamado cuando cada agente comienza a hablar.
     * @return Lista de respuestas de todos los agentes en esta ronda
     */

    private suspend fun executeHostMode(
        chat: GroupChatEntity,
        chatId: String,
        onSpeakerChange: ((AssistantEntity) -> Unit)?,
        ledgerId: String? = null,
        startMemberIndex: Int = 0,
    ): List<GroupChatMessageEntity> = withContext(Dispatchers.IO) {
        val memberIds = groupChatRepository.parseMemberIds(chat)
        if (memberIds.isEmpty()) {
            Logger.w(TAG, "Modo moderador:Chat grupal \"${chat.name}\"Ningún miembro")
            return@withContext emptyList()
        }

        val assistants = memberIds.mapNotNull { id ->
            resultOf { assistantRepository.getById(id) }.getOrNull()
        }
        if (assistants.isEmpty()) {
            Logger.w(TAG, "Modo moderador:Chat grupal \"${chat.name}\"No hay miembro válido")
            return@withContext emptyList()
        }

        // Encuentra el anfitrión
        val host = assistants.find { it.id == chat.hostId }
        if (host == null) {
            Logger.w(TAG, "Modo moderador:hostId=${chat.hostId} no entre los miembros,revertir round_robin")
            return@withContext triggerRoundRobinFallback(chat, chatId, assistants, onSpeakerChange, ledgerId, startMemberIndex)
        }

        val otherMembers = assistants.filter { it.id != host.id }
        if (otherMembers.isEmpty()) {
            Logger.w(TAG, "Modo moderador:Ningún otro miembro excepto el anfitrión.,revertir round_robin")
            return@withContext triggerRoundRobinFallback(chat, chatId, assistants, onSpeakerChange, ledgerId, startMemberIndex)
        }

        val memberNames = assistants.map { it.name }

        // B5-02: Cargar/Restaurar libro mayor,El plan de distribución registrado continuará directamente.,No más análisis repetidos
        var ledger = if (ledgerId != null) {
            resultOf { groupChatRepository.getGenerationLedger(ledgerId) }.getOrNull()
        } else {
            null
        }

        activityHub.clear(chatId)

        val dispatchPlan: List<AssistantEntity>
        val ledgerMemberIds = parseLedgerMemberIds(ledger)
        if (ledgerMemberIds != null) {
            dispatchPlan = ledgerMemberIds.mapNotNull { id -> otherMembers.firstOrNull { it.id == id } }
            if (dispatchPlan.isEmpty()) {
                Logger.w(TAG, "Modo moderador:El plan de distribución del libro mayor no es válido,revertir round_robin")
                return@withContext triggerRoundRobinFallback(chat, chatId, assistants, onSpeakerChange, ledgerId, startMemberIndex)
            }
        } else {
            // 1. Moderador analiza los problemas de los usuarios.,Plan de distribución de salida
            val recentMessages = groupChatRepository.getRecentMessages(chatId, DEFAULT_CONTEXT_SIZE)
            val lastUserMsg = recentMessages.lastOrNull { it.senderType == "user" }?.body ?: chat.name
            onSpeakerChange?.invoke(host)
            val plan = analyzeWithHost(chat, host, otherMembers, memberNames, lastUserMsg)
            if (plan.isEmpty()) {
                Logger.w(TAG, "Modo moderador:El anfitrión no proporcionó un plan de distribución válido.,revertir round_robin")
                return@withContext triggerRoundRobinFallback(chat, chatId, assistants, onSpeakerChange, ledgerId, startMemberIndex)
            }
            dispatchPlan = plan
            ledger = saveLedger(
                ledger, chatId, "host", 1, startMemberIndex.coerceIn(0, dispatchPlan.size),
                memberIdsJson = groupChatRepository.serializeMemberIds(dispatchPlan.map { it.id }),
            )
        }

        Logger.i(TAG, "Modo moderador:Anfitrión \"${host.name}\"Distribuir ${dispatchPlan.size} miembros: ${dispatchPlan.joinToString { it.name }}")

        // 2. Llame a los miembros enviados en secuencia de acuerdo con las instrucciones del anfitrión.
        val replies = mutableListOf<GroupChatMessageEntity>()
        val firstIndex = startMemberIndex.coerceIn(0, dispatchPlan.size)
        for (index in firstIndex until dispatchPlan.size) {
            val member = dispatchPlan[index]
            if (!chatGenerationManager.isStreaming(groupGenKey(chatId, GC_KEY_RR))) {
                Logger.i(TAG, "Modo moderador:Construcción cancelada")
                return@withContext replies
            }
            // B5-02: Marcar al miembro actual como en proceso
            ledger = saveLedger(ledger, chatId, "host", 1, index, status = "running")
            if (ledger != null && memberAlreadyRepliedSince(chatId, member.id, ledger.updatedAt)) {
                Logger.i(TAG, "Modo moderador:Agent「${member.name}\"Ha hablado después del punto de quiebre,Saltar continuar corriendo")
                ledger = saveLedger(ledger, chatId, "host", 1, index + 1, status = "running")
                continue
            }

            onSpeakerChange?.invoke(member)
            val result = invokeAgent(chat, chatId, member, memberNames, isMentioned = false)
            if (result is AgentResult.Reply) replies.add(result.message)
            // B5-02: Procesamiento de miembro actual completado
            ledger = saveLedger(ledger, chatId, "host", 1, index + 1, status = "running")
        }

        if (ledger != null) {
            resultOf { groupChatRepository.deleteGenerationLedger(ledger.id) }
                .onError { msg, t -> Logger.w(TAG, "Error al limpiar el libro mayor del chat grupal: $msg", t) }
        }

        Logger.i(TAG, "Modo moderador:Chat grupal \"${chat.name}\"Distribución completada,${replies.size} Respuestas")
        replies
    }

    /**
     * modo debate:Generar una lista de posicionamiento de roles en función del número de miembros.
     *
     * - 2 gente:Cuadrado / Cola
     * - 3 gente:Proponer soluciones/encontrar lagunas/promover mejoras
     * - 4+ gente:Sugerencias/preguntas/mejoras/suplementos(bucle)
     *
     * v1.0.74 fix: Anteriormente la versión privada era directamente return generateDebateRoles(count) recursividad infinita
     * (Seguimiento de miembros de la clase companion,Igual que el antiguo pozo ProactiveMessageRunner.isInAllowedWindow),
     * Se produce un StackOverflowError tan pronto como se activa el modo de debate. En su lugar, delega explícitamente a la implementación complementaria.
     */
    private fun generateDebateRoles(count: Int): List<String> {
        return Companion.generateDebateRoles(count)
    }

    /**
     * modo debate:Llame a un solo agente para generar un discurso.(Con posicionamiento de roles + habla la persona anterior).
     *
     * con [invokeAgent] la diferencia:prompt Inyecte roles de debate y la persona anterior habla,
     * y enfatizar que no está permitido PASS(Cada personaje del debate debe hablar.).
     * v1.0.29: Eliminar parámetro del modelo,pase interno [resolveAssistantModel] Modelo exclusivo de asistente de análisis.
     */
    private suspend fun invokeAgentForDebate(
        chat: GroupChatEntity,
        chatId: String,
        assistant: AssistantEntity,
        memberNames: List<String>,
        role: String,
        speakerIndex: Int,
        totalSpeakers: Int,
        previousReply: String?,
        isRepair: Boolean = false,
    ): AgentResult {
        activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.VIEWING)

        // v1.0.29: per-assistant Análisis de modelos
        val (model, providerConfig) = resolveAssistantModel(assistant)

        val contextSize = assistant.contextMessageSize.takeIf { it > 0 } ?: DEFAULT_CONTEXT_SIZE
        val recentMessages = groupChatRepository.getRecentMessages(chatId, contextSize)

        val messages = buildDebateMessages(
            chat.name, chatId, assistant, memberNames, recentMessages,
            role, speakerIndex, totalSpeakers, previousReply, isRepair,
        )

        val temperature = assistant.temperature ?: DEFAULT_TEMPERATURE
        val maxTokens = assistant.maxTokens ?: DEFAULT_MAX_TOKENS
        activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.REPLYING)

        val rawReplyText = resultOf {
            withTimeoutOrNull(AGENT_TIMEOUT_MS) {
                // A-13: Debate también sobre el limitador actual(con mensajería proactiva/Límite de simultaneidad compartida de la rueda de toma de decisiones del chat grupal)
                GenerationGate.withPermit {
                    val builder = StringBuilder()
                    // v1.x: proceso de pensamiento de flujo acumulativo(reasoning_content),Permita que los mensajes de chat grupal también muestren bloques de pensamiento profundo
                    val reasoningBuilder = StringBuilder()
                    var streamError: String? = null
                    chatService.streamChat(
                        messages = messages,
                        model = model,
                        temperature = temperature,
                        maxTokens = maxTokens,
                        providerConfig = providerConfig,
                    ).collect { event ->
                        when (event) {
                            is ChatStreamEvent.ContentDelta -> {
                                builder.append(event.delta)
                                // v1.x: Salida de transmisión de chat grupal — Empuje hacia en tiempo real UI
                                activityHub.updateStreamingContent(chatId, builder.toString())
                            }
                            is ChatStreamEvent.ReasoningDelta -> reasoningBuilder.append(event.delta)
                            is ChatStreamEvent.ImageDelta -> {}
                            is ChatStreamEvent.ToolCallDelta -> {}
                            is ChatStreamEvent.Done -> {}
                            is ChatStreamEvent.Error -> streamError = event.message
                            is ChatStreamEvent.StreamInterrupted -> streamError = event.message
                            is ChatStreamEvent.FallbackNotice -> {}
                            // A5: La ruta del chat grupal no muestra el uso de tokens,ignorar
                            is ChatStreamEvent.UsageDelta -> {}
                            is ChatStreamEvent.CitationDelta -> {}
                        }
                    }
                    if (streamError != null) throw IllegalStateException(streamError)
                    builder.toString().trim() to reasoningBuilder.toString().trim()
                }
            }
        }.onError { msg, _ ->
            activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.ERROR)
            scheduleIdleTransition(chatId, assistant)
            return AgentResult.Error(msg)
        }.getOrNull()

        if (rawReplyText == null) {
            activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.ERROR)
            scheduleIdleTransition(chatId, assistant)
            return AgentResult.Error("Agent「${assistant.name}\"Tiempo de espera para la llamada al debate")
        }

        val extractedMood = extractMood(rawReplyText.first)
        // v1.x: Priorizar el proceso de pensamiento de acumulación de flujo.(El modelo Reasoning_content no toma el texto final.,
        // extractReasoning Sólo se puede extraer texto <think> etiqueta);ambos estan vacios null
        val replyPair = rawReplyText
        val extractedReasoning = replyPair.second.ifBlank {
            extractReasoning(replyPair.first) ?: ""
        }.ifBlank { null }
        val replyText = sanitizeAgentReply(rawReplyText.first)

        if (replyText.isBlank() || replyText == PASS_MARKER) {
            activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.NO_REPLY)
            scheduleIdleTransition(chatId, assistant)
            return AgentResult.Pass()
        }

        if (restoreBlocksWrites(chatId, "respuesta al debate")) {
            return AgentResult.Error("La restauración de la copia de seguridad está en curso, se ha omitido la escritura de mensajes de chat grupal")
        }
        val msgId = groupChatRepository.sendMessage(
            chatId = chatId,
            senderType = "assistant",
            senderId = assistant.id,
            senderName = assistant.name,
            body = replyText,
            mood = extractedMood,
            reasoning = extractedReasoning,
        )
        // Reparación de auditoría (5.2) adaptarse: sendMessage Volver cuando se haya eliminado el chat grupal null(El mensaje no está en la base de datos.),Sáltate esta ronda.
        // B-21: No entrar a la biblioteca ya no es silencioso Pass — Recuerda ERROR y regresa Error,Que la persona que llama perciba la pérdida del habla.
        // (Anteriormente, Pass se trataría como"Saltar activamente",El usuario nunca verá el discurso del agente y no recibirá ningún mensaje.).
        if (msgId == null) {
            Logger.e(TAG, "No se pudo guardar el discurso del chat grupal(chatId=$chatId, agent=${assistant.name}),Mensaje perdido")
            return AgentResult.Error("No se pudo guardar el mensaje de chat grupal,Declaración perdida")
        }
        // v1.x: Se ha eliminado el contenido en streaming.,Borrar salida temporal de UI
        activityHub.clearStreamingContent(chatId)
        scheduleIdleTransition(chatId, assistant)

        if (groupChatMemoryRepository != null && !restoreBlocksWrites(chatId, "Memoria de chat grupal")) {
            val summary = buildGroupChatMemorySummary(chat.name, assistant, replyText)
            resultOf { groupChatMemoryRepository.saveSummary(chatId, assistant.id, summary) }
        }
        return AgentResult.Reply(
            GroupChatMessageEntity(
                id = msgId,
                chatId = chatId,
                senderType = "assistant",
                senderId = assistant.id,
                senderName = assistant.name,
                body = replyText,
                timestamp = System.currentTimeMillis(),
                mood = extractedMood,
                reasoning = extractedReasoning,
            ),
        )
    }

    /**
     * modo debate:Construir una lista de mensajes enviada a LLM(Incluyendo posicionamiento de roles + persona anterior hablando).
     */
    private suspend fun buildDebateMessages(
        chatName: String,
        chatId: String,
        assistant: AssistantEntity,
        memberNames: List<String>,
        recentMessages: List<GroupChatMessageEntity>,
        role: String,
        speakerIndex: Int,
        totalSpeakers: Int,
        previousReply: String?,
        isRepair: Boolean,
    ): List<UIMessage> {
        val systemContent = buildString {
            if (assistant.systemPrompt.isNotBlank()) {
                appendLine(assistant.systemPrompt)
                appendLine()
            }
            appendLine(
                SystemPromptAssembler.buildGroupChatHintSection(
                    chatName = chatName,
                    members = memberNames,
                    currentAgentName = assistant.name,
                ),
            )
            appendLine()
            appendLine(groupChatMoodSection())

            // v1.0.53: Inyectar memoria a largo plazo(Retrato de usuario)Chatear recuerdos con grupo.(agent Discursos pasados)
            // Reparación"La memoria del chat grupal y otras memorias locales no son interoperables"pregunta
            systemPromptAssembler?.let { assembler ->
                if (
                    AssistantMemoryAccessPolicy.canInjectAssistantFacts(
                        assistantId = assistant.id,
                        useGlobalMemory = assistant.useGlobalMemory,
                        memoryEnabled = assistant.memoryEnabled && settings.isMemoryEnabled(),
                        forSubagent = false,
                        ignoreMemory = false,
                    )
                ) {
                    val longTermMemory =
                        assembler.buildLongTermMemorySection(
                            assistantId = assistant.id,
                            scope = if (assistant.id == "default") "main" else assistant.id,
                            spaceId = settings.currentSpaceIdFlow.first().ifBlank { "default" },
                        )
                    if (longTermMemory.isNotBlank()) {
                        appendLine()
                        appendLine(longTermMemory)
                    }
                }
                val groupChatMemory = assembler.buildGroupChatMemorySection(assistant.id, chatId)
                if (groupChatMemory.isNotBlank()) {
                    appendLine()
                    appendLine(groupChatMemory)
                }
                // v2.x: Guía de uso de emoticonos(El chat grupal y el chat individual están en el mismo proceso;La biblioteca esta vacia/Cadena vacía cuando el interruptor está apagado)
                val stickerGuide = assembler.buildStickerGuideSection()
                if (stickerGuide.isNotBlank()) {
                    appendLine()
                    appendLine(stickerGuide)
                }
            }
            appendLine()
            appendLine("[Modo Debate] Estás participando en un debate estructurado.")
            appendLine("Tu papel en esta cadena:$role(No. ${speakerIndex + 1}/$totalSpeakers discurso)")
            appendLine("Reglas del debate:")
            appendLine("- todos deben hablar,no permitido [PASS]")
            appendLine("- Debe responder a la declaración de la persona anterior.(De acuerdo/objeto/suplemento/mejorar)")
            appendLine("- Mantén tu papel:El proponente da un plan específico.,Los escépticos encuentran lagunas en la lógica,Los mejoradores hacen sugerencias de optimización")
            if (isRepair) {
                appendLine("- No hablaste en la última ronda.,No se permite saltarse durante el debate,Por favor asegúrese de responder")
            }
        }

        val messages = mutableListOf<UIMessage>()
        messages.add(UIMessage(role = MessageRole.SYSTEM, content = systemContent))

        // User message:Contiene transcripción de noticias recientes + énfasis de la persona anterior.
        val userContent = buildString {
            appendLine("${assistant.name},Estas participando en$chatName”debate.")
            appendLine()
            if (recentMessages.isNotEmpty()) {
                appendLine("【Registro de discusión】")
                // Reparación de auditoría (1.6): Los debates también filtran los susurros,No revelar mensajes privados
                appendLine(formatMessageTranscript(visibleMessagesFor(recentMessages, assistant.id)))
                appendLine()
            }
            if (previousReply != null) {
                appendLine("[La persona anterior habló] Por favor responda al siguiente contenido:")
                appendLine(previousReply.take(800))
                appendLine()
            } else {
                appendLine("eres el primer orador,Proporcione su solución según el problema del usuario.")
                appendLine()
            }
            appendLine("Por favor use \"$role”perspectiva. debe responder,no permitido [PASS].")
        }
        messages.add(UIMessage(role = MessageRole.USER, content = userContent))
        return messages
    }

    /**
     * Modo moderador:Permita que la IA del moderador analice los problemas de los usuarios,Genere el plan de despacho.
     *
     * Después de que el moderador reciba la pregunta del usuario.,Generar una lista de despacho simple(Un nombre de miembro por línea),
     * El planificador determina qué miembros hablarán y en qué orden.
     * v1.0.29: Eliminar parámetro del modelo,pase interno [resolveAssistantModel] Analice el modelo específico del host.
     *
     * @return Lista de entidades asistentes que se están enviando(En el orden especificado por el anfitrión.);Devuelve una lista vacía en caso de error.
     */
    private suspend fun analyzeWithHost(
        chat: GroupChatEntity,
        host: AssistantEntity,
        otherMembers: List<AssistantEntity>,
        memberNames: List<String>,
        userMessage: String,
    ): List<AssistantEntity> {
        // v1.0.29: per-assistant Análisis de modelos(Modelo configurado con moderador)
        val (model, providerConfig) = resolveAssistantModel(host)

        val systemContent = buildString {
            if (host.systemPrompt.isNotBlank()) {
                appendLine(host.systemPrompt)
                appendLine()
            }
            appendLine("Eres un chat grupal \"${chat.name}”anfitrión.")
            appendLine("miembros del grupo:${otherMembers.joinToString(",") { it.name }}")
            appendLine()
            appendLine("Tu trabajo es analizar el problema del usuario.,Decida qué miembros deben hablar y en qué orden.")
            appendLine("Por favor ingrese solo el nombre del miembro que necesita hablar,uno por línea,Organice el orden de intervención.")
            appendLine("No genere nada más. Si no esta claro,Imprima todos los nombres de los miembros.")
        }

        val userContent = buildString {
            appendLine("Problemas de usuario:$userMessage")
            appendLine()
            appendLine("Miembros opcionales:")
            otherMembers.forEach { appendLine(it.name) }
            appendLine()
            appendLine("Por favor indique los nombres de los miembros que deberían hablar.(uno por línea):")
        }

        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = systemContent),
            UIMessage(role = MessageRole.USER, content = userContent),
        )

        val hostReply = resultOf {
            withTimeoutOrNull(AGENT_TIMEOUT_MS) {
                // A-13: La rueda de distribución del anfitrión también pasó la puerta límite actual.(con mensajería proactiva/Límite de simultaneidad compartida de la rueda de toma de decisiones del chat grupal)
                GenerationGate.withPermit {
                    val builder = StringBuilder()
                    chatService.streamChat(
                        messages = messages,
                        model = model,
                        temperature = 0.3f,
                        maxTokens = 200,
                        providerConfig = providerConfig,
                    ).collect { event ->
                        when (event) {
                            is ChatStreamEvent.ContentDelta -> builder.append(event.delta)
                            else -> {}
                        }
                    }
                    builder.toString().trim()
                }
            }
        }.getOrNull()

        if (hostReply.isNullOrBlank()) {
            Logger.w(TAG, "Modo moderador:Anfitrión \"${host.name}\"No se dio ninguna respuesta válida")
            return emptyList()
        }

        // Analizar el nombre del miembro generado por el moderador(uno por línea,coincidencia difusa)
        val requestedNames = hostReply.lines()
            .map { it.trim().removePrefix("-").removePrefix("•").trim() }
            .filter { it.isNotBlank() }

        val dispatched = mutableListOf<AssistantEntity>()
        for (name in requestedNames) {
            val matched = otherMembers.firstOrNull { member ->
                member.name.equals(name, ignoreCase = true) ||
                    member.name.contains(name, ignoreCase = true) ||
                    name.contains(member.name, ignoreCase = true)
            }
            if (matched != null && matched !in dispatched) {
                dispatched.add(matched)
            }
        }

        // Si el anfitrión no ha enviado a nadie efectivamente,Enviar a todos los miembros de forma predeterminada
        if (dispatched.isEmpty()) {
            Logger.w(TAG, "Modo moderador:Salida del moderador \"$hostReply》Ningún miembro fue emparejado,Enviar todo por defecto")
            return otherMembers
        }

        return dispatched
    }

    /**
     * Método alternativo:Cuando no se cumplen las condiciones del nuevo modo,Realiza rotación en serie estándar.
     */

    private suspend fun triggerRoundRobinFallback(
        chat: GroupChatEntity,
        chatId: String,
        assistants: List<AssistantEntity>,
        onSpeakerChange: ((AssistantEntity) -> Unit)?,
        ledgerId: String? = null,
        startMemberIndex: Int = 0,
    ): List<GroupChatMessageEntity> = withContext(Dispatchers.IO) {
        if (assistants.isEmpty()) return@withContext emptyList()
        val memberNames = assistants.map { it.name }
        val recentMessages = groupChatRepository.getRecentMessages(chatId, DEFAULT_CONTEXT_SIZE)
        val mentionedAgentIds = parseMentions(recentMessages, assistants)

        // B5-02: Cargar/Restaurar libro mayor
        var ledger = if (ledgerId != null) {
            resultOf { groupChatRepository.getGenerationLedger(ledgerId) }.getOrNull()
        } else {
            null
        }
        val ledgerMemberIds = parseLedgerMemberIds(ledger)
        val orderedAssistants = if (ledgerMemberIds != null) {
            ledgerMemberIds.mapNotNull { id -> assistants.firstOrNull { it.id == id } }
        } else {
            assistants.sortedByDescending { it.id in mentionedAgentIds }
        }
        ledger = saveLedger(
            ledger, chatId, "round_robin", 1, startMemberIndex.coerceIn(0, orderedAssistants.size),
            memberIdsJson = groupChatRepository.serializeMemberIds(orderedAssistants.map { it.id }),
        )

        activityHub.clear(chatId)
        val replies = mutableListOf<GroupChatMessageEntity>()
        val firstIndex = startMemberIndex.coerceIn(0, orderedAssistants.size)
        for (agentIndex in firstIndex until orderedAssistants.size) {
            val assistant = orderedAssistants[agentIndex]
            val isMentioned = assistant.id in mentionedAgentIds
            // B5-02: Marcar al miembro actual como en proceso
            ledger = saveLedger(ledger, chatId, "round_robin", 1, agentIndex, status = "running")
            if (ledger != null && memberAlreadyRepliedSince(chatId, assistant.id, ledger.updatedAt)) {
                Logger.i(TAG, "Agent「${assistant.name}\"Ha hablado después del punto de quiebre,Saltar continuar corriendo")
                ledger = saveLedger(ledger, chatId, "round_robin", 1, agentIndex + 1, status = "running")
                continue
            }

            onSpeakerChange?.invoke(assistant)
            when (val result = invokeAgent(chat, chatId, assistant, memberNames, isMentioned = isMentioned)) {
                is AgentResult.Reply -> replies.add(result.message)
                is AgentResult.Pass -> {
                    if (isMentioned) {
                        when (val retry = invokeAgent(chat, chatId, assistant, memberNames, isMentioned = true, isRepair = true)) {
                            is AgentResult.Reply -> replies.add(retry.message)
                            else -> {}
                        }
                    }
                }
                is AgentResult.Error -> Logger.w(TAG, "Agent「${assistant.name}error: ${result.message}")
            }
            // B5-02: Procesamiento de miembro actual completado
            ledger = saveLedger(ledger, chatId, "round_robin", 1, agentIndex + 1, status = "running")
        }

        if (ledger != null) {
            resultOf { groupChatRepository.deleteGenerationLedger(ledger.id) }
                .onError { msg, t -> Logger.w(TAG, "Error al limpiar el libro mayor del chat grupal: $msg", t) }
        }

        replies
    }

    /**
     * v1.97: Analizar de mensajes recientes @mention,Devuelve una lista de los identificadores de asistente mencionados.
     *
     * Reglas coincidentes:Buscar en mensajes recientes de usuarios @name,name Coincide con asistente.nombre o asistente.id.
     * Considere los límites de puntuación en chino e inglés al hacer coincidir,Orden descendente por longitud del nombre.
     */
    private fun parseMentions(recentMessages: List<GroupChatMessageEntity>, assistants: List<AssistantEntity>): Set<String> {
        // Obtenga el último mensaje de usuario
        val lastUserMsg = recentMessages.lastOrNull { it.senderType == "user" } ?: return emptySet()
        val text = lastUserMsg.body
        if (!text.contains("@")) return emptySet()

        // Extraer todo @token
        val tokens = MENTION_REGEX.findAll(text).map { it.groupValues[1] }.toList()
        if (tokens.isEmpty()) return emptySet()

        // Coincidir con el nombre/id del asistente(ignorar caso)
        // v1.116 (C2-1): Ordene por longitud del nombre en orden descendente antes de hacer coincidir,Evite que los nombres cortos no coincidan con el prefijo de los nombres largos.
        // Ejemplo:hay en el grupo "Alice" y "Alice2" tiempo,@Alice2 Debería coincidir con este último en lugar de con el primero.
        // Actualmente usando iguales para una coincidencia exacta,La clasificación no afecta los resultados.,pero para una futura expansión(Alias/coincidencia parcial)Sentar las bases.
        val sortedAssistants = assistants.sortedByDescending { it.name.length }
        val mentioned = mutableSetOf<String>()
        for (token in tokens) {
            val matched = sortedAssistants.firstOrNull { a ->
                a.name.equals(token, ignoreCase = true) || a.id.equals(token, ignoreCase = true)
            }
            if (matched != null) {
                mentioned.add(matched.id)
            }
        }
        return mentioned
    }

    /**
     * v2.x (B3): Anfitrión de captura de medios de chat grupal — poner generate_image / generate_qr_code / generate_video Los productos se incluyen en el archivo adjunto de esta ronda de información.
     *
     * Pasado dentro del alcance de la rutina local ejecutada por la herramienta. [MediaGenHostContext] inyectar:Generar gráfico
     * ([MediaGenResult.Success.imageUrls])Escribir archivo adjunto imageBase64Json;Generar vídeo
     * ([MediaGenResult.Success.videoFileUri])Escribir mensaje en la columna videoFileUri
     * (v2.x Restan los últimos toques,Renderizado por AssistantVideoCard).
     * Siempre devuelve un identificador de entrega no nulo.,Haga funcionar las herramientas multimedia"Se muestra en la conversación"Ramificar en lugar de degenerar para devolver rutas de archivos.
     */
    private class GroupChatMediaCaptureHost(
        private val onImages: (List<String>) -> Unit,
        private val onVideo: (String) -> Unit,
    ) : MediaGenHost {
        override suspend fun begin(kind: MediaGenKind): MediaGenSlot = object : MediaGenSlot {
            override fun isActive(): Boolean = true
            override suspend fun onProgress(elapsedSeconds: Int) = Unit
            override suspend fun report(result: MediaGenResult) {
                if (result is MediaGenResult.Success) {
                    if (result.imageUrls.isNotEmpty()) onImages(result.imageUrls)
                    result.videoFileUri?.takeIf { it.isNotBlank() }?.let(onVideo)
                }
            }
        }

        override suspend fun finish(kind: MediaGenKind) = Unit
    }

    /**
     * v2.x (B3): Archivos adjuntos de mensajes de chat grupal unificados — renderizador(MessageImageGrid/SmartImage)Sólo reconocer
     * data:image/ prefijo,http(s) con ruta de archivo;Los proveedores individuales devuelven base64 básica al agregar el prefijo URI de datos.
     */
    private fun normalizeGroupChatMediaRef(raw: String): String = when {
        raw.startsWith("data:") || raw.startsWith("http://") || raw.startsWith("https://") ||
            raw.startsWith("file:") || raw.startsWith("/") -> raw
        else -> "data:image/png;base64,$raw"
    }

    /**
     * El resultado de una ronda de invocación de un solo agente.
     *
     * usado para distinguir"Toma la iniciativa PASS","respuesta normal"con"Excepción de transmisión",evitar [triggerAgentRoundRobin]
     * El error de cálculo de la excepción de transmisión como PASS activo desencadena un reintento de reparación de decisión.
     */
    private sealed class AgentResult {
        /**
         * Sáltate esta ronda.
         *
         * @param implicit v1.0.53: true = API fracaso/Salto pasivo por tiempo de espera(No participa en el conteo de degradación),
         *                 false = Modelo activo channel_pass(salto explícito,Participar en el conteo de degradación).
         */
        data class Pass(val implicit: Boolean = false) : AgentResult()

        /** Respondió un mensaje normalmente. */
        data class Reply(val message: GroupChatMessageEntity) : AgentResult()

        /** La llamada de transmisión falló o se agotó el tiempo de espera,No se puede responder. */
        data class Error(val message: String) : AgentResult()
    }

    /**
     * v1.0.29: per-assistant Análisis de modelos — Deje que cada asistente en el chat grupal use su propio modelo configurado.
     *
     * orden de análisis(Chat único de referencia [ChatStreamCoordinator.resolveToolsAndModel]):
     *  1. El asistente está configurado. `modelId + providerId`:coincidencia exacta Provider+Model
     *  2. El asistente solo configura `modelId`(Ninguno providerId):Encuentre una identificación coincidente en todos los proveedores Model
     *  3. Asistente no configurado:Recurrir al correspondiente ModelId global seleccionado Model
     *  4. Global tampoco está seleccionado:Volver al primer modelo del proveedor activado,Luego recurra al primer modelo de Proveedor con un modelo
     *
     * ProviderConfig analizar:
     *  - Priorice el uso de model.providerId para encontrar el correspondiente Provider
     *  - Volver a la activación cuando no se encuentra Provider(y debe tener un modelo),Luego vuelve al primero con un modelo. Provider
     *
     * Esto asegura:
     *  - Chat grupal Un asistente usa OpenCode DeepSeek-V4, B Asistente para SiliconFlow GLM-4-9B,
     *    Las respectivas llamadas de StreamChat se enrutan a diferentes proveedores.
     *  - El asistente es consistente con el comportamiento histórico cuando no está configurado(Usar global selectedModel).
     *
     * @param assistant actual agent
     * @return (Model, ProviderConfig) — Model Cuando es nulo, ChatService asume el control.;
     *         ProviderConfig Cuando es nulo, ChatService llamará configStore.get() Ten todo en cuenta
     */
    private suspend fun resolveAssistantModel(assistant: AssistantEntity): Pair<Model?, ProviderConfig?> {
        val allProviders = resultOf { settings.providersFlow.first() }.getOrNull().orEmpty()
        if (allProviders.isEmpty()) return Pair(null, null)

        val assistantModelId = assistant.modelId?.takeIf { it.isNotBlank() }
        val assistantProviderId = assistant.providerId?.takeIf { it.isNotBlank() }
        val activeProviderId = resultOf { settings.activeProviderIdFlow.first() }.getOrNull()
        val selectedModelId = resultOf { settings.selectedModelIdFlow.first() }.getOrNull()

        // 1. analizar Model
        val resolvedModel: Model? = if (assistantModelId != null && assistantProviderId != null) {
            // coincidencia exacta:provider + model id
            allProviders.firstOrNull { it.id == assistantProviderId }
                ?.models?.firstOrNull { it.id == assistantModelId }
        } else {
            // Solo modelId: buscar en todos los proveedores
            assistantModelId?.let { aid ->
                allProviders.flatMap { it.models }.firstOrNull { it.id == aid }
            }
        } ?: selectedModelId?.let { sid ->
            // Regresar a lo global selectedModelId
            allProviders.flatMap { it.models }.firstOrNull { it.id == sid }
        } ?: allProviders.firstOrNull { it.id == activeProviderId && it.models.isNotEmpty() }?.let { p ->
            // Volver al primer modelo del proveedor activado
            p.models.firstOrNull()
        } ?: allProviders.firstOrNull { it.models.isNotEmpty() }?.let { p ->
            // Cobertura de segundo nivel:El primer Proveedor con un modelo El primer modelo
            p.models.firstOrNull()
        }

        // 2. analizar ProviderConfig
        val resolvedProviderConfig: ProviderConfig? = resolvedModel?.let { m ->
            allProviders.firstOrNull { it.id == m.providerId }
        } ?: allProviders.firstOrNull { it.id == activeProviderId && it.models.isNotEmpty() }
            ?: allProviders.firstOrNull { it.models.isNotEmpty() }

        return Pair(resolvedModel, resolvedProviderConfig)
    }

    /**
     * Llame a un solo agente para generar una declaración.
     *
     * v1.97: Se agregaron parámetros isMentioned / isRepair,Soporte para indicaciones de @mención y correcciones de decisiones.
     * v1.0.29: Eliminar parámetro del modelo,pase interno [resolveAssistantModel] Modelo exclusivo de asistente de análisis.
     * Transformación 2(Phone Session modo):Pase [buildMessages] Construir la fórmula de la sesión telefónica prompt,
     * Cada agente recibe de forma independiente"Empuje celular"en lugar de compartir contexto;Guía para la prevención de la ofuscación de identidad proporcionada por
     * [SystemPromptAssembler.buildGroupChatHintSection] inyectar(per-agent).
     *
     * v1.0.53 Phase 5: channel_* Acceso a herramientas — poner [PASS] El protocolo de texto cambia a decisiones de invocación de herramientas.
     *  - LLM debe ser llamado channel_reply(hablar)/ channel_pass(saltar)/ channel_read_context(Leer más historia) uno de
     *  - Múltiples rondas de toma de decisiones:Trío de la ronda 1 disponible.;Ruotiao read_context,Ronda 2 obligatoria después de completar los resultados reply/pass
     *  - Tiempo de espera de decisión(60s Decisión no completada dentro de)→ implicitPass(Instrucciones de implementación de alineación implicitPass)
     *  - Compatible con protocolos antiguos:LLM Cuando la herramienta no está ajustada pero se genera texto,prensa [PASS] Análisis de reglas de texto(Migración fluida)
     *
     * @param chat Entidad de chat grupal
     * @param chatId chat grupal id
     * @param assistant Configuración actual del agente
     * @param memberNames Nombre para mostrar de todos los miembros del chat grupal
     * @param isMentioned v1.97: Si fue mencionado por @(Afecta el aviso rápido)
     * @param isRepair v1.97: Si se debe volver a intentar la reparación de la decisión(El agente puntual no respondió en la ronda anterior.)
     * @return [AgentResult] — Pass Indica salto activo,Reply significa respuesta normal,Error Indica excepción de transmisión/tiempo de espera
     */
    /**
     * Revisión de corrección (2.0 B-17): Paquete de seguimiento de enlaces de chat grupal — Cancelar(usuario detenido/Generar preferencia)Cerrar el nodo de enlace,
     * Evite dejar "En ejecución" en la interfaz de usuario de la sesión principal. La implementación original de onDelegationFinished solo estaba disponible en Reply/Pass/Error
     * Tres estados van a,CancellationException Lanzar junto con TimeoutOrNull,Nodo permanentemente EN EJECUCIÓN.
     */
    private suspend fun invokeAgent(
        chat: GroupChatEntity,
        chatId: String,
        assistant: AssistantEntity,
        memberNames: List<String>,
        isMentioned: Boolean = false,
        isRepair: Boolean = false,
    ): AgentResult {
        // v1.202: Seguimiento de enlaces de chat grupal — Notificar a DelegationChainTracker para que comience en la entrada invokeAgent,
        // Permitir que la interfaz de usuario de la sesión principal vea el proceso de ejecución del chat grupal(Anteriormente, los chats grupales estaban completamente ausentes del seguimiento de enlaces.).
        // parentRequestId=null Indica que el chat grupal es una delegación de nivel superior.(feizi agent),con ejecutar con flujo de trabajo
        // TeamWorkflowExecutor La distinción onDelegationStarted se llama internamente.
        // requestId usar "gc-" prefijo + chatId + agentId + Marca de tiempo,Garantizar un origen globalmente único e identificable.
        val delegationRequestId = "gc-$chatId-${assistant.id}-${System.currentTimeMillis()}"
        delegationChainTracker?.onDelegationStarted(
            requestId = delegationRequestId,
            parentRequestId = null,
            task = "Respuesta del chat grupal",
            targetType = "assistant",
            targetId = assistant.id,
            targetName = assistant.name,
        )
        return try {
            invokeAgentInternal(chat, chatId, assistant, memberNames, isMentioned, isRepair, delegationRequestId)
        } catch (ce: CancellationException) {
            // B-17: Cerrar nodo al cancelar(El texto del resultado está vacío + descripción del error),Tíralo de nuevo
            delegationChainTracker?.onDelegationFinished(
                requestId = delegationRequestId,
                success = false,
                resultText = "",
                error = "Se canceló el discurso del miembro del chat grupal.",
            )
            throw ce
        }
    }

    private suspend fun invokeAgentInternal(
        chat: GroupChatEntity,
        chatId: String,
        assistant: AssistantEntity,
        memberNames: List<String>,
        isMentioned: Boolean = false,
        isRepair: Boolean = false,
        delegationRequestId: String,
    ): AgentResult {
        // ActivityHub: Al ingresar a invokeAgent se marca como VIEWING(leyendo las noticias),
        // Deje que la interfaz de usuario muestre inmediatamente que el agente comienza a procesar esta ronda de mensajes.
        activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.VIEWING)

        // v1.0.29: per-assistant Análisis de modelos — Deja que cada asistente use su propio modelo configurado,
        // en lugar del modelo seleccionado global. De esta forma, el Asistente A puede utilizar OpenCode en el chat grupal. DeepSeek,
        // B Asistente disponible para SiliconFlow GLM,Cada uno se enruta al Proveedor correspondiente.
        val (model, providerConfig) = resolveAssistantModel(assistant)

        // a. contexto de construcción
        val contextSize = assistant.contextMessageSize.takeIf { it > 0 } ?: DEFAULT_CONTEXT_SIZE
        val recentMessages = groupChatRepository.getRecentMessages(chatId, contextSize)

        // b. Construir lista de mensajes(Transform 2 Phone Session:sistema con identidad guidance,user Tipo de empuje phone prompt)
        val messages = buildMessages(chat, assistant, memberNames, recentMessages, model, isMentioned, isRepair)

        val temperature = assistant.temperature ?: DEFAULT_TEMPERATURE
        val maxTokens = assistant.maxTokens ?: DEFAULT_MAX_TOKENS
        // Los modelos con capacidades de razonamiento producirán contenido de pensamiento antes de emitir el primer token, lo que lleva mucho más tiempo que los modelos normales.
        // En consecuencia, el presupuesto de ronda única se relaja para evitar juicios erróneos de las respuestas normales como tiempos de espera para la decisión.
        val modelSupportsReasoning = model?.abilities?.contains(
            io.zer0.ai.core.ModelAbility.REASONING,
        ) == true
        // ActivityHub: La transmisión de LLM se ajustará pronto,marcado como REPLYING(Respondiendo).
        activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.REPLYING)

        // v1.0.53 Phase 5: channel_* Conjunto de herramientas(Phone Session modo)
        // conjunto de tres piezas:channel_reply(hablar) / channel_pass(saltar) / channel_read_context(Leer más historia)
        // Las devoluciones de llamada se capturan mediante cierres var,Después de la ejecución, escriba el resultado en replyContent / passReason
        var replyContent: String? = null
        var passReason: String? = null
        val workingMessages = messages.toMutableList()

        // B8-02: channel Conjunto de tres piezas + combinación global de herramientas generales,Los miembros del chat grupal también pueden conectarse a Internet./calcular/escribir recordatorio
        val channelTools = ChannelToolFactory.createChannelToolDefinitions(
            groupChatId = chatId,
            senderAssistantId = assistant.id,
            onReply = { content -> replyContent = content },
            onPass = { reason -> passReason = reason },
            contextProvider = { limit ->
                // Reparación de auditoría (S-06): channel_read_context También utiliza filtrado de visibilidad de mensajes privados. —
                // Lea todas las noticias recientes directamente antes,Cualquier miembro puede leer mensajes privados enviados a otros agentes llamando,
                // con"Sólo la IA objetivo es visible"promesas de productos contradictorias.
                val more = groupChatRepository.getRecentMessages(chatId, limit)
                formatMessageTranscript(visibleMessagesFor(more, assistant.id))
            },
        )
        // B8-03 Planificar B → v2.x (B3): Se abre la salida de medios del chat grupal — Generar gráfico/El producto del código QR se escribe como un archivo adjunto al mensaje.
        // (imageBase64Json,Representado por MessageImageGrid);El vídeo generado sigue bloqueado.(La entidad de mensaje de chat grupal no tiene columna de video).
        // B8-02: Filtrar por herramienta de ayuda para miembrosIdsJson,Mantener todas las herramientas cuando no estén configuradas
        val enabledToolIds = runCatching {
            AppJson.decodeFromString(ListSerializer(String.serializer()), assistant.toolIdsJson)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        // v2.x (B3): Desacoplar las herramientas multimedia de los interruptores de herramientas habituales — Las herramientas generales están desactivadas de forma predeterminada(Evite la tormenta de llamadas de herramientas vacías de modelos pequeños),
        // Las herramientas de medios ya tienen canales de visualización,Inyectar por separado;Ambos están filtrados por el miembro toolIdsJson.
        val assistantToolDefs = toolRegistry?.listToolsAsToolDefinitions(enabledToolIds) ?: emptyList()
        val mediaTools = GroupChatToolPolicy.filterMediaTools(assistantToolDefs)
        val regularTools = if (ENABLE_GROUP_CHAT_REGULAR_TOOLS) {
            GroupChatToolPolicy.filterRegularTools(assistantToolDefs)
        } else {
            emptyList<io.zer0.ai.core.ToolDefinition>()
        }
        val localTools = (mediaTools + regularTools).distinctBy { it.name }
        val allToolDefinitions = (channelTools.first + localTools).distinctBy { it.name }
        // v2.x (B3): Esta ronda del gráfico de generación de agentes./Almacenamiento en caché de resultados multimedia para códigos QR(Escribe un mensaje de respuesta al final. imageBase64Json)
        val generatedMediaImages = mutableListOf<String>()
        // v2.x cierre del legado: La dirección de video generada por el agente en esta ronda.(Escriba un mensaje de respuesta en la columna videoFileUri al final)
        var generatedMediaVideo: String? = null
        val toolExecutors = channelTools.second.toMutableMap()
        localTools.forEach { def ->
            toolExecutors[def.name] = { args ->
                withContext(Dispatchers.IO) {
                    toolRegistry?.let { registry ->
                        val execute: suspend () -> String = {
                            io.zer0.muse.tools.ToolRouteExecutionGuard(registry).executeFromJson(
                                def.name,
                                AppJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), args),
                            )
                        }
                        // v2.x (B3): La herramienta de medios inyecta el host de captura en el alcance de la rutina local.,Recupera el gráfico generado como archivo adjunto a esta ronda de mensajes.;
                        // Sólo aplica para esta convocatoria,No tocar el host de la interfaz de usuario global(charla privada/Seguridad de simultaneidad de chat grupal).
                        if (def.name in GroupChatToolPolicy.ENABLED_MEDIA_TOOLS) {
                            withContext(
                                MediaGenHostContext(
                                    GroupChatMediaCaptureHost(
                                        onImages = generatedMediaImages::addAll,
                                        onVideo = { generatedMediaVideo = it },
                                    ),
                                ),
                            ) {
                                execute()
                            }
                        } else {
                            execute()
                        }
                    } ?: "(La herramienta no está disponible)"
                }
            }
        }

        // v1.0.53 Phase 5: Múltiples rondas de ciclos de toma de decisiones(Tiempo muerto corto independiente para cada ronda. → Se considera tiempo de espera implicitPass)
        //  - Ronda 1:Disponible en juegos de tres piezas.
        //  - Ronda 2(Sólo si la 1.ª rotación es read_context):Quitar read_context,fuerza reply/pass
        //  - Salir después de cualquier ronda de respuesta/aprobación.;Salir incluso si read_context no está ajustado(implicit pass)
        //  - Error de transmisión(HTTP 500 / Anormalidad de la red)→ Regresar inmediatamente Error(Espere el tiempo de espera)
        //  - Tiempo de espera de una sola ronda → implicitPass,No más dejar que un transmisor lento consuma todo su presupuesto total de 60 años
        var streamErrorMessage: String? = null // Error de transmisión(sin tiempo de espera)
        var roundTimedOut = false
        // El presupuesto de ronda única se establece antes de ingresar al ciclo de toma de decisiones y el registro de tiempo de espera comparte el mismo valor con withTimeoutOrNull.
        val roundTimeoutMs = if (modelSupportsReasoning) {
            AGENT_ROUND_TIMEOUT_REASONING_MS
        } else {
            AGENT_ROUND_TIMEOUT_BASE_MS
        }
        // issue #6: channel Recuento de reintentos para fallas de herramientas/respuestas vacías(compartido durante toda la fase de toma de decisiones,Ciclo anti-muertos)
        var channelToolRetryCount = 0
        var channelEmptyRetryCount = 0
        // v1.x: Proceso de pensamiento de flujo acumulativo a lo largo de los turnos.(Bloque de pensamiento profundo para la respuesta final)
        val accumulatedReasoning = StringBuilder()
        for (round in 0 until MAX_CHANNEL_DECISION_ROUNDS) {
            replyContent = null
            passReason = null

            val toolsForThisRound = if (round == 0) {
                allToolDefinitions
            } else {
                // Ronda 2:Quitar read_context,fuerza reply/pass
                allToolDefinitions.filter { it.name != "channel_read_context" }
            }

            // llamada en streaming,acumular ContentDelta + ToolCallDelta
            val builder = StringBuilder()
            // v1.x: proceso de pensamiento de flujo acumulativo(Los mensajes en modo herramienta channel_reply también pueden mostrar bloques de pensamiento)
            val reasoningBuilder = accumulatedReasoning
            val toolCallAccumulator = mutableMapOf<Int, MutableList<ChatStreamEvent.ToolCallDelta>>()
            var streamError: String? = null

            val roundCompleted = withTimeoutOrNull(roundTimeoutMs) {
                // B-22: Compartir el límite actual simultáneo con el mensaje activo,Evite el disparador 429 superpuesto, lo que resulta en la pérdida del silencio del habla.
                GenerationGate.withPermit {
                    chatService.streamChat(
                        messages = workingMessages,
                        model = model,
                        temperature = temperature,
                        maxTokens = maxTokens,
                        tools = toolsForThisRound,
                        providerConfig = providerConfig,
                    ).collect { event ->
                        when (event) {
                            is ChatStreamEvent.ContentDelta -> {
                                builder.append(event.delta)
                                // v1.x: Salida de transmisión de chat grupal — Empuje hacia en tiempo real UI
                                activityHub.updateStreamingContent(chatId, builder.toString())
                            }
                            is ChatStreamEvent.ReasoningDelta -> reasoningBuilder.append(event.delta)
                            is ChatStreamEvent.ImageDelta -> { /* El chat grupal actualmente no admite la salida de imágenes,ignorar */ }
                            is ChatStreamEvent.ToolCallDelta -> {
                                // Marco de instantánea (isSnapshot=true) lleva parámetros completos y la semántica es "clara y reescrita".
                                // La ruta alternativa sin transmisión la utiliza para sobrescribir los parámetros incompletos que se han acumulado en la fase de transmisión;
                                // Si agrega directamente sin reconocer la instantánea, joinToString unirá los dos segmentos.
                                // (En el registro len=230 → argsLen=473, casi exactamente dos veces),
                                // El JSON detallado ya no es válido → Analizar campos vacíos → content esta vacio → Silencio.
                                val list = toolCallAccumulator.getOrPut(event.index) { mutableListOf() }
                                if (event.isSnapshot) list.clear()
                                list.add(event)
                            }
                            is ChatStreamEvent.Done -> { /* final de la corriente */ }
                            is ChatStreamEvent.Error -> streamError = event.message
                            is ChatStreamEvent.StreamInterrupted -> streamError = event.message
                            is ChatStreamEvent.FallbackNotice -> { /* Bajado automáticamente a no streaming */ }
                            // A5: La rueda de herramientas de chat grupal no muestra el uso de tokens,ignorar
                            is ChatStreamEvent.UsageDelta -> {}
                            is ChatStreamEvent.CitationDelta -> {}
                        }
                    }
                }
                true
            } ?: false
            if (!roundCompleted) {
                roundTimedOut = true
                break
            }
            if (streamError != null) {
                // Error de transmisión:Graba y sal del circuito,Regresar externamente Error
                streamErrorMessage = streamError
                break
            }

            // acumulado tool calls(Ordenar por índice,fusionar delta)
            val toolCalls = toolCallAccumulator.toSortedMap().values.map { deltas ->
                ToolCall(
                    id = deltas.firstNotNullOfOrNull { it.id } ?: "",
                    name = deltas.firstNotNullOfOrNull { it.name } ?: "",
                    arguments = deltas.mapNotNull { it.argumentsDelta }.joinToString(""),
                )
            }

            val rawText = builder.toString().trim()

            // Proceso tool calls
            var calledReadContext = false
            // issue #6: esta ronda channel_* Información de fallo de ejecución de la herramienta.
            // antigua implementación toolExecutors[...]?.invoke(args) El valor de retorno se descarta directamente.,
            // Causar que falle la verificación de channel_reply(Si el contenido está vacío)nadie lo sabe,
            // El proceso continúa hasta la rama "indeciso",eventualmente se convirtió implicit pass ——
            // Lo que el usuario ve es "ninguna respuesta y ningún error en toda la ronda".
            var channelToolFailure: String? = null
            // B-16: ¿Está girada la rueda de herramientas normal?"mensaje del asistente con tool_calls"relleno workingMessages
            var assistantToolMsgAdded = false
            for (tc in toolCalls) {
                when (tc.name) {
                    "channel_reply" -> {
                        // issue #10(comentarios de los usuarios): v2.6.4 Comencemos con el razonamiento aquí.
                        // Como resultado, el borrador en inglés del modelo se envió al chat grupal. El proceso de pensamiento no es el contenido del discurso y ese camino ha sido eliminado.
                        //
                        // Ahora: primero busque el texto en los argumentos (incluido un resumen de los nombres de campos comunes),
                        // ¿No puedes encontrarlo? Inténtalo de nuevo"arguments Es una situación de seguridad de "texto desnudo".
                        // Si aún no puedes conseguirlo, desiste y déjalo en manos del rellenador fallido que aparece a continuación para volver a intentarlo.
                        val rawArgs = parseToolArgs(tc.arguments)
                        val extracted = extractReplyContent(rawArgs)
                        val args = if (extracted != null) {
                            if (rawArgs["content"] != extracted) {
                                Logger.i(
                                    TAG,
                                    "Agent「${assistant.name}\"channel_reply El texto se ha tomado de nombres de campos no estándar y se ha corregido para content" +
                                        "(len=${extracted.length})",
                                )
                            }
                            rawArgs + ("content" to extracted)
                        } else {
                            val salvaged = salvageChannelContent(
                                unusedReasoning = "",
                                rawArguments = tc.arguments,
                            )
                            if (salvaged != null) {
                                Logger.w(
                                    TAG,
                                    "Agent「${assistant.name}\"channel_reply arguments es texto simple y se ha utilizado como texto del cuerpo" +
                                        "(len=${salvaged.length})",
                                )
                                rawArgs + ("content" to salvaged)
                            } else {
                                // No se puede obtener el texto: registre la estructura del parámetro (no el contenido) para facilitar la localización de por qué no se puede analizar.
                                Logger.w(
                                    TAG,
                                    "Agent「${assistant.name}\"channel_reply Falta texto y no se puede restaurar." +
                                        "(argsLen=${tc.arguments.length}, keys=${rawArgs.keys.joinToString(",")})" +
                                        " → Abandona esta ronda de discursos, falla y vuelve a intentarlo.",
                                )
                                rawArgs
                            }
                        }
                        val result = toolExecutors["channel_reply"]?.invoke(args)
                        if (result == null) {
                            channelToolFailure = "channel_reply No registrado"
                            Logger.w(TAG, "Agent「${assistant.name}\"channel_reply Herramienta no registrada")
                        } else if (isChannelToolFailure(result)) {
                            channelToolFailure = result
                            Logger.w(TAG, "Agent「${assistant.name}\"channel_reply La ejecución falló: $result")
                        }
                        // replyContent El valor ha sido asignado a través de la devolución de llamada onReply
                    }
                    "channel_pass" -> {
                        val args = parseToolArgs(tc.arguments)
                        val result = toolExecutors["channel_pass"]?.invoke(args)
                        if (result == null) {
                            channelToolFailure = "channel_pass No registrado"
                            Logger.w(TAG, "Agent「${assistant.name}\"channel_pass Herramienta no registrada")
                        } else if (isChannelToolFailure(result)) {
                            channelToolFailure = result
                            Logger.w(TAG, "Agent「${assistant.name}\"channel_pass La ejecución falló: $result")
                        }
                        // passReason Ya asignado mediante devolución de llamada onPass
                    }
                    "channel_read_context" -> {
                        val args = parseToolArgs(tc.arguments)
                        val result = toolExecutors["channel_read_context"]?.invoke(args) ?: "(sin contexto)"
                        // Rellene los resultados de read_context como mensajes de usuario,Dejemos que LLM tome decisiones basadas en esto en la próxima ronda.
                        workingMessages.add(
                            UIMessage(
                                role = MessageRole.USER,
                                content = "【channel_read_context Resultado]\n$result\n\nPor favor, según el contexto completo anterior.,Llame a channel_reply para hablar o a channel_pass para saltar.",
                            ),
                        )
                        calledReadContext = true
                    }
                    // Llamadas generales a herramientas — El resultado solo rellena el contexto del miembro actual.,No ingresar a la lista de mensajes del chat grupal
                    else -> {
                        // Revisión de corrección (2.0 B-16): Los resultados de la herramienta regular primero deben ser seguidos por"con llamadas a herramientas
                        // assistant noticias"después — Anteriormente solo se agregaban mensajes de HERRAMIENTA desnudos,Próxima ronda de solicitudes
                        // Prefijo faltante assistant tool_calls,OpenAI Compatible con API directa HTTP 400.
                        // Rellene únicamente las llamadas de herramientas habituales(channel_* El resultado finaliza con un mensaje de USUARIO./Relleno del método de salida,
                        // No puede aparecer en el asistente tool_calls,De lo contrario, también falta el resultado HERRAMIENTA correspondiente.).
                        if (!assistantToolMsgAdded) {
                            val regularCalls = toolCalls.filter { it.name !in CHANNEL_TOOL_NAMES }
                            if (regularCalls.isNotEmpty()) {
                                workingMessages.add(
                                    UIMessage(
                                        role = MessageRole.ASSISTANT,
                                        content = rawText,
                                        toolCalls = regularCalls,
                                    ),
                                )
                                assistantToolMsgAdded = true
                            }
                        }
                        val args = parseToolArgs(tc.arguments)
                        val result = toolExecutors[tc.name]?.invoke(args) ?: "(La herramienta no está disponible)"
                        workingMessages.add(
                            UIMessage(
                                role = MessageRole.TOOL,
                                content = result,
                                toolCallId = tc.id,
                            ),
                        )
                    }
                }
            }

            // Decisión completada?
            if (replyContent != null) break // ha hablado
            if (passReason != null) break // saltado
            if (!calledReadContext) {
                // issue #6: Manejar primero el error de ejecución de la herramienta —— Así es "la modelo quiere hablar pero la herramienta se niega",
                // No se puede considerar como "el modelo salta activamente". Rellene el motivo del error en el modelo.,Deja que corrija los parámetros y vuelve a intentarlo.
                if (channelToolFailure != null && channelToolRetryCount < MAX_CHANNEL_TOOL_RETRIES) {
                    channelToolRetryCount++
                    Logger.w(
                        TAG,
                        "Agent「${assistant.name}\"channel La ejecución de la herramienta falló($channelToolFailure)," +
                            "Rellene los errores e inténtelo de nuevo($channelToolRetryCount/$MAX_CHANNEL_TOOL_RETRIES)",
                    )
                    workingMessages.add(
                        UIMessage(
                            role = MessageRole.USER,
                            content = "[Error en la ejecución de la herramienta]\n$channelToolFailure\n\n" +
                                "Por favor llama de nuevo channel_reply(Asegúrate de que el contenido no esté vacío)O channel_pass para completar esta ronda de toma de decisiones.",
                        ),
                    )
                    continue
                }

                // read_context no ha sido ajustado ni decidido — Compruebe si hay salida de texto(Compatible con el viejo [PASS] acuerdo de texto)
                if (rawText == PASS_MARKER) {
                    // El modelo dice claramente [PASS] → verdadero salto explícito,Sin alarma
                    Logger.d(TAG, "Agent「${assistant.name}\"explícito [PASS] acuerdo de texto,Saltate esta ronda")
                    break
                }
                if (rawText.isBlank()) {
                    // issue #6: La respuesta vacía no significa "el modelo se salta activamente".
                    // La implementación anterior fusionó los dos en implicit pass,Esto hace que el modelo permanezca en silencio durante toda la ronda sin responder ni informar de un error.
                    // Inténtalo de nuevo aquí(Notificar el modelo de excepciones.),Si aún falla, se degradará para aprobar y dejar rastros.
                    if (channelToolFailure == null && channelEmptyRetryCount < MAX_CHANNEL_EMPTY_RETRIES) {
                        channelEmptyRetryCount++
                        Logger.w(
                            TAG,
                            "Agent「${assistant.name}\"No hay texto ni herramientas en esta ronda\".(respuesta vacía)," +
                                "Inténtalo de nuevo($channelEmptyRetryCount/$MAX_CHANNEL_EMPTY_RETRIES)",
                        )
                        workingMessages.add(
                            UIMessage(
                                role = MessageRole.USER,
                                content = "Su última ronda no produjo ningún resultado. Por favor llama a channel_reply para hablar,O llame a channel_pass para omitir explícitamente esta ronda.",
                            ),
                        )
                        continue
                    }
                    // Reintentar todavía vacío → degradado a pass,Pero escriba las razones para facilitar la investigación.
                    Logger.w(
                        TAG,
                        "Agent「${assistant.name}”Respuesta aún vacía después de volver a intentarlo,degradado a implicit pass" +
                            (if (channelToolFailure != null) "(Herramienta fallida: $channelToolFailure)" else ""),
                    )
                    break
                }
                // Hay texto pero no hay herramientas ajustadas. → como reply(Compatible con modelos que no admiten llamadas de herramientas)
                replyContent = rawText
                break
            }
            // De lo contrario, continúa a la siguiente ronda.(read_context post coerción reply/pass)
        }

        // Manejo de prioridad de errores de transmisión(sin tiempo de espera,Regresar Error)
        if (streamErrorMessage != null) {
            Logger.e(TAG, "Agent「${assistant.name}\"LLM llamada fallida: $streamErrorMessage")
            activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.ERROR)
            scheduleIdleTransition(chatId, assistant)
            delegationChainTracker?.onDelegationFinished(
                requestId = delegationRequestId,
                success = false,
                resultText = "",
                error = streamErrorMessage,
            )
            return AgentResult.Error(streamErrorMessage!!)
        }

        if (roundTimedOut) {
            // Tiempo de espera de decisión → implicitPass(Descripción de implementación de alineación implicitPass: se omite automáticamente si no se toma ninguna decisión después del tiempo de espera)
            Logger.w(TAG, "Agent「${assistant.name}\"Tiempo de espera de decisión de una sola ronda(${roundTimeoutMs / 1000}s),implicit pass")
            activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.NO_REPLY)
            scheduleIdleTransition(chatId, assistant)
            delegationChainTracker?.onDelegationFinished(
                requestId = delegationRequestId,
                success = false,
                resultText = "",
                error = "Agent Tiempo de espera de decisión(implicitPass)",
            )
            return AgentResult.Pass(implicit = true)
        }

        // Retorno basado en el resultado de la decisión AgentResult
        val replyText = replyContent?.let { sanitizeAgentReply(it) }
        if (replyText.isNullOrBlank() || replyText == PASS_MARKER) {
            // channel_pass o implicit pass
            Logger.i(TAG, "Agent「${assistant.name}\"Elige saltarte esta ronda(PASS, reason=${passReason ?: "implicit"})")
            activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.NO_REPLY)
            scheduleIdleTransition(chatId, assistant)
            delegationChainTracker?.onDelegationFinished(
                requestId = delegationRequestId,
                success = false,
                resultText = "",
                error = "Agent Saltate esta ronda(reason=${passReason ?: "implicit"})",
            )
            // v1.0.53: implicit=true Fallo de API/Salto pasivo de tiempo de espera,No cuenta para la rebaja
            return AgentResult.Pass(implicit = passReason == null)
        }

        // Extraer módulo de estado de ánimo/pensamiento(channel_reply El contenido puede contener etiquetas de estado de ánimo/pensamiento.)
        // Nota:Debe usar original content(sanitize ex replyContent),
        // sanitize Después de que se haya eliminado la etiqueta de estado de ánimo,La extracción debe estar vacía.
        val extractedMood = extractMood(replyContent ?: replyText)
        // v1.x: Priorizar el proceso de pensamiento de acumulación de flujo.(El modelo Reasoning_content no toma el texto final.)
        val extractedReasoning = accumulatedReasoning.toString().trim()
            .ifBlank { extractReasoning(replyContent ?: replyText) ?: "" }
            .ifBlank { null }

        // Guardar la respuesta del agente en el chat grupal
        // v2.x (B3): Gráficos multimedia generados en esta ronda.(Generar gráfico/código qr)Escribir mensaje adjunto imageBase64Json,
        // Representado por chat grupal MessageImageGrid;v2.x El legado termina,Generar video y escribir la columna videoFileUri,
        // Renderizado por AssistantVideoCard;Ninguno en absoluto "[]"/null(consistente con el comportamiento existente).
        val mediaImageJson = if (generatedMediaImages.isNotEmpty()) {
            AppJson.encodeToString(
                ListSerializer(String.serializer()),
                generatedMediaImages.map { normalizeGroupChatMediaRef(it) },
            )
        } else {
            "[]"
        }
        if (restoreBlocksWrites(chatId, "Respuestas de los miembros del chat grupal")) {
            return AgentResult.Error("La restauración de la copia de seguridad está en curso, se ha omitido la escritura de mensajes de chat grupal")
        }
        val msgId = groupChatRepository.sendMessage(
            chatId = chatId,
            senderType = "assistant",
            senderId = assistant.id,
            senderName = assistant.name,
            body = replyText,
            imageBase64Json = mediaImageJson,
            videoFileUri = generatedMediaVideo,
            mood = extractedMood,
            reasoning = extractedReasoning,
        )
        // Reparación de auditoría (5.2) adaptarse: sendMessage Volver cuando se haya eliminado el chat grupal null(El mensaje no está en la base de datos.),Sáltate esta ronda.
        // B-21: No entrar a la biblioteca ya no es silencioso Pass — Recuerda ERROR y regresa Error,Que la persona que llama perciba la pérdida del habla.
        // (Anteriormente, Pass se trataría como"Saltar activamente",El usuario nunca verá el discurso del agente y no recibirá ningún mensaje.).
        if (msgId == null) {
            Logger.e(TAG, "No se pudo guardar el discurso del chat grupal(chatId=$chatId, agent=${assistant.name}),Mensaje perdido")
            return AgentResult.Error("No se pudo guardar el mensaje de chat grupal,Declaración perdida")
        }
        // v1.x: Se ha eliminado el contenido en streaming.,Borrar salida temporal de UI
        activityHub.clearStreamingContent(chatId)

        Logger.i(TAG, "Agent「${assistant.name}\"En el chat grupal\"${chat.name}\"Discurso en")
        // ActivityHub: Respuesta normal completada → respaldo a después del retraso IDLE(El mensaje ha sido añadido a la lista.,chip Naturalmente escondido).
        scheduleIdleTransition(chatId, assistant)
        // v1.202: rastreo de enlaces — Agent respuesta normal,Registrar nodo exitoso(Vista previa del resultado limitada a 500 palabras)
        delegationChainTracker?.onDelegationFinished(
            requestId = delegationRequestId,
            success = true,
            resultText = replyText.take(500),
        )
        // v2.x: Aislamiento de memoria de chat grupal — Escriba el resumen de respuesta del agente de esta ronda en un formato independiente. fact store,
        // No escriba en el sistema de memoria principal del asistente.,Evite que los mensajes de chat grupal contaminen el contexto principal de la conversación.
        if (groupChatMemoryRepository != null && !restoreBlocksWrites(chatId, "Memoria de chat grupal")) {
            val summary = buildGroupChatMemorySummary(chat.name, assistant, replyText)
            resultOf { groupChatMemoryRepository.saveSummary(chatId, assistant.id, summary) }
                .onError { msg, t -> Logger.w(TAG, "Error al escribir la memoria del chat grupal(agent=${assistant.name}): $msg", t) }
        }
        return AgentResult.Reply(
            GroupChatMessageEntity(
                id = msgId,
                chatId = chatId,
                senderType = "assistant",
                senderId = assistant.id,
                senderName = assistant.name,
                body = replyText,
                imageBase64Json = mediaImageJson,
                videoFileUri = generatedMediaVideo,
                timestamp = System.currentTimeMillis(),
                mood = extractedMood,
                reasoning = extractedReasoning,
            ),
        )
    }

    /**
     * v1.0.53 Phase 5: El análisis de la cadena JSON de los argumentos de llamada de la herramienta devuelta por LLM es Map<String, String>.
     *
     * con [SkillExecutor.parseArgs] lógica homóloga,La implementación independiente evita depender de los métodos privados de SkillExecutor.
     * Devuelve nulo si falla el análisis Map(Deje que el ejecutor de la herramienta maneje los errores de parámetros faltantes por sí solo).
     */
    private fun parseToolArgs(json: String): Map<String, String> = resultOf {
        val obj = AppJson.decodeFromString(JsonObject.serializer(), json)
        obj.entries.associate { (k, v) ->
            val strValue = when (v) {
                is JsonPrimitive -> v.content
                else -> AppJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), v)
            }
            k to strValue
        }
    }.getOrNull() ?: emptyMap()

    /**
     * issue #9: Rescate el contenido del comentario de channel_reply del razonamiento/argumentos faltantes.
     *
     * issue #10 Arreglar (comentarios de los usuarios): **Nunca más utilices el razonamiento como contenido del discurso.**.
     *
     * v2.6.4 Una vez extraje el texto del último párrafo del razonamiento aquí y terminé escribiendo el borrador en inglés del modelo.
     * Enviado directamente al chat grupal (“The topic: ... I should reply briefly as Emma...”).
     * El proceso de pensamiento no es el contenido del discurso, cualquier“rescatado del razonamiento”Ninguna de las heurísticas es confiable.
     *
     * Ahora sólo queda un caso seguro:arguments Es texto simple en sí (algunos canales ascendentes convertirán directamente el texto principal
     * rellenar argumentos sin envolver JSON).JSON Encuentra contenido en el objeto por [extractReplyContent]
     * Sea responsable, abandone si no puede encontrarlo y vuelva a intentarlo si falla.
     *
     * @param unusedReasoning Los bits de parámetros están reservados únicamente para la compatibilidad del sitio de llamada,**Ya no se usa**.
     */
    private fun salvageChannelContent(unusedReasoning: String, rawArguments: String): String? {
        val rawText = rawArguments.trim()
        if (rawText.isEmpty() || rawText.startsWith("{") || rawText.startsWith("[")) return null
        // Texto desnudo: eliminar posibles comillas envolventes/Úselo directamente después de marcar el bloque de código.
        val cleaned = rawText
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
            .trim('"')
            .trim()
        return cleaned.takeIf { it.length >= MIN_SALVAGE_CHARS }
    }

    /**
     * issue #10: Extraiga el texto de los argumentos de channel_reply.
     *
     * Evidencia de registro: existe `channel_reply(len=204)` pero reportado“Falta el parámetro”situación,
     * Muestra que efectivamente hay contenido en los argumentos, pero el nombre del campo no. `content`(o anidado en un nivel).
     * Aquí, los nombres de campos comunes se prueban en orden de posibilidad y, si aún no se encuentran, se devuelve nulo.
     */
    private fun extractReplyContent(args: Map<String, String>): String? {
        args["content"]?.takeIf { it.isNotBlank() }?.let { return it }
        for (key in REPLY_CONTENT_KEYS) {
            args[key]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /**
     * issue #6: Determine si el resultado de la ejecución de la herramienta del canal falló.
     *
     * [ChannelReplyTool.execute] / [ChannelPassTool.execute] Acuerdo:Retorno del fracaso
     * a `error:` cadena al principio,Devuelve una descripción neutral en caso de éxito.(Como por ejemplo `Enviado al chat grupal`).
     * La implementación anterior descarta el valor de retorno,El fracaso nadie se da cuenta → Hubo todo un silencio sin respuesta.
     */
    private fun isChannelToolFailure(result: String): Boolean {
        val trimmed = result.trim()
        return trimmed.startsWith("error:") || trimmed.startsWith("Error:") ||
            trimmed.startsWith("Error:") || trimmed.startsWith("error:")
    }

    /**
     * ActivityHub: Retorno retrasado al estado INACTIVO(No bloquear la próxima llamada del agente).
     *
     * en [appScope] Iniciar rutina independiente en,espera [ACTIVITY_IDLE_DELAY_MS] Luego establezca el estado del agente especificado en INACTIVO.
     * De esta manera NO_REPLY / ERROR / REPLYING Espere a que el estado final permanezca en la interfaz de usuario por un tiempo,visible para los usuarios"quien saltó/error/Respondido",
     * La rotación del siguiente agente no se ve bloqueada por el retraso.
     *
     * @param chatId chat grupal id
     * @param assistant actual agent(tomar identificación y name)
     */
    private fun scheduleIdleTransition(chatId: String, assistant: AssistantEntity) {
        appScope.launch {
            delay(ACTIVITY_IDLE_DELAY_MS)
            activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.IDLE)
        }
    }

    /**
     * v2.x: Construya un resumen de la memoria del chat grupal.
     *
     * La implementación actual es una simple interceptación.:Combine el nombre del chat grupal + el nombre del agente + las primeras 200 palabras del texto de respuesta en un resumen.
     * No ajuste el LLM para evitar retrasos;Si necesita un resumen más detallado más adelante,,Puede ser generado por LLM en su lugar.
     *
     * Se escribirá un resumen [GroupChatMemoryRepository],Para uso de SystemPromptAssembler
     * `<group_chat_memory>` etiquetas inyectadas en system prompt,con memoria principal `<long_term_memory>` Distinguir.
     *
     * @param chatName Nombre del chat grupal
     * @param assistant Responder agent
     * @param replyText agent Responder texto
     * @return texto resumido(Límite a 300 palabras)
     */
    private fun buildGroupChatMemorySummary(chatName: String, assistant: AssistantEntity, replyText: String): String {
        val preview = replyText.take(200)
        return "En el chat grupal \"$chatName\"en,${assistant.name} Responder:$preview"
    }

    /**
     * Construya una lista de mensajes enviados a LLM.
     *
     * - System: assistant.systemPrompt + Solicitud de identidad del chat grupal(Contiene la modificación 3 para evitar confusión de identidad. guidance)
     * - System: v1.137 RAG inyectar(fragmento de visita de la base de conocimientos,@mention búsqueda dirigida)
     * - User: Phone Session empujar prompt(Transformación 2)
     *
     * v1.97: Se agregaron parámetros isMentioned / isRepair,Inyecte @menciones y sugerencias para corregir decisiones en los mensajes de los usuarios.
     * v1.137: Se agregó inyección de RAG y ayudas visuales.(VisionBridge)Proceso:
     *  - RAG:Analizar mensajes de usuarios recientes @mention,Buscar base de conocimientos,Inyecte el fragmento de hit como un mensaje de SISTEMA.
     *  - Visión:Cuando el modelo no soporta la visión pero tiene imágenes.,llamar [VisionBridge.prepare] Inyectar descripción de imagen en texto,
     *    en lugar de descartar la imagen directamente(Consulte el procesamiento de ChatViewModel de chat único.).
     *
     * Transformación 2(Phone Session modo):Cada agente recibe de forma independiente"Empuje celular"fórmula prompt,
     * en lugar de agrupar a todos los miembros en el mismo contexto.
     *  - System:assistant.systemPrompt + buildGroupChatHintSection(Contiene identidad para evitar confusión. guidance)
     *    + MOOD Formato + debe pasar channel_* decisión de herramienta
     *  - User:buildPhonePrompt construido"Empuje celular"prompt(Noticias recientes + @menciones + correcciones de decisiones)
     *
     * Transformación 3(Prevención de identidad guidance):por [SystemPromptAssembler.buildGroupChatHintSection]
     * llamada interna [SystemPromptAssembler.buildIdentityGuidance] inyectar,per-agent.
     *
     * channel_* Acceso a herramientas (v1.0.53 Phase 5 Ya implementado):[invokeAgentInternal] Pasa cada ronda
     * [ChannelToolFactory.createChannelToolDefinitions] estructura channel_reply / channel_pass /
     * channel_read_context Conjunto de tres y pasado a streamChat;LLM Llame a channel_reply para hablar,channel_pass saltar,
     * channel_read_context Leer más historia. Presione cuando no se invoque ninguna herramienta [PASS] El protocolo de texto cubre todos los aspectos (compatible con modelos que no admiten la llamada de herramientas).
     *
     * @param chatId chat grupal id(para el almacenamiento en caché de VisionBridge key)
     * @param chatName Nombre del chat grupal
     * @param assistant Configuración actual del agente
     * @param memberNames Nombre para mostrar de todos los miembros del chat grupal
     * @param recentMessages Lista de mensajes recientes(orden ascendente por tiempo)
     * @param model Modelo actualmente seleccionado(null Incapaz de juzgar la capacidad visual cuando,Sea conservador y no adjunte fotografías.)
     * @param isMentioned Si fue mencionado por @
     * @param isRepair Si se debe volver a intentar la reparación de la decisión
     * @return UIMessage lista
     */
    /**
     * Reparación de auditoría (1.6): Filtrado de visibilidad de mensajes privados — votar/Resumen/Debate, etc., todos ponen el mensaje en agent prompt
     * Los caminos son compartidos. Los agentes no objetivo no pueden ver el contenido de los mensajes privados.(violar"Sólo la IA objetivo es visible"promesa de producto).
     * Reglas de visibilidad ver [buildMessages] Comentarios internos.
     */
    private fun visibleMessagesFor(recentMessages: List<GroupChatMessageEntity>, assistantId: String): List<GroupChatMessageEntity> =
        recentMessages.filter { msg ->
            msg.whisperTargetId == null ||
                msg.whisperTargetId == assistantId ||
                (msg.senderId == assistantId && msg.whisperTargetId == "local_user")
        }

    private suspend fun buildMessages(
        chat: GroupChatEntity,
        assistant: AssistantEntity,
        memberNames: List<String>,
        recentMessages: List<GroupChatMessageEntity>,
        model: io.zer0.ai.core.Model?,
        isMentioned: Boolean = false,
        isRepair: Boolean = false,
    ): List<UIMessage> {
        val chatId = chat.id
        val chatName = chat.name
        val messages = mutableListOf<UIMessage>()

        // v2.x: Filtrado de susurros — Los agentes que no son el objetivo no pueden ver los mensajes privados.
        // reglas de visibilidad:
        //  - whisperTargetId == null:información pública,Visible para todos
        //  - whisperTargetId == assistant.id:Usuario→El mensaje privado de este agente,Este agente es visible.
        //  - senderId == assistant.id y whisperTargetId == "local_user":ben agent→Mensaje privado del usuario,Este agente es visible.(lo que dije)
        //  - Otros:No visible para el agente actual(filtrar)
        // Reparación de auditoría (1.6): Extraído como función de nivel superior [visibleMessagesFor],para votar/Resumen/Debate compartido.
        val visibleMessages = visibleMessagesFor(recentMessages, assistant.id)

        // System message: assistant.systemPrompt + Consejos para chatear en grupo(Contiene la modificación 3 para evitar confusión de identidad. guidance)
        // Sesión telefónica de Retrofit 2: buildGroupChatHintSection inyecta internamente la guía de identidad,per-agent.
        val systemContent = buildString {
            if (assistant.systemPrompt.isNotBlank()) {
                appendLine(assistant.systemPrompt)
                appendLine()
            }
            appendLine(
                SystemPromptAssembler.buildGroupChatHintSection(
                    chatName = chatName,
                    members = memberNames,
                    currentAgentName = assistant.name,
                ),
            )
            appendLine()
            appendLine(groupChatMoodSection())

            // v1.0.53: Inyectar memoria a largo plazo(Retrato de usuario)Chatear recuerdos con grupo.(agent Discursos pasados)
            // Reparación"La memoria del chat grupal y otras memorias locales no son interoperables"pregunta
            systemPromptAssembler?.let { assembler ->
                if (
                    AssistantMemoryAccessPolicy.canInjectAssistantFacts(
                        assistantId = assistant.id,
                        useGlobalMemory = assistant.useGlobalMemory,
                        memoryEnabled = assistant.memoryEnabled && settings.isMemoryEnabled(),
                        forSubagent = false,
                        ignoreMemory = false,
                    )
                ) {
                    val longTermMemory =
                        assembler.buildLongTermMemorySection(
                            assistantId = assistant.id,
                            scope = if (assistant.id == "default") "main" else assistant.id,
                            spaceId = settings.currentSpaceIdFlow.first().ifBlank { "default" },
                        )
                    if (longTermMemory.isNotBlank()) {
                        appendLine()
                        appendLine(longTermMemory)
                    }
                }
                val groupChatMemory = assembler.buildGroupChatMemorySection(assistant.id, chatId)
                if (groupChatMemory.isNotBlank()) {
                    appendLine()
                    appendLine(groupChatMemory)
                }
                // v2.x: Guía de uso de emoticonos(El chat grupal y el chat individual están en el mismo proceso;La biblioteca esta vacia/Cadena vacía cuando el interruptor está apagado)
                val stickerGuide = assembler.buildStickerGuideSection()
                if (stickerGuide.isNotBlank()) {
                    appendLine()
                    appendLine(stickerGuide)
                }
            }

            // v2.x: Inyectar documentos compartidos en grupo(Conocimientos previos compartidos visibles para todos los miembros.)
            val sharedDocs = resultOf { groupChatRepository.parseSharedDocs(chat) }.getOrNull().orEmpty()
            if (sharedDocs.isNotEmpty()) {
                appendLine()
                appendLine("[Documentos compartidos por el grupo] Los siguientes son materiales de referencia compartidos por este grupo.,Por favor consúltelo cuando hable.:")
                for (doc in sharedDocs) {
                    appendLine("— ${doc.title} —")
                    // El documento único está limitado a 2000 palabras.,Evite la hinchazón inmediata;más allá del truncamiento
                    val docContent = if (doc.content.length > 2000) {
                        doc.content.take(2000) + "…(truncado)"
                    } else {
                        doc.content
                    }
                    appendLine(docContent)
                    appendLine()
                }
            }

            // v2.x: Inyectar el contexto exclusivo de este agente.(Sólo visible para el agente actual,No puede ser visto por otros miembros.)
            val privateContextMap = resultOf { groupChatRepository.parseMemberPrivateContext(chat) }.getOrNull().orEmpty()
            val myPrivateContext = privateContextMap[assistant.id]
            if (!myPrivateContext.isNullOrBlank()) {
                appendLine()
                appendLine("[Su contexto exclusivo] La siguiente información solo es visible para usted,Otros miembros del grupo no lo saben.:")
                // Límite de 1500 palabras,Evite la hinchazón inmediata
                val privateText = if (myPrivateContext.length > 1500) {
                    myPrivateContext.take(1500) + "…(truncado)"
                } else {
                    myPrivateContext
                }
                appendLine(privateText)
            }

            // v1.0.53 Phase 5: channel_* La herramienta ha sido conectada,Informar a LLM que se deben tomar decisiones sobre herramientas.
            // versión antigua"no dar salida channel_* Texto de llamada de herramienta"Consejos para están obsoletos(La herramienta realmente ha sido registrada y pasada a streamChat)
            appendLine()
            appendLine(
                "【Herramienta de decisión】Has obtenido channel_reply / channel_pass / channel_read_context Tres herramientas." +
                    "Esta ronda deberá convocar a uno de ellos para expresar una posición.:",
            )
            appendLine("- quiero hablar:llamar channel_reply(content=tu respuesta),content escribe primero <mood>...</mood> Escribe el texto nuevamente.")
            appendLine("- no hables:llamar channel_pass(reason=Motivo opcional)")
            appendLine("- necesito más contexto:llamar channel_read_context(limit=Número de artículos,Predeterminado20,la mayoría50)")
            appendLine("No envíe directamente el texto de respuesta,No salgas tampoco [PASS],La posición debe expresarse a través de una llamada de herramienta.")
            appendLine(
                "【Positividad para hablar】debe responder por defecto:cuando alguien habla(Incluye un saludo sencillo/buenas noches/saludos)tiempo,deberías responder," +
                    "Incluso si es solo una breve respuesta;channel_pass Úsalo sólo cuando realmente no tengas nada que decir o el tema no tenga nada que ver contigo.," +
                    "No te quedes callado porque sientas que es innecesario——El significado del chat grupal es interacción.,Responder es una obligación.",
            )
            // v2.x: Evite el arranque repetido — Que los miembros lean primero los discursos ya pronunciados en esta ronda., Reducir la repetición homogénea
            appendLine(
                "[Evite repeticiones] Antes de hablar, lea lo que han dicho los miembros anteriores en esta ronda.:" +
                    "Si el punto de vista se superpone en gran medida con una declaración existente,Prioriza agregar nuevos ángulos/nueva evidencia/Disentimiento,O llame a channel_pass para omitir;" +
                    "En lugar de repetir otros,Más bien sea breve que repetitivo.",
            )
            // v2.x: Rango de longitud del discurso(brief/standard/detailed) — Control de configuración de grupo
            when (chat.replyLengthMode) {
                "brief" -> appendLine("[Duración del discurso] Esta ronda requiere discursos breves.:Una o dos frases para ir al grano directamente,No des más detalles.")
                "detailed" -> appendLine("[Duración del discurso] Esta ronda requiere discursos detallados.:Desarrollar completamente el argumento.,Se puede dividir en párrafos y ejemplos.")
                else -> Unit
            }
        }
        messages.add(UIMessage(role = MessageRole.SYSTEM, content = systemContent))

        // v1.137: RAG inyectar — Analizar mensajes de usuarios recientes @mention,Buscar base de conocimientos,
        // Inyectar fragmento de hit como mensaje de SISTEMA(Consulte la lógica de inyección RAG de ChatViewModel).
        // El fallo no bloquea el proceso principal.(resultOf Rebajar).
        // v2.x: Usar filtrado visibleMessages,Asegúrese de que los mensajes privados no se filtren como fuentes de consultas RAG a agentes que no sean el objetivo.
        val lastUserMsg = visibleMessages.lastOrNull { it.senderType == "user" }
        val ragQuery = lastUserMsg?.body?.takeIf { it.isNotBlank() }
        if (ragQuery != null) {
            val ragConfig = resultOf { settings.getRagConfig() }.getOrNull() ?: RagConfig()
            if (ragConfig.enabled) {
                val scopeDocIds = resultOf { ragService.resolveMentionToDocIds(ragQuery) }
                    .getOrNull()?.takeIf { it.isNotEmpty() }
                val injection = resultOf {
                    ragService.buildInjectionContextWithCitations(ragQuery, ragConfig, scopeDocIds)
                }.getOrNull()
                if (injection != null && injection.text.isNotBlank()) {
                    messages.add(UIMessage(role = MessageRole.SYSTEM, content = injection.text))
                }
            }
        }

        // Transformar 2 sesión telefónica: mensaje de usuario a"Empuje celular"fórmula prompt
        // Cada agente recibe uno de forma independiente"Tu teléfono celular recibió un nuevo mensaje del chat grupal"empujar,en lugar de agrupar a todos los miembros en el mismo contexto.
        // v2.x: Construir utilizando mensajes visibles filtrados transcript,Asegúrese de que los agentes que no son el objetivo no puedan ver el mensaje privado.
        val userContent = buildPhonePrompt(
            chatName = chatName,
            assistant = assistant,
            recentMessages = visibleMessages,
            isMentioned = isMentioned,
            isRepair = isRepair,
        )
        // v1.136: Pase la imagen del mensaje de usuario más reciente al modelo como entrada multimodal,
        // Evite el problema de guardar únicamente imágenes del chat grupal pero no ver las respuestas.
        // v1.137: Cuando el modelo no soporta la visión.,Utilice VisionBridge en su lugar para insertar descripciones de imágenes en texto(en lugar de descartar la imagen),
        // Evite enviar imágenes a modelos de texto sin formato que generen HTTP 400.
        val latestUserImages = lastUserMsg?.let { msg ->
            resultOf {
                AppJson.decodeFromString(ListSerializer(String.serializer()), msg.imageBase64Json)
            }.getOrNull()?.map { stripDataUriPrefix(it) }?.filter { it.isNotEmpty() }
        } ?: emptyList()

        val finalUserContent: String
        val finalImages: List<String>
        // M2.1: Enfoque unificado del juicio visual VisionBridge(Determinado internamente por ModelCapabilityQuery de tres estados),
        // Igual que la ruta principal del chat.,Evite los chats grupales/Danchao saca conclusiones sobre capacidades diferentes para el mismo modelo.
        if (model != null && visionBridge.supportsVision(model)) {
            // El modelo apoya la visión.:Sube fotos directamente(Mantener la lógica existente)
            finalUserContent = userContent
            finalImages = latestUserImages
        } else if (latestUserImages.isNotEmpty()) {
            // El modelo no admite imágenes pero tiene imágenes.:Llame a VisionBridge para insertar la descripción de la imagen en el texto,Imágenes claras para evitar HTTP 400
            val prepared = resultOf {
                visionBridge.prepare(
                    text = userContent,
                    images = latestUserImages,
                    userRequest = userContent,
                    sessionId = chatId,
                )
            }.getOrNull()
            if (prepared != null) {
                finalUserContent = prepared.text
                finalImages = prepared.images
            } else {
                // VisionBridge falló:Bajar de categoría sin subir fotos(Evite enviar imágenes a modelos de texto sin formato, lo que resultará en 400)
                finalUserContent = userContent
                finalImages = emptyList()
            }
        } else {
            finalUserContent = userContent
            finalImages = emptyList()
        }
        messages.add(
            UIMessage(
                role = MessageRole.USER,
                content = finalUserContent,
                imageBase64List = finalImages,
            ),
        )

        return messages
    }

    /**
     * Modificación 2: Construir mensaje de inserción de sesión telefónica.
     *
     * Cada agente recibe uno de forma independiente"Tu teléfono celular recibió un nuevo mensaje del chat grupal"empujar,en lugar de agrupar a todos los miembros en el mismo contexto.
     *  - declaración superior"Nuevo mensaje de chat grupal recibido en el teléfono celular"
     *  - En el medio están las últimas noticias. transcript(Anotación de la línea del orador,Cooperar con la guía anti-ofuscación de identidad)
     *  - La parte inferior solicita la prioridad según sea @,y solicitar"Llame a channel_reply para hablar/channel_pass para saltar"
     *
     * v1.0.53 Phase 5: poner [PASS] Los comandos de texto cambiaron a comandos de llamada de herramientas.
     * LLM No más salida [PASS] texto,En su lugar, llame a la herramienta channel_pass para omitir;Llama a channel_reply para hablar.
     *
     * @param chatName Nombre del chat grupal
     * @param assistant Configuración actual del agente(Se utiliza para llamar a alguien por su nombre en un mensaje.)
     * @param recentMessages Lista de mensajes recientes(orden ascendente por tiempo)
     * @param isMentioned Si fue mencionado por @(Alerta cuando se menciona"Recuerde primero en esta ronda")
     * @param isRepair Si se debe volver a intentar la reparación de la decisión(Consejos"Sin respuesta en la última ronda")
     * @return Phone Session Enviar texto de aviso
     */
    private fun buildPhonePrompt(
        chatName: String,
        assistant: AssistantEntity,
        recentMessages: List<GroupChatMessageEntity>,
        isMentioned: Boolean,
        isRepair: Boolean,
    ): String = buildString {
        appendLine("${assistant.name},Su teléfono celular recibió \"$chatName”Nuevo mensaje de chat grupal.")
        appendLine()
        if (recentMessages.isNotEmpty()) {
            appendLine("【Últimas noticias】")
            appendLine(formatMessageTranscript(recentMessages))
            appendLine()
        }
        // v1.0.53 Phase 5: Las instrucciones de decisión cambiaron a llamadas a herramientas.
        if (isMentioned) {
            appendLine("⚠️ Esta ronda de noticias claramente @ usted,Debes llamar a channel_reply para responder a este mensaje.")
            appendLine("- Usuario@tú,Porque quiero escuchar tu respuesta. Las respuestas deben ser naturales y relevantes para su función.")
            appendLine("- No se debe llamar cuando @ channel_pass,Debes llamar a channel_reply para dar tu respuesta.")
        } else {
            appendLine("También eres miembro del chat grupal.,Después de ver este mensaje, puedes tomar la iniciativa de participar.")
            appendLine("- saludo sencillo/saludos/charlar:Simplemente llama a channel_reply para responder con una oración.,No pases.")
            appendLine("- Sólo cuando realmente no tiene nada que ver contigo y no tienes nada que decir,Solo llama a channel_pass para saltarte esta ronda.")
        }
        if (isRepair) {
            appendLine()
            appendLine("⚠️ No respondiste en la última ronda.,Pero te @mencionaron. Se debe llamar a channel_reply en esta ronda para responder.,No debe volver a pasar channel_pass.")
        }
        appendLine()
        appendLine("Formato de voz(channel_reply El parámetro de contenido):")
        appendLine("- escribe primero <mood>...</mood> bloquear(Manuscrito interno,El sistema eliminará automáticamente)")
        appendLine("- Luego escribe el texto directamente.(Como el habla natural en un chat grupal.,coloquial)")
        appendLine("- usar <mood>...</mood>(corchetes angulares),no usar [mood]...[/mood](corchetes)")
    }

    /**
     * v1.136: Eliminar el prefijo URI de datos,Devuelve una cadena base64 pura.
     * La imagen en el chat grupal comienza con "data:image/jpeg;base64,..." Almacenamiento formal,Y OpenAIProvider espera base64 pura.
     */
    private fun stripDataUriPrefix(dataUri: String): String {
        val commaIndex = dataUri.indexOf(',')
        return if (commaIndex >= 0 && dataUri.startsWith("data:", ignoreCase = true)) {
            dataUri.substring(commaIndex + 1)
        } else {
            dataUri
        }
    }

    /**
     * Formatee los mensajes recientes como transcripción de texto.
     *
     * Formato:
     * ```
     * [Nombre del remitente | MM-dd HH:mm] contenido
     * [Nombre del remitente | MM-dd HH:mm] contenido
     * ```
     *
     * Si el mensaje va acompañado de una imagen.,Luego marque después del contenido. `[fotosxN]`.
     */
    private fun formatMessageTranscript(messages: List<GroupChatMessageEntity>): String {
        val timeFormatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder()
        for (msg in messages) {
            val timeStr = timeFormatter.format(Date(msg.timestamp))
            val imageCount = resultOf {
                AppJson.decodeFromString(ListSerializer(String.serializer()), msg.imageBase64Json).size
            }.getOrNull() ?: 0
            val imageHint = if (imageCount > 0) " [fotosx$imageCount]" else ""
            sb.appendLine("[${msg.senderName} | $timeStr] ${msg.body}$imageHint")
        }
        return sb.toString().trimEnd()
    }

    /**
     * Limpiar etiquetas contenedoras en las respuestas del agente que no deben mostrarse al usuario.
     *
     * - despegar `<mood>...</mood>` Módulo de emociones
     * - despegar `<think>...</think>` módulo de pensamiento
     * - despegar `[channel_reply]...[/channel_reply]` Envoltorio de llamada de herramienta,Mantenga solo el texto interior
     */
    private fun sanitizeAgentReply(text: String): String {
        // M9 Limitaciones conocidas: Esta expresión regular eliminará todo <mood>/<think> bloquear,Incluye etiquetas de ejemplos de código LLM.
        // Aceptable a corto plazo,A largo plazo se recomienda utilizar delimitadores más explícitos.
        val withoutMood = text.replace(MusePatterns.MOOD_TAG_REGEX, "")
        val afterThinkReplace = withoutMood.replace(MusePatterns.THINK_TAG_REGEX, "")
        // L9: Mango abierto <think> etiqueta — Si todavía hay <think> Residuo(Sin correspondencia </think>),Luego de <think> truncar para finalizar
        val thinkIdx = afterThinkReplace.indexOf("<think>", ignoreCase = true)
        var withoutThink = if (thinkIdx >= 0) afterThinkReplace.substring(0, thinkIdx) else afterThinkReplace
        // v1.x: Mango abierto <mood> / [mood] etiqueta — Truncar desde el principio de la etiqueta hasta el final(consistente con pensar),
        // Evite mostrar el texto original del bloque de estado de ánimo al usuario.(Cuando no está cerrado, es probable que el texto principal se mezcle con el texto de humor.,El truncamiento es más limpio)
        val moodIdx = withoutThink.indexOfFirstUnclosedMood()
        if (moodIdx >= 0) {
            withoutThink = withoutThink.substring(0, moodIdx)
        }
        val withoutChannelReply = withoutThink.replace(
            // L6: Agregue RegexOption.IGNORE_CASE para ignorar mayúsculas y minúsculas
            Regex("\\[channel_reply\\](.*?)\\[/channel_reply\\]", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
        ) { it.groupValues[1].trim() }
        return withoutChannelReply.trim()
    }

    /**
     * v1.x: encuentra el primero"Encabezado de etiqueta de estado de ánimo abierto"ubicación(Los cerrados serán despojados primero según la regla regular anterior.).
     * compatibles <mood> con [mood] Dos estilos.
     */
    private fun String.indexOfFirstUnclosedMood(): Int {
        val angle = indexOf("<mood>", ignoreCase = true)
        val bracket = indexOf("[mood]", ignoreCase = true)
        return when {
            angle < 0 && bracket < 0 -> -1
            angle < 0 -> bracket
            bracket < 0 -> angle
            else -> minOf(angle, bracket)
        }
    }

    /**
     * Extraído de la respuesta original <mood>...</mood> bloquear contenido.
     */
    private fun extractMood(text: String): String? {
        val sb = StringBuilder()
        var match = MusePatterns.MOOD_TAG_REGEX.find(text)
        while (match != null) {
            sb.appendLine(match.groupValues[1].trim())
            match = match.next()
        }
        return sb.toString().trim().ifBlank { null }
    }

    /**
     * Extraído de la respuesta original <think>...</think> bloquear contenido.
     */
    private fun extractReasoning(text: String): String? {
        val sb = StringBuilder()
        var match = MusePatterns.THINK_TAG_REGEX.find(text)
        while (match != null) {
            sb.appendLine(match.groupValues[1].trim())
            match = match.next()
        }
        return sb.toString().trim().ifBlank { null }
    }
}
