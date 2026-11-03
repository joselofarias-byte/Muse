package io.zer0.muse.tools

import android.content.Context
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolCallInfo
import io.zer0.ai.core.ToolCallSanitizer
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.Perf
import io.zer0.common.TextTruncation
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.chat.PendingToolCallStore
import io.zer0.muse.data.ExperimentsConfig
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.audit.AuditLogger
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.session.ToolRoundEntity
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.ToolCallRecord
import io.zer0.muse.ui.chat.ChatStateAccessor
import io.zer0.muse.ui.chat.ChatTaskCardCoordinator
import io.zer0.muse.ui.taskcard.TaskCardData
import io.zer0.muse.ui.taskcard.TaskCardPhase
import io.zer0.muse.ui.taskcard.TaskStepStatus
import io.zer0.muse.util.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

/** Umbral de tiempo de espera de llamada de herramienta(2 minutos),Terminar después del tiempo de espera,Evite bloquear la salida de streaming. */
internal const val TOOL_TIMEOUT_MS = 120_000L

/**
 * The generic 2-minute deadline is too short for bounded phone agents: each visual decision has
 * its own 60-second timeout, and workflows may intentionally contain several bounded Node steps.
 * Derive a deadline from the declared bounds so the outer runner does not cancel valid work early.
 */
internal object ToolExecutionTimeoutPolicy {
    private const val UI_AGENT_VISION_TIMEOUT_MS = 60_000L
    private const val UI_AGENT_STEP_OVERHEAD_MS = 5_000L
    private const val UI_AGENT_SETUP_OVERHEAD_MS = 10_000L
    private const val WORKFLOW_STEP_OVERHEAD_MS = 10_000L
    private const val WORKFLOW_SETUP_OVERHEAD_MS = 20_000L
    private const val DEFAULT_NODE_SCRIPT_TIMEOUT_MS = 30_000L
    private const val MAX_NODE_SCRIPT_TIMEOUT_MS = 300_000L
    private const val MAX_WORKFLOW_STEPS = 20

    fun forTool(toolName: String, argumentsJson: String, baseTimeoutMs: Long): Long {
        val args = runCatching { AppJson.parseToJsonElement(argumentsJson) as? JsonObject }.getOrNull()
            ?: return baseTimeoutMs
        val derived = when (toolName) {
            "ui_agent" -> {
                val steps = args.intOrNull("max_steps")?.coerceIn(3, 30) ?: 15
                UI_AGENT_SETUP_OVERHEAD_MS + steps * (UI_AGENT_VISION_TIMEOUT_MS + UI_AGENT_STEP_OVERHEAD_MS)
            }
            "automation_workflow" -> workflowTimeout(args)
            else -> null
        }
        return derived?.let { maxOf(baseTimeoutMs, it) } ?: baseTimeoutMs
    }

    private fun workflowTimeout(args: JsonObject): Long? {
        val steps = when (val value = args["steps"]) {
            is kotlinx.serialization.json.JsonArray -> value
            is JsonPrimitive -> runCatching {
                AppJson.parseToJsonElement(value.content) as? kotlinx.serialization.json.JsonArray
            }.getOrNull()
            else -> null
        }?.takeIf { it.isNotEmpty() } ?: return null
        var total = WORKFLOW_SETUP_OVERHEAD_MS
        steps.take(MAX_WORKFLOW_STEPS).forEach { element ->
            val step = element as? JsonObject ?: return@forEach
            val action = step.stringOrNull("action")?.trim()?.lowercase().orEmpty()
            val stepTimeout = when (action) {
                "node_script" -> (step.longOrNull("timeoutMs") ?: DEFAULT_NODE_SCRIPT_TIMEOUT_MS)
                    .coerceIn(1_000L, MAX_NODE_SCRIPT_TIMEOUT_MS) + WORKFLOW_STEP_OVERHEAD_MS
                "wait", "virtual_wait" -> (step.longOrNull("durationMs") ?: 400L)
                    .coerceIn(50L, 10_000L) + WORKFLOW_STEP_OVERHEAD_MS
                else -> WORKFLOW_STEP_OVERHEAD_MS
            }
            total += stepTimeout
        }
        return total
    }

    private fun JsonObject.stringOrNull(name: String): String? = runCatching { this[name]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.intOrNull(name: String): Int? = stringOrNull(name)?.toIntOrNull()

    private fun JsonObject.longOrNull(name: String): Long? = stringOrNull(name)?.toLongOrNull()
}

/**
 * Without a known model window, this threshold only selects inline versus file-backed delivery.
 * It never caps or drops the tool's complete result.
 */
internal const val TOOL_OUTPUT_INLINE_FALLBACK_CHARS = 32 * 1024
private const val SMALL_TOOL_RESULT_INLINE_CHARS = 4 * 1024

/** La cantidad de caracteres de vista previa proporcionados con la referencia completa del archivo después de que se haya excedido el presupuesto de contexto restante para la ronda actual. */
internal const val TOOL_RESULT_PREVIEW_CHARS = 4 * 1024

/** Suggested character page size for reading a saved tool output. */
internal const val TOOL_OUTPUT_READ_PAGE_CHARS = 64 * 1024

/** ¿Cuánto tiempo se deben conservar los archivos de salida de la herramienta?(milisegundos),después de exceder [cleanupOldToolOutputs] Limpiar. */
internal const val TOOL_OUTPUT_RETENTION_MS = 24L * 60 * 60 * 1000

/** Subdirectorio de almacenamiento de archivos de salida de herramientas(Ubicado en filesDir),suministro [ToolOrchestrator] con [cleanupOldToolOutputs] Compartir. */
internal const val TOOL_OUTPUTS_DIR = "tool_outputs"

/**
 * Phase 3: Tiempo de espera de colocación del disco de salida de herramienta(milisegundos).
 *
 * La colocación es"El resultado completo se puede leer con read_file"camino de mejora,No es el resultado de la herramienta en sí;
 * tiempo de espera(o escribir excepción)Mantener el texto original completo en la memoria y mantener registros.,Nunca permita que el disco IO bloquee un bucle de herramienta indefinidamente.
 */
internal const val TOOL_OUTPUT_WRITE_TIMEOUT_MS = 5_000L

/**
 * Phase 3: Cambio de simultaneidad limitada de herramientas de solo lectura(interruptor constante,Desactivado de forma predeterminada = conservador).
 *
 * Mantiene la semántica de ejecución en serie del B-38 cuando está cerrado.(Ejecutar llamadas a herramientas una por una en la misma ronda,Cero cambios de comportamiento);
 * Presione cuando la herramienta esté abierta y toda la rueda sea de solo lectura [READ_ONLY_TOOL_MAX_PARALLELISM] Ejecución concurrente,
 * Los resultados todavía se reponen en el orden de llamada de herramienta original. escribir/Los efectos secundarios externos se serializan en todos los casos.
 * Los usuarios pueden activarlo o desactivarlo con un clic a través de paraleloReadOnlyTools en la "Función experimental"(ver [ExperimentsConfig.parallelReadOnlyTools]).
 *
 * Rectificación y revisión(2026-09-20):Previamente habilitado de forma predeterminada,Sin embargo, el tiempo de ejecución concurrente solo se ha probado de una sola manera y no hay verificación del estado del dispositivo.,
 * Es incompatible con el plan "conservador por defecto, habilitado explícitamente por el usuario",Por lo tanto, el interruptor real se cambia a
 * [ExperimentsConfig.parallelReadOnlyTools](Predeterminado false = de serie)control.
 * esta reparación:Esta constante estaba previamente codificada false,Estará en [shouldExecuteRoundInParallel] cortocircuito por adelantado,
 * Inundar directamente el interruptor experimental del usuario —— Ahora sólo significa“Funciones disponibles”(true),El interruptor real se devuelve al usuario.
 */
internal const val PARALLEL_READ_ONLY_TOOLS_ENABLED_DEFAULT = true

/** Phase 3: Límite de simultaneidad de herramientas de solo lectura(Simultaneidad limitada,Evite romper la red/Límite actual del lado del servidor). */
internal const val READ_ONLY_TOOL_MAX_PARALLELISM = 3

/**
 * Phase 3: Categoría de ejecución de herramienta — Determine si se permite la concurrencia con otras herramientas en la misma ronda.
 *
 * solo lectura/Las herramientas idempotentes no tienen efectos secundarios compartidos,Posible simultaneidad limitada;escribir/herramientas de efectos secundarios externos(y todas las herramientas desconocidas)
 * debe ser serial,Garantice el orden de las llamadas y la visibilidad de los efectos secundarios.
 */
internal enum class ToolExecCategory {
    /** solo lectura/idempotente:Es posible una concurrencia limitada. */
    READ_ONLY,

    /** escribir/efectos secundarios externos/desconocido:Debe estar serializado. */
    SERIAL,
}

/**
 * Phase 3: Lista blanca de herramientas de solo lectura(enumeración conservadora).
 *
 * Registre únicamente herramientas de consulta que seguramente no tendrán efectos secundarios externos;Herramienta no registrada(Contiene habilidades personalizadas/MCP/futuras nuevas herramientas)
 * Presione siempre [ToolExecCategory.SERIAL] Proceso,Evite efectos secundarios desconocidos y condiciones de carrera simultáneas.
 */
private val READ_ONLY_TOOL_NAMES: Set<String> =
    setOf(
        // Cálculo básico/consulta de estado
        "get_current_time", "calculator", "echo", "current_status", "get_token_usage",
        // red de solo lectura(Buscar / Rastrear / Clima / DNS)
        "web_search", "web_fetch", "get_weather", "get_public_ip", "ping_host", "dns_lookup", "translate",
        // Información del dispositivo/sistema
        "get_device_info", "get_battery_info", "get_network_info", "get_storage_info", "get_memory_info",
        "get_display_info", "get_cpu_info", "get_sensors_list", "get_foreground_app", "get_wifi_info",
        "get_bluetooth_devices", "get_brightness", "get_volume", "get_location",
        "get_contacts_count", "get_contacts_list", "get_recent_notifications", "screen_time",
        "calendar_today", "get_calendar_today", "list_installed_apps", "list_reminders",
        "list_stickers", "list_skills",
        // Archivo de solo lectura
        "read_file", "read_public_file", "list_public_files", "list_dir",
        // Memoria/Base de datos/Notas Sólo lectura
        "search_memory", "recall_experience", "resource_list", "resource_search", "resource_get",
        "quick_note_list", "quick_note_get", "quick_note_search",
        "scheduled_task_list", "scheduled_task_get_history",
        // v2.6.2: Solo lectura entre sesiones (hojear otras sesiones)
        "session_list", "session_read",
        // Asistencia informática puramente local
        "json_pretty", "hash_text", "generate_uuid", "random_number", "base64_decode", "url_decode",
        "generate_password",
        // pantalla de solo lectura(No incluye capturas de pantalla/clics/entradas ni otros efectos secundarios)
        "ui_get_page_info", "ui_get_current_app", "screen_read", "screen_current_app", "screen_permission_status",
    )

/** Phase 3: Determinar la categoría de ejecución de la herramienta.;Las herramientas desconocidas siempre están serializadas.([ToolExecCategory.SERIAL]). */
internal fun classifyToolExecution(toolName: String): ToolExecCategory =
    if (toolName in READ_ONLY_TOOL_NAMES) ToolExecCategory.READ_ONLY else ToolExecCategory.SERIAL

/**
 * v2.6.2(auditoría de usuario): Convierta el parámetro de herramienta JsonObject en un mapa para aprobación de gancho/parametrizado.
 *
 * Tiene el mismo origen que la ruta de análisis de ToolRegistry.executeFromJson (ambos pasan parseArgumentsLenient primero),
 * y conservar el tipo original JSON: cadena→String, booleano→Boolean, números→Long/Double,
 * Objetos anidados/matriz→JSON texto. La implementación anterior siempre toma .content para JsonPrimitive.
 * pon los numeros/Todos los valores booleanos se convierten en cadenas, lo que hace que la política de parámetros sea inconsistente con el comportamiento de la ruta de ejecución cuando se determina por tipo.
 */
internal fun toolArgsToMap(obj: JsonObject): Map<String, Any> = obj.mapValues { (_, v) ->
    when (v) {
        is JsonPrimitive -> when {
            v.isString -> v.content
            v.content == "true" -> true
            v.content == "false" -> false
            else -> v.content.toLongOrNull() ?: v.content.toDoubleOrNull() ?: v.content
        }
        else -> v.toString()
    }
}

/**
 * Phase 3: Implementación del disco de escritura predeterminado de salida de herramienta(IO Sobrescribir hilo).
 * Uso fijo de enlaces de producción.;Una sola prueba puede inyectar una escritura lenta/Lanzar sustitutos de error para tiempos de espera de validación deterministas y rutas de degradación.
 */
private val DEFAULT_TOOL_OUTPUT_WRITER: suspend (File, String) -> Unit = { file, content ->
    withContext(Dispatchers.IO) { file.writeText(content) }
}

/**
 * Tool-chain message count is no longer an application truncation limit.
 *
 * When the provider exposes a context budget, [toolChainTailToKeep] uses the measured
 * token budget to retain a safe tail. If the window is unknown, preserving the complete
 * chain lets the provider or the user's own API decide how much it can accept.
 */
internal const val MAX_TOOL_CHAIN_MESSAGES = Int.MAX_VALUE

/** v1.x: tareas simples(Ninguno task_plan)El número máximo predeterminado de rondas. */
internal const val DEFAULT_MAX_TOOL_ROUNDS = 10

/** v1.x: Límite superior absoluto para bucles de llamada de herramientas(Bolsillo de circulación anti-muertos),No importa cuántos pasos de task_plan haya, no se excederá este valor. */
internal const val MAX_TOOL_ROUNDS_HARD_CAP = 25

/** v2.x: Valor centinela para rondas ilimitadas(Se utiliza cuando se libera la configuración del usuario.;llamadas repetidas/atascado/La protección del presupuesto, como por ejemplo fallos consecutivos, no se ve afectada.). */
internal const val UNLIMITED_TOOL_ROUNDS = Int.MAX_VALUE

/** Los resultados de la herramienta retroceden antes del mensaje.,La cantidad máxima de tiempo que pueden tardar los registros de puntos de interrupción de limpieza. */
private const val PENDING_TOOL_CLEANUP_TIMEOUT_MS = 2_000L

/**
 * Parámetros de entrada para solicitudes de transmisión de LLM de ronda única.
 *
 * @param round ronda actual(empezar desde 1)
 * @param history El historial de conversaciones que se enviará en esta ronda.(Prefijo del sistema y resultados de herramientas incluidos.)
 * @param currentAssistantId El marcador de posición actual para el mensaje del asistente. id
 * @param builder assistant acumulador de texto
 * @param reasoningBuilder assistant reasoning/think acumulador
 */
data class StreamRoundParams(
    val round: Int,
    val history: List<UIMessage>,
    val currentAssistantId: Uuid,
    val builder: StringBuilder,
    val reasoningBuilder: StringBuilder,
    // v1.0.1 (P4): Recuento de reintentos de transmisión por ronda de invocación de herramienta,Se incrementa cuando se llama a streamRound de forma recursiva.
    //   El contador original se declara fuera de launchStream.,Compartido en todas las rondas de herramientas,Como resultado, las rondas siguientes no se pueden volver a intentar una vez agotada la primera ronda.
    //   Cada ronda es independiente después de pasar a los parámetros.,Y el reintento recursivo pasa. copy(retryCount = ...) aprobar.
    val retryCount: Int = 0,
    /**
     * v1.0.17: StreamInterrupted Logotipo de currículum inteligente — true Al omitir constructor/razonamientoBuilder clear().
     *
     * Escenarios aplicables:La red fue interrumpida después de recibir algún contenido.(StreamInterrupted),Vuelva a intentarlo después de la recuperación de la red.,
     * Vuelva a enviar el mensaje completo pero conserve parte del contenido mostrado,UI Solo agregar contenido nuevo(No vuelvas a la primera palabra.).
     *
     * Nota:Esta bandera solo controla si el acumulador se borra;B3-03 El contenido mostrado ha sido tratado como resumeFromText
     * inyectar en ChatService,Se utiliza como mensaje final del asistente al reintentar para permitir que el modelo continúe donde lo dejó.,Evite reconstruir desde cero.
     */
    val preservePartialContent: Boolean = false,
    /** Provider Conmutador de búsqueda de red nativo; cuando esté habilitada, la herramienta web_search local no se enviará en esta ronda. */
    val nativeWebSearch: Boolean = false,
    /**
     * Revisión de corrección (2.0 A-01): Forzar el uso del modelo principal para volver a ejecutar la ronda. — toolModel rueda(round>1)Si sale directamente
     * respuesta final(Ninguno toolCalls),Demuestra que esta ronda es en realidad"ronda de respuesta final",Por ChatViewModel con esta bandera
     * Volver a ejecutar una ronda de forma recursiva,Se garantiza que la respuesta final será generada por el modelo maestro. Después de configurar isToolRound, se determina que está en cortocircuito.,
     * La segunda ronda suplementaria ya no se activará.(Prevenir la recursividad infinita).
     */
    val forceMainModel: Boolean = false,
    /**
     * La ronda final después de que la herramienta falla continuamente: fuerce no exponer ninguna herramienta, deje que el modelo principal se base en el ya completado
     * TOOL El resultado es una respuesta final visible para el usuario.
     */
    val forceFinalResponse: Boolean = false,
)

/**
 * Resultados de una única ronda de solicitudes de transmisión de LLM.
 */
sealed class StreamRoundResult {
    /**
     * La transmisión finalizó exitosamente.
     *
     * @param assistantMessage El mensaje del asistente finalizado.(Contiene toolCalls,si hay)
     * @param hasToolCalls Si se generó una llamada de herramienta en esta ronda
     * @param contentLength El número de caracteres en el texto asistente de esta ronda.(para estadísticas de rendimiento)
     * @param firstTokenTime Hora del sistema a la que llega el primer token,Si no ha llegado es 0
     * @param toolCallsTruncated Los argumentos para esta ronda de llamadas a herramientas se truncan por el límite superior de la longitud de salida.
     *   Los parámetros incompletos no se analizan ni ejecutan.,Haga que el orquestador complete un error explícito para cada llamada tool result,
     *   Deje que el modelo vea el motivo en el mismo contexto y vuelva a emitir la llamada completa.
     */
    data class Success(
        val assistantMessage: UIMessage,
        val hasToolCalls: Boolean,
        val contentLength: Int,
        val firstTokenTime: Long,
        val citationUrls: List<String> = emptyList(),
        val toolCallsTruncated: Boolean = false,
    ) : StreamRoundResult()

