package io.zer0.muse.ui.speech

import android.media.MediaMetadataRetriever
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Detector de duración del audio.
 *
 * Se extrae una interfaz separada para permitir que la capa de almacenamiento se ejecute en una prueba única de JVM pura: máquina real/Utilizado en simulador
 * [MediaMetadataDurationProbe], cambiado a un valor fijo en la prueba.
 */
fun interface VoiceDurationProbe {
    /** @return Duración (milisegundos); devuelto cuando no se puede determinar 0 */
    fun probeMs(file: File): Long
}

/** Usar Android nativo [MediaMetadataRetriever] Duración de la lectura del audio (no se agregan nuevas dependencias). */
object MediaMetadataDurationProbe : VoiceDurationProbe {
    private const val TAG = "MediaMetadataDurationProbe"

    override fun probeMs(file: File): Long {
        if (!file.isFile || file.length() <= 0L) return 0L
        val retriever = MediaMetadataRetriever()
        val duration = runCatching {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
        }.getOrElse { e ->
            // La falla de detección no afecta la disponibilidad del mensaje: la duración son solo metadatos para la visualización de la interfaz de usuario
            Logger.d(TAG, "Error en la detección de la duración del audio: ${e.message}")
            0L
        }
        runCatching { retriever.release() }
        return duration
    }
}

/**
 * Puerto de archivo de voz.
 *
 * capa de herramienta (`send_voice`) sólo usa dos cosas: "ubicación del disco + duración de la detección". Después de extraer la interfaz, no es necesario descargar el archivo real para realizar una prueba única.
 */
interface VoiceMessageArchiver {
    /** ver [VoiceMessageStore.persist]. */
    suspend fun persist(tempFile: File, key: String): String?

    /** ver [VoiceMessageStore.probeDurationMs]. */
    fun probeDurationMs(file: File): Long

    /** ver [VoiceMessageStore.delete]. */
    suspend fun delete(uri: String)
}

/**
 * Almacenamiento de audio de mensajes de voz.
 *
 * TTS El audio sintetizado originalmente cayó en `cacheDir`(El sistema puede limpiarse en cualquier momento y eliminarse después de reproducirlo). Los mensajes de voz requieren
 * Referencia rejugable a largo plazo, por lo que esta clase traslada el producto terminado a `filesDir/voice_messages/`, la base de datos sólo almacena
 * cadena entre comillas (`file://` + Ruta absoluta), consistente con el método de colocar imágenes en el disco.
 *
 * Nomenclatura de archivos [persist] de `key` hash de contenido (prefijo predeterminado de 16 dígitos hexadecimales), por lo que:
 *  - Cuando el mismo texto se sintetiza repetidamente y se selecciona el archivo con el mismo nombre, se sobrescribirá directamente sin acumular múltiples copias de audio equivalente;
 *  - Los nombres de archivos no contienen texto de usuario para evitar que el contenido del chat sea legible en el disco.
 *
 * Todas las operaciones de disco son [Dispatchers.IO] Durante la ejecución, el método en sí se puede llamar de forma segura directamente en la rutina.
 *
 * @param storageDir Directorio de persistencia (la convención es `filesDir/voice_messages/`)
 * @param durationProbe detector de duración; La medición única se puede reemplazar.
 */
