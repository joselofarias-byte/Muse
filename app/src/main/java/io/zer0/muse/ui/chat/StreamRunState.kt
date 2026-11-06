package io.zer0.muse.ui.chat

import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.RagCitation
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.ExperimentsConfig
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.privacy.PiiGuard
import io.zer0.muse.transformer.TransformContext
import kotlin.uuid.Uuid

/**
 * Contenedores de estado mutables durante la generación de streaming.
 *
 * v1.97: builder/reasoningBuilder/currentAssistantId/piiMatches Mencionar declaración fuera del bloque try(Ingresos actuales en esta categoría),
 * Hacer que los bloques de captura también sean accesibles —— Después de cortar la página, el bloque catch utiliza el contenido del generador + currentAssistantId para construir una respuesta parcial al pedido.,
 * PII Guard Restaurar la evitación de marcadores de posición [PHONE_001] Espere a que el marcador de posición persista en la base de datos.
 *
 * v1.0.27 Phase 4-A.1: Extraiga la clase privada dentro de ChatViewModel a un archivo separado,La visibilidad cambió a internal,
 * Para que ChatStreamCoordinator pueda acceder directamente al campo de estado después de asumir la función de preparación de 6 pasos de transmisión.
 * ChatViewModel La creación de instancias de StreamRunState y el acceso al bloque de captura aún se conservan,until En el siguiente paso también se retira el pestillo.
 */
