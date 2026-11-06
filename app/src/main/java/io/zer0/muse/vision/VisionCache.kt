package io.zer0.muse.vision

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.security.MessageDigest

/**
 * v1.0.5 Reescritura de ayuda visual:Caché LRU global + compartido entre sesiones.
 *
 * Doble caché:Persistencia de archivos LRU + sidecar en proceso.
 *
 * # Diferencias con versiones anteriores de VisionCache(v1.0.5 reescribir)
 *
 *  1. **Compartir entre sesiones**:Ya no dividir archivos por ID de sesión,Cambie a caché global + archivo sidecar para registrar todas las entradas de la sesión.
 *     La misma imagen y el mismo problema afectan al caché en todas las sesiones.
 *  2. **Las claves de caché están claramente separadas**:Ya no reutilice el complemento de campo visionModelId suffix,Cambiar a [CacheKey] clase de datos,
 *     contiene imageHash / modelId / userRequestHash / promptVersion Cuatro campos independientes.
 *  3. **LRU eliminar**:La memoria caché tiene un límite superior([MAX_MEMORY_ENTRIES] = 256),Exceder lastUsedAt elimina el más largo sin usar.
 *     La versión anterior no será eliminada.,La memoria operativa a largo plazo crece indefinidamente.
 *  4. **Disco de escritura incremental**:put sólo se añade al archivo sidecar cuando,No más reescrituras completas cada vez. cada [FLUSH_INTERVAL] Escriba una vez y vacíe el disco una vez.
 *  5. **Los nombres de los campos son consistentes con el contenido.**:CacheEntry Los nombres de los campos reflejan la verdadera semántica de,Sin hackear.
 *
 * # Diseño de clave de caché
 *
 * key = SHA-256(imageBase64) + modelId + SHA-256(userRequest) + promptVersion
 *
 * cuatro dimensiones:
 *  - imageHash:Misma imagen, diferentes modelos./Compartir problemas
 *  - modelId:Las diferentes descripciones de los modelos visuales pueden ser diferentes.
 *  - userRequestHash:No compartas la misma imagen sino diferentes preguntas.(Respuestas específicas)
 *  - promptVersion:Después de cambiar la palabra del mensaje, incremente el número de versión para invalidar el caché anterior.
 *
 * @param context Solicitud Context,solía conseguir filesDir
 */
class VisionCache(context: Context) {

    private val cacheDir = File(context.applicationContext.filesDir, "vision_cache")
    private val sidecarFile = File(cacheDir, "global_cache.json")
    private val mutex = Mutex()

    /**
     * caché de memoria:intercambio global,LRU eliminado.
     *
     * key = [CacheKey.toString()],value = Descripción visual + marca de tiempo lastUsedAt
     */
    private val memoryCache = mutableMapOf<String, CacheEntry>()

    /** Entradas incrementales que se vaciarán(put Agregar cuando,FLUSH_INTERVAL Escribir discos en lotes). */
    private val pendingFlush = mutableMapOf<String, CacheEntry>()

    /** El número de transferencias desde la última descarga del disco.,alcanzar [FLUSH_INTERVAL] Desencadene el enrojecimiento. */
    private var putSinceFlush = 0

    @Volatile
    private var loaded = false

    /**
     * entrada de caché.
     *
     * @param imageHash Contenido de la imagen SHA-256
     * @param modelId modelo visual id(no contiene suffix,puro modelId)
     * @param userRequestHash Solicitud de usuario SHA-256 corta hash
     * @param promptVersion Número de versión de palabra rápida
     * @param description descripción visual(no contiene <vision-context> etiqueta)
     * @param createdAt Crear marca de tiempo(milisegundos)
     * @param lastUsedAt Última marca de tiempo utilizada(milisegundos),para la eliminación de LRU
     * @param sessionIds El conjunto de identificadores de sesión que han utilizado esta entrada.(Fácil de limpiar por sesión)
     */
    @Serializable
    data class CacheEntry(
        val imageHash: String,
        val modelId: String,
        val userRequestHash: String,
        val promptVersion: Int,
        val description: String,
        val createdAt: Long,
        var lastUsedAt: Long,
        val sessionIds: List<String> = emptyList(),
    )