    /**
     * La transmisión falló(red/Limitación de corriente/API Errores etc,y ha agotado los reintentos automáticos).
     *
     * @param type Tipo de error
     * @param message Mensajes de error fáciles de usar
     * @param partialContent Parte del texto recibido.
     * @param partialReasoning Parte recibida reasoning
     */
    data class Error(
        val type: ChatErrorType,
        val message: String,
        val partialContent: String? = null,
        val partialReasoning: String? = null,
    ) : StreamRoundResult()
}

/**
 * v2.x: cadena de herramientas"reconstrucción local"Marca visible después.
 *
 * Visible para el usuario (puede ver que este párrafo se ha ordenado en el flujo de conversación) e informar al modelo al mismo tiempo."Se han descartado las llamadas de herramientas y los resultados más antiguos.",
 * Evite que piense que esos resultados siguen ahí y siga haciendo referencia a ellos.
 */
internal const val TRUNCATED_TOOL_CHAIN_MARKER: String =
    "(Las llamadas a herramientas anteriores y los resultados se han organizado y omitido.,Sólo se conservan los más recientes; si necesita volver a obtenerlos, llame nuevamente a la herramienta correspondiente.)"

/**
 * v2.x: Determina cuántos mensajes finales debe retener la cadena de herramientas (0 significa descartar todo).
 *
 * Determine únicamente basándose en el presupuesto de token conocido:
 *  1. **ventana desconocida**: Descartar el historial de herramientas sin personalizar la cantidad de elementos por aplicación;
 *  2. **Presupuesto simbólico conocido**: Los tokens acumulados exceden [budgetTokens] , presione"Deje la mitad del presupuesto para el último final"
 *     Calculo que todavía quedan algunos. —— protegerse contra"No hay muchas rondas, pero sí grandes resultados para cada entrada."(Registros, archivos grandes, textos largos de páginas web).
 *
 * Esta es una reconstrucción local sin solicitudes: sin ajuste del modelo, sin dependencia de la red y, en el peor de los casos, se puede enviar la ronda actual de solicitudes.
 *
 * @param chainSize Número de mensajes de la cadena de herramientas (excluyendo el contexto inicial)
 * @param chainTokens Estimación del token de la cadena de herramientas ([budgetTokens] <= 0 El que llama pasa 0)
 * @param budgetTokens Presupuesto de contexto disponible para esta ronda;<= 0 Indica que el anfitrión no puede proporcionarlo y solo se determina la cantidad de elementos.
 */
internal fun toolChainTailToKeep(chainSize: Int, chainTokens: Int, budgetTokens: Int): Int {
    val byCount = minOf(chainSize, MAX_TOOL_CHAIN_MESSAGES)
    val noBudgetSignal = budgetTokens <= 0 || chainTokens <= 0
    val withinHalfBudget = chainTokens <= budgetTokens / 2
    // prensa**Tamaño de barra promedio medido**Valor estimado, no fijo: resultado único muy grande (registro de cientos de miles de tokens)
    // Usando un token/artículo fijo de 50 para calcular, obtendremos"Todavía quedan miles"La conclusión absurda fue que la ventana seguía rota.
    val perMessageTokens = if (chainSize > 0) (chainTokens / chainSize).coerceAtLeast(1) else 1
    val byBudget = (budgetTokens / 2) / perMessageTokens
    return when {
        chainSize <= 0 -> 0
        noBudgetSignal || withinHalfBudget -> byCount
        // Deja al menos 1: Perderlos todos hará que el modelo sea completamente invisible."¿Qué pasó en esa llamada de hace un momento?", peor que dejar uno más.
        else -> minOf(byCount, maxOf(1, byBudget))
    }
}

/** Share the current tool-result budget while reserving room for the assistant's answer. */
internal fun toolOutputInlineBudgetTokens(contextBudgetTokens: Int, currentHistoryTokens: Int, toolCallCount: Int): Int? {
    if (contextBudgetTokens <= 0) return null
    val answerReserve = maxOf(1_024, contextBudgetTokens / 8)
    val remaining = (contextBudgetTokens - currentHistoryTokens - answerReserve).coerceAtLeast(0)
    return remaining / toolCallCount.coerceAtLeast(1)
}

internal fun canInlineToolOutput(outputLength: Int, estimatedTokens: Int, inlineBudgetTokens: Int?): Boolean = when {
    outputLength <= SMALL_TOOL_RESULT_INLINE_CHARS -> true
    inlineBudgetTokens == null -> outputLength <= TOOL_OUTPUT_INLINE_FALLBACK_CHARS
    else -> estimatedTokens <= inlineBudgetTokens
}

/**
 * La devolución de llamada del host para el bucle de llamada de la herramienta.
 *
 * por [ChatViewModel] darse cuenta,Responsable de solicitudes de streaming reales,UI Actualizaciones, aprobaciones de herramientas, etc.
 * [ToolOrchestrator] Solo organiza rondas,Sin manipulación directa de la interfaz de usuario.
 */
interface ToolLoopHost {
    /**
     * Ejecute una ronda de solicitudes de transmisión de LLM y devuelva los resultados.
     *
     * Necesidades de implementación:
     *  - llamar chatService.streamChat
     *  - recoger ContentDelta / ReasoningDelta / ToolCallDelta / ImageDelta
     *  - actualizaciones en tiempo real UI(acelerador)
     *  - Manejo de reintentos automáticos NETWORK/RATE_LIMIT
     *  - finalize assistant noticias(mood/reflection/think Extracto)
     *  - La devolución contiene/mensaje asistente sin herramientaLlamadas
     */
    suspend fun streamRound(params: StreamRoundParams): StreamRoundResult

    /**
     * Solicitar llamada a la herramienta de aprobación del usuario,Cuelgue hasta que el usuario tome una decisión.
     *
     * @param args v1.0.53: Parámetros completos de la herramienta,Para la determinación de permisos parametrizada(con valor predeterminado,Las implementaciones antiguas no se ven afectadas)
     */
    suspend fun requestToolApproval(
        toolName: String,
        toolCallId: String,
        argsPreview: String,
        args: Map<String, Any?> = emptyMap(),
    ): ToolApprovalState

    /**
     * Devolución de llamada cuando ocurre un error no fatal dentro del ciclo de llamada de la herramienta(Si la ubicación de la base de datos falla).
     */
    fun onToolLoopError(type: ChatErrorType, message: String, recoverable: Boolean = true)

    /**
     * v1.x: Devolución de llamada cuando una sola herramienta comienza a ejecutarse(Se puede utilizar para indicaciones de progreso de la interfaz de usuario./Iniciar sesión).
     *
     * Implementación vacía predeterminada,Anulaciones opcionales del host,Para notificaciones de progreso detalladas.
     */
    fun onToolStart(toolCallId: String, toolName: String) {}

    /**
     * v2.6.7: Canales de dispositivo actualmente listos(Accesibilidad/Shell/Root/Uso/Aviso de derechos de uso…).
     *
     * El orquestador lo utiliza para realizar una verificación previa antes de ejecutar la herramienta.,Bloquear "llamadas condenadas" antes de la ejecución,
     * Y proporcione pautas ejecutables sobre "qué abrir en la configuración" —— En lugar de dejar que la herramienta golpee la pared y devuelva una frase
     * Errores que ni el modelo ni el usuario pueden entender.
     *
     * Devolver un conjunto vacío de forma predeterminada significa "el anfitrión no proporcionó información del canal",Saltar verificación previa en este momento(mantener el comportamiento original),
     * Evite hosts que no implementen este método(Subagentes, tareas programadas, etc.)Se calculó erróneamente que todos los canales no están disponibles.
     */
    suspend fun availableDeviceChannels(): Set<ToolRequirements.Channel>? = null

    /**
     * v1.x: Devolución de llamada final de ejecución de una sola herramienta.
     *
     * @param success ¿Tiene éxito?(Juicio basado en los resultados de la herramienta.)
     * @param durationMs Tiempo de ejecución de la herramienta(milisegundos,Incluye aprobación pendiente)
     * Implementación vacía predeterminada,Anulación opcional del host.
     */
    fun onToolFinish(toolCallId: String, toolName: String, success: Boolean, durationMs: Long) {}

    /**
     * v2.x: El presupuesto de token de contexto disponible para esta ronda (0 O un número negativo significa que el anfitrión no puede proporcionarlo y el orquestador no determina el token).
     *
     * Por qué lo necesita: el truncamiento de la cadena de herramientas antes solo se veía**Número de artículos**([MAX_TOOL_CHAIN_MESSAGES]),
     * Sin embargo, algunos resultados de herramientas extremadamente grandes pueden hacer estallar la ventana dentro del límite numérico. —— No hay posibilidad de compresión durante la generación.
     * El anfitrión conoce la ventana y la reserva del modelo actual. Sólo se solicita una vez y no depende de la implementación del host.
     *
     * De forma predeterminada, se devuelve 0 (no se realiza ningún juicio), lo que garantiza que no es necesario cambiar la implementación existente.
     */
    fun contextBudgetTokens(): Int = 0

    /**
     * Toma las palabras (interjecciones) emitidas por el usuario mientras se ejecuta la cadena de herramientas.
     *
     * Se llama al orquestador una vez "después de que se hayan completado todos los resultados de esta ronda de herramientas y antes de que se envíe la siguiente ronda de solicitudes", y cada texto devuelto se
     * Se adjunta después del resultado de la herramienta como un nuevo mensaje de USUARIO. ejecutando/Las herramientas que se acaban de ejecutar no se ven afectadas:
     * No hay reversión ni cancelación y el resultado aún se repone. La interrupción solo cambia el contenido visible en la siguiente ronda de solicitudes.
     *
     * Devuelve una lista vacía por defecto —— El comportamiento de los hosts (subagentes, tareas programadas, etc.) que no implementan este método permanece completamente sin cambios:
     * No se generan mensajes adicionales cuando no hay interrupción,history Igual que antes del cambio.
     */
    suspend fun drainSteeringMessages(): List<String> = emptyList()
}

/**
 * Parámetros para el bucle de llamada de herramienta.
 *
 * @param sessionId sesión actual id
 * @param initialAssistantId Mensaje inicial del asistente de marcador de posición id
 * @param baseHistorySize Tamaño de contexto inicial no truncable(system prefix + historia)
 * @param maxRounds Rondas máximas de herramientas;0=Ilimitado,entero positivo=Límite superior fijo(Resuelto por la persona que llama desde la configuración)
 * @param tools Lista de definiciones de herramientas expuestas a LLM en esta ronda
 * @param skillMap habilidad habilitada id → SkillEntity mapeo
 * @param model Modelos realmente utilizados
 * @param providerConfig Configuración real del proveedor utilizada
 * @param temperature temperatura
 * @param maxTokens Número máximo de fichas
 * @param reasoningLevel nivel de razonamiento
 * @param webSearchEnabled Si se debe habilitar la búsqueda en Internet(Para extracción de resultados de la herramienta web_search citation URL)
 * @param experiments Configuración experimental(Solo para registros de depuración)
 * @param assistant Configuración actual del asistente(Opcional,para delegado_agente, etc.)
 */
data class ToolLoopParams(
    val sessionId: String,
    /** F-12: enlace unificado id(de StreamRunState.traceId),Las herramientas realizan auditorías y transporte de registros. */
    val traceId: String = "",
    val initialAssistantId: Uuid,
    val baseHistorySize: Int,
    val maxRounds: Int,
    val tools: List<ToolDefinition>,
    val skillMap: Map<String, SkillEntity>,
    /** Per-turn immutable exposure and execution routing decision. Null keeps legacy callers compatible. */
    val routeSnapshot: ToolRouteSnapshot? = null,
    val model: Model?,
    val providerConfig: ProviderConfig?,
    val temperature: Float?,
    val maxTokens: Int?,
    val reasoningLevel: ReasoningLevel,
    val webSearchEnabled: Boolean = false,
    /** Cuando utilice la búsqueda de red nativa del proveedor, desactive la herramienta web_search local para evitar la superposición de los dos conjuntos de búsquedas. */
    val nativeWebSearch: Boolean = false,
    val experiments: ExperimentsConfig = ExperimentsConfig(),
    val assistant: AssistantEntity? = null,
    /** B7-04: La primera ronda de ajustes preestablecidos ha producido el texto.(Reanudar la escritura desde el punto de interrupción cuando continúe la compilación). */
    val initialBuilderContent: String = "",
    /** B7-04: La primera ronda de ajustes preestablecidos ha producido razonamientos. */
    val initialReasoningContent: String = "",
    /** Nueva propiedad de la ronda de enlaces; cuando está vacío, sólo se conserva el comportamiento del vínculo anterior. */
    val turnId: String = "",
    /** La identidad unificada generada actualmente; una identidad compatible generada por el orquestador cuando la antigua persona que llama está vacía. */
    val generationIdentity: io.zer0.muse.session.GenerationIdentity? = null,
    /** Inyectado por el host de la sesión principal y utilizado por herramientas sensibles al aislamiento como search_memory. */
    val toolExecutionContext: ToolExecutionContext? = null,
    /** When false, tool calls are recorded as paused and never execute real side effects. */
    val allowToolExecution: Boolean = true,
)

/**
 * Mensaje de error para el bucle de llamada de herramienta.
 */
data class ToolLoopError(
    val type: ChatErrorType,
    val message: String,
    val partialContent: String? = null,
    val partialReasoning: String? = null,
)

/**
 * Phase 3: Motivo de terminación estructurado para el bucle de llamada de herramienta.
 *
 * con [ToolLoopResult.success] relación:`success` solo en [COMPLETED] el tiempo es true;
 * Límite redondo / Llamadas repetidas estancadas / Fallos consecutivos / Presupuesto agotado / Llamadas a herramientas no válidas / Errores de transmisión
 * Todos salen anormalmente,La persona que llama distingue"respuesta normal"con"Disyuntor".
 */
enum class ToolLoopTerminationReason {
    /** Terminar normalmente:LLM Producir respuesta final,No hay llamadas de herramientas pendientes. */
    COMPLETED,

    /** Límite de ronda de llamada de herramienta alcanzado(Calculado por ComputeMaxRounds). */
    ROUND_LIMIT,

    /** Lo mismo para múltiples rondas consecutivas tool_call,Juicio de estancamiento(fusible). */
    REPEATED_TOOL_CALLS,

    /** El fallo continuo de la herramienta alcanza el umbral(fusible). */
    CONSECUTIVE_FAILURES,

    /** turn Llamadas de nivel/presupuesto de tiempo agotado(fusible). */
    BUDGET_EXHAUSTED,

    /** Todas las llamadas a herramientas del modelo en esta ronda no son válidas después de la limpieza.,El orquestador termina después de inyectar la pista. */
    INVALID_TOOL_CALLS,

    /** Error de solicitud de transmisión(Error de red/límite actual/API),El bucle falla y sale. */
    STREAM_ERROR,

