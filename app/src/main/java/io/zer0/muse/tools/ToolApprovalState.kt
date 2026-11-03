package io.zer0.muse.tools

/**
 * Máquina de estado de aprobación de herramienta.
 *
 * Determine si la llamada a la herramienta requiere la aprobación del usuario antes de su ejecución y transmita los resultados de la aprobación.
 *
 * Flujo de estado:
 *  - AUTO: Liberado automáticamente según la política (siempre permitido)
 *  - PENDING: A la espera de la decisión del usuario, el ciclo de generación se interrumpe aquí.
 *  - APPROVED: el usuario aprueba esta llamada y puede realizar anulaciones de parámetros.
 *  - DENIED: El usuario rechaza esta llamada (se pueden adjuntar los motivos)
 *  - ANSWERED: El usuario proporciona una respuesta personalizada y no ejecuta la herramienta.
 *
 * v1.x: [Approved] llevar [Approved.argOverrides],Permitir a los usuarios anular parámetros individuales en tarjetas de aprobación
 * (Escenario típico: generate_image Herramienta reference_image,LLM No se pueden proporcionar imágenes locales directamente,
 * Seleccionado por el usuario del álbum en la interfaz de usuario de aprobación,Después de la selección, el URI de datos se ejecuta a través de la herramienta de inyección argOverrides.).
 */
sealed class ToolApprovalState {
    /** Aprobado automáticamente y ejecutado de inmediato. */
    data object Auto : ToolApprovalState()

    /** Esperando la aprobación del usuario. El ciclo de construcción debería interrumpirse aquí. */
    data object Pending : ToolApprovalState()

    /**
     * El usuario aprobó esta llamada a la herramienta.
     *
     * @param argOverrides Parámetros de herramientas anulados por los usuarios durante la fase de aprobación(clave → valor);Vacío significa que no hay cobertura,Utilice parámetros sin procesar de LLM.
     * El valor anulado será [io.zer0.muse.tools.ToolOrchestrator] fusionarse en [io.zer0.ai.core.ToolCall.arguments]
     * Herramienta de ejecución en JSON.
     */
    data class Approved(val argOverrides: Map<String, String> = emptyMap()) : ToolApprovalState()

    /** El usuario rechazó esta llamada a la herramienta. */
    data class Denied(val reason: String = "") : ToolApprovalState()

    /** El usuario proporcionó una respuesta de texto personalizada en lugar de ejecutar la herramienta. */
    data class Answered(val answer: String) : ToolApprovalState()

    val isTerminal: Boolean
        get() = this is Approved || this is Denied || this is Answered

    val isExecutable: Boolean
        get() = this is Auto || this is Approved
}

/**
 * Las políticas de aprobación para herramientas individuales se conservan en DataStore.
 */
enum class ToolApprovalPolicy {
    /** Aprobar siempre automáticamente (predeterminado para herramientas de seguridad como get_time). */
    ALWAYS_ALLOW,

    /** Negar siempre (equivale a desactivar la herramienta). */
    ALWAYS_DENY,

    /** Pregúntele al usuario cada vez. */
    ASK_EVERY_TIME,
}
