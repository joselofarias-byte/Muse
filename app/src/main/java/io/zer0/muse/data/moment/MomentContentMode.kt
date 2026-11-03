package io.zer0.muse.data.moment

/**
 * Modo de contenido de Momentos (los usuarios pueden cambiar en la página de configuración).
 *
 * Antecedentes: la antigua implementación del aviso fue escrita hasta la muerte"Basado únicamente en materiales reales, no inventes experiencias que los usuarios no hayan dicho.".
 * Esta regla encierra al personaje en un contexto de juego de roles."Solo puedo repetir usuario"——Los personajes no tienen vida propia;
 * El círculo de amigos ha degenerado en"Resumen de memoria de usuario", perdiendo inmersión. Pero, por otro lado, dejarse ir por completo se convertirá en una invención de la nada.
 * También destruye la confianza. Por lo tanto, se divide en dos niveles y los usuarios pueden elegir según su propia jugabilidad.
 *
 * - [REAL] Autenticidad: publique únicamente cosas relacionadas con el usuario, con moderación, como si realmente estuviera registrando su vida.
 * - [CHARACTER] Sistema de personajes: los personajes tienen su propia vida, trabajo y encuentros, y los usuarios son espectadores;
 *   Aún no se permite fabricar piezas que involucren a usuarios
 *
 * Predeterminado [REAL]: Más conservador, el contenido que los usuarios no hayan experimentado no aparecerá y los nuevos usuarios no se asustarán.
 */
enum class MomentContentMode {
    REAL,
    CHARACTER,
    ;

    companion object {
        /** Restaurar desde una cadena persistente; valor desconocido se degrada a [REAL]. */
        fun fromStorage(value: String?): MomentContentMode = when (value?.trim()?.lowercase()) {
            "character" -> CHARACTER
            else -> REAL
        }
    }

    /** Cadena persistente. */
    fun toStorage(): String = when (this) {
        REAL -> "real"
        CHARACTER -> "character"
    }
}