    /** La herramienta generó con éxito una respuesta final basada en los resultados existentes después de fallos consecutivos. */
    COMPLETED_AFTER_TOOL_FAILURES,
}

/**
 * El resultado de un bucle de llamada de herramienta.
 */
data class ToolLoopResult(
    val finalAssistantId: Uuid,
    val round: Int,
    val totalToolCallCount: Int,
    val totalCharCount: Int,
    val firstTokenTime: Long,
    val citationUrls: List<String>,
    /**
     * Ya sea para completar la respuesta final.
     *
     * Phase 3 solo si [terminationReason] == [COMPLETED] o
     * [COMPLETED_AFTER_TOOL_FAILURES] el tiempo es true;
     * El límite de ronda/llamadas repetidas/agotamiento presupuestario y otras salidas inconclusas son falsas;
     * Una ronda que falla consecutivamente pero genera exitosamente una respuesta de cierre está en estado de finalización.
     */
    val success: Boolean,
    val error: ToolLoopError? = null,
    /** El mensaje final del asistente cuando se completa el ciclo de la herramienta; nulo por pura interrupción del circuito y sin respuesta de cierre. */
    val finalAssistantMessage: UIMessage? = null,
    /** La rueda de herramientas estructurada de este ciclo de herramientas se envía una vez con el mensaje final. */
    val toolRounds: List<ToolRoundEntity> = emptyList(),
    /** Phase 3: Motivo de rescisión estructurada;con [success] consistente(success == terminationReason == COMPLETED). */
    val terminationReason: ToolLoopTerminationReason = ToolLoopTerminationReason.COMPLETED,
)

/**
 * Phase 2: La herramienta llama al orquestador de bucles.
 *
 * poner [ChatViewModel] medio grueso `while (hasToolCalls && round < maxToolRounds)` Saca el ciclo,
 * Responsabilidades:
 *  - Controle múltiples rondas de procesos de llamada de herramientas(Truncamiento, límite de ronda, parada anticipada por fallos consecutivos)
 *  - Ejecutar múltiples llamadas a herramientas en paralelo y completar los resultados.
 *  - Tarjeta de tareas de mantenimiento(TaskCard)Estado
 *  - Extracto de los resultados de la herramienta web_search citation URL,Como referencia en el mensaje final del asistente.
 *
 * Solicitudes de transmisión verdaderas,UI Actualizaciones y herramientas aprobadas [ToolLoopHost] La devolución de llamada se realiza a ChatViewModel.
 */
@Suppress("LongParameterList")
/**
 * P2-33: "Puerta de resultados" para bloquear los tiempos de espera de la herramienta— La estructura garantiza que no se puedan implementar resultados tardíos después del tiempo de espera.
 *
 * fondo:herramientas de bloqueo(WebView / bloqueando IO)RunBlocking usado internamente para puentear,[Future.cancel] solo puedo
 * Enviar una señal de interrupción;Las llamadas que ignoran las interrupciones seguirán finalizándose y produciendo resultados. En la implementación anterior, la rama de tiempo de espera se abandona directamente.
 * `future.get()`,El resultado de llegar tarde es simplemente "nadie leyó",Cualquier conexión posterior de este futuro/devolución de llamada al punto de escritura de estado
 * Cualquier cambio reintroducirá la contaminación.(El resultado sobrescribe la TaskCard/mensaje después del tiempo de espera / toolCallRecord).
 *
 * Esta puerta converge la "liberación de resultados" en un CAS: el tiempo de espera primero [abandon],Las publicaciones que llegan después se rechazan y se cuentan.,
 * El orquestador solo lee valores de la puerta. —— Llegar tarde no sólo significa que nadie lo lea,Y no puedo escribirlo.
 */
internal class ToolResultGate(
    /** Devolución de llamada cuando se descartan resultados tardíos(El orquestador registra esto/contar,La prueba única afirma basada en esto). */
    private val onLateDrop: () -> Unit = {},
) {
    private val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
    private val published = java.util.concurrent.atomic.AtomicBoolean(false)
    private val result = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /** No válido después del tiempo de espera(idempotente):después [publish] Rechace siempre. */
    fun abandon() {
        abandoned.set(true)
    }

    /** Si la ejecución ha sido cancelada por tiempo de espera. */
    val isAbandoned: Boolean get() = abandoned.get()

    /**
     * Resultados publicados(Llamada al hilo de ejecución):Si no se invalida, solo se acepta y devuelve el primer resultado. true;
     * anulado(tiempo de espera)Descartar resultados, diagnósticos de devolución de llamada y devolver falso.
     */
    fun publish(value: String): Boolean {
        if (abandoned.get()) {
            onLateDrop()
            return false
        }
        if (!published.compareAndSet(false, true)) return false
        // La liberación y el tiempo de espera pueden ser simultáneos:Si ha sido invalidado después de su publicación,El valor también es ilegible.(valueOrNull Revisión)
        result.set(value)
        return !abandoned.get()
    }

    /** Valor de lectura del orquestador:ha sido invalidado(tiempo de espera)regresar cuando null,Los resultados tardíos nunca se filtrarán. */
    fun valueOrNull(): String? = if (abandoned.get()) null else result.get()
}

class ToolOrchestrator(
    private val toolRegistry: ToolRegistry,
    private val skillRepository: SkillRepository,
    private val skillExecutor: SkillExecutor,
    private val assistantRepository: AssistantRepository,
    private val sessionRepository: SessionRepository,
    // v1.x: La inyección de contexto se utiliza para colocar la salida de herramientas súper largas en el disco. filesDir/tool_outputs/,
    // Deje que LLM lea el contenido completo a pedido a través de la herramienta read_file.
    private val context: Context,
    // P1-1: Hook Registro — Llamado en cada etapa de la invocación de la herramienta. ToolLifecycleHook
    private val hookRegistry: io.zer0.muse.hook.HookRegistry? = null,
    // P2-4: Registrador de auditoría(Se registra cuando se aprueban y lanzan las herramientas.).
    private val auditLogger: AuditLogger? = null,
    // F-08: Agent Run Libro mayor de memoria de recibos(Cada vez que la herramienta realiza una escritura,Contiene cadena de llamadas parentRunId).
    private val agentRunTracker: io.zer0.muse.data.audit.AgentRunTracker? = null,
    // v1.x: Registro de instancias de navegador a nivel de sesión(Independiente por sesión WebView).
    private val browserManagerRegistry: BrowserManagerRegistry = BrowserManagerRegistry(context),
    // R-TEST-10: Se puede inyectar el tiempo de espera de la herramienta,Valor predeterminado de producción 2 minutos
    private val toolTimeoutMs: Long = TOOL_TIMEOUT_MS,
    // v1.x: Registro unificado de recursos de ejecución de herramientas para cancelación por sesión y diagnóstico de resultados tardíos.
    private val executionRegistry: io.zer0.muse.session.SessionExecutionRegistry? = null,
    /**
     * Phase 3: Cambio de simultaneidad limitada de herramientas de solo lectura(Habilitado por defecto = Escala de grises;Las pruebas pueden inyectar falso para verificar la ruta en serie.).
     *
     * Después de abrir,Solo si todas las herramientas de la misma ronda son de solo lectura(lista blanca,y no enrutado a skill)Sólo presione
     * [READ_ONLY_TOOL_MAX_PARALLELISM] Simultaneidad limitada;incluyendo escritura/Las herramientas de efectos secundarios se serializan a lo largo de la ronda.
     * Los usuarios pueden utilizar la "Función experimental" [ExperimentsConfig.parallelReadOnlyTools] Un clic para cerrar.
     */
    private val parallelReadOnlyToolsEnabled: Boolean = PARALLEL_READ_ONLY_TOOLS_ENABLED_DEFAULT,
    /** Phase 3: Tiempo de espera de colocación del disco de salida de herramienta(Las pruebas pueden inyectar valores más cortos.). */
    private val toolOutputWriteTimeoutMs: Long = TOOL_OUTPUT_WRITE_TIMEOUT_MS,
    /** Phase 3: Implementación del disco de escritura de salida de herramienta(Las pruebas pueden inyectar escrituras lentas/Lanzar el sustituto equivocado). */
    private val toolOutputWriter: suspend (File, String) -> Unit = DEFAULT_TOOL_OUTPUT_WRITER,
) {
    private val routeGuard = ToolRouteExecutionGuard(toolRegistry)

    private companion object {
        const val TAG = "ToolOrchestrator"

        /**
         * F-12/B-28: Desensibilización de la auditoría — Elimina la parte de consulta de la URL del texto.
         * Prevenir parámetros de herramienta/La URL del resultado contiene parámetros confidenciales, como la clave API, y se escribe en el recibo de auditoría.
         * mantener solo el acuerdo+Anfitrión+camino;query/fragment Desechar siempre.
         */
        private val URL_QUERY_SANITIZER = Regex("""(https?://[^\s"'\]\}]+)(\?[^\s"'\]\}]*)?(#\S*)?""")

        /** v1.x: Nombre de la herramienta del navegador(Enrutamiento por sesión a independiente BrowserManager). */
        private val BROWSER_TOOL_NAMES =
            setOf(
                BrowserAutomationTool.TOOL_NAVIGATE,
                BrowserAutomationTool.TOOL_CLICK,
                BrowserAutomationTool.TOOL_TYPE,
                BrowserAutomationTool.TOOL_EXTRACT,
                BrowserAutomationTool.TOOL_SCROLL_BOTTOM,
                BrowserAutomationTool.TOOL_GET_HTML,
                BrowserAutomationTool.TOOL_SNAPSHOT,
                // v2.6.0: herramienta de recorte —— deja que el modelo“mira”Representación de página
                BrowserAutomationTool.TOOL_SCREENSHOT,
            )
    }

    /**
     * F-12/B-28: Eliminar todas las URL del texto query/fragment(ver [URL_QUERY_SANITIZER]).
     * El texto que no es URL se devuelve sin cambios.
     */
    private fun sanitizeUrlQuery(text: String): String = URL_QUERY_SANITIZER.replace(text) { match -> match.groupValues[1] }

    /**
     * v2.6.0: Extraiga capturas de pantalla de los resultados de la herramienta JSON(Base64 PNG).
     *
     * Sólo si:① Éxito de la herramienta;② El resultado es un objeto JSON;③ Contiene campos acordados [BrowserAutomationTool.SCREENSHOT_RESULT_FIELD]
     * Sólo entonces devuelve un valor no nulo. De lo contrario, devuelve nulo.
     */
    private fun extractScreenshotBase64(toolResult: String, isSuccess: Boolean): String? {
        if (!isSuccess) return null
        return runCatching {
            if (!toolResult.contains("\"${BrowserAutomationTool.SCREENSHOT_RESULT_FIELD}\"")) {
                null
            } else {
                AppJson.decodeFromString(JsonObject.serializer(), toolResult)[BrowserAutomationTool.SCREENSHOT_RESULT_FIELD]
                    ?.let { it as? JsonPrimitive }
                    ?.content
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /**
     * F-07: Máquina de estados de ejecución de herramientas.
     *  - [SUCCESS]: Ejecutado con éxito;
     *  - [FAILED]: La ejecución falló(Error de herramienta/Aprobación rechazada/tiempo de espera/Devolución de excepción);
     *  - [INTERRUPTED]: Ejecución interrumpida por streaming(usuario detenido/Cambio de sesión),Resultados incompletos;
     *  - [APPROVAL_PENDING]: Esperando la aprobación del usuario,El bucle de construcción se bloquea.
     */
    enum class ToolExecStatus { SUCCESS, FAILED, DENIED, INTERRUPTED, APPROVAL_PENDING, TIMED_OUT, CANCELLED }

    /**
     * Phase 3: Estado final de ejecución de la herramienta → Mapeo del estado de los pasos de la tarjeta de tareas.
     *
     * TIMED_OUT / CANCELLED ya no está doblado FAILED,Por lo tanto, la tarjeta de tareas puede presentar el verdadero estado final.
     * (Tanto la barra de pasos contraída como la fila de pasos expandida mostrarán el tiempo de espera en consecuencia./cancelar icono).
     */
    private fun ToolExecStatus.toTaskStepStatus(): TaskStepStatus = when (this) {
        ToolExecStatus.SUCCESS -> TaskStepStatus.SUCCESS
        ToolExecStatus.FAILED -> TaskStepStatus.FAILED
        // El rechazo activo del usuario no es un fracaso:La herramienta no se ejecutó y no se informó ningún error.,El mismo nivel que "cancelar" es más práctico.
        ToolExecStatus.DENIED -> TaskStepStatus.CANCELLED
        ToolExecStatus.INTERRUPTED -> TaskStepStatus.CANCELLED
        ToolExecStatus.CANCELLED -> TaskStepStatus.CANCELLED
        ToolExecStatus.TIMED_OUT -> TaskStepStatus.TIMED_OUT
        ToolExecStatus.APPROVAL_PENDING -> TaskStepStatus.RUNNING
    }

    private data class ToolExecResult(
        val idx: Int,
        val tc: ToolCall,
        val finalToolResult: String,
        val isSuccess: Boolean,
        /** v1.x: Resultados mostrados a los usuarios.(Fracaso sin instrucciones de LLM),null revertir [finalToolResult]. */
        val displayResult: String? = null,
        /** F-07: Estado de ejecución(Predeterminado SUCCESS,falló/Etiquetado explícito de puntos de ruptura.). */
        val status: ToolExecStatus = ToolExecStatus.SUCCESS,
        /** F-08: Tiempo de ejecución de la herramienta(ms),Proporciona el recibo de AgentRunRecord. */
        val durationMs: Long = 0L,
        val startedAt: Long = 0L,
        val finishedAt: Long = 0L,
        val executionId: String = "",
        /**
         * v2.6.0: Captura de pantalla de la salida de la herramienta(Base64 PNG,Ninguno dato: prefijo).
         * Sólo la herramienta de captura de pantalla del navegador tiene valor;La persona que llama lo agregará a la conversación como un mensaje de usuario con una imagen.,
         * Haz que el modelo sea realmente“ver”Representación de página(El resultado de la herramienta en sí no puede contener imágenes.).
         */
        val screenshotBase64: String? = null,
    )

    /**
     * Ejecute el ciclo de llamada de herramienta hasta que LLM ya no llame a la herramienta, alcance el límite de ronda o se complete después de fallas consecutivas.
     * Una ronda de respuesta final sin herramientas.
     *
     * @param params Parámetros de bucle
     * @param conversationHistory Historial de conversaciones variables(tool_calls/tool message se agregará en este método)
     * @param host Devolución de llamada del anfitrión
     */
    suspend fun runLoop(
        params: ToolLoopParams,
        conversationHistory: MutableList<UIMessage>,
        host: ToolLoopHost,
        accessor: ChatStateAccessor,
        taskCardCoordinator: ChatTaskCardCoordinator,
    ): ToolLoopResult {
        var round = 0
        var currentAssistantId = params.initialAssistantId
        var firstTokenTime = 0L
        val citationUrls = mutableListOf<String>()
        val toolRounds = mutableListOf<ToolRoundEntity>()
        var hasToolCalls = true
        var forceFinalResponse = false
        var nativeFallbackUsed = false
        var finalAssistantMessage: UIMessage? = null
        // A-07: Motivos de salida anormales(atascado/fracasos consecutivos),Copia para mensaje de cierre
        var abortReason: String? = null
        // Phase 3: Motivo de rescisión estructurada — Cada ruta de salida está etiquetada explícitamente,La línea final al final del ciclo es ROUND_LIMIT.
        var terminationReason: ToolLoopTerminationReason? = null

        // M3.3: presupuesto unificado/fuente de estado de parada — ToolExecutionPolicy Tapa redonda de gestión/Número de llamadas/fracasos consecutivos/Huellas dactilares duplicadas/Presupuesto de tiempo.
        // v2.x: El límite de ronda proviene de la configuración del usuario.(resolveToolRoundLimit:0=Ilimitado,entero positivo=Límite superior fijo),
        // escritura de resultados execPolicy.maxRounds,La variable local independiente maxRounds ya no se mantiene dentro del bucle.
        val execPolicy =
            ToolExecutionPolicy(
                initialMaxRounds = resolveToolRoundLimit(params.maxRounds),
            )
        val roundLimitLabel = if (execPolicy.maxRounds == UNLIMITED_TOOL_ROUNDS) "∞" else "${execPolicy.maxRounds}"
        val executionIdentity =
            params.generationIdentity ?: io.zer0.muse.session.GenerationIdentity(
                sessionId = params.sessionId,
                turnId = params.turnId.ifBlank { "turn-${params.traceId.ifBlank { currentAssistantId.toString() }}" },
                generationId = params.traceId.ifBlank { "generation-$currentAssistantId" },
                streamId = "stream-${params.traceId.ifBlank { currentAssistantId.toString() }}",
            )
        Logger.i(TAG, "Agent Loop empezar | sessionId=${params.sessionId} | rondas máximas iniciales: $roundLimitLabel")

        // Presione cada ronda"historia actual"Calcule la ocupación del token (para el presupuesto de producción en línea). Sólo quedan dos récords más en cada ronda de la historia.
        // Después de compartir una caché de recuento por instancia, solo las dos recién agregadas se contarán a partir de la segunda ronda y ya no se volverá a ejecutar toda la BPE.
        val loopTokenCache = io.zer0.muse.util.MessageTokenCache()

        while ((hasToolCalls || forceFinalResponse) && round < execPolicy.maxRounds) {
            round++
            val stepStartedAt = System.currentTimeMillis()
            Logger.d(TAG, "Agent Loop step $round/$roundLimitLabel empezar | sessionId=${params.sessionId}")

            // M3.2/P2-7: El presupuesto restante se controla uniformemente al comienzo de cada ronda.(llamadas totales/Caracteres de salida/fracasos consecutivos/tiempo),
            // Cuando algún elemento se agote, se finalizará con anticipación y se inyectará una copia visible.,No más viajes de ida y vuelta de modelos desperdiciados que seguramente serán interceptados.
            // Después de fallos consecutivos, se seguirá enviando una ronda de solicitudes de cierre sin herramientas; la herramienta ya no se ejecutará en esta ronda.
            // No puede ser bloqueado por el presupuesto fallido consecutivo que acaba de alcanzar.
            val roundBudget =
                if (forceFinalResponse) {
                    ToolExecutionPolicy.Decision(allowed = true)
                } else {
                    execPolicy.checkRoundBudget()
                }
            if (!roundBudget.allowed) {
                abortReason = roundBudgetStopText(roundBudget.reason)
                terminationReason = ToolLoopTerminationReason.BUDGET_EXHAUSTED
                Logger.w(
                    TAG,
                    "Agent Loop Presupuesto redondo agotado,terminación anticipada | sessionId=${params.sessionId} | " +
                        "reason=${roundBudget.reason} | ${roundBudget.detail} | " +
                        "traceId=${params.traceId} | Llamada ejecutada=${execPolicy.executedCalls}",
                )
                break
            }

            // C1-2: Truncado cuando la cadena de herramientas es demasiado larga,Mantener contexto inicial + cadena de herramientas reciente.
            // v2.x: Además del número de artículos, aumente **token presupuesto**juicio —— Algunos resultados de herramientas muy grandes pueden estar dentro del límite numérico
            //   volar la ventana,No hay posibilidad de compresión durante la generación. Este nivel es una reconstrucción local sin solicitudes.:
            //   Deseche la cadena de herramientas vieja y conserve solo la cola más reciente.,E inserte marcadores visibles para indicarle al modelo que su historial ha sido ordenado.
            val toolChainSize = conversationHistory.size - params.baseHistorySize
            val budget = host.contextBudgetTokens()
            val chainTokens =
                if (budget > 0 && toolChainSize > 0) {
                    TokenEstimator.estimate(conversationHistory.subList(params.baseHistorySize, conversationHistory.size))
                } else {
                    0
                }
            val keepTailCount = toolChainTailToKeep(toolChainSize, chainTokens, budget)
            if (keepTailCount < toolChainSize) {
                val keepHead = conversationHistory.subList(0, params.baseHistorySize).toList()
                val keepTail =
                    if (keepTailCount <= 0) {
                        emptyList()
                    } else {
                        conversationHistory.subList(conversationHistory.size - keepTailCount, conversationHistory.size).toList()
                    }
                val truncatedList =
                    keepHead +
                        listOf(
                            UIMessage(
                                role = MessageRole.SYSTEM,
                                content = TRUNCATED_TOOL_CHAIN_MARKER,
                            ),
                        ) + keepTail
                conversationHistory.clear()
                conversationHistory.addAll(truncatedList)
                Logger.i(
                    "ToolOrchestrator",
                    "tool-chain truncated | round=$round | size ${params.baseHistorySize + toolChainSize} → " +
                        "${conversationHistory.size} | chainTokens=$chainTokens budget=$budget keepTail=$keepTailCount",
                )
            }

            // Restablecer el totalizador de transmisión en cada ronda;Preestablezca el contenido de salida cuando continúe la primera ronda de generación.
            val isFirstRound = round == 1
            val builder =
                StringBuilder().apply {
                    if (isFirstRound && params.initialBuilderContent.isNotEmpty()) append(params.initialBuilderContent)
                }
            val reasoningBuilder =
                StringBuilder().apply {
                    if (isFirstRound && params.initialReasoningContent.isNotEmpty()) append(params.initialReasoningContent)
                }

            val outcome =
                host.streamRound(
                    StreamRoundParams(
                        round = round,
                        history = conversationHistory.toList(),
                        currentAssistantId = currentAssistantId,
                        builder = builder,
                        reasoningBuilder = reasoningBuilder,
                        preservePartialContent = isFirstRound && params.initialBuilderContent.isNotEmpty(),
                        nativeWebSearch = params.nativeWebSearch && isFirstRound && !nativeFallbackUsed,
                        forceMainModel = forceFinalResponse,
                        forceFinalResponse = forceFinalResponse,
                    ),
                )

            when (outcome) {
                is StreamRoundResult.Error -> {
                    // Bajar a la cadena de búsqueda local cuando falla la búsqueda nativa: primero se prueba la API configurada por el usuario,
                    // Finalmente, están Bing HTTP y Baidu HTTP. Baje de categoría solo una vez para evitar solicitudes de facturación repetidas.
                    if (params.nativeWebSearch && !nativeFallbackUsed && !forceFinalResponse) {
                        nativeFallbackUsed = true
                        round--
                        Logger.w(TAG, "La solicitud de búsqueda nativa falla y se degrada a la cadena de búsqueda API/HTTP del usuario")
                        continue
                    }
                    Logger.w(
                        TAG,
                        "Agent Loop Terminación debido a un error de transmisión | sessionId=${params.sessionId} | traceId=${params.traceId}" +
                            " | round=$round | type=${outcome.type} | msg=${outcome.message}",
                    )
                    return ToolLoopResult(
                        finalAssistantId = currentAssistantId,
                        round = round,
                        totalToolCallCount = execPolicy.emittedToolCallCount,
                        totalCharCount = execPolicy.streamedCharCount,
                        firstTokenTime = firstTokenTime,
                        citationUrls = citationUrls.toList(),
                        success = false,
                        error =
                        ToolLoopError(
                            type = outcome.type,
                            message = outcome.message,
                            partialContent = outcome.partialContent,
                            partialReasoning = outcome.partialReasoning,
                        ),
                        toolRounds = toolRounds.toList(),
                        terminationReason = ToolLoopTerminationReason.STREAM_ERROR,
                    )
                }

                is StreamRoundResult.Success -> {
                    citationUrls.addAll(outcome.citationUrls)
                    execPolicy.recordStreamedChars(outcome.contentLength)
                    if (outcome.firstTokenTime > 0L && firstTokenTime == 0L) {
                        firstTokenTime = outcome.firstTokenTime
                    }

                    if (!outcome.hasToolCalls) {
                        hasToolCalls = false
                        finalAssistantMessage = outcome.assistantMessage
                        terminationReason =
                            if (forceFinalResponse) {
                                ToolLoopTerminationReason.COMPLETED_AFTER_TOOL_FAILURES
                            } else {
                                ToolLoopTerminationReason.COMPLETED
                            }
                        forceFinalResponse = false
                        Logger.d(TAG, "Agent Loop step $round/$roundLimitLabel fin(Sin llamada de herramienta,El bucle termina normalmente.)")
                        break
                    }

                    // La rueda de acabado ha cerrado explícitamente la herramienta; Si el flujo ascendente aún devuelve llamadas a herramientas, ya no se ejecutarán efectos secundarios desconocidos.
                    // Cierra la ronda actual con un aviso visible para evitar una segunda tormenta de herramientas después de fallas consecutivas.
                    if (forceFinalResponse) {
                        hasToolCalls = false
                        finalAssistantMessage =
                            outcome.assistantMessage.copy(
                                toolCalls = emptyList(),
                                content = outcome.assistantMessage.content.ifBlank {
                                    "Las llamadas a la herramienta han fallado continuamente y se han detenido según los resultados existentes."
                                },
                            )
                        terminationReason = ToolLoopTerminationReason.COMPLETED_AFTER_TOOL_FAILURES
                        forceFinalResponse = false
                        break
                    }

                    val assistantToolMsg = outcome.assistantMessage
                    val rawToolCallList = assistantToolMsg.toolCalls ?: emptyList()
                    val truncatedArgsRound = outcome.toolCallsTruncated
                    val toolCallList =
                        if (truncatedArgsRound) {
                            truncatedToolCallsForBackfill(rawToolCallList, round)
                        } else {
                            // v1.0.48: Filtrado no válido toolCalls — SenseTime completeText puede regresar en escenarios como la reversión
                            //   nombre vacío o argumentos vacíos toolCall,La ejecución directa causará HTTP 400
                            //   (invalid tool_call function, function/name/arguments cannot be empty)
                            //   Si no hay ninguna llamada válida después del filtrado,prensa"Sin llamada de herramienta"Procesar esta ronda,Evite quedarse atascado
                            // v2.6.10: Pase el nombre de la herramienta expuesta en esta ronda. El modelo ocasionalmente envía líneas y nombres de funciones en una cadena.,
                            //   Sólo con esta lista podremos restaurar el nombre real de la herramienta al final.,En lugar de desperdiciar toda la llamada.
                            ToolCallSanitizer.sanitize(
                                rawToolCallList,
                                params.tools.mapTo(HashSet()) { it.name },
                            )
                        }
                    if (rawToolCallList.size != toolCallList.size) {
                        Logger.w(
                            TAG,
                            "Filtrado no válido toolCalls: ${rawToolCallList.size} -> ${toolCallList.size}" +
                                " | sessionId=${params.sessionId}",
                        )
                    }
                    execPolicy.recordEmittedToolCalls(toolCallList.size)

                    // v1.0.48: No válido después de filtrar toolCalls,prensa"Sin llamada de herramienta"Procesar esta ronda
                    if (toolCallList.isEmpty()) {
                        hasToolCalls = false
                        val rawCount = assistantToolMsg.toolCalls?.size ?: 0
                        // v2.6.10: Los modelos pequeños a menudo incluyen todo lo que quieren decir en llamadas a herramientas (name está vacío,
                        // arguments Sólo una frase completa). Esa frase en sí es lo que el modelo quiere decir,
                        // debe mostrarse como texto del cuerpo en lugar de informar al usuario"Formato de parámetro anormal".
                        val recovered = recoverProseFromInvalidToolCalls(rawToolCallList)
                        if (recovered != null) {
                            finalAssistantMessage =
                                assistantToolMsg.copy(toolCalls = emptyList(), content = recovered)
                            terminationReason = ToolLoopTerminationReason.COMPLETED
                            Logger.w(
                                TAG,
                                "Agent Loop step $round/$roundLimitLabel: toolCalls No ejecutable pero el contenido es texto.," +
                                    "Cerrado como respuesta de texto(raw=$rawCount, chars=${recovered.length})",
                            )
                            break
                        }
                        // v1.0.81: No más burbujas con contenido vacío(Eso mostrará una interfaz de usuario vacía sin indicaciones).
                        // Cuando se limpian las llamadas a herramientas, la salida del modelo suele estar incompleta./Parámetro de herramienta ilegal(Sanitizer descartar),
                        // Ofrezca a los usuarios y modelos un recordatorio claro,Haga que esta ronda sea observable y vuelva a intentar la siguiente ronda.
                        val hint =
                            if (rawCount > 0) {
                                "modelo iniciado $rawCount llamadas de herramientas,Sin embargo, el formato del parámetro era anormal y la ejecución falló. Por favor vuelva a describir sus necesidades,Lo intentaré de nuevo."
                            } else {
                                "El modelo no genera llamadas de herramientas válidas,No hay herramientas para ejecutar esta ronda. Para búsqueda u otras herramientas,Vuelva a describir sus necesidades."
                            }
                        finalAssistantMessage =
                            assistantToolMsg.copy(
                                toolCalls = emptyList(),
                                content = hint,
                            )
                        // Phase 3: No hay llamadas válidas después de la limpieza."Complete la respuesta final normalmente" — Razones de estructuración del documento.,
                        // Al final, usa la pista como error message,Es conveniente que la capa superior distinga entre las respuestas del modelo y los detalles del organizador.
                        abortReason = hint
                        terminationReason = ToolLoopTerminationReason.INVALID_TOOL_CALLS
                        Logger.w(
                            TAG,
                            "Agent Loop step $round/$roundLimitLabel: toolCalls Vaciar después de la limpieza(raw=$rawCount),Consejos de inyección",
                        )
                        break
                    }

                    // M3.3: Prueba de parada temprana continua y sin progresión — Subordinado a la detección de duplicación de firmas a nivel redondo de execPolicy,
                    // Juzgue antes de rellenar,Evite ejecutar herramientas duplicadas cuando esté atascado.
                    val currentSignature = toolCallSignature(toolCallList)
                    val progressDecision = execPolicy.checkRoundProgress(currentSignature)
                    if (!progressDecision.allowed) {
                        Logger.w(
                            TAG,
                            "Agent Loop Continuo idéntico detectado tool_call(${progressDecision.detail})" +
                                " | sessionId=${params.sessionId} | signature=$currentSignature",
                        )
                        Logger.w(TAG, "Agent Loop Las mismas rondas continuas de llamada de herramienta alcanzan el límite superior,Decidido a quedar atrapado,terminación anticipada")
                        hasToolCalls = false
                        terminationReason = ToolLoopTerminationReason.REPEATED_TOOL_CALLS
                        // A-07: Motivo de salida del registro,Inyecte una copia clara al final(De lo contrario, el usuario sólo verá la tarjeta de herramientas sin ninguna respuesta.)
                        abortReason = "Se detectó una llamada de herramienta estancada(continuo ${execPolicy.noProgressRoundsCount} No hay progreso en la ronda),Se detuvo automáticamente. para continuar,Déjame reprocesarlo."
                        // No agregue asistenteToolMsg history,Evite dejar mensajes del asistente sin resultados de la herramienta
                        break
                    }

                    // v1.0.62: La historia y la persistencia deben utilizar la limpieza. toolCalls,
                    // Evite que las llamadas ilegales con nombres y argumentos vacíos activen 400 en la siguiente ronda de solicitudes.
                    // v1.0.80: La implementación anterior solo usaba los resultados de limpieza cuando cambiaba la cantidad. — Si desinfectar solo arregla
                    // arguments(malo JSON → "{}")mientras la cantidad permanece sin cambios,Los malos argumentos seguirán pasando a la historia,
                    // Se activa la próxima ronda de solicitudes 400 "Assistant tool call arguments must be valid JSON".
                    // Utilice siempre la herramienta desinfectadaCallList en su lugar.
                    val cleanedAssistantToolMsg = assistantToolMsg.copy(toolCalls = toolCallList)

                    // Los mensajes del asistente con tool_calls solo pertenecen a esta ronda del historial del protocolo del proveedor.
                    // MessageEntity El campo ToolCalls no se guarda. Si el mensaje de protocolo con contenido vacío se coloca en la biblioteca,
                    // Después de recargar, solo quedará un shell de asistente vacío sin contenido.
                    conversationHistory.add(cleanedAssistantToolMsg)
                    if (
                        cleanedAssistantToolMsg.content.isNotBlank() ||
                        !cleanedAssistantToolMsg.reasoning.isNullOrBlank() ||
                        cleanedAssistantToolMsg.imageUrls.isNotEmpty() ||
                        cleanedAssistantToolMsg.imageBase64List.isNotEmpty() ||
                        cleanedAssistantToolMsg.toolCallInfo != null
                    ) {
                        persistAssistantToolMsg(params.sessionId, cleanedAssistantToolMsg, host)
                    }

                    // Reanudar la carga desde el punto de interrupción:Persistir en las llamadas de herramientas pendientes. El modo de pausa lo selecciona explícitamente el usuario
                    // “No ejecutado esta ronda”, estas llamadas no deben confundirse con los efectos secundarios que se restaurarán después de reiniciar.
                    // Lo mismo ocurre con la rueda de truncamiento de parámetros: los argumentos utilizados para el relleno son simplemente `{}` ocupar un lugar, caer en pending
                    // Se ejecutará como una llamada verdadera después de reiniciar.
                    if (params.allowToolExecution && !truncatedArgsRound) {
                        savePendingToolCalls(params.sessionId, toolCallList, executionIdentity, host)
                    }

                    // Cree la tarjeta de tareas y cambie a EXECUTING
                    // v1.0.53: send_sticker No incluido en tarjetas de tareas(Los emoticonos son interacciones divertidas,No mostrar plan de ejecución),
                    //   Cuando todas las llamadas sean herramientas silenciosas, no se creará ninguna tarjeta.(taskCardId=null,Las operaciones posteriores en la tarjeta se omitirán internamente.).
                    // v1.0.54: list_stickers Mismo silencio(Listar memes es un trabajo interno,Los usuarios no necesitan ver).
                    // No se crean tarjetas en la ronda de truncamiento de parámetros: ninguna de las llamadas en esta ronda se ejecutará realmente y las tarjetas solo se colgarán en una fila.
                    // No hay pasos hacia adelante; El hecho del truncamiento se explica al usuario en la tarjeta de recibo de la herramienta que aparece a continuación.
                    val silentToolNames = setOf("send_sticker", "list_stickers")
                    val taskCardToolCallsWithIds =
                        toolCallList
                            .filter { it.name !in silentToolNames }
                            .map { it.id to (it.name to it.arguments) }
                    val taskCardToolCalls = taskCardToolCallsWithIds.map { it.second }
                    val taskCardToolCallIds = taskCardToolCallsWithIds.map { it.first }
                    val taskCardId: String? =
                        if (taskCardToolCalls.isNotEmpty() && !truncatedArgsRound) {
                            val id = currentAssistantId.toString()
                            val taskCard =
                                TaskCardData.fromToolCalls(
                                    context = context,
                                    assistantId = currentAssistantId,
                                    toolCalls = taskCardToolCalls,
                                    toolCallIds = taskCardToolCallIds,
                                )
                            accessor.update {
                                it.copy(taskCards = it.taskCards + (id to taskCard))
                            }
                            taskCardCoordinator.updateTaskCardPhase(id, TaskCardPhase.EXECUTING)
                            id
                        } else {
                            null
                        }

                    // paralelo/Llamadas a herramientas de ejecución en serie
                    // v1.0.47 P6-2: El modelo de herramientas débil se degrada a la ejecución en serie,Evite las llamadas a herramientas paralelas que causan malformaciones
                    // Phase 3: stateLock/approvalLock Creado solo en rondas simultáneas de solo lectura;transmisión de ruta en serie null(El comportamiento es completamente consistente con el status quo.)
                    val contextBudgetTokens = host.contextBudgetTokens()
                    val currentHistoryTokens =
                        if (contextBudgetTokens > 0) {
                        TokenEstimator.estimate(conversationHistory, cache = loopTokenCache)
                    } else {
                        0
                    }
                    val inlineOutputBudgetTokens =
                        toolOutputInlineBudgetTokens(
                            contextBudgetTokens = contextBudgetTokens,
                            currentHistoryTokens = currentHistoryTokens,
                            toolCallCount = toolCallList.size,
                        )
                    val executeToolCall: suspend (Int, ToolCall, Mutex?, Mutex?) -> ToolExecResult =
                        { idx, tc, stateLock, approvalLock ->
                            val executionJob = coroutineContext[Job]
                            val executionId =
                                executionRegistry?.register(
                                    identity = executionIdentity,
                                    kind = io.zer0.muse.session.ExecutionKind.TOOL,
                                    cancel = { executionJob?.cancel() },
                                )
                            executionId?.let { id -> executionRegistry?.start(id) }
                            // F-13: progreso sin bloqueo — Nombre de la herramienta actual toolProgressMessage(UI mostrar),
                            // Fin de la fase de herramientas(execResults Después del relleno)Borrar, Vinculado con la máquina de estados F-07
                            accessor.update {
                                it.copy(toolProgressMessage = context.getString(R.string.tool_running, tc.name))
                            }
                            try {
                                val result =
                                    executeSingleToolCall(
                                        params, taskCardId, tc, idx, host, taskCardCoordinator, execPolicy,
                                        stateLock, approvalLock, inlineOutputBudgetTokens,
                                    ).copy(executionId = executionId ?: "")
                                persistToolRoundIncrementally(params, round, stepStartedAt, result)
                                executionId?.let { id -> executionRegistry?.finish(id) }
                                result
                            } catch (ce: kotlinx.coroutines.CancellationException) {
                                executionId?.let { id -> executionRegistry?.markCancelled(id) }
                                // El contexto de rutina no se puede suspender cuando se cancela; borrar el punto de interrupción del disco en NonCancellable
                                // Marcar ABORTADO para evitar ser cancelado después de reiniciar/Es posible que algunas llamadas de efectos secundarios se confundan con tareas ejecutables.
                                withContext(NonCancellable) {
                                    runCatching {
                                        PendingToolCallStore.updateState(
                                            tc.id,
                                            PendingToolCallStore.ABORTED,
                                            "generation_cancelled",
                                        )
                                    }.onFailure { error ->
                                        Logger.w(TAG, "La escritura ABORTADA falló después de cancelar la herramienta: ${tc.id}", error)
                                    }
                                }

                                // F-07: marca de rotura — Ejecución de herramienta interrumpida por streaming(usuario detenido/Cambio de sesión).
                                // Resultados incompletos, Registre el registro de estado INTERRUMPIDO y los pasos de la tarjeta de tareas, Propague la cancelación hacia arriba nuevamente.
                                Logger.w(TAG, "Herramientas ${tc.name} interrumpido(INTERRUPTED) | sessionId=${params.sessionId}")
                                val interruptedAt = System.currentTimeMillis()
                                val interruptedText = "[interrumpir] Herramientas ${tc.name} Ejecución cancelada"
                                // Phase 3: La cancelación es un estado final independiente.,ya no está completo FAILED(TaskStepStatus.CANCELLED).
                                taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
                                    s.copy(
                                        status = ToolExecStatus.CANCELLED.toTaskStepStatus(),
                                        result = interruptedText,
                                        finishedAt = interruptedAt,
                                    )
                                }
                                // Cancellation previously vanished from the message stream and the
                                // tool history; record it so theinterrumpir is visible after the fact.
                                accessor.update { state ->
                                    state.copy(
                                        toolCallHistory =
                                        state.toolCallHistory +
                                            ToolCallRecord(
                                                toolName = tc.name,
                                                arguments = tc.arguments,
                                                result = interruptedText,
                                                isSuccess = false,
                                                timestamp = interruptedAt,
                                                traceId = params.traceId,
                                                sessionId = params.sessionId,
                                                turnId = params.turnId,
                                                generationId = executionIdentity.generationId,
                                                roundIndex = round,
                                                toolCallId = tc.id,
                                                executionId = executionId ?: "",
                                                startedAt = stepStartedAt,
                                                finishedAt = interruptedAt,
                                                status = ToolExecStatus.CANCELLED.name,
                                                terminationReason = interruptedText,
                                            ),
                                    )
                                }
                                throw ce
                            } catch (e: Exception) {
                                executionId?.let { id -> executionRegistry?.fail(id) }
                                // Las herramientas pueden haber completado efectos secundarios externos.(Por ejemplo, escribir en la memoria.),pero devolución de llamada/Excepción durante la fase de limpieza
                                // No se puede simplemente dejar que desaparezca todo el ciclo de la herramienta,De lo contrario, los usuarios sólo verán“ejecutando”.
                                Logger.e(TAG, "No se pudieron devolver los resultados de la herramienta: ${tc.name}", e)
                                failedToolResult(
                                    taskCardId = taskCardId,
                                    tc = tc,
                                    idx = idx,
                                    host = host,
                                    taskCardCoordinator = taskCardCoordinator,
                                    reason = e.message ?: "error desconocido",
                                )
                            }
                        }

                    // B-38: Ejecución en serie predeterminada — async+awaitAll Al mismo tiempo execPolicy.afterExecute /
                    // taskCardCoordinator / PendingToolCallStore Existe una condición de carrera al escribir estados no atómicos.
                    // (ToolExecutionPolicy Presione explícitamente"Uso de secuencia de un solo giro"diseño).LLM Herramienta de marco único
                    // Llamadas normalmente 1-3,Rendimiento en serie aceptable,Y es consistente con la ruta de degradación del modelo de herramienta débil.
                    // Phase 3: Cuando el interruptor está activado y toda la ronda es una herramienta de solo lectura,Ejecutar con concurrencia limitada;Todas las escrituras de estado por turno
                    // (TaskCard / PendingToolCallStore / execPolicy / hook / host devolución de llamada)Serializado a través de stateLock,
                    // Solicitudes de aprobación serializadas mediante AppLock,Los resultados todavía se reponen en el orden de llamada de herramienta original.
                    if (toolCallList.size > 1 && WeakToolUseDetector.isWeakToolModel(params.model)) {
                        Logger.i(TAG, "Se detectó un modelo de herramienta débil ${toolCallList.size} llamadas de herramientas,ejecución en serie | model=${params.model?.id}")
                    }
                    val parallelRound = !truncatedArgsRound && shouldExecuteRoundInParallel(toolCallList, params)
                    val stateLock = if (parallelRound) Mutex() else null
                    val approvalLock = if (parallelRound) Mutex() else null
                    if (parallelRound) {
                        Logger.i(
                            TAG,
                            "Las herramientas de solo lectura tienen ejecución simultánea limitada: ${toolCallList.size} llamadas" +
                                " | Límite de concurrencia=$READ_ONLY_TOOL_MAX_PARALLELISM | sessionId=${params.sessionId}",
                        )
                    }
                    val execResults: List<ToolExecResult> =
                        if (truncatedArgsRound) {
                            // Sin ejecución, sin aprobación, sin pendientes: solo pon“Los parámetros de esta llamada están truncados por el límite superior de salida.”
                            // Como resultado de la herramienta, se rellena al modelo, que reenvía los parámetros completos en la siguiente ronda.
                            // El precio es aceptar una ronda más de solicitudes en esta ronda (el límite superior de rondas es 1 ronda adicional en el peor de los casos).
                            // Es mejor que invalidar silenciosamente una ronda entera; El truncamiento repetido todavía está sujeto a la detección de estancamiento y al límite superior de rondas.
                            toolCallList.mapIndexed { idx, tc ->
                                ToolExecResult(
                                    idx = idx,
                                    tc = tc,
                                    finalToolResult = truncatedArgumentsToolResult(tc.name),
                                    isSuccess = false,
                                    displayResult = "Herramientas ${tc.name} No ejecutado: el argumento fue truncado por el límite de longitud de salida en la generación",
                                    status = ToolExecStatus.FAILED,
                                    finishedAt = System.currentTimeMillis(),
                                )
                            }
                        } else {
                            executeToolRound(
                                toolCallList = toolCallList,
                                parallel = parallelRound,
                                execute = { idx, tc -> executeToolCall(idx, tc, stateLock, approvalLock) },
                            )
                        }
                    if (!params.allowToolExecution) {
                        // Dale al modelo una ronda explícita.“La herramienta está en pausa”Como resultado, la herramienta se ve obligada a cerrarse para terminar;
                        // Los pensamientos y las respuestas finales aún se conservan y no desencadenan efectos secundarios reales de la herramienta.
                        forceFinalResponse = true
                        hasToolCalls = true
                    }

                    // Registro estructurado de esta ronda de convocatorias de herramientas;UI El mensaje aún tiene el campo de compatibilidad y se puede restaurar desde esta tabla al volver a ingresar a la sesión.
                    if (params.turnId.isNotBlank()) {
                        val finishedAt = System.currentTimeMillis()
                        toolRounds +=
                            execResults.map { result ->
                                ToolRoundEntity(
                                    id = "${params.traceId.ifBlank { params.sessionId }}:$round:${result.tc.id}",
                                    turnId = params.turnId,
                                    roundIndex = round,
                                    toolCallId = result.tc.id,
                                    toolName = result.tc.name,
                                    argsJson = ToolDataPrivacy.safeArgumentsForPersistence(result.tc.name, result.tc.arguments),
                                    resultJson = ToolDataPrivacy.safeResultForPersistence(result.tc.name, result.finalToolResult),
                                    status = result.status.name,
                                    startedAt = stepStartedAt,
                                    finishedAt = finishedAt,
                                    errorDetail =
                                    if (result.isSuccess) {
                                        null
                                    } else {
                                        ToolDataPrivacy.safeResultForPersistence(
                                            result.tc.name,
                                            result.displayResult ?: result.finalToolResult,
                                        )
                                    },
                                )
                            }
                    }

                    // Rellene los resultados secuencialmente en el historial y UI
                    for (result in execResults) {
                        val (idx, tc, finalToolResult, isSuccess, displayResult) = result
                        // F-07: registro de estado — Registre el estado final al final de la ejecución de cada herramienta.(SUCCESS/FAILED/INTERRUPTED/APPROVAL_PENDING)
                        Logger.d(
                            TAG,
                            "Finaliza la ejecución de la herramienta: ${tc.name} status=${result.status} isSuccess=$isSuccess" +
                                " | sessionId=${params.sessionId}",
                        )
                        // F-08: Agent Run recibo unificado — Libro mayor de memoria + registro de auditoría(Persistencia opcional).
                        // parentRunId: Si esta herramienta es la iniciadora de delegado_agent/subagent_task,
                        // Las llamadas a herramientas en su subagente formarán una cadena de llamadas con este runId como parentRunId.
                        // F-11: exposedToolIds Registre esta ronda de herramientas visibles de LLM(Exponiendo evidencia instantánea,
                        // Se puede determinar comparando con callToolId"Si la herramienta está razonablemente expuesta").
                        val isDelegationTool = tc.name == "delegate_agent" || tc.name == "subagent_task"
                        agentRunTracker?.record(
                            io.zer0.muse.data.audit.AgentRunRecord(
                                runId = tc.id,
                                parentRunId = null,
                                sessionId = params.sessionId,
                                round = round,
                                toolName = tc.name,
                                // F-12/B-28: Resumen de parámetros y resultados primera desensibilización URL query,La auditoría no registra parámetros sensibles a clases clave
                                argumentsSummary = sanitizeUrlQuery(tc.arguments.take(200)),
                                resultSummary = sanitizeUrlQuery(finalToolResult.take(200)),
                                status = result.status.name,
                                durationMs = result.durationMs,
                                exposedToolIds = params.tools.joinToString(",") { it.name },
                                traceId = params.traceId,
                            ),
                        )
                        auditLogger?.log(
                            category = "agent_run",
                            action = if (isDelegationTool) "delegate_tool" else "tool_exec",
                            target = tc.id,
                            detail =
                            mapOf(
                                "sessionId" to params.sessionId,
                                "traceId" to params.traceId,
                                "round" to round,
                                "tool" to tc.name,
                                "status" to result.status.name,
                                "durationMs" to result.durationMs,
                                "isSuccess" to isSuccess,
                            ),
                        )
                        val toolMsg =
                            UIMessage(
                                role = MessageRole.TOOL,
                                content = finalToolResult,
                                toolCallId = tc.id,
                            )
                        conversationHistory.add(toolMsg)

                        // v2.6.0: Cuando la herramienta produce una captura de pantalla,Añadir un mensaje de usuario con una imagen. —— Los resultados de la herramienta en sí solo se pueden rellenar
                        // texto(function_call_output Cadena limitada),La imagen debe ser enviada al mensaje del usuario. imageBase64List
                        // canal(Ya está respaldado por información visual de varios proveedores.). Nota:Agregar al historial no se muestra directamente al usuario;
                        // UI La visualización de la captura de pantalla en el lateral es manejada por la propia tarjeta de herramientas.
                        result.screenshotBase64?.let { b64 ->
                            conversationHistory.add(
                                UIMessage(
                                    role = MessageRole.USER,
                                    content = "[Captura de pantalla del navegador] Esta es la representación de la página actual devuelta por la captura de pantalla del navegador que acaba de llamar.",
                                    imageBase64List = listOf(b64),
                                ),
                            )
                            Logger.d(TAG, "browser_screenshot El resultado se ha adjuntado como un mensaje con una imagen. | sessionId=${params.sessionId}")
                        }

                        val toolDisplay =
                            UIMessage(
                                role = MessageRole.ASSISTANT,
                                // v2.5.2 fix: El mensaje de visualización de la herramienta está vinculado al mensaje del asistente correspondiente (asociación de padres),
                                // De lo contrario, al eliminar esta ronda de mensajes del asistente, la tarjeta de herramientas no se puede encontrar en la eliminación en cascada (búsqueda por padre).
                                // Dejar tarjetas huérfanas (prueba de usuario:“El registro de llamada a la herramienta después de eliminar el mensaje aún permanece”).
                                parentMessageId = currentAssistantId.toString(),
                                parentGroupId = currentAssistantId.toString(),
                                // v1.0.54: La visualización de llamadas de herramientas está unificada en tarjetas plegadas.(ToolCallCard,y proceso de pensamiento/mood isomorfismo),
                                //   El cuerpo del mensaje ya no está escrito."herramienta de llamada/parámetros/resultado"texto.
                                //   send_sticker Caso especial: content Mantenga solo la ruta de la etiqueta(MessageBubble.extractStickerPaths
                                //   Renderiza la imagen en consecuencia),Tarjeta de herramientas silenciosa(isSilentTool).
                                content =
                                if (tc.name == "send_sticker") {
                                    extractStickerPaths(finalToolResult).joinToString("\n")
                                } else {
                                    ""
                                },
                                toolCallInfo =
                                ToolCallInfo(
                                    toolName = tc.name,
                                    arguments = tc.arguments,
                                    result = displayResult ?: finalToolResult,
                                    isSuccess = isSuccess,
                                    // Registrar el tiempo de ejecución local (excluyendo la espera de red y la generación de modelos),
                                    // Se muestra en el lado derecho de los pasos de la herramienta para que los usuarios puedan determinar si la lentitud radica en el modelo o en la herramienta.
                                    durationMs = (System.currentTimeMillis() - stepStartedAt)
                                        .takeIf { it >= 0 },
                                ),
                            )
                        val finishedAt = System.currentTimeMillis()
                        val record =
                            ToolCallRecord(
                                toolName = tc.name,
                                arguments = tc.arguments,
                                result = displayResult ?: finalToolResult,
                                isSuccess = isSuccess,
                                timestamp = finishedAt,
                                traceId = params.traceId,
                                sessionId = params.sessionId,
                                turnId = params.turnId,
                                generationId = executionIdentity.generationId,
                                roundIndex = round,
                                toolCallId = tc.id,
                                executionId = result.executionId,
                                startedAt = stepStartedAt,
                                finishedAt = finishedAt,
                                status = result.status.name,
                                terminationReason =
                                if (isSuccess) {
                                    null
                                } else {
                                    (displayResult ?: finalToolResult).lineSequence().firstOrNull()?.take(200)
                                },
                            )

                        // Los mensajes de resultados de la herramienta no se pueden colocar simplemente en la interfaz de usuario de la memoria: la tarjeta de la herramienta aún debe mostrarse después de que se corta la sesión y se reinicia el proceso.
                        // assistant(tool_calls) El pedido se realizó arriba y el mensaje de visualización del resultado de la herramienta correspondiente se completa aquí.
                        // El uso del propio ID del mensaje como inserción idempotente no afecta si la interfaz de usuario actual muestra la sesión.
                        if (tc.name !in silentToolNames || tc.name == "send_sticker") {
                            val persistedToolDisplay =
                                toolDisplay.copy(
                                    toolCallInfo =
                                    toolDisplay.toolCallInfo?.copy(
                                        arguments = ToolDataPrivacy.safeArgumentsForPersistence(tc.name, tc.arguments),
                                        result = ToolDataPrivacy.safeResultForPersistence(
                                            tc.name,
                                            displayResult ?: finalToolResult,
                                        ),
                                    ),
                                )
                            persistAssistantToolMsg(params.sessionId, persistedToolDisplay, host)
                        }
                        val snapshot = accessor.snapshot
                        val isCurrentDisplayedSession =
                            if (snapshot.isAgentMode) {
                                snapshot.agentSessionId == params.sessionId
                            } else {
                                snapshot.currentSessionId == params.sessionId
                            }
                        if (isCurrentDisplayedSession) {
                            // v1.0.54: herramienta silenciosa(list_stickers)No envíe ningún mensaje de interfaz de usuario — No hay rastro de funcionamiento interno;
                            //   send_sticker empujar content=mensaje de ruta de etiqueta(Renderizar imagen,tarjeta de silencio);
                            //   Otras herramientas se vacían content + toolCallInfo(expositor de tarjetas plegadas).
                            if (tc.name !in silentToolNames || tc.name == "send_sticker") {
                                accessor.updateMessages { it + toolDisplay }
                                accessor.update {
                                    it.copy(
                                        toolCallHistory = it.toolCallHistory + record,
                                    )
                                }
                            }
                        } else {
                            Logger.d(
                                "ToolOrchestrator",
                                "toolDisplay skipped (detached): sessionId=${params.sessionId}, " +
                                    "current=${snapshot.currentSessionId}, agent=${snapshot.agentSessionId}, " +
                                    "isAgent=${snapshot.isAgentMode}",
                            )
                        }

                        // Extracto de los resultados de web_search citation URL
                        if (tc.name == "web_search") {
                            citationUrls.addAll(extractWebSearchUrls(finalToolResult))
                        }

                        // M3.3: Fallo continuo y parada anticipada. — por execPolicy(fuente de estado única)juicio
                        if (execPolicy.shouldAbortOnConsecutiveFailures()) {
                            Logger.w(
                                "ToolOrchestrator",
                                "continuo ${execPolicy.consecutiveFailuresCount} herramienta falló,Terminación anticipada del bucle de llamada de herramienta " +
                                    "(round=$round, tool=${tc.name})",
                            )
                            // No termine aquí directamente: primero rellene completamente todos los resultados de las HERRAMIENTAS de esta ronda,
                            // Luego apague la herramienta y deje que el modelo principal genere el acabado visible.
                            forceFinalResponse = true
                            hasToolCalls = true
                        }
                    }

                    // F-13: Fin de la fase de herramientas,Borrar"ejecutando"Progreso(No habrá residuos en la próxima generación de todos contra todos.)
                    accessor.update { it.copy(toolProgressMessage = null) }
                    taskCardCoordinator.updateTaskCardPhase(taskCardId, TaskCardPhase.DONE)

                    // Sincronizar el plan de flujo de trabajo del agente con UI
                    // v1.137: Asociar el mensaje actual del asistente con el plan ID,Haga que la tarjeta de planificación permanezca fijada en el mensaje que la creó y desplácese con el mensaje,
                    // en lugar de siempre"saltar"Ir al último mensaje del asistente(Comentarios de los usuarios"La lista está fija en la parte inferior y no sigue el desplazamiento.").
                    val latestPlans =
                        skillExecutor.getActivePlans(params.sessionId).mapValues { (_, plan) ->
                            if (plan.messageId == null) plan.copy(messageId = currentAssistantId.toString()) else plan
                        }
                    if (latestPlans.isNotEmpty()) {
                        accessor.update { it.copy(agentPlans = latestPlans) }
                    }

                    // ¿Hay otra ronda de solicitudes por enviar? Las interjecciones sólo se toman cuando hay una siguiente ronda: si la ronda actual ha terminado,
                    // No hay ningún lugar para enviarlo (el mensaje del usuario no será solicitado ni estará en la cola).
                    val willRunAnotherRound = (hasToolCalls || forceFinalResponse) && round < execPolicy.maxRounds

                    // Interjección del usuario: Una vez finalizada la ronda de herramientas y antes de la siguiente ronda de solicitudes, las palabras pronunciadas por el usuario durante la generación se reciben como mensajes de USUARIO.
                    // **Después de todos los resultados de la herramienta**. Esta es la única ubicación legal:
                    //  - Insertar en epiciclo assistant(tool_calls) entre resultados de herramientas → Rompe el emparejamiento,Provider Directo 400;
                    //  - Fusionarse en un mensaje de HERRAMIENTA → TOOL Solo se pueden reponer los resultados correspondientes a tool_call_id, y el resto del contenido también se rellena con 400;
                    //  - insertado en medio de la historia anterior → Los prefijos que el modelo ya haya visto se sobrescribirán y la caché y la reproducción del bloque de pensamiento estarán desalineadas.
                    // Se sigue como un mensaje de USUARIO independiente al final, que no cambia el emparejamiento existente y garantiza que la siguiente ronda de modelos lo verá.
                    if (willRunAnotherRound) {
                        val steeringMessages = host.drainSteeringMessages()
                        if (steeringMessages.isNotEmpty()) {
                            steeringMessages.forEach { text ->
                                conversationHistory.add(UIMessage(role = MessageRole.USER, content = text))
                            }
                            Logger.i(
                                TAG,
                                "Insertar comentarios de usuario ${steeringMessages.size} Artículo | sessionId=${params.sessionId} | round=$round",
                            )
                        }
                    }

                    // Cree un nuevo mensaje de asistente de marcador de posición para recibir la siguiente ronda de respuestas
                    if (willRunAnotherRound) {
                        val nextAssistant = UIMessage(role = MessageRole.ASSISTANT, content = "")
                        val snapshot = accessor.snapshot
                        val isCurrentDisplayedSession2 =
                            if (snapshot.isAgentMode) {
                                snapshot.agentSessionId == params.sessionId
                            } else {
                                snapshot.currentSessionId == params.sessionId
                            }
                        if (isCurrentDisplayedSession2) {
                            accessor.updateMessages { it + nextAssistant }
                        }
                        currentAssistantId = nextAssistant.id
                    }

                    // v1.x: registro de fin de paso — Grabar nombre de la herramienta, éxito/Número de fallas, tiempo necesario.
                    val stepElapsedMs = System.currentTimeMillis() - stepStartedAt
                    val toolNames = toolCallList.joinToString(",") { it.name }
                    val successCount = execResults.count { it.isSuccess }
                    val failCount = execResults.size - successCount
                    // P3-9: Tiempo dedicado a enterrar puntos en cada ronda. — Escribir además del registro de depuración Perf(Proporcionar un registro/ANR ¿El diagnóstico y posicionamiento es lento en qué tramo?)
                    Perf.log("chat-tool-round", stepElapsedMs)
                    Logger.d(
                        TAG,
                        "Agent Loop step $round/$roundLimitLabel fin | Herramientas=[$toolNames]" +
                            " | éxito=$successCount falló=$failCount | Consume mucho tiempo=${stepElapsedMs}ms",
                    )
                }
            }
        }

        // v2.6.4: Cerrar el plan inacabado cuando sale el bucle de herramientas —— Convierta el paso PENDIENTE / EN_PROGRESO en CANCELADO,
        // De lo contrario, la tarjeta del plan siempre se detendrá en el medio.(girar en circulos), porque ya nadie ajusta update_plan_step.
        // Cubrir todas las rutas de salida(Finalización normal/Rondas agotadas/Fallos continuos/Error de transmisión),
        // Completado/falló/saltar/Los pasos cuyo tiempo de espera no se ven afectados (settleUnfinishedPlans Sólo los no finalizados se procesan internamente).
        val settledPlans = skillExecutor.settleUnfinishedPlans(params.sessionId)
        if (settledPlans.isNotEmpty()) {
            val snapshot = accessor.snapshot
            val isCurrentDisplayedSession3 =
                if (snapshot.isAgentMode) {
                    snapshot.agentSessionId == params.sessionId
                } else {
                    snapshot.currentSessionId == params.sessionId
                }
            if (isCurrentDisplayedSession3) {
                accessor.update { state ->
                    // Fusionar en lugar de sobrescribir: el plan cerrado sobrescribe el elemento con el mismo nombre y se conserva el plan sin cambios.
                    state.copy(agentPlans = state.agentPlans + settledPlans)
                }
            }
        }

        // v1.0.74 fix: Quedarse sin rondas/Cuando se atasca y sale, finalAssistantMessage es null,No hay nada en la sesión.
        // Consejos de cierre,La tarjeta de tareas queda colgada y el usuario piensa que el asistente está roto. Inyecte un mensaje de cierre claro.
        // A-07: Cubra todos los caminos de salida interminables — atascado/Fallo continuo y parada anticipada.(hasToolCalls Ya configurado false
        // Pero abortReason no está vacío)también inyectar,Y diferenciar el copywriting por motivo de salida.
        val roundLimitText = "[Se ha alcanzado el límite de ronda de llamadas de herramientas(${execPolicy.maxRounds} rueda),Deténgase automáticamente. para continuar,Puedes dejarme manejarlo.]"
        if (finalAssistantMessage == null) {
            val reasonText = abortReason ?: if (hasToolCalls) roundLimitText else null
            if (reasonText != null) {
                finalAssistantMessage =
                    UIMessage(
                        id = currentAssistantId,
                        role = MessageRole.ASSISTANT,
                        content = reasonText,
                    )
                // B-04: Reemplazar la semántica en lugar de agregar — El mensaje del marcador de posición ya está en la interfaz de usuario.,Agregar la misma identificación generará dos burbujas idénticas.
                // Si aparece un mensaje con la misma identificación, reemplácelo en su lugar.,De lo contrario, agregue un mensaje nuevo.
                accessor.updateMessages { list ->
                    if (list.any { it.id == finalAssistantMessage.id }) {
                        list.map { if (it.id == finalAssistantMessage.id) finalAssistantMessage else it }
                    } else {
                        list + finalAssistantMessage
                    }
                }
            }
        }

        // Phase 3: estado final estructurado — Salida de condición de bucle(while condición fallida)Sólo puede ser el límite de ronda.;
        // success solo significa"Complete la respuesta final normalmente",fusible/Salir de todo es siempre false,
        // Y envíe el motivo de la terminación a la capa superior como un mensaje de error.(De lo contrario, ChatViewModel sólo puede informar errores desconocidos.).
        val effectiveReason = terminationReason ?: ToolLoopTerminationReason.ROUND_LIMIT
        val completed =
            effectiveReason == ToolLoopTerminationReason.COMPLETED ||
                effectiveReason == ToolLoopTerminationReason.COMPLETED_AFTER_TOOL_FAILURES
        val terminationDetail =
            when (effectiveReason) {
                ToolLoopTerminationReason.COMPLETED -> null
                ToolLoopTerminationReason.COMPLETED_AFTER_TOOL_FAILURES -> null
                ToolLoopTerminationReason.ROUND_LIMIT -> roundLimitText
                else -> abortReason ?: "El bucle de llamada de herramienta finalizó prematuramente(${effectiveReason.name})"
            }

        Logger.i(
            TAG,
            "Agent Loop fin | sessionId=${params.sessionId} | rounds=$round/$roundLimitLabel" +
                " | toolCalls=${execPolicy.emittedToolCallCount} | chars=${execPolicy.streamedCharCount}" +
                " | success=$completed | reason=$effectiveReason",
        )
        return ToolLoopResult(
            finalAssistantId = currentAssistantId,
            round = round,
            totalToolCallCount = execPolicy.emittedToolCallCount,
            totalCharCount = execPolicy.streamedCharCount,
            firstTokenTime = firstTokenTime,
            citationUrls = citationUrls.toList(),
            success = completed,
            error =
            if (completed) {
                null
            } else {
                ToolLoopError(
                    type = ChatErrorType.TOOL_ERROR,
                    message = terminationDetail ?: effectiveReason.name,
                )
            },
            finalAssistantMessage = finalAssistantMessage,
            toolRounds = toolRounds.toList(),
            terminationReason = effectiveReason,
        )
    }

    /**
     * P2-7: Los usuarios que alcancen el presupuesto redondo pueden ver la copia final.
     *
     * Mismo origen que ToolLoopResult.error.message,Finalmente, runLoop finaliza y se inyecta en el mensaje del asistente.,
     * garantia"Terminar temprano"Visible para los usuarios(En lugar de simplemente dejar la tarjeta de tarea colgada).
     */
    private fun roundBudgetStopText(reason: ToolExecutionPolicy.StopReason?): String = when (reason) {
        ToolExecutionPolicy.StopReason.MAX_TOTAL_CALLS ->
            "El número de llamadas de herramientas en esta ronda ha alcanzado el límite superior,Se detuvo automáticamente. para continuar,Puedes dejarme manejarlo."
        ToolExecutionPolicy.StopReason.MAX_TOTAL_OUTPUT_CHARS ->
            "Esta ronda de producción de herramientas ha superado el presupuesto.,Se detuvo automáticamente. para continuar,Puedes dejarme manejarlo."
        ToolExecutionPolicy.StopReason.CONSECUTIVE_FAILURES ->
            "Varias llamadas a herramientas fallaron seguidas,Se detuvo automáticamente. para continuar,Déjame reprocesarlo."
        ToolExecutionPolicy.StopReason.TIME_BUDGET_EXHAUSTED ->
            "El tiempo del ciclo de la herramienta excedió el presupuesto,Se detuvo automáticamente. para continuar,Puedes dejarme manejarlo."
        else -> "La llamada de herramienta superó el presupuesto,Se detuvo automáticamente. para continuar,Puedes dejarme manejarlo."
    }

    private suspend fun persistToolRoundIncrementally(params: ToolLoopParams, round: Int, startedAt: Long, result: ToolExecResult) {
        if (params.turnId.isBlank()) return
        val now = System.currentTimeMillis()
        val entity =
            ToolRoundEntity(
                id = "${params.traceId.ifBlank { params.sessionId }}:$round:${result.tc.id}",
                turnId = params.turnId,
                roundIndex = round,
                toolCallId = result.tc.id,
                toolName = result.tc.name,
                argsJson = ToolDataPrivacy.safeArgumentsForPersistence(result.tc.name, result.tc.arguments),
                resultJson = ToolDataPrivacy.safeResultForPersistence(result.tc.name, result.finalToolResult),
                status = result.status.name,
                startedAt = startedAt,
                finishedAt = now,
                errorDetail =
                if (result.isSuccess) {
                    null
                } else {
                    ToolDataPrivacy.safeResultForPersistence(
                        result.tc.name,
                        result.displayResult ?: result.finalToolResult,
                    )
                },
            )
        resultOf { sessionRepository.upsertToolRound(entity) }
            .onError { msg, t ->
                Logger.w(TAG, "Error en la colocación incremental de la rueda de herramientas: ${result.tc.name}: $msg", t)
            }
    }

    /**
     * Phase 3: Si se debe ejecutar toda la ronda simultáneamente como de solo lectura.
     *
     * Condiciones(Necesidad de satisfacer a todos):
     *  - [parallelReadOnlyToolsEnabled] encender(Desactivado de forma predeterminada → Exactamente consistente con el comportamiento en serie del B-38.);
     *  - misma ronda ≥2 llamadas(Llamada única sin concurrencia);
     *  - modelo de herramienta no débil(Débil degradación del modelo tras el B-38);
     *  - Cada llamada llega a la lista blanca de solo lectura,y no enrutado a skill(herramienta desconocida/skill Siempre en serie).
     *
     * rueda mezcladora(incluyendo escritura/herramienta de efectos secundarios)serie general,evitar la destrucción"lee primero luego escribe"Semántica del orden de llamadas.
     */
    private fun shouldExecuteRoundInParallel(toolCallList: List<ToolCall>, params: ToolLoopParams): Boolean {
        if (!parallelReadOnlyToolsEnabled || toolCallList.size <= 1) return false
        // Los usuarios pueden desactivar el paralelismo de herramientas de solo lectura con un clic en la "Función experimental"(interruptor de escala de grises)
        if (!params.experiments.parallelReadOnlyTools) return false
        if (WeakToolUseDetector.isWeakToolModel(params.model)) return false
        return toolCallList.all { tc ->
            val routedToSkill =
                params.routeSnapshot?.routeFor(tc.name) is ToolRouteSnapshot.Route.Skill ||
                    (params.routeSnapshot == null && params.skillMap.containsKey(tc.name))
            !routedToSkill && classifyToolExecution(tc.name) == ToolExecCategory.READ_ONLY
        }
    }

    /**
     * Phase 3: Ejecute una ronda de llamadas a herramientas por patrón.
     *
     * - [parallel] = false: ejecución secuencial(consistente con el comportamiento existente);
     * - [parallel] = true: a [READ_ONLY_TOOL_MAX_PARALLELISM] Ejecución concurrente para el límite superior,
     *   `awaitAll` Lista de resultados garantizados con [toolCallList] Correspondencia uno a uno en orden(Relleno secuencial de llamada de herramienta original).
     */
    private suspend fun executeToolRound(
        toolCallList: List<ToolCall>,
        parallel: Boolean,
        execute: suspend (Int, ToolCall) -> ToolExecResult,
    ): List<ToolExecResult> {
        if (!parallel || toolCallList.size <= 1) {
            return toolCallList.mapIndexed { idx, tc -> execute(idx, tc) }
        }
        val semaphore = Semaphore(minOf(READ_ONLY_TOOL_MAX_PARALLELISM, toolCallList.size))
        return coroutineScope {
            toolCallList.mapIndexed { idx, tc ->
                async { semaphore.withPermit { execute(idx, tc) } }
            }.awaitAll()
        }
    }

    /**
     * Phase 3: per-turn El estado se escribe en el bloqueo en serie.
     *
     * Ronda simultánea de solo lectura,TaskCard/PendingToolCallStore/execPolicy/hook/host devolución de llamada, etc.
     * "Uso en secuencia de un solo giro."estado de diseño no atómico,Escrituras en serie unificadas a través de este bloqueo;
     * [lock] para null(ruta serial)ejecutado directamente,Cero gastos generales adicionales, comportamiento coherente con el status quo.
     */
    private suspend fun <T> withTurnLock(lock: Mutex?, block: suspend () -> T): T {
        if (lock == null) return block()
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * P2-18: Grupo de subprocesos de ejecución dedicado para herramientas de bloqueo.
     *
     * Implementación de herramientas(BrowserAutomationTool / CodeExecutionTool / WorkspaceTool espera)adaptarse
     * ToolRegistry Herramienta de sincronizaciónFn Firma,Para uso interno [kotlinx.coroutines.runBlocking] puente
     * suspend API;runBlocking es una llamada de bloqueo,La cancelación de rutina no se puede entregar,conduciendo a la capa exterior withTimeoutOrNull
     * Inválido(tiempo de espera falso:Nunca se puede alcanzar la rama de tiempo de espera,IO el hilo esta atascado).
     *
     * Planificar:Las llamadas de bloqueo se ejecutan en un grupo de subprocesos de demonio dedicado,La capa exterior utiliza el futuro de sondeo para mantener la corrutina cancelable.;
     * después del tiempo de espera [java.util.concurrent.Future.cancel] Interrumpir hilo dedicado(runBlocking conciencia de bucle de eventos
     * El hilo interrumpe y cancela la suspensión interna.),Hacer que los tiempos de espera realmente surtan efecto,Al mismo tiempo, no ocupa subprocesos del grupo de IO compartido.
     */
    private val blockingToolExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "muse-tool-blocking").apply { isDaemon = true }
        }

    /**
     * P2-33: Libro mayor de identificación de llamadas de herramienta donde se produjo el "resultado tardío después del tiempo de espera"(para diagnóstico;Nuevas ejecuciones posteriores con el mismo id.
     * no será bloqueado por eso,Porque la puerta de resultados se basa en la generación de ejecución.(Cada vez que ejecutas un nuevo)juicio).
     */
    private val abandonedToolCallIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** P2-33: Recuento de resultados tardíos descartados después del tiempo de espera(Diagnóstico/afirmación de prueba única). */
    private val lateDroppedResultCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** P2-33: ¿Alguna vez se agotó el tiempo de espera del toolCallId o llegó tarde?(El resultado ha sido descartado.). */
    internal fun isToolCallAbandoned(toolCallId: String): Boolean = toolCallId in abandonedToolCallIds

    /** P2-33: El número de resultados tardíos que se descartaron después del tiempo de espera. */
    internal fun droppedLateResultCount(): Int = lateDroppedResultCount.get()

    /**
     * P2-18: Ejecute llamadas a la herramienta de bloqueo en un hilo dedicado e imponga un tiempo de espera interrumpible.
     *
     * P2-33: Los resultados [ToolResultGate] publicar — Si el tiempo de espera expira, la puerta se desactivará primero y luego cancel,Ignore las llamadas interrumpidas incluso si
     * Terminar de ejecutar más tarde,El resultado sólo será descartado.([lateDroppedResultCount] contar + iniciar sesión),no entrará
     * TaskCard / noticias / toolCallRecord;Por lo tanto, los resultados tardíos para el mismo toolCallId ya no pueden afectar el estado
     * ([abandonedToolCallIds] dejar rastros). Las nuevas ejecuciones posteriores del mismo toolCallId utilizan la nueva puerta,No afectado por las viejas generaciones.
     *
     * @param timeoutMs Tiempo de espera en milisegundos
     * @param toolCallId llamada de herramienta id(Llegar tarde deja huella/para diagnóstico)
     * @param block Ejecución de herramienta de bloqueo(Puede contener en su interior runBlocking)
     * @return Resultado de la ejecución;Regreso del tiempo de espera null(La persona que llama lo maneja de acuerdo con la semántica de tiempo de espera existente.)
     */
    private suspend fun executeBlockingToolWithHardTimeout(timeoutMs: Long, toolCallId: String, block: () -> String): String? {
        val gate = ToolResultGate { onLateResultDropped(toolCallId) }
        val future =
            blockingToolExecutor.submit {
                // Pasó primero por la puerta.:Devolver falso indica que la ejecución ha agotado el tiempo de espera y no es válida.,los resultados se descartan
                gate.publish(block())
            }
        return try {
            withTimeout(timeoutMs) {
                // El sondeo mantiene la corrutina en un punto de suspensión cancelable;done después get() Regresar inmediatamente,Sin bloqueo secundario
                while (!future.isDone) delay(50)
                try {
                    future.get()
                } catch (e: java.util.concurrent.ExecutionException) {
                    // Excepción de ejecución de descomprimido,Deje que la ruta de manejo de errores existente de la persona que llama se haga cargo
                    throw (e.cause ?: e)
                }
                gate.valueOrNull()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // P2-33: Desactive la puerta primero(Eliminar las cancelaciones tardías),Luego interrumpa el hilo dedicado e intente finalizar runBlocking y su suspensión interna.
            gate.abandon()
            future.cancel(true)
            abandonedToolCallIds.add(toolCallId)
            Logger.w(
                TAG,
                "Tiempo de espera de la herramienta,Cancelación de la reescritura tardía | toolCallId=$toolCallId | timeout=${timeoutMs}ms",
            )
            null
        }
    }

    /**
     * P2-33: El resultado de la ejecución del tiempo de espera no se publica.(tarde)entrada de diagnóstico.
     * El resultado en sí ha sido [ToolResultGate] descartar,Aquí sólo contamos y dejamos huellas.,No se escribe ningún estado.
     */
    private fun onLateResultDropped(toolCallId: String) {
        lateDroppedResultCount.incrementAndGet()
        Logger.w(TAG, "Descartar resultados tardíos de las herramientas de tiempo de espera(Reescritura cortada) | toolCallId=$toolCallId")
    }

    // M3.2 Cableado:cuerpo funcional/La complejidad queda en la historia de este documento.(aprobación,Hook, almacenamiento de medios, agregación de múltiples rutas de tarjetas de tareas),
    // Esta vez sólo se dará a conocer el nuevo presupuesto./Dos párrafos de acuerdo;La división general es una tarea de reconstrucción independiente(precedente:A5 LargeClass Notas de exención).
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    private suspend fun executeSingleToolCall(
        params: ToolLoopParams,
        taskCardId: String?,
        tc: ToolCall,
        idx: Int,
        host: ToolLoopHost,
        taskCardCoordinator: ChatTaskCardCoordinator,
        execPolicy: ToolExecutionPolicy,
        /** Phase 3: Bloqueo de escritura de estado por turno para rondas simultáneas de solo lectura;La ruta serial es null(Sin gastos generales). */
        stateLock: Mutex?,
        /** Phase 3: La aprobación de rondas simultáneas de solo lectura solicita un bloqueo de escritura(No retenido durante el período pendiente de aprobación stateLock). */
        approvalLock: Mutex?,
        inlineOutputBudgetTokens: Int?,
    ): ToolExecResult {
        if (!params.allowToolExecution) {
            val finishedAt = System.currentTimeMillis()
            val pausedText = "Herramientas ${tc.name} En pausa, no se realizan operaciones reales en esta ronda."
            val safeToolName = tc.name.replace("\\", "\\\\").replace("\"", "\\\"")
            withTurnLock(stateLock) {
                taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
                    s.copy(
                        status = ToolExecStatus.CANCELLED.toTaskStepStatus(),
                        result = pausedText,
                        finishedAt = finishedAt,
                    )
                }
            }
            return ToolExecResult(
                idx = idx,
                tc = tc,
                finalToolResult = """{"error":"tool_execution_paused","tool":"$safeToolName"}""",
                isSuccess = false,
                displayResult = pausedText,
                status = ToolExecStatus.CANCELLED,
                finishedAt = finishedAt,
            )
        }
        // La pausa de una sola herramienta es un estado dinámico:El usuario puede realizar en el paso anterior/Las convocatorias posteriores quedan suspendidas durante el periodo de aprobación.
        // Verifique solo antes de que comiencen los efectos secundarios reales,No mate a la fuerza las herramientas que ya están en tránsito.
        if (taskCardCoordinator.isTaskStepPauseRequested(taskCardId, tc.id, idx)) {
            return pausedToolCallResult(
                taskCardId = taskCardId,
                tc = tc,
                idx = idx,
                taskCardCoordinator = taskCardCoordinator,
                stateLock = stateLock,
                host = null,
                toolStartAt = 0L,
            )
        }
        // M3.2: liberación del presupuesto unificado —— llamadas totales/fracasos consecutivos/Huellas dactilares duplicadas/Cortocircuito cuando llega el presupuesto de tiempo,
        // Sin aprobación y sin implementación de herramientas reales.;El motivo de la interceptación se devuelve al modelo y a la tarjeta de tarea como resultado de síntesis.
        // Phase 3: execPolicy Utilice diseños en orden de una sola vuelta.,Las rondas simultáneas se lanzan en serie a través de stateLock.
        val policyDecision = withTurnLock(stateLock) { execPolicy.beforeExecute(tc.name, tc.arguments) }
        if (!policyDecision.allowed) {
            Logger.w(
                TAG,
                "Llamada de herramienta bloqueada por presupuesto | reason=${policyDecision.reason} | ${policyDecision.detail} | " +
                    "sessionId=${params.sessionId} | traceId=${params.traceId} | tool=${tc.name}",
            )
            val blockedResult = """{"error": "tool_execution_policy_blocked", "reason": "${policyDecision.reason}"}"""
            withTurnLock(stateLock) {
                taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
                    s.copy(
                        status = TaskStepStatus.FAILED,
                        result = "Herramientas ${tc.name} No ejecutado(${policyDecision.reason})",
                        finishedAt = System.currentTimeMillis(),
                    )
                }
            }
            return ToolExecResult(
                idx = idx,
                tc = tc,
                finalToolResult = blockedResult,
                isSuccess = false,
                displayResult = "Herramientas ${tc.name} No ejecutado(${policyDecision.reason})",
                status = ToolExecStatus.FAILED,
            )
        }
        // v1.x: Notificar a la herramienta host para iniciar la ejecución(Incluyendo el tiempo de espera de aprobación),para un progreso detallado
        val toolStartAt = System.currentTimeMillis()
        withTurnLock(stateLock) { host.onToolStart(tc.id, tc.name) }

        // v1.0.53: Parámetros de análisis unificados(Hook La interceptación se comparte con aprobación parametrizada.)
        val paramsMap = parseToolCallArgs(tc.arguments)

        // P1-1: ToolLifecycleHook.onToolCallRequested — Llamadas de herramientas interceptables
        if (hookRegistry != null) {
            var blocked: io.zer0.muse.hook.ToolCallAction.Block? = null
            // Phase 3: hook El registro no es simultáneamente seguro,Rondas simultáneas de llamadas en serie de stateLock
            withTurnLock(stateLock) {
                hookRegistry.executeNoResult(io.zer0.muse.hook.ToolLifecycleHook::class) { hook ->
                    if (blocked != null) return@executeNoResult
                    when (val action = hook.onToolCallRequested(tc.name, paramsMap)) {
                        is io.zer0.muse.hook.ToolCallAction.Block -> blocked = action
                        is io.zer0.muse.hook.ToolCallAction.Allow -> { /* continuar */ }
                    }
                }
            }
            if (blocked != null) {
                val blockResult = """{"error": "Tool blocked by hook", "reason": "${blocked.reason}"}"""
                // v2.6.2(Auditoría de herramientas): Terminando con el mismo calibre que Denied —— Escribir paso de estado final + borrar pendiente,
                // De lo contrario, la llamada puede tratarse como una llamada incompleta y volver a ejecutarse durante la recuperación del reinicio.
                withTurnLock(stateLock) {
                    taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
                        s.copy(
                            status = TaskStepStatus.FAILED,
                            result = blockResult,
                            finishedAt = System.currentTimeMillis(),
                        )
                    }
                    PendingToolCallStore.updateState(tc.id, PendingToolCallStore.ABORTED, "hook_blocked")
                    host.onToolFinish(tc.id, tc.name, false, System.currentTimeMillis() - toolStartAt)
                }
                cleanupPendingToolCall(tc.id)
                return ToolExecResult(idx, tc, blockResult, false, status = ToolExecStatus.FAILED)
            }
        }

        // Verificación de aprobación de herramienta(v1.0.53: Pase argumentos completos para la determinación de permisos parametrizados)
        // F-07: Visibilidad pendiente de aprobación — El proceso de registros de respuesta de usuario pendientes de registro de estado APPROVAL_PENDING
        Logger.d(TAG, "Herramientas ${tc.name} Esperando aprobación(APPROVAL_PENDING) | sessionId=${params.sessionId}")
        withTurnLock(stateLock) {
            PendingToolCallStore.updateState(tc.id, PendingToolCallStore.APPROVAL_PENDING)
            // Make the wait visible on the task card: otherwise a pending approval looks
            // identical to a running tool until the 30s timeout silently denies it.
            taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
                s.copy(progressText = "Esperando aprobación…")
            }
        }
        // Phase 3: Los mensajes de aprobación aparecen uno por uno en rondas simultáneas(approvalLock),Evite que se cuelguen varios cuadros de diálogo de aprobación al mismo tiempo;
        // No retenido mientras se espera al usuario stateLock,Las escrituras de estado de otras herramientas no están bloqueadas.
        val approvalState =
            withTurnLock(approvalLock) {
                host.requestToolApproval(tc.name, tc.id, tc.arguments.take(200), paramsMap)
            }
        withTurnLock(stateLock) {
            PendingToolCallStore.updateState(tc.id, PendingToolCallStore.EXECUTING)
            taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
                if (s.progressText == "Esperando aprobación…") s.copy(progressText = null) else s
            }
        }

        // P1-1: ToolLifecycleHook.onToolPermissionChecked
        if (hookRegistry != null) {
            val approved = approvalState !is ToolApprovalState.Denied
            withTurnLock(stateLock) {
                hookRegistry.executeNoResult(io.zer0.muse.hook.ToolLifecycleHook::class) { hook ->
                    hook.onToolPermissionChecked(tc.name, approved)
                }
            }
        }

        if (approvalState is ToolApprovalState.Denied) {
            val deniedResult = """{"error": "Tool denied by user", "reason": "${approvalState.reason}"}"""
            withTurnLock(stateLock) {
                taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
                    s.copy(
                        status = TaskStepStatus.CANCELLED,
                        result = deniedResult,
                        finishedAt = System.currentTimeMillis(),
                    )
                }
                PendingToolCallStore.updateState(
                    tc.id,
                    PendingToolCallStore.ABORTED,
                    "user_denied",
                )
            }
            cleanupPendingToolCall(tc.id)
            withTurnLock(stateLock) {
                host.onToolFinish(tc.id, tc.name, false, System.currentTimeMillis() - toolStartAt)
            }
            return ToolExecResult(idx, tc, deniedResult, false, status = ToolExecStatus.DENIED)
        }

        // v2.6.2(Auditoría de herramientas): Answered La semántica es“El usuario proporciona una respuesta personalizada, la herramienta no se ejecuta”.
        // La implementación anterior solo provocó un cortocircuito. Denegado,Answered Continuará fusionando parámetros y realmente se ejecutará. —— La semántica es lo opuesto a la implementación.
        // Aquí Respondido se considera el estado final: la respuesta del usuario se devuelve como resultado de la herramienta y no se ejecuta.
        if (approvalState is ToolApprovalState.Answered) {
            val answeredResult = """{"user_answer": "${approvalState.answer}"}"""
            withTurnLock(stateLock) {
                taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
                    s.copy(
                        status = TaskStepStatus.SUCCESS,
                        result = approvalState.answer,
                        finishedAt = System.currentTimeMillis(),
                    )
                }
                PendingToolCallStore.updateState(tc.id, PendingToolCallStore.ABORTED, "user_answered")
            }
            cleanupPendingToolCall(tc.id)
            withTurnLock(stateLock) {
                host.onToolFinish(tc.id, tc.name, true, System.currentTimeMillis() - toolStartAt)
            }
            return ToolExecResult(idx, tc, answeredResult, true, status = ToolExecStatus.SUCCESS)
        }

        // Durante la ventana emergente de aprobación, los usuarios también pueden pausar las herramientas que aún no se han iniciado.;Inspección de puerta nuevamente después de la aprobación de la devolución.,
        // Asegúrese de que la aprobación no omita la intención de pausa que el usuario acaba de establecer.
        if (taskCardCoordinator.isTaskStepPauseRequested(taskCardId, tc.id, idx)) {
            return pausedToolCallResult(
                taskCardId = taskCardId,
                tc = tc,
                idx = idx,
                taskCardCoordinator = taskCardCoordinator,
                stateLock = stateLock,
                host = host,
                toolStartAt = toolStartAt,
            )
        }

        // P2-4: Registro de auditoría — Herramienta de aprobación y liberación de usuarios
        if (approvalState is ToolApprovalState.Approved) {
            withTurnLock(stateLock) {
                auditLogger?.log(
                    category = "user_action",
                    action = "approve_tool",
                    target = tc.id,
                    detail = mapOf("tool" to tc.name),
                )
            }
        }

        // v1.x: Parámetros anulados por los usuarios durante la fase de aprobación(Como el mapa local reference_image de generate_image)fusionarse en tc.arguments
        // fusionar reglas: argOverrides Las claves en LLM anulan las claves del mismo nombre en el JSON original de LLM;Cuando falla el análisis JSON sin formato, simplemente use overrides
        val effectiveArguments =
            if (approvalState is ToolApprovalState.Approved && approvalState.argOverrides.isNotEmpty()) {
                mergeToolArguments(tc.arguments, approvalState.argOverrides)
            } else {
                tc.arguments
            }

        val stepStartedAt = System.currentTimeMillis()

        // delegate_agent Analiza el nombre del asistente antes de comenzar el paso.,Actualizar título del paso/texto de progreso
        val delegateAgentInfo =
            if (tc.name == "delegate_agent") {
                resultOf {
                    TaskCardData.parseDelegateAgentArgs(tc.arguments)
                }.getOrNull()
            } else {
                null
            }
        val assistantName =
            delegateAgentInfo?.assistantId?.takeIf { it.isNotBlank() }?.let { id ->
                resultOf { assistantRepository.getById(id)?.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: id
            }
        val stepTitle = if (assistantName != null) "confiado a $assistantName" else tc.name
        val stepProgress = if (assistantName != null) "Delegar a $assistantName..." else null
        val claimedForExecution =
            withTurnLock(stateLock) {
                taskCardCoordinator.markTaskStepRunningIfNotPaused(
                    taskCardId = taskCardId,
                    toolCallId = tc.id,
                    fallbackIndex = idx,
                ) { s ->
                    s.copy(
                        title = stepTitle,
                        status = TaskStepStatus.RUNNING,
                        startedAt = stepStartedAt,
                        progressText = stepProgress,
                        pauseRequested = false,
                    )
                }
            }
        if (!claimedForExecution) {
            return pausedToolCallResult(
                taskCardId = taskCardId,
                tc = tc,
                idx = idx,
                taskCardCoordinator = taskCardCoordinator,
                stateLock = stateLock,
                host = host,
                toolStartAt = toolStartAt,
            )
        }

        // Revisión de corrección (2.0 B-14): subagent_task Cuando se omite parent_session_id, el lado de ejecución lo completará. —
        // Los resultados asincrónicos deben pertenecer a la sesión de inicio.,De lo contrario, guárdelo con una clave de cadena vacía. DeferredResultStore,
        // Inyectado por consumir degradación sin dueño"Sesión actualmente activa",Puede cruzar el canal a otras sesiones.
        // Lado de ejecución(esta oficina)es el único que sabe con certeza dónde se inició la sesión,No depende de LLM para pasar parámetros automáticamente.
        val subagentSessionFix =
            if (tc.name == "subagent_task") {
                val hasParent =
                    runCatching {
                        AppJson.parseToJsonElement(effectiveArguments)
                            .jsonObject["parent_session_id"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
                    }.getOrDefault(false)
                if (hasParent) {
                    effectiveArguments
                } else {
                    mergeToolArguments(effectiveArguments, mapOf("parent_session_id" to params.sessionId))
                }
            } else {
                effectiveArguments
            }

        val effectiveToolTimeoutMs = ToolExecutionTimeoutPolicy.forTool(tc.name, subagentSessionFix, toolTimeoutMs)

        // v2.6.7: Comprobación previa del canal de ejecución previa —— Las herramientas sin canales preparados están condenadas al fracaso,Detenlos con anticipación y dales la ruta de apertura.,
        // En lugar de dejar que el modelo acepte al usuario primero y luego choque contra la pared. Cuando el anfitrión no proporciona información del canal(null)saltar,Mantener el comportamiento original.
        val preflight = ToolChannelPreflight.checkPreflightable(tc.name, host.availableDeviceChannels())
        if (preflight is ToolChannelPreflight.Result.Blocked) {
            Logger.w(TAG, "Intercepción de verificación previa del canal ${tc.name}: desaparecido ${preflight.missing.joinToString(",")}")
        }

        // herramienta de ejecución:skill ir SkillExecutor(Pendiente, Cancelable),Las herramientas locales van ToolRegistry
        // P2-18: herramientas de bloqueo(Ejecución internaBloqueo de puentes WebView/archivo IO)No se puede cancelar mediante corrutina,
        // Se debe utilizar un grupo de subprocesos dedicado + future.cancel(true),De lo contrario, el tiempo de espera externo será inútil.(tiempo de espera falso).
        val route = params.routeSnapshot?.routeFor(tc.name)
        val skill = params.skillMap[tc.name]
        val rawToolResultOrNull: String? =
            if (preflight is ToolChannelPreflight.Result.Blocked) {
                preflight.message
            } else if (params.routeSnapshot != null && route == null) {
                "Error: tool '${tc.name}' is not exposed in this turn"
            } else if (route is ToolRouteSnapshot.Route.Skill || (route == null && skill != null)) {
                val skillToExecute = (route as? ToolRouteSnapshot.Route.Skill)?.skill ?: skill!!
                withTimeoutOrNull(effectiveToolTimeoutMs) {
                    skillExecutor.execute(
                        skill = skillToExecute,
                        argumentsJson = effectiveArguments,
                        onProgress = { msg ->
                            // Phase 3: onProgress Solo se puede acceder a las ramas de ejecución de habilidades,La herramienta de enrutamiento de habilidades no entrará
                            // Rondas simultáneas de solo lectura(shouldExecuteRoundInParallel excluir skill),Aquí no se requiere bloqueo.
                            taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
                                s.copy(progressText = msg)
                            }
                        },
                        turnKey = params.turnId.ifBlank { params.traceId.ifBlank { params.sessionId } },
                        sessionId = params.sessionId,
                    )
                }
            } else {
                executeBlockingToolWithHardTimeout(effectiveToolTimeoutMs, tc.id) {
                    // Las herramientas de bloqueo se ejecutan en un hilo dedicado;Se utiliza el ejecutable de suspensión de nivel superior desde Json. runBlocking
                    // Puente como llamada de bloqueo(Mismo hilo que runBlocking dentro de la herramienta,Las interrupciones son entregables),Los tiempos de espera pueden realmente interrumpirse
                    kotlinx.coroutines.runBlocking {
                        // v1.x: Herramientas del navegador Enrutamiento por sesión — Independiente por sesión BrowserManager(WebView),
                        // Evite la conversación cruzada entre sesiones(Sesión A Cerrar el navegador no afecta la sesión B).
                        if (tc.name in BROWSER_TOOL_NAMES) {
                            val sessionBm = browserManagerRegistry.getForSession(params.sessionId)
                            val argsMap =
                                runCatching {
                                    AppJson.decodeFromString(JsonObject.serializer(), subagentSessionFix)
                                        .entries.associate { (k, v) -> k to v.toString().trim('"') }
                                }.getOrDefault(emptyMap())
                            BrowserAutomationTool.executeFromArgs(tc.name, argsMap, sessionBm)
                        } else {
                            if (params.toolExecutionContext != null) {
                                if (params.routeSnapshot == null) {
                                    toolRegistry.executeFromJson(tc.name, subagentSessionFix, params.toolExecutionContext)
                                } else {
                                    routeGuard.executeFromJson(
                                        tc.name,
                                        subagentSessionFix,
                                        params.routeSnapshot,
                                        params.toolExecutionContext,
                                    )
                                }
                            } else {
                                if (params.routeSnapshot == null) {
                                    toolRegistry.executeFromJson(tc.name, subagentSessionFix)
                                } else {
                                    routeGuard.executeFromJson(tc.name, subagentSessionFix, params.routeSnapshot)
                                }
                            }
                        }
                    }
                }
            }
        val timedOut = rawToolResultOrNull == null
        val rawToolResult =
            rawToolResultOrNull
                ?: "[tiempo de espera] Herramientas ${tc.name} ${effectiveToolTimeoutMs / 1000} Sin respuesta por segundos,terminado"
        // Algunas herramientas que sólo producen efectos secundarios externos pueden devolver cadenas vacías. Independientemente de si los efectos secundarios han sido exitosos,
        // Tanto la interfaz de usuario como la siguiente ronda de modelos deben recibir un texto de estado final claro.,Evite que la tarjeta parezca que todavía está esperando.
        val toolResult = normalizeToolResult(tc.name, rawToolResult)

        val isSuccess = taskCardCoordinator.isToolResultSuccess(toolResult)
        // Phase 3: El estado final de ejecución de la herramienta es consistente con ToolExecStatus en la tarjeta de tareas. —
        // tiempo de espera → TaskStepStatus.TIMED_OUT(ya no ser isSuccess=false doblado en FAILED).
        val execStatus =
            when {
                timedOut -> ToolExecStatus.TIMED_OUT
                isSuccess -> ToolExecStatus.SUCCESS
                else -> ToolExecStatus.FAILED
            }
        // Seleccione pase directo o por presupuesto de contexto restante"Colocación completa + referencia del archivo";
        // Cuando se necesita más contenido, el asistente puede usar el cursor de caracteres de read_file para leer por partes.
        // Las representaciones de resultados se generan solo una vez, lo que muestra que la pantalla comparte el mismo texto completo o cita de archivo completo que el LLM.
        // El arranque fallido solo ingresa al historial de LLM,No filtrado a tarjetas de tareas visibles para el usuario/Tarjetas de herramientas.
        val baseResult = prepareToolOutput(tc.id, tc.name, toolResult, inlineOutputBudgetTokens)
        // v1.0.47 P2-1: Guía de fallas estructurada — Solo las noticias históricas de LLM.,Evite bucles de reintento no válidos
        val llmResult =
            if (isSuccess) {
                baseResult
            } else {
                // v2.6.7: Primero averigüe a qué categoría pertenece esta falla y si se puede volver a intentar.,Dame más orientación ——
                // Hasta ahora solo se daban cuatro niveles de prioridad fijos.,El modelo tiene que adivinar a partir del propio texto del error.,Adivinar mal desperdicia tu única oportunidad de corregir.
                val verdict = ToolFailureClassifier.classify(baseResult, timedOut = timedOut)
                val attempt = execPolicy.recordToolFailure(tc.name)
                // v2.6.7 fix (AUDIT F13): Al mismo tiempo, se proporciona un presupuesto de herramienta única y un presupuesto de falla continua para toda la ronda.
                // evitar“queda 1 vez”con“El bucle se ha detenido prematuramente”Dos calibres peleando.
                val budget = ToolRetryBudget.describeWithRoundBudget(
                    toolName = tc.name,
                    attempt = attempt,
                    retryable = verdict.retryable,
                    consecutiveFailures = execPolicy.consecutiveFailuresCount,
                    abortThreshold = execPolicy.consecutiveFailureAbortThreshold,
                )
                "$baseResult\n\n${ToolFailureClassifier.receiptLine(verdict)}\n$budget\n" +
                    "[La llamada a la herramienta no pudo iniciar] Por favor juzgue de acuerdo con las siguientes prioridades.:\n" +
                    "1. parámetros/Error de ruta → Vuelva a intentar esta herramienta después de realizar correcciones.(Máximo 1 vez)\n" +
                    "2. Permisos/Recurso no disponible → Cambiar a otra herramienta o informar a los usuarios sobre las limitaciones\n" +
                    "3. Tiempo de espera de la red → puedo intentarlo de nuevo,Si aún falla, notifique al usuario.\n" +
                    "4. No se puede resolver → Informar directamente a los usuarios de los motivos del fracaso y sugerencias.,no esperes"
            }
        val finalToolResult = llmResult
        // B-09: Mostrar texto de error puro,No incluye lenguaje introductorio al LLM.
        val displayResult = baseResult

        withTurnLock(stateLock) {
            taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
                s.copy(
                    status = execStatus.toTaskStepStatus(),
                    result = displayResult,
                    finishedAt = System.currentTimeMillis(),
                )
            }
        }

        cleanupPendingToolCall(tc.id)

        // P1-1: ToolLifecycleHook.onToolExecutionResult
        if (hookRegistry != null) {
            val execResult =
                io.zer0.muse.hook.ToolExecutionResult(
                    toolName = tc.name,
                    success = isSuccess,
                    output = finalToolResult,
                    durationMs = System.currentTimeMillis() - toolStartAt,
                )
            withTurnLock(stateLock) {
                hookRegistry.executeNoResult(io.zer0.muse.hook.ToolLifecycleHook::class) { hook ->
                    hook.onToolExecutionResult(execResult)
                }
            }
        }

        // Phase 3: host Devolución de llamada y liquidación del presupuesto de ejecución.(No es seguro para subprocesos)Escritura en serie unificada a través de stateLock
        val executionFinishedAt = System.currentTimeMillis()
        val (clampedResult, wasTruncated) =
            withTurnLock(stateLock) {
                host.onToolFinish(tc.id, tc.name, isSuccess, executionFinishedAt - toolStartAt)
                // M3.2: Ejecutar contabilidad(contar/combo fallido/Huellas dactilares duplicadas)+ Tamaño máximo de salida
                // (Aprobación rechazada/Se ha avanzado en el camino de la interceptación presupuestaria return,No contabilizado en llamadas)
                execPolicy.afterExecute(tc.name, tc.arguments, isSuccess)
                val clamped = execPolicy.clampOutput(finalToolResult)
                // Registre el número real de caracteres de relleno;La cantidad total de salida no está limitada de forma predeterminada.
                execPolicy.recordOutputChars(clamped.first.length)
                clamped
            }
        if (wasTruncated) {
            Logger.w(
                TAG,
                "Resultados de la herramienta truncados más allá de los límites | tool=${tc.name} | longitud original=${finalToolResult.length} | sessionId=${params.sessionId}",
            )
        }
        return ToolExecResult(
            idx,
            tc,
            clampedResult,
            isSuccess,
            displayResult = displayResult,
            // v2.6.0: Si los resultados de la herramienta incluyen capturas de pantalla(Herramienta de captura de pantalla del navegador),Extraerlo y dárselo a la persona que llama.,
            // Agrega un mensaje de usuario con una imagen para permitir que el modelo vea la representación de la página.
            screenshotBase64 = extractScreenshotBase64(toolResult, isSuccess),
            // Timeout must not be reported as a plain success/failure: callers use the
            // status to render the terminal state and to record a terminationReason.
            status = execStatus,
            durationMs = executionFinishedAt - toolStartAt,
        )
    }

    /**
     * Limpiar el registro del punto de interrupción una vez completada la herramienta,Pero no puedes permitir que los resultados del chat bloqueado de File IO se recuperen.
     *
     * Los registros de puntos de interrupción son datos auxiliares para las capacidades de recuperación.;Después de que se haya generado el resultado del mensaje,Se deben registrar las fallas de limpieza y se debe permitir que la ronda continúe hasta su finalización.
     */
    private suspend fun cleanupPendingToolCall(toolCallId: String) {
        val cleaned =
            withTimeoutOrNull(PENDING_TOOL_CLEANUP_TIMEOUT_MS) {
                PendingToolCallStore.remove(toolCallId)
                true
            } ?: false
        if (!cleaned) {
            Logger.w(TAG, "Limpiar el tiempo de espera de llamada de herramienta pendiente: $toolCallId")
        }
    }

    /** La herramienta no devuelve recibos visibles uniformes cuando se puede mostrar texto. */
    private fun normalizeToolResult(toolName: String, result: String): String = if (result.isBlank()) {
        "Herramientas $toolName Ejecución completada,Pero no se devuelve ningún contenido visualizable."
    } else {
        result
    }

    /**
     * Acabado uniforme con suspensión de una sola herramienta.
     *
     * Mantiene el mismo resultado del protocolo y el estado final CANCELADO que la pausa globalToolExecution,pero limpieza extra
     * Registros pendientes escritos,Evite que las rutas de recuperación de reinicio vuelvan a ejecutar efectos secundarios que el usuario pausó explícitamente.
     */
    private suspend fun pausedToolCallResult(
        taskCardId: String?,
        tc: ToolCall,
        idx: Int,
        taskCardCoordinator: ChatTaskCardCoordinator,
        stateLock: Mutex?,
        host: ToolLoopHost?,
        toolStartAt: Long,
    ): ToolExecResult {
        val finishedAt = System.currentTimeMillis()
        val pausedText = "Herramientas ${tc.name} En pausa, no se realizan operaciones reales en esta ronda."
        val safeToolName = tc.name.replace("\\", "\\\\").replace("\"", "\\\"")
        withTurnLock(stateLock) {
            taskCardCoordinator.updateTaskCardStepByToolCallId(taskCardId, tc.id) { step ->
                step.copy(
                    status = TaskStepStatus.CANCELLED,
                    result = pausedText,
                    finishedAt = finishedAt,
                    pauseRequested = false,
                )
            }
            // Compatible con tarjetas de tareas antiguas/Restaurar instantánea sin toolCallId.
            taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { step ->
                if (step.toolCallId.isBlank()) {
                    step.copy(
                        status = TaskStepStatus.CANCELLED,
                        result = pausedText,
                        finishedAt = finishedAt,
                        pauseRequested = false,
                    )
                } else {
                    step
                }
            }
            runCatching {
                PendingToolCallStore.updateState(
                    tc.id,
                    PendingToolCallStore.ABORTED,
                    "tool_paused",
                )
            }.onFailure { error ->
                Logger.w(TAG, "La escritura ABORTADA falló después de suspender la herramienta: ${tc.id}", error)
            }
        }
        cleanupPendingToolCall(tc.id)
        if (host != null) {
            host.onToolFinish(tc.id, tc.name, false, (finishedAt - toolStartAt).coerceAtLeast(0L))
        }
        return ToolExecResult(
            idx = idx,
            tc = tc,
            finalToolResult = """{"error":"tool_execution_paused","tool":"$safeToolName"}""",
            isSuccess = false,
            displayResult = pausedText,
            status = ToolExecStatus.CANCELLED,
            finishedAt = finishedAt,
        )
    }

    /**
     * Es posible que se hayan completado los efectos secundarios de la herramienta.,Sin embargo, el estado final es cuando el enlace de retorno aún es anormal.
     * Esta ruta garantiza específicamente que TaskCard,ToolCallCard y los registros pendientes ya no están estancados en la ejecución.
     */
    private suspend fun failedToolResult(
        taskCardId: String?,
        tc: ToolCall,
        idx: Int,
        host: ToolLoopHost,
        taskCardCoordinator: ChatTaskCardCoordinator,
        reason: String,
    ): ToolExecResult {
        val result = "Herramientas ${tc.name} La devolución de datos falló después de completarse la ejecución: $reason"
        taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { step ->
            step.copy(
                status = TaskStepStatus.FAILED,
                result = result,
                finishedAt = System.currentTimeMillis(),
            )
        }
        cleanupPendingToolCall(tc.id)
        host.onToolFinish(tc.id, tc.name, false, 0L)
        return ToolExecResult(
            idx = idx,
            tc = tc,
            finalToolResult = result,
            isSuccess = false,
            displayResult = result,
            status = ToolExecStatus.FAILED,
        )
    }

    /**
     * para“Argumento truncado por el límite superior de longitud de salida”La rueda de herramientas construye la lista de llamadas utilizada para el relleno.
     *
     * Los argumentos incompletos no se analizan, reparan ni ejecutan hasta el momento: la forma completada con la mitad de JSON nunca es la intención original del modelo.
     * Aquí solo se guardan los dos datos requeridos en el protocolo: nombre de la herramienta y tool_call_id.
     *  - arguments Reemplazar uniformemente con `{}` Marcador de posición: las llamadas a herramientas del asistente devueltas al proveedor deben ser JSON legales.
     *    De lo contrario, la siguiente ronda de solicitudes será“Assistant tool call arguments must be valid JSON”rechazar directamente;
     *  - Las llamadas que ni siquiera reciben el nombre de la herramienta no pueden describirse ni coincidir, por lo que se descarta toda la llamada (assistant Los elementos correspondientes desaparecen juntos,
     *    No quedará ningún tool_call sin resultado); regresar cuando todo esté descartado“No hay convocatoria válida en esta ronda”camino de cierre existente;
     *  - id Por ronda cuando falta o está duplicado+La síntesis de números de serie garantiza que cada llamada pueda identificarse mediante el resultado de su propia herramienta.
     */
    internal fun truncatedToolCallsForBackfill(raw: List<ToolCall>, round: Int): List<ToolCall> {
        val usedIds = mutableSetOf<String>()
        return raw.filter { it.name.isNotBlank() }.mapIndexed { idx, tc ->
            val id = tc.id.takeIf { it.isNotBlank() && it !in usedIds } ?: "truncated_call_${round}_$idx"
            usedIds += id
            ToolCall(id = id, name = tc.name, arguments = "{}")
        }
    }

    /** Recibo de truncamiento de parámetros: un código de error legible por máquina, más lo que el modelo debe hacer a continuación. */
    internal fun truncatedArgumentsToolResult(toolName: String): String {
        val safeToolName = toolName.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"error":"arguments_truncated_by_output_limit","tool":"$safeToolName",""" +
            """"detail":"Los parámetros de esta llamada fueron truncados por el límite superior de la longitud de salida cuando se generaron y no se ejecutaron;""" +
            """Reinicie la llamada con los parámetros completos. Si el contenido es demasiado largo, divídalo en varias llamadas más pequeñas."}"""
    }

    /**
     * Algunos modelos agrupan el mensaje completo en una llamada de herramienta:name está vacío o es ilegal,arguments Es toda una frase humana.
     *
     * Este tipo de llamada no se puede ejecutar, pero no está llena de parámetros, sino de lo que el modelo originalmente quería decirle al usuario.
     * Solo responde directamente"Formato de parámetro anormal"Convertirá una buena frase en un fracaso, así que la elimino aquí como texto principal.
     *
     * Sólo se acepta texto plano: con forma de datos estructurados (comenzando por { o [ Principio) no será respondido, eso significa que los parámetros están realmente rotos.
     * Debes tomar el camino que te indica que vuelvas a intentarlo.
     */
    private fun recoverProseFromInvalidToolCalls(raw: List<ToolCall>): String? {
        val joined = raw.joinToString(" ") { it.arguments.trim() }.trim()
        // Con forma de datos estructurados (con { o [ al principio) indica que los parámetros están realmente rotos y se le pedirá que vuelva a intentarlo;
        // Sólo se considera texto plano con letras lo que el modelo quiere decir.
        val looksStructured = joined.startsWith("{") || joined.startsWith("[")
        val isProse = joined.isNotEmpty() &&
            !looksStructured &&
            !ToolCallSanitizer.isValidJson(joined) &&
            joined.any { it.isLetter() }
        return if (isProse) joined else null
    }

    /** P1-1: Los parámetros JSON llamados por la herramienta de análisis son Map(Para uso con ToolLifecycleHook y aprobación parametrizada). */
    private fun parseToolCallArgs(argumentsJson: String): Map<String, Any> =
        runCatching { toolArgsToMap(toolRegistry.parseArgumentsLenient(argumentsJson)) }
            .getOrDefault(emptyMap())

    /**
     * v1.x: Agregar parámetros que los usuarios anulan durante la fase de aprobación(clave → valor)Fusionado en JSON de argumentos sin procesar de LLM.
     *
     * fusionar reglas:
     *  - El JSON sin formato se analiza como JsonObject,Reservado clave por clave;
     *  - [overrides] Las claves introducidas se sobrescriben con nuevos valores.(Ya existe)o agregar nuevo(no existe);
     *  - Cuando falla el análisis JSON sin formato,Sólo uso [overrides] Construye un nuevo JSON.
     * Todos los valores están escritos en forma de cadenas JSON.(Alinear las convenciones de análisis para ToolRegistry.executeFromJson:
     * `v.toString().trim('"')`).
     */
    internal fun mergeToolArguments(originalArguments: String, overrides: Map<String, String>): String {
        if (overrides.isEmpty()) return originalArguments
        val base: JsonObject =
            resultOf {
                AppJson.decodeFromString(JsonObject.serializer(), originalArguments)
            }.getOrNull() ?: JsonObject(emptyMap())
        val merged =
            buildJsonObject {
                base.forEach { (k, v) -> put(k, v) }
                overrides.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            }
        return merged.toString()
    }

    private suspend fun persistAssistantToolMsg(sessionId: String, msg: UIMessage, host: ToolLoopHost) {
        try {
            sessionRepository.upsertMessage(sessionId, msg)
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            throw ce
        } catch (e: Exception) {
            Logger.e("ToolOrchestrator", "upsertMessage(toolCalls) failed", e)
            host.onToolLoopError(
                ChatErrorType.TOOL_ERROR,
                "No se pudo guardar el registro de llamadas a la herramienta: ${e.message ?: "error desconocido"}",
            )
        }
    }

    private suspend fun savePendingToolCalls(
        sessionId: String,
        toolCalls: List<ToolCall>,
        identity: io.zer0.muse.session.GenerationIdentity,
        host: ToolLoopHost,
    ) {
        if (toolCalls.isEmpty()) return
        val now = System.currentTimeMillis()
        val pendings =
            toolCalls.map { tc ->
                PendingToolCallStore.PendingToolCall(
                    chatId = sessionId,
                    toolCallId = tc.id,
                    toolName = tc.name,
                    arguments = tc.arguments,
                    createdAt = now,
                    generationId = identity.generationId,
                    turnId = identity.turnId,
                )
            }
        try {
            PendingToolCallStore.saveAll(pendings)
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            throw ce
        } catch (e: Exception) {
            Logger.w("ToolOrchestrator", "PendingToolCallStore.saveAll falló: ${e.message}", e)
        }
    }

    /**
     * v2.x: Analice el límite superior de la ronda de este ciclo de herramienta.
     *  - requested <= 0:Ilimitado([UNLIMITED_TOOL_ROUNDS]) — Una vez que se liberan las configuraciones del usuario, las rondas ya no interrumpirán las tareas largas.,
     *    Llamar repetidamente a la huella digital/Sin detección de progreso/Fallo continuo y parada anticipada./Las protecciones presupuestarias, como el número total de llamadas, siguen teniendo efecto de forma independiente.
     *  - requested > 0:Límite superior fijo establecido por el usuario(No más expansión dinámica task_plan superpuesta)
     */
    internal fun resolveToolRoundLimit(requested: Int): Int = if (requested <= 0) UNLIMITED_TOOL_ROUNDS else requested

    /**
     * v1.x: Calcule dinámicamente las rondas máximas de llamadas de herramientas en función de la complejidad de la tarea(Reservar:Referencia de prueba y transmisión automática.;Los enlaces de producción ahora están controlados por la configuración del usuario.).
     *
     * Estrategia:
     *  - si ya hay task_plan(AgentPlan caché o historial tool_call),por número de pasos * 2 + 5 extrapolar
     *  - De lo contrario, las tareas simples están predeterminadas en [DEFAULT_MAX_TOOL_ROUNDS](10 rueda)
     *  - límite superior [MAX_TOOL_ROUNDS_HARD_CAP](25)Ten todo en cuenta,y no más que [hardCap](Compatible con versiones anteriores de llamadas pasadas params.maxRounds)
     */
    internal fun computeMaxRounds(messages: List<UIMessage>, hardCap: Int = MAX_TOOL_ROUNDS_HARD_CAP, sessionId: String? = null): Int {
        val planSteps = countTaskPlanSteps(messages, sessionId)
        val base = if (planSteps > 0) planSteps * 2 + 5 else DEFAULT_MAX_TOOL_ROUNDS
        return minOf(base, MAX_TOOL_ROUNDS_HARD_CAP, hardCap)
    }

    /**
     * Cuente el número de pasos en task_plan:Priorizar planes activos en la memoria de SkillExecutor,
     * Alternativa para analizar el parámetro de pasos de la llamada a la herramienta task_plan a partir de mensajes históricos(Reanudar la carga desde el punto de interrupción/Continuar escenario de conversación).
     */
    internal fun countTaskPlanSteps(messages: List<UIMessage>, sessionId: String? = null): Int {
        val activePlanSteps =
            (
                if (sessionId == null) {
                    skillExecutor.getActivePlans()
                } else {
                    skillExecutor.getActivePlans(sessionId)
                }
                ).values.sumOf { it.steps.size }
        if (activePlanSteps > 0) return activePlanSteps
        return messages.asSequence()
            .filter { it.role == MessageRole.ASSISTANT }
            .flatMap { (it.toolCalls ?: emptyList()).asSequence() }
            .filter { it.name == "task_plan" }
            .mapNotNull { parseTaskPlanStepCount(it.arguments) }
            .maxOrNull() ?: 0
    }

    /**
     * Analiza la longitud de la matriz de pasos a partir de los argumentos JSON de la llamada a la herramienta task_plan.
     * Devolver si falla el análisis null(tolerancia a fallas,No afecta el proceso principal.).
     */
    internal fun parseTaskPlanStepCount(argumentsJson: String): Int? {
        return resultOf {
            val obj = AppJson.decodeFromString(JsonObject.serializer(), argumentsJson)
            val stepsEl = obj["steps"] ?: return@resultOf 0
            val stepsStr = AppJson.encodeToString(JsonElement.serializer(), stepsEl)
            AppJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(JsonElement.serializer()),
                stepsStr,
            ).size
        }.getOrNull()
    }

    /**
     * v1.x: Calcular la firma de una ronda de listas de llamadas a herramientas(Utilizado para la detección de atascos).
     *
     * firma = Ordenado por identificación "name(arguments)" empalme,El orden no importa,Evite errores de cálculo por fluctuación de secuencia de herramientas en paralelo.
     * La lista vacía devuelve una cadena vacía(No participar en la detección atascada).
     */
    internal fun toolCallSignature(toolCalls: List<ToolCall>): String {
        if (toolCalls.isEmpty()) return ""
        return toolCalls.sortedBy { it.id }
            .joinToString("|") { "${it.name}(${it.arguments})" }
    }

    internal fun extractWebSearchUrls(result: String): List<String> {
        val regex = Regex("""^\s*URL:\s*(.+)$""", RegexOption.MULTILINE)
        return regex.findAll(result).map { it.groupValues[1].trim() }.toList()
    }

    /**
     * Priorizar el envío del resultado completo a la ventana del modelo actual; si no cabe guardar el texto completo y enviar sólo la vista previa y las instrucciones de lectura segmentadas.
     * Cuando falla la escritura en el disco, el resultado de la memoria original se conserva y la cola ya no se recorta silenciosamente.
     *
     * La dirección de vista previa se selecciona según la semántica de la herramienta:[keepsOutputTail] Deje la cola para verdaderas herramientas de comando (informar un error/caso de uso fallido/Los códigos de salida están al final),
     * El resto (lectura de archivos, resultados de búsqueda, etc.) todavía se consideran de principio a fin.——El comienzo es la información de posicionamiento más utilizada.
     */
    private suspend fun prepareToolOutput(
        toolCallId: String,
        toolName: String,
        output: String,
        inlineBudgetTokens: Int?,
    ): String {
        if (output.length <= SMALL_TOOL_RESULT_INLINE_CHARS) return output
        val estimatedTokens = TokenEstimator.estimate(listOf(UIMessage(role = MessageRole.TOOL, content = output)))
        if (canInlineToolOutput(output.length, estimatedTokens, inlineBudgetTokens)) return output

        // Vista previa del truncamiento seguro por punto de código:La toma directa pondrá al agente en (emoji etc.) cortado por la mitad,
        // Y decirle al modelo las omisiones para evitar que piense que lo que ve lo es todo.
        val keepTail = keepsOutputTail(toolName)
        val preview =
            if (keepTail) {
                TextTruncation.tailText(output, TOOL_RESULT_PREVIEW_CHARS)
            } else {
                TextTruncation.headTailText(output, TOOL_RESULT_PREVIEW_CHARS)
            }
        val fileName = "tool_output_${toolCallId}_${System.currentTimeMillis()}.txt"

        return try {
            val dir = File(context.filesDir, TOOL_OUTPUTS_DIR)
            val file = File(dir, fileName)
            // tiempo de espera independiente:Creación de directorio + escritura en disco;Tiempo de espera para retener el texto original completo actualmente en la memoria
            val written =
                withTimeoutOrNull(toolOutputWriteTimeoutMs) {
                    if (!dir.exists()) dir.mkdirs()
                    toolOutputWriter(file, output)
                    true
                }
            if (written == null) {
                Logger.w(
                    TAG,
                    "Tiempo de espera de colocación del disco de salida de herramienta(${toolOutputWriteTimeoutMs}ms),Mantener los resultados de la memoria llena" +
                        " | toolCallId=$toolCallId | longitud total=${output.length}",
                )
                return output + "\n\n[El resultado completo del tiempo de espera de colocación; el texto original completo de este mensaje permanece]"
            }
            Logger.i(
                TAG,
                "Colocación completa de salida de herramienta: toolCallId=$toolCallId | longitud total=${output.length}" +
                    " | El resultado completo ha sido publicado.: ${file.absolutePath}",
            )
            buildString {
                append("[Resultados completos guardados, total ${output.length} personaje]\n")
                // Emit the absolute path so the UI can render it as an openable attachment
                // chip; the previous "/tool_outputs/..." form matched no extractor pattern.
                append("[El resultado completo se ha guardado en: ${file.absolutePath}]\n")
                append(
                    "[Utilice workspace_read para leer segmentadamente cuando se requiera contenido completo.: path=$TOOL_OUTPUTS_DIR:/$fileName, " +
                        "offset_chars=0, length_chars=$TOOL_OUTPUT_READ_PAGE_CHARS;" +
                        "También puedes usar read_file para leer (path=$TOOL_OUTPUTS_DIR/$fileName)]\n\n",
                )
                append(preview)
                // Al obtener una vista previa de la cola, se informa claramente que se omite el comienzo; de lo contrario, el modelo tratará las últimas líneas como el comienzo del resultado completo.
                append(
                    if (keepTail) {
                        "\n\n[Lo anterior es una vista previa al final de los resultados, no los resultados completos; el comienzo comienza con offset_chars=0 Lectura segmentada]"
                    } else {
                        "\n\n[Lo anterior es una vista previa, no el resultado completo; por favor presione el cursor del carácter para continuar leyendo la parte requerida]"
                    },
                )
            }
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            throw ce
        } catch (e: Exception) {
            // No escribir en el disco no descarta el texto original que aún está en la memoria; el resultado es posteriormente presupuestado por el contexto/La ventana del modelo se procesa normalmente.
            Logger.w(TAG, "La salida de la herramienta falló en el disco,Mantener los resultados de la memoria llena: ${e.message}", e)
            output + "\n\n[La salida completa falló; el texto original completo de este mensaje permanece]"
        }
    }
}

