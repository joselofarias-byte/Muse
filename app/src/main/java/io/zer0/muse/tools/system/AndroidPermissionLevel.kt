package io.zer0.muse.tools.system

/**
 * P3-3: Android Nivel de permiso — Modelo de permisos de tres canales.
 *
 * Niveles crecientes,El nivel alto incluye capacidades de bajo nivel.:
 *  - [NONE]: Ningún canal está autorizado,No se puede ejecutar la automatización de la interfaz de usuario o los comandos de shell
 *  - [ACCESSIBILITY]: Los servicios de accesibilidad están habilitados.,Puede realizar operaciones de UI(Haga clic/Deslizar/entrada/captura de pantalla/Leer árbol de interfaz de usuario)
 *  - [SHIZUKU]: Shizuku Autorizado(API 23+,Ejecutar comandos con privilegios de shell,No es necesario root)
 *  - [ROOT]: El dispositivo tiene root,Puede ejecutar cualquier comando(máxima autoridad)
 *
 * reglas de enrutamiento([UIToolsSkill] / automation ShellExecutor):
 *  - UI Operación:necesidad [ACCESSIBILITY] y arriba
 *  - Shell comando:Prioridad [SHIZUKU](más seguro,No es necesario root),bajar de categoría a [ROOT];[ACCESSIBILITY] No se puede ejecutar shell
 */
enum class AndroidPermissionLevel {
    NONE,
    ACCESSIBILITY,
    SHIZUKU,
    ROOT, ;

    /** ¿Se ha alcanzado el nivel actual? [required] nivel. */
    fun atLeast(required: AndroidPermissionLevel): Boolean = this.ordinal >= required.ordinal

    companion object {
        /** Elija el nivel más alto de disponibilidad de múltiples canales. */
        fun highestOf(vararg available: Pair<AndroidPermissionLevel, Boolean>): AndroidPermissionLevel {
            var best = NONE
            for ((level, ok) in available) {
                if (ok && level.ordinal > best.ordinal) best = level
            }
            return best
        }
    }
}
