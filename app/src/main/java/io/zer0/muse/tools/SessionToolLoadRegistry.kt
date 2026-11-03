package io.zer0.muse.tools

import java.util.concurrent.ConcurrentHashMap

/**
 * v2.x Etapa de adelgazamiento de herramientas.3:nivel de sesión"carga dinámica"Registro de nombres de herramientas.
 *
 * find_tools Después de presionar la herramienta, registre el nombre de la herramienta aquí(clave = sesión de anfitrión id);Exposición de herramientas para solicitudes posteriores.
 * El filtrado fusionará las herramientas cargadas en dynamicAllowed,Hágalos continuamente visibles para el modelo.
 *
 * Nota:cargar ≠ liberar — Sólo afecta"visibilidad",Aprobación/Permisos/El vínculo de riesgo permanece completamente sin cambios.
 * Estado de memoria pura,Muerte del proceso y pérdida natural.;no con SessionPermissionStore(Usuario"Esta sesión permite")Mézclalo.
 */
object SessionToolLoadRegistry {

    /** Límite de carga de sesión única,Para evitar que find_tools se recupere repetidamente, la superficie de la herramienta se restaurará a su capacidad total. */
    private const val MAX_LOADED_PER_SESSION = 64

    private val loaded = ConcurrentHashMap<String, Set<String>>()

    /** Registre el nombre de la herramienta cargada en una sesión.(idempotente;No se agregarán más nuevas incorporaciones una vez que se alcance el límite superior.). */
    fun markLoaded(sessionId: String, toolNames: Collection<String>) {
        if (sessionId.isBlank() || toolNames.isEmpty()) return
        loaded.compute(sessionId) { _, current ->
            val set = (current ?: emptySet()).toMutableSet()
            toolNames.forEach { name -> if (set.size < MAX_LOADED_PER_SESSION) set.add(name) }
            set
        }
    }

    /** Leer el nombre de la herramienta cargada en una sesión.(Conjunto vacío cuando no hay registro). */
    fun loadedFor(sessionId: String): Set<String> = loaded[sessionId].orEmpty()

    /** Borrar el historial de carga de una sesión(Llamado cuando se elimina la sesión.). */
    fun clear(sessionId: String) {
        loaded.remove(sessionId)
    }
}
