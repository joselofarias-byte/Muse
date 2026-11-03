package io.zer0.muse.tools

import io.zer0.common.Logger

/**
 * B2-04: subagente/Puente de aprobación de herramientas compartido por la sesión principal.
 *
 * [SubagentRunner] Ya no rechaza directamente las herramientas ASK_EVERY_TIME cuando las encuentra,pero a través de
 * [ToolApprovalRouter] Enrutar la solicitud al actual ChatViewModel,Reutilice la tarjeta de aprobación de la sesión principal.
 */
interface ToolApprovalBridge {
    suspend fun requestToolApproval(
        toolName: String,
        toolCallId: String,
        argsPreview: String,
        args: Map<String, Any?> = emptyMap(),
    ): ToolApprovalState
}

/**
 * enrutador singleton,Registrado como delegado por ChatViewModel en la inicialización.
 *
 * B-4: La implementación original es una única [@Volatile][kotlin.jvm.Volatile] delegate campo,multisesión/subagente
 * Al compartir el singleton global,El ChatViewModel registrado posteriormente sobrescribirá silenciosamente al anterior.,Como resultado, se bloqueó la tarjeta de aprobación del subagente.
 * Error enrutado a otra sesión(La solicitud de aprobación en sí está sincronizada. suspend,No es necesario atribuir ninguna devolución de llamada de red según la identificación de la solicitud,Así que vete
 * Escribir esquema de guardia).
 *
 * Reparación:delegate Agregar guardia al colocar —— Cuando ya existen diferentes instancias y hay una solicitud de aprobación en proceso(inFlight>0)
 * Denegar cobertura y registrar,Evite el robo entre sesiones durante el proceso de aprobación;El cambio de delegado durante el período de inactividad normalmente está activo.
 * cambio de sesión,ser liberado.inFlight con [request] Coincidencia uno a uno,Cancelar ruta(todo será arrojado
 * CancellationException)También puesto a cero por finalmente,El recuento no se filtrará.
 */
class ToolApprovalRouter {
    private val TAG = "ToolApprovalRouter"
    private val lock = Any()
    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var backingDelegate: ToolApprovalBridge? = null

    /**
     * Instancia de puente de aprobación actual.
     *
     * Verificación de colocación:Si hay otras instancias y hay solicitudes de aprobación en curso,Denegar anulación y registro,
     * Evite sobrescribir sesiones cruzadas y cortar tarjetas de aprobación en progreso.
     */
    var delegate: ToolApprovalBridge?
        get() = backingDelegate
        set(value) {
            synchronized(lock) {
                val current = backingDelegate
                if (value != null && current != null && current !== value && inFlight.get() > 0) {
                    Logger.w(TAG, "Aprobación en curso,Rechazar aprobación de reemplazo delegate(Cobertura entre sesiones) | inFlight=${inFlight.get()}")
                    return
                }
                backingDelegate = value
            }
        }

    suspend fun request(
        toolName: String,
        toolCallId: String,
        argsPreview: String,
        args: Map<String, Any?> = emptyMap(),
    ): ToolApprovalState {
        val bridge = backingDelegate ?: return ToolApprovalState.Denied("El canal de aprobación no está listo")
        inFlight.incrementAndGet()
        return try {
            bridge.requestToolApproval(toolName, toolCallId, argsPreview, args)
        } finally {
            inFlight.decrementAndGet()
        }
    }
}