class VoiceMessageStore(
    internal val storageDir: File,
    private val durationProbe: VoiceDurationProbe = MediaMetadataDurationProbe,
) : VoiceMessageArchiver {

    /**
     * Mueva el audio temporal sintetizado a un directorio persistente.
     *
     * @param tempFile TTS El archivo temporal escrito (la persona que llama es responsable de su ciclo de vida original)
     * @param key Clave de contenido (identificación del mensaje o hash de texto) que determina el nombre del archivo comprimido
     * @return cita de archivo (`file://` + camino absoluto); falta el archivo fuente/Devuelve cuando está vacío o no se puede realizar el pedido. null
     */
    override suspend fun persist(tempFile: File, key: String): String? = withContext(Dispatchers.IO) {
        if (!tempFile.isFile || tempFile.length() <= 0L) {
            Logger.w(TAG, "Grabación de voz omitida: el archivo fuente no existe o está vacío ${tempFile.name}")
            return@withContext null
        }
        val dir = ensureStorageDir() ?: return@withContext null
        val target = File(dir, fileNameFor(key, tempFile.extension))
        if (sameFile(tempFile, target)) return@withContext FILE_PREFIX + target.absolutePath
        val staging = File(dir, target.name + TMP_SUFFIX)
        runCatching {
            tempFile.copyTo(staging, overwrite = true)
            if (target.exists() && !target.delete()) error("Error al eliminar el archivo antiguo: ${target.name}")
            if (!staging.renameTo(target)) error("El cambio de nombre falló: ${staging.name}")
        }.onFailure { e ->
            Logger.w(TAG, "Error al colocar la voz: ${e.message}")
            runCatching { staging.delete() }
        }
        if (!target.isFile) return@withContext null
        FILE_PREFIX + target.absolutePath
    }

    /**
     * Restaurar referencias a archivos locales.
     *
     * Acepte solo archivos en el directorio de archivo: presione cuando aparezca una ruta fuera de los límites en los datos históricos"No analizable"procesamiento,
     * Ninguna ruta local se transmitirá como voz.
     *
     * @return El archivo no existe/Devuelto cuando la referencia es ilegal. null
     */
    fun resolve(uri: String): File? {
        val raw = uri.trim().removePrefix(FILE_PREFIX).trim()
        if (raw.isEmpty()) return null
        val root = runCatching { storageDir.canonicalFile }.getOrNull()
        val candidate = if (File(raw).isAbsolute) File(raw) else File(storageDir, raw)
        val canonical = runCatching { candidate.canonicalFile }.getOrNull()
        if (root == null || canonical == null || canonical.parentFile != root) return null
        return canonical.takeIf { it.isFile }
    }

    /** Eliminar un único archivo de voz; ignórelo silenciosamente si la referencia es ilegal o el archivo ya no existe. */
    override suspend fun delete(uri: String) {
        withContext(Dispatchers.IO) {
            val file = resolve(uri) ?: return@withContext
            if (!file.delete()) Logger.w(TAG, "Error al eliminar el archivo de voz: ${file.name}")
        }
    }

    /**
     * Limpiar archivos a los que no hace referencia ningún mensaje (archivos huérfanos).
     *
     * La persona que llama recupera la colección de referencia de la base de datos (`MessageDao.getReferencedVoiceUris()`),
     * De esta forma, la capa de almacenamiento no depende de la base de datos y puede verificarse directamente en una única prueba.
     * Restos de la interrupción de la colocación del disco `.tmp` Borren los archivos temporales juntos.
     *
     * @param referencedUris Citas de discursos que todavía se citan en la base de datos.
     * @return Número de archivos eliminados
     */
    suspend fun cleanupOrphans(referencedUris: Collection<String>): Int = withContext(Dispatchers.IO) {
        val files = storageDir.listFiles() ?: return@withContext 0
        val keep = referencedUris.mapNotNull { canonicalPathOf(it) }.toSet()
        files.count { file -> file.isFile && shouldDelete(file, keep) }
    }

    /** La duración de la detección por referencia; devuelve 0 si la referencia no se puede resolver. */
    suspend fun durationMs(uri: String): Long = withContext(Dispatchers.IO) {
        val file = resolve(uri) ?: return@withContext 0L
        probeDurationMs(file)
    }

    /** Duración de la detección por archivo; devuelve 0 en caso de error (no se generará ninguna excepción). */
    override fun probeDurationMs(file: File): Long = runCatching { durationProbe.probeMs(file) }
        .getOrDefault(0L)
        .coerceAtLeast(0L)

    private fun shouldDelete(file: File, keep: Set<String>): Boolean {
        if (file.name.endsWith(TMP_SUFFIX)) return file.delete()
        val path = runCatching { file.canonicalPath }.getOrNull() ?: return false
        return path !in keep && file.delete()
    }

    private fun canonicalPathOf(uri: String): String? = resolve(uri)?.let {
        runCatching { it.canonicalPath }.getOrNull()
    }

    private fun ensureStorageDir(): File? {
        if (!storageDir.exists() && !storageDir.mkdirs()) {
            Logger.w(TAG, "Error al crear el directorio de archivos de voz: ${storageDir.absolutePath}")
            return null
        }
        return storageDir.takeIf { it.isDirectory }
    }

    private fun sameFile(a: File, b: File): Boolean =
        runCatching { a.canonicalPath == b.canonicalPath }.getOrDefault(false)

    /** Nombre del archivo comprimido = key Hash de contenido + extensión de archivo fuente (wav predeterminado). */
    private fun fileNameFor(key: String, extension: String): String {
        val ext = extension.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { DEFAULT_EXTENSION }
        return keyHash(key) + "." + ext
    }

    private fun keyHash(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_CHARS)
    }

    private companion object {
        private const val TAG = "VoiceMessageStore"

        /** Prefijo de cotización y la imagen se coloca en el disco (MessageImageStore) mantiene la misma forma de escribir. */
        private const val FILE_PREFIX = "file://"

        /** El sufijo de transferencia eventualmente cambiará de nombre a documento formal. */
        private const val TMP_SUFFIX = ".tmp"

        /** El secreto cuando no hay extensión (el TTS del sistema escribe wav y la mayoría de los servidores en la nube devuelven mp3). */
        private const val DEFAULT_EXTENSION = "wav"

        /** El hash toma los primeros 16 dígitos hexadecimales (64 bit), suficiente para evitar colisiones dentro del mismo directorio. */
        private const val HASH_CHARS = 16
    }
}
