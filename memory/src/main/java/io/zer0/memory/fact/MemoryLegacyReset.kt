package io.zer0.memory.fact

import android.content.Context

/**
 * R-DB-03: Registre hechos anteriores cuando la base de datos se archive y reconstruya,Proporciona a la capa de aplicaciones sugerencias visibles en la página de memoria.
 */
object MemoryLegacyReset {
    private const val PREFS_NAME = "muse_memory_legacy_reset"

    /** Prefijo clave para restaurar el estado interno, como el número de elementos —— No participe en la determinación de eventos archivados y no será [consume] Claro. */
    private const val INTERNAL_PREFIX = "__"

    private const val KEY_RECOVERED_COUNT = "__recovered_count"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Marca la base de datos especificada para la reconstrucción del archivo. */
    fun mark(context: Context, dbName: String) {
        prefs(context).edit().putBoolean(dbName, true).apply()
    }

    /** Si hay eventos de archivo pendientes(No consumir marca). */
    fun hasPending(context: Context): Boolean = archiveFlags(context).isNotEmpty()

    /** Recordatorio de consumo una vez;Devuelve si hay reconstrucción de archivo pendiente,y borre la marca de archivo. */
    fun consume(context: Context): Boolean {
        val p = prefs(context)
        val keys = archiveFlags(context)
        if (keys.isNotEmpty()) {
            p.edit().apply { keys.forEach { remove(it) } }.apply()
        }
        return keys.isNotEmpty()
    }

    /** v2.2.1: Registre el número de hechos recientemente restaurados del archivo.(recordatorio de la página de memoria;consumo único). */
    fun noteRecovered(context: Context, count: Int) {
        prefs(context).edit().putInt(KEY_RECOVERED_COUNT, count).apply()
    }

    /** v2.2.1: Leer y borrar la cantidad de elementos de recuperación;Devuelve nulo si nunca se registra. */
    fun consumeRecovered(context: Context): Int? {
        val p = prefs(context)
        if (!p.contains(KEY_RECOVERED_COUNT)) return null
        val count = p.getInt(KEY_RECOVERED_COUNT, -1)
        p.edit().remove(KEY_RECOVERED_COUNT).apply()
        return count.takeIf { it >= 0 }
    }

    /** v2.2.1: Si la biblioteca ha intentado recuperar un archivo erróneo(Pruebe solo una vez por biblioteca,Copia de seguridad de escaneo anti-repetitivo). */
    fun isRecoveryAttempted(context: Context, dbName: String): Boolean =
        prefs(context).getBoolean("${INTERNAL_PREFIX}recovery_attempted_$dbName", false)

    /** v2.2.1: Marque esta biblioteca como si hubiera intentado recuperar un archivo erróneo. */
    fun markRecoveryAttempted(context: Context, dbName: String) {
        prefs(context).edit().putBoolean("${INTERNAL_PREFIX}recovery_attempted_$dbName", true).apply()
    }

    /** Archivar colección de claves de etiquetas de eventos(Excluir el estado interno del prefijo __). */
    private fun archiveFlags(context: Context): Set<String> =
        prefs(context).all.filter { (key, value) -> !key.startsWith(INTERNAL_PREFIX) && value == true }.keys
}
