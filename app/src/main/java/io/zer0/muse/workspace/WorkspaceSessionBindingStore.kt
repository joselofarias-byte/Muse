package io.zer0.muse.workspace

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * La relación vinculante entre sesiones y montajes.
 *
 * Qué directorios deberían verse en diferentes sesiones es una cuestión de hábitos de uso más que de permisos: las sesiones que escriben código no deberían
 * Busque cómodamente en el directorio para escribir novelas en el contexto. Las sesiones no registradas no están restringidas y todos los soportes están disponibles.
 *
 * @param context Contexto de aplicación, utilizado para posicionamiento. filesDir
 */
class WorkspaceSessionBindingStore(private val context: Context) {

    /** Archivo de tabla vinculante: sesión id → Lista de ID de montaje permitidos. */
    private val storeFile: File = File(context.filesDir, "workspace_session_mounts.json")

    private val mutex = Mutex()

    private val json: Json = Json { prettyPrint = true }

    private val serializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

    /**
     * El ID de montaje disponible para una sesión.
     *
     * @param sessionId Identificación de sesión; vacío significa que no hay contexto de sesión (como tareas programadas en segundo plano)
     * @return El conjunto de montajes permitidos;null No indica ninguna restricción
     */
    suspend fun allowedMountIds(sessionId: String?): Set<String>? {
        if (sessionId.isNullOrBlank()) return null
        val table = read()
        return table[sessionId]?.takeIf { it.isNotEmpty() }?.toSet()
    }

    /**
     * Establece las monturas disponibles para una sesión.
     *
     * @param ids Cuando el conjunto está vacío, significa borrar el enlace y volver a ilimitado.
     */
    suspend fun setAllowed(sessionId: String, ids: Set<String>): Unit = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) return@withContext
        mutex.withLock {
            val table = read().toMutableMap()
            if (ids.isEmpty()) table.remove(sessionId) else table[sessionId] = ids.sorted()
            write(table)
        }
    }

    /** Borrar enlaces para una sesión. */
    suspend fun clear(sessionId: String): Unit = setAllowed(sessionId, emptySet())

    /** Retire la montura de los enlaces de todas las sesiones para evitar dejar ID pendientes después de eliminar la montura. */
    suspend fun forgetMount(mountId: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val table = read()
            var changed = false
            val next = table.mapValues { (_, ids) ->
                if (mountId in ids) {
                    changed = true
                    ids - mountId
                } else {
                    ids
                }
            }.filterValues { it.isNotEmpty() }
            if (changed) write(next)
        }
    }

    private fun read(): Map<String, List<String>> {
        if (!storeFile.exists()) return emptyMap()
        val table: Map<String, List<String>> = resultOf {
            val text = storeFile.readText(Charsets.UTF_8)
            if (text.isBlank()) emptyMap() else json.decodeFromString(serializer, text)
        }.onError { message, _ -> Logger.w(TAG, "No se pudo leer el enlace de sesión: $message") }
            .getOrNull() ?: return emptyMap()
        return table
    }

    private fun write(table: Map<String, List<String>>) {
        resultOf {
            val temp = File(storeFile.parentFile, "${storeFile.name}.tmp")
            temp.writeText(json.encodeToString(serializer, table), Charsets.UTF_8)
            if (!temp.renameTo(storeFile)) {
                storeFile.writeText(json.encodeToString(serializer, table), Charsets.UTF_8)
                temp.delete()
            }
        }.onError { message, _ -> Logger.w(TAG, "Error en el enlace de la sesión de escritura: $message") }
    }

    companion object {
        private const val TAG = "WorkspaceSessionBindingStore"
    }
}
