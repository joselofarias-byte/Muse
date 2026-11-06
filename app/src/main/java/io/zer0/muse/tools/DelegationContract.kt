package io.zer0.muse.tools

import io.zer0.ai.core.UIMessage
import kotlinx.serialization.Serializable

/**
 * v1.200: Contextos estructurados de delegación y contratos de retorno.
 *
 * Unificación de la colaboración entre múltiples agentes"asistente principal → sub-asistente/equipo"formato de entrada y salida,
 * Haga que la delegación de tareas pueda rastrearse, agregarse y aislarse.
 */
object DelegationContract {

    /**
     * Solicitud de delegación.
     *
     * @param requestId Solicitar único id,Generado por la persona que llama,para rastreo de enlaces
     * @param task Descripción de la tarea(lenguaje natural)
     * @param targetType tipo de objetivo:assistant / team
     * @param targetId ID del asistente de destino o teamId
     * @param parentSessionId sesión de padres id,Se utiliza para el aislamiento de contexto y el reabastecimiento de resultados.
     * @param contextMessages Lista de mensajes de contexto reducida para llevar(Historial de chat incompleto)
     * @param attachments Accesorios/Lista de identificación del producto,Como artefactoIds/ruta del archivo
     * @param timeoutSec Tiempo de espera de ejecución(segundos)
     * @param responseFormat Formato de devolución esperado:text / json / markdown / code
     * @param capabilityHints Etiquetas de capacidad que se esperan de los subasistentes(Se utiliza para verificación o enrutamiento automático.)
     * @param requireApproval Si se requiere la aprobación explícita del usuario(misión de alto riesgo)
     * @param metadata metadatos extendidos
     * @param threadId hilo de agente secundario ID(Pasado al continuar conectándose con el mismo subagente,La primera delegación no se pasa y es generada por el sistema.)
     * @param access archivo de permiso:read/write,Hereda la sesión principal cuando se omite
     * @param nonBlocking Ya sea en modo sin bloqueo(true=Regresar inmediatamente taskId,Realimentar después de completar la subtarea;false=bloqueo de espera)
     * @param label etiqueta(para exhibición,no como una identidad)
     * @param callerAssistantId El agente principal que inició la delegación. assistantId,Se utiliza para pasar después de completar la delegación.
     *        AgentDmRepository Rellene el resultado en uno sub-agent → main-agent mensaje privado.
     *        para null(Predeterminado)No enviado cuando DM,Mantenga la compatibilidad con personas que llaman anteriormente.
     */
    @Serializable
    data class DelegationRequest(
        val requestId: String,
        val task: String,
        val targetType: TargetType,
        val targetId: String,
        val parentSessionId: String? = null,
        val contextMessages: List<UIMessage> = emptyList(),
        val attachments: List<String> = emptyList(),
        val timeoutSec: Int = DEFAULT_TIMEOUT_SEC,
        val responseFormat: ResponseFormat = ResponseFormat.TEXT,
        val capabilityHints: List<String> = emptyList(),
        val requireApproval: Boolean = false,
        /** v1.201: Una lista de puntos de pausa declarados explícitamente,Valor opcional: "before_start" / "after_each_member" / "on_intermediate".
         *  Cuando está vacío, PausePolicy determina si se debe pausar;La prioridad es mayor que PausePolicy cuando no está vacía. */
        val pausePoints: List<String> = emptyList(),
        val metadata: RequestMetadata = RequestMetadata(),
        /** hilo de agente secundario ID(Pasado al continuar conectándose con el mismo subagente,La primera delegación no se pasa y es generada por el sistema.). */
        val threadId: String? = null,
        /** archivo de permiso:read/write,Cuando se omite, se hereda la sesión principal. */
        val access: String? = null,
        /** Ya sea en modo sin bloqueo(true=Regresar inmediatamente taskId,Realimentar después de completar la subtarea;false=bloqueo de espera). */
        val nonBlocking: Boolean = false,
        /** etiqueta(para exhibición,no como una identidad). */
        val label: String? = null,
        /** v1.202: agente principal assistantId,Se utiliza para rellenar el mensaje privado del resultado a través de AgentDmRepository una vez completada la delegación.
         *  El valor predeterminado es nulo para compatibilidad con versiones anteriores(no enviar DM). */
        val callerAssistantId: String? = null,
        /** v1.0.53 Phase 2: Flujo de trabajo journal run id;TEAM Reutilizar cuando se pasa el objetivo,De lo contrario generado por el ejecutor.
         *  La persona que llama puede recuperarlo de DelegationResult.metadata.journalRunId,Se utiliza para reanudar el reintento después de un error. */
        val journalRunId: String? = null,
        /** v1.0.53 Phase 2: Ya sea para restaurar desde el diario(Sólo funciona para objetivos de EQUIPO;true El nodo que llega al caché se devolverá en segundos.). */
        val resumeFromJournal: Boolean = false,
        /** v1.0.53 Phase 3: token límite presupuestario(prompt + completion total);null=Sin restricciones.
         *  Sólo es necesario establecer la delegación más externa;Subdelegación recursiva(subRequest)Debe establecerse en nulo para heredar del padre budget(Pasado a través de ThreadLocal). */
        val tokenBudget: Int? = null,
    ) {
        enum class TargetType { ASSISTANT, TEAM }
        enum class ResponseFormat { TEXT, JSON, MARKDOWN, CODE }

        @Serializable
        data class RequestMetadata(
            val sourceToolCallId: String? = null,
            val createdAt: Long = System.currentTimeMillis(),
            val correlationId: String? = null,
        )

        companion object {
            const val DEFAULT_TIMEOUT_SEC = 60
        }
    }

