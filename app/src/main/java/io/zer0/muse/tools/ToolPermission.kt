package io.zer0.muse.tools

/**
 * v2.x: Requisitos de permisos del entorno de ejecución de herramientas.
 *
 * con [ToolRiskLevel](Riesgo de aprobación)ortogonal:Respuesta del nivel de riesgo"¿Quieres preguntarle al usuario?",
 * Esta enumeración responde"Si el entorno operativo cumple con las condiciones de ejecución."(Shizuku/Root acceso, sin barreras,Termux),
 * Se utiliza para la visualización de la lista de herramientas y la inspección previa a la ejecución.
 */
enum class ToolPermission {
    /** Servicios de accesibilidad(Canal de proveedores independientes). */
    ACCESSIBILITY,

    /** Canal de comando del dispositivo:Shizuku O Root, cualquiera de los dos está disponible(execTiered Ejecución jerárquica). */
    SHELL_TIER,

    /** Termux canal de comando externo(El usuario instala Termux y completa la autorización.). */
    TERMUX,
}

/** etiqueta corta china(Avisos de error y registros). */
fun ToolPermission.shortLabel(): String = when (this) {
    ToolPermission.ACCESSIBILITY -> "Accesibilidad"
    ToolPermission.SHELL_TIER -> "Shizuku/Root"
    ToolPermission.TERMUX -> "Termux"
}

/** etiqueta corta en ingles(find_tools Espere la salida legible del modelo). */
fun ToolPermission.enLabel(): String = when (this) {
    ToolPermission.ACCESSIBILITY -> "Accessibility"
    ToolPermission.SHELL_TIER -> "Shizuku/Root"
    ToolPermission.TERMUX -> "Termux"
}

/**
 * v2.x: Instantánea del estado de autorización del entorno operativo.
 *
 * Inyectado por bota de arranque [ToolRegistry.permissionStatusProvider],fuente de datos:
 *  - [accessibility]/[shellTier]:AutomationManager.permissionState
 *  - [termux]:TermuxChannel.availability
 */
data class ToolPermissionStatus(
    val accessibility: Boolean,
    val shellTier: Boolean,
    val termux: Boolean,
) {
    /** El conjunto de requisitos está vacío → No se requiere autorización;De lo contrario"ya sea listo"considerado ejecutable(Como por ejemplo screen_* Accesible o equipo accesible). */
    fun satisfiedAny(required: Set<ToolPermission>): Boolean {
        if (required.isEmpty()) return true
        return required.any { permission ->
            when (permission) {
                ToolPermission.ACCESSIBILITY -> accessibility
                ToolPermission.SHELL_TIER -> shellTier
                ToolPermission.TERMUX -> termux
            }
        }
    }

    /** Descripción china de los requisitos establecidos.(Como por ejemplo"Accesible o Shizuku/Root"). */
    fun describe(required: Set<ToolPermission>): String = required.joinToString(" o ") { it.shortLabel() }

    /** descripción en inglés(find_tools salida). */
    fun describeEn(required: Set<ToolPermission>): String = required.joinToString(" or ") { it.enLabel() }
}