    /**
     * Cuatro dimensiones independientes de la clave de caché.
     *
     * Reemplazar versión anterior"Reutilizar el complemento de campo visionModelId suffix"Práctica de HACK.
     */
    data class CacheKey(
        val imageHash: String,
        val modelId: String,
        val userRequestHash: String,
        val promptVersion: Int,
    ) {
        override fun toString(): String = "$imageHash|$modelId|$userRequestHash|v$promptVersion"
    }

    /**
     * Carga diferida de archivos sidecar en la memoria caché.
     *
     * Cargado en la primera llamada,Posteriormente se lee la memoria directamente.
     */
    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return@withLock
            loaded = true
            val entries = loadSidecar()
            entries.forEach { entry ->
                val key = CacheKey(entry.imageHash, entry.modelId, entry.userRequestHash, entry.promptVersion)
                memoryCache[key.toString()] = entry
            }
            Logger.d(TAG, "Caché visual cargado sidecar,total ${entries.size} Artículo")
            // LRU Eliminado al límite superior
            evictIfNeeded()
        }
    }

    /**
     * Obtenga una descripción visual del caché.
     *
     * @param sessionId sesión actual id(Usado para grabar usado session,Fácil de limpiar por sesión)
     * @param imageBase64 fotos base64
     * @param key caché key(cuatro dimensiones)
     * @return Texto de descripción de caché;Regresar en caso de error null
     */
    suspend fun get(sessionId: String, imageBase64: String, key: CacheKey): String? {
        ensureLoaded()
        val imageHash = hash(imageBase64)
        val fullKey = key.copy(imageHash = imageHash)
        return mutex.withLock {
            val entry = memoryCache[fullKey.toString()]
            if (entry != null) {
                entry.lastUsedAt = System.currentTimeMillis()
                Logger.d(TAG, "golpe de caché visual: model=${key.modelId}, promptVer=${key.promptVersion}")
                entry.description
            } else {
                null
            }
        }
    }

    /**
     * Escriba una descripción visual en el caché.
     *
     * @param sessionId sesión actual id
     * @param imageBase64 fotos base64
     * @param key caché key
     * @param description descripción visual(no contiene <vision-context> etiqueta)
     */
    suspend fun put(sessionId: String, imageBase64: String, key: CacheKey, description: String) {
        ensureLoaded()
        val imageHash = hash(imageBase64)
        val fullKey = key.copy(imageHash = imageHash)
        val now = System.currentTimeMillis()
        val entry = CacheEntry(
            imageHash = imageHash,
            modelId = key.modelId,
            userRequestHash = key.userRequestHash,
            promptVersion = key.promptVersion,
            description = description,
            createdAt = now,
            lastUsedAt = now,
            sessionIds = listOf(sessionId),
        )
        mutex.withLock {
            // Fusionar sessionIds: si la entrada ya existe,Fusionar colecciones de sesiones
            val existing = memoryCache[fullKey.toString()]
            val mergedSessions = if (existing != null) {
                (existing.sessionIds + sessionId).distinct()
            } else {
                listOf(sessionId)
            }
            val finalEntry = entry.copy(sessionIds = mergedSessions)
            memoryCache[fullKey.toString()] = finalEntry
            pendingFlush[fullKey.toString()] = finalEntry
            putSinceFlush++
            if (putSinceFlush >= FLUSH_INTERVAL) {
                flushToDiskLocked()
            }
            evictIfNeeded()
        }
    }

    /**
     * Borre las entradas de caché relacionadas con la sesión especificada.
     *
     * Se utiliza para limpiar cuando se elimina una sesión. Dado que el caché se comparte entre sesiones,Solo limpiar entradas que solo hayan sido utilizadas por esta sesión,
     * Se conservan las entradas que también se utilizan en otras sesiones.
     */
    suspend fun clearSession(sessionId: String) {
        ensureLoaded()
        mutex.withLock {
            val toRemove = memoryCache.entries.filter { (_, entry) ->
                entry.sessionIds.contains(sessionId) && entry.sessionIds.size == 1
            }.map { it.key }
            toRemove.forEach { memoryCache.remove(it) }
            if (toRemove.isNotEmpty()) {
                // reescritura completa sidecar(Menos operaciones de eliminación,Se acepta escribir completo)
                flushToDiskLocked(full = true)
                Logger.d(TAG, "limpiar session $sessionId caché visual,Eliminar ${toRemove.size} Artículo")
            }
        }
    }

    /**
     * Borrar todo el caché(memoria + sidecar).
     */
    suspend fun clearAll() {
        mutex.withLock {
            memoryCache.clear()
            pendingFlush.clear()
            putSinceFlush = 0
            sidecarFile.delete()
            Logger.d(TAG, "Todos los cachés visuales borrados")
        }
    }

    /**
     * LRU eliminar:Si la memoria caché excede [MAX_MEMORY_ENTRIES],Presione lastUsedAt para eliminar el más antiguo no utilizado.
     */
    private fun evictIfNeeded() {
        if (memoryCache.size <= MAX_MEMORY_ENTRIES) return
        val toEvict = memoryCache.size - MAX_MEMORY_ENTRIES
        val sorted = memoryCache.entries.sortedBy { it.value.lastUsedAt }
        repeat(toEvict) { idx ->
            val key = sorted[idx].key
            memoryCache.remove(key)
            Logger.d(TAG, "LRU Eliminar entradas de caché visual: $key")
        }
    }

    /**
     * Cepilla el disco:Fusione las entradas incrementales de pendienteFlush en el archivo sidecar.
     *
     * @param full true=reescritura completa(Después de usar clearSession);false=Fusión incremental(Predeterminado)
     */
    private suspend fun flushToDiskLocked(full: Boolean = false) = withContext(Dispatchers.IO) {
        resultOf {
            cacheDir.mkdirs()
            val allEntries = if (full) {
                memoryCache.values.toList()
            } else {
                // incremento:Cargar existente sidecar,fusionarse de nuevo pendingFlush
                val existing = loadSidecar().associateBy { keyOf(it) }
                val merged = existing.toMutableMap()
                pendingFlush.forEach { (k, v) -> merged[k] = v }
                merged.values.toList()
            }
            val text = AppJson.encodeToString(ListSerializer(CacheEntry.serializer()), allEntries)
            val tmp = File(cacheDir, "${sidecarFile.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(sidecarFile)) {
                sidecarFile.writeText(text)
                tmp.delete()
            }
            pendingFlush.clear()
            putSinceFlush = 0
        }.onError { msg, t ->
            Logger.w(TAG, "Falló el caché visual intermitente: $msg", t)
        }
    }

    private fun keyOf(entry: CacheEntry): String = "${entry.imageHash}|${entry.modelId}|${entry.userRequestHash}|v${entry.promptVersion}"

    private suspend fun loadSidecar(): List<CacheEntry> = withContext(Dispatchers.IO) {
        if (!sidecarFile.exists()) return@withContext emptyList()
        resultOf {
            val text = sidecarFile.readText()
            if (text.isBlank()) {
                emptyList()
            } else {
                AppJson.decodeFromString(ListSerializer(CacheEntry.serializer()), text)
            }
        }.onError { msg, t ->
            Logger.w(TAG, "No se pudo cargar el sidecar de caché visual: $msg", t)
        }.getOrNull() ?: emptyList()
    }

    private fun hash(base64: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(base64.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "VisionCache"

        /** Límite de memoria caché(LRU eliminar). */
        private const val MAX_MEMORY_ENTRIES = 256

        /** Cada número de opciones de venta desencadena un color. */
        private const val FLUSH_INTERVAL = 5
    }
}
