package io.zer0.muse.tools

/**
 * Nivel de riesgo de la herramienta.
 *
 * Utilizado para el sistema de permisos de sesión.:Bajo diferentes modos de permiso,Herramientas con diferentes niveles de riesgo desencadenan diferentes estrategias de aprobación.
 */
enum class ToolRiskLevel {
    /** solo lectura/Consultar herramientas de seguridad.(Como por ejemplo get_current_time / calculator / web_search). */
    SAFE,

    /** herramientas comunes:Puede leer datos del usuario o tener efectos secundarios locales.,Pero reversible(Como por ejemplo clipboard_write / open_app). */
    NORMAL,

    /** herramientas de alto riesgo:Puede resultar en daños irreversibles, entre dispositivos o fondos./Privacidad/impacto en la seguridad(Como por ejemplo send_sms / add_contact / workspace_delete / execute_javascript). */
    HIGH,
}