    /**
     * Delegar resultados.
     *
     * @param requestId Solicitud correspondiente id
     * @param success ¿Tiene éxito?
     * @param resultText texto del resultado(Resultado principal del éxito)
     * @param error mensaje de error(success=false el tiempo no está vacío)
     * @param metadata metadatos de ejecución(Consume mucho tiempo/token/Modelo etc)
     * @param subResults equipo/Cuando se ejecuta el flujo de trabajo,Subresultados de cada miembro(lista plana,para cálculos agregados)
     * @param artifacts Productos generados o referenciados por esta delegación id
     * @param threadId hilo de agente secundario ID(Generado cuando se delega por primera vez,Se puede utilizar para extensiones posteriores.)
     * @param taskId Tareas asincrónicas ID(Regresar en modo sin bloqueo,Se utiliza para consultar el estado./Cancelar)
     * @param subResultTree árbol de subresultados(Flujo de trabajo del equipo,Resultados para cada nodo hijo,Se utiliza para la visualización del árbol de la interfaz de usuario)
     */
    @Serializable
    data class DelegationResult(
        val requestId: String,
        val success: Boolean,
        val resultText: String = "",
        val error: String? = null,
        val metadata: ResultMetadata = ResultMetadata(),
        val subResults: List<DelegationResult> = emptyList(),
        val artifacts: List<String> = emptyList(),
        /** hilo de agente secundario ID(Generado cuando se delega por primera vez,Se puede utilizar para extensiones posteriores.). */
        val threadId: String? = null,
        /** Tareas asincrónicas ID(Regresar en modo sin bloqueo,Se utiliza para consultar el estado./Cancelar). */
        val taskId: String? = null,
        /** árbol de subresultados(Flujo de trabajo del equipo,Resultados para cada nodo hijo,Se utiliza para la visualización del árbol de la interfaz de usuario). */
        val subResultTree: List<SubResultNode>? = null,
    ) {
        @Serializable
        data class ResultMetadata(
            val startedAt: Long = 0L,
            val finishedAt: Long = 0L,
            val durationMs: Long = 0L,
            val tokenCount: Int? = null,
            val modelId: String? = null,
            val assistantId: String? = null,
            val assistantName: String? = null,
            /** v1.0.53 Phase 2: Flujo de trabajo journal runId(Para que la persona que llama continúe y vuelva a intentarlo.). */
            val journalRunId: String? = null,
        )

        /** Si hay un resultado de texto legible. */
        val hasResultText: Boolean get() = resultText.isNotBlank()

        /** Resume los mensajes de error para sí mismo y para todos los subresultados. */
        fun collectErrors(): List<String> = buildList {
            if (!success && !error.isNullOrBlank()) add(error)
            subResults.forEach { addAll(it.collectErrors()) }
        }

        /** Resumir el texto de todos los subresultados.(Utilizado para la agregación de resultados del equipo.). */
        fun collectResultTexts(): List<String> = buildList {
            if (hasResultText) add(resultText)
            subResults.forEach { addAll(it.collectResultTexts()) }
        }
    }

