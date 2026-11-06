package io.zer0.muse.ui.chat

import io.zer0.muse.tools.SessionPermissionMode

/**
 * AUD-20261011-A2A-ALLOWALL: Determinación del modo de sesión para la aprobación de herramientas.(función pura,Se puede probar individualmente).
 *
 * calibre de seguridad crítico:[allowAllToolsThisRun] si**nivel de proceso** UI bit de estado
 * (El usuario verifica cualquier tarjeta de aprobación."Aprobar todo durante este período de apertura"trasero true),sólo puede representar"El usuario está actualmente mirando
 * Y dispuesto a dejar ir este tiempo". es solo en [displayedSessionId] Conversación con solicitud. [sessionId] consistente
 * (es decir, el usuario está viendo la sesión.)Sólo entonces se actualiza a CONFIANZA.
 *
 * De lo contrario(A2A Solicitud remota para cambiar la sesión / Nueva sesión A2A / Continuar generando en segundo plano)Regresar a la propia sesión.
 * modo de permiso —— Con la opción PREGUNTAR predeterminada, las herramientas de ALTO riesgo seguirán recibiendo una tarjeta de aprobación o serán rechazadas con un tiempo de espera de 30 segundos.,
 * No se ejecutará en silencio cuando no haya nadie frente a la pantalla. device_shell / app_clear_data / ui_agent.
 *
 * @param displayedSessionId La sesión que el usuario está viendo actualmente.(Cuando no hay enfoque, es null)
 * @param focusedMode Modo de permiso de sesión para sesión enfocada
 * @param persistedModeProvider Obtenga el modo de persistencia de la propia sesión no enfocada
 */
internal suspend fun resolveApprovalMode(
    allowAllToolsThisRun: Boolean,
    displayedSessionId: String?,
    sessionId: String,
    focusedMode: SessionPermissionMode,
    persistedModeProvider: suspend () -> SessionPermissionMode,
): SessionPermissionMode {
    val focused = displayedSessionId != null && displayedSessionId == sessionId
    return when {
        allowAllToolsThisRun && focused -> SessionPermissionMode.TRUSTED
        focused -> focusedMode
        else -> persistedModeProvider()
    }
}
