package io.zer0.muse.tools

/**
 * Modo de permisos de herramientas a nivel de sesión.
 *
 * Anular la política de aprobación de una sola herramienta,Permitir a los usuarios cambiar rápidamente dentro de una sola sesión"confianza/preguntar/estricto"Tres posturas.
 */
enum class SessionPermissionMode {
    /**
     * Delegación completa de autoridad:Excepto por la desactivación explícita del usuario y el rechazo total de los parámetros de la herramienta.,No todas las herramientas se aprueban caso por caso.
     * Los permisos de ejecución propios de la herramienta, la zona de pruebas y la autorización del dispositivo siguen vigentes.
     */
    TRUSTED,

    /**
     * Modo de consulta(Predeterminado):Las herramientas de seguridad automatizan la ejecución,Tanto las herramientas normales como las de alto riesgo requieren la aprobación del usuario.
     */
    ASK,

    /**
     * modo estricto:Cualquier herramienta que no sea de solo lectura requiere aprobación;Networking en herramientas de seguridad/Las solicitudes externas también se incluyen en la revisión y aprobación.
     */
    STRICT,
}