/**
 * v1.x: Limpiar más de [TOOL_OUTPUT_RETENTION_MS] archivo de salida de la herramienta.
 *
 * Cuando se inicia la aplicación(MuseApp.onCreate)llamar una vez,Evite la acumulación ilimitada del directorio tool_outputs/.
 * Recorre todos los archivos .txt en el directorio,Determinar si ha caducado por última modificación,Eliminar archivos caducados.
 * Las operaciones de eliminación fallidas solo se registran,No afecta la limpieza de otros archivos.
 */
fun cleanupOldToolOutputs(context: Context, retentionMs: Long = TOOL_OUTPUT_RETENTION_MS) {
    val dir = File(context.filesDir, TOOL_OUTPUTS_DIR)
    if (!dir.exists() || !dir.isDirectory) return
    val cutoff = System.currentTimeMillis() - retentionMs
    var deleted = 0
    dir.listFiles()?.forEach { f ->
        if (!f.isFile) return@forEach
        val mtime = runCatching { f.lastModified() }.getOrDefault(0L)
        if (mtime > 0 && mtime < cutoff) {
            runCatching { f.delete() }
                .onSuccess { deleted++ }
                .onFailure { Logger.w("ToolOrchestrator", "Error al eliminar la salida de la herramienta caducada: ${f.name}", it) }
        }
    }
    if (deleted > 0) {
        Logger.i("ToolOrchestrator", "Limpiar archivos de salida de herramientas obsoletos: Eliminar $deleted un")
    }
}

/**
 * v1.0.54: Extraiga la ruta absoluta del paquete de emoticonos del texto(El mismo patrón regular que MessageBubble.extractStickerPaths).
 * send_sticker La herramienta Mostrar contenido solo mantiene la ruta.,Para que MessageBubble represente imágenes de pegatinas.
 */
private val STICKER_PATH_PATTERN =
    Regex(
        """(/[^\s\]]*?/stickers/[^\s\]]+\.(?:png|jpg|jpeg|gif|webp|bmp))""",
        RegexOption.IGNORE_CASE,
    )

private fun extractStickerPaths(text: String): List<String> =
    STICKER_PATH_PATTERN.findAll(text).map { it.groupValues[1] }.distinct().toList()
