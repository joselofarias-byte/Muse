package io.zer0.muse.data.session

import io.zer0.common.Logger
import java.io.File
import java.util.Base64
import java.util.LinkedHashMap

/**
 * v1.134 P1-2: Servicio de almacenamiento de imágenes de mensajes. — base64 ↔ Conversión de ruta de archivo en ambas direcciones.
 *
 * **fondo**:Phase 8.6 iniciar tabla de mensajes [MessageEntity.imageBase64Json] Guarde imágenes directamente como cadenas base64,
 * Base de imagen única 4K64 aprox. 1MB+,Las sesiones largas/sesiones con varias imágenes ampliarán el tamaño de las filas de la tabla de mensajes,
 * Afecta el rendimiento de consultas/índices/copias de seguridad de SQLite.
 *
 * **Transformación**:Este servicio es responsable de colocar base64 en el disco. [storageDir],DB sólo mantener "file://xxx" camino de la forma;
 * Identificar al leer "file://" Devolución de archivo de lectura de prefijo base64,De lo contrario, se devolverán directamente como datos antiguos. base64(compatible con versiones anteriores).
 *
 * **umbral de activación**:Sólo si la longitud base64 > [MIN_LENGTH_TO_PERSIST](Predeterminado 1024) Solo haz el pedido,
 * datos cortos(Como imagen de marcador de posición 1x1)Todavía almacenado en formato base64 DB,Evite la fragmentación de archivos pequeños.
 *
 * **Nomenclatura de archivos**:`{messageId}_{index}.bin`(base64 Texto original,Sin prefijo MIME),
 * Igual que messageId + sobrescritura de índice,Evite la duplicación.
 *
 * **Seguridad del hilo**:Todos los métodos en [Dispatchers.IO] llamar a(Garantizado por quien llama),
 * Sin sincronización interna(La escritura/lectura de un solo mensaje no será simultánea).
 *
 * **Tolerancia al fracaso**:Si la escritura falla, regrese al original. base64(degradar al comportamiento anterior),El error de lectura devuelve una cadena vacía,
 * Evite fallos en el almacenamiento de imágenes bloqueando el flujo principal de la conversación.
 *
 * @param storageDir directorio de persistencia(filesDir/muse_images/)
 */
