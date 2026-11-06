package io.zer0.muse.automation.core

/**
 * UI Niveles de permisos automatizados.
 *
 * Tres niveles de gradientes corresponden a diferentes rangos de capacidad:
 * - [ACCESSIBILITY]: Servicios de accesibilidad,leer pantalla+Inyección de gestos,No es necesario conectarse a la computadora
 * - [SHELL]: Shizuku/adb shell,Comandos a nivel del sistema,Instalación silenciosa,settings,input espera
 * - [ROOT]: su Permisos,Control total del sistema
 *
 * Los ejecutores se registran de forma incremental y jerárquica.,Seleccionar automáticamente el nivel más bajo que satisfaga las necesidades a la hora de distribuir acciones(estrategia de degradación).
 */
enum class PermissionLevel {
    /** Servicios de Accesibilidad. */
    ACCESSIBILITY,

    /** Shizuku/adb shell. */
    SHELL,

    /** Root (su). */
    ROOT,

    /** Sin permisos de automatización. */
    NONE,
    ;

    /** ¿Este nivel contiene al menos [other] capacidad. */
    fun covers(other: PermissionLevel): Boolean = rank >= other.rank

    /** Los niveles de capacidad explícitos evitan que el orden de declaración de enumeración cambie la semántica.NONE Sin habilidad. */
    private val rank: Int
        get() = when (this) {
            NONE -> 0
            ACCESSIBILITY -> 1
            SHELL -> 2
            ROOT -> 3
        }

    companion object {
        /** Analizado a partir de combinaciones de indicadores disponibles. */
        fun fromFlags(accessibility: Boolean, shell: Boolean, root: Boolean): Set<PermissionLevel> = buildSet {
            if (accessibility) add(ACCESSIBILITY)
            if (shell) add(SHELL)
            if (root) add(ROOT)
        }
    }
}
