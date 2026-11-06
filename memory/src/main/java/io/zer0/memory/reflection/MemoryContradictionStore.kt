package io.zer0.memory.reflection

import io.zer0.common.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * P2-32: Persistencia de listas de recuerdos en conflicto(Archivo JSON codificado por alcance + spaceId).
 *
 * [MemoryReflectionRunner] Escrito después de que se detecte una afirmación contradictoria diaria,El centro de memoria lee la pantalla y permite
 * El usuario confirma artículo por artículo/Borrar — Resuelto antes"Registros solo de detección de conflictos"problema de ralentí.
 */
class MemoryContradictionStore(private val file: File) {

    /**
     * v2.6.2(auditoría de memoria): Leer y escribir mutex.
     *
     * La antigua implementación era“leer-reescribir”Y sin cerraduras, sin reemplazo atómico —— Reflexión diaria sobre la escritura y la simultaneidad de la limpieza de la interfaz de usuario
     * Sobrescriba al escritor anterior; la interrupción del proceso también puede dejar la mitad del JSON (luego readAll revierte silenciosamente la lista vacía y se pierden los datos).
     */
    private val mutex = kotlinx.coroutines.sync.Mutex()

    /** Un par de textos de memoria contradictorios. */
    @Serializable
    data class ContradictionPair(val a: String, val b: String)

    @Serializable
    private data class Entry(val key: String, val pairs: List<ContradictionPair>)

    @Serializable
    private data class StoreData(val entries: List<Entry> = emptyList())

    /** Sobrescriba y guarde la lista de conflictos del ámbito + espacio especificado. */
    suspend fun save(scope: String, spaceId: String, pairs: List<Pair<String, String>>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val key = keyOf(scope, spaceId)
            val all = readAll()
            val updated = all.entries.filterNot { it.key == key } +
                Entry(key, pairs.map { (a, b) -> ContradictionPair(a, b) })
            write(StoreData(updated))
        }
    }

    /** Lea la lista de conflictos para el ámbito+espacio especificado. */
    suspend fun list(scope: String, spaceId: String): List<ContradictionPair> = withContext(Dispatchers.IO) {
        readAll().entries.firstOrNull { it.key == keyOf(scope, spaceId) }?.pairs ?: emptyList()
    }

    /**
     * Borrar la lista de conflictos de un determinado alcance en todos los espacios.(Se utiliza cuando el asistente elimina).
     *
     * key en forma de `scope|space`,prensa `scope|` El prefijo coincide exactamente con el segmento de alcance,Otros ámbitos con el mismo prefijo de nombre no se eliminarán por error.
     *
     * @return Número de elementos eliminados
     */
    suspend fun clearScope(scope: String): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            val key = scope.trim()
            if (key.isEmpty()) return@withContext 0
            val all = readAll()
            val prefix = "$key|"
            val remaining = all.entries.filterNot { it.key.startsWith(prefix) }
            val removed = all.entries.size - remaining.size
            if (removed > 0) write(StoreData(remaining))
            removed
        }
    }

    /** Una vez que el usuario confirma, se elimina una de las contradicciones. */
    suspend fun removePair(scope: String, spaceId: String, pair: ContradictionPair) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val all = readAll()
            val key = keyOf(scope, spaceId)
            val entry = all.entries.firstOrNull { it.key == key } ?: return@withContext
            val remaining = entry.pairs.filterNot { it.a == pair.a && it.b == pair.b }
            val updated = all.entries
                .map { if (it.key == key) Entry(key, remaining) else it }
                .filter { it.pairs.isNotEmpty() }
            write(StoreData(updated))
        }
    }

    private fun keyOf(scope: String, spaceId: String): String = "$scope|${spaceId.ifBlank { "default" }}"

    private fun readAll(): StoreData = runCatching {
        if (!file.exists()) StoreData() else AppJson.decodeFromString(StoreData.serializer(), file.readText())
    }.getOrDefault(StoreData())

    private fun write(data: StoreData) {
        file.parentFile?.mkdirs()
        // v2.6.2: escrituras atómicas — Escriba primero el archivo temporal y luego cámbiele el nombre para evitar la interrupción del proceso y dejar la mitad del JSON.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(AppJson.encodeToString(StoreData.serializer(), data))
        if (!tmp.renameTo(file)) {
            // rename falló (el objetivo ya existe)/entre volúmenes)→ Recurra a la escritura directa y limpie archivos temporales
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