class MessageImageStore(
    private val storageDir: File,
) {
    /** base64 umbral de longitud,Realizar el pedido sólo si supera este valor(Todavía existen datos breves base64). */
    private val minLengthToPersist: Int = MIN_LENGTH_TO_PERSIST

    /**
     * R-DB-04: Archivo→base64 LRU caché(access-order).
     * Según el número de artículos al mismo tiempo.(MAX_CACHE_ENTRIES)y el número total de bytes(MAX_CACHE_BYTES)eliminar,
     * Evite que se atasquen cientos de imágenes base64 grandes MB(auditoría J-P2).
     * Evite lecturas de disco repetidas para la misma imagen durante el mapeo de la lista de mensajes(N+1).
     */
    private var imageCacheBytes = 0L
    private val imageCache = object : LinkedHashMap<String, String>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            if (size > MAX_CACHE_ENTRIES || imageCacheBytes > MAX_CACHE_BYTES) {
                eldest?.let { imageCacheBytes -= it.value.length }
                true
            } else {
                false
            }
    }

    /**
     * Convertir la lista base64 a forma persistente(ruta u original base64).
     *
     * - base64 longitud ≤ [minLengthToPersist]:Volver como está(guardar DB)
     * - base64 longitud > [minLengthToPersist]:Realizar el pedido,Regresar "file://{absolutePath}"
     * - Ya "file://" prefijo:Volver como está(Ya colocado,evitar la duplicación)
     *
     * Revisión de corrección (2.0 B-04/A-03): Nombre de archivo con sufijo hash de contenido — Herramienta de generación de gráficos paralelos en la misma ronda para el mismo mensaje
     * Al llamar a este método simultáneamente,Los archivos con diferentes contenidos no se sobrescriben entre sí.(antiguo nombre {messageId}_{idx}.bin El camino es el mismo
     * Quien escriba último gana,Dos imageUrls apuntarán al mismo archivo);La escritura repetida del mismo contenido llega al archivo con el mismo nombre.,Cubrirse es inofensivo.
     *
     * @param messageId noticias id(Usado como prefijo de nombre de archivo)
     * @param base64List Lista base64 sin procesar(puede contener data:image/...;base64, prefijo)
     * @return Lista de formularios persistentes(camino o base64)
     */
    fun toPersistable(messageId: String, base64List: List<String>): List<String> {
        if (base64List.isEmpty()) return emptyList()
        return base64List.mapIndexed { idx, value ->
            // ya un camino,Volver como está(evitar la duplicación)
            if (value.startsWith(FILE_PREFIX)) return@mapIndexed value
            // datos cortos,Volver como está
            if (value.length < minLengthToPersist) return@mapIndexed value
            // datos largos,Realizar el pedido
            runCatching {
                // despegar data:image/...;base64, Escribir después del prefijo
                val raw = stripDataUriPrefix(value)
                val file = File(storageDir, "${messageId}_${idx}_${contentHash(raw)}.bin")
                if (!storageDir.exists()) storageDir.mkdirs()
                // B-03: Upstream puede ajustar la salida con 76 caracteres base64,JDK basic Se produce una excepción cuando la decodificación encuentra una nueva línea
                // (Anteriormente, runCatching se rebajó para almacenar la cadena completa en base64. DB);Utilice el decodificador MIME para ignorar las nuevas líneas en su lugar
                file.writeBytes(Base64.getMimeDecoder().decode(raw))
                FILE_PREFIX + file.absolutePath
            }.getOrElse { e ->
                Logger.w(TAG, "Error al colocar la imagen,degradado a base64: ${e.message}")
                value
            }
        }
    }

    /**
     * persistir la forma(camino o base64)Convierta a una lista base64 para uso de la interfaz de usuario.
     *
     * - "file://" prefijo:Leer el archivo vuelve puro. base64(sin datos: prefijo;La persona que llama deletrea el prefijo por sí mismo al renderizar,
     *   Ver MessageBubble.displayImageUris "data:image/png;base64,$it" embalaje)
     * - Otros:Regresar directamente como base64(Compatible con versiones anteriores con datos más antiguos)
     *
     * Revisión de corrección (2.0 C-02): Corregir la inconsistencia del documento con la situación real. — Este método devuelve base64 pura sin prefijo.
     *
     * El error de lectura devuelve una cadena vacía(No lanzar excepción,Evite bloquear la carga de la lista de mensajes).
     */
    fun toBase64List(persistableList: List<String>): List<String> {
        if (persistableList.isEmpty()) return emptyList()
        return persistableList.map { value ->
            if (!value.startsWith(FILE_PREFIX)) return@map value
            val path = value.removePrefix(FILE_PREFIX)
            synchronized(imageCache) {
                imageCache[path]?.let { return@map it }
            }
            val encoded = runCatching {
                val bytes = File(path).readBytes()
                // El archivo almacena el binario decodificado en base64 original.,recodificado como base64
                // Sin datos: prefijo(UI La capa Picasso/Coil, etc. reconocerá automáticamente la pureza base64)
                Base64.getEncoder().encodeToString(bytes)
            }.getOrElse { e ->
                Logger.w(TAG, "Falló la lectura de la imagen: ${e.message}")
                ""
            }
            if (encoded.isNotEmpty()) {
                synchronized(imageCache) {
                    // Deshacer el recuento de bytes del valor anterior(Evite la acumulación repetida al cubrir el mismo camino.),Agregar nuevo valor;
                    // Luego coloque los activadores removeEldestEntry para deducir el mayor eliminado.
                    imageCache.remove(path)?.let { imageCacheBytes -= it.length }
                    imageCacheBytes += encoded.length
                    imageCache[path] = encoded
                }
            }
            encoded
        }
    }

    /**
     * Eliminar todos los archivos de imagen del mensaje especificado(Llamado cuando se elimina un mensaje,Evite archivos huérfanos).
     * El archivo no existe y se ignora silenciosamente.
     *
     * escaneado es `{messageId}_` prefijo, por lo que además de este tipo de ubicación `{messageId}_{idx}_{hash}.bin`,
     * También eliminará el lado web generado para base64 en línea. `{messageId}_inline_{hash}.{ext}`(Ver
     * [io.zer0.muse.web.HostMediaStore])—— Ambos tipos de archivos solo sirven ese mensaje.
     */
    fun deleteByMessageId(messageId: String) {
        runCatching {
            if (!storageDir.exists()) return@runCatching
            val prefix = "${messageId}_"
            storageDir.listFiles { f -> f.isFile && f.name.startsWith(prefix) }
                ?.forEach { f -> runCatching { f.delete() } }
        }.onFailure { e ->
            Logger.w(TAG, "No se pudo eliminar la imagen del mensaje: $messageId", e)
        }
    }

    /** Registre los candidatos antes de que la transacción de recuperación tenga éxito sidecar;Los archivos escritos recientemente no se incluyen en la limpieza de archivos antiguos. */
    fun snapshotStoredFilePaths(): Set<String> {
        return storageDir.listFiles { file -> file.isFile && file.name.endsWith(".bin") }
            ?.map { it.absolutePath }
            ?.toSet()
            .orEmpty()
    }

    /** Solo se eliminan los archivos de imagen que existían antes de la recuperación y a los que ya no se hace referencia en ningún mensaje después de la recuperación. */
    fun deleteSnapshotFilesNotReferenced(snapshot: Set<String>, referencedValues: Set<String>) {
        val referencedPaths = referencedValues
            .filter { it.startsWith(FILE_PREFIX) }
            .map { it.removePrefix(FILE_PREFIX) }
            .toSet()
        val root = runCatching { storageDir.canonicalFile }.getOrNull() ?: return
        snapshot.forEach { path ->
            runCatching {
                val candidate = File(path).canonicalFile
                if (
                    candidate.parentFile == root &&
                    candidate.path !in referencedPaths &&
                    candidate.delete()
                ) {
                    synchronized(imageCache) {
                        imageCache.remove(candidate.path)?.let { imageCacheBytes -= it.length }
                    }
                }
            }.onFailure { error ->
                Logger.w(TAG, "La limpieza de imágenes huérfanas después de que falló la recuperación: ${error.message}")
            }
        }
    }

    /** despegar data:image/...;base64, prefijo,Devuelve una cadena base64 pura. */
    private fun stripDataUriPrefix(value: String): String {
        val commaIdx = value.indexOf(',')
        // data:image/png;base64,xxx forma
        if (value.startsWith("data:") && commaIdx > 0 && commaIdx < 100) {
            return value.substring(commaIdx + 1)
        }
        return value
    }

    /** base64 Hash corto de contenido(Sufijo de nombre de archivo,Evite que la escritura paralela de los mismos mensajes se sobrescriba entre sí;Si el contenido es el mismo, el nombre del archivo será el mismo.,Superposición inofensiva). */
    private fun contentHash(raw: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(8)
    }

    private companion object {
        private const val TAG = "MessageImageStore"

        /** base64 umbral de longitud,Realizar el pedido sólo si supera este valor(aprox. 1KB,corresponder ~750 byte binario). */
        private const val MIN_LENGTH_TO_PERSIST = 1024

        /** prefijo de ruta de archivo,Se utiliza para identificar imágenes que se han comercializado. */
        private const val FILE_PREFIX = "file://"

        /** LRU El número máximo de entradas de caché. */
        private const val MAX_CACHE_ENTRIES = 64

        /** LRU Límite de bytes de caché(aprox. 64MB),Evite que las imágenes base64 grandes ocupen cientos de MB. */
        private const val MAX_CACHE_BYTES = 32L * 1024 * 1024
    }
}