    /**
     * nodo del árbol de subresultados(Se utiliza para la visualización del árbol de la interfaz de usuario).
     *
     * con [DelegationResult.subResults] la diferencia:
     *  - subResults es una lista plana de DelegationResult,para cálculos agregados
     *  - SubResultNode es una estructura de árbol recursiva,Se utiliza para que la interfaz de usuario muestre el estado de ejecución de los miembros del flujo de trabajo del equipo.
     */
    @Serializable
    data class SubResultNode(
        val requestId: String,
        val assistantId: String,
        val assistantName: String,
        val task: String,
        val status: String, // running/success/failed
        val resultText: String? = null,
        val error: String? = null,
        val durationMs: Long = 0,
        val children: List<SubResultNode> = emptyList(), // nodo hijo recursivo
    )

    /**
     * Nodo de flujo de trabajo del equipo.
     *
     * Se utiliza para describir la secuencia de ejecución y las dependencias de múltiples agentes dentro del equipo.,
     * es la serie posterior/paralelo/La estructura básica de la orquestación de ramas condicionales.
     */
    @Serializable
    data class TeamWorkflowNode(
        val id: String,
        val assistantId: String,
        val name: String = "",
        val taskTemplate: String = "",
        val dependsOn: List<String> = emptyList(),
        val mode: Mode = Mode.SEQUENTIAL,
    ) {
        enum class Mode { SEQUENTIAL, PARALLEL, CONDITIONAL }
    }

    /**
     * Definición del flujo de trabajo del equipo.
     */
    @Serializable
    data class TeamWorkflow(
        val nodes: List<TeamWorkflowNode> = emptyList(),
        val aggregationStrategy: AggregationStrategy = AggregationStrategy.MERGE,
    ) {
        enum class AggregationStrategy { MERGE, VOTE, EXPERT_REVIEW, FIRST_SUCCESS, LLM_REVIEW }
    }

    /** poner [DelegationResult] Serializado a una cadena JSON. */
    fun resultToJson(result: DelegationResult): String {
        return io.zer0.common.AppJson.encodeToString(DelegationResult.serializer(), result)
    }

    /** Analizar desde una cadena JSON [DelegationResult]. */
    fun resultFromJson(json: String): DelegationResult? {
        return runCatching {
            io.zer0.common.AppJson.decodeFromString(DelegationResult.serializer(), json)
        }.getOrNull()
    }

    /** poner [DelegationRequest] Serializado a una cadena JSON. */
    fun requestToJson(request: DelegationRequest): String {
        return io.zer0.common.AppJson.encodeToString(DelegationRequest.serializer(), request)
    }

    /** Analizar desde una cadena JSON [DelegationRequest]. */
    fun requestFromJson(json: String): DelegationRequest? {
        return runCatching {
            io.zer0.common.AppJson.decodeFromString(DelegationRequest.serializer(), json)
        }.getOrNull()
    }
}
