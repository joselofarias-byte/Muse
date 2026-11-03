package io.zer0.muse.ui.chat

import io.zer0.common.Logger
import io.zer0.muse.tools.SessionPermissionMode
import io.zer0.muse.tools.SessionPermissionStore
import io.zer0.muse.tools.ToolApprovalPolicy
import io.zer0.muse.tools.ToolApprovalState
import io.zer0.muse.tools.ToolConfigStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.x: Aprobación de herramientas extraída de ChatViewModel/Controlador de permisos.
 *
 * Responsabilidades:Superposición de imagen de referencia para que se apruebe la llamada de herramienta,"siempre permitido/Todos están liberados para esta carrera."Estado comprobado,
 * Modos de permisos y aprobaciones de herramientas a nivel de sesión/rechazar(compartido [approvalResults] Pendiente completo deferred).
 */
class ChatToolController(
    private val accessor: ChatStateAccessor,
    private val sessionPermissionStore: SessionPermissionStore,
    /** Tabla de resultados de espera compartida con requestToolApproval del lado de compilación(clave = toolCallId). */
    private val approvalResults: ConcurrentHashMap<String, CompletableDeferred<ToolApprovalState>>,
    private val toolConfigStore: ToolConfigStore?,
) {

    /** Establezca el valor de cobertura de la imagen de referencia para que se aprueben las llamadas de herramientas(data URI;null Borrar imágenes seleccionadas). */
    fun setToolApprovalReferenceImage(toolCallId: String, dataUri: String?) {
        accessor.update { current ->
            current.copy(
                pendingToolApprovals = current.pendingToolApprovals.map { approval ->
                    if (approval.toolCallId == toolCallId) {
                        approval.copy(referenceImageOverride = dataUri)
                    } else {
                        approval
                    }
                },
            )
        }
    }

    /** Actualizar la herramienta solicitada para aprobación"siempre permitido"Consultar estado. */
    fun setToolApprovalAlwaysAllow(toolCallId: String, alwaysAllow: Boolean) {
        accessor.update { current ->
            current.copy(
                pendingToolApprovals = current.pendingToolApprovals.map { approval ->
                    if (approval.toolCallId == toolCallId) {
                        approval.copy(alwaysAllow = alwaysAllow)
                    } else {
                        approval
                    }
                },
            )
        }
    }

    /** Actualizar la herramienta solicitada para aprobación"Aprobar todo durante este período de apertura"Estado comprobado,También escriba appRunAllowAllTools. */
    fun setToolApprovalAppRunAllowAll(toolCallId: String, allowAll: Boolean) {
        accessor.update { current ->
            current.copy(
                appRunAllowAllTools = allowAll,
                pendingToolApprovals = current.pendingToolApprovals.map { approval ->
                    if (approval.toolCallId == toolCallId) {
                        approval.copy(appRunAllowAll = allowAll)
                    } else {
                        approval
                    }
                },
            )
        }
    }

    /** P3: Establecer el modo de permiso de la herramienta para la sesión actual(Estado de la memoria + persistencia). */
    fun setSessionPermissionMode(mode: SessionPermissionMode) {
        val sessionId = if (accessor.snapshot.isAgentMode) {
            accessor.snapshot.agentSessionId
        } else {
            accessor.snapshot.currentSessionId
        } ?: return
        accessor.update { it.copy(sessionPermissionMode = mode) }
        accessor.coroutineScope.launch {
            sessionPermissionStore.setMode(sessionId, mode)
        }
    }

    /** Aprobar llamadas de herramientas:Eliminar elementos pendientes de aprobación,Inyecte la imagen de referencia seleccionada por el usuario en la tarjeta como argOverrides,y terminar de esperar los resultados. */
    fun approveToolCall(toolCallId: String) {
        val pending = accessor.snapshot.pendingToolApprovals.firstOrNull { it.toolCallId == toolCallId } ?: return
        accessor.update {
            it.copy(pendingToolApprovals = it.pendingToolApprovals.filter { p -> p.toolCallId != toolCallId })
        }
        val argOverrides = if (!pending.referenceImageOverride.isNullOrBlank() &&
            pending.toolName in REFERENCE_IMAGE_TOOL_NAMES
        ) {
            mapOf("reference_image" to pending.referenceImageOverride)
        } else {
            emptyMap()
        }
        approvalResults[toolCallId]?.complete(ToolApprovalState.Approved(argOverrides))
    }

    /**
     * F-37: La herramienta de aprobación llama e inyecta cualquier asignación de anulación de parámetros proporcionada por la persona que llama.
     *
     * con [approveToolCall] la diferencia:Los valores de cobertura no se limitan a imágenes de referencia.,Puede transportar directamente la transmisión transparente de la puerta de enlace del Host
     * El usuario completa los parámetros(web Aprobación answered),y a través de [io.zer0.muse.tools.ToolApprovalState.Approved.argOverrides]
     * Fusionado en argumentos de herramienta por ToolOrchestrator antes de la ejecución.
     */
    fun approveToolCallWithOverrides(toolCallId: String, argOverrides: Map<String, String>) {
        val pending = accessor.snapshot.pendingToolApprovals.firstOrNull { it.toolCallId == toolCallId } ?: return
        accessor.update {
            it.copy(pendingToolApprovals = it.pendingToolApprovals.filter { p -> p.toolCallId != toolCallId })
        }
        approvalResults[toolCallId]?.complete(ToolApprovalState.Approved(argOverrides))
    }

    /** Denegar llamada a herramienta:Eliminar elementos pendientes de aprobación,y terminar de esperar los resultados. */
    fun denyToolCall(toolCallId: String, reason: String) {
        val pending = accessor.snapshot.pendingToolApprovals.firstOrNull { it.toolCallId == toolCallId } ?: return
        accessor.update {
            it.copy(pendingToolApprovals = it.pendingToolApprovals.filter { p -> p.toolCallId != toolCallId })
        }
        approvalResults[toolCallId]?.complete(ToolApprovalState.Denied(reason))
    }

    /** Estrategia persistente de una sola herramienta(por tarjeta de aprobación"siempre permitido"disparador de botón). Desvinculado de esta aprobación. */
    fun persistToolPolicy(toolCallId: String, policy: ToolApprovalPolicy) {
        val pending = accessor.snapshot.pendingToolApprovals.firstOrNull { it.toolCallId == toolCallId } ?: return
        val store = toolConfigStore ?: return
        accessor.coroutineScope.launch {
            runCatching { store.setPolicy(pending.toolName, policy) }
                .onFailure { Logger.w("ChatVM", "persistToolPolicy(${pending.toolName}) falló: ${it.message}") }
        }
    }

    /** Agregue la herramienta al conjunto temporal permitido para la sesión actual(No vuelvas a preguntar en esta conversación.,Sólo la memoria no es persistente.). */
    fun allowToolForSession(toolCallId: String) {
        val pending = accessor.snapshot.pendingToolApprovals.firstOrNull { it.toolCallId == toolCallId } ?: return
        val sessionId = if (accessor.snapshot.isAgentMode) {
            accessor.snapshot.agentSessionId
        } else {
            accessor.snapshot.currentSessionId
        } ?: return
        sessionPermissionStore.allowToolForSession(sessionId, pending.toolName)
    }

    companion object {
        /** Admite la selección de la colección de nombres de herramientas de dibujos de referencia locales en la tarjeta de aprobación. */
        private val REFERENCE_IMAGE_TOOL_NAMES: Set<String> = setOf("generate_image")
    }
}
