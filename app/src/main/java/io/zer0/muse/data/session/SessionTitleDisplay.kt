package io.zer0.muse.data.session

/**
 * v2.5.2: El título de la conversación muestra el resultado final. —— La importación del historial/nombramiento automático puede guardar el título como puntos suspensivos puros ("…","...",
 * ". .","·" etc.) o en blanco, ocupando directamente el espacio y mostrándolo como dato incorrecto.
 *
 * El calibre unificado para todas las aplicaciones (lista de páginas de inicio CHAT-16, lista de sesiones de copia de seguridad y exportación, etc.) se puede encontrar aquí.
 * Evite el rendimiento inconsistente del mismo lote de datos incorrectos en diferentes páginas (la página de inicio muestra "nueva sesión" y la lista de exportación muestra "…").
 *
 * Sólo afecta la visualización; el valor original en la base de datos permanece sin cambios (para evitar modificar accidentalmente los datos del usuario).
 *
 * @param title título original
 * @param fallback Nombre para mostrar encubierto (normalmente "Nueva sesión")
 */
fun displaySessionTitle(title: String?, fallback: String): String {
    val trimmed = title?.trim().orEmpty()
    if (trimmed.isEmpty()) return fallback
    return if (isMeaninglessSessionTitle(trimmed)) fallback else trimmed
}

/**
 * v2.5.2: Si el título es un marcador de posición sin sentido (en blanco o compuesto enteramente de ././·/… símbolos).
 *
 * Para la capa de visualización ([displaySessionTitle]) comparte la misma decisión que la capa de escritura (interceptación automática de nombres).
 */
fun isMeaninglessSessionTitle(title: String?): Boolean {
    val trimmed = title?.trim().orEmpty()
    if (trimmed.isEmpty()) return true
    return trimmed.all {
        it == '.' || it == '.' || it == '·' || it == '…' || it.isWhitespace()
    }
}