internal class StreamRunState(
    val sessionId: String,
    val assistantId: Uuid,
    val isNewBranch: Boolean,
) {
    /**
     * F-12: Enlace unificado generado en esta ronda id — A lo largo de las solicitudes de LLM/ejecución de herramientas/memoria/Registro y auditoría de errores,
     * Para que AuditLogPage recupere toda la cadena de llamadas mediante traceId.
     */
    val traceId: String = kotlin.uuid.Uuid.random().toString()

    /** shadow La identidad de la ronda, separada del streamId del proveedor, los eventos antiguos no se pueden escribir en rondas. */
    val turnId: String = kotlin.uuid.Uuid.random().toString()
    val streamId: String = kotlin.uuid.Uuid.random().toString()

    /** generación cruzada,LLM Los flujos y las herramientas representan identidades generacionales compartidas.traceId Como el único ID de generación generado esta vez. */
    val generationIdentity: io.zer0.muse.session.GenerationIdentity
        get() = io.zer0.muse.session.GenerationIdentity(
            sessionId = sessionId,
            turnId = turnId,
            generationId = traceId,
            streamId = streamId,
        )
    var shadowTurnStarted: Boolean = false

    // Phase A: prepareHistory
    var streamStartedAt: Long = 0L

    /** persistir la bandeja de salida que envía la solicitud; se eliminará solo después de que el punto de control de primera generación sea exitoso. */
    var outboxId: String? = null
    var sessionTitle: String = ""
    var experiments: ExperimentsConfig = ExperimentsConfig()
    var assistant: AssistantEntity? = null

/**
     * v1.0.79 (F-1): Modelos cambiados manualmente dentro de una sesión id(Modelo exclusivo asistente de cobertura).
     * Escrito cuando el usuario cambia activamente de modelo en la sesión.,El análisis del modelo tiene prioridad sobre assistant.modelId;
     * Sólo estado de memoria,Cambiar de sesión/Después de reiniciar, regrese al modelo específico del asistente.
     */
    var sessionModelOverride: String? = null

    /** Anulación del proveedor a nivel de sesión, resuelta en pares con sessionModelOverride. */
    var sessionProviderOverride: String? = null

    /** Enrutamiento automático de tareas para la solicitud actual;Sólo efectivo para esta ronda.,No contamine la selección manual a nivel de sesión. */
    var taskRouteSelection: io.zer0.muse.data.SettingsRepository.TaskRouteSelection? = null

    /** Genere la configuración alternativa global capturada al inicio para evitar que se tome el modelo incorrecto de la instantánea de la interfaz de usuario de la nueva sesión después de cambiar de página. */
    var fallbackModelId: String? = null
    var fallbackProviderId: String? = null
    var requestedReasoningLevel: ReasoningLevel = ReasoningLevel.OFF
    var effectiveTemperature: Float = 0f
    var contextSize: Int = 20

    /** v2.x Importar calentamiento:Esta ronda es la primera ronda de la sesión de importación.,Monto total histórico + truncamiento del presupuesto de tokens(ver WarmupHistory). */
    var warmupActive: Boolean = false
    var rawHistory: List<UIMessage> = emptyList()
    var truncatedHistory: List<UIMessage> = emptyList()

    // Phase B: buildSystemPromptForStream
    var systemMessages: List<UIMessage> = emptyList()
    var prefixMessages: List<UIMessage> = emptyList()
    var pendingRagCitations: List<RagCitation> = emptyList()

    /**
     * P3-10: Compresión/Después del truncamiento, la carga útil de esta ronda aún excede el límite superior del contexto del modelo. — Rechazado por launchStream después de ser configurado,
     * Evite realizar solicitudes que estén garantizadas en 400. Válido solo en esta ronda.
     */
    var contextOverflowBlocked: Boolean = false

    // Phase D: applyTransformers
    var transformedMessages: List<UIMessage> = emptyList()

    // v1.x: acceso con triple gancho — Guarde el constructor de applyTransformers context,
    // Para su posterior reutilización mediante applyVisualTransform / applyOnGenerationFinish,Evite la construcción repetida
    var transformContext: TransformContext? = null

    // Phase E: resolveToolsAndModel
    var tools: List<ToolDefinition> = emptyList()
    var skillMap: Map<String, SkillEntity> = emptyMap()
    var routeSnapshot: io.zer0.muse.tools.ToolRouteSnapshot =
        io.zer0.muse.tools.RouteTable.snapshot(emptyList(), emptyList())

    // C-12: modelo maestro(Puede apoyar la visión)—— Para el juicio de ayuda visual y el uso de la ronda de recuperación final.
    var effectiveModel: Model? = null
    var effectiveProviderConfig: ProviderConfig? = null

    // C-12: modelo de herramienta(Configuración de usuario ligera toolModel,puede ser null)——
    //  solo"rueda de herramientas"(Ronda de continuación del resultado de la última ronda que contiene llamadas a herramientas)uso,El ciclo de recuperación final vuelve al modelo principal.,
    //  Para evitar que toolModel también degrade el modelo principal que admite elementos visuales a una ruta de texto sin formato.
    var toolModel: Model? = null
    var toolProviderConfig: ProviderConfig? = null
    var reasoningLevel: ReasoningLevel = ReasoningLevel.OFF
    var conversationHistory: MutableList<UIMessage> = mutableListOf()

    // Phase F: applyPiiGuard — También es necesario acceder a través de unmaskPii en el bloque catch.
    var piiMatches: List<PiiGuard.PiiMatch> = emptyList()

    // Phase H: runToolLoop resultado(También leído por finalizeResponse)
    var currentAssistantId: Uuid = assistantId
    var round: Int = 0
    var totalCharCount: Int = 0
    var totalToolCallCount: Int = 0
    var firstTokenTime: Long = 0L

    /**
     * v2.0: Número de actualizaciones enviadas a la interfaz de usuario en esta compilación de generación(contenido/pensar/fotos).
     * Solo se usa para registros de depuración y ajuste de tiempos de respuesta larga.,No involucrado en la lógica empresarial.
     */
    var uiFlushCount: Int = 0

    /**
     * A5: El uso real de token medido de este proveedor rotativo.
     * streamRound La rama UsageDelta escribe(Acumulación entre rondas,Cada ronda en un ciclo de herramienta de múltiples rondas puede causar),
     * finalizeResponse Al persistir, tome el último no- null(ronda final = ronda de respuesta final).
     * provider Cuando no se devuelve el uso, es null,UI Estimación local alternativa.
     */
    var usageTokens: io.zer0.ai.core.UsageTokens? = null

    // builder/reasoningBuilder:catch Para bloques(streamRound se utiliza durante la transmisión params.builder,
    // Aquí el constructor conserva un comportamiento coherente con la implementación original.)
    val builder: StringBuilder = StringBuilder()
    val reasoningBuilder: StringBuilder = StringBuilder()

    /**
     * B-24: El número de serie generado en esta generación. — launchStream Empezar desde ChatViewModel.streamGenerationSerial
     * captura.finalizeResponse/Antes de borrar la ruta de error isStreaming, verifique que aún esté"Última generación",
     * Evite que gen-1 borre el estado de transmisión de gen-2 durante ráfagas rápidas de fuego.
     */
    var generationSerial: Long = 0L

    fun unmaskPii(text: String): String = if (piiMatches.isEmpty()) text else PiiGuard.unmask(text, piiMatches)
}
